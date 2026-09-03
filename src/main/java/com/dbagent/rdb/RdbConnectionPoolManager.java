package com.dbagent.rdb;

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
public class RdbConnectionPoolManager {

    private static final Duration COOLDOWN = Duration.ofSeconds(10);

    @Value("${dbagent.rdb.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    // Mirrors OracleConnectionPoolManager's oracle.net.READ_TIMEOUT: bounds how long a query can block
    // once a connection is already checked out, not just how long acquiring one takes (that's
    // connectTimeoutMs above). Without this, a hung/unresponsive MySQL/MariaDB/PostgreSQL server could
    // block a caller's thread and its checked-out connection forever - with the default pool size of 5,
    // as few as 5 such hangs against the same instance exhaust its whole pool permanently, even after
    // the DB recovers, since the stuck connections never get returned.
    @Value("${dbagent.rdb.read-timeout-ms:60000}")
    private int readTimeoutMs;

    @Value("${dbagent.rdb.pool.min-idle:1}")
    private int defaultPoolMinIdle;

    @Value("${dbagent.rdb.pool.max-size:5}")
    private int defaultPoolMaxSize;

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final Map<String, Instant> failedPools = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> poolLocks = new ConcurrentHashMap<>();

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
                        + "?allowPublicKeyRetrieval=true&sslMode=disable&socketTimeout=" + readTimeoutMs;
            case "postgres":
                // pgjdbc's socketTimeout is whole seconds, not ms, and 0 means "no timeout" - floor it
                // at 1 so a small readTimeoutMs can't silently round down to 0 and disable it entirely.
                int socketTimeoutSeconds = Math.max(1, readTimeoutMs / 1000);
                return "jdbc:postgresql://" + target.host() + ":" + target.port() + "/" + database
                        + "?socketTimeout=" + socketTimeoutSeconds;
            default:
                throw new IllegalArgumentException("Unsupported db_type for RdbConnectionPoolManager: " + target.dbType());
        }
    }

    private HikariDataSource createPool(TargetDbConfig target, String url) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(target.user());
        cfg.setPassword(target.password());
        cfg.setMinimumIdle(target.poolMinIdle() != null ? target.poolMinIdle() : defaultPoolMinIdle);
        cfg.setMaximumPoolSize(target.poolMaxSize() != null ? target.poolMaxSize() : defaultPoolMaxSize);
        cfg.setPoolName("rdb-" + target.id());
        cfg.setConnectionTimeout(connectTimeoutMs);
        cfg.setInitializationFailTimeout(-1);
        return new HikariDataSource(cfg);
    }
}
