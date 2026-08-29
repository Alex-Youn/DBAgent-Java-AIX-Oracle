package com.dbagent.monitor;

import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Java port of the Oracle-monitoring routes in api_server.py (session/lock/tablespace/dashboard/etc). */
@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final OracleConnectionPoolManager poolManager;
    private final OracleQueryHelper queryHelper;

    public MonitorService(OracleConnectionPoolManager poolManager, OracleQueryHelper queryHelper) {
        this.poolManager = poolManager;
        this.queryHelper = queryHelper;
    }

    // ---------------------------------------------------------------- tmlock
    public List<Map<String, Object>> getTmLocks(TargetDbConfig target) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            int instId = queryHelper.getInstId(conn, target);
            String query = "SELECT " +
                    "hl.sid AS holder_sid, " + instId + " AS holder_inst_id, hs.serial# AS holder_serial, " +
                    "hp.spid AS holder_spid, NVL(hs.username, 'UNKNOWN') AS holder_username, hl.type AS holder_lock_type, " +
                    "DECODE(hl.lmode, 0, 'None', 1, 'Null', 2, 'Row-S', 3, 'Row-X', 4, 'Share', 5, 'S/Row-X', 6, 'Exclusive', TO_CHAR(hl.lmode)) AS holder_mode, " +
                    "o.object_name AS holder_object_waiting, TO_CHAR(hl.ctime) AS holder_time, " +
                    "TO_CHAR(hs.logon_time, 'YYYY-MM-DD HH24:MI:SS') AS holder_login, hs.status AS holder_status, " +
                    "hs.program AS holder_program, hs.machine AS holder_machine, " +
                    "wl.sid AS waiter_sid, " + instId + " AS waiter_inst_id, ws.serial# AS waiter_serial, " +
                    "wp.spid AS waiter_spid, NVL(ws.username, 'UNKNOWN') AS waiter_username, wl.type AS waiter_lock_type, " +
                    "DECODE(wl.request, 0, 'None', 1, 'Null', 2, 'Row-S', 3, 'Row-X', 4, 'Share', 5, 'S/Row-X', 6, 'Exclusive', TO_CHAR(wl.request)) AS waiter_mode, " +
                    "o.object_name AS waiter_object_waiting, TO_CHAR(wl.ctime) AS waiter_time, " +
                    "TO_CHAR(ws.logon_time, 'YYYY-MM-DD HH24:MI:SS') AS waiter_login, ws.status AS waiter_status, " +
                    "ws.program AS waiter_program, ws.machine AS waiter_machine " +
                    "FROM v$lock hl " +
                    "JOIN v$session hs ON hl.sid = hs.sid " +
                    "LEFT JOIN v$process hp ON hs.paddr = hp.addr " +
                    "LEFT JOIN v$lock wl ON hl.id1 = wl.id1 AND hl.id2 = wl.id2 AND wl.request > 0 " +
                    "LEFT JOIN v$session ws ON wl.sid = ws.sid " +
                    "LEFT JOIN v$process wp ON ws.paddr = wp.addr " +
                    "LEFT JOIN dba_objects o ON hl.id1 = o.object_id AND hl.type = 'TM' " +
                    "WHERE hl.block = 1";

            Map<Integer, Map<String, Object>> holdersMap = new LinkedHashMap<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(query)) {
                while (rs.next()) {
                    int hSid = rs.getInt("holder_sid");
                    Map<String, Object> holder = holdersMap.computeIfAbsent(hSid, k -> {
                        Map<String, Object> h = new LinkedHashMap<>();
                        try {
                            h.put("sid", hSid);
                            h.put("inst_id", rs.getObject("holder_inst_id"));
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
                        waiter.put("inst_id", rs.getObject("waiter_inst_id"));
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
                        waiter.put("is_holder", false);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> waiters = (List<Map<String, Object>>) holder.get("waiters");
                        waiters.add(waiter);
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
                "s.machine as machine_name, NVL(s.username, 'BACKGROUND') as username, s.program as program_name, " +
                "s.last_call_et as duration_time, " +
                "NVL(a.cpu_pct, 0) || ',' || NVL(a.user_io_pct, 0) || ',' || NVL(a.system_io_pct, 0) || ',' || " +
                "NVL(a.latch_pct, 0) || ',' || NVL(a.tx_lock_pct, 0) || ',' || NVL(a.tm_lock_pct, 0) || ',' || NVL(a.other_pct, 0) as session_wait_pct, " +
                "s.sql_id, s.event as event_name, sq.plan_hash_value, sq.sql_text, s.taddr, s.command, s.osuser, " +
                "TO_CHAR(SYSDATE, 'YYYY-MM-DD HH24:MI:SS') as capture_time " +
                "FROM v$session s " +
                "LEFT JOIN v$process p ON s.paddr = p.addr " +
                "LEFT JOIN ash_summary a ON s.sid = a.session_id AND s.serial# = a.session_serial# " +
                "LEFT JOIN v$sql sq ON s.sql_id = sq.sql_id AND s.sql_child_number = sq.child_number " +
                "WHERE s.type != 'BACKGROUND' AND s.status = 'ACTIVE' " +
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

    // ------------------------------------------------------------ session_extra
    // Feeds the "Current Session" screen's Active Transaction / Parallel Session / 2pc Pending
    // Transaction tabs and trend chart. Each piece is independently best-effort: DBA_2PC_PENDING in
    // particular requires a privilege many app accounts won't have, so one missing grant shouldn't
    // blank out the other tabs/lines.
    public Map<String, Object> getSessionExtra(TargetDbConfig target) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("active_transactions", queryActiveTransactions(conn));
            result.put("parallel_sessions", queryParallelSessions(conn));
            result.put("pending_2pc", queryPending2pc(conn));
            result.put("lock_wait_count", queryLockWaitCount(conn));
            return result;
        }
    }

    private List<Map<String, Object>> queryActiveTransactions(Connection conn) {
        String query = "SELECT s.sid, s.serial#, s.username, s.status, s.machine, s.program, s.sql_id, " +
                "t.start_time, t.used_ublk, t.used_urec " +
                "FROM v$transaction t JOIN v$session s ON s.saddr = t.ses_addr " +
                "ORDER BY s.sid";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(query)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sid", rs.getObject("sid"));
                row.put("serial", rs.getObject("serial#"));
                row.put("username", rs.getString("username"));
                row.put("status", rs.getString("status"));
                row.put("machine", rs.getString("machine"));
                row.put("program", rs.getString("program"));
                row.put("sql_id", rs.getString("sql_id"));
                row.put("start_time", rs.getString("start_time"));
                row.put("used_ublk", rs.getObject("used_ublk"));
                row.put("used_urec", rs.getObject("used_urec"));
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

    private int queryLockWaitCount(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(DISTINCT sid) as cnt FROM v$lock WHERE request > 0")) {
            if (rs.next()) {
                return rs.getInt("cnt");
            }
        } catch (SQLException ignored) {
        }
        return 0;
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
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM v$session WHERE status = 'ACTIVE' AND type != 'BACKGROUND'")) {
                if (rs.next()) activeSessions = rs.getInt(1);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("cpu", cpu);
            result.put("memory", memUtil);
            result.put("active_sessions", activeSessions);
            return result;
        }
    }

    // ------------------------------------------------------------- top_events
    public List<Map<String, Object>> getTopEvents(TargetDbConfig target) throws SQLException {
        String query = "SELECT NVL(event, 'ON CPU') as event, COUNT(*) as cnt " +
                "FROM v$session WHERE status = 'ACTIVE' AND type != 'BACKGROUND' " +
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
    public Map<String, Object> getSessionQuery(TargetDbConfig target, String sidVal, String sqlIdVal) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            Object sSid = sidVal;
            Object sSerial = null;
            String sSqlId = sqlIdVal;
            Integer sChildNumber = null;

            if (sidVal != null && !Strings.isBlank(sidVal)) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT sid, serial#, NVL(sql_id, prev_sql_id) as sql_id, NVL(sql_child_number, prev_child_number) as child_number " +
                                "FROM v$session WHERE sid = ?")) {
                    ps.setString(1, sidVal);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            sSid = rs.getObject(1);
                            sSerial = rs.getObject(2);
                            String rowSqlId = rs.getString(3);
                            sSqlId = (rowSqlId != null && !Strings.isBlank(rowSqlId)) ? rowSqlId : sSqlId;
                            Object childNum = rs.getObject(4);
                            sChildNumber = childNum == null ? null : ((Number) childNum).intValue();
                        } else if (sSqlId == null || Strings.isBlank(sSqlId)) {
                            try (PreparedStatement ashPs = conn.prepareStatement(
                                    "SELECT sql_id, sql_child_number FROM (" +
                                            "SELECT sql_id, sql_child_number FROM v$active_session_history " +
                                            "WHERE session_id = ? AND sql_id IS NOT NULL ORDER BY sample_time DESC" +
                                            ") WHERE ROWNUM = 1")) {
                                ashPs.setString(1, sidVal);
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
                    "SELECT sql_fulltext, hash_value FROM v$sql WHERE sql_id = ? AND ROWNUM = 1")) {
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
        String query = "SELECT count(distinct s.sid) FROM v$session s " +
                "JOIN v$lock l ON s.sid = l.sid JOIN dba_objects o ON l.id1 = o.object_id " +
                "WHERE l.type = 'TM' AND s.blocking_session IS NULL AND s.last_call_et >= 60 " +
                "AND EXISTS (SELECT 1 FROM v$session w WHERE w.blocking_instance = s.inst_id AND w.blocking_session = s.sid)";
        String fallbackQuery = "SELECT /*+ rule */ count(distinct s.sid) FROM v$session s " +
                "JOIN v$lock l ON s.sid = l.sid JOIN dba_objects o ON l.id1 = o.object_id " +
                "WHERE l.type = 'TM' AND s.blocking_session IS NULL AND s.last_call_et >= 60 " +
                "AND EXISTS (SELECT 1 FROM v$session w WHERE w.blocking_session = s.sid)";

        try (Connection conn = poolManager.getConnection(target); Statement st = conn.createStatement()) {
            int count;
            try (ResultSet rs = st.executeQuery(query)) {
                count = rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException e) {
                try (ResultSet rs = st.executeQuery(fallbackQuery)) {
                    count = rs.next() ? rs.getInt(1) : 0;
                }
            }
            return Maps.of("count", count);
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
    // Feeds the "Fleet Overview" landing page (fleet-overview-test-blue.html): one compact status
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

    public Map<String, Object> getFleetStatus(TargetDbConfig target) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", target.id());
        try (Connection conn = poolManager.getConnection(target); Statement st = conn.createStatement()) {
            String instanceStatus = "down";
            double uptimeDays = 0;
            try (ResultSet rs = st.executeQuery("SELECT status, (SYSDATE - startup_time) AS uptime_days FROM v$instance")) {
                if (rs.next()) {
                    instanceStatus = "OPEN".equals(rs.getString("status")) ? "alive" : "down";
                    uptimeDays = rs.getDouble("uptime_days");
                }
            }
            if (!"alive".equals(instanceStatus)) {
                result.put("status", "down");
                result.put("errorMessage", "인스턴스가 OPEN 상태가 아닙니다.");
                return result;
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
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM v$session WHERE status = 'ACTIVE' AND type != 'BACKGROUND'")) {
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
    public List<Map<String, Object>> getHistorySessions(TargetDbConfig target, String startTime, String endTime, String users) throws SQLException {
        String userFilter = buildUserFilter(users);
        String query = "WITH combined_ash AS (" +
                "SELECT session_id, session_serial#, sql_id, event, sample_time, sql_exec_start, program, user_id, sql_plan_hash_value, session_type " +
                "FROM v$active_session_history " +
                "WHERE sample_time BETWEEN TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') " +
                "UNION ALL " +
                "SELECT session_id, session_serial#, sql_id, event, sample_time, sql_exec_start, program, user_id, sql_plan_hash_value, session_type " +
                "FROM dba_hist_active_sess_history " +
                "WHERE sample_time BETWEEN TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI')" +
                "), sql_execs AS (" +
                "SELECT sql_id, SUM(executions) as exec_count FROM v$sqlarea GROUP BY sql_id" +
                ") SELECT h.session_id as sid, h.session_serial# as serial, h.sql_id, NVL(h.event, 'ON CPU') as event_name, " +
                "TO_CHAR(h.sample_time, 'YYYY-MM-DD HH24:MI:SS') as capture_time, " +
                "ROUND((CAST(h.sample_time AS DATE) - CAST(NVL(h.sql_exec_start, h.sample_time) AS DATE)) * 24 * 60 * 60, 2) as duration_time, " +
                "h.program as program_name, u.username as osuser, h.sql_plan_hash_value as plan_hash_value, NVL(s.exec_count, 0) as exec_count " +
                "FROM combined_ash h " +
                "LEFT JOIN dba_users u ON h.user_id = u.user_id " +
                "LEFT JOIN sql_execs s ON h.sql_id = s.sql_id " +
                "WHERE h.session_type = 'FOREGROUND'" + userFilter +
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
                    sessions.add(row);
                }
            }
        }
        return sessions;
    }

    // --------------------------------------------------- history_top_sessions
    public List<Map<String, Object>> getHistoryTopSessions(TargetDbConfig target, String startTime, String endTime, String users) throws SQLException {
        String userFilter = buildUserFilter(users);
        String query = "WITH combined_ash AS (" +
                "SELECT session_id, session_serial#, sql_id, sql_exec_id, sql_exec_start, event, sample_time, program, user_id, sql_plan_hash_value, session_type " +
                "FROM v$active_session_history " +
                "WHERE sample_time BETWEEN TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND sql_exec_start IS NOT NULL " +
                "UNION ALL " +
                "SELECT session_id, session_serial#, sql_id, sql_exec_id, sql_exec_start, event, sample_time, program, user_id, sql_plan_hash_value, session_type " +
                "FROM dba_hist_active_sess_history " +
                "WHERE sample_time BETWEEN TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND TO_DATE(?, 'YYYY-MM-DD\"T\"HH24:MI') AND sql_exec_start IS NOT NULL" +
                ") SELECT h.sql_id, COUNT(DISTINCT h.sql_exec_id) as exec_count, " +
                "MAX(ROUND((CAST(h.sample_time AS DATE) - CAST(h.sql_exec_start AS DATE)) * 24 * 60 * 60, 2)) as max_duration_time, " +
                "MAX(NVL(h.event, 'ON CPU')) as event_name, TO_CHAR(MAX(h.sample_time), 'YYYY-MM-DD HH24:MI:SS') as capture_time, " +
                "MAX(h.program) as program_name, MAX(u.username) as osuser, MAX(h.sql_plan_hash_value) as plan_hash_value, " +
                "MAX(h.session_id) as sid, MAX(h.session_serial#) as serial " +
                "FROM combined_ash h LEFT JOIN dba_users u ON h.user_id = u.user_id " +
                "WHERE h.session_type = 'FOREGROUND'" + userFilter +
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

    // ------------------------------------------------------------- db_users
    public List<String> getDbUsers(TargetDbConfig target) throws SQLException {
        List<String> users = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT username FROM dba_users WHERE account_status = 'OPEN' ORDER BY username")) {
            while (rs.next()) users.add(rs.getString(1));
        }
        return users;
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

    private Object orZero(Object value) {
        return value == null ? 0 : value;
    }
}
