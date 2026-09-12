package com.dbagent.aidba;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** AI Current SQL 분석("성능분석" 버튼, 매뉴통합.md 2-1) 요청 - 1차 성능점검 결과를 그대로 실어 보낸다. */
public final class CurrentSqlAnalyzeRequest {

    private final String query;
    private final Map<String, String> binds;
    private final String plan;

    @JsonCreator
    public CurrentSqlAnalyzeRequest(@JsonProperty("query") String query,
                                     @JsonProperty("binds") Map<String, String> binds,
                                     @JsonProperty("plan") String plan) {
        this.query = query;
        this.binds = binds;
        this.plan = plan;
    }

    public String query() {
        return query;
    }

    public Map<String, String> binds() {
        return binds;
    }

    public String plan() {
        return plan;
    }
}
