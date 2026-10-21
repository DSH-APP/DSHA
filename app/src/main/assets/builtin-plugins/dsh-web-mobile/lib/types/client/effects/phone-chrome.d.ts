import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
export declare function ensureDismissShadow(): void;
/**
 * Frame marker controller: owns `data-mobile-nav="frame"` and every plugin
 * marker that can survive on the shell-owned frame. Installed once at apply
 * time so effects no longer each need to find/set/clear the frame. Returns a
 * disposer that unregisters the task and resets the installed flag, so a
 * same-environment plugin reload can rebuild the reconciler from scratch.
 * (The host-generation probe this controller used to call was dead code —
 * nothing ever read `data-mobile-nav-gen`, and the plugin deliberately does
 * not yield the drawer to the host's one: see docs/maintenance/pitfalls.md
 * §0.1.5 抽屉 z 与遮罩.)
 */
export declare function installFrameController(): () => void;
/**
 * Phone chrome: KEEP the system status bar (no fullscreen) and make it
 * blend into the page. On narrow screens:
 * - The viewport meta is OWNED by the plugin while armed (viewport-meta.ts):
 *   width=device-width, initial-scale=1, viewport-fit=cover,
 *   interactive-widget=resizes-content (keyboard shrinks the layout viewport
 *   on Chromium instead of panning the page), re-asserted on every host
 *   rewrite, node replacement, or late injection, so
 *   env(safe-area-inset-top) stays the real status-bar / notch height
 *   instead of silently going stale when the host touches the meta. No zoom
 *   tokens here: iOS 10+ ignores them for user pinch but other engines
 *   honor them, and the focus-zoom fix is the >=16px field floor (#45), not
 *   a zoom ban. Dispose restores the host's own content as observed at arm
 *   time.
 * - A theme-color meta tracks the shell background (the official theme is
 *   toggled by body[data-ds-dark-theme], which flips --dsw-alias-bg-base):
 *   Android then paints the status bar / URL bar with the page's own base
 *   color, so the status bar reads as part of the UI instead of a foreign
 *   strip. The drawer paints the same strip on iOS / notch displays.
 * - documentElement carries data-mobile-nav-ios on iOS WebKit so the
 *   stylesheet can hold every text field at >=16px and Safari never
 *   focus-zooms the viewport (#45). Double-tap zoom is off through
 *   touch-action; pinch zoom stays available on purpose — it is the only way
 *   back out of a zoom the browser applied on its own.
 */
export declare function installPhoneChrome(ctx: ClientContext): void;
//# sourceMappingURL=phone-chrome.d.ts.map