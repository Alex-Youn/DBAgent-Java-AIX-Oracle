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
        return result;
    }
}
