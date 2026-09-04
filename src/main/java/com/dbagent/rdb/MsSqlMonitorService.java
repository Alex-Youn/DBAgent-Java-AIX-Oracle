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
        String sql = "SELECT DB_NAME(database_id) AS name, " +
                "ROUND(SUM(CAST(size AS BIGINT)) * 8.0 / 1024, 2) AS used_mb " +
                "FROM sys.master_files WHERE database_id > 4 " +
                "GROUP BY database_id ORDER BY used_mb DESC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                double usedMb = rs.getDouble("used_mb");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tablespace_name", rs.getString("name"));
                row.put("status", "ONLINE");
                row.put("total_mb", usedMb);
                row.put("used_mb", usedMb);
                row.put("free_mb", 0);
                row.put("used_pct", 100);
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
}
