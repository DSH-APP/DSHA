// Drawer-close interactions, moved verbatim out of effects/phone-chrome.ts
// (architecture P1 split, 2026-10-12): Escape + navigation-tap closing and the
// one shared drawer toggle every non-gesture closer routes through. The bodies
// below are a pure move - do not reflow or reword them.
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { installMobileEffect } from '../platform/breakpoints.ts'
import { getDrawer, getFrame } from '../platform/frame.ts'
import { drawerOpening, openDrawerAfterKeyboard } from '../platform/drawer-open.ts'
import { consumeIfGestured, isStrokeLocked } from './gesture-guard.ts'
import { isTapWithinSlop } from './session-row-fiber.ts'
import { closeDrawerAnimated } from './sidebar-swipe.ts'

/** Finger-down to finger-up travel that still counts as a tap on a session row
 *  (the DSHA click replacement below). Per-axis (`isTapWithinSlop` is max-norm, not Euclidean): the drawer
 *  list scrolls vertically, so a 60px vertical drift must not navigate while a
 *  diagonal wobble still reads as a tap. */
const TAP_NAV_SLOP_PX = 12

/** Where the current touch started (null for a mouse, and between touches).
 *  The DSHA click replacement only fires when the finger stayed put, so every
 *  touch pointerdown records this BEFORE any early return. Cleared by the
 *  effect's disposer. */
let touchDownAt: { x: number; y: number } | null = null

/**
 * Drawer close interactions that are plain event listeners, not DOM
 * reconciliation:
 * - Escape closes the drawer (yielding to any open modal dialog, which owns
 *   its own Escape handling).
 * - Tapping a navigation target inside the drawer (session row, sidebar panel
 *   row, task board / ssh takeover entries, search results) closes the drawer
 *   so the content it opened gets the whole screen. Session-row action buttons
 *   (kebab) are excluded — they open a menu that must survive the tap.
 *
 * The touch close always rides the synthesized click. Closing a non-row
 * target from pointerup collapsed the drawer before that click existed, and
 * a collapsed drawer no longer owns the touch point, so the browser
 * dispatched no click at all and the target's own onClick never ran (「新会话」
 * did nothing but retract the drawer, 2026-09-13).
 */
export const TAP_CLOSE_NAV_SELECTOR =
  'button[data-dsh-taskboard-entry], button[data-dsh-ssh-entry], [class*="newSession"], [class*="sessionRow"], [class*="searchResultRow"], [class*="searchResultWorkspace"], [class*="panelRow"]'

/**
 * The one drawer toggle every non-gesture entry point shares: a CLOSE animates
 * into the closed slot and flips the host marker only once it has landed
 * (closeDrawerAnimated's late commit — spec 2026-08-27), an OPEN stays a plain
 * toggle so the host's own .28s transform transition plays.
 *
 * Load-bearing for layering, not just for looks (2026-09-25): the popover
 * band's modal-root raise is gated on our backdrop being on screen, and the
 * backdrop outlives the marker flip by design (fade .2s + removal 260ms). A
 * closer that flips the marker while the column is still painted therefore
 * leaves an open modal under the drawer band for the length of the
 * transition — that is the 快捷键弹层「抽搐/闪」 root cause. Routing every
 * closer through here removes the window at the source instead of relying on
 * the band to cover it.
 *
 * An OPEN (frame still collapsed) goes through openDrawerAfterKeyboard: a
 * focused composer is blurred first and the drawer opens once the soft keyboard
 * has gone, so the host's own focus move into the drawer cannot close the
 * keyboard mid-slide (2026-11-05 drawer-jank report, R2). Every tap opener —
 * the header toggle (index.tsx) and the FAB — routes through here. The opening
 * hook runs right before the flip so the backdrop joins the same commit (R3).
 */
export function toggleDrawer(ctx: ClientContext): void {
  if (closeDrawerAnimated(ctx)) return
  const frame = getFrame()
  if (frame !== null && frame.hasAttribute('data-sidebar-collapsed')) {
    openDrawerAfterKeyboard(() => {
      drawerOpening() // backdrop lands in the same task as the host commit (R3)
      ctx.layout.toggleSidebar()
    })
    return
  }
  ctx.layout.toggleSidebar()
}

export function installOverlayInteractions(ctx: ClientContext): void {
  installMobileEffect(ctx, 'dsh-web-mobile: drawer close (Escape + navigate)', () => {
    // Every non-gesture close funnels through here (backdrop tap, Escape, the
    // nav observers, navigation taps). A close animates first - the host tears
    // the pane's subtree and surface at the marker flip, so the slide has to
    // land before it (closeDrawerAnimated) - while opening stays a plain toggle
    // so the host's own .28s transform transition plays.
    const toggleSidebar = (): void => {
      toggleDrawer(ctx)
    }
    const drawerOpen = (): boolean => {
      const frame = getFrame()
      return frame !== null && !frame.hasAttribute('data-sidebar-collapsed')
    }
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key !== 'Escape') return
      if (document.querySelector('[aria-modal="true"]') !== null) return
      if (drawerOpen()) toggleSidebar()
    }
    // Capture phase: run before the shell or a plugin processes the click,
    // so takeover panels never render under the open drawer.
    const drawerRoot = (): HTMLElement | null => getDrawer()

    // Shared frame: inside the drawer, on a row navigation target, and not on
    // one of its buttons. Deliberately free of the DSHA tap-close exemption —
    // onDrawerPointerDown arms long-press through this base, and starving that
    // arming would make the host's ⋯ row menu unreachable (#82).
    const isDrawerNavTarget = (target: EventTarget | null): boolean => {
      if (document.querySelector('[aria-modal="true"]') !== null) return false
      if (!drawerOpen()) return false
      if (!(target instanceof Element)) return false
      const drawer = drawerRoot()
      if (drawer === null || !drawer.contains(target)) return false
      if (target.closest('[class*="sessionRow"] button') !== null) return false
      // 「更多」这类面板行按钮是菜单锚点（aria-haspopup=menu，菜单走 portal）。
      // 它们命中 panelRow 选择器，但点它只该开菜单：收抽屉会把 portal 菜单留在
      // 会话上（2026-10-25 真机：侧边栏「更多」的菜单浮在聊天区）。
      if (target.closest('button[class*="panelRow"][aria-haspopup="menu"]') !== null) return false
      return target.closest(TAP_CLOSE_NAV_SELECTOR) !== null
    }
    // DSHA_SESSION_INTERACTION_V1：宿主把「单击=选中、双击=打开」拆成了两步
    // （data-dsha-session-select 标记 + dsha-session-open 事件）。上游的
    // 「点行即关抽屉」会在第一次单击就把抽屉收掉，双击永远到不了。
    // 这些行改由 dsha-session-open 事件关闭（见下方 document 监听）。
    // 非 DSHA 宿主没有这个标记，这一条天然不命中。
    // 豁免只属于点行关抽屉的两个调用方（click / pointerup），不得回流进
    // 长按武装门，否则 DSHA 行长按开不了 ⋯ 菜单（#82）。
    const shouldCloseOnTapInsideDrawer = (target: EventTarget | null): boolean =>
      !(target instanceof Element && target.closest('[data-dsha-session-select]') !== null)
      && isDrawerNavTarget(target)
    // Touch path for session/search rows: never close the drawer from pointer
    // events. Closing at pointerup (or deferring the close) races the browser's
    // synthesized click; some iOS shells suppress that click entirely, so the
    // row's onClick never runs. Instead arm the drawer to close on the *fact*
    // of navigation: when the selected row's title changes, React has already
    // opened the conversation, so the drawer can close safely.
    let lastTouchNavAt = 0
    let navSignatureAtArm = ''
    let navObserver: MutationObserver | null = null
    let navTimer: number | null = null

    /** Swallow the host's title double-click rename: on touch a double *tap*
     *  means "open the session" (DSHA's own two-tap detection sits on the row's
     *  click handler), and letting the browser's `dblclick` reach the title would
     *  open the host's rename dialog on that same gesture. Capture phase on
     *  `document`, so the event never reaches React's root container. Only real
     *  (trusted) events are swallowed — nothing here dispatches a synthetic
     *  dblclick since the long-press rename was dropped (2026-10-07). Armed only
     *  inside the mobile environment (this effect is MOBILE_QUERY-gated), so
     *  mouse-driven desktops keep the host behaviour untouched. */
    const onDrawerDoubleClick = (event: MouseEvent): void => {
      if (!event.isTrusted) return
      const target = event.target
      if (!(target instanceof Element)) return
      if (target.closest('[class*="sessionRow"] [class*="_title"]') === null) return
      event.preventDefault()
      event.stopPropagation()
    }

    const selectedRowSignature = (): string | null => {
      const selected = drawerRoot()?.querySelector<HTMLElement>('[role="treeitem"][aria-selected="true"]')
      const title = selected?.querySelector<HTMLElement>('[class*="_title"]')
      return title?.textContent?.trim() ?? null
    }

    const disarmNav = (): void => {
      navObserver?.disconnect()
      navObserver = null
      if (navTimer !== null) window.clearTimeout(navTimer)
      navTimer = null
      navSignatureAtArm = ''
    }

    const armNav = (): void => {
      disarmNav()
      navSignatureAtArm = selectedRowSignature() ?? ''
      const root = drawerRoot()
      if (root === null) return
      navObserver = new MutationObserver(() => {
        if (!drawerOpen()) {
          disarmNav()
          return
        }
        const signature = selectedRowSignature()
        if (signature !== null && signature !== navSignatureAtArm) {
          disarmNav()
          toggleSidebar()
        }
      })
      navObserver.observe(root, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['aria-selected'],
      })
      navTimer = window.setTimeout(disarmNav, 2000)
    }

    /** Record where a touch started. The DSHA click replacement in
     *  onDrawerClick needs the down point to tell a tap from a scroll or a
     *  swipe, so every touch pointerdown records it BEFORE any early return. */
    let touchStartedAt = 0
    const onDrawerPointerDown = (event: PointerEvent): void => {
      touchStartedAt = performance.now()
      touchDownAt = event.pointerType === 'touch' || event.pointerType === 'pen'
        ? { x: event.clientX, y: event.clientY }
        : null
    }

    const onDrawerClick = (event: MouseEvent): void => {
      const target = event.target
      // A classified swipe already toggled the drawer; never let its
      // synthetic tap also close it / navigate a row (gesture-guard).
      // isStrokeLocked: a stroke axis-locked mid-swipe (audit S0) — the
      // consume marks do not exist until the gesture layer's own pointerup,
      // which runs AFTER this handler on the same release event.
      if (isStrokeLocked() || consumeIfGestured(event)) return
      // The backdrop keeps its own listener, but the third-party mobile shim
      // stops click propagation at the frame for anything outside the drawer
      // (its own dismiss path), so that listener never sees the tap. Decide
      // here instead — before both the shim and the element handler.
      if (target instanceof Element && target.closest('[data-mobile-nav="hit"]') !== null) {
        if (drawerOpen()) toggleSidebar()
        return
      }
      // DSHA detail=0 follows the host's keyboard/programmatic open path.
      // Replace the real click, rather than opening on pointerup and allowing
      // the later native click to select again. dsha-session-open owns closing.
      const dshaRow = target instanceof Element
        ? target.closest('[role="treeitem"][data-dsha-session-select]') : null
      if (dshaRow !== null && event.detail > 0 && isDrawerNavTarget(target)) {
        if (event.defaultPrevented || touchDownAt !== null &&
          (performance.now() - touchStartedAt >= 500 ||
           !isTapWithinSlop(touchDownAt,
             { x: event.clientX, y: event.clientY }, TAP_NAV_SLOP_PX))) return
        event.preventDefault()
        event.stopPropagation()
        disarmNav()
        lastTouchNavAt = performance.now()
        dshaRow.dispatchEvent(new MouseEvent('click', {
          bubbles: true, cancelable: true, view: window, detail: 0,
        }))
        return
      }
      // A touch row-tap owns the close (pointerup or the navigation observer);
      // let the row's click reach React without toggling the drawer twice.
      if (performance.now() - lastTouchNavAt < 500) return
      if (shouldCloseOnTapInsideDrawer(target)) toggleSidebar()
    }

    const onDrawerPointerUp = (event: PointerEvent): void => {
      // A classified swipe must not arm the nav observer or toggle again
      // (gesture-guard): the drawer already toggled, and the row under the
      // stroke was never a tap. isStrokeLocked covers the release event of
      // a stroke locked mid-swipe but not yet classified — this handler
      // runs before the gesture layer's own pointerup (audit S0/S1: without
      // it the host toggled first and the gesture toggled back, net zero).
      if (isStrokeLocked() || consumeIfGestured(event)) return
      if (event.pointerType !== 'touch' && event.pointerType !== 'pen') return
      const target = event.target
      if (!(target instanceof Element)) return
      if (!shouldCloseOnTapInsideDrawer(target)) return

      const row = target.closest('[role="treeitem"]')
      if (row !== null) {
        lastTouchNavAt = performance.now()
        if (row.getAttribute('aria-selected') === 'true') {
          // Already-selected row will not navigate; closing immediately is safe.
          toggleSidebar()
        } else {
          // Unselected row: the row's own onClick navigates; close once the DOM
          // shows that navigation landed (selected-row title change). The
          // former store-driven `sessions.open` fallback (#49) is gone: hosts
          // >=0.2.2 expose no `sessions.open` and publish no `current`, so that
          // branch could never run.
          armNav()
        }
        return
      }

      // Non-row nav targets (newSession / taskboard / ssh / search rows that
      // are not treeitems) are closed by the capture click handler below:
      // closing here would retract the drawer before the browser dispatches
      // the tap's click, and the target's onClick would never run.
    }

    // DSHA 宿主在「真正打开会话」时才派发 dsha-session-open（单击只选中），
    // 所以抽屉的关闭挂在这个事实上，而不是挂在点击上。
    const onDshaSessionOpen = (): void => {
      if (drawerOpen()) toggleSidebar()
    }
    document.addEventListener('dsha-session-open', onDshaSessionOpen)
    document.addEventListener('dblclick', onDrawerDoubleClick, true)
    document.addEventListener('keydown', onKeyDown, true)
    document.addEventListener('click', onDrawerClick, true)
    document.addEventListener('pointerdown', onDrawerPointerDown, true)
    document.addEventListener('pointerup', onDrawerPointerUp, true)
    return () => {
      disarmNav()
      touchDownAt = null
      document.removeEventListener('dsha-session-open', onDshaSessionOpen)
      document.removeEventListener('dblclick', onDrawerDoubleClick, true)
      document.removeEventListener('keydown', onKeyDown, true)
      document.removeEventListener('click', onDrawerClick, true)
      document.removeEventListener('pointerdown', onDrawerPointerDown, true)
      document.removeEventListener('pointerup', onDrawerPointerUp, true)
    }
  })
}
