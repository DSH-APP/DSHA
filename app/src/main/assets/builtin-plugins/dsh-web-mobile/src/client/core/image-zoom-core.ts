// image-zoom-core.ts — DOM-free math for the image lightbox zoom.
//
// Deliberately ZERO imports and no DOM globals: the lightbox effect
// (effects/image-lightbox-zoom.ts) owns the listeners and the CSS transform,
// this module owns only the numbers, so node:test can pin the geometry with
// no browser. Conventions used by every function here:
//  - x/y are CSS translate values in the image's own coordinate space, i.e.
//    the translate3d() the effect writes before scale(s);
//  - the image is rendered with transform-origin 50% 50%, so a point p given
//    relative to the image centre lands at (x + s * p, y + s * p);
//  - an "anchor" is such a point in that same centre-relative space (the
//    effect converts a screen-space midpoint by subtracting the image's
//    untransformed centre before calling zoomAround);
//  - scale is clamped to [MIN_SCALE, MAX_SCALE]; at MIN_SCALE the image is
//    centred again, so no translation may survive.

export type ZoomState = { scale: number; x: number; y: number }
export type Point = { x: number; y: number }
export type Size = { width: number; height: number }

export const MIN_SCALE = 1
export const MAX_SCALE = 4
export const DOUBLE_TAP_SCALE = 2.5

/** Clamp a raw scale factor into the supported range (NaN collapses to 1:1). */
export function clampScale(n: number): number {
  if (Number.isNaN(n)) return MIN_SCALE
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, n))
}

/** The neutral 1:1 state. A fresh object every call: callers may hold it. */
export function identityZoom(): ZoomState {
  return { scale: MIN_SCALE, x: 0, y: 0 }
}

/**
 * Scale by `factor` while keeping `anchor` (centre-relative, see the header)
 * at the same screen position. The new translation is derived from the fixed
 * point relation x' + s' * p = x + s * p, so no iteration or accumulated
 * drift is involved. Clamping to MIN_SCALE recentres the image; clamping to
 * MAX_SCALE keeps the anchored relation at the clamped scale.
 */
export function zoomAround(state: ZoomState, factor: number, anchor: Point): ZoomState {
  if (!Number.isFinite(factor) || factor <= 0) return { ...state }
  if (!(state.scale > 0)) return identityZoom()
  const nextScale = clampScale(state.scale * factor)
  if (nextScale === MIN_SCALE) return identityZoom()
  const ratio = nextScale / state.scale
  return {
    scale: nextScale,
    x: anchor.x - (anchor.x - state.x) * ratio,
    y: anchor.y - (anchor.y - state.y) * ratio,
  }
}

/**
 * Keep a zoomed image from being dragged off its viewport. The allowance per
 * axis is half of the overflow the current scale creates, so the image edge
 * can reach the viewport edge but never pass it. A 1:1 image cannot move at
 * all. `viewport` is the visible box the image is allowed to travel within
 * (the effect passes the visible viewport size, not a cached box).
 */
export function clampTranslation(state: ZoomState, viewport: Size): ZoomState {
  const scale = clampScale(state.scale)
  if (scale === MIN_SCALE) return identityZoom()
  const width = Number.isFinite(viewport.width) && viewport.width > 0 ? viewport.width : 0
  const height = Number.isFinite(viewport.height) && viewport.height > 0 ? viewport.height : 0
  const boundX = ((scale - 1) * width) / 2
  const boundY = ((scale - 1) * height) / 2
  const x = Number.isFinite(state.x) ? Math.min(boundX, Math.max(-boundX, state.x)) : 0
  const y = Number.isFinite(state.y) ? Math.min(boundY, Math.max(-boundY, state.y)) : 0
  return { scale, x, y }
}

/**
 * Ratio between two pinch spans, each given as the vector between the two
 * touches (p2 - p1). A degenerate previous span (both fingers on one point)
 * returns 1 instead of dividing by zero.
 */
export function pinchFactor(prev: Point, next: Point): number {
  const from = Math.hypot(prev.x, prev.y)
  const to = Math.hypot(next.x, next.y)
  if (from === 0) return 1
  if (!Number.isFinite(from) || !Number.isFinite(to)) return 1
  return to / from
}

/** The double-tap step: 1:1 zooms to DOUBLE_TAP_SCALE, anything else resets. */
export function doubleTapTarget(state: ZoomState): ZoomState {
  return state.scale <= MIN_SCALE ? { scale: DOUBLE_TAP_SCALE, x: 0, y: 0 } : identityZoom()
}
