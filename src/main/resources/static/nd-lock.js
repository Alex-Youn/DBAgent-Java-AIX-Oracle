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
            '<div class="nd-plot" id="nd-lk-plot" tabindex="0" role="img" aria-label="최근 10분 TX, TM Lock 대기 세션 수"><svg id="nd-lk-svg"></svg></div>' +
            '<div class="nd-lk-log" id="nd-lk-log" hidden></div>';
        // KILL 확인 패널은 차트 본문이 아니라 카드 바로 아래에 둔다 - 본문 안(inset:0)이면 작은 화면(1920×1080)에서 카드 본문이
        // 116px로 줄 때 패널도 같이 줄어 Holder 목록 표 높이가 0이 됐다(KILL 대상이 안 보이고 버튼만 보임, 2026-09-28 실측).
        const killPanel = document.createElement('div');
        killPanel.className = 'nd-kill-panel';
        killPanel.id = 'nd-kill-panel';
        killPanel.hidden = true;
        killPanel.setAttribute('role', 'dialog');
        killPanel.setAttribute('aria-label', 'TM Lock Holder KILL 확인');
        $('nd-lock-card').appendChild(killPanel);
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

    // Lock 요약 한 줄(2026-09-30 오케스트레이터 결정 A안): 예전 4칸×3줄(라벨·값·상태) + 범례 줄 + 장애 기준 문구는 같은 기준을
    // 세 번 되풀이하고 정상일 때도 "대기 없음"이 칸마다 떠 지저분했다. 라벨 앞 색 표시가 범례를 겸하고, 상태(주의·위험)는
    // 정상이 아닐 때만 붙인다. 장애 기준·주의/위험 기준·전체 Holder 수는 항목 툴팁(title)으로 옮겼다(장애 시엔 알림 바에도 나옴).
    function renderStats() {
        const box = $('nd-lk-stats');
        if (!box) return;
        const d = st.last;
        const item = (label, value, tip, state, key) =>
            `<span class="nd-lk-stat" title="${esc(tip)}"><span class="nd-lk-lbl">${key ? `<span class="nd-sw" style="background:${key}"></span>` : ''}${esc(label)}</span>` +
            `<span class="nd-lk-val">${value}</span>${state}</span>`;
        const pill = (c, text) => `<span class="nd-lvl nd-lvl-${c === 'warn' ? 'serious' : c}">${esc(text)}</span>`;
        const state = (l) => l.c === 'good' ? '' : pill(l.c, l.t);
        const txTip = `TX Lock(행 잠금) 대기 세션 수 · 주의 ${TX_WARN}건↑ / 위험 ${TX_CRIT}건↑`;
        const tmTip = `TM Lock(테이블 잠금) 대기 세션 수 · 주의 ${TM_WARN}건↑ / 위험 ${TM_CRIT}건↑`;
        if (!d) {
            box.innerHTML = item('TX 대기', '—', txTip, '', TX_COLOR) + item('TM 대기', '—', tmTip, '', TM_COLOR) +
                item('블로킹 Holder', '—', '다른 세션을 막고 있는 TM Lock Holder 수', '') +
                item('최장 경과', '—', '다른 세션을 막고 있는 TM Lock Holder 중 가장 긴 last_call_et', '');
            return;
        }
        const th = d.lastCallEtThreshold, lim = d.incidentCount, oc = d.holderOverCount;
        // 분·초는 작은 단위 글씨로 - 숫자 글꼴(Consolas)에 한글이 없어 "2분 13초"를 값 크기로 쓰면 글자가 크고 들쭉날쭉했다
        const et = d.maxLastCallEt, etHtml = et >= 60 ? `${Math.floor(et / 60)}<small>분</small> ${pad(et % 60)}<small>초</small>` : `${et}<small>초</small>`;
        const ocState = oc >= lim ? pill('crit', '장애 기준 도달') : oc >= 3 ? pill('warn', `기준까지 ${lim - oc}개`) : '';
        box.innerHTML =
            item('TX 대기', String(d.txWait), txTip, state(lvl(d.txWait, TX_WARN, TX_CRIT)), TX_COLOR) +
            item('TM 대기', String(d.tmWait), tmTip, state(lvl(d.tmWait, TM_WARN, TM_CRIT)), TM_COLOR) +
            item('블로킹 Holder', `${oc}<small>/ ${lim}</small>`,
                `last_call_et ${th}초 이상 다른 세션을 막고 있는 TM Lock Holder 수 / 장애 기준 ${lim}개 · 전체 Holder ${d.holderTotal}개`, ocState) +
            item('최장 경과', d.maxLastCallEt ? etHtml : '—',
                `다른 세션을 막고 있는 TM Lock Holder 중 가장 긴 last_call_et · 주의 30초↑ / 위험 ${th}초↑`,
                d.maxLastCallEt ? state(lvl(d.maxLastCallEt, 30, th)) : '');
    }

    function renderIncident() {
        const bar = $('nd-inc-bar');
        const d = st.last;
        const wasHidden = bar.hidden;
        bar.hidden = !st.incident || !d;
        // 알림 바가 뜨거나 사라지면 차트 행 최소 높이(180/270)가 바뀐다 - nd-dashboard.js fitChart
        if (bar.hidden !== wasHidden && window.ndDashboard && window.ndDashboard.fitChart) window.ndDashboard.fitChart();
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
        // 화면이 없던 구간(다른 메뉴·다른 DB)은 서버가 60초 샘플로 채워 준다(fill) - 흐린 점선, 툴팁에 "1분 샘플"
        const fill = (d && d.fill ? d.fill : []).map(p => Object.assign({ coarse: true }, p));
        const all = hist.concat(fill).sort((a, b) => a.t - b.t);
        if (all.length < 1) { svg.innerHTML = ''; G = null; return; }
        const W = Math.max(220, plot.clientWidth), H = Math.max(60, plot.clientHeight);
        const t1 = all[all.length - 1].t, t0 = Math.min(all[0].t, t1 - 10 * 60000);
        // 값 하나라도 숫자가 아니면 Math.max가 NaN이 되어 아래 눈금 루프가 끝나지 않는다(탭 멈춤) - 그때는 기본 범위로 그린다.
        const mxRaw = Math.max(TX_WARN + 1, ...all.map(h => Math.max(h.tx, h.tm)));
        const mx = Number.isFinite(mxRaw) ? mxRaw : TX_WARN + 1;
        // Y 눈금은 차트 높이에 맞춰 18px에 1개 이하로 - 예전엔 간격이 최대 10 고정이라 대기 100건이면 눈금 12개가
        // 작은 차트(1920×1080에서 약 60px)에 몰려 숫자가 겹쳤다(2026-09-29 폐쇄망). 간격은 1·2·2.5·5 × 10^n.
        // 왼쪽 여백은 최댓값 자릿수에 맞춘다(세 자리 이상이 잘리지 않게).
        const mt = 8, mb = 20, ph = Math.max(20, H - mt - mb);
        const maxTicks = Math.max(1, Math.floor(ph / 18));
        let step = 1;
        for (let e = 1; e <= 1e9; e *= 10) {
            const c = [1, 2, 2.5, 5].map(k => k * e).filter(v => Number.isInteger(v)).find(v => Math.ceil(mx * 1.1 / v) <= maxTicks);
            if (c) { step = c; break; }
        }
        const top = Math.ceil(mx * 1.1 / step) * step;
        const ml = 14 + String(top).length * 8, mr = W < 420 ? 44 : 56, pw = W - ml - mr;
        const x = (t) => ml + (t - t0) / Math.max(1, t1 - t0) * pw, y = (v) => mt + ph - v / top * ph;
        G = { W, ml, pw, t0, t1, x, hist: all };
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
        // 채운 구간: 실시간 점까지 포함해 이어 그려(빈칸 없이) 흐린 점선 한 겹을 깔고, 그 위에 실시간 선을 그린다 →
        // 실시간 구간은 실선이 덮고, 비었던 구간만 점선으로 보인다. 1분 점 사이(샘플러 지연 포함 150초 초과)는 끊는다.
        if (fill.length) {
            const fsegs = [];
            all.forEach((h, i) => { if (i === 0 || h.t - all[i - 1].t > 150000) fsegs.push([h]); else fsegs[fsegs.length - 1].push(h); });
            fsegs.filter(pts => pts.some(p => p.coarse)).forEach(pts => {
                s += `<path class="nd-lkline-fill" d="${path(pts, 'tm')}" style="stroke:${TM_COLOR}"/>`;
                s += `<path class="nd-lkline-fill" d="${path(pts, 'tx')}" style="stroke:${TX_COLOR}"/>`;
            });
        }
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
        const L = hist.length ? hist[hist.length - 1] : all[all.length - 1];
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
        tip.innerHTML = `<div class="nd-tip-t"><span>${hms(h.t)}</span><span>${h.coarse ? '1분 샘플(화면 밖 구간)' : h.incident ? '장애 조건' : '대기 세션'}</span></div>` +
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
            const marked = ok.filter(r => r.marked).length; // ORA-00031 - 작업 정리 후 끊김(2026-10-01)
            const skipped = body.results.filter(r => r.result === 'SKIPPED').length;
            const failedRows = body.results.filter(r => r.result === 'FAILED');
            const failed = failedRows.length;
            logLine = `${hms(body.ts || now)} 장애 처리 완료 · TM Lock Holder ${ok.length}개 세션 KILL` +
                (ok.length ? ` (SID ${ok.map(r => r.sid).join(', ')})` : '') +
                (marked ? ` · ${marked}개는 종료 표시(작업 정리 후 끊김)` : '') +
                (skipped ? ` · 건너뜀 ${skipped}개(이미 종료)` : '') +
                (failed ? ` · <span class="nd-crit-t">실패 ${failed}개` +
                    (failedRows[0].message ? ` - ${esc(failedRows[0].message)}` : '') + '</span>' : '') +
                (body.auditWriteFailed ? ' · <span class="nd-crit-t">감사 기록 저장 실패 — 관리자에게 알리세요</span>' : '');
        }
        closePanel();
        render();
        if (dbId === window.currentDbId) ND.refreshNow();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ensureDom);
    else ensureDom();
})();
