package com.dbagent.monitor;

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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 성능 분석 "시스템 부하" 수집·저장·조회 (성능 분석 개편 Phase 2, 2026-10-09, 설계문서/성능분석 메뉴 설계.md 2장).
 *
 * <p>수집: 기존 60초 샘플러가 이미 열어 둔 대상 DB 연결로 v$sysmetric(group_id=2, 60초 값 지표 9개)과 v$eventmetric(Idle 제외, 대기가
 * 있는 이벤트 상위 {@value #EVENT_TOP_N}개)을 1분에 한 번씩 읽는다. 누적 카운터가 아니라 Oracle이 계산한 "최근 60초 값"이라
 * 델타·재기동·음수 처리가 필요 없다. 새 원본 조회가 생기므로 {@code dbagent.perf.sys-collect-enabled} 기본 꺼짐.
 * PDB 접속에서는 두 뷰가 0행이라(로컬 실측, 설계 3.6) 0으로 저장하지 않고 수집 상태 "unsupported"(판단 보류)로 둔다.
 *
 * <p>저장: perf_sys_metric(db_id, minute_key, metric_name, value), perf_sys_event(db_id, minute_key, event, wait_class, waits, time_us).
 * minute_key는 기존 perf_ash_min과 같은 DB 시각 분 키다. 성공한 분은 perf_collect_min(kind='sys')에 남겨 수집 공백을 판정한다.
 * 조회는 수집 저장소만 읽는다(운영 DB 조회 없음). SQL은 SQLite(main)·H2 1.4.200(AIX) 공통 문법만 쓴다. Java 8 문법만 쓴다.
 */
@Service
public class PerfSysService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(PerfSysService.class);

    /**
     * 한 분에 저장하는 대기 이벤트 수 상한(대기 시간 상위) - 저장소 증가를 묶는다. 100개면 DB 1개당 15일에 SQLite 약 317MB·H2 약 870MB를
     * 실측해(2026-10-09 쿼리 검토) 50개 + 분당 100µs 미만 제외로 줄였다.
     */
    static final int EVENT_TOP_N = 50;
    /** 이 값(1/100초) 미만으로 기다린 이벤트는 저장하지 않는다(0.01 = 분당 100µs). */
    private static final double EVENT_MIN_TIME_WAITED = 0.01;
    /** Hard parse 증가 힌트를 내는 최소 초당 값 - 지난주 기준이 아주 작을 때 작은 변동이 +100%를 넘는 것을 막는다. */
    private static final double HARD_PARSE_HINT_MIN_PER_SEC = 1.0;
    private static final int WAIT_ROWS = 50;
    /** 평균 비교(×배)에 쓰는 최소 대기 횟수 - 몇 번 안 되는 대기의 평균은 흔들린다. */
    private static final long MIN_WAITS_FOR_AVG = 10;
    private static final int MAX_RANGE_MIN = 24 * 60;
    private static final long WEEK_MIN = 7L * 1440;

    /** 카드 정의 {key, Oracle 지표 이름, 나누는 값, 표시 이름, 단위}. 저장은 Oracle 원값 그대로, 화면 값은 조회할 때 나눈다. */
    private static final String[][] CARDS = {
            {"dbtime", "Database Time Per Sec", "100", "DB Time", "s/s"},
            {"dbcpu", "CPU Usage Per Sec", "100", "DB CPU", "s/s"},
            {"exec", "Executions Per Sec", "1", "Executions", "/s"},
            {"calls", "User Calls Per Sec", "1", "User calls", "/s"},
            {"lread", "Logical Reads Per Sec", "1", "Logical reads", "blk/s"},
            {"pread", "Physical Reads Per Sec", "1", "Physical reads", "blk/s"},
            {"hparse", "Hard Parse Count Per Sec", "1", "Hard parse", "/s"},
            {"commit", "User Commits Per Sec", "1", "Commits", "/s"},
            {"redo", "Redo Generated Per Sec", "1024", "Redo size", "KB/s"}
    };

    @Autowired
    private SettingsService settings;

    private final JdbcTemplate jdbc;
    private final PerfLockService heartbeats;
    /** 1분 저장을 한 트랜잭션으로 묶는다 - JdbcTemplate.batchUpdate는 트랜잭션을 열지 않아 SQLite에서 행마다 커밋(fsync)된다(실측 231ms → 0.9ms). */
    private final TransactionTemplate tx;
    private final boolean h2;
    /** DB별 마지막 수집 상태 {ok|unsupported|error, 사유, 시각 ms}. */
    private final ConcurrentHashMap<String, String[]> collectState = new ConcurrentHashMap<String, String[]>();

    public PerfSysService(@Qualifier("perfJdbcTemplate") JdbcTemplate jdbc, PerfLockService heartbeats) {
        this.jdbc = jdbc;
        this.heartbeats = heartbeats;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        String url = jdbc.getDataSource() instanceof com.zaxxer.hikari.HikariDataSource
                ? ((com.zaxxer.hikari.HikariDataSource) jdbc.getDataSource()).getJdbcUrl() : null;
        this.h2 = url != null && url.startsWith("jdbc:h2:");
    }

    @Override
    public void afterPropertiesSet() {
        // SQLite는 WITHOUT ROWID로 만든다 - 기본키 자동 인덱스에 키 컬럼이 이중으로 저장되는 것을 없앤다(H2에는 없는 문법)
        String tail = h2 ? ")" : ") WITHOUT ROWID";
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_sys_metric (" +
                "db_id VARCHAR(64) NOT NULL, minute_key BIGINT NOT NULL, metric_name VARCHAR(64) NOT NULL, metric_value DOUBLE NOT NULL, " +
                "PRIMARY KEY (db_id, minute_key, metric_name)" + tail);
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_sys_event (" +
                "db_id VARCHAR(64) NOT NULL, minute_key BIGINT NOT NULL, event VARCHAR(64) NOT NULL, wait_class VARCHAR(32), " +
                "waits BIGINT NOT NULL, time_us BIGINT NOT NULL, " +
                "PRIMARY KEY (db_id, minute_key, event)" + tail);
    }

    // ------------------------------------------------------------------ 수집(샘플러의 병렬 조회 단계)

    /** 한 사이클에 읽은 값. metrics는 Oracle 지표 이름 → 값, events는 {event, wait_class, waits, time_us}. */
    public static final class SysBatch {
        final Map<String, Double> metrics = new LinkedHashMap<String, Double>();
        /** Oracle 60초 구간의 가운데 시각(DB 시각) - 분 키를 사이클 시작 시각이 아니라 값이 실제 속한 구간으로 정한다. 모르면 null. */
        LocalDateTime at;
        final List<Object[]> events = new ArrayList<Object[]>();
    }

    public boolean enabled() {
        return settings.getBool(SettingKey.SYS_COLLECT_ENABLED);
    }

    /**
     * v$sysmetric·v$eventmetric를 읽는다. 꺼져 있으면 null(상태도 건드리지 않음). 지표 조회가 실패하면 null(= 이번 분은 하트비트를
     * 남기지 않아 수집 공백), 지표가 0행이면(PDB 접속 등) null + 상태 unsupported. 이벤트 조회만 실패하면 지표는 살린다.
     */
    public SysBatch query(Connection conn, String dbId) {
        if (!enabled()) return null;
        int timeout = settings.getInt(SettingKey.LOCK_QUERY_TIMEOUT_SECONDS);
        SysBatch b = new SysBatch();
        StringBuilder in = new StringBuilder();
        for (String[] c : CARDS) in.append(in.length() == 0 ? "?" : ", ?");
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT metric_name, value, begin_time, end_time FROM v$sysmetric WHERE group_id = 2 AND metric_name IN (" + in + ")")) {
            ps.setQueryTimeout(timeout);
            for (int i = 0; i < CARDS.length; i++) ps.setString(i + 1, CARDS[i][1]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double v = rs.getDouble(2);
                    if (!rs.wasNull()) b.metrics.put(rs.getString(1), v);
                    Timestamp bt = rs.getTimestamp(3);
                    Timestamp et = rs.getTimestamp(4);
                    if (bt != null && et != null && et.after(bt) && (b.at == null || et.toLocalDateTime().isAfter(b.at))) {
                        b.at = bt.toLocalDateTime().plusSeconds((et.getTime() - bt.getTime()) / 2000);
                    }
                }
            }
        } catch (SQLException e) {
            collectState.put(dbId, new String[]{"error", "ORA-" + String.format("%05d", e.getErrorCode()), String.valueOf(System.currentTimeMillis())});
            log.debug("sysmetric sampling skipped for db_id={}: {}", dbId, e.toString());
            return null;
        }
        if (b.metrics.isEmpty()) {
            // 0으로 저장하지 않는다 - PDB 접속에서는 v$sysmetric(전 그룹)·v$eventmetric이 비어 있다(설계 3.6)
            collectState.put(dbId, new String[]{"unsupported", "v$sysmetric 0행", String.valueOf(System.currentTimeMillis())});
            return null;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name, wait_class, wait_count, time_waited FROM (" +
                        "SELECT n.name, n.wait_class, e.wait_count, e.time_waited FROM v$eventmetric e JOIN v$event_name n ON n.event# = e.event# " +
                        "WHERE n.wait_class <> 'Idle' AND e.time_waited >= " + EVENT_MIN_TIME_WAITED + " ORDER BY e.time_waited DESC) " +
                        "WHERE ROWNUM <= " + EVENT_TOP_N)) {
            ps.setQueryTimeout(timeout);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // time_waited는 1/100초 단위 - 마이크로초로 저장
                    b.events.add(new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3), Math.round(rs.getDouble(4) * 10000.0)});
                }
            }
            collectState.put(dbId, new String[]{"ok", "", String.valueOf(System.currentTimeMillis())});
        } catch (SQLException e) {
            collectState.put(dbId, new String[]{"error", "이벤트 지표 ORA-" + String.format("%05d", e.getErrorCode()), String.valueOf(System.currentTimeMillis())});
            log.debug("eventmetric sampling skipped for db_id={}: {}", dbId, e.toString());
        }
        return b;
    }

    // ------------------------------------------------------------------ 저장(순차 저장 단계)

    /** 이번 분 값을 저장한다. 같은 분에 다시 오면 덮어쓴다(지우고 다시 넣기 - SQLite·H2 공통). */
    public void ingest(String dbId, SysBatch b, LocalDateTime dbAt) {
        if (b == null || b.metrics.isEmpty()) return;
        // 값이 속한 Oracle 구간 가운데 시각의 분 - 같은 구간을 다시 읽으면 아래 DELETE+INSERT가 덮어쓰고, 구간이 건너뛰어도 키가 어긋나지 않는다
        final long key = PerfStoreService.minuteKey(b.at != null ? b.at : dbAt);
        List<Object[]> m = new ArrayList<Object[]>();
        for (Map.Entry<String, Double> e : b.metrics.entrySet()) m.add(new Object[]{dbId, key, e.getKey(), e.getValue()});
        // 같은 이름이 두 번 오면(드물게 event# 중복) 기본키 충돌 - 첫 행만 쓴다
        final List<Object[]> uniq = new ArrayList<Object[]>();
        java.util.Set<Object> seen = new java.util.HashSet<Object>();
        for (Object[] e : b.events) if (seen.add(e[0])) uniq.add(new Object[]{dbId, key, e[0], e[1], e[2], e[3]});
        tx.execute(status -> {
            jdbc.update("DELETE FROM perf_sys_metric WHERE db_id = ? AND minute_key = ?", dbId, key);
            jdbc.update("DELETE FROM perf_sys_event WHERE db_id = ? AND minute_key = ?", dbId, key);
            jdbc.batchUpdate("INSERT INTO perf_sys_metric (db_id, minute_key, metric_name, metric_value) VALUES (?, ?, ?, ?)", m);
            if (!uniq.isEmpty()) {
                jdbc.batchUpdate("INSERT INTO perf_sys_event (db_id, minute_key, event, wait_class, waits, time_us) VALUES (?, ?, ?, ?, ?, ?)", uniq);
            }
            heartbeats.saveHeartbeat(dbId, key, "sys");
            return null;
        });
    }

    // ------------------------------------------------------------------ 보관

    @Scheduled(cron = "0 35 4 * * *")
    void purge() {
        long cutoff = PerfStoreService.minuteKey(LocalDateTime.now(ZoneOffset.UTC)) - settings.getInt(SettingKey.PERF_RETENTION_DAYS) * 1440L;
        int total = 0;
        // 기본키가 db_id부터라 minute_key만으로는 범위 탐색이 안 된다(덩어리마다 전체 스캔, 실측 H2 2.7초) - DB별 기본키 범위로 지운다
        List<String> dbIds = jdbc.queryForList("SELECT db_id FROM perf_sys_metric UNION SELECT db_id FROM perf_sys_event", String.class);
        for (String table : new String[]{"perf_sys_event", "perf_sys_metric"}) {
            for (String id : dbIds) {
                List<Long> min = jdbc.queryForList("SELECT MIN(minute_key) FROM " + table + " WHERE db_id = ?", Long.class, id);
                Long cur = min.isEmpty() ? null : min.get(0);
                // 한 번에 지우면 쓰기 잠금이 길어지므로 6시간 단위로 앞에서부터 자른다(기존 purge 패턴)
                while (cur != null && cur < cutoff) {
                    long upto = Math.min(cur + 360, cutoff);
                    total += jdbc.update("DELETE FROM " + table + " WHERE db_id = ? AND minute_key >= ? AND minute_key < ?", id, cur, upto);
                    cur = upto;
                }
            }
        }
        if (total > 0) log.info("perf sys purge: {} row(s) older than {} day(s)", total, settings.getInt(SettingKey.PERF_RETENTION_DAYS));
    }

    // ------------------------------------------------------------------ 조회(화면 API)

    public Map<String, Object> collectStatus(String dbId) {
        String[] st = collectState.get(dbId);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("state", st == null ? "unknown" : st[0]);
        m.put("reason", st == null ? "" : st[1]);
        m.put("enabled", enabled());
        return m;
    }

    private static String iso(long minuteKey) {
        return LocalDateTime.ofEpochSecond(minuteKey * 60L, 0, ZoneOffset.UTC).toString();
    }

    private static double round(double v, int digits) {
        double p = Math.pow(10, digits);
        return Math.round(v * p) / p;
    }

    /** 유효숫자 기준 반올림 - 부하가 낮은 DB의 0.00243 같은 값이 소수 셋째 자리 반올림으로 0.002가 되어 5% 오차를 넘던 문제(QA D1). */
    private static double sig(double v, int digits) {
        if (v == 0 || Double.isNaN(v) || Double.isInfinite(v)) return v;
        double scale = Math.pow(10, digits - 1 - (int) Math.floor(Math.log10(Math.abs(v))));
        return Math.round(v * scale) / scale;
    }

    private static Double avg(Double[] a) {
        double s = 0;
        int n = 0;
        for (Double v : a) if (v != null) { s += v; n++; }
        return n == 0 ? null : s / n;
    }

    private static long keyOf(LocalDateTime t) {
        return PerfStoreService.minuteKey(t);
    }

    /** [fromKey, fromKey+n) 의 분별 값: 지표 이름 → 길이 n 배열(없는 분은 null). */
    private Map<String, Double[]> loadSeries(String dbId, long fromKey, int n) {
        final Map<String, Double[]> out = new HashMap<String, Double[]>();
        for (String[] c : CARDS) out.put(c[1], new Double[n]);
        jdbc.query("SELECT minute_key, metric_name, metric_value FROM perf_sys_metric WHERE db_id = ? AND minute_key >= ? AND minute_key < ?",
                rs -> {
                    Double[] arr = out.get(rs.getString(2));
                    int i = (int) (rs.getLong(1) - fromKey);
                    if (arr != null && i >= 0 && i < n) arr[i] = rs.getDouble(3);
                }, dbId, fromKey, fromKey + n);
        return out;
    }

    private static Double[] scaled(Double[] a, double div) {
        Double[] r = new Double[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i] == null ? null : sig(a[i] / div, 4);
        return r;
    }

    /**
     * 지표 카드 + 추이. baseline이 true면 같은 구간의 7일 전 값을 함께 준다(없으면 base 값 null → 화면은 "기준 없음").
     * 구간은 컨트롤러가 24시간 이하로 검증한다.
     */
    public Map<String, Object> loadMetrics(String dbId, LocalDateTime from, LocalDateTime to, boolean baseline) {
        long fromKey = keyOf(from);
        int n = (int) Math.max(1, Math.min(MAX_RANGE_MIN, keyOf(to) - fromKey));
        Map<String, Double[]> cur = loadSeries(dbId, fromKey, n);
        Map<String, Double[]> base = baseline ? loadSeries(dbId, fromKey - WEEK_MIN, n) : null;
        int cardPct = settings.getInt(SettingKey.DIAG_CARD_PCT);

        List<String> times = new ArrayList<String>();
        for (int i = 0; i < n; i++) times.add(iso(fromKey + i));
        List<Object> cards = new ArrayList<Object>();
        Map<String, Object> series = new LinkedHashMap<String, Object>();
        List<Object> hints = new ArrayList<Object>();
        boolean anyData = false;
        boolean anyBase = false;
        for (String[] c : CARDS) {
            double div = Double.parseDouble(c[2]);
            Double[] cs = scaled(cur.get(c[1]), div);
            Double[] bs = base == null ? null : scaled(base.get(c[1]), div);
            Double ca = avg(cs);
            Double ba = bs == null ? null : avg(bs);
            if (ca != null) anyData = true;
            if (ba != null) anyBase = true;
            // 변화율은 현재·지난주 양쪽에 값이 있는 분만으로 계산한다(서로 다른 시간대 평균을 비교하지 않게). 카드 평균은 각자 값이 있는 분 전체
            Double change = null;
            if (bs != null) {
                double sc = 0, sb = 0;
                for (int i = 0; i < n; i++) {
                    if (cs[i] != null && bs[i] != null) { sc += cs[i]; sb += bs[i]; }
                }
                if (sb > 0) change = round((sc - sb) / sb * 100.0, 1);
            }
            Map<String, Object> card = new LinkedHashMap<String, Object>();
            card.put("key", c[0]);
            card.put("label", c[3]);
            card.put("unit", c[4]);
            card.put("avg", ca == null ? null : sig(ca, 4));
            card.put("baseAvg", ba == null ? null : sig(ba, 4));
            card.put("changePct", change);
            card.put("level", change == null ? null : change >= cardPct ? "up" : change <= -cardPct ? "down" : null);
            cards.add(card);
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("cur", java.util.Arrays.asList(cs));
            if (bs != null) s.put("base", java.util.Arrays.asList(bs));
            series.put(c[0], s);
            if ("hparse".equals(c[0]) && change != null && ca != null && ca >= HARD_PARSE_HINT_MIN_PER_SEC
                    && change >= settings.getInt(SettingKey.DIAG_HARD_PARSE_PCT)) {
                hints.add(hint("HARD_PARSE", "Hard parse가 지난주 같은 시간보다 " + Math.round(change) + "% 늘었습니다. 리터럴로 만든 SQL이 많지 않은지 SQL 분석에서 확인하세요."));
            }
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("collect", collectStatus(dbId));
        Long first = heartbeats.firstHeartbeat(dbId, "sys");
        m.put("collectedFrom", first == null ? null : iso(first));
        m.put("times", times);
        m.put("cards", cards);
        m.put("series", series);
        m.put("hasData", anyData);
        m.put("hasBase", baseline && anyBase);
        m.put("hints", hints);
        return m;
    }

    private static Map<String, Object> hint(String code, String text) {
        Map<String, Object> h = new LinkedHashMap<String, Object>();
        h.put("code", code);
        h.put("text", text);
        return h;
    }

    private static final class Agg {
        String waitClass;
        long waits;
        long timeUs;
    }

    private Map<String, Agg> loadWaitAgg(String dbId, long fromKey, long toKey) {
        final Map<String, Agg> out = new LinkedHashMap<String, Agg>();
        jdbc.query("SELECT event, MAX(wait_class), SUM(waits), SUM(time_us) FROM perf_sys_event " +
                        "WHERE db_id = ? AND minute_key >= ? AND minute_key < ? GROUP BY event ORDER BY SUM(time_us) DESC",
                rs -> {
                    Agg a = new Agg();
                    a.waitClass = rs.getString(2);
                    a.waits = rs.getLong(3);
                    a.timeUs = rs.getLong(4);
                    out.put(rs.getString(1), a);
                }, dbId, fromKey, toKey);
        return out;
    }

    private static Double avgMs(Agg a) {
        return a == null || a.waits < MIN_WAITS_FOR_AVG ? null : a.timeUs / 1000.0 / a.waits;
    }

    /** 대기 이벤트 표(대기 시간 상위) + 지난주 평균 대비 배지·진단 힌트. */
    public Map<String, Object> loadWaits(String dbId, LocalDateTime from, LocalDateTime to, boolean baseline) {
        long fromKey = keyOf(from);
        long toKey = Math.min(keyOf(to), fromKey + MAX_RANGE_MIN);
        Map<String, Agg> cur = loadWaitAgg(dbId, fromKey, toKey);
        Map<String, Agg> base = baseline ? loadWaitAgg(dbId, fromKey - WEEK_MIN, toKey - WEEK_MIN) : null;
        boolean hasBase = base != null && !base.isEmpty();
        int ratioPct = settings.getInt(SettingKey.DIAG_WAIT_RATIO_PCT);
        long total = 0;
        for (Agg a : cur.values()) total += a.timeUs;

        List<Object> rows = new ArrayList<Object>();
        Map<String, Boolean> slow = new HashMap<String, Boolean>();
        for (Map.Entry<String, Agg> e : cur.entrySet()) {
            Agg a = e.getValue();
            Agg b = base == null ? null : base.get(e.getKey());
            Double avg = avgMs(a);
            Double bavg = avgMs(b);
            boolean isSlow = avg != null && bavg != null && bavg > 0 && avg * 100.0 >= bavg * ratioPct;
            slow.put(e.getKey(), isSlow);
            if (rows.size() >= WAIT_ROWS) continue;
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("event", e.getKey());
            r.put("waitClass", a.waitClass);
            r.put("timeSec", round(a.timeUs / 1e6, 2));
            r.put("pct", total > 0 ? round(a.timeUs * 100.0 / total, 1) : null);
            r.put("waits", a.waits);
            r.put("avgMs", avg == null ? null : round(avg, 3));
            r.put("baseAvgMs", bavg == null ? null : round(bavg, 3));
            // 기준 데이터가 아예 없으면 배지를 달지 않는다("신규"로 오인하지 않게)
            r.put("badge", isSlow ? "slow" : (hasBase && b == null) ? "new" : null);
            rows.add(r);
        }
        List<Object> hints = new ArrayList<Object>();
        if (Boolean.TRUE.equals(slow.get("log file sync")) && Boolean.TRUE.equals(slow.get("log file parallel write"))) {
            hints.add(hint("REDO_WRITE", "log file sync와 log file parallel write 평균이 함께 지난주의 " + (ratioPct / 100.0) + "배 이상입니다. redo 디스크 쓰기 지연이 의심됩니다."));
        }
        Double seq = avgMs(cur.get("db file sequential read"));
        int seqMs = settings.getInt(SettingKey.DIAG_SEQ_READ_MS);
        if (seq != null && seq >= seqMs) {
            hints.add(hint("SEQ_READ", "db file sequential read 평균이 " + round(seq, 1) + "ms로 기준(" + seqMs + "ms) 이상입니다. 스토리지 응답 지연이 의심됩니다."));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("collect", collectStatus(dbId));
        m.put("rows", rows);
        m.put("totalSec", round(total / 1e6, 2));
        m.put("hasBase", hasBase);
        m.put("hints", hints);
        return m;
    }
}
