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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final InstanceMetricSamplerService metricSamplerService;

    public MonitorController(MonitorService monitorService, DatabaseConfigService configService, AuthService authService,
            MySqlMonitorService mySqlMonitorService, PostgresMonitorService postgresMonitorService,
            MsSqlMonitorService msSqlMonitorService, CubridMonitorService cubridMonitorService,
            InstanceMetricHistoryService metricHistoryService, InstanceMetricSamplerService metricSamplerService) {
        this.monitorService = monitorService;
        this.configService = configService;
        this.authService = authService;
        this.mySqlMonitorService = mySqlMonitorService;
        this.postgresMonitorService = postgresMonitorService;
        this.msSqlMonitorService = msSqlMonitorService;
        this.cubridMonitorService = cubridMonitorService;
        this.metricHistoryService = metricHistoryService;
        this.metricSamplerService = metricSamplerService;
    }

    // 대시보드 CpuDbTimeLineChart/LockTrendChart용 - InstanceMetricSamplerService가 쌓아 둔
    // instance_metric_history를 그대로 내려준다. 지원 range: "15m"/"30m"/"1h"(기본)/"3h"/"6h"/"24h"/"7d".
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
        // C4(2026-09-25): "DB SYSDATE - 앱 시각"(ms). sampledAt은 앱 시각이라, 화면이 DB 시각 기준 조회
        // (Top SQL 등)와 구간을 맞출 때 쓴다. 아직 샘플링 전이면 null.
        result.put("dbClockOffsetMs", metricSamplerService.getDbClockOffsetMs(target.id()));
        return ResponseEntity.ok(result);
    }

    private long metricHistoryRangeMillis(String range) {
        // "6h"는 Active Session Wait Class 차트 6단계(설계문서 §0 결정, 자체 수집 경로 -
        // 2026-09-22)가 추가 - 기존 1h/24h/7d(v2 CpuDbTimeLineChart/LockTrendChart)는 그대로.
        switch (range) {
            // 15m/30m/3h: Current Session 차트를 저장값으로 그리면서(C5, 2026-09-25) 추가.
            case "15m": return TimeUnit.MINUTES.toMillis(15);
            case "30m": return TimeUnit.MINUTES.toMillis(30);
            case "3h": return TimeUnit.HOURS.toMillis(3);
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

    // Current Session 그래프 첫 로딩이 폐쇄망에서만 느린 원인(체크리스트 1-2)을 현장에서 구분하기 위해,
    // 서버 쪽 처리 시간(query_ms, 커넥션 획득+DB 조회)을 응답에 실어 보낸다. 브라우저는 전체 응답 시간과 이 값을 같이
    // 콘솔에 남기므로 "전체 - query_ms"가 네트워크·대기 시간이다. 느린 조회(3초 이상)는 서버 로그에도 남긴다.
    private static final long SLOW_ASH_QUERY_LOG_MS = 3000;

    private Map<String, Object> withQueryMs(String what, String dbId, Map<String, Object> result, long startedNanos) {
        long queryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        if (queryMs >= SLOW_ASH_QUERY_LOG_MS) {
            log.info("{} slow for db_id={}: {} ms", what, dbId, queryMs);
        }
        Map<String, Object> body = new LinkedHashMap<>(result);
        body.put("query_ms", queryMs);
        return body;
    }

    // "Current Session" 화면 Active Session Wait Class 차트(설계문서 `Current Session 매뉴
    // active_session 차트 개편.md` 1단계, 2026-09-22) - range_minutes는 30분/1시간(6시간/24시간은 저장값
    // /api/metric_history 경로). D1(2026-09-25): from/to(DB 시각, yyyy-MM-ddTHH:mm[:ss])를 주면 그 구간을
    // 조회한다 - 최대 24시간, ASH 보관 범위 밖은 AWR 보충(응답 source). step_minutes 1/5/10/15/30/60.
    private static final List<Integer> ASH_STEP_MINUTES = Arrays.asList(1, 5, 10, 15, 30, 60);

    @GetMapping("/ash_activity")
    public ResponseEntity<Object> ashActivity(@RequestParam(required = false) String db_id,
                                               @RequestParam(required = false) String token,
                                               @RequestParam(name = "range_minutes", required = false, defaultValue = "60") int rangeMinutes,
                                               @RequestParam(name = "step_minutes", required = false, defaultValue = "1") int stepMinutes,
                                               @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to,
                                               @RequestParam(required = false) String users,
                                               @RequestParam(required = false) String machines) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        LocalDateTime fromTime = null;
        LocalDateTime toTime = null;
        if (from != null && !from.trim().isEmpty()) {
            try {
                fromTime = LocalDateTime.parse(from.trim());
                toTime = (to == null || to.trim().isEmpty()) ? null : LocalDateTime.parse(to.trim());
            } catch (DateTimeParseException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Collections.singletonMap("error", "from/to must be yyyy-MM-ddTHH:mm[:ss] (DB time)"));
            }
            if (toTime != null && !fromTime.isBefore(toTime)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Collections.singletonMap("error", "from must be before to"));
            }
        } else if (rangeMinutes != 30 && rangeMinutes != 60) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Collections.singletonMap("error", "range_minutes must be 30 or 60 (1단계 구현 범위)"));
        }
        if (!ASH_STEP_MINUTES.contains(stepMinutes) || (fromTime == null && stepMinutes != 1)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Collections.singletonMap("error", "step_minutes must be 1 (from/to 지정 시 1/5/10/15/30/60)"));
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            long started = System.nanoTime();
            // users/machines: G3 성능 분석의 계정·접속 호스트 필터(2026-09-26). 없으면 예전과 같다.
            AshRange.Filter filter = AshRange.Filter.of(users, machines);
            Map<String, Object> result = monitorService.getAshActivity(target, rangeMinutes, stepMinutes, fromTime, toTime,
                    filter.isEmpty() ? null : filter);
            return ResponseEntity.ok(withQueryMs("ash_activity", db_id, result, started));
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
            long started = System.nanoTime();
            Map<String, Object> result = monitorService.getAshTopSql(target, rangeMinutes, stepMinutes);
            return ResponseEntity.ok(withQueryMs("ash_top_sql", db_id, result, started));
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
        // 정리(whenComplete)는 computeIfAbsent 밖에서 건다(2026-09-30, LockRealtimeService.inFlightFor와 같은 방식). 안에서 걸면
        // 조회가 아주 빨리 끝날 때(쿨다운 즉시 실패 등) 맵에 넣기 전에 remove가 먼저 돌고, 끝난 future가 영구히 남아 DB를 고쳐도
        // 재기동 전까지 옛 결과(down)만 돌려준다 - 원본 DBAgent-Java에서 포팅(docker_mssql 비밀번호를 고친 뒤 Fleet에서만 계속 down 실측·재현).
        final CompletableFuture<Map<String, Object>> f = fleetStatusInFlight.computeIfAbsent(inst.id(),
                id -> CompletableFuture.supplyAsync(() -> fleetStatusDispatch(inst), FLEET_STATUS_EXECUTOR));
        f.whenComplete((result, error) -> fleetStatusInFlight.remove(inst.id(), f));
        return f;
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
        // 비밀번호를 풀 수 없어 listAllInstances()에서 빠진 DB(2026-09-30) - 응답에서 그냥 빠지면 화면이 "접근 권한이 없는 DB일 수
        // 있습니다"로 잘못 안내하므로, down + reason=credential로 넣어 카드에 "비밀번호 확인 필요"가 보이게 한다.
        for (Map.Entry<String, String> e : configService.unreadableInstances().entrySet()) {
            if (!authService.canAccessDb(token, e.getKey())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", e.getKey());
            item.put("status", "down");
            item.put("reason", "credential");
            item.put("errorMessage", "등록된 비밀번호를 풀 수 없습니다 - DB 관리 화면에서 비밀번호를 다시 저장하세요. (" + e.getValue() + ")");
            results.add(item);
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

    // failure_prob 호출 대기 한도(초) - 원래 두 번째 대시보드(v2)의 active_alerts와 같이 쓰던 값. v2 삭제(2026-09-30,
    // 오케스트레이터 결정)로 instance_overview·active_alerts API는 없어졌고 이 값은 failure_prob만 쓴다(키 이름은 호환 위해 유지).
    @Value("${dbagent.monitor.active-alerts-timeout-seconds:5}")
    private long activeAlertsTimeoutSeconds;

    private static final int MAX_KILL_SESSIONS = 200;

    @PostMapping("/kill_session")
    public ResponseEntity<Object> killSession(
            @RequestParam(required = false) String db_id,
            @RequestBody KillSessionRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Maps.of("error", "세션 Kill 권한이 없습니다."));
        }
        // 2026-09-26 보강: 새 대시보드 KILL·RDB Kill과 같이 DB 접근 권한도 본다(관리자라도 허용 DB 밖은 거부).
        if (!authService.canAccessDb(req.token(), db_id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Maps.of("error", "해당 DB에 대한 접근 권한이 없습니다."));
        }
        if (req.sessions() == null || req.sessions().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "No sessions provided"));
        }
        if (req.sessions().size() > MAX_KILL_SESSIONS) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "Kill 대상이 너무 많습니다(최대 " + MAX_KILL_SESSIONS + ")."));
        }
        List<long[]> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (KillSessionRequest.SessionRef s : req.sessions()) {
            if (s == null || s.sid() == null || s.serial() == null || s.sid() <= 0 || s.serial() <= 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "sid/serial은 양의 정수여야 합니다."));
            }
            if (seen.add(s.sid() + ":" + s.serial())) parsed.add(new long[]{s.sid(), s.serial()});
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        String executedBy = authService.sessionForToken(req.token()).map(AuthService.AuthSession::username).orElse("?");
        String reason = "FAILOVER".equals(req.reason()) ? "FAILOVER" : "MANUAL_SESSION";
        try {
            List<Map<String, Object>> results = monitorService.killSessions(target, executedBy, reason, parsed);
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
        String[] name = parseTableName(tableName);
        if (name == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", TABLE_NAME_FORMAT_ERROR));
        }
        try {
            return ResponseEntity.ok(monitorService.getRelation(target, name[0], name[1], direction));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    private static final String TABLE_NAME_FORMAT_ERROR = "테이블명은 TABLE 또는 SCHEMA.TABLE 형식으로 입력해 주세요.";

    /**
     * Table 관계·테이블 정보 입력 "TABLE" 또는 "SCHEMA.TABLE"을 대문자로 나눈다 - [owner(생략 시 null), table].
     * 점이 둘 이상이거나 한쪽이 비면 null(2026-10-01 스키마 구분 추가). 값은 바인드 변수로만 쓴다.
     */
    static String[] parseTableName(String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase();
        if (v.isEmpty()) return null;
        int dot = v.indexOf('.');
        if (dot < 0) return new String[]{null, v};
        String owner = v.substring(0, dot).trim();
        String table = v.substring(dot + 1).trim();
        if (owner.isEmpty() || table.isEmpty() || table.indexOf('.') >= 0) return null;
        return new String[]{owner, table};
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
        String[] name = parseTableName(tableName);
        if (name == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", TABLE_NAME_FORMAT_ERROR));
        }
        try {
            return ResponseEntity.ok(monitorService.getTableInfo(target, name[0], name[1]));
        } catch (SQLException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Maps.of("error", e.getMessage()));
        }
    }

    // 예전 active_alerts(2026-09-30 v2와 함께 삭제)와 같은 이유(같은 v$lock 조인 패턴)로 같은 패턴을
    // 쓴다 - dba_objects join은 제거했지만(MonitorService.getFailureProb), 다른 환경에서 또 다른 원인
    // 으로 느려질 가능성까지 막으려면 호출자 쪽 타임아웃도 필요하다.
    private static final ExecutorService FAILURE_PROB_EXECUTOR = Executors.newCachedThreadPool();

    private final Map<String, CompletableFuture<Map<String, Object>>> failureProbInFlight = new ConcurrentHashMap<>();

    private CompletableFuture<Map<String, Object>> failureProbFor(TargetDbConfig target) {
        // 정리는 computeIfAbsent 밖에서 건다 - fleetStatusFor와 같은 이유(빨리 끝난 future가 영구히 남는 문제, 2026-09-30).
        final CompletableFuture<Map<String, Object>> f = failureProbInFlight.computeIfAbsent(target.id(),
                id -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return monitorService.getFailureProb(target);
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                }, FAILURE_PROB_EXECUTOR));
        f.whenComplete((result, error) -> failureProbInFlight.remove(target.id(), f));
        return f;
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
    // E1·E2·E3(2026-09-25): 좌측(Wait Class)·우측(Top SQL) 차트 드래그 공용. categories(7분류 키)·sql_ids·
    // exclude_sql_ids는 범례로 숨긴 계열을 뺀 필터(선택). 값은 허용 목록으로만 받는다(바인드 변수로 넘기지만
    // 형식이 틀린 값은 400).
    @GetMapping("/ash_session_detail")
    public ResponseEntity<Object> ashSessionDetail(
            @RequestParam(required = false) String db_id,
            @RequestParam(required = false) String token,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(required = false) String categories,
            @RequestParam(name = "sql_ids", required = false) String sqlIds,
            @RequestParam(name = "exclude_sql_ids", required = false) String excludeSqlIds) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        if (startTime == null || endTime == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Collections.singletonMap("error", "start_time and end_time are required"));
        }
        LocalDateTime start;
        LocalDateTime end;
        try {
            start = LocalDateTime.parse(startTime.trim());
            end = LocalDateTime.parse(endTime.trim());
        } catch (DateTimeParseException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Collections.singletonMap("error", "start_time/end_time must be yyyy-MM-ddTHH:mm (DB time)"));
        }
        List<String> categoryList = splitCsv(categories);
        for (String c : categoryList) {
            if (!Arrays.asList(AshCategories.KEYS7).contains(c)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Collections.singletonMap("error", "unknown category: " + c));
            }
        }
        List<String> sqlIdList = splitCsv(sqlIds);
        List<String> excludeList = splitCsv(excludeSqlIds);
        for (String id : concat(sqlIdList, excludeList)) {
            if (!id.matches("[a-z0-9]{13}")) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Collections.singletonMap("error", "invalid sql_id"));
            }
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(monitorService.getAshSessionDetail(target, start, end, categoryList, sqlIdList, excludeList));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
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

    /**
     * 요청 본문 JSON을 읽지 못한 경우(예: kill_session의 sid에 문자열) - Spring 기본 오류 JSON(timestamp/status/error 영문)
     * 대신 이 API들의 공통 형식 {"error": 한국어}로 400을 준다(2026-09-30 QA 낮음 항목). 이 컨트롤러에만 적용된다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleUnreadableBody(HttpMessageNotReadableException e) {
        // 본문이 없거나 JSON이 깨진 경우도 여기로 온다 - 문구는 원인을 단정하지 않게 일반적으로 둔다(2026-10-01 코드 점검).
        // 클라이언트 실수라 운영 로그를 채우지 않도록 debug로만 남긴다.
        log.debug("요청 본문 파싱 실패: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Maps.of("error", "요청 본문을 읽을 수 없습니다. JSON 형식과 값(예: sid·serial은 숫자)을 확인해 주세요."));
    }

    private ResponseEntity<Object> dbNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Maps.of("error", "등록되지 않은 DB입니다."));
    }
}
