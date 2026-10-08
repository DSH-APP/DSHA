import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
/** Re-arm marker kept on the editor element while its focus is shadowed.
 *  Exported: session-focus-guard.ts shares the same shadow slot (one marker,
 *  one own-property recipe) so both guards stay interoperable. */
export declare const SHADOW_MARKER = "data-mobile-nav-focus-shadow";
export declare function installComposerKeyboardGuard(ctx: ClientContext): void;
//# sourceMappingURL=composer-keyboard-guard.d.ts.map