import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
import type { MobileNavKey } from './i18n/locales.ts';
declare module '@deepseek-ai/dsh-client-ui-slots' {
    interface LocaleNamespaceMap {
        /** Directory-drawer controls copy. */
        'mobileNav': MobileNavKey;
    }
}
/** Required services (cordis fiber inject — the loader passes all module exports as an object plugin). */
export declare const inject: string[];
/**
 * Mobile-adaptive shell, browser half: injects the mobile stylesheet, then
 * contributes the directory toggle to the session header and the backdrop +
 * floating button to the shell overlay.
 * @param ctx - client root context.
 */
export declare function apply(ctx: ClientContext): void;
//# sourceMappingURL=index.d.ts.map