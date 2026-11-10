import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { installMobileEffect } from '../platform/breakpoints.ts'

/**
 * 加号「再点关闭」的接管（2026-09-23 二轮修订，推翻上一轮的根因判断）。
 *
 * 症状：加号点开候选菜单后，**再点加号关不掉**（菜单原地不动，等于又开一次）。
 *
 * 真根因（读 0.1.7 产物得出，另有 `aria-expanded` 旁证）：
 * `dsh-client-ui-input-trigger` 的 `toggleSource()` 本来就会关 ——
 *
 *   toggleSource(source, hit) {
 *     if (this.launcher.getSnapshot() === source && this.menu.getSnapshot().open) {
 *       this.dismiss()            // ← 第二击本应走这里
 *       return
 *     }
 *     …打开…
 *   }
 *
 * 但加号的 onClick 先跑 `focusDraftEditor(editor, revealSelection)`，编辑器一有
 * update 就回调 `onEditorUpdate()` → `inputTriggers.track(...)`；而 `track()` 开头是
 *
 *   const launched = this.launcher.getSnapshot() !== null
 *   this.clearLauncher()                              // ← launcher 被清成 null
 *   const raw = detectTrigger(draft, caret, guard)
 *   if (raw === null) { if (launched) return; … }     // ← 菜单留着，launcher 却没了
 *
 * ⇒ 轮到 toggleSource 时 `launcher` 已是 null，"已开就关"这一支永远不可达，
 *   于是每一击都等于"再开一次"。
 * 旁证：`+` 的 `aria-expanded` = `useMenuLauncher(s => s === 'command')`，
 *   菜单明明开着它却恒为 false —— 正是 launcher 被 track 清掉后的直接读数。
 *
 * 上一轮的两个误判，一并纠正在这里：
 *   · `shell.dismissPopup()` 关的是 `commandUi.popupFor()` 的 **popupSelect 壳**
 *     （选完指令后的选项面板，此刻根本没开），跟这个菜单无关，删它不解决问题；
 *   · 这个菜单是 `input-trigger` 的 MenuView：根节点带 **`data-trigger-menu`**，
 *     `role="listbox"` 只是它内部那一层 viewport；它挂在 `[data-composer-card]`
 *     里面，MenuView 的 outside-pointerdown 还专门豁免了这张卡片
 *     （`listRef.closest('[data-composer-card]')`），所以点卡片内的加号根本不会
 *     触发它的关闭。而 popupSelect 壳的卡片**没有任何 role**，上一轮按 role 找它
 *     必然找不到（`probe_esc` 全程 `menu=none` 就是这么来的）。
 *
 * 修法（只补这一个缺口，宿主行为原样保留）：
 *   click **捕获**阶段记下"点加号之前菜单是不是开着"；
 *   click **冒泡**阶段（React 挂在 root 容器上的监听器早已跑完）若菜单**仍然**开着，
 *   说明宿主的关闭分支又被 launcher 清空吃掉了 —— 这时才补一刀：朝 Lexical 根
 *   （`[data-composer-input]`）发一次 Escape。
 *   Escape 是宿主自己的关闭路径（编辑器的 escape 命令 → `arbitrate('escape')`
 *   → `reduce({ close })`），而且只认挂在编辑器根上的 keydown —— 必须 dispatch 在
 *   编辑器上，不能像上一轮那样发在菜单元素上（那边事件根本到不了 Lexical）。
 * 菜单本来就关着时（第一击的开启路径）完全不介入。
 */

/** 宿主加号按钮（dsh-client-ui-conversation 0.2.2 的 uV2eYG_add）；带包前缀，
 *  不再用裸 `_add` 子串——它会误中 `_addon`/`_address` 一类无关类名。 */
const ADD_SELECTOR = '[class*="uV2eYG_add"]'
/** slash/命令候选菜单的根：MenuView 自带这个标记，比样式哈希稳定。 */
const MENU_SELECTOR = '[data-trigger-menu]'
/** Lexical 的可编辑根：Escape 只在这里被宿主映射成命令。 */
const EDITOR_SELECTOR = '[data-composer-input]'

/** 收起后 React 会立刻摘掉节点，这里再兜一层"看得见才算开着"。 */
const isVisible = (el: Element): boolean => {
  const box = el.getBoundingClientRect()
  return box.width > 0 && box.height > 0 && el.getClientRects().length > 0
}

const openMenu = (): Element | null => {
  for (const el of document.querySelectorAll(MENU_SELECTOR)) if (isVisible(el)) return el
  return null
}

const editorEl = (): HTMLElement | null => {
  const el = document.querySelector(EDITOR_SELECTOR)
  return el instanceof HTMLElement ? el : null
}

/**
 * 命令菜单不需要软键盘。
 *
 * 真机机制（2026-09-23 三次取证）：点 `+` 之前编辑器往往**还"逻辑上"聚焦着**
 * （用户滚屏收起了键盘，DOM focus 没走），宿主的 `keepFocus` 又对按钮的 mousedown
 * 做了 `preventDefault()` ⇒ 这一下不会 blur，于是 Android 在真实手势里把刚收起的
 * IME 重新顶起来（探针：点前 `k754`，点后 ~170ms `k471`），整行上移 ~283 CSS px，
 * 店主第二下点的"加号原位"就落进软键盘了（页面收不到任何事件）。
 * 守卫那套影子只拦**程序化 focus()**，拦不到这条 IME 路径，所以必须主动收：
 * 按下加号的捕获阶段先把 DOM 焦点放掉，IME 就没有可依附的编辑面。
 */
const dropEditorFocus = (): void => {
  const editor = editorEl()
  if (editor !== null && document.activeElement === editor) editor.blur()
}

/** 走宿主自己的关闭路径。菜单没开时它一路 no-op（`arbitrate` 回 'pass'），可以放心重发。 */
const escapeEditor = (): void => {
  const editor = editorEl()
  if (editor === null) return
  editor.dispatchEvent(
    new KeyboardEvent('keydown', {
      key: 'Escape',
      code: 'Escape',
      keyCode: 27,
      which: 27,
      bubbles: true,
      cancelable: true,
    }),
  )
}

export function installComposerPlusToggle(ctx: ClientContext): void {
  installMobileEffect(ctx, 'dsh-web-mobile: composer plus toggle', () => {
    /** 这一击落下之前菜单开着吗（捕获阶段读，早于宿主 onClick）。 */
    let openBeforeClick = false
    /** 菜单开着期间的兜底收键盘计时器；用户一点编辑面就全部取消。 */
    let collapseTimers: number[] = []

    const cancelCollapse = (): void => {
      for (const id of collapseTimers) window.clearTimeout(id)
      collapseTimers = []
    }

    const onClickCapture = (event: Event): void => {
      const target = event.target
      openBeforeClick =
        target instanceof Element && target.closest(ADD_SELECTOR) !== null && openMenu() !== null
    }

    const onPointerDown = (event: Event): void => {
      const target = event.target
      if (!(target instanceof Element)) return
      if (target.closest(ADD_SELECTOR) === null) return
      // 断掉 IME 的依附面（见 dropEditorFocus 的注释）。
      dropEditorFocus()
    }

    const onClickBubble = (event: Event): void => {
      const wasOpen = openBeforeClick
      openBeforeClick = false
      const target = event.target
      if (!(target instanceof Element) || target.closest(ADD_SELECTOR) === null) return
      // 宿主那套 focus → track(clearLauncher) → toggle 此刻已经跑完：
      // 菜单还开着 = 它的关闭分支又被吃了，由我们关；已经关掉就什么都不做。
      if (wasOpen && openMenu() !== null) escapeEditor()
      // 兜底：宿主可能在"菜单打开后的 effect"里再聚焦一次，把键盘重新顶起来。
      // 只在菜单开着时按，用户一碰编辑面就全撤（见 onEditorPointerDown）。
      cancelCollapse()
      for (const delay of [120, 320, 640]) {
        collapseTimers.push(
          window.setTimeout(() => {
            if (openMenu() !== null) dropEditorFocus()
          }, delay),
        )
      }
    }

    /** 用户点了编辑面 = 要打字，任何兜底收键盘立刻作废（别和手指抢）。 */
    const onEditorPointerDown = (event: Event): void => {
      const target = event.target
      if (!(target instanceof Element)) return
      if (target.closest(EDITOR_SELECTOR) === null) return
      cancelCollapse()
    }

    document.addEventListener('pointerdown', onPointerDown, true)
    document.addEventListener('pointerdown', onEditorPointerDown, true)
    document.addEventListener('click', onClickCapture, true)
    document.addEventListener('click', onClickBubble, false)
    return () => {
      cancelCollapse()
      document.removeEventListener('pointerdown', onPointerDown, true)
      document.removeEventListener('pointerdown', onEditorPointerDown, true)
      document.removeEventListener('click', onClickCapture, true)
      document.removeEventListener('click', onClickBubble, false)
    }
  })
}
