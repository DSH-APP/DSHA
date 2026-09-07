(function () {
  'use strict';
  var page = document.querySelector('[data-install-page]');
  if (!page) return;
  var status = page.querySelector('[data-install-status]');
  var actions = page.querySelector('[data-install-actions]');
  var source = document.getElementById('plugin-source');
  var note = page.querySelector('[data-install-note]');
  var sharing = page.querySelector('[data-install-sharing]');
  var share = document.getElementById('install-share-url');
  var qr = page.querySelector('[data-install-qr]');
  var qrNote = page.querySelector('[data-install-qr-note]');
  var original = null;
  function invalidate(message) {
    actions.hidden = true; sharing.hidden = true;
    page.querySelector('[data-open-app]').removeAttribute('href');
    page.querySelector('[data-open-scheme]').removeAttribute('href');
    share.value = ''; qr.textContent = ''; note.textContent = '';
    if (message) status.textContent = message;
  }
  function parse(query) {
    var values = {};
    if (!query) return values;
    query.replace(/^\?/, '').split('&').forEach(function (part) {
      var pair = part.split('='), key = decodeURIComponent(pair.shift().replace(/\+/g, ' '));
      var value = decodeURIComponent(pair.join('=').replace(/\+/g, ' '));
      if (['url','sha256','name','version','builtin'].indexOf(key) < 0 || Object.prototype.hasOwnProperty.call(values,key) || /[\x00-\x1f\x7f]/.test(value)) throw new Error('安装链接包含无效或重复参数，请重新选择插件。');
      values[key] = value;
    });
    return values;
  }
  function encode(values) {
    return Object.keys(values).map(function (key) { return key + '=' + encodeURIComponent(values[key]); }).join('&');
  }
  function render(values) {
    invalidate();
    Object.keys(values).forEach(function (key) {
      if (/[\x00-\x1f\x7f]/.test(values[key])) throw new Error('安装信息包含无效字符。');
    });
    var name = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/;
    if (values.builtin) {
      if (values.url || !name.test(values.builtin) || values.builtin.length > 214) throw new Error('内置插件入口无效。');
    } else {
      if (!values.url || values.url.length > 6000) throw new Error('请输入插件链接或 npm 包名。');
      var parsed = document.createElement('a'); parsed.href = values.url;
      var https = /^https:\/\//i.test(values.url) && parsed.hostname && !parsed.username && !parsed.password;
      var npm = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*(?:@[a-zA-Z0-9.^~*+_-]+)?$/.test(values.url);
      if (!https && !npm) throw new Error('请使用 HTTPS 插件链接或 npm 包名。');
      if (values.sha256 && !/^[a-f0-9]{64}$/i.test(values.sha256)) throw new Error('插件摘要无效。');
      if (values.name && (!name.test(values.name) || values.name.length > 214)) throw new Error('插件名称无效。');
      if (values.version && values.version.length > 100) throw new Error('插件版本无效。');
      source.value = values.url;
    }
    var query = encode(values), scheme = 'dsha://install?' + query;
    var fallback = 'https://dsha.cc/install/?' + query;
    var android = /Android/i.test(navigator.userAgent);
    page.querySelector('[data-open-app]').href = android ? 'intent://install?' + query + '#Intent;scheme=dsha;package=com.dsh.client;S.browser_fallback_url=' + encodeURIComponent(fallback) + ';end' : scheme;
    page.querySelector('[data-open-scheme]').href = scheme;
    actions.hidden = false;
    status.textContent = values.builtin ? '打开插件管理，查看 ' + values.builtin + ' 的状态。' : '已准备好链接。请在 DSHA 中查看实际包信息并确认安装。';
    note.textContent = values.sha256 ? 'App 将核对目录中的 SHA-256 摘要。插件具体信息以下载包为准。' : '此链接由你或分享者提供。请在 App 中核对插件来源、版本及兼容范围。';
    share.value = fallback; sharing.hidden = false;
    try {
      // 控制密度以便手机扫码；超长来源仍可完整复制，不截断安装信息。
      if (fallback.length > 1200 || typeof qrcode !== 'function') throw new Error();
      var code = qrcode(0, 'M'); code.addData(fallback, 'Byte'); code.make();
      var size = code.getModuleCount(), ns = 'http://www.w3.org/2000/svg';
      var svg = document.createElementNS(ns, 'svg');
      svg.setAttribute('viewBox', '0 0 ' + (size + 8) + ' ' + (size + 8));
      svg.setAttribute('role', 'img'); svg.setAttribute('aria-label', '用手机扫描完整插件安装链接');
      var background = document.createElementNS(ns, 'rect');
      background.setAttribute('width', String(size + 8)); background.setAttribute('height', String(size + 8)); background.setAttribute('fill', '#fff'); svg.appendChild(background);
      var dots = document.createElementNS(ns, 'path'), path = [];
      for (var row = 0; row < size; row++) for (var col = 0; col < size; col++) if (code.isDark(row, col)) path.push('M' + (col + 4) + ',' + (row + 4) + 'h1v1h-1z');
      dots.setAttribute('d', path.join('')); dots.setAttribute('fill', '#000'); svg.appendChild(dots); qr.appendChild(svg);
      qrNote.textContent = '在电脑上浏览？用手机扫码，再在 DSHA 中确认。二维码与下方链接包含相同的完整安装信息。';
    } catch (error) {
      qrNote.textContent = '此链接暂时无法生成清晰二维码，请复制下方完整链接到手机打开。';
    }
  }
  source.addEventListener('input', function () {
    invalidate('来源已修改，请重新生成安装入口。');
    history.replaceState(null, '', '/install/');
  });
  page.querySelector('[data-install-form]').addEventListener('submit', function (event) {
    event.preventDefault();
    try { var url = source.value.trim(), values = original && original.url === url ? original : {url:url}; render(values); history.replaceState(null, '', '/install/?' + encode(values)); }
    catch (error) { status.textContent = error.message; }
  });
  if (location.search) try { var initial = parse(location.search); render(initial); original = initial; } catch (error) { invalidate('无法打开安装链接：' + error.message); }
})();
