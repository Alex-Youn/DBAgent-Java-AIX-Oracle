package com.dbagent.query;

import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.util.Strings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs EXPLAIN PLAN + DBMS_XPLAN.DISPLAY for a query without executing it (no rows returned/changed),
 * for the SQL 정합성/튜닝 menu's "1차 성능점검"/바인드 조회 기능. AIX 이관본은 sLLM(FastAPI) 연동이
 * 없으므로 이 서비스가 만드는 실행계획/실측 통계 자체가 최종 결과물 - 모델 분석은 하지 않음.
 */
@Service
public class ExecutionPlanService {

    private final OracleConnectionPoolManager poolManager;

    // Reuses the SQL Runner's existing safety bounds (same properties) since this mode - unlike
    // explain() - actually executes the user's query for real.
    @Value("${dbagent.sql-runner.max-rows-limit:5000}")
    private int maxRowsLimit;

    @Value("${dbagent.sql-runner.timeout-seconds:30}")
    private int timeoutSeconds;

    public ExecutionPlanService(OracleConnectionPoolManager poolManager) {
        this.poolManager = poolManager;
    }

    public String explain(TargetDbConfig target, String rawQuery) throws SQLException {
        String query = stripTrailingSemicolon(Strings.strip(rawQuery));
        // Unique per call so concurrent users' EXPLAIN PLAN rows in the shared PLAN_TABLE never collide.
        // Oracle's EXPLAIN PLAN SET STATEMENT_ID rejects values over 30 chars with ORA-00972 (identifier
        // too long) rather than a data-length error, so this must stay well under that (20 hex chars is
        // plenty of entropy for this app's low concurrency).
        String statementId = "DBA_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        try (Connection conn = poolManager.getConnection(target)) {
            // EXPLAIN PLAN's STATEMENT_ID clause is parsed as part of the command grammar itself and
            // rejects a bind variable there (ORA-01780: literal string required) - it must be a literal.
            // Safe to inline directly: statementId is server-generated hex/underscore only, no quotes possible.
            try (Statement st = conn.createStatement()) {
                st.execute("EXPLAIN PLAN SET STATEMENT_ID = '" + statementId + "' FOR " + query);
            }

            StringBuilder plan = new StringBuilder();
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', ?, 'TYPICAL'))")) {
                    ps.setString(1, statementId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            plan.append(rs.getString(1)).append('\n');
                        }
                    }
                }
            } finally {
                try (PreparedStatement cleanup = conn.prepareStatement("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = ?")) {
                    cleanup.setString(1, statementId);
                    cleanup.execute();
                } catch (SQLException ignored) {
                    // Best-effort cleanup only; a stray row here can't collide with a future call
                    // since each call gets its own random statement_id.
                }
            }
            return plan.toString();
        }
    }

    /**
     * Actually runs the query (SELECT/WITH only - real execution, unlike explain()) with
     * STATISTICS_LEVEL=ALL, then reads back DBMS_XPLAN.DISPLAY_CURSOR's ALLSTATS LAST report:
     * actual row counts / actual elapsed time / actual buffer gets per row source, instead of the
     * optimizer's estimates. This is the JDBC-only alternative to a real SQL trace + tkprof - no
     * server-side trace file or OS/Docker access needed, but it does run the query for real.
     */
    public String explainActual(TargetDbConfig target, String rawQuery, Map<String, String> bindValues) throws SQLException {
        String query = stripTrailingSemicolon(Strings.strip(rawQuery));
        // Classification only strips leading comments/whitespace (e.g. a "-- 설명" line DBAs often
        // put above a query) - the comments stay in the actual query text sent to Oracle below,
        // where they're harmless.
        String upper = stripLeadingComments(query).toUpperCase(Locale.ROOT);
        if (!(upper.startsWith("SELECT") || upper.startsWith("WITH"))) {
            throw new SQLException("실제 실행 통계 분석은 SELECT/WITH 조회 쿼리만 지원합니다 (데이터 변경 방지).");
        }
        List<String> bindNames = extractBindNames(query);

        try (Connection conn = poolManager.getConnection(target)) {
            try (Statement alter = conn.createStatement()) {
                alter.execute("ALTER SESSION SET STATISTICS_LEVEL = ALL");
            }
            try {
                // Oracle's driver collapses repeated occurrences of the same :name/:n bind into a
                // single JDBC parameter, indexed by order of first appearance - matching extractBindNames.
                try (PreparedStatement ps = conn.prepareStatement(query)) {
                    ps.setQueryTimeout(timeoutSeconds);
                    ps.setMaxRows(maxRowsLimit);
                    for (int i = 0; i < bindNames.size(); i++) {
                        String name = bindNames.get(i);
                        // A bind variable can legitimately have no value entered (e.g. an optional
                        // filter like "WHERE col = :p OR :p IS NULL") - bind NULL rather than rejecting
                        // the request, same as Oracle would treat an unset/empty bind at runtime.
                        String value = bindValues != null ? bindValues.get(name) : null;
                        ps.setString(i + 1, (value == null || value.isEmpty()) ? null : value);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            // Draining is enough to produce real row-source stats; the row data itself
                            // isn't needed here (this endpoint reports the plan, not query results).
                        }
                    }
                }

                // V$SESSION.SQL_ID moves to PREV_SQL_ID once the cursor above is closed, so this is
                // reliably our just-executed statement even under concurrent use of the shared pool.
                String sqlId = null;
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT PREV_SQL_ID FROM V$SESSION WHERE SID = SYS_CONTEXT('USERENV','SID')")) {
                    if (rs.next()) {
                        sqlId = rs.getString(1);
                    }
                }

                StringBuilder plan = new StringBuilder();
                if (sqlId != null) {
                    appendSqlStatsSummary(conn, sqlId, plan);
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(?, NULL, 'ALLSTATS LAST'))")) {
                    ps.setString(1, sqlId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            plan.append(rs.getString(1)).append('\n');
                        }
                    }
                }
                return plan.toString();
            } finally {
                try (Statement reset = conn.createStatement()) {
                    reset.execute("ALTER SESSION SET STATISTICS_LEVEL = TYPICAL");
                } catch (SQLException ignored) {
                    // Best-effort reset; the connection still works correctly either way, just with
                    // slightly more stats-collection overhead for whoever borrows it from the pool next.
                }
            }
        }
    }

    /**
     * Prepends a SQL*Plus autotrace-STATISTICS-like summary line (V$SQL.ELAPSED_TIME etc, converted
     * from microseconds and averaged over EXECUTIONS) so the total elapsed time doesn't have to be
     * dug out of the DISPLAY_CURSOR plan table's A-Time column by hand.
     */
    private void appendSqlStatsSummary(Connection conn, String sqlId, StringBuilder out) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ELAPSED_TIME, CPU_TIME, BUFFER_GETS, DISK_READS, EXECUTIONS, ROWS_PROCESSED "
                        + "FROM V$SQL WHERE SQL_ID = ? AND ROWNUM = 1")) {
            ps.setString(1, sqlId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                long elapsedUs = rs.getLong("ELAPSED_TIME");
                long cpuUs = rs.getLong("CPU_TIME");
                long bufferGets = rs.getLong("BUFFER_GETS");
                long diskReads = rs.getLong("DISK_READS");
                long executions = Math.max(rs.getLong("EXECUTIONS"), 1);
                long rowsProcessed = rs.getLong("ROWS_PROCESSED");

                out.append("=== 실행 통계 요약 (V$SQL, 파싱 이후 누적을 실행 횟수로 나눈 평균값) ===\n");
                out.append(String.format("총 소요시간: %.3f초 (실행 횟수: %d)%n", elapsedUs / executions / 1_000_000.0, executions));
                out.append(String.format("CPU 시간: %.3f초%n", cpuUs / executions / 1_000_000.0));
                out.append(String.format("Buffer Gets: %d%n", bufferGets / executions));
                out.append(String.format("Disk Reads: %d%n", diskReads / executions));
                out.append(String.format("Rows Processed: %d%n", rowsProcessed / executions));
                out.append('\n');
            }
        }
    }

    /**
     * Looks up previously-sampled bind values for a cached cursor via V$SQL_BIND_CAPTURE, keyed by
     * HASH_VALUE (as seen in V$SQL / AWR / tkprof output) - lets a user bulk-fill many bind fields
     * from a real production execution instead of typing each one by hand.
     */
    public Map<String, String> fetchBindCapture(TargetDbConfig target, String hashValue) throws SQLException {
        long hashValueNum;
        try {
            hashValueNum = Long.parseLong(hashValue.trim());
        } catch (NumberFormatException e) {
            throw new SQLException("HASH_VALUE는 숫자여야 합니다.");
        }

        Map<String, String> result = new LinkedHashMap<>();
        try (Connection conn = poolManager.getConnection(target);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT NAME, VALUE_STRING FROM V$SQL_BIND_CAPTURE "
                             + "WHERE HASH_VALUE = ? AND VALUE_STRING IS NOT NULL ORDER BY POSITION")) {
            ps.setLong(1, hashValueNum);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("NAME");
                    if (name == null) {
                        continue;
                    }
                    if (name.startsWith(":")) {
                        name = name.substring(1);
                    }
                    result.put(name, rs.getString("VALUE_STRING"));
                }
            }
        }
        return result;
    }

    // Named/numbered Oracle bind syntax (:1, :SID, :b1, ...), not JDBC's `?`. String-literal contents
    // are blanked out first so a colon inside quoted text (e.g. a timestamp literal) is never matched.
    private static final Pattern BIND_PATTERN = Pattern.compile(":([A-Za-z][A-Za-z0-9_$#]*|[0-9]+)");

    // String literals and block (/* ... */)/line (-- ...) comments, matched as one alternation instead
    // of two sequential passes. Two sequential passes (strip strings, then strip comments) would let an
    // apostrophe inside a comment - e.g. "-- don't touch this filter" - confuse the string-literal
    // regex into consuming from that apostrophe up to the next real quote, potentially swallowing a
    // real bind variable in between before the comment pass ever runs. Matching both in one left-to-
    // right pass instead means whichever construct's opening token appears first "wins" and is
    // consumed as one atomic unit, so a stray quote inside an already-matched comment is never
    // reinterpreted as a string start.
    private static final Pattern STRING_OR_COMMENT_PATTERN =
            Pattern.compile("'(?:[^']|'')*'|/\\*.*?\\*/|--[^\\r\\n]*", Pattern.DOTALL);

    private static String stripStringsAndComments(String sql) {
        Matcher m = STRING_OR_COMMENT_PATTERN.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String replacement = m.group().charAt(0) == '\'' ? "''" : " ";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static List<String> extractBindNames(String sql) {
        Matcher m = BIND_PATTERN.matcher(stripStringsAndComments(sql));
        LinkedHashSet<String> names = new LinkedHashSet<>();
        while (m.find()) {
            names.add(m.group(1));
        }
        return new ArrayList<>(names);
    }

    private String stripTrailingSemicolon(String sql) {
        return sql.endsWith(";") ? Strings.stripTrailing(sql.substring(0, sql.length() - 1)) : sql;
    }

    // Leading whitespace and comments (line "-- ..." or block "/* ... */") before the actual
    // statement - DBAs commonly put an explanatory comment line above the query itself, which would
    // otherwise make the SELECT/WITH-only check below reject a perfectly valid SELECT.
    private static final Pattern LEADING_COMMENT_PATTERN =
            Pattern.compile("\\A(?:\\s|--[^\\r\\n]*|/\\*.*?\\*/)+", Pattern.DOTALL);

    private static String stripLeadingComments(String sql) {
        Matcher m = LEADING_COMMENT_PATTERN.matcher(sql);
        return m.find() ? sql.substring(m.end()) : sql;
    }
}
