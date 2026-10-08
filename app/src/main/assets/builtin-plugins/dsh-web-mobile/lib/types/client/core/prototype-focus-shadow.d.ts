/**
 * One shared shadow over `HTMLInputElement.prototype.focus`.
 *
 * Several mobile guards must stop a *programmatic* focus (a host effect or a
 * Modal autofocus that would raise the soft keyboard). Each guard used to save
 * the method it saw, install its own wrapper, and write the saved method back
 * when it disarmed. With two guards loaded that single global slot is a lost
 * update: whoever disarms first deletes the other guard's wrapper, and whoever
 * disarms last writes a STALE wrapper back to the prototype — by then each
 * guard's saved "original" is the other guard's wrapper, so neither owns what
 * is installed any more and nobody can restore it (measured on the real
 * interleaving: arm A → arm B → disarm A → disarm B leaves A's wrapper on the
 * prototype with both saved originals null).
 *
 * This manager owns the single patch instead. Callers register a predicate
 * that returns true when an element must not be focused and get a disposer;
 * the real method is captured once and restored only when the last predicate
 * is gone. Arming and disarming in any order is therefore safe and no wrapper
 * outlives its guards.
 *
 * A user tap is unaffected: the browser focuses natively and never goes
 * through this method.
 *
 * Zero imports (same rule as `reconciler-core.ts`): the core stays DOM-agnostic
 * and the guards pass their own predicates in.
 */
/** Predicate deciding whether one element's programmatic focus must be swallowed. */
export type FocusSkip = (element: HTMLInputElement) => boolean;
/**
 * Register a predicate: while it is registered, `HTMLInputElement.prototype.focus`
 * is a no-op for every element it matches.
 * @param shouldSkip - predicate deciding which elements must not be focused.
 * @returns disposer removing this predicate; the patch falls away with the last one.
 */
export declare function shadowFocus(shouldSkip: FocusSkip): () => void;
//# sourceMappingURL=prototype-focus-shadow.d.ts.map