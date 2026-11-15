/** The AppFrame element: direct parent of the shell overlay layer. */
export declare function findFrame(): HTMLElement | null;
/** Resolve the plugin-owned frame marker, falling back to the raw shell frame. */
export declare function getFrame(): HTMLElement | null;
/**
 * The sidebar column, i.e. the frame's drawer element.
 *
 * Host 0.2.2 renders a native drawer mask BEFORE the sidebar whenever the shell
 * is in drawer mode, so a `> :first-child` anchor resolves to the mask (drawer
 * chrome drawn on the mask, real drawer unstyled, gestures aimed at the mask).
 * The column is matched by its `_sidebarCol` class suffix, which every
 * supported host (>=0.2.2-rc.1, dsh-client-ui-layout) renders; the first-child
 * fallback for pre-0.2.2 hosts was removed with the host floor.
 */
export declare function getDrawer(): HTMLElement | null;
/**
 * Whether a dialog that the drawer itself does NOT contain owns the screen.
 *
 * Host 0.2.2 turned the sidebar into a modal dialog: dsh-client-ui-sidebar
 * renders `role="dialog"` + `aria-modal="true"` + `data-shortcut-modal="sidebar"`
 * on the SidebarRoot it puts INSIDE the `_sidebarCol` column, whenever the
 * shell is in drawer mode and the drawer is expanded. A bare
 * `[aria-modal="true"]` probe therefore reports "a modal is up" for as long as
 * the drawer is open — and every guard written against it (gesture start,
 * per-move abort, Escape, row-tap close) silently yields to the drawer itself.
 *
 * Measured on 0.2.2-rc.1 at 390x844: the only `aria-modal` while the drawer is
 * open is the drawer's own `hHd-Xa_root`, and `_sidebarCol` CONTAINS it
 * (`colContainsModal: true`); the settings sheet (`VOzbGW_panel`) mounts with
 * `insideCol: false`. On 0.1.0-rc.6 the sidebar package emits no `aria-modal`
 * at all (grep: 0 vs 1), so this is a 0.2.2 addition, not a long-standing shape.
 */
export declare function foreignModalOpen(): boolean;
//# sourceMappingURL=frame.d.ts.map