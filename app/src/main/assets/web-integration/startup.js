/* 页面启动观察：模块实际加载/应用错误和官方启动失败屏；不吞异常、不改插件开关。 */
(function () {
  'use strict';
  if (window.top !== window) return;
  var binding=window.__DSHA_PAGE_BINDING__||{};
  if(window.__dshaStartupObserved&&window.__dshaStartupObservationNonce===String(binding.nonce||''))return;
  window.__dshaStartupObserved = true;
  window.__dshaStartupObservationNonce=String(binding.nonce||'');
  var documentId=String(Date.now())+'-'+Math.random().toString(36).slice(2);
  window.__dshaStartupDocumentId=documentId;
  var ready = false, seenBoot = false, lastFailure = '', count = 0;
  // dsh-client 可在页面重渲染时再次调用同一个插件的 apply。成功结果
  // 对启动诊断没有新增信息；只记录首次成功，错误/下一次错误前的加载仍保留。
  var reportedActive = Object.create(null), reportedLoading = Object.create(null);
  function uiText(zh, en) { return window.__DSHA_LANGUAGE__ === 'en' ? en : zh; }
  // 本机时间戳：网页侧没有别的量法能区分「外壳已渲染」和「插件都应用完」，
  // 带上 at 才能把每条事件排成一条真实的客户端启动时间线。
  function stamp() {
    try { return Math.round(performance.now()); } catch (_) { return 0; }
  }
  function report(type, id, message, fatal) {
    if (count++ > 500) return;
    var text = JSON.stringify({type:type, id:String(id || '').slice(0,214), message:String(message || '').slice(0,6000), fatal:!!fatal && !ready,
      nonce:String(binding.nonce||''),documentId:documentId,sequence:count-1,page:location.origin+location.pathname,at:stamp()});
    console.info('[DSHA_PAGE] ' + text);
    window.dispatchEvent(new CustomEvent('dsha-startup', {detail:text}));
  }
  // 进入界面后插件才逐个 apply 的窗口里，给一个不挡操作的进度条；它只改善
  // 预期，不改变启动速度，所以任何异常都必须静默放弃而不是影响页面。
  var progress = {pending:0, done:0, total:0, el:null, text:null, show:0, idle:0, cap:0, closed:false};
  function progressTotal() {
    try {
      var boot = window.__DSH_BOOT__;
      return boot && boot.entries && boot.entries.length ? boot.entries.length : 0;
    } catch (_) { return 0; }
  }
  function progressClose() {
    if (progress.closed) return;
    progress.closed = true;
    if (progress.show) clearTimeout(progress.show);
    if (progress.idle) clearTimeout(progress.idle);
    if (progress.cap) clearTimeout(progress.cap);
    try { if (progress.el && progress.el.parentNode) progress.el.parentNode.removeChild(progress.el); } catch (_) {}
    progress.el = null;
  }
  function progressRender() {
    if (!progress.text) return;
    var shown = progress.total ? progress.done + '/' + progress.total : String(progress.done);
    var line = uiText('正在启动网页功能 ', 'Starting web features ') + shown;
    if (progress.text.textContent !== line) progress.text.textContent = line;
  }
  function progressShow() {
    if (progress.el || progress.closed || !document.body) return;
    var box = document.createElement('div');
    box.setAttribute('data-dsha-boot-progress', '1');
    box.style.cssText = 'position:fixed;left:0;right:0;top:0;z-index:2147483000;pointer-events:none;'
        + 'display:flex;justify-content:center;padding:6px 10px;font:12px/1.4 system-ui,sans-serif;'
        + 'background:rgba(20,20,24,.86);color:#f2f2f4;text-align:center;';
    var span = document.createElement('span');
    box.appendChild(span);
    document.body.appendChild(box);
    progress.el = box;
    progress.text = span;
    progressRender();
    // 硬上限：插件卡住时进度条必须自己消失，不能永久占着屏幕。
    progress.cap = setTimeout(progressClose, 30000);
  }
  function progressSettle() {
    if (progress.closed || progress.pending !== 0) return;
    progress.idle = setTimeout(function () { if (progress.pending === 0) progressClose(); }, 800);
  }
  function progressNote(type) {
    if (progress.closed) return;
    if (!progress.total) progress.total = progressTotal();
    if (type === 'loading') {
      progress.pending++;
      // 计时器回调不在 reportPlugin 的 try 里，必须自己兜住。
      if (!progress.el && !progress.show) progress.show = setTimeout(function () {
        try { progressShow(); } catch (_) { progressClose(); }
      }, 250);
      if (progress.idle) { clearTimeout(progress.idle); progress.idle = 0; }
      return;
    }
    if (progress.pending > 0) progress.pending--;
    if (type === 'active') progress.done++;
    if (progress.el) progressRender();
    progressSettle();
  }
  function reportPlugin(type, id, message, fatal) {
    var key = String(id || '');
    if (type === 'loading') {
      if (reportedActive[key] || reportedLoading[key]) return;
      reportedLoading[key] = true;
    } else if (type === 'active') {
      reportedLoading[key] = false;
      if (reportedActive[key]) return;
      reportedActive[key] = true;
    } else if (type === 'issue') {
      reportedLoading[key] = false;
      reportedActive[key] = false;
    }
    try { progressNote(type); } catch (_) { progressClose(); }
    report(type, key, message, fatal);
  }
  function detail(error) { return error && (error.stack || error.message) || String(error); }
  function wrapExports(value, id) {
    if (!value || typeof value.apply !== 'function') return value;
    var descriptor = Object.getOwnPropertyDescriptor(value, 'apply');
    if (!descriptor || !descriptor.writable) return value;
    var original = value.apply;
    value.apply = function () {
      reportPlugin('loading', id, uiText('正在初始化网页插件：', 'Initializing web plugin: ') + id);
      try {
        var result = original.apply(this, arguments);
        if (result && typeof result.then === 'function') return result.then(function (v) {
          reportPlugin('active', id, uiText('网页插件初始化返回：', 'Web plugin initialization returned: ') + id); return v;
        }, function (error) { reportPlugin('issue', id, detail(error)); throw error; });
        reportPlugin('active', id, uiText('网页插件初始化返回：', 'Web plugin initialization returned: ') + id); return result;
      } catch (error) { reportPlugin('issue', id, detail(error)); throw error; }
    };
    return value;
  }
  function wrap(loader) {
    if (!loader || loader.__dshaObserved || typeof loader.load !== 'function') return loader;
    try {
      var original = loader.load;
      loader.load = function (definition) {
        if (!definition || typeof definition.factory !== 'function') return original.apply(this, arguments);
        var args = Array.prototype.slice.call(arguments), factory = definition.factory, id = definition.id;
        args[0] = Object.assign({}, definition, {factory:function () {
          try { return wrapExports(factory.apply(this, arguments), id); }
          catch (error) { report('issue', id, detail(error)); throw error; }
        }});
        return original.apply(this, args);
      };
      Object.defineProperty(loader, '__dshaObserved', {value:true});
    } catch (_) { /* 观察不可用时保持原加载行为。 */ }
    return loader;
  }
  try {
    var descriptor = Object.getOwnPropertyDescriptor(window, '__ModuleLoader__');
    if (!descriptor) {
      var facade;
      Object.defineProperty(window, '__ModuleLoader__', {configurable:true, enumerable:true,
        get:function () { return facade; }, set:function (v) { facade = wrap(v); }});
    } else wrap(window.__ModuleLoader__);
  } catch (_) {}
  window.addEventListener('error', function (event) {
    if (event.error || event.message) report('issue', '', detail(event.error || event.message) + '\n' + (event.filename || ''));
  });
  window.addEventListener('unhandledrejection', function (event) { report('issue', '', detail(event.reason)); });
  function inspect() {
    wrap(window.__ModuleLoader__);
    var boot = document.querySelector('[data-dsh-boot]');
    if (boot) {
      seenBoot = true;
      var text = boot.textContent || '';
      if (/Failed to load plugins|did not activate/.test(text) && text !== lastFailure) {
        lastFailure = text; report('issue', '', text, true);
      }
    }
    var root = document.getElementById('root');
    if (document.querySelector('[data-composer-input]') || (seenBoot && !boot && root && root.children.length)) {
      // ready 的语义（"外壳已可用"）与进度条无关：composer 常常先于插件出现，
      // 这里绝不能顺手关掉进度条，否则插件随后启动时用户什么都看不到。
      ready = true; report('ready', '', uiText('网页已就绪', 'Web page ready')); observer.disconnect();
    }
  }
  // 启动期 DOM 抖动很大；把每个 mutation 批次里的扫描合并到一次宏任务里，
  // 判据不变（仍然看同一个 boot 屏 / composer），只是少做几遍全场扫描。
  var inspectQueued = false;
  function queueInspect() {
    if (inspectQueued) return;
    inspectQueued = true;
    setTimeout(function () { inspectQueued = false; inspect(); }, 0);
  }
  var observer = new MutationObserver(queueInspect);
  observer.observe(document, {childList:true, subtree:true, characterData:true});
  // Old WebView has no document-start hook: inspect a failure/ready screen
  // that already exists when the fallback observer is injected.
  inspect();
})();
