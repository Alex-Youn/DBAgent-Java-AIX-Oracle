package com.dbagent.monitor;

import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ⑧ 점검 알림 화면용 조회 - 목록(항목별 최신 결과)과 6.4 상세(7일 추이·관련 객체·조치 문구). 전체 작업순서 F8(2026-09-26).
 * 관련 객체는 클릭할 때만 대상 DB를 조회한다(카드 목록은 저장소만 읽음).
 */
@Service
public class CheckDetailService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;

    static final Map<String, String> LABEL = new LinkedHashMap<>();
    static final Map<String, String> ACTION = new HashMap<>();
    static final Map<String, String> QUERY = new HashMap<>();

    static {
        LABEL.put("TABLESPACE", "테이블스페이스");
        LABEL.put("TABLE_SIZE", "테이블 용량");
        LABEL.put("FRA", "FRA 사용률");
        LABEL.put("TEMP", "TEMP 사용률");
        LABEL.put("JOB_FAIL", "스케줄러 잡 실패");
        LABEL.put("INVALID_OBJ", "무효 객체");
        ACTION.put("TABLESPACE", "데이터파일을 추가하거나 자동 확장 MAXSIZE를 늘리세요. 큰 세그먼트의 불필요한 데이터 정리(파티션 삭제·아카이빙)도 검토하세요.");
        ACTION.put("TABLE_SIZE", "보관 주기 정책과 파티셔닝·아카이빙을 검토하고, 삭제 후 공간 회수(SHRINK/MOVE)가 필요한지 확인하세요.");
        ACTION.put("FRA", "RMAN으로 아카이브 로그를 백업·삭제하거나 DB_RECOVERY_FILE_DEST_SIZE를 늘리세요. FRA가 가득 차면 아카이브가 멈춰 DB가 정지합니다.");
        ACTION.put("TEMP", "TEMP를 많이 쓰는 세션·SQL(대량 정렬·해시 조인)을 확인하고, 필요하면 TEMP 파일을 추가하세요.");
        ACTION.put("JOB_FAIL", "최근 실행 이력의 오류 번호와 메시지를 확인하고 잡을 수동 재실행해 원인을 확인하세요.");
        ACTION.put("INVALID_OBJ", "대상 객체를 재컴파일(UTL_RECOMP 또는 ALTER ... COMPILE)하고, 다시 무효가 되면 의존 객체 변경을 확인하세요.");
        QUERY.put("TABLESPACE", "SELECT m.tablespace_name, used_space*block_size, tablespace_size*block_size, used_percent FROM dba_tablespace_usage_metrics m JOIN dba_tablespaces t ... WHERE used_percent >= :warn_pct");
        QUERY.put("TABLE_SIZE", "SELECT owner, segment_name, SUM(bytes) FROM dba_segments WHERE segment_type IN ('TABLE','TABLE PARTITION','TABLE SUBPARTITION') AND owner NOT IN (...) GROUP BY owner, segment_name HAVING SUM(bytes) >= :min_gb");
        QUERY.put("FRA", "SELECT name, space_limit, space_used, space_reclaimable, (space_used-space_reclaimable)/NULLIF(space_limit,0)*100 FROM v$recovery_file_dest WHERE space_limit > 0");
        QUERY.put("TEMP", "SELECT f.tablespace_name, tablespace_size-free_space, max_bytes FROM dba_temp_free_space f JOIN (SELECT tablespace_name, SUM(CASE WHEN autoextensible='YES' THEN GREATEST(maxbytes,bytes) ELSE bytes END) max_bytes FROM dba_temp_files GROUP BY tablespace_name) m ...");
        QUERY.put("JOB_FAIL", "SELECT owner, job_name, status, error#, additional_info FROM dba_scheduler_job_run_details WHERE log_date > SYSDATE - 1 AND status <> 'SUCCEEDED'");
        QUERY.put("INVALID_OBJ", "SELECT owner, object_type, object_name, last_ddl_time FROM dba_objects WHERE status = 'INVALID' AND owner NOT IN (...)");
    }

    private final MonitorStoreService storeService;
    private final OracleConnectionPoolManager poolManager;

    @Value("${dbagent.monitor.check-query-timeout-seconds:30}")
    private int queryTimeoutSeconds;
    @Value("${dbagent.monitor.check-interval-minutes:10}")
    private int intervalMinutes;
    @Value("${dbagent.monitor.check-daily-hour:3}")
    private int dailyHour;

    /** 관련 객체 결과 60초 캐시 - dba_segments 계열은 필터가 있어도 딕셔너리 전체를 스캔하므로(query-performance-reviewer
     *  검토, 2026-09-26) 장애 대응 중 같은 카드를 반복 클릭해도 원본 DB에 다시 보내지 않는다. */
    private static final long RELATED_CACHE_MS = 60000L;
    private final Map<String, Object[]> relatedCache = new java.util.concurrent.ConcurrentHashMap<>();

    public CheckDetailService(MonitorStoreService storeService, OracleConnectionPoolManager poolManager) {
        this.storeService = storeService;
        this.poolManager = poolManager;
    }

    /** ⑧ 목록 - 항목별 최신 결과(OK 행은 마지막 점검 시각용). */
    public Map<String, Object> list(TargetDbConfig target) {
        List<Map<String, Object>> rows = storeService.latestChecks(target.id());
        long lastLight = 0, lastDaily = 0;
        Map<String, Long> lastByType = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            r.put("detail", parse((String) r.get("detail")));
            r.put("label", LABEL.getOrDefault(r.get("checkType"), (String) r.get("checkType")));
            long ts = (Long) r.get("checkTs");
            String type = (String) r.get("checkType");
            lastByType.merge(type, ts, Math::max);
            if (CheckCollector.LIGHT_TYPES.contains(type)) lastLight = Math.max(lastLight, ts);
            else lastDaily = Math.max(lastDaily, ts);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows);
        out.put("lastByType", lastByType);
        out.put("lastLight", lastLight == 0 ? null : lastLight);
        out.put("lastDaily", lastDaily == 0 ? null : lastDaily);
        out.put("intervalMinutes", intervalMinutes);
        out.put("dailyHour", dailyHour);
        return out;
    }

    /** 6.4 상세. */
    public Map<String, Object> detail(TargetDbConfig target, String checkType, String targetName) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> item = storeService.latestCheck(target.id(), checkType, targetName);
        if (item != null) {
            item.put("detail", parse((String) item.get("detail")));
            item.put("label", LABEL.getOrDefault(checkType, checkType));
        }
        out.put("item", item);
        out.put("label", LABEL.getOrDefault(checkType, checkType));
        out.put("action", ACTION.get(checkType));
        out.put("queries", Collections.singletonList(QUERY.get(checkType)));

        // 최근 7일 추이 - 사용률/크기 항목만(일별 최댓값). 테이블은 세그먼트 일 스냅샷.
        long from = System.currentTimeMillis() - 7 * DAY_MS;
        List<Map<String, Object>> trend = new ArrayList<>();
        if ("TABLE_SIZE".equals(checkType) && targetName.contains(".")) {
            String owner = targetName.substring(0, targetName.indexOf('.'));
            String name = targetName.substring(targetName.indexOf('.') + 1);
            trend = storeService.segmentHistory(target.id(), owner, name, from);
        } else if ("TABLESPACE".equals(checkType) || "FRA".equals(checkType) || "TEMP".equals(checkType)) {
            trend = dailyMax(storeService.checkHistory(target.id(), checkType, targetName, from));
        }
        out.put("trend", trend);
        if ("TABLESPACE".equals(checkType) && trend.size() >= 2 && item != null && item.get("value") != null) {
            Map<String, Object> first = trend.get(0), last = trend.get(trend.size() - 1);
            double days = ((Long) last.get("ts") - (Long) first.get("ts")) / (double) DAY_MS;
            double slope = days > 0 ? ((Double) last.get("value") - (Double) first.get("value")) / days : 0;
            if (slope > 0) out.put("daysToFull", Math.round((100 - (Double) item.get("value")) / slope * 10) / 10.0);
        }

        // 관련 객체 - 클릭할 때만 대상 DB 조회(같은 대상 60초 캐시)
        String cacheKey = target.id() + "|" + checkType + "|" + targetName;
        Object[] cached = relatedCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - (Long) cached[0] < RELATED_CACHE_MS) {
            @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) cached[1];
            out.putAll(c);
            out.put("relatedCachedAt", cached[0]);
            return out;
        }
        List<Map<String, Object>> related = new ArrayList<>();
        String relatedQuery = null;
        String relatedError = null;
        try (Connection conn = poolManager.getConnection(target)) {
            switch (checkType) {
                case "TABLESPACE":
                    relatedQuery = "SELECT * FROM (SELECT owner, segment_name, segment_type, ROUND(SUM(bytes)/POWER(1024,3), 2) AS size_gb " +
                            "FROM dba_segments WHERE tablespace_name = ? GROUP BY owner, segment_name, segment_type ORDER BY size_gb DESC) WHERE ROWNUM <= 5";
                    related = query(conn, relatedQuery, targetName);
                    out.put("autoextend", query(conn, "SELECT COUNT(*) AS files, SUM(CASE WHEN autoextensible = 'YES' THEN 1 ELSE 0 END) AS autoextensible " +
                            "FROM dba_data_files WHERE tablespace_name = ?", targetName));
                    break;
                case "TABLE_SIZE":
                    if (targetName.contains(".")) {
                        String owner = targetName.substring(0, targetName.indexOf('.'));
                        String name = targetName.substring(targetName.indexOf('.') + 1);
                        relatedQuery = "SELECT segment_name, segment_type, ROUND(SUM(bytes)/POWER(1024,3), 2) AS size_gb FROM dba_segments " +
                                "WHERE owner = ? AND (segment_name = ? OR segment_name IN (SELECT index_name FROM dba_indexes WHERE table_owner = ? AND table_name = ?)) " +
                                "GROUP BY segment_name, segment_type ORDER BY size_gb DESC";
                        related = query(conn, relatedQuery, owner, name, owner, name);
                    }
                    break;
                case "FRA":
                    relatedQuery = "SELECT file_type, percent_space_used, percent_space_reclaimable, number_of_files FROM v$recovery_area_usage ORDER BY percent_space_used DESC";
                    related = query(conn, relatedQuery);
                    break;
                case "TEMP":
                    relatedQuery = "SELECT * FROM (SELECT u.username, u.session_num AS serial_no, u.sql_id, ROUND(u.blocks * t.block_size / POWER(1024,2), 1) AS used_mb " +
                            "FROM v$tempseg_usage u JOIN dba_tablespaces t ON t.tablespace_name = u.tablespace WHERE u.tablespace = ? ORDER BY used_mb DESC) WHERE ROWNUM <= 10";
                    related = query(conn, relatedQuery, targetName);
                    break;
                case "JOB_FAIL":
                    if (targetName.contains(".")) {
                        relatedQuery = "SELECT * FROM (SELECT status, TO_CHAR(actual_start_date, 'YYYY-MM-DD HH24:MI:SS') AS started, " +
                                "error# AS error_no, SUBSTR(additional_info, 1, 200) AS info FROM dba_scheduler_job_run_details " +
                                "WHERE owner = ? AND job_name = ? ORDER BY log_date DESC) WHERE ROWNUM <= 10";
                        related = query(conn, relatedQuery, targetName.substring(0, targetName.indexOf('.')), targetName.substring(targetName.indexOf('.') + 1));
                    }
                    break;
                default:
                    // 무효 객체·통계: 점검 때 저장한 목록을 그대로 쓴다(딕셔너리를 다시 훑지 않음)
                    break;
            }
        } catch (SQLException e) {
            relatedError = e.getMessage();
        }
        out.put("related", related);
        if (relatedError != null) out.put("relatedError", relatedError);
        if (relatedQuery != null) out.put("queries", java.util.Arrays.asList(QUERY.get(checkType), relatedQuery));
        if (relatedError == null) {
            Map<String, Object> keep = new LinkedHashMap<>();
            for (String k : new String[]{"related", "autoextend", "queries"}) if (out.containsKey(k)) keep.put(k, out.get(k));
            relatedCache.put(cacheKey, new Object[]{System.currentTimeMillis(), keep});
            if (relatedCache.size() > 500) relatedCache.clear();
        }
        return out;
    }

    private List<Map<String, Object>> query(Connection conn, String sql, String... binds) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            for (int i = 0; i < binds.length; i++) ps.setString(i + 1, binds[i]);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int c = 1; c <= md.getColumnCount(); c++) {
                        Object v = rs.getObject(c);
                        m.put(md.getColumnLabel(c).toLowerCase(), v instanceof Number ? ((Number) v).doubleValue() : (v == null ? null : String.valueOf(v)));
                    }
                    rows.add(m);
                }
            }
        }
        return rows;
    }

    /** {ts, value} 목록 → 로컬 날짜별 최댓값 (7일 추이). */
    private static List<Map<String, Object>> dailyMax(List<Map<String, Object>> rows) {
        TreeMap<LocalDate, Double> byDay = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            LocalDate d = Instant.ofEpochMilli((Long) r.get("ts")).atZone(ZoneId.systemDefault()).toLocalDate();
            byDay.merge(d, (Double) r.get("value"), Math::max);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        byDay.forEach((d, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
            m.put("value", v);
            out.add(m);
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object parse(String json) {
        if (json == null) return null;
        try {
            return JSON.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
