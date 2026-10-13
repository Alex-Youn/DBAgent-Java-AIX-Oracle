package com.dbagent.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 저장소 하나를 쓰는 데 필요한 것 묶음: 어느 저장소인지, 접속(JdbcTemplate), 문법(방언), 트랜잭션.
 * 서비스는 {@code @Qualifier("perfStore") StoreHandle}처럼 주입받는다(빈 이름: usersStore, dbConfigStore, metricsStore, perfStore).
 * 연결 실패 서킷(StoreHealth)은 4단계에서 여기에 더한다.
 */
public final class StoreHandle {

    private final StoreId id;
    private final StoreDialect dialect;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public StoreHandle(StoreId id, StoreType type, JdbcTemplate jdbc) {
        this.id = id;
        this.dialect = dialectOf(type);
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public static StoreDialect dialectOf(StoreType type) {
        return type == StoreType.ORACLE ? OracleStoreDialect.INSTANCE : H2StoreDialect.INSTANCE;
    }

    public StoreId id() { return id; }
    public StoreType type() { return dialect.type(); }
    public StoreDialect dialect() { return dialect; }
    public JdbcTemplate jdbc() { return jdbc; }
    public TransactionTemplate tx() { return tx; }
}
