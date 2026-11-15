/**
 * Session-row action-menu injection: on touch-primary devices, adds a
 * "delete session" item to the host's per-row ⋯ menu (beside the host's own
 * items — rename / fork / archive, plus the 0.1.7 pin item; blank rows stay
 * host-native) and drives the whole delete flow: row → session id
 * resolution, a confirm dialog, the host delete endpoint, and the list
 * refresh.
 *
 * The host menu is React-owned (ui-workspace) with no extension slot, so the
 * item is injected into the portaled `[role="menu"]` list by cloning the
 * host's own item markup (reusing the hashed classes keeps the styling
 * identical), and re-injected whenever React recreates the menu. The item
 * shape is the primitives Menu's: `_itemIcon` + `_itemLabel` spans inside the
 * `role="menuitem"` button (dsh-client-ui-primitives 0.2.2 lib/index.js
 * MenuItem). The 0.1.5 bare-text item shape was dropped with the host floor.
 *
 * Row → session id: session rows carry no id in the DOM, so the id is read
 * off the host's own stable anchors (see `rowSessionId`).
 *
 * Ported from community-fork wzxmt-zhc v2.7.0; the only mainline delta is
 * where the delete-dialog CSS lives: base.css.ts, with corrected animation
 * names (`dsh-web-mobile-*`; the fork's originals referenced the pre-rename
 * `dsh-mobile-nav-*` names, which silently no-op).
 */
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
/**
 * Install the mobile session-delete menu machinery. Touch-gated: the whole
 * effect arms under TOUCH_QUERY — (pointer: coarse) at EVERY width — so a
 * large tablet in landscape keeps the desktop layout but still gets the
 * delete item, while any mouse-driven or pointer-less window stays a
 * complete no-op. Returns a disposer (via installMobileEffect) that removes
 * every listener, observer, injected node, and the confirm dialog.
 * @param ctx - client root context.
 */
export declare function installSessionMenuDelete(ctx: ClientContext): void;
//# sourceMappingURL=session-menu.d.ts.map