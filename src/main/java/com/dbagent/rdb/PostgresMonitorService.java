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
        // Same shape as Oracle's tablespace endpoint (see EngineMonitorService). Postgres 에도 Oracle
        // 데이터파일 같은 "미리 할당해 둔 크기" 개념이 없다 - pg_database_size 는 실제로 쓰고 있는
        // 크기다. 예전에는 total_mb=used_mb, free_mb=0, used_pct=100 을 채워 넣어 화면 사용률이
        // <b>항상 100%</b> 로 나왔다(09-05 문서 15-2절). 모르는 값은 null 로 두고 화면이 '-' 로
        // 그리게 한다(getCapacity 와 같은 방식).
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
                row.put("total_mb", null);
                row.put("used_mb", Math.round(usedMb * 100.0) / 100.0);
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

    // =============================================================================================
    // RDB 대시보드 세션 화면 3종 (문서 "세션리스트 및 세션 정보 조회 쿼리.md" 3절)
    //
    // PostgreSQL 은 세션 정보와 실행 중인 SQL 이 pg_stat_activity 한 뷰에 함께 있어 MS SQL 처럼
    // 뷰를 조인할 필요가 없다. 대신 락 관계는 pg_blocking_pids() 가 블로커 pid '배열' 을 주므로
    // UNNEST 로 펼쳐 1:N 을 그대로 행으로 만든다.
    // =============================================================================================

    @Override
    public List<Map<String, Object>> getSessionList(TargetDbConfig target) throws SQLException {
        // getSessions() 와 달리 datname 으로 좁히지 않는다 - 이 화면은 DBA 가 인스턴스 전체의 활성
        // 세션을 보는 곳이고, 다른 DB 에서 도는 무거운 쿼리가 목록에서 사라지면 오히려 못 찾는다.
        // 대신 db 컬럼(datname)을 함께 내려 어느 DB 의 세션인지 화면에서 구분할 수 있게 한다.
        // client_addr 는 유닉스 소켓 접속이면 NULL 이라 'local' 로 채운다(빈 칸은 조회 실패처럼 보인다).
        String sql = "SELECT pid AS session_id, usename, " +
                "COALESCE(HOST(client_addr), 'local') AS client_host, " +
                "datname, application_name, state, wait_event_type, wait_event, " +
                "ROUND(EXTRACT(EPOCH FROM (clock_timestamp() - query_start))::numeric, 2) AS duration_seconds, " +
                "LEFT(query, 500) AS query_preview " +
                "FROM pg_stat_activity " +
                "WHERE state IS NOT NULL AND state <> 'idle' AND pid <> pg_backend_pid() " +
                "ORDER BY query_start ASC NULLS LAST";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("session_id", rs.getObject("session_id"));
                row.put("user", rs.getString("usename"));
                row.put("host", rs.getString("client_host"));
                row.put("db", rs.getString("datname"));
                row.put("program", rs.getString("application_name"));
                row.put("status", rs.getString("state"));
                row.put("duration_seconds", rs.getObject("duration_seconds"));
                row.put("wait_event", waitEvent(rs.getString("wait_event_type"), rs.getString("wait_event")));
                row.put("query_preview", rs.getString("query_preview"));
                rows.add(row);
            }
        }
        return rows;
    }

    @Override
    public Map<String, Object> getSessionDetail(TargetDbConfig target, long sessionId) throws SQLException {
        String sql = "SELECT pid, usename, COALESCE(HOST(client_addr), 'local') AS client_host, " +
                "datname, application_name, state, wait_event_type, wait_event, " +
                "ROUND(EXTRACT(EPOCH FROM (clock_timestamp() - query_start))::numeric, 2) AS elapsed_sec, " +
                "ROUND(EXTRACT(EPOCH FROM (clock_timestamp() - xact_start))::numeric, 2) AS xact_sec, " +
                "query AS sql_text " +
                "FROM pg_stat_activity WHERE pid = ?";
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = poolManager.getConnection(target);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // pg 의 pid 는 int4 라 int 로 바인딩한다.
            ps.setInt(1, (int) sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    result.put("found", false);
                    return result;
                }
                List<Map<String, Object>> fields = new ArrayList<>();
                fields.add(field("세션 ID (pid)", rs.getObject("pid")));
                fields.add(field("계정", rs.getString("usename")));
                fields.add(field("접속 IP", rs.getString("client_host")));
                fields.add(field("현재 DB", rs.getString("datname")));
                fields.add(field("프로그램명", rs.getString("application_name")));
                fields.add(field("상태", rs.getString("state")));
                fields.add(field("경과 시간(초)", rs.getObject("elapsed_sec")));
                // 트랜잭션을 열어둔 채 방치되는 세션을 잡아내기 위한 값(문서 6.2) - 쿼리 경과보다
                // 훨씬 길면 idle in transaction 으로 락/블로트를 만들고 있을 수 있다.
                fields.add(field("트랜잭션 경과(초)", rs.getObject("xact_sec")));
                fields.add(field("대기 이벤트", waitEvent(rs.getString("wait_event_type"), rs.getString("wait_event"))));
                result.put("found", true);
                result.put("session_id", rs.getObject("pid"));
                result.put("fields", fields);
                result.put("sql_text", rs.getString("sql_text"));
            }
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> getLockWaits(TargetDbConfig target) throws SQLException {
        // 문서 3.3 그대로. pg_blocking_pids() 가 블로커 pid 배열을 주므로 UNNEST 로 펼쳐
        // "대기 세션 1개 : 블로커 N개" 관계를 행으로 편다.
        String sql = "SELECT blocked.pid AS waiter_pid, blocked.usename AS waiter_user, " +
                "COALESCE(HOST(blocked.client_addr), 'local') AS waiter_host, " +
                "ROUND(EXTRACT(EPOCH FROM (clock_timestamp() - blocked.state_change))::numeric, 2) AS wait_duration_sec, " +
                "blocked.wait_event_type, blocked.wait_event, " +
                "LEFT(blocked.query, 300) AS waiter_query, " +
                "blocker.pid AS blocker_pid, blocker.usename AS blocker_user, " +
                "COALESCE(HOST(blocker.client_addr), 'local') AS blocker_host, " +
                "blocker.state AS blocker_state, LEFT(blocker.query, 300) AS blocker_query " +
                "FROM pg_stat_activity blocked " +
                "JOIN LATERAL UNNEST(pg_blocking_pids(blocked.pid)) AS blocker_pid(pid) ON true " +
                "JOIN pg_stat_activity blocker ON blocker.pid = blocker_pid.pid " +
                "ORDER BY wait_duration_sec DESC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("waiter_session_id", rs.getObject("waiter_pid"));
                row.put("waiter_user", rs.getString("waiter_user"));
                row.put("waiter_host", rs.getString("waiter_host"));
                row.put("wait_duration_sec", rs.getObject("wait_duration_sec"));
                row.put("wait_type", waitEvent(rs.getString("wait_event_type"), rs.getString("wait_event")));
                row.put("waiter_query", rs.getString("waiter_query"));
                row.put("blocker_session_id", rs.getObject("blocker_pid"));
                row.put("blocker_user", rs.getString("blocker_user"));
                row.put("blocker_host", rs.getString("blocker_host"));
                row.put("blocker_state", rs.getString("blocker_state"));
                row.put("blocker_query", rs.getString("blocker_query"));
                rows.add(row);
            }
        }
        return rows;
    }

    /** wait_event_type 과 wait_event 를 한 칸에 담는다(둘 다 없으면 null - 화면은 '-' 로 그린다). */
    private String waitEvent(String type, String event) {
        if (type == null && event == null) {
            return null;
        }
        if (event == null) {
            return type;
        }
        if (type == null) {
            return event;
        }
        return type + " / " + event;
    }

    private Map<String, Object> field(String label, Object value) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("label", label);
        f.put("value", value);
        return f;
    }

    /**
     * PostgreSQL 은 명령이 아니라 함수라 유일하게 바인드 파라미터를 쓸 수 있다.
     * {@code pg_terminate_backend()} 는 대상이 이미 없으면 예외 대신 <b>false 를 돌려준다</b> -
     * 그 경우를 성공으로 세면 "성공 1건" 인데 세션은 그대로 남아 있는 것처럼 보이므로 구분한다.
     */
    @Override
    public Map<String, Object> killSession(TargetDbConfig target, long sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId);
        try (Connection conn = poolManager.getConnection(target);
             PreparedStatement ps = conn.prepareStatement("SELECT pg_terminate_backend(?) AS terminated")) {
            ps.setInt(1, (int) sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean terminated = rs.next() && rs.getBoolean("terminated");
                result.put("status", terminated ? "killed" : "error");
                result.put("message", terminated ? null : "이미 종료된 세션입니다.");
            }
        } catch (SQLException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // 용량 조회 (EngineMonitorService 의 용량 섹션 주석 참고)
    //
    // PostgreSQL 에는 진짜 테이블스페이스(pg_tablespace)가 있지만, 기본 구성에서는 pg_default
    // 하나뿐이라 그걸 1단으로 삼으면 행이 한 줄만 나온다. 그래서 접속한 데이터베이스의 스키마를
    // 1단으로 잡는다 - 이 앱은 databases.json 에서 인스턴스마다 접속할 DB 를 지정하므로,
    // "이 인스턴스가 보는 DB 안에서 어디가 무겁나" 가 실제로 필요한 정보다.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Map<String, Object> getCapacity(TargetDbConfig target) throws SQLException {
        // pg_table_size 는 TOAST 를 포함하고 인덱스는 빼며, pg_total_relation_size 는 둘 다 포함한다.
        // 그래서 data + index 가 total 과 대체로 맞아떨어진다.
        // relkind 'r'(일반 테이블)과 'p'(파티션 부모)만 센다 - 인덱스/뷰는 따로 세면 이중 계산이 된다.
        String sql = "SELECT n.nspname AS name, " +
                "COUNT(c.oid) AS table_count, " +
                "ROUND(COALESCE(SUM(pg_table_size(c.oid)), 0) / 1048576.0, 2) AS data_mb, " +
                "ROUND(COALESCE(SUM(pg_indexes_size(c.oid)), 0) / 1048576.0, 2) AS index_mb, " +
                "ROUND(COALESCE(SUM(pg_total_relation_size(c.oid)), 0) / 1048576.0, 2) AS used_mb " +
                "FROM pg_namespace n " +
                "LEFT JOIN pg_class c ON c.relnamespace = n.oid AND c.relkind IN ('r','p') " +
                "WHERE n.nspname NOT IN ('pg_catalog','information_schema') " +
                "  AND n.nspname NOT LIKE 'pg\\_toast%' AND n.nspname NOT LIKE 'pg\\_temp%' " +
                "GROUP BY n.nspname ORDER BY used_mb DESC";
        List<Map<String, Object>> rows = new ArrayList<>();
        String currentDb = "";
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT current_database() AS db")) {
                if (rs.next()) {
                    currentDb = rs.getString("db");
                }
            }
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", rs.getString("name"));
                    row.put("table_count", rs.getObject("table_count"));
                    row.put("data_mb", rs.getObject("data_mb"));
                    row.put("index_mb", rs.getObject("index_mb"));
                    row.put("used_mb", rs.getObject("used_mb"));
                    // 미리 할당해 두는 개념도, 스키마 단위의 여유 공간이라는 개념도 없다.
                    // (autovacuum 이 회수하지 못한 블로트는 여기 숫자로 드러나지 않는다)
                    row.put("total_mb", null);
                    row.put("free_mb", null);
                    row.put("used_pct", null);
                    rows.add(row);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unit", "스키마");
        result.put("note", "접속한 데이터베이스(" + currentDb + ")의 스키마 기준입니다. " +
                "PostgreSQL에도 테이블스페이스가 있지만 기본 구성에서는 pg_default 하나뿐이라 " +
                "스키마 단위가 더 유용합니다. 미리 할당해 두는 개념이 없어 '할당'과 '사용률'은 " +
                "표시하지 않으며, 죽은 튜플(블로트)은 이 숫자에 사용 중으로 잡힙니다.");
        result.put("rows", rows);
        return result;
    }

    @Override
    public Map<String, Object> getCapacityDetail(TargetDbConfig target, String scope) throws SQLException {
        // scope 는 사용자가 고른 스키마명이다 - 반드시 바인딩한다.
        String sql = "SELECT c.relname AS name, c.reltuples::bigint AS row_count, " +
                "ROUND(pg_table_size(c.oid) / 1048576.0, 2) AS data_mb, " +
                "ROUND(pg_indexes_size(c.oid) / 1048576.0, 2) AS index_mb, " +
                "ROUND(pg_total_relation_size(c.oid) / 1048576.0, 2) AS total_mb " +
                "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE c.relkind IN ('r','p') AND n.nspname = ? " +
                "ORDER BY pg_total_relation_size(c.oid) DESC";
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
                    row.put("free_mb", null);
                    rows.add(row);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope);
        // reltuples 는 ANALYZE 시점의 추정치라 -1(아직 분석 안 됨)이 나올 수 있다.
        result.put("note", rows.isEmpty() ? "이 스키마에는 테이블이 없습니다."
                : "행 수는 ANALYZE 시점의 추정치(reltuples)입니다. 한 번도 분석되지 않은 테이블은 -1로 나옵니다.");
        result.put("rows", rows);
        return result;
    }
}
