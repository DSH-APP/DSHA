import type { TranslateNS } from '@deepseek-ai/dsh-client-ui-slots';
import type { ReconcilerTask } from '../core/reconciler-core.ts';
/** Fade the CURRENT backdrop out (pointer-events off + opacity 0). Called by
 * the gesture layer when a close commit starts animating, so the dimming
 * fades WITH the drawer's slide-out instead of vanishing after it. The
 * element itself is removed later by the task's normal remove path (the
 * marker flip schedules it). */
export declare function fadeOverlayOut(): void;
export declare function createOverlayTask(t: TranslateNS<'mobileNav'>, toggleSidebar: () => void): ReconcilerTask;
//# sourceMappingURL=overlay-backdrop-fab.d.ts.map