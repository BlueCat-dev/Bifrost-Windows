/**
 * Bifrost Desktop UI Controller (Pure Vanilla JS, 100% Offline)
 */

const state = {
  status: 'STOPPED',
  localPort: 5050,
  activeConnections: 0,
  activeProxy: null,
  proxies: [],
  lastPingMs: 0,
  editingProxyId: null,
  appVersion: '2.2.0',
  latestUpdateInfo: null
};

// API Client Helper
async function api(path, method = 'GET', body = null) {
  const opts = { method, headers: {} };
  if (body) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  try {
    const res = await fetch(path, opts);
    try {
      return await res.json();
    } catch (_) {
      return { success: res.ok, status: res.status };
    }
  } catch (err) {
    throw new Error(err.message || 'عدم دسترسی به پروسس محلی');
  }
}

// Toast notification
function showToast(msg) {
  const container = document.getElementById('toastContainer');
  if (!container) return;
  const toast = document.createElement('div');
  toast.className = 'toast';
  toast.textContent = msg;
  container.appendChild(toast);
  setTimeout(() => toast.remove(), 2600);
}

// Persian Numbers formatter
function toPersianDigits(num) {
  const farsiDigits = ['۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'];
  return num.toString().replace(/\d/g, x => farsiDigits[x]);
}

// Realtime WebSocket for UI Sync
function initWebSocket() {
  const loc = window.location;
  const wsProto = loc.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsUrl = `${wsProto}//${loc.host}/ws/live`;
  try {
    const ws = new WebSocket(wsUrl);
    ws.onmessage = (e) => {
      try {
        const snap = JSON.parse(e.data);
        updateStateFromSnapshot(snap);
      } catch (err) {}
    };
    ws.onclose = () => setTimeout(initWebSocket, 3000);
    ws.onerror = () => ws.close();
  } catch (e) {
    console.error('WebSocket Error', e);
  }
}

function updateStateFromSnapshot(snap) {
  state.status = snap.status;
  state.localPort = snap.local_port;
  state.activeConnections = snap.active_connections;
  if (snap.last_ping_ms !== undefined && snap.last_ping_ms !== 0) {
    state.lastPingMs = snap.last_ping_ms;
  }
  if (snap.active_proxy) {
    state.activeProxy = snap.active_proxy;
  } else {
    state.activeProxy = null;
  }
  if (snap.proxies) {
    state.proxies = snap.proxies;
  }
  render();
}

async function fetchFullStatus() {
  try {
    const res = await api('/api/status');
    if (res.success && res.data) {
      if (res.version) {
        state.appVersion = res.version;
      }
      updateStateFromSnapshot(res.data);
      if (res.config && res.config.proxies) {
        state.proxies = res.config.proxies;
      }
      render();
    }
  } catch (e) {
    console.error('Fetch Status Error', e);
  }
}

async function toggleBridge() {
  try {
    const res = await api('/api/bridge/toggle', 'POST');
    if (res.data) {
      updateStateFromSnapshot(res.data);
    }
    if (res.success) {
      showToast(state.status !== 'STOPPED' ? 'پل ارتباطی متصل شد' : 'پل ارتباطی قطع شد');
    } else {
      let errMsg = res.error || 'عملیات ناموفق بود';
      if (errMsg.includes('bind') || errMsg.includes('Only one usage of each socket address')) {
        errMsg = `پورت ${state.localPort} توسط برنامه دیگری (مانند v2rayN) اشغال است. از آیکون ⚙️ پورت را تغییر دهید.`;
      }
      showToast('خطا: ' + errMsg);
    }
  } catch (e) {
    showToast('خطا در ارتباط: ' + e.message);
  }
}

async function pingActiveWorker() {
  const btn = document.getElementById('btnPing');
  if (btn) btn.classList.add('spinning');
  try {
    const res = await api('/api/ping', 'POST');
    if (res.success) {
      state.lastPingMs = res.latency_ms;
      showToast(`⚡ پینگ ورکر: ${res.latency_ms} ms`);
    } else {
      state.lastPingMs = -1;
      showToast(`خطای پینگ: ${res.error || 'ورکر در دسترس نیست'}`);
    }
  } catch (e) {
    state.lastPingMs = -1;
    showToast('خطا در تست پینگ: ' + e.message);
  } finally {
    if (btn) btn.classList.remove('spinning');
    render();
  }
}

// Version Comparison Helper
function isVersionGreater(remote, current) {
  const rParts = (remote || '').replace(/^v/, '').split('.').map(n => parseInt(n) || 0);
  const cParts = (current || '').replace(/^v/, '').split('.').map(n => parseInt(n) || 0);
  for (let i = 0; i < Math.max(rParts.length, cParts.length); i++) {
    const r = rParts[i] || 0;
    const c = cParts[i] || 0;
    if (r > c) return true;
    if (r < c) return false;
  }
  return false;
}

// Fallback Browser Direct Fetch for Official GitHub Release
async function tryDirectBrowserFetch() {
  const repo = 'Qorvhex/Bifrost';
  const url = `https://api.github.com/repos/${repo}/releases/latest`;
  try {
    const resp = await fetch(url, {
      headers: { 'Accept': 'application/vnd.github.v3+json' },
      cache: 'no-store'
    });
    if (resp.ok) {
      const rel = await resp.json();
      const ver = (rel.tag_name || rel.name || '').replace(/^v/, '');
      let setupUrl = rel.html_url || `https://github.com/${repo}/releases/latest`;
      if (rel.assets && Array.isArray(rel.assets)) {
        const setupAsset = rel.assets.find(a => a.name && a.name.toLowerCase().includes('setup'));
        if (setupAsset) setupUrl = setupAsset.browser_download_url;
      }
      return {
        version: ver,
        changelog: rel.body || '',
        setup_url: setupUrl,
        release_url: rel.html_url
      };
    }
  } catch (_) {}
  return null;
}

// Auto Update Functions
async function checkUpdateManual() {
  const btn = document.getElementById('btnUpdate');
  if (btn) btn.classList.add('spinning');

  try {
    let updateData = null;
    let curVer = state.appVersion || '2.2.0';

    // 1. Try Go backend check first
    try {
      const res = await api('/api/update/check');
      if (res.current_version) curVer = res.current_version;
      if (res.success && res.has_update && res.update) {
        updateData = res.update;
      } else if (res.success && !res.has_update) {
        showToast(`شما از آخرین نسخه استفاده می‌کنید (نسخه ${curVer})`);
        return;
      }
    } catch (e) {
      console.warn('Backend check error, trying browser direct fallback...', e);
    }

    // 2. If backend failed, fallback to native browser fetch (uses Windows system proxy)
    if (!updateData) {
      const directInfo = await tryDirectBrowserFetch();
      if (directInfo && directInfo.version) {
        if (isVersionGreater(directInfo.version, curVer)) {
          updateData = directInfo;
        } else {
          showToast(`شما از آخرین نسخه استفاده می‌کنید (نسخه ${curVer})`);
          return;
        }
      }
    }

    if (updateData) {
      state.latestUpdateInfo = updateData;
      document.getElementById('updateNewVer').textContent = 'v' + updateData.version;
      document.getElementById('updateCurVer').textContent = 'v' + curVer;
      document.getElementById('updateChangelog').textContent = updateData.changelog || 'به‌روزرسانی عمومی و بهبود پایداری.';
      const badge = document.getElementById('updateBadge');
      if (badge) badge.classList.add('active');
      openModal('modalUpdate');
    } else {
      showToast('خطا در بررسی به‌روزرسانی: لطفاً اتصال اینترنت خود را بررسی کنید.');
    }
  } catch (err) {
    showToast('خطا در بررسی به‌روزرسانی: ' + err.message);
  } finally {
    if (btn) btn.classList.remove('spinning');
  }
}

async function applyUpdateNow() {
  const btn = document.getElementById('btnApplyUpdate');
  if (btn) {
    btn.disabled = true;
    btn.textContent = 'در حال دریافت، اعتبارسنجی هش امنیتی و اعمال نسخه جدید...';
  }

  try {
    const res = await api('/api/update/apply', 'POST', {});
    if (res.success) {
      showToast('نسخه رسمی جدید راستی‌آزمایی و دریافت شد! برنامه در حال راه‌اندازی مجدد است...');
      setTimeout(() => {
        closeModal('modalUpdate');
      }, 1200);
    } else {
      showToast('خطا در به‌روزرسانی: ' + (res.error || 'ناشناخته'));
      if (btn) {
        btn.disabled = false;
        btn.textContent = 'به‌روزرسانی خودکار و راه‌اندازی مجدد';
      }
    }
  } catch (err) {
    showToast('خطا در به‌روزرسانی: ' + err.message);
    if (btn) {
      btn.disabled = false;
      btn.textContent = 'به‌روزرسانی خودکار و راه‌اندازی مجدد';
    }
  }
}

function openDirectDownloadLink() {
  const url = (state.latestUpdateInfo && (state.latestUpdateInfo.setup_url || state.latestUpdateInfo.release_url)) || 'https://github.com/Qorvhex/Bifrost/releases/latest';
  window.open(url, '_blank');
}

async function silentCheckUpdate() {
  try {
    let hasUpdate = false;
    let updateInfo = null;

    const res = await api('/api/update/check');
    if (res.success && res.has_update && res.update) {
      hasUpdate = true;
      updateInfo = res.update;
    } else if (!res.success) {
      const direct = await tryDirectBrowserFetch();
      if (direct && isVersionGreater(direct.version, state.appVersion)) {
        hasUpdate = true;
        updateInfo = direct;
      }
    }

    if (hasUpdate && updateInfo) {
      state.latestUpdateInfo = updateInfo;
      const badge = document.getElementById('updateBadge');
      if (badge) badge.classList.add('active');
    }
  } catch (_) {}
}

// Modal Helpers
function openModal(id) {
  const el = document.getElementById(id);
  if (el) el.classList.add('active');
}

function closeModal(id) {
  const el = document.getElementById(id);
  if (el) el.classList.remove('active');
}

// Select Config (Activate on click)
async function selectConfig(proxyId) {
  if (!proxyId) return;
  if (state.activeProxy && state.activeProxy.id === proxyId) return; // already active

  try {
    const res = await api('/api/proxies/active', 'POST', { id: proxyId });
    if (res.success) {
      state.lastPingMs = 0;
      if (res.data) {
        updateStateFromSnapshot(res.data);
      } else {
        await fetchFullStatus();
      }
      const selected = state.proxies.find(p => p.id === proxyId);
      const name = selected ? (selected.name || selected.worker_host) : '';
      showToast(`کانفیگ «${name}» فعال شد.`);
    } else {
      showToast('خطا در انتخاب کانفیگ: ' + res.error);
    }
  } catch (err) {
    showToast('خطا در ارتباط: ' + err.message);
  }
}

// Open Add Worker Modal (Clean slate)
function openAddWorkerModal() {
  state.editingProxyId = null;
  document.getElementById('modalTitle').textContent = 'افزودن ورکر جدید کلادفلر';
  document.getElementById('editHost').value = '';
  document.getElementById('editName').value = '';
  document.getElementById('editCleanIp').value = '';
  document.getElementById('editSecret').value = '';
  document.getElementById('editPort').value = '443';
  openModal('modalEdit');
  setTimeout(() => document.getElementById('editHost').focus(), 80);
}

// Edit specific config
function editConfig(proxy) {
  if (!proxy) return;
  state.editingProxyId = proxy.id;
  document.getElementById('modalTitle').textContent = 'ویرایش ورکر کلادفلر';
  document.getElementById('editHost').value = proxy.worker_host || '';
  document.getElementById('editName').value = proxy.name || '';
  document.getElementById('editCleanIp').value = proxy.clean_ip || '';
  document.getElementById('editSecret').value = proxy.secret || '';
  document.getElementById('editPort').value = proxy.port || 443;
  openModal('modalEdit');
  setTimeout(() => document.getElementById('editHost').focus(), 80);
}

// Delete specific config - Custom in-app glass modal
let pendingDeleteProxyId = null;

function deleteConfig(proxy) {
  if (!proxy) return;
  pendingDeleteProxyId = proxy.id;
  const name = proxy.name || proxy.worker_host || 'این کانفیگ';
  const nameEl = document.getElementById('deleteTargetName');
  if (nameEl) {
    nameEl.textContent = `«${name}»`;
  }
  openModal('modalConfirmDelete');
}

async function executeConfirmedDelete() {
  if (!pendingDeleteProxyId) return;
  const idToDelete = pendingDeleteProxyId;
  closeModal('modalConfirmDelete');
  pendingDeleteProxyId = null;

  try {
    const res = await api('/api/proxies', 'DELETE', { id: idToDelete });
    if (res.success) {
      showToast('کانفیگ با موفقیت حذف شد.');
      state.lastPingMs = 0;
      await fetchFullStatus();
    } else {
      showToast('خطا در حذف: ' + (res.error || 'ناموفق'));
    }
  } catch (err) {
    showToast('خطا در ارتباط با سرور: ' + err.message);
  }
}

// Handle pasting twp:// link directly into host field
function handleHostInputPaste(input) {
  const val = input.value.trim();
  if (val.startsWith('twp://')) {
    try {
      const url = new URL(val);
      input.value = url.hostname;
      if (url.port) document.getElementById('editPort').value = url.port;

      const params = new URLSearchParams(url.search);
      const secret = params.get('secret');
      const cleanIp = params.get('clean_ip') || params.get('cleanip') || params.get('ip');
      if (secret) document.getElementById('editSecret').value = secret;
      if (cleanIp) document.getElementById('editCleanIp').value = cleanIp;

      if (url.hash) {
        const decodedName = decodeURIComponent(url.hash.substring(1));
        if (decodedName) document.getElementById('editName').value = decodedName;
      } else {
        document.getElementById('editName').value = url.hostname;
      }
      showToast('لینک twp با موفقیت شناسایی و استخراج شد.');
    } catch (e) {
      console.warn('TWP parse error', e);
    }
  }
}

// Save Proxy (Add or Update)
async function saveProxyForm(e) {
  e.preventDefault();
  let rawHost = document.getElementById('editHost').value.trim();
  rawHost = rawHost.replace(/^https?:\/\//i, '').replace(/^wss?:\/\//i, '').replace(/\/.*$/, '');

  const payload = {
    id: state.editingProxyId || '',
    name: document.getElementById('editName').value.trim() || rawHost,
    worker_host: rawHost,
    clean_ip: document.getElementById('editCleanIp').value.trim(),
    secret: document.getElementById('editSecret').value.trim(),
    port: parseInt(document.getElementById('editPort').value) || 443
  };

  try {
    const res = await api('/api/proxies', 'POST', payload);
    if (res.success) {
      closeModal('modalEdit');
      state.lastPingMs = 0;
      await fetchFullStatus();
      showToast('ورکر با موفقیت ذخیره شد.');
    } else {
      showToast('خطا: ' + (res.error || 'ذخیره انجام نشد'));
    }
  } catch (err) {
    showToast('خطا در ارتباط با سرور: ' + err.message);
  }
}

function resetDefaultPort() {
  const portInput = document.getElementById('settingsPort');
  if (portInput) {
    portInput.value = 5050;
    portInput.focus();
  }
}

// Settings Modal
function openSettingsModal() {
  const portInput = document.getElementById('settingsPort');
  if (portInput) portInput.value = state.localPort || 5050;
  openModal('modalSettings');
  setTimeout(() => {
    if (portInput) portInput.focus();
  }, 80);
}

async function saveSettingsForm(e) {
  e.preventDefault();
  const port = parseInt(document.getElementById('settingsPort').value);
  if (isNaN(port) || port < 1024 || port > 65535) {
    showToast('شماره پورت نامعتبر است (بین ۱۰۲۴ تا ۶۵۵۳۵).');
    return;
  }

  try {
    const res = await api('/api/settings', 'POST', { local_port: port, port: port });
    if (res.success) {
      closeModal('modalSettings');
      state.localPort = res.local_port || res.port || port;
      render();
      showToast(`پورت محلی با موفقیت به ${state.localPort} تغییر یافت.`);
    } else {
      showToast('خطا: ' + (res.error || 'تغییر پورت ناموفق بود'));
    }
  } catch (err) {
    showToast('خطا در ارتباط با سرور: ' + err.message);
  }
}

// Main Render Function
function render() {
  // 1. Status Pill & Text
  const statusPill = document.getElementById('statusPill');
  const statusText = document.getElementById('statusText');
  let pillClass = 'status-pill stopped';
  let label = 'متوقف';

  if (state.status === 'LISTENING') {
    pillClass = 'status-pill listening';
    label = 'آماده اتصال';
  } else if (state.status === 'STREAMING') {
    pillClass = 'status-pill streaming';
    label = `متصل (${toPersianDigits(state.activeConnections)})`;
  }

  statusPill.className = pillClass;
  statusText.textContent = label;

  // 2. Local Endpoint Tag
  const endpointTag = document.getElementById('endpointTag');
  if (endpointTag) {
    endpointTag.textContent = `127.0.0.1:${state.localPort || 5050}`;
  }

  // 3. Ping Badge Slot
  const pingSlot = document.getElementById('pingBadgeSlot');
  if (pingSlot) {
    if (state.lastPingMs > 0) {
      pingSlot.innerHTML = `<span class="badge ping-badge">⚡ ${state.lastPingMs} ms</span>`;
    } else if (state.lastPingMs === -1) {
      pingSlot.innerHTML = `<span class="badge" style="color:var(--red);border-color:rgba(255,51,102,0.3);">⚠️ خطا</span>`;
    } else {
      pingSlot.innerHTML = '';
    }
  }

  // 4. Power Button: Glowing Green when connected, Glowing Red when stopped
  const btnPower = document.getElementById('btnPower');
  if (btnPower) {
    const isRunning = state.status !== 'STOPPED';
    btnPower.className = 'btn-power ' + (isRunning ? 'active' : 'stopped');
    btnPower.title = isRunning ? 'قطع اتصال پل' : 'برقراری اتصال پل';
  }

  // 5. Configs List: Stacked vertically, showing all configs simultaneously!
  const container = document.getElementById('configsContainer');
  if (container) {
    container.innerHTML = '';
    const proxies = state.proxies || [];

    if (proxies.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'empty-configs-msg';
      empty.textContent = 'هنوز کانفیگی ثبت نشده است. روی «افزودن لینک ورکر» کلیک کنید.';
      container.appendChild(empty);
    } else {
      const activeId = state.activeProxy ? state.activeProxy.id : (proxies[0] ? proxies[0].id : '');

      proxies.forEach(p => {
        const item = document.createElement('div');
        const isActive = (p.id === activeId);
        item.className = 'config-item ' + (isActive ? 'active' : '');
        item.onclick = () => selectConfig(p.id);

        // Main part: Radio circle + Config Name ONLY (no worker URL!)
        const main = document.createElement('div');
        main.className = 'config-item-main';

        const radio = document.createElement('div');
        radio.className = 'config-radio';
        const radioInner = document.createElement('div');
        radioInner.className = 'config-radio-inner';
        radio.appendChild(radioInner);

        const nameSpan = document.createElement('div');
        nameSpan.className = 'config-name';
        nameSpan.textContent = p.name || p.worker_host;

        main.appendChild(radio);
        main.appendChild(nameSpan);

        // Actions: Edit (✏️) and Delete (🗑️)
        const actions = document.createElement('div');
        actions.className = 'config-actions';

        const btnEdit = document.createElement('button');
        btnEdit.className = 'btn-action-icon';
        btnEdit.title = 'ویرایش کانفیگ';
        btnEdit.innerHTML = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>`;
        btnEdit.onclick = (e) => {
          e.stopPropagation(); // prevent triggering row select
          editConfig(p);
        };

        const btnDelete = document.createElement('button');
        btnDelete.className = 'btn-action-icon danger';
        btnDelete.title = 'حذف کانفیگ';
        btnDelete.innerHTML = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>`;
        btnDelete.onclick = (e) => {
          e.stopPropagation(); // prevent triggering row select
          deleteConfig(p);
        };

        actions.appendChild(btnEdit);
        actions.appendChild(btnDelete);

        item.appendChild(main);
        item.appendChild(actions);

        container.appendChild(item);
      });
    }
  }
}

// Global Process Termination on Native Window Close
window.exitProcess = function() {
  try {
    navigator.sendBeacon('/api/shutdown', JSON.stringify({}));
  } catch (e) {
    try {
      fetch('/api/shutdown', { method: 'POST', keepalive: true });
    } catch (_) {}
  }
};

window.addEventListener('beforeunload', () => {
  window.exitProcess();
});

// App Initialization
document.addEventListener('DOMContentLoaded', async () => {
  render();
  await fetchFullStatus();
  initWebSocket();
  setTimeout(silentCheckUpdate, 2500);
});
