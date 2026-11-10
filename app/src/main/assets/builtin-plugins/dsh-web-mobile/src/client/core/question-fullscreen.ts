// Ask-question card: the fullscreen answer band, and the answer field's way
// back into the card body's visible box. Pure geometry — no DOM (architecture
// R4: core/ stays engine-free), so every number below is unit-tested without a
// browser. The browser adapter is effects/question-fullscreen.ts; the CSS that
// consumes its marker and its measured reserve lives in styles/layout.css.ts.
//
// 事故（2026-10-25 owner 亲报三条，本文件对应第 2 条）：长问题 + 键盘弹起后
// 「打字时看不到自己打的字，收起键盘才看到」，内容整体上下猛跳。
//
// 机制是两块盒子的算术，不是某个变量写错：
//  - 宿主卡片 `Mbwy4a_card` 自带 `max-height: min(60vh, 520px)`。这台 Android
//    WebView（adjustResize）上 vh 跟着软键盘一起缩（实测 754 → 471，见 pitfalls
//    §弹层闪），于是键盘一响，卡片的高度预算从 452 掉到 283；
//  - 卡片里唯一能滚的是 `Mbwy4a_body`（宿主 `flex:auto; min-height:0;
//    overflow-y:auto`），自定义回答框是 body 的**最后一块**。浏览器只在「聚焦那
//    一刻」把输入框滚进可视区，而那一刻卡片还没缩；之后卡片缩掉 169px、body 的
//    scrollTop 不变 ⇒ 回答框落到 body 可视区之外，用户盲打；
//  - 收起键盘 vh 回弹、卡片长回去，回答框又「出现」—— 录屏里那句「收起键盘才看到」
//    与上下猛跳都是这一条。
//
// 解法两半都在本模块的调用方：① owner 拍板的手动「展开」按钮把卡片撑到「顶栏底 →
// composer 顶」的整条可见带（CSS 只做算术，数值由 questionBandPx 交出去）；
// ② answerFieldScrollTop 把回答框重新滚回 body 可视区（focusin 与视口变化各一次）。
//
// 只回答「可用高度」与「该不该真的进全屏态」两件事，量都在调用方取：
//  - 可见带底 = `--dsha-mobile-visible-vh`（viewport-meta.ts 维护，= min(innerHeight,
//    visualViewport.height)），顶栏底 = `--mobile-nav-header-h`（header-metrics.ts
//    维护，= frame 顶 → header 底，已含安全区下推）；
//  - 卡片下面还压着 composer 自己的东西（座位里的 dock 行等）以及 frame 自带的上下
//    内边距，这一截由调用方量出来当 reservePx 交回 CSS（CSS 只做
//    visible - header - reserve，不出现任何写死的 px）。

/** The host card's own height ceiling: `max-height: min(60vh, 520px)`. Kept as
 *  numbers so "does arming fullscreen actually add room" is a comparison
 *  against the host's own formula instead of a copied magic number. */
export const HOST_CARD_HEIGHT_RATIO = 0.6
export const HOST_CARD_HEIGHT_CAP_PX = 520

/** Gap kept between the answer field and the card body's edges when the field
 *  is scrolled back into view. The field's own block padding is 8px, so the
 *  text keeps a readable margin instead of grazing the box. */
export const ANSWER_FIELD_MARGIN_PX = 8

/** Everything the band needs, measured by the effect in viewport coordinates. */
export interface QuestionBandInput {
  /** min(innerHeight, visualViewport.height) — the band the IME leaves. */
  visibleHeight: number
  /** Frame top → header bottom (`--mobile-nav-header-h`). */
  headerHeight: number
  /** The question frame's bottom edge (its box bottom, padding included). */
  frameBottom: number
  /** The composer seat's bottom edge; sticky pins it to the band's bottom. */
  seatBottom: number
  /** The frame's own vertical padding (top + bottom). */
  framePadding: number
}

export interface QuestionBand {
  /** Height given back below the card: seat-to-band gap + what sits under the
   *  frame inside the seat + the frame's own padding. */
  reservePx: number
  /** Height the card may take in fullscreen mode. 0 when the measurement is
   *  not trustworthy — the caller must then leave the host default alone. */
  bandPx: number
}

const USELESS: QuestionBand = { reservePx: 0, bandPx: 0 }

/** The fullscreen band: the visible strip between the header's bottom and the
 *  composer's top, minus what the card does not own.
 *
 *  Fail-open on every unverified input: a wrong 0 means "keep the host's own
 *  min(60vh, 520px)", which is the pre-existing state, while a wrong positive
 *  would size the card against a band that is not on screen (overlapping the
 *  header, or taller than the viewport). */
export function questionBandPx(input: QuestionBandInput): QuestionBand {
  const { visibleHeight, headerHeight, frameBottom, seatBottom, framePadding } = input
  for (const value of [visibleHeight, headerHeight, frameBottom, seatBottom, framePadding]) {
    if (!Number.isFinite(value)) return USELESS
  }
  if (visibleHeight <= 0 || headerHeight < 0) return USELESS
  // The seat is sticky-bottom: it should sit exactly on the band's bottom, and
  // anything left between them is room the card cannot use. The second term is
  // the composer's own rows under the question frame; the third is the frame's
  // padding, which the card's box does not include.
  const belowSeatPx = Math.max(0, visibleHeight - seatBottom)
  const belowFramePx = Math.max(0, seatBottom - frameBottom)
  const reservePx = belowSeatPx + belowFramePx + Math.max(0, framePadding)
  const bandPx = Math.max(0, visibleHeight - headerHeight - reservePx)
  return { reservePx, bandPx }
}

/** The host's own ceiling for the same viewport, i.e. what the card would get
 *  without this feature. 0 when the viewport is unreadable. */
export function hostCardHeightPx(visibleHeight: number): number {
  if (!Number.isFinite(visibleHeight) || visibleHeight <= 0) return 0
  return Math.min(visibleHeight * HOST_CARD_HEIGHT_RATIO, HOST_CARD_HEIGHT_CAP_PX)
}

/** Whether the fullscreen marker is on.
 *
 *  - `requested` is the user's manual toggle (`false` always wins: the owner
 *    asked for a manual button, never for an automatic expansion);
 *  - entering needs proof that fullscreen actually adds room: a band at or
 *    below the host's own min(60vh, 520px) would only redraw the same card;
 *  - leaving only happens when the measurement becomes unusable, never because
 *    the band shrank. The keyboard opening shrinks the band on every IME frame;
 *    dropping out of fullscreen there would be the exact 「上下猛跳」 this
 *    feature exists to remove (same hysteresis reasoning as nextComposerTight
 *    in core/composer-clearance.ts). */
export function nextQuestionFull(
  previous: boolean,
  requested: boolean,
  bandPx: number,
  hostDefaultPx: number,
): boolean {
  if (!requested) return false
  if (!Number.isFinite(bandPx) || bandPx <= 0) return false
  if (previous) return true
  if (!Number.isFinite(hostDefaultPx) || hostDefaultPx <= 0) return true
  return bandPx > hostDefaultPx
}

/** The scrollable box the answer field lives in, plus the field's own rect. */
export interface AnswerFieldView {
  /** Answer field's top edge, viewport coordinates. */
  fieldTop: number
  /** Answer field's bottom edge, viewport coordinates. */
  fieldBottom: number
  /** Card body's top edge (its own box, not yet clipped by the screen). */
  bodyTop: number
  /** Card body's bottom edge (its own box, not yet clipped by the screen). */
  bodyBottom: number
  /** Current scrollTop of the card body. */
  scrollTop: number
  /** scrollHeight / clientHeight of the card body. */
  scrollHeight: number
  clientHeight: number
  /** Visual viewport's top edge in the same (layout-viewport) coordinates.
   *  Optional: omit it and the body box is taken at face value, which is
   *  correct on engines that resize the layout viewport for the IME. */
  visibleTop?: number
  /** Visual viewport's bottom edge (`offsetTop + height`), same coordinates. */
  visibleBottom?: number
}

/** Where the card body has to scroll so the answer field is inside its visible
 *  box again, or the current scrollTop when nothing needs to move.
 *
 *  The visible box is the body's own box **intersected with the visual
 *  viewport**: with `adjustResize` the layout viewport shrinks and bodyBottom
 *  already is the screen edge, but a platform that instead pans the visual
 *  viewport leaves the body box hanging below the pixel band the user can
 *  actually see. In that model "field is inside bodyBottom" is true while the
 *  user still types blind, so the caller hands us the visual viewport and the
 *  intersection is what counts (2026-10-25 residual report).
 *
 *  Deliberately NOT scrollIntoView: that walks every scrollable ancestor and
 *  would move the conversation behind the card (the host owns that scroll and
 *  re-anchors it while streaming). Only the card body moves, and only when the
 *  field is actually outside the box — a field taller than the body is aligned
 *  to its bottom edge, which is where the caret is. */
export function answerFieldScrollTop(view: AnswerFieldView): number {
  const {
    fieldTop,
    fieldBottom,
    bodyTop,
    bodyBottom,
    scrollTop,
    scrollHeight,
    clientHeight,
    visibleTop,
    visibleBottom,
  } = view
  for (const value of [fieldTop, fieldBottom, bodyTop, bodyBottom, scrollTop, scrollHeight, clientHeight]) {
    if (!Number.isFinite(value)) return scrollTop
  }
  const top = visibleTop === undefined || !Number.isFinite(visibleTop) ? bodyTop : Math.max(bodyTop, visibleTop)
  const bottom =
    visibleBottom === undefined || !Number.isFinite(visibleBottom) ? bodyBottom : Math.min(bodyBottom, visibleBottom)
  // No overlap at all: the body (or the screen) is not somewhere scrolling can
  // fix — leave the content alone instead of jumping to a bogus offset.
  if (bottom - top < 1) return scrollTop
  const max = Math.max(0, scrollHeight - clientHeight)
  const room = Math.max(0, bottom - top)
  const margin = Math.min(ANSWER_FIELD_MARGIN_PX, room / 2)
  let next = scrollTop
  if (fieldTop < top + margin) next = scrollTop - (top + margin - fieldTop)
  else if (fieldBottom > bottom - margin) next = scrollTop + (fieldBottom - (bottom - margin))
  return Math.min(Math.max(next, 0), max)
}
