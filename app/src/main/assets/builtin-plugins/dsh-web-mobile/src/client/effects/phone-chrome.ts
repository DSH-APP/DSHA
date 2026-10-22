import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { installMobileEffect } from '../platform/breakpoints.ts'
import { detectIosWebKit } from '../platform/device.ts'
import { findFrame } from '../platform/frame.ts'
import { createHeaderMetricsTask } from './header-metrics.ts'
import { addReconcilerTask } from './reconciler-adapter.ts'
import { applyIosMarker, preserveViewportMeta, syncStableViewportHeight } from './viewport-meta.ts'

/** The third-party mobile compat shim shipped inside `@linxin666/dsh-web-all`
 *  collapses the drawer on ANY click inside `[role="treeitem"]` at ≤768px by
 *  clicking the host's logo-row toggle — with no `_rowActions` exemption, so a
 *  tap on a row's ⋯ closed the drawer instead of opening its menu (2026-09-14;
 *  its sibling implementation inside `@linxin666/dsh-remote-web-ui` does exempt
 *  the row actions). It resolves that toggle with
 *  `frame.querySelector('[data-dsh-responsive-part="sidebar-toggle"]')`, so an
 *  inert element carrying the same stamp EARLIER in tree order turns every one
 *  of its dismiss calls into a no-op and leaves dismiss ownership to us (row
 *  taps close through the navigation observer, backdrop taps through the
 *  capture click path). Gated on the shim's own stamp: hosts without it stay
 *  untouched. */
const HOST_TOGGLE_SELECTOR = '[data-dsh-responsive-part="sidebar-toggle"]:not([data-mobile-nav])'
const DISMISS_SHADOW_SELECTOR = '[data-mobile-nav="dismiss-shadow"]'

export function ensureDismissShadow(): void {
  if (typeof document === 'undefined') return
  const shadow = document.querySelector<HTMLElement>(DISMISS_SHADOW_SELECTOR)
  const real = document.querySelector<HTMLElement>(HOST_TOGGLE_SELECTOR)
  const pane = real?.closest<HTMLElement>('[data-pane="sidebar"]') ?? null
  if (real === null || pane === null) {
    shadow?.remove()
    return
  }
  // Must stay a no-op once in place: the reconciler observes the whole tree and
  // a task that mutates on every flush would re-trigger itself forever.
  if (shadow !== null && shadow.parentElement === pane && pane.firstElementChild === shadow) return
  const element = shadow ?? document.createElement('span')
  if (shadow === null) {
    element.setAttribute('data-mobile-nav', 'dismiss-shadow')
    element.setAttribute('data-dsh-responsive-part', 'sidebar-toggle')
    element.setAttribute('aria-hidden', 'true')
    // The shim's collapsed-rail rule forces `display: inline-flex !important`
    // on anything carrying the stamp; only an inline !important outranks it.
    element.style.setProperty('display', 'none', 'important')
  }
  pane.insertBefore(element, pane.firstElementChild)
}


/**
 * Frame marker controller: owns `data-mobile-nav="frame"` and every plugin
 * marker that can survive on the shell-owned frame. Installed once at apply
 * time so effects no longer each need to find/set/clear the frame. Returns a
 * disposer that unregisters the task and resets the installed flag, so a
 * same-environment plugin reload can rebuild the reconciler from scratch.
 * (The host-generation probe this controller used to call was dead code —
 * nothing ever read `data-mobile-nav-gen`, and the plugin deliberately does
 * not yield the drawer to the host's one: see docs/maintenance/pitfalls.md
 * §0.1.5 抽屉 z 与遮罩.)
 */
export function installFrameController(): () => void {
  if (frameControllerInstalled) return () => {}
  frameControllerInstalled = true
  let frame: HTMLElement | null = null
  const removeTask = addReconcilerTask({
    name: 'frame-marker',
    scopes: ['*'],
    ensure: () => {
      frame = findFrame()
      if (frame !== null && !frame.hasAttribute('data-mobile-nav')) {
        frame.setAttribute('data-mobile-nav', 'frame')
      }
      ensureDismissShadow()
    },
    dispose: () => {
      if (frame !== null) {
        frame.removeAttribute('data-mobile-nav')
        frame.removeAttribute('data-mobile-preview-full')
        frame.removeAttribute('data-aionui-explorer-open')
        frame.removeAttribute('data-aionui-preview-open')
      }
      if (typeof document !== 'undefined') {
        document.querySelector(DISMISS_SHADOW_SELECTOR)?.remove()
      }
      frame = null
    },
  })
  const removeHeaderMetrics = addReconcilerTask(createHeaderMetricsTask())
  return () => {
    removeHeaderMetrics()
    removeTask()
    frameControllerInstalled = false
  }
}

let frameControllerInstalled = false


/**
 * Phone chrome: KEEP the system status bar (no fullscreen) and make it
 * blend into the page. On narrow screens:
 * - The viewport meta is OWNED by the plugin while armed (viewport-meta.ts):
 *   width=device-width, initial-scale=1, viewport-fit=cover,
 *   interactive-widget=resizes-content (keyboard shrinks the layout viewport
 *   on Chromium instead of panning the page), re-asserted on every host
 *   rewrite, node replacement, or late injection, so
 *   env(safe-area-inset-top) stays the real status-bar / notch height
 *   instead of silently going stale when the host touches the meta. No zoom
 *   tokens here: iOS 10+ ignores them for user pinch but other engines
 *   honor them, and the focus-zoom fix is the >=16px field floor (#45), not
 *   a zoom ban. Dispose restores the host's own content as observed at arm
 *   time.
 * - A theme-color meta tracks the shell background (the official theme is
 *   toggled by body[data-ds-dark-theme], which flips --dsw-alias-bg-base):
 *   Android then paints the status bar / URL bar with the page's own base
 *   color, so the status bar reads as part of the UI instead of a foreign
 *   strip. The drawer paints the same strip on iOS / notch displays.
 * - documentElement carries data-mobile-nav-ios on iOS WebKit so the
 *   stylesheet can hold every text field at >=16px and Safari never
 *   focus-zooms the viewport (#45). Double-tap zoom is off through
 *   touch-action; pinch zoom stays available on purpose — it is the only way
 *   back out of a zoom the browser applied on its own.
 */
export function installPhoneChrome(ctx: ClientContext): void {
  installMobileEffect(ctx, 'dsh-web-mobile: status bar theme + viewport + zoom guard', () => {
    const themeMeta = document.createElement('meta')
    themeMeta.name = 'theme-color'
    const bodyBg = (): string => getComputedStyle(document.body).backgroundColor
    const cssSupports =
      typeof CSS !== 'undefined' && typeof CSS.supports === 'function'
        ? (condition: string): boolean => CSS.supports(condition)
        : null
    themeMeta.content = bodyBg()
    if (themeMeta.parentElement === null) document.head.appendChild(themeMeta)
    const observer = new MutationObserver(() => {
      themeMeta.content = bodyBg()
    })
    observer.observe(document.body, { attributes: true, attributeFilter: ['data-ds-dark-theme'] })

    // 武装顺序照 876 行原始版：viewport meta → iOS 标记 → 稳定视口高度。
    // 这个次序不是排版偏好：viewport meta 的所有权会改视口，而稳定视口高度读的是
    // window.innerHeight，所以「先认领 meta、最后读高度」才是原行为。
    const stopViewportMeta = preserveViewportMeta()
    const stopIosMarker = applyIosMarker(detectIosWebKit(navigator, cssSupports))
    const stopStableViewport = syncStableViewportHeight()

    // 清理顺序同样照原版——它**不是**武装顺序的逆序，而是三块与 theme-color 清理
    // 交错的固定序：稳定视口 → observer → 恢复 viewport meta → 摘 themeMeta →
    // 摘 iOS 标记。故逐位写，不写成 for 循环。
    return () => {
      stopStableViewport()
      observer.disconnect()
      stopViewportMeta()
      themeMeta.remove()
      stopIosMarker?.()
    }
  })
}
