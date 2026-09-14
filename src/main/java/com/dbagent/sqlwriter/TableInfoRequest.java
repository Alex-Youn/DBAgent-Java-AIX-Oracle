package com.dbagent.sqlwriter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AI SQL 작성기 - 테이블 구조 조회("조회" 버튼) 요청.
 * owner는 선택값 - 비워두면 현재 접속 계정 소유 → 없으면 자동 후보 탐색(TableInfoService 참고),
 * 후보가 여럿이라 프론트에서 사용자가 고른 뒤 다시 요청할 때만 채워서 보낸다.
 */
public final class TableInfoRequest {

    private final String dbId;
    private final String account;
    private final String token;
    private final String tableName;
    private final String owner;

    @JsonCreator
    public TableInfoRequest(@JsonProperty("db_id") String dbId,
                             @JsonProperty("account") String account,
                             @JsonProperty("token") String token,
                             @JsonProperty("table_name") String tableName,
                             @JsonProperty("owner") String owner) {
        this.dbId = dbId;
        this.account = account;
        this.token = token;
        this.tableName = tableName;
        this.owner = owner;
    }

    public String dbId() {
        return dbId;
    }

    public String account() {
        return account;
    }

    public String token() {
        return token;
    }

    public String tableName() {
        return tableName;
    }

    public String owner() {
        return owner;
    }
}
