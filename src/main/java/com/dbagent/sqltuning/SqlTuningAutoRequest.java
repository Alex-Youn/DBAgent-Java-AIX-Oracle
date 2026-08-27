package com.dbagent.sqltuning;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public final class SqlTuningAutoRequest {

    private final String dbId;
    private final String account;
    private final String token;
    private final String query;
    // 바인드 변수명(콜론 제외, 예: "1", "SID") -> 값. 실제 실행(analyze_from_query_actual/quick_check)에서만 사용.
    private final Map<String, String> binds;

    @JsonCreator
    public SqlTuningAutoRequest(@JsonProperty("db_id") String dbId,
                                 @JsonProperty("account") String account,
                                 @JsonProperty("token") String token,
                                 @JsonProperty("query") String query,
                                 @JsonProperty("binds") Map<String, String> binds) {
        this.dbId = dbId;
        this.account = account;
        this.token = token;
        this.query = query;
        this.binds = binds;
    }

    public String dbId() {
        return dbId;
    }

    public String account() {
        return account;
    }

    public String token() {
        return token;
    }

    public String query() {
        return query;
    }

    public Map<String, String> binds() {
        return binds;
    }
}
