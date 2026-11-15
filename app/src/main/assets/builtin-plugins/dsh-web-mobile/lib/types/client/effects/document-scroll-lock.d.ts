import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
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
export declare function installDocumentScrollLock(ctx: ClientContext): void;
//# sourceMappingURL=document-scroll-lock.d.ts.map