package com.dbagent.rdb;

import com.dbagent.oracle.TargetDbConfig;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Lightweight monitoring surface for non-Oracle engines (MySQL/MariaDB/PostgreSQL/MS SQL Server),
 * implemented by {@link MySqlMonitorService}, {@link PostgresMonitorService} and
 * {@link MsSqlMonitorService}. Dispatch happens by {@link TargetDbConfig#dbType()} - see
 * RdbMonitorController and MonitorController.fleetStatusFor().
 */
public interface EngineMonitorService {

    /** Session/process list - same rough shape across engines (id/user/host/state/query text). */
    List<Map<String, Object>> getSessions(TargetDbConfig target) throws SQLException;

    /**
     * Storage usage per schema/database. Reuses the same JSON shape as Oracle's
     * MonitorService.getTablespaces() (tablespace_name/status/total_mb/used_mb/free_mb/used_pct) so the
     * existing tablespace-summary rendering can be reused as-is - these engines have no
     * allocated-vs-used distinction like Oracle datafiles, so total_mb==used_mb and free_mb=0.
     */
    List<Map<String, Object>> getStorage(TargetDbConfig target) throws SQLException;

    /** Fleet Overview card status - same outer shape as MonitorService.getFleetStatus(). */
    Map<String, Object> getFleetStatus(TargetDbConfig target);

    // -----------------------------------------------------------------------------------------
    // RDB 대시보드 세션 화면 3종 (세션 리스트 / 세션 상세 / Lock 모니터링).
    // 근거 문서: "세션리스트 및 세션 정보 조회 쿼리.md" (2026-09-05 구현).
    //
    // 세 엔진의 원본 뷰는 컬럼 이름도 단위도 제각각이다(MS SQL 만 밀리초, MySQL 은 program 개념이
    // 아예 없음). 화면에서 엔진마다 다른 렌더링 코드를 두지 않도록 여기서 아래 공통 키로 정규화해서
    // 돌려준다 - rdb-session-views.js 는 이 키만 알면 세 대시보드에서 같은 표를 그린다.
    // -----------------------------------------------------------------------------------------

    /**
     * 활성 세션 목록. 아래 키로 정규화된다(값이 없는 엔진은 null - 화면은 '-' 로 그린다).
     *
     * <pre>
     * session_id       세션 식별자 (Oracle SID 대응: pg=pid / mysql=id / mssql=session_id)
     * user             계정
     * host             접속 IP/호스트
     * db               현재 DB (없으면 null)
     * program          클라이언트 프로그램 (MySQL/MariaDB 는 개념이 없어 항상 null)
     * status           상태 (pg=state / mysql=command / mssql=status)
     * duration_seconds 경과 시간(초). MS SQL 의 ms 단위는 여기서 초로 환산해서 담는다
     * wait_event       대기 이벤트 (pg=wait_event_type/wait_event / mysql=state / mssql=wait_type)
     * query_preview    실행 중인 SQL 앞 500자 (전문은 getSessionDetail 로)
     * </pre>
     *
     * 공통 필터: 모니터링 쿼리 자신을 제외하고, 유휴 세션을 제외한다(문서 1절).
     */
    List<Map<String, Object>> getSessionList(TargetDbConfig target) throws SQLException;

    /**
     * 세션 상세(SQL 전문). 목록에서 한 행을 클릭했을 때 여는 팝업용.
     *
     * <pre>
     * found     boolean - 그 사이 세션이 끝나 사라졌으면 false
     * fields    [{label, value}, ...] - 표시 순서 그대로. 엔진마다 항목이 다르므로
     *           (MS SQL 은 cpu_time, MySQL 은 command 등) 화면은 이 배열을 그대로 그린다
     * sql_text  실행 중인 SQL 전문 (없으면 null)
     * </pre>
     *
     * 목록과 달리 유휴/자기 세션 필터를 걸지 않는다 - 사용자가 클릭한 그 세션을 보여주는 것이
     * 목적이고, 목록을 그린 뒤 클릭하기까지의 사이에 세션이 유휴로 바뀌었다고 해서 "없음" 으로
     * 보이면 오히려 혼란스럽다.
     */
    Map<String, Object> getSessionDetail(TargetDbConfig target, long sessionId) throws SQLException;

    /**
     * Lock 블로킹 체인 - 대기(waiter) 세션과 그것을 막고 있는(blocker) 세션을 한 행에 쌍으로 담는다.
     *
     * <pre>
     * waiter_session_id / waiter_user / waiter_host / waiter_query
     * wait_duration_sec  대기 시간(초)
     * wait_type          대기 유형. 엔진별 표현이 달라 문자열로 합쳐 담는다
     *                    (pg=wait_event_type/wait_event / mysql=요청 락 모드 (대상 테이블) / mssql=wait_type)
     * blocker_session_id / blocker_user / blocker_host / blocker_state / blocker_query
     * </pre>
     */
    List<Map<String, Object>> getLockWaits(TargetDbConfig target) throws SQLException;

    /**
     * 세션 하나를 강제 종료한다. 오라클의 {@code MonitorService.killSessions()} 대응.
     *
     * <pre>
     * session_id  대상 세션 id (요청한 값 그대로 되돌려준다 - 화면에서 행과 맞추기 위함)
     * status      "killed" | "error"
     * message     status=error 일 때의 사유 (killed 면 null)
     * </pre>
     *
     * 엔진별 명령이 다르다: MySQL/MariaDB {@code KILL <id>}, PostgreSQL
     * {@code pg_terminate_backend(<pid>)}, MS SQL {@code KILL <spid>}.
     *
     * <p><b>세션 id 는 호출 전에 반드시 숫자로 검증되어 있어야 한다.</b> MySQL 의 {@code KILL} 과
     * MS SQL 의 {@code KILL} 은 바인드 파라미터를 받지 못하는 명령이라 문자열로 조립할 수밖에 없다 -
     * RdbMonitorController 가 {@code Long.parseLong} 을 통과한 값만 여기로 넘긴다(그래서 시그니처가
     * String 이 아니라 long 이다).
     *
     * <p>예외를 던지지 않고 결과 맵에 담는 이유: 여러 세션을 한 번에 kill 할 때 한 건이 실패해도
     * 나머지는 계속 진행해야 하고, 화면은 "성공 n건 / 실패 m건" 을 건별 사유와 함께 보여준다.
     */
    Map<String, Object> killSession(TargetDbConfig target, long sessionId);

    // -----------------------------------------------------------------------------------------
    // 용량 조회 (오라클 "테이블 스페이스 조회" 대응, 2026-09-05)
    //
    // 오라클은 테이블스페이스 → 데이터파일 2단이다. RDB 쪽에는 테이블스페이스라는 단위가 없거나
    // (MySQL/MariaDB) 기본 구성에서 하나뿐이라(PostgreSQL) 의미가 없어서, 같은 2단 구조를
    // "저장 단위 → 테이블" 로 대응시켰다. DBA 가 실제로 알고 싶은 것도 "어느 스키마/DB 가, 그 안의
    // 어느 테이블이 용량을 먹고 있나" 이지 파일 경로가 아니다.
    // -----------------------------------------------------------------------------------------

    /**
     * 저장 단위별 용량 (1단). 단위가 엔진마다 다르므로 화면이 컬럼 머리글을 바꿔 달 수 있도록
     * 단위 이름을 함께 준다.
     *
     * <pre>
     * unit  "스키마"(MySQL/MariaDB/PostgreSQL) | "데이터베이스"(MS SQL)
     * note  이 숫자가 무엇을 센 것인지에 대한 엔진별 설명 (화면 하단에 그대로 띄운다)
     * rows  [{ name, table_count, data_mb, index_mb, used_mb, total_mb, free_mb, used_pct }]
     * </pre>
     *
     * 컬럼 의미는 아래로 고정한다. <b>해당 개념이 없는 엔진은 null 을 넣는다</b>(화면은 '-' 로 그린다) -
     * 억지로 채우면 안 된다. 실제로 기존 {@code getStorage()} 는 total==used, free=0 으로 채워서
     * 사용률이 <b>항상 100%</b> 로 나오는데, 그건 정보가 아니라 잡음이다.
     *
     * <pre>
     * data_mb / index_mb  데이터/인덱스가 실제로 차지한 크기
     * used_mb             실제 점유 크기
     * total_mb            미리 할당된 크기. MS SQL 의 데이터 파일 할당량만 해당한다 -
     *                     MySQL/PostgreSQL 은 "할당" 개념이 없어 null
     * free_mb             여유. MySQL 은 InnoDB 재사용 가능 조각(data_free), MS SQL 은 할당-사용,
     *                     PostgreSQL 은 상응하는 값이 없어 null
     * used_pct            할당 대비 사용률. total_mb 가 있는 MS SQL 만 값이 있다
     * </pre>
     */
    Map<String, Object> getCapacity(TargetDbConfig target) throws SQLException;

    /**
     * 저장 단위 하나를 파고든 테이블별 용량 (2단). {@code scope} 는 1단에서 클릭한 행의 name
     * (스키마명 또는 데이터베이스명)이다.
     *
     * <pre>
     * scope  요청한 저장 단위 이름 (화면에서 제목에 쓴다)
     * note   조회할 수 없는 경우의 사유 (rows 가 비어 있을 때만 채워진다)
     * rows   [{ name, row_count, data_mb, index_mb, total_mb, free_mb }]
     * </pre>
     *
     * {@code scope} 는 사용자가 고른 값이 그대로 SQL 에 들어가는 자리이므로 <b>반드시 바인딩하거나
     * (MySQL/PostgreSQL) MS SQL 처럼 {@code QUOTENAME()} 으로 감싼다.</b> 문자열 연결로 붙이지 말 것.
     */
    Map<String, Object> getCapacityDetail(TargetDbConfig target, String scope) throws SQLException;
}
