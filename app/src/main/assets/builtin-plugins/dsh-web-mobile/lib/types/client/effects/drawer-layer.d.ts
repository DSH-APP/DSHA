import type { TranslateNS } from '@deepseek-ai/dsh-client-ui-slots';
import type { ReconcilerTask } from '../core/reconciler-core.ts';
import { NS } from '../i18n/locales.ts';
import type { PanelExit } from './panel-exit.ts';
/** Start the dim's fade-out and disable the hit target early. Called by the
 * gesture layer when a close commit starts animating, so the dimming fades
 * WITH the drawer's slide-out instead of vanishing after it. The dim itself is
 * painted by the frame's ::after (data-mobile-nav-dim); the catcher element is
 * persistent and only re-arms when the marker flips. */
export declare function fadeDimOut(): void;
/**
 * @param t - our dictionary (the `NS` locale namespace, i18n/locales.ts).
 * @param toggleSidebar - opens/closes the drawer.
 * @param panelExit - the sidebar-panel exit face (panel-exit.ts). The FAB is the
 *   screen's only full-size control while a panel owns the main area — the
 *   header toggle does not render there — so it also steps back: one host view
 *   level on a panel subpage, out of the panel at its root. Null on a host that
 *   cannot select panels (rc.6), where it stays a plain drawer button.
 */
export declare function createDrawerLayerTask(t: TranslateNS<typeof NS>, toggleSidebar: () => void, panelExit: PanelExit | null): ReconcilerTask;
//# sourceMappingURL=drawer-layer.d.ts.map