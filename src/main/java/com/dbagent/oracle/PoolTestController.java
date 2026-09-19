package com.dbagent.oracle;

import com.dbagent.auth.AuthService;
import com.dbagent.util.Maps;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

/** Minimal endpoint to verify the ported connection pool against a real Oracle instance. */
@RestController
public class PoolTestController {

    private final DatabaseConfigService configService;
    private final OracleConnectionPoolManager poolManager;
    private final AuthService authService;

    public PoolTestController(DatabaseConfigService configService, OracleConnectionPoolManager poolManager,
            AuthService authService) {
        this.configService = configService;
        this.poolManager = poolManager;
        this.authService = authService;
    }

    @GetMapping("/api/pool/test")
    public ResponseEntity<Map<String, Object>> test(@RequestParam("db_id") String dbId,
            @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, dbId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Maps.of("success", false, "message", "해당 DB에 대한 접근 권한이 없습니다."));
        }
        TargetDbConfig target = configService.resolve(dbId);
        if (target == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Maps.of("success", false, "message", "등록되지 않은 DB입니다."));
        }
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM dual")) {
            rs.next();
            return ResponseEntity.ok(Maps.of(
                    "success", true,
                    "db_id", target.id(),
                    "name", target.name(),
                    "result", rs.getInt(1)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Maps.of("success", false, "message", String.valueOf(e.getMessage())));
        }
    }
}
