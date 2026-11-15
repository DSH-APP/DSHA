import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
/**
 * 这个元素是不是「文本输入面」——即聚焦它就会弹出软键盘。
 * @param element - 待判定元素。
 * @returns 是文本输入面时为 true。
 */
export declare function isTextEntry(element: Element | null): boolean;
/**
 * 这次 focus 该不该被吞掉。
 * @param element - 收到 focus() 的元素。
 * @param gestureTarget - 最近一次真实手势落在的元素（无手势为 null）。
 * @param sinceGestureMs - 距那次手势的毫秒数。
 * @returns 需要拦截时为 true。
 */
export declare function shouldBlockProgrammaticFocus(element: Element, gestureTarget: Element | null, sinceGestureMs: number): boolean;
export declare function installFocusGestureGuard(ctx: ClientContext): void;
//# sourceMappingURL=focus-gesture-guard.d.ts.map