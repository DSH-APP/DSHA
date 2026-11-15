// Every supported host (>=0.2.2-rc.1, dsh-client-ui-layout) exposes
// selectPanel(panelId | null) on the layout service. The repo still
// type-checks against the pinned 0.1.0-rc.6 typings, whose ILayout carries only
// toggleSidebar/openDetails/closeDetails, so the call stays a structural probe:
// compile-green against those typings, and inert rather than throwing if a
// host ever drops the method.
//
// Kept DOM-free and dependency-free (like sessions-compat.ts) so it is directly
// unit-testable and compiles against either generation's typings — never import
// the layout service types here.

/** Structural view across generations. */
interface LayoutLike {
  selectPanel?: unknown
}

/**
 * The "leave the panel" action, or null when the host has no panel-selection
 * API at all (rc.6).
 *
 * The sidebar panel list is an alpha.2-era surface, so on rc.6 nothing arms
 * this — but the capability is probed rather than assumed, because a plugin
 * that throws on an older host is worse than one that goes inert.
 *
 * @param layout - `ctx.layout` as handed to the plugin.
 * @returns a no-argument action calling `selectPanel(null)`, or null.
 */
export function panelSelectorOf(layout: unknown): (() => void) | null {
  if (typeof layout !== 'object' || layout === null) return null
  const select = (layout as LayoutLike).selectPanel
  if (typeof select !== 'function') return null
  return (): void => {
    ;(select as (panelId: null) => void).call(layout, null)
  }
}
