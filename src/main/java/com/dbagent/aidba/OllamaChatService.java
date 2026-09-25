package com.dbagent.aidba;

import com.dbagent.util.Strings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL Tune Advisor GPU 서버의 sqlrestapi를 호출하는 서비스 - Ollama(11434)에는 더 이상 직접 붙지
 * 않는다. GPU 서버 방화벽이 REST API 포트(9300, sqlrestapi) 하나만 열어주는 구성으로 확정되면서
 * (2026-09-11) Ollama 포트는 AIX에서 도달 불가능해졌다 - sqlrestapi(non-AIX DBAgent-Java의
 * RAGController)가 그 앞단의 프록시 역할을 대신한다.
 *
 * 시스템 프롬프트는 이제 이 서비스가 문자열로 들고 있지 않는다 - sqlrestapi 쪽에 promptId별 파일
 * (prompts/chatbot.md)로 옮겨서 SQL Tune Advisor(promptId=tuning)와 구조를 통일했다(2026-09-11).
 *
 * 하이브리드 검색(2026-09-13, non-AIX 쪽 포팅): ErrorSearchService가 ORA 코드 정확 일치/키워드로
 * 컨텍스트를 찾으면 ask()(검색 없는 /api/chat)로 바로 답변을 받고, 못 찾으면 askWithSemanticSearch()로
 * sqlrestapi의 OpenSearch 시맨틱 검색(error_dictionary 인덱스)에 맡긴다 - AiDbaController가 이 둘을
 * 고른다. AIX는 Java 8/RestTemplate 기반이라 non-AIX(HttpClient)와 통신 방식만 다르고 API 계약(요청
 * 필드 prompt, 응답 필드 answer/context_used)은 동일하게 맞췄다.
 */
@Service
public class OllamaChatService {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatService.class);

    private static final String PROMPT_ID = "chatbot";
    // ORA 오류 코드가 없는 질문용 system prompt(sqlrestapi prompts/chatbot-general.md, 원본은 저장소 aidba-prompts/).
    private static final String GENERAL_PROMPT_ID = "chatbot-general";
    private static final String ERROR_INDEX = "error_dictionary";

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 2026-09-14: aidba.ollama.url + sqltuneadvisor.api.url 는 결국 같은 sqlrestapi 서버를 가리키므로
    // aidba.restapi.url 하나로 통합했다. 스킴을 빠뜨려도 되게 normalizeUrl()에서 http://를 보정한다.
    @Value("${aidba.restapi.url}")
    private String ollamaUrl;

    @Value("${aidba.ollama.timeout-ms:30000}")
    private int timeoutMs;

    @PostConstruct
    private void normalizeUrl() {
        if (ollamaUrl != null && !ollamaUrl.contains("://")) {
            ollamaUrl = "http://" + ollamaUrl;
        }
    }

    /** 연결 실패 안내에 대상 주소를 함께 보여주려고 컨트롤러가 읽는다(SqlTuningService.apiUrl()과 같은 용도). */
    public String apiUrl() {
        return ollamaUrl;
    }

    /**
     * SqlTuningController의 modelErrorMessage()와 같은 이유로 둔다 - 이 문자열은 화면에 그대로 빨간
     * 글씨로 나가므로 원시 예외("I/O error on POST request ..." 등)를 흘리면 안 된다. 서버가 안 떠
     * 있는 건 폐쇄망 운영 중 흔한 상황이라 다음 행동을 알려주는 안내문으로 바꾸고 원인은 로그로만
     * 남긴다. sqlrestapi를 호출하는 화면들(AI Current SQL 분석, AI SQL 작성기)이 공유한다.
     * AIX는 RestTemplate 기반이라 연결 실패가 ResourceAccessException으로 온다(원본의 ConnectException 아님).
     */
    public String friendlyErrorMessage(String what, Exception e) {
        Throwable cause = e;
        while (cause != null && !(cause instanceof ResourceAccessException)) {
            cause = cause.getCause();
        }
        if (cause != null) {
            log.warn("sqlrestapi 연결 실패 (url={}): {}", ollamaUrl, e.toString());
            return "AI 서버(" + ollamaUrl + ")에 연결할 수 없습니다. "
                    + "서버가 켜져 있는지, 이 호스트에서 도달 가능한 주소인지 확인해주세요.";
        }
        log.warn("sqlrestapi {} 실패", what, e);
        return what + " 중 오류가 발생했습니다: " + e.getMessage();
    }

    /** 검색 없이 컨텍스트(caller가 이미 조립)를 그대로 붙여 답변만 받는다 - ORA 코드 정확 일치/키워드 경로. */
    public String ask(String prompt, String context) {
        String finalPrompt = "[참고 자료]\n" + context + "\n\n[사용자 질문]\n" + prompt
                + "\n\n반드시 한국어로 답하세요.";
        return askWithPrompt(PROMPT_ID, finalPrompt);
    }

    /**
     * ORA 오류 코드가 없는 질문(체크리스트 6-1) - 사내 오류 사전 검색 없이 모델 자체 지식으로 답한다. 허용 범위
     * (일반 개념·버전별 설명, DB 무관 주제 포함)와 금지 사항(위험 명령 생성, 확인 안 된 버그/패치/MOS 번호 인용)은
     * chatbot-general.md 에 있다. 예전엔 이런 질문에도 의미상 가까운 오류 사례를 [참고 자료]로 붙여, "참고 자료에
     * 없으면 지어내지 말라"는 원칙 때문에 답을 피하는 경우가 많았다.
     */
    public String askGeneral(String prompt) {
        return askWithPrompt(GENERAL_PROMPT_ID, "[사용자 질문]\n" + prompt + "\n\n반드시 한국어로 답하세요.");
    }

    /**
     * RAG 검색 없이 완성된 프롬프트를 그대로 sqlrestapi(promptId별 시스템 프롬프트)에 던진다. AI Current
     * SQL 분석(promptId=current-sql)/AI SQL 작성기(promptId=sql-writer, 매뉴통합.md 2-1/2-3)처럼 사내
     * 사례를 찾는 게 아니라 이미 가진 데이터를 해석/가공하는 화면들이 공용으로 쓴다.
     */
    public String askWithPrompt(String promptId, String finalPrompt) {
        String answer = callChatApiWithPromptId(promptId, finalPrompt);
        return Strings.isBlank(answer) ? "답변을 생성하지 못했습니다." : answer;
    }

    /**
     * 좌측 프레임 상단 모델명 표시용(매뉴통합.md 3절). sqlrestapi의 isConnected()가 타임아웃 없이
     * OpenSearch 응답을 무한정 기다릴 수 있어(sqlrestapi 개선 후보, 이 저장소 밖) 여기서라도 짧은
     * 타임아웃(5초)을 걸어 화면이 멈추지 않게 한다 - 실패하면 호출자가 표시를 생략한다.
     */
    public Map<String, Object> health() {
        JsonNode root = getForJson(ollamaUrl + "/health", 5000);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("llm_model", root.path("llm_model").asText(""));
        result.put("vector_db", root.path("vector_db").asText(""));
        return result;
    }

    private String callChatApiWithPromptId(String promptId, String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("promptId", promptId);
        body.put("prompt", prompt);

        JsonNode root = postForJson(ollamaUrl + "/api/chat", body);
        JsonNode answer = root.path("answer");
        return answer.isMissingNode() ? "" : answer.asText();
    }

    /**
     * ORA 코드 정확 일치/키워드로 아무것도 못 찾았을 때의 폴백 - sqlrestapi가 bge-m3로 질문을
     * 임베딩해 error_dictionary 인덱스에서 의미상 가까운 사례를 찾고, 그걸 컨텍스트로 붙여 직접
     * 답변까지 생성해 돌려준다. 반환 맵: {"answer": String, "references": List<Map<source,content>>}.
     */
    public Map<String, Object> askWithSemanticSearch(String userMessage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("promptId", PROMPT_ID);
        body.put("index", ERROR_INDEX);
        body.put("query", userMessage);
        body.put("n_results", 3);

        JsonNode root = postForJson(ollamaUrl + "/api/query", body);
        String answer = root.path("answer").asText("");

        List<Map<String, String>> references = new ArrayList<>();
        for (JsonNode ref : root.path("references")) {
            Map<String, String> r = new LinkedHashMap<>();
            r.put("source", ref.path("source").asText(""));
            r.put("content", ref.path("content").asText(""));
            references.add(r);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", Strings.isBlank(answer) ? "답변을 생성하지 못했습니다." : answer);
        result.put("references", references);
        return result;
    }

    private JsonNode getForJson(String url, int timeoutMsOverride) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMsOverride);
        factory.setReadTimeout(timeoutMsOverride);
        RestTemplate restTemplate = new RestTemplate(factory);

        String raw = restTemplate.getForObject(url, String.class);
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("sqlrestapi 응답 파싱 실패: " + e.getMessage(), e);
        }
    }

    private JsonNode postForJson(String url, Object body) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        RestTemplate restTemplate = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);

        String raw = restTemplate.postForObject(url, entity, String.class);
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("sqlrestapi 응답 파싱 실패: " + e.getMessage(), e);
        }
    }
}
