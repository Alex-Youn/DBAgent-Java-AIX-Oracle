
// --- Theme Logic ---
// Applied at script-load time (not inside DOMContentLoaded) so the correct theme paints as early as
// possible instead of flashing the default dark theme first.
(function applySavedTheme() {
    const saved = localStorage.getItem('dbagent_theme') || 'dark';
    if (saved === 'light') document.documentElement.setAttribute('data-theme', 'light');
})();

function isLightTheme() {
    return document.documentElement.getAttribute('data-theme') === 'light';
}

// Chart.js configs below hardcode axis/grid colors as shades of white. Both theme slots are dark
// backgrounds now (2026-08-28: the "light" toggle slot was changed from white back to the original
// navy dark palette per user request), so this always returns the white-based shade regardless of
// isLightTheme() - there is no longer an actual light/white background to contrast against. Only
// for chart chrome (ticks/grid/borders/titles), not series colors (those stay theme-neutral).
function chartLineColor(alpha) {
    return `rgba(255, 255, 255, ${alpha})`;
}

// --- Auth Logic ---
const API_BASE_AUTH = `/api`;

async function checkAuth() {
    const token = sessionStorage.getItem('dbagent_token');
    if (!token) {
        document.getElementById('login-overlay').style.display = 'flex';
        return false;
    }
    try {
        const res = await fetch(`${API_BASE_AUTH}/check-auth`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token })
        });
        if (res.ok) {
            const data = await res.json();
            if (data.authenticated) {
                document.getElementById('login-overlay').style.display = 'none';
                document.querySelector('.user-name').textContent = data.username;
                sessionStorage.setItem('dbagent_role', data.role || 'user');
                sessionStorage.setItem('dbagent_account_hidden_menus', JSON.stringify(data.hidden_menus || []));
                sessionStorage.setItem('dbagent_account_hidden_dbs', JSON.stringify(data.hidden_dbs || []));
                sessionStorage.setItem('dbagent_fleet_overview', data.fleet_overview ? 'true' : 'false');
                sessionStorage.setItem('dbagent_fleet_overview_auto_redirect', data.fleet_overview_auto_redirect ? 'true' : 'false');
                return true;
            }
        }
    } catch (e) {
        console.error('Auth check error', e);
    }
    document.getElementById('login-overlay').style.display = 'flex';
    return false;
}

function isAdmin() {
    return sessionStorage.getItem('dbagent_role') === 'admin';
}

// Fleet Overview access: admin always has it; other accounts only if an admin granted it (계정 관리 >
// "Fleet Overview 접근 허용", AuthService.canAccessFleetOverview - the backend is the actual gate via
// /api/fleet_status's 403, this is just for hiding the entry points a account can't use anyway.
function canFleetOverview() {
    return isAdmin() || sessionStorage.getItem('dbagent_fleet_overview') === 'true';
}

// Personal preference, independent of the access permission above (사용자 요청: "admin 권한도 진입
// 옵션 선택할 수 있나") - admin always has fleet_overview access but can still opt out of the
// post-login auto-jump for themselves; a granted non-admin account can do the same.
function wantsFleetOverviewAutoRedirect() {
    return sessionStorage.getItem('dbagent_fleet_overview_auto_redirect') === 'true';
}

function getToken() {
    return sessionStorage.getItem('dbagent_token') || '';
}

// DB 전환 레이스 방지 공용 유틸(체크리스트 1-5, 2026-09-25) - 화면 하나(목록/차트)당 하나씩 만든다.
// 요청을 시작할 때 begin()으로 세대 번호와 그때의 DB를 잡아 두고, 응답이 오면 isStale()로 "그 사이
// 같은 화면에 더 새 요청이 나갔거나 DB가 바뀌었는지" 확인해 늦게 온 이전 응답을 버린다. 이전 DB
// 응답이 새 DB 화면을 덮어쓰던 버그(테이블스페이스 7-1, Current Session 1-5)를 막는 패턴이며, 대시보드
// 개편(9-2)도 이것을 재사용한다. invalidate()는 새 요청 없이 진행 중인 응답만 무효화할 때 쓴다.
function dbagentLatestRequest() {
    let seq = 0;
    return {
        begin() {
            const my = ++seq;
            const dbId = window.currentDbId;
            return {
                dbId,
                isLatest: () => my === seq,
                isStale: () => my !== seq || dbId !== window.currentDbId
            };
        },
        invalidate() { seq++; }
    };
}

// Auth event listeners moved to DOMContentLoaded
// ----------------------

;(async function initApp() {
    try {
        // Caps Lock 상태 안내 (로그인 비밀번호 입력창)
        const loginPasswordInput = document.getElementById('login-password');
        const loginCapslockHint = document.getElementById('login-password-capslock');
        if (loginPasswordInput && loginCapslockHint) {
            const updateCapslockHint = (e) => {
                const isOn = typeof e.getModifierState === 'function' && e.getModifierState('CapsLock');
                loginCapslockHint.style.display = isOn ? 'block' : 'none';
            };
            loginPasswordInput.addEventListener('keydown', updateCapslockHint);
            loginPasswordInput.addEventListener('keyup', updateCapslockHint);
            loginPasswordInput.addEventListener('blur', () => { loginCapslockHint.style.display = 'none'; });
        }

        // 비밀번호 표시/숨김 토글 (사용자 요청, 2026-09-02) - input type을 text/password로 바꾸고
        // 버튼에 .is-showing을 토글해 eye/eye-off 아이콘 표시만 전환 (CSS 쪽 패턴은 style.css 참고).
        document.getElementById('login-password-toggle')?.addEventListener('click', () => {
            const input = document.getElementById('login-password');
            const btn = document.getElementById('login-password-toggle');
            const showing = input.type === 'text';
            input.type = showing ? 'password' : 'text';
            btn.classList.toggle('is-showing', !showing);
        });

        // 계정 저장 (사용자 요청, 2026-08-31): 로그인 화면에 admin이 디폴트로 박혀있던 것 제거하고,
        // 대신 "계정 저장" 체크 시 성공한 로그인의 username만 localStorage에 남겨뒀다가 다음 방문 때
        // 입력칸에 미리 채워준다 - 비밀번호는 저장 대상이 아님. localStorage 사용 이유: 로그인 화면은
        // sessionStorage가 아직 없는 시점(로그아웃 상태)에도 채워져야 하고, 브라우저를 완전히 닫았다
        // 재방문해도 유지돼야 하는 값이라 세션이 아니라 영구 저장소가 맞음.
        const savedUsername = localStorage.getItem('dbagent_saved_username');
        const loginUsernameInput = document.getElementById('login-username');
        const loginSaveUsernameCheckbox = document.getElementById('login-save-username');
        if (savedUsername && loginUsernameInput && loginSaveUsernameCheckbox) {
            loginUsernameInput.value = savedUsername;
            loginSaveUsernameCheckbox.checked = true;
        }

        // Attach auth event listeners
        /**
         * 로그인 직후 착지할 페이지를 정한다(2026-09-06 추가).
         *
         * index.html 은 오라클 전용 화면이라, 오라클 DB가 하나도 허용되지 않은 계정이 여기 남으면
         * 빈 화면만 보게 된다. 계정에 열려 있는 DB가 RDB 쪽뿐이면 그 대시보드로 보낸다.
         *
         * null 을 돌려주면 호출부가 기존대로 이 페이지를 reload 한다 - 오라클이 열려 있거나(정상),
         * 목록을 못 읽었거나(판단 근거 없음), 양쪽 다 없는 경우다. 특히 <b>실패 시 이동시키지 않는
         * 것</b>이 중요하다: 잘못 보내면 사용자가 원인을 알 수 없는 곳에 떨어진다.
         */
        async function resolveLandingPage() {
            try {
                const list = await window.dbagentLoadDbInstances();
                const acc = window.dbagentAccessibleInstances(list);
                if (acc.oracle.length > 0) return null;      // 오라클이 있으면 이 화면이 맞다
                if (acc.rdb.length === 0) return null;       // 양쪽 다 없으면 보낼 곳이 없다
                const first = acc.rdb[0];
                return window.dbagentRdbPageFor(first.engine)
                    + '?db_id=' + encodeURIComponent(first.id)
                    + '&db_type=' + encodeURIComponent(first.engine)
                    + '&name=' + encodeURIComponent(first.label);
            } catch (err) {
                console.error('[login] 착지 화면 판정 실패 - 기본 화면으로 남습니다:', err);
                return null;
            }
        }

        document.getElementById('login-form').addEventListener('submit', async (e) => {
            e.preventDefault();
            const username = document.getElementById('login-username').value;
            const password = document.getElementById('login-password').value;
            const errDiv = document.getElementById('login-error');
            errDiv.style.display = 'none';

            try {
                const res = await fetch(`${API_BASE_AUTH}/login`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username, password })
                });
                const data = await res.json();
                if (res.ok && data.success) {
                    if (loginSaveUsernameCheckbox.checked) {
                        localStorage.setItem('dbagent_saved_username', username);
                    } else {
                        localStorage.removeItem('dbagent_saved_username');
                    }
                    sessionStorage.setItem('dbagent_token', data.token);
                    sessionStorage.setItem('dbagent_role', data.role || 'user');
                    sessionStorage.setItem('dbagent_account_hidden_menus', JSON.stringify(data.hidden_menus || []));
                    sessionStorage.setItem('dbagent_account_hidden_dbs', JSON.stringify(data.hidden_dbs || []));
                    sessionStorage.setItem('dbagent_fleet_overview', data.fleet_overview ? 'true' : 'false');
                    sessionStorage.setItem('dbagent_fleet_overview_auto_redirect', data.fleet_overview_auto_redirect ? 'true' : 'false');
                    document.querySelector('.user-name').textContent = data.username;
                    // Fleet Overview (fleet-overview.html) is the post-login landing screen
                    // (사용자 결정), but only for accounts with access (admin, or granted via 계정 관리 >
                    // "Fleet Overview 접근 허용") AND who haven't personally turned the auto-jump off
                    // (사용자 요청: "admin 권한도 진입 옵션 선택할 수 있나" - a personal preference,
                    // toggled next to the FO button, separate from the access grant itself). Accounts
                    // without either fall back to the normal dashboard reload, same as before this
                    // feature existed. Only fires on a fresh login submit; reloads elsewhere (logout,
                    // theme toggle, password change) intentionally still land back on this page.
                    if (canFleetOverview() && wantsFleetOverviewAutoRedirect()) {
                        window.location.href = 'fleet-overview.html';
                    } else {
                        // 이 화면(index.html)은 <b>오라클 전용</b>이다. 계정에 허용된 DB가 RDB 뿐이면
                        // 여기서 reload 해 봐야 좌측 트리도 상단 드롭다운도 0건이고, RDB 버튼마저
                        // "양쪽 모두 접근 가능한 계정에만 노출"(2026-09-05) 규칙에 걸려 숨겨져서 -
                        // <b>허용받은 DB로 갈 수단이 화면에 하나도 없는 상태</b>가 된다(2026-09-06 재현).
                        // 계정에 실제로 열려 있는 쪽으로 보낸다.
                        const landing = await resolveLandingPage();
                        if (landing) window.location.href = landing;
                        else location.reload();
                    }
                } else {
                    errDiv.textContent = data.message || '로그인 실패';
                    errDiv.style.display = 'block';
                }
            } catch (e) {
                errDiv.textContent = '서버 연결 실패';
                errDiv.style.display = 'block';
            }
        });
        
        document.getElementById('fleet-overview-btn')?.addEventListener('click', () => {
            window.location.href = 'fleet-overview.html';
        });

        // RDB 대시보드 바로가기. RDB 화면은 db_id 없이는 아무것도 못 그리므로(각 패널이
        // /api/rdb/*?db_id=... 로 조회한다) 계정이 접근할 수 있는 첫 RDB 인스턴스를 찾아
        // 그 화면으로 보낸다.
        //
        // 노출 조건은 dbagentCrossNavAccess() - 오라클과 RDB 양쪽 모두에 접근 권한이 있는
        // 계정에만 보인다(사용자 지시, 2026-09-05). 이전에는 게이팅이 전혀 없어서, RDB 권한이
        // 없는 계정도 버튼을 보고 건너간 뒤 "해당 DB에 대한 접근 권한이 없습니다" 만 봤다.
        const rdbBtn = document.getElementById('rdb-dashboard-btn');
        if (rdbBtn) {
            rdbBtn.style.display = 'none'; // 판정 전에는 감춰 둔다 - 잠깐 떴다 사라지지 않도록
            window.dbagentLoadDbInstances().then(list => {
                const acc = window.dbagentCrossNavAccess(list);
                // RDB 가 <b>아예 등록되어 있지 않으면</b> 버튼을 보여주고 눌렀을 때 그 사실을 알린다
                // (2026-09-06 사용자 요청). 권한 때문에 0건인 경우는 기존대로 감춘 채 둔다 -
                // 그때 "등록된 RDB가 없습니다" 라고 하면 사실과 다른 안내가 된다(dbagent-common.js 주석).
                if (acc.emptyRdb) {
                    rdbBtn.title = '등록된 RDB가 없습니다';
                    rdbBtn.addEventListener('click', () => { alert('등록된 RDB가 없습니다.'); });
                    rdbBtn.style.display = '';
                    return;
                }
                if (!acc.allowed) return;
                const first = acc.rdb[0];
                const href = window.dbagentRdbPageFor(first.engine)
                    + '?db_id=' + encodeURIComponent(first.id)
                    + '&db_type=' + encodeURIComponent(first.engine)
                    + '&name=' + encodeURIComponent(first.label);
                rdbBtn.title = 'RDB 대시보드 (' + first.label + ')';
                rdbBtn.addEventListener('click', () => { window.location.href = href; });
                rdbBtn.style.display = '';
            }).catch(err => {
                console.error('[rdb-btn] /api/config 조회 실패:', err);
            });
        }

        document.getElementById('logout-btn')?.addEventListener('click', async () => {
            const token = getToken();
            // Only invalidates this device's own session - other concurrent logins to the same
            // account (같은 계정 동시 로그인 허용, see AuthService) are untouched.
            try {
                await fetch(`${API_BASE_AUTH}/logout`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ token })
                });
            } catch (e) {
                console.error('Logout request failed', e);
            }
            sessionStorage.removeItem('dbagent_token');
            sessionStorage.removeItem('dbagent_role');
            sessionStorage.removeItem('dbagent_account_hidden_menus');
            sessionStorage.removeItem('dbagent_account_hidden_dbs');
            sessionStorage.removeItem('dbagent_fleet_overview');
            sessionStorage.removeItem('dbagent_fleet_overview_auto_redirect');
            location.reload();
        });

        // 2026-08-28 사용자 요청: 화면배색 버튼 아이콘을 sun/moon 상태 전환 방식에서 고정된 palette
        // 아이콘으로 변경 - 더 이상 라이트/다크를 아이콘으로 구분해서 보여줄 필요가 없어짐 (palette
        // 아이콘 자체는 index.html에 고정 마크업으로 이미 존재, 여기서는 클릭 시 배색만 전환).
        // 로그인 화면(#login-theme-toggle-btn)과 로그인 후 화면(#theme-toggle-btn) 둘 다 이 함수를
        // 공유 - 로그인 전에도 배색을 미리 바꿔볼 수 있게 해달라는 요청(2026-08-31)으로 추가됨.
        function toggleTheme() {
            const isLight = document.documentElement.getAttribute('data-theme') === 'light';
            const next = isLight ? 'dark' : 'light';
            localStorage.setItem('dbagent_theme', next);
            // 예전엔 여기서 location.reload()를 했음 - Chart.js 색상(chartLineColor())이 라이트/다크에
            // 따라 달라지던 시절엔 차트를 다시 칠하려면 리로드가 제일 간단했음. 그런데 2026-08-28에 두
            // 테마 슬롯이 전부 어두운 배경으로 바뀌면서 chartLineColor()가 테마와 무관하게 항상 흰색
            // 계열을 반환하도록 바뀌었고(isLightTheme()도 이제 다른 곳에서 안 쓰임), 리로드가 아무 실익
            // 없이 현재 조회 결과(예: Table Parent/Child 관계 조회)만 날려버리는 부작용만 남았던 것
            // (사용자 확인, 2026-08-29). data-theme 속성만 바꾸면 나머지는 전부 CSS 변수라 즉시 다시
            // 칠해지므로, applySavedTheme()과 동일한 방식으로 속성만 토글.
            if (next === 'light') {
                document.documentElement.setAttribute('data-theme', 'light');
            } else {
                document.documentElement.removeAttribute('data-theme');
            }
        }
        document.getElementById('theme-toggle-btn')?.addEventListener('click', toggleTheme);
        document.getElementById('login-theme-toggle-btn')?.addEventListener('click', toggleTheme);

        const pwdModal = document.getElementById('change-pwd-modal');
        document.getElementById('change-pwd-trigger')?.addEventListener('click', () => {
            pwdModal.style.display = 'flex';
        });
        document.getElementById('pwd-cancel-btn')?.addEventListener('click', () => {
            pwdModal.style.display = 'none';
            document.getElementById('change-pwd-form').reset();
            document.getElementById('pwd-error').style.display = 'none';
        });
        
        document.getElementById('change-pwd-form')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            const current = document.getElementById('pwd-current').value;
            const newPwd = document.getElementById('pwd-new').value;
            const confirm = document.getElementById('pwd-confirm').value;
            const errDiv = document.getElementById('pwd-error');
            
            if (newPwd !== confirm) {
                errDiv.textContent = '새 비밀번호가 일치하지 않습니다.';
                errDiv.style.display = 'block';
                return;
            }
            
            const token = sessionStorage.getItem('dbagent_token');
            try {
                const res = await fetch(`${API_BASE_AUTH}/change-password`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ token, current_password: current, new_password: newPwd })
                });
                const data = await res.json();
                if (res.ok && data.success) {
                    alert('비밀번호가 성공적으로 변경되었습니다. 다시 로그인해주세요.');
                    sessionStorage.removeItem('dbagent_token');
                    sessionStorage.removeItem('dbagent_role');
                    sessionStorage.removeItem('dbagent_account_hidden_menus');
                    sessionStorage.removeItem('dbagent_account_hidden_dbs');
                    location.reload();
                } else {
                    errDiv.textContent = data.message || '변경 실패';
                    errDiv.style.display = 'block';
                }
            } catch (e) {
                errDiv.textContent = '서버 오류';
                errDiv.style.display = 'block';
            }
        });

        // Account management/DB management/menu visibility settings all used to be in-page modals
        // here; moved to real popup windows (account-mgmt.html/db-mgmt.html/menu-visibility.html) so
        // the user can drag them to a second monitor, same reasoning as session-detail.html. Each
        // popup reads what it needs from window.opener (nav items, sidebar DB list, the token) and
        // reloads/closes the opener on a successful save - see those files for the actual logic.
        const gearTrigger = document.getElementById('gear-trigger');
        const gearDropdown = document.getElementById('gear-dropdown');
        gearTrigger?.addEventListener('click', (e) => {
            e.stopPropagation();
            gearDropdown.classList.toggle('open');
        });
        document.addEventListener('click', (e) => {
            if (gearDropdown && gearDropdown.classList.contains('open')
                && !gearDropdown.contains(e.target) && e.target !== gearTrigger) {
                gearDropdown.classList.remove('open');
            }
        });

        function openAdminPopup(url, name, features) {
            gearDropdown.classList.remove('open');
            const popup = window.open(url, name, features);
            if (popup) popup.focus();
        }
        document.getElementById('open-account-mgmt-btn')?.addEventListener('click', () => {
            openAdminPopup('account-mgmt.html', 'dbagent_account_mgmt', window.dbagentAdminPopupFeatures('account'));
        });
        document.getElementById('open-db-mgmt-btn')?.addEventListener('click', () => {
            openAdminPopup('db-mgmt.html', 'dbagent_db_mgmt', 'width=980,height=820,resizable=yes,scrollbars=yes');
        });
        document.getElementById('open-menu-visibility-btn')?.addEventListener('click', () => {
            openAdminPopup('menu-visibility.html', 'dbagent_menu_visibility', window.dbagentAdminPopupFeatures('menu'));
        });

        // --- Menu visibility settings (admin only) ---
        // getHiddenMenus/applyMenuVisibility etc. stay here (not moved into menu-visibility.html)
        // because they're also needed on every normal page load, not just from that popup.
        function getHiddenMenus() {
            try {
                return JSON.parse(localStorage.getItem('dbagent_hidden_menus') || '[]');
            } catch (e) {
                return [];
            }
        }

        function getAccountHiddenMenus() {
            try {
                return JSON.parse(sessionStorage.getItem('dbagent_account_hidden_menus') || '[]');
            } catch (e) {
                return [];
            }
        }

        function getAccountHiddenDbs() {
            try {
                return JSON.parse(sessionStorage.getItem('dbagent_account_hidden_dbs') || '[]');
            } catch (e) {
                return [];
            }
        }

        function applyMenuVisibility() {
            // A menu is hidden if either the per-browser preference or the account's own
            // configuration (set by an admin when the account was created) hides it.
            const hidden = new Set([...getHiddenMenus(), ...getAccountHiddenMenus()]);
            document.querySelectorAll('.top-nav .nav-item[data-target]').forEach(item => {
                const target = item.getAttribute('data-target');
                if (target === 'dashboard') return;
                item.classList.toggle('menu-hidden', hidden.has(target));
            });
        }


        const authed = await checkAuth();
        if (!authed) {
            // Everything past this point (including the app's only other lucide.createIcons() calls,
            // further below) is skipped pre-login, so the login screen's own icons (brand icon, and
            // the palette theme-toggle button) would otherwise stay unconverted <i data-lucide> tags
            // forever - convert them here instead.
            if (typeof lucide !== 'undefined') lucide.createIcons();
            return;
        }

        if (!isAdmin()) {
            document.querySelectorAll('.admin-only').forEach(el => el.style.display = 'none');
        }
        const foAutoToggleWrap = document.getElementById('fo-auto-toggle-wrap');
        const foAutoToggleInput = document.getElementById('fo-auto-toggle-input');
        // 좌측 상단 로고도 FO 로 가는 통로다 - 버튼만 숨기고 로고를 열어 두면, 권한 없는 계정이
        // 로고를 눌러 건너간 뒤 "접근 권한이 없습니다" 만 보게 된다(2026-09-06 실측).
        if (window.dbagentGateBrandLink) window.dbagentGateBrandLink('.sidebar-brand-link');

        if (!canFleetOverview()) {
            const foBtn = document.getElementById('fleet-overview-btn');
            if (foBtn) foBtn.style.display = 'none';
            if (foAutoToggleWrap) foAutoToggleWrap.style.display = 'none';
        } else if (foAutoToggleInput) {
            foAutoToggleInput.checked = wantsFleetOverviewAutoRedirect();
            foAutoToggleInput.addEventListener('change', async () => {
                const autoRedirect = foAutoToggleInput.checked;
                sessionStorage.setItem('dbagent_fleet_overview_auto_redirect', autoRedirect ? 'true' : 'false');
                try {
                    await fetch(`${API_BASE_AUTH}/me/fleet_overview_auto_redirect`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ token: getToken(), auto_redirect: autoRedirect })
                    });
                } catch (e) {
                    console.error('Failed to save Fleet Overview auto-redirect preference', e);
                }
            });
        }
        applyMenuVisibility();

        window.currentDbId = "";

        // MySQL/MariaDB and PostgreSQL each get their own PMM-style Overview/Detail dashboard - the
        // panel terminology differs too much (InnoDB/MySQL Handlers vs pg_stat_* views) to share one page.
        function rdbTargetPage(dbType) {
            if (dbType === 'mysql' || dbType === 'mariadb') return 'mysql-overview-dashboard.html';
            if (dbType === 'postgres') return 'postgres-overview-dashboard.html';
            if (dbType === 'mssql') return 'mssql-overview-dashboard.html';
            if (dbType === 'cubrid') return 'cubrid-overview-dashboard.html';
            return 'rdb-dashboard.html';
        }

        // ---- 상단 DB 선택 드롭다운 (2026-09-06, RDB 대시보드 헤더와 같은 chevron 방식) ----
        //
        // 좌측 트리를 펼치지 않고도 DB를 바꿀 수 있게 한다. 여기서는 DB를 직접 바꾸지 않고 트리의
        // 해당 인스턴스 링크를 click() 한다 - 전환 로직(위젯 초기화, 세션 모니터 리셋, 보고 있던
        // 탭 유지)이 트리 쪽에만 있어야 두 경로가 어긋나지 않고, 트리의 선택 표시도 같이 따라온다.
        function syncTopTitle(inst) {
            const titleEl = document.getElementById('ora-title');
            if (titleEl) titleEl.textContent = (inst && (inst.name || inst.id)) || 'Oracle Overview';
            const dd = document.getElementById('ora-title-dropdown');
            if (!dd) return;
            Array.prototype.forEach.call(dd.querySelectorAll('.td-item'), function (el) {
                const mine = inst && el.getAttribute('data-db-id') === inst.id;
                el.classList.toggle('active', !!mine);
            });
        }

        function buildTopDbDropdown(entries, linksById) {
            const wrap = document.getElementById('ora-title-wrap');
            const dd = document.getElementById('ora-title-dropdown');
            if (!wrap || !dd) return;

            if (!entries.length) {
                dd.innerHTML = '<div class="td-group">표시할 Oracle DB가 없습니다</div>';
            } else {
                let html = '';
                let lastGroup = null;
                entries.forEach(function (e) {
                    if (e.group !== lastGroup) {
                        html += '<div class="td-group">' + e.group + '</div>';
                        lastGroup = e.group;
                    }
                    // 오라클 인스턴스는 host 가 비어 있고 sid(TNS 별칭)만 있는 경우가 흔하다.
                    // 그대로 이으면 "· ORCL" 처럼 앞에 점만 덩그러니 남는다.
                    const hostText = [e.inst.host, e.inst.sid].filter(function (v) { return v; }).join(' · ');
                    html += '<div class="td-item" data-db-id="' + e.inst.id + '">'
                        + '<span>' + (e.inst.name || e.inst.id) + '</span>'
                        + '<span class="td-host">' + hostText + '</span></div>';
                });
                dd.innerHTML = html;
            }

            Array.prototype.forEach.call(dd.querySelectorAll('.td-item[data-db-id]'), function (el) {
                el.addEventListener('click', function (ev) {
                    ev.stopPropagation();
                    dd.classList.remove('open');
                    wrap.classList.remove('open');
                    // 이미 보고 있는 DB면 아무것도 하지 않는다(불필요한 위젯 초기화 방지).
                    const id = el.getAttribute('data-db-id');
                    if (id === window.currentDbId) return;
                    const link = linksById[id];
                    if (link) link.click();
                });
            });

            wrap.addEventListener('click', function (ev) {
                ev.stopPropagation();
                const open = dd.classList.toggle('open');
                wrap.classList.toggle('open', open);
            });
            // 드롭다운 안을 클릭해도 위 토글까지 올라가 바로 닫히지 않도록 막는다.
            dd.addEventListener('click', function (ev) { ev.stopPropagation(); });
            document.addEventListener('click', function () {
                dd.classList.remove('open');
                wrap.classList.remove('open');
            });
        }

        // Load config and build tree
        fetch(`/api/config`)
            .then(res => res.json())
            .then(data => {
                const container = document.getElementById('db-groups-container');
                if(!container) return;

                let isFirstInstance = true;
                // 상단 DB 선택 드롭다운(RDB 대시보드의 헤더 chevron과 같은 것, 2026-09-06 추가)이
                // 쓸 링크 목록. 드롭다운은 자기가 DB를 바꾸지 않고 여기 담아 둔 트리 링크를 click()
                // 한다 - 전환 로직(위젯 초기화/세션 모니터 리셋/현재 탭 유지)이 트리 쪽에만 있고,
                // 그래야 좌측 트리의 선택 표시도 저절로 같이 따라온다.
                const treeLinksById = {};
                const ddGroups = [];
                // ?db_id=... jumps straight to that instance instead of the usual "first
                // non-restricted instance" default - used by the Fleet Overview page's card-click
                // navigation (fleet-overview.html opens index.html?db_id=<id>).
                const jumpToDbId = new URLSearchParams(window.location.search).get('db_id');
                // Admins always see every DB; other accounts don't see ones an admin restricted
                // for them when the account was created (or later, via 계정 관리 > 수정).
                const restrictedDbs = isAdmin() ? new Set() : new Set(getAccountHiddenDbs());

                // A stale/old bookmark (or someone hand-typing a URL) can still land on
                // index.html?db_id=<mysql/mariadb/postgres/mssql instance> even though Fleet
                // Overview itself never generates such a link anymore (it goes straight to the
                // engine's own dashboard page). Since the sidebar below only ever iterates
                // Oracle instances now (2026-09-04, see the oracleInstances filter), that redirect
                // has to be checked here against the full unfiltered instance list, before the
                // per-group loop, or it silently stops firing.
                if (jumpToDbId) {
                    for (const group of data.groups) {
                        const jumpInst = group.instances.find(inst => inst.id === jumpToDbId);
                        if (jumpInst && jumpInst.db_type && jumpInst.db_type !== 'oracle') {
                            window.location.href = `${rdbTargetPage(jumpInst.db_type)}?db_id=${encodeURIComponent(jumpInst.id)}&db_type=${encodeURIComponent(jumpInst.db_type)}&name=${encodeURIComponent(jumpInst.name || jumpInst.id)}`;
                            return;
                        }
                    }
                }

                data.groups.forEach((group, gIdx) => {
                    // Oracle 전용 사이드바 - MySQL/MariaDB/PostgreSQL/MS SQL Server 인스턴스는 각자
                    // 전용 PMM 스타일 대시보드(mysql/postgres/mssql-overview-dashboard.html)의 왼쪽
                    // 트리에 이미 따로 표시되므로 여기서는 제외 (사용자 지적, 2026-09-04: "왜 오라클
                    // 대시보드 좌측 화면에 붙어 있지?"). db_type이 없는 레거시 인스턴스는 오라클로 취급.
                    const oracleInstances = group.instances.filter(inst => !inst.db_type || inst.db_type === 'oracle');
                    if (!oracleInstances.length) return;
                    // If every instance in this group is restricted for the account, don't
                    // show the group at all (no point showing an empty accordion header).
                    if (!isAdmin() && oracleInstances.every(inst => restrictedDbs.has(inst.id))) {
                        return;
                    }

                    const groupDiv = document.createElement('div');
                    groupDiv.className = 'db-info-panel';
                    groupDiv.style.padding = '15px 24px';
                    groupDiv.style.borderBottom = '1px solid var(--border)';
                    
                    const groupHeader = document.createElement('div');
                    groupHeader.style.display = 'flex';
                    groupHeader.style.alignItems = 'center';
                    groupHeader.style.justifyContent = 'space-between';
                    groupHeader.style.cursor = 'pointer';
                    groupHeader.style.color = 'var(--text-primary)';
                    groupHeader.style.fontWeight = '600';
                    
                    groupHeader.innerHTML = `
                        <span>${group.group_name}</span>
                        <i data-lucide="chevron-right" style="width: 18px; height: 18px; transition: transform 0.3s ease;"></i>
                    `;
                    
                    const instancesDiv = document.createElement('div');
                    instancesDiv.style.display = 'none';
                    instancesDiv.style.paddingTop = '10px';
                    instancesDiv.style.paddingLeft = '10px';
                    instancesDiv.style.borderLeft = '2px solid var(--border)';
                    instancesDiv.style.marginTop = '5px';
                    
                    oracleInstances.forEach((inst, iIdx) => {
                        const instLink = document.createElement('a');
                        instLink.href = '#dashboard';
                        instLink.className = 'instance-item';
                        instLink.setAttribute('data-db-id', inst.id);
                        const isRestricted = restrictedDbs.has(inst.id);
                        // 상단 드롭다운은 이 링크를 눌러 DB를 바꾼다. 계정에 제한된 인스턴스는
                        // 트리에서 숨기는 것과 같이 드롭다운에도 올리지 않는다.
                        if (!isRestricted) {
                            treeLinksById[inst.id] = instLink;
                            ddGroups.push({ group: group.group_name, inst: inst });
                        }
                        instLink.style.display = isRestricted ? 'none' : 'flex';
                        instLink.style.alignItems = 'center';
                        instLink.style.gap = '8px';
                        instLink.style.color = 'var(--primary)';
                        instLink.style.textDecoration = 'none';
                        instLink.style.padding = '5px';
                        instLink.style.borderRadius = '4px';
                        instLink.style.transition = 'background 0.2s';
                        instLink.style.cursor = 'pointer';
                        
                        instLink.innerHTML = `
                            <i data-lucide="database" class="instance-icon-static" style="width: 16px; height: 16px;"></i>
                            <i data-lucide="activity" class="instance-icon-live" style="width: 16px; height: 16px;"></i>
                            <span style="font-weight: bold; font-size: 0.95rem;">${inst.name}</span>
                        `;
                        
                        instLink.addEventListener('click', (e) => {
                            e.preventDefault();

                            // Reset all links colors (unselected instances shown in blue, not muted gray)
                            document.querySelectorAll('.instance-item').forEach(el => {
                                el.style.color = 'var(--primary)';
                                el.classList.remove('active-monitoring');
                                // querySelector('svg') 대신 명시적으로 static 아이콘을 지정 - 두 아이콘(static/live)이
                                // 함께 렌더링되기 시작한 뒤로 'svg'는 DOM 순서상 항상 static을 먼저 찾기 때문에,
                                // 여기선 우연히 맞지만 아래 active 쪽에서는 틀린 아이콘을 잡던 문제를 함께 바로잡음.
                                const svg = el.querySelector('.instance-icon-static');
                                if(svg) svg.style.color = 'var(--primary)';
                            });

                            // Set active color
                            instLink.style.color = 'var(--success)';
                            instLink.classList.add('active-monitoring');
                            const svg = instLink.querySelector('.instance-icon-live');
                            if(svg) svg.style.color = 'var(--success)';
                            
                            window.currentDbId = inst.id;
                            // 상단 타이틀/드롭다운 표시를 지금 고른 DB로 맞춘다. 트리에서 골랐든
                            // 드롭다운에서 골랐든 여기 한 곳을 지나므로 둘이 어긋나지 않는다.
                            if (typeof syncTopTitle === 'function') syncTopTitle(inst);
                            // 인스턴스별 세션 임계치 오버라이드 (databases.json의 "session_thresholds": [t1..t5]),
                            // 없으면 undefined -> getSessColor()가 자동으로 기본값(DEFAULT_SESSION_THRESHOLDS) 사용.
                            window.currentSessionThresholds = Array.isArray(inst.session_thresholds) ? inst.session_thresholds : null;
                            if (typeof resetAllDashboardWidgets === 'function') resetAllDashboardWidgets();
                            if (typeof resetSessionMonitor === 'function') resetSessionMonitor(inst.id);
                            // DB를 바꿔도 지금 보고 있던 메뉴에 그대로 머무르도록 - 대시보드로 강제 이동하지 않음.
                            const activeNav = document.querySelector('.nav-item.active');
                            switchTab(activeNav ? activeNav.getAttribute('data-target') : 'dashboard');
                            // v2/v3가 보이는 중이면 지금 바로 새 DB로 갱신 - 안 그러면 다음 10초 폴링
                            // 틱까지 화면이 이전 DB 데이터(또는 최초 진입 시 빈 화면)로 멈춰 있다
                            // (오케스트레이터 실측, 2026-09-18: "UI-1 초기화면이 너무 늦게 뜬다").
                            if (typeof window.fetchActiveDashboardView === 'function') window.fetchActiveDashboardView();
                        });
                        
                        instancesDiv.appendChild(instLink);
                        
                        // Auto-select: the requested db_id if one was given (jumpToDbId), otherwise
                        // the first non-restricted instance across all groups. oracleInstances above
                        // already guarantees every inst reaching this point is Oracle (or a legacy
                        // instance with no db_type) - a non-Oracle jumpToDbId is handled earlier via
                        // the redirect check before this loop, so it never reaches here. If db_id was
                        // given but never matches (unknown id, or restricted for this account), nothing
                        // here auto-selects - no silent fallback to "first", so a stale/bad link doesn't
                        // quietly land on the wrong DB.
                        const shouldAutoSelect = jumpToDbId
                            ? (inst.id === jumpToDbId && !isRestricted)
                            : (isFirstInstance && !isRestricted);
                        if (shouldAutoSelect) {
                            isFirstInstance = false;
                            setTimeout(() => {
                                groupHeader.click();
                                instLink.click();
                            }, 100);
                        }
                    });
                
                groupHeader.addEventListener('click', () => {
                    const icon = groupHeader.querySelector('svg') || groupHeader.querySelector('i');
                    if (instancesDiv.style.display === 'none') {
                        instancesDiv.style.display = 'block';
                        icon.style.transform = 'rotate(90deg)';
                    } else {
                        instancesDiv.style.display = 'none';
                        icon.style.transform = 'rotate(0deg)';
                    }
                });
                
                groupDiv.appendChild(groupHeader);
                groupDiv.appendChild(instancesDiv);
                container.appendChild(groupDiv);
            });

            // 트리가 비면(이 계정에 열린 오라클 DB가 없음) 빈 사이드바만 남아 원인을 알 수 없다.
            // 로그인 직후에는 resolveLandingPage() 가 RDB 화면으로 보내지만, 이미 로그인한 채
            // 이 주소로 직접 들어오면(북마크·새로고침) 여기로 온다 - 이유를 밝히고, 갈 곳이 있으면
            // 그 링크를 준다(2026-09-06).
            if (!container.children.length) {
                const note = document.createElement('div');
                note.className = 'db-tree-error';
                note.style.color = 'var(--text-muted)';
                const anyOracle = (data.groups || []).some(g =>
                    (g.instances || []).some(i => !i.db_type || i.db_type === 'oracle'));
                note.textContent = anyOracle
                    ? '이 계정에 허용된 오라클DB가 없습니다.'
                    : '등록된 오라클DB가 없습니다.';
                container.appendChild(note);

                if (window.dbagentAccessibleInstances) {
                    const acc = window.dbagentAccessibleInstances(window.dbagentInstancesFromConfig(data));
                    if (acc.rdb.length > 0) {
                        const first = acc.rdb[0];
                        const link = document.createElement('a');
                        link.href = window.dbagentRdbPageFor(first.engine)
                            + '?db_id=' + encodeURIComponent(first.id)
                            + '&db_type=' + encodeURIComponent(first.engine)
                            + '&name=' + encodeURIComponent(first.label);
                        link.textContent = 'RDB 대시보드로 이동 (' + first.label + ')';
                        link.style.cssText = 'display:block; padding:8px; color:var(--primary); text-decoration:none;';
                        container.appendChild(link);
                    }
                }
            }

            buildTopDbDropdown(ddGroups, treeLinksById);
            
            if (typeof lucide !== 'undefined') lucide.createIcons();
            
            // Handle initial load after config is loaded and db is selected
            const initialHash = window.location.hash.substring(1);
            if (initialHash) {
                switchTab(initialHash);
            }
        });

    // Initialize Lucide icons
    try {
        if (typeof lucide !== 'undefined') {
            lucide.createIcons();
        }
    } catch (e) {
        console.error('Lucide icons failed to load:', e);
    }



    // Navigation Logic
    const navItems = document.querySelectorAll('.nav-item');
    const sections = document.querySelectorAll('.content-section');
    const pageTitle = document.getElementById('page-title');



    function switchTab(targetId) {
        if (!targetId) return;
        
        // Find target item and section
        const targetNav = document.querySelector(`.nav-item[data-target="${targetId}"]`);
        const targetSection = document.getElementById(targetId);
        
        if (!targetNav || !targetSection) return;

        // Remove active class from all nav items and sections
        navItems.forEach(nav => nav.classList.remove('active'));
        sections.forEach(section => section.classList.remove('active'));

        // Add active class
        targetNav.classList.add('active');
        targetSection.classList.add('active');

        // Update page title
        const text = targetNav.querySelector('span').innerText;
        pageTitle.innerText = text;

        // Auto-fetch data if tablespace
        if (targetId === 'tablespace') {
            const btn = document.getElementById('tablespace-refresh-btn');
            if (btn) {
                // 같은 DB를 이미 조회 중이면 중복 클릭하지 않는다. 다만 조회 중인 DB와 지금 고른 DB가
                // 다르면(체크리스트 7-1, 2026-09-25) 새 DB로 반드시 다시 요청한다 - 예전엔 "조회 중"이면
                // 무조건 건너뛰어, 이전 DB 조회가 늦게 끝나며 그 값(전체 할당량/사용량/사용률 포함)이
                // 새 DB 화면에 남는 버그가 있었다. 늦게 온 이전 DB 응답은 클릭 핸들러가 버린다.
                const icon = btn.querySelector('i');
                const busy = icon && icon.classList.contains('spinning');
                if (!busy || window.tablespaceInFlightDbId !== window.currentDbId) {
                    btn.click();
                }
            }
        }
        
        // 사용자 요청(2026-08-31): 다른 메뉴 갔다가 Current Session으로 돌아와도 추이/Trace 그래프가
        // 안 끊기게 - 예전엔 여기서 매번 resetSessionMonitor()를 무조건 호출해 그래프를 지웠는데, 자동
        // 갱신이 켜져 있으면 이 탭을 안 보고 있는 동안에도 폴링은 백그라운드에서 계속 돌며
        // sessionHistory/scatterDataPoints를 쌓고 있었으므로, 돌아왔을 때 그걸 지우는 게 아니라 그대로
        // 이어서 보여주면 됨. DB를 바꿨을 때의 초기화는 이 메뉴 진입과 무관하게 인스턴스 클릭
        // 핸들러(위쪽, resetSessionMonitor() 호출부)에서 이미 별도로 처리하고 있어 여기서 또 지울
        // 필요가 없다 - 그래서 이 블록에선 자동 갱신이 꺼져 있을 때만(=최초 진입 등) 재시작.
        if (targetId === 'session') {
            const toggleBtn = document.getElementById('session-toggle-btn');
            if (toggleBtn && !isSessionAutoRefreshing) {
                setTimeout(() => { toggleBtn.click(); }, 100);
            }
        }

        // Populate the account dropdown for the currently selected DB
        if (targetId === 'sqlrunner' && typeof window.loadSqlRunnerAccounts === 'function') {
            window.loadSqlRunnerAccounts();
        }
        if (targetId === 'aidba' && typeof window.loadAiDbaHealth === 'function') {
            window.loadAiDbaHealth();
        }
        if (targetId === 'aidba' && typeof window.loadTunnerCurrentAccounts === 'function') {
            window.loadTunnerCurrentAccounts();
            if (typeof window.renderTunnerCurrentBindFields === 'function') window.renderTunnerCurrentBindFields();
        }
        if (targetId === 'aidba' && typeof window.loadSqlWriterAccounts === 'function') {
            window.loadSqlWriterAccounts();
        }

        // Auto-fetch data if tmlock
        if (targetId === 'tmlock') {
            const btn = document.getElementById('tmlock-refresh-btn');
            if (btn) {
                const icon = btn.querySelector('i');
                if (!icon || !icon.classList.contains('spinning')) {
                    btn.click();
                }
            }
        }

        // Returning to dashboard: canvases were display:none while another menu was open,
        // so Chart.js cached a stale (often 0) size. Force a resize before the next data
        // update or the sparkline renders flat/squashed for one tick.
        if (targetId === 'dashboard') {
            [dashCpuChart, dashMemChart, dashFailChart, dashSessChart].forEach(c => c && c.resize());
            fetchDashboard();
        }
    }

    // Attach click events
    navItems.forEach(item => {
        item.addEventListener('click', (e) => {
            e.preventDefault();
            const targetId = item.getAttribute('data-target');
            // Update URL hash
            if (window.location.hash !== `#${targetId}`) {
                history.pushState(null, null, `#${targetId}`);
            }
            switchTab(targetId);
        });
    });

    // Handle hash change events (like browser back/forward)
    window.addEventListener('hashchange', () => {
        const hash = window.location.hash.substring(1);
        if (hash) switchTab(hash);
    });



    // Initialize Mermaid
    if (typeof mermaid !== 'undefined') {
        // er.entityPadding: 기본값(15)에서는 폰트 측정-렌더링 오차로 테이블명 끝 글자가
        // 박스 밖으로 살짝 잘려 보이는 경우가 있어 여유 폭을 넉넉히 확보함
        mermaid.initialize({ startOnLoad: false, theme: 'dark', er: { entityPadding: 30, minEntityWidth: 120 } });
    }

    // Relation Logic
    const relationSearchBtn = document.getElementById('relation-search-btn');
    const relationSearchInput = document.getElementById('relation-search-input');
    const relationEmptyState = document.getElementById('relation-empty-state');
    const relationContainer = document.getElementById('relation-container');

    if (relationSearchBtn) {
        relationSearchBtn.addEventListener('click', async () => {
            const tableName = relationSearchInput.value.trim().toUpperCase();
            if (!tableName) {
                alert('테이블명을 입력해주세요.');
                return;
            }

            relationEmptyState.style.display = 'none';
            relationContainer.style.display = 'block';
            relationContainer.innerHTML = '<div style="padding: 20px; text-align: center;">데이터 조회 중...</div>';

            try {
                const directionEl = document.querySelector('input[name="relation-direction"]:checked');
                const direction = directionEl ? directionEl.value : 'bi';
                const response = await fetch(`/api/relation?db_id=${window.currentDbId || ""}&table_name=${encodeURIComponent(tableName)}&direction=${direction}&token=${encodeURIComponent(getToken())}`);
                if (!response.ok) throw new Error('Failed to fetch relation data');
                const result = await response.json();
                
                if (result.error) {
                    throw new Error(result.error);
                }

                
                
                const data = result.data;
                
                const parentRelations = data.filter(item => item.child_table.toUpperCase() === tableName);
                const childRelations = data.filter(item => item.parent_table.toUpperCase() === tableName);
                
                let treeHTML = `<div style="display: flex; flex-direction: column; align-items: center;">`;

                let mermaidSyntax = "erDiagram\n";
                let hasData = false;
                const uniqueRelations = new Set();
                
                // Helper to clean table names for mermaid nodes
                const cleanName = (name) => name.replace(/[^A-Za-z0-9_]/g, '_');

                // 1. 내가 자식인 경우 (부모 테이블들)
                if (parentRelations.length > 0) {
                    hasData = true;
                    treeHTML += `<div style="text-align: center; margin-bottom: 5px; font-weight: bold; color: var(--text-secondary);">내가 참조하는 부모 테이블들 (내가 자식)</div>`;
                    treeHTML += `<div class="tree-children" style="display: flex; gap: 10px; justify-content: center; flex-wrap: wrap; margin-bottom: 20px; border-bottom: 1px solid var(--border-color); padding-bottom: 20px; width: 100%;">`;
                    parentRelations.forEach(item => {
                        treeHTML += `
                            <div class="tree-card" style="cursor: pointer;" onclick="if(window.showTableInfoModal) window.showTableInfoModal('${item.parent_table}')">
                                <div class="table-name"><i data-lucide="arrow-up-circle"></i> ${item.parent_table}</div>
                                <div class="relation-type">Parent (FK: ${item.fk_name})</div>
                                <div style="font-size: 0.8rem; color: var(--text-secondary); margin-top: 5px;">
                                    ${item.child_column} → ${item.parent_column}
                                </div>
                            </div>
                        `;
                        const relKey = `    ${cleanName(item.parent_table)} ||--o{ ${cleanName(tableName)} : "${item.fk_name}"
`;
                        if (!uniqueRelations.has(relKey)) {
                            mermaidSyntax += relKey;
                            uniqueRelations.add(relKey);
                        }
                    });
                    treeHTML += `</div>`;
                }

                // 2. 검색 대상 테이블 (Root)
                treeHTML += `
                    <div class="tree-card root" style="border: 2px solid var(--primary-color); box-shadow: 0 0 10px rgba(57, 135, 229, 0.3); cursor: pointer;" onclick="if(window.showTableInfoModal) window.showTableInfoModal('${tableName}')">
                        <div class="table-name"><i data-lucide="table"></i> ${tableName}</div>
                        <div class="relation-type">Selected Table (검색 대상)</div>
                    </div>
                `;

                // 3. 내가 부모인 경우 (자식 테이블들)
                if (childRelations.length > 0) {
                    hasData = true;
                    treeHTML += `<div class="tree-children" style="display: flex; gap: 10px; justify-content: center; flex-wrap: wrap; margin-top: 20px; border-top: 1px solid var(--border-color); padding-top: 20px; width: 100%;">`;
                    childRelations.forEach(item => {
                        treeHTML += `
                            <div class="tree-card" style="cursor: pointer;" onclick="if(window.showTableInfoModal) window.showTableInfoModal('${item.child_table}')">
                                <div class="table-name"><i data-lucide="arrow-down-circle"></i> ${item.child_table}</div>
                                <div class="relation-type">Child (FK: ${item.fk_name})</div>
                                <div style="font-size: 0.8rem; color: var(--text-secondary); margin-top: 5px;">
                                    ${item.child_column} → ${item.parent_column}
                                </div>
                            </div>
                        `;
                        const relKey = `    ${cleanName(tableName)} ||--o{ ${cleanName(item.child_table)} : "${item.fk_name}"
`;
                        if (!uniqueRelations.has(relKey)) {
                            mermaidSyntax += relKey;
                            uniqueRelations.add(relKey);
                        }
                    });
                    treeHTML += `</div>`;
                    treeHTML += `<div style="text-align: center; margin-top: 5px; font-weight: bold; color: var(--text-secondary);">나를 참조하는 자식 테이블들 (내가 부모)</div>`;
                }
                
                if (!hasData) {
                    treeHTML += `
                        <div style="margin-top: 20px; color: var(--text-secondary); text-align: center;">
                            관계된 부모/자식 테이블이 없습니다.
                        </div>
                    `;
                    mermaidSyntax += `    ${cleanName(tableName)}
`;
                }
                
                treeHTML += `</div>`;
let layoutHTML = "";
                if (direction === 'uni') {
                    // 단방향: 좌우 분할 레이아웃
                    // 사용자 리포트(2026-09-01): 좌측 트리와 우측 ERD를 나누는 경계선이 정확히 그려지지
                    // 않음 - 원인은 이 flex row가 align-items: flex-start였던 것. flex-start는 두 컬럼을
                    // 위쪽만 맞추고 각자 자기 컨텐츠 높이만큼만 차지하게 두므로, 트리 카드 개수(부모+자식)와
                    // ERD 다이어그램의 실제 렌더 높이가 다르면(거의 항상 다름) 오른쪽 컬럼의 border-left가
                    // 왼쪽 카드 박스보다 짧거나 길게 끝나 버려 경계선이 어긋나 보임. align-items를 기본값인
                    // stretch로 바꿔 두 컬럼이 항상 같은(둘 중 더 큰) 높이로 늘어나게 하면, border-left가
                    // 매번 왼쪽 카드 박스와 정확히 같은 높이로 그려진다.
                    layoutHTML = `
                        <div style="display: flex; gap: 20px;">
                            <div style="flex: 1; min-width: 0;">
                                <h3 style="margin-top: 0; margin-bottom: 15px; font-size: 1rem; color: var(--text-primary);">트리 형태</h3>
                                <div style="padding: 20px; background: var(--bg-card); border-radius: 8px; border: 1px solid var(--border-color);">
                                    ${treeHTML}
                                </div>
                            </div>
                            <div style="flex: 1; min-width: 0; border-left: 1px solid var(--border-color); padding-left: 20px; text-align: center;">
                                <h3 style="margin-top: 0; margin-bottom: 15px; font-size: 1rem; color: var(--text-primary); text-align: left;">ERD 형태</h3>
                                <div class="mermaid" style="opacity: 0; transition: opacity 0.15s ease;">
                                    ${mermaidSyntax}
                                </div>
                            </div>
                        </div>
                    `;
                } else {
                    // 양방향: 상하 분할 레이아웃 + 가로 스크롤
                    layoutHTML = `
                        <div style="display: flex; flex-direction: column; gap: 40px; align-items: stretch;">
                            <div style="width: 100%;">
                                <h3 style="margin-top: 0; margin-bottom: 15px; font-size: 1.2rem; color: var(--text-primary); text-align: center;">트리 형태</h3>
                                <div style="width: 100%; overflow-x: auto; padding: 20px; background: var(--bg-card); border-radius: 8px; border: 1px solid var(--border-color);">
                                    ${treeHTML}
                                </div>
                            </div>
                            <div style="width: 100%; border-top: 2px solid var(--border-color); padding-top: 30px; text-align: center;">
                                <h3 id="erd-popup-btn" style="margin-top: 0; margin-bottom: 15px; font-size: 1.2rem; color: var(--primary); text-align: center; cursor: pointer; text-decoration: underline;" title="클릭하면 팝업창에서 더 크게 볼 수 있습니다. (Ctrl+마우스 휠로 확대/축소 가능)"><i data-lucide="maximize-2" style="width: 18px; height: 18px; margin-right: 5px;"></i>ERD 형태</h3>
                                <div style="width: 100%; overflow-x: auto; padding: 20px; background: var(--bg-card); border-radius: 8px; border: 1px solid var(--border-color);">
                                    <div class="mermaid" style="padding-bottom: 20px; text-align: center; display: flex; justify-content: center; min-width: 100%; width: max-content; margin: 0 auto; opacity: 0; transition: opacity 0.15s ease;">
                                        ${mermaidSyntax}
                                    </div>
                                </div>
                            </div>
                        </div>
                    `;
                }
                
                relationContainer.innerHTML = layoutHTML;

                if (typeof lucide !== 'undefined') {
                    lucide.createIcons();
                }
                
                if (typeof mermaid !== 'undefined') {
                    try {
                        mermaid.init(undefined, document.querySelectorAll('#relation-container .mermaid'));
                        
                        setTimeout(() => {
                            const svg = document.querySelector('#relation-container .mermaid svg');
                            if (svg) {
                                // 테이블명은 svg <text>가 아니라 <g class="label"><foreignObject><div>...</div></foreignObject></g>
                                // 형태의 HTML 라벨로 렌더링되는데, mermaid가 foreignObject 폭을 실제 텍스트 폭보다
                                // 좁게 계산하는 경우가 있어(내부 폭 측정 버그) 글자가 그 경계에서 잘려 보임.
                                // → 각 foreignObject의 실제 내용 폭(scrollWidth)을 재보고 부족하면 foreignObject와
                                //   같은 그룹의 엔티티 박스(rect)까지 함께 넓혀줌(가운데 정렬 유지).
                                try {
                                    svg.querySelectorAll('foreignObject').forEach(fo => {
                                        const div = fo.querySelector('div');
                                        if (!div) return;
                                        const neededWidth = div.scrollWidth;
                                        const curWidth = parseFloat(fo.getAttribute('width')) || 0;
                                        if (neededWidth <= curWidth) return;
                                        const extra = neededWidth - curWidth + 4;
                                        const newWidth = curWidth + extra;
                                        fo.setAttribute('width', newWidth);

                                        const labelG = fo.parentElement;
                                        const m = labelG && labelG.getAttribute('transform') &&
                                            labelG.getAttribute('transform').match(/translate\(([-0-9.]+),\s*([-0-9.]+)\)/);
                                        if (m) {
                                            labelG.setAttribute('transform', `translate(${parseFloat(m[1]) - extra / 2}, ${m[2]})`);
                                        }

                                        const nodeG = labelG && labelG.parentElement;
                                        const rect = nodeG && nodeG.querySelector('rect');
                                        if (rect) {
                                            const rectWidth = parseFloat(rect.getAttribute('width')) || 0;
                                            if (newWidth > rectWidth) {
                                                const rectExtra = newWidth - rectWidth;
                                                rect.setAttribute('width', rectWidth + rectExtra);
                                                rect.setAttribute('x', (parseFloat(rect.getAttribute('x')) || 0) - rectExtra / 2);
                                            }
                                        }
                                    });
                                } catch (labelErr) {
                                    console.error('ERD 라벨 폭 보정 실패:', labelErr);
                                }

                                // 위에서 라벨/박스 폭을 넓혔으므로, svg 자체의 viewBox도 실제 렌더링된
                                // 컨텐츠 전체를 다시 측정해서 맞춰준다(안 그러면 넓어진 박스가 기존
                                // viewBox 경계 밖으로 나가 다시 잘려 보일 수 있음).
                                try {
                                    const rootG = svg.querySelector('g');
                                    if (rootG) {
                                        const bbox = rootG.getBBox();
                                        const margin = 15;
                                        svg.setAttribute('viewBox', `${bbox.x - margin} ${bbox.y - margin} ${bbox.width + margin * 2} ${bbox.height + margin * 2}`);
                                        svg.setAttribute('width', bbox.width + margin * 2);
                                        svg.setAttribute('height', bbox.height + margin * 2);
                                    }
                                } catch (bboxErr) {
                                    console.error('ERD viewBox 보정 실패:', bboxErr);
                                }

                                // 잘린 채로 그려진 최초 박스가 눈에 보였다가 위 보정으로 뒤늦게
                                // 정상 크기로 바뀌는 깜빡임(FOUC)을 막기 위해, 위 보정이 다 끝난
                                // 지금 시점에만 컨테이너를 보이게 전환한다(그 전까지는 opacity:0으로 숨겨둠).
                                const mermaidContainer = svg.closest('.mermaid');
                                if (mermaidContainer) {
                                    mermaidContainer.style.opacity = '1';
                                }

                                // Add pointer cursor specifically to boxes and text (not the whole SVG background)
                                const clickableElements = svg.querySelectorAll('.entityBox, .node, text, span, foreignObject');
                                clickableElements.forEach(el => {
                                    const txt = el.textContent ? el.textContent.trim() : '';
                                    if (txt && txt.length < 40 && /^[A-Za-z0-9_$]+$/.test(txt.replace(/[^A-Za-z0-9_$]/g, '')) && txt !== 'REFERENCES') {
                                        el.style.cursor = 'pointer';
                                    }
                                });
                                
                                const popupBtn = document.getElementById('erd-popup-btn');
                                if (popupBtn) {
                                    // 인페이지 모달(#image-modal) 대신 실제 팝업창으로 변경 (사용자 요청,
                                    // 2026-08-29) - session-detail.html 등 이 앱의 다른 팝업들과 같은 방식.
                                    // ERD는 정적 페이지가 아니라 그때그때 렌더링되는 내용이라, 별도 HTML
                                    // 파일로 빼는 대신 이미 렌더링된 svg.outerHTML을 빈 팝업에 그대로 써넣는다
                                    // (Ctrl+휠 확대/축소도 팝업 자체 document에 새로 붙여야 동작함 - 부모
                                    // 창의 핸들러는 별개의 document인 팝업에는 적용되지 않음).
                                    popupBtn.addEventListener('click', () => {
                                        const popup = window.open('', 'dbagent_erd_popup', 'width=1200,height=800,resizable=yes,scrollbars=yes');
                                        if (!popup) return;
                                        popup.document.write(`
                                            <!DOCTYPE html>
                                            <html>
                                            <head>
                                                <meta charset="UTF-8">
                                                <title>ERD 형태</title>
                                                <style>
                                                    /* mermaid.initialize가 theme:'dark'라서(app.js) 관계선/텍스트가 밝은 색으로
                                                       나온다 - 흰 배경이면 테이블이 몇 개 안 될 땐 안 보이다가, 테이블이 늘어나
                                                       선으로 서로 연결되면 그 선이 흰 배경에 묻혀 안 보이게 된다 (사용자 확인,
                                                       2026-08-29). 본문 화면(var(--bg-main))과 같은 어두운 배경으로 맞춘다. */
                                                    body { margin: 0; padding: 50px; display: flex; justify-content: center; align-items: center; min-height: 100vh; box-sizing: border-box; background: #0d0d0d; }
                                                    svg { max-width: none; transition: transform 0.1s ease; transform-origin: center center; }
                                                </style>
                                            </head>
                                            <body>
                                                ${svg.outerHTML}
                                                <script>
                                                    (function () {
                                                        var scale = 1;
                                                        var svgEl = document.querySelector('svg');
                                                        document.body.addEventListener('wheel', function (e) {
                                                            if (e.ctrlKey) {
                                                                e.preventDefault();
                                                                scale += e.deltaY * -0.001;
                                                                scale = Math.min(Math.max(0.125, scale), 4);
                                                                svgEl.style.transform = 'scale(' + scale + ')';
                                                            }
                                                        }, { passive: false });
                                                    })();
                                                </script>
                                            </body>
                                            </html>
                                        `);
                                        popup.document.close();
                                    });
                                }
                                
                                svg.addEventListener('click', (e) => {
                                    let current = e.target;
                                    let clickedTableName = null;
                                    
                                    while (current && current !== svg) {
                                        // 1. If we hit a known Mermaid node class
                                        if (current.classList && (current.classList.contains('entityBox') || current.classList.contains('node'))) {
                                            clickedTableName = current.textContent.trim();
                                            break;
                                        }
                                        
                                        // 2. Or if we directly clicked on text or span (foreignObject)
                                        if (current.tagName && ['text', 'span'].includes(current.tagName.toLowerCase())) {
                                            clickedTableName = current.textContent.trim();
                                            break;
                                        }
                                        
                                        current = current.parentNode;
                                    }
                                    
                                    if (clickedTableName) {
                                        // Extract alphanumeric table name
                                        clickedTableName = clickedTableName.replace(/[^A-Za-z0-9_$]/g, '');
                                        if (clickedTableName && clickedTableName !== 'REFERENCES' && clickedTableName.toLowerCase() !== 'has' && clickedTableName.toLowerCase() !== 'manages') {
                                            if (typeof window.showTableInfoModal === 'function') {
                                                window.showTableInfoModal(clickedTableName.toUpperCase());
                                            }
                                        }
                                    }
                                });
                            } else {
                                // svg를 못 찾은 경우(렌더링 실패 등)에도 opacity:0으로 숨긴 컨테이너가
                                // 영영 안 보이는 상태로 남지 않도록 안전망으로 다시 보이게 해준다.
                                document.querySelectorAll('#relation-container .mermaid').forEach(el => {
                                    el.style.opacity = '1';
                                });
                            }
                        }, 500);

                    } catch (err) {
                        console.error('Mermaid render error:', err);
                        document.querySelectorAll('#relation-container .mermaid').forEach(el => {
                            el.style.opacity = '1';
                        });
                    }
                }

            } catch (error) {
                relationContainer.innerHTML = `<div style="padding: 20px; color: var(--danger); text-align: center;">오류 발생: ${error.message}</div>`;
            }
        });

        relationSearchInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') {
                relationSearchBtn.click();
            }
        });
    }

    // Tablespace Logic (Mock Version)
    const tsRefreshBtn = document.getElementById('tablespace-refresh-btn');
    const tsTbody = document.getElementById('tablespace-tbody');

    if (tsRefreshBtn && tsTbody) {
        const tsTotalMbEl = document.getElementById('tablespace-total-mb');
        const tsUsedMbEl = document.getElementById('tablespace-used-mb');
        const tsTotalPctEl = document.getElementById('tablespace-total-pct');
        const resetTablespaceTotals = () => {
            if (tsTotalMbEl) tsTotalMbEl.textContent = '-- MB';
            if (tsUsedMbEl) tsUsedMbEl.textContent = '-- MB';
            if (tsTotalPctEl) tsTotalPctEl.textContent = '--%';
        };
        // 요청 세대 번호 - DB를 빠르게 바꾸면 이전 DB 응답이 나중에 도착할 수 있다. 가장 최근 요청의
        // 응답만 화면에 반영하고, 나머지는 버린다(체크리스트 7-1, 2026-09-25).
        let tsRequestSeq = 0;

        tsRefreshBtn.addEventListener('click', async () => {
            // 조회 시작 즉시 목록과 합계를 함께 비운다 - 예전엔 목록만 비우고 합계(전체 할당량/사용량/
            // 사용률)는 응답이 올 때까지 이전 DB 값이 그대로 남아 있었다(체크리스트 7-1).
            tsTbody.innerHTML = '';
            resetTablespaceTotals();
            if (!window.currentDbId) {
                tsTbody.innerHTML = '<tr><td colspan="6" style="text-align:center; padding: 30px;">DB를 먼저 선택해주세요.</td></tr>';
                return;
            }
            const mySeq = ++tsRequestSeq;
            const myDbId = window.currentDbId;
            window.tablespaceInFlightDbId = myDbId;
            const isStale = () => mySeq !== tsRequestSeq || myDbId !== window.currentDbId;

            const icon = tsRefreshBtn.querySelector('i');
            if (icon) icon.classList.add('spinning');

            const tsLoadingOverlay = document.getElementById('tablespace-loading-overlay');
            if (tsLoadingOverlay) tsLoadingOverlay.style.display = 'flex';

            try {
                const response = await fetch(`/api/tablespace?db_id=${myDbId}&token=${encodeURIComponent(getToken())}`);
                if (isStale()) return;
                if (response.ok) {
                    const data = await response.json();
                    if (isStale()) return;

                    if (data.error) {
                        tsTbody.innerHTML = `<tr><td colspan="6" style="color:#d03b3b; text-align:center; padding: 30px;">DB Error: ${data.error}</td></tr>`;
                        if (tsTotalMbEl) tsTotalMbEl.textContent = '-- MB';
                        if (tsUsedMbEl) tsUsedMbEl.textContent = '-- MB';
                        if (tsTotalPctEl) tsTotalPctEl.textContent = '--%';
                    } else if (data.length === 0) {
                        tsTbody.innerHTML = '<tr><td colspan="6" style="text-align:center; padding: 30px;">테이블 스페이스 정보가 없습니다.</td></tr>';
                        if (tsTotalMbEl) tsTotalMbEl.textContent = '0 MB';
                        if (tsUsedMbEl) tsUsedMbEl.textContent = '0 MB';
                        if (tsTotalPctEl) tsTotalPctEl.textContent = '0%';
                    } else {
                        tsTbody.innerHTML = '';
                        let sumTotalMb = 0;
                        let sumUsedMb = 0;
                        data.forEach(ts => {
                            sumTotalMb += Number(ts.total_mb) || 0;
                            sumUsedMb += Number(ts.used_mb) || 0;
                            const free = ts.free_mb;
                            const numPct = Number(ts.used_pct);
                            const displayPct = numPct.toFixed(1);
                            let barClass = '';
                            if (numPct >= 90) barClass = 'danger';
                            else if (numPct >= 80) barClass = 'warning';
                            
                            let statusBadge = 'online';
                            if (ts.status && ts.status.toUpperCase() !== 'ONLINE') {
                                statusBadge = 'offline';
                            }

                            const row = `
                                <tr>
                                    <td><a href="#" class="tablespace-name-link" style="color: var(--primary-color); text-decoration: underline; cursor: pointer;" onclick="window.showTablespaceDatafiles('${ts.tablespace_name}'); return false;">${ts.tablespace_name}</a></td>
                                    <td><span class="status-badge ${statusBadge}">${ts.status}</span></td>
                                    <td>${ts.total_mb.toLocaleString()}</td>
                                    <td>${ts.used_mb.toLocaleString()}</td>
                                    <td>${free.toLocaleString()}</td>
                                    <td>
                                        <div class="progress-bar-container">
                                            <div class="progress-bar ${barClass}" style="width: ${numPct}%;"></div>
                                            <span>${displayPct}%</span>
                                        </div>
                                    </td>
                                </tr>
                            `;
                            tsTbody.insertAdjacentHTML('beforeend', row);
                        });
                        if (tsTotalMbEl) tsTotalMbEl.textContent = `${sumTotalMb.toLocaleString()} MB`;
                        if (tsUsedMbEl) tsUsedMbEl.textContent = `${sumUsedMb.toLocaleString()} MB`;
                        if (tsTotalPctEl) tsTotalPctEl.textContent = sumTotalMb > 0 ? `${((sumUsedMb / sumTotalMb) * 100).toFixed(1)}%` : '0%';
                    }
                } else {
                    tsTbody.innerHTML = `<tr><td colspan="6" style="color:#d03b3b; text-align:center; padding: 30px;">API 서버 오류가 발생했습니다.</td></tr>`;
                    resetTablespaceTotals();
                }
            } catch (error) {
                if (isStale()) return;
                console.error('Tablespace fetch error:', error);
                tsTbody.innerHTML = `<tr><td colspan="6" style="color:#d03b3b; text-align:center; padding: 30px;">데이터를 불러오는 데 실패했습니다: ${error.message}</td></tr>`;
                resetTablespaceTotals();
            } finally {
                // 스피너/오버레이는 가장 최근 요청이 끝날 때만 내린다 - 이전 DB 요청이 먼저 끝나며
                // 새 DB 조회 중 표시를 지워버리지 않게.
                if (mySeq === tsRequestSeq) {
                    if (icon) icon.classList.remove('spinning');
                    if (tsLoadingOverlay) tsLoadingOverlay.style.display = 'none';
                    window.tablespaceInFlightDbId = null;
                }
            }
        });
    }

    // TM LOCK Logic
    const tmlockRefreshBtn = document.getElementById('tmlock-refresh-btn');
    const tmlockToggleBtn = document.getElementById('tmlock-toggle-btn');
    const tmlockIntervalInput = document.getElementById('tmlock-refresh-interval');
    const tmlockTbody = document.getElementById('tmlock-tbody');
    
    let tmlockTimer = null;
    let isAutoRefreshing = false;

    async function fetchTMLocks() {
        if (!tmlockTbody) return;
        if (!window.currentDbId) {
            tmlockTbody.innerHTML = '<tr><td colspan="14" style="text-align:center; padding: 30px;">DB를 먼저 선택해주세요.</td></tr>';
            return;
        }

        try {
            const icon = tmlockRefreshBtn.querySelector('i');
            if (icon) icon.classList.add('spinning');
            
            const response = await fetch(`/api/tmlock?db_id=${window.currentDbId || ""}&token=${encodeURIComponent(getToken())}`);
            if (!response.ok) throw new Error('Network response was not ok');
            const data = await response.json();
            
            if (!data || data.length === 0) {
                tmlockTbody.innerHTML = `
                    <tr>
                        <td colspan="14" style="text-align: center; padding: 40px; color: var(--text-secondary);">
                            현재 감지된 TM LOCK 대상건이 없습니다. (정상)
                        </td>
                    </tr>
                `;
                if (window.dbagentSyncKillButtons) window.dbagentSyncKillButtons();
                if (icon) icon.classList.remove('spinning');
                return;
            }
            
            const formatDuration = (seconds) => {
                if (seconds === null || seconds === undefined) return 'N/A';
                if (seconds < 60) return `${seconds}초`;
                const m = Math.floor(seconds / 60);
                const s = seconds % 60;
                if (m < 60) return `${m}분 ${s}초`;
                const h = Math.floor(m / 60);
                const rm = m % 60;
                return `${h}시간 ${rm}분 ${s}초`;
            };

            // ---- Holder/Waiter 트리 만들기 (RDB 대시보드의 buildLockTree 와 같은 방식) ----
            //
            // /api/tmlock 은 "막고 있는 세션(block=1)" 마다 그 세션을 기다리는 waiters 를 붙여 주는
            // <b>2단 구조</b>다. A가 B를, B가 C를 막는 체인이면 A(waiters=[B]) 와 B(waiters=[C]) 가
            // 각각 최상위로 나와, 예전 화면에서는 B가 두 번(홀더로 한 번, A의 대기자로 한 번) 보이고
            // 체인이라는 사실이 드러나지 않았다. waiter -> holder 간선을 이어 실제 트리로 세우면
            // A └─ B └─ C 로 이어지고, 중간의 B는 역할이 둘 다이므로 BOTH 로 표시된다.
            const nodes = {};
            function ensureNode(row, isHolder) {
                const key = String(row.sid);
                if (!nodes[key]) nodes[key] = { data: row, isHolder: false, isWaiter: false, children: [] };
                const n = nodes[key];
                // 홀더 행에는 보유 모드가, 대기 행에는 요청 모드/대기 시간이 담긴다. 대기 쪽 정보가
                // "무엇을 얼마나 기다리는가" 를 말해 주므로 그쪽을 우선해 남긴다.
                if (!isHolder || !n.isWaiter) n.data = row;
                if (isHolder) n.isHolder = true; else n.isWaiter = true;
                return n;
            }
            data.forEach(holder => {
                const h = ensureNode(holder, true);
                (holder.waiters || []).forEach(waiter => {
                    const w = ensureNode(waiter, false);
                    if (h.children.indexOf(w) < 0) h.children.push(w);
                });
            });
            const allNodes = Object.keys(nodes).map(k => nodes[k]);
            const hasParent = {};
            allNodes.forEach(n => n.children.forEach(c => { hasParent[String(c.data.sid)] = true; }));
            let roots = allNodes.filter(n => !hasParent[String(n.data.sid)]);
            // 루트가 하나도 없으면 서로가 서로를 기다리는 순환(교착)이다. 그대로 두면 아무것도
            // 안 그려지므로 아무 노드나 하나를 시작점으로 세운다(RDB 쪽과 같은 처리).
            let cyclic = false;
            if (!roots.length && allNodes.length) { cyclic = true; roots = [allNodes[0]]; }

            // 트리를 화면 순서대로 펴면서 가지 문자열(prefix)을 만든다.
            const flat = [];
            (function flatten(list, depth, prefix, path) {
                list.forEach((node, i) => {
                    const sid = String(node.data.sid);
                    const repeated = path.indexOf(sid) >= 0;
                    const isLast = i === list.length - 1;
                    flat.push({ node: node, depth: depth, isLast: isLast, prefix: prefix, repeated: repeated });
                    if (repeated) return;   // 순환 - 여기서 끊지 않으면 무한 재귀다
                    flatten(node.children, depth + 1,
                            depth === 0 ? '' : prefix + (isLast ? '   ' : '│  '),
                            path.concat([sid]));
                });
            })(roots, 0, '', []);

            let tableHtml = '';
            flat.forEach(item => {
                const n = item.node;
                const d = n.data;
                const role = (n.isHolder && n.isWaiter) ? 'both' : (n.isHolder ? 'holder' : 'waiter');
                const roleLabel = role === 'both' ? 'BOTH' : (role === 'holder' ? 'HOLDER' : 'WAITER');
                const roleTitle = role === 'both'
                    ? '다른 세션을 막고 있으면서 자신도 다른 세션을 기다리는 중입니다(체인 중간).'
                    : (role === 'holder' ? '락을 쥐고 있는 세션입니다.' : '락을 기다리는 세션입니다.');
                const branch = item.depth > 0
                    ? `<span class="lock-branch">${item.prefix}${item.isLast ? '└─ ' : '├─ '}</span>`
                    : '';
                const cycle = item.repeated ? '<span class="lock-cycle">↻ 순환</span>' : '';
                tableHtml += `
                    <tr class="tmlock-row clickable-session-row" data-sid="${d.sid}" data-serial="${d.serial || ''}">
                        <td style="text-align: center;"><input type="checkbox" class="tmlock-checkbox" data-sid="${d.sid}" data-serial="${d.serial}" onclick="event.stopPropagation();"></td>
                        <td>${branch}<span class="lock-badge ${role}" title="${roleTitle}">${roleLabel}</span><span class="lock-sid">${d.sid}</span>${cycle}</td>
                        <td>${d.serial}</td>
                        <td>${d.spid || ''}</td>
                        <td>${d.username}</td>
                        <td>${d.lock_type}</td>
                        <td>${d.mode}</td>
                        <td>${d.object_waiting || ''}</td>
                        <td>${formatDuration(d.time)}</td>
                        <td>${d.login || ''}</td>
                        <td>${d.status || ''}</td>
                        <td>${d.program || ''}</td>
                        <td>${d.machine || ''}</td>
                        <td>${d.osuser || ''}</td>
                    </tr>
                `;
            });
            if (cyclic) {
                tableHtml += `
                    <tr><td colspan="14" style="padding: 10px 12px; color: var(--warning); font-size: 0.9rem;">
                        ⚠ 서로가 서로를 기다리는 순환 구조입니다(교착 가능성). 시작점을 특정할 수 없어 한 세션을 맨 위에 두었습니다.
                    </td></tr>
                `;
            }

            // Oracle SID는 재사용되므로 SID만으로는 세션을 특정할 수 없다 - SID+SERIAL# 복합키로 대조.
            const checkedTmlockKeys = new Set(Array.from(document.querySelectorAll('.tmlock-checkbox:checked'))
                .map(cb => cb.getAttribute('data-sid') + ':' + cb.getAttribute('data-serial')));
            tmlockTbody.innerHTML = tableHtml;
            document.querySelectorAll('.tmlock-checkbox').forEach(cb => {
                if (checkedTmlockKeys.has(cb.getAttribute('data-sid') + ':' + cb.getAttribute('data-serial'))) {
                    cb.checked = true;
                }
            });
            // 표를 다시 그리면 체크 상태가 복원되므로 버튼 상태도 다시 맞춘다(위임 리스너는
            // 사용자의 change 만 받기 때문에 여기서 한 번 더 호출해야 한다).
            if (window.dbagentSyncKillButtons) window.dbagentSyncKillButtons();

            // Re-attach select all event listener if it exists
            const selectAllCb = document.getElementById('tmlock-select-all');
            if (selectAllCb) {
                // Remove old event listeners by replacing the element to prevent multiple bindings
                const newSelectAllCb = selectAllCb.cloneNode(true);
                selectAllCb.parentNode.replaceChild(newSelectAllCb, selectAllCb);
                
                newSelectAllCb.addEventListener('change', (e) => {
                    const cbs = document.querySelectorAll('.tmlock-checkbox');
                    cbs.forEach(cb => cb.checked = e.target.checked);
                });
            }

            // Row clicks are handled by the global `.clickable-session-row` delegate (opens
            // session-detail.html as a separate, draggable OS window) - see the document-level
            // click listener near showSelectedSessionsPopup. No per-row listener needed here.

            if (typeof lucide !== 'undefined') {
                lucide.createIcons();
            }
            
            if (icon) icon.classList.remove('spinning');
        } catch (error) {
            tmlockTbody.innerHTML = `<tr><td colspan="14" style="color:var(--danger); padding:20px; text-align:center;">데이터를 불러오는 데 실패했습니다: ${error.message}</td></tr>`;
        }
    }

    if (tmlockRefreshBtn) {
        tmlockRefreshBtn.addEventListener('click', fetchTMLocks);
    }

    if (tmlockToggleBtn) {
        tmlockToggleBtn.addEventListener('click', () => {
            if (isAutoRefreshing) {
                // Stop auto-refresh
                clearTimeout(tmlockTimer);
                isAutoRefreshing = false;
                tmlockToggleBtn.textContent = '자동 갱신 시작';
                tmlockToggleBtn.classList.remove('danger-btn');
                tmlockToggleBtn.classList.add('primary-btn');
                tmlockRefreshBtn.disabled = false;
            } else {
                // Start auto-refresh
                isAutoRefreshing = true;
                tmlockToggleBtn.textContent = '자동 갱신 중지';
                tmlockToggleBtn.classList.remove('primary-btn');
                tmlockToggleBtn.classList.add('danger-btn');
                tmlockRefreshBtn.disabled = true;
                
                (async function loop() {
                    if (!isAutoRefreshing) return;
                    await fetchTMLocks();
                    if (!isAutoRefreshing) return;
                    const interval = Math.max(1, parseInt(tmlockIntervalInput.value) || 5);
                    tmlockTimer = setTimeout(loop, interval * 1000);
                })();
            }
        });
        // 기본값을 자동 갱신 켜짐으로(오케스트레이터 요청, 2026-09-18: "OFF가 디폴트인 것 같은데 ON으로
        // 해달라") - 대시보드 자동 갱신과 같은 패턴(항상 켜진 채로 시작, 이후 버튼으로 직접 껐다 켰다).
        tmlockToggleBtn.click();
    }

    const tmlockKillBtn = document.getElementById('tmlock-kill-btn');
    if (tmlockKillBtn) {
        tmlockKillBtn.addEventListener('click', async () => {
            const checkboxes = document.querySelectorAll('.tmlock-checkbox:checked');
            if (checkboxes.length === 0) {
                alert('Kill할 세션을 선택해주세요.');
                return;
            }
            
            if (!confirm(`선택한 ${checkboxes.length}개의 세션을 Kill 하시겠습니까?`)) {
                return;
            }
            
            const sessions = Array.from(checkboxes).map(cb => ({
                sid: cb.getAttribute('data-sid'),
                serial: cb.getAttribute('data-serial')
            }));
            
            try {
                const response = await fetch(`/api/kill_session?db_id=${window.currentDbId || ""}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ sessions, token: sessionStorage.getItem('dbagent_token') })
                });

                const data = await response.json();
                if (data.error) throw new Error(data.error);
                
                let successCount = 0;
                let failCount = 0;
                data.results.forEach(r => {
                    if (r.status === 'killed') successCount++;
                    else failCount++;
                });
                
                alert(`처리 결과:\n성공: ${successCount}건\n실패: ${failCount}건`);
                fetchTMLocks(); // refresh
            } catch (error) {
                alert('세션 Kill 처리 중 오류가 발생했습니다: ' + error.message);
            }
        });
    }

    // Session Monitoring Logic
    const sessionRefreshBtn = document.getElementById('session-refresh-btn');
    const sessionToggleBtn = document.getElementById('session-toggle-btn');
    const sessionIntervalInput = document.getElementById('session-refresh-interval');
    const sessionTbody = document.getElementById('session-tbody');
    
    let sessionTimer = null;
    let isSessionAutoRefreshing = false;
    let ashActivityChart = null;
    let ashTopSqlChart = null;
    // Trace 산점도(sessionScatterChart)/SCATTER_CATEGORIES/scatterDataPoints와 그 DB 전환 스냅샷
    // 캐시(dbSessionHistoryCache 등, 사용자 요청 2026-09-01/2026-09-02)는 2026-09-22 4단계에서 Top SQL
    // Activity Timeline으로 완전 대체되며 전부 제거됨(구현단계 체크리스트 4단계 참고) - showSelected
    // SessionsPopup()/session-list.html은 History 탭 산점도가 계속 쓰므로 그 경로는 그대로 남아있다.

    // 색상박스+글씨로 된 커스텀 범례를 만들고, 클릭할 때마다 on/off 스위치처럼 글씨가 밝아지거나(켜짐)
    // 어두워지며(꺼짐) 해당 Chart.js 데이터셋을 보이거나 숨긴다(사용자 요청 2026-08-31: 체크박스 대신
    // 클릭식 밝기 토글). container.dataset.built로 한 번만 그려서, DB 전환으로 차트가 재생성돼도
    // (resetSessionMonitor) on/off 상태 자체는 그대로 유지되고, 새로 만들어진 차트에 그 상태를 다시
    // 적용해주기만 하면 된다.
    function buildChartLegend(containerId, series, chartGetter) {
        const container = document.getElementById(containerId);
        if (!container || container.dataset.built) return;
        container.dataset.built = '1';
        container.innerHTML = series.map((s, i) => `
            <span class="chart-legend-item" data-idx="${i}" data-active="true" style="display:flex; align-items:center; gap:5px; cursor:pointer; font-size:0.78rem; font-weight:600; user-select:none; color: var(--text-main); opacity:1; transition: color 0.15s, opacity 0.15s;">
                <span style="display:inline-block; width:10px; height:10px; border-radius:2px; background:${s.color}; flex:none;"></span>
                ${s.label}
            </span>
        `).join('');
        container.querySelectorAll('.chart-legend-item').forEach(item => {
            item.addEventListener('click', () => {
                const chart = chartGetter();
                if (!chart) return;
                const next = item.dataset.active !== 'true';
                item.dataset.active = String(next);
                item.style.color = next ? 'var(--text-main)' : 'var(--text-muted)';
                item.style.opacity = next ? '1' : '0.45';
                chart.setDatasetVisibility(parseInt(item.dataset.idx), next);
                chart.update();
            });
        });
    }

    // DB 전환 등으로 차트가 파괴되고 새로 만들어졌을 때, 기존 범례의 on/off 상태(이미 꺼둔 계열이
    // 있다면 그 상태)를 새 차트 인스턴스에도 그대로 반영 - 안 그러면 범례는 꺼진 채인데 새로 만들어진
    // 차트는 Chart.js 기본값대로 전부 다시 보이는 상태로 어긋나게 된다.
    function applyLegendVisibility(containerId, chart) {
        if (!chart) return;
        document.querySelectorAll(`#${containerId} .chart-legend-item`).forEach(item => {
            chart.setDatasetVisibility(parseInt(item.dataset.idx), item.dataset.active === 'true');
        });
        chart.update();
    }

    // 세션 목록 요청 세대 - DB를 바꾼 뒤 늦게 도착한 이전 DB 응답이 새 DB 목록을 덮어쓰던 버그 방지
    // (체크리스트 1-5, 2026-09-25 CDP로 재현: B로 전환 후 도착한 A 응답이 B 화면 목록에 그려졌음).
    const sessionRequestGuard = dbagentLatestRequest();

    async function fetchSessions() {
        if (!sessionTbody) return;
        if (!window.currentDbId) {
            sessionTbody.innerHTML = '<tr><td colspan="8" style="text-align:center; padding: 30px;">DB를 먼저 선택해주세요.</td></tr>';
            return;
        }

        const req = sessionRequestGuard.begin();
        const icon = sessionRefreshBtn ? sessionRefreshBtn.querySelector('i') : null;
        try {
            if (icon) icon.classList.add('spinning');

            const [response, extraResponse] = await Promise.all([
                fetch(`/api/session?db_id=${req.dbId}&token=${encodeURIComponent(getToken())}`),
                fetch(`/api/session_extra?db_id=${req.dbId}&token=${encodeURIComponent(getToken())}`)
            ]);
            if (req.isStale()) return;
            if (!response.ok) throw new Error('Network response was not ok');
            const data = await response.json();

            if (data.error) throw new Error(data.error);

            // session_extra is best-effort (feeds the trend lines + the 3 extra tabs below) - a
            // failure there shouldn't take down the primary Active Session list/table.
            let extra = { active_transactions: [], parallel_sessions: [], pending_2pc: [], tx_lock_count: 0, tx_lock_sids: [], tm_lock_count: 0, tm_lock_sids: [] };
            try {
                if (extraResponse.ok) {
                    const extraData = await extraResponse.json();
                    if (!extraData.error) extra = extraData;
                }
            } catch (extraErr) {
                console.error('Failed to fetch session_extra:', extraErr);
            }
            if (req.isStale()) return;

            let activeCount = 0;
            let inactiveCount = 0;
            const activeSessions = [];

            data.forEach(session => {
                if (session.status.toUpperCase() === 'ACTIVE') {
                    activeCount++;
                    activeSessions.push(session);
                } else {
                    inactiveCount++;
                }
            });

            // 좌측 "실시간 세션 추이" 라인 차트는 2026-09-22 Active Session Wait Class 차트(§0 결정 2 -
            // 완전 대체)로 교체됨 - sessionHistory 누적/렌더 로직은 fetchAshActivity()로 이전.
            // 우측 Trace 산점도는 2026-09-22 4단계에서 Top SQL Activity Timeline으로 완전 대체되고
            // 제거됨(구현단계 체크리스트 4단계) - 드래그→세션 상세는 이제 그 차트 쪽 드래그로 대체
            // (initAshTopSqlBrush, 아래쪽). showSelectedSessionsPopup()/session-list.html은 History
            // 탭의 자체 산점도가 계속 쓰므로 그대로 유지.

            // Update Table (Only Active Sessions)
            if (activeSessions.length === 0) {
                sessionTbody.innerHTML = '<tr><td colspan="8" style="text-align:center; padding: 30px;">현재 ACTIVE 상태인 세션이 없습니다.</td></tr>';
                fitSessListScroll(sessionTbody);
                // 표가 비면 고를 것이 없다 - 선택이 남아 있던 상태에서 세션이 사라져도 버튼이
                // 활성인 채로 남지 않도록 여기서도 맞춘다.
                if (window.dbagentSyncKillButtons) window.dbagentSyncKillButtons();
            } else {
                const maxDuration = activeSessions.reduce((max, s) => Math.max(max, Number(s.duration_time) || 0), 1);
                let html = '';
                activeSessions.forEach(session => {
                    const statusClass = 'online';
                    const durationVal = session.duration_time !== null ? Number(session.duration_time) : 0;
                    const durationPct = Math.min((durationVal / maxDuration) * 100, 100);
                    const durationHtml = session.duration_time !== null ? `<div style="display: flex; align-items: center; gap: 8px;"><div style="flex-grow: 1; background-color: var(--track-bg); height: 8px; border-radius: 4px; overflow: hidden; width: 60px;"><div style="width: ${durationPct}%; height: 100%; background-color: #3987e5; border-radius: 4px;"></div></div><span style="min-width: 30px; text-align: right;">${durationVal}</span></div>` : '-';
                    html += `
                        <tr class="clickable-session-row" style="cursor:pointer;" data-sid="${session.sid}" data-serial="${session.serial || ''}" data-sql_id="${session.sql_id || ''}">
                            <td style="text-align:center;" onclick="event.stopPropagation();"><input type="checkbox" class="session-checkbox" data-sid="${session.sid}" data-serial="${session.serial}"></td>
                            <td>${session.db_name || '-'}</td>
                            <td><span class="status-badge ${statusClass}">${session.status}</span></td>
                            <td>${session.sid}</td>
                            <td>${session.serial}</td>
                            <td>${session.server_pid || '-'}</td>
                            <td>${durationHtml}</td>
                            <td>${(() => {
                                let waitHtml = `<div style="color: var(--text-secondary);">-</div>`;
                                if (session.session_wait_pct && session.session_wait_pct.includes(',')) {
                                    const [cpu, uio, sio, latch, txlock, tmlock, other] = session.session_wait_pct.split(',').map(Number);
                                    if (cpu + uio + sio + latch + txlock + tmlock + other > 0) {
                                        waitHtml = `<div style="display: flex; width: 100px; height: 12px; border-radius: 6px; overflow: hidden; background-color: var(--track-bg);" title="CPU: ${cpu}%, User I/O: ${uio}%, Sys I/O: ${sio}%, Latch: ${latch}%, TX Lock: ${txlock}%, TM Lock: ${tmlock}%, Other: ${other}%"><div style="width: ${cpu}%; background-color: #22d3ee;" title="CPU: ${cpu}%"></div><div style="width: ${uio}%; background-color: #2ecc71;" title="User I/O: ${uio}%"></div><div style="width: ${sio}%; background-color: #e67e22;" title="Sys I/O: ${sio}%"></div><div style="width: ${latch}%; background-color: #808000;" title="Latch: ${latch}%"></div><div style="width: ${txlock}%; background-color: #7c3aed;" title="TX Lock: ${txlock}%"></div><div style="width: ${tmlock}%; background-color: #be123c;" title="TM Lock: ${tmlock}%"></div><div style="width: ${other}%; background-color: var(--text-muted);" title="Other: ${other}%"></div></div>`;
                                    }
                                }
                                return waitHtml;
                            })()}</td>
                            <td>${session.sql_id || '-'}</td>
                            <td>${session.event_name || '-'}</td>
                            <td>${session.plan_hash_value || '-'}</td>
                            <td><div style="max-width:200px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;" title="${session.sql_text || ''}">${session.sql_text || '-'}</div></td>
                            <td>${session.machine_name || '-'}</td>
                            <td>${session.osuser || '-'}</td>
                            <td>${session.username || '-'}</td>
                            <td>${session.program_name || '-'}</td>
                        </tr>
                    `;
                });
                // Oracle SID는 재사용되므로 SID만으로는 세션을 특정할 수 없다 - SID+SERIAL# 복합키로 대조.
                const checkedSessionKeys = new Set(Array.from(document.querySelectorAll('.session-checkbox:checked'))
                    .map(cb => cb.getAttribute('data-sid') + ':' + cb.getAttribute('data-serial')));
                sessionTbody.innerHTML = html;
                fitSessListScroll(sessionTbody);
                document.querySelectorAll('.session-checkbox').forEach(cb => {
                    if (checkedSessionKeys.has(cb.getAttribute('data-sid') + ':' + cb.getAttribute('data-serial'))) {
                        cb.checked = true;
                    }
                });
                if (window.dbagentSyncKillButtons) window.dbagentSyncKillButtons();
            }

            renderActiveTransactionsTab(extra.active_transactions);
            renderParallelSessionsTab(extra.parallel_sessions);
            renderPending2pcTab(extra.pending_2pc);
        } catch (error) {
            if (req.isStale()) return;
            console.error('Error fetching sessions:', error);
            sessionTbody.innerHTML = `<tr><td colspan="7" style="color:#d03b3b; text-align:center; padding: 30px;">데이터를 불러오는 데 실패했습니다: ${error.message}</td></tr>`;
        } finally {
            // 예전엔 성공할 때만 스피너를 내려 실패 시 계속 돌았다. 가장 최근 요청이 끝날 때 내린다.
            if (icon && req.isLatest()) icon.classList.remove('spinning');
        }
    }

    if (sessionRefreshBtn) {
        sessionRefreshBtn.addEventListener('click', fetchSessions);
    }

    // 사용자 실측(2026-09-14): getSessionExtra()의 v$lock 스캔이 이 환경 일부 인스턴스에서 최대
    // 49초까지 걸릴 수 있음(서버 쪽에 쿼리 타임아웃을 추가했지만, 그와 별개로 이전엔 setInterval이
    // 이전 fetchSessions() 완료 여부와 무관하게 무조건 매 주기마다 새 요청을 쐈다 - 한 사이클이
    // 오래 걸리면 다음 주기 요청들이 겹쳐 쌓이면서 오라클 커넥션 풀을 잠식했다. setInterval 대신
    // "이전 호출이 끝난 뒤에만 다음 걸 예약"하는 재귀 setTimeout으로 바꿔 겹침 자체를 차단한다.
    // 코드 리뷰 지적(2026-09-14): isSessionAutoRefreshing 하나만으로는 Start→Stop→Start를 첫 fetch가
    // 끝나기 전에 빠르게 누르는 경우를 못 막는다 - Stop 후 다시 Start하면 플래그가 다시 true가 되므로,
    // 그 사이 아직 살아있던 이전 Start의 fetchSessions() 체인도 resolve될 때 조건을 통과해 자기 체인을
    // 또 예약해버려 폴링 체인이 두 개로 늘어난다(정확히 이 fix가 막으려던 겹침이 재발). Start를 누를
    // 때마다 세대(generation)를 하나씩 올리고, 그 세대를 클로저로 들고 있다가 resolve 시점에 "지금도
    // 그 세대가 최신인지"까지 같이 확인해야 낡은 체인이 스스로 죽는다.
    let sessionRefreshGeneration = 0;
    function scheduleNextSessionFetch(intervalMs, generation) {
        sessionTimer = setTimeout(async () => {
            await fetchSessions();
            if (isSessionAutoRefreshing && generation === sessionRefreshGeneration) {
                scheduleNextSessionFetch(intervalMs, generation);
            }
        }, intervalMs);
    }

    if (sessionToggleBtn) {
        sessionToggleBtn.addEventListener('click', () => {
            if (isSessionAutoRefreshing) {
                clearTimeout(sessionTimer);
                sessionRefreshGeneration++; // 아직 살아있는 이전 체인이 있다면 여기서 무효화.
                isSessionAutoRefreshing = false;
                sessionToggleBtn.textContent = '자동 갱신 시작';
                sessionToggleBtn.classList.remove('danger-btn');
                sessionToggleBtn.classList.add('primary-btn');
                sessionRefreshBtn.disabled = false;
            } else {
                const interval = parseInt(sessionIntervalInput.value) || 3; // 기본 3초(체크리스트 1-9)
                isSessionAutoRefreshing = true;
                const generation = ++sessionRefreshGeneration;
                fetchSessions().then(() => {
                    if (isSessionAutoRefreshing && generation === sessionRefreshGeneration) {
                        scheduleNextSessionFetch(interval * 1000, generation);
                    }
                });
                sessionToggleBtn.textContent = '자동 갱신 중지';
                sessionToggleBtn.classList.remove('primary-btn');
                sessionToggleBtn.classList.add('danger-btn');
                sessionRefreshBtn.disabled = true;
            }
        });
    }

    // 세션 목록 스크롤 높이 맞춤(체크리스트 1-4, 2026-09-25) - 행이 SESSLIST_VISIBLE_ROWS개를 넘으면 목록
    // 영역을 "머리글 + 첫 7행"의 실제 높이로 제한해 그 아래는 세로 스크롤로 본다. 행 높이는 내용(대기
    // 막대 등)과 화면 배율에 따라 달라 고정 px 대신 매번 잰다. 숨겨진 탭은 높이를 잴 수 없으므로 CSS의
    // 대략값(.sesslist-scroll max-height)을 그대로 두고, 탭을 열 때 다시 잰다.
    const SESSLIST_VISIBLE_ROWS = 7;
    function fitSessListScroll(tbody) {
        if (!tbody) return;
        const box = tbody.closest('.sesslist-scroll');
        if (!box) return;
        const rows = tbody.rows;
        if (rows.length <= SESSLIST_VISIBLE_ROWS) { box.style.maxHeight = 'none'; return; }
        if (!box.offsetParent) { box.style.maxHeight = ''; return; }
        const thead = box.querySelector('thead');
        let height = thead ? thead.offsetHeight : 0;
        for (let i = 0; i < SESSLIST_VISIBLE_ROWS; i++) height += rows[i].offsetHeight;
        box.style.maxHeight = (height + 2) + 'px'; // +2: 테두리
    }

    // Column set mirrors the Active Session tab's rendering (same fields, same duration/wait bar
    // treatment) - Active Transaction shows the same columns, just scoped to sessions holding a
    // transaction rather than status='ACTIVE'.
    function renderActiveTransactionsTab(rows) {
        const tbody = document.getElementById('sesslist-active-tx-tbody');
        if (!tbody) return;
        if (!rows || rows.length === 0) {
            tbody.innerHTML = '<tr><td colspan="15" style="text-align:center; padding: 30px;">활성 트랜잭션이 없습니다.</td></tr>';
            fitSessListScroll(tbody);
            return;
        }
        const maxDuration = rows.reduce((max, r) => Math.max(max, Number(r.duration_time) || 0), 1);
        tbody.innerHTML = rows.map(r => {
            const durationVal = r.duration_time !== null && r.duration_time !== undefined ? Number(r.duration_time) : 0;
            const durationPct = Math.min((durationVal / maxDuration) * 100, 100);
            const durationHtml = r.duration_time !== null && r.duration_time !== undefined ? `<div style="display: flex; align-items: center; gap: 8px;"><div style="flex-grow: 1; background-color: var(--track-bg); height: 8px; border-radius: 4px; overflow: hidden; width: 60px;"><div style="width: ${durationPct}%; height: 100%; background-color: #3987e5; border-radius: 4px;"></div></div><span style="min-width: 30px; text-align: right;">${durationVal}</span></div>` : '-';
            let waitHtml = `<div style="color: var(--text-secondary);">-</div>`;
            if (r.session_wait_pct && r.session_wait_pct.includes(',')) {
                const [cpu, uio, sio, latch, txlock, tmlock, other] = r.session_wait_pct.split(',').map(Number);
                if (cpu + uio + sio + latch + txlock + tmlock + other > 0) {
                    waitHtml = `<div style="display: flex; width: 100px; height: 12px; border-radius: 6px; overflow: hidden; background-color: var(--track-bg);" title="CPU: ${cpu}%, User I/O: ${uio}%, Sys I/O: ${sio}%, Latch: ${latch}%, TX Lock: ${txlock}%, TM Lock: ${tmlock}%, Other: ${other}%"><div style="width: ${cpu}%; background-color: #22d3ee;" title="CPU: ${cpu}%"></div><div style="width: ${uio}%; background-color: #2ecc71;" title="User I/O: ${uio}%"></div><div style="width: ${sio}%; background-color: #e67e22;" title="Sys I/O: ${sio}%"></div><div style="width: ${latch}%; background-color: #808000;" title="Latch: ${latch}%"></div><div style="width: ${txlock}%; background-color: #7c3aed;" title="TX Lock: ${txlock}%"></div><div style="width: ${tmlock}%; background-color: #be123c;" title="TM Lock: ${tmlock}%"></div><div style="width: ${other}%; background-color: var(--text-muted);" title="Other: ${other}%"></div></div>`;
                }
            }
            // Unlike Active Session (queried with status='ACTIVE' only), Active Transaction has no
            // status filter - a session can legitimately show INACTIVE here (idle-in-transaction, a
            // lock-holder signal DBAs watch for), so the badge must reflect the real status instead of
            // always painting the healthy "online" green.
            const statusClass = (r.status || '').toUpperCase() === 'ACTIVE' ? 'online' : 'offline';
            return `
            <tr class="clickable-session-row" style="cursor:pointer;" data-sid="${r.sid}" data-serial="${r.serial || ''}" data-sql_id="${r.sql_id || ''}">
                <td>${r.db_name || '-'}</td>
                <td><span class="status-badge ${statusClass}">${r.status || '-'}</span></td>
                <td>${r.sid}</td>
                <td>${r.serial}</td>
                <td>${r.server_pid || '-'}</td>
                <td>${durationHtml}</td>
                <td>${waitHtml}</td>
                <td>${r.sql_id || '-'}</td>
                <td>${r.event_name || '-'}</td>
                <td>${r.plan_hash_value || '-'}</td>
                <td><div style="max-width:200px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;" title="${r.sql_text || ''}">${r.sql_text || '-'}</div></td>
                <td>${r.machine_name || '-'}</td>
                <td>${r.osuser || '-'}</td>
                <td>${r.username || '-'}</td>
                <td>${r.program_name || '-'}</td>
            </tr>
        `;
        }).join('');
        fitSessListScroll(tbody);
    }

    function renderParallelSessionsTab(rows) {
        const tbody = document.getElementById('sesslist-parallel-tbody');
        if (!tbody) return;
        if (!rows || rows.length === 0) {
            tbody.innerHTML = '<tr><td colspan="11" style="text-align:center; padding: 30px;">병렬 세션이 없습니다.</td></tr>';
            fitSessListScroll(tbody);
            return;
        }
        tbody.innerHTML = rows.map(r => `
            <tr class="clickable-session-row" style="cursor:pointer;" data-sid="${r.sid}" data-serial="${r.serial || ''}" data-sql_id="">
                <td>${r.qcsid != null ? r.qcsid : '-'}</td>
                <td>${r.qcserial != null ? r.qcserial : '-'}</td>
                <td>${r.sid}</td>
                <td>${r.serial}</td>
                <td>${r.server_number != null ? r.server_number : '-'}</td>
                <td>${r.degree != null ? r.degree : '-'}</td>
                <td>${r.req_degree != null ? r.req_degree : '-'}</td>
                <td>${r.username || '-'}</td>
                <td>${r.status || '-'}</td>
                <td>${r.program || '-'}</td>
                <td>${r.machine || '-'}</td>
            </tr>
        `).join('');
        fitSessListScroll(tbody);
    }

    function renderPending2pcTab(rows) {
        const tbody = document.getElementById('sesslist-2pc-tbody');
        if (!tbody) return;
        if (!rows || rows.length === 0) {
            tbody.innerHTML = '<tr><td colspan="9" style="text-align:center; padding: 30px;">보류 중인 2PC 트랜잭션이 없습니다.</td></tr>';
            fitSessListScroll(tbody);
            return;
        }
        tbody.innerHTML = rows.map(r => `
            <tr>
                <td>${r.local_tran_id || '-'}</td>
                <td>${r.global_tran_id || '-'}</td>
                <td>${r.state || '-'}</td>
                <td>${r.mixed || '-'}</td>
                <td>${r.tran_comment || '-'}</td>
                <td>${r.host || '-'}</td>
                <td>${r.fail_time || '-'}</td>
                <td>${r.retry_time || '-'}</td>
                <td>${r.os_user || '-'}</td>
            </tr>
        `).join('');
        fitSessListScroll(tbody);
    }

    // Active Session / Active Transaction / Parallel Session / 2pc Pending Transaction tabs
    const sessListTabBtns = document.querySelectorAll('.sesslist-tab-btn');
    sessListTabBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            sessListTabBtns.forEach(b => {
                b.classList.remove('active');
                b.style.borderBottom = 'none';
                b.style.color = 'var(--text-muted)';
                b.style.fontWeight = '500';
            });
            btn.classList.add('active');
            btn.style.borderBottom = '2px solid #3987e5';
            btn.style.color = '#3987e5';
            btn.style.fontWeight = '600';

            const targetId = btn.getAttribute('data-sesslist-tab');
            document.querySelectorAll('.sesslist-tab-content').forEach(content => {
                content.style.display = 'none';
            });
            document.getElementById(targetId).style.display = 'block';
            fitSessListScroll(document.querySelector(`#${targetId} tbody`)); // 숨겨져 있어 못 쟀던 높이를 지금 잰다
        });
    });

    // Called on every DB switch (see the instance-click handler above) - resets the session table/tabs
    // to a loading state before re-fetching for the new DB. Active Session Wait Class 차트/Top SQL
    // Activity Timeline(둘 다 서버 ASH 기반)은 여기서 다루지 않고 fetchAshPanels()가 db_id 변경 시
    // 독립적으로 다시 그린다 - Trace 산점도는 2026-09-22 4단계에서 제거됨(구현단계 체크리스트 참고).
    function resetSessionMonitor(nextDbId) {
        ashSelectedWindow = null; // DB를 바꾸면 이전 드래그 선택은 더 이상 유효하지 않음(§9.1)
        if (typeof closeAshOtherDrilldown === 'function') closeAshOtherDrilldown();
        if (sessionTbody) sessionTbody.innerHTML = '<tr><td colspan="16" style="text-align:center; padding: 30px;">접속 중...</td></tr>';
        const activeTxTbody = document.getElementById('sesslist-active-tx-tbody');
        if (activeTxTbody) activeTxTbody.innerHTML = '<tr><td colspan="15" style="text-align:center; padding: 30px;">접속 중...</td></tr>';
        const parallelTbody = document.getElementById('sesslist-parallel-tbody');
        if (parallelTbody) parallelTbody.innerHTML = '<tr><td colspan="11" style="text-align:center; padding: 30px;">접속 중...</td></tr>';
        const pending2pcTbody = document.getElementById('sesslist-2pc-tbody');
        if (pending2pcTbody) pending2pcTbody.innerHTML = '<tr><td colspan="9" style="text-align:center; padding: 30px;">접속 중...</td></tr>';

        fetchSessions();
        // 이전 DB 그래프·KPI를 즉시 비우고 "불러오는 중" 표시 후 새 DB로 재조회(체크리스트 1-5).
        clearAshPanelsForDbSwitch();
        restartAshActivityPolling();
    }

    // ---- Active Session Wait Class 차트 (설계문서 `Current Session 매뉴 active_session 차트 개편.md`
    // §0/§2/§3, 1단계 구현 - 2026-09-22, 원본 DBAgent-Java에서 포팅) ----
    // 팔레트는 §0 결정 3에 따라 신규 색이 아니라 기존 세션별 대기 분해 미니바(waitHtml 조립부,
    // app.js:2144/2256 부근)와 동일한 색을 그대로 재사용한다. Other만 고정 hex가 아니라 테마 토큰
    // (--text-muted)을 그대로 따라간다 - 다만 범례는 최초 1회만 그려지므로(buildChartLegend의
    // container.dataset.built 가드) 차트를 만든 시점의 값으로 고정되고, 이후 테마를 바꿔도 스와치
    // 색까지 실시간으로 따라가진 않는다(2단계에서 표현 다듬을 때 같이 보완 예정).
    const ASH_ACTIVITY_CATEGORIES = [
        { key: 'cpu', label: 'CPU', color: '#22d3ee' },
        { key: 'latch', label: 'Latch', color: '#808000' },
        { key: 'user_io', label: 'User I/O', color: '#2ecc71' },
        { key: 'tx_lock', label: 'TX Lock', color: '#7c3aed' },
        { key: 'sys_io', label: 'Sys I/O', color: '#e67e22' },
        { key: 'tm_lock', label: 'TM Lock', color: '#be123c' },
        { key: 'other', label: 'Other', color: getComputedStyle(document.documentElement).getPropertyValue('--text-muted').trim() || '#94a3b8' }
    ];
    const ASH_ACTIVITY_POLL_MS = 30000; // 세션 테이블 폴링 주기(사용자 조정 가능)와 무관하게 독립 폴링
    let ashActivityRangeMinutes = 60;
    let ashActivityTimer = null;
    let ashActivityGeneration = 0;
    // 2단계(표현·접근성, 2026-09-22) 상태 - 전부 재조회 없이 lastAshActivityData로 즉시 다시 그린다.
    let ashActivityForm = 'area'; // 'area' | 'bar' (§1 뷰 전환)
    let ashActivityTexture = false; // §2 "고대비 텍스처" 토글
    let ashActivityShowTable = false; // §2 "표로 보기" 토글
    let ashActivityChartRenderedForm = null; // 마지막으로 실제 렌더된 form - 바뀌면 destroy 후 재생성
    let lastAshActivityData = null;

    // 6단계(6시간/24시간, 자체 수집 경로 - 설계문서 §0 결정, 2026-09-22) - 실시간 ASH 조회
    // (/api/ash_activity, 30분/1시간만 지원)와 자체 수집 조회(/api/metric_history, 이미 60초 샘플러가
    // instance_metric_history에 쌓고 있는 ash_* metric_name)를 range에 따라 나눈다. 실시간 조회를
    // 그대로 6시간/24시간까지 늘리지 않는 이유는 채팅에서 오간 성능 비교 그대로 - 무거운 AWR GROUP BY
    // 스캔이 보는 사람 수만큼 매번 원본 Oracle에 나가는 걸 피하기 위함(이미 InstanceMetricSampler
    // Service가 v2 대시보드 폴링 부하 사고를 겪고 캐시 샘플링으로 옮긴 것과 같은 이유).
    const ASH_LONG_RANGE_METRIC_NAMES = ['ash_cpu', 'ash_latch', 'ash_user_io', 'ash_tx_lock', 'ash_system_io', 'ash_tm_lock', 'ash_other', 'ash_cpu_cores'];
    const ASH_LONG_RANGE_CATEGORY_KEYS = ['ash_cpu', 'ash_latch', 'ash_user_io', 'ash_tx_lock', 'ash_system_io', 'ash_tm_lock', 'ash_other'];

    function isAshLongRange() {
        return ashActivityRangeMinutes > 60;
    }

    async function fetchAshActivityFromHistory(myDbId) {
        const rangeKey = ashActivityRangeMinutes >= 1440 ? '24h' : '6h';
        const res = await fetch(`/api/metric_history?db_id=${myDbId}&range=${rangeKey}&metrics=${ASH_LONG_RANGE_METRIC_NAMES.join(',')}&token=${encodeURIComponent(getToken())}`);
        const raw = await res.json();
        if (!res.ok || raw.error) throw new Error(raw.error || 'metric_history 조회 실패');

        // 8개 시계열(ash_* 7개 + ash_cpu_cores)을 sampledAt 기준으로 합쳐 ash_activity 응답과 같은
        // {cpu_cores, categories, series} 모양으로 변환 - renderAshActivityChart()가 출처를 몰라도
        // 되게 한다. 전부 InstanceMetricSamplerService의 같은 recordIfPresent() 호출에서 같은
        // sampledAt으로 쓰이므로 실제로는 8개 배열의 타임스탬프가 항상 일치하지만, 혹시 몰라
        // sampledAt을 키로 한 Map으로 맞춰(포지션 의존 없이) 방어적으로 합친다.
        const seriesMaps = {};
        ASH_LONG_RANGE_METRIC_NAMES.forEach(name => {
            seriesMaps[name] = new Map((raw[name] || []).map(pt => [pt.sampledAt, pt.value]));
        });
        const timestamps = Array.from(new Set(
            ASH_LONG_RANGE_METRIC_NAMES.flatMap(name => Array.from(seriesMaps[name].keys()))
        )).sort((a, b) => a - b);

        let lastCpuCores = 0;
        const series = timestamps.map(ts => {
            const values = ASH_LONG_RANGE_CATEGORY_KEYS.map(key => seriesMaps[key].get(ts) || 0);
            if (seriesMaps['ash_cpu_cores'].has(ts)) lastCpuCores = seriesMaps['ash_cpu_cores'].get(ts);
            return { time: new Date(ts).toISOString(), values };
        });

        return {
            range_minutes: ashActivityRangeMinutes,
            step_minutes: null, // 자체 수집은 고정 60초 원본 샘플이라 0-패딩 버킷 개념이 없음
            cpu_cores: lastCpuCores,
            categories: ['CPU', 'Latch', 'User I/O', 'TX Lock', 'Sys I/O', 'TM Lock', 'Other'],
            series
        };
    }

    // 두 차트의 요청 세대 - DB 전환·구간 변경 뒤 늦게 온 이전 응답을 버린다(체크리스트 1-5). 예전엔
    // currentDbId만 비교해 A→B→A처럼 같은 DB로 돌아온 경우의 옛 응답은 막지 못했다.
    const ashActivityRequestGuard = dbagentLatestRequest();
    const ashTopSqlRequestGuard = dbagentLatestRequest();

    // 차트 위에 겹치는 안내 문구("불러오는 중…"/오류). DB를 바꾼 직후 새 DB 응답이 올 때까지 이전 DB
    // 그래프가 새 DB 것처럼 보이던 문제(체크리스트 1-5, 2026-09-25 CDP 재현: 제목은 DB #2인데 그래프·
    // KPI는 DB #1 값)를 막기 위해, 전환 즉시 차트를 비우고 이 문구를 띄운다.
    function setAshPanelMessage(container, text, isError) {
        if (!container) return;
        let el = container.querySelector(':scope > .ash-panel-message');
        if (!text) { if (el) el.remove(); return; }
        if (!el) {
            el = document.createElement('div');
            el.className = 'ash-panel-message';
            el.style.cssText = 'position:absolute; inset:0; display:flex; align-items:center; justify-content:center; pointer-events:none; font-size:0.85rem; z-index:5;';
            container.appendChild(el);
        }
        el.style.color = isError ? 'var(--danger)' : 'var(--text-muted)';
        el.textContent = text;
    }
    function ashActivityContainer() {
        const c = document.getElementById('ash-activity-chart');
        return c ? c.parentElement : null;
    }
    function ashTopSqlContainer() {
        return document.getElementById('ash-topsql-container');
    }

    function resetAshKpis() {
        ['ash-kpi-current', 'ash-kpi-cores', 'ash-kpi-avg', 'ash-kpi-exceed'].forEach(id => ashSetText(id, '–'));
        const deltaEl = document.getElementById('ash-kpi-delta');
        if (deltaEl) deltaEl.textContent = '';
    }

    // 첫 로딩 스켈레톤(체크리스트 1-2, 2026-09-25) - 예전엔 첫 ASH 응답이 와야 차트를 처음 만들어서, 폐쇄망처럼
    // 응답이 느리면 그동안 그래프 자리가 축·격자도 없는 빈(검은) 영역이었다. 데이터가 없어도 빈 데이터로
    // 차트를 먼저 만들어 축·격자·제목을 즉시 보여주고, 그 위에 "불러오는 중…"을 띄운다.
    function ensureAshSkeletons() {
        if (!ashActivityChart && !ashActivityShowTable) {
            renderAshActivityChart({ series: [], cpu_cores: 0 });
            lastAshActivityData = null; // 빈 스켈레톤은 "그린 데이터"가 아니다(오류 표시 판단용)
            resetAshKpis();
        }
        if (!ashTopSqlChart && !isAshLongRange()) {
            renderAshTopSqlChart({ sql_categories: [], series: [] });
            lastAshTopSqlData = null;
            const topLegend = document.getElementById('ash-topsql-legend');
            if (topLegend) topLegend.innerHTML = '';
        }
    }

    // 조회 시간 진단 로그(체크리스트 1-2): 2초 이상 걸린 응답만 콘솔에 "전체 / 서버 처리(query_ms) /
    // 네트워크·대기"로 나눠 남긴다 - 폐쇄망 현장에서 F12 콘솔만 보면 원인을 구분할 수 있게.
    function logSlowAshResponse(what, dbId, startedAt, data) {
        const total = Math.round(performance.now() - startedAt);
        if (total < 2000) return;
        const serverMs = data && typeof data.query_ms === 'number' ? data.query_ms : null;
        console.info(`[DBAgent] ${what} db=${dbId} 전체 ${total}ms`
            + (serverMs !== null ? ` / 서버 처리(커넥션+DB 조회) ${serverMs}ms / 네트워크·대기 ${total - serverMs}ms` : ''));
    }

    // DB 전환 시(resetSessionMonitor) 호출 - 이전 DB의 그래프·KPI·범례를 즉시 지우고 "불러오는 중"
    // 표시. 차트 인스턴스는 유지하고 데이터만 비운다(크기·범례 on/off 상태 유지).
    function clearAshPanelsForDbSwitch() {
        ashActivityRequestGuard.invalidate();
        ashTopSqlRequestGuard.invalidate();
        lastAshActivityData = null;
        lastAshTopSqlData = null;
        if (ashActivityChart) { ashActivityChart.data.datasets = []; ashActivityChart.update(); }
        if (ashTopSqlChart) { ashTopSqlChart.data.datasets = []; ashTopSqlChart.update(); }
        resetAshKpis();
        const tableEl = document.getElementById('ash-activity-table');
        if (tableEl) tableEl.innerHTML = '';
        const topLegend = document.getElementById('ash-topsql-legend');
        if (topLegend) topLegend.innerHTML = '';
        ensureAshSkeletons();
        setAshPanelMessage(ashActivityContainer(), '불러오는 중…');
        setAshPanelMessage(ashTopSqlContainer(), isAshLongRange() ? '' : '불러오는 중…');
    }

    async function fetchAshActivity() {
        const canvas = document.getElementById('ash-activity-chart');
        if (!canvas || !window.currentDbId) return;
        const req = ashActivityRequestGuard.begin();
        const myDbId = req.dbId;
        try {
            const data = isAshLongRange()
                ? await fetchAshActivityFromHistory(myDbId)
                : await (async () => {
                    const startedAt = performance.now();
                    const res = await fetch(`/api/ash_activity?db_id=${myDbId}&range_minutes=${ashActivityRangeMinutes}&step_minutes=1&token=${encodeURIComponent(getToken())}`);
                    const d = await res.json();
                    logSlowAshResponse('ash_activity', myDbId, startedAt, d);
                    if (!res.ok || d.error) throw new Error(d.error || 'ash_activity 조회 실패');
                    return d;
                })();
            // DB를 빠르게 전환하면 늦게 도착한 이전 DB 응답이 새로 선택된 DB 화면을 덮어쓸 수 있어 방어.
            if (req.isStale()) return;
            setAshPanelMessage(ashActivityContainer(), '');
            renderAshActivityChart(data);
        } catch (err) {
            if (req.isStale()) return;
            console.error('Failed to fetch ash_activity:', err);
            // 이전에 그린 데이터가 없을 때만 오류를 차트 위에 표시(있으면 마지막 정상 그래프를 유지).
            if (!lastAshActivityData) setAshPanelMessage(ashActivityContainer(), '조회 실패: ' + err.message, true);
        }
    }

    function ashSetText(id, text) {
        const el = document.getElementById(id);
        if (el) el.textContent = text;
    }

    // KPI 행(설계문서 §5) - 현재 AAS(+직전 대비 delta) / CPU 코어 수 / 선택 구간 평균 / 기준선 초과 비율.
    // 전부 이미 받아온 series에서 클라이언트가 계산 - 별도 API 불필요.
    function updateAshActivityKpis(data) {
        const series = data.series || [];
        const cpuCores = data.cpu_cores || 0;
        const sums = series.map(pt => pt.values.reduce((a, b) => a + b, 0));
        const current = sums.length ? sums[sums.length - 1] : 0;
        const prev = sums.length > 1 ? sums[sums.length - 2] : null;
        const avg = sums.length ? sums.reduce((a, b) => a + b, 0) / sums.length : 0;
        const exceedCount = cpuCores > 0 ? sums.filter(s => s > cpuCores).length : 0;

        ashSetText('ash-kpi-current', current.toFixed(2));
        ashSetText('ash-kpi-cores', String(cpuCores));
        ashSetText('ash-kpi-avg', avg.toFixed(2));
        ashSetText('ash-kpi-exceed', cpuCores > 0 ? Math.round(exceedCount / sums.length * 100) + '%' : '–');

        const deltaEl = document.getElementById('ash-kpi-delta');
        if (deltaEl) {
            if (prev === null) {
                deltaEl.textContent = '';
            } else {
                const diff = current - prev;
                const arrow = diff > 0.01 ? '▲' : (diff < -0.01 ? '▼' : '–');
                // 대기 세션이 늘어나는 쪽(▲)이 주의가 필요한 신호라 danger, 줄어드는 쪽(▼)이 success -
                // 세션 리스트 등 이 앱의 다른 화면에서 "많을수록 나쁨" 신호에 쓰는 색과 같은 관례.
                deltaEl.style.color = diff > 0.01 ? 'var(--danger)' : (diff < -0.01 ? 'var(--success)' : 'var(--text-muted)');
                deltaEl.textContent = `${arrow} ${Math.abs(diff).toFixed(2)} (직전 대비)`;
            }
        }
    }

    // "고대비 텍스처" 토글(설계문서 §2) - CVD/저시력 대응 45°/135° 사선 해치를 CanvasPattern으로 생성.
    // 목업(active_session_mockup_1.html)의 SVG <pattern> 방식(6x6 타일, 카테고리 인덱스 짝/홀에 따라
    // 45°/135° 교차)과 같은 개념을 Chart.js/canvas용으로 옮겼다 - 타일 경계에서 대각선이 끊기지 않도록
    // 모서리에 절반 길이 선을 추가로 그리는 표준 해치 타일링 기법을 쓴다.
    const ashPatternCache = new Map();
    function ashStripePattern(hexColor, angleDeg) {
        const cacheKey = hexColor + ':' + angleDeg;
        if (ashPatternCache.has(cacheKey)) return ashPatternCache.get(cacheKey);
        const size = 8;
        const tile = document.createElement('canvas');
        tile.width = size;
        tile.height = size;
        const tctx = tile.getContext('2d');
        tctx.fillStyle = hexColor + '2e'; // 옅은 바탕(~18% 불투명) - 텍스처 위에 카테고리색 기미만 남김
        tctx.fillRect(0, 0, size, size);
        tctx.strokeStyle = hexColor;
        tctx.lineWidth = 1.6;
        tctx.beginPath();
        if (angleDeg === 45) {
            tctx.moveTo(0, size); tctx.lineTo(size, 0);
            tctx.moveTo(-size / 2, size / 2); tctx.lineTo(size / 2, -size / 2);
            tctx.moveTo(size / 2, size * 1.5); tctx.lineTo(size * 1.5, size / 2);
        } else {
            tctx.moveTo(0, 0); tctx.lineTo(size, size);
            tctx.moveTo(-size / 2, size / 2); tctx.lineTo(size / 2, size * 1.5);
            tctx.moveTo(size / 2, -size / 2); tctx.lineTo(size * 1.5, size / 2);
        }
        tctx.stroke();
        const pattern = tile.getContext('2d').createPattern(tile, 'repeat');
        ashPatternCache.set(cacheKey, pattern);
        return pattern;
    }

    function buildAshDatasets(data, form, texture) {
        const isBar = form === 'bar';
        const categoryDatasets = ASH_ACTIVITY_CATEGORIES.map((cat, i) => {
            const fillColor = texture
                ? ashStripePattern(cat.color, i % 2 === 0 ? 45 : 135) // §2: 인접 카테고리와 각도 교차
                : (cat.color + 'd1'); // ~82% 불투명(설계문서 §2 마크 스펙 fill-opacity 0.82)
            const base = {
                label: cat.label,
                data: data.series.map(pt => ({ x: new Date(pt.time).getTime(), y: pt.values[i] })),
                borderColor: cat.color,
                backgroundColor: fillColor,
                borderWidth: 1,
                stack: 'ash'
            };
            return isBar ? base : Object.assign(base, { pointRadius: 0, fill: true, tension: 0.15 });
        });
        // cpu_count 기준선(§3.3) - 스택 대상이 아니라 별도 계열로 얹는다(합산에서 제외되도록 stack 미지정).
        // 막대형에서도 항상 선으로 그리도록 type을 명시(Chart.js 혼합 차트 - 데이터셋별 type override).
        const cpuCores = data.cpu_cores || 0;
        const cpuLine = {
            type: 'line',
            label: `CPU 코어 수 (${cpuCores})`,
            data: data.series.map(pt => ({ x: new Date(pt.time).getTime(), y: cpuCores })),
            borderColor: chartLineColor(0.6),
            borderDash: [5, 4],
            borderWidth: 1.5,
            pointRadius: 0,
            fill: false,
            order: -1
        };
        return categoryDatasets.concat([cpuLine]);
    }

    // "표로 보기" 토글(설계문서 §2/§5) - 차트와 같은 series를 텍스트 표로 노출(접근성 대체 경로).
    function renderAshActivityTable(data) {
        const tableEl = document.getElementById('ash-activity-table');
        if (!tableEl) return;
        const cats = data.categories || [];
        let html = '<table class="data-table" style="width:100%;"><thead><tr>'
            + '<th style="text-align:left;">시간</th>'
            + cats.map(c => `<th style="text-align:right;">${c}</th>`).join('')
            + '<th style="text-align:right;">합계</th></tr></thead><tbody>';
        (data.series || []).forEach(pt => {
            const total = pt.values.reduce((a, b) => a + b, 0);
            const timeLabel = new Date(pt.time).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
            html += '<tr><td style="text-align:left;">' + timeLabel + '</td>'
                + pt.values.map(v => `<td style="text-align:right;">${v.toFixed(2)}</td>`).join('')
                + `<td style="text-align:right; font-weight:600;">${total.toFixed(2)}</td></tr>`;
        });
        html += '</tbody></table>';
        tableEl.innerHTML = html;
    }

    function renderAshActivityChart(data) {
        lastAshActivityData = data;
        updateAshActivityKpis(data);

        const canvas = document.getElementById('ash-activity-chart');
        const tableEl = document.getElementById('ash-activity-table');

        if (ashActivityShowTable) {
            if (canvas) canvas.style.display = 'none';
            if (tableEl) tableEl.style.display = 'block';
            renderAshActivityTable(data);
            return; // 차트는 표 모드에서 굳이 갱신하지 않음 - 다음에 차트로 돌아갈 때 최신 데이터로 다시 그림
        }
        if (tableEl) tableEl.style.display = 'none';
        if (!canvas) return;
        // 표 모드 동안 canvas가 display:none이었다면 Chart.js가 크기를 0으로 캐싱했을 수 있음(기존
        // 대시보드 탭 전환에서도 같은 이유로 resize() 강제 - 위 "Returning to dashboard" 주석 참고).
        canvas.style.display = 'block';
        if (ashActivityChart) ashActivityChart.resize();

        const datasets = buildAshDatasets(data, ashActivityForm, ashActivityTexture);

        // 영역형↔막대형 전환은 Chart.js에서 기존 인스턴스의 type을 안전하게 바꿀 수 없어 destroy 후
        // 재생성한다(그 외 갱신은 기존 인스턴스에 데이터만 갈아끼움).
        if (ashActivityChart && ashActivityChartRenderedForm !== ashActivityForm) {
            ashActivityChart.destroy();
            ashActivityChart = null;
        }

        if (!ashActivityChart) {
            ashActivityChart = new Chart(canvas, {
                type: ashActivityForm === 'bar' ? 'bar' : 'line',
                data: { datasets },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    animation: { duration: 0 },
                    interaction: { mode: 'index', intersect: false },
                    scales: {
                        x: {
                            type: 'time',
                            time: { unit: 'minute', tooltipFormat: 'HH:mm', displayFormats: { minute: 'HH:mm' } },
                            ticks: { color: chartLineColor(0.8), maxRotation: 0, minRotation: 0, font: { size: 13 } },
                            grid: { drawOnChartArea: true, color: chartLineColor(0.15), borderDash: [4, 4] },
                            border: { display: true, color: chartLineColor(1), width: 2 }
                        },
                        y: {
                            stacked: true,
                            beginAtZero: true,
                            min: 0,
                            ticks: { precision: 1, color: chartLineColor(0.8), font: { size: 13 } },
                            grid: { drawOnChartArea: true, color: chartLineColor(0.15), borderDash: [4, 4] },
                            border: { display: true, color: chartLineColor(1), width: 2 }
                        }
                    },
                    plugins: {
                        // 커스텀 범례(#ash-activity-legend)로 대체 - 기본 범례는 끔.
                        legend: { display: false },
                        title: { display: true, text: 'Active Session Wait Class' },
                        subtitle: {
                            display: true,
                            text: 'AAS(Average Active Sessions)',
                            align: 'start',
                            color: chartLineColor(0.8),
                            padding: { bottom: 10 }
                        }
                    }
                }
            });
            ashActivityChartRenderedForm = ashActivityForm;
            buildChartLegend('ash-activity-legend', ASH_ACTIVITY_CATEGORIES, () => ashActivityChart);
            applyLegendVisibility('ash-activity-legend', ashActivityChart);
        } else {
            ashActivityChart.data.datasets = datasets;
            ashActivityChart.update();
        }
    }

    // ---- Top SQL Activity Timeline (설계문서 §8, 3단계 구현 - 2026-09-22, 원본 DBAgent-Java에서 포팅) ----
    // §8.1: SQL_ID별 별도 색을 쓰지 않고, 각 Top SQL을 그 SQL의 지배적 대기 카테고리에 매핑해 §2/1단계와
    // 동일한 hex를 재사용한다(Sys I/O는 제외 - 서버가 이미 그렇게 판정해서 내려줌). 구성 SQL이 폴링마다
    // 바뀔 수 있어 buildChartLegend의 "최초 1회만 그림" 캐시를 못 쓰고 매번 다시 그리는 전용 렌더러를 쓴다.
    const ASH_TOPSQL_CATEGORY_COLOR = {};
    ASH_ACTIVITY_CATEGORIES.forEach(cat => { ASH_TOPSQL_CATEGORY_COLOR[cat.label] = cat.color; });
    const ASH_TOPSQL_OTHER_COLOR = ASH_ACTIVITY_CATEGORIES.find(c => c.key === 'other').color;
    // 5단계(Other 드릴다운, 설계문서 §9, 2026-09-22) 상태.
    let lastAshTopSqlData = null;
    let ashOtherDrilldownOpen = false;
    let ashSelectedWindow = null; // {start: Date, end: Date} | null - 드래그 선택 중이면 그 구간, 없으면 null(전체 표시 구간)

    async function fetchAshTopSql() {
        const canvas = document.getElementById('ash-topsql-chart');
        if (!canvas || !window.currentDbId) return;
        // Top SQL Activity Timeline/Other 드릴다운은 6단계(6시간/24시간) 대상이 아님 - SQL_ID 단위
        // 자체 수집은 카디널리티가 무한정이라 이번 6단계 범위 밖(체크리스트 참고). 실시간(30분/1시간)
        // 전용으로 남기고, 장기 구간에서는 안내만 보여준다.
        if (isAshLongRange()) {
            renderAshTopSqlLongRangeNotice();
            return;
        }
        const req = ashTopSqlRequestGuard.begin();
        const myDbId = req.dbId;
        try {
            const startedAt = performance.now();
            const res = await fetch(`/api/ash_top_sql?db_id=${myDbId}&range_minutes=${ashActivityRangeMinutes}&step_minutes=1&token=${encodeURIComponent(getToken())}`);
            const data = await res.json();
            logSlowAshResponse('ash_top_sql', myDbId, startedAt, data);
            if (!res.ok || data.error) throw new Error(data.error || 'ash_top_sql 조회 실패');
            if (req.isStale()) return;
            setAshPanelMessage(ashTopSqlContainer(), '');
            renderAshTopSqlChart(data);
        } catch (err) {
            if (req.isStale()) return;
            console.error('Failed to fetch ash_top_sql:', err);
            if (!lastAshTopSqlData) setAshPanelMessage(ashTopSqlContainer(), '조회 실패: ' + err.message, true);
        }
    }

    function renderAshTopSqlLongRangeNotice() {
        if (ashTopSqlChart) { ashTopSqlChart.destroy(); ashTopSqlChart = null; }
        lastAshTopSqlData = null;
        if (ashOtherDrilldownOpen) closeAshOtherDrilldown();
        const legend = document.getElementById('ash-topsql-legend');
        if (legend) legend.innerHTML = '';
        const canvas = document.getElementById('ash-topsql-chart');
        if (canvas) {
            const ctx = canvas.getContext('2d');
            ctx.clearRect(0, 0, canvas.width, canvas.height);
        }
        const container = document.getElementById('ash-topsql-container');
        if (container && !document.getElementById('ash-topsql-long-range-notice')) {
            const notice = document.createElement('div');
            notice.id = 'ash-topsql-long-range-notice';
            notice.style.cssText = 'position:absolute; inset:0; display:flex; align-items:center; justify-content:center; color: var(--text-muted); font-size:0.85rem; text-align:center; padding:20px;';
            notice.textContent = 'Top SQL Activity Timeline은 30분/1시간 구간에서만 제공됩니다.';
            container.appendChild(notice);
        }
    }

    function renderAshTopSqlLegend(sqlCategories) {
        const container = document.getElementById('ash-topsql-legend');
        if (!container) return;
        const items = sqlCategories.map(s => ({
            label: s.module ? `${s.label} (${s.module})` : s.label,
            color: ASH_TOPSQL_CATEGORY_COLOR[s.category] || ASH_TOPSQL_OTHER_COLOR
        }));
        // §9.1: 범례의 Other 항목에 클릭 가능 표시(밑줄 호버 + ▸ 화살표) - 클릭 시 드릴다운 패널 토글.
        container.innerHTML = items.map(it => `
            <span style="display:flex; align-items:center; gap:5px; font-size:0.78rem; font-weight:600; color: var(--text-main);">
                <span style="display:inline-block; width:10px; height:10px; border-radius:2px; background:${it.color}; flex:none;"></span>
                ${it.label}
            </span>
        `).join('') + `
            <span id="ash-topsql-other-legend" style="display:flex; align-items:center; gap:5px; font-size:0.78rem; font-weight:600; color: var(--text-main); cursor:pointer;">
                <span style="display:inline-block; width:10px; height:10px; border-radius:2px; background:${ASH_TOPSQL_OTHER_COLOR}; flex:none;"></span>
                <span style="text-decoration: underline dotted;">Other</span>
                <span style="font-size:0.7rem;">▸</span>
            </span>
        `;
        const otherLegend = document.getElementById('ash-topsql-other-legend');
        if (otherLegend) {
            otherLegend.addEventListener('click', () => {
                if (ashOtherDrilldownOpen) {
                    closeAshOtherDrilldown();
                } else {
                    openAshOtherDrilldown();
                }
            });
        }
    }

    function renderAshTopSqlChart(data) {
        const canvas = document.getElementById('ash-topsql-chart');
        if (!canvas) return;
        const notice = document.getElementById('ash-topsql-long-range-notice');
        if (notice) notice.remove();
        lastAshTopSqlData = data;
        renderAshTopSqlLegend(data.sql_categories || []);

        const sqlCats = data.sql_categories || [];
        const datasets = sqlCats.map((s, i) => {
            const color = ASH_TOPSQL_CATEGORY_COLOR[s.category] || ASH_TOPSQL_OTHER_COLOR;
            return {
                label: s.module ? `${s.label} (${s.module})` : s.label,
                data: data.series.map(pt => ({ x: new Date(pt.time).getTime(), y: pt.values[i] })),
                borderColor: color,
                backgroundColor: color + 'd1',
                borderWidth: 1,
                pointRadius: 0,
                fill: true,
                stack: 'topsql',
                tension: 0.15
            };
        });
        datasets.push({
            label: 'Other',
            data: data.series.map(pt => ({ x: new Date(pt.time).getTime(), y: pt.values[sqlCats.length] })),
            borderColor: ASH_TOPSQL_OTHER_COLOR,
            backgroundColor: ASH_TOPSQL_OTHER_COLOR + 'd1',
            borderWidth: 1,
            pointRadius: 0,
            fill: true,
            stack: 'topsql',
            tension: 0.15
        });

        if (!ashTopSqlChart) {
            ashTopSqlChart = new Chart(canvas, {
                type: 'line',
                data: { datasets },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    animation: { duration: 0 },
                    interaction: { mode: 'index', intersect: false },
                    scales: {
                        x: {
                            type: 'time',
                            time: { unit: 'minute', tooltipFormat: 'HH:mm', displayFormats: { minute: 'HH:mm' } },
                            ticks: { color: chartLineColor(0.8), maxRotation: 0, minRotation: 0, font: { size: 13 } },
                            grid: { drawOnChartArea: true, color: chartLineColor(0.15), borderDash: [4, 4] },
                            border: { display: true, color: chartLineColor(1), width: 2 }
                        },
                        y: {
                            stacked: true,
                            beginAtZero: true,
                            min: 0,
                            ticks: { precision: 1, color: chartLineColor(0.8), font: { size: 13 } },
                            grid: { drawOnChartArea: true, color: chartLineColor(0.15), borderDash: [4, 4] },
                            border: { display: true, color: chartLineColor(1), width: 2 }
                        }
                    },
                    plugins: {
                        legend: { display: false }, // 커스텀 범례(#ash-topsql-legend)로 대체
                        title: { display: true, text: 'Top SQL Activity Timeline' },
                        subtitle: {
                            display: true,
                            text: 'AAS by SQL_ID (Top 5 + Other)',
                            align: 'start',
                            color: chartLineColor(0.8),
                            padding: { bottom: 10 }
                        }
                    }
                }
            });
        } else {
            // Top5 구성이 폴링마다 바뀔 수 있어(§8.2 - 표시 구간 전체 기준으로만 재산정) 데이터셋 개수
            // 자체가 매번 달라질 수 있다 - 라벨/범례가 그대로 유지된다는 보장이 없으므로 항상 통째로 교체.
            ashTopSqlChart.data.datasets = datasets;
            ashTopSqlChart.update();
        }

        // §9.1: "시간 범위를 바꾸면 열려 있는 드릴다운 패널도 자동으로 새 범위 기준으로 갱신" - 이
        // 함수는 매 30초 폴링과 30분/1시간 전환마다 호출되므로 여기 한 곳에 걸어두면 다 커버된다.
        if (ashOtherDrilldownOpen) refreshAshOtherDrilldown();
    }

    // "Other" 드릴다운(설계문서 §9, 5단계 - 2026-09-22) - ashSelectedWindow(드래그 선택)가 있으면 그
    // 구간, 없으면 현재 차트가 보여주는 전체 표시 구간(lastAshTopSqlData.series 첫~끝)을 사용(§9.1).
    function getAshDrilldownWindow() {
        if (ashSelectedWindow) return ashSelectedWindow;
        if (!lastAshTopSqlData || !lastAshTopSqlData.series || lastAshTopSqlData.series.length === 0) return null;
        const series = lastAshTopSqlData.series;
        const stepMs = (lastAshTopSqlData.step_minutes || 1) * 60000;
        return {
            start: new Date(series[0].time),
            end: new Date(new Date(series[series.length - 1].time).getTime() + stepMs)
        };
    }

    async function fetchAshOtherBreakdown() {
        if (!window.currentDbId || !lastAshTopSqlData) return;
        const win = getAshDrilldownWindow();
        if (!win) return;
        const startParam = formatAshDateTimeParam(win.start);
        const endParam = formatAshDateTimeParam(win.end);
        const excludeIds = (lastAshTopSqlData.sql_categories || []).map(s => s.sql_id).join(',');
        try {
            const res = await fetch(`/api/ash_other_breakdown?db_id=${window.currentDbId}&start_time=${encodeURIComponent(startParam)}&end_time=${encodeURIComponent(endParam)}&exclude_sql_ids=${encodeURIComponent(excludeIds)}&token=${encodeURIComponent(getToken())}`);
            const data = await res.json();
            if (!res.ok || data.error) throw new Error(data.error || 'ash_other_breakdown 조회 실패');
            renderAshOtherBreakdownPanel(data, win);
        } catch (err) {
            console.error('Failed to fetch ash_other_breakdown:', err);
        }
    }

    function renderAshOtherBreakdownPanel(data, win) {
        const summaryEl = document.getElementById('ash-other-drilldown-summary');
        const listEl = document.getElementById('ash-other-drilldown-list');
        const tailEl = document.getElementById('ash-other-drilldown-tail');
        if (!summaryEl || !listEl || !tailEl) return;

        const fmtTime = (d) => d.toTimeString().slice(0, 5);
        summaryEl.textContent = `선택 구간 ${fmtTime(win.start)} ~ ${fmtTime(win.end)} · Other 합계 AAS ${data.other_total_aas.toFixed(2)}`;

        const items = data.items || [];
        if (items.length === 0) {
            listEl.innerHTML = '<div style="color: var(--text-muted); padding: 10px 0;">이 구간에는 Other로 뭉친 SQL이 없습니다.</div>';
        } else {
            const maxPct = Math.max(...items.map(it => it.pct)); // §9.3: 막대 길이는 1위 값을 100%로 한 상대값
            listEl.innerHTML = items.map((it, i) => {
                const color = ASH_TOPSQL_CATEGORY_COLOR[it.category] || ASH_TOPSQL_OTHER_COLOR;
                const barPct = maxPct > 0 ? (it.pct / maxPct) * 100 : 0;
                return `
                    <div style="display:flex; align-items:center; gap:8px; padding:4px 0; font-size:0.82rem;">
                        <span style="width:20px; color: var(--text-muted); text-align:right;">${i + 1}.</span>
                        <span style="display:inline-block; width:9px; height:9px; border-radius:50%; background:${color}; flex:none;"></span>
                        <span style="width:90px; color: var(--text-main); font-family: monospace;">${it.label}</span>
                        <span style="flex:1; background: var(--bg-card); border-radius:3px; height:14px; overflow:hidden;">
                            <span style="display:block; width:${barPct}%; height:100%; background:${color};"></span>
                        </span>
                        <span style="width:50px; text-align:right; color: var(--text-main);">${it.pct.toFixed(1)}%</span>
                        <span style="width:70px; color: var(--text-secondary);">${it.category}</span>
                    </div>
                `;
            }).join('');
        }

        tailEl.textContent = data.tail_count > 0
            ? `이 외 ${data.tail_count}개 SQL이 약 ${data.tail_aas.toFixed(2)} AAS를 차지 (개별 미표시)`
            : '';
    }

    function openAshOtherDrilldown() {
        ashOtherDrilldownOpen = true;
        const panel = document.getElementById('ash-other-drilldown');
        if (panel) panel.style.display = 'block';
        fetchAshOtherBreakdown();
    }

    function closeAshOtherDrilldown() {
        ashOtherDrilldownOpen = false;
        const panel = document.getElementById('ash-other-drilldown');
        if (panel) panel.style.display = 'none';
    }

    function refreshAshOtherDrilldown() {
        fetchAshOtherBreakdown();
    }

    const ashOtherDrilldownCloseBtn = document.getElementById('ash-other-drilldown-close');
    if (ashOtherDrilldownCloseBtn) {
        ashOtherDrilldownCloseBtn.addEventListener('click', closeAshOtherDrilldown);
    }

    // Active Session Wait Class 차트 + Top SQL Activity Timeline(3단계) - 같은 range를 공유하는
    // companion 위젯이라 같은 30초 루프에서 함께 갱신한다(§8 "나란히 배치" 요구사항).
    function fetchAshPanels() {
        return Promise.all([fetchAshActivity(), fetchAshTopSql()]);
    }

    function scheduleNextAshActivityFetch(generation) {
        ashActivityTimer = setTimeout(async () => {
            await fetchAshPanels();
            if (generation === ashActivityGeneration) {
                scheduleNextAshActivityFetch(generation);
            }
        }, ASH_ACTIVITY_POLL_MS);
    }

    // DB 전환/구간(30분↔1시간) 변경 시 호출 - 기존 30초 루프를 무효화(세대 증가)하고 즉시 1회 조회 후
    // 새 루프를 시작한다. fetchSessions()의 sessionRefreshGeneration과 같은 기법.
    function restartAshActivityPolling() {
        clearTimeout(ashActivityTimer);
        const generation = ++ashActivityGeneration;
        fetchAshPanels().then(() => {
            if (generation === ashActivityGeneration) {
                scheduleNextAshActivityFetch(generation);
            }
        });
    }

    // 페이지를 막 열었을 때(DB 자동 선택·첫 응답 전)도 그래프 자리가 빈 영역이 아니라 축·격자 +
    // "불러오는 중…"으로 보이게 한다(체크리스트 1-2). RDB/FO 화면에서 돌아올 때도 페이지가 새로
    // 열리므로 같은 경로를 탄다.
    try {
        ensureAshSkeletons();
        setAshPanelMessage(ashActivityContainer(), '불러오는 중…');
        setAshPanelMessage(ashTopSqlContainer(), '불러오는 중…');
    } catch (skeletonErr) {
        console.error('ASH skeleton init failed:', skeletonErr);
    }

    document.querySelectorAll('[data-ash-range]').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('[data-ash-range]').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            ashActivityRangeMinutes = parseInt(btn.getAttribute('data-ash-range'), 10) || 60;
            ashSelectedWindow = null; // 구간을 바꾸면 이전 드래그 선택은 더 이상 유효하지 않음(§9.1)
            restartAshActivityPolling();
        });
    });

    // 영역형↔막대형/텍스처/표 보기는 서버 재조회 없이 lastAshActivityData로 즉시 다시 그린다.
    document.querySelectorAll('[data-ash-form]').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('[data-ash-form]').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            ashActivityForm = btn.getAttribute('data-ash-form') === 'bar' ? 'bar' : 'area';
            if (lastAshActivityData) renderAshActivityChart(lastAshActivityData);
        });
    });
    const ashTextureToggle = document.getElementById('ash-texture-toggle');
    if (ashTextureToggle) {
        ashTextureToggle.addEventListener('change', () => {
            ashActivityTexture = ashTextureToggle.checked;
            if (lastAshActivityData) renderAshActivityChart(lastAshActivityData);
        });
    }
    const ashTableToggle = document.getElementById('ash-table-toggle');
    if (ashTableToggle) {
        ashTableToggle.addEventListener('change', () => {
            ashActivityShowTable = ashTableToggle.checked;
            if (lastAshActivityData) renderAshActivityChart(lastAshActivityData);
        });
    }

    // Dashboard Logic
    const dashCpuVal = document.getElementById('dash-cpu-val');
    const dashMemVal = document.getElementById('dash-mem-val');
    const dashSessVal = document.getElementById('dash-sess-val');
    
    let dashCpuChart = null;
    let dashMemChart = null;
    let dashFailChart = null;
    let dashSessChart = null;
    
    const dashHistory = {
        labels: [],
        cpu: [],
        mem: []
    };

    const maxDashPoints = 300; // 5 minutes at 1s interval

    // 기본 임계치(전역) - DB별로 databases.json 인스턴스에 "session_thresholds": [t1,t2,t3,t4,t5] 를
    // 추가하면(예: [200,300,400,500,600]) 그 DB에서는 이 기본값 대신 그 값을 사용함 (window.currentSessionThresholds,
    // instLink 클릭 시 채워짐 - 위 DB 트리 로딩 부분 참고).
    const DEFAULT_SESSION_THRESHOLDS = [60, 70, 80, 90, 100];

    function getSessColor(count, thresholds) {
        const t = (thresholds && thresholds.length === 5) ? thresholds : DEFAULT_SESSION_THRESHOLDS;
        if (count >= t[4]) return '#6e1f1f';
        if (count >= t[3]) return '#9e2d2d';
        if (count >= t[2]) return '#d03b3b';
        if (count >= t[1]) return '#fab219';
        if (count >= t[0]) return '#d9a72f';
        return '#0ca30c';
    }

    function createSegmentedDoughnutChart(ctx, value, activeColor) {
        const segments = 10;
        const dataArr = Array(segments).fill(1);
        const bgColors = Array(segments).fill(chartLineColor(0.1)); // Faint white outline for off segments
        
        const activeSegments = Math.round((value / 100) * segments);
        for(let i=0; i<activeSegments; i++) {
            bgColors[i] = activeColor;
        }

        return new Chart(ctx, {
            type: 'doughnut',
            data: {
                labels: Array(segments).fill(''),
                datasets: [{
                    data: dataArr,
                    backgroundColor: bgColors,
                    borderWidth: 0, // No border needed
                    spacing: 4 // Creates physical transparent gaps between segments
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                cutout: '65%',
                animation: { duration: 0 },
                plugins: {
                    legend: { display: false },
                    tooltip: { enabled: false }
                }
            }
        });
    }

    // Track which db_id each fetch is in flight for (not just a boolean): lets switching DB
    // start a fresh request immediately instead of being blocked by the previous DB's slow one,
    // and lets a late/stale response be discarded if the user has since switched DBs.
    let fetchingBasicForDbId = null;
    let fetchingHealthForDbId = null;
    let fetchingLocksForDbId = null;
    let fetchingSessionDataForDbId = null;
    let fetchingEventsForDbId = null;

    // Defined at this shared scope (not inside fetchDashboard) so the DB-switch click handler can
    // also call them directly, wiping stale widgets the instant a new DB is selected rather than
    // waiting on any in-flight fetch to resolve.
    // Clears a segmented doughnut chart back to the "no data" look (all segments faint) instead of
    // leaving the previous DB's colored segments on screen until the next fetch resolves.
    const clearSegmentedDoughnutChart = (chart) => {
        if (!chart) return;
        const segments = chart.data.datasets[0].backgroundColor.length;
        chart.data.datasets[0].backgroundColor = Array(segments).fill(chartLineColor(0.1));
        chart.update();
    };

    const resetBasic = () => {
        const dashCpuVal = document.getElementById('dash-cpu-val');
        const dashMemVal = document.getElementById('dash-mem-val');
        const dashSessVal = document.getElementById('dash-sess-val');
        if (dashCpuVal) { dashCpuVal.innerText = '-'; dashCpuVal.style.color = 'var(--text-main)'; }
        if (dashMemVal) { dashMemVal.innerText = '-'; dashMemVal.style.color = 'var(--text-main)'; }
        if (dashSessVal) dashSessVal.innerText = '-';
        clearSegmentedDoughnutChart(dashCpuChart);
        clearSegmentedDoughnutChart(dashMemChart);
        clearSegmentedDoughnutChart(dashSessChart);
    };

    const resetFailIndicator = () => {
        const failVal = document.getElementById('dash-fail-val');
        const failCountLabel = document.getElementById('dash-fail-count');
        if (failVal) failVal.textContent = '-';
        if (failCountLabel) failCountLabel.textContent = '';
        document.body.classList.remove('alert-blink');
        const incidentBtn = document.getElementById('dash-incident-action-btn');
        if (incidentBtn) incidentBtn.style.display = 'none';
        clearSegmentedDoughnutChart(dashFailChart);
    };

    const resetHealth = () => {
        const elInst = document.getElementById('mini-status-instance');
        const elInstCirc = document.getElementById('mini-status-instance-circle');
        if (elInst && elInstCirc) {
            elInst.innerText = '-';
            elInst.style.color = 'var(--text-main)';
            elInstCirc.style.backgroundColor = 'var(--text-muted)';
        }
        const elList = document.getElementById('mini-status-listener');
        const elListCirc = document.getElementById('mini-status-listener-circle');
        if (elList && elListCirc) {
            elList.innerText = '-';
            elList.style.color = 'var(--text-main)';
            elListCirc.style.backgroundColor = 'var(--text-muted)';
        }
        ['mini-status-max-session', 'mini-status-active-session', 'mini-status-inactive-session',
         'mini-status-max-process', 'mini-status-dedicated-session', 'mini-status-shared-session'].forEach(id => {
            const el = document.getElementById(id);
            if (el) el.innerText = '--';
        });
    };

    const resetSessionData = (message = 'DB에 연결할 수 없습니다.') => {
        const tbody = document.getElementById('dash-sess-tbody');
        if (tbody) tbody.innerHTML = `<tr><td colspan="7" style="text-align:center;">${message}</td></tr>`;
    };

    const resetEvents = (message = 'DB에 연결할 수 없습니다.') => {
        const tbody = document.getElementById('dash-event-tbody');
        if (tbody) tbody.innerHTML = `<tr><td colspan="2" style="text-align:center;">${message}</td></tr>`;
    };

    // Active Session 목록 행 HTML을 만드는 공용 헬퍼 - 원래 fetchDashboard() 안의 dash-sess-tbody
    // 전용 코드였는데, 오라클 인스턴스 대시보드 v3(벤토형) 하단에도 같은 목록을 통째로 옮겨야 해서
    // (사용자 요청, 2026-09-17) checkboxClass를 매개변수로 뽑아냈다. v3 쪽은 별도 IIFE(스코프가 달라
    // 이 함수를 직접 참조 못함)라 window에 노출해서 쓴다.
    function buildSessionRowsHtml(sessions, checkboxClass) {
        if (!sessions || sessions.length === 0) {
            return '<tr><td colspan="16" style="text-align:center;">ACTIVE 상태인 세션이 없습니다.</td></tr>';
        }
        const maxDuration = sessions.reduce((max, s) => Math.max(max, Number(s.duration_time) || 0), 1);
        return sessions.map(s => {
            const durationVal = s.duration_time !== null ? Number(s.duration_time) : 0;
            const durationPct = Math.min((durationVal / maxDuration) * 100, 100);
            const durationHtml = s.duration_time !== null ? `<div style="display: flex; align-items: center; gap: 8px;"><div style="flex-grow: 1; background-color: var(--track-bg); height: 8px; border-radius: 4px; overflow: hidden; width: 60px;"><div style="width: ${durationPct}%; height: 100%; background-color: #3987e5; border-radius: 4px;"></div></div><span style="min-width: 30px; text-align: right;">${durationVal}</span></div>` : '-';
            let waitHtml = `<div style="color: var(--text-secondary);">-</div>`;
            if (s.session_wait_pct && s.session_wait_pct.includes(',')) {
                const [cpu, uio, sio, latch, txlock, tmlock, other] = s.session_wait_pct.split(',').map(Number);
                if (cpu + uio + sio + latch + txlock + tmlock + other > 0) {
                    waitHtml = `<div style="display: flex; width: 100px; height: 12px; border-radius: 6px; overflow: hidden; background-color: var(--track-bg);" title="CPU: ${cpu}%, User I/O: ${uio}%, Sys I/O: ${sio}%, Latch: ${latch}%, TX Lock: ${txlock}%, TM Lock: ${tmlock}%, Other: ${other}%"><div style="width: ${cpu}%; background-color: #22d3ee;" title="CPU: ${cpu}%"></div><div style="width: ${uio}%; background-color: #2ecc71;" title="User I/O: ${uio}%"></div><div style="width: ${sio}%; background-color: #e67e22;" title="Sys I/O: ${sio}%"></div><div style="width: ${latch}%; background-color: #808000;" title="Latch: ${latch}%"></div><div style="width: ${txlock}%; background-color: #7c3aed;" title="TX Lock: ${txlock}%"></div><div style="width: ${tmlock}%; background-color: #be123c;" title="TM Lock: ${tmlock}%"></div><div style="width: ${other}%; background-color: var(--text-muted);" title="Other: ${other}%"></div></div>`;
                }
            }
            return `<tr class="clickable-session-row" style="cursor:pointer;" data-sid="${s.sid}" data-serial="${s.serial || ''}" data-sql_id="${s.sql_id || ''}">
                <td style="text-align:center;" onclick="event.stopPropagation();"><input type="checkbox" class="${checkboxClass}" data-sid="${s.sid}" data-serial="${s.serial}"></td>
                <td>${s.db_name || '-'}</td>
                <td><span class="status-badge online">${s.status}</span></td>
                <td>${s.sid}</td>
                <td>${s.serial}</td>
                <td>${s.server_pid || '-'}</td>
                <td>${durationHtml}</td>
                <td>${waitHtml}</td>
                <td>${s.sql_id || '-'}</td>
                <td>${s.event_name || '-'}</td>
                <td>${s.plan_hash_value || '-'}</td>
                <td><div style="max-width:200px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;" title="${s.sql_text || ''}">${s.sql_text || '-'}</div></td>
                <td>${s.machine_name || '-'}</td>
                <td>${s.osuser || '-'}</td>
                <td>${s.username || '-'}</td>
                <td>${s.program_name || '-'}</td>
            </tr>`;
        }).join('');
    }
    window.dbagentBuildSessionRows = buildSessionRowsHtml;

    // Called right when a DB is selected, before fetchDashboard() has had a chance to return -
    // this is a loading state, not a real connection failure, so it must not use the error wording.
    function resetAllDashboardWidgets() {
        resetBasic();
        resetHealth();
        resetFailIndicator();
        resetSessionData('접속 중...');
        resetEvents('접속 중...');
    }

    async function fetchDashboard() {
        if (!document.getElementById('dashboard').classList.contains('active')) return;
        // v2/v3(신규 대시보드)가 보이는 동안은 이 레거시 폴러를 쉰다 - 안 그러면 같은 인스턴스에
        // 레거시(이 함수, 2초 간격 5종 병렬 fetch)와 v2/v3(10초 간격, 별도 5~6종) 폴링이 동시에 겹쳐
        // 커넥션 풀을 이중으로 잠식한다(사용자 실측 "신규 대시보드 진입 후 전체 메뉴 먹통" 버그).
        // 플래그가 아직 없으면(v2/v3 마크업이 없는 페이지 등) 기존과 동일하게 항상 돈다.
        if (window.dbagentActiveDashboardSubView && window.dbagentActiveDashboardSubView !== 'legacy') return;
        // DB가 아직 선택되기 전(페이지 막 로드된 시점)이면 db_id=""로 나가는 낭비성 요청을 막는다
        // (오케스트레이터 실측, 2026-09-18: 로그인 직후 이 폴러의 첫 tick이 setView()보다 먼저 돌아
        // 5종 API가 전부 빈 db_id로 나가며 각각 커넥션 타임아웃만큼 헛돌았음).
        if (!window.currentDbId) return;

        const host = window.location.hostname || '127.0.0.1';
        const dbId = window.currentDbId || "";

        const fetchBasic = async () => {
            if (fetchingBasicForDbId === dbId) return;
            fetchingBasicForDbId = dbId;
            try {
                const response = await fetch(`/api/dashboard?db_id=${dbId}&token=${encodeURIComponent(getToken())}`);
                if (dbId !== window.currentDbId) return; // stale: user switched DBs while this was in flight
                if (!response.ok) { resetBasic(); return; }
                const data = await response.json();
                if (data.error) { resetBasic(); return; }

                // Update texts
                const dashCpuVal = document.getElementById('dash-cpu-val');
                const dashMemVal = document.getElementById('dash-mem-val');
                const dashSessVal = document.getElementById('dash-sess-val');
                
                if (dashCpuVal) {
                    dashCpuVal.innerText = `${data.cpu}%`;
                    if (data.cpu >= 90) dashCpuVal.style.color = '#d03b3b';
                    else if (data.cpu >= 80) dashCpuVal.style.color = '#fab219';
                    else dashCpuVal.style.color = 'var(--text-main)';
                }
                if (dashMemVal) dashMemVal.innerText = `${data.memory}%`;
                if (dashSessVal) {
                    dashSessVal.innerText = data.active_sessions;
                    dashSessVal.style.color = getSessColor(data.active_sessions, window.currentSessionThresholds);
                }
                
                // Update Memory color based on usage
                if (dashMemVal) {
                    if (data.memory >= 90) dashMemVal.style.color = '#d03b3b';
                    else if (data.memory >= 80) dashMemVal.style.color = '#fab219';
                    else dashMemVal.style.color = 'var(--text-main)';
                }
                
                // Update charts
                const nowTime = new Date().getTime();
                dashHistory.labels.push(nowTime);
                dashHistory.cpu.push(data.cpu);
                dashHistory.mem.push(data.memory);

                if (dashHistory.labels.length > maxDashPoints) {
                    dashHistory.labels.shift();
                    dashHistory.cpu.shift();
                    dashHistory.mem.shift();
                }
                
                const cpuCtx = document.getElementById('dash-cpu-chart');
                const memCtx = document.getElementById('dash-mem-chart');
                const sessCtx = document.getElementById('dash-sess-chart');
                
                if (cpuCtx) {
                    let cpuColor = '#3987e5';
                    if (data.cpu >= 90) cpuColor = '#d03b3b';
                    else if (data.cpu >= 80) cpuColor = '#fab219';
                    
                    const segments = 10;
                    const activeSegments = Math.round((data.cpu / 100) * segments);
                    const bgColors = Array(segments).fill(chartLineColor(0.1));
                    for(let i=0; i<activeSegments; i++) {
                        bgColors[i] = cpuColor;
                    }

                    if (!dashCpuChart) {
                        dashCpuChart = createSegmentedDoughnutChart(cpuCtx, data.cpu, cpuColor);
                    } else {
                        dashCpuChart.data.datasets[0].backgroundColor = bgColors;
                        dashCpuChart.update();
                    }
                }
                if (memCtx) {
                    let memColor = '#3987e5';
                    if (data.memory >= 90) memColor = '#d03b3b';
                    else if (data.memory >= 80) memColor = '#fab219';
                    
                    const segments = 10;
                    const activeSegments = Math.round((data.memory / 100) * segments);
                    const bgColors = Array(segments).fill(chartLineColor(0.1));
                    for(let i=0; i<activeSegments; i++) {
                        bgColors[i] = memColor;
                    }

                    if (!dashMemChart) {
                        dashMemChart = createSegmentedDoughnutChart(memCtx, data.memory, memColor);
                    } else {
                        dashMemChart.data.datasets[0].backgroundColor = bgColors;
                        dashMemChart.update();
                    }
                }
                
                if (sessCtx) {
                    const sessThresholds = window.currentSessionThresholds || DEFAULT_SESSION_THRESHOLDS;
                    const sessColor = getSessColor(data.active_sessions, sessThresholds);
                    // 링은 이 DB의 최상위 임계치(다섯 번째 값) 기준으로 꽉 채워짐 - DB마다 정상 범위가 다르므로
                    // 절대 100이 아니라 그 DB의 "심각" 기준에 도달했을 때 100%로 보이게 함.
                    const sessPercent = Math.min((data.active_sessions / sessThresholds[4]) * 100, 100);

                    const segments = 10;
                    const activeSegments = Math.round((sessPercent / 100) * segments);
                    const bgColors = Array(segments).fill(chartLineColor(0.1));
                    for(let i=0; i<activeSegments; i++) {
                        bgColors[i] = sessColor;
                    }

                    if (!dashSessChart) {
                        dashSessChart = createSegmentedDoughnutChart(sessCtx, sessPercent, sessColor);
                    } else {
                        dashSessChart.data.datasets[0].backgroundColor = bgColors;
                        dashSessChart.update();
                    }
                }
            } catch (error) {
                console.error('Dashboard fetchBasic error:', error);
                if (dbId === window.currentDbId) resetBasic();
            } finally {
                if (fetchingBasicForDbId === dbId) fetchingBasicForDbId = null;
            }
        };

        const markHealthDown = () => {
            const elInst = document.getElementById('mini-status-instance');
            const elInstCirc = document.getElementById('mini-status-instance-circle');
            if (elInst && elInstCirc) {
                elInst.innerText = 'Not Alive';
                elInst.style.color = '#d03b3b';
                elInstCirc.style.backgroundColor = '#d03b3b';
            }
            const elList = document.getElementById('mini-status-listener');
            const elListCirc = document.getElementById('mini-status-listener-circle');
            if (elList && elListCirc) {
                elList.innerText = 'Not Alive';
                elList.style.color = '#d03b3b';
                elListCirc.style.backgroundColor = '#d03b3b';
            }
            ['mini-status-max-session', 'mini-status-active-session', 'mini-status-inactive-session',
             'mini-status-max-process', 'mini-status-dedicated-session', 'mini-status-shared-session'].forEach(id => {
                const el = document.getElementById(id);
                if (el) el.innerText = '--';
            });
        };

        const fetchHealth = async () => {
            if (fetchingHealthForDbId === dbId) return;
            fetchingHealthForDbId = dbId;
            try {
                const hRes = await fetch(`/api/health?db_id=${dbId}&token=${encodeURIComponent(getToken())}`);
                if (dbId !== window.currentDbId) return; // stale: user switched DBs while this was in flight
                if (hRes.ok) {
                    const hData = await hRes.json();
                    
                    // 'Busy' = self-inflicted pool contention/cooldown, not a real outage - shown in
                    // amber so it isn't mistaken for the DB actually being down.
                    const statusColor = (status) => status === 'Alive' ? '#3987e5' : (status === 'Busy' ? '#fab219' : '#d03b3b');

                    const elInst = document.getElementById('mini-status-instance');
                    const elInstCirc = document.getElementById('mini-status-instance-circle');
                    if (elInst && elInstCirc) {
                        elInst.innerText = hData.instance_status;
                        const c = statusColor(hData.instance_status);
                        elInst.style.color = c;
                        elInstCirc.style.backgroundColor = c;
                    }

                    const dbNameEl = document.getElementById('current-db-name');
                    if (dbNameEl && hData.db_name) {
                        dbNameEl.innerText = hData.db_name;
                    }

                    const elList = document.getElementById('mini-status-listener');
                    const elListCirc = document.getElementById('mini-status-listener-circle');
                    if (elList && elListCirc) {
                        elList.innerText = hData.listener_status;
                        const lc = statusColor(hData.listener_status);
                        elList.style.color = lc;
                        elListCirc.style.backgroundColor = lc;
                    }

                    // null when the underlying v$parameter/v$session query failed (e.g. missing
                    // grant) rather than the DB being down - show '--' instead of a misleading 0.
                    const setStat = (id, value) => {
                        const el = document.getElementById(id);
                        if (el) el.innerText = (value === null || value === undefined) ? '--' : value;
                    };
                    setStat('mini-status-max-session', hData.max_sessions);
                    setStat('mini-status-active-session', hData.active_sessions);
                    setStat('mini-status-inactive-session', hData.inactive_sessions);
                    setStat('mini-status-max-process', hData.max_processes);
                    setStat('mini-status-dedicated-session', hData.dedicated_sessions);
                    setStat('mini-status-shared-session', hData.shared_sessions);
                } else {
                    markHealthDown();
                }
            } catch(e) {
                if (dbId === window.currentDbId) markHealthDown();
            } finally {
                if (fetchingHealthForDbId === dbId) fetchingHealthForDbId = null;
            }
        };

        const fetchLocks = async () => {
            if (fetchingLocksForDbId === dbId) return;
            fetchingLocksForDbId = dbId;
            try {
                const [tmResponse, failResponse] = await Promise.all([
                    fetch(`/api/tmlock?db_id=${dbId}&token=${encodeURIComponent(getToken())}`),
                    fetch(`/api/failure_prob?db_id=${dbId}&token=${encodeURIComponent(getToken())}`)
                ]);
                if (dbId !== window.currentDbId) return; // stale: user switched DBs while this was in flight

                let tmLockCount = 0;
                let txLockCount = 0;
                if (tmResponse.ok) {
                    const tmData = await tmResponse.json();
                    if (tmData && !tmData.error) {
                        tmData.forEach(h => {
                            if (h.lock_type === 'TM') tmLockCount++;
                            else if (h.lock_type === 'TX') txLockCount++;
                            if (h.waiters) {
                                h.waiters.forEach(w => {
                                    if (w.lock_type === 'TM') tmLockCount++;
                                    else if (w.lock_type === 'TX') txLockCount++;
                                });
                            }
                        });
                    }
                }

                let count = 0;
                if (failResponse.ok) {
                    const failData = await failResponse.json();
                    if (failData && failData.count !== undefined) {
                        count = failData.count;
                    }
                }
                
                let percentage = 0;
                if (count === 1) percentage = 30;
                else if (count === 2) percentage = 40;
                else if (count === 3) percentage = 50;
                else if (count === 4) percentage = 60;
                else if (count === 5) percentage = 70;
                else if (count === 6) percentage = 80;
                else if (count >= 7) percentage = 90;
                
                const failCtx = document.getElementById('dash-fail-chart');
                const failVal = document.getElementById('dash-fail-val');
                const failCountLabel = document.getElementById('dash-fail-count');
                
                if (failCtx && failVal) {
                    failVal.textContent = percentage + '%';
                    if (failCountLabel) {
                        failCountLabel.textContent = `[TM Lock ${tmLockCount} EA / TX Lock ${txLockCount} EA]`;
                    }
                    if (percentage >= 80) document.body.classList.add('alert-blink');
                    else document.body.classList.remove('alert-blink');

                    const incidentBtn = document.getElementById('dash-incident-action-btn');
                    if (incidentBtn) incidentBtn.style.display = (percentage >= 80 && isAdmin()) ? 'block' : 'none';


                    let failColor = '#0ca30c';
                    if (percentage >= 70) failColor = '#d03b3b';
                    else if (percentage >= 50) failColor = '#fab219';
                    
                    const segments = 10;
                    const activeSegments = Math.round((percentage / 100) * segments);
                    const bgColors = Array(segments).fill(chartLineColor(0.1));
                    for(let i=0; i<activeSegments; i++) bgColors[i] = failColor;
                    
                    if (!dashFailChart) {
                        dashFailChart = createSegmentedDoughnutChart(failCtx, percentage, failColor);
                    } else {
                        dashFailChart.data.datasets[0].backgroundColor = bgColors;
                        dashFailChart.update();
                    }
                }
            } catch (e) {
                console.error('Dashboard fetchLocks error:', e);
            } finally {
                if (fetchingLocksForDbId === dbId) fetchingLocksForDbId = null;
            }
        };

        const fetchSessionData = async () => {
            if (document.getElementById('dash-active-sess').style.display === 'none') return;
            if (fetchingSessionDataForDbId === dbId) return;
            fetchingSessionDataForDbId = dbId;
            try {
                const sessResponse = await fetch(`/api/session?db_id=${dbId}&token=${encodeURIComponent(getToken())}`);
                if (dbId !== window.currentDbId) return; // stale: user switched DBs while this was in flight
                if (sessResponse.ok) {
                    const sessData = await sessResponse.json();
                    if (sessData.error) { resetSessionData(); }
                    else {
                        const activeSess = sessData.filter(s => s && s.status && s.status.trim().toUpperCase() === 'ACTIVE');
                        const tbody = document.getElementById('dash-sess-tbody');
                        if (tbody) {
                            // Oracle SID는 재사용되므로 SID만으로는 세션을 특정할 수 없다 - SID+SERIAL# 복합키로 대조.
                            const checkedDashKeys = new Set(Array.from(document.querySelectorAll('.dash-sess-checkbox:checked'))
                                .map(cb => cb.getAttribute('data-sid') + ':' + cb.getAttribute('data-serial')));
                            tbody.innerHTML = buildSessionRowsHtml(activeSess, 'dash-sess-checkbox');
                            document.querySelectorAll('.dash-sess-checkbox').forEach(cb => {
                                if (checkedDashKeys.has(cb.getAttribute('data-sid') + ':' + cb.getAttribute('data-serial'))) {
                                    cb.checked = true;
                                }
                            });
                            if (window.dbagentSyncKillButtons) window.dbagentSyncKillButtons();
                        }
                    }
                } else {
                    resetSessionData();
                }
            } catch (e) {
                console.error('Dashboard fetchSession error:', e);
                if (dbId === window.currentDbId) resetSessionData();
            } finally {
                if (fetchingSessionDataForDbId === dbId) fetchingSessionDataForDbId = null;
            }
        };

        const fetchEvents = async () => {
            if (document.getElementById('dash-top-event').style.display === 'none') return;
            if (fetchingEventsForDbId === dbId) return;
            fetchingEventsForDbId = dbId;
            try {
                const eventResponse = await fetch(`/api/top_events?db_id=${dbId}&token=${encodeURIComponent(getToken())}`);
                if (dbId !== window.currentDbId) return; // stale: user switched DBs while this was in flight
                if (eventResponse.ok) {
                    const eventData = await eventResponse.json();
                    if (eventData.error) { resetEvents(); }
                    else {
                        const tbody = document.getElementById('dash-event-tbody');
                        if (tbody) {
                            if (eventData.length === 0) {
                                tbody.innerHTML = '<tr><td colspan="2" style="text-align:center;">이벤트 데이터가 없습니다.</td></tr>';
                            } else {
                                let html = '';
                                eventData.forEach(e => {
                                    html += `<tr>
                                        <td>${e.event}</td>
                                        <td>${e.count}</td>
                                    </tr>`;
                                });
                                tbody.innerHTML = html;
                            }
                        }
                    }
                } else {
                    resetEvents();
                }
            } catch (e) {
                console.error('Dashboard fetchEvents error:', e);
                if (dbId === window.currentDbId) resetEvents();
            } finally {
                if (fetchingEventsForDbId === dbId) fetchingEventsForDbId = null;
            }
        };

        // Fire all independent fetches without awaiting so they run fully in parallel
        fetchBasic();
        fetchHealth();
        fetchLocks();
        fetchSessionData();
        fetchEvents();
    }
    
    // Start dashboard polling. Interval comes from application.properties (dbagent.ui.polling-interval-ms,
    // default 2000) - 1000ms was tight enough that concurrent polling across widgets/tabs could exhaust
    // the connection pool and misreport a healthy DB as down. The property only seeds the initial value
    // at startup (사용자 요청, 2026-08-31) - "리프레쉬 주기" 입력칸 + "수동 새로고침"/"자동 갱신 시작·중지"
    // 버튼(Current Session 메뉴가 이미 쓰는 것과 동일한 패턴)으로 이후 직접 바꿀 수 있다. 대시보드는
    // 원래부터 항상 자동 갱신 상태였으므로 페이지 로드 시 기본값은 "켜짐"으로 유지 - Current Session과
    // 달리 꺼진 채로 시작하지 않는다.
    let dashboardPollingIntervalMs = 2000;
    let dashboardPollingTimer = null;
    let isDashboardAutoRefreshing = false;
    const dashRefreshIntervalInput = document.getElementById('dash-refresh-interval');
    const dashRefreshBtn = document.getElementById('dash-refresh-btn');
    const dashToggleBtn = document.getElementById('dash-toggle-btn');
    try {
        const pollingRes = await fetch('/api/config');
        if (pollingRes.ok) {
            const pollingData = await pollingRes.json();
            if (pollingData.polling_interval_ms) dashboardPollingIntervalMs = pollingData.polling_interval_ms;
            if (dashRefreshIntervalInput) dashRefreshIntervalInput.value = Math.round(dashboardPollingIntervalMs / 1000);
            // Current Session "리프레쉬 주기(초)" 기본값(dbagent.ui.session-refresh-seconds, 기본 3초 - 체크리스트 1-9).
            // 이미 자동 갱신이 돌고 있으면(이 응답보다 먼저 메뉴에 들어온 경우) 도는 주기와 입력칸이 어긋나지
            // 않도록 건드리지 않는다 - 그 경우엔 HTML 기본값 3초로 돌고 있다.
            if (pollingData.session_refresh_seconds && sessionIntervalInput && !isSessionAutoRefreshing) {
                sessionIntervalInput.value = pollingData.session_refresh_seconds;
            }

            // SQL Runner row-limit input: pre-fill with the server default and cap it at the
            // server's hard ceiling, so the UI can't ask for more rows than the backend allows anyway.
            const rowLimitInput = document.getElementById('sqlrunner-rowlimit-input');
            const maxRowsLabel = document.getElementById('sqlrunner-maxrows');
            const maxRowsLimitLabel = document.getElementById('sqlrunner-maxrows-limit');
            if (pollingData.sql_runner_max_rows) {
                if (rowLimitInput) rowLimitInput.value = pollingData.sql_runner_max_rows;
                if (maxRowsLabel) maxRowsLabel.textContent = pollingData.sql_runner_max_rows;
            }
            // SQL 실행 메뉴 읽기 전용 안내(체크리스트 5-1, 2026-09-25). 실제 차단은 서버가 한다 - 이건 표시만.
            const readOnlyNote = document.getElementById('sqlrunner-readonly-note');
            if (readOnlyNote) readOnlyNote.style.display = pollingData.sql_runner_read_only === false ? 'none' : 'inline-block';
            if (pollingData.sql_runner_max_rows_limit) {
                if (rowLimitInput) rowLimitInput.max = pollingData.sql_runner_max_rows_limit;
                if (maxRowsLimitLabel) maxRowsLimitLabel.textContent = pollingData.sql_runner_max_rows_limit;
            }
        }
    } catch (e) {
        console.error('Failed to load polling interval, using default:', e);
    }

    function startDashboardPolling() {
        fetchDashboard();
        dashboardPollingTimer = setInterval(fetchDashboard, dashboardPollingIntervalMs);
        isDashboardAutoRefreshing = true;
        if (dashToggleBtn) {
            dashToggleBtn.textContent = '자동 갱신 중지';
            dashToggleBtn.classList.remove('primary-btn');
            dashToggleBtn.classList.add('danger-btn');
        }
        if (dashRefreshBtn) dashRefreshBtn.disabled = true;
    }

    function stopDashboardPolling() {
        if (dashboardPollingTimer) clearInterval(dashboardPollingTimer);
        dashboardPollingTimer = null;
        isDashboardAutoRefreshing = false;
        if (dashToggleBtn) {
            dashToggleBtn.textContent = '자동 갱신 시작';
            dashToggleBtn.classList.remove('danger-btn');
            dashToggleBtn.classList.add('primary-btn');
        }
        if (dashRefreshBtn) dashRefreshBtn.disabled = false;
    }

    startDashboardPolling();

    if (dashToggleBtn) {
        dashToggleBtn.addEventListener('click', () => {
            if (isDashboardAutoRefreshing) {
                stopDashboardPolling();
            } else {
                // Math.max(1, ...)로 0 이하 입력을 막음 - 코드리뷰로 발견: "-5"처럼 truthy한 음수를
                // 넣으면 ||만으로는 안 걸러져서 setInterval에 음수(사실상 0ms)가 들어가 폴링이
                // 쉴새없이 돌며 커넥션 풀을 고갈시킬 수 있었음.
                const seconds = Math.max(1, parseInt(dashRefreshIntervalInput.value) || 2);
                dashboardPollingIntervalMs = seconds * 1000;
                startDashboardPolling();
            }
        });
    }
    if (dashRefreshBtn) {
        dashRefreshBtn.addEventListener('click', fetchDashboard);
    }

    // ---- Oracle instance dashboard v2/v3 (신규/베타, 2026-09-16) ----
    // 기존 대시보드(위 dashRefreshBtn/fetchDashboard 등)는 건드리지 않는다 - 화면 하단 스위치로 뷰만
    // 전환하고, 보이는 뷰의 폴러만 돈다. v2(스택형)/v3(벤토형)는 같은 데이터 계약(instance_overview/
    // active_alerts/top_events/metric_history)을 공유한다(Top SQL/Quick Stats 패널은 사용자 요청으로
    // 2026-09-18 제거, oracle-instance-dashboard-UI-spec_2.md
    // §0 "두 버전 모두 데이터 소스와 의미는 동일 - 프레젠테이션 레이어만 다르다"). 어떤 뷰를 기본값으로
    // 할지는 아직 미정이라 localStorage에 마지막 선택을 기억해 두고 다음 방문 때 그대로 이어서 보여준다.
    (function initInstanceDashboardV2() {
        const legacyView = document.getElementById('dashboard-legacy-view');
        const v2View = document.getElementById('dashboard-v2-view');
        const switchBtns = document.querySelectorAll('#iv2-view-switch .iv2-view-btn');
        if (!legacyView || !v2View || switchBtns.length === 0) return;

        let currentView = 'legacy';
        let iv2PollingTimer = null;
        // Current Session에서 이미 겪은 것과 같은 유형의 폴링 오버랩 버그(사용자 실측 2026-09-14,
        // scheduleNextSessionFetch 참고) 방지용. setInterval은 이전 라운드(아래 6개 API) 완료 여부와
        // 무관하게 무조건 10초마다 새로 쏘므로, 인스턴스 하나가 느려지거나 응답이 안 오면 라운드가
        // 계속 겹쳐 쌓이면서 브라우저 탭이 먹통이 된다(오케스트레이터 실측 2026-09-21: "다른 DB 접속시
        // hang → 전체 먹통, 브라우저 재시작하면 정상" - 서버는 멀쩡한데 클라이언트만 죽는 것으로 확인
        // 되어 서버가 아니라 여기가 원인). 같은 db_id의 이전 라운드가 아직 안 끝났으면 그 db_id의 새
        // 라운드만 건너뛴다.
        // db_id별 in-flight 여부를 단일 변수(마지막으로 시작한 db_id 하나만 기억)로 막으면, A→B→A로
        // 빠르게 되돌아갈 때 A의 첫 라운드가 아직 안 끝난 상태에서 두 번째 A 라운드가 또 시작되고,
        // 그러면 먼저 끝난 첫 라운드의 finally가 "지금 값이 A니까 내 라운드구나"라고 착각해 아직 진행 중인
        // 두 번째 A 라운드의 in-flight 상태를 지워버린다 - 그 틈에 10초 폴링이 겹쳐 다시 쌓이는 같은
        // 클래스의 버그가 재현된다. db_id마다 독립적으로 추적해야 해서 Set으로 관리한다.
        let iv2FetchInFlightDbIds = new Set();
        let iv2CpuDbTimeChart = null;
        let iv2WaitEventsChart = null;
        let iv2LockTrendChart = null;
        let iv2TmSparkChart = null;
        let iv2TxSparkChart = null;
        let iv2Range = '1h';
        let iv2LastAlerts = [];
        let iv2LastInstanceName = '';
        // iv2LastInstanceName이 어느 db_id에서 채워진 값인지 - DB 전환 직후엔 fetchInstanceOverviewV2()와
        // fetchActiveSessionV2()가 같은 Promise.all로 동시에 새 dbId를 요청하지만 응답 처리 순서는 보장되지
        // 않는다(오케스트레이터 실측, 2026-09-22) - fetchActiveSessionV2()가 먼저 끝나면 아직 이전 DB의
        // instanceName이 남아있는 iv2LastInstanceName을 그대로 보여줘 "세션 목록은 새 DB인데 라벨은 옛
        // DB"인 상태가 잠깐 뜬다. dbId가 일치할 때만 신뢰하고, 다르면(=아직 새 DB 값으로 안 채워짐) 값이
        // 없는 것처럼 취급해 로그인 직후 첫 폴링과 동일한 정책(비워두고 다음 폴링에 채움)을 적용한다.
        let iv2LastInstanceNameDbId = '';
        let iv2LastTmVal = 0;
        let iv2LastTxVal = 0;
        // UI-2(v3) Lock 현황 카드처럼 미니 스파크라인을 그리기 위한 클라이언트 쪽 롤링 버퍼 - 서버
        // 히스토리를 또 조회하지 않고 fetchIncidentGateV2()가 10초마다 받아오는 실시간 값을 그때그때
        // 쌓아서 쓴다(오케스트레이터 요청, 2026-09-18). 20개 = 폴링 10초 간격 기준 약 3분 20초치.
        let iv2TmSparkBuffer = [];
        let iv2TxSparkBuffer = [];
        const IV2_SPARK_MAX_POINTS = 20;

        // v2/v3 공통 - Lock 추이 카드 아래 "현재 TM/TX Lock 대기" 요약 박스(DASHBOARD-UI-1 샘플 목업
        // 반영, 2026-09-16 / UI-2 카드 스타일 + 실시간화로 개편, 2026-09-18). tm/tx 값은
        // fetchIncidentGateV2()가 /api/failure_prob에서 받아오는 실시간 값(장애조치 버튼과 동일 소스),
        // Blocking SID는 active_alerts의 "Blocking Session 감지" 항목이 이미 들고 있는 relatedSid를
        // 그대로 재사용 - 새 조회 없음.
        // 카드가 붉은색으로 바뀌는 임계치 - 장애조치 버튼 임계치(failure_prob 기반, iv2FailurePercentage)와
        // 완전히 별개(오케스트레이터 지적, 2026-09-18: "1건만 돼도 붉어지는 건 너무 민감하다").
        const IV2_TM_LOCK_DANGER_THRESHOLD = 6;
        const IV2_TX_LOCK_DANGER_THRESHOLD = 15;

        function iv2UpdateLockCurrentBoxes() {
            const tmEl = document.getElementById('iv2-lock-tm-current');
            const txEl = document.getElementById('iv2-lock-tx-current');
            if (tmEl) tmEl.textContent = `${iv2LastTmVal}건`;
            if (txEl) txEl.textContent = `${iv2LastTxVal}건`;
            const tmBox = document.getElementById('iv2-lock-tm-box');
            if (tmBox) tmBox.classList.toggle('danger', iv2LastTmVal >= IV2_TM_LOCK_DANGER_THRESHOLD);
            const txBox = document.getElementById('iv2-lock-tx-box');
            if (txBox) txBox.classList.toggle('danger', iv2LastTxVal >= IV2_TX_LOCK_DANGER_THRESHOLD);
            const subEl = document.getElementById('iv2-lock-tx-sub');
            if (subEl) {
                const blocking = iv2LastAlerts.find(a => a.relatedSid);
                subEl.textContent = (iv2LastTxVal >= IV2_TX_LOCK_DANGER_THRESHOLD && blocking) ? `SID ${blocking.relatedSid} Blocking` : '';
            }
        }

        // 미니 스파크라인(값+추세만 보여주는 축 없는 라인차트) 공용 렌더러 - 원래 UI-2(v3) 전용이었지만
        // UI-2 제거(오케스트레이터 요청, 2026-09-18) 후에도 UI-1의 TM/TX Lock 카드가 계속 쓰므로 이름에서
        // v3를 뗀다.
        function renderSparkline(canvasId, chartRef, values, color) {
            const ctx = document.getElementById(canvasId);
            if (!ctx) return chartRef;
            if (chartRef) {
                chartRef.data.labels = values.map((_, i) => i);
                chartRef.data.datasets[0].data = values;
                chartRef.update();
                return chartRef;
            }
            return new Chart(ctx, {
                type: 'line',
                data: { labels: values.map((_, i) => i), datasets: [{ data: values, borderColor: color, borderWidth: 1.5, pointRadius: 0, tension: 0.3, fill: false }] },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    animation: { duration: 0 },
                    plugins: { legend: { display: false }, tooltip: { enabled: false } },
                    scales: { x: { display: false }, y: { display: false } }
                }
            });
        }

        // fetchIncidentGateV2()가 새 실시간 값을 받아올 때만 부른다 - fetchActiveAlertsV2()는 Blocking
        // SID 라벨만 갱신하려고 iv2UpdateLockCurrentBoxes()를 따로 부르는데, 거기서 이것까지 같이
        // 부르면 값이 안 바뀌었는데도 스파크라인에 같은 점이 중복으로 찍힌다.
        function iv2PushLockSpark(tmVal, txVal) {
            iv2TmSparkBuffer.push(tmVal);
            iv2TxSparkBuffer.push(txVal);
            if (iv2TmSparkBuffer.length > IV2_SPARK_MAX_POINTS) iv2TmSparkBuffer.shift();
            if (iv2TxSparkBuffer.length > IV2_SPARK_MAX_POINTS) iv2TxSparkBuffer.shift();
            // 색은 Current Session 그래프의 TM/TX LOCK과 동일(오케스트레이터 요청, 2026-09-18) -
            // 아래 DBMS Lock 추이 그래프 색과 같은 이유(CPU/DB Time 색은 이후 원래대로 Rewind됨).
            iv2TmSparkChart = renderSparkline('iv2-lock-tm-spark', iv2TmSparkChart, iv2TmSparkBuffer, '#be123c');
            iv2TxSparkChart = renderSparkline('iv2-lock-tx-spark', iv2TxSparkChart, iv2TxSparkBuffer, '#7c3aed');
        }

        function fetchActiveDashboardView() {
            if (currentView === 'v2') fetchInstanceDashboardV2();
        }
        // instLink 클릭 핸들러(이 IIFE 밖, DB 트리 초기화 코드)가 최초 DB 자동선택/전환 직후 바로
        // 불러 쓰기 위한 훅(오케스트레이터 실측, 2026-09-18) - 안 그러면 v2는 다음 10초 폴링
        // 틱까지 기다려야 실제 데이터가 뜬다.
        window.fetchActiveDashboardView = fetchActiveDashboardView;

        function setView(view) {
            currentView = view;
            legacyView.style.display = view === 'legacy' ? '' : 'none';
            v2View.style.display = view === 'v2' ? '' : 'none';
            switchBtns.forEach(b => b.classList.toggle('active', b.getAttribute('data-iv2-view') === view));
            try { localStorage.setItem('dbagent.dashboardView', view); } catch (e) { /* private mode 등 - 무시 */ }

            // fetchDashboard()(레거시 2초 폴러)가 이 값을 보고 자기 자신을 쉬게 한다 - 기존 대시보드
            // 코드는 건드리지 않고, 신규 뷰가 보이는 동안만 레거시 폴링을 멈춰 같은 인스턴스에 두 폴러가
            // 동시에 부하를 주지 않도록 한다.
            window.dbagentActiveDashboardSubView = view;
            if (view === 'legacy') fetchDashboard();

            if (iv2PollingTimer) { clearInterval(iv2PollingTimer); iv2PollingTimer = null; }
            if (view === 'v2') {
                fetchInstanceDashboardV2();
                iv2PollingTimer = setInterval(fetchInstanceDashboardV2, 10000);
            }
        }

        switchBtns.forEach(btn => {
            btn.addEventListener('click', () => setView(btn.getAttribute('data-iv2-view')));
        });

        document.querySelectorAll('#iv2-range-switch .iv-range-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                iv2Range = btn.getAttribute('data-range');
                document.querySelectorAll('#iv2-range-switch .iv-range-btn').forEach(b => b.classList.toggle('active', b === btn));
                fetchMetricHistoryV2();
            });
        });

        function iv2RelativeTime(epochMs) {
            const diffSec = Math.max(0, Math.round((Date.now() - epochMs) / 1000));
            if (diffSec < 60) return '방금 전';
            if (diffSec < 3600) return Math.floor(diffSec / 60) + '분 전';
            return Math.floor(diffSec / 3600) + '시간 전';
        }

        function iv2FormatBytes(bytes) {
            if (!bytes) return '0G';
            return (bytes / (1024 * 1024 * 1024)).toFixed(1) + 'G';
        }

        // 6개 v2 위젯 fetch가 fetchInstanceDashboardV2()의 Promise.all로 묶여있어, 그 중 하나가 응답
        // 없이 멈추면 iv2FetchInFlightDbIds 가드가 영원히 안 풀려 그 DB 전체 화면이 먹통된다(오케스트
        // 레이터 실측, 2026-09-22: activeAlerts가 특정 인스턴스에서 pending 상태로 무한 대기). 백엔드
        // 쪽 쿼리에 타임아웃을 걸었지만(MonitorService.getActiveAlerts), 브라우저 fetch()엔 원래
        // 타임아웃이 없어서 이중 방어로 8초(10초 폴링 주기보다 짧게)에서 끊는다.
        function iv2Fetch(url) {
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), 8000);
            return fetch(url, { signal: controller.signal }).finally(() => clearTimeout(timer));
        }

        // fetchInstanceOverviewV2()와 fetchActiveSessionV2()가 둘 다 iv2-session-db-name을 그리는데,
        // 각자 자기 완료 시점에 딱 한 번만 그리고 끝나면 둘 중 먼저 끝난 쪽 기준으로 그 사이클이
        // 굳어버린다 - instance_overview가 하위 쿼리 9개라 session보다 항상 느려서(오케스트레이터
        // 실측, 2026-09-22: DB 전환해도 라벨이 다음 10초 폴링까지 안 뜸), session이 먼저 끝나 아직 안
        // 채워진 값으로 빈 라벨을 그린 뒤, instance_overview가 나중에 값을 채워도 재렌더링할 계기가
        // 없었다. 둘 다 완료 시점에 이 함수를 불러 "지금 window.currentDbId 기준 최신값"으로 다시
        // 그리게 하면, 둘 중 나중에 끝나는 쪽이 항상 최종 렌더를 맡아 그 사이클 안에 수렴한다.
        function iv2RenderSessionDbNameLabel() {
            const dbNameEl = document.getElementById('iv2-session-db-name');
            if (!dbNameEl) return;
            const dbId = window.currentDbId || '';
            const liveName = (iv2LastInstanceNameDbId === dbId) ? iv2LastInstanceName : '';
            dbNameEl.textContent = liveName ? `(${liveName})` : '';
        }

        async function fetchInstanceOverviewV2() {
            const dbId = window.currentDbId || '';
            const res = await iv2Fetch(`/api/instance_overview?db_id=${dbId}&token=${encodeURIComponent(getToken())}`);
            if (!res.ok) return;
            const data = await res.json();
            if (data.error) return;

            const nameEl = document.getElementById('iv2-instance-name');
            if (nameEl) nameEl.textContent = data.instanceName || '--';
            // Active Session 목록 라벨(fetchActiveSessionV2)이 공유하는 라이브 인스턴스명 - 세션 유무와
            // 무관하게 매 폴링마다 조회되는 이 API가 유일하게 신뢰 가능한 소스다(오케스트레이터 실측,
            // 2026-09-22: /api/session은 ACTIVE 세션이 0건이면 응답 배열이 비어서 db_name을 못 읽고
            // dbId로 도로 폴백했었다).
            iv2LastInstanceName = data.instanceName || '';
            iv2LastInstanceNameDbId = dbId;
            iv2RenderSessionDbNameLabel();
            const statusBadge = document.getElementById('iv2-status-badge');
            if (statusBadge) {
                statusBadge.textContent = data.status || '--';
                statusBadge.classList.toggle('online', data.status === '정상 운영');
                statusBadge.classList.toggle('offline', data.status !== '정상 운영');
            }
            const tagsEl = document.getElementById('iv2-tags');
            if (tagsEl) {
                const version = data.dbVersion ? `Oracle ${data.versionCodename || data.dbVersion}` : '--';
                tagsEl.innerHTML = [
                    version,
                    data.topology || '--',
                    `가동시간 ${Math.floor(data.uptimeDays || 0)}일`,
                    `SGA ${iv2FormatBytes(data.sgaBytes)} / PGA ${iv2FormatBytes(data.pgaBytes)}`
                ].map(t => `<span>${t}</span>`).join('');
            }

            const setText = (id, text) => { const el = document.getElementById(id); if (el) el.textContent = text; };
            setText('iv2-kpi-cpu', `${data.cpuPct ?? '--'}%`);
            setText('iv2-kpi-mem', `${data.memPct ?? '--'}%`);
            setText('iv2-kpi-sessions', `${data.activeSessions ?? '--'} / ${data.maxSessions ?? '--'}`);
            setText('iv2-kpi-aas', `${data.dbTimeAas ?? '--'}`);
            setText('iv2-kpi-tps', `${data.tps ?? '--'} 건/초`);
            setText('iv2-kpi-hitratio', `${data.bufferCacheHitRatio ?? '--'}%`);

            // CPU/메모리/버퍼캐시 히트율 타일 밑 얇은 진행률 막대(오케스트레이터 요청, 2026-09-18).
            const setBar = (id, pct, tone) => {
                const el = document.getElementById(id);
                if (!el) return;
                el.style.width = `${Math.max(0, Math.min(100, pct || 0))}%`;
                el.classList.remove('warning', 'danger');
                if (tone) el.classList.add(tone);
            };
            setBar('iv2-kpi-cpu-bar', data.cpuPct, data.cpuPct >= 90 ? 'danger' : data.cpuPct >= 80 ? 'warning' : null);
            setBar('iv2-kpi-mem-bar', data.memPct, data.memPct >= 90 ? 'danger' : data.memPct >= 80 ? 'warning' : null);
            // 히트율은 낮을수록 나쁨 - 클래식 대시보드/구 헬스스코어와 같은 기준(80% 미만 위험, 90% 미만 주의).
            const hitRatio = data.bufferCacheHitRatio;
            setBar('iv2-kpi-hitratio-bar', hitRatio, (hitRatio ?? 100) < 80 ? 'danger' : (hitRatio ?? 100) < 90 ? 'warning' : null);
        }

        // 이벤트별로 다른 색(오케스트레이터 요청, 2026-09-18) - buildSessionRowsHtml()의 세션 대기
        // 유형별 색상표와 같은 팔레트를 재사용해 앱 전체에서 색 의미가 일관되게 한다.
        const IV2_WAIT_EVENT_COLORS = ['#3987e5', '#d95926', '#22d3ee', '#2ecc71', '#7c3aed', '#be123c', '#808000'];

        async function fetchTopWaitEventsV2() {
            const dbId = window.currentDbId || '';
            const res = await iv2Fetch(`/api/top_events?db_id=${dbId}&token=${encodeURIComponent(getToken())}`);
            if (!res.ok) return;
            const events = await res.json();
            if (!Array.isArray(events)) return;
            const top5 = events.slice(0, 5);
            const ctx = document.getElementById('iv2-wait-events-chart');
            if (!ctx) return;
            const labels = top5.map(e => e.event);
            const values = top5.map(e => e.count);
            const colors = labels.map((_, i) => IV2_WAIT_EVENT_COLORS[i % IV2_WAIT_EVENT_COLORS.length]);
            if (!iv2WaitEventsChart) {
                iv2WaitEventsChart = new Chart(ctx, {
                    type: 'bar',
                    // maxBarThickness(오케스트레이터 요청, 2026-09-18) - 기본값(flex)은 막대 개수에 따라
                    // 두께가 자동으로 늘었다 줄었다 해서 1개만 있을 때 유독 두껍게 보였는데, 고정값인
                    // barThickness를 쓰면 반대로 이벤트가 5개로 늘었을 때 150px 프레임에 안 맞아 막대가
                    // 겹치고 라벨도 잘리는 버그가 있었다(오케스트레이터 재지적, 2026-09-18). 상한만 두는
                    // maxBarThickness면 적을 땐 18px로 고정되고, 많을 땐 프레임에 맞게 자동으로 얇아진다.
                    data: { labels, datasets: [{ label: '세션 수', data: values, backgroundColor: colors, maxBarThickness: 18 }] },
                    options: {
                        indexAxis: 'y',
                        responsive: true,
                        maintainAspectRatio: false,
                        animation: { duration: 0 },
                        plugins: { legend: { display: false } },
                        scales: {
                            x: { beginAtZero: true, ticks: { precision: 0, color: chartLineColor(0.75) } },
                            // autoSkip:false로 5개까지는 항상 전부 표시(기존엔 겹침 때문에 3개만 보이던 버그).
                            y: { ticks: { autoSkip: false, color: chartLineColor(0.85), font: { size: 11 } } }
                        }
                    }
                });
            } else {
                iv2WaitEventsChart.data.labels = labels;
                iv2WaitEventsChart.data.datasets[0].data = values;
                iv2WaitEventsChart.data.datasets[0].backgroundColor = colors;
                iv2WaitEventsChart.update();
            }
        }

        // 기존 Lock 추이 그래프가 있던 칸에 넣는 압축형 Active Session 목록(오케스트레이터 요청,
        // 2026-09-18) - 클래식/v3 하단의 16열 테이블은 이 좁은 칸엔 안 맞아서, 알림 리스트와 같은
        // .iv2-alert-list 카드형 스타일을 재사용한다. 그래야 확인 필요 알림과 max-height/스크롤이
        // 같아 단차 없이 나란히 붙는다. 클릭하면 다른 세션 테이블들과 동일하게 전역
        // .clickable-session-row 델리게이트(app.js 하단)가 session-detail.html 팝업을 띄운다.
        async function fetchActiveSessionV2() {
            const dbId = window.currentDbId || '';
            const res = await iv2Fetch(`/api/session?db_id=${dbId}&token=${encodeURIComponent(getToken())}`);
            const listEl = document.getElementById('iv2-session-list');
            if (!listEl) return;
            if (!res.ok) return;
            const rows = await res.json();
            // 지금 이 목록이 실제로 어느 DB 것인지 눈으로 바로 확인하기 위한 표시 - 실제 렌더링은
            // iv2RenderSessionDbNameLabel() 공유 함수가 한다(위 선언부 주석 참고). 여기서도 한 번 더
            // 불러주는 이유: fetchInstanceOverviewV2()가 이미 먼저 끝나 값을 채워놨을 수도 있는데, 그때
            // 라벨을 그릴 계기가 이 fetch뿐이었을 수 있어서다(반대로 이 fetch가 먼저 끝나면 아직 값이
            // 없어 빈 라벨로 그려졌다가, fetchInstanceOverviewV2()가 끝나며 다시 불러 올바른 값으로
            // 덮어쓴다 - 두 fetch 중 나중에 끝나는 쪽이 항상 최종 렌더를 맡는다).
            iv2RenderSessionDbNameLabel();
            const sessions = Array.isArray(rows)
                ? rows.filter(s => s && s.status && s.status.trim().toUpperCase() === 'ACTIVE')
                : [];
            if (!sessions.length) {
                listEl.innerHTML = '<li class="iv2-alert-item">ACTIVE 상태인 세션이 없습니다.</li>';
                return;
            }
            listEl.innerHTML = sessions.map(s => `
                <li class="iv2-alert-item clickable-session-row" style="cursor:pointer;" data-sid="${s.sid}" data-serial="${s.serial || ''}" data-sql_id="${s.sql_id || ''}">
                    <span style="font-family:monospace; color:var(--primary); flex-shrink:0;">SID ${s.sid}</span>
                    <span style="flex-shrink:0; color:var(--text-muted);">${s.duration_time ?? '-'}초</span>
                    <span style="flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${(s.sql_text || '').replace(/"/g, '&quot;')}">${s.sql_text || s.event_name || '-'}</span>
                </li>
            `).join('');
        }

        async function fetchActiveAlertsV2() {
            const dbId = window.currentDbId || '';
            const res = await iv2Fetch(`/api/active_alerts?db_id=${dbId}&token=${encodeURIComponent(getToken())}`);
            const listEl = document.getElementById('iv2-alert-list');
            if (!res.ok) return;
            const alerts = await res.json();
            iv2LastAlerts = Array.isArray(alerts) ? alerts : [];
            const countEl = document.getElementById('iv2-alert-count');
            if (countEl) countEl.textContent = `${iv2LastAlerts.length}건`;
            iv2UpdateLockCurrentBoxes();
            if (!listEl) return;
            if (iv2LastAlerts.length === 0) {
                listEl.innerHTML = '<li class="iv2-alert-item info">확인이 필요한 알림이 없습니다.</li>';
                return;
            }
            listEl.innerHTML = iv2LastAlerts.map(a => `
                <li class="iv2-alert-item ${a.severity}">
                    <span>${a.message}</span>
                    <span class="iv2-alert-time">${iv2RelativeTime(a.occurredAt)}</span>
                </li>
            `).join('');
        }

        async function fetchMetricHistoryV2() {
            const dbId = window.currentDbId || '';
            const res = await iv2Fetch(`/api/metric_history?db_id=${dbId}&range=${iv2Range}&token=${encodeURIComponent(getToken())}`);
            if (!res.ok) return;
            const data = await res.json();
            if (data.error) return;

            const toPoints = (series) => (series || []).map(p => ({ x: p.sampledAt, y: p.value }));

            const cpuCtx = document.getElementById('iv2-cpu-dbtime-chart');
            if (cpuCtx) {
                const cpuPoints = toPoints(data.cpu_pct);
                const aasPoints = toPoints(data.db_time_aas);
                if (!iv2CpuDbTimeChart) {
                    iv2CpuDbTimeChart = new Chart(cpuCtx, {
                        type: 'line',
                        data: {
                            datasets: [
                                // 색은 원래 배색으로 되돌림(오케스트레이터 요청, 2026-09-18 재수정) - Current
                                // Session 팔레트 준용은 Rewind. CPU%는 파랑, DB Time(AAS)은 주황.
                                { label: 'CPU %', data: cpuPoints, borderColor: '#3987e5', backgroundColor: 'rgba(57,135,229,0.1)', borderWidth: 1.5, pointRadius: 0, fill: true, tension: 0.2 },
                                { label: 'DB Time (AAS)', data: aasPoints, borderColor: '#d95926', backgroundColor: 'rgba(217,89,38,0.1)', borderWidth: 1.5, pointRadius: 0, fill: true, tension: 0.2, yAxisID: 'y1' }
                            ]
                        },
                        options: {
                            responsive: true,
                            maintainAspectRatio: false,
                            animation: { duration: 0 },
                            plugins: { legend: { labels: { color: chartLineColor(0.85) } } },
                            scales: {
                                // 24시간제로 5분 단위 눈금만 표시(오케스트레이터 요청, 2026-09-18) -
                                // 실시간(1시간) 범위 기준 12개 눈금(=60분/5분).
                                x: { type: 'time', time: { unit: 'minute', stepSize: 5, displayFormats: { minute: 'HH:mm' } }, ticks: { color: chartLineColor(0.75) } },
                                y: { beginAtZero: true, position: 'left', ticks: { color: chartLineColor(0.75) } },
                                y1: { beginAtZero: true, position: 'right', grid: { drawOnChartArea: false }, ticks: { color: chartLineColor(0.75) } }
                            }
                        }
                    });
                } else {
                    iv2CpuDbTimeChart.data.datasets[0].data = cpuPoints;
                    iv2CpuDbTimeChart.data.datasets[1].data = aasPoints;
                    iv2CpuDbTimeChart.update();
                }
            }

            const lockCtx = document.getElementById('iv2-lock-trend-chart');
            if (lockCtx) {
                const tmPoints = toPoints(data.tm_lock_waiting);
                const txPoints = toPoints(data.tx_lock_waiting);
                // 현재 TM/TX 대기 카드 값은 여기(추이 마지막 포인트, 최대 metric-sample-interval-seconds
                // 만큼 지연)가 아니라 fetchIncidentGateV2()의 /api/failure_prob 실시간 값으로 채운다
                // (오케스트레이터 지적, 2026-09-18) - 이 추이 차트 자체는 그대로 유지.
                if (!iv2LockTrendChart) {
                    iv2LockTrendChart = new Chart(lockCtx, {
                        type: 'line',
                        data: {
                            datasets: [
                                // 색은 Current Session 그래프의 TM LOCK/TX LOCK과 동일(오케스트레이터
                                // 요청, 2026-09-18) - 현재값 카드 스파크라인(iv2PushLockSpark)과도 통일.
                                { label: 'TM Lock 대기', data: tmPoints, borderColor: '#be123c', backgroundColor: 'rgba(190,18,60,0.1)', borderWidth: 1.5, pointRadius: 0, fill: true, tension: 0.2, stepped: true },
                                { label: 'TX Lock 대기', data: txPoints, borderColor: '#7c3aed', backgroundColor: 'rgba(124,58,237,0.1)', borderWidth: 1.5, pointRadius: 0, fill: true, tension: 0.2, stepped: true }
                            ]
                        },
                        options: {
                            responsive: true,
                            maintainAspectRatio: false,
                            animation: { duration: 0 },
                            plugins: { legend: { labels: { color: chartLineColor(0.85) } } },
                            scales: {
                                // 24시간제로 5분 단위 눈금만 표시(오케스트레이터 요청, 2026-09-18) -
                                // 실시간(1시간) 범위 기준 12개 눈금(=60분/5분).
                                x: { type: 'time', time: { unit: 'minute', stepSize: 5, displayFormats: { minute: 'HH:mm' } }, ticks: { color: chartLineColor(0.75) } },
                                y: { beginAtZero: true, ticks: { precision: 0, color: chartLineColor(0.75) } }
                            }
                        }
                    });
                } else {
                    iv2LockTrendChart.data.datasets[0].data = tmPoints;
                    iv2LockTrendChart.data.datasets[1].data = txPoints;
                    iv2LockTrendChart.update();
                }
            }
        }

        // 클래식 대시보드의 "장애발생 가능성" 카드에 숨어있던 장애조치 버튼(사용자 요청, 2026-09-16)을
        // 신규 뷰에도 넣는다 - 표시 조건(failure_prob 기반 percentage>=80 & 관리자)과 클릭 시 동작(TM
        // Lock holder만 자동 kill, TX는 절대 대상 아님)을 그대로 재사용한다. 레거시 코드(dashToggleBtn
        // 근처의 fetchLocks/incidentBtn)는 건드리지 않고 이 IIFE 안에서 동일 로직을 독립적으로 둔다 -
        // 신규 뷰는 처음부터 레거시와 분리된 채로 만들어 왔으므로 같은 원칙을 유지.
        function iv2FailurePercentage(count) {
            if (count === 1) return 30;
            if (count === 2) return 40;
            if (count === 3) return 50;
            if (count === 4) return 60;
            if (count === 5) return 70;
            if (count === 6) return 80;
            if (count >= 7) return 90;
            return 0;
        }

        async function fetchIncidentGateV2() {
            const btn = document.getElementById('iv2-incident-action-btn');
            if (!btn) return;
            const dbId = window.currentDbId || '';
            try {
                const res = await iv2Fetch(`/api/failure_prob?db_id=${dbId}&token=${encodeURIComponent(getToken())}`);
                if (!res.ok) { btn.style.display = 'none'; return; }
                const data = await res.json();
                const count = (data && data.count !== undefined) ? data.count : 0;
                // 장애조치 버튼과 같은 응답의 실시간 TM/TX 대기 건수 - "현재 TM/TX Lock 대기" 카드도
                // 이걸로 채운다(오케스트레이터 지적, 2026-09-18: 버튼은 실시간인데 카드 숫자는 최대
                // 60초 지연된 추이 샘플이라 서로 어긋났음).
                iv2LastTmVal = (data && data.tmLockWaiting !== undefined) ? data.tmLockWaiting : 0;
                iv2LastTxVal = (data && data.txLockWaiting !== undefined) ? data.txLockWaiting : 0;
                iv2PushLockSpark(iv2LastTmVal, iv2LastTxVal);
                iv2UpdateLockCurrentBoxes();
                const percentage = iv2FailurePercentage(count);
                btn.style.display = (percentage >= 80 && isAdmin()) ? 'block' : 'none';
            } catch (e) {
                btn.style.display = 'none';
            }
        }

        document.getElementById('iv2-incident-action-btn')?.addEventListener('click', async () => {
            const dbId = window.currentDbId || '';
            try {
                const tmResponse = await fetch(`/api/tmlock?db_id=${dbId}&token=${encodeURIComponent(getToken())}`);
                const tmData = await tmResponse.json();
                if (!tmResponse.ok || !tmData || tmData.error) {
                    alert('TM Lock 정보를 가져오지 못했습니다.');
                    return;
                }
                const holders = tmData
                    .filter(h => h.sid != null && h.serial != null && h.lock_type === 'TM')
                    .map(h => ({ sid: h.sid, serial: h.serial }));
                if (holders.length === 0) {
                    alert('현재 Kill할 TM Lock Holder 세션이 없습니다. (TX 락은 자동조치 대상이 아닙니다)');
                    return;
                }
                if (!confirm(`Blocking 중인 TM Lock Holder 세션 ${holders.length}건을 즉시 Kill 하시겠습니까?`)) return;

                const res = await fetch(`/api/kill_session?db_id=${dbId}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ sessions: holders, token: sessionStorage.getItem('dbagent_token') })
                });
                const data = await res.json();
                if (data.error) {
                    alert(`장애조치 중 오류: ${data.error}`);
                    return;
                }
                let successCount = 0, failCount = 0;
                data.results.forEach(r => (r.status === 'killed' ? successCount++ : failCount++));
                alert(`장애조치 완료:\n성공: ${successCount}건\n실패: ${failCount}건`);
                fetchInstanceDashboardV2();
            } catch (e) {
                alert(`장애조치 처리 중 오류가 발생했습니다: ${e.message}`);
            }
        });

        async function fetchInstanceDashboardV2() {
            const dashboardSection = document.getElementById('dashboard');
            if (!dashboardSection || !dashboardSection.classList.contains('active') || currentView !== 'v2') return;
            // DB가 아직 선택되기 전(페이지 막 로드된 시점)이면 db_id=""로 나가는 낭비성 요청을 막는다
            // (오케스트레이터 실측, 2026-09-18: 로그인 직후 이 상태로 6개 API가 동시에 나가 각각
            // 커넥션 타임아웃만큼 헛돌고, 다음 10초 폴링까지 기다려야 실제 데이터가 떴음).
            const dbId = window.currentDbId;
            if (!dbId) return;
            if (iv2FetchInFlightDbIds.has(dbId)) return;
            iv2FetchInFlightDbIds.add(dbId);
            try {
                await Promise.all([
                    fetchInstanceOverviewV2(),
                    fetchTopWaitEventsV2(),
                    fetchActiveAlertsV2(),
                    fetchMetricHistoryV2(),
                    fetchIncidentGateV2(),
                    fetchActiveSessionV2()
                ]);
            } finally {
                iv2FetchInFlightDbIds.delete(dbId);
            }
        }

        let initialView = 'legacy';
        try {
            const saved = localStorage.getItem('dbagent.dashboardView');
            if (saved === 'v2') initialView = saved;
        } catch (e) { /* ignore */ }
        setView(initialView);

        // 전환 스위치는 .content-section 바깥(항상 뷰포트 기준 우측하단)에 두었기 때문에, DASHBOARD 탭이
        // 활성화된 동안에만 보이도록 표시 여부를 직접 관리해야 한다 - .content-section.active의 CSS
        // display 토글에 더 이상 얹혀갈 수 없다(사용자 실측 버그 수정, 2026-09-16).
        const iv2SwitchEl = document.getElementById('iv2-view-switch');
        function syncIv2SwitchVisibility() {
            const dashboardSection = document.getElementById('dashboard');
            if (iv2SwitchEl) {
                iv2SwitchEl.style.display = (dashboardSection && dashboardSection.classList.contains('active')) ? '' : 'none';
            }
        }
        syncIv2SwitchVisibility();
        document.querySelectorAll('.nav-item[data-target]').forEach(nav => {
            nav.addEventListener('click', () => {
                syncIv2SwitchVisibility();
                if (nav.getAttribute('data-target') === 'dashboard') setTimeout(fetchActiveDashboardView, 50);
            });
        });
    })();

    // Dashboard Tabs
    const dashTabBtns = document.querySelectorAll('.dash-tab-btn');
    dashTabBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            dashTabBtns.forEach(b => {
                b.classList.remove('active');
                b.style.borderBottom = 'none';
                b.style.color = 'var(--text-muted)';
                b.style.fontWeight = '500';
            });
            
            btn.classList.add('active');
            btn.style.borderBottom = '2px solid #3987e5';
            btn.style.color = '#3987e5';
            btn.style.fontWeight = '600';
            
            const targetId = btn.getAttribute('data-dash-tab');
            document.querySelectorAll('.dash-tab-content').forEach(content => {
                content.style.display = 'none';
            });
            document.getElementById(targetId).style.display = 'block';
            
            fetchDashboard(); // Fetch immediately on tab switch
        });
    });

    // Session Kill Logic
    async function killSessions(checkboxClass) {
        const checkboxes = document.querySelectorAll(`.${checkboxClass}:checked`);
        if (checkboxes.length === 0) {
            alert('Kill 할 세션을 선택하세요.');
            return;
        }
        
        if (!confirm(`선택한 ${checkboxes.length}개의 세션을 Kill 하시겠습니까?`)) {
            return;
        }
        
        const sessionsToKill = Array.from(checkboxes).map(cb => ({
            sid: cb.getAttribute('data-sid'),
            serial: cb.getAttribute('data-serial')
        }));
        
        try {
            const res = await fetch(`/api/kill_session?db_id=${window.currentDbId || ""}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessions: sessionsToKill, token: sessionStorage.getItem('dbagent_token') })
            });
            const data = await res.json();
            if (data.error) {
                alert(`에러 발생: ${data.error}`);
            } else {
                // data.results의 개별 세션 status를 확인하지 않고 무조건 "성공적으로 전송되었습니다"만
                // 띄우던 버그 수정 (2026-08-30 실사용 테스트로 발견) - 예를 들어 세션이 인터럽트 불가능한
                // PL/SQL 호출 중이어서 ORA-00031(session marked for kill)이 나도 사용자에게는 성공으로
                // 보였음. TM Lock 탭(tmlockKillBtn)/장애조치 버튼(dash-incident-action-btn)과 동일하게
                // 성공/실패 건수를 세어서 보여주도록 통일.
                let successCount = 0, failCount = 0;
                (data.results || []).forEach(r => (r.status === 'killed' ? successCount++ : failCount++));
                alert(`처리 결과:\n성공: ${successCount}건\n실패: ${failCount}건`);
                if (checkboxClass === 'session-checkbox') {
                    const btn = document.getElementById('session-refresh-btn');
                    if (btn) btn.click();
                } else {
                    fetchDashboard();
                }
            }
        } catch (e) {
            alert(`요청 실패: ${e}`);
        }
    }
    
    document.getElementById('dash-kill-btn')?.addEventListener('click', () => killSessions('dash-sess-checkbox'));
    document.getElementById('session-kill-btn')?.addEventListener('click', () => killSessions('session-checkbox'));

    document.getElementById('dash-incident-action-btn')?.addEventListener('click', async () => {
        const dbId = window.currentDbId || "";
        try {
            const tmResponse = await fetch(`/api/tmlock?db_id=${dbId}&token=${encodeURIComponent(getToken())}`);
            const tmData = await tmResponse.json();
            if (!tmResponse.ok || !tmData || tmData.error) {
                alert('TM Lock 정보를 가져오지 못했습니다.');
                return;
            }

            // TX(행 잠금) holder는 자동 kill 대상에서 절대 제외한다: 정상적인 트랜잭션 진행 중
            // 잠깐의 행 경합일 뿐인 경우가 대부분이라, 장애발생 가능성(failure_prob)도 TM 락
            // blocking만으로 계산된다 - 그 기준과 동일하게 TM 락 holder만 자동조치 대상으로 삼는다.
            const holders = tmData
                .filter(h => h.sid != null && h.serial != null && h.lock_type === 'TM')
                .map(h => ({ sid: h.sid, serial: h.serial }));

            if (holders.length === 0) {
                alert('현재 Kill할 TM Lock Holder 세션이 없습니다. (TX 락은 자동조치 대상이 아닙니다)');
                return;
            }

            if (!confirm(`Blocking 중인 TM Lock Holder 세션 ${holders.length}건을 즉시 Kill 하시겠습니까?`)) {
                return;
            }

            const res = await fetch(`/api/kill_session?db_id=${dbId}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessions: holders, token: sessionStorage.getItem('dbagent_token') })
            });
            const data = await res.json();
            if (data.error) {
                alert(`장애조치 중 오류: ${data.error}`);
                return;
            }

            let successCount = 0, failCount = 0;
            data.results.forEach(r => (r.status === 'killed' ? successCount++ : failCount++));
            alert(`장애조치 완료:\n성공: ${successCount}건\n실패: ${failCount}건`);
            fetchDashboard();
        } catch (e) {
            alert(`장애조치 처리 중 오류가 발생했습니다: ${e.message}`);
        }
    });
    
    document.getElementById('dash-select-all-sess')?.addEventListener('change', (e) => {
        document.querySelectorAll('.dash-sess-checkbox').forEach(cb => cb.checked = e.target.checked);
    });

    document.getElementById('session-select-all')?.addEventListener('change', (e) => {
        document.querySelectorAll('.session-checkbox').forEach(cb => cb.checked = e.target.checked);
    });

    // ---- 선택 세션 Kill 버튼 활성/비활성 (2026-09-06 사용자 요청) --------------------------------
    //
    // 예전에는 버튼이 항상 눌렸고, 아무것도 안 고른 채 누르면 "Kill할 세션을 선택해주세요" 경고만
    // 떴다 - 누를 수 있다는 것 자체가 잘못된 신호였다. 고른 게 있을 때만 활성화하고, 몇 건이
    // 지워지는지 옆에 표시한다(RDB 화면의 Lock/세션 탭과 같은 동작).
    //
    // <b>표는 자동 갱신마다 tbody 를 통째로 다시 그린다</b> - 그리는 시점에 개별 체크박스에
    // 리스너를 달면 갱신될 때마다 사라지고, 새 행에는 안 붙는다. 그래서 document 레벨 위임으로
    // 한 번만 걸고, 갱신 직후에도 다시 계산되도록 아래에서 폴링 없이 change 이벤트를 받는다.
    // 이 화면의 Kill 버튼은 셋이다 - DASHBOARD 탭의 Active Session 목록, Current Session 메뉴,
    // Lock Holder/Waiter Tree. 셋 다 같은 규칙으로 동작해야 한다(2026-09-06 사용자 지적으로 대시보드
    // 것이 빠져 있던 것을 보완했던 것과 같은 이유).
    const KILL_PANES = [
        { box: 'dash-sess-checkbox', btn: 'dash-kill-btn',    count: 'dash-selected-count',    all: 'dash-select-all-sess' },
        { box: 'session-checkbox',   btn: 'session-kill-btn', count: 'session-selected-count', all: 'session-select-all'   },
        { box: 'tmlock-checkbox',    btn: 'tmlock-kill-btn',  count: 'tmlock-selected-count',  all: 'tmlock-select-all'    }
    ];
    function syncKillButtons() {
        KILL_PANES.forEach(p => {
            const btn = document.getElementById(p.btn);
            if (!btn) return;
            const n = document.querySelectorAll('.' + p.box + ':checked').length;
            btn.disabled = (n === 0);
            const label = document.getElementById(p.count);
            if (label) label.innerHTML = n > 0 ? '선택 <b>' + n + '</b>건' : '';
            // 전체 선택 체크박스도 실제 상태에 맞춘다(개별 해제 시 풀리도록).
            const all = document.getElementById(p.all);
            if (all) {
                const total = document.querySelectorAll('.' + p.box).length;
                all.checked = total > 0 && n === total;
            }
        });
    }
    window.dbagentSyncKillButtons = syncKillButtons;   // 표를 다시 그린 쪽에서 호출한다
    document.addEventListener('change', (e) => {
        if (!e.target || !e.target.classList) return;
        const hit = KILL_PANES.some(p =>
            e.target.classList.contains(p.box) || e.target.id === p.all);
        if (hit) syncKillButtons();
    });

    // Modal Global Listeners
    const modal = document.getElementById('image-modal');
    const closeBtn = document.querySelector('.close-modal');

    if (modal && closeBtn) {
        closeBtn.addEventListener('click', () => {
            modal.style.display = 'none';
        });

        window.addEventListener('click', (event) => {
            if (event.target === modal) {
                modal.style.display = 'none';
            }
        });

        window.addEventListener('keydown', (event) => {
            if (event.key === 'Escape' && modal.style.display === 'block') {
                modal.style.display = 'none';
            }
        });
    }

    // --- Table Info Popup ---
    // 인페이지 모달(#table-info-modal) 대신 실제 팝업창으로 변경 (사용자 요청, 2026-08-29) -
    // session-detail.html과 같은 패턴: table-info.html이 URL 쿼리스트링(db_id/table_name)과
    // sessionStorage 토큰만으로 스스로 /api/table_info를 조회/렌더링한다. 트리 카드 클릭
    // (onclick="window.showTableInfoModal(...)")과 ERD SVG 클릭 핸들러 양쪽에서 이 함수 이름을
    // 그대로 참조하고 있어 함수명은 유지하고 내부 구현만 팝업으로 교체.
    // 위 #image-modal(closeBtn) 존재 여부와는 무관한 별도 기능이라, 그 if 블록 밖에서 항상 등록한다
    // (안에 있으면 #image-modal이 없는 페이지/향후 정리에서 이 함수들도 같이 사라지는 버그가 됨).
    window.showTableInfoModal = function(tableName) {
        if (!tableName) return;
        const url = `table-info.html?db_id=${encodeURIComponent(window.currentDbId || '')}&table_name=${encodeURIComponent(tableName)}`;
        const popup = window.open(url, `dbagent_table_info_${tableName}`, 'width=900,height=720,resizable=yes,scrollbars=yes');
        if (popup) popup.focus();
    };

    // --- Tablespace Datafiles Popup ---
    // 테이블스페이스 조회 결과에서 테이블스페이스명 클릭 시 해당 테이블스페이스에 할당된 데이터파일과
    // 파일별 사용량을 보여주는 팝업 (사용자 요청, 2026-08-29) - table-info.html과 동일한 패턴.
    window.showTablespaceDatafiles = function(tablespaceName) {
        if (!tablespaceName) return;
        const url = `tablespace-datafiles.html?db_id=${encodeURIComponent(window.currentDbId || '')}&tablespace_name=${encodeURIComponent(tablespaceName)}`;
        const popup = window.open(url, `dbagent_ts_datafiles_${tablespaceName}`, 'width=1000,height=600,resizable=yes,scrollbars=yes');
        if (popup) popup.focus();
    };


// Global delegate for clickable session rows - opens the session detail (SQL/Plan/Bind) in a
// separate real browser window (window.open) rather than an in-page modal, so it's a native OS
// window the user can drag to a second monitor and keep open side-by-side with the dashboard.
// See session-detail.html for the popup's own fetch/render logic (it has its own document, so it
// can't share this page's JS scope).
document.addEventListener('click', (e) => {
    const row = e.target.closest('.clickable-session-row');
    if (row && !e.target.closest('input[type="checkbox"]')) {
        const sid = row.getAttribute('data-sid');
        const serial = row.getAttribute('data-serial') || '';
        const sql_id = row.getAttribute('data-sql_id') || '';
        if (!sid && !sql_id) return;

        const url = `session-detail.html?db_id=${encodeURIComponent(window.currentDbId || '')}&sid=${encodeURIComponent(sid || '')}&serial=${encodeURIComponent(serial)}&sql_id=${encodeURIComponent(sql_id)}`;
        // Window name keyed on sid/sql_id: re-clicking the same row focuses/reloads its existing
        // popup instead of spawning a duplicate, while different sessions each get their own window.
        const popup = window.open(url, `dbagent_session_detail_${sid || sql_id}`, 'width=640,height=720,resizable=yes,scrollbars=yes');
        if (popup) popup.focus();
    }
});

// Receiving end of the "Tuning" button in the session-detail.html popup (called via window.opener).
// 2026-09-12 매뉴통합.md 2-1: 목적지가 SQL 정합성/튜닝 화면에서 AI DBA > AI SQL Tunner > AI Current
// SQL 분석 탭으로 바뀌었다 - 같은 handoff 메커니즘에 목적지만 바꾼 것. AIX는 sLLM 서버가 없지만
// 이 탭의 기능(1차 성능점검, 바인드 불러오기, sqlrestapi 기반 성능분석)은 원본과 동일하게 동작한다.
window.openSqlTuningFromPopup = function(sqlText, hashValue, binds, plan) {
    // If this popup was opened by clicking a session inside the Trace-drag "선택된 세션 리스트" modal
    // (see showSelectedSessionsPopup), that modal is still open behind the popup - switching the main
    // window's menu here without closing it first leaves its fixed full-screen overlay sitting on top
    // of the new 화면, making the page look unresponsive/disabled.
    const selectionModal = document.getElementById('image-modal');
    if (selectionModal) selectionModal.style.display = 'none';

    const tuningInputEl = document.getElementById('tunner-current-input');
    const tuningHashEl = document.getElementById('tunner-current-bind-hashvalue');
    if (tuningInputEl) tuningInputEl.value = sqlText || '';
    if (tuningHashEl) tuningHashEl.value = (hashValue != null) ? String(hashValue) : '';

    tunnerCurrentBindValues = {};
    (binds || []).forEach(b => {
        if (!b.name) return;
        const name = b.name.startsWith(':') ? b.name.substring(1) : b.name;
        tunnerCurrentBindValues[name] = b.value || '';
    });

    const navItem = document.querySelector('.nav-item[data-target="aidba"]');
    if (navItem) navItem.click();
    // 트리 메뉴로 바뀐 뒤로는 Current SQL 탭 버튼 클릭 한 번이 Tunner 화면 전환까지 함께 처리한다.
    const currentTabBtn = document.querySelector('.tunner-tab-btn[data-tunner-tab="tab-tunner-current"]');
    if (currentTabBtn) currentTabBtn.click();
    if (typeof window.renderTunnerCurrentBindFields === 'function') window.renderTunnerCurrentBindFields();

    // 세션상세 팝업은 이미 실측 Plan(v$sql_plan)을 들고 있으므로, 1차점검을 다시 실행하지 않아도
    // 바로 "성능분석"을 누를 수 있는 상태로 채워 넣는다(매뉴통합.md 2-1 - Plan handoff 확장).
    if (plan) {
        tunnerCurrentLastPlan = plan;
        const analyzeBtn = document.getElementById('tunner-current-analyze-btn');
        if (analyzeBtn) analyzeBtn.disabled = false;
        renderTunnerCurrentPlan(plan);
    }

    if (tuningInputEl) tuningInputEl.focus();
};


// .app-container has CSS `zoom: 80%` (index.html, 90% before 2026-09-25), which makes getBoundingClientRect()/clientX
// report real screen pixels (post-zoom) while the selection box's own left/top and Chart.js's pixel
// space are both interpreted in the container's local (pre-zoom) pixels. Mixing the two spaces is why
// the drag box/selection used to land away from the actual cursor - convert screen px to local px here.
function scatterPointerToLocal(e, container) {
    const rect = container.getBoundingClientRect();
    const scaleX = rect.width ? container.clientWidth / rect.width : 1;
    const scaleY = rect.height ? container.clientHeight / rect.height : 1;
    const x = Math.max(0, Math.min((e.clientX - rect.left) * scaleX, container.clientWidth));
    const y = Math.max(0, Math.min((e.clientY - rect.top) * scaleY, container.clientHeight));
    return { x, y };
}

// Drag-select on the Top SQL Activity Timeline (설계문서 §8.4, 4단계 - 2026-09-22, 기존 Trace 산점도의
// drag-brush를 대체). 세로 밴드로 시간 구간만 선택한다(값 축은 무관 - 위 History 탭 산점도의 2D 박스
// 선택과 다른 이유는 이 차트가 누적 영역이라 y값이 "그 시점의 합계"이지 세션 하나하나의 좌표가 아니라서).
// §0/체크리스트 검토 결과 기존 /api/history_sessions는 재사용하지 않기로 함 - getHistorySessions()는
// "성능 이력 조회" 화면 전용으로 elapsed>=3초 AND exec_count>=100 튜닝 후보 필터가 걸려 있어, 일반
// 드래그 드릴다운(§8.4 의도)에 쓰면 대부분의 정상적인 드래그가 "결과 없음"으로 나온다 - 그래서 같은
// 필터 없이 구간 내 세션을 그대로 보여주는 /api/ash_session_detail을 새로 추가했다(getAshSessionDetail).
(function initAshTopSqlBrush() {
    const container = document.getElementById('ash-topsql-container');
    const selectionBox = document.getElementById('ash-topsql-selection-box');
    let isDragging = false;
    let startX = 0;

    if (!container || !selectionBox || window.isAshTopSqlBrushBound) return;
    window.isAshTopSqlBrushBound = true;

    container.addEventListener('mousedown', (e) => {
        if (e.target.id !== 'ash-topsql-chart') return;
        isDragging = true;
        const p = scatterPointerToLocal(e, container);
        startX = p.x;
        selectionBox.style.left = startX + 'px';
        selectionBox.style.top = '0px';
        selectionBox.style.width = '0px';
        selectionBox.style.height = '100%';
        selectionBox.style.display = 'block';
    });

    window.addEventListener('mousemove', (e) => {
        if (!isDragging) return;
        const p = scatterPointerToLocal(e, container);
        const left = Math.min(startX, p.x);
        selectionBox.style.left = left + 'px';
        selectionBox.style.width = Math.abs(p.x - startX) + 'px';
    });

    window.addEventListener('mouseup', async (e) => {
        if (!isDragging) return;
        isDragging = false;
        selectionBox.style.display = 'none';
        if (!ashTopSqlChart) return;

        const p = scatterPointerToLocal(e, container);
        const endX = p.x;
        if (Math.abs(endX - startX) < 5) return; // 클릭과 구분 - 최소 드래그 폭

        try {
            const xAxis = ashTopSqlChart.scales.x;
            const val1 = xAxis.getValueForPixel(Math.min(startX, endX));
            const val2 = xAxis.getValueForPixel(Math.max(startX, endX));
            if (val1 == null || val2 == null) return;
            // §9.1: Other 드릴다운이 열려 있으면 새 드래그 구간 기준으로 같이 갱신(패널이 열려 있는
            // 동안은 패널이 캔버스를 덮어 새로 드래그할 수 없으므로, 실제로는 "드래그 후 Other를 열면
            // 이미 이 구간 기준"이 되는 경로로 동작한다).
            ashSelectedWindow = { start: new Date(val1), end: new Date(val2) };
            if (ashOtherDrilldownOpen) refreshAshOtherDrilldown();
            await fetchAshSessionDetailForDrag(new Date(val1), new Date(val2));
        } catch (err) {
            console.error('Top SQL brush selection error', err);
        }
    });
})();

function formatAshDateTimeParam(date) {
    const pad = (n) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

// 드래그로 뽑은 시간 구간의 실제 세션 목록을 조회해 기존 산점도-드래그와 같은 팝업(showSelectedSessions
// Popup/session-list.html)으로 보여준다 - /api/ash_session_detail이 이미 session-list.html이 기대하는
// 필드명(sid/serial/sql_id/capture_time/duration_time/program_name/username/db_name)으로 내려주므로
// 별도 매핑이 필요 없다(command/osuser는 ASH에 없는 정보라 비워두면 팝업이 '-'로 표시).
async function fetchAshSessionDetailForDrag(startDate, endDate) {
    if (!window.currentDbId) return;
    if (endDate.getTime() - startDate.getTime() < 60000) {
        // 드래그 폭이 1분 미만이면 버킷 하나도 안 걸릴 수 있어 최소 1분 폭을 보장.
        endDate = new Date(startDate.getTime() + 60000);
    }
    const startParam = formatAshDateTimeParam(startDate);
    const endParam = formatAshDateTimeParam(endDate);
    try {
        const res = await fetch(`/api/ash_session_detail?db_id=${window.currentDbId}&start_time=${encodeURIComponent(startParam)}&end_time=${encodeURIComponent(endParam)}&token=${encodeURIComponent(getToken())}`);
        const data = await res.json();
        if (!res.ok || data.error) {
            console.error('ash_session_detail 조회 실패:', data && data.error);
            return;
        }
        if (data.length > 0) {
            showSelectedSessionsPopup(data);
        } else {
            alert('선택한 구간(' + startParam.replace('T', ' ') + ' ~ ' + endParam.replace('T', ' ') + ')에 해당하는 세션이 없습니다.');
        }
    } catch (err) {
        console.error('Failed to fetch ash_session_detail:', err);
    }
}

// Drag-selecting on the Trace scatter (or the History tab's scatter) used to render this list into
// the shared #image-modal in-page. Moved to a real OS window (session-list.html) so it can be dragged
// to a second monitor the same way session-detail.html already can. localStorage (not sessionStorage -
// see session-list.html's comment) carries the selection over, with a 'storage' event on the popup
// side so re-dragging a new selection updates the already-open window instead of showing stale data.
function showSelectedSessionsPopup(sessions) {
    try {
        localStorage.setItem('dbagent_selected_sessions', JSON.stringify({ sessions, ts: Date.now() }));
    } catch (e) {
        console.error('Failed to stash selected sessions for the list popup', e);
        return;
    }
    const popup = window.open('session-list.html', 'dbagent_selected_sessions', 'width=1000,height=600,resizable=yes,scrollbars=yes');
    if (popup) popup.focus();
}

let historyScatterChart = null;
let historyDataCache = [];

    const endInput = document.getElementById('history-end-time');
    const startInput = document.getElementById('history-start-time');
    if (endInput && startInput) {
        const now = new Date();
        const oneHourAgo = new Date(now.getTime() - 60 * 60 * 1000);
        const formatDateTime = (date) => {
            const pad = (n) => n.toString().padStart(2, '0');
            return `${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
        };
        endInput.value = formatDateTime(now);
        startInput.value = formatDateTime(oneHourAgo);
    }

    const historySearchBtn = document.getElementById('history-search-btn');
    if (historySearchBtn) {
        historySearchBtn.addEventListener('click', async () => {
            const startTime = document.getElementById('history-start-time').value;
            const endTime = document.getElementById('history-end-time').value;
            const userSelect = document.getElementById('history-users');
            const selectedUsers = userSelect ? Array.from(userSelect.selectedOptions).map(o => o.value).join(',') : '';
            const machineSelect = document.getElementById('history-machines');
            const selectedMachines = machineSelect ? Array.from(machineSelect.selectedOptions).map(o => o.value).join(',') : '';
            const targetDb = window.currentDbId || "";

            if (!startTime || !endTime) {
                alert("시작 시간과 종료 시간을 모두 입력해주세요.");
                return;
            }
            
            const tbody = document.getElementById('history-tbody');
            tbody.innerHTML = '';

            const loadingOverlay = document.getElementById('history-loading-overlay');
            if (loadingOverlay) loadingOverlay.style.display = 'flex';

            try {
                const response = await fetch(`/api/history_sessions?db_id=${encodeURIComponent(targetDb)}&start_time=${encodeURIComponent(startTime)}&end_time=${encodeURIComponent(endTime)}&users=${encodeURIComponent(selectedUsers)}&machines=${encodeURIComponent(selectedMachines)}&token=${encodeURIComponent(getToken())}`);
                const data = await response.json();

                if (data.error) {
                    alert('오류 발생: ' + data.error);
                    tbody.innerHTML = '<tr><td colspan="10" style="text-align:center; padding: 30px;">조회 중 오류가 발생했습니다.</td></tr>';
                    return;
                }

                historyDataCache = data;
                updateHistoryUI(data);

            } catch (error) {
                console.error('Error fetching history:', error);
                tbody.innerHTML = '<tr><td colspan="10" style="text-align:center; padding: 30px;">서버와의 통신에 실패했습니다.</td></tr>';
            } finally {
                if (loadingOverlay) loadingOverlay.style.display = 'none';
            }
        });
    }
    
    // 2. Setup drag-to-select for history chart
    const historyContainer = document.getElementById('history-scatter-container');
    const historySelectionBox = document.getElementById('history-scatter-selection-box');
    let hIsDragging = false;
    let hStartX, hStartY;
    
    if (historyContainer && historySelectionBox) {
        historyContainer.addEventListener('mousedown', (e) => {
            if (e.target.id !== 'history-scatter-chart') return;
            hIsDragging = true;
            const p = scatterPointerToLocal(e, historyContainer);
            hStartX = p.x;
            hStartY = p.y;

            historySelectionBox.style.left = hStartX + 'px';
            historySelectionBox.style.top = hStartY + 'px';
            historySelectionBox.style.width = '0px';
            historySelectionBox.style.height = '0px';
            historySelectionBox.style.display = 'block';
        });

        window.addEventListener('mousemove', (e) => {
            if (!hIsDragging) return;
            const p = scatterPointerToLocal(e, historyContainer);
            const currentX = p.x;
            const currentY = p.y;

            const left = Math.min(hStartX, currentX);
            const top = Math.min(hStartY, currentY);
            const width = Math.abs(currentX - hStartX);
            const height = Math.abs(currentY - hStartY);
            
            historySelectionBox.style.left = left + 'px';
            historySelectionBox.style.top = top + 'px';
            historySelectionBox.style.width = width + 'px';
            historySelectionBox.style.height = height + 'px';
        });
        
        window.addEventListener('mouseup', (e) => {
            if (!hIsDragging) return;
            hIsDragging = false;
            historySelectionBox.style.display = 'none';

            const p = scatterPointerToLocal(e, historyContainer);
            const endX = p.x;
            const endY = p.y;

            const left = Math.min(hStartX, endX);
            const right = Math.max(hStartX, endX);
            const top = Math.min(hStartY, endY);
            const bottom = Math.max(hStartY, endY);
            
            if (Math.abs(right - left) < 5 && Math.abs(bottom - top) < 5) return;
            
            if (!historyScatterChart) return;
            
            const xAxis = historyScatterChart.scales.x;
            const yAxis = historyScatterChart.scales.y;
            
            const valLeft = xAxis.getValueForPixel(left);
            const valRight = xAxis.getValueForPixel(right);
            const valTop = yAxis.getValueForPixel(top);
            const valBottom = yAxis.getValueForPixel(bottom);
            
            const xMin = Math.min(valLeft, valRight);
            const xMax = Math.max(valLeft, valRight);
            const yMin = Math.min(valTop, valBottom);
            const yMax = Math.max(valTop, valBottom);
            
            const selectedPoints = historyDataCache.filter(point => {
                const pDate = new Date(point.capture_time).getTime();
                return pDate >= xMin && pDate <= xMax &&
                       point.duration_time >= yMin && point.duration_time <= yMax;
            });
            
            if (selectedPoints.length > 0) {
                showSelectedSessionsPopup(selectedPoints);
            }
        });
    }

function updateHistoryUI(data) {
    const scatterCtx = document.getElementById('history-scatter-chart');
    if (!scatterCtx) return;
    
    const chartData = data.map(s => ({
        x: new Date(s.capture_time).getTime(),
        y: s.duration_time,
        raw: s
    }));
    
    if (historyScatterChart) {
        historyScatterChart.data.datasets[0].data = chartData;
        historyScatterChart.update();
    } else {
        historyScatterChart = new Chart(scatterCtx, {
            type: 'scatter',
            data: {
                datasets: [{
                    label: 'Active Sessions (History)',
                    data: chartData,
                    backgroundColor: '#ffcc00',
                    borderColor: '#ffcc00',
                    borderWidth: 2,
                    pointRadius: 2,
                    pointHoverRadius: 5,
                    pointStyle: 'star'
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: false,
                scales: {
                    x: {
                        type: 'time',
                        time: {
                            displayFormats: {
                                millisecond: 'HH:mm:ss',
                                second: 'HH:mm:ss',
                                minute: 'HH:mm',
                                hour: 'HH:mm'
                            },
                            tooltipFormat: 'yyyy-MM-dd HH:mm:ss'
                        },
                        title: { display: true, text: 'Sample Time' },
                        grid: { color: chartLineColor(0.05) }
                    },
                    y: {
                        beginAtZero: true,
                        title: { display: true, text: 'Duration (sec)' },
                        grid: { color: chartLineColor(0.05) }
                    }
                },
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        callbacks: {
                            label: (context) => {
                                const p = context.raw.raw;
                                return `SID:${p.sid} | Dur:${p.duration_time}s | SQL:${p.sql_id||'None'}`;
                            }
                        }
                    }
                }
            }
        });
    }
    
    const tbody = document.getElementById('history-tbody');
    if (data.length === 0) {
        tbody.innerHTML = '<tr><td colspan="10" style="text-align:center; padding: 30px;">해당 시간 범위에 데이터가 없습니다.</td></tr>';
        return;
    }
    
    tbody.innerHTML = data.map(s => `
        <tr class="clickable-session-row" style="cursor:pointer;" data-sid="${s.sid}" data-serial="${s.serial || ''}" data-sql_id="${s.sql_id || ''}">
            <td>${s.capture_time}</td>
            <td>${s.sid}</td>
            <td>${s.serial}</td>
            <td style="color:var(--danger); font-weight:bold;">${s.duration_time}s</td>
            <td>${s.event_name}</td>
            <td style="color:var(--text-info); text-decoration:underline;">${s.sql_id || '-'}</td>
            <td>${s.plan_hash_value || '-'}</td>
            <td>${s.program_name || '-'}</td>
            <td>${s.osuser || '-'}</td>
        </tr>
    `).join('');
}


        // Top 100 History Logic
        const historyTopSearchBtn = document.getElementById('history-top-search-btn');
        if (historyTopSearchBtn) {
            historyTopSearchBtn.addEventListener('click', async () => {
                const startTime = document.getElementById('history-top-start-time').value;
                const endTime = document.getElementById('history-top-end-time').value;
                const userSelect = document.getElementById('history-top-users');
                const selectedUsers = userSelect ? Array.from(userSelect.selectedOptions).map(o => o.value).join(',') : '';
                const targetDb = window.currentDbId || "";
                
                if (!startTime || !endTime) {
                    alert("시작 시간과 종료 시간을 모두 입력해주세요.");
                    return;
                }
                
                const tbody = document.getElementById('history-top-tbody');
                tbody.innerHTML = '<tr><td colspan="8" style="text-align:center; padding: 30px;">조회 중입니다... (과거 이력 조회 시 최대 수십 초가 소요될 수 있습니다)</td></tr>';
                
                try {
                    const response = await fetch(`/api/history_top_sessions?db_id=${encodeURIComponent(targetDb)}&start_time=${encodeURIComponent(startTime)}&end_time=${encodeURIComponent(endTime)}&users=${encodeURIComponent(selectedUsers)}&token=${encodeURIComponent(getToken())}`);
                    const data = await response.json();
                    
                    if (data.error) {
                        alert('오류 발생: ' + data.error);
                        tbody.innerHTML = '<tr><td colspan="8" style="text-align:center; padding: 30px;">조회 중 오류가 발생했습니다.</td></tr>';
                        return;
                    }
                    
                    if (!data || data.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="8" style="text-align:center; padding: 30px;">해당 기간에 5초 이상 수행된 악성 세션이 없습니다.</td></tr>';
                        return;
                    }
                    
                    historyTopDataCache = data;
                    renderTopHistory(historyTopDataCache);
                } catch (error) {
                    console.error('Error fetching top history:', error);
                    tbody.innerHTML = '<tr><td colspan="8" style="text-align:center; padding: 30px;">데이터를 불러오는 중 오류가 발생했습니다.</td></tr>';
                }
            });
            
            // Set default time to last 1 hour
            const now = new Date();
            const oneHourAgo = new Date(now.getTime() - 60 * 60 * 1000);
            
            const formatForInput = (date) => {
                const pad = (n) => n.toString().padStart(2, '0');
                return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
            };
            
            document.getElementById('history-top-start-time').value = formatForInput(oneHourAgo);
            document.getElementById('history-top-end-time').value = formatForInput(now);
        }


    // Sorting logic
    let historyTopDataCache = [];
    let currentSort = { col: null, asc: true };
    
    function sortData(data, col, asc) {
        return data.sort((a, b) => {
            let valA = a[col];
            let valB = b[col];
            
            if (valA == null) valA = '';
            if (valB == null) valB = '';
            
            if (typeof valA === 'string') valA = valA.toLowerCase();
            if (typeof valB === 'string') valB = valB.toLowerCase();
            
            if (valA < valB) return asc ? -1 : 1;
            if (valA > valB) return asc ? 1 : -1;
            return 0;
        });
    }

    function renderTopHistory(data) {
        const tbody = document.getElementById('history-top-tbody');
        tbody.innerHTML = '';
        if (data.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" style="text-align:center; padding: 30px;">데이터가 없습니다.</td></tr>';
            return;
        }
        
        data.forEach(session => {
            const tr = document.createElement('tr');
            tr.style.cursor = 'pointer';
            tr.innerHTML = `
                <td>${session.capture_time || ''}</td>
                <td><span class="badge badge-primary">${session.sql_id || ''}</span></td>
                <td style="color: blue; font-weight: bold;">${session.exec_count || 0}</td>
                <td style="color: red; font-weight: bold;">${session.duration_time || 0}</td>
                <td>${session.event_name || ''}</td>
                <td>${session.program_name || ''}</td>
                <td>${session.osuser || ''}</td>
            `;
            tr.addEventListener('click', () => {
                if (session.sql_id) {
                    showSqlPopup(session.sid, session.sql_id);
                }
            });
            tbody.appendChild(tr);
        });
    }
    
    // Attach sort events
    document.querySelectorAll('#history-top th[data-sort]').forEach(th => {
        th.addEventListener('click', () => {
            const col = th.getAttribute('data-sort');
            currentSort.asc = (currentSort.col === col) ? !currentSort.asc : false; // Default desc for new col
            currentSort.col = col;
            historyTopDataCache = sortData(historyTopDataCache, col, currentSort.asc);
            renderTopHistory(historyTopDataCache);
        });
    });
    
    document.querySelectorAll('#history th[data-sort]').forEach(th => {
        th.addEventListener('click', () => {
            const col = th.getAttribute('data-sort');
            currentSort.asc = (currentSort.col === col) ? !currentSort.asc : false;
            currentSort.col = col;
            historyDataCache = sortData(historyDataCache, col, currentSort.asc);
            updateHistoryUI(historyDataCache);
        });
    });


// Global DB Users loader
let dbUsersLoaded = false;
// 이전 요청이 아직 안 끝났으면 새로 안 쏜다(오케스트레이터 실측, 2026-09-18: 아래 "Aggressive DB
// Users loader"가 1초마다 무조건 재시도하다 보니, DB가 느릴 때 매초 fetch 쌍이 새로 쌓여 - 5초 걸리는
// 상황이면 5쌍이 동시에 같은 커넥션을 다투는 꼴이었다).
let dbUsersLoading = false;
const dbUsersEscapeHtml = (s) => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
async function loadDbUsers(targetDb) {
    if (!targetDb || dbUsersLoading) return;
    dbUsersLoading = true;
    // 사용자 요청(2026-09-14): history_users/history_machines는 서로 독립적인 요청이라 순차 await로
    // 묶을 이유가 없다 - 먼저 둘 다 fetch()만 시작해 두고(요청은 즉시 동시에 나감), 각자의 await/처리는
    // 기존처럼 별도 try/catch로 나눠 에러 처리 독립성은 그대로 유지한다(코드 리뷰 지적, 2026-09-14).
    const usersFetch = fetch(`/api/history_users?db_id=${encodeURIComponent(targetDb)}&token=${encodeURIComponent(getToken())}`);
    const machinesFetch = fetch(`/api/history_machines?db_id=${encodeURIComponent(targetDb)}&token=${encodeURIComponent(getToken())}`);

    try {
        const response = await usersFetch;
        let users = await response.json();

        const hSelect = document.getElementById('history-users');
        const htSelect = document.getElementById('history-top-users');

        if (hSelect) {
            hSelect.style.display = 'inline-block';
            if (Array.isArray(users) && users.length > 0) {
                hSelect.innerHTML = `<option value="">전체 계정(All)</option>` + users.map(u => `<option value="${dbUsersEscapeHtml(u)}">${dbUsersEscapeHtml(u)}</option>`).join('');
            } else {
                hSelect.innerHTML = `<option value="">계정 없음 ` + JSON.stringify(users) + `</option>`;
            }
        }

        if (htSelect) {
            htSelect.style.display = 'inline-block';
            if (Array.isArray(users) && users.length > 0) {
                htSelect.innerHTML = `<option value="">전체 계정(All)</option>` + users.map(u => `<option value="${dbUsersEscapeHtml(u)}">${dbUsersEscapeHtml(u)}</option>`).join('');
            } else {
                htSelect.innerHTML = `<option value="">계정 없음</option>`;
            }
        }
        dbUsersLoaded = true;
    } catch(e) {
        console.error("Failed to load db users", e);
        const hSelect = document.getElementById('history-users');
        if (hSelect) {
            hSelect.innerHTML = `<option value="">Error: ${dbUsersEscapeHtml(e.message)}</option>`;
            hSelect.style.display = 'inline-block';
        }
    }

    // 사용자 요청(2026-09-14): 성능 이력 조회에서 실제 업무(WAS) 세션만 골라 보고 싶다는 요청 -
    // 최근 ASH/AWR에 남아있는 접속 호스트(machine) 목록을 드롭다운으로 제공한다. WAS 서버 목록이
    // databases.json 등 어디에도 설정되어 있지 않아 실측값(dba_hist 최근 7일 + 현재 v$active_
    // session_history)에서 뽑는 방식 - DBA가 자기 WAS 호스트명을 알아서 고르면 된다.
    try {
        const mResponse = await machinesFetch;
        const machines = await mResponse.json();
        const mSelect = document.getElementById('history-machines');
        if (mSelect) {
            mSelect.style.display = 'inline-block';
            if (Array.isArray(machines) && machines.length > 0) {
                mSelect.innerHTML = `<option value="">전체 서버(All)</option>` + machines.map(m => `<option value="${dbUsersEscapeHtml(m)}">${dbUsersEscapeHtml(m)}</option>`).join('');
            } else {
                mSelect.innerHTML = `<option value="">서버 없음</option>`;
            }
        }
    } catch (e) {
        console.error("Failed to load history machines", e);
        const mSelect = document.getElementById('history-machines');
        if (mSelect) {
            mSelect.innerHTML = `<option value="">Error: ${dbUsersEscapeHtml(e.message)}</option>`;
            mSelect.style.display = 'inline-block';
        }
    }
    dbUsersLoading = false;
}

// Hook into db select change (Safe)
const dbSelectElem = document.getElementById('db-select');
if (dbSelectElem) {
    dbSelectElem.addEventListener('change', (e) => {
        dbUsersLoaded = false; // reset when db changes
        if(document.getElementById('history').style.display !== 'none' || document.getElementById('history-top').style.display !== 'none') {
            loadDbUsers(e.target.value);
        }
    });
}

// Hook into navigation
document.querySelectorAll('.sidebar .nav-link').forEach(link => {
    link.addEventListener('click', (e) => {
        const targetId = link.getAttribute('data-target');
        if (targetId === 'history' || targetId === 'history-top') {
            if (window.currentDbId) {
                loadDbUsers(window.currentDbId);
            }
        }
    });
});


// Aggressive DB Users loader
setInterval(() => {
    if (window.currentDbId && (!dbUsersLoaded || (document.getElementById("history-users") && document.getElementById("history-users").options && document.getElementById("history-users").options.length <= 1))) {
        const histDisplay = document.getElementById('history') ? document.getElementById('history').style.display : 'none';
        const topDisplay = document.getElementById('history-top') ? document.getElementById('history-top').style.display : 'none';
        
        // Always try to load if we have a currentDbId, regardless of tab, so it's ready!
        loadDbUsers(window.currentDbId);
    }
}, 1000);

// History table sorting logic
let historySortCol = 'capture_time';
let historySortAsc = true;




    setTimeout(() => {
        document.querySelectorAll('#history-thead th').forEach(th => {
            th.addEventListener('click', () => {
                const sortKey = th.getAttribute('data-sort');
                if (!sortKey) return;
                
                if (historySortCol === sortKey) {
                    historySortAsc = !historySortAsc;
                } else {
                    historySortCol = sortKey;
                    historySortAsc = true;
                }
                
                if (historyDataCache && historyDataCache.length > 0) {
                    historyDataCache.sort((a, b) => {
                        let valA = a[sortKey];
                        let valB = b[sortKey];
                        if (valA == null) valA = '';
                        if (valB == null) valB = '';
                        if (typeof valA === 'string' && typeof valB === 'string') {
                            return historySortAsc ? valA.localeCompare(valB) : valB.localeCompare(valA);
                        }
                        return historySortAsc ? (valA > valB ? 1 : -1) : (valB > valA ? 1 : -1);
                    });
                    
                    // Re-render table only
                    const tbody = document.getElementById('history-tbody');
                    if(tbody) {
                        tbody.innerHTML = historyDataCache.map(s => {
                            return `<tr>
                                <td>${s.capture_time}</td>
                                <td>${s.sid}</td>
                                <td>${s.serial}</td>
                                <td>${s.exec_count}</td>
                                <td>${s.duration_time}</td>
                                <td>${s.event_name}</td>
                                <td>${s.sql_id}</td>
                                <td>${s.plan_hash_value || ''}</td>
                                <td>${s.program_name || ''}</td>
                                <td>${s.osuser || ''}</td>
                            </tr>`;
                        }).join('');
                    }
                }
            });
        });
    }, 1000);

// SQL 정합성/튜닝 Logic
// AIX 이관본: sLLM(FastAPI) 서버가 없어 모델 기반 버튼(분석 실행/실행계획 조회 후 분석/실제 실행 통계로
// 분석)은 서버가 항상 "sLLM 연동 필요" 메시지를 돌려주도록 되어 있음(SqlTuningController 참고) - 이
// 프런트엔드 코드는 원본과 완전히 동일하며 그 메시지를 그대로 표시할 뿐, 특별한 분기 없음. 모델이
// 필요 없는 "1차 성능점검"/바인드 불러오기는 실제로 동작함.

    const sqlTuningInput = document.getElementById('sqltuning-input');
    const sqlTuningBtn = document.getElementById('sqltuning-run-btn');
    const sqlTuningResult = document.getElementById('sqltuning-result');

    // 모델 답변에 줄바꿈(\n)이 있으면 그대로 쓰고, 하나도 없이 한 문단으로 쭉 이어진 경우엔
    // 문장이 끝나는 마침표 뒤마다 줄바꿈을 넣어 가독성을 보완한다. 숫자 뒤 마침표(번호 목록
    // "1. ..." 이나 소수점 "2.5")는 문장 끝이 아니므로 lookbehind로 제외.
    function formatSqlTuningAnswer(text) {
        if (!text) return '';
        if (text.indexOf('\n') !== -1) {
            return text.replace(/\n/g, '<br/>');
        }
        return text.replace(/(?<!\d)\.\s+/g, '.<br/><br/>');
    }

    if (sqlTuningInput && sqlTuningBtn && sqlTuningResult) {
        const runSqlTuning = () => {
            const text = sqlTuningInput.value.trim();
            if (!text || sqlTuningBtn.disabled) return;

            sqlTuningBtn.disabled = true;
            sqlTuningResult.innerHTML = '<div style="display: flex; align-items: center; gap: 8px; color: var(--text-secondary);"><i data-lucide="loader-2" class="spinning"></i> 모델이 분석 중입니다 (최대 1분 정도 소요될 수 있습니다)...</div>';
            if (typeof lucide !== 'undefined') lucide.createIcons({root: sqlTuningResult});

            fetch(`/api/sqltuning/analyze`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ prompt: text })
            })
            .then(res => res.json())
            .then(data => {
                sqlTuningBtn.disabled = false;
                if (data.success === false) {
                    sqlTuningResult.innerHTML = `<div style="color: #d03b3b;">${data.message || '분석 중 오류가 발생했습니다.'}</div>`;
                    return;
                }
                const formatted = formatSqlTuningAnswer(data.answer);
                sqlTuningResult.innerHTML = `<div style="line-height: 1.6;">${formatted}</div>`;
            })
            .catch(() => {
                sqlTuningBtn.disabled = false;
                sqlTuningResult.innerHTML = '<div style="color: #d03b3b;">서버 통신 오류가 발생했습니다. (SQL 튜닝 모델 서버가 켜져 있는지 확인하세요)</div>';
            });
        };

        sqlTuningBtn.addEventListener('click', runSqlTuning);
        sqlTuningInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) runSqlTuning();
        });
    }

    // 화면 클리어 - sLLM 자체 분석 화면은 바인드/계정이 없는 자유 텍스트라 입력/결과만 초기화
    const sqlTuningClearBtn = document.getElementById('sqltuning-clear-btn');
    if (sqlTuningClearBtn && sqlTuningInput && sqlTuningResult) {
        sqlTuningClearBtn.addEventListener('click', () => {
            sqlTuningInput.value = '';
            sqlTuningResult.innerHTML = '<div style="color: var(--text-secondary); text-align: center; margin-top: 30px;">쿼리/실행계획을 입력하고 분석 실행 버튼을 누르세요.</div>';
            sqlTuningInput.focus();
        });
    }

// AI Current SQL 분석 Logic (매뉴통합.md 2-1) - 1차 성능점검(기존 /api/sqltuning/quick_check 재사용) +
// 바인드 변수 패널은 기존 SQL 정합성/튜닝 화면에서 이 탭으로 이동한 것. 1차점검 결과가 있어야만
// "성능분석"(sqlrestapi promptId=current-sql)이 활성화되는 human-in-the-loop 구조.

    const tunnerCurrentInput = document.getElementById('tunner-current-input');
    const tunnerCurrentResult = document.getElementById('tunner-current-result');
    const tunnerCurrentAccountSelect = document.getElementById('tunner-current-account-select');
    const tunnerCurrentBindPanel = document.getElementById('tunner-current-bind-panel');
    const tunnerCurrentBindFields = document.getElementById('tunner-current-bind-fields');
    const tunnerCurrentBindToggleBtn = document.getElementById('tunner-current-bind-toggle-btn');
    const tunnerCurrentBindHashInput = document.getElementById('tunner-current-bind-hashvalue');
    const tunnerCurrentBindCaptureBtn = document.getElementById('tunner-current-bind-capture-btn');
    const tunnerCurrentBindCaptureStatus = document.getElementById('tunner-current-bind-capture-status');
    const tunnerCurrentQuickCheckBtn = document.getElementById('tunner-current-quickcheck-btn');
    const tunnerCurrentAnalyzeBtn = document.getElementById('tunner-current-analyze-btn');
    const tunnerCurrentClearBtn = document.getElementById('tunner-current-clear-btn');
    const TUNNER_CURRENT_BIND_COLLAPSE_THRESHOLD = 6; // 이보다 많으면 기본 접힘 + 펼치기 버튼
    let tunnerCurrentBindValues = {};
    let tunnerCurrentBindExpanded = false;
    // 성능분석은 1차점검으로 실측치를 이미 얻었을 때만 의미가 있다(쿼리 텍스트만 있는 분석은 탭 ①의
    // 몫이라 중복) - 직전 1차점검 결과를 여기 들고 있다가 성능분석 호출 시 그대로 함께 보낸다.
    let tunnerCurrentLastPlan = null;
    // 후속질문(2026-09-15) 컨텍스트 - sqlrestapi는 세션을 기억하지 않으므로(stateless), 이전
    // 분석/문답 내역을 프론트에서 누적해뒀다가 다음 질문을 보낼 때 매번 그대로 다시 실어 보낸다.
    // { question: string|null, answer: string }[] - 최초 분석은 question이 null.
    let tunnerCurrentAnswerHistory = [];
    const escapeTunnerCurrentHtml = (s) => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

    function extractTunnerCurrentBindNames(query) {
        const stripped = query.replace(/'(?:[^']|'')*'|\/\*[\s\S]*?\*\/|--[^\r\n]*/g, (m) => m.charAt(0) === "'" ? "''" : ' ');
        const re = /:([A-Za-z][A-Za-z0-9_$#]*|[0-9]+)/g;
        const seen = new Set();
        const names = [];
        let m;
        while ((m = re.exec(stripped)) !== null) {
            if (!seen.has(m[1])) { seen.add(m[1]); names.push(m[1]); }
        }
        return names;
    }

    function renderTunnerCurrentBindField(name) {
        const val = (tunnerCurrentBindValues[name] || '').replace(/"/g, '&quot;');
        return `<label style="display:flex; align-items:center; gap:4px; font-size:0.85rem; color: var(--text-secondary);">:${name}
            <input type="text" data-bind-name="${name}" value="${val}" style="width: 140px; padding: 4px 6px; border: 1px solid var(--border-color); border-radius: 4px; background: var(--bg-main); color: var(--text-main); font-family: 'Consolas', 'D2Coding', monospace;">
        </label>`;
    }

    window.renderTunnerCurrentBindFields = function () {
        if (!tunnerCurrentBindPanel || !tunnerCurrentBindFields || !tunnerCurrentInput || !isAdmin()) return;
        const names = extractTunnerCurrentBindNames(tunnerCurrentInput.value);
        if (names.length === 0) {
            tunnerCurrentBindPanel.style.display = 'none';
            tunnerCurrentBindFields.innerHTML = '';
            return;
        }
        tunnerCurrentBindPanel.style.display = 'flex';

        const isCollapsible = names.length > TUNNER_CURRENT_BIND_COLLAPSE_THRESHOLD;
        if (tunnerCurrentBindToggleBtn) {
            tunnerCurrentBindToggleBtn.style.display = isCollapsible ? 'inline-block' : 'none';
            tunnerCurrentBindToggleBtn.textContent = `바인드 변수 ${names.length}개 (${tunnerCurrentBindExpanded ? '접기 ▲' : '펼치기 ▼'})`;
        }

        if (isCollapsible && !tunnerCurrentBindExpanded) {
            tunnerCurrentBindFields.style.display = 'none';
            return;
        }

        tunnerCurrentBindFields.style.cssText = isCollapsible
            ? 'display: flex; flex-wrap: wrap; gap: 8px; max-height: 320px; overflow-y: auto; padding: 4px;'
            : 'display: flex; flex-wrap: wrap; gap: 8px;';
        tunnerCurrentBindFields.innerHTML = names.map(renderTunnerCurrentBindField).join('');
        tunnerCurrentBindFields.querySelectorAll('input[data-bind-name]').forEach(inp => {
            inp.addEventListener('input', () => {
                tunnerCurrentBindValues[inp.dataset.bindName] = inp.value;
            });
        });
    }

    if (tunnerCurrentBindToggleBtn) {
        tunnerCurrentBindToggleBtn.addEventListener('click', () => {
            tunnerCurrentBindExpanded = !tunnerCurrentBindExpanded;
            window.renderTunnerCurrentBindFields();
        });
    }

    if (tunnerCurrentBindCaptureBtn && tunnerCurrentBindHashInput) {
        tunnerCurrentBindCaptureBtn.addEventListener('click', async () => {
            const hashValue = tunnerCurrentBindHashInput.value.trim();
            if (!hashValue) return;
            tunnerCurrentBindCaptureBtn.disabled = true;
            if (tunnerCurrentBindCaptureStatus) tunnerCurrentBindCaptureStatus.textContent = '조회 중...';
            try {
                const res = await fetch('/api/sqltuning/bind_capture', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        db_id: window.currentDbId || '',
                        account: tunnerCurrentAccountSelect ? tunnerCurrentAccountSelect.value : '',
                        token: getToken(),
                        hash_value: hashValue
                    })
                });
                const data = await res.json();
                if (!data.success) {
                    if (tunnerCurrentBindCaptureStatus) tunnerCurrentBindCaptureStatus.textContent = data.message || '조회 실패';
                    return;
                }
                Object.assign(tunnerCurrentBindValues, data.binds || {});
                tunnerCurrentBindExpanded = true;
                window.renderTunnerCurrentBindFields();
                if (tunnerCurrentBindCaptureStatus) {
                    tunnerCurrentBindCaptureStatus.textContent = `${Object.keys(data.binds || {}).length}개 값 채움`;
                }
            } catch (e) {
                if (tunnerCurrentBindCaptureStatus) tunnerCurrentBindCaptureStatus.textContent = '서버 통신 오류';
            } finally {
                tunnerCurrentBindCaptureBtn.disabled = false;
            }
        });
    }

    if (tunnerCurrentInput) {
        tunnerCurrentInput.addEventListener('input', () => {
            window.renderTunnerCurrentBindFields();
            // 쿼리를 고치면 방금 전 1차점검 결과와 더 이상 대응하지 않으므로 성능분석을 다시 잠근다.
            tunnerCurrentLastPlan = null;
            tunnerCurrentAnswerHistory = [];
            if (tunnerCurrentAnalyzeBtn) tunnerCurrentAnalyzeBtn.disabled = true;
        });
        // placeholder 가 "(Ctrl+Enter: 1차 성능점검)" 이라고 안내하므로 실제로 동작하게 한다.
        tunnerCurrentInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && tunnerCurrentQuickCheckBtn) {
                e.preventDefault();
                tunnerCurrentQuickCheckBtn.click();
            }
        });
    }

    window.loadTunnerCurrentAccounts = async function () {
        if (!tunnerCurrentAccountSelect) return;
        const dbId = window.currentDbId || '';
        try {
            const res = await fetch(`/api/query/accounts?db_id=${encodeURIComponent(dbId)}&token=${encodeURIComponent(getToken())}`);
            const data = await res.json();
            const accounts = data.accounts || [];
            const previous = tunnerCurrentAccountSelect.value;
            tunnerCurrentAccountSelect.innerHTML = accounts.map(a => `<option value="${a}">${a}</option>`).join('');
            if (accounts.includes(previous)) {
                tunnerCurrentAccountSelect.value = previous;
            }
        } catch (e) {
            console.error('Failed to load AI Current SQL 분석 accounts:', e);
        }
    };

    // 실행계획 본문은 반드시 textContent 로 넣는다 - innerHTML 로 흘리면 쿼리에 들어있는 `a<b` 같은
    // 표현이 태그 시작으로 해석돼 그 뒤 실행계획이 통째로 화면에서 사라진다(DISPLAY_CURSOR 출력에는
    // 쿼리 원문이 그대로 들어있다).
    function renderTunnerCurrentPlan(plan) {
        if (!tunnerCurrentResult) return;
        tunnerCurrentResult.innerHTML = `<div id="tunner-current-plan" style="font-size: 0.85rem; color: var(--text-muted); background: var(--bg-card); padding: 12px; border-radius: 4px; white-space: pre-wrap; font-family: 'Consolas', 'D2Coding', monospace;"></div>
            <div id="tunner-current-analysis"></div>`;
        document.getElementById('tunner-current-plan').textContent = plan || '';
    }

    // 1차 성능점검 - 실행계획/실측 통계를 얻지만 AI 호출 없이 그대로 바로 보여줌
    // (AI 분석 전에 DBA가 눈으로 먼저 훑어보는 용도, 훨씬 빠름). 성공하면 성능분석 버튼이 열린다.
    if (tunnerCurrentQuickCheckBtn && tunnerCurrentInput && tunnerCurrentResult) {
        tunnerCurrentQuickCheckBtn.addEventListener('click', () => {
            const query = tunnerCurrentInput.value.trim();
            if (!query || tunnerCurrentQuickCheckBtn.disabled) return;

            tunnerCurrentQuickCheckBtn.disabled = true;
            if (tunnerCurrentAnalyzeBtn) tunnerCurrentAnalyzeBtn.disabled = true;
            tunnerCurrentLastPlan = null;
            tunnerCurrentAnswerHistory = [];
            tunnerCurrentResult.innerHTML = '<div style="display: flex; align-items: center; gap: 8px; color: var(--text-secondary);"><i data-lucide="loader-2" class="spinning"></i> 쿼리를 실제로 실행 중...</div>';
            if (typeof lucide !== 'undefined') lucide.createIcons({root: tunnerCurrentResult});

            fetch('/api/sqltuning/quick_check', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    db_id: window.currentDbId || '',
                    account: tunnerCurrentAccountSelect ? tunnerCurrentAccountSelect.value : '',
                    token: getToken(),
                    query: query,
                    binds: tunnerCurrentBindValues
                })
            })
            .then(res => res.json())
            .then(data => {
                tunnerCurrentQuickCheckBtn.disabled = false;
                if (data.success === false) {
                    tunnerCurrentResult.innerHTML = `<div style="color: #d03b3b;">${data.message || '점검 중 오류가 발생했습니다.'}</div>`;
                    return;
                }
                tunnerCurrentLastPlan = data.plan;
                if (tunnerCurrentAnalyzeBtn) tunnerCurrentAnalyzeBtn.disabled = false;
                renderTunnerCurrentPlan(data.plan);
            })
            .catch(() => {
                tunnerCurrentQuickCheckBtn.disabled = false;
                tunnerCurrentResult.innerHTML = '<div style="color: #d03b3b;">서버 통신 오류가 발생했습니다.</div>';
            });
        });
    }

    // 성능분석 - 1차점검 결과(쿼리+바인드+실행계획/실측치)를 sqlrestapi(promptId=current-sql)로 보내
    // 해석을 요청한다. RAG 검색 없이 바로 답변만 받는다(사내 사례를 찾는 게 아니라 눈앞의 실측치를
    // 해석하는 작업이라 - 매뉴통합.md 2-1).
    if (tunnerCurrentAnalyzeBtn) {
        tunnerCurrentAnalyzeBtn.addEventListener('click', () => {
            if (tunnerCurrentAnalyzeBtn.disabled || !tunnerCurrentLastPlan) return;
            const query = tunnerCurrentInput.value.trim();
            const analysisEl = document.getElementById('tunner-current-analysis');
            if (!analysisEl) return;

            tunnerCurrentAnalyzeBtn.disabled = true;
            tunnerCurrentAnswerHistory = []; // 새로 분석을 돌리면 이전 후속질문 맥락은 폐기
            analysisEl.innerHTML = '<div style="display: flex; align-items: center; gap: 8px; color: var(--text-secondary); margin-top: 16px;"><i data-lucide="loader-2" class="spinning"></i> AI가 실측치를 분석 중입니다...</div>';
            if (typeof lucide !== 'undefined') lucide.createIcons({root: analysisEl});

            fetch('/api/aidba/current_sql/analyze', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ query: query, binds: tunnerCurrentBindValues, plan: tunnerCurrentLastPlan })
            })
            .then(res => res.json())
            .then(data => {
                tunnerCurrentAnalyzeBtn.disabled = false;
                if (data.success === false) {
                    analysisEl.innerHTML = `<div style="color: #d03b3b; margin-top: 16px;">${data.message || '분석 중 오류가 발생했습니다.'}</div>`;
                    return;
                }
                // current-sql.md 는 "### 1. 실측 요약" 같은 마크다운 4단 구조를 강제하므로 줄바꿈만
                // 바꾸는 formatSqlTuningAnswer(자체 sLLM 화면용) 로는 ###/``` 가 그대로 보인다.
                const formatted = formatAiMarkdownAnswer(data.answer);
                analysisEl.innerHTML = '';
                const analysisWrapper = document.createElement('div');
                analysisWrapper.style.cssText = 'line-height: 1.6; margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--border-color);';
                analysisEl.appendChild(analysisWrapper);
                tunnerCurrentAnswerHistory = [{ question: null, answer: data.answer }];
                typeHtmlInto(analysisWrapper, formatted, { onComplete: () => renderTunnerCurrentFollowup(analysisEl) });
            })
            .catch(() => {
                tunnerCurrentAnalyzeBtn.disabled = false;
                analysisEl.innerHTML = '<div style="color: #d03b3b; margin-top: 16px;">서버 통신 오류가 발생했습니다.</div>';
            });
        });
    }

    // 화면 클리어
    if (tunnerCurrentClearBtn && tunnerCurrentInput && tunnerCurrentResult) {
        tunnerCurrentClearBtn.addEventListener('click', () => {
            tunnerCurrentInput.value = '';
            tunnerCurrentBindValues = {};
            tunnerCurrentBindExpanded = false;
            tunnerCurrentLastPlan = null;
            tunnerCurrentAnswerHistory = [];
            if (tunnerCurrentAnalyzeBtn) tunnerCurrentAnalyzeBtn.disabled = true;
            if (tunnerCurrentBindHashInput) tunnerCurrentBindHashInput.value = '';
            if (tunnerCurrentBindCaptureStatus) tunnerCurrentBindCaptureStatus.textContent = '';
            window.renderTunnerCurrentBindFields();
            tunnerCurrentResult.innerHTML = '<div style="text-align: center; margin-top: 20px;"><div style="display: inline-flex; align-items: center; justify-content: center; width: 56px; height: 56px; border-radius: 16px; background: var(--accent-2-soft); margin-bottom: 12px;"><i data-lucide="message-square-text" style="width: 26px; height: 26px; color: var(--accent-2);"></i></div><div style="color: var(--text-secondary); line-height: 1.7;">쿼리를 입력하고 "1차 성능점검"을 실행하면 실행계획/실측 통계가 여기에 표시됩니다.<br>2차로 "성능분석"을 실행하면 해당쿼리의 성능 분석 및 튜닝을 지원합니다.</div></div>';
            if (typeof lucide !== 'undefined') lucide.createIcons({root: tunnerCurrentResult});
            tunnerCurrentInput.focus();
        });
    }

    // 후속질문(2026-09-15): 최초 성능분석이 끝나면 결과 아래에 "추가 질문" 입력을 붙여, 같은 쿼리/
    // 실행계획 맥락에서 AI에게 더 물어볼 수 있게 한다. sqlrestapi는 세션이 없으므로(stateless), 매
    // 질문마다 지금까지의 분석/문답 내역(tunnerCurrentAnswerHistory)을 그대로 다시 실어 보낸다 - 대화가
    // 길어질수록 프롬프트가 커져 느려지고 결국 길이 제한에 걸릴 수 있지만, 짧은 후속질문 몇 번엔 충분하고
    // sqlrestapi(별도 GPU 서버 프로젝트) 쪽 변경 없이 지금 붙일 수 있는 가장 작은 구현이라 우선 이걸로 함.
    function buildTunnerCurrentPreviousContext() {
        return tunnerCurrentAnswerHistory.map(turn => {
            if (turn.question === null) return turn.answer; // 최초 분석 답변
            return `[DBA 추가 질문] ${turn.question}\n[AI 답변] ${turn.answer}`;
        }).join('\n\n');
    }

    function renderTunnerCurrentFollowup(analysisEl) {
        let followupEl = document.getElementById('tunner-current-followup');
        if (!followupEl) {
            followupEl = document.createElement('div');
            followupEl.id = 'tunner-current-followup';
            followupEl.style.cssText = 'margin-top: 20px; padding-top: 16px; border-top: 1px dashed var(--border-color);';
            analysisEl.appendChild(followupEl);
        }
        followupEl.innerHTML = `
            <div style="display: flex; gap: 8px; align-items: flex-start;">
                <textarea id="tunner-current-followup-input" rows="2" placeholder="추가로 궁금한 점을 입력하세요 (예: 인덱스 설계안도 제안해줘)" style="flex: 1; padding: 8px 10px; border: 1px solid var(--border-color); border-radius: 6px; background: var(--bg-card); color: var(--text-main); font-family: inherit; font-size: 0.88rem; resize: vertical;"></textarea>
                <button id="tunner-current-followup-btn" class="primary-btn" style="padding: 0 16px; height: 38px; white-space: nowrap;"><i data-lucide="send"></i> 추가 질문</button>
            </div>
            <div id="tunner-current-followup-status" style="font-size: 0.8rem; color: var(--text-muted); margin-top: 4px;"></div>`;
        if (typeof lucide !== 'undefined') lucide.createIcons({root: followupEl});

        const followupInput = document.getElementById('tunner-current-followup-input');
        const followupBtn = document.getElementById('tunner-current-followup-btn');
        const followupStatus = document.getElementById('tunner-current-followup-status');

        const submitFollowup = () => {
            const question = followupInput.value.trim();
            if (!question || followupBtn.disabled) return;
            followupBtn.disabled = true;
            followupInput.disabled = true;
            followupStatus.textContent = '';

            const turnBlock = document.createElement('div');
            turnBlock.style.cssText = 'margin-bottom: 16px;';
            turnBlock.innerHTML = `<div style="font-weight: 600; color: var(--text-secondary); margin-bottom: 6px;">${escapeTunnerCurrentHtml(question)}</div><div class="tunner-current-followup-answer" style="display: flex; align-items: center; gap: 8px; color: var(--text-secondary);"><i data-lucide="loader-2" class="spinning"></i> AI가 답변을 작성 중입니다...</div>`;
            followupEl.parentNode.insertBefore(turnBlock, followupEl);
            if (typeof lucide !== 'undefined') lucide.createIcons({root: turnBlock});
            turnBlock.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

            fetch('/api/aidba/current_sql/analyze', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    query: tunnerCurrentInput.value.trim(),
                    binds: tunnerCurrentBindValues,
                    plan: tunnerCurrentLastPlan,
                    previousContext: buildTunnerCurrentPreviousContext(),
                    followUpQuestion: question
                })
            })
            .then(res => res.json())
            .then(data => {
                followupBtn.disabled = false;
                followupInput.disabled = false;
                const answerEl = turnBlock.querySelector('.tunner-current-followup-answer');
                if (data.success === false) {
                    answerEl.style.cssText = 'color: #d03b3b;';
                    answerEl.textContent = data.message || '답변 생성 중 오류가 발생했습니다.';
                    return;
                }
                tunnerCurrentAnswerHistory.push({ question: question, answer: data.answer });
                answerEl.style.cssText = 'line-height: 1.6;';
                answerEl.innerHTML = '';
                followupInput.value = '';
                typeHtmlInto(answerEl, formatAiMarkdownAnswer(data.answer));
            })
            .catch(() => {
                followupBtn.disabled = false;
                followupInput.disabled = false;
                const answerEl = turnBlock.querySelector('.tunner-current-followup-answer');
                answerEl.style.cssText = 'color: #d03b3b;';
                answerEl.textContent = '서버 통신 오류가 발생했습니다.';
            });
        };

        followupBtn.addEventListener('click', submitFollowup);
        followupInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                submitFollowup();
            }
        });
    }

// SQL Tune Advisor Logic (사내 튜닝 사례 RAG 검색 + LLM 분석, /api/sqltuneadvisor/query 를 통해
// 폐쇄망 GPU 서버의 sqlrestapi(RAGController)를 호출한다. 그 서버의 System Prompt 가 답변을
// "### 1. 문제점 분석" 처럼 마크다운 4단계 구조로 강제하므로, 여기서는 채팅처럼 줄바꿈만 바꾸는 대신
// 헤더/코드블록/굵게/글머리 정도만 가볍게 렌더링한다(외부 마크다운 라이브러리는 추가하지 않음 - 이
// 프로젝트는 폐쇄망 배포 대상이라 CDN 의존 없이 로컬 vendored 라이브러리만 쓰는 원칙, style.css 참고).
(function initSqlTuneAdvisor() {
    const input = document.getElementById('sqltuneadvisor-input');
    const runBtn = document.getElementById('sqltuneadvisor-run-btn');
    const clearBtn = document.getElementById('sqltuneadvisor-clear-btn');
    const resultEl = document.getElementById('sqltuneadvisor-result');
    if (!input || !runBtn || !clearBtn || !resultEl) return;

    const PLACEHOLDER_HTML = '<div style="text-align: center; margin-top: 20px;"><div style="display: inline-flex; align-items: center; justify-content: center; width: 56px; height: 56px; border-radius: 16px; background: var(--accent-2-soft); margin-bottom: 12px;"><i data-lucide="message-square-text" style="width: 26px; height: 26px; color: var(--accent-2);"></i></div><div style="color: var(--text-secondary); line-height: 1.7;">쿼리나 튜닝하고 싶은 상황을 입력하고 분석을 실행해주세요.</div></div>';

    const escapeHtml = (s) => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

    // System Prompt 가 요구하는 답변 포맷(### 헤더, ```sql 코드블록, **굵게**, - 글머리)만 가볍게
    // HTML로 바꾼다 - 정식 마크다운 파서가 아니라 이 화면에서 실제로 나오는 패턴에 맞춘 간이 변환.
    function formatAdvisorAnswer(text) {
        if (!text) return '';
        // 문단(특히 번호 섹션 1,2,3...) 간격이 너무 넓다는 사용자 지적(2026-09-14). 원인: 헤더/구분선(---)/
        // 글머리를 자체 margin이 있는 <div>로 바꿔도, 그 앞뒤에 낀 마크다운 상의 빈 줄은 그대로 남아
        // 있다가 마지막 \n->br 치환에서 <br/>가 되어 div margin 위에 또 쌓인다. 특히 "---"로 구분되는
        // 절 사이엔 "빈 줄 + --- + 빈 줄"이 한꺼번에 끼어 있어 헤더 하나 지날 때마다 <br/>가 여러 번
        // 겹쳤다. 정규식 치환을 이어 붙이는 대신 줄 단위로 순회하면서, 헤더/구분선/글머리는 서로 인접한
        // <div>로만 쌓는다(인접 블록 요소의 위아래 margin은 브라우저가 알아서 겹쳐 처리한다) - 그 사이에
        // 낀 빈 줄은 버리고, 일반 문단과 문단 사이의 빈 줄만 <br/><br/> 한 번으로 살려 문단 구분을 유지한다.
        // (DBAgent-Java와 동일)
        const bold = (s) => s.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        const inlineCode = (s) => s.replace(/`([^`]+)`/g, '<code style="background: rgba(0,0,0,0.25); padding: 1px 5px; border-radius: 4px; font-family: Consolas, monospace;">$1</code>');
        const inline = (s) => inlineCode(bold(s));

        const lines = String(text).replace(/\r\n/g, '\n').split('\n');
        let html = '';
        let inCode = false;
        let codeBuf = [];
        let lastBlock = true;
        let pendingGap = false;

        const flushCode = () => {
            html += `<pre style="background: rgba(0,0,0,0.3); color: #e2e8f0; padding: 12px 14px; border-radius: 6px; overflow-x: auto; white-space: pre; font-family: 'D2Coding', Consolas, monospace; font-size: 0.85rem; margin: 10px 0; line-height: 1.4;">${codeBuf.join('\n')}</pre>`;
            codeBuf = [];
            lastBlock = true;
            pendingGap = false;
        };

        for (const raw of lines) {
            if (inCode) {
                if (raw.trim() === '```') { inCode = false; flushCode(); }
                else codeBuf.push(escapeHtml(raw));
                continue;
            }
            if (/^```/.test(raw.trim())) { inCode = true; continue; }

            if (raw.trim() === '') {
                pendingGap = true;
                continue;
            }

            const heading = /^#{2,4}\s+(.+)$/.exec(raw);
            if (heading) {
                html += `<div style="margin: 16px 0 8px; font-weight: 700; color: var(--primary); font-size: 1rem;">${inline(escapeHtml(heading[1]))}</div>`;
                lastBlock = true; pendingGap = false;
                continue;
            }

            if (/^-{3,}\s*$/.test(raw.trim())) {
                html += '<div style="border-top: 1px solid var(--border-color); margin: 14px 0;"></div>';
                lastBlock = true; pendingGap = false;
                continue;
            }

            const bullet = /^(\s*)-\s+(.+)$/.exec(raw);
            if (bullet) {
                const nested = bullet[1].length >= 2;
                html += `<div style="margin: 3px 0 3px ${nested ? 28 : 14}px;">• ${inline(escapeHtml(bullet[2]))}</div>`;
                lastBlock = true; pendingGap = false;
                continue;
            }

            if (pendingGap && !lastBlock) html += '<br/><br/>';
            else if (!lastBlock) html += '<br/>';
            html += inline(escapeHtml(raw));
            lastBlock = false; pendingGap = false;
        }
        if (inCode) flushCode();
        return html;
    }

    function formatReferences(references) {
        if (!references || references.length === 0) return '';
        const items = references.map((ref, i) => {
            const label = escapeHtml(ref.source || ('사례 ' + (i + 1)));
            const content = escapeHtml(ref.content || '');
            return `
                <div style="margin-bottom: 8px;">
                    <div style="display: flex; justify-content: space-between; align-items: center; gap: 10px;">
                        <span style="font-size: 0.85rem; color: var(--text-secondary);">${i + 1}. ${label}</span>
                        <span style="font-size: 0.82rem; font-weight: 600; color: var(--primary); cursor: pointer; user-select: none; white-space: nowrap;"
                              onclick="var b=this.parentElement.nextElementSibling; var willOpen=(b.style.display==='none'); b.style.display=willOpen?'block':'none'; this.textContent=willOpen?'[-] 접기':'[+] 상세보기';">[+] 상세보기</span>
                    </div>
                    <div style="display: none; font-size: 0.85rem; line-height: 1.6; color: var(--text-main); background: var(--bg-card); border: 1px solid var(--border-color); padding: 10px 12px; border-radius: 6px; margin-top: 6px; white-space: pre-wrap; word-break: break-word; font-family: 'D2Coding', Consolas, monospace; max-height: 280px; overflow-y: auto;">${content}</div>
                </div>`;
        }).join('');
        return `<div style="margin-top: 20px; padding-top: 14px; border-top: 1px solid var(--border-color);">
                    <div style="font-weight: 700; color: var(--text-secondary); margin-bottom: 10px; font-size: 0.9rem;">참고한 사내 튜닝 사례 (${references.length}건)</div>
                    ${items}
                </div>`;
    }

    const run = () => {
        const text = input.value.trim();
        if (!text || runBtn.disabled) return;

        runBtn.disabled = true;
        resultEl.innerHTML = '<div style="display: flex; align-items: center; gap: 8px; color: var(--text-secondary);"><i data-lucide="loader-2" class="spinning"></i> 사내 튜닝 사례를 검색하고 분석 중입니다...</div>';
        if (typeof lucide !== 'undefined') lucide.createIcons({root: resultEl});

        fetch('/api/sqltuneadvisor/query', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query: text })
        })
        .then(res => res.json())
        .then(data => {
            runBtn.disabled = false;
            if (data.success === false) {
                resultEl.innerHTML = `<div style="color: #d03b3b;">${escapeHtml(data.message || '분석 중 오류가 발생했습니다.')}</div>`;
                return;
            }
            resultEl.innerHTML = `<div style="line-height: 1.6;">${formatAdvisorAnswer(data.answer)}${formatReferences(data.references)}</div>`;
        })
        .catch(() => {
            runBtn.disabled = false;
            resultEl.innerHTML = '<div style="color: #d03b3b;">서버 통신 오류가 발생했습니다. (SQL Tune Advisor 서버가 켜져 있는지 확인하세요)</div>';
        });
    };

    runBtn.addEventListener('click', run);
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) run();
    });
    clearBtn.addEventListener('click', () => {
        input.value = '';
        resultEl.innerHTML = PLACEHOLDER_HTML;
        if (typeof lucide !== 'undefined') lucide.createIcons({root: resultEl});
        input.focus();
    });
})();

// AI DBA 좌측 프레임 화면 전환 + AI SQL Tunner 내부 탭 + Error Search Logic



    // 좌측 프레임 화면 전환 (AI SQL Tunner / AI 챗봇 / AI SQL 작성기 / Regex 오류검색) - 탭이 아니라
    // 화면 자체를 바꾸는 것이므로 아래쪽 강조는 좌측 보더로 표시한다(매뉴통합.md 1절).
    const aidbaSideBtns = document.querySelectorAll('.aidba-side-btn');
    const aidbaViews = document.querySelectorAll('.aidba-view');

    // AI SQL Tunner 하위 트리 (AI SQL 성능분석 / AI Current SQL 분석) - 좌측 프레임에 부모/자식으로
    // 통합됐다(사용자 요청, 2026-09-12). 본문 상단 가로 탭은 없어졌고, 이 버튼들을 누르면 해당 탭
    // 콘텐츠 표시 + Tunner 화면 전환(activateAidbaView)을 함께 한다 - 다른 화면을 보다가 바로 자식
    // 탭을 눌러도 Tunner 화면으로 전환되어야 하기 때문.
    const tunnerTabBtns = document.querySelectorAll('.tunner-tab-btn');
    const tunnerTabContents = document.querySelectorAll('.tunner-tab-content');

    // 자식 버튼의 "선택됨" 강조(글씨색/굵기)는 .active 클래스만으로는 부족하다 - 다른 좌측 항목(AI
    // 챗봇 등)으로 옮겨가도 클래스가 그대로 남아 강조색이 계속 보이는 버그가 있었다(사용자 리포트,
    // 2026-09-12). Tunner 화면이 실제로 보이고 있을 때만 강조를 켠다.
    function refreshTunnerTabStyles() {
        const tunnerBtn = document.querySelector('.aidba-side-btn[data-view="aidba-view-tunner"]');
        const tunnerIsActive = !!(tunnerBtn && tunnerBtn.classList.contains('active'));
        tunnerTabBtns.forEach(b => {
            const isActive = tunnerIsActive && b.classList.contains('active');
            b.style.color = isActive ? 'var(--primary)' : 'var(--text-main)';
            b.style.fontWeight = isActive ? '600' : '500';
        });
    }

    function activateAidbaView(viewId) {
        aidbaSideBtns.forEach(b => {
            b.classList.remove('active');
            b.style.color = 'var(--text-main)';
            b.style.fontWeight = '500';
            b.style.borderLeftColor = 'transparent';
            b.style.background = 'transparent';
        });
        aidbaViews.forEach(v => v.style.display = 'none');

        const activeBtn = document.querySelector(`.aidba-side-btn[data-view="${viewId}"]`);
        if (activeBtn) {
            activeBtn.classList.add('active');
            activeBtn.style.color = 'var(--primary)';
            activeBtn.style.fontWeight = '600';
            activeBtn.style.borderLeftColor = 'var(--primary)';
            activeBtn.style.background = 'var(--bg-main)';
        }
        const view = document.getElementById(viewId);
        if (view) view.style.display = 'flex';
        refreshTunnerTabStyles();
    }

    aidbaSideBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            const viewId = btn.getAttribute('data-view');
            // "AI SQL Tunner" 부모 자체를 누르면(자식이 아니라) 항상 기본 탭(AI Current SQL 분석)으로
            // 들어간다 - 마지막으로 보던 자식을 기억해 뒀다가 엉뚱한 화면으로 복귀하면 사용자가
            // "리턴이 안 된다"고 느낀다(사용자 리포트, 2026-09-12). 기본 탭은 AI Current SQL 분석으로
            // 변경(사용자 요청, 2026-09-15).
            if (viewId === 'aidba-view-tunner') {
                const defaultTab = document.querySelector('.tunner-tab-btn[data-tunner-tab="tab-tunner-current"]');
                if (defaultTab) { defaultTab.click(); return; }
            }
            activateAidbaView(viewId);
        });
    });

    tunnerTabBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            tunnerTabBtns.forEach(b => b.classList.remove('active'));
            tunnerTabContents.forEach(c => c.style.display = 'none');

            btn.classList.add('active');
            const targetId = btn.getAttribute('data-tunner-tab');
            document.getElementById(targetId).style.display = 'block';

            activateAidbaView('aidba-view-tunner');
        });
    });

    // AI DBA 메뉴 진입 시 sqlrestapi 모델명/OpenSearch 상태 1회 조회 (폴링 없음, 매뉴통합.md 3절)
    let aidbaHealthLoaded = false;
    window.loadAiDbaHealth = function () {
        if (aidbaHealthLoaded) return;
        const nameEl = document.getElementById('aidba-model-name');
        const vecEl = document.getElementById('aidba-vectordb-status');
        if (!nameEl) return;
        aidbaHealthLoaded = true;
        fetch('/api/aidba/health')
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    nameEl.textContent = '모델: ' + (data.llm_model || '알 수 없음');
                    if (vecEl && data.vector_db === 'connected') {
                        vecEl.style.display = 'flex';
                    }
                } else {
                    nameEl.textContent = '모델: 확인 불가';
                }
            })
            .catch(() => { nameEl.textContent = '모델: 확인 불가'; });
    };

// AI SQL 작성기 Logic (매뉴통합.md 2-3) - 테이블명 입력 → 조회 → 추가 방식으로 여러 테이블 구조를
// 컨텍스트에 누적한 뒤(조인 쿼리 대응) 자연어 요청으로 SQL을 생성한다. 생성 SQL은 SQL 실행/1차
// 성능점검 화면으로 바로 보낼 수 있다(매뉴통합.md 2-3 - "생성된 SQL은 실행 연동").

    const sqlWriterAccountSelect = document.getElementById('sqlwriter-account-select');
    const sqlWriterTableInput = document.getElementById('sqlwriter-table-input');
    const sqlWriterLookupBtn = document.getElementById('sqlwriter-lookup-btn');
    const sqlWriterLookupStatus = document.getElementById('sqlwriter-lookup-status');
    const sqlWriterOwnerPicker = document.getElementById('sqlwriter-owner-picker');
    const sqlWriterOwnerPickerSelect = document.getElementById('sqlwriter-owner-picker-select');
    const sqlWriterOwnerPickerBtn = document.getElementById('sqlwriter-owner-picker-btn');
    const sqlWriterPreview = document.getElementById('sqlwriter-preview');
    const sqlWriterPreviewBody = document.getElementById('sqlwriter-preview-body');
    const sqlWriterAddBtn = document.getElementById('sqlwriter-add-btn');
    const sqlWriterTableList = document.getElementById('sqlwriter-table-list');
    const sqlWriterTableCount = document.getElementById('sqlwriter-table-count');
    const sqlWriterRequestInput = document.getElementById('sqlwriter-request-input');
    const sqlWriterGenerateBtn = document.getElementById('sqlwriter-generate-btn');
    const sqlWriterClearBtn = document.getElementById('sqlwriter-clear-btn');
    const sqlWriterResult = document.getElementById('sqlwriter-result');
    const SQLWRITER_RESULT_PLACEHOLDER = '<div style="text-align: center; margin-top: 20px;"><div style="display: inline-flex; align-items: center; justify-content: center; width: 56px; height: 56px; border-radius: 16px; background: var(--accent-2-soft); margin-bottom: 12px;"><i data-lucide="message-square-text" style="width: 26px; height: 26px; color: var(--accent-2);"></i></div><div style="color: var(--text-secondary); line-height: 1.7;">테이블을 추가하고 요청 조건을 입력한 뒤 "SQL 생성"을 눌러주세요.</div></div>';

    let sqlWriterPendingTable = null; // 방금 조회했지만 아직 "추가"하지 않은 테이블
    let sqlWriterTables = [];         // 컨텍스트에 추가된 테이블들 (table_info 응답 그대로)

    window.loadSqlWriterAccounts = async function () {
        if (!sqlWriterAccountSelect) return;
        const dbId = window.currentDbId || '';
        try {
            const res = await fetch(`/api/query/accounts?db_id=${encodeURIComponent(dbId)}&token=${encodeURIComponent(getToken())}`);
            const data = await res.json();
            const accounts = data.accounts || [];
            const previous = sqlWriterAccountSelect.value;
            sqlWriterAccountSelect.innerHTML = accounts.map(a => `<option value="${a}">${a}</option>`).join('');
            if (accounts.includes(previous)) sqlWriterAccountSelect.value = previous;
        } catch (e) {
            console.error('Failed to load AI SQL 작성기 accounts:', e);
        }
    };

    function formatTablePreview(table) {
        const cols = (table.columns || []).map(c => `  ${c.name}  ${c.dataType}${c.nullable ? '' : '  NOT NULL'}`).join('\n');
        const idxLines = (table.indexes || []).map(i => `  ${i.name}  ${i.unique ? 'UNIQUE ' : ''}(${(i.columns || []).join(', ')})`).join('\n');
        const qualifiedName = table.owner ? `${table.owner}.${table.name}` : table.name;
        let text = `TABLE: ${qualifiedName}\nCOLUMNS:\n${cols}`;
        if (idxLines) text += `\nINDEXES:\n${idxLines}`;
        return text;
    }

    function renderSqlWriterTableList() {
        if (!sqlWriterTableList) return;
        if (sqlWriterTables.length === 0) {
            sqlWriterTableList.innerHTML = '<span style="font-size: 0.82rem; color: var(--text-muted);">아직 추가된 테이블이 없습니다.</span>';
        } else {
            sqlWriterTableList.innerHTML = sqlWriterTables.map((t, i) => {
                const qualifiedName = t.owner ? `${t.owner}.${t.name}` : (t.name || '');
                const escaped = String(qualifiedName).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
                return `
                <span style="display:inline-flex; align-items:center; gap:6px; padding: 4px 10px; border-radius: 14px; background: var(--bg-card); border: 1px solid var(--border-color); font-size: 0.82rem; font-family: 'Consolas', 'D2Coding', monospace;">
                    ${escaped}
                    <span data-remove-idx="${i}" style="cursor:pointer; color: var(--text-muted); font-weight: bold;" title="제거">×</span>
                </span>`;
            }).join('');
            sqlWriterTableList.querySelectorAll('[data-remove-idx]').forEach(el => {
                el.addEventListener('click', () => {
                    sqlWriterTables.splice(Number(el.dataset.removeIdx), 1);
                    renderSqlWriterTableList();
                });
            });
        }
        if (sqlWriterTableCount) sqlWriterTableCount.textContent = String(sqlWriterTables.length);
        if (sqlWriterGenerateBtn) sqlWriterGenerateBtn.disabled = sqlWriterTables.length === 0;
    }

    if (sqlWriterLookupBtn && sqlWriterTableInput) {
        // owner를 생략하면(자동 탐색형): 서버가 ① 현재 접속 계정 소유 → ② ALL_TAB_COLUMNS 후보 순으로
        // 찾고, 후보가 여럿이면 needsOwnerSelection 으로 돌려준다 - 그때만 아래 선택 UI를 띄우고,
        // 사용자가 고르면 owner를 채워 doLookup을 다시 호출한다(사용자 요청 2026-09-14).
        const doLookup = (owner) => {
            const tableName = sqlWriterTableInput.value.trim();
            if (!tableName || sqlWriterLookupBtn.disabled) return;
            sqlWriterLookupBtn.disabled = true;
            sqlWriterPendingTable = null;
            if (sqlWriterPreview) sqlWriterPreview.style.display = 'none';
            if (sqlWriterOwnerPicker) sqlWriterOwnerPicker.style.display = 'none';
            if (sqlWriterLookupStatus) sqlWriterLookupStatus.textContent = '조회 중...';

            fetch('/api/sqlwriter/table_info', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    db_id: window.currentDbId || '',
                    account: sqlWriterAccountSelect ? sqlWriterAccountSelect.value : '',
                    token: getToken(),
                    table_name: tableName,
                    owner: owner || ''
                })
            })
            .then(res => res.json())
            .then(data => {
                sqlWriterLookupBtn.disabled = false;
                if (!data.success) {
                    if (data.needsOwnerSelection && Array.isArray(data.owners) && sqlWriterOwnerPicker && sqlWriterOwnerPickerSelect) {
                        // 코드 리뷰 지적(2026-09-14): OWNER는 따옴표로 감싼 식별자를 쓰면 임의 문자를
                        // 담을 수 있는 DB 값이라, innerHTML에 그대로 꽂으면 저장형 XSS가 된다.
                        const escapeOwner = (s) => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
                        sqlWriterOwnerPickerSelect.innerHTML = data.owners.map(o => `<option value="${escapeOwner(o)}">${escapeOwner(o)}</option>`).join('');
                        sqlWriterOwnerPicker.style.display = 'flex';
                    }
                    if (sqlWriterLookupStatus) sqlWriterLookupStatus.textContent = data.message || '조회 실패';
                    return;
                }
                sqlWriterPendingTable = data.table;
                if (sqlWriterLookupStatus) sqlWriterLookupStatus.textContent = '';
                if (sqlWriterPreviewBody) sqlWriterPreviewBody.textContent = formatTablePreview(data.table);
                if (sqlWriterPreview) sqlWriterPreview.style.display = 'flex';
            })
            .catch(() => {
                sqlWriterLookupBtn.disabled = false;
                if (sqlWriterLookupStatus) sqlWriterLookupStatus.textContent = '서버 통신 오류';
            });
        };
        sqlWriterLookupBtn.addEventListener('click', () => doLookup());
        sqlWriterTableInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') { e.preventDefault(); doLookup(); }
        });
        // 테이블명을 바꾸면 이전 조회에서 뜬 OWNER 후보 목록은 더 이상 유효하지 않으므로 숨긴다.
        sqlWriterTableInput.addEventListener('input', () => {
            if (sqlWriterOwnerPicker) sqlWriterOwnerPicker.style.display = 'none';
        });
        if (sqlWriterOwnerPickerBtn) {
            sqlWriterOwnerPickerBtn.addEventListener('click', () => {
                if (sqlWriterOwnerPickerSelect) doLookup(sqlWriterOwnerPickerSelect.value);
            });
        }
    }

    if (sqlWriterAddBtn) {
        sqlWriterAddBtn.addEventListener('click', () => {
            if (!sqlWriterPendingTable) return;
            // 같은 테이블을 다시 추가하면 최신 조회 결과로 교체(중복 방지).
            sqlWriterTables = sqlWriterTables.filter(t => t.name !== sqlWriterPendingTable.name);
            sqlWriterTables.push(sqlWriterPendingTable);
            renderSqlWriterTableList();
            sqlWriterPendingTable = null;
            if (sqlWriterPreview) sqlWriterPreview.style.display = 'none';
            if (sqlWriterTableInput) { sqlWriterTableInput.value = ''; sqlWriterTableInput.focus(); }
        });
    }

    // sql-writer.md 는 SQL 코드 블록을 정확히 하나만 답변에 담도록 강제되어 있다(실행 연동을 위해) -
    // 그 블록만 뽑아 "SQL 실행"/"1차 성능점검" 화면으로 바로 보낼 수 있게 한다.
    function extractSqlWriterCode(answer) {
        const m = /```(?:sql)?\r?\n([\s\S]*?)```/i.exec(answer || '');
        return m ? m[1].trim() : null;
    }

    // container의 조상 중 실제로 스크롤되는 엘리먼트를 찾는다. AI Current SQL 분석 패널은 좌측 트리
    // 메뉴 레이아웃(AI SQL Tunner 트리 메뉴) 때문에 페이지 전체가 아니라 `#tab-tunner-current`
    // (고정 높이 + overflow-y:auto)가 실제 스크롤 경계다 - `.main-content`를 스크롤해도 이 안쪽 패널은
    // 바닥까지 내려가지 않는다(non-AIX 쪽에서 실측 확인). 클래스명을 하드코딩하는 대신 overflow-y:auto인
    // 첫 조상을 찾아야 다른 화면에 재사용해도 맞는 스크롤 박스를 잡는다.
    function findScrollParent(el) {
        let node = el.parentElement;
        while (node && node !== document.body) {
            const overflowY = getComputedStyle(node).overflowY;
            if (overflowY === 'auto' || overflowY === 'scroll') {
                return node;
            }
            node = node.parentElement;
        }
        return document.scrollingElement || document.documentElement;
    }

    // AI Current SQL 분석(성능분석)의 타이핑 효과 - formatAiMarkdownAnswer가 만든 HTML을 한 번에
    // innerHTML로 꽂는 대신, 태그 구조는 그대로 유지한 채 텍스트만 한 글자씩 흘려 넣는다(순수 문자열
    // 타이핑이면 태그가 중간에 잘려 그대로 노출된다 - 예: "<div style=" 가 화면에 텍스트로 보임).
    // 매 틱마다 실제 스크롤 컨테이너를 바닥까지 내려서 글씨가 늘어나는 동안 자동으로 따라 내려가게 한다.
    function typeHtmlInto(container, html, opts) {
        opts = opts || {};
        const charsPerTick = opts.charsPerTick || 3;
        const intervalMs = opts.intervalMs || 16;
        const scrollContainer = findScrollParent(container);

        const source = document.createElement('div');
        source.innerHTML = html;

        const queue = [];
        function walk(node) {
            if (node.nodeType === Node.TEXT_NODE) {
                for (const ch of node.textContent) {
                    queue.push({ type: 'char', ch });
                }
            } else if (node.nodeType === Node.ELEMENT_NODE) {
                queue.push({ type: 'open', tag: node.tagName, attrs: Array.from(node.attributes) });
                node.childNodes.forEach(walk);
                queue.push({ type: 'close' });
            }
        }
        Array.from(source.childNodes).forEach(walk);

        container.innerHTML = '';
        const stack = [container];
        let idx = 0;

        function step() {
            // container가 DOM에서 떨어져나갔으면(재클릭으로 innerHTML이 갈아치워졌거나 화면 클리어) 이
            // 루프를 멈춘다 - 안 그러면 고아가 된 타이머가 계속 살아서 실제 화면의 scrollContainer를
            // 매 틱마다 바닥으로 강제로 끌어내려, 사용자가 새 내용을 보다가도 스크롤이 붙잡힌다.
            if (!container.isConnected) {
                return;
            }
            let charsThisTick = 0;
            while (idx < queue.length && charsThisTick < charsPerTick) {
                const item = queue[idx++];
                const parent = stack[stack.length - 1];
                if (item.type === 'open') {
                    const el = document.createElement(item.tag);
                    item.attrs.forEach(a => el.setAttribute(a.name, a.value));
                    parent.appendChild(el);
                    stack.push(el);
                } else if (item.type === 'close') {
                    stack.pop();
                } else {
                    const last = parent.lastChild;
                    if (last && last.nodeType === Node.TEXT_NODE) {
                        last.textContent += item.ch;
                    } else {
                        parent.appendChild(document.createTextNode(item.ch));
                    }
                    charsThisTick++;
                }
            }
            if (scrollContainer) {
                scrollContainer.scrollTop = scrollContainer.scrollHeight;
            }
            if (idx < queue.length) {
                setTimeout(step, intervalMs);
            } else if (typeof opts.onComplete === 'function') {
                opts.onComplete();
            }
        }
        step();
    }

    // AI SQL 작성기/AI Current SQL 분석의 답변 렌더러. 두 프롬프트(sql-writer.md, current-sql.md) 모두
    // "### 헤더 + ```코드블록" 마크다운을 강제하므로 같은 렌더러를 쓴다. 먼저 escape 한 뒤 서식을
    // 입히므로, 답변에 섞인 SQL(`a<b` 등)이 태그로 해석돼 이후 내용이 통째로 사라지는 일이 없다.
    function formatAiMarkdownAnswer(text) {
        if (!text) return '';
        const escapeHtml = (s) => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
        // 문단(특히 번호 섹션 1,2,3...) 간격이 너무 넓다는 사용자 지적(2026-09-14) - formatAdvisorAnswer와
        // 같은 원인(헤더/구분선/글머리 앞뒤 빈 줄이 div margin 위에 <br/>로 겹쳐 쌓임)이라 같은 줄 단위
        // 렌더링 방식으로 수정 - 자세한 이유는 그쪽 주석 참고. (DBAgent-Java와 동일)
        const bold = (s) => s.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');

        const lines = String(text).replace(/\r\n/g, '\n').split('\n');
        let html = '';
        let inCode = false;
        let codeBuf = [];
        let lastBlock = true;
        let pendingGap = false;

        const flushCode = () => {
            html += `<pre style="background: rgba(0,0,0,0.3); color: #e2e8f0; padding: 12px 14px; border-radius: 6px; overflow-x: auto; white-space: pre; font-family: 'D2Coding', Consolas, monospace; font-size: 0.85rem; margin: 10px 0; line-height: 1.4;">${codeBuf.join('\n')}</pre>`;
            codeBuf = [];
            lastBlock = true;
            pendingGap = false;
        };

        for (const raw of lines) {
            if (inCode) {
                if (raw.trim() === '```') { inCode = false; flushCode(); }
                else codeBuf.push(escapeHtml(raw));
                continue;
            }
            if (/^```/.test(raw.trim())) { inCode = true; continue; }

            if (raw.trim() === '') {
                pendingGap = true;
                continue;
            }

            const heading = /^#{2,4}\s+(.+)$/.exec(raw);
            if (heading) {
                html += `<div style="margin: 16px 0 8px; font-weight: 700; color: var(--primary); font-size: 1rem;">${bold(escapeHtml(heading[1]))}</div>`;
                lastBlock = true; pendingGap = false;
                continue;
            }

            if (/^-{3,}\s*$/.test(raw.trim())) {
                html += '<div style="border-top: 1px solid var(--border-color); margin: 14px 0;"></div>';
                lastBlock = true; pendingGap = false;
                continue;
            }

            const bullet = /^(\s*)-\s+(.+)$/.exec(raw);
            if (bullet) {
                const nested = bullet[1].length >= 2;
                html += `<div style="margin: 3px 0 3px ${nested ? 28 : 14}px;">• ${bold(escapeHtml(bullet[2]))}</div>`;
                lastBlock = true; pendingGap = false;
                continue;
            }

            if (pendingGap && !lastBlock) html += '<br/><br/>';
            else if (!lastBlock) html += '<br/>';
            html += bold(escapeHtml(raw));
            lastBlock = false; pendingGap = false;
        }
        if (inCode) flushCode();
        return html;
    }

    if (sqlWriterGenerateBtn) {
        const runGenerate = () => {
            if (sqlWriterGenerateBtn.disabled || sqlWriterTables.length === 0) return;
            const requestText = (sqlWriterRequestInput ? sqlWriterRequestInput.value.trim() : '');
            if (!requestText) { if (sqlWriterRequestInput) sqlWriterRequestInput.focus(); return; }

            sqlWriterGenerateBtn.disabled = true;
            sqlWriterResult.innerHTML = '<div style="display: flex; align-items: center; gap: 8px; color: var(--text-secondary);"><i data-lucide="loader-2" class="spinning"></i> AI가 SQL을 작성 중입니다...</div>';
            if (typeof lucide !== 'undefined') lucide.createIcons({root: sqlWriterResult});

            fetch('/api/sqlwriter/generate', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ tables: sqlWriterTables, request_text: requestText })
            })
            .then(res => res.json())
            .then(data => {
                sqlWriterGenerateBtn.disabled = false;
                if (data.success === false) {
                    sqlWriterResult.innerHTML = `<div style="color: #d03b3b;">${data.message || 'SQL 생성 중 오류가 발생했습니다.'}</div>`;
                    return;
                }
                const sql = extractSqlWriterCode(data.answer);
                let actionsHtml = '';
                if (sql) {
                    actionsHtml = `<div style="display: flex; gap: 8px; margin-top: 14px;">
                        <button type="button" id="sqlwriter-send-runner-btn" class="secondary-btn" style="padding: 6px 14px; border-radius: 4px; font-size: 0.85rem;"><i data-lucide="play"></i> SQL 실행으로 보내기</button>
                        <button type="button" id="sqlwriter-send-quickcheck-btn" class="secondary-btn" style="padding: 6px 14px; border-radius: 4px; font-size: 0.85rem;"><i data-lucide="list-checks"></i> 1차 성능점검으로 보내기</button>
                    </div>`;
                }
                sqlWriterResult.innerHTML = `<div style="line-height: 1.6;">${formatAiMarkdownAnswer(data.answer)}</div>${actionsHtml}`;
                if (typeof lucide !== 'undefined') lucide.createIcons({root: sqlWriterResult});

                const sendRunnerBtn = document.getElementById('sqlwriter-send-runner-btn');
                if (sendRunnerBtn) {
                    sendRunnerBtn.addEventListener('click', () => {
                        const navItem = document.querySelector('.nav-item[data-target="sqlrunner"]');
                        if (navItem) navItem.click();
                        const runnerInput = document.getElementById('sqlrunner-input');
                        if (runnerInput) { runnerInput.value = sql; runnerInput.focus(); }
                    });
                }
                const sendQuickCheckBtn = document.getElementById('sqlwriter-send-quickcheck-btn');
                if (sendQuickCheckBtn) {
                    sendQuickCheckBtn.addEventListener('click', () => {
                        const navItem = document.querySelector('.nav-item[data-target="aidba"]');
                        if (navItem) navItem.click();
                        const currentTabBtn = document.querySelector('.tunner-tab-btn[data-tunner-tab="tab-tunner-current"]');
                        if (currentTabBtn) currentTabBtn.click();
                        const currentInput = document.getElementById('tunner-current-input');
                        if (currentInput) { currentInput.value = sql; currentInput.dispatchEvent(new Event('input')); currentInput.focus(); }
                    });
                }
            })
            .catch(() => {
                sqlWriterGenerateBtn.disabled = false;
                sqlWriterResult.innerHTML = '<div style="color: #d03b3b;">서버 통신 오류가 발생했습니다.</div>';
            });
        };
        sqlWriterGenerateBtn.addEventListener('click', runGenerate);
        if (sqlWriterRequestInput) {
            sqlWriterRequestInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) runGenerate();
            });
        }
    }

    if (sqlWriterClearBtn) {
        sqlWriterClearBtn.addEventListener('click', () => {
            sqlWriterTables = [];
            sqlWriterPendingTable = null;
            renderSqlWriterTableList();
            if (sqlWriterTableInput) sqlWriterTableInput.value = '';
            if (sqlWriterRequestInput) sqlWriterRequestInput.value = '';
            if (sqlWriterPreview) sqlWriterPreview.style.display = 'none';
            if (sqlWriterLookupStatus) sqlWriterLookupStatus.textContent = '';
            if (sqlWriterResult) {
                sqlWriterResult.innerHTML = SQLWRITER_RESULT_PLACEHOLDER;
                if (typeof lucide !== 'undefined') lucide.createIcons({root: sqlWriterResult});
            }
            if (sqlWriterTableInput) sqlWriterTableInput.focus();
        });
    }

    renderSqlWriterTableList();

    // Error Search
    const errorCodeInput = document.getElementById('error-code-input');
    const errorSearchBtn = document.getElementById('error-search-btn');
    const errorSearchResult = document.getElementById('error-search-result');

    if (errorSearchBtn && errorCodeInput && errorSearchResult) {
        const doErrorSearch = async () => {
            const code = errorCodeInput.value.trim();
            if (!code) return;

            errorSearchResult.innerHTML = '<div style="text-align: center; margin-top: 50px;">검색 중...</div>';
            
            try {
                const response = await fetch(`/api/aidba/error_search?code=${encodeURIComponent(code)}`);
                const data = await response.json();

                if (data.found) {
                    errorSearchResult.innerHTML = `
                        <h3 style="margin-top: 0; color: var(--primary);">${data.error_code}</h3>
                        <div style="margin-bottom: 15px;">
                            <strong style="color: var(--text-primary);">■ 발생 원인:</strong>
                            <p style="white-space: pre-wrap; margin-top: 5px; color: var(--text-secondary); line-height: 1.5;">${data.cause}</p>
                        </div>
                        <div style="margin-bottom: 15px;">
                            <strong style="color: var(--text-primary);">■ 조치 방안:</strong>
                            <p style="white-space: pre-wrap; margin-top: 5px; color: var(--text-secondary); line-height: 1.5;">${data.action}</p>
                        </div>
                        <div style="margin-bottom: 0;">
                            <strong style="color: var(--text-primary);">■ 관련 쿼리 및 로그 위치:</strong>
                            <p style="white-space: pre-wrap; margin-top: 5px; color: var(--text-secondary); line-height: 1.5;">${data.query_or_log}</p>
                        </div>
                    `;
                } else if (data.error) {
                    errorSearchResult.innerHTML = `<div style="color: #d03b3b; text-align: center; margin-top: 50px;">오류: ${data.error}</div>`;
                } else {
                    errorSearchResult.innerHTML = `<div style="color: var(--text-secondary); text-align: center; margin-top: 50px;">${data.message || '결과를 찾을 수 없습니다.'}</div>`;
                }
            } catch (err) {
                errorSearchResult.innerHTML = `<div style="color: #d03b3b; text-align: center; margin-top: 50px;">서버 통신 오류가 발생했습니다.</div>`;
            }
        };

        errorSearchBtn.addEventListener('click', doErrorSearch);
        errorCodeInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') doErrorSearch();
        });
    }

    // AI Chatbot (Ollama)
    // AIX 이관본 주의사항 두 가지.
    //
    // 1) 여기서 잡는 id 는 index.html 의 실제 id 와 맞춰야 한다. 이관 과정에서 chat-input /
    //    chat-send-btn / chat-log 를 찾고 있었는데 index.html 에는 그런 id 가 없어서
    //    (aidba-chat-* 로 되어 있다) 아래 블록 전체가 실행되지 않았고, 결과적으로 전송 버튼이
    //    아무 반응도 하지 않는 상태였다. 2026-09-07 에 실제 id 로 교정.
    //
    // 2) AIX 서버에는 Ollama(GPU 런타임 필요)를 직접 올릴 수 없어 사내망 GPU 서버(sqlrestapi)를
    //    거쳐야 동작한다. GPU 서버 방화벽이 REST API 포트(9300)만 열어주는 구성으로 확정되고
    //    OllamaChatService가 sqlrestapi의 /api/chat을 호출하도록 이관되면서(2026-09-11) 이 상수를
    //    false로 바꿨다 - application.properties의 aidba.ollama.url을 그 sqlrestapi 주소로
    //    맞춰야 실제로 동작한다(dist-aix/application.properties.sample 참고, 모델명 설정은 이제
    //    불필요).
    const AIDBA_GPU_PENDING = false;
    const chatInput = document.getElementById('aidba-chat-input');
    const chatSendBtn = document.getElementById('aidba-chat-send-btn');
    const chatLog = document.getElementById('aidba-chat-history');
    const chatClearBtn = document.getElementById('aidba-chat-clear-btn');
    // "화면 클리어" 로 되돌릴 초기 인사말(원본 app.js와 동일 패턴, 2026-09-15 포팅).
    const chatInitialHtml = chatLog ? chatLog.innerHTML : '';

    if (chatInput && chatSendBtn && chatLog) {
        const escapeHtml = (s) => s.replace(/[&<>"']/g, (c) => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        }[c]));

        const appendChatMessage = (role, text) => {
            const placeholder = chatLog.querySelector('div[style*="text-align: center"]');
            if (placeholder) placeholder.remove();

            const isUser = role === 'user';
            const bubble = document.createElement('div');
            bubble.style.cssText = `margin-bottom: 12px; display: flex; ${isUser ? 'justify-content: flex-end;' : 'justify-content: flex-start;'}`;
            bubble.innerHTML = `
                <div style="max-width: 80%; padding: 10px 14px; border-radius: 10px; white-space: pre-wrap; word-break: break-word;
                            background: ${isUser ? 'var(--primary)' : 'var(--bg-card)'};
                            color: ${isUser ? '#fff' : 'var(--text-primary)'};
                            border: ${isUser ? 'none' : '1px solid var(--border-color)'};">
                    ${escapeHtml(text)}
                </div>`;
            chatLog.appendChild(bubble);
            chatLog.scrollTop = chatLog.scrollHeight;
            return bubble;
        };

        const doChatSend = async () => {
            const message = chatInput.value.trim();
            if (!message) return;

            chatInput.value = '';
            appendChatMessage('user', message);

            if (AIDBA_GPU_PENDING) {
                appendChatMessage('assistant', 'GPU 서버 연동후 사용 가능합니다.');
                chatInput.focus();
                return;
            }

            chatSendBtn.disabled = true;
            const pending = appendChatMessage('assistant', '생각 중...');

            try {
                const response = await fetch('/api/aidba/chat', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ prompt: message })
                });
                const data = await response.json();
                const textDiv = pending.querySelector('div');
                if (data.error || data.success === false) {
                    textDiv.textContent = `오류: ${data.error || data.message || '알 수 없는 오류'}`;
                } else {
                    // non-AIX 쪽과 동일하게 ORA 코드 regex/시맨틱 검색으로 찾은 error_dictionary 원문을
                    // "첨부 문서"로 답변 아래 기본 펼침 상태로 붙인다(2026-09-13 포팅). 답변/첨부 문서 모두
                    // 사용자 입력이 아니라 DB/LLM에서 온 텍스트지만 HTML로 렌더링하므로 이스케이프한다 -
                    // 개행은 부모 버블의 white-space:pre-wrap이 그대로 살려준다.
                    let sourceHtml = '';
                    if (data.context_used) {
                        const srcLabel = '첨부 문서';
                        sourceHtml = `<div style="margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--border-color); font-size: 0.9rem; font-weight: 600; color: var(--primary); cursor: pointer; user-select: none;" onclick="var b=this.nextElementSibling; var willOpen=(b.style.display==='none'); b.style.display=willOpen?'block':'none'; this.textContent=(willOpen?'[-] ':'[+] ')+'${srcLabel}';">[-] ${srcLabel}</div><div style="display: block; font-size: 0.95rem; line-height: 1.7; white-space: pre-wrap; word-break: break-word; font-family: 'D2Coding', Consolas, 'Courier New', monospace; max-height: 320px; overflow-y: auto; margin-top: 8px;">${escapeHtml(data.context_used)}</div>`;
                    }
                    textDiv.innerHTML = escapeHtml(data.answer || '(빈 응답)') + sourceHtml;
                }
            } catch (err) {
                pending.querySelector('div').textContent = '서버 통신 오류가 발생했습니다.';
            } finally {
                chatSendBtn.disabled = false;
                chatInput.focus();
            }
        };

        chatSendBtn.addEventListener('click', doChatSend);
        // input -> textarea 로 바뀌면서 Enter 의 의미가 갈렸다: Enter 는 전송, Shift+Enter 는 줄바꿈.
        // isComposing 체크가 없으면 한글 조합 중 Enter(글자 확정)에 그대로 전송돼 버린다(원본과 동일 패턴).
        chatInput.addEventListener('keydown', (e) => {
            if (e.isComposing) return;
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                doChatSend();
            }
        });

        if (chatClearBtn) {
            chatClearBtn.addEventListener('click', () => {
                chatLog.innerHTML = chatInitialHtml;
                chatLog.scrollTop = 0;
                chatInput.value = '';
                chatInput.focus();
                if (typeof lucide !== 'undefined') lucide.createIcons({root: chatLog});
            });
        }
    }

// SQL Runner Logic

    const sqlRunnerInput = document.getElementById('sqlrunner-input');
    const sqlRunnerBtn = document.getElementById('sqlrunner-run-btn');
    const sqlRunnerClearBtn = document.getElementById('sqlrunner-clear-btn');
    const sqlRunnerResult = document.getElementById('sqlrunner-result');
    const sqlRunnerStatus = document.getElementById('sqlrunner-status');
    const sqlRunnerRowLimitInput = document.getElementById('sqlrunner-rowlimit-input');
    const sqlRunnerAccountSelect = document.getElementById('sqlrunner-account-select');

    // Populates the account dropdown for the currently selected DB. Re-run whenever the SQL Runner
    // tab is opened (see switchTab) so it stays in sync with the sidebar's DB selection.
    window.loadSqlRunnerAccounts = async function () {
        if (!sqlRunnerAccountSelect) return;
        const dbId = window.currentDbId || '';
        try {
            const res = await fetch(`/api/query/accounts?db_id=${encodeURIComponent(dbId)}&token=${encodeURIComponent(getToken())}`);
            const data = await res.json();
            const accounts = data.accounts || [];
            const previous = sqlRunnerAccountSelect.value;
            sqlRunnerAccountSelect.innerHTML = accounts.map(a => `<option value="${a}">${a}</option>`).join('');
            if (accounts.includes(previous)) {
                sqlRunnerAccountSelect.value = previous;
            }
        } catch (e) {
            console.error('Failed to load SQL runner accounts:', e);
        }
    };

    if (sqlRunnerInput && sqlRunnerBtn && sqlRunnerResult) {
        const escapeHtml = (s) => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

        const runSql = async () => {
            const sql = sqlRunnerInput.value.trim();
            if (!sql) return;

            const account = sqlRunnerAccountSelect ? sqlRunnerAccountSelect.value : '';
            const rowLimit = sqlRunnerRowLimitInput ? parseInt(sqlRunnerRowLimitInput.value, 10) : null;

            sqlRunnerBtn.disabled = true;
            sqlRunnerStatus.textContent = '실행 중...';
            sqlRunnerResult.innerHTML = '<div style="text-align:center; margin-top:30px; color: var(--text-secondary);">실행 중입니다...</div>';

            try {
                const res = await fetch('/api/query/execute', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ db_id: window.currentDbId || '', sql, max_rows: (rowLimit && rowLimit > 0) ? rowLimit : null, account, token: sessionStorage.getItem('dbagent_token') })
                });
                const data = await res.json();

                if (!data.success) {
                    sqlRunnerStatus.textContent = '';
                    sqlRunnerResult.innerHTML = `<div style="color: #d03b3b; padding: 15px; background: var(--bg-card); border-radius: 6px; white-space: pre-wrap;">오류: ${escapeHtml(data.message || '알 수 없는 오류')}</div>`;
                    return;
                }

                sqlRunnerStatus.textContent = `${data.elapsed_ms}ms`;

                if (data.type === 'update') {
                    sqlRunnerResult.innerHTML = `<div style="color: var(--success); padding: 15px; background: var(--bg-card); border-radius: 6px;">${data.affected_rows}건 처리되었습니다.</div>`;
                    return;
                }

                const columns = data.columns || [];
                const rows = data.rows || [];
                if (rows.length === 0) {
                    sqlRunnerResult.innerHTML = '<div style="color: var(--text-secondary); text-align:center; margin-top:30px;">조회 결과가 없습니다.</div>';
                    return;
                }

                let html = `<div style="margin-bottom:8px; font-size:0.85rem; color: var(--text-secondary);">${data.row_count}건${data.truncated ? ` (조회 건수 상한 ${data.max_rows}건 도달 - 상위 결과만 표시됨. 더 보려면 조회 건수를 늘려서 다시 실행하세요)` : ''}</div>`;
                html += '<div class="table-container" style="max-height: 500px; overflow: auto;"><table class="data-table sql-result-table"><thead><tr>';
                columns.forEach(c => { html += `<th>${escapeHtml(c)}</th>`; });
                html += '</tr></thead><tbody>';
                rows.forEach(row => {
                    html += '<tr>';
                    row.forEach(v => {
                        html += (v === null || v === undefined)
                            ? '<td><span style="color: var(--text-muted);">NULL</span></td>'
                            : `<td>${escapeHtml(v)}</td>`;
                    });
                    html += '</tr>';
                });
                html += '</tbody></table></div>';
                sqlRunnerResult.innerHTML = html;
            } catch (err) {
                sqlRunnerStatus.textContent = '';
                sqlRunnerResult.innerHTML = '<div style="color: #d03b3b; text-align:center; margin-top:30px;">서버 통신 오류가 발생했습니다.</div>';
            } finally {
                sqlRunnerBtn.disabled = false;
            }
        };

        sqlRunnerBtn.addEventListener('click', runSql);

        if (sqlRunnerClearBtn) {
            sqlRunnerClearBtn.addEventListener('click', () => {
                sqlRunnerInput.value = '';
                sqlRunnerStatus.textContent = '';
                sqlRunnerResult.innerHTML = '<div style="color: var(--text-secondary); text-align: center; margin-top: 30px;">SQL을 입력하고 실행 버튼을 누르세요.</div>';
                sqlRunnerInput.focus();
            });
        }

        sqlRunnerInput.addEventListener('keydown', (e) => {
            // Enter runs the query; Shift+Enter still inserts a newline for multi-line SQL.
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                runSql();
            }
        });
    }

    } catch (e) {
        alert("JS Error: " + e.message);
    }
})();
