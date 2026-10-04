// Gecko 页面世界报告只传入现有同源只读诊断通道。
if (window.top === window) {
  window.__dshaStartupPort = browser.runtime.connectNative('dsha');
  let observationNonce='',pendingStartup=[];
  const sendStartup=report=>{
    report.nonce=observationNonce;report.page=location.origin+location.pathname;
    window.__dshaStartupPort.postMessage({type:'startup',report});
  };
  window.__dshaStartupPort.onMessage.addListener(message => {
    if (message?.type === 'browser-resume') {
      const host = document.head || document.documentElement;
      if (host) {
        const script = document.createElement('script');
        script.textContent = "window.dispatchEvent(new Event('dsha-browser-resume'));";
        host.appendChild(script); script.remove();
      }
      return;
    }
    if (message?.type !== 'language' || !['zh','en'].includes(message.language)) return;
    if(typeof message.observationNonce==='string'&&/^[a-f0-9]{32}$/.test(message.observationNonce)){
      observationNonce=message.observationNonce;const buffered=pendingStartup;pendingStartup=[];
      buffered.forEach(sendStartup);
    }
    const inject = () => {
      const host = document.head || document.documentElement;
      if (!host) return false;
      const script = document.createElement('script');
      script.textContent = "window.__DSHA_LANGUAGE__=" + JSON.stringify(message.language) + ";window.dispatchEvent(new CustomEvent('dsha-language'));";
      host.appendChild(script);script.remove();return true;
    };
    if (!inject()) {
      const observer = new MutationObserver(() => { if (inject()) observer.disconnect(); });
      observer.observe(document, {childList:true,subtree:true});
    }
  });
  window.addEventListener('dsha-language-selected', event => {
    if (event.detail === 'zh' || event.detail === 'en') window.__dshaStartupPort.postMessage({type:'language-selected',language:event.detail});
  });
  window.addEventListener('dsha-startup', event => {
    if (typeof event.detail !== 'string' || event.detail.length > 9000) return;
    try { const report=JSON.parse(event.detail);if(observationNonce)sendStartup(report);else if(pendingStartup.length<501)pendingStartup.push(report); } catch (_) {}
  });
}
