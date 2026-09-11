package com.dbagent.sqltuneadvisor;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls the SQL Tune Advisor RAG server (sqltune-rag-java / RAGController, GPU 서버에서 Ollama(bge-m3)로
 * 질의를 임베딩하고 OpenSearch에서 사내 튜닝 사례를 검색한 뒤 qwen3-coder:30b로 분석까지 수행한다) over
 * plain HTTP/JSON. Same contract as the non-AIX DBAgent-Java's SqlTuneAdvisorService, but rewritten with
 * RestTemplate instead of java.net.http.HttpClient (Java 11+ only) - same reasoning as SqlTuningService's
 * RestTemplate rewrite (이 프로젝트는 Java 8 고정).
 */
@Service
public class SqlTuneAdvisorService {

    @Value("${sqltuneadvisor.api.url:http://localhost:9300}")
    private String apiUrl;

    @Value("${sqltuneadvisor.api.timeout-ms:180000}")
    private int timeoutMs;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 연결 실패 안내에 대상 주소를 함께 보여주려고 컨트롤러가 읽는다. */
    public String apiUrl() {
        return apiUrl;
    }

    public Map<String, Object> query(String query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("n_results", 3);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(timeoutMs);
        RestTemplate restTemplate = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);

        String raw;
        try {
            raw = restTemplate.postForObject(apiUrl + "/api/query", entity, String.class);
        } catch (HttpStatusCodeException e) {
            HttpStatus status = e.getStatusCode();
            throw new IllegalStateException("SQL Tune Advisor 서버가 HTTP " + status.value() + "를 반환했습니다.", e);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("SQL Tune Advisor 서버 응답 파싱 실패: " + e.getMessage(), e);
        }
        String answer = root.path("answer").asText("");

        List<Map<String, String>> references = new ArrayList<>();
        for (JsonNode ref : root.path("references")) {
            Map<String, String> r = new LinkedHashMap<>();
            r.put("source", ref.path("source").asText(""));
            r.put("content", ref.path("content").asText(""));
            references.add(r);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer.trim().isEmpty() ? "답변을 생성하지 못했습니다." : answer);
        result.put("references", references);
        return result;
    }
}
