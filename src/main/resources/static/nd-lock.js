// 새 대시보드 ③ Lock 대기 세션(실시간) · ③-1 TM Lock 장애 처리 - 설계문서 `대시보드 UI 개선 설계.md` 5장 ③·③-1,
// 전체 작업순서 F6(2026-09-26). nd-dashboard.js 다음에 로드.
//
// - 데이터: 공용 'lock' 이벤트(리프레쉬 주기마다 /api/dashboard/{dbId}/lock/realtime 1회 - F2). 추이는 서버가 DB별로
//   들고 있는 최근 10분(시간 기준)을 그대로 그린다 → 화면을 새로 열어도 10분이 바로 보이고, 보는 사람 수와 무관.
// - 판단 보류(조회 실패·타임아웃): 직전 값을 흐리게 유지, 0을 찍지 않음, 장애 표시는 직전 상태 유지, KILL 비활성.
// - 장애: 카드 붉게 + 알림 바 + (관리자) "장애 처리" 버튼 → 카드 안 확인 패널(모달 아님, 2026-09-25 결정대로
//   조건이 풀려도 자동으로 닫지 않음, 새 Holder는 미선택, 종료 세션은 비활성, 패널 연 순간의 dbId로 요청).
(function () {
    'use strict';
    const ND = window.ndDashboard;
    if (!ND) return;

    // TX 보라·TM 붉은색 - Current Session Wait Class 차트·세션 목록 대기 분해 막대와 같은 색(2026-09-26 오케스트레이터 지적:
    // 예전엔 대기 클래스 토큰을 빌려 써서 TX가 붉은색, TM이 보라로 뒤바뀌어 보였다).
    const TX_COLOR = '#7c3aed';
    const TM_COLOR = '#be123c';
    // 대기 세션 기준(Waiter 수, 2026-09-26 오케스트레이터 결정) - TX 5건 주의/10건 위험, TM 3건 주의/6건 위험
    // (TM은 테이블 단위라 한 건이 여러 세션을 막을 수 있어 더 낮게. 블로킹 TM Holder 3개 주의/6개 장애와 같은 숫자).
    // 예전: TX 1~4 주의/5 위험, TM 1~2 주의/3 위험. 그래프 점선은 TX 주의 기준(5건).
    const TX_WARN = 5;
    const TX_CRIT = 10;
    const TM_WARN = 3;
    const TM_CRIT = 6;

    const st = {
        last: null,        // 마지막 정상 응답
        pending: false,    // 판단 보류 중
        incident: false,
        hover: null
    };
    const panel = { open: false, dbId: null, rows: new Map(), busy: false };
    let G = null;
    let logLine = null;

    const $ = (id) => document.getElementById(id);
    const esc = (s) => String(s === null || s === undefined ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const pad = (n) => String(n).padStart(2, '0');
    // Lock 추이·KILL 시각은 서버(앱) 시각 ms - ② 차트와 같은 DB 시각으로 보이도록 dbClockOffsetMs를 더한다(nd-aas.js가 60초마다 갱신).
    const off = () => (typeof ND.state.dbClockOffsetMs === 'number' ? ND.state.dbClockOffsetMs : 0);
    const hms = (t) => { const d = new Date(t + off()); return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`; };
    const mmss = (sec) => sec >= 60 ? `${Math.floor(sec / 60)}분 ${pad(sec % 60)}초` : `${sec}초`;
    const admin = () => typeof isAdmin === 'function' && isAdmin();

    // ================================================================== DOM

    function ensureDom() {
        const body = $('nd-lock-body');
        if (!body || body.dataset.ready) return;
        body.dataset.ready = '1';
        body.classList.add('nd-lock');
        body.innerHTML =
            '<div class="nd-lk-stats" id="nd-lk-stats"></div>' +
            '<div class="nd-inc-bar" id="nd-inc-bar" hidden role="alert"><span class="nd-inc-txt" id="nd-inc-txt"></span>' +
            '<span id="nd-inc-action"></span></div>' +
            '<div class="nd-lk-legend"><span><span class="nd-sw" style="background:' + TX_COLOR + '"></span>TX Lock</span>' +
            '<span><span class="nd-sw" style="background:' + TM_COLOR + '"></span>TM Lock</span>' +
            '<span class="nd-lk-rule" id="nd-lk-rule"></span></div>' +
            '<div class="nd-plot" id="nd-lk-plot" tabindex="0" role="img" aria-label="최근 10분 TX, TM Lock 대기 세션 수"><svg id="nd-lk-svg"></svg></div>' +
            '<div class="nd-lk-log" id="nd-lk-log" hidden></div>' +
            '<div class="nd-kill-panel" id="nd-kill-panel" hidden role="dialog" aria-label="TM Lock Holder KILL 확인"></div>';
        const title = $('nd-lock-card').querySelector('.nd-card-title');
        const hold = document.createElement('span');
        hold.className = 'nd-badge-delay';
        hold.id = 'nd-lk-hold';
        hold.hidden = true;
        hold.textContent = '판단 보류 · 조회 지연';
        title.appendChild(hold);
        const tip = document.createElement('div');
        tip.className = 'nd-tip';
        tip.id = 'nd-lk-tip';
        tip.hidden = true;
        document.body.appendChild(tip);

        const plot = $('nd-lk-plot');
        plot.addEventListener('pointermove', (e) => { st.hover = tAt(e); renderChart(); showTip(e.clientX, e.clientY); });
        plot.addEventListener('pointerleave', () => { st.hover = null; renderChart(); showTip(); });
        $('nd-kill-panel').addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && !panel.busy) { e.preventDefault(); closePanel(); const b = $('nd-inc-btn'); if (b) b.focus(); }
        });
        if (typeof ResizeObserver === 'function') new ResizeObserver(() => renderChart()).observe(plot);
        renderStats();
    }

    // ================================================================== 이벤트

    ND.on('lock', (d) => {
        ensureDom();
        if (!d || !d.ok) {
            st.pending = true;       // 직전 값 유지, 흐리게
        } else {
            st.pending = false;
            st.last = d;
            st.incident = !!d.incident;
        }
        render();
    });

    ND.onReset(() => {
        closePanel();                // DB 전환 시 다른 DB의 대상 목록을 남기지 않는다
        st.last = null;
        st.pending = false;
        st.incident = false;
        st.hover = null;
        logLine = null;
        const svg = $('nd-lk-svg');
        if (svg) svg.innerHTML = '';
        const plot = $('nd-lk-plot');
        if (plot) plot.classList.add('nd-loading');
        render();
    });

    // ================================================================== 그리기

    function render() {
        if (!$('nd-lock-body') || !$('nd-lock-body').dataset.ready) return;
        const card = $('nd-lock-card');
        card.classList.toggle('nd-incident', st.incident);
        card.classList.toggle('nd-pending', st.pending);
        $('nd-lk-hold').hidden = !st.pending;
        const d = st.last;
        $('nd-lk-rule').textContent = d ? `장애 기준: ${d.lastCallEtThreshold}초↑ 블로킹 TM Holder ${d.incidentCount}개↑` : '';
        if (d) $('nd-lk-plot').classList.remove('nd-loading');
        renderStats();
        renderIncident();
        renderChart();
        const lg = $('nd-lk-log');
        lg.hidden = !logLine;
        if (logLine) lg.innerHTML = logLine;
        if (panel.open && !panel.busy) refreshPanel();
    }

    function lvl(v, warn, crit) {
        return v >= crit ? { c: 'crit', t: '위험' } : v >= warn ? { c: 'warn', t: '주의' } : { c: 'good', t: '정상' };
    }

    function renderStats() {
        const box = $('nd-lk-stats');
        if (!box) return;
        const d = st.last;
        const cell = (label, value, unit, pill, key) =>
            `<div class="nd-lk-stat"><div class="nd-kpi-label">${key ? `<span class="nd-sw" style="background:${key}"></span>` : ''}${esc(label)}</div>` +
            `<div class="nd-kpi-value">${value}${unit ? `<small>${esc(unit)}</small>` : ''}</div><div class="nd-kpi-sub">${pill}</div></div>`;
        const pill = (l, text) => `<span class="nd-lvl nd-lvl-${l.c === 'warn' ? 'serious' : l.c}">${esc(text || l.t)}</span>`;
        if (!d) {
            box.innerHTML = cell('TX 대기 세션', '—', '', '&nbsp;', TX_COLOR) + cell('TM 대기 세션', '—', '', '&nbsp;', TM_COLOR) +
                cell('블로킹 TM Holder', '—', '', '&nbsp;') + cell('최장 last_call_et', '—', '', '&nbsp;');
            return;
        }
        const th = d.lastCallEtThreshold, lim = d.incidentCount, oc = d.holderOverCount;
        const ocPill = oc >= lim ? pill({ c: 'crit' }, '장애 기준 도달')
            : oc >= 3 ? pill({ c: 'warn' }, `기준까지 ${lim - oc}개`) : pill({ c: 'good' }, `전체 Holder ${d.holderTotal}개`);
        box.innerHTML =
            cell('TX 대기 세션', String(d.txWait), '건', d.txWait ? pill(lvl(d.txWait, TX_WARN, TX_CRIT)) : pill({ c: 'good' }, '대기 없음'), TX_COLOR) +
            cell('TM 대기 세션', String(d.tmWait), '건', d.tmWait ? pill(lvl(d.tmWait, TM_WARN, TM_CRIT)) : pill({ c: 'good' }, '대기 없음'), TM_COLOR) +
            cell(`블로킹 TM Holder (${th}초↑)`, String(oc), `/ ${lim}개`, ocPill) +
            cell('최장 last_call_et', d.maxLastCallEt ? esc(mmss(d.maxLastCallEt)) : '—', '',
                d.maxLastCallEt ? pill(lvl(d.maxLastCallEt, 30, th)) : pill({ c: 'good' }, 'Holder 없음'));
    }

    function renderIncident() {
        const bar = $('nd-inc-bar');
        const d = st.last;
        bar.hidden = !st.incident || !d;
        if (bar.hidden) return;
        $('nd-inc-txt').textContent = `TM Lock 장애 감지 — ${d.lastCallEtThreshold}초 이상 다른 세션을 막고 있는 TM Lock Holder ${d.holderOverCount}개 · 기준 ${d.incidentCount}개 이상 · 전체 Holder ${d.holderTotal}개`;
        const act = $('nd-inc-action');
        if (!admin()) {
            act.innerHTML = '<span class="nd-inc-note">장애 처리는 관리자만 할 수 있습니다</span>';
            return;
        }
        let btn = $('nd-inc-btn');
        if (!btn) {
            act.innerHTML = '<button type="button" class="danger-btn" id="nd-inc-btn"></button>';
            btn = $('nd-inc-btn');
            btn.addEventListener('click', () => { if (panel.open) closePanel(); else openPanel(); });
        }
        btn.textContent = `장애 처리 · TM Holder ${d.holderTotal}개 KILL`;
        btn.disabled = st.pending; // 판단 보류 중에는 서버 재조회도 실패할 수 있다
    }

    function renderChart() {
        const plot = $('nd-lk-plot'), svg = $('nd-lk-svg');
        if (!plot || !svg) return;
        const d = st.last;
        const hist = d && d.history ? d.history : [];
        if (hist.length < 1) { svg.innerHTML = ''; G = null; return; }
        const W = Math.max(220, plot.clientWidth), H = Math.max(60, plot.clientHeight);
        const ml = 30, mr = W < 420 ? 44 : 56, mt = 8, mb = 20, pw = W - ml - mr, ph = H - mt - mb;
        const t1 = hist[hist.length - 1].t, t0 = Math.min(hist[0].t, t1 - 10 * 60000);
        const mx = Math.max(TX_WARN + 1, ...hist.map(h => Math.max(h.tx, h.tm)));
        const step = mx <= 8 ? 2 : mx <= 20 ? 5 : 10, top = Math.ceil(mx * 1.1 / step) * step;
        const x = (t) => ml + (t - t0) / Math.max(1, t1 - t0) * pw, y = (v) => mt + ph - v / top * ph;
        G = { W, ml, pw, t0, t1, x, hist };
        svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
        svg.setAttribute('width', W);
        svg.setAttribute('height', H);
        // 조회가 빠진(판단 보류 등) 간격은 끊는다 - 주기의 3배 또는 10초 이상 벌어지면 다른 조각
        const gapMs = Math.max(10000, ND.state.refreshSec * 3000);
        const segs = [];
        hist.forEach((h, i) => { if (i === 0 || h.t - hist[i - 1].t > gapMs) segs.push([h]); else segs[segs.length - 1].push(h); });
        let s = '';
        // 장애 조건 구간
        for (let i = 0; i < hist.length; i++) {
            if (!hist[i].incident) continue;
            let j = i;
            while (j + 1 < hist.length && hist[j + 1].incident) j++;
            const xa = x(hist[i].t), xb = j + 1 < hist.length ? x(hist[j + 1].t) : x(hist[j].t);
            s += `<rect class="nd-incbg" x="${xa.toFixed(1)}" y="${mt}" width="${Math.max(2, xb - xa).toFixed(1)}" height="${ph}"/>`;
            i = j;
        }
        for (let v = 0; v <= top; v += step) {
            const yy = y(v).toFixed(1);
            s += `<line class="${v ? 'nd-gridl' : 'nd-base'}" x1="${ml}" x2="${ml + pw}" y1="${yy}" y2="${yy}"/><text class="nd-tick" x="${ml - 5}" y="${+yy + 4}" text-anchor="end">${v}</text>`;
        }
        for (let t = Math.ceil((t0 + off()) / 120000) * 120000 - off(); t <= t1; t += 120000) {
            s += `<text class="nd-tick" x="${x(t).toFixed(1)}" y="${H - 5}" text-anchor="middle">${hms(t).slice(0, 5)}</text>`;
        }
        const wy = y(TX_WARN).toFixed(1);
        s += `<line class="nd-thl" x1="${ml}" x2="${ml + pw}" y1="${wy}" y2="${wy}"/>`;
        const path = (pts, k) => {
            let p = `M${x(pts[0].t).toFixed(1)},${y(pts[0][k]).toFixed(1)}`;
            for (let i = 1; i < pts.length; i++) p += `H${x(pts[i].t).toFixed(1)}V${y(pts[i][k]).toFixed(1)}`;
            return p;
        };
        segs.forEach(pts => {
            const txd = path(pts, 'tx');
            s += `<path class="nd-lkarea" d="${txd}V${y(0).toFixed(1)}H${x(pts[0].t).toFixed(1)}Z" style="fill:${TX_COLOR}"/>`;
            s += `<path class="nd-lkline" d="${path(pts, 'tm')}" style="stroke:${TM_COLOR}"/>`;
            s += `<path class="nd-lkline" d="${txd}" style="stroke:${TX_COLOR}"/>`;
        });
        (d.killEvents || []).filter(k => k >= t0).forEach(k => {
            const kx = x(k).toFixed(1);
            s += `<line class="nd-killmk" x1="${kx}" x2="${kx}" y1="${mt}" y2="${mt + ph}"/><text class="nd-killlbl" x="${+kx + 4}" y="${mt + 11}">KILL</text>`;
        });
        const L = hist[hist.length - 1];
        let ytx = y(L.tx), ytm = y(L.tm);
        if (Math.abs(ytx - ytm) < 14) { if (L.tx >= L.tm) { ytx = Math.min(ytx, ytm) - 7; ytm = ytx + 14; } else { ytm = Math.min(ytx, ytm) - 7; ytx = ytm + 14; } }
        s += `<circle cx="${x(L.t).toFixed(1)}" cy="${y(L.tx).toFixed(1)}" r="4" style="fill:${TX_COLOR};stroke:var(--nd-surface);stroke-width:2"/>`;
        s += `<circle cx="${x(L.t).toFixed(1)}" cy="${y(L.tm).toFixed(1)}" r="4" style="fill:${TM_COLOR};stroke:var(--nd-surface);stroke-width:2"/>`;
        s += `<text class="nd-endlbl" x="${ml + pw + 8}" y="${(ytx + 4).toFixed(1)}">TX ${L.tx}</text><text class="nd-endlbl" x="${ml + pw + 8}" y="${(ytm + 4).toFixed(1)}">TM ${L.tm}</text>`;
        if (st.hover !== null) {
            const hx = x(st.hover).toFixed(1);
            s += `<line class="nd-xh" x1="${hx}" x2="${hx}" y1="${mt}" y2="${mt + ph}"/>`;
        }
        svg.innerHTML = s;
    }

    function tAt(e) {
        if (!G) return null;
        const r = $('nd-lk-svg').getBoundingClientRect();
        const sx = (e.clientX - r.left) * (G.W / (r.width || G.W));
        const t = G.t0 + (sx - G.ml) / G.pw * (G.t1 - G.t0);
        let best = G.hist[0];
        G.hist.forEach(h => { if (Math.abs(h.t - t) < Math.abs(best.t - t)) best = h; });
        return best.t;
    }

    function showTip(cx, cy) {
        const tip = $('nd-lk-tip');
        if (!tip) return;
        const h = st.hover !== null && G ? G.hist.find(p => p.t === st.hover) : null;
        if (!h) { tip.hidden = true; return; }
        tip.innerHTML = `<div class="nd-tip-t"><span>${hms(h.t)}</span><span>${h.incident ? '장애 조건' : '대기 세션'}</span></div>` +
            `<div class="nd-tip-r"><span class="nd-sw" style="background:${TX_COLOR}"></span><b>${h.tx}건</b><span>TX Lock</span></div>` +
            `<div class="nd-tip-r"><span class="nd-sw" style="background:${TM_COLOR}"></span><b>${h.tm}건</b><span>TM Lock</span></div>`;
        tip.hidden = false;
        const tw = tip.offsetWidth, th = tip.offsetHeight;
        let left = cx + 14;
        if (left + tw > window.innerWidth - 4) left = Math.max(4, cx - 14 - tw);
        tip.style.left = left + 'px';
        tip.style.top = Math.max(4, Math.min(window.innerHeight - th - 4, cy - th / 2)) + 'px';
    }

    // ================================================================== 확인 패널 (③-1)

    function holderKey(h) { return h.sid + ':' + h.serial; }

    function openPanel() {
        const d = st.last;
        if (!d || !admin()) return;
        panel.open = true;
        panel.dbId = window.currentDbId;       // 요청 dbId는 패널을 연 순간의 값(설계 ③-1 6번)
        panel.rows = new Map();
        const p = $('nd-kill-panel');
        p.hidden = false;
        p.innerHTML =
            `<h3>TM Lock Holder ${d.holderTotal}개 세션을 KILL 합니다</h3>` +
            '<p class="nd-kp-note" id="nd-kp-note" role="status" hidden></p>' +
            '<p class="nd-kp-sub">기본값은 전체 선택입니다. 패널을 연 뒤 새로 생긴 Holder는 자동으로 선택되지 않습니다. 대상은 실행 직전에 서버가 다시 조회해서, 그 사이 커밋·종료된 세션은 건너뜁니다.</p>' +
            '<div class="nd-kp-table"><table class="nd-table"><thead><tr><th></th><th>SID,SERIAL#</th><th>사용자 · 프로그램</th><th>잠금 객체</th><th>last_call_et</th><th>막고 있는 세션</th></tr></thead><tbody id="nd-kp-body"></tbody></table></div>' +
            '<pre class="nd-kp-cmd" id="nd-kp-cmd"></pre>' +
            '<div class="nd-kp-act"><button type="button" class="nd-link-btn" id="nd-kp-cancel">취소</button><button type="button" class="danger-btn" id="nd-kp-go"></button></div>';
        const tb = $('nd-kp-body');
        d.holders.slice().sort((a, b) => b.lastCallEt - a.lastCallEt).forEach(h => addRow(tb, h, true, false));
        $('nd-kp-cancel').addEventListener('click', closePanel);
        $('nd-kp-go').addEventListener('click', execKill);
        updCmd();
        refreshPanel();
        $('nd-kp-go').focus();
    }

    function addRow(tb, h, checked, isNew) {
        const tr = document.createElement('tr');
        const key = holderKey(h);
        tr.innerHTML = `<td><input type="checkbox" ${checked ? 'checked' : ''} aria-label="SID ${esc(h.sid)} 선택"></td>` +
            `<td class="nd-mono">${esc(h.sid)},${esc(h.serial)}${isNew ? ' <span class="nd-newtag">새로 생김</span>' : ''}</td>` +
            `<td>${esc(h.username || '-')} · ${esc(h.program || '-')}</td><td class="nd-mono">${esc(h.object || '-')}</td>` +
            '<td class="nd-kp-lce"></td><td class="nd-kp-w"></td>';
        tb.appendChild(tr);
        const r = { tr, cb: tr.querySelector('input'), lce: tr.querySelector('.nd-kp-lce'), w: tr.querySelector('.nd-kp-w'), h, gone: false };
        r.cb.addEventListener('change', updCmd);
        panel.rows.set(key, r);
        fillRow(r, h);
    }

    function fillRow(r, h) {
        const th = st.last ? st.last.lastCallEtThreshold : 60;
        r.h = h;
        r.lce.innerHTML = esc(mmss(h.lastCallEt)) + (h.lastCallEt < th ? ` <span class="nd-short">${th}초 미만</span>` : '');
        r.w.textContent = `${h.waiters}건`;
    }

    /** 매 주기: 조건 해소 안내, 종료된 세션 비활성, 새 Holder 추가(미선택), 값 갱신 - 패널은 자동으로 닫지 않는다. */
    function refreshPanel() {
        const d = st.last;
        const note = $('nd-kp-note');
        if (!note || !d) return;
        if (!st.incident) {
            note.hidden = false;
            note.textContent = `장애 조건 해소됨 (현재 블로킹 Holder ${d.holderOverCount}개 · 기준 ${d.incidentCount}개). KILL 여부를 판단한 뒤 직접 닫으세요.`;
        } else {
            note.hidden = true;
        }
        const alive = new Map(d.holders.map(h => [holderKey(h), h]));
        panel.rows.forEach((r, key) => {
            const h = alive.get(key);
            if (!h) {
                if (!r.gone) {
                    r.gone = true;
                    r.cb.checked = false;
                    r.cb.disabled = true;
                    r.tr.classList.add('nd-gone');
                    r.lce.innerHTML = '<span class="nd-gonetag">종료됨</span>';
                    r.w.textContent = '—';
                }
            } else {
                fillRow(r, h);
            }
        });
        const tb = $('nd-kp-body');
        d.holders.forEach(h => { if (!panel.rows.has(holderKey(h))) addRow(tb, h, false, true); });
        updCmd();
    }

    function selected() {
        const out = [];
        panel.rows.forEach(r => { if (r.cb.checked && !r.cb.disabled) out.push(r.h); });
        return out;
    }

    function updCmd() {
        const hs = selected();
        const cmd = $('nd-kp-cmd'), go = $('nd-kp-go');
        if (!cmd || !go) return;
        cmd.textContent = hs.length ? hs.map(h => `ALTER SYSTEM KILL SESSION '${h.sid},${h.serial}' IMMEDIATE;`).join('\n') : '-- 선택된 세션이 없습니다';
        if (!panel.busy) {
            go.textContent = `KILL 실행 (${hs.length}개)`;
            go.disabled = !hs.length || st.pending;
        }
    }

    function closePanel() {
        panel.open = false;
        panel.busy = false;
        panel.dbId = null;
        panel.rows = new Map();
        const p = $('nd-kill-panel');
        if (p) { p.hidden = true; p.innerHTML = ''; }
    }

    async function execKill() {
        const hs = selected();
        if (!hs.length || panel.busy || st.pending) return;
        const dbId = panel.dbId;
        const go = $('nd-kp-go');
        panel.busy = true;
        go.disabled = true;
        go.textContent = 'KILL 실행 중…';
        let res, body;
        try {
            res = await fetch('/api/dashboard/' + encodeURIComponent(dbId) + '/lock/tm-holders/kill', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: getToken(), dbId, targets: hs.map(h => ({ sid: h.sid, serial: h.serial })) })
            });
            body = await res.json().catch(() => null);
        } catch (e) {
            body = { error: e.message };
        }
        const now = Date.now();
        if (!res || !res.ok || !body || !body.results) {
            logLine = `<span class="nd-crit-t">${hms(now)} 장애 처리 실패 · ${esc((body && body.error) || ('HTTP ' + (res ? res.status : '-')))}</span>`;
        } else {
            const ok = body.results.filter(r => r.result === 'SUCCESS');
            const skipped = body.results.filter(r => r.result === 'SKIPPED').length;
            const failed = body.results.filter(r => r.result === 'FAILED').length;
            logLine = `${hms(body.ts || now)} 장애 처리 완료 · TM Lock Holder ${ok.length}개 세션 KILL` +
                (ok.length ? ` (SID ${ok.map(r => r.sid).join(', ')})` : '') +
                (skipped ? ` · 건너뜀 ${skipped}개(이미 종료)` : '') +
                (failed ? ` · <span class="nd-crit-t">실패 ${failed}개</span>` : '') +
                (body.auditWriteFailed ? ' · <span class="nd-crit-t">감사 기록 저장 실패 — 관리자에게 알리세요</span>' : '');
        }
        closePanel();
        render();
        if (dbId === window.currentDbId) ND.refreshNow();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ensureDom);
    else ensureDom();
})();
