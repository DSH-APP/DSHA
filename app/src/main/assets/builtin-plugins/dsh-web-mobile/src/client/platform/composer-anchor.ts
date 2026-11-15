/**
 * Which element inside the composer is the "ring" readout, and which one merely
 * LOOKS like it.
 *
 * The ring is found by walking up from the stats line and taking the first
 * sibling whose text carries a percentage. That shape also matches things that
 * must never be touched:
 *
 *   2026-10-31, iPhone CriOS 156, host 0.2.1 — the composer card's own text
 *   carried a percentage, so the walk picked the *card* as the ring. The plugin
 *   marked it `data-mobile-nav="stats-ring"` and the fold wrote `left/top` on it,
 *   which threw the whole composer to x=299,y=30: input squeezed to 22px, seat
 *   collapsed to 34px, buttons parked off the right edge, frozen until a refresh.
 *   Captured live: `<div class="…_card" data-composer-card="true"
 *   data-mobile-nav="stats-ring" style="left: 299px; top: 30px;">`.
 *
 * A ring is a small readout, so anything that is or contains the composer — the
 * card, the editing surface, or any control — is disqualified by construction
 * rather than by a size threshold.
 */
const COMPOSER_OR_CONTROL =
  '[data-composer-card], [data-composer-input], [contenteditable], input, textarea, button'

/**
 * True when `element` is, or contains, the composer or a control.
 *
 * The test doubles used by this package implement only the parts of the DOM a
 * pass touches, so both calls are probed instead of assumed.
 */
export function carriesComposer(element: Element): boolean {
  const matcher = (element as Element).matches
  if (typeof matcher === 'function' && matcher.call(element, COMPOSER_OR_CONTROL)) return true
  const query = (element as Element).querySelector
  return typeof query === 'function' && query.call(element, COMPOSER_OR_CONTROL) !== null
}

/**
 * True when the element actually draws a progress ring.
 *
 * The ring in the host is `ContextMeter` (`@deepseek-ai/dsh-client-ui-conversation`,
 * `skeleton/ContextMeter.tsx`): a 14x14 `<svg>` holding two `<circle>`s, the
 * second of which sets `stroke-dasharray` to the fraction used. That dash array
 * is what makes it a progress ring — it is a property of the drawing, not of the
 * wording around it, so it survives copy changes, locale changes and any node
 * that merely *mentions* a percentage (the composer card's model line does).
 *
 * Test doubles implement only the handful of DOM calls a pass touches, so a
 * missing `querySelectorAll`/`getComputedStyle` falls back to the old text test
 * — the shipped behaviour is ring-first, the fallback only keeps the stubs
 * honest.
 */
function hasProgressRing(element: Element): boolean {
  const query = (element as Element).querySelectorAll
  if (typeof query !== 'function' || typeof getComputedStyle !== 'function') {
    return /\d\s*%/.test(element.textContent ?? '')
  }
  for (const circle of query.call(element, 'svg circle')) {
    const dash = getComputedStyle(circle as Element).strokeDasharray
    if (dash !== '' && dash !== 'none' && !/^(0px?)(\s+0px?)*$/.test(dash)) return true
  }
  return false
}

/** First sibling of the stats line that is not the stats line, not composer, and draws a ring. */
export function isRingCandidate(child: Element, stats: Element): boolean {
  if (child.contains(stats)) return false
  if (carriesComposer(child)) return false
  return hasProgressRing(child)
}

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
export function locateRing(stats: Element): { dock: Element; ring: Element } | null {
  const dock = stats.closest('[data-composer-dock]')
  if (dock !== null) {
    const siblings = [...dock.children].filter((child) =>
      !child.contains(stats) && !carriesComposer(child) && !child.hasAttribute('data-mobile-nav'),
    )
    const ring = siblings.find(hasProgressRing) ?? (siblings.length === 1 ? siblings[0] : undefined)
    if (ring !== undefined) return { dock, ring }
  }
  const stack = stats.closest('[class*="_composerStack"]')
  for (let node = stats.parentElement; node !== null; node = node.parentElement) {
    const ring = [...node.children].find((child) => isRingCandidate(child, stats))
    if (ring !== undefined) return { dock: node, ring }
    if (node === stack) break
  }
  return null
}
