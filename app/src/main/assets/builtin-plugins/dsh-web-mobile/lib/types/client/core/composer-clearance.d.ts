/** How far the page scroll offset and the visual viewport's offsetTop may drift before the
 *  screen-space model counts as unverified. Same value and reasoning as
 *  composer-keyboard-lift.ts's CHANNEL_TOLERANCE_PX, restated here because
 *  core/ may not import effects/ (layer direction) and the tolerance is part
 *  of this module's fail-open contract, not of that effect. */
export declare const SCROLL_CHANNEL_TOLERANCE_PX = 24;
/** Occlusion is only real from 1px on: sub-pixel overlap is rect rounding, not
 *  a hidden editing row. Entering at 0 would also flip the marker on every
 *  fractional resize tick. */
export declare const TIGHT_ENTER_PX = 1;
/** The measured band, in viewport coordinates. */
export interface ComposerBand {
    /** The smaller of the layout and visual viewport heights — the band the IME leaves
     *  (same `available` quantity viewport-meta.ts publishes). */
    visibleHeight: number;
    /** Conversation header's bottom edge. */
    headerBottom: number;
    /** Seat height with every row visible (the *unmitigated* height on purpose). */
    seatHeight: number;
    /** Measured seat bottom edge. Sticky pins it to the band's bottom while the
     *  layout and visual viewports agree; a stale layout viewport shows up here. */
    seatBottom: number;
    /** The page scroll offset (layout-viewport scroll). */
    scrollY: number;
    /** `visualViewport.offsetTop`. */
    offsetTop: number;
}
/** Pixels of the composer seat the user cannot see, 0 when the model is not
 *  trusted. Fail-open on every unverified input: a wrong 0 (the status quo)
 *  costs nothing, a wrong positive would re-stack the header under the
 *  composer on devices that never had the defect. */
export declare function composerOccludedPx(band: ComposerBand): number;
/** Hysteresis for the marker: enter on a real overlap, leave only when the
 *  occlusion is gone. Without the dead band a 1px IME animation frame or a
 *  rounding tick would toggle the layout on and off. */
export declare function nextComposerTight(previous: boolean, occludedPx: number): boolean;
//# sourceMappingURL=composer-clearance.d.ts.map