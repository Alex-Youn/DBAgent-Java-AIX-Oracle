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
    // "oracle" (default) / "mysql" / "mariadb" / "postgres" / "mssql" / "cubrid" - see TargetDbConfig.
    private final String dbType;
    private final String host;
    private final int port;
    private final String sid;
    private final String user;
    private final String password;
    // null/blank = host:port:sid (default). "sid"/"service"/"descriptor" - see
    // OracleConnectionPoolManager.buildDsn(). Oracle-only; ignored for other db_type values.
    private final String connectMode;
    // null/blank = no check - compared against v$instance.instance_name on first connection.
    // Oracle-only; see TargetDbConfig, PoolTestController.
    private final String expectedInstanceName;
    private final Integer poolMinIdle;
    private final Integer poolMaxSize;
    // Extra accounts: each map has "user" and "password" keys - see db_instances' "accounts" column.
    private final List<Map<String, String>> accounts;
    // 5 ascending ints, or null/empty to not override the global default - see db_instances'
    // "session_thresholds" column and DatabaseConfigService.buildSessionThresholdsJson.
    private final List<Integer> sessionThresholds;
    // true = CDB 구조의 PDB에 접속(connect_mode는 service로 고정), null/false = Non-CDB - Oracle 전용(2026-10-02).
    private final Boolean oracleCdb;

    @JsonCreator
    public CreateDbInstanceRequest(
            @JsonProperty("token") String token,
            @JsonProperty("group_name") String groupName,
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("db_type") String dbType,
            @JsonProperty("host") String host,
            @JsonProperty("port") int port,
            @JsonProperty("sid") String sid,
            @JsonProperty("user") String user,
            @JsonProperty("password") String password,
            @JsonProperty("connect_mode") String connectMode,
            @JsonProperty("expected_instance_name") String expectedInstanceName,
            @JsonProperty("pool_min_idle") Integer poolMinIdle,
            @JsonProperty("pool_max_size") Integer poolMaxSize,
            @JsonProperty("accounts") List<Map<String, String>> accounts,
            @JsonProperty("session_thresholds") List<Integer> sessionThresholds,
            @JsonProperty("oracle_cdb") Boolean oracleCdb) {
        this.token = token;
        this.groupName = groupName;
        this.id = id;
        this.name = name;
        this.dbType = dbType;
        this.host = host;
        this.port = port;
        this.sid = sid;
        this.user = user;
        this.password = password;
        this.connectMode = connectMode;
        this.expectedInstanceName = expectedInstanceName;
        this.poolMinIdle = poolMinIdle;
        this.poolMaxSize = poolMaxSize;
        this.accounts = accounts;
        this.sessionThresholds = sessionThresholds;
        this.oracleCdb = oracleCdb;
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

    public String dbType() {
        return dbType;
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

    public String connectMode() {
        return connectMode;
    }

    public String expectedInstanceName() {
        return expectedInstanceName;
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

    public Boolean oracleCdb() {
        return oracleCdb;
    }
}
