package com.dbagent.oracle;

import com.dbagent.auth.AuthService;
import com.dbagent.monitor.InstanceMetricSamplerService;
import com.dbagent.monitor.LockRealtimeService;
import com.dbagent.monitor.MonitorService;
import com.dbagent.rdb.RdbConnectionPoolManager;
import com.dbagent.util.Maps;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only CRUD for db_instances rows (the "DB 관리" screen). Listing reuses the existing
 * public GET /api/config (ConfigController) - it already carries every field these writes touch,
 * minus the password, so a separate read endpoint isn't needed here.
 */
@RestController
public class DbConfigAdminController {

    /** CDB 확인 조회 타임아웃 - 딕셔너리가 아닌 v$database 1행이라 몇 초면 충분하다. */
    private static final int CDB_CHECK_TIMEOUT_SECONDS = 5;

    private final AuthService authService;
    private final DatabaseConfigService configService;
    private final OracleConnectionPoolManager poolManager;
    private final RdbConnectionPoolManager rdbPoolManager;
    private final InstanceMetricSamplerService metricSamplerService;
    private final MonitorService monitorService;
    private final LockRealtimeService lockRealtimeService;

    public DbConfigAdminController(AuthService authService, DatabaseConfigService configService,
            OracleConnectionPoolManager poolManager, RdbConnectionPoolManager rdbPoolManager,
            InstanceMetricSamplerService metricSamplerService, MonitorService monitorService,
            LockRealtimeService lockRealtimeService) {
        this.authService = authService;
        this.configService = configService;
        this.poolManager = poolManager;
        this.rdbPoolManager = rdbPoolManager;
        this.metricSamplerService = metricSamplerService;
        this.monitorService = monitorService;
        this.lockRealtimeService = lockRealtimeService;
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
                req.sessionThresholds(), req.oracleCdb());
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
                req.poolMinIdle(), req.poolMaxSize(), req.accounts(), req.sessionThresholds(), req.oracleCdb());
        if (Boolean.TRUE.equals(result.get("success"))) {
            // Host/user/port (or db_type itself) may have changed - evict any pool cached under this
            // db_id's old key so the next request builds a fresh one instead of talking to the old
            // target forever. Evicting both managers unconditionally is harmless (a no-op if this id
            // never had a pool of that kind) and correctly handles an instance's db_type changing
            // between oracle and mysql/mariadb/postgres across the edit.
            // Lock 실시간 상태는 풀보다 먼저 지운다 - 풀을 지운 직후 A안 백그라운드 수집이 옛 설정으로
            // 풀을 다시 만들지 않게(2026-09-28 점검).
            lockRealtimeService.clearDb(id);
            poolManager.evictPoolsForDbId(id);
            rdbPoolManager.evictPoolsForDbId(id);
            monitorService.clearBusyState(id);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 저장 직후 자동 확인(2026-10-02, 오케스트레이터 요청) - 등록한 CDB/Non-CDB 구분이 실제 접속 결과와 맞는지 본다.
     * SID로 등록해 CDB 루트(CDB$ROOT)에 붙는 실수를 잡는 게 목적이다. 저장을 막지는 않고 결과만 알려 준다
     * (ok=false면 화면이 경고를 띄운다). 조회는 dual의 SYS_CONTEXT 1회(구버전만 v$database 1행)라 대상 DB 부담은 없다.
     */
    @GetMapping("/api/db_configs/{id}/cdb_check")
    public ResponseEntity<Map<String, Object>> cdbCheck(@PathVariable String id, @RequestParam String token) {
        if (!authService.isAdmin(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Maps.of("success", false, "message", "관리자만 확인할 수 있습니다."));
        }
        TargetDbConfig target = configService.resolve(id);
        if (target == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Maps.of("success", false, "message", "등록되지 않은 DB입니다: " + id));
        }
        if (!"oracle".equalsIgnoreCase(target.dbType())) {
            return ResponseEntity.ok(Maps.of("success", true, "ok", true, "message", "Oracle이 아니라 확인 대상이 아닙니다."));
        }
        boolean registeredCdb = configService.isOracleCdb(id);
        Boolean actualCdb;
        String conName = null;
        try (Connection conn = poolManager.getConnection(target); Statement st = conn.createStatement()) {
            st.setQueryTimeout(CDB_CHECK_TIMEOUT_SECONDS);
            // SYS_CONTEXT는 권한이 필요 없어서 먼저 쓴다 - PDB 로컬 계정은 v$database 권한이 없는 경우가 있다
            // (로컬 XE XEPDB1의 dbagent_user에서 ORA-00942 실측, 2026-10-02). CDB_NAME은 CDB가 아니면 null(12.2+).
            try (ResultSet rs = st.executeQuery(
                    "SELECT SYS_CONTEXT('USERENV', 'CON_NAME'), SYS_CONTEXT('USERENV', 'CDB_NAME') FROM dual")) {
                rs.next();
                conName = rs.getString(1);
                actualCdb = rs.getString(2) != null;
            } catch (SQLException e) {
                // 12.1은 CDB_NAME, 11g 이하는 CON_NAME 자체가 없다(ORA-02003) - v$database로 한 번 더 본다.
                if (e.getErrorCode() != 2003) throw e;
                conName = null;
                try (ResultSet rs = st.executeQuery("SELECT cdb FROM v$database")) {
                    actualCdb = rs.next() && "YES".equalsIgnoreCase(rs.getString(1));
                } catch (SQLException e2) {
                    // 11g 이하는 v$database에 CDB 컬럼이 없다(ORA-00904) - 멀티테넌트 이전 버전이므로 Non-CDB로 본다.
                    if (e2.getErrorCode() != 904) throw e2;
                    actualCdb = false;
                }
                if (actualCdb) {
                    try (ResultSet rs = st.executeQuery("SELECT SYS_CONTEXT('USERENV', 'CON_NAME') FROM dual")) {
                        conName = rs.next() ? rs.getString(1) : null;
                    }
                }
            }
        } catch (SQLException | RuntimeException e) {
            // 풀 생성 실패 등 RuntimeException이 500으로 새면 화면이 JSON 파싱에 실패해 원인을 못 보여 주므로 같이 받는다.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("registered_cdb", registeredCdb);
            body.put("message", "접속 확인에 실패했습니다: " + oraReason(e));
            return ResponseEntity.ok(body);
        }

        boolean atRoot = "CDB$ROOT".equalsIgnoreCase(conName);
        boolean ok;
        String message;
        if (registeredCdb && actualCdb && !atRoot) {
            ok = true;
            message = "CDB 구조의 PDB" + pdbLabel(conName) + "에 접속되었습니다.";
        } else if (registeredCdb && atRoot) {
            ok = false;
            message = "CDB 루트(CDB$ROOT)에 접속되었습니다. 업무 DB를 보려면 PDB 서비스명으로 등록하세요.";
        } else if (registeredCdb) {
            ok = false;
            message = "Non-CDB DB입니다. 'CDB 방식' 체크를 해제하고 SID 방식으로 등록하세요.";
        } else if (!actualCdb) {
            ok = true;
            message = "Non-CDB DB에 접속되었습니다.";
        } else if (atRoot) {
            ok = false;
            message = "CDB 구조 DB의 루트(CDB$ROOT)에 접속되었습니다. 업무 DB(PDB)를 보려면 'CDB 방식'을 체크하고 "
                    + "PDB 서비스명으로 등록하세요.";
        } else {
            ok = false;
            message = "CDB 구조의 PDB" + pdbLabel(conName) + "입니다. 'CDB 방식'을 체크해 등록하세요(접속은 지금도 정상입니다).";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("ok", ok);
        body.put("registered_cdb", registeredCdb);
        body.put("actual_cdb", actualCdb);
        body.put("con_name", conName);
        body.put("message", message);
        return ResponseEntity.ok(body);
    }

    /** CON_NAME을 못 얻은 경우 "PDB(null)"이 나오지 않게 이름이 있을 때만 괄호로 붙인다. */
    private static String pdbLabel(String conName) {
        return (conName == null || conName.trim().isEmpty()) ? "" : "(" + conName + ")";
    }

    /**
     * 접속 실패 사유 - Hikari는 원인을 "Connection is not available..." 일반 문구로 감싸므로 원인 예외 사슬에서
     * ORA- 로 시작하는 첫 줄을 꺼내 비밀번호(ORA-01017)·리스너(ORA-12541) 등을 구분해 보여 준다(2026-10-02).
     */
    private static String oraReason(Throwable e) {
        for (Throwable t = e; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            String m = t.getMessage();
            if (m == null) continue;
            int i = m.indexOf("ORA-");
            if (i >= 0) {
                int nl = m.indexOf('\n', i);
                return (nl < 0 ? m.substring(i) : m.substring(i, nl)).trim();
            }
        }
        // 등록 직후 백그라운드 수집이 먼저 실패하면 풀에 10초 쿨다운이 걸려 ORA 사유 대신 이 문구가 나온다(2026-10-02 QA).
        for (Throwable t = e; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t.getMessage() != null && t.getMessage().contains("Waiting for cooldown")) {
                return "직전 접속 실패 직후라 잠시(약 10초) 쉬는 중입니다. 잠시 후 다시 확인하세요";
            }
        }
        return e.getMessage() == null ? "알 수 없는 오류" : e.getMessage();
    }

    @DeleteMapping("/api/db_configs/{id}")
    public ResponseEntity<Map<String, Object>> deleteDbConfig(@PathVariable String id, @RequestParam String token) {
        if (!authService.isAdmin(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Maps.of("success", false, "message", "관리자만 DB를 삭제할 수 있습니다."));
        }
        Map<String, Object> result = configService.deleteInstance(id);
        if (Boolean.TRUE.equals(result.get("success"))) {
            lockRealtimeService.clearDb(id);
            poolManager.evictPoolsForDbId(id);
            rdbPoolManager.evictPoolsForDbId(id);
            monitorService.clearBusyState(id);
        }
        return ResponseEntity.ok(result);
    }
}
