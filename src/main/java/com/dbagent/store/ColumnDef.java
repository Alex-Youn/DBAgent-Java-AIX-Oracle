package com.dbagent.store;

/**
 * 컬럼 정의 한 개. 값은 만들 때 정하고 바꾸지 않는다(설정 메서드는 새 객체를 돌려준다).
 */
public final class ColumnDef {

    private final String name;
    private final ColType type;
    private final int length;
    private final boolean h2NoLength;
    private final boolean notNull;
    private final String defaultSql;
    private final String checkSql;
    private final boolean emptyAsNull;
    private final boolean oracleOmit;
    private final boolean addedLater;

    private ColumnDef(String name, ColType type, int length, boolean h2NoLength, boolean notNull, String defaultSql,
                      String checkSql, boolean emptyAsNull, boolean oracleOmit, boolean addedLater) {
        this.name = name;
        this.type = type;
        this.length = length;
        this.h2NoLength = h2NoLength;
        this.notNull = notNull;
        this.defaultSql = defaultSql;
        this.checkSql = checkSql;
        this.emptyAsNull = emptyAsNull;
        this.oracleOmit = oracleOmit;
        this.addedLater = addedLater;
    }

    public static ColumnDef varchar(String name, int length) {
        return new ColumnDef(name, ColType.VARCHAR, length, false, false, null, null, false, false, false);
    }

    /** H2에서는 길이 없는 VARCHAR로 만들어진 컬럼(users·sessions·instance_metric_history). length는 Oracle에서 쓰는 길이다. */
    public static ColumnDef varcharNoLengthInH2(String name, int oracleLength) {
        return new ColumnDef(name, ColType.VARCHAR, oracleLength, true, false, null, null, false, false, false);
    }

    public static ColumnDef textLarge(String name, int h2Length) {
        return new ColumnDef(name, ColType.TEXT_LARGE, h2Length, false, false, null, null, false, false, false);
    }

    public static ColumnDef bigint(String name) {
        return of(name, ColType.BIGINT);
    }

    public static ColumnDef integer(String name) {
        return of(name, ColType.INTEGER);
    }

    public static ColumnDef dbl(String name) {
        return of(name, ColType.DOUBLE);
    }

    public static ColumnDef bool01(String name) {
        return of(name, ColType.BOOL01);
    }

    public static ColumnDef identity(String name) {
        return of(name, ColType.IDENTITY);
    }

    private static ColumnDef of(String name, ColType type) {
        return new ColumnDef(name, type, 0, false, false, null, null, false, false, false);
    }

    public ColumnDef notNull() {
        return new ColumnDef(name, type, length, h2NoLength, true, defaultSql, checkSql, emptyAsNull, oracleOmit, addedLater);
    }

    /** SQL 리터럴 그대로(예: {@code "''"}, {@code "0"}, {@code "'oracle'"}). */
    public ColumnDef dflt(String sqlLiteral) {
        return new ColumnDef(name, type, length, h2NoLength, notNull, sqlLiteral, checkSql, emptyAsNull, oracleOmit, addedLater);
    }

    /** 컬럼에 붙는 CHECK 조건(예: {@code "id = 1"}). */
    public ColumnDef check(String condition) {
        return new ColumnDef(name, type, length, h2NoLength, notNull, defaultSql, condition, emptyAsNull, oracleOmit, addedLater);
    }

    /**
     * 빈 문자열("")이 실제로 들어가는 컬럼. Oracle은 ""를 NULL로 저장하므로 Oracle DDL에서는 NOT NULL과 DEFAULT ''를 뺀다
     * (설계서 2.6절 E1 - 읽을 때 null → ""는 서비스가 한다). H2 DDL은 그대로다.
     */
    public ColumnDef emptyAsNull() {
        return new ColumnDef(name, type, length, h2NoLength, notNull, defaultSql, checkSql, true, oracleOmit, addedLater);
    }

    /** 더 쓰지 않는 옛 컬럼 - H2에는 남겨 두고 Oracle(신규 스키마)에는 만들지 않는다. */
    public ColumnDef oracleOmit() {
        return new ColumnDef(name, type, length, h2NoLength, notNull, defaultSql, checkSql, emptyAsNull, true, addedLater);
    }

    /** 테이블이 처음 만들어진 뒤에 추가된 컬럼 - 옛 H2 파일에는 없을 수 있어 H2 방언이 ADD COLUMN IF NOT EXISTS로 보정한다. */
    public ColumnDef addedLater() {
        return new ColumnDef(name, type, length, h2NoLength, notNull, defaultSql, checkSql, emptyAsNull, oracleOmit, true);
    }

    public String name() { return name; }
    public ColType type() { return type; }
    public int length() { return length; }
    public boolean h2NoLength() { return h2NoLength; }
    public boolean isNotNull() { return notNull; }
    public String defaultSql() { return defaultSql; }
    public String checkSql() { return checkSql; }
    public boolean isEmptyAsNull() { return emptyAsNull; }
    public boolean isOracleOmit() { return oracleOmit; }
    public boolean isAddedLater() { return addedLater; }
}
