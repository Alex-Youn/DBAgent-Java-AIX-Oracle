package com.dbagent.oracle;

import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a db_id to Oracle/RDB connection details, backed by the db_instances SQLite table
 * (dbconfig.db). Replaces the earlier databases.json file store - oracle.env 제거 마이그레이션
 * 4-1단계, 2026-09-21. On first boot (db_config_meta.migrated_from_json = 0), imports whatever
 * groups/instances are in the legacy databases.json file (if present) as a one-time migration,
 * then never reads that file again - see migrateFromJsonFile().
 */
@Service
public class DatabaseConfigService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfigService.class);

    // Legacy file this service migrates FROM exactly once - not a live config path any more.
    private static final String LEGACY_JSON_PATH = "databases.json";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    // Serializes createInstance/updateInstance/deleteInstance against each other and against the
    // migration step. Reads (resolve, listAccounts, listAllInstances, safeConfig, ...) don't need
    // this - SQLite/JDBC already gives each query a consistent snapshot.
    private final Object writeLock = new Object();

    public DatabaseConfigService(@Qualifier("dbConfigJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        // VARCHAR, not TEXT - this H2 version (1.4.200) maps TEXT to CLOB, and CLOB columns can't be
        // indexed ("Feature not supported: Index on BLOB or CLOB column"), which breaks id's PRIMARY
        // KEY. SQLite (main project's build of this same service) has no such restriction and keeps TEXT.
        jdbc.execute("CREATE TABLE IF NOT EXISTS db_instances (" +
                "id VARCHAR(200) PRIMARY KEY," +
                "group_name VARCHAR(200) NOT NULL," +
                "group_order INTEGER NOT NULL," +
                "instance_order INTEGER NOT NULL," +
                "name VARCHAR(200) NOT NULL DEFAULT ''," +
                "db_type VARCHAR(50) NOT NULL DEFAULT 'oracle'," +
                "host VARCHAR(200) NOT NULL DEFAULT ''," +
                "port INTEGER NOT NULL DEFAULT 1521," +
                "sid VARCHAR(200) NOT NULL DEFAULT ''," +
                // Named db_user, not user - "user"/"USER" collides with a reserved keyword/function
                // on H2 (this project's users.mv.db/metrics.mv.db engine).
                "db_user VARCHAR(200) NOT NULL DEFAULT ''," +
                "password VARCHAR(500) NOT NULL DEFAULT ''," +
                "connect_mode VARCHAR(50) NOT NULL DEFAULT ''," +
                "pool_min_idle INTEGER," +
                "pool_max_size INTEGER," +
                "session_thresholds VARCHAR(200)," +
                "accounts VARCHAR(4000)," +
                "expected_instance_name VARCHAR(200)" +
                ")");
        // A dbconfig.mv.db created before 5단계 (expected_instance_name didn't exist yet) needs this
        // column added on top - H2 supports ADD COLUMN IF NOT EXISTS natively, unlike SQLite.
        jdbc.execute("ALTER TABLE db_instances ADD COLUMN IF NOT EXISTS expected_instance_name VARCHAR(200)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS db_config_meta (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1)," +
                "migrated_from_json INTEGER NOT NULL DEFAULT 0" +
                ")");
        // H2 has no "INSERT OR IGNORE" (that's SQLite/MySQL dialect) - this portable form works on
        // both engines and, unlike a MERGE, never touches the row if it already exists (a MERGE would
        // reset migrated_from_json back to 0 on every boot, re-running the migration each time).
        jdbc.update("INSERT INTO db_config_meta (id, migrated_from_json) " +
                "SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM db_config_meta WHERE id = 1)");
        Integer migrated = jdbc.queryForObject(
                "SELECT migrated_from_json FROM db_config_meta WHERE id = 1", Integer.class);
        if (migrated == null || migrated == 0) {
            migrateFromJsonFile();
            jdbc.update("UPDATE db_config_meta SET migrated_from_json = 1 WHERE id = 1");
        }
        warnOnDuplicates();
        errorOnMissingOracleHost();
    }

    // oracle.env 제거 마이그레이션 5단계: alias(host 빈값 → tnsnames.ora) 경로를 완전히 삭제했으므로
    // host 없는 Oracle 인스턴스는 이제 접속 자체가 불가능하다(buildDsn()이 그냥 ":port:sid"를 만들어
    // 조용히 실패함). 그 상태로 방치되지 않도록 기동 시점에 눈에 띄게 ERROR로 남긴다 - 다른 정상
    // 인스턴스까지 막지는 않기 위해(warnOnDuplicates()와 같은 원칙) 기동 자체를 중단시키지는 않는다.
    private void errorOnMissingOracleHost() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM db_instances WHERE db_type = 'oracle' AND (host IS NULL OR TRIM(host) = '')");
        for (Map<String, Object> row : rows) {
            log.error("db_instances의 Oracle 인스턴스 '{}'에 host가 비어 있습니다 - alias(tnsnames.ora) 경로가 " +
                    "제거되어 이 상태로는 접속할 수 없습니다. 관리 화면에서 host를 채워주세요.", row.get("id"));
        }
    }

    /**
     * One-time import from the legacy databases.json (if it exists next to the jar) into
     * db_instances - runs at most once per dbconfig.db (guarded by db_config_meta above), so
     * deleting/trimming databases.json before a server's first run on this new storage controls
     * exactly what gets carried over (e.g. moving only a couple of instances to a closed-network
     * server while the rest are added later through the admin UI). Missing file = nothing to
     * import, not an error - an empty db_instances table is a normal, fully supported state.
     */
    private void migrateFromJsonFile() {
        File file = new File(LEGACY_JSON_PATH);
        if (!file.exists()) {
            return;
        }
        JsonNode root;
        try {
            root = mapper.readTree(file);
        } catch (IOException e) {
            log.warn("{} 마이그레이션 실패 - 파일을 읽을 수 없습니다: {}", LEGACY_JSON_PATH, e.getMessage());
            return;
        }
        if (root == null || !root.has("groups")) {
            return;
        }
        int groupOrder = 0;
        int imported = 0;
        for (JsonNode group : root.get("groups")) {
            String groupName = group.path("group_name").asText("");
            int instanceOrder = 0;
            for (JsonNode inst : group.path("instances")) {
                String id = inst.path("id").asText("");
                if (Strings.isBlank(id)) {
                    continue;
                }
                String sessionThresholds = inst.has("session_thresholds") ? inst.get("session_thresholds").toString() : null;
                String accounts = inst.has("accounts") ? inst.get("accounts").toString() : null;
                // H2 has no "INSERT OR REPLACE" (SQLite dialect) - MERGE ... KEY (id) is the H2
                // equivalent: insert if id is new, overwrite the row if id already exists.
                jdbc.update("MERGE INTO db_instances " +
                                "(id, group_name, group_order, instance_order, name, db_type, host, port, sid, db_user, " +
                                "password, connect_mode, pool_min_idle, pool_max_size, session_thresholds, accounts) " +
                                "KEY (id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        id, groupName, groupOrder, instanceOrder,
                        inst.path("name").asText(""),
                        inst.path("db_type").asText("oracle"),
                        inst.path("host").asText(""),
                        inst.path("port").asInt(1521),
                        inst.path("sid").asText(""),
                        inst.path("user").asText(""),
                        inst.path("password").asText(""),
                        inst.path("connect_mode").asText(""),
                        inst.hasNonNull("pool_min_idle") ? inst.path("pool_min_idle").asInt() : null,
                        inst.hasNonNull("pool_max_size") ? inst.path("pool_max_size").asInt() : null,
                        sessionThresholds, accounts);
                instanceOrder++;
                imported++;
            }
            groupOrder++;
        }
        log.info("{}에서 db_instances 테이블로 {}개 인스턴스를 마이그레이션했습니다.", LEGACY_JSON_PATH, imported);
    }

    // oracle.env 제거 마이그레이션 1단계: id 오타/재사용이나 (host,port,sid) 중복 등록은 예전에도
    // "엉뚱한 DB에 접속" 사고로 이어졌으므로(설계문서 참고), 기동 시점에 눈에 띄게 경고만 남긴다 -
    // 기존 동작을 바꾸지 않기 위해 시작을 막지는 않는다. id 중복은 PRIMARY KEY 제약으로 이제 테이블
    // 차원에서 막히지만(마이그레이션 중 중복이 있었다면 나중 값으로 덮어써짐), endpoint 중복은 여전히
    // 가능해 그대로 검사한다.
    private void warnOnDuplicates() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, host, port, sid FROM db_instances");
        Set<String> seenEndpoints = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String host = (String) row.get("host");
            if (!Strings.isBlank(host)) {
                String endpoint = host + ":" + row.get("port") + ":" + row.get("sid");
                if (!seenEndpoints.add(endpoint)) {
                    log.warn("db_instances에 동일한 (host,port,sid)를 가진 인스턴스가 여러 개 있습니다: {}", endpoint);
                }
            }
        }
    }

    /**
     * A blank/missing dbId - no instance selected yet, a typo, a stale bookmark, a removed instance -
     * returns null rather than silently substituting a real but unrelated DB (oracle.env's old fallback
     * removed in the oracle.env removal migration's 4단계: that used to let any authenticated account
     * view or query that default DB via a blank/bogus db_id, and made stale/broken links fail silently
     * instead of loudly). Callers must treat a null result as "DB not found", not attempt to connect to it.
     */
    public TargetDbConfig resolve(String dbId) {
        if (Strings.isBlank(dbId)) {
            return null;
        }
        Map<String, Object> row = findRow(dbId);
        return row != null ? fromRow(row) : null;
    }

    /**
     * Same as resolve(dbId), but if account is non-blank and doesn't match the instance's default
     * user, looks it up in the instance's optional "accounts" column (JSON array) and returns a
     * TargetDbConfig with that account's user/password instead, keeping the same host/port/sid/id.
     * Falls back to the default account if account is blank or isn't found in "accounts". See
     * resolve(dbId) above for why a blank or unknown dbId returns null.
     */
    public TargetDbConfig resolve(String dbId, String account) {
        if (Strings.isBlank(dbId)) {
            return null;
        }
        Map<String, Object> row = findRow(dbId);
        if (row == null) {
            return null;
        }
        TargetDbConfig base = fromRow(row);
        if (account == null || Strings.isBlank(account) || account.equals(base.user())) {
            return base;
        }
        String accountsJson = (String) row.get("accounts");
        if (accountsJson != null) {
            try {
                for (JsonNode acc : mapper.readTree(accountsJson)) {
                    if (account.equals(acc.path("user").asText(""))) {
                        return new TargetDbConfig(
                                base.id(),
                                base.name(),
                                base.dbType(),
                                acc.path("user").asText(),
                                resolvePassword(acc.path("password").asText("")),
                                base.host(),
                                base.port(),
                                base.sid(),
                                base.connectMode(),
                                base.poolMinIdle(),
                                base.poolMaxSize(),
                                base.expectedInstanceName());
                    }
                }
            } catch (IOException ignored) {
                // Malformed accounts JSON - fall through to the default account below.
            }
        }
        return base;
    }

    /**
     * Default account first, followed by any accounts listed in the instance's optional "accounts"
     * column. A blank or unregistered dbId lists no accounts, matching resolve(dbId)'s handling of the
     * same cases (both return "nothing found" rather than leaking a default DB's identity).
     */
    public List<String> listAccounts(String dbId) {
        List<String> users = new ArrayList<>();
        Map<String, Object> row = findRow(dbId);
        if (row == null) {
            return users;
        }
        users.add((String) row.get("db_user"));
        String accountsJson = (String) row.get("accounts");
        if (accountsJson != null) {
            try {
                for (JsonNode acc : mapper.readTree(accountsJson)) {
                    String u = acc.path("user").asText("");
                    if (!Strings.isBlank(u) && !users.contains(u)) {
                        users.add(u);
                    }
                }
            } catch (IOException ignored) {
                // Malformed accounts JSON - just the default account above.
            }
        }
        return users;
    }

    public List<TargetDbConfig> listAllInstances() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM db_instances ORDER BY group_order, instance_order");
        List<TargetDbConfig> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(fromRow(row));
        }
        return result;
    }

    private Map<String, Object> findRow(String dbId) {
        if (dbId == null) {
            return null;
        }
        // Defensive: a request with a duplicated db_id query param (?db_id=x&db_id=x) gets bound by
        // Spring as a single comma-joined string ("x,x"), which would otherwise silently match no
        // instance and return null instead of resolving the one actually picked.
        String normalized = dbId.indexOf(',') >= 0 ? dbId.substring(0, dbId.indexOf(',')) : dbId;
        if (Strings.isBlank(normalized)) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM db_instances WHERE id = ?", normalized);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private TargetDbConfig fromRow(Map<String, Object> row) {
        Object poolMinIdle = row.get("pool_min_idle");
        Object poolMaxSize = row.get("pool_max_size");
        return new TargetDbConfig(
                (String) row.get("id"),
                (String) row.get("name"),
                (String) row.get("db_type"),
                (String) row.get("db_user"),
                resolvePassword((String) row.get("password")),
                (String) row.get("host"),
                ((Number) row.get("port")).intValue(),
                (String) row.get("sid"),
                (String) row.get("connect_mode"),
                poolMinIdle == null ? null : ((Number) poolMinIdle).intValue(),
                poolMaxSize == null ? null : ((Number) poolMaxSize).intValue(),
                (String) row.get("expected_instance_name"));
    }

    /** Java port of api_server.py's /api/config: same groups/instances shape, with passwords stripped. */
    public Map<String, Object> safeConfig() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM db_instances ORDER BY group_order, instance_order");
        LinkedHashMap<String, List<Map<String, Object>>> byGroup = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String groupName = (String) row.get("group_name");
            List<Map<String, Object>> list = byGroup.get(groupName);
            if (list == null) {
                list = new ArrayList<>();
                byGroup.put(groupName, list);
            }
            list.add(toSafeInstanceMap(row));
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byGroup.entrySet()) {
            Map<String, Object> safeGroup = new LinkedHashMap<>();
            safeGroup.put("group_name", entry.getKey());
            safeGroup.put("instances", entry.getValue());
            groups.add(safeGroup);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", groups);
        return result;
    }

    private Map<String, Object> toSafeInstanceMap(Map<String, Object> row) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("id", row.get("id"));
        safe.put("name", row.get("name"));
        safe.put("db_type", row.get("db_type"));
        safe.put("host", row.get("host"));
        safe.put("port", row.get("port"));
        safe.put("sid", row.get("sid"));
        safe.put("user", row.get("db_user"));
        safe.put("pool_min_idle", row.get("pool_min_idle"));
        safe.put("pool_max_size", row.get("pool_max_size"));
        String connectMode = (String) row.get("connect_mode");
        if (!Strings.isBlank(connectMode)) {
            safe.put("connect_mode", connectMode);
        }
        String expectedInstanceName = (String) row.get("expected_instance_name");
        if (!Strings.isBlank(expectedInstanceName)) {
            safe.put("expected_instance_name", expectedInstanceName);
        }
        String sessionThresholdsJson = (String) row.get("session_thresholds");
        if (sessionThresholdsJson != null) {
            try {
                safe.put("session_thresholds", mapper.readValue(sessionThresholdsJson, List.class));
            } catch (IOException ignored) {
                // Malformed - just omit the override, global default applies on the frontend.
            }
        }
        String accountsJson = (String) row.get("accounts");
        if (accountsJson != null) {
            try {
                List<Map<String, Object>> safeAccounts = new ArrayList<>();
                for (JsonNode acc : mapper.readTree(accountsJson)) {
                    Map<String, Object> safeAcc = new LinkedHashMap<>();
                    // Strip each extra account's (still B64-obfuscated, but not real encryption)
                    // password - this response goes to every logged-in user, not just admins, same
                    // as the top-level instance password above.
                    safeAcc.put("user", acc.path("user").asText(""));
                    safeAccounts.add(safeAcc);
                }
                if (!safeAccounts.isEmpty()) {
                    safe.put("accounts", safeAccounts);
                }
            } catch (IOException ignored) {
                // Malformed - just omit the extra accounts.
            }
        }
        return safe;
    }

    // Not real encryption - just keeps the raw password out of a plain-text glance at the DB file.
    // B64(...)-wrapped values are decoded; anything else passes through unchanged as plaintext.
    private static final String B64_PREFIX = "B64(";
    private static final String B64_SUFFIX = ")";

    private String resolvePassword(String raw) {
        if (raw == null || !raw.startsWith(B64_PREFIX) || !raw.endsWith(B64_SUFFIX)) {
            return raw;
        }
        String encoded = raw.substring(B64_PREFIX.length(), raw.length() - B64_SUFFIX.length());
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private String encodePassword(String plain) {
        if (plain == null) {
            return "";
        }
        return B64_PREFIX + Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8)) + B64_SUFFIX;
    }

    /** Admin UI: add a new DB instance under groupName (created if it doesn't already exist). */
    public Map<String, Object> createInstance(String groupName, String id, String name, String dbType, String host,
            int port, String sid, String user, String password, String connectMode, String expectedInstanceName,
            Integer poolMinIdle, Integer poolMaxSize, List<Map<String, String>> accounts,
            List<Integer> sessionThresholds) {
        if (Strings.isBlank(id)) {
            return Maps.of("success", false, "message", "ID는 필수입니다.");
        }
        if (Strings.isBlank(groupName)) {
            return Maps.of("success", false, "message", "그룹명은 필수입니다.");
        }
        if (Strings.isBlank(password)) {
            return Maps.of("success", false, "message", "비밀번호는 필수입니다.");
        }
        // oracle.env 제거 마이그레이션 5단계: alias(tnsnames.ora) 경로 삭제로 host는 이제 항상 필수.
        String resolvedDbType = Strings.isBlank(dbType) ? "oracle" : dbType;
        if ("oracle".equals(resolvedDbType) && Strings.isBlank(host)) {
            return Maps.of("success", false, "message", "Host는 필수입니다.");
        }
        synchronized (writeLock) {
            List<Map<String, Object>> existing = jdbc.queryForList("SELECT id FROM db_instances WHERE id = ?", id);
            if (!existing.isEmpty()) {
                return Maps.of("success", false, "message", "이미 존재하는 ID입니다: " + id);
            }
            // A brand-new instance has no stored accounts to fall back to, so every row here needs
            // its own non-blank password.
            String accountsError = validateAccounts(accounts, null);
            if (accountsError != null) {
                return Maps.of("success", false, "message", accountsError);
            }
            String thresholdsError = validateSessionThresholds(sessionThresholds);
            if (thresholdsError != null) {
                return Maps.of("success", false, "message", thresholdsError);
            }

            int groupOrder = groupOrderFor(groupName);
            int instanceOrder = nextInstanceOrder(groupName);
            jdbc.update("INSERT INTO db_instances " +
                            "(id, group_name, group_order, instance_order, name, db_type, host, port, sid, db_user, " +
                            "password, connect_mode, pool_min_idle, pool_max_size, session_thresholds, accounts, " +
                            "expected_instance_name) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, groupName, groupOrder, instanceOrder,
                    name == null ? "" : name, resolvedDbType,
                    host == null ? "" : host, port, sid == null ? "" : sid, user == null ? "" : user,
                    encodePassword(password), connectMode == null ? "" : connectMode, poolMinIdle, poolMaxSize,
                    buildSessionThresholdsJson(sessionThresholds), buildAccountsJson(accounts, null),
                    Strings.isBlank(expectedInstanceName) ? null : expectedInstanceName);
            return Maps.of("success", true, "message", "DB가 추가되었습니다.");
        }
    }

    /** Admin UI: update an existing instance's fields. Blank/null password keeps the stored value. */
    public Map<String, Object> updateInstance(String id, String name, String dbType, String host, int port,
            String sid, String user, String password, String connectMode, String expectedInstanceName,
            Integer poolMinIdle, Integer poolMaxSize, List<Map<String, String>> accounts,
            List<Integer> sessionThresholds) {
        String resolvedDbType = Strings.isBlank(dbType) ? "oracle" : dbType;
        if ("oracle".equals(resolvedDbType) && Strings.isBlank(host)) {
            return Maps.of("success", false, "message", "Host는 필수입니다.");
        }
        synchronized (writeLock) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM db_instances WHERE id = ?", id);
            if (rows.isEmpty()) {
                return Maps.of("success", false, "message", "존재하지 않는 DB입니다: " + id);
            }
            Map<String, Object> existing = rows.get(0);
            String existingAccountsJson = (String) existing.get("accounts");

            // A blank password on an existing account row keeps that account's stored password.
            String accountsError = validateAccounts(accounts, existingAccountsJson);
            if (accountsError != null) {
                return Maps.of("success", false, "message", accountsError);
            }
            String thresholdsError = validateSessionThresholds(sessionThresholds);
            if (thresholdsError != null) {
                return Maps.of("success", false, "message", thresholdsError);
            }

            String newPassword = (!Strings.isBlank(password))
                    ? encodePassword(password) : (String) existing.get("password");
            jdbc.update("UPDATE db_instances SET name=?, db_type=?, host=?, port=?, sid=?, db_user=?, password=?, " +
                            "connect_mode=?, pool_min_idle=?, pool_max_size=?, session_thresholds=?, accounts=?, " +
                            "expected_instance_name=? WHERE id=?",
                    name == null ? "" : name, resolvedDbType,
                    host == null ? "" : host, port, sid == null ? "" : sid, user == null ? "" : user,
                    newPassword, connectMode == null ? "" : connectMode, poolMinIdle, poolMaxSize,
                    buildSessionThresholdsJson(sessionThresholds), buildAccountsJson(accounts, existingAccountsJson),
                    Strings.isBlank(expectedInstanceName) ? null : expectedInstanceName,
                    id);
            return Maps.of("success", true, "message", "DB 정보가 수정되었습니다.");
        }
    }

    /** Admin UI: remove an instance. No group bookkeeping needed - groups are derived from rows on read. */
    public Map<String, Object> deleteInstance(String id) {
        synchronized (writeLock) {
            int deleted = jdbc.update("DELETE FROM db_instances WHERE id = ?", id);
            if (deleted == 0) {
                return Maps.of("success", false, "message", "존재하지 않는 DB입니다: " + id);
            }
            return Maps.of("success", true, "message", "DB가 삭제되었습니다.");
        }
    }

    private int groupOrderFor(String groupName) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT group_order FROM db_instances WHERE group_name = ? LIMIT 1", groupName);
        if (!rows.isEmpty()) {
            return ((Number) rows.get(0).get("group_order")).intValue();
        }
        Integer maxGroupOrder = jdbc.queryForObject("SELECT MAX(group_order) FROM db_instances", Integer.class);
        return (maxGroupOrder == null ? -1 : maxGroupOrder) + 1;
    }

    private int nextInstanceOrder(String groupName) {
        Integer maxInstanceOrder = jdbc.queryForObject(
                "SELECT MAX(instance_order) FROM db_instances WHERE group_name = ?", Integer.class, groupName);
        return (maxInstanceOrder == null ? -1 : maxInstanceOrder) + 1;
    }

    /**
     * Validates the admin UI's account rows without building the JSON yet - a blank password on a
     * row is only valid if existingAccountsJson already has a stored password for that user (the
     * "leave blank to keep unchanged" contract). Returns an error message, or null if all rows are
     * valid.
     */
    private String validateAccounts(List<Map<String, String>> accounts, String existingAccountsJson) {
        if (accounts == null) {
            return null;
        }
        for (Map<String, String> acc : accounts) {
            String accUser = acc.get("user");
            if (Strings.isBlank(accUser)) {
                continue;
            }
            String accPassword = acc.get("password");
            if (!Strings.isBlank(accPassword)) {
                continue;
            }
            if (findExistingAccountPassword(existingAccountsJson, accUser) == null) {
                return "추가 계정 '" + accUser + "'의 비밀번호를 입력하세요.";
            }
        }
        return null;
    }

    /**
     * Rebuilds the "accounts" JSON array from the admin UI's rows - call only after
     * validateAccounts() has confirmed every row resolves to a password. A row with a blank
     * password reuses the matching user's already-stored (still B64-encoded) password from
     * existingAccountsJson, if any.
     */
    private String buildAccountsJson(List<Map<String, String>> accounts, String existingAccountsJson) {
        if (accounts == null || accounts.isEmpty()) {
            return null;
        }
        ArrayNode accArr = mapper.createArrayNode();
        for (Map<String, String> acc : accounts) {
            String accUser = acc.get("user");
            if (Strings.isBlank(accUser)) {
                continue;
            }
            String accPassword = acc.get("password");
            String encoded = (!Strings.isBlank(accPassword))
                    ? encodePassword(accPassword)
                    : findExistingAccountPassword(existingAccountsJson, accUser);
            ObjectNode accNode = mapper.createObjectNode();
            accNode.put("user", accUser);
            accNode.put("password", encoded);
            accArr.add(accNode);
        }
        return accArr.size() > 0 ? accArr.toString() : null;
    }

    private String findExistingAccountPassword(String existingAccountsJson, String user) {
        if (existingAccountsJson == null) {
            return null;
        }
        try {
            for (JsonNode acc : mapper.readTree(existingAccountsJson)) {
                if (user.equals(acc.path("user").asText(""))) {
                    return acc.path("password").asText(null);
                }
            }
        } catch (IOException ignored) {
            // Malformed - treat as "no existing password to reuse".
        }
        return null;
    }

    /**
     * "session_thresholds": [t1..t5] - per-instance override of the dashboard's active-session
     * color/gauge thresholds (see app.js DEFAULT_SESSION_THRESHOLDS). Null/empty from the admin UI
     * means "don't override" (global default applies); otherwise exactly 5 values are required,
     * matching what app.js's getSessColor() expects.
     */
    private String validateSessionThresholds(List<Integer> sessionThresholds) {
        if (sessionThresholds == null || sessionThresholds.isEmpty()) {
            return null;
        }
        if (sessionThresholds.size() != 5 || sessionThresholds.contains(null)) {
            return "세션 임계치는 5개 값을 모두 입력해야 합니다.";
        }
        return null;
    }

    private String buildSessionThresholdsJson(List<Integer> sessionThresholds) {
        if (sessionThresholds == null || sessionThresholds.isEmpty()) {
            return null;
        }
        ArrayNode arr = mapper.createArrayNode();
        for (Integer t : sessionThresholds) {
            arr.add(t);
        }
        return arr.toString();
    }
}
