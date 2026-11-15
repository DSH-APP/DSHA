import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { detectIosWebKit } from '../platform/device.ts'
import { installMobileEffect } from '../platform/breakpoints.ts'

/**
 * iOS keyboard guard for the composer's fixed control cluster.
 *
 * Upstream `dsh-client-ui-conversation` (0.1.2-rc.1) hangs the same
 * `keepFocus` handler on the composer row's three buttons (send, stop, the
 * `+` commands trigger):
 *
 *   const keepFocus = (e) => {
 *     e.preventDefault()
 *     editor?.getRootElement()?.focus({ preventScroll: true })
 *   }
 *
 * `onMouseDown` keeps the caret in the editor across button clicks on
 * desktop. On iOS WebKit the same handler runs inside the tap's synthesized
 * mousedown, and that programmatic `focus()` call re-raises the on-screen
 * keyboard whenever it had closed (scroll-to-dismiss, keyboard dismissal,
 * PWA relaunch) while logical focus never left the contenteditable. The
 * user taps Send on a collapsed keyboard and the keyboard springs back up
 * over the running conversation — the message still sends, but the screen
 * is now half keyboard.
 *
 * Fix strategy, scoped to iOS WebKit (the engine that re-raises keyboards
 * from a programmatic focus; Android/desktop behavior is untouched):
 *
 * In the capture phase of every mousedown whose target sits inside the
 * composer card but is NOT the editing surface itself, temporarily install
 * an own no-op `focus` property on the `[data-composer-input]` element.
 * React's `keepFocus` then calls the shadow instead of the prototype
 * method, the keyboard stays down, and the shadow is removed on the next
 * macrotask so nothing outlives the tap:
 *
 *   capture mousedown → shadow focus → (bubbling) keepFocus → click →
 *   macrotask restore
 *
 * Why shadowing instead of intercepting the event: `keepFocus`'s own
 * `preventDefault()` must keep running (it stops the tap from blurring
 * the editor), and the editor's own tap-to-type path must never be
 * touched — only the button-initiated programmatic focus is undesirable
 * on iOS. A capture-phase `stopPropagation` would break both.
 *
 * DOM contract (verified against 0.1.2-rc.1 dsh-client-ui-conversation):
 * - `[data-composer-card]` — the composer card root (InputBar).
 * - `[data-composer-input]` — the Lexical contenteditable surface.
 * - The buttons carry hashed `_primary`/`_add` classes and no stable
 *   data marker, so the card boundary (not the buttons) is the anchor.
 * Audit both markers when the conversation package upgrades.
 */

/** The composer card root that owns the fixed control cluster. */
const COMPOSER_CARD_SELECTOR = '[data-composer-card]'

/** The Lexical editing surface (the only element allowed to raise the keyboard). */
const COMPOSER_INPUT_SELECTOR = '[data-composer-input]'

/** Interactive controls inside the card — the only presses whose handlers
 *  (keepFocus on send/stop/+, the + onClick's focusDraftEditor) refocus the
 *  editor programmatically. Blank card padding/gaps are not controls. */
const COMPOSER_CONTROL_SELECTOR =
  'button, [role="button"], [role="option"], [role="menuitem"], [role="combobox"], [aria-haspopup], select, a[href], label, input'

/** Re-arm marker kept on the editor element while its focus is shadowed.
 *  Exported for its source-contract test. (Its former second user,
 *  session-focus-guard.ts, was removed 2026-11-05: host 0.2.2's InputBar skips
 *  the mount-time editor focus on mobile devices.) */
export const SHADOW_MARKER = 'data-mobile-nav-focus-shadow'

/** 键盘至少吃掉这么多像素才算「弹着」（滚动条/地址栏抖动远小于此，最矮的输入法也远大于此）。 */
const KEYBOARD_MIN_PX = 120

/**
 * 软键盘此刻是否弹着：无键盘高度（phone-chrome 维护的 --dsh-web-mobile-vh，只增不减）与可见带高度之差。
 * 平板档不跑 phone-chrome，变量缺席时退回 innerHeight —— adjustResize 下会把「弹着」误判成「收着」，
 * 后果只是这一下按钮会把键盘收起，不会反过来把收着的键盘顶起来。
 */
function keyboardUp(): boolean {
  const stable = Number.parseFloat(document.documentElement.style.getPropertyValue('--dsh-web-mobile-vh'))
  const base = Number.isFinite(stable) && stable > 0 ? stable : window.innerHeight
  const visible = window.visualViewport?.height ?? window.innerHeight
  return base - visible > KEYBOARD_MIN_PX
}

export function installComposerKeyboardGuard(ctx: ClientContext): void {
  installMobileEffect(ctx, 'dsh-web-mobile: composer keyboard guard', () => {
    // 2026-09-23 扩档（店主报"点加号会弹键盘、而且再点关不掉"）：
    // `+` 的 onClick 是宿主的 onToggleCommandMenu，它先 focusDraftEditor()
    // 再 toggleCommandMenu(caretSpan) —— 命令菜单需要光标，于是每次点 + 都
    // 把键盘顶起来。键盘一开，整行上移 ~283px（真机探针：composer y 687→404），
    // 店主第二次点的是"加号原来的位置"，自然关不掉，看起来像 toggle 坏了。
    // 同一段 focusDraftEditor 在 iOS 上就是本守卫要拦的调用，所以把启用条件
    // 从"仅 iOS WebKit"放宽到"触屏档（pointer: coarse）"：桌面（精细指针）保持
    // 原样，手机/平板上一律不让 composer 按钮去抢编辑器焦点。拦截的是**程序
    // 化** focus()，原生点输入框聚焦不受影响（点输入框的路径已被下面的 early
    // return 排除）。
    const ios = detectIosWebKit(navigator, typeof CSS !== 'undefined' && typeof CSS.supports === 'function' ? CSS.supports.bind(CSS) : null)
    const coarse = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches
    if (!ios && !coarse) {
      return undefined
    }

    /** 影子撤除计时器（每次按钮点按重置；见 onPointerDown 里的时间轴注释）。 */
    let shadowTimer = 0

    /**
     * 撤影子 **并且关掉守卫窗口**。
     *
     * 2026-09-23 二次修订（店主报"点两下加号之后输入框动不了了"）：
     * 原来这里只删影子、不归零 `shadowTimer`，而下面 `onFocusIn` 的开关就是
     * `shadowTimer === 0` —— 于是**点过一次 composer 按钮之后守卫永久生效**：
     * 任何 focusin 都被当场 `blur()`，店主点输入框再也弹不出键盘。
     * 真机行为级取证（探针：先合成一次 composer 按钮 pointerdown，等过 700ms 窗口，
     * 再 blur + focus 编辑器）：
     *   修前 `shadowAttrLeft=false ownFocusLeft=false blurWorked=true focusHeld=false k=754`
     *   ⇒ 影子已撤、计时器却还挂着 ⇒ 守卫一直在，编辑器拿不回焦点。
     */
    const restore = (): void => {
      window.clearTimeout(shadowTimer)
      shadowTimer = 0
      const el = document.querySelector<HTMLElement>(`[${SHADOW_MARKER}]`)
      if (el === null) return
      el.removeAttribute(SHADOW_MARKER)
      const shadowed = el as Partial<Record<'focus', () => void>>
      if (Object.prototype.hasOwnProperty.call(el, 'focus')) delete shadowed.focus
    }

    const onPointerDown = (event: Event): void => {
      const target = event.target
      if (!(target instanceof Element)) return
      if (typeof target.closest !== 'function') return
      const card = target.closest(COMPOSER_CARD_SELECTOR)
      if (card === null) return
      const editor = card.querySelector<HTMLElement>(COMPOSER_INPUT_SELECTOR)
      if (editor === null) return
      // 店主自己点编辑面：这是"我要打字"的正路，立刻解除守卫窗口，
      // 绝不让兜底 blur 打到这一下（窗口内点输入框也必须能弹键盘）。
      if (target.closest(COMPOSER_INPUT_SELECTOR) !== null) {
        restore()
        return
      }
      // 2026-10-04（B2「长按粘贴被吞」）：只有按在**控件**上才开窗口。卡片里的
      // 空白（输入区滚动层的留白、输入区与按钮行之间 12px 的 gap 等）不是 keepFocus
      // 的来源，却曾经同样开出 700ms 的 blur 窗口：长按落在这些空白上时 pointerdown
      // 命中空白容器，而引擎的长按手势经触点校正（touch adjustment）把光标放进编辑面
      // 并聚焦它——约 500ms，正在窗口内——onFocusIn 当场 blur，系统菜单的「粘贴」
      // 随后派发到 body，内容不进草稿（headless 探针：修前长按 gap 处 paste 目标 BODY、
      // 草稿不变；修后 paste 进编辑面）。空白处按下按"要打字"处理，与按在编辑面上
      // 同路；控件判定限定在卡片内，卡片外的祖先不算。
      const control = target.closest(COMPOSER_CONTROL_SELECTOR)
      if (control === null || !card.contains(control)) {
        restore()
        return
      }
      // A button-area tap: shadow focus for the remainder of this dispatch.
      restore()
      // 2026-11-04 店主报障「输完字收起输入法，点发送又弹出来」：返回键收起输入法不会让编辑面失焦，
      // keepFocus 又 preventDefault 掉了按钮的 mousedown 失焦 ⇒ 点完发送，引擎看到「焦点仍在可编辑元素上
      // 的一次真实点按」就把输入法顶回来——没有 focus() 调用、也没有 focusin，影子和兜底都拦不到。
      // 键盘收着时先把焦点从编辑面拿走：之后提交清空草稿时 Lexical 的 root.focus() 落在下面的影子上，
      // 兜底 focusin 也还在窗口内。键盘弹着时不动，连续发送照旧不收键盘。草稿与光标在宿主 keyboard 状态里。
      if (document.activeElement === editor && !keyboardUp()) editor.blur()
      editor.setAttribute(SHADOW_MARKER, '')
      Object.defineProperty(editor, 'focus', {
        configurable: true,
        writable: true,
        value: function swallowedFocus(): void {
          /* keepFocus called; keep the dismissed keyboard dismissed */
        },
      })
      // 影子的存活窗口 = 700ms 固定窗口，**不能**"click 后立刻撤"。
      // 2026-09-23 真机探针的事件轨迹（点一次 `+`）：
      //   51.5 clicks:添加文件或调用指令 / shadow:ON
      //   51.6 shadow:off          ← 旧的"click 后 setTimeout(0) 撤"
      //   51.7 vv 754→471          ← 键盘此时才弹 ⇒ 宿主是在"菜单打开后的 effect"
      //                              里再 focus 一次，撤早了等于白装。
      // 影子只拦**程序化** focus()；窗口内用户点输入框由上面那个 early return
      // 当场解除窗口，所以放宽到 700ms 是安全的；窗口内新的按钮点按会重置计时。
      window.clearTimeout(shadowTimer)
      shadowTimer = window.setTimeout(restore, 700)
    }

    // 2026-09-23：只挂 mousedown 会空转。Android WebView 上按钮的点击经常吃不到
    // 兼容性 mousedown（touchstart 被 preventDefault 时更甚），于是影子从没装上，
    // 宿主 click 里的 focusDraftEditor 照样把键盘顶起来 —— 店主实测"两个问题都还在"。
    // 三个入口都挂上，处理体是幂等的（每次先 restore 再重装影子）。
    // 兜底：万一"按钮点按 → 编辑器被聚焦"仍然把键盘顶起来（真机可能走
    // 我们拦不到的路径），在同一个 700ms 窗口内立刻把焦点还回去 —— blur 会
    // 收起软键盘。宿主的光标/草稿来自它自己的 keyboard 状态，不依赖 DOM focus，
    // 所以这里 blur 不会丢草稿（用户随后点输入框照常输入）。
    const onFocusIn = (event: Event): void => {
      if (shadowTimer === 0) return
      const target = event.target
      if (!(target instanceof HTMLElement)) return
      if (target.closest(COMPOSER_INPUT_SELECTOR) === null) return
      // 同步 blur：放到宏任务里 IME 已经开始弹了（真机实测 setTimeout 版无效，
      // vv 仍然 754→471）。在 focusin 的捕获阶段当场 blur，键盘根本不会出现。
      target.blur()
    }

    document.addEventListener('pointerdown', onPointerDown, true)
    document.addEventListener('touchstart', onPointerDown, true)
    document.addEventListener('mousedown', onPointerDown, true)
    document.addEventListener('focusin', onFocusIn, true)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown, true)
      document.removeEventListener('touchstart', onPointerDown, true)
      document.removeEventListener('mousedown', onPointerDown, true)
      document.removeEventListener('focusin', onFocusIn, true)
      restore()
    }
  })
}
