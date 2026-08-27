package com.dbagent.sqltuning;

import com.dbagent.auth.AuthService;
import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.query.ExecutionPlanService;
import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AIX 이관본: sLLM(FastAPI, WSL/GPU 필요) 연동이 없음 - analyze* 엔드포인트는 화면 인터페이스만 유지하고
 * 항상 "sLLM 서버 연동 필요" 메시지를 반환한다. quick_check/bind_capture는 모델 없이 Oracle 조회만으로
 * 동작하는 기능이라 원본과 동일하게 완전히 구현되어 있다.
 */
@RestController
@RequestMapping("/api/sqltuning")
public class SqlTuningController {

    private static final String MODEL_UNAVAILABLE_MESSAGE =
            "이 환경(AIX)에는 SQL 튜닝 sLLM 서버가 연동되어 있지 않습니다. 모델 기반 분석은 지원되지 않으며, "
                    + "\"1차 성능점검\" 버튼으로 실행계획/실측 통계만 직접 확인해주세요.";

    private final ExecutionPlanService executionPlanService;
    private final DatabaseConfigService configService;
    private final AuthService authService;

    public SqlTuningController(ExecutionPlanService executionPlanService,
                                DatabaseConfigService configService, AuthService authService) {
        this.executionPlanService = executionPlanService;
        this.configService = configService;
        this.authService = authService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody SqlTuningRequest req) {
        return ResponseEntity.ok(Maps.of("success", false, "message", MODEL_UNAVAILABLE_MESSAGE));
    }

    /** 쿼리만 입력받아 EXPLAIN PLAN으로 실행계획을 직접 조회한 뒤 분석까지 이어서 수행 (관리자 전용, 쿼리 미실행). */
    @PostMapping("/analyze_from_query")
    public ResponseEntity<Map<String, Object>> analyzeFromQuery(@RequestBody SqlTuningAutoRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "실행계획 자동 조회는 관리자만 사용할 수 있습니다."));
        }
        return ResponseEntity.ok(Maps.of("success", false, "message", MODEL_UNAVAILABLE_MESSAGE));
    }

    /**
     * 쿼리를 실제로 실행해 DBMS_XPLAN.DISPLAY_CURSOR(ALLSTATS LAST)의 실측 통계(실제 로우 수/시간/버퍼)로
     * 분석 (관리자 전용, SELECT/WITH만 허용 - 데이터 반환 없이 draining만 하지만 실제 실행은 됨).
     */
    @PostMapping("/analyze_from_query_actual")
    public ResponseEntity<Map<String, Object>> analyzeFromQueryActual(@RequestBody SqlTuningAutoRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "실제 실행 통계 분석은 관리자만 사용할 수 있습니다."));
        }
        return ResponseEntity.ok(Maps.of("success", false, "message", MODEL_UNAVAILABLE_MESSAGE));
    }

    /**
     * "실제 실행 통계로 분석"과 동일하게 쿼리를 실제 실행해 DISPLAY_CURSOR(ALLSTATS LAST) 실측치를
     * 얻지만, sLLM 호출 없이 그 결과만 그대로 반환한다 (관리자 전용, SELECT/WITH만 허용).
     * DBA가 모델 없이 직접 눈으로 훑어보는 1차 성능점검 용도 - AIX에서는 이게 유일하게 동작하는 자동 분석 기능.
     */
    @PostMapping("/quick_check")
    public ResponseEntity<Map<String, Object>> quickCheck(@RequestBody SqlTuningAutoRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "성능점검은 관리자만 사용할 수 있습니다."));
        }
        String query = req.query();
        if (query == null || Strings.isBlank(query)) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "쿼리를 입력해주세요."));
        }
        try {
            TargetDbConfig target = configService.resolve(req.dbId(), req.account());
            String plan = executionPlanService.explainActual(target, query, req.binds());
            if (plan == null || Strings.isBlank(plan)) {
                return ResponseEntity.ok(Maps.of("success", false, "message", "실행계획을 가져오지 못했습니다."));
            }
            return ResponseEntity.ok(Maps.of("success", true, "plan", plan));
        } catch (Exception e) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "성능점검 오류: " + e.getMessage()));
        }
    }

    /** V$SQL_BIND_CAPTURE에서 HASH_VALUE로 과거 실행된 바인드 값을 조회 (관리자 전용, 일괄 입력용). */
    @PostMapping("/bind_capture")
    public ResponseEntity<Map<String, Object>> bindCapture(@RequestBody BindCaptureRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "관리자만 사용할 수 있습니다."));
        }
        if (req.hashValue() == null || Strings.isBlank(req.hashValue())) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "HASH_VALUE를 입력해주세요."));
        }
        try {
            TargetDbConfig target = configService.resolve(req.dbId(), req.account());
            Map<String, String> binds = executionPlanService.fetchBindCapture(target, req.hashValue());
            if (binds.isEmpty()) {
                return ResponseEntity.ok(Maps.of("success", false,
                        "message", "V$SQL_BIND_CAPTURE에서 해당 HASH_VALUE의 바인드 값을 찾지 못했습니다 (커서가 이미 캐시에서 밀려났을 수 있습니다)."));
            }
            return ResponseEntity.ok(Maps.of("success", true, "binds", binds));
        } catch (Exception e) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "조회 오류: " + e.getMessage()));
        }
    }
}
