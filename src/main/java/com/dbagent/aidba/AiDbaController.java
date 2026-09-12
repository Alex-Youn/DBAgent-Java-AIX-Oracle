package com.dbagent.aidba;

import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/aidba")
public class AiDbaController {

    private static final String CURRENT_SQL_PROMPT_ID = "current-sql";

    private final ErrorSearchService errorSearchService;
    private final OllamaChatService ollamaChatService;

    public AiDbaController(ErrorSearchService errorSearchService, OllamaChatService ollamaChatService) {
        this.errorSearchService = errorSearchService;
        this.ollamaChatService = ollamaChatService;
    }

    @GetMapping("/error_search")
    public ResponseEntity<Map<String, Object>> errorSearch(
            @RequestParam(name = "code", required = false, defaultValue = "") String code) {
        if (Strings.isBlank(code)) {
            return ResponseEntity.badRequest().body(Maps.of("error", "No error code provided"));
        }
        return ResponseEntity.ok(errorSearchService.getErrorSolution(code));
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        return ResponseEntity.ok(ollamaChatService.chat(request.message()));
    }

    /** 좌측 프레임 상단 모델명/OpenSearch 상태 표시용(매뉴통합.md 3절) - 연결 상태 자체는 표시하지 않는다. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            Map<String, Object> result = new LinkedHashMap<>(ollamaChatService.health());
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Maps.of("success", false));
        }
    }

    /**
     * AI Current SQL 분석("성능분석" 버튼, 매뉴통합.md 2-1) - 1차 성능점검(quick_check)이 이미 얻어둔
     * 쿼리+바인드+실행계획/실측치를 그대로 sqlrestapi(promptId=current-sql)로 보내 해석을 요청한다.
     * 사내 사례를 찾는 게 아니라 눈앞의 실측치를 읽는 작업이라 RAG 검색은 타지 않는다.
     */
    @PostMapping("/current_sql/analyze")
    public ResponseEntity<Map<String, Object>> analyzeCurrentSql(@RequestBody CurrentSqlAnalyzeRequest req) {
        String query = req.query();
        String plan = req.plan();
        if (query == null || Strings.isBlank(query)) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "분석할 쿼리가 없습니다."));
        }
        if (plan == null || Strings.isBlank(plan)) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "먼저 1차 성능점검을 실행해주세요."));
        }
        try {
            String prompt = buildCurrentSqlPrompt(query, req.binds(), plan);
            String answer = ollamaChatService.askWithPrompt(CURRENT_SQL_PROMPT_ID, prompt);
            return ResponseEntity.ok(Maps.of("success", true, "answer", answer));
        } catch (Exception e) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "서버 오류: " + e.getMessage()));
        }
    }

    /** current-sql.md가 요구하는 입력 형식([분석 대상 SQL]/[바인드 변수]/[실행계획 및 실측 통계]) 그대로 조립. */
    private String buildCurrentSqlPrompt(String query, Map<String, String> binds, String plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("[분석 대상 SQL]\n").append(query).append("\n\n");
        if (binds != null && !binds.isEmpty()) {
            sb.append("[바인드 변수]\n");
            for (Map.Entry<String, String> e : binds.entrySet()) {
                sb.append(":").append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("[실행계획 및 실측 통계]\n").append(plan);
        return sb.toString();
    }
}
