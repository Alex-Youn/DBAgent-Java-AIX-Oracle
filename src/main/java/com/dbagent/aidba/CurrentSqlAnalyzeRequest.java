package com.dbagent.aidba;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * AI Current SQL 분석("성능분석" 버튼, 매뉴통합.md 2-1) 요청 - 1차 성능점검 결과를 그대로 실어 보낸다.
 * previousContext/followUpQuestion은 후속질문(2026-09-15, non-AIX 쪽 포팅) 용 - LLM은 이전 호출을
 * 기억하지 못하므로 프론트에서 누적한 이전 분석/문답 내역을 매번 그대로 다시 실어 보내는 stateless 방식이다.
 */
public final class CurrentSqlAnalyzeRequest {

    private final String query;
    private final Map<String, String> binds;
    private final String plan;
    private final String previousContext;
    private final String followUpQuestion;

    @JsonCreator
    public CurrentSqlAnalyzeRequest(@JsonProperty("query") String query,
                                     @JsonProperty("binds") Map<String, String> binds,
                                     @JsonProperty("plan") String plan,
                                     @JsonProperty("previousContext") String previousContext,
                                     @JsonProperty("followUpQuestion") String followUpQuestion) {
        this.query = query;
        this.binds = binds;
        this.plan = plan;
        this.previousContext = previousContext;
        this.followUpQuestion = followUpQuestion;
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

    public String previousContext() {
        return previousContext;
    }

    public String followUpQuestion() {
        return followUpQuestion;
    }
}
