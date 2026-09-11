package com.dbagent.sqltuneadvisor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class SqlTuneAdvisorRequest {

    private final String query;

    @JsonCreator
    public SqlTuneAdvisorRequest(@JsonProperty("query") String query) {
        this.query = query;
    }

    public String query() {
        return query;
    }
}
