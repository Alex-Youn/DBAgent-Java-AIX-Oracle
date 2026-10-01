package com.dbagent.oracle;

import com.zaxxer.hikari.SQLExceptionOverride;

import java.sql.SQLException;

/**
 * Oracle 풀(HikariCP)의 "연결이 끊겼는지" 판정 보정(2026-10-01).
 *
 * ALTER SYSTEM KILL SESSION ... IMMEDIATE가 실행 중인 세션을 바로 못 끊으면 ORA-00031(session marked for kill)을 주는데,
 * 이 예외의 SQLSTATE가 08006(연결 실패 계열)이라 Hikari가 우리 커넥션을 broken으로 보고 닫았다. 그래서 여러 세션을
 * 한 번에 KILL할 때 첫 세션이 ORA-00031이면 같은 커넥션으로 하던 나머지가 ORA-17009(닫힌 명령문)로 전부 실패했다
 * (로컬 재현: "marked as broken because of SQLSTATE(08006), ErrorCode(31)"). ORA-00031은 대상 세션 쪽 사정이고 우리
 * 커넥션은 멀쩡하므로 버리지 않는다. 그 밖의 예외는 Hikari 기본 판정(CONTINUE_EVICT - SQLSTATE·오류 코드로 판단)을 그대로 따른다(4.0.3·5.1.0 공통 상수).
 * Hikari가 이름으로 만들므로 public 클래스 + 기본 생성자여야 한다(HikariConfig.setExceptionOverrideClassName).
 */
public class OracleSqlExceptionOverride implements SQLExceptionOverride {

    /** ORA-00031 session marked for kill. */
    private static final int ORA_SESSION_MARKED_FOR_KILL = 31;

    // 인터페이스 안에 Override라는 enum이 있어 애너테이션은 java.lang.Override로 적는다.
    @java.lang.Override
    public SQLExceptionOverride.Override adjudicate(SQLException e) {
        return e.getErrorCode() == ORA_SESSION_MARKED_FOR_KILL
                ? SQLExceptionOverride.Override.DO_NOT_EVICT
                : SQLExceptionOverride.Override.CONTINUE_EVICT;
    }
}
