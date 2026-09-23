/* 页面启动观察：模块实际加载/应用错误和官方启动失败屏；不吞异常、不改插件开关。 */
(function () {
  'use strict';
  if (window.top !== window || window.__dshaStartupObserved) return;
  window.__dshaStartupObserved = true;
  var ready = false, seenBoot = false, lastFailure = '', count = 0;
  // dsh-client 可在页面重渲染时再次调用同一个插件的 apply。成功结果
  // 对启动诊断没有新增信息；只记录首次成功，错误/下一次错误前的加载仍保留。
  var reportedActive = Object.create(null), reportedLoading = Object.create(null);
  function uiText(zh, en) { return window.__DSHA_LANGUAGE__ === 'en' ? en : zh; }
  function report(type, id, message, fatal) {
    if (count++ > 500) return;
    var text = JSON.stringify({type:type, id:String(id || '').slice(0,214), message:String(message || '').slice(0,6000), fatal:!!fatal && !ready});
    console.info('[DSHA_PAGE] ' + text);
    window.dispatchEvent(new CustomEvent('dsha-startup', {detail:text}));
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
  var observer = new MutationObserver(function () {
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
      ready = true; report('ready', '', uiText('网页已就绪', 'Web page ready')); observer.disconnect();
    }
  });
  observer.observe(document, {childList:true, subtree:true, characterData:true});
})();
