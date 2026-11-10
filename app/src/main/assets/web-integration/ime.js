/* 原生只提供实际可见区域；本模块不预测键盘高度或自行补间。 */
window.__dshaIme = window.__dshaIme || (() => {
  let binding, epoch = 0, lastId = 0, target, seat, token, written, sheet, rule, childRule;
  let originalTranslate, originalTransform, hero, resizeObserver, contextObserver;
  let raf = 0, pendingAck, applied = 0, observedWidth = window.innerWidth;
  const same = packet => binding && packet && packet.nonce === binding.nonce
    && packet.generation === binding.generation && packet.instance === binding.instance;
  const context = found => {
    const content = found.closest('[data-conversation-content]');
    const phase = found.closest('[data-phase]');
    return {content, phase, key:JSON.stringify([content?.getAttribute('data-conversation-session'),
      content?.getAttribute('data-content-phase'), phase?.getAttribute('data-phase')]),
      hero:content?.getAttribute('data-content-phase') === 'hero' || phase?.getAttribute('data-phase') === 'hero'};
  };
  const heroContentHeight = rect => {
    // composerHero 的尾部留白（当前上游为 32px）不属于需要避让的可见控件。
    // 从真实输入卡取底界；卡内工具栏/附件已包含在内，卡外 dock 只计实际可见子项。
    const card = seat.querySelector('[data-composer-input]')?.closest('[data-composer-card]');
    if (!card || !seat.contains(card))
      return rule.style.maxHeight ? Math.max(rect.height, seat.scrollHeight) : rect.height;
    let bottom = card.getBoundingClientRect().bottom;
    for (const item of seat.querySelectorAll('[data-composer-dock] > *')) {
      const bounds = item.getBoundingClientRect();
      if (bounds.width > 0 && bounds.height > 0 && getComputedStyle(item).visibility !== 'hidden')
        bottom = Math.max(bottom, bounds.bottom);
    }
    return Math.max(0, bottom - rect.top + seat.scrollTop);
  };
  const restore = () => {
    resizeObserver?.disconnect(); contextObserver?.disconnect();
    resizeObserver = contextObserver = undefined;
    if (seat && seat.getAttribute('data-dsha-ime-seat') === token) seat.removeAttribute('data-dsha-ime-seat');
    if (rule) rule.style.cssText = '';
    if (childRule) childRule.style.cssText = '';
    seat = token = hero = undefined; written = undefined; applied = 0;
  };
  const cancel = () => {
    if (raf) cancelAnimationFrame(raf);
    raf = 0; target = undefined;
    if (pendingAck) pendingAck(false);
    pendingAck = undefined; restore();
  };
  const schedule = () => { if (target && !raf) raf = requestAnimationFrame(flush); };
  const acquire = () => {
    const input = document.activeElement;
    const found = input?.closest?.('[class*="_composerSeat"]');
    if (seat?.isConnected && found === seat && (!hero || context(seat).key === hero.key)) return true;
    restore();
    if (!binding || !found || !found.closest('[data-mobile-nav="frame"]')
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
      sheet.sheet.insertRule('[data-dsha-ime-seat] > * {}', 1);
      rule = sheet.sheet.cssRules[0]; childRule = sheet.sheet.cssRules[1];
    }
    token = binding.nonce + '-' + epoch;
    rule.selectorText = '[data-dsha-ime-seat="' + token + '"]';
    childRule.selectorText = '[data-dsha-ime-seat="' + token + '"] > *';
    seat.setAttribute('data-dsha-ime-seat', token);
    const current = context(seat);
    if (current.hero) {
      // focusin 在输入法 resize 前保留整组的实际位置；不缓存整个网页或 PiP 几何。
      const rect = seat.getBoundingClientRect();
      const scroll = seat.closest('[data-conversation-scroll]') || seat.parentElement;
      hero = {...current, top:rect.top, scroll};
      resizeObserver = new ResizeObserver(schedule);
      resizeObserver.observe(seat);
      resizeObserver.observe(scroll);
    }
    if (current.content || current.phase) {
      contextObserver = new MutationObserver(() => {
        // 同一 React seat 可能换到另一会话/阶段。立即退休旧锚点，再按当前 DOM 认领。
        const latest = target; restore(); target = latest; schedule();
      });
      for (const element of new Set([current.content, current.phase].filter(Boolean)))
        contextObserver.observe(element, {attributes:true,
          attributeFilter:['data-conversation-session','data-content-phase','data-phase']});
    }
    return true;
  };
  const flush = () => {
    raf = 0;
    let supported = false;
    try {
      if (target && !document.hidden && (!window.visualViewport || Math.abs(window.visualViewport.scale - 1) < 0.01)
          && window.innerWidth > 0 && acquire()
          && seat.getAttribute('data-dsha-ime-seat') === token
          && seat.style.getPropertyValue('translate') === originalTranslate
          && seat.style.getPropertyValue('transform') === originalTransform) {
        const visible = target.height * window.innerWidth / target.width;
        let offset;
        if (hero) {
          // 标题、工作区和输入同属 composerSeat。只在实际遮挡时最小上移；
          // native 末态 resize 导致的重新居中由此抵消，不能再套整高差值。
          const floor = Math.max(0, hero.scroll.getBoundingClientRect().top);
          const available = Math.max(1, visible - 8 - floor);
          const before = seat.getBoundingClientRect();
          const naturalHeight = heroContentHeight(before);
          const clipped = naturalHeight > available + 0.5;
          const nextCap = clipped ? available + 'px' : '';
          const capChanged = rule.style.maxHeight !== nextCap;
          if (capChanged) {
            rule.style.setProperty('max-height', nextCap, 'important');
            rule.style.setProperty('overflow-y', clipped ? 'auto' : 'visible', 'important');
            rule.style.setProperty('overscroll-behavior-y', clipped ? 'contain' : 'auto');
            childRule.style.setProperty('flex-shrink', clipped ? '0' : '');
          }
          const rect = capChanged ? seat.getBoundingClientRect() : before;
          const height = Math.min(naturalHeight, available);
          const top = Math.max(floor, Math.min(hero.top, visible - 8 - height));
          offset = top - (rect.top - applied);
        } else {
          // 普通聊天保留原有底部间距并随实际键盘帧贴底；不锁整页高度。
          offset = Math.min(0, visible - window.innerHeight);
        }
        if (Number.isFinite(offset)) {
          const next = '0 ' + offset + 'px';
          // CSSOM 不改 seat/root 的 style 属性，避免上游 MutationObserver 每帧全树 reconcile。
          if (next !== written) rule.style.setProperty('translate', next, 'important');
          written = next; applied = offset; supported = true;
          if (!hero && target.phase === 'end' && Math.abs(offset) < 0.5) restore();
        }
      }
    } catch (_) { supported = false; }
    if (!supported) restore();
    const ack = pendingAck; pendingAck = undefined;
    if (ack) ack(supported);
  };
  // 先保存 focus 时的 hero 位置；不等待第一条可能已缩容的 native end 包。
  document.addEventListener('focusin', () => { if (binding) { acquire(); schedule(); } }, true);
  document.addEventListener('focusout', event => {
    if (seat && !seat.contains(event.relatedTarget)) cancel();
  }, true);
  window.addEventListener('resize', () => {
    if (window.innerWidth !== observedWidth) { observedWidth = window.innerWidth; cancel(); }
    else schedule();
  });
  window.addEventListener('orientationchange', cancel);
  window.addEventListener('blur', cancel);
  window.addEventListener('pagehide', cancel);
  document.addEventListener('dsha-session-open', cancel);
  document.addEventListener('dsha-native-open-session', cancel);
  document.addEventListener('visibilitychange', () => { if (document.hidden) cancel(); });
  return {
    bind(next) {
      if (!same(next)) { cancel(); epoch = lastId = 0; }
      binding = next;
    },
    cancel,
    receive(packet, ack) {
      // resize 事件可晚于浏览器尺寸更新；先退休旧宽度的锚点，不能取消刚到的新宽度帧。
      if (same(packet) && window.innerWidth !== observedWidth) {
        observedWidth = window.innerWidth; cancel();
      }
      if (!same(packet) || !Number.isSafeInteger(packet.epoch) || packet.epoch < epoch
          || !Number.isSafeInteger(packet.frameId) || packet.frameId <= 0
          || packet.epoch === epoch && packet.frameId <= lastId) return;
      if (packet.epoch > epoch) {
        // 新 native epoch 本身就是退休边界，即使 cancel 包未到也不复用旧几何。
        // 唯一例外是 bind 后首包：focusin 已在键盘前保存了本次真实位置。
        if (epoch > 0) cancel();
        else {
          if (raf) cancelAnimationFrame(raf);
          raf = 0;
          if (pendingAck) pendingAck(false);
          pendingAck = undefined;
        }
        epoch = packet.epoch; lastId = 0;
      }
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
