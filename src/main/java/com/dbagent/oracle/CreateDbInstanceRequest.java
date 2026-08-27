package com.dbagent.oracle;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public final class CreateDbInstanceRequest {

    private final String token;
    private final String groupName;
    private final String id;
    private final String name;
    private final String host;
    private final int port;
    private final String sid;
    private final String user;
    private final String password;
    private final Integer poolMinIdle;
    private final Integer poolMaxSize;
    // Extra accounts: each map has "user" and "password" keys - see databases.json's "accounts".
    private final List<Map<String, String>> accounts;
    // 5 ascending ints, or null/empty to not override the global default - see databases.json's
    // "session_thresholds" and DatabaseConfigService.applySessionThresholds.
    private final List<Integer> sessionThresholds;

    @JsonCreator
    public CreateDbInstanceRequest(
            @JsonProperty("token") String token,
            @JsonProperty("group_name") String groupName,
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("host") String host,
            @JsonProperty("port") int port,
            @JsonProperty("sid") String sid,
            @JsonProperty("user") String user,
            @JsonProperty("password") String password,
            @JsonProperty("pool_min_idle") Integer poolMinIdle,
            @JsonProperty("pool_max_size") Integer poolMaxSize,
            @JsonProperty("accounts") List<Map<String, String>> accounts,
            @JsonProperty("session_thresholds") List<Integer> sessionThresholds) {
        this.token = token;
        this.groupName = groupName;
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.sid = sid;
        this.user = user;
        this.password = password;
        this.poolMinIdle = poolMinIdle;
        this.poolMaxSize = poolMaxSize;
        this.accounts = accounts;
        this.sessionThresholds = sessionThresholds;
    }

    public String token() {
        return token;
    }

    public String groupName() {
        return groupName;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String sid() {
        return sid;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public Integer poolMinIdle() {
        return poolMinIdle;
    }

    public Integer poolMaxSize() {
        return poolMaxSize;
    }

    public List<Map<String, String>> accounts() {
        return accounts;
    }

    public List<Integer> sessionThresholds() {
        return sessionThresholds;
    }
}
