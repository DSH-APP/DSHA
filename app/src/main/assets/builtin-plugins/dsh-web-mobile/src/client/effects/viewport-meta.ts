// Viewport meta ownership + the stable (keyboard-less) viewport height + the
// iOS focus-zoom marker.
//
// 2026-10-12 P1 拆分第五片：由 effects/phone-chrome.ts 逐字搬出（纯搬运，不改行为）。
// 形状：这三段的语句原本全在 installPhoneChrome 的**同一个** `installMobileEffect`
// 回调里，所以这里导出的是三个**普通函数**（各自返回自己的 disposer），由
// installPhoneChrome 按原顺序调用并把三个 disposer 与 theme-color 的清理合成一个。
// 本模块**不**调 installMobileEffect / ctx.effect：各段自持的 matchMedia 会变成
// 「每次外层重新武装就往 ctx 上再注册三个收不到的 effect」的累积注册，也会把
// 一段确定的回调顺序拆成三个监听器各自的（规范未定义的）触发顺序。

/** Marker the iOS-only zoom-guard CSS is scoped to (html element). */
const IOS_MARKER = 'data-mobile-nav-ios'

/**
 * Viewport content the plugin owns while the mobile branch is armed.
 * Deliberately zoom-free: iOS 10+ ignores maximum-scale/user-scalable for
 * user pinch but other engines honor them, so writing them would only take
 * zoom away from Android/DSHA; the iOS focus-zoom fix is the >=16px field
 * floor (data-mobile-nav-ios), not a zoom ban (#45).
 *
 * Also deliberately free of `interactive-widget=resizes-content`.
 *
 * That hint asks the engine to shrink the LAYOUT viewport when the keyboard
 * opens, which is the model Android WebView (`adjustResize`) already uses and
 * the one every keyboard measurement in this repo was taken in. It was added
 * on 2026-10-18 to stop Chromium browsers from panning instead. Two things
 * went wrong with keeping it:
 *
 *   - It is a Chromium convenience, not a contract. WebKit only started
 *     implementing it in 2026 (w3c/editing#549: "Webkit team is actively
 *     working on supporting resizes-content and overlays-content"), so the
 *     support that exists is brand new.
 *   - Where it is half-implemented the page is compensated twice. Field data
 *     2026-10-30, 402x684 iPhone (CriOS 156): `innerHeight` 684 -> 345 while
 *     `visualViewport.offsetTop` went to 339, carrying the composer from
 *     578..684 to 239..345 — off the visible band `[339, 684]` — before the
 *     pan settled at 246 and it came back to 362..438.
 *
 * The viewport this plugin actually relies on is `visualViewport`, which every
 * mobile engine implements and whose band `[offsetTop, offsetTop + height]` is
 * the single model the composer lift is written against (team-standard recipe,
 * see keyboard-visibility / the composer lift's own derivation). Asking the
 * engine to also resize the layout buys nothing that band does not already
 * describe, and it is what created the double compensation. So: declare no
 * hint, handle both engine models with the band.
 */
const VIEWPORT_CONTENT = 'width=device-width, initial-scale=1, viewport-fit=cover'

/**
 * CSS custom property carrying the viewport height WITHOUT the soft keyboard
 * (px), maintained by `syncStableViewportHeight()` in this module. Mobile
 * cards that must not move when the keyboard appears size themselves with it
 * instead of a viewport unit — see the settings sheet / shortcut card rules in
 * layout.css.ts.
 *
 * 本模块不自起 effect：`syncStableViewportHeight()` 是返回 disposer 的普通函数，
 * 调用方是 phone-chrome 的 status-bar effect（P1 拆分第五片，2026-10-12）。
 */
import { VISIBLE_BAND_EVENT } from '../platform/visible-band.ts'

export const STABLE_VIEWPORT_VAR = '--dsh-web-mobile-vh'

const findViewportMeta = (): HTMLMetaElement | null =>
  document.querySelector<HTMLMetaElement>('meta[name="viewport"]')

/** Viewport meta ownership: re-assert our content on every host rewrite, node
 *  replacement, or late injection while the mobile branch is armed. */
export function preserveViewportMeta(): () => void {
  let originalViewport: string | null = null
  let observedMeta: HTMLMetaElement | null = null
  // Our own write retriggers the observers; the equality check in
  // assertViewport turns that pass into a no-op. `applying` guards the
  // write itself against re-entrant observer callbacks on exotic engines.
  let applying = false

  // The plugin owns the meta while armed, so a host rewrite, a node
  // replacement, or a meta that arrives after this effect arms cannot
  // silently drop viewport-fit=cover and shift every surface under the
  // notch. Both observers funnel into the same assertion;
  // attachMetaObserver re-binds to the current node so a replacement keeps
  // being watched.
  const assertViewport = (): void => {
    const viewport = findViewportMeta()
    if (viewport === null) return
    if (originalViewport === null) originalViewport = viewport.content
    if (applying || viewport.content === VIEWPORT_CONTENT) return
    applying = true
    viewport.content = VIEWPORT_CONTENT
    applying = false
  }
  const metaObserver = new MutationObserver(assertViewport)
  const attachMetaObserver = (): void => {
    const viewport = findViewportMeta()
    if (viewport === observedMeta) return
    if (observedMeta !== null) metaObserver.disconnect()
    observedMeta = viewport
    if (viewport !== null) {
      metaObserver.observe(viewport, { attributes: true, attributeFilter: ['content'] })
    }
  }
  const headObserver = new MutationObserver((): void => {
    attachMetaObserver()
    assertViewport()
  })
  headObserver.observe(document.head, { childList: true })
  attachMetaObserver()
  assertViewport()
  return () => {
    metaObserver.disconnect()
    headObserver.disconnect()
    const viewport = findViewportMeta()
    // Hand the meta back only if it still holds OUR content; a host value
    // written while we were armed wins on dispose.
    if (viewport !== null && originalViewport !== null && viewport.content === VIEWPORT_CONTENT) {
      viewport.content = originalViewport
    }
  }
}

/** The keyboard-less viewport height (STABLE_VIEWPORT_VAR). */
export function syncStableViewportHeight(): () => void {
  const root = document.documentElement
  let stableVh = 0
  let stableWidth = 0

  // Writing a custom property on :root invalidates style for the WHOLE
  // document, so the value is compared before the property is touched: a
  // width change that leaves the height alone (split-screen resize, a
  // rotation that lands back on the same height) used to re-run the document
  // style pass for an identical string. Measured 2026-10-09 on Android 16
  // WebView: the monotonic rule above already suppresses every keyboard
  // driven write (the keyboard only lowers the height, and a lower height
  // never passes the growth test), so no per-frame dedup is needed here —
  // this guard only removes the redundant identical writes.
  let stableValue = ''
  let pendingVisible = ''
  let pendingVisibleFrames = 0
  let pendingVisibleFrame = 0
  // 2026-11-03：发布时机晚于最后一次 resize 事件（要等两帧确认），而读这个变量排版的
  // 效果（提问卡全屏带）只在 resize 时量。没有这个通知，它们最后一次测量量到的是
  // 发布前的旧几何——录屏实测：键盘收起后全屏卡片停在键盘高度不回来。
  const publishVisible = (value: string): void => {
    root.style.setProperty('--dsha-mobile-visible-vh', value)
    document.dispatchEvent(new CustomEvent(VISIBLE_BAND_EVENT))
  }
  const syncStableViewport = (): void => {
    const height = window.innerHeight
    const width = window.innerWidth
    const visible = window.visualViewport
    const available = Math.max(1, Math.min(height, visible?.height ?? height))
    const visibleValue = `${available}px`
    const topValue = `${visible?.offsetTop ?? 0}px`
    // Only publish the band height once it STOPS MOVING (two consecutive frames
    // agree). The shell is sized from this variable, and a custom property on
    // :root invalidates style for the whole document — following the keyboard's
    // 300ms rise frame by frame re-laid-out the entire app ~20 times and the
    // conversation list painted blank (white) for 1-2s on the owner's phone
    // (2026-10-31). Publishing on settle costs one resize, right after the
    // keyboard stops, and the animation itself is left to the compositor.
    // 2026-11-04 店主报「键盘收起/弹起时闪一下」（录屏逐帧：整页跳一帧再回来；二分定位到本处两帧确认）：
    // DSHA 壳不逐帧改 WebView 尺寸，键盘动画的起止各一步跳到终值（WebFullscreenUi 的 IME 内边距）。
    // 只等停稳才发布，外壳就比视口晚 ≥2 帧，中间那帧按旧高度排版 = 闪。所以静止后的**第一次**变化立即发布
    // （一步跳变的引擎这就是终值），动画中的后续帧仍不写，停稳再收拢一次 —— 逐帧动画的引擎每次键盘动画
    // 最多写两次，不回到 2026-10-31 的逐帧重排。
    const atRest = pendingVisibleFrames >= 2
    pendingVisibleFrames = visibleValue === pendingVisible ? pendingVisibleFrames + 1 : 0
    pendingVisible = visibleValue
    if ((atRest || pendingVisibleFrames >= 2) &&
      root.style.getPropertyValue('--dsha-mobile-visible-vh') !== visibleValue)
      publishVisible(visibleValue)
    // The keyboard stops without announcing it: the last resize event still sees
    // a fresh value, so confirmation has to come from a frame of its own. One
    // rAF at a time; during the animation each tick re-arms exactly once.
    // 立即发布之后也要继续确认（不是 else）：否则计数停在 0，下一次键盘动画不算「静止后」，又会晚两帧。
    if (pendingVisibleFrames < 2 && pendingVisibleFrame === 0)
      pendingVisibleFrame = requestAnimationFrame(() => {
        pendingVisibleFrame = 0
        syncStableViewport()
      })
    if (root.style.getPropertyValue('--dsha-mobile-viewport-top') !== topValue)
      root.style.setProperty('--dsha-mobile-viewport-top', topValue)
    if (stableVh === 0 || height > stableVh || width !== stableWidth) {
      stableVh = height
      stableWidth = width
      const next = `${height}px`
      if (next !== stableValue) {
        stableValue = next
        root.style.setProperty(STABLE_VIEWPORT_VAR, next)
      }
    }
  }

  syncStableViewport()
  window.addEventListener('resize', syncStableViewport)
  window.visualViewport?.addEventListener('resize', syncStableViewport)
  window.visualViewport?.addEventListener('scroll', syncStableViewport)

  return () => {
    window.removeEventListener('resize', syncStableViewport)
    window.visualViewport?.removeEventListener('resize', syncStableViewport)
    window.visualViewport?.removeEventListener('scroll', syncStableViewport)
    if (pendingVisibleFrame !== 0) cancelAnimationFrame(pendingVisibleFrame)
    pendingVisibleFrame = 0
    root.style.removeProperty('--dsha-mobile-visible-vh')
    root.style.removeProperty('--dsha-mobile-viewport-top')
    stableValue = ''
    root.style.removeProperty(STABLE_VIEWPORT_VAR)
  }
}

/** iOS-only: mark documentElement so the stylesheet can hold text fields at
 *  >=16px and Safari never focus-zooms the viewport (#45). */
export function applyIosMarker(isIosWebKit: boolean): (() => void) | undefined {
  if (!isIosWebKit) return undefined
  const root = document.documentElement
  root.setAttribute(IOS_MARKER, '')
  return () => {
    root.removeAttribute(IOS_MARKER)
  }
}
