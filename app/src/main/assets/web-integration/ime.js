/* 原生只提供实际可见区域；本模块不预测键盘高度或自行补间。 */
window.__dshaIme = window.__dshaIme || (() => {
  let binding, epoch = 0, lastId = 0, target, seat, token, written, sheet, rule;
  let originalTranslate, originalTransform;
  let raf = 0, pendingAck;
  const same = packet => binding && packet && packet.nonce === binding.nonce
    && packet.generation === binding.generation && packet.instance === binding.instance;
  const restore = () => {
    if (seat && seat.getAttribute('data-dsha-ime-seat') === token) seat.removeAttribute('data-dsha-ime-seat');
    rule?.style.removeProperty('translate');
    seat = token = undefined; written = undefined;
  };
  const cancel = () => {
    if (raf) cancelAnimationFrame(raf);
    raf = 0; target = undefined;
    if (pendingAck) pendingAck(false);
    pendingAck = undefined; restore();
  };
  const acquire = () => {
    if (seat?.isConnected) return true;
    restore();
    const input = document.activeElement;
    const found = input?.closest?.('[class*="_composerSeat"]');
    if (!found || !found.closest('[data-mobile-nav="frame"]')
        || !input.closest('[data-composer-input]')
        || typeof CSS === 'undefined' || !CSS.supports('translate', '0 1px')) return false;
    const css = getComputedStyle(found);
    // 不覆盖用户或其他插件持有的变换；iOS 专用 lift 仍归上游处理。
    if (found.hasAttribute('data-dsha-ime-seat') || css.transform !== 'none' || css.translate && css.translate !== 'none') return false;
    seat = found;
    originalTranslate = seat.style.getPropertyValue('translate');
    originalTransform = seat.style.getPropertyValue('transform');
    if (!sheet) {
      sheet = document.createElement('style');
      sheet.setAttribute('data-dsha-ime-sheet', '1');
      (document.head || document.documentElement).appendChild(sheet);
      sheet.sheet.insertRule('[data-dsha-ime-seat] {}', 0);
      rule = sheet.sheet.cssRules[0];
    }
    token = binding.nonce + '-' + epoch;
    rule.selectorText = `[data-dsha-ime-seat="${token}"]`;
    seat.setAttribute('data-dsha-ime-seat', token);
    return true;
  };
  const flush = () => {
    raf = 0;
    let supported = false;
    try {
    if (target && !document.hidden && (!window.visualViewport || Math.abs(window.visualViewport.scale - 1) < 0.01)
        && window.innerWidth > 0 && acquire()) {
      if (seat.getAttribute('data-dsha-ime-seat') === token
          && seat.style.getPropertyValue('translate') === originalTranslate
          && seat.style.getPropertyValue('transform') === originalTransform) {
        const visible = target.height * window.innerWidth / target.width;
        // 绝对可见高度减当前浏览器视口；不逐帧读取 DOM 几何，也保留 composer 原有底部间距。
        const offset = Math.min(0, visible - window.innerHeight);
        if (Number.isFinite(offset)) {
          const next = `0 ${offset}px`;
          // CSSOM 不改 seat/root 的 style 属性，避免上游 MutationObserver 每帧全树 reconcile。
          if (next !== written) rule.style.setProperty('translate', next, 'important');
          written = next; supported = true;
          // 末态 native resize 与消息异步到达均使用相同绝对目标；偏移归零才还原自有样式。
          if (target.phase === 'end' && Math.abs(offset) < 0.5) restore();
        }
      }
    }
    } catch (_) { supported = false; }
    if (!supported) restore();
    const ack = pendingAck; pendingAck = undefined;
    if (ack) ack(supported);
  };
  const schedule = () => { if (target && !raf) raf = requestAnimationFrame(flush); };
  window.addEventListener('resize', schedule);
  window.addEventListener('blur', cancel);
  window.addEventListener('pagehide', cancel);
  document.addEventListener('visibilitychange', () => { if (document.hidden) cancel(); });
  return {
    bind(next) {
      if (!same(next)) { cancel(); epoch = lastId = 0; }
      binding = next;
    },
    cancel,
    receive(packet, ack) {
      if (!same(packet) || !Number.isSafeInteger(packet.epoch) || packet.epoch < epoch
          || !Number.isSafeInteger(packet.frameId) || packet.frameId <= 0
          || packet.epoch === epoch && packet.frameId <= lastId) return;
      if (packet.epoch > epoch) { cancel(); epoch = packet.epoch; lastId = 0; }
      lastId = packet.frameId;
      if (packet.phase === 'cancel') { cancel(); ack({frameId:packet.frameId,supported:false}); return; }
      if (!['frame','end'].includes(packet.phase) || !Number.isFinite(packet.width) || packet.width <= 0
          || packet.width > 1000000 || !Number.isFinite(packet.height) || packet.height <= 0 || packet.height > 1000000) return;
      target = packet;
      if (pendingAck) pendingAck(false);
      pendingAck = supported => ack({frameId:packet.frameId,supported});
      schedule();
    }
  };
})();
