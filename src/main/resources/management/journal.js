(() => {
    'use strict';
    window.RelayJournal = {create({request, handleError, element, cell, clock, loggedIn}) {
        const $ = id => document.getElementById(id);
        let active = false, kind = 'event', epoch = 0, loading = false, failed = false, cursor = 0, nextCursor = 0;
        let history = [0], rows = [], selected = null, applied = null;
        const levelName = {INFO: '信息', WARN: '警告', ERROR: '错误'};
        function filters() {
            return {kind, level: $('eventLevel').value, port: $('eventPort').value, q: $('eventKeyword').value.trim(), hours: $('eventRange').value};
        }
        function controls() {
            for (const id of ['eventSearch', 'eventLatest', 'eventPrevious', 'eventOlder']) $(id).disabled = loading || !loggedIn();
            $('eventPrevious').disabled ||= history.length <= 1 || failed;
            $('eventOlder').disabled ||= !nextCursor || failed;
        }
        async function load(reset = false) {
            if (!loggedIn()) return;
            if (reset) { cursor = 0; history = [0]; applied = filters(); }
            if (!applied) applied = filters();
            const version = ++epoch;
            const snapshot = {...applied};
            const query = new URLSearchParams({kind: snapshot.kind, level: snapshot.level, q: snapshot.q, before: String(cursor), limit: '50', from: String(Date.now() - Number(snapshot.hours) * 3600000)});
            if (snapshot.port) query.set('port', snapshot.port);
            loading = true; controls();
            try {
                const result = await request('/api/events?' + query);
                if (version !== epoch || !loggedIn()) return;
                if (!Array.isArray(result.entries)) throw Error('返回的日志数据无效');
                rows = result.entries; nextCursor = Number(result.nextCursor || 0);
                failed = false;
                $('journalError').hidden = true;
                const storage = result.storage || {};
                const warnings = [storage.warning, storage.dropped ? storage.dropped + ' 条记录未能写入文件，可能因队列繁忙或磁盘异常。' : ''].filter(Boolean);
                $('journalWarning').textContent = warnings.join(' '); $('journalWarning').hidden = warnings.length === 0;
                $('eventsUpdated').textContent = '更新于 ' + new Date().toLocaleTimeString('zh-CN', {hour12: false});
                $('eventSummary').textContent = '本页 ' + rows.length + ' 条，匹配 ' + result.total + ' 条' + (cursor ? ' · 正在查看较早记录' : '');
                $('journalNote').textContent = '文件保存在 logs/' + document.body.dataset.mode + '/；最多保留 7 天，最多约 20 MB。页面最多索引最近 10,000 条记录。程序日志保留 INFO、WARN、ERROR，部分日志未关联端口。' + (cursor ? ' 查看较早记录时暂停自动刷新。' : '');
                render();
            } catch (error) {
                if (version !== epoch) return;
                failed = true;
                $('journalError').textContent = error.message + (rows.length ? '，当前保留上一次读取的记录。' : ''); $('journalError').hidden = false;
                if (error.status === 401 || error.status === 403 || error.status === 0) handleError(error);
            } finally { if (version === epoch) { loading = false; controls(); } }
        }
        function render() {
            const body = $('eventRows'); body.replaceChildren();
            if (!rows.length) {
                const row = element('tr'), td = cell(row, ''); td.colSpan = 5;
                const box = element('div', '', 'empty'); box.append(element('strong', '没有符合条件的记录'), element('p', '调整端口、时间或关键词后重新查询。')); td.append(box); body.append(row);
            }
            for (const item of rows) {
                const row = element('tr'); cell(row, clock(item.time), 'event-time');
                cell(row, element('span', levelName[item.level] || item.level, 'log-level ' + (item.level || '').toLowerCase()));
                const ports = item.ports || [];
                cell(row, item.port ? String(item.port) : ports.length ? ports.length + ' 个端口' : '—', 'event-port');
                const message = element('div', '', 'event-message'); message.append(element('span', item.message));
                message.append(element('small', item.kind === 'log' ? item.source : item.category)); cell(row, message);
                const actions = element('div', '', 'row-actions'), button = element('button', '详情'); button.type = 'button';
                button.addEventListener('click', () => details(item)); actions.append(button); cell(row, actions); body.append(row);
            }
        }
        function details(item) {
            selected = item;
            $('eventDetailTitle').textContent = item.kind === 'log' ? '程序日志详情' : '运行事件详情';
            $('eventDetailMeta').textContent = clock(item.time) + ' · ' + (levelName[item.level] || item.level) + ' · ' + (item.category || '程序日志');
            $('eventDetailMessage').textContent = item.message;
            const ports = item.ports?.length ? item.ports : item.port ? [item.port] : [];
            $('eventDetailText').textContent = [ports.length ? '关联公网端口：' + ports.join(', ') : '', item.source ? '来源：' + item.source : '', item.detail].filter(Boolean).join('\n\n') || '没有更多详情。';
            $('eventCopyHint').textContent = '';
            $('eventDetailDialog').showModal();
        }
        function setKind(value) {
            kind = value;
            for (const [id, name] of [['eventKindEvents', 'event'], ['eventKindLogs', 'log']]) {
                $(id).classList.toggle('active', name === kind); $(id).setAttribute('aria-pressed', String(name === kind));
            }
            $('eventMessageHeader').textContent = kind === 'event' ? '运行事件' : '程序日志';
        }
        function resetFilters() { $('eventFilters').reset(); setKind('event'); }
        $('eventFilters').addEventListener('submit', event => { event.preventDefault(); if ($('eventFilters').reportValidity()) load(true); });
        for (const id of ['eventLevel', 'eventRange']) $(id).addEventListener('change', () => { if ($('eventFilters').reportValidity()) load(true); });
        $('eventKindEvents').addEventListener('click', () => { setKind('event'); load(true); });
        $('eventKindLogs').addEventListener('click', () => { setKind('log'); load(true); });
        $('eventReset').addEventListener('click', () => { resetFilters(); load(true); });
        $('eventLatest').addEventListener('click', () => load(true));
        $('eventOlder').addEventListener('click', () => { if (nextCursor) { history.push(nextCursor); cursor = nextCursor; load(); } });
        $('eventPrevious').addEventListener('click', () => { if (history.length > 1) { history.pop(); cursor = history.at(-1); load(); } });
        $('eventCopy').addEventListener('click', async () => {
            if (!selected) return;
            try { await navigator.clipboard.writeText($('eventDetailMeta').textContent + '\n' + $('eventDetailMessage').textContent + '\n\n' + $('eventDetailText').textContent); $('eventCopyHint').textContent = '已复制'; }
            catch { $('eventCopyHint').textContent = '浏览器未允许复制，可选中上方详情手动复制。'; }
        });
        setInterval(() => { if (active && loggedIn() && !loading && !cursor && $('eventsAuto').checked && document.visibilityState === 'visible') load(); }, 5000);
        return {
            activate(value) { active = value; if (value) load(true); },
            forPort(port) { $('eventPort').value = port; setKind('event'); load(true); },
            reset() { active = false; epoch++; loading = false; cursor = 0; nextCursor = 0; history = [0]; rows = []; applied = null; selected = null; resetFilters(); $('journalError').hidden = true; $('journalWarning').hidden = true; $('eventRows').replaceChildren(); $('eventDetailText').textContent = ''; $('eventDetailMessage').textContent = ''; if ($('eventDetailDialog').open) $('eventDetailDialog').close(); controls(); }
        };
    }};
})();
