package com.dbagent.monitor;

import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 대시보드 개편 ⑤⑥⑦ Top 목록과 6장 상세 드로어(세션·SQL·이벤트) 조회 - 설계문서 `대시보드 UI 개선 설계.md`
 * 5장 ⑤⑥⑦·6장, 전체 작업순서 F3(2026-09-25).
 *
 * <ul>
 *   <li>모두 선택 구간(DB 시각)의 ASH를 공용 AshRange(D1)로 읽는다 - FOREGROUND, 모니터링 계정 제외, 8분류(CASE8),
 *       보관 범위 밖은 AWR 보충(가중치 w). AAS = SUM(w) / 구간 초.</li>
 *   <li>Top 3종은 GROUPING SETS로 ASH를 한 번만 읽어 함께 구한다(설계의 쿼리 3개를 한 스캔으로).</li>
 *   <li>블로커가 다른 RAC 인스턴스면(blocking_inst_id ≠ 접속 인스턴스 번호) 표시만 하고 로컬 조회하지 않는다.</li>
 *   <li>응답의 queries는 화면의 "조회 쿼리 보기"용(바인드 자리는 ?).</li>
 * </ul>
 */
@Service
public class DashboardQueryService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int TOP_N = 5;
    private static final int LIST_LIMIT = 50;

    private final OracleConnectionPoolManager poolManager;
    private final InstanceMetricSamplerService samplerService;
    private final MonitorStoreService storeService;

    @Value("${dbagent.monitor.ash-activity-query-timeout-seconds:10}")
    private int ashTimeoutSeconds;

    @Value("${dbagent.monitor.ash-awr-query-timeout-seconds:60}")
    private int awrTimeoutSeconds;

    @Value("${dbagent.monitor.ash-awr-fallback:true}")
    private boolean awrFallback;

    /** DB별 user_id → username (dba_users를 ASH 행마다 조인하지 않고 결과 행에만 붙인다). */
    private final Map<String, Map<Long, String>> usernames = new ConcurrentHashMap<>();

    public DashboardQueryService(OracleConnectionPoolManager poolManager, InstanceMetricSamplerService samplerService,
                                 MonitorStoreService storeService) {
        this.poolManager = poolManager;
        this.samplerService = samplerService;
        this.storeService = storeService;
    }

    /** ash_base 컬럼(별칭 h) - 8분류와 "ON CPU" 이벤트 라벨 포함. */
    private static final String BASE_COLUMNS =
            "h.sample_time, h.sql_id, h.sql_plan_hash_value AS phv, h.session_id AS sid, h.session_serial# AS serial_no, " +
            "h.user_id, h.program, CASE WHEN h.session_state = 'ON CPU' THEN 'ON CPU' ELSE h.event END AS event_name, " +
            "h.blocking_session, h.blocking_inst_id, " + AshCategories.CASE8 + " AS category";

    // ================================================================== ⑤⑥⑦ Top

    public Map<String, Object> top(TargetDbConfig target, LocalDateTime from, LocalDateTime to) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            AshRange.Window w = AshRange.resolve(conn, from, to, awrFallback);
            int localInst = instanceNumber(conn);
            String query = "SELECT GROUPING(b.sql_id) AS g_sql, GROUPING(b.sid) AS g_sid, GROUPING(b.event_name) AS g_evt, " +
                    "b.sql_id, b.sid, b.serial_no, b.event_name, b.category, SUM(b.w) AS cnt, MAX(b.user_id) AS user_id, " +
                    "MAX(b.program) AS program, MAX(b.blocking_session) AS blocking_session, MAX(b.blocking_inst_id) AS blocking_inst_id " +
                    "FROM (" + AshRange.baseSql(w, BASE_COLUMNS, "") + ") b WHERE b.category IS NOT NULL " +
                    "GROUP BY GROUPING SETS ((b.sql_id, b.category), (b.sid, b.serial_no, b.category), (b.event_name, b.category))";
            Map<String, Agg> sqls = new LinkedHashMap<>();
            Map<String, Agg> sessions = new LinkedHashMap<>();
            Map<String, Agg> events = new LinkedHashMap<>();
            double total = 0;
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setQueryTimeout(w.useAwr ? awrTimeoutSeconds : ashTimeoutSeconds);
                AshRange.bind(ps, 1, w, null);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String cat = rs.getString("category");
                        long cnt = rs.getLong("cnt");
                        if (rs.getInt("g_sql") == 0) {
                            String sqlId = rs.getString("sql_id");
                            if (sqlId == null) continue; // SQL 없이 샘플된 행(로그인 등)은 Top SQL에서 제외
                            sqls.computeIfAbsent(sqlId, k -> new Agg()).add(cat, cnt);
                        } else if (rs.getInt("g_sid") == 0) {
                            String key = rs.getLong("sid") + ":" + rs.getLong("serial_no");
                            Agg a = sessions.computeIfAbsent(key, k -> new Agg());
                            a.add(cat, cnt);
                            a.sid = rs.getLong("sid");
                            a.serial = rs.getLong("serial_no");
                            a.userId = nullableLong(rs, "user_id", a.userId);
                            if (rs.getString("program") != null) a.program = rs.getString("program");
                            a.blockingSession = nullableLong(rs, "blocking_session", a.blockingSession);
                            a.blockingInstId = nullableLong(rs, "blocking_inst_id", a.blockingInstId);
                        } else if (rs.getInt("g_evt") == 0) {
                            String ev = rs.getString("event_name");
                            Agg a = events.computeIfAbsent(ev + "|" + cat, k -> new Agg());
                            a.add(cat, cnt);
                            a.event = ev;
                            total += cnt;
                        }
                    }
                }
            }
            double seconds = w.seconds();
            List<Agg> topSqls = topN(sqls.values());
            List<Agg> topSessions = topN(sessions.values());
            List<Agg> topEvents = topN(events.values());
            Map<Long, String> names = resolveUsernames(conn, target.id(), topSessions);
            Map<String, String> sqlTexts = sqlTexts(conn, topSqls.isEmpty() ? Collections.<String>emptyList() : keysOf(sqls, topSqls));

            List<Map<String, Object>> sqlOut = new ArrayList<>();
            for (Map.Entry<String, Agg> e : sqls.entrySet()) {
                if (!topSqls.contains(e.getValue())) continue;
                Map<String, Object> m = row(e.getValue(), seconds, total);
                m.put("sqlId", e.getKey());
                m.put("sqlText", sqlTexts.get(e.getKey())); // null = 공유 풀에서 밀려남(화면: "SQL 텍스트 없음")
                sqlOut.add(m);
            }
            sqlOut.sort(Comparator.comparing((Map<String, Object> m) -> -((Number) m.get("aas")).doubleValue()));
            List<Map<String, Object>> sessOut = new ArrayList<>();
            for (Agg a : topSessions) {
                Map<String, Object> m = row(a, seconds, total);
                m.put("sid", a.sid);
                m.put("serial", a.serial);
                m.put("username", a.userId == null ? null : names.get(a.userId));
                m.put("program", a.program);
                putBlocker(m, a.blockingSession, a.blockingInstId, localInst);
                sessOut.add(m);
            }
            List<Map<String, Object>> evOut = new ArrayList<>();
            for (Agg a : topEvents) {
                Map<String, Object> m = row(a, seconds, total);
                m.put("event", a.event);
                m.put("category", a.byCat.keySet().iterator().next());
                m.remove("byCategory");
                evOut.add(m);
            }

            Map<String, Object> r = header(conn, w, localInst);
            r.put("totalAas", round2(total / seconds));
            r.put("topSql", sqlOut);
            r.put("topSession", sessOut);
            r.put("topEvent", evOut);
            r.put("queries", Collections.singletonList(query));
            return r;
        }
    }

    // ================================================================== 6.1 세션 상세

    public Map<String, Object> sessionDetail(TargetDbConfig target, long sid, long serial, LocalDateTime from, LocalDateTime to) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            AshRange.Window w = AshRange.resolve(conn, from, to, awrFallback);
            int localInst = instanceNumber(conn);
            Map<String, Object> r = header(conn, w, localInst);
            r.put("sid", sid);
            r.put("serial", serial);

            String currentSql = "SELECT s.sid, s.serial#, s.username, s.status, s.type, s.program, s.module, s.machine, s.osuser, " +
                    "p.spid, TO_CHAR(s.logon_time, 'YYYY-MM-DD HH24:MI:SS') AS logon_time, s.last_call_et, s.state, s.event, " +
                    "s.wait_class, ROUND(s.wait_time_micro / 1e6) AS wait_sec, s.blocking_session, s.blocking_instance, " +
                    "s.sql_id, q.plan_hash_value, DBMS_LOB.SUBSTR(q.sql_fulltext, 2000, 1) AS sql_fulltext " +
                    "FROM v$session s LEFT JOIN v$process p ON p.addr = s.paddr " +
                    "LEFT JOIN v$sql q ON q.sql_id = s.sql_id AND q.child_number = s.sql_child_number " +
                    "WHERE s.sid = ? AND s.serial# = ?";
            Map<String, Object> cur = null;
            try (PreparedStatement ps = conn.prepareStatement(currentSql)) {
                ps.setQueryTimeout(ashTimeoutSeconds);
                ps.setLong(1, sid);
                ps.setLong(2, serial);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        cur = new LinkedHashMap<>();
                        for (String c : new String[]{"username", "status", "type", "program", "module", "machine", "osuser", "spid",
                                "logon_time", "state", "event", "wait_class", "sql_id", "sql_fulltext"}) {
                            cur.put(camel(c), rs.getString(c));
                        }
                        cur.put("lastCallEt", nullableLong(rs, "last_call_et", null));
                        cur.put("waitSec", nullableLong(rs, "wait_sec", null));
                        cur.put("planHashValue", nullableLong(rs, "plan_hash_value", null));
                        Long bs = nullableLong(rs, "blocking_session", null);
                        Long bi = nullableLong(rs, "blocking_instance", null);
                        putBlocker(cur, bs, bi, localInst);
                    }
                }
            }
            String blockerSql = "SELECT sid, serial#, username, status, program, last_call_et FROM v$session WHERE sid = ?";
            if (cur != null && cur.get("blockingSession") != null && !Boolean.TRUE.equals(cur.get("blockerRemote"))) {
                try (PreparedStatement ps = conn.prepareStatement(blockerSql)) {
                    ps.setQueryTimeout(ashTimeoutSeconds);
                    ps.setLong(1, (Long) cur.get("blockingSession"));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            Map<String, Object> b = new LinkedHashMap<>();
                            b.put("sid", rs.getLong("sid"));
                            b.put("serial", rs.getLong("serial#"));
                            b.put("username", rs.getString("username"));
                            b.put("status", rs.getString("status"));
                            b.put("program", rs.getString("program"));
                            b.put("lastCallEt", rs.getLong("last_call_et"));
                            cur.put("blocker", b);
                        }
                    }
                }
            }
            r.put("current", cur);            // null = 세션 종료됨
            r.put("ended", cur == null);

            // 이 세션의 분류별 합계와 구간 전체 합계(% 분모)를 ASH 한 번 스캔으로 - 필터를 WHERE가 아닌 조건부 SUM에
            // 두어 같은 스캔에서 전체 합도 구한다(query-performance-reviewer 검토: 필터 유무와 무관하게 스캔 비용은 같음).
            String cond = "b.sid = ? AND b.serial_no = ?";
            String actSql = "SELECT b.category, SUM(CASE WHEN " + cond + " THEN b.w ELSE 0 END) AS cnt, " +
                    "TO_CHAR(MAX(CASE WHEN " + cond + " THEN b.sample_time END), 'YYYY-MM-DD HH24:MI:SS') AS last_sample, " +
                    "SUM(SUM(b.w)) OVER () AS grand_total FROM (" + AshRange.baseSql(w, BASE_COLUMNS, "") +
                    ") b WHERE b.category IS NOT NULL GROUP BY b.category";
            Agg act = new Agg();
            String lastSample = null;
            double total = 0;
            try (PreparedStatement ps = conn.prepareStatement(actSql)) {
                ps.setQueryTimeout(w.useAwr ? awrTimeoutSeconds : ashTimeoutSeconds);
                ps.setLong(1, sid);
                ps.setLong(2, serial);
                ps.setLong(3, sid);
                ps.setLong(4, serial);
                AshRange.bind(ps, 5, w, null);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        total = rs.getDouble("grand_total");
                        long cnt = rs.getLong("cnt");
                        if (cnt > 0) act.add(rs.getString("category"), cnt);
                        String ls = rs.getString("last_sample");
                        if (ls != null && (lastSample == null || ls.compareTo(lastSample) > 0)) lastSample = ls;
                    }
                }
            }
            r.put("activity", row(act, w.seconds(), total));
            r.put("lastSample", lastSample);
            r.put("queries", java.util.Arrays.asList(currentSql, blockerSql, actSql));
            return r;
        }
    }

    // ================================================================== 6.2 SQL 상세

    public Map<String, Object> sqlDetail(TargetDbConfig target, String sqlId, LocalDateTime from, LocalDateTime to) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            AshRange.Window w = AshRange.resolve(conn, from, to, awrFallback);
            int localInst = instanceNumber(conn);
            Map<String, Object> r = header(conn, w, localInst);
            r.put("sqlId", sqlId);

            // 구간 SQL 통계(mon_sqlstat_delta, 앱 시각 ms) - DB 시각 구간을 dbClockOffsetMs로 바꿔 조회
            Long offset = samplerService.getDbClockOffsetMs(target.id());
            long off = offset == null ? 0L : offset;
            ZoneId zone = ZoneId.systemDefault();
            long fromApp = w.from.atZone(zone).toInstant().toEpochMilli() - off;
            long toApp = w.to.atZone(zone).toInstant().toEpochMilli() - off;
            r.put("stats", storeService.sqlstatSummary(target.id(), sqlId, fromApp, toApp));

            String textSql = "SELECT DBMS_LOB.SUBSTR(sql_fulltext, 2000, 1) FROM v$sqlstats WHERE sql_id = ?";
            String fullText = null;
            try (PreparedStatement ps = conn.prepareStatement(textSql)) {
                ps.setQueryTimeout(ashTimeoutSeconds);
                ps.setString(1, sqlId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) fullText = rs.getString(1);
                }
            }
            r.put("sqlFullText", fullText);

            String extra = "AND h.sql_id = ? ";
            AshRange.ExtraBinder binder = (stmt, i) -> {
                stmt.setString(i++, sqlId);
                return i;
            };
            Agg act = new Agg();
            String actSql = activityWithTotalSql(w, "b.sql_id = ?");
            double total = activityWithTotal(conn, w, actSql, ps -> ps.setString(1, sqlId), act);
            r.put("activity", row(act, w.seconds(), total));
            String sessSql = sessionListSql(w, extra);
            r.put("sessions", sessionList(conn, target.id(), w, sessSql, binder, localInst, total));
            r.put("queries", java.util.Arrays.asList(textSql, actSql, sessSql,
                    "SELECT SUM(executions_d), SUM(elapsed_us_d), SUM(buffer_gets_d), MAX(plan_hash_value) FROM mon_sqlstat_delta " +
                            "WHERE db_id = ? AND sql_id = ? AND collect_ts BETWEEN ? AND ?"));
            return r;
        }
    }

    // ================================================================== 6.3 이벤트 상세

    public Map<String, Object> eventDetail(TargetDbConfig target, String event, LocalDateTime from, LocalDateTime to) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            AshRange.Window w = AshRange.resolve(conn, from, to, awrFallback);
            int localInst = instanceNumber(conn);
            Map<String, Object> r = header(conn, w, localInst);
            r.put("event", event);
            String extra = "AND (CASE WHEN h.session_state = 'ON CPU' THEN 'ON CPU' ELSE h.event END) = ? ";
            AshRange.ExtraBinder binder = (stmt, i) -> {
                stmt.setString(i++, event);
                return i;
            };
            Agg act = new Agg();
            String actSql = activityWithTotalSql(w, "b.event_name = ?");
            double total = activityWithTotal(conn, w, actSql, ps -> ps.setString(1, event), act);
            Map<String, Object> a = row(act, w.seconds(), total);
            a.put("category", act.byCat.isEmpty() ? null : act.byCat.keySet().iterator().next());
            r.put("activity", a);
            String sessSql = sessionListSql(w, extra);
            r.put("sessions", sessionList(conn, target.id(), w, sessSql, binder, localInst, total));
            r.put("queries", java.util.Arrays.asList(actSql, sessSql));
            return r;
        }
    }

    // ================================================================== 공통

    /** SQL·이벤트 상세의 세션 목록 - 세션별 AAS 순, 가장 최근 샘플의 이벤트·SQL·블로커. */
    private static String sessionListSql(AshRange.Window w, String extra) {
        return "SELECT * FROM (SELECT b.sid, b.serial_no, SUM(b.w) AS cnt, MAX(b.user_id) AS user_id, " +
                "MAX(b.event_name) KEEP (DENSE_RANK LAST ORDER BY b.sample_time) AS last_event, " +
                "MAX(b.sql_id) KEEP (DENSE_RANK LAST ORDER BY b.sample_time) AS last_sql_id, " +
                "MAX(b.blocking_session) AS blocking_session, MAX(b.blocking_inst_id) AS blocking_inst_id " +
                "FROM (" + AshRange.baseSql(w, BASE_COLUMNS, extra) + ") b WHERE b.category IS NOT NULL " +
                "GROUP BY b.sid, b.serial_no ORDER BY cnt DESC) WHERE ROWNUM <= " + LIST_LIMIT;
    }

    private List<Map<String, Object>> sessionList(Connection conn, String dbId, AshRange.Window w, String sql,
                                                  AshRange.ExtraBinder binder, int localInst, double total) throws SQLException {
        List<Agg> aggs = new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(w.useAwr ? awrTimeoutSeconds : ashTimeoutSeconds);
            AshRange.bind(ps, 1, w, binder);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Agg a = new Agg();
                    a.sid = rs.getLong("sid");
                    a.serial = rs.getLong("serial_no");
                    a.userId = nullableLong(rs, "user_id", null);
                    a.total = rs.getLong("cnt");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sid", a.sid);
                    m.put("serial", a.serial);
                    m.put("event", rs.getString("last_event"));
                    m.put("sqlId", rs.getString("last_sql_id"));
                    m.put("aas", round2((double) a.total / w.seconds()));
                    m.put("pct", total > 0 ? round1(a.total / total * 100) : 0);
                    putBlocker(m, nullableLong(rs, "blocking_session", null), nullableLong(rs, "blocking_inst_id", null), localInst);
                    aggs.add(a);
                    out.add(m);
                }
            }
        }
        Map<Long, String> names = resolveUsernames(conn, dbId, aggs);
        for (int i = 0; i < out.size(); i++) {
            Long uid = aggs.get(i).userId;
            out.get(i).put("username", uid == null ? null : names.get(uid));
        }
        return out;
    }

    /** 조건(바인드 1개)에 맞는 분류별 합계 + 구간 전체 합계(% 분모)를 한 스캔으로 구하는 쿼리. */
    private static String activityWithTotalSql(AshRange.Window w, String cond) {
        return "SELECT b.category, SUM(CASE WHEN " + cond + " THEN b.w ELSE 0 END) AS cnt, SUM(SUM(b.w)) OVER () AS grand_total " +
                "FROM (" + AshRange.baseSql(w, BASE_COLUMNS, "") + ") b WHERE b.category IS NOT NULL GROUP BY b.category";
    }

    private interface FirstBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    /** activityWithTotalSql 실행 - act에 분류별 합계를 채우고 구간 전체 합계를 돌려준다. */
    private double activityWithTotal(Connection conn, AshRange.Window w, String sql, FirstBinder first, Agg act) throws SQLException {
        double total = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(w.useAwr ? awrTimeoutSeconds : ashTimeoutSeconds);
            first.bind(ps);
            AshRange.bind(ps, 2, w, null);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    total = rs.getDouble("grand_total");
                    long cnt = rs.getLong("cnt");
                    if (cnt > 0) act.add(rs.getString("category"), cnt);
                }
            }
        }
        return total;
    }

    private Map<String, Object> header(Connection conn, AshRange.Window w, int localInst) throws SQLException {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("from", w.from.format(ISO));
        r.put("to", w.to.format(ISO));
        r.put("dbNow", w.dbNow.format(ISO));
        r.put("source", w.source());
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT instance_name FROM v$instance")) {
            r.put("instanceName", rs.next() ? rs.getString(1) : null);
        }
        r.put("instanceNumber", localInst);
        return r;
    }

    private static int instanceNumber(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT instance_number FROM v$instance")) {
            return rs.next() ? rs.getInt(1) : 1;
        }
    }

    /** 블로커가 다른 인스턴스면 blockerRemote=true - 화면은 "다른 인스턴스(n번) SID m"으로만 표시하고 상세로 가지 않는다. */
    private static void putBlocker(Map<String, Object> m, Long blockingSession, Long blockingInst, int localInst) {
        m.put("blockingSession", blockingSession);
        m.put("blockingInstId", blockingInst);
        m.put("blockerRemote", blockingSession != null && blockingInst != null && blockingInst != localInst);
    }

    private Map<Long, String> resolveUsernames(Connection conn, String dbId, List<Agg> aggs) {
        Map<Long, String> cache = usernames.computeIfAbsent(dbId, k -> new ConcurrentHashMap<>());
        Set<Long> unknown = new TreeSet<>();
        for (Agg a : aggs) if (a.userId != null && !cache.containsKey(a.userId)) unknown.add(a.userId);
        if (!unknown.isEmpty()) {
            String in = String.join(",", Collections.nCopies(unknown.size(), "?"));
            try (PreparedStatement ps = conn.prepareStatement("SELECT user_id, username FROM dba_users WHERE user_id IN (" + in + ")")) {
                ps.setQueryTimeout(ashTimeoutSeconds);
                int i = 1;
                for (Long id : unknown) ps.setLong(i++, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) cache.put(rs.getLong(1), rs.getString(2));
                }
            } catch (SQLException ignored) {
                // 사용자명은 보조 정보 - 못 읽어도 목록은 보여준다
            }
        }
        return cache;
    }

    /**
     * 상위 SQL 텍스트 - v$sqlstats(X$ 고정 테이블)는 sql_id = ? 단건이면 FIXED INDEX로 읽지만 IN 리스트면 공유 풀 전체를
     * 훑는다(query-performance-reviewer EXPLAIN 실측, 2026-09-25). 그래서 단건 조회를 UNION ALL로 잇는다(최대 5개).
     */
    private Map<String, String> sqlTexts(Connection conn, List<String> sqlIds) {
        Map<String, String> out = new HashMap<>();
        if (sqlIds.isEmpty()) return out;
        StringBuilder q = new StringBuilder();
        for (int k = 0; k < sqlIds.size(); k++) {
            if (k > 0) q.append(" UNION ALL ");
            q.append("SELECT sql_id, SUBSTR(sql_text, 1, 300) FROM v$sqlstats WHERE sql_id = ?");
        }
        try (PreparedStatement ps = conn.prepareStatement(q.toString())) {
            ps.setQueryTimeout(ashTimeoutSeconds);
            int i = 1;
            for (String id : sqlIds) ps.setString(i++, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException ignored) {
            // 텍스트는 보조 정보
        }
        return out;
    }

    private static List<String> keysOf(Map<String, Agg> map, List<Agg> picked) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Agg> e : map.entrySet()) if (picked.contains(e.getValue())) keys.add(e.getKey());
        return keys;
    }

    private static List<Agg> topN(java.util.Collection<Agg> all) {
        List<Agg> list = new ArrayList<>(all);
        list.sort((a, b) -> Long.compare(b.total, a.total));
        return list.size() > TOP_N ? new ArrayList<>(list.subList(0, TOP_N)) : list;
    }

    /** 공통 행 값: aas, pct, byCategory(분류별 AAS, 8분류 키). */
    private static Map<String, Object> row(Agg a, double seconds, double total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aas", round2(a.total / seconds));
        m.put("pct", total > 0 ? round1(a.total / total * 100) : 0);
        Map<String, Object> by = new LinkedHashMap<>();
        for (String k : AshCategories.KEYS8) {
            Long c = a.byCat.get(k);
            if (c != null && c > 0) by.put(k, round2(c / seconds));
        }
        m.put("byCategory", by);
        return m;
    }

    private static Long nullableLong(ResultSet rs, String col, Long fallback) throws SQLException {
        Object v = rs.getObject(col);
        // 삼항식으로 쓰면 결과 타입이 long이 되어 fallback(null)이 언박싱되며 NPE - if로 분리(2026-09-25 실측).
        if (v == null) return fallback;
        return ((Number) v).longValue();
    }

    private static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 집계 중간 결과. */
    private static final class Agg {
        long total;
        final Map<String, Long> byCat = new LinkedHashMap<>();
        long sid;
        long serial;
        Long userId;
        String program;
        Long blockingSession;
        Long blockingInstId;
        String event;

        void add(String cat, long cnt) {
            total += cnt;
            byCat.merge(cat, cnt, Long::sum);
        }
    }
}
