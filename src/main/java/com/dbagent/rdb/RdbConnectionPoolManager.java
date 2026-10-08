package com.dbagent.rdb;

import com.dbagent.settings.SettingKey;
import com.dbagent.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import com.dbagent.oracle.TargetDbConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MySQL/MariaDB/PostgreSQL counterpart of OracleConnectionPoolManager - same key/cooldown/lock-per-key/
 * evict-by-prefix design, kept as a separate class so the Oracle path (com.dbagent.oracle) is never
 * touched by this feature. Only the JDBC URL/driver selection differs by TargetDbConfig.dbType().
 */
@Service
public class RdbConnectionPoolManager implements org.springframework.beans.factory.InitializingBean {

    // 환경설정 DB화 P3-2(2026-10-04): 이 클래스가 쓰던 @Value 설정을 SettingsService 조회로 옮겼다(저장 즉시 반영).
    @Autowired
    private SettingsService settings;

    private static final Duration COOLDOWN = Duration.ofSeconds(10);


    // Mirrors OracleConnectionPoolManager's oracle.net.READ_TIMEOUT: bounds how long a query can block
    // once a connection is already checked out, not just how long acquiring one takes (that's
    // connectTimeoutMs above). Without this, a hung/unresponsive MySQL/MariaDB/PostgreSQL server could
    // block a caller's thread and its checked-out connection forever - with the default pool size of 5,
    // as few as 5 such hangs against the same instance exhaust its whole pool permanently, even after
    // the DB recovers, since the stuck connections never get returned.



    // 풀 키 구분자. id 가 "a" 인 DB 와 "a_b" 인 DB 가 prefix 로 섞이지 않게 id 경계를 제어문자로 둔다(2026-10-09 점검 수정).
    private static final String KEY_SEP = "\u0001";
    // 설정 키를 읽지 못할 때의 조회 제한 시간(초).
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    // KILL 전용 풀(DB 당 연결 1개). 일반 풀 고갈 중에도 KILL 이 나갈 수 있게 따로 둔다.
    private final Map<String, HikariDataSource> killPools = new ConcurrentHashMap<>();
    private final Map<String, Instant> failedPools = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> poolLocks = new ConcurrentHashMap<>();

    // 환경설정 DB화 P5(2026-10-04): RDB 접속·응답 대기 시간과 풀 크기 기본값을 저장 즉시 반영한다. 설정이 바뀌면 지금 쓰던
    // 풀을 목록에서 빼 다음 조회부터 새 설정으로 풀을 다시 만들고, 쓰던 풀은 진행 중인 조회가 끝날 시간을 준 뒤 닫는다
    // (바로 닫으면 실행 중인 조회가 끊긴다). 풀을 다시 만들 때 대상 DB 에 새 접속이 생기므로 해당 설정은 화면에서 '위험' 표시다.
    // 쓰던 풀은 진행 중인 조회가 끝날 시간을 준 뒤 닫는다: 응답 대기 시간(그 풀이 만들어질 때의 값) + 30초, 최소 60초.
    // 조회 하나는 응답 대기 시간을 넘기면 어차피 끊기므로 그 안에는 끝난다 (QA 에서 60초 고정 지연이 150초 쿼리를 끊는 것을 확인 - 2026-10-04).
    private static final long POOL_RETIRE_MIN_DELAY_SECONDS = 60;
    private static final long POOL_RETIRE_EXTRA_SECONDS = 30;
    private volatile int appliedReadTimeoutMs;
    private static final java.util.concurrent.ScheduledExecutorService POOL_RETIRE =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pool-retire");
                t.setDaemon(true);
                return t;
            });
    private static final org.slf4j.Logger poolLog = org.slf4j.LoggerFactory.getLogger(RdbConnectionPoolManager.class);
    private volatile String poolSettingsSig;

    @Override
    public void afterPropertiesSet() {
        poolSettingsSig = poolSettingsSignature();
        appliedReadTimeoutMs = settings.getInt(SettingKey.RDB_READ_TIMEOUT_MS);
        settings.addChangeListener(this::onSettingsChanged);
    }

    private String poolSettingsSignature() {
        return settings.getInt(SettingKey.RDB_CONNECT_TIMEOUT_MS) + "|" + settings.getInt(SettingKey.RDB_READ_TIMEOUT_MS) + "|" + settings.getInt(SettingKey.RDB_POOL_MIN_IDLE) + "|" + settings.getInt(SettingKey.RDB_POOL_MAX_SIZE);
    }

    private synchronized void onSettingsChanged() {
        String sig = poolSettingsSignature();
        if (sig.equals(poolSettingsSig)) {
            return;
        }
        poolSettingsSig = sig;
        long delay = Math.max(POOL_RETIRE_MIN_DELAY_SECONDS, appliedReadTimeoutMs / 1000L + POOL_RETIRE_EXTRA_SECONDS);
        appliedReadTimeoutMs = settings.getInt(SettingKey.RDB_READ_TIMEOUT_MS);
        java.util.List<HikariDataSource> old = new java.util.ArrayList<>(pools.values());
        old.addAll(killPools.values());
        pools.clear();
        killPools.clear();
        failedPools.clear();
        poolLog.info("RDB 풀 설정이 바뀌어 기존 풀 {}개를 교체합니다 (다음 조회부터 새 설정, 기존 풀은 {}초 뒤 닫음): {}",
                old.size(), delay, sig);
        for (HikariDataSource ds : old) {
            POOL_RETIRE.schedule(() -> {
                try {
                    ds.close();
                } catch (RuntimeException e) {
                    poolLog.warn("이전 풀을 닫지 못했습니다: {}", e.toString());
                }
            }, delay, java.util.concurrent.TimeUnit.SECONDS);
        }
    }


    public Connection getConnection(TargetDbConfig target) throws SQLException {
        return acquire(target, false);
    }

    /**
     * KILL 전용 연결. 10초 쿨다운을 건너뛰고, 일반 풀이 가득 찼거나(느린 쿼리가 연결을 다 잡은 상황)
     * 접속에 실패하면 KILL 전용 풀(최대 1개)에서 따로 연결을 얻는다.
     * 풀이 고갈된 순간이 바로 KILL 이 가장 필요한 때라, 일반 조회와 같은 풀·쿨다운에 묶어 두면
     * 그 순간 KILL 도 막히기 때문이다 (2026-10-09 점검 수정 D-3).
     */
    public Connection getKillConnection(TargetDbConfig target) throws SQLException {
        return acquire(target, true);
    }

    private Connection acquire(TargetDbConfig target, boolean forKill) throws SQLException {
        String url = buildJdbcUrl(target);
        String poolKey = poolKey(target, url);

        if (!forKill) {
            checkCooldown(poolKey);
        }

        HikariDataSource ds = pools.get(poolKey);
        if (ds == null) {
            ReentrantLock keyLock = poolLocks.computeIfAbsent(poolKey, k -> new ReentrantLock());
            keyLock.lock();
            try {
                if (!forKill) {
                    checkCooldown(poolKey);
                }
                ds = pools.get(poolKey);
                if (ds == null) {
                    ds = createPool(target, url, false);
                    pools.put(poolKey, ds);
                }
            } finally {
                keyLock.unlock();
            }
        }

        // KILL 인데 일반 풀이 이미 가득 찼다면 연결 대기(수 초)를 건너뛰고 바로 전용 풀로 간다.
        if (forKill && isExhausted(ds)) {
            return killPoolConnection(target, url, poolKey);
        }

        try {
            return withQueryTimeout(ds.getConnection());
        } catch (SQLException e) {
            // 풀 고갈로 인한 대기 초과는 DB 장애가 아니므로 쿨다운을 걸지 않는다(걸면 풀이 비어도 10초간 전체 조회가 막힌다).
            if (!isExhausted(ds)) {
                failedPools.put(poolKey, Instant.now());
            }
            if (forKill) {
                try {
                    return killPoolConnection(target, url, poolKey);
                } catch (SQLException ke) {
                    e.addSuppressed(ke);
                }
            }
            throw e;
        }
    }

    private static String poolKey(TargetDbConfig target, String url) {
        return target.id() + KEY_SEP + target.user() + KEY_SEP + url;
    }

    private static boolean isExhausted(HikariDataSource ds) {
        try {
            com.zaxxer.hikari.HikariPoolMXBean mx = ds.getHikariPoolMXBean();
            return mx != null && mx.getIdleConnections() == 0
                    && mx.getActiveConnections() >= ds.getMaximumPoolSize();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Connection killPoolConnection(TargetDbConfig target, String url, String poolKey) throws SQLException {
        HikariDataSource kp = killPools.get(poolKey);
        if (kp == null) {
            ReentrantLock keyLock = poolLocks.computeIfAbsent(poolKey, k -> new ReentrantLock());
            keyLock.lock();
            try {
                kp = killPools.get(poolKey);
                if (kp == null) {
                    kp = createPool(target, url, true);
                    killPools.put(poolKey, kp);
                }
            } finally {
                keyLock.unlock();
            }
        }
        return withQueryTimeout(kp.getConnection());
    }

    /**
     * 이 DB 의 풀을 정확히 id 가 같은 것만 제거한다. 예전에는 prefix(id + "_")로 지워서
     * id "a" 를 지우면 "a_b" 풀까지 닫혔다 (2026-10-09 점검 수정).
     */
    public void evictPoolsForDbId(String dbId) {
        String prefix = dbId + KEY_SEP;
        for (Map<String, HikariDataSource> m : java.util.Arrays.asList(pools, killPools)) {
            m.keySet().removeIf(key -> {
                if (!key.startsWith(prefix)) {
                    return false;
                }
                HikariDataSource ds = m.get(key);
                if (ds != null) {
                    ds.close();
                }
                return true;
            });
        }
        failedPools.keySet().removeIf(key -> key.startsWith(prefix));
        poolLocks.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void checkCooldown(String poolKey) throws SQLException {
        Instant failedAt = failedPools.get(poolKey);
        if (failedAt != null) {
            if (Duration.between(failedAt, Instant.now()).compareTo(COOLDOWN) < 0) {
                throw new SQLException("최근 접속에 실패하여 잠시 대기 중입니다. 10초 후 다시 시도해 주세요.");
            }
            failedPools.remove(poolKey);
        }
    }

    /**
     * 이 연결에서 만드는 모든 Statement/PreparedStatement 에 쿼리 제한 시간을 건다(D-4, 2026-10-09 점검 수정).
     * 서비스 4개의 조회 지점이 수십 곳이라 호출부마다 넣으면 빠뜨리기 쉬워, 연결을 감싸 한 곳에서 처리한다.
     * 제한 시간은 화면 조회 제한 시간 설정(screen-query-timeout-seconds)을 따르고, 드라이버가
     * setQueryTimeout 을 지원하지 않아 예외가 나도 조회 자체는 계속한다.
     */
    private Connection withQueryTimeout(Connection raw) {
        int seconds;
        try {
            seconds = settings.getInt(SettingKey.SCREEN_QUERY_TIMEOUT_SECONDS);
        } catch (RuntimeException e) {
            seconds = DEFAULT_QUERY_TIMEOUT_SECONDS;
        }
        if (seconds <= 0) {
            return raw;
        }
        final int timeoutSec = seconds;
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(raw, args);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        throw ite.getCause() != null ? ite.getCause() : ite;
                    }
                    String name = method.getName();
                    if (result instanceof java.sql.Statement
                            && (name.equals("createStatement") || name.equals("prepareStatement")
                                    || name.equals("prepareCall"))) {
                        try {
                            ((java.sql.Statement) result).setQueryTimeout(timeoutSec);
                        } catch (SQLException | RuntimeException e) {
                            poolLog.debug("setQueryTimeout 을 적용하지 못했습니다(조회는 계속): {}", e.toString());
                        }
                    }
                    return result;
                });
    }

    private String buildJdbcUrl(TargetDbConfig target) {
        String database = target.sid() == null ? "" : target.sid();
        switch (target.dbType()) {
            case "mysql":
            case "mariadb":
                // mariadb-java-client speaks both MySQL's and MariaDB's wire protocol. MySQL 8+'s
                // default caching_sha2_password auth needs either SSL or RSA public-key retrieval to
                // send the password safely - these test/internal instances don't have SSL configured,
                // so allow the public-key round trip instead of failing the handshake. socketTimeout is
                // in milliseconds here and, like connectTimeoutMs, has no effect until it's actually
                // reached - 0 (never set) means the driver blocks forever on a stalled read.
                return "jdbc:mariadb://" + target.host() + ":" + target.port() + "/" + database
                        + "?allowPublicKeyRetrieval=true&sslMode=disable&socketTimeout=" + settings.getInt(SettingKey.RDB_READ_TIMEOUT_MS);
            case "postgres":
                // pgjdbc's socketTimeout is whole seconds, not ms, and 0 means "no timeout" - floor it
                // at 1 so a small readTimeoutMs can't silently round down to 0 and disable it entirely.
                int socketTimeoutSeconds = Math.max(1, settings.getInt(SettingKey.RDB_READ_TIMEOUT_MS) / 1000);
                return "jdbc:postgresql://" + target.host() + ":" + target.port() + "/" + database
                        + "?socketTimeout=" + socketTimeoutSeconds;
            case "mssql":
                // mssql-jdbc always TLS-wraps the login packet (credentials), even with encrypt=false -
                // that handshake fails against a self-signed/no-cert Docker or internal instance unless
                // trustServerCertificate=true tells the driver to skip validating the server's cert
                // chain. encrypt=false additionally disables encrypting the rest of the session data.
                // mssql-jdbc's socketTimeout is milliseconds, like MySQL's.
                return "jdbc:sqlserver://" + target.host() + ":" + target.port()
                        + ";databaseName=" + database + ";encrypt=false;trustServerCertificate=true"
                        + ";socketTimeout=" + settings.getInt(SettingKey.RDB_READ_TIMEOUT_MS);
            case "cubrid":
                // CUBRID's URL packs db/user/password into the path itself
                // (jdbc:CUBRID:host:port:db:user:password:?props) - the user/password segments are left
                // empty here on purpose so Hikari supplies them from setUsername/setPassword like every
                // other engine, instead of this string carrying the password.
                //
                // The port is the BROKER port (default 33000), not the server port (1523): clients never
                // talk to cub_server directly, they go through a CAS process behind the broker. A common
                // misconfiguration is registering 1523 here, which just times out.
                //
                // charset=utf-8 keeps Korean data readable; without it the driver falls back to the JVM
                // default and mangles multibyte text. There is no socket-timeout property in CUBRID's URL
                // syntax, so the read-timeout bound the other engines get from their driver comes from
                // Hikari's connection/validation timeouts alone here.
                return "jdbc:CUBRID:" + target.host() + ":" + target.port() + ":" + database
                        + ":::?charset=utf-8";
            default:
                throw new IllegalArgumentException("Unsupported db_type for RdbConnectionPoolManager: " + target.dbType());
        }
    }

    private HikariDataSource createPool(TargetDbConfig target, String url, boolean killOnly) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(target.user());
        cfg.setPassword(target.password());
        if (killOnly) {
            // KILL 전용: 평소엔 연결을 만들지 않고(minIdle 0) 필요할 때 1개만 연다.
            cfg.setMinimumIdle(0);
            cfg.setMaximumPoolSize(1);
            cfg.setPoolName("rdb-kill-" + target.id());
        } else {
            cfg.setMinimumIdle(target.poolMinIdle() != null ? target.poolMinIdle() : settings.getInt(SettingKey.RDB_POOL_MIN_IDLE));
            cfg.setMaximumPoolSize(target.poolMaxSize() != null ? target.poolMaxSize() : settings.getInt(SettingKey.RDB_POOL_MAX_SIZE));
            cfg.setPoolName("rdb-" + target.id());
        }
        cfg.setConnectionTimeout(settings.getInt(SettingKey.RDB_CONNECT_TIMEOUT_MS));
        cfg.setInitializationFailTimeout(-1);
        return new HikariDataSource(cfg);
    }
}
