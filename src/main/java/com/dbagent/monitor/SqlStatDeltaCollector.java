package com.dbagent.monitor;

import com.dbagent.settings.SettingKey;
import com.dbagent.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.OracleConnectionPoolManager;
import com.dbagent.oracle.TargetDbConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v$sqlstats 1분 델타 수집(설계문서 `대시보드 UI 개선 설계.md` 4.3 (4), 전체 작업순서 F3 - 2026-09-25) →
 * mon_sqlstat_delta. SQL 상세 드로어(6.2)의 "구간 실행 횟수·평균 수행 시간·평균 Buffer Gets"에 쓴다.
 *
 * <ul>
 *   <li>누적값이라 (db_id, sql_id, plan_hash_value)별 이전 값(메모리)과의 차이를 저장한다.</li>
 *   <li>첫 관측 규칙(성능 분석 Phase 3, 2026-10-09 변경): 수집기를 처음 돌린 사이클과, 예전에 본 적 있는(6시간 안) 커서가
 *       다시 나타난 경우는 기준값만 기억한다(누적값 전체가 1분에 몰려 급등값이 되지 않게). 반면 기준이 확립된 뒤 한 번도 본 적 없는
 *       커서가 나타나면 누적값 전체를 그 1분의 델타로 인정한다 - 한 번만 실행되고 사라지는 리터럴 SQL을 놓치지 않기 위해서다.
 *       단 누적 실행 수가 max-execs(기본 1000)를 넘는 커서는 오래된 커서일 가능성이 커 기준값만 기억하고, SQL 단위 행은 사이클당
 *       max-rows(기본 100)개까지만 저장한다(저장 행 폭주 방지). FORCE_MATCHING_SIGNATURE별 집계는 상한과 무관하게 센다(리터럴 보기용).</li>
 *   <li>델타가 음수면(커서가 aged-out 후 다시 올라와 누적값이 리셋됨) 그 1분은 버리고 기준값만 갱신한다.</li>
 *   <li>델타가 전부 0이면 저장하지 않는다.</li>
 *   <li>조회 창 2분(샘플러 지연 흡수), 10분 동안 안 보인 키는 메모리에서 지운다.</li>
 *   <li>SQL 원문은 저장하지 않는다 - 읽는 곳이 없는데 1분마다 SQL마다 최대 1000자를 반복 저장해 AIX metrics.mv.db가
 *       4일 만에 15GB(이 테이블만 11GB, 행당 약 535바이트)까지 커졌다(2026-10-02 폐쇄망 실측, 오케스트레이터 결정).
 *       드로어의 SQL 원문은 v$sqlstats.sql_fulltext에서 바로 읽는다(DashboardQueryService.sqlDetail).</li>
 *   <li>SQL 텍스트 미리 저장(2026-10-13, 기본 꺼짐): 위와 달리 SQL_ID당 한 행(perf_sql_text, 앞 1000자)만 둔다 - captureTexts 참고.</li>
 * </ul>
 */
@Service
public class SqlStatDeltaCollector {

    // 환경설정 DB화 P3-2(2026-10-04): 이 클래스가 쓰던 @Value 설정을 SettingsService 조회로 옮겼다(저장 즉시 반영).
    @Autowired
    private SettingsService settings;

    private static final Logger log = LoggerFactory.getLogger(SqlStatDeltaCollector.class);

    /** 샘플러와 같은 이유로 전용 스레드 - 공용 @Scheduled 스레드를 오래 잡지 않게. */
    private static final ExecutorService COLLECT_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "sqlstat-delta");
        t.setDaemon(true);
        return t;
    });

    /**
     * 기준값을 기억하는 시간. (2026-10-09 쿼리 검토) 예전에는 10분이라 12분~6시간 간격으로 도는 배치 SQL이 다시 나타날 때마다 "첫 관측"으로
     * 처리돼 그 실행분이 기록되지 않았다 - 6시간으로 늘려 그 간격의 SQL도 정확한 델타로 센다. 메모리는 PREV_MAX_ENTRIES로 막는다.
     */
    private static final long FORGET_AFTER_MS = 6L * 3600 * 1000;
    /** DB당 기준값 수 상한(리터럴 폭주 방지) - 넘으면 전부 비우고 기준을 다시 잡는다. */
    private static final int PREV_MAX_ENTRIES = 300000;
    /** 텍스트 저장에 한 사이클이 쓰는 전체 시간 상한 - 단건 조회가 느린 DB에서도 수집 주기를 밀지 않게. */
    private static final long TEXT_CAPTURE_DEADLINE_NS = 1000L * 1000 * 1000;
    /** 저장한 텍스트를 다시 읽는 간격(분). perf_sql_text는 저장 시각 기준으로 보관 기간 뒤 지워지므로 계속 도는 SQL은 갱신해 둔다(샘플러와 같은 7일). */
    private static final long TEXT_REFRESH_MINUTES = 7L * 1440;
    /** 대상 DB에서 텍스트를 못 찾은 SQL을 다시 묻지 않는 시간(분) - 화면 조회(PerfSqlService.textsFor)의 10분과 같다. */
    private static final long TEXT_MISS_MINUTES = 10;
    /** 한 사이클에 대상 DB에 묻는 SQL 수 = 저장 상한 × 이 값. SYS 내부 SQL을 읽고도 뒤로 미루는 만큼 더 본다(전체 시간 상한 1초는 그대로). */
    private static final int TEXT_LOOKUP_FACTOR = 5;

    private final DatabaseConfigService configService;
    private final OracleConnectionPoolManager poolManager;
    private final MonitorStoreService storeService;


    /** db_id → (sql_id|phv → {executions, elapsed, cpu, buffer_gets, disk_reads, rows, parse_calls, lastSeenMs}) */
    private final Map<String, Map<String, long[]>> previous = new ConcurrentHashMap<>();
    /** v$sqlstats에 해당 컬럼이 없는 DB(ORA-00904) - 컬럼별로 따로 기억해 있는 컬럼은 계속 쓴다(폐쇄망 11g에서 어느 쪽이 없는지 모른다). */
    private final java.util.Set<String> noParseCalls = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> noFms = ConcurrentHashMap.newKeySet();
    private final PerfSqlService perfSql;
    /**
     * 기준값을 끝까지 읽은 DB(첫 사이클이 성공적으로 끝났음). (2026-10-09 점검 수정) 첫 사이클이 v$sqlstats를 읽다가 중간에
     * 타임아웃이 나면 일부만 기준값에 들어간 채 다음 사이클이 나머지 커서 전부를 "새 커서"로 보고 누적값을 한 분에 몰아 저장했다.
     */
    private final java.util.Set<String> baselineDone = ConcurrentHashMap.newKeySet();
    /** db_id → (sql_id → 텍스트를 저장한 분 키). 기동 후 DB마다 처음 한 번 저장소에서 읽는다. */
    private final Map<String, Map<String, Long>> textKnown = new ConcurrentHashMap<>();
    /** v$sql을 읽을 수 없는 DB(권한·컬럼 없음) - 파싱 계정 구분 없이 v$sqlstats만으로 텍스트를 읽는다. */
    private final java.util.Set<String> noVsql = ConcurrentHashMap.newKeySet();

    @Autowired
    private PerfStoreService perfStore;

    public SqlStatDeltaCollector(DatabaseConfigService configService, OracleConnectionPoolManager poolManager,
                                 MonitorStoreService storeService, PerfSqlService perfSql) {
        this.configService = configService;
        this.poolManager = poolManager;
        this.storeService = storeService;
        this.perfSql = perfSql;
    }

    @Scheduled(fixedDelayString = "${dbagent.monitor.metric-sample-interval-seconds:60}000",
            initialDelayString = "${dbagent.monitor.metric-sample-interval-seconds:60}000")
    void collectAll() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (TargetDbConfig target : configService.listAllInstances()) {
            if ("oracle".equalsIgnoreCase(target.dbType())) {
                futures.add(CompletableFuture.runAsync(() -> collect(target), COLLECT_EXECUTOR));
            }
        }
        for (CompletableFuture<Void> f : futures) {
            try {
                f.join();
            } catch (Exception ignored) {
                // collect()가 스스로 로그를 남긴다
            }
        }
    }

    /** 한 DB 1회 수집. 저장한 행 수를 돌려준다(테스트·로그용). */
    int collect(TargetDbConfig target) {
        long now = System.currentTimeMillis();
        long started = System.nanoTime();
        Map<String, long[]> prev = previous.computeIfAbsent(target.id(), k -> new ConcurrentHashMap<>());
        // 기준이 확립됐는지 - 이 DB를 한 번 이상 돈 뒤여야 "처음 보는 커서"를 새 커서로 본다(재기동 직후 첫 사이클은 모두 기준값)
        final boolean baselineReady = baselineDone.contains(target.id());
        final int newMaxRows = settings.getInt(SettingKey.SQLSTAT_NEW_CURSOR_MAX_ROWS);
        final long newMaxExecs = settings.getInt(SettingKey.SQLSTAT_NEW_CURSOR_MAX_EXECS);
        // 새 커서의 누적 Elapsed 상한 - 실행 수만 막으면 6시간 넘게 쉬던 배치가 돌아올 때 누적 Elapsed 전체가 한 분 델타로 다시 더해진다(쿼리 검토)
        final long newMaxElapsedUs = settings.getInt(SettingKey.SQLSTAT_NEW_CURSOR_MAX_ELAPSED_SEC) * 1_000_000L;
        final boolean hasParse = !noParseCalls.contains(target.id());
        final boolean hasFms = !noFms.contains(target.id());
        List<Object[]> newCursorRows = new ArrayList<>(); // 새 커서 행 후보 - 사이클 끝에 Elapsed 큰 순으로 상한까지만 저장(QA D4)
        Map<String, Object[]> literal = new HashMap<>(); // fms → {새 SQL_ID 수, 실행 수, parse 호출, elapsed_us, 대표 sql_id}
        List<Object[]> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target); Statement st = conn.createStatement()) {
            st.setQueryTimeout(settings.getInt(SettingKey.SQLSTAT_QUERY_TIMEOUT_SECONDS));
            try (ResultSet rs = st.executeQuery(
                    "SELECT sql_id, plan_hash_value, executions, elapsed_time, cpu_time, buffer_gets, disk_reads, " +
                            "rows_processed, " + (hasParse ? "parse_calls" : "0") + ", " + (hasFms ? "TO_CHAR(force_matching_signature) " : "NULL ") +
                            "FROM v$sqlstats WHERE last_active_time >= SYSDATE - 2/1440")) {
                while (rs.next()) {
                    String sqlId = rs.getString(1);
                    long phv = rs.getLong(2);
                    long[] cur = {rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), now};
                    // FORCE_MATCHING_SIGNATURE는 부호 없는 64비트라 long에 안 들어간다 - 문자열로 읽는다(0 = 시그니처 없음).
                    // 드라이버의 NUMBER→문자열 변환 형식(지수 표기 등)에 기대지 않게 Oracle TO_CHAR로 정수 문자열을 받는다.
                    String fms = rs.getString(10);
                    String key = sqlId + "|" + phv;
                    long[] old = prev.put(key, cur);
                    if (old == null) {
                        // 처음 보는 커서(리터럴 SQL 등): 기준이 확립됐고, 누적 실행 수·Elapsed가 상한 이하일 때만 누적값 전체를 델타로 인정
                        if (!baselineReady || newMaxRows <= 0 || cur[0] <= 0 || cur[0] > newMaxExecs || cur[1] > newMaxElapsedUs) continue;
                        if (fms != null && !"0".equals(fms)) {
                            Object[] a = literal.computeIfAbsent(fms, k -> new Object[]{0L, 0L, 0L, 0L, sqlId, new java.util.HashSet<String>()});
                            @SuppressWarnings("unchecked")
                            java.util.Set<String> ids = (java.util.Set<String>) a[5];
                            // 같은 SQL_ID가 Plan만 달라 두 번 보여도 SQL_ID 수는 한 번만 센다(QA D5, 같은 사이클 안에서)
                            if (ids.add(sqlId)) a[0] = (Long) a[0] + 1;
                            a[1] = (Long) a[1] + cur[0];
                            a[2] = (Long) a[2] + cur[6];
                            a[3] = (Long) a[3] + cur[1];
                        }
                        newCursorRows.add(new Object[]{target.id(), now, sqlId, phv, cur[0], cur[1], cur[2], cur[3], cur[4], cur[5], cur[6], fms});
                        continue;
                    }
                    long[] d = new long[7];
                    boolean negative = false;
                    boolean allZero = true;
                    for (int i = 0; i < 7; i++) {
                        d[i] = cur[i] - old[i];
                        if (d[i] < 0) negative = true;
                        if (d[i] != 0) allZero = false;
                    }
                    if (negative || allZero) continue; // 리셋된 1분은 버림 / 실행 안 된 1분은 저장 안 함
                    rows.add(new Object[]{target.id(), now, sqlId, phv, d[0], d[1], d[2], d[3], d[4], d[5], d[6], fms});
                }
            }
        } catch (SQLException e) {
            // ORA-00904: 11g 등 확장 컬럼이 없는 DB - 컬럼을 빼고 예전 쿼리로 다시 한 번 수집한다(없으면 기존 SQL 상세 통계까지 멈춘다)
            if (e.getErrorCode() == 904 && (hasParse || hasFms)) {
                // 오류 문구의 컬럼 이름으로 어느 쪽이 없는지 가린다(알 수 없으면 둘 다 뺀다). FMS가 없으면 리터럴 보기는 비고, parse가 없으면 Parse/Exec 진단이 비게 된다
                String msg = String.valueOf(e.getMessage()).toUpperCase();
                if (hasFms && msg.contains("FORCE_MATCHING_SIGNATURE")) noFms.add(target.id());
                else if (hasParse && msg.contains("PARSE_CALLS")) noParseCalls.add(target.id());
                else { noParseCalls.add(target.id()); noFms.add(target.id()); }
                log.warn("v$sqlstats column missing for db_id={} ({}) - collecting without parse_calls={} / force_matching_signature={}",
                        target.id(), e.getMessage(), noParseCalls.contains(target.id()), noFms.contains(target.id()));
                return collect(target);
            }
            log.debug("sqlstat delta collection skipped for db_id={}: {}", target.id(), e.toString());
            return 0;
        }
        // 새 커서 행: v$sqlstats가 돌려준 순서가 아니라 Elapsed 큰 순으로 상한(max-rows)까지만 저장한다 - 리터럴 폭주가 몰린 분에
        // 같은 분에 처음 나타난 정상 SQL의 첫 분이 빠지지 않게(QA D4). 나머지는 리터럴 집계에는 이미 반영돼 있다.
        if (!newCursorRows.isEmpty()) {
            Collections.sort(newCursorRows, new java.util.Comparator<Object[]>() {
                @Override
                public int compare(Object[] a, Object[] b) { return Long.compare((Long) b[5], (Long) a[5]); }
            });
            rows.addAll(newCursorRows.subList(0, Math.min(newMaxRows, newCursorRows.size())));
        }
        baselineDone.add(target.id()); // 끝까지 읽었다 - 다음 사이클부터 처음 보는 커서를 새 커서로 인정
        for (Iterator<Map.Entry<String, long[]>> it = prev.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue()[7] > FORGET_AFTER_MS) it.remove();
        }
        // 리터럴 폭주로 기준값이 상한을 넘으면 전부 비우고 기준을 다시 잡는다(비운 직후 사이클은 기준값만 기억 - 기존 커서를 새 커서로 오인하지 않게)
        if (prev.size() > PREV_MAX_ENTRIES) {
            log.warn("sqlstat delta baseline for db_id={} exceeded {} cursors - clearing (new-cursor detection restarts)", target.id(), PREV_MAX_ENTRIES);
            prev.clear();
            baselineDone.remove(target.id());
        }
        long tookMs = (System.nanoTime() - started) / 1_000_000L;
        // v$sqlstats 전체 스캔은 구조상 불가피(시간 조건으로는 어느 뷰도 인덱스 접근 불가) - 운영 규모 공유 풀에서
        // 실제로 얼마나 걸리는지 보려고 느릴 때만 남긴다(query-performance-reviewer 권고, 2026-09-25).
        if (tookMs >= 5000) {
            log.warn("sqlstat delta collection for db_id={} took {} ms ({} delta row(s))", target.id(), tookMs, rows.size());
        }
        try {
            storeService.saveSqlstatDeltas(rows);
        } catch (Exception e) {
            log.warn("mon_sqlstat_delta save failed for db_id={}: {}", target.id(), e.toString());
            return 0;
        }
        if (!literal.isEmpty()) {
            try {
                perfSql.saveLiteral(target.id(), now, literal);
            } catch (Exception e) {
                log.warn("perf_sql_literal save failed for db_id={}: {}", target.id(), e.toString());
            }
        }
        captureTexts(target, rows);
        return rows.size();
    }

    /**
     * 이번 사이클에 통계가 잡힌 SQL 중 텍스트가 아직 없는 것을 Elapsed 큰 순으로 상한까지 읽어 perf_sql_text에 저장한다
     * (2026-10-13 오케스트레이터 결정). 텍스트는 화면을 열 때 대상 DB에서 읽는데, 그 전에 공유 풀에서 밀려난 SQL은 SQL 분석 목록·범례에
     * SQL_ID만 남는다(로컬 XE 실측: 09:05에 한 번 실행된 SQL이 몇 분 뒤 v$sqlstats에서 사라짐). ASH에 잡힌 SQL은 샘플러가 이미 저장하므로
     * 여기서는 ASH에 안 잡히는 짧은 SQL을 보충한다.
     *
     * <p>운영 DB 부하: sql_id = ? 단건 조회라 v$sqlstats 고정 인덱스(X$KKSSQLSTAT)를 탄다(로컬 XE 실측: 200건 10ms 미만). 한 번 저장한
     * SQL은 다시 묻지 않고, 사이클당 건수(설정)와 전체 시간(1초)에 상한을 둔다. 기본값 0 = 꺼짐.
     *
     * <p>SYS 내부 SQL 뒤로 미루기(2026-10-13 QA): Oracle 재귀 SQL이 몇 분마다 100개 넘게 몰려 상한을 다 쓰면, Elapsed가 작은 업무 SQL은
     * 순위에서 밀린 채 2~4분 만에 공유 풀에서 사라져 텍스트를 끝내 못 얻었다(로컬 XE 실측: 테스트 SQL 3개 중 2개). v$sqlstats에는 파싱 계정이
     * 없어, 텍스트를 읽을 때 v$sql(sql_id 고정 인덱스 X$KGLCURSOR_CHILD, 로컬 XE 실측: 1000건 30ms)에서 parsing_schema_id를 함께 읽고
     * SYS(0)가 파싱한 SQL은 일반 SQL을 저장하고 남는 자리에만 저장한다. v$sql에 없으면 v$sqlstats에서 텍스트만 읽는다.
     */
    private void captureTexts(TargetDbConfig target, List<Object[]> rows) {
        final int max = settings.getInt(SettingKey.SQLSTAT_TEXT_CAPTURE_MAX_PER_CYCLE);
        if (max <= 0 || rows.isEmpty()) return;
        final String dbId = target.id();
        Map<String, Long> known = textKnown.get(dbId);
        if (known == null) {
            known = new ConcurrentHashMap<String, Long>();
            try {
                known.putAll(perfStore.knownSqlIds(dbId));
            } catch (Exception e) {
                log.debug("known sql text load failed for db_id={}: {}", dbId, e.toString());
            }
            textKnown.put(dbId, known);
        }
        // 화면 조회(PerfSqlService.textsFor)가 저장할 때와 같은 분 키 - 보관 기간 정리가 이 값으로 지운다
        final long nowKey = PerfStoreService.minuteKey(LocalDateTime.now(ZoneOffset.UTC));
        List<Object[]> sorted = new ArrayList<Object[]>(rows);
        Collections.sort(sorted, new java.util.Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) { return Long.compare((Long) b[5], (Long) a[5]); }
        });
        // 조회 후보: 저장 상한의 TEXT_LOOKUP_FACTOR배까지. SYS 내부 SQL은 읽고도 뒤로 미루므로 저장 상한보다 넉넉히 본다.
        List<String> want = new ArrayList<String>();
        java.util.Set<String> seenIds = new java.util.HashSet<String>();
        for (Object[] r : sorted) {
            String id = (String) r[2];
            if (!seenIds.add(id)) continue; // 같은 SQL_ID가 Plan만 달라 여러 행일 수 있다
            Long saved = known.get(id);
            if (saved != null && nowKey - saved < TEXT_REFRESH_MINUTES) continue;
            want.add(id);
            if (want.size() >= max * TEXT_LOOKUP_FACTOR) break;
        }
        if (want.isEmpty()) return;
        final long deadline = System.nanoTime() + TEXT_CAPTURE_DEADLINE_NS;
        boolean useVsql = !noVsql.contains(dbId);
        int savedCount = 0;
        List<String[]> sysDeferred = new ArrayList<String[]>(); // SYS가 파싱한 SQL {sql_id, text} - 일반 SQL을 먼저 저장한 뒤 남는 자리에만
        try (Connection conn = poolManager.getConnection(target);
             PreparedStatement psSql = conn.prepareStatement("SELECT parsing_schema_id, SUBSTR(sql_text, 1, 1000) FROM v$sql WHERE sql_id = ? AND ROWNUM = 1");
             PreparedStatement psStats = conn.prepareStatement("SELECT SUBSTR(sql_text, 1, 1000) FROM v$sqlstats WHERE sql_id = ? AND ROWNUM = 1")) {
            int timeout = settings.getInt(SettingKey.LOCK_QUERY_TIMEOUT_SECONDS);
            psSql.setQueryTimeout(timeout);
            psStats.setQueryTimeout(timeout);
            for (String id : want) {
                if (savedCount >= max) break;
                if (System.nanoTime() > deadline) break; // 남은 것은 다음에 다시 보일 때 읽는다
                String text = null;
                boolean sys = false;
                if (useVsql) {
                    try {
                        psSql.setString(1, id);
                        try (ResultSet rs = psSql.executeQuery()) {
                            if (rs.next()) {
                                sys = rs.getLong(1) == 0L; // parsing_schema_id 0 = SYS
                                text = rs.getString(2);
                            }
                        }
                    } catch (SQLException e) {
                        // v$sql을 볼 권한이 없거나(ORA-00942) 컬럼이 없는(ORA-00904) DB는 계정 구분 없이 v$sqlstats만 쓴다
                        if (e.getErrorCode() != 942 && e.getErrorCode() != 904) throw e;
                        noVsql.add(dbId);
                        useVsql = false;
                        log.warn("v$sql not readable for db_id={} ({}) - sql text capture continues without the SYS filter", dbId, e.getMessage());
                    }
                }
                if (text == null) {
                    // 커서는 라이브러리 캐시에서 밀려났어도 v$sqlstats에는 통계와 텍스트가 더 오래 남는다(로컬 XE 실측: 최근 SQL 1000건 중 v$sql 835건)
                    psStats.setString(1, id);
                    try (ResultSet rs = psStats.executeQuery()) {
                        if (rs.next()) text = rs.getString(1);
                    }
                }
                if (text == null) {
                    // 못 찾은 것은 10분만 쉬고 다시 묻는다(2026-10-13 코드 점검·QA): 방금 통계를 읽은 커서가 없다면 그사이 밀려난 것이지만,
                    // 다시 올라온 SQL까지 7일 동안 빼면 텍스트를 끝내 못 얻는다. 저장한 시각 대신 "10분 뒤에 갱신 주기가 끝나는 시각"을 넣는다.
                    known.put(id, nowKey - TEXT_REFRESH_MINUTES + TEXT_MISS_MINUTES);
                    continue;
                }
                if (sys) {
                    sysDeferred.add(new String[]{id, text});
                    continue;
                }
                if (saveText(dbId, id, text, nowKey, known)) savedCount++;
            }
        } catch (SQLException e) {
            log.debug("sql text capture skipped for db_id={}: {}", dbId, e.toString());
        }
        // SYS 내부 SQL: 남는 자리에만 저장하고, 못 넣은 것은 10분 쉬었다가 다시 본다(매 사이클 같은 SQL을 다시 읽지 않게)
        for (String[] t : sysDeferred) {
            if (savedCount < max) {
                if (saveText(dbId, t[0], t[1], nowKey, known)) savedCount++;
            } else {
                known.put(t[0], nowKey - TEXT_REFRESH_MINUTES + TEXT_MISS_MINUTES);
            }
        }
        // 리터럴 폭주로 기억한 SQL_ID가 너무 많아지면 내용만 비운다. (2026-10-13 코드 점검) 맵 자체를 지워 저장소에서 다시 읽게 하면,
        // 저장소(perf_sql_text)에 이미 상한보다 많은 행이 있을 때 매 사이클 전부 읽고 다시 지우기를 반복한다. 비운 뒤에는 이미 저장한
        // SQL도 다시 묻게 되지만 사이클당 상한(설정)까지만이다.
        if (known.size() > PREV_MAX_ENTRIES) known.clear();
    }

    /** 텍스트 한 건 저장. 성공한 것만 기억한다(2026-10-13 코드 점검) - 저장이 실패했는데(저장소 잠금 등) 기억하면 텍스트가 없는 채로 7일 동안 다시 읽지 않는다. */
    private boolean saveText(String dbId, String sqlId, String text, long nowKey, Map<String, Long> known) {
        try {
            perfStore.saveSqlText(dbId, sqlId, text, nowKey);
            known.put(sqlId, nowKey);
            return true;
        } catch (Exception e) {
            log.debug("sql text save failed for db_id={}: {}", dbId, e.toString());
            return false;
        }
    }
}
