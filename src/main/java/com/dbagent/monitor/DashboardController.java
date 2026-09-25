package com.dbagent.monitor;

import com.dbagent.auth.AuthService;
import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.TargetDbConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 대시보드 개편 API(설계문서 `대시보드 UI 개선 설계.md` 7장, 전체 작업순서 F2~F3 - 2026-09-25).
 * 경로의 {dbId}가 대상 DB다. 모든 요청은 canAccessDb, KILL은 관리자 권한까지 확인한다.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    /** 한 번에 KILL 요청할 수 있는 최대 세션 수 - 잘못된 요청이 대량 KILL로 번지지 않게. */
    private static final int MAX_KILL_TARGETS = 200;

    private final AuthService authService;
    private final DatabaseConfigService configService;
    private final LockRealtimeService lockService;

    public DashboardController(AuthService authService, DatabaseConfigService configService,
                               LockRealtimeService lockService) {
        this.authService = authService;
        this.configService = configService;
        this.lockService = lockService;
    }

    /** ③ Lock 대기 세션(실시간) - TX/TM 건수, 장애 판정 수, KILL 대상 Holder, 최근 10분 추이. */
    @GetMapping("/{dbId}/lock/realtime")
    public ResponseEntity<Object> lockRealtime(@PathVariable String dbId, @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, dbId)) {
            return error(HttpStatus.FORBIDDEN, "해당 DB에 대한 접근 권한이 없습니다.");
        }
        TargetDbConfig target = configService.resolve(dbId);
        if (target == null || !"oracle".equalsIgnoreCase(target.dbType())) {
            return error(HttpStatus.NOT_FOUND, "등록되지 않은 Oracle DB입니다.");
        }
        return ResponseEntity.ok(lockService.realtime(target));
    }

    /** 요청 본문 - dbId는 확인 패널을 연 순간의 DB(화면에 고정된 값). 경로와 다르면 거부(DB 전환 시 오전송 방지). */
    public static class KillRequest {
        public String token;
        public String dbId;
        public List<Map<String, Object>> targets;
    }

    /** ③-1 TM Lock 장애 처리 - 관리자 전용, 실행 직전 재조회 후 교집합만 KILL, 전부 감사 기록. */
    @PostMapping("/{dbId}/lock/tm-holders/kill")
    public ResponseEntity<Object> killTmHolders(@PathVariable String dbId, @RequestBody KillRequest req) {
        if (req == null) {
            return error(HttpStatus.BAD_REQUEST, "요청 본문이 없습니다.");
        }
        if (!authService.isAdmin(req.token)) {
            return error(HttpStatus.FORBIDDEN, "세션 Kill 권한이 없습니다.");
        }
        if (!authService.canAccessDb(req.token, dbId)) {
            return error(HttpStatus.FORBIDDEN, "해당 DB에 대한 접근 권한이 없습니다.");
        }
        if (req.dbId == null || !req.dbId.equals(dbId)) {
            return error(HttpStatus.BAD_REQUEST, "요청 DB가 화면의 DB와 다릅니다(경로와 본문 dbId 불일치).");
        }
        if (req.targets == null || req.targets.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "KILL 대상이 없습니다.");
        }
        if (req.targets.size() > MAX_KILL_TARGETS) {
            return error(HttpStatus.BAD_REQUEST, "KILL 대상이 너무 많습니다(최대 " + MAX_KILL_TARGETS + ").");
        }
        List<long[]> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> t : req.targets) {
            Long sid = toPositiveLong(t == null ? null : t.get("sid"));
            Long serial = toPositiveLong(t == null ? null : t.get("serial"));
            if (sid == null || serial == null) {
                return error(HttpStatus.BAD_REQUEST, "sid/serial은 양의 정수여야 합니다.");
            }
            if (seen.add(sid + ":" + serial)) {
                parsed.add(new long[]{sid, serial});
            }
        }
        TargetDbConfig target = configService.resolve(dbId);
        if (target == null || !"oracle".equalsIgnoreCase(target.dbType())) {
            return error(HttpStatus.NOT_FOUND, "등록되지 않은 Oracle DB입니다.");
        }
        String executedBy = authService.sessionForToken(req.token).map(AuthService.AuthSession::username).orElse("?");
        try {
            return ResponseEntity.ok(lockService.killTmHolders(target, executedBy, parsed));
        } catch (SQLException e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "KILL 처리 중 DB 오류: " + e.getMessage());
        }
    }

    /** 정수만 허용(문자열 "12"도 허용하되 "12,34"·"12 or 1=1" 같은 값은 거부) - ALTER SYSTEM은 바인드 불가라 유일한 방어선. */
    private static Long toPositiveLong(Object v) {
        if (v instanceof Integer || v instanceof Long) {
            long n = ((Number) v).longValue();
            return n > 0 ? n : null;
        }
        if (v instanceof String && ((String) v).matches("\\d{1,10}")) {
            long n = Long.parseLong((String) v);
            return n > 0 ? n : null;
        }
        return null;
    }

    private static ResponseEntity<Object> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(Collections.unmodifiableMap(body));
    }
}
