package com.dbagent.monitor;

import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    }

    // Active Session Wait Class 차트 6단계(6시간/24시간, 자체 수집 경로 - 2026-09-22)용 metric_name
    // 7개 - MonitorService.getAshActivity()가 쓰는 내부 카테고리 키(cpu/latch/user_io/tx_lock/
    // system_io/tm_lock/other, ashCategoryAas 배열과 같은 순서)에 "ash_" 접두사만 붙인다.
    private static final String[] ASH_METRIC_NAMES =
            {"ash_cpu", "ash_latch", "ash_user_io", "ash_tx_lock", "ash_system_io", "ash_tm_lock", "ash_other"};

    private void recordIfPresent(SampleResult r) {
        if (r == null) return;
        historyService.record(r.instanceId, "cpu_pct", r.sampledAt, r.cpuPct);
        historyService.record(r.instanceId, "db_time_aas", r.sampledAt, r.dbTimeAas);
        historyService.record(r.instanceId, "tm_lock_waiting", r.sampledAt, r.tmLockWaiting);
        historyService.record(r.instanceId, "tx_lock_waiting", r.sampledAt, r.txLockWaiting);
        historyService.record(r.instanceId, "ash_cpu_cores", r.sampledAt, r.cpuCores);
        for (int i = 0; i < ASH_METRIC_NAMES.length; i++) {
            historyService.record(r.instanceId, ASH_METRIC_NAMES[i], r.sampledAt, r.ashCategoryAas[i]);
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

            // Active Session Wait Class 차트 6시간/24시간 구간(설계문서 §0 결정 6단계, 자체 수집 경로,
            // 2026-09-22)용 ASH 카테고리 샘플링 - MonitorService.getAshActivity()와 완전히 같은 CASE
            // 판정 기준을 그대로 복사해서 쓴다(두 경로가 같은 시간대를 조금이라도 다르게 분류하면
            // 30분/1시간 실시간 뷰와 6시간/24시간 자체 수집 뷰의 숫자가 어긋나 보이므로 반드시 일치시켜야
            // 함). "최근 sampleIntervalSeconds초" 창을 매 사이클 집계해 근사 AAS로 저장 - v$active_
            // session_history가 인메모리 최근 데이터만 담고 있어 조회 자체는 가볍다(대시보드 v2가 겪은
            // dba_data_files류의 무거운 딕셔너리 조인과는 다름, 위 TABLESPACE_CRITICAL_PCT 주석 참고).
            double[] ashCategoryAas = new double[7]; // cpu, latch, user_io, tx_lock, sys_io, tm_lock, other
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT category, COUNT(*) AS cnt FROM (" +
                            "SELECT " +
                            "CASE " +
                            "WHEN h.session_state = 'ON CPU' THEN 'cpu' " +
                            "WHEN h.wait_class = 'User I/O' THEN 'user_io' " +
                            "WHEN h.wait_class = 'System I/O' THEN 'system_io' " +
                            "WHEN h.event LIKE 'latch%' THEN 'latch' " +
                            "WHEN h.event LIKE 'enq: TX%' THEN 'tx_lock' " +
                            "WHEN h.event LIKE 'enq: TM%' THEN 'tm_lock' " +
                            "WHEN h.wait_class NOT IN ('User I/O', 'System I/O', 'Idle') THEN 'other' " +
                            "ELSE NULL " +
                            "END AS category " +
                            "FROM v$active_session_history h " +
                            "LEFT JOIN dba_users u ON h.user_id = u.user_id " +
                            "WHERE h.sample_time >= SYSDATE - (? / 86400) " +
                            "AND h.session_type = 'FOREGROUND' " +
                            "AND (u.username IS NULL OR u.username != " + monitoringAccountLiteral(target) + ")" +
                            ") WHERE category IS NOT NULL GROUP BY category")) {
                ps.setQueryTimeout(lockQueryTimeoutSeconds);
                ps.setInt(1, sampleIntervalSeconds);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long cnt = rs.getLong("cnt");
                        double aas = Math.round((cnt / (double) sampleIntervalSeconds) * 100.0) / 100.0;
                        switch (rs.getString("category")) {
                            case "cpu": ashCategoryAas[0] = aas; break;
                            case "latch": ashCategoryAas[1] = aas; break;
                            case "user_io": ashCategoryAas[2] = aas; break;
                            case "tx_lock": ashCategoryAas[3] = aas; break;
                            case "system_io": ashCategoryAas[4] = aas; break;
                            case "tm_lock": ashCategoryAas[5] = aas; break;
                            case "other": ashCategoryAas[6] = aas; break;
                            default: break;
                        }
                    }
                }
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
                    numCpus, ashCategoryAas);
        } catch (Exception e) {
            log.warn("Instance metric sampling failed for db_id={}: {}", target.id(), e.toString());
            return null;
        }
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
        final double[] ashCategoryAas; // cpu, latch, user_io, tx_lock, sys_io, tm_lock, other (6단계, 2026-09-22)

        SampleResult(String instanceId, long sampledAt, double cpuPct, double dbTimeAas,
                     int tmLockWaiting, int txLockWaiting, double cpuCores, double[] ashCategoryAas) {
            this.instanceId = instanceId;
            this.sampledAt = sampledAt;
            this.cpuPct = cpuPct;
            this.dbTimeAas = dbTimeAas;
            this.tmLockWaiting = tmLockWaiting;
            this.txLockWaiting = txLockWaiting;
            this.cpuCores = cpuCores;
            this.ashCategoryAas = ashCategoryAas;
        }
    }
}
