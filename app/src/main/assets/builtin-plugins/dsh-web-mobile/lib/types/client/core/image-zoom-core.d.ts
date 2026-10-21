export type ZoomState = {
    scale: number;
    x: number;
    y: number;
};
export type Point = {
    x: number;
    y: number;
};
export type Size = {
    width: number;
    height: number;
};
export declare const MIN_SCALE = 1;
export declare const MAX_SCALE = 4;
export declare const DOUBLE_TAP_SCALE = 2.5;
/** Clamp a raw scale factor into the supported range (NaN collapses to 1:1). */
export declare function clampScale(n: number): number;
/** The neutral 1:1 state. A fresh object every call: callers may hold it. */
export declare function identityZoom(): ZoomState;
/**
 * Scale by `factor` while keeping `anchor` (centre-relative, see the header)
 * at the same screen position. The new translation is derived from the fixed
 * point relation x' + s' * p = x + s * p, so no iteration or accumulated
 * drift is involved. Clamping to MIN_SCALE recentres the image; clamping to
 * MAX_SCALE keeps the anchored relation at the clamped scale.
 */
export declare function zoomAround(state: ZoomState, factor: number, anchor: Point): ZoomState;
/**
 * Keep a zoomed image from being dragged off its viewport. The allowance per
 * axis is half of the overflow the current scale creates, so the image edge
 * can reach the viewport edge but never pass it. A 1:1 image cannot move at
 * all. `viewport` is the visible box the image is allowed to travel within
 * (the effect passes the visible viewport size, not a cached box).
 */
export declare function clampTranslation(state: ZoomState, viewport: Size): ZoomState;
/**
 * Ratio between two pinch spans, each given as the vector between the two
 * touches (p2 - p1). A degenerate previous span (both fingers on one point)
 * returns 1 instead of dividing by zero.
 */
export declare function pinchFactor(prev: Point, next: Point): number;
/** The double-tap step: 1:1 zooms to DOUBLE_TAP_SCALE, anything else resets. */
export declare function doubleTapTarget(state: ZoomState): ZoomState;
//# sourceMappingURL=image-zoom-core.d.ts.map