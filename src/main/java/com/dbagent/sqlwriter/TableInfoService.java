package com.dbagent.sqlwriter;

import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI SQL 작성기(매뉴통합.md 2-3)의 "테이블 구조 조회" - 접속 계정 소유 테이블의 컬럼/인덱스를 조회한다.
 * USER_TAB_COLUMNS/USER_IND_COLUMNS는 "지금 접속한 계정 소유" 객체만 보여주므로, 다른 스키마의
 * 테이블을 조회하려면 그 계정으로 접속을 바꿔야 한다(SQL 실행 화면과 동일한 "접속 계정" 개념).
 */
@Service
public class TableInfoService {

    private final OracleConnectionPoolManager poolManager;

    public TableInfoService(OracleConnectionPoolManager poolManager) {
        this.poolManager = poolManager;
    }

    /** 입력 테이블명을 Oracle 식별자 규칙(대문자)으로 맞춘 이름. 터키어 로케일의 i→İ 변환을 피하려고 ROOT 고정. */
    public static String normalizeTableName(String tableName) {
        return tableName.trim().toUpperCase(Locale.ROOT);
    }

    /** 테이블이 없으면 null. */
    public Map<String, Object> fetchTableInfo(TargetDbConfig target, String tableName) throws SQLException {
        String upperName = normalizeTableName(tableName);

        try (Connection conn = poolManager.getConnection(target)) {
            List<Map<String, Object>> columns = fetchColumns(conn, upperName);
            if (columns.isEmpty()) {
                return null;
            }
            List<Map<String, Object>> indexes = fetchIndexes(conn, upperName);

            Map<String, Object> table = new LinkedHashMap<>();
            table.put("name", upperName);
            table.put("columns", columns);
            table.put("indexes", indexes);
            return table;
        }
    }

    private List<Map<String, Object>> fetchColumns(Connection conn, String tableName) throws SQLException {
        // char_length/char_used 까지 읽는 이유: NLS_LENGTH_SEMANTICS=CHAR 로 만든 VARCHAR2(50 CHAR)
        // 컬럼은 AL32UTF8 에서 data_length 가 200(바이트)으로 나와, 그대로 쓰면 미리보기와 LLM 프롬프트에
        // 컬럼 길이가 4배로 부풀어 보인다.
        String sql = "SELECT column_name, data_type, data_length, char_length, char_used, "
                + "data_precision, data_scale, nullable "
                + "FROM USER_TAB_COLUMNS WHERE table_name = ? ORDER BY column_id";
        List<Map<String, Object>> columns = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("name", rs.getString("column_name"));
                    Integer precision = rs.getObject("data_precision") != null ? rs.getInt("data_precision") : null;
                    Integer scale = rs.getObject("data_scale") != null ? rs.getInt("data_scale") : null;
                    col.put("dataType", formatDataType(rs.getString("data_type"), rs.getInt("data_length"),
                            rs.getInt("char_length"), rs.getString("char_used"), precision, scale));
                    col.put("nullable", "Y".equals(rs.getString("nullable")));
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    private List<Map<String, Object>> fetchIndexes(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT ic.index_name, i.uniqueness, ic.column_name "
                + "FROM USER_IND_COLUMNS ic JOIN USER_INDEXES i ON i.index_name = ic.index_name "
                + "WHERE ic.table_name = ? ORDER BY ic.index_name, ic.column_position";
        // LinkedHashMap 으로 등장 순서(=인덱스명 순) 유지하며 컬럼을 그룹핑한다.
        Map<String, Map<String, Object>> byIndexName = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String indexName = rs.getString("index_name");
                    Map<String, Object> idx = byIndexName.get(indexName);
                    if (idx == null) {
                        idx = new LinkedHashMap<>();
                        idx.put("name", indexName);
                        idx.put("unique", false);
                        idx.put("columns", new ArrayList<String>());
                        byIndexName.put(indexName, idx);
                    }
                    idx.put("unique", "UNIQUE".equals(rs.getString("uniqueness")));
                    @SuppressWarnings("unchecked")
                    List<String> cols = (List<String>) idx.get("columns");
                    cols.add(rs.getString("column_name"));
                }
            }
        }
        return new ArrayList<>(byIndexName.values());
    }

    /** sql-writer.md 예시("NUMBER(4)", "VARCHAR2(10)")와 같은 표기로 맞춘다. */
    private String formatDataType(String dataType, int length, int charLength, String charUsed,
                                   Integer precision, Integer scale) {
        if ("NUMBER".equals(dataType)) {
            if (precision == null) {
                return "NUMBER";
            }
            return (scale != null && scale != 0) ? "NUMBER(" + precision + "," + scale + ")" : "NUMBER(" + precision + ")";
        }
        // CHAR 세만틱(char_used='C')이면 선언 그대로 "(50 CHAR)"로, 아니면 바이트 길이를 쓴다.
        if ("VARCHAR2".equals(dataType) || "NVARCHAR2".equals(dataType) || "CHAR".equals(dataType)
                || "NCHAR".equals(dataType)) {
            return "C".equals(charUsed) ? dataType + "(" + charLength + " CHAR)" : dataType + "(" + length + ")";
        }
        if ("RAW".equals(dataType)) {
            return dataType + "(" + length + ")";
        }
        return dataType;
    }
}
