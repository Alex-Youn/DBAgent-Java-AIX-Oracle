// 새 대시보드 ① KPI · ② 평균 활성 세션(대기 클래스별) · ④ 진단 배너 - 설계문서 `대시보드 UI 개선 설계.md` 5장 ①②④,
// 전체 작업순서 F5(2026-09-26). nd-dashboard.js(공통 엔진) 다음에 로드한다.
//
// - ①②④는 /api/metric_history의 8분류(ash_wc_*) + ash_cpu_cores 하나로 그린다(추가 조회 없음, 60초 주기).
//   AAS_TOTAL = 그 분의 8분류 합계 → ① KPI 숫자와 ② 누적 높이가 항상 같다.
// - 시각은 DB 시각: sampledAt(앱 시각) + dbClockOffsetMs. 샘플은 끝난 1분을 대표하므로 (t − 30초)가 속한 분에 둔다.
// - 값이 없는 분은 0으로 잇지 않고 끊어 그린다. 맨 앞 공백은 "수집 이전".
// - ① 5·6번째 칸(활성 세션·메모리)은 'lock' 이벤트(리프레쉬 주기)로 갱신.
// - 선택 구간은 'selection' 이벤트로 ④와 ⑤⑥⑦(F7)에 알린다: {from, to}(DB 시각 ms), auto.
// - 툴팁은 body 바로 아래에 붙인다(.app-container zoom 안에 두면 위치가 어긋난 전례, 설계 2.4).
(function () {
    'use strict';
    const ND = window.ndDashboard;
    if (!ND) return;

    const CLS = [
        { k: 'cpu', n: 'CPU' }, { k: 'user_io', n: 'User I/O' }, { k: 'system_io', n: 'System I/O' },
        { k: 'concurrency', n: 'Concurrency' }, { k: 'application', n: 'Application' }, { k: 'commit', n: 'Commit' },
        { k: 'network', n: 'Network' }, { k: 'other', n: 'Other' }
    ];
    const NC = CLS.length;
    const METRICS = CLS.map(c => 'ash_wc_' + c.k).concat(['ash_cpu_cores']);
    const RANGE_MIN = { '15m': 15, '1h': 60, '3h': 180, '24h': 1440 };
    const TICK_EVERY = { '15m': 5, '1h': 10, '3h': 30, '24h': 120 };
    const RANGE_LABEL = { '15m': '최근 15분', '1h': '최근 1시간', '3h': '최근 3시간', '24h': '최근 24시간' };
    const ACTION = {
        cpu: 'CPU 사용이 대부분입니다. 논리 읽기(buffer gets)가 많은 SQL의 실행계획부터 확인하세요.',
        user_io: '디스크 읽기 대기입니다. Full Scan, 인덱스 누락, 대량 배치 SQL을 확인하세요.',
        system_io: 'LGWR·DBWR 같은 백그라운드 쓰기 대기입니다. 스토리지 쓰기 지연을 확인하세요.',
        concurrency: '같은 블록이나 라이브러리 캐시에 대한 경합입니다. 핫 블록과 하드 파싱을 확인하세요.',
        application: '잠금(enq: TX 행 잠금 / enq: TM 테이블 잠금) 대기입니다. 블로킹 세션과 커밋되지 않은 트랜잭션을 확인하고, TX/TM 구분은 ③ Lock 카드와 ⑦ Top 대기 이벤트에서 확인하세요.',
        commit: 'log file sync 대기입니다. 커밋 빈도와 redo 로그 디스크 쓰기 속도를 확인하세요.',
        network: 'SQL*Net 대기입니다. 대량 fetch나 클라이언트·네트워크 지연을 확인하세요.',
        other: 'Configuration, Cluster, Scheduler 등 기타 대기의 합입니다. ⑦ Top 대기 이벤트에서 어떤 이벤트인지 확인하세요.'
    };
    // ⑤⑥⑦·드로어(nd-top.js)가 같은 분류 이름·조치 문구를 쓰도록 공유
    ND.classes = CLS;
    ND.classActions = ACTION;
    const DEFAULT_SESSION_THRESHOLDS = [60, 70, 80, 90, 100];
    const DELAY_MS = 3 * 60000;

    const st = {
        rangeKey: '1h',
        rows: [],          // [{t: DB 시각 분 시작 ms, v: [8] | null}]
        cores: null,
        lastSample: null,  // DB 시각 ms
        nowDb: null,
        hidden: new Set(),
        sel: null,         // [a, b] rows 인덱스
        userSel: false,
        hover: null,
        showTable: false,
        loaded: false
    };
    let G = null;          // 마지막 그리기의 좌표 정보
    let drag = null;

    const $ = (id) => document.getElementById(id);
    const color = (i) => `var(--nd-c-${CLS[i].k})`;
    const f1 = (v) => (Math.round(v * 10) / 10).toFixed(1);
    const pad = (n) => String(n).padStart(2, '0');
    const hm = (t) => { const d = new Date(t); return pad(d.getHours()) + ':' + pad(d.getMinutes()); };
    const esc = (s) => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const total = (v) => v ? v.reduce((a, b) => a + b, 0) : null;
    const visTotal = (v) => v ? v.reduce((a, b, c) => a + (st.hidden.has(c) ? 0 : b), 0) : null;

    // ================================================================== DOM 준비

    function ensureDom() {
        const body = $('nd-aas-body');
        if (!body || body.dataset.ready) return;
        body.dataset.ready = '1';
        body.innerHTML = '';
        body.classList.add('nd-aas');
        const card = $('nd-aas-card');
        const title = card.querySelector('.nd-card-title');
        const delay = document.createElement('span');
        delay.className = 'nd-badge-delay';
        delay.id = 'nd-aas-delay';
        delay.hidden = true;
        title.appendChild(delay);

        const legend = document.createElement('div');
        legend.className = 'nd-legend';
        legend.id = 'nd-aas-legend';
        const plot = document.createElement('div');
        plot.className = 'nd-plot';
        plot.id = 'nd-aas-plot';
        plot.tabIndex = 0;
        plot.setAttribute('role', 'img');
        plot.setAttribute('aria-label', '대기 클래스별 평균 활성 세션 추이 - 클릭: 5분 선택, 드래그: 범위 선택, 좌우 화살표 이동, Enter 선택');
        plot.innerHTML = '<svg id="nd-aas-svg"></svg>';
        const table = document.createElement('div');
        table.className = 'nd-aas-table';
        table.id = 'nd-aas-table';
        table.hidden = true;
        const foot = document.createElement('div');
        foot.className = 'nd-chart-foot';
        foot.innerHTML = '<span class="nd-src">Oracle 대기 클래스 기준 · v$active_session_history · 60초 수집 · 1분 평균 · 접속 인스턴스 기준</span>' +
            '<span class="nd-foot-btns"><button type="button" class="nd-link-btn" id="nd-aas-clear" hidden>선택 해제</button>' +
            '<button type="button" class="nd-link-btn" id="nd-aas-table-btn">표로 보기</button></span>';
        body.append(legend, plot, table, foot);

        const tip = document.createElement('div');
        tip.className = 'nd-tip';
        tip.id = 'nd-aas-tip';
        tip.hidden = true;
        document.body.appendChild(tip);

        renderLegend();
        bindPlot(plot);
        $('nd-aas-clear').addEventListener('click', () => { st.userSel = false; autoSelect(); render(); });
        $('nd-aas-table-btn').addEventListener('click', () => {
            st.showTable = !st.showTable;
            $('nd-aas-table-btn').textContent = st.showTable ? '차트로 보기' : '표로 보기';
            render();
        });
        if (typeof ResizeObserver === 'function') {
            new ResizeObserver(() => { if (st.loaded) renderChart(); }).observe(plot);
        } else {
            window.addEventListener('resize', () => { if (st.loaded) renderChart(); });
        }
    }

    function renderLegend() {
        const box = $('nd-aas-legend');
        if (!box) return;
        box.innerHTML = '';
        CLS.forEach((c, i) => {
            const b = document.createElement('button');
            b.type = 'button';
            b.className = 'nd-legend-item';
            b.setAttribute('aria-pressed', st.hidden.has(i) ? 'false' : 'true');
            b.innerHTML = `<span class="nd-sw" style="background:${color(i)}"></span>${esc(c.n)}`;
            b.addEventListener('click', () => {
                if (st.hidden.has(i)) st.hidden.delete(i);
                else if (st.hidden.size < NC - 1) st.hidden.add(i); // 전부 숨길 수는 없다
                renderLegend();
                render();
            });
            box.appendChild(b);
        });
    }

    // ================================================================== 데이터

    async function load(ctx) {
        ensureDom();
        const rangeKey = ctx.range;
        const d = await ctx.fetchJson('/api/metric_history', { db_id: ctx.dbId, range: rangeKey, metrics: METRICS.join(',') });
        if (!d || ctx.isStale()) return;
        const offset = typeof d.dbClockOffsetMs === 'number' ? d.dbClockOffsetMs : 0;
        ND.state.dbClockOffsetMs = offset; // ③ Lock 차트 등 앱 시각 값을 DB 시각으로 표시할 때 공유
        const nowDb = Date.now() + offset;
        const n = RANGE_MIN[rangeKey] || 60;
        const endBucket = Math.floor((nowDb - 30000) / 60000) * 60000;
        const startBucket = endBucket - (n - 1) * 60000;
        const rows = [];
        for (let i = 0; i < n; i++) rows.push({ t: startBucket + i * 60000, v: null });
        let lastSample = null;
        CLS.forEach((c, ci) => {
            (d['ash_wc_' + c.k] || []).forEach(p => {
                const t = p.sampledAt + offset;
                const idx = Math.round((Math.floor((t - 30000) / 60000) * 60000 - startBucket) / 60000);
                if (idx < 0 || idx >= n) return;
                if (!rows[idx].v) rows[idx].v = new Array(NC).fill(0);
                rows[idx].v[ci] = p.value;
                if (lastSample === null || t > lastSample) lastSample = t;
            });
        });
        const coreSeries = d.ash_cpu_cores || [];
        const cores = coreSeries.length ? coreSeries[coreSeries.length - 1].value : null;

        if (rangeKey !== st.rangeKey) { st.userSel = false; st.sel = null; }
        st.rangeKey = rangeKey;
        st.rows = rows;
        st.cores = cores;
        st.lastSample = lastSample;
        st.nowDb = nowDb;
        st.loaded = true;
        if (!st.userSel || !st.sel) autoSelect();
        render();
    }
    ND.register('slow', 'aas', load);

    ND.onReset(() => {
        st.rows = [];
        st.cores = null;
        st.lastSample = null;
        st.sel = null;
        st.userSel = false;
        st.hover = null;
        st.loaded = false;
        drag = null;
        const tip = $('nd-aas-tip');
        if (tip) tip.hidden = true;
        ['nd-kpi-current', 'nd-kpi-avg', 'nd-kpi-max', 'nd-kpi-over', 'nd-kpi-sessions', 'nd-kpi-memory'].forEach(id => { const e = $(id); if (e) e.textContent = '—'; });
        ['nd-kpi-current-sub', 'nd-kpi-avg-sub', 'nd-kpi-max-sub', 'nd-kpi-over-sub'].forEach(id => { const e = $(id); if (e) e.innerHTML = '&nbsp;'; });
        ['nd-kpi-sessions-bar', 'nd-kpi-memory-bar'].forEach(id => { const e = $(id); if (e) e.innerHTML = ''; });
        const svg = $('nd-aas-svg');
        if (svg) svg.innerHTML = '';
        const plot = $('nd-aas-plot');
        if (plot) plot.classList.add('nd-loading');
        const banner = $('nd-banner');
        if (banner) banner.innerHTML = '<div class="nd-skel nd-skel-line" style="width: 55%;"></div>';
        const delay = $('nd-aas-delay');
        if (delay) delay.hidden = true;
    });

    // ================================================================== 선택 구간

    /** 구간 내 10분 평균 부하가 가장 큰 구간(값이 없는 분은 0으로 셈). */
    function autoSelect() {
        const n = st.rows.length;
        if (!n || !st.rows.some(r => r.v)) { st.sel = null; emitSelection(); return; }
        const L = Math.min(10, n) - 1;
        let best = 0, bv = -1;
        for (let s = 0; s + L < n; s++) {
            let v = 0;
            for (let i = s; i <= s + L; i++) v += total(st.rows[i].v) || 0;
            if (v > bv) { bv = v; best = s; }
        }
        st.sel = [best, best + L];
        emitSelection();
    }

    function selectAround(i) {
        const n = st.rows.length;
        let a = Math.max(0, i - 2), b = Math.min(n - 1, a + 4);
        a = Math.max(0, b - 4);
        st.sel = [a, b];
        st.userSel = true;
        emitSelection();
        render();
    }

    function emitSelection() {
        if (!st.sel || !st.rows.length) { ND.emit('selection', null); return; }
        ND.emit('selection', {
            from: st.rows[st.sel[0]].t,
            to: st.rows[st.sel[1]].t + 60000,
            auto: !st.userSel
        });
    }

    // ================================================================== 그리기

    function render() {
        if (!st.loaded) return;
        const plot = $('nd-aas-plot');
        if (plot) plot.classList.remove('nd-loading');
        renderKpis();
        renderDelay();
        const table = $('nd-aas-table');
        if (st.showTable) {
            plot.hidden = true;
            table.hidden = false;
            renderTable();
        } else {
            table.hidden = true;
            plot.hidden = false;
            renderChart();
        }
        $('nd-aas-clear').hidden = !st.userSel;
        renderBanner();
    }

    function renderDelay() {
        const badge = $('nd-aas-delay');
        if (!badge) return;
        const late = st.lastSample === null || (st.nowDb - st.lastSample) > DELAY_MS;
        badge.hidden = !late;
        badge.textContent = st.lastSample === null ? '수집 이전' : `수집 지연 · 마지막 ${hm(st.lastSample)}`;
    }

    function renderKpis() {
        const vals = st.rows.filter(r => r.v).map(r => ({ t: r.t, tot: total(r.v) }));
        const cores = st.cores;
        const set = (id, html) => { const e = $(id); if (e) e.innerHTML = html; };
        if (!vals.length) {
            ['nd-kpi-current', 'nd-kpi-avg', 'nd-kpi-max', 'nd-kpi-over'].forEach(id => set(id, '—'));
            set('nd-kpi-current-sub', '수집 이전');
            set('nd-kpi-avg-sub', esc(RANGE_LABEL[st.rangeKey]));
            set('nd-kpi-max-sub', '&nbsp;');
            set('nd-kpi-over-sub', '&nbsp;');
            return;
        }
        const now = vals[vals.length - 1];
        const avg = vals.reduce((a, b) => a + b.tot, 0) / vals.length;
        let mx = vals[0];
        vals.forEach(v => { if (v.tot > mx.tot) mx = v; });
        set('nd-kpi-current', f1(now.tot));
        set('nd-kpi-current-sub', `${hm(now.t)}${cores ? ' · 부하율 ' + (now.tot / cores).toFixed(2) : ''}`);
        set('nd-kpi-avg', f1(avg));
        set('nd-kpi-avg-sub', esc(RANGE_LABEL[st.rangeKey]));
        set('nd-kpi-max', f1(mx.tot));
        set('nd-kpi-max-sub', `${hm(mx.t)}${cores ? ' · 코어의 ' + Math.round(mx.tot / cores * 100) + '%' : ''}`);
        if (!cores) {
            set('nd-kpi-over', '—');
            set('nd-kpi-over-sub', '코어 수 확인 전');
            return;
        }
        const over = vals.filter(v => v.tot > cores).length;
        set('nd-kpi-over', `${over}<small>분</small>`);
        const lvl = over === 0 ? { c: 'good', s: '●', t: '코어 이내 유지' } : over < 10 ? { c: 'serious', s: '◆', t: `코어 ${cores}개를 넘은 시간` } : { c: 'crit', s: '■', t: `코어 ${cores}개를 넘은 시간` };
        set('nd-kpi-over-sub', `<span class="nd-lvl nd-lvl-${lvl.c}">${lvl.s} ${esc(lvl.t)}</span>`);
    }

    function nice(max) {
        if (!(max > 0)) max = 1;
        const raw = max / 5, p = Math.pow(10, Math.floor(Math.log10(raw)));
        const step = [1, 2, 2.5, 5, 10].map(m => m * p).find(s => s >= raw);
        return { step, top: Math.ceil(max / step) * step };
    }

    function renderChart() {
        const plot = $('nd-aas-plot'), svg = $('nd-aas-svg');
        if (!plot || !svg || plot.hidden) return;
        const W = Math.max(260, plot.clientWidth), H = Math.max(80, plot.clientHeight);
        const ml = 40, mr = W < 520 ? 12 : 72, mt = 10, mb = 22;
        const pw = W - ml - mr, ph = H - mt - mb;
        const rows = st.rows, n = rows.length;
        if (!n) { svg.innerHTML = ''; return; }
        const cores = st.cores;
        const tots = rows.map(r => visTotal(r.v) || 0);
        const { step, top } = nice(Math.max(Math.max.apply(null, tots), cores || 0) * 1.08);
        const x = (i) => ml + (n === 1 ? pw / 2 : (i / (n - 1)) * pw);
        const y = (v) => mt + ph - (v / top) * ph;
        G = { W, H, ml, mr, mt, pw, ph, n, x };
        svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
        svg.setAttribute('width', W);
        svg.setAttribute('height', H);
        let s = '';
        for (let v = 0; v <= top + 1e-9; v += step) {
            const yy = y(v).toFixed(1);
            s += `<line class="${v === 0 ? 'nd-base' : 'nd-gridl'}" x1="${ml}" x2="${ml + pw}" y1="${yy}" y2="${yy}"/>` +
                `<text class="nd-tick" x="${ml - 6}" y="${+yy + 3.5}" text-anchor="end">${+v.toFixed(2)}</text>`;
        }
        const every = TICK_EVERY[st.rangeKey] || 10;
        rows.forEach((r, i) => {
            const m = Math.round(r.t / 60000);
            if (m % every === 0) s += `<text class="nd-tick" x="${x(i).toFixed(1)}" y="${H - 5}" text-anchor="middle">${hm(r.t)}</text>`;
        });
        // 수집 이전(맨 앞 공백 3분 이상)
        let lead = 0;
        while (lead < n && !rows[lead].v) lead++;
        if (lead >= 3 && lead < n) {
            const xe = x(lead - 1);
            s += `<rect class="nd-nodata" x="${ml}" y="${mt}" width="${Math.max(0, xe - ml).toFixed(1)}" height="${ph}"/>` +
                `<text class="nd-nodata-t" x="${((ml + xe) / 2).toFixed(1)}" y="${(mt + ph / 2).toFixed(1)}" text-anchor="middle">수집 이전</text>`;
        }
        // 누적 영역 - 값이 있는 연속 구간마다 따로 그려 공백을 0으로 잇지 않는다
        const segs = [];
        let cur = null;
        rows.forEach((r, i) => {
            if (r.v) { if (!cur) { cur = [i, i]; segs.push(cur); } else cur[1] = i; } else cur = null;
        });
        const half = n > 1 ? pw / (n - 1) / 2 : 4;
        segs.forEach(([a, b]) => {
            const acc = new Array(b - a + 1).fill(0);
            const xs = [];
            for (let i = a; i <= b; i++) xs.push(x(i));
            if (a === b) { xs[0] = x(a) - half * 0.8; xs.push(x(a) + half * 0.8); acc.push(0); }
            for (let c = 0; c < NC; c++) {
                if (st.hidden.has(c)) continue;
                const lo = acc.slice();
                const hi = acc.map((v, k) => v + rows[Math.min(a + k, b)].v[c]);
                let d = 'M' + hi.map((v, k) => `${xs[k].toFixed(1)},${y(v).toFixed(1)}`).join('L');
                for (let k = lo.length - 1; k >= 0; k--) d += `L${xs[k].toFixed(1)},${y(lo[k]).toFixed(1)}`;
                s += `<path class="nd-area" d="${d}Z" style="fill:${color(c)}"/>`;
                for (let k = 0; k < acc.length; k++) acc[k] = hi[k];
            }
        });
        if (cores) {
            const cy = y(cores).toFixed(1);
            s += `<line class="nd-corel" x1="${ml}" x2="${ml + pw}" y1="${cy}" y2="${cy}"/>`;
            s += mr > 36
                ? `<text class="nd-corelbl" x="${ml + pw + 6}" y="${+cy - 2}">CPU 코어</text><text class="nd-corelbl" x="${ml + pw + 6}" y="${+cy + 11}">${cores}</text>`
                : `<text class="nd-corelbl" x="${ml + pw - 4}" y="${+cy - 5}" text-anchor="end">CPU 코어 ${cores}</text>`;
        }
        if (st.sel) {
            const xa = x(st.sel[0]), xb = x(st.sel[1]);
            s += `<rect class="nd-selr" x="${xa.toFixed(1)}" y="${mt}" width="${Math.max(2, xb - xa).toFixed(1)}" height="${ph}"/>`;
        }
        if (st.hover !== null && st.hover < n) {
            const hx = x(st.hover).toFixed(1);
            s += `<line class="nd-xh" x1="${hx}" x2="${hx}" y1="${mt}" y2="${mt + ph}"/>`;
        }
        svg.innerHTML = s;
    }

    function showTip(i, clientX, clientY) {
        const tip = $('nd-aas-tip');
        if (!tip) return;
        if (i === null || !st.rows[i]) { tip.hidden = true; return; }
        const r = st.rows[i];
        let h = `<div class="nd-tip-t"><span>${hm(r.t)}</span><span>AAS</span></div>`;
        if (!r.v) {
            h += '<div class="nd-tip-empty">수집값 없음</div>';
        } else {
            h += '<div class="nd-tip-grid">';
            for (let c = NC - 1; c >= 0; c--) {
                if (st.hidden.has(c)) continue;
                h += `<div class="nd-tip-r"><span class="nd-sw" style="background:${color(c)}"></span><b>${f1(r.v[c])}</b><span>${esc(CLS[c].n)}</span></div>`;
            }
            h += '</div>';
            const tv = visTotal(r.v);
            h += `<div class="nd-tip-tot"><span>합계</span><b>${f1(tv)}${st.cores ? ' · 코어의 ' + Math.round(tv / st.cores * 100) + '%' : ''}</b></div>`;
        }
        tip.innerHTML = h;
        tip.hidden = false;
        const tw = tip.offsetWidth, th = tip.offsetHeight;
        let left = clientX + 14;
        if (left + tw > window.innerWidth - 4) left = Math.max(4, clientX - 14 - tw);
        let top = clientY - th / 2;
        top = Math.max(4, Math.min(window.innerHeight - th - 4, top));
        tip.style.left = left + 'px';
        tip.style.top = top + 'px';
    }

    function renderTable() {
        const box = $('nd-aas-table');
        let h = '<table class="nd-table"><thead><tr><th>시각</th>';
        CLS.forEach(c => { h += `<th>${esc(c.n)}</th>`; });
        h += '<th>합계</th></tr></thead><tbody>';
        for (let i = st.rows.length - 1; i >= 0; i--) {
            const r = st.rows[i];
            if (!r.v) continue;
            const tot = total(r.v);
            const over = st.cores && tot > st.cores;
            h += `<tr class="${over ? 'nd-over' : ''}"><td>${hm(r.t)}</td>`;
            r.v.forEach(v => { h += `<td>${f1(v)}</td>`; });
            h += `<td><b>${f1(tot)}</b></td></tr>`;
        }
        h += '</tbody></table>';
        box.innerHTML = h;
    }

    function renderBanner() {
        const box = $('nd-banner');
        if (!box) return;
        if (!st.sel) {
            box.innerHTML = '<div class="nd-banner-title">선택 구간 없음</div><div class="nd-banner-msg nd-muted">조회 구간에 수집된 값이 없습니다.</div>';
            return;
        }
        const [a, b] = st.sel;
        const sums = new Array(NC).fill(0);
        let count = 0;
        for (let i = a; i <= b; i++) {
            const v = st.rows[i].v;
            if (!v) continue;
            count++;
            v.forEach((x, c) => { sums[c] += x; });
        }
        const mins = b - a + 1;
        const avg = count ? sums.reduce((p, q) => p + q, 0) / count : 0;
        const label = st.userSel ? '선택 구간' : '자동 선택 구간 (구간 내 최대 부하)';
        const title = `${label} ${hm(st.rows[a].t)} – ${hm(st.rows[b].t + 60000)} (${mins}분) · 평균 AAS ${f1(avg)}`;
        let msg;
        if (!count || avg <= 0) {
            msg = `<span class="nd-muted">선택 구간 평균 AAS 0 — 부하 없음</span>`;
        } else {
            let top = 0;
            sums.forEach((v, c) => { if (v > sums[top]) top = c; });
            const pct = Math.round(sums[top] / sums.reduce((p, q) => p + q, 0) * 100);
            msg = `<span class="nd-sw" style="background:${color(top)}"></span><b>${esc(CLS[top].n)} ${pct}%</b>가 가장 큽니다. ${esc(ACTION[CLS[top].k])}`;
            if (st.cores && avg > st.cores) {
                msg += ` <b class="nd-crit-t">구간 평균이 CPU 코어 ${st.cores}개를 넘어 세션이 대기열에 쌓이고 있습니다.</b>`;
            }
        }
        box.innerHTML = `<div class="nd-banner-title">${esc(title)}</div><div class="nd-banner-msg">${msg}</div>`;
    }

    // ================================================================== 포인터·키보드

    function idxAt(ev) {
        const svg = $('nd-aas-svg');
        if (!G || !svg) return null;
        const r = svg.getBoundingClientRect();
        const sx = (ev.clientX - r.left) * (G.W / (r.width || G.W)); // zoom과 무관하게 화면 비율로 환산(설계 2.4)
        const i = G.n === 1 ? 0 : Math.round((sx - G.ml) / G.pw * (G.n - 1));
        return Math.max(0, Math.min(G.n - 1, i));
    }

    function bindPlot(plot) {
        plot.addEventListener('pointerdown', (e) => {
            if (!G || !st.loaded) return;
            drag = { a: idxAt(e), moved: false };
            if (plot.setPointerCapture) plot.setPointerCapture(e.pointerId);
        });
        plot.addEventListener('pointermove', (e) => {
            if (!G || !st.loaded) return;
            const i = idxAt(e);
            st.hover = i;
            if (drag && Math.abs(i - drag.a) >= 1) {
                drag.moved = true;
                st.sel = [Math.min(drag.a, i), Math.max(drag.a, i)];
                st.userSel = true;
            }
            renderChart();
            showTip(i, e.clientX, e.clientY);
        });
        plot.addEventListener('pointerup', (e) => {
            if (!drag) return;
            const i = idxAt(e);
            if (!drag.moved || Math.abs(i - drag.a) < 2) selectAround(drag.a);
            else { st.sel = [Math.min(drag.a, i), Math.max(drag.a, i)]; st.userSel = true; emitSelection(); render(); }
            drag = null;
        });
        plot.addEventListener('pointerleave', () => {
            if (drag) return;
            st.hover = null;
            renderChart();
            showTip(null);
        });
        plot.addEventListener('keydown', (e) => {
            if (!G || !st.loaded) return;
            let h = st.hover === null ? st.rows.length - 1 : st.hover;
            if (e.key === 'ArrowLeft') h = Math.max(0, h - 1);
            else if (e.key === 'ArrowRight') h = Math.min(st.rows.length - 1, h + 1);
            else if (e.key === 'Enter' || e.key === ' ') { selectAround(h); e.preventDefault(); return; }
            else return;
            e.preventDefault();
            st.hover = h;
            renderChart();
            const svg = $('nd-aas-svg').getBoundingClientRect();
            const px = svg.left + G.x(h) * (svg.width / G.W);
            showTip(h, px, svg.top + svg.height / 2);
        });
        plot.addEventListener('blur', () => { st.hover = null; renderChart(); showTip(null); });
    }

    // ================================================================== ① 활성 세션·메모리 (리프레쉬 주기)

    function miniBar(id, ratio, colorValue) {
        const box = $(id);
        if (!box) return;
        const on = Math.max(0, Math.min(10, Math.round(ratio * 10)));
        let h = '';
        for (let i = 0; i < 10; i++) h += `<span${i < on ? ` style="background:${colorValue}"` : ''}></span>`;
        box.innerHTML = h;
    }

    function sessionColor(count, t) {
        if (count >= t[4]) return '#6e1f1f';
        if (count >= t[3]) return '#9e2d2d';
        if (count >= t[2]) return 'var(--nd-crit)';
        if (count >= t[1]) return 'var(--nd-warn)';
        if (count >= t[0]) return '#d9a72f';
        return 'var(--nd-good)';
    }

    ND.on('lock', (d) => {
        const sEl = $('nd-kpi-sessions'), mEl = $('nd-kpi-memory');
        if (!d || !d.ok) {
            // 판단 보류 - 직전 값을 지우지 않고 흐리게(0으로 보이지 않게)
            [sEl, mEl].forEach(e => { if (e) e.classList.add('nd-stale'); });
            return;
        }
        [sEl, mEl].forEach(e => { if (e) e.classList.remove('nd-stale'); });
        const th = (Array.isArray(window.currentSessionThresholds) && window.currentSessionThresholds.length === 5)
            ? window.currentSessionThresholds : DEFAULT_SESSION_THRESHOLDS;
        if (sEl) sEl.textContent = typeof d.activeSessions === 'number' ? String(d.activeSessions) : '—';
        if (typeof d.activeSessions === 'number') miniBar('nd-kpi-sessions-bar', d.activeSessions / th[4], sessionColor(d.activeSessions, th));
        if (mEl) mEl.innerHTML = typeof d.memoryPct === 'number' ? `${f1(d.memoryPct)}<small>%</small>` : '—';
        if (typeof d.memoryPct === 'number') {
            const mc = d.memoryPct >= 90 ? 'var(--nd-crit)' : d.memoryPct >= 80 ? 'var(--nd-warn)' : 'var(--primary)';
            miniBar('nd-kpi-memory-bar', d.memoryPct / 100, mc);
        }
    });

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ensureDom);
    else ensureDom();
})();
