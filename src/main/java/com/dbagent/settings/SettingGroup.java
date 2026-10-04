package com.dbagent.settings;

/** 환경설정 화면의 그룹 탭. order 순서대로 보여준다. */
public enum SettingGroup {
    UI("ui", "화면 · 인증", "화면 갱신 주기, 로그인 유지 기간", 10, true),
    LOCK("lock", "Lock · 실시간", "Lock 대시보드 장애 판정·표시 기준 (DB별 설정 가능)", 20, true),
    CHECK("check", "점검 알림", "점검 수집 주기와 경고 임계치", 30, true),
    TIMEOUT("timeout", "조회 타임아웃", "대상 DB 조회·접속 제한 시간", 40, true),
    RETENTION("retention", "보존 기간", "수집 이력 보관 일수", 50, true),
    POOL("pool", "커넥션 풀", "대상 DB 커넥션 풀 크기 기본값", 60, true),
    AI("ai", "AI · 튜닝", "AI 서버 주소와 응답 대기 시간", 70, true),
    SQL("sql", "SQL 실행", "SQL 실행 화면 제한 (보안 영향)", 80, true),
    /** 화면에 노출하지 않는 내부 값 (서로 맞물린 주기 등). */
    INTERNAL("internal", "내부", "화면에 노출하지 않음", 999, false);

    private final String id;
    private final String label;
    private final String description;
    private final int order;
    private final boolean visible;

    SettingGroup(String id, String label, String description, int order, boolean visible) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.order = order;
        this.visible = visible;
    }

    public String id() { return id; }
    public String label() { return label; }
    public String description() { return description; }
    public int order() { return order; }
    public boolean visible() { return visible; }
}
