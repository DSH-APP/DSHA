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
 * - files: opens the file browser directly — one tap, no drawer round-trip.
 *   Which surface that is (host right sidebar vs. the third-party explorer
 *   sheet) is decided in open-files-panel.ts. The hero/blank phases have no
 *   session header, so this control is absent there; the files entry in those
 *   phases is the right-edge leftward swipe (sidebar-swipe.ts).
 * Hidden entirely on wide screens (CSS media query).
 */
export declare function MobileNavToggle({ toggleSidebar, t }: MobileNavToggleProps): import("react").JSX.Element;
//# sourceMappingURL=MobileNavToggle.d.ts.map