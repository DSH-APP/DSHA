// Composer clearance: how much of the host composer seat sits outside the band
// the user can actually see. Pure geometry + the marker's hysteresis — no DOM
// (architecture: core/ stays DOM-free), so both halves are unit-tested without
// an engine. The browser adapter is effects/composer-clearance.ts; the CSS that
// consumes its marker lives in styles/layout.css.ts.
//
// 事故（issue #245，owner 亲报 2026-10-25）：「当输入时屏幕变为横屏时，输入框
// 可能会错位甚至消失，目前只能进行刷新」。横屏 + 软键盘把布局视口压到 ~110-160px
// 之后，composer 座位（宿主 `_composerSeat`，position:sticky; bottom:0）仍然按
// 自己的高度（~70-102px）钉在可见带底部；它的**上半截就是 Lexical 编辑行**，于是
// 编辑行落进会话顶栏的盒子（插件给顶栏 `z-index:30` + 不透明底，见 layout.css.ts
// 「Opaque AND above the flow」），被顶栏整块盖住 —— 用户看到的就是「输入框消失」，
// 只露出底部的发送行（「错位」）。sticky 是纯 CSS，滚动改不了它的位置，键盘不收
// 起来就一直在；刷新会收起键盘、布局视口回到 360px，所以看起来「只能刷新」。
//
// 这里只回答一个问题：「座位有多少像素落在用户看不见的地方」——两个互相独立的
// 来源相加：
//   ① 被顶栏盖住：座位顶边（按 sticky 语义 = 可见带底 − 座位高）高于顶栏底边；
//   ② 掉到可见带外：座位底边在可见带底边之下（可写视口 / 布局视口不同步时的兜底）。
// 只要这个数 > 0，座位就不可能被完整看到，标记就该武装。判定刻意用**未缓解的座位
// 高度**（调用方在标记关闭时缓存），否则 CSS 一收起次要行，几何就自证清白、标记
// 会退出，下一次 resize 又进来 —— 一个自激的状态机。

/** How far the page scroll offset and the visual viewport's offsetTop may drift before the
 *  screen-space model counts as unverified. Same value and reasoning as
 *  composer-keyboard-lift.ts's CHANNEL_TOLERANCE_PX, restated here because
 *  core/ may not import effects/ (layer direction) and the tolerance is part
 *  of this module's fail-open contract, not of that effect. */
export const SCROLL_CHANNEL_TOLERANCE_PX = 24

/** Occlusion is only real from 1px on: sub-pixel overlap is rect rounding, not
 *  a hidden editing row. Entering at 0 would also flip the marker on every
 *  fractional resize tick. */
export const TIGHT_ENTER_PX = 1

/** The measured band, in viewport coordinates. */
export interface ComposerBand {
  /** The smaller of the layout and visual viewport heights — the band the IME leaves
   *  (same `available` quantity viewport-meta.ts publishes). */
  visibleHeight: number
  /** Conversation header's bottom edge. */
  headerBottom: number
  /** Seat height with every row visible (the *unmitigated* height on purpose). */
  seatHeight: number
  /** Measured seat bottom edge. Sticky pins it to the band's bottom while the
   *  layout and visual viewports agree; a stale layout viewport shows up here. */
  seatBottom: number
  /** The page scroll offset (layout-viewport scroll). */
  scrollY: number
  /** `visualViewport.offsetTop`. */
  offsetTop: number
}

/** Pixels of the composer seat the user cannot see, 0 when the model is not
 *  trusted. Fail-open on every unverified input: a wrong 0 (the status quo)
 *  costs nothing, a wrong positive would re-stack the header under the
 *  composer on devices that never had the defect. */
export function composerOccludedPx(band: ComposerBand): number {
  const { visibleHeight, headerBottom, seatHeight, seatBottom, scrollY, offsetTop } = band
  for (const value of [visibleHeight, headerBottom, seatHeight, seatBottom, scrollY, offsetTop]) {
    if (!Number.isFinite(value)) return 0
  }
  // Screen-space coordinates only make sense while both scroll channels agree
  // (layout viewport == visual viewport, the Android adjustResize model). A iOS
  // style visual-viewport pan separates them; then rect coordinates are not
  // screen positions and any occlusion number derived from them is fiction.
  if (Math.abs(scrollY - offsetTop) > SCROLL_CHANNEL_TOLERANCE_PX) return 0
  const visible = Math.max(0, visibleHeight)
  // Sticky bottom pins the seat's bottom edge to the band's bottom; what is
  // left above it is the seat itself. A seat taller than the band starts at 0.
  const seatTop = Math.max(0, visible - Math.max(0, seatHeight))
  const underHeader = Math.max(0, headerBottom - seatTop)
  const belowBand = Math.max(0, seatBottom - visible)
  return underHeader + belowBand
}

/** Hysteresis for the marker: enter on a real overlap, leave only when the
 *  occlusion is gone. Without the dead band a 1px IME animation frame or a
 *  rounding tick would toggle the layout on and off. */
export function nextComposerTight(previous: boolean, occludedPx: number): boolean {
  if (!Number.isFinite(occludedPx)) return false
  return previous ? occludedPx > 0 : occludedPx >= TIGHT_ENTER_PX
}
