(() => {
  const TITLES = {
    overview: ['总览', '多实例运行态势'],
    instances: ['网关实例', '协议无关 · 类型仅为标签'],
    catalog: ['类型目录', 'gateway.catalog 可插拔适配器'],
    ops: ['运维', '非密钥配置与进程指标'],
  };

  const $ = (sel) => document.querySelector(sel);
  const toastEl = $('#toast');

  function toast(msg, type = 'ok') {
    toastEl.hidden = false;
    toastEl.className = 'toast ' + type;
    toastEl.textContent = msg;
    clearTimeout(toastEl._t);
    toastEl._t = setTimeout(() => { toastEl.hidden = true; }, 2800);
  }

  async function api(path, opts) {
    const res = await fetch('/console/api' + path, Object.assign({
      headers: { Accept: 'application/json' },
    }, opts || {}));
    const text = await res.text();
    let data;
    try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text }; }
    if (!res.ok) throw new Error((data && data.message) || res.statusText || '请求失败');
    return data;
  }

  function escapeHtml(s) {
    return String(s ?? '').replace(/[&<>"']/g, (c) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
  }

  function badge(text, cls) {
    return `<span class="badge ${cls || ''}">${escapeHtml(text)}</span>`;
  }

  function setStatusPill(health) {
    const pill = $('#status-pill');
    const running = Number(health?.runningCount || 0);
    const up = running > 0;
    pill.classList.toggle('up', up);
    pill.classList.toggle('down', !up);
    pill.querySelector('.label').textContent = up
      ? `运行中 ${running}/${health?.instanceCount ?? '—'} 实例`
      : `无运行实例 · 共 ${health?.instanceCount ?? 0}`;
  }

  function renderKpis(overview) {
    const health = overview.health || {};
    const metrics = overview.metrics || {};
    const byStatus = overview.byStatus || {};
    $('#kpi-instances').textContent = health.instanceCount ?? (overview.instances || []).length;
    $('#kpi-running').textContent = byStatus.RUNNING ?? health.runningCount ?? 0;
    $('#kpi-accepted').textContent = metrics.connectionsAccepted ?? '—';
    $('#kpi-denials').textContent = metrics.policyDenials ?? '—';
  }

  function renderInstanceCards(instances, mountId) {
    const el = document.getElementById(mountId);
    const list = instances || [];
    if (!list.length) {
      el.innerHTML = '<p class="note">暂无实例。可配置 gateway.instances，或依赖空列表时合成的 default。</p>';
      return;
    }
    el.innerHTML = list.map((inst) => {
      const running = inst.status === 'RUNNING';
      const canAct = !!inst.startable && !!inst.bound;
      const target = `${inst.targetUsername ? escapeHtml(inst.targetUsername) + '@' : ''}${escapeHtml(inst.targetHost)}:${inst.targetPort}/${escapeHtml(inst.targetDatabase || '')}`;
      return `
        <article class="instance-card ${inst.bound ? 'bound' : ''}" data-id="${escapeHtml(inst.id)}">
          <div class="instance-card-head">
            <div>
              <h3>${escapeHtml(inst.name)}</h3>
              <div class="id">${escapeHtml(inst.id)}</div>
            </div>
            <div class="instance-card-badges">
              ${badge(inst.dbType, 'type')}
              ${badge(inst.status, inst.status)}
            </div>
          </div>
          <div class="instance-meta">
            <div><span>监听</span>${escapeHtml(inst.listenHost)}:${inst.listenPort}</div>
            <div><span>目标</span>${target}</div>
            <div><span>会话</span>${inst.activeSessions ?? '—'} · 绑定 ${inst.bound ? '是' : '否'}</div>
            <div><span>密码</span>${inst.passwordConfigured ? '已配置（已脱敏）' : '未配置'}</div>
          </div>
          <div class="instance-msg">${escapeHtml(inst.message || '')}</div>
          <div class="instance-actions">
            <button type="button" class="btn primary btn-start" data-id="${escapeHtml(inst.id)}" ${(!canAct || running) ? 'disabled' : ''}>启动</button>
            <button type="button" class="btn danger btn-stop" data-id="${escapeHtml(inst.id)}" ${(!canAct || !running) ? 'disabled' : ''}>停止</button>
          </div>
        </article>`;
    }).join('');
  }

  function renderCatalog(databases) {
    const tbody = $('#catalog-table tbody');
    tbody.innerHTML = (databases || []).map((d) => `
      <tr>
        <td class="mono">${escapeHtml(d.id)}</td>
        <td>${escapeHtml(d.displayName)}</td>
        <td>${badge(d.maturity, d.maturity)}</td>
        <td>${d.enabled ? '是' : badge('否', 'off')}</td>
        <td class="mono">${d.defaultProxyPort} → ${d.defaultTargetPort}</td>
        <td>${d.registered ? '是' : '否'}</td>
        <td>${d.creatable ? '是' : '否'}${d.consoleCreateAllowed ? '' : ' / 管控禁用'}</td>
        <td>${escapeHtml(d.notes || '')}</td>
      </tr>`).join('');
  }

  function renderMetrics(metrics) {
    const grid = $('#metrics-grid');
    const labels = {
      connectionsAccepted: '已接受连接',
      connectionsRejectedLimit: '超限拒绝',
      connectionsRejectedPolicy: '策略拒绝(连接)',
      opaqueTunnelsEntered: '进入 Opaque 隧道',
      opaqueTunnelsDenied: 'Opaque 隧道拒绝',
      backendFailovers: '后端 Failover',
      policyDenials: '策略拒绝(操作)',
    };
    const entries = Object.entries(metrics || {});
    grid.innerHTML = entries.length
      ? entries.map(([k, v]) => `
        <div class="metric-tile">
          <div class="name">${escapeHtml(labels[k] || k)}</div>
          <div class="val">${escapeHtml(v)}</div>
        </div>`).join('')
      : '<p class="note">暂无指标</p>';
  }

  function renderConfig(cfg) {
    const safe = JSON.parse(JSON.stringify(cfg || {}));
    if (safe.target && typeof safe.target === 'object' && 'password' in safe.target) {
      safe.target.password = '********';
    }
    $('#config-json').textContent = JSON.stringify(safe, null, 2);
  }

  function renderAll(overview) {
    setStatusPill(overview.health);
    renderKpis(overview);
    renderInstanceCards(overview.instances, 'overview-cards');
    renderInstanceCards(overview.instances, 'instance-cards');
    renderCatalog(overview.databases);
    renderMetrics(overview.metrics);
    renderConfig(overview.config);
  }

  async function refresh() {
    try {
      renderAll(await api('/overview'));
    } catch (e) {
      toast('刷新失败：' + e.message, 'error');
    }
  }

  function showSection(id) {
    document.querySelectorAll('.section').forEach((s) => s.classList.remove('active'));
    document.querySelectorAll('.nav-item').forEach((b) => b.classList.toggle('active', b.dataset.section === id));
    const section = document.getElementById('section-' + id);
    if (section) section.classList.add('active');
    const t = TITLES[id] || [id, ''];
    $('#page-title').textContent = t[0];
    $('#page-subtitle').textContent = t[1];
  }

  $('#nav').addEventListener('click', (e) => {
    const btn = e.target.closest('.nav-item');
    if (btn) showSection(btn.dataset.section);
  });
  $('#btn-refresh').addEventListener('click', refresh);

  document.addEventListener('click', async (e) => {
    const start = e.target.closest('.btn-start');
    const stop = e.target.closest('.btn-stop');
    const btn = start || stop;
    if (!btn || btn.disabled) return;
    const id = btn.dataset.id;
    try {
      const body = await api(`/instances/${encodeURIComponent(id)}/${start ? 'start' : 'stop'}`, { method: 'POST' });
      toast(body.message || (start ? '已启动' : '已停止'), body.ok === false ? 'error' : 'ok');
      await refresh();
    } catch (err) {
      toast((start ? '启动' : '停止') + '失败：' + err.message, 'error');
    }
  });

  showSection('overview');
  refresh();
  setInterval(refresh, 8000);
})();
