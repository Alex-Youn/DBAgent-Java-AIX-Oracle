package com.dbagent.aidba;

import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * 하이브리드 검색(2026-09-13, non-AIX 쪽 포팅): ORA 코드 정확 일치/키워드(ErrorSearchService.retrieveDocs,
     * H2 LIKE)로 뭔가 찾으면 그걸 그대로 컨텍스트로 붙여 빠르게 답변한다(짧은 문자열인 에러 코드는
     * 임베딩 유사도보다 정확 일치가 훨씬 신뢰도가 높다). 아무것도 못 찾으면(에러 코드 없이 증상만
     * 설명하는 자연어 질문 등) sqlrestapi의 OpenSearch 시맨틱 검색(error_dictionary 인덱스)으로
     * 폴백한다 - 둘 다 실패하면 LLM이 참고 자료 없이 일반 지식으로만 답한다.
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest req) {
        String prompt = req.prompt();
        if (prompt == null || Strings.isBlank(prompt)) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "질문이 없습니다."));
        }
        try {
            List<String> docs = errorSearchService.retrieveDocs(prompt);

            Map<String, Object> body = new LinkedHashMap<>();
            if (!docs.isEmpty()) {
                String context = String.join("\n\n", docs);
                String answer = ollamaChatService.ask(prompt, context);
                body.put("success", true);
                body.put("answer", answer);
                body.put("context_used", context);
            } else {
                Map<String, Object> result = ollamaChatService.askWithSemanticSearch(prompt);
                body.put("success", true);
                body.put("answer", result.get("answer"));
                body.put("context_used", formatSemanticContext(result));
            }
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.ok(Maps.of("success", false, "message", ollamaChatService.friendlyErrorMessage("AI DBA 챗봇 호출", e)));
        }
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
            String prompt = buildCurrentSqlPrompt(query, req.binds(), plan, req.previousContext(), req.followUpQuestion());
            String answer = ollamaChatService.askWithPrompt(CURRENT_SQL_PROMPT_ID, prompt);
            return ResponseEntity.ok(Maps.of("success", true, "answer", answer));
        } catch (Exception e) {
            return ResponseEntity.ok(Maps.of("success", false, "message", ollamaChatService.friendlyErrorMessage("AI 분석", e)));
        }
    }

    /**
     * current-sql.md가 요구하는 입력 형식([분석 대상 SQL]/[바인드 변수]/[실행계획 및 실측 통계]/[DBA 추가 요청])
     * 그대로 조립. previousContext(이전 분석/후속문답 누적본)가 있으면 [이전 분석 결과]로 함께 실어,
     * followUpQuestion을 프롬프트가 이미 정의해둔 [DBA 추가 요청] 블록에 담는다(후속질문, 2026-09-15).
     */
    private String buildCurrentSqlPrompt(String query, Map<String, String> binds, String plan,
                                          String previousContext, String followUpQuestion) {
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
        if (previousContext != null && !Strings.isBlank(previousContext)) {
            sb.append("\n\n[이전 분석 결과]\n").append(previousContext);
        }
        if (followUpQuestion != null && !Strings.isBlank(followUpQuestion)) {
            sb.append("\n\n[DBA 추가 요청]\n").append(followUpQuestion);
        }
        return sb.toString();
    }

    /**
     * app.js는 context_used를 "첨부 문서"로 그대로 펼쳐 보여주므로(있으면 토글 노출, 없으면 숨김),
     * 시맨틱 검색 결과도 정확 일치 경로와 같은 문자열 형태로 맞춰준다 - 프론트엔드는 이 컨텍스트가
     * 정확 일치로 왔는지 검색으로 왔는지 몰라도 된다.
     */
    @SuppressWarnings("unchecked")
    private String formatSemanticContext(Map<String, Object> result) {
        Object refsObj = result.get("references");
        if (!(refsObj instanceof List)) {
            return null;
        }
        List<Map<String, String>> refs = (List<Map<String, String>>) refsObj;
        if (refs.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> ref : refs) {
            sb.append("[").append(ref.get("source")).append("]\n").append(ref.get("content")).append("\n\n");
        }
        return sb.toString();
    }
}
