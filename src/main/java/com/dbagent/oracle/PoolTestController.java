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
            int result = rs.getInt(1);

            // oracle.env 제거 마이그레이션 5단계: host/port 오타로 엉뚱하지만 실제로 존재하는 다른
            // Oracle 인스턴스에 접속되는 사고를 막기 위한 안전장치. expected_instance_name이 설정된
            // 경우에만 확인하고, 연결 자체는 성공했더라도 인스턴스가 다르면 실패로 처리한다.
            String expected = target.expectedInstanceName();
            if ("oracle".equalsIgnoreCase(target.dbType()) && expected != null && !expected.trim().isEmpty()) {
                try (Statement instSt = conn.createStatement();
                     ResultSet instRs = instSt.executeQuery("SELECT instance_name FROM v$instance")) {
                    String actual = instRs.next() ? instRs.getString(1) : null;
                    if (actual == null || !expected.trim().equalsIgnoreCase(actual.trim())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(Maps.of(
                                "success", false,
                                "db_id", target.id(),
                                "name", target.name(),
                                "message", "접속은 성공했지만 등록된 인스턴스가 아닙니다 (기대: " + expected
                                        + ", 실제: " + actual + ") - host/port 설정을 다시 확인하세요."));
                    }
                }
            }

            return ResponseEntity.ok(Maps.of(
                    "success", true,
                    "db_id", target.id(),
                    "name", target.name(),
                    "result", result));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Maps.of("success", false, "message", String.valueOf(e.getMessage())));
        }
    }
}
