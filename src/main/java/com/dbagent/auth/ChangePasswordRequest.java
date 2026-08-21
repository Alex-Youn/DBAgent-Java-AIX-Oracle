package com.dbagent.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class ChangePasswordRequest {

    private final String token;
    private final String currentPassword;
    private final String newPassword;

    @JsonCreator
    public ChangePasswordRequest(
            @JsonProperty("token") String token,
            @JsonProperty("current_password") String currentPassword,
            @JsonProperty("new_password") String newPassword) {
        this.token = token;
        this.currentPassword = currentPassword;
        this.newPassword = newPassword;
    }

    public String token() {
        return token;
    }

    public String currentPassword() {
        return currentPassword;
    }

    public String newPassword() {
        return newPassword;
    }
}
