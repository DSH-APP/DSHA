import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { installMobileEffect } from '../platform/breakpoints.ts'

/**
 * 触屏：软键盘的回车键是「换行」，不是「发送」（2026-11-05 owner 真机要求）。
 *
 * 宿主行为（dsh-client-ui-conversation 0.2.2-rc.1，内置 Lexical）：输入框在
 * KEY_ENTER_COMMAND 上挂了 CRITICAL 优先级（4）的处理器——无修饰键的 Enter 直接
 * `handlers.submit()`，只有 Shift+Enter 放行给纯文本插件插入换行。手机软键盘没有
 * Shift，长按回车也不出换行，于是回车只能发送。回车到达 KEY_ENTER_COMMAND 有两条路：
 *   ① 真实 keydown（Gboard 等直接派 Enter 键）；
 *   ② 输入法组字结束时文本以 '\n' 收尾，Lexical 以 `null` 事件派发同一命令——此时
 *      没有事件对象可改，只能在命令层拦。
 * 所以本效果在命令层接管：给当前编辑器注册一个排在宿主前面的 KEY_ENTER_COMMAND
 * 处理器（Lexical 的 registerCommand 在「优先级带高位标记」时 addFront，同桶内先于
 * 宿主执行），对「普通回车」改派 INSERT_LINE_BREAK_COMMAND 并吞掉命令，宿主的发送
 * 分支不会再跑。发送改由发送按钮完成（宿主按钮不受影响）。
 *
 * 放行给宿主的情形（行为与桌面一致）：
 *   - Ctrl/Cmd/Alt + Enter：外接键盘的发送 / 加速发送快捷键；
 *   - Shift + Enter：宿主本来就放行给换行，不重复处理；
 *   - 输入法组字中的回车（`isComposing`）：确认候选词，宿主自己吞掉；
 *   - 斜杠 / @ 候选菜单打开时（`[data-trigger-menu]` 可见）：回车是「选中候选项」。
 *
 * 命令对象不由宿主导出：Lexical 的命令是 `{ type: 'KEY_ENTER_COMMAND' }` 形状的单例，
 * 编辑器把注册过的命令存在 `editor._commands`（Map，键即命令对象）。按 type 找回同一个
 * 对象即可注册；找不到（宿主换编辑器 / 未来改名）就什么都不做，回车保持宿主原样。
 * 编辑器实例取自 contenteditable 根上的 `__lexicalEditor`（Lexical 自己写的反向引用）。
 *
 * 作用域：MOBILE_QUERY（<1024 且粗指针）。平板 768–1023 同样生效（软键盘一样没有 Shift）。
 * 每次编辑器根获得焦点时幂等挂钩（会话切换 / 子代理视图会换新编辑器）。
 */

/** composer 的 Lexical contenteditable 根。 */
const COMPOSER_INPUT_SELECTOR = '[data-composer-input]'
/** 斜杠 / @ 候选菜单根（宿主 MenuView 自带标记）。 */
const TRIGGER_MENU_SELECTOR = '[data-trigger-menu]'
/** Lexical COMMAND_PRIORITY_CRITICAL；带 8 位标记让 registerCommand 走 addFront。 */
const FRONT_OF_CRITICAL = 4 | 8

/** 回车事件里与判定相关的最小形状（便于纯函数单测）。 */
export interface EnterKeyLike {
  readonly shiftKey: boolean
  readonly ctrlKey: boolean
  readonly metaKey: boolean
  readonly altKey: boolean
  readonly isComposing: boolean
}

/**
 * 这一次 KEY_ENTER_COMMAND 是否应改成换行。
 * @param event - 键盘事件；输入法组字结束派发的命令没有事件对象（null），按普通回车处理。
 * @param menuOpen - 候选菜单是否可见。
 * @returns 应插入换行时为 true；false 表示交回宿主（发送 / 选中候选 / 宿主自己的换行）。
 */
export function shouldEnterInsertNewline(event: EnterKeyLike | null, menuOpen: boolean): boolean {
  if (menuOpen) return false
  if (event === null) return true
  if (event.isComposing) return false
  if (event.shiftKey || event.ctrlKey || event.metaKey || event.altKey) return false
  return true
}

/** Lexical 编辑器里本效果用到的最小面。 */
interface LexicalCommand { readonly type?: unknown }
interface LexicalEditorLike {
  readonly _commands?: Map<LexicalCommand, unknown>
  registerCommand(command: LexicalCommand, listener: (payload: unknown) => boolean, priority: number): () => void
  dispatchCommand(command: LexicalCommand, payload: unknown): boolean
}

/**
 * 按 type 找回编辑器已注册的命令单例。
 * @param editor - Lexical 编辑器。
 * @param type - 命令 type 字符串。
 * @returns 命令对象；未注册时为 null。
 */
export function findCommand(editor: Pick<LexicalEditorLike, '_commands'>, type: string): LexicalCommand | null {
  const commands = editor._commands
  if (!(commands instanceof Map)) return null
  for (const command of commands.keys()) {
    if (command !== null && typeof command === 'object' && command.type === type) return command
  }
  return null
}

function editorOf(root: Element): LexicalEditorLike | null {
  const editor = (root as Element & { __lexicalEditor?: unknown }).__lexicalEditor
  if (editor === null || typeof editor !== 'object') return null
  const face = editor as Partial<LexicalEditorLike>
  return typeof face.registerCommand === 'function' && typeof face.dispatchCommand === 'function'
    ? editor as LexicalEditorLike
    : null
}

/** 候选菜单是否真的看得见（收起后 React 可能还没摘节点）。 */
function triggerMenuOpen(): boolean {
  for (const menu of document.querySelectorAll(TRIGGER_MENU_SELECTOR)) {
    const box = menu.getBoundingClientRect()
    if (box.width > 0 && box.height > 0) return true
  }
  return false
}

export function installComposerEnterNewline(ctx: ClientContext): void {
  installMobileEffect(ctx, 'dsh-web-mobile: composer enter inserts newline', () => {
    /** 已挂钩的编辑器 → 撤销函数。WeakMap：编辑器被宿主丢弃后自然回收。 */
    const hooked = new WeakMap<LexicalEditorLike, () => void>()
    /** 撤销用的强引用表（WeakMap 不可遍历）。 */
    const disposers = new Set<() => void>()

    const hook = (root: Element): void => {
      const editor = editorOf(root)
      if (editor === null || hooked.has(editor)) return
      const enter = findCommand(editor, 'KEY_ENTER_COMMAND')
      const lineBreak = findCommand(editor, 'INSERT_LINE_BREAK_COMMAND')
      if (enter === null || lineBreak === null) return
      const unregister = editor.registerCommand(enter, (payload) => {
        const event = payload instanceof KeyboardEvent ? payload : null
        if (payload !== null && event === null) return false
        if (!shouldEnterInsertNewline(event, triggerMenuOpen())) return false
        event?.preventDefault()
        // 宿主纯文本插件的同一路径：false = 光标停在换行之后。
        editor.dispatchCommand(lineBreak, false)
        return true
      }, FRONT_OF_CRITICAL)
      const dispose = (): void => {
        unregister()
        disposers.delete(dispose)
        hooked.delete(editor)
      }
      hooked.set(editor, dispose)
      disposers.add(dispose)
    }

    /**
     * 按事件目标补挂（幂等）。只靠 focusin 不够：宿主在 hero → active 相位切换、
     * 会话切换、子代理视图这些时机换掉编辑器实例，换掉之后若不再触发 focusin
     * （键盘一直弹着、焦点没变），新编辑器上就没有我们的处理器，回车又变回发送
     * ——真机 2026-11-05「有概率发送」正是这个窗口。所有能引出 KEY_ENTER_COMMAND
     * 的事件都在捕获期先补挂：keydown 与 beforeinput 都早于 Lexical 自己的根监听，
     * 所以补挂能赶在同一拍生效。
     */
    const ensure = (event: Event): void => {
      const target = event.target
      if (!(target instanceof Element)) return
      const root = target.closest(COMPOSER_INPUT_SELECTOR)
      if (root !== null) hook(root)
    }

    document.addEventListener('focusin', ensure, true)
    document.addEventListener('keydown', ensure, true)
    document.addEventListener('beforeinput', ensure, true)
    // 安装时键盘可能已经弹着：当前聚焦的编辑器也挂上。
    const active = document.activeElement?.closest(COMPOSER_INPUT_SELECTOR)
    if (active instanceof Element) hook(active)

    return () => {
      document.removeEventListener('focusin', ensure, true)
      document.removeEventListener('keydown', ensure, true)
      document.removeEventListener('beforeinput', ensure, true)
      for (const dispose of [...disposers]) dispose()
    }
  })
}
