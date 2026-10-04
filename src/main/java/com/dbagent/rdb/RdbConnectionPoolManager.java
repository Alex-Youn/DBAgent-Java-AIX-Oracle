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



    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
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
        pools.clear();
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
        String url = buildJdbcUrl(target);
        String poolKey = target.id() + "_" + target.user() + "_" + url;

        checkCooldown(poolKey);

        HikariDataSource ds = pools.get(poolKey);
        if (ds == null) {
            ReentrantLock keyLock = poolLocks.computeIfAbsent(poolKey, k -> new ReentrantLock());
            keyLock.lock();
            try {
                checkCooldown(poolKey);
                ds = pools.get(poolKey);
                if (ds == null) {
                    ds = createPool(target, url);
                    pools.put(poolKey, ds);
                }
            } finally {
                keyLock.unlock();
            }
        }

        try {
            return ds.getConnection();
        } catch (SQLException e) {
            failedPools.put(poolKey, Instant.now());
            throw e;
        }
    }

    public void evictPoolsForDbId(String dbId) {
        String prefix = dbId + "_";
        pools.keySet().removeIf(key -> {
            if (!key.startsWith(prefix)) {
                return false;
            }
            HikariDataSource ds = pools.get(key);
            if (ds != null) {
                ds.close();
            }
            return true;
        });
        failedPools.keySet().removeIf(key -> key.startsWith(prefix));
        poolLocks.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void checkCooldown(String poolKey) throws SQLException {
        Instant failedAt = failedPools.get(poolKey);
        if (failedAt != null) {
            if (Duration.between(failedAt, Instant.now()).compareTo(COOLDOWN) < 0) {
                throw new SQLException("Connection failed recently. Waiting for cooldown.");
            }
            failedPools.remove(poolKey);
        }
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

    private HikariDataSource createPool(TargetDbConfig target, String url) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(target.user());
        cfg.setPassword(target.password());
        cfg.setMinimumIdle(target.poolMinIdle() != null ? target.poolMinIdle() : settings.getInt(SettingKey.RDB_POOL_MIN_IDLE));
        cfg.setMaximumPoolSize(target.poolMaxSize() != null ? target.poolMaxSize() : settings.getInt(SettingKey.RDB_POOL_MAX_SIZE));
        cfg.setPoolName("rdb-" + target.id());
        cfg.setConnectionTimeout(settings.getInt(SettingKey.RDB_CONNECT_TIMEOUT_MS));
        cfg.setInitializationFailTimeout(-1);
        return new HikariDataSource(cfg);
    }
}
