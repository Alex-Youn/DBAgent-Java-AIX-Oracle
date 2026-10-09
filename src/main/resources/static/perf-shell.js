// 성능 분석 공통 조건 바 + 하위 탭 프레임 (성능 분석 개편 Phase 0b, 2026-10-09)
// 설계문서 `성능분석 메뉴 설계.md` 2.2·2.3·3.3·3.6. perf-analysis.js(세션 분석)보다 먼저 로드한다(전역 getToken 사용).
//
// 맡는 일: 시작·종료 입력, 빠른 선택(15분~24시간), 이전/다음, 계정·접속 호스트 필터, 검증 문구, DB 시각(db_now) 기준 계산,
// 접속 컨테이너 표시, 드래그 선택 구간 보관, URL(#history/{sub}?db_id=&from=&to=) 복원, 하위 탭 전환.
// 하위 탭 모듈(perf-analysis.js = 세션 분석, 이후 perf-lock.js …)은 PerfShell.onSearch(ctx)를 구독해 데이터를 그린다.
// 시각은 전부 DB 시각이다(브라우저·DB 시간대가 달라도 맞게 db_now 오프셋으로 계산).
(function () {
    'use strict';

    const $ = (id) => document.getElementById(id);
    const pad = (n) => String(n).padStart(2, '0');
    const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    // DB 시각 문자열 <-> 차트 x값. 차트는 시간대 변환 없이 "벽시계 그대로" 그리도록 문자열을 로컬 Date로 읽는다.
    const parseLocal = (s) => new Date(String(s).length === 16 ? s + ':00' : s);
    const fmtMinute = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
    const human = (s) => String(s || '').replace('T', ' ').slice(0, 16);
    const enc = encodeURIComponent;

    // 화면 좌표 → 컨테이너 안 좌표. 본문이 zoom 90%라 getBoundingClientRect(확대 반영)와 clientWidth(CSS px)의
    // 비율로 되돌린다 - 모든 성능 분석 차트(드래그 선택·막대 클릭)가 이 함수를 쓴다.
    function toLocal(e, container) {
        const rect = container.getBoundingClientRect();
        const sx = rect.width ? container.clientWidth / rect.width : 1;
        const sy = rect.height ? container.clientHeight / rect.height : 1;
        return {
            x: Math.max(0, Math.min((e.clientX - rect.left) * sx, container.clientWidth)),
            y: Math.max(0, Math.min((e.clientY - rect.top) * sy, container.clientHeight))
        };
    }

    async function getJson(url) {
        const res = await fetch(url + `&token=${enc(getToken())}`);
        const d = await res.json().catch(() => null);
        if (!res.ok || !d || d.error) throw new Error((d && d.error) || ('HTTP ' + res.status));
        return d;
    }

    // 색은 CSS 변수에서 읽는다(하드코딩 금지, 두 테마 모두 style.css 변수).
    function cssVar(name) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    }

    const MAX_RANGE_MIN = 24 * 60;
    // 2026-09-26 오케스트레이터 결정: 1시간 → 15분(초기 자동 조회 부하를 줄이고, 구간이 ASH 보관 범위를 넘어 AWR까지 가지 않게).
    const DEFAULT_RANGE_MS = 15 * 60000;

    const st = {
        dbId: null,        // 조회한 DB
        range: null,       // {from, to} 조회 구간(DB 시각 문자열 yyyy-MM-ddTHH:mm)
        sel: null,         // {from, to} 드래그 선택 구간, 없으면 null(= 전체)
        filter: '',        // &users=..&machines=..
        gen: 0,            // 조회·초기화 세대(늦게 도착한 이전 응답을 버리는 데 쓴다)
        initFor: null,     // 입력칸 기본값을 채운 DB
        nowOffsetMs: 0,    // DB 시각 - 브라우저 시각(db_now 응답 기준)
        nowReady: false,   // 현재 DB의 db_now를 받아 nowOffsetMs가 유효한지(받기 전·DB 전환 직후에는 브라우저 시각으로 계산하면 시간대가 어긋난다)
        conName: null,     // 접속 컨테이너(12c+ CDB: CDB$ROOT 또는 PDB 이름, 11g·조회 불가는 null)
        sub: 'session',    // 현재 하위 탭
        pending: null,     // URL에서 읽은 복원할 조건 {dbId, from, to}
        notice: '',        // 조건 복원을 못 했을 때의 안내(사용자가 다시 조작하기 전까지 입력칸 아래에 남긴다)
        lastMsg: ''        // 마지막 validateRange 문구
    };
    const searchFns = [];
    const resetFns = [];

    // ------------------------------------------------------------------ 시간 계산·검증

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
        st.lastMsg = msg;
        rangeMsg(msg || st.notice);
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

    // ------------------------------------------------------------------ 필터

    function filterParams() {
        const pick = (id) => {
            const el = $(id);
            return el ? Array.from(el.selectedOptions).map(o => o.value).filter(Boolean).join(',') : '';
        };
        const u = pick('history-users');
        const m = pick('history-machines');
        return (u ? `&users=${enc(u)}` : '') + (m ? `&machines=${enc(m)}` : '');
    }

    // 계정·접속 호스트 드롭다운 - 수집 저장소의 보관 기간 안 값(운영 DB 조회 없음). 고른 값이 목록에 있으면 유지한다.
    // 인스턴스 필터는 노출하지 않는다(RAC 묶음 도입 후, 설계 2.1).
    async function loadFilters(dbId) {
        try {
            const d = await getJson(`/api/perf/filters?db_id=${enc(dbId)}`);
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

    // ------------------------------------------------------------------ 메타 줄(조회 구간·데이터·접속 컨테이너)

    // 접속 컨테이너(설계 3.6): PDB 이름을 보여 주고, CDB$ROOT로 등록돼 있으면 막지는 않고 경고만 한다.
    function containerHtml() {
        if (!st.conName) return '';
        if (st.conName === 'CDB$ROOT') {
            return ` · 접속 컨테이너 <b>${esc(st.conName)}</b> <span class="perf-warn">CDB 전체 값이라 PDB별로 구분되지 않습니다</span>`;
        }
        return ` · 접속 컨테이너 <b>PDB ${esc(st.conName)}</b>`;
    }

    // 하위 탭 모듈이 만든 메타 문구 뒤에 공통 항목(접속 컨테이너)을 붙여 표시한다. html은 호출자가 이스케이프한 것.
    function setMeta(html) {
        const el = $('perf-meta');
        if (el) el.innerHTML = html + containerHtml();
    }

    // ------------------------------------------------------------------ 조회

    function search(opts) {
        const dbId = window.currentDbId;
        const from = $('history-start-time').value;
        const to = $('history-end-time').value;
        if (!dbId) return;
        if (!nowOk()) { rangeMsg('DB 현재 시각을 확인하는 중입니다. 잠시 후 다시 눌러 주세요.'); return; }
        if (!validateRange()) return;
        const gen = ++st.gen;
        st.dbId = dbId;
        st.range = { from, to };
        st.sel = null;
        st.filter = filterParams();
        const ctx = {
            dbId, from, to, gen,
            minutes: (parseLocal(to) - parseLocal(from)) / 60000,
            filter: st.filter,
            toggle: !!(opts && opts.toggle)   // 원본/저장소 전환 버튼에서 온 조회(세션 분석만 의미 있음)
        };
        syncUrl();
        refreshGoButtons();
        searchFns.forEach(fn => { try { fn(ctx); } catch (e) { console.error('[DBAgent] perf onSearch 실패:', e); } });
    }

    // 서버가 확정한 구간(종료가 DB 현재 시각보다 뒤면 현재 시각으로 잘림)을 기준으로 삼고 입력칸에도 되돌려 채운다
    // (입력값과 실제 조회 구간이 달라 보이지 않게).
    function confirmRange(from, to) {
        st.range = { from, to };
        $('history-start-time').value = from.slice(0, 16);
        $('history-end-time').value = to.slice(0, 16);
        refreshInputLimits();
        validateRange();
        syncUrl();
        refreshGoButtons();
    }

    function reset() {
        st.gen++;
        st.range = null;
        st.sel = null;
        resetFns.forEach(fn => { try { fn(); } catch (e) { console.error('[DBAgent] perf onReset 실패:', e); } });
        refreshGoButtons();
    }

    // ------------------------------------------------------------------ 선택 구간(드래그) 보관

    function currentWindow() { return st.sel || st.range; }

    function setSel(sel) {
        st.sel = sel;
        refreshGoButtons();
    }

    // ------------------------------------------------------------------ URL 조건 복원 (#history/{sub}?db_id=&from=&to=)

    // 조회할 때마다 현재 조건을 해시에 남긴다(새로고침·링크 공유 시 복원). replaceState라 기록은 쌓이지 않는다.
    function syncUrl() {
        if (!st.range || !st.dbId) return;
        if (window.location.hash.substring(1).split(/[/?]/)[0] !== 'history') return;
        history.replaceState(null, null,
            `#history/${st.sub}?db_id=${enc(st.dbId)}&from=${enc(st.range.from)}&to=${enc(st.range.to)}`);
    }

    function parseQuery(q) {
        const r = {};
        String(q || '').split('&').forEach(kv => {
            if (!kv) return;
            const i = kv.indexOf('=');
            try { r[decodeURIComponent(i < 0 ? kv : kv.slice(0, i))] = i < 0 ? '' : decodeURIComponent(kv.slice(i + 1)); } catch (e) { /* 잘못된 인코딩은 무시 */ }
        });
        return r;
    }

    // 복원할 조건이 있으면 적용하고 조회까지 했을 때 true. URL의 db_id가 현재 DB와 다르면 복원하지 않고 안내만 한다(설계 2.2).
    function applyPending() {
        const p = st.pending;
        if (!p) return false;
        const cur = window.currentDbId;
        if (!cur) return false;
        if (p.dbId && p.dbId !== cur) {
            st.pending = null;
            st.notice = `URL의 DB(${p.dbId})가 현재 선택한 DB(${cur})와 달라 조회 조건을 복원하지 않았습니다.`;
            rangeMsg(st.notice);
            return false;
        }
        if (!nowOk()) return false; // db_now를 받은 뒤 ensureDefaultRange가 다시 부른다
        st.pending = null;
        if (!p.from || !p.to) return false;
        const f = parseLocal(p.from);
        const t = parseLocal(p.to);
        if (isNaN(f.getTime()) || isNaN(t.getTime())) return false;
        setRange(f, t);
        if (!validateRange()) {
            // 막힌 구간(24시간 초과·시작이 미래 등)은 조회하지 않고 사유를 안내한다. 입력칸은 호출자가 기본 구간으로 되돌린다.
            st.notice = `URL의 조회 조건을 적용할 수 없습니다. (${st.lastMsg})`;
            return false;
        }
        search();
        return true;
    }

    // ------------------------------------------------------------------ 기본 구간(DB 시각 최근 15분)

    async function ensureDefaultRange() {
        const dbId = window.currentDbId;
        if (!dbId || st.initFor === dbId) return;
        st.initFor = dbId;
        st.nowReady = false; // DB가 바뀌었으면 이전 DB의 시계 차이를 쓰지 않는다
        st.conName = null;
        try {
            const d = await getJson(`/api/perf/db_now?db_id=${enc(dbId)}`);
            const now = parseLocal(d.dbNow);
            st.nowOffsetMs = now.getTime() - Date.now();
            st.nowReady = true;
            st.conName = d.conName || null;
            const defFrom = new Date(now.getTime() - DEFAULT_RANGE_MS);
            setRange(defFrom, now);
            loadFilters(dbId);
            // URL에 조건이 있으면 그 구간을 복원해 조회하고, 없거나 적용할 수 없으면 기본 구간(최근 15분)을 바로 조회한다
            // (2026-09-26 오케스트레이터 요청: 빈 화면 대신 상단 차트와 하단 event 막대를 보여 준다).
            st.notice = '';
            if (st.pending && applyPending()) return;
            st.pending = null;
            setRange(defFrom, now); // 복원에 실패했으면 입력칸을 기본 구간으로 되돌린다(안내 문구는 st.notice로 남는다)
            if (!st.range && dbId === window.currentDbId) search();
        } catch (e) {
            st.initFor = null; // 다음에 다시 시도
            console.warn('[DBAgent] perf db_now 실패:', e.message);
        }
    }

    // ------------------------------------------------------------------ 하위 탭 프레임 (Phase 0a에서 이전)
    // 해시는 #history/{sub}[?조건]이고 app.js switchTab이 첫 구간만 섹션 id로 쓰며 나머지를 perf:route 이벤트로 넘긴다.
    // 노출된 하위 탭(hidden 아닌 버튼)만 열 수 있고, 없는 탭이면 세션 분석으로 돌린다.

    function subButtons() { return Array.prototype.slice.call(document.querySelectorAll('[data-perf-sub]')); }

    function subAvailable(sub) {
        return subButtons().some(b => b.dataset.perfSub === sub && !b.hidden);
    }

    // "이 구간 ○○ 분석 →" 버튼: 대상 탭이 노출돼 있고 조회한 구간이 있을 때만 켠다.
    function refreshGoButtons() {
        document.querySelectorAll('[data-perf-go]').forEach(b => {
            const ok = subAvailable(b.dataset.perfGo) && !!st.range;
            b.disabled = !ok;
            if (ok) b.removeAttribute('title');
        });
    }

    function showSub(sub) {
        st.sub = sub;
        subButtons().forEach(b => {
            const on = b.dataset.perfSub === sub;
            b.classList.toggle('active', on);
            b.setAttribute('aria-selected', on ? 'true' : 'false');
        });
        document.querySelectorAll('[data-perf-pane]').forEach(p => { p.hidden = p.dataset.perfPane !== sub; });
        refreshGoButtons();
    }

    function onRoute(ev) {
        let sub = (ev && ev.detail && ev.detail.sub) || 'session';
        if (!subAvailable(sub)) {
            // 아직 없는 하위 탭(#history/lock 등)이면 세션 분석으로 - 기록을 쌓지 않도록 replaceState
            sub = 'session';
            if (window.location.hash.indexOf('#history') === 0) history.replaceState(null, null, '#history/session');
        }
        showSub(sub);
        // 해시의 조건(db_id·from·to)을 복원 대기열에 올린다. 아직 db_now 전이면 ensureDefaultRange가 이어서 처리한다.
        const q = parseQuery(ev && ev.detail && ev.detail.query);
        if (q.from && q.to) {
            st.pending = { dbId: q.db_id || '', from: q.from, to: q.to };
            applyPending();
        }
    }

    // 현재 조회·선택 구간을 조건으로 실어 다른 하위 탭으로 이동한다(드래그로 고른 구간이 있으면 그 구간).
    function goSub(sub) {
        if (!subAvailable(sub) || !st.range || !st.dbId) return;
        const w = currentWindow();
        window.location.hash = `#history/${sub}?db_id=${enc(st.dbId)}&from=${enc(w.from)}&to=${enc(w.to)}`;
    }

    // ------------------------------------------------------------------ 연결

    // 사용자가 조건을 직접 바꾸면 복원 실패 안내는 거둔다.
    function userAction() { st.notice = ''; }

    function init() {
        const btn = $('perf-search-btn');
        if (!btn) return;
        btn.addEventListener('click', () => { userAction(); search(); });
        $('history-start-time').addEventListener('change', () => { userAction(); onStartChange(); });
        $('history-end-time').addEventListener('change', () => { userAction(); validateRange(); });
        // 화면을 오래 열어 두면 max(=열 때의 DB 시각)가 낡아 달력에서 최근 시각을 못 고르므로, 입력칸에 들어올 때 갱신한다.
        $('history-start-time').addEventListener('focus', refreshInputLimits);
        $('history-end-time').addEventListener('focus', refreshInputLimits);
        document.querySelectorAll('[data-perf-quick]').forEach(b =>
            b.addEventListener('click', () => { userAction(); quickRange(parseInt(b.dataset.perfQuick, 10)); }));
        const prev = $('perf-range-prev');
        const next = $('perf-range-next');
        if (prev) prev.addEventListener('click', () => { userAction(); shiftRange(-1); });
        if (next) next.addEventListener('click', () => { userAction(); shiftRange(1); });

        window.addEventListener('perf:route', onRoute);
        subButtons().forEach(b => b.addEventListener('click', () => {
            const sub = b.dataset.perfSub;
            if (!subAvailable(sub)) return;
            const target = '#history/' + sub;
            if (window.location.hash.split('?')[0] !== target) window.location.hash = target;
            else showSub(sub);
        }));
        document.querySelectorAll('[data-perf-go]').forEach(b =>
            b.addEventListener('click', () => goSub(b.dataset.perfGo)));
        refreshGoButtons();

        // 메뉴에 들어올 때·DB가 바뀔 때 기본 구간을 DB 시각으로 다시 잡고, 다른 DB 결과는 지운다.
        setInterval(() => {
            if (typeof dbagentSectionVisible === 'function' && !dbagentSectionVisible('history')) return;
            if (st.range && st.dbId !== window.currentDbId) reset();
            ensureDefaultRange();
        }, 1000);
    }

    window.PerfShell = {
        utils: { $, esc, pad, parseLocal, fmtMinute, human, toLocal, getJson },
        cssVar,
        onSearch: (fn) => { searchFns.push(fn); },
        onReset: (fn) => { resetFns.push(fn); },
        search,
        reset,
        confirmRange,
        setMeta,
        rangeMsg,
        setSel,
        getSel: () => st.sel,
        getRange: () => st.range,
        window: currentWindow,
        getDbId: () => st.dbId,
        getFilter: () => st.filter,
        gen: () => st.gen,
        sub: () => st.sub,
        dbNowDate,
        refreshGoButtons
    };

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
