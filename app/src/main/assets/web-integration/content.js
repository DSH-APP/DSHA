if (window.top === window) {
  const port = browser.runtime.connectNative('dsha');
  port.onMessage.addListener(message => {
    if (message?.type !== 'back' || !Number.isSafeInteger(message.id)) return;
    let handled = false;
    try { handled = window.__dshaPageBack(); } catch {}
    port.postMessage({type:'back', id:message.id, handled});
  });
}
