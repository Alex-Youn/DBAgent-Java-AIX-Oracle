package com.dbagent.oracle;

import com.dbagent.util.Strings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                        if (!"password".equals(field.getKey())) {
                            safeInst.put(field.getKey(), field.getValue().isTextual() ? field.getValue().asText() : field.getValue());
                        }
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
}
