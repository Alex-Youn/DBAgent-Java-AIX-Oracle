package com.dbagent.monitor;

import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 대시보드 개편(설계문서 `대시보드 UI 개선 설계.md` 4.2, 전체 작업순서 F1 - 2026-09-25)의 수집 테이블 5개.
 * instance_metric_history와 같은 전용 저장소(H2 metrics.mv.db, metricsJdbcTemplate - 원본 DBAgent-Java에서 포팅)에 둔다 - 60초/10분 주기 쓰기가
 * 계정·세션 경로(users.db)와 락을 다투지 않게 하려고 파일을 분리한 이유 그대로.
 *
 * <ul>
 *   <li>mon_sqlstat_delta - v$sqlstats 1분 델타 (PK에 plan_hash_value 포함: v$sqlstats가 그 단위)</li>
 *   <li>mon_lock_sample - ③ Lock 1분 요약 (실시간 값은 메모리). tm_holder_over_max = 장애 판정 수의 1분 내 최대값</li>
 *   <li>mon_kill_audit - 장애 처리(KILL) 감사 로그. 접속 인스턴스명만 기록(inst_id 없음 - RAC 원칙)</li>
 *   <li>mon_check_result - ⑧ 점검 결과. severity CRIT/WARN/INFO/OK/ERROR</li>
 *   <li>mon_segment_size - 테이블 크기 일 스냅샷(증가량 계산용)</li>
 * </ul>
 *
 * 관례: 테이블명 소문자 snake_case, 시각은 epoch ms 정수, 문자열 PK 컬럼은 VARCHAR(H2 이식본과 같은 DDL을
 * 쓰기 위해 - SQLite는 타입 이름을 그대로 받아들인다). 저장소마다 다른 것은 자동 증가 키, upsert 문법,
 * 분할 삭제 문법 세 가지뿐이다(AIX H2 이식본 참고).
 *
 * <p>파일 크기: 보관 기간이 지나 지운 공간은 H2 MVStore가 백그라운드 압축(auto compact)으로 재사용하므로 일정 기간
 * 뒤로는 파일이 더 커지지 않는다. dbconfig DB처럼 DEFRAG_ALWAYS를 걸지 않는다 - 종료가 느려지고, 비밀번호 재암호화
 * 때처럼 "지운 값이 파일에 남으면 안 되는" 요구가 이 테이블들에는 없다.</p>
 */
@Service
public class MonitorStoreService {

    private static final Logger log = LoggerFactory.getLogger(MonitorStoreService.class);

    /** 분할 삭제 한 번에 지우는 행 수 - 한 번에 다 지우면 긴 쓰기 락으로 60초 샘플러 쓰기가 막힌다. */
    private static final int DELETE_CHUNK_ROWS = 5000;

    private final JdbcTemplate jdbc;

    @Value("${dbagent.monitor.store-retention-days:30}")
    private int retentionDays;

    @Value("${dbagent.monitor.kill-audit-retention-days:365}")
    private int killAuditRetentionDays;

    public MonitorStoreService(@Qualifier("metricsJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS mon_sqlstat_delta (" +
                "db_id VARCHAR(64) NOT NULL, collect_ts BIGINT NOT NULL, sql_id VARCHAR(13) NOT NULL, " +
                "plan_hash_value BIGINT NOT NULL, executions_d BIGINT, elapsed_us_d BIGINT, cpu_us_d BIGINT, " +
                "buffer_gets_d BIGINT, disk_reads_d BIGINT, rows_d BIGINT, sql_text VARCHAR(1000), " +
                "PRIMARY KEY (db_id, collect_ts, sql_id, plan_hash_value))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_mon_sqlstat_delta_sql ON mon_sqlstat_delta (db_id, sql_id, collect_ts)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS mon_lock_sample (" +
                "db_id VARCHAR(64) NOT NULL, collect_ts BIGINT NOT NULL, tx_wait_max INTEGER, tm_wait_max INTEGER, " +
                "tm_holder_over_max INTEGER, incident_yn VARCHAR(1), " +
                "PRIMARY KEY (db_id, collect_ts))");

        jdbc.execute("CREATE TABLE IF NOT EXISTS mon_kill_audit (" +
                AUDIT_ID_DDL + ", " +
                "db_id VARCHAR(64) NOT NULL, kill_ts BIGINT NOT NULL, executed_by VARCHAR(64) NOT NULL, " +
                "sid BIGINT NOT NULL, serial_no BIGINT NOT NULL, instance_name VARCHAR(64), username VARCHAR(128), " +
                "program VARCHAR(128), machine VARCHAR(128), lock_object VARCHAR(261), last_call_et BIGINT, " +
                "reason VARCHAR(100), kill_result VARCHAR(20), err_msg VARCHAR(300))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_mon_kill_audit_ts ON mon_kill_audit (db_id, kill_ts)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS mon_check_result (" +
                "db_id VARCHAR(64) NOT NULL, check_ts BIGINT NOT NULL, check_type VARCHAR(30) NOT NULL, " +
                "target_name VARCHAR(261) NOT NULL, severity VARCHAR(10) NOT NULL, metric_value DOUBLE, " +
                "threshold DOUBLE, detail_json VARCHAR(4000), ack_by VARCHAR(64), ack_ts BIGINT, " +
                "PRIMARY KEY (db_id, check_ts, check_type, target_name))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_mon_check_result_type ON mon_check_result (db_id, check_type, check_ts)");
        // '통계 오래됨' 점검 제거(2026-09-26) - 남은 결과가 종류별 최신 행으로 계속 카드에 뜨지 않게 지운다
        jdbc.update("DELETE FROM mon_check_result WHERE check_type = 'STALE_STATS'");

        jdbc.execute("CREATE TABLE IF NOT EXISTS mon_segment_size (" +
                "db_id VARCHAR(64) NOT NULL, snap_date BIGINT NOT NULL, owner VARCHAR(128) NOT NULL, " +
                "segment_name VARCHAR(128) NOT NULL, size_bytes BIGINT NOT NULL, " +
                "PRIMARY KEY (db_id, snap_date, owner, segment_name))");
    }

    // ------------------------------------------------------------------ 저장소별 문법 (H2 1.4.200)

    private static final String AUDIT_ID_DDL = "audit_id BIGINT AUTO_INCREMENT PRIMARY KEY";

    private static String upsert(String table, String columns, String keyColumns, int paramCount) {
        StringBuilder q = new StringBuilder("MERGE INTO ").append(table).append(" (").append(columns)
                .append(") KEY (").append(keyColumns).append(") VALUES (");
        for (int i = 0; i < paramCount; i++) q.append(i == 0 ? "?" : ", ?");
        return q.append(")").toString();
    }

    private int deleteChunk(String table, String tsColumn, long cutoff) {
        return jdbc.update("DELETE FROM " + table + " WHERE " + tsColumn + " < ? LIMIT " + DELETE_CHUNK_ROWS, cutoff);
    }

    // ------------------------------------------------------------------ 쓰기

    /** 행: {dbId, collectTs, sqlId, planHashValue, executionsD, elapsedUsD, cpuUsD, bufferGetsD, diskReadsD, rowsD, sqlText} */
    public void saveSqlstatDeltas(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) return;
        jdbc.batchUpdate(upsert("mon_sqlstat_delta",
                "db_id, collect_ts, sql_id, plan_hash_value, executions_d, elapsed_us_d, cpu_us_d, buffer_gets_d, disk_reads_d, rows_d, sql_text",
                "db_id, collect_ts, sql_id, plan_hash_value", 11), rows);
    }

    public void saveLockSample(String dbId, long collectTs, int txWaitMax, int tmWaitMax, int tmHolderOverMax, boolean incident) {
        jdbc.update(upsert("mon_lock_sample", "db_id, collect_ts, tx_wait_max, tm_wait_max, tm_holder_over_max, incident_yn",
                        "db_id, collect_ts", 6),
                dbId, collectTs, txWaitMax, tmWaitMax, tmHolderOverMax, incident ? "Y" : "N");
    }

    /** KILL 결과 한 건 - 성공·건너뜀·실패 모두 남긴다(설계 ③-1). */
    public void saveKillAudit(String dbId, long killTs, String executedBy, long sid, long serial, String instanceName,
                              String username, String program, String machine, String lockObject, Long lastCallEt,
                              String reason, String result, String errMsg) {
        jdbc.update("INSERT INTO mon_kill_audit (db_id, kill_ts, executed_by, sid, serial_no, instance_name, username, " +
                        "program, machine, lock_object, last_call_et, reason, kill_result, err_msg) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                dbId, killTs, executedBy, sid, serial, instanceName, username, program, machine, lockObject,
                lastCallEt, reason, result, truncate(errMsg, 300));
    }

    /** 행: {dbId, checkTs, checkType, targetName, severity, metricValue, threshold, detailJson} */
    public void saveCheckResults(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) return;
        jdbc.batchUpdate(upsert("mon_check_result",
                "db_id, check_ts, check_type, target_name, severity, metric_value, threshold, detail_json",
                "db_id, check_ts, check_type, target_name", 8), rows);
    }

    /** 행: {dbId, snapDate, owner, segmentName, sizeBytes} */
    public void saveSegmentSizes(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) return;
        jdbc.batchUpdate(upsert("mon_segment_size", "db_id, snap_date, owner, segment_name, size_bytes",
                "db_id, snap_date, owner, segment_name", 5), rows);
    }

    // ------------------------------------------------------------------ 읽기

    /** SQL 상세(설계 6.2) - 구간(앱 시각 ms)의 실행 횟수·평균 수행 시간·평균 Buffer Gets·DB Time. 기록이 없으면 samples=0. */
    public java.util.Map<String, Object> sqlstatSummary(String dbId, String sqlId, long fromMs, long toMs) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) AS samples, SUM(executions_d) AS execs, SUM(elapsed_us_d) AS ela_us, " +
                        "SUM(buffer_gets_d) AS gets, MAX(plan_hash_value) AS phv " +
                        "FROM mon_sqlstat_delta WHERE db_id = ? AND sql_id = ? AND collect_ts BETWEEN ? AND ?",
                (rs, n) -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    long samples = rs.getLong("samples");
                    long execs = rs.getLong("execs");
                    long ela = rs.getLong("ela_us");
                    long gets = rs.getLong("gets");
                    m.put("samples", samples);
                    m.put("executions", samples == 0 ? null : execs);
                    m.put("elapsedMsPerExec", execs > 0 ? Math.round(ela / 1000.0 / execs * 100.0) / 100.0 : null);
                    m.put("bufferGetsPerExec", execs > 0 ? Math.round((double) gets / execs * 10.0) / 10.0 : null);
                    m.put("dbTimeSec", samples == 0 ? null : Math.round(ela / 1e6 * 100.0) / 100.0);
                    Object phv = rs.getObject("phv");
                    m.put("planHashValue", phv == null ? null : ((Number) phv).longValue());
                    return m;
                },
                dbId, sqlId, fromMs, toMs);
    }

    /** ⑧ 화면: 항목 종류별 최신 점검 결과(OK·ERROR 포함) - 10분·1일 항목의 check_ts가 달라 종류별 MAX를 쓴다. */
    public List<java.util.Map<String, Object>> latestChecks(String dbId) {
        return jdbc.query(
                "SELECT r.check_type, r.target_name, r.severity, r.metric_value, r.threshold, r.detail_json, r.check_ts " +
                        "FROM mon_check_result r JOIN (SELECT check_type, MAX(check_ts) AS last_ts FROM mon_check_result " +
                        "WHERE db_id = ? GROUP BY check_type) x ON x.check_type = r.check_type AND x.last_ts = r.check_ts " +
                        "WHERE r.db_id = ? " +
                        "ORDER BY CASE r.severity WHEN 'CRIT' THEN 0 WHEN 'WARN' THEN 1 WHEN 'INFO' THEN 2 WHEN 'ERROR' THEN 3 ELSE 4 END, r.metric_value DESC",
                (rs, n) -> checkRow(rs), dbId, dbId);
    }

    /** 6.4 상세: 해당 대상의 최근 결과 행(없으면 null). */
    public java.util.Map<String, Object> latestCheck(String dbId, String checkType, String targetName) {
        List<java.util.Map<String, Object>> rows = jdbc.query(
                "SELECT check_type, target_name, severity, metric_value, threshold, detail_json, check_ts FROM mon_check_result " +
                        "WHERE db_id = ? AND check_type = ? AND target_name = ? ORDER BY check_ts DESC LIMIT 1",
                (rs, n) -> checkRow(rs), dbId, checkType, targetName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 6.4 7일 추이: 대상의 점검 값 {ts, value} (일별 최댓값 계산은 화면/서비스에서). */
    public List<java.util.Map<String, Object>> checkHistory(String dbId, String checkType, String targetName, long fromMs) {
        return jdbc.query(
                "SELECT check_ts, metric_value FROM mon_check_result WHERE db_id = ? AND check_type = ? AND target_name = ? " +
                        "AND check_ts >= ? AND metric_value IS NOT NULL ORDER BY check_ts",
                (rs, n) -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("ts", rs.getLong(1));
                    m.put("value", rs.getDouble(2));
                    return m;
                }, dbId, checkType, targetName, fromMs);
    }

    /** 6.4 테이블 7일 추이: 세그먼트 크기 일 스냅샷. */
    public List<java.util.Map<String, Object>> segmentHistory(String dbId, String owner, String segmentName, long fromMs) {
        return jdbc.query(
                "SELECT snap_date, size_bytes FROM mon_segment_size WHERE db_id = ? AND owner = ? AND segment_name = ? " +
                        "AND snap_date >= ? ORDER BY snap_date",
                (rs, n) -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("ts", rs.getLong(1));
                    m.put("value", Math.round(rs.getLong(2) / Math.pow(1024, 3) * 10) / 10.0);
                    return m;
                }, dbId, owner, segmentName, fromMs);
    }

    private static java.util.Map<String, Object> checkRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("checkType", rs.getString("check_type"));
        m.put("targetName", rs.getString("target_name"));
        m.put("severity", rs.getString("severity"));
        Object v = rs.getObject("metric_value");
        m.put("value", v == null ? null : ((Number) v).doubleValue());
        Object t = rs.getObject("threshold");
        m.put("threshold", t == null ? null : ((Number) t).doubleValue());
        m.put("detail", rs.getString("detail_json"));
        m.put("checkTs", rs.getLong("check_ts"));
        return m;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    // ------------------------------------------------------------------ 보관 주기

    /** 1분·10분·일 테이블은 retentionDays(기본 30일), 감사 로그는 1년 - instance_metric_history 정리(03:45) 뒤에 돈다. */
    @Scheduled(cron = "0 50 3 * * *")
    void cleanupOldRows() {
        long now = System.currentTimeMillis();
        long cutoff = now - TimeUnit.DAYS.toMillis(retentionDays);
        purge("mon_sqlstat_delta", "collect_ts", cutoff);
        purge("mon_lock_sample", "collect_ts", cutoff);
        purge("mon_check_result", "check_ts", cutoff);
        purge("mon_segment_size", "snap_date", cutoff);
        purge("mon_kill_audit", "kill_ts", now - TimeUnit.DAYS.toMillis(killAuditRetentionDays));
    }

    /** 분할 삭제 - 덩어리 사이에 잠깐 쉬어 다른 쓰기(샘플러)가 끼어들 수 있게 한다. */
    int purge(String table, String tsColumn, long cutoff) {
        int total = 0;
        try {
            while (true) {
                int n = deleteChunk(table, tsColumn, cutoff);
                total += n;
                if (n < DELETE_CHUNK_ROWS) break;
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Cleanup of {} stopped after {} row(s): {}", table, total, e.toString());
        }
        if (total > 0) {
            log.info("Cleaned up {} row(s) from {} older than cutoff", total, table);
        }
        return total;
    }
}
