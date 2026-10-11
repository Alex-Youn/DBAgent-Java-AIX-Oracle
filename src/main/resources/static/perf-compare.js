// 성능 분석 "구간 비교" 하위 탭 (성능 분석 개편 Phase 4, 2026-10-11) - 설계문서 `성능분석 메뉴 설계.md` Phase 4.
// perf-shell.js(PerfShell) 다음에 로드한다. B 구간 = 공통 조건 바의 조회 구간, A 구간 = 지난주/어제 같은 시간(DB 시각 기준) 또는 직접 지정.
// /api/perf/compare 는 수집 저장소만 읽는다(신규 수집 없음). 값은 시스템 부하·SQL 분석·Lock 분석 탭이 쓰는 같은 서비스에서 온다.
(function () {
    'use strict';

    const S = window.PerfShell;
    if (!S) return;
    const { $, esc, human, parseLocal, fmtMinute, getJson } = S.utils;
    const enc = encodeURIComponent;
    const MAX_MIN = 24 * 60;

    const st = {
        mode: 'week',     // week(지난주 같은 시간) | day(어제 같은 시간) | custom(직접 지정)
        aFrom: '', aTo: '',
        key: null,        // 마지막으로 읽은 조건(같은 조건이면 탭 전환 때 다시 읽지 않는다)
        gen: 0
    };

    const TYPE_LABEL = { SURGE: '실행 수 급증', PLAN: 'Plan 변경', ELAPSED: 'Elapsed 증가', NEW: 'B 구간 신규', LITERAL: '리터럴 SQL_ID 증가' };

    function fmt(v) {
        if (v === null || v === undefined) return '-';
        const a = Math.abs(v);
        if (a >= 1000) return Math.round(v).toLocaleString();
        if (a >= 100) return v.toFixed(1);
        if (a >= 1) return v.toFixed(2);
        return Number(v.toPrecision(3)).toString(); // 0.00243 같은 작은 값은 유효숫자 3자리(시스템 부하 탭과 같은 규칙)
    }

    // 날짜 계산은 setDate로 한다(밀리초를 빼면 서머타임이 있는 브라우저에서 하루가 어긋날 수 있다).
    function shiftDays(str, days) {
        const d = parseLocal(str);
        d.setDate(d.getDate() - days);
        return fmtMinute(d);
    }

    function setMsg(text) {
        const el = $('perf-cmp-msg');
        el.textContent = text || '';
        el.style.display = text ? '' : 'none';
    }

    // ------------------------------------------------------------------ A 구간 정하기

    function applyMode() {
        const base = S.getRange();
        document.querySelectorAll('[data-cmp-mode]').forEach(b => b.classList.toggle('active', b.dataset.cmpMode === st.mode));
        const custom = st.mode === 'custom';
        $('perf-cmp-a-from').readOnly = !custom;
        $('perf-cmp-a-to').readOnly = !custom;
        if (base) {
            $('perf-cmp-b-text').textContent = `${human(base.from)} ~ ${human(base.to)}`;
            if (!custom) {
                st.aFrom = shiftDays(base.from, st.mode === 'day' ? 1 : 7);
                st.aTo = shiftDays(base.to, st.mode === 'day' ? 1 : 7);
            }
        } else {
            $('perf-cmp-b-text').textContent = '-';
        }
        $('perf-cmp-a-from').value = st.aFrom || '';
        $('perf-cmp-a-to').value = st.aTo || '';
        // 새로고침·링크 공유 때 비교 조건이 복원되게 URL에 남긴다(기본 지난주 같은 시간이면 남기지 않는다)
        S.setUrlExtra('cmp', st.mode === 'week' ? '' : st.mode === 'day' ? '&mode=day'
            : `&mode=custom&a_from=${enc(st.aFrom)}&a_to=${enc(st.aTo)}`);
    }

    // A 구간 검증 - 문제가 없으면 null, 있으면 화면에 보일 문구
    function checkA() {
        if (!st.aFrom || !st.aTo) return 'A 구간의 시작·종료를 입력해 주세요.';
        const f = parseLocal(st.aFrom);
        const t = parseLocal(st.aTo);
        if (isNaN(f) || isNaN(t)) return 'A 구간 시각 형식이 올바르지 않습니다.';
        if (!(f < t)) return 'A 구간의 시작 시각이 종료 시각보다 앞서야 합니다.';
        if ((t - f) / 60000 > MAX_MIN) return 'A 구간은 최대 24시간까지 가능합니다. 구간을 줄여 주세요.';
        return null;
    }

    // ------------------------------------------------------------------ 읽기

    const keyOf = (base) => [S.getDbId(), base.from, base.to, st.aFrom, st.aTo].join('|');

    async function load() {
        const base = S.getRange();
        if (S.sub() !== 'cmp') return;
        if (!base) { setMsg('위 조건 바에서 B 구간을 조회해 주세요.'); return; }
        applyMode();
        const bad = checkA();
        if (bad) { st.gen++; st.key = null; clearBody(); setMsg(bad); return; } // 이전 A로 계산한 결과가 새 입력 옆에 남지 않게
        setMsg('');
        st.key = keyOf(base);
        const my = ++st.gen;
        $('perf-cmp-sum').innerHTML = '<div class="perf-lk-empty">불러오는 중…</div>';
        let d;
        try {
            d = await getJson(`/api/perf/compare?db_id=${enc(S.getDbId())}&a_from=${enc(st.aFrom)}&a_to=${enc(st.aTo)}&b_from=${enc(base.from)}&b_to=${enc(base.to)}`);
        } catch (e) {
            if (my !== st.gen) return;
            st.key = null; // 실패한 조건은 다음에 다시 읽는다
            clearBody();
            $('perf-cmp-sum').innerHTML = `<div class="perf-lk-empty">판단 보류 - ${esc(e.message)}</div>`;
            return;
        }
        if (my !== st.gen) return; // 그사이 조건·DB가 바뀌었으면 늦게 온 응답은 버린다
        render(d);
    }

    // 같은 틱에 여러 번 불려도(공통 조건 바 조회 + 주소 변경 이벤트) 한 번만 읽는다 - 서버 쿼리와 Oracle SQL 텍스트 보충 조회가 2배가 되지 않게
    let loadTimer = null;
    function scheduleLoad() {
        if (loadTimer) clearTimeout(loadTimer);
        loadTimer = setTimeout(() => { loadTimer = null; load(); }, 0);
    }

    // ------------------------------------------------------------------ 그리기

    function clearBody() {
        ['perf-cmp-cov', 'perf-cmp-warn', 'perf-cmp-sum', 'perf-cmp-metrics', 'perf-cmp-waits', 'perf-cmp-sql'].forEach(id => { $(id).innerHTML = ''; });
    }

    function covText(side, c) {
        const one = (k, name) => {
            if (!k) return '';
            if (k.state === 'none') return k.kind === 'sys' ? '' : `${name} 수집 이력 없음`;
            if (k.state === 'before') return `${name} 수집 시작 이전${k.collectedFrom ? ' (수집 시작 ' + esc(human(k.collectedFrom)) + ')' : ''}`;
            if (k.state === 'pending') return `${name} 아직 수집 전인 시각`;
            const pct = k.pct;
            const warn = pct < 80 ? ' perf-cmp-low' : '';
            const gaps = (k.gaps || []).map(g => `${human(g.from).slice(11)}~${human(g.to).slice(11)}`).join(', '); // esc는 아래 출력에서 한 번에
            return `${name} <b class="${warn.trim()}">${pct}%</b>${gaps ? ` <span class="perf-hint">(공백 ${esc(gaps)}${k.gapCount > (k.gaps || []).length ? ' 외' : ''})</span>` : ''}`;
        };
        const parts = [one(c.ash, '세션·Lock'), one(c.sys, '시스템 부하')].filter(Boolean);
        return `<span class="perf-cmp-tag ${side}">${side.toUpperCase()}</span> 수집 커버율 ${parts.length ? parts.join(' · ') : '-'}`;
    }

    function section(d, key, ok) {
        if (d.errors && d.errors[key]) return `판단 보류 - ${esc(d.errors[key])}`;
        return ok ? null : '';
    }

    function render(d) {
        // 수집 커버율 + 경고
        $('perf-cmp-cov').innerHTML = d.coverage
            ? `<div>${covText('a', d.coverage.a)}</div><div>${covText('b', d.coverage.b)}</div>`
            : `<div class="perf-hint">${esc((d.errors && d.errors.coverage) || '수집 커버율을 읽지 못했습니다.')}</div>`;
        // 경고는 한 상자에 줄로 모은다(커버율 경고가 최대 4건이라 따로 두면 세로 공간을 많이 차지한다)
        $('perf-cmp-warn').innerHTML = (d.warnings || []).length ? `<div class="perf-lk-notice">${d.warnings.map(esc).join('<br>')}</div>` : '';

        // 요약 카드 4개
        $('perf-cmp-sum').innerHTML = (d.summary || []).map(c =>
            `<div class="perf-ld-card${c.level === 'up' ? ' up' : c.level === 'down' ? ' down' : ''}">` +
            `<div class="l">${esc(c.label)}</div><div class="v perf-cmp-v">${esc(c.value)}</div><div class="d">${esc(c.detail)}</div></div>`).join('');

        renderMetrics(d);
        renderWaits(d);
        renderSql(d);
    }

    function changeCell(pct) {
        if (pct === null || pct === undefined) return '<span class="perf-hint">기준 없음</span>';
        return `${pct > 0 ? '+' : ''}${pct}%`;
    }

    function renderMetrics(d) {
        const body = $('perf-cmp-metrics');
        const err = section(d, 'metrics', !!d.metrics);
        if (err !== null) { body.innerHTML = `<tr><td colspan="5" class="perf-lk-empty">${err || '판단 보류'}</td></tr>`; return; }
        if (!d.metrics.hasA || !d.metrics.hasB) {
            body.innerHTML = '<tr><td colspan="5" class="perf-lk-empty">' + esc(!d.metrics.hasA && !d.metrics.hasB ? 'A·B 구간 모두' : !d.metrics.hasA ? 'A 구간에' : 'B 구간에') +
                ' 수집된 시스템 부하 데이터가 없습니다(환경설정의 시스템 부하 수집이 켜져 있어야 쌓입니다).</td></tr>';
            return;
        }
        body.innerHTML = d.metrics.rows.map(r => {
            const cls = r.level === 'up' ? ' up' : r.level === 'down' ? ' down' : '';
            const w = r.changePct === null ? 0 : Math.min(100, Math.abs(r.changePct) / 3);
            return `<tr><td>${esc(r.label)} <span class="perf-hint">${esc(r.unit)}</span></td><td class="num">${fmt(r.a)}</td><td class="num">${fmt(r.b)}</td>` +
                `<td class="num perf-cmp-chg${cls}">${changeCell(r.changePct)}</td>` +
                `<td>${r.changePct === null ? '' : `<div class="perf-cmp-bar2${cls}" style="width:${w}%"></div>`}</td></tr>`;
        }).join('');
    }

    function renderWaits(d) {
        const body = $('perf-cmp-waits');
        const err = section(d, 'waits', !!d.waits);
        if (err !== null) { body.innerHTML = `<tr><td colspan="4" class="perf-lk-empty">${err || '판단 보류'}</td></tr>`; return; }
        if (!d.waits.rows.length) { body.innerHTML = '<tr><td colspan="4" class="perf-lk-empty">A·B 구간에 수집된 대기 이벤트가 없습니다</td></tr>'; return; }
        body.innerHTML = d.waits.rows.map(r => {
            let chg;
            if (r.badge === 'new') chg = '<span class="perf-ld-badge new" title="A 구간에는 없던 이벤트입니다">신규</span>';
            else if (r.ratio === null || r.ratio === undefined) chg = '<span class="perf-hint" title="대기 횟수가 너무 적어 평균을 내지 않습니다">-</span>';
            else chg = r.badge === 'slow' ? `<span class="perf-ld-badge slow">×${r.ratio}</span>` : `<span class="perf-hint">×${r.ratio}</span>`;
            return `<tr><td title="${esc(r.event)}">${esc(r.event)}</td><td class="num">${fmt(r.aAvgMs)}</td><td class="num">${fmt(r.bAvgMs)}</td><td class="num">${chg}</td></tr>`;
        }).join('');
    }

    function renderSql(d) {
        const body = $('perf-cmp-sql');
        const err = section(d, 'sql', !!d.sql);
        if (err !== null) { body.innerHTML = `<tr><td colspan="6" class="perf-lk-empty">${err || '판단 보류'}</td></tr>`; return; }
        const s = d.sql;
        // exact=A·B 모두 1분 델타. 시간 롤업은 시간마다 상위 SQL만 담아 "A에 없다"를 "신규"로 단정할 수 없다
        $('perf-cmp-sql-note').textContent = s.exact ? '1분 델타 기준입니다.'
            : s.mixedSource ? 'A와 B가 서로 다른 소스(시간 롤업·1분 델타)라 "B 구간 신규"는 판단하지 않고 실행 수 급증은 근사 값입니다.'
                : '시간 롤업(상위 SQL) 기준이라 "B 구간 신규"는 판단하지 않고 시간 단위로 넓어진 근사 값입니다.';
        if (!s.hasA || !s.hasB) {
            body.innerHTML = '<tr><td colspan="6" class="perf-lk-empty">' + esc(!s.hasA && !s.hasB ? 'A·B 구간 모두' : !s.hasA ? 'A 구간에' : 'B 구간에') +
                ' SQL 통계가 없어 SQL 변화를 비교하지 못합니다(보관 기간이 지났거나 수집 전일 수 있습니다). Plan 변경·리터럴 항목만 아래에 보일 수 있습니다.</td></tr>' + rowsHtml(s.rows);
        } else {
            body.innerHTML = s.rows.length ? rowsHtml(s.rows) : '<tr><td colspan="6" class="perf-lk-empty">B 구간에서 눈에 띄게 달라진 SQL이 없습니다</td></tr>';
        }
        bindSqlLinks(body);
    }

    function rowsHtml(rows) {
        return rows.map(r => {
            const id = r.type === 'LITERAL'
                ? `<span class="perf-hint" title="FORCE_MATCHING_SIGNATURE">FMS ${esc(String(r.fms || '').slice(-8))}</span>`
                : `<a href="#" class="perf-sq-link" data-sql="${esc(r.sqlId)}">${esc(r.sqlId)}</a>`;
            const a = r.aValue === null || r.aValue === undefined ? '-' : fmt(r.aValue);
            const b = fmt(r.bValue);
            let chg;
            if (r.type === 'NEW') chg = '<span class="perf-ld-badge new">신규</span>';
            else if (r.ratio === null || r.ratio === undefined) chg = '<span class="perf-hint">-</span>';
            else chg = `<span class="perf-ld-badge slow">×${r.ratio}</span>`;
            const unit = ` <span class="perf-hint">${esc(r.unit)}</span>`;
            return `<tr><td>${id}</td><td title="${esc(r.text || '')}">${esc(r.text || '')}</td><td>${esc(TYPE_LABEL[r.type] || r.type)}</td>` +
                `<td class="num">${a}${r.aValue === null ? '' : unit}</td><td class="num">${b}${unit}</td><td class="num">${chg}</td></tr>`;
        }).join('');
    }

    // SQL_ID는 SQL 분석 탭의 상세 패널로 보낸다(B 구간 기준, 설계 3-5: 다른 탭의 SQL_ID 링크는 SQL 분석 탭에서 연다)
    function bindSqlLinks(root) {
        root.querySelectorAll('a[data-sql]').forEach(a => a.addEventListener('click', (ev) => {
            ev.preventDefault();
            if (/^[A-Za-z0-9]{1,13}$/.test(a.dataset.sql || '')) S.goSub('sql', `&view=cost&group=id&sql_id=${enc(a.dataset.sql)}`);
        }));
    }

    // ------------------------------------------------------------------ 연결

    function setMode(mode) {
        if (mode !== 'week' && mode !== 'day' && mode !== 'custom') mode = 'week';
        st.mode = mode;
        applyMode();
    }

    function onRoute(ev) {
        if (!(ev && ev.detail && ev.detail.sub === 'cmp')) return;
        let q;
        try { q = new URLSearchParams(ev.detail.query || ''); } catch (e) { q = new URLSearchParams(''); }
        const mode = q.get('mode');
        if (mode === 'custom' && q.get('a_from') && q.get('a_to')) { st.aFrom = q.get('a_from'); st.aTo = q.get('a_to'); setMode('custom'); }
        else if (mode === 'day') setMode('day');
        else if (!mode && q.get('from')) setMode('week'); // 새로고침·링크 공유로 들어온 기본 상태(조건은 있고 mode는 없음) - 탭 클릭 이동은 현재 선택을 유지
        const base = S.getRange();
        if (base && keyOf(base) !== st.key) scheduleLoad();
    }

    function init() {
        if (!$('perf-cmp-metrics')) return;
        S.onSearch(() => { if (S.sub() === 'cmp') scheduleLoad(); else st.key = null; });
        S.onReset(() => {
            st.gen++;
            st.key = null;
            st.mode = 'week';
            st.aFrom = '';
            st.aTo = '';
            S.setUrlExtra('cmp', '');
            clearBody();
            $('perf-cmp-sql-note').textContent = '';
            $('perf-cmp-b-text').textContent = '-';
            $('perf-cmp-a-from').value = '';
            $('perf-cmp-a-to').value = '';
            setMsg('');
        });
        document.querySelectorAll('[data-cmp-mode]').forEach(b => b.addEventListener('click', () => {
            setMode(b.dataset.cmpMode);
            if (st.mode !== 'custom') load();
        }));
        // 직접 지정: A 입력칸을 고치면 직접 지정으로 바뀌고, [비교]를 눌러야 읽는다
        ['perf-cmp-a-from', 'perf-cmp-a-to'].forEach(id => $(id).addEventListener('change', () => {
            if (st.mode !== 'custom') { st.mode = 'custom'; }
            st.aFrom = $('perf-cmp-a-from').value;
            st.aTo = $('perf-cmp-a-to').value;
            applyMode();
        }));
        $('perf-cmp-run').addEventListener('click', () => {
            if (st.mode === 'custom') { st.aFrom = $('perf-cmp-a-from').value; st.aTo = $('perf-cmp-a-to').value; }
            st.key = null;
            load();
        });
        window.addEventListener('perf:route', onRoute);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
