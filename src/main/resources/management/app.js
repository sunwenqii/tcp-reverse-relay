(() => {
    'use strict';
    const $ = id => document.getElementById(id);
    const client = document.body.dataset.mode === 'client';
    let setupMode = document.body.dataset.setup === 'true';
    let token = '', forwards = [], clients = [], page = 1, view = 'mappings';
    let stale = false, lastSuccess = 0, generation = 0, loading = null, canChangePassword = false;
    let toastTimer, batchAction = 'add', previewKey = '', diagnosticPort = null, diagnosticGeneration = 0, finishConfirmation = null;
    const selected = new Set(), busy = new Set();
    let ownerPort = null, connection = null;
    const journalUI = window.RelayJournal.create({request, handleError, element, cell, clock, loggedIn: () => Boolean(token)});

    function element(tag, text = '', className = '') {
        const node = document.createElement(tag);
        node.textContent = text;
        node.className = className;
        return node;
    }
    function cell(row, value, className = '') {
        const node = element('td', '', className);
        if (value instanceof Node) node.append(value); else node.textContent = value;
        row.append(node); return node;
    }
    function clock(time) { return time ? new Date(time).toLocaleString('zh-CN', {hour12: false}) : '—'; }
    function address(host, port) { return (host.includes(':') ? '[' + host + ']' : host) + ':' + port; }
    function message(result, fallback) { return result && typeof result === 'object' ? result.message || fallback : result || fallback; }
    function writable() { return Boolean(token) && !stale; }
    function setBusy(id, enabled) { enabled ? busy.add(id) : busy.delete(id); syncControls(); }
    function syncControls() {
        for (const id of ['addButton', 'batchButton', 'publicSwitch', 'enableSelected', 'disableSelected', 'deleteSelected', 'saveButton', 'savePassword', 'healthButton', 'saveHealth', 'saveOwner', 'saveConnection'])
            $(id).disabled = !writable() || busy.has(id);
        $('applyBatch').disabled = !writable() || busy.has('applyBatch') || !previewKey || previewKey !== batchAction + '\n' + $('batchEntries').value;
        for (const id of ['refreshButton', 'refreshClients', 'retryButton']) $(id).disabled = !token || Boolean(loading);
        for (const id of ['searchInput', 'statusFilter', 'selectPage', 'showClients', 'showEvents']) $(id).disabled = !token;
        $('connectionButton').disabled = !writable() || !connection || busy.has('connectionButton');
        $('passwordButton').disabled = !writable() || !canChangePassword;
        $('passwordButton').title = canChangePassword ? '' : '当前实例的管理密码由外部配置提供';
        document.querySelectorAll('#mappingRows .row-actions button').forEach(button => { button.disabled = !writable(); });
    }
    function configureGate() {
        $('gateTitle').textContent = setupMode ? '首次设置管理密码' : '登录管理控制台';
        $('gateDescription').textContent = setupMode ? '设置 8 至 128 位管理密码。首次设置只能在本机完成。' : '请输入当前设备的管理密码，刷新页面后需重新登录。';
        $('tokenLabel').textContent = setupMode ? '设置密码' : '管理密码';
        $('tokenInput').minLength = setupMode ? 8 : 1;
        $('tokenInput').maxLength = 128;
        $('tokenInput').autocomplete = setupMode ? 'new-password' : 'off';
        $('confirmField').hidden = !setupMode;
        $('confirmInput').required = setupMode;
        $('connectButton').textContent = setupMode ? '保存密码并进入' : '进入控制台';
    }
    configureGate();
    if (client) {
        $('pageSubtitle').textContent = '管理公网端口与目标服务之间的转发关系。';
        $('connectedLabel').textContent = '已注册映射';
        $('connectedCaption').textContent = '已与服务端建立控制连接';
        $('waitingLabel').textContent = '待注册映射';
        $('waitingCaption').textContent = '连接恢复后自动重试';
        $('activityLabel').textContent = '已配置目标';
        $('activityCaption').textContent = '本机或局域网目标服务';
        $('activityLabel').title = '已保存目标地址的映射数量';
        $('statusHeader').textContent = '注册状态';
        $('targetHeader').textContent = '目标服务';
        $('statusFilter').querySelector('[value="connected"]').textContent = '已注册';
        $('statusFilter').querySelector('[value="waiting"]').textContent = '待注册';
        $('roleHint').textContent = '配置保存后自动注册；目标地址从客户端所在机器访问。修改目标只影响新连接。';
        $('targetHost').required = true;
        $('targetPort').required = true;
    }

    function notice(text, error = false) {
        $('toast').textContent = text;
        $('toast').classList.toggle('error', error);
        $('toast').hidden = false;
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4500);
    }
    async function request(path, fields, timeout = 8000) {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeout);
        try {
            const options = {headers: {Authorization: 'Bearer ' + token}, cache: 'no-store', signal: controller.signal};
            if (fields) {
                options.method = 'POST'; options.headers['Content-Type'] = 'application/x-www-form-urlencoded';
                options.body = new URLSearchParams(fields);
            }
            const response = await fetch(path, options);
            const body = await response.text();
            if (!response.ok) { const error = new Error(body || '操作失败'); error.status = response.status; throw error; }
            return response.headers.get('Content-Type')?.includes('application/json') ? JSON.parse(body) : body;
        } catch (error) {
            if (error.status == null) {
                error.status = 0;
                error.message = error.name === 'AbortError' ? '管理服务响应超时' : '无法连接管理服务';
                if (fields) error.message += '，操作结果尚未确认，请在连接恢复后检查列表';
            }
            throw error;
        } finally { clearTimeout(timer); }
    }
    function markStale(error) {
        stale = true;
        $('connectionBanner').hidden = false;
        $('connectionDescription').textContent = error.message + '。当前显示上一次的数据' + (lastSuccess ? '（' + clock(lastSuccess) + '）' : '') + '，恢复连接后会自动更新。';
        $('mainContent').classList.add('stale');
        syncControls();
    }
    function handleError(error, inline) {
        if (error.status === 401 || error.status === 403) {
            lock(); $('gateError').textContent = error.message;
        } else {
            if (error.status === 0) markStale(error);
            if (inline) $(inline).textContent = error.message; else notice(error.message, true);
        }
    }
    async function load(silent = true) {
        if (!token) return;
        if (loading) return loading;
        const epoch = generation;
        const job = (async () => {
            try {
                const [state, directory] = await Promise.all([request('/api/forwards'), client ? Promise.resolve({clients: []}) : request('/api/clients')]);
                if (epoch !== generation || !token) return;
                if (!Array.isArray(state.forwards) || !Array.isArray(directory.clients)) throw new Error('返回的管理数据无效');
                forwards = state.forwards.slice().sort((a, b) => a.port - b.port);
                clients = directory.clients;
                if (client) { connection = state.connection || null; renderConnection(); }
                for (const port of selected) if (!forwards.some(item => item.port === port)) selected.delete(port);
                lastSuccess = Date.now(); stale = false;
                $('connectionBanner').hidden = true; $('mainContent').classList.remove('stale');
                render(); renderClients();
                if (!silent) notice('状态已更新');
            } catch (error) {
                if (epoch !== generation) return;
                if (error.status === 401 || error.status === 403) handleError(error); else markStale(error);
                throw error;
            }
        })();
        loading = job; syncControls();
        try { return await job; } finally { if (loading === job) loading = null; syncControls(); }
    }
    async function loadSettings() {
        const settings = await request('/api/settings');
        $('publicSwitch').checked = Boolean(settings.publicAccess);
        $('accessDescription').textContent = settings.publicAccess ? '已允许其他设备访问' : '当前仅本机可访问';
        canChangePassword = Boolean(settings.canChangePassword);
        syncControls();
    }
    function status(item) {
        if (item.enabled === false || item.state === 'paused') return ['已暂停', 'paused'];
        if (item.connected) return [client ? '已注册' : '在线', 'connected'];
        if (item.state === 'connecting') return ['注册中', 'waiting'];
        if (item.state === 'error') return [client ? '注册失败' : '监听异常', 'failed'];
        return [client ? '待注册' : '离线', 'waiting'];
    }
    function visibleRows() {
        const query = $('searchInput').value.trim().toLowerCase();
        const filter = $('statusFilter').value;
        return forwards.filter(item => {
            const text = [item.port, item.target, item.targetHost, item.clientName, item.clientId, item.ownerId].filter(value => value != null).join(' ').toLowerCase();
            const enabled = item.enabled !== false;
            return (!query || text.includes(query)) && (filter === 'all' || filter === 'paused' && !enabled
                || filter === 'connected' && enabled && item.connected || filter === 'waiting' && enabled && !item.connected
                || filter === 'error' && item.state === 'error' || filter === 'unhealthy' && ['down', 'suspect', 'stale'].includes(item.health?.state));
        });
    }
    function render() {
        const connected = forwards.filter(item => item.connected).length;
        const paused = forwards.filter(item => item.enabled === false).length;
        $('totalCount').textContent = String(forwards.length);
        $('totalCaption').textContent = paused ? '其中 ' + paused + ' 个已暂停' : '已保存的规则';
        $('connectedCount').textContent = String(connected);
        $('waitingCount').textContent = String(forwards.length - connected - paused);
        $('activityCount').textContent = String(client ? forwards.filter(item => item.target != null).length : forwards.reduce((sum, item) => sum + (item.connections || 0), 0));
        $('listCount').textContent = String(forwards.length);
        $('updatedAt').textContent = lastSuccess ? '更新于 ' + new Date(lastSuccess).toLocaleTimeString('zh-CN', {hour12: false}) : '尚未读取';
        const shown = visibleRows(), pages = Math.max(1, Math.ceil(shown.length / 20));
        page = Math.min(page, pages);
        const rows = shown.slice((page - 1) * 20, page * 20), tbody = $('mappingRows');
        tbody.replaceChildren();
        if (!rows.length) {
            const row = element('tr'), td = cell(row, ''); td.colSpan = client ? 6 : 7;
            const box = element('div', '', 'empty');
            box.append(element('strong', token ? (forwards.length ? '没有匹配的映射' : '还没有端口映射') : '请先登录管理页'),
                element('p', forwards.length ? '调整搜索条件或状态筛选。' : '使用新增映射或批量添加开始配置。'));
            td.append(box); tbody.append(row);
        }
        for (const item of rows) {
            const row = element('tr'), checkbox = document.createElement('input');
            checkbox.type = 'checkbox'; checkbox.checked = selected.has(item.port); checkbox.disabled = !token;
            checkbox.setAttribute('aria-label', '选择端口 ' + item.port);
            checkbox.addEventListener('change', () => { checkbox.checked ? selected.add(item.port) : selected.delete(item.port); render(); });
            cell(row, checkbox, 'checkbox-cell');
            cell(row, element('span', String(item.port), 'port-main')).append(element('span', 'TCP · 公网', 'port-sub'));
            const target = cell(row, item.target != null ? address(item.targetHost || '127.0.0.1', item.target) : '等待客户端上报', item.target == null ? 'target-muted' : 'target-main');
            if (!client && (item.clientName || item.ownerId)) {
                const owner = element('span', item.clientName || '已绑定客户端', 'port-sub');
                owner.title = '归属：' + (item.ownerId || '首次注册后绑定'); target.append(owner);
            }
            const [label, className] = status(item), state = element('span', label, 'status ' + className);
            state.title = item.connected ? '客户端控制连接已建立；不代表目标应用健康状态' : item.enabled === false ? '配置已保留，恢复后重新注册' : '等待控制连接建立或恢复';
            const statusCell = cell(row, state);
            if (item.lastError) { const detail = element('span', item.lastError, 'status-detail'); detail.title = item.lastError; statusCell.append(detail); }
            else if (client && item.enabled !== false && !item.connected) statusCell.append(element('span', '自动重试中', 'port-sub'));
            const health = item.health || {state: 'pending'};
            const healthLabels = {up: ['可达', 'connected'], down: ['不可达', 'failed'], suspect: ['待确认', 'waiting'], pending: ['待检测', 'waiting'],
                disabled: ['未启用', 'paused'], paused: ['已暂停', 'paused'], offline: ['无报告', 'paused'], stale: ['已过期', 'waiting'], unsupported: ['待升级', 'paused']};
            const [healthText, healthClass] = healthLabels[health.state] || ['待检测', 'waiting'];
            const healthCell = cell(row, element('span', healthText, 'status ' + healthClass));
            healthCell.title = [health.message, health.checkedAt ? clock(health.checkedAt) + ' · ' + health.durationMs + ' ms' : '尚无检查结果'].filter(Boolean).join(' · ');
            if (health.checkedAt) healthCell.append(element('span', health.durationMs + ' ms', 'port-sub'));
            if (!client) { const count = cell(row, String(item.connections || 0), 'connection-count'); count.title = '含等待接通的外部 TCP 连接'; }
            const actions = element('div', '', 'row-actions');
            for (const [text, action, css] of [
                ['诊断', () => openDiagnostic(item.port), ''], ['日志', () => { showView('events'); journalUI.forPort(item.port); }, ''], ['编辑', () => openDrawer(item), 'write-action'],
                ...(!client ? [['归属', () => openOwner(item), 'write-action']] : []),
                [item.enabled === false ? '启用' : '暂停', () => openBatch(item.enabled === false ? 'enable' : 'disable', [item.port]), 'write-action'],
                ['删除', () => deleteForward(item), 'delete write-action']]) {
                const button = element('button', text, css); button.type = 'button'; button.disabled = !writable();
                button.setAttribute('aria-label', text + '端口 ' + item.port); button.addEventListener('click', action); actions.append(button);
            }
            cell(row, actions); tbody.append(row);
        }
        $('selectPage').checked = rows.length > 0 && rows.every(item => selected.has(item.port));
        $('selectPage').indeterminate = rows.some(item => selected.has(item.port)) && !$('selectPage').checked;
        $('selectionBar').hidden = selected.size === 0;
        $('selectionCount').textContent = '已选择 ' + selected.size + ' 个映射';
        $('resultSummary').textContent = shown.length ? '显示 ' + ((page - 1) * 20 + 1) + '–' + Math.min(page * 20, shown.length) + ' 条，共 ' + shown.length + ' 条' : '共 0 条映射';
        $('pageIndicator').textContent = shown.length ? page + ' / ' + pages : '—';
        $('prevButton').disabled = page <= 1; $('nextButton').disabled = page >= pages;
        syncControls();
    }
    function renderClients() {
        $('clientCount').textContent = String(clients.length);
        const body = $('clientRows'); body.replaceChildren();
        if (!clients.length) { const row = element('tr'), td = cell(row, '尚无客户端注册记录', 'empty'); td.colSpan = 6; body.append(row); }
        for (const item of clients) {
            const row = element('tr');
            cell(row, element('span', item.name || '未命名客户端', 'port-main')).append(element('span', (item.id || '').slice(0, 12) + ' · ' + (item.version || '旧版'), 'port-sub'));
            cell(row, item.address || '—'); cell(row, element('span', item.online ? '在线' : '离线', 'status ' + (item.online ? 'connected' : 'paused')));
            const mappings = item.mappings || [];
            cell(row, mappings.filter(mapping => mapping.connected).length + ' 在线 / ' + mappings.length + ' 个端口');
            cell(row, clock(item.lastSeenAt));
            const button = element('button', '查看映射', 'text-button'); button.type = 'button';
            button.addEventListener('click', () => { showView('mappings'); $('searchInput').value = item.id; page = 1; render(); });
            const actions = element('div', '', 'row-actions'); actions.append(button); cell(row, actions); body.append(row);
        }
    }
    function showView(next) {
        view = next;
        $('mappingView').hidden = view !== 'mappings'; $('mappingActions').hidden = view !== 'mappings'; $('clientsView').hidden = view !== 'clients';
        $('eventsView').hidden = view !== 'events'; journalUI.activate(view === 'events');
        $('pageTitle').textContent = view === 'events' ? '运行日志' : view === 'clients' ? '客户端' : '端口映射';
        $('pageSubtitle').textContent = view === 'events' ? '追踪连接变化、配置操作与程序异常。' : view === 'clients' ? '查看客户端身份、接入来源和端口注册情况。' : client ? '管理公网端口与目标服务之间的转发关系。' : '管理服务端开放的公网 TCP 端口。';
        for (const [id, name] of [['showMappings', 'mappings'], ['showClients', 'clients'], ['showEvents', 'events']]) {
            $(id).classList.toggle('active', view === name);
            if (view === name) $(id).setAttribute('aria-current', 'page'); else $(id).removeAttribute('aria-current');
        }
    }
    function closeDrawer() { $('drawer').hidden = true; $('drawerBackdrop').hidden = true; }
    function openDrawer(item) {
        $('mappingForm').reset(); $('drawerError').textContent = '';
        $('oldPort').value = item ? item.port : ''; $('remotePort').value = item ? item.port : '';
        $('targetHost').value = item?.targetHost || '127.0.0.1'; $('targetPort').value = item?.target ?? '';
        $('drawerTitle').textContent = item ? '编辑映射' : '新增映射';
        $('drawerIntro').textContent = client ? '保存目标地址，客户端会自动向服务端注册。' : '服务端开放端口后，客户端即可申请注册。';
        $('drawerNote').textContent = item?.enabled === false ? '当前映射已暂停，保存后仍保持暂停。'
            : client ? '目标修改只影响新连接；更换公网端口会中断该映射的现有连接。' : '更换公网端口会中断该映射的现有连接，目标服务地址由客户端配置。';
        $('drawerBackdrop').hidden = false; $('drawer').hidden = false; $('remotePort').focus();
    }
    function confirmOperation(title, details, label = '确认') {
        if (finishConfirmation) return Promise.resolve(false);
        return new Promise(resolve => {
            const dialog = $('deleteDialog'), listeners = new AbortController();
            $('deleteTitle').textContent = title; $('deleteMessage').textContent = details; $('deleteSubmit').textContent = label;
            const finish = accepted => { listeners.abort(); dialog.close(); finishConfirmation = null; resolve(accepted); };
            finishConfirmation = finish;
            $('deleteCancel').addEventListener('click', () => finish(false), {signal: listeners.signal});
            $('deleteSubmit').addEventListener('click', () => finish(true), {signal: listeners.signal});
            dialog.addEventListener('cancel', event => { event.preventDefault(); finish(false); }, {signal: listeners.signal});
            dialog.showModal();
        });
    }
    async function deleteForward(item) {
        if (!await confirmOperation('删除端口映射？', '端口 ' + item.port + ' 的现有连接会断开，配置也会被删除。', '删除映射')) return;
        if (!writable()) return;
        try { const result = await request('/api/forwards/delete', {port: item.port}); selected.delete(item.port); await load(); notice(message(result, '映射已删除')); }
        catch (error) { handleError(error); }
    }
    function openBatch(action, ports = []) {
        batchAction = action; previewKey = ''; $('batchError').textContent = ''; $('batchPreview').hidden = true;
        const names = {add: '批量添加', enable: '批量启用', disable: '批量暂停', delete: '批量删除'};
        $('batchTitle').textContent = names[action]; $('batchEntries').readOnly = action !== 'add';
        $('batchEntries').value = action === 'add' ? '' : ports.join(',');
        $('batchHint').textContent = action === 'add' ? (client ? '每行一个映射，支持 公网端口:目标端口 或 公网端口:目标地址:目标端口。' : '支持单端口、逗号分隔和区间；区间不包含结束端口。') : '先预览操作范围，确认后统一保存。暂停和删除会中断现有连接。';
        $('batchEntries').placeholder = client ? '18080:127.0.0.1:8080\n20000:192.168.1.10:9000' : '18080,20000-20010';
        $('batchDialog').showModal(); syncControls();
    }
    async function previewBatch() {
        $('batchError').textContent = ''; previewKey = ''; setBusy('previewBatch', true); $('previewBatch').disabled = true;
        const entries = $('batchEntries').value;
        try {
            const result = await request('/api/forwards/batch/preview', {action: batchAction, entries});
            if (entries !== $('batchEntries').value) return;
            previewKey = batchAction + '\n' + entries;
            $('batchPreview').replaceChildren(element('strong', '共 ' + result.count + ' 个映射'),
                element('p', result.ports.slice(0, 12).join(', ') + (result.ports.length > 12 ? ' …' : '')),
                element('p', (result.connections || 0) + ' 条现有连接涉及此次操作。' + result.message));
            $('batchPreview').hidden = false;
        } catch (error) { handleError(error, 'batchError'); }
        finally { busy.delete('previewBatch'); $('previewBatch').disabled = !writable(); syncControls(); }
    }
    async function applyBatch() {
        if (!previewKey || !writable()) return;
        setBusy('applyBatch', true);
        try {
            const result = await request('/api/forwards/batch', {action: batchAction, entries: $('batchEntries').value}, 45000);
            for (const port of result.ports) selected.delete(port);
            $('batchDialog').close(); previewKey = ''; await load(); notice(message(result, '批量操作已完成'));
        } catch (error) { previewKey = ''; $('batchPreview').hidden = true; handleError(error, 'batchError'); }
        finally { setBusy('applyBatch', false); }
    }
    async function openDiagnostic(port) {
        const epoch = ++diagnosticGeneration;
        diagnosticPort = port; $('diagnosticTitle').textContent = '映射诊断 · ' + port;
        if (!$('diagnosticDialog').open) $('diagnosticDialog').showModal();
        $('diagnosticChecks').replaceChildren(element('p', '正在检查连接，请稍候…', 'modal-note'));
        $('rerunDiagnostic').disabled = true;
        try {
            const result = await request('/api/forwards/diagnose', {port}, 12000);
            if (epoch !== diagnosticGeneration) return;
            $('diagnosticTime').textContent = '检查于 ' + clock(result.checkedAt); $('diagnosticChecks').replaceChildren();
            const labels = {passed: '通过', failed: '失败', skipped: '未检查', pending: '等待'};
            for (const check of result.checks) {
                const row = element('div', '', 'diagnostic-row'), heading = element('div', '', 'diagnostic-heading');
                heading.append(element('strong', check.name), element('span', labels[check.status] || check.status, 'check-state ' + check.status));
                row.append(heading, element('p', check.detail), element('small', check.durationMs ? check.durationMs + ' ms' : ''));
                $('diagnosticChecks').append(row);
            }
        } catch (error) { if (epoch === diagnosticGeneration) { $('diagnosticChecks').replaceChildren(element('p', error.message, 'form-error')); handleError(error); } }
        finally { if (epoch === diagnosticGeneration) $('rerunDiagnostic').disabled = false; }
    }
    function lock() {
        diagnosticGeneration++;
        token = ''; generation++; loading = null; stale = false; forwards = []; clients = []; selected.clear();
        connection = null; renderConnection(); $('connectionForm').reset();
        journalUI.reset();
        $('gate').hidden = false; $('lockButton').hidden = true; $('passwordButton').hidden = true;
        $('tokenInput').value = ''; $('confirmInput').value = ''; $('connectionBanner').hidden = true;
        $('mainContent').classList.remove('stale'); closeDrawer();
        if (finishConfirmation) finishConfirmation(false);
        for (const id of ['diagnosticDialog', 'batchDialog', 'passwordDialog', 'ownerDialog', 'healthDialog', 'connectionDialog']) if ($(id).open) $(id).close();
        $('passwordForm').reset(); previewKey = ''; showView('mappings'); render(); renderClients();
        for (const id of ['totalCount', 'connectedCount', 'waitingCount', 'activityCount']) $(id).textContent = '—';
        $('updatedAt').textContent = '尚未读取'; $('tokenInput').focus();
    }

    $('tokenForm').addEventListener('submit', async event => {
        event.preventDefault(); $('gateError').textContent = '';
        const password = $('tokenInput').value;
        if (setupMode && password !== $('confirmInput').value) { $('gateError').textContent = '两次输入的密码不一致'; return; }
        token = password; generation++; $('connectButton').disabled = true;
        try {
            if (setupMode) { await request('/api/setup', {password}); setupMode = false; configureGate(); }
            await load(); await loadSettings();
            $('gate').hidden = true; $('lockButton').hidden = false; $('passwordButton').hidden = false;
            $('tokenInput').value = ''; $('confirmInput').value = ''; syncControls(); $('addButton').focus();
        } catch (error) { token = ''; generation++; $('gateError').textContent = error.message; syncControls(); }
        finally { $('connectButton').disabled = false; }
    });
    $('showToken').addEventListener('click', () => { const input = $('tokenInput'); input.type = input.type === 'password' ? 'text' : 'password'; $('showToken').textContent = input.type === 'password' ? '显示' : '隐藏'; });
    $('lockButton').addEventListener('click', () => { $('gateError').textContent = ''; lock(); });
    $('addButton').addEventListener('click', () => openDrawer(null));
    $('batchButton').addEventListener('click', () => openBatch('add'));
    $('showMappings').addEventListener('click', () => showView('mappings'));
    $('showClients').addEventListener('click', () => showView('clients'));
    $('showEvents').addEventListener('click', () => showView('events'));
    for (const id of ['refreshButton', 'refreshClients', 'retryButton']) $(id).addEventListener('click', () => load(false).catch(() => {}));
    for (const id of ['searchInput', 'statusFilter']) $(id).addEventListener(id === 'searchInput' ? 'input' : 'change', () => { page = 1; render(); });
    $('prevButton').addEventListener('click', () => { page--; render(); }); $('nextButton').addEventListener('click', () => { page++; render(); });
    $('selectPage').addEventListener('change', event => { const checked = event.target.checked; visibleRows().slice((page - 1) * 20, page * 20).forEach(item => checked ? selected.add(item.port) : selected.delete(item.port)); render(); });
    $('clearSelection').addEventListener('click', () => { selected.clear(); render(); });
    for (const [id, action] of [['enableSelected', 'enable'], ['disableSelected', 'disable'], ['deleteSelected', 'delete']]) $(id).addEventListener('click', () => openBatch(action, [...selected]));
    for (const id of ['closeDrawer', 'cancelButton', 'drawerBackdrop']) $(id).addEventListener('click', closeDrawer);
    document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
    $('batchEntries').addEventListener('input', () => { previewKey = ''; $('batchPreview').hidden = true; syncControls(); });
    $('previewBatch').addEventListener('click', previewBatch); $('applyBatch').addEventListener('click', applyBatch);
    $('batchForm').addEventListener('submit', event => { event.preventDefault(); previewBatch(); });
    $('rerunDiagnostic').addEventListener('click', () => openDiagnostic(diagnosticPort));
    $('mappingForm').addEventListener('submit', async event => {
        event.preventDefault(); $('drawerError').textContent = '';
        if (!writable()) return;
        const oldPort = $('oldPort').value, port = $('remotePort').value;
        if (oldPort && oldPort !== port) {
            const previous = forwards.find(item => String(item.port) === oldPort);
            if (!await confirmOperation('更换公网端口？', '端口 ' + oldPort + ' 的 ' + (previous?.connections || 0) + ' 条现有连接会中断。', '保存并更换')) return;
        }
        if (!writable()) return;
        const fields = {port}; if (oldPort) fields.oldPort = oldPort;
        if (client) { fields.targetHost = $('targetHost').value.trim(); fields.target = $('targetPort').value; }
        setBusy('saveButton', true);
        try { const result = await request('/api/forwards', fields); closeDrawer(); await load(); notice(message(result, '配置已保存')); }
        catch (error) { handleError(error, 'drawerError'); }
        finally { setBusy('saveButton', false); }
    });
    $('publicSwitch').addEventListener('change', async event => {
        const enabled = event.target.checked; setBusy('publicSwitch', true);
        try {
            await request('/api/settings', {public: String(enabled)});
            $('accessDescription').textContent = enabled ? '已允许其他设备访问' : '当前仅本机可访问';
            notice(enabled ? '外网访问已开启，请确认防火墙已放行管理端口' : '外网访问已关闭');
            if (!enabled && !['127.0.0.1', 'localhost', '::1', '[::1]'].includes(location.hostname)) { lock(); $('gateError').textContent = '外网访问已关闭，请从运行程序的电脑访问管理页'; }
        } catch (error) { event.target.checked = !enabled; handleError(error); }
        finally { setBusy('publicSwitch', false); }
    });
    $('passwordButton').addEventListener('click', () => { $('passwordForm').reset(); $('passwordError').textContent = ''; $('passwordDialog').showModal(); $('currentPassword').focus(); });
    $('passwordForm').addEventListener('submit', async event => {
        event.preventDefault(); $('passwordError').textContent = '';
        if ($('newPassword').value !== $('confirmPassword').value) { $('passwordError').textContent = '两次新密码不一致'; return; }
        setBusy('savePassword', true);
        try {
            await request('/api/password', {currentPassword: $('currentPassword').value, newPassword: $('newPassword').value});
            lock(); $('gateError').textContent = '密码已修改，请使用新密码登录';
        } catch (error) { if (error.status === 0) markStale(error); $('passwordError').textContent = error.message; }
        finally { setBusy('savePassword', false); }
    });
    function openOwner(item) {
        ownerPort = item.port; $('ownerPortLabel').textContent = '公网端口 ' + item.port;
        $('ownerInput').value = item.ownerId || ''; $('ownerError').textContent = '';
        $('ownerClients').replaceChildren(...clients.map(entry => { const option = element('option'); option.value = entry.id; option.label = entry.name; return option; }));
        $('ownerDialog').showModal(); $('ownerInput').focus();
    }
    $('ownerForm').addEventListener('submit', async event => {
        event.preventDefault(); if (!writable() || busy.has('saveOwner')) return;
        if (!await confirmOperation('保存端口归属？', '端口 ' + ownerPort + ' 的现有连接将关闭。', '保存归属') || !writable()) return;
        setBusy('saveOwner', true); $('ownerError').textContent = '';
        try { const result = await request('/api/forwards/owner', {port: ownerPort, clientId: $('ownerInput').value.trim()}); $('ownerDialog').close(); await load(); notice(result.message); }
        catch (error) { handleError(error, 'ownerError'); } finally { setBusy('saveOwner', false); }
    });
    $('healthButton').addEventListener('click', async () => {
        const epoch = generation;
        try {
            const result = await request('/api/health/settings');
            if (epoch !== generation || !token) return;
            $('healthEnabled').checked = result.enabled; $('healthInterval').value = result.intervalSeconds;
            $('healthTimeout').value = result.timeoutMillis; $('healthThreshold').value = result.failureThreshold;
            $('healthError').textContent = ''; $('healthDialog').showModal();
        } catch (error) { handleError(error); }
    });
    $('healthForm').addEventListener('submit', async event => {
        event.preventDefault(); if (!writable() || busy.has('saveHealth')) return;
        setBusy('saveHealth', true); $('healthError').textContent = '';
        try {
            await request('/api/health/settings', {enabled: String($('healthEnabled').checked), intervalSeconds: $('healthInterval').value,
                timeoutMillis: $('healthTimeout').value, failureThreshold: $('healthThreshold').value});
            $('healthDialog').close(); await load(); notice('检查设置已保存并生效');
        } catch (error) { handleError(error, 'healthError'); } finally { setBusy('saveHealth', false); }
    });
    function renderConnection() {
        $('serverEndpoint').textContent = connection ? address(connection.host, connection.port) : '尚未读取';
        const info = $('serverConnectionState');
        info.classList.toggle('failed', Boolean(connection?.lastError));
        info.textContent = !connection ? '登录后查看连接设置' : connection.enabledMappings === 0 ? '尚无启用的映射'
            : connection.registeredMappings + ' / ' + connection.enabledMappings + ' 个映射已注册' + (connection.lastError ? ' · ' + connection.lastError : '');
    }
    $('connectionButton').addEventListener('click', async () => {
        if (!writable() || busy.has('connectionButton')) return;
        const epoch = generation;
        setBusy('connectionButton', true);
        try {
            const current = await request('/api/connection/settings');
            if (epoch !== generation || !token) return;
            connection = current; renderConnection();
            $('connectionHost').value = current.host; $('connectionPort').value = current.port;
            $('connectionMode').textContent = '当前通信模式：' + current.mode + '。请保持两端通信模式一致；加密模式的密钥沿用配置文件，也需与服务端一致。';
            $('connectionError').textContent = ''; $('connectionDialog').showModal(); $('connectionHost').focus();
        } catch (error) { if (epoch === generation) handleError(error); }
        finally { setBusy('connectionButton', false); }
    });
    $('connectionForm').addEventListener('submit', async event => {
        event.preventDefault(); if (!writable() || busy.has('saveConnection')) return;
        const epoch = generation, fields = {host: $('connectionHost').value.trim(), port: $('connectionPort').value};
        const changed = fields.host !== connection?.host || Number(fields.port) !== connection?.port;
        if (changed && forwards.some(item => item.enabled !== false)
            && !await confirmOperation('切换服务器？', '现有转发连接将断开，所有启用的映射会向新服务器重新注册。', '切换并连接')) return;
        if (!writable() || epoch !== generation) return;
        setBusy('saveConnection', true); $('connectionError').textContent = '';
        try {
            const result = await request('/api/connection/settings', fields);
            if (epoch !== generation || !token) return;
            generation++; loading = null; diagnosticGeneration++;
            connection = result.connection; forwards = result.forwards.slice().sort((a,b) => a.port-b.port);
            renderConnection(); render(); $('connectionDialog').close();
            notice(result.message);
            await load();
        } catch (error) { if (token) handleError(error, 'connectionError'); }
        finally { setBusy('saveConnection', false); }
    });
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && !$('drawer').hidden) closeDrawer();
        if (event.key === '/' && $('gate').hidden && $('drawer').hidden && !document.querySelector('dialog[open]') && !['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement?.tagName)) { event.preventDefault(); showView('mappings'); $('searchInput').focus(); }
    });
    setInterval(() => { if (token && document.visibilityState === 'visible') load().catch(() => {}); }, 5000);
    document.addEventListener('visibilitychange', () => { if (token && document.visibilityState === 'visible') load().catch(() => {}); });
    syncControls(); $('tokenInput').focus();
})();
