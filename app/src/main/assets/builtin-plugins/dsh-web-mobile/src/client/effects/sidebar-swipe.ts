import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { getFrame } from '../platform/frame.ts'
import { installMobileEffect } from '../platform/breakpoints.ts'
import { markGestureConsumed, consumeIfGestured, markStrokeLocked, clearStrokeLocked } from './gesture-guard.ts'
import { fadeOverlayOut } from './overlay-backdrop-fab.ts'

/**
 * Sidebar drawer swipe gestures (B 档 hybrid follow, per the 2026-08-29
 * controlled upgrade of docs/specs/2026-08-27-sidebar-swipe-gestures.md).
 *
 * Three gestures:
 * - edge swipe-in: the pointer goes down within the start zone (45% of the
 *   left edge) and the drawer is closed → the host state is flipped AT
 *   AXIS-LOCK (early commit) while the drawer is pinned in its closed slot,
 *   so the REAL open subtree mounts off-screen and then follows the finger
 *   out of the slot (see startFollow for why the flip has to come first);
 * - content swipe-toward-slot: the pointer goes down inside the open drawer
 *   and drags LEFT (LTR) → the drawer FOLLOWS the finger (inline translateX,
 *   transition:none) and releases into the host's transition;
 * - content swipe-out (legacy): drag RIGHT inside the open drawer → no
 *   follow (A 档 semantics preserved verbatim), release classifies.
 *
 * The release decision is UNCHANGED from A 档: classifySwipe (distance ratio
 * OR recent-window velocity) — after a follow stroke, dx IS the followed
 * position, so the same function decides complete vs spring-back. The commit
 * is still just `ctx.layout.toggleSidebar()`. The follow mechanics ride the
 * host transition instead of fighting it: during the stroke the drawer gets
 * inline `transition: none` + translateX; on release the inline styles are
 * dropped and the commit retargets the host transition IN THE SAME TASK (no
 * paint in between), so the drawer animates from the finger position to the
 * final state with zero custom animation code.
 *
 * Review constraints honored (spec 2026-08-27 second review): the backdrop
 * stays binary (appears at commit — never opacity-followed, 缺陷 2); a modal
 * rising mid-stroke reverts the drawer every move event (缺陷 1's per-frame
 * guard); the OPEN final state must end with transform:none (the containing
 * block invariant for fixed descendants) — a transitionend-free cleanup pair
 * (inline clear + host value) guarantees it because the host open rule is
 * transform:none. No gesture-layer DOM, no setPointerCapture. Zero transform
 * writes remain true for the LEGACY rightward-close path.
 *
 * Coexistence with the host's overlay interactions (document capture click /
 * pointerup) is two-layered via gesture-guard.ts: (1) tryLock publishes an
 * axis-lock flag the instant the stroke locks horizontal — during
 * pointermove, strictly before any pointerup — and the host handlers yield
 * on it first, because they are registered EARLIER and the post-release
 * consume marks do not exist yet on the stroke's own release event (audit
 * S0: the host toggled first and the gesture toggled back, net zero);
 * (2) a classified swipe additionally marks its target chain consumed so
 * the synthetic click after the stroke can never toggle twice or navigate
 * a row.
 */

/**
 * Start-zone width as a FRACTION of the viewport width: the pointer counts
 * as "from the left edge" anywhere inside the left (RTL: right) strip this
 * wide. The zone STAYS at 45% (2026-09-11 user decision): a brief seventh
 * pass shrank it to 0.25 for the draggable-widget conflict and was rolled
 * back the same day — the user keeps the "anywhere in the left half" feel
 * and the conflict is handled by yield signals instead (the
 * data-mobile-nav-dragging cooperation mark + the floating-widget positional
 * heuristic, see dragMarkYields/findFloatingWidget).
 * History of the constant: 24px (hotspot era) → 48px (third pass, fixed
 * "识别成对话内容滚动") → 96px (fourth pass — at that point the zone also
 * finally cleared Chrome Android's EDGE_WIDTH_DP=48dp history-navigation
 * trigger strip, whose strokes the browser claims and pointercancels; the
 * browser gesture itself is suppressed by the root overscroll-behavior-x:
 * none rule in layout.css.ts) → 0.45×viewport (fifth pass; brief 0.25
 * experiment rolled back) — this value.
 * Safety at this width: the release classification (0.16×w travel OR
 * 0.45px/ms velocity) still gates the commit, so widening cannot open on a
 * tap; vertical strokes never reach the touchmove preventDefault, so their
 * native scroll is untouched (see onTouchMove — the cancel is only allowed
 * after the horizontal axis lock); strokes beginning inside genuinely
 * horizontally scrollable containers are excluded from the zone entirely —
 * see findHorizontalScroller (at 45% the stats line / message code blocks
 * sit well inside the strip, so that guard is load-bearing).
 */
const START_ZONE_RATIO = 0.45

/**
 * The zone in pixels for a given viewport width (pure, exported for the
 * decision-table tests). Rounded so the probe boundary assertions stay
 * integral (390px → Math.round(175.5) = 176).
 */
export function startZonePxFor(viewportWidthPx: number, ratio: number = START_ZONE_RATIO): number {
  return Math.round(viewportWidthPx * ratio)
}
/**
 * Axis-lock threshold: once the stroke's dominant axis has moved this far,
 * the axis is decided. Horizontal-dominant (|dx| > |dy|) locks the stroke
 * to X (a swipe); vertical-dominant abandons it to native scrolling.
 * Replaces the old 4px slop + 1.5× direction-bias pair — a 1.5× bias
 * rejected natural ~45° diagonal swipes (the other half of the
 * "识别成滚动" report). MUI uses a 3px uncertainty threshold; 8px is a
 * comfortable margin against tap jitter while still deciding in the first
 * ~16ms of movement.
 */
const LOCK_PX = 8
/** Distance thresholds as a fraction of the viewport width.
 *  Second tuning pass (2026-08-27, "识别成滚动" feedback): 0.16 open = ~62px
 *  on a 390px phone, 0.13 close = ~51px. Keep the open threshold above the
 *  close threshold so an accidental reverse swipe cannot re-open. */
const OPEN_DISTANCE_RATIO = 0.16
const CLOSE_DISTANCE_RATIO = 0.13
/** Velocity window: most-recent-60ms instantaneous speed (end-segment slope). */
const VELOCITY_WINDOW_MS = 60
/** px/ms speed thresholds for open / close (MUI uses 0.45). */
const OPEN_VELOCITY = 0.45
const CLOSE_VELOCITY = 0.45
/** Covers the .28s CSS transition; prevents reverse-gesture double-toggles. */
const COOLDOWN_MS = 350
/** How long a consumed gesture mark stays live (covers the synthetic click).
 * Short by design: browsers dispatch the synthetic click within tens of ms,
 * while iOS shells suppress it entirely — a long window with no delivery
 * would let the marks swallow the user's next genuine tap (dead-tap bug).
 * When upTo is absent from the release chain (edge swipe-in releases over
 * the main content) the mark walk reaches the document root, so this short
 * window is also the bound on how long any tap can be suppressed. */
const CONSUME_WINDOW_MS = 300
/**
 * Rightward travel (from the stroke start) that arms the OPEN follow, i.e.
 * flips the host state early so the real drawer subtree mounts. Slightly
 * above LOCK_PX so an 8px horizontal twitch inside the wide start zone does
 * not mount-and-unmount 389 nodes; small enough that the dead zone before
 * the drawer's edge appears is imperceptible.
 */
/** The open follow arms at the AXIS LOCK itself: tryLock already demanded
 * 8px of horizontal-dominant travel, so no extra twitch margin is needed —
 * every pixel between lock and arm was dead drag (user report 2026-08-29
 * 「右滑的过程中最开始有真空期,有一段卡的地方」). The release verdict still
 * decides the outcome, so arming early cannot commit a false open. */
const OPEN_FOLLOW_ARM_PX = 8
/**
 * The host's closed-slot offset as a PERCENTAGE of the drawer's own width
 * (`transform: translateX(-110%)` — the 10% overshoot hides the drawer's
 * shadow). Percentages are load-bearing for the open follow: the element
 * width changes mid-stroke when React swaps the collapsed rail for the real
 * drawer, and a percentage re-resolves against the current width while a
 * cached px value would not.
 */
const CLOSED_SLOT_PCT = 110
/** Duration of the self-run terminal close animation. Matches the host's
 * .28s drawer transition so the handoff feels identical. */
const COMMIT_ANIM_MS = 280
/** Percentage baseline of the OPEN-direction follow. The host's closed slot
 * is -110%, but following from -110% hides the first 28px of travel (the
 * 10% overshoot of the 280px drawer): the drawer stayed invisible until
 * ~dx=28 — user report 「刚开始会卡一下，之后才会拖出来」(measured: first
 * paint at dx=12 was left=-296, edge reached the viewport only at dx=28).
 * 101% keeps a small hidden margin (subpixel safety, would-be sliver at
 * exactly -100%) so the drawer edge answers the finger right after the
 * axis lock: at the 8px arm the edge is already ~5px on-screen (-102% left
 * only 2.4px and read as a vacuum; -110% hid the first 28px entirely).
 * The closed slot itself is only ever needed at TERMINAL states,
 * where CLOSED_SLOT_PCT is used verbatim. */
const OPEN_FOLLOW_BASE_PCT = 101

/**
 * Files-panel (host right sidebar) gesture constants — the right-edge mirror
 * of the drawer layer (spec 2026-09-13-files-swipe-gesture-design.md).
 *
 * FILES_ZONE_RATIO mirrors the drawer's START_ZONE_RATIO: a narrow strip
 * cannot be used because Chrome Android's history-nav edge strip (~48dp)
 * pointercancels strokes starting inside it — the same reason the drawer
 * zone grew to 45% of the viewport.
 */
const FILES_ZONE_RATIO = 0.45
/**
 * Distance threshold as a fraction of the viewport width for BOTH files
 * directions (≈62px on a 390px phone). The drawer's separate close ratio
 * (0.13) exists because the close stroke follows into its slot; files
 * strokes have no follow, so one ratio serves both directions.
 */
const FILES_DISTANCE_RATIO = 0.16
/** px/ms velocity threshold for both files directions (drawer parity). */
const FILES_VELOCITY = 0.45

/** Pointer id we are tracking (multi-touch is ignored). */
let trackingPointer = 0
/** True once the stroke is axis-locked (direction bias passed). */
let tracking = false
/** Stroke samples (x + timestamp) for the recent-window velocity. */
let samples: Array<{ t: number; x: number }> = []
/** Stroke origin (for the direction-bias check). */
let startX = 0
let startY = 0
/** Drawer visibility at lock time. */
let lockDrawerOpen = false
/** Expiry of the post-release cooldown (performance.now()). */
let cooldownUntil = 0
/** Element whose stroke was marked consumed (null = no live mark). */
let consumedEl: Element | null = null

/** B 档 follow state — one cache per stroke, set once at lock time so the
 * per-move writes never read layout (the spec review's rAF-contention
 * constraint). followDrawer stays bound for the whole stroke so a
 * released-then-re-engaged stroke (direction wobble) reuses the cache. */
let followDrawer: HTMLElement | null = null
let followEngaged = false
let strokeClosedTx = 0
let strokeRtl = false
/** True while an OPEN stroke has early-committed the host state (the drawer
 * subtree is mounted but pinned in its slot, following the finger). The
 * release must then either keep it open or toggle it back. */
let openFollowArmed = false
/** True once an open stroke has decided NOT to arm the follow (aborted arm:
 * a modal/takeover veto, a missing drawer) so it never retries mid-stroke. */
let openFollowRefused = false

/**
 * Which gesture family owns the current stroke: 'drawer' = the sidebar
 * gestures (every pre-existing behavior, untouched); 'files' = the
 * right-edge files-panel gesture (no follow painting, host-panel commit).
 * Written by beginStroke only; a fresh beginStroke always routes it.
 */
let strokeMode: 'drawer' | 'files' = 'drawer'
/** Files-panel visibility at lock time (the mirror of lockDrawerOpen). */
let lockFilesOpen = false
/**
 * The files-panel toggle injected at install (openFilesPanel: it toggles by
 * the host's own control state, so open and close share one function).
 * Module-level because endStroke is a module-level function; the default is
 * a no-op so the node:test suite can import the module without a DOM.
 */
let filesToggleFn: () => boolean = () => false

export interface SwipeThresholds {
  openDistanceRatio: number
  closeDistanceRatio: number
  openVelocity: number
  closeVelocity: number
  lockPx: number
}

/**
 * Pure decision: what does this stroke do, given the drawer state?
 * `dx`/`dy` are raw pointer deltas (RTL mirrors X through `rtl`), `velX` is
 * the raw recent-window X velocity. The stroke must be locked horizontal
 * (|dx| > |dy| and past the lock slop) and direction-consistent; then
 * distance OR velocity wins, with the drawer-state-specific threshold.
 */
export function classifySwipe(
  t: SwipeThresholds & { viewportWidthPx: number; drawerOpen: boolean },
  m: { dx: number; dy: number; velX: number },
  rtl: boolean,
): 'open' | 'close' | 'none' {
  // RTL mirrors the X axis: a rightward stroke (positive dx in LTR) is
  // leftward in RTL. Normalize to the logical direction before judging.
  const dx = rtl ? -m.dx : m.dx
  if (Math.abs(dx) <= t.lockPx) return 'none'
  if (Math.abs(dx) <= Math.abs(m.dy)) return 'none'
  if (t.drawerOpen) {
    // BOTH horizontal directions close (2026-08-29 sixth round, user report
    // 「根本没法左滑关闭」). Leftward is the natural "push it back into its
    // slot" gesture — and the only one the follow animation actually paints
    // (followTranslate's close branch follows leftward), so refusing it made
    // the drawer track the finger and then spring back, i.e. the animation
    // promised a close the classifier would not honor. Rightward stays
    // accepted verbatim: four tuning rounds of muscle memory ride on it and
    // failure scenarios B0/B1/B2 assert it. Nothing else competes for a
    // horizontal stroke while the drawer is open, so accepting both costs no
    // ambiguity.
    const travel = Math.abs(dx)
    if (travel / t.viewportWidthPx >= t.closeDistanceRatio) return 'close'
    const velX = rtl ? -m.velX : m.velX
    // A fling only counts when it agrees with the stroke's own direction
    // (same contradiction guard the open branch applies).
    if (velX > 0 !== dx > 0) return 'none'
    return Math.abs(velX) >= t.closeVelocity ? 'close' : 'none'
  }
  if (dx <= 0) return 'none'
  if (dx / t.viewportWidthPx >= t.openDistanceRatio) return 'open'
  const velX = rtl ? -m.velX : m.velX
  return velX >= t.openVelocity ? 'open' : 'none'
}

/** Threshold shape for the files classifier (pure, node:testable). */
export interface FilesThresholds {
  distanceRatio: number
  velocity: number
  lockPx: number
  viewportWidthPx: number
  /** Files panel mounted at lock time. */
  panelOpen: boolean
  /** Drawer open at lock time. */
  drawerOpen: boolean
  /**
   * Distance gate for the drawer-open rightward cell. That cell commits a
   * DRAWER close, so it rides the drawer's own CLOSE_DISTANCE_RATIO (0.13),
   * not the files panel's 0.16: one physical stroke must judge the same
   * wherever it starts. Optional — defaults to `distanceRatio`.
   */
  drawerCloseDistanceRatio?: number
}

/**
 * Pure decision for the FILES gesture (right-edge zone), the mirror twin of
 * classifySwipe. RTL mirrors the X axis exactly like classifySwipe. The
 * verdict space extends the drawer's with `files` (the files-panel commit:
 * open the panel on a leftward stroke when everything is closed, close it on
 * a rightward stroke when it is open):
 * - leftward-logical strokes only ever mean "open the panel" and fire ONLY
 *   when panel and drawer are BOTH closed — the panel would mount under the
 *   open drawer (z-1100) and be invisible, so the stroke is 'none' (the
 *   2026-09-13 narrowing: a leftward stroke NEVER collapses anything);
 * - rightward-logical strokes close the VISIBLE TOP: drawer open → 'close'
 *   (the animated commitFollowClose path, gated on the drawer's own
 *   distance/velocity thresholds so both families judge a stroke alike);
 *   else panel open → 'files'; else 'none'.
 */
export function classifyFilesSwipe(
  t: FilesThresholds,
  m: { dx: number; dy: number; velX: number },
  rtl: boolean,
): 'open' | 'close' | 'files' | 'none' {
  const dx = rtl ? -m.dx : m.dx
  if (Math.abs(dx) <= t.lockPx) return 'none'
  if (Math.abs(dx) <= Math.abs(m.dy)) return 'none'
  const velX = rtl ? -m.velX : m.velX
  if (dx < 0) {
    if (t.panelOpen || t.drawerOpen) return 'none'
    if (-dx / t.viewportWidthPx >= t.distanceRatio) return 'files'
    // A fling only counts when it agrees with the stroke's own direction
    // (the same contradiction guard classifySwipe applies).
    if (velX > 0 !== dx > 0) return 'none'
    return -velX >= t.velocity ? 'files' : 'none'
  }
  if (t.drawerOpen) {
    // Same gates as the drawer family's close (classifySwipe), including its
    // CLOSE_DISTANCE_RATIO: this cell IS the drawer-close commit path, so the
    // two families must judge one physical stroke alike. Without the gate the
    // files zone's 45% reaches ~66px into the open drawer column at 390px,
    // where a thumb resting on a row drifts ~8px sideways while scrolling —
    // that closed the drawer AND consumed the tap. The leftward cell beside the
    // drawer is already 'none', so this is what makes the families agree.
    const closeRatio = t.drawerCloseDistanceRatio ?? t.distanceRatio
    if (dx / t.viewportWidthPx >= closeRatio) return 'close'
    if (velX <= 0) return 'none'
    return velX >= t.velocity ? 'close' : 'none'
  }
  if (t.panelOpen) {
    if (dx / t.viewportWidthPx >= t.distanceRatio) return 'files'
    if (velX > 0 !== dx > 0) return 'none'
    return velX >= t.velocity ? 'files' : 'none'
  }
  return 'none'
}

/**
 * Recent-window instantaneous velocity (px/ms) from the tail of the last
 * `windowMs` milliseconds of samples, up to `now`. Sliding X per ms between
 * the LAST TWO in-window samples — the end-of-stroke slope — so a long slow
 * drag then a quick flick reports the flick, not the drag average. Samples
 * older than the window are ignored. Fewer than two in-window samples → 0.
 */
export function slidingVelocity(
  samples: Array<{ t: number; x: number }>,
  windowMs: number,
  now: number,
): number {
  const cutoff = now - windowMs
  const inWindow = samples.filter((s) => s.t >= cutoff)
  if (inWindow.length < 2) return 0
  const a = inWindow[inWindow.length - 2]!
  const b = inWindow[inWindow.length - 1]!
  const dt = b.t - a.t
  if (dt <= 0) return 0
  return (b.x - a.x) / dt
}

/**
 * Geometric start-hit test: the pointer went down in the left edge start
 * zone (when the drawer is closed) or inside the drawer content area (when
 * open). Pure and viewport-relative so it is unit-testable; the runtime
 * variant additionally checks the drawer geometry via the DOM.
 */
export function hitTestStart(
  clientX: number,
  viewportWidthPx: number,
  rtl: boolean,
  t: { startZonePx: number },
): boolean {
  const edge = rtl ? viewportWidthPx - clientX : clientX
  return edge >= 0 && edge <= t.startZonePx
}

/**
 * Geometric start-hit test for the FILES gesture: the pointer went down in
 * the RIGHT edge zone (RTL: LEFT) — the exact mirror of hitTestStart. Pure
 * and viewport-relative.
 */
export function filesZoneHit(
  clientX: number,
  viewportWidthPx: number,
  rtl: boolean,
  zonePx: number,
): boolean {
  const edge = rtl ? clientX : viewportWidthPx - clientX
  return edge >= 0 && edge <= zonePx
}

/** Which gesture family owns a stroke that begins while the drawer is OPEN.
 * Inside the drawer body the drawer family always wins (owner's rule
 * 2026-09-17: the drawer's own surface must answer a leftward drag, whether or
 * not the viewport-ratio files zone overlaps it — at 390px that zone starts at
 * x=214, inside the 280px drawer); outside the body the right zone keeps its
 * files routing and its deliberate leftward 'none' verdict (2026-09-13
 * narrowing). */
export function openStateStartMode(insideDrawer: boolean, inFilesZone: boolean): 'drawer' | 'files' {
  return insideDrawer || !inFilesZone ? 'drawer' : 'files'
}

/**
 * Pure follow mapping (B 档): the translateX (px) to paint for a stroke
 * sample, or null when THIS sample has no follow. `closedTx` is the signed
 * closed-slot translateX (negative LTR, positive RTL — the drawer slides
 * off the anchored edge); `dx` is the RAW pointer delta; normalization
 * mirrors classifySwipe (`d = rtl ? -dx : dx`, rightward-logical positive =
 * toward open).
 *
 * Decision table (C3 hybrid, 2026-08-29 user decision):
 * - close stroke (drawer open): LEFTWARD-logical travel drags the drawer
 *   toward its closed slot, clamped at the slot; rightward-logical → null
 *   (the legacy A 档 close owns that direction — no follow, momentum-honest);
 * - open stroke (drawer closed): NOT used at runtime — the open direction
 *   follows through `followOpenTransform` instead, because its baseline has
 *   to stay a percentage across the subtree swap (see that function). The px
 *   mapping is kept pure and tested as the reference semantics;
 * - a zero closed slot (degenerate host without a closed transform) yields
 *   a constant 0 — the follow degrades to a no-op instead of inventing
 *   travel.
 */
export function followTranslate(
  closedTx: number,
  dx: number,
  rtl: boolean,
  drawerOpen: boolean,
): number | null {
  const dir = closedTx <= 0 ? -1 : 1
  const slot = Math.abs(closedTx)
  const d = rtl ? -dx : dx
  if (drawerOpen) {
    if (d >= 0) return null
    // + 0 normalizes -0 (dir=-1 times a clamped 0) so strict equality in the
    // decision table and in probe comparisons sees a plain zero.
    return dir * Math.min(slot, -d) + 0
  }
  if (d <= 0) return null
  return dir * (slot - Math.min(slot, d)) + 0
}

/**
 * Pure follow mapping for the OPEN direction (B 档, 2026-08-29 second pass).
 * Returns the CSS transform to paint for a stroke that has already
 * early-committed the host state, or null when this sample has no follow
 * (leftward-logical travel, i.e. pulled back past the stroke origin).
 *
 * The baseline is the host's own PERCENTAGE slot (`translateX(-110%)`), kept
 * symbolic on purpose: at arm time the element is still the ~206px collapsed
 * rail and a frame later React has swapped in the ~280px drawer. A px
 * baseline captured before the swap would leave the wider drawer 74px
 * off-position (its slot is -308px, not -227px); `-110%` re-resolves against
 * the element's current width on every frame, so the same declaration is
 * correct across the mount. `min()`/`max()` clamp the open end so overshoot
 * cannot drag the drawer past its resting position.
 */
export function followOpenTransform(travelPx: number, rtl: boolean): string | null {
  const t = rtl ? -travelPx : travelPx
  if (t <= 0) return null
  return rtl
    ? `translateX(max(0px, calc(${OPEN_FOLLOW_BASE_PCT}% - ${t}px)))`
    : `translateX(min(0px, calc(-${OPEN_FOLLOW_BASE_PCT}% + ${t}px)))`
}

/**
 * Minimal ancestor snapshot for the horizontal-scroller walk. Plain data on
 * purpose: the pure walk below is node:testable, and the runtime maps real
 * Elements onto this shape (chainFrom) before calling it.
 */
export interface SwipeChainNode {
  parent: SwipeChainNode | null
  scrollWidth: number
  clientWidth: number
  overflowX: string
}

/**
 * Pure walk: the innermost element of the chain (self included) that is a
 * GENUINELY horizontally scrollable container — overflow-x auto/scroll AND
 * content actually overflowing (scrollWidth > clientWidth + 1; the +1
 * absorbs subpixel rounding). A stroke beginning inside one belongs to that
 * scroller: the browser claims the horizontal pan (pointercancel on real
 * devices) and the release classification must neither compete with it nor
 * preventDefault it away — prevention is what would break the strip's native
 * scrolling near the left edge once the start zone grew to 45% of the
 * viewport (the stats
 * line spans the full width; message code blocks are overflow-x:auto too).
 * CDP failure scenario C1 pins this contract. overflow-x:hidden/clip never
 * match: clipped content cannot pan, so a horizontal stroke there stays free
 * for the gesture layer.
 */
export function findHorizontalScroller(node: SwipeChainNode | null): SwipeChainNode | null {
  let cur = node
  while (cur !== null) {
    if (
      (cur.overflowX === 'auto' || cur.overflowX === 'scroll') &&
      cur.scrollWidth > cur.clientWidth + 1
    ) {
      return cur
    }
    cur = cur.parent
  }
  return null
}

/** The open drawer element: first child of the plugin frame. */
function findDrawer(): HTMLElement | null {
  const frame = getFrame()
  return frame !== null && frame.firstElementChild instanceof HTMLElement
    ? frame.firstElementChild
    : null
}

/** True when the drawer is currently open (per the collapsed marker). */
function drawerOpen(): boolean {
  const frame = getFrame()
  return frame !== null && !frame.hasAttribute('data-sidebar-collapsed')
}

/**
 * True when the host's right sidebar files panel is currently VISIBLE (any
 * form: fullscreen on phones, docked on tablets). Measured on 0.1.5: the
 * panel element is persistent — the closed fullscreen form stays in the DOM
 * at `visibility: hidden` with its rect pushed to x=viewport-width — so
 * presence alone is not the panel-state read. Detect the open state by the
 * three ways the host can hide it (visibility / display / pushed-off rect);
 * a host without this panel keeps returning false and the explorer fallback
 * applies.
 */
function filesPanelOpen(): boolean {
  const panel = document.querySelector('[data-sidebar-right-panel]')
  if (panel === null) return false
  const cs = getComputedStyle(panel)
  if (cs.visibility === 'hidden' || cs.display === 'none') return false
  return panel.getBoundingClientRect().left < window.innerWidth
}

/**
 * Map the real DOM ancestor chain (target first, root last) onto the plain
 * SwipeChainNode shape findHorizontalScroller walks. Bounded by the document
 * depth (~15 nodes in this app) and run once per pointerdown, so the
 * getComputedStyle calls are not a per-frame cost.
 */
function chainFrom(target: Element): SwipeChainNode | null {
  let node: SwipeChainNode | null = null
  let el: Element | null = target
  while (el !== null) {
    node = {
      parent: node,
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
      overflowX: getComputedStyle(el).overflowX,
    }
    el = el.parentElement
  }
  return node
}

/** Whether a modal dialog owns the screen (gestures must yield to it). */
function modalOpen(): boolean {
  return document.querySelector('[aria-modal="true"]') !== null
}

/** True when a full-screen takeover (taskboard / ssh) owns the frame, or any
 *  host conversation.view overlay (trajectory tab, dsh-file-viewer, or a
 *  future third-party view — they all set the generic
 *  `data-conversation-composer-overlay` attribute on their root) is open.
 *  In both cases the drawer edge-swipe gestures yield so horizontal content
 *  scrolling (kanban columns, trajectory tables, CSV/code panes) wins the
 *  left-edge start zone; the FAB still opens the drawer. This reads the
 *  generic overlay attribute directly — deliberately decoupled from the
 *  file-viewer marker, which keys on `.dsfv-panel` only (design
 *  2026-09-06-conversation-overlay-takeover-design.md §4.2). */
function takeoverActive(): boolean {
  return (
    document.documentElement.hasAttribute('data-dsh-taskboard-active') ||
    document.documentElement.hasAttribute('data-dsh-ssh-active') ||
    document.querySelector('[data-conversation-composer-overlay]') !== null
  )
}

/**
 * Whether a live, non-collapsed text selection owns the pointer stroke.
 * A selection-handle drag (and a long-press selection that appears between
 * pointerdown and the axis lock) is horizontally dominant and geometrically
 * indistinguishable from a drawer swipe — the browser must keep it (#43,
 * iPad WebKit). Feature-detected so the node:test suite can import the
 * predicate without a DOM.
 *
 * TWO selection models must be read, because they are disjoint:
 * - the DOCUMENT selection (window.getSelection) covers message-flow text
 *   and contenteditable hosts;
 * - a selection inside a text control lives on the ELEMENT as
 *   selectionStart/selectionEnd and is INVISIBLE to window.getSelection —
 *   measured on the composer during a hijacked stroke (#44, real iPad):
 *   taStart=0 taEnd=20 while the document selection reported isCollapsed.
 *   Reading only the document selection let the swipe layer arm, lock, and
 *   collapse the composer selection the user was extending.
 * document.activeElement is the right anchor for the element model: a handle
 * drag keeps focus inside the control, and it also covers strokes whose
 * points land outside the control's own box.
 */
export function selectionOwnsStroke(): boolean {
  if (typeof window === 'undefined') return false
  const sel = window.getSelection()
  if (sel !== null && !sel.isCollapsed) return true
  if (typeof document === 'undefined') return false
  const el = document.activeElement as HTMLTextAreaElement | null
  if (el === null) return false
  const tag = el.tagName
  if (tag !== 'TEXTAREA' && tag !== 'INPUT') return false
  // Input types without a text selection (checkbox, number, email, …) report
  // null here — measured in Chromium — and older WebKit/Gecko throw
  // InvalidStateError instead. Both mean "no text selection is being
  // dragged", never "the control owns this stroke", so neither may be
  // allowed to escape from a pointer handler.
  try {
    const { selectionStart: start, selectionEnd: end } = el
    return typeof start === 'number' && typeof end === 'number' && start !== end
  } catch {
    return false
  }
}

/** Whether the swipe layer is on cooldown (animation in flight). */
function onCooldown(): boolean {
  return performance.now() < cooldownUntil
}

/**
 * Draggable-element yield mark (2026-09-11, 桌宠拖动冲突 D 方案的 C 侧):
 * a dragging component (desktop pet, floating ball, drag-to-reorder, …)
 * marks itself with `data-mobile-nav-dragging` while its drag is live — on
 * the element the pointer is holding (or any ancestor), or on
 * documentElement/body as a global mark when the dragged node moves around
 * or the dragger prefers not to touch the node tree. The gesture layer
 * reads the mark at pointerdown AND at every axis-lock attempt before the
 * stroke locks: a mark present at either point yields the whole stroke (no
 * drawer arm, no touchmove preventDefault) because the two layers would
 * otherwise both answer the same pointer stream — the exact bug the probe
 * reproduces (draggable-conflict-probe pet.t1: a 56px floating ball dragged
 * rightward inside the fifth-pass 45% zone opened the drawer mid-drag).
 * Semantics mirror selectionOwnsStroke: the mark must be up by the first
 * few move events (a pointerdown handler is the natural place); once the
 * stroke axis-locks the gesture stays committed — a mark appearing
 * mid-locked-stroke does not unwind an already-armed open follow.
 */
function dragMarkYields(event: PointerEvent): boolean {
  if (document.documentElement.hasAttribute('data-mobile-nav-dragging')) return true
  if (document.body.hasAttribute('data-mobile-nav-dragging')) return true
  return (
    event.target instanceof Element &&
    event.target.closest('[data-mobile-nav-dragging]') !== null
  )
}

/** Upper bound (px) of the "small floating widget" positional heuristic.
 * The real-world reference is dsh-pet's floating ball (kz2Bea_float,
 * position:fixed, measured 148x160 on the live profile page) — 160 would
 * sit exactly on that widget's edge; 200 leaves headroom for sibling
 * plugin widgets while a full-screen overlay (backdrop, sheets, dialogs)
 * still cannot pass. */
const FLOATING_WIDGET_MAX_PX = 200

/**
 * Floating-widget positional yield (2026-09-11, 悬浮窗拖动冲突 B 侧): plugins
 * ship draggable floating widgets (desktop-pet / floating-ball / draggable
 * panel shapes) that carry NO standard "draggable" DOM mark, yet the user
 * presses the widget itself — so the stroke's start target sits inside that
 * widget's layer. Draggable widgets almost always live in a SMALL
 * freely-positioned layer (position: fixed | absolute, own box ≤ 160px)
 * hovering above the page, so walk the ancestor chain from the event target:
 * the first small positioned ancestor counts as a floating widget and the
 * stroke yields (no arm, no touchmove preventDefault). Pairs with
 * dragMarkYields (cooperation mark) which needs no shape guessing.
 * Excluded: anything inside our own frame subtree — the FAB / backdrop /
 * drawer content carry their own gesture semantics and must never be
 * misread as floating widgets (the closed-state FAB sits in the start zone).
 * ponytail: no DOM-standard draggable signal exists; shape ≈ draggable is an
 * approximation with a known ceiling — a STATIC small positioned element
 * (e.g. a message badge) also yields, costing a stroke start under a
 * ≤160px dot; a REAL widget that misses (bigger layer, static positioning)
 * upgrades via the data-mobile-nav-dragging mark or by raising the cap.
 */
function findFloatingWidget(target: Element): Element | null {
  if (target.closest('[data-mobile-nav="frame"]') !== null) return null
  let el: Element | null = target
  while (el !== null) {
    if (el instanceof HTMLElement) {
      const cs = getComputedStyle(el)
      if (
        (cs.position === 'fixed' || cs.position === 'absolute') &&
        el.offsetWidth <= FLOATING_WIDGET_MAX_PX &&
        el.offsetHeight <= FLOATING_WIDGET_MAX_PX
      ) {
        return el
      }
    }
    el = el.parentElement
  }
  return null
}

function floatingWidgetYields(event: PointerEvent): boolean {
  return (
    event.target instanceof Element &&
    findFloatingWidget(event.target) !== null
  )
}

/**
 * Cache the follow geometry for a freshly locked stroke. Runs ONCE per
 * stroke (one getComputedStyle, plus one getBoundingClientRect only for the
 * cold-start fallback); the per-move path afterwards is write-only.
 *
 * CLOSE strokes follow from a px baseline read here. OPEN strokes cannot:
 * the host renders TWO different subtrees in the same sidebar column —
 * collapsed it is a ~206px rail holding only Task Board / SSH / Files /
 * Session log (79 nodes, ZERO `role=treeitem`), open it is the ~280px drawer
 * with the session tree and footer (389 nodes, 15 treeitems). Dragging the
 * closed column would only reveal the rail (measured 2026-08-29, the user's
 * "完全不同的 UI、没有真实会话、位置全乱" report). The open direction therefore
 * commits FIRST and follows AFTER (armOpenFollow), which is also why its
 * baseline must stay a percentage rather than a px value cached here.
 */
function startFollow(): void {
  // Unbind first: followDrawer survives across strokes (endStroke releases
  // the styles AFTER reset(), so reset must not clear it). Without this an
  // open stroke would inherit the binding left by the previous close-follow
  // and start following after all — exactly what the probe assertion
  // swipe.open-stroke-no-follow catches.
  followDrawer = null
  followEngaged = false
  openFollowArmed = false
  openFollowRefused = false
  // strokeRtl is read by the OPEN branch of applyFollow BEFORE it arms, so it
  // must be refreshed for every locked stroke — not only the close branch —
  // or an open stroke would inherit the previous stroke's reading direction.
  strokeRtl = frameRtl()
  const drawer = findDrawer()
  if (drawer === null) return
  // A closed stroke binds nothing here: the OPEN direction early-commits and
  // binds inside armOpenFollow, using a percentage baseline (the element's
  // width changes when React swaps the rail for the real drawer).
  if (!lockDrawerOpen) return
  followDrawer = drawer
  // The slot is 110% of the element's OWN width (the host's closed rule is
  // translateX(-110%), the extra 10% covering any shadow).
  //
  // Measuring the OPEN drawer is load-bearing (2026-08-29 seventh round,
  // user report 「左滑的时候会卡一下…会突然有出现半开不开的样子」 →
  // 「UI 会停在我最终滑动的地方，之后消失」). The previous baseline was a
  // slot observed on the CLOSED host, i.e. on the ~206px nav rail
  // (~-226.7px) — but the drawer being dragged is ~280px and parks at
  // ~-308px. followTranslate clamps at the slot, so the drag froze 81px
  // short of the edge: the drawer stopped under a still-moving finger
  // (「半开不开」), and the release then had to travel that remainder,
  // reading as a stall followed by a disappearance.
  //
  // Width is stable for the duration of a close stroke (no subtree swap
  // until the release commits), so a px baseline is safe here — unlike the
  // open direction, which must stay percentage-based because React swaps the
  // rail for the real drawer mid-stroke.
  const slot = (drawer.getBoundingClientRect().width * CLOSED_SLOT_PCT) / 100
  strokeClosedTx = strokeRtl ? slot : -slot
}

/**
 * Arm the OPEN follow: pin the drawer in its closed slot with an important
 * inline pair, THEN flip the host state in the same task. React mounts the
 * real ~280px drawer subtree while our inline transform holds it off-screen,
 * so the next move samples slide the genuine drawer — session tree and all —
 * out of the slot under the finger. Ordering matters: pin before the flip,
 * or the host's open rule (`transform: none`) paints the drawer at rest for
 * one frame and the user sees it snap into place before the follow starts.
 *
 * The backdrop and the FAB swap at the flip, which is the documented binary
 * behavior (spec review 缺陷 2: no opacity-following backdrop).
 */
/** True while the drawer subtree layout+paint is deliberately deferred by
 * the arm-time content-visibility split (see armOpenFollow). */
let cvDeferred = false
/** Pending reveal chain handle (double rAF); cancelled on dispose. */
let armRevealRaf = 0

/** Re-materialize the drawer contents after the mount-frame split. */
function revealDrawerContent(): void {
  if (!cvDeferred) return
  cvDeferred = false
  followDrawer?.style.removeProperty('content-visibility')
  const el = findDrawer()
  if (el !== null && el !== followDrawer) el.style.removeProperty('content-visibility')
}

function armOpenFollow(ctx: ClientContext): void {
  if (openFollowArmed || openFollowRefused) return
  const drawer = findDrawer()
  if (drawer === null || modalOpen() || takeoverActive()) {
    openFollowRefused = true
    return
  }
  followDrawer = drawer
  followEngaged = true
  drawer.style.setProperty('transition', 'none', 'important')
  const pinned = followOpenTransform(0.0001, strokeRtl)
  drawer.style.setProperty('transform', pinned ?? `translateX(-${CLOSED_SLOT_PCT}%)`, 'important')
  // Split the mount cost (2026-08-29, user report 「滑动不会立刻生效，而是卡
  // 那么零点几秒」): the toggle below synchronously mounts the 389-node
  // drawer subtree, and reconcile + style + layout + paint all land in ONE
  // long task — measured 308ms at 4x CPU throttle, a quarter-second of
  // frozen screen on a phone. content-visibility:hidden (set BEFORE the
  // flip, on the column that survives the subtree swap) makes the mount
  // frame skip subtree layout+paint — the panel BOX still paints and the
  // compositor keeps following the finger — and the contents materialize
  // two frames later via revealDrawerContent(), where the motion masks the
  // second (smaller) block. Ignored by browsers without support (no-op).
  drawer.style.setProperty('content-visibility', 'hidden', 'important')
  cvDeferred = true
  openFollowArmed = true
  ctx.layout.toggleSidebar()
  // Two frames (layout flush, then reveal). Kept in module state so a dispose
  // between them cancels the chain instead of revealing a drawer that no
  // longer belongs to this effect (issue #86).
  armRevealRaf = requestAnimationFrame(() => {
    armRevealRaf = requestAnimationFrame(() => {
      armRevealRaf = 0
      revealDrawerContent()
    })
  })
}

/**
 * Paint this move sample's follow position. Null mapping (legacy direction
 * or pulled back past the stroke origin) releases the inline styles so the
 * host transition is live again — the drawer springs to wherever the host
 * state puts it and the classification still owns the release. Re-engaging
 * after a null sample rewrites both inline properties, which also
 * self-heals anything that restored them mid-stroke (React re-render).
 *
 * Both properties MUST be written with `important` priority. The open state
 * is styled by our own `transform: none !important` (layout.css.ts — the
 * containing-block rule for the settings overlay), which outranks a plain
 * inline declaration: a normal `style.transform = ...` leaves the computed
 * transform at `none` and the drawer never moves. That is exactly how the
 * first follow implementation shipped invisible while every inline-string
 * assertion passed (2026-08-29) — assert COMPUTED transform, never
 * `element.style.transform`.
 */
function applyFollow(ctx: ClientContext, dx: number): void {
  if (!tracking || strokeMode !== 'drawer') return
  if (!lockDrawerOpen) {
    // OPEN direction: arm past the twitch threshold, then follow with the
    // percentage baseline (the element's width changes across the mount).
    const travel = strokeRtl ? -dx : dx
    if (!openFollowArmed) {
      if (travel < OPEN_FOLLOW_ARM_PX) return
      armOpenFollow(ctx)
      if (!openFollowArmed) return
    }
    const value = followOpenTransform(dx, strokeRtl)
    if (value === null) {
      // Pulled back past the origin: hold the drawer parked in its slot
      // rather than releasing (releasing would let the host animate it open
      // behind the finger). The release still classifies and may revert.
      followDrawer?.style.setProperty(
        'transform',
        `translateX(-${CLOSED_SLOT_PCT}%)`,
        'important',
      )
      return
    }
    followDrawer?.style.setProperty('transform', value, 'important')
    return
  }
  if (followDrawer === null) return
  const tx = followTranslate(strokeClosedTx, dx, strokeRtl, lockDrawerOpen)
  if (tx === null) {
    // Pulled back past the origin. Hold the drawer at rest instead of
    // releasing the inline pair: releasing would restore the host's .28s
    // transition mid-stroke, so a direction wobble would animate the drawer
    // and then jump when the finger crosses back — the same reason the open
    // branch pins instead of releasing.
    followEngaged = true
    followDrawer.style.setProperty('transition', 'none', 'important')
    followDrawer.style.setProperty('transform', 'translateX(0px)', 'important')
    return
  }
  followEngaged = true
  followDrawer.style.setProperty('transition', 'none', 'important')
  followDrawer.style.setProperty('transform', `translateX(${tx}px)`, 'important')
}

/**
 * Drop the inline follow styles. The host stylesheet retakes control: with
 * the transition restored, clearing the transform animates the drawer from
 * the finger position to whatever the CURRENT host state says. Called on
 * every end-stroke branch (revert: this IS the spring-back; commit: the
 * same-task retarget below overrides the initial leg before any paint).
 */
function releaseFollowStyles(): void {
  const el = followDrawer
  if (!followEngaged || el === null) return
  followEngaged = false
  el.style.removeProperty('transition')
  el.style.removeProperty('transform')
}

/** A close commit that is still animating to the closed slot before the host
 * state flips. The flip MUST wait: the sidebar column renders two mutually
 * exclusive subtrees (280px drawer when open, 206px nav rail when closed),
 * and React swaps them some ~200ms after the marker flips — measured
 * mid-animation at t≈200ms of a 280ms transition (width 280→206, tx jumped
 * -207.6→-181.9 as -110% re-resolved against the narrower rail). Flipping
 * first therefore replaces the drawer's content and retargets its transition
 * IN FLIGHT — user report 「最后抽屉样式突然消失,不是自然的动画收起」.
 * Late commit: animate the inline transform to the slot, flip only when the
 * drawer is already off-screen, then drop the inline pair. */
let pendingCommit: { el: HTMLElement; ctx: ClientContext; timer: number; target: string } | null = null

function finishPendingCommit(): void {
  const pending = pendingCommit
  if (pending === null) return
  pendingCommit = null
  window.clearTimeout(pending.timer)
  // The element may already be unmounted (React swaps the subtree at the
  // flip); stripping inline from a detached node is a harmless no-op.
  pending.el.style.removeProperty('transition')
  pending.el.style.removeProperty('transform')
  // If the host already closed while our animation ran (e.g. a genuine
  // backdrop tap inside the 280ms window), the flip already happened and a
  // blind toggle would RE-OPEN the drawer — skip it.
  const frame = getFrame()
  if (frame !== null && !frame.hasAttribute('data-sidebar-collapsed')) {
    pending.ctx.layout.toggleSidebar()
  }
}

/** Animate `el` to `targetTx` with our own transition, flip the host when it
 * lands. A repeat of the SAME intent inside the window is claimed by the
 * in-flight commit (no-op); a different intent settles it first. */
function commitWithAnimation(ctx: ClientContext, el: HTMLElement, targetTx: string): void {
  // Same target, same element = the drawer is already leaving that way. Let the
  // first commit land: force-settling here would flip the host state NOW and
  // then animate an already-closed drawer (Escape twice inside the 320ms window
  // used to toggle the host twice — the "晚提交" contract broken by us).
  const inFlight = pendingCommit
  if (inFlight !== null && inFlight.el === el && inFlight.target === targetTx) return
  finishPendingCommit()
  // prefers-reduced-motion: degrade the animation instead of adding one. This
  // cannot be left to CSS — the transition written below is inline
  // `!important`, which the stylesheet's reduce block cannot override, and the
  // gesture-close path reaches here without a reduce check (issue #86). Land
  // the exact same guarded commit, just without the animation beat.
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    fadeOverlayOut()
    cooldownUntil = performance.now() + COOLDOWN_MS
    pendingCommit = { el, ctx, timer: 0, target: targetTx }
    finishPendingCommit()
    return
  }
  el.style.setProperty('transition', `transform ${COMMIT_ANIM_MS}ms ease-in-out`, 'important')
  // Flush the before-change style ONLY when an inline transform is already
  // present — that is the case this flush exists for: a finger-follow wrote the
  // current position in this same frame, and the transition has to start from it
  // instead of from a coalesced recalc. A click/Escape/backdrop close starts from
  // the stylesheet's open state (`transform: none`, no inline value), so the
  // transition is well defined without forcing a reflow of the whole column —
  // measured 105ms of `onDrawerClick` on a 3.5k-node session, growing with the
  // conversation.
  if (el.style.getPropertyValue('transform') !== '') void el.getBoundingClientRect()
  el.style.setProperty('transform', targetTx, 'important')
  // Fade the dimming in step with the slide-out: the marker flips only when
  // the drawer lands, so without this the screen would go drawer-then-dark
  // (backdrop snapping away ~260ms AFTER the drawer already left).
  fadeOverlayOut()
  cooldownUntil = performance.now() + COOLDOWN_MS
  pendingCommit = {
    el,
    ctx,
    timer: window.setTimeout(finishPendingCommit, COMMIT_ANIM_MS + 40),
    target: targetTx,
  }
}

/** Terminal close commit: animate the drawer into the closed slot, then flip
 * the host. The slot must be the host's REAL closed rule (-110%), because
 * after the flip the closed host paints exactly this value — dropping the
 * inline pair must be a no-op, not a jump. */
function commitFollowClose(ctx: ClientContext): void {
  const el = followDrawer
  followDrawer = null
  followEngaged = false
  if (el === null) {
    // No follow binding (defensive): fall back to the immediate flip.
    releaseFollowStyles()
    ctx.layout.toggleSidebar()
    cooldownUntil = performance.now() + COOLDOWN_MS
    return
  }
  const target = strokeRtl
    ? `translateX(${CLOSED_SLOT_PCT}%)`
    : `translateX(-${CLOSED_SLOT_PCT}%)`
  commitWithAnimation(ctx, el, target)
}

/** Animate an OPEN drawer into its closed slot and flip the host state once it
 * has landed. Every non-gesture closer (backdrop tap, Escape, navigation taps)
 * routes through this, so a click close animates exactly like a swipe close:
 * the host swaps the pane's subtree AND drops its surface (transparent,
 * borderless, content display:none) at the marker flip, so a plain CSS
 * transition would slide out an invisible shell - the same reason the gesture
 * close uses a late commit (eighth round, 2026-08-29). The OPEN direction needs
 * none of this: the host keeps the pane's visuals until the marker flips, so
 * its own transform transition plays (spec 2026-08-27, A 档).
 * Returns false when the caller must fall back to a plain toggleSidebar(): the
 * drawer is already closed (that call would OPEN it) or the user asked for
 * reduced motion, where the spec degrades the animation instead of adding one. */
export function closeDrawerAnimated(ctx: ClientContext): boolean {
  if (!drawerOpen()) return false
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return false
  const drawer = findDrawer()
  if (drawer === null) return false
  const target = frameRtl()
    ? `translateX(${CLOSED_SLOT_PCT}%)`
    : `translateX(-${CLOSED_SLOT_PCT}%)`
  commitWithAnimation(ctx, drawer, target)
  return true
}

/** Cancel paths: styles back to the host, pointer state to idle. An armed
 * open follow has already flipped the host state, so a cancel must also
 * toggle it back — release the inline pair first so the host transition
 * animates home from the finger position within the same task. */
function abortStroke(ctx: ClientContext | null, immediate = false): void {
  if (pendingCommit !== null) {
    // A terminal commit is animating: this stroke already ended. Only a
    // teardown (dispose) must settle it synchronously; otherwise let the
    // timer land the flip.
    if (immediate) finishPendingCommit()
    return
  }
  const wasArmed = openFollowArmed
  openFollowArmed = false
  openFollowRefused = false
  revealDrawerContent()
  if (wasArmed && ctx !== null && followDrawer !== null && !immediate) {
    // Armed open stroke aborted mid-follow: the host is already open, and
    // flipping now would swap the subtree mid-motion — same artifact as the
    // close release. Animate back into the slot, then flip.
    reset()
    commitFollowClose(ctx)
    return
  }
  releaseFollowStyles()
  reset()
  if (wasArmed && ctx !== null) {
    ctx.layout.toggleSidebar()
    cooldownUntil = performance.now() + COOLDOWN_MS
  }
}

/** Start a stroke; returns true when it may be tracked. */
function beginStroke(
  event: PointerEvent,
  rtl: boolean,
  viewportWidthPx: number,
): boolean {
  if (onCooldown()) return false
  if (modalOpen()) return false
  if (takeoverActive()) return false
  // A live text selection owns the stroke (a selection-handle drag is
  // horizontally dominant and geometrically identical to a swipe — #43,
  // iPad WebKit): yield before any geometric test. This also blocks
  // swipe-open while a stale selection is alive; one tap collapses the
  // selection everywhere, and backdrop tap-to-close is unaffected (a tap
  // never reaches tryLock).
  if (selectionOwnsStroke()) return false
  // A live draggable (data-mobile-nav-dragging, see dragMarkYields) owns the
  // stroke: yield before any geometric test so the drawer cannot arm for a
  // drag that starts inside the start zone.
  if (dragMarkYields(event)) return false
  // Plugin-shipped draggable floating widgets (pet / floating-ball shapes
  // without any cooperation mark) yield the same way, via the positional
  // heuristic — the user pressed the widget itself.
  if (floatingWidgetYields(event)) return false
  if (!(event.target instanceof Element)) return false
  // A stroke beginning inside a genuinely horizontally scrollable container
  // belongs to that scroller (the stats line, a message code block, any
  // carousel): yield it so its native horizontal pan survives — and so the
  // wide 45%-of-viewport start zone cannot turn a strip scroll into a
  // drawer open (failure scenario C1). Applies to both branches: inside the
  // drawer the same "scroller owns horizontal" semantics should hold.
  if (findHorizontalScroller(chainFrom(event.target)) !== null) return false
  const open = drawerOpen()
  const filesZonePx = startZonePxFor(viewportWidthPx, FILES_ZONE_RATIO)
  if (open) {
    // Close strokes may start ANYWHERE over the frame (2026-08-29 sixth
    // round, user report 「希望打开抽屉之后以外的部分可以进行左滑」). The
    // previous gate required the start point inside the drawer's own
    // geometry and explicitly rejected the backdrop, so the ~28% of the
    // screen beside the drawer swallowed every swipe — combined with the
    // leftward verdict being refused, closing felt impossible. Nothing else
    // owns a horizontal stroke while the drawer is open (the conversation is
    // behind the backdrop), so the whole frame is fair game.
    //
    // 2026-09-13 narrowing (user decision): the RIGHT zone beside the drawer
    // now belongs to the files gesture. Its leftward stroke must NOT close
    // the drawer (the files panel would mount UNDER the drawer — z-1100 —
    // and be invisible); its rightward stroke keeps the animated close via
    // classifyFilesSwipe's 'close' verdict. The left zone / drawer content
    // keeps every pre-existing drawer behavior. Drawer-mode follow painting
    // only ever maps LEFTWARD (followTranslate's close branch), which is
    // exactly the direction the narrowing removes from this zone — no close
    // follow is lost by the routing (probe swipe.close-follow-reaches-slot
    // was relocated into the drawer family accordingly).
    //
    // Tap-to-close on the backdrop is unaffected: a tap never reaches
    // tryLock, so endStroke returns on !wasTracking without writing a
    // consume mark, and the document-capture click handler passes backdrop /
    // FAB clicks through unconditionally anyway.
    const frame = getFrame()
    if (frame === null) return false
    const rect = frame.getBoundingClientRect()
    if (event.clientX < rect.left || event.clientX > rect.right) return false
    if (event.clientY < rect.top || event.clientY > rect.bottom) return false
    // A session-row action menu (kebab) owns its own tap.
    if (event.target.closest('[class*="sessionRow"] button') !== null) return false
    //
    // 2026-09-17 (owner: the judgment zone must end at the drawer's right
    // edge): inside the drawer BODY the drawer family always wins. The files
    // zone is viewport-relative (0.45 from the right edge = x >= 214 at 390px)
    // and overlaps the 280px drawer by 66px, so that sliver of the drawer's own
    // surface used to answer 'none' to a leftward drag - touching the drawer and
    // dragging left did nothing. Routing on the drawer's live rect (not a
    // hardcoded width) keeps the rule true at every viewport: at >= 509px the
    // 0.45 zone starts right of the drawer and nothing changes. Outside the
    // body the files routing, including its leftward no-op, is untouched.
    const drawer = findDrawer()
    const drawerRect = drawer === null ? null : drawer.getBoundingClientRect()
    const insideDrawer =
      drawerRect !== null && event.clientX >= drawerRect.left && event.clientX <= drawerRect.right
    strokeMode = openStateStartMode(
      insideDrawer,
      filesZoneHit(event.clientX, viewportWidthPx, rtl, filesZonePx),
    )
  } else if (hitTestStart(event.clientX, viewportWidthPx, rtl, { startZonePx: startZonePxFor(viewportWidthPx) })) {
    strokeMode = 'drawer'
  } else if (filesZoneHit(event.clientX, viewportWidthPx, rtl, filesZonePx)) {
    strokeMode = 'files'
  } else {
    return false
  }
  trackingPointer = event.pointerId
  tracking = false
  startX = event.clientX
  startY = event.clientY
  samples = [{ t: event.timeStamp, x: event.clientX }]
  return true
}

/**
 * Axis-lock the stroke once its dominant axis has moved LOCK_PX. Horizontal
 * dominance (|dx| > |dy|) locks to X and is tracked; vertical dominance
 * abandons the stroke back to native scrolling (browser takes over, no
 * further preventDefault). Once locked the axis never re-decides — matching
 * MUI's UNCERTAINTY_THRESHOLD semantics.
 */
function tryLock(event: PointerEvent): boolean {
  const dx = event.clientX - startX
  const dy = event.clientY - startY
  if (Math.max(Math.abs(dx), Math.abs(dy)) < LOCK_PX) return false
  // Second timing window for the drag mark (same pattern as the selection
  // check in onPointerMove): the dragger often raises the mark in its own
  // pointerdown/move handler, i.e. AFTER our beginStroke ran. Re-check at
  // every lock attempt so the stroke yields before the axis locks.
  if (dragMarkYields(event) || floatingWidgetYields(event)) {
    reset()
    return false
  }
  if (Math.abs(dx) <= Math.abs(dy)) {
    // Vertical-dominant: hand the touch back to scrolling.
    reset()
    return false
  }
  tracking = true
  lockDrawerOpen = drawerOpen()
  if (strokeMode === 'files') {
    lockFilesOpen = filesPanelOpen()
    // An OPEN drawer shares this stroke (the right-edge rightward close):
    // bind the drawer's close follow so the commit animates exactly like
    // today's right-zone close. applyFollow stays mode-guarded, so no follow
    // ever paints for files strokes — the LEFTWARD narrowing stroke gets no
    // painting either, which is the point (it must not drag the drawer).
    if (lockDrawerOpen) startFollow()
    markStrokeLocked()
    return true
  }
  // Publish the lock to the host handlers (see gesture-guard.ts): they run
  // EARLIER in this release event's capture phase, before endStroke writes
  // any consume mark — the flag is their only ordering-proof yield signal
  // (audit S0/S1).
  markStrokeLocked()
  startFollow()
  return true
}

/** Append a sample and prune the window. */
function pushSample(event: PointerEvent): void {
  samples.push({ t: event.timeStamp, x: event.clientX })
  const cutoff = event.timeStamp - VELOCITY_WINDOW_MS
  let i = 0
  while (i < samples.length - 1 && samples[i]!.t < cutoff) i += 1
  if (i > 0) samples = samples.slice(i)
}

/**
 * Release the stroke: classify, then either commit or spring back.
 *
 * B 档 ordering is load-bearing: the verdict is computed FIRST (the follow
 * position IS dx, so classifySwipe decides complete-vs-revert exactly as in
 * A 档), then the inline follow styles are dropped — restoring the host
 * transition and clearing the transform starts an animation toward the
 * drawer's CURRENT host state — and only then does the commit flip the host
 * state, retargeting that transition within the SAME task. No paint happens
 * between the two, so the user sees one continuous motion from the finger
 * position into the final state; a reverted stroke simply animates home.
 *
 * An ARMED OPEN follow inverts the commit: the host state was already
 * flipped at arm time, so a positive verdict must NOT toggle again (that
 * would close the drawer the user just pulled out) and a negative verdict
 * must toggle BACK. Either way the inline release comes first, so the host
 * transition animates from the finger position to whichever state wins.
 */
function endStroke(
  ctx: ClientContext,
  event: PointerEvent,
  rtl: boolean,
  viewportWidthPx: number,
): void {
  const wasTracking = tracking
  const armedOpen = openFollowArmed
  openFollowArmed = false
  openFollowRefused = false
  // The stroke's mode and panel flag must be captured BEFORE reset(): reset()
  // rewrites strokeMode to 'drawer' and clears lockFilesOpen, and the verdict
  // below branches on them. Harmless for drawer strokes (whose mode already
  // reads 'drawer'), fatal for files strokes — the verdict silently degraded
  // to the drawer classifier and every files commit turned into 'none'.
  const filesMode = strokeMode === 'files'
  const filesOpenAtLock = lockFilesOpen
  // Velocity must be computed before reset() clears the samples.
  const vel = slidingVelocity(samples, VELOCITY_WINDOW_MS, event.timeStamp)
  // Distance is measured from the stroke START (not the axis-lock point):
  // the slop is an activation gate, not travel that should consume the
  // user's swipe distance. Measuring from the lock point made the effective
  // travel = slop + threshold (e.g. 4px + 78px), so a 78px threshold
  // actually needed ~82px+ of finger travel — the "feels like half the
  // screen" complaint. From the start, a 78px threshold is a 78px swipe.
  const dx = event.clientX - startX
  const dy = event.clientY - startY
  reset()
  if (!wasTracking) {
    // A stroke that armed the follow is by definition locked, so this branch
    // cannot leave the host state flipped — but keep the invariant explicit.
    if (armedOpen) {
      commitFollowClose(ctx)
    }
    return
  }
  const modal = modalOpen()
  // An armed open follow has already flipped the marker, so classifySwipe
  // must still be asked the question the USER answered: it was a closed
  // drawer when the stroke began (lockDrawerOpen), which is what the stored
  // flag holds — never re-read drawerOpen() here.
  const verdict =
    modal || (!armedOpen && onCooldown())
      ? ('none' as const)
      : filesMode
        ? classifyFilesSwipe(
            {
              distanceRatio: FILES_DISTANCE_RATIO,
              velocity: FILES_VELOCITY,
              lockPx: LOCK_PX,
              viewportWidthPx,
              panelOpen: filesOpenAtLock,
              drawerOpen: lockDrawerOpen,
              // The drawer-open cell commits a drawer close, so it keeps the
              // drawer's own close distance (the spec's "┍ identical to
              // today's close").
              drawerCloseDistanceRatio: CLOSE_DISTANCE_RATIO,
            },
            { dx, dy, velX: vel },
            rtl,
          )
        : classifySwipe(
            {
              openDistanceRatio: OPEN_DISTANCE_RATIO,
              closeDistanceRatio: CLOSE_DISTANCE_RATIO,
              openVelocity: OPEN_VELOCITY,
              closeVelocity: CLOSE_VELOCITY,
              lockPx: LOCK_PX,
              viewportWidthPx,
              drawerOpen: lockDrawerOpen,
            },
            { dx, dy, velX: vel },
            rtl,
          )
  // The mount-frame split must never survive into a terminal state: reveal
  // the contents (no-op unless armed this stroke) before any release or
  // commit animation.
  revealDrawerContent()
  // Terminal styles, per verdict. CLOSE commits are LATE: animate the inline
  // transform into the closed slot and flip the host only when the drawer is
  // already off-screen (commitFollowClose → commitWithAnimation) — flipping
  // first swaps the sidebar subtree mid-animation (measured: width 280→206
  // at t≈200ms of the 280ms transition, tx jumped backward). OPEN verdicts
  // and the revert/modal/cooldown paths keep the plain release: the host
  // stays in its current state, so its own transition finishes the motion
  // and no subtree swap can be in flight. Every path either releases or
  // hands the inline pair to the pending commit — it can never leak.
  if (armedOpen) {
    // The host is already open (early commit). Keep it on 'open', otherwise
    // animate back into the slot and flip closed.
    if (verdict === 'open') {
      releaseFollowStyles()
      cooldownUntil = performance.now() + COOLDOWN_MS
    } else {
      commitFollowClose(ctx)
    }
    if (event.target instanceof Element) markStrokeConsumed(event.target)
    return
  }
  if (!(event.target instanceof Element)) return
  if (verdict === 'close') {
    // Mark the stroke consumed so the tap's synthetic click cannot
    // double-toggle or navigate a row. The mark walks the ancestor chain up
    // to the DRAWER (not the frame): the synthetic click always lands on the
    // stroke's own start target (left-edge start zone / drawer content), never
    // on the backdrop — but the backdrop is a frame child, so marking up to
    // the frame would make the host treat a genuine backdrop tap within the
    // 300ms window as consumed and swallow the close (the "tap twice to close"
    // bug). Marking stays IMMEDIATE even though the flip is late: the mark
    // snapshots the chain now, and the synthetic click arrives within ~10ms.
    markStrokeConsumed(event.target)
    commitFollowClose(ctx)
    return
  }
  releaseFollowStyles()
  if (verdict === 'open') {
    // Unreachable for a tracked stroke (an unarmed stroke is by definition
    // drawer-open at start), but keep the host-service commit symmetric.
    markStrokeConsumed(event.target)
    ctx.layout.toggleSidebar()
    cooldownUntil = performance.now() + COOLDOWN_MS
  }
  if (verdict === 'files') {
    // The files-panel commit: open (both closed + leftward) or close (panel
    // open + rightward). Toggle FIRST, then mark: the consume mark walks the
    // stroke-start target's ancestors up to the FRAME, and the frame is also
    // an ancestor of the host control the toggle clicks programmatically —
    // marking first matches that very click through the shared upper chain
    // segments and swallows it, so the panel never opens (0.1.5 live
    // observation). The mark still covers the browser's own synthetic click,
    // which is dispatched asynchronously after the release (the send button,
    // a row button, … sit under the release point).
    filesToggleFn()
    markStrokeConsumed(event.target)
    cooldownUntil = performance.now() + COOLDOWN_MS
  }
  if (filesMode && verdict === 'none') {
    // A 'none' files release is still a gesture (panel open + leftward, or
    // too short): consume its synthetic click so it cannot flip the panel
    // through the host toggle under the release point. This must run AFTER
    // the commit branches above, never before a programmatic toggle click.
    markStrokeConsumed(event.target)
  }
}

/**
 * Mark the released stroke so its synthetic click cannot re-toggle the drawer
 * or activate a row.
 *
 * The mark walks the ancestor chain up to the DRAWER when the stroke started
 * inside it: the backdrop is a frame child, so stopping at the frame would
 * make the host treat a genuine backdrop tap within the window as consumed
 * and swallow the close (the "tap twice to close" bug). A stroke that started
 * OUTSIDE the drawer (the left-edge start zone, or — since closing accepts
 * the whole frame — the backdrop itself) has no drawer in its chain, so the
 * walk would otherwise run all the way to the document root and briefly
 * shadow every tap on the page; the frame is the tightest correct stop for
 * those, and it is what must be marked anyway, because a backdrop-started
 * close stroke needs its own overlay click consumed.
 */
function markStrokeConsumed(target: Element): void {
  const drawer = findDrawer()
  const upTo =
    drawer !== null && drawer.contains(target) ? drawer : getFrame() ?? null
  markGestureConsumed(target, CONSUME_WINDOW_MS, upTo)
  consumedEl = target
}

/** Forget stroke state (called on cancel / visibility change / blur). */
function reset(): void {
  trackingPointer = 0
  tracking = false
  samples = []
  strokeMode = 'drawer'
  lockFilesOpen = false
  clearStrokeLocked()
}

/** The logical reading direction of the frame (RTL support). */
function frameRtl(): boolean {
  const frame = getFrame()
  return frame !== null && getComputedStyle(frame).direction === 'rtl'
}

/** Install the gesture layer for the current mobile breakpoint. */
export function installSidebarSwipe(ctx: ClientContext, filesToggle: () => boolean): void {
  installMobileEffect(ctx, 'dsh-web-mobile: sidebar swipe gestures', () => {
    filesToggleFn = filesToggle
    const viewportWidth = (): number =>
      window.innerWidth || document.documentElement.clientWidth || 0

    const onPointerDown = (event: PointerEvent): void => {
      // A new pointer starts a new interaction epoch: drop the previous
      // stroke's click gate. When the browser never delivers the synthetic
      // click (iOS shells suppress it after a swipe), this — together with
      // the short CONSUME_WINDOW_MS — keeps the next genuine tap alive
      // instead of eating it at the document-capture click handler.
      consumedEl = null
      clearStrokeLocked() // belt-and-suspenders: a lost stroke must not leak its lock into this epoch
      if (event.pointerType !== 'touch' && event.pointerType !== 'pen') return
      // A second finger means the browser owns this interaction (pinch zoom),
      // and a two-finger drag is never a drawer swipe. Merely ignoring the
      // extra pointer would keep the stroke alive — and with it the
      // touchmove preventDefault below, which cancels the native pinch. On
      // iOS that pinch is the only way back out of a zoom, so fighting it
      // recreates exactly the trap #45 reported. Hand the whole interaction
      // back instead.
      if (trackingPointer !== 0 && trackingPointer !== event.pointerId) {
        abortStroke(ctx)
        return
      }
      beginStroke(event, frameRtl(), viewportWidth())
    }

    const onPointerMove = (event: PointerEvent): void => {
      if (event.pointerId !== trackingPointer) return
      // A modal may rise mid-stroke (e.g. an a11y trap opening) — spec review
      // 缺陷 1's guard, now per-MOVE because B 档 paints a transform the
      // modal must not inherit: abandon and spring the drawer back.
      if (modalOpen() || takeoverActive()) {
        abortStroke(ctx)
        return
      }
      if (!tracking) {
        // A long-press selection can appear AFTER pointerdown but BEFORE the
        // axis lock (#43 second timing window): abandon the stroke and hand
        // the touch back so the handles become draggable (reset() also lifts
        // the touchmove preventDefault). Once locked the gesture stays
        // committed — a selection never appears mid-swipe.
        if (selectionOwnsStroke()) {
          reset()
          return
        }
        if (tryLock(event)) {
          pushSample(event)
          applyFollow(ctx, event.clientX - startX)
        }
      } else {
        pushSample(event)
        applyFollow(ctx, event.clientX - startX)
      }
    }

    const onPointerUp = (event: PointerEvent): void => {
      if (event.pointerId !== trackingPointer) return
      endStroke(ctx, event, frameRtl(), viewportWidth())
    }

    const onPointerCancel = (event: PointerEvent): void => {
      if (event.pointerId !== trackingPointer) return
      abortStroke(ctx)
    }

    // The browser may synthesize a click a few ms after the stroke's
    // pointerup. The host overlay handlers and the FAB / backdrop element
    // listeners would treat it as a tap; swallow it at document capture so
    // a swipe can never toggle twice or navigate a row. Non-gesture taps
    // (no live mark) pass through untouched.
    //
    // A click whose target is (or is inside) the backdrop or the FAB is
    // NEVER a gesture's synthetic click: the stroke start is always the
    // left-edge start zone or the drawer content, never the backdrop (outside
    // the drawer, on the right) or the FAB. The mark chain can reach them
    // in degenerate hit-test cases (e.g. a stroke starting on a point where
    // the empty drawer does not register as the event target), and
    // swallowing that click would break the "tap the backdrop to close"
    // path — the "tap twice to close" bug. Let those clicks through.
    const onClick = (event: MouseEvent): void => {
      if (consumedEl === null) return
      if (!(event.target instanceof Element)) return
      // A genuine backdrop / FAB tap is always let through: their own click
      // listeners toggle the drawer, and a consume mark that walked to the
      // document root would otherwise swallow it ("tap twice to close").
      // The one exception is a click on the overlay element that STARTED the
      // just-committed stroke — since close strokes may begin anywhere over
      // the frame, the backdrop can now be the stroke's own start target,
      // and letting its synthetic click through would re-toggle the drawer
      // straight back open.
      const overlay = event.target.closest(
        '[data-mobile-nav="backdrop"], [data-mobile-nav="fab"]',
      )
      if (overlay !== null && !overlay.contains(consumedEl)) return
      if (!consumeIfGestured(event)) return
      event.stopPropagation()
      event.preventDefault()
      consumedEl = null
    }

    const onVisibility = (): void => {
      if (document.hidden) abortStroke(ctx)
    }

    // Edge-touch priority, from the AXIS LOCK onward only: once the stroke is
    // known horizontal, preventDefault (passive:false) stops the browser from
    // claiming it as a scroll, keeping the pointer stream intact (iOS
    // UIScreenEdgePanGestureRecognizer semantics). Before the lock the handler
    // must stay out of the way entirely: canceling a touchmove suppresses the
    // touch point's default action for the WHOLE gesture (Touch Events
    // cancelability), and WebKit does not resume scrolling after reset() —
    // preventing here made both 45% start zones dead for scrolling on iOS
    // (#222). Multi-touch is never prevented: two fingers mean a pinch, and
    // the pointerdown guard above plus this check hand it back (#46).
    const onTouchMove = (event: TouchEvent): void => {
      if (trackingPointer === 0) return
      if (event.touches.length !== 1 || !event.cancelable || document.hidden) {
        abortStroke(ctx)
        return
      }
      if (!tracking) return
      event.preventDefault()
    }

    document.addEventListener('pointerdown', onPointerDown, true)
    document.addEventListener('pointermove', onPointerMove, true)
    document.addEventListener('pointerup', onPointerUp, true)
    document.addEventListener('pointercancel', onPointerCancel, true)
    document.addEventListener('click', onClick, true)
    document.addEventListener('touchmove', onTouchMove, { capture: true, passive: false })
    const onBlur = (): void => abortStroke(ctx)
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('blur', onBlur)

    return () => {
      document.removeEventListener('pointerdown', onPointerDown, true)
      document.removeEventListener('pointermove', onPointerMove, true)
      document.removeEventListener('pointerup', onPointerUp, true)
      document.removeEventListener('pointercancel', onPointerCancel, true)
      document.removeEventListener('click', onClick, true)
      document.removeEventListener('touchmove', onTouchMove, { capture: true } as EventListenerOptions)
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('blur', onBlur)
      if (armRevealRaf !== 0) {
        cancelAnimationFrame(armRevealRaf)
        armRevealRaf = 0
      }
      abortStroke(ctx, true)
    }
  })
}
