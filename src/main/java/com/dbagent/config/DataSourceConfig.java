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
}
