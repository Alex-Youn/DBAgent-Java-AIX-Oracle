// 성능 분석 "시스템 부하" 하위 탭 (성능 분석 개편 Phase 2, 2026-10-09) - 설계문서 `성능분석 메뉴 설계.md` 2장.
// perf-shell.js(PerfShell) 다음에 로드한다. 공통 조건 바의 조회를 구독해 /api/perf/load_* (수집 저장소, 운영 DB 조회 없음)를 그린다.
// 지표는 v$sysmetric 60초 값(초당), 지난주 같은 시간 대비(baseline=lastweek)는 서버가 계산한다. 시각은 전부 DB 시각이다.
(function () {
    'use strict';

    const S = window.PerfShell;
    if (!S) return;
    const { $, esc, human, parseLocal, getJson } = S.utils;
    const enc = encodeURIComponent;

    const st = {
        sel: 'dbtime',     // 차트에 보이는 지표 카드 key
        metrics: null,     // 마지막 load_metrics 응답
        key: null,         // 마지막으로 읽은 조건(같은 조건이면 탭 전환 때 다시 읽지 않는다)
        gen: 0,
        chart: null
    };

    const q = (path, base) => `/api/perf/${path}?db_id=${enc(S.getDbId())}&from=${enc(base.from)}&to=${enc(base.to)}&baseline=lastweek`;
    const keyOf = (base) => [S.getDbId(), base.from, base.to].join('|');

    // 숫자 표시: 큰 값은 천 단위, 작은 값은 소수 둘째 자리까지
    function fmt(v) {
        if (v === null || v === undefined) return '-';
        const a = Math.abs(v);
        if (a >= 1000) return Math.round(v).toLocaleString();
        if (a >= 100) return v.toFixed(1);
        if (a >= 1) return v.toFixed(2);
        return Number(v.toPrecision(3)).toString(); // 0.00243 같은 작은 값은 유효숫자 3자리(소수 자리 고정이면 낮은 부하에서 오차가 크다)
    }

    function setNotice(collect) {
        const el = $('perf-load-notice');
        let msg = '';
        if (collect && collect.enabled === false) msg = '시스템 부하 수집이 꺼져 있습니다(환경설정 → 시스템 부하 수집). 이미 쌓인 이력만 보입니다.';
        else if (collect && collect.state === 'unsupported') msg = 'PDB 접속에서는 지원하지 않음 - 판단 보류. 이 DB는 v$sysmetric·v$eventmetric이 비어 있어 시스템 부하를 수집할 수 없습니다(CDB 루트나 non-CDB로 등록된 DB에서만 수집합니다).';
        else if (collect && collect.state === 'error') msg = `최근 시스템 부하 수집에 실패했습니다${collect.reason ? ' (' + collect.reason + ')' : ''}. 그 구간은 수집 공백으로 표시될 수 있습니다.`;
        el.textContent = msg;
        el.style.display = msg ? '' : 'none';
    }

    // ------------------------------------------------------------------ 읽기

    async function load() {
        const base = S.getRange();
        if (!base || S.sub() !== 'load') return;
        st.key = keyOf(base);
        const my = ++st.gen;
        $('perf-load-cards').innerHTML = '<div class="perf-lk-empty">불러오는 중…</div>';
        const [m, w] = await Promise.all([
            getJson(q('load_metrics', base)).catch(e => ({ _err: e.message })),
            getJson(q('load_waits', base)).catch(e => ({ _err: e.message }))
        ]);
        if (my !== st.gen) return;
        setNotice((m && m.collect) || (w && w.collect));
        st.metrics = m._err ? null : m;
        renderCards(m);
        renderChart();
        renderWaits(w);
        renderHints(m, w);
    }

    // ------------------------------------------------------------------ 그리기

    function renderCards(m) {
        const el = $('perf-load-cards');
        if (m._err) { el.innerHTML = `<div class="perf-lk-empty">판단 보류 - ${esc(m._err)}</div>`; return; }
        if (!m.hasData) {
            el.innerHTML = '<div class="perf-lk-empty">이 구간에 수집된 시스템 부하 데이터가 없습니다' +
                (m.collectedFrom ? ` (수집 시작 ${esc(human(m.collectedFrom))})` : (m.collect && m.collect.state === 'unsupported' ? '' : ' (수집이 켜진 뒤 첫 수집부터 쌓입니다)')) + '</div>';
            return;
        }
        el.innerHTML = m.cards.map(c => {
            let delta;
            if (!m.hasBase || c.baseAvg === null || c.baseAvg === undefined) delta = '기준 없음';
            else if (c.changePct === null || c.changePct === undefined) delta = `지난주 ${fmt(c.baseAvg)}`;
            else delta = `지난주 대비 ${c.changePct > 0 ? '+' : ''}${c.changePct}%`;
            const cls = c.level === 'up' ? ' up' : c.level === 'down' ? ' down' : '';
            return `<button type="button" class="perf-ld-card${cls}${c.key === st.sel ? ' sel' : ''}" data-k="${esc(c.key)}">` +
                `<div class="l">${esc(c.label)}</div><div class="v">${fmt(c.avg)} <span class="u">${esc(c.unit)}</span></div><div class="d">${esc(delta)}</div></button>`;
        }).join('');
        el.querySelectorAll('.perf-ld-card').forEach(b => b.addEventListener('click', () => {
            st.sel = b.dataset.k;
            el.querySelectorAll('.perf-ld-card').forEach(x => x.classList.toggle('sel', x === b));
            renderChart();
        }));
    }

    function destroyChart() {
        if (st.chart) { st.chart.destroy(); st.chart = null; }
    }

    function renderChart() {
        const m = st.metrics;
        const note = $('perf-load-chart-note');
        if (!m || !m.hasData) { destroyChart(); note.textContent = ''; return; }
        const card = m.cards.find(c => c.key === st.sel) || m.cards[0];
        const s = m.series[card.key];
        // 지난주 데이터가 없는 지표는 점선·범례를 만들지 않는다 - 기존 차트에 남은 데이터셋도 새 data로 교체한다
        const hasBaseLine = !!(s.base && s.base.some(v => v !== null));
        $('perf-load-chart-title').textContent = `${card.label} (${card.unit})`;
        note.textContent = hasBaseLine ? '실선 = 선택 구간, 점선 = 지난주 같은 시간' : '지난주 같은 시간 데이터가 없어 기준선은 그리지 않습니다';
        const labels = m.times.map(t => parseLocal(t).getTime());
        const cur = S.cssVar('--primary');
        const old = S.cssVar('--text-secondary');
        const datasets = [{ label: '선택 구간', data: s.cur, borderColor: cur, backgroundColor: cur, borderWidth: 2, pointRadius: 0, spanGaps: false, tension: 0 }];
        if (hasBaseLine) datasets.push({ label: '지난주', data: s.base, borderColor: old, backgroundColor: old, borderWidth: 1.5, borderDash: [5, 4], pointRadius: 0, spanGaps: false, tension: 0 });
        const unit = labels.length > 180 ? 'hour' : 'minute';
        if (st.chart) {
            st.chart.data = { labels, datasets };
            st.chart.options.scales.x.time.unit = unit;
            st.chart.update('none');
            return;
        }
        st.chart = new Chart($('perf-load-chart'), {
            type: 'line',
            data: { labels, datasets },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: { duration: 0 },
                interaction: { mode: 'index', intersect: false },
                scales: {
                    x: { type: 'time',
                        time: { unit, tooltipFormat: 'MM-dd HH:mm', displayFormats: { minute: 'HH:mm', hour: 'MM-dd HH:mm' } },
                        ticks: { color: chartLineColor(0.8), maxRotation: 0, autoSkip: true }, grid: { color: chartLineColor(0.12) } },
                    y: { beginAtZero: true, ticks: { color: chartLineColor(0.8) }, grid: { color: chartLineColor(0.12) } }
                },
                plugins: {
                    legend: { position: 'top', labels: { color: chartLineColor(0.9), boxWidth: 12 } },
                    tooltip: { callbacks: { label: (c) => c.parsed.y === null ? `${c.dataset.label}: 수집 공백` : `${c.dataset.label}: ${fmt(c.parsed.y)}` } }
                }
            }
        });
    }

    function renderWaits(w) {
        const body = $('perf-load-waits');
        if (w._err) { body.innerHTML = `<tr><td colspan="7" class="perf-lk-empty">판단 보류 - ${esc(w._err)}</td></tr>`; return; }
        if (!w.rows.length) { body.innerHTML = '<tr><td colspan="7" class="perf-lk-empty">이 구간에 수집된 대기 이벤트가 없습니다</td></tr>'; return; }
        body.innerHTML = w.rows.map(r => {
            const badge = r.badge === 'slow' ? ' <span class="perf-ld-badge slow" title="평균 대기가 지난주보다 기준 이상 늘었습니다">지연</span>'
                : r.badge === 'new' ? ' <span class="perf-ld-badge new" title="지난주 같은 시간에는 없던 이벤트입니다">신규</span>' : '';
            const base = !w.hasBase ? '<span class="perf-hint">기준 없음</span>' : (r.baseAvgMs === null || r.baseAvgMs === undefined ? '-' : fmt(r.baseAvgMs));
            return `<tr><td title="${esc(r.event)}">${esc(r.event)}${badge}</td><td>${esc(r.waitClass || '')}</td>` +
                `<td class="num">${fmt(r.timeSec)}</td><td class="num">${r.pct === null ? '-' : r.pct + '%'}</td>` +
                `<td class="num">${Number(r.waits).toLocaleString()}</td><td class="num">${r.avgMs === null ? '<span class="perf-hint" title="대기 횟수가 너무 적어 평균을 내지 않습니다">-</span>' : fmt(r.avgMs)}</td>` +
                `<td class="num">${base}</td></tr>`;
        }).join('');
    }

    function renderHints(m, w) {
        const list = ((m && m.hints) || []).concat((w && w.hints) || []);
        const el = $('perf-load-hints');
        el.innerHTML = list.map(h => `<div class="perf-lk-hint">${esc(h.text)}</div>`).join('');
    }

    // ------------------------------------------------------------------ 연결

    function onRoute(ev) {
        if (!(ev && ev.detail && ev.detail.sub === 'load')) return;
        const base = S.getRange();
        if (base && keyOf(base) !== st.key) load();
    }

    function init() {
        if (!$('perf-load-waits')) return;
        S.onSearch(() => { if (S.sub() === 'load') load(); else st.key = null; });
        S.onReset(() => {
            st.gen++;
            st.key = null;
            st.metrics = null;
            $('perf-load-chart-title').textContent = '지표';
            destroyChart();
            // 이전 DB의 수집 상태 안내(PDB 판단 보류·수집 실패)가 새 DB 화면에 남지 않게 함께 지운다(2026-10-09 Phase 2 점검 수정)
            setNotice(null);
            $('perf-load-chart-note').textContent = '';
            $('perf-load-cards').innerHTML = '';
            $('perf-load-waits').innerHTML = '';
            $('perf-load-hints').innerHTML = '';
        });
        window.addEventListener('perf:route', onRoute);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
