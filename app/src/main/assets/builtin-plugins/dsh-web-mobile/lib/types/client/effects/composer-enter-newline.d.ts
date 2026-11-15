import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
/** 回车事件里与判定相关的最小形状（便于纯函数单测）。 */
export interface EnterKeyLike {
    readonly shiftKey: boolean;
    readonly ctrlKey: boolean;
    readonly metaKey: boolean;
    readonly altKey: boolean;
    readonly isComposing: boolean;
}
/**
 * 这一次 KEY_ENTER_COMMAND 是否应改成换行。
 * @param event - 键盘事件；输入法组字结束派发的命令没有事件对象（null），按普通回车处理。
 * @param menuOpen - 候选菜单是否可见。
 * @returns 应插入换行时为 true；false 表示交回宿主（发送 / 选中候选 / 宿主自己的换行）。
 */
export declare function shouldEnterInsertNewline(event: EnterKeyLike | null, menuOpen: boolean): boolean;
/** Lexical 编辑器里本效果用到的最小面。 */
interface LexicalCommand {
    readonly type?: unknown;
}
interface LexicalEditorLike {
    readonly _commands?: Map<LexicalCommand, unknown>;
    registerCommand(command: LexicalCommand, listener: (payload: unknown) => boolean, priority: number): () => void;
    dispatchCommand(command: LexicalCommand, payload: unknown): boolean;
}
/**
 * 按 type 找回编辑器已注册的命令单例。
 * @param editor - Lexical 编辑器。
 * @param type - 命令 type 字符串。
 * @returns 命令对象；未注册时为 null。
 */
export declare function findCommand(editor: Pick<LexicalEditorLike, '_commands'>, type: string): LexicalCommand | null;
export declare function installComposerEnterNewline(ctx: ClientContext): void;
export {};
//# sourceMappingURL=composer-enter-newline.d.ts.map