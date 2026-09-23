import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
import type { ReconcilerTask } from '../core/reconciler-core.ts';
/** Whether a panel owns the main area (DOM truth, ignoring the exit window). */
export declare function panelOwnsMainArea(): boolean;
/**
 * `ctx.layout.selectPanel` exists on 0.1.6-alpha.2 but NOT on rc.6, whose
 * layout face carries only toggleSidebar/openDetails/closeDetails (the same
 * generation drift `core/sessions-compat.ts` documents). The sidebar panel list
 * is an alpha.2-era surface, so on rc.6 nothing ever arms this feature — but the
 * call is capability-checked rather than assumed, so the plugin stays
 * compile-green against rc.6 typings and goes inert there instead of throwing.
 * The probe itself lives in core/layout-compat.ts so it stays unit-testable.
 */
/** The three exit routes plus the capability gate they all share. */
export interface PanelExit {
    /** Leave the panel (no-op when the host has no panel-selection API). */
    exit: () => void;
    /** Whether the host can select a panel at all; false on rc.6. */
    supported: boolean;
    /**
     * Whether a panel owns the main area, IGNORING the in-flight exit window.
     * The FAB reads this one: its icon must not flip back to 「打开目录」 halfway
     * through the panel's departure.
     */
    panelOpen: () => boolean;
    /** The system-back route, for the shared reconciler. */
    task: ReconcilerTask;
}
/**
 * Leave the panel: switch back to the conversation and let the incoming content
 * fade in.
 *
 * ⚠ The switch is deliberately NOT delayed behind an outgoing animation.
 * `selectPanel(null)` makes React remount the whole conversation, and that
 * commit blocks the main thread long enough to matter (measured on a phone:
 * ~390 ms for a long session). Fading the panel out first would show a blank
 * screen for that entire window — the panel is already transparent but the
 * conversation has not mounted yet. Keeping the panel opaque until the very
 * commit means it disappears on the same frame the conversation appears, and
 * the only transition is the conversation's fade-in.
 *
 * @param layout - `ctx.layout`; probed, never assumed.
 * @returns the exit action (idempotent while an exit is in flight, so a double
 *   tap cannot queue two swaps) and the system-back reconciler task.
 */
export declare function createPanelExit(layout: unknown): PanelExit;
/**
 * The system back key / back gesture exits the panel.
 *
 * The host core does not touch the browser history at all (only the PDF preview
 * plugin does, and that is unrelated), and measured on a phone the panel view
 * sits at `history.length === 1` — pressing back there leaves the page
 * entirely. So this layer is free to take over: arm one history entry while a
 * panel is open, and exit the panel when it is popped.
 *
 * Two edges are handled explicitly:
 *  - Our own `history.back()` (used when the panel is left by another route)
 *    echoes back as a popstate. `selfBackPending` swallows that echo, with a
 *    timeout so a host WebView that never emits popstate cannot leave the flag
 *    stuck and eat the user's next real back press.
 *  - Between the click and React's commit the panel row is still marked active;
 *    `panelViewOpen()` reports closed during that window, so no second history
 *    entry is armed.
 *
 * @param exitPanel - the shared exit action.
 * @param supported - false on hosts with no panel-selection API; the task then
 *   never arms a history entry at all.
 */
export declare function createPanelBackExitTask(exitPanel: () => void, supported: boolean): ReconcilerTask;
/**
 * Tapping the already-selected panel row again returns to the conversation.
 * Unselected rows are left alone — they still go through the host's own
 * `selectPanel(id)`.
 *
 * Runs in the capture phase so it can stop the host's onClick. The drawer close
 * rides the same click (phone-chrome's navigation-tap whitelist), which is the
 * only ordering that survives a touch: closing the drawer on pointerup cancels
 * the synthesized click entirely (see the 抽屉导航 click pitfall).
 */
export declare function installPanelRowExit(ctx: ClientContext, exitPanel: () => void): void;
//# sourceMappingURL=panel-exit.d.ts.map