package com.dbagent.rdb;

import com.dbagent.oracle.TargetDbConfig;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MySQL and MariaDB share the same wire protocol and information_schema/SHOW-command surface used
 * here, so one service handles both db_type=mysql and db_type=mariadb - see EngineMonitorService.
 */
@Service
public class MySqlMonitorService implements EngineMonitorService {

    private final RdbConnectionPoolManager poolManager;

    public MySqlMonitorService(RdbConnectionPoolManager poolManager) {
        this.poolManager = poolManager;
    }

    @Override
    public List<Map<String, Object>> getSessions(TargetDbConfig target) throws SQLException {
        String sql = "SELECT id, user, host, db, command, time, state, info " +
                "FROM information_schema.processlist ORDER BY time DESC";
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
                row.put("command", rs.getString("command"));
                row.put("time", rs.getObject("time"));
                row.put("state", rs.getString("state"));
                row.put("info", rs.getString("info"));
                rows.add(row);
            }
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> getStorage(TargetDbConfig target) throws SQLException {
        // No allocated-vs-used concept like Oracle datafiles - total_mb==used_mb, free_mb=0, so the
        // Oracle tablespace-summary UI (전체 할당량/사용량/사용률) can render this unchanged.
        // Starts from information_schema.schemata (every database), LEFT JOINed to tables - a database
        // with zero tables still needs to show up as its own 0 MB row. Starting from
        // information_schema.tables instead (as an earlier version of this query did) silently drops
        // any empty database from the result entirely, which reads as "테이블 정보를 못 가져왔다" rather
        // than the true "이 DB는 비어 있다".
        String sql = "SELECT s.schema_name AS name, " +
                "ROUND(COALESCE(SUM(t.data_length + t.index_length), 0) / 1048576, 2) AS used_mb " +
                "FROM information_schema.schemata s " +
                "LEFT JOIN information_schema.tables t ON t.table_schema = s.schema_name " +
                "WHERE s.schema_name NOT IN ('information_schema','mysql','performance_schema','sys') " +
                "GROUP BY s.schema_name ORDER BY used_mb DESC";
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
            long activeSession = globalStatusLong(st, "Threads_connected");
            long uptimeSeconds = globalStatusLong(st, "Uptime");
            double uptimeDays = uptimeSeconds / 86400.0;
            double uptimePct = Math.min(100.0, (uptimeDays / 30.0) * 100.0);

            String version = "";
            try (ResultSet rs = st.executeQuery("SELECT VERSION() AS v")) {
                if (rs.next()) {
                    version = rs.getString("v");
                }
            }

            result.put("status", "alive");
            result.put("sid", target.sid());
            result.put("version", version);
            // CPU/memory are OS-level metrics this engine doesn't expose via SQL - shown as 0 in the UI.
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
     * MySQL/MariaDB-only (not part of EngineMonitorService - Postgres has no InnoDB buffer pool or
     * this Queries/Uptime status surface) - powers mysql-overview-dashboard.html's 4 KPI cards.
     * QPS is an average since server start (Queries/Uptime), not an instantaneous rate - a true
     * "current" QPS needs two samples with a delay, which this single-request endpoint doesn't do.
     * Buffer pool "% of total RAM" isn't included - MySQL has no SQL-level way to see host RAM total,
     * especially not through a remote JDBC connection - so the 4th KPI card shows the buffer pool hit
     * rate instead (1 - Innodb_buffer_pool_reads / Innodb_buffer_pool_read_requests), a standard
     * SQL-derivable efficiency metric most real MySQL dashboards show in that slot anyway.
     */
    public Map<String, Object> getOverviewStats(TargetDbConfig target) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            Map<String, Long> status = new HashMap<>();
            try (ResultSet rs = st.executeQuery(
                    "SHOW GLOBAL STATUS WHERE Variable_name IN ('Uptime','Queries'," +
                            "'Innodb_buffer_pool_read_requests','Innodb_buffer_pool_reads')")) {
                while (rs.next()) {
                    status.put(rs.getString(1), rs.getLong(2));
                }
            }
            long uptimeSeconds = status.getOrDefault("Uptime", 0L);
            long queries = status.getOrDefault("Queries", 0L);
            long readRequests = status.getOrDefault("Innodb_buffer_pool_read_requests", 0L);
            long diskReads = status.getOrDefault("Innodb_buffer_pool_reads", 0L);

            long bufferPoolBytes = 0;
            try (ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE 'innodb_buffer_pool_size'")) {
                if (rs.next()) {
                    bufferPoolBytes = rs.getLong(2);
                }
            }

            double qps = uptimeSeconds > 0 ? (double) queries / uptimeSeconds : 0;
            // On a near-idle instance (tiny read_requests count), Innodb_buffer_pool_reads can exceed
            // read_requests - it also counts background/startup page reads (redo/undo, doublewrite)
            // that aren't tied to a logical read request, which would otherwise make this go negative.
            // Clamped to [0, 100] since a hit rate outside that range isn't meaningful to show.
            double hitRatePct = readRequests > 0 ? (1.0 - ((double) diskReads / readRequests)) * 100.0 : 100.0;
            hitRatePct = Math.max(0.0, Math.min(100.0, hitRatePct));

            result.put("uptimeSeconds", uptimeSeconds);
            result.put("uptimeLabel", formatUptime(uptimeSeconds));
            result.put("qps", Math.round(qps * 100.0) / 100.0);
            result.put("bufferPoolBytes", bufferPoolBytes);
            result.put("bufferPoolGib", Math.round((bufferPoolBytes / 1073741824.0) * 100.0) / 100.0);
            result.put("bufferPoolHitRatePct", Math.round(hitRatePct * 100.0) / 100.0);
        } catch (SQLException e) {
            result.put("error", "DB에 연결할 수 없습니다.");
        }
        return result;
    }

    /**
     * MySQL/MariaDB-only - powers most of mysql-overview-dashboard.html's collapsed rows (Table Locks,
     * Temporary Objects, Sorts, Aborted, Network, Memory, Command/Handlers/Processes, Query Cache,
     * Files and Tables, Table Openings, Table Definition Cache). One SHOW GLOBAL STATUS dump plus a
     * couple of SHOW VARIABLES lookups and one information_schema.processlist query - not split into
     * many small round trips. Every value here is a cumulative counter since server start (or a simple
     * average derived from counter/uptime), not a true instantaneous rate or historical time series -
     * this app has no time-series store, so "Process States Hourly" (which needs history) is reported
     * as unsupported rather than faked.
     */
    public Map<String, Object> getStatusOverview(TargetDbConfig target) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            Map<String, String> status = new HashMap<>();
            try (ResultSet rs = st.executeQuery("SHOW GLOBAL STATUS")) {
                while (rs.next()) {
                    status.put(rs.getString(1), rs.getString(2));
                }
            }
            long uptimeSeconds = statusLong(status, "Uptime");

            // Connections/threads - powers mysql-overview-dashboard.html's "detail panel" comparison
            // (spec mockup vs real data rendered in the same chart+legend visual style).
            result.put("maxUsedConnections", statusLong(status, "Max_used_connections"));
            result.put("threadsConnected", statusLong(status, "Threads_connected"));
            result.put("threadsRunning", statusLong(status, "Threads_running"));
            result.put("threadsCached", statusLong(status, "Threads_cached"));
            result.put("questionsPerSec", uptimeSeconds > 0 ? round2((double) statusLong(status, "Questions") / uptimeSeconds) : 0);
            try (ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE 'max_connections'")) {
                result.put("maxConnections", rs.next() ? parseLong(rs.getString(2)) : 0L);
            }
            try (ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE 'thread_cache_size'")) {
                result.put("threadCacheSize", rs.next() ? parseLong(rs.getString(2)) : 0L);
            }

            // Table Locks
            result.put("tableLocksImmediate", statusLong(status, "Table_locks_immediate"));
            result.put("tableLocksWaited", statusLong(status, "Table_locks_waited"));

            // Temporary Objects
            long tmpTables = statusLong(status, "Created_tmp_tables");
            long tmpDiskTables = statusLong(status, "Created_tmp_disk_tables");
            result.put("createdTmpTables", tmpTables);
            result.put("createdTmpDiskTables", tmpDiskTables);
            result.put("createdTmpFiles", statusLong(status, "Created_tmp_files"));
            result.put("tmpDiskTablePct", tmpTables > 0 ? round2(100.0 * tmpDiskTables / tmpTables) : 0);

            // Sorts
            result.put("sortRows", statusLong(status, "Sort_rows"));
            result.put("sortRange", statusLong(status, "Sort_range"));
            result.put("sortScan", statusLong(status, "Sort_scan"));
            result.put("sortMergePasses", statusLong(status, "Sort_merge_passes"));

            // Aborted
            result.put("abortedClients", statusLong(status, "Aborted_clients"));
            result.put("abortedConnects", statusLong(status, "Aborted_connects"));

            // Network
            long bytesReceived = statusLong(status, "Bytes_received");
            long bytesSent = statusLong(status, "Bytes_sent");
            result.put("bytesReceived", bytesReceived);
            result.put("bytesSent", bytesSent);
            result.put("bytesReceivedPerSec", uptimeSeconds > 0 ? round2((double) bytesReceived / uptimeSeconds) : 0);
            result.put("bytesSentPerSec", uptimeSeconds > 0 ? round2((double) bytesSent / uptimeSeconds) : 0);

            // Memory - InnoDB buffer pool memory only, not host OS memory (see getOverviewStats javadoc).
            result.put("innodbBufferPoolBytesData", statusLong(status, "Innodb_buffer_pool_bytes_data"));
            result.put("innodbBufferPoolBytesDirty", statusLong(status, "Innodb_buffer_pool_bytes_dirty"));

            // Commands
            result.put("comSelect", statusLong(status, "Com_select"));
            result.put("comInsert", statusLong(status, "Com_insert"));
            result.put("comUpdate", statusLong(status, "Com_update"));
            result.put("comDelete", statusLong(status, "Com_delete"));
            result.put("comReplace", statusLong(status, "Com_replace"));
            result.put("questions", statusLong(status, "Questions"));
            result.put("comCommit", statusLong(status, "Com_commit"));
            result.put("comRollback", statusLong(status, "Com_rollback"));
            result.put("comBegin", statusLong(status, "Com_begin"));

            // Handlers
            long hReadKey = statusLong(status, "Handler_read_key");
            long hReadNext = statusLong(status, "Handler_read_next");
            long hReadRnd = statusLong(status, "Handler_read_rnd");
            long hReadRndNext = statusLong(status, "Handler_read_rnd_next");
            long hWrite = statusLong(status, "Handler_write");
            long hUpdate = statusLong(status, "Handler_update");
            long hDelete = statusLong(status, "Handler_delete");
            result.put("handlerReadKey", hReadKey);
            result.put("handlerReadNext", hReadNext);
            result.put("handlerReadRnd", hReadRnd);
            result.put("handlerReadRndNext", hReadRndNext);
            result.put("handlerWrite", hWrite);
            result.put("handlerUpdate", hUpdate);
            result.put("handlerDelete", hDelete);
            long handlerTotal = hReadKey + hReadNext + hReadRnd + hReadRndNext + hWrite + hUpdate + hDelete;
            result.put("handlerTotalPerSec", uptimeSeconds > 0 ? round2((double) handlerTotal / uptimeSeconds) : 0);

            // Files and Tables / Table Openings / Table Definition Cache
            result.put("openFiles", statusLong(status, "Open_files"));
            result.put("openedFiles", statusLong(status, "Opened_files"));
            result.put("openTables", statusLong(status, "Open_tables"));
            result.put("openedTables", statusLong(status, "Opened_tables"));
            result.put("tableOpenCacheHits", statusLong(status, "Table_open_cache_hits"));
            result.put("tableOpenCacheMisses", statusLong(status, "Table_open_cache_misses"));
            result.put("tableOpenCacheOverflows", statusLong(status, "Table_open_cache_overflows"));
            result.put("openTableDefinitions", statusLong(status, "Open_table_definitions"));
            result.put("openedTableDefinitions", statusLong(status, "Opened_table_definitions"));

            // Query cache: MySQL 8.0+ removed it entirely (have_query_cache=NO, no Qcache_* status
            // vars at all); MariaDB still has it (usually disabled by default, but the vars exist).
            String haveQueryCache = "NO";
            try (ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE 'have_query_cache'")) {
                if (rs.next()) {
                    haveQueryCache = rs.getString(2);
                }
            }
            boolean qcSupported = "YES".equalsIgnoreCase(haveQueryCache);
            result.put("queryCacheSupported", qcSupported);
            if (qcSupported) {
                result.put("qcacheHits", statusLong(status, "Qcache_hits"));
                result.put("qcacheInserts", statusLong(status, "Qcache_inserts"));
                result.put("qcacheFreeMemory", statusLong(status, "Qcache_free_memory"));
                result.put("qcacheQueriesInCache", statusLong(status, "Qcache_queries_in_cache"));
            }

            try (ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE 'table_definition_cache'")) {
                result.put("tableDefinitionCacheSize", rs.next() ? parseLong(rs.getString(2)) : 0L);
            }

            // Process states - real snapshot grouping, not a time series (see javadoc above).
            List<Map<String, Object>> processStates = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT COALESCE(NULLIF(state,''),'(none)') AS st, COUNT(*) AS cnt " +
                            "FROM information_schema.processlist GROUP BY st ORDER BY cnt DESC")) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("state", rs.getString("st"));
                    row.put("count", rs.getLong("cnt"));
                    processStates.add(row);
                }
            }
            result.put("processStates", processStates);
        } catch (SQLException e) {
            result.put("error", "DB에 연결할 수 없습니다.");
        }
        return result;
    }

    private long statusLong(Map<String, String> status, String key) {
        return parseLong(status.get(key));
    }

    private long parseLong(String s) {
        if (s == null) {
            return 0L;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
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

    private long globalStatusLong(Statement st, String variableName) throws SQLException {
        try (ResultSet rs = st.executeQuery("SHOW GLOBAL STATUS LIKE '" + variableName + "'")) {
            if (rs.next()) {
                return rs.getLong("Value");
            }
        }
        return 0L;
    }
}
