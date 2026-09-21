package com.dbagent.oracle;

public final class TargetDbConfig {

    private final String id;
    private final String name;
    // "oracle" (default, legacy instances have no db_type in databases.json) / "mysql" / "mariadb" / "postgres" / "mssql" / "cubrid".
    private final String dbType;
    private final String user;
    private final String password;
    private final String host;
    private final int port;
    private final String sid;
    // null/blank = default host:port:sid. "sid" = explicit host:port:sid; "service" =
    // host:port/service_name (sid field holds the service name); "descriptor" = sid field holds a
    // full connect descriptor/TNS string, host/port ignored.
    private final String connectMode;
    // null = not set in databases.json, caller should fall back to the application.properties default.
    private final Integer poolMinIdle;
    private final Integer poolMaxSize;
    // null/blank = no check. Oracle-only (compared against v$instance.instance_name on first
    // successful connection) - catches a mistyped host/port that happens to reach a real, but wrong,
    // instance (oracle.env 제거 마이그레이션 5단계, see PoolTestController).
    private final String expectedInstanceName;

    public TargetDbConfig(String id, String name, String dbType, String user, String password, String host, int port,
                           String sid, String connectMode, Integer poolMinIdle, Integer poolMaxSize,
                           String expectedInstanceName) {
        this.id = id;
        this.name = name;
        this.dbType = dbType;
        this.user = user;
        this.password = password;
        this.host = host;
        this.port = port;
        this.sid = sid;
        this.connectMode = connectMode;
        this.poolMinIdle = poolMinIdle;
        this.poolMaxSize = poolMaxSize;
        this.expectedInstanceName = expectedInstanceName;
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

    public String user() {
        return user;
    }

    public String password() {
        return password;
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

    public String connectMode() {
        return connectMode;
    }

    public Integer poolMinIdle() {
        return poolMinIdle;
    }

    public Integer poolMaxSize() {
        return poolMaxSize;
    }

    public String expectedInstanceName() {
        return expectedInstanceName;
    }
}
