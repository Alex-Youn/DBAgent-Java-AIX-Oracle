// 성능 분석 "오브젝트·I/O" 하위 탭 (성능 분석 개편 Phase 5, 2026-10-11) - 설계문서 `성능분석 메뉴 설계.md` 5-2.
// perf-shell.js(PerfShell) 다음에 로드한다. 공통 조건 바의 조회를 구독해 /api/perf/obj_* (수집 저장소, 운영 DB 조회 없음)를 그린다.
// 수집(v$segstat 델타·v$filestat·v$log_history·v$tempseg_usage)은 기본 꺼짐이라 꺼져 있으면 안내만 보인다. 시각은 전부 DB 시각이다.
(function () {
    'use strict';

    const S = window.PerfShell;
    if (!S) return;
    const { $, esc, human, getJson } = S.utils;
    const enc = encodeURIComponent;

    const STAT_LABEL = { lr: 'Logical reads', pr: 'Physical reads', rlw: 'Row lock waits', itl: 'ITL waits', bbw: 'Buffer busy waits' };
    const STATS = ['lr', 'pr', 'rlw', 'itl', 'bbw'];

    const st = {
        sort: 'lr',
        key: null,      // 마지막으로 읽은 조건(같은 조건이면 탭 전환 때 다시 읽지 않는다)
        gen: 0,
        segGen: 0,      // 정렬만 바꿀 때 세그먼트 표만 다시 읽는 조회의 세대(전체 조회 결과를 버리지 않게 따로 둔다)
        chart: null
    };

    const q = (path, base, extra) => `/api/perf/${path}?db_id=${enc(S.getDbId())}&from=${enc(base.from)}&to=${enc(base.to)}${extra || ''}`;
    const keyOf = (base) => [S.getDbId(), base.from, base.to].join('|');

    function fmt(v) {
        if (v === null || v === undefined) return '-';
        return Number(v).toLocaleString();
    }

    function ms(v) {
        if (v === null || v === undefined) return '-';
        return v >= 100 ? v.toFixed(0) : v.toFixed(2);
    }

    function setNotice(collect) {
        const el = $('perf-obj-notice');
        let msg = '';
        if (collect && collect.enabled === false) msg = '오브젝트·I/O 수집이 꺼져 있습니다(환경설정 → 오브젝트·I/O 수집). 이미 쌓인 이력만 보입니다.';
        else if (collect && collect.state === 'error') msg = `최근 오브젝트·I/O 수집에 실패했습니다${collect.reason ? ' (' + collect.reason + ')' : ''}. 그 주기는 건너뛰었고 0으로 기록하지 않습니다.`;
        el.textContent = msg;
        el.style.display = msg ? '' : 'none';
    }

    function emptyText(c) {
        if (c && c.collectedFrom) return `이 구간에 수집된 데이터가 없습니다 (수집 시작 ${human(c.collectedFrom)})`;
        return '이 구간에 수집된 데이터가 없습니다 (수집을 켠 뒤 두 번째 주기부터 쌓입니다)';
    }

    // ------------------------------------------------------------------ 읽기

    async function load() {
        const base = S.getRange();
        if (!base || S.sub() !== 'obj') return;
        st.key = keyOf(base);
        const my = ++st.gen;
        const sortAtStart = st.sort;
        $('perf-obj-seg').innerHTML = '<tr><td colspan="7" class="perf-lk-empty">불러오는 중…</td></tr>';
        const [seg, files, temp, redo] = await Promise.all([
            getJson(q('obj_segments', base, '&sort=' + st.sort)).catch(e => ({ _err: e.message })),
            getJson(q('obj_files', base)).catch(e => ({ _err: e.message })),
            getJson(q('obj_temp_top', base)).catch(e => ({ _err: e.message })),
            getJson(q('obj_redo_switch', base)).catch(e => ({ _err: e.message }))
        ]);
        if (my !== st.gen) return; // 그사이 조건·DB가 바뀌었으면 늦게 온 응답은 버린다
        if ([seg, files, temp, redo].every(x => x && x._err)) st.key = null; // 네 조회가 모두 실패했으면 탭을 다시 열 때 다시 읽는다
        setNotice([seg, files, temp, redo].map(x => x && x.collect).find(Boolean));
        if (sortAtStart === st.sort) renderSegments(seg); // 읽는 사이 정렬이 바뀌었으면 그 정렬의 조회(loadSegmentsOnly)가 그린다
        renderFiles(files);
        renderTemp(temp);
        renderRedo(redo);
    }

    // 정렬만 바뀌면 세그먼트 표만 다시 읽는다
    async function loadSegmentsOnly() {
        const base = S.getRange();
        if (!base || S.sub() !== 'obj') return;
        const my = ++st.segGen;
        const gen = st.gen;
        const seg = await getJson(q('obj_segments', base, '&sort=' + st.sort)).catch(e => ({ _err: e.message }));
        if (my !== st.segGen || gen !== st.gen) return; // 정렬을 또 바꿨거나 조건·DB가 바뀌었으면 버린다
        renderSegments(seg);
    }

    // ------------------------------------------------------------------ 그리기

    function renderSegments(d) {
        const body = $('perf-obj-seg');
        document.querySelectorAll('#perf-obj-seg-head th[data-stat]').forEach(th => th.classList.toggle('perf-obj-sorted', th.dataset.stat === st.sort));
        if (d._err) { body.innerHTML = `<tr><td colspan="7" class="perf-lk-empty">판단 보류 - ${esc(d._err)}</td></tr>`; return; }
        if (!d.rows.length) {
            const msg = d.hasData ? `이 구간에 ${STAT_LABEL[st.sort]}이(가) 발생한 오브젝트가 수집되지 않았습니다` : emptyText(d.collect);
            body.innerHTML = `<tr><td colspan="7" class="perf-lk-empty">${esc(msg)}</td></tr>`;
            return;
        }
        const max = Math.max(1, ...d.rows.map(r => r[st.sort] || 0));
        body.innerHTML = d.rows.map((r, i) => {
            const cells = STATS.map(k => {
                const v = r[k] || 0;
                if (k !== st.sort) return `<td class="num">${v ? fmt(v) : '<span class="perf-hint">-</span>'}</td>`;
                // 정렬 기준 컬럼: 값에 비례하는 막대, 1위는 강조
                const w = Math.max(2, Math.round(v / max * 100));
                return `<td class="num perf-obj-hot${i === 0 ? ' top' : ''}"><div class="perf-obj-bar" style="width:${w}%"></div><span>${fmt(v)}</span></td>`;
            }).join('');
            return `<tr><td title="${esc(r.name)}">${esc(r.name)}</td><td>${esc(r.type || '')}</td>${cells}</tr>`;
        }).join('');
    }

    function renderFiles(d) {
        const body = $('perf-obj-files');
        $('perf-obj-files-note').textContent = '';
        if (d._err) { body.innerHTML = `<tr><td colspan="6" class="perf-lk-empty">판단 보류 - ${esc(d._err)}</td></tr>`; return; }
        if (!d.rows.length) { body.innerHTML = `<tr><td colspan="6" class="perf-lk-empty">${esc(emptyText(d.collect))}</td></tr>`; return; }
        $('perf-obj-files-note').textContent = `평균 읽기 ${d.slowMs}ms 이상은 강조 · Temp 파일은 TEMP로 표시`;
        body.innerHTML = d.rows.map(r => {
            const slow = r.slow ? ' class="perf-obj-slow"' : '';
            return `<tr><td>${esc(r.tablespace || '')}${r.kind === 'TEMP' ? ' <span class="perf-ld-badge new">TEMP</span>' : ''}</td>` +
                `<td title="${esc(r.file)}">${esc(r.file)}</td><td class="num">${fmt(r.reads)}</td>` +
                `<td class="num"><span${slow}>${ms(r.readMs)}</span></td><td class="num">${fmt(r.writes)}</td><td class="num">${ms(r.writeMs)}</td></tr>`;
        }).join('');
    }

    function renderTemp(d) {
        const body = $('perf-obj-temp');
        if (d._err) { body.innerHTML = `<tr><td colspan="4" class="perf-lk-empty">판단 보류 - ${esc(d._err)}</td></tr>`; return; }
        if (!d.rows.length) { body.innerHTML = '<tr><td colspan="4" class="perf-lk-empty">이 구간에 Temp를 1MB 이상 쓴 SQL이 수집되지 않았습니다</td></tr>'; return; }
        body.innerHTML = d.rows.map(r =>
            `<tr><td><a href="#" class="perf-sq-link" data-sql="${esc(r.sqlId)}">${esc(r.sqlId)}</a></td><td title="${esc(r.module || '')}">${esc(r.module || '')}</td>` +
            `<td class="num">${fmt(r.maxMb)}</td><td>${esc(human(r.time))}</td></tr>`).join('');
        // SQL_ID는 SQL 분석 탭의 상세 패널로 보낸다(설계 3-5)
        body.querySelectorAll('a[data-sql]').forEach(a => a.addEventListener('click', (ev) => {
            ev.preventDefault();
            if (/^[A-Za-z0-9]{1,13}$/.test(a.dataset.sql || '')) S.goSub('sql', `&view=cost&group=id&sql_id=${enc(a.dataset.sql)}`);
        }));
    }

    function destroyChart() {
        if (st.chart) { st.chart.destroy(); st.chart = null; }
    }

    function renderRedo(d) {
        const hint = $('perf-obj-redo-hint');
        if (d._err) { destroyChart(); hint.textContent = `판단 보류 - ${d._err}`; return; }
        if (!d.hasData) { destroyChart(); hint.textContent = emptyText(d.collect); return; }
        const labels = d.hours.map(h => human(h.time).slice(5));
        const vals = d.hours.map(h => h.switches);
        const over = S.cssVar('--danger');
        const norm = S.cssVar('--primary');
        const line = S.cssVar('--text-secondary');
        // 기준선(시간당 N회)을 넘는 막대는 다른 색 - 수집되지 않은 시간은 null(막대 없음)
        const datasets = [
            { type: 'bar', label: '스위치 횟수', data: vals, backgroundColor: vals.map(v => v !== null && v > d.warn ? over : norm), order: 2 },
            { type: 'line', label: `기준선 (${d.warn}회/시간)`, data: vals.map(() => d.warn), borderColor: line, borderDash: [4, 4], pointRadius: 0, borderWidth: 1, order: 1 }
        ];
        if (st.chart) {
            st.chart.data = { labels, datasets };
            st.chart.update('none');
        } else {
            st.chart = new Chart($('perf-obj-redo-chart'), {
                data: { labels, datasets },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    animation: { duration: 0 },
                    scales: {
                        x: { ticks: { color: chartLineColor(0.8), maxRotation: 0, autoSkip: true, maxTicksLimit: 12 }, grid: { display: false } },
                        y: { beginAtZero: true, ticks: { color: chartLineColor(0.8), precision: 0 }, grid: { color: chartLineColor(0.12) } }
                    },
                    plugins: {
                        legend: { position: 'top', align: 'end', labels: { color: chartLineColor(0.9), boxWidth: 12,
                            // 막대 색이 막대마다 달라 범례가 첫 막대(초과면 빨강)를 따라가므로 기본 색으로 고정한다
                            generateLabels: (chart) => Chart.defaults.plugins.legend.labels.generateLabels(chart).map(l => (l.datasetIndex === 0 ? Object.assign(l, { fillStyle: norm, strokeStyle: norm }) : l)) } },
                        tooltip: { callbacks: { label: (c) => c.parsed.y === null ? `${c.dataset.label}: 수집 안 됨` : `${c.dataset.label}: ${c.parsed.y}` } }
                    }
                }
            });
        }
        hint.textContent = d.hint || '';
    }

    // ------------------------------------------------------------------ 연결

    function onRoute(ev) {
        if (!(ev && ev.detail && ev.detail.sub === 'obj')) return;
        const base = S.getRange();
        if (base && keyOf(base) !== st.key) load();
    }

    function init() {
        if (!$('perf-obj-seg')) return;
        S.onSearch(() => { if (S.sub() === 'obj') load(); else { st.key = null; st.gen++; st.segGen++; } });
        S.onReset(() => {
            st.gen++;
            st.segGen++;
            st.key = null;
            destroyChart();
            setNotice(null);
            ['perf-obj-seg', 'perf-obj-files', 'perf-obj-temp'].forEach(id => { $(id).innerHTML = ''; });
            $('perf-obj-redo-hint').textContent = '';
            $('perf-obj-files-note').textContent = '';
        });
        $('perf-obj-sort').addEventListener('change', () => { st.sort = $('perf-obj-sort').value; loadSegmentsOnly(); });
        window.addEventListener('perf:route', onRoute);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
