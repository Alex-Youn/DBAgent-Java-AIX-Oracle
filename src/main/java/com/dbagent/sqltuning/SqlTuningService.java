package com.dbagent.sqltuning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls the QLoRA-finetuned Qwen2.5-Coder-7B inference server (FastAPI, New_sLLM/serve/api_server.py)
 * over plain HTTP/JSON. Same contract as the non-AIX DBAgent-Java's SqlTuningService, but rewritten
 * with RestTemplate instead of java.net.http.HttpClient (Java 11+ only) because this project is fixed
 * to Java 8 - same reasoning as OllamaChatService's RestTemplate rewrite.
 *
 * sqltuning.api.url doesn't have to point at localhost - this service only does plain HTTP(JSON), so
 * it works the same whether the FastAPI sLLM server runs on this machine, a GPU box on the LAN, or a
 * WSL instance on a developer's machine reachable over the network (see OllamaChatService's aidba.ollama.url
 * for the same pattern). It does NOT work if this AIX host has no network path to wherever that server
 * actually runs - verify reachability (e.g. curl <url>/health from this host) before relying on it.
 */
@Service
public class SqlTuningService {

    // 기본값을 반드시 둘 것. 이 속성은 application.properties 에만 있는데 그 파일은 환경마다 값이 달라
    // 커밋 대상에서 빠져 있다(2026-09-04 결정). 기본값이 없으면 속성이 없는 환경에서 플레이스홀더 해석에
    // 실패해 SQL 튜닝 기능만이 아니라 **앱 전체가 기동하지 못한다**.
    @Value("${sqltuning.api.url:http://localhost:8010}")
    private String apiUrl;

    @Value("${sqltuning.api.timeout-ms:180000}")
    private int timeoutMs;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 연결 실패 안내에 대상 주소를 함께 보여주려고 컨트롤러가 읽는다. */
    public String apiUrl() {
        return apiUrl;
    }

    public String analyze(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(timeoutMs);
        RestTemplate restTemplate = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);

        String raw;
        try {
            raw = restTemplate.postForObject(apiUrl + "/generate", entity, String.class);
        } catch (HttpStatusCodeException e) {
            HttpStatus status = e.getStatusCode();
            throw new IllegalStateException("SQL 튜닝 모델 서버가 HTTP " + status.value() + "를 반환했습니다.", e);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("SQL 튜닝 모델 서버 응답 파싱 실패: " + e.getMessage(), e);
        }
        String answer = root.path("response").asText("");
        return answer.trim().isEmpty() ? "답변을 생성하지 못했습니다." : answer;
    }
}
