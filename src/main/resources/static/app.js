
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
                            
                            if (typeof saveSessionHistorySnapshot === 'function') saveSessionHistorySnapshot(window.currentDbId);
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
                // Ensure we don't spam clicks if already fetching
                const icon = btn.querySelector('i');
                if (!icon || !icon.classList.contains('spinning')) {
                    btn.click();
                }
            }
        }
        
        // 사용자 요청(2026-08-31): 다른 메뉴 갔다가 Current Session으로 돌아와도 추이/Trace 그래프가
        // 안 끊기게 - 예전엔 여기서 매번 resetSessionMonitor()를 무조건 호출해 그래프를 지웠는데, 자동
        // 갱신이 켜져 있으면 이 탭을 안 보고 있는 동안에도 폴링(setInterval)은 백그라운드에서 계속 돌며
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
        tsRefreshBtn.addEventListener('click', async () => {
            const icon = tsRefreshBtn.querySelector('i');
            if (icon) icon.classList.add('spinning');

            const tsLoadingOverlay = document.getElementById('tablespace-loading-overlay');
            if (tsLoadingOverlay) tsLoadingOverlay.style.display = 'flex';

            tsTbody.innerHTML = '';

            try {
                const response = await fetch(`/api/tablespace?db_id=${window.currentDbId || ""}&token=${encodeURIComponent(getToken())}`);
                if (response.ok) {
                    const data = await response.json();
                    const tsTotalMbEl = document.getElementById('tablespace-total-mb');
                    const tsUsedMbEl = document.getElementById('tablespace-used-mb');
                    const tsTotalPctEl = document.getElementById('tablespace-total-pct');

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
                }
            } catch (error) {
                console.error('Tablespace fetch error:', error);
                tsTbody.innerHTML = `<tr><td colspan="6" style="color:#d03b3b; text-align:center; padding: 30px;">데이터를 불러오는 데 실패했습니다: ${error.message}</td></tr>`;
            }
            if (icon) icon.classList.remove('spinning');
            if (tsLoadingOverlay) tsLoadingOverlay.style.display = 'none';
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

            const checkedTmlockSids = Array.from(document.querySelectorAll('.tmlock-checkbox:checked')).map(cb => cb.getAttribute('data-sid'));
            tmlockTbody.innerHTML = tableHtml;
            document.querySelectorAll('.tmlock-checkbox').forEach(cb => {
                if (checkedTmlockSids.includes(cb.getAttribute('data-sid'))) {
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
    let sessionChart = null;
    let sessionScatterChart = null;
    let scatterDataPoints = [];
    let isScatterBrushBound = false;
    // Both trend charts (left line chart, right Trace scatter) show this same fixed window with
    // ticks every 5 minutes (20 ticks total) regardless of the polling interval. Window size is tied
    // to tick count on purpose - 20 ticks is about what fits without Chart.js's autoSkip thinning them
    // back out, so halving stepSize (10min -> 5min) halves the window too, not just the tick label.
    const CHART_WINDOW_MS = 20 * 5 * 60 * 1000;
    const sessionHistory = {
        labels: [],
        activeSessions: [],
        activeTx: [],
        parallel: [],
        pending2pc: [],
        lockWait: []
    };

    // 사용자 요청(2026-09-01): 다른 DB로 갔다가 원래 DB로 돌아와도 Current Session 추이/Trace 그래프가
    // 유지되게 함 - db_id별로 sessionHistory/scatterDataPoints 스냅샷을 따로 보관해뒀다가, 그 DB로
    // 돌아오면 그대로 복원한다. 같은 DB 안에서 메뉴만 왔다갔다 하는 경우는 switchTab()이 이미 별도로
    // 처리 중이라(위 주석 참고) 여기서는 DB 전환(인스턴스 클릭) 케이스만 다룬다.
    //
    // 사용자 지적(2026-09-02): 같은 index.html 안에서 DB만 바꾸는 건 유지되는데, Fleet Overview
    // 화면(fleet-overview.html)에 갔다가 돌아오면 초기화됨 - 이건 FO가 SPA 전환이 아니라 완전히 다른
    // 페이지로의 실제 이동(location.href)이라 index.html의 JS 컨텍스트 자체가 통째로 파괴/재생성되기
    // 때문. dbSessionHistoryCache가 메모리 Map이라서 못 버텼던 것 - 같은 탭이 유지되는 동안 살아있는
    // sessionStorage에 캐시를 함께 영속화해서, 페이지가 다시 로드돼도 저장해둔 스냅샷을 그대로 복원한다.
    const SESSION_HISTORY_STORAGE_KEY = 'dbagent_session_history_cache';

    function loadSessionHistoryCache() {
        try {
            const raw = sessionStorage.getItem(SESSION_HISTORY_STORAGE_KEY);
            return raw ? new Map(JSON.parse(raw)) : new Map();
        } catch (e) {
            return new Map();
        }
    }

    function persistSessionHistoryCache() {
        try {
            sessionStorage.setItem(SESSION_HISTORY_STORAGE_KEY, JSON.stringify(Array.from(dbSessionHistoryCache.entries())));
        } catch (e) {
            // sessionStorage 용량 초과 등으로 실패해도 메모리 캐시(dbSessionHistoryCache)로는 계속
            // 정상 동작 - 같은 페이지 안에서의 DB 전환 유지 기능만 그대로 살아있으면 되므로 조용히 무시.
        }
    }

    const dbSessionHistoryCache = loadSessionHistoryCache();

    function saveSessionHistorySnapshot(dbId) {
        if (!dbId) return;
        dbSessionHistoryCache.set(dbId, {
            sessionHistory: {
                labels: sessionHistory.labels.slice(),
                activeSessions: sessionHistory.activeSessions.slice(),
                activeTx: sessionHistory.activeTx.slice(),
                parallel: sessionHistory.parallel.slice(),
                pending2pc: sessionHistory.pending2pc.slice(),
                lockWait: sessionHistory.lockWait.slice()
            },
            scatterDataPoints: scatterDataPoints.slice()
        });
        persistSessionHistoryCache();
    }

    // FO 화면으로 넘어가는 링크(사이드바 브랜드 링크, FO 버튼 등)는 여러 곳에서 각자 location.href를
    // 바꾸므로 매 지점을 다 걸어주는 대신, 페이지를 떠나는 시점(pagehide)에 한 번에 현재 DB의 스냅샷을
    // 저장한다. 새로고침/탭 종료에도 함께 발생하지만 이건 로그아웃과 달리 그냥 차트 데이터 저장일
    // 뿐이라 부작용이 없다 - 오히려 새로고침 후에도 그래프가 이어지는 효과.
    window.addEventListener('pagehide', () => {
        if (window.currentDbId) saveSessionHistorySnapshot(window.currentDbId);
    });

    // 사용자 요청(2026-08-31): 추이 그래프와 Trace 그래프의 색상/범례를 동기화하고, 각 계열을 체크박스로
    // 켜고 끌 수 있게 함. 두 배열의 색상은 반드시 같은 순서로 유지 - TREND_SERIES는 추이 그래프(라인)
    // 5계열, SCATTER_CATEGORIES는 Trace 그래프(산점도) 4계열(2PC Pending은 살아있는 세션에 붙는 속성이
    // 아니라 개별 점으로 표시할 대상이 원천적으로 없어 제외 - dba_2pc_pending은 보통 이미 끊어진/고아
    // 상태의 분산 트랜잭션이라 대응되는 v$session 행이 없는 경우가 대부분).
    const TREND_SERIES = [
        { label: 'ACTIVE SESSION', color: '#0ca30c' },
        { label: 'ACTIVE TRANSACTION', color: '#3987e5' },
        { label: 'PARALLEL SESSION', color: '#808000' },
        { label: '2PC PENDING TRANSACTION', color: '#e53935' },
        { label: 'LOCK WAIT', color: '#9333ea' }
    ];
    const SCATTER_CATEGORIES = [
        { key: 'active_session', label: 'ACTIVE SESSION', color: '#0ca30c' },
        { key: 'active_transaction', label: 'ACTIVE TRANSACTION', color: '#3987e5' },
        { key: 'parallel_session', label: 'PARALLEL SESSION', color: '#808000' },
        { key: 'lock_wait', label: 'LOCK WAIT', color: '#9333ea' }
    ];

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

    async function fetchSessions() {
        if (!sessionTbody) return;
        
        try {
            const icon = sessionRefreshBtn.querySelector('i');
            if (icon) icon.classList.add('spinning');
            
            const [response, extraResponse] = await Promise.all([
                fetch(`/api/session?db_id=${window.currentDbId || ""}&token=${encodeURIComponent(getToken())}`),
                fetch(`/api/session_extra?db_id=${window.currentDbId || ""}&token=${encodeURIComponent(getToken())}`)
            ]);
            if (!response.ok) throw new Error('Network response was not ok');
            const data = await response.json();

            if (data.error) throw new Error(data.error);

            // session_extra is best-effort (feeds the trend lines + the 3 extra tabs below) - a
            // failure there shouldn't take down the primary Active Session list/table.
            let extra = { active_transactions: [], parallel_sessions: [], pending_2pc: [], lock_wait_count: 0, lock_wait_sids: [] };
            try {
                if (extraResponse.ok) {
                    const extraData = await extraResponse.json();
                    if (!extraData.error) extra = extraData;
                }
            } catch (extraErr) {
                console.error('Failed to fetch session_extra:', extraErr);
            }

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

            const now = new Date();
            const nowTime = now.getTime();
            sessionHistory.labels.push(nowTime);
            sessionHistory.activeSessions.push(activeCount);
            sessionHistory.activeTx.push(extra.active_transactions.length);
            sessionHistory.parallel.push(extra.parallel_sessions.length);
            sessionHistory.pending2pc.push(extra.pending_2pc.length);
            sessionHistory.lockWait.push(extra.lock_wait_count || 0);

            // Recomputed every fetch (not a fixed constant) since the polling interval is user-adjustable
            // - always keep enough points to cover the fixed CHART_WINDOW_MS window at the current rate.
            const refreshMs = (parseInt(sessionIntervalInput.value) || 5) * 1000;
            const maxDataPoints = Math.max(1, Math.ceil(CHART_WINDOW_MS / refreshMs));
            if (sessionHistory.labels.length > maxDataPoints) {
                sessionHistory.labels.shift();
                sessionHistory.activeSessions.shift();
                sessionHistory.activeTx.shift();
                sessionHistory.parallel.shift();
                sessionHistory.pending2pc.shift();
                sessionHistory.lockWait.shift();
            }

            const ctx = document.getElementById('session-chart');
            if (ctx) {
                if (!sessionChart) {
                    sessionChart = new Chart(ctx, {
                        type: 'line',
                        data: {
                            labels: sessionHistory.labels,
                            datasets: [
                                {
                                    label: 'ACTIVE SESSION',
                                    data: sessionHistory.activeSessions,
                                    borderColor: '#0ca30c',
                                    backgroundColor: 'rgba(12, 163, 12, 0.1)',
                                    borderWidth: 1.5,
                                    pointRadius: 1.5,
                                    pointHoverRadius: 3,
                                    fill: true,
                                    tension: 0
                                },
                                {
                                    label: 'ACTIVE TRANSACTION',
                                    data: sessionHistory.activeTx,
                                    borderColor: '#3987e5',
                                    backgroundColor: 'rgba(57, 135, 229, 0.1)',
                                    borderWidth: 1.5,
                                    pointRadius: 1.5,
                                    pointHoverRadius: 3,
                                    fill: true,
                                    tension: 0
                                },
                                {
                                    label: 'PARALLEL SESSION',
                                    data: sessionHistory.parallel,
                                    borderColor: '#808000',
                                    backgroundColor: 'rgba(128, 128, 0, 0.1)',
                                    borderWidth: 1.5,
                                    pointRadius: 1.5,
                                    pointHoverRadius: 3,
                                    fill: true,
                                    tension: 0
                                },
                                {
                                    label: '2PC PENDING TRANSACTION',
                                    data: sessionHistory.pending2pc,
                                    borderColor: '#e53935',
                                    backgroundColor: 'rgba(229, 57, 53, 0.1)',
                                    borderWidth: 1.5,
                                    pointRadius: 1.5,
                                    pointHoverRadius: 3,
                                    fill: true,
                                    tension: 0
                                },
                                {
                                    label: 'LOCK WAIT',
                                    data: sessionHistory.lockWait,
                                    borderColor: '#9333ea',
                                    backgroundColor: 'rgba(147, 51, 234, 0.1)',
                                    borderWidth: 1.5,
                                    pointRadius: 1.5,
                                    pointHoverRadius: 3,
                                    fill: true,
                                    tension: 0
                                }
                            ]
                        },
                        options: {
                            responsive: true,
                            maintainAspectRatio: false,
                            animation: {
                                duration: 0 // Disable animation for real-time updates
                            },
                            scales: {
                                x: {
                                    type: 'time',
                                    time: {
                                        unit: 'minute',
                                        tooltipFormat: 'HH:mm:ss',
                                        displayFormats: {
                                            minute: 'HH:mm'
                                        }
                                    },
                                    min: nowTime - CHART_WINDOW_MS,
                                    max: nowTime,
                                    // stepSize belongs under ticks (not time) in Chart.js v4's time scale -
                                    // this is what actually forces exact 10-minute-spaced ticks.
                                    ticks: { color: chartLineColor(0.8), stepSize: 5, maxRotation: 0, minRotation: 0, font: { size: 13 } },
                                    grid: { 
                                        drawOnChartArea: true,
                                        color: chartLineColor(0.15),
                                        borderDash: [4, 4]
                                    },
                                    border: {
                                        display: true,
                                        color: chartLineColor(1),
                                        width: 2
                                    }
                                },
                                y: {
                                    beginAtZero: true,
                                    min: 0,
                                    ticks: { precision: 0, color: chartLineColor(0.8), font: { size: 13 } },
                                    grid: {
                                        drawOnChartArea: true,
                                        color: chartLineColor(0.15),
                                        borderDash: [4, 4]
                                    },
                                    border: {
                                        display: true,
                                        color: chartLineColor(1),
                                        width: 2
                                    }
                                }
                            },
                            plugins: {
                                // 커스텀 체크박스 범례(#session-chart-legend)로 대체 - 기본 범례는 끔.
                                legend: {
                                    display: false
                                },
                                title: {
                                    display: true,
                                    text: '실시간 세션 추이'
                                },
                                subtitle: {
                                    display: true,
                                    text: 'Transaction / Session Count',
                                    align: 'start',
                                    color: chartLineColor(0.8),
                                    padding: { bottom: 10 }
                                }
                            }
                        }
                    });
                    buildChartLegend('session-chart-legend', TREND_SERIES, () => sessionChart);
                    applyLegendVisibility('session-chart-legend', sessionChart);
                } else {
                    sessionChart.options.scales.x.min = nowTime - CHART_WINDOW_MS;
                    sessionChart.options.scales.x.max = nowTime;
                    sessionChart.update();
                }
            }

            // Independent of sessionChart's init state above, so the Trace scatter chart is created on
            // the very first fetch too instead of only starting from the second polling cycle.
            const scatterNowTime = Date.now();
            // 사용자 요청(2026-08-31): Trace 점(개별 세션)을 추이 그래프와 같은 색으로 카테고리 구분 -
            // 우선순위 Lock Wait > Active Transaction > Parallel Session > 기본 Active Session (더 급한
            // 신호가 우선). has_transaction은 이미 getSessions()가 내려주는 필드, parallel/lock_wait는
            // SID 집합으로 대조.
            const parallelSidSet = new Set((extra.parallel_sessions || []).map(p => p.sid));
            const lockWaitSidSet = new Set(extra.lock_wait_sids || []);
            data.forEach(s => {
                if (s && s.status && s.status.trim().toUpperCase() === 'ACTIVE' && s.duration_time !== null) {
                    // Only add if not exactly identical recently
                    const lastPoint = scatterDataPoints.length > 0 ? scatterDataPoints[scatterDataPoints.length - 1] : null;
                    if (!lastPoint || lastPoint.session.sid !== s.sid || lastPoint.y !== Number(s.duration_time)) {
                        let category = 'active_session';
                        if (lockWaitSidSet.has(s.sid)) category = 'lock_wait';
                        else if (s.has_transaction) category = 'active_transaction';
                        else if (parallelSidSet.has(s.sid)) category = 'parallel_session';
                        scatterDataPoints.push({
                            x: scatterNowTime,
                            y: Number(s.duration_time),
                            session: s,
                            category
                        });
                    }
                }
            });
            // Keep only the fixed CHART_WINDOW_MS window (matches the left trend chart)
            scatterDataPoints = scatterDataPoints.filter(p => scatterNowTime - p.x <= CHART_WINDOW_MS);

            const scatterCtx = document.getElementById('session-scatter-chart');
            if (scatterCtx) {
                if (!sessionScatterChart) {
                    sessionScatterChart = new Chart(scatterCtx, {
                        type: 'scatter',
                        data: {
                            // 카테고리별 별도 데이터셋 - 체크박스 범례가 데이터셋 단위로 show/hide하므로
                            // 이렇게 나눠야 "Parallel Session 점만 숨기기" 같은 필터링이 가능해진다.
                            datasets: SCATTER_CATEGORIES.map(cat => ({
                                label: cat.label,
                                data: scatterDataPoints.filter(p => p.category === cat.key),
                                backgroundColor: cat.color,
                                borderColor: cat.color,
                                borderWidth: 2,
                                pointRadius: 2,
                                pointHoverRadius: 5,
                                pointStyle: 'crossRot'
                            }))
                        },
                        options: {
                            responsive: true,
                            maintainAspectRatio: false,
                            animation: false,
                            scales: {
                                x: {
                                    type: 'time',
                                    position: 'bottom',
                                    time: {
                                        unit: 'minute',
                                        tooltipFormat: 'HH:mm:ss',
                                        displayFormats: {
                                            minute: 'HH:mm'
                                        }
                                    },
                                    min: scatterNowTime - CHART_WINDOW_MS,
                                    max: scatterNowTime,
                                    border: {
                                        display: true,
                                        color: chartLineColor(1),
                                        width: 1
                                    },
                                    // stepSize belongs under ticks (not time) in Chart.js v4's time scale.
                                    ticks: {
                                        color: chartLineColor(1),
                                        stepSize: 5,
                                        maxRotation: 0,
                                        font: { size: 13 }
                                    },
                                    grid: {
                                        color: chartLineColor(0.1),
                                        borderColor: chartLineColor(1),
                                        tickColor: chartLineColor(1)
                                    },
                                    title: { display: false }
                                },
                                y: {
                                    title: { display: false },
                                    min: 0,
                                    max: 300,
                                    border: {
                                        display: true,
                                        color: chartLineColor(1),
                                        width: 1
                                    },
                                    ticks: {
                                        color: chartLineColor(1),
                                        stepSize: 100,
                                        font: { size: 13 },
                                        callback: function(value) {
                                            return value;
                                        }
                                    },
                                    grid: { 
                                        color: chartLineColor(0.1),
                                        borderColor: chartLineColor(1),
                                        tickColor: chartLineColor(1)
                                    }
                                }
                            },
                            plugins: {
                                // 커스텀 체크박스 범례(#scatter-chart-legend)로 대체 - 기본 범례는 끔.
                                legend: {
                                    display: false
                                },
                                title: {
                                    display: true,
                                    text: 'Trace(sec)',
                                    align: 'start',
                                    color: chartLineColor(0.7),
                                    font: { size: 12, weight: 'bold' },
                                    padding: { top: 0, bottom: 5 }
                                },
                                tooltip: {
                                    callbacks: {
                                        label: function(ctx) {
                                            const p = ctx.raw;
                                            return `SID: ${p.session.sid}, Duration: ${p.y}s, Event: ${p.session.event_name || '-'} [${ctx.dataset.label}]`;
                                        }
                                    }
                                }
                            }
                        }
                    });
                    buildChartLegend('scatter-chart-legend', SCATTER_CATEGORIES, () => sessionScatterChart);
                    applyLegendVisibility('scatter-chart-legend', sessionScatterChart);
                } else if (!window.scatterDragActive) {
                    // Skip the visual refresh while a brush-selection drag is in progress (see
                    // initScatterBrush below) - the x-axis window slides forward on every poll tick,
                    // so a chart.update() mid-drag shifts every dot left out from under the selection
                    // box the user is still drawing. mouseup's pixel->value conversion reads this same
                    // chart's live scale, so if it moved mid-drag the selected sessions no longer match
                    // what was visually boxed. Freezing the render here (data keeps accumulating in
                    // scatterDataPoints/window.globalScatterDataPoints regardless) keeps what's on
                    // screen consistent with what mouseup will compute; the next poll after mouseup
                    // catches the chart up in one jump.
                    SCATTER_CATEGORIES.forEach((cat, i) => {
                        sessionScatterChart.data.datasets[i].data = scatterDataPoints.filter(p => p.category === cat.key);
                    });
                    const minX = scatterNowTime - CHART_WINDOW_MS;
                    sessionScatterChart.options.scales.x.min = minX;
                    sessionScatterChart.options.scales.x.max = scatterNowTime;
                    sessionScatterChart.update('none');
                }
                window.globalSessionScatterChart = sessionScatterChart;
                window.globalScatterDataPoints = scatterDataPoints;
            }

            // Update Table (Only Active Sessions)
            if (activeSessions.length === 0) {
                sessionTbody.innerHTML = '<tr><td colspan="8" style="text-align:center; padding: 30px;">현재 ACTIVE 상태인 세션이 없습니다.</td></tr>';
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
                                        waitHtml = `<div style="display: flex; width: 100px; height: 12px; border-radius: 6px; overflow: hidden; background-color: var(--track-bg);" title="CPU: ${cpu}%, User I/O: ${uio}%, Sys I/O: ${sio}%, Latch: ${latch}%, TX Lock: ${txlock}%, TM Lock: ${tmlock}%, Other: ${other}%"><div style="width: ${cpu}%; background-color: #9b59b6;" title="CPU: ${cpu}%"></div><div style="width: ${uio}%; background-color: #2ecc71;" title="User I/O: ${uio}%"></div><div style="width: ${sio}%; background-color: #e67e22;" title="Sys I/O: ${sio}%"></div><div style="width: ${latch}%; background-color: #808000;" title="Latch: ${latch}%"></div><div style="width: ${txlock}%; background-color: #e91e63;" title="TX Lock: ${txlock}%"></div><div style="width: ${tmlock}%; background-color: #e74c3c;" title="TM Lock: ${tmlock}%"></div><div style="width: ${other}%; background-color: var(--text-muted);" title="Other: ${other}%"></div></div>`;
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
                const checkedSids = Array.from(document.querySelectorAll('.session-checkbox:checked')).map(cb => cb.getAttribute('data-sid'));
                sessionTbody.innerHTML = html;
                document.querySelectorAll('.session-checkbox').forEach(cb => {
                    if (checkedSids.includes(cb.getAttribute('data-sid'))) {
                        cb.checked = true;
                    }
                });
                if (window.dbagentSyncKillButtons) window.dbagentSyncKillButtons();
            }

            renderActiveTransactionsTab(extra.active_transactions);
            renderParallelSessionsTab(extra.parallel_sessions);
            renderPending2pcTab(extra.pending_2pc);

            if (icon) icon.classList.remove('spinning');
        } catch (error) {
            console.error('Error fetching sessions:', error);
            sessionTbody.innerHTML = `<tr><td colspan="7" style="color:#d03b3b; text-align:center; padding: 30px;">데이터를 불러오는 데 실패했습니다: ${error.message}</td></tr>`;
        }
    }

    if (sessionRefreshBtn) {
        sessionRefreshBtn.addEventListener('click', fetchSessions);
    }

    if (sessionToggleBtn) {
        sessionToggleBtn.addEventListener('click', () => {
            if (isSessionAutoRefreshing) {
                clearInterval(sessionTimer);
                isSessionAutoRefreshing = false;
                sessionToggleBtn.textContent = '자동 갱신 시작';
                sessionToggleBtn.classList.remove('danger-btn');
                sessionToggleBtn.classList.add('primary-btn');
                sessionRefreshBtn.disabled = false;
            } else {
                const interval = parseInt(sessionIntervalInput.value) || 5;
                fetchSessions(); // fetch immediately
                sessionTimer = setInterval(fetchSessions, interval * 1000);
                isSessionAutoRefreshing = true;
                sessionToggleBtn.textContent = '자동 갱신 중지';
                sessionToggleBtn.classList.remove('primary-btn');
                sessionToggleBtn.classList.add('danger-btn');
                sessionRefreshBtn.disabled = true;
            }
        });
    }

    // Column set mirrors the Active Session tab's rendering (same fields, same duration/wait bar
    // treatment) - Active Transaction shows the same columns, just scoped to sessions holding a
    // transaction rather than status='ACTIVE'.
    function renderActiveTransactionsTab(rows) {
        const tbody = document.getElementById('sesslist-active-tx-tbody');
        if (!tbody) return;
        if (!rows || rows.length === 0) {
            tbody.innerHTML = '<tr><td colspan="15" style="text-align:center; padding: 30px;">활성 트랜잭션이 없습니다.</td></tr>';
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
                    waitHtml = `<div style="display: flex; width: 100px; height: 12px; border-radius: 6px; overflow: hidden; background-color: var(--track-bg);" title="CPU: ${cpu}%, User I/O: ${uio}%, Sys I/O: ${sio}%, Latch: ${latch}%, TX Lock: ${txlock}%, TM Lock: ${tmlock}%, Other: ${other}%"><div style="width: ${cpu}%; background-color: #9b59b6;" title="CPU: ${cpu}%"></div><div style="width: ${uio}%; background-color: #2ecc71;" title="User I/O: ${uio}%"></div><div style="width: ${sio}%; background-color: #e67e22;" title="Sys I/O: ${sio}%"></div><div style="width: ${latch}%; background-color: #808000;" title="Latch: ${latch}%"></div><div style="width: ${txlock}%; background-color: #e91e63;" title="TX Lock: ${txlock}%"></div><div style="width: ${tmlock}%; background-color: #e74c3c;" title="TM Lock: ${tmlock}%"></div><div style="width: ${other}%; background-color: var(--text-muted);" title="Other: ${other}%"></div></div>`;
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
    }

    function renderParallelSessionsTab(rows) {
        const tbody = document.getElementById('sesslist-parallel-tbody');
        if (!tbody) return;
        if (!rows || rows.length === 0) {
            tbody.innerHTML = '<tr><td colspan="11" style="text-align:center; padding: 30px;">병렬 세션이 없습니다.</td></tr>';
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
    }

    function renderPending2pcTab(rows) {
        const tbody = document.getElementById('sesslist-2pc-tbody');
        if (!tbody) return;
        if (!rows || rows.length === 0) {
            tbody.innerHTML = '<tr><td colspan="9" style="text-align:center; padding: 30px;">보류 중인 2PC 트랜잭션이 없습니다.</td></tr>';
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
        });
    });

    // Called on every DB switch (see the instance-click handler above) - destroys the trend/scatter
    // charts (Chart.js canvases can't just be repointed at new arrays-in-place after a destroy) and
    // swaps their backing history arrays for whatever this nextDbId had stored the last time it was
    // active (dbSessionHistoryCache, saved by saveSessionHistorySnapshot() right before this DB
    // became current) instead of always wiping to empty - otherwise switching to DB B and back to DB
    // A would lose A's graph even though nothing about A's monitoring actually stopped. A DB visited
    // for the first time this session has no cache entry yet, so it still starts empty as before.
    function resetSessionMonitor(nextDbId) {
        if (sessionChart) { sessionChart.destroy(); sessionChart = null; }
        if (sessionScatterChart) { sessionScatterChart.destroy(); sessionScatterChart = null; }

        const cached = nextDbId ? dbSessionHistoryCache.get(nextDbId) : null;
        sessionHistory.labels = cached ? cached.sessionHistory.labels.slice() : [];
        sessionHistory.activeSessions = cached ? cached.sessionHistory.activeSessions.slice() : [];
        sessionHistory.activeTx = cached ? cached.sessionHistory.activeTx.slice() : [];
        sessionHistory.parallel = cached ? cached.sessionHistory.parallel.slice() : [];
        sessionHistory.pending2pc = cached ? cached.sessionHistory.pending2pc.slice() : [];
        sessionHistory.lockWait = cached ? cached.sessionHistory.lockWait.slice() : [];
        scatterDataPoints = cached ? cached.scatterDataPoints.slice() : [];

        if (sessionTbody) sessionTbody.innerHTML = '<tr><td colspan="16" style="text-align:center; padding: 30px;">접속 중...</td></tr>';
        const activeTxTbody = document.getElementById('sesslist-active-tx-tbody');
        if (activeTxTbody) activeTxTbody.innerHTML = '<tr><td colspan="15" style="text-align:center; padding: 30px;">접속 중...</td></tr>';
        const parallelTbody = document.getElementById('sesslist-parallel-tbody');
        if (parallelTbody) parallelTbody.innerHTML = '<tr><td colspan="11" style="text-align:center; padding: 30px;">접속 중...</td></tr>';
        const pending2pcTbody = document.getElementById('sesslist-2pc-tbody');
        if (pending2pcTbody) pending2pcTbody.innerHTML = '<tr><td colspan="9" style="text-align:center; padding: 30px;">접속 중...</td></tr>';

        fetchSessions();
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
                            if (activeSess.length === 0) {
                                tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;">ACTIVE 상태인 세션이 없습니다.</td></tr>';
                                if (window.dbagentSyncKillButtons) window.dbagentSyncKillButtons();
                            } else {
                                const maxDuration = activeSess.reduce((max, s) => Math.max(max, Number(s.duration_time) || 0), 1);
                                let html = '';
                                activeSess.forEach(s => {
                                    const durationVal = s.duration_time !== null ? Number(s.duration_time) : 0;
                                    const durationPct = Math.min((durationVal / maxDuration) * 100, 100);
                                    const durationHtml = s.duration_time !== null ? `<div style="display: flex; align-items: center; gap: 8px;"><div style="flex-grow: 1; background-color: var(--track-bg); height: 8px; border-radius: 4px; overflow: hidden; width: 60px;"><div style="width: ${durationPct}%; height: 100%; background-color: #3987e5; border-radius: 4px;"></div></div><span style="min-width: 30px; text-align: right;">${durationVal}</span></div>` : '-';
                                    html += `<tr class="clickable-session-row" style="cursor:pointer;" data-sid="${s.sid}" data-serial="${s.serial || ''}" data-sql_id="${s.sql_id || ''}">
                                        <td style="text-align:center;" onclick="event.stopPropagation();"><input type="checkbox" class="dash-sess-checkbox" data-sid="${s.sid}" data-serial="${s.serial}"></td>
                                        <td>${s.db_name || '-'}</td>
                                        <td><span class="status-badge online">${s.status}</span></td>
                                        <td>${s.sid}</td>
                                        <td>${s.serial}</td>
                                        <td>${s.server_pid || '-'}</td>
                                        <td>${durationHtml}</td>
                                        <td>${(() => {
                                            let waitHtml = `<div style="color: var(--text-secondary);">-</div>`;
                                            if (s.session_wait_pct && s.session_wait_pct.includes(',')) {
                                                const [cpu, uio, sio, latch, txlock, tmlock, other] = s.session_wait_pct.split(',').map(Number);
                                                if (cpu + uio + sio + latch + txlock + tmlock + other > 0) {
                                                    waitHtml = `<div style="display: flex; width: 100px; height: 12px; border-radius: 6px; overflow: hidden; background-color: var(--track-bg);" title="CPU: ${cpu}%, User I/O: ${uio}%, Sys I/O: ${sio}%, Latch: ${latch}%, TX Lock: ${txlock}%, TM Lock: ${tmlock}%, Other: ${other}%"><div style="width: ${cpu}%; background-color: #9b59b6;" title="CPU: ${cpu}%"></div><div style="width: ${uio}%; background-color: #2ecc71;" title="User I/O: ${uio}%"></div><div style="width: ${sio}%; background-color: #e67e22;" title="Sys I/O: ${sio}%"></div><div style="width: ${latch}%; background-color: #808000;" title="Latch: ${latch}%"></div><div style="width: ${txlock}%; background-color: #e91e63;" title="TX Lock: ${txlock}%"></div><div style="width: ${tmlock}%; background-color: #e74c3c;" title="TM Lock: ${tmlock}%"></div><div style="width: ${other}%; background-color: var(--text-muted);" title="Other: ${other}%"></div></div>`;
                                                }
                                            }
                                            return waitHtml;
                                        })()}</td>
                                        <td>${s.sql_id || '-'}</td>
                                        <td>${s.event_name || '-'}</td>
                                        <td>${s.plan_hash_value || '-'}</td>
                                        <td><div style="max-width:200px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;" title="${s.sql_text || ''}">${s.sql_text || '-'}</div></td>
                                        <td>${s.machine_name || '-'}</td>
                                        <td>${s.osuser || '-'}</td>
                                        <td>${s.username || '-'}</td>
                                        <td>${s.program_name || '-'}</td>
                                    </tr>`;
                                });
                                const checkedSids = Array.from(document.querySelectorAll('.dash-sess-checkbox:checked')).map(cb => cb.getAttribute('data-sid'));
                                tbody.innerHTML = html;
                                document.querySelectorAll('.dash-sess-checkbox').forEach(cb => {
                                    if (checkedSids.includes(cb.getAttribute('data-sid'))) {
                                        cb.checked = true;
                                    }
                                });
                                if (window.dbagentSyncKillButtons) window.dbagentSyncKillButtons();
                            }
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

            // SQL Runner row-limit input: pre-fill with the server default and cap it at the
            // server's hard ceiling, so the UI can't ask for more rows than the backend allows anyway.
            const rowLimitInput = document.getElementById('sqlrunner-rowlimit-input');
            const maxRowsLabel = document.getElementById('sqlrunner-maxrows');
            const maxRowsLimitLabel = document.getElementById('sqlrunner-maxrows-limit');
            if (pollingData.sql_runner_max_rows) {
                if (rowLimitInput) rowLimitInput.value = pollingData.sql_runner_max_rows;
                if (maxRowsLabel) maxRowsLabel.textContent = pollingData.sql_runner_max_rows;
            }
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
    // 것이 빠져 있던 것을 보완).
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


// .app-container has CSS `zoom: 90%` (see style.css), which makes getBoundingClientRect()/clientX
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

// Scatter Brush Selection Logic
(function initScatterBrush() {
    const scatterContainer = document.getElementById('scatter-container');
    const selectionBox = document.getElementById('scatter-selection-box');
    let isDragging = false;
    let startX, startY;

    if (scatterContainer && selectionBox && !window.isScatterBrushBound) {
        window.isScatterBrushBound = true;
        
        scatterContainer.addEventListener('mousedown', (e) => {
            if (e.target.id !== 'session-scatter-chart') return;
            isDragging = true;
            // Freezes the live chart render (see the polling code around window.scatterDragActive)
            // so the axis window doesn't slide out from under the box while the user is still dragging.
            window.scatterDragActive = true;
            const p = scatterPointerToLocal(e, scatterContainer);
            startX = p.x;
            startY = p.y;

            selectionBox.style.left = startX + 'px';
            selectionBox.style.top = startY + 'px';
            selectionBox.style.width = '0px';
            selectionBox.style.height = '0px';
            selectionBox.style.display = 'block';
        });

        window.addEventListener('mousemove', (e) => {
            if (!isDragging) return;
            const p = scatterPointerToLocal(e, scatterContainer);
            const currentX = p.x;
            const currentY = p.y;

            const left = Math.min(startX, currentX);
            const top = Math.min(startY, currentY);
            const width = Math.abs(currentX - startX);
            const height = Math.abs(currentY - startY);
            
            selectionBox.style.left = left + 'px';
            selectionBox.style.top = top + 'px';
            selectionBox.style.width = width + 'px';
            selectionBox.style.height = height + 'px';
        });
        
        window.addEventListener('mouseup', (e) => {
            if (!isDragging) return;
            isDragging = false;
            // Cleared before every early return below so the live chart never stays frozen past the
            // drag that set window.scatterDragActive in mousedown.
            window.scatterDragActive = false;
            selectionBox.style.display = 'none';

            if (!window.globalSessionScatterChart) return;

            const p = scatterPointerToLocal(e, scatterContainer);
            const endX = p.x;
            const endY = p.y;

            if (Math.abs(endX - startX) < 5 && Math.abs(endY - startY) < 5) return;

            // Points render as a 'crossRot' mark (pointRadius 2 + borderWidth 2, ~4px half-extent) but
            // the filter below only tests each point's exact center coordinate. A mark whose crossed
            // lines are visibly inside the drawn selection box was still getting dropped whenever its
            // center sat a few px outside - pad the box by the mark's visual half-extent so "the dot
            // looks selected" and "the dot IS selected" agree.
            const HIT_PAD = 6;
            const left = Math.min(startX, endX) - HIT_PAD;
            const right = Math.max(startX, endX) + HIT_PAD;
            const top = Math.min(startY, endY) - HIT_PAD;
            const bottom = Math.max(startY, endY) + HIT_PAD;

            try {
                const xAxis = window.globalSessionScatterChart.scales.x;
                const yAxis = window.globalSessionScatterChart.scales.y;
                
                const valX1 = xAxis.getValueForPixel(left);
                const valX2 = xAxis.getValueForPixel(right);
                const valY1 = yAxis.getValueForPixel(bottom); 
                const valY2 = yAxis.getValueForPixel(top);    
                
                const minX = Math.min(valX1, valX2);
                const maxX = Math.max(valX1, valX2);
                const minY = Math.min(valY1, valY2);
                const maxY = Math.max(valY1, valY2);
                
                if (!window.globalScatterDataPoints) return;
                
                const selectedPoints = (window.globalScatterDataPoints || []).filter(pt =>
                    pt.x >= minX && pt.x <= maxX && pt.y >= minY && pt.y <= maxY
                );

                // Deduplicate by SID (to show latest for each SID in the box)
                const uniqueSessions = {};
                selectedPoints.forEach(pt => {
                    uniqueSessions[pt.session.sid] = pt.session;
                });
                
                const finalSessions = Object.values(uniqueSessions);
                
                if (finalSessions.length > 0) {
                    showSelectedSessionsPopup(finalSessions);
                }
            } catch(err) {
                console.error("Brush selection error", err);
            }
        });
    }
})();

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
                const response = await fetch(`/api/history_sessions?db_id=${encodeURIComponent(targetDb)}&start_time=${encodeURIComponent(startTime)}&end_time=${encodeURIComponent(endTime)}&users=${encodeURIComponent(selectedUsers)}&token=${encodeURIComponent(getToken())}`);
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
async function loadDbUsers(targetDb) {
    if (!targetDb) return;
    try {
        const response = await fetch(`/api/db_users?db_id=${encodeURIComponent(targetDb)}&token=${encodeURIComponent(getToken())}`);
        let users = await response.json();
        
        const hSelect = document.getElementById('history-users');
        const htSelect = document.getElementById('history-top-users');
        
        if (hSelect) {
            hSelect.style.display = 'inline-block';
            if (Array.isArray(users) && users.length > 0) {
                hSelect.innerHTML = `<option value="">전체 계정(All)</option>` + users.map(u => `<option value="${u}">${u}</option>`).join('');
            } else {
                hSelect.innerHTML = `<option value="">계정 없음 ` + JSON.stringify(users) + `</option>`;
            }
        }
        
        if (htSelect) {
            htSelect.style.display = 'inline-block';
            if (Array.isArray(users) && users.length > 0) {
                htSelect.innerHTML = `<option value="">전체 계정(All)</option>` + users.map(u => `<option value="${u}">${u}</option>`).join('');
            } else {
                htSelect.innerHTML = `<option value="">계정 없음</option>`;
            }
        }
        dbUsersLoaded = true;
    } catch(e) {
        console.error("Failed to load db users", e);
        const hSelect = document.getElementById('history-users');
        if (hSelect) {
            hSelect.innerHTML = `<option value="">Error: ${e.message}</option>`;
            hSelect.style.display = 'inline-block';
        }
    }
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
                analysisEl.innerHTML = `<div style="line-height: 1.6; margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--border-color);">${formatted}</div>`;
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
            if (tunnerCurrentAnalyzeBtn) tunnerCurrentAnalyzeBtn.disabled = true;
            if (tunnerCurrentBindHashInput) tunnerCurrentBindHashInput.value = '';
            if (tunnerCurrentBindCaptureStatus) tunnerCurrentBindCaptureStatus.textContent = '';
            window.renderTunnerCurrentBindFields();
            tunnerCurrentResult.innerHTML = '<div style="color: var(--text-secondary); text-align: center; margin-top: 30px;">쿼리를 입력하고 "1차 성능점검"을 실행하면 실행계획/실측 통계가 여기에 표시됩니다.</div>';
            tunnerCurrentInput.focus();
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

    const PLACEHOLDER_HTML = '<div style="color: var(--text-secondary); text-align: center; margin-top: 30px;">쿼리나 튜닝하고 싶은 상황을 입력하고 분석을 실행해주세요.</div>';

    const escapeHtml = (s) => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

    // System Prompt 가 요구하는 답변 포맷(### 헤더, ```sql 코드블록, **굵게**, - 글머리)만 가볍게
    // HTML로 바꾼다 - 정식 마크다운 파서가 아니라 이 화면에서 실제로 나오는 패턴에 맞춘 간이 변환.
    function formatAdvisorAnswer(text) {
        if (!text) return '';
        let html = escapeHtml(text);
        html = html.replace(/```([a-zA-Z0-9_-]*)\n([\s\S]*?)```/g, (m, lang, code) =>
            `<pre style="background: rgba(0,0,0,0.3); color: #e2e8f0; padding: 12px 14px; border-radius: 6px; overflow-x: auto; white-space: pre; font-family: 'D2Coding', Consolas, monospace; font-size: 0.85rem; margin: 10px 0; line-height: 1.4;">${code}</pre>`);
        html = html.replace(/^#{2,4}\s+(.+)$/gm,
            '<div style="margin: 16px 0 8px; font-weight: 700; color: var(--primary); font-size: 1rem;">$1</div>');
        html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        html = html.replace(/`([^`]+)`/g, '<code style="background: rgba(0,0,0,0.25); padding: 1px 5px; border-radius: 4px; font-family: Consolas, monospace;">$1</code>');
        html = html.replace(/^-\s+(.+)$/gm, '<div style="margin: 3px 0 3px 14px;">• $1</div>');
        html = html.replace(/\n/g, '<br/>');
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
            b.style.color = isActive ? 'var(--primary)' : 'var(--text-secondary)';
            b.style.fontWeight = isActive ? '600' : '500';
        });
    }

    function activateAidbaView(viewId) {
        aidbaSideBtns.forEach(b => {
            b.classList.remove('active');
            b.style.color = 'var(--text-secondary)';
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
            // "AI SQL Tunner" 부모 자체를 누르면(자식이 아니라) 항상 기본 탭(AI SQL 성능분석)으로
            // 들어간다 - 마지막으로 보던 자식을 기억해 뒀다가 엉뚱한 화면(예: AI Current SQL 분석)
            // 으로 복귀하면 사용자가 "리턴이 안 된다"고 느낀다(사용자 리포트, 2026-09-12).
            if (viewId === 'aidba-view-tunner') {
                const defaultTab = document.querySelector('.tunner-tab-btn[data-tunner-tab="tab-tunner-perf"]');
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
    const sqlWriterPreview = document.getElementById('sqlwriter-preview');
    const sqlWriterPreviewBody = document.getElementById('sqlwriter-preview-body');
    const sqlWriterAddBtn = document.getElementById('sqlwriter-add-btn');
    const sqlWriterTableList = document.getElementById('sqlwriter-table-list');
    const sqlWriterTableCount = document.getElementById('sqlwriter-table-count');
    const sqlWriterRequestInput = document.getElementById('sqlwriter-request-input');
    const sqlWriterGenerateBtn = document.getElementById('sqlwriter-generate-btn');
    const sqlWriterClearBtn = document.getElementById('sqlwriter-clear-btn');
    const sqlWriterResult = document.getElementById('sqlwriter-result');
    const SQLWRITER_RESULT_PLACEHOLDER = '<div style="color: var(--text-secondary); text-align: center; margin-top: 30px;">테이블을 추가하고 요청 조건을 입력한 뒤 "SQL 생성"을 눌러주세요.</div>';

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
        let text = `TABLE: ${table.name}\nCOLUMNS:\n${cols}`;
        if (idxLines) text += `\nINDEXES:\n${idxLines}`;
        return text;
    }

    function renderSqlWriterTableList() {
        if (!sqlWriterTableList) return;
        if (sqlWriterTables.length === 0) {
            sqlWriterTableList.innerHTML = '<span style="font-size: 0.82rem; color: var(--text-muted);">아직 추가된 테이블이 없습니다.</span>';
        } else {
            sqlWriterTableList.innerHTML = sqlWriterTables.map((t, i) => `
                <span style="display:inline-flex; align-items:center; gap:6px; padding: 4px 10px; border-radius: 14px; background: var(--bg-card); border: 1px solid var(--border-color); font-size: 0.82rem; font-family: 'Consolas', 'D2Coding', monospace;">
                    ${String(t.name || '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))}
                    <span data-remove-idx="${i}" style="cursor:pointer; color: var(--text-muted); font-weight: bold;" title="제거">×</span>
                </span>`).join('');
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
        const doLookup = () => {
            const tableName = sqlWriterTableInput.value.trim();
            if (!tableName || sqlWriterLookupBtn.disabled) return;
            sqlWriterLookupBtn.disabled = true;
            sqlWriterPendingTable = null;
            if (sqlWriterPreview) sqlWriterPreview.style.display = 'none';
            if (sqlWriterLookupStatus) sqlWriterLookupStatus.textContent = '조회 중...';

            fetch('/api/sqlwriter/table_info', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    db_id: window.currentDbId || '',
                    account: sqlWriterAccountSelect ? sqlWriterAccountSelect.value : '',
                    token: getToken(),
                    table_name: tableName
                })
            })
            .then(res => res.json())
            .then(data => {
                sqlWriterLookupBtn.disabled = false;
                if (!data.success) {
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
        sqlWriterLookupBtn.addEventListener('click', doLookup);
        sqlWriterTableInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') { e.preventDefault(); doLookup(); }
        });
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

    // AI SQL 작성기/AI Current SQL 분석의 답변 렌더러. 두 프롬프트(sql-writer.md, current-sql.md) 모두
    // "### 헤더 + ```코드블록" 마크다운을 강제하므로 같은 렌더러를 쓴다. 먼저 escape 한 뒤 서식을
    // 입히므로, 답변에 섞인 SQL(`a<b` 등)이 태그로 해석돼 이후 내용이 통째로 사라지는 일이 없다.
    function formatAiMarkdownAnswer(text) {
        if (!text) return '';
        const escapeHtml = (s) => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
        let html = escapeHtml(text);
        html = html.replace(/```([a-zA-Z0-9_-]*)\n([\s\S]*?)```/g, (m, lang, code) =>
            `<pre style="background: rgba(0,0,0,0.3); color: #e2e8f0; padding: 12px 14px; border-radius: 6px; overflow-x: auto; white-space: pre; font-family: 'D2Coding', Consolas, monospace; font-size: 0.85rem; margin: 10px 0; line-height: 1.4;">${code}</pre>`);
        html = html.replace(/^#{2,4}\s+(.+)$/gm,
            '<div style="margin: 16px 0 8px; font-weight: 700; color: var(--primary); font-size: 1rem;">$1</div>');
        html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        html = html.replace(/^-\s+(.+)$/gm, '<div style="margin: 3px 0 3px 14px;">• $1</div>');
        html = html.replace(/\n/g, '<br/>');
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
            if (sqlWriterResult) sqlWriterResult.innerHTML = SQLWRITER_RESULT_PLACEHOLDER;
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
                    body: JSON.stringify({ message })
                });
                const data = await response.json();
                const textDiv = pending.querySelector('div');
                if (data.error) {
                    textDiv.textContent = `오류: ${data.error}`;
                } else {
                    textDiv.textContent = data.answer || '(빈 응답)';
                }
            } catch (err) {
                pending.querySelector('div').textContent = '서버 통신 오류가 발생했습니다.';
            } finally {
                chatSendBtn.disabled = false;
                chatInput.focus();
            }
        };

        chatSendBtn.addEventListener('click', doChatSend);
        chatInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') doChatSend();
        });
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
