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
  // Runs before the locale plugin; these observer labels must be self-contained.
  var uiMessages = {
    zh: {initializing:'正在初始化网页插件：', initialized:'网页插件初始化返回：', ready:'网页已就绪'},
    en: {initializing:'Initializing web plugin: ', initialized:'Web plugin initialization returned: ', ready:'Web page ready'},
    'zh-Hant': {initializing:'正在初始化網頁外掛：', initialized:'網頁外掛初始化已返回：', ready:'網頁已就緒'},
    ru: {initializing:'Инициализация веб-плагина: ', initialized:'Инициализация веб-плагина завершилась: ', ready:'Веб-страница готова'},
    ja: {initializing:'Web プラグインを初期化中：', initialized:'Web プラグインの初期化から戻りました：', ready:'Web ページの準備ができました'},
    ko: {initializing:'웹 플러그인 초기화 중: ', initialized:'웹 플러그인 초기화가 반환되었습니다: ', ready:'웹 페이지가 준비되었습니다'},
    ar: {initializing:'جارٍ تهيئة إضافة الويب: ', initialized:'عادت تهيئة إضافة الويب: ', ready:'صفحة الويب جاهزة'},
    pt: {initializing:'Inicializando plugin da Web: ', initialized:'A inicialização do plugin da Web retornou: ', ready:'Página da Web pronta'},
    la: {initializing:'Additamentum interretiale initiatur: ', initialized:'Initiatio additamenti interretialis rediit: ', ready:'Pagina interretialis parata est'},
    fr: {initializing:'Initialisation du module Web : ', initialized:'L’initialisation du module Web a rendu la main : ', ready:'La page Web est prête'}
  };
  function uiText(key) {
    var locale = String(window.__DSHA_LANGUAGE__ || 'zh').replace(/_/g,'-').toLowerCase();
    var language = /^zh-(?:hant|tw|hk|mo)(?:-|$)/.test(locale) ? 'zh-Hant' : locale.split('-')[0];
    return (uiMessages[language] || uiMessages.zh)[key];
  }
  function report(type, id, message, fatal) {
    if (count++ > 500) return;
    var text = JSON.stringify({type:type, id:String(id || '').slice(0,214), message:String(message || '').slice(0,6000), fatal:!!fatal && !ready,
      nonce:String(binding.nonce||''),documentId:documentId,sequence:count-1,page:location.origin+location.pathname});
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
      reportPlugin('loading', id, uiText('initializing') + id);
      try {
        var result = original.apply(this, arguments);
        if (result && typeof result.then === 'function') return result.then(function (v) {
          reportPlugin('active', id, uiText('initialized') + id); return v;
        }, function (error) { reportPlugin('issue', id, detail(error)); throw error; });
        reportPlugin('active', id, uiText('initialized') + id); return result;
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
    if (ready) return;
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
    // rc2 首次说明、模型引导和空会话同样属于已经挂载的客户端。
    // 旧 WebView 的完成时注入可能错过整个 boot，不能以“曾看过 boot”为前提；
    // 使用真实布局的 shell 槽位，避免把任意非空 root 或 HTTP/插件就绪当作页面就绪。
    var mountedShell = root && root.querySelector('[data-shell-overlay]') && root.querySelector('[data-shell-bottom]');
    if (!boot && (document.querySelector('[data-composer-input]') || mountedShell || (seenBoot && root && root.children.length))) {
      ready = true; report('ready', '', uiText('ready')); observer.disconnect();
    }
  }
  var observer = new MutationObserver(inspect);
  observer.observe(document, {childList:true, subtree:true, characterData:true});
  // Old WebView has no document-start hook: inspect a failure/ready screen
  // that already exists when the fallback observer is injected.
  inspect();
})();
