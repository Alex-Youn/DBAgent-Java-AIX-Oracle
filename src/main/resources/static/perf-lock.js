// 성능 분석 "Lock 분석" 하위 탭 (성능 분석 개편 Phase 1a, 2026-10-09) - 설계문서 `성능분석 메뉴 설계.md` 1-6.
// perf-shell.js(PerfShell) 다음에 로드한다. 공통 조건 바의 조회를 구독해 /api/perf/lock_* (수집 저장소, 운영 DB 조회 없음)를 그린다.
// 락 발생 건 = 같은 블로커·같은 락 유형의 대기 샘플을 묶은 것(서버가 묶는다). 시각은 전부 DB 시각이다.
(function () {
    'use strict';

    const S = window.PerfShell;
    if (!S) return;
    const { $, esc, human, parseLocal, fmtMinute, toLocal, getJson } = S.utils;
    const enc = encodeURIComponent;

    const st = {
        type: 'all',       // all | TX | TM
        minSec: 0,
        listSel: null,     // 차트를 드래그해 고른 구간 {from, to} - 목록·KPI·Top만 이 구간으로 다시 읽는다
        incidents: [],
        selId: null,
        key: null,         // 마지막으로 읽은 조건(같은 조건이면 탭 전환 때 다시 읽지 않는다)
        gen: 0,
        listGen: 0,        // 목록·KPI·Top 읽기 세대(유형·지속시간·구간 변경은 추이를 다시 읽지 않는다)
        chart: null,
        range: null
    };

    // ------------------------------------------------------------------ 표시 도우미

    const dur = (sec) => {
        if (sec === null || sec === undefined) return '-';
        sec = Math.round(sec);
        if (sec < 60) return sec + '초';
        const m = Math.floor(sec / 60);
        if (m < 60) return m + '분 ' + (sec % 60) + '초';
        return Math.floor(m / 60) + '시간 ' + (m % 60) + '분';
    };
    const hm = (iso) => String(iso || '').slice(11, 16);
    const full = (iso) => String(iso || '').replace('T', ' ').slice(0, 19);
    const TM_MODE = { 2: 'RS', 3: 'SX', 4: 'S', 5: 'SSX', 6: 'X' };

    function modeText(it) {
        const m = it.reqMode;
        if (m === null || m === undefined) return '-';
        if (it.lockType === 'TM') {
            const held = it.heldMode === null || it.heldMode === undefined ? '미상' : (it.heldMode + ' (' + (TM_MODE[it.heldMode] || '?') + ')');
            return `요청 ${m}(${TM_MODE[m] || '?'}) / 보유 ${held}`;
        }
        return m === 6 ? '6 · 행 경합' : m === 4 ? '4 · Unique 키 등' : String(m);
    }

    function endText(it) {
        if (it.status === 'OPEN') return `<span class="perf-warn">진행 중</span> <span class="perf-hint">(마지막 수집 ${esc(hm(it.endTime))})</span>`;
        if (it.status === 'UNKNOWN') return `<span class="perf-warn" title="그 사이 수집 공백이 있어 종료 시각을 확정할 수 없습니다">종료 미확정</span> <span class="perf-hint">(마지막 수집 ${esc(hm(it.endTime))})</span>`;
        return esc(hm(it.endTime));
    }

    const typeBadge = (t) => `<span class="perf-lk-badge perf-lk-${t === 'TM' ? 'tm' : 'tx'}">${esc(t)}</span>`;
    const blockerText = (it) => it.blockerSid === null || it.blockerSid === undefined ? '<span class="perf-hint">블로커 미상</span>'
        : `SID ${esc(it.blockerSid)}${it.blockerSerial ? ',' + esc(it.blockerSerial) : ''}${it.blockerModule ? ' · ' + esc(it.blockerModule) : ''}`;

    function currentWindow() { return st.listSel || S.getRange(); }

    // ------------------------------------------------------------------ 읽기

    const q = (path, w, extra) => `/api/perf/${path}?db_id=${enc(S.getDbId())}&from=${enc(w.from)}&to=${enc(w.to)}${S.getFilter()}${extra || ''}`;

    function setNotice(collect) {
        const el = $('perf-lock-notice');
        if (!el) return;
        let msg = '';
        if (collect && collect.enabled === false) msg = 'Lock 분석 수집이 꺼져 있습니다(환경설정 → Lock 분석 수집). 이미 쌓인 이력만 보입니다.';
        else if (collect && collect.state === 'unavailable') msg = `ASH 조회 불가 - 판단 보류${collect.reason ? ' (' + collect.reason + ')' : ''}. 이 DB의 v$active_session_history를 읽을 수 없어 락 이력을 만들 수 없습니다.`;
        else if (collect && collect.state === 'truncated') msg = `락 대기 샘플이 너무 많아 일부만 수집되었습니다${collect.reason ? ' (' + collect.reason + ')' : ''}. 이 시간대의 건수·대기 시간은 실제보다 작을 수 있습니다.`;
        else if (collect && collect.state === 'error') msg = `최근 락 수집에 실패했습니다${collect.reason ? ' (' + collect.reason + ')' : ''}. 그 구간은 수집 공백으로 표시될 수 있습니다.`;
        el.textContent = msg;
        el.style.display = msg ? '' : 'none';
    }

    async function loadTimeline(base, my) {
        try {
            const d = await getJson(q('lock_timeline', base));
            if (my !== st.gen) return;
            renderChart(d);
        } catch (e) {
            if (my === st.gen) { destroyChart(); $('perf-lock-chart-note').textContent = '추이 조회 실패: ' + e.message; }
        }
    }

    async function loadList(my) {  // my = st.listGen 값
        const w = currentWindow();
        if (!w) return;
        const typeParam = `&type=${st.type}&min_sec=${st.minSec}`;
        $('perf-lock-kpis').innerHTML = '<div class="perf-lk-empty">불러오는 중…</div>';
        const [sum, inc, tb, to] = await Promise.all([
            getJson(q('lock_summary', w, typeParam)).catch(e => ({ _err: e.message })),
            getJson(q('lock_incidents', w, typeParam)).catch(e => ({ _err: e.message })),
            getJson(q('lock_top_blockers', w, `&type=${st.type}`)).catch(e => ({ _err: e.message })),
            getJson(q('lock_top_objects', w, `&type=${st.type}`)).catch(e => ({ _err: e.message }))
        ]);
        if (my !== st.listGen) return;
        setNotice((sum && sum.collect) || (inc && inc.collect));
        renderKpis(sum);
        st.incidents = inc._err ? [] : (inc.incidents || []);
        renderList(inc);
        renderTops(tb, to);
        if (st.incidents.length) {
            if (!st.incidents.some(i => i.incidentId === st.selId)) st.selId = st.incidents[0].incidentId;
            showDetail(st.selId);
        } else {
            st.selId = null;
            $('perf-lock-detail').innerHTML = '<div class="perf-lk-empty">선택한 건이 없습니다</div>';
        }
    }

    function keyOf(base) {
        return [S.getDbId(), base.from, base.to, S.getFilter()].join('|');
    }

    function load() {
        const base = S.getRange();
        if (!base || S.sub() !== 'lock') return;
        st.range = base;
        st.listSel = null;
        $('perf-lock-sel-reset').style.display = 'none';
        st.key = keyOf(base);
        const my = ++st.gen;
        const lg = ++st.listGen;
        $('perf-lock-sel-text').textContent = `선택 구간: 전체 (${human(base.from)} ~ ${human(base.to)})`;
        loadTimeline(base, my);
        loadList(lg);
    }

    // ------------------------------------------------------------------ 그리기

    function destroyChart() {
        if (st.chart) { st.chart.destroy(); st.chart = null; }
    }

    function renderKpis(d) {
        const el = $('perf-lock-kpis');
        if (d._err) { el.innerHTML = `<div class="perf-lk-empty">판단 보류 - ${esc(d._err)}</div>`; return; }
        const card = (label, value, sub) => `<div class="perf-lk-kpi"><div class="l">${label}</div><div class="v">${value}</div><div class="d">${sub || ''}</div></div>`;
        const none = d.totalCount === 0;
        el.innerHTML = [
            card('락 발생', none ? '0건' : `${d.totalCount}건`, `TX ${d.txCount} · TM ${d.tmCount}${d.truncated ? ' · 상위 1000건만 집계' : ''}`),
            card('최장 지속', d.longestSec === null ? '-' : esc(dur(d.longestSec)), esc(d.longestObject || '')),
            card('최대 동시 대기', none ? '-' : `${d.maxWaiters}세션`, ''),
            card('누적 대기', none ? '-' : `${(d.waitSessionSec / 60).toFixed(1)}세션·분`, `${d.waitSessionSec}세션·초`),
            card('고유 블로커', none ? '-' : `${d.distinctBlockers}세션`, '모듈은 블로커 상세 수집 후 표시')
        ].join('');
    }

    function renderChart(d) {
        const pts = d.points || [];
        $('perf-lock-chart-note').textContent = d.collectedFrom
            ? `1분 단위 평균 대기 세션 수 · 수집 시작 ${human(d.collectedFrom)}${d.gapMinutes ? ` · 수집 공백 ${d.gapMinutes}분(끊어서 표시)` : ''}`
            : '아직 수집된 분이 없습니다(Lock 분석 수집은 배포 후 첫 수집부터 시작합니다).';
        const tx = S.cssVar('--nd-wc-tx-lock');
        const tm = S.cssVar('--nd-wc-tm-lock');
        const data = {
            labels: pts.map(p => parseLocal(p.time).getTime()),
            datasets: [
                { label: 'TX 락', data: pts.map(p => p.tx), backgroundColor: tx, borderColor: tx, borderWidth: 0, stack: 'lk', hidden: st.type === 'TM' },
                { label: 'TM 락', data: pts.map(p => p.tm), backgroundColor: tm, borderColor: tm, borderWidth: 0, stack: 'lk', hidden: st.type === 'TX' }
            ]
        };
        const minutes = pts.length;
        const unit = minutes > 180 ? 'hour' : 'minute';
        if (st.chart) {
            st.chart.data = data;
            st.chart.options.scales.x.time.unit = unit;
            st.chart.update('none');
            return;
        }
        st.chart = new Chart($('perf-lock-chart'), {
            type: 'bar',
            data,
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: { duration: 0 },
                interaction: { mode: 'index', intersect: false },
                scales: {
                    x: { type: 'time', stacked: true, offset: true,
                        time: { unit, tooltipFormat: 'MM-dd HH:mm', displayFormats: { minute: 'HH:mm', hour: 'MM-dd HH:mm' } },
                        ticks: { color: chartLineColor(0.8), maxRotation: 0, autoSkip: true }, grid: { color: chartLineColor(0.12) } },
                    y: { stacked: true, beginAtZero: true, title: { display: true, text: '대기 세션', color: chartLineColor(0.8) },
                        ticks: { color: chartLineColor(0.8), precision: 1 }, grid: { color: chartLineColor(0.12) } }
                },
                plugins: {
                    legend: { position: 'top', labels: { color: chartLineColor(0.9), boxWidth: 12 } },
                    tooltip: { callbacks: { label: (c) => c.parsed.y === null ? `${c.dataset.label}: 수집 공백` : `${c.dataset.label}: ${c.parsed.y}` } }
                }
            }
        });
    }

    function renderList(inc) {
        const body = $('perf-lock-rows');
        $('perf-lock-mode-h').textContent = st.type === 'TM' ? '요청 / 보유 모드' : st.type === 'TX' ? '대기 모드' : '모드';
        if (inc._err) {
            body.innerHTML = `<tr><td colspan="8" class="perf-lk-empty">판단 보류 - ${esc(inc._err)}</td></tr>`;
            $('perf-lock-count').textContent = '';
            return;
        }
        const list = st.incidents;
        $('perf-lock-count').textContent = `${list.length}건${inc.truncated ? '(상위 1000건)' : ''} · 행을 누르면 오른쪽에 블로킹 상세가 열립니다`;
        if (!list.length) {
            body.innerHTML = '<tr><td colspan="8" class="perf-lk-empty">조건에 맞는 락 발생 건이 없습니다</td></tr>';
            return;
        }
        body.innerHTML = list.map(it => `<tr class="perf-lk-row${it.incidentId === st.selId ? ' sel' : ''}" data-id="${esc(it.incidentId)}">` +
            `<td>${esc(hm(it.startTime))}</td><td>${endText(it)}</td><td class="num">${esc(dur(it.durationSec))}</td>` +
            `<td>${typeBadge(it.lockType)}</td><td>${esc(modeText(it))}</td><td>${blockerText(it)}</td>` +
            `<td class="num">${esc(it.maxWaiters)}</td><td title="${esc(it.objName || '')}">${esc(shortObj(it))}</td></tr>`).join('');
        body.querySelectorAll('tr.perf-lk-row').forEach(tr => tr.addEventListener('click', () => {
            st.selId = tr.dataset.id;
            body.querySelectorAll('tr.perf-lk-row').forEach(r => r.classList.toggle('sel', r === tr));
            showDetail(st.selId);
        }));
    }

    const shortObj = (it) => it.objName ? it.objName.replace(/^[^.]+\./, '') : (it.objId ? 'OBJ#' + it.objId : '-');

    function renderTops(tb, to) {
        const blk = $('perf-lock-top-blockers');
        const obj = $('perf-lock-top-objects');
        blk.innerHTML = tb._err ? `<tr><td colspan="3" class="perf-lk-empty">판단 보류</td></tr>`
            : ((tb.blockers || []).map(b => `<tr><td>${b.blockerSid === null ? '<span class="perf-hint">블로커 미상</span>' : 'SID ' + esc(b.blockerSid) + (b.blockerSerial ? ',' + esc(b.blockerSerial) : '') + (b.blockerModule ? ' · ' + esc(b.blockerModule) : '')}</td>` +
                `<td class="num">${esc(b.count)}</td><td class="num">${esc(dur(b.maxDurationSec))}</td></tr>`).join('') || '<tr><td colspan="3" class="perf-lk-empty">없음</td></tr>');
        obj.innerHTML = to._err ? `<tr><td colspan="3" class="perf-lk-empty">판단 보류</td></tr>`
            : ((to.objects || []).map(o => `<tr><td title="${esc(o.objName)}">${esc(String(o.objName).replace(/^[^.]+\./, ''))}</td><td>${typeBadge(o.lockType)}</td><td class="num">${esc(o.count)}</td></tr>`).join('') || '<tr><td colspan="3" class="perf-lk-empty">없음</td></tr>');
    }

    // ------------------------------------------------------------------ 블로킹 상세

    function treeHtml(it, waiters) {
        // 목록이 많으면 대기 시간이 긴 3세션까지만 보이고 "외 N세션"으로 접는다(설계 1-6)
        const shown = waiters.slice(0, 3);
        const more = waiters.length - shown.length;
        const shownIds = {};
        shown.forEach(w => { shownIds[w.sid] = true; });
        const kids = {};
        shown.forEach(w => { (kids[w.parentSid] = kids[w.parentSid] || []).push(w); });
        const row = (w, depth) => `<div class="perf-lk-tree-row" style="padding-left:${18 + depth * 16}px">└ SID ${esc(w.sid)},${esc(w.serial)}` +
            `${w.username ? ' · ' + esc(w.username) : ''}${w.module ? ' · ' + esc(w.module) : ''}` +
            ` <span class="perf-hint">${esc(it.waitEvent || '')} · ${esc(dur(w.waitSec))}</span>` +
            `${w.sqlId ? ` <button type="button" class="perf-lk-sql" data-sql="${esc(w.sqlId)}">${esc(w.sqlId)}</button>` : ''}</div>` +
            (depth < 6 ? (kids[w.sid] || []).map(c => row(c, depth + 1)).join('') : '');
        // 부모가 보이는 대기자가 아니면(블로커이거나 접힌 대기자) 블로커 바로 아래에 둔다
        const roots = shown.filter(w => !shownIds[w.parentSid] || w.parentSid === w.sid);
        const head = `<div class="perf-lk-tree-row"><b>${it.blockerSid === null || it.blockerSid === undefined ? '블로커 미상' : 'SID ' + esc(it.blockerSid) + (it.blockerSerial ? ',' + esc(it.blockerSerial) : '')}</b>` +
            ` <span class="perf-hint">${it.blockerModule ? esc(it.blockerModule) : '블로커 세션 정보는 블로커 상세 수집을 켜면 표시됩니다'}</span></div>`;
        return head + roots.map(w => row(w, 0)).join('') +
            (more > 0 ? `<div class="perf-lk-tree-row perf-hint" style="padding-left:18px">└ 외 ${more}세션</div>` : '');
    }

    async function showDetail(id) {
        const box = $('perf-lock-detail');
        box.innerHTML = '<div class="perf-lk-empty">불러오는 중…</div>';
        const my = st.gen;
        let d;
        try {
            d = await getJson(`/api/perf/lock_incident?db_id=${enc(S.getDbId())}&incident_id=${enc(id)}`);
        } catch (e) {
            if (my === st.gen) box.innerHTML = `<div class="perf-lk-empty">상세 조회 실패 - ${esc(e.message)}</div>`;
            return;
        }
        if (my !== st.gen || id !== st.selId) return;
        const conf = { HIGH: '높음', MEDIUM: '보통', LOW: '낮음' }[d.causeConfidence] || '';
        const waiters = d.waiters || [];
        const sqlIds = waiters.map(w => w.sqlId).filter(Boolean);
        const kv = [
            ['유형', d.lockType === 'TM' ? 'TM 락(테이블)' : 'TX 락(행/트랜잭션)'],
            [d.lockType === 'TM' ? '요청 / 보유 모드' : '대기 모드', modeText(d)],
            ['대기 이벤트', d.waitEvent || '-'],
            ['대상 오브젝트', d.objName || (d.objId ? 'OBJ#' + d.objId : '-')],
            ['누적 대기', `${d.waitSessionSec}세션·초 (최대 ${d.maxWaiters}세션 동시)`],
            ['같은 시간대 반복', `최근 7일 같은 시간대(±1시간) ${d.repeatCount7d || 0}건`]
        ];
        const hint = d.causeText
            ? `<div class="perf-lk-hint"><b>${esc(d.causeCode || '')}</b>${conf ? ` <span class="perf-hint">(판정 신뢰도 ${conf})</span>` : ''}<br>${esc(d.causeText)}</div>` : '';
        const off = '<div class="perf-hint" style="margin-top:6px">블로커 상세 수집이 꺼져 있어 일부 원인(커밋 누락, DDL, FK 인덱스 등)은 판정하지 않습니다.</div>';
        box.innerHTML = `<div class="perf-lk-detail-head">${typeBadge(d.lockType)} <b>${esc(full(d.startTime))} ~ ${d.status === 'OPEN' ? '진행 중' : esc(hm(d.endTime))}</b>` +
            ` <span class="perf-hint">${esc(dur(d.durationSec))} 지속 · 대상 ${esc(d.objName || '-')}</span></div>` +
            `<div class="perf-hint" style="margin:10px 0 4px">블로킹 트리 (최대 대기 시점 ${esc(hm(d.peakTime))})</div>` +
            `<div class="perf-lk-tree">${treeHtml(d, waiters)}</div>` +
            `<table class="perf-lk-kv">${kv.map(r => `<tr><th>${esc(r[0])}</th><td>${esc(r[1])}</td></tr>`).join('')}</table>` +
            hint + off +
            `<div class="perf-lk-actions">${sqlIds.length ? `<button type="button" class="perf-link-btn perf-lk-sql" data-sql="${esc(sqlIds[0])}">SQL 상세</button>` : ''}` +
            `<button type="button" class="perf-link-btn" id="perf-lock-go-session">세션 분석에서 이 시점 보기</button></div>`;
        box.querySelectorAll('.perf-lk-sql').forEach(b => b.addEventListener('click', () => openSql(b.dataset.sql)));
        $('perf-lock-go-session').addEventListener('click', () => goSession(d));
    }

    // SQL 분석 탭이 생기기 전에는 대시보드의 SQL 상세 드로어를 연다(설계 1-6, 대시보드 선택 구간 기준으로 보인다)
    function openSql(sqlId) {
        const ND = window.ndDashboard;
        try {
            if (ND && ND.drawer && ND.views && ND.views.sql) { ND.drawer.open(() => ND.views.sql(sqlId)); return; }
        } catch (e) { console.warn('[DBAgent] SQL 상세 열기 실패:', e); }
        S.rangeMsg('SQL 상세는 DASHBOARD 화면을 한 번 연 뒤에 열 수 있습니다.');
    }

    // 피크 시각 ±10분(끝은 DB 현재 시각으로 자름)으로 세션 분석을 연다. 브라우저 뒤로 가기로 돌아올 수 있다.
    function goSession(d) {
        const peak = parseLocal(d.peakTime);
        let to = new Date(peak.getTime() + 10 * 60000);
        const now = S.dbNowDate();
        if (to > now) to = now;
        const from = new Date(peak.getTime() - 10 * 60000);
        if (!(to > from)) return;
        window.location.hash = `#history/session?db_id=${enc(S.getDbId())}&from=${enc(fmtMinute(from))}&to=${enc(fmtMinute(to))}`;
    }

    // ------------------------------------------------------------------ 차트 드래그로 목록 구간 좁히기

    function attachBrush() {
        const box = $('perf-lock-chart-box');
        const brush = $('perf-lock-brush');
        if (!box || box.dataset.bound) return;
        box.dataset.bound = '1';
        let dragging = false;
        let x0 = 0;
        box.addEventListener('mousedown', (e) => {
            if (e.target.id !== 'perf-lock-chart' || !st.chart || !st.range) return;
            dragging = true;
            x0 = toLocal(e, box).x;
            brush.style.left = x0 + 'px';
            brush.style.width = '0px';
            brush.style.display = 'block';
        });
        window.addEventListener('mousemove', (e) => {
            if (!dragging) return;
            const x = toLocal(e, box).x;
            brush.style.left = Math.min(x0, x) + 'px';
            brush.style.width = Math.abs(x - x0) + 'px';
        });
        window.addEventListener('mouseup', (e) => {
            if (!dragging) return;
            dragging = false;
            brush.style.display = 'none';
            const x1 = toLocal(e, box).x;
            if (Math.abs(x1 - x0) < 5) return;
            const xs = st.chart.scales.x;
            const lo = Math.max(xs.getValueForPixel(Math.min(x0, x1)), parseLocal(st.range.from).getTime());
            const hi = Math.min(xs.getValueForPixel(Math.max(x0, x1)), parseLocal(st.range.to).getTime());
            if (!(hi > lo)) return;
            const from = new Date(Math.floor(lo / 60000) * 60000);
            let to = new Date(Math.ceil(hi / 60000) * 60000);
            if (to.getTime() - from.getTime() < 60000) to = new Date(from.getTime() + 60000);
            st.listSel = { from: fmtMinute(from), to: fmtMinute(to) };
            $('perf-lock-sel-text').textContent = `선택 구간: ${human(st.listSel.from)} ~ ${human(st.listSel.to)}`;
            $('perf-lock-sel-reset').style.display = '';
            loadList(++st.listGen);
        });
    }

    // ------------------------------------------------------------------ 연결

    // 유형 필터(전체/TX/TM)를 코드로 바꾼다 - 세션 분석의 "TX/TM 락을 Lock 분석에서 보기"가 #history/lock?...&type=TX 로 넘긴다.
    function setType(t) {
        if (t !== 'TX' && t !== 'TM' && t !== 'all') return false;
        if (t === st.type) return false;
        st.type = t;
        S.setUrlExtra('lock', t === 'all' ? '' : '&type=' + t); // 새로고침·링크 공유 때 유형 필터가 복원되게 URL에 남긴다
        document.querySelectorAll('#perf-lock-type button').forEach(x => x.classList.toggle('active', x.dataset.t === t));
        if (st.chart) {
            st.chart.data.datasets[0].hidden = t === 'TM';
            st.chart.data.datasets[1].hidden = t === 'TX';
            st.chart.update('none');
        }
        return true;
    }

    function onRoute(ev) {
        const sub = ev && ev.detail && ev.detail.sub;
        if (sub !== 'lock') return;
        let typeChanged = false;
        try { typeChanged = setType(new URLSearchParams((ev.detail && ev.detail.query) || '').get('type')); } catch (e) { /* 잘못된 쿼리는 무시 */ }
        const base = S.getRange();
        if (base && keyOf(base) !== st.key) load();
        else if (base && typeChanged) loadList(++st.listGen);
    }

    function init() {
        if (!$('perf-lock-rows')) return;
        S.onSearch(() => { if (S.sub() === 'lock') load(); else st.key = null; });
        S.onReset(() => {
            st.gen++;
            st.listGen++;
            st.key = null;
            st.listSel = null;
            st.range = null;
            destroyChart();
            $('perf-lock-rows').innerHTML = '';
            $('perf-lock-detail').innerHTML = '<div class="perf-lk-empty">조회 후 표시됩니다</div>';
        });
        window.addEventListener('perf:route', onRoute);
        document.querySelectorAll('#perf-lock-type button').forEach(b => b.addEventListener('click', () => {
            setType(b.dataset.t);
            if (S.getRange()) loadList(++st.listGen);
        }));
        $('perf-lock-min').addEventListener('change', (e) => {
            st.minSec = parseInt(e.target.value, 10) || 0;
            if (S.getRange()) loadList(++st.listGen);
        });
        $('perf-lock-sel-reset').addEventListener('click', () => {
            st.listSel = null;
            $('perf-lock-sel-reset').style.display = 'none';
            if (st.range) $('perf-lock-sel-text').textContent = `선택 구간: 전체 (${human(st.range.from)} ~ ${human(st.range.to)})`;
            loadList(++st.listGen);
        });
        attachBrush();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
