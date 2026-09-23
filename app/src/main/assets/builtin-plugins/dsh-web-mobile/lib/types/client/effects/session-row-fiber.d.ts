/** The only part of a React fiber this walk reads. */
export interface FiberNodeLike {
    memoizedProps?: Record<string, unknown> | null;
    return?: FiberNodeLike | null;
}
/**
 * Hop budget for one walk. The measured row item fiber is 3 hops up and the
 * whole chain is well under this; the bound exists so an unexpectedly deep or
 * malformed chain cannot spin.
 */
export declare const FIBER_WALK_LIMIT = 60;
/**
 * Walk from `fiber` towards the root (`.return`) and return the session id of
 * the NEAREST hop offering one the caller knows. A hop whose candidate is
 * rejected does not stop the walk, so an outer row item fiber still wins over
 * an inner fiber carrying an unrelated or stale id. Returns null when nothing
 * within `limit` hops (the starting fiber counts as the first) is accepted.
 */
export declare function findSessionIdInFiber(fiber: FiberNodeLike | null | undefined, isKnownId: (id: string) => boolean, limit?: number): string | null;
/**
 * The React fiber a DOM node (or any renderer-stamped object) carries: React
 * assigns it under `__reactFiber$<rendererKey>` and keeps the legacy
 * `__reactInternalInstance$<rendererKey>` alias. First stamp wins; null when
 * the value is not a stamped object.
 */
export declare function reactFiberOf(instance: object | null | undefined): FiberNodeLike | null;
/**
 * Whether a pointer release still counts as a tap: it stayed within `slopPx` on
 * BOTH axes (max-norm, not Euclidean). The drawer list scrolls vertically, so a
 * 60px vertical drift must not navigate.
 */
export declare function isTapWithinSlop(from: {
    x: number;
    y: number;
}, to: {
    x: number;
    y: number;
}, slopPx: number): boolean;
//# sourceMappingURL=session-row-fiber.d.ts.map