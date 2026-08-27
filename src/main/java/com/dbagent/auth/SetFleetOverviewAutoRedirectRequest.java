package com.dbagent.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class SetFleetOverviewAutoRedirectRequest {

    private final String token;
    private final boolean autoRedirect;

    @JsonCreator
    public SetFleetOverviewAutoRedirectRequest(
            @JsonProperty("token") String token,
            @JsonProperty("auto_redirect") boolean autoRedirect) {
        this.token = token;
        this.autoRedirect = autoRedirect;
    }

    public String token() {
        return token;
    }

    public boolean autoRedirect() {
        return autoRedirect;
    }
}
