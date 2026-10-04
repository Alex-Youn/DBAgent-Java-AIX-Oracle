package com.dbagent.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import java.io.File;

/**
 * 부트스트랩 설정 파일(conf/dbagent.conf) 점검 + SQLite 파일 폴더 보장
 * (환경설정 DB화 P1-1, 2026-10-04 오케스트레이터 결정 - 설계문서/환경설정_DB화_설계_2026-10-04.md).
 *
 * <p>1) conf/dbagent.conf는 번들 application.properties의 spring.config.import로 읽는데, 확장자 힌트
 * [.properties]를 빼먹거나 이름에 오타가 있어도 optional: 때문에 오류·경고 없이 파일이 통째로 무시된다
 * (로컬 실측 2026-10-04). 포트가 조용히 8005로 떠서 운영 포트와 달라지는 사고를 막으려고, 파일이 있는데
 * 읽히지 않았으면 경고를 남기고 읽혔으면 INFO로 알린다.
 *
 * <p>2) 번들 기본 DB 경로를 db_config/ 하위로 바꿨는데 sqlite-jdbc는 상위 폴더를 만들어 주지 않아(H2는 만든다),
 * 외부 설정 파일 없이 처음 기동하면 폴더가 없어 DB를 못 연다 - 연결 전에 폴더를 만든다.
 */
public class BootstrapConfigPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String CONF_FILE = "conf/dbagent.conf";
    private static final String SQLITE_PREFIX = "jdbc:sqlite:";
    private static final String[] DB_URL_KEYS = {
            "spring.datasource.url",
            "dbagent.metrics.datasource.url",
            "dbagent.dbconfig.datasource.url",
            "dbagent.perf.datasource.url"
    };

    private final Log log;

    // DeferredLogFactory: 이 시점엔 로깅 시스템이 아직 초기화 전이라 로그를 모아 뒀다가 나중에 출력한다.
    public BootstrapConfigPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(BootstrapConfigPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        checkConfFile(environment);
        ensureSqliteDirs(environment);
    }

    // spring.config.import 처리(ConfigDataEnvironmentPostProcessor)보다 나중에 돌아야 로딩 결과를 볼 수 있다.
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private void checkConfFile(ConfigurableEnvironment environment) {
        File conf = new File(CONF_FILE);
        if (!conf.isFile()) {
            log.info("부트스트랩 설정 파일 없음(" + CONF_FILE + ") - jar 번들 기본값으로 기동합니다.");
            return;
        }
        boolean loaded = false;
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source.getName().contains("dbagent.conf")) {
                loaded = true;
                break;
            }
        }
        if (loaded) {
            log.info("부트스트랩 설정 파일 로드됨: " + conf.getAbsolutePath());
        } else {
            log.warn("부트스트랩 설정 파일이 있지만 읽히지 않았습니다: " + conf.getAbsolutePath()
                    + " - 번들 application.properties의 spring.config.import에 [.properties] 확장자 힌트가 있는지,"
                    + " 작업 디렉터리(" + new File(".").getAbsolutePath() + ")가 맞는지 확인하세요."
                    + " 지금은 파일 값 없이 번들 기본값(포트 등)으로 기동합니다.");
        }
    }

    private void ensureSqliteDirs(ConfigurableEnvironment environment) {
        for (String key : DB_URL_KEYS) {
            String url = environment.getProperty(key);
            if (url == null || !url.startsWith(SQLITE_PREFIX)) {
                continue;
            }
            String path = url.substring(SQLITE_PREFIX.length());
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            File parent = new File(path).getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) {
                if (parent.mkdirs()) {
                    log.info("DB 폴더를 만들었습니다: " + parent.getAbsolutePath());
                } else {
                    log.warn("DB 폴더를 만들지 못했습니다: " + parent.getAbsolutePath() + " (" + key + ")");
                }
            }
        }
    }
}
