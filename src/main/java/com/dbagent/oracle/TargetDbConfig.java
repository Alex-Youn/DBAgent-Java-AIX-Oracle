package com.dbagent.oracle;

public final class TargetDbConfig {

    private final String id;
    private final String name;
    // "oracle" (default, legacy instances have no db_type in databases.json) / "mysql" / "mariadb" / "postgres".
    private final String dbType;
    private final String user;
    private final String password;
    private final String host;
    private final int port;
    private final String sid;
    // null = not set in databases.json, caller should fall back to the application.properties default.
    private final Integer poolMinIdle;
    private final Integer poolMaxSize;

    public TargetDbConfig(String id, String name, String dbType, String user, String password, String host, int port,
                           String sid, Integer poolMinIdle, Integer poolMaxSize) {
        this.id = id;
        this.name = name;
        this.dbType = dbType;
        this.user = user;
        this.password = password;
        this.host = host;
        this.port = port;
        this.sid = sid;
        this.poolMinIdle = poolMinIdle;
        this.poolMaxSize = poolMaxSize;
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

    public Integer poolMinIdle() {
        return poolMinIdle;
    }

    public Integer poolMaxSize() {
        return poolMaxSize;
    }
}
