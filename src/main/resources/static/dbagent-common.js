/*
 * 여러 화면이 함께 쓰는 공용 유틸.
 *   - 관리자 팝업(account-mgmt.html / menu-visibility.html): 메뉴/DB 체크박스 목록
 *   - 오라클 화면(index.html) / RDB 대시보드: 상호 이동 버튼(RDB, ORA/CLE)의 노출 판정
 *
 * 2026-09-05 이전에는 두 팝업 모두 체크박스 목록을 window.opener 의 DOM 에서 긁어왔다
 * (메뉴는 '.top-nav .nav-item[data-target]', DB 는 '#db-groups-container .instance-item[data-db-id]').
 * 여기서 두 가지 문제가 났다.
 *
 *   1) 2026-09-04 에 오라클 사이드바를 db_type === 'oracle' 인 인스턴스만 그리도록 필터하면서
 *      (app.js 의 oracleInstances) MySQL/MariaDB/PostgreSQL/MS SQL 인스턴스가 계정 권한 화면에서도
 *      통째로 사라졌다. 백엔드 RdbMonitorController 는 canAccessDb() 로 접근을 막고 있는데
 *      hidden_dbs 에 RDB id 를 넣을 방법이 UI 에 없어서, 일반 계정이 모든 RDB 인스턴스에
 *      무조건 접근 가능한 상태였다.
 *
 *   2) 이 팝업들은 오라클 화면뿐 아니라 RDB 대시보드(mysql/postgres/mssql-overview-dashboard.html)
 *      의 톱니 메뉴에서도 열리는데, 그쪽 DOM 에는 '.top-nav' 도 '#db-groups-container' 도 없다.
 *      그래서 목록이 빈 채로 그려지고, 빈 목록은 collectHidden() 에서 "숨긴 항목 없음" 으로 수집되어
 *      계정을 수정·저장하는 순간 기존 메뉴/DB 제한이 조용히 전부 풀렸다.
 *
 * 그래서 목록의 출처를 opener DOM 이 아니라 (메뉴는) 이 파일의 상수, (DB 는) /api/config 로 옮겼다.
 * 어느 화면에서 팝업을 열든 같은 목록이 나온다.
 *
 * !! index.html 의 .top-nav 에 메뉴를 추가하면 아래 DBAGENT_MENU_GROUPS 에도 같이 추가할 것.
 *    (누락돼도 opener 가 오라클 화면이면 자동으로 덧붙여지지만, RDB 화면에서 열면 빠진다)
 */
(function () {
    'use strict';

    // 오라클 화면의 DASHBOARD 는 숨김 대상이 아니다(원래도 두 팝업 모두 건너뛰고 있었다).
    // RDB 대시보드의 DASHBOARD 탭은 id 가 'rdb_dashboard' 로 따로라 이 목록과 겹치지 않는다.
    var ALWAYS_VISIBLE = ['dashboard'];

    // RDB 대시보드(mysql/postgres/mssql/cubrid-overview-dashboard.html)의 탭.
    // menu id → 탭 버튼의 DOM id. 탭을 추가하면 (1) 여기, (2) 아래 DBAGENT_MENU_GROUPS 의 rdb 그룹,
    // (3) 네 대시보드 HTML 의 .view-tabs 마크업, (4) rdb-session-views.js 의 TABS 배열을 함께 고친다.
    //
    // 탭 구성이 엔진마다 다를 수 있다. 그 처리는 <b>해당 페이지 마크업에 버튼/컨테이너를 두지
    // 않는 것만으로</b> 끝난다 - 여기나 rdb-session-views.js 에 엔진 분기를 넣지 말 것
    // (없는 버튼은 visibleTabs/showView 가, 없는 컨테이너는 buildPane 이 알아서 건너뛴다).
    //
    // 지금은 네 대시보드가 같은 탭 4개를 쓰지만 Lock 탭의 성격이 하나 다르다. CUBRID 는 락을 쥔
    // holder 를 JDBC 로 알 수 없어(CubridMonitorService.getLockWaits 주석) 트리 대신 대기 세션
    // 목록으로 그려지고, 그 페이지의 탭 이름도 "Lock 대기 세션" 이다. 화면 코드는 그대로다 -
    // blocker 가 null 인 행을 buildLockTree 가 단독 루트로 세운다.
    var RDB_TAB_BUTTONS = {
        rdb_dashboard: 'tabDetailBtn',
        rdb_sessions: 'tabSessionsBtn',
        rdb_locks: 'tabLocksBtn',
        rdb_capacity: 'tabCapacityBtn'
    };

    /**
     * 메뉴 목록. 오라클 화면 메뉴와 RDB 대시보드 메뉴는 서로 다른 화면의 것이라 섞이면 헷갈리므로
     * 그룹으로 나눠 보여준다(사용자 요청, 2026-09-05).
     *
     * !! index.html 의 .top-nav 에 메뉴를 추가하면 oracle 그룹에도 같이 추가할 것. opener 가 오라클
     *    화면이면 자동 병합되지만 RDB 화면에서 팝업을 열면 빠진다.
     */
    window.DBAGENT_MENU_GROUPS = [
        {
            key: 'oracle',
            label: 'Oracle 메뉴',
            items: [
                { id: 'session', label: 'Current Session' },
                { id: 'history', label: '성능 이력 조회' },
                { id: 'tmlock', label: 'Lock Holder/Waiter Tree' },
                { id: 'tablespace', label: '테이블 스페이스 조회' },
                { id: 'relation', label: 'Table Parent/Child 관계' },
                { id: 'sqlrunner', label: 'SQL 실행' },
                { id: 'aidba', label: 'AI DBA' },
                { id: 'sqltuning', label: 'SQL 정합성/튜닝' }
            ]
        },
        {
            key: 'rdb',
            label: 'RDB 메뉴',
            items: [
                { id: 'rdb_dashboard', label: 'DASHBOARD' },
                { id: 'rdb_sessions', label: '세션 리스트' },
                { id: 'rdb_locks', label: 'Lock Holder/Waiter' },
                { id: 'rdb_capacity', label: '용량 조회' }
            ]
        }
    ];

    var ENGINE_LABEL = {
        oracle: 'ORACLE',
        mysql: 'MySQL',
        mariadb: 'MariaDB',
        postgres: 'PostgreSQL',
        postgresql: 'PostgreSQL',
        mssql: 'MS SQL',
        cubrid: 'CUBRID'
    };

    /**
     * 메뉴 목록을 그룹(Oracle/RDB)으로 돌려준다. 기본은 위 상수이고, opener 가 오라클 메인 화면이면
     * 실제 nav 를 읽어 (a) 라벨이 바뀐 경우 따라가고 (b) 상수에 없는 새 메뉴를 Oracle 그룹 뒤에
     * 덧붙인다. 목록에서 빠지는 쪽이 항상 더 위험하므로(=권한이 조용히 풀림) 병합만 하고 제거는 하지
     * 않는다.
     */
    window.dbagentMenuGroups = function (openerDoc) {
        var groups = window.DBAGENT_MENU_GROUPS.map(function (g) {
            return {
                key: g.key,
                label: g.label,
                items: g.items.map(function (m) { return { id: m.id, label: m.label }; })
            };
        });
        var oracleGroup = groups.filter(function (g) { return g.key === 'oracle'; })[0] || groups[0];
        var byId = {};
        groups.forEach(function (g) {
            g.items.forEach(function (m) { byId[m.id] = m; });
        });

        var navItems = [];
        try {
            if (openerDoc) {
                navItems = Array.prototype.slice.call(
                    openerDoc.querySelectorAll('.top-nav .nav-item[data-target]'));
            }
        } catch (e) {
            // opener 가 이미 닫혔거나 접근 불가 - 상수만으로 진행한다.
            navItems = [];
        }

        navItems.forEach(function (el) {
            var target = el.getAttribute('data-target');
            if (!target || ALWAYS_VISIBLE.indexOf(target) >= 0) return;
            var span = el.querySelector('span');
            var label = span ? String(span.innerText || '').trim() : '';
            if (byId[target]) {
                if (label) byId[target].label = label;
            } else {
                var added = { id: target, label: label || target };
                oracleGroup.items.push(added);
                byId[target] = added;
            }
        });
        return groups;
    };

    /** 그룹 구조를 무시하고 전체 메뉴를 한 줄로 편다. */
    window.dbagentMenuItems = function (openerDoc) {
        var flat = [];
        window.dbagentMenuGroups(openerDoc).forEach(function (g) {
            g.items.forEach(function (m) { flat.push(m); });
        });
        return flat;
    };

    /**
     * 지금 이 브라우저/계정에서 숨겨진 메뉴 id 집합.
     * 브라우저별 설정(localStorage, 메뉴 표시 설정 팝업)과 계정별 설정(sessionStorage, 관리자가
     * 계정 관리에서 지정)의 합집합이다 - app.js 의 applyMenuVisibility() 와 같은 규칙.
     */
    window.dbagentHiddenMenus = function () {
        var out = {};
        [['localStorage', 'dbagent_hidden_menus'], ['sessionStorage', 'dbagent_account_hidden_menus']]
            .forEach(function (pair) {
                try {
                    JSON.parse(window[pair[0]].getItem(pair[1]) || '[]').forEach(function (id) {
                        out[id] = true;
                    });
                } catch (e) { /* 깨진 값은 무시 - 숨김 없음으로 본다 */ }
            });
        return out;
    };

    /**
     * RDB 대시보드의 탭 버튼에 메뉴 표시 설정을 적용한다(오라클 화면의 applyMenuVisibility 대응).
     *
     * 탭이 DASHBOARD 하나뿐이던 시절에는 버튼만 감추면 끝이었다(본문은 탭과 무관하게 늘 보였다).
     * 세션 리스트/Lock 탭이 생기면서 "지금 보고 있는 탭이 숨겨지는" 경우가 실제로 생기므로,
     * 버튼을 감춘 뒤 rdb-session-views.js 에 현재 탭을 다시 맞추라고 알린다 - 안 그러면 탭 버튼은
     * 사라졌는데 그 본문만 남는다.
     */
    window.dbagentApplyRdbMenuVisibility = function () {
        var hidden = window.dbagentHiddenMenus();
        var found = false;
        Object.keys(RDB_TAB_BUTTONS).forEach(function (menuId) {
            var btn = document.getElementById(RDB_TAB_BUTTONS[menuId]);
            if (!btn) return;
            found = true;
            btn.style.display = hidden[menuId] ? 'none' : '';
        });
        if (found && window.dbagentSyncRdbActiveTab) window.dbagentSyncRdbActiveTab();
    };

    /**
     * /api/config 응답을 평평한 인스턴스 목록으로 바꾼다. 오라클/RDB를 가리지 않고 전부 담는다
     * (사이드바 필터는 화면 표시용일 뿐, 권한 판정 대상은 등록된 인스턴스 전체여야 한다).
     * 이미 /api/config 를 받아 둔 화면은 다시 요청하지 말고 이 함수만 쓰면 된다.
     */
    // 마지막으로 읽은 인스턴스 목록. 관리자 팝업을 "열 때" 창 크기를 계산하는 데 쓴다
    // (window.open 은 사용자 제스처 안에서 동기적으로 호출해야 해서 그때 fetch 할 수 없다).
    var cachedInstances = null;
    window.dbagentCachedInstances = function () { return cachedInstances; };

    window.dbagentInstancesFromConfig = function (data) {
        var out = [];
        ((data && data.groups) || []).forEach(function (group) {
            (group.instances || []).forEach(function (inst) {
                if (!inst || !inst.id) return;
                var engine = String(inst.db_type || 'oracle').toLowerCase();
                out.push({
                    id: inst.id,
                    label: inst.name || inst.id,
                    group: group.group_name || '',
                    engine: engine,
                    engineLabel: ENGINE_LABEL[engine] || engine.toUpperCase()
                });
            });
        });
        cachedInstances = out;
        return out;
    };

    /** /api/config 를 받아 dbagentInstancesFromConfig() 로 넘긴다. */
    window.dbagentLoadDbInstances = function () {
        return fetch('/api/config')
            .then(function (res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            })
            .then(window.dbagentInstancesFromConfig);
    };

    window.dbagentIsAdmin = function () {
        return sessionStorage.getItem('dbagent_role') === 'admin';
    };

    /**
     * Fleet Overview 접근 권한(admin 이거나 계정 관리에서 "Fleet Overview 접근 허용" 을 받은 계정).
     * app.js 의 canFleetOverview() 와 같은 판정이다.
     */
    window.dbagentCanFleetOverview = function () {
        return window.dbagentIsAdmin()
            || sessionStorage.getItem('dbagent_fleet_overview') === 'true';
    };

    /**
     * 좌측 상단 "DB InsightFlow" 로고의 Fleet Overview 링크를 권한에 맞춰 잠근다(2026-09-06).
     *
     * FO 버튼(#fleet-overview-btn / #pmmFoBtn)은 권한이 없으면 숨기는데 <b>이 로고 링크에는 게이팅이
     * 전혀 없어서</b>, 버튼은 안 보이는 계정이 로고를 눌러 FO 로 건너간 뒤 "접근 권한이 없습니다"
     * 안내만 보는 상태였다(실측 확인). 데이터가 새는 건 아니지만(그 페이지 자체 방어 + 백엔드 403)
     * 같은 목적지로 가는 통로 둘 중 하나만 막혀 있어 일관성이 깨져 있었다.
     *
     * 로고 자체를 숨기면 사이드바가 허전해지므로 <b>링크만 죽인다</b> - href 를 떼고 안내 문구를 바꾼다.
     */
    window.dbagentGateBrandLink = function (selector) {
        var el = document.querySelector(selector);
        if (!el || window.dbagentCanFleetOverview()) return;
        el.removeAttribute('href');
        el.style.cursor = 'default';
        el.title = 'Fleet Overview 접근 권한이 없습니다';
        el.addEventListener('click', function (e) { e.preventDefault(); });
    };

    // 계정에 설정된 접근 불가 DB. 로그인 응답이 sessionStorage 에 넣어 둔다(app.js 의
    // getAccountHiddenDbs 와 같은 값). 실제 차단은 백엔드 canAccessDb() 가 하고, 여기서는
    // 못 쓰는 진입점을 감추는 용도다.
    window.dbagentAccountHiddenDbs = function () {
        try {
            return new Set(JSON.parse(sessionStorage.getItem('dbagent_account_hidden_dbs') || '[]'));
        } catch (e) {
            return new Set();
        }
    };

    /** dbagentLoadDbInstances() 결과를 계정이 접근 가능한 것만 남겨 계열별로 나눈다. */
    window.dbagentAccessibleInstances = function (instances) {
        var hidden = window.dbagentAccountHiddenDbs();
        var admin = window.dbagentIsAdmin();
        var out = { oracle: [], rdb: [] };
        (instances || []).forEach(function (inst) {
            if (!admin && hidden.has(inst.id)) return;
            (inst.engine === 'oracle' ? out.oracle : out.rdb).push(inst);
        });
        return out;
    };

    /**
     * 오라클 화면의 RDB 버튼 / RDB 대시보드의 ORA/CLE 버튼 노출 조건.
     *
     * 두 계열 모두에 접근 권한이 있는 계정에만 보여준다(사용자 지시, 2026-09-05). 한쪽만 쓰는
     * 계정에게 건너가는 버튼을 보여줘 봐야 도착해서 "해당 DB에 대한 접근 권한이 없습니다" 만
     * 보게 된다. 로그인 전에는 판단 근거(hidden_dbs)가 없으므로 감춘다 - FO 버튼과 같은 방식.
     *
     * <b>다만 "버튼이 안 보이는 이유" 는 두 가지다</b>(2026-09-06 에 구분하기로 함).
     *   emptyOracle/emptyRdb : 그 계열 DB가 <b>아예 등록되어 있지 않다</b>(databases.json 에 없음).
     *                          이 경우는 버튼을 보여주고 눌렀을 때 "등록된 ...가 없습니다" 를 알린다 -
     *                          권한 문제가 아니라 설정이 비어 있는 것이므로 사용자가 알아야 한다.
     *   그 외(권한으로 걸러져 0건)  : 기존대로 버튼 자체를 감춘다(2026-09-05 지시 유지).
     * 둘을 뭉뚱그리면 권한 때문에 못 보는 계정에게 "등록된 DB가 없다" 는 <b>사실과 다른 안내</b>를 하게 된다.
     *
     * 실제 접근 차단은 백엔드(MonitorController/RdbMonitorController 의 canAccessDb)가 한다.
     */
    window.dbagentCrossNavAccess = function (instances) {
        var acc = window.dbagentAccessibleInstances(instances);
        var token = sessionStorage.getItem('dbagent_token') || '';
        // 권한 필터를 거치기 <b>전</b> 개수 - 등록 자체가 없는지 판단하는 근거다.
        var totalOracle = 0, totalRdb = 0;
        (instances || []).forEach(function (inst) {
            if (inst.engine === 'oracle') totalOracle += 1; else totalRdb += 1;
        });
        acc.emptyOracle = totalOracle === 0;
        acc.emptyRdb = totalRdb === 0;
        acc.allowed = !!token && acc.oracle.length > 0 && acc.rdb.length > 0;
        return acc;
    };

    /** db_type 별 전용 대시보드 페이지. fleet-overview.html/app.js 의 rdbTargetPage()와 같은 매핑. */
    window.dbagentRdbPageFor = function (dbType) {
        if (dbType === 'mysql' || dbType === 'mariadb') return 'mysql-overview-dashboard.html';
        if (dbType === 'postgres' || dbType === 'postgresql') return 'postgres-overview-dashboard.html';
        if (dbType === 'mssql') return 'mssql-overview-dashboard.html';
        if (dbType === 'cubrid') return 'cubrid-overview-dashboard.html';
        return 'rdb-dashboard.html';
    };

    /** 항목 수에 맞춘 열 수. 창 크기 예측(dbagentAdminPopupFeatures)도 같은 값을 써야 맞는다. */
    function gridCols(count) {
        return Math.min(4, Math.max(2, Math.ceil(count / 8)));
    }

    /** 체크박스 한 줄(label + input + 선택적 엔진 배지 + 텍스트). */
    function checkboxRow(item, hiddenSet, attr) {
        var row = document.createElement('label');
        if (item.missing) row.title = '등록된 인스턴스 목록에 없는 항목입니다(삭제되었을 수 있음).';
        else if (item.group) row.title = item.group;

        var cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.setAttribute(attr, item.id);
        cb.checked = !hiddenSet.has(item.id);
        row.appendChild(cb);

        if (item.engineLabel) {
            var badge = document.createElement('span');
            badge.className = 'engine-badge engine-' + (item.engine || 'other');
            badge.textContent = item.engineLabel;
            row.appendChild(badge);
        }

        // 라벨은 textContent 로 넣는다. 예전에는 innerHTML 템플릿에 DB 이름을 그대로 끼워 넣어서
        // databases.json 의 이름에 마크업이 들어가면 그대로 실행됐다.
        var text = document.createElement('span');
        text.textContent = item.label + (item.missing ? ' (등록되지 않음)' : '');
        row.appendChild(text);
        return row;
    }

    /**
     * 체크박스 목록을 그린다. 체크됨 = 허용, 체크 해제 = 숨김(hidden_menus/hidden_dbs).
     *
     * - 맨 위에 "전체 선택" 체크박스를 둔다. 기본값이 전부 체크라 일부만 남기려면 하나씩 눌러야 했다
     *   (사용자 요청, 2026-09-05). 일부만 체크된 상태에서는 indeterminate 로 표시한다.
     * - groups 에 label 이 있으면 그룹 머리글을 붙인다(Oracle 메뉴 / RDB 메뉴).
     * - 열 수는 항목 수에 맞춰 2~4열로 늘린다. CSS 의 2열 고정이면 항목이 늘 때 세로로만 길어진다.
     *
     * 전체 선택 체크박스에는 attr 을 붙이지 않는다 - 호출부의 collectHidden 이 `input[attr]` 로만
     * 수집하므로 목록 값에 섞이지 않는다.
     */
    window.dbagentRenderCheckboxList = function (container, groups, hiddenSet, attr) {
        container.innerHTML = '';
        container.classList.add('checkbox-list');

        var masterLabel = document.createElement('label');
        masterLabel.className = 'checkbox-list-all';
        var master = document.createElement('input');
        master.type = 'checkbox';
        masterLabel.appendChild(master);
        var masterText = document.createElement('span');
        masterText.textContent = '전체 선택';
        masterLabel.appendChild(masterText);
        container.appendChild(masterLabel);

        groups.forEach(function (group) {
            if (!group.items.length) return;
            if (group.label) {
                var head = document.createElement('div');
                head.className = 'checkbox-list-group';
                head.textContent = group.label;
                container.appendChild(head);
            }
            var grid = document.createElement('div');
            grid.className = 'checkbox-list-grid';
            grid.style.gridTemplateColumns = 'repeat(' + gridCols(group.items.length) + ', max-content)';
            group.items.forEach(function (item) {
                grid.appendChild(checkboxRow(item, hiddenSet, attr));
            });
            container.appendChild(grid);
        });

        var boxes = Array.prototype.slice.call(container.querySelectorAll('input[' + attr + ']'));
        function syncMaster() {
            var checked = boxes.filter(function (b) { return b.checked; }).length;
            master.checked = checked === boxes.length && boxes.length > 0;
            master.indeterminate = checked > 0 && checked < boxes.length;
        }
        master.addEventListener('change', function () {
            boxes.forEach(function (b) { b.checked = master.checked; });
            master.indeterminate = false;
        });
        boxes.forEach(function (b) { b.addEventListener('change', syncMaster); });
        syncMaster();
    };

    /** 그룹 없이 한 덩어리로 그릴 때(예: 접속 대상 DB 목록). */
    window.dbagentRenderCheckboxGrid = function (container, items, hiddenSet, attr) {
        window.dbagentRenderCheckboxList(container, [{ label: null, items: items }], hiddenSet, attr);
    };

    /**
     * 관리자 팝업을 "열 때" 넘길 window.open 피처 문자열을 항목 수로 계산한다.
     *
     * 예전에는 'width=620,height=760' 처럼 고정값이라 메뉴/DB 가 늘어나도 창은 그대로였고 안에서
     * 스크롤만 생겼다(사용자 지적, 2026-09-05). 열고 난 뒤 dbagentAutoSizePopup() 이 실제 콘텐츠로
     * 다시 맞추지만, resizeTo 를 무시하는 브라우저/환경이 있어서 처음 열 때부터 근사치를 준다.
     * 정확한 픽셀이 아니라 "항목이 늘면 창도 커진다"를 보장하는 게 목적이다.
     */
    window.dbagentAdminPopupFeatures = function (kind) {
        var groups = window.DBAGENT_MENU_GROUPS;
        var dbs = (cachedInstances && cachedInstances.length) || 12;
        var availW = (window.screen && window.screen.availWidth) || 1280;
        var availH = (window.screen && window.screen.availHeight) || 900;

        // 메뉴는 그룹마다 별도 그리드라 열 수/줄 수를 그룹별로 계산해 합친다.
        var mCols = 1, mRows = 0;
        groups.forEach(function (g) {
            var cols = gridCols(g.items.length);
            if (cols > mCols) mCols = cols;
            mRows += Math.ceil(g.items.length / cols);
        });
        var mHeadRows = groups.length;          // 그룹 머리글
        var dCols = gridCols(dbs);
        var dRows = Math.ceil(dbs / dCols);

        // 한 칸 폭: 메뉴는 라벨만, DB 는 엔진 배지가 붙어 더 넓다. 그리드 padding/border 포함.
        var mBlock = mCols * 180 + (mCols - 1) * 24 + 26;
        var dBlock = dCols * 215 + (dCols - 1) * 24 + 26;
        // 전체 선택 1줄 + 그룹 머리글
        var mExtra = 34 + mHeadRows * 34;   // 전체 선택 1줄 + 그룹 머리글(색 막대/여백 포함)
        var dExtra = 34;

        var w, h;
        if (kind === 'menu') {
            w = mBlock + 80 + 40 + 40;                 // 카드 padding + body padding + 창 테두리
            h = 240 + mRows * 30 + mExtra + 90;
        } else {                                        // 'account'
            w = mBlock + dBlock + 16 + 80 + 40 + 40;   // 두 목록이 나란히 + 사이 gap
            h = 430 + Math.max(mRows * 30 + mExtra, dRows * 30 + dExtra) + 90;
        }
        w = Math.max(560, Math.min(w, availW - 80));
        h = Math.max(620, Math.min(h, availH - 100));
        return 'width=' + Math.round(w) + ',height=' + Math.round(h) + ',resizable=yes,scrollbars=yes';
    };

    /**
     * 팝업 창을 콘텐츠 크기에 맞춘다.
     *
     * window.open() 이 고정 크기(예: 620x760)로 열기 때문에 메뉴/DB 가 늘어나도 창은 그대로였고
     * 안쪽에 스크롤만 생겼다(사용자 지적, 2026-09-05).
     *
     * 가로는 scrollWidth 로 잴 수 없다 - 창이 좁으면 콘텐츠가 그 폭에 맞춰 줄바꿈돼서
     * scrollWidth 가 항상 innerWidth 언저리로 나온다. 카드를 잠깐 width:max-content 로 만들어
     * "줄바꿈하지 않았을 때 필요한 폭"을 재고 되돌린다. 세로는 폭이 확정된 뒤 한 프레임 지나야
     * 정확하므로 requestAnimationFrame 뒤에 잰다.
     */
    window.dbagentAutoSizePopup = function (opts) {
        opts = opts || {};
        // 스크립트로 연 팝업이 아니면 브라우저가 resizeTo 를 무시한다(일반 탭에서 URL 직접 연 경우).
        if (!window.opener) return;
        // 창이 만들어지는 도중(load 전)의 resizeTo 도 무시된다 - 2026-09-05 실측: 초기 렌더에서 부른
        // 것은 전혀 반영되지 않고 사용자 클릭 뒤에 부른 것만 반영됐다. 그래서 메뉴 표시 설정 팝업은
        // 콘텐츠가 창보다 넓은 채로 남아 글씨가 팝업 밖으로 삐져나왔다. load 이후로 미뤄서 호출한다.
        if (document.readyState !== 'complete') {
            window.addEventListener('load', function () {
                setTimeout(function () { autoSize(opts); }, 0);
            });
            return;
        }
        autoSize(opts);
    };

    function autoSize(opts) {
        var card = document.querySelector('.login-card') || document.body;
        var minW = opts.minWidth || 520;
        var minH = opts.minHeight || 360;
        var bodyPad = 40; // body { padding: 20px }

        var prevWidth = card.style.width;
        var prevMaxWidth = card.style.maxWidth;
        card.style.maxWidth = 'none';
        card.style.width = 'max-content';
        var naturalW = Math.ceil(card.getBoundingClientRect().width);
        card.style.width = prevWidth;
        card.style.maxWidth = prevMaxWidth;

        var availW = window.screen.availWidth || 1280;
        var availH = window.screen.availHeight || 900;
        var maxInnerW = Math.max(minW, availW - 120);
        var maxInnerH = Math.max(minH, availH - 140);
        // **줄이지는 않는다.** 브라우저는 창이 만들어질 때 한 번만 resizeTo 를 받아주고 그 뒤 호출은
        // 조용히 무시한다(2026-09-05 실측: 로드 시 축소는 먹었는데 이후 확대는 opener/자기 자신 어느
        // 쪽에서 불러도 무효). 계정 관리처럼 뷰마다 필요한 크기가 다른 팝업에서 처음에 작은 메인 뷰에
        // 맞춰 줄여버리면, 목록이 있는 큰 뷰로 바꿀 때 다시 키우지 못해 콘텐츠가 잘린다. 그래서
        // window.open 이 항목 수로 계산해 준 크기(dbagentAdminPopupFeatures)를 하한으로 삼는다.
        var wantW = Math.min(Math.max(naturalW + bodyPad + 8, minW, window.innerWidth), maxInnerW);

        // resizeTo 는 창 바깥(테두리 포함) 크기를 받는데, 테두리 두께를 outerWidth-innerWidth 로
        // 구하면 안 된다 - 브라우저/상황에 따라 outerWidth 가 부모 창 값이나 옛 값을 돌려준다
        // (실측: 팝업 inner 가 544 인데 outer 가 1500 으로 나옴). 그래서 두께를 계산하지 않고,
        // 일단 맞춰 본 뒤 실제 innerWidth/innerHeight 와의 차이만큼 한 번 더 보정한다.
        function step(reqW, reqH, pass) {
            try {
                window.resizeTo(reqW, reqH);
            } catch (e) {
                return; // 창 크기 조정 실패는 기능에 영향 없음 - 스크롤로 볼 수 있다
            }
            requestAnimationFrame(function () {
                try {
                    var wantH = Math.min(
                        Math.max(document.documentElement.scrollHeight + 8, minH, window.innerHeight), maxInnerH);
                    var dw = wantW - window.innerWidth;
                    var dh = wantH - window.innerHeight;
                    if (pass < 2 && (Math.abs(dw) > 2 || Math.abs(dh) > 2)) {
                        step(reqW + dw, reqH + dh, pass + 1);
                        return;
                    }
                    // 커진 창이 화면 밖으로 밀려나면 안쪽으로 당긴다.
                    var left = window.screenX;
                    var top = window.screenY;
                    var newLeft = Math.max(0, Math.min(left, availW - reqW));
                    var newTop = Math.max(0, Math.min(top, availH - reqH));
                    if (newLeft !== left || newTop !== top) window.moveTo(newLeft, newTop);
                } catch (e) { /* 위와 같음 */ }
            });
        }
        step(wantW, Math.min(
            Math.max(document.documentElement.scrollHeight + 8, minH, window.innerHeight), maxInnerH), 0);
    }
})();
