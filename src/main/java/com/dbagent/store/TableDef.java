package com.dbagent.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 테이블 정의 한 개. 앱의 테이블 생성, 설치 쉘(DDL 출력), 이전 도구, 스키마 검증이 모두 이 정의 하나를 쓴다(설계서 2.4절 S2).
 */
public final class TableDef {

    private final StoreId store;
    private final String name;
    private final List<ColumnDef> columns;
    private final List<String> primaryKey;
    private final List<IndexDef> indexes;
    private final List<String> h2LegacySql;

    private TableDef(StoreId store, String name, List<ColumnDef> columns, List<String> primaryKey, List<IndexDef> indexes,
                     List<String> h2LegacySql) {
        this.store = store;
        this.name = name;
        this.columns = columns;
        this.primaryKey = primaryKey;
        this.indexes = indexes;
        this.h2LegacySql = h2LegacySql;
    }

    public static TableDef of(StoreId store, String name, ColumnDef... columns) {
        return new TableDef(store, name, Collections.unmodifiableList(Arrays.asList(columns)),
                Collections.<String>emptyList(), Collections.<IndexDef>emptyList(), Collections.<String>emptyList());
    }

    public TableDef pk(String... columnNames) {
        for (String c : columnNames) {
            column(c); // 없는 컬럼이면 여기서 멈춘다
        }
        return new TableDef(store, name, columns, Collections.unmodifiableList(Arrays.asList(columnNames)), indexes, h2LegacySql);
    }

    public TableDef index(IndexDef index) {
        for (String c : index.columns()) {
            column(c);
        }
        List<IndexDef> next = new ArrayList<IndexDef>(indexes);
        next.add(index);
        return new TableDef(store, name, columns, primaryKey, Collections.unmodifiableList(next), h2LegacySql);
    }

    /** 옛 H2 파일을 지금 구조로 맞추는 문장(여러 번 실행해도 결과가 같아야 한다). Oracle에서는 실행하지 않는다. */
    public TableDef h2Legacy(String sql) {
        List<String> next = new ArrayList<String>(h2LegacySql);
        next.add(sql);
        return new TableDef(store, name, columns, primaryKey, indexes, Collections.unmodifiableList(next));
    }

    public ColumnDef column(String columnName) {
        for (ColumnDef c : columns) {
            if (c.name().equalsIgnoreCase(columnName)) return c;
        }
        throw new IllegalArgumentException("테이블 " + name + "에 컬럼이 없습니다: " + columnName);
    }

    public boolean isKey(String columnName) {
        for (String k : primaryKey) {
            if (k.equalsIgnoreCase(columnName)) return true;
        }
        return false;
    }

    public StoreId store() { return store; }
    public String name() { return name; }
    public List<ColumnDef> columns() { return columns; }
    public List<String> primaryKey() { return primaryKey; }
    public List<IndexDef> indexes() { return indexes; }
    public List<String> h2LegacySql() { return h2LegacySql; }
}
