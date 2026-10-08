// G3 성능 분석(구 "성능 이력 조회", 2026-09-26) - 설계문서 `성능이력조회_개편_검토_2026-09-24.md` 9절.
// app.js 다음에 로드한다(전역 getToken·chartLineColor·dbagentPopupFeatures 사용).
//
// 데이터 출처(2026-09-26 오케스트레이터 결정): 기본은 수집 저장소(main SQLite / AIX H2의 perf 저장소 - 60초 샘플러가
// 분 단위로 (7분류, event, 계정, 서버, SQL) 요약을 쌓는다, 15일 보관). 운영 DB에는 조회가 가지 않는다. 수집 전 구간을
// 봐야 할 때만 화면의 "원본 DB(ASH/AWR)에서 조회" 버튼으로 예전 경로를 쓴다.
//
// 3단 드릴다운: 상단 wait class 막대(/api/perf/activity, 원본은 /api/ash_activity - 드래그로 구간 선택)
//   → 하단 선택 구간의 event별 막대(/api/perf/events)
//   → 막대 클릭 시 그 event의 세션 목록 팝업(perf-event-sessions.html, G2 팝업 스펙의 첫 신규 적용).
// 시각은 전부 DB 시각이다. 입력칸 기본값도 /api/perf/db_now 기준 최근 15분(브라우저·DB 시간대가 달라도 맞게)이고,
// 메뉴에 처음 들어오거나 DB를 바꾸면 그 구간을 바로 조회한다.
(function () {
    'use strict';

    // Current Session의 Active Session Wait Class 차트(app.js ASH_ACTIVITY_CATEGORIES)와 같은 7분류·같은 색.
    // 서버 키는 AshCategories.KEYS7 순서(system_io).
    const CATS = [
        { key: 'cpu', label: 'CPU', color: '#22d3ee' },
        { key: 'latch', label: 'Latch', color: '#808000' },
        { key: 'user_io', label: 'User I/O', color: '#2ecc71' },
        { key: 'tx_lock', label: 'TX Lock', color: '#7c3aed' },
        { key: 'system_io', label: 'Sys I/O', color: '#e67e22' },
        { key: 'tm_lock', label: 'TM Lock', color: '#be123c' },
        { key: 'other', label: 'Other', color: '#94a3b8' }
    ];
    const CAT_BY_KEY = {};
    CATS.forEach(c => { CAT_BY_KEY[c.key] = c; });

    const st = {
        dbId: null,        // 조회한 DB(입력칸 기본값도 이 DB 기준)
        range: null,       // {from, to} 조회 구간(DB 시각 문자열 yyyy-MM-ddTHH:mm)
        sel: null,         // {from, to} 드래그 선택 구간, 없으면 null(= 전체)
        filter: '',        // &users=..&machines=..
        activity: null,
        gen: 0,
        eventsGen: 0,
        initFor: null,     // 입력칸 기본값을 채운 DB
        source: 'store',   // 'store'(수집 저장소, 기본) | 'oracle'(원본 ASH/AWR)
        nowOffsetMs: 0,    // DB 시각 - 브라우저 시각(db_now 응답 기준). 빠른 선택·max 계산을 DB 시각으로 맞추려고
        nowReady: false    // 현재 DB의 db_now를 받아 nowOffsetMs가 유효한지(받기 전·DB 전환 직후에는 브라우저 시각으로 계산하면 시간대가 어긋난다)
    };
    const MAX_RANGE_MIN = 24 * 60;
    let activityChart = null;
    let eventsChart = null;
    let lastEvents = [];

    const $ = (id) => document.getElementById(id);
    // 화면 좌표 → 컨테이너 안 좌표. 본문이 zoom 90%라 getBoundingClientRect(확대 반영)와 clientWidth(CSS px)의
    // 비율로 되돌린다 - app.js의 scatterPointerToLocal과 같은 계산(그쪽은 initApp 안에 있어 전역이 아니다).
    function toLocal(e, container) {
        const rect = container.getBoundingClientRect();
        const sx = rect.width ? container.clientWidth / rect.width : 1;
        const sy = rect.height ? container.clientHeight / rect.height : 1;
        return {
            x: Math.max(0, Math.min((e.clientX - rect.left) * sx, container.clientWidth)),
            y: Math.max(0, Math.min((e.clientY - rect.top) * sy, container.clientHeight))
        };
    }
    const pad = (n) => String(n).padStart(2, '0');
    const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    // DB 시각 문자열 <-> 차트 x값. 차트는 시간대 변환 없이 "벽시계 그대로" 그리도록 문자열을 로컬 Date로 읽는다.
    const parseLocal = (s) => new Date(String(s).length === 16 ? s + ':00' : s);
    const fmtMinute = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
    const human = (s) => String(s || '').replace('T', ' ').slice(0, 16);

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

    function filterParams() {
        const pick = (id) => {
            const el = $(id);
            return el ? Array.from(el.selectedOptions).map(o => o.value).filter(Boolean).join(',') : '';
        };
        const u = pick('history-users');
        const m = pick('history-machines');
        return (u ? `&users=${encodeURIComponent(u)}` : '') + (m ? `&machines=${encodeURIComponent(m)}` : '');
    }

    async function getJson(url) {
        const res = await fetch(url + `&token=${encodeURIComponent(getToken())}`);
        const d = await res.json().catch(() => null);
        if (!res.ok || !d || d.error) throw new Error((d && d.error) || ('HTTP ' + res.status));
        return d;
    }

    // ------------------------------------------------------------------ 기본 구간(DB 시각 최근 15분)
    // 2026-09-26 오케스트레이터 결정: 1시간 → 15분(초기 자동 조회 부하를 줄이고, 구간이 ASH 보관 범위를 넘어 AWR까지 가지 않게).
    const DEFAULT_RANGE_MS = 15 * 60000;

    // 지금의 DB 시각(분 단위로 내림). db_now를 받은 뒤의 오프셋으로 계산해 브라우저·DB 시간대가 달라도 맞는다.
    const dbNowDate = () => new Date(Math.floor((Date.now() + st.nowOffsetMs) / 60000) * 60000);

    function rangeMsg(text) {
        const el = $('perf-range-msg');
        if (!el) return;
        el.textContent = text || '';
        el.style.display = text ? '' : 'none';
    }

    // 입력칸에서 미래를 못 고르게 max를 지금(DB 시각)으로 둔다(2026-10-09 오케스트레이터 요청: 시간 선택 개선).
    function refreshInputLimits() {
        if (!st.nowReady) return; // DB 시각을 모르면 max를 두지 않는다(브라우저 시각으로 두면 DB가 더 앞선 시간대에서 유효한 시각을 막는다)
        const mx = fmtMinute(dbNowDate());
        $('history-start-time').max = mx;
        $('history-end-time').max = mx;
    }

    function setRange(from, to) {
        $('history-start-time').value = fmtMinute(from);
        $('history-end-time').value = fmtMinute(to);
        refreshInputLimits();
        validateRange();
    }

    // 조회 전에 입력칸 아래 문구로 미리 알린다(alert 대신). 조회할 수 있으면 true.
    function validateRange() {
        const from = $('history-start-time').value;
        const to = $('history-end-time').value;
        const btn = $('perf-search-btn');
        let msg = '';
        let blocked = false;
        if (!from || !to) { msg = '시작·종료 시간을 모두 입력해 주세요.'; blocked = true; }
        else {
            const minutes = (parseLocal(to) - parseLocal(from)) / 60000;
            if (!(minutes > 0)) { msg = '시작 시간이 종료 시간보다 앞서야 합니다.'; blocked = true; }
            else if (minutes > MAX_RANGE_MIN) { msg = '한 번에 최대 24시간까지 조회할 수 있습니다. 구간을 줄여 주세요.'; blocked = true; }
            // 시작이 DB 현재 시각 이후면 서버가 종료만 잘라 시작>종료로 뒤집히므로 미리 막는다(QA 결함 1, 키보드 직접 입력으로 재현)
            else if (st.nowReady && parseLocal(from) >= dbNowDate()) { msg = '시작 시간이 DB 현재 시각보다 뒤입니다. 더 이전 시각을 선택해 주세요.'; blocked = true; }
            else if (parseLocal(to) > dbNowDate()) msg = '종료 시간이 DB 현재 시각보다 뒤입니다. 조회하면 현재 시각까지로 잘립니다.';
        }
        rangeMsg(msg);
        if (btn) btn.disabled = blocked;
        return !blocked;
    }

    // 시작을 바꿨는데 종료와 어긋나면(종료<=시작, 24시간 초과) 종료를 시작+1시간(최대 현재)으로 보정한다.
    function onStartChange() {
        const from = $('history-start-time').value;
        const to = $('history-end-time').value;
        if (from) {
            const f = parseLocal(from);
            const mins = to ? (parseLocal(to) - f) / 60000 : 0;
            if (!to || !(mins > 0) || mins > MAX_RANGE_MIN) {
                let e = new Date(Math.min(f.getTime() + 60 * 60000, dbNowDate().getTime()));
                if (!(e > f)) e = new Date(f.getTime() + 60000);
                $('history-end-time').value = fmtMinute(e);
            }
        }
        validateRange();
    }

    // 현재 DB의 db_now를 받아 시각 기준이 유효한지(DB 전환 직후·응답 전에는 이전 DB의 입력값으로 조회하지 않는다 - QA 결함 2)
    function nowOk() {
        return st.nowReady && st.initFor === window.currentDbId;
    }

    // 빠른 선택: 종료=DB 현재 시각, 시작=종료-N분 후 바로 조회
    function quickRange(minutes) {
        if (!nowOk()) { rangeMsg('DB 현재 시각을 확인하는 중입니다. 잠시 후 다시 눌러 주세요.'); return; }
        const now = dbNowDate();
        setRange(new Date(now.getTime() - minutes * 60000), now);
        search();
    }

    // 현재 폭만큼 앞/뒤로 이동(뒤로는 현재 시각을 넘지 않게)
    function shiftRange(dir) {
        if (!nowOk()) { rangeMsg('DB 현재 시각을 확인하는 중입니다. 잠시 후 다시 눌러 주세요.'); return; }
        const from = $('history-start-time').value;
        const to = $('history-end-time').value;
        if (!from || !to) return;
        const f = parseLocal(from).getTime();
        const t = parseLocal(to).getTime();
        const width = t - f;
        if (!(width > 0)) return;
        let nf = f + dir * width;
        let nt = t + dir * width;
        const now = dbNowDate().getTime();
        if (nt > now) { nt = now; nf = now - width; }
        setRange(new Date(nf), new Date(nt));
        search();
    }

    async function ensureDefaultRange() {
        const dbId = window.currentDbId;
        if (!dbId || st.initFor === dbId) return;
        st.initFor = dbId;
        st.nowReady = false; // DB가 바뀌었으면 이전 DB의 시계 차이를 쓰지 않는다
        try {
            const d = await getJson(`/api/perf/db_now?db_id=${encodeURIComponent(dbId)}`);
            const now = parseLocal(d.dbNow);
            st.nowOffsetMs = now.getTime() - Date.now();
            st.nowReady = true;
            setRange(new Date(now.getTime() - DEFAULT_RANGE_MS), now);
            loadFilters(dbId);
            // 초기 화면(2026-09-26 오케스트레이터 요청): 메뉴에 들어오면 빈 화면 대신 최근 15분을 바로 조회해
            // 상단 차트와 하단 event 막대를 보여 준다. 이미 조회한 결과가 있으면(같은 DB) 다시 조회하지 않는다.
            if (!st.range && dbId === window.currentDbId) search();
        } catch (e) {
            st.initFor = null; // 다음에 다시 시도
            console.warn('[DBAgent] perf db_now 실패:', e.message);
        }
    }

    // 계정·서버 드롭다운 - 수집 저장소의 보관 기간 안 값(운영 DB 조회 없음). 고른 값이 목록에 있으면 유지한다.
    async function loadFilters(dbId) {
        try {
            const d = await getJson(`/api/perf/filters?db_id=${encodeURIComponent(dbId)}`);
            if (dbId !== window.currentDbId) return;
            const fill = (id, list, allLabel) => {
                const el = $(id);
                if (!el) return;
                const cur = el.value;
                el.innerHTML = `<option value="">${allLabel}</option>` + (list || []).map(v => `<option value="${esc(v)}">${esc(v)}</option>`).join('');
                if (cur && (list || []).indexOf(cur) >= 0) el.value = cur;
                el.style.display = 'inline-block';
            };
            fill('history-users', d.users, '전체 계정(All)');
            fill('history-machines', d.machines, '전체 서버(All)');
        } catch (e) {
            console.warn('[DBAgent] perf filters 실패:', e.message);
        }
    }

    // ------------------------------------------------------------------ 조회

    async function search() {
        const dbId = window.currentDbId;
        const from = $('history-start-time').value;
        const to = $('history-end-time').value;
        if (!dbId) return;
        if (!nowOk()) { rangeMsg('DB 현재 시각을 확인하는 중입니다. 잠시 후 다시 눌러 주세요.'); return; }
        if (!validateRange()) return;
        const minutes = (parseLocal(to) - parseLocal(from)) / 60000;

        const my = ++st.gen;
        st.dbId = dbId;
        st.range = { from, to };
        st.sel = null;
        st.filter = filterParams();
        const overlay = $('history-loading-overlay');
        if (overlay) overlay.style.display = 'flex';
        try {
            const step = stepFor(minutes);
            const actPath = st.source === 'oracle' ? '/api/ash_activity' : '/api/perf/activity';
            const act = await getJson(`${actPath}?db_id=${encodeURIComponent(dbId)}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&step_minutes=${step}${st.filter}`);
            if (my !== st.gen || dbId !== window.currentDbId) return;
            st.activity = act;
            // 서버가 확정한 구간(종료가 DB 현재 시각보다 뒤면 현재 시각으로 잘림)을 기준으로 삼는다.
            if (act.from && act.to) {
                st.range = { from: act.from, to: act.to };
                // 서버가 잘라 확정한 구간을 입력칸에도 되돌려 채운다(입력값과 실제 조회 구간이 달라 보이지 않게)
                $('history-start-time').value = act.from.slice(0, 16);
                $('history-end-time').value = act.to.slice(0, 16);
                refreshInputLimits();
                validateRange();
            }
            renderActivity(act);
            renderSelection();
            await loadEvents();
        } catch (e) {
            if (my === st.gen) {
                $('perf-meta').innerHTML = `<span style="color: var(--danger);">조회 실패: ${esc(e.message)}</span>`;
                clearEvents('조회에 실패했습니다.');
            }
        } finally {
            if (my === st.gen && overlay) overlay.style.display = 'none';
        }
    }

    function currentWindow() {
        return st.sel || st.range;
    }

    async function loadEvents() {
        if (!st.range) return;
        const my = ++st.eventsGen;
        const dbId = st.dbId;
        const w = currentWindow();
        clearEvents('불러오는 중…');
        try {
            const d = await getJson(`/api/perf/events?db_id=${encodeURIComponent(dbId)}&from=${encodeURIComponent(w.from)}&to=${encodeURIComponent(w.to)}${st.filter}&source=${st.source}`);
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
            ? (store ? ` · <span style="color: var(--warning);">이 구간에 수집 데이터가 없습니다${d.storedFrom ? ` (수집 시작 ${esc(human(d.storedFrom))})` : ''}</span>`
                : ' · <span style="color: var(--warning);">이 구간에 샘플이 없습니다(ASH/AWR 보관 기간 밖일 수 있음)</span>')
            : '';
        // 수집 저장소가 비어 있는 구간(수집 시작 전·앱이 꺼져 있던 시간)만 원본 ASH/AWR로 볼 수 있게 - 운영 DB 조회라 버튼으로만
        const switchBtn = store
            ? (total === 0 ? ' <button type="button" class="perf-link-btn" id="perf-src-toggle" data-src="oracle">원본 DB(ASH/AWR)에서 조회</button>' : '')
            : ' <button type="button" class="perf-link-btn" id="perf-src-toggle" data-src="store">수집 저장소로 돌아가기</button>';
        $('perf-meta').innerHTML = `조회 구간 <b>${esc(human(d.from))} ~ ${esc(human(d.to))}</b> · 데이터 <b>${esc(sourceText(d.source))}</b>` +
            ` · ${d.step_minutes}분 막대 · CPU ${d.cpu_cores || '-'}` + pendingNote + empty + switchBtn;
        const tg = $('perf-src-toggle');
        if (tg) tg.addEventListener('click', () => { st.source = tg.dataset.src; search(); });
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

    // 드래그로 고른 구간을 차트 위에 옅게 표시
    const selectionBandPlugin = {
        id: 'perfSelectionBand',
        beforeDatasetsDraw(chart) {
            if (!st.sel) return;
            const x = chart.scales.x;
            const a = x.getPixelForValue(parseLocal(st.sel.from).getTime());
            const b = x.getPixelForValue(parseLocal(st.sel.to).getTime());
            const { top, bottom } = chart.chartArea;
            const ctx = chart.ctx;
            ctx.save();
            ctx.fillStyle = 'rgba(57,135,229,0.14)';
            ctx.fillRect(Math.min(a, b), top, Math.abs(b - a), bottom - top);
            ctx.restore();
        }
    };

    function renderSelection() {
        const w = currentWindow();
        $('perf-sel-text').textContent = st.sel ? `선택 구간: ${human(w.from)} ~ ${human(w.to)}` : `선택 구간: 전체 (${human(w.from)} ~ ${human(w.to)})`;
        $('perf-sel-reset').style.display = st.sel ? '' : 'none';
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
            if (e.target.id !== 'perf-activity-chart' || !activityChart || !st.range) return;
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
            const xs = activityChart.scales.x;
            const lo = Math.max(xs.getValueForPixel(Math.min(startX, endX)), parseLocal(st.range.from).getTime());
            const hi = Math.min(xs.getValueForPixel(Math.max(startX, endX)), parseLocal(st.range.to).getTime());
            if (!(hi > lo)) return;
            // 분 단위로 넓혀 잡는다(막대 하나 폭보다 좁게 골라도 최소 1분).
            const from = new Date(Math.floor(lo / 60000) * 60000);
            let to = new Date(Math.ceil(hi / 60000) * 60000);
            if (to.getTime() - from.getTime() < 60000) to = new Date(from.getTime() + 60000);
            st.sel = { from: fmtMinute(from), to: fmtMinute(to) };
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
        const w = currentWindow();
        const q = `db_id=${encodeURIComponent(st.dbId)}&event=${encodeURIComponent(event)}&from=${encodeURIComponent(w.from)}&to=${encodeURIComponent(w.to)}${st.filter}&source=${st.source}`;
        const popup = window.open('perf-event-sessions.html?' + q, 'dbagent_perf_event_sessions', dbagentPopupFeatures());
        if (popup) popup.focus();
    }

    // ------------------------------------------------------------------ 연결

    function reset() {
        st.gen++;
        st.eventsGen++;
        st.range = null;
        st.sel = null;
        st.activity = null;
        st.source = 'store';
        // 진행 중이던 조회는 gen이 바뀌어 finally에서 오버레이를 못 끄므로 여기서 끈다(db_now 실패 시 "조회 중" 화면이 남는 것 방지)
        const ov = $('history-loading-overlay');
        if (ov) ov.style.display = 'none';
        if (activityChart) { activityChart.destroy(); activityChart = null; }
        clearEvents('');
        const meta = $('perf-meta');
        if (meta) meta.textContent = '조회할 구간을 정하고 조회 버튼을 눌러 주세요. 최대 24시간, ASH 보관 범위 밖은 AWR로 보충합니다.';
        const sel = $('perf-sel-text');
        if (sel) sel.textContent = '선택 구간: 전체';
        const r = $('perf-sel-reset');
        if (r) r.style.display = 'none';
    }

    function init() {
        const btn = $('perf-search-btn');
        if (!btn) return;
        btn.addEventListener('click', search);
        $('history-start-time').addEventListener('change', onStartChange);
        $('history-end-time').addEventListener('change', validateRange);
        // 화면을 오래 열어 두면 max(=열 때의 DB 시각)가 낡아 달력에서 최근 시각을 못 고르므로, 입력칸에 들어올 때 갱신한다.
        $('history-start-time').addEventListener('focus', refreshInputLimits);
        $('history-end-time').addEventListener('focus', refreshInputLimits);
        document.querySelectorAll('[data-perf-quick]').forEach(b =>
            b.addEventListener('click', () => quickRange(parseInt(b.dataset.perfQuick, 10))));
        const prev = $('perf-range-prev');
        const next = $('perf-range-next');
        if (prev) prev.addEventListener('click', () => shiftRange(-1));
        if (next) next.addEventListener('click', () => shiftRange(1));
        $('perf-sel-reset').addEventListener('click', () => { st.sel = null; renderSelection(); loadEvents(); });
        attachBrush();
        attachEventsClick();
        // 메뉴에 들어올 때·DB가 바뀔 때 기본 구간을 DB 시각으로 다시 잡고, 다른 DB 결과는 지운다.
        setInterval(() => {
            if (typeof dbagentSectionVisible === 'function' && !dbagentSectionVisible('history')) return;
            if (st.range && st.dbId !== window.currentDbId) reset();
            ensureDefaultRange();
        }, 1000);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
