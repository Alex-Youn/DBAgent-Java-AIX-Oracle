package com.dbagent.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class TokenRequest {

    private final String token;

    @JsonCreator
    public TokenRequest(@JsonProperty("token") String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }
}
