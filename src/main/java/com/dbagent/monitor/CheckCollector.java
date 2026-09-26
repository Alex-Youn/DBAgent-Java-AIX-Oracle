package com.dbagent.monitor;

import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * ⑧ 점검 알림 수집기 - 설계문서 `대시보드 UI 개선 설계.md` 5장 ⑧, 전체 작업순서 F8(2026-09-26) → mon_check_result.
 *
 * <ul>
 *   <li>가벼운 항목(10분): 테이블스페이스(97/98%), FRA(미설정 DB 제외), TEMP(autoextend MAXSIZE 기준), 스케줄러 잡 실패(24시간).</li>
 *   <li>무거운 항목(1일, check-daily-hour 기본 새벽 3시 + 앱 기동 직후 1회): 테이블 용량(dba_segments 1회 조회로 세그먼트
 *       스냅샷 mon_segment_size도 함께), 무효 객체. 딕셔너리 뷰 스캔이라 무겁고 하루에 크게 바뀌지 않는다.</li>
 *   <li>항목 종류마다 실행할 때 severity='OK', target_name='*' 행 1개를 남긴다 - 카드가 사라진 이유(해소 vs 점검 실패)를
 *       구분하기 위해. 쿼리가 실패하면 severity='ERROR' 행 + 오류 메시지(권한 없음·타임아웃을 "알림 없음"으로 보이게 하지 않음).</li>
 *   <li>임계치 미만이면 항목 행을 남기지 않는다. 시각은 앱 시각 epoch ms(다른 mon_* 테이블과 같음).</li>
 * </ul>
 */
@Service
public class CheckCollector {

    private static final Logger log = LoggerFactory.getLogger(CheckCollector.class);
    // 4개: 무거운 1일 점검(딕셔너리 풀스캔)이 다른 DB의 10분 점검을 오래 막지 않게(query-performance-reviewer 검토, 2026-09-26)
    private static final ExecutorService CHECK_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "check-collector");
        t.setDaemon(true);
        return t;
    });
    private static final ObjectMapper JSON = new ObjectMapper();

    static final List<String> LIGHT_TYPES = Arrays.asList("TABLESPACE", "FRA", "TEMP", "JOB_FAIL");
    static final List<String> DAILY_TYPES = Arrays.asList("TABLE_SIZE", "INVALID_OBJ");

    private final DatabaseConfigService configService;
    private final OracleConnectionPoolManager poolManager;
    private final MonitorStoreService storeService;

    @Value("${dbagent.monitor.check-query-timeout-seconds:30}")
    private int queryTimeoutSeconds;
    @Value("${dbagent.monitor.check-daily-query-timeout-seconds:120}")
    private int dailyTimeoutSeconds;
    // 커넥션 읽기 타임아웃(전역) - 쿼리 타임아웃이 이보다 길면 실제로는 이 값에서 네트워크 오류로 끊겨 "DB 접속 실패"처럼 보인다.
    // 전역 값은 건드리지 않고 1일 점검 타임아웃을 이보다 5초 짧게 맞춘다(더 길게 필요하면 두 값을 같이 올린다).
    @Value("${dbagent.oracle.read-timeout-ms:60000}")
    private long readTimeoutMs;
    // 잡 실패 점검은 dba_scheduler_job_run_details가 날짜 인덱스 없이 항상 전체를 읽어 10분보다 드물게(기본 30분)
    @Value("${dbagent.monitor.check-job-interval-minutes:30}")
    private int jobIntervalMinutes;
    private final Map<String, Long> lastJobCheck = new java.util.concurrent.ConcurrentHashMap<>();
    @Value("${dbagent.monitor.check-tablespace-warn-pct:97}")
    private double tsWarn;
    @Value("${dbagent.monitor.check-tablespace-crit-pct:98}")
    private double tsCrit;
    @Value("${dbagent.monitor.check-table-size-warn-gb:30}")
    private double tableWarnGb;
    @Value("${dbagent.monitor.check-table-size-crit-gb:40}")
    private double tableCritGb;
    @Value("${dbagent.monitor.check-segment-snapshot-min-gb:1}")
    private double snapshotMinGb;
    @Value("${dbagent.monitor.check-fra-warn-pct:80}")
    private double fraWarn;
    @Value("${dbagent.monitor.check-fra-crit-pct:90}")
    private double fraCrit;
    @Value("${dbagent.monitor.check-temp-warn-pct:75}")
    private double tempWarn;
    @Value("${dbagent.monitor.check-temp-crit-pct:90}")
    private double tempCrit;
    @Value("${dbagent.monitor.check-exclude-owners:SYS,SYSTEM,AUDSYS,XDB,MDSYS,CTXSYS}")
    private String excludeOwners;

    public CheckCollector(DatabaseConfigService configService, OracleConnectionPoolManager poolManager,
                          MonitorStoreService storeService) {
        this.configService = configService;
        this.poolManager = poolManager;
        this.storeService = storeService;
    }

    // ================================================================== 스케줄

    @Scheduled(fixedDelayString = "#{${dbagent.monitor.check-interval-minutes:10} * 60000}", initialDelay = 30000L)
    void runLight() {
        forEachOracle(this::checkLight);
    }

    @Scheduled(cron = "0 0 ${dbagent.monitor.check-daily-hour:3} * * *")
    void runDaily() {
        forEachOracle(this::checkDaily);
    }

    /** 기동 직후 1일 항목을 한 번 돌려 빈 화면을 막는다(설계 4.1). 샘플러 backfill과 겹치지 않게 조금 늦춘다. */
    @EventListener(ApplicationReadyEvent.class)
    void dailyOnStartup() {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(45000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            forEachOracle(this::checkDaily);
        }, CHECK_EXECUTOR);
    }

    /** 신규 DB 등록 직후 등 - 한 DB만 바로 점검. */
    public void checkNowAsync(TargetDbConfig target) {
        if (!"oracle".equalsIgnoreCase(target.dbType())) return;
        CompletableFuture.runAsync(() -> { checkLight(target); checkDaily(target); }, CHECK_EXECUTOR);
    }

    private interface PerDb {
        void run(TargetDbConfig target);
    }

    private void forEachOracle(PerDb fn) {
        List<CompletableFuture<Void>> fs = new ArrayList<>();
        for (TargetDbConfig t : configService.listAllInstances()) {
            if ("oracle".equalsIgnoreCase(t.dbType())) fs.add(CompletableFuture.runAsync(() -> fn.run(t), CHECK_EXECUTOR));
        }
        for (CompletableFuture<Void> f : fs) {
            try {
                f.join();
            } catch (Exception ignored) {
                // 각 점검이 스스로 ERROR 행을 남긴다
            }
        }
    }

    // ================================================================== 점검 묶음

    void checkLight(TargetDbConfig target) {
        long ts = System.currentTimeMillis();
        List<Object[]> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target)) {
            run(rows, target, ts, "TABLESPACE", () -> checkTablespace(conn, target, ts, rows));
            run(rows, target, ts, "FRA", () -> checkFra(conn, target, ts, rows));
            run(rows, target, ts, "TEMP", () -> checkTemp(conn, target, ts, rows));
            Long lastJob = lastJobCheck.get(target.id());
            if (lastJob == null || ts - lastJob >= jobIntervalMinutes * 60000L - 5000L) {
                lastJobCheck.put(target.id(), ts);
                run(rows, target, ts, "JOB_FAIL", () -> checkJobs(conn, target, ts, rows));
            }
        } catch (SQLException e) {
            for (String type : LIGHT_TYPES) rows.add(error(target, ts, type, "DB 접속 실패: " + e.getMessage()));
        }
        save(target, rows);
    }

    /** 1일 점검 쿼리 타임아웃 - 커넥션 읽기 타임아웃보다 5초 짧게(최소 10초). */
    int dailyTimeout() {
        int cap = (int) Math.max(10, readTimeoutMs / 1000 - 5);
        return Math.min(dailyTimeoutSeconds, cap);
    }

    void checkDaily(TargetDbConfig target) {
        long ts = System.currentTimeMillis();
        List<Object[]> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target)) {
            run(rows, target, ts, "TABLE_SIZE", () -> checkTableSize(conn, target, ts, rows));
            run(rows, target, ts, "INVALID_OBJ", () -> checkInvalid(conn, target, ts, rows));
        } catch (SQLException e) {
            for (String type : DAILY_TYPES) rows.add(error(target, ts, type, "DB 접속 실패: " + e.getMessage()));
        }
        save(target, rows);
    }

    private interface Check {
        void run() throws SQLException;
    }

    /** 항목 1개 실행: 성공하면 OK('*') 행, 실패하면 ERROR 행(이미 쌓인 그 항목 결과는 버림). */
    private void run(List<Object[]> rows, TargetDbConfig target, long ts, String type, Check check) {
        int before = rows.size();
        try {
            check.run();
            rows.add(row(target, ts, type, "*", "OK", null, null, null));
        } catch (SQLException e) {
            while (rows.size() > before) rows.remove(rows.size() - 1);
            rows.add(error(target, ts, type, e.getMessage()));
            log.debug("check {} failed for db_id={}: {}", type, target.id(), e.toString());
        }
    }

    private void save(TargetDbConfig target, List<Object[]> rows) {
        try {
            storeService.saveCheckResults(rows);
        } catch (Exception e) {
            log.warn("mon_check_result save failed for db_id={}: {}", target.id(), e.toString());
        }
    }

    // ================================================================== 가벼운 항목 (10분)

    private void checkTablespace(Connection conn, TargetDbConfig target, long ts, List<Object[]> rows) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT m.tablespace_name, ROUND(m.used_space * t.block_size / POWER(1024,3), 1) AS used_gb, " +
                        "ROUND(m.tablespace_size * t.block_size / POWER(1024,3), 1) AS max_gb, ROUND(m.used_percent, 1) AS used_pct " +
                        "FROM dba_tablespace_usage_metrics m JOIN dba_tablespaces t ON t.tablespace_name = m.tablespace_name " +
                        "WHERE m.used_percent >= ? ORDER BY m.used_percent DESC")) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setDouble(1, tsWarn);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double pct = rs.getDouble("used_pct");
                    double used = rs.getDouble("used_gb"), max = rs.getDouble("max_gb");
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("usedGb", used);
                    d.put("maxGb", max);
                    d.put("freeGb", Math.round((max - used) * 10) / 10.0);
                    rows.add(row(target, ts, "TABLESPACE", rs.getString(1), pct >= tsCrit ? "CRIT" : "WARN", pct, pct >= tsCrit ? tsCrit : tsWarn, d));
                }
            }
        }
    }

    private void checkFra(Connection conn, TargetDbConfig target, long ts, List<Object[]> rows) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(queryTimeoutSeconds);
            // FRA를 설정하지 않은 DB는 space_limit = 0 → 제외(0으로 나누기 방지, 미설정을 오류·위험으로 보지 않음)
            try (ResultSet rs = st.executeQuery(
                    "SELECT name, ROUND(space_limit / POWER(1024,3), 1) AS limit_gb, ROUND(space_used / POWER(1024,3), 1) AS used_gb, " +
                            "ROUND(space_reclaimable / POWER(1024,3), 1) AS reclaim_gb, " +
                            "ROUND((space_used - space_reclaimable) / NULLIF(space_limit, 0) * 100, 1) AS used_pct " +
                            "FROM v$recovery_file_dest WHERE space_limit > 0")) {
                while (rs.next()) {
                    double pct = rs.getDouble("used_pct");
                    if (pct < fraWarn) continue;
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("limitGb", rs.getDouble("limit_gb"));
                    d.put("usedGb", rs.getDouble("used_gb"));
                    d.put("reclaimableGb", rs.getDouble("reclaim_gb"));
                    rows.add(row(target, ts, "FRA", rs.getString("name"), pct >= fraCrit ? "CRIT" : "WARN", pct, pct >= fraCrit ? fraCrit : fraWarn, d));
                }
            }
        }
    }

    private void checkTemp(Connection conn, TargetDbConfig target, long ts, List<Object[]> rows) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rs = st.executeQuery(
                    "SELECT f.tablespace_name, ROUND((f.tablespace_size - f.free_space) / POWER(1024,3), 1) AS used_gb, " +
                            "ROUND(m.max_bytes / POWER(1024,3), 1) AS max_gb, " +
                            "ROUND((f.tablespace_size - f.free_space) / NULLIF(m.max_bytes, 0) * 100, 1) AS used_pct " +
                            "FROM dba_temp_free_space f JOIN (SELECT tablespace_name, " +
                            "SUM(CASE WHEN autoextensible = 'YES' THEN GREATEST(maxbytes, bytes) ELSE bytes END) AS max_bytes " +
                            "FROM dba_temp_files GROUP BY tablespace_name) m ON m.tablespace_name = f.tablespace_name")) {
                while (rs.next()) {
                    double pct = rs.getDouble("used_pct");
                    if (pct < tempWarn) continue;
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("usedGb", rs.getDouble("used_gb"));
                    d.put("maxGb", rs.getDouble("max_gb"));
                    rows.add(row(target, ts, "TEMP", rs.getString(1), pct >= tempCrit ? "CRIT" : "WARN", pct, pct >= tempCrit ? tempCrit : tempWarn, d));
                }
            }
        }
    }

    /** 최근 24시간 실패가 있는 잡 - 최근 2회 실행이 모두 실패면 위험(2회 연속), 아니면 주의. */
    private void checkJobs(Connection conn, TargetDbConfig target, long ts, List<Object[]> rows) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rs = st.executeQuery(
                    "SELECT owner, job_name, fail_cnt, last1, last2, last_err, last_info, TO_CHAR(last_fail, 'YYYY-MM-DD HH24:MI:SS') AS last_fail FROM (" +
                            "SELECT owner, job_name, " +
                            "SUM(CASE WHEN status <> 'SUCCEEDED' THEN 1 ELSE 0 END) OVER (PARTITION BY owner, job_name) AS fail_cnt, " +
                            "status AS last1, LEAD(status) OVER (PARTITION BY owner, job_name ORDER BY log_date DESC) AS last2, " +
                            "error# AS last_err, SUBSTR(additional_info, 1, 300) AS last_info, " +
                            "MAX(CASE WHEN status <> 'SUCCEEDED' THEN log_date END) OVER (PARTITION BY owner, job_name) AS last_fail, " +
                            "ROW_NUMBER() OVER (PARTITION BY owner, job_name ORDER BY log_date DESC) AS rn " +
                            "FROM dba_scheduler_job_run_details WHERE log_date > SYSDATE - 1" +
                            ") WHERE rn = 1 AND fail_cnt > 0 ORDER BY fail_cnt DESC")) {
                while (rs.next()) {
                    boolean twoInRow = !"SUCCEEDED".equals(rs.getString("last1")) && rs.getString("last2") != null
                            && !"SUCCEEDED".equals(rs.getString("last2"));
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("lastStatus", rs.getString("last1"));
                    d.put("lastError", rs.getObject("last_err"));
                    d.put("lastInfo", rs.getString("last_info"));
                    d.put("lastFail", rs.getString("last_fail"));
                    d.put("consecutive", twoInRow);
                    rows.add(row(target, ts, "JOB_FAIL", rs.getString("owner") + "." + rs.getString("job_name"),
                            twoInRow ? "CRIT" : "WARN", rs.getDouble("fail_cnt"), twoInRow ? 2.0 : 1.0, d));
                }
            }
        }
    }

    // ================================================================== 무거운 항목 (1일)

    private String ownerNotIn() {
        String list = Arrays.stream(excludeOwners.split(",")).map(String::trim).filter(s -> s.matches("[A-Za-z0-9_$#]+"))
                .map(s -> "'" + s.toUpperCase() + "'").collect(Collectors.joining(","));
        return list.isEmpty() ? "" : " AND owner NOT IN (" + list + ") ";
    }

    /** 테이블 용량 + 세그먼트 크기 일 스냅샷 - dba_segments는 한 번만 읽는다. */
    private void checkTableSize(Connection conn, TargetDbConfig target, long ts, List<Object[]> rows) throws SQLException {
        long snapDate = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<Object[]> snap = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT owner, segment_name, SUM(bytes) AS size_bytes FROM dba_segments " +
                        "WHERE segment_type IN ('TABLE','TABLE PARTITION','TABLE SUBPARTITION')" + ownerNotIn() +
                        "GROUP BY owner, segment_name HAVING SUM(bytes) >= ? * POWER(1024,3) ORDER BY size_bytes DESC")) {
            ps.setQueryTimeout(dailyTimeout());
            ps.setDouble(1, Math.min(snapshotMinGb, tableWarnGb));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String owner = rs.getString(1), name = rs.getString(2);
                    long bytes = rs.getLong(3);
                    snap.add(new Object[]{target.id(), snapDate, owner, name, bytes});
                    double gb = Math.round(bytes / Math.pow(1024, 3) * 10) / 10.0;
                    if (gb >= tableWarnGb) {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("sizeGb", gb);
                        rows.add(row(target, ts, "TABLE_SIZE", owner + "." + name, gb >= tableCritGb ? "CRIT" : "WARN", gb,
                                gb >= tableCritGb ? tableCritGb : tableWarnGb, d));
                    }
                }
            }
        }
        storeService.saveSegmentSizes(snap);
    }

    private void checkInvalid(Connection conn, TargetDbConfig target, long ts, List<Object[]> rows) throws SQLException {
        listCheck(conn, target, ts, rows, "INVALID_OBJ", "무효 객체",
                "SELECT owner, object_type, object_name, TO_CHAR(last_ddl_time, 'YYYY-MM-DD HH24:MI') AS last_ddl " +
                        "FROM dba_objects WHERE status = 'INVALID'" + ownerNotIn() + "ORDER BY owner, object_type, object_name",
                new String[]{"owner", "object_type", "object_name", "last_ddl"});
    }

    /** 대상 목록형(무효 객체): 1개 이상이면 확인(INFO) 카드 1장, 목록은 앞 100개만 detail_json에. */
    private void listCheck(Connection conn, TargetDbConfig target, long ts, List<Object[]> rows, String type, String label,
                           String sql, String[] cols) throws SQLException {
        List<Map<String, Object>> items = new ArrayList<>();
        int count = 0;
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(dailyTimeout());
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    count++;
                    if (items.size() < 100) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        for (String c : cols) m.put(c, rs.getObject(c) == null ? null : String.valueOf(rs.getObject(c)));
                        items.add(m);
                    }
                }
            }
        }
        if (count > 0) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("count", count);
            d.put("items", items);
            rows.add(row(target, ts, type, label, "INFO", (double) count, 1.0, d));
        }
    }

    // ================================================================== 행

    private static Object[] row(TargetDbConfig target, long ts, String type, String targetName, String severity,
                                Double value, Double threshold, Map<String, Object> detail) {
        String json = null;
        if (detail != null) {
            json = toJson(detail);
            // 저장 컬럼 4000자 - 자르면 깨진 JSON이 되므로 목록(items)을 줄여 가며 맞춘다
            Object items = detail.get("items");
            while (json != null && json.length() > 3900 && items instanceof List && !((List<?>) items).isEmpty()) {
                List<?> list = (List<?>) items;
                items = new ArrayList<>(list.subList(0, list.size() / 2));
                detail.put("items", items);
                detail.put("itemsTruncated", true);
                json = toJson(detail);
            }
            if (json != null && json.length() > 3900) json = "{\"truncated\":true}";
        }
        String name = targetName.length() > 261 ? targetName.substring(0, 261) : targetName;
        return new Object[]{target.id(), ts, type, name, severity, value, threshold, json};
    }

    private static String toJson(Map<String, Object> m) {
        try {
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object[] error(TargetDbConfig target, long ts, String type, String message) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("error", message == null ? "" : (message.length() > 500 ? message.substring(0, 500) : message));
        return row(target, ts, type, "*", "ERROR", null, null, d);
    }
}
