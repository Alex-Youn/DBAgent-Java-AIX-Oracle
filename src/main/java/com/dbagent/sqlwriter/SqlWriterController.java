package com.dbagent.sqlwriter;

import com.dbagent.aidba.OllamaChatService;
import com.dbagent.auth.AuthService;
import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * AI SQL 작성기 화면(매뉴통합.md 2-3) 전용 엔드포인트.
 * - table_info: 테이블 구조 조회는 Oracle 접속이 필요하므로 SqlTuningController 등과 같이 관리자 전용.
 * - generate: 이미 조회해 둔 구조 데이터 + 자연어 요청만으로 LLM을 호출하며 DB에 다시 붙지 않으므로
 *   AiDbaController의 /chat과 같은 성격 - 관리자 제한 없음.
 */
@RestController
@RequestMapping("/api/sqlwriter")
public class SqlWriterController {

    private static final String PROMPT_ID = "sql-writer";

    private final TableInfoService tableInfoService;
    private final DatabaseConfigService configService;
    private final AuthService authService;
    private final OllamaChatService ollamaChatService;

    public SqlWriterController(TableInfoService tableInfoService, DatabaseConfigService configService,
                                AuthService authService, OllamaChatService ollamaChatService) {
        this.tableInfoService = tableInfoService;
        this.configService = configService;
        this.authService = authService;
        this.ollamaChatService = ollamaChatService;
    }

    @PostMapping("/table_info")
    public ResponseEntity<Map<String, Object>> tableInfo(@RequestBody TableInfoRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "테이블 구조 조회는 관리자만 사용할 수 있습니다."));
        }
        String tableName = req.tableName();
        if (tableName == null || Strings.isBlank(tableName)) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "테이블명을 입력해주세요."));
        }
        TargetDbConfig target = configService.resolve(req.dbId(), req.account());
        if (target == null) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "등록되지 않은 DB입니다."));
        }
        try {
            Map<String, Object> table = tableInfoService.fetchTableInfo(target, tableName);
            if (table == null) {
                return ResponseEntity.ok(Maps.of("success", false,
                        "message", "테이블 " + tableName.trim().toUpperCase()
                                + "을(를) 찾지 못했습니다 (테이블명을 확인하거나 접속 계정 소유 테이블인지 확인하세요)."));
            }
            return ResponseEntity.ok(Maps.of("success", true, "table", table));
        } catch (SQLException e) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "조회 오류: " + e.getMessage()));
        }
    }

    /**
     * 누적된 테이블 구조 + 자연어 요청 조건을 sql-writer.md 입력 형식으로 조립해 sqlrestapi(promptId=
     * sql-writer)로 보낸다. RAG 검색 없음(current_sql/analyze와 같은 성격).
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody SqlWriterGenerateRequest req) {
        List<Map<String, Object>> tables = req.tables();
        String requestText = req.requestText();
        if (tables == null || tables.isEmpty()) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "먼저 테이블을 조회해 추가해주세요."));
        }
        if (requestText == null || Strings.isBlank(requestText)) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "요청 조건을 입력해주세요."));
        }
        try {
            String prompt = "[테이블 구조]\n" + buildTableStructureBlock(tables) + "[요청 조건]\n" + requestText;
            String answer = ollamaChatService.askWithPrompt(PROMPT_ID, prompt);
            return ResponseEntity.ok(Maps.of("success", true, "answer", answer));
        } catch (Exception e) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "서버 오류: " + e.getMessage()));
        }
    }

    /** sql-writer.md가 요구하는 입력 형식(TABLE:/COLUMNS:/INDEXES:) 그대로 조립. */
    @SuppressWarnings("unchecked")
    private String buildTableStructureBlock(List<Map<String, Object>> tables) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> table : tables) {
            sb.append("TABLE: ").append(table.get("name")).append("\n");
            sb.append("  COLUMNS:\n");
            Object colsObj = table.get("columns");
            if (colsObj instanceof List) {
                for (Object c : (List<Object>) colsObj) {
                    if (!(c instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> col = (Map<String, Object>) c;
                    Object nullableObj = col.get("nullable");
                    boolean nullable = !(nullableObj instanceof Boolean) || (Boolean) nullableObj;
                    sb.append("    ").append(col.get("name")).append("  ").append(col.get("dataType"));
                    if (!nullable) {
                        sb.append("  NOT NULL");
                    }
                    sb.append("\n");
                }
            }
            Object idxObj = table.get("indexes");
            if (idxObj instanceof List && !((List<Object>) idxObj).isEmpty()) {
                sb.append("  INDEXES:\n");
                for (Object ix : (List<Object>) idxObj) {
                    if (!(ix instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> idx = (Map<String, Object>) ix;
                    Object uniqueObj = idx.get("unique");
                    boolean unique = uniqueObj instanceof Boolean && (Boolean) uniqueObj;
                    Object columnsObj = idx.get("columns");
                    StringBuilder colsJoined = new StringBuilder();
                    if (columnsObj instanceof List) {
                        List<Object> cl = (List<Object>) columnsObj;
                        for (int i = 0; i < cl.size(); i++) {
                            if (i > 0) {
                                colsJoined.append(", ");
                            }
                            colsJoined.append(cl.get(i));
                        }
                    }
                    sb.append("    ").append(idx.get("name")).append("  ")
                            .append(unique ? "UNIQUE " : "").append("(").append(colsJoined).append(")\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
