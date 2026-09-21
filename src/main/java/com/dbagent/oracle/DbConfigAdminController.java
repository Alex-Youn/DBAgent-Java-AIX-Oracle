package com.dbagent.oracle;

import com.dbagent.auth.AuthService;
import com.dbagent.monitor.InstanceMetricSamplerService;
import com.dbagent.rdb.RdbConnectionPoolManager;
import com.dbagent.util.Maps;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin-only CRUD for db_instances rows (the "DB 관리" screen). Listing reuses the existing
 * public GET /api/config (ConfigController) - it already carries every field these writes touch,
 * minus the password, so a separate read endpoint isn't needed here.
 */
@RestController
public class DbConfigAdminController {

    private final AuthService authService;
    private final DatabaseConfigService configService;
    private final OracleConnectionPoolManager poolManager;
    private final RdbConnectionPoolManager rdbPoolManager;
    private final InstanceMetricSamplerService metricSamplerService;

    public DbConfigAdminController(AuthService authService, DatabaseConfigService configService,
            OracleConnectionPoolManager poolManager, RdbConnectionPoolManager rdbPoolManager,
            InstanceMetricSamplerService metricSamplerService) {
        this.authService = authService;
        this.configService = configService;
        this.poolManager = poolManager;
        this.rdbPoolManager = rdbPoolManager;
        this.metricSamplerService = metricSamplerService;
    }

    @PostMapping("/api/db_configs")
    public ResponseEntity<Map<String, Object>> createDbConfig(@RequestBody CreateDbInstanceRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Maps.of("success", false, "message", "관리자만 DB를 추가할 수 있습니다."));
        }
        Map<String, Object> result = configService.createInstance(req.groupName(), req.id(), req.name(), req.dbType(),
                req.host(), req.port(), req.sid(), req.user(), req.password(), req.connectMode(),
                req.expectedInstanceName(), req.poolMinIdle(), req.poolMaxSize(), req.accounts(),
                req.sessionThresholds());
        if (Boolean.TRUE.equals(result.get("success"))) {
            // 등록 직후 바로 1회 수집해 둔다(오케스트레이터 요청, 2026-09-18) - 안 그러면 CPU/DB Time·
            // Lock 추이 그래프가 다음 정기 수집 사이클(기본 60초)까지 완전히 비어 보인다.
            TargetDbConfig newTarget = configService.resolve(req.id());
            if (newTarget != null) {
                metricSamplerService.sampleNowAsync(newTarget);
            }
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping("/api/db_configs/{id}")
    public ResponseEntity<Map<String, Object>> updateDbConfig(@PathVariable String id, @RequestBody UpdateDbInstanceRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Maps.of("success", false, "message", "관리자만 DB를 수정할 수 있습니다."));
        }
        Map<String, Object> result = configService.updateInstance(id, req.name(), req.dbType(), req.host(),
                req.port(), req.sid(), req.user(), req.password(), req.connectMode(), req.expectedInstanceName(),
                req.poolMinIdle(), req.poolMaxSize(), req.accounts(), req.sessionThresholds());
        if (Boolean.TRUE.equals(result.get("success"))) {
            // Host/user/port (or db_type itself) may have changed - evict any pool cached under this
            // db_id's old key so the next request builds a fresh one instead of talking to the old
            // target forever. Evicting both managers unconditionally is harmless (a no-op if this id
            // never had a pool of that kind) and correctly handles an instance's db_type changing
            // between oracle and mysql/mariadb/postgres across the edit.
            poolManager.evictPoolsForDbId(id);
            rdbPoolManager.evictPoolsForDbId(id);
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/api/db_configs/{id}")
    public ResponseEntity<Map<String, Object>> deleteDbConfig(@PathVariable String id, @RequestParam String token) {
        if (!authService.isAdmin(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Maps.of("success", false, "message", "관리자만 DB를 삭제할 수 있습니다."));
        }
        Map<String, Object> result = configService.deleteInstance(id);
        if (Boolean.TRUE.equals(result.get("success"))) {
            poolManager.evictPoolsForDbId(id);
            rdbPoolManager.evictPoolsForDbId(id);
        }
        return ResponseEntity.ok(result);
    }
}
