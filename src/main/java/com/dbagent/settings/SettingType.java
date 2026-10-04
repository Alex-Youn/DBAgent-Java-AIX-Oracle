package com.dbagent.settings;

/** 설정 값의 종류. 화면 입력칸 모양과 서버 검증 방식이 이것으로 정해진다. */
public enum SettingType {
    /** 정수 (SQL 문자열에 붙는 값이 많아 소수는 받지 않는다). */
    INT,
    /** 소수 허용 숫자 (사용률 % 등). */
    DECIMAL,
    /** true / false */
    BOOL,
    /** 자유 문자열 (빈 값 허용 여부는 키별). */
    STRING,
    /** 서버 주소 - 스킴(http://)은 생략해도 된다(서비스가 normalizeUrl 에서 보정). */
    URL,
    /** 쉼표로 구분한 목록 (소유자 목록, LIKE 패턴 목록 등). */
    CSV
}
