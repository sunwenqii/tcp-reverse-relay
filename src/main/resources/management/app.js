(() => {
    'use strict';
    const $ = id => document.getElementById(id);
    const client = document.body.dataset.mode === 'client';
    const statusText = client ? {connected: '已注册', waiting: '待注册'} : {connected: '在线', waiting: '离线'};
    let setupMode = document.body.dataset.setup === 'true';
    let token = '';
    let forwards = [];
    let page = 1;
    let toastTimer;

    function configureGate() {
        $('gateTitle').textContent = setupMode ? '首次设置管理密码' : '连接到管理控制台';
        $('gateDescription').textContent = setupMode
            ? '为当前设备设置 8 至 128 位管理密码。首次设置只能在本机完成，保存后即可进入控制台。'
            : '请输入当前设备的管理密码。密码仅保存在这个页面，刷新后需重新输入。';
        $('tokenLabel').textContent = setupMode ? '设置密码' : '管理密码';
        $('tokenInput').placeholder = setupMode ? '至少 8 个字符' : '输入管理密码';
        $('tokenInput').minLength = setupMode ? 8 : 1;
        $('tokenInput').maxLength = 128;
        $('tokenInput').autocomplete = setupMode ? 'new-password' : 'off';
        $('confirmField').hidden = !setupMode;
        $('confirmInput').required = setupMode;
        $('gateFootText').textContent = setupMode ? '首次设置只允许在本机进行' : '外网访问由登录后的开关控制';
        $('connectButton').textContent = setupMode ? '保存密码并进入' : '进入控制台';
    }

    configureGate();
    if (client) {
        $('pageSubtitle').textContent = '管理公网端口与本机服务之间的转发关系。';
        $('connectedLabel').textContent = '已注册映射';
        $('connectedCaption').textContent = '已与服务端建立控制连接';
        $('waitingLabel').textContent = '待注册映射';
        $('waitingCaption').textContent = '连接恢复后自动重试';
        $('statusHeader').textContent = '注册状态';
        $('statusHeader').title = '是否已向服务端注册此端口；不代表本地目标服务的健康状态';
        $('statusFilter').setAttribute('aria-label', '按注册状态筛选');
        $('statusFilter').querySelector('option[value="connected"]').textContent = statusText.connected;
        $('statusFilter').querySelector('option[value="waiting"]').textContent = statusText.waiting;
        $('activityLabel').textContent = '本地目标';
        $('activityLabel').title = '已配置本地目标端口的映射数量';
        $('activityCaption').textContent = '已设置目标的映射';
        $('targetHeader').textContent = '本地目标';
        $('roleHint').textContent = '请先在服务端开放公网端口，再在这里设置相同的端口和本机目标。断线后会自动重试。';
        $('drawerNote').textContent = '保存后立即生效；服务端尚未开放该端口时，客户端会持续重试。';
        $('targetPort').required = true;
    }

    function notice(message, error = false) {
        const box = $('toast');
        box.textContent = message;
        box.classList.toggle('error', error);
        box.hidden = false;
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => {
            box.hidden = true;
        }, 4500);
    }

    async function request(path, fields) {
        const options = {headers: {Authorization: 'Bearer ' + token}, cache: 'no-store'};
        if (fields) {
            options.method = 'POST';
            options.headers['Content-Type'] = 'application/x-www-form-urlencoded';
            options.body = new URLSearchParams(fields);
        }
        const response = await fetch(path, options);
        const body = await response.text();
        if (!response.ok) {
            const error = new Error(body || '请求失败，请稍后重试');
            error.status = response.status;
            throw error;
        }
        return body;
    }

    function closeDrawer() {
        $('drawer').hidden = true;
        $('drawerBackdrop').hidden = true;
    }

    function lock() {
        token = '';
        forwards = [];
        $('tokenInput').value = '';
        $('gate').hidden = false;
        $('lockButton').hidden = true;
        for (const id of ['addButton', 'refreshButton', 'searchInput', 'statusFilter', 'publicSwitch']) $(id).disabled = true;
        for (const id of ['totalCount', 'connectedCount', 'waitingCount', 'activityCount']) $(id).textContent = '—';
        $('updatedAt').textContent = '尚未读取';
        closeDrawer();
        render();
        $('tokenInput').focus();
    }

    async function load(silent = false) {
        if (!token) return;
        try {
            const state = JSON.parse(await request('/api/forwards'));
            if (!Array.isArray(state.forwards)) throw new Error('服务器返回的映射数据无效');
            forwards = state.forwards.sort((a, b) => a.port - b.port);
            const connected = forwards.filter(item => item.connected).length;
            $('totalCount').textContent = forwards.length.toLocaleString('zh-CN');
            $('connectedCount').textContent = connected.toLocaleString('zh-CN');
            $('waitingCount').textContent = (forwards.length - connected).toLocaleString('zh-CN');
            $('activityCount').textContent = (client ? forwards.filter(item => item.target != null).length : forwards.reduce((sum, item) => sum + (item.connections || 0), 0)).toLocaleString('zh-CN');
            $('listCount').textContent = forwards.length.toLocaleString('zh-CN');
            $('updatedAt').textContent = '更新于 ' + new Intl.DateTimeFormat('zh-CN', {
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit'
            }).format(new Date());
            render();
            if (!silent) notice('映射状态已更新');
        } catch (error) {
            if (error.status === 401 && $('gate').hidden) {
                lock();
                $('gateError').textContent = '管理令牌已失效，请重新输入。';
            } else if (!silent) notice(error.message, true);
            throw error;
        }
    }

    function cell(row, value, className = '') {
        const td = document.createElement('td');
        td.className = className;
        if (value instanceof Node) td.append(value);
        else td.textContent = value;
        row.append(td);
        return td;
    }

    function span(value, className) {
        const element = document.createElement('span');
        element.className = className;
        element.textContent = value;
        return element;
    }

    function render() {
        const query = $('searchInput').value.trim();
        const filter = $('statusFilter').value;
        const shown = forwards.filter(item =>
            (!query || String(item.port).includes(query) || (item.target != null && String(item.target).includes(query))) &&
            (filter === 'all' || (filter === 'connected') === Boolean(item.connected)));
        const pages = Math.max(1, Math.ceil(shown.length / 20));
        page = Math.min(page, pages);
        const tbody = $('mappingRows');
        tbody.replaceChildren();
        if (!shown.length) {
            const row = document.createElement('tr');
            const td = cell(row, '');
            td.colSpan = client ? 4 : 5;
            const box = document.createElement('div');
            box.className = 'empty';
            const title = document.createElement('strong');
            title.textContent = token ? (forwards.length ? '没有匹配的映射' : '还没有端口映射') : '请先登录管理页';
            const detail = document.createElement('p');
            detail.textContent = token ? (forwards.length ? '试试其他端口或状态。' : '点击“新增映射”开始配置。') : '登录后可查看和管理端口映射。';
            box.append(span('◫', 'empty-icon'), title, detail);
            td.append(box);
            tbody.append(row);
        }
        for (const item of shown.slice((page - 1) * 20, page * 20)) {
            const row = document.createElement('tr');
            cell(row, span(String(item.port), 'port-main')).append(span('TCP · 公网', 'port-sub'));
            cell(row, item.target != null ? '127.0.0.1 : ' + item.target : '由客户端指定', item.target == null ? 'target-muted' : 'target-main');
            const status = span(item.connected ? statusText.connected : statusText.waiting,
                'status ' + (item.connected ? 'connected' : 'waiting'));
            status.title = client
                ? (item.connected ? '已向服务端注册此端口' : '尚未完成注册，客户端会自动重试')
                : (item.connected ? '该端口的客户端控制连接正常' : '该端口暂无客户端控制连接');
            cell(row, status);
            if (!client) {
                const connections = cell(row, String(item.connections || 0), 'connection-count');
                connections.title = '当前外部 TCP 连接数，包含等待接通的连接';
            }
            const actions = document.createElement('div');
            actions.className = 'row-actions';
            const edit = document.createElement('button');
            edit.type = 'button';
            edit.textContent = '编辑';
            edit.setAttribute('aria-label', '编辑端口 ' + item.port);
            edit.addEventListener('click', () => openDrawer(item));
            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'delete';
            remove.textContent = '删除';
            remove.setAttribute('aria-label', '删除端口 ' + item.port);
            remove.addEventListener('click', () => deleteForward(item));
            actions.append(edit, remove);
            cell(row, actions);
            tbody.append(row);
        }
        $('resultSummary').textContent = shown.length ? `显示 ${(page - 1) * 20 + 1}–${Math.min(page * 20, shown.length)} 条，共 ${shown.length} 条` : '共 0 条映射';
        $('pageIndicator').textContent = shown.length ? `${page} / ${pages}` : '—';
        $('prevButton').disabled = page <= 1;
        $('nextButton').disabled = page >= pages;
    }

    function openDrawer(item) {
        $('mappingForm').reset();
        $('oldPort').value = item ? item.port : '';
        $('remotePort').value = item ? item.port : '';
        $('targetPort').value = item?.target ?? '';
        $('drawerTitle').textContent = item ? '编辑映射' : '新增映射';
        $('drawerIntro').textContent = item ? '调整端口配置，保存后立即应用。' : (client ? '指定公网端口和本机服务的目标端口。' : '设置需要开放的公网端口。');
        $('drawerBackdrop').hidden = false;
        $('drawer').hidden = false;
        $('remotePort').focus();
    }

    function confirmDelete(port) {
        return new Promise(resolve => {
            const dialog = $('deleteDialog');
            if (dialog.open) {
                resolve(false);
                return;
            }
            $('deletePort').textContent = String(port);
            const listeners = new AbortController();
            const finish = accepted => {
                listeners.abort();
                dialog.close();
                resolve(accepted);
            };
            $('deleteCancel').addEventListener('click', () => finish(false), {signal: listeners.signal});
            $('deleteSubmit').addEventListener('click', () => finish(true), {signal: listeners.signal});
            dialog.addEventListener('cancel', event => {
                event.preventDefault();
                finish(false);
            }, {signal: listeners.signal});
            dialog.showModal();
        });
    }

    async function deleteForward(item) {
        if (!await confirmDelete(item.port)) return;
        try {
            await request('/api/forwards/delete', {port: item.port});
            await load(true);
            notice('端口 ' + item.port + ' 已删除并保存');
        } catch (error) {
            notice(error.message, true);
        }
    }

    async function loadSettings() {
        const settings = JSON.parse(await request('/api/settings'));
        $('publicSwitch').checked = Boolean(settings.publicAccess);
        $('publicSwitch').disabled = false;
        $('accessDescription').textContent = settings.publicAccess ? '已允许其他设备访问' : '当前仅本机可访问';
    }

    $('publicSwitch').addEventListener('change', async event => {
        const enabled = event.target.checked;
        event.target.disabled = true;
        try {
            await request('/api/settings', {public: String(enabled)});
            $('accessDescription').textContent = enabled ? '已允许其他设备访问' : '当前仅本机可访问';
            notice(enabled ? '外网访问已开启；还需放行系统或云防火墙端口' : '外网访问已关闭');
            if (!enabled && !['127.0.0.1', 'localhost', '::1'].includes(location.hostname)) {
                lock();
                $('gateError').textContent = '外网访问已关闭，请在运行程序的电脑上重新打开管理页。';
            }
        } catch (error) {
            event.target.checked = !enabled;
            notice(error.message, true);
        } finally {
            event.target.disabled = !token;
        }
    });
    $('tokenForm').addEventListener('submit', async event => {
        event.preventDefault();
        token = $('tokenInput').value;
        $('gateError').textContent = '';
        if (setupMode && token !== $('confirmInput').value) {
            $('gateError').textContent = '两次输入的密码不一致。';
            return;
        }
        $('connectButton').disabled = true;
        try {
            if (setupMode) {
                await request('/api/setup', {password: token});
                setupMode = false;
                configureGate();
            }
            await load(true);
            await loadSettings();
            $('gate').hidden = true;
            $('lockButton').hidden = false;
            for (const id of ['addButton', 'refreshButton', 'searchInput', 'statusFilter']) $(id).disabled = false;
            $('tokenInput').value = '';
            $('confirmInput').value = '';
            $('addButton').focus();
        } catch (error) {
            $('gateError').textContent = error.status === 401 ? '密码不正确，请检查配置文件中的 ADMIN_TOKEN。' : error.message;
            token = '';
        } finally {
            $('connectButton').disabled = false;
        }
    });
    $('showToken').addEventListener('click', () => {
        const input = $('tokenInput');
        input.type = input.type === 'password' ? 'text' : 'password';
        $('showToken').textContent = input.type === 'password' ? '显示' : '隐藏';
        input.focus();
    });
    $('lockButton').addEventListener('click', () => {
        $('gateError').textContent = '';
        lock();
    });
    $('addButton').addEventListener('click', () => openDrawer(null));
    $('refreshButton').addEventListener('click', () => load().catch(() => {
    }));
    $('searchInput').addEventListener('input', () => {
        page = 1;
        render();
    });
    $('statusFilter').addEventListener('change', () => {
        page = 1;
        render();
    });
    $('prevButton').addEventListener('click', () => {
        page--;
        render();
    });
    $('nextButton').addEventListener('click', () => {
        page++;
        render();
    });
    for (const id of ['closeDrawer', 'cancelButton', 'drawerBackdrop']) $(id).addEventListener('click', closeDrawer);
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && !$('drawer').hidden) closeDrawer();
        if (event.key === '/' && $('gate').hidden && $('drawer').hidden && !['INPUT', 'TEXTAREA'].includes(document.activeElement?.tagName)) {
            event.preventDefault();
            $('searchInput').focus();
        }
    });
    $('mappingForm').addEventListener('submit', async event => {
        event.preventDefault();
        const oldPort = $('oldPort').value;
        const port = $('remotePort').value;
        const fields = {port};
        if (oldPort) fields.oldPort = oldPort;
        if (client) fields.target = $('targetPort').value;
        $('saveButton').disabled = true;
        try {
            await request('/api/forwards', fields);
            closeDrawer();
            await load(true);
            notice(`端口 ${port} 已保存并应用`);
        } catch (error) {
            notice(error.message, true);
        } finally {
            $('saveButton').disabled = false;
        }
    });
    setInterval(() => {
        if (token && document.visibilityState === 'visible' && $('drawer').hidden) load(true).catch(() => {
        });
    }, 15000);
    $('tokenInput').focus();
})();
