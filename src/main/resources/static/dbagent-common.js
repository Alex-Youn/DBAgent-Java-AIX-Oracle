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
 * !! index.html 의 .top-nav 에 메뉴를 추가하면 아래 DBAGENT_MENUS 에도 같이 추가할 것.
 *    (누락돼도 opener 가 오라클 화면이면 자동으로 덧붙여지지만, RDB 화면에서 열면 빠진다)
 */
(function () {
    'use strict';

    // DASHBOARD 는 숨김 대상이 아니다(원래도 두 팝업 모두 건너뛰고 있었다).
    var ALWAYS_VISIBLE = ['dashboard'];

    window.DBAGENT_MENUS = [
        { target: 'session', label: 'Current Session' },
        { target: 'history', label: '성능 이력 조회' },
        { target: 'tmlock', label: 'Lock Holder/Waiter Tree' },
        { target: 'tablespace', label: '테이블 스페이스 조회' },
        { target: 'relation', label: 'Table Parent/Child 관계' },
        { target: 'sqlrunner', label: 'SQL 실행' },
        { target: 'aidba', label: 'AI DBA' },
        { target: 'sqltuning', label: 'SQL 정합성/튜닝' }
    ];

    var ENGINE_LABEL = {
        oracle: 'ORACLE',
        mysql: 'MySQL',
        mariadb: 'MariaDB',
        postgres: 'PostgreSQL',
        postgresql: 'PostgreSQL',
        mssql: 'MS SQL'
    };

    /**
     * 메뉴 목록을 돌려준다. 기본은 위 상수이고, opener 가 오라클 메인 화면이면 실제 nav 를 읽어
     * (a) 라벨이 바뀐 경우 따라가고 (b) 상수에 없는 새 메뉴를 뒤에 덧붙인다. 목록에서 빠지는 쪽이
     * 항상 더 위험하므로(=권한이 조용히 풀림) 병합만 하고 제거는 하지 않는다.
     */
    window.dbagentMenuItems = function (openerDoc) {
        var items = window.DBAGENT_MENUS.map(function (m) {
            return { id: m.target, label: m.label };
        });
        var byTarget = {};
        items.forEach(function (m) { byTarget[m.id] = m; });

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
            if (byTarget[target]) {
                if (label) byTarget[target].label = label;
            } else {
                var added = { id: target, label: label || target };
                items.push(added);
                byTarget[target] = added;
            }
        });
        return items;
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
     * 실제 접근 차단은 백엔드(MonitorController/RdbMonitorController 의 canAccessDb)가 한다.
     */
    window.dbagentCrossNavAccess = function (instances) {
        var acc = window.dbagentAccessibleInstances(instances);
        var token = sessionStorage.getItem('dbagent_token') || '';
        acc.allowed = !!token && acc.oracle.length > 0 && acc.rdb.length > 0;
        return acc;
    };

    /** db_type 별 전용 대시보드 페이지. fleet-overview.html/app.js 의 rdbTargetPage()와 같은 매핑. */
    window.dbagentRdbPageFor = function (dbType) {
        if (dbType === 'mysql' || dbType === 'mariadb') return 'mysql-overview-dashboard.html';
        if (dbType === 'postgres' || dbType === 'postgresql') return 'postgres-overview-dashboard.html';
        if (dbType === 'mssql') return 'mssql-overview-dashboard.html';
        return 'rdb-dashboard.html';
    };

    /**
     * 체크박스 그리드를 그린다. 체크됨 = 허용, 체크 해제 = 숨김(hidden_menus/hidden_dbs).
     *
     * 열 수를 항목 수에 맞춰 늘린다 - .menu-checkbox-grid 는 CSS 에서 2열 고정이라 DB/메뉴가
     * 늘어나면 세로로만 길어졌다(사용자 지적, 2026-09-05).
     *
     * 라벨은 textContent 로 넣는다. 예전에는 innerHTML 템플릿에 DB 이름을 그대로 끼워 넣어서
     * databases.json 의 이름에 마크업이 들어가면 그대로 실행됐다.
     */
    window.dbagentRenderCheckboxGrid = function (container, items, hiddenSet, attr) {
        container.innerHTML = '';
        items.forEach(function (item) {
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

            var text = document.createElement('span');
            text.textContent = item.label + (item.missing ? ' (등록되지 않음)' : '');
            row.appendChild(text);

            container.appendChild(row);
        });

        var cols = Math.min(4, Math.max(2, Math.ceil(items.length / 8)));
        container.style.gridTemplateColumns = 'repeat(' + cols + ', max-content)';
    };

    /** dbagentRenderCheckboxGrid 와 같은 열 수 계산 - 창 크기 예측에도 같은 값을 써야 맞는다. */
    function gridCols(count) {
        return Math.min(4, Math.max(2, Math.ceil(count / 8)));
    }

    /**
     * 관리자 팝업을 "열 때" 넘길 window.open 피처 문자열을 항목 수로 계산한다.
     *
     * 예전에는 'width=620,height=760' 처럼 고정값이라 메뉴/DB 가 늘어나도 창은 그대로였고 안에서
     * 스크롤만 생겼다(사용자 지적, 2026-09-05). 열고 난 뒤 dbagentAutoSizePopup() 이 실제 콘텐츠로
     * 다시 맞추지만, resizeTo 를 무시하는 브라우저/환경이 있어서 처음 열 때부터 근사치를 준다.
     * 정확한 픽셀이 아니라 "항목이 늘면 창도 커진다"를 보장하는 게 목적이다.
     */
    window.dbagentAdminPopupFeatures = function (kind) {
        var menus = window.DBAGENT_MENUS.length;
        var dbs = (cachedInstances && cachedInstances.length) || 12;
        var availW = (window.screen && window.screen.availWidth) || 1280;
        var availH = (window.screen && window.screen.availHeight) || 900;

        var mCols = gridCols(menus), dCols = gridCols(dbs);
        var mRows = Math.ceil(menus / mCols), dRows = Math.ceil(dbs / dCols);
        // 한 칸 폭: 메뉴는 라벨만, DB 는 엔진 배지가 붙어 더 넓다. 그리드 padding/border 포함.
        var mBlock = mCols * 180 + (mCols - 1) * 24 + 26;
        var dBlock = dCols * 215 + (dCols - 1) * 24 + 26;

        var w, h;
        if (kind === 'menu') {
            w = mBlock + 80 + 40 + 40;                 // 카드 padding + body padding + 창 테두리
            h = 240 + mRows * 30 + 90;
        } else {                                        // 'account'
            w = mBlock + dBlock + 16 + 80 + 40 + 40;   // 두 목록이 나란히 + 사이 gap
            h = 430 + Math.max(mRows, dRows) * 30 + 90;
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
        var wantW = Math.min(Math.max(naturalW + bodyPad + 8, minW), maxInnerW);

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
                    var wantH = Math.min(Math.max(document.documentElement.scrollHeight + 8, minH), maxInnerH);
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
        step(wantW, Math.min(Math.max(document.documentElement.scrollHeight + 8, minH), maxInnerH), 0);
    };
})();
