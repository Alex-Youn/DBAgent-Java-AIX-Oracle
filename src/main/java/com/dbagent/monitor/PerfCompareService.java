package com.dbagent.monitor;

import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.settings.SettingKey;
import com.dbagent.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 성능 분석 "구간 비교" (Phase 4, 2026-10-11, 설계문서 `성능분석 메뉴 설계.md` Phase 4).
 * 신규 수집 없이 Phase 1~3의 저장소 데이터만 읽는다. 지표·대기·SQL·락은 각 하위 탭이 쓰는 같은 서비스 메서드를 A/B 구간에 각각 불러
 * 조합하므로 "시스템 부하 탭에서 구간을 따로 조회한 값과 같다"(설계 9.5)가 구조적으로 보장된다.
 * 한 섹션이 실패해도 나머지는 보이도록 섹션별로 실패를 격리해 errors에 담는다(조회 실패를 0으로 내리지 않는다).
 * 구간은 각각 24시간 이하이며(서비스 안에서도 다시 검증), 아직 수집되지 않은 시각(PerfStoreService.collectedBefore 이후)은 잘라낸다.
 * 시각은 전부 DB 시각이다.
 */
@Service
public class PerfCompareService {

    private static final Logger log = LoggerFactory.getLogger(PerfCompareService.class);

    /** 이 길이(분) 이상 연속으로 비면 "수집 공백"으로 보여 준다. */
    private static final int GAP_MIN_MINUTES = 2;
    private static final int GAP_SHOW = 3;
    /** 수집 커버율이 이 값(%) 미만이면 비교 결과에 경고를 붙인다. */
    private static final double COVERAGE_WARN_PCT = 80.0;
    /** 요약 카드가 대기 이벤트를 "변했다"고 보여 주는 최소 배율(이보다 작으면 변화 없음/신규로 대체). */
    private static final double WAIT_CARD_MIN_RATIO = 1.5;
    /** 요약 카드의 대기 이벤트는 분당 대기 시간이 큰 상위 몇 개 안에서만 고른다(아주 작은 이벤트의 큰 배율을 뽑지 않게). */
    private static final int WAIT_CARD_TOP = 10;
    /** A·B 길이가 이 비율 이내로 비슷하면 락 건수를 그대로, 아니면 시간당 건수로 견준다. */
    private static final double SAME_LENGTH_RATIO = 0.2;

    @Autowired
    private SettingsService settings;

    private final PerfSysService sys;
    private final PerfSqlService sql;
    private final PerfLockService lock;
    private final PerfStoreService store;

    public PerfCompareService(PerfSysService sys, PerfSqlService sql, PerfLockService lock, PerfStoreService store) {
        this.sys = sys;
        this.sql = sql;
        this.lock = lock;
        this.store = store;
    }

    private static double round(double v, int digits) {
        double p = Math.pow(10, digits);
        return Math.round(v * p) / p;
    }

    private static String iso(long minuteKey) {
        return LocalDateTime.ofEpochSecond(minuteKey * 60L, 0, ZoneOffset.UTC).toString();
    }

    private static long minutes(LocalDateTime f, LocalDateTime t) {
        return Duration.between(f, t).toMinutes();
    }

    private static Map<String, Object> range(LocalDateTime f, LocalDateTime t, LocalDateTime requestedTo) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("from", f.toString());
        m.put("to", t.toString());
        m.put("minutes", minutes(f, t));
        m.put("clamped", !t.equals(requestedTo));
        return m;
    }

    /** 종료 시각이 아직 수집되지 않은 시각이면 수집 완료 시각까지로 자른다(구간 전체가 미수집이면 그대로 두고 커버율이 pending으로 알린다). */
    private static LocalDateTime clampTo(Long collectedBefore, LocalDateTime from, LocalDateTime to) {
        if (collectedBefore == null) return to;
        LocalDateTime limit = PerfStoreService.fromMinuteKey(collectedBefore);
        return to.isAfter(limit) && limit.isAfter(from) ? limit : to;
    }

    // ------------------------------------------------------------------ 숫자 표기 (지수 표기·서버 로캘의 쉼표 소수점을 피한다)

    private static String plain(double v, int scale) {
        return new BigDecimal(v).setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(double c) {
        return (c > 0 ? "+" : "") + plain(c, 1) + "%";
    }

    private static String num(Object v) {
        if (v == null) return "-";
        double d = ((Number) v).doubleValue();
        double a = Math.abs(d);
        if (d == 0) return "0";
        if (a >= 1000) return String.format(Locale.US, "%,d", Math.round(d));
        if (a >= 100) return plain(d, 1);
        if (a >= 1) return plain(d, 2);
        // 0.00243 같은 작은 값은 유효숫자 3자리(소수 자리를 고정하면 낮은 부하에서 오차가 크다 - 시스템 부하 탭과 같은 규칙)
        return new BigDecimal(d).round(new MathContext(3)).stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ 수집 커버율

    /**
     * kind(ash=세션·Lock 수집, sys=시스템 부하)의 하트비트가 구간 안에 있는 분의 비율. 수집이 시작되기 전 분은 분모에서 뺀다
     * ("수집 공백"과 "수집 전"을 구분, 설계 3.1의 5). 아직 수집되지 않은 분(collectedBefore 이후)도 공백이 아니라 분모에서 뺀다.
     * state: ok / before(구간 전체가 수집 시작 이전) / pending(구간 전체가 아직 수집 전) / none(수집 이력 없음).
     * first는 호출자가 한 번만 읽어 넘긴다(같은 MIN 조회를 A·B에서 되풀이하지 않게).
     */
    Map<String, Object> coverage(String dbId, String kind, Long first, Long collectedBefore, LocalDateTime from, LocalDateTime to) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        long fk = PerfStoreService.minuteKey(from);
        long tk = PerfStoreService.minuteKey(to);
        if (collectedBefore != null) tk = Math.min(tk, collectedBefore);
        m.put("kind", kind);
        m.put("pct", null);
        m.put("gaps", new ArrayList<Object>());
        if (first == null) {
            m.put("state", "none");
            return m;
        }
        m.put("collectedFrom", iso(first));
        if (collectedBefore != null && fk >= collectedBefore) {
            m.put("state", "pending");
            return m;
        }
        tk = Math.max(fk + 1, tk);
        long start = Math.max(fk, first);
        if (start >= tk) {
            m.put("state", "before");
            return m;
        }
        Set<Long> have = lock.heartbeatMinutes(dbId, kind, start, tk);
        long total = tk - start;
        m.put("state", "ok");
        m.put("pct", round(have.size() * 100.0 / total, 1));
        m.put("minutes", total);
        m.put("missing", total - have.size());
        // 연속으로 빈 구간(공백)을 긴 순으로 몇 개만
        final List<long[]> gaps = new ArrayList<long[]>();
        long runStart = -1;
        for (long k = start; k <= tk; k++) {
            boolean missing = k < tk && !have.contains(k);
            if (missing && runStart < 0) runStart = k;
            if (!missing && runStart >= 0) {
                if (k - runStart >= GAP_MIN_MINUTES) gaps.add(new long[]{runStart, k});
                runStart = -1;
            }
        }
        Collections.sort(gaps, new Comparator<long[]>() {
            @Override
            public int compare(long[] x, long[] y) { return Long.compare(y[1] - y[0], x[1] - x[0]); }
        });
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < gaps.size() && i < GAP_SHOW; i++) {
            Map<String, Object> g = new LinkedHashMap<String, Object>();
            g.put("from", iso(gaps.get(i)[0]));
            g.put("to", iso(gaps.get(i)[1]));
            g.put("minutes", gaps.get(i)[1] - gaps.get(i)[0]);
            out.add(g);
        }
        m.put("gaps", out);
        m.put("gapCount", gaps.size());
        return m;
    }

    private static boolean covOk(Map<String, Object> cov, String side, String kind) {
        if (cov == null || cov.isEmpty()) return false;
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) ((Map<String, Object>) cov.get(side)).get(kind);
        return c != null && "ok".equals(c.get("state"));
    }

    // ------------------------------------------------------------------ 섹션

    @SuppressWarnings("unchecked")
    private Map<String, Object> metrics(String dbId, LocalDateTime aFrom, LocalDateTime aTo, LocalDateTime bFrom, LocalDateTime bTo) {
        Map<String, Object> a = sys.loadMetrics(dbId, aFrom, aTo, false);
        Map<String, Object> b = sys.loadMetrics(dbId, bFrom, bTo, false);
        List<Object> ca = (List<Object>) a.get("cards");
        List<Object> cb = (List<Object>) b.get("cards");
        int cardPct = settings.getInt(SettingKey.DIAG_CARD_PCT);
        List<Object> rows = new ArrayList<Object>();
        for (int i = 0; i < ca.size() && i < cb.size(); i++) {
            Map<String, Object> x = (Map<String, Object>) ca.get(i);
            Map<String, Object> y = (Map<String, Object>) cb.get(i);
            Double av = (Double) x.get("avg");
            Double bv = (Double) y.get("avg");
            Double chg = av != null && bv != null && av > 0 ? round((bv - av) / av * 100.0, 1) : null;
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("key", x.get("key"));
            r.put("label", x.get("label"));
            r.put("unit", x.get("unit"));
            r.put("a", av);
            r.put("b", bv);
            r.put("changePct", chg);
            r.put("level", chg == null ? null : chg >= cardPct ? "up" : chg <= -cardPct ? "down" : null);
            rows.add(r);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("rows", rows);
        m.put("hasA", Boolean.TRUE.equals(a.get("hasData")));
        m.put("hasB", Boolean.TRUE.equals(b.get("hasData")));
        m.put("collect", b.get("collect"));
        return m;
    }

    // ------------------------------------------------------------------ 요약 카드 4개(변화가 큰 항목을 자동으로 뽑는다)

    private static Map<String, Object> card(String key, String label, String value, String detail, String level) {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("key", key);
        c.put("label", label);
        c.put("value", value);
        c.put("detail", detail);
        c.put("level", level);
        return c;
    }

    @SuppressWarnings("unchecked")
    private List<Object> summary(Map<String, Object> metrics, Map<String, Object> waits, Map<String, Object> sqlRes,
                                 Map<String, Object> la, Map<String, Object> lb, Map<String, Object> cov, long minA, long minB) {
        List<Object> out = new ArrayList<Object>();
        // 1) 지표: 기준(diag-card-pct) 이상 변한 항목 중 변화율이 가장 큰 것. 없으면 "변화 없음"(작은 값의 큰 변화율을 뽑지 않는다)
        Map<String, Object> bestM = null;
        Map<String, Object> maxAny = null;
        if (metrics != null) {
            for (Object o : (List<Object>) metrics.get("rows")) {
                Map<String, Object> r = (Map<String, Object>) o;
                Double c = (Double) r.get("changePct");
                if (c == null) continue;
                if (maxAny == null || Math.abs(c) > Math.abs((Double) maxAny.get("changePct"))) maxAny = r;
                if (r.get("level") != null && (bestM == null || Math.abs(c) > Math.abs((Double) bestM.get("changePct")))) bestM = r;
            }
        }
        if (bestM != null) {
            double c = (Double) bestM.get("changePct");
            out.add(card("metric", String.valueOf(bestM.get("label")), num(bestM.get("a")) + " → " + num(bestM.get("b")) + " " + bestM.get("unit"),
                    pct(c), String.valueOf(bestM.get("level"))));
        } else if (maxAny != null) {
            out.add(card("metric", "부하 지표", "변화 없음", "가장 큰 변화: " + maxAny.get("label") + " " + pct((Double) maxAny.get("changePct")), null));
        } else {
            out.add(card("metric", "부하 지표", "-", "비교할 지표 값이 없습니다(A/B 양쪽에 수집 데이터가 필요).", null));
        }
        // 2) 대기: 분당 대기 시간 상위 몇 개 중 평균 배율이 가장 큰 이벤트. 큰 변화가 없으면 신규 이벤트, 그것도 없으면 "변화 없음"
        Map<String, Object> bestW = null;
        Map<String, Object> firstNew = null;
        boolean anyAvg = false;
        if (waits != null) {
            int seen = 0;
            for (Object o : (List<Object>) waits.get("rows")) {
                if (seen++ >= WAIT_CARD_TOP) break;
                Map<String, Object> r = (Map<String, Object>) o;
                Double ratio = (Double) r.get("ratio");
                if (ratio != null) anyAvg = true;
                if ("new".equals(r.get("badge")) && firstNew == null) firstNew = r;
                if (ratio == null) continue;
                if (bestW == null || ratio > (Double) bestW.get("ratio")) bestW = r;
            }
        }
        if (bestW != null && (Double) bestW.get("ratio") >= WAIT_CARD_MIN_RATIO) {
            out.add(card("wait", bestW.get("event") + " 평균", num(bestW.get("aAvgMs")) + " → " + num(bestW.get("bAvgMs")) + " ms",
                    "×" + plain((Double) bestW.get("ratio"), 2), "slow".equals(bestW.get("badge")) ? "up" : null));
        } else if (firstNew != null) {
            out.add(card("wait", "신규 대기 이벤트", String.valueOf(firstNew.get("event")), "A 구간에는 없던 이벤트(B 평균 " + num(firstNew.get("bAvgMs")) + " ms)", "up"));
        } else if (anyAvg) {
            out.add(card("wait", "대기 이벤트", "변화 없음", "평균 대기시간이 크게 달라진 이벤트가 없습니다.", null));
        } else {
            out.add(card("wait", "대기 이벤트", "-", "비교할 대기 이벤트 평균이 없습니다.", null));
        }
        // 3) Plan 변경 건수 - B에 SQL 통계가 없으면 판단 보류(감지는 매시 1회 배치라 최근 구간은 아직 안 잡혔을 수 있다)
        if (sqlRes == null) out.add(card("plan", "Plan 변경", "-", "SQL 변화를 읽지 못했습니다.", null));
        else if (!Boolean.TRUE.equals(sqlRes.get("hasB"))) out.add(card("plan", "Plan 변경", "-", "B 구간에 SQL 통계가 없어 판단을 보류합니다.", null));
        else {
            Map<String, Object> counts = (Map<String, Object>) sqlRes.get("counts");
            long plan = ((Number) counts.get("plan")).longValue();
            Map<String, Object> top = (Map<String, Object>) sqlRes.get("topPlan");
            out.add(card("plan", "Plan 변경", plan + "건", top == null ? "B 구간에 감지된 Plan 변경이 없습니다(감지는 1시간 주기)." : top.get("sqlId") + (top.get("ratio") == null ? "" : " ×" + top.get("ratio") + " 악화"),
                    plan > 0 ? "up" : null));
        }
        // 4) 락 발생 건수 - 한쪽이라도 수집 전이면 0건이 아니라 판단 보류. 길이가 다르면 시간당 건수로 견준다
        if (la == null || lb == null) out.add(card("lock", "락 발생", "-", "락 건수를 읽지 못했습니다.", null));
        else if (!covOk(cov, "a", "ash") || !covOk(cov, "b", "ash")) {
            out.add(card("lock", "락 발생", "-", "A 또는 B 구간이 수집 전이라 락 건수를 비교할 수 없습니다.", null));
        } else {
            long ta = ((Number) la.get("total")).longValue();
            long tb = ((Number) lb.get("total")).longValue();
            boolean same = Math.abs(minA - minB) <= SAME_LENGTH_RATIO * Math.max(minA, minB);
            double ra = ta * 60.0 / Math.max(1, minA);
            double rb = tb * 60.0 / Math.max(1, minB);
            String level = same ? (tb > ta ? "up" : tb < ta ? "down" : null) : (rb > ra ? "up" : rb < ra ? "down" : null);
            out.add(card("lock", "락 발생", ta + " → " + tb + "건",
                    "B: TX " + lb.get("tx") + "건 · TM " + lb.get("tm") + "건" + (same ? "" : " (구간 길이가 달라 시간당 " + plain(ra, 1) + " → " + plain(rb, 1) + "건으로 비교)"), level));
        }
        return out;
    }

    // ------------------------------------------------------------------ 진입점

    /** 비교 결과 전체. 섹션별로 실패하면 그 섹션만 null로 두고 errors에 한국어 사유를 담는다. 구간이 24시간을 넘으면 IllegalArgumentException. */
    public Map<String, Object> compare(TargetDbConfig target, LocalDateTime aFrom, LocalDateTime aTo0, LocalDateTime bFrom, LocalDateTime bTo0) {
        String dbId = target.id();
        if (minutes(aFrom, aTo0) > AshRange.MAX_RANGE_MINUTES || minutes(bFrom, bTo0) > AshRange.MAX_RANGE_MINUTES) {
            throw new IllegalArgumentException("구간은 각각 최대 24시간까지 가능합니다. 구간을 줄여 주세요.");
        }
        Long collectedBefore = store.collectedBefore(dbId);
        LocalDateTime aTo = clampTo(collectedBefore, aFrom, aTo0);
        LocalDateTime bTo = clampTo(collectedBefore, bFrom, bTo0);
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        Map<String, Object> errors = new LinkedHashMap<String, Object>();
        res.put("a", range(aFrom, aTo, aTo0));
        res.put("b", range(bFrom, bTo, bTo0));

        Map<String, Object> cov = new LinkedHashMap<String, Object>();
        try {
            Long firstAsh = lock.firstHeartbeat(dbId, "ash");
            Long firstSys = lock.firstHeartbeat(dbId, "sys");
            Map<String, Object> ca = new LinkedHashMap<String, Object>();
            Map<String, Object> cb = new LinkedHashMap<String, Object>();
            ca.put("ash", coverage(dbId, "ash", firstAsh, collectedBefore, aFrom, aTo));
            ca.put("sys", coverage(dbId, "sys", firstSys, collectedBefore, aFrom, aTo));
            cb.put("ash", coverage(dbId, "ash", firstAsh, collectedBefore, bFrom, bTo));
            cb.put("sys", coverage(dbId, "sys", firstSys, collectedBefore, bFrom, bTo));
            cov.put("a", ca);
            cov.put("b", cb);
        } catch (RuntimeException e) {
            log.warn("perf compare coverage failed for db_id={}: {}", dbId, e.toString());
            errors.put("coverage", "수집 커버율을 읽지 못했습니다.");
        }
        res.put("coverage", cov.isEmpty() ? null : cov);

        Map<String, Object> metrics = null;
        try {
            metrics = metrics(dbId, aFrom, aTo, bFrom, bTo);
        } catch (RuntimeException e) {
            log.warn("perf compare metrics failed for db_id={}: {}", dbId, e.toString());
            errors.put("metrics", "시스템 부하 지표를 읽지 못했습니다.");
        }
        Map<String, Object> waits = null;
        try {
            waits = sys.compareWaits(dbId, aFrom, aTo, bFrom, bTo);
        } catch (RuntimeException e) {
            log.warn("perf compare waits failed for db_id={}: {}", dbId, e.toString());
            errors.put("waits", "대기 이벤트를 읽지 못했습니다.");
        }
        Map<String, Object> sqlRes = null;
        try {
            sqlRes = sql.compareSql(target, aFrom, aTo, bFrom, bTo);
        } catch (RuntimeException e) {
            log.warn("perf compare sql failed for db_id={}: {}", dbId, e.toString());
            errors.put("sql", "SQL 변화를 읽지 못했습니다.");
        }
        Map<String, Object> la = null;
        Map<String, Object> lb = null;
        try {
            la = lock.compareCounts(dbId, aFrom, aTo);
            lb = lock.compareCounts(dbId, bFrom, bTo);
        } catch (RuntimeException e) {
            log.warn("perf compare locks failed for db_id={}: {}", dbId, e.toString());
            errors.put("locks", "락 발생 건수를 읽지 못했습니다.");
            la = null;
            lb = null;
        }
        res.put("metrics", metrics);
        res.put("waits", waits);
        res.put("sql", sqlRes);
        Map<String, Object> locks = null;
        if (la != null && lb != null) {
            locks = new LinkedHashMap<String, Object>();
            locks.put("a", la);
            locks.put("b", lb);
        }
        res.put("locks", locks);
        res.put("summary", summary(metrics, waits, sqlRes, la, lb, cov, minutes(aFrom, aTo), minutes(bFrom, bTo)));
        res.put("warnings", warnings(cov, aFrom, aTo, bFrom, bTo, aTo0, bTo0));
        res.put("errors", errors);
        return res;
    }

    /** 커버율이 낮거나 수집 전인 구간, 잘린 종료 시각, A·B 겹침을 알린다(낮은 구간의 비교 결과는 믿기 어렵다). */
    @SuppressWarnings("unchecked")
    private List<Object> warnings(Map<String, Object> cov, LocalDateTime aFrom, LocalDateTime aTo, LocalDateTime bFrom, LocalDateTime bTo,
                                  LocalDateTime aTo0, LocalDateTime bTo0) {
        List<Object> w = new ArrayList<Object>();
        if (aFrom.isBefore(bTo) && bFrom.isBefore(aTo)) w.add("A와 B 구간이 겹칩니다. 겹친 시간은 양쪽에 함께 집계되어 변화가 작게 보일 수 있습니다.");
        if (!aTo.equals(aTo0)) w.add("A 구간 종료가 아직 수집되지 않은 시각이라 " + aTo.toString().replace('T', ' ') + "까지로 잘랐습니다.");
        if (!bTo.equals(bTo0)) w.add("B 구간 종료가 아직 수집되지 않은 시각이라 " + bTo.toString().replace('T', ' ') + "까지로 잘랐습니다.");
        if (cov.isEmpty()) return w;
        for (String side : new String[]{"a", "b"}) {
            Map<String, Object> c = (Map<String, Object>) cov.get(side);
            for (String kind : new String[]{"ash", "sys"}) {
                Map<String, Object> k = (Map<String, Object>) c.get(kind);
                String name = side.toUpperCase() + " 구간 " + ("ash".equals(kind) ? "세션·Lock" : "시스템 부하");
                String state = (String) k.get("state");
                if ("before".equals(state)) w.add(name + "은 수집이 시작되기 전입니다. 비교할 데이터가 없습니다.");
                else if ("pending".equals(state)) w.add(name + "은 아직 수집되지 않은 시각입니다.");
                else if ("ok".equals(state) && ((Double) k.get("pct")) < COVERAGE_WARN_PCT) {
                    w.add(name + " 수집 커버율이 " + k.get("pct") + "%로 낮습니다. 공백 구간이 있어 비교 결과가 실제와 다를 수 있습니다.");
                }
            }
        }
        return w;
    }
}
