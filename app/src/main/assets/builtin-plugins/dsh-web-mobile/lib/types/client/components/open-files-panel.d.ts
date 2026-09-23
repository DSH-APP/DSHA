/** The host's own right-sidebar opener (ui-sidebar-right: ExpandButton). */
export declare const HOST_FILES_OPENER = "[data-sidebar-right-expand]";
/** The host's collapse control, mounted while the right sidebar is open. */
export declare const HOST_FILES_CLOSER = "[data-sidebar-right-toggle]";
/** Minimal document surface this helper needs (injectable for tests). */
interface FilesPanelDocument {
    querySelector: (selector: string) => unknown;
}
/** Minimal frame surface the explorer fallback touches (injectable for tests). */
interface FilesPanelFrame {
    removeAttribute: (name: string) => void;
    setAttribute: (name: string, value: string) => void;
}
/**
 * Open the file browser from a mobile control, preferring the surface the user
 * actually has.
 *
 * 1. The host's own right sidebar (`data-sidebar-right-expand`) is the current
 *    file browser: on 0.1.5 it holds the workspace tree (Files tab). The host
 *    renders its opener inside `headerCorner`, which the desktop layout hides
 *    with `display: none`, so the control exists and its click handler runs
 *    while it has no painted size — acting on it programmatically is the
 *    supported path, and forcing the corner visible would fight the very
 *    layout that hides it.
 * 2. Only when that surface is absent (hosts without ui-sidebar-right) do we
 *    fall back to the third-party explorer column, which needs its
 *    `data-aionui-explorer-open` marker; the preview sheet is yielded first
 *    because compat.css gives preview precedence over explorer.
 *
 * Returns true when the official sidebar took the action, so callers can skip
 * their own layout work (the host panel covers the frame on its own).
 */
export declare function openFilesPanel(doc?: FilesPanelDocument, frame?: FilesPanelFrame | null): boolean;
export {};
//# sourceMappingURL=open-files-panel.d.ts.map