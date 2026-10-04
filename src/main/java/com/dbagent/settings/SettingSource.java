package com.dbagent.settings;

/**
 * 현재 적용 중인 값이 어디서 왔는지. 우선순위(높은 쪽부터):
 * 기동 인자 > conf/dbagent.conf > DB(DB별 > 전역) > 옛 외부 application.properties > jar 번들 > 코드 기본값.
 */
public enum SettingSource {
    LOCKED_ARG("기동 인자 고정", true),
    CONF("conf/dbagent.conf", true),
    DB_PER_DB("이 DB 재정의", false),
    DB_GLOBAL("DB 저장값", false),
    OLD_FILE("옛 외부 파일", false),
    BUNDLE("번들 기본값", false),
    CODE_DEFAULT("기본값", false);

    private final String label;
    private final boolean locksOverDb;

    SettingSource(String label, boolean locksOverDb) {
        this.label = label;
        this.locksOverDb = locksOverDb;
    }

    public String label() { return label; }

    /** true 이면 DB 에 저장된 값이 있어도 이 출처가 이긴다 (화면에서 입력을 막는다). */
    public boolean locksOverDb() { return locksOverDb; }
}
