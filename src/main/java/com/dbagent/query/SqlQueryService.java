package com.dbagent.query;

import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.util.Strings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs one ad-hoc SQL statement against a target DB's existing HikariCP pool - the AI DBA tab's SQL runner. */
@Service
public class SqlQueryService {

    private final OracleConnectionPoolManager poolManager;

    // Caps both how long a query may run and how many rows come back to the browser grid, since
    // this endpoint accepts arbitrary SQL (unlike the rest of the app's fixed monitoring queries).
    // maxRows is the default when the caller doesn't ask for a specific row count; maxRowsLimit is
    // the hard ceiling a caller-supplied value is clamped to, so the grid/DB can't be asked for more
    // than that no matter what the UI sends.
    @Value("${dbagent.sql-runner.max-rows:500}")
    private int maxRows;

    @Value("${dbagent.sql-runner.max-rows-limit:5000}")
    private int maxRowsLimit;

    @Value("${dbagent.sql-runner.timeout-seconds:30}")
    private int timeoutSeconds;

    // Kept short (not the full CLOB) so one huge column can't blow up the JSON response.
    private static final int CLOB_PREVIEW_CHARS = 4000;

    // 읽기 전용 모드(체크리스트 5-1, 2026-09-25 오케스트레이터 결정). 이 화면은 풀 커넥션이 자동 커밋이라
    // DML이 실행 즉시 확정되고 ROLLBACK으로도 되돌릴 수 없었다(실측). 켜져 있으면 두 겹으로 막는다:
    //  1) 주석·공백·여는 괄호를 건너뛴 첫 단어가 SELECT/WITH가 아니면 실행 전에 거부(DML·DDL·PL/SQL·
    //     COMMIT/ROLLBACK·ALTER SYSTEM 등). WITH FUNCTION/PROCEDURE(쿼리 안 PL/SQL)도 거부.
    //  2) 실행 전 SET TRANSACTION READ ONLY, 끝나면 롤백 - 조회문 안에 숨은 변경 시도도 DB가 거부한다.
    // 기본 true. 필요할 때만 application.properties에서 false로 끈다(값이 "false"가 아니면 켜진 것으로 본다 -
    // 오타로 안전장치가 풀리지 않게, 그리고 잘못된 값으로 기동이 실패하지 않게 문자열로 받는다).
    private boolean readOnly = true;

    @Value("${dbagent.sql-runner.read-only:true}")
    void setReadOnly(String value) {
        this.readOnly = !"false".equalsIgnoreCase(value == null ? "" : value.trim());
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    private static final Pattern LEADING_NOISE =
            Pattern.compile("(\\s+|--[^\\n]*(\\n|$)|/\\*.*?\\*/|\\()+", Pattern.DOTALL);
    private static final Pattern FIRST_WORD = Pattern.compile("[A-Za-z]+");
    private static final Pattern WITH_PLSQL = Pattern.compile("WITH\\s+(FUNCTION|PROCEDURE)\\b", Pattern.CASE_INSENSITIVE);

    /** 앞쪽의 공백, 줄 주석(--), 블록 주석, 여는 괄호를 건너뛴 본문. */
    static String skipLeadingNoise(String sql) {
        Matcher m = LEADING_NOISE.matcher(sql);
        return m.lookingAt() ? sql.substring(m.end()) : sql;
    }

    /** 본문의 첫 단어(대문자), 없으면 빈 문자열. */
    static String firstKeyword(String sql) {
        Matcher m = FIRST_WORD.matcher(skipLeadingNoise(sql));
        return m.lookingAt() ? m.group().toUpperCase() : "";
    }

    /** 읽기 전용 모드에서 거부할 사유, 허용이면 null. */
    static String readOnlyViolation(String sql) {
        String keyword = firstKeyword(sql);
        if (!"SELECT".equals(keyword) && !"WITH".equals(keyword)) {
            return "읽기 전용 모드: SELECT 또는 WITH로 시작하는 조회문만 실행할 수 있습니다."
                    + (keyword.isEmpty() ? "" : " (입력한 명령: " + keyword + ")");
        }
        if (WITH_PLSQL.matcher(skipLeadingNoise(sql)).lookingAt()) {
            return "읽기 전용 모드: WITH FUNCTION/PROCEDURE(쿼리 안 PL/SQL)는 실행할 수 없습니다.";
        }
        return null;
    }

    public SqlQueryService(OracleConnectionPoolManager poolManager) {
        this.poolManager = poolManager;
    }

    public Map<String, Object> execute(TargetDbConfig target, String rawSql, Integer requestedMaxRows) throws SQLException {
        String sql = stripTrailingSemicolon(Strings.strip(rawSql));
        int effectiveMaxRows = (requestedMaxRows != null && requestedMaxRows > 0)
                ? Math.min(requestedMaxRows, maxRowsLimit)
                : maxRows;
        if (readOnly) {
            String violation = readOnlyViolation(sql);
            if (violation != null) {
                Map<String, Object> rejected = new LinkedHashMap<>();
                rejected.put("success", false);
                rejected.put("message", violation);
                rejected.put("read_only", true);
                return rejected;
            }
        }
        long start = System.currentTimeMillis();

        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
          boolean readOnlyTx = false;
          try {
            if (readOnly) {
                conn.setAutoCommit(false);
                readOnlyTx = true;
                try (Statement tx = conn.createStatement()) {
                    tx.execute("SET TRANSACTION READ ONLY");
                }
            }
            st.setQueryTimeout(timeoutSeconds);
            st.setMaxRows(effectiveMaxRows);

            boolean hasResultSet = st.execute(sql);
            long elapsedMs = System.currentTimeMillis() - start;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("elapsed_ms", elapsedMs);

            if (hasResultSet) {
                try (ResultSet rs = st.getResultSet()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();

                    List<String> columns = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        columns.add(meta.getColumnLabel(i));
                    }

                    List<List<Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        List<Object> row = new ArrayList<>(colCount);
                        for (int i = 1; i <= colCount; i++) {
                            row.add(convertValue(rs.getObject(i)));
                        }
                        rows.add(row);
                    }

                    result.put("type", "result");
                    result.put("columns", columns);
                    result.put("rows", rows);
                    result.put("row_count", rows.size());
                    result.put("max_rows", effectiveMaxRows);
                    result.put("truncated", rows.size() >= effectiveMaxRows);
                }
            } else {
                result.put("type", "update");
                result.put("affected_rows", Math.max(st.getUpdateCount(), 0));
            }
            return result;
          } finally {
            // 읽기 전용 트랜잭션을 닫고 풀에 돌려주기 전에 자동 커밋을 원래대로 되돌린다.
            if (readOnlyTx) {
                try { conn.rollback(); } catch (SQLException ignored) { }
                try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
            }
          }
        }
    }

    private String stripTrailingSemicolon(String sql) {
        // PL/SQL 블록(BEGIN/DECLARE ... END;)은 끝의 ;가 문법의 일부라 남긴다 - 예전엔 무조건 잘라서
        // BEGIN ... END; 가 ORA-06550으로 실패했다(2026-09-25 발견). 읽기 전용 모드를 끈 경우에만 해당.
        String keyword = firstKeyword(sql);
        if ("BEGIN".equals(keyword) || "DECLARE".equals(keyword)) {
            return sql;
        }
        return sql.endsWith(";") ? Strings.stripTrailing(sql.substring(0, sql.length() - 1)) : sql;
    }

    private Object convertValue(Object val) throws SQLException {
        if (val == null) {
            return null;
        }
        if (val instanceof Clob) {
            Clob clob = (Clob) val;
            long len = clob.length();
            String text = clob.getSubString(1, (int) Math.min(len, CLOB_PREVIEW_CHARS));
            return len > CLOB_PREVIEW_CHARS ? text + "... (truncated)" : text;
        }
        if (val instanceof Blob) {
            Blob blob = (Blob) val;
            return "(BLOB, " + blob.length() + " bytes)";
        }
        if (val instanceof byte[]) {
            byte[] bytes = (byte[]) val;
            String hex = Strings.toHex(bytes);
            return hex.length() > 200 ? hex.substring(0, 200) + "... (truncated)" : hex;
        }
        if (val instanceof java.util.Date) {
            java.util.Date date = (java.util.Date) val;
            return date.toString();
        }
        return val;
    }
}
