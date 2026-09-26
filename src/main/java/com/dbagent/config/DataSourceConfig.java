package com.dbagent.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * users.mv.db(계정/세션, spring.datasource.*)와 metrics.mv.db(instance_metric_history,
 * dbagent.metrics.datasource.*)를 별도 파일 + 별도 HikariCP 풀로 분리한다(오케스트레이터 요청,
 * 2026-09-18, 원본 DBAgent-Java 대응 커밋 포팅) - 샘플러(InstanceMetricSamplerService)가 60초마다
 * 계속 쓰는 유일한 고빈도 쓰기 주체라, users DB에서 떼어내는 것만으로 세션 검증/로그인 경로와의 경합이
 * 사실상 사라진다. H2 MVStore는 SQLite와 달리 단일 writer 락 제약이 원래 없지만, 그래도 파일을 분리해
 * 두 용도의 스키마/데이터를 물리적으로 분리해 두는 편이 향후 운영(백업/트러블슈팅)에도 낫다고 판단.
 *
 * DataSource 타입 빈을 하나라도 직접 정의하면 Spring Boot의 DataSourceAutoConfiguration/
 * JdbcTemplateAutoConfiguration이 @ConditionalOnMissingBean 때문에 기존 spring.datasource.*
 * 기반 기본 DataSource/JdbcTemplate 생성을 통째로 건너뛴다 - 그래서 users DB용 기본 빈도 여기서
 * 같이 명시적으로 만들어 @Primary로 지정한다(Boot가 내부적으로 쓰는 것과 동일한
 * DataSourceProperties + spring.datasource.hikari.* 바인딩 방식이라 동작은 기존과 동일).
 */
@Configuration
public class DataSourceConfig {

    @Primary
    @Bean(name = "usersDataSourceProperties")
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties usersDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Primary
    @Bean(name = "dataSource")
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(@Qualifier("usersDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Primary
    @Bean(name = "jdbcTemplate")
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "metricsDataSourceProperties")
    @ConfigurationProperties("dbagent.metrics.datasource")
    public DataSourceProperties metricsDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "metricsDataSource")
    @ConfigurationProperties("dbagent.metrics.datasource.hikari")
    public HikariDataSource metricsDataSource(@Qualifier("metricsDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "metricsJdbcTemplate")
    public JdbcTemplate metricsJdbcTemplate(@Qualifier("metricsDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    // db_instances(구 databases.json) 전용 H2 - 같은 이유로 users DB/metrics DB와 파일을 분리한다
    // (oracle.env 제거 마이그레이션 4-1단계, 2026-09-21, 원본 DBAgent-Java 대응 커밋 포팅). 파일은
    // dbconfig.mv.db가 된다.
    @Bean(name = "dbConfigDataSourceProperties")
    @ConfigurationProperties("dbagent.dbconfig.datasource")
    public DataSourceProperties dbConfigDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "dbConfigDataSource")
    @ConfigurationProperties("dbagent.dbconfig.datasource.hikari")
    public HikariDataSource dbConfigDataSource(@Qualifier("dbConfigDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "dbConfigJdbcTemplate")
    public JdbcTemplate dbConfigJdbcTemplate(@Qualifier("dbConfigDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 성능 분석 수집 저장소(2026-09-26, 오케스트레이터 결정 - PerfStoreService). metrics 저장소와 파일을 나눠 분 단위
     * 계정·서버·SQL 요약 쓰기가 대시보드·샘플러 쓰기와 잠금을 다투지 않게 한다.
     * application.properties는 현장에서 pull로 갱신되지 않으므로(skip-worktree) 키가 없어도 동작해야 한다 -
     * dbagent.perf.datasource.url이 없으면 metrics URL의 파일 이름만 perf로 바꿔 같은 폴더(db_config/)에 만든다.
     * 드라이버·계정·접속 초기화 SQL도 metrics 설정을 그대로 따른다(SQLite WAL / H2 LOCK_TIMEOUT).
     */
    @Bean(name = "perfDataSource")
    public HikariDataSource perfDataSource(org.springframework.core.env.Environment env) {
        String metricsUrl = env.getProperty("dbagent.metrics.datasource.url", "jdbc:sqlite:metrics.db?busy_timeout=5000");
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("perf");
        ds.setJdbcUrl(env.getProperty("dbagent.perf.datasource.url", derivePerfUrl(metricsUrl)));
        String driver = env.getProperty("dbagent.metrics.datasource.driver-class-name");
        if (driver != null) ds.setDriverClassName(driver);
        String user = env.getProperty("dbagent.metrics.datasource.username");
        if (user != null) ds.setUsername(user);
        String password = env.getProperty("dbagent.metrics.datasource.password");
        if (password != null) ds.setPassword(password);
        ds.setMaximumPoolSize(env.getProperty("dbagent.perf.datasource.hikari.maximum-pool-size", Integer.class, 4));
        String initSql = env.getProperty("dbagent.metrics.datasource.hikari.connection-init-sql");
        if (initSql != null) ds.setConnectionInitSql(initSql);
        return ds;
    }

    @Bean(name = "perfJdbcTemplate")
    public JdbcTemplate perfJdbcTemplate(@Qualifier("perfDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** ...metrics.db?x → ...perf.db?x (SQLite), ...db_config/metrics;x → ...db_config/perf;x (H2). */
    static String derivePerfUrl(String metricsUrl) {
        int cut = metricsUrl.length();
        for (char c : new char[]{'?', ';'}) {
            int i = metricsUrl.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        String path = metricsUrl.substring(0, cut);
        String rest = metricsUrl.substring(cut);
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf(':'));
        String file = path.substring(slash + 1);
        String renamed = file.contains("metrics") ? file.replace("metrics", "perf") : "perf" + (file.endsWith(".db") ? ".db" : "");
        return path.substring(0, slash + 1) + renamed + rest;
    }
}
