package com.dbagent.sqltuneadvisor;

import com.dbagent.settings.SettingKey;
import com.dbagent.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
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

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Calls the SQL Tune Advisor RAG server (sqltune-rag-java / RAGController, GPU 서버에서 Ollama(bge-m3)로
 * 질의를 임베딩하고 OpenSearch에서 사내 튜닝 사례를 검색한 뒤 qwen3-coder:30b로 분석까지 수행한다) over
 * plain HTTP/JSON. Same contract as the non-AIX DBAgent-Java's SqlTuneAdvisorService, but rewritten with
 * RestTemplate instead of java.net.http.HttpClient (Java 11+ only) - same reasoning as SqlTuningService's
 * RestTemplate rewrite (이 프로젝트는 Java 8 고정).
 */
@Service
public class SqlTuneAdvisorService {

    // 환경설정 DB화 P3-2(2026-10-04): 이 클래스가 쓰던 @Value 설정을 SettingsService 조회로 옮겼다(저장 즉시 반영).
    @Autowired
    private SettingsService settings;

    /** 서버 주소 - 스킴(http://)이 없으면 붙인다(예전 @PostConstruct 정규화와 같은 규칙). 설정이 바뀌면 다음 호출부터 새 주소를 쓴다. */
    private String apiBase() {
        String u = settings.getString(SettingKey.AIDBA_RESTAPI_URL);
        return (u != null && !u.contains("://")) ? "http://" + u : u;
    }

    // 2026-09-14: aidba.ollama.url + sqltuneadvisor.api.url 는 결국 같은 sqlrestapi 서버를 가리키므로
    // aidba.restapi.url 하나로 통합했다. 스킴을 빠뜨려도 되게 normalizeUrl()에서 http://를 보정한다.


    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 연결 실패 안내에 대상 주소를 함께 보여주려고 컨트롤러가 읽는다. */
    public String apiUrl() {
        return apiBase();
    }

    public Map<String, Object> query(String query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("n_results", 3);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(settings.getInt(SettingKey.SQLTUNEADVISOR_API_TIMEOUT_MS));
        RestTemplate restTemplate = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);

        String raw;
        try {
            raw = restTemplate.postForObject(apiBase() + "/api/query", entity, String.class);
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

    /**
     * sqlrestapi의 /api/query/stream(NDJSON)을 그대로 중계한다. 한 줄이 곧 한 JSON 객체 -
     * {"chunk": "...", "done": false} 가 토큰마다 오다가 마지막에 {"references": [...]} 한 줄로 끝난다.
     * 이 메서드는 그 줄들을 파싱하지 않고 도착하는 대로 onLine으로 넘겨주기만 한다.
     *
     * <p>메인은 java.net.http.HttpClient(Java 11+)의 BodyHandlers.ofLines()를 쓰지만 이 프로젝트는 Java 8이라
     * HttpURLConnection의 응답 스트림을 한 줄씩 읽는다. /query가 쓰는 RestTemplate은 응답 본문을 다 받은 뒤에
     * 넘겨주므로 스트리밍에 쓸 수 없다 (2026-10-12: 스트리밍이 메인에만 있고 AIX에 없어 폐쇄망에서는 답변이 한 번에 보였다).</p>
     * 응답 대기 시간(sqltuneadvisor.api.timeout-ms)은 여기서는 "다음 줄이 올 때까지"의 최대 대기로 적용된다.
     */
    public void queryStream(String query, Consumer<String> onLine) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("n_results", 3);
        body.put("stream", true);
        byte[] payload = objectMapper.writeValueAsBytes(body);

        HttpURLConnection conn = (HttpURLConnection) new URL(apiBase() + "/api/query/stream").openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(settings.getInt(SettingKey.SQLTUNEADVISOR_API_TIMEOUT_MS));
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int status = conn.getResponseCode();
            if (status >= 400) {
                throw new IOException("SQL Tune Advisor 서버가 HTTP " + status + "를 반환했습니다.");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onLine.accept(line);
                }
            }
        } finally {
            // 브라우저가 중간에 끊어 onLine이 예외를 던진 경우에도 GPU 서버 쪽 연결을 닫아 생성을 멈추게 한다
            conn.disconnect();
        }
    }
}
