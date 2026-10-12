package com.dbagent.monitor;

import com.dbagent.settings.SettingKey;
import com.dbagent.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 성능 분석 수집 저장소(2026-09-26, 오케스트레이터 결정) - main은 SQLite(db_config/perf.db), AIX는 H2(db_config/perf.mv.db).
 * 60초 샘플러가 매분 읽는 ASH 구간을 (분, 7분류, event, 계정, 접속 서버, SQL) 조합으로 묶어 샘플 초를 쌓는다.
 * 성능 분석 화면은 이 저장소를 읽어 운영 DB에 조회를 보내지 않는다(원본 ASH/AWR 조회는 수집 전 구간용 선택 경로).
 *
 * <ul>
 *   <li>perf_ash_min: 샘플러 한 번(보통 1분)마다 행을 추가만 한다(갱신 없음) - 같은 분에 두 번 들어와도 읽을 때 SUM이라
 *       문법 차이가 큰 upsert가 필요 없다. 시각 minute_key는 DB 벽시계 분을 "UTC로 본 epoch 분"으로 바꾼 값이라
 *       화면의 DB 시각 문자열과 시간대 변환 없이 맞는다.</li>
 *   <li>perf_sql_text: sql_id별 SQL 앞부분 1000자 - 15일 전 SQL은 공유 풀에서 이미 밀려나므로 수집 시점에 잡아 둔다.</li>
 *   <li>perf_filter_value: 계정·접속 서버 드롭다운 값(값마다 1행, 마지막으로 본 분). 화면을 열 때마다 perf_ash_min
 *       보관 기간 전체를 DISTINCT로 훑지 않도록 수집 시점에 따로 적어 둔다(2026-10-12, 폐쇄망 실측 7초).</li>
 *   <li>보관: dbagent.monitor.perf-retention-days(기본 15일), 매일 새벽 한 시간 단위로 잘라 지운다(쓰기 잠금을 짧게).</li>
 * </ul>
 * SQL은 SQLite·H2(1.4.200) 공통 문법만 쓰고, 다른 곳(SQL 텍스트 upsert)만 URL로 나눈다.
 */
@Service
public class PerfStoreService implements InitializingBean, DisposableBean {

    // 환경설정 DB화 P3-2(2026-10-04): 이 클래스가 쓰던 @Value 설정을 SettingsService 조회로 옮겼다(저장 즉시 반영).
    @Autowired
    private SettingsService settings;

    private static final Logger log = LoggerFactory.getLogger(PerfStoreService.class);

    private final JdbcTemplate jdbc;
    private final boolean h2;

    // ------------------------------------------------------------------ 계정·서버 드롭다운 값(perf_filter_value)
    // (2026-10-12 폐쇄망 실측: /api/perf/filters가 7초 - 화면을 열 때마다 perf_ash_min 15일치를 계정용·서버용 두 번 훑었다.)
    private static final String KIND_USER = "U";
    private static final String KIND_MACHINE = "M";
    /** DB별 "기존 perf_ash_min에서 값을 다 옮겼다" 표시 행의 kind. 값 행(U/M)과 같은 테이블에 둔다. */
    private static final String KIND_SEEDED = "S";
    /** 같은 값의 last_key를 다시 쓰는 최소 간격(분) - 매분 쓰지 않는다. 읽을 때 이만큼 구간을 넓혀 보정한다. */
    private static final long FILTER_TOUCH_MINUTES = 60;

    /** dbId + kind + 값 → 저장소에 마지막으로 쓴 minute_key. 메모리 값이라 재기동 후 첫 수집 때 값마다 한 번 다시 쓴다. */
    private final ConcurrentHashMap<String, Long> filterPersisted = new ConcurrentHashMap<>();
    /** 기존 데이터 옮기기가 끝난 DB. 없으면 표시 행을 한 번 조회해 확인한다. */
    private final Set<String> filterSeeded = ConcurrentHashMap.newKeySet();
    /** DB별 옮기기 잠금 - 화면 요청과 백그라운드가 같은 DB를 동시에 훑지 않게 한다(먼저 온 쪽이 훑고 나머지는 기다린다). */
    private final ConcurrentHashMap<String, Object> filterSeedLocks = new ConcurrentHashMap<>();
    private final Set<String> filterSeedQueued = ConcurrentHashMap.newKeySet();
    /** 옮기기가 실패한 DB를 다시 맡겨도 되는 시각(ms). 실패 원인이 계속되면 매분 perf_ash_min을 훑게 되므로 간격을 둔다. */
    private final ConcurrentHashMap<String, Long> filterSeedRetryAt = new ConcurrentHashMap<>();
    private static final long FILTER_SEED_RETRY_MS = 10 * 60_000L;
    private volatile boolean stopping;
    // 기존 데이터 옮기기는 DB마다 perf_ash_min을 한 번 훑는다(느리다) - 샘플러·공용 스케줄러 스레드를 붙잡지 않게 전용 스레드 1개로,
    // 한 번에 한 DB씩 한다.
    private final ExecutorService filterSeedExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "perf-filter-seed");
        t.setDaemon(true);
        return t;
    });


    // DB별 "이 분(minute_key) 앞까지는 수집이 끝났다" - 샘플러가 저장할 때마다 올린다(2026-09-30 QA). 성능 분석 차트가 아직 수집
    // 안 된 최근 1~2분을 0으로 그려 부하가 꺼진 것처럼 보이던 문제를 막는다. 메모리 값이라 앱 기동 직후 첫 샘플 전에는 없다(null).
    private final java.util.concurrent.ConcurrentHashMap<String, Long> collectedBefore = new java.util.concurrent.ConcurrentHashMap<>();

    /** 샘플러: dbId의 수집 완료 기준을 key(이 분 앞까지 완료)로 올린다. 뒤로 가지 않는다. */
    public void markCollected(String dbId, long key) {
        collectedBefore.merge(dbId, key, Math::max);
    }

    /** 이 분 앞까지 수집 완료(없으면 null - 아직 모름). */
    public Long collectedBefore(String dbId) {
        return collectedBefore.get(dbId);
    }

    public PerfStoreService(@Qualifier("perfJdbcTemplate") JdbcTemplate jdbc, @Qualifier("perfDataSource") HikariDataSource ds) {
        this.jdbc = jdbc;
        this.h2 = ds.getJdbcUrl() != null && ds.getJdbcUrl().startsWith("jdbc:h2:");
    }

    @Override
    public void afterPropertiesSet() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_ash_min (" +
                "db_id VARCHAR(64) NOT NULL, minute_key BIGINT NOT NULL, category VARCHAR(16) NOT NULL, " +
                "event VARCHAR(64) NOT NULL, username VARCHAR(128) NOT NULL, machine VARCHAR(64) NOT NULL, " +
                "sql_id VARCHAR(13) NOT NULL, sql_opcode INTEGER, seconds INTEGER NOT NULL, sessions INTEGER, " +
                "last_sid BIGINT, last_serial BIGINT)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_perf_ash_min_time ON perf_ash_min (db_id, minute_key)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_sql_text (" +
                "db_id VARCHAR(64) NOT NULL, sql_id VARCHAR(13) NOT NULL, sql_text VARCHAR(1000), captured_key BIGINT NOT NULL, " +
                "PRIMARY KEY (db_id, sql_id))");
        // 새 테이블이라 기존 큰 테이블을 바꾸지 않는다(기동 지연 없음). 컬럼 이름 val: VALUE는 H2 예약어 계열이라 피한다.
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_filter_value (" +
                "db_id VARCHAR(64) NOT NULL, kind VARCHAR(1) NOT NULL, val VARCHAR(128) NOT NULL, last_key BIGINT NOT NULL, " +
                "PRIMARY KEY (db_id, kind, val))");
    }

    @Override
    public void destroy() {
        // shutdownNow()로 인터럽트를 걸지 않는다 (2026-10-12 코드 점검 실측: H2 1.4.200은 조회 중인 스레드가 인터럽트되면
        // 파일 채널을 닫아, 그 조회와 같은 파일을 쓰는 다른 연결의 다음 읽기까지 한 번 실패한다). 대기 중인 작업만 건너뛰게 하고,
        // 돌고 있는 옮기기는 데몬 스레드라 JVM 종료를 막지 않는다.
        stopping = true;
        filterSeedExecutor.shutdown();
    }

    // ------------------------------------------------------------------ 시각 변환

    /** DB 벽시계 시각 → minute_key(분). */
    public static long minuteKey(LocalDateTime dbTime) {
        return Math.floorDiv(dbTime.toEpochSecond(ZoneOffset.UTC), 60L);
    }

    public static LocalDateTime fromMinuteKey(long key) {
        return LocalDateTime.ofEpochSecond(key * 60L, 0, ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ 쓰기

    /** 한 번에 한 DB·한 샘플 구간의 행들. row = {minute_key, category, event, username, machine, sql_id, opcode, seconds, sessions, last_sid, last_serial}. */
    public void saveRows(String dbId, List<Object[]> rows) {
        // 행이 없는 수집(한가한 DB)에서도 맡긴다 - 지금 조용한 DB도 이미 쌓인 값은 옮겨야 화면 첫 조회가 느리지 않다
        queueFilterSeed(dbId);
        if (rows.isEmpty()) return;
        List<Object[]> args = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Object[] a = new Object[r.length + 1];
            a[0] = dbId;
            System.arraycopy(r, 0, a, 1, r.length);
            args.add(a);
        }
        jdbc.batchUpdate("INSERT INTO perf_ash_min (db_id, minute_key, category, event, username, machine, sql_id, sql_opcode, " +
                "seconds, sessions, last_sid, last_serial) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", args);
        try {
            touchFilterValues(dbId, rows);
        } catch (Exception e) {
            // 드롭다운 값 기록 실패가 ASH 저장(위에서 이미 끝남)을 실패로 만들지 않게 한다
            log.warn("perf filter value save failed for {}: {}", dbId, e.getMessage());
        }
    }

    /** 이번 수집 행에 나온 계정·서버 값 중 처음 보거나 last_key가 오래된 것만 저장소에 쓴다. */
    private void touchFilterValues(String dbId, List<Object[]> rows) {
        Map<String, Long> users = new java.util.HashMap<>();
        Map<String, Long> machines = new java.util.HashMap<>();
        for (Object[] r : rows) {
            long key = ((Number) r[0]).longValue();
            if (r[3] != null && !r[3].toString().isEmpty()) users.merge(r[3].toString(), key, Math::max);
            if (r[4] != null && !r[4].toString().isEmpty()) machines.merge(r[4].toString(), key, Math::max);
        }
        List<Object[]> writes = new ArrayList<>();
        collectFilterWrites(dbId, KIND_USER, users, writes);
        collectFilterWrites(dbId, KIND_MACHINE, machines, writes);
        writeFilterValues(writes);
        // 메모리 기록은 저장소 쓰기가 성공한 뒤에만 올린다 - 먼저 올리면 쓰기가 실패(잠금 대기 초과 등)했을 때 "썼다"고 남아
        // 그 값이 최대 FILTER_TOUCH_MINUTES 동안(그 뒤로 안 나오면 계속) 드롭다운에서 빠진다. 실패하면 다음 수집 때 다시 쓴다.
        // (2026-10-12 코드 점검 수정)
        for (Object[] w : writes) {
            filterPersisted.merge(filterMapKey(dbId, (String) w[1], (String) w[2]), (Long) w[3], Math::max);
        }
    }

    private static String filterMapKey(String dbId, String kind, String value) {
        return dbId + '\u0001' + kind + value;
    }

    private void collectFilterWrites(String dbId, String kind, Map<String, Long> values, List<Object[]> writes) {
        for (Map.Entry<String, Long> e : values.entrySet()) {
            Long persisted = filterPersisted.get(filterMapKey(dbId, kind, e.getKey()));
            if (persisted != null && e.getValue() - persisted < FILTER_TOUCH_MINUTES) continue;
            writes.add(new Object[]{dbId, kind, e.getKey(), e.getValue()});
        }
    }

    private void writeFilterValues(List<Object[]> writes) {
        if (writes.isEmpty()) return;
        jdbc.batchUpdate(h2
                ? "MERGE INTO perf_filter_value (db_id, kind, val, last_key) KEY (db_id, kind, val) VALUES (?, ?, ?, ?)"
                : "INSERT OR REPLACE INTO perf_filter_value (db_id, kind, val, last_key) VALUES (?, ?, ?, ?)", writes);
    }

    /** 이 DB의 기존 데이터 옮기기가 아직이면 전용 스레드에 한 번만 맡긴다(샘플러는 기다리지 않는다). */
    private void queueFilterSeed(String dbId) {
        if (filterSeeded.contains(dbId)) return;
        Long retryAt = filterSeedRetryAt.get(dbId);
        if (retryAt != null && System.currentTimeMillis() < retryAt) return;
        if (!filterSeedQueued.add(dbId)) return;
        try {
            filterSeedExecutor.execute(() -> {
                if (stopping) return;
                try {
                    ensureFilterSeeded(dbId);
                    filterSeedRetryAt.remove(dbId);
                } catch (Exception e) {
                    filterSeedRetryAt.put(dbId, System.currentTimeMillis() + FILTER_SEED_RETRY_MS);
                    filterSeedQueued.remove(dbId); // 간격이 지난 뒤의 수집 때 다시 시도
                    log.warn("perf filter value seed failed for {} (retry in {} min): {}",
                            dbId, FILTER_SEED_RETRY_MS / 60_000L, e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            filterSeedQueued.remove(dbId); // 앱 종료 중 - 수집 저장을 실패로 만들지 않는다
        }
    }

    /**
     * 이 DB의 perf_ash_min에 이미 쌓인 계정·서버 값을 perf_filter_value로 옮긴다(DB마다 한 번, 표시 행으로 기억).
     * perf_ash_min을 한 번 훑으므로 느리다 - 예전에는 화면을 열 때마다 이 스캔을 두 번 했다.
     */
    private void ensureFilterSeeded(String dbId) {
        if (filterSeeded.contains(dbId)) return;
        synchronized (filterSeedLocks.computeIfAbsent(dbId, k -> new Object())) {
            if (filterSeeded.contains(dbId)) return;
            Integer marked = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM perf_filter_value WHERE db_id = ? AND kind = ?", Integer.class, dbId, KIND_SEEDED);
            if (marked == null || marked == 0) {
                long started = System.currentTimeMillis();
                Map<String, Long> users = new java.util.HashMap<>();
                Map<String, Long> machines = new java.util.HashMap<>();
                jdbc.query("SELECT username, machine, MAX(minute_key) FROM perf_ash_min WHERE db_id = ? GROUP BY username, machine",
                        rs -> {
                            long key = rs.getLong(3);
                            String u = rs.getString(1);
                            String m = rs.getString(2);
                            if (u != null && !u.isEmpty()) users.merge(u, key, Math::max);
                            if (m != null && !m.isEmpty()) machines.merge(m, key, Math::max);
                        }, dbId);
                List<Object[]> writes = new ArrayList<>();
                for (Map.Entry<String, Long> e : users.entrySet()) writes.add(new Object[]{dbId, KIND_USER, e.getKey(), e.getValue()});
                for (Map.Entry<String, Long> e : machines.entrySet()) writes.add(new Object[]{dbId, KIND_MACHINE, e.getKey(), e.getValue()});
                writeFilterValues(writes);
                // 표시 행은 값 행을 다 쓴 뒤에 따로 쓴다 - 값 쓰기가 실패하면 표시가 없어 다음에 처음부터 다시 옮긴다
                writeFilterValues(Collections.singletonList(
                        new Object[]{dbId, KIND_SEEDED, "-", minuteKey(LocalDateTime.now(ZoneOffset.UTC))}));
                log.info("perf filter values seeded for {}: {} user(s), {} machine(s) in {} ms",
                        dbId, users.size(), machines.size(), System.currentTimeMillis() - started);
            }
            filterSeeded.add(dbId);
        }
    }

    /** 이미 행이 있는 분(minute_key) - backfill이 겹쳐 쓰지 않게. */
    public Set<Long> minutesWithData(String dbId, long fromKey, long toKey) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT minute_key FROM perf_ash_min WHERE db_id = ? AND minute_key >= ? AND minute_key < ?",
                Long.class, dbId, fromKey, toKey));
    }

    public void saveSqlText(String dbId, String sqlId, String text, long capturedKey) {
        String t = text == null ? null : (text.length() > 1000 ? text.substring(0, 1000) : text);
        if (h2) {
            jdbc.update("MERGE INTO perf_sql_text (db_id, sql_id, sql_text, captured_key) KEY (db_id, sql_id) VALUES (?, ?, ?, ?)",
                    dbId, sqlId, t, capturedKey);
        } else {
            jdbc.update("INSERT OR REPLACE INTO perf_sql_text (db_id, sql_id, sql_text, captured_key) VALUES (?, ?, ?, ?)",
                    dbId, sqlId, t, capturedKey);
        }
    }

    /** 저장된 sql_id(수집기가 기동 후 처음 한 번 읽어 "이미 잡은 SQL"로 쓴다) → captured_key. */
    public Map<String, Long> knownSqlIds(String dbId) {
        Map<String, Long> out = new java.util.HashMap<>();
        jdbc.query("SELECT sql_id, captured_key FROM perf_sql_text WHERE db_id = ?",
                rs -> { out.put(rs.getString(1), rs.getLong(2)); }, dbId);
        return out;
    }

    // ------------------------------------------------------------------ 읽기

    /** 필터(계정·서버) 조건과 바인드. 별칭 없음. */
    private static String filterSql(AshRange.Filter f, List<Object> binds) {
        StringBuilder sb = new StringBuilder();
        if (f != null && !f.users.isEmpty()) {
            sb.append(" AND username IN (").append(placeholders(f.users.size())).append(")");
            binds.addAll(f.users);
        }
        if (f != null && !f.machines.isEmpty()) {
            sb.append(" AND machine IN (").append(placeholders(f.machines.size())).append(")");
            binds.addAll(f.machines);
        }
        return sb.toString();
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ", ?");
        return sb.toString();
    }

    /** 분×7분류 초 합계: {minute_key, category, seconds}. */
    public List<Map<String, Object>> minuteCategory(String dbId, long fromKey, long toKey, AshRange.Filter f) {
        List<Object> b = new ArrayList<>(java.util.Arrays.asList(dbId, fromKey, toKey));
        String sql = "SELECT minute_key, category, SUM(seconds) AS seconds FROM perf_ash_min " +
                "WHERE db_id = ? AND minute_key >= ? AND minute_key < ?" + filterSql(f, b) + " GROUP BY minute_key, category";
        return jdbc.queryForList(sql, b.toArray());
    }

    /** event×분류 초 합계(내림차순). */
    public List<Map<String, Object>> eventTotals(String dbId, long fromKey, long toKey, AshRange.Filter f) {
        List<Object> b = new ArrayList<>(java.util.Arrays.asList(dbId, fromKey, toKey));
        String sql = "SELECT event, category, SUM(seconds) AS seconds FROM perf_ash_min " +
                "WHERE db_id = ? AND minute_key >= ? AND minute_key < ?" + filterSql(f, b) +
                " GROUP BY event, category ORDER BY seconds DESC";
        return jdbc.queryForList(sql, b.toArray());
    }

    /** event 하나의 (계정, 서버, SQL) 조합별 합계 - 마지막 SID는 가장 늦은 분의 값을 자바에서 고른다. */
    public List<Map<String, Object>> eventRows(String dbId, String event, long fromKey, long toKey, AshRange.Filter f) {
        List<Object> b = new ArrayList<>(java.util.Arrays.asList(dbId, fromKey, toKey, event));
        String sql = "SELECT username, machine, sql_id, sql_opcode, minute_key, seconds, sessions, last_sid, last_serial " +
                "FROM perf_ash_min WHERE db_id = ? AND minute_key >= ? AND minute_key < ? AND event = ?" + filterSql(f, b);
        return jdbc.queryForList(sql, b.toArray());
    }

    public Map<String, String> sqlTexts(String dbId, Collection<String> sqlIds) {
        Map<String, String> out = new java.util.HashMap<>();
        if (sqlIds.isEmpty()) return out;
        List<String> ids = new ArrayList<>(sqlIds);
        for (int i = 0; i < ids.size(); i += 200) {
            List<String> part = ids.subList(i, Math.min(ids.size(), i + 200));
            List<Object> b = new ArrayList<>();
            b.add(dbId);
            b.addAll(part);
            jdbc.query("SELECT sql_id, sql_text FROM perf_sql_text WHERE db_id = ? AND sql_id IN (" + placeholders(part.size()) + ")",
                    rs -> { out.put(rs.getString(1), rs.getString(2)); }, b.toArray());
        }
        return out;
    }

    /**
     * 드롭다운용: 보관 기간 안에 수집된 계정·서버 목록. perf_filter_value(값마다 1행)만 읽는다.
     * 기존 데이터를 아직 못 옮긴 DB는 여기서 한 번 옮기고(그 요청만 느리다) 읽는다. 옮기기가 실패하면 예전처럼 perf_ash_min을 훑는다.
     */
    public List<String> distinct(String dbId, String column, long fromKey) {
        if (!"username".equals(column) && !"machine".equals(column)) return Collections.emptyList();
        try {
            ensureFilterSeeded(dbId);
        } catch (Exception e) {
            log.warn("perf filter value seed failed for {}, scanning perf_ash_min instead: {}", dbId, e.getMessage());
            return jdbc.queryForList("SELECT DISTINCT " + column + " FROM perf_ash_min WHERE db_id = ? AND minute_key >= ? AND " +
                    column + " <> '' ORDER BY " + column, String.class, dbId, fromKey);
        }
        // 저장된 last_key는 최대 FILTER_TOUCH_MINUTES만큼 늦다 - 그만큼 구간을 넓혀 아직 보관 중인 값이 빠지지 않게 한다
        return jdbc.queryForList("SELECT val FROM perf_filter_value WHERE db_id = ? AND kind = ? AND last_key >= ? ORDER BY val",
                String.class, dbId, "username".equals(column) ? KIND_USER : KIND_MACHINE, fromKey - FILTER_TOUCH_MINUTES);
    }

    /** 가장 오래된·최근 수집 분(없으면 null) - 화면 안내("수집 시작 이후만 있음")용. */
    public Map<String, Object> coverage(String dbId) {
        return jdbc.queryForMap("SELECT MIN(minute_key) AS min_key, MAX(minute_key) AS max_key FROM perf_ash_min WHERE db_id = ?", dbId);
    }

    // ------------------------------------------------------------------ 보관

    @Scheduled(cron = "0 15 4 * * *")
    void purge() {
        long cutoff = minuteKey(LocalDateTime.now(ZoneOffset.UTC)) - settings.getInt(SettingKey.PERF_RETENTION_DAYS) * 1440L;
        Long oldest = jdbc.queryForObject("SELECT MIN(minute_key) FROM perf_ash_min", Long.class);
        int total = 0;
        if (oldest != null) {
            // 한 시간치씩 - 한 번에 지우면 SQLite 쓰기 잠금이 길어져 샘플러 쓰기가 막힌다
            for (long k = oldest; k < cutoff; k += 60) {
                total += jdbc.update("DELETE FROM perf_ash_min WHERE minute_key >= ? AND minute_key < ?", k, Math.min(k + 60, cutoff));
            }
        }
        int texts = jdbc.update("DELETE FROM perf_sql_text WHERE captured_key < ?", cutoff);
        // 드롭다운 값: 보관 기간 동안 한 번도 안 나온 값만 지운다(표시 행 S는 남긴다). 메모리 기록도 같이 비워 다시 나오면 새로 쓴다.
        jdbc.update("DELETE FROM perf_filter_value WHERE kind <> ? AND last_key < ?", KIND_SEEDED, cutoff - FILTER_TOUCH_MINUTES);
        filterPersisted.values().removeIf(k -> k < cutoff - FILTER_TOUCH_MINUTES);
        if (total > 0 || texts > 0) {
            log.info("perf store purge: {} row(s), {} sql text(s) older than {} day(s)", total, texts, settings.getInt(SettingKey.PERF_RETENTION_DAYS));
        }
    }
}
