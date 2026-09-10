/**
 * Gesture-consumption contract between the sidebar swipe layer and every
 * other document-level listener that would otherwise treat the release as a
 * plain tap (the overlay's drawer-close click/pointerup handlers and the
 * FAB / backdrop element listeners).
 *
 * Two independent signals, because they answer questions at different times:
 * - the axis-lock flag (`markStrokeLocked` at tryLock, i.e. during
 *   pointermove) tells a host handler running EARLIER in the same release
 *   event's capture phase that this pointerup is a swipe release, not a tap;
 * - the consume marks (`markGestureConsumed` at the gesture layer's own
 *   pointerup, after classification) cover the events that come AFTER the
 *   release.
 *
 * When the gesture layer classifies a stroke as a real swipe it calls
 * `markGestureConsumed(target, windowMs, upTo)`; any later listener that
 * calls `consumeIfGestured(event)` on the same stroke returns true and bails
 * out, so a swipe can never toggle the drawer twice or navigate a session
 * row — including the synthetic click the browser dispatches after the
 * stroke (its target is the release point or an ancestor of it, which is why
 * the mark walks the ancestor chain up to `upTo`).
 *
 * Non-gesture taps leave both signals clear, so the host's own close / tap /
 * nav-arm logic keeps working untouched.
 */
/** Flag the live stroke as axis-locked horizontal (called by tryLock). */
export declare function markStrokeLocked(): void;
/** Clear the axis-lock flag (called by reset and on a new pointer epoch). */
export declare function clearStrokeLocked(): void;
/** True while a stroke is axis-locked horizontal (host handlers yield). */
export declare function isStrokeLocked(): boolean;
/**
 * Register that the stroke ending on `target` is a gesture. The mark covers
 * `target` itself and every ancestor up to and including `upTo` (when given
 * and present in the chain), so a follow-up synthetic click — whose target
 * is usually an ancestor of the release point — is reported as consumed
 * too. Multiple marks accumulate independently and expire after `windowMs`.
 */
export declare function markGestureConsumed(target: EventTarget, windowMs: number, upTo?: Element | null): void;
/**
 * True when the event belongs to a stroke already marked as a gesture.
 * Matches the event target itself or any of its ancestors. Stale marks are
 * dropped lazily.
 */
export declare function consumeIfGestured(event: Event): boolean;
/**
 * Test-only probe: true when a gesture mark is still live for the given
 * element (tests/sidebar-swipe.test.ts asserts expiry with it; production
 * code never calls it — the swipe layer gates on its own consumedEl
 * instead). Returns false for stale marks.
 */
export declare function isGestureConsumed(target: Element): boolean;
//# sourceMappingURL=gesture-guard.d.ts.map