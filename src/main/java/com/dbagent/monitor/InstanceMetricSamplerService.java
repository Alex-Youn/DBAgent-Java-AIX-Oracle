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

    private void recordIfPresent(SampleResult r) {
        if (r == null) return;
        historyService.record(r.instanceId, "cpu_pct", r.sampledAt, r.cpuPct);
        historyService.record(r.instanceId, "db_time_aas", r.sampledAt, r.dbTimeAas);
        historyService.record(r.instanceId, "tm_lock_waiting", r.sampledAt, r.tmLockWaiting);
        historyService.record(r.instanceId, "tx_lock_waiting", r.sampledAt, r.txLockWaiting);
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

            return new SampleResult(target.id(), sampledAt, cpuPct, dbTimeAas, tmLockWaiting, txLockWaiting);
        } catch (Exception e) {
            log.warn("Instance metric sampling failed for db_id={}: {}", target.id(), e.toString());
            return null;
        }
    }

    /** MonitorService.getActiveAlerts()가 실시간 조회 대신 읽는 마지막 샘플링 결과. */
    List<Map<String, Object>> getCachedTablespaceAlerts(String dbId) {
        return tablespaceAlertsCache.getOrDefault(dbId, Collections.emptyList());
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

        SampleResult(String instanceId, long sampledAt, double cpuPct, double dbTimeAas,
                     int tmLockWaiting, int txLockWaiting) {
            this.instanceId = instanceId;
            this.sampledAt = sampledAt;
            this.cpuPct = cpuPct;
            this.dbTimeAas = dbTimeAas;
            this.tmLockWaiting = tmLockWaiting;
            this.txLockWaiting = txLockWaiting;
        }
    }
}
