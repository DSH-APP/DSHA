import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { installMobileEffect } from '../platform/breakpoints.ts'

/**
 * Keep the mobile shell from ever being scrolled as a document.
 *
 * Derivation, from the recorder's full-state dumps (1604 snapshots, 2026-10-31):
 *
 *   scrollY === visualViewport.offsetTop     1604 / 1604
 *   frameTop === -scrollY                    1604 / 1604
 *
 * On this engine the "visual viewport pan" the keyboard causes IS the document
 * scroll — there is no second mechanism. And the shell's own geometry is only
 * correct while that scroll is zero: with `scrollY === 0` and the keyboard up,
 * 139 dumps read `seat bottom - frame bottom = -1..0`, i.e. the composer sits
 * exactly on the shell's bottom edge. Once the document has scrolled (131 dumps,
 * 84..339px) the whole shell — composer included — is displaced by exactly that
 * amount, which is the owner's 「输入框被顶飞，下面是空白背景」.
 *
 * Who scrolls it: the host's caret-visibility helper calls `window.scrollBy`
 * (the caller behind issue #149). `overflow: hidden` does not stop that call,
 * and `position: fixed` on html/body (tried first) broke the document paint
 * outright. So the document is pinned back in JS: the scroll lands, we put it
 * back, same task, before paint.
 */
export function installDocumentScrollLock(ctx: ClientContext): void {
  installMobileEffect(ctx, 'dsh-web-mobile: document scroll lock', () => {
    // Undoing the scroll after the fact is not enough: the call itself has to be
    // refused, because one frame of it is already one frame of the shell sitting
    // 339px above the band (11:46:58 dump: sy=339, frame=-339+345, seat=-101 —
    // the owner's 「炸了」). `overflow: hidden` does not stop window.scrollBy and
    // neither does `position: fixed` on html (tried, same dump).
    const nativeScrollBy = window.scrollBy.bind(window)
    const nativeScrollTo = window.scrollTo.bind(window)
    const refuse = (): void => {
      if (window.scrollX !== 0 || window.scrollY !== 0) nativeScrollTo(0, 0)
    }
    const patchedScrollBy = ((...args: unknown[]): void => {
      void args
      refuse()
    }) as typeof window.scrollBy
    const patchedScrollTo = ((...args: unknown[]): void => {
      void args
      refuse()
    }) as typeof window.scrollTo
    const pin = (): void => {
      if (window.scrollX !== 0 || window.scrollY !== 0) nativeScrollTo(0, 0)
    }
    window.scrollBy = patchedScrollBy
    window.scrollTo = patchedScrollTo
    window.addEventListener('scroll', pin, { passive: true })
    document.addEventListener('scroll', pin, true)
    pin()
    return () => {
      window.scrollBy = nativeScrollBy as typeof window.scrollBy
      window.scrollTo = nativeScrollTo as typeof window.scrollTo
      window.removeEventListener('scroll', pin)
      document.removeEventListener('scroll', pin, true)
    }
  })
}
