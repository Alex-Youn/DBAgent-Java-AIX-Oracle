package com.dbagent.oracle;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ConfigController {

    private final DatabaseConfigService configService;

    // Dashboard polling interval, read once by app.js on load and used for setInterval(fetchDashboard, ...)
    // - see application.properties for the default and tuning notes.
    @Value("${dbagent.ui.polling-interval-ms:2000}")
    private int pollingIntervalMs;

    // Fleet Overview auto-refresh interval, read once by fleet-overview.html on load - see
    // application.properties for the default and tuning notes.
    @Value("${dbagent.fleet-overview.polling-interval-ms:3000}")
    private int fleetOverviewPollingIntervalMs;

    // SQL Runner row-count defaults, read once by app.js to pre-fill and cap the row-limit input
    // - see application.properties (dbagent.sql-runner.*) for the values and tuning notes.
    @Value("${dbagent.sql-runner.max-rows:500}")
    private int sqlRunnerMaxRows;

    @Value("${dbagent.sql-runner.max-rows-limit:5000}")
    private int sqlRunnerMaxRowsLimit;

    // 오라클 인스턴스 대시보드 v3(벤토형) 히어로 스코어카드의 종합 헬스 스코어 가중치/목표치.
    // oracle-instance-dashboard-UI-spec_2.md §5 권장대로 하드코딩 대신 설정값으로 뺐다 - 인스턴스
    // SLA/운영 기준에 맞춰 조정할 수 있어야 함. 점수는 프론트(app.js)에서 이미 받아온 instance_overview/
    // active_alerts/metric_history 값으로 계산하므로 별도 백엔드 엔드포인트는 없다.
    @Value("${dbagent.health-score.cpu-target-pct:80}")
    private double healthScoreCpuTargetPct;

    @Value("${dbagent.health-score.cpu-weight:1.0}")
    private double healthScoreCpuWeight;

    @Value("${dbagent.health-score.mem-target-pct:85}")
    private double healthScoreMemTargetPct;

    @Value("${dbagent.health-score.mem-weight:1.0}")
    private double healthScoreMemWeight;

    @Value("${dbagent.health-score.tx-lock-penalty:5}")
    private double healthScoreTxLockPenalty;

    @Value("${dbagent.health-score.alert-critical-penalty:15}")
    private double healthScoreAlertCriticalPenalty;

    @Value("${dbagent.health-score.alert-warning-penalty:5}")
    private double healthScoreAlertWarningPenalty;

    public ConfigController(DatabaseConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/api/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> result = configService.safeConfig();
        result.put("polling_interval_ms", pollingIntervalMs);
        result.put("fleet_overview_polling_interval_ms", fleetOverviewPollingIntervalMs);
        result.put("sql_runner_max_rows", sqlRunnerMaxRows);
        result.put("sql_runner_max_rows_limit", sqlRunnerMaxRowsLimit);
        result.put("health_score_cpu_target_pct", healthScoreCpuTargetPct);
        result.put("health_score_cpu_weight", healthScoreCpuWeight);
        result.put("health_score_mem_target_pct", healthScoreMemTargetPct);
        result.put("health_score_mem_weight", healthScoreMemWeight);
        result.put("health_score_tx_lock_penalty", healthScoreTxLockPenalty);
        result.put("health_score_alert_critical_penalty", healthScoreAlertCriticalPenalty);
        result.put("health_score_alert_warning_penalty", healthScoreAlertWarningPenalty);
        return result;
    }
}
