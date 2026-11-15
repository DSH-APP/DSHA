// Platform layer: viewport + pointer primitives, no feature semantics.
//
// Moved out of effects/phone-chrome.ts (architecture P1): six other effects
// imported these from phone-chrome, which made it the shared surface of the
// whole effects layer and the only runtime import cycle in src/client.
// Dependency direction is index.tsx → effects → platform → core, and
// effects/ must never reach into another effect for infrastructure.
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'

/** Same width bound as the shell's SIDEBAR_AUTO_COLLAPSE (viewport < 1024),
 *  ANDed with a touch-primary pointer guard. Width alone cannot tell a phone
 *  from a desktop window: split views and OS display scaling push a PC's CSS
 *  viewport below 1024px too, and the whole mobile shell (drawer, header
 *  Files button, gestures) would mount there. (pointer: coarse) keeps the
 *  adaptation on touch-primary devices — phones, tablets, DSHA — while any
 *  mouse-driven window stays desktop at every width. Headless probes have no
 *  pointer at all: arm the mobile branch with Emulation.setTouchEmulation-
 *  Enabled before asserting mobile UI. */
export const MOBILE_QUERY = '(max-width: 1023px) and (pointer: coarse)'

/** Informational wide-bound for the debug badge. The authoritative desktop
 *  guard is the CSS hide block in misc.css.ts — the exact complement of
 *  MOBILE_QUERY — because slot-rendered controls exist at every width. */
export const DESKTOP_QUERY = '(min-width: 1024px)'

/** Pointer-only guard for the ONE feature that has no desktop equivalent:
 *  the session-delete menu injection. Armed on touch-primary devices at
 *  EVERY width — a large tablet in landscape (e.g. 1238px) keeps the desktop
 *  layout but still gets the 「删除会话」 item. Mouse-driven or pointer-less
 *  windows never arm it, at any width. */
export const TOUCH_QUERY = '(pointer: coarse)'

/** 宽屏触屏（平板 ≥1024 + 粗指针）：宿主原生布局照旧，本插件只补「触屏缺 hover /
 *  触屏热区」这一类补丁（2026-10-29 owner 定的方向：平板走原生，不做手机壳）。
 *  与 MOBILE_QUERY 互补但不重叠；桌面（pointer: fine）任何宽度都不命中。 */
export const TABLET_TOUCH_QUERY = '(min-width: 1024px) and (pointer: coarse)'

/**
 * Re-arm a mobile-only DOM effect on every query change. Replaces the
 * repeated matchMedia + change-listener scaffold so all breakpoint strings
 * live in one place. `query` defaults to MOBILE_QUERY; effects that arm on a
 * different condition (e.g. TOUCH_QUERY) pass their own string instead of
 * building a private matchMedia scaffold.
 */
export function installMobileEffect(
  ctx: ClientContext,
  label: string,
  install: (narrow: MediaQueryList) => (() => void) | undefined,
  query: string = MOBILE_QUERY,
): void {
  ctx.effect(() => {
    const narrow = window.matchMedia(query)
    let cleanup: (() => void) | undefined
    const arm = (): void => {
      cleanup?.()
      cleanup = narrow.matches ? install(narrow) : undefined
    }
    arm()
    narrow.addEventListener('change', arm)
    return () => {
      narrow.removeEventListener('change', arm)
      cleanup?.()
    }
  }, label)
}
