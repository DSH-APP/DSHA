/**
 * True when `element` is, or contains, the composer or a control.
 *
 * The test doubles used by this package implement only the parts of the DOM a
 * pass touches, so both calls are probed instead of assumed.
 */
export declare function carriesComposer(element: Element): boolean;
/** First sibling of the stats line that is not the stats line, not composer, and draws a ring. */
export declare function isRingCandidate(child: Element, stats: Element): boolean;
/**
 * Locate the ring by HIERARCHY first, geometry only as a tie-break.
 *
 * The host mounts both halves in one row — `InputBar.tsx` renders
 *
 *   <div data-composer-dock>
 *     {renderSlot('conversation.composer.dock')}   ← the stats pills (ui-chat)
 *     <ContextMeter />                             ← the ring
 *   </div>
 *
 * so the ring is simply the dock's other child: same parent as the stats pills,
 * not the branch that holds them. `data-composer-dock` is the host's own stable
 * attribute (no hashed class), which is why this survives rebuilds while the old
 * "walk up until something mentions a percentage" walk did not.
 *
 * The composer stack walk stays as a fallback for hosts that render the stats row
 * outside the dock; the ring-shaped check then breaks ties.
 */
export declare function locateRing(stats: Element): {
    dock: Element;
    ring: Element;
} | null;
//# sourceMappingURL=composer-anchor.d.ts.map