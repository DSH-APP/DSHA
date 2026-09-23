import type { PropsLocale, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots';
import { NS } from '../i18n/locales.ts';
/** Full props for the sidebar footer action entry. */
export interface MobileDrawerFooterProps extends PropsRuntime<'sidebar.footer.action'>, PropsLocale<typeof NS> {
    /** Bound ctx.sessionLogDownload.download() for the current session. */
    downloadSessionLog: (sessionId: string) => void;
}
/**
 * Mobile-only drawer footer action, relocated from the session header to the
 * drawer footer (beside Settings): the official session-log-export
 * controller, so the progress/result dialog is shared with the desktop flow.
 * Hidden entirely on wide screens (CSS media query).
 *
 * The Files entry that used to live here was removed on 2026-09-17: while the
 * drawer is open neither the host (it refuses to expand the right sidebar)
 * nor the third-party drawer-dismiss shim (it swallows every frame-interior
 * click outside the drawer, programmatic ones included) lets a click reach
 * the right-sidebar opener, so the entry could only ever close the drawer.
 * Contract: docs/specs/2026-09-17-sidebar-files-coexistence-design.md
 */
export declare function MobileDrawerFooter({ useSessions, downloadSessionLog, t }: MobileDrawerFooterProps): import("react").JSX.Element;
//# sourceMappingURL=MobileDrawerFooter.d.ts.map