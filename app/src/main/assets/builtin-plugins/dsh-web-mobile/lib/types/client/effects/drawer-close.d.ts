import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
/**
 * Drawer close interactions that are plain event listeners, not DOM
 * reconciliation:
 * - Escape closes the drawer (yielding to any open modal dialog, which owns
 *   its own Escape handling).
 * - Tapping a navigation target inside the drawer (session row, sidebar panel
 *   row, task board / ssh takeover entries, search results) closes the drawer
 *   so the content it opened gets the whole screen. Session-row action buttons
 *   (kebab) are excluded — they open a menu that must survive the tap.
 *
 * The touch close always rides the synthesized click. Closing a non-row
 * target from pointerup collapsed the drawer before that click existed, and
 * a collapsed drawer no longer owns the touch point, so the browser
 * dispatched no click at all and the target's own onClick never ran (「新会话」
 * did nothing but retract the drawer, 2026-09-13).
 */
export declare const TAP_CLOSE_NAV_SELECTOR = "button[data-dsh-taskboard-entry], button[data-dsh-ssh-entry], [class*=\"newSession\"], [class*=\"sessionRow\"], [class*=\"searchResultRow\"], [class*=\"searchResultWorkspace\"], [class*=\"panelRow\"]";
/**
 * The one drawer toggle every non-gesture entry point shares: a CLOSE animates
 * into the closed slot and flips the host marker only once it has landed
 * (closeDrawerAnimated's late commit — spec 2026-08-27), an OPEN stays a plain
 * toggle so the host's own .28s transform transition plays.
 *
 * Load-bearing for layering, not just for looks (2026-09-25): the popover
 * band's modal-root raise is gated on our backdrop being on screen, and the
 * backdrop outlives the marker flip by design (fade .2s + removal 260ms). A
 * closer that flips the marker while the column is still painted therefore
 * leaves an open modal under the drawer band for the length of the
 * transition — that is the 快捷键弹层「抽搐/闪」 root cause. Routing every
 * closer through here removes the window at the source instead of relying on
 * the band to cover it.
 *
 * An OPEN (frame still collapsed) goes through openDrawerAfterKeyboard: a
 * focused composer is blurred first and the drawer opens once the soft keyboard
 * has gone, so the host's own focus move into the drawer cannot close the
 * keyboard mid-slide (2026-11-05 drawer-jank report, R2). Every tap opener —
 * the header toggle (index.tsx) and the FAB — routes through here. The opening
 * hook runs right before the flip so the backdrop joins the same commit (R3).
 */
export declare function toggleDrawer(ctx: ClientContext): void;
export declare function installOverlayInteractions(ctx: ClientContext): void;
//# sourceMappingURL=drawer-close.d.ts.map