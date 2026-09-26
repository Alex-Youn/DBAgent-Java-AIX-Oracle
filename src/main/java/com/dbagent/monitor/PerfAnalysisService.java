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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * G3 성능 분석(구 "성능 이력 조회", 2026-09-26) - 설계문서 `성능이력조회_개편_검토_2026-09-24.md` 9절.
 *
 * <ul>
 *   <li>상단 wait class 막대는 기존 /api/ash_activity(from/to + users/machines)를 그대로 쓴다.</li>
 *   <li>하단: 선택 구간의 event별 합계({@link #events}) - 원본 event명 단위(AWR "Top Timed Events"와 같은 모양),
 *       분류는 상단과 같은 7분류(AshCategories.CASE7)라 막대 색이 상단과 맞는다.</li>
 *   <li>event 막대 클릭: 그 event로 샘플된 세션 목록({@link #eventSessions}) - 필드는 9.3절 확정대로 getSessions()
 *       기준에서 status·session_wait_pct·has_transaction을 뺀 것. ASH에 없는 server_pid·osuser는 비운다.</li>
 * </ul>
 * 모두 공용 AshRange(ASH + 보관 범위 밖은 AWR 보충, FOREGROUND, 모니터링 계정 제외)를 쓴다.
 */
@Service
public class PerfAnalysisService {

    static final int EVENT_LIMIT = 20;
    static final int SESSION_LIMIT = 200;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    /** ON CPU 샘플은 event가 NULL이라 이 이름으로 묶는다(대시보드 Top 이벤트와 같은 라벨). */
    private static final String EVENT_EXPR = "CASE WHEN h.session_state = 'ON CPU' THEN 'ON CPU' ELSE h.event END";

    private final OracleConnectionPoolManager poolManager;

    @Value("${dbagent.monitor.ash-activity-query-timeout-seconds:10}")
    private int ashTimeoutSeconds;
    @Value("${dbagent.monitor.ash-awr-query-timeout-seconds:60}")
    private int awrTimeoutSeconds;
    @Value("${dbagent.monitor.ash-awr-fallback:true}")
    private boolean awrFallback;

    public PerfAnalysisService(OracleConnectionPoolManager poolManager) {
        this.poolManager = poolManager;
    }

    public String dbNow(TargetDbConfig target) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            return AshRange.dbNow(conn).withNano(0).format(ISO);
        }
    }

    /** 선택 구간의 event별 합계(상위 EVENT_LIMIT개) + 구간 전체 합계. */
    public Map<String, Object> events(TargetDbConfig target, LocalDateTime from, LocalDateTime to, AshRange.Filter filter) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            AshRange.Window w = AshRange.resolve(conn, from, to, awrFallback);
            String columns = "h.sample_time, " + EVENT_EXPR + " AS event_name, " + AshCategories.CASE7 + " AS category";
            String query = "SELECT event_name, category, SUM(w) AS cnt, SUM(SUM(w)) OVER () AS total " +
                    "FROM (" + AshRange.baseSql(w, columns, filter.sql()) + ") b WHERE category IS NOT NULL " +
                    "GROUP BY event_name, category ORDER BY cnt DESC";
            List<Map<String, Object>> events = new ArrayList<>();
            double total = 0;
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setQueryTimeout(w.useAwr ? awrTimeoutSeconds : ashTimeoutSeconds);
                ps.setMaxRows(EVENT_LIMIT);
                AshRange.bind(ps, 1, w, filter.isEmpty() ? null : filter::bind);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        total = rs.getDouble("total");
                        long cnt = rs.getLong("cnt");
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("event", rs.getString("event_name"));
                        m.put("category", rs.getString("category"));
                        m.put("seconds", cnt);
                        m.put("aas", round2((double) cnt / w.seconds()));
                        events.add(m);
                    }
                }
            }
            for (Map<String, Object> m : events) {
                m.put("pct", total > 0 ? Math.round(((Number) m.get("seconds")).doubleValue() / total * 1000) / 10.0 : 0);
            }
            Map<String, Object> r = header(w);
            r.put("totalSeconds", Math.round(total));
            r.put("events", events);
            return r;
        }
    }

    /** event 하나의 세션 목록 - 그 event로 샘플된 시간(초) 순. */
    public Map<String, Object> eventSessions(TargetDbConfig target, String event, LocalDateTime from, LocalDateTime to,
                                             AshRange.Filter filter) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            AshRange.Window w = AshRange.resolve(conn, from, to, awrFallback);
            String columns = "h.sample_time, h.session_id AS sid, h.session_serial# AS serial_no, h.user_id, h.program, " +
                    "h.machine, h.module, h.sql_id, h.sql_plan_hash_value AS phv, h.sql_opcode";
            String extra = "AND " + EVENT_EXPR + " = ? " + filter.sql();
            String last = " KEEP (DENSE_RANK LAST ORDER BY b.sample_time)";
            String query = "SELECT * FROM (SELECT b.sid, b.serial_no, SUM(b.w) AS cnt, MAX(b.sample_time) AS last_ts, " +
                    "MAX(b.user_id)" + last + " AS user_id, MAX(b.program)" + last + " AS program, " +
                    "MAX(b.machine)" + last + " AS machine, MAX(b.module)" + last + " AS module, " +
                    "MAX(b.sql_id)" + last + " AS sql_id, MAX(b.phv)" + last + " AS phv, " +
                    "MAX(b.sql_opcode)" + last + " AS sql_opcode " +
                    "FROM (" + AshRange.baseSql(w, columns, extra) + ") b " +
                    "GROUP BY b.sid, b.serial_no ORDER BY cnt DESC) WHERE ROWNUM <= " + SESSION_LIMIT;
            List<Map<String, Object>> rows = new ArrayList<>();
            Set<Long> userIds = new LinkedHashSet<>();
            Set<Integer> opcodes = new LinkedHashSet<>();
            Set<String> sqlIds = new LinkedHashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setQueryTimeout(w.useAwr ? awrTimeoutSeconds : ashTimeoutSeconds);
                AshRange.bind(ps, 1, w, (stmt, i) -> {
                    stmt.setString(i++, event);
                    return filter.bind(stmt, i);
                });
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("sid", rs.getLong("sid"));
                        m.put("serial", rs.getLong("serial_no"));
                        m.put("duration_time", rs.getLong("cnt"));
                        Timestamp ts = rs.getTimestamp("last_ts");
                        m.put("capture_time", ts == null ? null : ts.toLocalDateTime().withNano(0).format(ISO).replace('T', ' '));
                        long uid = rs.getLong("user_id");
                        m.put("user_id", rs.wasNull() ? null : uid);
                        m.put("program_name", rs.getString("program"));
                        m.put("machine_name", rs.getString("machine"));
                        m.put("module", rs.getString("module"));
                        m.put("sql_id", rs.getString("sql_id"));
                        long phv = rs.getLong("phv");
                        m.put("plan_hash_value", rs.wasNull() || phv == 0 ? null : phv);
                        int op = rs.getInt("sql_opcode");
                        m.put("opcode", rs.wasNull() ? null : op);
                        m.put("event_name", event);
                        if (m.get("user_id") != null) userIds.add((Long) m.get("user_id"));
                        if (m.get("opcode") != null) opcodes.add((Integer) m.get("opcode"));
                        if (m.get("sql_id") != null && sqlIds.size() < 50) sqlIds.add((String) m.get("sql_id"));
                        rows.add(m);
                    }
                }
            }
            Map<Long, String> names = usernames(conn, userIds);
            Map<Integer, String> commands = commandNames(conn, opcodes);
            Map<String, String> texts = sqlTexts(conn, sqlIds);
            for (Map<String, Object> m : rows) {
                Object uid = m.remove("user_id");
                m.put("username", uid == null ? null : names.get(uid));
                Object op = m.remove("opcode");
                m.put("command", op == null ? null : commands.get(op));
                m.put("sql_text", m.get("sql_id") == null ? null : texts.get(m.get("sql_id")));
            }
            Map<String, Object> r = header(w);
            r.put("event", event);
            r.put("sessions", rows);
            r.put("limit", SESSION_LIMIT);
            return r;
        }
    }

    // ------------------------------------------------------------------ 보조 조회

    private static Map<String, Object> header(AshRange.Window w) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("from", w.from.format(ISO));
        r.put("to", w.to.format(ISO));
        r.put("dbNow", w.dbNow.format(ISO));
        r.put("source", w.source());
        return r;
    }

    private static Map<Long, String> usernames(Connection conn, Set<Long> ids) throws SQLException {
        Map<Long, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        try (PreparedStatement ps = conn.prepareStatement("SELECT username FROM dba_users WHERE user_id = ?")) {
            for (Long id : ids) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) out.put(id, rs.getString(1));
                }
            }
        }
        return out;
    }

    /** sql_opcode → 명령 이름(audit_actions는 모든 버전에 있다). 실패해도 목록은 보여 준다. */
    private static Map<Integer, String> commandNames(Connection conn, Set<Integer> codes) {
        Map<Integer, String> out = new HashMap<>();
        if (codes.isEmpty()) return out;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT action, name FROM audit_actions")) {
            while (rs.next()) {
                int code = rs.getInt(1);
                if (codes.contains(code) && !"UNKNOWN".equals(rs.getString(2))) out.put(code, rs.getString(2));
            }
        } catch (SQLException ignored) {
            // 명령 이름은 보조 정보
        }
        return out;
    }

    /** sql_id별 SQL 앞부분 - v$sqlstats는 sql_id 단건 조회일 때 FIXED INDEX를 탄다(대시보드 Top SQL과 같은 방식). */
    private static Map<String, String> sqlTexts(Connection conn, Set<String> sqlIds) {
        Map<String, String> out = new HashMap<>();
        if (sqlIds.isEmpty()) return out;
        try (PreparedStatement ps = conn.prepareStatement("SELECT sql_text FROM v$sqlstats WHERE sql_id = ? AND ROWNUM = 1")) {
            for (String id : sqlIds) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) out.put(id, rs.getString(1));
                }
            }
        } catch (SQLException ignored) {
            // 공유 풀에서 밀려난 SQL은 비워 둔다
        }
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
