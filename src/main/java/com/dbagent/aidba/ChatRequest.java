package com.dbagent.aidba;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class ChatRequest {

    private final String message;

    @JsonCreator
    public ChatRequest(@JsonProperty("message") String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
