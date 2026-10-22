// Reconciler adapter: the one full-tree MutationObserver, the task registry
// face every effect registers through, and the task set this plugin installs.
//
// 2026-10-12 P1 拆分第四片：由 effects/phone-chrome.ts 逐字搬出（纯搬运，不改行为）。
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { installMobileEffect } from '../platform/breakpoints.ts'
import { createReconcilerCore, mutationDirtyKey } from '../core/reconciler-core.ts'
import type { ReconcilerTask } from '../core/reconciler-core.ts'
import { NS } from '../i18n/locales.ts'
import { createPreviewCloseTask, createSheetRiseTask } from './aionui-compat.ts'
import { createStatsLineTask } from './stats-line.ts'
import { createPreviewFullscreenTask } from './preview-fullscreen.ts'
import { createOverlayTask } from './overlay-backdrop-fab.ts'
import { createFileViewerMarkerTask } from './file-viewer-compat.ts'
import { createDrawerSessionLogTask } from './drawer-session-log.ts'
import { createImageLightboxZoomTask } from './image-lightbox-zoom.ts'
import type { PanelExit } from './panel-exit.ts'
import { toggleDrawer } from './drawer-close.ts'

let reconcilerInstalled = false
let reconcileTasksRegistered = false

// The DOM-free core owns the task registry, dirty-key routing, and coalesced
// flush scheduling; this module is the thin browser adapter that feeds it
// MutationObserver records and drives its lifecycle from the mobile effect.
const core = createReconcilerCore({
  requestFrame: (flush) => {
    let id = 0
    const run = (): void => {
      id = 0
      flush()
    }
    id = requestAnimationFrame(run)
    return () => {
      if (id !== 0) cancelAnimationFrame(id)
    }
  },
})

/**
 * One full-tree MutationObserver for every mobile DOM reconciler. Tasks can be
 * registered from React or plain effects; they only run while the mobile
 * breakpoint is active and are re-armed automatically on width changes.
 */
export function installReconciler(ctx: ClientContext): () => void {
  if (reconcilerInstalled) return () => {}
  reconcilerInstalled = true
  installMobileEffect(ctx, 'dsh-web-mobile: DOM reconciler', () => {
    // Coalesce every mutation burst (typing, animations, per-token TPS
    // re-renders) into one dirty-key pass per animation frame. Each task
    // declares scopes so only intersecting tasks run on a given flush. The
    // record-to-key mapping is the core's (reconciler-core.ts), so the keys a
    // task declares and the keys this observer produces cannot drift apart.
    const observer = new MutationObserver((records) => {
      const keys = new Set<string>()
      for (const record of records) {
        // DSHA: message history and streaming text do not change shell geometry.
        // This filter drops body records for EVERY key (text included), which is
        // deliberate: it is why the chat flow cannot wake shell-geometry tasks.
        const target = record.target instanceof Element ? record.target : record.target.parentElement
        if (target?.closest('[data-chat-flow]')) continue
        keys.add(mutationDirtyKey(record))
      }
      if (keys.size > 0) core.note(keys)
    })
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      // In-place text rewrites (React updates a text node's value instead of
      // rebuilding the node) only surface as characterData records. Without
      // this flag they never reach note() at all, so a text-only update used to
      // wake nothing; with it they map to TEXT_MUTATION_SCOPE and wake exactly
      // the tasks that declare that key (stats-line reads the TPS text).
      characterData: true,
      attributeFilter: [
        'style',
        'class',
        'data-phase',
        'data-sidebar-collapsed',
        'data-aionui-explorer-open',
        'data-aionui-preview-open',
        'data-mobile-preview-full',
      ],
    })
    core.activate()
    return () => {
      observer.disconnect()
      core.deactivate()
    }
  })
  return () => {
    reconcilerInstalled = false
  }
}

/** Register a reconciler task. The returned disposer removes it immediately. */
export function addReconcilerTask(task: ReconcilerTask): () => void {
  return core.register(task)
}
/**
 * Register the shared DOM reconciler tasks. Returns a disposer that
 * unregisters every task and resets the flag, so a same-environment plugin
 * reload can rebuild the reconciler from scratch.
 *
 * @param panelExit - the sidebar-panel exit face (panel-exit.ts): its system-back
 *   route is registered here so it shares this reconciler, and the FAB reads it
 *   to switch its meaning while a panel owns the main area.
 */
export function registerReconcileTasks(ctx: ClientContext, panelExit: PanelExit): () => void {
  if (reconcileTasksRegistered) return () => {}
  reconcileTasksRegistered = true
  const t = ctx.locale.bind(NS)
  const removeTasks = [
    addReconcilerTask(createPreviewFullscreenTask(t)),
    addReconcilerTask(createPreviewCloseTask()),
    addReconcilerTask(createSheetRiseTask()),
    addReconcilerTask(createStatsLineTask()),
    addReconcilerTask(createOverlayTask(t, () => toggleDrawer(ctx), panelExit)),
    addReconcilerTask(panelExit.task),
    addReconcilerTask(createFileViewerMarkerTask()),
    addReconcilerTask(createDrawerSessionLogTask(ctx)),
    addReconcilerTask(createImageLightboxZoomTask()),
  ]
  return () => {
    for (const remove of removeTasks) remove()
    reconcileTasksRegistered = false
  }
}
