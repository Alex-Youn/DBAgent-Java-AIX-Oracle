package com.dbagent.store;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 저장소 엔진마다 다른 문법을 한곳에 모은다(설계서 2.2절). 서비스 코드에는 {@code if (oracle)} 분기를 두지 않는다.
 *
 * <p>1단계(2026-10-13)에는 정의·DDL 렌더링·문장 생성까지만 있다. 서비스의 테이블 생성과 쓰기 문장을 이 방언 호출로 바꾸는 것은
 * 2단계(users·dbconfig)·3단계(metrics·perf)에서 한다 - 그때까지 서비스는 기존 H2 문장을 그대로 쓴다.</p>
 */
public abstract class StoreDialect {

    public abstract StoreType type();

    public boolean isH2() {
        return type() == StoreType.H2;
    }

    // ------------------------------------------------------------------ DDL

    /** 컬럼의 DDL 타입(길이 포함). */
    public abstract String renderType(ColumnDef column);

    public abstract String renderCreateTable(TableDef table);

    public abstract String renderCreateIndex(TableDef table, IndexDef index);

    /** 이 엔진에서 만드는 인덱스인지. */
    public abstract boolean wantIndex(TableDef table, IndexDef index);

    /** 이 엔진의 테이블에 만드는 컬럼인지. */
    public abstract boolean wantColumn(ColumnDef column);

    public abstract boolean tableExists(JdbcTemplate jdbc, String table);

    public abstract boolean columnExists(JdbcTemplate jdbc, String table, String column);

    public abstract boolean indexExists(JdbcTemplate jdbc, String index);

    /** 테이블·빠진 컬럼·인덱스를 정의대로 맞춘다. 이미 맞으면 아무것도 바꾸지 않는다. */
    public abstract void ensureTable(JdbcTemplate jdbc, TableDef table);

    /** 옛 파일을 지금 구조로 맞추는 문장(H2 전용 보정). 신규 스키마뿐인 엔진은 빈 목록. */
    public abstract List<String> legacyUpgrades(TableDef table);

    // ------------------------------------------------------------------ 쓰기·조회 문장

    /**
     * 키가 있으면 나머지 컬럼을 바꾸고 없으면 넣는 문장. 바인드 순서는 columns 순서 그대로다.
     * 바인드할 때는 {@link #argTypes(TableDef, List)}로 타입을 함께 넘긴다(아래 설명).
     */
    public abstract String upsert(TableDef table, List<String> columns);

    /** 키가 없을 때만 넣는 문장. 두 엔진의 결과가 같도록 columns는 기본키 컬럼만 받는다. */
    public abstract String insertIgnore(TableDef table, List<String> columns);

    /** 조회 문장 끝에 "앞에서 n행만"을 붙인다. */
    public abstract String limit(String select, int rows);

    /** 조건에 맞는 행을 최대 n행만 지우는 문장(보관 정리의 분할 삭제). */
    public abstract String deleteChunk(String table, String where, int rows);

    /**
     * 컬럼의 JDBC 바인드 타입({@code JdbcTemplate.update(sql, args, argTypes)}용).
     *
     * <p>타입을 넘기는 이유(2026-10-12 스파이크, 로컬 21c XE·19.3 실측): Oracle은 타입 없이 null을 바인드하면
     * {@code MERGE ... USING (SELECT ? FROM dual)}이 ORA-17004로 실패하고, {@code Double.NaN}은 바인드 타입이
     * BINARY_DOUBLE(101)이 아니면 쓰지 못한다. 타입을 주면 일반 INSERT 배치도 빨라진다(2000행 약 210ms → 약 10ms).</p>
     */
    public abstract int sqlType(ColumnDef column);

    public int[] argTypes(TableDef table, List<String> columns) {
        int[] types = new int[columns.size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = sqlType(table.column(columns.get(i)));
        }
        return types;
    }

    // ------------------------------------------------------------------ 예외 판별

    /** 기본키 중복(동시에 같은 새 키를 넣을 때 정상적으로 생길 수 있다 - upsert는 한 번 재시도, insertIgnore는 무시). */
    public abstract boolean isDuplicateKey(Throwable error);

    /** 테이블이 아직 없음(첫 기동 판정). */
    public abstract boolean isMissingTable(Throwable error);

    /** 저장소에 접속하지 못함(쓰기 건너뛰기·503 안내의 판단 근거). */
    public abstract boolean isConnectionFailure(Throwable error);

    // ------------------------------------------------------------------ 공통 도우미

    /** 원인 사슬에서 SQLException의 벤더 오류 코드를 모은다. */
    protected static List<Integer> sqlErrorCodes(Throwable error) {
        List<Integer> codes = new ArrayList<Integer>();
        int depth = 0;
        for (Throwable t = error; t != null && depth < 20; t = t.getCause(), depth++) {
            if (t instanceof SQLException) {
                codes.add(((SQLException) t).getErrorCode());
            }
        }
        return codes;
    }

    protected static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    protected static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i == 0 ? "?" : ", ?");
        }
        return sb.toString();
    }

    protected static void requireKeysOnly(TableDef table, List<String> columns) {
        if (columns.size() != table.primaryKey().size()) {
            throw new IllegalArgumentException("insertIgnore는 기본키 컬럼만 받습니다: " + table.name() + " " + columns);
        }
        for (String c : columns) {
            if (!table.isKey(c)) {
                throw new IllegalArgumentException("insertIgnore는 기본키 컬럼만 받습니다: " + table.name() + "." + c);
            }
        }
    }

    protected static void requireKeysIncluded(TableDef table, List<String> columns) {
        if (table.primaryKey().isEmpty()) {
            throw new IllegalArgumentException("기본키가 없는 테이블은 upsert를 쓸 수 없습니다: " + table.name());
        }
        for (String k : table.primaryKey()) {
            boolean found = false;
            for (String c : columns) {
                if (c.equalsIgnoreCase(k)) found = true;
            }
            if (!found) {
                throw new IllegalArgumentException("upsert 컬럼에 기본키가 빠졌습니다: " + table.name() + "." + k);
            }
        }
    }
}
