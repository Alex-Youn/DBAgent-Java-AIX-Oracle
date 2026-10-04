package com.dbagent.settings;

import com.dbagent.auth.AuthService;
import com.dbagent.oracle.DatabaseConfigService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 환경설정 화면(app-settings.html)용 API (환경설정 DB화 P1-4 조회 + P2 저장·초기화·이력·내보내기, 2026-10-04,
 * 설계문서/환경설정_DB화_설계_2026-10-04.md 7절). 모두 관리자 전용이다.
 *
 * <p>관리자용 기존 API(/api/db_configs 등)와 같은 snake_case 이고, 오류는 HTTP 상태 + {"error": 한국어 사유}
 * (+ 항목별 오류는 "field_errors": {키: 사유}). 저장은 검증을 전부 통과해야 쓰고(400), 같은 항목을 다른 관리자가 먼저
 * 바꿨으면 409 로 막는다.
 */
@RestController
public class AppSettingsController {

    /** 화면에 읽기 전용으로 보여주는 부트스트랩 항목 (conf/dbagent.conf 대상). {키, 설명}. */
    private static final String[][] BOOTSTRAP = {
            {"server.port", "웹 서버 포트"},
            {"spring.datasource.url", "로그인 계정·세션 DB 파일 위치"},
            {"dbagent.dbconfig.datasource.url", "환경설정·등록 DB 목록을 저장하는 DB 파일 위치 (이 화면의 값이 저장되는 곳)"},
            {"dbagent.metrics.datasource.url", "추이 시계열 DB 파일 위치"},
            {"dbagent.perf.datasource.url", "성능 분석 DB 파일 위치 (지정하지 않으면 metrics 와 같은 폴더에 perf 이름으로 만든다)"},
            {"dbagent.security.key-file", "대상 DB 비밀번호 암호화 마스터 키 파일 (비우면 작업 디렉터리의 dbagent.key). 환경변수 DBAGENT_MASTER_KEY 가 우선"},
            {"aidba.errors-db-path", "AI DBA 에러 사전 DB 위치"}
    };

    private static final int HISTORY_DEFAULT_LIMIT = 100;
    private static final int HISTORY_MAX_LIMIT = 500;

    private final AuthService authService;
    private final SettingsService settings;
    private final DatabaseConfigService configService;
    private final AppSettingStore store;

    public AppSettingsController(AuthService authService, SettingsService settings, DatabaseConfigService configService,
            AppSettingStore store) {
        this.authService = authService;
        this.settings = settings;
        this.configService = configService;
        this.store = store;
    }

    // ───────────────────────── 조회 ─────────────────────────

    @GetMapping("/api/app_settings")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "token", required = false) String token,
            @RequestParam(value = "db_id", required = false) String dbId) {
        ResponseEntity<Map<String, Object>> denied = requireAdmin(token);
        if (denied != null) {
            return denied;
        }
        List<Map<String, Object>> dbs = oracleDbs();
        String scope = blankToNull(dbId);
        if (scope != null && !containsId(dbs, scope)) {
            return error(HttpStatus.NOT_FOUND, "등록되지 않은 Oracle DB 입니다: " + scope);
        }

        Map<SettingKey, SettingValue> resolved = settings.resolveAll(scope);
        Map<SettingGroup, List<Map<String, Object>>> byGroup = new EnumMap<SettingGroup, List<Map<String, Object>>>(SettingGroup.class);
        for (SettingKey k : SettingKey.values()) {
            if (k.hidden() || !k.group().visible()) {
                continue;
            }
            List<Map<String, Object>> items = byGroup.get(k.group());
            if (items == null) {
                items = new ArrayList<Map<String, Object>>();
                byGroup.put(k.group(), items);
            }
            items.add(item(k, resolved.get(k), scope));
        }
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        for (SettingGroup g : SettingGroup.values()) {
            List<Map<String, Object>> items = byGroup.get(g);
            if (items == null) {
                continue;
            }
            boolean anyPerDb = false;
            for (SettingKey k : SettingKey.values()) {
                if (k.group() == g && k.perDb() && !k.hidden()) {
                    anyPerDb = true;
                    break;
                }
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", g.id());
            m.put("label", g.label());
            m.put("description", g.description());
            m.put("per_db", anyPerDb);
            m.put("items", items);
            groups.add(m);
        }

        List<Map<String, Object>> stale = new ArrayList<Map<String, Object>>();
        for (SettingsService.FileDiff d : settings.staleFileValues()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("key", d.key().key());
            m.put("label", d.key().label());
            m.put("file_value", d.fileValue());
            m.put("db_value", d.dbValue());
            stale.add(m);
        }

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ignore_db", settings.isIgnoreDb());
        body.put("db_id", scope);
        body.put("dbs", dbs);
        body.put("groups", groups);
        body.put("bootstrap", bootstrap());
        body.put("stale_file_values", stale);
        return ResponseEntity.ok(body);
    }

    // ───────────────────────── 저장 · 초기화 ─────────────────────────

    /** body: {token, db_id?, changes:{키:값}, versions:{키:버전}, reason?} */
    @PutMapping("/api/app_settings")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        String token = asString(body.get("token"));
        ResponseEntity<Map<String, Object>> denied = requireAdmin(token);
        if (denied != null) {
            return denied;
        }
        String scope = blankToNull(asString(body.get("db_id")));
        ResponseEntity<Map<String, Object>> badScope = checkScope(scope);
        if (badScope != null) {
            return badScope;
        }
        Map<String, String> changes = new LinkedHashMap<String, String>();
        Object rawChanges = body.get("changes");
        if (rawChanges instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) rawChanges).entrySet()) {
                changes.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
        }
        Map<String, Long> versions = new LinkedHashMap<String, Long>();
        Object rawVersions = body.get("versions");
        if (rawVersions instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) rawVersions).entrySet()) {
                if (e.getValue() instanceof Number) {
                    versions.put(String.valueOf(e.getKey()), ((Number) e.getValue()).longValue());
                }
            }
        }
        SettingsService.ApplyResult r = settings.apply(scope, changes, versions, usernameOf(token), asString(body.get("reason")));
        return toResponse(r);
    }

    /** body: {token, db_id?, keys:[키...], reason?} - 저장된 값을 지워 기본값(DB 별이면 전역값)으로 되돌린다. */
    @PostMapping("/api/app_settings/reset")
    public ResponseEntity<Map<String, Object>> reset(@RequestBody Map<String, Object> body) {
        String token = asString(body.get("token"));
        ResponseEntity<Map<String, Object>> denied = requireAdmin(token);
        if (denied != null) {
            return denied;
        }
        String scope = blankToNull(asString(body.get("db_id")));
        ResponseEntity<Map<String, Object>> badScope = checkScope(scope);
        if (badScope != null) {
            return badScope;
        }
        List<String> keys = new ArrayList<String>();
        Object rawKeys = body.get("keys");
        if (rawKeys instanceof List) {
            for (Object o : (List<?>) rawKeys) {
                keys.add(String.valueOf(o));
            }
        }
        return toResponse(settings.reset(scope, keys, usernameOf(token), asString(body.get("reason"))));
    }

    // ───────────────────────── 이력 · 내보내기 ─────────────────────────

    @GetMapping("/api/app_settings/history")
    public ResponseEntity<Map<String, Object>> history(
            @RequestParam(value = "token", required = false) String token,
            @RequestParam(value = "key", required = false) String key,
            @RequestParam(value = "db_id", required = false) String dbId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        ResponseEntity<Map<String, Object>> denied = requireAdmin(token);
        if (denied != null) {
            return denied;
        }
        int n = limit == null ? HISTORY_DEFAULT_LIMIT : Math.max(1, Math.min(limit, HISTORY_MAX_LIMIT));
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : store.history(blankToNull(key), blankToNull(dbId), n)) {
            String k = String.valueOf(row.get("setting_key"));
            SettingKey def = SettingKey.byKey(k);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", row.get("id"));
            m.put("changed_at", row.get("changed_at"));
            m.put("changed_by", row.get("changed_by"));
            m.put("action", row.get("action"));
            m.put("key", k);
            m.put("label", def == null ? k : def.label());
            m.put("db_id", row.get("db_id"));
            m.put("old_value", row.get("old_value"));
            m.put("new_value", row.get("new_value"));
            m.put("reason", row.get("reason"));
            out.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("items", out);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/app_settings/export")
    public ResponseEntity<?> export(@RequestParam(value = "token", required = false) String token) {
        ResponseEntity<Map<String, Object>> denied = requireAdmin(token);
        if (denied != null) {
            return denied;
        }
        String name = "dbagent-settings-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".properties";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(settings.exportProperties());
    }

    // ───────────────────────── 내부 ─────────────────────────

    private Map<String, Object> item(SettingKey k, SettingValue v, String scope) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", k.key());
        m.put("label", k.label());
        m.put("summary", k.summary());
        m.put("detail", k.detail());
        m.put("warning", k.warning());
        m.put("type", k.type().name());
        m.put("unit", k.unit());
        m.put("min", k.min());
        m.put("max", k.max());
        m.put("default", k.defaultValue());
        m.put("value", k.secret() ? null : v.value());
        m.put("is_set", k.secret() ? Boolean.valueOf(v.value() != null && !v.value().isEmpty()) : null);
        m.put("source", v.source().name());
        m.put("source_label", v.source().label());
        m.put("locked", v.source().locksOverDb());
        m.put("editable", !v.source().locksOverDb() && !k.secret() && !settings.isIgnoreDb());
        m.put("per_db", k.perDb());
        m.put("danger", k.danger());
        m.put("version", settings.rowVersion(k, scope));
        // 적용 방식: live(저장 즉시) / restart(재시작 후) / none(아직 어디에서도 읽지 않아 저장만 됨).
        // DB별 값은 소비자 코드가 아직 DB별로 읽지 않아 P3 전까지는 저장만 된다.
        String applies;
        if (k.unwired() || (scope != null && k.perDb())) {
            applies = "none";
        } else {
            applies = k.live() ? "live" : "restart";
        }
        m.put("applies", applies);
        m.put("requires_restart", k.requiresRestart());
        m.put("restart_pending", scope == null && settings.restartPending(k));
        return m;
    }

    private List<Map<String, Object>> bootstrap() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (String[] b : BOOTSTRAP) {
            SettingsService.EnvValue ev = settings.environmentValue(b[0]);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("key", b[0]);
            m.put("description", b[1]);
            if (ev == null) {
                // 값이 없는 키의 의미를 화면에 그대로 적는다 ("(비어 있음)" 으로 보이면 설정 누락으로 오해한다).
                m.put("value", "dbagent.perf.datasource.url".equals(b[0]) ? "(지정 안 함 - metrics 와 같은 폴더에 perf 이름으로 생성)"
                        : "dbagent.security.key-file".equals(b[0]) ? "(지정 안 함 - 작업 디렉터리의 dbagent.key)" : null);
                m.put("source_label", "코드 기본값");
            } else {
                m.put("value", ev.value());
                m.put("source_label", ev.source().label());
            }
            out.add(m);
        }
        return out;
    }

    /** DB 별 재정의 대상이 되는 Oracle DB 목록 (Lock 실시간은 Oracle 전용). */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> oracleDbs() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Object groups = configService.safeConfig().get("groups");
        if (!(groups instanceof List)) {
            return out;
        }
        for (Object g : (List<Object>) groups) {
            Object instances = ((Map<String, Object>) g).get("instances");
            if (!(instances instanceof List)) {
                continue;
            }
            for (Object i : (List<Object>) instances) {
                Map<String, Object> inst = (Map<String, Object>) i;
                Object type = inst.get("db_type");
                if (type != null && !"oracle".equalsIgnoreCase(String.valueOf(type))) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", inst.get("id"));
                m.put("name", inst.get("name"));
                out.add(m);
            }
        }
        return out;
    }

    private ResponseEntity<Map<String, Object>> requireAdmin(String token) {
        if (token == null || token.trim().isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        if (!authService.isAdmin(token)) {
            return error(HttpStatus.FORBIDDEN, "관리자만 환경설정을 볼 수 있습니다.");
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> checkScope(String scope) {
        if (scope != null && !containsId(oracleDbs(), scope)) {
            return error(HttpStatus.NOT_FOUND, "등록되지 않은 Oracle DB 입니다: " + scope);
        }
        return null;
    }

    private String usernameOf(String token) {
        return authService.sessionForToken(token).map(s -> s.username()).orElse("unknown");
    }

    private ResponseEntity<Map<String, Object>> toResponse(SettingsService.ApplyResult r) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        if (r.ok()) {
            body.put("ok", Boolean.TRUE);
            body.put("changed", r.changedKeys());
            body.put("unchanged", r.unchanged());
            return ResponseEntity.ok(body);
        }
        body.put("error", r.error());
        if (!r.fieldErrors().isEmpty()) {
            body.put("field_errors", r.fieldErrors());
        }
        return ResponseEntity.status(r.status()).body(body);
    }

    private static boolean containsId(List<Map<String, Object>> dbs, String id) {
        for (Map<String, Object> d : dbs) {
            if (id.equals(String.valueOf(d.get("id")))) {
                return true;
            }
        }
        return false;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.<String, Object>singletonMap("error", message));
    }
}
