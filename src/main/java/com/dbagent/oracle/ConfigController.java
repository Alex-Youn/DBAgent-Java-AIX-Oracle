package com.dbagent.oracle;

import com.dbagent.settings.SettingKey;
import com.dbagent.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import com.dbagent.query.SqlQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.dbagent.auth.AuthService;

import java.util.Map;
import java.util.Optional;

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


    private final AuthService authService;

    public ConfigController(DatabaseConfigService configService, SqlQueryService sqlQueryService,
                            AuthService authService) {
        this.configService = configService;
        this.sqlQueryService = sqlQueryService;
        this.authService = authService;
    }

    /**
     * 토큰은 선택. 신분에 따라 DB 목록 수준을 나눈다(2026-10-09 B안, 점검 B-2 - 인증 없이 host/sid/계정명이 노출되던 문제).
     * 토큰 없음/무효 = id·name·db_type만, 관리자 = 전체, 일반 사용자 = 숨김 DB 제외 + id·name·db_type·session_thresholds.
     * UI 설정값(폴링 주기 등)은 로그인 전 화면도 쓰므로 항상 포함한다.
     */
    @GetMapping("/api/config")
    public Map<String, Object> getConfig(@RequestParam(value = "token", required = false) String token) {
        Optional<AuthService.AuthSession> session = (token == null || token.trim().isEmpty())
                ? Optional.<AuthService.AuthSession>empty() : authService.sessionForToken(token);
        Map<String, Object> result;
        if (!session.isPresent()) {
            result = configService.safeConfig(DatabaseConfigService.ConfigLevel.MINIMAL, null);
        } else if ("admin".equals(session.get().role())) {
            result = configService.safeConfig();
        } else {
            result = configService.safeConfig(DatabaseConfigService.ConfigLevel.USER, session.get().hiddenDbs());
        }
        result.put("polling_interval_ms", settings.getInt(SettingKey.UI_POLLING_INTERVAL_MS));
        result.put("fleet_overview_polling_interval_ms", settings.getInt(SettingKey.FLEET_OVERVIEW_POLLING_INTERVAL_MS));
        result.put("session_refresh_seconds", settings.getInt(SettingKey.UI_SESSION_REFRESH_SECONDS));
        // AAS 차트 CPU 기준선을 물리 코어로 볼지(dbagent.ui.aas-cpu-physical, 기본 꺼짐 = 논리 CPU) - 화면이 AAS 차트를 갱신할 때(약 1분)마다 읽는다
        result.put("aas_cpu_physical", settings.getBool(SettingKey.AAS_CPU_PHYSICAL));
        result.put("sql_runner_max_rows", settings.getInt(SettingKey.SQL_RUNNER_MAX_ROWS));
        result.put("sql_runner_max_rows_limit", settings.getInt(SettingKey.SQL_RUNNER_MAX_ROWS_LIMIT));
        // SQL 실행 메뉴 읽기 전용 모드 여부(dbagent.sql-runner.read-only) - 화면 안내 문구 표시용.
        // 실제 차단은 서버(SqlQueryService)가 한다.
        result.put("sql_runner_read_only", sqlQueryService.isReadOnly());
        return result;
    }
}
