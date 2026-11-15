export declare const STABLE_VIEWPORT_VAR = "--dsh-web-mobile-vh";
/** Viewport meta ownership: re-assert our content on every host rewrite, node
 *  replacement, or late injection while the mobile branch is armed. */
export declare function preserveViewportMeta(): () => void;
/** The keyboard-less viewport height (STABLE_VIEWPORT_VAR). */
export declare function syncStableViewportHeight(): () => void;
/** iOS-only: mark documentElement so the stylesheet can hold text fields at
 *  >=16px and Safari never focus-zooms the viewport (#45). */
export declare function applyIosMarker(isIosWebKit: boolean): (() => void) | undefined;
//# sourceMappingURL=viewport-meta.d.ts.map