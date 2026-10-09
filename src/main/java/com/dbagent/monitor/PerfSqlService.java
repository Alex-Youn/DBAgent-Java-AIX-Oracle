package com.dbagent.monitor;

import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.settings.SettingKey;
import com.dbagent.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 성능 분석 "SQL 분석" 롤업·Plan 변경 감지·조회 (성능 분석 개편 Phase 3, 2026-10-09, 설계문서/성능분석 메뉴 설계.md 3장).
 *
 * <p>원본은 기존 1분 v$sqlstats 델타 수집이 쌓는 {@code mon_sqlstat_delta}(metrics DB, 앱 시각 ms)다 - 새 원본 조회는 없다(대상 DB 조회는
 * SQL 상세 패널을 열 때 SQL 전문 단건 조회뿐). 이 서비스는 perf DB에 세 가지를 둔다.
 * <ul>
 *   <li>perf_sql_hour - 시간 롤업(시간마다 Elapsed 상위 N + 실행 수 상위 N 합집합 + Plan 변경 SQL, 기본 90일). 1분 델타는 매일 03:50에
 *       store-retention-days(폐쇄망 3일)를 넘긴 것을 지우므로 6시간을 넘는 구간·지난주 대비는 여기서 읽는다.</li>
 *   <li>perf_sql_literal_hour - 리터럴 SQL(같은 FORCE_MATCHING_SIGNATURE의 새 SQL_ID) 시간별 집계. 수집기가 처음 보는 커서를 셀 때마다 더한다.</li>
 *   <li>perf_sql_plan_change - Plan 변경 기록(최근 24시간, 변경 전후 각 30회 이상 실행, 변경 후 ms/exec가 2배 이상).</li>
 * </ul>
 * 시각은 DB 시각(분 키)이며 앱 시각(ms)과는 샘플러가 재는 DB 시계 차이로 바꾼다. SQLite(main)·H2 1.4.200(AIX) 공통 SQL만 쓴다.
 * 계정·서버 필터는 적용하지 않는다(v$sqlstats에는 계정·서버가 없다).
 */
@Service
public class PerfSqlService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(PerfSqlService.class);

    /** 이 분 수 이하의 구간은 1분 델타를, 넘으면 시간 롤업을 읽는다(설계 3-1). */
    private static final int DELTA_MAX_MINUTES = 360;
    private static final int MAX_RANGE_MINUTES = 24 * 60;
    private static final long WEEK_MS = 7L * 86400 * 1000;
    private static final int LITERAL_TOP_PER_CYCLE = 50;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int PLAN_MIN_EXECS = 30;
    private static final double PLAN_WORSEN_RATIO = 2.0;
    private static final int PLAN_CANDIDATE_CAP = 500;
    /** 롤업 보충 최대 시간 - 1분 델타 보존 일수(기본 7일, 최대 7일)만큼. 앱이 오래 꺼져 있던 경우의 롤업 누락을 줄인다. */
    private static final int ROLLUP_CATCHUP_MAX_HOURS = 7 * 24;
    /** 롤업·Plan 변경 배치 전용 스레드 - 공용 @Scheduled 스레드(2개)를 DB 수 × 수 초 동안 잡지 않게(쿼리 검토). */
    private static final java.util.concurrent.ExecutorService JOB_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "perf-sql-hourly");
        t.setDaemon(true);
        return t;
    });
    /** v$sqlstats에 없던 sql_id를 다시 묻지 않는 시간(ms). */
    private static final long TEXT_MISS_MS = 10L * 60 * 1000;
    private static final long TEXT_LOOKUP_DEADLINE_NS = 3L * 1000 * 1000 * 1000;
    private static final int TEXT_PREVIEW = 300;

    @Autowired
    private SettingsService settings;

    private final JdbcTemplate jdbc;
    private final boolean h2;
    private final TransactionTemplate tx;
    private final MonitorStoreService store;
    private final InstanceMetricSamplerService sampler;
    private final PerfStoreService perfStore;
    private final PerfLockService heartbeats;
    private final DatabaseConfigService configService;
    private final OracleConnectionPoolManager poolManager;

    public PerfSqlService(@Qualifier("perfJdbcTemplate") JdbcTemplate jdbc, MonitorStoreService store,
                          InstanceMetricSamplerService sampler, PerfStoreService perfStore, PerfLockService heartbeats,
                          DatabaseConfigService configService, OracleConnectionPoolManager poolManager) {
        this.jdbc = jdbc;
        this.store = store;
        this.sampler = sampler;
        this.perfStore = perfStore;
        this.heartbeats = heartbeats;
        this.configService = configService;
        this.poolManager = poolManager;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        String url = jdbc.getDataSource() instanceof com.zaxxer.hikari.HikariDataSource
                ? ((com.zaxxer.hikari.HikariDataSource) jdbc.getDataSource()).getJdbcUrl() : null;
        this.h2 = url != null && url.startsWith("jdbc:h2:");
    }

    @Override
    public void afterPropertiesSet() {
        String tail = h2 ? ")" : ") WITHOUT ROWID";
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_sql_hour (" +
                "db_id VARCHAR(64) NOT NULL, hour_key BIGINT NOT NULL, sql_id VARCHAR(13) NOT NULL, plan_hash_value BIGINT NOT NULL, " +
                "fms VARCHAR(24), executions BIGINT NOT NULL, elapsed_us BIGINT NOT NULL, cpu_us BIGINT NOT NULL, buffer_gets BIGINT NOT NULL, " +
                "disk_reads BIGINT NOT NULL, rows_proc BIGINT NOT NULL, parse_calls BIGINT NOT NULL, " +
                "PRIMARY KEY (db_id, hour_key, sql_id, plan_hash_value)" + tail);
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_sql_literal_hour (" +
                "db_id VARCHAR(64) NOT NULL, hour_key BIGINT NOT NULL, fms VARCHAR(24) NOT NULL, sql_id_cnt BIGINT NOT NULL, " +
                "execs BIGINT NOT NULL, parse_calls BIGINT NOT NULL, elapsed_us BIGINT NOT NULL, sample_sql_id VARCHAR(13), " +
                "PRIMARY KEY (db_id, hour_key, fms)" + tail);
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_sql_plan_change (" +
                "db_id VARCHAR(64) NOT NULL, sql_id VARCHAR(13) NOT NULL, changed_key BIGINT NOT NULL, old_plan_hash BIGINT NOT NULL, " +
                "new_plan_hash BIGINT NOT NULL, old_ms_per_exec DOUBLE NOT NULL, new_ms_per_exec DOUBLE NOT NULL, " +
                "old_execs BIGINT NOT NULL, new_execs BIGINT NOT NULL, PRIMARY KEY (db_id, sql_id, changed_key)" + tail);
    }

    // ------------------------------------------------------------------ 시각 변환

    private long offsetMs(String dbId) {
        Long o = sampler.getDbClockOffsetMs(dbId);
        return o == null ? 0L : o;
    }

    /** DB 시각 → 앱 epoch ms(collect_ts 기준). */
    private long toAppMs(LocalDateTime dbTime, long off) {
        return dbTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - off;
    }

    /** 앱 epoch ms → DB 시각 분 키. */
    private long minuteKeyOf(long appMs, long off) {
        return PerfStoreService.minuteKey(LocalDateTime.ofInstant(Instant.ofEpochMilli(appMs + off), ZoneId.systemDefault()));
    }

    private static long hourFloor(long minuteKey) {
        return Math.floorDiv(minuteKey, 60L) * 60L;
    }

    private static String iso(long minuteKey) {
        return LocalDateTime.ofEpochSecond(minuteKey * 60L, 0, ZoneOffset.UTC).toString();
    }

    private static double round(double v, int digits) {
        double p = Math.pow(10, digits);
        return Math.round(v * p) / p;
    }

    // ------------------------------------------------------------------ 리터럴 집계 저장(수집기가 호출)

    /**
     * 사이클에서 처음 본 커서를 FORCE_MATCHING_SIGNATURE별로 센 값을 시간 집계에 더한다. literal: fms → {SQL_ID 수, 실행 수, parse 호출,
     * elapsed_us, 대표 sql_id}. 사이클당 SQL_ID 수 상위 {@value #LITERAL_TOP_PER_CYCLE}개만 저장한다(저장 행 상한).
     */
    public void saveLiteral(String dbId, long appMs, Map<String, Object[]> literal) {
        if (literal == null || literal.isEmpty()) return;
        final long hour = hourFloor(minuteKeyOf(appMs, offsetMs(dbId)));
        List<Map.Entry<String, Object[]>> top = new ArrayList<Map.Entry<String, Object[]>>(literal.entrySet());
        Collections.sort(top, new Comparator<Map.Entry<String, Object[]>>() {
            @Override
            public int compare(Map.Entry<String, Object[]> a, Map.Entry<String, Object[]> b) {
                return Long.compare((Long) b.getValue()[0], (Long) a.getValue()[0]);
            }
        });
        final List<Map.Entry<String, Object[]>> use = top.subList(0, Math.min(LITERAL_TOP_PER_CYCLE, top.size()));
        tx.execute(status -> {
            for (Map.Entry<String, Object[]> e : use) {
                Object[] v = e.getValue();
                int n = jdbc.update("UPDATE perf_sql_literal_hour SET sql_id_cnt = sql_id_cnt + ?, execs = execs + ?, parse_calls = parse_calls + ?, " +
                        "elapsed_us = elapsed_us + ? WHERE db_id = ? AND hour_key = ? AND fms = ?", v[0], v[1], v[2], v[3], dbId, hour, e.getKey());
                if (n == 0) {
                    jdbc.update("INSERT INTO perf_sql_literal_hour (db_id, hour_key, fms, sql_id_cnt, execs, parse_calls, elapsed_us, sample_sql_id) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", dbId, hour, e.getKey(), v[0], v[1], v[2], v[3], v[4]);
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ 시간 롤업·Plan 변경 감지 (시간 주기 배치)

    /** 매시 7분: 지난 시간의 롤업을 만들고(없는 최근 48시간분 보충) Plan 변경을 감지한다. 전용 스레드가 아니라 짧은 작업이라 @Scheduled에서 직접 돈다. */
    @Scheduled(cron = "0 7 * * * *")
    void hourlyJob() {
        JOB_EXECUTOR.execute(this::hourlyJobBody);
    }

    void hourlyJobBody() {
        for (TargetDbConfig t : configService.listAllInstances()) {
            if (!"oracle".equalsIgnoreCase(t.dbType())) continue;
            try {
                rollupHours(t.id());
            } catch (Exception e) {
                log.warn("perf sql rollup failed for db_id={}: {}", t.id(), e.toString());
            }
            try {
                detectPlanChanges(t.id());
            } catch (Exception e) {
                log.warn("perf sql plan change detection failed for db_id={}: {}", t.id(), e.toString());
            }
        }
    }

    /** 완료된 시간 중 롤업이 없는 것(최근 1분 델타 보존 일수만큼, 최대 7일)을 만든다. 반환: 만든 시간 수. */
    int rollupHours(String dbId) {
        if (sampler.getDbClockOffsetMs(dbId) == null) return 0; // 아직 DB 시각을 모르면 시간 경계를 못 맞춘다
        long off = offsetMs(dbId);
        long nowKey = minuteKeyOf(System.currentTimeMillis(), off);
        long lastDone = hourFloor(nowKey) - 60;
        int catchupHours = (int) Math.min(ROLLUP_CATCHUP_MAX_HOURS, 24L * settings.getInt(SettingKey.SQLSTAT_DELTA_RETENTION_DAYS));
        long firstWanted = lastDone - 60L * (catchupHours - 1);
        java.util.Set<Long> have = heartbeats.heartbeatMinutes(dbId, "sqlhour", firstWanted, lastDone + 1);
        int made = 0;
        for (long h = firstWanted; h <= lastDone; h += 60) {
            if (have.contains(h)) continue;
            long fromApp = toAppMs(PerfStoreService.fromMinuteKey(h), off);
            long toApp = toAppMs(PerfStoreService.fromMinuteKey(h + 60), off);
            List<Object[]> rows = store.sqlstatHourRows(dbId, fromApp, toApp);
            writeHour(dbId, h, rows);
            made++;
        }
        // 리터럴 시간 집계: 끝난 시간에서 SQL_ID가 하나뿐인 시그니처는 리터럴이 아니므로 지운다(FMS≠0인 새 커서가 모두 쌓여 무한히 커지는 것을 막는다)
        jdbc.update("DELETE FROM perf_sql_literal_hour WHERE db_id = ? AND hour_key <= ? AND sql_id_cnt < 2", dbId, lastDone);
        return made;
    }

    private void writeHour(final String dbId, final long hourKey, List<Object[]> rows) {
        final boolean rowsEmpty = rows == null || rows.isEmpty();
        final int topN = settings.getInt(SettingKey.SQL_ROLLUP_TOP_N);
        // sql_id별 합계로 Elapsed 상위·실행 수 상위를 고르고, plan_hash가 둘 이상인 SQL과 최근 Plan 변경 SQL은 항상 남긴다
        Map<String, long[]> sum = new HashMap<String, long[]>(); // {elapsed, execs}
        Map<String, Set<Long>> plans = new HashMap<String, Set<Long>>();
        for (Object[] r : rows) {
            String id = (String) r[0];
            long[] s = sum.get(id);
            if (s == null) { s = new long[2]; sum.put(id, s); }
            s[0] += (Long) r[3];
            s[1] += (Long) r[2];
            Set<Long> p = plans.get(id);
            if (p == null) { p = new HashSet<Long>(); plans.put(id, p); }
            p.add((Long) r[1]);
        }
        Set<String> keep = new HashSet<String>();
        List<String> ids = new ArrayList<String>(sum.keySet());
        final Map<String, long[]> sumF = sum;
        Collections.sort(ids, new Comparator<String>() {
            @Override
            public int compare(String a, String b) { return Long.compare(sumF.get(b)[0], sumF.get(a)[0]); }
        });
        for (int i = 0; i < Math.min(topN, ids.size()); i++) keep.add(ids.get(i));
        Collections.sort(ids, new Comparator<String>() {
            @Override
            public int compare(String a, String b) { return Long.compare(sumF.get(b)[1], sumF.get(a)[1]); }
        });
        for (int i = 0; i < Math.min(topN, ids.size()); i++) keep.add(ids.get(i));
        for (Map.Entry<String, Set<Long>> e : plans.entrySet()) if (e.getValue().size() > 1) keep.add(e.getKey());
        List<String> changed = jdbc.queryForList("SELECT DISTINCT sql_id FROM perf_sql_plan_change WHERE db_id = ? AND changed_key >= ?",
                String.class, dbId, hourKey - 24 * 60);
        keep.addAll(changed);
        final List<Object[]> out = new ArrayList<Object[]>();
        for (Object[] r : rows) {
            if (keep.contains((String) r[0])) {
                out.add(new Object[]{dbId, hourKey, r[0], r[1], r[9], r[2], r[3], r[4], r[5], r[6], r[7], r[8]});
            }
        }
        tx.execute(status -> {
            jdbc.update("DELETE FROM perf_sql_hour WHERE db_id = ? AND hour_key = ?", dbId, hourKey);
            if (!out.isEmpty()) {
                jdbc.batchUpdate("INSERT INTO perf_sql_hour (db_id, hour_key, sql_id, plan_hash_value, fms, executions, elapsed_us, cpu_us, " +
                        "buffer_gets, disk_reads, rows_proc, parse_calls) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", out);
            }
            // 1분 델타가 없는 시간(앱이 꺼져 있던 시간)은 롤업이 있는 것으로 표시하지 않는다 - 지난주 대비의 "기준 있음"·롤업 시작 시각·
            // 초당 실행 수 분모가 빈 시간 때문에 틀어지지 않게(QA D7). 대신 그 시간은 다음 실행에서 다시 확인한다(빈 범위 조회라 가볍다).
            if (!rowsEmpty) heartbeats.saveHeartbeat(dbId, hourKey, "sqlhour");
            return null;
        });
    }

    /** 최근 24시간에 plan_hash가 바뀐 SQL 중 변경 전후 각 30회 이상 실행되고 변경 후 ms/exec가 2배 이상인 것을 기록한다(설계 3-4). */
    int detectPlanChanges(String dbId) {
        if (sampler.getDbClockOffsetMs(dbId) == null) return 0;
        long off = offsetMs(dbId);
        long nowApp = System.currentTimeMillis();
        long fromApp = nowApp - 24L * 3600 * 1000;
        List<String> cand = store.sqlIdsWithMultiplePlans(dbId, fromApp, nowApp);
        if (cand.size() > PLAN_CANDIDATE_CAP) cand = cand.subList(0, PLAN_CANDIDATE_CAP);
        int saved = 0;
        for (String id : cand) {
            List<Object[]> ps = store.sqlstatPlanSums(dbId, id, fromApp, nowApp);
            Collections.sort(ps, new Comparator<Object[]>() {
                @Override
                public int compare(Object[] a, Object[] b) { return Long.compare((Long) a[1], (Long) b[1]); }
            });
            for (int i = 1; i < ps.size(); i++) {
                Object[] o = ps.get(i - 1);
                Object[] n = ps.get(i);
                long oe = (Long) o[3];
                long ne = (Long) n[3];
                if (oe < PLAN_MIN_EXECS || ne < PLAN_MIN_EXECS) continue;
                double oms = (Long) o[4] / 1000.0 / oe;
                double nms = (Long) n[4] / 1000.0 / ne;
                if (oms <= 0 || nms < oms * PLAN_WORSEN_RATIO) continue;
                final long key = minuteKeyOf((Long) n[1], off);
                // (2026-10-09 점검 수정) 두 Plan을 번갈아 쓰는 SQL은 "새 Plan의 첫 시각"이 24시간 창의 시작에 붙어 매시간 1시간씩
                // 밀린다 - 같은 (이전→현재) 쌍이 직전 24시간 안에 이미 기록됐으면 새 행을 만들지 않는다(같은 키면 값만 갱신).
                Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM perf_sql_plan_change WHERE db_id = ? AND sql_id = ? AND old_plan_hash = ? " +
                        "AND new_plan_hash = ? AND changed_key >= ? AND changed_key < ?", Integer.class, dbId, id, o[0], n[0], key - 1440, key);
                if (dup != null && dup > 0) continue;
                final Object[] args = {dbId, id, key, o[0], n[0], oms, nms, oe, ne};
                tx.execute(status -> {
                    jdbc.update("DELETE FROM perf_sql_plan_change WHERE db_id = ? AND sql_id = ? AND changed_key = ?", args[0], args[1], args[2]);
                    jdbc.update("INSERT INTO perf_sql_plan_change (db_id, sql_id, changed_key, old_plan_hash, new_plan_hash, old_ms_per_exec, " +
                            "new_ms_per_exec, old_execs, new_execs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", args);
                    return null;
                });
                saved++;
            }
        }
        return saved;
    }

    // ------------------------------------------------------------------ 보관

    @Scheduled(cron = "0 45 4 * * *")
    void purge() {
        long nowKey = PerfStoreService.minuteKey(LocalDateTime.now(ZoneOffset.UTC));
        long cutoff = nowKey - 1440L * settings.getInt(SettingKey.SQL_ROLLUP_RETENTION_DAYS);
        int total = 0;
        List<String> dbIds = jdbc.queryForList("SELECT db_id FROM perf_sql_hour UNION SELECT db_id FROM perf_sql_literal_hour " +
                "UNION SELECT db_id FROM perf_sql_plan_change", String.class);
        String[][] tables = {{"perf_sql_hour", "hour_key"}, {"perf_sql_literal_hour", "hour_key"}, {"perf_sql_plan_change", "changed_key"}};
        for (String[] t : tables) {
            for (String id : dbIds) {
                List<Long> min = jdbc.queryForList("SELECT MIN(" + t[1] + ") FROM " + t[0] + " WHERE db_id = ?", Long.class, id);
                Long cur = min.isEmpty() ? null : min.get(0);
                // 기본키가 db_id부터라 DB별 범위로 지운다. 하루 단위로 앞에서부터 잘라 쓰기 잠금을 짧게 한다
                while (cur != null && cur < cutoff) {
                    long upto = Math.min(cur + 1440, cutoff);
                    total += jdbc.update("DELETE FROM " + t[0] + " WHERE db_id = ? AND " + t[1] + " >= ? AND " + t[1] + " < ?", id, cur, upto);
                    cur = upto;
                }
            }
        }
        // 롤업 하트비트('sqlhour')도 롤업과 같은 보존 기간으로 지운다 - PerfLockService.purge는 성능 분석 보존 기간(15일)으로
        // 하트비트를 지우는데 'sqlhour'는 제외한다(지워지면 15일 넘은 구간의 지난주 대비가 "기준 없음"이 되고 롤업 시작 시각이 틀어진다).
        total += jdbc.update("DELETE FROM perf_collect_min WHERE kind = 'sqlhour' AND minute_key < ?", cutoff);
        if (total > 0) log.info("perf sql purge: {} row(s) older than {} day(s)", total, settings.getInt(SettingKey.SQL_ROLLUP_RETENTION_DAYS));
    }

    // ------------------------------------------------------------------ 조회: 집계 행

    /** 구간 SQL_ID별 합계 한 행. */
    private static final class Agg {
        String sqlId;
        long execs, elaUs, cpuUs, gets, rows, parse;
        String fms;
        int planCount;
        long lastPhv;
    }

    private static String normFms(String f) {
        return f == null || f.isEmpty() || "0".equals(f) ? null : f;
    }

    /** 구간에서 읽을 원본 정보. */
    private static final class Src {
        boolean delta;      // true=1분 델타, false=시간 롤업
        long fromApp, toApp;
        long fromKey, toKey;   // 입력 구간 분 키(DB 시각)
        long fromHour, toHour; // 롤업이면 시간 키 구간 [fromHour, toHour)
        double seconds;
        Set<Long> rolled;      // 롤업이면 [fromHour, toHour) 중 롤업이 만들어진 시간(하트비트), 델타면 null
    }

    /** 1분 델타가 실제로 남는 일수 - MonitorStoreService.cleanupOldRows의 삭제 기준과 같다. */
    private long deltaRetentionDays() {
        return Math.min(settings.getInt(SettingKey.STORE_RETENTION_DAYS), settings.getInt(SettingKey.SQLSTAT_DELTA_RETENTION_DAYS));
    }

    private Src pickSource(String dbId, LocalDateTime from, LocalDateTime to) {
        long off = offsetMs(dbId);
        Src s = new Src();
        s.fromApp = toAppMs(from, off);
        s.toApp = toAppMs(to, off);
        s.seconds = Math.max(1, Duration.between(from, to).getSeconds());
        long minutes = Duration.between(from, to).toMinutes();
        long retentionMs = deltaRetentionDays() * 86400L * 1000;
        // 1분 델타는 min(store-retention-days, sqlstat-delta-retention-days)까지만 남는다 - 구간이 보관 한계에 걸치면(여유 1시간) 롤업을 쓴다(QA D1)
        s.delta = minutes <= DELTA_MAX_MINUTES && s.fromApp >= System.currentTimeMillis() - retentionMs + 3600_000L;
        long fk = PerfStoreService.minuteKey(from);
        long tk = PerfStoreService.minuteKey(to);
        s.fromKey = fk;
        s.toKey = tk;
        s.fromHour = hourFloor(fk);
        s.toHour = (tk % 60 == 0) ? tk : hourFloor(tk) + 60;
        if (!s.delta) {
            // (2026-10-09 점검 수정) 롤업은 시간 단위로 넓어지고 진행 중인 시간(매시 7분에 만든다)은 아직 없다 - 초당 실행 수(N+1·
            // 커서 재사용 X 배지)의 분모를 입력 구간이 아니라 실제로 롤업이 있는 시간 수로 잡는다(예: 10:30~17:30이면 데이터는 8시간치).
            s.rolled = heartbeats.heartbeatMinutes(dbId, "sqlhour", s.fromHour, s.toHour);
            if (!s.rolled.isEmpty()) s.seconds = s.rolled.size() * 3600.0;
        }
        return s;
    }

    private List<Agg> aggregate(String dbId, Src s) {
        List<Agg> out = new ArrayList<Agg>();
        if (s.delta) {
            for (Object[] r : store.sqlstatAggregate(dbId, s.fromApp, s.toApp)) {
                Agg a = new Agg();
                a.sqlId = (String) r[0];
                a.execs = (Long) r[1]; a.elaUs = (Long) r[2]; a.cpuUs = (Long) r[3]; a.gets = (Long) r[4]; a.rows = (Long) r[5]; a.parse = (Long) r[6];
                a.fms = normFms((String) r[7]);
                a.planCount = (Integer) r[8];
                a.lastPhv = (Long) r[9];
                out.add(a);
            }
        } else {
            jdbc.query("SELECT sql_id, SUM(executions), SUM(elapsed_us), SUM(cpu_us), SUM(buffer_gets), SUM(rows_proc), SUM(parse_calls), " +
                            "MAX(fms), COUNT(DISTINCT plan_hash_value), MAX(plan_hash_value) FROM perf_sql_hour " +
                            "WHERE db_id = ? AND hour_key >= ? AND hour_key < ? GROUP BY sql_id",
                    rs -> {
                        Agg a = new Agg();
                        a.sqlId = rs.getString(1);
                        a.execs = rs.getLong(2); a.elaUs = rs.getLong(3); a.cpuUs = rs.getLong(4); a.gets = rs.getLong(5); a.rows = rs.getLong(6); a.parse = rs.getLong(7);
                        a.fms = normFms(rs.getString(8));
                        a.planCount = rs.getInt(9);
                        a.lastPhv = rs.getLong(10);
                        out.add(a);
                    }, dbId, s.fromHour, s.toHour);
        }
        return out;
    }

    private static boolean isSelect(String text) {
        if (text == null) return false;
        // (2026-10-09 점검 수정) 여러 줄 블록 주석(?s)과 -- 줄 주석이 앞에 붙은 SELECT(MyBatis 매퍼 주석 등)도 SELECT로 본다
        String t = text.replaceAll("(?s)^(\\s|/\\*.*?\\*/|--[^\\n]*)+", "").trim().toUpperCase();
        return t.startsWith("SELECT") || t.startsWith("WITH");
    }

    private static String preview(String text) {
        if (text == null) return null;
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > TEXT_PREVIEW ? t.substring(0, TEXT_PREVIEW) : t;
    }

    private Map<String, Object> collectInfo(String dbId, Src s) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("source", s.delta ? "delta" : "hour");
        m.put("approx", !s.delta); // 롤업은 시간 단위라 구간 경계가 시간으로 넓어진다
        m.put("deltaRetentionDays", deltaRetentionDays());
        Long first = heartbeats.firstHeartbeat(dbId, "sqlhour");
        m.put("rollupFrom", first == null ? null : iso(first));
        return m;
    }

    // ------------------------------------------------------------------ SQL 텍스트 (저장된 앞부분 + 목록에 필요한 만큼만 대상 DB 단건 조회)

    /**
     * 목록에 보일 SQL 텍스트. 저장소(perf_sql_text, ASH에 잡힌 SQL)에 없는 것은 대상 DB의 v$sqlstats에서 한 번에(최대 50개, 바인드 IN,
     * 쿼리 제한 시간) 읽어 저장해 둔다 - 화면을 열 때만 나가는 조회이고 정기 수집은 아니다. 실패하면 없는 대로 둔다.
     */
    private final Map<String, Long> textMiss = new java.util.concurrent.ConcurrentHashMap<String, Long>();

    private Map<String, String> textsFor(TargetDbConfig target, List<String> ids) {
        String dbId = target.id();
        Map<String, String> texts = perfStore.sqlTexts(dbId, ids);
        long nowMs = System.currentTimeMillis();
        List<String> missing = new ArrayList<String>();
        for (String id : ids) {
            if (id == null || texts.containsKey(id) || missing.size() >= 50) continue;
            Long until = textMiss.get(dbId + "|" + id);
            if (until != null && until > nowMs) continue; // 공유 풀에서 밀려난 SQL은 10분 동안 다시 묻지 않는다
            missing.add(id);
        }
        if (missing.isEmpty()) return texts;
        // sql_id = ? 단건 조회는 v$sqlstats 고정 인덱스(X$KKSSQLSTAT SQL_ID)를 쓴다. IN 목록은 전체 스캔이라 쓰지 않는다(쿼리 검토 실측).
        long deadline = System.nanoTime() + TEXT_LOOKUP_DEADLINE_NS;
        try (Connection conn = poolManager.getConnection(target);
             PreparedStatement ps = conn.prepareStatement("SELECT SUBSTR(sql_text, 1, 1000) FROM v$sqlstats WHERE sql_id = ? AND ROWNUM = 1")) {
            ps.setQueryTimeout(settings.getInt(SettingKey.ASH_ACTIVITY_QUERY_TIMEOUT_SECONDS));
            long key = PerfStoreService.minuteKey(LocalDateTime.now(ZoneOffset.UTC));
            for (String id : missing) {
                if (System.nanoTime() > deadline) break;
                ps.setString(1, id);
                String text = null;
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) text = rs.getString(1);
                }
                if (text == null) {
                    textMiss.put(dbId + "|" + id, nowMs + TEXT_MISS_MS);
                    continue;
                }
                texts.put(id, text);
                try {
                    perfStore.saveSqlText(dbId, id, text, key);
                } catch (Exception e) {
                    log.debug("sql text save failed: {}", e.toString());
                }
            }
            if (textMiss.size() > 5000) textMiss.clear();
        } catch (SQLException e) {
            log.debug("sql text lookup skipped for db_id={}: {}", dbId, e.toString());
        }
        return texts;
    }

    // ------------------------------------------------------------------ 조회: sql_top

    public Map<String, Object> sqlTop(TargetDbConfig target, LocalDateTime from, LocalDateTime to, String view, String group, int limit, boolean baseline) {
        String dbId = target.id();
        Src s = pickSource(dbId, from, to);
        List<Agg> all = aggregate(dbId, s);
        boolean fmsGroup = "fms".equals(group);
        Map<String, Integer> fmsCount = new HashMap<String, Integer>();
        for (Agg a : all) if (a.fms != null) fmsCount.merge(a.fms, 1, Integer::sum);

        long totalElaUs = 0, totalExecs = 0;
        for (Agg a : all) { totalElaUs += a.elaUs; totalExecs += a.execs; }

        // 집계 단위: SQL_ID(기본) 또는 FMS(같은 시그니처 SQL_ID를 한 행으로, 0은 합치지 않음)
        List<Agg> units = new ArrayList<Agg>();
        Map<String, Integer> idCount = new HashMap<String, Integer>();
        if (fmsGroup) {
            Map<String, Agg> byFms = new LinkedHashMap<String, Agg>();
            Map<String, Long> bestEla = new HashMap<String, Long>();
            for (Agg a : all) {
                if (a.fms == null) { units.add(a); idCount.put(a.sqlId, 1); continue; }
                Agg g = byFms.get(a.fms);
                if (g == null) { g = new Agg(); g.fms = a.fms; g.sqlId = a.sqlId; byFms.put(a.fms, g); bestEla.put(a.fms, a.elaUs); }
                g.execs += a.execs; g.elaUs += a.elaUs; g.cpuUs += a.cpuUs; g.gets += a.gets; g.rows += a.rows; g.parse += a.parse;
                g.planCount = Math.max(g.planCount, a.planCount);
                if (a.elaUs > bestEla.get(a.fms)) { g.sqlId = a.sqlId; bestEla.put(a.fms, a.elaUs); } // 대표 SQL_ID = Elapsed가 가장 큰 것
            }
            units.addAll(byFms.values());
            for (Agg g : byFms.values()) idCount.put(g.sqlId, fmsCount.get(g.fms));
        } else {
            units.addAll(all);
        }
        final boolean freq = "freq".equals(view);
        Collections.sort(units, new Comparator<Agg>() {
            @Override
            public int compare(Agg a, Agg b) { return freq ? Long.compare(b.execs, a.execs) : Long.compare(b.elaUs, a.elaUs); }
        });
        int lim = Math.max(1, Math.min(MAX_LIMIT, limit <= 0 ? DEFAULT_LIMIT : limit));
        List<Agg> top = units.subList(0, Math.min(lim, units.size()));

        List<String> ids = new ArrayList<String>();
        for (Agg a : top) ids.add(a.sqlId);
        Map<String, String> texts = textsFor(target, ids);
        Map<String, Double> lastWeek = baseline ? lastWeekExecs(dbId, ids, s) : null;
        boolean hasBase = lastWeek != null && baselineAvailable(dbId, from, to);
        Set<String> changed = planChangedIds(dbId, from, to);

        List<Object> rows = new ArrayList<Object>();
        for (Agg a : top) {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("sqlId", a.sqlId);
            r.put("fms", a.fms);
            Integer cnt = idCount.get(a.sqlId);
            r.put("sqlIdCount", fmsGroup && a.fms != null ? cnt : null);
            r.put("text", preview(texts.get(a.sqlId)));
            r.put("elapsedSec", round(a.elaUs / 1e6, 2));
            r.put("pct", totalElaUs > 0 ? round(a.elaUs * 100.0 / totalElaUs, 1) : null);
            r.put("cpuSec", round(a.cpuUs / 1e6, 2));
            r.put("execs", a.execs);
            r.put("execPerSec", round(a.execs / s.seconds, 3));
            r.put("execPct", totalExecs > 0 ? round(a.execs * 100.0 / totalExecs, 1) : null);
            Double ms = a.execs > 0 ? a.elaUs / 1000.0 / a.execs : null;
            r.put("msPerExec", ms == null ? null : round(ms, 3));
            r.put("getsPerExec", a.execs > 0 ? round((double) a.gets / a.execs, 1) : null);
            r.put("rowsPerExec", a.execs > 0 ? round((double) a.rows / a.execs, 2) : null);
            r.put("parsePerExec", a.execs > 0 ? round((double) a.parse / a.execs, 2) : null);
            // (2026-10-09 점검 수정) FMS 묶음 행은 실행 수가 여러 SQL_ID 합인데 지난주 값은 대표 SQL_ID 하나뿐이라(롤업도 시간당 상위 N개만
            // 남아 리터럴 SQL_ID는 대부분 빠진다) 비교하면 급증이 과대로 나온다 - 묶음 행은 "기준 없음"(null)으로 둔다.
            // 같은 시그니처 SQL_ID가 둘 이상 묶인 행만 기준 없음(×1 묶음은 SQL_ID 하나라 지난주 값과 비교할 수 있다, QA D6)
            boolean merged = fmsGroup && a.fms != null && fmsCount.get(a.fms) != null && fmsCount.get(a.fms) > 1;
            Double base = lastWeek == null || merged ? null : lastWeek.get(a.sqlId);
            Double change = (hasBase && base != null && base > 0) ? round((a.execs - base) / base * 100.0, 1) : null;
            r.put("lastWeekExecs", hasBase && base != null ? Math.round(base) : null);
            r.put("changePct", change);
            r.put("badges", badges(a, s.seconds, texts.get(a.sqlId), change, fmsCount, changed));
            rows.add(r);
        }
        Map<String, Object> m = collectInfo(dbId, s);
        m.put("view", freq ? "freq" : "cost");
        m.put("group", fmsGroup ? "fms" : "id");
        m.put("from", from.toString());
        m.put("to", to.toString());
        m.put("rows", rows);
        m.put("totalExecs", totalExecs);
        m.put("totalElapsedSec", round(totalElaUs / 1e6, 2));
        m.put("sqlCount", all.size());
        m.put("hasBase", hasBase);
        return m;
    }

    private List<String> badges(Agg a, double seconds, String text, Double changePct, Map<String, Integer> fmsCount, Set<String> changed) {
        List<String> b = new ArrayList<String>();
        double execSec = a.execs / seconds;
        if (changePct != null && changePct >= settings.getInt(SettingKey.DIAG_SQL_SURGE_PCT) && a.execs >= settings.getInt(SettingKey.DIAG_SQL_SURGE_MIN_EXECS)) b.add("SURGE");
        if (a.execs > 0 && isSelect(text) && (double) a.rows / a.execs <= 1.01 && execSec >= settings.getInt(SettingKey.DIAG_SQL_NPLUS1_EXEC_SEC)) b.add("NPLUS1");
        if (a.execs > 0 && (double) a.parse / a.execs >= settings.getInt(SettingKey.DIAG_SQL_REUSE_PARSE_PCT) / 100.0
                && execSec >= settings.getInt(SettingKey.DIAG_SQL_REUSE_EXEC_SEC)) b.add("NO_REUSE");
        if (a.planCount >= 2 || changed.contains(a.sqlId)) b.add("PLAN_CHANGE");
        if (a.fms != null) {
            Integer c = fmsCount.get(a.fms);
            if (c != null && c >= settings.getInt(SettingKey.DIAG_SQL_LITERAL_ID_COUNT)) b.add("LITERAL");
        }
        return b;
    }

    private Set<String> planChangedIds(String dbId, LocalDateTime from, LocalDateTime to) {
        return new HashSet<String>(jdbc.queryForList("SELECT DISTINCT sql_id FROM perf_sql_plan_change WHERE db_id = ? AND changed_key >= ? AND changed_key < ?",
                String.class, dbId, PerfStoreService.minuteKey(from), PerfStoreService.minuteKey(to)));
    }

    // ------------------------------------------------------------------ 지난주 대비 (시간 롤업)

    /** 지난주 같은 구간에 롤업이 있는지(없으면 "기준 없음"). */
    private boolean baselineAvailable(String dbId, LocalDateTime from, LocalDateTime to) {
        long off = 7L * 1440;
        long fk = hourFloor(PerfStoreService.minuteKey(from)) - off;
        long toKey = PerfStoreService.minuteKey(to);
        long tk = (toKey % 60 == 0 ? toKey : hourFloor(toKey) + 60) - off; // 정시로 끝나는 구간은 그 시간을 더 보지 않는다(QA D7)
        return !heartbeats.heartbeatMinutes(dbId, "sqlhour", fk, tk).isEmpty();
    }

    /** SQL_ID별 지난주 같은 구간 실행 수 - 시간 롤업의 시간별 값을 구간과 겹친 비율로 나눠 더한다(1시간보다 짧은 구간은 비례 추정). */
    private Map<String, Double> lastWeekExecs(String dbId, List<String> ids, Src s) {
        Map<String, Double> out = new HashMap<String, Double>();
        if (ids.isEmpty()) return out;
        final long shift = 7L * 1440;
        // (2026-10-09 점검 수정) 이번 주 값과 같은 창으로 비교한다 - 델타면 입력 구간 그대로, 롤업이면 시간 단위로 넓힌 구간 중
        // 이번 주에 롤업이 있는 시간만(예전에는 롤업이어도 입력 구간으로 잘라 비율 추정해 창이 어긋났다).
        final long fk = (s.delta ? s.fromKey : s.fromHour) - shift;
        final long tk = (s.delta ? s.toKey : s.toHour) - shift;
        final Set<Long> rolled = s.delta ? null : s.rolled;
        StringBuilder in = new StringBuilder();
        List<Object> b = new ArrayList<Object>();
        b.add(dbId);
        b.add(hourFloor(fk));
        b.add(tk);
        for (String id : ids) { in.append(in.length() == 0 ? "?" : ", ?"); b.add(id); }
        jdbc.query("SELECT sql_id, hour_key, SUM(executions) FROM perf_sql_hour WHERE db_id = ? AND hour_key >= ? AND hour_key < ? " +
                        "AND sql_id IN (" + in + ") GROUP BY sql_id, hour_key",
                rs -> {
                    long h = rs.getLong(2);
                    if (rolled != null && !rolled.contains(h + shift)) return; // 이번 주 롤업이 아직 없는 시간(진행 중 등)은 비교에서 뺀다
                    long ov = Math.min(tk, h + 60) - Math.max(fk, h);
                    if (ov <= 0) return;
                    out.merge(rs.getString(1), rs.getLong(3) * (ov / 60.0), Double::sum);
                }, b.toArray());
        return out;
    }

    // ------------------------------------------------------------------ 조회: sql_trend

    /** Top SQL 추이: 델타면 분 단위, 롤업이면 시간 단위. metric = elapsed(초/분) | exec(회/초) | ms(ms/exec). */
    public Map<String, Object> sqlTrend(String dbId, LocalDateTime from, LocalDateTime to, List<String> sqlIds, String metric) {
        Src s = pickSource(dbId, from, to);
        long off = offsetMs(dbId);
        long fk = PerfStoreService.minuteKey(from);
        long tk = PerfStoreService.minuteKey(to);
        int step = s.delta ? 1 : 60;
        long start = s.delta ? fk : s.fromHour;
        long end = s.delta ? tk : s.toHour;
        int n = (int) Math.max(1, Math.min(MAX_RANGE_MINUTES / step + 1, (end - start + step - 1) / step));
        List<String> ids = sqlIds.size() > 20 ? sqlIds.subList(0, 20) : sqlIds;
        Map<String, double[][]> acc = new LinkedHashMap<String, double[][]>(); // sql_id → [0]=execs [1]=elapsed_us per bucket
        for (String id : ids) acc.put(id, new double[][]{new double[n], new double[n]});
        if (s.delta) {
            for (Object[] r : store.sqlstatSeries(dbId, ids, s.fromApp, s.toApp)) {
                int i = (int) (minuteKeyOf((Long) r[0], off) - start);
                double[][] a = acc.get((String) r[1]);
                if (a != null && i >= 0 && i < n) { a[0][i] += (Long) r[2]; a[1][i] += (Long) r[3]; }
            }
        } else if (!ids.isEmpty()) {
            StringBuilder in = new StringBuilder();
            List<Object> b = new ArrayList<Object>();
            b.add(dbId); b.add(start); b.add(end);
            for (String id : ids) { in.append(in.length() == 0 ? "?" : ", ?"); b.add(id); }
            final long st = start;
            jdbc.query("SELECT hour_key, sql_id, SUM(executions), SUM(elapsed_us) FROM perf_sql_hour WHERE db_id = ? AND hour_key >= ? AND hour_key < ? " +
                    "AND sql_id IN (" + in + ") GROUP BY hour_key, sql_id", rs -> {
                int i = (int) ((rs.getLong(1) - st) / 60);
                double[][] a = acc.get(rs.getString(2));
                if (a != null && i >= 0 && i < n) { a[0][i] += rs.getLong(3); a[1][i] += rs.getLong(4); }
            }, b.toArray());
        }
        List<String> times = new ArrayList<String>();
        for (int i = 0; i < n; i++) times.add(iso(start + (long) i * step));
        Map<String, Object> series = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, double[][]> e : acc.entrySet()) {
            List<Double> vals = new ArrayList<Double>();
            for (int i = 0; i < n; i++) {
                double ex = e.getValue()[0][i];
                double el = e.getValue()[1][i];
                Double v;
                if ("exec".equals(metric)) v = ex == 0 && el == 0 ? null : round(ex / (step * 60.0), 3);
                else if ("ms".equals(metric)) v = ex > 0 ? round(el / 1000.0 / ex, 3) : null;
                else v = el == 0 && ex == 0 ? null : round(el / 1e6 / step, 3);
                vals.add(v);
            }
            series.put(e.getKey(), vals);
        }
        Map<String, Object> m = collectInfo(dbId, s);
        m.put("metric", "exec".equals(metric) ? "exec" : "ms".equals(metric) ? "ms" : "elapsed");
        m.put("stepMinutes", step);
        m.put("times", times);
        m.put("series", series);
        return m;
    }

    // ------------------------------------------------------------------ 조회: sql_literal

    public Map<String, Object> sqlLiteral(TargetDbConfig target, LocalDateTime from, LocalDateTime to, int minCount, boolean baseline) {
        String dbId = target.id();
        long fk = PerfStoreService.minuteKey(from);
        long tk = PerfStoreService.minuteKey(to);
        final long fh = hourFloor(fk);
        final long th = (tk % 60 == 0) ? tk : hourFloor(tk) + 60;
        final double minutes = Math.max(1, (th - fh));
        final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        jdbc.query("SELECT fms, SUM(sql_id_cnt), SUM(execs), SUM(parse_calls), SUM(elapsed_us), MAX(sample_sql_id) FROM perf_sql_literal_hour " +
                        "WHERE db_id = ? AND hour_key >= ? AND hour_key < ? GROUP BY fms ORDER BY SUM(sql_id_cnt) DESC",
                rs -> {
                    if (rs.getLong(2) < Math.max(1, minCount)) return;
                    if (rows.size() >= 100) return;
                    Map<String, Object> r = new LinkedHashMap<String, Object>();
                    r.put("fms", rs.getString(1));
                    r.put("sqlIdCount", rs.getLong(2));
                    r.put("execs", rs.getLong(3));
                    r.put("hardParsePerMin", round(rs.getLong(4) / minutes, 2));
                    r.put("elapsedSec", round(rs.getLong(5) / 1e6, 2));
                    r.put("sampleSqlId", rs.getString(6));
                    rows.add(r);
                }, dbId, fh, th);
        List<String> ids = new ArrayList<String>();
        for (Map<String, Object> r : rows) if (r.get("sampleSqlId") != null) ids.add((String) r.get("sampleSqlId"));
        Map<String, String> texts = textsFor(target, ids);
        int litThreshold = settings.getInt(SettingKey.DIAG_SQL_LITERAL_ID_COUNT);
        final Map<String, Long> base = new HashMap<String, Long>();
        boolean hasBase = false;
        if (baseline) {
            long shift = 7L * 1440;
            hasBase = !heartbeats.heartbeatMinutes(dbId, "sqlhour", fh - shift, th - shift).isEmpty()
                    || jdbc.queryForObject("SELECT COUNT(*) FROM perf_sql_literal_hour WHERE db_id = ? AND hour_key >= ? AND hour_key < ?",
                    Integer.class, dbId, fh - shift, th - shift) > 0;
            if (hasBase) {
                jdbc.query("SELECT fms, SUM(sql_id_cnt) FROM perf_sql_literal_hour WHERE db_id = ? AND hour_key >= ? AND hour_key < ? GROUP BY fms",
                        rs -> { base.put(rs.getString(1), rs.getLong(2)); }, dbId, fh - shift, th - shift);
            }
        }
        for (Map<String, Object> r : rows) {
            r.put("text", preview(texts.get((String) r.get("sampleSqlId"))));
            r.put("lastWeekSqlIdCount", hasBase ? (Object) (base.containsKey(r.get("fms")) ? base.get(r.get("fms")) : 0L) : null);
            r.put("literal", (Long) r.get("sqlIdCount") >= litThreshold);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("from", from.toString());
        m.put("to", to.toString());
        m.put("granularity", "hour");
        m.put("rows", rows);
        m.put("hasBase", hasBase);
        m.put("threshold", litThreshold);
        m.put("newCursorEnabled", settings.getInt(SettingKey.SQLSTAT_NEW_CURSOR_MAX_ROWS) > 0);
        return m;
    }

    // ------------------------------------------------------------------ 조회: sql_plan_changes

    public Map<String, Object> sqlPlanChanges(TargetDbConfig target, LocalDateTime from, LocalDateTime to) {
        String dbId = target.id();
        final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        jdbc.query("SELECT sql_id, changed_key, old_plan_hash, new_plan_hash, old_ms_per_exec, new_ms_per_exec, old_execs, new_execs " +
                        "FROM perf_sql_plan_change WHERE db_id = ? AND changed_key >= ? AND changed_key < ?",
                rs -> {
                    Map<String, Object> r = new LinkedHashMap<String, Object>();
                    r.put("sqlId", rs.getString(1));
                    r.put("changedTime", iso(rs.getLong(2)));
                    r.put("oldPlan", rs.getLong(3));
                    r.put("newPlan", rs.getLong(4));
                    double o = rs.getDouble(5);
                    double nn = rs.getDouble(6);
                    r.put("oldMs", round(o, 3));
                    r.put("newMs", round(nn, 3));
                    r.put("ratio", o > 0 ? round(nn / o, 1) : null);
                    r.put("oldExecs", rs.getLong(7));
                    r.put("newExecs", rs.getLong(8));
                    rows.add(r);
                }, dbId, PerfStoreService.minuteKey(from), PerfStoreService.minuteKey(to));
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                double x = a.get("ratio") == null ? 0 : (Double) a.get("ratio");
                double y = b.get("ratio") == null ? 0 : (Double) b.get("ratio");
                return Double.compare(y, x);
            }
        });
        List<String> ids = new ArrayList<String>();
        for (Map<String, Object> r : rows) ids.add((String) r.get("sqlId"));
        Map<String, String> texts = textsFor(target, ids.size() > 50 ? ids.subList(0, 50) : ids);
        for (Map<String, Object> r : rows) r.put("text", preview(texts.get((String) r.get("sqlId"))));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("from", from.toString());
        m.put("to", to.toString());
        m.put("rows", rows.size() > 200 ? rows.subList(0, 200) : rows);
        m.put("note", "최근 24시간 감지(1시간 주기), 변경 전후 각 " + PLAN_MIN_EXECS + "회 이상 실행되고 변경 후 ms/exec가 " + (int) PLAN_WORSEN_RATIO + "배 이상인 건만 기록");
        return m;
    }

    // ------------------------------------------------------------------ 조회: sql_detail

    /** SQL 상세 - KPI 6개·실행계획 이력·진단 힌트·전문(대상 DB 단건 조회, 실패하면 저장된 앞부분). target은 컨트롤러가 검증해 넘긴다. */
    public Map<String, Object> sqlDetail(TargetDbConfig target, String sqlId, LocalDateTime from, LocalDateTime to) {
        String dbId = target.id();
        Src s = pickSource(dbId, from, to);
        List<Agg> all = aggregate(dbId, s);
        Agg me = null;
        int sameFms = 0;
        String fms = null;
        for (Agg a : all) if (a.sqlId.equals(sqlId)) { me = a; fms = a.fms; break; }
        if (fms != null) for (Agg a : all) if (fms.equals(a.fms)) sameFms++;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("sqlId", sqlId);
        m.put("fms", fms);
        m.put("sqlIdCount", fms == null ? null : sameFms);
        m.put("source", s.delta ? "delta" : "hour");
        m.put("approx", !s.delta);
        Map<String, String> saved = perfStore.sqlTexts(dbId, Arrays.asList(sqlId));
        String text = null;
        String textSource = null;
        try (Connection conn = poolManager.getConnection(target);
             PreparedStatement ps = conn.prepareStatement("SELECT sql_fulltext FROM v$sqlstats WHERE sql_id = ? AND ROWNUM = 1")) {
            // (2026-10-09 점검 수정) DBMS_LOB.SUBSTR(sql_fulltext, 4000, 1)은 SQL 문맥에서 VARCHAR2 4000바이트 한도라, 앞 4000자에
            // 한글(AL32UTF8 3바이트) 주석·리터럴이 섞인 긴 SQL은 ORA-06502로 실패해 늘 저장된 앞부분(1000자)으로 떨어졌다 -
            // CLOB을 그대로 읽어 Java에서 4000자로 자른다. (sql_id, plan_hash)마다 행이 있어 ROWNUM = 1로 한 행만 읽는다.
            ps.setQueryTimeout(settings.getInt(SettingKey.ASH_ACTIVITY_QUERY_TIMEOUT_SECONDS));
            ps.setString(1, sqlId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    text = rs.getString(1);
                    if (text != null && text.length() > 4000) text = text.substring(0, 4000);
                    if (text != null) textSource = "live";
                }
            }
            // v$sqlstats.sql_fulltext는 약 1000바이트까지만 담는다 - 그만큼 읽혔으면 v$sqlarea(전문 CLOB, sql_id 고정 인덱스 단건)에서 보충한다(QA D8)
            if (text != null && text.length() >= 900) {
                try (PreparedStatement ps2 = conn.prepareStatement("SELECT sql_fulltext FROM v$sqlarea WHERE sql_id = ? AND ROWNUM = 1")) {
                    ps2.setQueryTimeout(settings.getInt(SettingKey.ASH_ACTIVITY_QUERY_TIMEOUT_SECONDS));
                    ps2.setString(1, sqlId);
                    try (ResultSet rs2 = ps2.executeQuery()) {
                        if (rs2.next()) {
                            String full = rs2.getString(1);
                            if (full != null && full.length() > text.length()) text = full.length() > 4000 ? full.substring(0, 4000) : full;
                        }
                    }
                } catch (SQLException e2) {
                    log.debug("sql fulltext (v$sqlarea) lookup failed for sql_id={}: {}", sqlId, e2.toString());
                }
            }
        } catch (SQLException e) {
            log.debug("sql fulltext lookup failed for db_id={} sql_id={}: {}", dbId, sqlId, e.toString());
        }
        if (text == null && saved.get(sqlId) != null) { text = saved.get(sqlId); textSource = "saved"; }
        m.put("text", text);
        m.put("textSource", textSource);
        if (me == null) {
            m.put("found", false);
            m.put("plans", new ArrayList<Object>());
            m.put("badges", new ArrayList<Object>());
            return m;
        }
        m.put("found", true);
        Map<String, Object> k = new LinkedHashMap<String, Object>();
        k.put("execs", me.execs);
        k.put("execPerSec", round(me.execs / s.seconds, 3));
        k.put("msPerExec", me.execs > 0 ? round(me.elaUs / 1000.0 / me.execs, 3) : null);
        k.put("getsPerExec", me.execs > 0 ? round((double) me.gets / me.execs, 1) : null);
        k.put("rowsPerExec", me.execs > 0 ? round((double) me.rows / me.execs, 2) : null);
        k.put("parsePerExec", me.execs > 0 ? round((double) me.parse / me.execs, 2) : null);
        m.put("kpi", k);
        // 실행계획 이력: plan_hash별 첫·마지막 시각, ms/exec
        List<Object> plans = new ArrayList<Object>();
        long off = offsetMs(dbId);
        if (s.delta) {
            List<Object[]> ps = store.sqlstatPlanSums(dbId, sqlId, s.fromApp, s.toApp);
            Collections.sort(ps, new Comparator<Object[]>() {
                @Override
                public int compare(Object[] a, Object[] b) { return Long.compare((Long) a[1], (Long) b[1]); }
            });
            long latest = 0;
            for (Object[] p : ps) latest = Math.max(latest, (Long) p[2]);
            for (Object[] p : ps) {
                Map<String, Object> r = new LinkedHashMap<String, Object>();
                r.put("planHash", p[0]);
                r.put("firstTime", iso(minuteKeyOf((Long) p[1], off)));
                r.put("lastTime", iso(minuteKeyOf((Long) p[2], off)));
                long e = (Long) p[3];
                r.put("execs", e);
                r.put("msPerExec", e > 0 ? round((Long) p[4] / 1000.0 / e, 3) : null);
                r.put("current", ((Long) p[2]).equals(latest));
                plans.add(r);
            }
        } else {
            final List<Object[]> hp = new ArrayList<Object[]>();
            jdbc.query("SELECT plan_hash_value, MIN(hour_key), MAX(hour_key), SUM(executions), SUM(elapsed_us) FROM perf_sql_hour " +
                            "WHERE db_id = ? AND sql_id = ? AND hour_key >= ? AND hour_key < ? GROUP BY plan_hash_value",
                    rs -> { hp.add(new Object[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)}); },
                    dbId, sqlId, s.fromHour, s.toHour);
            Collections.sort(hp, new Comparator<Object[]>() {
                @Override
                public int compare(Object[] a, Object[] b) { return Long.compare((Long) a[1], (Long) b[1]); }
            });
            // "현재" Plan은 마지막으로 나타난 시간이 가장 늦은 것 하나만(같은 시간에 둘이면 실행 수가 많은 쪽, QA D10)
            Object[] cur = null;
            for (Object[] p : hp) {
                if (cur == null || (Long) p[2] > (Long) cur[2] || ((Long) p[2]).equals(cur[2]) && (Long) p[3] > (Long) cur[3]) cur = p;
            }
            for (Object[] p : hp) {
                Map<String, Object> r = new LinkedHashMap<String, Object>();
                r.put("planHash", p[0]);
                r.put("firstTime", iso((Long) p[1]));
                r.put("lastTime", iso((Long) p[2] + 60));
                long e = (Long) p[3];
                r.put("execs", e);
                r.put("msPerExec", e > 0 ? round((Long) p[4] / 1000.0 / e, 3) : null);
                r.put("current", p == cur);
                plans.add(r);
            }
        }
        m.put("plans", plans);
        Map<String, Integer> fmsCount = new HashMap<String, Integer>();
        if (fms != null) fmsCount.put(fms, sameFms);
        Map<String, Double> lw = lastWeekExecs(dbId, Arrays.asList(sqlId), s);
        Double base = lw.get(sqlId);
        boolean hasBase = baselineAvailable(dbId, from, to);
        Double change = (hasBase && base != null && base > 0) ? round((me.execs - base) / base * 100.0, 1) : null;
        m.put("lastWeekExecs", hasBase && base != null ? Math.round(base) : null);
        m.put("changePct", change);
        m.put("badges", badges(me, s.seconds, text, change, fmsCount, planChangedIds(dbId, from, to)));
        return m;
    }
}
