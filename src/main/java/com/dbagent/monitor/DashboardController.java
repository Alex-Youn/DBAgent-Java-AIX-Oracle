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
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
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
    private final DashboardQueryService queryService;

    public DashboardController(AuthService authService, DatabaseConfigService configService,
                               LockRealtimeService lockService, DashboardQueryService queryService) {
        this.authService = authService;
        this.configService = configService;
        this.lockService = lockService;
        this.queryService = queryService;
    }

    // ------------------------------------------------------------------ ⑤⑥⑦ Top / 6장 드로어 (F3)

    /** 선택 구간(DB 시각) 파싱 - from 필수, to 없으면 DB 현재 시각. 최대 24시간은 AshRange가 자른다. */
    private static LocalDateTime[] parseRange(String from, String to) {
        if (from == null || from.trim().isEmpty()) return null;
        try {
            LocalDateTime f = LocalDateTime.parse(from.trim());
            LocalDateTime t = (to == null || to.trim().isEmpty()) ? null : LocalDateTime.parse(to.trim());
            if (t != null && !f.isBefore(t)) return null;
            return new LocalDateTime[]{f, t};
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private interface Query {
        Object run(TargetDbConfig target, LocalDateTime from, LocalDateTime to) throws SQLException;
    }

    private ResponseEntity<Object> rangeQuery(String dbId, String token, String from, String to, Query q) {
        if (!authService.canAccessDb(token, dbId)) {
            return error(HttpStatus.FORBIDDEN, "해당 DB에 대한 접근 권한이 없습니다.");
        }
        LocalDateTime[] range = parseRange(from, to);
        if (range == null) {
            return error(HttpStatus.BAD_REQUEST, "from/to는 yyyy-MM-ddTHH:mm[:ss](DB 시각)이고 from < to 여야 합니다.");
        }
        TargetDbConfig target = configService.resolve(dbId);
        if (target == null || !"oracle".equalsIgnoreCase(target.dbType())) {
            return error(HttpStatus.NOT_FOUND, "등록되지 않은 Oracle DB입니다.");
        }
        try {
            return ResponseEntity.ok(q.run(target, range[0], range[1]));
        } catch (SQLException e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "조회 실패: " + e.getMessage());
        }
    }

    /** ⑤⑥⑦ Top SQL·세션·이벤트 5개씩(ASH 한 번 스캔) + source(ash/awr/mixed). */
    @GetMapping("/{dbId}/top")
    public ResponseEntity<Object> top(@PathVariable String dbId, @RequestParam(required = false) String token,
                                      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return rangeQuery(dbId, token, from, to, queryService::top);
    }

    /** 6.1 세션 상세. */
    @GetMapping("/{dbId}/session/{sid}/{serial}")
    public ResponseEntity<Object> sessionDetail(@PathVariable String dbId, @PathVariable String sid, @PathVariable String serial,
                                                @RequestParam(required = false) String token,
                                                @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        Long s = toPositiveLong(sid);
        Long se = toPositiveLong(serial);
        if (s == null || se == null) {
            return error(HttpStatus.BAD_REQUEST, "sid/serial은 양의 정수여야 합니다.");
        }
        return rangeQuery(dbId, token, from, to, (t, f, e) -> queryService.sessionDetail(t, s, se, f, e));
    }

    /** 6.2 SQL 상세. */
    @GetMapping("/{dbId}/sql/{sqlId}")
    public ResponseEntity<Object> sqlDetail(@PathVariable String dbId, @PathVariable String sqlId,
                                            @RequestParam(required = false) String token,
                                            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        if (sqlId == null || !sqlId.matches("[a-z0-9]{13}")) {
            return error(HttpStatus.BAD_REQUEST, "sql_id 형식이 올바르지 않습니다.");
        }
        return rangeQuery(dbId, token, from, to, (t, f, e) -> queryService.sqlDetail(t, sqlId, f, e));
    }

    /** 6.3 대기 이벤트 상세 - 이벤트명은 바인드로만 쓰고, 길이·문자만 검사한다. */
    @GetMapping("/{dbId}/event")
    public ResponseEntity<Object> eventDetail(@PathVariable String dbId, @RequestParam(required = false) String name,
                                              @RequestParam(required = false) String token,
                                              @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        if (name == null || name.trim().isEmpty() || name.length() > 64 || !name.matches("[\\p{Print}]+")) {
            return error(HttpStatus.BAD_REQUEST, "이벤트명이 올바르지 않습니다.");
        }
        return rangeQuery(dbId, token, from, to, (t, f, e) -> queryService.eventDetail(t, name, f, e));
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
