// The drawer's layer surface, owned by one reconciler task: the floating
// fallback button (FAB), the persistent full-screen hit layer that closes the
// drawer on a blank tap, and the dim marker (`data-mobile-nav-dim`). It does
// NOT paint the dim — that lives on the frame's ::after, because a painted
// full-screen element is what made the device flash bright on close
// (2026-11-08, device-only; see base.css.ts). Task name: `drawer-layer`.
import type { TranslateNS } from '@deepseek-ai/dsh-client-ui-slots'
import type { ReconcilerTask } from '../core/reconciler-core.ts'
import { NS } from '../i18n/locales.ts'
import type { PanelExit } from './panel-exit.ts'
import { getFrame } from '../platform/frame.ts'
import { onDrawerOpening } from '../platform/drawer-open.ts'

/** Start the dim's fade-out and disable the hit target early. Called by the
 * gesture layer when a close commit starts animating, so the dimming fades
 * WITH the drawer's slide-out instead of vanishing after it. The dim itself is
 * painted by the frame's ::after (data-mobile-nav-dim); the catcher element is
 * persistent and only re-arms when the marker flips. */
export function fadeDimOut(): void {
  fadeHook?.()
}

let fadeHook: (() => void) | null = null

/** The dim's opacity transition, shared with the drawer column's .28s slide
 * (user request 2026-08-29 「背景黑色遮罩进行渐变动画」). */
const DIM_FADE_MS = 280

/** How long the presence-gated band outlives the marker flip: one fade plus
 * the 60ms slack the old removal timer carried. The slack is load-bearing for
 * the 快捷键弹层 z band (2026-09-25): the raised modal root must outlive the
 * host's pane-subtree swap, measured at ~200-280ms after the flip. */
const BAND_RELEASE_MS = DIM_FADE_MS + 60

/** How long an optimistically started dim (see showNow) waits for the host's
 * open commit before the closed-state fade is allowed to take it back. */
const OPEN_CONFIRM_MS = 120

/** The FAB's second face: an arrow, shown while a sidebar panel owns the main
 * area — both the subpage 「返回」 and the panel-root 「返回会话」 modes. */
const FAB_BACK_ICON =
  '<svg viewBox="0 0 16 16" fill="none" aria-hidden="true" width="18" height="18">' +
  '<path d="M9.8 3.4 5.2 8l4.6 4.6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>' +
  '</svg>'

const FAB_DRAWER_ICON =
  '<svg viewBox="0 0 16 16" fill="none" aria-hidden="true" width="18" height="18">' +
  '<path fill-rule="evenodd" clip-rule="evenodd" d="M9.67272 0.522841C10.8339 0.522841 11.76 0.522714 12.4963 0.602493C13.2453 0.683657 13.8789 0.854248 14.4264 1.25197C14.7504 1.48739 15.0355 1.77247 15.2709 2.0965C15.6686 2.64394 15.8392 3.27758 15.9204 4.02655C16.0002 4.7629 16 5.68895 16 6.85014V9.14986C16 10.3111 16.0002 11.2371 15.9204 11.9735C15.8392 12.7224 15.6686 13.3561 15.2709 13.9035C15.0355 14.2275 14.7504 14.5126 14.4264 14.748C13.8789 15.1458 13.2453 15.3163 12.4963 15.3975C11.76 15.4773 10.8339 15.4772 9.67272 15.4772H6.3273C5.16611 15.4772 4.24006 15.4773 3.50371 15.3975C2.75474 15.3163 2.1211 15.1458 1.57366 14.748C1.24963 14.5126 0.964549 14.2275 0.729131 13.9035C0.331407 13.3561 0.160817 12.7224 0.0796529 11.9735C-0.000126137 11.2371 1.25338e-09 10.3111 1.25338e-09 9.14986V6.85014C1.25329e-09 5.68895 -0.000126137 4.7629 0.0796529 4.02655C0.160817 3.27758 0.331407 2.64394 0.729131 2.0965C0.964549 1.77247 1.24963 1.48739 1.57366 1.25197C2.1211 0.854248 2.75474 0.683657 3.50371 0.602493C4.24006 0.522714 5.16611 0.522841 6.3273 0.522841H9.67272ZM5.54303 1.88715V14.1118C5.78636 14.1128 6.04709 14.1169 6.3273 14.1169H9.67272C10.8639 14.1169 11.7032 14.1164 12.3493 14.0465C12.9824 13.9779 13.3497 13.8494 13.6268 13.6482C13.8354 13.4966 14.0195 13.3125 14.1711 13.1039C14.3723 12.8268 14.5007 12.4595 14.5693 11.8264C14.6393 11.1803 14.6398 10.341 14.6398 9.14986V6.85014C14.6398 5.65896 14.6393 4.81967 14.5693 4.1736C14.5007 3.54048 14.3723 3.17318 14.1711 2.89609C14.0195 2.68747 13.8354 2.50337 13.6268 2.35179C13.3497 2.1506 12.9824 2.02212 12.3493 1.95353C11.7032 1.88358 10.8639 1.88307 9.67272 1.88307H6.3273C6.04709 1.88307 5.78636 1.8862 5.54303 1.88715ZM4.1828 1.91166C3.99125 1.9216 3.8148 1.93577 3.65076 1.95353C3.01764 2.02212 2.65034 2.1506 2.37325 2.35179C2.16463 2.50337 1.98052 2.68747 1.82895 2.89609C1.62776 3.17318 1.49928 3.54048 1.43069 4.1736C1.36074 4.81967 1.36023 5.65896 1.36023 6.85014V9.14986C1.36023 10.341 1.36074 11.1803 1.43069 11.8264C1.49928 12.4595 1.62776 12.8268 1.82895 13.1039C1.98052 13.3125 2.16463 13.4966 2.37325 13.6482C2.65034 13.8494 3.01764 13.9779 3.65076 14.0465C4.29683 14.1164 5.13612 14.1169 6.3273 14.1169H9.67272C10.8639 14.1169 11.7032 14.1164 12.3493 14.0465C12.9824 13.9779 13.3497 13.8494 13.6268 13.6482C13.8354 13.4966 14.0195 13.3125 14.1711 13.1039C14.3723 12.8268 14.5007 12.4595 14.5693 11.8264C14.6393 11.1803 14.6398 10.341 14.6398 9.14986V6.85014C14.6398 5.65896 14.6393 4.81967 14.5693 4.1736C14.5007 3.54048 14.3723 3.17318 14.1711 2.89609C14.0195 2.68747 13.8354 2.50337 13.6268 2.35179C13.3497 2.1506 12.9824 2.02212 12.3493 1.95353C11.7032 1.88358 10.8639 1.88307 9.67272 1.88307H6.3273C5.13612 1.88307 4.29683 1.88358 3.65076 1.95353C3.47672 1.97129 3.30027 1.98546 3.10872 1.9954L4.1828 1.91166Z" fill="currentColor"/>' +
  '</svg>'

/**
 * @param t - our dictionary (the `NS` locale namespace, i18n/locales.ts).
 * @param toggleSidebar - opens/closes the drawer.
 * @param panelExit - the sidebar-panel exit face (panel-exit.ts). The FAB is the
 *   screen's only full-size control while a panel owns the main area — the
 *   header toggle does not render there — so it also steps back: one host view
 *   level on a panel subpage, out of the panel at its root. Null on a host that
 *   cannot select panels (rc.6), where it stays a plain drawer button.
 */
export function createDrawerLayerTask(
  t: TranslateNS<typeof NS>,
  toggleSidebar: () => void,
  panelExit: PanelExit | null,
): ReconcilerTask {
  let fab: HTMLButtonElement | null = null
  /**
   * Persistent click catcher (`hitLayer`). It paints nothing and carries no interactive
   * role/aria on purpose: the dim is painted by the frame's own ::after (same
   * paint tree as the conversation, no separate full-screen layer). When this
   * element carried role="button", the device browser treated the full-screen
   * surface as a pressed button — the "tap the blank area → the right side
   * flashes bright" report (2026-11-08, device-only).
   */
  let hitLayer: HTMLDivElement | null = null
  /** Pending confirm of an optimistic dim (showNow); while armed the closed
   * branch leaves the dim alone — the host commit may simply not have landed. */
  let openConfirmTimer: number | null = null
  /** Releases [data-active] after the close fade so presence-gated CSS never
   * flips in the same recalculation that starts the dim change. */
  let activeReleaseTimer: number | null = null
  /** True while a close commit has pre-faded the dim but the marker is still
   * open (late commit). The open branch must not re-light the dim in that
   * window: a tree mutation from anywhere in the document runs this task
   * (`scopes` carries the tree key), and restoring there would undo the
   * pre-fade and hand the screen a second dark step — the bug the removed
   * mount-based guard used to prevent (2026-11-08 review). Cleared by the
   * marker flip. */
  let dimHeldOff = false
  let removeOpeningHook: (() => void) | null = null

  const ensureHitLayer = (frame: HTMLElement): HTMLDivElement => {
    if (hitLayer === null) {
      hitLayer = document.createElement('div')
      hitLayer.dataset.mobileNav = 'hit'
      hitLayer.setAttribute('aria-hidden', 'true')
      hitLayer.style.pointerEvents = 'none'
      frame.appendChild(hitLayer)
    }
    return hitLayer
  }

  const setDim = (frame: HTMLElement, on: boolean): void => {
    frame.setAttribute('data-mobile-nav-dim', on ? '1' : '0')
  }

  /** Cancel the optimistic-open confirm window (armed by showNow). */
  const cancelOpenConfirm = (): void => {
    if (openConfirmTimer === null) return
    window.clearTimeout(openConfirmTimer)
    openConfirmTimer = null
  }

  /** Cancel the pending band release ([data-active] removal). */
  const cancelActiveRelease = (): void => {
    if (activeReleaseTimer === null) return
    window.clearTimeout(activeReleaseTimer)
    activeReleaseTimer = null
  }

  const releaseActiveAfter = (ms: number): void => {
    cancelActiveRelease()
    activeReleaseTimer = window.setTimeout(() => {
      activeReleaseTimer = null
      delete hitLayer?.dataset.active
    }, ms)
  }
  const drawerOpen = (): boolean => {
    const frame = getFrame()
    if (frame === null) return false
    // The user's call (2026-09-13): our drawer is the one users get, even on
    // hosts that ship their own overlay drawer. The host's version measures
    // 321px wide with z-index:1100 and, notably, NO full-screen backdrop at all
    // (measured: the conversation stays hit-testable beside it), which is the
    // behaviour the phone owner rejected as unusable. So the legacy column
    // rules stay armed and the drawer keeps our own dim and hit target.
    return !frame.hasAttribute('data-sidebar-collapsed')
  }
  const heroPhase = (): boolean =>
    document.querySelector('[data-phase="active"]') === null
  /**
   * The FAB does one job per view. While a sidebar panel owns the main area it
   * steps back through the panel and everywhere else it opens the drawer.
   *
   * It matters because the FAB is the ONLY full-size control on screen there:
   * the panel replaces the conversation, so the header (and with it the drawer
   * toggle) does not render. A panel subpage does ship the host's own
   * 「返回上一级」 control (moreBack / crumb), but it sits in the same top-left
   * corner and is geometrically covered by the FAB — so the FAB has to be the
   * one that climbs a level.
   */
  const onFabClick = (event: MouseEvent): void => {
    if (panelExit !== null && panelExit.panelOpen()) {
      event.preventDefault()
      event.stopPropagation()
      // One level, not the whole panel: on a plugin subpage this clicks the
      // host's own 「返回上一级」 (see back in panel-exit.ts).
      panelExit.back()
      return
    }
    toggleSidebar()
  }
  /**
   * Icon and accessible name follow the view so the button never reads as a
   * mystery control. Three states, two icons: open-drawer (no panel),
   * back-level (panel subpage — same arrow, named 「返回」, because it climbs a
   * host view level and does not leave the panel) and exit-panel (panel root —
   * named 「返回会话」). Idempotent: ensure() runs on every mutation burst.
   */
  const syncFab = (): void => {
    if (fab === null) return
    const onPanel = panelExit !== null && panelExit.panelOpen()
    const onSubpage = onPanel && panelExit !== null && panelExit.subpageOpen()
    const mode = onSubpage ? 'back-level' : onPanel ? 'exit-panel' : 'open-drawer'
    if (fab.dataset.mobileNavFabMode === mode) return
    fab.dataset.mobileNavFabMode = mode
    const label = onSubpage ? t('back') : onPanel ? t('backToConversation') : t('open')
    fab.setAttribute('aria-label', label)
    fab.title = label
    fab.innerHTML = onPanel ? FAB_BACK_ICON : FAB_DRAWER_ICON
  }
  /**
   * Opening hook (platform/drawer-open.ts, drawer-jank R3): every opener calls
   * it right before flipping the host, so the dim starts on the drawer's first
   * frame. Unlike the old optimistic mount this only flips the frame marker —
   * no element is inserted and no full-screen layer is created on the open
   * frame (that insertion was the device flash). The next ensure() stays the
   * source of truth and fades the dim back if the open never landed.
   */
  const showNow = (): void => {
    const frame = getFrame()
    if (frame === null || !frame.hasAttribute('data-sidebar-collapsed')) return
    // A fresh open cancels any pending pre-fade from a close still in flight.
    dimHeldOff = false
    ensureHitLayer(frame)
    setDim(frame, true)
    cancelOpenConfirm()
    openConfirmTimer = window.setTimeout(() => {
      openConfirmTimer = null
      sync()
    }, OPEN_CONFIRM_MS)
  }
  const sync = (): void => {
    // Re-armed on every ensure, not once in the factory: core.deactivate()
    // runs dispose() on every MOBILE_QUERY flip (reconciler-core.ts), and
    // reactivation only re-runs ensure() - a hook assigned in the factory
    // body stayed null from the first breakpoint flip onwards, silently
    // killing the dim fade.
    fadeHook = (): void => {
      const frame = getFrame()
      dimHeldOff = true
      if (frame !== null) setDim(frame, false)
      if (hitLayer !== null) hitLayer.style.pointerEvents = 'none'
      releaseActiveAfter(BAND_RELEASE_MS)
    }
    removeOpeningHook ??= onDrawerOpening(showNow)
    const frame = getFrame()
    if (frame === null) return
    const hit = ensureHitLayer(frame)
    if (drawerOpen()) {
      cancelOpenConfirm()
      // A close commit may still be animating (marker not flipped yet): the
      // pre-fade owns the dim until that flip, so restoring here is a no-op.
      if (!dimHeldOff) {
        cancelActiveRelease()
        setDim(frame, true)
        hit.dataset.active = ''
        hit.style.pointerEvents = 'auto'
      }
    } else if (openConfirmTimer === null) {
      // Fade the dim out (geometry-static opacity transition on the frame's
      // ::after) and release the presence-gated band once it has landed.
      dimHeldOff = false
      setDim(frame, false)
      if (hit.style.pointerEvents !== 'none') {
        hit.style.pointerEvents = 'none'
        releaseActiveAfter(BAND_RELEASE_MS)
      }
    }
    /**
     * 插件二级页（面板子页）不挂悬浮键（owner 2026-11-05：真机上它压在最上面，
     * 与宿主自己的「‹ 插件」返回条重复，要求去掉）。一级面板仍留着它当「返回会话」，
     * 会话页则是抽屉入口。
     */
    const onSubpage = panelExit !== null && panelExit.subpageOpen()
    if (heroPhase() && !drawerOpen() && !onSubpage && fab === null) {
      fab = document.createElement('button')
      fab.type = 'button'
      fab.dataset.mobileNav = 'fab'
      fab.setAttribute('aria-label', t('open'))
      fab.title = t('open')
      fab.addEventListener('click', onFabClick)
      frame.appendChild(fab)
    } else if ((!heroPhase() || drawerOpen() || onSubpage) && fab !== null) {
      fab.remove()
      fab = null
    }
    syncFab()
  }
  return {
    name: 'drawer-layer',
    scopes: ['*', 'data-sidebar-collapsed', 'data-phase'],
    ensure: sync,
    dispose: () => {
      cancelOpenConfirm()
      cancelActiveRelease()
      removeOpeningHook?.()
      removeOpeningHook = null
      fadeHook = null
      getFrame()?.removeAttribute('data-mobile-nav-dim')
      hitLayer?.remove()
      hitLayer = null
      fab?.remove()
      fab = null
    },
  }
}
