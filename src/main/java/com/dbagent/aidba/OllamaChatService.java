package com.dbagent.aidba;

import com.dbagent.util.Maps;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL Tune Advisor GPU 서버의 sqlrestapi를 호출하는 서비스 - Ollama(11434)에는 더 이상 직접 붙지
 * 않는다. GPU 서버 방화벽이 REST API 포트(9300, sqlrestapi) 하나만 열어주는 구성으로 확정되면서
 * (2026-09-11) Ollama 포트는 AIX에서 도달 불가능해졌다 - sqlrestapi(non-AIX DBAgent-Java의
 * RAGController)가 그 앞단의 프록시 역할을 대신한다.
 *
 * 시스템 프롬프트는 이제 이 서비스가 문자열로 들고 있지 않는다 - sqlrestapi 쪽에 promptId별 파일
 * (prompts/chatbot.md)로 옮겨서 SQL Tune Advisor(promptId=tuning)와 구조를 통일했다(2026-09-11).
 *
 * 하이브리드 검색: buildContext()가 ORA 코드 정확 일치로 컨텍스트를 찾으면 검색 없는 /api/chat으로
 * 바로 답변을 받고, 못 찾으면(코드가 없거나 사전에 없는 코드) sqlrestapi의 OpenSearch 시맨틱 검색
 * (error_dictionary 인덱스)으로 폴백한다.
 */
@Service
public class OllamaChatService {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatService.class);

    private static final Pattern ORA_CODE_PATTERN = Pattern.compile("ORA-\\d{4,5}", Pattern.CASE_INSENSITIVE);
    private static final String PROMPT_ID = "chatbot";
    private static final String ERROR_INDEX = "error_dictionary";

    private final ErrorSearchService errorSearchService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${aidba.ollama.url}")
    private String ollamaUrl;

    @Value("${aidba.ollama.timeout-ms:30000}")
    private int timeoutMs;

    public OllamaChatService(ErrorSearchService errorSearchService) {
        this.errorSearchService = errorSearchService;
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

    public Map<String, Object> chat(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return Maps.of("error", "메시지가 비어 있습니다.");
        }

        try {
            String context = buildContext(userMessage);
            if (context != null) {
                String prompt = context + "\n\n위 정보를 참고해서 다음 질문에 답해줘: " + userMessage;
                String answer = callChatApi(prompt);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("answer", answer);
                result.put("context_used", true);
                return result;
            }
            return callQueryApi(userMessage);
        } catch (ResourceAccessException e) {
            return Maps.of("error", "sqlrestapi(" + ollamaUrl + ")에 연결할 수 없습니다: " + e.getMessage());
        } catch (Exception e) {
            return Maps.of("error", "AI DBA 챗봇 호출 중 오류 발생: " + e.getMessage());
        }
    }

    /** RAG-lite: 메시지에 ORA 에러코드가 있으면 error_dictionary에서 찾아 컨텍스트로 붙여줌. */
    private String buildContext(String userMessage) {
        Matcher m = ORA_CODE_PATTERN.matcher(userMessage);
        if (!m.find()) {
            return null;
        }
        String code = m.group().toUpperCase(Locale.ROOT);
        Map<String, Object> lookup = errorSearchService.getErrorSolution(code);
        if (!Boolean.TRUE.equals(lookup.get("found"))) {
            return null;
        }
        return "[" + code + "]\n원인: " + lookup.get("cause")
                + "\n조치: " + lookup.get("action")
                + "\n참고 쿼리/로그: " + lookup.get("query_or_log");
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

    private String callChatApi(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("promptId", PROMPT_ID);
        body.put("prompt", prompt);

        JsonNode root = postForJson(ollamaUrl + "/api/chat", body);
        JsonNode answer = root.path("answer");
        return answer.isMissingNode() ? "" : answer.asText();
    }

    /**
     * ORA 코드를 못 찾았을 때의 폴백 - sqlrestapi가 bge-m3로 질문을 임베딩해 error_dictionary
     * 인덱스에서 의미상 가까운 사례를 찾고, 그걸 컨텍스트로 붙여 직접 답변까지 생성해 돌려준다.
     */
    private Map<String, Object> callQueryApi(String userMessage) {
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

        String contextUsed = null;
        if (!references.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, String> r : references) {
                sb.append("[").append(r.get("source")).append("]\n").append(r.get("content")).append("\n\n");
            }
            contextUsed = sb.toString();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer.isEmpty() ? "답변을 생성하지 못했습니다." : answer);
        result.put("context_used", contextUsed);
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
