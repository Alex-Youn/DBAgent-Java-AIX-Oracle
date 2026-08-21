package com.dbagent.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class UpdateUserRequest {

    private final String token;
    private final String role;
    private final List<String> hiddenMenus;
    private final List<String> hiddenDbs;

    @JsonCreator
    public UpdateUserRequest(
            @JsonProperty("token") String token,
            @JsonProperty("role") String role,
            @JsonProperty("hidden_menus") List<String> hiddenMenus,
            @JsonProperty("hidden_dbs") List<String> hiddenDbs) {
        this.token = token;
        this.role = role;
        this.hiddenMenus = hiddenMenus;
        this.hiddenDbs = hiddenDbs;
    }

    public String token() {
        return token;
    }

    public String role() {
        return role;
    }

    public List<String> hiddenMenus() {
        return hiddenMenus;
    }

    public List<String> hiddenDbs() {
        return hiddenDbs;
    }
}
