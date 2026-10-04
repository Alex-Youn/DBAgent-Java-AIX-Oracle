package com.dbagent.settings;

import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 환경설정 조회 서비스 (환경설정 DB화 P1-2, 2026-10-04, 설계문서/환경설정_DB화_설계_2026-10-04.md 5절).
 *
 * <p>값 우선순위(높은 쪽부터): 기동 인자·-D·환경변수 &gt; conf/dbagent.conf &gt; DB(DB별 &gt; 전역) &gt;
 * 옛 외부 application.properties &gt; jar 번들 &gt; 코드 기본값. 앞의 두 출처는 "고정"이라 DB 값이 있어도 이긴다
 * (잘못 저장한 값 때문에 화면에 못 들어갈 때 기동 인자로 덮어쓰는 비상 탈출구).
 *
 * <p>DB 저장값은 {@link #reload()} 때 한 번 읽어 불변 스냅샷으로 들고 있다 - 조회는 맵 한 번 읽기라 3초 주기
 * 폴링 경로에서 써도 DB 를 치지 않는다. 환경(Environment)은 기동 후 바뀌지 않으므로 기동 때 한 번만 훑는다.
 *
 * <p>스냅샷에 담을 때 키 정의({@link SettingKey})로 검증해 범위를 벗어났거나 모르는 키·DB별 불가 키는 경고 로그와
 * 함께 버린다(그 값은 다음 출처로 넘어간다). DB 를 직접 고쳤을 때도 잘못된 값이 조회로 새지 않게 하려는 것.
 *
 * <p>현재 소비자 코드는 아직 기존 {@code @Value} 를 쓴다. 키를 하나씩 이 서비스 조회로 옮긴다(P3).
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    /**
     * 기동 시 DB 값을 Spring Environment 에 주입할 때 쓰는 PropertySource 이름(P1-3). 이 서비스는 DB 값을
     * 자기 스냅샷으로 따로 들고 있으므로 환경을 훑을 때 이 출처는 건너뛴다(안 그러면 DB 값이 "번들" 로 보인다).
     */
    public static final String DB_INJECTED_SOURCE_NAME = "dbagent-settings-db";

    private final Environment environment;
    private final AppSettingStore store;
    private final Map<SettingKey, EnvHit> envHits = new EnumMap<SettingKey, EnvHit>(SettingKey.class);
    private volatile Snapshot snapshot = new Snapshot(Collections.<SettingKey, AppSettingStore.Row>emptyMap(),
            Collections.<String, Map<SettingKey, AppSettingStore.Row>>emptyMap());

    public SettingsService(Environment environment, AppSettingStore store) {
        this.environment = environment;
        this.store = store;
    }

    @PostConstruct
    void init() {
        verifyRegistry();
        scanEnvironment();
        reload();
    }

    /** 환경(파일·번들·인자)에서 찾은 값과 그 출처. */
    private static final class EnvHit {
        final String value;
        final SettingSource source;

        EnvHit(String value, SettingSource source) {
            this.value = value;
            this.source = source;
        }
    }

    private static final class Snapshot {
        final Map<SettingKey, AppSettingStore.Row> global;
        final Map<String, Map<SettingKey, AppSettingStore.Row>> perDb;

        Snapshot(Map<SettingKey, AppSettingStore.Row> global, Map<String, Map<SettingKey, AppSettingStore.Row>> perDb) {
            this.global = global;
            this.perDb = perDb;
        }
    }

    // ───────────────────────── 조회 ─────────────────────────

    public SettingValue resolve(SettingKey key) {
        return resolve(key, null);
    }

    /**
     * @param dbId 대상 DB. null 이면 전역값. key 가 DB별 재정의를 지원하지 않으면 무시한다.
     */
    public SettingValue resolve(SettingKey key, String dbId) {
        EnvHit hit = envHits.get(key);
        boolean envLocked = hit != null && hit.source.locksOverDb();
        if (!envLocked) {
            Snapshot s = snapshot;
            if (key.perDb() && dbId != null) {
                Map<SettingKey, AppSettingStore.Row> m = s.perDb.get(dbId);
                AppSettingStore.Row row = m == null ? null : m.get(key);
                if (row != null) {
                    return new SettingValue(key, row.value(), SettingSource.DB_PER_DB);
                }
            }
            AppSettingStore.Row g = s.global.get(key);
            if (g != null) {
                return new SettingValue(key, g.value(), SettingSource.DB_GLOBAL);
            }
        }
        if (hit != null) {
            return new SettingValue(key, hit.value, hit.source);
        }
        return new SettingValue(key, key.defaultValue(), SettingSource.CODE_DEFAULT);
    }

    /** 모든 키의 유효값 (화면 목록용). dbId 가 null 이면 전역 기준. */
    public Map<SettingKey, SettingValue> resolveAll(String dbId) {
        Map<SettingKey, SettingValue> out = new LinkedHashMap<SettingKey, SettingValue>();
        for (SettingKey k : SettingKey.values()) {
            out.put(k, resolve(k, dbId));
        }
        return out;
    }

    public int getInt(SettingKey key) { return getInt(key, null); }

    public int getInt(SettingKey key, String dbId) {
        return (int) getLong(key, dbId);
    }

    public long getLong(SettingKey key) { return getLong(key, null); }

    public long getLong(SettingKey key, String dbId) {
        requireType(key, SettingType.INT);
        return SettingKey.parseLong(resolve(key, dbId).value());
    }

    /** INT / DECIMAL 키를 실수로 읽는다 (사용률 % 등). */
    public double getDouble(SettingKey key) { return getDouble(key, null); }

    public double getDouble(SettingKey key, String dbId) {
        if (key.type() != SettingType.INT && key.type() != SettingType.DECIMAL) {
            throw new IllegalArgumentException(key.key() + " 는 숫자 키가 아닙니다: " + key.type());
        }
        return Double.parseDouble(resolve(key, dbId).value().trim());
    }

    public boolean getBool(SettingKey key) { return getBool(key, null); }

    public boolean getBool(SettingKey key, String dbId) {
        requireType(key, SettingType.BOOL);
        return Boolean.parseBoolean(resolve(key, dbId).value().trim());
    }

    /** STRING / URL / CSV 키를 문자열로 읽는다. */
    public String getString(SettingKey key) { return getString(key, null); }

    public String getString(SettingKey key, String dbId) {
        return resolve(key, dbId).value();
    }

    private static void requireType(SettingKey key, SettingType expected) {
        if (key.type() != expected) {
            throw new IllegalArgumentException(key.key() + " 의 종류는 " + key.type() + " 입니다 (요청: " + expected + ")");
        }
    }

    // ───────────────────────── 스냅샷 갱신 ─────────────────────────

    /** DB 저장값을 다시 읽어 스냅샷을 교체한다. 저장·초기화 직후와 기동 때 부른다. */
    public synchronized void reload() {
        Map<SettingKey, AppSettingStore.Row> global = new EnumMap<SettingKey, AppSettingStore.Row>(SettingKey.class);
        for (AppSettingStore.Row r : store.loadGlobal().values()) {
            SettingKey k = acceptable(r, null);
            if (k != null) {
                global.put(k, r);
            }
        }
        Map<String, Map<SettingKey, AppSettingStore.Row>> perDb = new HashMap<String, Map<SettingKey, AppSettingStore.Row>>();
        for (Map.Entry<String, Map<String, AppSettingStore.Row>> e : store.loadPerDb().entrySet()) {
            Map<SettingKey, AppSettingStore.Row> m = new EnumMap<SettingKey, AppSettingStore.Row>(SettingKey.class);
            for (AppSettingStore.Row r : e.getValue().values()) {
                SettingKey k = acceptable(r, e.getKey());
                if (k != null) {
                    m.put(k, r);
                }
            }
            if (!m.isEmpty()) {
                perDb.put(e.getKey(), Collections.unmodifiableMap(m));
            }
        }
        snapshot = new Snapshot(Collections.unmodifiableMap(global), Collections.unmodifiableMap(perDb));
        int perDbCount = 0;
        for (Map<SettingKey, AppSettingStore.Row> m : perDb.values()) {
            perDbCount += m.size();
        }
        log.info("환경설정 스냅샷 갱신: DB 저장 전역 {}건, DB별 재정의 {}건 ({}개 DB)", global.size(), perDbCount, perDb.size());
    }

    /** 저장된 행이 쓸 수 있는 값이면 키를, 아니면 경고 후 null. */
    private SettingKey acceptable(AppSettingStore.Row row, String dbId) {
        String where = dbId == null ? "" : " @" + dbId;
        SettingKey k = SettingKey.byKey(row.key());
        if (k == null) {
            log.warn("환경설정 DB 에 모르는 키가 있어 무시합니다: {}{}", row.key(), where);
            return null;
        }
        if (dbId != null && !k.perDb()) {
            log.warn("DB별 재정의를 지원하지 않는 키라 무시합니다: {}{}", row.key(), where);
            return null;
        }
        String problem = k.validate(row.value());
        if (problem != null) {
            log.warn("환경설정 DB 값이 올바르지 않아 무시합니다: {}{} = '{}' ({})", row.key(), where,
                    k.secret() ? "***" : row.value(), problem);
            return null;
        }
        return k;
    }

    // ───────────────────────── 환경(Environment) 훑기 ─────────────────────────

    private void scanEnvironment() {
        if (!(environment instanceof ConfigurableEnvironment)) {
            log.warn("Environment 가 ConfigurableEnvironment 가 아니라 설정 출처를 구분하지 못합니다: {}", environment.getClass().getName());
            return;
        }
        ConfigurableEnvironment cenv = (ConfigurableEnvironment) environment;
        int locked = 0;
        Map<SettingSource, Integer> bySource = new EnumMap<SettingSource, Integer>(SettingSource.class);
        for (SettingKey k : SettingKey.values()) {
            for (PropertySource<?> ps : cenv.getPropertySources()) {
                String name = ps.getName();
                // configurationProperties 는 모든 출처를 비춰 주는 어댑터라 맨 앞에서 항상 걸린다 - 건너뛴다.
                if ("configurationProperties".equals(name) || DB_INJECTED_SOURCE_NAME.equals(name)) {
                    continue;
                }
                if (!ps.containsProperty(k.key())) {
                    continue;
                }
                // 건너뛴 출처가 같은 키를 갖고 있을 수 있어 environment.getProperty 대신 찾은 출처에서 직접 읽는다
                // (${...} 참조만 환경으로 풀어 준다).
                Object raw = ps.getProperty(k.key());
                if (raw == null) {
                    break;
                }
                String value = cenv.resolvePlaceholders(String.valueOf(raw));
                String problem = k.validate(value);
                if (problem != null) {
                    log.warn("설정 파일/인자 값이 올바르지 않아 무시합니다: {} = '{}' ({}) [출처 {}]", k.key(), value, problem, name);
                    break;
                }
                SettingSource src = classify(name);
                envHits.put(k, new EnvHit(value, src));
                Integer c = bySource.get(src);
                bySource.put(src, c == null ? 1 : c + 1);
                if (src.locksOverDb()) {
                    locked++;
                }
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<SettingSource, Integer> e : bySource.entrySet()) {
            sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey().label()).append(' ').append(e.getValue());
        }
        log.info("환경설정 출처 확인: 등록 키 {}개 중 환경에서 찾은 키 {}개 [{}] (그중 인자/conf 고정 {}개)",
                SettingKey.values().length, envHits.size(), sb, locked);
    }

    /** PropertySource 이름으로 출처를 나눈다 (Boot 2.7 / 3.x 공통). */
    static SettingSource classify(String name) {
        if (name.startsWith("commandLineArgs") || name.startsWith("systemProperties") || name.startsWith("systemEnvironment")) {
            return SettingSource.LOCKED_ARG;
        }
        if (name.contains("dbagent.conf")) {
            return SettingSource.CONF;
        }
        if (name.contains("class path resource")) {
            return SettingSource.BUNDLE;
        }
        return SettingSource.OLD_FILE;
    }

    /** 기본값이 키 정의와 맞는지 기동 때 확인한다 - 레지스트리 오타를 첫 기동에서 바로 잡으려는 것. */
    private void verifyRegistry() {
        for (SettingKey k : SettingKey.values()) {
            String problem = k.validate(k.defaultValue());
            if (problem != null) {
                throw new IllegalStateException("환경설정 키 기본값이 정의와 맞지 않습니다: " + k.key()
                        + " = '" + k.defaultValue() + "' (" + problem + ")");
            }
        }
    }
}
