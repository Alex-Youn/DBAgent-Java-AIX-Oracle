package com.dbagent.sqlwriter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * AI SQL 작성기 - "SQL 생성" 요청. tables는 table_info 응답을 그대로 누적해 보낸 것(프론트가 "추가"로
 * 모은 목록) - DB 접속이 다시 필요하지 않으므로 dbId/account/token 없이 구조 데이터만 받는다.
 */
public final class SqlWriterGenerateRequest {

    private final List<Map<String, Object>> tables;
    private final String requestText;

    @JsonCreator
    public SqlWriterGenerateRequest(@JsonProperty("tables") List<Map<String, Object>> tables,
                                     @JsonProperty("request_text") String requestText) {
        this.tables = tables;
        this.requestText = requestText;
    }

    public List<Map<String, Object>> tables() {
        return tables;
    }

    public String requestText() {
        return requestText;
    }
}
