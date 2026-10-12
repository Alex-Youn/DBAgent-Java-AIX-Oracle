package com.dbagent.monitor;

import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 성능 분석 "오브젝트·I/O" (Phase 5, 2026-10-11, 설계문서 `성능분석 메뉴 설계.md` 5장). 새 원본 조회라 기본 꺼짐(dbagent.perf.obj-collect-enabled).
 *
 * <p>수집(주기 기본 10분, 전용 단일 스레드 - 공용 @Scheduled 스레드 2개를 DB 수 × 수 초 동안 잡지 않는다):
 * v$segstat(통계 5종 누적값 → 직전 값과의 델타, 통계별 상위 50개만 저장), 데이터파일 I/O(v$filemetric의 최근 10분 구간 값, 못 쓰는 DB만
 * v$filestat 누적값 → 델타)·v$tempstat(파일별 델타), v$log_history(최근 24시간 시간별
 * 스위치 수, 자기 스레드만), v$tempseg_usage(SQL별 Temp 사용). 모든 조회에 쿼리 제한 시간이 있고, 실패하면 그 항목은 건너뛴다(0으로 저장하지 않는다).
 * 앱을 재시작한 뒤 첫 주기는 델타 기준값만 기억하고 세그먼트·파일은 저장하지 않는다(음수 델타 = 인스턴스 재시작·오브젝트 재생성은 그 행만 버린다).
 * 단, v$filemetric으로 읽는 데이터파일 I/O는 이미 구간 값이라 첫 주기부터 저장한다.
 * 델타 기준값은 수만~수십만 건이 될 수 있어 박싱 맵 대신 기본형 배열 해시(LongLongMap)에 둔다(AIX 힙).
 *
 * <p>저장(perf.db/perf.mv.db): perf_obj_seg(통계별 상위 50), perf_obj_name(오브젝트 이름·유형 캐시), perf_obj_file(+ perf_obj_file_name),
 * perf_obj_redo(스레드·시간별, 수집할 때마다 최근 24시간을 지우고 다시 쓴다 - 진행 중인 시간은 다음 주기에 갱신), perf_obj_temp.
 * 성공한 주기는 perf_collect_min(kind='obj')에 남긴다. SQLite(메인)·H2 1.4.200(AIX) 공통 문법, Java 8 문법만 쓴다.
 */
@Service
public class PerfObjService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(PerfObjService.class);

    /** {Oracle 통계 이름, 저장 코드}. 코드의 순서가 델타 키의 통계 번호다(0~4). */
    static final String[][] STATS = {
            {"logical reads", "lr"}, {"physical reads", "pr"}, {"row lock waits", "rlw"}, {"ITL waits", "itl"}, {"buffer busy waits", "bbw"}};
    static final int SEG_TOP_N = 50;
    /** 이 수집은 주기가 길어(기본 10분) lock 조회(3초)보다 여유를 둔다. 넘기면 그 주기는 건너뛴다. */
    private static final int QUERY_TIMEOUT_SEC = 10;
    /** v$segstat에서 읽을 최대 행 수 - 넘으면 그 주기는 건너뛴다(메모리·전송 보호). */
    private static final int SEG_ROW_CAP = 400000;
    private static final long TEMP_MIN_KB = 1024;
    private static final int TEMP_TOP_N = 20;
    private static final int TEMP_ROWS = 20;
    private static final int FILE_ROWS = 100;
    private static final int RESOLVE_CHUNK = 500;
    private static final int RESOLVE_MAX_CHUNKS = 4;
    /** 바인드 개수를 이 크기로 맞춘다(남는 자리는 -1) - IN 목록 길이마다 새 커서가 생겨 대상 DB shared pool을 키우는 것을 막는다. */
    private static final int[] IN_BUCKETS = {10, 50, 500};
    /** 못 찾은 오브젝트(삭제된 것 등)를 다시 묻지 않는 시간 - 수집 주기보다 훨씬 길게 두어 매 주기 딕셔너리 조회가 나가지 않게 한다. */
    private static final long NAME_MISS_MS = 6L * 3600 * 1000;
    /** 이름 조회가 실패(타임아웃 등)했을 때 다시 묻기 전 대기. */
    private static final long NAME_FAIL_MS = 10L * 60 * 1000;
    /** dba_objects 접근 권한이 없으면(ORA-00942·01031) 이 시간 동안 그 DB의 이름 해석을 쉰다. */
    private static final long NAME_OFF_MS = 3600L * 1000;
    /** v$segstat 행이 상한을 넘은 DB는 이 시간 동안 세그먼트 읽기를 쉰다(매 주기 수십만 행을 읽고 버리지 않게). */
    private static final long SEG_SKIP_MS = 6L * 3600 * 1000;
    private static final int REDO_WINDOW_HOURS = 24;
    /** 한 주기에 저장하는 파일 수 상한(읽기+쓰기가 많은 순) - 파일이 수천 개인 DB에서 저장량을 묶는다. */
    private static final int FILE_STORE_N = 100;
    /** v$filemetric을 쓸 수 없는 DB(PDB 접속은 0행, 권한 없음, 갱신 안 됨)는 이 시간 동안 다시 묻지 않고 v$filestat으로 수집한다. */
    private static final long FILE_METRIC_OFF_MS = 6L * 3600 * 1000;
    /** v$filemetric의 구간 종료 시각이 DB 현재 시각보다 이만큼(분) 넘게 오래됐으면 갱신되지 않는 것으로 본다(정상은 10분 구간). */
    private static final long FILE_METRIC_STALE_MIN = 30;
    /** 데이터파일 이름을 한 번 읽은 뒤, 모르는 파일 번호가 나와도 이 시간 안에는 다시 읽지 않는다. */
    private static final long FILE_NAME_RELOAD_MS = 3600L * 1000;

    /** 수집 전용 스레드. */
    private static final java.util.concurrent.ExecutorService JOB_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "perf-obj-collect");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    private SettingsService settings;

    private final JdbcTemplate jdbc;
    private final boolean h2;
    private final TransactionTemplate tx;
    private final PerfLockService heartbeats;
    private final DatabaseConfigService configService;
    private final OracleConnectionPoolManager poolManager;

    /** DB별 직전 스냅샷(델타 기준값). */
    private final ConcurrentHashMap<String, Snap> prev = new ConcurrentHashMap<String, Snap>();
    /** DB별 마지막 수집 상태 {ok|error, 사유, 시각 ms}. */
    private final ConcurrentHashMap<String, String[]> collectState = new ConcurrentHashMap<String, String[]>();
    /** DB별 v$ 뷰에 con_id 컬럼이 있는지(12c+). 없으면(11g, ORA-00904) 그 조건을 뺀다. */
    private final ConcurrentHashMap<String, Boolean> segConId = new ConcurrentHashMap<String, Boolean>();
    private final ConcurrentHashMap<String, Boolean> fileConId = new ConcurrentHashMap<String, Boolean>();
    /** DB별 v$filemetric을 다시 물어도 되는 시각(ms). 그 전까지는 v$filestat 누적값으로 수집한다. */
    private final ConcurrentHashMap<String, Long> fileMetricOffUntil = new ConcurrentHashMap<String, Long>();
    /** 데이터파일 이름 캐시: DB → (file# → {테이블스페이스, 파일명})와, 모르는 번호가 나왔을 때 다시 읽어도 되는 시각(ms). */
    private final ConcurrentHashMap<String, Map<Long, String[]>> dataFileNames = new ConcurrentHashMap<String, Map<Long, String[]>>();
    private final ConcurrentHashMap<String, Long> dataFileNamesRetryAt = new ConcurrentHashMap<String, Long>();
    private final ConcurrentHashMap<String, Long> blockSize = new ConcurrentHashMap<String, Long>();
    /** 이름 캐시: DB → (object_id → {owner.name[:partition], type}). 못 찾은 id는 일정 시간 다시 묻지 않는다. */
    private final ConcurrentHashMap<String, Map<Long, String[]>> names = new ConcurrentHashMap<String, Map<Long, String[]>>();
    private final ConcurrentHashMap<String, Map<Long, Long>> missingUntil = new ConcurrentHashMap<String, Map<Long, Long>>();
    /** perf_obj_name에 이미 쓴 (DB:object_id) - 같은 이름을 주기마다 다시 쓰지 않는다(커밋에 성공한 뒤에만 넣는다). */
    private final Set<String> persistedNames = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** perf_obj_file_name에 이미 쓴 내용(DB:종류:번호 → 테이블스페이스|파일명) - 바뀐 파일만 다시 쓴다. */
    private final ConcurrentHashMap<String, String> persistedFiles = new ConcurrentHashMap<String, String>();
    /** DB별 세그먼트 읽기를 쉬는 시각(ms), 이름 해석을 쉬는 시각(ms). */
    private final ConcurrentHashMap<String, Long> segSkipUntil = new ConcurrentHashMap<String, Long>();
    private final ConcurrentHashMap<String, Long> nameOffUntil = new ConcurrentHashMap<String, Long>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PerfObjService(@Qualifier("perfJdbcTemplate") JdbcTemplate jdbc, PerfLockService heartbeats,
                          DatabaseConfigService configService, OracleConnectionPoolManager poolManager) {
        this.jdbc = jdbc;
        this.heartbeats = heartbeats;
        this.configService = configService;
        this.poolManager = poolManager;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        String url = jdbc.getDataSource() instanceof com.zaxxer.hikari.HikariDataSource
                ? ((com.zaxxer.hikari.HikariDataSource) jdbc.getDataSource()).getJdbcUrl() : null;
        this.h2 = url != null && url.startsWith("jdbc:h2:");
    }

    @Override
    public void afterPropertiesSet() {
        // SQLite는 WITHOUT ROWID(기본키 자동 인덱스에 키 컬럼이 이중으로 저장되는 것을 없앤다, H2에는 없는 문법). 컬럼 이름에 value·delta 같은 예약어를 피한다.
        String tail = h2 ? ")" : ") WITHOUT ROWID";
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_obj_seg (" +
                "db_id VARCHAR(64) NOT NULL, minute_key BIGINT NOT NULL, stat VARCHAR(4) NOT NULL, obj_id BIGINT NOT NULL, val BIGINT NOT NULL, " +
                "PRIMARY KEY (db_id, minute_key, stat, obj_id)" + tail);
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_obj_name (" +
                "db_id VARCHAR(64) NOT NULL, obj_id BIGINT NOT NULL, obj_name VARCHAR(400) NOT NULL, obj_type VARCHAR(30), " +
                "PRIMARY KEY (db_id, obj_id)" + tail);
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_obj_file (" +
                "db_id VARCHAR(64) NOT NULL, minute_key BIGINT NOT NULL, kind VARCHAR(1) NOT NULL, file_no BIGINT NOT NULL, " +
                "reads_d BIGINT NOT NULL, writes_d BIGINT NOT NULL, readtim_d BIGINT NOT NULL, writetim_d BIGINT NOT NULL, " +
                "PRIMARY KEY (db_id, minute_key, kind, file_no)" + tail);
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_obj_file_name (" +
                "db_id VARCHAR(64) NOT NULL, kind VARCHAR(1) NOT NULL, file_no BIGINT NOT NULL, ts_name VARCHAR(128), file_name VARCHAR(513), " +
                "PRIMARY KEY (db_id, kind, file_no)" + tail);
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_obj_redo (" +
                "db_id VARCHAR(64) NOT NULL, thread_no BIGINT NOT NULL, hour_key BIGINT NOT NULL, switches BIGINT NOT NULL, " +
                "PRIMARY KEY (db_id, thread_no, hour_key)" + tail);
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_obj_temp (" +
                "db_id VARCHAR(64) NOT NULL, minute_key BIGINT NOT NULL, sql_id VARCHAR(13) NOT NULL, module VARCHAR(64), temp_kb BIGINT NOT NULL, " +
                "PRIMARY KEY (db_id, minute_key, sql_id)" + tail);
    }

    /** DB별 설정(없으면 전역값). 데이터파일이 수천 개인 DB처럼 부담이 큰 DB만 끄거나, 일부 DB만 켤 수 있다(2026-10-12). */
    public boolean enabled(String dbId) {
        return settings.getBool(SettingKey.OBJ_COLLECT_ENABLED, dbId);
    }

    // ------------------------------------------------------------------ 기본형 해시 (델타 기준값)

    /** long → long 해시(열린 주소법). 키는 0 이상이다(-1이 빈 칸). 수십만 건이어도 16B × 2 안팎이라 박싱 맵보다 훨씬 가볍다. */
    static final class LongLongMap {
        long[] keys;
        long[] vals;
        int size;
        int mask;

        LongLongMap(int expected) {
            int n = 16;
            while (n < expected * 2) n <<= 1;
            keys = new long[n];
            Arrays.fill(keys, -1L);
            vals = new long[n];
            mask = n - 1;
        }

        private int slot(long key) {
            int h = (int) (key ^ (key >>> 32)) * 0x9E3779B9;
            return (h ^ (h >>> 16)) & mask;
        }

        void put(long key, long val) {
            if (size * 2 >= keys.length) grow();
            int i = slot(key);
            while (keys[i] != -1L && keys[i] != key) i = (i + 1) & mask;
            if (keys[i] == -1L) { keys[i] = key; size++; }
            vals[i] = val;
        }

        long get(long key, long def) {
            int i = slot(key);
            while (keys[i] != -1L) {
                if (keys[i] == key) return vals[i];
                i = (i + 1) & mask;
            }
            return def;
        }

        private void grow() {
            long[] ok = keys;
            long[] ov = vals;
            keys = new long[ok.length * 2];
            Arrays.fill(keys, -1L);
            vals = new long[ok.length * 2];
            mask = keys.length - 1;
            size = 0;
            for (int i = 0; i < ok.length; i++) if (ok[i] != -1L) put(ok[i], ov[i]);
        }
    }

    /**
     * 직전 스냅샷: 세그먼트(키 = dataobj# × 8 + 통계 번호)와 파일(키 "D:번호"/"T:번호" → {reads, writes, readtim, writetim}).
     * key(수집 분)·ident(인스턴스 번호:기동 시각)는 기준값을 믿어도 되는지 판단하는 데 쓴다 - 오래된 기준값(수집이 끊겼다 켜짐 등)이나
     * 다른 인스턴스·재시작 전 값과 빼면 누적값 전체가 델타로 저장된다(2026-10-11 점검).
     */
    private static final class Snap {
        long key;
        String ident;
        LongLongMap seg;
        Map<String, long[]> files = new HashMap<String, long[]>();
    }

    /** v$segstat 읽기 결과: vals = dataobj#×8+통계 번호 → 값, objOf = dataobj# → 대표 obj#(이름 해석용). */
    private static final class SegRead {
        LongLongMap vals = new LongLongMap(4096);
        LongLongMap objOf = new LongLongMap(4096);
    }

    /** 한 주기에 읽은 것(Oracle 커넥션을 반납한 뒤 저장한다). */
    private static final class Fetched {
        long key;
        long thread;
        List<long[]> segRows;
        Map<Long, String[]> segNames = Collections.emptyMap();
        /** 데이터파일 행과 그 행을 저장할 분. v$filemetric이면 구간 종료 시각(같은 구간을 두 번 읽어도 같은 자리에 덮어쓴다), v$filestat 델타면 수집 분. */
        List<Object[]> dataFileRows;
        long dataFileKey;
        /** Temp 파일 델타 행(수집 분에 저장). */
        List<Object[]> tempFileRows;
        Map<String, String[]> fileNames = new HashMap<String, String[]>();
        List<Object[]> redoRows;
        List<Object[]> tempRows;
        boolean ok = true;
        String reason = "";
    }

    // ------------------------------------------------------------------ 수집 (전용 스레드)

    /** 주기는 시작할 때 한 번 읽는다(스케줄러 주기라 재시작이 필요한 설정). 켜기/끄기는 매 주기 확인한다. */
    @Scheduled(fixedDelayString = "#{T(java.lang.Math).max(1, ${dbagent.perf.obj-collect-interval-min:10}) * 60000}", initialDelay = 120000)
    void tick() {
        // 켜기/끄기는 DB별 설정이라 여기서 전체를 건너뛰지 않고 collectAll에서 DB마다 확인한다(모두 꺼져 있으면 대상 DB 조회는 없다)
        if (!running.compareAndSet(false, true)) return; // 이전 주기가 아직 돌고 있으면 겹쳐 돌리지 않는다
        JOB_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    collectAll();
                } finally {
                    running.set(false);
                }
            }
        });
    }

    void collectAll() {
        List<TargetDbConfig> targets;
        try {
            targets = configService.listAllInstances();
        } catch (RuntimeException e) {
            log.warn("perf obj collect skipped - could not load DB instances: {}", e.toString());
            return;
        }
        Set<String> alive = new HashSet<String>();
        for (TargetDbConfig t : targets) {
            if (!"oracle".equalsIgnoreCase(t.dbType())) continue;
            if (!enabled(t.id())) {
                // 꺼져 있는 동안 기준값을 들고 있지 않는다(메모리) - 다시 켜면 첫 주기는 기준값만 기억한다(꺼진 기간 전체가 한 번에 델타로 저장되지 않게)
                prev.remove(t.id());
                continue;
            }
            alive.add(t.id());
            try {
                collectOne(t);
            } catch (Exception e) {
                log.warn("perf obj collect failed for db_id={}: {}", t.id(), e.toString());
                collectState.put(t.id(), new String[]{"error", "수집 중 오류", String.valueOf(System.currentTimeMillis())});
            }
        }
        // 설정에서 빠진 DB의 기준값·캐시는 버린다(메모리)
        prev.keySet().retainAll(alive);
        names.keySet().retainAll(alive);
        missingUntil.keySet().retainAll(alive);
        dataFileNames.keySet().retainAll(alive);
        dataFileNamesRetryAt.keySet().retainAll(alive);
        fileMetricOffUntil.keySet().retainAll(alive);
    }

    private static void fail(ConcurrentHashMap<String, String[]> st, String dbId, String what, SQLException e) {
        st.put(dbId, new String[]{"error", what + " ORA-" + String.format("%05d", e.getErrorCode()), String.valueOf(System.currentTimeMillis())});
    }

    void collectOne(TargetDbConfig target) {
        final String dbId = target.id();
        Fetched f;
        // 대상 DB 조회가 끝나면 커넥션을 먼저 반납하고(try 블록 끝) 그 뒤에 수집 저장소에 쓴다 - 저장이 느려도 대상 DB 세션을 붙들고 있지 않는다
        try (Connection conn = poolManager.getConnection(target)) {
            f = fetch(conn, dbId);
        } catch (SQLException e) {
            fail(collectState, dbId, "접속·시각 조회", e);
            log.debug("perf obj collect skipped for db_id={}: {}", dbId, e.toString());
            return;
        }
        store(dbId, f);
        if (f.segRows != null) {
            // 세그먼트 델타까지 계산된 주기만 하트비트(수집 커버율·"수집 시작" 판정) - 기준값만 기억한 첫 주기는 남기지 않는다
            heartbeats.saveHeartbeat(dbId, f.key, "obj");
        }
        collectState.put(dbId, new String[]{f.ok ? "ok" : "error", f.reason, String.valueOf(System.currentTimeMillis())});
    }

    /** 대상 DB에서 이번 주기 값을 읽는다. 항목별로 실패를 격리한다(실패한 항목은 건너뛰고 0으로 저장하지 않는다). */
    private Fetched fetch(Connection conn, String dbId) throws SQLException {
        Fetched f = new Fetched();
        // DB 시각·접속 인스턴스(redo 스레드 번호·인스턴스 번호·기동 시각). v$ 뷰는 인스턴스별 누적값이라 인스턴스가 바뀌거나 재시작되면 기준값을 버려야 한다.
        LocalDateTime dbNow;
        String ident;
        try (PreparedStatement ps = conn.prepareStatement("SELECT SYSDATE, i.thread#, i.instance_number, i.startup_time FROM v$instance i")) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                dbNow = rs.getTimestamp(1).toLocalDateTime();
                f.thread = rs.getLong(2);
                Timestamp up = rs.getTimestamp(4);
                ident = rs.getLong(3) + ":" + (up == null ? 0 : up.getTime());
            }
        }
        f.key = PerfStoreService.minuteKey(dbNow);

        // 기준값 유효성: 처음이거나, 다른 인스턴스·재시작 뒤이거나, 같은 분에 또 수집했거나, 너무 오래된(수집이 끊겼다 이어진) 기준값이면 기준값만 새로 기억한다
        Snap before = prev.get(dbId);
        long maxGap = 2L * settings.getInt(SettingKey.OBJ_COLLECT_INTERVAL_MIN) + 1;
        if (before != null && (!ident.equals(before.ident) || f.key <= before.key || f.key - before.key > maxGap)) before = null;
        Snap now = new Snap();
        now.key = f.key;
        now.ident = ident;

        // 1) 세그먼트 통계
        long nowMs = System.currentTimeMillis();
        Long skipUntil = segSkipUntil.get(dbId);
        if (skipUntil != null && skipUntil > nowMs) {
            f.ok = false;
            f.reason = "세그먼트 수가 상한(" + SEG_ROW_CAP + "행)을 넘어 세그먼트 수집을 쉬는 중입니다";
        } else {
            try {
                SegRead cur = readSegments(conn, dbId);
                now.seg = cur.vals;
                if (before != null && before.seg != null) f.segRows = topDeltas(cur, before.seg);
                if (f.segRows != null && !f.segRows.isEmpty()) {
                    Set<Long> ids = new HashSet<Long>();
                    for (long[] r : f.segRows) ids.add(r[0]);
                    f.segNames = resolveNames(conn, dbId, ids);
                }
            } catch (SQLException e) {
                f.ok = false;
                if ("CAP".equals(e.getSQLState())) {
                    segSkipUntil.put(dbId, nowMs + SEG_SKIP_MS);
                    f.reason = "세그먼트 수가 상한(" + SEG_ROW_CAP + "행)을 넘어 세그먼트 수집을 쉽니다";
                } else {
                    f.reason = "세그먼트 통계 ORA-" + String.format("%05d", e.getErrorCode());
                }
                log.debug("perf obj segstat skipped for db_id={}: {}", dbId, e.toString());
            }
        }

        // 2) 데이터파일·Temp 파일 I/O
        try {
            Map<String, long[]> cur = new HashMap<String, long[]>();
            // Temp 파일은 수가 적어 v$tempstat 누적값을 그대로 읽는다(이름 조인 포함)
            readTempFiles(conn, dbId, cur, f.fileNames);
            // 데이터파일은 v$filemetric(DB가 10분마다 계산해 둔 구간 값)을 먼저 쓰고, 못 쓰는 DB만 v$filestat 누적값을 읽는다
            List<Object[]> metric = readFileMetric(conn, dbId, dbNow, f);
            if (metric == null) readDataFileStats(conn, cur);
            now.files = cur;
            if (before != null) f.tempFileRows = fileDeltas(cur, before.files, "T");
            if (metric != null) {
                f.dataFileRows = metric;
            } else if (before != null) {
                f.dataFileRows = fileDeltas(cur, before.files, "D");
                f.dataFileKey = f.key;
            }
            if (f.dataFileRows != null && !f.dataFileRows.isEmpty()) addDataFileNames(conn, dbId, f.dataFileRows, f.fileNames);
        } catch (SQLException e) {
            if (f.ok) f.reason = "파일 I/O ORA-" + String.format("%05d", e.getErrorCode());
            f.ok = false;
            log.debug("perf obj filestat skipped for db_id={}: {}", dbId, e.toString());
        }

        // 3) Redo 스위치(최근 24시간)
        try {
            f.redoRows = readRedo(conn, f.thread, dbNow);
        } catch (SQLException e) {
            if (f.ok) f.reason = "Redo 이력 ORA-" + String.format("%05d", e.getErrorCode());
            f.ok = false;
            log.debug("perf obj log_history skipped for db_id={}: {}", dbId, e.toString());
        }

        // 4) Temp 사용 SQL
        try {
            f.tempRows = readTemp(conn, dbId);
        } catch (SQLException e) {
            if (f.ok) f.reason = "Temp 사용 ORA-" + String.format("%05d", e.getErrorCode());
            f.ok = false;
            log.debug("perf obj tempseg skipped for db_id={}: {}", dbId, e.toString());
        }

        // 기준값 갱신: 이번에 못 읽은 부분은 비워 둔다(다음 주기에 기준값만 기억 - 오래된 값과 빼서 긴 구간의 델타가 저장되지 않게)
        prev.put(dbId, now);
        return f;
    }

    // ------------------------------------------------------------------ 원본 조회

    /**
     * v$segstat 통계 5종(값 > 0). 키 = dataobj# × 8 + 통계 번호 - **obj#가 아니라 세그먼트(dataobj#)로 센다**: 클러스터에 묶인 테이블들은 세그먼트 한 개가
     * v$segstat에 한 행으로 나오는데 그 행의 obj#가 읽을 때마다 바뀌고(실측: TAB$·COL$ 누적값 약 100만이 매 주기 델타로 저장됨), 같은 obj#가
     * dataobj#만 다른 여러 행으로 나오기도 한다(TRUNCATE·임시 테이블). 같은 키는 값을 합산한다. 행이 상한을 넘으면 CAP 예외로 알린다.
     */
    private SegRead readSegments(Connection conn, String dbId) throws SQLException {
        StringBuilder in = new StringBuilder();
        StringBuilder dec = new StringBuilder();
        for (int i = 0; i < STATS.length; i++) {
            in.append(i == 0 ? "" : ", ").append("'").append(STATS[i][0]).append("'");
            dec.append("'").append(STATS[i][0]).append("', ").append(i).append(", ");
        }
        // 통계 이름 문자열을 행마다 받지 않도록 번호로 받는다(전송량). 상수만 쓰므로 커서는 하나다.
        String base = "SELECT obj#, dataobj#, DECODE(statistic_name, " + dec + "-1), value FROM v$segstat WHERE statistic_name IN (" + in + ") AND value > 0";
        Boolean cap = segConId.get(dbId);
        try {
            return readSegmentsSql(conn, base + (Boolean.FALSE.equals(cap) ? "" : " AND con_id = SYS_CONTEXT('USERENV', 'CON_ID')"));
        } catch (SQLException e) {
            // 11g는 con_id 컬럼이 없다(ORA-00904) - 그 조건을 빼고 다시 읽는다. CDB 루트의 같은 obj#가 PDB마다 겹치는 문제는 12c+에서만 있다
            if (e.getErrorCode() == 904 && !Boolean.FALSE.equals(cap)) {
                segConId.put(dbId, Boolean.FALSE);
                return readSegmentsSql(conn, base);
            }
            throw e;
        }
    }

    private SegRead readSegmentsSql(Connection conn, String sql) throws SQLException {
        SegRead r = new SegRead();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            ps.setFetchSize(2000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int si = rs.getInt(3);
                    if (si < 0 || si >= STATS.length) continue;
                    long data = rs.getLong(2);
                    long k = data * 8 + si;
                    r.vals.put(k, r.vals.get(k, 0L) + rs.getLong(4));
                    r.objOf.put(data, rs.getLong(1));
                    if (r.vals.size > SEG_ROW_CAP) {
                        log.warn("perf obj segstat rows over {} - segment collection paused for this DB", SEG_ROW_CAP);
                        throw new SQLException("v$segstat rows over cap", "CAP");
                    }
                }
            }
        }
        return r;
    }

    /**
     * 통계별 델타 상위 N개: {obj_id, 통계 번호, 델타}. 세그먼트(dataobj#)별 델타를 구한 뒤 오브젝트(obj#)별로 합산해 순위를 낸다
     * (같은 obj#가 여러 세그먼트로 나오는 경우 저장 키가 겹치지 않게). 델타 0 이하(변화 없음·카운터 리셋)는 버린다. 직전 값에 없던 키는 0에서 시작한
     * 것으로 본다(값이 0이어서 읽지 않던 세그먼트). 기준값이 유효한지는 호출자가 이미 확인했다.
     */
    private List<long[]> topDeltas(SegRead cur, LongLongMap old) {
        List<Map<Long, Long>> acc = new ArrayList<Map<Long, Long>>();
        for (int i = 0; i < STATS.length; i++) acc.add(new HashMap<Long, Long>());
        LongLongMap c = cur.vals;
        for (int i = 0; i < c.keys.length; i++) {
            long k = c.keys[i];
            if (k == -1L) continue;
            long d = c.vals[i] - old.get(k, 0L);
            if (d <= 0) continue;
            long obj = cur.objOf.get(k >> 3, -1L);
            if (obj < 0) continue;
            Map<Long, Long> m = acc.get((int) (k & 7));
            Long prevD = m.get(obj);
            m.put(obj, prevD == null ? d : prevD + d);
        }
        List<long[]> out = new ArrayList<long[]>();
        for (int si = 0; si < STATS.length; si++) {
            PriorityQueue<long[]> h = new PriorityQueue<long[]>(SEG_TOP_N + 1, new Comparator<long[]>() {
                @Override
                public int compare(long[] a, long[] b) { return Long.compare(a[2], b[2]); }
            });
            for (Map.Entry<Long, Long> e : acc.get(si).entrySet()) {
                if (h.size() < SEG_TOP_N) h.add(new long[]{e.getKey(), si, e.getValue()});
                else if (h.peek()[2] < e.getValue()) { h.poll(); h.add(new long[]{e.getKey(), si, e.getValue()}); }
            }
            out.addAll(h);
        }
        return out;
    }

    /**
     * Temp 파일 누적값 읽기. cur: "T:번호" → {reads, writes, readtim, writetim}, names: 같은 키 → {tablespace, 파일명}.
     * fetch에서 가장 먼저 부른다(con_id 없는 DB에서 다시 읽을 때 cur·fileNames를 비우므로).
     */
    private void readTempFiles(Connection conn, String dbId, Map<String, long[]> cur, Map<String, String[]> fileNames) throws SQLException {
        Boolean cap = fileConId.get(dbId);
        try {
            readTempFilesSql(conn, Boolean.FALSE.equals(cap), cur, fileNames);
        } catch (SQLException e) {
            if (e.getErrorCode() == 904 && !Boolean.FALSE.equals(cap)) {
                fileConId.put(dbId, Boolean.FALSE);
                cur.clear();
                fileNames.clear();
                readTempFilesSql(conn, true, cur, fileNames);
            } else {
                throw e;
            }
        }
    }

    private void readTempFilesSql(Connection conn, boolean noConId, Map<String, long[]> cur, Map<String, String[]> fileNames) throws SQLException {
        // 루트 접속(12c+ CDB)은 ts#가 컨테이너마다 겹치므로 con_id까지 맞춰 이름을 붙인다. 11g는 con_id가 없어 뺀다.
        String dj = noConId ? "" : " AND t.con_id = d.con_id AND d.con_id = f.con_id";
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT f.file#, d.name, t.name, f.phyrds, f.phywrts, f.readtim, f.writetim FROM v$tempstat f " +
                        "JOIN v$tempfile d ON d.file# = f.file# JOIN v$tablespace t ON t.ts# = d.ts#" + dj)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String k = "T:" + rs.getLong(1);
                    cur.put(k, new long[]{rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)});
                    fileNames.put(k, new String[]{rs.getString(3), rs.getString(2)});
                }
            }
        }
    }

    /**
     * 데이터파일 I/O를 v$filemetric에서 읽는다: DB가 10분마다 계산해 둔 "최근 구간" 값이라 누적값의 차이를 구할 필요가 없고 가볍다.
     * (2026-10-12 폐쇄망 실측, 데이터파일 3,721개인 11gR2: v$filestat 3.66초·이름 조인까지 6.24초, v$filemetric 0.04초)
     * 행 = {"D", file#, reads, writes, readtim, writetim}. 시간은 v$filestat과 같은 1/100초 합계로 맞춘다(평균(1/100초/회) × 횟수).
     * 저장할 분(f.dataFileKey)은 구간 종료 시각이다 - 수집 주기와 구간이 어긋나 같은 구간을 두 번 읽어도 같은 자리에 덮어쓴다.
     * 수집 주기를 10분보다 길게 잡으면 그 사이 구간은 건너뛴다(합계는 표본이 되고, 평균 읽기/쓰기 ms는 그대로 유효하다).
     *
     * @return 쓸 수 없는 DB면 null(호출자가 v$filestat으로 대신 읽는다): PDB 접속은 0행(로컬 21c 실측), 권한 없음·오류, 구간이 오래돼 갱신되지 않는 경우.
     */
    private List<Object[]> readFileMetric(Connection conn, String dbId, LocalDateTime dbNow, Fetched f) {
        long nowMs = System.currentTimeMillis();
        Long off = fileMetricOffUntil.get(dbId);
        if (off != null && off > nowMs) return null;
        List<Object[]> out = new ArrayList<Object[]>();
        Timestamp end = null;
        int rowCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT file_id, physical_reads, physical_writes, average_read_time, average_write_time, end_time FROM v$filemetric")) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rowCount++;
                    Timestamp e = rs.getTimestamp(6);
                    if (e != null && (end == null || e.after(end))) end = e;
                    long r = rs.getLong(2);
                    long w = rs.getLong(3);
                    if (r < 0 || w < 0 || (r == 0 && w == 0)) continue;
                    out.add(new Object[]{"D", rs.getLong(1), r, w, Math.round(rs.getDouble(4) * r), Math.round(rs.getDouble(5) * w)});
                }
            }
        } catch (SQLException e) {
            // 타임아웃 같은 일시 오류일 수 있어 짧게만 쉰다(0행·오래됨처럼 6시간을 쉬면 그동안 무거운 v$filestat이 매 주기 나간다 - 2026-10-12 코드 점검)
            fileMetricOffUntil.put(dbId, nowMs + NAME_FAIL_MS);
            log.debug("perf obj filemetric unavailable for db_id={} (using v$filestat): {}", dbId, e.toString());
            return null;
        }
        if (rowCount == 0 || end == null
                || java.time.Duration.between(end.toLocalDateTime(), dbNow).toMinutes() > FILE_METRIC_STALE_MIN) {
            fileMetricOffUntil.put(dbId, nowMs + FILE_METRIC_OFF_MS);
            log.debug("perf obj filemetric empty or stale for db_id={} (rows={}, end={}) - using v$filestat", dbId, rowCount, end);
            return null;
        }
        f.dataFileKey = PerfStoreService.minuteKey(end.toLocalDateTime());
        return topFiles(out);
    }

    /** v$filemetric을 못 쓰는 DB의 데이터파일 누적값. 이름은 조인하지 않는다(addDataFileNames가 저장할 파일만 따로 붙인다). cur: "D:번호" → 값. */
    private void readDataFileStats(Connection conn, Map<String, long[]> cur) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT file#, phyrds, phywrts, readtim, writetim FROM v$filestat")) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cur.put("D:" + rs.getLong(1), new long[]{rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)});
                }
            }
        }
    }

    /**
     * 저장할 데이터파일 행에 이름(테이블스페이스·파일명)을 붙인다. 이름은 거의 바뀌지 않으므로 DB마다 한 번 읽어 기억하고,
     * 모르는 파일 번호(새로 추가된 파일)가 나왔을 때만 다시 읽는다 - 매 주기 v$datafile을 조인하지 않는다.
     * 이름을 못 읽어도 I/O 값은 저장한다(화면에는 파일 번호로 보인다).
     */
    private void addDataFileNames(Connection conn, String dbId, List<Object[]> rows, Map<String, String[]> fileNames) {
        Map<Long, String[]> cache = dataFileNames.get(dbId);
        boolean unknown = cache == null;
        if (!unknown) {
            for (Object[] r : rows) {
                if (!cache.containsKey((Long) r[1])) { unknown = true; break; }
            }
        }
        long nowMs = System.currentTimeMillis();
        Long retryAt = dataFileNamesRetryAt.get(dbId);
        if (unknown && (retryAt == null || retryAt <= nowMs)) {
            try {
                cache = loadDataFileNames(conn, dbId);
                dataFileNames.put(dbId, cache);
                dataFileNamesRetryAt.put(dbId, nowMs + FILE_NAME_RELOAD_MS);
            } catch (SQLException e) {
                dataFileNamesRetryAt.put(dbId, nowMs + NAME_FAIL_MS);
                log.debug("perf obj datafile names skipped for db_id={}: {}", dbId, e.toString());
            }
        }
        if (cache == null) return;
        for (Object[] r : rows) {
            String[] n = cache.get((Long) r[1]);
            if (n != null) fileNames.put("D:" + r[1], n);
        }
    }

    private Map<Long, String[]> loadDataFileNames(Connection conn, String dbId) throws SQLException {
        Boolean cap = fileConId.get(dbId);
        try {
            return loadDataFileNamesSql(conn, Boolean.FALSE.equals(cap));
        } catch (SQLException e) {
            if (e.getErrorCode() == 904 && !Boolean.FALSE.equals(cap)) {
                fileConId.put(dbId, Boolean.FALSE);
                return loadDataFileNamesSql(conn, true);
            }
            throw e;
        }
    }

    private Map<Long, String[]> loadDataFileNamesSql(Connection conn, boolean noConId) throws SQLException {
        // 루트 접속(12c+ CDB)은 ts#가 컨테이너마다 겹치므로 con_id까지 맞춘다. 11g는 con_id가 없어 뺀다.
        Map<Long, String[]> out = new HashMap<Long, String[]>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT d.file#, d.name, t.name FROM v$datafile d JOIN v$tablespace t ON t.ts# = d.ts#" + (noConId ? "" : " AND t.con_id = d.con_id"))) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getLong(1), new String[]{rs.getString(3), rs.getString(2)});
            }
        }
        return out;
    }

    /** 파일별 델타 행(kind = "D" 또는 "T"만): {kind, file_no, reads, writes, readtim, writetim}. 읽기·쓰기가 모두 0이거나 음수가 있는 파일은 뺀다. */
    private List<Object[]> fileDeltas(Map<String, long[]> cur, Map<String, long[]> old, String kind) {
        List<Object[]> out = new ArrayList<Object[]>();
        for (Map.Entry<String, long[]> e : cur.entrySet()) {
            if (!e.getKey().startsWith(kind)) continue;
            long[] o = old.get(e.getKey());
            if (o == null) continue; // 새로 생긴 파일은 기준값만 기억한다
            long[] c = e.getValue();
            long r = c[0] - o[0];
            long w = c[1] - o[1];
            long rt = c[2] - o[2];
            long wt = c[3] - o[3];
            if (r < 0 || w < 0 || rt < 0 || wt < 0 || (r == 0 && w == 0)) continue;
            out.add(new Object[]{e.getKey().substring(0, 1), Long.parseLong(e.getKey().substring(2)), r, w, rt, wt});
        }
        return topFiles(out);
    }

    /** 저장할 파일 수 상한. */
    private static List<Object[]> topFiles(List<Object[]> out) {
        if (out.size() > FILE_STORE_N) {
            // 파일이 수천 개인 DB에서 저장량을 묶는다(읽기+쓰기가 많은 순)
            Collections.sort(out, new Comparator<Object[]>() {
                @Override
                public int compare(Object[] x, Object[] y) { return Long.compare((Long) y[2] + (Long) y[3], (Long) x[2] + (Long) x[3]); }
            });
            return new ArrayList<Object[]>(out.subList(0, FILE_STORE_N));
        }
        return out;
    }

    /** 최근 24시간(시간 단위로 잘라) 자기 스레드의 시간별 Redo 스위치 수: {thread, hour_key, switches}. 스위치가 없던 시간도 0으로 채워 "수집 안 됨"과 구분한다. */
    private List<Object[]> readRedo(Connection conn, long thread, LocalDateTime dbNow) throws SQLException {
        Map<Long, Long> byHour = new HashMap<Long, Long>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TRUNC(h.first_time, 'HH24'), COUNT(*) FROM v$log_history h " +
                        "WHERE h.first_time >= TRUNC(SYSDATE - 1, 'HH24') AND h.thread# = ? " +
                        "GROUP BY TRUNC(h.first_time, 'HH24')")) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            ps.setLong(1, thread); // 자기 스레드만 - v$log_history는 컨트롤 파일 기반이라 어느 노드에서 읽어도 RAC 전체 스레드가 보인다(합산 중복 방지)
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp t = rs.getTimestamp(1);
                    if (t != null) byHour.put(PerfStoreService.minuteKey(t.toLocalDateTime()), rs.getLong(2));
                }
            }
        }
        long cur = (PerfStoreService.minuteKey(dbNow) / 60) * 60;
        List<Object[]> out = new ArrayList<Object[]>();
        for (int i = REDO_WINDOW_HOURS; i >= 0; i--) {
            long h = cur - 60L * i;
            Long c = byHour.get(h);
            out.add(new Object[]{thread, h, c == null ? 0L : c});
        }
        return out;
    }

    /** Temp를 쓰고 있는 SQL별 사용량(KB): {sql_id, module, kb}. 1MB 미만은 뺀다. */
    private List<Object[]> readTemp(Connection conn, String dbId) throws SQLException {
        long bs = blockSizeOf(conn, dbId);
        List<Object[]> out = new ArrayList<Object[]>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT t.sql_id, MAX(s.module), SUM(t.blocks) FROM v$tempseg_usage t LEFT JOIN v$session s ON s.saddr = t.session_addr " +
                        "WHERE t.sql_id IS NOT NULL GROUP BY t.sql_id")) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long kb = rs.getLong(3) * bs / 1024;
                    if (kb >= TEMP_MIN_KB) out.add(new Object[]{rs.getString(1), rs.getString(2), kb});
                }
            }
        }
        Collections.sort(out, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) { return Long.compare((Long) b[2], (Long) a[2]); }
        });
        return out.size() > TEMP_TOP_N ? new ArrayList<Object[]>(out.subList(0, TEMP_TOP_N)) : out;
    }

    private long blockSizeOf(Connection conn, String dbId) {
        Long c = blockSize.get(dbId);
        if (c != null) return c;
        long bs = 8192;
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM v$parameter WHERE name = 'db_block_size'")) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) bs = Long.parseLong(rs.getString(1).trim());
            }
            blockSize.put(dbId, bs);
        } catch (SQLException | RuntimeException e) {
            log.debug("db_block_size lookup skipped for db_id={}: {}", dbId, e.toString());
        }
        return bs;
    }

    /**
     * object_id → {owner.name[:partition], type}. 모르는 id만 dba_objects에서 묶음으로 읽고(못 찾으면 6시간 재조회 안 함) 캐시한다.
     * 바인드 개수는 10/50/500으로 맞춘다(남는 자리는 -1) - 목록 길이마다 새 커서가 생기는 것을 막는다. dba_objects 접근 권한이 없으면
     * (ORA-00942·01031) 그 DB의 이름 해석을 1시간 쉰다(화면에는 OBJ#번호로 보인다).
     */
    private Map<Long, String[]> resolveNames(Connection conn, String dbId, Set<Long> ids) {
        Map<Long, String[]> cache = names.get(dbId);
        if (cache == null) { cache = new ConcurrentHashMap<Long, String[]>(); names.put(dbId, cache); }
        Map<Long, Long> miss = missingUntil.get(dbId);
        if (miss == null) { miss = new ConcurrentHashMap<Long, Long>(); missingUntil.put(dbId, miss); }
        long nowMs = System.currentTimeMillis();
        Long off = nameOffUntil.get(dbId);
        List<Long> unknown = new ArrayList<Long>();
        if (off == null || off <= nowMs) {
            for (Long id : ids) {
                if (cache.containsKey(id)) continue;
                Long until = miss.get(id);
                if (until != null && until > nowMs) continue;
                unknown.add(id);
            }
        }
        for (int from = 0, chunk = 0; from < unknown.size() && chunk < RESOLVE_MAX_CHUNKS; from += RESOLVE_CHUNK, chunk++) {
            List<Long> part = unknown.subList(from, Math.min(unknown.size(), from + RESOLVE_CHUNK));
            int bucket = IN_BUCKETS[IN_BUCKETS.length - 1];
            for (int b : IN_BUCKETS) { if (part.size() <= b) { bucket = b; break; } }
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < bucket; i++) in.append(i == 0 ? "?" : ", ?");
            boolean failed = false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT object_id, owner, object_name, subobject_name, object_type FROM dba_objects WHERE object_id IN (" + in + ")")) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
                for (int i = 0; i < bucket; i++) ps.setLong(i + 1, i < part.size() ? part.get(i) : -1L);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String sub = rs.getString(4);
                        String nm = rs.getString(2) + "." + rs.getString(3) + (sub == null ? "" : ":" + sub);
                        cache.put(rs.getLong(1), new String[]{nm, rs.getString(5)});
                    }
                }
            } catch (SQLException e) {
                failed = true;
                if (e.getErrorCode() == 942 || e.getErrorCode() == 1031) nameOffUntil.put(dbId, nowMs + NAME_OFF_MS);
                log.debug("perf obj object name lookup skipped for db_id={}: {}", dbId, e.toString());
            }
            long ttl = failed ? NAME_FAIL_MS : NAME_MISS_MS;
            for (Long id : part) if (!cache.containsKey(id)) miss.put(id, nowMs + ttl);
        }
        if (miss.size() > 5000) miss.clear();
        Map<Long, String[]> out = new HashMap<Long, String[]>();
        for (Long id : ids) { String[] n = cache.get(id); if (n != null) out.put(id, n); }
        return out;
    }

    // ------------------------------------------------------------------ 저장 (한 트랜잭션)

    /** 한 트랜잭션으로 저장한다. 이름·파일명 캐시는 **커밋에 성공한 뒤에만** 갱신한다(롤백됐는데 "썼음"으로 남으면 다시 쓰지 않아 이름이 비는 문제). */
    private void store(final String dbId, final Fetched f) {
        if (f.segRows == null && f.dataFileRows == null && f.tempFileRows == null && f.redoRows == null && f.tempRows == null) return;
        final long key = f.key;
        final List<String> newNames = new ArrayList<String>();
        final Map<String, String> newFiles = new HashMap<String, String>();
        tx.execute(status -> {
            if (f.segRows != null) {
                jdbc.update("DELETE FROM perf_obj_seg WHERE db_id = ? AND minute_key = ?", dbId, key);
                List<Object[]> b = new ArrayList<Object[]>();
                for (long[] r : f.segRows) b.add(new Object[]{dbId, key, STATS[(int) r[1]][1], r[0], r[2]});
                if (!b.isEmpty()) jdbc.batchUpdate("INSERT INTO perf_obj_seg (db_id, minute_key, stat, obj_id, val) VALUES (?, ?, ?, ?, ?)", b);
                // 이름은 한 번만 쓴다(같은 이름을 주기마다 다시 쓰지 않는다)
                for (Map.Entry<Long, String[]> e : f.segNames.entrySet()) {
                    String nk = dbId + ":" + e.getKey();
                    if (persistedNames.contains(nk)) continue;
                    jdbc.update("DELETE FROM perf_obj_name WHERE db_id = ? AND obj_id = ?", dbId, e.getKey());
                    jdbc.update("INSERT INTO perf_obj_name (db_id, obj_id, obj_name, obj_type) VALUES (?, ?, ?, ?)", dbId, e.getKey(), e.getValue()[0], e.getValue()[1]);
                    newNames.add(nk);
                }
            }
            // 데이터파일(D)과 Temp 파일(T)은 저장하는 분이 다를 수 있어(v$filemetric은 구간 종료 시각) 종류별로 지우고 쓴다
            List<Object[]> fb = new ArrayList<Object[]>();
            if (f.dataFileRows != null) {
                jdbc.update("DELETE FROM perf_obj_file WHERE db_id = ? AND minute_key = ? AND kind = 'D'", dbId, f.dataFileKey);
                for (Object[] r : f.dataFileRows) fb.add(new Object[]{dbId, f.dataFileKey, r[0], r[1], r[2], r[3], r[4], r[5]});
            }
            if (f.tempFileRows != null) {
                jdbc.update("DELETE FROM perf_obj_file WHERE db_id = ? AND minute_key = ? AND kind = 'T'", dbId, key);
                for (Object[] r : f.tempFileRows) fb.add(new Object[]{dbId, key, r[0], r[1], r[2], r[3], r[4], r[5]});
            }
            if (!fb.isEmpty()) jdbc.batchUpdate("INSERT INTO perf_obj_file (db_id, minute_key, kind, file_no, reads_d, writes_d, readtim_d, writetim_d) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", fb);
            if (!f.fileNames.isEmpty()) {
                // 파일 이름은 바뀐 것만 쓴다(파일이 수천 개여도 주기마다 문장 수천 개를 쓰지 않는다)
                for (Map.Entry<String, String[]> e : f.fileNames.entrySet()) {
                    String kind = e.getKey().substring(0, 1);
                    long no = Long.parseLong(e.getKey().substring(2));
                    String sig = e.getValue()[0] + "|" + e.getValue()[1];
                    String fk = dbId + ":" + e.getKey();
                    if (sig.equals(persistedFiles.get(fk))) continue;
                    jdbc.update("DELETE FROM perf_obj_file_name WHERE db_id = ? AND kind = ? AND file_no = ?", dbId, kind, no);
                    jdbc.update("INSERT INTO perf_obj_file_name (db_id, kind, file_no, ts_name, file_name) VALUES (?, ?, ?, ?, ?)", dbId, kind, no, e.getValue()[0], e.getValue()[1]);
                    newFiles.put(fk, sig);
                }
            }
            if (f.redoRows != null) {
                // 수집할 때마다 최근 24시간(+진행 중인 시간)을 지우고 다시 쓴다 - 같은 시간대를 여러 번 수집해도 행이 중복되지 않는다
                long minHour = Long.MAX_VALUE;
                for (Object[] r : f.redoRows) minHour = Math.min(minHour, (Long) r[1]);
                jdbc.update("DELETE FROM perf_obj_redo WHERE db_id = ? AND thread_no = ? AND hour_key >= ?", dbId, f.thread, minHour);
                List<Object[]> b = new ArrayList<Object[]>();
                for (Object[] r : f.redoRows) b.add(new Object[]{dbId, r[0], r[1], r[2]});
                jdbc.batchUpdate("INSERT INTO perf_obj_redo (db_id, thread_no, hour_key, switches) VALUES (?, ?, ?, ?)", b);
            }
            if (f.tempRows != null) {
                jdbc.update("DELETE FROM perf_obj_temp WHERE db_id = ? AND minute_key = ?", dbId, key);
                List<Object[]> b = new ArrayList<Object[]>();
                for (Object[] r : f.tempRows) b.add(new Object[]{dbId, key, r[0], r[1], r[2]});
                if (!b.isEmpty()) jdbc.batchUpdate("INSERT INTO perf_obj_temp (db_id, minute_key, sql_id, module, temp_kb) VALUES (?, ?, ?, ?, ?)", b);
            }
            return null;
        });
        persistedNames.addAll(newNames);
        persistedFiles.putAll(newFiles);
    }

    // ------------------------------------------------------------------ 보관

    /** 시간 단위로 나눠 지우는 기존 purge 패턴(기본키가 db_id부터라 DB별 범위로). 보관 기간은 perf-retention-days를 따른다. */
    @Scheduled(cron = "0 40 4 * * *")
    void purge() {
        long nowKey = PerfStoreService.minuteKey(LocalDateTime.now(ZoneOffset.UTC));
        final long cutoff = nowKey - 1440L * settings.getInt(SettingKey.PERF_RETENTION_DAYS);
        int total = 0;
        for (String[] t : new String[][]{{"perf_obj_seg", "minute_key"}, {"perf_obj_file", "minute_key"}, {"perf_obj_temp", "minute_key"}, {"perf_obj_redo", "hour_key"}}) {
            List<String> dbIds = jdbc.queryForList("SELECT DISTINCT db_id FROM " + t[0], String.class);
            for (String id : dbIds) {
                List<Long> min = jdbc.queryForList("SELECT MIN(" + t[1] + ") FROM " + t[0] + " WHERE db_id = ?", Long.class, id);
                Long cur = min.isEmpty() ? null : min.get(0);
                while (cur != null && cur < cutoff) {
                    long upto = Math.min(cur + 360, cutoff);
                    total += jdbc.update("DELETE FROM " + t[0] + " WHERE db_id = ? AND " + t[1] + " >= ? AND " + t[1] + " < ?", id, cur, upto);
                    cur = upto;
                }
            }
        }
        if (total > 0) log.info("perf obj purge: {} row(s) older than {} day(s)", total, settings.getInt(SettingKey.PERF_RETENTION_DAYS));
    }

    // ------------------------------------------------------------------ 조회 (화면 API, 수집 저장소만 읽는다)

    public Map<String, Object> collectStatus(String dbId) {
        String[] st = collectState.get(dbId);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("state", st == null ? "unknown" : st[0]);
        m.put("reason", st == null ? "" : st[1]);
        m.put("enabled", enabled(dbId));
        Long first = heartbeats.firstHeartbeat(dbId, "obj");
        m.put("collectedFrom", first == null ? null : LocalDateTime.ofEpochSecond(first * 60L, 0, ZoneOffset.UTC).toString());
        return m;
    }

    private static long key(LocalDateTime t) {
        return PerfStoreService.minuteKey(t);
    }

    private static int statIndex(String sort) {
        for (int i = 0; i < STATS.length; i++) if (STATS[i][1].equals(sort)) return i;
        throw new IllegalArgumentException("sort는 lr, pr, rlw, itl, bbw 중 하나여야 합니다.");
    }

    /** Hot 세그먼트: 구간의 통계별 델타 합계를 오브젝트별로 모아 정렬 기준 통계가 큰 순 상위 50개. */
    public Map<String, Object> segments(String dbId, LocalDateTime from, LocalDateTime to, String sort) {
        final int si = statIndex(sort == null || sort.isEmpty() ? "lr" : sort);
        final Map<String, Integer> codeIdx = new HashMap<String, Integer>();
        for (int i = 0; i < STATS.length; i++) codeIdx.put(STATS[i][1], i);
        final Map<Long, long[]> agg = new HashMap<Long, long[]>();
        jdbc.query("SELECT obj_id, stat, SUM(val) FROM perf_obj_seg WHERE db_id = ? AND minute_key >= ? AND minute_key < ? GROUP BY obj_id, stat",
                rs -> {
                    Integer i = codeIdx.get(rs.getString(2));
                    if (i == null) return;
                    long[] a = agg.get(rs.getLong(1));
                    if (a == null) { a = new long[STATS.length]; agg.put(rs.getLong(1), a); }
                    a[i] = rs.getLong(3);
                }, dbId, key(from), key(to));
        List<Map.Entry<Long, long[]>> list = new ArrayList<Map.Entry<Long, long[]>>();
        for (Map.Entry<Long, long[]> e : agg.entrySet()) if (e.getValue()[si] > 0) list.add(e);
        Collections.sort(list, new Comparator<Map.Entry<Long, long[]>>() {
            @Override
            public int compare(Map.Entry<Long, long[]> a, Map.Entry<Long, long[]> b) { return Long.compare(b.getValue()[si], a.getValue()[si]); }
        });
        if (list.size() > SEG_TOP_N) list = new ArrayList<Map.Entry<Long, long[]>>(list.subList(0, SEG_TOP_N));
        final Map<Long, String[]> nm = new HashMap<Long, String[]>();
        if (!list.isEmpty()) {
            StringBuilder in = new StringBuilder();
            List<Object> b = new ArrayList<Object>();
            b.add(dbId);
            for (Map.Entry<Long, long[]> e : list) { in.append(in.length() == 0 ? "?" : ", ?"); b.add(e.getKey()); }
            jdbc.query("SELECT obj_id, obj_name, obj_type FROM perf_obj_name WHERE db_id = ? AND obj_id IN (" + in + ")",
                    rs -> { nm.put(rs.getLong(1), new String[]{rs.getString(2), rs.getString(3)}); }, b.toArray());
        }
        List<Object> rows = new ArrayList<Object>();
        for (Map.Entry<Long, long[]> e : list) {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            String[] n = nm.get(e.getKey());
            r.put("objId", e.getKey());
            r.put("name", n == null ? "OBJ#" + e.getKey() : n[0]);
            r.put("type", n == null ? null : n[1]);
            for (int i = 0; i < STATS.length; i++) r.put(STATS[i][1], e.getValue()[i]);
            rows.add(r);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("collect", collectStatus(dbId));
        m.put("sort", STATS[si][1]);
        m.put("rows", rows);
        m.put("hasData", !agg.isEmpty());
        m.put("topN", SEG_TOP_N);
        return m;
    }

    /** 데이터파일·Temp 파일 I/O: 구간의 델타 합계와 평균 읽기/쓰기(ms = 시간(1/100초) × 10 / 횟수). 읽기+쓰기가 많은 순. */
    public Map<String, Object> files(String dbId, LocalDateTime from, LocalDateTime to) {
        final Map<String, long[]> agg = new HashMap<String, long[]>();
        jdbc.query("SELECT kind, file_no, SUM(reads_d), SUM(writes_d), SUM(readtim_d), SUM(writetim_d) FROM perf_obj_file " +
                        "WHERE db_id = ? AND minute_key >= ? AND minute_key < ? GROUP BY kind, file_no",
                rs -> { agg.put(rs.getString(1) + ":" + rs.getLong(2), new long[]{rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)}); },
                dbId, key(from), key(to));
        final Map<String, String[]> nm = new HashMap<String, String[]>();
        jdbc.query("SELECT kind, file_no, ts_name, file_name FROM perf_obj_file_name WHERE db_id = ?",
                rs -> { nm.put(rs.getString(1) + ":" + rs.getLong(2), new String[]{rs.getString(3), rs.getString(4)}); }, dbId);
        List<Map.Entry<String, long[]>> list = new ArrayList<Map.Entry<String, long[]>>(agg.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, long[]>>() {
            @Override
            public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                return Long.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]);
            }
        });
        int slowMs = settings.getInt(SettingKey.DIAG_SEQ_READ_MS);
        List<Object> rows = new ArrayList<Object>();
        for (Map.Entry<String, long[]> e : list) {
            if (rows.size() >= FILE_ROWS) break;
            long[] v = e.getValue();
            String[] n = nm.get(e.getKey());
            Double rms = v[0] > 0 ? Math.round(v[2] * 10.0 / v[0] * 100.0) / 100.0 : null;
            Double wms = v[1] > 0 ? Math.round(v[3] * 10.0 / v[1] * 100.0) / 100.0 : null;
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("kind", "T".equals(e.getKey().substring(0, 1)) ? "TEMP" : "DATA");
            r.put("tablespace", n == null ? null : n[0]);
            r.put("file", n == null ? "#" + e.getKey().substring(2) : n[1]);
            r.put("reads", v[0]);
            r.put("readMs", rms);
            r.put("writes", v[1]);
            r.put("writeMs", wms);
            r.put("slow", rms != null && rms >= slowMs);
            rows.add(r);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("collect", collectStatus(dbId));
        m.put("rows", rows);
        m.put("hasData", !agg.isEmpty());
        m.put("slowMs", slowMs);
        return m;
    }

    /** Redo 로그 스위치: 구간이 덮는 시간별 횟수(스레드 합). 수집되지 않은 시간은 null(0과 구분). 기준선 초과가 있으면 힌트. */
    public Map<String, Object> redo(String dbId, LocalDateTime from, LocalDateTime to) {
        long fk = key(from);
        long tk = key(to);
        final long fh = (fk / 60) * 60;
        final long th = (tk % 60 == 0) ? tk : (tk / 60) * 60 + 60;
        final Map<Long, Long> byHour = new HashMap<Long, Long>();
        jdbc.query("SELECT hour_key, SUM(switches) FROM perf_obj_redo WHERE db_id = ? AND hour_key >= ? AND hour_key < ? GROUP BY hour_key",
                rs -> { byHour.put(rs.getLong(1), rs.getLong(2)); }, dbId, fh, th);
        int warn = settings.getInt(SettingKey.REDO_SWITCH_WARN_PER_HOUR);
        List<Object> hours = new ArrayList<Object>();
        List<String> over = new ArrayList<String>();
        long max = 0;
        for (long h = fh; h < th; h += 60) {
            Long c = byHour.get(h);
            Map<String, Object> p = new LinkedHashMap<String, Object>();
            String t = LocalDateTime.ofEpochSecond(h * 60L, 0, ZoneOffset.UTC).toString();
            p.put("time", t);
            p.put("switches", c);
            hours.add(p);
            if (c != null && c > warn) { over.add(t); max = Math.max(max, c); }
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("collect", collectStatus(dbId));
        m.put("hours", hours);
        m.put("warn", warn);
        m.put("overCount", over.size());
        m.put("hasData", !byHour.isEmpty());
        m.put("hint", over.isEmpty() ? null : "시간당 " + warn + "회를 넘는 시간이 " + over.size() + "곳 있습니다(최대 " + max
                + "회). 스위치가 잦으면 log file switch 대기가 늘 수 있으니 redo 로그 그룹 크기를 늘리는 것을 검토하세요.");
        return m;
    }

    /** Temp 사용 Top SQL: 구간에서 SQL별 최대 Temp(MB)와 그때의 모듈·시각. */
    public Map<String, Object> tempTop(String dbId, LocalDateTime from, LocalDateTime to) {
        final Map<String, Object[]> best = new HashMap<String, Object[]>();
        jdbc.query("SELECT sql_id, module, temp_kb, minute_key FROM perf_obj_temp WHERE db_id = ? AND minute_key >= ? AND minute_key < ?",
                rs -> {
                    Object[] o = best.get(rs.getString(1));
                    if (o == null || rs.getLong(3) > (Long) o[1]) best.put(rs.getString(1), new Object[]{rs.getString(2), rs.getLong(3), rs.getLong(4)});
                }, dbId, key(from), key(to));
        List<Map.Entry<String, Object[]>> list = new ArrayList<Map.Entry<String, Object[]>>(best.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, Object[]>>() {
            @Override
            public int compare(Map.Entry<String, Object[]> a, Map.Entry<String, Object[]> b) { return Long.compare((Long) b.getValue()[1], (Long) a.getValue()[1]); }
        });
        List<Object> rows = new ArrayList<Object>();
        for (Map.Entry<String, Object[]> e : list) {
            if (rows.size() >= TEMP_ROWS) break;
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("sqlId", e.getKey());
            r.put("module", e.getValue()[0]);
            r.put("maxMb", Math.round((Long) e.getValue()[1] / 1024.0 * 10.0) / 10.0);
            r.put("time", LocalDateTime.ofEpochSecond((Long) e.getValue()[2] * 60L, 0, ZoneOffset.UTC).toString());
            rows.add(r);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("collect", collectStatus(dbId));
        m.put("rows", rows);
        m.put("hasData", !best.isEmpty());
        return m;
    }

}
