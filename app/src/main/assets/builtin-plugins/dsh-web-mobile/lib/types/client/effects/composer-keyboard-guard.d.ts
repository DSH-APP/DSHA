import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
/** Re-arm marker kept on the editor element while its focus is shadowed.
 *  Exported for its source-contract test. (Its former second user,
 *  session-focus-guard.ts, was removed 2026-11-05: host 0.2.2's InputBar skips
 *  the mount-time editor focus on mobile devices.) */
export declare const SHADOW_MARKER = "data-mobile-nav-focus-shadow";
export declare function installComposerKeyboardGuard(ctx: ClientContext): void;
//# sourceMappingURL=composer-keyboard-guard.d.ts.map