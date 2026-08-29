package com.dbagent.monitor;

import com.dbagent.auth.AuthService;
import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.TargetDbConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.ArrayList;
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

    public MonitorController(MonitorService monitorService, DatabaseConfigService configService, AuthService authService) {
        this.monitorService = monitorService;
        this.configService = configService;
        this.authService = authService;
    }

    @GetMapping("/tmlock")
    public ResponseEntity<Object> tmLock(@RequestParam(required = false) String db_id,
                                          @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        try {
            return ResponseEntity.ok(monitorService.getTmLocks(configService.resolve(db_id)));
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
        try {
            return ResponseEntity.ok(monitorService.getErdSchema(
                    configService.resolve(db_id), owner.toUpperCase(), prefix.toUpperCase()));
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
        try {
            return ResponseEntity.ok(monitorService.getSessions(configService.resolve(db_id)));
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
        try {
            return ResponseEntity.ok(monitorService.getSessionExtra(configService.resolve(db_id)));
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
        try {
            return ResponseEntity.ok(monitorService.getTablespaces(configService.resolve(db_id)));
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
        try {
            return ResponseEntity.ok(monitorService.getTablespaceDatafiles(configService.resolve(db_id), tablespaceName.toUpperCase()));
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
        try {
            return ResponseEntity.ok(monitorService.getDashboardStats(configService.resolve(db_id)));
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
                    CompletableFuture.supplyAsync(() -> monitorService.getFleetStatus(inst), FLEET_STATUS_EXECUTOR);
            f.whenComplete((result, error) -> fleetStatusInFlight.remove(id, f));
            return f;
        });
    }

    // Fleet Overview (fleet-overview-test-blue.html): one status snapshot per configured instance.
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
        try {
            return ResponseEntity.ok(monitorService.getTopEvents(configService.resolve(db_id)));
        } catch (SQLException e) {
            return dbError(e);
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
        try {
            List<Map<String, Object>> results = monitorService.killSessions(configService.resolve(db_id), req.sessions());
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
        try {
            return ResponseEntity.ok(monitorService.getRelation(configService.resolve(db_id), tableName.toUpperCase(), direction));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/session_query")
    public ResponseEntity<Object> sessionQuery(
            @RequestParam(required = false) String db_id,
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String sid,
            @RequestParam(name = "sql_id", required = false) String sqlId) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        if ((sid == null || Strings.isBlank(sid)) && (sqlId == null || Strings.isBlank(sqlId))) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "SID or SQL_ID is required"));
        }
        try {
            return ResponseEntity.ok(monitorService.getSessionQuery(configService.resolve(db_id), sid, sqlId));
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
        try {
            return ResponseEntity.ok(monitorService.getTableInfo(configService.resolve(db_id), tableName.toUpperCase()));
        } catch (SQLException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Maps.of("error", e.getMessage()));
        }
    }

    @GetMapping("/failure_prob")
    public ResponseEntity<Object> failureProb(@RequestParam(required = false) String db_id,
                                               @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        try {
            return ResponseEntity.ok(monitorService.getFailureProb(configService.resolve(db_id)));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/history_sessions")
    public ResponseEntity<Object> historySessions(
            @RequestParam(required = false) String db_id,
            @RequestParam(required = false) String token,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(required = false) String users) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        if (startTime == null || endTime == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "start_time and end_time are required"));
        }
        try {
            return ResponseEntity.ok(monitorService.getHistorySessions(configService.resolve(db_id), startTime, endTime, users));
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
        return ResponseEntity.ok(monitorService.getHealth(configService.resolve(db_id)));
    }

    @GetMapping("/history_top_sessions")
    public ResponseEntity<Object> historyTopSessions(
            @RequestParam(required = false) String db_id,
            @RequestParam(required = false) String token,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(required = false) String users) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        if (startTime == null || endTime == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "start_time and end_time are required"));
        }
        try {
            return ResponseEntity.ok(monitorService.getHistoryTopSessions(configService.resolve(db_id), startTime, endTime, users));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/db_users")
    public ResponseEntity<Object> dbUsers(@RequestParam(required = false) String db_id,
                                           @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        try {
            return ResponseEntity.ok(monitorService.getDbUsers(configService.resolve(db_id)));
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
}
