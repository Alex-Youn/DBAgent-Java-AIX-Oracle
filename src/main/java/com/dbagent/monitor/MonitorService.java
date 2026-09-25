package com.dbagent.monitor;

import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java port of the Oracle-monitoring routes in api_server.py (session/lock/tablespace/dashboard/etc). */
@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final OracleConnectionPoolManager poolManager;
    private final OracleQueryHelper queryHelper;
    private final InstanceMetricSamplerService metricSamplerService;

    // 코드 리뷰 지적(2026-09-14): 이 값은 SqlQueryService.timeoutSeconds/ExecutionPlanService.timeoutSeconds
    // 처럼 이 코드베이스가 이미 쓰는 관례대로 application.properties로 외부화 - 환경별로(v$lock이 특히
    // 느린 인스턴스 등) 재빌드 없이 조정 가능해야 한다.
    @Value("${dbagent.monitor.lock-query-timeout-seconds:3}")
    private int lockQueryTimeoutSeconds;

    // ASH 시간대별 집계(getAshActivity)는 v$lock류보다 스캔 범위가 넓어(최근 N분 전체 샘플) lock-query-
    // timeout-seconds(3초, 락 조회 전용값)보다 여유를 둔다 - 별도 프로퍼티로 분리.
    @Value("${dbagent.monitor.ash-activity-query-timeout-seconds:10}")
    private int ashActivityQueryTimeoutSeconds;

    // D2(2026-09-25): 선택 구간이 ASH 인메모리 보관 범위를 벗어나면 dba_hist_active_sess_history(AWR)로 보충.
    // Diagnostics Pack이 없는 사이트는 false로 끈다(설계 대시보드 UI 개선 7장 ash.awrFallback).
    @Value("${dbagent.monitor.ash-awr-fallback:true}")
    private boolean ashAwrFallback;

    // AWR이 섞인 구간 조회는 디스크의 dba_hist_active_sess_history를 읽어 인메모리 ASH보다 훨씬 느리다 -
    // 최대 24시간 구간을 10초(ash-activity-query-timeout-seconds)에 끝내기 어렵다(query-performance-reviewer 검토).
    @Value("${dbagent.monitor.ash-awr-query-timeout-seconds:60}")
    private int ashAwrQueryTimeoutSeconds;

    // TM Lock 장애 판정 기준(초) - 이 시간 이상 last_call_et가 흐른 블로킹 TM Holder만 센다. getFailureProb()
    // (장애 판정·장애조치 버튼)와 getActiveAlerts()("Blocking Session 감지" 알림)가 같은 값을 써야 화면마다
    // 판정이 갈리지 않는다(2026-09-25 오케스트레이터 결정으로 하드코딩 60초를 프로퍼티화). application.properties는
    // skip-worktree라 pull로 새 키가 들어가지 않으므로 키가 없으면 기존과 같은 60초로 동작해야 하고, 폐쇄망에서
    // 오타로 잘못 넣어도 앱 기동 자체가 실패하지 않도록 문자열로 받아 직접 검증한다.
    private static final int DEFAULT_TM_HOLDER_LAST_CALL_ET_SECONDS = 60;
    private int tmHolderLastCallEtSeconds = DEFAULT_TM_HOLDER_LAST_CALL_ET_SECONDS;

    @Value("${dbagent.monitor.tm-holder-last-call-et-seconds:60}")
    void setTmHolderLastCallEtSeconds(String value) {
        int seconds;
        try {
            seconds = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            seconds = -1;
        }
        if (seconds < 1) {
            log.warn("dbagent.monitor.tm-holder-last-call-et-seconds='{}' is not a positive integer - using {}s",
                    value, DEFAULT_TM_HOLDER_LAST_CALL_ET_SECONDS);
            seconds = DEFAULT_TM_HOLDER_LAST_CALL_ET_SECONDS;
        }
        this.tmHolderLastCallEtSeconds = seconds;
    }

    public MonitorService(OracleConnectionPoolManager poolManager, OracleQueryHelper queryHelper,
                           InstanceMetricSamplerService metricSamplerService) {
        this.poolManager = poolManager;
        this.queryHelper = queryHelper;
        this.metricSamplerService = metricSamplerService;
    }

    // ---------------------------------------------------------------- tmlock
    public List<Map<String, Object>> getTmLocks(TargetDbConfig target) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            // inst_id 는 더 이상 내려보내지 않는다(2026-09-06 사용자 지시). RAC 환경이라도 gv$ 를
            // 쓰지 않기로 확정했기 때문이다 - 실제 RAC 에서 gv$ 기반 Lock/세션 조회는 인스턴스 간
            // 조정 비용 때문에 성능 저하가 발생한다. 이 쿼리는 원래도 v$ 만 쓰고 inst_id 는 별도
            // 조회(getInstId)로 채워 넣던 값이라, 빼도 조회 방식은 달라지지 않는다.
            // 대신 화면 맨 끝에 OS User(v$session.osuser)를 넣는다 - 어느 OS 계정이 건 세션인지가
            // 실제 조치할 때 훨씬 쓸모 있다.
            String query = "SELECT " +
                    "hl.sid AS holder_sid, hs.serial# AS holder_serial, " +
                    "hp.spid AS holder_spid, NVL(hs.username, 'UNKNOWN') AS holder_username, hl.type AS holder_lock_type, " +
                    "DECODE(hl.lmode, 0, 'None', 1, 'Null', 2, 'Row-S', 3, 'Row-X', 4, 'Share', 5, 'S/Row-X', 6, 'Exclusive', TO_CHAR(hl.lmode)) AS holder_mode, " +
                    "o.object_name AS holder_object_waiting, TO_CHAR(hl.ctime) AS holder_time, " +
                    "TO_CHAR(hs.logon_time, 'YYYY-MM-DD HH24:MI:SS') AS holder_login, hs.status AS holder_status, " +
                    "hs.program AS holder_program, hs.machine AS holder_machine, hs.osuser AS holder_osuser, " +
                    "wl.sid AS waiter_sid, ws.serial# AS waiter_serial, " +
                    "wp.spid AS waiter_spid, NVL(ws.username, 'UNKNOWN') AS waiter_username, wl.type AS waiter_lock_type, " +
                    "DECODE(wl.request, 0, 'None', 1, 'Null', 2, 'Row-S', 3, 'Row-X', 4, 'Share', 5, 'S/Row-X', 6, 'Exclusive', TO_CHAR(wl.request)) AS waiter_mode, " +
                    "o.object_name AS waiter_object_waiting, TO_CHAR(wl.ctime) AS waiter_time, " +
                    "TO_CHAR(ws.logon_time, 'YYYY-MM-DD HH24:MI:SS') AS waiter_login, ws.status AS waiter_status, " +
                    "ws.program AS waiter_program, ws.machine AS waiter_machine, ws.osuser AS waiter_osuser " +
                    "FROM v$lock hl " +
                    "JOIN v$session hs ON hl.sid = hs.sid " +
                    "LEFT JOIN v$process hp ON hs.paddr = hp.addr " +
                    "LEFT JOIN v$lock wl ON hl.id1 = wl.id1 AND hl.id2 = wl.id2 AND wl.request > 0 " +
                    "LEFT JOIN v$session ws ON wl.sid = ws.sid " +
                    "LEFT JOIN v$process wp ON ws.paddr = wp.addr " +
                    "LEFT JOIN dba_objects o ON hl.id1 = o.object_id AND hl.type = 'TM' " +
                    "WHERE hl.block = 1";

            Map<Integer, Map<String, Object>> holdersMap = new LinkedHashMap<>();
            // 사용자 실측: 이 환경 일부 인스턴스에서 v$lock 스캔 자체가 49초까지 걸릴 수 있음(enqueue
            // 해시체인이 김) - 이 쿼리는 그 위에 자기 자신과의 self-join까지 더해져 더 무겁다. Lock
            // Holder/Waiter Tree 화면이 열려있는 동안 반복 호출되므로(2026-09-14), 짧은 타임아웃으로
            // 캡을 걸어 느리면 빨리 실패시킨다 - 이 메서드는 throws SQLException 이라 타임아웃도 평소
            // DB 오류처럼 MonitorController가 그대로 dbError()로 응답한다(별도 폴백 불필요).
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(lockQueryTimeoutSeconds);
                try (ResultSet rs = st.executeQuery(query)) {
                while (rs.next()) {
                    int hSid = rs.getInt("holder_sid");
                    Map<String, Object> holder = holdersMap.computeIfAbsent(hSid, k -> {
                        Map<String, Object> h = new LinkedHashMap<>();
                        try {
                            h.put("sid", hSid);
                            h.put("serial", rs.getObject("holder_serial"));
                            h.put("spid", rs.getString("holder_spid"));
                            h.put("username", rs.getString("holder_username"));
                            h.put("lock_type", rs.getString("holder_lock_type"));
                            h.put("mode", rs.getString("holder_mode"));
                            h.put("object_waiting", rs.getString("holder_object_waiting"));
                            h.put("time", rs.getString("holder_time"));
                            h.put("login", rs.getString("holder_login"));
                            h.put("status", rs.getString("holder_status"));
                            h.put("program", rs.getString("holder_program"));
                            h.put("machine", rs.getString("holder_machine"));
                            h.put("osuser", rs.getString("holder_osuser"));
                            h.put("is_holder", true);
                            h.put("waiters", new ArrayList<Map<String, Object>>());
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                        return h;
                    });

                    Object waiterSid = rs.getObject("waiter_sid");
                    if (waiterSid != null) {
                        Map<String, Object> waiter = new LinkedHashMap<>();
                        waiter.put("sid", waiterSid);
                        waiter.put("serial", rs.getObject("waiter_serial"));
                        waiter.put("spid", rs.getString("waiter_spid"));
                        waiter.put("username", rs.getString("waiter_username"));
                        waiter.put("lock_type", rs.getString("waiter_lock_type"));
                        waiter.put("mode", rs.getString("waiter_mode"));
                        waiter.put("object_waiting", rs.getString("waiter_object_waiting"));
                        waiter.put("time", rs.getString("waiter_time"));
                        waiter.put("login", rs.getString("waiter_login"));
                        waiter.put("status", rs.getString("waiter_status"));
                        waiter.put("program", rs.getString("waiter_program"));
                        waiter.put("machine", rs.getString("waiter_machine"));
                        waiter.put("osuser", rs.getString("waiter_osuser"));
                        waiter.put("is_holder", false);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> waiters = (List<Map<String, Object>>) holder.get("waiters");
                        waiters.add(waiter);
                    }
                }
                }
            }
            return new ArrayList<>(holdersMap.values());
        }
    }

    // ------------------------------------------------------------ erd/schema
    public Map<String, Object> getErdSchema(TargetDbConfig target, String owner, String prefix) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            List<String> tables = new ArrayList<>();
            boolean hasPrefix = prefix != null && !Strings.isBlank(prefix);

            String tableSql = hasPrefix
                    ? "SELECT table_name FROM all_tables WHERE owner = ? AND table_name LIKE ?"
                    : "SELECT table_name FROM all_tables WHERE owner = ?";
            try (PreparedStatement ps = conn.prepareStatement(tableSql)) {
                ps.setString(1, owner);
                if (hasPrefix) ps.setString(2, prefix + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) tables.add(rs.getString(1));
                }
            }

            String fkSqlBase = "SELECT a.table_name as child_table, c.table_name as parent_table " +
                    "FROM all_constraints a " +
                    "JOIN all_constraints c ON a.r_constraint_name = c.constraint_name AND c.owner = ? " +
                    "WHERE a.constraint_type = 'R' AND a.owner = ?";
            String fkSql = hasPrefix ? fkSqlBase + " AND a.table_name LIKE ? AND c.table_name LIKE ?" : fkSqlBase;

            List<Map<String, Object>> relationships = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(fkSql)) {
                ps.setString(1, owner);
                ps.setString(2, owner);
                if (hasPrefix) {
                    ps.setString(3, prefix + "%");
                    ps.setString(4, prefix + "%");
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> rel = new LinkedHashMap<>();
                        rel.put("child", rs.getString(1));
                        rel.put("parent", rs.getString(2));
                        relationships.add(rel);
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("owner", owner);
            result.put("prefix", prefix == null ? "" : prefix);
            result.put("tables", tables);
            result.put("relationships", relationships);
            return result;
        }
    }

    // ---------------------------------------------------------------- session
    public List<Map<String, Object>> getSessions(TargetDbConfig target) throws SQLException {
        String query = "WITH ash_summary AS (" +
                "SELECT session_id, session_serial#, " +
                "ROUND(SUM(CASE WHEN session_state = 'ON CPU' THEN 1 ELSE 0 END) / COUNT(*) * 100) as cpu_pct, " +
                "ROUND(SUM(CASE WHEN session_state = 'WAITING' AND wait_class = 'User I/O' THEN 1 ELSE 0 END) / COUNT(*) * 100) as user_io_pct, " +
                "ROUND(SUM(CASE WHEN session_state = 'WAITING' AND wait_class = 'System I/O' THEN 1 ELSE 0 END) / COUNT(*) * 100) as system_io_pct, " +
                "ROUND(SUM(CASE WHEN session_state = 'WAITING' AND event LIKE 'latch%' THEN 1 ELSE 0 END) / COUNT(*) * 100) as latch_pct, " +
                "ROUND(SUM(CASE WHEN session_state = 'WAITING' AND event LIKE 'enq: TX%' THEN 1 ELSE 0 END) / COUNT(*) * 100) as tx_lock_pct, " +
                "ROUND(SUM(CASE WHEN session_state = 'WAITING' AND event LIKE 'enq: TM%' THEN 1 ELSE 0 END) / COUNT(*) * 100) as tm_lock_pct, " +
                "ROUND(SUM(CASE WHEN session_state = 'WAITING' AND wait_class NOT IN ('User I/O', 'System I/O', 'Idle') " +
                "AND event NOT LIKE 'latch%' AND event NOT LIKE 'enq: TX%' AND event NOT LIKE 'enq: TM%' THEN 1 ELSE 0 END) / COUNT(*) * 100) as other_pct " +
                "FROM v$active_session_history WHERE sample_time >= SYSDATE - 1/24/60 GROUP BY session_id, session_serial#) " +
                "SELECT (SELECT instance_name FROM v$instance) as db_name, s.status, s.sid, s.serial#, p.spid as server_pid, " +
                "s.machine as machine_name, s.username as username, s.program as program_name, " +
                "s.last_call_et as duration_time, " +
                "NVL(a.cpu_pct, 0) || ',' || NVL(a.user_io_pct, 0) || ',' || NVL(a.system_io_pct, 0) || ',' || " +
                "NVL(a.latch_pct, 0) || ',' || NVL(a.tx_lock_pct, 0) || ',' || NVL(a.tm_lock_pct, 0) || ',' || NVL(a.other_pct, 0) as session_wait_pct, " +
                "s.sql_id, s.event as event_name, sq.plan_hash_value, sq.sql_text, s.taddr, s.command, s.osuser, " +
                "TO_CHAR(SYSDATE, 'YYYY-MM-DD HH24:MI:SS') as capture_time " +
                "FROM v$session s " +
                "LEFT JOIN v$process p ON s.paddr = p.addr " +
                "LEFT JOIN ash_summary a ON s.sid = a.session_id AND s.serial# = a.session_serial# " +
                "LEFT JOIN v$sql sq ON s.sql_id = sq.sql_id AND s.sql_child_number = sq.child_number " +
                // 사용자 요청(2026-08-31): 화면에 표시되던 "BACKGROUND"는 실제 계정이 아니라 이 쿼리가
                // username이 NULL인 세션(내부 job/AQ 등 스키마와 무관한 세션)에 붙이던 표시용 문자열이었음
                // - username IS NOT NULL로 아예 제외. 모니터링 계정(databases.json 설정 계정) 자신의
                // 세션도 함께 제외 - 이 계정은 모니터링 전용이라 다른 실사용자가 쓸 일이 없음
                // (monitoringAccountLiteral 참고).
                "WHERE s.type != 'BACKGROUND' AND s.status = 'ACTIVE' " +
                "AND s.username IS NOT NULL AND s.username != " + monitoringAccountLiteral(target) + " " +
                "ORDER BY s.last_call_et DESC";

        List<Map<String, Object>> sessions = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(query)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("db_name", rs.getString("db_name"));
                row.put("status", rs.getString("status"));
                row.put("sid", rs.getObject("sid"));
                row.put("serial", rs.getObject("serial#"));
                row.put("server_pid", rs.getString("server_pid"));
                row.put("machine_name", rs.getString("machine_name"));
                row.put("username", rs.getString("username"));
                row.put("program_name", rs.getString("program_name"));
                row.put("duration_time", rs.getObject("duration_time"));
                row.put("session_wait_pct", rs.getString("session_wait_pct"));
                row.put("sql_id", rs.getString("sql_id"));
                row.put("event_name", rs.getString("event_name"));
                row.put("plan_hash_value", rs.getObject("plan_hash_value"));
                row.put("sql_text", rs.getString("sql_text"));
                row.put("has_transaction", rs.getObject("taddr") != null);
                row.put("command", rs.getObject("command"));
                row.put("osuser", rs.getString("osuser"));
                row.put("capture_time", rs.getString("capture_time"));
                sessions.add(row);
            }
        }
        return sessions;
    }

    // ---------------------------------------------------------------- ash_activity
    // "Current Session" 화면의 Active Session Wait Class 차트(설계문서 `Current Session 매뉴
    // active_session 차트 개편.md` §0/§2/§3, 2026-09-22 1단계 - 원본 DBAgent-Java에서 포팅) 데이터
    // 소스. v$active_session_history를 §3.1 분류 기준(session_state/event 패턴 - WAIT_CLASS 자체는
    // 쓰지 않음. getSessions()/queryActiveTransactions()의 세션별 wait% 분해와 같은 판정 기준이나,
    // 그쪽은 "세션별 최근 1분의 카테고리 비중"이고 이건 "시간 버킷별 카테고리 합계"라 집계 모양이
    // 달라 SQL 조각을 공유하지 않음)으로 시간 버킷×카테고리 집계한다.
    // gv$는 쓰지 않는다(2026-09-06 RAC 원칙 확정 - 접속 인스턴스 기준만, MonitorController 상단 주석 참고).
    public Map<String, Object> getAshActivity(TargetDbConfig target, int rangeMinutes, int stepMinutes) throws SQLException {
        return getAshActivity(target, rangeMinutes, stepMinutes, null, null);
    }

    /**
     * D1(2026-09-25): 시작/종료 시각(DB 시각) 지정 조회 - from이 null이면 예전처럼 "최근 rangeMinutes분".
     * 공용 AshRange를 써서 ASH 보관 범위 밖 구간은 AWR(dba_hist_active_sess_history, 10초 간격)로 보충하고
     * 응답에 source(ash/awr/mixed)를 내려준다. 최대 24시간.
     */
    public Map<String, Object> getAshActivity(TargetDbConfig target, int rangeMinutes, int stepMinutes,
                                              LocalDateTime from, LocalDateTime to) throws SQLException {
        String[] categoryLabels = {"CPU", "Latch", "User I/O", "TX Lock", "Sys I/O", "TM Lock", "Other"};
        // 버그 수정(2026-09-22, code-inspector 점검): 아래 CASE 문은 System I/O를 'system_io'로
        // 분류하는데 이 배열은 'sys_io'를 쓰고 있어 raw 맵에서 절대 안 걸려 Sys I/O가 항상 0으로
        // 표시되고 있었다(6단계 자체 수집 경로는 처음부터 'system_io'로 일치해서 영향 없었음).
        String[] categoryKeys = AshCategories.KEYS7;

        Map<String, Map<String, Long>> raw = new LinkedHashMap<>();
        int cpuCores;
        AshRange.Window window;
        List<String> bucketLabels = null;
        try (Connection conn = poolManager.getConnection(target)) {
            // 0-패딩 버킷 경계는 반드시 DB 서버의 시각(SYSDATE) 기준이어야 한다 - 로컬 실측(2026-09-22,
            // 도커 Oracle 컨테이너는 UTC, 이 JVM은 KST)에서 LocalDateTime.now()(JVM/OS 시계)로 계산했더니
            // 쿼리가 반환한 bucket_label(SYSDATE 기준)과 약 9시간 어긋나 항상 0으로만 채워지는 버그가 있었다.
            LocalDateTime dbNow = AshRange.dbNow(conn);
            if (from == null) {
                // 예전 "최근 N분"과 같은 버킷: 현재 분이 속한 버킷에서 거꾸로 ceil(range/step)개.
                bucketLabels = generateAshBucketLabels(dbNow, rangeMinutes, stepMinutes);
                from = LocalDateTime.parse(bucketLabels.get(0));
                to = null;
            }
            window = AshRange.resolve(conn, from, to, ashAwrFallback);
            if (bucketLabels == null) {
                bucketLabels = generateAshBucketLabels(window.from, window.to, stepMinutes);
            }

            String query = "SELECT TO_CHAR(bucket_time, 'YYYY-MM-DD\"T\"HH24:MI:\"00\"') AS bucket_label, category, SUM(w) AS cnt " +
                    "FROM (" +
                    // sample_time은 TIMESTAMP라 TIMESTAMP - TIMESTAMP는 INTERVAL(NUMBER 연산 불가, ORA-00932)이
                    // 되므로 CAST(... AS DATE)로 맞춘 뒤 계산한다.
                    "SELECT TRUNC(CAST(b.sample_time AS DATE)) + FLOOR((CAST(b.sample_time AS DATE) - TRUNC(CAST(b.sample_time AS DATE))) * 1440 / ?) * (? / 1440) AS bucket_time, " +
                    "b.category, b.w FROM (" +
                    // 7분류 CASE는 60초 샘플러(ash_*)와 반드시 같아야 해서 공용 상수를 쓴다(2026-09-25).
                    AshRange.baseSql(window, "h.sample_time, " + AshCategories.CASE7 + " AS category", "") +
                    ") b) WHERE category IS NOT NULL " +
                    "GROUP BY bucket_time, category " +
                    "ORDER BY bucket_time";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setQueryTimeout(window.useAwr ? ashAwrQueryTimeoutSeconds : ashActivityQueryTimeoutSeconds);
                ps.setInt(1, stepMinutes);
                ps.setInt(2, stepMinutes);
                AshRange.bind(ps, 3, window, null);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        raw.computeIfAbsent(rs.getString("bucket_label"), k -> new LinkedHashMap<>())
                                .put(rs.getString("category"), rs.getLong("cnt"));
                    }
                }
            }
            // 코어 기준선은 60초 샘플러(ash_cpu_cores)와 같은 CpuCores 헬퍼로 읽는다 - NUM_CPU_CORES, 없으면
            // NUM_CPUS(체크리스트 1-1, 2026-09-25). 화면마다 "코어 수"가 갈리지 않게 한 곳으로 모은다.
            cpuCores = CpuCores.query(conn);
        }

        // 0-패딩: 샘플이 전혀 없는 버킷/카테고리도 빠짐없이 0으로 채워 누적 영역 차트가 항상 길이 7의
        // values 배열을 받도록 보장한다(설계문서 §4 데이터 계약의 공백 - 1단계 체크리스트 항목).
        List<Map<String, Object>> series = new ArrayList<>(bucketLabels.size());
        double divisor = stepMinutes * 60.0; // SUM(w) = 버킷 안 샘플이 대표하는 초 합계, / 버킷 초 수 = AAS
        for (String label : bucketLabels) {
            Map<String, Long> counts = raw.getOrDefault(label, Collections.emptyMap());
            List<Double> values = new ArrayList<>(categoryKeys.length);
            for (String key : categoryKeys) {
                long cnt = counts.getOrDefault(key, 0L);
                values.add(Math.round((cnt / divisor) * 100.0) / 100.0);
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("time", label);
            point.put("values", values);
            series.add(point);
        }

        DateTimeFormatter isoFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range_minutes", rangeMinutes);
        result.put("step_minutes", stepMinutes);
        result.put("cpu_cores", cpuCores);
        result.put("categories", Arrays.asList(categoryLabels));
        result.put("series", series);
        result.put("source", window.source());
        result.put("from", window.from.format(isoFmt));
        result.put("to", window.to.format(isoFmt));
        result.put("dbNow", window.dbNow.format(isoFmt));
        return result;
    }

    // ---------------------------------------------------------------- ash_top_sql
    // Top SQL Activity Timeline(설계문서 §8, 3단계 - 2026-09-22, 원본 DBAgent-Java에서 포팅) - Active
    // Session Wait Class 차트 옆에 나란히 배치하는 companion 위젯. Top 5 SQL_ID는 §8.2대로 표시 구간
    // 전체 기준 1회만 산정. 각 SQL의 "지배적인 대기 카테고리"는 §8.1대로 getAshActivity()와 같은 CASE
    // 판정 기준을 SQL_ID 단위로 다시 집계해서 구한다(Sys I/O는 제외 - 특정 SQL_ID에 자연스럽게 귀속되지
    // 않는 백그라운드 프로세스 대기이기 때문, §8.1 참고).
    // `FETCH FIRST n ROWS ONLY`(12c+) 대신 ROWNUM을 쓴다 - 코드베이스 기존 관례가 압도적으로 ROWNUM이고
    // (getSessions 등), 폐쇄망 대상 Oracle 최소 버전이 아직 확인되지 않았다(체크리스트 참고).
    public Map<String, Object> getAshTopSql(TargetDbConfig target, int rangeMinutes, int stepMinutes) throws SQLException {
        String monitoringFilter = AshRange.EXCLUDE_SELF_SQL; // D1: dba_users JOIN 대신 접속 계정 ID 비교(결과 동일)

        List<Map<String, Object>> topSql = new ArrayList<>();
        LocalDateTime dbNow;
        try (Connection conn = poolManager.getConnection(target)) {
            String topSqlQuery = "SELECT sql_id, module FROM (" +
                    "SELECT h.sql_id AS sql_id, MAX(h.module) AS module, COUNT(*) AS cnt " +
                    "FROM v$active_session_history h " +
                    "WHERE h.sample_time >= SYSDATE - (? / 1440) " +
                    "AND h.session_type = 'FOREGROUND' " +
                    "AND h.sql_id IS NOT NULL " +
                    monitoringFilter +
                    "GROUP BY h.sql_id ORDER BY cnt DESC" +
                    ") WHERE ROWNUM <= 5";
            try (PreparedStatement ps = conn.prepareStatement(topSqlQuery)) {
                ps.setQueryTimeout(ashActivityQueryTimeoutSeconds);
                ps.setInt(1, rangeMinutes);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("sql_id", rs.getString("sql_id"));
                        row.put("module", rs.getString("module"));
                        topSql.add(row);
                    }
                }
            }

            if (!topSql.isEmpty()) {
                List<String> sqlIds = new ArrayList<>();
                for (Map<String, Object> row : topSql) sqlIds.add((String) row.get("sql_id"));
                String placeholders = String.join(",", Collections.nCopies(sqlIds.size(), "?"));

                // §8.1 카테고리 판정(Sys I/O 제외) - sql_id별 카테고리 샘플 수를 구해 Java에서 최빈값을 뽑는다.
                Map<String, String> dominantCategory = new LinkedHashMap<>();
                String categoryQuery = "SELECT sql_id, category, cnt FROM (" +
                        "SELECT h.sql_id AS sql_id, " +
                        "CASE " +
                        "WHEN h.session_state = 'ON CPU' THEN 'CPU' " +
                        "WHEN h.event LIKE 'latch%' THEN 'Latch' " +
                        "WHEN h.wait_class = 'User I/O' THEN 'User I/O' " +
                        "WHEN h.event LIKE 'enq: TX%' THEN 'TX Lock' " +
                        "WHEN h.event LIKE 'enq: TM%' THEN 'TM Lock' " +
                        "ELSE NULL END AS category, " +
                        "COUNT(*) AS cnt " +
                        "FROM v$active_session_history h " +
                        "WHERE h.sample_time >= SYSDATE - (? / 1440) " +
                        "AND h.sql_id IN (" + placeholders + ") " +
                        "GROUP BY h.sql_id, CASE " +
                        "WHEN h.session_state = 'ON CPU' THEN 'CPU' " +
                        "WHEN h.event LIKE 'latch%' THEN 'Latch' " +
                        "WHEN h.wait_class = 'User I/O' THEN 'User I/O' " +
                        "WHEN h.event LIKE 'enq: TX%' THEN 'TX Lock' " +
                        "WHEN h.event LIKE 'enq: TM%' THEN 'TM Lock' " +
                        "ELSE NULL END" +
                        ") WHERE category IS NOT NULL ORDER BY sql_id, cnt DESC";
                try (PreparedStatement ps = conn.prepareStatement(categoryQuery)) {
                    ps.setQueryTimeout(ashActivityQueryTimeoutSeconds);
                    int idx = 1;
                    ps.setInt(idx++, rangeMinutes);
                    for (String sqlId : sqlIds) ps.setString(idx++, sqlId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            // ORDER BY sql_id, cnt DESC라 각 sql_id의 첫 행이 최빈 카테고리 - 이미 있으면 건너뜀.
                            dominantCategory.putIfAbsent(rs.getString("sql_id"), rs.getString("category"));
                        }
                    }
                }
                for (Map<String, Object> row : topSql) {
                    String sqlId = (String) row.get("sql_id");
                    // 5개 카테고리(§8.1) 전부 밖인 SQL(예: 표본이 전부 System I/O/Idle뿐)은 Other로 갈음.
                    String category = dominantCategory.getOrDefault(sqlId, "Other");
                    // 오케스트레이터 요청(2026-09-22): label을 SQL_ID 축약형("SQL-BU6BGF")이 아니라
                    // 카테고리명 그대로 표시 - 범례/차트에서 바로 "CPU"/"Latch" 식으로 보이게 한다.
                    row.put("label", category);
                    row.put("category", category);
                }
            }

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT SYSDATE FROM dual")) {
                rs.next();
                dbNow = rs.getTimestamp(1).toLocalDateTime();
            }

            List<String> bucketLabels = generateAshBucketLabels(dbNow, rangeMinutes, stepMinutes);
            List<Map<String, Object>> series = new ArrayList<>(bucketLabels.size());
            double divisor = stepMinutes * 60.0;

            if (topSql.isEmpty()) {
                // 이 구간에 sql_id가 붙은 FOREGROUND 샘플 자체가 없었다는 뜻 - Other도 항상 0.
                for (String label : bucketLabels) {
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("time", label);
                    point.put("values", Collections.singletonList(0.0));
                    series.add(point);
                }
            } else {
                List<String> sqlIds = new ArrayList<>();
                for (Map<String, Object> row : topSql) sqlIds.add((String) row.get("sql_id"));
                String placeholders = String.join(",", Collections.nCopies(sqlIds.size(), "?"));

                String seriesQuery = "SELECT TO_CHAR(bucket_time, 'YYYY-MM-DD\"T\"HH24:MI:\"00\"') AS bucket_label, bucket_key, COUNT(*) AS cnt " +
                        "FROM (" +
                        "SELECT TRUNC(CAST(h.sample_time AS DATE)) + FLOOR((CAST(h.sample_time AS DATE) - TRUNC(CAST(h.sample_time AS DATE))) * 1440 / ?) * (? / 1440) AS bucket_time, " +
                        "CASE WHEN h.sql_id IN (" + placeholders + ") THEN h.sql_id ELSE 'OTHER' END AS bucket_key " +
                        "FROM v$active_session_history h " +
                        "WHERE h.sample_time >= SYSDATE - (? / 1440) " +
                        "AND h.session_type = 'FOREGROUND' " +
                        "AND h.sql_id IS NOT NULL " +
                        monitoringFilter +
                        ") GROUP BY bucket_time, bucket_key ORDER BY bucket_time";

                Map<String, Map<String, Long>> raw = new LinkedHashMap<>();
                try (PreparedStatement ps = conn.prepareStatement(seriesQuery)) {
                    ps.setQueryTimeout(ashActivityQueryTimeoutSeconds);
                    int idx = 1;
                    ps.setInt(idx++, stepMinutes);
                    ps.setInt(idx++, stepMinutes);
                    for (String sqlId : sqlIds) ps.setString(idx++, sqlId);
                    ps.setInt(idx++, rangeMinutes);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            raw.computeIfAbsent(rs.getString("bucket_label"), k -> new LinkedHashMap<>())
                                    .put(rs.getString("bucket_key"), rs.getLong("cnt"));
                        }
                    }
                }

                for (String label : bucketLabels) {
                    Map<String, Long> counts = raw.getOrDefault(label, Collections.emptyMap());
                    List<Double> values = new ArrayList<>(sqlIds.size() + 1);
                    for (String sqlId : sqlIds) {
                        long cnt = counts.getOrDefault(sqlId, 0L);
                        values.add(Math.round((cnt / divisor) * 100.0) / 100.0);
                    }
                    long otherCnt = counts.getOrDefault("OTHER", 0L);
                    values.add(Math.round((otherCnt / divisor) * 100.0) / 100.0);
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("time", label);
                    point.put("values", values);
                    series.add(point);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("range_minutes", rangeMinutes);
            result.put("step_minutes", stepMinutes);
            result.put("sql_categories", topSql);
            result.put("series", series);
            return result;
        }
    }

    // ---------------------------------------------------------------- ash_other_breakdown
    // "Other" 드릴다운(설계문서 §9, 5단계 - 2026-09-22, 원본 DBAgent-Java에서 포팅) - Top SQL 그래프의
    // Other(Top5 밖 전체)를 클릭했을 때, 그 안에 뭉쳐 있던 SQL들의 순위를 다시 펼쳐 보여준다. §9.2
    // 방식대로 이미 프론트가 들고 있는 Top5 SQL_ID 목록을 그대로 제외 목록으로 받는다(서버가 재산정
    // 하지 않음 - 프론트 차트와 항상 같은 Top5 기준을 쓰기 위해). excludeSqlIds는 0~5개 아무 길이나
    // 올 수 있으므로("Top5 미만" 케이스, 체크리스트 5단계 항목) NOT IN 절의 바인드 개수를 항상 실제
    // 길이에 맞춰 동적으로 만든다 - 고정 5개를 가정한 §9.2 예시 쿼리를 그대로 쓰면 Top5가 5개 미만일
    // 때 바인드 개수 불일치로 깨진다.
    public Map<String, Object> getAshOtherBreakdown(TargetDbConfig target, String startTime, String endTime, List<String> excludeSqlIds) throws SQLException {
        String monitoringFilter = AshRange.EXCLUDE_SELF_SQL; // D1: dba_users JOIN 대신 접속 계정 ID 비교(결과 동일)
        String excludeClause = "";
        if (excludeSqlIds != null && !excludeSqlIds.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(excludeSqlIds.size(), "?"));
            excludeClause = "AND h.sql_id NOT IN (" + placeholders + ") ";
        }

        // SUM(cnt)/COUNT(*) OVER ()는 ROW_NUMBER로 상위 10건만 골라내는 바깥 WHERE 필터보다 먼저(같은
        // 뷰 안에서) 계산되므로, 반환되는 10행 각각이 이미 "tail 전체"의 합계/개수를 들고 있다 - 즉
        // 이 한 번의 쿼리로 상위 10건 랭킹과 "표시 안 된 나머지" 집계(§9.3 "이 외 N개 SQL...")를
        // 동시에 구할 수 있어 쿼리를 2번 돌릴 필요가 없다.
        String query = "WITH tail AS (" +
                "SELECT h.sql_id AS sql_id, COUNT(*) AS cnt " +
                "FROM v$active_session_history h " +
                "WHERE h.sample_time BETWEEN TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') " +
                "AND h.session_type = 'FOREGROUND' " +
                "AND h.sql_id IS NOT NULL " +
                excludeClause +
                monitoringFilter +
                "GROUP BY h.sql_id" +
                ") SELECT sql_id, cnt, total_cnt, distinct_count FROM (" +
                "SELECT sql_id, cnt, " +
                "SUM(cnt) OVER () AS total_cnt, " +
                "COUNT(*) OVER () AS distinct_count, " +
                "ROW_NUMBER() OVER (ORDER BY cnt DESC) AS rn " +
                "FROM tail" +
                ") WHERE rn <= 10 ORDER BY rn";

        List<Map<String, Object>> items = new ArrayList<>();
        long totalCnt = 0;
        long distinctCount = 0;
        try (Connection conn = poolManager.getConnection(target)) {
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setQueryTimeout(ashActivityQueryTimeoutSeconds);
                int idx = 1;
                ps.setString(idx++, startTime);
                ps.setString(idx++, endTime);
                if (excludeSqlIds != null) {
                    for (String sqlId : excludeSqlIds) ps.setString(idx++, sqlId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("sql_id", rs.getString("sql_id"));
                        row.put("cnt", rs.getLong("cnt"));
                        items.add(row);
                        totalCnt = rs.getLong("total_cnt");
                        distinctCount = rs.getLong("distinct_count");
                    }
                }
            }

            if (!items.isEmpty()) {
                List<String> shownIds = new ArrayList<>();
                for (Map<String, Object> row : items) shownIds.add((String) row.get("sql_id"));
                String placeholders = String.join(",", Collections.nCopies(shownIds.size(), "?"));

                // §3.1과 같은 판정 기준(Sys I/O 제외, §8.1) - getAshTopSql()의 카테고리 조회와 동일 패턴.
                Map<String, String> dominantCategory = new LinkedHashMap<>();
                String categoryQuery = "SELECT sql_id, category, cnt FROM (" +
                        "SELECT h.sql_id AS sql_id, " +
                        "CASE " +
                        "WHEN h.session_state = 'ON CPU' THEN 'CPU' " +
                        "WHEN h.event LIKE 'latch%' THEN 'Latch' " +
                        "WHEN h.wait_class = 'User I/O' THEN 'User I/O' " +
                        "WHEN h.event LIKE 'enq: TX%' THEN 'TX Lock' " +
                        "WHEN h.event LIKE 'enq: TM%' THEN 'TM Lock' " +
                        "ELSE NULL END AS category, " +
                        "COUNT(*) AS cnt " +
                        "FROM v$active_session_history h " +
                        "WHERE h.sample_time BETWEEN TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') " +
                        "AND h.sql_id IN (" + placeholders + ") " +
                        "GROUP BY h.sql_id, CASE " +
                        "WHEN h.session_state = 'ON CPU' THEN 'CPU' " +
                        "WHEN h.event LIKE 'latch%' THEN 'Latch' " +
                        "WHEN h.wait_class = 'User I/O' THEN 'User I/O' " +
                        "WHEN h.event LIKE 'enq: TX%' THEN 'TX Lock' " +
                        "WHEN h.event LIKE 'enq: TM%' THEN 'TM Lock' " +
                        "ELSE NULL END" +
                        ") WHERE category IS NOT NULL ORDER BY sql_id, cnt DESC";
                try (PreparedStatement ps = conn.prepareStatement(categoryQuery)) {
                    ps.setQueryTimeout(ashActivityQueryTimeoutSeconds);
                    int idx = 1;
                    ps.setString(idx++, startTime);
                    ps.setString(idx++, endTime);
                    for (String sqlId : shownIds) ps.setString(idx++, sqlId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            dominantCategory.putIfAbsent(rs.getString("sql_id"), rs.getString("category"));
                        }
                    }
                }

                long shownCnt = 0;
                for (Map<String, Object> row : items) {
                    String sqlId = (String) row.get("sql_id");
                    long cnt = (Long) row.get("cnt");
                    shownCnt += cnt;
                    // 오케스트레이터 요청(2026-09-22): label을 SQL_ID 축약형이 아니라 카테고리명 그대로
                    // 표시 - getAshTopSql()과 동일 규칙(순위 리스트에서 "SQL-XXXXXX" 대신 "CPU"/"Latch" 식).
                    String category = dominantCategory.getOrDefault(sqlId, "Other");
                    row.put("label", category);
                    row.put("category", category);
                    row.put("pct", totalCnt > 0 ? Math.round((cnt / (double) totalCnt) * 1000.0) / 10.0 : 0.0);
                    row.remove("cnt");
                }
                // §9.3 "이 외 N개 SQL이 약 X AAS를 차지" - 표시된 상위 10건을 뺀 나머지.
                long tailCnt = totalCnt - shownCnt;
                long tailCount = distinctCount - items.size();
                double divisor = windowSeconds(startTime, endTime);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("window_start", startTime);
                result.put("window_end", endTime);
                result.put("other_total_aas", divisor > 0 ? Math.round((totalCnt / divisor) * 100.0) / 100.0 : 0.0);
                result.put("items", items);
                result.put("tail_count", Math.max(0, tailCount));
                result.put("tail_aas", divisor > 0 ? Math.round((Math.max(0, tailCnt) / divisor) * 100.0) / 100.0 : 0.0);
                return result;
            }
        }

        Map<String, Object> emptyResult = new LinkedHashMap<>();
        emptyResult.put("window_start", startTime);
        emptyResult.put("window_end", endTime);
        emptyResult.put("other_total_aas", 0.0);
        emptyResult.put("items", items);
        emptyResult.put("tail_count", 0);
        emptyResult.put("tail_aas", 0.0);
        return emptyResult;
    }

    // startTime/endTime('yyyy-MM-dd"T"HH24:MI' 형식) 사이 초 단위 길이 - ASH 1초 샘플링 기준으로
    // count를 AAS로 정규화할 때 쓴다(getAshOtherBreakdown 전용 - 다른 곳은 고정 step 버킷이라
    // stepMinutes*60을 그대로 쓰면 되지만, 여긴 임의 길이의 드래그 구간이라 직접 계산해야 함).
    private double windowSeconds(String startTime, String endTime) {
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
            LocalDateTime start = LocalDateTime.parse(startTime, fmt);
            LocalDateTime end = LocalDateTime.parse(endTime, fmt);
            return Math.max(1, Duration.between(start, end).getSeconds());
        } catch (Exception e) {
            return 60.0;
        }
    }

    // 0-패딩 버킷 시작/끝 계산 - getAshActivity()/getAshTopSql()이 공유한다(DB의 SYSDATE 기준이어야
    // 하는 이유는 getAshActivity() 쪽 주석 참고 - 로컬 테스트에서 실제로 겪은 시간대 불일치 버그).
    /** [from, to) 구간의 버킷 시작 라벨 - from을 step 단위로 내린 경계부터 to가 속한 버킷까지(D1). */
    private List<String> generateAshBucketLabels(LocalDateTime from, LocalDateTime to, int stepMinutes) {
        DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:00");
        List<String> labels = new ArrayList<>();
        LocalDateTime last = AshRange.floorToStep(to.minusSeconds(1), stepMinutes);
        for (LocalDateTime t = AshRange.floorToStep(from, stepMinutes); !t.isAfter(last); t = t.plusMinutes(stepMinutes)) {
            labels.add(t.format(labelFmt));
        }
        return labels;
    }

    private List<String> generateAshBucketLabels(LocalDateTime dbNow, int rangeMinutes, int stepMinutes) {
        int totalMinutesToday = dbNow.getHour() * 60 + dbNow.getMinute();
        int flooredTotalMinutes = (totalMinutesToday / stepMinutes) * stepMinutes;
        LocalDateTime endBucket = dbNow.toLocalDate().atStartOfDay().plusMinutes(flooredTotalMinutes);
        int bucketCount = (int) Math.ceil(rangeMinutes / (double) stepMinutes);
        LocalDateTime startBucket = endBucket.minusMinutes((long) stepMinutes * (bucketCount - 1));
        DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:00");
        List<String> labels = new ArrayList<>(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            labels.add(startBucket.plusMinutes((long) stepMinutes * i).format(labelFmt));
        }
        return labels;
    }

    // ---------------------------------------------------------------- ash_session_detail
    // Top SQL Activity Timeline 드래그→세션 상세(설계문서 §8.4, 4단계 - 2026-09-22, 원본
    // DBAgent-Java에서 포팅)의 데이터 소스. 기존 getHistorySessions()(/api/history_sessions, "성능
    // 이력 조회" 화면용)를 재사용하지 않는다 - 그 쿼리는 elapsed>=3초 AND exec_count>=100인 "튜닝
    // 후보"만 걸러내는 화면 전용 필터가 있어서, 일반적인 드래그 드릴다운에 쓰면 대부분 빈 결과가
    // 나온다(4단계 착수 전 검토에서 발견). 그래서 같은 필터 없이 구간 내 세션을 SID당 최신 샘플 1건으로
    // 보여주는 전용 쿼리를 새로 둔다. 응답 필드명은 session-list.html이 그대로 기대하는 이름(sid/
    // serial/sql_id/capture_time/duration_time/program_name/username/db_name)과 맞춰, 프론트에서
    // 별도 매핑 없이 기존 showSelectedSessionsPopup()에 바로 넘길 수 있게 한다.
    public List<Map<String, Object>> getAshSessionDetail(TargetDbConfig target, String startTime, String endTime) throws SQLException {
        // getHistorySessions()와 동일한 NVL(sql_exec_start, sample_time)/sample_time 기준 경과시간
        // 계산 - design-advisor 검토(2026-09-22)가 지적한 "SYSDATE 기준으로 계산하면 과거 구간의
        // 경과시간이 부풀려진다"는 결함을 처음부터 피한다.
        String query = "SELECT sid, serial, sql_id, event_name, capture_time, duration_time, program_name, username, db_name FROM (" +
                "SELECT h.session_id AS sid, h.session_serial# AS serial, h.sql_id AS sql_id, " +
                "NVL(h.event, 'ON CPU') AS event_name, " +
                "TO_CHAR(h.sample_time, 'YYYY-MM-DD HH24:MI:SS') AS capture_time, " +
                "ROUND((CAST(h.sample_time AS DATE) - CAST(NVL(h.sql_exec_start, h.sample_time) AS DATE)) * 86400, 2) AS duration_time, " +
                "h.program AS program_name, u.username AS username, " +
                "(SELECT instance_name FROM v$instance) AS db_name, " +
                "ROW_NUMBER() OVER (PARTITION BY h.session_id ORDER BY h.sample_time DESC) AS rn " +
                "FROM v$active_session_history h " +
                "LEFT JOIN dba_users u ON h.user_id = u.user_id " +
                "WHERE h.sample_time BETWEEN TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') " +
                "AND h.session_type = 'FOREGROUND' " +
                "AND (u.username IS NULL OR u.username != " + monitoringAccountLiteral(target) + ")" +
                ") WHERE rn = 1 ORDER BY capture_time DESC";

        List<Map<String, Object>> sessions = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setQueryTimeout(ashActivityQueryTimeoutSeconds);
            ps.setString(1, startTime);
            ps.setString(2, endTime);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sid", rs.getObject("sid"));
                    row.put("serial", rs.getObject("serial"));
                    row.put("sql_id", rs.getString("sql_id"));
                    row.put("event_name", rs.getString("event_name"));
                    row.put("capture_time", rs.getString("capture_time"));
                    Object duration = rs.getObject("duration_time");
                    row.put("duration_time", duration == null ? 0 : Math.max(0, ((Number) duration).doubleValue()));
                    row.put("program_name", rs.getString("program_name"));
                    row.put("username", rs.getString("username"));
                    row.put("db_name", rs.getString("db_name"));
                    sessions.add(row);
                }
            }
        }
        return sessions;
    }

    // ------------------------------------------------------------ session_extra
    // Feeds the "Current Session" screen's Active Transaction / Parallel Session / 2pc Pending
    // Transaction tabs and trend chart. Each piece is independently best-effort: DBA_2PC_PENDING in
    // particular requires a privilege many app accounts won't have, so one missing grant shouldn't
    // blank out the other tabs/lines.
    public Map<String, Object> getSessionExtra(TargetDbConfig target) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("active_transactions", queryActiveTransactions(conn, target));
            result.put("parallel_sessions", queryParallelSessions(conn));
            result.put("pending_2pc", queryPending2pc(conn));
            // 사용자 요청(2026-08-31): Trace 그래프 점(개별 세션)을 Lock Wait 여부로도 색칠하려면 몇 명인지
            // 뿐 아니라 어떤 SID인지가 필요 - 목록으로 바꾸고 카운트는 그 목록의 크기로 계산해 v$lock을
            // 두 번 조회하지 않는다.
            // 사용자 요청(2026-09-14): 추이/Trace 그래프의 Lock Wait 한 계열을 세션 리스트의 Session Wait
            // 막대와 색을 맞추기 위해 TX Lock/TM Lock 두 계열로 쪼갬 - v$lock.type으로 구분(TX=행 락,
            // TM=DML/테이블 락). 그 외 타입(UL 등)은 세션 리스트의 wait% 분해에서도 "기타"로 묶이므로
            // 여기서도 둘 다에서 제외.
            Map<String, List<Object>> lockWaitSids = queryLockWaitSidsByType(conn);
            List<Object> txLockSids = lockWaitSids.get("tx");
            List<Object> tmLockSids = lockWaitSids.get("tm");
            result.put("tx_lock_count", txLockSids.size());
            result.put("tx_lock_sids", txLockSids);
            result.put("tm_lock_count", tmLockSids.size());
            result.put("tm_lock_sids", tmLockSids);
            return result;
        }
    }

    // Column set intentionally mirrors getSessions()'s Active Session query (same ash_summary wait-%
    // breakdown, same v$process/v$sql joins) - Active Transaction is meant to show the identical
    // columns, just scoped to sessions that hold a transaction (v$transaction) instead of status='ACTIVE'.
    private List<Map<String, Object>> queryActiveTransactions(Connection conn, TargetDbConfig target) {
        String query = "WITH ash_summary AS (" +
                "SELECT session_id, session_serial#, " +
                "ROUND(SUM(CASE WHEN session_state = 'ON CPU' THEN 1 ELSE 0 END) / COUNT(*) * 100) as cpu_pct, " +
                "ROUND(SUM(CASE WHEN session_state = 'WAITING' AND wait_class = 'User I/O' THEN 1 ELSE 0 END) / COUNT(*) * 100) as user_io_pct, " +
                "ROUND(SUM(CASE WHEN session_state = 'WAITING' AND wait_class = 'System I/O' THEN 1 ELSE 0 END) / COUNT(*) * 100) as system_io_pct, " +
                "ROUND(SUM(CASE WHEN session_state = 'WAITING' AND event LIKE 'latch%' THEN 1 ELSE 0 END) / COUNT(*) * 100) as latch_pct, " +
                "ROUND(SUM(CASE WHEN session_state = 'WAITING' AND event LIKE 'enq: TX%' THEN 1 ELSE 0 END) / COUNT(*) * 100) as tx_lock_pct, " +
                "ROUND(SUM(CASE WHEN session_state = 'WAITING' AND event LIKE 'enq: TM%' THEN 1 ELSE 0 END) / COUNT(*) * 100) as tm_lock_pct, " +
                "ROUND(SUM(CASE WHEN session_state = 'WAITING' AND wait_class NOT IN ('User I/O', 'System I/O', 'Idle') " +
                "AND event NOT LIKE 'latch%' AND event NOT LIKE 'enq: TX%' AND event NOT LIKE 'enq: TM%' THEN 1 ELSE 0 END) / COUNT(*) * 100) as other_pct " +
                "FROM v$active_session_history WHERE sample_time >= SYSDATE - 1/24/60 GROUP BY session_id, session_serial#) " +
                "SELECT (SELECT instance_name FROM v$instance) as db_name, s.status, s.sid, s.serial#, p.spid as server_pid, " +
                "s.machine as machine_name, s.username as username, s.program as program_name, " +
                "s.last_call_et as duration_time, " +
                "NVL(a.cpu_pct, 0) || ',' || NVL(a.user_io_pct, 0) || ',' || NVL(a.system_io_pct, 0) || ',' || " +
                "NVL(a.latch_pct, 0) || ',' || NVL(a.tx_lock_pct, 0) || ',' || NVL(a.tm_lock_pct, 0) || ',' || NVL(a.other_pct, 0) as session_wait_pct, " +
                "s.sql_id, s.event as event_name, sq.plan_hash_value, sq.sql_text, s.osuser " +
                "FROM v$transaction t " +
                "JOIN v$session s ON s.saddr = t.ses_addr " +
                "LEFT JOIN v$process p ON s.paddr = p.addr " +
                "LEFT JOIN ash_summary a ON s.sid = a.session_id AND s.serial# = a.session_serial# " +
                "LEFT JOIN v$sql sq ON s.sql_id = sq.sql_id AND s.sql_child_number = sq.child_number " +
                // 사용자 요청(2026-08-31): idle-in-transaction(커밋 안 하고 대기 중이라 status가
                // INACTIVE로 바뀐) 세션은 이 탭에서 제외 - Active Session과 동일하게 ACTIVE만 표시.
                // "BACKGROUND" 표시(username NULL)와 모니터링 계정 자신의 세션도 getSessions()와 동일한
                // 이유로 함께 제외 (monitoringAccountLiteral 참고).
                "WHERE s.status = 'ACTIVE' AND s.type != 'BACKGROUND' " +
                "AND s.username IS NOT NULL AND s.username != " + monitoringAccountLiteral(target) + " " +
                "ORDER BY s.sid";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(query)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("db_name", rs.getString("db_name"));
                row.put("status", rs.getString("status"));
                row.put("sid", rs.getObject("sid"));
                row.put("serial", rs.getObject("serial#"));
                row.put("server_pid", rs.getString("server_pid"));
                row.put("machine_name", rs.getString("machine_name"));
                row.put("username", rs.getString("username"));
                row.put("program_name", rs.getString("program_name"));
                row.put("duration_time", rs.getObject("duration_time"));
                row.put("session_wait_pct", rs.getString("session_wait_pct"));
                row.put("sql_id", rs.getString("sql_id"));
                row.put("event_name", rs.getString("event_name"));
                row.put("plan_hash_value", rs.getObject("plan_hash_value"));
                row.put("osuser", rs.getString("osuser"));
                row.put("sql_text", rs.getString("sql_text"));
                rows.add(row);
            }
        } catch (SQLException ignored) {
            // Best-effort - leave the tab empty rather than failing the whole session_extra call.
        }
        return rows;
    }

    private List<Map<String, Object>> queryParallelSessions(Connection conn) {
        String query = "SELECT px.sid, px.serial#, px.qcsid, px.qcserial#, px.server#, px.degree, px.req_degree, " +
                "s.username, s.status, s.program, s.machine " +
                "FROM v$px_session px JOIN v$session s ON s.sid = px.sid AND s.serial# = px.serial# " +
                "ORDER BY px.qcsid, px.server#";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(query)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sid", rs.getObject("sid"));
                row.put("serial", rs.getObject("serial#"));
                row.put("qcsid", rs.getObject("qcsid"));
                row.put("qcserial", rs.getObject("qcserial#"));
                row.put("server_number", rs.getObject("server#"));
                row.put("degree", rs.getObject("degree"));
                row.put("req_degree", rs.getObject("req_degree"));
                row.put("username", rs.getString("username"));
                row.put("status", rs.getString("status"));
                row.put("program", rs.getString("program"));
                row.put("machine", rs.getString("machine"));
                rows.add(row);
            }
        } catch (SQLException ignored) {
        }
        return rows;
    }

    private List<Map<String, Object>> queryPending2pc(Connection conn) {
        String query = "SELECT local_tran_id, global_tran_id, state, mixed, tran_comment, host, " +
                "TO_CHAR(fail_time, 'YYYY-MM-DD HH24:MI:SS') as fail_time, " +
                "TO_CHAR(retry_time, 'YYYY-MM-DD HH24:MI:SS') as retry_time, os_user " +
                "FROM dba_2pc_pending ORDER BY fail_time";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(query)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("local_tran_id", rs.getString("local_tran_id"));
                row.put("global_tran_id", rs.getString("global_tran_id"));
                row.put("state", rs.getString("state"));
                row.put("mixed", rs.getString("mixed"));
                row.put("tran_comment", rs.getString("tran_comment"));
                row.put("host", rs.getString("host"));
                row.put("fail_time", rs.getString("fail_time"));
                row.put("retry_time", rs.getString("retry_time"));
                row.put("os_user", rs.getString("os_user"));
                rows.add(row);
            }
        } catch (SQLException ignored) {
            // Most app accounts won't have SELECT on DBA_2PC_PENDING - empty tab rather than an error.
        }
        return rows;
    }

    // 사용자 실측: 이 환경 일부 인스턴스에서 v$lock 스캔 자체가 49초까지 걸릴 수 있음(enqueue
    // 해시체인이 김). getSessionExtra()는 Current Session이 5초 간격으로 폴링하는 경로라, 이 스캔이
    // 느려지면 세션 리스트 렌더링이 그만큼 밀리고 폴링이 겹쳐 쌓이면서 커넥션 풀도 잠식된다
    // (2026-09-14 성능 조사, non-AIX 쪽과 동일 반영). 쿼리 타임아웃으로 캡을 걸어 느리면 이번
    // 사이클만 빈 리스트로 폴백 - 다음 폴링에서 다시 시도되므로 최신 락 상태를 영구히 놓치지 않는다.
    private Map<String, List<Object>> queryLockWaitSidsByType(Connection conn) {
        List<Object> txSids = new ArrayList<>();
        List<Object> tmSids = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(lockQueryTimeoutSeconds);
            try (ResultSet rs = st.executeQuery(
                    "SELECT DISTINCT sid, type FROM v$lock WHERE request > 0 AND type IN ('TX', 'TM')")) {
                while (rs.next()) {
                    Object sid = rs.getObject("sid");
                    String type = rs.getString("type");
                    if ("TX".equals(type)) {
                        txSids.add(sid);
                    } else if ("TM".equals(type)) {
                        tmSids.add(sid);
                    }
                }
            }
        } catch (SQLException ignored) {
            // 코드 리뷰 지적(2026-09-14): 타임아웃(ORA-01013)이 결과 스트리밍 도중에 발생하면 그때까지
            // rs.next()가 이미 채워 넣은 SID가 남아있어, "이번 사이클은 빈 리스트로 폴백"이라는 위 주석과
            // 다르게 불완전한 락 목록을 완전한 것처럼 돌려주게 된다 - 실패 시엔 항상 완전히 비운다.
            txSids.clear();
            tmSids.clear();
        }
        Map<String, List<Object>> result = new LinkedHashMap<>();
        result.put("tx", txSids);
        result.put("tm", tmSids);
        return result;
    }

    // Fleet Overview's per-instance card metric (replaces a v$lock-based lock-wait count there - 사용자가
    // 실측: 이 환경 일부 인스턴스에서 v$lock 스캔 자체가 49초씩 걸림, enqueue 해시체인이 김). v$sysmetric
    // is a lightweight in-memory metric snapshot, no v$lock scan involved. intsize_csec is measured
    // elapsed centiseconds for the bucket, not always exactly 6000, hence the range instead of equality;
    // 5900~6100 covers both the 60s bucket's normal jitter. Value is per-second, scaled to per-minute
    // since that's the more readable unit for a dashboard card.
    private long queryTxnPerMinute(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT value FROM v$sysmetric WHERE metric_name = 'User Transaction Per Sec' " +
                     "AND intsize_csec BETWEEN 5900 AND 6100")) {
            if (rs.next()) {
                return Math.round(rs.getDouble(1) * 60);
            }
        } catch (SQLException ignored) {
        }
        return 0;
    }

    // ------------------------------------------------------------ tablespace
    public List<Map<String, Object>> getTablespaces(TargetDbConfig target) throws SQLException {
        String query = "SELECT df.tablespace_name, df.status, df.total_mb, " +
                "df.total_mb - NVL(fs.free_mb, 0) as used_mb, NVL(fs.free_mb, 0) as free_mb, " +
                "ROUND(((df.total_mb - NVL(fs.free_mb, 0)) / df.total_mb) * 100, 2) as used_pct " +
                "FROM (SELECT t.tablespace_name, t.status, ROUND(SUM(d.bytes) / 1048576) as total_mb " +
                "      FROM dba_tablespaces t JOIN dba_data_files d ON t.tablespace_name = d.tablespace_name " +
                "      GROUP BY t.tablespace_name, t.status) df " +
                "LEFT JOIN (SELECT tablespace_name, ROUND(SUM(bytes) / 1048576) as free_mb " +
                "           FROM dba_free_space GROUP BY tablespace_name) fs " +
                "ON df.tablespace_name = fs.tablespace_name " +
                "ORDER BY ROUND(((df.total_mb - NVL(fs.free_mb, 0)) / df.total_mb) * 100, 2) DESC, df.tablespace_name";

        List<Map<String, Object>> tablespaces = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(query)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tablespace_name", rs.getString("tablespace_name"));
                row.put("status", rs.getString("status"));
                row.put("total_mb", orZero(rs.getObject("total_mb")));
                row.put("used_mb", orZero(rs.getObject("used_mb")));
                row.put("free_mb", orZero(rs.getObject("free_mb")));
                row.put("used_pct", orZero(rs.getObject("used_pct")));
                tablespaces.add(row);
            }
        }
        return tablespaces;
    }

    // ------------------------------------------------------- tablespace datafiles
    public List<Map<String, Object>> getTablespaceDatafiles(TargetDbConfig target, String tablespaceName) throws SQLException {
        String query = "SELECT df.file_id, df.file_name, df.status, df.autoextensible, " +
                "ROUND(df.bytes / 1048576) as total_mb, " +
                "ROUND(df.bytes / 1048576) - NVL(fs.free_mb, 0) as used_mb, " +
                "NVL(fs.free_mb, 0) as free_mb, " +
                "ROUND(((ROUND(df.bytes / 1048576) - NVL(fs.free_mb, 0)) / ROUND(df.bytes / 1048576)) * 100, 2) as used_pct " +
                "FROM dba_data_files df " +
                "LEFT JOIN (SELECT file_id, ROUND(SUM(bytes) / 1048576) as free_mb FROM dba_free_space " +
                "           WHERE tablespace_name = ? GROUP BY file_id) fs " +
                "ON df.file_id = fs.file_id " +
                "WHERE df.tablespace_name = ? ORDER BY df.file_name";

        List<Map<String, Object>> datafiles = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, tablespaceName);
            ps.setString(2, tablespaceName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("file_id", rs.getInt("file_id"));
                    row.put("file_name", rs.getString("file_name"));
                    row.put("status", rs.getString("status"));
                    row.put("autoextensible", rs.getString("autoextensible"));
                    row.put("total_mb", orZero(rs.getObject("total_mb")));
                    row.put("used_mb", orZero(rs.getObject("used_mb")));
                    row.put("free_mb", orZero(rs.getObject("free_mb")));
                    row.put("used_pct", orZero(rs.getObject("used_pct")));
                    datafiles.add(row);
                }
            }
        }
        return datafiles;
    }

    // -------------------------------------------------------------- dashboard
    public Map<String, Object> getDashboardStats(TargetDbConfig target) throws SQLException {
        try (Connection conn = poolManager.getConnection(target); Statement st = conn.createStatement()) {
            double numCpus = 1;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$osstat WHERE stat_name = 'NUM_CPUS'")) {
                if (rs.next()) numCpus = rs.getDouble(1);
            }
            double cpuUsage = 0;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$sysmetric WHERE metric_name = 'CPU Usage Per Sec'")) {
                if (rs.next()) cpuUsage = rs.getDouble(1);
            }
            double cpu = numCpus > 0 ? Math.round((cpuUsage / numCpus) * 100.0) / 100.0 : 0;

            double sgaBytes = 0;
            try (ResultSet rs = st.executeQuery("SELECT sum(bytes) FROM v$sgastat WHERE 1=1")) {
                if (rs.next()) sgaBytes = rs.getDouble(1);
            }
            double pgaBytes = 0;
            try (ResultSet rs = st.executeQuery("SELECT sum(value) FROM v$pgastat WHERE name = 'total PGA allocated'")) {
                if (rs.next()) pgaBytes = rs.getDouble(1);
            }
            double totalMem = 1;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$osstat WHERE stat_name = 'PHYSICAL_MEMORY_BYTES'")) {
                if (rs.next()) totalMem = rs.getDouble(1);
            }
            double memUtil = totalMem > 0 ? Math.round(((sgaBytes + pgaBytes) / totalMem) * 10000.0) / 100.0 : 0.0;

            int activeSessions = 0;
            // 사용자 요청(2026-08-31)으로 getSessions()가 제외하기 시작한 것과 동일한 대상(NULL
            // username, 모니터링 계정 자신)을 이 카운트에서도 빼서, Active Session 목록의 실제 행
            // 개수와 여기 KPI 숫자가 어긋나지 않게 함.
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM v$session WHERE status = 'ACTIVE' AND type != 'BACKGROUND' " +
                    "AND username IS NOT NULL AND username != " + monitoringAccountLiteral(target))) {
                if (rs.next()) activeSessions = rs.getInt(1);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("cpu", cpu);
            result.put("memory", memUtil);
            result.put("active_sessions", activeSessions);
            return result;
        }
    }

    // ----------------------------------------------------- instance_overview (dashboard v2)
    // oracle-instance-dashboard-spec.md의 PageHeader/KpiTileRow용 - 기존 getDashboardStats()와 별도
    // 엔드포인트로 둔 이유는, 기존 대시보드(레거시)를 건드리지 않고 신규 대시보드를 추가하기로 한
    // 결정(2026-09-15 설계 검토) 때문 - 응답 shape을 공유하면 레거시 프론트가 깨질 위험이 있다.
    public Map<String, Object> getInstanceOverview(TargetDbConfig target) throws SQLException {
        try (Connection conn = poolManager.getConnection(target); Statement st = conn.createStatement()) {
            Map<String, Object> result = new LinkedHashMap<>();

            String status = "MOUNTED";
            double uptimeDays = 0;
            String version = null;
            String instanceName = null;
            try (ResultSet rs = st.executeQuery(
                    "SELECT status, (SYSDATE - startup_time) AS uptime_days, version, instance_name FROM v$instance")) {
                if (rs.next()) {
                    status = rs.getString("status");
                    uptimeDays = rs.getDouble("uptime_days");
                    version = rs.getString("version");
                    instanceName = rs.getString("instance_name");
                }
            }

            String versionCodename = null;
            try (ResultSet rs = st.executeQuery("SELECT banner FROM v$version WHERE banner LIKE 'Oracle Database%'")) {
                if (rs.next()) {
                    Matcher m = VERSION_CODENAME_PATTERN.matcher(rs.getString("banner"));
                    if (m.find()) versionCodename = m.group(1);
                }
            } catch (Exception e) {
                log.warn("v$version lookup failed for db_id={}: {}", target.id(), e.toString());
            }

            // RAC 여부는 gv$instance 행 수로 판단 - Single Instance(XE 등)에서는 이 뷰 자체가 1행만
            // 주거나 권한이 없을 수 있어 조회 실패는 조용히 Single Instance로 취급한다.
            String topology = "Single Instance";
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM gv$instance")) {
                if (rs.next() && rs.getInt(1) > 1) {
                    topology = "RAC (" + rs.getInt(1) + " nodes)";
                }
            } catch (SQLException ignored) {
            }

            double numCpus = 1;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$osstat WHERE stat_name = 'NUM_CPUS'")) {
                if (rs.next()) numCpus = rs.getDouble(1);
            }
            double cpuUsage = 0;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$sysmetric WHERE metric_name = 'CPU Usage Per Sec'")) {
                if (rs.next()) cpuUsage = rs.getDouble(1);
            }
            double cpuPct = numCpus > 0 ? Math.round((cpuUsage / numCpus) * 100.0) / 100.0 : 0;

            double sgaBytes = 0;
            try (ResultSet rs = st.executeQuery("SELECT sum(bytes) FROM v$sgastat")) {
                if (rs.next()) sgaBytes = rs.getDouble(1);
            }
            double pgaBytes = 0;
            try (ResultSet rs = st.executeQuery("SELECT sum(value) FROM v$pgastat WHERE name = 'total PGA allocated'")) {
                if (rs.next()) pgaBytes = rs.getDouble(1);
            }
            double totalMem = 1;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$osstat WHERE stat_name = 'PHYSICAL_MEMORY_BYTES'")) {
                if (rs.next()) totalMem = rs.getDouble(1);
            }
            double memPct = totalMem > 0 ? Math.round(((sgaBytes + pgaBytes) / totalMem) * 10000.0) / 100.0 : 0;

            int activeSessions = 0;
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM v$session WHERE status = 'ACTIVE' AND type != 'BACKGROUND' " +
                    "AND username IS NOT NULL AND username != " + monitoringAccountLiteral(target))) {
                if (rs.next()) activeSessions = rs.getInt(1);
            }
            Integer maxSessions = null;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$parameter WHERE name = 'sessions'")) {
                if (rs.next()) maxSessions = rs.getInt(1);
            } catch (SQLException ignored) {
            }

            double dbTimeAas = 0;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$sysmetric WHERE metric_name = 'Average Active Sessions'")) {
                if (rs.next()) dbTimeAas = rs.getDouble(1);
            }
            double tps = 0;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$sysmetric WHERE metric_name = 'User Transaction Per Sec'")) {
                if (rs.next()) tps = rs.getDouble(1);
            }
            double bufferCacheHitRatio = 0;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$sysmetric WHERE metric_name = 'Buffer Cache Hit Ratio'")) {
                if (rs.next()) bufferCacheHitRatio = rs.getDouble(1);
            }

            result.put("instanceName", instanceName);
            result.put("status", "OPEN".equals(status) ? "정상 운영" : "장애");
            result.put("dbVersion", version);
            result.put("versionCodename", versionCodename);
            result.put("topology", topology);
            result.put("uptimeDays", Math.round(uptimeDays * 100.0) / 100.0);
            result.put("sgaBytes", sgaBytes);
            result.put("pgaBytes", pgaBytes);
            result.put("cpuPct", cpuPct);
            result.put("memPct", memPct);
            result.put("activeSessions", activeSessions);
            result.put("maxSessions", maxSessions);
            result.put("dbTimeAas", Math.round(dbTimeAas * 100.0) / 100.0);
            result.put("tps", Math.round(tps * 100.0) / 100.0);
            result.put("bufferCacheHitRatio", Math.round(bufferCacheHitRatio * 100.0) / 100.0);
            return result;
        }
    }

    // ----------------------------------------------------- active_alerts (dashboard v2)
    // TEMP 사용률/Blocking Session/장시간 쿼리 3종만 다룬다 - 문서 예시에 있던 "일일 백업 정상 완료"는
    // RMAN 카탈로그 의존이라 이 테스트 환경에서 검증할 수 없어 제외(2026-09-16). 필요해지면
    // v$rman_backup_job_details로 추가 가능.
    private static final double TEMP_TABLESPACE_CRITICAL_PCT = 90.0;
    private static final int LONG_RUNNING_QUERY_SECONDS = 300;

    public List<Map<String, Object>> getActiveAlerts(TargetDbConfig target) throws SQLException {
        List<Map<String, Object>> alerts = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target); Statement st = conn.createStatement()) {
            // v$lock 스캔 전용 lockSt에만 타임아웃이 걸려 있고 이 st(temp tablespace 스캔, 장시간 쿼리
            // 카운트)엔 없었다 - 오케스트레이터 실측(2026-09-22, patent_integ_1/2): 다른 API는 전부
            // 정상인데 active_alerts만 계속 pending으로 남아 v2 in-flight 가드(iv2FetchInFlightDbIds)가
            // 영원히 안 풀리고 그 DB 전체 화면이 먹통이 됨 - 네트워크 read timeout(60초)만으로는 부족해서
            // 같은 짧은 타임아웃을 여기도 건다. 타임아웃 나면 SQLException으로 dbError() 응답이 나가
            // 프런트 finally가 가드를 정상적으로 풀어준다(영구 hang보다 "그 알림만 잠깐 비어보임"이 낫다).
            st.setQueryTimeout(lockQueryTimeoutSeconds);
            // v$temp_space_header 대신 dba_temp_free_space 사용(오케스트레이터 지적, 2026-09-22): 전자는
            // 파일 헤더의 익스텐트 하이워터마크 기준이라 TEMP 세그먼트가 반납된 뒤에도 안 줄어드는 등
            // 실제 사용량과 어긋날 수 있다. 후자(allocated_space - free_space)가 실사용량 기준.
            try (ResultSet rs = st.executeQuery(
                    "SELECT tablespace_name, " +
                            "ROUND((GREATEST(allocated_space - free_space, 0) / NULLIF(allocated_space, 0)) * 100, 1) as pct " +
                            "FROM dba_temp_free_space")) {
                while (rs.next()) {
                    double pct = rs.getDouble("pct");
                    if (pct >= TEMP_TABLESPACE_CRITICAL_PCT) {
                        alerts.add(alertItem("critical",
                                rs.getString("tablespace_name") + " 테이블스페이스 " + pct + "%", null));
                    }
                }
            } catch (SQLException ignored) {
            }

            // 일반(TEMP 아닌) 테이블스페이스 97% 알림(오케스트레이터 요청, 2026-09-18) - 처음엔 여기서
            // dba_data_files/dba_free_space를 실시간 조회했는데, 폐쇄망 실운영 규모(테이블스페이스/
            // 데이터파일 수가 이 테스트 환경보다 훨씬 많음)에서 그 딕셔너리 뷰 조인이 무겁게 나와
            // active_alerts 호출(v2 10초 폴링 + 인스턴스 전환마다 즉시 1회)마다 커넥션을 오래 붙잡았고,
            // 그게 인스턴스 전환 지연·기존 대시보드 전환 시 busy·간헐적 접속 실패로 번졌다(오케스트레이터
            // 폐쇄망 실측, 2026-09-18). InstanceMetricSamplerService가 CPU/AAS/Lock과 같은 주기
            // (metric-sample-interval-seconds)로 이미 같은 쿼리를 백그라운드에서 샘플링해 캐시해 두므로,
            // 여기서는 그 결과만 읽는다 - 알림 자체는 최대 그 주기(기본 60초)만큼만 늦게 뜬다.
            alerts.addAll(metricSamplerService.getCachedTablespaceAlerts(target.id()));

            // queryLockWaitSidsByType과 같은 이유로 전용 Statement에 타임아웃을 건다(사용자 실측: 이
            // 환경 일부 인스턴스에서 v$lock 스캔 자체가 49초까지 걸릴 수 있음) - active_alerts는 v2/v3가
            // 10초 간격으로 폴링하므로, 캡 없이 두면 스캔이 느려질 때마다 커넥션을 오래 붙잡아 같은
            // 인스턴스의 다른 요청까지 풀 고갈로 끌고 갈 수 있다.
            try (Statement lockSt = conn.createStatement()) {
                lockSt.setQueryTimeout(lockQueryTimeoutSeconds);
                try (ResultSet rs = lockSt.executeQuery(
                        // dba_objects join 제거(오케스트레이터 실측, 2026-09-22, 11g patent_integ_1/2):
                        // 이 쿼리는 o의 컬럼을 하나도 안 쓰는데(TM lock은 항상 실제 object에 걸리므로
                        // object 존재 확인 자체가 불필요), 딕셔너리 뷰 조인만 더해 11g에서 이 쿼리가
                        // 아예 응답 없이 블로킹되는 원인이었다(SQL*Plus로 직접 실행해도 무응답 확인,
                        // dba_objects join 빼고 실행하면 빠름).
                        "SELECT DISTINCT s.sid FROM v$session s " +
                                "JOIN v$lock l ON s.sid = l.sid " +
                                "WHERE l.type = 'TM' AND s.blocking_session IS NULL AND s.last_call_et >= " + tmHolderLastCallEtSeconds + " " +
                                "AND EXISTS (SELECT 1 FROM v$session w WHERE w.blocking_session = s.sid) AND ROWNUM <= 3")) {
                    while (rs.next()) {
                        String sid = String.valueOf(rs.getInt("sid"));
                        alerts.add(alertItem("warning", "Blocking Session 감지(SID " + sid + ")", sid));
                    }
                }
            } catch (SQLException ignored) {
            }

            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM v$session WHERE status = 'ACTIVE' AND type != 'BACKGROUND' " +
                            "AND username IS NOT NULL AND username != " + monitoringAccountLiteral(target) +
                            " AND last_call_et >= " + LONG_RUNNING_QUERY_SECONDS)) {
                if (rs.next() && rs.getInt(1) > 0) {
                    alerts.add(alertItem("warning", "장시간 실행 쿼리 " + rs.getInt(1) + "건", null));
                }
            } catch (SQLException ignored) {
            }
        }
        return alerts;
    }

    private Map<String, Object> alertItem(String severity, String message, String relatedSid) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("severity", severity);
        item.put("message", message);
        item.put("occurredAt", System.currentTimeMillis());
        item.put("relatedSid", relatedSid);
        return item;
    }

    // ------------------------------------------------------------- top_events
    public List<Map<String, Object>> getTopEvents(TargetDbConfig target) throws SQLException {
        // Dashboard의 "Active Session 목록" 탭 바로 옆 "Top Event 목록" 탭이라, 같은 세션 집합을
        // 기준으로 집계해야 함 - getSessions()와 동일하게 NULL username/모니터링 계정 자신 제외.
        String query = "SELECT NVL(event, 'ON CPU') as event, COUNT(*) as cnt " +
                "FROM v$session WHERE status = 'ACTIVE' AND type != 'BACKGROUND' " +
                "AND username IS NOT NULL AND username != " + monitoringAccountLiteral(target) + " " +
                "GROUP BY event ORDER BY cnt DESC";
        List<Map<String, Object>> events = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(query)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("event", rs.getString(1));
                row.put("count", rs.getObject(2));
                events.add(row);
            }
        }
        return events;
    }

    // ----------------------------------------------------------- kill_session
    public List<Map<String, Object>> killSessions(TargetDbConfig target, List<KillSessionRequest.SessionRef> sessions) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target); Statement st = conn.createStatement()) {
            int instId = queryHelper.getInstId(conn, target);
            for (KillSessionRequest.SessionRef s : sessions) {
                if (s.sid() == null || s.serial() == null) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("sid", s.sid());
                try {
                    st.execute("ALTER SYSTEM KILL SESSION '" + s.sid() + "," + s.serial() + ",@" + instId + "' IMMEDIATE");
                    r.put("status", "killed");
                } catch (SQLException e) {
                    r.put("status", "error");
                    r.put("message", e.getMessage());
                }
                results.add(r);
            }
        }
        return results;
    }

    // -------------------------------------------------------------- relation
    public Map<String, Object> getRelation(TargetDbConfig target, String tableName, String direction) throws SQLException {
        String baseQuery = "select uc.table_name as child_table, uc.constraint_name as fk_name, " +
                "ucc.column_name as child_column, ruc.table_name as parent_table, rucc.column_name as parent_column " +
                "from dba_constraints uc " +
                "join dba_cons_columns ucc on uc.owner = ucc.owner and uc.constraint_name = ucc.constraint_name " +
                "join dba_constraints ruc on uc.r_owner = ruc.owner and uc.r_constraint_name = ruc.constraint_name " +
                "join dba_cons_columns rucc on ruc.owner = rucc.owner and ruc.constraint_name = rucc.constraint_name " +
                "   and ucc.position = rucc.position " +
                "where uc.constraint_type = 'R'";

        boolean uni = "uni".equals(direction);
        String query = baseQuery + (uni ? " and uc.table_name = ?" : " and (ruc.table_name = ? or uc.table_name = ?)");

        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, tableName);
            if (!uni) ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("child_table", rs.getString(1));
                    row.put("fk_name", rs.getString(2));
                    row.put("child_column", rs.getString(3));
                    row.put("parent_table", rs.getString(4));
                    row.put("parent_column", rs.getString(5));
                    results.add(row);
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", results);
        body.put("table_name", tableName);
        return body;
    }

    // --------------------------------------------------------- session_query
    public Map<String, Object> getSessionQuery(TargetDbConfig target, String sidVal, String serialVal, String sqlIdVal) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            Object sSid = sidVal;
            Object sSerial = serialVal;
            String sSqlId = sqlIdVal;
            Integer sChildNumber = null;

            // A caller that already knows the sql_id (a drag-selected snapshot from the Trace graph,
            // History tab or Lock tree - see session-list.html / the clickable-session-row rows) is
            // trusted as-is. v$session below only fills in gaps, never overrides a sql_id the caller
            // already captured - re-deriving it from v$session by sid alone would show whatever that SID
            // happens to be running *right now*, which drifts away from the snapshot within seconds and
            // breaks entirely once Oracle recycles the SID for an unrelated session.
            boolean haveClientSqlId = sqlIdVal != null && !Strings.isBlank(sqlIdVal);
            boolean haveSerial = serialVal != null && !Strings.isBlank(serialVal);

            if (sidVal != null && !Strings.isBlank(sidVal)) {
                // Match serial# too when the caller has one, so a SID that's since been recycled by a
                // different session isn't mistaken for the one that was actually selected.
                String sessionSql = "SELECT sid, serial#, NVL(sql_id, prev_sql_id) as sql_id, NVL(sql_child_number, prev_child_number) as child_number " +
                        "FROM v$session WHERE sid = ?" + (haveSerial ? " AND serial# = ?" : "");
                try (PreparedStatement ps = conn.prepareStatement(sessionSql)) {
                    ps.setString(1, sidVal);
                    if (haveSerial) ps.setString(2, serialVal);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            sSid = rs.getObject(1);
                            sSerial = rs.getObject(2);
                            if (!haveClientSqlId) {
                                String rowSqlId = rs.getString(3);
                                if (rowSqlId != null && !Strings.isBlank(rowSqlId)) {
                                    sSqlId = rowSqlId;
                                    Object childNum = rs.getObject(4);
                                    sChildNumber = childNum == null ? null : ((Number) childNum).intValue();
                                }
                            }
                        } else if (!haveClientSqlId) {
                            // Session already ended, or (when a serial# was given) a different session has
                            // since reused the same SID - fall back to ASH's last known sql_id, matched on
                            // the same sid/serial# pair when available.
                            String ashSql = "SELECT sql_id, sql_child_number FROM (" +
                                    "SELECT sql_id, sql_child_number FROM v$active_session_history " +
                                    "WHERE session_id = ?" + (haveSerial ? " AND session_serial# = ?" : "") +
                                    " AND sql_id IS NOT NULL ORDER BY sample_time DESC) WHERE ROWNUM = 1";
                            try (PreparedStatement ashPs = conn.prepareStatement(ashSql)) {
                                ashPs.setString(1, sidVal);
                                if (haveSerial) ashPs.setString(2, serialVal);
                                try (ResultSet ashRs = ashPs.executeQuery()) {
                                    if (ashRs.next()) {
                                        sSqlId = ashRs.getString(1);
                                        Object childNum = ashRs.getObject(2);
                                        sChildNumber = childNum == null ? null : ((Number) childNum).intValue();
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (sSqlId == null || Strings.isBlank(sSqlId)) {
                return Maps.of("error", "Session is closed and no SQL_ID could be found.");
            }

            String sqlText = "";
            String planText = "";
            Object hashValue = null;
            List<Map<String, Object>> binds = new ArrayList<>();

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT sql_fulltext, hash_value, child_number FROM v$sql WHERE sql_id = ? AND ROWNUM = 1")) {
                ps.setString(1, sSqlId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Object lob = rs.getObject(1);
                        if (lob instanceof Clob) {
                            Clob clob = (Clob) lob;
                            sqlText = clob.getSubString(1, (int) clob.length());
                        } else if (lob != null) {
                            sqlText = rs.getString(1);
                        }
                        hashValue = rs.getObject(2);
                        // Trusting the client's sql_id (above) means we skip v$session/ASH's child_number
                        // lookup, so recover a child cursor here too - otherwise DISPLAY_CURSOR below falls
                        // back to dumping every child plan for the sql_id concatenated together.
                        if (sChildNumber == null) {
                            Object childNum = rs.getObject(3);
                            sChildNumber = childNum == null ? null : ((Number) childNum).intValue();
                        }
                    }
                }
            }

            try {
                String planSql = sChildNumber != null
                        ? "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(?, ?, 'TYPICAL'))"
                        : "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(?, NULL, 'TYPICAL'))";
                try (PreparedStatement ps = conn.prepareStatement(planSql)) {
                    ps.setString(1, sSqlId);
                    if (sChildNumber != null) ps.setInt(2, sChildNumber);
                    StringBuilder sb = new StringBuilder();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String line = rs.getString(1);
                            if (line != null) {
                                if (sb.length() > 0) sb.append('\n');
                                sb.append(line);
                            }
                        }
                    }
                    planText = sb.toString();
                }
            } catch (SQLException e) {
                planText = "Failed to fetch execution plan: " + e.getMessage();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name, position, datatype_string, value_string, TO_CHAR(last_captured, 'YYYY-MM-DD HH24:MI:SS') " +
                            "FROM v$sql_bind_capture WHERE sql_id = ? ORDER BY position")) {
                ps.setString(1, sSqlId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("name", rs.getString(1));
                        b.put("position", rs.getObject(2));
                        b.put("datatype", rs.getString(3));
                        b.put("value", rs.getString(4));
                        b.put("last_captured", rs.getString(5));
                        binds.add(b);
                    }
                }
            } catch (SQLException ignored) {
                // Bind capture is best-effort, same as the Python route.
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sid", sSid);
            result.put("serial", sSerial);
            result.put("sql_id", sSqlId);
            result.put("hash_value", hashValue);
            result.put("sql_fulltext", sqlText);
            result.put("plan_text", planText);
            result.put("binds", binds);
            return result;
        }
    }

    // --------------------------------------------------------------- table_info
    public Map<String, Object> getTableInfo(TargetDbConfig target, String tableName) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            String tableComment = "";
            try (PreparedStatement ps = conn.prepareStatement("SELECT comments FROM dba_tab_comments WHERE table_name = ?")) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) tableComment = rs.getString(1);
                }
            }

            List<Map<String, Object>> columns = new ArrayList<>();
            String colQuery = "SELECT c.column_name, c.data_type, c.data_length, c.nullable, cm.comments " +
                    "FROM dba_tab_columns c " +
                    "LEFT JOIN dba_col_comments cm ON c.owner = cm.owner AND c.table_name = cm.table_name AND c.column_name = cm.column_name " +
                    "WHERE c.table_name = ? ORDER BY c.owner, c.column_id";
            try (PreparedStatement ps = conn.prepareStatement(colQuery)) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("column_name", rs.getString(1));
                        c.put("data_type", rs.getString(2));
                        c.put("data_length", rs.getObject(3));
                        c.put("nullable", rs.getString(4));
                        String comments = rs.getString(5);
                        c.put("comments", comments == null ? "" : comments);
                        columns.add(c);
                    }
                }
            }

            List<Map<String, Object>> indexes = new ArrayList<>();
            String idxQuery = "SELECT i.index_name, MAX(i.index_type) as index_type, " +
                    "LISTAGG(ic.column_name, ', ') WITHIN GROUP (ORDER BY ic.column_position) as column_names, " +
                    "MAX(c.constraint_type) as constraint_type " +
                    "FROM dba_indexes i " +
                    "JOIN dba_ind_columns ic ON i.owner = ic.index_owner AND i.index_name = ic.index_name " +
                    "LEFT JOIN dba_constraints c ON i.owner = c.owner AND i.table_name = c.table_name AND i.index_name = c.index_name " +
                    "WHERE i.table_name = ? GROUP BY i.index_name ORDER BY i.index_name";
            try (PreparedStatement ps = conn.prepareStatement(idxQuery)) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> idx = new LinkedHashMap<>();
                        idx.put("index_name", rs.getString(1));
                        idx.put("index_type", rs.getString(2));
                        idx.put("column_name", rs.getString(3));
                        idx.put("is_pk", "P".equals(rs.getString(4)));
                        indexes.add(idx);
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("table_name", tableName);
            result.put("table_comment", tableComment);
            result.put("columns", columns);
            result.put("indexes", indexes);
            return result;
        }
    }

    // ----------------------------------------------------------- failure_prob
    public Map<String, Object> getFailureProb(TargetDbConfig target) throws SQLException {
        // dba_objects join 제거(오케스트레이터 실측, 2026-09-22, 11g patent_integ_1/2) - getActiveAlerts()의
        // 같은 패턴 쿼리와 동일한 이유: o의 컬럼을 하나도 안 쓰면서 딕셔너리 뷰 조인만 더해 11g에서
        // 무응답으로 블로킹되던 원인이었다.
        // 버그 수정(2026-09-25): 예전 1차 쿼리는 v$session에 없는 s.inst_id(gv$ 시절 흔적)를 참조해
        // 매번 ORA-00904로 실패하고 아래 rule 힌트 쿼리로 넘어가고 있었다 - 즉 운영에서 실제로 돌던 건
        // 항상 이 쿼리였다. 폐쇄망에서 검증된 동작(rule 힌트 포함)을 그대로 두고, 매 호출 헛도는 1차
        // 쿼리와 폴백 구조만 없앤다. gv$/inst_id는 쓰지 않는다(2026-09-06 RAC 원칙).
        // last_call_et 기준은 프로퍼티(dbagent.monitor.tm-holder-last-call-et-seconds, 기본 60) - int라 결합해도 안전.
        String query = "SELECT /*+ rule */ count(distinct s.sid) FROM v$session s " +
                "JOIN v$lock l ON s.sid = l.sid " +
                "WHERE l.type = 'TM' AND s.blocking_session IS NULL AND s.last_call_et >= " + tmHolderLastCallEtSeconds + " " +
                "AND EXISTS (SELECT 1 FROM v$session w WHERE w.blocking_session = s.sid)";

        try (Connection conn = poolManager.getConnection(target); Statement st = conn.createStatement()) {
            // getActiveAlerts()의 v$lock 조인과 같은 이유로 타임아웃을 건다(사용자 실측: 이 환경 일부
            // 인스턴스에서 v$lock 스캔 자체가 49초까지 걸릴 수 있음) - 이 쿼리도 같은 조인 패턴인데
            // 빠져 있었다(오케스트레이터 지적, 2026-09-18).
            st.setQueryTimeout(lockQueryTimeoutSeconds);
            int count;
            try (ResultSet rs = st.executeQuery(query)) {
                count = rs.next() ? rs.getInt(1) : 0;
            }
            // v2 대시보드의 "현재 TM/TX Lock 대기" 카드용 실시간 건수 - 장애조치 버튼(위 count)이 이미
            // 이 순간의 v$lock을 직접 보고 판단하는데, 화면에 보이는 대기 건수는 최대
            // metric-sample-interval-seconds(기본 60초)전 샘플 값이라 버튼은 즉시 뜨는데 숫자는
            // 안 따라오는 불일치가 있었다(오케스트레이터 지적, 2026-09-18). 커넥션이 이미 열려있는
            // 김에 같은 요청 안에서 같이 구해 추가 API 호출 없이 실시간화한다.
            Map<String, List<Object>> lockSids = queryLockWaitSidsByType(conn);
            return Maps.of("count", count,
                    "tmLockWaiting", lockSids.get("tm").size(),
                    "txLockWaiting", lockSids.get("tx").size());
        }
    }

    // ------------------------------------------------------------ health
    public Map<String, Object> getHealth(TargetDbConfig target) {
        String instanceStatus = "Not Alive";
        String listenerStatus = "Not Alive";
        String dbName = target.sid();
        String errorMessage = null;
        Integer maxSessions = null;
        Integer activeSessions = null;
        Integer inactiveSessions = null;
        Integer maxProcesses = null;
        Integer dedicatedSessions = null;
        Integer sharedSessions = null;

        try (Connection conn = poolManager.getConnection(target); Statement st = conn.createStatement()) {
            listenerStatus = "Alive";

            try (ResultSet rs = st.executeQuery("SELECT status FROM v$instance WHERE 1=1")) {
                if (rs.next() && "OPEN".equals(rs.getString(1))) {
                    instanceStatus = "Alive";
                } else {
                    instanceStatus = "Not Alive";
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT instance_name FROM v$instance WHERE 1=1")) {
                if (rs.next()) dbName = rs.getString(1);
            }

            // Best-effort: the db-mini-status bar's Max Session/Process tiles. Kept separate from the
            // instance/listener check above so a missing grant on v$parameter (unlikely, but possible
            // for a locked-down app account) only blanks these tiles instead of misreporting the DB
            // as down.
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$parameter WHERE name = 'sessions'")) {
                if (rs.next()) maxSessions = rs.getInt(1);
            } catch (SQLException ignored) {
            }
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$parameter WHERE name = 'processes'")) {
                if (rs.next()) maxProcesses = rs.getInt(1);
            } catch (SQLException ignored) {
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT status, COUNT(*) FROM v$session WHERE type != 'BACKGROUND' AND status IN ('ACTIVE', 'INACTIVE') GROUP BY status")) {
                activeSessions = 0;
                inactiveSessions = 0;
                while (rs.next()) {
                    if ("ACTIVE".equals(rs.getString(1))) activeSessions = rs.getInt(2);
                    else inactiveSessions = rs.getInt(2);
                }
            } catch (SQLException ignored) {
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT server, COUNT(*) FROM v$session WHERE type != 'BACKGROUND' AND server IN ('DEDICATED', 'SHARED') GROUP BY server")) {
                dedicatedSessions = 0;
                sharedSessions = 0;
                while (rs.next()) {
                    if ("DEDICATED".equals(rs.getString(1))) dedicatedSessions = rs.getInt(2);
                    else sharedSessions = rs.getInt(2);
                }
            } catch (SQLException ignored) {
            }
        } catch (SQLException e) {
            errorMessage = e.getMessage();
            if (isSelfInflicted(e)) {
                // Pool contention (too many concurrent callers) or our own post-failure cooldown -
                // neither means the DB itself is unreachable, so don't report it as an outage.
                instanceStatus = "Busy";
                listenerStatus = "Busy";
            } else {
                // The top-level exception is often HikariCP's own timeout wrapper ("Connection is not
                // available..."), not the real Oracle error - the actual ORA- code is on the cause chain.
                String oraMessage = findOraMessage(e);
                instanceStatus = "Not Alive";
                if (oraMessage != null && oraMessage.contains("ORA-01034")) {
                    // ORA-01034 (ORACLE not available) only happens after the listener accepted the
                    // connection and handed off to the instance, so the listener is confirmed alive.
                    listenerStatus = "Alive";
                } else {
                    // ORA-12541 (no listener), unresolved TNS alias, connect timeout, etc: we never got a
                    // confirmed response from anything, so don't claim the listener is alive.
                    listenerStatus = "Not Alive";
                }
            }
        } catch (Exception e) {
            errorMessage = e.getMessage();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instance_status", instanceStatus);
        result.put("listener_status", listenerStatus);
        result.put("db_name", dbName);
        result.put("error_message", errorMessage);
        result.put("max_sessions", maxSessions);
        result.put("active_sessions", activeSessions);
        result.put("inactive_sessions", inactiveSessions);
        result.put("max_processes", maxProcesses);
        result.put("dedicated_sessions", dedicatedSessions);
        result.put("shared_sessions", sharedSessions);
        return result;
    }

    /** True for OracleConnectionPoolManager's own pool-exhaustion/cooldown errors, never a real Oracle response. */
    private boolean isSelfInflicted(SQLException e) {
        return OracleConnectionPoolManager.isPoolExhausted(e)
                || (e.getMessage() != null && e.getMessage().contains("Waiting for cooldown"));
    }

    /** Walks the exception's cause chain for the first message containing an Oracle ORA- error code. */
    private String findOraMessage(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("ORA-")) {
                return msg;
            }
        }
        return null;
    }

    /**
     * A short, DBA-facing reason instead of HikariCP's raw pool-timeout wording (사용자 피드백: the
     * "Connection is not available, request timed out after 5001ms (total=0, active=0, idle=0,
     * waiting=0)" text showing up on the Fleet Overview card was noise, not useful). Surfaces the
     * Oracle ORA- code when the failure actually got far enough to have one (genuinely useful to a
     * DBA), otherwise falls back to a plain connection-failure message - never the pool/HikariCP
     * internals.
     */
    private String friendlyDownMessage(Exception e) {
        if (e instanceof SQLException) {
            SQLException se = (SQLException) e;
            if (isSelfInflicted(se)) {
                return "일시적으로 응답이 지연되고 있습니다.";
            }
            String oraMessage = findOraMessage(se);
            if (oraMessage != null) {
                int newline = oraMessage.indexOf('\n');
                return (newline > 0 ? oraMessage.substring(0, newline) : oraMessage).trim();
            }
        }
        return "DB에 연결할 수 없습니다.";
    }

    // ------------------------------------------------------- fleet_status
    // Feeds the "Fleet Overview" landing page (fleet-overview.html): one compact status
    // snapshot per configured instance, everything in a single connection/round of queries to keep
    // the fan-out across possibly many DBs cheap. MonitorController runs one of these per instance
    // concurrently (see its fleetStatus()) rather than looping sequentially, since a slow/down DB
    // would otherwise stall every DB after it - the "전체 DB 동시 폴링으로 인한 커넥션 풀 부하" concern
    // the design doc itself flagged as unresolved is mitigated this way (bounded by the per-instance
    // connect timeout, not by N times it).
    //
    // Uptime is NOT a measured historical availability percentage - Oracle doesn't track that, and
    // this app has no time-series log of past health checks to compute one from. Instead it's derived
    // from v$instance.startup_time: an instance continuously up for UPTIME_FULL_DAYS or more reads as
    // 100%, scaled linearly below that. A DB that was rock-solid for a year but restarted 2 days ago
    // reads the same as one that has only ever run for 2 days - it's "how stable right now", not "how
    // reliable historically". Documented here and to the user so it doesn't get mistaken for the
    // former.
    private static final double UPTIME_FULL_DAYS = 30.0;

    // v$version.banner still spells out the marketing codename right after "Database" on every
    // release we've seen (11g/12c/18c/19c/21c/23ai) - e.g. "Oracle Database 21c Enterprise Edition
    // Release 21.0.0.0.0 - Production" - so this is read off the DB itself instead of hardcoding a
    // "major version number -> g/c" lookup table that would need updating for every future release
    // (사용자 요청, 2026-08-30: "버전 표시는 21c, 11g 이런식으론 못 나타내나").
    private static final Pattern VERSION_CODENAME_PATTERN = Pattern.compile("Database\\s+(\\S+)");

    public Map<String, Object> getFleetStatus(TargetDbConfig target) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", target.id());
        try (Connection conn = poolManager.getConnection(target); Statement st = conn.createStatement()) {
            String instanceStatus = "down";
            double uptimeDays = 0;
            String version = null;
            String instanceName = null;
            try (ResultSet rs = st.executeQuery("SELECT status, (SYSDATE - startup_time) AS uptime_days, version, instance_name FROM v$instance")) {
                if (rs.next()) {
                    instanceStatus = "OPEN".equals(rs.getString("status")) ? "alive" : "down";
                    uptimeDays = rs.getDouble("uptime_days");
                    version = rs.getString("version");
                    instanceName = rs.getString("instance_name");
                }
            }
            if (!"alive".equals(instanceStatus)) {
                result.put("status", "down");
                result.put("errorMessage", "인스턴스가 OPEN 상태가 아닙니다.");
                // 명시적으로 null을 넣어야 프런트가 Object.assign으로 이전 폴링의 alive 응답과
                // 병합할 때 예전 버전 정보가 안 지워지고 남는 걸 막을 수 있다 (사용자에게 옛
                // "[Oracle 21c]" 태그가 다운 카드에 그대로 남아있는 것처럼 보이는 버그 방지).
                result.put("version", null);
                result.put("versionCodename", null);
                return result;
            }

            // v$version 조회 실패(예: 계정에 v$version SELECT 권한이 없는 경우)가 전체 상태 조회를
            // 실패시키지 않도록 별도 try/catch로 격리 - CPU/MEM/세션 등 나머지 지표는 이 쿼리와
            // 무관하게 정상 조회되어야 한다.
            String versionCodename = null;
            try (ResultSet rs = st.executeQuery("SELECT banner FROM v$version WHERE banner LIKE 'Oracle Database%'")) {
                if (rs.next()) {
                    Matcher m = VERSION_CODENAME_PATTERN.matcher(rs.getString("banner"));
                    if (m.find()) versionCodename = m.group(1);
                }
            } catch (Exception e) {
                log.warn("v$version lookup failed for db_id={}: {}", target.id(), e.toString());
            }

            double numCpus = 1;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$osstat WHERE stat_name = 'NUM_CPUS'")) {
                if (rs.next()) numCpus = rs.getDouble(1);
            }
            double cpuUsage = 0;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$sysmetric WHERE metric_name = 'CPU Usage Per Sec'")) {
                if (rs.next()) cpuUsage = rs.getDouble(1);
            }
            double cpuPct = numCpus > 0 ? Math.round((cpuUsage / numCpus) * 100.0) / 100.0 : 0;

            double sgaBytes = 0;
            try (ResultSet rs = st.executeQuery("SELECT sum(bytes) FROM v$sgastat")) {
                if (rs.next()) sgaBytes = rs.getDouble(1);
            }
            double pgaBytes = 0;
            try (ResultSet rs = st.executeQuery("SELECT sum(value) FROM v$pgastat WHERE name = 'total PGA allocated'")) {
                if (rs.next()) pgaBytes = rs.getDouble(1);
            }
            double totalMem = 1;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$osstat WHERE stat_name = 'PHYSICAL_MEMORY_BYTES'")) {
                if (rs.next()) totalMem = rs.getDouble(1);
            }
            double memPct = totalMem > 0 ? Math.round(((sgaBytes + pgaBytes) / totalMem) * 10000.0) / 100.0 : 0;

            int activeSessions = 0;
            // 사용자 요청(2026-08-31)으로 getSessions()가 제외하기 시작한 것과 동일한 대상(NULL
            // username, 모니터링 계정 자신)을 이 카운트에서도 빼서, Active Session 목록의 실제 행
            // 개수와 여기 KPI 숫자가 어긋나지 않게 함.
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM v$session WHERE status = 'ACTIVE' AND type != 'BACKGROUND' " +
                    "AND username IS NOT NULL AND username != " + monitoringAccountLiteral(target))) {
                if (rs.next()) activeSessions = rs.getInt(1);
            }

            long txnPerMin = queryTxnPerMinute(conn);
            double uptimePct = Math.min(100.0, Math.round((uptimeDays / UPTIME_FULL_DAYS) * 10000.0) / 100.0);

            // v1 threshold: CPU high or memory high reads as "경고", rolled up into one status instead
            // of per-metric. CPU bumped 60→80 (사용자 요청) - no longer matches the dashboard's own
            // CPU mini-bar coloring breakpoint, which is a separate, unrelated display. Used to also
            // flag on any lock waiter (v$lock-based), dropped when that metric was swapped for
            // txnPerMin (사용자가 겪은 문제: 이 인스턴스에서 v$lock 스캔 자체가 49초씩 걸림 -
            // v$sysmetric 기반의 분당 트랜잭션 수로 교체, 트랜잭션 양 자체는 경고 신호가 아니므로 임계값 없음).
            boolean warning = cpuPct >= 80 || memPct >= 80;

            result.put("status", warning ? "degraded" : "alive");
            // 사용자 요청(2026-08-31): FO 카드의 SID를 databases.json 설정값이 아니라 실제 DB의
            // v$instance.instance_name으로 표시 - 프런트(fleet-overview.html)가 config 응답과 이
            // fleet_status 응답을 Object.assign({}, configEntry, statusEntry)로 합치면서 뒤에 오는
            // statusEntry가 우선하므로, 같은 "sid" 키로 내려주기만 하면 config의 값을 자동으로 덮어씀.
            result.put("sid", instanceName);
            result.put("version", version);
            result.put("versionCodename", versionCodename);
            result.put("cpuPct", cpuPct);
            result.put("memPct", memPct);
            result.put("activeSession", activeSessions);
            result.put("txnPerMin", txnPerMin);
            result.put("uptimePct", uptimePct);
        } catch (Exception e) {
            result.put("status", "down");
            result.put("errorMessage", friendlyDownMessage(e));
            log.warn("fleet_status failed for db_id={} after {}ms: {}",
                    target.id(), System.currentTimeMillis() - startedAt, e.toString());
        }
        return result;
    }

    // ------------------------------------------------------- history_sessions
    public List<Map<String, Object>> getHistorySessions(TargetDbConfig target, String startTime, String endTime, String users, String machines) throws SQLException {
        String userFilter = buildUserFilter(users);
        String machineFilter = buildMachineFilter(machines);
        String query = "WITH combined_ash AS (" +
                "SELECT session_id, session_serial#, sql_id, event, sample_time, sql_exec_start, program, machine, user_id, sql_plan_hash_value, session_type " +
                "FROM v$active_session_history " +
                "WHERE sample_time BETWEEN TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') " +
                "UNION ALL " +
                "SELECT session_id, session_serial#, sql_id, event, sample_time, sql_exec_start, program, machine, user_id, sql_plan_hash_value, session_type " +
                "FROM dba_hist_active_sess_history " +
                "WHERE sample_time BETWEEN TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI')" +
                "), sql_execs AS (" +
                "SELECT sql_id, SUM(executions) as exec_count FROM v$sqlarea GROUP BY sql_id" +
                ") SELECT h.session_id as sid, h.session_serial# as serial, h.sql_id, NVL(h.event, 'ON CPU') as event_name, " +
                "TO_CHAR(h.sample_time, 'YYYY-MM-DD HH24:MI:SS') as capture_time, " +
                "ROUND((CAST(h.sample_time AS DATE) - CAST(NVL(h.sql_exec_start, h.sample_time) AS DATE)) * 24 * 60 * 60, 2) as duration_time, " +
                "h.program as program_name, u.username as osuser, h.sql_plan_hash_value as plan_hash_value, NVL(s.exec_count, 0) as exec_count, " +
                "(SELECT instance_name FROM v$instance) as db_name " +
                "FROM combined_ash h " +
                "LEFT JOIN dba_users u ON h.user_id = u.user_id " +
                "LEFT JOIN sql_execs s ON h.sql_id = s.sql_id " +
                "WHERE h.session_type = 'FOREGROUND'" + userFilter + machineFilter +
                excludeMonitoringAccountFilter(target) +
                "  AND ROUND((CAST(h.sample_time AS DATE) - CAST(NVL(h.sql_exec_start, h.sample_time) AS DATE)) * 24 * 60 * 60, 2) >= 3 " +
                "  AND NVL(s.exec_count, 0) >= 100 " +
                "ORDER BY h.sample_time ASC";

        List<Map<String, Object>> sessions = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, startTime);
            ps.setString(2, endTime);
            ps.setString(3, startTime);
            ps.setString(4, endTime);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sid", rs.getObject(1));
                    row.put("serial", rs.getObject(2));
                    row.put("sql_id", rs.getString(3));
                    row.put("event_name", rs.getString(4));
                    row.put("capture_time", rs.getString(5));
                    Object duration = rs.getObject(6);
                    row.put("duration_time", duration == null ? 0 : Math.max(0, ((Number) duration).doubleValue()));
                    row.put("program_name", rs.getString(7));
                    row.put("osuser", rs.getString(8));
                    row.put("plan_hash_value", rs.getObject(9));
                    row.put("exec_count", rs.getObject(10));
                    row.put("db_name", rs.getString(11));
                    sessions.add(row);
                }
            }
        }
        return sessions;
    }

    // --------------------------------------------------- history_top_sessions
    public List<Map<String, Object>> getHistoryTopSessions(TargetDbConfig target, String startTime, String endTime, String users, String machines) throws SQLException {
        String userFilter = buildUserFilter(users);
        String machineFilter = buildMachineFilter(machines);
        String query = "WITH combined_ash AS (" +
                "SELECT session_id, session_serial#, sql_id, sql_exec_id, sql_exec_start, event, sample_time, program, machine, user_id, sql_plan_hash_value, session_type " +
                "FROM v$active_session_history " +
                "WHERE sample_time BETWEEN TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND sql_exec_start IS NOT NULL " +
                "UNION ALL " +
                "SELECT session_id, session_serial#, sql_id, sql_exec_id, sql_exec_start, event, sample_time, program, machine, user_id, sql_plan_hash_value, session_type " +
                "FROM dba_hist_active_sess_history " +
                "WHERE sample_time BETWEEN TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND sql_exec_start IS NOT NULL" +
                ") SELECT h.sql_id, COUNT(DISTINCT h.sql_exec_id) as exec_count, " +
                "MAX(ROUND((CAST(h.sample_time AS DATE) - CAST(h.sql_exec_start AS DATE)) * 24 * 60 * 60, 2)) as max_duration_time, " +
                "MAX(NVL(h.event, 'ON CPU')) as event_name, TO_CHAR(MAX(h.sample_time), 'YYYY-MM-DD HH24:MI:SS') as capture_time, " +
                "MAX(h.program) as program_name, MAX(u.username) as osuser, MAX(h.sql_plan_hash_value) as plan_hash_value, " +
                "MAX(h.session_id) as sid, MAX(h.session_serial#) as serial " +
                "FROM combined_ash h LEFT JOIN dba_users u ON h.user_id = u.user_id " +
                "WHERE h.session_type = 'FOREGROUND'" + userFilter + machineFilter +
                excludeMonitoringAccountFilter(target) +
                " GROUP BY h.sql_id, u.username " +
                "HAVING MAX(ROUND((CAST(h.sample_time AS DATE) - CAST(h.sql_exec_start AS DATE)) * 24 * 60 * 60, 2)) >= 5 " +
                "ORDER BY max_duration_time DESC FETCH FIRST 100 ROWS ONLY";

        List<Map<String, Object>> sessions = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, startTime);
            ps.setString(2, endTime);
            ps.setString(3, startTime);
            ps.setString(4, endTime);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sql_id", rs.getString(1));
                    row.put("exec_count", rs.getObject(2));
                    row.put("duration_time", rs.getObject(3));
                    row.put("event_name", rs.getString(4));
                    row.put("capture_time", rs.getString(5));
                    row.put("program_name", rs.getString(6));
                    row.put("osuser", rs.getString(7));
                    row.put("plan_hash_value", rs.getObject(8));
                    row.put("sid", rs.getObject(9));
                    row.put("serial", rs.getObject(10));
                    sessions.add(row);
                }
            }
        }
        return sessions;
    }

    // ------------------------------------------------------------- history_users
    // 사용자 지적(2026-09-14): 계정 드롭다운이 dba_users(인스턴스에 열려있는 계정 전체)에서 왔던 반면
    // machine 드롭다운은 실제 ASH/AWR 관측값에서 왔던 것이 서로 기준이 달라 어색함 - 계정도 machine과
    // 같은 기준(성능 이력에 실제로 잡힌 계정만)으로 통일. 이력이 전혀 없는 스키마 계정은 이제 목록에
    // 안 뜬다. 필터링용 드롭다운 값이라 user_id가 dba_users에 없는 행은 애초에 고를 대상이 아니므로
    // getHistorySessions/getHistoryTopSessions 본문 필터와 달리 여기선 LEFT JOIN이 아니라 JOIN.
    public List<String> getHistoryUsers(TargetDbConfig target) throws SQLException {
        String query = "SELECT DISTINCT u.username FROM (" +
                "SELECT user_id FROM v$active_session_history WHERE session_type = 'FOREGROUND' " +
                "UNION ALL " +
                "SELECT user_id FROM dba_hist_active_sess_history " +
                "WHERE session_type = 'FOREGROUND' AND sample_time >= SYSDATE - 7" +
                ") h JOIN dba_users u ON h.user_id = u.user_id " +
                "WHERE u.username != " + monitoringAccountLiteral(target) + " " +
                "ORDER BY u.username";
        List<String> users = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(query)) {
            while (rs.next()) users.add(rs.getString(1));
        }
        return users;
    }

    // ---------------------------------------------------------- history_machines
    // 사용자 요청(2026-09-14): 성능 이력 조회(ASH)에 WAS 서버 기준 필터가 없어 DBA 툴/배치/모니터링
    // 계정 등 업무와 무관한 세션까지 섞여 나오는 문제 - v$active_session_history/AWR에 남아있는
    // 세션이 실제로 접속했던 machine(호스트명) 목록을 뽑아 드롭다운으로 골라 쓰게 한다. dba_hist_
    // active_sess_history 전체를 훑으면 무거우니 최근 7일로 제한(대부분 폐쇄망 조회 목적엔 충분).
    public List<String> getHistoryMachines(TargetDbConfig target) throws SQLException {
        String query = "SELECT DISTINCT h.machine FROM (" +
                "SELECT machine, user_id FROM v$active_session_history WHERE session_type = 'FOREGROUND' AND machine IS NOT NULL " +
                "UNION ALL " +
                "SELECT machine, user_id FROM dba_hist_active_sess_history " +
                "WHERE session_type = 'FOREGROUND' AND machine IS NOT NULL AND sample_time >= SYSDATE - 7" +
                ") h LEFT JOIN dba_users u ON h.user_id = u.user_id " +
                "WHERE (u.username IS NULL OR u.username != " + monitoringAccountLiteral(target) + ") " +
                "ORDER BY h.machine";
        List<String> machines = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(query)) {
            while (rs.next()) machines.add(rs.getString(1));
        }
        return machines;
    }

    private String buildUserFilter(String usersParam) {
        if (usersParam == null || Strings.isBlank(usersParam)) return "";
        List<String> list = new ArrayList<>();
        for (String u : usersParam.split(",")) {
            String trimmed = Strings.strip(u);
            if (!trimmed.isEmpty()) {
                list.add("'" + trimmed.replace("'", "''") + "'");
            }
        }
        if (list.isEmpty()) return "";
        return " AND u.username IN (" + String.join(",", list) + ") ";
    }

    private String buildMachineFilter(String machinesParam) {
        if (machinesParam == null || Strings.isBlank(machinesParam)) return "";
        List<String> list = new ArrayList<>();
        for (String m : machinesParam.split(",")) {
            String trimmed = Strings.strip(m);
            if (!trimmed.isEmpty()) {
                list.add("'" + trimmed.replace("'", "''") + "'");
            }
        }
        if (list.isEmpty()) return "";
        return " AND h.machine IN (" + String.join(",", list) + ") ";
    }

    // 사용자 요청(2026-08-31/2026-09-01): 모니터링 계정 자신의 세션을 Active Session류 화면에서
    // 제외할 때 SYS_CONTEXT('USERENV','SID') 방식(현재 커넥션 자신만 제외)을 써봤으나, 폐쇄망 실사용
    // 테스트 결과 같은 모니터링 계정의 풀에 있는 "다른" 커넥션(동시에 도는 다른 폴링 쿼리)은 SID가
    // 달라 제외되지 않고 Active Session 목록에 섞여 들어오는 문제가 발견됨. 이 계정은 모니터링
    // 전용이라 실사용자가 쓸 일이 없으므로, databases.json에 설정된 계정명 자체로 걸러 그 계정의
    // 세션을 전부(SID와 무관하게) 제외하는 원래 방식으로 되돌림.
    private String monitoringAccountLiteral(TargetDbConfig target) {
        return "'" + target.user().toUpperCase().replace("'", "''") + "'";
    }

    // 사용자 지적(2026-09-14): 성능 이력 조회(getHistorySessions/getHistoryTopSessions)엔 이 모니터링
    // 계정 제외 로직이 빠져 있었다 - 실시간 Active Session류 화면과 달리 dba_users를 LEFT JOIN해서
    // 얻은 u.username이라 NULL일 수 있어(user_id가 dba_users에 없는 극단적 케이스), 순수 "!="만 쓰면
    // NULL 비교의 3치 논리로 그 행이 통째로 걸러진다 - u.username IS NULL 인 행은 그대로 살려둔다.
    private String excludeMonitoringAccountFilter(TargetDbConfig target) {
        return "  AND (u.username IS NULL OR u.username != " + monitoringAccountLiteral(target) + ") ";
    }

    private Object orZero(Object value) {
        return value == null ? 0 : value;
    }
}
