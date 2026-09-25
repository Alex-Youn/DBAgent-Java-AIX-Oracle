// 새 대시보드(설계문서 `대시보드 UI 개선 설계.md`, 전체 작업순서 F4 - 2026-09-25) - 공통 폴링 엔진과 상태바.
// 패널(① KPI · ② AAS · ③ Lock · ④ 진단 · ⑤⑥⑦ Top · ⑧ 점검)은 F5~F8에서 ndDashboard.register()로 붙인다.
//
// - 빠른 주기(리프레쉬 주기, 기본 3초, 2~60초 보정): ③ Lock 실시간 + ① 활성 세션·메모리
// - 느린 주기(60초 = 수집 주기): 상태바, ① ② ④ ⑤ ⑥ ⑦ ⑧
// - DB 전환: 진행 중 요청을 AbortController로 끊고, 세대 번호로 늦게 온 응답을 버린다. 드로어·패널은 reset 훅에서 닫는다.
// - 화면 숨김(document.hidden)·다른 메뉴로 이동하면 폴링을 멈추고, 돌아오면 한 번 갱신한 뒤 재개한다.
// - 403(DB 권한 없음)은 한 줄로 표시한다.
// app.js의 getToken()·window.currentDbId에 의존한다(이 파일은 app.js 다음에 로드).
(function () {
    'use strict';

    const RANGE_MS = { '15m': 15 * 60000, '1h': 60 * 60000, '3h': 3 * 60 * 60000, '24h': 24 * 60 * 60000 };
    const SLOW_MS = 60000;
    const REFRESH_MIN = 2;
    const REFRESH_MAX = 60;

    const state = {
        active: false,      // 스위치에서 새 대시보드를 고른 상태
        dbId: null,
        gen: 0,             // DB 전환·구간 변경 세대
        range: '1h',
        refreshSec: 3,
        auto: true,
        controller: null,
        fastTimer: null,
        slowTimer: null
    };
    const tasks = { fast: [], slow: [] };
    const resetHooks = [];

    const $ = (id) => document.getElementById(id);

    function isVisible() {
        const section = $('dashboard');
        return state.active && !document.hidden && !!section && section.classList.contains('active') && !!window.currentDbId;
    }

    // ------------------------------------------------------------------ 오류 한 줄

    function showError(msg) {
        const el = $('nd-error');
        if (!el) return;
        el.textContent = msg || '';
        el.style.display = msg ? 'block' : 'none';
    }

    // ------------------------------------------------------------------ 요청

    function makeCtx() {
        const gen = state.gen;
        const dbId = window.currentDbId;
        const signal = state.controller ? state.controller.signal : undefined;
        const ctx = {
            dbId,
            gen,
            signal,
            range: state.range,
            rangeMs: RANGE_MS[state.range],
            refreshSec: state.refreshSec,
            isStale: () => gen !== state.gen || dbId !== window.currentDbId || !state.active,
            /** GET JSON - token을 붙이고, 403은 한 줄 표시 후 null, 오래된 응답은 null. */
            async fetchJson(path, params) {
                const qs = new URLSearchParams(params || {});
                qs.set('token', getToken());
                const url = path + (path.indexOf('?') >= 0 ? '&' : '?') + qs.toString();
                let res;
                try {
                    res = await fetch(url, { signal });
                } catch (e) {
                    if (e && e.name === 'AbortError') return null;
                    throw e;
                }
                if (ctx.isStale()) return null;
                if (res.status === 403) {
                    showError('이 DB에 대한 조회 권한이 없습니다.');
                    return null;
                }
                const body = await res.json().catch(() => null);
                if (ctx.isStale()) return null;
                if (!res.ok) {
                    throw new Error((body && body.error) || ('HTTP ' + res.status));
                }
                return body;
            }
        };
        return ctx;
    }

    async function run(kind) {
        if (!isVisible()) return;
        const ctx = makeCtx();
        const results = await Promise.allSettled(tasks[kind].map(t => Promise.resolve().then(() => t.fn(ctx))));
        results.forEach((r, i) => {
            if (r.status === 'rejected') console.error(`[DBAgent] 새 대시보드 ${tasks[kind][i].name} 실패:`, r.reason);
        });
    }

    // ------------------------------------------------------------------ 스케줄

    function clearTimers() {
        if (state.fastTimer) { clearTimeout(state.fastTimer); state.fastTimer = null; }
        if (state.slowTimer) { clearTimeout(state.slowTimer); state.slowTimer = null; }
    }

    // 이전 호출이 끝난 뒤에만 다음을 예약한다(느린 DB에서 요청이 겹쳐 쌓이지 않게 - Current Session 폴링과 같은 원칙).
    function scheduleFast() {
        if (state.fastTimer) clearTimeout(state.fastTimer);
        state.fastTimer = null;
        if (!state.auto || !isVisible()) return;
        const myGen = state.gen;
        state.fastTimer = setTimeout(async () => {
            await run('fast');
            if (myGen === state.gen) scheduleFast();
        }, state.refreshSec * 1000);
    }

    function scheduleSlow() {
        if (state.slowTimer) clearTimeout(state.slowTimer);
        state.slowTimer = null;
        if (!state.auto || !isVisible()) return;
        const myGen = state.gen;
        state.slowTimer = setTimeout(async () => {
            await run('slow');
            if (myGen === state.gen) scheduleSlow();
        }, SLOW_MS);
    }

    /** 지금 한 번 전체 갱신하고 주기를 다시 건다. 같은 세대에서 이미 진행 중이면 그것을 기다린다(메뉴 복귀 훅과
     *  DB 선택 훅이 동시에 불러 두 번 도는 것 방지 - 2026-09-25 CDP 확인). */
    let refreshing = null;
    function refreshNow() {
        if (refreshing && refreshing.gen === state.gen) return refreshing.promise;
        clearTimers();
        const gen = state.gen;
        const promise = Promise.all([run('fast'), run('slow')]).then(() => {
            if (refreshing && refreshing.promise === promise) refreshing = null;
            if (gen === state.gen) { scheduleFast(); scheduleSlow(); }
        });
        refreshing = { gen, promise };
        return promise;
    }

    /** DB 전환·표시 시작: 진행 중 요청 중단 + 세대 증가 + 패널 초기화(스켈레톤, 드로어 닫기) 후 즉시 조회. */
    function reset() {
        if (state.controller) state.controller.abort();
        state.controller = typeof AbortController === 'function' ? new AbortController() : null;
        state.gen++;
        state.dbId = window.currentDbId;
        clearTimers();
        showError('');
        resetHooks.forEach(fn => {
            try { fn(); } catch (e) { console.error('[DBAgent] 새 대시보드 reset 훅 실패:', e); }
        });
        resetStatusBar();
        if (isVisible()) refreshNow();
    }

    function pause() {
        clearTimers();
    }

    function resume() {
        if (!isVisible()) return;
        if (state.dbId !== window.currentDbId) { reset(); return; }
        refreshNow();
    }

    // ------------------------------------------------------------------ 높이 맞춤 (2.2)

    // .app-container의 zoom(현재 80%) 때문에 CSS vh만으로는 정확히 맞지 않는다 - 실제 화면 배율을 재서 "창 아래까지"
    // 높이를 준다. 배율 값을 하드코딩하지 않는다(2.4: zoom 값이 바뀌어도 동작).
    function fitHeight() {
        const el = $('dashboard-nd-view');
        if (!el || el.style.display === 'none') return;
        const rect = el.getBoundingClientRect();
        const scale = el.offsetHeight > 0 ? rect.height / el.offsetHeight : 1;
        const available = (window.innerHeight - rect.top - 12) / (scale || 1);
        el.style.height = Math.max(400, Math.floor(available)) + 'px';
    }
    window.addEventListener('resize', () => { if (state.active) fitHeight(); });

    // ------------------------------------------------------------------ 상태바 (느린 주기)

    function statusColor(status) {
        return status === 'Alive' ? 'var(--primary)' : (status === 'Busy' ? 'var(--nd-warn)' : 'var(--nd-crit)');
    }

    function setText(id, v) {
        const el = $(id);
        if (el) el.textContent = (v === null || v === undefined || v === '') ? '--' : String(v);
    }

    function resetStatusBar() {
        ['nd-st-instance', 'nd-st-listener', 'nd-st-max-session', 'nd-st-active', 'nd-st-inactive',
            'nd-st-max-process', 'nd-st-dedicated', 'nd-st-shared'].forEach(id => setText(id, null));
        ['nd-st-instance-dot', 'nd-st-listener-dot'].forEach(id => { const d = $(id); if (d) d.style.background = ''; });
    }

    async function loadStatus(ctx) {
        const h = await ctx.fetchJson('/api/health', { db_id: ctx.dbId });
        if (!h) return;
        setText('nd-st-instance', h.instance_status);
        setText('nd-st-listener', h.listener_status);
        const id = $('nd-st-instance-dot'); if (id) id.style.background = statusColor(h.instance_status);
        const ld = $('nd-st-listener-dot'); if (ld) ld.style.background = statusColor(h.listener_status);
        setText('nd-st-max-session', h.max_sessions);
        setText('nd-st-active', h.active_sessions);
        setText('nd-st-inactive', h.inactive_sessions);
        setText('nd-st-max-process', h.max_processes);
        setText('nd-st-dedicated', h.dedicated_sessions);
        setText('nd-st-shared', h.shared_sessions);
        // 기존 화면처럼 상단 DB 이름도 갱신(기존 화면 폴링은 새 대시보드가 보이는 동안 멈춰 있다).
        const dbNameEl = $('current-db-name');
        if (dbNameEl && h.db_name) dbNameEl.innerText = h.db_name;
        if (h.error_message) showError('DB 상태 확인 실패: ' + h.error_message); else if ($('nd-error') && $('nd-error').textContent.indexOf('DB 상태') === 0) showError('');
    }
    tasks.slow.push({ name: 'status', fn: loadStatus });

    // ------------------------------------------------------------------ 컨트롤

    function updateLiveLabel() {
        const el = $('nd-lock-live');
        if (!el) return;
        el.textContent = state.auto ? `LIVE · ${state.refreshSec}초 주기` : '일시정지됨';
        el.classList.toggle('paused', !state.auto);
    }

    function bindControls() {
        const range = $('nd-range');
        if (range) {
            range.querySelectorAll('button[data-range]').forEach(btn => {
                btn.addEventListener('click', () => {
                    const r = btn.getAttribute('data-range');
                    if (!RANGE_MS[r] || r === state.range) return;
                    state.range = r;
                    range.querySelectorAll('button').forEach(b => b.classList.toggle('active', b === btn));
                    state.gen++; // 이전 구간 응답 폐기
                    if (isVisible()) {
                        clearTimers();
                        run('slow').then(() => { scheduleFast(); scheduleSlow(); });
                    }
                });
            });
        }
        const input = $('nd-refresh-sec');
        if (input) {
            input.addEventListener('change', () => {
                let v = parseInt(input.value, 10);
                if (isNaN(v)) v = 3;
                v = Math.min(REFRESH_MAX, Math.max(REFRESH_MIN, v)); // 범위 밖은 가장 가까운 경계로
                input.value = String(v);
                state.refreshSec = v;
                updateLiveLabel();
                scheduleFast(); // 다음 주기부터 바로 적용
            });
        }
        const refreshBtn = $('nd-refresh-btn');
        if (refreshBtn) refreshBtn.addEventListener('click', () => refreshNow());
        const toggleBtn = $('nd-toggle-btn');
        if (toggleBtn) {
            toggleBtn.addEventListener('click', () => {
                state.auto = !state.auto;
                toggleBtn.textContent = state.auto ? '자동 갱신 중지' : '자동 갱신 재개';
                toggleBtn.classList.toggle('danger-btn', state.auto);
                toggleBtn.classList.toggle('primary-btn', !state.auto);
                updateLiveLabel();
                if (state.auto) refreshNow(); else clearTimers();
            });
        }
        document.addEventListener('visibilitychange', () => {
            if (document.hidden) pause(); else resume();
        });
        // 다른 메뉴(Current Session 등)로 가면 멈춘다. DASHBOARD로 돌아올 때의 1회 갱신·재개는 app.js가 이미 50ms 뒤
        // fetchActiveDashboardView() → onDbChange()로 부르므로 여기서는 높이만 맞춘다(두 번 도는 것 방지).
        document.querySelectorAll('.nav-item[data-target]').forEach(nav => {
            nav.addEventListener('click', () => setTimeout(() => {
                if (isVisible()) fitHeight(); else pause();
            }, 0));
        });
        updateLiveLabel();
    }

    // ------------------------------------------------------------------ 공개 API

    window.ndDashboard = {
        state,
        /** kind: 'fast'(리프레쉬 주기) | 'slow'(60초). fn(ctx)는 ctx.fetchJson()으로 조회하고 ctx.isStale()이면 그리지 않는다. */
        register(kind, name, fn) { tasks[kind].push({ name, fn }); },
        /** DB 전환·표시 시작 때 호출 - 패널을 스켈레톤으로 되돌리고 드로어·확인 패널을 닫는다. */
        onReset(fn) { resetHooks.push(fn); },
        show() {
            state.active = true;
            fitHeight();
            reset();
        },
        hide() {
            state.active = false;
            clearTimers();
            if (state.controller) state.controller.abort();
        },
        /** app.js의 DB 선택 직후 훅(fetchActiveDashboardView)에서 부른다. */
        onDbChange() {
            if (state.active && state.dbId !== window.currentDbId) reset();
            else if (state.active) resume();
        },
        refreshNow,
        rangeMs: () => RANGE_MS[state.range]
    };

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bindControls);
    else bindControls();
})();
