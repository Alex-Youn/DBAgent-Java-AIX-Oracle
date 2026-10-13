package com.dbagent.store;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * H2 1.4.200 방언. DDL은 서비스들이 지금까지 직접 실행하던 문장과 같은 스키마를 만들어야 한다
 * (2026-10-13 1단계 확인: 빈 H2에서 서비스 DDL로 만든 스키마와 이 방언으로 만든 스키마의 INFORMATION_SCHEMA 차이 0건).
 */
public final class H2StoreDialect extends StoreDialect {

    public static final H2StoreDialect INSTANCE = new H2StoreDialect();

    private H2StoreDialect() {
    }

    @Override
    public StoreType type() {
        return StoreType.H2;
    }

    @Override
    public String renderType(ColumnDef c) {
        switch (c.type()) {
            case VARCHAR:
            case TEXT_LARGE:
                return c.h2NoLength() ? "VARCHAR" : "VARCHAR(" + c.length() + ")";
            case BIGINT:
                return "BIGINT";
            case INTEGER:
            case BOOL01:
                return "INTEGER";
            case DOUBLE:
                return "DOUBLE";
            case IDENTITY:
                return "BIGINT AUTO_INCREMENT";
            default:
                throw new IllegalStateException("타입을 알 수 없습니다: " + c.type());
        }
    }

    private String renderColumn(ColumnDef c) {
        StringBuilder sb = new StringBuilder(c.name()).append(' ').append(renderType(c));
        if (c.defaultSql() != null) sb.append(" DEFAULT ").append(c.defaultSql());
        if (c.isNotNull()) sb.append(" NOT NULL");
        if (c.checkSql() != null) sb.append(" CHECK (").append(c.checkSql()).append(")");
        return sb.toString();
    }

    @Override
    public String renderCreateTable(TableDef t) {
        List<String> parts = new ArrayList<String>();
        for (ColumnDef c : t.columns()) {
            parts.add(renderColumn(c));
        }
        if (!t.primaryKey().isEmpty()) {
            parts.add("PRIMARY KEY (" + join(t.primaryKey()) + ")");
        }
        return "CREATE TABLE IF NOT EXISTS " + t.name() + " (" + join(parts) + ")";
    }

    @Override
    public String renderCreateIndex(TableDef t, IndexDef i) {
        return "CREATE INDEX IF NOT EXISTS " + i.name() + " ON " + t.name() + " (" + join(i.columns()) + ")";
    }

    @Override
    public boolean wantIndex(TableDef t, IndexDef i) {
        return !i.isH2Skip();
    }

    @Override
    public boolean wantColumn(ColumnDef c) {
        return true;
    }

    @Override
    public boolean tableExists(JdbcTemplate jdbc, String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = UPPER(?)", Integer.class, table);
        return n != null && n > 0;
    }

    @Override
    public boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = UPPER(?) AND COLUMN_NAME = UPPER(?)", Integer.class, table, column);
        return n != null && n > 0;
    }

    @Override
    public boolean indexExists(JdbcTemplate jdbc, String index) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES " +
                "WHERE TABLE_SCHEMA = 'PUBLIC' AND INDEX_NAME = UPPER(?)", Integer.class, index);
        return n != null && n > 0;
    }

    @Override
    public void ensureTable(JdbcTemplate jdbc, TableDef t) {
        jdbc.execute(renderCreateTable(t));
        for (IndexDef i : t.indexes()) {
            if (wantIndex(t, i)) jdbc.execute(renderCreateIndex(t, i));
        }
    }

    /**
     * 옛 H2 파일 보정: 나중에 추가된 컬럼과 테이블별 보정 문장. 모두 여러 번 실행해도 결과가 같다.
     * 절차가 필요한 보정(mon_sqlstat_delta 컬럼을 한 번에 추가하며 경고 로그 남기기, 끊긴 복사본 테이블 정리, users DB에 있던
     * 옛 이력 옮기기)은 이 목록에 넣지 않았다 - 해당 서비스를 방언 호출로 바꾸는 3a단계에서 옮긴다.
     */
    @Override
    public List<String> legacyUpgrades(TableDef t) {
        List<String> out = new ArrayList<String>();
        for (ColumnDef c : t.columns()) {
            if (c.isAddedLater()) {
                out.add("ALTER TABLE " + t.name() + " ADD COLUMN IF NOT EXISTS " + renderColumn(c));
            }
        }
        out.addAll(t.h2LegacySql());
        for (IndexDef i : t.indexes()) {
            if (i.isH2Skip()) out.add("DROP INDEX IF EXISTS " + i.name());
        }
        return out;
    }

    @Override
    public String upsert(TableDef t, List<String> columns) {
        requireKeysIncluded(t, columns);
        return "MERGE INTO " + t.name() + " (" + join(columns) + ") KEY (" + join(t.primaryKey()) + ") VALUES (" +
                placeholders(columns.size()) + ")";
    }

    @Override
    public String insertIgnore(TableDef t, List<String> columns) {
        requireKeysOnly(t, columns);
        // 컬럼이 전부 키라서 MERGE가 기존 행을 바꿀 것이 없다 - "없을 때만 넣기"와 결과가 같다
        return "MERGE INTO " + t.name() + " (" + join(columns) + ") KEY (" + join(columns) + ") VALUES (" +
                placeholders(columns.size()) + ")";
    }

    @Override
    public String limit(String select, int rows) {
        return select + " LIMIT " + rows;
    }

    @Override
    public String deleteChunk(String table, String where, int rows) {
        return "DELETE FROM " + table + " WHERE " + where + " LIMIT " + rows;
    }

    @Override
    public int sqlType(ColumnDef c) {
        switch (c.type()) {
            case VARCHAR:
            case TEXT_LARGE:
                return Types.VARCHAR;
            case BIGINT:
            case IDENTITY:
                return Types.BIGINT;
            case INTEGER:
            case BOOL01:
                return Types.INTEGER;
            case DOUBLE:
                return Types.DOUBLE;
            default:
                throw new IllegalStateException("타입을 알 수 없습니다: " + c.type());
        }
    }

    @Override
    public boolean isDuplicateKey(Throwable error) {
        if (error instanceof DuplicateKeyException) return true;
        return sqlErrorCodes(error).contains(23505);
    }

    @Override
    public boolean isMissingTable(Throwable error) {
        return sqlErrorCodes(error).contains(42102);
    }

    @Override
    public boolean isConnectionFailure(Throwable error) {
        // 내장 파일 DB라 "접속 불가" 상태가 없다
        return false;
    }
}
