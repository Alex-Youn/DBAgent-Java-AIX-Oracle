package com.dbagent.monitor;

import com.dbagent.settings.SettingKey;
import com.dbagent.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import com.dbagent.auth.AuthService;
import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.TargetDbConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(PerfAnalysisController.class);

    // 환경설정 DB화 P3-2(2026-10-04): 보존 기간을 SettingsService 에서 읽는다(저장 즉시 반영).
    @Autowired
    private SettingsService settings;

    private final AuthService authService;
    private final DatabaseConfigService configService;
    private final PerfAnalysisService perfService;
    private final PerfLockService lockService;
    private final PerfSysService sysService;
    private final PerfSqlService sqlService;
    private final PerfCompareService compareService;
    private final PerfObjService objService;

    public PerfAnalysisController(AuthService authService, DatabaseConfigService configService, PerfAnalysisService perfService,
                                  PerfLockService lockService, PerfSysService sysService, PerfSqlService sqlService,
                                  PerfCompareService compareService, PerfObjService objService) {
        this.compareService = compareService;
        this.objService = objService;
        this.sysService = sysService;
        this.sqlService = sqlService;
        this.authService = authService;
        this.configService = configService;
        this.perfService = perfService;
        this.lockService = lockService;
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
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<String, Object>();
            body.put("dbNow", perfService.dbNow(target));
            // 접속 컨테이너(CDB$ROOT·PDB 이름). 11g·조회 불가는 null(화면은 표시만 생략)
            body.put("conName", perfService.containerName(target));
            return ResponseEntity.ok(body);
        } catch (SQLException e) {
            return dbError(e);
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
            return ResponseEntity.ok(perfService.storeFilters(target, settings.getInt(SettingKey.PERF_RETENTION_DAYS)));
        } catch (SQLException e) {
            return dbError(e);
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

    // ------------------------------------------------------------------ Lock 분석 (성능 분석 개편 Phase 1a, 2026-10-09, 설계 1-5)
    // 수집 저장소만 읽는다(운영 DB 조회 없음). 파라미터는 공통(db_id, from, to, users, machines) + type(all/TX/TM), min_sec.
    // 응답 JSON 키는 camelCase. 수집이 안 되는 DB는 응답의 collect.state(unavailable·error)로 "판단 보류"를 알린다.

    private static String lockType(String type) {
        return "TX".equalsIgnoreCase(type) ? "TX" : "TM".equalsIgnoreCase(type) ? "TM" : "all";
    }

    @GetMapping("/lock_summary")
    public ResponseEntity<Object> lockSummary(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                              @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                              @RequestParam(required = false) String users, @RequestParam(required = false) String machines,
                                              @RequestParam(required = false, defaultValue = "all") String type,
                                              @RequestParam(name = "min_sec", required = false, defaultValue = "0") int minSec) {
        return run(db_id, token, from, to, users, machines,
                (t, f, e, fl) -> lockService.summary(t.id(), f, e, fl, lockType(type), Math.max(0, minSec)));
    }

    @GetMapping("/lock_timeline")
    public ResponseEntity<Object> lockTimeline(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                               @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                               @RequestParam(required = false) String users, @RequestParam(required = false) String machines) {
        return run(db_id, token, from, to, users, machines, (t, f, e, fl) -> lockService.timeline(t.id(), f, e, fl));
    }

    @GetMapping("/lock_incidents")
    public ResponseEntity<Object> lockIncidents(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                                @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                @RequestParam(required = false) String users, @RequestParam(required = false) String machines,
                                                @RequestParam(required = false, defaultValue = "all") String type,
                                                @RequestParam(name = "min_sec", required = false, defaultValue = "0") int minSec) {
        return run(db_id, token, from, to, users, machines,
                (t, f, e, fl) -> lockService.incidents(t.id(), f, e, fl, lockType(type), Math.max(0, minSec)));
    }

    @GetMapping("/lock_top_blockers")
    public ResponseEntity<Object> lockTopBlockers(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                                  @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                  @RequestParam(required = false) String users, @RequestParam(required = false) String machines,
                                                  @RequestParam(required = false, defaultValue = "all") String type) {
        return run(db_id, token, from, to, users, machines, (t, f, e, fl) -> lockService.topBlockers(t.id(), f, e, fl, lockType(type)));
    }

    @GetMapping("/lock_top_objects")
    public ResponseEntity<Object> lockTopObjects(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                                 @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                 @RequestParam(required = false) String users, @RequestParam(required = false) String machines,
                                                 @RequestParam(required = false, defaultValue = "all") String type) {
        return run(db_id, token, from, to, users, machines, (t, f, e, fl) -> lockService.topObjects(t.id(), f, e, fl, lockType(type)));
    }

    /** 락 발생 건 하나(구간 조건 없음) - 상세 패널용. */
    @GetMapping("/lock_incident")
    public ResponseEntity<Object> lockIncident(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                               @RequestParam(name = "incident_id", required = false) String incidentId) {
        if (!authService.canAccessDb(token, db_id)) {
            return error(HttpStatus.FORBIDDEN, "해당 DB에 대한 접근 권한이 없습니다.");
        }
        if (incidentId == null || incidentId.trim().isEmpty() || incidentId.length() > 200) {
            return error(HttpStatus.BAD_REQUEST, "incident_id가 필요합니다.");
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null || !"oracle".equalsIgnoreCase(target.dbType())) {
            return error(HttpStatus.NOT_FOUND, "등록되지 않은 Oracle DB입니다.");
        }
        java.util.Map<String, Object> r = lockService.incident(target.id(), incidentId.trim());
        if (r == null) {
            return error(HttpStatus.NOT_FOUND, "락 발생 건을 찾을 수 없습니다(보관 기간이 지났을 수 있습니다).");
        }
        return ResponseEntity.ok(r);
    }

    // ------------------------------------------------------------------ 시스템 부하 (성능 분석 개편 Phase 2, 2026-10-09, 설계 2-3)
    // 수집 저장소만 읽는다(운영 DB 조회 없음). baseline=lastweek 이면 7일 전 같은 구간을 함께 준다. 응답 JSON 키는 camelCase.

    @GetMapping("/load_metrics")
    public ResponseEntity<Object> loadMetrics(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                              @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                              @RequestParam(required = false) String baseline) {
        return run(db_id, token, from, to, null, null,
                (t, f, e, fl) -> sysService.loadMetrics(t.id(), f, e, "lastweek".equals(baseline)));
    }

    @GetMapping("/load_waits")
    public ResponseEntity<Object> loadWaits(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                            @RequestParam(required = false) String baseline) {
        return run(db_id, token, from, to, null, null,
                (t, f, e, fl) -> sysService.loadWaits(t.id(), f, e, "lastweek".equals(baseline)));
    }

    // ------------------------------------------------------------------ SQL 분석 (성능 분석 개편 Phase 3, 2026-10-09, 설계 3-6)
    // 수집 저장소만 읽는다(sql_detail의 SQL 전문 단건 조회만 대상 DB). 계정·서버 필터는 적용하지 않는다(v$sqlstats에 없음).

    @GetMapping("/sql_top")
    public ResponseEntity<Object> sqlTop(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                         @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                         @RequestParam(required = false, defaultValue = "cost") String view,
                                         @RequestParam(required = false, defaultValue = "id") String group,
                                         @RequestParam(required = false, defaultValue = "50") int limit,
                                         @RequestParam(required = false, defaultValue = "lastweek") String baseline) {
        return run(db_id, token, from, to, null, null,
                (t, f, e, fl) -> sqlService.sqlTop(t, f, e, view, group, limit, "lastweek".equals(baseline)));
    }

    @GetMapping("/sql_trend")
    public ResponseEntity<Object> sqlTrend(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                           @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                           @RequestParam(name = "sql_ids", required = false) String sqlIds,
                                           @RequestParam(required = false, defaultValue = "elapsed") String metric) {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        if (sqlIds != null) {
            for (String id : sqlIds.split(",")) {
                String x = id.trim();
                if (!x.isEmpty() && x.length() <= 13 && x.matches("[A-Za-z0-9]+") && ids.size() < 20) ids.add(x);
            }
        }
        return run(db_id, token, from, to, null, null, (t, f, e, fl) -> sqlService.sqlTrend(t.id(), f, e, ids, metric));
    }

    @GetMapping("/sql_literal")
    public ResponseEntity<Object> sqlLiteral(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                             @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                             @RequestParam(name = "min_count", required = false, defaultValue = "2") int minCount) {
        return run(db_id, token, from, to, null, null, (t, f, e, fl) -> sqlService.sqlLiteral(t, f, e, minCount, true));
    }

    @GetMapping("/sql_plan_changes")
    public ResponseEntity<Object> sqlPlanChanges(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                                 @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return run(db_id, token, from, to, null, null, (t, f, e, fl) -> sqlService.sqlPlanChanges(t, f, e));
    }

    @GetMapping("/sql_detail")
    public ResponseEntity<Object> sqlDetail(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                            @RequestParam(name = "sql_id", required = false) String sqlId) {
        if (sqlId == null || !sqlId.trim().matches("[A-Za-z0-9]{1,13}")) {
            return error(HttpStatus.BAD_REQUEST, "sql_id가 필요합니다(영문·숫자 13자 이하).");
        }
        final String id = sqlId.trim();
        return run(db_id, token, from, to, null, null, (t, f, e, fl) -> sqlService.sqlDetail(t, id, f, e));
    }

    // ------------------------------------------------------------------ 오브젝트·I/O (성능 분석 개편 Phase 5, 2026-10-11, 설계 5-2)
    // 수집 저장소만 읽는다(수집은 기본 꺼짐 dbagent.perf.obj-collect-enabled). 응답 JSON 키는 camelCase. 구간은 24시간 이하.

    /** Hot 세그먼트 - sort = lr(Logical reads) | pr(Physical reads) | rlw(Row lock waits) | itl(ITL waits) | bbw(Buffer busy waits). */
    @GetMapping("/obj_segments")
    public ResponseEntity<Object> objSegments(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                              @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                              @RequestParam(required = false, defaultValue = "lr") String sort) {
        return run(db_id, token, from, to, null, null, (t, f, e, fl) -> objService.segments(t.id(), f, e, sort));
    }

    @GetMapping("/obj_files")
    public ResponseEntity<Object> objFiles(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                           @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return run(db_id, token, from, to, null, null, (t, f, e, fl) -> objService.files(t.id(), f, e));
    }

    @GetMapping("/obj_redo_switch")
    public ResponseEntity<Object> objRedoSwitch(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                                @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return run(db_id, token, from, to, null, null, (t, f, e, fl) -> objService.redo(t.id(), f, e));
    }

    @GetMapping("/obj_temp_top")
    public ResponseEntity<Object> objTempTop(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                             @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return run(db_id, token, from, to, null, null, (t, f, e, fl) -> objService.tempTop(t.id(), f, e));
    }

    // ------------------------------------------------------------------ 구간 비교 (성능 분석 개편 Phase 4, 2026-10-11, 설계 Phase 4)
    // 수집 저장소만 읽는다(신규 수집·운영 DB 조회 없음, SQL 텍스트 보충 단건 조회만 예외). A/B 각각 24시간 이하.

    @GetMapping("/compare")
    public ResponseEntity<Object> compare(@RequestParam(required = false) String db_id, @RequestParam(required = false) String token,
                                          @RequestParam(name = "a_from", required = false) String aFrom, @RequestParam(name = "a_to", required = false) String aTo,
                                          @RequestParam(name = "b_from", required = false) String bFrom, @RequestParam(name = "b_to", required = false) String bTo) {
        if (!authService.canAccessDb(token, db_id)) {
            return error(HttpStatus.FORBIDDEN, "해당 DB에 대한 접근 권한이 없습니다.");
        }
        LocalDateTime af;
        LocalDateTime at;
        LocalDateTime bf;
        LocalDateTime bt;
        try {
            if (isBlank(aFrom) || isBlank(aTo) || isBlank(bFrom) || isBlank(bTo)) {
                return error(HttpStatus.BAD_REQUEST, "a_from/a_to/b_from/b_to가 필요합니다(yyyy-MM-ddTHH:mm, DB 시각).");
            }
            af = LocalDateTime.parse(aFrom.trim());
            at = LocalDateTime.parse(aTo.trim());
            bf = LocalDateTime.parse(bFrom.trim());
            bt = LocalDateTime.parse(bTo.trim());
        } catch (DateTimeParseException e) {
            return error(HttpStatus.BAD_REQUEST, "구간 형식은 yyyy-MM-ddTHH:mm[:ss]입니다(DB 시각).");
        }
        String bad = checkRange("A", af, at);
        if (bad == null) bad = checkRange("B", bf, bt);
        if (bad != null) {
            return error(HttpStatus.BAD_REQUEST, bad);
        }
        TargetDbConfig target = configService.resolve(db_id);
        if (target == null || !"oracle".equalsIgnoreCase(target.dbType())) {
            return error(HttpStatus.NOT_FOUND, "등록되지 않은 Oracle DB입니다.");
        }
        try {
            return ResponseEntity.ok(compareService.compare(target, af, at, bf, bt));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("perf compare store query failed: {}", e.toString());
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "수집 저장소 조회 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 구간 하나의 검증 - 문제가 없으면 null, 있으면 화면에 보일 한국어 메시지. */
    private static String checkRange(String name, LocalDateTime f, LocalDateTime t) {
        if (!f.isBefore(t)) return name + " 구간의 시작 시각이 종료 시각보다 앞서야 합니다.";
        if (java.time.Duration.between(f, t).toMinutes() > AshRange.MAX_RANGE_MINUTES) {
            return name + " 구간은 최대 24시간까지 가능합니다. 구간을 줄여 주세요.";
        }
        return null;
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
        // (2026-10-09 점검 수정 A1-2) 기본 경로(source=store)에도 구간 상한을 둔다 - 예전에는 원본(oracle) 경로만 AshRange.resolve에서
        // 24시간으로 잘렸고, store 경로는 from=2000-01-01 같은 입력에 분 단위 버킷 수천만 개를 만들어 서버 메모리를 고갈시킬 수 있었다.
        // 화면(perf-analysis.js)도 24시간 초과를 이미 막고 있어 자르지 않고 400으로 알린다.
        if (java.time.Duration.between(f, t).toMinutes() > AshRange.MAX_RANGE_MINUTES) {
            return error(HttpStatus.BAD_REQUEST, "조회 구간은 최대 24시간까지 가능합니다. 구간을 줄여 주세요.");
        }
        TargetDbConfig target = configService.resolve(dbId);
        if (target == null || !"oracle".equalsIgnoreCase(target.dbType())) {
            return error(HttpStatus.NOT_FOUND, "등록되지 않은 Oracle DB입니다.");
        }
        try {
            return ResponseEntity.ok(q.run(target, f, t, AshRange.Filter.of(users, machines)));
        } catch (SQLException e) {
            return dbError(e);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (org.springframework.dao.DataAccessException e) {
            // 수집 저장소(SQLite/H2) 오류도 {"error": 한국어} 규약으로 내린다(Lock 분석 API는 저장소만 읽는다)
            log.warn("perf store query failed: {}", e.toString());
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "수집 저장소 조회 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /** DB 오류 원문(ORA-...)은 화면에 내리지 않고 로그에만 남긴다(2026-10-09 점검 수정 A1-12). */
    private static ResponseEntity<Object> dbError(SQLException e) {
        log.warn("perf analysis DB query failed: {}", e.toString());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "조회 중 DB 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
    }

    private static ResponseEntity<Object> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
