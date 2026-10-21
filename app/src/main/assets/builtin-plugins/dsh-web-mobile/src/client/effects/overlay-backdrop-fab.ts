import type { TranslateNS } from '@deepseek-ai/dsh-client-ui-slots'
import type { ReconcilerTask } from '../core/reconciler-core.ts'
import { NS } from '../i18n/locales.ts'
import type { PanelExit } from './panel-exit.ts'
import { getFrame } from '../platform/frame.ts'

/** Fade the CURRENT backdrop out (pointer-events off + opacity 0). Called by
 * the gesture layer when a close commit starts animating, so the dimming
 * fades WITH the drawer's slide-out instead of vanishing after it. The
 * element itself is removed later by the task's normal remove path (the
 * marker flip schedules it). */
export function fadeOverlayOut(): void {
  fadeHook?.()
}

let fadeHook: (() => void) | null = null

/** Removal is deferred by one fade so the dimming eases out instead of
 * snapping (user request 2026-08-29 「背景黑色遮罩进行渐变动画」; the fade-IN
 * already existed as a mount animation). */
const BACKDROP_FADE_MS = 200

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
export function createOverlayTask(
  t: TranslateNS<typeof NS>,
  toggleSidebar: () => void,
  panelExit: PanelExit | null,
): ReconcilerTask {
  let backdrop: HTMLDivElement | null = null
  let fab: HTMLButtonElement | null = null
  let backdropRemoveTimer: number | null = null
  /** True while the backdrop carries our inline faded state. Guards the
   * restore branch: while a late close commit is animating, the marker is
   * STILL open, so a plain open-branch restore would undo the pre-fade. */
  let faded = false
  const drawerOpen = (): boolean => {
    const frame = getFrame()
    if (frame === null) return false
    // The user's call (2026-09-13): our drawer is the one users get, even on
    // hosts that ship their own overlay drawer. The host's version measures
    // 321px wide with z-index:1100 and, notably, NO full-screen backdrop at all
    // (measured: the conversation stays hit-testable beside it), which is the
    // behaviour the phone owner rejected as unusable. So the legacy column
    // rules stay armed and this backdrop keeps being created.
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
  return {
    name: 'overlay-backdrop-fab',
    scopes: ['*', 'data-sidebar-collapsed', 'data-phase'],
    ensure: () => {
      // Re-armed on every ensure, not once in the factory: core.deactivate()
      // runs dispose() on every MOBILE_QUERY flip (reconciler-core.ts), and
      // reactivation only re-runs ensure() - a hook assigned in the factory
      // body stayed null from the first breakpoint flip onwards, silently
      // killing the backdrop fade.
      fadeHook = (): void => {
        if (backdrop === null) return
        faded = true
        backdrop.style.pointerEvents = 'none'
        backdrop.style.opacity = '0'
      }
      const frame = getFrame()
      if (frame === null) return
      if (drawerOpen()) {
        if (backdrop === null) {
          backdrop = document.createElement('div')
          backdrop.dataset.mobileNav = 'backdrop'
          backdrop.setAttribute('role', 'button')
          backdrop.setAttribute('aria-label', t('backdrop'))
          // No element-level click listener: phone-chrome's capture-phase click
          // handler owns the backdrop tap. The third-party mobile shim stops
          // click propagation at the frame for anything outside the drawer, so
          // a listener down here never sees the tap (2026-09-14).
          frame.appendChild(backdrop)
          faded = false
        } else if (faded && backdropRemoveTimer !== null) {
          // Quick close→reopen inside the fade window: cancel the pending
          // removal and let the CSS transition ease the dimming back in.
          window.clearTimeout(backdropRemoveTimer)
          backdropRemoveTimer = null
          faded = false
          backdrop.style.removeProperty('pointer-events')
          backdrop.style.removeProperty('opacity')
        }
      } else if (backdrop !== null) {
        backdrop.style.pointerEvents = 'none'
        backdrop.style.opacity = '0'
        faded = true
        if (backdropRemoveTimer === null) {
          backdropRemoveTimer = window.setTimeout(() => {
            backdropRemoveTimer = null
            backdrop?.remove()
            backdrop = null
          }, BACKDROP_FADE_MS + 60)
        }
      }
      if (heroPhase() && !drawerOpen() && fab === null) {
        fab = document.createElement('button')
        fab.type = 'button'
        fab.dataset.mobileNav = 'fab'
        fab.setAttribute('aria-label', t('open'))
        fab.title = t('open')
        fab.addEventListener('click', onFabClick)
        frame.appendChild(fab)
      } else if ((!heroPhase() || drawerOpen()) && fab !== null) {
        fab.remove()
        fab = null
      }
      syncFab()
    },
    dispose: () => {
      if (backdropRemoveTimer !== null) {
        window.clearTimeout(backdropRemoveTimer)
        backdropRemoveTimer = null
      }
      fadeHook = null
      backdrop?.remove()
      backdrop = null
      fab?.remove()
      fab = null
    },
  }
}
