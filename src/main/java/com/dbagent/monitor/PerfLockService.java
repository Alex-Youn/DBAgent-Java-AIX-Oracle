package com.dbagent.monitor;

import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.settings.SettingKey;
import com.dbagent.settings.SettingsService;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 성능 분석 "Lock 분석" 수집·저장·조회 (성능 분석 개편 Phase 1a, 2026-10-09, 설계문서/성능분석 메뉴 설계.md 1장).
 *
 * <p>수집: 기존 60초 샘플러가 읽는 ASH 구간(같은 sample_id 구간)에서 락 대기 이벤트 4종({@code enq: TX - row lock contention},
 * {@code allocate ITL entry}, {@code index contention}, {@code enq: TM - contention})의 행만 한 번 더 읽는다 - 새 원본
 * 조회 종류는 없고(v$active_session_history 한 번 더, 락 이벤트 행만) 쿼리 제한 시간은 lock-query-timeout-seconds를 따른다.
 * 읽기는 샘플러의 병렬 조회 단계에서, 저장({@link #ingest})은 순차 저장 단계에서 한다.
 *
 * <p>저장: 샘플을 그대로 쌓지 않고 "락 발생 건(incident)"으로 묶어 perf_lock_incident(+ 피크 시점 대기자 perf_lock_incident_waiter)에
 * 둔다. 묶는 키는 (최종 블로커 SID·serial#, 락 유형)이고, 같은 키의 샘플이 {@code dbagent.perf.lock-gap-sec}(기본 30초) 넘게
 * 끊기면 별개의 건이다. 진행 중인 건은 OPEN, 끊김 기준이 지나고 그 사이 수집 공백이 없으면 CLOSED, 공백이 있으면 종료 시각을
 * 확정할 수 없어 UNKNOWN으로 둔다(앱이 꺼져 있던 동안은 기록하는 주체도 없다 - 수집 하트비트 perf_collect_min으로 판정).
 *
 * <p>시각은 DB 벽시계를 "UTC로 본 epoch 초"로 바꾼 값(start_ts/end_ts/peak_ts)이라 화면의 DB 시각과 변환 없이 맞는다.
 * SQL은 SQLite(main)·H2 1.4.200(AIX) 공통 문법만 쓴다(upsert만 URL로 나눔). Java 8 문법만 쓴다(AIX와 같은 소스).
 */
@Service
public class PerfLockService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(PerfLockService.class);

    /** 락 대기로 보는 ASH 이벤트(상수라 SQL에 그대로 넣는다 - 사용자 입력 아님). */
    private static final String LOCK_EVENTS_SQL =
            "('enq: TX - row lock contention', 'enq: TX - allocate ITL entry', 'enq: TX - index contention', 'enq: TM - contention')";
    /** 한 사이클에서 읽는 락 대기 샘플 상한 - 락 폭주 때 메모리를 지킨다. */
    private static final int SAMPLE_ROW_CAP = 50000;
    private static final int LIST_LIMIT = 1000;
    /** 종료 판정 때 허용하는 수집 하트비트 누락 분 수(샘플러 주기 흔들림 허용). 넘으면 공백으로 본다. */
    private static final int HEARTBEAT_MISS_TOLERANCE = 1;

    @Autowired
    private SettingsService settings;

    private final JdbcTemplate jdbc;
    private final boolean h2;
    private final LockRealtimeService lockRealtime;
    private final PerfStoreService store;

    /** DB별 ASH 컬럼 모드: 0=전체, 1=blocking_inst_id 없음, 2=blocking_inst_id·blocking_session_serial# 없음(11g 대비). */
    private final ConcurrentHashMap<String, Integer> columnMode = new ConcurrentHashMap<String, Integer>();
    /** DB별 마지막 락 수집 상태 {상태(ok|unavailable|error), 사유, 시각 ms} - 화면에 "ASH 조회 불가 - 판단 보류"를 보이려고. */
    private final ConcurrentHashMap<String, String[]> collectState = new ConcurrentHashMap<String, String[]>();

    public PerfLockService(@Qualifier("perfJdbcTemplate") JdbcTemplate jdbc, @Qualifier("perfDataSource") HikariDataSource ds,
                           LockRealtimeService lockRealtime, PerfStoreService store) {
        this.jdbc = jdbc;
        this.store = store;
        this.h2 = ds.getJdbcUrl() != null && ds.getJdbcUrl().startsWith("jdbc:h2:");
        this.lockRealtime = lockRealtime;
    }

    @Override
    public void afterPropertiesSet() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_collect_min (" +
                "db_id VARCHAR(64) NOT NULL, minute_key BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, " +
                "PRIMARY KEY (db_id, minute_key, kind))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_lock_incident (" +
                "incident_id VARCHAR(200) PRIMARY KEY, db_id VARCHAR(64) NOT NULL, lock_type VARCHAR(2) NOT NULL, status VARCHAR(8) NOT NULL, " +
                "start_ts BIGINT NOT NULL, end_ts BIGINT NOT NULL, peak_ts BIGINT NOT NULL, duration_sec BIGINT NOT NULL, " +
                "max_waiters INTEGER NOT NULL, wait_session_sec BIGINT NOT NULL, " +
                "blocker_sid BIGINT, blocker_serial BIGINT, blocker_inst BIGINT, blocker_user VARCHAR(128), blocker_module VARCHAR(128), " +
                "blocker_program VARCHAR(128), blocker_machine VARCHAR(64), blocker_status VARCHAR(16), blocker_idle_sec BIGINT, " +
                "blocker_sql_id VARCHAR(13), req_mode INTEGER, held_mode INTEGER, wait_event VARCHAR(64), " +
                "obj_id BIGINT, obj_name VARCHAR(200), row_info VARCHAR(200), " +
                "cause_code VARCHAR(24), cause_text VARCHAR(400), cause_confidence VARCHAR(8), fk_info VARCHAR(400))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_perf_lock_incident_time ON perf_lock_incident (db_id, start_ts)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_perf_lock_incident_end ON perf_lock_incident (db_id, end_ts)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_perf_lock_incident_status ON perf_lock_incident (db_id, status)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_lock_incident_waiter (" +
                "incident_id VARCHAR(200) NOT NULL, sid BIGINT NOT NULL, serial_no BIGINT NOT NULL, username VARCHAR(128), " +
                "module VARCHAR(128), machine VARCHAR(64), sql_id VARCHAR(13), wait_sec BIGINT, parent_sid BIGINT, " +
                "PRIMARY KEY (incident_id, sid, serial_no))");
    }

    // ------------------------------------------------------------------ 수집 하트비트

    /** 성공한 수집 분을 남긴다(같은 분에 여러 번이어도 한 행). 앱이 꺼져 있던 분은 행이 없어 수집 공백이 된다. */
    public void saveHeartbeat(String dbId, long minuteKey, String kind) {
        if (h2) {
            jdbc.update("MERGE INTO perf_collect_min (db_id, minute_key, kind) KEY (db_id, minute_key, kind) VALUES (?, ?, ?)",
                    dbId, minuteKey, kind);
        } else {
            jdbc.update("INSERT OR IGNORE INTO perf_collect_min (db_id, minute_key, kind) VALUES (?, ?, ?)", dbId, minuteKey, kind);
        }
    }

    /** [fromKey, toKey) 안에서 하트비트가 있는 분. */
    public Set<Long> heartbeatMinutes(String dbId, String kind, long fromKey, long toKey) {
        return new HashSet<Long>(jdbc.queryForList(
                "SELECT minute_key FROM perf_collect_min WHERE db_id = ? AND kind = ? AND minute_key >= ? AND minute_key < ?",
                Long.class, dbId, kind, fromKey, toKey));
    }

    /** 가장 오래된 하트비트 분(없으면 null) - 수집 시작 이전을 "수집 공백"이 아니라 "수집 전"으로 구분한다. */
    public Long firstHeartbeat(String dbId, String kind) {
        List<Long> r = jdbc.queryForList("SELECT MIN(minute_key) FROM perf_collect_min WHERE db_id = ? AND kind = ?", Long.class, dbId, kind);
        return r.isEmpty() ? null : r.get(0);
    }

    // ------------------------------------------------------------------ 읽기(샘플러의 병렬 조회 단계)

    /** 샘플러가 넘기는 ASH 구간 바인딩(where 절의 ? 자리). */
    public interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    /** 락 대기 샘플 한 행(ASH 1초 샘플). 시각은 DB 시각을 UTC로 본 epoch 초. */
    public static final class LockSample {
        long ts;
        long sid;
        long serial;
        Long userId;
        String username;
        String module;
        String machine;
        String sqlId;
        String event;
        long p1;
        long p2;
        Long bsid;
        Long bserial;
        Long binst;
        Long obj;
        String objName;
    }

    /**
     * 구간(where, 별칭 h)의 락 대기 샘플을 읽는다. 읽기에 실패하면 null(= 이번 사이클은 락 수집 하트비트를 남기지 않는다 - 0이나
     * "락 없음"으로 저장하지 않고 수집 공백으로 둔다). ASH를 못 읽는 DB(뷰 없음·권한 없음)는 상태에 사유를 남긴다.
     */
    public List<LockSample> query(Connection conn, TargetDbConfig target, String where, Binder binder,
                                  java.util.function.Function<Set<Long>, Map<Long, String>> userResolver) {
        String dbId = target.id();
        int mode = columnMode.containsKey(dbId) ? columnMode.get(dbId) : 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                List<LockSample> out = runQuery(conn, where, binder, mode);
                columnMode.put(dbId, mode);
                // 상한을 넘으면 임의의 행이 잘린 것이다 - 조용히 완전한 데이터처럼 보이지 않게 상태에 남긴다(2026-10-09 쿼리 검토 필수 2)
                boolean truncated = out.size() > SAMPLE_ROW_CAP;
                if (truncated) out.remove(out.size() - 1);
                enrich(conn, dbId, out, userResolver);
                collectState.put(dbId, truncated
                        ? new String[]{"truncated", "락 대기 샘플이 " + SAMPLE_ROW_CAP + "건을 넘어 일부만 수집", String.valueOf(System.currentTimeMillis())}
                        : new String[]{"ok", "", String.valueOf(System.currentTimeMillis())});
                return out;
            } catch (SQLException e) {
                // ORA-00904(부적합한 식별자): 11g에 없는 ASH 컬럼 - 해당 컬럼을 NULL로 바꿔 한 번 더 시도한다.
                if (e.getErrorCode() == 904 && mode < 2) {
                    mode++;
                    continue;
                }
                String state = (e.getErrorCode() == 942 || e.getErrorCode() == 1031) ? "unavailable" : "error";
                collectState.put(dbId, new String[]{state, "ORA-" + String.format("%05d", e.getErrorCode()), String.valueOf(System.currentTimeMillis())});
                log.debug("lock wait sampling skipped for db_id={}: {}", dbId, e.toString());
                return null;
            }
        }
        return null;
    }

    /** 락 대기가 없어 락 샘플 ASH 스캔을 건너뛴 사이클 - 빈 목록(= 이번 구간 락 없음, 하트비트·종료 판정은 그대로)과 수집 상태 ok. */
    public List<LockSample> noScan(String dbId) {
        collectState.put(dbId, new String[]{"ok", "", String.valueOf(System.currentTimeMillis())});
        return new ArrayList<LockSample>();
    }

    private List<LockSample> runQuery(Connection conn, String where, Binder binder, int mode) throws SQLException {
        String bser = mode >= 2 ? "NULL" : "h.blocking_session_serial#";
        String binst = mode >= 1 ? "NULL" : "h.blocking_inst_id";
        String sql = "SELECT TO_CHAR(h.sample_time, 'YYYY-MM-DD HH24:MI:SS') AS st, h.session_id AS sid, h.session_serial# AS ser, " +
                "h.user_id, h.module, h.machine, h.sql_id, h.event, h.p1, h.p2, " +
                "h.blocking_session AS bsid, " + bser + " AS bser, " + binst + " AS binst, h.current_obj# AS obj " +
                "FROM v$active_session_history h WHERE " + where +
                "AND h.session_type = 'FOREGROUND' AND h.session_state = 'WAITING' " + // WAITING 조건이 없으면 마지막 대기 이벤트가 남은 CPU 샘플이 섞인다
                "AND h.event IN " + LOCK_EVENTS_SQL + " " + AshRange.EXCLUDE_SELF_SQL +
                "AND ROWNUM <= " + (SAMPLE_ROW_CAP + 1); // 한 건 더 읽어 상한 초과를 알아챈다
        List<LockSample> out = new ArrayList<LockSample>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(settings.getInt(SettingKey.LOCK_QUERY_TIMEOUT_SECONDS));
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LockSample s = new LockSample();
                    s.ts = LocalDateTime.parse(rs.getString("st").replace(' ', 'T')).toEpochSecond(ZoneOffset.UTC);
                    s.sid = rs.getLong("sid");
                    s.serial = rs.getLong("ser");
                    long uid = rs.getLong("user_id");
                    s.userId = rs.wasNull() ? null : Long.valueOf(uid);
                    s.module = cut(rs.getString("module"), 128);
                    s.machine = cut(rs.getString("machine"), 64);
                    s.sqlId = rs.getString("sql_id");
                    s.event = rs.getString("event");
                    s.p1 = rs.getLong("p1");
                    s.p2 = rs.getLong("p2");
                    s.bsid = nullableLong(rs, "bsid");
                    s.bserial = nullableLong(rs, "bser");
                    s.binst = nullableLong(rs, "binst");
                    s.obj = nullableLong(rs, "obj");
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : Long.valueOf(v);
    }

    private static String cut(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }

    /** 계정명·대상 오브젝트명을 붙인다 - 둘 다 기존 캐시를 재사용하고 모르는 값만 조회한다(매 주기 dba_* 조인 금지). */
    private void enrich(Connection conn, String dbId, List<LockSample> samples,
                        java.util.function.Function<Set<Long>, Map<Long, String>> userResolver) {
        if (samples.isEmpty()) return;
        Set<Long> userIds = new HashSet<Long>();
        Set<Long> objIds = new HashSet<Long>();
        for (LockSample s : samples) {
            if (s.userId != null) userIds.add(s.userId);
            Long o = objectId(s);
            if (o != null) objIds.add(o);
        }
        Map<Long, String> users = userIds.isEmpty() ? Collections.<Long, String>emptyMap() : userResolver.apply(userIds);
        Map<Long, String> objs = objIds.isEmpty() ? Collections.<Long, String>emptyMap() : lockRealtime.objectNamesFor(conn, dbId, objIds);
        for (LockSample s : samples) {
            s.username = s.userId == null ? "" : (users.containsKey(s.userId) ? users.get(s.userId) : "#" + s.userId);
            Long o = objectId(s);
            s.objName = o == null ? null : objs.get(o);
        }
    }

    /** 대상 오브젝트 id: TM은 p2(object_id), TX는 current_obj#(-1·0이면 미상). */
    private static Long objectId(LockSample s) {
        if (isTm(s.event)) return s.p2 > 0 ? Long.valueOf(s.p2) : null;
        return s.obj != null && s.obj > 0 ? s.obj : null;
    }

    private static boolean isTm(String event) {
        return event != null && event.startsWith("enq: TM");
    }

    // ------------------------------------------------------------------ 락 발생 건 묶기·저장(샘플러의 순차 저장 단계)

    /** 한 사이클의 같은 키 연속 구간. */
    private static final class Episode {
        String type;
        long bsid;
        long bserial;
        long binst;
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        long samples;
        final Map<Long, Set<String>> perSecond = new HashMap<Long, Set<String>>();
        final Map<String, Long> eventCounts = new HashMap<String, Long>();
        final Map<Long, Long> modeCounts = new HashMap<Long, Long>();
        final Map<Long, Long> objCounts = new HashMap<Long, Long>();
        final Map<Long, String> objNames = new HashMap<Long, String>();
        final Map<String, WaiterAgg> waiters = new HashMap<String, WaiterAgg>();
        int maxWaiters;
        long peakTs;
        Map<String, Long> peakParent = new HashMap<String, Long>();
        final List<LockSample> sampleList = new ArrayList<LockSample>();
        String key;
    }

    private static final class WaiterAgg {
        long sid;
        long serial;
        String username;
        String module;
        String machine;
        String sqlId;
        long samples;
    }

    /** DB의 저장된 진행 중(OPEN) 건 하나. */
    private static final class Incident {
        String id;
        String type;
        long bsid;
        long bserial;
        long start;
        long end;
        long peakTs;
        int maxWaiters;
        long waitSessionSec;
        String key;
        Long objId;      // 저장된 대상 오브젝트(이번 사이클에 오브젝트를 모르면 이 값을 유지한다)
        String objName;
        List<Map<String, Object>> waiters;   // 피크 시점 스냅샷(새 피크가 나올 때만 교체)
        boolean touched;
        boolean isNew;
    }

    /**
     * 읽어 온 락 대기 샘플을 락 발생 건으로 묶어 저장한다. samples가 null이면(읽기 실패) 아무것도 하지 않는다 - 이번 분은 수집
     * 하트비트도 남기지 않아 공백이 된다. collectedUntil = 이번 사이클의 DB 시각.
     * continuous = 지난 사이클 이후의 ASH 구간을 빠짐없이 읽었는지(sample_id 구간). 앱 재기동 직후 첫 사이클처럼 최근 60초만
     * 읽은 사이클은 그 사이가 비어 있을 수 있어 하트비트를 남기지 않고 종료도 확정하지 않는다(2026-10-09 점검 보고 2).
     */
    public void ingest(String dbId, List<LockSample> samples, LocalDateTime collectedUntil, boolean continuous) {
        if (samples == null) return;
        long nowSec = collectedUntil.toEpochSecond(ZoneOffset.UTC);
        long nowMinute = PerfStoreService.minuteKey(collectedUntil);
        if (continuous) saveHeartbeat(dbId, nowMinute, "lock");
        long gap = settings.getInt(SettingKey.LOCK_GAP_SEC);

        List<Episode> episodes = buildEpisodes(samples, gap);
        Map<String, List<Incident>> open = loadOpen(dbId);
        List<Incident> changed = new ArrayList<Incident>();
        // 건마다 이번 사이클에 마지막으로 이어 붙인 구간(원인 분류용). 같은 키가 한 사이클에 두 건으로 나뉘면 키만으로 찾을 때
        // 뒤 건의 구간이 앞 건 분류에 쓰였다(2026-10-09 Phase 1a 점검 수정).
        Map<Incident, Episode> lastEp = new HashMap<Incident, Episode>();

        for (Episode ep : episodes) {
            List<Incident> list = open.get(ep.key);
            Incident target = null;
            if (list != null) {
                for (Incident inc : list) {
                    if (ep.start - inc.end <= gap && ep.start >= inc.start - gap) { target = inc; break; }
                }
            }
            if (target == null) {
                target = newIncident(dbId, ep);
                List<Incident> l = open.get(ep.key);
                if (l == null) { l = new ArrayList<Incident>(); open.put(ep.key, l); }
                l.add(target);
                target.isNew = true;
            }
            mergeEpisode(target, ep);
            target.touched = true;
            lastEp.put(target, ep);
            if (!changed.contains(target)) changed.add(target);
        }

        for (Incident inc : changed) {
            try {
                persist(dbId, inc, "OPEN", classify(inc, lastEp.get(inc)));
            } catch (Exception e) {
                log.warn("perf lock incident write failed for db_id={}: {}", dbId, e.toString());
            }
        }

        // 이번 사이클에 이어지지 않은 진행 중인 건: 끊김 기준이 지났으면 종료를 확정한다(수집 공백이 있으면 확정하지 않는다)
        for (List<Incident> list : open.values()) {
            if (!continuous) break; // 연속 구간이 아니면 종료를 확정하지 않는다(다음 연속 사이클에서 공백 여부와 함께 판정)
            for (Incident inc : list) {
                if (inc.touched || nowSec - inc.end <= gap) continue;
                long missing = missingHeartbeats(dbId, inc.end, nowMinute);
                String status = missing > HEARTBEAT_MISS_TOLERANCE ? "UNKNOWN" : "CLOSED";
                try {
                    jdbc.update("UPDATE perf_lock_incident SET status = ? WHERE incident_id = ?", status, inc.id);
                } catch (Exception e) {
                    log.warn("perf lock incident close failed for db_id={}: {}", dbId, e.toString());
                }
            }
        }
    }

    /** end 이후 ~ 지금 사이에 수집 하트비트가 없는 분 수(경계 두 분은 제외). */
    private long missingHeartbeats(String dbId, long endSec, long nowMinute) {
        long from = Math.floorDiv(endSec, 60L) + 1;
        long to = nowMinute; // [from, nowMinute)
        if (to <= from) return 0;
        Set<Long> have = heartbeatMinutes(dbId, "lock", from, to);
        return (to - from) - have.size();
    }

    private List<Episode> buildEpisodes(List<LockSample> samples, long gap) {
        // 같은 초에 기록된 대기자 → 직접 블로커 표(체인의 최종 블로커를 찾는 데 쓴다)
        Map<Long, Map<Long, long[]>> bySecond = new HashMap<Long, Map<Long, long[]>>();
        for (LockSample s : samples) {
            if (s.bsid == null || s.bsid <= 0) continue;
            Map<Long, long[]> m = bySecond.get(s.ts);
            if (m == null) { m = new HashMap<Long, long[]>(); bySecond.put(s.ts, m); }
            m.put(s.sid, new long[]{s.bsid, s.bserial == null ? 0 : s.bserial, s.binst == null ? 0 : s.binst});
        }
        // 키별로 샘플 모으기
        Map<String, List<Object[]>> byKey = new LinkedHashMap<String, List<Object[]>>(); // {sample, bsid, bserial, binst}
        for (LockSample s : samples) {
            String type = isTm(s.event) ? "TM" : "TX";
            long bsid = 0;
            long bserial = 0;
            long binst = 0;
            if (s.bsid != null && s.bsid > 0) {
                bsid = s.bsid;
                bserial = s.bserial == null ? 0 : s.bserial;
                binst = s.binst == null ? 0 : s.binst;
                Map<Long, long[]> m = bySecond.get(s.ts);
                for (int i = 0; i < 8 && m != null; i++) { // 최종 블로커(체인 끝): 블로커 자신도 같은 초에 대기 중이면 한 칸 더 올라간다
                    long[] up = m.get(bsid);
                    if (up == null || up[0] == s.sid || up[0] == bsid) break;
                    bsid = up[0];
                    bserial = up[1];
                    binst = up[2];
                }
            }
            Long o = objectId(s);
            // 블로커를 모르는 대기(ITL·인덱스 경합 등)는 대상 오브젝트로 구분해 서로 다른 건이 합쳐지지 않게 한다
            String key = type + "|" + bsid + "|" + bserial + (bsid == 0 ? "|OBJ" + (o == null ? 0 : o) : "");
            List<Object[]> l = byKey.get(key);
            if (l == null) { l = new ArrayList<Object[]>(); byKey.put(key, l); }
            l.add(new Object[]{s, Long.valueOf(bsid), Long.valueOf(bserial), Long.valueOf(binst)});
        }
        List<Episode> out = new ArrayList<Episode>();
        for (Map.Entry<String, List<Object[]>> en : byKey.entrySet()) {
            List<Object[]> l = en.getValue();
            Collections.sort(l, new Comparator<Object[]>() {
                @Override
                public int compare(Object[] a, Object[] b) {
                    return Long.compare(((LockSample) a[0]).ts, ((LockSample) b[0]).ts);
                }
            });
            Episode cur = null;
            long prev = 0;
            for (Object[] row : l) {
                LockSample s = (LockSample) row[0];
                if (cur == null || s.ts - prev > gap) {
                    cur = new Episode();
                    cur.key = en.getKey();
                    cur.type = isTm(s.event) ? "TM" : "TX";
                    cur.bsid = (Long) row[1];
                    cur.bserial = (Long) row[2];
                    cur.binst = (Long) row[3];
                    out.add(cur);
                }
                prev = s.ts;
                addSample(cur, s, (Long) row[1]);
            }
        }
        for (Episode ep : out) {
            for (Map.Entry<Long, Set<String>> e : ep.perSecond.entrySet()) {
                if (e.getValue().size() > ep.maxWaiters || (e.getValue().size() == ep.maxWaiters && e.getKey() < ep.peakTs)) {
                    ep.maxWaiters = e.getValue().size();
                    ep.peakTs = e.getKey();
                }
            }
        }
        // 피크 시점의 대기자별 직접 블로커(트리용)
        for (Episode ep : out) {
            for (LockSample s : ep.sampleList) {
                if (s.ts == ep.peakTs && s.bsid != null) ep.peakParent.put(s.sid + ":" + s.serial, s.bsid);
            }
        }
        return out;
    }

    private static void addSample(Episode ep, LockSample s, long finalBsid) {
        ep.sampleList.add(s);
        ep.start = Math.min(ep.start, s.ts);
        ep.end = Math.max(ep.end, s.ts);
        ep.samples++;
        String w = s.sid + ":" + s.serial;
        Set<String> set = ep.perSecond.get(s.ts);
        if (set == null) { set = new HashSet<String>(); ep.perSecond.put(s.ts, set); }
        set.add(w);
        inc(ep.eventCounts, s.event);
        inc(ep.modeCounts, Long.valueOf(s.p1 & 0xFFFF));
        Long o = objectId(s);
        if (o != null) {
            inc(ep.objCounts, o);
            if (s.objName != null) ep.objNames.put(o, s.objName);
        }
        WaiterAgg a = ep.waiters.get(w);
        if (a == null) {
            a = new WaiterAgg();
            a.sid = s.sid;
            a.serial = s.serial;
            a.username = s.username;
            a.module = s.module;
            a.machine = s.machine;
            a.sqlId = s.sqlId;
            ep.waiters.put(w, a);
        }
        a.samples++;
        if (s.sqlId != null) a.sqlId = s.sqlId;
    }

    private static <K> void inc(Map<K, Long> m, K k) {
        Long v = m.get(k);
        m.put(k, v == null ? 1L : v + 1);
    }

    private static <K> K top(Map<K, Long> m) {
        K best = null;
        long bv = -1;
        for (Map.Entry<K, Long> e : m.entrySet()) {
            if (e.getValue() > bv) { bv = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    private Incident newIncident(String dbId, Episode ep) {
        Incident inc = new Incident();
        inc.type = ep.type;
        inc.bsid = ep.bsid;
        inc.bserial = ep.bserial;
        inc.key = ep.key;
        inc.start = ep.start;
        inc.end = ep.end;
        inc.peakTs = ep.peakTs;
        // id는 묶는 키 전체로 만든다 - 블로커 미상(bsid=0) 건은 키에 대상 오브젝트가 들어가는데 id에 빠져 있으면, 서로 다른
        // 오브젝트의 ITL·인덱스 경합이 같은 초에 시작할 때 id가 겹쳐 뒤 건 INSERT가 PK 위반으로 버려졌다(2026-10-09 Phase 1a 점검 수정).
        // 블로커를 아는 건은 예전 형식(db_TX_sid_serial_start)과 같다.
        inc.id = dbId + "_" + ep.key.replace('|', '_') + "_" + ep.start;
        return inc;
    }

    private static void mergeEpisode(Incident inc, Episode ep) {
        inc.start = Math.min(inc.start, ep.start);
        inc.end = Math.max(inc.end, ep.end);
        inc.waitSessionSec += ep.samples;
        if (ep.maxWaiters > inc.maxWaiters) { // 기존 피크보다 클 때만 대기자 스냅샷을 바꾼다(불러온 진행 중 건은 스냅샷을 메모리에 두지 않는다)
            inc.maxWaiters = ep.maxWaiters;
            inc.peakTs = ep.peakTs;
            List<Map<String, Object>> w = new ArrayList<Map<String, Object>>();
            Set<String> atPeak = ep.perSecond.get(ep.peakTs);
            for (Map.Entry<String, WaiterAgg> e : ep.waiters.entrySet()) {
                if (atPeak != null && !atPeak.contains(e.getKey())) continue;
                WaiterAgg a = e.getValue();
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("sid", a.sid);
                m.put("serial", a.serial);
                m.put("username", a.username);
                m.put("module", a.module);
                m.put("machine", a.machine);
                m.put("sqlId", a.sqlId);
                m.put("waitSec", a.samples);
                Long parent = ep.peakParent.get(e.getKey());
                m.put("parentSid", parent);
                w.add(m);
            }
            inc.waiters = w;
        }
    }

    // ------------------------------------------------------------------ 원인 분류 (블로커 상세 없이 가능한 범위, 설계 1-3)

    private static final class Cause {
        String code;
        String text;
        String confidence;
        String event;
        Integer reqMode;
        Long objId;
        String objName;
    }

    /** 판정 순서: 이벤트명 → 요청 모드. 블로커 상세(1b)가 있어야 가능한 분류(TX_UNCOMMITTED, TM_DDL, TM_LOCK_TABLE, TM_FK_NOINDEX)는 하지 않는다. */
    private Cause classify(Incident inc, Episode ep) {
        Cause c = new Cause();
        if (ep == null) return c;
        c.event = top(ep.eventCounts);
        Long mode = top(ep.modeCounts);
        c.reqMode = mode == null ? null : Integer.valueOf(mode.intValue());
        c.objId = top(ep.objCounts);
        c.objName = c.objId == null ? null : ep.objNames.get(c.objId);
        String ev = c.event == null ? "" : c.event;
        if (ev.indexOf("allocate ITL") >= 0) {
            c.code = "TX_ITL";
            c.text = "ITL(트랜잭션 슬롯) 부족 - 같은 블록에 동시 DML이 몰렸습니다. INITRANS 조정을 검토하세요.";
            c.confidence = "HIGH";
        } else if (ev.indexOf("index contention") >= 0) {
            c.code = "TX_INDEX_SPLIT";
            c.text = "인덱스 리프 블록 분할 경합 - 오른쪽으로만 커지는 인덱스·시퀀스 키 여부를 확인하세요(해시 파티션, 리버스 키 검토). Unique 키 중복과 원인·조치가 다릅니다.";
            c.confidence = "HIGH";
        } else if (ev.indexOf("row lock contention") >= 0) {
            if (c.reqMode != null && c.reqMode == 4) {
                c.code = "TX_MODE4_OTHER";
                c.text = "요청 모드 4 - Unique 키 중복 INSERT 외에도 ITL 부족, 비트맵 인덱스, 분산·XA 트랜잭션이 모드 4를 만듭니다. 추가 확인이 필요합니다.";
                c.confidence = "MEDIUM";
            } else {
                c.code = "TX_ROW";
                c.text = "같은 행에 대한 갱신 경합(행 락 대기) - 블로커의 미커밋 여부는 블로커 상세 수집을 켜면 판정합니다. 같은 시간대 반복 여부를 확인하세요.";
                c.confidence = "MEDIUM";
            }
        } else if (ev.startsWith("enq: TM")) {
            c.code = "TM_OTHER";
            c.text = "테이블 락(TM) 대기 - FK 인덱스 미존재·DDL·LOCK TABLE 여부는 블로커 상세 수집을 켜면 판정합니다.";
            c.confidence = "LOW";
        } else {
            c.code = inc.type.equals("TM") ? "TM_OTHER" : "TX_ROW";
            c.confidence = "LOW";
        }
        return c;
    }

    // ------------------------------------------------------------------ DB 읽기·쓰기

    @SuppressWarnings("unchecked")
    private Map<String, List<Incident>> loadOpen(String dbId) {
        Map<String, List<Incident>> out = new HashMap<String, List<Incident>>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT incident_id, lock_type, blocker_sid, blocker_serial, start_ts, end_ts, peak_ts, max_waiters, wait_session_sec, obj_id, obj_name " +
                        "FROM perf_lock_incident WHERE db_id = ? AND status = 'OPEN'", dbId);
        for (Map<String, Object> r : rows) {
            Incident inc = new Incident();
            inc.id = (String) r.get("incident_id");
            inc.type = (String) r.get("lock_type");
            inc.bsid = r.get("blocker_sid") == null ? 0 : ((Number) r.get("blocker_sid")).longValue();
            inc.bserial = r.get("blocker_serial") == null ? 0 : ((Number) r.get("blocker_serial")).longValue();
            inc.start = ((Number) r.get("start_ts")).longValue();
            inc.end = ((Number) r.get("end_ts")).longValue();
            inc.peakTs = ((Number) r.get("peak_ts")).longValue();
            inc.maxWaiters = ((Number) r.get("max_waiters")).intValue();
            inc.waitSessionSec = ((Number) r.get("wait_session_sec")).longValue();
            long obj = r.get("obj_id") == null ? 0 : ((Number) r.get("obj_id")).longValue();
            inc.objId = r.get("obj_id") == null ? null : Long.valueOf(obj);
            inc.objName = (String) r.get("obj_name");
            inc.key = inc.type + "|" + inc.bsid + "|" + inc.bserial + (inc.bsid == 0 ? "|OBJ" + obj : "");
            List<Incident> l = out.get(inc.key);
            if (l == null) { l = new ArrayList<Incident>(); out.put(inc.key, l); }
            l.add(inc);
        }
        return out;
    }

    private void persist(String dbId, Incident inc, String status, Cause c) {
        long duration = Math.max(1, inc.end - inc.start);
        // 이번 사이클에 대상 오브젝트를 모르면(current_obj# 미상, dba_objects 조회 실패·캐시 비움) 저장된 값을 유지한다 - 덮어쓰면
        // 진행 중인 건의 오브젝트명이 마지막 사이클 사정으로 영구히 지워졌다(2026-10-09 Phase 1a 점검 수정).
        if (c.objId == null) {
            c.objId = inc.objId;
            c.objName = inc.objName;
        } else if (c.objName == null && c.objId.equals(inc.objId)) {
            c.objName = inc.objName;
        }
        inc.objId = c.objId;
        inc.objName = c.objName;
        String causeCode = c.code;
        Object[] vals = new Object[]{status, inc.start, inc.end, inc.peakTs, duration, inc.maxWaiters, inc.waitSessionSec,
                c.reqMode, c.event, c.objId, c.objName, causeCode, c.text, c.confidence};
        if (!inc.isNew) {
            jdbc.update("UPDATE perf_lock_incident SET status = ?, start_ts = ?, end_ts = ?, peak_ts = ?, duration_sec = ?, max_waiters = ?, " +
                    "wait_session_sec = ?, req_mode = ?, wait_event = ?, obj_id = ?, obj_name = ?, cause_code = ?, cause_text = ?, " +
                    "cause_confidence = ? WHERE incident_id = ?", concat(vals, inc.id));
        } else {
            jdbc.update("INSERT INTO perf_lock_incident (status, start_ts, end_ts, peak_ts, duration_sec, max_waiters, wait_session_sec, " +
                    "req_mode, wait_event, obj_id, obj_name, cause_code, cause_text, cause_confidence, incident_id, db_id, lock_type, " +
                    "blocker_sid, blocker_serial) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    concat(concat(vals, inc.id), dbId, inc.type, inc.bsid > 0 ? Long.valueOf(inc.bsid) : null,
                            inc.bsid > 0 ? Long.valueOf(inc.bserial) : null));
            inc.isNew = false;
        }
        if (inc.waiters != null) {
            jdbc.update("DELETE FROM perf_lock_incident_waiter WHERE incident_id = ?", inc.id);
            List<Object[]> args = new ArrayList<Object[]>();
            for (Map<String, Object> w : inc.waiters) {
                args.add(new Object[]{inc.id, w.get("sid"), w.get("serial"), w.get("username"), w.get("module"), w.get("machine"),
                        w.get("sqlId"), w.get("waitSec"), w.get("parentSid")});
            }
            jdbc.batchUpdate("INSERT INTO perf_lock_incident_waiter (incident_id, sid, serial_no, username, module, machine, sql_id, " +
                    "wait_sec, parent_sid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", args);
            inc.waiters = null; // 이번 사이클에 저장했다 - 다음 병합에서 새 피크가 나올 때만 다시 쓴다
        }
    }

    private static Object[] concat(Object[] a, Object... b) {
        Object[] r = new Object[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // ------------------------------------------------------------------ 보관

    @Scheduled(cron = "0 25 4 * * *")
    void purge() {
        long cutoffSec = PerfStoreService.minuteKey(LocalDateTime.now(ZoneOffset.UTC)) * 60L
                - settings.getInt(SettingKey.PERF_RETENTION_DAYS) * 86400L;
        long cutoffMin = Math.floorDiv(cutoffSec, 60L);
        int incidents = 0;
        // 한 번에 지우면 쓰기 잠금이 길어지므로 천 건씩(기존 purge 패턴)
        while (true) {
            // 상태와 무관하게 마지막 샘플이 보관 기간보다 오래된 건을 지운다 - DB 등록 삭제·수집 끔으로 영영 닫히지 않는 OPEN 건 정리(쿼리 검토 필수 3)
            List<String> ids = jdbc.queryForList("SELECT incident_id FROM perf_lock_incident WHERE end_ts < ? LIMIT 1000",
                    String.class, cutoffSec);
            if (ids.isEmpty()) break;
            for (String id : ids) {
                jdbc.update("DELETE FROM perf_lock_incident_waiter WHERE incident_id = ?", id);
                incidents += jdbc.update("DELETE FROM perf_lock_incident WHERE incident_id = ?", id);
            }
        }
        int beats = jdbc.update("DELETE FROM perf_collect_min WHERE minute_key < ?", cutoffMin);
        if (incidents > 0 || beats > 0) {
            log.info("perf lock purge: {} incident(s), {} heartbeat(s) older than {} day(s)", incidents, beats,
                    settings.getInt(SettingKey.PERF_RETENTION_DAYS));
        }
    }

    // ------------------------------------------------------------------ 조회(화면 API)

    private String stateOf(String dbId) {
        String[] st = collectState.get(dbId);
        return st == null ? "unknown" : st[0];
    }

    /** 화면에 보일 수집 상태: ok | unavailable(ASH 조회 불가) | error(최근 수집 실패) | unknown(아직 수집 시도 없음). */
    public Map<String, Object> collectStatus(String dbId) {
        String[] st = collectState.get(dbId);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("state", st == null ? "unknown" : st[0]);
        m.put("reason", st == null ? "" : st[1]);
        m.put("enabled", settings.getBool(SettingKey.LOCK_COLLECT_ENABLED));
        return m;
    }

    private static long sec(LocalDateTime t) {
        return t.toEpochSecond(ZoneOffset.UTC);
    }

    private static String iso(long ts) {
        return LocalDateTime.ofEpochSecond(ts, 0, ZoneOffset.UTC).toString();
    }

    private List<Map<String, Object>> queryIncidents(String dbId, LocalDateTime from, LocalDateTime to, AshRange.Filter f,
                                                      String type, int minSec, int limit) {
        List<Object> b = new ArrayList<Object>();
        StringBuilder sql = new StringBuilder("SELECT * FROM perf_lock_incident i WHERE i.db_id = ? AND i.start_ts < ? AND i.end_ts >= ?");
        b.add(dbId);
        b.add(sec(to));
        b.add(sec(from));
        if ("TX".equals(type) || "TM".equals(type)) {
            sql.append(" AND i.lock_type = ?");
            b.add(type);
        }
        if (minSec > 0) {
            sql.append(" AND i.duration_sec >= ?");
            b.add(Long.valueOf(minSec));
        }
        // 계정·접속 호스트 필터: 피크 시점 대기자 중 하나라도 맞으면 포함한다(블로커 계정은 블로커 상세 수집 전에는 모른다)
        if (f != null && (!f.users.isEmpty() || !f.machines.isEmpty())) {
            sql.append(" AND EXISTS (SELECT 1 FROM perf_lock_incident_waiter w WHERE w.incident_id = i.incident_id");
            if (!f.users.isEmpty()) {
                sql.append(" AND w.username IN (").append(placeholders(f.users.size())).append(")");
                b.addAll(f.users);
            }
            if (!f.machines.isEmpty()) {
                sql.append(" AND w.machine IN (").append(placeholders(f.machines.size())).append(")");
                b.addAll(f.machines);
            }
            sql.append(")");
        }
        sql.append(" ORDER BY i.start_ts DESC LIMIT ").append(limit);
        return jdbc.queryForList(sql.toString(), b.toArray());
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ", ?");
        return sb.toString();
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private Map<String, Object> toView(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("incidentId", r.get("incident_id"));
        m.put("lockType", r.get("lock_type"));
        m.put("status", r.get("status"));
        m.put("startTime", iso(num(r.get("start_ts"))));
        m.put("endTime", iso(num(r.get("end_ts"))));
        m.put("peakTime", iso(num(r.get("peak_ts"))));
        m.put("durationSec", num(r.get("duration_sec")));
        m.put("maxWaiters", num(r.get("max_waiters")));
        m.put("waitSessionSec", num(r.get("wait_session_sec")));
        m.put("blockerSid", r.get("blocker_sid"));
        m.put("blockerSerial", r.get("blocker_serial"));
        m.put("blockerModule", r.get("blocker_module"));
        m.put("reqMode", r.get("req_mode"));
        m.put("heldMode", r.get("held_mode"));
        m.put("waitEvent", r.get("wait_event"));
        m.put("objId", r.get("obj_id"));
        m.put("objName", r.get("obj_name"));
        m.put("causeCode", r.get("cause_code"));
        m.put("causeText", r.get("cause_text"));
        m.put("causeConfidence", r.get("cause_confidence"));
        return m;
    }

    public Map<String, Object> incidents(String dbId, LocalDateTime from, LocalDateTime to, AshRange.Filter f, String type, int minSec) {
        List<Map<String, Object>> rows = queryIncidents(dbId, from, to, f, type, minSec, LIST_LIMIT);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : rows) out.add(toView(r));
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("incidents", out);
        res.put("truncated", rows.size() >= LIST_LIMIT);
        res.put("collect", collectStatus(dbId));
        return res;
    }

    public Map<String, Object> summary(String dbId, LocalDateTime from, LocalDateTime to, AshRange.Filter f, String type, int minSec) {
        List<Map<String, Object>> rows = queryIncidents(dbId, from, to, f, type, minSec, LIST_LIMIT);
        long tx = 0;
        long tm = 0;
        long waitSec = 0;
        int maxWaiters = 0;
        Map<String, Object> longest = null;
        Set<String> blockers = new HashSet<String>();
        for (Map<String, Object> r : rows) {
            if ("TM".equals(r.get("lock_type"))) tm++; else tx++;
            waitSec += num(r.get("wait_session_sec"));
            maxWaiters = Math.max(maxWaiters, (int) num(r.get("max_waiters")));
            if (longest == null || num(r.get("duration_sec")) > num(longest.get("duration_sec"))) longest = r;
            if (r.get("blocker_sid") != null) blockers.add(r.get("blocker_sid") + ":" + r.get("blocker_serial"));
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("txCount", tx);
        res.put("tmCount", tm);
        res.put("totalCount", tx + tm);
        res.put("longestSec", longest == null ? null : Long.valueOf(num(longest.get("duration_sec"))));
        res.put("longestObject", longest == null ? null : longest.get("obj_name"));
        res.put("maxWaiters", maxWaiters);
        res.put("waitSessionSec", waitSec);
        res.put("distinctBlockers", blockers.size());
        res.put("truncated", rows.size() >= LIST_LIMIT);
        res.put("collect", collectStatus(dbId));
        return res;
    }

    /** 락 발생 건 하나 + 피크 시점 대기자 + 같은 오브젝트의 최근 7일 같은 시간대(±1시간) 반복 건수. */
    public Map<String, Object> incident(String dbId, String incidentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM perf_lock_incident WHERE db_id = ? AND incident_id = ?", dbId, incidentId);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        Map<String, Object> m = toView(r);
        List<Map<String, Object>> waiters = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> w : jdbc.queryForList(
                "SELECT sid, serial_no, username, module, machine, sql_id, wait_sec, parent_sid FROM perf_lock_incident_waiter " +
                        "WHERE incident_id = ? ORDER BY wait_sec DESC", incidentId)) {
            Map<String, Object> v = new LinkedHashMap<String, Object>();
            v.put("sid", w.get("sid"));
            v.put("serial", w.get("serial_no"));
            v.put("username", w.get("username"));
            v.put("module", w.get("module"));
            v.put("machine", w.get("machine"));
            v.put("sqlId", w.get("sql_id"));
            v.put("waitSec", w.get("wait_sec"));
            v.put("parentSid", w.get("parent_sid"));
            waiters.add(v);
        }
        m.put("waiters", waiters);
        m.put("repeatCount7d", repeatCount(dbId, r));
        m.put("collect", collectStatus(dbId));
        return m;
    }

    private long repeatCount(String dbId, Map<String, Object> r) {
        Object obj = r.get("obj_id");
        if (obj == null) return 0;
        long start = num(r.get("start_ts"));
        long tod = Math.floorMod(start, 86400L);
        long count = 0;
        List<Long> starts = jdbc.queryForList("SELECT start_ts FROM perf_lock_incident WHERE db_id = ? AND obj_id = ? AND lock_type = ? " +
                        "AND start_ts >= ? AND start_ts < ? AND incident_id <> ?", Long.class,
                dbId, obj, r.get("lock_type"), start - 7 * 86400L, start, r.get("incident_id"));
        for (Long s : starts) {
            long d = Math.abs(Math.floorMod(s, 86400L) - tod);
            if (Math.min(d, 86400L - d) <= 3600L) count++;
        }
        return count;
    }

    /** 블로커(SID·serial#)별 건수·최장 지속. 블로커 모듈은 블로커 상세 수집(1b)을 켜야 알 수 있다. */
    public Map<String, Object> topBlockers(String dbId, LocalDateTime from, LocalDateTime to, AshRange.Filter f, String type) {
        List<Map<String, Object>> rows = queryIncidents(dbId, from, to, f, type, 0, LIST_LIMIT);
        Map<String, Map<String, Object>> agg = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> r : rows) {
            String k = r.get("blocker_sid") == null ? "-" : r.get("blocker_sid") + ":" + r.get("blocker_serial");
            Map<String, Object> a = agg.get(k);
            if (a == null) {
                a = new LinkedHashMap<String, Object>();
                a.put("blockerSid", r.get("blocker_sid"));
                a.put("blockerSerial", r.get("blocker_serial"));
                a.put("blockerModule", r.get("blocker_module"));
                a.put("count", 0L);
                a.put("maxDurationSec", 0L);
                agg.put(k, a);
            }
            a.put("count", ((Long) a.get("count")) + 1);
            a.put("maxDurationSec", Math.max((Long) a.get("maxDurationSec"), num(r.get("duration_sec"))));
        }
        return topList(agg, "blockers", dbId);
    }

    public Map<String, Object> topObjects(String dbId, LocalDateTime from, LocalDateTime to, AshRange.Filter f, String type) {
        List<Map<String, Object>> rows = queryIncidents(dbId, from, to, f, type, 0, LIST_LIMIT);
        Map<String, Map<String, Object>> agg = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> r : rows) {
            String name = r.get("obj_name") != null ? (String) r.get("obj_name") : (r.get("obj_id") != null ? "OBJ#" + r.get("obj_id") : "-");
            String k = r.get("lock_type") + "|" + name;
            Map<String, Object> a = agg.get(k);
            if (a == null) {
                a = new LinkedHashMap<String, Object>();
                a.put("objName", name);
                a.put("lockType", r.get("lock_type"));
                a.put("count", 0L);
                a.put("maxDurationSec", 0L);
                agg.put(k, a);
            }
            a.put("count", ((Long) a.get("count")) + 1);
            a.put("maxDurationSec", Math.max((Long) a.get("maxDurationSec"), num(r.get("duration_sec"))));
        }
        return topList(agg, "objects", dbId);
    }

    private Map<String, Object> topList(Map<String, Map<String, Object>> agg, String key, String dbId) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>(agg.values());
        Collections.sort(list, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                int c = Long.compare((Long) b.get("count"), (Long) a.get("count"));
                return c != 0 ? c : Long.compare((Long) b.get("maxDurationSec"), (Long) a.get("maxDurationSec"));
            }
        });
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put(key, list.size() > 10 ? list.subList(0, 10) : list);
        res.put("collect", collectStatus(dbId));
        return res;
    }

    /**
     * 1분 단위 추이: TX/TM 평균 대기 세션 수(그 분의 락 대기 초 합 / 60). 값은 기존 perf_ash_min의 tx_lock/tm_lock 분류를 재사용한다
     * (새 1분 집계 테이블 없음). 수집 하트비트가 없는 분은 null(수집 공백), 이번 분처럼 아직 덜 찬 분도 null(수집 중).
     */
    public Map<String, Object> timeline(String dbId, LocalDateTime from, LocalDateTime to, AshRange.Filter f) {
        long fromKey = PerfStoreService.minuteKey(from);
        long toKey = PerfStoreService.minuteKey(to);
        Map<Long, Double> tx = new HashMap<Long, Double>();
        Map<Long, Double> tm = new HashMap<Long, Double>();
        for (Map<String, Object> r : store.minuteCategory(dbId, fromKey, toKey, f)) {
            String cat = (String) r.get("category");
            long k = num(r.get("minute_key"));
            double v = Math.round(num(r.get("seconds")) / 60.0 * 100.0) / 100.0;
            if ("tx_lock".equals(cat)) tx.put(k, v);
            else if ("tm_lock".equals(cat)) tm.put(k, v);
        }
        Set<Long> beats = heartbeatMinutes(dbId, "ash", fromKey, toKey);
        Long first = firstHeartbeat(dbId, "ash");
        Long collectedBefore = store.collectedBefore(dbId);
        List<Map<String, Object>> points = new ArrayList<Map<String, Object>>();
        int gaps = 0;
        for (long k = fromKey; k < toKey; k++) {
            Map<String, Object> p = new LinkedHashMap<String, Object>();
            p.put("time", PerfStoreService.fromMinuteKey(k).toString());
            boolean pending = collectedBefore != null && k >= collectedBefore;
            boolean covered = first != null && k >= first && beats.contains(k);
            if (!pending && covered) {
                p.put("tx", tx.containsKey(k) ? tx.get(k) : Double.valueOf(0));
                p.put("tm", tm.containsKey(k) ? tm.get(k) : Double.valueOf(0));
            } else {
                p.put("tx", null);
                p.put("tm", null);
                if (!pending && first != null && k >= first) gaps++;
            }
            points.add(p);
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("points", points);
        res.put("gapMinutes", gaps);
        res.put("collectedFrom", first == null ? null : PerfStoreService.fromMinuteKey(first).toString());
        res.put("collect", collectStatus(dbId));
        return res;
    }
}
