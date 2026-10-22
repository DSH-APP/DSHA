if (window.top === window) {
  if (location.pathname === '/__dsha/browser-origin-restore' && document.body?.dataset.dshaOriginRestore === '1') {
    const migration = browser.runtime.connectNative('dsha-origin-restore');
    migration.onMessage.addListener(message => {
      if (message?.type !== 'origin-restore' || typeof message.configuration !== 'string') return;
      const host = document.head || document.documentElement, script = document.createElement('script');
      script.textContent = "window.dispatchEvent(new CustomEvent('dsha-origin-restore-start',{detail:" + JSON.stringify(message.configuration) + "}));";
      host.appendChild(script); script.remove();
    });
  }
  const port = window.__dshaStartupPort || browser.runtime.connectNative('dsha');
  window.addEventListener('dsha-native-report', event => {
    if (typeof event.detail !== 'string' || event.detail.length > 2048) return;
    try { port.postMessage({type:'presentation',report:JSON.parse(event.detail)}); } catch (_) {}
  });
  port.onMessage.addListener(message => {
    if (message?.type === 'ime-frame') {
      window.__dshaNativePresentation?.ime(message.request); return;
    }
    if (message?.type === 'presentation-init') {
      window.__dshaNativePresentation?.install(message.binding); return;
    }
    if (message?.type === 'session-route') {
      window.__dshaNativePresentation?.route(message.request); return;
    }
    if (message?.type === 'presentation-cancel') {
      window.__dshaNativePresentation?.cancel(); return;
    }
    if (message?.type !== 'back' || !Number.isSafeInteger(message.id)) return;
    let handled = false;
    try { handled = window.__dshaPageBack(); } catch {}
    port.postMessage({type:'back', id:message.id, handled});
  });
}
