package com.dbagent.oracle;

import com.dbagent.util.Strings;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of api_server.py's get_connection(): one HikariCP pool per (db_id, user, dsn, auth-mode),
 * created lazily, with a 10s cooldown after a failed connection attempt to avoid hammering a dead DB.
 */
@Service
public class OracleConnectionPoolManager {

    private static final Duration COOLDOWN = Duration.ofSeconds(10);

    // Bounds both the TCP connect and the Oracle login handshake, so a dead/firewalled DB fails
    // fast instead of hanging on an OS-level TCP timeout (which can be far longer than this).
    // Kept well under 1s: pool init (see minimumIdle below) makes one attempt at this bound, so the
    // *total* failure time is roughly this value, not a multiple of it. Tune upward
    // (dbagent.oracle.connect-timeout-ms) if a real-but-slow DB starts false-failing.
    @Value("${dbagent.oracle.connect-timeout-ms:500}")
    private int connectTimeoutMs;

    // Separate from connectTimeoutMs on purpose (사용자 피드백: SQL 정합성/튜닝 매뉴가 폐쇄망에서
    // 전부 타임아웃) - oracle.net.READ_TIMEOUT bounds how long the driver waits for the NEXT byte of
    // a query's response while a connection is already established, not connection setup. Reusing the
    // short dead-DB-detection value here meant any query that legitimately took longer than that
    // (AWR/ASH history, SQL tuning analysis, or just a slower network) got killed as if the DB were
    // unreachable. Kept generous since a real query has no natural upper bound the way a TCP connect
    // does.
    @Value("${dbagent.oracle.read-timeout-ms:60000}")
    private int readTimeoutMs;

    // Global defaults, used when an instance in databases.json doesn't set its own pool_min_idle/pool_max_size.
    @Value("${dbagent.oracle.pool.min-idle:2}")
    private int defaultPoolMinIdle;

    @Value("${dbagent.oracle.pool.max-size:10}")
    private int defaultPoolMaxSize;

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final Map<String, Instant> failedPools = new ConcurrentHashMap<>();
    // One lock per pool key, NOT a single shared lock: a hung/unreachable DB must never block
    // pool creation for any other, unrelated DB.
    private final Map<String, ReentrantLock> poolLocks = new ConcurrentHashMap<>();

    public Connection getConnection(TargetDbConfig target) throws SQLException {
        boolean sysdba = "SYS".equalsIgnoreCase(target.user());
        String dsn = buildDsn(target);
        String poolKey = target.id() + "_" + target.user() + "_" + dsn + "_" + (sysdba ? "SYSDBA" : "DEFAULT");

        checkCooldown(poolKey);

        HikariDataSource ds = pools.get(poolKey);
        if (ds == null) {
            ReentrantLock keyLock = poolLocks.computeIfAbsent(poolKey, k -> new ReentrantLock());
            keyLock.lock();
            try {
                checkCooldown(poolKey);
                ds = pools.get(poolKey);
                if (ds == null) {
                    // createPool() itself never blocks on connectivity (see initializationFailTimeout
                    // below) - the first real connection attempt, and its failure, happens below instead.
                    ds = createPool(target, dsn, sysdba);
                    pools.put(poolKey, ds);
                }
            } finally {
                keyLock.unlock();
            }
        }

        try {
            return ds.getConnection();
        } catch (SQLException e) {
            // A pool-exhaustion timeout (HikariCP's own "Connection is not available" message) means
            // too many callers were competing for this pool at once, not that the DB is unreachable -
            // don't cool down on it, so the very next caller gets a fresh attempt instead of being
            // locked out for COOLDOWN on top of a pool that likely already freed up.
            if (!isPoolExhausted(e)) {
                failedPools.put(poolKey, Instant.now());
            }
            throw e;
        }
    }

    // Pool keys embed host/user/dsn (see getConnection), so editing or deleting a databases.json
    // instance never matches an already-cached pool's key - without this, the old pool (and its live
    // DB connections) would just leak forever instead of being replaced. Called by DbConfigAdminController
    // after a successful update/delete.
    public void evictPoolsForDbId(String dbId) {
        final String prefix = dbId + "_";
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

    private static final Pattern WAITING_PATTERN = Pattern.compile("waiting=(\\d+)");

    // HikariCP wraps BOTH cases in the exact same "Connection is not available" message: (a) every
    // existing connection was busy and this caller queued up for one (self-inflicted contention - the
    // DB is fine), and (b) the pool had no connections at all because the underlying physical connect
    // attempt itself failed (a real outage). The "waiting=N" count in the message is what actually
    // tells them apart: N > 0 means another caller really was queued for an in-use connection; N == 0
    // means nobody was competing, so the timeout can only be a genuine connect failure.
    public static boolean isPoolExhausted(SQLException e) {
        String msg = e.getMessage();
        if (msg == null || !msg.contains("Connection is not available")) {
            return false;
        }
        Matcher m = WAITING_PATTERN.matcher(msg);
        return m.find() && Integer.parseInt(m.group(1)) > 0;
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

    private String buildDsn(TargetDbConfig target) {
        String host = target.host();
        if (host != null && !Strings.isBlank(host)) {
            return host + ":" + target.port() + ":" + target.sid();
        }
        // Empty host => treat SID as a TNS alias (tnsnames.ora), same fallback the Python client used.
        return target.sid();
    }

    private HikariDataSource createPool(TargetDbConfig target, String dsn, boolean sysdba) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:oracle:thin:@" + dsn);
        cfg.setUsername(target.user());
        cfg.setPassword(target.password());
        cfg.setDriverClassName("oracle.jdbc.OracleDriver");
        cfg.setMinimumIdle(target.poolMinIdle() != null ? target.poolMinIdle() : defaultPoolMinIdle);
        cfg.setMaximumPoolSize(target.poolMaxSize() != null ? target.poolMaxSize() : defaultPoolMaxSize);
        cfg.setPoolName("oracle-" + target.id());
        // Hikari's own wait-for-connection bound, used by every getConnection() call.
        cfg.setConnectionTimeout(connectTimeoutMs);
        // Negative = do NOT synchronously probe the DB (and retry with an internal ~1s backoff) while
        // constructing the pool; warm-up connections are created in the background instead. This is
        // what keeps pool creation itself instant - the single bounded attempt now happens only on
        // getConnection(), so a down DB fails in one connectTimeoutMs window, not a multiple of it.
        cfg.setInitializationFailTimeout(-1);
        // Oracle-driver-level bounds: without these, a firewalled/black-holed host can hang far
        // longer than connectTimeoutMs on the raw TCP connect, ignoring Hikari's own timeout.
        cfg.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(connectTimeoutMs));
        cfg.addDataSourceProperty("oracle.net.READ_TIMEOUT", String.valueOf(readTimeoutMs));
        if (sysdba) {
            // Thin-driver SYSDBA login, equivalent to oracledb.AUTH_MODE_SYSDBA in Python.
            cfg.addDataSourceProperty("internal_logon", "sysdba");
        }
        return new HikariDataSource(cfg);
    }
}
