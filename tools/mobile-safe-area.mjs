// The native container owns bars/cutouts. Browser tabs retain CSS env() fallbacks.
// 3.3.1/3.3.5 的 frame 顶部已经自带 var(--dsha-safe-area-inset-top, env(...))；这里跳过已包好的
// 那一处，避免生成 var(--dsha-…, var(--dsha-…, env(...))) 的嵌套贬值。
export function applyMobileSafeArea(source) {
  return source.replace(/(var\(--dsha-safe-area-inset-(?:top|right|bottom|left),\s*)?env\(safe-area-inset-(top|right|bottom|left),\s*0px\)/g,
    (original, wrapped, side) => wrapped ? original : `var(--dsha-safe-area-inset-${side}, ${original})`);
}
