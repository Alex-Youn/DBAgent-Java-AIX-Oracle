package com.dbagent.monitor;

import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 대시보드 추이 차트(CpuDbTimeLineChart, LockTrendChart)용 시계열 저장소.
 * V$ 뷰는 현재 시점 스냅샷만 주므로, 주기적으로 샘플링한 값을 이 테이블에 쌓아 추이를 그린다.
 * 전용 H2(metrics.mv.db, com.dbagent.config.DataSourceConfig)를 쓴다 - 처음엔 users DB(계정/세션)에
 * 같이 있었으나, 이 서비스가 60초마다 계속 쓰는 유일한 고빈도 쓰기 주체라 세션 검증/로그인 경로와의
 * 경합을 없애려고 파일 자체를 분리했다(오케스트레이터 요청, 2026-09-18, 원본 대응 커밋 포팅).
 *
 * <p>원본(DBAgent-Java)은 SQLite를 써서 {@code TEXT}/{@code INTEGER}/{@code INSERT OR REPLACE}를
 * 그대로 쓸 수 있었지만, 이 포팅본은 H2를 쓰므로 세 가지를 바꿨다(포팅 중 로컬 기동 실패로 발견,
 * 2026-09-17): {@code TEXT}는 H2에서 CLOB류로 매핑되어 PK/인덱스에 못 써서 {@code VARCHAR}로,
 * {@code INTEGER}(32비트)는 epoch millis(약 1.7조)를 못 담아서 {@code BIGINT}로, SQLite 전용
 * upsert 문법인 {@code INSERT OR REPLACE}는 H2의 {@code MERGE INTO ... KEY(...)}로 교체.</p>
 */
@Service
public class InstanceMetricHistoryService {

    private static final Logger log = LoggerFactory.getLogger(InstanceMetricHistoryService.class);

    private final JdbcTemplate jdbc;
    private final JdbcTemplate legacyUsersJdbc;

    // AuthService.sessionTtlDays와 같은 이유로 설정값화 - 운영 중 보존 기간을 재빌드 없이 바꿀 수 있어야 함
    // (2026-09-15 agent 백로그 논의에서 정한 원칙을 이 테이블에도 그대로 적용).
    @Value("${dbagent.monitor.metric-retention-days:30}")
    private int retentionDays;

    public InstanceMetricHistoryService(@Qualifier("metricsJdbcTemplate") JdbcTemplate jdbc,
                                         @Qualifier("jdbcTemplate") JdbcTemplate legacyUsersJdbc) {
        this.jdbc = jdbc;
        this.legacyUsersJdbc = legacyUsersJdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS instance_metric_history (" +
                "instance_name VARCHAR NOT NULL, " +
                "metric_name VARCHAR NOT NULL, " +
                "sampled_at BIGINT NOT NULL, " +
                "value DOUBLE, " +
                "PRIMARY KEY (instance_name, metric_name, sampled_at))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_instance_metric_history_lookup " +
                "ON instance_metric_history (instance_name, metric_name, sampled_at)");
        migrateFromUsersDbIfPresent();
    }

    /**
     * instance_metric_history가 users DB 시절 이미 쌓아둔 데이터가 있으면 metrics DB로 옮기고 원본
     * 테이블은 지운다(1회성 - 이미 옮겨졌으면 INFORMATION_SCHEMA.TABLES에 테이블이 없어 다음부터는
     * 즉시 반환한다). H2는 unquoted 식별자를 대문자로 저장하므로 TABLE_NAME을 대문자로 비교한다.
     */
    private void migrateFromUsersDbIfPresent() {
        List<String> legacyTables = legacyUsersJdbc.query(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'INSTANCE_METRIC_HISTORY'",
                (rs, rowNum) -> rs.getString("TABLE_NAME"));
        if (legacyTables.isEmpty()) {
            return;
        }
        List<Object[]> rows = legacyUsersJdbc.query(
                "SELECT instance_name, metric_name, sampled_at, value FROM instance_metric_history",
                (rs, rowNum) -> new Object[]{
                        rs.getString("instance_name"), rs.getString("metric_name"),
                        rs.getLong("sampled_at"), rs.getDouble("value")});
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("MERGE INTO instance_metric_history " +
                    "(instance_name, metric_name, sampled_at, value) KEY (instance_name, metric_name, sampled_at) " +
                    "VALUES (?, ?, ?, ?)", rows);
        }
        legacyUsersJdbc.execute("DROP TABLE instance_metric_history");
        log.info("Migrated {} instance metric sample(s) from users DB to metrics DB, dropped legacy table",
                rows.size());
    }

    /** 같은 사이클에서 샘플러가 재시도해도 죽지 않도록 MERGE(upsert) - PK 충돌은 정상 경로다. */
    public void record(String instanceName, String metricName, long sampledAtMillis, double value) {
        jdbc.update("MERGE INTO instance_metric_history " +
                        "(instance_name, metric_name, sampled_at, value) KEY (instance_name, metric_name, sampled_at) " +
                        "VALUES (?, ?, ?, ?)",
                instanceName, metricName, sampledAtMillis, value);
    }

    /** 여러 값을 한 트랜잭션으로 기록 - 행은 {instanceName, metricName, sampledAtMillis, value} (C3 backfill, 2026-09-25). */
    public void recordBatch(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) return;
        jdbc.batchUpdate("MERGE INTO instance_metric_history " +
                "(instance_name, metric_name, sampled_at, value) KEY (instance_name, metric_name, sampled_at) " +
                "VALUES (?, ?, ?, ?)", rows);
    }

    /** 구간 안에 이미 기록된 샘플 시각 목록 - backfill이 이미 있는 분을 건너뛸 때 쓴다. */
    public List<Long> sampleTimes(String instanceName, String metricName, long fromMillis, long toMillis) {
        return jdbc.query(
                "SELECT sampled_at FROM instance_metric_history " +
                        "WHERE instance_name = ? AND metric_name = ? AND sampled_at BETWEEN ? AND ? " +
                        "ORDER BY sampled_at ASC",
                (rs, rowNum) -> rs.getLong("sampled_at"),
                instanceName, metricName, fromMillis, toMillis);
    }

    public List<Map<String, Object>> query(String instanceName, String metricName, long fromMillis, long toMillis) {
        return jdbc.query(
                "SELECT sampled_at, value FROM instance_metric_history " +
                        "WHERE instance_name = ? AND metric_name = ? AND sampled_at BETWEEN ? AND ? " +
                        "ORDER BY sampled_at ASC",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sampledAt", rs.getLong("sampled_at"));
                    row.put("value", rs.getDouble("value"));
                    return row;
                },
                instanceName, metricName, fromMillis, toMillis);
    }

    @Scheduled(cron = "0 45 3 * * *")
    void cleanupOldSamples() {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
        int deleted = jdbc.update("DELETE FROM instance_metric_history WHERE sampled_at < ?", cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} instance metric sample(s) older than {} day(s)", deleted, retentionDays);
        }
    }
}
