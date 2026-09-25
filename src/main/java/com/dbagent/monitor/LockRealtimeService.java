package com.dbagent.monitor;

import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
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

    private static final long HISTORY_WINDOW_MS = 10 * 60 * 1000L;

    private final OracleConnectionPoolManager poolManager;
    private final MonitorService monitorService;
    private final MonitorStoreService storeService;

    @Value("${dbagent.monitor.lock-query-timeout-seconds:3}")
    private int lockQueryTimeoutSeconds;

    @Value("${dbagent.monitor.lock-cache-millis:2000}")
    private long cacheMillis;

    @Value("${dbagent.monitor.tm-holder-incident-count:6}")
    private int incidentCount;

    @Value("${dbagent.monitor.tm-holder-kill-blocking-only:false}")
    private boolean killBlockingOnly;

    @Value("${dbagent.monitor.tm-holder-kill-inactive-only:false}")
    private boolean killInactiveOnly;

    @Value("${dbagent.monitor.lock-tx-row-lock-only:false}")
    private boolean txRowLockOnly;

    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Snapshot>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Deque<Map<String, Object>>> history = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> killEvents = new ConcurrentHashMap<>();
    private final Map<String, MinuteSummary> minuteSummary = new ConcurrentHashMap<>();
    /** ① KPI 메모리 사용률 - 천천히 변하는 값이라 DB별 60초 캐시(3초 주기마다 v$sgastat 등을 읽지 않음). */
    private static final long MEMORY_CACHE_MS = 60000L;
    private final Map<String, double[]> memoryCache = new ConcurrentHashMap<>(); // {측정 시각, 사용률%}

    /** DB별 object_id → OWNER.NAME. 매 주기 dba_objects 조인 금지(11g 무응답 전례) - 모르는 id만 한 번 조회. */
    private final Map<String, Map<Long, String>> objectNames = new ConcurrentHashMap<>();

    public LockRealtimeService(OracleConnectionPoolManager poolManager, MonitorService monitorService,
                               MonitorStoreService storeService) {
        this.poolManager = poolManager;
        this.monitorService = monitorService;
        this.storeService = storeService;
    }

    // ------------------------------------------------------------------ 조회

    /** 실시간 응답 - 캐시(2초) 또는 진행 중 조회를 공유한다. */
    public Map<String, Object> realtime(TargetDbConfig target) {
        Snapshot cached = cache.get(target.id());
        long now = System.currentTimeMillis();
        Snapshot snap;
        if (cached != null && now - cached.at < cacheMillis) {
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
        return inFlight.computeIfAbsent(target.id(), id -> {
            CompletableFuture<Snapshot> f = CompletableFuture.supplyAsync(() -> {
                Snapshot s;
                try {
                    s = query(target);
                } catch (Exception e) {
                    s = Snapshot.failed(System.currentTimeMillis(), e);
                }
                cache.put(id, s);
                record(id, s);
                return s;
            }, LOCK_EXECUTOR);
            f.whenComplete((r, e) -> inFlight.remove(id, f));
            return f;
        });
    }

    private Snapshot query(TargetDbConfig target) throws SQLException {
        try (Connection conn = poolManager.getConnection(target)) {
            Snapshot s = new Snapshot(System.currentTimeMillis());
            String txCond = txRowLockOnly ? "event = 'enq: TX - row lock contention'" : "event LIKE 'enq: TX%'";
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
            // 같은 커넥션·같은 캐시를 타므로 원본 DB 조회 횟수는 늘지 않는다.
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(lockQueryTimeoutSeconds);
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM v$session WHERE status = 'ACTIVE' AND type != 'BACKGROUND' " +
                        "AND username IS NOT NULL AND username != '" + target.user().toUpperCase().replace("'", "''") + "'")) {
                    s.activeSessions = rs.next() ? rs.getInt(1) : 0;
                }
            }
            s.memoryPct = memoryPct(conn, target.id());
            s.holders = queryHolders(conn);
            resolveObjectNames(conn, target.id(), s.holders);
            evaluate(s);
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
                            "(SELECT COUNT(*) FROM v$session w WHERE w.blocking_session = s.sid) AS waiters " +
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
    private void evaluate(Snapshot s) {
        int threshold = monitorService.getTmHolderLastCallEtSeconds();
        int over = 0;
        long maxEt = 0;
        List<Map<String, Object>> killTargets = new ArrayList<>();
        for (Map<String, Object> h : s.holders) {
            long et = (Long) h.get("lastCallEt");
            boolean isOver = et >= threshold && h.get("blockingSession") == null && (Integer) h.get("waiters") > 0;
            h.put("overThreshold", isOver);
            if (isOver) over++;
            if (isKillable(h)) {
                killTargets.add(h);
                maxEt = Math.max(maxEt, et);
            }
        }
        s.holderOverCount = over;
        s.killTargets = killTargets;
        s.maxLastCallEt = maxEt;
        s.incident = over >= incidentCount;
    }

    /** KILL 대상: USER 세션만(백그라운드는 목록에도 넣지 않음), 설정이 켜져 있을 때만 더 좁힌다. */
    private boolean isKillable(Map<String, Object> h) {
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

    private Map<String, Object> toResponse(String dbId, Snapshot s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ts", s.at);
        r.put("ok", s.ok);
        if (!s.ok) {
            r.put("error", s.error);
        } else {
            r.put("activeSessions", s.activeSessions);
            r.put("memoryPct", s.memoryPct);
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
        r.put("lastCallEtThreshold", monitorService.getTmHolderLastCallEtSeconds());
        r.put("incidentCount", incidentCount);
        Deque<Map<String, Object>> h = history.get(dbId);
        List<Map<String, Object>> hist = new ArrayList<>();
        if (h != null) {
            synchronized (h) {
                hist.addAll(h);
            }
        }
        r.put("history", hist);
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
            evaluate(fresh);
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
                        result = "FAILED";
                        err = e.getMessage();
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

    private static final class Snapshot {
        final long at;
        boolean ok = true;
        String error;
        int activeSessions;
        Double memoryPct;
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
