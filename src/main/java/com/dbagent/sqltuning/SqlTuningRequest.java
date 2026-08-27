package com.dbagent.sqltuning;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class SqlTuningRequest {

    private final String prompt;

    @JsonCreator
    public SqlTuningRequest(@JsonProperty("prompt") String prompt) {
        this.prompt = prompt;
    }

    public String prompt() {
        return prompt;
    }
}
