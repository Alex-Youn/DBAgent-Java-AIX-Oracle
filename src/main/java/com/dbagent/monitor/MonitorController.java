package com.dbagent.monitor;

import com.dbagent.auth.AuthService;
import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.TargetDbConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MonitorController {

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
