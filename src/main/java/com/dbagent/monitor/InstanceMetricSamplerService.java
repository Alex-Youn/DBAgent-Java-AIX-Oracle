package com.dbagent.monitor;

import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * instance_metric_history를 채우는 주기적 샘플러. Oracle 인스턴스 대시보드의 CpuDbTimeLineChart/
 * LockTrendChart가 쓸 시계열을 만든다. 사용자가 화면을 보고 있지 않아도 계속 쌓여야 추이가 끊기지
 * 않으므로, 요청 기반이 아니라 서버 스케줄러로 동작한다.
 *
 * fleet_status(MonitorController)의 CompletableFuture 병렬 폴링과 같은 방식으로 인스턴스별 샘플링을
 * 병렬화한다(2026-09-18, 데이터 수집 부하 조사 - 순차 루프였을 때는 인스턴스 하나가 느리거나 다운돼
 * 있으면 뒤에 줄 선 나머지 인스턴스 수집까지 통째로 밀렸고, 인스턴스 수가 늘수록 한 사이클이 설정된
 * metric-sample-interval-seconds보다 길어지기 쉬워 사실상 쉬지 않고 도는 문제가 있었다). 전용
 * ExecutorService를 써서 이 작업이 SchedulingConfig의 공용 @Scheduled 스레드풀을 오래 점유하지
 * 않게 한다.
 */
@Service
public class InstanceMetricSamplerService {

    private static final Logger log = LoggerFactory.getLogger(InstanceMetricSamplerService.class);

    // MonitorController.FLEET_STATUS_EXECUTOR와 같은 이유로 static - 인스턴스별 샘플링 I/O를 이
    // 서비스 자체의 스케줄러 스레드(SchedulingConfig 참고)와 분리해, 한 사이클이 인스턴스 수만큼
    // 순차로 늘어지지 않고 가장 느린 인스턴스 하나만큼만 걸리게 한다.
    private static final ExecutorService SAMPLER_EXECUTOR = Executors.newFixedThreadPool(4);

    // 일반(TEMP 아닌) 테이블스페이스 사용률 알림 임계치 - MonitorService.getActiveAlerts()가 쓰던
    // 값을 여기로 옮겼다(오케스트레이터 폐쇄망 실측, 2026-09-18: dba_data_files/dba_free_space 조인을
    // active_alerts 요청마다(v2 10초 폴링 + 인스턴스 전환마다 즉시 1회) 실시간으로 돌렸더니, 실제
    // 운영 규모(테이블스페이스/데이터파일 수가 이 테스트 환경보다 훨씬 많음)에서는 딕셔너리 뷰 조인이
    // 가볍지 않아 커넥션을 오래 붙잡았다 - v$lock 스캔과 같은 풀(인스턴스당 최대
    // dbagent.oracle.pool.max-size개)을 같이 쓰다 보니 전환 지연/풀 고갈로 번져, 기존 대시보드로
    // 돌아가거나 다른 인스턴스로 전환할 때도 연쇄적으로 느려지거나 접속 실패가 났다. v$lock/CPU/AAS
    // 처럼 이미 주기 샘플링하는 값들과 같은 사이클(metric-sample-interval-seconds)에 태워, 실시간
    // 조회 대신 마지막 샘플링 결과를 캐시에서 읽게 한다.
    private static final double TABLESPACE_CRITICAL_PCT = 97.0;

    // db_id -> 마지막 샘플링에서 임계치를 넘은 테이블스페이스 알림 목록. MonitorService.getActiveAlerts()가
    // 읽기 전용으로 참조한다. 값 자체가 매 사이클 통째로 교체되는 스냅샷이라 ConcurrentHashMap이면
    // 충분하고, 읽는 쪽과 쓰는 쪽(SAMPLER_EXECUTOR의 여러 스레드) 사이에 추가 동기화가 필요 없다.
    private final ConcurrentHashMap<String, List<Map<String, Object>>> tablespaceAlertsCache = new ConcurrentHashMap<>();

    // C1(2026-09-25, 체크리스트 1-10 보완): 인스턴스별로 마지막에 집계한 ASH sample_id. 예전엔 매 사이클
    // "SYSDATE - 60초" 창을 셌는데 fixedDelay라 실제 주기가 60초를 넘으면(조회 시간만큼 밀림) 그 사이 몇 초가
    // 어느 창에도 들어가지 않았다. sample_id 기준으로 "지난번 이후 전부"를 세면 빠지는 초가 없다. 메모리에만
    // 두므로 앱 재기동 직후 첫 사이클은 예전처럼 시간 창으로 센다.
    private final ConcurrentHashMap<String, Long> ashWatermark = new ConcurrentHashMap<>();

    // C4(2026-09-25): 인스턴스별 "DB SYSDATE - 앱 시각"(ms). 저장값(sampled_at)은 앱 시각이고 ASH 조회는 DB
    // 시각이라, 화면에서 고른 구간을 DB 시각으로 바꿀 때 쓴다(도커/폐쇄망에서 DB와 앱 서버 시간대가 다를 수
    // 있음). 매 사이클 갱신하며 /api/metric_history가 dbClockOffsetMs로 내려준다.
    private final ConcurrentHashMap<String, Long> dbClockOffsetMs = new ConcurrentHashMap<>();

    // 이만큼보다 오래 샘플링이 끊겼으면(sample_id 차이 기준) 한 점에 몰아 평균내지 않고 시간 창으로 센다.
    private static final int MAX_WATERMARK_GAP_CYCLES = 5;

    // 앱 기동·신규 DB 등록 시 ASH로 채워 넣는 과거 구간(분) - C3.
    private static final int BACKFILL_MINUTES = 60;

    private final OracleConnectionPoolManager poolManager;
    private final DatabaseConfigService configService;
    private final InstanceMetricHistoryService historyService;

    // MonitorService.lockQueryTimeoutSeconds와 같은 값 - 이 환경 일부 인스턴스에서 v$lock 스캔이
    // 49초까지 걸리는 걸 실측했으므로, 여기서도 동일하게 짧게 캡을 건다.
    @Value("${dbagent.monitor.lock-query-timeout-seconds:3}")
    private int lockQueryTimeoutSeconds;

    // ash_* 계열(아래 sampleOne() 참고) 카운트를 AAS로 정규화할 때 쓰는 창 길이 - sampleAll()의
    // @Scheduled 주기(fixedDelayString)와 반드시 같은 값이어야 한다(그래야 "최근 N초"에 실제로 샘플링
    // 공백이 안 생김). 두 곳이 같은 프로퍼티 키를 읽으므로 값이 어긋날 일은 없다.
    @Value("${dbagent.monitor.metric-sample-interval-seconds:60}")
    private int sampleIntervalSeconds;

    public InstanceMetricSamplerService(OracleConnectionPoolManager poolManager, DatabaseConfigService configService,
                                         InstanceMetricHistoryService historyService) {
        this.poolManager = poolManager;
        this.configService = configService;
        this.historyService = historyService;
    }

    @Scheduled(fixedDelayString = "${dbagent.monitor.metric-sample-interval-seconds:60}000")
    void sampleAll() {
        long sampledAt = System.currentTimeMillis();
        List<CompletableFuture<SampleResult>> futures = configService.listAllInstances().stream()
                .filter(target -> "oracle".equalsIgnoreCase(target.dbType()))
                .map(target -> CompletableFuture.supplyAsync(() -> sampleOne(target, sampledAt), SAMPLER_EXECUTOR))
                .collect(Collectors.toList());

        // 오라클 조회는 위에서 병렬로 끝내고, SQLite 기록은 여기서 순차로 한다 - 앱 전체 SQLite가
        // Hikari 커넥션 1개뿐이고(application.properties의 spring.datasource.hikari.maximum-pool-size)
        // 그 커넥션을 AuthService.sessionForToken(모든 인증 요청의 토큰 검증)도 같이 쓰기 때문에,
        // 병렬 스레드 여러 개가 동시에 SQLite에 쓰면 그 순간 들어온 사용자 요청이 뒤에 줄을 선다
        // (오케스트레이터 실측: 신규 대시보드 → 기존 대시보드 전환 시 busy, 2026-09-18). 순차로 쓰면
        // 병렬화 이전과 동일하게 샘플러가 SQLite 커넥션을 한 번에 하나씩만 잡는다.
        for (CompletableFuture<SampleResult> f : futures) {
            recordIfPresent(f.join());
        }
    }

    /**
     * 인스턴스를 신규 등록한 직후 곧바로 1회 수집한다(오케스트레이터 요청, 2026-09-18) - 안 그러면
     * 다음 정기 사이클(최대 metric-sample-interval-seconds, 기본 60초)까지 추이 그래프가 완전히
     * 비어 있다. HTTP 응답을 막지 않도록 SAMPLER_EXECUTOR에 던지고 결과를 기다리지 않는다 - 새로
     * 등록한 인스턴스가 접속 불가 상태라도(호스트/포트 오타 등) sampleOne()이 알아서 실패를 삼키고
     * 로그만 남기므로, 여기서 별도 에러 처리를 할 필요가 없다(정기 사이클의 실패 인스턴스와 동일하게
     * 처리됨).
     */
    public void sampleNowAsync(TargetDbConfig target) {
        if (!"oracle".equalsIgnoreCase(target.dbType())) return;
        CompletableFuture.supplyAsync(() -> sampleOne(target, System.currentTimeMillis()), SAMPLER_EXECUTOR)
                .thenAccept(this::recordIfPresent);
        backfillAsync(target);
    }

    /**
     * C3(2026-09-25, 체크리스트 1-10 보완): 앱 기동 직후에는 추이 그래프(ash_*, ash_wc_*)가 비어 있다 - 샘플러가
     * 멈춰 있던 동안의 값이 없기 때문이다. ASH 인메모리 기록으로 최근 BACKFILL_MINUTES분을 1분 단위로 한 번
     * 채워 넣는다. 이미 샘플이 있는 분은 건너뛴다.
     */
    @EventListener(ApplicationReadyEvent.class)
    void backfillOnStartup() {
        for (TargetDbConfig target : configService.listAllInstances()) {
            if ("oracle".equalsIgnoreCase(target.dbType())) {
                backfillAsync(target);
            }
        }
    }

    void backfillAsync(TargetDbConfig target) {
        CompletableFuture.runAsync(() -> backfill(target), SAMPLER_EXECUTOR);
    }

    /** C4: 화면 구간을 DB 시각으로 바꿀 때 쓰는 "DB SYSDATE - 앱 시각"(ms), 아직 모르면 null. */
    public Long getDbClockOffsetMs(String dbId) {
        return dbId == null ? null : dbClockOffsetMs.get(dbId);
    }

    // Active Session Wait Class 차트 6단계(6시간/24시간, 자체 수집 경로 - 2026-09-22)용 metric_name
    // 7개 - MonitorService.getAshActivity()가 쓰는 내부 카테고리 키(cpu/latch/user_io/tx_lock/
    // system_io/tm_lock/other, ashCategoryAas 배열과 같은 순서)에 "ash_" 접두사만 붙인다.
    private static final String[] ASH_METRIC_NAMES =
            {"ash_cpu", "ash_latch", "ash_user_io", "ash_tx_lock", "ash_system_io", "ash_tm_lock", "ash_other"};

    // C2(2026-09-25): 대시보드 개편용 8분류(Oracle 대기 클래스) - AshCategories.KEYS8과 같은 순서.
    // 같은 ASH 스캔에서 7분류와 함께 센다(원본 DB 조회 횟수는 그대로).
    static final String[] ASH_WC_METRIC_NAMES = {"ash_wc_cpu", "ash_wc_user_io", "ash_wc_system_io",
            "ash_wc_concurrency", "ash_wc_application", "ash_wc_commit", "ash_wc_network", "ash_wc_other"};

    private void recordIfPresent(SampleResult r) {
        if (r == null) return;
        historyService.record(r.instanceId, "cpu_pct", r.sampledAt, r.cpuPct);
        historyService.record(r.instanceId, "db_time_aas", r.sampledAt, r.dbTimeAas);
        historyService.record(r.instanceId, "tm_lock_waiting", r.sampledAt, r.tmLockWaiting);
        historyService.record(r.instanceId, "tx_lock_waiting", r.sampledAt, r.txLockWaiting);
        historyService.record(r.instanceId, "ash_cpu_cores", r.sampledAt, r.cpuCores);
        // ASH 조회가 실패한 사이클은 분류 값을 아예 남기지 않는다 - 예전엔 0으로 저장돼 차트에 "부하 없음"처럼
        // 보였다(값이 없는 분은 차트에서 끊겨 보이는 게 맞다).
        if (r.ashCategoryAas != null) {
            for (int i = 0; i < ASH_METRIC_NAMES.length; i++) {
                historyService.record(r.instanceId, ASH_METRIC_NAMES[i], r.sampledAt, r.ashCategoryAas[i]);
            }
        }
        if (r.ashWaitClassAas != null) {
            for (int i = 0; i < ASH_WC_METRIC_NAMES.length; i++) {
                historyService.record(r.instanceId, ASH_WC_METRIC_NAMES[i], r.sampledAt, r.ashWaitClassAas[i]);
            }
        }
    }

    private SampleResult sampleOne(TargetDbConfig target, long sampledAt) {
        try (Connection conn = poolManager.getConnection(target); Statement st = conn.createStatement()) {
            double numCpus = 1;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$osstat WHERE stat_name = 'NUM_CPUS'")) {
                if (rs.next()) numCpus = rs.getDouble(1);
            }
            double cpuUsage = 0;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$sysmetric WHERE metric_name = 'CPU Usage Per Sec'")) {
                if (rs.next()) cpuUsage = rs.getDouble(1);
            }
            double cpuPct = numCpus > 0 ? Math.round((cpuUsage / numCpus) * 100.0) / 100.0 : 0;
            // CPU%의 분모는 위처럼 논리 CPU(NUM_CPUS) 그대로, 차트의 코어 기준선(ash_cpu_cores)만 물리 코어
            // (NUM_CPU_CORES) - 이유는 CpuCores 주석 참고(체크리스트 1-1, 2026-09-25 결정).
            double cpuCores = CpuCores.query(conn);

            double dbTimeAas = 0;
            try (ResultSet rs = st.executeQuery("SELECT value FROM v$sysmetric WHERE metric_name = 'Average Active Sessions'")) {
                if (rs.next()) dbTimeAas = rs.getDouble(1);
            }

            int tmLockWaiting = 0;
            int txLockWaiting = 0;
            st.setQueryTimeout(lockQueryTimeoutSeconds);
            try (ResultSet rs = st.executeQuery(
                    "SELECT type, COUNT(DISTINCT sid) as cnt FROM v$lock WHERE request > 0 AND type IN ('TX', 'TM') GROUP BY type")) {
                while (rs.next()) {
                    if ("TM".equals(rs.getString("type"))) tmLockWaiting = rs.getInt("cnt");
                    else if ("TX".equals(rs.getString("type"))) txLockWaiting = rs.getInt("cnt");
                }
            } catch (SQLException lockScanFailed) {
                // 타임아웃(ORA-01013) 등으로 락 스캔만 실패해도 CPU/DBTime 샘플은 이미 확보했으니 버리지 않는다.
                log.debug("Lock wait sampling skipped for db_id={}: {}", target.id(), lockScanFailed.toString());
            }

            // C4: DB 시각과 앱 시각의 차이를 매 사이클 갱신(SYSDATE는 초 단위라 오차는 1초 안팎).
            refreshClockOffset(conn, target.id());

            // 7분류(ash_*, Current Session 차트) + 8분류(ash_wc_*, 대시보드 개편)를 ASH 스캔 한 번으로 센다.
            // 실패하면 둘 다 null(= 이번 사이클은 기록하지 않음).
            double[] ashCategoryAas = null;
            double[] ashWaitClassAas = null;
            try {
                double[][] ash = sampleAsh(conn, target);
                ashCategoryAas = ash[0];
                ashWaitClassAas = ash[1];
            } catch (SQLException ashScanFailed) {
                log.debug("ASH category sampling skipped for db_id={}: {}", target.id(), ashScanFailed.toString());
            }

            try (Statement tsSt = conn.createStatement()) {
                tsSt.setQueryTimeout(lockQueryTimeoutSeconds);
                List<Map<String, Object>> tsAlerts = new ArrayList<>();
                try (ResultSet rs = tsSt.executeQuery(
                        "SELECT df.tablespace_name, " +
                                "ROUND(((df.total_mb - NVL(fs.free_mb, 0)) / df.total_mb) * 100, 1) as pct " +
                                "FROM (SELECT tablespace_name, ROUND(SUM(bytes) / 1048576) as total_mb " +
                                "      FROM dba_data_files GROUP BY tablespace_name) df " +
                                "LEFT JOIN (SELECT tablespace_name, ROUND(SUM(bytes) / 1048576) as free_mb " +
                                "           FROM dba_free_space GROUP BY tablespace_name) fs " +
                                "ON df.tablespace_name = fs.tablespace_name")) {
                    while (rs.next()) {
                        double pct = rs.getDouble("pct");
                        if (pct >= TABLESPACE_CRITICAL_PCT) {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("severity", "critical");
                            item.put("message", rs.getString("tablespace_name") + " 테이블스페이스 " + pct + "%");
                            item.put("occurredAt", sampledAt);
                            item.put("relatedSid", null);
                            tsAlerts.add(item);
                        }
                    }
                }
                tablespaceAlertsCache.put(target.id(), tsAlerts);
            } catch (SQLException tablespaceScanFailed) {
                // 이 샘플링 사이클만 건너뛰고 캐시는 직전 값을 유지한다(락 스캔 실패 처리와 같은 이유).
                log.debug("Tablespace usage sampling skipped for db_id={}: {}", target.id(), tablespaceScanFailed.toString());
            }

            return new SampleResult(target.id(), sampledAt, cpuPct, dbTimeAas, tmLockWaiting, txLockWaiting,
                    cpuCores, ashCategoryAas, ashWaitClassAas);
        } catch (Exception e) {
            log.warn("Instance metric sampling failed for db_id={}: {}", target.id(), e.toString());
            return null;
        }
    }

    /** "SELECT SYSDATE"로 DB 시각과 앱 시각 차이를 잰다(쿼리 전후 시각의 중간값 기준). */
    private long refreshClockOffset(Connection conn, String dbId) throws SQLException {
        long before = System.currentTimeMillis();
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT SYSDATE FROM dual")) {
            if (rs.next()) {
                long after = System.currentTimeMillis();
                Timestamp dbNow = rs.getTimestamp(1);
                long offset = dbNow.getTime() - (before + after) / 2;
                dbClockOffsetMs.put(dbId, offset);
                return offset;
            }
        }
        Long cached = dbClockOffsetMs.get(dbId);
        return cached == null ? 0L : cached;
    }

    /** 현재 ASH의 최신 sample_id(v$ash_info), 못 읽으면 -1. 활성 세션이 없던 초도 번호는 올라가므로 분모로 쓸 수 있다. */
    private long latestAshSampleId(Connection conn) {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT latest_sample_id FROM v$ash_info")) {
            return rs.next() ? rs.getLong(1) : -1L;
        } catch (SQLException e) {
            return -1L;
        }
    }

    /**
     * 7분류/8분류 AAS를 한 번의 ASH 스캔으로 센다 - 반환값 [0]=7분류(AshCategories.KEYS7 순서), [1]=8분류(KEYS8 순서).
     * 지난번 이후의 sample_id 구간을 세고(C1), 기준이 없거나(앱 재기동 직후) 인스턴스 재기동으로 번호가 줄었거나
     * 공백이 너무 길면 예전처럼 "최근 sampleIntervalSeconds초" 시간 창으로 센다.
     */
    private double[][] sampleAsh(Connection conn, TargetDbConfig target) throws SQLException {
        Long last = ashWatermark.get(target.id());
        long latest = latestAshSampleId(conn);
        boolean byId = latest > 0 && last != null && latest > last
                && (latest - last) <= (long) sampleIntervalSeconds * MAX_WATERMARK_GAP_CYCLES;
        long windowSeconds = byId ? (latest - last) : sampleIntervalSeconds;
        String where = byId ? "h.sample_id > ? AND h.sample_id <= ? " : "h.sample_time >= SYSDATE - (? / 86400) ";

        double[] c7 = new double[AshCategories.KEYS7.length];
        double[] c8 = new double[AshCategories.KEYS8.length];
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT cat7, cat8, COUNT(*) AS cnt FROM (" +
                        "SELECT " + AshCategories.CASE7 + " AS cat7, " + AshCategories.CASE8 + " AS cat8 " +
                        "FROM v$active_session_history h " +
                        "WHERE " + where +
                        "AND h.session_type = 'FOREGROUND' " +
                        EXCLUDE_SELF_SQL +
                        ") WHERE cat7 IS NOT NULL OR cat8 IS NOT NULL GROUP BY cat7, cat8")) {
            ps.setQueryTimeout(lockQueryTimeoutSeconds);
            if (byId) {
                ps.setLong(1, last);
                ps.setLong(2, latest);
            } else {
                ps.setInt(1, sampleIntervalSeconds);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long cnt = rs.getLong("cnt");
                    int i7 = AshCategories.indexOf(AshCategories.KEYS7, rs.getString("cat7"));
                    int i8 = AshCategories.indexOf(AshCategories.KEYS8, rs.getString("cat8"));
                    if (i7 >= 0) c7[i7] += cnt;
                    if (i8 >= 0) c8[i8] += cnt;
                }
            }
        }
        for (int i = 0; i < c7.length; i++) c7[i] = Math.round((c7[i] / windowSeconds) * 100.0) / 100.0;
        for (int i = 0; i < c8.length; i++) c8[i] = Math.round((c8[i] / windowSeconds) * 100.0) / 100.0;
        if (latest > 0) {
            ashWatermark.put(target.id(), latest);
        }
        return new double[][]{c7, c8};
    }

    // 모니터링 계정(= 이 커넥션의 접속 계정) 세션 제외. 예전 방식(dba_users LEFT JOIN + 계정명 리터럴)과
    // 결과는 같지만 ASH 행마다 딕셔너리 조인을 하지 않는다(query-performance-reviewer 검토, 2026-09-25).
    // user_id가 NULL인 행은 예전처럼 남긴다. MonitorService의 나머지 ASH 쿼리는 D1(공용 ASH 구간 조회)에서 같이 바꾼다.
    private static final String EXCLUDE_SELF_SQL =
            "AND (h.user_id IS NULL OR h.user_id <> TO_NUMBER(SYS_CONTEXT('USERENV', 'SESSION_USERID')))";

    // backfill은 기동·DB 등록 시 1회라 여유 있게 - 60분치 ASH GROUP BY가 바쁜 인스턴스에서 10초를 넘으면
    // 조용히 취소돼 차트가 빈 채로 남기 때문(query-performance-reviewer 검토 3번).
    private static final int BACKFILL_QUERY_TIMEOUT_SECONDS = 60;

    private static final DateTimeFormatter BUCKET_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * C3: ASH 인메모리 기록으로 최근 BACKFILL_MINUTES분을 1분 단위 AAS로 채운다(7분류·8분류 각각, 이미 샘플이 있는
     * 분은 건너뜀). 각 분의 값은 그 분이 끝나는 시각(앱 시각으로 변환)에 기록한다. ASH가 그만큼 거슬러 올라가지
     * 못하면(인메모리 보관 범위 밖) ASH에 남아 있는 가장 오래된 온전한 분부터만 채운다 - 모르는 분을 0으로 채우지 않는다.
     */
    private void backfill(TargetDbConfig target) {
        try (Connection conn = poolManager.getConnection(target)) {
            long offset = refreshClockOffset(conn, target.id());
            String dbNowMinute;
            String oldestMinute;
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT TO_CHAR(TRUNC(SYSDATE, 'MI'), 'YYYY-MM-DD HH24:MI'), " +
                                 "TO_CHAR(TRUNC(CAST(MIN(sample_time) AS DATE), 'MI') + 1/1440, 'YYYY-MM-DD HH24:MI') " +
                                 "FROM v$active_session_history")) {
                if (!rs.next() || rs.getString(1) == null) return;
                dbNowMinute = rs.getString(1);
                oldestMinute = rs.getString(2); // 가장 오래된 샘플이 속한 분의 다음 분 - 그 분부터 온전하다
            }
            LocalDateTime end = LocalDateTime.parse(dbNowMinute, BUCKET_FORMAT); // 이 분은 아직 진행 중이라 제외
            LocalDateTime start = end.minusMinutes(BACKFILL_MINUTES);
            if (oldestMinute != null) {
                LocalDateTime oldest = LocalDateTime.parse(oldestMinute, BUCKET_FORMAT);
                if (oldest.isAfter(start)) start = oldest;
            }
            if (!start.isBefore(end)) return;

            // 분 -> [7분류 카운트, 8분류 카운트]
            TreeMap<LocalDateTime, double[][]> buckets = new TreeMap<>();
            for (LocalDateTime m = start; m.isBefore(end); m = m.plusMinutes(1)) {
                buckets.put(m, new double[][]{new double[AshCategories.KEYS7.length], new double[AshCategories.KEYS8.length]});
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT TO_CHAR(TRUNC(CAST(sample_time AS DATE), 'MI'), 'YYYY-MM-DD HH24:MI') AS bucket, cat7, cat8, COUNT(*) AS cnt FROM (" +
                            "SELECT h.sample_time, " + AshCategories.CASE7 + " AS cat7, " + AshCategories.CASE8 + " AS cat8 " +
                            "FROM v$active_session_history h " +
                            "WHERE h.sample_time >= TO_DATE(?, 'YYYY-MM-DD HH24:MI') AND h.sample_time < TO_DATE(?, 'YYYY-MM-DD HH24:MI') " +
                            "AND h.session_type = 'FOREGROUND' " +
                            EXCLUDE_SELF_SQL +
                            ") WHERE cat7 IS NOT NULL OR cat8 IS NOT NULL " +
                            "GROUP BY TO_CHAR(TRUNC(CAST(sample_time AS DATE), 'MI'), 'YYYY-MM-DD HH24:MI'), cat7, cat8")) {
                ps.setQueryTimeout(BACKFILL_QUERY_TIMEOUT_SECONDS);
                ps.setString(1, start.format(BUCKET_FORMAT));
                ps.setString(2, end.format(BUCKET_FORMAT));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double[][] b = buckets.get(LocalDateTime.parse(rs.getString("bucket"), BUCKET_FORMAT));
                        if (b == null) continue;
                        long cnt = rs.getLong("cnt");
                        int i7 = AshCategories.indexOf(AshCategories.KEYS7, rs.getString("cat7"));
                        int i8 = AshCategories.indexOf(AshCategories.KEYS8, rs.getString("cat8"));
                        if (i7 >= 0) b[0][i7] += cnt;
                        if (i8 >= 0) b[1][i8] += cnt;
                    }
                }
            }

            // 분이 끝나는 DB 시각 -> 앱 시각(sampled_at)
            ZoneId zone = ZoneId.systemDefault();
            long fromApp = start.atZone(zone).toInstant().toEpochMilli() - offset;
            long toApp = end.plusMinutes(1).atZone(zone).toInstant().toEpochMilli() - offset;
            List<Long> have7 = historyService.sampleTimes(target.id(), ASH_METRIC_NAMES[0], fromApp - 60_000L, toApp);
            List<Long> have8 = historyService.sampleTimes(target.id(), ASH_WC_METRIC_NAMES[0], fromApp - 60_000L, toApp);

            List<Object[]> rows = new ArrayList<>();
            for (Map.Entry<LocalDateTime, double[][]> e : buckets.entrySet()) {
                long at = e.getKey().plusMinutes(1).atZone(zone).toInstant().toEpochMilli() - offset;
                if (!hasSampleNear(have7, at)) {
                    for (int i = 0; i < ASH_METRIC_NAMES.length; i++) {
                        rows.add(new Object[]{target.id(), ASH_METRIC_NAMES[i], at, Math.round(e.getValue()[0][i] / 60.0 * 100.0) / 100.0});
                    }
                }
                if (!hasSampleNear(have8, at)) {
                    for (int i = 0; i < ASH_WC_METRIC_NAMES.length; i++) {
                        rows.add(new Object[]{target.id(), ASH_WC_METRIC_NAMES[i], at, Math.round(e.getValue()[1][i] / 60.0 * 100.0) / 100.0});
                    }
                }
            }
            if (!rows.isEmpty()) {
                historyService.recordBatch(rows);
                log.info("ASH backfill for db_id={}: {} minute(s), {} value(s)", target.id(), buckets.size(), rows.size());
            }
        } catch (Exception e) {
            log.warn("ASH backfill skipped for db_id={}: {}", target.id(), e.toString());
        }
    }

    /** 이미 기록된 샘플 중 at 앞뒤 45초 안에 있는 게 있으면 true - 그 분은 채우지 않는다. */
    private static boolean hasSampleNear(List<Long> sampleTimes, long at) {
        for (Long t : sampleTimes) {
            if (Math.abs(t - at) <= 45_000L) return true;
        }
        return false;
    }

    /** MonitorService.getActiveAlerts()가 실시간 조회 대신 읽는 마지막 샘플링 결과. */
    List<Map<String, Object>> getCachedTablespaceAlerts(String dbId) {
        return tablespaceAlertsCache.getOrDefault(dbId, Collections.emptyList());
    }

    // MonitorService.monitoringAccountLiteral()과 동일 - 클래스가 달라 공유 못 하므로 그대로 복사.
    private String monitoringAccountLiteral(TargetDbConfig target) {
        return "'" + target.user().toUpperCase().replace("'", "''") + "'";
    }

    // sampleOne()의 오라클 조회 결과를 SQLite 기록 없이 들고만 있는 값 객체 - 기록은 sampleAll()이
    // 모든 병렬 조회가 끝난 뒤 순차로 한다(위 sampleAll() 주석 참고). Java 8(AIX 빌드) 호환을 위해
    // record 대신 평범한 클래스를 쓴다.
    private static final class SampleResult {
        final String instanceId;
        final long sampledAt;
        final double cpuPct;
        final double dbTimeAas;
        final int tmLockWaiting;
        final int txLockWaiting;
        final double cpuCores;
        final double[] ashCategoryAas; // 7분류(AshCategories.KEYS7 순서), ASH 조회 실패 시 null
        final double[] ashWaitClassAas; // 8분류(AshCategories.KEYS8 순서), ASH 조회 실패 시 null (2026-09-25)

        SampleResult(String instanceId, long sampledAt, double cpuPct, double dbTimeAas,
                     int tmLockWaiting, int txLockWaiting, double cpuCores, double[] ashCategoryAas,
                     double[] ashWaitClassAas) {
            this.instanceId = instanceId;
            this.sampledAt = sampledAt;
            this.cpuPct = cpuPct;
            this.dbTimeAas = dbTimeAas;
            this.tmLockWaiting = tmLockWaiting;
            this.txLockWaiting = txLockWaiting;
            this.cpuCores = cpuCores;
            this.ashCategoryAas = ashCategoryAas;
            this.ashWaitClassAas = ashWaitClassAas;
        }
    }
}
