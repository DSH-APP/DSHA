import type { TranslateNS } from '@deepseek-ai/dsh-client-ui-slots';
import type { ReconcilerTask } from '../core/reconciler-core.ts';
import { NS } from '../i18n/locales.ts';
import type { PanelExit } from './panel-exit.ts';
/** Fade the CURRENT backdrop out (pointer-events off + opacity 0). Called by
 * the gesture layer when a close commit starts animating, so the dimming
 * fades WITH the drawer's slide-out instead of vanishing after it. The
 * element itself is removed later by the task's normal remove path (the
 * marker flip schedules it). */
export declare function fadeOverlayOut(): void;
/**
 * @param t - our dictionary (the `NS` locale namespace, i18n/locales.ts).
 * @param toggleSidebar - opens/closes the drawer.
 * @param panelExit - the sidebar-panel exit face (panel-exit.ts). The FAB is the
 *   screen's only full-size control while a panel owns the main area — the
 *   header toggle does not render there — so it also steps back: one host view
 *   level on a panel subpage, out of the panel at its root. Null on a host that
 *   cannot select panels (rc.6), where it stays a plain drawer button.
 */
export declare function createOverlayTask(t: TranslateNS<typeof NS>, toggleSidebar: () => void, panelExit: PanelExit | null): ReconcilerTask;
//# sourceMappingURL=overlay-backdrop-fab.d.ts.map