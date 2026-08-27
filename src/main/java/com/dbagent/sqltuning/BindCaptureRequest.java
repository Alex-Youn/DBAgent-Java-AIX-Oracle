package com.dbagent.sqltuning;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class BindCaptureRequest {

    private final String dbId;
    private final String account;
    private final String token;
    private final String hashValue;

    @JsonCreator
    public BindCaptureRequest(@JsonProperty("db_id") String dbId,
                               @JsonProperty("account") String account,
                               @JsonProperty("token") String token,
                               @JsonProperty("hash_value") String hashValue) {
        this.dbId = dbId;
        this.account = account;
        this.token = token;
        this.hashValue = hashValue;
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

    public String hashValue() {
        return hashValue;
    }
}
