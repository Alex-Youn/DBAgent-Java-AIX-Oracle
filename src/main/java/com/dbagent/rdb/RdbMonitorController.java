package com.dbagent.rdb;

import com.dbagent.auth.AuthService;
import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.util.Maps;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MySQL/MariaDB/PostgreSQL counterpart of MonitorController's session/tablespace endpoints. */
@RestController
@RequestMapping("/api/rdb")
public class RdbMonitorController {

    private final AuthService authService;
    private final DatabaseConfigService configService;
    private final MySqlMonitorService mySqlMonitorService;
    private final PostgresMonitorService postgresMonitorService;
    private final MsSqlMonitorService msSqlMonitorService;

    public RdbMonitorController(AuthService authService, DatabaseConfigService configService,
            MySqlMonitorService mySqlMonitorService, PostgresMonitorService postgresMonitorService,
            MsSqlMonitorService msSqlMonitorService) {
        this.authService = authService;
        this.configService = configService;
        this.mySqlMonitorService = mySqlMonitorService;
        this.postgresMonitorService = postgresMonitorService;
        this.msSqlMonitorService = msSqlMonitorService;
    }

    @GetMapping("/sessions")
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
            return ResponseEntity.ok(engineFor(target).getSessions(target));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/storage")
    public ResponseEntity<Object> storage(@RequestParam(required = false) String db_id,
                                           @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(engineFor(target).getStorage(target));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // RDB 대시보드 세션 화면 3종 (세션 리스트 / 세션 상세 / Lock 모니터링).
    // 근거 문서: "세션리스트 및 세션 정보 조회 쿼리.md". 엔진별 쿼리는 engineFor(target) 가 고른
    // 서비스 안에 있고, 응답은 EngineMonitorService 의 주석에 적힌 공통 키로 정규화되어 나온다.
    //
    // 세 엔드포인트 모두 canAccessDb(token, db_id) 를 먼저 건다 - 세션 목록과 실행 중인 SQL 전문은
    // 이 앱이 내보내는 정보 중 가장 민감한 축에 속한다(다른 팀의 쿼리 원문이 그대로 보인다).
    // ---------------------------------------------------------------------------------------------

    @GetMapping("/session_list")
    public ResponseEntity<Object> sessionList(@RequestParam(required = false) String db_id,
                                               @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(engineFor(target).getSessionList(target));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/session_detail")
    public ResponseEntity<Object> sessionDetail(@RequestParam(required = false) String db_id,
                                                 @RequestParam(required = false) String session_id,
                                                 @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        // 세션 id 는 엔진별로 pid/id/SPID 라 이름만 다를 뿐 전부 정수다. 문자열로 받아 여기서
        // 한 번 검증한 뒤 PreparedStatement 로 바인딩한다 - 세션 화면은 사용자가 고른 값이
        // 그대로 쿼리에 들어가는 유일한 자리라, 숫자가 아니면 DB 까지 보내지 않고 잘라낸다.
        long sessionId;
        try {
            sessionId = Long.parseLong(String.valueOf(session_id).trim());
        } catch (NumberFormatException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Maps.of("error", "세션 ID가 올바르지 않습니다."));
        }
        try {
            return ResponseEntity.ok(engineFor(target).getSessionDetail(target, sessionId));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/lock_waits")
    public ResponseEntity<Object> lockWaits(@RequestParam(required = false) String db_id,
                                             @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(engineFor(target).getLockWaits(target));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 용량 조회 (오라클 "테이블 스페이스 조회" 대응). 엔진별 단위 차이는 서비스가 흡수하고,
    // 응답의 unit/note 로 화면이 머리글과 설명을 바꿔 단다 - EngineMonitorService 주석 참고.
    // ---------------------------------------------------------------------------------------------

    @GetMapping("/capacity")
    public ResponseEntity<Object> capacity(@RequestParam(required = false) String db_id,
                                            @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        try {
            return ResponseEntity.ok(engineFor(target).getCapacity(target));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    @GetMapping("/capacity_detail")
    public ResponseEntity<Object> capacityDetail(@RequestParam(required = false) String db_id,
                                                  @RequestParam(required = false) String scope,
                                                  @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        // scope 는 1단 목록에서 고른 스키마/DB 이름이다. 값 자체는 서비스가 바인딩하거나
        // QUOTENAME 으로 감싸므로 여기서는 비었는지만 본다.
        if (scope == null || scope.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Maps.of("error", "조회할 스키마/데이터베이스를 선택해주세요."));
        }
        try {
            return ResponseEntity.ok(engineFor(target).getCapacityDetail(target, scope.trim()));
        } catch (SQLException e) {
            return dbError(e);
        }
    }

    /**
     * 선택한 세션들을 강제 종료한다(RDB 대시보드 세션 리스트 / Lock 트리의 Kill 버튼).
     *
     * <p><b>관리자 전용이다(사용자 지시, 2026-09-05).</b> 다른 조회 API 처럼 canAccessDb 만 걸면
     * "볼 수 있으면 죽일 수도 있는" 상태가 된다. 세션 Kill 은 남의 트랜잭션을 되돌리는 파괴적
     * 동작이라 조회 권한과 같은 급으로 둘 수 없다. 오라클 쪽 kill_session 과 같은 기준이다
     * (MonitorController.killSession 은 authService.isAdmin 만 본다).
     *
     * <p>admin 이라도 등록되지 않은 db_id 로는 못 들어가도록 canAccessDb 도 함께 건다 - admin 은
     * 이 검사를 통과하므로 관리자에게는 아무 제약이 아니고, 일반 계정에는 이중 방어가 된다.
     *
     * <p>화면 쪽에서도 Kill 버튼을 admin 에게만 보여주지만, 그건 편의일 뿐 권한 판정이 아니다 -
     * sessionStorage 의 role 은 브라우저에서 고칠 수 있으므로 <b>실제 차단은 여기서만 한다.</b>
     */
    @PostMapping("/kill_session")
    public ResponseEntity<Object> killSession(@RequestParam(required = false) String db_id,
                                               @RequestBody RdbKillSessionRequest req) {
        String token = req == null ? null : req.token();
        if (!authService.isAdmin(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Maps.of("error", "세션 Kill 권한이 없습니다. 관리자만 사용할 수 있습니다."));
        }
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        List<String> requested = req.sessions();
        if (requested == null || requested.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Maps.of("error", "종료할 세션을 선택해주세요."));
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }

        // 한 건이 실패해도 나머지는 계속 진행한다 - 화면은 건별 성공/실패를 함께 보여준다.
        // 숫자가 아닌 값은 DB 까지 보내지 않고 여기서 잘라낸다(KILL 은 바인드 파라미터를 못 받는
        // 명령이라, 검증되지 않은 값이 문자열로 조립되면 그대로 주입 통로가 된다).
        List<Map<String, Object>> results = new ArrayList<>();
        for (String raw : requested) {
            long sessionId;
            try {
                sessionId = Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                Map<String, Object> bad = new LinkedHashMap<>();
                bad.put("session_id", raw);
                bad.put("status", "error");
                bad.put("message", "세션 ID가 올바르지 않습니다.");
                results.add(bad);
                continue;
            }
            results.add(engineFor(target).killSession(target, sessionId));
        }
        return ResponseEntity.ok(Maps.of("results", results));
    }



    // MySQL/MariaDB-only KPI stats (uptime/QPS/buffer pool) for mysql-overview-dashboard.html - see
    // MySqlMonitorService.getOverviewStats() javadoc for why Postgres isn't handled here too.
    @GetMapping("/mysql_overview")
    public ResponseEntity<Object> mysqlOverview(@RequestParam(required = false) String db_id,
                                                 @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        if (!"mysql".equals(target.dbType()) && !"mariadb".equals(target.dbType())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "MySQL/MariaDB 전용 API입니다."));
        }
        return ResponseEntity.ok(mySqlMonitorService.getOverviewStats(target));
    }

    // MySQL/MariaDB-only status counters (locks/temp objects/sorts/aborted/network/memory/commands/
    // handlers/processes/query cache/file+table caches) for the mockup's remaining collapsed rows -
    // see MySqlMonitorService.getStatusOverview() javadoc.
    @GetMapping("/mysql_status")
    public ResponseEntity<Object> mysqlStatus(@RequestParam(required = false) String db_id,
                                               @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        if (!"mysql".equals(target.dbType()) && !"mariadb".equals(target.dbType())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "MySQL/MariaDB 전용 API입니다."));
        }
        return ResponseEntity.ok(mySqlMonitorService.getStatusOverview(target));
    }

    // PostgreSQL-only KPI stats (uptime/TPS/shared buffers/cache hit rate) for
    // postgres-overview-dashboard.html - see PostgresMonitorService.getOverviewStats() javadoc.
    @GetMapping("/postgres_overview")
    public ResponseEntity<Object> postgresOverview(@RequestParam(required = false) String db_id,
                                                     @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        if (!"postgres".equals(target.dbType())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "PostgreSQL 전용 API입니다."));
        }
        return ResponseEntity.ok(postgresMonitorService.getOverviewStats(target));
    }

    // PostgreSQL-only status counters (connections/locks/temp files/tuple activity/transactions/
    // deadlocks/checkpoints/WAL/vacuum/replication) - see PostgresMonitorService.getStatusOverview().
    @GetMapping("/postgres_status")
    public ResponseEntity<Object> postgresStatus(@RequestParam(required = false) String db_id,
                                                   @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        if (!"postgres".equals(target.dbType())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "PostgreSQL 전용 API입니다."));
        }
        return ResponseEntity.ok(postgresMonitorService.getStatusOverview(target));
    }

    // MS SQL Server-only KPI stats (uptime/batch requests/buffer memory/cache hit rate) for
    // mssql-overview-dashboard.html - see MsSqlMonitorService.getOverviewStats() javadoc.
    @GetMapping("/mssql_overview")
    public ResponseEntity<Object> mssqlOverview(@RequestParam(required = false) String db_id,
                                                 @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        if (!"mssql".equals(target.dbType())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "MS SQL Server 전용 API입니다."));
        }
        return ResponseEntity.ok(msSqlMonitorService.getOverviewStats(target));
    }

    // MS SQL Server-only status counters (connections/batch activity/locks/wait stats/memory/tempdb/
    // I/O) - see MsSqlMonitorService.getStatusOverview() javadoc.
    @GetMapping("/mssql_status")
    public ResponseEntity<Object> mssqlStatus(@RequestParam(required = false) String db_id,
                                               @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return dbAccessDenied();
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null) {
            return dbNotFound();
        }
        if (!"mssql".equals(target.dbType())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Maps.of("error", "MS SQL Server 전용 API입니다."));
        }
        return ResponseEntity.ok(msSqlMonitorService.getStatusOverview(target));
    }

    private EngineMonitorService engineFor(TargetDbConfig target) {
        if ("postgres".equals(target.dbType())) {
            return postgresMonitorService;
        }
        if ("mssql".equals(target.dbType())) {
            return msSqlMonitorService;
        }
        // mysql/mariadb (and any unrecognized non-oracle type falls back here rather than 500ing).
        return mySqlMonitorService;
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
