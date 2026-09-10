import type { PropsLocale, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots';
import { NS } from '../i18n/locales.ts';
/** Full props for the session-header directory toggle. */
export interface MobileNavToggleProps extends PropsRuntime<'conversation.session.header.actions'>, PropsLocale<typeof NS> {
    /** Bound ctx.layout.toggleSidebar(). */
    toggleSidebar: () => void;
}
/**
 * Mobile-only icon buttons next to the session title:
 * - toggle: opens the directory drawer on narrow screens.
 * - files: toggles the dsh-web-ui explorer sheet directly — one tap opens,
 *   a second tap closes it, no drawer round-trip. (The drawer footer keeps
 *   a Files entry for the hero/blank phases where this header does not
 *   exist.)
 * Hidden entirely on wide screens (CSS media query).
 */
export declare function MobileNavToggle({ toggleSidebar, t }: MobileNavToggleProps): import("react").JSX.Element;
//# sourceMappingURL=MobileNavToggle.d.ts.map