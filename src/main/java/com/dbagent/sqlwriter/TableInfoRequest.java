package com.dbagent.sqlwriter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** AI SQL 작성기 - 테이블 구조 조회("조회" 버튼) 요청. */
public final class TableInfoRequest {

    private final String dbId;
    private final String account;
    private final String token;
    private final String tableName;

    @JsonCreator
    public TableInfoRequest(@JsonProperty("db_id") String dbId,
                             @JsonProperty("account") String account,
                             @JsonProperty("token") String token,
                             @JsonProperty("table_name") String tableName) {
        this.dbId = dbId;
        this.account = account;
        this.token = token;
        this.tableName = tableName;
    }

    public String dbId() {
        return dbId;
    }

    public String account() {
        return account;
    }

    public String token() {
        return token;
    }

    public String tableName() {
        return tableName;
    }
}
