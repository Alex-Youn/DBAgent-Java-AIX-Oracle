package com.dbagent.aidba;

import com.dbagent.util.Maps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ollama 호출 서비스. 원래는 java.net.http.HttpClient(Java 11+ 전용)로 구현되어 있었으나
 * 이 프로젝트가 Java 8로 고정되면서 RestTemplate(spring-boot-starter-web에 이미 포함, Java 8 호환)으로 재작성.
 * aidba.ollama.url은 로컬뿐 아니라 원격 GPU 서버를 가리켜도 됨 - 이 서비스는 순수 HTTP(JSON) 호출만 하므로
 * Ollama가 실제로 어느 머신에서 도는지와는 무관함 (AIX가 아닌 별도 GPU 서버에 Ollama를 두고 네트워크로 호출하는 구성 가능).
 */
@Service
public class OllamaChatService {

    private static final Pattern ORA_CODE_PATTERN = Pattern.compile("ORA-\\d{4,5}", Pattern.CASE_INSENSITIVE);

    private final ErrorSearchService errorSearchService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${aidba.ollama.url}")
    private String ollamaUrl;

    @Value("${aidba.ollama.model}")
    private String ollamaModel;

    @Value("${aidba.ollama.timeout-ms:30000}")
    private int timeoutMs;

    public OllamaChatService(ErrorSearchService errorSearchService) {
        this.errorSearchService = errorSearchService;
    }

    public Map<String, Object> chat(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return Maps.of("error", "메시지가 비어 있습니다.");
        }

        String context = buildContext(userMessage);
        String prompt = (context == null)
                ? userMessage
                : context + "\n\n위 정보를 참고해서 다음 질문에 답해줘: " + userMessage;

        try {
            String answer = callChatApi(prompt);
            if (answer == null) {
                // 구버전 Ollama(/api/chat 없음) 대비 /api/generate 폴백
                answer = callGenerateApi(prompt);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", answer);
            result.put("context_used", context != null);
            return result;
        } catch (ResourceAccessException e) {
            return Maps.of("error", "Ollama 서버(" + ollamaUrl + ")에 연결할 수 없습니다: " + e.getMessage());
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

    private String callChatApi(String prompt) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", ollamaModel);
        body.put("messages", Collections.singletonList(message));
        body.put("stream", false);

        try {
            JsonNode root = postForJson(ollamaUrl + "/api/chat", body);
            JsonNode content = root.path("message").path("content");
            return content.isMissingNode() ? null : content.asText();
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    private String callGenerateApi(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", ollamaModel);
        body.put("prompt", prompt);
        body.put("stream", false);

        JsonNode root = postForJson(ollamaUrl + "/api/generate", body);
        JsonNode response = root.path("response");
        return response.isMissingNode() ? "" : response.asText();
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
            throw new IllegalStateException("Ollama 응답 파싱 실패: " + e.getMessage(), e);
        }
    }
}
