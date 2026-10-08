import type { ReactElement } from 'react';
import type { PropsLocale } from '@deepseek-ai/dsh-client-ui-slots';
import type { ReadBytesResult } from './file-share-core.ts';
import { FILE_SHARE_NS } from './file-share-locale.ts';
/**
 * Host 0.2.0 slot contracts this feature contributes to (declared by
 * ui-sidebar-files and ui-sidebar-documentpreview; the repo's locked
 * dsh-client-ui-slots 0.1.0-rc.6 typings predate them). Both are session
 * list slots whose owner passes `{ absolutePath }` (files: the tree root;
 * document: the previewed file's Host path, rendered only once it is known).
 */
declare module '@deepseek-ai/dsh-client-ui-slots' {
    interface SlotMap {
        'sidebar.right.tab.files.actions': {
            kind: 'list';
            scope: 'session';
            owner: {
                absolutePath: string;
            };
        };
        'sidebar.right.tab.document.actions': {
            kind: 'list';
            scope: 'session';
            owner: {
                absolutePath: string;
            };
        };
    }
}
/**
 * Session-bound ranged read over the host's `remote.workspaceFiles.readBytes`.
 * @param sessionId - Session whose workspace authorizes the read.
 * @param path - absolute or workspace-relative path.
 * @param range - byte window.
 * @param signal - cancellation.
 */
export type ReadFileRange = (sessionId: string, path: string, range: {
    offset: number;
    length: number;
}, signal: AbortSignal) => Promise<ReadBytesResult>;
/** Business face both share controls receive through their slot `inject`. */
export interface FileShareInject {
    readFileRange: ReadFileRange;
}
/** Props both slot entries consume (a supertype of the composed slot props). */
export interface FileShareSlotProps extends FileShareInject, PropsLocale<typeof FILE_SHARE_NS> {
    /** Owner share: the tree root (files) or the previewed file (document). */
    absolutePath: string;
    /** Framework-resolved session id. */
    sessionId: string;
}
/**
 * Preview header share button, contributed to the documentpreview-declared
 * `sidebar.right.tab.document.actions` list slot ("Header toolbar
 * contributions acting on the previewed file"). The owner renders the slot
 * only once the file's Host path is known, for every renderer (text, code,
 * image, PDF, Office, unsupported).
 */
export declare function FilePreviewShareButton({ absolutePath, sessionId, readFileRange, t }: FileShareSlotProps): ReactElement;
/**
 * Row-end share buttons for the Files tree.
 *
 * The host file tree (ui-sidebar-files FilesBody) gives rows no menu, no
 * long-press and no per-row slot: each file is `<li data-files-entry="file"
 * data-files-path=…>` holding exactly one `<button>` whose click opens the
 * preview. The only extension seat is `sidebar.right.tab.files.actions`, in
 * the tree header. This entry sits there invisibly (a hidden anchor span) to
 * get a session-bound lifetime scoped to ONE tree, and decorates that tree's
 * file rows by APPENDING a plugin-owned button after the host's button.
 * Host nodes are never moved, wrapped or re-parented (#104 rule): React only
 * reconciles the children it rendered, so an extra trailing sibling survives
 * re-renders and leaves with the `li` on unmount. Directories and "other"
 * entries get no button; a reused `li` that turned into a directory drops it.
 */
export declare function FilesRowShare({ sessionId, readFileRange, t }: FileShareSlotProps): ReactElement;
//# sourceMappingURL=file-share-controls.d.ts.map