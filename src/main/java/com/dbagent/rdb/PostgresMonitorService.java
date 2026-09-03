package com.dbagent.rdb;

import com.dbagent.oracle.TargetDbConfig;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PostgresMonitorService implements EngineMonitorService {

    private final RdbConnectionPoolManager poolManager;

    public PostgresMonitorService(RdbConnectionPoolManager poolManager) {
        this.poolManager = poolManager;
    }

    @Override
    public List<Map<String, Object>> getSessions(TargetDbConfig target) throws SQLException {
        String sql = "SELECT pid, usename, client_addr, state, query, application_name, " +
                "EXTRACT(EPOCH FROM (now() - query_start)) AS duration_sec " +
                "FROM pg_stat_activity WHERE datname = current_database() ORDER BY query_start DESC NULLS LAST";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pid", rs.getObject("pid"));
                row.put("user", rs.getString("usename"));
                row.put("host", rs.getString("client_addr"));
                row.put("state", rs.getString("state"));
                row.put("info", rs.getString("query"));
                row.put("application_name", rs.getString("application_name"));
                row.put("duration_sec", rs.getObject("duration_sec"));
                rows.add(row);
            }
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> getStorage(TargetDbConfig target) throws SQLException {
        // Same shape as Oracle's tablespace endpoint (see EngineMonitorService) - Postgres has no
        // allocated-vs-used distinction either, so total_mb==used_mb, free_mb=0.
        String sql = "SELECT datname AS name, pg_database_size(datname) AS bytes " +
                "FROM pg_database WHERE datistemplate = false ORDER BY bytes DESC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                double usedMb = rs.getLong("bytes") / 1048576.0;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tablespace_name", rs.getString("name"));
                row.put("status", "ONLINE");
                row.put("total_mb", Math.round(usedMb * 100.0) / 100.0);
                row.put("used_mb", Math.round(usedMb * 100.0) / 100.0);
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
                    "SELECT count(*) AS c FROM pg_stat_activity WHERE datname = current_database()")) {
                if (rs.next()) {
                    activeSession = rs.getLong("c");
                }
            }
            double uptimeSeconds = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT extract(epoch from now() - pg_postmaster_start_time()) AS s")) {
                if (rs.next()) {
                    uptimeSeconds = rs.getDouble("s");
                }
            }
            double uptimePct = Math.min(100.0, (uptimeSeconds / 86400.0 / 30.0) * 100.0);

            // server_version can carry a packaging suffix ("18.6 (Debian 18.6-1.pgdg13+2)") - keep just
            // the leading version token so the fleet card's "[PostgreSQL <version>]" label stays short.
            String version = "";
            try (ResultSet rs = st.executeQuery("SHOW server_version")) {
                if (rs.next()) {
                    String raw = rs.getString(1);
                    version = raw == null ? "" : raw.split("\\s+")[0];
                }
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
     * PostgreSQL-only (not part of EngineMonitorService - see MySqlMonitorService.getOverviewStats()
     * for the MySQL/MariaDB equivalent) - powers postgres-overview-dashboard.html's 4 KPI cards.
     * TPS is an average since server start (xact_commit+xact_rollback / uptime), not an instantaneous
     * rate. Shared Buffers size comes from pg_settings (setting is in 8kB pages, not bytes directly).
     * Cache hit rate is the standard blks_hit/(blks_hit+blks_read) ratio from pg_stat_database, scoped
     * to the current database only.
     */
    public Map<String, Object> getOverviewStats(TargetDbConfig target) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            double uptimeSeconds = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT extract(epoch from now() - pg_postmaster_start_time()) AS s")) {
                if (rs.next()) {
                    uptimeSeconds = rs.getDouble("s");
                }
            }

            long xactCommit = 0, xactRollback = 0, blksHit = 0, blksRead = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT xact_commit, xact_rollback, blks_hit, blks_read FROM pg_stat_database " +
                            "WHERE datname = current_database()")) {
                if (rs.next()) {
                    xactCommit = rs.getLong("xact_commit");
                    xactRollback = rs.getLong("xact_rollback");
                    blksHit = rs.getLong("blks_hit");
                    blksRead = rs.getLong("blks_read");
                }
            }
            double tps = uptimeSeconds > 0 ? (xactCommit + xactRollback) / uptimeSeconds : 0;
            double hitRatePct = (blksHit + blksRead) > 0 ? (100.0 * blksHit / (blksHit + blksRead)) : 100.0;

            long sharedBuffersBytes = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT setting, unit FROM pg_settings WHERE name = 'shared_buffers'")) {
                if (rs.next()) {
                    sharedBuffersBytes = parseLong(rs.getString("setting")) * unitBytes(rs.getString("unit"));
                }
            }

            result.put("uptimeSeconds", uptimeSeconds);
            result.put("uptimeLabel", formatUptime((long) uptimeSeconds));
            result.put("tps", round2(tps));
            result.put("sharedBuffersBytes", sharedBuffersBytes);
            result.put("sharedBuffersGib", round2(sharedBuffersBytes / 1073741824.0));
            result.put("cacheHitRatePct", round2(hitRatePct));
        } catch (SQLException e) {
            result.put("error", "DB에 연결할 수 없습니다.");
        }
        return result;
    }

    /**
     * PostgreSQL-only - powers postgres-overview-dashboard.html's collapsed accordion rows and the
     * Detail tab's chart panels. Every value here is a cumulative counter since the last stats reset
     * (or a simple average derived from counter/uptime), not a true instantaneous rate or historical
     * time series. Checkpoint stats live in a different view depending on server version - PostgreSQL
     * 17+ split them out into pg_stat_checkpointer (columns num_timed/num_requested/... ), older
     * versions carry them on pg_stat_bgwriter (checkpoints_timed/checkpoints_req/...) - so both are
     * tried and checkpointSupported tells the frontend which (if either) succeeded. pg_stat_wal exists
     * from PostgreSQL 14+ only, gated the same way via walSupported.
     */
    public Map<String, Object> getStatusOverview(TargetDbConfig target) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            long maxConnections = 0;
            try (ResultSet rs = st.executeQuery("SHOW max_connections")) {
                if (rs.next()) {
                    maxConnections = parseLong(rs.getString(1));
                }
            }
            result.put("maxConnections", maxConnections);

            long currentConnections = 0, activeCount = 0, idleCount = 0, idleInTxCount = 0, otherCount = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT COALESCE(state,'(none)') AS st, COUNT(*) AS cnt FROM pg_stat_activity " +
                            "WHERE datname = current_database() GROUP BY st")) {
                while (rs.next()) {
                    String state = rs.getString("st");
                    long cnt = rs.getLong("cnt");
                    currentConnections += cnt;
                    if ("active".equals(state)) {
                        activeCount = cnt;
                    } else if ("idle".equals(state)) {
                        idleCount = cnt;
                    } else if (state != null && state.startsWith("idle in transaction")) {
                        idleInTxCount += cnt;
                    } else {
                        otherCount += cnt;
                    }
                }
            }
            result.put("currentConnections", currentConnections);
            result.put("activeCount", activeCount);
            result.put("idleCount", idleCount);
            result.put("idleInTxCount", idleInTxCount);
            result.put("otherCount", otherCount);

            // pg_locks has no reliable per-lock database column (many lock types - transactionid,
            // virtualxid - carry a NULL database), so it's scoped to the current database the same way
            // as the connections query above: via the owning backend's pg_stat_activity row. A raw,
            // unscoped pg_locks count would silently include every other database on the same server.
            long locksGranted = 0, locksWaiting = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT l.granted, COUNT(*) AS cnt FROM pg_locks l " +
                            "JOIN pg_stat_activity a ON a.pid = l.pid " +
                            "WHERE a.datname = current_database() GROUP BY l.granted")) {
                while (rs.next()) {
                    if (rs.getBoolean("granted")) {
                        locksGranted = rs.getLong("cnt");
                    } else {
                        locksWaiting = rs.getLong("cnt");
                    }
                }
            }
            result.put("locksGranted", locksGranted);
            result.put("locksWaiting", locksWaiting);

            try (ResultSet rs = st.executeQuery(
                    "SELECT xact_commit, xact_rollback, temp_files, temp_bytes, deadlocks, conflicts " +
                            "FROM pg_stat_database WHERE datname = current_database()")) {
                if (rs.next()) {
                    long commit = rs.getLong("xact_commit");
                    long rollback = rs.getLong("xact_rollback");
                    result.put("xactCommit", commit);
                    result.put("xactRollback", rollback);
                    result.put("rollbackPct", (commit + rollback) > 0 ? round2(100.0 * rollback / (commit + rollback)) : 0);
                    result.put("tempFiles", rs.getLong("temp_files"));
                    result.put("tempBytes", rs.getLong("temp_bytes"));
                    result.put("deadlocks", rs.getLong("deadlocks"));
                    result.put("conflicts", rs.getLong("conflicts"));
                }
            }

            try (ResultSet rs = st.executeQuery(
                    "SELECT COALESCE(SUM(seq_scan),0) AS seq_scan, COALESCE(SUM(idx_scan),0) AS idx_scan, " +
                            "COALESCE(SUM(n_tup_ins),0) AS ins, COALESCE(SUM(n_tup_upd),0) AS upd, " +
                            "COALESCE(SUM(n_tup_del),0) AS del, COALESCE(SUM(n_dead_tup),0) AS dead, " +
                            "COALESCE(SUM(autovacuum_count),0) AS av, COALESCE(SUM(autoanalyze_count),0) AS aa " +
                            "FROM pg_stat_user_tables")) {
                if (rs.next()) {
                    result.put("seqScan", rs.getLong("seq_scan"));
                    result.put("idxScan", rs.getLong("idx_scan"));
                    result.put("tupIns", rs.getLong("ins"));
                    result.put("tupUpd", rs.getLong("upd"));
                    result.put("tupDel", rs.getLong("del"));
                    result.put("deadTuples", rs.getLong("dead"));
                    result.put("autovacuumCount", rs.getLong("av"));
                    result.put("autoanalyzeCount", rs.getLong("aa"));
                }
            }

            boolean checkpointSupported = true;
            try (ResultSet rs = st.executeQuery(
                    "SELECT num_timed AS timed, num_requested AS req, buffers_written AS bufs FROM pg_stat_checkpointer")) {
                if (rs.next()) {
                    result.put("checkpointsTimed", rs.getLong("timed"));
                    result.put("checkpointsReq", rs.getLong("req"));
                    result.put("checkpointBuffersWritten", rs.getLong("bufs"));
                }
            } catch (SQLException e) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT checkpoints_timed AS timed, checkpoints_req AS req, buffers_checkpoint AS bufs FROM pg_stat_bgwriter")) {
                    if (rs.next()) {
                        result.put("checkpointsTimed", rs.getLong("timed"));
                        result.put("checkpointsReq", rs.getLong("req"));
                        result.put("checkpointBuffersWritten", rs.getLong("bufs"));
                    }
                } catch (SQLException e2) {
                    checkpointSupported = false;
                }
            }
            result.put("checkpointSupported", checkpointSupported);

            boolean walSupported = true;
            try (ResultSet rs = st.executeQuery("SELECT wal_records, wal_bytes FROM pg_stat_wal")) {
                if (rs.next()) {
                    result.put("walRecords", rs.getLong("wal_records"));
                    result.put("walBytes", rs.getLong("wal_bytes"));
                }
            } catch (SQLException e) {
                walSupported = false;
            }
            result.put("walSupported", walSupported);

            long replicaCount = 0;
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) AS c FROM pg_stat_replication")) {
                if (rs.next()) {
                    replicaCount = rs.getLong("c");
                }
            }
            long replicationSlotCount = 0;
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) AS c FROM pg_replication_slots")) {
                if (rs.next()) {
                    replicationSlotCount = rs.getLong("c");
                }
            }
            result.put("replicaCount", replicaCount);
            result.put("replicationSlotCount", replicationSlotCount);
        } catch (SQLException e) {
            result.put("error", "DB에 연결할 수 없습니다.");
        }
        return result;
    }

    private long unitBytes(String unit) {
        if (unit == null) {
            return 1L;
        }
        switch (unit.trim()) {
            case "8kB": return 8192L;
            case "kB": return 1024L;
            case "MB": return 1048576L;
            case "GB": return 1073741824L;
            case "B": return 1L;
            default: return 1L;
        }
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
}
