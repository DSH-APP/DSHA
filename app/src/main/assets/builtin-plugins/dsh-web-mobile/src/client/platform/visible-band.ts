/** Dispatched on `document` right after viewport-meta publishes
 *  `--dsha-mobile-visible-vh`. The publish waits for the keyboard to settle
 *  (two equal frames), so it lands after the last resize event; effects that lay
 *  out against the variable re-measure on this instead of racing it (2026-11-03). */
export const VISIBLE_BAND_EVENT = 'dsh-mobile-visible-band'
