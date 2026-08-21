package com.dbagent.query;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class SqlQueryRequest {

    private final String dbId;
    private final String sql;
    private final Integer maxRows;
    private final String account;
    private final String token;

    @JsonCreator
    public SqlQueryRequest(
            @JsonProperty("db_id") String dbId,
            @JsonProperty("sql") String sql,
            @JsonProperty("max_rows") Integer maxRows,
            @JsonProperty("account") String account,
            @JsonProperty("token") String token) {
        this.dbId = dbId;
        this.sql = sql;
        this.maxRows = maxRows;
        this.account = account;
        this.token = token;
    }

    public String dbId() {
        return dbId;
    }

    public String sql() {
        return sql;
    }

    public Integer maxRows() {
        return maxRows;
    }

    public String account() {
        return account;
    }

    public String token() {
        return token;
    }
}
