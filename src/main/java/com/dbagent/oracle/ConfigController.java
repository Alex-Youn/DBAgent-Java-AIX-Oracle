package com.dbagent.oracle;

import com.dbagent.settings.SettingKey;
import com.dbagent.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import com.dbagent.query.SqlQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ConfigController {

    // 환경설정 DB화 P3-2(2026-10-04): 이 클래스가 쓰던 @Value 설정을 SettingsService 조회로 옮겼다(저장 즉시 반영).
    @Autowired
    private SettingsService settings;

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final DatabaseConfigService configService;
    private final SqlQueryService sqlQueryService;

    // Dashboard polling interval, read once by app.js on load and used for setInterval(fetchDashboard, ...)
    // - see application.properties for the default and tuning notes.

    // Fleet Overview auto-refresh interval, read once by fleet-overview.html on load - see
    // application.properties for the default and tuning notes.

    // Current Session 메뉴 "리프레쉬 주기(초)" 입력칸의 기본값(체크리스트 1-9, 2026-09-25: 5초 -> 3초 +
    // 프로퍼티화). app.js가 로드 시 한 번 읽어 입력칸에 채우고, 이후엔 화면에서 직접 바꿀 수 있다.
    // 세션 목록(Active Session 등 4개 탭)만 이 주기로 갱신되고, 위쪽 두 그래프는 이 값과 무관하게 따로
    // 갱신된다(app.js ASH_ACTIVITY_POLL_MS). application.properties는 skip-worktree라 키가 없으면 기본 3초,
    // 폐쇄망에서 잘못 넣어도 기동이 실패하지 않게 문자열로 받아 1~60초로 검증한다.
    // 세션 새로고침 기본 주기(1~60초)는 SettingsService 가 범위를 검증해 돌려준다 (환경설정 DB화 P3-2).

    // SQL Runner row-count defaults, read once by app.js to pre-fill and cap the row-limit input
    // - see application.properties (dbagent.sql-runner.*) for the values and tuning notes.


    public ConfigController(DatabaseConfigService configService, SqlQueryService sqlQueryService) {
        this.configService = configService;
        this.sqlQueryService = sqlQueryService;
    }

    @GetMapping("/api/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> result = configService.safeConfig();
        result.put("polling_interval_ms", settings.getInt(SettingKey.UI_POLLING_INTERVAL_MS));
        result.put("fleet_overview_polling_interval_ms", settings.getInt(SettingKey.FLEET_OVERVIEW_POLLING_INTERVAL_MS));
        result.put("session_refresh_seconds", settings.getInt(SettingKey.UI_SESSION_REFRESH_SECONDS));
        result.put("sql_runner_max_rows", settings.getInt(SettingKey.SQL_RUNNER_MAX_ROWS));
        result.put("sql_runner_max_rows_limit", settings.getInt(SettingKey.SQL_RUNNER_MAX_ROWS_LIMIT));
        // SQL 실행 메뉴 읽기 전용 모드 여부(dbagent.sql-runner.read-only) - 화면 안내 문구 표시용.
        // 실제 차단은 서버(SqlQueryService)가 한다.
        result.put("sql_runner_read_only", sqlQueryService.isReadOnly());
        return result;
    }
}
