package com.dbagent.sqltuneadvisor;

import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;

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

    /** SqlTuningController.modelErrorMessage()와 동일한 이유(연결 실패 예외 문자열을 그대로 노출하지 않음). */
    private String modelErrorMessage(Exception e) {
        Throwable cause = e;
        while (cause != null && !(cause instanceof ResourceAccessException)) {
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
