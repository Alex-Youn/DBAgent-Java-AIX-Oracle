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

    public SqlQueryService(OracleConnectionPoolManager poolManager) {
        this.poolManager = poolManager;
    }

    public Map<String, Object> execute(TargetDbConfig target, String rawSql, Integer requestedMaxRows) throws SQLException {
        String sql = stripTrailingSemicolon(Strings.strip(rawSql));
        int effectiveMaxRows = (requestedMaxRows != null && requestedMaxRows > 0)
                ? Math.min(requestedMaxRows, maxRowsLimit)
                : maxRows;
        long start = System.currentTimeMillis();

        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
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
        }
    }

    private String stripTrailingSemicolon(String sql) {
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
