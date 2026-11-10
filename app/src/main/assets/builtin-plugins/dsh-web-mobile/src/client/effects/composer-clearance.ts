import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { composerOccludedPx, nextComposerTight } from '../core/composer-clearance.ts'
import { installMobileEffect } from '../platform/breakpoints.ts'
import { getFrame } from '../platform/frame.ts'

/**
 * Issue #245: rotating to landscape while the composer holds focus and the soft
 * keyboard is up leaves the input row misaligned or gone until the page is
 * reloaded.
 *
 * Mechanism (derivation, numbers and the host contract in
 * core/composer-clearance.ts):
 * - The host composer seat `[class*="_composerSeat"]` is `position: sticky;
 *   bottom: 0` inside the conversation scroll body — pure CSS, the host never
 *   measures it in JS (host 0.2.1-rc.3: `composerSeat` appears only in its
 *   stylesheet, class map and JSX; nowhere does the package read its rect or a
 *   `dvh`). So the seat's top edge is always `band bottom - seat height`.
 * - This plugin owns the header stacking: styles/layout.css.ts paints the
 *   conversation header opaque and above the flow (`z-index: 30`, the
 *   "Opaque AND above the flow" block) and lifts the scroll body's box under
 *   it, so the seat's box region includes the header band.
 * - Portrait leaves 700+px above the keyboard, so the seat never reaches the
 *   header. Landscape (792x360 here) with the IME still up leaves the layout
 *   viewport at ~110-160px while the header is ~67-77px tall and the seat
 *   ~70-102px: `67 + 70 > 110`, the editing row is laid out *inside the header
 *   band* and the opaque header paints over it. Sticky cannot be scrolled out
 *   of that state, and reloading closes the keyboard — hence "only a refresh
 *   helps".
 *
 * What this module does: measure the band/header/seat on every viewport event
 * and publish the decision as a DOM marker on the frame; the CSS block keyed on
 * `data-mobile-nav-composer-tight` then lets the seat outrank the header and
 * drops the seat's secondary rows so the editing row fits. No transform, no
 * inline styles on host nodes: a lift would be wrong here (the seat is already
 * bottom-pinned; the covered part is its top) — that is why
 * composer-keyboard-lift.ts stays iOS-only and is not touched.
 *
 * Why not a CSS media query: the trigger is `header height + seat height >
 * visible band`, and all three depend on the device, the IME and the draft
 * (a multi-line draft grows the seat). A px threshold would be a magic number
 * that misses the next phone and misfires on tablets in landscape. The
 * decision is measured, the pure half is unit-tested
 * (tests/composer-clearance.test.ts).
 *
 * Fail-open: `composerOccludedPx` returns 0 for garbage readings and whenever
 * the two scroll channels disagree (visual-viewport pan), and the marker is
 * dropped whenever the composer is not laid out (hero phase, panel takeover,
 * host unmount).
 *
 * DOM contract (audit on host upgrades): the seat is the host-owned hashed
 * `_composerSeat` (the same substring composer-keyboard-lift.ts anchors), the
 * header is the host header class `wSkVaW_header` (not a bare `header`: the
 * ask-question card renders its own header inside the seat, pitfalls §提问卡
 * 顶栏误伤), and the secondary row the CSS drops is `[data-composer-dock]`
 * (the InputBar dock row; the older `[data-composer-stats]` arm was removed
 * with the host floor >=0.2.2-rc.1).
 */

/** Marker written on the frame while the composer seat is occluded. */
const TIGHT_MARKER = 'data-mobile-nav-composer-tight'

/** Host composer seat, scoped under the plugin frame marker. */
const SEAT_SELECTOR = '[class*="_composerSeat"]'

/** The conversation header — the host header class, never a bare `header`.
 *  The anchor is the underscore suffix, not a hash prefix: the host renamed
 *  its CSS-module prefix (wSkVaW_ → tlcmdG_ in 0.2.1-rc.2) and every
 *  prefix-anchored rule silently stopped matching. */
const HEADER_SELECTOR = 'header:has([class*="_headerLeading"])'

export function installComposerClearance(ctx: ClientContext): void {
  installMobileEffect(ctx, 'dsh-web-mobile: composer clearance', () => {
    /** Current state of the marker (the hysteresis input). */
    let tight = false
    /** Seat height with every row visible. Refreshed only while the marker is
     *  off: while it is on our own CSS has already dropped the dock row, and
     *  feeding that shrunk height back would turn the state machine off on the
     *  next resize tick and on again one tick later. */
    let fullHeight = 0
    let frame = 0

    const clear = (): void => {
      tight = false
      fullHeight = 0
      getFrame()?.removeAttribute(TIGHT_MARKER)
    }

    const measure = (): void => {
      frame = 0
      const host = getFrame()
      if (host === null) {
        clear()
        return
      }
      const seat = host.querySelector<HTMLElement>(SEAT_SELECTOR)
      const header = host.querySelector<HTMLElement>(HEADER_SELECTOR)
      const rect = seat?.getBoundingClientRect() ?? null
      // Not laid out: hero phase, panel takeover (the host hides the seat), or
      // a host that has not mounted the composer yet. Nothing to clear up.
      if (seat === null || header === null || rect === null || rect.width === 0 || rect.height === 0) {
        clear()
        return
      }
      if (!tight) fullHeight = rect.height
      const viewport = window.visualViewport
      const occluded = composerOccludedPx({
        visibleHeight: Math.min(window.innerHeight, viewport?.height ?? window.innerHeight),
        headerBottom: header.getBoundingClientRect().bottom,
        seatHeight: fullHeight > 0 ? fullHeight : rect.height,
        seatBottom: rect.bottom,
        scrollY: window.scrollY,
        offsetTop: viewport?.offsetTop ?? 0,
      })
      tight = nextComposerTight(tight, occluded)
      if (tight) {
        if (!host.hasAttribute(TIGHT_MARKER)) host.setAttribute(TIGHT_MARKER, '')
      } else if (host.hasAttribute(TIGHT_MARKER)) {
        host.removeAttribute(TIGHT_MARKER)
      }
    }

    const schedule = (): void => {
      if (frame === 0) frame = requestAnimationFrame(measure)
    }

    // The IME animation, the rotation and the keyboard-less restore all arrive
    // as resize storms; one measurement per frame is enough and keeps the
    // whole effect off the streaming path. `orientationchange` is kept for the
    // WebView sequences that fire it before the final resize.
    window.addEventListener('resize', schedule)
    window.addEventListener('orientationchange', schedule)
    window.visualViewport?.addEventListener('resize', schedule)
    window.visualViewport?.addEventListener('scroll', schedule)
    // A composer that mounts under an already-shrunk band (session switch with
    // the keyboard up) sees no resize at all; its own autofocus is the signal.
    document.addEventListener('focusin', schedule, true)
    document.addEventListener('focusout', schedule, true)
    schedule()

    return () => {
      window.removeEventListener('resize', schedule)
      window.removeEventListener('orientationchange', schedule)
      window.visualViewport?.removeEventListener('resize', schedule)
      window.visualViewport?.removeEventListener('scroll', schedule)
      document.removeEventListener('focusin', schedule, true)
      document.removeEventListener('focusout', schedule, true)
      if (frame !== 0) {
        cancelAnimationFrame(frame)
        frame = 0
      }
      clear()
    }
  })
}
