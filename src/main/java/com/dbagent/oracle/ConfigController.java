package com.dbagent.oracle;

import com.dbagent.query.SqlQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final DatabaseConfigService configService;
    private final SqlQueryService sqlQueryService;

    // Dashboard polling interval, read once by app.js on load and used for setInterval(fetchDashboard, ...)
    // - see application.properties for the default and tuning notes.
    @Value("${dbagent.ui.polling-interval-ms:2000}")
    private int pollingIntervalMs;

    // Fleet Overview auto-refresh interval, read once by fleet-overview.html on load - see
    // application.properties for the default and tuning notes.
    @Value("${dbagent.fleet-overview.polling-interval-ms:3000}")
    private int fleetOverviewPollingIntervalMs;

    // Current Session 메뉴 "리프레쉬 주기(초)" 입력칸의 기본값(체크리스트 1-9, 2026-09-25: 5초 -> 3초 +
    // 프로퍼티화). app.js가 로드 시 한 번 읽어 입력칸에 채우고, 이후엔 화면에서 직접 바꿀 수 있다.
    // 세션 목록(Active Session 등 4개 탭)만 이 주기로 갱신되고, 위쪽 두 그래프는 이 값과 무관하게 따로
    // 갱신된다(app.js ASH_ACTIVITY_POLL_MS). application.properties는 skip-worktree라 키가 없으면 기본 3초,
    // 폐쇄망에서 잘못 넣어도 기동이 실패하지 않게 문자열로 받아 1~60초로 검증한다.
    private static final int DEFAULT_SESSION_REFRESH_SECONDS = 3;
    private int sessionRefreshSeconds = DEFAULT_SESSION_REFRESH_SECONDS;

    @Value("${dbagent.ui.session-refresh-seconds:3}")
    void setSessionRefreshSeconds(String value) {
        int seconds;
        try {
            seconds = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            seconds = -1;
        }
        if (seconds < 1 || seconds > 60) {
            log.warn("dbagent.ui.session-refresh-seconds='{}' is not between 1 and 60 - using {}s",
                    value, DEFAULT_SESSION_REFRESH_SECONDS);
            seconds = DEFAULT_SESSION_REFRESH_SECONDS;
        }
        this.sessionRefreshSeconds = seconds;
    }

    // SQL Runner row-count defaults, read once by app.js to pre-fill and cap the row-limit input
    // - see application.properties (dbagent.sql-runner.*) for the values and tuning notes.
    @Value("${dbagent.sql-runner.max-rows:500}")
    private int sqlRunnerMaxRows;

    @Value("${dbagent.sql-runner.max-rows-limit:5000}")
    private int sqlRunnerMaxRowsLimit;

    public ConfigController(DatabaseConfigService configService, SqlQueryService sqlQueryService) {
        this.configService = configService;
        this.sqlQueryService = sqlQueryService;
    }

    @GetMapping("/api/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> result = configService.safeConfig();
        result.put("polling_interval_ms", pollingIntervalMs);
        result.put("fleet_overview_polling_interval_ms", fleetOverviewPollingIntervalMs);
        result.put("session_refresh_seconds", sessionRefreshSeconds);
        result.put("sql_runner_max_rows", sqlRunnerMaxRows);
        result.put("sql_runner_max_rows_limit", sqlRunnerMaxRowsLimit);
        // SQL 실행 메뉴 읽기 전용 모드 여부(dbagent.sql-runner.read-only) - 화면 안내 문구 표시용.
        // 실제 차단은 서버(SqlQueryService)가 한다.
        result.put("sql_runner_read_only", sqlQueryService.isReadOnly());
        return result;
    }
}
