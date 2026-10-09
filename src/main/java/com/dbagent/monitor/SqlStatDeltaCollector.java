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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
        return rows.size();
    }
}
