package com.dbagent.rdb;

import com.dbagent.oracle.TargetDbConfig;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MS SQL Server counterpart of MySqlMonitorService/PostgresMonitorService - see EngineMonitorService.
 * Everything here reads from sys.dm_* DMVs (Dynamic Management Views), which is the SQL-only surface
 * available through a remote JDBC connection - no OS-level host metrics (CPU/memory/disk), same
 * limitation as the other two engines.
 */
@Service
public class MsSqlMonitorService implements EngineMonitorService {

    private final RdbConnectionPoolManager poolManager;

    public MsSqlMonitorService(RdbConnectionPoolManager poolManager) {
        this.poolManager = poolManager;
    }

    @Override
    public List<Map<String, Object>> getSessions(TargetDbConfig target) throws SQLException {
        // is_user_process=1 excludes SQL Server's own background/system sessions (session_id <= 50),
        // matching what an admin actually wants to see - the same filtering the other engines' session
        // lists get "for free" from information_schema.processlist / pg_stat_activity's own scoping.
        String sql = "SELECT s.session_id AS id, s.login_name AS [user], s.host_name AS host, " +
                "DB_NAME(s.database_id) AS db, s.program_name AS application_name, " +
                "COALESCE(r.command, s.status) AS command, s.status, " +
                "DATEDIFF(SECOND, s.last_request_start_time, GETDATE()) AS idle_seconds, " +
                "t.text AS info " +
                "FROM sys.dm_exec_sessions s " +
                "LEFT JOIN sys.dm_exec_requests r ON r.session_id = s.session_id " +
                "OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t " +
                "WHERE s.is_user_process = 1 " +
                "ORDER BY s.login_time DESC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getObject("id"));
                row.put("user", rs.getString("user"));
                row.put("host", rs.getString("host"));
                row.put("db", rs.getString("db"));
                row.put("application_name", rs.getString("application_name"));
                row.put("command", rs.getString("command"));
                row.put("state", rs.getString("status"));
                row.put("time", rs.getObject("idle_seconds"));
                row.put("info", rs.getString("info"));
                rows.add(row);
            }
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> getStorage(TargetDbConfig target) throws SQLException {
        // database_id > 4 excludes the four fixed system databases (master/tempdb/model/msdb), which
        // always occupy ids 1-4 on every instance. size is in 8KB pages, so *8/1024 converts to MB.
        //
        // <b>sys.master_files.size 는 파일에 "할당된" 크기지 그 안에 채워진 크기가 아니다.</b> 예전에는
        // 이 값을 used_mb 로도 담고 total_mb 로도 담은 뒤 used_pct=100 을 박아서, 화면 사용률이 항상
        // 100% 로 나왔다(09-05 문서 15-2절). 값의 실제 의미대로 total_mb 에만 담고, 이 쿼리로는 알 수
        // 없는 사용량/여유/사용률은 null 로 둔다 - 화면은 '-' 로 그린다. 진짜 사용량이 필요하면 용량
        // 조회 탭(getCapacity)이 FILEPROPERTY/dm_db_file_space_usage 로 제대로 구해 준다.
        String sql = "SELECT DB_NAME(database_id) AS name, " +
                "ROUND(SUM(CAST(size AS BIGINT)) * 8.0 / 1024, 2) AS total_mb " +
                "FROM sys.master_files WHERE database_id > 4 " +
                "GROUP BY database_id ORDER BY total_mb DESC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tablespace_name", rs.getString("name"));
                row.put("status", "ONLINE");
                row.put("total_mb", rs.getDouble("total_mb"));
                row.put("used_mb", null);
                row.put("free_mb", null);
                row.put("used_pct", null);
                rows.add(row);
            }
        }
        return rows;
    }

    @Override
    public Map<String, Object> getFleetStatus(TargetDbConfig target) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", target.id());
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            long activeSession = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) AS c FROM sys.dm_exec_sessions WHERE is_user_process = 1")) {
                if (rs.next()) activeSession = rs.getLong("c");
            }
            long uptimeSeconds = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT DATEDIFF(SECOND, sqlserver_start_time, GETDATE()) AS s FROM sys.dm_os_sys_info")) {
                if (rs.next()) uptimeSeconds = rs.getLong("s");
            }
            double uptimeDays = uptimeSeconds / 86400.0;
            double uptimePct = Math.min(100.0, (uptimeDays / 30.0) * 100.0);

            String version = "";
            try (ResultSet rs = st.executeQuery("SELECT @@VERSION AS v")) {
                if (rs.next()) version = rs.getString("v");
            }

            result.put("status", "alive");
            result.put("sid", target.sid());
            result.put("version", version);
            result.put("cpuPct", 0);
            result.put("memPct", 0);
            result.put("activeSession", activeSession);
            result.put("txnPerMin", 0);
            result.put("uptimePct", uptimePct);
        } catch (SQLException e) {
            result.put("status", "down");
            result.put("errorMessage", "DB에 연결할 수 없습니다.");
        }
        return result;
    }

    /**
     * MS SQL-only (not part of EngineMonitorService) - powers mssql-overview-dashboard.html's 4 KPI
     * cards. Batch Requests/sec is an average since server start (counter value / uptime), same
     * "single request, no time-series store" approach as MySQL's Queries/Uptime and Postgres's TPS.
     */
    public Map<String, Object> getOverviewStats(TargetDbConfig target) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            long uptimeSeconds = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT DATEDIFF(SECOND, sqlserver_start_time, GETDATE()) AS s FROM sys.dm_os_sys_info")) {
                if (rs.next()) uptimeSeconds = rs.getLong("s");
            }

            long batchRequests = perfCounterValue(st, "SQL Statistics", "Batch Requests/sec");
            long totalServerMemoryKb = perfCounterValue(st, "Memory Manager", "Total Server Memory (KB)");
            long bufferHit = perfCounterValue(st, "Buffer Manager", "Buffer cache hit ratio");
            long bufferHitBase = perfCounterValue(st, "Buffer Manager", "Buffer cache hit ratio base");

            double qps = uptimeSeconds > 0 ? (double) batchRequests / uptimeSeconds : 0;
            double hitRatePct = bufferHitBase > 0 ? (100.0 * bufferHit / bufferHitBase) : 100.0;
            hitRatePct = Math.max(0.0, Math.min(100.0, hitRatePct));

            result.put("uptimeSeconds", uptimeSeconds);
            result.put("uptimeLabel", formatUptime(uptimeSeconds));
            result.put("qps", Math.round(qps * 100.0) / 100.0);
            long bufferBytes = totalServerMemoryKb * 1024;
            result.put("bufferPoolBytes", bufferBytes);
            result.put("bufferPoolGib", Math.round((bufferBytes / 1073741824.0) * 100.0) / 100.0);
            result.put("bufferPoolHitRatePct", Math.round(hitRatePct * 100.0) / 100.0);
        } catch (SQLException e) {
            result.put("error", "DB에 연결할 수 없습니다.");
        }
        return result;
    }

    /**
     * MS SQL-only - powers mssql-overview-dashboard.html's accordion rows (Connections, Batch Activity,
     * Locks, Wait Stats, Memory, TempDB, I/O) plus the Detail tab's 4 time-series+legend panels. Every
     * value is either a point-in-time DMV snapshot or a cumulative-counter-since-startup average, same
     * limitation as the other two engines - this app has no time-series store.
     */
    public Map<String, Object> getStatusOverview(TargetDbConfig target) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            long uptimeSeconds = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT DATEDIFF(SECOND, sqlserver_start_time, GETDATE()) AS s FROM sys.dm_os_sys_info")) {
                if (rs.next()) uptimeSeconds = rs.getLong("s");
            }

            // Connections
            long currentConnections = perfCounterValue(st, "General Statistics", "User Connections");
            long sessionsConnected = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) AS c FROM sys.dm_exec_sessions WHERE is_user_process = 1")) {
                if (rs.next()) sessionsConnected = rs.getLong("c");
            }
            long requestsRunning = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) AS c FROM sys.dm_exec_requests WHERE session_id > 50")) {
                if (rs.next()) requestsRunning = rs.getLong("c");
            }
            long maxWorkers = 0;
            try (ResultSet rs = st.executeQuery("SELECT max_workers_count AS c FROM sys.dm_os_sys_info")) {
                if (rs.next()) maxWorkers = rs.getLong("c");
            }
            result.put("currentConnections", currentConnections);
            result.put("sessionsConnected", sessionsConnected);
            result.put("requestsRunning", requestsRunning);
            result.put("maxWorkers", maxWorkers);

            // Batch activity / compilations - cumulative counters since server start, reported as an
            // average per-second rate the same way qps is derived in getOverviewStats().
            long batchRequests = perfCounterValue(st, "SQL Statistics", "Batch Requests/sec");
            long compilations = perfCounterValue(st, "SQL Statistics", "SQL Compilations/sec");
            long recompilations = perfCounterValue(st, "SQL Statistics", "SQL Re-Compilations/sec");
            result.put("batchRequestsPerSec", uptimeSeconds > 0 ? round2((double) batchRequests / uptimeSeconds) : 0);
            result.put("compilationsPerSec", uptimeSeconds > 0 ? round2((double) compilations / uptimeSeconds) : 0);
            result.put("recompilationsPerSec", uptimeSeconds > 0 ? round2((double) recompilations / uptimeSeconds) : 0);
            result.put("pageLifeExpectancy", perfCounterValue(st, "Buffer Manager", "Page life expectancy"));

            // Locks
            long locksGranted = 0, locksWaiting = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT request_status, COUNT(*) AS c FROM sys.dm_tran_locks GROUP BY request_status")) {
                while (rs.next()) {
                    String status = rs.getString("request_status");
                    if ("GRANT".equalsIgnoreCase(status)) locksGranted = rs.getLong("c");
                    else if ("WAIT".equalsIgnoreCase(status)) locksWaiting += rs.getLong("c");
                }
            }
            result.put("locksGranted", locksGranted);
            result.put("locksWaiting", locksWaiting);

            // Wait stats - top 5 by total wait time, excluding the long list of benign/idle wait types
            // that are always near the top on an otherwise-healthy instance (background housekeeping,
            // idle worker parking, etc.) and would drown out anything actually worth looking at.
            List<Map<String, Object>> waits = new ArrayList<>();
            String waitSql = "SELECT TOP 5 wait_type, wait_time_ms, waiting_tasks_count " +
                    "FROM sys.dm_os_wait_stats " +
                    "WHERE wait_time_ms > 0 AND wait_type NOT IN (" +
                    "'CLR_SEMAPHORE','LAZYWRITER_SLEEP','RESOURCE_QUEUE','SLEEP_TASK','SLEEP_SYSTEMTASK'," +
                    "'SQLTRACE_BUFFER_FLUSH','WAITFOR','LOGMGR_QUEUE','CHECKPOINT_QUEUE'," +
                    "'REQUEST_FOR_DEADLOCK_SEARCH','XE_TIMER_EVENT','BROKER_TO_FLUSH','BROKER_TASK_STOP'," +
                    "'CLR_MANUAL_EVENT','CLR_AUTO_EVENT','DISPATCHER_QUEUE_SEMAPHORE'," +
                    "'FT_IFTS_SCHEDULER_IDLE_WAIT','XE_DISPATCHER_WAIT','XE_DISPATCHER_JOIN'," +
                    "'BROKER_EVENTHANDLER','TRACEWRITE','FT_IFTSHC_MUTEX','SQLTRACE_INCREMENTAL_FLUSH_SLEEP'," +
                    "'BROKER_RECEIVE_WAITFOR','ONDEMAND_TASK_QUEUE','DBMIRROR_EVENTS_QUEUE'," +
                    "'DBMIRROR_WORKER_QUEUE','DBMIRRORING_CMD','HADR_FILESTREAM_IOMGR_IOCOMPLETION'," +
                    "'SP_SERVER_DIAGNOSTICS_SLEEP') " +
                    "ORDER BY wait_time_ms DESC";
            try (ResultSet rs = st.executeQuery(waitSql)) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("waitType", rs.getString("wait_type"));
                    row.put("waitTimeMs", rs.getLong("wait_time_ms"));
                    row.put("waitingTasks", rs.getLong("waiting_tasks_count"));
                    waits.add(row);
                }
            }
            result.put("topWaits", waits);

            // Memory
            long totalServerMemoryKb = perfCounterValue(st, "Memory Manager", "Total Server Memory (KB)");
            long targetServerMemoryKb = perfCounterValue(st, "Memory Manager", "Target Server Memory (KB)");
            result.put("totalServerMemoryMb", Math.round(totalServerMemoryKb / 1024.0));
            result.put("targetServerMemoryMb", Math.round(targetServerMemoryKb / 1024.0));

            // TempDB usage (data + log file sizes, in MB) - database_id=2 is always tempdb.
            try (ResultSet rs = st.executeQuery(
                    "SELECT SUM(CASE WHEN type = 0 THEN size ELSE 0 END) * 8.0 / 1024 AS data_mb, " +
                            "SUM(CASE WHEN type = 1 THEN size ELSE 0 END) * 8.0 / 1024 AS log_mb " +
                            "FROM sys.master_files WHERE database_id = 2")) {
                if (rs.next()) {
                    result.put("tempdbDataMb", round2(rs.getDouble("data_mb")));
                    result.put("tempdbLogMb", round2(rs.getDouble("log_mb")));
                }
            }

            // I/O stats, aggregated across every data/log file on the instance.
            try (ResultSet rs = st.executeQuery(
                    "SELECT SUM(num_of_reads) AS reads, SUM(num_of_writes) AS writes, " +
                            "SUM(io_stall_read_ms) AS read_stall_ms, SUM(io_stall_write_ms) AS write_stall_ms " +
                            "FROM sys.dm_io_virtual_file_stats(NULL, NULL)")) {
                if (rs.next()) {
                    result.put("ioReads", rs.getLong("reads"));
                    result.put("ioWrites", rs.getLong("writes"));
                    result.put("ioReadStallMs", rs.getLong("read_stall_ms"));
                    result.put("ioWriteStallMs", rs.getLong("write_stall_ms"));
                }
            }
        } catch (SQLException e) {
            result.put("error", "DB에 연결할 수 없습니다.");
        }
        return result;
    }

    /** sys.dm_os_performance_counters values come back padded with trailing spaces on every column. */
    private long perfCounterValue(Statement st, String objectNameFragment, String counterName) throws SQLException {
        String sql = "SELECT cntr_value FROM sys.dm_os_performance_counters " +
                "WHERE RTRIM(object_name) LIKE ? AND RTRIM(counter_name) = ?";
        try (PreparedStatement ps = st.getConnection().prepareStatement(sql)) {
            ps.setString(1, "%:" + objectNameFragment);
            ps.setString(2, counterName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("cntr_value");
            }
        }
        return 0L;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String formatUptime(long seconds) {
        double days = seconds / 86400.0;
        if (days >= 7) {
            return String.format(Locale.US, "%.1f weeks", days / 7);
        }
        if (days >= 1) {
            return String.format(Locale.US, "%.1f days", days);
        }
        double hours = seconds / 3600.0;
        if (hours >= 1) {
            return String.format(Locale.US, "%.1f hours", hours);
        }
        return String.format(Locale.US, "%d min", seconds / 60);
    }

    // =============================================================================================
    // RDB 대시보드 세션 화면 3종 (문서 "세션리스트 및 세션 정보 조회 쿼리.md" 5절)
    //
    // 네 엔진 중 MS SQL 만 세션 뷰(dm_exec_sessions) · 요청 뷰(dm_exec_requests) · SQL 텍스트 함수
    // (dm_exec_sql_text)를 셋 다 엮어야 한 줄이 완성된다. 그리고 시간 단위가 밀리초라
    // (total_elapsed_time, wait_duration_ms) 다른 엔진과 맞추려면 1000 으로 나눠야 한다.
    // =============================================================================================

    @Override
    public List<Map<String, Object>> getSessionList(TargetDbConfig target) throws SQLException {
        // is_user_process=1 로 서버 자신의 백그라운드 세션을 빼고, @@SPID 로 이 모니터링 쿼리
        // 자신을 뺀다(문서 1절 공통 필터). dm_exec_requests 기준이라 지금 실제로 일하고 있는
        // 세션만 나온다 - 접속만 해 둔 유휴 세션은 애초에 이 뷰에 행이 없다.
        String sql = "SELECT r.session_id, s.login_name, s.host_name, s.program_name, " +
                "DB_NAME(r.database_id) AS db_name, r.status, r.command, " +
                "r.total_elapsed_time / 1000.0 AS duration_seconds, r.wait_type, " +
                "LEFT(t.text, 500) AS query_preview " +
                "FROM sys.dm_exec_requests r " +
                "INNER JOIN sys.dm_exec_sessions s ON r.session_id = s.session_id " +
                "OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t " +
                "WHERE s.is_user_process = 1 AND r.session_id <> @@SPID " +
                "ORDER BY r.total_elapsed_time DESC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("session_id", rs.getObject("session_id"));
                row.put("user", rs.getString("login_name"));
                row.put("host", rs.getString("host_name"));
                row.put("db", rs.getString("db_name"));
                row.put("program", rs.getString("program_name"));
                // 상태만으로는 뭘 하다 멈춰 있는지 알 수 없어 명령(SELECT/UPDATE/WAITFOR)을 괄호로
                // 붙인다 - "suspended" 한 단어보다 "suspended (UPDATE)" 가 훨씬 읽힌다.
                row.put("status", statusWithCommand(rs.getString("status"), rs.getString("command")));
                row.put("duration_seconds", rs.getObject("duration_seconds"));
                row.put("wait_event", rs.getString("wait_type"));
                row.put("query_preview", rs.getString("query_preview"));
                rows.add(row);
            }
        }
        return rows;
    }

    @Override
    public Map<String, Object> getSessionDetail(TargetDbConfig target, long sessionId) throws SQLException {
        // 목록과 달리 dm_exec_sessions 를 기준으로 LEFT JOIN 한다 - 목록을 그린 뒤 클릭하기까지의
        // 사이에 요청이 끝났더라도 "없는 세션" 으로 보이지 않게 하기 위함이다(요청이 없으면 실행
        // 중인 SQL 만 비고 접속 정보는 그대로 나온다).
        String sql = "SELECT s.session_id, s.login_name, s.host_name, s.program_name, " +
                "DB_NAME(COALESCE(r.database_id, s.database_id)) AS db_name, " +
                "COALESCE(r.status, s.status) AS status, r.command, r.cpu_time, " +
                "r.total_elapsed_time / 1000.0 AS elapsed_sec, r.wait_type, " +
                "t.text AS sql_text " +
                "FROM sys.dm_exec_sessions s " +
                "LEFT JOIN sys.dm_exec_requests r ON r.session_id = s.session_id " +
                "OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t " +
                "WHERE s.session_id = ?";
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = poolManager.getConnection(target);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // session_id 는 smallint 라 int 로 바인딩한다.
            ps.setInt(1, (int) sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    result.put("found", false);
                    return result;
                }
                List<Map<String, Object>> fields = new ArrayList<>();
                fields.add(field("세션 ID (SPID)", rs.getObject("session_id")));
                fields.add(field("계정", rs.getString("login_name")));
                fields.add(field("접속 머신", rs.getString("host_name")));
                fields.add(field("클라이언트 프로그램", rs.getString("program_name")));
                fields.add(field("현재 DB", rs.getString("db_name")));
                fields.add(field("요청 상태", rs.getString("status")));
                fields.add(field("명령", rs.getString("command")));
                fields.add(field("CPU 시간(ms)", rs.getObject("cpu_time")));
                fields.add(field("경과 시간(초)", rs.getObject("elapsed_sec")));
                fields.add(field("대기 유형", rs.getString("wait_type")));
                result.put("found", true);
                result.put("session_id", rs.getObject("session_id"));
                result.put("fields", fields);
                result.put("sql_text", rs.getString("sql_text"));
            }
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> getLockWaits(TargetDbConfig target) throws SQLException {
        // 문서 5.3 을 실측(2026-09-05, SQL Server 2022)으로 손본 것이다. 원문은 대기 세션의 요청
        // (r_w)과 블로커 세션(s_b)을 INNER JOIN 하는데, 블로커가 트랜잭션만 열어둔 채 아무 요청도
        // 돌리지 않는 경우가 실제 블로킹의 상당수다. INNER JOIN 이면 그런 행이 통째로 사라져
        // "막혀 있는데 Lock 화면은 비어 있는" 최악의 오해를 만든다. 그래서 LEFT JOIN 으로 바꿨다.
        String sql = "SELECT wt.session_id AS waiter_session_id, s_w.login_name AS waiter_user, " +
                "s_w.host_name AS waiter_host, " +
                "wt.wait_duration_ms / 1000.0 AS wait_duration_sec, wt.wait_type, " +
                "LEFT(t_w.text, 300) AS waiter_query, " +
                "wt.blocking_session_id AS blocker_session_id, s_b.login_name AS blocker_user, " +
                "s_b.host_name AS blocker_host, " +
                "COALESCE(r_b.status, s_b.status) AS blocker_state, " +
                "LEFT(t_b.text, 300) AS blocker_query " +
                "FROM sys.dm_os_waiting_tasks wt " +
                "LEFT JOIN sys.dm_exec_sessions s_w ON wt.session_id = s_w.session_id " +
                "LEFT JOIN sys.dm_exec_requests r_w ON wt.session_id = r_w.session_id " +
                "OUTER APPLY sys.dm_exec_sql_text(r_w.sql_handle) t_w " +
                "LEFT JOIN sys.dm_exec_sessions s_b ON wt.blocking_session_id = s_b.session_id " +
                "LEFT JOIN sys.dm_exec_requests r_b ON wt.blocking_session_id = r_b.session_id " +
                "OUTER APPLY sys.dm_exec_sql_text(r_b.sql_handle) t_b " +
                "WHERE wt.blocking_session_id IS NOT NULL AND wt.blocking_session_id <> 0 " +
                "ORDER BY wt.wait_duration_ms DESC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("waiter_session_id", rs.getObject("waiter_session_id"));
                row.put("waiter_user", rs.getString("waiter_user"));
                row.put("waiter_host", rs.getString("waiter_host"));
                row.put("wait_duration_sec", rs.getObject("wait_duration_sec"));
                row.put("wait_type", rs.getString("wait_type"));
                row.put("waiter_query", rs.getString("waiter_query"));
                row.put("blocker_session_id", rs.getObject("blocker_session_id"));
                row.put("blocker_user", rs.getString("blocker_user"));
                row.put("blocker_host", rs.getString("blocker_host"));
                row.put("blocker_state", rs.getString("blocker_state"));
                row.put("blocker_query", rs.getString("blocker_query"));
                rows.add(row);
            }
        }
        return rows;
    }

    private String statusWithCommand(String status, String command) {
        if (command == null || command.trim().isEmpty()) {
            return status;
        }
        if (status == null) {
            return command;
        }
        return status + " (" + command.trim() + ")";
    }

    private Map<String, Object> field(String label, Object value) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("label", label);
        f.put("value", value);
        return f;
    }

    /**
     * MS SQL 은 {@code KILL <spid>} 다. MySQL 과 마찬가지로 바인드 파라미터를 못 받는 명령이라
     * 문자열로 조립한다(EngineMonitorService.killSession 주석 참고).
     *
     * <p>{@code ALTER ANY CONNECTION} 권한이 필요하고, 시스템 세션(SPID &lt;= 50)과 자기 자신은
     * 죽일 수 없다. 그 경우 SQL Server 가 에러를 던지므로 사유를 그대로 화면에 올린다.
     */
    @Override
    public Map<String, Object> killSession(TargetDbConfig target, long sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId);
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            st.execute("KILL " + sessionId);
            result.put("status", "killed");
            result.put("message", null);
        } catch (SQLException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // 용량 조회 (EngineMonitorService 의 용량 섹션 주석 참고)
    //
    // 네 엔진 중 MS SQL 만 오라클과 구조가 같다 - 데이터베이스마다 데이터 파일을 미리 할당해 두고
    // 그 안을 채워 쓴다. 그래서 "할당 대비 사용률" 이 실제로 의미가 있는 유일한 엔진이고,
    // 1단도 스키마가 아니라 데이터베이스로 잡는다(오라클의 테이블스페이스에 대응).
    // ---------------------------------------------------------------------------------------------

    /**
     * 할당량은 sys.master_files 로 한 번에 읽히지만, <b>실제 사용량은 데이터베이스 안에 들어가야</b>
     * 볼 수 있다(dm_db_partition_stats 는 DB 단위 DMV 다). DB 마다 따로 질의하면 왕복이 DB 수만큼
     * 늘어나므로, 3-part naming 으로 각 DB 를 가리키는 SELECT 를 UNION ALL 로 이어 붙인 문장
     * 하나를 만들어 한 번에 실행한다.
     *
     * <p>DB 이름은 {@code QUOTENAME()} 으로 감싼다 - 대괄호를 이스케이프해 주므로 이름에 특수문자가
     * 있어도 안전하다. 그리고 이 이름들은 사용자 입력이 아니라 {@code sys.databases} 에서 방금 읽은
     * 값이다. {@code HAS_DBACCESS} 로 접근 가능한 DB 만 넣는데, 이게 없으면 권한 없는 DB 하나 때문에
     * 문장 전체가 실패한다.
     */
    @Override
    public Map<String, Object> getCapacity(TargetDbConfig target) throws SQLException {
        String sql =
                "DECLARE @u nvarchar(max) = N''; " +
                "SELECT @u = @u + CASE WHEN @u = N'' THEN N'' ELSE N' UNION ALL ' END + " +
                "       N'SELECT ' + CAST(d.database_id AS nvarchar(10)) + N' AS database_id, " +
                "         COUNT(DISTINCT CASE WHEN index_id IN (0,1) THEN object_id END) AS table_count, " +
                "         SUM(CASE WHEN index_id IN (0,1) THEN used_page_count ELSE 0 END) AS data_pages, " +
                "         SUM(CASE WHEN index_id NOT IN (0,1) THEN used_page_count ELSE 0 END) AS index_pages, " +
                "         SUM(reserved_page_count) AS used_pages FROM ' + " +
                "       QUOTENAME(d.name) + N'.sys.dm_db_partition_stats' " +
                "FROM sys.databases d WHERE d.state = 0 AND HAS_DBACCESS(d.name) = 1; " +
                "DECLARE @sql nvarchar(max) = N'" +
                "WITH used AS (' + @u + N'), " +
                "alloc AS (SELECT database_id, " +
                "                 SUM(CASE WHEN type = 0 THEN size ELSE 0 END) AS data_pages, " +
                "                 SUM(CASE WHEN type = 1 THEN size ELSE 0 END) AS log_pages " +
                "          FROM sys.master_files GROUP BY database_id) " +
                "SELECT DB_NAME(a.database_id) AS name, u.table_count, " +
                "       CAST(ISNULL(u.data_pages,0) * 8.0/1024 AS DECIMAL(18,2)) AS data_mb, " +
                "       CAST(ISNULL(u.index_pages,0) * 8.0/1024 AS DECIMAL(18,2)) AS index_mb, " +
                "       CAST(ISNULL(u.used_pages,0) * 8.0/1024 AS DECIMAL(18,2)) AS used_mb, " +
                "       CAST(a.data_pages * 8.0/1024 AS DECIMAL(18,2)) AS total_mb, " +
                "       CAST((a.data_pages - ISNULL(u.used_pages,0)) * 8.0/1024 AS DECIMAL(18,2)) AS free_mb, " +
                "       CASE WHEN a.data_pages > 0 THEN " +
                "            CAST(ISNULL(u.used_pages,0) * 100.0 / a.data_pages AS DECIMAL(5,2)) END AS used_pct, " +
                "       CAST(a.log_pages * 8.0/1024 AS DECIMAL(18,2)) AS log_mb " +
                "FROM alloc a LEFT JOIN used u ON u.database_id = a.database_id " +
                "ORDER BY a.data_pages DESC'; " +
                "EXEC sp_executesql @sql";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", rs.getString("name"));
                row.put("table_count", rs.getObject("table_count"));
                row.put("data_mb", rs.getObject("data_mb"));
                row.put("index_mb", rs.getObject("index_mb"));
                row.put("used_mb", rs.getObject("used_mb"));
                row.put("total_mb", rs.getObject("total_mb"));
                row.put("free_mb", rs.getObject("free_mb"));
                row.put("used_pct", rs.getObject("used_pct"));
                // 트랜잭션 로그 파일 크기. 위 표의 할당/사용률은 데이터 파일(ROWS)만 센다 - 로그는
                // 성격이 달라 같이 더하면 사용률이 왜곡되기 때문이다. 쿼리는 예전부터 log_mb 를
                // 뽑아 놓고 응답에 담지 않아 죽은 컬럼이었다(09-05 문서 15-2절). 별도 칸으로 내려
                // 보낸다 - 문서 6.5(임시 공간/로그 용량)가 요구하는 값이 바로 이것이다.
                row.put("log_mb", rs.getObject("log_mb"));
                rows.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unit", "데이터베이스");
        result.put("note", "'할당'은 데이터 파일(ROWS)에 미리 잡아 둔 크기이고 '사용'은 그 안에 실제로 " +
                "채워진 크기입니다 - Oracle의 테이블스페이스/데이터파일과 같은 구조라 사용률이 " +
                "의미를 가집니다. 트랜잭션 로그 파일은 성격이 달라 이 표에서 제외했습니다. " +
                "tempdb를 포함한 시스템 데이터베이스도 함께 보여줍니다(용량 장애는 tempdb에서 " +
                "시작되는 경우가 많습니다).");
        result.put("rows", rows);
        return result;
    }

    @Override
    public Map<String, Object> getCapacityDetail(TargetDbConfig target, String scope) throws SQLException {
        // scope(데이터베이스명)를 sp_executesql 파라미터로 넘기고 QUOTENAME 으로 감싼다 -
        // 문자열 연결로 붙이면 그대로 주입 통로가 된다.
        String sql =
                "DECLARE @db sysname = ?; " +
                "IF NOT EXISTS (SELECT 1 FROM sys.databases WHERE name = @db AND state = 0 AND HAS_DBACCESS(name) = 1) " +
                "BEGIN SELECT CAST(NULL AS sysname) AS name, CAST(NULL AS bigint) AS row_count, " +
                "             CAST(NULL AS DECIMAL(18,2)) AS data_mb, CAST(NULL AS DECIMAL(18,2)) AS index_mb, " +
                "             CAST(NULL AS DECIMAL(18,2)) AS total_mb WHERE 1 = 0; RETURN; END " +
                "DECLARE @sql nvarchar(max) = N'" +
                "SELECT s.name + ''.'' + t.name AS name, " +
                "       SUM(CASE WHEN ps.index_id IN (0,1) THEN ps.row_count ELSE 0 END) AS row_count, " +
                "       CAST(SUM(CASE WHEN ps.index_id IN (0,1) THEN ps.used_page_count ELSE 0 END) " +
                "            * 8.0/1024 AS DECIMAL(18,2)) AS data_mb, " +
                "       CAST(SUM(CASE WHEN ps.index_id NOT IN (0,1) THEN ps.used_page_count ELSE 0 END) " +
                "            * 8.0/1024 AS DECIMAL(18,2)) AS index_mb, " +
                "       CAST(SUM(ps.reserved_page_count) * 8.0/1024 AS DECIMAL(18,2)) AS total_mb " +
                "FROM ' + QUOTENAME(@db) + N'.sys.tables t " +
                "JOIN ' + QUOTENAME(@db) + N'.sys.schemas s ON s.schema_id = t.schema_id " +
                "JOIN ' + QUOTENAME(@db) + N'.sys.dm_db_partition_stats ps ON ps.object_id = t.object_id " +
                "GROUP BY s.name, t.name ORDER BY SUM(ps.reserved_page_count) DESC'; " +
                "EXEC sp_executesql @sql";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scope);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", rs.getString("name"));
                    row.put("row_count", rs.getObject("row_count"));
                    row.put("data_mb", rs.getObject("data_mb"));
                    row.put("index_mb", rs.getObject("index_mb"));
                    row.put("total_mb", rs.getObject("total_mb"));
                    // 테이블 단위의 여유 공간(reserved - used)은 인덱스 조각까지 섞여 오해를 부르기
                    // 쉬워 내지 않는다. 여유는 1단(파일 할당 대비)에서 보는 값이다.
                    row.put("free_mb", null);
                    rows.add(row);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope);
        result.put("note", rows.isEmpty()
                ? "이 데이터베이스에는 테이블이 없거나, 접근 권한이 없거나, 온라인 상태가 아닙니다."
                : "'전체'는 예약된(reserved) 크기라 데이터+인덱스 합계보다 조금 큽니다 - 그 차이가 아직 안 쓴 예약 공간입니다.");
        result.put("rows", rows);
        return result;
    }
}
