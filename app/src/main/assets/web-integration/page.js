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
  // 详情预览由公共 layout 控制器关闭。
  const shell = document.querySelector('[data-shell-overlay]')?.parentElement;
  if (visible(shell) && !shell.hasAttribute('data-details-collapsed')) {
    document.documentElement.removeAttribute('data-dsha-back-handled');
    document.dispatchEvent(new CustomEvent('dsha-close-details'));
    if (document.documentElement.getAttribute('data-dsha-back-handled') === 'true') return true;
  }
  return false;
};
