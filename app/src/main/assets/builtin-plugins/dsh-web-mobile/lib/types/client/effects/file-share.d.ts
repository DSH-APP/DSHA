/**
 * Mobile file sharing: a share entry on Files-tree file rows and in the
 * document preview header. Tapping it reads the file through the host's own
 * Remote (`remote.workspaceFiles.readBytes`, the call the preview itself
 * uses — authentication and workspace confinement stay host-owned) and hands
 * the File to `navigator.share`; platforms that cannot share files (Android
 * WebView among them) get a download instead. Decision logic lives in
 * components/file-share-core.ts (pure, unit-tested); the controls in
 * components/file-share-controls.tsx.
 *
 * Both entries ride OFFICIAL host 0.2.0 slots, no menu cloning:
 *   - `sidebar.right.tab.document.actions` (ui-sidebar-documentpreview):
 *     header toolbar contributions acting on the previewed file.
 *   - `sidebar.right.tab.files.actions` (ui-sidebar-files): the tree header;
 *     the entry there is invisible and only scopes the row decoration to its
 *     own tree (host rows carry no menu or per-row slot — see FilesRowShare).
 * On hosts that never declare these slots (≤0.1.x) `slots.inject` simply
 * keeps waiting, so the feature is inert there.
 *
 * Mobile-only: the registrations live inside installMobileEffect, so a
 * desktop / mouse-driven window never mounts either control, and a
 * wide→narrow transition registers them.
 */
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
/**
 * Install the mobile file-share entries.
 * @param ctx - client root context.
 */
export declare function installFileShare(ctx: ClientContext): void;
//# sourceMappingURL=file-share.d.ts.map