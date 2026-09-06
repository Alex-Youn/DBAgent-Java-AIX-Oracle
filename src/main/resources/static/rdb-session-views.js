/*
 * rdb-session-views.js - RDB 대시보드(mysql/postgres/mssql/cubrid-overview-dashboard.html)의
 * "세션 리스트 / 세션 상세 / Lock Holder-Waiter 트리 / 용량 조회" 화면과 세션 Kill.
 *
 * 근거 문서: "세션리스트 및 세션 정보 조회 쿼리.md" (2026-09-05 구현)
 * 백엔드: /api/rdb/session_list, /api/rdb/session_detail, /api/rdb/lock_waits,
 *         /api/rdb/capacity, /api/rdb/capacity_detail,
 *         /api/rdb/kill_session (POST, 관리자 전용)
 *
 * 갱신은 두 경로다. (1) 필터바의 Interval 셀렉트가 대시보드의 refreshAllPanels() 를 주기적으로
 * 부르고, 그게 dbagentRefreshRdbSessionViews() 를 통해 지금 보고 있는 탭만 다시 읽는다.
 * (2) 각 탭 툴바의 강제 새로고침 버튼은 Interval 설정(off 포함)과 무관하게 즉시 읽는다.
 *
 * !! 용량 조회 탭은 (1)의 대상이 아니다(사용자 지시, 2026-09-05). 용량은 초 단위로 변하지 않는데
 *    쿼리는 전 테이블의 크기를 훑어 무겁다 - 탭을 열 때와 새로고침 버튼을 누를 때만 읽는다.
 *    TABS 의 autoRefresh:false 가 그 표시다.
 *
 * 왜 별도 파일인가
 * ----------------
 * 이 화면은 네 대시보드에 똑같이 들어간다. HTML 네 곳에 같은 표 렌더링 코드를 복사하면 두 저장소
 * (DBAgent-Java / DBAgent-Java-AIX)까지 합쳐 여덟 벌이 되고, 그 중 하나만 고치는 사고가 난다.
 * dbagent-common.js 에 넣지 않은 이유는 그 파일이 index.html 과 관리자 팝업에도 로드되기 때문 -
 * 그쪽 화면에는 필요 없는 코드다.
 *
 * 그래서 HTML 쪽에는 탭 버튼과 빈 컨테이너만 두고(네 파일에서 같은 id), 표·트리·모달·CSS 는
 * 전부 이 파일이 만든다.
 *
 * !! 엔진별 쿼리 차이는 전부 백엔드에서 흡수된다. 서버가 EngineMonitorService 주석의 공통 키로
 *    정규화해서 주므로, 이 파일에는 db_type 분기가 한 줄도 없다.
 *
 * !! SQL 원문은 반드시 textContent 로만 넣는다. 여기 흐르는 문자열은 다른 사용자가 실행 중인
 *    쿼리라 이 앱이 통제할 수 없는 값이다 - innerHTML 로 넣으면 그대로 XSS 다.
 *
 * !! 세션 Kill 은 관리자 전용이다(사용자 지시, 2026-09-05). 여기서 하는 것은 버튼을 감추는
 *    "편의"일 뿐이고, 실제 차단은 서버(RdbMonitorController.killSession 의 authService.isAdmin)
 *    에서만 한다 - sessionStorage 의 role 은 브라우저에서 고칠 수 있다.
 */
(function () {
    'use strict';

    var SESSION_COLUMNS = [
        { key: 'session_id', label: '세션 ID', cls: 'num' },
        { key: 'user', label: '계정' },
        { key: 'host', label: '호스트' },
        { key: 'db', label: 'DB' },
        { key: 'program', label: '프로그램' },
        { key: 'status', label: '상태' },
        { key: 'duration_seconds', label: '경과(초)', cls: 'num' },
        { key: 'wait_event', label: '대기 이벤트' },
        { key: 'query_preview', label: 'SQL', cls: 'sql' }
    ];

    /**
     * 용량 조회(1단) 컬럼. 오라클 "테이블 스페이스 조회" 대응인데, 단위(스키마/데이터베이스)는
     * 엔진마다 달라 응답의 unit 으로 첫 컬럼 머리글을 바꿔 단다.
     *
     * 해당 개념이 없는 엔진은 서버가 null 을 주고 화면은 '-' 로 그린다 - 억지로 채우면 안 된다.
     * (기존 DASHBOARD 탭의 Storage 패널은 total==used 로 채워서 사용률이 항상 100% 로 나온다)
     */
    var CAPACITY_COLUMNS = [
        { key: 'name', label: '이름' },
        { key: 'table_count', label: '테이블 수', cls: 'num' },
        { key: 'data_mb', label: '데이터(MB)', cls: 'num' },
        { key: 'index_mb', label: '인덱스(MB)', cls: 'num' },
        { key: 'used_mb', label: '사용(MB)', cls: 'num' },
        { key: 'total_mb', label: '할당(MB)', cls: 'num' },
        { key: 'free_mb', label: '여유(MB)', cls: 'num' },
        { key: 'used_pct', label: '사용률', cls: 'num pct' }
    ];

    /** 용량 조회(2단) - 저장 단위 하나 안의 테이블별 용량. */
    var CAPACITY_DETAIL_COLUMNS = [
        { key: 'name', label: '테이블' },
        { key: 'row_count', label: '행 수', cls: 'num' },
        { key: 'data_mb', label: '데이터(MB)', cls: 'num' },
        { key: 'index_mb', label: '인덱스(MB)', cls: 'num' },
        { key: 'total_mb', label: '전체(MB)', cls: 'num' },
        { key: 'free_mb', label: '여유(MB)', cls: 'num' }
    ];

    var CSS = [
        /* 탭이 DASHBOARD 하나뿐이던 시절 세 대시보드의 .view-tab 은 cursor:default 에 hover 반응도
           없었다(2026-09-04, "누를 수 있는 것처럼 보이지 않게"). 이제 실제로 전환되는 탭이 생겼으니
           되돌린다. 이 <style> 은 런타임에 head 끝에 붙으므로 각 HTML 의 인라인 규칙보다 뒤에 온다. */
        '.view-tab { cursor:pointer; }',
        '.view-tab:hover { color:var(--text); }',
        '.view-tab.active:hover { color:var(--blue); }',
        '.rsv-toolbar { display:flex; align-items:center; gap:12px; margin-bottom:10px; flex-wrap:wrap; }',
        '.rsv-count { color:var(--text-muted); font-size:12px; }',
        '.rsv-count b { color:var(--text); font-weight:600; }',
        '.rsv-note { color:var(--text-muted); font-size:11.5px; margin:0 0 12px; line-height:1.55; max-width:940px; }',
        '.rsv-spacer { flex:1; }',
        '.rsv-selected { color:var(--text-muted); font-size:12px; }',
        '.rsv-selected b { color:var(--orange); font-weight:700; }',
        /* 강제 새로고침 - Interval 과 무관하게 지금 바로 다시 읽는다. */
        '.rsv-reload-btn { padding:5px 12px; border-radius:4px; border:1px solid var(--border);',
        '  background:#17181c; color:var(--text); cursor:pointer; font-size:12px; font-family:inherit;',
        '  display:inline-flex; align-items:center; gap:6px; }',
        '.rsv-reload-btn:hover:not(:disabled) { border-color:var(--teal); color:var(--teal); }',
        '.rsv-reload-btn:disabled { opacity:.5; cursor:progress; }',
        '.rsv-reload-btn .spin { display:inline-block; }',
        '.rsv-reload-btn.busy .spin { animation:rsv-spin .8s linear infinite; }',
        '@keyframes rsv-spin { from { transform:rotate(0deg); } to { transform:rotate(360deg); } }',
        /* Kill 은 되돌릴 수 없는 동작이라 다른 버튼과 확실히 다르게 보여야 한다. */
        '.rsv-kill-btn { padding:5px 14px; border-radius:4px; border:1px solid #ef4444; background:transparent;',
        '  color:#ef4444; cursor:pointer; font-size:12px; font-family:inherit; font-weight:600; }',
        '.rsv-kill-btn:hover:not(:disabled) { background:#ef4444; color:#fff; }',
        '.rsv-kill-btn:disabled { opacity:.4; cursor:not-allowed; border-color:var(--border); color:var(--text-muted); }',
        '.rsv-table-wrap { border:1px solid var(--border); border-radius:4px; overflow-x:auto; background:var(--bg-panel); }',
        '.rsv-table { width:100%; border-collapse:collapse; font-size:12px; }',
        '.rsv-table th, .rsv-table td { text-align:left; padding:6px 9px; border-bottom:1px solid var(--border); white-space:nowrap; }',
        '.rsv-table th { color:var(--text-muted); font-weight:600; position:sticky; top:0; background:var(--bg-panel); z-index:1; }',
        '.rsv-table td { color:var(--text); }',
        '.rsv-table td.num { text-align:right; font-variant-numeric:tabular-nums; }',
        /* SQL 칸만 길다. 표 전체가 가로로 늘어나지 않도록 여기서만 잘라내고 전문은 상세 팝업으로 본다. */
        '.rsv-table td.sql { max-width:420px; overflow:hidden; text-overflow:ellipsis; color:var(--text-muted); font-family:Consolas,Menlo,monospace; }',
        '.rsv-table tbody tr.clickable { cursor:pointer; }',
        '.rsv-table tbody tr.clickable:hover { background:rgba(87,148,242,.12); }',
        '.rsv-table th.check-col, .rsv-table td.check-col { width:1%; text-align:center; padding-right:4px; }',
        '.rsv-table td.check-col input, .rsv-table th.check-col input { cursor:pointer; margin:0; }',
        /* ---- 용량 조회 ---- */
        /* 사용률은 숫자만으로는 "찼는지" 가 눈에 안 들어와서 칸 안에 막대를 깐다. */
        '.rsv-pct { display:flex; align-items:center; justify-content:flex-end; gap:8px; }',
        '.rsv-pct-bar { width:64px; height:6px; border-radius:3px; background:var(--border); overflow:hidden; flex:none; }',
        '.rsv-pct-fill { height:100%; background:var(--green); }',
        '.rsv-pct-fill.warn { background:var(--orange); }',
        '.rsv-pct-fill.crit { background:#ef4444; }',
        '.rsv-table tfoot td { border-top:1px solid var(--border); border-bottom:none;',
        '  font-weight:700; color:var(--text); background:var(--bg-panel); }',
        '.rsv-empty, .rsv-error { padding:18px; text-align:center; font-size:12.5px; }',
        '.rsv-empty { color:var(--text-muted); }',
        '.rsv-error { color:#ef4444; }',
        /* ---- Lock Holder/Waiter 트리 ---- */
        /* 들여쓰기는 padding 이 아니라 앞쪽 span 폭으로 준다 - td 에 padding 을 걸면 셀 배경까지
           밀려서 hover 하이라이트가 계단처럼 끊긴다. */
        '.rsv-branch { color:var(--text-muted); font-family:Consolas,Menlo,monospace; white-space:pre; }',
        '.rsv-badge { display:inline-block; font-size:9.5px; font-weight:700; letter-spacing:.3px;',
        '  padding:1px 5px; border-radius:3px; margin-right:6px; vertical-align:1px; }',
        '.rsv-badge.holder { background:rgba(255,152,48,.18); color:var(--orange); }',
        '.rsv-badge.waiter { background:rgba(87,148,242,.18); color:var(--blue); }',
        '.rsv-badge.both { background:rgba(239,68,68,.18); color:#ef4444; }',
        '.rsv-sid { font-variant-numeric:tabular-nums; font-weight:600; }',
        '.rsv-cycle { color:#ef4444; font-size:11px; margin-left:6px; }',
        /* ---- 모달 (상세 / Kill 확인 / Kill 결과) ---- */
        '.rsv-modal { display:none; position:fixed; inset:0; background:rgba(0,0,0,.6); align-items:center; justify-content:center; z-index:200; }',
        '.rsv-modal.open { display:flex; }',
        '.rsv-modal-card { background:var(--bg-panel); border:1px solid var(--border); border-radius:8px;',
        '  width:min(860px, 92vw); max-height:86vh; display:flex; flex-direction:column; }',
        '.rsv-modal-card.narrow { width:min(520px, 92vw); }',
        '.rsv-modal-head { display:flex; align-items:center; justify-content:space-between; gap:12px;',
        '  padding:16px 20px; border-bottom:1px solid var(--border); }',
        '.rsv-modal-head h3 { margin:0; font-size:14px; color:var(--text); font-weight:600; }',
        '.rsv-modal-close { background:none; border:none; color:var(--text-muted); font-size:20px; line-height:1; cursor:pointer; padding:0 4px; }',
        '.rsv-modal-close:hover { color:var(--text); }',
        '.rsv-modal-body { padding:16px 20px 20px; overflow:auto; }',
        '.rsv-modal-foot { display:flex; gap:10px; justify-content:flex-end; padding:12px 20px 18px; }',
        '.rsv-btn { padding:7px 16px; border-radius:4px; border:1px solid var(--border); background:#17181c;',
        '  color:var(--text); cursor:pointer; font-size:12.5px; font-family:inherit; }',
        '.rsv-btn:hover { border-color:var(--teal); color:var(--teal); }',
        '.rsv-btn.danger { background:#ef4444; border-color:#ef4444; color:#fff; font-weight:600; }',
        '.rsv-btn.danger:hover { background:#dc2626; border-color:#dc2626; color:#fff; }',
        '.rsv-warn { color:var(--orange); font-size:12.5px; line-height:1.6; margin:0 0 12px; }',
        '.rsv-idlist { margin:0; padding:10px 12px; background:#17181c; border:1px solid var(--border); border-radius:4px;',
        '  color:var(--text); font-family:Consolas,Menlo,monospace; font-size:12px; max-height:30vh; overflow:auto; }',
        '.rsv-result-line { font-size:12.5px; padding:5px 0; border-bottom:1px solid var(--border); }',
        '.rsv-result-line:last-child { border-bottom:none; }',
        '.rsv-result-line .ok { color:var(--green); font-weight:600; }',
        '.rsv-result-line .err { color:#ef4444; font-weight:600; }',
        '.rsv-result-line .why { color:var(--text-muted); margin-left:6px; }',
        '.rsv-fields { display:grid; grid-template-columns:repeat(auto-fill, minmax(240px, 1fr)); gap:8px 18px; margin-bottom:16px; }',
        '.rsv-field { font-size:12px; border-bottom:1px solid var(--border); padding-bottom:5px; }',
        '.rsv-field .k { color:var(--text-muted); margin-right:8px; }',
        '.rsv-field .v { color:var(--text); }',
        '.rsv-sql-head { display:flex; align-items:center; justify-content:space-between; margin-bottom:6px; }',
        '.rsv-sql-head span { color:var(--text-muted); font-size:12px; }',
        '.rsv-copy-btn { padding:4px 10px; border-radius:4px; border:1px solid var(--border); background:#17181c;',
        '  color:var(--text); cursor:pointer; font-size:11.5px; font-family:inherit; }',
        '.rsv-copy-btn:hover { border-color:var(--teal); color:var(--teal); }',
        '.rsv-sql { margin:0; padding:12px; background:#17181c; border:1px solid var(--border); border-radius:4px;',
        '  color:var(--text); font-family:Consolas,Menlo,monospace; font-size:12px; line-height:1.6;',
        '  white-space:pre-wrap; word-break:break-word; max-height:38vh; overflow:auto; }',
        // SQL 자리에 원문 대신 "이 엔진은 제공하지 않는다" 는 안내가 들어갔을 때. 코드가 아니라
        // 설명문이므로 고정폭 글꼴을 벗기고 톤을 낮춰, 쿼리로 잘못 읽히지 않게 한다.
        '.rsv-sql.rsv-sql-note { font-family:inherit; color:var(--text-muted); font-style:italic; }'
    ].join('\n');

    function injectCss() {
        if (document.getElementById('rsv-style')) return;
        var style = document.createElement('style');
        style.id = 'rsv-style';
        style.textContent = CSS;
        document.head.appendChild(style);
    }

    /**
     * 관리자인지. Kill 버튼 노출 여부에만 쓴다 - 진짜 권한 판정은 서버가 한다(파일 머리말 참고).
     * index.html 의 applyMenuVisibility 와 같은 값(sessionStorage 의 dbagent_role)을 본다.
     */
    function isAdmin() {
        try {
            return sessionStorage.getItem('dbagent_role') === 'admin';
        } catch (e) {
            return false;
        }
    }

    /** 값이 비어 있으면 '-'. 0 과 false 는 진짜 값이므로 살려야 한다. */
    function display(v) {
        if (v === null || v === undefined || v === '') return '-';
        if (typeof v === 'number') {
            // 경과 시간은 소수점이 붙는 엔진(pg/mssql)과 정수인 엔진(mysql)이 섞여 있다.
            return Number.isInteger(v) ? String(v) : v.toFixed(2);
        }
        return String(v);
    }

    function el(tag, cls, text) {
        var node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text !== undefined && text !== null) node.textContent = text;
        return node;
    }

    function checkboxCell(sessionId, checkedSet) {
        var td = el('td', 'check-col');
        var cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.className = 'rsv-check';
        cb.setAttribute('data-sid', String(sessionId));
        cb.checked = checkedSet.has(String(sessionId));
        // 행 클릭은 상세 팝업이므로 체크박스 클릭이 거기까지 올라가면 안 된다.
        cb.addEventListener('click', function (e) { e.stopPropagation(); });
        td.addEventListener('click', function (e) { e.stopPropagation(); });
        td.appendChild(cb);
        return td;
    }

    function renderError(container, message) {
        container.textContent = '';
        var wrap = el('div', 'rsv-table-wrap');
        wrap.appendChild(el('div', 'rsv-error', message));
        container.appendChild(wrap);
    }

    // =============================================================================================
    // Lock Holder/Waiter 트리
    //
    // 백엔드는 "대기 세션 1 : 유발 세션 1" 쌍의 평평한 목록을 준다. 실제 블로킹은 A→B→C 처럼
    // 여러 단계로 이어지는 경우가 있으므로(그리고 PostgreSQL 은 pg_blocking_pids() 가 배열이라
    // 한 대기 세션에 유발 세션이 여럿일 수 있으므로) 화면에서 쌍을 이어 붙여 트리로 만든다.
    // 서버 쿼리를 늘리지 않고도 임의 깊이의 체인을 그릴 수 있다.
    // =============================================================================================

    function buildLockTree(rows) {
        var nodes = {};

        function ensure(id) {
            var key = String(id);
            if (!nodes[key]) {
                nodes[key] = {
                    id: key, user: null, host: null, state: null, query: null,
                    waitType: null, waitSec: null,
                    isWaiter: false, isBlocker: false, children: []
                };
            }
            return nodes[key];
        }

        rows.forEach(function (r) {
            if (r.waiter_session_id === null || r.waiter_session_id === undefined) return;
            if (r.blocker_session_id === null || r.blocker_session_id === undefined) return;
            var w = ensure(r.waiter_session_id);
            var b = ensure(r.blocker_session_id);

            // 대기 쪽 정보는 대기 행에만 있다(대기 유형/대기 시간).
            w.isWaiter = true;
            w.user = r.waiter_user;
            w.host = r.waiter_host;
            w.query = r.waiter_query;
            w.waitType = r.wait_type;
            w.waitSec = r.wait_duration_sec;

            b.isBlocker = true;
            // 이 세션이 다른 행에서 대기자로도 나오면 그쪽 정보가 더 풍부하므로 덮어쓰지 않는다.
            if (b.user === null) b.user = r.blocker_user;
            if (b.host === null) b.host = r.blocker_host;
            if (b.query === null) b.query = r.blocker_query;
            if (b.state === null) b.state = r.blocker_state;

            if (b.children.indexOf(w) < 0) b.children.push(w);
        });

        var all = Object.keys(nodes).map(function (k) { return nodes[k]; });
        // 루트 = 아무도 안 기다리는 세션 = 진짜 Holder.
        var roots = all.filter(function (n) { return !n.isWaiter; });
        // 루트가 하나도 없으면 전부가 서로를 기다리는 순환(교착 직전)이다. 그대로 두면 화면이
        // 통째로 비어 "락이 없다" 로 읽히므로, 가장 오래 기다린 세션을 임시 루트로 세워 보여준다.
        var cyclic = false;
        if (roots.length === 0 && all.length > 0) {
            cyclic = true;
            roots = [all.slice().sort(function (a, b) { return (b.waitSec || 0) - (a.waitSec || 0); })[0]];
        }
        return { roots: roots, total: all.length, cyclic: cyclic };
    }

    /** 트리를 화면에 그릴 순서(깊이 우선)로 편다. path 로 순환을 끊는다. */
    function flattenTree(roots) {
        var out = [];
        function walk(node, depth, isLast, prefix, path) {
            var repeated = path.indexOf(node.id) >= 0;
            out.push({ node: node, depth: depth, isLast: isLast, prefix: prefix, repeated: repeated });
            if (repeated) return;   // 순환 - 여기서 끊지 않으면 무한 재귀다
            var nextPath = path.concat([node.id]);
            var nextPrefix = depth === 0 ? '' : prefix + (isLast ? '   ' : '│  ');
            node.children.forEach(function (child, i) {
                walk(child, depth + 1, i === node.children.length - 1, nextPrefix, nextPath);
            });
        }
        roots.forEach(function (root, i) {
            walk(root, 0, i === roots.length - 1, '', []);
        });
        return out;
    }

    // ---------------------------------------------------------------------------------------------

    window.dbagentInitRdbSessionViews = function (options) {
        var opts = options || {};
        var dbId = opts.dbId || '';
        var getToken = opts.getToken || function () { return sessionStorage.getItem('dbagent_token') || ''; };

        var sessionsView = document.getElementById('sessionsView');
        var locksView = document.getElementById('locksView');
        var capacityView = document.getElementById('capacityView');
        // 세션 리스트가 없으면 이 화면에는 세션 탭 자체가 없다는 뜻 - 조용히 물러난다.
        //
        // Lock/용량은 있는 화면만 만든다. 예전에는 셋을 모두 요구했는데, 그러면 <b>탭 구성이 다른
        // 엔진에서 세 탭이 통째로 죽는다</b> - CUBRID 대시보드에는 Lock 탭이 없어(대기 세션을 막고
        // 있는 holder 를 JDBC 로 알 수 없다, CubridMonitorService 주석) locksView 를 두지 않는데,
        // 그 때문에 세션 리스트와 용량 탭까지 초기화되지 않던 것을 2026-09-06 에 고쳤다.
        if (!sessionsView) return;

        injectCss();
        var admin = isAdmin();

        // ---- 화면 뼈대 (HTML 네 곳에 복사하지 않으려고 여기서 만든다) ----
        function buildPane(view, noteText, withKill) {
            // 이 화면에 그 탭이 없으면(엔진마다 탭 구성이 다르다) pane 도 만들지 않는다.
            // 부르는 쪽은 null 을 만나면 그 탭 관련 처리를 통째로 건너뛴다.
            if (!view) return null;
            var note = el('p', 'rsv-note', noteText);
            view.appendChild(note);
            var bar = el('div', 'rsv-toolbar');
            var count = el('div', 'rsv-count');
            bar.appendChild(count);
            bar.appendChild(el('div', 'rsv-spacer'));
            // 강제 새로고침. Interval 을 off 로 둔 사용자와, 방금 조치한 결과를 바로 확인하고
            // 싶은 경우를 위해 모든 탭에 둔다(용량 탭은 자동 갱신이 60초로 묶여 있어 특히 필요하다).
            // 관리자 전용이 아니다 - 읽기 동작일 뿐이다.
            var reloadBtn = el('button', 'rsv-reload-btn');
            reloadBtn.type = 'button';
            reloadBtn.title = 'Interval 설정과 무관하게 지금 바로 다시 조회합니다.';
            reloadBtn.appendChild(el('span', 'spin', '⟳'));
            reloadBtn.appendChild(el('span', '', '새로고침'));
            bar.appendChild(reloadBtn);

            var selected = el('div', 'rsv-selected');
            var killBtn = el('button', 'rsv-kill-btn', '선택 세션 Kill');
            killBtn.type = 'button';
            killBtn.disabled = true;
            if (!admin || withKill === false) {
                // 관리자가 아니면 버튼 자체를 없앤다. 서버가 어차피 403 을 주지만, 누를 수 있게
                // 두면 "권한 없음" 을 눌러 보고서야 알게 된다.
                selected.style.display = 'none';
                killBtn.style.display = 'none';
            }
            bar.appendChild(selected);
            bar.appendChild(killBtn);
            view.appendChild(bar);
            var body = el('div');
            view.appendChild(body);
            return { note: note, count: count, selected: selected, killBtn: killBtn,
                     reloadBtn: reloadBtn, body: body };
        }

        var sessionsPane = buildPane(sessionsView,
            '지금 실행 중인 세션만 보여줍니다(유휴 세션과 이 모니터링 쿼리 자신은 제외). ' +
            '행을 클릭하면 그 세션이 실행 중인 SQL 전문을 볼 수 있습니다.' +
            (admin ? ' 왼쪽 체크박스로 세션을 골라 강제 종료할 수 있습니다.' : ''));

        var locksPane = buildPane(locksView,
            '락을 쥔 세션(Holder)을 위에, 그 락을 기다리는 세션(Waiter)을 아래에 붙여 트리로 보여줍니다. ' +
            'A가 B를, B가 C를 막는 다단계 체인도 그대로 이어집니다. ' +
            '맨 위 Holder를 처리하면 아래가 한꺼번에 풀립니다. 행을 클릭하면 SQL 전문을 볼 수 있습니다.' +
            (admin ? ' 왼쪽 체크박스로 세션을 골라 강제 종료할 수 있습니다.' : ''));

        // 용량 조회는 Kill 대상이 아니다 - 버튼을 붙이지 않는다.
        // 안내 문구는 엔진마다 달라(서버의 note) 적재 시점에 채운다.
        var capacityPane = buildPane(capacityView, '', false);

        // 자동 새로고침이 표를 다시 그려도 체크가 풀리지 않도록 선택 상태를 화면 밖에 들고 있는다.
        // (오라클 화면의 TM Lock 탭이 이 처리가 빠져 5초마다 체크가 풀리던 버그가 있었다)
        var checkedSessions = new Set();
        var checkedLocks = new Set();

        // ---- 공용 모달 ----
        var modal = el('div', 'rsv-modal');
        var card = el('div', 'rsv-modal-card');
        var head = el('div', 'rsv-modal-head');
        var headTitle = el('h3', '', '세션 상세');
        var closeBtn = el('button', 'rsv-modal-close', '×');
        closeBtn.type = 'button';
        closeBtn.title = '닫기';
        head.appendChild(headTitle);
        head.appendChild(closeBtn);
        var body = el('div', 'rsv-modal-body');
        var foot = el('div', 'rsv-modal-foot');
        card.appendChild(head);
        card.appendChild(body);
        card.appendChild(foot);
        modal.appendChild(card);
        document.body.appendChild(modal);

        function closeModal() {
            modal.classList.remove('open');
            foot.textContent = '';
            card.classList.remove('narrow');
        }
        closeBtn.addEventListener('click', closeModal);
        modal.addEventListener('click', function (e) { if (e.target === modal) closeModal(); });
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && modal.classList.contains('open')) closeModal();
        });

        function openModal(title, narrow) {
            headTitle.textContent = title;
            body.textContent = '';
            foot.textContent = '';
            card.classList.toggle('narrow', !!narrow);
            modal.classList.add('open');
        }

        function api(path, extra) {
            return '/api/rdb/' + path + '?db_id=' + encodeURIComponent(dbId) +
                '&token=' + encodeURIComponent(getToken()) + (extra || '');
        }

        // ---- 세션 상세 ----
        function openDetail(sessionId) {
            if (sessionId === null || sessionId === undefined || sessionId === '') return;
            openModal('세션 상세 - ' + sessionId);
            body.appendChild(el('div', 'rsv-empty', '불러오는 중...'));

            fetch(api('session_detail', '&session_id=' + encodeURIComponent(sessionId)))
                .then(function (res) { return res.json(); })
                .then(function (data) {
                    body.textContent = '';
                    if (data && data.error) {
                        body.appendChild(el('div', 'rsv-error', data.error));
                        return;
                    }
                    if (!data || !data.found) {
                        // 목록을 그린 뒤 클릭하기까지의 사이에 세션이 끝나면 여기로 온다.
                        body.appendChild(el('div', 'rsv-empty',
                            '이 세션은 이미 종료되었습니다. 목록을 새로고침해주세요.'));
                        return;
                    }
                    var fields = el('div', 'rsv-fields');
                    (data.fields || []).forEach(function (f) {
                        var item = el('div', 'rsv-field');
                        item.appendChild(el('span', 'k', f.label));
                        item.appendChild(el('span', 'v', display(f.value)));
                        fields.appendChild(item);
                    });
                    body.appendChild(fields);

                    var sqlHead = el('div', 'rsv-sql-head');
                    sqlHead.appendChild(el('span', '', '실행 중인 SQL 전문'));
                    var copyBtn = el('button', 'rsv-copy-btn', '복사');
                    copyBtn.type = 'button';
                    sqlHead.appendChild(copyBtn);
                    body.appendChild(sqlHead);

                    var sqlText = data.sql_text || '';
                    // SQL 이 비어 있는 이유는 두 가지고, 둘을 구별해야 한다.
                    //   (1) 정말로 지금 실행 중인 SQL 이 없다 - 기본 문구
                    //   (2) 그 엔진이 SQL 원문을 제공하지 않는다 - 서버가 sql_text_note 로 사유를 준다
                    //       (CUBRID 가 이 경우다. 기본 문구를 그대로 쓰면 "쿼리가 없다" 로 잘못 읽힌다.)
                    // 엔진 이름으로 분기하지 않고 서버가 준 사유를 그대로 띄운다.
                    var pre = el('pre', 'rsv-sql',
                        sqlText || data.sql_text_note || '(실행 중인 SQL이 없습니다)');
                    if (!sqlText && data.sql_text_note) pre.classList.add('rsv-sql-note');
                    body.appendChild(pre);

                    function selectPre() {
                        var range = document.createRange();
                        range.selectNodeContents(pre);
                        var sel = window.getSelection();
                        sel.removeAllRanges();
                        sel.addRange(range);
                        copyBtn.textContent = 'Ctrl+C';
                        setTimeout(function () { copyBtn.textContent = '복사'; }, 2500);
                    }
                    copyBtn.addEventListener('click', function () {
                        if (!sqlText) return;
                        // 클립보드 API 는 비보안 origin(사내 http)에서 없을 수 있다 - 그때는
                        // 조용히 실패하지 말고 사용자가 직접 고를 수 있게 선택해준다.
                        if (navigator.clipboard && navigator.clipboard.writeText) {
                            navigator.clipboard.writeText(sqlText).then(function () {
                                copyBtn.textContent = '복사됨';
                                setTimeout(function () { copyBtn.textContent = '복사'; }, 1500);
                            }, selectPre);
                        } else {
                            selectPre();
                        }
                    });
                })
                .catch(function (err) {
                    body.textContent = '';
                    body.appendChild(el('div', 'rsv-error', err.message));
                });
        }

        // ---- 세션 Kill ----
        //
        // 확인창과 결과창을 window.confirm / window.alert 대신 이 페이지의 모달로 만든다.
        // 브라우저 기본 대화상자는 페이지의 모든 이벤트를 막아 자동화 검증이 그 지점에서 멈추고,
        // 무엇보다 "무엇을 죽이는지" 를 목록으로 보여줄 수가 없다.

        function confirmKill(ids, onDone) {
            openModal('세션 강제 종료 확인', true);
            body.appendChild(el('p', 'rsv-warn',
                '선택한 ' + ids.length + '개 세션을 강제 종료합니다. ' +
                '진행 중인 트랜잭션은 롤백되며 되돌릴 수 없습니다.'));
            body.appendChild(el('pre', 'rsv-idlist', '세션 ID: ' + ids.join(', ')));
            var cancel = el('button', 'rsv-btn', '취소');
            cancel.type = 'button';
            var go = el('button', 'rsv-btn danger', '강제 종료');
            go.type = 'button';
            cancel.addEventListener('click', closeModal);
            go.addEventListener('click', function () { onDone(); });
            foot.appendChild(cancel);
            foot.appendChild(go);
        }

        function runKill(ids, checkedSet, reload) {
            openModal('세션 강제 종료', true);
            body.appendChild(el('div', 'rsv-empty', '처리 중...'));
            fetch('/api/rdb/kill_session?db_id=' + encodeURIComponent(dbId), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessions: ids, token: getToken() })
            })
                .then(function (res) { return res.json(); })
                .then(function (data) {
                    body.textContent = '';
                    if (data && data.error) {
                        body.appendChild(el('div', 'rsv-error', data.error));
                    } else {
                        var results = (data && data.results) || [];
                        var ok = 0, fail = 0;
                        results.forEach(function (r) { r.status === 'killed' ? ok++ : fail++; });
                        // 오라클 화면과 같은 규칙 - 건별 status 를 보지 않고 "전송했습니다" 만
                        // 띄우면 실패를 성공으로 오해한다(2026-08-30에 실제로 겪은 버그).
                        body.appendChild(el('p', 'rsv-warn', '성공 ' + ok + '건 / 실패 ' + fail + '건'));
                        results.forEach(function (r) {
                            var line = el('div', 'rsv-result-line');
                            line.appendChild(el('span', '', '세션 ' + display(r.session_id) + '  '));
                            line.appendChild(el('span', r.status === 'killed' ? 'ok' : 'err',
                                r.status === 'killed' ? '종료됨' : '실패'));
                            if (r.message) line.appendChild(el('span', 'why', r.message));
                            body.appendChild(line);
                        });
                        // 죽었든 실패했든 선택은 비운다 - 남겨두면 다음 새로고침에서 이미 사라진
                        // 세션 id 가 계속 선택된 것처럼 보인다.
                        checkedSet.clear();
                    }
                    var close = el('button', 'rsv-btn', '닫기');
                    close.type = 'button';
                    close.addEventListener('click', closeModal);
                    foot.appendChild(close);
                    reload();
                })
                .catch(function (err) {
                    body.textContent = '';
                    body.appendChild(el('div', 'rsv-error', err.message));
                    var close = el('button', 'rsv-btn', '닫기');
                    close.type = 'button';
                    close.addEventListener('click', closeModal);
                    foot.appendChild(close);
                });
        }

        function wireKillButton(pane, checkedSet, reload) {
            if (!pane || !pane.killBtn) return;   // 없는 탭이거나 Kill 이 없는 탭(용량 조회)
            pane.killBtn.addEventListener('click', function () {
                var ids = Array.from(checkedSet);
                if (ids.length === 0) return;
                confirmKill(ids, function () { runKill(ids, checkedSet, reload); });
            });
        }

        /** 선택 개수 표시와 Kill 버튼 활성/비활성을 현재 선택 집합에 맞춘다. */
        function syncSelection(pane, checkedSet) {
            var n = checkedSet.size;
            pane.selected.textContent = '';
            if (n > 0) {
                pane.selected.appendChild(el('span', '', '선택 '));
                pane.selected.appendChild(el('b', '', String(n)));
                pane.selected.appendChild(el('span', '', '건'));
            }
            pane.killBtn.disabled = (n === 0);
        }

        /** 표 안의 체크박스를 선택 집합과 연결한다(그린 직후 한 번 호출). */
        function wireChecks(scope, pane, checkedSet, headCheck) {
            var boxes = Array.prototype.slice.call(scope.querySelectorAll('.rsv-check'));
            function syncHead() {
                if (!headCheck) return;
                var checked = boxes.filter(function (b) { return b.checked; }).length;
                headCheck.checked = boxes.length > 0 && checked === boxes.length;
                headCheck.indeterminate = checked > 0 && checked < boxes.length;
            }
            boxes.forEach(function (cb) {
                cb.addEventListener('change', function () {
                    var sid = cb.getAttribute('data-sid');
                    if (cb.checked) checkedSet.add(sid); else checkedSet.delete(sid);
                    syncSelection(pane, checkedSet);
                    syncHead();
                });
            });
            if (headCheck) {
                headCheck.addEventListener('change', function () {
                    boxes.forEach(function (cb) {
                        cb.checked = headCheck.checked;
                        var sid = cb.getAttribute('data-sid');
                        if (cb.checked) checkedSet.add(sid); else checkedSet.delete(sid);
                    });
                    headCheck.indeterminate = false;
                    syncSelection(pane, checkedSet);
                });
            }
            syncHead();
            // 화면에서 사라진 세션(이미 끝난 것)은 선택 집합에서도 지운다.
            var live = {};
            boxes.forEach(function (cb) { live[cb.getAttribute('data-sid')] = true; });
            Array.from(checkedSet).forEach(function (sid) { if (!live[sid]) checkedSet.delete(sid); });
            syncSelection(pane, checkedSet);
        }

        // ---- 세션 리스트 ----
        function renderSessions(container, rows) {
            container.textContent = '';
            var wrap = el('div', 'rsv-table-wrap');
            var table = el('table', 'rsv-table');

            var thead = document.createElement('thead');
            var htr = document.createElement('tr');
            var headCheck = null;
            if (admin) {
                var hth = el('th', 'check-col');
                headCheck = document.createElement('input');
                headCheck.type = 'checkbox';
                headCheck.title = '전체 선택';
                hth.appendChild(headCheck);
                htr.appendChild(hth);
            }
            SESSION_COLUMNS.forEach(function (col) { htr.appendChild(el('th', '', col.label)); });
            thead.appendChild(htr);
            table.appendChild(thead);

            var tbody = document.createElement('tbody');
            rows.forEach(function (row) {
                var tr = document.createElement('tr');
                tr.className = 'clickable';
                tr.title = '클릭하면 이 세션의 SQL 전문을 봅니다.';
                tr.addEventListener('click', function () { openDetail(row.session_id); });
                if (admin) tr.appendChild(checkboxCell(row.session_id, checkedSessions));
                SESSION_COLUMNS.forEach(function (col) {
                    var td = el('td', col.cls || '', display(row[col.key]));
                    // 잘린 SQL 은 마우스를 올리면 미리보기 전체가 보이게 한다(전문은 상세 팝업).
                    if (col.cls && col.cls.indexOf('sql') >= 0 && row[col.key]) {
                        td.title = String(row[col.key]);
                    }
                    tr.appendChild(td);
                });
                tbody.appendChild(tr);
            });
            table.appendChild(tbody);
            wrap.appendChild(table);
            container.appendChild(wrap);
            if (rows.length === 0) wrap.appendChild(el('div', 'rsv-empty', '실행 중인 세션이 없습니다.'));
            if (admin) wireChecks(container, sessionsPane, checkedSessions, headCheck);
        }

        // ---- Lock Holder/Waiter 트리 ----
        function renderLockTree(container, rows) {
            container.textContent = '';
            var tree = buildLockTree(rows);
            var flat = flattenTree(tree.roots);

            var wrap = el('div', 'rsv-table-wrap');
            var table = el('table', 'rsv-table');

            var thead = document.createElement('thead');
            var htr = document.createElement('tr');
            if (admin) htr.appendChild(el('th', 'check-col', ''));
            ['세션', '계정', '호스트', '상태 / 대기 유형', '대기(초)', 'SQL'].forEach(function (label, i) {
                htr.appendChild(el('th', i === 4 ? 'num' : '', label));
            });
            thead.appendChild(htr);
            table.appendChild(thead);

            var tbody = document.createElement('tbody');
            flat.forEach(function (item) {
                var n = item.node;
                var tr = document.createElement('tr');
                tr.className = 'clickable';
                tr.title = '클릭하면 이 세션의 SQL 전문을 봅니다.';
                tr.addEventListener('click', function () { openDetail(n.id); });
                if (admin) tr.appendChild(checkboxCell(n.id, checkedLocks));

                // 세션 칸 = 트리 가지 + 역할 배지 + SID
                var sidTd = el('td');
                if (item.depth > 0) {
                    sidTd.appendChild(el('span', 'rsv-branch', item.prefix + (item.isLast ? '└─ ' : '├─ ')));
                }
                var role = n.isWaiter && n.isBlocker ? 'both' : (n.isBlocker ? 'holder' : 'waiter');
                var roleLabel = role === 'both' ? 'BOTH' : (role === 'holder' ? 'HOLDER' : 'WAITER');
                var badge = el('span', 'rsv-badge ' + role, roleLabel);
                badge.title = role === 'both'
                    ? '다른 세션을 막고 있으면서 자신도 다른 세션을 기다리는 중입니다(체인 중간).'
                    : (role === 'holder' ? '락을 쥐고 있는 세션입니다.' : '락을 기다리는 세션입니다.');
                sidTd.appendChild(badge);
                sidTd.appendChild(el('span', 'rsv-sid', display(n.id)));
                if (item.repeated) {
                    sidTd.appendChild(el('span', 'rsv-cycle', '↻ 순환'));
                }
                tr.appendChild(sidTd);

                tr.appendChild(el('td', '', display(n.user)));
                tr.appendChild(el('td', '', display(n.host)));
                // 대기 중이면 무엇을 기다리는지가, 아니면 지금 상태가 궁금한 값이다.
                tr.appendChild(el('td', '', display(n.isWaiter ? n.waitType : n.state)));
                tr.appendChild(el('td', 'num', n.isWaiter ? display(n.waitSec) : '-'));
                var sqlTd = el('td', 'sql', display(n.query));
                if (n.query) sqlTd.title = String(n.query);
                tr.appendChild(sqlTd);

                tbody.appendChild(tr);
            });
            table.appendChild(tbody);
            wrap.appendChild(table);
            container.appendChild(wrap);

            if (flat.length === 0) {
                wrap.appendChild(el('div', 'rsv-empty', '대기 중인 락이 없습니다.'));
            } else if (tree.cyclic) {
                container.appendChild(el('p', 'rsv-warn',
                    '⚠ 서로가 서로를 기다리는 순환 구조입니다(교착 가능성). 시작점을 특정할 수 없어 ' +
                    '가장 오래 기다린 세션을 맨 위에 두었습니다.'));
            }
            if (admin) wireChecks(container, locksPane, checkedLocks, null);
            return tree;
        }

        // ---- 용량 조회 ----
        //
        // 오라클 "테이블 스페이스 조회" 대응. 1단(저장 단위) 행을 클릭하면 2단(테이블별)을 모달로 연다.
        // 단위 이름(스키마/데이터베이스)과 안내 문구는 서버 응답의 unit/note 를 그대로 쓴다 -
        // 그래야 이 파일에 db_type 분기가 생기지 않는다.

        /** MB 값을 천단위 구분해 소수 2자리로. null 은 '-'. */
        function mb(v) {
            if (v === null || v === undefined || v === '') return '-';
            var n = Number(v);
            if (isNaN(n)) return String(v);
            return n.toLocaleString('ko-KR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        }

        function intOrDash(v) {
            if (v === null || v === undefined || v === '') return '-';
            var n = Number(v);
            if (isNaN(n)) return String(v);
            return n.toLocaleString('ko-KR');
        }

        /** 사용률 칸: 숫자 + 막대. 값이 없으면(할당 개념이 없는 엔진) '-'. */
        function pctCell(td, v) {
            if (v === null || v === undefined || v === '') {
                td.textContent = '-';
                td.title = '이 엔진에는 미리 할당해 두는 개념이 없어 사용률을 계산할 수 없습니다.';
                return;
            }
            var n = Number(v);
            td.textContent = '';
            var wrap = el('div', 'rsv-pct');
            wrap.appendChild(el('span', '', n.toFixed(2) + '%'));
            var track = el('div', 'rsv-pct-bar');
            var fill = el('div', 'rsv-pct-fill' + (n >= 90 ? ' crit' : (n >= 75 ? ' warn' : '')));
            fill.style.width = Math.min(100, Math.max(0, n)) + '%';
            track.appendChild(fill);
            wrap.appendChild(track);
            td.appendChild(wrap);
        }

        function capacityCell(col, row) {
            var td = el('td', col.cls || '');
            if (col.key === 'used_pct') {
                pctCell(td, row[col.key]);
            } else if (col.key === 'name') {
                td.textContent = display(row[col.key]);
            } else if (col.key === 'table_count' || col.key === 'row_count') {
                td.textContent = intOrDash(row[col.key]);
            } else {
                td.textContent = mb(row[col.key]);
            }
            return td;
        }

        function sumOf(rows, key) {
            var any = false, sum = 0;
            rows.forEach(function (r) {
                var v = r[key];
                if (v === null || v === undefined || v === '') return;
                var n = Number(v);
                if (isNaN(n)) return;
                any = true;
                sum += n;
            });
            return any ? sum : null;
        }

        function renderCapacity(container, data) {
            var rows = (data && data.rows) || [];
            var unit = (data && data.unit) || '이름';
            container.textContent = '';

            var wrap = el('div', 'rsv-table-wrap');
            var table = el('table', 'rsv-table');

            var thead = document.createElement('thead');
            var htr = document.createElement('tr');
            CAPACITY_COLUMNS.forEach(function (col) {
                htr.appendChild(el('th', col.cls || '', col.key === 'name' ? unit : col.label));
            });
            thead.appendChild(htr);
            table.appendChild(thead);

            var tbody = document.createElement('tbody');
            rows.forEach(function (row) {
                var tr = document.createElement('tr');
                tr.className = 'clickable';
                tr.title = '클릭하면 이 ' + unit + ' 안의 테이블별 용량을 봅니다.';
                tr.addEventListener('click', function () { openCapacityDetail(row.name, unit); });
                CAPACITY_COLUMNS.forEach(function (col) { tr.appendChild(capacityCell(col, row)); });
                tbody.appendChild(tr);
            });
            table.appendChild(tbody);

            // 합계 행. 오라클 테이블스페이스 화면의 "전체 할당량/사용량" 요약에 대응한다.
            // 사용률은 더할 수 있는 값이 아니라 전체 사용/전체 할당으로 다시 계산한다.
            if (rows.length > 0) {
                var tfoot = document.createElement('tfoot');
                var ftr = document.createElement('tr');
                var totalUsed = sumOf(rows, 'used_mb');
                var totalAlloc = sumOf(rows, 'total_mb');
                CAPACITY_COLUMNS.forEach(function (col) {
                    if (col.key === 'name') {
                        ftr.appendChild(el('td', '', '합계 (' + rows.length + '개)'));
                    } else if (col.key === 'used_pct') {
                        var td = el('td', col.cls || '');
                        pctCell(td, (totalAlloc && totalAlloc > 0 && totalUsed !== null)
                            ? (totalUsed / totalAlloc) * 100 : null);
                        ftr.appendChild(td);
                    } else if (col.key === 'table_count') {
                        ftr.appendChild(el('td', col.cls || '', intOrDash(sumOf(rows, col.key))));
                    } else {
                        ftr.appendChild(el('td', col.cls || '', mb(sumOf(rows, col.key))));
                    }
                });
                tfoot.appendChild(ftr);
                table.appendChild(tfoot);
            }

            wrap.appendChild(table);
            container.appendChild(wrap);
            if (rows.length === 0) {
                wrap.appendChild(el('div', 'rsv-empty', '조회된 ' + unit + '가 없습니다.'));
            }
        }

        function openCapacityDetail(scope, unit) {
            if (scope === null || scope === undefined || scope === '') return;
            openModal(unit + ' 용량 상세 - ' + scope);
            body.appendChild(el('div', 'rsv-empty', '불러오는 중...'));

            fetch(api('capacity_detail', '&scope=' + encodeURIComponent(scope)))
                .then(function (res) { return res.json(); })
                .then(function (data) {
                    body.textContent = '';
                    if (data && data.error) {
                        body.appendChild(el('div', 'rsv-error', data.error));
                        return;
                    }
                    var rows = (data && data.rows) || [];
                    var wrap = el('div', 'rsv-table-wrap');
                    var table = el('table', 'rsv-table');
                    var thead = document.createElement('thead');
                    var htr = document.createElement('tr');
                    CAPACITY_DETAIL_COLUMNS.forEach(function (col) {
                        htr.appendChild(el('th', col.cls || '', col.label));
                    });
                    thead.appendChild(htr);
                    table.appendChild(thead);
                    var tbody = document.createElement('tbody');
                    rows.forEach(function (row) {
                        var tr = document.createElement('tr');
                        CAPACITY_DETAIL_COLUMNS.forEach(function (col) {
                            tr.appendChild(capacityCell(col, row));
                        });
                        tbody.appendChild(tr);
                    });
                    table.appendChild(tbody);
                    if (rows.length > 0) {
                        var tfoot = document.createElement('tfoot');
                        var ftr = document.createElement('tr');
                        CAPACITY_DETAIL_COLUMNS.forEach(function (col) {
                            if (col.key === 'name') {
                                ftr.appendChild(el('td', '', '합계 (' + rows.length + '개)'));
                            } else if (col.key === 'row_count') {
                                ftr.appendChild(el('td', col.cls || '', intOrDash(sumOf(rows, col.key))));
                            } else {
                                ftr.appendChild(el('td', col.cls || '', mb(sumOf(rows, col.key))));
                            }
                        });
                        tfoot.appendChild(ftr);
                        table.appendChild(tfoot);
                    }
                    wrap.appendChild(table);
                    body.appendChild(wrap);
                    if (rows.length === 0) wrap.appendChild(el('div', 'rsv-empty', '테이블이 없습니다.'));
                    if (data && data.note) body.appendChild(el('p', 'rsv-note', data.note));
                })
                .catch(function (err) {
                    body.textContent = '';
                    body.appendChild(el('div', 'rsv-error', err.message));
                });
        }

        // ---- 데이터 적재 ----
        function guardNoDbId(pane) {
            if (!pane) return true;   // 이 화면에 없는 탭 - 적재할 것이 없다
            if (dbId) return false;
            pane.count.textContent = '';
            renderError(pane.body, 'db_id 없음 - 좌측 트리나 Host 목록에서 인스턴스를 선택해주세요.');
            return true;
        }

        function stamp(pane, label, n) {
            pane.count.textContent = '';
            pane.count.appendChild(el('span', '', label + ' '));
            pane.count.appendChild(el('b', '', String(n)));
            pane.count.appendChild(el('span', '', '건 · ' + new Date().toLocaleTimeString('ko-KR')));
        }

        function loadSessions() {
            if (guardNoDbId(sessionsPane)) return;
            return fetch(api('session_list'))
                .then(function (res) { return res.json(); })
                .then(function (data) {
                    if (data && data.error) {
                        sessionsPane.count.textContent = '';
                        renderError(sessionsPane.body, data.error);
                        return;
                    }
                    var rows = Array.isArray(data) ? data : [];
                    stamp(sessionsPane, '활성 세션', rows.length);
                    renderSessions(sessionsPane.body, rows);
                })
                .catch(function (err) {
                    sessionsPane.count.textContent = '';
                    renderError(sessionsPane.body, err.message);
                });
        }

        function loadLocks() {
            if (guardNoDbId(locksPane)) return;
            return fetch(api('lock_waits'))
                .then(function (res) { return res.json(); })
                .then(function (data) {
                    if (data && data.error) {
                        locksPane.count.textContent = '';
                        renderError(locksPane.body, data.error);
                        return;
                    }
                    var rows = Array.isArray(data) ? data : [];
                    var tree = renderLockTree(locksPane.body, rows);
                    // 쌍의 개수가 아니라 관련 세션 수가 실제로 궁금한 값이다(A→B→C 는 쌍 2개지만
                    // 얽힌 세션은 3개다).
                    stamp(locksPane, '락 관련 세션', tree.total);
                })
                .catch(function (err) {
                    locksPane.count.textContent = '';
                    renderError(locksPane.body, err.message);
                });
        }

        function loadCapacity() {
            if (guardNoDbId(capacityPane)) return;
            return fetch(api('capacity'))
                .then(function (res) { return res.json(); })
                .then(function (data) {
                    if (data && data.error) {
                        capacityPane.count.textContent = '';
                        renderError(capacityPane.body, data.error);
                        return;
                    }
                    var unit = (data && data.unit) || '항목';
                    // 안내 문구는 엔진마다 다르므로 서버가 준 note 를 그대로 쓰고, 이 탭만
                    // 자동 갱신을 하지 않는다는 점을 덧붙인다 - 안 밝히면 시각이 안 바뀌는 것을
                    // 고장으로 오해한다.
                    capacityPane.note.textContent = ((data && data.note) || '') +
                        ' 이 탭은 Interval 자동 새로고침 대상이 아닙니다(용량은 초 단위로 변하지 않고 ' +
                        '조회가 무겁습니다). 탭을 열 때와 새로고침 버튼을 누를 때만 다시 읽습니다.';
                    stamp(capacityPane, unit, ((data && data.rows) || []).length);
                    renderCapacity(capacityPane.body, data);
                })
                .catch(function (err) {
                    capacityPane.count.textContent = '';
                    renderError(capacityPane.body, err.message);
                });
        }

        /**
         * 강제 새로고침 버튼. Interval 설정(off 포함)과 무관하게 즉시 다시 읽는다.
         * 용량 조회 탭은 애초에 자동 갱신을 하지 않으므로(TABS 의 autoRefresh:false) 이 버튼이
         * 유일한 갱신 수단이다.
         */
        function wireReloadButton(pane, loadFn) {
            if (!pane || !pane.reloadBtn) return;   // 이 화면에 없는 탭 (buildPane 주석 참고)
            pane.reloadBtn.addEventListener('click', function () {
                if (pane.reloadBtn.disabled) return;
                pane.reloadBtn.disabled = true;
                pane.reloadBtn.classList.add('busy');
                var done = function () {
                    pane.reloadBtn.disabled = false;
                    pane.reloadBtn.classList.remove('busy');
                };
                var p = loadFn();
                // guardNoDbId 로 일찍 빠져나오면 promise 가 아니다.
                if (p && typeof p.then === 'function') p.then(done, done);
                else done();
            });
        }

        wireKillButton(sessionsPane, checkedSessions, loadSessions);
        wireKillButton(locksPane, checkedLocks, loadLocks);
        wireReloadButton(sessionsPane, loadSessions);
        wireReloadButton(locksPane, loadLocks);
        wireReloadButton(capacityPane, loadCapacity);

        // ---- 탭 전환 ----
        // 보이는 탭만 새로고침한다 - 세 탭을 항상 다 갱신하면 안 보는 화면 때문에 DB 에 매 주기
        // 쿼리가 두 배로 나간다(세션/락 쿼리는 둘 다 시스템 뷰 전체를 훑는다).
        var TABS = [
            { view: 'detail', btn: 'tabDetailBtn', panel: 'detailView', load: null },
            { view: 'sessions', btn: 'tabSessionsBtn', panel: 'sessionsView', load: loadSessions },
            { view: 'locks', btn: 'tabLocksBtn', panel: 'locksView', load: loadLocks },
            // 용량 조회는 Interval 자동 갱신 대상이 아니다(사용자 지시, 2026-09-05).
            // 용량은 초 단위로 변하지 않는데 쿼리는 전 테이블의 크기를 훑어 무겁다 - 주기적으로
            // 돌려서 얻는 것이 없다. 이 탭은 탭을 열 때와 새로고침 버튼을 누를 때만 읽는다.
            { view: 'capacity', btn: 'tabCapacityBtn', panel: 'capacityView', load: loadCapacity,
              autoRefresh: false }
        ];
        var ACTIVE_KEY = 'rdb_active_tab';
        var activeView = 'detail';

        function visibleTabs() {
            return TABS.filter(function (t) {
                var btn = document.getElementById(t.btn);
                return btn && btn.style.display !== 'none';
            });
        }

        function showView(view) {
            var tabs = visibleTabs();
            // 메뉴 표시 설정으로 지금 탭이 숨겨졌으면 남아 있는 첫 탭으로 옮긴다 - 안 그러면
            // 탭 버튼은 사라졌는데 그 본문만 덩그러니 남는다.
            if (!tabs.some(function (t) { return t.view === view; })) {
                view = tabs.length ? tabs[0].view : 'detail';
            }
            activeView = view;
            TABS.forEach(function (t) {
                var btn = document.getElementById(t.btn);
                var panel = document.getElementById(t.panel);
                if (btn) btn.classList.toggle('active', t.view === view);
                if (panel) panel.style.display = (t.view === view) ? 'block' : 'none';
            });
            try { localStorage.setItem(ACTIVE_KEY, view); } catch (e) { /* 저장 실패는 무시 */ }
            var current = TABS.filter(function (t) { return t.view === view; })[0];
            if (current && current.load) current.load();
        }

        TABS.forEach(function (t) {
            var btn = document.getElementById(t.btn);
            if (btn) btn.addEventListener('click', function () { showView(t.view); });
        });

        /** 자동 새로고침(refreshAllPanels)에서 부른다 - 지금 보고 있는 탭만 다시 읽는다. */
        window.dbagentRefreshRdbSessionViews = function () {
            // Kill 확인/결과 모달이나 용량 상세가 떠 있는 동안 표가 다시 그려지면, 사용자가 방금
            // 고른 대상이 눈앞에서 바뀐다. 모달이 닫힐 때까지 자동 갱신을 미룬다.
            if (modal.classList.contains('open')) return;
            var current = TABS.filter(function (t) { return t.view === activeView; })[0];
            if (!current || !current.load) return;
            // 자동 갱신을 원하지 않는 탭(용량 조회)은 여기서 뺀다 - 새로고침 버튼으로만 읽는다.
            if (current.autoRefresh === false) return;
            current.load();
        };

        /** 메뉴 표시 설정이 바뀌어 탭이 숨겨졌을 때 현재 탭을 다시 맞춘다. */
        window.dbagentSyncRdbActiveTab = function () { showView(activeView); };

        var saved = null;
        try { saved = localStorage.getItem(ACTIVE_KEY); } catch (e) { saved = null; }
        showView(saved || 'detail');
    };
})();
