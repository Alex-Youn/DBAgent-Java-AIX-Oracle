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
    // Lock 실패 시 상태바 즉시 확인(checkStatusOnLock) - 10초에 1번까지. lockWasOk는 DB 전환(reset) 때 null로 되돌린다.
    const QUICK_STATUS_MIN_MS = 10000;
    let lockWasOk = null;
    let lastQuickStatusAt = 0;
    let quickStatusBusy = false;
    let quickStatusRecheck = null; // 진행 중에 복구 신호가 오면 끝난 뒤 다시 확인할 ctx
    const resetHooks = [];
    // 패널끼리 데이터를 나눠 쓰는 작은 이벤트 전달 - 예: 'lock'(③ Lock 카드 + ① 활성 세션·메모리 칸이 같은 응답 1개를 씀),
    // 'selection'(② 차트의 선택 구간 → ④ 배너·⑤⑥⑦ Top).
    const listeners = {};
    function on(name, fn) { (listeners[name] = listeners[name] || []).push(fn); }
    function emit(name, data) {
        (listeners[name] || []).forEach(fn => {
            try { fn(data); } catch (e) { console.error(`[DBAgent] 새 대시보드 ${name} 처리 실패:`, e); }
        });
    }

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

    // 403은 "권한 없음"과 "로그인 만료"가 같은 코드로 온다. 서버는 기동할 때마다 로그인 세션을 모두 지우므로(AuthService.init)
    // jar 교체·재기동 뒤 열려 있던 탭은 전부 403을 받아 "권한이 없습니다"로 오해했다(2026-10-01 재배포 후 실제로 발생).
    // 403이면 /api/check-auth로 한 번 확인해, 로그인이 풀렸으면(401) 로그인 화면을 띄운다. 다시 로그인하면 페이지가 새로 뜬다.
    let loginExpired = false;
    let authCheck = null; // 같은 주기의 여러 403이 check-auth를 한 번만 부르게 공유
    function isLoginExpired() {
        if (loginExpired) return Promise.resolve(true);
        if (!authCheck) {
            // 5초 안에 답이 없으면 끊는다 - 응답이 멈추면 이 Promise를 기다리는 갱신 주기 전체가 멈춘다(2026-10-01 코드 점검).
            const ac = typeof AbortController === 'function' ? new AbortController() : null;
            const timer = ac ? setTimeout(() => ac.abort(), 5000) : null;
            authCheck = fetch('/api/check-auth', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: getToken() }),
                signal: ac ? ac.signal : undefined
            }).then(r => r.status === 401).catch(() => false) // 확인 실패·시간 초과는 판단 보류 - 기존 문구 유지
              .finally(() => {
                  if (timer) clearTimeout(timer);
                  setTimeout(() => { authCheck = null; }, 5000);
              });
        }
        return authCheck;
    }
    function showLoginExpired() {
        if (loginExpired) return;
        loginExpired = true;
        // 만료된 토큰으로 3초마다 403만 받는 폴링은 멈춘다. 다시 로그인하면 페이지가 새로 떠서 원래대로 돈다.
        state.auto = false;
        clearTimers();
        showError('로그인이 해제되었습니다. 다시 로그인해 주세요.');
        const overlay = document.getElementById('login-overlay');
        const err = document.getElementById('login-error');
        if (err) {
            // 401은 재기동뿐 아니라 세션 만료(session-ttl-days)·계정 삭제에도 온다 - 원인을 단정하지 않는다(2026-10-01 코드 점검).
            err.textContent = '로그인이 해제되었습니다(서버 재기동·세션 만료 등). 다시 로그인해 주세요.';
            err.style.display = 'block';
        }
        if (overlay) overlay.style.display = 'flex';
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
                    if (await isLoginExpired()) {
                        showLoginExpired();
                        return null;
                    }
                    if (ctx.isStale()) return null;
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
        lockWasOk = null;
        quickStatusRecheck = null;
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

    // .app-container의 zoom(현재 90%) 때문에 CSS vh만으로는 정확히 맞지 않는다 - 실제 화면 배율을 재서 "창 아래까지"
    // 높이를 준다. 배율 값을 하드코딩하지 않는다(2.4: zoom 값이 바뀌어도 동작).
    // 아래 여백은 좌우와 같은 프레임 여백(style.css --frame-gutter)을 본문(.content-area)에서 읽어 쓴다 - 예전엔 화면 12px 고정
    // (2026-10-01 오케스트레이터 지시: 프레임 여백 1~1.5mm). 본문 아래 여백은 style.css가 대시보드에서만 같은 값으로 줄인다.
    function fitHeight() {
        const el = $('dashboard-nd-view');
        if (!el || el.style.display === 'none') return;
        const rect = el.getBoundingClientRect();
        const scale = el.offsetHeight > 0 ? rect.height / el.offsetHeight : 1;
        const area = el.closest('.content-area');
        const gutter = (area && parseFloat(getComputedStyle(area).paddingLeft)) || 0;
        // 화면 전환 애니메이션(.content-section fadeIn 0.4초, 10px 아래에서 올라옴) 도중에 재면 그만큼 아래에 있는 위치로 계산돼
        // 높이가 모자랐다 - 처음 열 때·메뉴 전환 때 아래가 화면 약 8px 더 비었다(2026-10-01 실측: 아래 13.6px, 창 크기를 바꾸면 5.4px).
        // 지금 걸려 있는 이동량(CSS px)을 읽어 빼 준다.
        const section = el.closest('.content-section');
        const tf = section ? getComputedStyle(section).transform : 'none';
        const shift = tf && tf !== 'none' && typeof DOMMatrixReadOnly === 'function' ? new DOMMatrixReadOnly(tf).m42 : 0;
        const available = (window.innerHeight - rect.top) / (scale || 1) + shift - gutter;
        el.style.height = Math.max(400, Math.floor(available)) + 'px';
        fitChart();
    }
    window.addEventListener('resize', () => { if (state.active) fitHeight(); });

    // ②③ 차트 행 높이: 남는 높이를 모두 차지하고 최소 180px (2026-09-28 결정, UI개선_mockup.html - 기존 300~400px 대체).
    // 180px 아래로는 줄이지 않는다(넘치면 대시보드 영역 안에서 스크롤).
    // 부하·데이터가 아니라 "대시보드 영역 높이 - 나머지 블록 높이"로만 정해지므로 같은 창에서는 크기가 흔들리지 않는다.
    // 단, Lock 장애 알림 바가 떠 있을 때만 최소 270px - 1920×1080에서 180px이면 Lock 카드 본문이 116px로
    // 통계+알림 바+범례+차트가 안 들어가 차트 축이 카드 밖으로 넘친다. 평소엔 180 유지(스크롤 증가는 장애 때만)
    // (2026-09-28 오케스트레이터 결정, 1920×1080 실측).
    //
    // B안 세로 압축(2026-09-29 오케스트레이터 결정): 보통 크기로는 차트가 최소 높이보다 작아지는 창(1920×1080 등)에서만
    // .nd-compact를 붙여 Top 목록 3행 + 점검 알림 한 줄로 줄인다. 판단은 매번 "압축하지 않았을 때" 높이로 한다 - 클래스를
    // 잠깐 떼고 재서 같은 프레임 안에 다시 붙이므로 화면에 깜박임은 없다. 경계에서 켜졌다 꺼졌다 하지 않게 풀 때는 여유 8px.
    //
    // 그래프 영역 최소 150px(2026-09-29 오케스트레이터 결정): 위 180/270은 카드 높이라 카드 안 제목·범례·Lock 통계를 빼면
    // 실제 그래프는 훨씬 작다 - 브라우저 125% 확대에서 Lock 그래프 90px·AAS 127px로 납작해졌다(CSS px, 배율 90% 전 값).
    // 카드 안 "그래프 말고 나머지" 높이를 매번 재서(고정 숫자 아님 - 장애 알림 바·통계 줄바꿈 반영) 두 그래프 모두
    // 150px 이상이 되게 최소 높이를 올린다. 1920×1080 100%(압축 상태 Lock 그래프 약 156px)에는 영향 없고, 확대했을 때만 스크롤.
    const CHART_MIN = 180, CHART_MIN_INCIDENT = 270, CHART_MAX = Infinity, COMPACT_HYST = 8, PLOT_MIN = 150;
    // 압축을 푸는(Top 5행·점검 알림 전체) 기준 - 풀어도 차트 행이 이 높이 이상 남을 때만 푼다(2026-10-01 오케스트레이터 결정).
    // 예전엔 "차트가 최소(180)만 넘으면 푼다"라 브라우저 90%(1920×1080에서 CSS 화면 약 2133×1059)에서 압축이 풀려 Top이 127px
    // 커지고 차트는 100%(334)보다 오히려 작아졌다(324, 화면 기준 약 13% 축소). 생긴 공간은 차트에 먼저 주고, 큰 화면(2560×1440 등)만 5행.
    const CHART_EXPAND_MIN = 360;
    const PLOT_IDS = ['nd-aas-plot', 'nd-lk-plot'];
    function plotOverhead() {
        let over = 0;
        for (const id of PLOT_IDS) {
            const plot = $(id);
            const card = plot && plot.closest('.nd-card');
            if (!card || plot.offsetParent === null) continue; // 아직 안 그려졌거나 표 보기로 숨김
            // 카드가 좁아 내용이 넘치면 scrollHeight가 실제 내용 높이다. 테두리(offset - client)도 더한다.
            over = Math.max(over, card.scrollHeight + (card.offsetHeight - card.clientHeight) - plot.offsetHeight);
        }
        return over;
    }
    function fitChart() {
        const el = $('dashboard-nd-view');
        const row = el && el.querySelector('.nd-chart-row');
        if (!row || el.style.display === 'none') return;
        observeChartCards(row);
        const incBar = $('nd-inc-bar');
        const base = incBar && !incBar.hidden ? CHART_MIN_INCIDENT : CHART_MIN;
        const over = plotOverhead();
        const min = over > 0 ? Math.max(base, Math.ceil(over + PLOT_MIN)) : base;
        const gap = parseFloat(getComputedStyle(el).rowGap) || 0;
        const free = () => {
            let others = 0, count = 0;
            for (const c of el.children) {
                if (c === row || c.offsetParent === null) continue;
                others += c.offsetHeight; count++;
            }
            return Math.floor(el.clientHeight - others - gap * count);
        };
        const wasCompact = el.classList.contains('nd-compact');
        el.classList.remove('nd-compact');
        const normal = free();
        const compact = normal < Math.max(min, CHART_EXPAND_MIN) + (wasCompact ? COMPACT_HYST : 0);
        el.classList.toggle('nd-compact', compact);
        const h = Math.max(min, Math.min(CHART_MAX, compact ? free() : normal));
        if (row.style.height !== h + 'px') { row.style.height = h + 'px'; row.style.flexBasis = h + 'px'; }
        // 행은 flex 항목(flex-shrink 1)이라 공간이 모자라면 CSS min-height(180px)까지 줄어든다 - 계산한 최소값도 min-height로
        // 걸어야 그래프 150px·장애 270px이 지켜진다(2026-09-29 실측: 287px을 줘도 125%에서 219px로 줄어듦).
        if (row.style.minHeight !== min + 'px') row.style.minHeight = min + 'px';
    }
    // 다른 블록 높이가 바뀌면(⑧ 카드 줄 수, 상태바 줄바꿈 등) 다시 맞춘다.
    const fitRo = typeof ResizeObserver === 'function' ? new ResizeObserver(() => { if (state.active) fitChart(); }) : null;
    if (fitRo) {
        const view = $('dashboard-nd-view');
        if (view) for (const c of view.children) if (!c.classList.contains('nd-chart-row')) fitRo.observe(c);
    }
    // 차트 카드 안의 그래프 말고 나머지(제목·범례·Lock 통계·장애 알림 바)도 지켜본다 - 이 높이가 그래프 최소 150px 계산에
    // 들어간다. 카드 내용은 각 패널 스크립트가 나중에 만들므로 fitChart 때마다 새로 생긴 요소만 추가한다. 그래프(.nd-plot)와
    // 그것을 담은 .nd-chart-body, AAS 표 보기(.nd-aas-table)는 행 높이를 따라 바뀌므로 뺀다(넣으면 높이 맞춤이 자기 자신을 다시 부른다).
    const fitObserved = new WeakSet();
    const FIT_SKIP = ['nd-chart-body', 'nd-plot', 'nd-aas-table'];
    function observeChartCards(row) {
        if (!fitRo) return;
        row.querySelectorAll('.nd-card > *, .nd-chart-body > *').forEach(n => {
            if (fitObserved.has(n) || FIT_SKIP.some(c => n.classList.contains(c))) return;
            fitObserved.add(n);
            fitRo.observe(n);
        });
    }

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

    // 서버(앱) 자체가 죽으면 fetchJson이 네트워크 에러를 그대로 던지는데, loadStatus에는 이걸 잡는
    // 코드가 없어서 run('slow')의 Promise.allSettled가 콘솔에만 에러를 찍고 화면은 마지막으로
    // 성공했을 때의 "Alive"가 그대로 남아있었다(원본 DBAgent-Java에서 포팅, 2026-09-28). loadLock처럼
    // 실패를 명시적으로 화면에 반영한다.
    function markStatusDown() {
        setText('nd-st-instance', 'Not Alive');
        setText('nd-st-listener', 'Not Alive');
        ['nd-st-max-session', 'nd-st-active', 'nd-st-inactive', 'nd-st-max-process', 'nd-st-dedicated', 'nd-st-shared']
            .forEach(id => setText(id, null));
        const id = $('nd-st-instance-dot'); if (id) id.style.background = 'var(--nd-crit)';
        const ld = $('nd-st-listener-dot'); if (ld) ld.style.background = 'var(--nd-crit)';
    }

    async function loadStatus(ctx) {
        let h;
        try {
            h = await ctx.fetchJson('/api/health', { db_id: ctx.dbId });
        } catch (e) {
            if (!ctx.isStale()) {
                markStatusDown();
                if (window.dbagentSetSidebarHealth) window.dbagentSetSidebarHealth(ctx.dbId, 'Not Alive');
            }
            return;
        }
        if (!h || ctx.isStale()) return;
        // 좌측 트리의 선택된 DB 색도 같은 결과로 맞춘다(app.js dbagentSetSidebarHealth).
        if (window.dbagentSetSidebarHealth) window.dbagentSetSidebarHealth(ctx.dbId, h.instance_status);
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

    // ③ Lock 실시간 + ① 활성 세션·메모리 - 빠른 주기에 한 번만 조회해 'lock' 이벤트로 나눠 준다(F2 API).
    // 실패하면 'lock'에 {ok:false}를 보내 각 패널이 "판단 보류"로 표시하게 한다(0으로 그리지 않음).
    async function loadLock(ctx) {
        let d;
        try {
            d = await ctx.fetchJson('/api/dashboard/' + encodeURIComponent(ctx.dbId) + '/lock/realtime');
        } catch (e) {
            if (!ctx.isStale()) {
                emit('lock', { ok: false, error: e.message });
                checkStatusOnLock(ctx, false);
            }
            return;
        }
        if (d && !ctx.isStale()) {
            emit('lock', d);
            checkStatusOnLock(ctx, d.ok !== false);
        }
    }
    tasks.fast.push({ name: 'lock', fn: loadLock });

    // 상태바(헬스체크)는 느린 주기(60초)라 DB가 죽어도 Not Alive까지 최대 1분 넘게 걸렸다(2026-09-29 오케스트레이터
    // 지적: "Not Alive 판정이 너무 오래 걸린다"). 빠른 주기 Lock 조회가 실패하면 그때 헬스체크를 앞당기고(10초에 1번까지),
    // 실패하던 Lock이 다시 성공하면 한 번 더 불러 Alive로 빨리 되돌린다. 평소(Lock 정상)에는 추가 조회가 없다.
    // Lock 쿼리 타임아웃(3초)처럼 DB가 살아 있는데 실패한 경우도 헬스체크 한 번이면 Alive로 확인된다.
    // (상태 변수 lockWasOk·lastQuickStatusAt·quickStatusBusy는 reset()이 쓰므로 파일 위쪽 state 옆에 둔다.)
    function checkStatusOnLock(ctx, ok) {
        const recovered = ok && lockWasOk === false;
        lockWasOk = ok;
        if (ok && !recovered) return;
        if (quickStatusBusy) {
            // 죽어 있을 때 보낸 헬스체크(접속 제한 시간만큼 걸림)가 아직 안 끝났는데 DB가 살아났다 - 그 응답은 Not Alive로
            // 끝나므로, 끝난 뒤 한 번 더 확인한다(예전엔 이 복구 신호가 버려져 최대 60초 Not Alive로 남았다, 2026-09-29 점검).
            if (recovered) quickStatusRecheck = ctx;
            return;
        }
        if (!recovered && Date.now() - lastQuickStatusAt < QUICK_STATUS_MIN_MS) return;
        runQuickStatus(ctx);
    }
    function runQuickStatus(ctx) {
        quickStatusBusy = true;
        lastQuickStatusAt = Date.now();
        // 빠른 주기를 붙잡지 않게 기다리지 않는다(헬스체크는 죽은 DB에서 접속 제한 시간만큼 걸린다).
        loadStatus(ctx).catch(e => console.error('[DBAgent] 새 대시보드 상태 즉시 확인 실패:', e))
            .finally(() => {
                quickStatusBusy = false;
                const again = quickStatusRecheck;
                quickStatusRecheck = null;
                if (again && !again.isStale()) runQuickStatus(again);
            });
    }

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
        on,
        emit,
        /** DB 전환·표시 시작 때 호출 - 패널을 스켈레톤으로 되돌리고 드로어·확인 패널을 닫는다. */
        onReset(fn) { resetHooks.push(fn); },
        /** 차트 행 높이를 다시 맞춘다 - 차트 행 안의 변화(Lock 장애 알림 바 표시 등)는 ResizeObserver가 못 잡는다. */
        fitChart() { if (state.active) fitChart(); },
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
