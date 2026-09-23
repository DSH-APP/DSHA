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
export declare function panelSelectorOf(layout: unknown): (() => void) | null;
//# sourceMappingURL=layout-compat.d.ts.map