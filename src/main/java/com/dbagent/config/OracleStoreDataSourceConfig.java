package com.dbagent.config;

import com.dbagent.store.ConditionalOnStoreType;
import com.dbagent.store.OracleStorePools;
import com.dbagent.store.StoreId;
import com.dbagent.store.StoreType;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * 내부 저장소가 Oracle일 때의 DataSource 빈(2026-10-13, 내부 저장소 Oracle 선택 지원 1단계).
 * 빈 이름은 {@link DataSourceConfig}의 H2용과 같고, 저장소별로 둘 중 하나만 만들어진다 - 그래서 서비스·JdbcTemplate 쪽은
 * 어느 엔진인지 몰라도 된다. 풀 설정은 {@link OracleStorePools}에 있다.
 *
 * <p>H2용 빈과 달리 {@code @ConfigurationProperties} 바인딩을 걸지 않는다 - 걸면 번들 설정의 H2 전용 값
 * (hikari.connection-init-sql 등)이 Oracle 풀에 실린다.</p>
 */
@Configuration
public class OracleStoreDataSourceConfig {

    @Primary
    @Bean(name = "dataSource")
    @ConditionalOnStoreType(id = StoreId.USERS, type = StoreType.ORACLE)
    public HikariDataSource oracleUsersDataSource(Environment env) {
        return OracleStorePools.create(env, StoreId.USERS);
    }

    @Bean(name = "metricsDataSource")
    @ConditionalOnStoreType(id = StoreId.METRICS, type = StoreType.ORACLE)
    public HikariDataSource oracleMetricsDataSource(Environment env) {
        return OracleStorePools.create(env, StoreId.METRICS);
    }

    @Bean(name = "dbConfigDataSource")
    @ConditionalOnStoreType(id = StoreId.DBCONFIG, type = StoreType.ORACLE)
    public HikariDataSource oracleDbConfigDataSource(Environment env) {
        return OracleStorePools.create(env, StoreId.DBCONFIG);
    }

    @Bean(name = "perfDataSource")
    @ConditionalOnStoreType(id = StoreId.PERF, type = StoreType.ORACLE)
    public HikariDataSource oraclePerfDataSource(Environment env) {
        return OracleStorePools.create(env, StoreId.PERF);
    }
}
