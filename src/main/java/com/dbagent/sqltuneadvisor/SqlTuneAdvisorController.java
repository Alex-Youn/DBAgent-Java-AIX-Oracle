package com.dbagent.sqltuneadvisor;

import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SQL Tune Advisor 화면(AI DBA 메뉴 아래) 전용 엔드포인트. Oracle 접속/실행 권한과 무관하게 사용자가
 * 입력한 텍스트를 그대로 GPU 서버의 RAG API로 전달하므로, SqlTuningController의 자동 실행계획 조회
 * 기능들과 달리 관리자 제한을 두지 않는다(AiDbaController의 /chat과 동일한 성격).
 * non-AIX DBAgent-Java 쪽과 동일한 연동을 RestTemplate(Java 8 호환) 기반으로 이관.
 */
@RestController
@RequestMapping("/api/sqltuneadvisor")
public class SqlTuneAdvisorController {

    private static final Logger log = LoggerFactory.getLogger(SqlTuneAdvisorController.class);

    private final SqlTuneAdvisorService sqlTuneAdvisorService;
    private final ObjectMapper mapper = new ObjectMapper();
    /**
     * 스트리밍 줄의 미디어 타입 - charset을 명시한다. MediaType.APPLICATION_NDJSON에는 charset이 없어 문자열 변환기의 기본값에 기대게 되는데,
     * AIX는 JVM 기본 문자셋이 UTF-8이 아닐 수 있다. 화면은 UTF-8로만 해석하므로(TextDecoder) 한글이 깨지지 않게 여기서 고정한다(2026-10-12 코드 점검).
     */
    private static final MediaType NDJSON_UTF8 = new MediaType("application", "x-ndjson", StandardCharsets.UTF_8);
    // 스트리밍 응답은 LLM이 답변을 다 생성할 때까지(수십 초~수 분) 스레드를 붙잡는다 - 요청 스레드나 공용 스케줄러 스레드가 아니라
    // 전용 스레드에서 돌린다. 데몬 스레드라 앱 종료를 막지 않는다.
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tune-advisor-stream");
        t.setDaemon(true);
        return t;
    });

    public SqlTuneAdvisorController(SqlTuneAdvisorService sqlTuneAdvisorService) {
        this.sqlTuneAdvisorService = sqlTuneAdvisorService;
    }

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody SqlTuneAdvisorRequest req) {
        String query = req.query();
        if (query == null || Strings.isBlank(query)) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "질의할 SQL/질문을 입력해주세요."));
        }
        try {
            Map<String, Object> result = sqlTuneAdvisorService.query(query);
            return ResponseEntity.ok(Maps.of(
                    "success", true,
                    "answer", result.get("answer"),
                    "references", result.get("references")));
        } catch (Exception e) {
            return ResponseEntity.ok(Maps.of("success", false, "message", modelErrorMessage(e)));
        }
    }

    /**
     * /query와 같은 질의를 sqlrestapi의 /api/query/stream(NDJSON)으로 그대로 중계한다 - 토큰이 도착하는 대로
     * {"chunk": "..."} 줄을 브라우저에 흘려보내 답변이 한 글자씩 생성되는 것처럼 보이게 한다. 마지막 줄은
     * {"references": [...]}. 연결 실패 등으로 스트리밍을 아예 시작 못하면 {"error": "..."} 한 줄만 보내고 끝낸다 -
     * /query의 success:false와 같은 안내문을 쓴다. (메인 2026-09-11 구현을 2026-10-12에 AIX로 옮김)
     */
    @PostMapping(value = "/query/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseBodyEmitter queryStream(@RequestBody SqlTuneAdvisorRequest req) {
        final ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        final String query = req.query();
        if (query == null || Strings.isBlank(query)) {
            sendErrorLine(emitter, "질의할 SQL/질문을 입력해주세요.");
            emitter.complete();
            return emitter;
        }
        streamExecutor.execute(() -> {
            // 브라우저가 중간에 끊으면(화면 이동·새로고침) send가 실패한다 - 흔한 일이라 "조회 실패" 경고와 스택을 남기지 않는다(2026-10-12 코드 점검)
            final boolean[] clientGone = {false};
            try {
                sqlTuneAdvisorService.queryStream(query, line -> {
                    try {
                        emitter.send(line + "\n", NDJSON_UTF8);
                    } catch (IOException | RuntimeException e) {
                        clientGone[0] = true;
                        throw new IllegalStateException("client disconnected", e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                if (clientGone[0]) {
                    log.debug("SQL Tune Advisor 스트리밍: 클라이언트 연결 종료");
                } else {
                    sendErrorLine(emitter, modelErrorMessage(e));
                }
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 이미 끊긴 연결
                }
            }
        });
        return emitter;
    }

    private void sendErrorLine(ResponseBodyEmitter emitter, String message) {
        try {
            emitter.send(mapper.writeValueAsString(Collections.singletonMap("error", message)) + "\n", NDJSON_UTF8);
        } catch (Exception ignored) {
            // 클라이언트가 이미 연결을 끊었을 뿐이라 조용히 무시 - complete()는 호출자가 마저 부른다.
        }
    }

    /** SqlTuningController.modelErrorMessage()와 동일한 이유(연결 실패 예외 문자열을 그대로 노출하지 않음). */
    private String modelErrorMessage(Exception e) {
        Throwable cause = e;
        // /query(RestTemplate)는 ResourceAccessException, /query/stream(HttpURLConnection)은 java.net 예외로 연결 실패가 올라온다
        while (cause != null && !(cause instanceof ResourceAccessException)
                && !(cause instanceof java.net.ConnectException) && !(cause instanceof java.net.SocketTimeoutException)
                && !(cause instanceof java.net.UnknownHostException) && !(cause instanceof java.net.NoRouteToHostException)) {
            cause = cause.getCause();
        }
        if (cause != null) {
            log.warn("SQL Tune Advisor 서버 연결 실패 (url={}): {}", sqlTuneAdvisorService.apiUrl(), e.toString());
            return "SQL Tune Advisor 서버(" + sqlTuneAdvisorService.apiUrl() + ")에 연결할 수 없습니다. "
                    + "서버가 켜져 있는지, 이 호스트에서 도달 가능한 주소인지 확인해주세요.";
        }
        log.warn("SQL Tune Advisor 조회 실패", e);
        return "조회 오류: " + e.getMessage();
    }
}
