// 새 대시보드 ⑤ Top SQL · ⑥ Top 세션 · ⑦ Top 대기 이벤트 · 6장 상세 드로어 - 설계문서 `대시보드 UI 개선 설계.md`
// 5장 ⑤⑥⑦·6장, 전체 작업순서 F7(2026-09-26). nd-dashboard.js·nd-aas.js 다음에 로드.
//
// - ② 차트의 'selection'(DB 시각 from/to) 이 바뀔 때만 /api/dashboard/{dbId}/top 을 부른다(ASH 스캔이라 무거움 -
//   같은 구간이면 다시 부르지 않음). 수동 새로고침은 같은 구간이라도 다시 부른다.
// - 드로어: body 바로 아래(zoom 영향 없음), 오버레이, Esc·배경·✕로 닫고 연 행으로 포커스 복귀, 안에서 이동하면
//   "‹ 뒤로"(스택). 선택 구간이 바뀌면 열린 드로어도 새 구간으로 다시 그린다.
// - 블로커가 다른 RAC 인스턴스면 "다른 인스턴스(n번) SID m"으로만 표시하고 상세로 가지 않는다.
// - "KILL 구문 복사"는 관리자만, 백그라운드 세션은 비활성. 복사: navigator.clipboard(HTTPS/localhost만) →
//   execCommand('copy') → 텍스트 선택 후 "Ctrl+C" 안내(폐쇄망 http://IP:포트 대비, 설계 2.4).
(function () {
    'use strict';
    const ND = window.ndDashboard;
    if (!ND) return;

    const st = { sel: null, key: null, gen: 0, data: null, error: null, loading: false };
    const dw = { stack: [], from: null, gen: 0 };

    const $ = (id) => document.getElementById(id);
    const esc = (s) => String(s === null || s === undefined ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const pad = (n) => String(n).padStart(2, '0');
    const f1 = (v) => (Math.round((v || 0) * 10) / 10).toFixed(1);
    const f2 = (v) => (Math.round((v || 0) * 100) / 100).toFixed(2);
    const iso = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`; };
    const hm = (ms) => { const d = new Date(ms); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };
    const classes = () => ND.classes || [];
    const clsName = (k) => { const c = classes().find(x => x.k === k); return c ? c.n : k; };
    const clsColor = (k) => `var(--nd-c-${k})`;
    const admin = () => typeof isAdmin === 'function' && isAdmin();

    // ================================================================== 데이터

    ND.on('selection', (sel) => {
        st.sel = sel;
        if (!sel) { renderEmpty('선택 구간이 없습니다.'); return; }
        const key = `${window.currentDbId}|${sel.from}|${sel.to}`;
        if (key === st.key) return; // 같은 구간이면 다시 조회하지 않는다
        st.key = key;
        loadTop();
        if (dw.stack.length) paintDrawer(); // 열린 드로어도 새 구간 기준으로
    });

    ND.onReset(() => {
        st.key = null;
        st.sel = null;
        st.data = null;
        st.gen++;
        closeDrawer(true);
        ['nd-top-sql', 'nd-top-session', 'nd-top-event'].forEach(id => {
            const body = $(id) && $(id).querySelector('.nd-top-body');
            if (body) body.innerHTML = '<div class="nd-skel nd-skel-line"></div><div class="nd-skel nd-skel-line"></div><div class="nd-skel nd-skel-line"></div>';
        });
        const src = $('nd-top-src');
        if (src) src.textContent = '';
    });

    // 수동 새로고침은 같은 구간이라도 다시 조회
    document.addEventListener('click', (e) => {
        if (e.target && e.target.closest && e.target.closest('#nd-refresh-btn')) st.key = null;
    }, true);

    async function loadTop() {
        const sel = st.sel;
        const dbId = window.currentDbId;
        if (!sel || !dbId) return;
        const gen = ++st.gen;
        const q = new URLSearchParams({ from: iso(sel.from), to: iso(sel.to), token: getToken() });
        let res, body;
        try {
            res = await fetch(`/api/dashboard/${encodeURIComponent(dbId)}/top?${q}`);
            body = await res.json().catch(() => null);
        } catch (e) {
            body = { error: e.message };
        }
        if (gen !== st.gen || dbId !== window.currentDbId) return; // 늦게 온 응답 폐기
        if (!res || !res.ok || !body || body.error) {
            if (res && res.status === 403) return; // 권한 없음은 공통 엔진이 한 줄로 표시
            st.data = null;
            st.error = (body && body.error) || ('HTTP ' + (res ? res.status : '-'));
            renderError();
            return;
        }
        st.data = body;
        st.error = null;
        renderTop();
    }

    // ================================================================== ⑤⑥⑦ 목록

    function segBar(by, scale) {
        const total = Object.values(by || {}).reduce((a, b) => a + b, 0);
        if (!total) return '<div class="nd-segbar" style="width:2%"></div>';
        let h = `<div class="nd-segbar" style="width:${Math.max(2, total / scale * 100).toFixed(1)}%">`;
        classes().forEach(c => {
            const v = by[c.k];
            if (!v || v / total < 0.02) return;
            h += `<span style="flex:${v};background:${clsColor(c.k)}" title="${esc(c.n)} ${f2(v)}"></span>`;
        });
        return h + '</div>';
    }

    function body(id) { return $(id).querySelector('.nd-top-body'); }

    function renderEmpty(msg) {
        ['nd-top-sql', 'nd-top-session', 'nd-top-event'].forEach(id => { if ($(id)) body(id).innerHTML = `<div class="nd-placeholder">${esc(msg)}</div>`; });
    }

    function renderError() {
        const html = `<div class="nd-top-err">ASH 조회 실패 — 권한 또는 조회 시간 초과<div class="nd-top-errmsg">${esc(st.error)}</div>` +
            '<button type="button" class="nd-link-btn nd-top-retry">다시 시도</button></div>';
        ['nd-top-sql', 'nd-top-session', 'nd-top-event'].forEach(id => { body(id).innerHTML = html; });
        document.querySelectorAll('.nd-top-retry').forEach(b => b.addEventListener('click', () => { st.key = null; loadTop(); }));
    }

    function blockerHtml(r) {
        if (r.blockingSession === null || r.blockingSession === undefined) return '';
        if (r.blockerRemote) return `<span class="nd-blk">블로커: 다른 인스턴스(${esc(r.blockingInstId)}번) SID ${esc(r.blockingSession)}</span>`;
        return `<span class="nd-blk">블로커 SID ${esc(r.blockingSession)}</span>`;
    }

    function renderTop() {
        const d = st.data;
        const src = $('nd-top-src');
        if (src) {
            src.textContent = `${d.instanceName || ''} · 이 인스턴스 기준 · v$active_session_history` +
                (d.source === 'ash' ? '' : ' + dba_hist_active_sess_history') + ` · ${hm(st.sel.from)}–${hm(st.sel.to)}`;
        }
        const empty = '<div class="nd-placeholder">선택 구간에 활성 세션이 없습니다</div>';
        // ⑤ Top SQL
        const sqls = d.topSql || [];
        const sqlMax = sqls.length ? sqls[0].aas : 1;
        body('nd-top-sql').innerHTML = sqls.length ? '<ul class="nd-top-list">' + sqls.map((r, i) =>
            `<li class="nd-clk" tabindex="0" role="button" data-kind="sql" data-i="${i}" aria-label="SQL ${esc(r.sqlId)} 상세 보기">` +
            `<div class="nd-rtop"><span class="nd-rid">${esc(r.sqlId)}</span><span class="nd-rval">${f1(r.aas)}<small>${f1(r.pct)}%</small></span></div>` +
            segBar(r.byCategory, sqlMax) +
            `<div class="nd-rtxt">${r.sqlText ? esc(r.sqlText) : '<span class="nd-muted">SQL 텍스트 없음(공유 풀에서 밀려남)</span>'}</div></li>`).join('') + '</ul>' : empty;
        // ⑥ Top 세션
        const sess = d.topSession || [];
        const sessMax = sess.length ? sess[0].aas : 1;
        body('nd-top-session').innerHTML = sess.length ? '<ul class="nd-top-list">' + sess.map((r, i) => {
            const app = (r.byCategory || {}).application || 0;
            const showBlk = r.blockingSession !== null && r.blockingSession !== undefined && r.aas > 0 && app / r.aas >= 0.3;
            return `<li class="nd-clk" tabindex="0" role="button" data-kind="session" data-i="${i}" aria-label="세션 SID ${esc(r.sid)} 상세 보기">` +
                `<div class="nd-rtop"><span class="nd-rid">SID ${esc(r.sid)}</span><span class="nd-rval">${f1(r.aas)}<small>${f1(r.pct)}%</small></span></div>` +
                segBar(r.byCategory, sessMax) +
                `<div class="nd-rmeta"><span>${esc(r.username || '-')}</span><span>${esc(r.program || '')}</span>${showBlk ? blockerHtml(r) : ''}</div></li>`;
        }).join('') + '</ul>' : empty;
        // ⑦ Top 대기 이벤트
        const evs = d.topEvent || [];
        const evMax = evs.length ? evs[0].aas : 1;
        body('nd-top-event').innerHTML = evs.length ? '<ul class="nd-top-list">' + evs.map((r, i) => {
            const by = {}; by[r.category] = r.aas;
            return `<li class="nd-clk" tabindex="0" role="button" data-kind="event" data-i="${i}" aria-label="대기 이벤트 ${esc(r.event)} 상세 보기">` +
                `<div class="nd-rtop"><span class="nd-rid">${esc(r.event)}</span><span class="nd-rval">${f1(r.aas)}<small>${f1(r.pct)}%</small></span></div>` +
                segBar(by, evMax) + `<div class="nd-rmeta"><span>${esc(clsName(r.category))}</span></div></li>`;
        }).join('') + '</ul>' : empty;
        document.querySelectorAll('#nd-top-sql li.nd-clk, #nd-top-session li.nd-clk, #nd-top-event li.nd-clk').forEach(li => {
            const open = () => {
                const i = +li.dataset.i;
                if (li.dataset.kind === 'sql') openDrawer(() => viewSql(sqls[i].sqlId), li);
                else if (li.dataset.kind === 'session') openDrawer(() => viewSession(sess[i].sid, sess[i].serial), li);
                else openDrawer(() => viewEvent(evs[i].event), li);
            };
            li.addEventListener('click', open);
            li.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } });
        });
    }

    // ================================================================== 드로어 틀

    function ensureDrawer() {
        if ($('nd-drawer')) return;
        const bg = document.createElement('div');
        bg.className = 'nd-drawer-bg';
        bg.id = 'nd-drawer-bg';
        bg.hidden = true;
        const d = document.createElement('aside');
        d.className = 'nd-drawer';
        d.id = 'nd-drawer';
        d.hidden = true;
        d.setAttribute('role', 'dialog');
        d.setAttribute('aria-modal', 'true');
        d.innerHTML = '<div class="nd-dw-head"><button type="button" class="nd-dw-back" id="nd-dw-back" hidden>‹ 뒤로</button>' +
            '<div class="nd-dw-titles"><div class="nd-dw-kind" id="nd-dw-kind"></div><div class="nd-dw-title" id="nd-dw-title"></div><div class="nd-dw-sub" id="nd-dw-sub"></div></div>' +
            '<button type="button" class="nd-dw-close" id="nd-dw-close" aria-label="닫기">✕</button></div>' +
            '<div class="nd-dw-body" id="nd-dw-body"></div><div class="nd-dw-foot" id="nd-dw-foot" hidden></div>';
        document.body.append(bg, d); // body 직속 - .app-container zoom 안에 두면 fixed 위치가 어긋난 전례(설계 2.4)
        bg.addEventListener('click', () => closeDrawer());
        $('nd-dw-close').addEventListener('click', () => closeDrawer());
        $('nd-dw-back').addEventListener('click', () => { dw.stack.pop(); paintDrawer(); });
        document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && dw.stack.length) closeDrawer(); });
    }

    function openDrawer(view, from) {
        ensureDrawer();
        dw.from = from;
        dw.stack = [view];
        paintDrawer();
        $('nd-dw-close').focus();
    }

    function pushDrawer(view) {
        dw.stack.push(view);
        paintDrawer();
        $('nd-dw-body').scrollTop = 0;
    }

    function closeDrawer(silent) {
        dw.stack = [];
        dw.gen++;
        if ($('nd-drawer')) { $('nd-drawer').hidden = true; $('nd-drawer-bg').hidden = true; }
        if (!silent && dw.from && document.contains(dw.from)) dw.from.focus();
    }

    async function paintDrawer() {
        if (!dw.stack.length) return;
        const gen = ++dw.gen;
        $('nd-drawer').hidden = false;
        $('nd-drawer-bg').hidden = false;
        $('nd-dw-back').hidden = dw.stack.length < 2;
        $('nd-dw-body').innerHTML = '<div class="nd-skel nd-skel-line"></div><div class="nd-skel nd-skel-line"></div><div class="nd-skel nd-skel-line" style="width:60%"></div>';
        $('nd-dw-foot').hidden = true;
        let v;
        try {
            v = await dw.stack[dw.stack.length - 1]();
        } catch (e) {
            v = { kind: '오류', title: '상세 조회 실패', secs: [`<p class="nd-top-err">${esc(e.message)}</p>`] };
        }
        if (gen !== dw.gen) return;
        $('nd-dw-kind').textContent = v.kind;
        $('nd-dw-title').textContent = v.title;
        $('nd-dw-sub').innerHTML = v.sub || '';
        $('nd-dw-body').innerHTML = v.secs.join('');
        const foot = $('nd-dw-foot');
        foot.innerHTML = '';
        (v.actions || []).forEach(a => foot.appendChild(a));
        foot.hidden = !(v.actions || []).length;
        if (v.bind) v.bind($('nd-dw-body'));
    }

    async function getDetail(path) {
        const sel = st.sel;
        const q = new URLSearchParams({ from: iso(sel.from), to: iso(sel.to), token: getToken() });
        const res = await fetch(`/api/dashboard/${encodeURIComponent(window.currentDbId)}/${path}${path.indexOf('?') >= 0 ? '&' : '?'}${q}`);
        const body = await res.json().catch(() => null);
        if (!res.ok || !body || body.error) throw new Error((body && body.error) || ('HTTP ' + res.status));
        return body;
    }

    // ------------------------------------------------------------------ 드로어 부품

    const sec = (title, inner) => `<section class="nd-dw-sec"><h4>${esc(title)}</h4>${inner}</section>`;
    const kv = (pairs) => '<dl class="nd-kv">' + pairs.map(([k, v, raw]) => `<dt>${esc(k)}</dt><dd>${raw ? v : esc(v === null || v === undefined || v === '' ? '—' : v)}</dd>`).join('') + '</dl>';
    const code = (t) => `<pre class="nd-dw-code">${esc(t)}</pre>`;
    const queries = (list) => `<details class="nd-dw-q"><summary>조회 쿼리 보기</summary>${(list || []).map(code).join('')}</details>`;
    const winTxt = () => `${hm(st.sel.from)} – ${hm(st.sel.to)} (${Math.round((st.sel.to - st.sel.from) / 60000)}분)`;

    function activityHtml(a) {
        const by = a.byCategory || {};
        const total = Object.values(by).reduce((x, y) => x + y, 0);
        const items = Object.keys(by).map(k => ({ k, v: by[k] })).filter(x => total && x.v / total >= 0.02).sort((x, y) => y.v - x.v);
        return `<div class="nd-dw-actline"><b>${f2(a.aas)}</b> AAS · 구간 전체의 ${f1(a.pct)}%</div>` +
            segBar(by, total || 1).replace('class="nd-segbar"', 'class="nd-segbar nd-dw-bar"') +
            '<ul class="nd-dw-cls">' + items.map(x => `<li><span class="nd-sw" style="background:${clsColor(x.k)}"></span><span>${esc(clsName(x.k))}</span><b>${f2(x.v)} (${Math.round(x.v / total * 100)}%)</b></li>`).join('') + '</ul>';
    }

    function sessListHtml(items) {
        if (!items || !items.length) return '<p class="nd-muted">해당하는 세션이 없습니다.</p>';
        return '<ul class="nd-top-list nd-dw-list">' + items.map((r, i) =>
            `<li class="nd-clk" tabindex="0" role="button" data-i="${i}"><div class="nd-rtop"><span class="nd-rid">SID ${esc(r.sid)}</span><span class="nd-rval">${f2(r.aas)}<small>${f1(r.pct)}%</small></span></div>` +
            `<div class="nd-rmeta"><span>${esc(r.username || '-')}</span><span>${esc(r.event || '')}</span>${blockerHtml(r)}</div></li>`).join('') + '</ul>';
    }

    function bindSessList(root, items) {
        root.querySelectorAll('.nd-dw-list li.nd-clk').forEach(li => {
            const r = items[+li.dataset.i];
            const go = () => pushDrawer(() => viewSession(r.sid, r.serial));
            li.addEventListener('click', go);
            li.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(); } });
        });
    }

    /** 클립보드: navigator.clipboard(보안 컨텍스트만) → execCommand → 선택 후 수동 복사 안내. */
    function copyText(text, btn, label) {
        const done = (msg) => { btn.textContent = msg; setTimeout(() => { btn.textContent = label; }, 1800); };
        const fallback = () => {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.setAttribute('readonly', '');
            ta.style.cssText = 'position:fixed;left:-9999px;top:0;';
            document.body.appendChild(ta);
            ta.select();
            let ok = false;
            try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
            document.body.removeChild(ta);
            if (ok) { done('복사됨'); return; }
            const pre = $('nd-dw-copy-manual');
            if (pre) {
                pre.hidden = false;
                pre.textContent = text;
                const range = document.createRange();
                range.selectNodeContents(pre);
                const s = window.getSelection();
                s.removeAllRanges();
                s.addRange(range);
            }
            done('Ctrl+C로 복사하세요');
        };
        if (navigator.clipboard && window.isSecureContext) {
            navigator.clipboard.writeText(text).then(() => done('복사됨'), fallback);
        } else {
            fallback();
        }
    }

    // ------------------------------------------------------------------ 6.1 세션 상세

    async function viewSession(sid, serial) {
        const d = await getDetail(`session/${encodeURIComponent(sid)}/${encodeURIComponent(serial)}`);
        const c = d.current;
        const bg = c && c.type === 'BACKGROUND';
        const pill = !c ? '<span class="nd-pill nd-pill-muted">세션 종료됨</span>'
            : `<span class="nd-pill ${bg ? 'nd-pill-good' : c.blockingSession !== null && c.blockingSession !== undefined ? 'nd-pill-crit' : 'nd-pill-warn'}">${bg ? 'BACKGROUND' : esc(c.status || 'ACTIVE')}</span>`;
        const secs = [];
        if (c) {
            secs.push(sec('기본 정보', kv([['USERNAME', c.username], ['PROGRAM', c.program], ['MODULE', c.module], ['MACHINE', c.machine],
                ['OSUSER', c.osuser], ['서버 PID (SPID)', c.spid], ['LOGON_TIME', c.logonTime]])));
            let blk = '없음';
            if (c.blockingSession !== null && c.blockingSession !== undefined) {
                if (c.blockerRemote) blk = `<span class="nd-blk">다른 인스턴스(${esc(c.blockingInstId)}번) SID ${esc(c.blockingSession)}</span>`;
                else if (c.blocker) blk = `<span class="nd-blk">SID ${esc(c.blocker.sid)} (${esc(c.blocker.username || '-')} · ${esc(c.blocker.status)} · last_call_et ${esc(c.blocker.lastCallEt)}초)</span>`;
                else blk = `<span class="nd-blk">SID ${esc(c.blockingSession)}</span>`;
            }
            secs.push(sec('현재 상태', kv([['STATE', c.state], ['EVENT', c.event], ['WAIT_CLASS', c.waitClass],
                ['대기 시간', c.waitSec ? c.waitSec + '초' : '—'], ['LAST_CALL_ET', c.lastCallEt !== null ? c.lastCallEt + '초' : '—'], ['BLOCKING_SESSION', blk, true]])));
        } else {
            secs.push(sec('현재 상태', `<p class="nd-muted">세션 종료됨 — 마지막 샘플 ${esc(d.lastSample || '-')}</p>`));
        }
        secs.push(sec(`선택 구간 활동 · ${winTxt()}`, activityHtml(d.activity)));
        if (c && c.sqlId) {
            secs.push(sec('현재 SQL', kv([['SQL_ID', `<button type="button" class="nd-link-btn" id="nd-dw-sqllink">${esc(c.sqlId)} ›</button>`, true],
                ['PLAN_HASH_VALUE', c.planHashValue]]) + code(c.sqlFulltext || '(SQL 텍스트 없음)')));
        } else {
            secs.push(sec('현재 SQL', `<p class="nd-muted">${bg ? '백그라운드 프로세스라 실행 중인 사용자 SQL이 없습니다.' : '실행 중인 사용자 SQL이 없습니다.'}</p>`));
        }
        secs.push('<pre class="nd-dw-code" id="nd-dw-copy-manual" hidden></pre>');
        secs.push(queries(d.queries));
        const actions = [];
        if (admin()) {
            const label = 'KILL 구문 복사';
            const b = document.createElement('button');
            b.type = 'button';
            b.className = 'secondary-btn';
            b.textContent = label;
            const stmt = `ALTER SYSTEM KILL SESSION '${d.sid},${d.serial}' IMMEDIATE;`;
            if (!c || bg) { b.disabled = true; b.title = !c ? '이미 종료된 세션입니다' : '백그라운드 세션은 KILL하면 안 됩니다'; }
            else b.addEventListener('click', () => copyText(stmt, b, label));
            actions.push(b);
        }
        return {
            kind: '세션 상세',
            title: `SID ${d.sid}, ${d.serial}`,
            sub: `${pill} <span class="nd-muted">${esc(c ? (c.username || '-') + ' · ' + (c.program || '') : '')} · ${esc(d.instanceName || '')} (이 인스턴스 기준)</span>`,
            secs,
            actions,
            bind: (root) => {
                const link = root.querySelector('#nd-dw-sqllink');
                if (link) link.addEventListener('click', () => pushDrawer(() => viewSql(c.sqlId)));
            }
        };
    }

    // ------------------------------------------------------------------ 6.2 SQL 상세

    async function viewSql(sqlId) {
        const d = await getDetail(`sql/${encodeURIComponent(sqlId)}`);
        const s = d.stats || {};
        const secs = [
            sec('SQL 정보', kv([['SQL_ID', d.sqlId], ['PLAN_HASH_VALUE', s.planHashValue], ['구간 실행 횟수', s.executions],
                ['평균 수행 시간', s.elapsedMsPerExec !== null && s.elapsedMsPerExec !== undefined ? f2(s.elapsedMsPerExec) + ' ms' : '—'],
                ['평균 Buffer Gets', s.bufferGetsPerExec], ['구간 DB Time', s.dbTimeSec !== null && s.dbTimeSec !== undefined ? f2(s.dbTimeSec) + ' 초' : '—']]) +
                (s.samples ? '' : '<p class="nd-muted">이 구간의 SQL 통계 수집값이 없습니다(1분 델타 수집 전이거나 실행이 없었음).</p>')),
            sec(`선택 구간 활동 · ${winTxt()}`, activityHtml(d.activity)),
            sec('SQL 텍스트', code(d.sqlFullText || 'SQL 텍스트 없음(공유 풀에서 밀려남)')),
            sec('실행 세션', sessListHtml(d.sessions)),
            queries(d.queries)
        ];
        return {
            kind: 'SQL 상세',
            title: d.sqlId,
            sub: `<span class="nd-muted">${f2(d.activity.aas)} AAS · 구간 전체의 ${f1(d.activity.pct)}% · ${esc(d.instanceName || '')} (이 인스턴스 기준)</span>`,
            secs,
            bind: (root) => bindSessList(root, d.sessions)
        };
    }

    // ------------------------------------------------------------------ 6.3 이벤트 상세

    async function viewEvent(name) {
        const d = await getDetail(`event?name=${encodeURIComponent(name)}`);
        const a = d.activity || {};
        const cat = a.category;
        const secs = [
            sec('이벤트 정보', kv([['EVENT', d.event], ['대기 클래스', cat ? clsName(cat) : '—'], ['구간 평균 AAS', f2(a.aas)],
                ['구간 비중', f1(a.pct) + '%'], ['구간', winTxt()]])),
            sec('확인할 점', `<p>${esc((ND.classActions || {})[cat] || '—')}</p>`),
            sec('대기 세션', sessListHtml(d.sessions)),
            queries(d.queries)
        ];
        return {
            kind: '대기 이벤트 상세',
            title: d.event,
            sub: `<span class="nd-muted">${esc(cat ? clsName(cat) : '')} · ${f2(a.aas)} AAS · ${esc(d.instanceName || '')} (이 인스턴스 기준)</span>`,
            secs,
            bind: (root) => bindSessList(root, d.sessions)
        };
    }

    // ⑧ 점검 알림(nd-checks.js)이 같은 드로어·부품을 쓰도록 공개
    ND.drawer = { open: openDrawer, push: pushDrawer, close: closeDrawer };
    ND.drawerKit = { sec, kv, code, queries, esc };

    // ================================================================== 초기 DOM

    function ensureDom() {
        const row = document.querySelector('.nd-top-row');
        if (!row || $('nd-top-src')) return;
        const src = document.createElement('div');
        src.className = 'nd-top-src';
        src.id = 'nd-top-src';
        row.parentNode.insertBefore(src, row);
        ensureDrawer();
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ensureDom);
    else ensureDom();
})();
