package com.dbagent.rdb;

import com.dbagent.oracle.TargetDbConfig;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CUBRID 모니터링 (5번째 엔진, 2026-09-06).
 *
 * <p><b>이 엔진은 다른 넷과 정보 공개 범위가 근본적으로 다르다.</b> MySQL/MariaDB/PostgreSQL/MS SQL 은
 * 세션 정보를 시스템 뷰(pg_stat_activity, processlist, dm_exec_sessions)로 공개하지만, CUBRID 의
 * 시스템 카탈로그(db_class 등 49개)에는 <b>모니터링 뷰가 하나도 없다</b> - 전부 스키마 메타데이터다.
 * 대신 {@code SHOW} 구문이 그 역할을 한다. 2026-09-06 에 로컬 CUBRID 11.4.5 컨테이너로 실측해
 * 확인한 내용은 아래와 같다.
 *
 * <pre>
 * 쓸 수 있는 것 (JDBC)
 *   SHOW TRANSACTION TABLES [WHERE ...]  세션 목록 (46개 컬럼, 바인드 파라미터 O)
 *   SHOW THREADS [WHERE ...]             락 대기 스레드 (Lockwait_blocked_mode/start_time/state)
 *   SHOW VOLUME HEADER OF <volid>        볼륨별 공간
 *   SHOW LOG HEADER                      DB 버전/페이지 크기/체크포인트
 *   SHOW ACCESS STATUS                   계정별 마지막 접속
 *   KILL &lt;tran_index&gt;                    세션 종료 (executeUpdate 가 실제 종료 건수를 돌려준다)
 *
 * 쓸 수 없는 것 - 이게 화면 구성을 갈랐다
 *   SQL 원문      SHOW TRANSACTION TABLES 에 없다(Xasl_id 해시만 있다). 서버는 갖고 있지만
 *                 `cubrid killtran -q` CLI 로만 나온다 - DB 서버 호스트에서 실행해야 하므로
 *                 원격 JDBC 앱은 접근할 방법이 없다.
 *   Lock holder   같은 이유로 "누가 막고 있는지"(killtran -q 의 Wait for lock holder)를 못 얻는다.
 *                 대기자(waiter)는 SHOW THREADS 로 알 수 있어 세션 리스트의 대기 이벤트 칸에
 *                 담지만, holder 를 모르면 Holder/Waiter <b>트리는 성립하지 않는다</b> -
 *                 그래서 이 엔진에서는 Lock 탭 자체를 숨긴다(getLockWaits 주석 참고).
 *   서버 통계     SHOW EXEC STATISTICS / statdump 가 기본 구성에서 전부 0 이라 QPS·버퍼 적중률
 *                 같은 KPI 를 못 만든다. 대시보드는 대신 세션/스레드/볼륨 기반으로 구성했다.
 *   가동 시간     서버 기동 시각을 주는 SQL 이 없다(SHOW LOG HEADER 의 Creation_time 은 DB
 *                 생성 시각이지 기동 시각이 아니다). uptime 계열은 전부 null 이다.
 * </pre>
 *
 * <p>값이 없는 자리는 <b>null 로 두고 화면이 '-' 로 그린다</b> - 다른 엔진과 같은 원칙이다
 * (EngineMonitorService 의 용량 섹션 주석 참고). 억지로 채우면 "항상 100%" 류의 잡음이 된다.
 */
@Service
public class CubridMonitorService implements EngineMonitorService {

    /**
     * 볼륨 열거 상한. CUBRID 는 볼륨 목록을 주는 SQL 이 없어 {@code SHOW VOLUME HEADER OF n} 을
     * 하나씩 두드려 보는 수밖에 없다(실패하면 그 번호는 없는 볼륨이다). 영구 볼륨은 0 부터
     * 오름차순, 임시 볼륨은 32766 부터 <b>내림차순</b>으로 붙는다 - 그래서 0,1,2… 만 훑으면
     * 임시 볼륨을 통째로 놓친다(실측: 이 컨테이너의 임시 볼륨이 32766 이었다).
     * 왕복 횟수를 묶어두려고 양쪽 모두 상한을 둔다.
     */
    private static final int MAX_PERMANENT_VOLUMES = 64;
    private static final int MAX_TEMP_VOLUMES = 16;
    private static final int FIRST_TEMP_VOLID = 32766;

    private final RdbConnectionPoolManager poolManager;

    public CubridMonitorService(RdbConnectionPoolManager poolManager) {
        this.poolManager = poolManager;
    }

    // =============================================================================================
    // 기존 3종 (다른 엔진과 같은 계약)
    // =============================================================================================

    @Override
    public List<Map<String, Object>> getSessions(TargetDbConfig target) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            Timestamp serverNow = serverNow(st);
            try (ResultSet rs = st.executeQuery("SHOW TRANSACTION TABLES")) {
                while (rs.next()) {
                    if (isSystemInternal(rs)) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("pid", rs.getObject("Tran_index"));
                    row.put("user", rs.getString("Client_db_user"));
                    row.put("host", rs.getString("Client_host"));
                    row.put("state", rs.getString("State"));
                    // CUBRID 는 실행 중인 SQL 원문을 JDBC 로 주지 않는다(클래스 주석 참고).
                    row.put("info", null);
                    row.put("application_name", rs.getString("Client_program"));
                    row.put("duration_sec", elapsedSeconds(serverNow, startTime(rs)));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> getStorage(TargetDbConfig target) throws SQLException {
        // 오라클 테이블스페이스 응답과 같은 모양(EngineMonitorService 참고). CUBRID 는 다른 셋과 달리
        // 볼륨을 미리 할당해 두고 그 안을 채워 쓰기 때문에 total/used/free 가 전부 진짜 값이다 -
        // MySQL/PostgreSQL 처럼 total==used, free=0 으로 채워 넣지 않아도 된다.
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            for (Map<String, Object> vol : readVolumes(st)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tablespace_name", vol.get("name"));
                row.put("status", "ONLINE");
                row.put("total_mb", vol.get("total_mb"));
                row.put("used_mb", vol.get("used_mb"));
                row.put("free_mb", vol.get("free_mb"));
                row.put("used_pct", vol.get("used_pct"));
                rows.add(row);
            }
        }
        return rows;
    }

    @Override
    public Map<String, Object> getFleetStatus(TargetDbConfig target) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", target.id());
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            long activeSession = 0;
            try (ResultSet rs = st.executeQuery("SHOW TRANSACTION TABLES")) {
                while (rs.next()) {
                    if (!isSystemInternal(rs)) {
                        activeSession++;
                    }
                }
            }
            result.put("status", "alive");
            result.put("sid", target.sid());
            result.put("version", logHeaderRelease(st));
            result.put("cpuPct", 0);
            result.put("memPct", 0);
            result.put("activeSession", activeSession);
            result.put("txnPerMin", 0);
            // 서버 기동 시각을 주는 SQL 이 없어 가동률을 계산할 수 없다(클래스 주석 참고).
            // 0 을 넣으면 카드가 "가동률 0%" 로 보여 장애처럼 읽히므로 null 로 둔다.
            result.put("uptimePct", null);
        } catch (SQLException e) {
            result.put("status", "down");
            result.put("errorMessage", "DB에 연결할 수 없습니다.");
        }
        return result;
    }

    // =============================================================================================
    // CUBRID 전용 대시보드 (cubrid-overview-dashboard.html)
    // =============================================================================================

    /**
     * CUBRID 전용 KPI 카드. 다른 엔진의 KPI(가동시간/QPS/버퍼 적중률)는 서버 누적 통계에서 나오는데
     * CUBRID 는 그 통계가 기본 구성에서 전부 0 이라(클래스 주석) 쓸 수 없다. 대신 <b>지금 이 순간의
     * 서버 상태</b>로 KPI 를 짰다 - 어차피 DBA 가 대시보드에서 먼저 보는 것도 이쪽이다.
     *
     * <pre>
     * activeSessions   실제 클라이언트 트랜잭션 수 (SYSTEM_INTERNAL 제외)
     * lockWaitSessions 락을 기다리는 중인 세션 수 - 0 이 아니면 그 자체로 경보다
     * workerBusyPct    워커 스레드 사용률. 문서 6.1 "커넥션 풀 고갈율" 의 CUBRID 대응이다 -
     *                  CUBRID 는 커넥션당 워커 스레드를 물리므로 이게 100% 에 닿으면 새 요청이 막힌다
     * volumeUsedPct    영구 볼륨 전체의 사용률 (할당 대비)
     * </pre>
     */
    public Map<String, Object> getOverviewStats(TargetDbConfig target) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            long activeSessions = 0;
            long openTransactions = 0;
            try (ResultSet rs = st.executeQuery("SHOW TRANSACTION TABLES")) {
                while (rs.next()) {
                    if (isSystemInternal(rs)) {
                        continue;
                    }
                    activeSessions++;
                    if (startTime(rs) != null) {
                        openTransactions++;
                    }
                }
            }
            result.put("activeSessions", activeSessions);
            result.put("openTransactions", openTransactions);
            result.put("lockWaitSessions", (long) readLockWaitsByTran(st).size());

            // 워커가 "일하는 중" 인지는 Status 로 판단하면 안 된다.
            //
            // 처음에는 Status <> 'FREE' 로 셌는데, CUBRID 를 재기동한 직후 실측하니 <b>세션이 1개뿐인
            // 유휴 서버에서 워커 199개 중 195개가 Status='RUN'</b> 이었다(FREE 는 4개). 그대로 두면
            // 사용률이 98.5% 로 떠서 "스레드 풀 고갈 직전" 이라는 잘못된 경보가 된다 - 이 앱이 피하려는
            // "항상 100%" 류 잡음과 똑같은 사고다. (오래 떠 있던 인스턴스에서는 반대로 대부분 FREE 로
            // 보여서, 같은 서버인데 재기동 여부에 따라 수치가 뒤집힌다.)
            //
            // 실제로 일을 받은 워커만 트랜잭션에 묶인다 - 같은 시점에 Tran_index 가 있는 워커는 정확히
            // 1개로 실제 세션 수와 일치했다. 그래서 Tran_index 유무로 센다.
            long workerTotal = 0, workerBusy = 0;
            try (ResultSet rs = st.executeQuery("SHOW THREADS WHERE Type = 'WORKER'")) {
                while (rs.next()) {
                    workerTotal++;
                    if (rs.getObject("Tran_index") != null) {
                        workerBusy++;
                    }
                }
            }
            result.put("workerTotal", workerTotal);
            result.put("workerBusy", workerBusy);
            result.put("workerBusyPct", workerTotal > 0 ? round2(100.0 * workerBusy / workerTotal) : null);

            double totalMb = 0, usedMb = 0;
            boolean anyVolume = false;
            for (Map<String, Object> vol : readVolumes(st)) {
                if (!"PERMANENT".equals(vol.get("kind"))) {
                    continue;
                }
                anyVolume = true;
                totalMb += toDouble(vol.get("total_mb"));
                usedMb += toDouble(vol.get("used_mb"));
            }
            result.put("volumeTotalMb", anyVolume ? round2(totalMb) : null);
            result.put("volumeUsedMb", anyVolume ? round2(usedMb) : null);
            result.put("volumeUsedPct", anyVolume && totalMb > 0 ? round2(100.0 * usedMb / totalMb) : null);

            result.put("version", logHeaderRelease(st));
            // 다른 엔진의 KPI 카드와 자리를 맞추되, 값이 없다는 사실을 화면이 알 수 있게 명시한다.
            result.put("uptimeSeconds", null);
            result.put("uptimeLabel", null);
            result.put("uptimeNote", "CUBRID는 서버 기동 시각을 SQL로 제공하지 않아 가동 시간을 표시할 수 없습니다.");
        } catch (SQLException e) {
            result.put("error", "DB에 연결할 수 없습니다.");
        }
        return result;
    }

    /**
     * CUBRID 전용 상세 카운터 (대시보드 아코디언 행). 스레드 풀 구성, 로그/체크포인트 상태, 볼륨
     * 목록, 최근 접속 계정처럼 <b>SHOW 로 실제로 얻어지는 것만</b> 담는다.
     */
    public Map<String, Object> getStatusOverview(TargetDbConfig target) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            Map<String, Long> threadsByType = new LinkedHashMap<>();
            Map<String, Long> threadsByStatus = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SHOW THREADS")) {
                while (rs.next()) {
                    String type = rs.getString("Type");
                    String status = rs.getString("Status");
                    threadsByType.merge(type == null ? "(unknown)" : type, 1L, Long::sum);
                    threadsByStatus.merge(status == null ? "(unknown)" : status, 1L, Long::sum);
                }
            }
            result.put("threadsByType", threadsByType);
            result.put("threadsByStatus", threadsByStatus);

            Map<String, Long> tranByState = new LinkedHashMap<>();
            Map<String, Long> tranByIsolation = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SHOW TRANSACTION TABLES")) {
                while (rs.next()) {
                    if (isSystemInternal(rs)) {
                        continue;
                    }
                    String state = rs.getString("State");
                    String iso = rs.getString("Isolation");
                    tranByState.merge(state == null ? "(unknown)" : state, 1L, Long::sum);
                    tranByIsolation.merge(iso == null ? "(unknown)" : iso, 1L, Long::sum);
                }
            }
            result.put("tranByState", tranByState);
            result.put("tranByIsolation", tranByIsolation);

            try (ResultSet rs = st.executeQuery("SHOW LOG HEADER")) {
                if (rs.next()) {
                    result.put("release", rs.getString("Release"));
                    result.put("dbPageSize", rs.getObject("Db_page_size"));
                    result.put("logPageSize", rs.getObject("Log_page_size"));
                    result.put("numActiveLogPages", rs.getObject("Num_active_log_pages"));
                    result.put("nextTransId", rs.getObject("Next_trans_id"));
                    result.put("checkpointLsa", rs.getString("Checkpoint"));
                    result.put("dbCreationTime", rs.getObject("Creation_time"));
                }
            }

            result.put("volumes", readVolumes(st));

            List<Map<String, Object>> access = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SHOW ACCESS STATUS")) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("user", rs.getString("user_name"));
                    row.put("last_access_time", rs.getObject("last_access_time"));
                    row.put("last_access_host", rs.getString("last_access_host"));
                    row.put("program", rs.getString("program_name"));
                    access.add(row);
                }
            }
            result.put("accessStatus", access);
        } catch (SQLException e) {
            result.put("error", "DB에 연결할 수 없습니다.");
        }
        return result;
    }

    // =============================================================================================
    // 세션 화면
    // =============================================================================================

    /**
     * {@inheritDoc}
     *
     * <p>CUBRID 판의 두 가지 차이:
     *
     * <p><b>1. query_preview 가 항상 null 이다.</b> SQL 원문을 JDBC 로 얻을 수 없다(클래스 주석).
     * 화면은 이 칸을 '-' 로 그리고 상단 안내 문구로 이유를 밝힌다.
     *
     * <p><b>2. 모니터링 연결 자신을 목록에서 뺄 수 없다.</b> 다른 엔진은 pg_backend_pid() /
     * CONNECTION_ID() / @@SPID 로 자기 세션을 알아내 제외하지만, CUBRID 에는 현재 트랜잭션
     * 인덱스를 돌려주는 함수가 없다(CURRENT_TRAN_INDEX/CONNECTION_ID/SESSION_ID 전부 문법 오류).
     * 그래서 이 앱의 커넥션 풀도 목록에 함께 보인다 - 숨기는 것보다 정직하고, 어차피 서버 입장에서는
     * 진짜 세션이다. 대신 Client_program 이 broker*_cub_cas_* 로 보이는 이유를 화면에서 설명한다.
     *
     * <p>필터는 SYSTEM_INTERNAL(내부 트랜잭션 index 0)만 뺀다. 다른 엔진처럼 "유휴 제외" 를 하지
     * 않는 이유는 CUBRID 가 커넥션마다 트랜잭션 컨텍스트를 유지해 유휴 상태도 State=ACTIVE 로
     * 보이기 때문이다 - 그 값으로는 유휴와 실행 중을 가를 수 없다. 대신 트랜잭션/쿼리 시작 시각이
     * 있는지로 "무언가 하고 있는" 세션을 구분해 duration 을 계산한다.
     */
    @Override
    public List<Map<String, Object>> getSessionList(TargetDbConfig target) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            Timestamp serverNow = serverNow(st);
            Map<Integer, String> lockWaits = readLockWaitsByTran(st);
            try (ResultSet rs = st.executeQuery(
                    "SHOW TRANSACTION TABLES WHERE Client_type <> 'SYSTEM_INTERNAL'")) {
                while (rs.next()) {
                    Integer tranIndex = (Integer) rs.getObject("Tran_index");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("session_id", tranIndex);
                    row.put("user", rs.getString("Client_db_user"));
                    row.put("host", rs.getString("Client_host"));
                    // CUBRID 는 서버 프로세스 하나가 데이터베이스 하나를 담당하므로 세션별 DB 구분이
                    // 없다 - 등록된 인스턴스의 DB 이름이 곧 모든 세션의 DB 다.
                    row.put("db", target.sid());
                    row.put("program", rs.getString("Client_program"));
                    row.put("status", rs.getString("State"));
                    row.put("duration_seconds", elapsedSeconds(serverNow, startTime(rs)));
                    row.put("wait_event", lockWaits.get(tranIndex));
                    row.put("query_preview", null);
                    rows.add(row);
                }
            }
        }
        rows.sort((a, b) -> Double.compare(toDouble(b.get("duration_seconds")), toDouble(a.get("duration_seconds"))));
        return rows;
    }

    @Override
    public Map<String, Object> getSessionDetail(TargetDbConfig target, long sessionId) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = poolManager.getConnection(target);
             // SHOW 구문도 바인드 파라미터를 받는다(실측 확인) - 세션 id 를 문자열로 붙이지 않는다.
             PreparedStatement ps = conn.prepareStatement("SHOW TRANSACTION TABLES WHERE Tran_index = ?")) {
            ps.setInt(1, (int) sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    result.put("found", false);
                    return result;
                }
                Timestamp serverNow;
                Map<Integer, String> lockWaits;
                try (Statement st = conn.createStatement()) {
                    serverNow = serverNow(st);
                    lockWaits = readLockWaitsByTran(st);
                }
                List<Map<String, Object>> fields = new ArrayList<>();
                fields.add(field("세션 ID (Tran_index)", rs.getObject("Tran_index")));
                fields.add(field("트랜잭션 ID", rs.getObject("Tran_id")));
                fields.add(field("계정", rs.getString("Client_db_user")));
                fields.add(field("접속 호스트", rs.getString("Client_host")));
                fields.add(field("프로그램명", rs.getString("Client_program")));
                fields.add(field("클라이언트 종류", rs.getString("Client_type")));
                fields.add(field("클라이언트 PID", rs.getObject("Client_pid")));
                fields.add(field("OS 로그인 계정", rs.getString("Client_login_user")));
                fields.add(field("상태", rs.getString("State")));
                fields.add(field("격리 수준", rs.getString("Isolation")));
                fields.add(field("트랜잭션 시작", rs.getObject("Tran_start_time")));
                fields.add(field("쿼리 시작", rs.getObject("Query_start_time")));
                fields.add(field("경과 시간(초)", elapsedSeconds(serverNow, startTime(rs))));
                fields.add(field("대기 이벤트", lockWaits.get((Integer) rs.getObject("Tran_index"))));
                // 락 대기 타임아웃(-1 = 무한 대기). 무한 대기면 홀더가 커밋할 때까지 안 풀린다.
                fields.add(field("락 대기 타임아웃(ms)", rs.getObject("Wait_msecs")));
                result.put("found", true);
                result.put("session_id", rs.getObject("Tran_index"));
                result.put("fields", fields);
                // SQL 원문 없음 - 화면이 이 값이 null 이면 안내 문구를 대신 띄운다.
                result.put("sql_text", null);
                result.put("sql_text_note",
                        "CUBRID는 실행 중인 SQL 원문을 JDBC로 제공하지 않습니다. "
                                + "DB 서버에서 `cubrid killtran -q <db>` 로만 확인할 수 있습니다.");
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>CUBRID 에서는 항상 빈 목록이다.</b> 대기자(waiter)는 SHOW THREADS 로 알 수 있지만
     * 그 세션을 <b>막고 있는 holder 를 알아낼 방법이 JDBC 에 없다</b>(클래스 주석). holder 없이
     * 그리는 Holder/Waiter 트리는 트리가 아니고, 빈 blocker 칸만 늘어선 표는 "락은 있는데 원인은
     * 모른다" 는 인상만 준다. 그래서 이 엔진에서는 <b>Lock 탭 자체를 화면에서 숨기고</b>
     * (dbagent-common.js 의 RDB_TAB_BUTTONS), 대기 정보는 세션 리스트의 '대기 이벤트' 칸으로
     * 옮겼다 - "누가 얼마나 기다리는 중" 까지는 거기서 그대로 보인다.
     *
     * <p>빈 목록을 돌려주는 쪽을 택한 이유: 이 메서드가 대기자만 담아 돌려주면 화면의 트리 계산이
     * blocker 가 null 인 행을 루트로 잘못 세워, 실제와 다른 구조를 그린다.
     */
    @Override
    public List<Map<String, Object>> getLockWaits(TargetDbConfig target) throws SQLException {
        return new ArrayList<>();
    }

    /**
     * {@inheritDoc}
     *
     * <p>CUBRID 의 {@code KILL} 은 명령이라 바인드 파라미터를 받지 못한다(MySQL/MS SQL 과 같다) -
     * 그래서 시그니처가 long 이고, 검증된 숫자만 문자열로 조립된다.
     *
     * <p><b>executeUpdate 의 반환값이 실제로 종료된 트랜잭션 수다</b>(실측: 없는 id 는 0,
     * 살아 있는 세션은 1). PostgreSQL 의 pg_terminate_backend 가 false 를 돌려주는 것과 같은
     * 자리 - 0 을 성공으로 세면 "성공 1건" 인데 세션은 그대로인 것처럼 보인다.
     */
    @Override
    public Map<String, Object> killSession(TargetDbConfig target, long sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId);
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            int killed = st.executeUpdate("KILL " + sessionId);
            result.put("status", killed > 0 ? "killed" : "error");
            result.put("message", killed > 0 ? null : "이미 종료된 세션입니다.");
        } catch (SQLException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    // =============================================================================================
    // 용량 조회
    //
    // CUBRID 는 넷 중 유일하게 오라클과 같은 구조다 - 볼륨을 미리 할당해 두고 그 안을 채워 쓴다.
    // 그래서 "테이블스페이스 → 데이터파일" 2단을 <b>"볼륨 용도 → 개별 볼륨"</b> 으로 그대로
    // 대응시킬 수 있고, 할당/여유/사용률이 전부 진짜 값이다(MS SQL 과 같은 급, MySQL/PostgreSQL
    // 처럼 null 로 비울 필요가 없다).
    //
    // 2단을 테이블별로 하지 않은 이유: 테이블 용량은 SHOW HEAP CAPACITY OF <table> 로만 얻는데
    // 이 구문이 <b>대상 테이블의 락을 기다린다.</b> 실측 중 쓰기 트랜잭션이 열려 있는 테이블에
    // 걸어보니 그대로 멈췄다 - 모니터링 화면이 감시 대상 때문에 멈추는 것은 최악의 동작이라
    // 채택하지 않았다.
    // =============================================================================================

    @Override
    public Map<String, Object> getCapacity(TargetDbConfig target) throws SQLException {
        List<Map<String, Object>> volumes;
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            volumes = readVolumes(st);
        }

        // 용도별로 묶는다(Permanent data purpose / Temporary data purpose ...).
        Map<String, Map<String, Object>> byPurpose = new LinkedHashMap<>();
        for (Map<String, Object> vol : volumes) {
            String purpose = String.valueOf(vol.get("purpose"));
            Map<String, Object> agg = byPurpose.computeIfAbsent(purpose, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", k);
                m.put("table_count", 0L);       // 여기서는 '볼륨 수'
                m.put("data_mb", null);
                m.put("index_mb", null);
                m.put("used_mb", 0.0);
                m.put("total_mb", 0.0);
                m.put("free_mb", 0.0);
                m.put("used_pct", null);
                return m;
            });
            agg.put("table_count", toLong(agg.get("table_count")) + 1);
            agg.put("used_mb", toDouble(agg.get("used_mb")) + toDouble(vol.get("used_mb")));
            agg.put("total_mb", toDouble(agg.get("total_mb")) + toDouble(vol.get("total_mb")));
            agg.put("free_mb", toDouble(agg.get("free_mb")) + toDouble(vol.get("free_mb")));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> agg : byPurpose.values()) {
            double total = toDouble(agg.get("total_mb"));
            agg.put("used_mb", round2(toDouble(agg.get("used_mb"))));
            agg.put("total_mb", round2(total));
            agg.put("free_mb", round2(toDouble(agg.get("free_mb"))));
            agg.put("used_pct", total > 0 ? round2(100.0 * toDouble(agg.get("used_mb")) / total) : null);
            rows.add(agg);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unit", "볼륨 용도");
        result.put("note", "CUBRID는 볼륨을 미리 할당해 두고 그 안을 채워 쓰는 구조라(오라클 데이터파일과 같음) "
                + "할당·여유·사용률이 모두 실제 값입니다. 행을 클릭하면 그 용도의 개별 볼륨을 볼 수 있습니다. "
                + "테이블별 용량은 제공하지 않습니다 - CUBRID에서 테이블 크기를 구하는 SHOW HEAP CAPACITY 구문이 "
                + "대상 테이블의 락을 기다려, 쓰기 중인 테이블에서 조회가 멈추기 때문입니다. "
                + "'볼륨 수'는 그 용도에 속한 볼륨 개수입니다.");
        result.put("rows", rows);
        return result;
    }

    @Override
    public Map<String, Object> getCapacityDetail(TargetDbConfig target, String scope) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target);
             Statement st = conn.createStatement()) {
            for (Map<String, Object> vol : readVolumes(st)) {
                if (!scope.equals(vol.get("purpose"))) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", vol.get("name"));
                row.put("row_count", null);
                row.put("data_mb", null);
                row.put("index_mb", null);
                row.put("total_mb", vol.get("total_mb"));
                row.put("free_mb", vol.get("free_mb"));
                rows.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope);
        result.put("note", rows.isEmpty()
                ? "이 용도에 해당하는 볼륨이 없습니다."
                : "볼륨 파일 경로와 할당/여유 크기입니다. 행 수·데이터·인덱스 크기는 볼륨 단위로는 "
                        + "구분되지 않아 표시하지 않습니다.");
        result.put("rows", rows);
        return result;
    }

    // =============================================================================================
    // 내부 헬퍼
    // =============================================================================================

    /**
     * 볼륨 목록. CUBRID 는 볼륨을 한 번에 주는 SQL 이 없어 번호를 하나씩 두드린다
     * (MAX_PERMANENT_VOLUMES 상수 주석 참고). 없는 번호는 예외가 나므로 그것을 종료 조건으로 쓴다 -
     * 예외를 삼키는 자리라 <b>연결이 끊긴 경우와 구분해야 한다</b>: 첫 볼륨(0번)조차 실패하면
     * 그건 "볼륨이 없다" 가 아니라 연결/권한 문제이므로 그대로 던진다.
     */
    private List<Map<String, Object>> readVolumes(Statement st) throws SQLException {
        List<Map<String, Object>> volumes = new ArrayList<>();
        for (int volid = 0; volid < MAX_PERMANENT_VOLUMES; volid++) {
            Map<String, Object> vol = readVolume(st, volid);
            if (vol == null) {
                if (volid == 0) {
                    throw new SQLException("볼륨 정보를 읽을 수 없습니다 (SHOW VOLUME HEADER OF 0 실패).");
                }
                break;
            }
            volumes.add(vol);
        }
        for (int i = 0; i < MAX_TEMP_VOLUMES; i++) {
            Map<String, Object> vol = readVolume(st, FIRST_TEMP_VOLID - i);
            if (vol == null) {
                break;
            }
            volumes.add(vol);
        }
        return volumes;
    }

    /** 볼륨 하나. 없는 볼륨이면 null (예외를 종료 조건으로 쓰는 자리 - readVolumes 주석 참고). */
    private Map<String, Object> readVolume(Statement st, int volid) {
        try (ResultSet rs = st.executeQuery("SHOW VOLUME HEADER OF " + volid)) {
            if (!rs.next()) {
                return null;
            }
            // 크기 = 섹터 수 × 섹터당 페이지 수 × 페이지 크기.
            // (실측 검증: 128 × 64 × 16384 = 128MB, cubrid spacedb 의 "128.0 M" 과 일치)
            long pageSize = toLong(rs.getObject("Io_page_size"));
            long sectorPages = toLong(rs.getObject("Sector_size_in_pages"));
            long totalSectors = toLong(rs.getObject("Num_total_sectors"));
            long freeSectors = toLong(rs.getObject("Num_free_sectors"));
            double bytesPerSector = (double) sectorPages * pageSize;
            double totalMb = totalSectors * bytesPerSector / 1048576.0;
            double freeMb = freeSectors * bytesPerSector / 1048576.0;
            double usedMb = totalMb - freeMb;

            String purpose = rs.getString("Purpose");
            String type = rs.getString("Type");
            Map<String, Object> vol = new LinkedHashMap<>();
            vol.put("volid", volid);
            vol.put("name", rs.getString("Full_name"));
            vol.put("purpose", purpose);
            vol.put("type", type);
            // 영구/임시 구분 - 대시보드 KPI 는 영구 볼륨만 합산한다(임시 볼륨은 정렬·해시 조인이
            // 잠깐 쓰고 반납하는 공간이라, 같이 더하면 사용률이 실제보다 낮게 보인다).
            vol.put("kind", type != null && type.toUpperCase(Locale.US).contains("TEMPORARY")
                    ? "TEMPORARY" : "PERMANENT");
            vol.put("total_mb", round2(totalMb));
            vol.put("used_mb", round2(usedMb));
            vol.put("free_mb", round2(freeMb));
            vol.put("used_pct", totalMb > 0 ? round2(100.0 * usedMb / totalMb) : null);
            return vol;
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * 락을 기다리는 중인 세션: Tran_index → 대기 설명("X_LOCK 대기 (12.3초)").
     *
     * <p>Lockwait_msecs 는 <b>경과 시간이 아니라 타임아웃 설정값</b>이다(-1 = 무한 대기) - 실측에서
     * 5초를 기다린 세션도 -1 이었다. 그래서 대기 시간은 Lockwait_start_time 과 서버 현재 시각의
     * 차이로 직접 계산한다.
     */
    private Map<Integer, String> readLockWaitsByTran(Statement st) throws SQLException {
        Map<Integer, String> byTran = new HashMap<>();
        Timestamp serverNow = serverNow(st);
        try (ResultSet rs = st.executeQuery("SHOW THREADS WHERE Lockwait_msecs IS NOT NULL")) {
            while (rs.next()) {
                Object tranIndex = rs.getObject("Tran_index");
                if (!(tranIndex instanceof Integer)) {
                    continue;
                }
                String mode = rs.getString("Lockwait_blocked_mode");
                Double waited = elapsedSeconds(serverNow, asTimestamp(rs.getObject("Lockwait_start_time")));
                StringBuilder sb = new StringBuilder(mode == null ? "락" : mode).append(" 대기");
                if (waited != null) {
                    sb.append(" (").append(String.format(Locale.US, "%.1f", waited)).append("초)");
                }
                byTran.put((Integer) tranIndex, sb.toString());
            }
        }
        return byTran;
    }

    /**
     * 서버의 현재 시각. 경과 시간을 <b>앱 호스트 시계로 계산하면 안 된다</b> - 실측 환경만 해도
     * DB 컨테이너는 UTC, 앱 호스트는 KST 라 9시간이 어긋난다. 시작 시각과 현재 시각을 모두
     * 서버에서 받아 그 차이만 쓴다.
     */
    private Timestamp serverNow(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT SYS_DATETIME FROM db_root")) {
            return rs.next() ? asTimestamp(rs.getObject(1)) : null;
        }
    }

    /** 쿼리 시작 시각이 있으면 그것을, 없으면 트랜잭션 시작 시각을 쓴다. */
    private Timestamp startTime(ResultSet rs) throws SQLException {
        Timestamp queryStart = asTimestamp(rs.getObject("Query_start_time"));
        return queryStart != null ? queryStart : asTimestamp(rs.getObject("Tran_start_time"));
    }

    private Timestamp asTimestamp(Object value) {
        return value instanceof Timestamp ? (Timestamp) value : null;
    }

    private Double elapsedSeconds(Timestamp now, Timestamp start) {
        if (now == null || start == null) {
            return null;
        }
        // 서버 시계 기준의 두 시각 차이. 음수(시계 보정 등)는 0 으로 눌러 화면에 -3.2초가 뜨지 않게 한다.
        double sec = (now.getTime() - start.getTime()) / 1000.0;
        return round2(Math.max(0.0, sec));
    }

    private boolean isSystemInternal(ResultSet rs) throws SQLException {
        return "SYSTEM_INTERNAL".equals(rs.getString("Client_type"));
    }

    private Map<String, Object> field(String label, Object value) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("label", label);
        f.put("value", value);
        return f;
    }

    private double toDouble(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
    }

    private long toLong(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private String logHeaderRelease(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SHOW LOG HEADER")) {
            return rs.next() ? rs.getString("Release") : "";
        }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
