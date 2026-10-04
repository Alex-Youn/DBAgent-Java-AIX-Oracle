package com.dbagent.settings;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 기동 초기에 dbconfig DB 의 환경설정 값(app_setting)을 Spring Environment 에 끼워 넣는다
 * (환경설정 DB화 P1-3, 2026-10-04, 설계문서/환경설정_DB화_설계_2026-10-04.md 5절).
 *
 * <p>목적: 기존 {@code @Value("${키:기본값}")} 소비자 코드를 하나도 고치지 않고도 화면에서 저장한 값이 "재시작 후"
 * 반영되게 한다. 주입 위치는 conf/dbagent.conf 바로 아래, 옛 외부 application.properties 바로 위다.
 * 우선순위: 기동 인자·-D·환경변수 &gt; conf/dbagent.conf &gt; <b>DB</b> &gt; 옛 외부 파일 &gt; jar 번들 &gt; 코드 기본값.
 *
 * <p>Spring 빈이 만들어지기 전이라 JdbcTemplate/Hikari 를 못 쓰므로 JDBC 로 직접 읽고 바로 닫는다 (H2 파일 잠금을
 * 남기지 않기 위해 반드시 close). 읽기만 하고 테이블은 만들지 않는다 - 첫 기동이라 테이블이 없으면 주입할 값이
 * 없는 정상 경로다. 읽기에 실패해도 기동은 계속한다(파일·번들 값으로 fail-open).
 *
 * <p>잘못된 값(범위 밖·모르는 키)은 주입하지 않는다 - {@code @Value int} 소비자가 파싱 오류로 기동에 실패하는 것을 막는다.
 *
 * <p>비상 탈출구: {@code -Ddbagent.settings.ignore-db=true} (또는 같은 이름의 환경변수·인자·conf)이면 DB 값을 통째로
 * 건너뛴다. 잘못 저장한 값 때문에 앱이 안 뜨거나 화면에 못 들어갈 때 쓴다.
 *
 * <p>비밀값은 CredentialCipher 로 복호화해야 하는데 이 시점엔 빈이 없다. 현재 비밀 키가 없어 문제가 없고,
 * {@code secret()} 키는 주입하지 않고 SettingsService 조회로만 읽는다.
 */
public class SettingsDbEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String IGNORE_DB_KEY = "dbagent.settings.ignore-db";

    private static final String URL_KEY = "dbagent.dbconfig.datasource.url";
    private static final String DRIVER_KEY = "dbagent.dbconfig.datasource.driver-class-name";
    private static final String USER_KEY = "dbagent.dbconfig.datasource.username";
    private static final String PASSWORD_KEY = "dbagent.dbconfig.datasource.password";

    private final Log log;

    public SettingsDbEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(SettingsDbEnvironmentPostProcessor.class);
    }

    // BootstrapConfigPostProcessor(DB 폴더 보장)보다 뒤에, 다른 일반 후처리기보다도 뒤에 돈다.
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (isIgnoreDb(environment)) {
            log.warn("안전 모드(" + IGNORE_DB_KEY + "=true): DB 에 저장된 환경설정 값을 모두 무시하고 파일·번들 값으로 기동합니다.");
            return;
        }
        String url = environment.getProperty(URL_KEY);
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        Map<String, Object> rows = readRows(environment, url);
        if (rows.isEmpty()) {
            return;
        }
        MapPropertySource source = new MapPropertySource(SettingsService.DB_INJECTED_SOURCE_NAME, rows);
        MutablePropertySources sources = environment.getPropertySources();
        // conf/dbagent.conf 가 아닌 첫 설정 파일(옛 외부 파일 또는 번들) 바로 앞 = conf 바로 아래.
        String before = null;
        for (PropertySource<?> s : sources) {
            String name = s.getName();
            if (name.startsWith("Config resource") && !name.contains("dbagent.conf")) {
                before = name;
                break;
            }
        }
        if (before != null) {
            sources.addBefore(before, source);
        } else {
            sources.addLast(source);
        }
        log.info("DB 에 저장된 환경설정 " + rows.size() + "건을 적용했습니다 (기동 인자·conf 보다 아래, 옛 외부 파일·번들 보다 위).");
    }

    static boolean isIgnoreDb(ConfigurableEnvironment environment) {
        return "true".equalsIgnoreCase(environment.getProperty(IGNORE_DB_KEY, "false").trim());
    }

    private Map<String, Object> readRows(ConfigurableEnvironment environment, String url) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String driver = environment.getProperty(DRIVER_KEY);
        if (driver != null && !driver.trim().isEmpty()) {
            try {
                Class.forName(driver.trim());
            } catch (ClassNotFoundException e) {
                log.warn("환경설정 DB 드라이버를 찾지 못해 DB 값을 건너뜁니다: " + driver);
                return out;
            }
        }
        String user = environment.getProperty(USER_KEY);
        String password = environment.getProperty(PASSWORD_KEY);
        // Spring 은 H2 임베디드 URL 에 계정이 없으면 sa / 빈 비밀번호를 쓴다 - 같은 규칙.
        if (user == null && url.startsWith("jdbc:h2:")) {
            user = "sa";
            password = "";
        }
        Connection conn = null;
        try {
            conn = (user == null) ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password == null ? "" : password);
            Statement st = conn.createStatement();
            try {
                st.setQueryTimeout(10);
                ResultSet rs = st.executeQuery("SELECT setting_key, setting_value FROM app_setting");
                try {
                    while (rs.next()) {
                        accept(rs.getString(1), rs.getString(2), out);
                    }
                } finally {
                    rs.close();
                }
            } finally {
                st.close();
            }
        } catch (SQLException e) {
            String msg = String.valueOf(e.getMessage());
            String lower = msg.toLowerCase();
            if (lower.contains("no such table") || lower.contains("not found")) {
                log.info("환경설정 테이블이 아직 없어 DB 값 없이 기동합니다 (첫 기동이면 정상).");
            } else {
                log.warn("DB 의 환경설정 값을 읽지 못해 파일·번들 값으로 기동합니다: " + msg);
            }
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignore) {
                    // 닫기 실패는 기동에 영향이 없다.
                }
            }
        }
        return out;
    }

    private void accept(String key, String value, Map<String, Object> out) {
        SettingKey k = SettingKey.byKey(key);
        if (k == null) {
            log.warn("환경설정 DB 에 모르는 키가 있어 적용하지 않습니다: " + key);
            return;
        }
        if (k.secret()) {
            return;
        }
        String problem = k.validate(value);
        if (problem != null) {
            log.warn("환경설정 DB 값이 올바르지 않아 적용하지 않습니다: " + key + " = '" + value + "' (" + problem + ")");
            return;
        }
        out.put(key, value == null ? "" : value.trim());
    }
}
