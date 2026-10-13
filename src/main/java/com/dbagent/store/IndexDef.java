package com.dbagent.store;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 보조 인덱스 정의. 기본키 인덱스는 TableDef의 기본키가 만든다. */
public final class IndexDef {

    private final String name;
    private final List<String> columns;
    private final boolean h2Skip;

    private IndexDef(String name, List<String> columns, boolean h2Skip) {
        this.name = name;
        this.columns = columns;
        this.h2Skip = h2Skip;
    }

    public static IndexDef of(String name, String... columns) {
        return new IndexDef(name, Collections.unmodifiableList(Arrays.asList(columns)), false);
    }

    /** H2에서는 만들지 않고, 이미 있으면 지우는 인덱스(쓰기·삭제가 잦은 테이블에서 H2 파일이 부푸는 것을 막으려는 기존 결정). */
    public IndexDef h2Skip() {
        return new IndexDef(name, columns, true);
    }

    public String name() { return name; }
    public List<String> columns() { return columns; }
    public boolean isH2Skip() { return h2Skip; }
}
