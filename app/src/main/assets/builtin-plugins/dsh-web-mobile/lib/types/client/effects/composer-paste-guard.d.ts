import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
/** beforeinput 中与本守卫相关的最小形状（便于纯函数单测）。 */
export interface PasteGuardInput {
    readonly inputType: string;
    readonly data: string | null;
    readonly isTrusted: boolean;
    readonly defaultPrevented: boolean;
}
/**
 * 这一次 beforeinput 是否需要改投 paste 通道。
 * 单个 '\n' 是宿主自己的换行命令（Shift+Enter / IME 换行），绝不拦。
 * @param event - beforeinput 的相关字段。
 * @returns 需要改投时为 true。
 */
export declare function shouldRerouteInsertText(event: PasteGuardInput): boolean;
export declare function installComposerPasteGuard(ctx: ClientContext): void;
//# sourceMappingURL=composer-paste-guard.d.ts.map