package com.dbagent.rdb;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * RDB 세션 Kill 요청 바디. 오라클의 {@code KillSessionRequest} 대응이지만 세션 식별자가 하나뿐이다 -
 * 오라클은 {@code SID,SERIAL#,@INST_ID} 세 조각이 필요한 반면 MySQL/MariaDB(id) · PostgreSQL(pid) ·
 * MS SQL(SPID)은 전부 정수 하나로 세션이 특정된다.
 *
 * <p>세션 id 를 {@code Long} 이 아니라 {@code String} 으로 받는 이유: Jackson 이 숫자가 아닌 값을
 * 만나면 400 을 먼저 던져 "세션 ID가 올바르지 않습니다" 라는 우리 메시지가 나갈 기회가 없다.
 * 문자열로 받아 컨트롤러에서 직접 검증해야 세션 상세 조회(session_detail)와 같은 응답을 줄 수 있다.
 *
 * <p>이 클래스는 record 가 아니라 {@code @JsonCreator} 를 단 평범한 final 클래스다 - 그래야
 * 원본(Java 17)과 AIX 이관본(Java 8)에 <b>같은 파일을 그대로</b> 둘 수 있다.
 */
public final class RdbKillSessionRequest {

    private final List<String> sessions;
    private final String token;

    @JsonCreator
    public RdbKillSessionRequest(@JsonProperty("sessions") List<String> sessions,
                                  @JsonProperty("token") String token) {
        this.sessions = sessions;
        this.token = token;
    }

    public List<String> sessions() {
        return sessions;
    }

    public String token() {
        return token;
    }
}
