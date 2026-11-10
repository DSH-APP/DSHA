// Platform layer: the plugin-owned frame marker lookup.
//
// Moved out of effects/phone-chrome.ts (architecture P1). Six effects plus the
// open-files-panel component needed this lookup, which made phone-chrome the
// shared surface of the effects layer (the only runtime import cycle in
// src/client) and the one components → effects layer violation. Nothing here
// knows about features; the marker itself is still owned and set by
// phone-chrome's frame-marker task.

/** The AppFrame element: direct parent of the shell overlay layer. */
export function findFrame(): HTMLElement | null {
  return document.querySelector('[data-shell-overlay]')?.parentElement ?? null
}

/**
 * Memoized frame lookup. The marked column survives React swaps, so the cached
 * node stays valid while it is still connected AND still carries the marker;
 * anything else re-queries. Streamed output wakes every `*` task each frame and
 * each task resolves the frame again (measured: 1063 full-document
 * `[data-mobile-nav="frame"]` queries in a 4s mutation storm at ~60Hz).
 *
 * The cache is the one piece of module-level state this layer owns; the
 * architecture ledger carries that fact explicitly.
 */
let frameCache: HTMLElement | null = null

/** Resolve the plugin-owned frame marker, falling back to the raw shell frame. */
export function getFrame(): HTMLElement | null {
  const cached = frameCache
  if (cached !== null && cached.isConnected && cached.getAttribute('data-mobile-nav') === 'frame') {
    return cached
  }
  const marked = document.querySelector('[data-mobile-nav="frame"]')
  const found = (marked ?? findFrame()) as HTMLElement | null
  frameCache = found
  return found
}

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
export function getDrawer(): HTMLElement | null {
  const frame = getFrame()
  if (frame === null) return null
  const column = frame.querySelector('[class*="_sidebarCol"]')
  return column instanceof HTMLElement ? column : null
}

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
/* @patch
   id: drawer-own-dialog-not-a-modal
   for: dsh-client-ui-sidebar 0.2.2-rc.1 的 SidebarRoot（抽屉态、展开时在 _sidebarCol 内渲染 role=dialog + aria-modal=true + data-shortcut-modal=sidebar）
   why: 0.2.2 之前侧栏没有任何 aria-modal（0.1.0-rc.6 该包 grep 计数 0 vs 1），插件各处守卫都按「出现 aria-modal = 外来模态升起」写；宿主把抽屉本身标成模态后，裸查询在抽屉打开期间恒真，手势起手/每帧/松手判定全部让位给自己
   until: 宿主不再把抽屉渲染成 aria-modal 对话框，或给「外来模态」提供独立标记（届时可退回单次查询）
   test: tests/sidebar-swipe.test.ts
*/
export function foreignModalOpen(): boolean {
  const modals = document.querySelectorAll('[aria-modal="true"]')
  if (modals.length === 0) return false
  const drawer = getDrawer()
  if (drawer === null) return true
  // Every match must be checked, not just the first: document order puts the
  // drawer's own dialog ahead of a sheet opened ON TOP of it (measured with the
  // drawer expanded and the settings sheet open: ["sidebar", "settings"]), so a
  // single querySelector would answer "inside the drawer" and miss the sheet.
  for (const modal of modals) if (!drawer.contains(modal)) return true
  return false
}
