package com.dbagent.oracle;

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

    public PoolTestController(DatabaseConfigService configService, OracleConnectionPoolManager poolManager) {
        this.configService = configService;
        this.poolManager = poolManager;
    }

    @GetMapping("/api/pool/test")
    public ResponseEntity<Map<String, Object>> test(@RequestParam("db_id") String dbId) {
        TargetDbConfig target = configService.resolve(dbId);
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
