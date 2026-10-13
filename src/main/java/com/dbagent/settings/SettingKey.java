package com.dbagent.settings;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static com.dbagent.settings.SettingGroup.*;
import static com.dbagent.settings.SettingType.*;

/**
 * 환경설정 키 목록(레지스트리) - 키 이름·종류·기본값·범위·설명을 코드 한 곳에 둔다
 * (환경설정 DB화 P1-2, 2026-10-04, 설계문서/환경설정_DB화_설계_2026-10-04.md 4절).
 *
 * <p>DB 에는 "기본값과 다르게 바꾼 값"만 저장하고, 키 정의(타입·범위·설명)는 여기에만 있다. 그래서 코드의 기본값을
 * 고치면 DB 에 굳은 옛 기본값 없이 바로 반영되고, 키를 추가하면 화면에 자동으로 나타난다.
 *
 * <p>기본값은 기존 {@code @Value("${키:기본값}")} 의 코드 기본값과 같아야 한다(동작 변화 0). 아직 소비자 코드가 없는
 * 신규 키(홀더 주의 개수, TX/TM 대기 주의·위험)는 기존 nd-lock.js 에 하드코딩돼 있던 숫자를 기본값으로 쓴다.
 *
 * <p>현재 모든 키는 "재시작 후 적용"이다. 소비자 코드가 {@code SettingsService} 조회로 바뀐 키만 {@code live()} 를
 * 붙여 "저장 즉시 적용"으로 올린다(P3). 스케줄러 주기처럼 원래 재시작이 필요한 키는 끝까지 live 를 붙이지 않는다.
 *
 * <p>Java 8 문법만 쓴다 (AIX 와 같은 소스).
 */
public enum SettingKey {

    // ───────────────────────── 화면 · 인증 ─────────────────────────
    UI_POLLING_INTERVAL_MS(d("dbagent.ui.polling-interval-ms", INT, "2000", UI, "대시보드 갱신 주기",
            "대시보드가 서버에서 값을 다시 가져오는 간격입니다.").unit("ms").range(1000, 60000)
            .detail("낮추면 화면은 빨라지지만 서버와 대상 DB 조회가 늘어납니다. 1000ms 는 여러 위젯·탭이 동시에 조회하면 "
                    + "커넥션 풀이 고갈되어 정상 DB 를 접속 불가로 오인한 적이 있어 2000~5000 을 권장합니다. "
                    + "탭이 보이지 않으면 갱신을 멈춥니다. 화면은 로드할 때 이 값을 한 번 읽으므로, 저장 후 이미 열려 있는 화면은 새로고침해야 새 값을 씁니다.")),
    FLEET_OVERVIEW_POLLING_INTERVAL_MS(d("dbagent.fleet-overview.polling-interval-ms", INT, "3000", UI, "전체 현황 갱신 주기",
            "전체 DB 현황 화면의 갱신 간격입니다.").unit("ms").range(1000, 60000)
            .detail("탭이 보일 때만 동작합니다. 백그라운드 탭은 조회하지 않습니다. 화면을 로드할 때 이 값을 한 번 읽으므로, 저장 후 열려 있는 화면은 새로고침해야 새 값을 씁니다.")),
    // AAS 차트의 CPU 기준선(판정 기준)을 논리 CPU(NUM_CPUS)와 물리 코어(NUM_CPU_CORES) 중 무엇으로 볼지(2026-10-11 오케스트레이터 요청).
    // 기본은 논리 CPU - 2026-09-28 운영 실측(AIX SMT 72코어 서버에서 코어 기준이면 AAS가 늘 기준선을 넘음)으로 정한 값이다. CPU 사용률(%)의 분모는 바꾸지 않는다.
    AAS_CPU_PHYSICAL(d("dbagent.ui.aas-cpu-physical", BOOL, "false", UI, "AAS 차트 CPU 기준선을 물리 코어로",
            "켜면 신규 대시보드 AAS(평균 활성 세션) 차트, 실시간 세션 모니터링(Current Session)의 Active Session 차트, 성능 분석 세션 분석 차트의 CPU 기준선과 그 판정(CPU 초과 시간·초과 비율·부하율·진단 문구 등)을 논리 CPU 수 대신 물리 코어 수로 합니다.")
            .detail("끄면(기본) 논리 CPU(NUM_CPUS)가 기준선이고 물리 코어(NUM_CPU_CORES)는 점선 보조선으로만 보입니다. 켜면 기준이 바뀌어 물리 코어가 기준선, 논리 CPU가 보조선이 됩니다. "
                    + "SMT가 있는 서버(AIX 등)에서는 물리 코어 기준이면 AAS가 기준선을 넘는 시간이 훨씬 많아집니다. CPU 사용률(%)의 분모와 다른 화면은 바뀌지 않습니다. "
                    + "물리 코어 수를 읽지 못한 DB는 켜도 논리 CPU 기준으로 보입니다. 신규 대시보드는 AAS 차트를 갱신할 때(약 1분마다) 이 값을 다시 읽어 새로고침 없이 반영되고, 나머지 두 차트는 다음 조회·갱신 때 반영됩니다.")),
    UI_SESSION_REFRESH_SECONDS(d("dbagent.ui.session-refresh-seconds", INT, "3", UI, "세션 목록 갱신 주기",
            "Current Session 세션 목록(4개 탭)의 기본 갱신 주기입니다.").unit("초").range(1, 60)
            .detail("화면의 '리프레쉬 주기' 입력칸 기본값입니다. 위쪽 두 그래프는 이 값과 무관하게 따로 갱신됩니다. 화면을 로드할 때 한 번 읽으므로 저장 후 열려 있는 화면은 새로고침해야 반영됩니다.")),
    AUTH_SESSION_TTL_DAYS(d("dbagent.auth.session-ttl-days", INT, "30", UI, "로그인 유지 기간",
            "로그인 토큰이 유효한 기간입니다.").unit("일").range(1, 365)
            .detail("로그아웃을 누르지 않아도 이 기간이 지나면 토큰이 만료되고, 매일 새벽 3시 정리 작업이 행을 삭제합니다.")),

    // ───────────────────────── Lock · 실시간 (DB별 재정의 가능) ─────────────────────────
    TM_HOLDER_LAST_CALL_ET_SECONDS(d("dbagent.monitor.tm-holder-last-call-et-seconds", INT, "60", LOCK, "Holder 경과 기준",
            "TM Lock 을 잡은 세션이 이 시간 이상 아무 동작이 없으면 '오래 붙잡은 Holder' 로 봅니다.").unit("초").range(10, 3600).perDb()
            .detail("낮추면 일찍 장애로 판정되어 오탐이 늘고, 높이면 늦게 판정됩니다. 권장 30~300초. 조회 SQL 조건에 정수로 "
                    + "들어가므로 정수만 허용합니다.")
            .warning("30초 미만은 정상 배치 작업도 장애 후보로 잡을 수 있습니다.")),
    TM_HOLDER_INCIDENT_COUNT(d("dbagent.monitor.tm-holder-incident-count", INT, "6", LOCK, "장애 판정 Holder 수",
            "기준 시간을 넘긴 Holder 가 이 개수 이상이면 '장애 처리' 버튼이 나타납니다.").unit("개").range(1, 100).perDb()
            .detail("'장애 처리 · TM Holder N개 KILL' 버튼의 노출 조건입니다. 주의 개수보다 커야 합니다.")),
    TM_HOLDER_WARN_COUNT(d("dbagent.monitor.tm-holder-warn-count", INT, "3", LOCK, "Holder 주의 개수",
            "Holder 가 이 개수 이상이면 주의 색으로 표시합니다. 장애 판정 개수보다 작아야 합니다. 0이면 주의 단계를 쓰지 않습니다.").unit("개").range(0, 100).perDb()
            .detail("기존에는 화면 코드(nd-lock.js)에 3으로 고정돼 있었습니다.")),
    LOCK_TX_WAIT_WARN(d("dbagent.monitor.lock-tx-wait-warn", INT, "5", LOCK, "TX 대기 주의",
            "행 Lock(TX) 대기 세션 수가 이 값 이상이면 주의 색으로 표시합니다.").unit("세션").range(1, 1000).perDb()
            .detail("위험 기준보다 작아야 합니다. 기존에는 화면 코드에 5로 고정돼 있었습니다.")),
    LOCK_TX_WAIT_CRIT(d("dbagent.monitor.lock-tx-wait-crit", INT, "10", LOCK, "TX 대기 위험",
            "행 Lock(TX) 대기 세션 수가 이 값 이상이면 위험 색으로 표시합니다.").unit("세션").range(1, 1000).perDb()
            .detail("기존에는 화면 코드에 10으로 고정돼 있었습니다.")),
    LOCK_TM_WAIT_WARN(d("dbagent.monitor.lock-tm-wait-warn", INT, "3", LOCK, "TM 대기 주의",
            "테이블 Lock(TM) 대기 세션 수가 이 값 이상이면 주의 색으로 표시합니다.").unit("세션").range(1, 1000).perDb()
            .detail("위험 기준보다 작아야 합니다. 기존에는 화면 코드에 3으로 고정돼 있었습니다.")),
    LOCK_TM_WAIT_CRIT(d("dbagent.monitor.lock-tm-wait-crit", INT, "6", LOCK, "TM 대기 위험",
            "테이블 Lock(TM) 대기 세션 수가 이 값 이상이면 위험 색으로 표시합니다.").unit("세션").range(1, 1000).perDb()
            .detail("기존에는 화면 코드에 6으로 고정돼 있었습니다.")),
    TM_HOLDER_KILL_BLOCKING_ONLY(d("dbagent.monitor.tm-holder-kill-blocking-only", BOOL, "false", LOCK,
            "KILL 대상: 다른 세션을 막는 Holder 만", "켜면 '장애 처리' 시 실제로 다른 세션을 막고 있는 Holder 만 KILL 대상으로 좁힙니다.")
            .perDb().danger()
            .detail("끄면 기준 시간을 넘긴 모든 Holder 가 KILL 대상입니다.")
            .warning("KILL 대상 범위에 영향을 주는 설정입니다. 변경 사유가 이력에 기록됩니다.")),
    TM_HOLDER_KILL_INACTIVE_ONLY(d("dbagent.monitor.tm-holder-kill-inactive-only", BOOL, "false", LOCK,
            "KILL 대상: INACTIVE 세션만", "켜면 '장애 처리' 시 INACTIVE 상태 Holder 만 KILL 대상으로 좁힙니다.")
            .perDb().danger()
            .detail("실행 중(ACTIVE)인 세션을 건드리지 않으려는 안전장치입니다.")
            .warning("KILL 대상 범위에 영향을 주는 설정입니다. 변경 사유가 이력에 기록됩니다.")),
    LOCK_TX_ROW_LOCK_ONLY(d("dbagent.monitor.lock-tx-row-lock-only", BOOL, "false", LOCK, "TX 집계: 행 Lock 대기만",
            "켜면 TX 대기 집계에서 행 Lock 대기만 셉니다.")
            .detail("TX 슬롯·ITL 대기 등을 제외하고 행 Lock 대기만 집계합니다.")),
    LONG_RUNNING_QUERY_SECONDS(d("dbagent.monitor.long-running-query-seconds", INT, "300", LOCK, "장시간 실행 쿼리 기준",
            "이 시간 이상 실행 중인 쿼리를 '장시간 실행 쿼리' 카드에 표시합니다. 0이면 끕니다.").unit("초").range(0, 86400).perDb()
            .detail("0(끔) 또는 30 이상을 권장합니다.")),
    LOCK_CACHE_MILLIS(d("dbagent.monitor.lock-cache-millis", INT, "2000", LOCK, "Lock 조회 결과 캐시 시간",
            "Lock 실시간 조회 결과를 이 시간 동안 재사용합니다.").unit("ms").range(500, 60000)
            .detail("화면이 여러 개여도 원본 DB 조회는 이 주기당 1회만 합니다. 낮추면 최신성은 올라가지만 대상 DB 부하가 늘어납니다.")),
    LOCK_KEEPALIVE_MINUTES(d("dbagent.monitor.lock-keepalive-minutes", INT, "0", LOCK, "최근 본 DB 백그라운드 수집 유지",
            "0 이면 끔. 양수이면 화면을 닫아도 최근 본 DB 의 Lock 을 이 시간(분) 동안 계속 수집합니다.").unit("분").range(0, 1440).danger()
            .detail("Lock 차트의 빈 구간을 줄이려고 화면 없이도 3초마다 대상 DB 를 조회합니다. 기본 꺼짐. 저장하면 재시작 없이 바로 켜지고 꺼집니다 (켜기 전에 본 DB 는 기억하지 않으므로, 켠 뒤 화면에서 본 DB 부터 수집합니다).")
            .warning("대상 DB 에 상시 조회 부하가 생깁니다. 켜기 전에 DB 부하 영향을 확인하세요.")),
    LOCK_KEEPALIVE_MAX_DBS(d("dbagent.monitor.lock-keepalive-max-dbs", INT, "5", LOCK, "백그라운드 수집 최대 DB 수",
            "백그라운드 수집을 동시에 유지할 DB 개수의 상한입니다.").unit("개").range(1, 50)
            .detail("수집 유지 기능이 꺼져 있으면(0분) 의미가 없습니다.")),
    // 성능 분석 개편 Phase 1a(2026-10-09, 설계문서/성능분석 메뉴 설계.md 6장): Lock 분석용 대기 이력 수집
    LOCK_COLLECT_ENABLED(d("dbagent.perf.lock-collect-enabled", BOOL, "true", LOCK, "Lock 분석 수집",
            "성능 분석의 Lock 분석에 쓰는 락 대기 이력(TX·TM)을 수집합니다.")
            .detail("기존 60초 ASH 수집에 락 이벤트 4종의 컬럼을 더 읽습니다(새 원본 조회 없음, 같은 ASH 구간). 끄면 새 락 발생 건이 쌓이지 않고 이미 쌓인 이력은 보관 기간까지 남습니다.")),
    BLOCKER_DETAIL_ENABLED(d("dbagent.perf.blocker-detail-enabled", BOOL, "false", LOCK, "Lock 분석: 블로커 상세 수집",
            "락이 잡혔을 때 블로커 세션 정보·TM 보유 모드·FK 인덱스 여부를 대상 DB에서 추가로 조회해 원인을 더 정확히 판정합니다.")
            .detail("락 대기자가 있을 때만 v$session(블로커 SID 목록, 바인드), v$lock(TM 건의 블로커만, type='TM'), v$sqlstats(TM 건의 블로커 SQL 앞부분), 필요 시 dba_constraints·dba_ind_columns(테이블 단위 하루 1회 캐시)를 조회합니다. 모두 lock-query-timeout-seconds 제한이 걸립니다. 꺼져 있으면 TX_UNCOMMITTED·TM_DDL·TM_LOCK_TABLE·TM_FK_NOINDEX는 판정하지 않습니다.")
            .warning("대상 DB에 락 발생 중에만 추가 조회가 나갑니다. 켜기 전에 DB 부하 영향을 확인하세요.")),
    BLOCKER_TM_MODE_ENABLED(d("dbagent.perf.blocker-tm-mode-enabled", BOOL, "true", LOCK, "Lock 분석: TM 보유 모드 조회",
            "블로커 상세 수집이 켜져 있을 때, TM 락 건의 블로커가 잡은 TM 모드를 v$lock 에서 따로 조회할지 정합니다.")
            .detail("v$lock(type='TM', 블로커 sid 한정)을 읽는 유일한 조회입니다. 끄면 이 조회만 건너뛰고 블로커 세션·SQL 조회는 그대로입니다. 보유 모드를 모르면 TM_DDL·TM_LOCK_TABLE 판정 신뢰도가 낮아지고 TM_FK_NOINDEX 는 요청 모드만으로 판정합니다. 블로커 상세 수집이 꺼져 있으면 의미가 없습니다.")
            .warning("폐쇄망에서 v$lock 조회가 느렸던 전례가 있습니다. 락 발생 시 지연이 보이면 끄세요.")),
    LOCK_GAP_SEC(d("dbagent.perf.lock-gap-sec", INT, "30", LOCK, "Lock 발생 건 끊김 기준",
            "같은 블로커·같은 락 유형의 대기 샘플이 이 시간보다 오래 끊기면 별개의 락 발생 건으로 나눕니다.").unit("초").range(5, 600)
            .detail("ASH 샘플은 약 1초 간격입니다. 낮추면 짧은 중단에도 건이 잘게 나뉘고, 높이면 간헐적으로 이어지는 락이 한 건으로 묶입니다.")),

    // 성능 분석 개편 Phase 2(2026-10-09, 설계 2장): 시스템 부하 수집(v$sysmetric·v$eventmetric 60초 값) - 새 원본 조회라 기본 꺼짐
    SYS_COLLECT_ENABLED(d("dbagent.perf.sys-collect-enabled", BOOL, "false", LOCK, "시스템 부하 수집",
            "성능 분석의 시스템 부하 탭에 쓰는 지표(DB Time·Executions 등)와 대기 이벤트를 1분마다 수집합니다.")
            .detail("60초 샘플러가 이미 접속한 연결로 v$sysmetric(60초 그룹 9개 지표)과 v$eventmetric(Idle 제외, 대기가 있는 이벤트 대기 시간 상위 50개(분당 100µs 미만 제외))을 한 번씩 읽습니다. 지난주 같은 시간 대비는 설치 후 1주일이 지나야 의미가 있습니다. PDB 서비스로 등록한 DB는 이 뷰가 비어 있어 수집하지 않고 화면에 판단 보류로 표시합니다.")
            .warning("대상 DB에 1분마다 조회 2건이 추가됩니다. 켜기 전에 부하 영향을 확인하세요.")),
    DIAG_CARD_PCT(d("dbagent.perf.diag-card-pct", INT, "30", LOCK, "시스템 부하: 지표 카드 강조 기준",
            "지난주 같은 시간 대비 이 비율(%) 이상 늘거나 줄면 지표 카드를 색으로 강조합니다.").unit("%").range(5, 500)),
    DIAG_WAIT_RATIO_PCT(d("dbagent.perf.diag-wait-ratio-pct", INT, "200", LOCK, "시스템 부하: 대기 평균 악화 기준",
            "대기 이벤트 평균 대기가 지난주 평균의 이 비율(%) 이상이면 배지와 진단 힌트(redo 쓰기 지연 의심)를 표시합니다.").unit("%").range(110, 2000)),
    DIAG_HARD_PARSE_PCT(d("dbagent.perf.diag-hard-parse-pct", INT, "100", LOCK, "시스템 부하: Hard parse 증가 기준",
            "Hard parse(초당)가 지난주 같은 시간보다 이 비율(%) 이상 늘고 현재 초당 1건 이상일 때 리터럴 SQL 점검 힌트를 표시합니다(기준이 아주 작을 때의 작은 변동 제외).").unit("%").range(10, 2000)),
    DIAG_SEQ_READ_MS(d("dbagent.perf.diag-seq-read-ms", INT, "10", LOCK, "시스템 부하: 단일 블록 읽기 지연 기준",
            "db file sequential read 평균 대기가 이 시간(ms) 이상이면 스토리지 응답 지연 의심 힌트를 표시합니다.").unit("ms").range(1, 1000)),

    // 성능 분석 개편 Phase 5(2026-10-11, 설계 5장): 오브젝트·I/O - 세그먼트·파일 I/O·Redo 스위치·Temp 사용 수집. 새 원본 조회라 기본 꺼짐
    OBJ_COLLECT_ENABLED(d("dbagent.perf.obj-collect-enabled", BOOL, "false", LOCK, "오브젝트·I/O 수집",
            "성능 분석의 오브젝트·I/O 탭에 쓰는 세그먼트 통계·데이터파일 I/O·Redo 스위치·Temp 사용 SQL을 주기적으로 수집합니다.")
            .detail("DB마다 따로 켜고 끌 수 있습니다(적용 대상 DB를 고르지 않으면 전체 기본값). 데이터파일이나 세그먼트가 매우 많은 DB만 끄거나 일부 DB만 켤 수 있습니다. "
                    + "데이터파일 I/O는 DB가 10분마다 계산해 두는 v$filemetric(최근 10분 구간 값)을 읽고, 그 뷰를 쓸 수 없는 DB(PDB 접속 등)만 v$filestat 누적값을 읽습니다. "
                    + "수집 주기를 10분보다 길게 잡으면 그 사이 구간의 데이터파일 I/O는 건너뜁니다(평균 읽기·쓰기 ms는 그대로 유효). "
                    + "수집 주기(기본 10분)마다 대상 DB에서 v$segstat(통계 5종, 값이 0보다 큰 행 전체), v$filemetric(또는 v$filestat)·v$tempstat(파일 수만큼), "
                    + "v$log_history(최근 24시간 시간별 건수), v$tempseg_usage(SQL별 Temp 사용)를 읽고, 누적값은 직전 값과의 차이(델타)로 바꿔 통계별 상위 50개만 저장합니다. "
                    + "모든 조회에 쿼리 제한 시간이 있고, 실패한 항목은 건너뜁니다(0으로 저장하지 않고 나머지 항목은 저장합니다). 대용량 DB에서는 v$segstat 한 번 읽기가 가장 무겁고, 세그먼트 행이 40만 개를 넘으면 그 DB의 세그먼트 수집을 6시간 쉽니다. "
                    + "앱을 재시작하거나 수집을 껐다 켠 뒤, 대상 DB 인스턴스가 재시작된 뒤의 첫 주기는 기준값만 기억하고 저장하지 않습니다(그 다음 주기부터 쌓입니다. v$filemetric으로 읽는 데이터파일 I/O만 첫 주기부터 저장합니다). "
                    + "세그먼트는 dataobj#(세그먼트) 기준으로 세기 때문에 클러스터에 묶인 테이블은 구성 테이블 중 하나의 이름으로 보일 수 있습니다. CDB 루트로 등록한 DB는 루트 컨테이너의 오브젝트·파일만 읽습니다(PDB 접속을 권장합니다).")
            .warning("대상 DB에 수집 주기마다 조회 6건(처음 보는 오브젝트의 이름을 확인하는 dba_objects 조회가 가끔 최대 4건, 처음 보는 데이터파일의 이름 조회가 가끔 1건 더)이 추가됩니다. 켜기 전에 부하 영향을 확인하세요.")
            .perDb()),
    OBJ_COLLECT_INTERVAL_MIN(d("dbagent.perf.obj-collect-interval-min", INT, "10", LOCK, "오브젝트·I/O 수집 주기",
            "오브젝트·I/O 수집을 실행하는 간격입니다.").unit("분").range(1, 120)
            .detail("스케줄러 주기라 저장 후 앱을 다시 시작해야 적용됩니다. 짧을수록 세부 변화를 보지만 대상 DB 조회와 저장 행이 늘어납니다.")),
    REDO_SWITCH_WARN_PER_HOUR(d("dbagent.perf.redo-switch-warn-per-hour", INT, "4", LOCK, "Redo 로그 스위치 기준선",
            "시간당 Redo 로그 스위치가 이 횟수를 넘으면 차트 막대를 다른 색으로 표시하고 로그 그룹 크기 조정 힌트를 보여 줍니다.").unit("회/시간").range(1, 1000)),

    // 성능 분석 개편 Phase 3(2026-10-09, 설계 3장): SQL 분석 - 기존 1분 v$sqlstats 델타 수집을 확장한다(새 원본 조회 없음)
    SQLSTAT_NEW_CURSOR_MAX_ROWS(d("dbagent.monitor.sqlstat-new-cursor-max-rows", INT, "100", LOCK, "SQL 분석: 새 커서 저장 상한",
            "1분 수집에서 처음 보는 SQL(주로 리터럴 SQL)의 누적값을 사이클당 이 개수까지만 SQL 단위로 저장합니다. 0이면 새 커서를 저장하지 않습니다(리터럴 보기도 비게 됩니다).").unit("개").range(0, 5000)
            .detail("처음 보인 커서의 누적 통계를 그 1분의 델타로 인정해야 한 번만 실행되고 사라지는 리터럴 SQL을 잡을 수 있습니다. 상한을 넘는 새 커서는 SQL 단위 행을 저장하지 않고, FORCE_MATCHING_SIGNATURE 단위 집계(리터럴 보기용)에만 반영합니다. 저장 행이 늘어나는 만큼 metrics DB가 커집니다(행당 약 100~150바이트).")),
    SQLSTAT_NEW_CURSOR_MAX_EXECS(d("dbagent.monitor.sqlstat-new-cursor-max-execs", INT, "1000", LOCK, "SQL 분석: 새 커서 인정 최대 실행 수",
            "처음 보인 커서의 누적 실행 수가 이 값 이하일 때만 새 SQL로 보고 누적값을 델타로 인정합니다.").unit("회").range(1, 1000000)
            .detail("오래 살아 있던 커서가 다시 보이기 시작한 경우(누적 실행 수가 큼)에는 지난 누적값 전체가 한 분에 몰려 급등값이 되므로 기준값만 기억하고 다음 분부터 셉니다.")),
    SQLSTAT_NEW_CURSOR_MAX_ELAPSED_SEC(d("dbagent.monitor.sqlstat-new-cursor-max-elapsed-sec", INT, "120", LOCK, "SQL 분석: 새 커서 인정 최대 Elapsed",
            "처음 보인 커서의 누적 Elapsed가 이 값(초) 이하일 때만 새 SQL로 보고 누적값을 델타로 인정합니다.").unit("초").range(1, 86400)
            .detail("오래 쉬다가 돌아온 배치 SQL의 누적 Elapsed 전체가 한 분의 델타로 다시 더해지는 것을 막습니다. 새 커서 인정 최대 실행 수와 함께 적용됩니다.")),
    SQLSTAT_TEXT_CAPTURE_MAX_PER_CYCLE(d("dbagent.monitor.sqlstat-text-capture-max-per-cycle", INT, "0", LOCK, "SQL 분석: 수집 시 SQL 텍스트 저장 상한",
            "1분 SQL 통계 수집 때 텍스트가 아직 없는 SQL을 Elapsed 큰 순으로 사이클당 이 개수까지 읽어 저장합니다. 0이면 저장하지 않습니다(화면을 열 때만 읽음).").unit("개").range(0, 50)
            .detail("SQL 텍스트는 화면을 열 때 대상 DB에서 읽기 때문에, 그 전에 공유 풀에서 밀려난 SQL은 SQL 분석 목록과 차트 범례에 SQL_ID만 보입니다. 이 값을 켜면 수집 시점에 미리 저장해 두어 나중에도 텍스트를 볼 수 있습니다. SYS가 파싱한 Oracle 내부 SQL은 일반 SQL을 저장하고 남는 자리에만 저장합니다. 대상 DB에는 SQL_ID 단건 조회가 사이클당 이 개수의 5배까지 늘어나며(이미 저장한 SQL은 다시 묻지 않고, 한 사이클에 최대 1초까지만 씁니다), SQL_ID당 한 행(앞 1000자)이 성능 분석 저장소에 쌓입니다. 리터럴 SQL이 많은 DB에서는 저장 행이 빠르게 늘 수 있어 상한을 50으로 제한합니다.")),
    DIAG_SQL_SURGE_PCT(d("dbagent.perf.diag-sql-surge-pct", INT, "100", LOCK, "SQL 분석: 급증 기준",
            "지난주 같은 시간대보다 실행 수가 이 비율(%) 이상 늘면 급증 배지를 표시합니다(구간 실행 수가 급증 최소 실행 수 이상일 때).").unit("%").range(10, 10000)),
    DIAG_SQL_SURGE_MIN_EXECS(d("dbagent.perf.diag-sql-surge-min-execs", INT, "10000", LOCK, "SQL 분석: 급증 최소 실행 수",
            "급증 배지를 표시하려면 구간 실행 수가 이 값 이상이어야 합니다.").unit("회").range(1, 100000000)),
    DIAG_SQL_NPLUS1_EXEC_SEC(d("dbagent.perf.diag-sql-nplus1-exec-sec", INT, "50", LOCK, "SQL 분석: N+1 의심 초당 실행",
            "SELECT 문이 초당 이 횟수 이상 실행되고 실행당 행 수가 1.01 이하이면 N+1 의심 배지를 표시합니다.").unit("회/초").range(1, 100000)),
    DIAG_SQL_REUSE_EXEC_SEC(d("dbagent.perf.diag-sql-reuse-exec-sec", INT, "10", LOCK, "SQL 분석: 커서 재사용 안 됨 초당 실행",
            "Parse/Exec가 기준 이상이고 초당 이 횟수 이상 실행되면 커서 재사용 X 배지를 표시합니다.").unit("회/초").range(1, 100000)),
    DIAG_SQL_REUSE_PARSE_PCT(d("dbagent.perf.diag-sql-reuse-parse-pct", INT, "90", LOCK, "SQL 분석: 커서 재사용 안 됨 Parse 비율",
            "실행 1회당 Parse 호출 비율이 이 값(%) 이상이면 커서 재사용 X 배지를 표시합니다.").unit("%").range(10, 1000)),
    DIAG_SQL_LITERAL_ID_COUNT(d("dbagent.perf.diag-sql-literal-id-count", INT, "50", LOCK, "SQL 분석: 리터럴 SQL_ID 수 기준",
            "같은 FORCE_MATCHING_SIGNATURE에 속한 SQL_ID가 이 개수 이상이면 리터럴 배지를 표시합니다.").unit("개").range(2, 1000000)),

    // ───────────────────────── 점검 알림 ─────────────────────────
    CHECK_INTERVAL_MINUTES(d("dbagent.monitor.check-interval-minutes", INT, "10", CHECK, "점검 수집 주기",
            "점검 알림 수집 작업이 도는 간격입니다.").unit("분").range(1, 1440)
            .detail("스케줄러 기동 시 한 번만 읽으므로 변경 후 재시작이 필요합니다.")),
    CHECK_DAILY_HOUR(d("dbagent.monitor.check-daily-hour", INT, "3", CHECK, "일 1회 점검 시각",
            "무거운 일 단위 점검을 실행하는 시각(시)입니다.").unit("시").range(0, 23)
            .detail("스케줄러 cron 에 들어가므로 변경 후 재시작이 필요합니다.")),
    CHECK_QUERY_TIMEOUT_SECONDS(d("dbagent.monitor.check-query-timeout-seconds", INT, "30", CHECK, "점검 쿼리 제한 시간",
            "점검 쿼리 하나가 기다릴 수 있는 최대 시간입니다.").unit("초").range(1, 600)),
    CHECK_DAILY_QUERY_TIMEOUT_SECONDS(d("dbagent.monitor.check-daily-query-timeout-seconds", INT, "120", CHECK,
            "일 1회 점검 쿼리 제한 시간", "일 단위 무거운 점검 쿼리의 최대 대기 시간입니다.").unit("초").range(1, 3600)),
    CHECK_JOB_INTERVAL_MINUTES(d("dbagent.monitor.check-job-interval-minutes", INT, "30", CHECK, "잡 실패 점검 주기",
            "스케줄러 잡 실패 점검을 수행하는 간격입니다.").unit("분").range(1, 1440)),
    CHECK_TABLESPACE_WARN_PCT(d("dbagent.monitor.check-tablespace-warn-pct", DECIMAL, "97", CHECK, "테이블스페이스 경고",
            "테이블스페이스 사용률이 이 값 이상이면 주의로 표시합니다.").unit("%").range(1, 100)
            .detail("위험 기준보다 작아야 합니다.")),
    CHECK_TABLESPACE_CRIT_PCT(d("dbagent.monitor.check-tablespace-crit-pct", DECIMAL, "98", CHECK, "테이블스페이스 위험",
            "테이블스페이스 사용률이 이 값 이상이면 위험으로 표시합니다.").unit("%").range(1, 100)),
    CHECK_SEGMENT_SNAPSHOT_MIN_GB(d("dbagent.monitor.check-segment-snapshot-min-gb", DECIMAL, "1", CHECK, "세그먼트 스냅샷 최소 크기",
            "이 크기(GB) 이상인 세그먼트만 스냅샷에 담습니다.").unit("GB").range(0, 100000)),
    CHECK_FRA_WARN_PCT(d("dbagent.monitor.check-fra-warn-pct", DECIMAL, "80", CHECK, "FRA 경고",
            "FRA(복구 영역) 사용률이 이 값 이상이면 주의로 표시합니다.").unit("%").range(1, 100)),
    CHECK_FRA_CRIT_PCT(d("dbagent.monitor.check-fra-crit-pct", DECIMAL, "90", CHECK, "FRA 위험",
            "FRA 사용률이 이 값 이상이면 위험으로 표시합니다.").unit("%").range(1, 100)),
    CHECK_TEMP_WARN_PCT(d("dbagent.monitor.check-temp-warn-pct", DECIMAL, "75", CHECK, "Temp 경고",
            "Temp 테이블스페이스 사용률이 이 값 이상이면 주의로 표시합니다.").unit("%").range(1, 100)),
    CHECK_TEMP_CRIT_PCT(d("dbagent.monitor.check-temp-crit-pct", DECIMAL, "90", CHECK, "Temp 위험",
            "Temp 테이블스페이스 사용률이 이 값 이상이면 위험으로 표시합니다.").unit("%").range(1, 100)),
    CHECK_EXCLUDE_OWNERS(d("dbagent.monitor.check-exclude-owners", CSV, "SYS,SYSTEM,AUDSYS,XDB,MDSYS,CTXSYS", CHECK,
            "점검 제외 소유자", "세그먼트 점검에서 제외할 스키마(소유자) 목록입니다. 쉼표로 구분합니다.")
            .detail("영문·숫자·_ $ # 만 허용됩니다.")),
    JOB_FAIL_DOWNGRADE(d("dbagent.monitor.job-fail-downgrade", CSV, "ORA$AT_%", CHECK, "잡 실패: '확인'으로 낮출 잡",
            "이 이름 패턴의 잡 실패는 주의/위험 대신 '확인'으로 표시합니다. 쉼표 구분, % 는 아무 글자.").emptyAllowed()
            .detail("JOB_NAME 또는 OWNER.JOB_NAME 과 비교하며 대소문자를 무시합니다. 기본 ORA$AT_% 는 Oracle 자동 유지보수 작업입니다.")),
    JOB_FAIL_EXCLUDE(d("dbagent.monitor.job-fail-exclude", CSV, "", CHECK, "잡 실패: 점검에서 뺄 잡",
            "이 이름 패턴의 잡 실패는 점검에서 아예 제외합니다. 쉼표 구분, % 는 아무 글자.").emptyAllowed()),

    // ───────────────────────── 조회 타임아웃 ─────────────────────────
    LOCK_QUERY_TIMEOUT_SECONDS(d("dbagent.monitor.lock-query-timeout-seconds", INT, "3", TIMEOUT, "v$lock 조회 제한 시간",
            "v$lock 스캔 쿼리의 최대 대기 시간입니다. 느리면 빨리 실패시키고 다음 폴링에서 재시도합니다.").unit("초").range(1, 600)
            .detail("일부 인스턴스는 enqueue 해시체인이 길어 스캔이 49초까지 걸린 전례가 있어 짧게 잡습니다.")),
    SCREEN_QUERY_TIMEOUT_SECONDS(d("dbagent.monitor.screen-query-timeout-seconds", INT, "10", TIMEOUT, "화면 조회 제한 시간",
            "세션·화면용 조회 쿼리의 최대 대기 시간입니다.").unit("초").range(1, 600)),
    ASH_ACTIVITY_QUERY_TIMEOUT_SECONDS(d("dbagent.monitor.ash-activity-query-timeout-seconds", INT, "10", TIMEOUT,
            "ASH 활동 조회 제한 시간", "ASH 활동 차트 조회의 최대 대기 시간입니다.").unit("초").range(1, 600)),
    ASH_AWR_QUERY_TIMEOUT_SECONDS(d("dbagent.monitor.ash-awr-query-timeout-seconds", INT, "60", TIMEOUT,
            "ASH/AWR 이력 조회 제한 시간", "AWR 이력 조회의 최대 대기 시간입니다.").unit("초").range(1, 3600)),
    ASH_AWR_FALLBACK(d("dbagent.monitor.ash-awr-fallback", BOOL, "true", TIMEOUT, "ASH → AWR 대체 조회",
            "메모리 ASH 에 없는 구간을 AWR 이력으로 대신 조회합니다.")),
    SQLSTAT_QUERY_TIMEOUT_SECONDS(d("dbagent.monitor.sqlstat-query-timeout-seconds", INT, "15", TIMEOUT, "SQL 통계 조회 제한 시간",
            "SQL 통계 수집 쿼리의 최대 대기 시간입니다.").unit("초").range(1, 600)),
    HEALTH_BUSY_MAX_SECONDS(d("dbagent.monitor.health-busy-max-seconds", INT, "30", TIMEOUT, "상태 점검 지연 허용 시간",
            "상태 점검이 이 시간 이상 걸리면 '바쁨' 으로 판단합니다.").unit("초").range(1, 600)),
    SESSION_EXTRA_FAIL_LOG_MINUTES(d("dbagent.monitor.session-extra-fail-log-minutes", INT, "10", TIMEOUT, "세션 부가정보 실패 로그 간격",
            "세션 부가정보 조회 실패가 이어질 때 경고 로그를 남기는 간격입니다.").unit("분").range(0, 1440)),
    FLEET_STATUS_TIMEOUT_SECONDS(d("dbagent.fleet-status.timeout-seconds", INT, "5", TIMEOUT, "전체 현황 DB 확인 제한 시간",
            "전체 현황에서 DB 하나의 상태 확인에 쓰는 최대 시간입니다.").unit("초").range(1, 120)
            .detail("느리거나 접속 불가한 인스턴스 하나가 전체 현황 화면을 붙잡지 않게 하는 상한입니다.")),
    ACTIVE_ALERTS_TIMEOUT_SECONDS(d("dbagent.monitor.active-alerts-timeout-seconds", INT, "5", TIMEOUT, "활성 알림 조회 제한 시간",
            "활성 알림 집계 조회의 최대 대기 시간입니다.").unit("초").range(1, 120)),
    ORACLE_CONNECT_TIMEOUT_MS(d("dbagent.oracle.connect-timeout-ms", INT, "3000", TIMEOUT, "Oracle 접속 제한 시간",
            "Oracle 새 커넥션을 수립하는 최대 시간입니다.").unit("ms").range(250, 60000)
            .danger().detail("저장하면 풀을 다시 만들어 다음 조회부터 적용됩니다(기존 풀은 진행 중인 조회가 끝날 시간을 준 뒤 닫음). 너무 짧으면 실제 네트워크 지연에 정상 DB 를 접속 불가로 오인합니다.")
            .warning("저장하면 풀을 다시 만듭니다: 다음 조회부터 새 설정으로 대상 DB 에 새로 접속하고, 기존 풀은 진행 중인 조회가 끝나도록 응답 대기 시간+30초(최소 60초) 뒤에 닫습니다.")),
    ORACLE_READ_TIMEOUT_MS(d("dbagent.oracle.read-timeout-ms", INT, "60000", TIMEOUT, "Oracle 응답 대기 시간",
            "이미 연결된 커넥션에서 쿼리 응답을 기다리는 최대 시간입니다.").unit("ms").range(1000, 600000)
            .detail("접속 제한 시간과 목적이 다릅니다. 짧게 잡으면 AWR/ASH 조회처럼 원래 오래 걸리는 쿼리가 접속 불가로 오인됩니다. "
                    + "저장하면 풀을 다시 만들어 다음 조회부터 적용됩니다(기존 풀은 진행 중인 조회가 끝날 시간을 준 뒤 닫음).")
            .danger().warning("저장하면 풀을 다시 만듭니다: 다음 조회부터 새 설정으로 대상 DB 에 새로 접속하고, 기존 풀은 진행 중인 조회가 끝나도록 응답 대기 시간+30초(최소 60초) 뒤에 닫습니다.")),
    RDB_CONNECT_TIMEOUT_MS(d("dbagent.rdb.connect-timeout-ms", INT, "2000", TIMEOUT, "RDB 접속 제한 시간",
            "MySQL·PostgreSQL 등 새 커넥션을 수립하는 최대 시간입니다.").unit("ms").range(250, 60000).danger()
            .detail("저장하면 풀을 다시 만들어 다음 조회부터 적용됩니다(기존 풀은 진행 중인 조회가 끝날 시간을 준 뒤 닫음).")
            .warning("저장하면 풀을 다시 만듭니다: 다음 조회부터 새 설정으로 대상 DB 에 새로 접속하고, 기존 풀은 진행 중인 조회가 끝나도록 응답 대기 시간+30초(최소 60초) 뒤에 닫습니다.")),
    RDB_READ_TIMEOUT_MS(d("dbagent.rdb.read-timeout-ms", INT, "60000", TIMEOUT, "RDB 응답 대기 시간",
            "RDB 커넥션에서 쿼리 응답을 기다리는 최대 시간입니다.").unit("ms").range(1000, 600000)
            .danger().detail("이 값이 없으면 응답 없는 서버 하나가 커넥션을 영구히 붙잡아 풀이 고갈될 수 있습니다. 저장하면 풀을 다시 만들어 다음 조회부터 적용됩니다(기존 풀은 진행 중인 조회가 끝날 시간을 준 뒤 닫음).")
            .warning("저장하면 풀을 다시 만듭니다: 다음 조회부터 새 설정으로 대상 DB 에 새로 접속하고, 기존 풀은 진행 중인 조회가 끝나도록 응답 대기 시간+30초(최소 60초) 뒤에 닫습니다.")),

    // ───────────────────────── 보존 기간 ─────────────────────────
    METRIC_RETENTION_DAYS(d("dbagent.monitor.metric-retention-days", INT, "15", RETENTION, "추이 지표 보존 기간",
            "추이 차트용 지표(instance_metric_history)를 보관하는 일수입니다.").unit("일").range(1, 3650)),
    PERF_RETENTION_DAYS(d("dbagent.monitor.perf-retention-days", INT, "15", RETENTION, "성능 분석 보존 기간",
            "성능 분석 수집 이력을 보관하는 일수입니다.").unit("일").range(1, 3650)),
    // 성능 분석 Phase 2(2026-10-09 오케스트레이터 결정: 기본 15일, 필요하면 화면에서 변경): 대기 이벤트 분 데이터는 행이 많아(분당 최대 50행) 지표와 따로 정한다
    SYS_EVENT_RETENTION_DAYS(d("dbagent.perf.sys-event-retention-days", INT, "15", RETENTION, "시스템 부하 대기 이벤트 보존 기간",
            "시스템 부하 탭의 대기 이벤트 분 데이터를 보관하는 일수입니다. 지표(분당 9행)는 성능 분석 보존 기간을 따릅니다.").unit("일").range(1, 3650)
            .detail("대기 이벤트는 분당 최대 50행이라 저장 용량의 대부분을 차지합니다(DB 1개당 15일에 SQLite 약 200MB 이상, H2는 더 큼). 지난주 같은 시간 비교에는 최소 8일이 필요하므로 8일 미만으로 줄이면 대기 이벤트의 지난주 대비 배지와 힌트가 나오지 않습니다.")),
    SQL_ROLLUP_RETENTION_DAYS(d("dbagent.perf.rollup-retention-days", INT, "90", RETENTION, "SQL 시간 롤업 보존 기간",
            "SQL 분석의 시간 단위 롤업(시간당 상위 SQL + Plan 변경 SQL)과 Plan 변경 기록을 보관하는 일수입니다.").unit("일").range(1, 3650)),
    SQL_ROLLUP_TOP_N(d("dbagent.perf.rollup-top-n", INT, "200", RETENTION, "SQL 시간 롤업 SQL 수",
            "시간마다 Elapsed 상위 N개와 실행 수 상위 N개(합집합) SQL을 롤업에 남깁니다. 6시간을 넘는 구간 조회와 지난주 대비는 이 롤업을 씁니다.").unit("개").range(10, 5000)),
    SQLSTAT_DELTA_RETENTION_DAYS(d("dbagent.perf.sqlstat-delta-retention-days", INT, "7", RETENTION, "SQL 1분 델타 보존 기간",
            "SQL 분석의 1분 단위 SQL 통계(mon_sqlstat_delta)를 보관하는 일수입니다. 점검·Lock 이력 보존 기간보다 길게 두어도 이 값까지만 보관합니다.").unit("일").range(1, 3650)
            .detail("6시간을 넘는 구간과 지난주 대비는 시간 롤업을 쓰므로 1분 델타를 오래 둘 필요가 없습니다. 새 커서 행이 늘어 이 테이블이 커지므로 기본 7일로 제한합니다.")),
    STORE_RETENTION_DAYS(d("dbagent.monitor.store-retention-days", INT, "30", RETENTION, "점검·Lock 이력 보존 기간",
            "점검 알림·Lock 샘플 이력을 보관하는 일수입니다.").unit("일").range(1, 3650)),
    KILL_AUDIT_RETENTION_DAYS(d("dbagent.monitor.kill-audit-retention-days", INT, "365", RETENTION, "KILL 감사 기록 보존 기간",
            "세션 KILL 감사 기록을 보관하는 일수입니다.").unit("일").range(1, 3650)),

    // ───────────────────────── 커넥션 풀 ─────────────────────────
    ORACLE_POOL_MIN_IDLE(d("dbagent.oracle.pool.min-idle", INT, "2", POOL, "Oracle 풀 최소 유휴", "대상 Oracle DB 풀의 최소 유휴 커넥션 수 기본값입니다.")
            .unit("개").range(0, 100).danger()
            .detail("DB 등록 시 인스턴스별로 따로 지정하면 그 값이 항상 우선합니다. 저장하면 풀을 다시 만들어 다음 조회부터 적용됩니다(기존 풀은 진행 중인 조회가 끝날 시간을 준 뒤 닫음).")
            .warning("대상 DB 세션 수에 직접 영향을 줍니다.")),
    ORACLE_POOL_MAX_SIZE(d("dbagent.oracle.pool.max-size", INT, "10", POOL, "Oracle 풀 최대 크기", "대상 Oracle DB 풀의 최대 커넥션 수 기본값입니다.")
            .unit("개").range(1, 200).danger()
            .detail("DB 등록 시 인스턴스별 값이 우선합니다. 저장하면 풀을 다시 만들어 다음 조회부터 적용됩니다(기존 풀은 진행 중인 조회가 끝날 시간을 준 뒤 닫음).")
            .warning("대상 DB 세션 수에 직접 영향을 줍니다.")),
    RDB_POOL_MIN_IDLE(d("dbagent.rdb.pool.min-idle", INT, "1", POOL, "RDB 풀 최소 유휴", "MySQL·PostgreSQL 등 풀의 최소 유휴 커넥션 수 기본값입니다.")
            .unit("개").range(0, 100).danger()
            .warning("대상 DB 세션 수에 직접 영향을 줍니다.")),
    RDB_POOL_MAX_SIZE(d("dbagent.rdb.pool.max-size", INT, "5", POOL, "RDB 풀 최대 크기", "MySQL·PostgreSQL 등 풀의 최대 커넥션 수 기본값입니다.")
            .unit("개").range(1, 200).danger()
            .warning("대상 DB 세션 수에 직접 영향을 줍니다.")),

    // ───────────────────────── AI · 튜닝 ─────────────────────────
    AIDBA_RESTAPI_URL(d("aidba.restapi.url", URL, "http://localhost:9300", AI, "AI DBA 서버 주소",
            "AI 챗봇·SQL 작성기·AI SQL 성능분석이 호출하는 GPU 서버(sqlrestapi) 주소입니다.")
            .detail("사내망에서 접근 가능한 GPU 서버 주소로 바꿉니다. 스킴(http://)을 빼고 'IP:9300' 으로 써도 서비스가 보정합니다.")),
    AIDBA_OLLAMA_TIMEOUT_MS(d("aidba.ollama.timeout-ms", INT, "300000", AI, "AI 응답 대기 시간",
            "AI 서버 응답을 기다리는 최대 시간입니다.").unit("ms").range(10000, 3600000)
            .detail("모델 크기·GPU 컨텍스트 크기에 따라 응답이 길어질 수 있습니다. 원격 GPU 와 대형 모델이면 늘려야 할 수 있습니다.")),
    SQLTUNING_API_URL(d("sqltuning.api.url", URL, "http://localhost:8010", AI, "SQL 튜닝 모델 서버 주소",
            "SQL 정합성/튜닝 모델 서버 주소입니다.")),
    SQLTUNING_API_TIMEOUT_MS(d("sqltuning.api.timeout-ms", INT, "180000", AI, "SQL 튜닝 응답 대기 시간",
            "SQL 튜닝 모델 서버 응답을 기다리는 최대 시간입니다.").unit("ms").range(10000, 3600000)),
    SQLTUNEADVISOR_API_TIMEOUT_MS(d("sqltuneadvisor.api.timeout-ms", INT, "180000", AI, "Tune Advisor 응답 대기 시간",
            "AI SQL 성능분석(Tune Advisor) 응답을 기다리는 최대 시간입니다.").unit("ms").range(10000, 3600000)),

    // ───────────────────────── SQL 실행 ─────────────────────────
    SQL_RUNNER_MAX_ROWS(d("dbagent.sql-runner.max-rows", INT, "500", SQL, "SQL 실행 기본 조회 건수",
            "건수를 지정하지 않았을 때 가져오는 행 수입니다.").unit("건").range(1, 1000000)
            .detail("최대 조회 건수 상한보다 클 수 없습니다.")),
    SQL_RUNNER_MAX_ROWS_LIMIT(d("dbagent.sql-runner.max-rows-limit", INT, "5000", SQL, "SQL 실행 최대 조회 건수",
            "사용자가 건수를 지정해도 넘을 수 없는 상한입니다.").unit("건").range(1, 1000000)
            .detail("브라우저 결과 그리드가 너무 커지는 것을 막습니다.")),
    SQL_RUNNER_TIMEOUT_SECONDS(d("dbagent.sql-runner.timeout-seconds", INT, "30", SQL, "SQL 실행 제한 시간",
            "SQL 한 문장의 최대 실행 시간입니다.").unit("초").range(1, 3600)),
    SQL_RUNNER_READ_ONLY(d("dbagent.sql-runner.read-only", BOOL, "true", SQL, "SQL 실행 읽기 전용",
            "켜면 SQL 실행 화면에서 조회(SELECT) 문만 실행할 수 있습니다.").danger()
            .detail("끄면 INSERT/UPDATE/DELETE/DDL 이 실행될 수 있습니다.")
            .warning("운영 DB 에 변경이 가해질 수 있습니다. 변경 사유가 필수이며 이력에 남습니다.")),

    // ───────────────────────── 내부 (화면 비노출) ─────────────────────────
    METRIC_SAMPLE_INTERVAL_SECONDS(d("dbagent.monitor.metric-sample-interval-seconds", INT, "60", INTERNAL, "지표 샘플링 주기",
            "추이 지표·SQL 통계 샘플러의 수집 주기입니다.").unit("초").range(10, 3600).hidden()
            .detail("샘플러, '최근 N초' 계산, SQL 통계 델타 수집이 서로 맞물려 있어 화면에 노출하지 않습니다. 스케줄러 주기라 재시작이 필요합니다."));

    // ─────────────────────────────────────────────────────────────

    private static final Map<String, SettingKey> BY_KEY;

    static {
        Map<String, SettingKey> m = new HashMap<String, SettingKey>();
        for (SettingKey k : values()) {
            if (m.put(k.key, k) != null) {
                throw new IllegalStateException("중복된 설정 키: " + k.key);
            }
        }
        BY_KEY = Collections.unmodifiableMap(m);
    }

    // 소비자 코드가 SettingsService 조회로 옮겨져 저장 즉시 적용되는 키 (환경설정 DB화 P3, 2026-10-04). 나머지는 기동 때 한 번
    // 읽는  라 재시작이 필요하다. 키를 하나씩 옮길 때마다 여기에 추가한다.
    private static final java.util.Set<SettingKey> LIVE = java.util.Collections.unmodifiableSet(java.util.EnumSet.of(
            // P3-1 Lock · 실시간 (DB별 재정의 가능한 키 + 전역 키)
            TM_HOLDER_LAST_CALL_ET_SECONDS, TM_HOLDER_INCIDENT_COUNT, TM_HOLDER_WARN_COUNT,
            LOCK_TX_WAIT_WARN, LOCK_TX_WAIT_CRIT, LOCK_TM_WAIT_WARN, LOCK_TM_WAIT_CRIT,
            TM_HOLDER_KILL_BLOCKING_ONLY, TM_HOLDER_KILL_INACTIVE_ONLY, LOCK_TX_ROW_LOCK_ONLY,
            LONG_RUNNING_QUERY_SECONDS, LOCK_CACHE_MILLIS,
            // P3-2 화면 주기·인증
            UI_POLLING_INTERVAL_MS, FLEET_OVERVIEW_POLLING_INTERVAL_MS, UI_SESSION_REFRESH_SECONDS, AAS_CPU_PHYSICAL, AUTH_SESSION_TTL_DAYS,
            // P3-2 SQL 실행 (읽기 전용 포함)
            SQL_RUNNER_MAX_ROWS, SQL_RUNNER_MAX_ROWS_LIMIT, SQL_RUNNER_TIMEOUT_SECONDS, SQL_RUNNER_READ_ONLY,
            // P3-2 AI · 튜닝 (주소·응답 대기 시간)
            AIDBA_RESTAPI_URL, AIDBA_OLLAMA_TIMEOUT_MS, SQLTUNING_API_URL, SQLTUNING_API_TIMEOUT_MS, SQLTUNEADVISOR_API_TIMEOUT_MS,
            // P3-2 보존 기간
            METRIC_RETENTION_DAYS, PERF_RETENTION_DAYS, SYS_EVENT_RETENTION_DAYS, STORE_RETENTION_DAYS, KILL_AUDIT_RETENTION_DAYS,
            // P3-2 점검 알림 임계치 (수집 주기·시각은 스케줄러 기동 때 한 번 읽으므로 재시작 키로 남는다)
            CHECK_QUERY_TIMEOUT_SECONDS, CHECK_DAILY_QUERY_TIMEOUT_SECONDS, CHECK_JOB_INTERVAL_MINUTES,
            CHECK_TABLESPACE_WARN_PCT, CHECK_TABLESPACE_CRIT_PCT, CHECK_SEGMENT_SNAPSHOT_MIN_GB,
            CHECK_FRA_WARN_PCT, CHECK_FRA_CRIT_PCT, CHECK_TEMP_WARN_PCT, CHECK_TEMP_CRIT_PCT,
            CHECK_EXCLUDE_OWNERS, JOB_FAIL_DOWNGRADE, JOB_FAIL_EXCLUDE,
            // P3-2 조회 타임아웃 (Oracle/RDB 접속·응답 대기는 풀을 만들 때 한 번 쓰이므로 재시작 키로 남는다)
            LOCK_QUERY_TIMEOUT_SECONDS, SCREEN_QUERY_TIMEOUT_SECONDS, ASH_ACTIVITY_QUERY_TIMEOUT_SECONDS,
            ASH_AWR_QUERY_TIMEOUT_SECONDS, ASH_AWR_FALLBACK, SQLSTAT_QUERY_TIMEOUT_SECONDS, HEALTH_BUSY_MAX_SECONDS,
            SESSION_EXTRA_FAIL_LOG_MINUTES, FLEET_STATUS_TIMEOUT_SECONDS, ACTIVE_ALERTS_TIMEOUT_SECONDS,
            // P5 Lock 백그라운드 수집 켜기/끄기·상한, 접속/응답 대기와 풀 크기(저장하면 풀을 다시 만든다)
            LOCK_KEEPALIVE_MINUTES, LOCK_KEEPALIVE_MAX_DBS, LOCK_COLLECT_ENABLED, BLOCKER_DETAIL_ENABLED, BLOCKER_TM_MODE_ENABLED, LOCK_GAP_SEC,
            OBJ_COLLECT_ENABLED, REDO_SWITCH_WARN_PER_HOUR, SYS_COLLECT_ENABLED, DIAG_CARD_PCT, SQLSTAT_NEW_CURSOR_MAX_ROWS, SQLSTAT_NEW_CURSOR_MAX_EXECS,
            SQLSTAT_NEW_CURSOR_MAX_ELAPSED_SEC, SQLSTAT_TEXT_CAPTURE_MAX_PER_CYCLE, SQLSTAT_DELTA_RETENTION_DAYS, DIAG_SQL_SURGE_PCT, DIAG_SQL_SURGE_MIN_EXECS, DIAG_SQL_NPLUS1_EXEC_SEC, DIAG_SQL_REUSE_EXEC_SEC, DIAG_SQL_REUSE_PARSE_PCT,
            DIAG_SQL_LITERAL_ID_COUNT, SQL_ROLLUP_RETENTION_DAYS, SQL_ROLLUP_TOP_N, DIAG_WAIT_RATIO_PCT, DIAG_HARD_PARSE_PCT, DIAG_SEQ_READ_MS,
            ORACLE_CONNECT_TIMEOUT_MS, ORACLE_READ_TIMEOUT_MS, ORACLE_POOL_MIN_IDLE, ORACLE_POOL_MAX_SIZE,
            RDB_CONNECT_TIMEOUT_MS, RDB_READ_TIMEOUT_MS, RDB_POOL_MIN_IDLE, RDB_POOL_MAX_SIZE));

    private static final Pattern CSV_CHARS =Pattern.compile("[A-Za-z0-9_$#%.,\\- ]*");

    private final String key;
    private final SettingType type;
    private final String defaultValue;
    private final SettingGroup group;
    private final String label;
    private final String summary;
    private final String unit;
    private final Long min;
    private final Long max;
    private final String detail;
    private final String warning;
    private final boolean perDb;
    private final boolean live;
    private final boolean danger;
    private final boolean hidden;
    private final boolean secret;
    private final boolean emptyAllowed;

    SettingKey(Def d) {
        this.key = d.key;
        this.type = d.type;
        this.defaultValue = d.defaultValue;
        this.group = d.group;
        this.label = d.label;
        this.summary = d.summary;
        this.unit = d.unit;
        this.min = d.min;
        this.max = d.max;
        this.detail = d.detail;
        this.warning = d.warning;
        this.perDb = d.perDb;
        this.live = d.live;
        this.danger = d.danger;
        this.hidden = d.hidden;
        this.secret = d.secret;
        this.emptyAllowed = d.emptyAllowed;
    }

    public String key() { return key; }
    public SettingType type() { return type; }
    public String defaultValue() { return defaultValue; }
    public SettingGroup group() { return group; }
    public String label() { return label; }
    public String summary() { return summary; }
    public String unit() { return unit; }
    public Long min() { return min; }
    public Long max() { return max; }
    public String detail() { return detail; }
    public String warning() { return warning; }
    /** DB 별 재정의 가능 여부 (Lock 임계치류). */
    public boolean perDb() { return perDb; }
    /** true 이면 저장 즉시 적용, false 이면 재시작 후 적용. */
    public boolean live() { return live || LIVE.contains(this); }
    public boolean requiresRestart() { return !live(); }
    /** 운영에 영향이 커서 변경 사유를 필수로 받는 키. */
    public boolean danger() { return danger; }
    /** 화면에 노출하지 않는 키. */
    public boolean hidden() { return hidden; }
    /** 값을 화면·로그에 그대로 내리지 않는 키 (CredentialCipher 로 암호화 저장). */
    public boolean secret() { return secret; }


    public static SettingKey byKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    /**
     * 값이 이 키의 종류·범위에 맞는지 검사한다. 맞으면 null, 아니면 사용자에게 보일 한국어 사유.
     * 키 사이의 관계(주의 &lt; 위험 등)는 여기서 보지 않는다 - 저장 시 유효값 기준으로 따로 검사한다.
     */
    public String validate(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            return emptyAllowed ? null : "값을 입력하세요.";
        }
        if (v.length() > 1000) {
            // 저장소 컬럼(VARCHAR 4000)·H2 오류를 막고 비정상 입력을 걸러내는 상한
            return "값이 너무 깁니다 (최대 1000자).";
        }
        switch (type) {
            case INT: {
                long n;
                try {
                    n = Long.parseLong(v);
                } catch (NumberFormatException e) {
                    return "정수를 입력하세요.";
                }
                if (min != null && n < min) return "허용 범위는 " + min + " ~ " + max + " 입니다.";
                if (max != null && n > max) return "허용 범위는 " + min + " ~ " + max + " 입니다.";
                return null;
            }
            case DECIMAL: {
                BigDecimal n;
                try {
                    n = new BigDecimal(v);
                } catch (NumberFormatException e) {
                    return "숫자를 입력하세요.";
                }
                if (min != null && n.compareTo(BigDecimal.valueOf(min)) < 0) return "허용 범위는 " + min + " ~ " + max + " 입니다.";
                if (max != null && n.compareTo(BigDecimal.valueOf(max)) > 0) return "허용 범위는 " + min + " ~ " + max + " 입니다.";
                return null;
            }
            case BOOL:
                return ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) ? null : "true 또는 false 여야 합니다.";
            case URL:
                // 스킴은 생략 가능(서비스가 normalizeUrl 에서 http:// 를 붙인다). 공백·제어문자만 막는다.
                // (2026-10-09 점검 수정) 스킴이 있으면 http/https 만 허용 - 공백만 없으면 어떤 스킴이든 통과하던 것을 좁힌다.
                return (v.matches("^(https?://)?\\S+$") && (!v.contains("://") || v.matches("(?i)^https?://\\S+$")))
                        ? null : "공백 없는 http:// 또는 https:// 서버 주소를 입력하세요. 예) http://서버IP:9300";
            case CSV:
                if (this == CHECK_EXCLUDE_OWNERS) {
                    // CheckCollector 가 항목마다 [A-Za-z0-9_$#]+ 만 SQL 에 넣고 나머지는 조용히 빼므로, 저장 때부터 같은 규칙으로 막는다.
                    for (String item : v.split(",", -1)) {
                        if (!item.trim().matches("[A-Za-z0-9_$#]+")) {
                            return "소유자 이름은 영문·숫자와 _ $ # 만 쓸 수 있고 쉼표로 구분합니다: '" + item.trim() + "'";
                        }
                    }
                    return null;
                }
                return CSV_CHARS.matcher(v).matches() ? null : "영문·숫자와 _ $ # % . , - 만 쓸 수 있습니다.";
            case STRING:
            default:
                return null;
        }
    }

    /** 정수 키(INT)의 값을 읽는다. 호출 전에 validate 를 통과한 값이어야 한다. */
    static long parseLong(String v) {
        return Long.parseLong(v.trim());
    }

    // ───────────────────────── 정의 빌더 ─────────────────────────

    private static Def d(String key, SettingType type, String defaultValue, SettingGroup group, String label, String summary) {
        return new Def(key, type, defaultValue, group, label, summary);
    }

    /** enum 상수 선언을 읽기 쉽게 하려는 정의 빌더. 상수 생성 시점에만 쓴다. */
    static final class Def {
        final String key;
        final SettingType type;
        final String defaultValue;
        final SettingGroup group;
        final String label;
        final String summary;
        String unit = "";
        Long min;
        Long max;
        String detail = "";
        String warning = "";
        boolean perDb;
        boolean live;
        boolean danger;
        boolean hidden;
        boolean secret;
        boolean emptyAllowed;

        Def(String key, SettingType type, String defaultValue, SettingGroup group, String label, String summary) {
            this.key = key;
            this.type = type;
            this.defaultValue = defaultValue;
            this.group = group;
            this.label = label;
            this.summary = summary;
        }

        Def unit(String u) { this.unit = u; return this; }
        Def range(long min, long max) { this.min = min; this.max = max; return this; }
        Def detail(String s) { this.detail = s; return this; }
        Def warning(String s) { this.warning = s; return this; }
        Def perDb() { this.perDb = true; return this; }
        Def live() { this.live = true; return this; }
        Def danger() { this.danger = true; return this; }
        Def hidden() { this.hidden = true; return this; }
        Def secret() { this.secret = true; return this; }
        Def emptyAllowed() { this.emptyAllowed = true; return this; }
    }
}
