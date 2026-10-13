// 성능 분석 "SQL 분석" 하위 탭 (성능 분석 개편 Phase 3, 2026-10-09) - 설계문서 `성능분석 메뉴 설계.md` 3장.
// perf-shell.js(PerfShell) 다음에 로드한다. 공통 조건 바의 조회를 구독해 /api/perf/sql_* (수집 저장소, 운영 DB 조회 없음 - 상세 패널의
// SQL 전문만 대상 DB 단건 조회)를 그린다. 보기 4종: 비용 Top / 실행 빈도 / 리터럴 SQL / Plan 변경. 시각은 전부 DB 시각이다.
(function () {
    'use strict';

    const S = window.PerfShell;
    if (!S) return;
    const { $, esc, human, parseLocal, getJson } = S.utils;
    const enc = encodeURIComponent;

    const VIEWS = ['cost', 'freq', 'literal', 'plan'];
    const st = {
        view: 'cost',
        group: 'id',       // id | fms (비용 Top·실행 빈도만)
        rows: [],          // 마지막 목록 행
        selId: null,       // 상세 패널에 열린 sql_id
        key: null,         // 마지막으로 읽은 조건(같은 조건이면 탭 전환 때 다시 읽지 않는다)
        gen: 0,
        detailGen: 0,
        chartGen: 0,       // 추이 차트 응답 세대(Plan 변경 보기에서 행을 빠르게 바꿔 누를 때 늦게 온 이전 응답이 덮지 않게)
        loadingGen: 0,     // 진행 중인 목록 조회의 gen(끝나면 0) - URL의 sql_id를 목록 조회가 끝난 뒤에 열기 위해
        chart: null,
        wantSql: null,     // URL(sql_id=)로 요청된 상세 - 목록을 읽은 뒤 연다
        wantDb: ''         // 그 URL이 가리킨 DB - 조회가 끝나 실제 DB를 알게 되면 다르면 버린다(페이지를 처음 열 때는 currentDbId를 아직 모른다)
    };

    // 진단 배지(설계 3-3): 코드 → [표시, 힌트]
    const BADGES = {
        SURGE: ['급증', '지난주 같은 시간대보다 실행 수가 크게 늘었습니다. 최근 배포에서 호출 로직이 바뀌었는지 확인하세요.'],
        NPLUS1: ['N+1 의심', 'SELECT 문이 행 1건 안팎을 돌려주며 매우 자주 실행됩니다. 반복문 안의 호출이 아닌지, 캐싱이 가능한지 확인하세요.'],
        NO_REUSE: ['커서 재사용 X', '실행마다 Parse가 일어납니다. statement cache·session_cached_cursors 설정과 PreparedStatement 재사용을 확인하세요.'],
        PLAN_CHANGE: ['Plan 변경', '구간 안에서 실행계획(plan_hash)이 둘 이상 나타났습니다. 실행계획 이력과 ms/exec 변화를 확인하세요.'],
        LITERAL: ['리터럴', '같은 구조의 SQL이 값만 달라 SQL_ID가 많이 생겼습니다. 바인드 변수 적용 대상입니다.']
    };

    const fmt = (v, d) => v === null || v === undefined ? '-' : (typeof v === 'number' ? (Math.abs(v) >= 1000 ? Math.round(v).toLocaleString() : (d === undefined ? (Math.abs(v) > 0 && Math.abs(v) < 1 ? String(Number(v.toPrecision(2))) : String(Math.round(v * 100) / 100)) : v.toFixed(d))) : String(v));
    const badgeHtml = (list) => (list || []).map(c => BADGES[c] ? `<span class="perf-sq-badge perf-sq-${c.toLowerCase()}" title="${esc(BADGES[c][1])}">${esc(BADGES[c][0])}</span>` : '').join(' ');
    const q = (path, base, extra) => `/api/perf/${path}?db_id=${enc(S.getDbId())}&from=${enc(base.from)}&to=${enc(base.to)}${extra || ''}`;
    const keyOf = (base) => [S.getDbId(), base.from, base.to, st.view, st.group].join('|');
    const sqlLink = (id) => `<a href="#" class="perf-sq-link" data-sql="${esc(id)}">${esc(id)}</a>`;

    function setNotice(d) {
        const el = $('perf-sql-notice');
        let msg = '';
        if (d && d._err) msg = '';
        else if (d && d.source === 'hour') {
            msg = `6시간을 넘는 구간(또는 1분 데이터 보관 기간 밖)이라 시간 단위 롤업을 읽습니다 - 시간마다 상위 SQL만 남아 있고 구간 경계는 시간 단위로 넓어집니다${d.rollupFrom ? ` (롤업 시작 ${human(d.rollupFrom)})` : ' (롤업이 아직 없습니다 - 수집 후 매시 7분에 만들어집니다)'}.`;
        } else if (d && d.source === 'delta' && d.deltaRetentionDays && d.deltaRetentionDays <= 3) {
            msg = `1분 단위 SQL 데이터는 ${d.deltaRetentionDays}일만 보관합니다(이후는 시간 롤업).`;
        }
        el.textContent = msg;
        el.style.display = msg ? '' : 'none';
    }

    // ------------------------------------------------------------------ 목록

    function destroyChart() {
        if (st.chart) { st.chart.destroy(); st.chart = null; }
    }

    function head(cols) {
        $('perf-sql-head').innerHTML = '<tr>' + cols.map(c => `<th${c[1] ? ' class="num"' : ''}>${c[0]}</th>`).join('') + '</tr>';
    }

    function textCell(r) {
        return r.text ? `<span title="${esc(r.text)}">${esc(r.text.length > 70 ? r.text.slice(0, 70) + '…' : r.text)}</span>` : '<span class="perf-hint">(텍스트 없음)</span>';
    }

    function idCell(r) {
        return sqlLink(r.sqlId) + (r.sqlIdCount ? ` <span class="perf-sq-cnt" title="같은 FORCE_MATCHING_SIGNATURE의 SQL_ID 수">×${r.sqlIdCount}</span>` : '');
    }

    function weekCell(r, hasBase) {
        if (!hasBase) return '<span class="perf-hint">기준 없음</span>';
        if (r.changePct === null || r.changePct === undefined) return r.lastWeekExecs === null || r.lastWeekExecs === undefined ? '<span class="perf-hint">기준 없음</span>' : `지난주 ${fmt(r.lastWeekExecs)}`;
        const cls = (r.badges || []).indexOf('SURGE') >= 0 ? 'perf-warn' : ''; // 급증 기준은 서버가 설정(diag-sql-surge-pct)으로 판정한다
        return `<span class="${cls}">${r.changePct > 0 ? '+' : ''}${r.changePct}%</span>`;
    }

    function renderTop(d) {
        const cost = st.view === 'cost';
        head(cost
            ? [['SQL_ID'], ['SQL'], ['Elapsed(s)', 1], ['비중', 1], ['CPU(s)', 1], ['Execs', 1], ['ms/exec', 1], ['Gets/exec', 1], ['진단']]
            : [['SQL_ID'], ['SQL'], ['Execs', 1], ['Exec/s', 1], ['비중', 1], ['ms/exec', 1], ['Rows/exec', 1], ['Parse/Exec', 1], ['지난주 대비'], ['진단']]);
        st.rows = d.rows || [];
        $('perf-sql-count').textContent = `(${d.sqlCount}개 SQL 중 ${st.rows.length}개 · 총 ${fmt(d.totalExecs)}회 · Elapsed ${fmt(d.totalElapsedSec)}초)`;
        if (!st.rows.length) {
            $('perf-sql-rows').innerHTML = `<tr><td colspan="10" class="perf-lk-empty">이 구간에 수집된 SQL 통계가 없습니다${d.source === 'hour' ? '(롤업이 아직 없을 수 있습니다)' : ''}</td></tr>`;
            return;
        }
        // 상위 5개 행에는 위 추이 차트의 선과 같은 색 점을 붙인다 - 범례를 읽지 않아도 선과 행이 짝지어진다
        const cs = colors();
        $('perf-sql-rows').innerHTML = st.rows.map((r, i) => {
            const sel = r.sqlId === st.selId ? ' sel' : '';
            // FMS 보기의 행은 시그니처 합계인데 차트 선은 대표 SQL_ID 한 건이라, 점을 붙이면 둘이 같은 값처럼 보인다(2026-10-13 코드 점검) - 붙이지 않는다
            const dot = i < TREND_TOP && st.group !== 'fms' ? `<span class="perf-sq-dot" style="background:${esc(cs[i % cs.length])}" title="위 추이 차트의 ${i + 1}위 선"></span>` : '';
            const tail = cost
                ? `<td class="num">${fmt(r.elapsedSec)}</td><td class="num">${r.pct === null ? '-' : r.pct + '%'}</td><td class="num">${fmt(r.cpuSec)}</td><td class="num">${fmt(r.execs)}</td><td class="num">${fmt(r.msPerExec)}</td><td class="num">${fmt(r.getsPerExec)}</td>`
                : `<td class="num">${fmt(r.execs)}</td><td class="num">${fmt(r.execPerSec)}</td><td class="num">${r.execPct === null ? '-' : r.execPct + '%'}</td><td class="num">${fmt(r.msPerExec)}</td><td class="num">${fmt(r.rowsPerExec)}</td><td class="num">${fmt(r.parsePerExec)}</td><td>${weekCell(r, d.hasBase)}</td>`;
            return `<tr class="perf-lk-row${sel}" data-id="${esc(r.sqlId)}"><td>${dot}${idCell(r)}</td><td>${textCell(r)}</td>${tail}<td>${badgeHtml(r.badges)}</td></tr>`;
        }).join('');
    }

    function renderLiteral(d) {
        head([['FORCE_MATCHING_SIGNATURE'], ['대표 SQL'], ['SQL_ID 수', 1], ['Execs', 1], ['하드파싱/분', 1], ['지난주 SQL_ID 수', 1], ['진단']]);
        st.rows = (d.rows || []).map(r => Object.assign({ sqlId: r.sampleSqlId }, r));
        $('perf-sql-count').textContent = `(${st.rows.length}개 시그니처 · 시간 단위 집계)`;
        if (!st.rows.length) {
            $('perf-sql-rows').innerHTML = `<tr><td colspan="7" class="perf-lk-empty">${d.newCursorEnabled ? '이 구간에 리터럴로 보이는 SQL이 없습니다(새 SQL_ID가 두 개 이상인 시그니처 없음)' : '새 커서 수집이 꺼져 있습니다(환경설정 → SQL 분석: 새 커서 저장 상한 = 0)'}</td></tr>`;
            return;
        }
        $('perf-sql-rows').innerHTML = st.rows.map(r => {
            const base = r.lastWeekSqlIdCount === null || r.lastWeekSqlIdCount === undefined ? '<span class="perf-hint">기준 없음</span>' : fmt(r.lastWeekSqlIdCount);
            return `<tr class="perf-lk-row${r.sqlId === st.selId ? ' sel' : ''}" data-id="${esc(r.sqlId || '')}"><td title="${esc(r.fms)}">${esc(String(r.fms).slice(0, 14))}…</td><td>${textCell(r)}</td>` +
                `<td class="num">${fmt(r.sqlIdCount)}</td><td class="num">${fmt(r.execs)}</td><td class="num">${fmt(r.hardParsePerMin)}</td><td class="num">${base}</td><td>${r.literal ? badgeHtml(['LITERAL']) : ''}</td></tr>`;
        }).join('');
    }

    function renderPlan(d) {
        head([['SQL_ID'], ['SQL'], ['변경 시각'], ['이전 → 현재 Plan'], ['이전 ms/exec', 1], ['현재 ms/exec', 1], ['악화 배율', 1]]);
        st.rows = d.rows || [];
        $('perf-sql-count').textContent = `(${st.rows.length}건)`;
        if (!st.rows.length) {
            $('perf-sql-rows').innerHTML = `<tr><td colspan="7" class="perf-lk-empty">이 구간에 감지된 Plan 변경이 없습니다 <span class="perf-hint">${esc(d.note || '')}</span></td></tr>`;
            return;
        }
        $('perf-sql-rows').innerHTML = st.rows.map(r =>
            `<tr class="perf-lk-row${r.sqlId === st.selId ? ' sel' : ''}" data-id="${esc(r.sqlId)}"><td>${sqlLink(r.sqlId)}</td><td>${textCell(r)}</td><td>${esc(human(r.changedTime))}</td>` +
            `<td>${esc(r.oldPlan)} → ${esc(r.newPlan)}</td><td class="num">${fmt(r.oldMs)}</td><td class="num">${fmt(r.newMs)}</td><td class="num"><b>${r.ratio === null ? '-' : '×' + r.ratio}</b></td></tr>`).join('');
    }

    function bindRows() {
        document.querySelectorAll('#perf-sql-rows tr[data-id]').forEach(tr => tr.addEventListener('click', (e) => {
            e.preventDefault();
            select(tr.dataset.id);
        }));
    }

    // ------------------------------------------------------------------ 추이 차트

    const TREND_TOP = 5; // 비용 Top·실행 빈도 차트에 그리는 SQL 수(색 5개와 같다)

    function colors() {
        return ['--primary', '--warning', '--success', '--danger', '--text-secondary'].map(v => S.cssVar(v));
    }

    // 범례 라벨: SQL_ID만 쓰면 뜻 없는 13자리 글자라 깨진 글자처럼 보이고 어떤 SQL인지 알 수 없다(2026-10-13 오케스트레이터 지적) -
    // 목록 순위와 SQL 앞부분을 붙인다. 순위는 비용 Top·실행 빈도 보기에서만(Plan 변경 보기는 선택한 SQL 하나).
    function seriesLabel(id, i, ranked) {
        const r = st.rows.find(x => x.sqlId === id);
        const t = r && r.text ? r.text.replace(/\s+/g, ' ').trim() : '';
        return (ranked ? (i + 1) + '위 · ' : '') + id + (t ? ' · ' + (t.length > 26 ? t.slice(0, 26) + '…' : t) : '');
    }

    async function loadChart(base, my, ids, metric, title) {
        const box = $('perf-sql-chart-box');
        const cg = ++st.chartGen;
        if (!ids.length) { destroyChart(); box.style.display = 'none'; return; }
        box.style.display = '';
        try {
            const d = await getJson(q('sql_trend', base, `&sql_ids=${enc(ids.join(','))}&metric=${metric}`));
            if (my !== st.gen || cg !== st.chartGen) return;
            const labels = d.times.map(t => parseLocal(t).getTime());
            const cs = colors();
            const fms = st.group === 'fms' && metric !== 'ms'; // FMS 보기: 선은 시그니처의 대표 SQL 한 건이라 목록 순위를 붙이지 않는다
            const ranked = metric !== 'ms' && !fms;
            const datasets = ids.map((id, i) => ({
                label: seriesLabel(id, i, ranked), data: d.series[id] || [], borderColor: cs[i % cs.length], backgroundColor: cs[i % cs.length],
                borderWidth: 2, spanGaps: false,
                // 앞뒤가 비어 있는 외톨이 점(한 분에만 실행된 SQL)은 선이 안 그려지므로 점으로 보인다
                pointRadius: (c) => { const v = c.dataset.data, i = c.dataIndex; return (i === 0 || v[i - 1] == null) && (i === v.length - 1 || v[i + 1] == null) ? 3 : 0; }, tension: 0, stepped: metric === 'ms' ? 'before' : false
            }));
            $('perf-sql-chart-title').textContent = title + (d.stepMinutes === 60 ? ' (시간 단위)' : '');
            // 선이 끊긴 이유를 화면에서 알 수 있게 한다 - 1분 델타는 실행이 없던 분을 서버가 0으로 채워 주므로 끊긴 곳은 수집이 없던 시간이다
            $('perf-sql-chart-hint').textContent = d.zeroFill ? '선이 끊긴 구간은 수집되지 않은 시간입니다(실행이 없던 분은 0)'
                : d.stepMinutes === 60 ? '시간 롤업은 시간마다 상위 SQL만 남습니다 - 빈 구간이 실행 없음을 뜻하지는 않습니다'
                : '실행이 없던 분은 비어 있습니다';
            if (fms) $('perf-sql-chart-hint').textContent += ' · FMS 보기의 선은 시그니처 합계가 아니라 대표 SQL 한 건입니다';
            // 시간 롤업(stepMinutes 60)은 점이 25개 이하라도 6시간을 넘는 구간이다 - 분 단위 눈금(HH:mm만)으로 그리면 날짜가 안 보인다
            const unit = (labels.length > 180 || d.stepMinutes === 60) ? 'hour' : 'minute';
            if (st.chart) {
                st.chart.data = { labels, datasets };
                st.chart.options.scales.x.time.unit = unit;
                st.chart.update('none');
                return;
            }
            st.chart = new Chart($('perf-sql-chart'), {
                type: 'line',
                data: { labels, datasets },
                options: {
                    responsive: true, maintainAspectRatio: false, animation: { duration: 0 },
                    interaction: { mode: 'index', intersect: false },
                    scales: {
                        x: { type: 'time', time: { unit, tooltipFormat: 'MM-dd HH:mm', displayFormats: { minute: 'HH:mm', hour: 'MM-dd HH:mm' } },
                            ticks: { color: chartLineColor(0.8), maxRotation: 0, autoSkip: true }, grid: { color: chartLineColor(0.12) } },
                        y: { beginAtZero: true, ticks: { color: chartLineColor(0.8) }, grid: { color: chartLineColor(0.12) } }
                    },
                    plugins: { legend: { position: 'top', labels: { color: chartLineColor(0.9), boxWidth: 12 } } }
                }
            });
        } catch (e) {
            if (my === st.gen && cg === st.chartGen) { destroyChart(); $('perf-sql-chart-title').textContent = '추이 조회 실패: ' + e.message; $('perf-sql-chart-hint').textContent = ''; }
        }
    }

    // ------------------------------------------------------------------ 읽기

    async function load() {
        const base = S.getRange();
        if (!base || S.sub() !== 'sql') return;
        st.key = keyOf(base);
        const my = ++st.gen;
        st.loadingGen = my;
        // URL이 가리킨 DB가 지금 조회하는 DB와 다르면 그 SQL_ID는 열지 않는다(공유 링크·새로고침, QA D2)
        if (st.wantSql && st.wantDb && st.wantDb !== S.getDbId()) { st.wantSql = null; st.wantDb = ''; }
        $('perf-sql-rows').innerHTML = '<tr><td class="perf-lk-empty">불러오는 중…</td></tr>';
        let d;
        try {
            if (st.view === 'literal') d = await getJson(q('sql_literal', base, '&min_count=2'));
            else if (st.view === 'plan') d = await getJson(q('sql_plan_changes', base));
            else d = await getJson(q('sql_top', base, `&view=${st.view}&group=${st.group}&limit=50`));
        } catch (e) {
            if (my !== st.gen) return;
            d = { _err: e.message };
        }
        if (my !== st.gen) return;
        st.loadingGen = 0;
        setNotice(d);
        if (d._err) {
            head([['SQL_ID']]);
            $('perf-sql-rows').innerHTML = `<tr><td class="perf-lk-empty">판단 보류 - ${esc(d._err)}</td></tr>`;
            $('perf-sql-count').textContent = '';
            destroyChart();
            $('perf-sql-chart-box').style.display = 'none';
            // 이전 조건의 상세가 남아 지금 구간의 값처럼 보이지 않게 비운다(늦게 오는 상세 응답도 버린다)
            st.detailGen++;
            $('perf-sql-detail').innerHTML = '<div class="perf-lk-empty">목록 조회에 실패해 상세를 표시하지 않습니다</div>';
            return;
        }
        if (st.view === 'literal') renderLiteral(d);
        else if (st.view === 'plan') renderPlan(d);
        else renderTop(d);
        bindRows();
        // 상단 차트: 비용 Top = Top 5 Elapsed(초/분), 실행 빈도 = Top 5 Exec/s, Plan 변경 = 선택한 SQL의 ms/exec(계단), 리터럴 = 숨김
        if (st.view === 'cost' || st.view === 'freq') {
            const ids = st.rows.slice(0, TREND_TOP).map(r => r.sqlId);
            loadChart(base, my, ids, st.view === 'cost' ? 'elapsed' : 'exec', st.view === 'cost' ? 'Top 5 Elapsed 추이 (초/분)' : 'Top 5 실행 빈도 추이 (회/초)');
        } else if (st.view === 'plan') {
            // 아래에서 select()할 SQL이 있으면 select()가 그 SQL의 추이를 읽는다 - 여기서도 읽으면 같은 sql_trend가 두 번 나간다
            const willSelect = !!st.wantSql || (!!st.selId && st.rows.some(r => r.sqlId === st.selId));
            if (!willSelect) loadChart(base, my, st.rows.length ? [st.rows[0].sqlId] : [], 'ms', 'ms/exec 추이 (Plan 변경 SQL)');
        } else {
            destroyChart();
            $('perf-sql-chart-box').style.display = 'none';
        }
        if (st.wantSql) {
            const id = st.wantSql;
            st.wantSql = null;
            select(id);
        } else if (st.selId && st.rows.some(r => r.sqlId === st.selId)) {
            select(st.selId);
        } else {
            st.selId = null;
            $('perf-sql-detail').innerHTML = '<div class="perf-lk-empty">목록에서 SQL을 선택하면 상세가 표시됩니다</div>';
        }
    }

    // ------------------------------------------------------------------ 상세 패널 (설계 3-5)

    function select(id) {
        if (!id) return;
        st.selId = id;
        document.querySelectorAll('#perf-sql-rows tr[data-id]').forEach(tr => tr.classList.toggle('sel', tr.dataset.id === id));
        S.setUrlExtra('sql', `&view=${st.view}&group=${st.group}&sql_id=${enc(id)}`);
        showDetail(id);
        if (st.view === 'plan') loadChart(S.getRange(), st.gen, [id], 'ms', 'ms/exec 추이 (Plan 변경 SQL)');
    }

    async function showDetail(id) {
        const box = $('perf-sql-detail');
        const base = S.getRange();
        if (!base) return;
        const my = ++st.detailGen;
        box.innerHTML = '<div class="perf-lk-empty">불러오는 중…</div>';
        let d;
        try {
            d = await getJson(q('sql_detail', base, `&sql_id=${enc(id)}`));
        } catch (e) {
            if (my === st.detailGen) box.innerHTML = `<div class="perf-lk-empty">판단 보류 - ${esc(e.message)}</div>`;
            return;
        }
        if (my !== st.detailGen) return;
        const kpi = d.kpi || {};
        const card = (l, v) => `<div class="perf-lk-kpi"><div class="l">${l}</div><div class="v">${fmt(v)}</div></div>`;
        const plans = (d.plans || []).map(p => `<tr><td>${esc(p.planHash)}${p.current ? ' <span class="perf-sq-badge perf-sq-cur">현재</span>' : ''}</td><td>${esc(human(p.firstTime))} ~ ${esc(human(p.lastTime))}</td><td class="num">${fmt(p.execs)}</td><td class="num">${fmt(p.msPerExec)}</td></tr>`).join('');
        const hints = (d.badges || []).map(c => BADGES[c] ? `<div class="perf-lk-hint"><b>${esc(BADGES[c][0])}</b> - ${esc(BADGES[c][1])}</div>` : '').join('');
        const textNote = d.textSource === 'saved' ? ' <span class="perf-hint">(저장된 앞부분 - 공유 풀에서 밀려난 SQL은 전문을 볼 수 없습니다)</span>' : '';
        box.innerHTML = `<div class="perf-lk-detail-head"><b>${esc(d.sqlId)}</b>${d.sqlIdCount && d.sqlIdCount > 1 ? ` <span class="perf-sq-cnt" title="같은 FORCE_MATCHING_SIGNATURE">SQL_ID ×${d.sqlIdCount}</span>` : ''} ${badgeHtml(d.badges)}</div>` +
            `<div class="perf-hint" style="margin:8px 0 4px">SQL 전문${textNote}</div>` +
            `<pre class="perf-sq-text">${d.text ? esc(d.text) : '(SQL 텍스트를 찾을 수 없습니다 - 공유 풀에서 밀려났거나 수집 전 SQL입니다)'}</pre>` +
            (d.found ? `<div class="perf-sq-kpis">${card('Execs', kpi.execs)}${card('Exec/s', kpi.execPerSec)}${card('ms/exec', kpi.msPerExec)}${card('Gets/exec', kpi.getsPerExec)}${card('Rows/exec', kpi.rowsPerExec)}${card('Parse/Exec', kpi.parsePerExec)}</div>` +
                `<div class="perf-hint" style="margin:10px 0 4px">실행계획 이력</div>` +
                `<table class="perf-lk-table"><thead><tr><th>plan_hash_value</th><th>사용 구간</th><th class="num">Execs</th><th class="num">ms/exec</th></tr></thead><tbody>${plans || '<tr><td colspan="4" class="perf-lk-empty">없음</td></tr>'}</tbody></table>` + hints
                : '<div class="perf-lk-empty">이 구간에는 이 SQL의 수집된 통계가 없습니다</div>');
    }

    // ------------------------------------------------------------------ 연결

    function setView(v, silent) {
        if (VIEWS.indexOf(v) < 0) return false;
        const changed = v !== st.view;
        st.view = v;
        document.querySelectorAll('#perf-sql-view button').forEach(b => b.classList.toggle('active', b.dataset.v === v));
        $('perf-sql-group').style.display = (v === 'cost' || v === 'freq') ? '' : 'none';
        if (!silent) S.setUrlExtra('sql', `&view=${v}&group=${st.group}${st.selId ? '&sql_id=' + enc(st.selId) : ''}`);
        return changed;
    }

    function setGroup(g) {
        if (g !== 'id' && g !== 'fms') return false;
        const changed = g !== st.group;
        st.group = g;
        document.querySelectorAll('#perf-sql-group button').forEach(b => b.classList.toggle('active', b.dataset.g === g));
        return changed;
    }

    function onRoute(ev) {
        if (!(ev && ev.detail && ev.detail.sub === 'sql')) return;
        let changed = false;
        try {
            const p = new URLSearchParams(ev.detail.query || '');
            if (p.get('view')) changed = setView(p.get('view'), true) || changed;
            if (p.get('group')) changed = setGroup(p.get('group')) || changed;
            const id = p.get('sql_id');
            // URL의 DB가 현재 DB와 다르면 공통 조건 바가 조건을 복원하지 않는다 - 다른 DB의 SQL_ID를 현재 DB에서 열지 않는다
            const urlDb = p.get('db_id');
            const otherDb = !!(urlDb && window.currentDbId && urlDb !== window.currentDbId);
            if (id && /^[A-Za-z0-9]{1,13}$/.test(id) && !otherDb) { st.wantSql = id; st.wantDb = urlDb || ''; }
            // 새로고침해도 보기·그룹·sql_id가 URL에 남도록 공통 조건 바에 다시 알린다(예전에는 클릭했을 때만 남아 다음 새로고침에서 기본값으로 돌아갔다)
            S.setUrlExtra('sql', `&view=${st.view}&group=${st.group}${st.wantSql ? '&sql_id=' + enc(st.wantSql) : (st.selId ? '&sql_id=' + enc(st.selId) : '')}`);
        } catch (e) { /* 잘못된 쿼리는 무시 */ }
        const base = S.getRange();
        // (2026-10-09 점검 수정) 다른 탭의 SQL_ID 링크로 오면 공통 조건 바의 perf:route가 먼저 조회(load)를 시작해 키가 같아진다.
        // 그때 바로 select하면 목록 응답이 늦게 와서 "선택한 SQL이 목록(상위 50)에 없음"으로 상세 패널을 지워 버린다 -
        // 조회가 진행 중이면 wantSql을 남겨 두고 load가 끝난 뒤 연다.
        if (base && (changed || keyOf(base) !== st.key)) load();
        else if (base && st.wantSql && st.loadingGen !== st.gen) { const id = st.wantSql; st.wantSql = null; select(id); }
    }

    function init() {
        if (!$('perf-sql-rows')) return;
        S.onSearch(() => { if (S.sub() === 'sql') load(); else st.key = null; });
        S.onReset(() => {
            st.gen++;
            st.detailGen++;
            st.key = null;
            st.selId = null;
            st.rows = [];
            st.wantSql = null;   // 이전 DB에서 열려던 SQL_ID를 새 DB에서 열지 않는다
            st.wantDb = '';
            S.setUrlExtra('sql', ''); // 해시에 이전 DB의 sql_id가 남지 않게
            st.loadingGen = 0;
            st.chartGen++;
            destroyChart();
            $('perf-sql-chart-box').style.display = 'none'; // 빈 캔버스 상자가 남지 않게
            $('perf-sql-rows').innerHTML = '';
            $('perf-sql-count').textContent = '';
            $('perf-sql-notice').style.display = 'none';
            $('perf-sql-detail').innerHTML = '<div class="perf-lk-empty">조회 후 표시됩니다</div>';
        });
        window.addEventListener('perf:route', onRoute);
        document.querySelectorAll('#perf-sql-view button').forEach(b => b.addEventListener('click', () => {
            if (setView(b.dataset.v) && S.getRange()) load();
        }));
        document.querySelectorAll('#perf-sql-group button').forEach(b => b.addEventListener('click', () => {
            if (setGroup(b.dataset.g) && S.getRange()) {
                S.setUrlExtra('sql', `&view=${st.view}&group=${st.group}${st.selId ? '&sql_id=' + enc(st.selId) : ''}`);
                load();
            }
        }));
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
