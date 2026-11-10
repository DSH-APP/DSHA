/** The host card's own height ceiling: `max-height: min(60vh, 520px)`. Kept as
 *  numbers so "does arming fullscreen actually add room" is a comparison
 *  against the host's own formula instead of a copied magic number. */
export declare const HOST_CARD_HEIGHT_RATIO = 0.6;
export declare const HOST_CARD_HEIGHT_CAP_PX = 520;
/** Gap kept between the answer field and the card body's edges when the field
 *  is scrolled back into view. The field's own block padding is 8px, so the
 *  text keeps a readable margin instead of grazing the box. */
export declare const ANSWER_FIELD_MARGIN_PX = 8;
/** Everything the band needs, measured by the effect in viewport coordinates. */
export interface QuestionBandInput {
    /** min(innerHeight, visualViewport.height) — the band the IME leaves. */
    visibleHeight: number;
    /** Frame top → header bottom (`--mobile-nav-header-h`). */
    headerHeight: number;
    /** The question frame's bottom edge (its box bottom, padding included). */
    frameBottom: number;
    /** The composer seat's bottom edge; sticky pins it to the band's bottom. */
    seatBottom: number;
    /** The frame's own vertical padding (top + bottom). */
    framePadding: number;
}
export interface QuestionBand {
    /** Height given back below the card: seat-to-band gap + what sits under the
     *  frame inside the seat + the frame's own padding. */
    reservePx: number;
    /** Height the card may take in fullscreen mode. 0 when the measurement is
     *  not trustworthy — the caller must then leave the host default alone. */
    bandPx: number;
}
/** The fullscreen band: the visible strip between the header's bottom and the
 *  composer's top, minus what the card does not own.
 *
 *  Fail-open on every unverified input: a wrong 0 means "keep the host's own
 *  min(60vh, 520px)", which is the pre-existing state, while a wrong positive
 *  would size the card against a band that is not on screen (overlapping the
 *  header, or taller than the viewport). */
export declare function questionBandPx(input: QuestionBandInput): QuestionBand;
/** The host's own ceiling for the same viewport, i.e. what the card would get
 *  without this feature. 0 when the viewport is unreadable. */
export declare function hostCardHeightPx(visibleHeight: number): number;
/** Whether the fullscreen marker is on.
 *
 *  - `requested` is the user's manual toggle (`false` always wins: the owner
 *    asked for a manual button, never for an automatic expansion);
 *  - entering needs proof that fullscreen actually adds room: a band at or
 *    below the host's own min(60vh, 520px) would only redraw the same card;
 *  - leaving only happens when the measurement becomes unusable, never because
 *    the band shrank. The keyboard opening shrinks the band on every IME frame;
 *    dropping out of fullscreen there would be the exact 「上下猛跳」 this
 *    feature exists to remove (same hysteresis reasoning as nextComposerTight
 *    in core/composer-clearance.ts). */
export declare function nextQuestionFull(previous: boolean, requested: boolean, bandPx: number, hostDefaultPx: number): boolean;
/** The scrollable box the answer field lives in, plus the field's own rect. */
export interface AnswerFieldView {
    /** Answer field's top edge, viewport coordinates. */
    fieldTop: number;
    /** Answer field's bottom edge, viewport coordinates. */
    fieldBottom: number;
    /** Card body's top edge (its own box, not yet clipped by the screen). */
    bodyTop: number;
    /** Card body's bottom edge (its own box, not yet clipped by the screen). */
    bodyBottom: number;
    /** Current scrollTop of the card body. */
    scrollTop: number;
    /** scrollHeight / clientHeight of the card body. */
    scrollHeight: number;
    clientHeight: number;
    /** Visual viewport's top edge in the same (layout-viewport) coordinates.
     *  Optional: omit it and the body box is taken at face value, which is
     *  correct on engines that resize the layout viewport for the IME. */
    visibleTop?: number;
    /** Visual viewport's bottom edge (`offsetTop + height`), same coordinates. */
    visibleBottom?: number;
}
/** Where the card body has to scroll so the answer field is inside its visible
 *  box again, or the current scrollTop when nothing needs to move.
 *
 *  The visible box is the body's own box **intersected with the visual
 *  viewport**: with `adjustResize` the layout viewport shrinks and bodyBottom
 *  already is the screen edge, but a platform that instead pans the visual
 *  viewport leaves the body box hanging below the pixel band the user can
 *  actually see. In that model "field is inside bodyBottom" is true while the
 *  user still types blind, so the caller hands us the visual viewport and the
 *  intersection is what counts (2026-10-25 residual report).
 *
 *  Deliberately NOT scrollIntoView: that walks every scrollable ancestor and
 *  would move the conversation behind the card (the host owns that scroll and
 *  re-anchors it while streaming). Only the card body moves, and only when the
 *  field is actually outside the box — a field taller than the body is aligned
 *  to its bottom edge, which is where the caret is. */
export declare function answerFieldScrollTop(view: AnswerFieldView): number;
//# sourceMappingURL=question-fullscreen.d.ts.map