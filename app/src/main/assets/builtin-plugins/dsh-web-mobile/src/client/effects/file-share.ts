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
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { installMobileEffect } from '../platform/breakpoints.ts'
import { FILE_SHARE_NS, fileShareLocales } from '../components/file-share-locale.ts'
import { FilePreviewShareButton, FilesRowShare } from '../components/file-share-controls.tsx'
import type { FileShareInject } from '../components/file-share-controls.tsx'
import type { ReadBytesResult } from '../components/file-share-core.ts'

/** The slice of the host Remote face this feature calls (dsh-api-workspace-files 0.2.0). */
interface WorkspaceFilesRemote {
  workspaceFiles: {
    readBytes: (
      sessionId: string,
      path: string,
      options: { range?: { offset?: number; length?: number } },
      signal?: AbortSignal,
    ) => Promise<ReadBytesResult>
  }
}

/**
 * Install the mobile file-share entries.
 * @param ctx - client root context.
 */
export function installFileShare(ctx: ClientContext): void {
  // `remote.workspaceFiles` is not in the root inject list: a child fiber
  // waits for it, so hosts without the namespace stay a quiet no-op.
  ctx.inject(['slots', 'locale', 'remote', 'remote.workspaceFiles'], (scope: ClientContext) => {
    scope.effect(() => scope.locale.register(FILE_SHARE_NS, fileShareLocales), 'dsh-web-mobile: file-share dictionaries')

    const remote = (scope as unknown as { remote: WorkspaceFilesRemote }).remote
    const inject = (): FileShareInject => ({
      readFileRange: (sessionId, path, range, signal) =>
        remote.workspaceFiles.readBytes(sessionId, path, { range }, signal),
    })

    installMobileEffect(scope, 'dsh-web-mobile: file share', () => {
      const stops = [
        scope.slots.inject('sidebar.right.tab.document.actions', () => scope.slots.register({
          name: 'sidebar.right.tab.document.actions',
          id: 'mobile-nav-file-share',
          order: 100,
          locale: FILE_SHARE_NS,
          inject,
        }, FilePreviewShareButton)),
        scope.slots.inject('sidebar.right.tab.files.actions', () => scope.slots.register({
          name: 'sidebar.right.tab.files.actions',
          id: 'mobile-nav-file-share-rows',
          order: 100,
          locale: FILE_SHARE_NS,
          inject,
        }, FilesRowShare)),
      ]
      return () => {
        for (const stop of stops) stop()
      }
    })
  })
}

import type {} from '@deepseek-ai/dsh-client-locale/client'
