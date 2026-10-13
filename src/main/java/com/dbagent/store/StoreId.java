package com.dbagent.store;

/** 내부 저장소 4개. key는 설정 키({@code dbagent.store.<key>.type})와 Oracle 풀 이름({@code store-<key>})에 쓴다. */
public enum StoreId {
    USERS("users"), DBCONFIG("dbconfig"), METRICS("metrics"), PERF("perf");

    private final String key;

    StoreId(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
