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

/**
 * instance_metric_history를 채우는 주기적 샘플러. Oracle 인스턴스 대시보드의 CpuDbTimeLineChart/
 * LockTrendChart가 쓸 시계열을 만든다. 사용자가 화면을 보고 있지 않아도 계속 쌓여야 추이가 끊기지
 * 않으므로, 요청 기반이 아니라 서버 스케줄러로 동작한다.
 *
 * fleet_status(MonitorController)의 CompletableFuture 병렬 폴링과 달리 순차 루프를 쓴다 - 이건
 * 사용자가 기다리는 HTTP 요청이 아니라 백그라운드 주기 작업이라 한 인스턴스가 느려도 다음 사이클에서
 * 다시 시도하면 되고, 인스턴스별 connectTimeoutMs로 이미 상한이 걸려 있다.
 */
@Service
public class InstanceMetricSamplerService {

    private static final Logger log = LoggerFactory.getLogger(InstanceMetricSamplerService.class);

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
        for (TargetDbConfig target : configService.listAllInstances()) {
            if (!"oracle".equalsIgnoreCase(target.dbType())) {
                continue;
            }
            try {
                sampleOne(target, sampledAt);
            } catch (Exception e) {
                log.warn("Instance metric sampling failed for db_id={}: {}", target.id(), e.toString());
            }
        }
    }

    private void sampleOne(TargetDbConfig target, long sampledAt) throws SQLException {
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

            historyService.record(target.id(), "cpu_pct", sampledAt, cpuPct);
            historyService.record(target.id(), "db_time_aas", sampledAt, dbTimeAas);
            historyService.record(target.id(), "tm_lock_waiting", sampledAt, tmLockWaiting);
            historyService.record(target.id(), "tx_lock_waiting", sampledAt, txLockWaiting);
        }
    }
}
