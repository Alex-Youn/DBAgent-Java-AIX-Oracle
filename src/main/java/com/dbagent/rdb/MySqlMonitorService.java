package com.dbagent.rdb;

import com.dbagent.oracle.TargetDbConfig;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
        // MySQL/MariaDB 에는 Oracle 데이터파일 같은 "미리 할당해 둔 크기" 개념이 없다. 예전에는
        // Oracle 테이블스페이스 요약 UI(전체 할당량/사용량/사용률)를 그대로 쓰려고 total_mb=used_mb,
        // free_mb=0, used_pct=100 을 채워 넣었는데, 그러면 화면의 사용률이 <b>항상 100%</b> 로 나와
        // "곧 꽉 찬다" 는 거짓 경보가 된다(2026-09-05 발견, 09-05 문서 15-2절). 모르는 값은 채우지 말고
        // null 로 두고 화면이 '-' 로 그리게 한다 - 용량 조회 탭(getCapacity)이 이미 쓰는 방식이고,
        // 이 앱이 계속 피해 온 "항상 100%" 류 잡음을 막는 원칙이다.
        //
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
                row.put("total_mb", null);
                row.put("used_mb", usedMb);
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

            // Innodb_buffer_pool_reads counts background/startup page reads (redo/undo, doublewrite)
            // that aren't tied to a logical read request, so on a freshly started instance it can
            // exceed read_requests and make (1 - reads/read_requests) negative. This used to clamp
            // that to 0, which is worse than showing nothing: "hit rate 0%" reads as "the cache never
            // hits", an alarming claim about a server that has simply not served any real reads yet
            // (2026-09-05: a just-started MariaDB showed 0% with reads=155 > read_requests=43).
            // Now the value is left null in that window and the dashboard shows "-" with the reason.
            Double hitRatePct = null;
            String hitRateNote = null;
            if (readRequests <= 0) {
                hitRateNote = "논리 읽기 요청이 아직 없어 버퍼 풀 적중률을 계산할 수 없습니다.";
            } else if (diskReads > readRequests) {
                hitRateNote = "기동 직후라 버퍼 풀 카운터가 아직 유효하지 않습니다"
                        + " (reads=" + diskReads + " > read_requests=" + readRequests + ").";
            } else {
                double pct = (1.0 - ((double) diskReads / readRequests)) * 100.0;
                hitRatePct = Math.round(pct * 100.0) / 100.0;
            }

            result.put("uptimeSeconds", uptimeSeconds);
            result.put("uptimeLabel", formatUptime(uptimeSeconds));
            result.put("qps", Math.round(qps * 100.0) / 100.0);
            result.put("bufferPoolBytes", bufferPoolBytes);
            result.put("bufferPoolGib", Math.round((bufferPoolBytes / 1073741824.0) * 100.0) / 100.0);
            result.put("bufferPoolHitRatePct", hitRatePct);
            result.put("bufferPoolHitRateNote", hitRateNote);
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

    // =============================================================================================
    // RDB 대시보드 세션 화면 3종 (문서 "세션리스트 및 세션 정보 조회 쿼리.md" 4절)
    //
    // !! MySQL 과 MariaDB 는 여기서 갈라진다. MariaDB 11.8 에는 performance_schema.processlist 도
    //    data_locks/data_lock_waits 도 존재하지 않고(2026-09-04 실측: ERROR 1146), 반대로 MySQL 8.0
    //    에서 제거된 information_schema.INNODB_LOCKS/INNODB_LOCK_WAITS 는 MariaDB 에 남아 있다.
    //    wire protocol 이 호환된다고 모니터링 뷰까지 같지는 않다.
    // =============================================================================================

    /**
     * MariaDB 여부. databases.json 의 db_type 을 그대로 믿지 않고 JDBC 메타데이터의 제품 버전
     * 문자열("11.8.9-MariaDB-...")로 먼저 판정한다 - db_type 을 mysql 로 잘못 등록한 MariaDB 에
     * MySQL 전용 쿼리를 날리면 ERROR 1146 으로 화면이 통째로 죽는데, 그건 설정 실수치고는 대가가
     * 너무 크다. 메타데이터는 드라이버가 접속 시 이미 받아 둔 값이라 추가 쿼리가 나가지 않는다.
     */
    private boolean isMariaDb(TargetDbConfig target, Connection conn) {
        try {
            String version = conn.getMetaData().getDatabaseProductVersion();
            if (version != null && !version.isEmpty()) {
                return version.toLowerCase(Locale.US).contains("mariadb");
            }
        } catch (SQLException e) {
            // 메타데이터를 못 읽으면 등록값으로 판단한다.
        }
        return "mariadb".equalsIgnoreCase(target.dbType());
    }

    @Override
    public List<Map<String, Object>> getSessionList(TargetDbConfig target) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target)) {
            // 유휴 세션(Sleep)과 서버 자신의 백그라운드 스레드(Daemon - event_scheduler 등), 그리고
            // 이 모니터링 쿼리 자신을 뺀다. Daemon 제외는 문서에 없는 추가분인데, 넣지 않으면 활성
            // 세션 목록 맨 위에 항상 event_scheduler 한 줄이 상주해 실제 세션을 밀어낸다.
            String sql = "SELECT id AS session_id, user, host, db, command, " +
                    "time AS duration_seconds, state, LEFT(info, 500) AS query_preview " +
                    "FROM " + processlistView(target, conn) + " " +
                    "WHERE command NOT IN ('Sleep', 'Daemon') AND id <> CONNECTION_ID() " +
                    "ORDER BY time DESC";
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("session_id", rs.getObject("session_id"));
                    row.put("user", rs.getString("user"));
                    row.put("host", rs.getString("host"));
                    row.put("db", rs.getString("db"));
                    // MySQL/MariaDB 에는 클라이언트 프로그램명 개념이 없다(문서 2절 매핑표의 '-').
                    row.put("program", null);
                    row.put("status", rs.getString("command"));
                    row.put("duration_seconds", rs.getObject("duration_seconds"));
                    row.put("wait_event", rs.getString("state"));
                    row.put("query_preview", rs.getString("query_preview"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    @Override
    public Map<String, Object> getSessionDetail(TargetDbConfig target, long sessionId) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = poolManager.getConnection(target)) {
            String sql = "SELECT id, user, host, db, command, state, time, info " +
                    "FROM " + processlistView(target, conn) + " WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        result.put("found", false);
                        return result;
                    }
                    List<Map<String, Object>> fields = new ArrayList<>();
                    fields.add(field("세션 ID", rs.getObject("id")));
                    fields.add(field("계정", rs.getString("user")));
                    fields.add(field("접속 호스트", rs.getString("host")));
                    fields.add(field("현재 DB", rs.getString("db")));
                    fields.add(field("명령 유형", rs.getString("command")));
                    fields.add(field("처리 상태", rs.getString("state")));
                    fields.add(field("경과 시간(초)", rs.getObject("time")));
                    result.put("found", true);
                    result.put("session_id", rs.getObject("id"));
                    result.put("fields", fields);
                    result.put("sql_text", rs.getString("info"));
                }
            }
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> getLockWaits(TargetDbConfig target) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target)) {
            boolean mariadb = isMariaDb(target, conn);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(mariadb ? MARIADB_LOCK_SQL : MYSQL_LOCK_SQL)) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("waiter_session_id", rs.getObject("waiter_session_id"));
                    row.put("waiter_user", rs.getString("waiter_user"));
                    row.put("waiter_host", rs.getString("waiter_host"));
                    row.put("wait_duration_sec", rs.getObject("wait_duration_sec"));
                    // 엔진별 대기 유형 표현이 달라 문자열로 합쳐 담는다 - 여기서는
                    // "요청한 락 모드 (대상 테이블)" 형태.
                    row.put("wait_type", lockWaitType(rs.getString("requested_mode"), rs.getString("target_table")));
                    row.put("waiter_query", rs.getString("waiter_query"));
                    row.put("blocker_session_id", rs.getObject("blocker_session_id"));
                    row.put("blocker_user", rs.getString("blocker_user"));
                    row.put("blocker_host", rs.getString("blocker_host"));
                    row.put("blocker_state", rs.getString("holding_mode"));
                    row.put("blocker_query", rs.getString("blocker_query"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /** MySQL 8 은 performance_schema, MariaDB 는 information_schema - 위 구분 주석 참고. */
    private String processlistView(TargetDbConfig target, Connection conn) {
        return isMariaDb(target, conn) ? "information_schema.PROCESSLIST" : "performance_schema.processlist";
    }

    private Map<String, Object> field(String label, Object value) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("label", label);
        f.put("value", value);
        return f;
    }

    private String lockWaitType(String mode, String table) {
        if (mode == null && table == null) {
            return null;
        }
        if (table == null) {
            return mode;
        }
        return (mode == null ? "?" : mode) + " (" + table + ")";
    }

    /**
     * MySQL 8.0+ 락 대기 체인. 문서 4.4 를 실측(2026-09-05, MySQL 8.4.11)으로 고쳐 쓴 것이다 -
     * 원문은 data_lock_waits 에서 requested_lock_mode / blocking_lock_mode 를 읽는데 그 뷰에는
     * 두 컬럼이 없다(ERROR 1054). 락 모드는 data_locks 쪽에 있으므로 요청/블로킹 lock_id 로
     * data_locks 를 각각 조인해서 가져온다.
     */
    private static final String MYSQL_LOCK_SQL =
            "SELECT w_th.processlist_id AS waiter_session_id, " +
            "       w_th.processlist_user AS waiter_user, " +
            "       w_th.processlist_host AS waiter_host, " +
            "       w_th.processlist_time AS wait_duration_sec, " +
            "       w_l.lock_mode AS requested_mode, " +
            "       CONCAT(w_l.object_schema, '.', w_l.object_name) AS target_table, " +
            "       LEFT(w_th.processlist_info, 300) AS waiter_query, " +
            "       b_th.processlist_id AS blocker_session_id, " +
            "       b_th.processlist_user AS blocker_user, " +
            "       b_th.processlist_host AS blocker_host, " +
            "       b_l.lock_mode AS holding_mode, " +
            "       LEFT(b_th.processlist_info, 300) AS blocker_query " +
            "FROM performance_schema.data_lock_waits w " +
            "JOIN performance_schema.data_locks w_l ON w_l.engine_lock_id = w.requesting_engine_lock_id " +
            "JOIN performance_schema.data_locks b_l ON b_l.engine_lock_id = w.blocking_engine_lock_id " +
            "JOIN performance_schema.threads w_th ON w_th.thread_id = w.requesting_thread_id " +
            "JOIN performance_schema.threads b_th ON b_th.thread_id = w.blocking_thread_id " +
            "ORDER BY w_th.processlist_time DESC";

    /**
     * MariaDB(및 MySQL 5.7 이하) 락 대기 체인. 문서 4.5 를 실측(2026-09-05, MariaDB 11.8.9)으로
     * 고쳐 쓴 것이다 - 원문은 innodb_lock_waits 의 컬럼을 requesting_lock_id 로 적었는데 실제
     * 이름은 requested_lock_id 다(ERROR 1054). 이 뷰가 트랜잭션 id 도 함께 주므로 원문처럼
     * innodb_locks 를 거쳐 트랜잭션을 찾아갈 필요 없이 innodb_trx 로 바로 조인해 조인 수를 줄였고,
     * 락 모드/대상 테이블만 innodb_locks 에서 LEFT JOIN 으로 덧붙인다 - 락 행이 그 사이 사라져도
     * 대기 관계 자체는 사라지지 않게 하기 위함이다.
     */
    private static final String MARIADB_LOCK_SQL =
            "SELECT w_p.id AS waiter_session_id, " +
            "       w_p.user AS waiter_user, " +
            "       w_p.host AS waiter_host, " +
            "       w_p.time AS wait_duration_sec, " +
            "       w_l.lock_mode AS requested_mode, " +
            "       w_l.lock_table AS target_table, " +
            "       LEFT(w_p.info, 300) AS waiter_query, " +
            "       b_p.id AS blocker_session_id, " +
            "       b_p.user AS blocker_user, " +
            "       b_p.host AS blocker_host, " +
            "       b_l.lock_mode AS holding_mode, " +
            "       LEFT(b_p.info, 300) AS blocker_query " +
            "FROM information_schema.innodb_lock_waits w " +
            "JOIN information_schema.innodb_trx w_t ON w_t.trx_id = w.requesting_trx_id " +
            "JOIN information_schema.processlist w_p ON w_p.id = w_t.trx_mysql_thread_id " +
            "LEFT JOIN information_schema.innodb_locks w_l ON w_l.lock_id = w.requested_lock_id " +
            "JOIN information_schema.innodb_trx b_t ON b_t.trx_id = w.blocking_trx_id " +
            "JOIN information_schema.processlist b_p ON b_p.id = b_t.trx_mysql_thread_id " +
            "LEFT JOIN information_schema.innodb_locks b_l ON b_l.lock_id = w.blocking_lock_id " +
            "ORDER BY w_p.time DESC";

    /**
     * MySQL/MariaDB 는 {@code KILL <id>} 로 커넥션을 끊는다({@code KILL QUERY} 는 쿼리만 취소하고
     * 커넥션은 남는데, 오라클 화면의 kill 이 세션 자체를 끊는 것과 맞추려고 커넥션을 끊는 쪽을 쓴다).
     * 이 명령은 바인드 파라미터를 받지 못하므로 문자열로 조립한다 - 그래서 파라미터가 long 이다
     * (EngineMonitorService.killSession 주석 참고).
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
            // ER_NO_SUCH_THREAD(1094) - 누르기 직전에 스스로 끝난 세션이다. 사용자에게는
            // 원문 에러보다 "이미 종료되었다" 가 정확한 설명이다.
            result.put("message", e.getErrorCode() == 1094
                    ? "이미 종료된 세션입니다." : e.getMessage());
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // 용량 조회 (EngineMonitorService 의 용량 섹션 주석 참고)
    //
    // MySQL/MariaDB 에는 오라클의 테이블스페이스에 해당하는 관리 단위가 없다(InnoDB 의
    // file-per-table 은 테이블 하나가 파일 하나일 뿐 묶음이 아니다). 그래서 스키마(=데이터베이스)를
    // 1단으로 잡는다.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Map<String, Object> getCapacity(TargetDbConfig target) throws SQLException {
        // getStorage() 와 달리 schemata 에서 시작해 LEFT JOIN 한다 - 테이블이 하나도 없는 스키마도
        // 0 MB 행으로 나와야 한다(목록에서 빠지면 "조회 실패" 로 읽힌다).
        String sql = "SELECT s.schema_name AS name, " +
                "COUNT(t.table_name) AS table_count, " +
                "ROUND(COALESCE(SUM(t.data_length), 0) / 1048576, 2) AS data_mb, " +
                "ROUND(COALESCE(SUM(t.index_length), 0) / 1048576, 2) AS index_mb, " +
                "ROUND(COALESCE(SUM(t.data_length + t.index_length), 0) / 1048576, 2) AS used_mb, " +
                "ROUND(COALESCE(SUM(t.data_free), 0) / 1048576, 2) AS free_mb " +
                "FROM information_schema.schemata s " +
                "LEFT JOIN information_schema.tables t " +
                "  ON t.table_schema = s.schema_name AND t.table_type = 'BASE TABLE' " +
                "WHERE s.schema_name NOT IN ('information_schema','mysql','performance_schema','sys') " +
                "GROUP BY s.schema_name ORDER BY used_mb DESC";
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
                // 미리 할당해 두는 개념이 없다 - 억지로 used 를 넣으면 사용률이 항상 100% 가 된다.
                row.put("total_mb", null);
                row.put("free_mb", rs.getObject("free_mb"));
                row.put("used_pct", null);
                rows.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unit", "스키마");
        result.put("note", "MySQL/MariaDB에는 Oracle의 테이블스페이스에 해당하는 관리 단위가 없어 " +
                "스키마(데이터베이스) 기준으로 집계합니다. 시스템 스키마" +
                "(information_schema/mysql/performance_schema/sys)는 제외했습니다. " +
                "미리 할당해 두는 개념이 없어 '할당'과 '사용률'은 표시하지 않습니다. " +
                "'여유'는 InnoDB가 재사용할 수 있는 조각 공간(data_free)이며, 통계 기반이라 정확한 값이 아닙니다.");
        result.put("rows", rows);
        return result;
    }

    @Override
    public Map<String, Object> getCapacityDetail(TargetDbConfig target, String scope) throws SQLException {
        // scope 는 사용자가 고른 스키마명이다 - 반드시 바인딩한다.
        String sql = "SELECT table_name AS name, table_rows AS row_count, " +
                "ROUND(data_length / 1048576, 2) AS data_mb, " +
                "ROUND(index_length / 1048576, 2) AS index_mb, " +
                "ROUND((data_length + index_length) / 1048576, 2) AS total_mb, " +
                "ROUND(data_free / 1048576, 2) AS free_mb " +
                "FROM information_schema.tables " +
                "WHERE table_schema = ? AND table_type = 'BASE TABLE' " +
                "ORDER BY (data_length + index_length) DESC";
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
                    row.put("free_mb", rs.getObject("free_mb"));
                    rows.add(row);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope);
        // table_rows 는 InnoDB 에서 통계 기반 추정치다 - 정확한 건수로 오해하지 않도록 밝혀둔다.
        result.put("note", rows.isEmpty() ? "이 스키마에는 테이블이 없습니다."
                : "행 수는 InnoDB 통계 기반 추정치라 실제와 다를 수 있습니다(정확한 값은 COUNT(*)).");
        result.put("rows", rows);
        return result;
    }
}
