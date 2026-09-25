package com.dbagent.monitor;

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
 *   <li>첫 관측은 기준값만 기억하고 저장하지 않는다 - 재기동 직후·새 SQL의 누적값 전체가 1분에 몰려 급등값이 되지 않게.</li>
 *   <li>델타가 음수면(커서가 aged-out 후 다시 올라와 누적값이 리셋됨) 그 1분은 버리고 기준값만 갱신한다.</li>
 *   <li>델타가 전부 0이면 저장하지 않는다.</li>
 *   <li>조회 창 2분(샘플러 지연 흡수), 10분 동안 안 보인 키는 메모리에서 지운다.</li>
 * </ul>
 */
@Service
public class SqlStatDeltaCollector {

    private static final Logger log = LoggerFactory.getLogger(SqlStatDeltaCollector.class);

    /** 샘플러와 같은 이유로 전용 스레드 - 공용 @Scheduled 스레드를 오래 잡지 않게. */
    private static final ExecutorService COLLECT_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "sqlstat-delta");
        t.setDaemon(true);
        return t;
    });

    private static final long FORGET_AFTER_MS = 10 * 60 * 1000L;

    private final DatabaseConfigService configService;
    private final OracleConnectionPoolManager poolManager;
    private final MonitorStoreService storeService;

    @Value("${dbagent.monitor.sqlstat-query-timeout-seconds:15}")
    private int queryTimeoutSeconds;

    /** db_id → (sql_id|phv → {executions, elapsed, cpu, buffer_gets, disk_reads, rows, lastSeenMs}) */
    private final Map<String, Map<String, long[]>> previous = new ConcurrentHashMap<>();

    public SqlStatDeltaCollector(DatabaseConfigService configService, OracleConnectionPoolManager poolManager,
                                 MonitorStoreService storeService) {
        this.configService = configService;
        this.poolManager = poolManager;
        this.storeService = storeService;
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
        List<Object[]> rows = new ArrayList<>();
        try (Connection conn = poolManager.getConnection(target); Statement st = conn.createStatement()) {
            st.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rs = st.executeQuery(
                    "SELECT sql_id, plan_hash_value, executions, elapsed_time, cpu_time, buffer_gets, disk_reads, " +
                            "rows_processed, SUBSTR(sql_text, 1, 1000) AS sql_text " +
                            "FROM v$sqlstats WHERE last_active_time >= SYSDATE - 2/1440")) {
                while (rs.next()) {
                    String sqlId = rs.getString(1);
                    long phv = rs.getLong(2);
                    long[] cur = {rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), now};
                    String key = sqlId + "|" + phv;
                    long[] old = prev.put(key, cur);
                    if (old == null) continue; // 첫 관측 - 기준값만
                    long[] d = new long[6];
                    boolean negative = false;
                    boolean allZero = true;
                    for (int i = 0; i < 6; i++) {
                        d[i] = cur[i] - old[i];
                        if (d[i] < 0) negative = true;
                        if (d[i] != 0) allZero = false;
                    }
                    if (negative || allZero) continue; // 리셋된 1분은 버림 / 실행 안 된 1분은 저장 안 함
                    rows.add(new Object[]{target.id(), now, sqlId, phv, d[0], d[1], d[2], d[3], d[4], d[5], rs.getString(9)});
                }
            }
        } catch (SQLException e) {
            log.debug("sqlstat delta collection skipped for db_id={}: {}", target.id(), e.toString());
            return 0;
        }
        for (Iterator<Map.Entry<String, long[]>> it = prev.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue()[6] > FORGET_AFTER_MS) it.remove();
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
        return rows.size();
    }
}
