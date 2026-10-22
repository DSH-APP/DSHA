/* 仅处理网页已展示的最上层。普通历史回退由原生处理。 */
window.__dshaPageBack = function () {
  const visible = el => el && el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden';
  const layers = Array.from(document.querySelectorAll('[role="dialog"], [aria-modal="true"], [role="menu"], [data-trigger-menu], dialog[open]')).filter(visible);
  const top = layers[layers.length - 1];
  const escape = target => (target || document.activeElement || document.body).dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', code:'Escape', bubbles:true, cancelable:true}));
  if (top) {
    // 使用组件已有的 Escape 处理，原生不移除 React 持有的 DOM。
    const focus = document.activeElement;
    escape(top.contains(focus) ? focus : top);
    return true;
  }
  const frame = document.querySelector('[data-mobile-nav="frame"]');
  if (visible(frame) && !frame.hasAttribute('data-sidebar-collapsed')) { escape(); return true; }
  // 新版文件与预览由右侧面板控制器关闭，不能只改布局宽度而留下展开状态。
  const panel = document.querySelector('[data-sidebar-right-panel][data-sidebar-right-open]');
  if (visible(panel)) {
    document.documentElement.removeAttribute('data-dsha-back-handled');
    document.dispatchEvent(new CustomEvent('dsha-close-details'));
    if (document.documentElement.getAttribute('data-dsha-back-handled') === 'true') return true;
  }
  return false;
};

/* 原生展示只读主题；会话选择交给签名集成插件的真实 Host，消息绑定本次页面。 */
window.__dshaNativePresentation = window.__dshaNativePresentation || (function () {
  let binding, sequence = 0, observer, frame = 0, pending, deadline, lastColor = '', lastComputed = '', colorCanvas;
  const send = (type, data) => {
    if (!binding || window.top !== window) return;
    const text = JSON.stringify(Object.assign({type, nonce:binding.nonce,
      generation:binding.generation, instance:binding.instance, sequence:sequence++}, data));
    if (window.DshaTheme && typeof window.DshaTheme.postMessage === 'function') window.DshaTheme.postMessage(text);
    else window.dispatchEvent(new CustomEvent('dsha-native-report', {detail:text}));
  };
  const color = () => {
    frame = 0;
    if (!binding || document.hidden) return;
    const meta = document.querySelector('meta[name="theme-color"]');
    const body = document.body || document.documentElement;
    if (!body) return;
    const css = getComputedStyle(body);
    let value = css.getPropertyValue('--dsw-alias-bg-base').trim() || meta && meta.content || css.backgroundColor;
    if (!value || value === 'transparent') return;
    const probe = document.createElement('span');
    probe.style.cssText = 'position:fixed;visibility:hidden;pointer-events:none';
    probe.style.backgroundColor = value;
    if (!probe.style.backgroundColor) return;
    body.appendChild(probe); value = getComputedStyle(probe).backgroundColor; probe.remove();
    if (value === lastComputed) return; lastComputed = value;
    // 只将 CSS 颜色规范化为 sRGB；此画布没有网页图像，不读取屏幕或用户内容。
    try {
      colorCanvas = colorCanvas || document.createElement('canvas'); colorCanvas.width = colorCanvas.height = 1;
      const paint = typeof colorCanvas.getContext === 'function' && colorCanvas.getContext('2d');
      if (paint) {
        paint.clearRect(0,0,1,1); paint.fillStyle = value; paint.fillRect(0,0,1,1);
        const rgba = paint.getImageData(0,0,1,1).data;
        value = 'rgba(' + rgba[0] + ',' + rgba[1] + ',' + rgba[2] + ',' + rgba[3]/255 + ')';
      }
    } catch (_) {}
    if (value !== lastColor) { lastColor = value; send('theme-color', {color:value}); }
  };
  const schedule = () => { if (!frame) frame = requestAnimationFrame(color); };
  const dispatchRoute = () => {
    if (!pending || !binding) return;
    document.dispatchEvent(new CustomEvent('dsha-native-open-session', {detail:JSON.stringify(pending)}));
  };
  const cancelRoute = () => {
    clearTimeout(deadline); pending = undefined;
    document.dispatchEvent(new CustomEvent('dsha-native-route-cancel'));
  };
  document.addEventListener('dsha-native-session-result', event => {
    if (typeof event.detail !== 'string' || event.detail.length > 2048 || !pending) return;
    try {
      const result = JSON.parse(event.detail);
      if (result.nonce !== binding.nonce || result.requestToken !== pending.requestToken
          || result.sessionId !== pending.sessionId) return;
      clearTimeout(deadline); pending = undefined;
      send('session-route', {requestToken:result.requestToken,sessionId:result.sessionId,status:result.status});
    } catch (_) {}
  });
  document.addEventListener('dsha-native-route-ready', dispatchRoute);
  window.addEventListener('resize', schedule);
  document.addEventListener('visibilitychange', schedule);
  const scheme = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)');
  if (scheme && typeof scheme.addEventListener === 'function') scheme.addEventListener('change',schedule);
  else if (scheme && typeof scheme.addListener === 'function') scheme.addListener(schedule);
  return {
    install(next) {
      if (window.top !== window || !next || !/^[a-f0-9]{32}$/.test(next.nonce)
          || !Number.isSafeInteger(next.generation) || next.generation <= 0 || typeof next.instance !== 'string') return;
      if (binding && binding.nonce === next.nonce && binding.generation === next.generation
          && binding.instance === next.instance) { schedule(); return; }
      if (observer) observer.disconnect();
      if (frame) cancelAnimationFrame(frame);
      cancelRoute(); binding = next; sequence = 0; lastColor = ''; lastComputed = '';
      window.__dshaIme?.bind(next);
      document.documentElement.setAttribute('data-dsha-native-nonce', next.nonce);
      observer = new MutationObserver(schedule);
      observer.observe(document.documentElement,{attributes:true,attributeFilter:['class','style','data-ds-dark-theme']});
      if (document.body) observer.observe(document.body,{attributes:true,attributeFilter:['class','style','data-ds-dark-theme']});
      if (document.head) observer.observe(document.head,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});
      schedule();
    },
    cancel() { cancelRoute(); window.__dshaIme?.cancel(); },
    ime(request) { window.__dshaIme?.receive(request, result => send('ime-ack', result)); },
    route(request) {
      if (!binding || !request || request.nonce !== binding.nonce
          || request.generation !== binding.generation || request.instance !== binding.instance
          || typeof request.sessionId !== 'string' || !request.sessionId || request.sessionId.length > 512
          || typeof request.requestToken !== 'string' || !request.requestToken) return;
      cancelRoute(); pending = request; dispatchRoute();
      if (pending) deadline = setTimeout(() => {
        if (!pending) return; const expired = pending; pending = undefined;
        send('session-route',{requestToken:expired.requestToken,sessionId:expired.sessionId,status:'unavailable'});
      },8000);
    }
  };
})();
