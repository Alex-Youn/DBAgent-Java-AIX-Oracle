package com.dbagent.monitor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 참고: ASH·AWR 경계는 [from, ashFrom)=AWR, [ashFrom, to)=ASH로 겹치지 않는다. 다만 ASH 버퍼가 밀려나는 속도가
 * AWR flush(스냅샷)보다 빠른 바쁜 인스턴스에서는 경계 직전 몇 초~분이 어느 쪽에도 없을 수 있다(Oracle 구조상 한계).
 *
 * 공용 ASH 구간 조회(2026-09-25, 전체 작업순서 D1·D2) - 시작/종료 시각(DB 시각)을 받아 그 구간의 ASH 행을
 * 돌려주는 인라인 뷰를 만든다. Current Session 차트(getAshActivity), 성능 분석(G3), 대시보드 Top·드로어(F3)가
 * 같은 필터·같은 AWR 보충 규칙을 쓰도록 한 곳에 모은다.
 *
 * <ul>
 *   <li>필터: FOREGROUND, 모니터링 계정(= 이 커넥션의 접속 계정) 제외 - dba_users JOIN 없이 SESSION_USERID 비교
 *       (query-performance-reviewer 검토, C단계).</li>
 *   <li>D2 AWR 보충: 구간 시작이 ASH 인메모리 보관 범위(v$active_session_history의 MIN(sample_time))보다
 *       이르면 그 앞부분만 dba_hist_active_sess_history(10초 간격)에서 읽는다. 뷰의 {@code w} 컬럼이 샘플
 *       한 건이 대표하는 초(ASH 1, AWR 10)라 AAS = SUM(w) / 구간 초.</li>
 *   <li>RAC여도 접속 인스턴스 기준(v$, AWR은 dbid·instance_number를 접속 인스턴스로 고정) - gv$·inst_id 미사용.</li>
 * </ul>
 */
final class AshRange {

    private AshRange() {
    }

    /** 한 번에 조회할 수 있는 최대 구간 - 설계 4.3 (3). */
    static final int MAX_RANGE_MINUTES = 24 * 60;

    /** 모니터링 계정 세션 제외(별칭 h). user_id가 NULL인 행은 예전 dba_users LEFT JOIN 방식처럼 남긴다. */
    static final String EXCLUDE_SELF_SQL =
            "AND (h.user_id IS NULL OR h.user_id <> TO_NUMBER(SYS_CONTEXT('USERENV', 'SESSION_USERID'))) ";

    /** 조회 구간과 소스 판단 결과. 시각은 모두 DB 시각(SYSDATE 기준 벽시계). */
    static final class Window {
        final LocalDateTime from;
        final LocalDateTime to;
        final LocalDateTime dbNow;
        /** ASH에서 읽는 구간의 시작 - from보다 늦으면 [from, ashFrom)은 AWR에서 읽는다. */
        final LocalDateTime ashFrom;
        final boolean useAwr;
        final boolean useAsh;

        Window(LocalDateTime from, LocalDateTime to, LocalDateTime dbNow, LocalDateTime ashFrom, boolean useAwr, boolean useAsh) {
            this.from = from;
            this.to = to;
            this.dbNow = dbNow;
            this.ashFrom = ashFrom;
            this.useAwr = useAwr;
            this.useAsh = useAsh;
        }

        /** 응답의 source 값 - "ash" | "awr" | "mixed". */
        String source() {
            if (useAwr && useAsh) return "mixed";
            return useAwr ? "awr" : "ash";
        }

        long seconds() {
            return Math.max(1L, java.time.Duration.between(from, to).getSeconds());
        }
    }

    /** DB의 현재 시각(SYSDATE). */
    static LocalDateTime dbNow(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT SYSDATE FROM dual")) {
            rs.next();
            return rs.getTimestamp(1).toLocalDateTime();
        }
    }

    /**
     * 구간을 확정한다. to가 null이면 DB 현재 시각, 구간은 최대 MAX_RANGE_MINUTES로 잘라 뒤쪽(최근)을 남긴다.
     * awrFallback이 false면 ASH 보관 범위 밖은 비어 있는 채로 둔다(설정 dbagent.monitor.ash-awr-fallback).
     */
    static Window resolve(Connection conn, LocalDateTime from, LocalDateTime to, boolean awrFallback) throws SQLException {
        LocalDateTime now = dbNow(conn);
        LocalDateTime end = (to == null || to.isAfter(now)) ? now : to;
        LocalDateTime start = from == null ? end.minusHours(1) : from;
        if (start.isBefore(end.minusMinutes(MAX_RANGE_MINUTES))) {
            start = end.minusMinutes(MAX_RANGE_MINUTES);
        }
        if (!start.isBefore(end)) {
            start = end.minusMinutes(1);
        }
        LocalDateTime ashMin = null;
        if (awrFallback) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT MIN(sample_time) FROM v$active_session_history")) {
                if (rs.next() && rs.getTimestamp(1) != null) {
                    ashMin = rs.getTimestamp(1).toLocalDateTime();
                }
            }
        }
        if (ashMin == null || !start.isBefore(ashMin)) {
            return new Window(start, end, now, start, false, true);
        }
        if (!ashMin.isBefore(end)) {
            // 구간 전체가 ASH 보관 범위보다 과거
            return new Window(start, end, now, end, true, false);
        }
        return new Window(start, end, now, ashMin, true, true);
    }

    /**
     * 구간의 ASH 행 인라인 뷰. {@code columns}는 별칭 h 기준 SELECT 목록(쉼표로 끝나지 않게), 결과에는 그 컬럼들 +
     * {@code w}(샘플 1건이 대표하는 초)가 있다. {@code extraWhere}는 "AND ..." 형태의 추가 조건(없으면 빈 문자열).
     * 바인드 순서는 {@link #bind}가 맞춘다.
     */
    static String baseSql(Window w, String columns, String extraWhere) {
        StringBuilder sb = new StringBuilder();
        if (w.useAsh) {
            sb.append("SELECT ").append(columns).append(", 1 AS w ")
                    .append("FROM v$active_session_history h ")
                    .append("WHERE h.sample_time >= ? AND h.sample_time < ? ")
                    .append("AND h.session_type = 'FOREGROUND' ")
                    .append(EXCLUDE_SELF_SQL)
                    .append(extraWhere);
        }
        if (w.useAwr) {
            if (sb.length() > 0) sb.append(" UNION ALL ");
            // snap_id 하한·상한: dba_hist_active_sess_history는 (dbid, snap_id) 파티션이라 sample_time만 주면
            // 보관 기간 전체를 훑는다 - 구간을 덮는 스냅샷 범위로 잘라 준다. 상한은 구간 끝을 덮는 첫 스냅샷,
            // 아직 스냅샷이 없으면 제한 없음(query-performance-reviewer 검토, 2026-09-25 - EXPLAIN으로 하한만
            // 있으면 최신 파티션까지 이터레이트하는 것 확인).
            sb.append("SELECT ").append(columns).append(", 10 AS w ")
                    .append("FROM dba_hist_active_sess_history h ")
                    .append("WHERE h.dbid = (SELECT dbid FROM v$database) ")
                    .append("AND h.instance_number = (SELECT instance_number FROM v$instance) ")
                    .append("AND h.snap_id >= (SELECT NVL(MIN(s.snap_id), 0) FROM dba_hist_snapshot s ")
                    .append("WHERE s.dbid = (SELECT dbid FROM v$database) ")
                    .append("AND s.instance_number = (SELECT instance_number FROM v$instance) ")
                    .append("AND s.end_interval_time >= ?) ")
                    .append("AND h.snap_id <= (SELECT NVL(MIN(s.snap_id), 2147483647) FROM dba_hist_snapshot s ")
                    .append("WHERE s.dbid = (SELECT dbid FROM v$database) ")
                    .append("AND s.instance_number = (SELECT instance_number FROM v$instance) ")
                    .append("AND s.end_interval_time >= ?) ")
                    .append("AND h.sample_time >= ? AND h.sample_time < ? ")
                    .append("AND h.session_type = 'FOREGROUND' ")
                    .append(EXCLUDE_SELF_SQL)
                    .append(extraWhere);
        }
        return sb.toString();
    }

    /**
     * baseSql의 바인드 변수를 채운다. extraWhere에 바인드가 있으면 {@code extraBinder}가 각 소스 블록마다
     * 한 번씩 이어서 채운다. 다음 바인드 위치를 돌려준다.
     */
    static int bind(PreparedStatement ps, int idx, Window w, ExtraBinder extraBinder) throws SQLException {
        if (w.useAsh) {
            ps.setTimestamp(idx++, Timestamp.valueOf(w.ashFrom));
            ps.setTimestamp(idx++, Timestamp.valueOf(w.to));
            if (extraBinder != null) idx = extraBinder.bind(ps, idx);
        }
        if (w.useAwr) {
            Timestamp awrTo = Timestamp.valueOf(w.useAsh ? w.ashFrom : w.to);
            ps.setTimestamp(idx++, Timestamp.valueOf(w.from)); // snap_id 하한
            ps.setTimestamp(idx++, awrTo);                     // snap_id 상한
            ps.setTimestamp(idx++, Timestamp.valueOf(w.from));
            ps.setTimestamp(idx++, awrTo);
            if (extraBinder != null) idx = extraBinder.bind(ps, idx);
        }
        return idx;
    }

    /** extraWhere의 바인드 채우기 - 다음 바인드 위치를 돌려준다. */
    interface ExtraBinder {
        int bind(PreparedStatement ps, int idx) throws SQLException;
    }

    /**
     * G3 성능 분석(2026-09-26)의 계정·접속 호스트 필터 - 예전 성능 이력 조회의 users/machines 드롭다운(2026-09-14
     * 요청)을 그대로 잇는다. 예전 코드는 값을 문자열로 이어 붙였지만 여기서는 바인드 변수로 넘긴다.
     * 쉼표로 구분한 값, 빈 값은 "전체". 각각 최대 MAX_FILTER_VALUES개.
     */
    static final class Filter {
        static final int MAX_FILTER_VALUES = 50;
        final java.util.List<String> users;
        final java.util.List<String> machines;

        private Filter(java.util.List<String> users, java.util.List<String> machines) {
            this.users = users;
            this.machines = machines;
        }

        static Filter of(String usersCsv, String machinesCsv) {
            return new Filter(split(usersCsv), split(machinesCsv));
        }

        private static java.util.List<String> split(String csv) {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (csv == null) return out;
            for (String v : csv.split(",")) {
                String t = v.trim();
                if (!t.isEmpty() && !out.contains(t) && out.size() < MAX_FILTER_VALUES) out.add(t);
            }
            return out;
        }

        boolean isEmpty() {
            return users.isEmpty() && machines.isEmpty();
        }

        /** "AND ..." 조건(별칭 h). 없으면 빈 문자열. */
        String sql() {
            StringBuilder sb = new StringBuilder();
            if (!users.isEmpty()) {
                sb.append("AND h.user_id IN (SELECT u.user_id FROM dba_users u WHERE u.username IN (")
                        .append(placeholders(users.size())).append(")) ");
            }
            if (!machines.isEmpty()) {
                sb.append("AND h.machine IN (").append(placeholders(machines.size())).append(") ");
            }
            return sb.toString();
        }

        int bind(PreparedStatement ps, int idx) throws SQLException {
            for (String u : users) ps.setString(idx++, u);
            for (String m : machines) ps.setString(idx++, m);
            return idx;
        }

        private static String placeholders(int n) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ", ?");
            return sb.toString();
        }
    }

    /** 구간 시작을 step 분 단위로 내린 버킷 경계(DB 시각). */
    static LocalDateTime floorToStep(LocalDateTime t, int stepMinutes) {
        int minuteOfDay = t.getHour() * 60 + t.getMinute();
        return t.toLocalDate().atStartOfDay().plusMinutes((long) (minuteOfDay / stepMinutes) * stepMinutes);
    }
}
