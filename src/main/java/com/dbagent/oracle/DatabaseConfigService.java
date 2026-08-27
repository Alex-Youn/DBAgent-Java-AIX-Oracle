package com.dbagent.oracle;

import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves a db_id to Oracle connection details, backed by databases.json with an oracle.env fallback. */
@Service
public class DatabaseConfigService {

    @Value("${dbagent.databases-config}")
    private String databasesConfigPath;

    @Value("${dbagent.oracle-env-path}")
    private String oracleEnvPath;

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode config;
    private TargetDbConfig fallback;

    // Guards createInstance/updateInstance/deleteInstance: config is otherwise copy-on-write (each
    // write builds a whole new tree and swaps the `config` reference), so reads (resolve, findInstance,
    // safeConfig, ...) never need to synchronize - they just see either the old tree or the new one,
    // never a half-mutated one. This lock only serializes concurrent admin writes against each other.
    private final Object writeLock = new Object();

    @PostConstruct
    void init() throws IOException {
        File file = new File(databasesConfigPath);
        config = file.exists() ? mapper.readTree(file) : mapper.createObjectNode();
        fallback = loadOracleEnvFallback(new File(oracleEnvPath));
    }

    public TargetDbConfig resolve(String dbId) {
        JsonNode inst = findInstance(dbId);
        return inst != null ? fromInstance(inst) : fallback;
    }

    /**
     * Same as resolve(dbId), but if account is non-blank and doesn't match the instance's default
     * user, looks it up in the instance's optional "accounts" array (see databases.json) and returns
     * a TargetDbConfig with that account's user/password instead, keeping the same host/port/sid/id.
     * Falls back to the default account if account is blank or isn't found in "accounts".
     */
    public TargetDbConfig resolve(String dbId, String account) {
        JsonNode inst = findInstance(dbId);
        if (inst == null) {
            return fallback;
        }
        TargetDbConfig base = fromInstance(inst);
        if (account == null || Strings.isBlank(account) || account.equals(base.user())) {
            return base;
        }
        for (JsonNode acc : inst.path("accounts")) {
            if (account.equals(acc.path("user").asText(""))) {
                return new TargetDbConfig(
                        base.id(),
                        base.name(),
                        acc.path("user").asText(),
                        resolvePassword(acc.path("password").asText("")),
                        base.host(),
                        base.port(),
                        base.sid(),
                        base.poolMinIdle(),
                        base.poolMaxSize());
            }
        }
        return base;
    }

    /** Default account first, followed by any accounts listed in the instance's optional "accounts" array. */
    public List<String> listAccounts(String dbId) {
        JsonNode inst = findInstance(dbId);
        List<String> users = new ArrayList<>();
        if (inst == null) {
            users.add(fallback.user());
            return users;
        }
        users.add(inst.path("user").asText("SYS"));
        for (JsonNode acc : inst.path("accounts")) {
            String u = acc.path("user").asText("");
            if (!Strings.isBlank(u) && !users.contains(u)) {
                users.add(u);
            }
        }
        return users;
    }

    private JsonNode findInstance(String dbId) {
        // Defensive: a request with a duplicated db_id query param (?db_id=x&db_id=x) gets bound by
        // Spring as a single comma-joined string ("x,x"), which would otherwise silently match no
        // instance and fall through to the SYS/oracle.env default instead of the one actually picked.
        if (dbId != null && dbId.indexOf(',') >= 0) {
            dbId = dbId.substring(0, dbId.indexOf(','));
        }
        if (config != null && config.has("groups")) {
            for (JsonNode group : config.get("groups")) {
                for (JsonNode inst : group.path("instances")) {
                    if (inst.path("id").asText("").equals(dbId)) {
                        return inst;
                    }
                }
            }
        }
        return null;
    }

    private TargetDbConfig fromInstance(JsonNode inst) {
        return new TargetDbConfig(
                inst.path("id").asText(),
                inst.path("name").asText(""),
                inst.path("user").asText("SYS"),
                resolvePassword(inst.path("password").asText("")),
                inst.path("host").asText(""),
                inst.path("port").asInt(1521),
                inst.path("sid").asText("ORCL"),
                inst.hasNonNull("pool_min_idle") ? inst.path("pool_min_idle").asInt() : null,
                inst.hasNonNull("pool_max_size") ? inst.path("pool_max_size").asInt() : null);
    }

    /** Java port of api_server.py's /api/config: same groups/instances, with passwords stripped. */
    public Map<String, Object> safeConfig() {
        List<Map<String, Object>> groups = new ArrayList<>();
        if (config != null && config.has("groups")) {
            for (JsonNode group : config.get("groups")) {
                Map<String, Object> safeGroup = new LinkedHashMap<>();
                safeGroup.put("group_name", group.path("group_name").asText(""));
                List<Map<String, Object>> instances = new ArrayList<>();
                for (JsonNode inst : group.path("instances")) {
                    Map<String, Object> safeInst = new LinkedHashMap<>();
                    Iterator<Map.Entry<String, JsonNode>> fields = inst.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        if ("password".equals(field.getKey())) {
                            continue;
                        }
                        if ("accounts".equals(field.getKey())) {
                            // Strip each extra account's (still B64-obfuscated, but not real
                            // encryption) password too - this response goes to every logged-in
                            // user, not just admins, same as the top-level instance password above.
                            List<Map<String, Object>> safeAccounts = new ArrayList<>();
                            for (JsonNode acc : field.getValue()) {
                                Map<String, Object> safeAcc = new LinkedHashMap<>();
                                safeAcc.put("user", acc.path("user").asText(""));
                                safeAccounts.add(safeAcc);
                            }
                            safeInst.put("accounts", safeAccounts);
                            continue;
                        }
                        safeInst.put(field.getKey(), field.getValue().isTextual() ? field.getValue().asText() : field.getValue());
                    }
                    instances.add(safeInst);
                }
                safeGroup.put("instances", instances);
                groups.add(safeGroup);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", groups);
        return result;
    }

    private TargetDbConfig loadOracleEnvFallback(File file) {
        Map<String, String> kv = new HashMap<>();
        if (file.exists()) {
            try {
                for (String line : Files.readAllLines(file.toPath())) {
                    int idx = line.indexOf('=');
                    if (idx > 0) {
                        kv.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                    }
                }
            } catch (IOException ignored) {
                // fall through to defaults below
            }
        }
        int port;
        try {
            port = Integer.parseInt(kv.getOrDefault("PORT", "1521"));
        } catch (NumberFormatException e) {
            port = 1521;
        }
        return new TargetDbConfig(
                "default",
                "Default (oracle.env)",
                kv.getOrDefault("USER", "SYS"),
                resolvePassword(kv.getOrDefault("PASSWORD", "")),
                kv.getOrDefault("HOST", ""),
                port,
                kv.getOrDefault("SID", "ORCL"),
                null,
                null);
    }

    // Not real encryption - just keeps the raw password out of a plain-text glance at the file.
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
    public Map<String, Object> createInstance(String groupName, String id, String name, String host, int port,
            String sid, String user, String password, Integer poolMinIdle, Integer poolMaxSize,
            List<Map<String, String>> accounts, List<Integer> sessionThresholds) {
        if (Strings.isBlank(id)) {
            return Maps.of("success", false, "message", "ID는 필수입니다.");
        }
        if (Strings.isBlank(groupName)) {
            return Maps.of("success", false, "message", "그룹명은 필수입니다.");
        }
        if (Strings.isBlank(password)) {
            return Maps.of("success", false, "message", "비밀번호는 필수입니다.");
        }
        synchronized (writeLock) {
            ObjectNode root = ((ObjectNode) config).deepCopy();
            ArrayNode groups = ensureGroupsArray(root);
            if (findInstanceNode(groups, id) != null) {
                return Maps.of("success", false, "message", "이미 존재하는 ID입니다: " + id);
            }
            ArrayNode instances = ensureInstancesArray(findOrCreateGroup(groups, groupName));
            ObjectNode inst = mapper.createObjectNode();
            inst.put("id", id);
            inst.put("name", name == null ? "" : name);
            inst.put("host", host == null ? "" : host);
            inst.put("port", port);
            inst.put("sid", sid == null ? "" : sid);
            inst.put("user", user == null ? "" : user);
            inst.put("password", encodePassword(password));
            putNullableInt(inst, "pool_min_idle", poolMinIdle);
            putNullableInt(inst, "pool_max_size", poolMaxSize);
            // A brand-new instance has no stored accounts to fall back to, so every row here needs
            // its own non-blank password.
            String accountsError = applyAccounts(inst, accounts, null);
            if (accountsError != null) {
                return Maps.of("success", false, "message", accountsError);
            }
            String thresholdsError = applySessionThresholds(inst, sessionThresholds);
            if (thresholdsError != null) {
                return Maps.of("success", false, "message", thresholdsError);
            }
            instances.add(inst);
            return persist(root, "DB가 추가되었습니다.");
        }
    }

    /** Admin UI: update an existing instance's fields. Blank/null password keeps the stored value. */
    public Map<String, Object> updateInstance(String id, String name, String host, int port, String sid,
            String user, String password, Integer poolMinIdle, Integer poolMaxSize,
            List<Map<String, String>> accounts, List<Integer> sessionThresholds) {
        synchronized (writeLock) {
            ObjectNode root = ((ObjectNode) config).deepCopy();
            ArrayNode groups = ensureGroupsArray(root);
            ObjectNode inst = findInstanceNode(groups, id);
            if (inst == null) {
                return Maps.of("success", false, "message", "존재하지 않는 DB입니다: " + id);
            }
            JsonNode existingAccounts = inst.path("accounts");
            inst.put("name", name == null ? "" : name);
            inst.put("host", host == null ? "" : host);
            inst.put("port", port);
            inst.put("sid", sid == null ? "" : sid);
            inst.put("user", user == null ? "" : user);
            if (!Strings.isBlank(password)) {
                inst.put("password", encodePassword(password));
            }
            putNullableInt(inst, "pool_min_idle", poolMinIdle);
            putNullableInt(inst, "pool_max_size", poolMaxSize);
            // A blank password on an existing account row keeps that account's stored password.
            String accountsError = applyAccounts(inst, accounts, existingAccounts);
            if (accountsError != null) {
                return Maps.of("success", false, "message", accountsError);
            }
            String thresholdsError = applySessionThresholds(inst, sessionThresholds);
            if (thresholdsError != null) {
                return Maps.of("success", false, "message", thresholdsError);
            }
            return persist(root, "DB 정보가 수정되었습니다.");
        }
    }

    /** Admin UI: remove an instance; removes its group too if that was the group's last instance. */
    public Map<String, Object> deleteInstance(String id) {
        synchronized (writeLock) {
            ObjectNode root = ((ObjectNode) config).deepCopy();
            ArrayNode groups = ensureGroupsArray(root);
            boolean removed = false;
            Iterator<JsonNode> groupIt = groups.iterator();
            while (groupIt.hasNext()) {
                JsonNode group = groupIt.next();
                if (!(group.path("instances") instanceof ArrayNode)) {
                    continue;
                }
                ArrayNode instances = (ArrayNode) group.path("instances");
                for (int i = 0; i < instances.size(); i++) {
                    if (id.equals(instances.get(i).path("id").asText(""))) {
                        instances.remove(i);
                        removed = true;
                        break;
                    }
                }
                if (removed) {
                    if (instances.isEmpty()) {
                        groupIt.remove();
                    }
                    break;
                }
            }
            if (!removed) {
                return Maps.of("success", false, "message", "존재하지 않는 DB입니다: " + id);
            }
            return persist(root, "DB가 삭제되었습니다.");
        }
    }

    private ArrayNode ensureGroupsArray(ObjectNode root) {
        JsonNode existing = root.get("groups");
        if (existing instanceof ArrayNode) {
            return (ArrayNode) existing;
        }
        return root.putArray("groups");
    }

    private ArrayNode ensureInstancesArray(ObjectNode group) {
        JsonNode existing = group.get("instances");
        if (existing instanceof ArrayNode) {
            return (ArrayNode) existing;
        }
        return group.putArray("instances");
    }

    private ObjectNode findOrCreateGroup(ArrayNode groups, String groupName) {
        for (JsonNode group : groups) {
            if (groupName.equals(group.path("group_name").asText(""))) {
                return (ObjectNode) group;
            }
        }
        ObjectNode group = mapper.createObjectNode();
        group.put("group_name", groupName);
        group.putArray("instances");
        groups.add(group);
        return group;
    }

    private ObjectNode findInstanceNode(ArrayNode groups, String id) {
        for (JsonNode group : groups) {
            for (JsonNode inst : group.path("instances")) {
                if (id.equals(inst.path("id").asText(""))) {
                    return (ObjectNode) inst;
                }
            }
        }
        return null;
    }

    private void putNullableInt(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /**
     * Rebuilds inst's "accounts" array from the admin UI's rows. A row with a blank password reuses
     * the matching user's already-stored (still B64-encoded) password from existingAccounts, if any -
     * this is how "leave blank to keep unchanged" works for account rows shown to the admin without
     * ever sending a decoded password back to the browser. Returns an error message, or null on success.
     */
    private String applyAccounts(ObjectNode inst, List<Map<String, String>> accounts, JsonNode existingAccounts) {
        if (accounts == null || accounts.isEmpty()) {
            inst.remove("accounts");
            return null;
        }
        ArrayNode accArr = mapper.createArrayNode();
        for (Map<String, String> acc : accounts) {
            String accUser = acc.get("user");
            if (Strings.isBlank(accUser)) {
                continue;
            }
            String accPassword = acc.get("password");
            String encoded;
            if (!Strings.isBlank(accPassword)) {
                encoded = encodePassword(accPassword);
            } else {
                encoded = existingAccounts == null ? null : findAccountPassword(existingAccounts, accUser);
                if (encoded == null) {
                    return "추가 계정 '" + accUser + "'의 비밀번호를 입력하세요.";
                }
            }
            ObjectNode accNode = mapper.createObjectNode();
            accNode.put("user", accUser);
            accNode.put("password", encoded);
            accArr.add(accNode);
        }
        if (accArr.size() > 0) {
            inst.set("accounts", accArr);
        } else {
            inst.remove("accounts");
        }
        return null;
    }

    private String findAccountPassword(JsonNode existingAccounts, String user) {
        for (JsonNode acc : existingAccounts) {
            if (user.equals(acc.path("user").asText(""))) {
                return acc.path("password").asText(null);
            }
        }
        return null;
    }

    /**
     * "session_thresholds": [t1..t5] - per-instance override of the dashboard's active-session
     * color/gauge thresholds (see app.js DEFAULT_SESSION_THRESHOLDS). Null/empty from the admin UI
     * means "don't override" (field removed, global default applies); otherwise exactly 5 values are
     * required, matching what app.js's getSessColor() expects.
     */
    private String applySessionThresholds(ObjectNode inst, List<Integer> sessionThresholds) {
        if (sessionThresholds == null || sessionThresholds.isEmpty()) {
            inst.remove("session_thresholds");
            return null;
        }
        if (sessionThresholds.size() != 5 || sessionThresholds.contains(null)) {
            return "세션 임계치는 5개 값을 모두 입력해야 합니다.";
        }
        ArrayNode arr = mapper.createArrayNode();
        for (Integer t : sessionThresholds) {
            arr.add(t);
        }
        inst.set("session_thresholds", arr);
        return null;
    }

    /** Writes root to databases.json and swaps it in as the live in-memory config on success. */
    private Map<String, Object> persist(ObjectNode root, String successMessage) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(databasesConfigPath), root);
        } catch (IOException e) {
            return Maps.of("success", false, "message", "파일 저장 실패: " + e.getMessage());
        }
        config = root;
        return Maps.of("success", true, "message", successMessage);
    }
}
