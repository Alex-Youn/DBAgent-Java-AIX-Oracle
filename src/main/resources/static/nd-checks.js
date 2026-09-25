// 새 대시보드 ⑧ 점검 알림 · 6.4 점검 알림 상세 - 설계문서 `대시보드 UI 개선 설계.md` 5장 ⑧·6.4, 전체 작업순서 F8(2026-09-26).
// nd-dashboard.js·nd-top.js(드로어) 다음에 로드.
//
// - 목록: /api/dashboard/{dbId}/checks (저장소만 읽는 가벼운 조회, 느린 주기 60초). 점검 자체는 서버가 10분/1일 주기로 한다.
// - 헤더: 심각도별 건수 + 마지막 점검 + 필터(전체/위험/주의/확인). 카드는 위험→주의→확인, 점검 실패는 회색, 가로 한 줄
//   (넘치면 프레임 안에서만 가로 스크롤). OK 행은 카드로 그리지 않고 항목별 마지막 점검 시각에만 쓴다.
// - 카드 클릭 → 6.4 상세 드로어(7일 추이·관련 객체·조치 권장·조회 쿼리).
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
    const st = { data: null, filter: 'all', gen: 0 };

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
            case 'JOB_FAIL': return `최근 24시간 실패 ${num(it.value, 0)}회${d.consecutive ? ' · 2회 연속 실패' : ''} · 마지막 ${d.lastFail || '-'}`;
            case 'INVALID_OBJ': case 'STALE_STATS': {
                const items = d.items || [];
                const first = items[0] ? [items[0].owner, items[0].object_name || items[0].table_name].filter(Boolean).join('.') : '';
                return `${num(it.value, 0)}개${first ? ' · ' + first + (items.length > 1 ? ' 외' : '') : ''}`;
            }
            default: return '';
        }
    }

    function periodText(type) {
        const d = st.data;
        const ts = d && d.lastByType ? d.lastByType[type] : null;
        const daily = ['TABLE_SIZE', 'INVALID_OBJ', 'STALE_STATS'].indexOf(type) >= 0;
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

    ND.onReset(() => {
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
            '<button type="button" data-f="WARN">주의</button><button type="button" data-f="INFO">확인</button></span>';
        card.querySelector('.nd-checks-body').id = 'nd-checks-body';
        $('nd-checks-filter').querySelectorAll('button').forEach(b => b.addEventListener('click', () => {
            st.filter = b.dataset.f;
            $('nd-checks-filter').querySelectorAll('button').forEach(x => x.classList.toggle('active', x === b));
            render();
        }));
    }

    function render() {
        const d = st.data;
        if (!d) return;
        const items = (d.items || []).filter(it => it.severity !== 'OK');
        const count = (s) => items.filter(it => it.severity === s).length;
        const fail = count('ERROR');
        $('nd-checks-meta').innerHTML =
            `<span class="nd-chk-cnt nd-sev-crit">■ 위험 ${count('CRIT')}</span> · <span class="nd-chk-cnt nd-sev-warn">▲ 주의 ${count('WARN')}</span> · ` +
            `<span class="nd-chk-cnt nd-sev-info">○ 확인 ${count('INFO')}</span>` + (fail ? ` · <span class="nd-chk-cnt nd-sev-err">실패 ${fail}</span>` : '') +
            `<span class="nd-muted"> · 마지막 점검 ${d.lastLight ? hm(d.lastLight) : '—'} · ${d.intervalMinutes}분 주기</span>`;
        const shown = items
            .filter(it => st.filter === 'all' || it.severity === st.filter || (st.filter === 'all' && it.severity === 'ERROR'))
            .sort((a, b) => SEV[a.severity].order - SEV[b.severity].order || (b.value || 0) - (a.value || 0));
        const body = $('nd-checks-body');
        if (!d.lastLight && !d.lastDaily) {
            body.innerHTML = '<div class="nd-placeholder">아직 점검 전입니다 — 앱 기동 후 30초~1분 안에 첫 점검이 끝납니다.</div>';
            return;
        }
        if (!shown.length) {
            body.innerHTML = `<div class="nd-placeholder">${st.filter === 'all' ? '임계치를 넘은 점검 항목이 없습니다.' : '해당 심각도의 항목이 없습니다.'}</div>`;
            return;
        }
        body.innerHTML = '<div class="nd-chk-cards">' + shown.map((it, i) => cardHtml(it, i)).join('') + '</div>';
        body.querySelectorAll('.nd-chk-card').forEach(c => {
            const it = shown[+c.dataset.i];
            const open = () => ND.drawer && ND.drawer.open(() => viewDetail(it), c);
            c.addEventListener('click', open);
            c.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } });
        });
    }

    function cardHtml(it, i) {
        const sev = SEV[it.severity] || SEV.INFO;
        if (it.severity === 'ERROR') {
            const msg = (it.detail && it.detail.error) || '';
            return `<div class="nd-chk-card nd-sev-err-card" tabindex="0" role="button" data-i="${i}" aria-label="점검 실패 ${esc(it.label)}">` +
                `<div class="nd-chk-r1"><span class="nd-pill nd-pill-muted">점검 실패</span><b>${esc(it.label)}</b><span class="nd-chk-when">${esc(periodText(it.checkType))}</span></div>` +
                `<div class="nd-chk-sub nd-chk-errmsg" title="${esc(msg)}">${esc(msg || '오류 메시지 없음')}</div></div>`;
        }
        let gauge = '';
        if (PCT_TYPES.indexOf(it.checkType) >= 0 && it.value !== null) {
            const w = Math.max(0, Math.min(100, it.value));
            gauge = `<div class="nd-chk-gauge"><span style="width:${w}%" class="nd-gauge-${sev.cls}"></span>` +
                (it.threshold !== null ? `<i style="left:${Math.min(100, it.threshold)}%"></i>` : '') + '</div>';
        }
        return `<div class="nd-chk-card nd-sev-${sev.cls}-card" tabindex="0" role="button" data-i="${i}" aria-label="${esc(sev.t)} ${esc(it.label)} ${esc(it.targetName)}">` +
            `<div class="nd-chk-r1"><span class="nd-pill nd-pill-${sev.cls === 'info' ? 'muted' : sev.cls}">${sev.sym} ${esc(sev.t)}</span><b>${esc(it.label)}</b>` +
            `<span class="nd-chk-when">${esc(periodText(it.checkType))}</span></div>` +
            `<div class="nd-chk-r2"><span class="nd-chk-target" title="${esc(it.targetName)}">${esc(it.targetName)}</span><span class="nd-chk-val">${esc(valueText(it))}</span></div>` +
            gauge + `<div class="nd-chk-sub" title="${esc(subText(it))}">${esc(subText(it))}</div></div>`;
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
        if ((it.checkType === 'INVALID_OBJ' || it.checkType === 'STALE_STATS') && det.items) {
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

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ensureDom);
    else ensureDom();
})();
