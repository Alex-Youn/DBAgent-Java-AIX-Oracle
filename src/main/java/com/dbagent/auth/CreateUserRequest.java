package com.dbagent.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class CreateUserRequest {

    private final String token;
    private final String username;
    private final String password;
    private final String role;
    private final List<String> hiddenMenus;
    private final List<String> hiddenDbs;
    private final Boolean fleetOverview;

    @JsonCreator
    public CreateUserRequest(
            @JsonProperty("token") String token,
            @JsonProperty("username") String username,
            @JsonProperty("password") String password,
            @JsonProperty("role") String role,
            @JsonProperty("hidden_menus") List<String> hiddenMenus,
            @JsonProperty("hidden_dbs") List<String> hiddenDbs,
            @JsonProperty("fleet_overview") Boolean fleetOverview) {
        this.token = token;
        this.username = username;
        this.password = password;
        this.role = role;
        this.hiddenMenus = hiddenMenus;
        this.hiddenDbs = hiddenDbs;
        this.fleetOverview = fleetOverview;
    }

    public String token() {
        return token;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
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

    public Boolean fleetOverview() {
        return fleetOverview;
    }
}
