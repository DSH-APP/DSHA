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
 * `interactive-widget=resizes-content`（2026-10-18 补）：手机上「键盘弹起时整页被
 * 顶上去、顶栏出屏」不是本插件的布局 bug，而是 Chrome for Android 的键盘视口模型——
 * 该 flag 缺省即 `resizes-visual`，只缩**视觉**视口，布局视口（`100%` / `100dvh`
 * 跟着的那个）仍是整屏高，浏览器为了露出被聚焦的输入框把整页往上平移。本仓所有
 * 键盘测量都是在 Android WebView（`adjustResize`，键盘缩**布局**视口）上取的，那台
 * 引擎没有这个问题；显式要 `resizes-content` 就是让 Chromium 浏览器回到同一模型：
 * `100dvh` / `innerHeight` / frame 全都等于可见区，页面不再平移。不认这个值的引擎
 * （iOS Safari）会忽略它，行为与改动前一致——iOS 的键盘模型仍由
 * composer-keyboard-lift.ts 兜。
 */
const VIEWPORT_CONTENT =
  'width=device-width, initial-scale=1, viewport-fit=cover, interactive-widget=resizes-content'

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
  const syncStableViewport = (): void => {
    const height = window.innerHeight
    const width = window.innerWidth
    const visible = window.visualViewport
    const available = Math.max(1, Math.min(height, visible?.height ?? height))
    const visibleValue = `${available}px`
    const topValue = `${visible?.offsetTop ?? 0}px`
    if (root.style.getPropertyValue('--dsha-mobile-visible-vh') !== visibleValue)
      root.style.setProperty('--dsha-mobile-visible-vh', visibleValue)
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
