package com.dbagent.settings;

import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 환경설정 값 저장소 - dbconfig DB 의 app_setting / app_setting_db / app_setting_history / app_setting_meta
 * (환경설정 DB화 P1-2, 2026-10-04, 설계문서/환경설정_DB화_설계_2026-10-04.md 4절).
 *
 * <p>SQLite(메인)와 H2(AIX)에서 같은 DDL·SQL 이 돌도록 이식 가능한 문법만 쓴다: 컬럼은 VARCHAR(H2 1.4.200 의
 * TEXT 는 CLOB 이라 키로 못 쓴다), upsert 는 "UPDATE 후 0건이면 INSERT", 이력 id 는 쓰기 잠금 안에서 MAX+1.
 * 설정 변경은 드물어 이 방식으로 충분하다. 컬럼 이름 key/value 는 H2 예약어라 setting_key/setting_value 로 쓴다.
 *
 * <p>DB 에는 기본값과 다르게 바꾼 값만 둔다 - 행이 없으면 기본값이다(옛 기본값이 DB 에 굳는 문제 방지).
 */
@Component
public class AppSettingStore {

    private static final Logger log = LoggerFactory.getLogger(AppSettingStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 저장된 한 행. */
    public static final class Row {
        private final String key;
        private final String value;
        private final String updatedAt;
        private final String updatedBy;
        private final long version;

        Row(String key, String value, String updatedAt, String updatedBy, long version) {
            this.key = key;
            this.value = value;
            this.updatedAt = updatedAt;
            this.updatedBy = updatedBy;
            this.version = version;
        }

        public String key() { return key; }
        public String value() { return value; }
        public String updatedAt() { return updatedAt; }
        public String updatedBy() { return updatedBy; }
        public long version() { return version; }
    }

    private final JdbcTemplate jdbc;
    private final Object writeLock = new Object();

    public AppSettingStore(@Qualifier("dbConfigJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_setting (" +
                "setting_key VARCHAR(200) PRIMARY KEY," +
                "setting_value VARCHAR(4000) NOT NULL," +
                "updated_at VARCHAR(40)," +
                "updated_by VARCHAR(100)," +
                "row_version INTEGER NOT NULL DEFAULT 1" +
                ")");
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_setting_db (" +
                "db_id VARCHAR(200) NOT NULL," +
                "setting_key VARCHAR(200) NOT NULL," +
                "setting_value VARCHAR(4000) NOT NULL," +
                "updated_at VARCHAR(40)," +
                "updated_by VARCHAR(100)," +
                "row_version INTEGER NOT NULL DEFAULT 1," +
                "PRIMARY KEY (db_id, setting_key)" +
                ")");
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_setting_history (" +
                "id BIGINT PRIMARY KEY," +
                "setting_key VARCHAR(200) NOT NULL," +
                "db_id VARCHAR(200)," +
                "old_value VARCHAR(4000)," +
                "new_value VARCHAR(4000)," +
                "action VARCHAR(20) NOT NULL," +
                "changed_by VARCHAR(100)," +
                "changed_at VARCHAR(40) NOT NULL," +
                "reason VARCHAR(1000)" +
                ")");
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_setting_meta (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1)," +
                "migrated_from_properties INTEGER NOT NULL DEFAULT 0" +
                ")");
        // SQLite 의 INSERT OR IGNORE / H2 의 MERGE 대신 양쪽에서 같은 문법으로 "없을 때만" 넣는다.
        jdbc.update("INSERT INTO app_setting_meta (id, migrated_from_properties) " +
                "SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM app_setting_meta WHERE id = 1)");
    }

    /** 전역 저장값 (키 이름 → 행). 기본값과 다르게 바꾼 것만 있다. */
    public Map<String, Row> loadGlobal() {
        Map<String, Row> out = new LinkedHashMap<String, Row>();
        List<Row> rows = jdbc.query(
                "SELECT setting_key, setting_value, updated_at, updated_by, row_version FROM app_setting ORDER BY setting_key",
                (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)));
        for (Row r : rows) {
            out.put(r.key(), r);
        }
        return out;
    }

    /** DB 별 재정의 (db_id → 키 이름 → 행). */
    public Map<String, Map<String, Row>> loadPerDb() {
        Map<String, Map<String, Row>> out = new HashMap<String, Map<String, Row>>();
        jdbc.query("SELECT db_id, setting_key, setting_value, updated_at, updated_by, row_version FROM app_setting_db ORDER BY db_id, setting_key",
                rs -> {
                    String dbId = rs.getString(1);
                    Map<String, Row> m = out.get(dbId);
                    if (m == null) {
                        m = new LinkedHashMap<String, Row>();
                        out.put(dbId, m);
                    }
                    m.put(rs.getString(2), new Row(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6)));
                });
        return out;
    }

    public boolean isMigratedFromProperties() {
        Integer v = jdbc.queryForObject("SELECT migrated_from_properties FROM app_setting_meta WHERE id = 1", Integer.class);
        return v != null && v != 0;
    }

    public void markMigratedFromProperties() {
        jdbc.update("UPDATE app_setting_meta SET migrated_from_properties = 1 WHERE id = 1");
    }

    /**
     * 전역 값을 저장한다 (신규면 INSERT, 있으면 UPDATE + 버전 증가). 이력도 같이 남긴다.
     *
     * @param action SET / MIGRATE
     */
    public void setGlobal(String key, String value, String user, String reason, String action) {
        synchronized (writeLock) {
            List<String> old = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE setting_key = ?", String.class, key);
            String now = LocalDateTime.now().format(TS);
            int n = jdbc.update("UPDATE app_setting SET setting_value = ?, updated_at = ?, updated_by = ?, row_version = row_version + 1 " +
                    "WHERE setting_key = ?", value, now, user, key);
            if (n == 0) {
                jdbc.update("INSERT INTO app_setting (setting_key, setting_value, updated_at, updated_by, row_version) VALUES (?, ?, ?, ?, 1)",
                        key, value, now, user);
            }
            appendHistory(key, null, old.isEmpty() ? null : old.get(0), value, action, user, reason, now);
        }
    }

    /** 전역 값을 지워 기본값으로 되돌린다. 이력에 RESET 을 남긴다. 지울 행이 없으면 false. */
    public boolean resetGlobal(String key, String user, String reason) {
        synchronized (writeLock) {
            List<String> old = jdbc.queryForList("SELECT setting_value FROM app_setting WHERE setting_key = ?", String.class, key);
            if (old.isEmpty()) {
                return false;
            }
            jdbc.update("DELETE FROM app_setting WHERE setting_key = ?", key);
            appendHistory(key, null, old.get(0), null, "RESET", user, reason, LocalDateTime.now().format(TS));
            return true;
        }
    }

    /** DB 별 재정의를 저장한다. */
    public void setPerDb(String dbId, String key, String value, String user, String reason) {
        synchronized (writeLock) {
            List<String> old = jdbc.queryForList("SELECT setting_value FROM app_setting_db WHERE db_id = ? AND setting_key = ?",
                    String.class, dbId, key);
            String now = LocalDateTime.now().format(TS);
            int n = jdbc.update("UPDATE app_setting_db SET setting_value = ?, updated_at = ?, updated_by = ?, row_version = row_version + 1 " +
                    "WHERE db_id = ? AND setting_key = ?", value, now, user, dbId, key);
            if (n == 0) {
                jdbc.update("INSERT INTO app_setting_db (db_id, setting_key, setting_value, updated_at, updated_by, row_version) VALUES (?, ?, ?, ?, ?, 1)",
                        dbId, key, value, now, user);
            }
            appendHistory(key, dbId, old.isEmpty() ? null : old.get(0), value, "SET", user, reason, now);
        }
    }

    /** DB 별 재정의를 지워 전역값을 따르게 한다. */
    public boolean resetPerDb(String dbId, String key, String user, String reason) {
        synchronized (writeLock) {
            List<String> old = jdbc.queryForList("SELECT setting_value FROM app_setting_db WHERE db_id = ? AND setting_key = ?",
                    String.class, dbId, key);
            if (old.isEmpty()) {
                return false;
            }
            jdbc.update("DELETE FROM app_setting_db WHERE db_id = ? AND setting_key = ?", dbId, key);
            appendHistory(key, dbId, old.get(0), null, "RESET", user, reason, LocalDateTime.now().format(TS));
            return true;
        }
    }

    /** DB 를 삭제할 때 그 DB 의 재정의를 모두 정리한다. */
    public int deleteAllForDb(String dbId, String user) {
        synchronized (writeLock) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT setting_key, setting_value FROM app_setting_db WHERE db_id = ?", dbId);
            if (rows.isEmpty()) {
                return 0;
            }
            String now = LocalDateTime.now().format(TS);
            for (Map<String, Object> r : rows) {
                appendHistory(String.valueOf(r.get("setting_key")), dbId, String.valueOf(r.get("setting_value")), null,
                        "RESET", user, "DB 삭제로 재정의 정리", now);
            }
            return jdbc.update("DELETE FROM app_setting_db WHERE db_id = ?", dbId);
        }
    }

    private void appendHistory(String key, String dbId, String oldValue, String newValue, String action, String user, String reason, String now) {
        // 이력 id: 쓰기 잠금 안이라 MAX+1 로 충분하다 (AUTOINCREMENT 문법이 SQLite/H2 에서 달라 피한다).
        Long next = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM app_setting_history", Long.class);
        SettingKey def = SettingKey.byKey(key);
        boolean mask = def != null && def.secret();
        jdbc.update("INSERT INTO app_setting_history (id, setting_key, db_id, old_value, new_value, action, changed_by, changed_at, reason) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                next, key, dbId, mask && oldValue != null ? "***" : oldValue, mask && newValue != null ? "***" : newValue,
                action, user, now, reason);
        log.info("설정 변경 [{}] {}{} : {} -> {} (by {}{})", action, key, dbId == null ? "" : " @" + dbId,
                mask ? "***" : oldValue, mask ? "***" : newValue, user, reason == null ? "" : ", 사유=" + reason.replaceAll("[\r\n]+", " "));
    }

    /** 변경 이력 조회 (최신순). key / dbId 가 null 이면 그 조건은 걸지 않는다. */
    public List<Map<String, Object>> history(String key, String dbId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, setting_key, db_id, old_value, new_value, action, changed_by, changed_at, reason " +
                "FROM app_setting_history WHERE 1 = 1");
        List<Object> args = new ArrayList<Object>();
        if (key != null) {
            sql.append(" AND setting_key = ?");
            args.add(key);
        }
        if (dbId != null) {
            sql.append(" AND db_id = ?");
            args.add(dbId);
        }
        sql.append(" ORDER BY id DESC");
        List<Map<String, Object>> all = jdbc.queryForList(sql.toString(), args.toArray());
        return all.size() > limit ? all.subList(0, Math.max(limit, 0)) : all;
    }
}
