package com.dbagent.monitor;

import com.dbagent.settings.SettingKey;
import com.dbagent.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
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

    // 환경설정 DB화 P3-2(2026-10-04): 이 클래스가 쓰던 @Value 설정을 SettingsService 조회로 옮겼다(저장 즉시 반영).
    @Autowired
    private SettingsService settings;

    private static final Logger log = LoggerFactory.getLogger(MonitorStoreService.class);

    /** 분할 삭제 한 번에 지우는 행 수 - 한 번에 다 지우면 긴 쓰기 락으로 60초 샘플러 쓰기가 막힌다. */
    private static final int DELETE_CHUNK_ROWS = 5000;

    private final JdbcTemplate jdbc;



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
        // 보조 인덱스 (db_id, sql_id, collect_ts)는 AIX(H2)에서만 만들지 않는다 (2026-10-11 오케스트레이터 결정).
        // 이유: H2 MVStore는 1분마다 쌓고 매일 지우는 이 테이블의 인덱스 때문에 파일이 크게 부풀었다.
        // (로컬 실측: 보관 3일·7일 운영 시뮬레이션에서 인덱스 있음 8,284MB / 없음 885MB, 논리 크기 191MB.
        //  삭제 없이 3일치만 쌓아도 2,591MB / 155MB.)
        // 대가: sql_id 한 건 조회(sqlstatPlanSums·sqlstatSummary, SQL 상세 패널)가 3일 구간에서 약 3ms → 370~400ms로 느려진다.
        // 나머지 읽기 쿼리는 기본키 (db_id, collect_ts, ...) 범위 스캔이라 차이가 없었다. 메인(SQLite)은 인덱스를 유지한다.
        // 이미 인덱스가 만들어진 파일은 여기서 지운다 (파일 크기는 H2가 정상 종료할 때 MAX_COMPACT_TIME 안에서 줄어든다).
        jdbc.execute("DROP INDEX IF EXISTS idx_mon_sqlstat_delta_sql");
        // 성능 분석 Phase 3(2026-10-09): parse_calls 델타와 FORCE_MATCHING_SIGNATURE(부호 없는 64비트라 long에 안 들어가 문자열로 저장).
        // 이미 만들어진 테이블에는 컬럼이 없다 - SQLite·H2 모두 ADD COLUMN을 지원한다.
        for (String col : new String[]{"parse_calls_d BIGINT", "force_matching_signature VARCHAR(24)"}) {
            // H2는 IF NOT EXISTS를 지원한다 - 컬럼 유무를 조회로 확인하면 예외 스택이 metrics.trace.db에 남는다(QA D12)
            jdbc.execute("ALTER TABLE mon_sqlstat_delta ADD COLUMN IF NOT EXISTS " + col);
        }

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

        // ⑧ 점검 알림 숨김(운영자 확인 처리, 2026-09-29) - 대상별 1행. fingerprint는 숨긴 당시 상태(잡 실패는 마지막 실패 시각,
        // 그 밖은 심각도)라 상태가 바뀌면(다시 실패·등급 상승) 숨김이 풀린 것으로 본다. 되돌리기는 행 삭제.
        jdbc.execute("CREATE TABLE IF NOT EXISTS mon_check_ack (" +
                "db_id VARCHAR(64) NOT NULL, check_type VARCHAR(30) NOT NULL, target_name VARCHAR(261) NOT NULL, " +
                "fingerprint VARCHAR(200) NOT NULL, ack_by VARCHAR(64) NOT NULL, ack_ts BIGINT NOT NULL, " +
                "PRIMARY KEY (db_id, check_type, target_name))");

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

    /**
     * 행: {dbId, collectTs, sqlId, planHashValue, executionsD, elapsedUsD, cpuUsD, bufferGetsD, diskReadsD, rowsD, parseCallsD, fms}.
     * sql_text 컬럼은 기존 파일 호환으로 남겨 두지만 쓰지 않는다(NULL) - SqlStatDeltaCollector 주석 참고(2026-10-02).
     */
    public void saveSqlstatDeltas(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) return;
        jdbc.batchUpdate(upsert("mon_sqlstat_delta",
                "db_id, collect_ts, sql_id, plan_hash_value, executions_d, elapsed_us_d, cpu_us_d, buffer_gets_d, disk_reads_d, rows_d, " +
                        "parse_calls_d, force_matching_signature",
                "db_id, collect_ts, sql_id, plan_hash_value", 12), rows);
    }

    // ------------------------------------------------------------------ SQL 분석 읽기 (성능 분석 Phase 3, 2026-10-09)
    // 시각은 앱 epoch ms(collect_ts). 호출자(PerfSqlService)가 DB 시각 ↔ 앱 시각을 바꾼다.

    /** 구간의 SQL_ID별 합계: {sql_id, executions, elapsed_us, cpu_us, buffer_gets, rows, parse_calls, fms, plan_count, last_plan_hash}. */
    public List<Object[]> sqlstatAggregate(String dbId, long fromMs, long toMs) {
        return jdbc.query("SELECT sql_id, SUM(executions_d), SUM(elapsed_us_d), SUM(cpu_us_d), SUM(buffer_gets_d), SUM(rows_d), " +
                        "SUM(parse_calls_d), MAX(force_matching_signature), COUNT(DISTINCT plan_hash_value), MAX(plan_hash_value) " +
                        "FROM mon_sqlstat_delta WHERE db_id = ? AND collect_ts >= ? AND collect_ts < ? GROUP BY sql_id",
                (rs, n) -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
                        rs.getLong(7), rs.getString(8), rs.getInt(9), rs.getLong(10)}, dbId, fromMs, toMs);
    }

    /** 구간의 (collect_ts, sql_id)별 실행 수·Elapsed: {collect_ts, sql_id, executions, elapsed_us}. sqlIds는 호출자가 200개 이하로 준다. */
    public List<Object[]> sqlstatSeries(String dbId, java.util.Collection<String> sqlIds, long fromMs, long toMs) {
        if (sqlIds.isEmpty()) return new java.util.ArrayList<>();
        StringBuilder in = new StringBuilder();
        List<Object> b = new java.util.ArrayList<>();
        b.add(dbId);
        b.add(fromMs);
        b.add(toMs);
        for (String id : sqlIds) {
            in.append(in.length() == 0 ? "?" : ", ?");
            b.add(id);
        }
        return jdbc.query("SELECT collect_ts, sql_id, SUM(executions_d), SUM(elapsed_us_d) FROM mon_sqlstat_delta " +
                        "WHERE db_id = ? AND collect_ts >= ? AND collect_ts < ? AND sql_id IN (" + in + ") GROUP BY collect_ts, sql_id",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4)}, b.toArray());
    }

    /** 한 SQL의 plan_hash별 합계(첫·마지막 시각 포함): {plan_hash, min_ts, max_ts, executions, elapsed_us, buffer_gets}. */
    public List<Object[]> sqlstatPlanSums(String dbId, String sqlId, long fromMs, long toMs) {
        return jdbc.query("SELECT plan_hash_value, MIN(collect_ts), MAX(collect_ts), SUM(executions_d), SUM(elapsed_us_d), SUM(buffer_gets_d) " +
                        "FROM mon_sqlstat_delta WHERE db_id = ? AND sql_id = ? AND collect_ts >= ? AND collect_ts < ? GROUP BY plan_hash_value",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)},
                dbId, sqlId, fromMs, toMs);
    }

    /** 구간에서 plan_hash가 둘 이상 나타난 SQL_ID(Plan 변경 후보). */
    public List<String> sqlIdsWithMultiplePlans(String dbId, long fromMs, long toMs) {
        return jdbc.queryForList("SELECT sql_id FROM mon_sqlstat_delta WHERE db_id = ? AND collect_ts >= ? AND collect_ts < ? " +
                "GROUP BY sql_id HAVING COUNT(DISTINCT plan_hash_value) > 1", String.class, dbId, fromMs, toMs);
    }

    /** 시간 롤업용 (sql_id, plan_hash)별 합계: {sql_id, plan_hash, executions, elapsed_us, cpu_us, buffer_gets, disk_reads, rows, parse_calls, fms}. */
    public List<Object[]> sqlstatHourRows(String dbId, long fromMs, long toMs) {
        return jdbc.query("SELECT sql_id, plan_hash_value, SUM(executions_d), SUM(elapsed_us_d), SUM(cpu_us_d), SUM(buffer_gets_d), " +
                        "SUM(disk_reads_d), SUM(rows_d), SUM(parse_calls_d), MAX(force_matching_signature) FROM mon_sqlstat_delta " +
                        "WHERE db_id = ? AND collect_ts >= ? AND collect_ts < ? GROUP BY sql_id, plan_hash_value",
                (rs, n) -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
                        rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getString(10)}, dbId, fromMs, toMs);
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

    /** ⑧ 숨김 저장(같은 대상이면 덮어씀). */
    public void saveCheckAck(String dbId, String checkType, String targetName, String fingerprint, String ackBy, long ackTs) {
        jdbc.update(upsert("mon_check_ack", "db_id, check_type, target_name, fingerprint, ack_by, ack_ts",
                        "db_id, check_type, target_name", 6),
                dbId, checkType, targetName, truncate(fingerprint, 200), truncate(ackBy, 64), ackTs);
    }

    /** ⑧ 숨김 되돌리기 - 지운 행 수. */
    public int deleteCheckAck(String dbId, String checkType, String targetName) {
        return jdbc.update("DELETE FROM mon_check_ack WHERE db_id = ? AND check_type = ? AND target_name = ?",
                dbId, checkType, targetName);
    }

    /** ⑧ DB의 숨김 목록 {checkType, targetName, fingerprint, ackBy, ackTs}. */
    public List<java.util.Map<String, Object>> checkAcks(String dbId) {
        return jdbc.query("SELECT check_type, target_name, fingerprint, ack_by, ack_ts FROM mon_check_ack WHERE db_id = ?",
                (rs, n) -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("checkType", rs.getString(1));
                    m.put("targetName", rs.getString(2));
                    m.put("fingerprint", rs.getString(3));
                    m.put("ackBy", rs.getString(4));
                    m.put("ackTs", rs.getLong(5));
                    return m;
                }, dbId);
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
        long cutoff = now - TimeUnit.DAYS.toMillis(settings.getInt(SettingKey.STORE_RETENTION_DAYS));
        // SQL 1분 델타는 새 커서 행이 늘어 따로 짧게 둔다(기본 7일) - store-retention-days가 더 짧으면 그 값(2026-10-09 쿼리 검토)
        long deltaDays = Math.min(settings.getInt(SettingKey.STORE_RETENTION_DAYS), settings.getInt(SettingKey.SQLSTAT_DELTA_RETENTION_DAYS));
        purge("mon_sqlstat_delta", "collect_ts", now - TimeUnit.DAYS.toMillis(deltaDays));
        purge("mon_lock_sample", "collect_ts", cutoff);
        purge("mon_check_result", "check_ts", cutoff);
        purge("mon_segment_size", "snap_date", cutoff);
        // 숨김도 보관 기간이 지나면 지운다 - 같은 상태로 한 달 넘게 남은 알림은 한 번 다시 보여 주는 편이 안전하다
        purge("mon_check_ack", "ack_ts", cutoff);
        purge("mon_kill_audit", "kill_ts", now - TimeUnit.DAYS.toMillis(settings.getInt(SettingKey.KILL_AUDIT_RETENTION_DAYS)));
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
