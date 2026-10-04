package com.dbagent.oracle;

import com.dbagent.settings.SettingKey;
import com.dbagent.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import com.dbagent.util.Strings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
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
public class OracleConnectionPoolManager implements org.springframework.beans.factory.InitializingBean {

    // 환경설정 DB화 P3-2(2026-10-04): 이 클래스가 쓰던 @Value 설정을 SettingsService 조회로 옮겼다(저장 즉시 반영).
    @Autowired
    private SettingsService settings;

    private static final Logger log = LoggerFactory.getLogger(OracleConnectionPoolManager.class);

    private static final Duration COOLDOWN = Duration.ofSeconds(10);

    // Bounds both the TCP connect and the Oracle login handshake, so a dead/firewalled DB fails
    // fast instead of hanging on an OS-level TCP timeout (which can be far longer than this).
    // Kept well under 1s: pool init (see minimumIdle below) makes one attempt at this bound, so the
    // *total* failure time is roughly this value, not a multiple of it. Tune upward
    // (dbagent.oracle.connect-timeout-ms) if a real-but-slow DB starts false-failing.
    // 코드 기본값을 jar 번들 값(3000)과 맞췄다(2026-10-04) - 설정 파일 없이 뜨는 환경에서도 실제 네트워크 지연에
    // 오탐으로 "접속 불가"가 나지 않게 하려는 것. 로컬 개발처럼 빨리 실패시키고 싶으면 500 등으로 내린다.

    // Separate from connectTimeoutMs on purpose (사용자 피드백: SQL 정합성/튜닝 매뉴가 폐쇄망에서
    // 전부 타임아웃) - oracle.net.READ_TIMEOUT bounds how long the driver waits for the NEXT byte of
    // a query's response while a connection is already established, not connection setup. Reusing the
    // short dead-DB-detection value here meant any query that legitimately took longer than that
    // (AWR/ASH history, SQL tuning analysis, or just a slower network) got killed as if the DB were
    // unreachable. Kept generous since a real query has no natural upper bound the way a TCP connect
    // does.

    // Global defaults, used when an instance in databases.json doesn't set its own pool_min_idle/pool_max_size.


    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final Map<String, Instant> failedPools = new ConcurrentHashMap<>();
    // One lock per pool key, NOT a single shared lock: a hung/unreachable DB must never block
    // pool creation for any other, unrelated DB.
    private final Map<String, ReentrantLock> poolLocks = new ConcurrentHashMap<>();

    // 환경설정 DB화 P5(2026-10-04): Oracle 접속·응답 대기 시간과 풀 크기 기본값을 저장 즉시 반영한다. 설정이 바뀌면 지금 쓰던
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
    private static final org.slf4j.Logger poolLog = org.slf4j.LoggerFactory.getLogger(OracleConnectionPoolManager.class);
    private volatile String poolSettingsSig;

    @Override
    public void afterPropertiesSet() {
        poolSettingsSig = poolSettingsSignature();
        appliedReadTimeoutMs = settings.getInt(SettingKey.ORACLE_READ_TIMEOUT_MS);
        settings.addChangeListener(this::onSettingsChanged);
    }

    private String poolSettingsSignature() {
        return settings.getInt(SettingKey.ORACLE_CONNECT_TIMEOUT_MS) + "|" + settings.getInt(SettingKey.ORACLE_READ_TIMEOUT_MS) + "|" + settings.getInt(SettingKey.ORACLE_POOL_MIN_IDLE) + "|" + settings.getInt(SettingKey.ORACLE_POOL_MAX_SIZE);
    }

    private synchronized void onSettingsChanged() {
        String sig = poolSettingsSignature();
        if (sig.equals(poolSettingsSig)) {
            return;
        }
        poolSettingsSig = sig;
        long delay = Math.max(POOL_RETIRE_MIN_DELAY_SECONDS, appliedReadTimeoutMs / 1000L + POOL_RETIRE_EXTRA_SECONDS);
        appliedReadTimeoutMs = settings.getInt(SettingKey.ORACLE_READ_TIMEOUT_MS);
        java.util.List<HikariDataSource> old = new java.util.ArrayList<>(pools.values());
        pools.clear();
        failedPools.clear();
        poolLog.info("Oracle 풀 설정이 바뀌어 기존 풀 {}개를 교체합니다 (다음 조회부터 새 설정, 기존 풀은 {}초 뒤 닫음): {}",
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
    private static final Pattern ACTIVE_PATTERN = Pattern.compile("active=(\\d+)");

    // HikariCP wraps BOTH cases in the exact same "Connection is not available" message: (a) every
    // existing connection was busy and this caller queued up for one (self-inflicted contention - the
    // DB is fine), and (b) the pool had no connections at all because the underlying physical connect
    // attempt itself failed (a real outage). The "waiting=N" count in the message is what actually
    // tells them apart: N > 0 means another caller really was queued for an in-use connection; N == 0
    // means nobody was competing, so the timeout can only be a genuine connect failure.
    // active=0도 실제 장애로 본다: 죽은 DB에 여러 요청이 동시에 오면 연결 하나 없이(total=0, active=0)
    // waiting만 2 이상이 되는데, 쥐고 있는 연결이 없으니 경합일 수 없다. 예전엔 이것도 경합(Busy)으로 봐서
    // 헬스체크가 Not Alive ↔ Busy로 섞여 나왔다(2026-09-29 로컬 실측: DB 정지 중 12번 중 3번 Busy).
    // active 값을 못 읽으면(메시지 형식 변경) 예전처럼 waiting만 본다.
    public static boolean isPoolExhausted(SQLException e) {
        String msg = e.getMessage();
        if (msg == null || !msg.contains("Connection is not available")) {
            return false;
        }
        Matcher m = WAITING_PATTERN.matcher(msg);
        if (!m.find() || Integer.parseInt(m.group(1)) <= 0) {
            return false;
        }
        Matcher a = ACTIVE_PATTERN.matcher(msg);
        return !a.find() || Integer.parseInt(a.group(1)) > 0;
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

    // oracle.env 제거 마이그레이션 5단계: alias(tnsnames.ora, host 빈값 → sid를 TNS alias로 취급)
    // 경로 완전 삭제. host는 이제 항상 필수이므로 미지정 connect_mode는 그냥 host:port:sid로
    // 고정된다 - RAC도 노드별 개별 등록(설계문서 "RAC 대응" 절)이라 failover용 alias가 애초에 필요
    // 없고, 정말 복잡한 커넥트 디스크립터가 필요하면 connect_mode=descriptor로 표현 가능하다.
    private String buildDsn(TargetDbConfig target) {
        String mode = target.connectMode();
        if (!Strings.isBlank(mode)) {
            switch (mode.toLowerCase(Locale.ROOT)) {
                // Explicit SID descriptor: host:port:SID.
                case "sid":
                    return target.host() + ":" + target.port() + ":" + target.sid();
                // Service-name descriptor: needs the "//" form, unlike the plain SID syntax above.
                case "service":
                    return "//" + target.host() + ":" + target.port() + "/" + target.sid();
                // Caller supplies a full connect descriptor/TNS string in "sid" verbatim; host/port unused.
                case "descriptor":
                    return target.sid();
                default:
                    return target.host() + ":" + target.port() + ":" + target.sid();
            }
        }
        return target.host() + ":" + target.port() + ":" + target.sid();
    }

    private HikariDataSource createPool(TargetDbConfig target, String dsn, boolean sysdba) {
        log.info("oracle-{} -> {}", target.id(), dsn);
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:oracle:thin:@" + dsn);
        cfg.setUsername(target.user());
        cfg.setPassword(target.password());
        cfg.setDriverClassName("oracle.jdbc.OracleDriver");
        cfg.setMinimumIdle(target.poolMinIdle() != null ? target.poolMinIdle() : settings.getInt(SettingKey.ORACLE_POOL_MIN_IDLE));
        cfg.setMaximumPoolSize(target.poolMaxSize() != null ? target.poolMaxSize() : settings.getInt(SettingKey.ORACLE_POOL_MAX_SIZE));
        cfg.setPoolName("oracle-" + target.id());
        // KILL 대상 세션의 ORA-00031(SQLSTATE 08006)로 우리 커넥션을 닫지 않게(2026-10-01, OracleSqlExceptionOverride 참고)
        cfg.setExceptionOverrideClassName(OracleSqlExceptionOverride.class.getName());
        // Hikari's own wait-for-connection bound, used by every getConnection() call.
        cfg.setConnectionTimeout(settings.getInt(SettingKey.ORACLE_CONNECT_TIMEOUT_MS));
        // Negative = do NOT synchronously probe the DB (and retry with an internal ~1s backoff) while
        // constructing the pool; warm-up connections are created in the background instead. This is
        // what keeps pool creation itself instant - the single bounded attempt now happens only on
        // getConnection(), so a down DB fails in one connectTimeoutMs window, not a multiple of it.
        cfg.setInitializationFailTimeout(-1);
        // Oracle-driver-level bounds: without these, a firewalled/black-holed host can hang far
        // longer than connectTimeoutMs on the raw TCP connect, ignoring Hikari's own timeout.
        cfg.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(settings.getInt(SettingKey.ORACLE_CONNECT_TIMEOUT_MS)));
        cfg.addDataSourceProperty("oracle.net.READ_TIMEOUT", String.valueOf(settings.getInt(SettingKey.ORACLE_READ_TIMEOUT_MS)));
        if (sysdba) {
            // Thin-driver SYSDBA login, equivalent to oracledb.AUTH_MODE_SYSDBA in Python.
            cfg.addDataSourceProperty("internal_logon", "sysdba");
        }
        return new HikariDataSource(cfg);
    }
}
