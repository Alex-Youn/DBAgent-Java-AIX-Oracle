package com.dbagent.monitor;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li>보관: dbagent.monitor.perf-retention-days(기본 15일), 매일 새벽 한 시간 단위로 잘라 지운다(쓰기 잠금을 짧게).</li>
 * </ul>
 * SQL은 SQLite·H2(1.4.200) 공통 문법만 쓰고, 다른 곳(SQL 텍스트 upsert)만 URL로 나눈다.
 */
@Service
public class PerfStoreService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(PerfStoreService.class);

    private final JdbcTemplate jdbc;
    private final boolean h2;

    @Value("${dbagent.monitor.perf-retention-days:15}")
    private int retentionDays;

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

    /** 드롭다운용: 보관 기간 안에 수집된 계정·서버 목록. */
    public List<String> distinct(String dbId, String column, long fromKey) {
        if (!"username".equals(column) && !"machine".equals(column)) return Collections.emptyList();
        return jdbc.queryForList("SELECT DISTINCT " + column + " FROM perf_ash_min WHERE db_id = ? AND minute_key >= ? AND " +
                column + " <> '' ORDER BY " + column, String.class, dbId, fromKey);
    }

    /** 가장 오래된·최근 수집 분(없으면 null) - 화면 안내("수집 시작 이후만 있음")용. */
    public Map<String, Object> coverage(String dbId) {
        return jdbc.queryForMap("SELECT MIN(minute_key) AS min_key, MAX(minute_key) AS max_key FROM perf_ash_min WHERE db_id = ?", dbId);
    }

    // ------------------------------------------------------------------ 보관

    @Scheduled(cron = "0 15 4 * * *")
    void purge() {
        long cutoff = minuteKey(LocalDateTime.now(ZoneOffset.UTC)) - retentionDays * 1440L;
        Long oldest = jdbc.queryForObject("SELECT MIN(minute_key) FROM perf_ash_min", Long.class);
        int total = 0;
        if (oldest != null) {
            // 한 시간치씩 - 한 번에 지우면 SQLite 쓰기 잠금이 길어져 샘플러 쓰기가 막힌다
            for (long k = oldest; k < cutoff; k += 60) {
                total += jdbc.update("DELETE FROM perf_ash_min WHERE minute_key >= ? AND minute_key < ?", k, Math.min(k + 60, cutoff));
            }
        }
        int texts = jdbc.update("DELETE FROM perf_sql_text WHERE captured_key < ?", cutoff);
        if (total > 0 || texts > 0) {
            log.info("perf store purge: {} row(s), {} sql text(s) older than {} day(s)", total, texts, retentionDays);
        }
    }
}
