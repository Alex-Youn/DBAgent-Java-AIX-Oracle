package com.dbagent.rdb;

import com.dbagent.auth.AuthService;
import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.util.Maps;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.Map;

/** MySQL/MariaDB/PostgreSQL counterpart of MonitorController's session/tablespace endpoints. */
@RestController
@RequestMapping("/api/rdb")
public class RdbMonitorController {

    private final AuthService authService;
    private final DatabaseConfigService configService;
    private final MySqlMonitorService mySqlMonitorService;
    private final PostgresMonitorService postgresMonitorService;

    public RdbMonitorController(AuthService authService, DatabaseConfigService configService,
            MySqlMonitorService mySqlMonitorService, PostgresMonitorService postgresMonitorService) {
        this.authService = authService;
        this.configService = configService;
        this.mySqlMonitorService = mySqlMonitorService;
        this.postgresMonitorService = postgresMonitorService;
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

    private EngineMonitorService engineFor(TargetDbConfig target) {
        if ("postgres".equals(target.dbType())) {
            return postgresMonitorService;
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
