/* WebFullscreenUi consumes system bars/cutouts before either browser is laid out.
 * Do not depend on a plugin service or a browser's remaining CSS env() values.
 * A normal browser tab does not receive this script and keeps its own safe area. */
(function () {
  'use strict';
  if (window.top !== window) return;
  var apply = function () {
    var root = document.documentElement;
    if (!root) return false;
    root.setAttribute('data-dsha-safe-top', 'native');
    ['top', 'right', 'bottom', 'left'].forEach(function (side) {
      root.style.setProperty('--dsha-safe-area-inset-' + side, '0px');
    });
    return true;
  };
  if (apply()) return;
  // document_start can run before the parser creates <html>.
  var observer = new MutationObserver(function () {
    if (apply()) observer.disconnect();
  });
  observer.observe(document, {childList:true});
})();
