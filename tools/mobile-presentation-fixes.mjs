// 固定 0.2.2 的展示兼容：技术标记不依赖翻译；原生恢复可见区不等两帧。
export function applyMobilePresentationFixes(source) {
  const replace = (before, after) => {
    if (source.split(before).length !== 2) throw Error('MOBILE_PRESENTATION_ANCHOR: '+before.slice(0,80));
    source=source.replace(before,after);
  };
  const host='  html [data-mobile-nav="frame"] [class*="hHd-Xa_toggle"]:is([aria-label*="sidebar" i], [aria-label*="侧边栏"]),';
  replace(host,'  html [data-mobile-nav="frame"] [data-sidebar-opener],\n'+host);
  replace('[data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-mobile-nav="toggle"] {\n    position: absolute !important;\n    left: 8px !important;\n    top: auto !important;',
    '[data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-mobile-nav="toggle"] {\n    position: absolute !important;\n    left: 8px !important;\n    top: 6px !important;');
  replace('html [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleRow"] {\n      height: auto !important;\n      min-height: 0 !important;',
    'html [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleRow"] {\n      height: auto !important;\n      min-height: 40px !important;');
  // WebView 原生尺寸包含输入法；壳直接使用百分比高度，不等待 JS resize 回调发布变量。
  const center='  [data-mobile-nav="frame"] > [class*="_centerCol"] {';
  replace(center, '  html[data-dsha-ime-mode="resize"] [data-mobile-nav="frame"] {\n    height: 100% !important;\n  }\n'+center);
  // 原生已消费 IME；visualViewport 可晚于容器恢复。
  // 再取 min 会二次扣键盘：壳变矮，同时 composer 继续平移。浏览器标签保持上游模型。
  replace('const available = Math.max(1, Math.min(height, visible?.height ?? height));',
    'const nativeViewport = root.getAttribute("data-dsha-safe-top") === "native";\n        const available = Math.max(1, nativeViewport ? height : Math.min(height, visible?.height ?? height));');
  replace('const topValue = `${visible?.offsetTop ?? 0}px`;',
    'const topValue = `${nativeViewport ? 0 : visible?.offsetTop ?? 0}px`;');
  replace('if ((atRest || pendingVisibleFrames >= 2) &&',
    'if ((nativeViewport || atRest || pendingVisibleFrames >= 2) &&');
  return source;
}
