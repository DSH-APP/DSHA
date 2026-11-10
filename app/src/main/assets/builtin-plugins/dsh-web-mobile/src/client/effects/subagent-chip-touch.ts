import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { installMobileEffect } from '../platform/breakpoints.ts'

/**
 * Touch support for the lineage-count chip ("N 个子代理") that
 * `dsh-client-ui-subagent` renders in the session header.
 *
 * Upstream history (both observed live on the served bundle):
 *
 * 1. The original count-variant trigger shipped without an onClick handler
 *    (`onClick: openTitle === void 0 ? void 0 : …`) and drove its card purely
 *    through onMouseEnter/onMouseLeave hover timers — enter arms a 150 ms
 *    open timer, leave arms a 120 ms close timer, and each cancels the
 *    other. On touch devices every tap makes the browser synthesize paired
 *    mouseenter/mouseleave from its tracked mouse position, which usually
 *    differs from the tap point: taps did nothing, or the card popped back
 *    open ~200 ms after an outside close (the “点了没反应 / 自弹回” era).
 *
 * 2. A later host removed the hover timers and gave the trigger
 *    a native `onClick: () => changeOpen(!open)`. A phone tap now crosses
 *    TWO toggle sources: the browser fires pointerup first (this shim
 *    dispatches the synthetic ArrowDown there, capture phase — BEFORE the
 *    click), which opens the card through the component's own keyboard
 *    path, and then the tap's click reaches the native onClick, which
 *    toggles the card right back shut. The two toggles cancel each other:
 *    the panel flashes open for a frame and is gone (「闪退」), and the
 *    chip reads as unresponsive.
 *
 * Fix strategy, scoped to touch pointers (mouse users keep native hover):
 * 1. Toggle the card ourselves along the component's own keyboard path —
 *    ArrowDown keydown on the trigger opens (+focus first row), Escape
 *    closes (both verified against the live component in both upstream
 *    versions). React delivers dispatched KeyboardEvents to onKeyDown like
 *    any bubbling event.
 * 2. Swallow the tap's own follow-up click on the trigger we just toggled,
 *    so a native onClick (era 2) can never cancel the keyboard-path
 *    toggle. On the hover-only build the click never toggled anything, so
 *    swallowing it is a no-op — one deterministic toggle per tap across
 *    both upstreams.
 * 3. For a short window after every touch pointer activity, swallow trusted
 *    synthesized mouseover/out/enter/leave events targeting the lineage
 *    root or its menu, so era-1 hover timers can neither cancel our toggle
 *    nor resurrect a just-closed card (no-op on rc.6, which has no hover
 *    timers at all).
 */

/** Count-variant trigger only: the switcher variant has its own onClick. */
const CHIP_TRIGGER_SELECTOR =
  '[data-mobile-nav="frame"] button[class*="_trigger"][aria-haspopup="tree"][aria-expanded]:not([class*="_switcherTrigger"])'

/**
 * Lineage root plus its menu (dsh-client-ui-subagent 0.2.2: ZKlsPq_).
 * The pre-0.2 h8S2Va_ fallback was removed with the low-generation
 * branches. Audit these selectors when the host package upgrades.
 */
const HOVER_SUBTREE_SELECTOR = '[class*="ZKlsPq_root"], [class*="ZKlsPq_menu"]'

/** How long after touch activity synthesized hover events stay suppressed. */
const SWALLOW_WINDOW_MS = 800

/**
 * How long the tap's follow-up click stays suppressed on the trigger we
 * toggled through the keyboard path. A touch click lands a few ms after its
 * pointerup; 1 s is a generous upper bound that still expires before the
 * user's next deliberate tap.
 */
const CLICK_GRACE_MS = 1000

const SWALLOWED_TYPES = [
  'mouseover',
  'mouseout',
  'mouseenter',
  'mouseleave',
  // 2026-11-08 (#281): the same hover timers moved to the POINTER channel —
  // the lineage root and the menu container now carry onPointerLeave →
  // scheduleHoverClose (120 ms). Swallowing only the mouse family leaves the
  // tap's own pointerout/pointerleave untouched, and because the keyboard path
  // never sets the component's pinnedRef, that timer closes the card we just
  // opened (CDP: expanded is true right after pointerup, still true at 60 ms,
  // false by 150 ms; blocking these two events keeps it open).
  'pointerout',
  'pointerleave',
] as const

export function installSubagentChipTouch(ctx: ClientContext): void {
  installMobileEffect(ctx, 'dsh-web-mobile: lineage chip touch toggle', () => {
    if (typeof PointerEvent === 'undefined') return undefined

    let swallowUntil = 0
    const armSwallowWindow = (): void => {
      swallowUntil = Date.now() + SWALLOW_WINDOW_MS
    }

    // The trigger whose tap we just toggled through the keyboard path, and
    // how long that tap's follow-up click must be suppressed on it.
    let toggledTrigger: HTMLElement | null = null
    let toggledUntil = 0

    const onPointerUp = (event: PointerEvent): void => {
      if (event.pointerType !== 'touch' && event.pointerType !== 'pen') return
      armSwallowWindow()
      const target = event.target
      if (!(target instanceof Element)) return
      const trigger = target.closest<HTMLElement>(CHIP_TRIGGER_SELECTOR)
      if (trigger === null) return
      const open = trigger.getAttribute('aria-expanded') === 'true'
      // The component's own keyboard path: navigate() treats ArrowDown as
      // open (+focus first row) and Escape as close-with-focus-restore.
      trigger.dispatchEvent(
        new KeyboardEvent('keydown', {
          key: open ? 'Escape' : 'ArrowDown',
          bubbles: true,
          cancelable: true,
        }),
      )
      toggledTrigger = trigger
      toggledUntil = Date.now() + CLICK_GRACE_MS
    }

    /**
     * The tap's own click must not re-toggle the trigger: on 0.1.0-rc.6 the
     * trigger carries a native onClick (changeOpen(!open)) that would cancel
     * the keyboard-path toggle fired on pointerup — the flash-and-close
     * race. stopPropagation() at document capture blocks the click from
     * reaching the container-level React delegation (so the trigger's
     * onClick never runs) while letting other document listeners observe it.
     * Identity-checked, so taps on menu rows or anywhere else pass through
     * untouched.
     */
    const onClick = (event: MouseEvent): void => {
      if (toggledTrigger === null) return
      if (Date.now() >= toggledUntil) {
        toggledTrigger = null
        return
      }
      const target = event.target
      if (!(target instanceof Element)) return
      if (target.closest<HTMLElement>(CHIP_TRIGGER_SELECTOR) !== toggledTrigger) return
      toggledTrigger = null
      event.stopPropagation()
    }

    const onAnyPointerActivity = (event: PointerEvent): void => {
      if (event.pointerType !== 'touch' && event.pointerType !== 'pen') return
      armSwallowWindow()
      void event
    }

    const swallowSyntheticHover = (event: MouseEvent): void => {
      if (Date.now() >= swallowUntil) return
      if (!event.isTrusted) return
      const target = event.target
      if (!(target instanceof Element)) return
      if (target.closest(HOVER_SUBTREE_SELECTOR) === null) return
      event.stopImmediatePropagation()
    }

    document.addEventListener('pointerdown', onAnyPointerActivity, true)
    document.addEventListener('pointerup', onPointerUp, true)
    document.addEventListener('click', onClick, true)
    for (const type of SWALLOWED_TYPES) {
      document.addEventListener(type, swallowSyntheticHover, true)
    }
    return () => {
      document.removeEventListener('pointerdown', onAnyPointerActivity, true)
      document.removeEventListener('pointerup', onPointerUp, true)
      document.removeEventListener('click', onClick, true)
      for (const type of SWALLOWED_TYPES) {
        document.removeEventListener(type, swallowSyntheticHover, true)
      }
    }
  })
}
