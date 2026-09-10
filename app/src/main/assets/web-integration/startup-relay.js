// Gecko 页面世界报告只传入现有同源只读诊断通道。
if (window.top === window) {
  window.__dshaStartupPort = browser.runtime.connectNative('dsha');
  window.addEventListener('dsha-startup', event => {
    if (typeof event.detail !== 'string' || event.detail.length > 9000) return;
    try { window.__dshaStartupPort.postMessage({type:'startup', report:JSON.parse(event.detail)}); } catch (_) {}
  });
}
