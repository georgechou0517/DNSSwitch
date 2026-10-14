/* DNSSwitch WebUI 逻辑
 * 契约见 WebAppInterface.kt：
 *  - getConfig()/saveConfig(json)/startVpn()/requestVpnConsent()/stopVpn()
 *  - getStatus()/getLogs()/clearLogs()/testChannel(type)/getUiMode()/openBatterySettings()/getAppVersion()
 *  - 原生推送：window.onNativeStatus(jsonStr) 每 1.2s；window.onNativeEvent(name)
 */
(function () {
  'use strict';

  /* ---------------- 工具 ---------------- */
  function $(id) { return document.getElementById(id); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  var toastTimer = null;
  function toast(msg) {
    var el = $('toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove('show'); }, 2200);
  }

  function fmtTime(ms) {
    var d = new Date(ms);
    function p(n) { return (n < 10 ? '0' : '') + n; }
    return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }

  function fmtUptime(sec) {
    if (!sec || sec <= 0) return '未运行';
    var h = Math.floor(sec / 3600);
    var m = Math.floor((sec % 3600) / 60);
    var s = sec % 60;
    if (h > 0) return '已运行 ' + h + ' 小时 ' + m + ' 分';
    if (m > 0) return '已运行 ' + m + ' 分 ' + s + ' 秒';
    return '已运行 ' + s + ' 秒';
  }

  /* ---------------- 原生桥封装（含浏览器预览降级） ---------------- */
  var hasNative = !!(window.NativeBridge && window.NativeBridge.getConfig);
  var preview = !hasNative;

  var MOCK = {
    config: {
      traditional: { enabled: true, server: '223.5.5.5', serverV6: '', port: 53, tcp: false },
      doh: { enabled: true, url: 'https://dns.alidns.com/dns-query' },
      dot: { enabled: false, server: 'dns.alidns.com', serverV6: '', port: 853 },
      strategy: 'smart_failover'
    },
    status: {
      running: false, strategy: 'smart_failover', strategyLabel: '智能分流（故障转移）',
      totalQueries: 0, vpnStartedAt: 0, uptimeSec: 0,
      channels: [
        { type: 'traditional', label: '传统', enabled: true, queries: 0, failures: 0, avgMs: 0 },
        { type: 'doh', label: 'DoH', enabled: true, queries: 0, failures: 0, avgMs: 0 },
        { type: 'dot', label: 'DoT', enabled: false, queries: 0, failures: 0, avgMs: 0 }
      ],
      activeServers: ['223.5.5.5:53 (UDP)', 'https://dns.alidns.com/dns-query']
    },
    logs: [],
    presets: []
  };

  function call(name) {
    if (preview) return null;
    try {
      var fn = window.NativeBridge[name];
      if (typeof fn !== 'function') return null;
      var args = Array.prototype.slice.call(arguments, 1);
      return fn.apply(window.NativeBridge, args);
    } catch (e) {
      return null;
    }
  }

  function callJson(name) {
    var raw = call.apply(null, arguments);
    if (raw === null || raw === undefined || raw === '') return null;
    try { return JSON.parse(raw); } catch (e) { return null; }
  }

  /* ---------------- 启动页 & 预览提示 ---------------- */
  function initSplash() {
    setTimeout(function () {
      var s = $('splash');
      s.classList.add('fade');
      setTimeout(function () { s.classList.add('hidden'); }, 350);
      $('app').classList.remove('hidden');
    }, 2000);
  }

  function initPreview() {
    if (!preview) return;
    $('preview-banner').classList.remove('hidden');
    /* 浏览器预览时同样跟随系统深浅偏好，便于查看深色样式 */
    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    var apply = function () { document.documentElement.classList.toggle('dark', mq.matches); };
    apply();
    if (mq.addEventListener) mq.addEventListener('change', apply);
  }

  /* ---------------- 底部导航 ---------------- */
  function initNav() {
    var pages = { config: $('page-config'), log: $('page-log'), about: $('page-about') };
    $$('.bottom-nav button').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var page = btn.getAttribute('data-page');
        $$('.bottom-nav button').forEach(function (b) { b.classList.remove('active'); });
        btn.classList.add('active');
        Object.keys(pages).forEach(function (k) {
          pages[k].classList.toggle('hidden', k !== page);
        });
        if (page === 'log') refreshLogs();
      });
    });
  }

  /* ---------------- 配置表单 ---------------- */
  var saveTimer = null;
  var cfg = null;

  function formToConfig() {
    return {
      traditional: {
        enabled: $('trad-enabled').checked,
        server: $('trad-server').value.trim(),
        serverV6: $('trad-server-v6').value.trim(),
        port: parseInt($('trad-port').value, 10) || 53,
        tcp: currentProto() === 'tcp'
      },
      doh: {
        enabled: $('doh-enabled').checked,
        url: $('doh-url').value.trim()
      },
      dot: {
        enabled: $('dot-enabled').checked,
        server: $('dot-server').value.trim(),
        serverV6: $('dot-server-v6').value.trim(),
        port: parseInt($('dot-port').value, 10) || 853
      },
      strategy: currentStrategy()
    };
  }

  function currentProto() {
    var btn = $('#trad-proto button.active');
    return btn ? btn.getAttribute('data-proto') : 'udp';
  }

  function currentStrategy() {
    var sel = $('#strategy-list .radio-card.selected');
    return sel ? sel.getAttribute('data-strategy') : 'smart_failover';
  }

  function loadConfigToForm(c) {
    cfg = c;
    $('trad-enabled').checked = !!c.traditional.enabled;
    $('trad-server').value = c.traditional.server || '';
    $('trad-server-v6').value = c.traditional.serverV6 || '';
    $('trad-port').value = c.traditional.port || 53;
    setProto(c.traditional.tcp ? 'tcp' : 'udp');
    $('doh-enabled').checked = !!c.doh.enabled;
    $('doh-url').value = c.doh.url || '';
    $('dot-enabled').checked = !!c.dot.enabled;
    $('dot-server').value = c.dot.server || '';
    $('dot-server-v6').value = c.dot.serverV6 || '';
    $('dot-port').value = c.dot.port || 853;
    setStrategy(c.strategy || 'smart_failover');
    syncBodies();
    syncStrategyAvailability();
  }

  function setProto(p) {
    $$('#trad-proto button').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-proto') === p);
    });
  }

  function setStrategy(id) {
    $$('#strategy-list .radio-card').forEach(function (card) {
      card.classList.toggle('selected', card.getAttribute('data-strategy') === id);
    });
  }

  /** 开关关闭时收起对应表单区 */
  function syncBodies() {
    $('trad-body').classList.toggle('hidden', !$('trad-enabled').checked);
    $('doh-body').classList.toggle('hidden', !$('doh-enabled').checked);
    $('dot-body').classList.toggle('hidden', !$('dot-enabled').checked);
  }

  /** 通道未启用时，对应「单通道策略」置灰不可选 */
  function syncStrategyAvailability() {
    var tradOn = $('trad-enabled').checked;
    var dohOn = $('doh-enabled').checked;
    var dotOn = $('dot-enabled').checked;
    var map = { all_traditional: tradOn, all_doh: dohOn, all_dot: dotOn };
    $$('#strategy-list .radio-card').forEach(function (card) {
      var id = card.getAttribute('data-strategy');
      if (map[id] === undefined) return; /* smart / parallel 不受影响 */
      card.classList.toggle('disabled', !map[id]);
    });
    var cur = currentStrategy();
    if (map[cur] === false) setStrategy('smart_failover');
  }

  /** 同步开关/策略联动 + 输入防抖自动保存（400ms） */
  function scheduleSave() {
    syncBodies();
    syncStrategyAvailability();
    clearTimeout(saveTimer);
    saveTimer = setTimeout(commitConfig, 400);
  }

  function commitConfig() {
    var json = JSON.stringify(formToConfig());
    if (preview) { toast('预览模式：配置不会保存'); return; }
    var r = call('saveConfig', json);
    if (r !== 'ok') {
      toast(r ? String(r).replace(/^error:/, '保存失败：') : '保存失败');
      refreshConfigFromNative();
    }
  }

  function refreshConfigFromNative() {
    var c = callJson('getConfig');
    if (c) loadConfigToForm(c);
  }

  function initConfigForm() {
    ['trad-enabled', 'doh-enabled', 'dot-enabled'].forEach(function (id) {
      $(id).addEventListener('change', function () {
        syncBodies();
        syncStrategyAvailability();
        clearTimeout(saveTimer);
        commitConfig();
      });
    });
    ['trad-server', 'trad-server-v6', 'trad-port', 'doh-url', 'dot-server', 'dot-server-v6',
      'dot-port'].forEach(function (id) {
      $(id).addEventListener('input', scheduleSave);
      $(id).addEventListener('change', scheduleSave);
    });
    $$('#trad-proto button').forEach(function (b) {
      b.addEventListener('click', function () {
        setProto(b.getAttribute('data-proto'));
        commitConfig();
      });
    });
    $$('#strategy-list .radio-card').forEach(function (card) {
      card.addEventListener('click', function () {
        if (card.classList.contains('disabled')) {
          toast('请先启用对应通道');
          return;
        }
        setStrategy(card.getAttribute('data-strategy'));
        commitConfig();
      });
    });
    /* 内置快捷 chips：一行配置表代替三段重复绑定 */
    var BUILTIN_CHIPS = {
      'trad-presets': { attr: 'data-preset', target: 'trad-server' },
      'doh-presets': { attr: 'data-doh', target: 'doh-url' },
      'dot-presets': { attr: 'data-dot', target: 'dot-server' }
    };
    Object.keys(BUILTIN_CHIPS).forEach(function (box) {
      var m = BUILTIN_CHIPS[box];
      $$('#' + box + ' .chip').forEach(function (chip) {
        chip.addEventListener('click', function () {
          var v = chip.getAttribute(m.attr);
          $(m.target).value = v;
          commitConfig();
          toast('已填入 ' + v);
        });
      });
    });
    /* 自定义预设：存为预设 / 打开管理 */
    $$('[data-save-preset]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        saveAsPreset(btn.getAttribute('data-save-preset'));
      });
    });
    $$('[data-manage-preset]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        openPresetModal(btn.getAttribute('data-manage-preset'));
      });
    });
    $('btn-battery').addEventListener('click', function () {
      if (preview) { toast('预览模式不可用'); return; }
      call('openBatterySettings');
    });
  }

  /* ---------------- 自定义预设 ---------------- */
  var presets = [];
  var presetFilter = 'all';
  var pendingPreset = null;

  var TYPE_LABEL = { dns: '传统 DNS', doh: 'DoH', dot: 'DoT' };
  var CHANNEL_TYPE = { traditional: 'dns', doh: 'doh', dot: 'dot' };
  var CUSTOM_BOX = { dns: 'trad-custom', doh: 'doh-custom', dot: 'dot-custom' };

  function loadPresets() {
    var arr = preview ? MOCK.presets : callJson('getPresets');
    presets = arr || [];
    renderCustomChips();
    renderPresetList();
  }

  /** 各通道卡片下方的自定义预设快捷 chips */
  function renderCustomChips() {
    Object.keys(CUSTOM_BOX).forEach(function (t) {
      var box = $(CUSTOM_BOX[t]);
      if (!box) return;
      var list = presets.filter(function (p) { return p.type === t; });
      box.innerHTML = '';
      if (!list.length) { box.classList.add('hidden'); return; }
      box.classList.remove('hidden');
      list.forEach(function (p) {
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'chip custom';
        b.textContent = p.name;
        b.title = presetSummary(p);
        b.addEventListener('click', function () { applyPreset(p); });
        box.appendChild(b);
      });
    });
  }

  function presetSummary(p) {
    if (!p) return '';
    if (p.type === 'dns') return [p.v4, p.v6].filter(Boolean).join('  /  ');
    if (p.type === 'dot') return [p.host, p.v6].filter(Boolean).join('  /  ');
    return p.url || '';
  }

  /** 把预设填入对应通道表单并立即保存 */
  function applyPreset(p) {
    /* 应用预设 = 用预设覆盖该通道的地址字段（预设未提供的字段清空，避免残留旧值） */
    if (p.type === 'dns') {
      $('trad-enabled').checked = true;
      $('trad-server').value = p.v4 || '';
      $('trad-server-v6').value = p.v6 || '';
    } else if (p.type === 'doh') {
      $('doh-enabled').checked = true;
      $('doh-url').value = p.url || '';
    } else {
      $('dot-enabled').checked = true;
      $('dot-server').value = p.host || '';
      $('dot-server-v6').value = p.v6 || '';
    }
    syncBodies();
    syncStrategyAvailability();
    commitConfig();
    toast('已应用预设「' + p.name + '」');
  }

  function shortHost(url) {
    var m = /^[a-z]+:\/\/([^/:]+)/i.exec(url || '');
    return m ? m[1] : (url || '');
  }

  /** 「＋存为预设」：从当前表单取值 → 命名弹窗 → 保存 */
  function saveAsPreset(channel) {
    var t = CHANNEL_TYPE[channel] || 'dns';
    var p = { type: t, v4: '', v6: '', host: '', url: '' };
    if (t === 'dns') {
      p.v4 = $('trad-server').value.trim();
      p.v6 = $('trad-server-v6').value.trim();
      if (!p.v4 && !p.v6) { toast('请先填写 DNS 服务器地址'); return; }
      p.name = p.v4 || p.v6;
    } else if (t === 'doh') {
      p.url = $('doh-url').value.trim();
      if (!p.url) { toast('请先填写 DoH URL'); return; }
      p.name = shortHost(p.url);
    } else {
      p.host = $('dot-server').value.trim();
      p.v6 = $('dot-server-v6').value.trim();
      if (!p.host) { toast('请先填写 DoT 服务器'); return; }
      p.name = p.host;
    }
    pendingPreset = p;
    $('preset-name-input').value = p.name;
    $('modal-name').classList.remove('hidden');
  }

  function doAddPreset(p) {
    if (preview) {
      MOCK.presets = MOCK.presets.filter(function (x) {
        return !(x.type === p.type && x.name === p.name);
      });
      MOCK.presets.push(p);
      loadPresets();
      toast('已保存（预览模式）');
      return;
    }
    var r = callJson('addPreset', JSON.stringify(p));
    if (!r || r.ok === false) { toast((r && r.message) || '保存失败'); return; }
    toast(r.message || ('已存为预设「' + p.name + '」'));
    loadPresets();
  }

  function openPresetModal(filter) {
    presetFilter = filter || 'all';
    $$('#preset-filter button').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-filter') === presetFilter);
    });
    $('preset-text').value = '';
    loadPresets();
    $('modal-presets').classList.remove('hidden');
  }

  function closePresetModal() { $('modal-presets').classList.add('hidden'); }

  function renderPresetList() {
    var box = $('preset-list');
    if (!box) return;
    var list = presetFilter === 'all'
      ? presets
      : presets.filter(function (p) { return p.type === presetFilter; });
    $('preset-empty').classList.toggle('hidden', list.length > 0);
    box.innerHTML = '';
    list.forEach(function (p) {
      var row = document.createElement('div');
      row.className = 'preset-row';
      var main = document.createElement('div');
      main.className = 'pr-main';
      main.innerHTML = '<div class="pr-name">' + escapeHtml(p.name) +
        ' <span class="pr-type">' + (TYPE_LABEL[p.type] || p.type) + '</span></div>' +
        '<div class="pr-sub">' + escapeHtml(presetSummary(p)) + '</div>';
      row.appendChild(main);
      var use = document.createElement('button');
      use.type = 'button';
      use.className = 'pr-use';
      use.textContent = '应用';
      use.addEventListener('click', function () { applyPreset(p); closePresetModal(); });
      row.appendChild(use);
      var del = document.createElement('button');
      del.type = 'button';
      del.className = 'pr-del';
      del.textContent = '删除';
      del.addEventListener('click', function () {
        if (preview) {
          MOCK.presets = MOCK.presets.filter(function (x) { return x.id !== p.id; });
          loadPresets();
          toast('已删除（预览模式）');
          return;
        }
        if (call('deletePreset', p.id) === 'ok') {
          loadPresets();
          toast('已删除「' + p.name + '」');
        } else {
          toast('删除失败');
        }
      });
      row.appendChild(del);
      box.appendChild(row);
    });
  }

  /** 导入文本（txt 文件内容或粘贴内容） */
  function importPresetText(text) {
    if (!text || !String(text).trim()) { toast('内容为空'); return; }
    if (preview) { toast('预览模式不支持导入'); return; }
    var r = callJson('importPresetText', String(text));
    if (!r) { toast('导入失败'); return; }
    var msg = r.message || '导入完成';
    if (r.invalid > 0 && r.samples && r.samples.length) msg += '，例如：' + r.samples[0];
    toast(msg);
    loadPresets();
  }

  /** 原生读取完 txt 后回传（MainActivity.onActivityResult → evaluateJavascript） */
  window.onPresetFileLoaded = function (text) { importPresetText(text); };

  function initPresets() {
    /* 预设管理弹窗：分类切换 */
    $$('#preset-filter button').forEach(function (b) {
      b.addEventListener('click', function () {
        presetFilter = b.getAttribute('data-filter');
        $$('#preset-filter button').forEach(function (x) { x.classList.toggle('active', x === b); });
        renderPresetList();
      });
    });
    $('btn-presets-close').addEventListener('click', closePresetModal);
    $('modal-presets').addEventListener('click', function (e) {
      if (e.target === $('modal-presets')) closePresetModal();
    });

    /* 命名弹窗 */
    $('btn-name-cancel').addEventListener('click', function () {
      $('modal-name').classList.add('hidden');
      pendingPreset = null;
    });
    $('btn-name-ok').addEventListener('click', function () {
      var p = pendingPreset;
      if (!p) return;
      var n = $('preset-name-input').value.trim();
      if (n) p.name = n;
      $('modal-name').classList.add('hidden');
      pendingPreset = null;
      doAddPreset(p);
    });

    /* txt 文件导入：WebView 下由原生读取后回调；浏览器预览用 FileReader */
    $('btn-import-file').addEventListener('click', function () {
      if (preview) { toast('预览模式不支持选择文件，请用下方粘贴导入'); return; }
      $('preset-file').click();
    });
    $('preset-file').addEventListener('change', function () {
      var input = $('preset-file');
      var f = input.files && input.files[0];
      if (!f) return;
      if (preview) {
        var fr = new FileReader();
        fr.onload = function () {
          importPresetText(String(fr.result));
          input.value = '';
        };
        fr.readAsText(f, 'utf-8');
        return;
      }
      toast('已选择文件，读取中…');
      input.value = '';
    });
    $('btn-import-paste').addEventListener('click', function () {
      importPresetText($('preset-text').value);
    });
  }

  /* ---------------- 电源 / VPN 启停 ---------------- */
  function initPower() {
    $('btn-power').addEventListener('click', function () {
      if (preview) { toast('预览模式：请在 Android 应用内启动'); return; }
      if ($('btn-power').classList.contains('running')) {
        call('stopVpn');
        toast('正在停止 DNS 服务…');
        return;
      }
      var r = call('startVpn');
      if (r === 'need_consent') {
        $('modal-guide').classList.remove('hidden');
      } else if (r === 'already_running') {
        toast('服务已在运行');
      } else if (r === 'starting') {
        toast('正在启动…');
      } else {
        toast('启动失败，请重试');
      }
    });
    $('btn-guide-ok').addEventListener('click', function () {
      $('modal-guide').classList.add('hidden');
      call('requestVpnConsent');
    });
    $('btn-guide-cancel').addEventListener('click', function () {
      $('modal-guide').classList.add('hidden');
    });
  }

  /* ---------------- 状态渲染 ---------------- */
  var lastErrorShown = '';

  function renderStatus(s) {
    var running = !!s.running;
    var err = (!running && s.lastError) ? String(s.lastError) : '';

    var pill = $('status-pill');
    pill.classList.toggle('running', running);
    pill.classList.toggle('error', !!err);
    $('status-text').textContent = running ? '运行中' : (err ? '启动失败' : '已停止');

    var btn = $('btn-power');
    btn.classList.toggle('running', running);
    btn.textContent = running ? '停止 DNS 服务' : '启动 DNS 服务';

    if (running) {
      $('run-status').innerHTML = '服务运行中 · <b>' + escapeHtml(s.strategyLabel || '') + '</b>';
      $('run-meta').textContent = (s.activeServers && s.activeServers.length)
        ? ('生效通道：' + s.activeServers.join('  |  '))
        : '';
    } else if (err) {
      $('run-status').innerHTML = '<b style="color:var(--danger)">启动失败</b>';
      $('run-meta').textContent = err + '（可查看「日志」页）';
      if (err !== lastErrorShown) toast('启动失败：' + err);
    } else {
      $('run-status').textContent = '服务已停止';
      $('run-meta').textContent = '';
    }
    lastErrorShown = err;

    $('stat-total').textContent = String(s.totalQueries || 0);
    $('stat-uptime').textContent = running ? fmtUptime(s.uptimeSec) : '未运行';

    var byType = {};
    (s.channels || []).forEach(function (c) { byType[c.type] = c; });
    ['traditional', 'doh', 'dot'].forEach(function (t) {
      var c = byType[t] || { queries: 0, failures: 0, avgMs: 0, enabled: false };
      var card = $('stat-' + (t === 'traditional' ? 'traditional' : t));
      card.classList.toggle('off', !c.enabled);
      $('stat-' + t + '-queries').textContent = String(c.queries || 0);
      $('stat-' + t + '-sub').textContent =
        '失败 ' + (c.failures || 0) + ' · 均 ' + (c.avgMs || 0) + 'ms';
    });
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  window.onNativeStatus = function (jsonStr) {
    var s = null;
    try { s = JSON.parse(jsonStr); } catch (e) { return; }
    if (s) renderStatus(s);
  };

  window.onNativeEvent = function (name) {
    if (name === 'ui_mode_dark') document.documentElement.classList.add('dark');
    else if (name === 'ui_mode_light') document.documentElement.classList.remove('dark');
    else if (name === 'vpn_started') toast('DNS 服务已启动');
    else if (name === 'vpn_consent_denied') toast('未获得 VPN 授权，服务未启动');
    else if (name === 'vpn_consent_required') $('modal-guide').classList.remove('hidden');
    else if (name === 'vpn_stopped') toast('DNS 服务已停止');
  };

  /* ---------------- 日志 ---------------- */
  function refreshLogs() {
    var arr = preview ? MOCK.logs : callJson('getLogs');
    if (!arr) return;
    $('log-count').textContent = '最近 ' + arr.length + ' 条查询';
    $('log-empty').classList.toggle('hidden', arr.length > 0);
    var html = '';
    /* 最新在前；「系统」条目为诊断信息，整行完整显示不截断 */
    for (var i = arr.length - 1; i >= 0; i--) {
      var e = arr[i];
      var isSys = e.c === '系统';
      html += '<div class="log-row' + (isSys ? ' sys' : '') + '">' +
        '<span class="time">' + fmtTime(e.t) + '</span>' +
        '<span class="domain">' + escapeHtml(e.d) + '</span>' +
        '<span class="ch">' + escapeHtml(e.c) + '</span>' +
        '<span class="ms">' + (e.ms || 0) + 'ms</span>' +
        '<span class="ok-dot ' + (e.ok ? 'ok' : 'fail') + '"></span>' +
        '</div>';
    }
    $('log-list').innerHTML = html;
  }

  function initLogs() {
    $('btn-clear-log').addEventListener('click', function () {
      if (preview) { MOCK.logs = []; refreshLogs(); toast('已清空（预览）'); return; }
      call('clearLogs');
      refreshLogs();
      toast('日志已清空');
    });
    setInterval(function () {
      if (!$('page-log').classList.contains('hidden')) refreshLogs();
    }, 1500);
  }

  /* ---------------- 通道测试 ---------------- */
  function initTestButtons() {
    $$('.test-btn').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var type = btn.getAttribute('data-test');
        if (preview) { toast('预览模式不可用'); return; }
        btn.disabled = true;
        var old = btn.textContent;
        btn.textContent = '…';
        /* 同步执行可能阻塞 UI 线程片刻，给一帧时间重绘 */
        setTimeout(function () {
          var r = null;
          try { r = JSON.parse(call('testChannel', type)); } catch (e) { r = null; }
          btn.disabled = false;
          btn.textContent = old;
          if (!r) { toast('测试失败：无法调用'); return; }
          if (r.ok && r.ip) toast(type.toUpperCase() + ' 测试通过：' + r.ip + '（' + r.ms + 'ms）');
          else if (r.ok) toast(type.toUpperCase() + ' 可达，但' + (r.error || '无有效应答') + '（' + r.ms + 'ms）');
          else toast(type.toUpperCase() + ' 测试失败：' + (r.error || '未知错误') + '（' + r.ms + 'ms）');
        }, 50);
      });
    });
  }

  /* ---------------- 关于页 ---------------- */
  function initAbout() {
    $('about-version').textContent = preview ? 'v1.0.0 (预览)' : 'v' + call('getAppVersion');
    $('btn-privacy').addEventListener('click', function () {
      location.href = 'privacy.html';
    });
  }

  /* ---------------- 检查更新 ---------------- */
  var updateInfo = null;

  function initUpdate() {
    setUpdateSource(preview ? 'auto' : (call('getUpdateSource') || 'auto'));
    $$('#update-source button').forEach(function (b) {
      b.addEventListener('click', function () {
        var s = b.getAttribute('data-source');
        setUpdateSource(s);
        if (preview) { toast('预览模式：下载源不会保存'); return; }
        call('setUpdateSource', s);
        toast('下载源：' + sourceLabel(s));
      });
    });
    $('btn-check-update').addEventListener('click', doCheckUpdate);
    $('btn-upd-later').addEventListener('click', closeUpdateModal);
    $('modal-update').addEventListener('click', function (e) {
      if (e.target === $('modal-update')) closeUpdateModal();
    });
    $('btn-upd-download').addEventListener('click', doDownloadUpdate);
  }

  function setUpdateSource(s) {
    $$('#update-source button').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-source') === s);
    });
  }

  function sourceLabel(s) {
    if (s === 'gitee') return 'Gitee';
    if (s === 'github') return 'GitHub';
    return '自动（按地区）';
  }

  function doCheckUpdate() {
    if (preview) { toast('预览模式不支持检查更新'); return; }
    var btn = $('btn-check-update');
    btn.disabled = true;
    btn.textContent = '检查中…';
    call('checkUpdate'); /* 结果经 window.onUpdateResult 异步回传 */
  }

  /** 原生异步检查完成后回调（MainActivity.deliverUpdateResult → evaluateJavascript） */
  window.onUpdateResult = function (jsonStr) {
    var r = null;
    try { r = JSON.parse(jsonStr); } catch (e) { r = null; }
    var btn = $('btn-check-update');
    btn.disabled = false;
    btn.textContent = '检查';
    if (!r) { toast('检查结果解析失败'); return; }
    if (!r.ok) { toast(r.error || '检查更新失败'); return; }
    updateInfo = r;
    if (!r.hasUpdate) { toast('已是最新版本 v' + r.current); return; }
    $('upd-title').textContent = '发现新版本 v' + r.latest;
    $('upd-ver').textContent = '当前 v' + r.current + ' · 下载源：' + sourceLabel(r.source);
    $('upd-notes').textContent = r.notes || '（本次发布未提供更新说明）';
    $('modal-update').classList.remove('hidden');
  };

  function closeUpdateModal() { $('modal-update').classList.add('hidden'); }

  function doDownloadUpdate() {
    if (!updateInfo) { closeUpdateModal(); return; }
    var url = updateInfo.apkUrl;
    closeUpdateModal();
    if (!url) { openExternal(updateInfo.pageUrl); return; }
    if (preview) { toast('预览模式不支持下载'); return; }
    /* Android 10+ 走系统下载器（完成后自动调起安装）；老设备回退浏览器打开 */
    if (call('downloadUpdate', url, updateInfo.apkName || '') === 'downloading') {
      toast('已开始下载，完成后会提示安装');
    } else {
      openExternal(url);
    }
  }

  /** 外链交给系统浏览器（WebViewClient 拦截非 file:// 请求） */
  function openExternal(url) { if (url) location.href = url; }

  /* ---------------- 预览模式模拟 ---------------- */
  function startPreviewSimulation() {
    if (!preview) return;
    setInterval(function () {
      MOCK.status.totalQueries += Math.floor(Math.random() * 3);
      renderStatus(MOCK.status);
    }, 1500);
  }

  /* ---------------- 启动 ---------------- */
  function boot() {
    initSplash();
    initPreview();
    initNav();
    initConfigForm();
    initPresets();
    initPower();
    initLogs();
    initTestButtons();
    initAbout();
    initUpdate();

    if (preview) {
      loadConfigToForm(MOCK.config);
      renderStatus(MOCK.status);
      startPreviewSimulation();
    } else {
      var c = callJson('getConfig');
      if (c) loadConfigToForm(c);
      var mode = call('getUiMode');
      document.documentElement.classList.toggle('dark', mode === 'dark');
      var s = callJson('getStatus');
      if (s) renderStatus(s);
    }
    loadPresets();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
