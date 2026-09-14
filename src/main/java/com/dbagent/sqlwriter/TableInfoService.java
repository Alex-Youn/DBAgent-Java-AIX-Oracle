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
 *
 * 사용자 지적(2026-09-14): OWNER 스키마가 실제 테이블을 갖고 GRANT + SYNONYM으로 일반 계정에
 * 쓰게 하는 구성에서는, USER_TAB_COLUMNS(현재 접속 계정 소유 객체만 보임)로는 그 테이블을 영영
 * 못 찾는다 - SYNONYM 자체도 컬럼을 가진 오브젝트가 아니라 USER_TAB_COLUMNS에 안 잡힌다.
 * 자동 탐색형으로 해결: ① 먼저 현재 접속 계정 소유(USER_TAB_COLUMNS)로 시도 → ② 없으면
 * ALL_TAB_COLUMNS에서 같은 이름을 가진 OWNER 후보를 찾아 ③ 후보가 하나면 그걸로 자동 조회,
 * 여러 개면 프론트가 고르게 "ambiguousOwners" 목록을 돌려준다. ALL_TAB_COLUMNS는 현재 계정이
 * 권한(GRANT)을 가진 객체까지 보여주므로 SYNONYM 유무와 무관하게 동작한다.
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

    /**
     * 테이블을 못 찾으면 null. 후보 OWNER가 여럿이면 {"ambiguousOwners": List&lt;String&gt;}를 돌려주며,
     * 이 경우 호출자가 owner를 채워 다시 호출해야 한다. owner가 주어지면 바로 ALL_TAB_COLUMNS를
     * owner+table_name으로 조회한다(자동 탐색 생략).
     */
    public Map<String, Object> fetchTableInfo(TargetDbConfig target, String tableName, String owner) throws SQLException {
        String upperName = normalizeTableName(tableName);
        String upperOwner = (owner == null || owner.trim().isEmpty()) ? null : owner.trim().toUpperCase(Locale.ROOT);

        try (Connection conn = poolManager.getConnection(target)) {
            if (upperOwner == null) {
                // ① 현재 접속 계정 소유 객체 우선 - 기존 동작과 동일한 빠른 경로.
                List<Map<String, Object>> columns = fetchColumns(conn, upperName);
                if (!columns.isEmpty()) {
                    List<Map<String, Object>> indexes = fetchIndexes(conn, upperName);
                    return buildTable(upperName, target.user().toUpperCase(Locale.ROOT), columns, indexes);
                }
                // ② 현재 계정 소유가 아니면 ALL_TAB_COLUMNS에서 접근 가능한 OWNER 후보를 찾는다.
                List<String> owners = fetchCandidateOwners(conn, upperName);
                if (owners.isEmpty()) {
                    return null;
                }
                if (owners.size() > 1) {
                    Map<String, Object> ambiguous = new LinkedHashMap<>();
                    ambiguous.put("ambiguousOwners", owners);
                    return ambiguous;
                }
                upperOwner = owners.get(0);
            }

            // ③ owner가 명시됐거나 후보가 정확히 하나로 좁혀진 경우.
            List<Map<String, Object>> columns = fetchColumnsForOwner(conn, upperOwner, upperName);
            if (columns.isEmpty()) {
                return null;
            }
            List<Map<String, Object>> indexes = fetchIndexesForOwner(conn, upperOwner, upperName);
            return buildTable(upperName, upperOwner, columns, indexes);
        }
    }

    private Map<String, Object> buildTable(String name, String owner, List<Map<String, Object>> columns, List<Map<String, Object>> indexes) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("name", name);
        table.put("owner", owner);
        table.put("columns", columns);
        table.put("indexes", indexes);
        return table;
    }

    private List<String> fetchCandidateOwners(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT DISTINCT owner FROM ALL_TAB_COLUMNS WHERE table_name = ? ORDER BY owner";
        List<String> owners = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    owners.add(rs.getString("owner"));
                }
            }
        }
        return owners;
    }

    private List<Map<String, Object>> fetchColumns(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT column_name, data_type, data_length, char_length, char_used, "
                + "data_precision, data_scale, nullable "
                + "FROM USER_TAB_COLUMNS WHERE table_name = ? ORDER BY column_id";
        return runColumnsQuery(conn, sql, null, tableName);
    }

    /** fetchColumns()의 ALL_TAB_COLUMNS + owner 필터 버전 - 다른 스키마 소유 테이블 조회용. */
    private List<Map<String, Object>> fetchColumnsForOwner(Connection conn, String owner, String tableName) throws SQLException {
        String sql = "SELECT column_name, data_type, data_length, char_length, char_used, "
                + "data_precision, data_scale, nullable "
                + "FROM ALL_TAB_COLUMNS WHERE owner = ? AND table_name = ? ORDER BY column_id";
        return runColumnsQuery(conn, sql, owner, tableName);
    }

    // fetchColumns/fetchColumnsForOwner가 FROM 절(USER_TAB_COLUMNS vs ALL_TAB_COLUMNS+owner 바인드)만
    // 다르고 컬럼 매핑 로직은 완전히 동일해서, owner가 null이면 파라미터를 하나만 바인드하는 공용 헬퍼로 합쳤다.
    private List<Map<String, Object>> runColumnsQuery(Connection conn, String sql, String owner, String tableName) throws SQLException {
        // char_length/char_used 까지 읽는 이유: NLS_LENGTH_SEMANTICS=CHAR 로 만든 VARCHAR2(50 CHAR)
        // 컬럼은 AL32UTF8 에서 data_length 가 200(바이트)으로 나와, 그대로 쓰면 미리보기와 LLM 프롬프트에
        // 컬럼 길이가 4배로 부풀어 보인다.
        List<Map<String, Object>> columns = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            if (owner != null) {
                ps.setString(idx++, owner);
            }
            ps.setString(idx, tableName);
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
        return runIndexesQuery(conn, sql, null, tableName);
    }

    /** fetchIndexes()의 ALL_IND_COLUMNS/ALL_INDEXES + owner 필터 버전. */
    private List<Map<String, Object>> fetchIndexesForOwner(Connection conn, String owner, String tableName) throws SQLException {
        String sql = "SELECT ic.index_name, i.uniqueness, ic.column_name "
                + "FROM ALL_IND_COLUMNS ic JOIN ALL_INDEXES i "
                + "  ON i.index_name = ic.index_name AND i.owner = ic.index_owner "
                + "WHERE ic.table_owner = ? AND ic.table_name = ? ORDER BY ic.index_name, ic.column_position";
        return runIndexesQuery(conn, sql, owner, tableName);
    }

    private List<Map<String, Object>> runIndexesQuery(Connection conn, String sql, String owner, String tableName) throws SQLException {
        // LinkedHashMap 으로 등장 순서(=인덱스명 순) 유지하며 컬럼을 그룹핑한다.
        Map<String, Map<String, Object>> byIndexName = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            if (owner != null) {
                ps.setString(idx++, owner);
            }
            ps.setString(idx, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String indexName = rs.getString("index_name");
                    Map<String, Object> ix = byIndexName.get(indexName);
                    if (ix == null) {
                        ix = new LinkedHashMap<>();
                        ix.put("name", indexName);
                        ix.put("unique", false);
                        ix.put("columns", new ArrayList<String>());
                        byIndexName.put(indexName, ix);
                    }
                    ix.put("unique", "UNIQUE".equals(rs.getString("uniqueness")));
                    @SuppressWarnings("unchecked")
                    List<String> cols = (List<String>) ix.get("columns");
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
