import type { ReconcilerTask } from '../core/reconciler-core.ts';
/**
 * Image lightbox zoom (dsh-client-ui-primitives ImageLightbox).
 *
 * Background: the host lightbox (a body-portal div[role=dialog][aria-modal]
 * whose stage holds an img + a close button) ships no zoom at all, while the
 * page-level touch-action in layout.css.ts grants pinch-zoom to the document.
 * So a two-finger gesture over the image used to zoom the whole WebView. This
 * effect gives the IMAGE its own transform and keeps the browser gesture out
 * of the lightbox layer:
 *  - the dialog root carries data-mobile-nav="image-lightbox" while open;
 *  - only the img gets translate3d(x,y,0) scale(s) (never zoom:, never the
 *    standalone scale property), with transform-origin 50% 50%;
 *  - pointer events, gesturestart and touchmove are attached to the dialog
 *    root ONLY. Never document: cdp-zoom-probe A5 dispatches gesturestart on
 *    document and the #45 contract requires it not to be prevented; a
 *    document-level listener here would turn that probe red and kill page
 *    pinch on every other surface.
 * The math lives in core/image-zoom-core.ts (DOM-free, unit-tested); this
 * module owns listeners and the transform string only.
 *
 * Lifecycle: a ReconcilerTask (scopes ['*']) so the full-tree observer wakes
 * it when the portal mounts/unmounts and when the mobile breakpoint
 * deactivates. Everything it creates lives in this factory closure, so the
 * dispose() path strips every listener, attribute and inline style with no
 * module-level state left behind. A host generation that changes the portal
 * shape simply stops being detected (lazy deactivation) instead of throwing.
 */
export declare function createImageLightboxZoomTask(): ReconcilerTask;
//# sourceMappingURL=image-lightbox-zoom.d.ts.map