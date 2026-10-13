package com.dbagent.store;

/**
 * 내부 저장소 엔진 (2026-10-11 설계: 내부저장소_Oracle선택지원_설계와체크리스트 2.1절).
 * 기본은 H2 - {@code dbagent.store.type}을 적지 않으면 지금까지와 같은 H2 파일 저장소로 뜬다.
 */
public enum StoreType {
    H2, ORACLE;

    /** 설정 값 → 타입. 비어 있으면 H2. 모르는 값은 조용히 H2로 넘기지 않고 멈춘다(오타로 엉뚱한 저장소에 뜨는 것을 막는다). */
    public static StoreType parse(String value) {
        if (value == null || value.trim().isEmpty()) return H2;
        String v = value.trim();
        if ("h2".equalsIgnoreCase(v)) return H2;
        if ("oracle".equalsIgnoreCase(v)) return ORACLE;
        throw new IllegalStateException("dbagent.store.type 값을 알 수 없습니다: '" + value + "' (h2 또는 oracle)");
    }
}
