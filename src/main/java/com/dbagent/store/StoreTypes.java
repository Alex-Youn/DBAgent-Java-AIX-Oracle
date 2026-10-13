package com.dbagent.store;

import org.springframework.core.env.Environment;

/** 설정에서 저장소별 엔진을 정한다. 빈을 만들기 전(조건 평가)에도 쓰므로 Environment만 받는다. */
public final class StoreTypes {

    /** 내부 저장소 전체의 엔진. 기본 h2. */
    public static final String KEY = "dbagent.store.type";

    private StoreTypes() {
    }

    /**
     * 저장소별 재정의 키 {@code dbagent.store.<id>.type}이 있으면 그 값, 없으면 {@code dbagent.store.type}, 그것도 없으면 H2.
     * 저장소별 키는 단계 검증용 숨김 키다(2026-10-12 오케스트레이터 결정 Q12: 설정 화면·매뉴얼에 싣지 않고 운영에서는 지원하지 않는다).
     */
    public static StoreType resolve(Environment env, StoreId id) {
        String own = env.getProperty("dbagent.store." + id.key() + ".type");
        if (own != null && !own.trim().isEmpty()) return StoreType.parse(own);
        return StoreType.parse(env.getProperty(KEY));
    }

    public static boolean anyOracle(Environment env) {
        for (StoreId id : StoreId.values()) {
            if (resolve(env, id) == StoreType.ORACLE) return true;
        }
        return false;
    }
}
