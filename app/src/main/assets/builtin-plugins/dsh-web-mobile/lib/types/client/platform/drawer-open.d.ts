/**
 * Register the callback that runs right before an opening toggle.
 * @param hook - runs synchronously, before the host state flips.
 * @returns unregister (only clears the slot while it still holds this hook).
 */
export declare function onDrawerOpening(hook: () => void): () => void;
/** Run the registered opening hook (no-op when none is registered). */
export declare function drawerOpening(): void;
/**
 * Whether the soft keyboard is up: the keyboard-less height viewport-meta keeps
 * in --dsh-web-mobile-vh (falls back to innerHeight) against the visual
 * viewport height.
 */
export declare function softKeyboardUp(): boolean;
/**
 * Blur the focused text-entry element, if any.
 * @returns true when focus was released AND the soft keyboard was up at that moment.
 */
export declare function releaseTextFocus(): boolean;
/**
 * Tap-opener entry: release text focus, then open — immediately when no
 * keyboard was up, otherwise once the keyboard has gone (it reads down and the
 * viewport has been quiet for KEYBOARD_SETTLE_MS) or after KEYBOARD_WAIT_CAP_MS.
 * A second call while an open is pending cancels it (the user tapped the toggle
 * again) and does nothing else.
 * @param open - fires the opening hook and flips the host state.
 */
export declare function openDrawerAfterKeyboard(open: () => void): void;
//# sourceMappingURL=drawer-open.d.ts.map