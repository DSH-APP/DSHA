import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { installMobileEffect } from '../platform/breakpoints.ts'

/**
 * 触屏：只有「手指点在输入框上」才允许唤起软键盘（owner 2026-11-05）。
 *
 * 报障：「随便一个返回就会唤起输入法，真的好烦」——宿主大量流程会在挂载、
 * 返回、切换页签时代码里 `el.focus()`（或给输入框挂 `autoFocus`），桌面上这是
 * 体贴，手机上每一次都等于把键盘顶到屏幕下半张。
 *
 * 做法：在移动档内包一层 `focus`，对**文本输入面**（input 的文本类 type、
 * textarea、contenteditable）加一个手势门：
 *   - 最近 GESTURE_WINDOW_MS 内，用户真的在该元素（或它的祖先/后代）上按下过 →
 *     放行，走原生 focus（键盘正常弹出）；
 *   - 否则吞掉这次 focus（元素不获得焦点，键盘不弹）。
 * 非文本目标（按钮、菜单容器、对话框、`tabindex=-1` 的壳）一律不受影响 ——
 * 宿主靠它们做焦点管理，压掉会破坏键盘/读屏导航。
 *
 * 为什么改原型而不是逐个改调用方：宿主自己有几十处 `ref.current?.focus()` /
 * `autoFocus`（见 docs 里的清点），插件不可能逐一改写；原型层是唯一能把
 * 「有没有真实手势」统一判定的位置。
 *
 * 作用域：MOBILE_QUERY（<1024 且粗指针）。桌面保留宿主原有的自动聚焦。
 * 与 composer-keyboard-guard（iOS 的 keepFocus 影子）互不影响：两者都只是
 * 「让某次 focus 不生效」，叠加时先命中者返回即可。
 */

/** 输入卡根：卡内的按下不在这里收键盘（见 blurUnfocusedTap）。 */
const COMPOSER_CARD_SELECTOR = '[data-composer-card]'

/** 用户手指在输入面上的记录有效时长：轻点后 React 的回调链通常 <100ms。 */
const GESTURE_WINDOW_MS = 1200

/** 会唤起软键盘的 input type（file/checkbox/button 等不算）。 */
const TEXT_INPUT_TYPES = new Set([
  '', 'text', 'search', 'email', 'url', 'tel', 'password', 'number',
  'date', 'time', 'datetime-local', 'month', 'week',
])

/**
 * 这个元素是不是「文本输入面」——即聚焦它就会弹出软键盘。
 * @param element - 待判定元素。
 * @returns 是文本输入面时为 true。
 */
export function isTextEntry(element: Element | null): boolean {
  if (element === null) return false
  const tag = element.tagName
  if (tag === 'TEXTAREA') return true
  if (tag === 'INPUT') {
    const type = (element as HTMLInputElement).type ?? ''
    return TEXT_INPUT_TYPES.has(type.toLowerCase())
  }
  const editable = element.getAttribute('contenteditable')
  return editable !== null && editable !== 'false'
}

/**
 * 这次 focus 该不该被吞掉。
 * @param element - 收到 focus() 的元素。
 * @param gestureTarget - 最近一次真实手势落在的元素（无手势为 null）。
 * @param sinceGestureMs - 距那次手势的毫秒数。
 * @returns 需要拦截时为 true。
 */
export function shouldBlockProgrammaticFocus(
  element: Element,
  gestureTarget: Element | null,
  sinceGestureMs: number,
): boolean {
  if (!isTextEntry(element)) return false
  if (gestureTarget === null) return true
  if (sinceGestureMs > GESTURE_WINDOW_MS) return true
  // 手势落在输入面上、或落在这颗输入框包着的子节点里 → 放行。
  return !(element === gestureTarget || element.contains(gestureTarget) || gestureTarget.contains(element))
}

type Focusable = { focus: (options?: FocusOptions) => void }

export function installFocusGestureGuard(ctx: ClientContext): void {
  installMobileEffect(ctx, 'dsh-web-mobile: focus requires a real tap', () => {
    let gestureTarget: Element | null = null
    let gestureAt = 0

    /**
     * 点输入框以外的地方 = 收起键盘。
     *
     * 只拦「程序化 focus」还不够：真机 2026-11-05（返回会话时输入法自己弹起来）——
     * 输入框在打开面板前拿过焦点，面板撤掉时 WebView 会把软键盘还给那颗**已经聚焦**
     * 的可编辑面，这一步不经过任何 JS `focus()`，拦不到。所以按下的位置不在输入面上时
     * 主动 blur 掉当前文本焦点：面板再关掉时就没有「已聚焦的输入框」可还原，
     * 键盘也就不会自己冒出来。顺带得到常见行为：点空白处收键盘。
     */
    const blurUnfocusedTap = (target: Element): void => {
      const active = document.activeElement
      if (!(active instanceof Element) || !isTextEntry(active)) return
      if (active === target || active.contains(target) || target.contains(active)) return
      // 输入卡内部的按钮（发送 / 停止 / 📎 / 模型）不在这里收键盘：真机 2026-11-05
      // 「点发送，键盘退下去了但消息没发出去，要再点一次」——手指按下时收掉焦点，
      // 这一次 tap 就被 Android 当成「关键盘」吃掉了，click 不再派发。卡内的收键盘
      // 归宿主自己的 keepFocus 管，这里放行。
      if (target.closest(COMPOSER_CARD_SELECTOR) !== null) return
      if (typeof (active as HTMLElement).blur !== 'function') return
      ;(active as HTMLElement).blur()
    }

    const record = (event: Event): void => {
      const target = event.target
      if (target instanceof Element) {
        gestureTarget = target
        gestureAt = performance.now()
        blurUnfocusedTap(target)
      }
    }

    const isAllowed = (element: Element): boolean =>
      !shouldBlockProgrammaticFocus(
        element,
        gestureTarget,
        gestureTarget === null ? Number.POSITIVE_INFINITY : performance.now() - gestureAt,
      )

    /** 包一层 focus：只拦文本输入面的「非手势」聚焦。 */
    const patch = (owner: { focus?: unknown } | undefined): (() => void) => {
      if (owner === undefined) return () => {}
      const original = owner.focus
      if (typeof original !== 'function') return () => {}
      const wrapper = function focus(this: Element, options?: FocusOptions): void {
        if (isAllowed(this)) (original as Focusable['focus']).call(this, options)
      }
      try {
        Object.defineProperty(owner, 'focus', {
          configurable: true, writable: true,
          enumerable: Object.getOwnPropertyDescriptor(owner, 'focus')?.enumerable ?? false,
          value: wrapper,
        })
      } catch {
        return () => {}
      }
      return () => {
        if (Object.getOwnPropertyDescriptor(owner, 'focus')?.value !== wrapper) return
        try {
          Object.defineProperty(owner, 'focus', { configurable: true, writable: true, value: original })
        } catch {
          // 被外部改写：规则已移除，保持现状即可。
        }
      }
    }

    const disposers: Array<() => void> = []
    disposers.push(patch(globalThis.HTMLElement?.prototype))
    if (typeof HTMLInputElement !== 'undefined') disposers.push(patch(HTMLInputElement.prototype))
    if (typeof HTMLTextAreaElement !== 'undefined') disposers.push(patch(HTMLTextAreaElement.prototype))

    /**
     * 兜底：**任何**来源的聚焦都在 focusin 复核一次。
     *
     * 原型上包 focus 只能拦住 JS 主动调用；真机 2026-11-05 第二次取证（返回会话时
     * 键盘仍自己弹起）说明还有别的入口：面板被移除时浏览器会把焦点**还原**给上一个
     * 聚焦过的可编辑面，这条路径根本不调 focus()，是引擎自己做的。focusin 是它们
     * 共同的出口——不管谁把焦点放上去，只要不是手指点在这颗输入面上，当场收掉。
     * 代价是这类「还原」可能闪一帧键盘，但不会停留。
     */
    const onFocusIn = (event: Event): void => {
      const target = event.target
      if (!(target instanceof Element)) return
      if (isAllowed(target)) return
      if (typeof (target as HTMLElement).blur) (target as HTMLElement).blur()
    }

    document.addEventListener('pointerdown', record, true)
    document.addEventListener('touchstart', record, true)
    document.addEventListener('mousedown', record, true)
    document.addEventListener('focusin', onFocusIn, true)

    return () => {
      document.removeEventListener('focusin', onFocusIn, true)
      document.removeEventListener('pointerdown', record, true)
      document.removeEventListener('touchstart', record, true)
      document.removeEventListener('mousedown', record, true)
      for (const dispose of disposers) dispose()
    }
  })
}
