package com.dbagent.settings;

import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    /** 안전 모드(-Ddbagent.settings.ignore-db=true): DB 저장값을 읽지도 이관하지도 않는다. */
    private volatile boolean ignoreDb;
    /** 옛 외부 파일 값이 DB 값과 달라 DB 값이 적용 중인 키 (화면 경고용). */
    private volatile List<FileDiff> staleFileValues = Collections.<FileDiff>emptyList();
    private volatile Snapshot snapshot = new Snapshot(Collections.<SettingKey, AppSettingStore.Row>emptyMap(),
            Collections.<String, Map<SettingKey, AppSettingStore.Row>>emptyMap());

    public SettingsService(Environment environment, AppSettingStore store) {
        this.environment = environment;
        this.store = store;
    }

    @PostConstruct
    void init() {
        verifyRegistry();
        ignoreDb = "true".equalsIgnoreCase(environment.getProperty(SettingsDbEnvironmentPostProcessor.IGNORE_DB_KEY, "false").trim());
        scanEnvironment();
        if (ignoreDb) {
            log.warn("안전 모드: DB 에 저장된 환경설정 값을 읽지도 이관하지도 않습니다 (파일·번들·기본값만 사용).");
            captureBootValues();
            return;
        }
        migrateFromOldFileIfNeeded();
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
        if (ignoreDb) {
            return;
        }
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
        notifyChanged();
        refreshStaleFileValues();
        if (bootValues.isEmpty()) {
            // 기동 직후 첫 스냅샷의 유효값 = 소비자 코드(@Value)가 지금 쓰고 있는 값
            captureBootValues();
        }
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

    // ───────────────────────── 저장 · 초기화 (P2) ─────────────────────────

    /** 저장·초기화 결과. status: 200 성공 / 400 입력 오류 / 409 버전 충돌·저장 불가. */
    public static final class ApplyResult {
        private final int status;
        private final String error;
        private final Map<String, String> fieldErrors;
        private final List<String> changedKeys;
        private final int unchanged;

        ApplyResult(int status, String error, Map<String, String> fieldErrors, List<String> changedKeys, int unchanged) {
            this.status = status;
            this.error = error;
            this.fieldErrors = fieldErrors == null ? Collections.<String, String>emptyMap() : fieldErrors;
            this.changedKeys = changedKeys == null ? Collections.<String>emptyList() : changedKeys;
            this.unchanged = unchanged;
        }

        public int status() { return status; }
        public String error() { return error; }
        public Map<String, String> fieldErrors() { return fieldErrors; }
        public List<String> changedKeys() { return changedKeys; }
        public int unchanged() { return unchanged; }
        public boolean ok() { return status == 200; }
    }

    /** 두 키의 크기 관계 규칙 (low &lt; high, 또는 low &lt;= high). 둘 중 하나라도 바뀔 때 유효값 기준으로 검사한다. */
    private static final class PairRule {
        final SettingKey low;
        final SettingKey high;
        final boolean strict;
        final String message;

        PairRule(SettingKey low, SettingKey high, boolean strict, String message) {
            this.low = low;
            this.high = high;
            this.strict = strict;
            this.message = message;
        }
    }

    private static final PairRule[] PAIR_RULES = {
            new PairRule(SettingKey.TM_HOLDER_WARN_COUNT, SettingKey.TM_HOLDER_INCIDENT_COUNT, true, "Holder 주의 개수는 장애 판정 Holder 수보다 작아야 합니다."),
            new PairRule(SettingKey.LOCK_TX_WAIT_WARN, SettingKey.LOCK_TX_WAIT_CRIT, true, "TX 대기 주의는 위험 기준보다 작아야 합니다."),
            new PairRule(SettingKey.LOCK_TM_WAIT_WARN, SettingKey.LOCK_TM_WAIT_CRIT, true, "TM 대기 주의는 위험 기준보다 작아야 합니다."),
            new PairRule(SettingKey.CHECK_TABLESPACE_WARN_PCT, SettingKey.CHECK_TABLESPACE_CRIT_PCT, true, "테이블스페이스 경고는 위험 기준보다 작아야 합니다."),
            new PairRule(SettingKey.CHECK_FRA_WARN_PCT, SettingKey.CHECK_FRA_CRIT_PCT, true, "FRA 경고는 위험 기준보다 작아야 합니다."),
            new PairRule(SettingKey.CHECK_TEMP_WARN_PCT, SettingKey.CHECK_TEMP_CRIT_PCT, true, "Temp 경고는 위험 기준보다 작아야 합니다."),
            new PairRule(SettingKey.SQL_RUNNER_MAX_ROWS, SettingKey.SQL_RUNNER_MAX_ROWS_LIMIT, false, "기본 조회 건수는 최대 조회 건수 이하여야 합니다."),
            new PairRule(SettingKey.ORACLE_POOL_MIN_IDLE, SettingKey.ORACLE_POOL_MAX_SIZE, false, "Oracle 풀 최소 유휴는 최대 크기 이하여야 합니다."),
            new PairRule(SettingKey.RDB_POOL_MIN_IDLE, SettingKey.RDB_POOL_MAX_SIZE, false, "RDB 풀 최소 유휴는 최대 크기 이하여야 합니다.")
    };

    /** 기동 시점의 유효값 - 재시작 전에 소비자 코드(@Value)가 실제로 쓰는 값이라 "재시작 대기" 판단에 쓴다. */
    private volatile Map<SettingKey, String> bootValues = Collections.<SettingKey, String>emptyMap();

    private void captureBootValues() {
        Map<SettingKey, String> m = new EnumMap<SettingKey, String>(SettingKey.class);
        for (SettingKey k : SettingKey.values()) {
            m.put(k, resolve(k, null).value());
        }
        bootValues = Collections.unmodifiableMap(m);
    }

    /** 저장된 값이 아직 실행 중인 앱에 적용되지 않았는지 (재시작 필요 키이고 기동 때 값과 다를 때). */
    public boolean restartPending(SettingKey key) {
        if (!key.requiresRestart()) {
            return false;
        }
        String boot = bootValues.get(key);
        return boot != null && !sameValue(key, boot, resolve(key, null).value());
    }

    /** 이 범위(전역 또는 DB별)에 저장된 행의 버전. 행이 없으면 0. 동시 수정 감지용. */
    public long rowVersion(SettingKey key, String dbId) {
        Snapshot s = snapshot;
        if (dbId != null && key.perDb()) {
            Map<SettingKey, AppSettingStore.Row> m = s.perDb.get(dbId);
            AppSettingStore.Row r = m == null ? null : m.get(key);
            return r == null ? 0L : r.version();
        }
        AppSettingStore.Row g = s.global.get(key);
        return g == null ? 0L : g.version();
    }

    /**
     * DB 에 저장된 값을 properties 형식 텍스트로 내보낸다 (롤백·백업용). 전역 값은 그대로 옛 application.properties 에
     * 붙여 쓸 수 있고, DB별 재정의는 properties 로 표현할 수 없어 주석으로만 적는다. 비밀값은 제외한다.
     */
    public String exportProperties() {
        Snapshot s = snapshot;
        StringBuilder sb = new StringBuilder();
        sb.append("# DBAgent 환경설정 내보내기 - DB 에 저장된(기본값과 다른) 전역 값만 담았습니다.\n");
        sb.append("# 롤백할 때 이 내용을 옛 application.properties 에 붙여 쓸 수 있습니다.\n");
        for (Map.Entry<SettingKey, AppSettingStore.Row> e : s.global.entrySet()) {
            if (!e.getKey().secret()) {
                sb.append(e.getKey().key()).append('=').append(e.getValue().value()).append('\n');
            }
        }
        boolean header = false;
        for (Map.Entry<String, Map<SettingKey, AppSettingStore.Row>> d : s.perDb.entrySet()) {
            for (Map.Entry<SettingKey, AppSettingStore.Row> e : d.getValue().entrySet()) {
                if (!header) {
                    sb.append("\n# --- DB별 재정의 (properties 로는 복원할 수 없는 참고용) ---\n");
                    header = true;
                }
                sb.append("# [").append(d.getKey()).append("] ").append(e.getKey().key()).append('=').append(e.getValue().value()).append('\n');
            }
        }
        return sb.toString();
    }

    /** [기본값 복원] 으로 DB 저장값을 지웠을 때 돌아가는 값과 그 출처 (옛 파일·번들 값, 없으면 코드 기본값). 화면 표시용. */
    public EnvValue restoreValue(SettingKey key) {
        EnvHit hit = envHits.get(key);
        if (hit != null && !hit.source.locksOverDb()) {
            return new EnvValue(hit.value, hit.source);
        }
        return new EnvValue(key.defaultValue(), SettingSource.CODE_DEFAULT);
    }

    /** DB 값을 걷어냈을 때 돌아가는 값 (옛 파일·번들 값, 없으면 코드 기본값). */
    private String baseValue(SettingKey key) {
        EnvHit hit = envHits.get(key);
        return hit != null ? hit.value : key.defaultValue();
    }

    /**
     * 값을 저장한다 (전부 검증을 통과해야 쓴다). 새 값이 "DB 를 걷어냈을 때의 값"과 같으면 행을 지운다 -
     * 기본값과 다른 값만 저장한다는 원칙(옛 기본값이 DB 에 굳지 않게). DB별 저장은 전역 유효값과 같으면 재정의를 지운다.
     *
     * <p>쓰기는 항목마다 따로 커밋된다(전체 롤백 없음) - 검증을 먼저 전부 끝내 두어 중간 실패 가능성을 낮춘다.
     */
    public synchronized ApplyResult apply(String dbId, Map<String, String> changes, Map<String, Long> versions, String user, String reason) {
        if (ignoreDb) {
            return new ApplyResult(409, "안전 모드로 기동 중이라 저장할 수 없습니다. 안전 모드(-Ddbagent.settings.ignore-db)를 빼고 재기동하세요.", null, null, 0);
        }
        if (changes == null || changes.isEmpty()) {
            return new ApplyResult(400, "변경할 항목이 없습니다.", null, null, 0);
        }
        Map<String, String> errors = new LinkedHashMap<String, String>();
        Map<SettingKey, String> parsed = new LinkedHashMap<SettingKey, String>();
        for (Map.Entry<String, String> e : changes.entrySet()) {
            SettingKey k = SettingKey.byKey(e.getKey());
            String v = e.getValue() == null ? "" : e.getValue().trim();
            String problem = editProblem(k, dbId);
            if (problem == null) {
                problem = k.validate(v);
            }
            if (problem != null) {
                errors.put(e.getKey(), problem);
            } else {
                parsed.put(k, v);
            }
        }
        // 키 사이 관계 (주의 < 위험 등) - 바뀌는 키가 있는 규칙만, 유효값 기준
        for (PairRule r : PAIR_RULES) {
            if (dbId != null && !r.low.perDb()) {
                continue;
            }
            if (!parsed.containsKey(r.low) && !parsed.containsKey(r.high)) {
                continue;
            }
            if (errors.containsKey(r.low.key()) || errors.containsKey(r.high.key())) {
                continue;
            }
            String lo = parsed.containsKey(r.low) ? parsed.get(r.low) : resolve(r.low, dbId).value();
            String hi = parsed.containsKey(r.high) ? parsed.get(r.high) : resolve(r.high, dbId).value();
            int c = compareNumbers(lo, hi);
            if (r.strict ? c >= 0 : c > 0) {
                String field = parsed.containsKey(r.low) ? r.low.key() : r.high.key();
                errors.put(field, r.message + " (적용될 값: " + lo + " / " + hi + ")");
            }
        }
        if (reason != null && reason.trim().length() > 1000) {
            return new ApplyResult(400, "변경 사유는 1000자 이내로 입력하세요.", null, null, 0);
        }
        // 위험 키는 사유 필수
        String dangerKey = null;
        for (Map.Entry<SettingKey, String> e : parsed.entrySet()) {
            if (e.getKey().danger() && !sameValue(e.getKey(), e.getValue(), resolve(e.getKey(), dbId).value())) {
                dangerKey = e.getKey().key();
                break;
            }
        }
        if (dangerKey != null && (reason == null || reason.trim().length() < 2)) {
            errors.put(dangerKey, "위험 항목은 변경 사유를 입력해야 합니다.");
        }
        if (!errors.isEmpty()) {
            return new ApplyResult(400, "입력값을 확인하세요.", errors, null, 0);
        }
        // 동시 수정 감지
        Map<String, String> conflicts = new LinkedHashMap<String, String>();
        for (SettingKey k : parsed.keySet()) {
            Long sent = versions == null ? null : versions.get(k.key());
            if (sent != null && sent.longValue() != rowVersion(k, dbId)) {
                conflicts.put(k.key(), "다른 관리자가 먼저 바꿨습니다. 새로고침한 뒤 다시 시도하세요.");
            }
        }
        if (!conflicts.isEmpty()) {
            return new ApplyResult(409, "다른 관리자가 먼저 변경한 항목이 있습니다.", conflicts, null, 0);
        }

        List<String> changed = new ArrayList<String>();
        int unchanged = 0;
        String who = user == null ? "unknown" : user;
        String why = (reason == null || reason.trim().isEmpty()) ? null : reason.trim();
        try {
            for (Map.Entry<SettingKey, String> e : parsed.entrySet()) {
                SettingKey k = e.getKey();
                String v = e.getValue();
                if (sameValue(k, v, resolve(k, dbId).value())) {
                    unchanged++;
                    continue;
                }
                String base = dbId == null ? baseValue(k) : resolve(k, null).value();
                boolean backToBase = sameValue(k, v, base);
                if (dbId == null) {
                    if (backToBase) {
                        store.resetGlobal(k.key(), who, why);
                    } else {
                        store.setGlobal(k.key(), v, who, why, "SET");
                    }
                } else {
                    if (backToBase) {
                        store.resetPerDb(dbId, k.key(), who, why);
                    } else {
                        store.setPerDb(dbId, k.key(), v, who, why);
                    }
                }
                changed.add(k.key());
            }
        } finally {
            // 중간에 저장이 실패해도 이미 커밋된 항목은 스냅샷에 반영한다 (안 그러면 DB 와 동작 값이 어긋난다).
            reload();
        }
        return new ApplyResult(200, null, null, changed, unchanged);
    }

    /**
     * 한 DB 의 재정의(저장된 DB별 값)를 다른 DB 들에 그대로 복사한다 (환경설정 DB화 P5, 2026-10-04).
     * 대상 DB 마다 apply() 를 거치므로 종류·범위·키 사이 관계·위험 키 사유 검증과 이력이 똑같이 적용되고,
     * 복사한 값이 대상 DB 의 전역 유효값과 같으면 재정의를 만들지 않는다. 한 대상이 실패해도 다른 대상은 계속한다.
     *
     * @return 대상 DB id → 결과. 원본에 재정의가 없으면 빈 맵.
     */
    public synchronized Map<String, ApplyResult> copyPerDb(String fromDb, List<String> toDbs, String user, String reason) {
        Map<String, ApplyResult> out = new LinkedHashMap<String, ApplyResult>();
        Map<SettingKey, AppSettingStore.Row> src = snapshot.perDb.get(fromDb);
        if (src == null || src.isEmpty() || toDbs == null) {
            return out;
        }
        Map<String, String> changes = new LinkedHashMap<String, String>();
        for (Map.Entry<SettingKey, AppSettingStore.Row> e : src.entrySet()) {
            changes.put(e.getKey().key(), e.getValue().value());
        }
        for (String to : toDbs) {
            if (to == null || to.equals(fromDb) || out.containsKey(to)) {
                continue;
            }
            out.put(to, apply(to, changes, null, user, reason));
        }
        return out;
    }

    /** 이 DB 에 저장된 재정의 개수 (복사 화면 안내용). */
    public int overrideCount(String dbId) {
        Map<SettingKey, AppSettingStore.Row> m = snapshot.perDb.get(dbId);
        return m == null ? 0 : m.size();
    }

    /** 저장된 값을 지워 기본값(DB 별이면 전역값)으로 되돌린다. */
    public synchronized ApplyResult reset(String dbId, List<String> keys, String user, String reason) {
        if (ignoreDb) {
            return new ApplyResult(409, "안전 모드로 기동 중이라 초기화할 수 없습니다.", null, null, 0);
        }
        if (keys == null || keys.isEmpty()) {
            return new ApplyResult(400, "초기화할 항목이 없습니다.", null, null, 0);
        }
        Map<String, String> errors = new LinkedHashMap<String, String>();
        List<SettingKey> targets = new ArrayList<SettingKey>();
        String dangerKey = null;
        for (String name : keys) {
            SettingKey k = SettingKey.byKey(name);
            String problem = editProblem(k, dbId);
            if (problem != null) {
                errors.put(name, problem);
                continue;
            }
            targets.add(k);
            if (k.danger() && dangerKey == null && hasRow(k, dbId)) {
                dangerKey = k.key();
            }
        }
        if (dangerKey != null && (reason == null || reason.trim().length() < 2)) {
            errors.put(dangerKey, "위험 항목은 변경 사유를 입력해야 합니다.");
        }
        if (!errors.isEmpty()) {
            return new ApplyResult(400, "입력값을 확인하세요.", errors, null, 0);
        }
        List<String> changed = new ArrayList<String>();
        int unchanged = 0;
        String who = user == null ? "unknown" : user;
        String why = (reason == null || reason.trim().isEmpty()) ? null : reason.trim();
        try {
            for (SettingKey k : targets) {
                boolean removed = dbId == null ? store.resetGlobal(k.key(), who, why) : store.resetPerDb(dbId, k.key(), who, why);
                if (removed) {
                    changed.add(k.key());
                } else {
                    unchanged++;
                }
            }
        } finally {
            reload();
        }
        return new ApplyResult(200, null, null, changed, unchanged);
    }

    private boolean hasRow(SettingKey key, String dbId) {
        Snapshot s = snapshot;
        if (dbId != null && key.perDb()) {
            Map<SettingKey, AppSettingStore.Row> m = s.perDb.get(dbId);
            return m != null && m.containsKey(key);
        }
        return s.global.containsKey(key);
    }

    /** 이 키를 이 범위에서 화면으로 바꿀 수 있는지 - 바꿀 수 없으면 사용자에게 보일 사유, 가능하면 null. */
    private String editProblem(SettingKey k, String dbId) {
        if (k == null || k.hidden()) {
            return "변경할 수 없는 항목입니다.";
        }
        if (k.secret()) {
            return "비밀값은 이 화면에서 변경할 수 없습니다.";
        }
        if (dbId != null && !k.perDb()) {
            return "DB별 재정의를 지원하지 않는 항목입니다.";
        }
        EnvHit hit = envHits.get(k);
        if (hit != null && hit.source.locksOverDb()) {
            return "기동 인자 또는 conf/dbagent.conf 로 고정된 값이라 화면에서 바꿀 수 없습니다. (현재 출처: " + hit.source.label() + ")";
        }
        return null;
    }

    private static int compareNumbers(String a, String b) {
        return new BigDecimal(a.trim()).compareTo(new BigDecimal(b.trim()));
    }

    // ───────────────────────── 옛 외부 파일 이관 · 불일치 점검 ─────────────────────────

    /** 환경(파일·번들·인자)에서 찾은 임의 키의 값과 출처. 부트스트랩 항목 표시용. */
    public static final class EnvValue {
        private final String value;
        private final SettingSource source;

        EnvValue(String value, SettingSource source) {
            this.value = value;
            this.source = source;
        }

        public String value() { return value; }
        public SettingSource source() { return source; }
    }

    /**
     * SettingKey 에 없는 키(포트·DB 경로 같은 부트스트랩 값)의 현재 값과 출처를 환경에서 찾는다. 없으면 null.
     * 비밀번호 키는 호출하지 말 것.
     */
    public EnvValue environmentValue(String key) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return null;
        }
        ConfigurableEnvironment cenv = (ConfigurableEnvironment) environment;
        for (PropertySource<?> ps : cenv.getPropertySources()) {
            String name = ps.getName();
            if ("configurationProperties".equals(name) || DB_INJECTED_SOURCE_NAME.equals(name) || !ps.containsProperty(key)) {
                continue;
            }
            Object raw = ps.getProperty(key);
            if (raw == null) {
                return null;
            }
            return new EnvValue(cenv.resolvePlaceholders(String.valueOf(raw)), classify(name));
        }
        return null;
    }

    private final List<Runnable> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<Runnable>();

    /**
     * 설정이 다시 읽힐 때(저장·초기화·reload) 부를 콜백을 등록한다. 설정을 캐시해 판정에 쓰는 서비스(Lock 실시간 스냅샷 등)가
     * 옛 기준으로 만든 결과를 버리는 데 쓴다. 콜백은 설정 잠금 안에서 불리므로 가볍게 끝내야 한다.
     */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void notifyChanged() {
        for (Runnable r : changeListeners) {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warn("설정 변경 콜백 실패: {}", e.toString());
            }
        }
    }

    /** DB 를 삭제할 때 그 DB 의 재정의를 모두 지우고 스냅샷을 갱신한다. 실패해도 DB 삭제 자체는 막지 않는다. */
    public synchronized void clearDbOverrides(String dbId, String user) {
        if (dbId == null || dbId.isEmpty()) {
            return;
        }
        try {
            int n = store.deleteAllForDb(dbId, user);
            if (n > 0) {
                log.info("DB 삭제로 환경설정 재정의 {}건을 정리했습니다: {}", n, dbId);
                reload();
            }
        } catch (RuntimeException e) {
            log.warn("DB 삭제 후 환경설정 재정의 정리에 실패했습니다 ({}): {}", dbId, e.toString());
        }
    }

    /** 안전 모드 여부. */
    public boolean isIgnoreDb() {
        return ignoreDb;
    }

    /** 옛 외부 파일에 DB 와 다른 값이 남아 있는 키 목록. 파일 값은 무시되고 DB 값이 적용 중이다. */
    public List<FileDiff> staleFileValues() {
        return staleFileValues;
    }

    /** 파일 값과 DB 값이 다른 한 건. */
    public static final class FileDiff {
        private final SettingKey key;
        private final String fileValue;
        private final String dbValue;

        FileDiff(SettingKey key, String fileValue, String dbValue) {
            this.key = key;
            this.fileValue = fileValue;
            this.dbValue = dbValue;
        }

        public SettingKey key() { return key; }
        public String fileValue() { return fileValue; }
        public String dbValue() { return dbValue; }
    }

    /**
     * 최초 1회: 옛 외부 application.properties 에서 온 값 중 기본값과 다른 것만 DB 로 옮긴다
     * (app_setting_meta.migrated_from_properties 로 한 번만). 기본값과 같은 값은 옮기지 않는다 - 나중에 코드 기본값을
     * 고쳐도 DB 에 옛 값이 굳지 않게 하려는 것. 파일은 건드리지 않는다(롤백 보험, 이름 변경은 운영자가 수동으로).
     * 인자·conf 가 같은 키를 덮고 있으면(envHits 의 출처가 OLD_FILE 이 아님) 옛 파일 값은 가려져 있으므로 옮기지 않는다.
     */
    private void migrateFromOldFileIfNeeded() {
        try {
            if (store.isMigratedFromProperties()) {
                return;
            }
            int moved = 0;
            StringBuilder names = new StringBuilder();
            for (Map.Entry<SettingKey, EnvHit> e : envHits.entrySet()) {
                SettingKey k = e.getKey();
                EnvHit hit = e.getValue();
                if (hit.source != SettingSource.OLD_FILE || k.secret() || sameValue(k, hit.value, k.defaultValue())) {
                    continue;
                }
                store.setGlobal(k.key(), hit.value.trim(), "system", "application.properties 최초 이관", "MIGRATE");
                moved++;
                names.append(names.length() == 0 ? "" : ", ").append(k.key());
            }
            store.markMigratedFromProperties();
            log.info("옛 외부 application.properties 값 이관 완료: {}건{} (파일은 그대로 둡니다)", moved,
                    moved == 0 ? "" : " [" + names + "]");
        } catch (RuntimeException ex) {
            // 이관이 안 돼도 기동은 계속한다 - 파일 값이 그대로 쓰이고, 플래그를 안 올렸으므로 다음 기동에 다시 시도한다.
            log.warn("옛 외부 파일 값 이관에 실패했습니다 (다음 기동에 다시 시도): {}", ex.toString());
        }
    }

    /** 옛 파일 값과 DB 값이 달라진 키를 찾아 경고한다 (DB 값이 이기므로 파일을 고쳐도 반영되지 않는다). */
    private void refreshStaleFileValues() {
        Snapshot s = snapshot;
        List<FileDiff> out = new ArrayList<FileDiff>();
        for (Map.Entry<SettingKey, EnvHit> e : envHits.entrySet()) {
            EnvHit hit = e.getValue();
            AppSettingStore.Row row = s.global.get(e.getKey());
            if (hit.source == SettingSource.OLD_FILE && row != null && !sameValue(e.getKey(), hit.value, row.value())) {
                out.add(new FileDiff(e.getKey(), hit.value, row.value()));
                log.warn("옛 외부 파일 값과 DB 값이 달라 DB 값을 적용 중입니다: {} (파일 {}, DB {})", e.getKey().key(), hit.value, row.value());
            }
        }
        staleFileValues = Collections.unmodifiableList(out);
    }

    /** 종류에 맞게 값이 같은지 본다 (정수·소수는 수치 비교, 불리언은 대소문자 무시). */
    static boolean sameValue(SettingKey k, String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        if (x.equals(y)) {
            return true;
        }
        switch (k.type()) {
            case INT:
            case DECIMAL:
                try {
                    return new BigDecimal(x).compareTo(new BigDecimal(y)) == 0;
                } catch (NumberFormatException e) {
                    return false;
                }
            case BOOL:
                return x.equalsIgnoreCase(y);
            default:
                return false;
        }
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
