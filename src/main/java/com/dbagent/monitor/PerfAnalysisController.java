package com.dbagent.monitor;

import com.dbagent.auth.AuthService;
import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.TargetDbConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;

/**
 * G3 성능 분석 API(2026-09-26). 시각은 DB 시각 yyyy-MM-ddTHH:mm[:ss], 구간은 최대 24시간(AshRange).
 * users/machines는 쉼표 구분(빈 값 = 전체).
 */
@RestController
@RequestMapping("/api/perf")
public class PerfAnalysisController {

    private final AuthService authService;
    private final DatabaseConfigService configService;
    private final PerfAnalysisService perfService;

    @org.springframework.beans.factory.annotation.Value("${dbagent.monitor.perf-retention-days:15}")
    private int perfRetentionDays;

    public PerfAnalysisController(AuthService authService, DatabaseConfigService configService, PerfAnalysisService perfService) {
        this.authService = authService;
        this.configService = configService;
        this.perfService = perfService;
    }

    private interface Query {
        Object run(TargetDbConfig target, LocalDateTime from, LocalDateTime to, AshRange.Filter filter) throws SQLException;
    }

    /** 화면 기본 구간(최근 1시간)을 DB 시각으로 잡기 위한 DB 현재 시각 - 브라우저·DB 시간대가 달라도 맞게. */
    @GetMapping("/db_now")
    public ResponseEntity<Object> dbNow(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return error(HttpStatus.FORBIDDEN, "해당 DB에 대한 접근 권한이 없습니다.");
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null || !"oracle".equalsIgnoreCase(target.dbType())) {
            return error(HttpStatus.NOT_FOUND, "등록되지 않은 Oracle DB입니다.");
        }
        try {
            return ResponseEntity.ok(Collections.singletonMap("dbNow", perfService.dbNow(target)));
        } catch (SQLException e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "조회 중 DB 오류: " + e.getMessage());
        }
    }

    /** 상단 차트(수집 저장소) - step_minutes 1/5/10/15/30/60. 원본 ASH/AWR 경로는 /api/ash_activity. */
    @GetMapping("/activity")
    public ResponseEntity<Object> activity(@RequestParam(required = false) String db_id,
                                           @RequestParam(required = false) String token,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(name = "step_minutes", required = false, defaultValue = "1") int step,
                                           @RequestParam(required = false) String users,
                                           @RequestParam(required = false) String machines) {
        if (!java.util.Arrays.asList(1, 5, 10, 15, 30, 60).contains(step)) {
            return error(HttpStatus.BAD_REQUEST, "step_minutes는 1/5/10/15/30/60 중 하나입니다.");
        }
        return run(db_id, token, from, to, users, machines, (t, f, e, fl) -> perfService.storeActivity(t, f, e, step, fl));
    }

    /** 계정·서버 드롭다운(수집 저장소, 보관 기간 안 값). */
    @GetMapping("/filters")
    public ResponseEntity<Object> filters(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, db_id)) {
            return error(HttpStatus.FORBIDDEN, "해당 DB에 대한 접근 권한이 없습니다.");
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null || !"oracle".equalsIgnoreCase(target.dbType())) {
            return error(HttpStatus.NOT_FOUND, "등록되지 않은 Oracle DB입니다.");
        }
        try {
            return ResponseEntity.ok(perfService.storeFilters(target, perfRetentionDays));
        } catch (SQLException e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "조회 중 DB 오류: " + e.getMessage());
        }
    }

    /** source=store(기본, 수집 저장소) | oracle(원본 ASH/AWR - 수집 전 구간용). */
    @GetMapping("/events")
    public ResponseEntity<Object> events(@RequestParam(required = false) String db_id,
                                         @RequestParam(required = false) String token,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String users,
                                         @RequestParam(required = false) String machines,
                                         @RequestParam(required = false, defaultValue = "store") String source) {
        return run(db_id, token, from, to, users, machines,
                "oracle".equals(source) ? perfService::events : perfService::storeEvents);
    }

    @GetMapping("/event_sessions")
    public ResponseEntity<Object> eventSessions(@RequestParam(required = false) String db_id,
                                                @RequestParam(required = false) String token,
                                                @RequestParam(required = false) String event,
                                                @RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to,
                                                @RequestParam(required = false) String users,
                                                @RequestParam(required = false) String machines,
                                                @RequestParam(required = false, defaultValue = "store") String source) {
        if (event == null || event.trim().isEmpty() || event.length() > 64) {
            return error(HttpStatus.BAD_REQUEST, "event가 필요합니다(64자 이하).");
        }
        boolean oracle = "oracle".equals(source);
        return run(db_id, token, from, to, users, machines, (t, f, e, fl) -> oracle
                ? perfService.eventSessions(t, event, f, e, fl) : perfService.storeEventSessions(t, event, f, e, fl));
    }

    private ResponseEntity<Object> run(String dbId, String token, String from, String to, String users, String machines, Query q) {
        if (!authService.canAccessDb(token, dbId)) {
            return error(HttpStatus.FORBIDDEN, "해당 DB에 대한 접근 권한이 없습니다.");
        }
        LocalDateTime f;
        LocalDateTime t;
        try {
            if (from == null || from.trim().isEmpty() || to == null || to.trim().isEmpty()) {
                return error(HttpStatus.BAD_REQUEST, "from/to가 필요합니다(yyyy-MM-ddTHH:mm, DB 시각).");
            }
            f = LocalDateTime.parse(from.trim());
            t = LocalDateTime.parse(to.trim());
        } catch (DateTimeParseException e) {
            return error(HttpStatus.BAD_REQUEST, "from/to 형식은 yyyy-MM-ddTHH:mm[:ss]입니다(DB 시각).");
        }
        if (!f.isBefore(t)) {
            return error(HttpStatus.BAD_REQUEST, "시작 시각이 종료 시각보다 앞서야 합니다.");
        }
        TargetDbConfig target = configService.resolve(dbId);
        if (target == null || !"oracle".equalsIgnoreCase(target.dbType())) {
            return error(HttpStatus.NOT_FOUND, "등록되지 않은 Oracle DB입니다.");
        }
        try {
            return ResponseEntity.ok(q.run(target, f, t, AshRange.Filter.of(users, machines)));
        } catch (SQLException e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "조회 중 DB 오류: " + e.getMessage());
        }
    }

    private static ResponseEntity<Object> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
