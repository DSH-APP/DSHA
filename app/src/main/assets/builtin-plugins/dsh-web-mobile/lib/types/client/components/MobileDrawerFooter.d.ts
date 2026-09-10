import type { PropsLocale, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots';
import { NS } from '../i18n/locales.ts';
/** Full props for the sidebar footer action entry. */
export interface MobileDrawerFooterProps extends PropsRuntime<'sidebar.footer.action'>, PropsLocale<typeof NS> {
    /** Bound ctx.sessionLogDownload.download() for the current session. */
    downloadSessionLog: (sessionId: string) => void;
    /** Bound ctx.layout.toggleSidebar(): the Files sheet closes the drawer. */
    toggleSidebar: () => void;
}
/**
 * Mobile-only drawer footer actions, relocated from the session header to the
 * drawer footer (beside Settings):
 * - Files: opens the dsh-web-ui aionui explorer as a floating bottom sheet
 *   (the explorer column is hidden on mobile until this marker is set, so
 *   the suite's own persisted-expanded state can never cover the UI on load).
 * - Session log: the official session-log-export controller, so the
 *   progress/result dialog is shared with the desktop flow.
 * Hidden entirely on wide screens (CSS media query).
 */
export declare function MobileDrawerFooter({ useSessions, downloadSessionLog, toggleSidebar, t }: MobileDrawerFooterProps): import("react").JSX.Element;
//# sourceMappingURL=MobileDrawerFooter.d.ts.map