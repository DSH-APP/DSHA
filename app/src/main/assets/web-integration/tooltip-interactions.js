/* DSHA_TOOLTIP_INTERACTION_V1：区分触摸、鼠标悬停与键盘焦点；不改变按钮/菜单动作。 */
function createDshaTooltipRuntime(environment) {
  const doc = environment.document, win = environment.window;
  const later = environment.setTimeout || setTimeout, cancel = environment.clearTimeout || clearTimeout;
  const active = new Set();
  let users = 0, keyboard = true;
  const dismissAll = () => { for (const item of Array.from(active)) item.dismiss(); };
  const pointerDown = () => { keyboard = false; dismissAll(); };
  const keyDown = event => {
    if (!['Shift', 'Control', 'Alt', 'Meta'].includes(event.key)) keyboard = true;
    if (event.key === 'Escape') dismissAll();
  };
  const visibility = () => { if (doc.hidden) dismissAll(); };
  const listen = on => {
    const method = on ? 'addEventListener' : 'removeEventListener';
    doc[method]('pointerdown', pointerDown, true);
    doc[method]('pointercancel', dismissAll, true);
    doc[method]('keydown', keyDown, true);
    doc[method]('scroll', dismissAll, true);
    doc[method]('visibilitychange', visibility);
    win[method]('blur', dismissAll);
  };
  return {
    create(callbacks) {
      let mounted = false, shown = false, timer = null;
      const clear = () => { if (timer !== null) { cancel(timer); timer = null; } };
      const open = () => {
        timer = null;
        if (!mounted || !callbacks.enabled()) { active.delete(controller); return; }
        for (const item of Array.from(active)) if (item !== controller) item.dismiss();
        shown = true; active.add(controller); callbacks.open();
      };
      const controller = {
        mount() {
          if (!mounted) { mounted = true; if (users++ === 0) listen(true); }
          return () => {
            if (!mounted) return;
            mounted = false; clear(); active.delete(controller); shown = false;
            if (--users === 0) listen(false);
          };
        },
        dismiss() {
          clear(); active.delete(controller);
          if (shown) { shown = false; if (mounted) callbacks.close(); }
        },
        pointerEnter(event) {
          // 触摸产生的兼容 mouseover 不参与；只接受真实鼠标的 pointerenter。
          if (!mounted || event.pointerType !== 'mouse' || !callbacks.enabled()) return;
          clear(); active.add(controller);
          const delay = callbacks.delay();
          if (delay > 0) timer = later(open, delay); else open();
        },
        pointerLeave() { controller.dismiss(); },
        focus() { if (keyboard) { clear(); open(); } },
        blur() { controller.dismiss(); },
        activate() { controller.dismiss(); }
      };
      return controller;
    }
  };
}
