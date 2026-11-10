// This isolated extension is installed only by the emergency Gecko surface.
// It relays the ten canonical UI locale values and never reads URL credentials.
(function () {
  'use strict';
  if (window.top !== window || location.protocol !== 'http:' || location.hostname !== 'localhost'
      || !location.port || location.username || location.password) return;
  const valid = value => ['zh','zh-Hant','en','ru','ja','ko','ar','pt','la','fr'].includes(value);
  const port = browser.runtime.connectNative('dsha_recovery_locale');
  port.onMessage.addListener(message => {
    if (message?.type !== 'language' || !valid(message.language)) return;
    const inject = () => {
      const host = document.head || document.documentElement;
      if (!host) return false;
      const script = document.createElement('script');
      script.textContent = "window.__DSHA_LANGUAGE__='" + message.language
        + "';window.dispatchEvent(new CustomEvent('dsha-language'));";
      host.appendChild(script);
      script.remove();
      return true;
    };
    if (!inject()) {
      const observer = new MutationObserver(() => { if (inject()) observer.disconnect(); });
      observer.observe(document, { childList: true, subtree: true });
    }
  });
  window.addEventListener('dsha-language-selected', event => {
    if (valid(event.detail)) port.postMessage({ type: 'language-selected', language: event.detail });
  });
})();
