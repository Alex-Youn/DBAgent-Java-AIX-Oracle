package com.dbagent.monitor;

import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.settings.SettingKey;
import com.dbagent.settings.SettingsService;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 대시보드 개편 ③ Lock 대기 세션(실시간)과 ③-1 TM Lock 장애 처리(KILL) - 설계문서 `대시보드 UI 개선 설계.md`
 * 5장 ③·③-1, 전체 작업순서 F2(2026-09-25).
 *
 * <ul>
 *   <li>한 번의 조회(TX/TM 대기 건수 + TM Holder 세션 단위 목록)를 두 가지로 나눠 쓴다: 장애 판정 수(v2
 *       getFailureProb()와 같은 규칙: last_call_et ≥ 기준, 자기는 안 막혀 있고, 누군가를 막고 있음)와 KILL 대상(USER
 *       세션 전체 - 2026-09-25 결정, 설정으로만 좁힘). 쿼리에는 필터를 넣지 않는다.</li>
 *   <li>부하 제어: DB별로 결과를 cacheMillis(2초) 동안 재사용하고, 조회 중이면 새로 띄우지 않고 그 결과를 같이 기다린다
 *       - 보는 사람이 여러 명이어도 원본 DB 조회는 주기당 1회.</li>
 *   <li>실패·타임아웃이면 ok=false("판단 보류")로 내려 0으로 그리지 않게 한다.</li>
 *   <li>최근 10분 추이는 메모리, 1분 요약(최대값)은 mon_lock_sample에 저장.</li>
 *   <li>v$ 뷰만 사용(RAC 원칙, inst_id 없음).</li>
 * </ul>
 */
@Service
public class LockRealtimeService {

    private static final Logger log = LoggerFactory.getLogger(LockRealtimeService.class);

    private static final ExecutorService LOCK_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lock-realtime");
        t.setDaemon(true);
        return t;
    });

    private static final ScheduledExecutorService KEEPALIVE_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lock-keepalive");
        t.setDaemon(true);
        return t;
    });

    private static final long HISTORY_WINDOW_MS = 10 * 60 * 1000L;

    // 빈 구간 채움(2026-09-28): 화면이 없던 동안(다른 메뉴·다른 DB)은 3초 추이가 쌓이지 않아, 돌아오면 중간이 비거나
    // (10분 넘게 비웠으면) 처음부터 다시 그려졌다. 그 구간을 60초 샘플러가 모든 DB에 대해 늘 남기는 tx/tm_lock_waiting
    // (v$lock 요청 세션 수, 1분 해상도)으로 채워 "fill"로 내려준다 - 원본 DB 조회는 늘지 않는다. 3초마다 SQLite를 읽지
    // 않게 DB별로 FILL_CACHE_MS 동안 재사용한다.
    private static final long FILL_CACHE_MS = 30000L;
    // 실시간 점이 이 거리 안에 있으면 그 1분 점은 쓰지 않는다(실시간 값이 있는 구간은 실시간 값만).
    private static final long FILL_NEAR_LIVE_MS = 30000L;
    private final Map<String, Object[]> fillCache = new ConcurrentHashMap<>(); // {조회 시각, List<{t, tx, tm}>}

    private final OracleConnectionPoolManager poolManager;
    private final MonitorService monitorService;
    private final MonitorStoreService storeService;
    private final InstanceMetricHistoryService historyService;
    private final SettingsService settings;

    @Value("${dbagent.monitor.lock-query-timeout-seconds:3}")
    private int lockQueryTimeoutSeconds;






    // ⑧ 점검 알림의 "장시간 실행 쿼리" 실시간 카드(2026-09-30 두 번째 대시보드에서 이식) - ACTIVE 사용자 세션 중 현재 호출이
    // 이 초 이상 도는 것(last_call_et). 두 번째 대시보드와 같은 기본 300초. 10분 주기 점검이 아니라 3초 조회에 얹는 이유:
    // 5분 넘는 쿼리는 다음 10분 점검 전에 끝나 버려 놓치기 쉽다. 0 이하면 끔(프로젝트 관례, 2026-09-30 정밀 점검 반영 -
    // 예전엔 0이면 모든 ACTIVE 세션이 장시간으로 잡혔다).

    // A안 - 최근 본 DB 백그라운드 수집(2026-09-28, 설계문서 `Lock실시간차트_빈구간_A+B_전환계획_2026-09-28.md` 4.1).
    // 마지막으로 본 뒤 keepaliveMinutes 동안은 화면이 없어도 3초마다 조회해 history를 이어 붙인다. 그보다 오래 비운
    // 구간은 지금처럼 B(fill)로 채운다. 0이면 A 비활성(= B만) - 운영 DB 부하 실측 전까지 기본값은 0.
    @Value("${dbagent.monitor.lock-keepalive-minutes:0}")
    private int keepaliveMinutes;

    /** 동시에 백그라운드 수집하는 DB 수 상한 - 넘으면 가장 오래 전에 본 DB부터 제외. 0 이하면 상한 없음. */
    @Value("${dbagent.monitor.lock-keepalive-max-dbs:5}")
    private int keepaliveMaxDbs;

    private static final long KEEPALIVE_INTERVAL_MS = 3000L;
    // 화면 폴링(3초)이 방금 조회했으면 건너뛴다 - 화면이 열려 있는 동안 원본 DB 조회가 주기당 1회를 넘지 않게.
    private static final long KEEPALIVE_SKIP_IF_FRESH_MS = KEEPALIVE_INTERVAL_MS - 500L;
    // 접속 불가 DB에 3초마다 타임아웃을 거는 것 방지 - 연속 실패가 이 횟수면 일정 시간 백그라운드 수집을 멈춘다.
    private static final int KEEPALIVE_FAIL_LIMIT = 5;
    private static final long KEEPALIVE_FAIL_PAUSE_MS = 5 * 60 * 1000L;
    private final Map<String, Viewed> viewed = new ConcurrentHashMap<>();

    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Snapshot>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Deque<Map<String, Object>>> history = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> killEvents = new ConcurrentHashMap<>();
    private final Map<String, MinuteSummary> minuteSummary = new ConcurrentHashMap<>();
    /** ① KPI 메모리 사용률 - 천천히 변하는 값이라 DB별 60초 캐시(3초 주기마다 v$sgastat 등을 읽지 않음). */
    private static final long MEMORY_CACHE_MS = 60000L;
    private final Map<String, double[]> memoryCache = new ConcurrentHashMap<>(); // {측정 시각, 사용률%}
    /**
     * ① KPI TPS·Buffer Cache Hit(2026-09-30 추가) - v$sysmetric 60초 그룹 값이라 1분마다만 바뀐다. DB별 15초 캐시로
     * 3초 주기마다 읽지 않으면서 새 값이 나오면 15초 안에 반영.
     */
    private static final long SYSMETRIC_CACHE_MS = 15000L;
    private final Map<String, Double[]> sysmetricCache = new ConcurrentHashMap<>(); // {측정 시각, TPS, Hit%}
    /** 장시간 실행 쿼리 상위 5개 목록 - 그런 세션이 계속 있는 DB에서 3초마다 목록 조회가 나가지 않게 DB별 15초 캐시. */
    private static final long LONG_TOP_CACHE_MS = 15000L;
    private final Map<String, LongTop> longTopCache = new ConcurrentHashMap<>();

    /** DB별 object_id → OWNER.NAME. 매 주기 dba_objects 조인 금지(11g 무응답 전례) - 모르는 id만 한 번 조회. */
    private final Map<String, Map<Long, String>> objectNames = new ConcurrentHashMap<>();

    public LockRealtimeService(OracleConnectionPoolManager poolManager, MonitorService monitorService,
                               MonitorStoreService storeService, InstanceMetricHistoryService historyService,
                               SettingsService settings) {
        this.poolManager = poolManager;
        this.monitorService = monitorService;
        this.storeService = storeService;
        this.historyService = historyService;
        this.settings = settings;
        // 설정이 바뀌면 2초 캐시된 스냅샷(옛 기준으로 판정된 장애 여부·KILL 대상)을 버려 다음 조회부터 새 기준을 쓴다.
        settings.addChangeListener(cache::clear);
    }

    // ------------------------------------------------------------------ 조회

    /** 실시간 응답 - 캐시(2초) 또는 진행 중 조회를 공유한다. */
    public Map<String, Object> realtime(TargetDbConfig target) {
        Snapshot cached = cache.get(target.id());
        long now = System.currentTimeMillis();
        if (keepaliveMinutes > 0) {
            viewed.compute(target.id(), (id, v) -> {
                Viewed n = v != null ? v : new Viewed();
                n.target = target; // 설정이 바뀌었으면 다음 백그라운드 조회부터 새 값으로
                n.lastViewedAt = now;
                return n;
            });
        }
        Snapshot snap;
        if (cached != null && now - cached.at < settings.getLong(SettingKey.LOCK_CACHE_MILLIS)) {
            snap = cached;
        } else {
            try {
                snap = inFlightFor(target).join();
            } catch (CompletionException e) {
                snap = Snapshot.failed(now, e.getCause() != null ? e.getCause() : e);
            }
        }
        return toResponse(target.id(), snap);
    }

    private CompletableFuture<Snapshot> inFlightFor(TargetDbConfig target) {
        String id = target.id();
        CompletableFuture<Snapshot> f = inFlight.computeIfAbsent(id, k -> CompletableFuture.supplyAsync(() -> {
            Snapshot s;
            try {
                s = query(target);
            } catch (Exception e) {
                s = Snapshot.failed(System.currentTimeMillis(), e);
            }
            cache.put(k, s);
            record(k, s);
            return s;
        }, LOCK_EXECUTOR));
        // 정리 콜백은 computeIfAbsent 밖에서 건다(2026-09-28 점검). 안에서 걸면 조회가 콜백 등록 전에 끝났을 때 콜백이
        // computeIfAbsent 도중 같은 키로 remove를 불러 "Recursive update" 예외가 나거나 아무것도 지우지 못해, 끝난 future가
        // inFlight에 영구히 남아 화면 폴링·A안 백그라운드 수집이 옛 결과만 재사용(Lock 카드 멈춤)할 수 있었다.
        // 여러 호출자가 각각 걸어도 remove(id, f)는 같은 future일 때만 지우므로 무해하다.
        f.whenComplete((r, e) -> inFlight.remove(id, f));
        return f;
    }

    /**
     * DB 설정 수정·삭제 시 이 DB의 상태를 모두 버린다(2026-09-28 점검). 안 지우면 A안 viewed에 남은 옛 TargetDbConfig로
     * 백그라운드 수집이 계속돼 - 삭제한 DB는 풀이 다시 만들어져 방치되고, 수정한 DB는 옛 접속 정보로 조회한다.
     * 추이·캐시도 옛 대상의 값이라 같이 지운다. 이미 진행 중인 옛 조회 1건은 끝나면서 한 번 기록될 수 있으나
     * 다음 주기부터는 새 설정으로 조회하므로 그대로 둔다.
     */
    public void clearDb(String id) {
        viewed.remove(id);
        inFlight.remove(id);
        cache.remove(id);
        history.remove(id);
        killEvents.remove(id);
        minuteSummary.remove(id);
        memoryCache.remove(id);
        sysmetricCache.remove(id);
        longTopCache.remove(id);
        fillCache.remove(id);
        objectNames.remove(id);
    }

    /**
     * A안이 켜져 있을 때만 전용 스레드로 3초 수집을 돌린다. 공용 @Scheduled 스케줄러(SchedulingConfig, 스레드 2개)를
     * 쓰지 않는 이유: 기동 직후 점검 알림(CheckCollector)과 60초 샘플러가 두 스레드를 수십 초 잡고 있으면 이 작업이
     * 밀려 3초 선이 끊겼다(2026-09-28 로컬 실측 - 기동 직후 44초 공백).
     */
    @PostConstruct
    void startKeepalive() {
        if (keepaliveMinutes <= 0) return;
        KEEPALIVE_SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                keepaliveCollect();
            } catch (Exception e) {
                log.debug("lock keepalive cycle failed: {}", e.toString()); // 예외가 새면 이후 주기가 모두 멈춘다
            }
        }, KEEPALIVE_INTERVAL_MS, KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("lock keepalive enabled: {} min after last view, max {} DBs", keepaliveMinutes,
                keepaliveMaxDbs > 0 ? keepaliveMaxDbs : "unlimited");
    }

    /**
     * A안 백그라운드 수집 - 최근 keepaliveMinutes 안에 본 DB를 3초마다 조회한다. 조회는 inFlightFor()를 그대로 타므로
     * 캐시·진행 중 조회 공유·record()(10분 추이, 1분 요약)가 화면 조회와 똑같이 적용된다. 이 스레드는 조회를 기다리지
     * 않는다(비동기) - 느린 DB가 다른 DB의 수집을 막지 않게.
     */
    void keepaliveCollect() {
        long now = System.currentTimeMillis();
        long keepaliveMs = keepaliveMinutes * 60000L;
        viewed.entrySet().removeIf(e -> now - e.getValue().lastViewedAt >= keepaliveMs);
        List<Map.Entry<String, Viewed>> targets = new ArrayList<>(viewed.entrySet());
        targets.sort((a, b) -> Long.compare(b.getValue().lastViewedAt, a.getValue().lastViewedAt)); // 최근 본 순
        if (keepaliveMaxDbs > 0 && targets.size() > keepaliveMaxDbs) {
            targets = targets.subList(0, keepaliveMaxDbs);
        }
        for (Map.Entry<String, Viewed> e : targets) {
            String id = e.getKey();
            Viewed v = e.getValue();
            // 목록을 복사한 뒤 DB 설정 수정·삭제로 clearDb()가 불렸으면 옛 설정으로 조회하지 않게 건너뛴다(2026-09-28 점검) -
            // 안 그러면 삭제한 DB의 풀이 옛 접속 정보로 1회 재생성될 수 있었다. 확인~조회 사이의 아주 짧은 틈은 남지만
            // 1회 조회 때문에 락까지 걸 일은 아니라고 판단.
            if (viewed.get(id) != v) continue;
            if (now < v.pausedUntil) continue;
            Snapshot cached = cache.get(id);
            if (cached != null && now - cached.at < KEEPALIVE_SKIP_IF_FRESH_MS) continue;
            if (inFlight.containsKey(id)) continue;
            inFlightFor(v.target).whenComplete((s, ex) -> {
                if (s != null && s.ok) {
                    v.fails = 0;
                } else if (++v.fails >= KEEPALIVE_FAIL_LIMIT) {
                    v.fails = 0;
                    v.pausedUntil = System.currentTimeMillis() + KEEPALIVE_FAIL_PAUSE_MS;
                    log.info("lock keepalive paused for {} ({} consecutive failures, {} min): {}", id, KEEPALIVE_FAIL_LIMIT,
                            KEEPALIVE_FAIL_PAUSE_MS / 60000L, s != null ? s.error : ex);
                }
            });
        }
    }

    private Snapshot query(TargetDbConfig target) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            Snapshot s = new Snapshot(System.currentTimeMillis());
            // 장시간 실행 기준은 DB별로 설정할 수 있다(환경설정 DB화 P3-1, 2026-10-04). 정수만 SQL 문자열에 들어간다.
            int longRunningSeconds = settings.getInt(SettingKey.LONG_RUNNING_QUERY_SECONDS, target.id());
            String txCond = settings.getBool(SettingKey.LOCK_TX_ROW_LOCK_ONLY) ? "event = 'enq: TX - row lock contention'" : "event LIKE 'enq: TX%'";
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(lockQueryTimeoutSeconds);
                try (ResultSet rs = st.executeQuery(
                        "SELECT COUNT(CASE WHEN " + txCond + " THEN 1 END) AS tx_cnt, " +
                                "COUNT(CASE WHEN event LIKE 'enq: TM%' THEN 1 END) AS tm_cnt " +
                                "FROM v$session WHERE state = 'WAITING' AND (event LIKE 'enq: TX%' OR event LIKE 'enq: TM%')")) {
                    if (rs.next()) {
                        s.txWait = rs.getInt("tx_cnt");
                        s.tmWait = rs.getInt("tm_cnt");
                    }
                }
            }
            // ① KPI "활성 세션" 칸(2026-09-25 추가) - 기존 대시보드 활성 세션(MonitorService의 status 조회)과 같은 정의.
            // 같은 커넥션·같은 캐시를 타므로 원본 DB 조회 횟수는 늘지 않는다. 장시간 실행 쿼리 수(2026-09-30)도 같은 스캔에서 센다.
            String userSessions = "FROM v$session WHERE status = 'ACTIVE' AND type != 'BACKGROUND' " +
                    "AND username IS NOT NULL AND username != '" + target.user().toUpperCase().replace("'", "''") + "'";
            // 장시간 실행: Idle 이벤트로 기다리는 중인 세션은 뺀다 - AQ 리스너·GoldenGate·sleep처럼 늘 ACTIVE인 대기형 세션이
            // 항상 잡혀 카드가 영원히 떠 있지 않게(2026-09-30 정밀 점검 반영). 정수 설정값만 문자열에 넣는다.
            String longCond = "last_call_et >= " + longRunningSeconds + " AND NOT (state = 'WAITING' AND wait_class = 'Idle')";
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(lockQueryTimeoutSeconds);
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*), " +
                        (longRunningSeconds > 0 ? "COUNT(CASE WHEN " + longCond + " THEN 1 END) " : "0 ") + userSessions)) {
                    if (rs.next()) {
                        s.activeSessions = rs.getInt(1);
                        s.longRunning = rs.getInt(2);
                    }
                }
            }
            if (s.longRunning > 0) {
                s.longRunningTop = longRunningTop(conn, target.id(), userSessions + " AND " + longCond);
            } else {
                longTopCache.remove(target.id());
            }
            s.memoryPct = memoryPct(conn, target.id());
            Double[] sm = sysmetric(conn, target.id());
            s.tps = sm[1];
            s.bufferHitPct = sm[2];
            s.holders = queryHolders(conn);
            resolveObjectNames(conn, target.id(), s.holders);
            evaluate(s, target.id());
            return s;
        }
    }

    /**
     * ① KPI "메모리 사용률"(2026-09-25 추가) - 기존 대시보드(MonitorService.getDashboardStats)와 같은 정의:
     * (SGA 합 + total PGA allocated) / PHYSICAL_MEMORY_BYTES × 100. 실패하면 null(판단 보류).
     */
    private Double memoryPct(Connection conn, String dbId) {
        double[] cached = memoryCache.get(dbId);
        long now = System.currentTimeMillis();
        if (cached != null && now - (long) cached[0] < MEMORY_CACHE_MS) {
            return cached[1];
        }
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(lockQueryTimeoutSeconds);
            double sga = 0;
            double pga = 0;
            double total = 0;
            try (ResultSet rs = st.executeQuery("SELECT SUM(bytes) FROM v$sgastat")) {
                if (rs.next()) sga = rs.getDouble(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT SUM(value) FROM v$pgastat WHERE name = 'total PGA allocated'")) {
                if (rs.next()) pga = rs.getDouble(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$osstat WHERE stat_name = 'PHYSICAL_MEMORY_BYTES'")) {
                if (rs.next()) total = rs.getDouble(1);
            }
            if (total <= 0) return null;
            double pct = Math.round(((sga + pga) / total) * 10000.0) / 100.0;
            memoryCache.put(dbId, new double[]{now, pct});
            return pct;
        } catch (SQLException e) {
            log.debug("memory usage lookup failed for {}: {}", dbId, e.toString());
            return cached != null ? cached[1] : null;
        }
    }

    /**
     * ① KPI TPS(User Transaction Per Sec)·Buffer Cache Hit Ratio - 기존 두 번째 대시보드(MonitorService의 status 조회)와
     * 같은 지표. 단 v$sysmetric은 같은 지표가 60초 그룹(group_id 2)과 15초 그룹(group_id 3)에 한 행씩 있고, 한가하면
     * 15초 그룹 Hit률이 0으로 나온다(2026-09-30 로컬 21c XE 실측) - 60초 그룹만 읽는다. 반환 {측정 시각, TPS, Hit%},
     * 값이 없거나 실패하면 해당 칸 null(판단 보류, 0으로 내리지 않음). 실패도 15초 캐시한다 - 예전엔 실패 때 오래된 캐시 값을
     * 현재 값처럼 돌려주고(몇 시간 전 값도 그대로), 시각을 안 남겨 3초마다 재시도하며 Lock 조회를 매번 늦췄다(2026-09-30 정밀 점검 반영).
     */
    private Double[] sysmetric(Connection conn, String dbId) {
        Double[] cached = sysmetricCache.get(dbId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached[0].longValue() < SYSMETRIC_CACHE_MS) {
            return cached;
        }
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(lockQueryTimeoutSeconds);
            Double tps = null;
            Double hit = null;
            try (ResultSet rs = st.executeQuery("SELECT metric_name, value FROM v$sysmetric WHERE group_id = 2 " +
                    "AND metric_name IN ('User Transaction Per Sec', 'Buffer Cache Hit Ratio')")) {
                while (rs.next()) {
                    double v = Math.round(rs.getDouble(2) * 100.0) / 100.0;
                    if ("User Transaction Per Sec".equals(rs.getString(1))) tps = v;
                    else hit = v;
                }
            }
            Double[] r = new Double[]{(double) now, tps, hit};
            sysmetricCache.put(dbId, r);
            return r;
        } catch (SQLException e) {
            log.debug("sysmetric lookup failed for {}: {}", dbId, e.toString());
            Double[] r = new Double[]{(double) now, null, null};
            sysmetricCache.put(dbId, r);
            return r;
        }
    }

    /**
     * 장시간 실행 쿼리 상위 5개(오래 돈 순) - 건수가 1 이상일 때만, DB별 15초에 한 번만 읽는다. 실패하면 null(화면은 건수만 보여 주고
     * "목록 조회 실패"), 조회는 됐는데 그사이 끝나 0행이면 빈 목록 - 둘을 구분한다. 목록 조회 실패로 Lock 스냅샷 전체를 판단 보류로
     * 만들지 않는다. where는 userSessions + 장시간 조건(정수 설정값만 들어간 문자열).
     */
    private List<Map<String, Object>> longRunningTop(Connection conn, String dbId, String where) {
        LongTop cached = longTopCache.get(dbId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at < LONG_TOP_CACHE_MS) {
            return cached.rows;
        }
        List<Map<String, Object>> result = queryLongRunningTop(conn, where);
        longTopCache.put(dbId, new LongTop(now, result));
        return result;
    }

    private List<Map<String, Object>> queryLongRunningTop(Connection conn, String where) {
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(lockQueryTimeoutSeconds);
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT * FROM (SELECT sid, serial#, username, sql_id, last_call_et, event, program " +
                    where + " ORDER BY last_call_et DESC) WHERE ROWNUM <= 5")) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("sid", rs.getLong("sid"));
                    r.put("serial", rs.getLong("serial#"));
                    r.put("username", rs.getString("username"));
                    r.put("sqlId", rs.getString("sql_id"));
                    r.put("elapsedSec", rs.getLong("last_call_et"));
                    r.put("event", rs.getString("event"));
                    r.put("program", rs.getString("program"));
                    rows.add(r);
                }
            }
            return rows;
        } catch (SQLException e) {
            log.debug("long-running query list failed: {}", e.toString());
            return null;
        }
    }

    /** TM Lock Holder - 세션 단위 1행(한 세션이 테이블 여러 개를 잡아도 1개로 센다), 필터 없음. */
    List<Map<String, Object>> queryHolders(Connection conn) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(lockQueryTimeoutSeconds);
            try (ResultSet rs = st.executeQuery(
                    // /*+ rule */은 getFailureProb()가 폐쇄망에서 검증한 방식 그대로(v$lock 조인이 느린 환경 대응).
                    "SELECT /*+ rule */ s.sid, s.serial#, MAX(s.type) AS session_type, MAX(s.username) AS username, " +
                            "MAX(s.program) AS program, MAX(s.machine) AS machine, MAX(s.status) AS status, " +
                            "MAX(s.last_call_et) AS last_call_et, MAX(s.blocking_session) AS blocking_session, " +
                            "MIN(l.id1) AS obj_id, COUNT(DISTINCT l.id1) AS obj_cnt, MAX(l.block) AS block, " +
                            // blocking_instance 조건: 다른 RAC 노드 세션에 막힌 대기를 같은 SID의 이 노드 세션이 막은 것으로 세지 않게
                            // (MonitorService.LOCAL_INSTANCE 주석 참고, 2026-09-29) - 장애 판정 수·KILL 패널이 부풀지 않도록.
                            "(SELECT COUNT(*) FROM v$session w WHERE w.blocking_session = s.sid " +
                            "AND w.blocking_instance = " + MonitorService.LOCAL_INSTANCE + ") AS waiters " +
                            "FROM v$lock l JOIN v$session s ON s.sid = l.sid " +
                            "WHERE l.type = 'TM' AND l.lmode > 0 " +
                            "GROUP BY s.sid, s.serial#")) {
                while (rs.next()) {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("sid", rs.getLong("sid"));
                    h.put("serial", rs.getLong("serial#"));
                    h.put("sessionType", rs.getString("session_type"));
                    h.put("username", rs.getString("username"));
                    h.put("program", rs.getString("program"));
                    h.put("machine", rs.getString("machine"));
                    h.put("status", rs.getString("status"));
                    h.put("lastCallEt", rs.getLong("last_call_et"));
                    Object blocking = rs.getObject("blocking_session");
                    h.put("blockingSession", blocking == null ? null : ((Number) blocking).longValue());
                    h.put("objId", rs.getLong("obj_id"));
                    h.put("objCnt", rs.getInt("obj_cnt"));
                    h.put("block", rs.getInt("block"));
                    h.put("waiters", rs.getInt("waiters"));
                    rows.add(h);
                }
            }
        }
        return rows;
    }

    private void resolveObjectNames(Connection conn, String dbId, List<Map<String, Object>> holders) {
        Map<Long, String> names = objectNames.computeIfAbsent(dbId, k -> new ConcurrentHashMap<>());
        Set<Long> unknown = new HashSet<>();
        for (Map<String, Object> h : holders) {
            Long id = (Long) h.get("objId");
            if (id != null && !names.containsKey(id)) unknown.add(id);
        }
        if (!unknown.isEmpty() && unknown.size() <= 500) {
            String in = String.join(",", Collections.nCopies(unknown.size(), "?"));
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT object_id, owner || '.' || object_name AS name FROM dba_objects WHERE object_id IN (" + in + ")")) {
                ps.setQueryTimeout(lockQueryTimeoutSeconds);
                int i = 1;
                for (Long id : unknown) ps.setLong(i++, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) names.put(rs.getLong(1), rs.getString(2));
                }
            } catch (SQLException e) {
                log.debug("TM holder object name lookup skipped: {}", e.toString());
            }
            if (names.size() > 5000) names.clear(); // 캐시 상한 - 객체가 계속 바뀌는 환경 대비
        }
        for (Map<String, Object> h : holders) {
            Long id = (Long) h.get("objId");
            String name = id == null ? null : names.get(id);
            int cnt = (Integer) h.get("objCnt");
            String label = name != null ? name : (id == null ? "-" : "OBJ#" + id);
            h.put("object", cnt > 1 ? label + " 외 " + (cnt - 1) + "개" : label);
        }
    }

    /** 한 번 조회한 Holder 목록을 장애 판정 수와 KILL 대상으로 나눈다. */
    private void evaluate(Snapshot s, String dbId) {
        // 장애 판정·KILL 대상 기준은 DB별로 설정할 수 있다 (환경설정 DB화 P3-1, 2026-10-04) - DB별 재정의 > 전역 > 기본값.
        int threshold = settings.getInt(SettingKey.TM_HOLDER_LAST_CALL_ET_SECONDS, dbId);
        int incidentCount = settings.getInt(SettingKey.TM_HOLDER_INCIDENT_COUNT, dbId);
        boolean killBlockingOnly = settings.getBool(SettingKey.TM_HOLDER_KILL_BLOCKING_ONLY, dbId);
        boolean killInactiveOnly = settings.getBool(SettingKey.TM_HOLDER_KILL_INACTIVE_ONLY, dbId);
        int over = 0;
        long maxEt = 0;
        List<Map<String, Object>> killTargets = new ArrayList<>();
        for (Map<String, Object> h : s.holders) {
            long et = (Long) h.get("lastCallEt");
            boolean isOver = et >= threshold && h.get("blockingSession") == null && (Integer) h.get("waiters") > 0;
            h.put("overThreshold", isOver);
            if (isOver) over++;
            if (isKillable(h, killBlockingOnly, killInactiveOnly)) {
                killTargets.add(h);
                // 최장 경과는 실제로 다른 세션을 막고 있는(대기 세션이 있는) Holder만 - 예전엔 KILL 대상 전체라, 아무도 막지 않는
                // 커밋 대기 세션 하나만 있어도 Lock 카드에 "최장 경과 … 위험"이 떴다(2026-09-30 QA). KILL 대상 범위는 그대로.
                if ((Integer) h.get("waiters") > 0) maxEt = Math.max(maxEt, et);
            }
        }
        s.holderOverCount = over;
        s.killTargets = killTargets;
        s.maxLastCallEt = maxEt;
        s.incident = over >= incidentCount;
    }

    /** KILL 대상: USER 세션만(백그라운드는 목록에도 넣지 않음), 설정이 켜져 있을 때만 더 좁힌다. */
    private boolean isKillable(Map<String, Object> h, boolean killBlockingOnly, boolean killInactiveOnly) {
        if (!"USER".equals(h.get("sessionType"))) return false;
        if (killBlockingOnly && (Integer) h.get("block") <= 0) return false;
        if (killInactiveOnly && !"INACTIVE".equals(h.get("status"))) return false;
        return true;
    }

    // ------------------------------------------------------------------ 추이 / 1분 요약

    private void record(String dbId, Snapshot s) {
        if (!s.ok) return;
        Deque<Map<String, Object>> h = history.computeIfAbsent(dbId, k -> new ArrayDeque<>());
        synchronized (h) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", s.at);
            p.put("tx", s.txWait);
            p.put("tm", s.tmWait);
            p.put("over", s.holderOverCount);
            p.put("incident", s.incident);
            h.addLast(p);
            while (!h.isEmpty() && (Long) h.peekFirst().get("t") < s.at - HISTORY_WINDOW_MS) h.removeFirst();
        }
        long minute = s.at - (s.at % 60000L);
        synchronized (minuteSummary) {
            MinuteSummary cur = minuteSummary.get(dbId);
            if (cur != null && cur.minute != minute) {
                saveSummary(dbId, cur);
                cur = null;
            }
            if (cur == null) {
                cur = new MinuteSummary(minute);
                minuteSummary.put(dbId, cur);
            }
            cur.tx = Math.max(cur.tx, s.txWait);
            cur.tm = Math.max(cur.tm, s.tmWait);
            cur.over = Math.max(cur.over, s.holderOverCount);
            cur.incident = cur.incident || s.incident;
        }
    }

    /**
     * 지난 분의 요약을 저장 - 요약은 "다음 조회에서 분이 바뀐 걸 알았을 때" 저장되므로, 화면을 닫으면 마지막 분(장애가
     * 있었던 분일 수 있음)이 남는다. 1분마다 끝난 분을 정리해 저장한다.
     */
    @Scheduled(fixedDelay = 60000L, initialDelay = 60000L)
    void flushFinishedMinutes() {
        long currentMinute = System.currentTimeMillis() - (System.currentTimeMillis() % 60000L);
        synchronized (minuteSummary) {
            for (Iterator<Map.Entry<String, MinuteSummary>> it = minuteSummary.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, MinuteSummary> e = it.next();
                if (e.getValue().minute < currentMinute) {
                    saveSummary(e.getKey(), e.getValue());
                    it.remove();
                }
            }
        }
    }

    private void saveSummary(String dbId, MinuteSummary m) {
        try {
            storeService.saveLockSample(dbId, m.minute, m.tx, m.tm, m.over, m.incident);
        } catch (Exception e) {
            log.debug("mon_lock_sample save failed for {}: {}", dbId, e.toString());
        }
    }

    /** 최근 10분 중 실시간 점이 없는 구간의 1분 샘플(tx/tm_lock_waiting) - {t, tx, tm}, 시각 오름차순. */
    private List<Map<String, Object>> fillPoints(String dbId, List<Map<String, Object>> live) {
        long now = System.currentTimeMillis();
        Object[] cached = fillCache.get(dbId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> coarse = cached != null && now - (Long) cached[0] < FILL_CACHE_MS
                ? (List<Map<String, Object>>) cached[1] : null;
        if (coarse == null) {
            coarse = new ArrayList<>();
            try {
                long from = now - HISTORY_WINDOW_MS - FILL_CACHE_MS; // 캐시 동안 창이 밀려도 앞쪽이 모자라지 않게
                Map<Long, Double> tm = new HashMap<>();
                for (Map<String, Object> row : historyService.query(dbId, "tm_lock_waiting", from, now)) {
                    tm.put((Long) row.get("sampledAt"), (Double) row.get("value"));
                }
                for (Map<String, Object> row : historyService.query(dbId, "tx_lock_waiting", from, now)) {
                    Long t = (Long) row.get("sampledAt");
                    Double tmv = tm.get(t);
                    if (tmv == null) continue;
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("t", t);
                    p.put("tx", ((Double) row.get("value")).intValue());
                    p.put("tm", tmv.intValue());
                    coarse.add(p);
                }
                coarse.sort((a, b) -> Long.compare((Long) a.get("t"), (Long) b.get("t")));
            } catch (Exception e) {
                log.debug("lock fill query failed for {}: {}", dbId, e.toString());
            }
            fillCache.put(dbId, new Object[]{now, coarse});
        }
        List<Map<String, Object>> out = new ArrayList<>();
        long windowStart = now - HISTORY_WINDOW_MS;
        int j = 0;
        for (Map<String, Object> p : coarse) {
            long t = (Long) p.get("t");
            if (t < windowStart) continue;
            // live는 시각 오름차순 - t 근처의 실시간 점만 본다
            while (j < live.size() && (Long) live.get(j).get("t") < t - FILL_NEAR_LIVE_MS) j++;
            boolean nearLive = j < live.size() && (Long) live.get(j).get("t") <= t + FILL_NEAR_LIVE_MS;
            if (!nearLive) out.add(p);
        }
        return out;
    }

    private Map<String, Object> toResponse(String dbId, Snapshot s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ts", s.at);
        r.put("ok", s.ok);
        if (!s.ok) {
            r.put("error", s.error);
        } else {
            r.put("activeSessions", s.activeSessions);
            r.put("memoryPct", s.memoryPct);
            r.put("tps", s.tps);
            r.put("bufferHitPct", s.bufferHitPct);
            r.put("longRunning", s.longRunning);
            r.put("longRunningSec", settings.getInt(SettingKey.LONG_RUNNING_QUERY_SECONDS, dbId));
            r.put("longRunningTop", s.longRunningTop);
            r.put("txWait", s.txWait);
            r.put("tmWait", s.tmWait);
            r.put("holderOverCount", s.holderOverCount);
            r.put("holderTotal", s.killTargets.size());
            r.put("maxLastCallEt", s.maxLastCallEt);
            r.put("incident", s.incident);
            List<Map<String, Object>> holders = new ArrayList<>();
            for (Map<String, Object> h : s.killTargets) {
                Map<String, Object> o = new LinkedHashMap<>(h);
                o.remove("objId");
                o.remove("sessionType");
                holders.add(o);
            }
            r.put("holders", holders);
        }
        // 화면이 색·문구에 쓰는 기준도 같은 DB의 유효값으로 내려보낸다 - 판정(서버)과 표시(화면)가 어긋나지 않게 (P3-1, 2026-10-04).
        r.put("lastCallEtThreshold", settings.getInt(SettingKey.TM_HOLDER_LAST_CALL_ET_SECONDS, dbId));
        r.put("incidentCount", settings.getInt(SettingKey.TM_HOLDER_INCIDENT_COUNT, dbId));
        r.put("holderWarnCount", settings.getInt(SettingKey.TM_HOLDER_WARN_COUNT, dbId));
        r.put("txWaitWarn", settings.getInt(SettingKey.LOCK_TX_WAIT_WARN, dbId));
        r.put("txWaitCrit", settings.getInt(SettingKey.LOCK_TX_WAIT_CRIT, dbId));
        r.put("tmWaitWarn", settings.getInt(SettingKey.LOCK_TM_WAIT_WARN, dbId));
        r.put("tmWaitCrit", settings.getInt(SettingKey.LOCK_TM_WAIT_CRIT, dbId));
        Deque<Map<String, Object>> h = history.get(dbId);
        List<Map<String, Object>> hist = new ArrayList<>();
        if (h != null) {
            synchronized (h) {
                hist.addAll(h);
            }
        }
        r.put("history", hist);
        r.put("fill", fillPoints(dbId, hist));
        Deque<Long> kills = killEvents.get(dbId);
        List<Long> killTs = new ArrayList<>();
        if (kills != null) {
            synchronized (kills) {
                for (Iterator<Long> it = kills.iterator(); it.hasNext(); ) {
                    Long t = it.next();
                    if (t < System.currentTimeMillis() - HISTORY_WINDOW_MS) it.remove();
                    else killTs.add(t);
                }
            }
        }
        r.put("killEvents", killTs);
        return r;
    }

    // ------------------------------------------------------------------ KILL

    /**
     * ③-1 장애 처리. 실행 직전에 Holder를 다시 조회해, 요청 대상 중 아직 있고 SERIAL#이 같은 USER 세션만 KILL한다.
     * 사라진 세션은 SKIPPED. 결과는 모두 mon_kill_audit에 남긴다. sid/serial은 호출자가 정수로 파싱해 넘긴다 -
     * ALTER SYSTEM은 바인드 변수를 못 쓰므로 정수 타입이 유일한 방어선이다.
     */
    public Map<String, Object> killTmHolders(TargetDbConfig target, String executedBy, List<long[]> requested) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        long now = System.currentTimeMillis();
        boolean anyAuditFailed = false;
        try (Connection conn = poolManager.getConnection(target)) {
            String instanceName = null;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT instance_name FROM v$instance")) {
                if (rs.next()) instanceName = rs.getString(1);
            }
            List<Map<String, Object>> holders = queryHolders(conn);
            resolveObjectNames(conn, target.id(), holders);
            Snapshot fresh = new Snapshot(now);
            fresh.holders = holders;
            evaluate(fresh, target.id());
            String reason = fresh.incident ? "TM_HOLDER_INCIDENT" : "MANUAL";

            Map<String, Map<String, Object>> current = new HashMap<>();
            for (Map<String, Object> h : holders) {
                if ("USER".equals(h.get("sessionType"))) {
                    current.put(h.get("sid") + ":" + h.get("serial"), h);
                }
            }
            for (long[] t : requested) {
                long sid = t[0];
                long serial = t[1];
                Map<String, Object> h = current.get(sid + ":" + serial);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("sid", sid);
                r.put("serial", serial);
                String result;
                String err = null;
                if (h == null) {
                    result = "SKIPPED"; // 이미 끝났거나 SERIAL#이 달라짐(다른 세션) 또는 USER가 아님
                } else {
                    try (Statement st = conn.createStatement()) {
                        st.execute("ALTER SYSTEM KILL SESSION '" + sid + "," + serial + "' IMMEDIATE");
                        result = "SUCCESS";
                    } catch (SQLException e) {
                        // ORA-00031(종료 표시)은 요청이 받아들여진 것이라 성공 + marked, ORA-00030(이미 없음)은 건너뜀으로 센다
                        // (2026-10-01 - 예전엔 둘 다 실패였다. 분류 기준은 기존 KILL과 같이 MonitorService에 둔다).
                        err = e.getMessage();
                        String status = MonitorService.killErrorStatus(e);
                        result = "marked".equals(status) ? "SUCCESS" : "skipped".equals(status) ? "SKIPPED" : "FAILED";
                        if ("marked".equals(status)) r.put("marked", true);
                        r.put("message", MonitorService.killErrorMessage(e));
                    }
                }
                r.put("result", result);
                if (err != null) r.put("error", err);
                results.add(r);
                // 감사 기록: 대상 DB(Oracle)와 감사 저장소가 달라 원자성을 보장할 수 없으므로, 한 번 재시도하고 그래도
                // 실패하면 응답에 표시해 운영자가 "KILL은 됐는데 감사 기록이 없다"를 알 수 있게 한다(security-reviewer 검토).
                boolean saved = false;
                for (int attempt = 1; attempt <= 2 && !saved; attempt++) {
                    try {
                        storeService.saveKillAudit(target.id(), now, executedBy, sid, serial, instanceName,
                                h == null ? null : (String) h.get("username"),
                                h == null ? null : (String) h.get("program"),
                                h == null ? null : (String) h.get("machine"),
                                h == null ? null : (String) h.get("object"),
                                h == null ? null : (Long) h.get("lastCallEt"),
                                reason, result, err);
                        saved = true;
                    } catch (Exception e) {
                        log.error("mon_kill_audit write failed (attempt {}, db_id={}, sid={}, serial={}, result={}, by={}): {}",
                                attempt, target.id(), sid, serial, result, executedBy, e.toString());
                    }
                }
                if (!saved) {
                    r.put("auditWriteFailed", true);
                    anyAuditFailed = true;
                }
            }
        } finally {
            // 재조회 등에서 예외가 나도 KILL 시도 표시와 캐시 무효화는 한다.
            Deque<Long> k = killEvents.computeIfAbsent(target.id(), key -> new ArrayDeque<>());
            synchronized (k) {
                k.addLast(now);
            }
            cache.remove(target.id()); // 다음 조회는 KILL 이후 상태로
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", now);
        out.put("results", results);
        if (anyAuditFailed) out.put("auditWriteFailed", true);
        return out;
    }

    // ------------------------------------------------------------------ 내부 타입

    /** 장시간 실행 쿼리 상위 목록 캐시 한 칸 - rows가 null이면 조회 실패(그것도 15초 동안 재사용해 재시도를 늦춘다). */
    private static final class LongTop {
        final long at;
        final List<Map<String, Object>> rows;

        LongTop(long at, List<Map<String, Object>> rows) {
            this.at = at;
            this.rows = rows;
        }
    }

    private static final class Snapshot {
        final long at;
        boolean ok = true;
        String error;
        int activeSessions;
        Double memoryPct;
        Double tps;
        Double bufferHitPct;
        int longRunning;
        List<Map<String, Object>> longRunningTop;
        int txWait;
        int tmWait;
        int holderOverCount;
        long maxLastCallEt;
        boolean incident;
        List<Map<String, Object>> holders = new ArrayList<>();
        List<Map<String, Object>> killTargets = new ArrayList<>();

        Snapshot(long at) {
            this.at = at;
        }

        static Snapshot failed(long at, Throwable e) {
            Snapshot s = new Snapshot(at);
            s.ok = false;
            s.error = e.getMessage() != null ? e.getMessage() : e.toString();
            return s;
        }
    }

    /** A안 백그라운드 수집 대상 - 마지막으로 본 시각과 연속 실패 상태. fails/pausedUntil은 조회 완료 스레드에서만 바뀐다. */
    private static final class Viewed {
        volatile TargetDbConfig target;
        volatile long lastViewedAt;
        volatile int fails;
        volatile long pausedUntil;
    }

    private static final class MinuteSummary {
        final long minute;
        int tx;
        int tm;
        int over;
        boolean incident;

        MinuteSummary(long minute) {
            this.minute = minute;
        }
    }
}
