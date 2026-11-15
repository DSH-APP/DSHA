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
export declare function installPhoneChrome(ctx: ClientContext): void;
//# sourceMappingURL=phone-chrome.d.ts.map