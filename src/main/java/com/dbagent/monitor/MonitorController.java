package com.dbagent.monitor;

import com.dbagent.auth.AuthService;
import com.dbagent.util.Lists;
import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.rdb.CubridMonitorService;
import com.dbagent.rdb.MsSqlMonitorService;
import com.dbagent.rdb.MySqlMonitorService;
import com.dbagent.rdb.PostgresMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class MonitorController {

    private static final Logger log = LoggerFactory.getLogger(MonitorController.class);

    private final MonitorService monitorService;
    private final DatabaseConfigService configService;
    private final AuthService authService;
    private final MySqlMonitorService mySqlMonitorService;
    private final PostgresMonitorService postgresMonitorService;
    private final MsSqlMonitorService msSqlMonitorService;
    private final CubridMonitorService cubridMonitorService;
    private final InstanceMetricHistoryService metricHistoryService;

    public MonitorController(MonitorService monitorService, DatabaseConfigService configService, AuthService authService,
            MySqlMonitorService mySqlMonitorService, PostgresMonitorService postgresMonitorService,
            MsSqlMonitorService msSqlMonitorService, CubridMonitorService cubridMonitorService,
            InstanceMetricHistoryService metricHistoryService) {
        this.monitorService = monitorService;
        this.configService = configService;
        this.authService = authService;
        this.mySqlMonitorService = mySqlMonitorService;
        this.postgresMonitorService = postgresMonitorService;
        this.msSqlMonitorService = msSqlMonitorService;
        this.cubridMonitorService = cubridMonitorService;
        this.metricHistoryService = metricHistoryService;
    }

    // 대시보드 CpuDbTimeLineChart/LockTrendChart용 - InstanceMetricSamplerService가 쌓아 둔
    // instance_metric_history를 그대로 내려준다. 지원 range: "1h"(기본)/"24h"/"7d".
    @GetMapping("/metric_history")
    public ResponseEntity<Object> metricHistory(
            @RequestParam(required = false) String db_id,
            @RequestParam(required = false) String token,
            @RequestParam(defaultValue = "1h") String range,
            @RequestParam(required = false) String metrics) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        long toMillis = System.currentTimeMillis();
        long fromMillis = toMillis - metricHistoryRangeMillis(range);
        List<String> metricNames = Strings.isBlank(metrics)
                ? Lists.of("cpu_pct", "db_time_aas", "tm_lock_waiting", "tx_lock_waiting")
                : Arrays.stream(metrics.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        for (String metricName : metricNames) {
            result.put(metricName, metricHistoryService.query(target.id(), metricName, fromMillis, toMillis));
        }
        return ResponseEntity.ok(result);
    }

    private long metricHistoryRangeMillis(String range) {
        // "6h"는 Active Session Wait Class 차트 6단계(설계문서 §0 결정, 자체 수집 경로 -
        // 2026-09-22)가 추가 - 기존 1h/24h/7d(v2 CpuDbTimeLineChart/LockTrendChart)는 그대로.
        switch (range) {
            case "6h": return TimeUnit.HOURS.toMillis(6);
            case "24h": return TimeUnit.HOURS.toMillis(24);
            case "7d": return TimeUnit.DAYS.toMillis(7);
            default: return TimeUnit.HOURS.toMillis(1);
        }
    }

    @GetMapping("/tmlock")
    public ResponseEntity<Object> tmLock(@RequestParam(required = false) String db_id,
                                          @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getTmLocks(target));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/erd/schema")
    public ResponseEntity<Object> erdSchema(
            @RequestParam(required = false) String db_id,
            @RequestParam(required = false) String token,
            @RequestParam(defaultValue = "KIPOADM") String owner,
            @RequestParam(defaultValue = "") String prefix) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getErdSchema(
                    target, owner.toUpperCase(), prefix.toUpperCase()));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/session")
    public ResponseEntity<Object> sessions(@RequestParam(required = false) String db_id,
                                            @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getSessions(target));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/session_extra")
    public ResponseEntity<Object> sessionExtra(@RequestParam(required = false) String db_id,
                                                @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getSessionExtra(target));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    // "Current Session" 화면 Active Session Wait Class 차트(설계문서 `Current Session 매뉴
    // active_session 차트 개편.md` 1단계, 2026-09-22 - 원본 DBAgent-Java에서 포팅) - 30분/1시간만
    // 지원. 6시간/24시간(AWR 소스)은 문서 §0 결정에 따라 이후 단계(6단계)에서 추가 예정이라 여기서는
    // 명시적으로 거부한다.
    @GetMapping("/ash_activity")
    public ResponseEntity<Object> ashActivity(@RequestParam(required = false) String db_id,
                                               @RequestParam(required = false) String token,
                                               @RequestParam(name = "range_minutes", required = false, defaultValue = "60") int rangeMinutes,
                                               @RequestParam(name = "step_minutes", required = false, defaultValue = "1") int stepMinutes) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        if (rangeMinutes != 30 && rangeMinutes != 60) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "range_minutes must be 30 or 60 (1단계 구현 범위)"));
        }
        if (stepMinutes != 1) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "step_minutes must be 1 (1단계 구현 범위)"));
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getAshActivity(target, rangeMinutes, stepMinutes));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    // Top SQL Activity Timeline(설계문서 §8, 3단계 - 2026-09-22) - ash_activity와 같은 range/step 제약.
    @GetMapping("/ash_top_sql")
    public ResponseEntity<Object> ashTopSql(@RequestParam(required = false) String db_id,
                                             @RequestParam(required = false) String token,
                                             @RequestParam(name = "range_minutes", required = false, defaultValue = "60") int rangeMinutes,
                                             @RequestParam(name = "step_minutes", required = false, defaultValue = "1") int stepMinutes) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        if (rangeMinutes != 30 && rangeMinutes != 60) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "range_minutes must be 30 or 60 (1단계 구현 범위)"));
        }
        if (stepMinutes != 1) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "step_minutes must be 1 (1단계 구현 범위)"));
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getAshTopSql(target, rangeMinutes, stepMinutes));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/tablespace")
    public ResponseEntity<Object> tablespaces(@RequestParam(required = false) String db_id,
                                               @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getTablespaces(target));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/tablespace_datafiles")
    public ResponseEntity<Object> tablespaceDatafiles(
            @RequestParam(required = false) String db_id,
            @RequestParam(required = false) String token,
            @RequestParam(name = "tablespace_name", defaultValue = "") String tablespaceName) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getTablespaceDatafiles(target, tablespaceName.toUpperCase()));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/dashboard")
    public ResponseEntity<Object> dashboard(@RequestParam(required = false) String db_id,
                                             @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getDashboardStats(target));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    // Not the default ForkJoinPool.commonPool() - its parallelism defaults to (CPU cores - 1), which
    // on a modest server can be far smaller than the number of configured DB instances, so most
    // instances would queue behind each other instead of actually running concurrently (사용자
    // 피드백: 11개 DB 조회가 너무 느림). Cached so it costs nothing when idle and scales with however
    // many instances/concurrent requests actually show up.
    private static final ExecutorService FLEET_STATUS_EXECUTOR = Executors.newCachedThreadPool();

    // Backs withTimeout() below - a single daemon-ish scheduler is enough since it only ever holds a
    // cheap "fire a timeout" callback per in-flight fleet_status call, never any real work.
    private static final ScheduledExecutorService FLEET_STATUS_TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    // Hard ceiling on top of MonitorService.getFleetStatus()'s own connect-timeout-bounded query: a
    // safety net for cases the pool-level timeout doesn't cover (DNS resolution hanging before the
    // socket connect even starts, for one - 사용자가 겪은 ORA-17002 사례에서, 막힌 DB 하나가 전체
    // fleet_status 응답을 계속 물고 있는 것처럼 보였던 문제). Past this bound the instance just
    // reports "down" instead of holding up every other instance's already-ready result.
    @Value("${dbagent.fleet-status.timeout-seconds:5}")
    private long fleetStatusTimeoutSeconds;

    // Java 8 has no CompletableFuture.orTimeout() (added in 9) - this is the manual equivalent: race
    // a *derived view* of the source future against a scheduled failure, whichever completes first
    // wins. Deliberately not completing `source` itself here - `source` may be the shared in-flight
    // future every concurrent poll for a db_id is watching (see fleetStatusFor()/fleetStatusInFlight
    // below). If one poll's timeout completed the shared future directly, it would look "done" to
    // fleetStatusInFlight and get evicted while the real query is still running underneath, defeating
    // the de-dupe entirely (the very next poll would just start a second overlapping query again).
    private static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> source, long timeout, TimeUnit unit) {
        CompletableFuture<T> view = new CompletableFuture<>();
        java.util.concurrent.ScheduledFuture<?> timeoutTask = FLEET_STATUS_TIMEOUT_SCHEDULER.schedule(
                () -> view.completeExceptionally(new TimeoutException("timed out after " + timeout + " " + unit)),
                timeout, unit);
        source.whenComplete((value, error) -> {
            timeoutTask.cancel(false);
            if (error != null) {
                view.completeExceptionally(error);
            } else {
                view.complete(value);
            }
        });
        return view;
    }

    // De-dupes overlapping polls per DB: the timeout above only makes the *caller* stop waiting - it
    // doesn't cancel the underlying query, which keeps running against the real DB. If a slow instance
    // takes longer than one polling interval to answer, the next poll used to fire a whole new
    // getFleetStatus() call on top of the still-running one, and the one after that on top of that -
    // every cycle stacking another copy of the same query (사용자가 DB 쪽에서 직접 확인:
    // queryLockWaitCount()의 v$lock 카운트 쿼리가 계속 쌓이는 게 보임). Now every poll for a given
    // db_id just attaches to whatever's already in flight instead of starting a second one, so at most
    // one real query per instance is ever running at a time no matter how many polls land while it's
    // still slow.
    private final Map<String, CompletableFuture<Map<String, Object>>> fleetStatusInFlight = new ConcurrentHashMap<>();

    private CompletableFuture<Map<String, Object>> fleetStatusFor(TargetDbConfig inst) {
        return fleetStatusInFlight.computeIfAbsent(inst.id(), id -> {
            CompletableFuture<Map<String, Object>> f =
                    CompletableFuture.supplyAsync(() -> fleetStatusDispatch(inst), FLEET_STATUS_EXECUTOR);
            f.whenComplete((result, error) -> fleetStatusInFlight.remove(id, f));
            return f;
        });
    }

    // Only seam that's engine-aware - every other piece of fleet_status orchestration (executor,
    // in-flight de-dupe, timeout wrapping, per-instance auth filtering below) stays untouched.
    private Map<String, Object> fleetStatusDispatch(TargetDbConfig inst) {
        String dbType = inst.dbType();
        if (dbType == null || "oracle".equals(dbType)) {
            return monitorService.getFleetStatus(inst);
        }
        if ("postgres".equals(dbType)) {
            return postgresMonitorService.getFleetStatus(inst);
        }
        if ("mssql".equals(dbType)) {
            return msSqlMonitorService.getFleetStatus(inst);
        }
        if ("cubrid".equals(dbType)) {
            return cubridMonitorService.getFleetStatus(inst);
        }
        return mySqlMonitorService.getFleetStatus(inst);
    }

    // Fleet Overview (fleet-overview.html): one status snapshot per configured instance.
    // Checked concurrently, not in a loop - a single down/slow DB would otherwise stall every
    // instance queued after it. Respects the same per-account DB visibility as everywhere else
    // (canAccessDb) - an instance the caller can't see just doesn't appear in the response, same as
    // it wouldn't in the sidebar.
    @GetMapping("/fleet_status")
    public ResponseEntity<Object> fleetStatus(@RequestParam(required = false) String token) {
        if (!authService.canAccessFleetOverview(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Maps.of("error", "Fleet Overview 접근 권한이 없습니다."));
        }
        List<CompletableFuture<Map<String, Object>>> futures = configService.listAllInstances().stream()
                .filter(inst -> authService.canAccessDb(token, inst.id()))
                .map(inst -> withTimeout(fleetStatusFor(inst), fleetStatusTimeoutSeconds, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            // withTimeout()의 view는 직접 completeExceptionally()로 완료되므로 보통 원본
                            // 예외를 그대로 받지만, source 쪽 체인 상황에 따라 CompletionException으로
                            // 감싸져 올 수도 있어 한 겹 벗겨서 실제 원인으로 타임아웃 여부를 판단한다 -
                            // 이전엔 모든 실패를 "timed out"으로 로깅/응답해서 실제 원인(DB 예외 등)이
                            // 가려졌었다.
                            Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
                            boolean isTimeout = cause instanceof TimeoutException;
                            if (isTimeout) {
                                log.warn("fleet_status timed out after {}s for db_id={}: {}",
                                        fleetStatusTimeoutSeconds, inst.id(), cause.toString());
                            } else {
                                log.warn("fleet_status failed for db_id={}: {}", inst.id(), cause.toString());
                            }
                            Map<String, Object> fallback = new LinkedHashMap<>();
                            fallback.put("id", inst.id());
                            fallback.put("status", "down");
                            fallback.put("errorMessage", isTimeout ? "상태 조회 시간 초과" : "상태 조회 실패");
                            return fallback;
                        }))
                .collect(Collectors.toList());
        List<Map<String, Object>> results = new ArrayList<>(futures.size());
        for (CompletableFuture<Map<String, Object>> f : futures) {
            results.add(f.join());
        }
        return ResponseEntity.ok(results);
    }

    @GetMapping("/top_events")
    public ResponseEntity<Object> topEvents(@RequestParam(required = false) String db_id,
                                             @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getTopEvents(target));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    // ---- dashboard v2 (oracle-instance-dashboard-spec.md, 2026-09-16) ----

    @GetMapping("/instance_overview")
    public ResponseEntity<Object> instanceOverview(@RequestParam(required = false) String db_id,
                                                     @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getInstanceOverview(target));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    // fleet_status와 같은 이유로 같은 패턴(withTimeout + 전용 executor + db_id별 in-flight dedupe)을
    // 쓴다 - 오케스트레이터 실측(2026-09-22, 11g 인스턴스 patent_integ_1/2): getActiveAlerts() 내부
    // v$lock/dba_objects 조인 쿼리가 락 대기로 블로킹되면 이미 걸려 있던 Statement.setQueryTimeout()도
    // 취소를 못 시킨다(SQL*Plus로 직접 돌려도 응답 없음 확인) - JDBC 레벨 쿼리 타임아웃은 세션이 CPU를
    // 쓰며 도는 동안만 신뢰할 수 있고, 락 대기로 멈춰있으면 취소 신호 자체가 처리 안 된다. v2가 10초
    // 간격으로 폴링하는데 이 하나가 안 풀리면 iv2FetchInFlightDbIds 가드가 영원히 안 풀려 그 DB 전체
    // 화면이 먹통된다 - 그래서 쿼리를 못 끊는 대신 "호출자가 기다리는 것"만이라도 끊는다.
    private static final ExecutorService ACTIVE_ALERTS_EXECUTOR = Executors.newCachedThreadPool();

    @Value("${dbagent.monitor.active-alerts-timeout-seconds:5}")
    private long activeAlertsTimeoutSeconds;

    private final Map<String, CompletableFuture<List<Map<String, Object>>>> activeAlertsInFlight = new ConcurrentHashMap<>();

    private CompletableFuture<List<Map<String, Object>>> activeAlertsFor(TargetDbConfig target) {
        return activeAlertsInFlight.computeIfAbsent(target.id(), id -> {
            CompletableFuture<List<Map<String, Object>>> f = CompletableFuture.supplyAsync(() -> {
                try {
                    return monitorService.getActiveAlerts(target);
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            }, ACTIVE_ALERTS_EXECUTOR);
            f.whenComplete((result, error) -> activeAlertsInFlight.remove(id, f));
            return f;
        });
    }

    @GetMapping("/active_alerts")
    public ResponseEntity<Object> activeAlerts(@RequestParam(required = false) String db_id,
                                                @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            List<Map<String, Object>> alerts =
                    withTimeout(activeAlertsFor(target), activeAlertsTimeoutSeconds, TimeUnit.SECONDS).join();
            return ResponseEntity.ok(alerts);
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof TimeoutException) {
                // getActiveAlerts() 내부의 개별 쿼리 3개도 각자 실패하면 그 알림만 조용히 생략하는
                // 정책이다(코드 내 catch(SQLException ignored) 참고) - 여기서도 같은 정책을 outer
                // 레벨에서 한 번 더 적용: "못 걷었다"를 "알림 없음"으로 접는다. 안 풀린 쿼리 자체는
                // ACTIVE_ALERTS_EXECUTOR 스레드에서 계속 돌다가 나중에 끝나든 안 끝나든 다음 폴링과는
                // 무관하다(활성 alert dedupe map에서 이미 빠짐).
                log.warn("active_alerts timed out after {}s for db_id={}", activeAlertsTimeoutSeconds, db_id);
                return ResponseEntity.ok(Lists.of());
            }
            if (cause instanceof SQLException) {
                return dbError((SQLException) cause);
            }
            throw ex;
        }
    }

    @PostMapping("/kill_session")
    public ResponseEntity<Object> killSession(
            @RequestParam(required = false) String db_id,
            @RequestBody KillSessionRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Maps.of("error", "세션 Kill 권한이 없습니다."));
        }
        if (req.sessions() == null || req.sessions().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "No sessions provided"));
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            List<Map<String, Object>> results = monitorService.killSessions(target, req.sessions());
            return ResponseEntity.ok(Maps.of("results", results));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/relation")
    public ResponseEntity<Object> relation(
            @RequestParam(required = false) String db_id,
            @RequestParam(required = false) String token,
            @RequestParam(name = "table_name", defaultValue = "") String tableName,
            @RequestParam(defaultValue = "bi") String direction) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getRelation(target, tableName.toUpperCase(), direction));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/session_query")
    public ResponseEntity<Object> sessionQuery(
            @RequestParam(required = false) String db_id,
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String serial,
            @RequestParam(name = "sql_id", required = false) String sqlId) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        if ((sid == null || Strings.isBlank(sid)) && (sqlId == null || Strings.isBlank(sqlId))) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "SID or SQL_ID is required"));
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getSessionQuery(target, sid, serial, sqlId));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/table_info")
    public ResponseEntity<Object> tableInfo(
            @RequestParam(required = false) String db_id,
            @RequestParam(required = false) String token,
            @RequestParam(name = "table_name", defaultValue = "") String tableName) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getTableInfo(target, tableName.toUpperCase()));
        } catch (SQLException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Maps.of("error", e.getMessage()));
        }
    }

    // active_alerts와 같은 이유(같은 v$lock 조인 패턴, 같은 Promise.all에 걸린 v2 위젯)로 같은 패턴을
    // 쓴다 - dba_objects join은 제거했지만(MonitorService.getFailureProb), 다른 환경에서 또 다른 원인
    // 으로 느려질 가능성까지 막으려면 호출자 쪽 타임아웃도 필요하다.
    private static final ExecutorService FAILURE_PROB_EXECUTOR = Executors.newCachedThreadPool();

    private final Map<String, CompletableFuture<Map<String, Object>>> failureProbInFlight = new ConcurrentHashMap<>();

    private CompletableFuture<Map<String, Object>> failureProbFor(TargetDbConfig target) {
        return failureProbInFlight.computeIfAbsent(target.id(), id -> {
            CompletableFuture<Map<String, Object>> f = CompletableFuture.supplyAsync(() -> {
                try {
                    return monitorService.getFailureProb(target);
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            }, FAILURE_PROB_EXECUTOR);
            f.whenComplete((result, error) -> failureProbInFlight.remove(id, f));
            return f;
        });
    }

    @GetMapping("/failure_prob")
    public ResponseEntity<Object> failureProb(@RequestParam(required = false) String db_id,
                                               @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            Map<String, Object> result =
                    withTimeout(failureProbFor(target), activeAlertsTimeoutSeconds, TimeUnit.SECONDS).join();
            return ResponseEntity.ok(result);
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof TimeoutException) {
                // 장애조치 버튼은 "이상 없음"보다 "판단 보류"가 안전하다 - count=0으로 응답하면 실제
                // 장애가 있어도 버튼이 숨어 놓친 것처럼 보인다. !res.ok 경로(app.js fetchIncidentGateV2)를
                // 타게 해서 버튼을 숨기고 다음 폴링에서 다시 시도하게 한다.
                log.warn("failure_prob timed out after {}s for db_id={}", activeAlertsTimeoutSeconds, db_id);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Maps.of("error", "조회 시간 초과"));
            }
            if (cause instanceof SQLException) {
                return dbError((SQLException) cause);
            }
            throw ex;
        }
    }

    @GetMapping("/history_sessions")
    public ResponseEntity<Object> historySessions(
            @RequestParam(required = false) String db_id,
            @RequestParam(required = false) String token,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(required = false) String users,
            @RequestParam(required = false) String machines) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        if (startTime == null || endTime == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "start_time and end_time are required"));
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getHistorySessions(target, startTime, endTime, users, machines));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    // Top SQL Activity Timeline 드래그→세션 상세(설계문서 §8.4, 4단계 - 2026-09-22). getHistorySessions와
    // 달리 튜닝 후보 필터(elapsed>=3초, exec_count>=100)가 없다 - 왜 재사용하지 않았는지는
    // MonitorService.getAshSessionDetail() 주석 참고.
    @GetMapping("/ash_session_detail")
    public ResponseEntity<Object> ashSessionDetail(
            @RequestParam(required = false) String db_id,
            @RequestParam(required = false) String token,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        if (startTime == null || endTime == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "start_time and end_time are required"));
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getAshSessionDetail(target, startTime, endTime));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    // "Other" 드릴다운(설계문서 §9, 5단계 - 2026-09-22) - exclude_sql_ids는 프론트가 이미 들고 있는
    // Top SQL Activity Timeline의 sql_categories 목록을 그대로 콤마로 이어 붙여 보낸다(§9.2 - 서버가
    // Top5를 다시 산정하지 않고 프론트와 같은 기준을 그대로 씀). 0~5개 아무 길이나 허용.
    @GetMapping("/ash_other_breakdown")
    public ResponseEntity<Object> ashOtherBreakdown(
            @RequestParam(required = false) String db_id,
            @RequestParam(required = false) String token,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(name = "exclude_sql_ids", required = false) String excludeSqlIds) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        if (startTime == null || endTime == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "start_time and end_time are required"));
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        List<String> excludeList = new ArrayList<>();
        if (excludeSqlIds != null && !Strings.isBlank(excludeSqlIds)) {
            for (String id : excludeSqlIds.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) excludeList.add(trimmed);
            }
        }
        try {
            return ResponseEntity.ok(monitorService.getAshOtherBreakdown(target, startTime, endTime, excludeList));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Object> health(@RequestParam(required = false) String db_id,
                                          @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        return ResponseEntity.ok(monitorService.getHealth(target));
    }

    @GetMapping("/history_top_sessions")
    public ResponseEntity<Object> historyTopSessions(
            @RequestParam(required = false) String db_id,
            @RequestParam(required = false) String token,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(required = false) String users,
            @RequestParam(required = false) String machines) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        if (startTime == null || endTime == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "start_time and end_time are required"));
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getHistoryTopSessions(target, startTime, endTime, users, machines));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/history_machines")
    public ResponseEntity<Object> historyMachines(@RequestParam(required = false) String db_id,
                                                    @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getHistoryMachines(target));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/history_users")
    public ResponseEntity<Object> historyUsers(@RequestParam(required = false) String db_id,
                                                @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getHistoryUsers(target));
        } catch (SQLException e) {
            return ResponseEntity.ok(Maps.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Object> dbError(SQLException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Maps.of("error", e.getMessage()));
    }

    private ResponseEntity<Object> dbAccessDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Maps.of("error", "해당 DB에 대한 접근 권한이 없습니다."));
    }

    private ResponseEntity<Object> dbNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Maps.of("error", "등록되지 않은 DB입니다."));
    }
}
