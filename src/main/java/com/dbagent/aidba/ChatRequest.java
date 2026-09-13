package com.dbagent.aidba;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class ChatRequest {

    private final String prompt;

    @JsonCreator
    public ChatRequest(@JsonProperty("prompt") String prompt) {
        this.prompt = prompt;
    }

    public String prompt() {
        return prompt;
    }
}
