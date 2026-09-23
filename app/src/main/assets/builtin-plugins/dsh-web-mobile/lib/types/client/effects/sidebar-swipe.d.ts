import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
/**
 * The zone in pixels for a given viewport width (pure, exported for the
 * decision-table tests). Rounded so the probe boundary assertions stay
 * integral (390px → Math.round(175.5) = 176).
 */
export declare function startZonePxFor(viewportWidthPx: number, ratio?: number): number;
export interface SwipeThresholds {
    openDistanceRatio: number;
    closeDistanceRatio: number;
    velocityWindowMs: number;
    openVelocity: number;
    closeVelocity: number;
    lockPx: number;
    cooldownMs: number;
    startZonePx: number;
}
/**
 * Pure decision: what does this stroke do, given the drawer state?
 * `dx`/`dy` are raw pointer deltas (RTL mirrors X through `rtl`), `velX` is
 * the raw recent-window X velocity. The stroke must be locked horizontal
 * (|dx| > |dy| and past the lock slop) and direction-consistent; then
 * distance OR velocity wins, with the drawer-state-specific threshold.
 */
export declare function classifySwipe(t: SwipeThresholds & {
    viewportWidthPx: number;
    drawerOpen: boolean;
}, m: {
    dx: number;
    dy: number;
    velX: number;
}, rtl: boolean): 'open' | 'close' | 'none';
/** Threshold shape for the files classifier (pure, node:testable). */
export interface FilesThresholds {
    distanceRatio: number;
    velocity: number;
    lockPx: number;
    viewportWidthPx: number;
    /** Files panel mounted at lock time. */
    panelOpen: boolean;
    /** Drawer open at lock time. */
    drawerOpen: boolean;
    /**
     * Distance gate for the drawer-open rightward cell. That cell commits a
     * DRAWER close, so it rides the drawer's own CLOSE_DISTANCE_RATIO (0.13),
     * not the files panel's 0.16: one physical stroke must judge the same
     * wherever it starts. Optional — defaults to `distanceRatio`.
     */
    drawerCloseDistanceRatio?: number;
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
export declare function classifyFilesSwipe(t: FilesThresholds, m: {
    dx: number;
    dy: number;
    velX: number;
}, rtl: boolean): 'open' | 'close' | 'files' | 'none';
/**
 * Recent-window instantaneous velocity (px/ms) from the tail of the last
 * `windowMs` milliseconds of samples, up to `now`. Sliding X per ms between
 * the LAST TWO in-window samples — the end-of-stroke slope — so a long slow
 * drag then a quick flick reports the flick, not the drag average. Samples
 * older than the window are ignored. Fewer than two in-window samples → 0.
 */
export declare function slidingVelocity(samples: Array<{
    t: number;
    x: number;
}>, windowMs: number, now: number): number;
/**
 * Geometric start-hit test: the pointer went down in the left edge start
 * zone (when the drawer is closed) or inside the drawer content area (when
 * open). Pure and viewport-relative so it is unit-testable; the runtime
 * variant additionally checks the drawer geometry via the DOM.
 */
export declare function hitTestStart(clientX: number, viewportWidthPx: number, rtl: boolean, t: Pick<SwipeThresholds, 'startZonePx'>): boolean;
/**
 * Geometric start-hit test for the FILES gesture: the pointer went down in
 * the RIGHT edge zone (RTL: LEFT) — the exact mirror of hitTestStart. Pure
 * and viewport-relative.
 */
export declare function filesZoneHit(clientX: number, viewportWidthPx: number, rtl: boolean, zonePx: number): boolean;
/** Which gesture family owns a stroke that begins while the drawer is OPEN.
 * Inside the drawer body the drawer family always wins (owner's rule
 * 2026-09-17: the drawer's own surface must answer a leftward drag, whether or
 * not the viewport-ratio files zone overlaps it — at 390px that zone starts at
 * x=214, inside the 280px drawer); outside the body the right zone keeps its
 * files routing and its deliberate leftward 'none' verdict (2026-09-13
 * narrowing). */
export declare function openStateStartMode(insideDrawer: boolean, inFilesZone: boolean): 'drawer' | 'files';
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
export declare function followTranslate(closedTx: number, dx: number, rtl: boolean, drawerOpen: boolean): number | null;
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
export declare function followOpenTransform(travelPx: number, rtl: boolean): string | null;
/**
 * Minimal ancestor snapshot for the horizontal-scroller walk. Plain data on
 * purpose: the pure walk below is node:testable, and the runtime maps real
 * Elements onto this shape (chainFrom) before calling it.
 */
export interface SwipeChainNode {
    parent: SwipeChainNode | null;
    scrollWidth: number;
    clientWidth: number;
    overflowX: string;
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
export declare function findHorizontalScroller(node: SwipeChainNode | null): SwipeChainNode | null;
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
export declare function selectionOwnsStroke(): boolean;
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
export declare function closeDrawerAnimated(ctx: ClientContext): boolean;
/** Install the gesture layer for the current mobile breakpoint. */
export declare function installSidebarSwipe(ctx: ClientContext, filesToggle: () => boolean): void;
//# sourceMappingURL=sidebar-swipe.d.ts.map