// G3 성능 분석(구 "성능 이력 조회", 2026-09-26) - 설계문서 `성능이력조회_개편_검토_2026-09-24.md` 9절.
// 성능 분석 개편 Phase 0b(2026-10-09): 조건 바·시간 계산·필터·선택 구간 보관·하위 탭 프레임은 perf-shell.js(PerfShell)로 옮겼고,
// 이 파일은 "세션 분석" 하위 탭(차트·Event 막대·세션 목록 팝업)만 맡는다. perf-shell.js, app.js 다음에 로드한다
// (전역 getToken·chartLineColor·dbagentPopupFeatures 사용).
//
// 데이터 출처(2026-09-26 오케스트레이터 결정): 기본은 수집 저장소(main SQLite / AIX H2의 perf 저장소 - 60초 샘플러가
// 분 단위로 (7분류, event, 계정, 서버, SQL) 요약을 쌓는다, 15일 보관). 운영 DB에는 조회가 가지 않는다. 수집 전 구간을
// 봐야 할 때만 화면의 "원본 DB(ASH/AWR)에서 조회" 버튼으로 예전 경로를 쓴다. 원본 조회는 조회 1회 단위이고, 구간을 다시
// 조회하거나 다른 하위 탭으로 가면 수집 저장소로 돌아온다(설계 3.2, 결정 8).
//
// 3단 드릴다운: 상단 wait class 막대(/api/perf/activity, 원본은 /api/ash_activity - 드래그로 구간 선택)
//   → 하단 선택 구간의 event별 막대(/api/perf/events)
//   → 막대 클릭 시 그 event의 세션 목록 팝업(perf-event-sessions.html, G2 팝업 스펙의 첫 신규 적용).
(function () {
    'use strict';

    const S = window.PerfShell;
    if (!S) { console.error('[DBAgent] perf-shell.js 가 먼저 로드되어야 합니다.'); return; }
    const { $, esc, parseLocal, fmtMinute, human, toLocal, getJson } = S.utils;

    // Current Session의 Active Session Wait Class 차트(app.js ASH_ACTIVITY_CATEGORIES)와 같은 7분류·같은 색.
    // 서버 키는 AshCategories.KEYS7 순서(system_io). 색은 style.css 변수(--nd-wc-*)에서 읽는다.
    const CAT_DEFS = [
        { key: 'cpu', label: 'CPU', cssVar: '--nd-wc-cpu' },
        { key: 'latch', label: 'Latch', cssVar: '--nd-wc-latch' },
        { key: 'user_io', label: 'User I/O', cssVar: '--nd-wc-user-io' },
        { key: 'tx_lock', label: 'TX Lock', cssVar: '--nd-wc-tx-lock' },
        { key: 'system_io', label: 'Sys I/O', cssVar: '--nd-wc-system-io' },
        { key: 'tm_lock', label: 'TM Lock', cssVar: '--nd-wc-tm-lock' },
        { key: 'other', label: 'Other', cssVar: '--nd-wc-other' }
    ];
    const CATS = CAT_DEFS.map(c => ({ key: c.key, label: c.label, get color() { return S.cssVar(c.cssVar); } }));
    const CAT_BY_KEY = {};
    CATS.forEach(c => { CAT_BY_KEY[c.key] = c; });

    const st = {
        activity: null,
        eventsGen: 0,
        source: 'store'    // 'store'(수집 저장소, 기본) | 'oracle'(원본 ASH/AWR)
    };
    let activityChart = null;
    let eventsChart = null;
    let lastEvents = [];

    function stepFor(minutes) {
        if (minutes <= 90) return 1;
        if (minutes <= 6 * 60) return 5;
        if (minutes <= 12 * 60) return 10;
        return 15;
    }

    function sourceText(src) {
        if (src === 'store') return '수집 저장소(1분 요약)';
        return src === 'awr' ? '원본 AWR(10초 간격)' : src === 'mixed' ? '원본 ASH + AWR 보충' : '원본 ASH';
    }

    // ------------------------------------------------------------------ 조회 (공통 조건 바의 조회를 구독)

    async function runSearch(ctx) {
        // 원본 조회는 조회 1회 단위 - 전환 버튼에서 온 조회가 아니면 수집 저장소로 되돌린다.
        if (!ctx.toggle) st.source = 'store';
        const my = ctx.gen;
        const dbId = ctx.dbId;
        st.activity = null;
        const overlay = $('history-loading-overlay');
        if (overlay) overlay.style.display = 'flex';
        try {
            const step = stepFor(ctx.minutes);
            const actPath = st.source === 'oracle' ? '/api/ash_activity' : '/api/perf/activity';
            const act = await getJson(`${actPath}?db_id=${encodeURIComponent(dbId)}&from=${encodeURIComponent(ctx.from)}&to=${encodeURIComponent(ctx.to)}&step_minutes=${step}${ctx.filter}`);
            if (my !== S.gen() || dbId !== window.currentDbId) return;
            st.activity = act;
            if (act.from && act.to) S.confirmRange(act.from, act.to);
            renderActivity(act);
            renderSelection();
            await loadEvents();
        } catch (e) {
            if (my === S.gen()) {
                S.setMeta(`<span style="color: var(--danger);">조회 실패: ${esc(e.message)}</span>`);
                clearEvents('조회에 실패했습니다.');
            }
        } finally {
            if (my === S.gen() && overlay) overlay.style.display = 'none';
        }
    }

    async function loadEvents() {
        const w = S.window();
        if (!w) return;
        const my = ++st.eventsGen;
        const dbId = S.getDbId();
        clearEvents('불러오는 중…');
        try {
            const d = await getJson(`/api/perf/events?db_id=${encodeURIComponent(dbId)}&from=${encodeURIComponent(w.from)}&to=${encodeURIComponent(w.to)}${S.getFilter()}&source=${st.source}`);
            if (my !== st.eventsGen || dbId !== window.currentDbId) return;
            renderEvents(d);
        } catch (e) {
            if (my === st.eventsGen) clearEvents('Event 집계 실패: ' + e.message);
        }
    }

    // ------------------------------------------------------------------ 상단: wait class 막대

    function renderActivity(d) {
        const series = d.series || [];
        // null = 아직 수집이 끝나지 않은 막대(서버 collectedTo 이후) - 0으로 그리지 않고 비워 둔다(2026-09-30 QA)
        const total = series.reduce((a, p) => a + p.values.reduce((x, y) => x + (y || 0), 0), 0);
        const pendingCount = series.filter(p => p.values.some(v => v === null)).length;
        const pendingNote = pendingCount ? ` · <span class="perf-hint" title="수집 완료 ${esc(human(d.collectedTo || ''))}까지">최근 ${pendingCount * d.step_minutes}분은 수집 중</span>` : '';
        const store = d.source === 'store';
        const empty = total === 0
            ? (store ? ` · <span class="perf-warn">이 구간에 수집 데이터가 없습니다${d.storedFrom ? ` (수집 시작 ${esc(human(d.storedFrom))})` : ''}</span>`
                : ' · <span class="perf-warn">이 구간에 샘플이 없습니다(ASH/AWR 보관 기간 밖일 수 있음)</span>')
            : '';
        // 수집 저장소가 비어 있는 구간(수집 시작 전·앱이 꺼져 있던 시간)만 원본 ASH/AWR로 볼 수 있게 - 운영 DB 조회라 버튼으로만
        const switchBtn = store
            ? (total === 0 ? ' <button type="button" class="perf-link-btn" id="perf-src-toggle" data-src="oracle">원본 DB(ASH/AWR)에서 조회</button>' : '')
            : ' <span class="perf-warn">운영 DB 조회</span> <button type="button" class="perf-link-btn" id="perf-src-toggle" data-src="store">수집 저장소로 돌아가기</button>';
        S.setMeta(`조회 구간 <b>${esc(human(d.from))} ~ ${esc(human(d.to))}</b> · 데이터 <b>${esc(sourceText(d.source))}</b>` +
            ` · ${d.step_minutes}분 막대 · CPU ${d.cpu_cores || '-'}` + pendingNote + empty + switchBtn);
        const tg = $('perf-src-toggle');
        if (tg) tg.addEventListener('click', () => { st.source = tg.dataset.src; S.search({ toggle: true }); });
        const datasets = CATS.map((c, i) => ({
            label: c.label,
            data: series.map(p => ({ x: parseLocal(p.time).getTime(), y: p.values[i] })),
            backgroundColor: c.color + 'd1',
            borderColor: c.color,
            borderWidth: 0,
            stack: 'ash'
        }));
        datasets.push({
            type: 'line',
            label: `CPU 수 (${d.cpu_cores || 0})`,
            data: series.map(p => ({ x: parseLocal(p.time).getTime(), y: d.cpu_cores || 0 })),
            borderColor: chartLineColor(0.6),
            borderDash: [5, 4],
            borderWidth: 1.5,
            pointRadius: 0,
            fill: false,
            order: -1
        });
        if (activityChart) {
            activityChart.data.datasets = datasets;
            activityChart.options.scales.x.time.unit = d.step_minutes >= 10 ? 'hour' : 'minute';
            activityChart.update();
            return;
        }
        activityChart = new Chart($('perf-activity-chart'), {
            type: 'bar',
            data: { datasets },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: { duration: 0 },
                interaction: { mode: 'index', intersect: false },
                scales: {
                    x: {
                        type: 'time', stacked: true, offset: true,
                        time: { unit: d.step_minutes >= 10 ? 'hour' : 'minute', tooltipFormat: 'MM-dd HH:mm', displayFormats: { minute: 'HH:mm', hour: 'MM-dd HH:mm' } },
                        ticks: { color: chartLineColor(0.8), maxRotation: 0, autoSkip: true },
                        grid: { color: chartLineColor(0.12) }
                    },
                    y: {
                        stacked: true, beginAtZero: true,
                        title: { display: true, text: 'AAS', color: chartLineColor(0.8) },
                        ticks: { color: chartLineColor(0.8), precision: 1 },
                        grid: { color: chartLineColor(0.12) }
                    }
                },
                plugins: {
                    legend: { position: 'top', labels: { color: chartLineColor(0.9), boxWidth: 12 } },
                    title: { display: true, text: 'Active Session Wait Class', color: chartLineColor(0.95) }
                }
            },
            plugins: [selectionBandPlugin]
        });
    }

    // 드래그로 고른 구간을 차트 위에 옅게 표시(색은 --nd-sel-fill)
    const selectionBandPlugin = {
        id: 'perfSelectionBand',
        beforeDatasetsDraw(chart) {
            const sel = S.getSel();
            if (!sel) return;
            const x = chart.scales.x;
            const a = x.getPixelForValue(parseLocal(sel.from).getTime());
            const b = x.getPixelForValue(parseLocal(sel.to).getTime());
            const { top, bottom } = chart.chartArea;
            const ctx = chart.ctx;
            ctx.save();
            ctx.fillStyle = S.cssVar('--nd-sel-fill');
            ctx.fillRect(Math.min(a, b), top, Math.abs(b - a), bottom - top);
            ctx.restore();
        }
    };

    function renderSelection() {
        const w = S.window();
        if (!w) return;
        const sel = S.getSel();
        $('perf-sel-text').textContent = sel ? `선택 구간: ${human(w.from)} ~ ${human(w.to)}` : `선택 구간: 전체 (${human(w.from)} ~ ${human(w.to)})`;
        $('perf-sel-reset').style.display = sel ? '' : 'none';
        S.refreshGoButtons();
        if (activityChart) activityChart.update('none');
    }

    function attachBrush() {
        const container = $('perf-activity-container');
        const box = $('perf-activity-selection-box');
        if (!container || container.dataset.brushBound) return;
        container.dataset.brushBound = '1';
        let dragging = false;
        let startX = 0;
        container.addEventListener('mousedown', (e) => {
            if (e.target.id !== 'perf-activity-chart' || !activityChart || !S.getRange()) return;
            dragging = true;
            startX = toLocal(e, container).x;
            box.style.left = startX + 'px';
            box.style.width = '0px';
            box.style.display = 'block';
        });
        window.addEventListener('mousemove', (e) => {
            if (!dragging) return;
            const x = toLocal(e, container).x;
            box.style.left = Math.min(startX, x) + 'px';
            box.style.width = Math.abs(x - startX) + 'px';
        });
        window.addEventListener('mouseup', (e) => {
            if (!dragging) return;
            dragging = false;
            box.style.display = 'none';
            const endX = toLocal(e, container).x;
            if (Math.abs(endX - startX) < 5) return;
            const range = S.getRange();
            if (!range) return;
            const xs = activityChart.scales.x;
            const lo = Math.max(xs.getValueForPixel(Math.min(startX, endX)), parseLocal(range.from).getTime());
            const hi = Math.min(xs.getValueForPixel(Math.max(startX, endX)), parseLocal(range.to).getTime());
            if (!(hi > lo)) return;
            // 분 단위로 넓혀 잡는다(막대 하나 폭보다 좁게 골라도 최소 1분).
            const from = new Date(Math.floor(lo / 60000) * 60000);
            let to = new Date(Math.ceil(hi / 60000) * 60000);
            if (to.getTime() - from.getTime() < 60000) to = new Date(from.getTime() + 60000);
            S.setSel({ from: fmtMinute(from), to: fmtMinute(to) });
            renderSelection();
            loadEvents();
        });
    }

    // ------------------------------------------------------------------ 하단: event별 막대

    function clearEvents(msg) {
        lastEvents = [];
        if (eventsChart) { eventsChart.destroy(); eventsChart = null; }
        const empty = $('perf-events-empty');
        if (empty) { empty.textContent = msg || ''; empty.style.display = msg ? 'flex' : 'none'; }
    }

    function renderEvents(d) {
        const events = d.events || [];
        if (!events.length) { clearEvents('선택 구간에 대기 이벤트 샘플이 없습니다.'); return; }
        $('perf-events-empty').style.display = 'none';
        lastEvents = events;
        const box = $('perf-events-container');
        box.style.height = Math.max(160, 40 + events.length * 26) + 'px';
        const colors = events.map(e => (CAT_BY_KEY[e.category] || CAT_BY_KEY.other).color);
        const data = {
            labels: events.map(e => e.event),
            datasets: [{
                label: '대기 시간(초)',
                data: events.map(e => e.seconds),
                backgroundColor: colors.map(c => c + 'd1'),
                borderColor: colors,
                borderWidth: 1
            }]
        };
        if (eventsChart) eventsChart.destroy();
        eventsChart = new Chart($('perf-events-chart'), {
            type: 'bar',
            data,
            options: {
                indexAxis: 'y',
                responsive: true,
                maintainAspectRatio: false,
                animation: { duration: 0 },
                onHover: (evt, els, chart) => { chart.canvas.style.cursor = 'pointer'; },
                scales: {
                    x: { beginAtZero: true, title: { display: true, text: `대기 시간(초) · 합계 ${d.totalSeconds}초 · ${sourceText(d.source)}`, color: chartLineColor(0.8) },
                        ticks: { color: chartLineColor(0.8) }, grid: { color: chartLineColor(0.12) } },
                    y: { ticks: { color: chartLineColor(0.9), autoSkip: false }, grid: { display: false } }
                },
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        callbacks: {
                            label: (ctx) => {
                                const e = events[ctx.dataIndex];
                                const cat = (CAT_BY_KEY[e.category] || CAT_BY_KEY.other).label;
                                return ` ${e.seconds}초 · AAS ${e.aas} · ${e.pct}% · ${cat}`;
                            }
                        }
                    }
                }
            }
        });
    }

    // 막대(행) 클릭 → 세션 목록 팝업. zoom 90% 컨테이너라 Chart.js 이벤트 좌표 대신 toLocal로 행을 찾는다.
    function attachEventsClick() {
        const container = $('perf-events-container');
        if (!container || container.dataset.clickBound) return;
        container.dataset.clickBound = '1';
        container.addEventListener('click', (e) => {
            if (!eventsChart || !lastEvents.length || e.target.id !== 'perf-events-chart') return;
            const y = toLocal(e, container).y;
            const ys = eventsChart.scales.y;
            if (y < ys.top || y > ys.bottom) return;
            const idx = Math.round(ys.getValueForPixel(y));
            const ev = lastEvents[idx];
            if (ev) openEventSessions(ev.event);
        });
    }

    function openEventSessions(event) {
        const w = S.window();
        if (!w) return;
        const q = `db_id=${encodeURIComponent(S.getDbId())}&event=${encodeURIComponent(event)}&from=${encodeURIComponent(w.from)}&to=${encodeURIComponent(w.to)}${S.getFilter()}&source=${st.source}`;
        const popup = window.open('perf-event-sessions.html?' + q, 'dbagent_perf_event_sessions', dbagentPopupFeatures());
        if (popup) popup.focus();
    }

    // ------------------------------------------------------------------ 연결

    // DB가 바뀌었을 때(공통 조건 바가 알려 준다) 이전 DB 결과를 지운다.
    function reset() {
        st.eventsGen++;
        st.activity = null;
        st.source = 'store';
        // 진행 중이던 조회는 gen이 바뀌어 finally에서 오버레이를 못 끄므로 여기서 끈다(db_now 실패 시 "조회 중" 화면이 남는 것 방지)
        const ov = $('history-loading-overlay');
        if (ov) ov.style.display = 'none';
        if (activityChart) { activityChart.destroy(); activityChart = null; }
        clearEvents('');
        S.setMeta('조회할 구간을 정하고 조회 버튼을 눌러 주세요. 최대 24시간, ASH 보관 범위 밖은 AWR로 보충합니다.');
        const sel = $('perf-sel-text');
        if (sel) sel.textContent = '선택 구간: 전체';
        const r = $('perf-sel-reset');
        if (r) r.style.display = 'none';
    }

    function init() {
        if (!$('perf-activity-chart')) return;
        S.onSearch(runSearch);
        S.onReset(reset);
        $('perf-sel-reset').addEventListener('click', () => { S.setSel(null); renderSelection(); loadEvents(); });
        attachBrush();
        attachEventsClick();
        // 다른 하위 탭으로 가면 원본 조회 상태를 버리고 수집 저장소로 돌아온다(설계 3.2).
        window.addEventListener('perf:route', (ev) => {
            const sub = (ev && ev.detail && ev.detail.sub) || 'session';
            if (sub !== 'session') st.source = 'store';
        });
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
