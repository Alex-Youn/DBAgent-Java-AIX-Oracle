// 새 대시보드 ⑧ 점검 알림 · 6.4 점검 알림 상세 - 설계문서 `대시보드 UI 개선 설계.md` 5장 ⑧·6.4, 전체 작업순서 F8(2026-09-26).
// nd-dashboard.js·nd-top.js(드로어) 다음에 로드.
//
// - 목록: /api/dashboard/{dbId}/checks (저장소만 읽는 가벼운 조회, 느린 주기 60초). 점검 자체는 서버가 10분/1일 주기로 한다.
// - 헤더: 심각도별 건수 + 마지막 점검 + 필터(전체/위험/주의/확인). 카드는 위험→주의→확인, 점검 실패는 회색, 가로 한 줄
//   (넘치면 프레임 안에서만 가로 스크롤). OK 행은 카드로 그리지 않고 항목별 마지막 점검 시각에만 쓴다.
// - 카드 클릭 → 6.4 상세 드로어(7일 추이·관련 객체·조치 권장·조회 쿼리).
// - 숨김(2026-09-29): 카드의 "숨김" → 서버에 누가·언제·당시 상태를 저장하고 목록·건수에서 뺀다. 상태가 바뀌면(잡이 다시
//   실패, 주의→위험) 서버가 숨김으로 보지 않아 다시 뜬다. "숨긴 항목 n" 버튼으로 숨긴 카드만 보고 되돌린다.
(function () {
    'use strict';
    const ND = window.ndDashboard;
    if (!ND) return;

    const SEV = {
        CRIT: { t: '위험', sym: '■', cls: 'crit', order: 0 },
        WARN: { t: '주의', sym: '▲', cls: 'warn', order: 1 },
        INFO: { t: '확인', sym: '○', cls: 'info', order: 2 },
        ERROR: { t: '점검 실패', sym: '×', cls: 'err', order: 3 }
    };
    const PCT_TYPES = ['TABLESPACE', 'FRA', 'TEMP'];
    const st = { data: null, filter: 'all', gen: 0, showHidden: false, long: null, longKey: '' };

    const $ = (id) => document.getElementById(id);
    const esc = (s) => String(s === null || s === undefined ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const pad = (n) => String(n).padStart(2, '0');
    const off = () => (typeof ND.state.dbClockOffsetMs === 'number' ? ND.state.dbClockOffsetMs : 0);
    const hm = (ms) => { const d = new Date(ms + off()); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; }; // ②③과 같은 DB 시각
    const md = (ms) => { const d = new Date(ms + off()); return `${pad(d.getMonth() + 1)}/${pad(d.getDate())}`; };
    const num = (v, dgt) => (v === null || v === undefined ? '—' : (Math.round(v * Math.pow(10, dgt)) / Math.pow(10, dgt)).toFixed(dgt));

    function valueText(it) {
        const v = it.value;
        switch (it.checkType) {
            case 'TABLESPACE': case 'FRA': case 'TEMP': return `${num(v, 1)}%`;
            case 'TABLE_SIZE': return `${num(v, 1)} GB`;
            case 'JOB_FAIL': return `${num(v, 0)}회`;
            case 'LONG_QUERY': return `${num(v, 0)}건`;
            default: return `${num(v, 0)}개`;
        }
    }

    function subText(it) {
        const d = it.detail || {};
        switch (it.checkType) {
            case 'TABLESPACE': return `${num(d.usedGb, 1)} / ${num(d.maxGb, 1)} GB · 여유 ${num(d.freeGb, 1)} GB`;
            case 'TEMP': return `${num(d.usedGb, 1)} / ${num(d.maxGb, 1)} GB (자동 확장 최대 기준)`;
            case 'FRA': return `${num(d.usedGb, 1)} / ${num(d.limitGb, 1)} GB · 회수 가능 ${num(d.reclaimableGb, 1)} GB`;
            case 'TABLE_SIZE': return `임계치 ${num(it.threshold, 0)} GB`;
            case 'JOB_FAIL': return `최근 24시간 실패 ${num(it.value, 0)}회${d.consecutive ? ' · 2회 연속 실패' : ''} · 마지막 ${d.lastFail || '-'}` +
                (d.downgraded ? ' · 등급 낮춤 설정 대상(확인 등급)' : '');
            case 'INVALID_OBJ': {
                const items = d.items || [];
                const first = items[0] ? [items[0].owner, items[0].object_name].filter(Boolean).join('.') : '';
                return `${num(it.value, 0)}개${first ? ' · ' + first + (items.length > 1 ? ' 외' : '') : ''}`;
            }
            case 'LONG_QUERY': {
                // null = 목록 조회 실패, [] = 조회는 됐는데 그사이 끝남(2026-09-30 정밀 점검 반영 - 예전엔 둘 다 "실패"로 보였다).
                // 카드는 1분에 한 번만 다시 그리므로 초는 빼고 분까지만(초까지는 상세 창에서).
                if (it.top === null) return '세션 목록 조회 실패 - 건수만 표시';
                const t = it.top[0];
                return t ? `최장 ${durMin(t.elapsedSec)} · SID ${t.sid} (${t.username || '-'})${t.sqlId ? ' · ' + t.sqlId : ''}` : '목록을 읽는 사이 세션이 끝났습니다';
            }
            default: return '';
        }
    }

    function dur(sec) {
        const h = Math.floor(sec / 3600), m = Math.floor(sec % 3600 / 60), s = sec % 60;
        return h > 0 ? `${h}시간 ${m}분` : `${m}분 ${pad(s)}초`;
    }

    function durMin(sec) {
        const h = Math.floor(sec / 3600), m = Math.floor(sec % 3600 / 60);
        return h > 0 ? `${h}시간 ${m}분` : `${m}분`;
    }

    // 장시간 기준 표시 - 60의 배수가 아니면 초로(예전 Math.round(sec/60)은 90초를 "2분", 20초를 "0분"으로 보였다)
    function thrText(sec) {
        return sec % 60 ? `${sec}초` : `${sec / 60}분`;
    }

    function periodText(type) {
        if (type === 'LONG_QUERY') return '실시간';
        const d = st.data;
        const ts = d && d.lastByType ? d.lastByType[type] : null;
        const daily = ['TABLE_SIZE', 'INVALID_OBJ'].indexOf(type) >= 0;
        return `${daily ? '1일 주기' : (d ? d.intervalMinutes : 10) + '분 주기'}${ts ? ' · ' + hm(ts) : ''}`;
    }

    // ================================================================== 목록

    async function load(ctx) {
        ensureDom();
        const d = await ctx.fetchJson('/api/dashboard/' + encodeURIComponent(ctx.dbId) + '/checks');
        if (!d || ctx.isStale()) return;
        st.data = d;
        render();
    }
    ND.register('slow', 'checks', load);
    // 시각은 DB 시각으로 보여 준다 - ② 차트가 dbClockOffsetMs를 받은 뒤(selection 발행 시점) 다시 그린다.
    // 안 그러면 첫 화면만 앱 시각으로 보이다가 1분 뒤 바뀐다(2026-09-26 CDP 확인).
    ND.on('selection', () => { if (st.data) render(); });

    // 장시간 실행 쿼리 실시간 카드(2026-09-30 두 번째 대시보드에서 이식) - 10분 주기 점검이 아니라 3초 Lock 조회('lock')에 실려 온다.
    // 저장된 점검 결과가 아니라 숨김은 없다. 매 3초 다시 그리면 카드가 깜빡이므로 건수·세션 구성·최장 경과(분)가 바뀔 때만 그린다.
    // 판단 보류(ok:false)면 직전 카드를 그대로 둔다(0건으로 지우지 않음).
    ND.on('lock', (d) => {
        if (!d || !d.ok) return;
        const top = Array.isArray(d.longRunningTop) ? d.longRunningTop : null;
        st.long = d.longRunning > 0 ? { count: d.longRunning, sec: d.longRunningSec, top } : null;
        const key = st.long ? `${st.long.count}|${(top || []).map(t => t.sid + ':' + t.serial).join(',')}|${top && top[0] ? Math.floor(top[0].elapsedSec / 60) : ''}` : '';
        if (key === st.longKey) return;
        st.longKey = key;
        render(); // 점검 목록(/checks)이 아직 없어도 그린다 - 3초 조회로 옮긴 의미(2026-09-30 정밀 점검 반영)
    });

    function liveItem() {
        const l = st.long;
        if (!l) return null;
        return { live: true, checkType: 'LONG_QUERY', severity: 'WARN', label: '장시간 실행 쿼리',
            targetName: `${thrText(l.sec)} 이상 실행 중`, value: l.count, threshold: null, top: l.top, sec: l.sec };
    }

    ND.onReset(() => {
        st.long = null;
        st.longKey = '';
        st.data = null;
        const body = $('nd-checks-body');
        if (body) body.innerHTML = '<div class="nd-skel nd-skel-line" style="width: 70%;"></div>';
        const meta = $('nd-checks-meta');
        if (meta) meta.innerHTML = '';
    });

    function ensureDom() {
        const card = $('nd-checks');
        if (!card || card.dataset.ready) return;
        card.dataset.ready = '1';
        const title = card.querySelector('.nd-card-title');
        title.innerHTML = '점검 알림 <span class="nd-chk-meta" id="nd-checks-meta"></span>' +
            '<span class="nd-seg nd-chk-filter" id="nd-checks-filter" role="group" aria-label="점검 알림 필터">' +
            '<button type="button" data-f="all" class="active">전체</button><button type="button" data-f="CRIT">위험</button>' +
            '<button type="button" data-f="WARN">주의</button><button type="button" data-f="INFO">확인</button></span>' +
            '<button type="button" class="nd-link-btn nd-chk-hidden-btn" id="nd-checks-hidden-btn" aria-pressed="false" hidden></button>';
        card.querySelector('.nd-checks-body').id = 'nd-checks-body';
        $('nd-checks-filter').querySelectorAll('button').forEach(b => b.addEventListener('click', () => {
            st.filter = b.dataset.f;
            $('nd-checks-filter').querySelectorAll('button').forEach(x => x.classList.toggle('active', x === b));
            render();
        }));
        $('nd-checks-hidden-btn').addEventListener('click', () => { st.showHidden = !st.showHidden; render(); });
    }

    // 숨김·되돌리기 - 성공하면 목록만 다시 읽는다(다른 패널은 그대로).
    async function setHidden(it, hide, btn) {
        const dbId = window.currentDbId;
        if (btn) btn.disabled = true;
        let res, body;
        try {
            res = await fetch(`/api/dashboard/${encodeURIComponent(dbId)}/checks/${hide ? 'ack' : 'unack'}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: getToken(), checkType: it.checkType, targetName: it.targetName })
            });
            body = await res.json().catch(() => null);
        } catch (e) {
            body = { error: e.message };
        }
        if (dbId !== window.currentDbId) return;
        if (!res || !res.ok || !body || body.error) {
            if (btn) { btn.disabled = false; btn.title = (body && body.error) || '처리 실패'; btn.textContent = '실패'; }
            return;
        }
        const q = new URLSearchParams({ token: getToken() });
        const d = await fetch(`/api/dashboard/${encodeURIComponent(dbId)}/checks?${q}`).then(r => r.ok ? r.json() : null).catch(() => null);
        if (!d || dbId !== window.currentDbId) return;
        st.data = d;
        render();
    }

    function render() {
        ensureDom();
        if (!$('nd-checks-body')) return;
        // 점검 목록이 아직 없으면(첫 로딩·조회 실패) 빈 목록으로 보고 실시간 카드만 그린다
        const d = st.data || { items: [], intervalMinutes: 10 };
        const all = (d.items || []).filter(it => it.severity !== 'OK');
        const items = all.filter(it => !it.hidden);
        const live = liveItem();
        if (live) items.push(live);
        const hiddenItems = all.filter(it => it.hidden);
        if (!hiddenItems.length) st.showHidden = false;
        const hb = $('nd-checks-hidden-btn');
        hb.hidden = !hiddenItems.length;
        hb.textContent = st.showHidden ? '‹ 알림 목록으로' : `숨긴 항목 ${hiddenItems.length}`;
        hb.setAttribute('aria-pressed', st.showHidden ? 'true' : 'false');
        const count = (s) => items.filter(it => it.severity === s).length;
        const fail = count('ERROR');
        $('nd-checks-meta').innerHTML =
            `<span class="nd-chk-cnt nd-sev-crit">■ 위험 ${count('CRIT')}</span> · <span class="nd-chk-cnt nd-sev-warn">▲ 주의 ${count('WARN')}</span> · ` +
            `<span class="nd-chk-cnt nd-sev-info">○ 확인 ${count('INFO')}</span>` + (fail ? ` · <span class="nd-chk-cnt nd-sev-err">실패 ${fail}</span>` : '') +
            `<span class="nd-muted"> · 마지막 점검 ${d.lastLight ? hm(d.lastLight) : '—'} · ${d.intervalMinutes}분 주기</span>`;
        const shown = (st.showHidden ? hiddenItems : items)
            .filter(it => st.showHidden || st.filter === 'all' || it.severity === st.filter)
            .sort((a, b) => SEV[a.severity].order - SEV[b.severity].order || (b.value || 0) - (a.value || 0));
        const body = $('nd-checks-body');
        if (!d.lastLight && !d.lastDaily && !live) {
            body.innerHTML = st.data
                ? '<div class="nd-placeholder">아직 점검 전입니다 — 앱 기동 후 30초~1분 안에 첫 점검이 끝납니다.</div>'
                : '<div class="nd-skel nd-skel-line" style="width: 70%;"></div>'; // 점검 목록 로딩 중
            return;
        }
        if (!shown.length) {
            body.innerHTML = `<div class="nd-placeholder">${st.filter === 'all' ? '임계치를 넘은 점검 항목이 없습니다.' : '해당 심각도의 항목이 없습니다.'}</div>`;
            return;
        }
        body.innerHTML = '<div class="nd-chk-cards">' + shown.map((it, i) => cardHtml(it, i)).join('') + '</div>';
        body.querySelectorAll('.nd-chk-card').forEach(c => {
            const it = shown[+c.dataset.i];
            const open = () => ND.drawer && ND.drawer.open(() => (it.live ? viewLong(it) : viewDetail(it)), c);
            c.addEventListener('click', open);
            c.addEventListener('keydown', (e) => { if (e.target !== c) return; if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } });
            const act = c.querySelector('.nd-chk-act');
            if (act) act.addEventListener('click', (e) => { e.stopPropagation(); setHidden(it, !it.hidden, act); });
        });
    }

    // 카드 오른쪽 위 버튼 - 목록에서는 "숨김", 숨긴 항목 보기에서는 "되돌리기"
    function actBtn(it) {
        if (it.live) return '';
        return it.hidden
            ? `<button type="button" class="nd-chk-act" title="다시 알림 목록에 표시">되돌리기</button>`
            : `<button type="button" class="nd-chk-act" title="이 알림 숨기기 - 상태가 바뀌면(다시 실패·등급 상승) 다시 표시됩니다">숨김</button>`;
    }

    function hiddenText(it) {
        const h = it.hidden;
        if (!h) return '';
        const dt = new Date(h.ts);
        return `숨김: ${h.by || '?'} · ${pad(dt.getMonth() + 1)}/${pad(dt.getDate())} ${pad(dt.getHours())}:${pad(dt.getMinutes())}`;
    }

    function cardHtml(it, i) {
        const sev = SEV[it.severity] || SEV.INFO;
        if (it.severity === 'ERROR') {
            const msg = (it.detail && it.detail.error) || '';
            return `<div class="nd-chk-card nd-sev-err-card" tabindex="0" role="button" data-i="${i}" aria-label="점검 실패 ${esc(it.label)}">` +
                `<div class="nd-chk-r1"><span class="nd-pill nd-pill-muted">점검 실패</span><b>${esc(it.label)}</b><span class="nd-chk-when">${esc(periodText(it.checkType))}</span>${actBtn(it)}</div>` +
                `<div class="nd-chk-sub nd-chk-errmsg" title="${esc(msg)}">${esc(it.hidden ? hiddenText(it) : (msg || '오류 메시지 없음'))}</div></div>`;
        }
        let gauge = '';
        if (PCT_TYPES.indexOf(it.checkType) >= 0 && it.value !== null) {
            const w = Math.max(0, Math.min(100, it.value));
            gauge = `<div class="nd-chk-gauge"><span style="width:${w}%" class="nd-gauge-${sev.cls}"></span>` +
                (it.threshold !== null ? `<i style="left:${Math.min(100, it.threshold)}%"></i>` : '') + '</div>';
        }
        return `<div class="nd-chk-card nd-sev-${sev.cls}-card" tabindex="0" role="button" data-i="${i}" aria-label="${esc(sev.t)} ${esc(it.label)} ${esc(it.targetName)}">` +
            `<div class="nd-chk-r1"><span class="nd-pill nd-pill-${sev.cls === 'info' ? 'muted' : sev.cls}">${sev.sym} ${esc(sev.t)}</span><b>${esc(it.label)}</b>` +
            `<span class="nd-chk-when">${esc(periodText(it.checkType))}</span>${actBtn(it)}</div>` +
            `<div class="nd-chk-r2"><span class="nd-chk-target" title="${esc(it.targetName)}">${esc(it.targetName)}</span><span class="nd-chk-val">${esc(valueText(it))}</span></div>` +
            gauge + `<div class="nd-chk-sub" title="${esc(subText(it))}">${esc(it.hidden ? hiddenText(it) : subText(it))}</div></div>`;
    }

    // ================================================================== 6.4 상세

    function trendSvg(trend, threshold, unit) {
        if (!trend || trend.length < 1) return '<p class="nd-muted">추이 데이터가 아직 없습니다(하루 1개 이상 쌓이면 표시).</p>';
        const W = 420, H = 130, ml = 34, mr = 8, mt = 8, mb = 20, pw = W - ml - mr, ph = H - mt - mb;
        const vals = trend.map(p => p.value);
        const top = Math.max(threshold || 0, Math.max.apply(null, vals)) * 1.1 || 1;
        const n = trend.length;
        const x = (i) => ml + (n === 1 ? pw / 2 : i / (n - 1) * pw);
        const y = (v) => mt + ph - v / top * ph;
        let s = `<line class="nd-base" x1="${ml}" x2="${ml + pw}" y1="${mt + ph}" y2="${mt + ph}"/>`;
        [0, top / 2, top].forEach(v => { s += `<text class="nd-tick" x="${ml - 4}" y="${(y(v) + 4).toFixed(1)}" text-anchor="end">${num(v, v >= 10 ? 0 : 1)}</text>`; });
        if (threshold) s += `<line class="nd-corel" x1="${ml}" x2="${ml + pw}" y1="${y(threshold).toFixed(1)}" y2="${y(threshold).toFixed(1)}"/>`;
        s += `<polyline fill="none" stroke="var(--primary)" stroke-width="2" points="${trend.map((p, i) => `${x(i).toFixed(1)},${y(p.value).toFixed(1)}`).join(' ')}"/>`;
        trend.forEach((p, i) => {
            s += `<circle cx="${x(i).toFixed(1)}" cy="${y(p.value).toFixed(1)}" r="3" fill="var(--primary)"><title>${md(p.ts)} ${num(p.value, 1)}${unit}</title></circle>`;
            s += `<text class="nd-tick" x="${x(i).toFixed(1)}" y="${H - 5}" text-anchor="middle">${md(p.ts)}</text>`;
        });
        return `<svg class="nd-chk-trend" viewBox="0 0 ${W} ${H}" width="100%" role="img" aria-label="최근 7일 추이">${s}</svg>`;
    }

    function tableHtml(rows) {
        if (!rows || !rows.length) return '<p class="nd-muted">해당하는 객체가 없습니다.</p>';
        const cols = Object.keys(rows[0]);
        return '<div class="nd-chk-rel"><table class="nd-table"><thead><tr>' + cols.map(c => `<th>${esc(c)}</th>`).join('') + '</tr></thead><tbody>' +
            rows.map(r => '<tr>' + cols.map(c => `<td>${esc(r[c] === null || r[c] === undefined ? '' : r[c])}</td>`).join('') + '</tr>').join('') + '</tbody></table></div>';
    }

    async function viewDetail(it) {
        const K = ND.drawerKit;
        const q = new URLSearchParams({ target: it.targetName, token: getToken() });
        const res = await fetch(`/api/dashboard/${encodeURIComponent(window.currentDbId)}/checks/${encodeURIComponent(it.checkType)}?${q}`);
        const d = await res.json().catch(() => null);
        if (!res.ok || !d || d.error) throw new Error((d && d.error) || ('HTTP ' + res.status));
        const cur = d.item || it;
        const sev = SEV[cur.severity] || SEV.INFO;
        const det = cur.detail || {};
        const secs = [];
        const pairs = [['점검 값', valueText(cur)], ['임계치', cur.threshold === null || cur.threshold === undefined ? '—' : valueText(Object.assign({}, cur, { value: cur.threshold }))],
            ['점검 시각', periodText(cur.checkType)]];
        if (it.checkType === 'TABLESPACE') {
            pairs.push(['사용량', `${num(det.usedGb, 1)} GB`], ['최대 크기', `${num(det.maxGb, 1)} GB`], ['여유', `${num(det.freeGb, 1)} GB`]);
            const ae = (d.autoextend || [])[0];
            if (ae) pairs.push(['자동 확장', `데이터파일 ${num(ae.files, 0)}개 중 ${num(ae.autoextensible, 0)}개`]);
            pairs.push(['가득 찰 때까지', d.daysToFull ? `약 ${num(d.daysToFull, 1)}일 (최근 7일 증가 추세)` : '증가 추세 없음 또는 판단 불가']);
        } else if (it.checkType === 'JOB_FAIL') {
            pairs.push(['마지막 상태', det.lastStatus], ['오류 번호', det.lastError], ['2회 연속 실패', det.consecutive ? '예' : '아니오']);
        } else if (it.checkType === 'FRA') {
            pairs.push(['사용량', `${num(det.usedGb, 1)} GB`], ['한도', `${num(det.limitGb, 1)} GB`], ['회수 가능', `${num(det.reclaimableGb, 1)} GB`]);
        } else if (it.checkType === 'TEMP') {
            pairs.push(['사용량', `${num(det.usedGb, 1)} GB`], ['최대 크기(자동 확장)', `${num(det.maxGb, 1)} GB`]);
        }
        if (cur.severity === 'ERROR') pairs.push(['오류', det.error || '']);
        secs.push(K.sec('상태', K.kv(pairs)));
        if (PCT_TYPES.indexOf(it.checkType) >= 0 || it.checkType === 'TABLE_SIZE') {
            secs.push(K.sec('최근 7일 추이 (일별 최댓값)', trendSvg(d.trend, cur.threshold, it.checkType === 'TABLE_SIZE' ? ' GB' : '%')));
        }
        let related = d.related;
        if (it.checkType === 'INVALID_OBJ' && det.items) {
            related = det.items;
        }
        secs.push(K.sec('관련 객체', (d.relatedError ? `<p class="nd-top-err">관련 객체 조회 실패: ${esc(d.relatedError)}</p>` : '') + tableHtml(related) +
            (det.itemsTruncated ? '<p class="nd-muted">목록이 길어 일부만 표시합니다.</p>' : '')));
        secs.push(K.sec('조치 권장', `<p>${esc(d.action || '')}</p>`));
        secs.push(K.queries(d.queries));
        return {
            kind: `점검 알림 · ${d.label}`,
            title: it.targetName,
            sub: `<span class="nd-pill nd-pill-${sev.cls === 'info' || sev.cls === 'err' ? 'muted' : sev.cls}">${esc(sev.t)}</span> <span class="nd-muted">${esc(valueText(cur))}${cur.threshold !== null && cur.threshold !== undefined ? ' · 임계치 ' + esc(valueText(Object.assign({}, cur, { value: cur.threshold }))) : ''}</span>`,
            secs
        };
    }

    // 장시간 실행 쿼리 상세 - 카드를 누른 시점의 목록(서버가 15초마다 새로 읽는 오래 돈 순 최대 5개). 세션을 누르면 6.1 세션 상세로 이어진다.
    async function viewLong(it) {
        const K = ND.drawerKit;
        const rows = it.top || [];
        const list = rows.length
            ? '<ul class="nd-top-list nd-dw-list">' + rows.map((r, i) =>
                `<li class="nd-clk" tabindex="0" role="button" data-i="${i}"><div class="nd-rtop"><span class="nd-rid">SID ${esc(r.sid)}</span><span class="nd-rval">${esc(dur(r.elapsedSec))}</span></div>` +
                `<div class="nd-rmeta"><span>${esc(r.username || '-')}</span><span>${esc(r.sqlId || 'SQL_ID 없음')}</span><span>${esc(r.event || '')}</span></div></li>`).join('') + '</ul>'
            : `<p class="nd-muted">${it.top === null ? '세션 목록을 읽지 못했습니다(건수만 확인됨).' : '목록을 읽는 사이 세션이 끝났습니다.'}</p>`;
        return {
            kind: '점검 알림 · 장시간 실행 쿼리',
            title: `${it.value}건 실행 중`,
            sub: `<span class="nd-pill nd-pill-warn">▲ 주의</span> <span class="nd-muted">현재 호출이 ${esc(thrText(it.sec))} 이상 이어지는 사용자 세션 · 실시간</span>`,
            secs: [
                K.sec('기준', K.kv([['조건', `ACTIVE 사용자 세션의 last_call_et ${it.sec}초 이상`],
                    ['제외', '백그라운드 세션 · 모니터링 계정 · Idle 이벤트로 대기 중인 세션(AQ 리스너·sleep 등)'],
                    ['갱신', '건수는 실시간, 목록은 15초마다'], ['기준 변경', 'dbagent.monitor.long-running-query-seconds (0이면 끔)']])),
                K.sec(`오래 돈 순 상위 ${rows.length}개 · 최대 15초 전 목록`, list),
                K.sec('조치 권장', '<p>SQL_ID로 실행계획을 확인하고 배치·보고서성 작업인지 확인하세요. 다른 세션을 막고 있지 않다면 바로 종료할 필요는 없습니다. ' +
                    '종료가 필요하면 세션 상세에서 KILL 구문을 복사하세요(관리자).</p>')
            ],
            bind: (root) => root.querySelectorAll('.nd-dw-list li.nd-clk').forEach(li => {
                const r = rows[+li.dataset.i];
                const go = () => { if (ND.views && ND.views.session) ND.drawer.push(() => ND.views.session(r.sid, r.serial)); };
                li.addEventListener('click', go);
                li.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(); } });
            })
        };
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ensureDom);
    else ensureDom();
})();
