package com.dbagent.monitor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class KillSessionRequest {

    private final List<SessionRef> sessions;
    private final String token;
    /** "FAILOVER"(장애조치 버튼) 또는 생략(선택 세션 Kill) - 감사 기록 구분용. */
    private final String reason;

    @JsonCreator
    public KillSessionRequest(@JsonProperty("sessions") List<SessionRef> sessions, @JsonProperty("token") String token,
                              @JsonProperty("reason") String reason) {
        this.sessions = sessions;
        this.token = token;
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    public List<SessionRef> sessions() {
        return sessions;
    }

    public String token() {
        return token;
    }

    public static final class SessionRef {

        private final Long sid;
        private final Long serial;

        @JsonCreator
        public SessionRef(@JsonProperty("sid") Long sid, @JsonProperty("serial") Long serial) {
            this.sid = sid;
            this.serial = serial;
        }

        public Long sid() {
            return sid;
        }

        public Long serial() {
            return serial;
        }
    }
}
