import type { ReconcilerTask } from '../core/reconciler-core.ts'
import {
  clampTranslation,
  doubleTapTarget,
  identityZoom,
  pinchFactor,
  zoomAround,
} from '../core/image-zoom-core.ts'
import type { Point, ZoomState } from '../core/image-zoom-core.ts'

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
export function createImageLightboxZoomTask(): ReconcilerTask {
  let root: HTMLElement | null = null
  let image: HTMLImageElement | null = null
  let zoom: ZoomState = identityZoom()
  const pointers = new Map<number, Point>()
  let pan: {
    pointerId: number
    startX: number
    startY: number
    originX: number
    originY: number
    moved: boolean
    startedOnImage: boolean
  } | null = null
  let pinch: {
    startSpan: Point
    startMid: Point
    startZoom: ZoomState
    anchor: Point
  } | null = null
  let lastTap: { at: number; x: number; y: number } | null = null

  const viewport = (): { width: number; height: number } => ({
    width: window.innerWidth,
    height: window.innerHeight,
  })

  /** The portal root this effect recognises: a body child dialog whose stage
   *  holds the preview image as a direct child. Settings and other modals are
   *  direct body children too, hence the img shape test rather than "first". */
  const findLightbox = (): { root: HTMLElement; image: HTMLImageElement } | null => {
    for (const candidate of document.body.querySelectorAll(':scope > [role="dialog"][aria-modal="true"]')) {
      if (!(candidate instanceof HTMLElement)) continue
      const found = candidate.querySelector(':scope > :first-child > img')
      if (found instanceof HTMLImageElement) return { root: candidate, image: found }
    }
    return null
  }

  const clearImage = (): void => {
    if (image === null) return
    image.style.transform = ''
    image.style.transformOrigin = ''
    image.style.willChange = ''
    image = null
  }

  const detach = (): void => {
    if (root !== null) {
      root.removeEventListener('pointerdown', onPointerDown)
      root.removeEventListener('pointermove', onPointerMove)
      root.removeEventListener('pointerup', onPointerUp)
      root.removeEventListener('pointercancel', onPointerCancel)
      root.removeEventListener('gesturestart', onGestureStart)
      root.removeEventListener('touchmove', onTouchMove)
      if (root.getAttribute('data-mobile-nav') === 'image-lightbox') {
        root.removeAttribute('data-mobile-nav')
      }
    }
    clearImage()
    pointers.clear()
    pan = null
    pinch = null
    lastTap = null
    zoom = identityZoom()
    root = null
  }

  /** untransformed image centre in viewport coordinates (the transform's
   *  scale is about the centre, so only the translate has to be undone). */
  const baseCentre = (): Point => {
    if (image === null) return { x: 0, y: 0 }
    const rect = image.getBoundingClientRect()
    return { x: rect.left + rect.width / 2 - zoom.x, y: rect.top + rect.height / 2 - zoom.y }
  }

  const paint = (): void => {
    if (image === null) return
    image.style.transform = 'translate3d(' + zoom.x + 'px, ' + zoom.y + 'px, 0) scale(' + zoom.scale + ')'
  }

  const startPinch = (): void => {
    const points = [...pointers.values()]
    const first = points[0]
    const second = points[1]
    if (first === undefined || second === undefined) return
    const startMid = { x: (first.x + second.x) / 2, y: (first.y + second.y) / 2 }
    const centre = baseCentre()
    pinch = {
      startSpan: { x: second.x - first.x, y: second.y - first.y },
      startMid,
      startZoom: { ...zoom },
      anchor: { x: startMid.x - centre.x, y: startMid.y - centre.y },
    }
    pan = null
  }

  const onPointerDown = (event: PointerEvent): void => {
    if (event.pointerType === 'mouse') return
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY })
    if (pointers.size >= 2) {
      startPinch()
      return
    }
    pan = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      originX: zoom.x,
      originY: zoom.y,
      moved: false,
      startedOnImage: event.target === image,
    }
  }

  const onPointerMove = (event: PointerEvent): void => {
    if (!pointers.has(event.pointerId)) return
    const point = { x: event.clientX, y: event.clientY }
    pointers.set(event.pointerId, point)
    if (pointers.size >= 2) {
      if (pinch === null) {
        startPinch()
        return
      }
      const points = [...pointers.values()]
      const first = points[0]
      const second = points[1]
      if (first === undefined || second === undefined) return
      const mid = { x: (first.x + second.x) / 2, y: (first.y + second.y) / 2 }
      const factor = pinchFactor(pinch.startSpan, { x: second.x - first.x, y: second.y - first.y })
      const scaled = zoomAround(pinch.startZoom, factor, pinch.anchor)
      zoom = clampTranslation(
        {
          scale: scaled.scale,
          x: scaled.x + (mid.x - pinch.startMid.x),
          y: scaled.y + (mid.y - pinch.startMid.y),
        },
        viewport(),
      )
      paint()
      return
    }
    if (pan === null || pan.pointerId !== event.pointerId || !pan.startedOnImage) return
    const dx = point.x - pan.startX
    const dy = point.y - pan.startY
    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) pan.moved = true
    zoom = clampTranslation({ scale: zoom.scale, x: pan.originX + dx, y: pan.originY + dy }, viewport())
    paint()
  }

  const onPointerUp = (event: PointerEvent): void => {
    if (!pointers.delete(event.pointerId)) return
    if (pointers.size >= 2) {
      startPinch()
      return
    }
    if (pointers.size === 1) {
      // One finger lifted first: the remaining finger keeps panning from the
      // current zoom instead of jumping back to its pre-pinch origin.
      const remaining = [...pointers.entries()][0]
      pinch = null
      pan = null
      if (remaining !== undefined) {
        const [id, point] = remaining
        pan = {
          pointerId: id,
          startX: point.x,
          startY: point.y,
          originX: zoom.x,
          originY: zoom.y,
          moved: true,
          startedOnImage: true,
        }
      }
      return
    }
    const tap = pan
    pan = null
    pinch = null
    if (tap === null || tap.pointerId !== event.pointerId || !tap.startedOnImage || tap.moved) {
      lastTap = null
      return
    }
    const at = Date.now()
    if (
      lastTap !== null
      && at - lastTap.at <= 320
      && Math.hypot(event.clientX - lastTap.x, event.clientY - lastTap.y) <= 28
    ) {
      zoom = clampTranslation(doubleTapTarget(zoom), viewport())
      lastTap = null
      paint()
      return
    }
    lastTap = { at, x: event.clientX, y: event.clientY }
  }

  const onPointerCancel = (event: PointerEvent): void => {
    pointers.delete(event.pointerId)
    if (pointers.size < 2) pinch = null
    if (pointers.size === 0) {
      pan = null
      lastTap = null
    }
  }

  // iOS gesture events bubble from the touched element; keeping this on the
  // dialog root stops the WebView's own pinch but leaves every other surface
  // (and the document-level #45 contract) untouched.
  const onGestureStart = (event: Event): void => {
    event.preventDefault()
  }

  // Pointer-events belt and braces: some WebViews still deliver TouchEvents
  // first. Two touches on the lightbox are always the image pinch, never a
  // page pinch, so the fallback cancels the default only then.
  const onTouchMove = (event: TouchEvent): void => {
    if (event.touches.length > 1) event.preventDefault()
  }

  const attach = (nextRoot: HTMLElement, nextImage: HTMLImageElement): void => {
    root = nextRoot
    image = nextImage
    zoom = identityZoom()
    pointers.clear()
    pan = null
    pinch = null
    lastTap = null
    root.setAttribute('data-mobile-nav', 'image-lightbox')
    image.style.transformOrigin = '50% 50%'
    image.style.willChange = 'transform'
    root.addEventListener('pointerdown', onPointerDown)
    root.addEventListener('pointermove', onPointerMove)
    root.addEventListener('pointerup', onPointerUp)
    root.addEventListener('pointercancel', onPointerCancel)
    root.addEventListener('gesturestart', onGestureStart)
    root.addEventListener('touchmove', onTouchMove, { passive: false })
  }

  return {
    name: 'image-lightbox-zoom',
    scopes: ['*'],
    ensure: () => {
      const found = findLightbox()
      if (found === null) {
        detach()
        return
      }
      if (root === found.root) {
        if (image !== found.image) {
          clearImage()
          image = found.image
          zoom = identityZoom()
          image.style.transformOrigin = '50% 50%'
          image.style.willChange = 'transform'
        }
        return
      }
      detach()
      attach(found.root, found.image)
    },
    dispose: () => {
      detach()
    },
  }
}
