// Question card fullscreen answer mode (owner report 2026-10-25, item 2).
//
// 症状：长问题 + 键盘弹起后，「打字时看不到自己打的字，收起键盘才看到」，内容
// 整体上下猛跳。owner 明确要一个**手动**「展开」按钮 = 全屏回答，不要自动全屏。
//
// 机制（推导与实测数字在 core/question-fullscreen.ts 的文件头）：
//  - 宿主 Mbwy4a_card 自带 `max-height: min(60vh, 520px)`，而这台 Android WebView
//    （adjustResize）上 vh 跟着软键盘一起缩（754 → 471），卡片的高度预算从 452
//    掉到 283；
//  - 卡片里唯一能滚的 Mbwy4a_body 把自定义回答框（body 的最后一块）滚出可视区，
//    浏览器只在聚焦那一刻替它滚过一次，卡片缩小时不会再滚。
// 所以本效果做两件事：
//  ① 往卡头 Mbwy4a_headerActions 注入插件自己的展开/收起按钮，点击在**插件 frame**
//    上切 `data-mobile-nav-question-full`；CSS 用
//    「--dsha-mobile-visible-vh - --mobile-nav-header-h - 实测 reserve」把卡片撑满
//    「顶栏底 → composer 顶」的可见带（styles/layout.css.ts 同一节）。
//  ② 把回答框重新滚回卡片 body 的可视区（focusin + 视口变化各一次）。这条**不依赖
//    全屏态**——不进全屏也要修「打字看不见」。可见区取 body 盒 ∩ 视觉视口：有的平台
//    不缩 layout 视口、只平移视觉视口，此时 body 盒底部仍在屏幕外，只按 body 盒算
//    会误判「已经可见」（2026-10-25 残余报障）。
//
// 注入路线（为什么注入卡头，而不是把控件绝对定位挂在 frame/card 上）：
// 宿主这一代的 Mbwy4a_headerActions 是普通 flex 行，卡头由 React 渲染，但 React 只
// 对自己的 host 子节点调用 insertBefore/removeChild，我们追加的按钮不在它的 fiber
// 树里、也不占它的 childNodes 参照位置，所以既不会被摘掉，也不会让宿主卸载时
// removeChild 失败（pitfalls §搬宿主 React 节点 说的是搬走**宿主节点**，方向相反）。
// 宿主整体重建卡头时我们的节点随之消失，由 reconciler 任务 `scopes:['*']` 的 ensure
// 幂等重挂——与 effects/drawer-quick-settings.ts、effects/preview-fullscreen.ts 同一
// 形状。绝对定位方案被否决：多一层定位包含块、卡高变化要重算坐标、还要自己管 z 序，
// 收益只是少一次 appendChild。
//
// 与 issue #245 的紧凑态（`data-mobile-nav-composer-tight`）共存：
//  - 两个标记都挂在同一个 frame 上，互不读取、互不覆盖，可以同时为真；
//  - 那条把座位抬到 z-index 31（压过顶栏 30）并收起 dock 行，本条只改卡片高度；
//  - 本条用**可见带** `--dsha-mobile-visible-vh`（键盘会缩），不是弹层用的
//    `--dsh-web-mobile-vh`（键盘不缩）：全屏回答要跟着键盘让位，被键盘盖住的那块
//    绝不能算进可用高度。
//
// Fail-open：`--dsha-mobile-visible-vh` / `--mobile-nav-header-h` 任一读不到（宿主
// 换代、header-metrics 还没跑）⇒ 纯核返回 band 0 ⇒ 不武装标记，卡片保持宿主自己的
// `min(60vh, 520px)`。测量值只在变化时写回 DOM：`style` / `class` 都在全树 reconciler
// 的 attributeFilter 里，无条件写就是自激（同 header-metrics 的 publish）。
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import {
  answerFieldScrollTop,
  hostCardHeightPx,
  nextQuestionFull,
  questionBandPx,
} from '../core/question-fullscreen.ts'
import type { ReconcilerTask } from '../core/reconciler-core.ts'
import { NS } from '../i18n/locales.ts'
import { installMobileEffect } from '../platform/breakpoints.ts'
import { getFrame } from '../platform/frame.ts'
import { VISIBLE_BAND_EVENT } from '../platform/visible-band.ts'
import { addReconcilerTask } from './reconciler-adapter.ts'

/** Frame marker: the card owns the whole answer band. */
const FULL_MARKER = 'data-mobile-nav-question-full'

/** Measured "everything under the card" height, consumed by the fullscreen CSS
 *  as the only number it does not derive from the two published variables. */
const RESERVE_VAR = '--dsha-mobile-question-reserve'

/** Our control; also the CSS anchor for its box and its icon swap. */
const BUTTON_MARKER = 'question-expand'

/** Ask-question card family (`QuestionComposer.module.css`, hash substring). */
const CARD_FRAME_SELECTOR = '[class*="Mbwy4a_frame"]'
const HEADER_ACTIONS_SELECTOR = '[class*="Mbwy4a_headerActions"]'
const CARD_BODY_SELECTOR = '[class*="Mbwy4a_body"]'
const ANSWER_FIELD_SELECTOR = '[class*="Mbwy4a_fieldInput"]'

/** The host composer seat (same anchor as composer-clearance.ts): the card's
 *  bottom edge has to be measured against the seat it sits in. */
const SEAT_SELECTOR = '[class*="_composerSeat"]'

/** Published viewport facts, written INLINE on <html> by viewport-meta.ts
 *  (`--dsha-mobile-visible-vh`) and header-metrics.ts (`--mobile-nav-header-h`).
 *  Read from the inline declaration on purpose: no style resolution, and the
 *  numbers are exactly the ones the stylesheet will use. */
const VISIBLE_VH_VAR = '--dsha-mobile-visible-vh'
const HEADER_H_VAR = '--mobile-nav-header-h'

/** Expand = corners out, collapse = corners in, same 16-grid / 1.5-stroke
 *  language as the host's own card icons (and as preview-fullscreen.ts). The
 *  two are swapped by CSS on the frame marker, so the JS never rewrites markup. */
const ICONS = [
  '<svg class="dsh-web-mobile-question-expand" viewBox="0 0 16 16" fill="none" aria-hidden="true">',
  '<path d="M6 2H2v4M10 2h4v4M6 14H2v-4M10 14h4v-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>',
  '</svg>',
  '<svg class="dsh-web-mobile-question-collapse" viewBox="0 0 16 16" fill="none" aria-hidden="true">',
  '<path d="M2 6h4V2M14 6h-4V2M2 10h4v4M14 10h-4v4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>',
  '</svg>',
].join('')

/** Inline custom-property read; NaN when the variable is absent. */
const readVarPx = (name: string): number => {
  const raw = document.documentElement.style.getPropertyValue(name).trim()
  const value = Number.parseFloat(raw)
  return Number.isFinite(value) ? value : Number.NaN
}

/**
 * Fullscreen answer for the ask-question card: the injected expand/collapse
 * toggle, the measured band marker, and the answer field's way back into the
 * card body.
 *
 * Registered from index.tsx next to the other composer effects. Everything
 * lives inside one mobile effect so a width change tears the listeners and the
 * reconciler task down together (and re-arms them on the way back to a phone).
 *
 * @param ctx - client root context: locale dictionary for the button labels.
 */
export function installQuestionFullscreen(ctx: ClientContext): void {
  const t = ctx.locale.bind(NS)
  installMobileEffect(ctx, 'dsh-web-mobile: question fullscreen', () => {
    /** The user's manual toggle. Set only by a tap, cleared when the card goes
     *  away — fullscreen must never re-arm itself (owner: 手动展开，不要自动全屏). */
    let requested = false
    /** Whether the frame currently carries the marker (= the button's state). */
    let armed = false
    /** Injected control; kept across host re-renders and re-attached by ensure. */
    let button: HTMLButtonElement | null = null
    /** The card frame we wrote the reserve variable on (cleared on dispose). */
    let published: HTMLElement | null = null
    /** Last reserve text written; '' when nothing was written. */
    let reserveText = ''
    let frame = 0

    // 2026-11-03 性能：本任务 scopes:['*']，流式时每批变动都跑；原先每次都在整个 frame
    // 上做一次 [class*=] 子串查询，卡片不在时（绝大多数时间）就是一次全树扫。卡片只会
    // 出现在 composer 座位里，所以先缓存座位、只在座位子树里找；找到的卡片也缓存，
    // 仍连在座位里就直接复用。座位不在（老宿主 / 空态）时退回原来的整 frame 查询。
    let seatCache: HTMLElement | null = null
    let cardCache: HTMLElement | null = null
    const composerSeat = (host: HTMLElement): HTMLElement | null => {
      if (seatCache !== null && seatCache.isConnected && host.contains(seatCache)) return seatCache
      seatCache = host.querySelector<HTMLElement>(SEAT_SELECTOR)
      return seatCache
    }
    const cardFrame = (): HTMLElement | null => {
      const host = getFrame()
      if (host === null) return null
      const seat = composerSeat(host)
      const scope = seat ?? host
      if (cardCache !== null && cardCache.isConnected && scope.contains(cardCache)) return cardCache
      cardCache = scope.querySelector<HTMLElement>(CARD_FRAME_SELECTOR)
      return cardCache
    }

    const label = (): string => t(armed ? 'questionCollapse' : 'questionExpand')

    /** Reflect the state on the button. Only on change: attribute writes wake
     *  the full-tree reconciler, this task included. */
    const syncButton = (): void => {
      if (button === null) return
      const text = label()
      if (button.getAttribute('aria-label') !== text) {
        button.setAttribute('aria-label', text)
        button.title = text
      }
      const expanded = armed ? 'true' : 'false'
      if (button.getAttribute('aria-expanded') !== expanded) {
        button.setAttribute('aria-expanded', expanded)
      }
    }

    /** Drop every trace of the feature and forget the user's intent. */
    const clear = (): void => {
      requested = false
      armed = false
      if (frame !== 0) {
        cancelAnimationFrame(frame)
        frame = 0
      }
      const host = getFrame()
      if (host !== null && host.hasAttribute(FULL_MARKER)) host.removeAttribute(FULL_MARKER)
      published?.style.removeProperty(RESERVE_VAR)
      published = null
      reserveText = ''
      syncButton()
    }

    /** Scroll the answer field back inside the card body's visible box, but
     *  only while it holds focus (a scroll the user started must not be undone)
     *  and only when it actually left the box. Never focuses, never touches the
     *  host's draft state, never scrolls an ancestor (see
     *  core/question-fullscreen.ts on why not scrollIntoView).
     *
     *  The visual viewport is handed to the pure core as the clipping rectangle
     *  (2026-10-25 residual report): a platform that pans the visual viewport
     *  instead of resizing the layout viewport leaves the body box below the
     *  band the user can see, so "inside bodyBottom" is not "on screen". On
     *  `adjustResize` engines `offsetTop` is 0 and the intersection is a no-op. */
    const keepAnswerVisible = (): void => {
      // Read the focus owner first and only then walk up: on the keyboard's
      // resize storm this runs once per frame, and probing for the card with two
      // document queries would pay that cost even when no answer field is
      // focused (i.e. every keyboard episode that is not about this card).
      const active = document.activeElement
      if (!(active instanceof HTMLElement) || !active.matches(ANSWER_FIELD_SELECTOR)) return
      // 2026-11-03：手机档滚动区从 body 换成了整张卡（layout.css.ts question-card-single-scroll），
      // 所以滚卡片；卡片不滚（老样式 / 最小化）时仍退回 body。底部钉住的按钮行要从
      // 可视底边里扣掉，否则回答框会停在按钮后面。
      const cardEl = active.closest<HTMLElement>('[class*="Mbwy4a_card"]')
      const bodyEl = active.closest<HTMLElement>(CARD_BODY_SELECTOR)
      const body = cardEl !== null && cardEl.scrollHeight > cardEl.clientHeight + 1 ? cardEl : bodyEl
      if (body === null) return
      const pinned = body === cardEl ? cardEl.querySelector<HTMLElement>(':scope > [class*="Mbwy4a_footer"]') : null
      const field = active
      const fieldRect = field.getBoundingClientRect()
      const box = body.getBoundingClientRect()
      const pinnedTop = pinned?.getBoundingClientRect().top ?? Number.NaN
      const bodyRect = { top: box.top, bottom: Number.isFinite(pinnedTop) ? Math.min(box.bottom, pinnedTop) : box.bottom }
      const visual = window.visualViewport
      const visibleTop = visual?.offsetTop ?? Number.NaN
      const visibleBottom = visual === null || visual === undefined ? Number.NaN : visual.offsetTop + visual.height
      const next = answerFieldScrollTop({
        fieldTop: fieldRect.top,
        fieldBottom: fieldRect.bottom,
        bodyTop: bodyRect.top,
        bodyBottom: bodyRect.bottom,
        scrollTop: body.scrollTop,
        scrollHeight: body.scrollHeight,
        clientHeight: body.clientHeight,
        visibleTop,
        visibleBottom,
      })
      if (Math.abs(next - body.scrollTop) >= 1) body.scrollTop = next
    }

    /** Measure the band, publish the reserve and the marker. One rAF per
     *  viewport episode: the IME animation arrives as a resize storm. */
    const measure = (): void => {
      frame = 0
      const host = getFrame()
      const card = cardFrame()
      if (host === null || card === null) {
        clear()
        return
      }
      const seat = host.querySelector<HTMLElement>(SEAT_SELECTOR)
      const cardRect = card.getBoundingClientRect()
      const seatRect = seat?.getBoundingClientRect() ?? null
      const style = getComputedStyle(card)
      const visible = readVarPx(VISIBLE_VH_VAR)
      const band = questionBandPx({
        visibleHeight: visible,
        headerHeight: readVarPx(HEADER_H_VAR),
        frameBottom: cardRect.bottom,
        seatBottom: seatRect === null ? Number.NaN : seatRect.bottom,
        framePadding:
          Number.parseFloat(style.paddingTop) + Number.parseFloat(style.paddingBottom),
      })
      const next = nextQuestionFull(armed, requested, band.bandPx, hostCardHeightPx(visible))
      const nextReserve = `${Math.round(band.reservePx)}px`
      if (published !== card) {
        published?.style.removeProperty(RESERVE_VAR)
        published = card
        reserveText = ''
      }
      if (reserveText !== nextReserve) {
        reserveText = nextReserve
        card.style.setProperty(RESERVE_VAR, nextReserve)
      }
      if (next !== host.hasAttribute(FULL_MARKER)) {
        if (next) host.setAttribute(FULL_MARKER, '')
        else host.removeAttribute(FULL_MARKER)
      }
      armed = next
      syncButton()
      // A band that cannot be measured must not keep the intent alive: the next
      // successful measurement would otherwise expand the card on its own.
      if (!armed) requested = false
    }

    const schedule = (): void => {
      if (frame !== 0) return
      frame = requestAnimationFrame(() => {
        frame = 0
        keepAnswerVisible()
        if (requested) measure()
      })
    }

    const onClick = (): void => {
      // Toggle the state that is actually on screen, not the intent: an
      // unmeasurable band leaves the marker off, so a second tap must try to
      // arm again instead of "collapsing" a card that never expanded.
      requested = !armed
      if (frame !== 0) {
        cancelAnimationFrame(frame)
        frame = 0
      }
      measure()
      if (!armed) requested = false
    }

    /** Idempotent injection into the card's action row. Inserted just before
     *  the host's last action (the close/cancel button) so the two sizing
     *  controls sit together and the destructive one keeps the far edge; the
     *  row's existing 12px gap then gives this control the same 32px touch
     *  target as the host pair (4px expansion lives in the stylesheet). */
    const ensureButton = (card: HTMLElement): void => {
      const actions = card.querySelector<HTMLElement>(HEADER_ACTIONS_SELECTOR)
      if (actions === null) return
      if (button === null) {
        const element = document.createElement('button')
        element.type = 'button'
        element.dataset.mobileNav = BUTTON_MARKER
        element.innerHTML = ICONS
        element.addEventListener('click', onClick)
        button = element
      }
      // Parent identity only: re-inserting on every flush would be a mutation
      // loop, and the row's order is stable enough for a position check to be
      // noise.
      if (button.parentElement !== actions) {
        actions.insertBefore(button, actions.lastElementChild)
      }
      syncButton()
    }

    const task: ReconcilerTask = {
      name: 'question-fullscreen',
      scopes: ['*'],
      ensure: () => {
        const card = cardFrame()
        if (card === null) {
          clear()
          return
        }
        // Fullscreen only makes sense while the card has an answer area: the
        // host drops Mbwy4a_body when the card is minimized, and the review
        // panel (PlanReviewPanel) never renders one — both keep the header row,
        // so injecting there would offer a toggle with nothing to expand.
        if (card.querySelector(CARD_BODY_SELECTOR) === null) {
          button?.remove()
          clear()
          return
        }
        ensureButton(card)
        // Geometry is not read here: this task wakes on every mutation batch
        // (streaming included), and a rect read per batch is exactly the cost
        // header-metrics.ts removed. One coalesced frame is enough.
        if (requested) schedule()
      },
      dispose: () => {
        clear()
        if (button !== null) {
          button.removeEventListener('click', onClick)
          button.remove()
          button = null
        }
      },
    }

    const removeTask = addReconcilerTask(task)

    // Every episode that relayouts the band without a DOM mutation: the IME
    // animation, the rotation, the keyboard-less restore, and the card mounting
    // under an already-shrunk band. visualViewport.resize is the per-frame IME
    // signal; `scroll` is deliberately absent — panning the visual viewport
    // does not change the card body's own scroll geometry, and listening to it
    // would fight a drag inside the card.
    window.addEventListener('resize', schedule)
    window.addEventListener('orientationchange', schedule)
    window.visualViewport?.addEventListener('resize', schedule)
    // 可见带变量是在键盘停稳后才发布的（viewport-meta），晚于最后一次 resize：
    // 发布那一刻再量一次，否则 reserve 停在发布前的旧几何上。
    document.addEventListener(VISIBLE_BAND_EVENT, schedule)
    document.addEventListener('focusin', schedule, true)
    document.addEventListener('focusout', schedule, true)
    schedule()

    return () => {
      window.removeEventListener('resize', schedule)
      window.removeEventListener('orientationchange', schedule)
      window.visualViewport?.removeEventListener('resize', schedule)
      document.removeEventListener(VISIBLE_BAND_EVENT, schedule)
      document.removeEventListener('focusin', schedule, true)
      document.removeEventListener('focusout', schedule, true)
      removeTask()
    }
  })
}
