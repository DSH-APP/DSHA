import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
import type { ReconcilerTask } from '../core/reconciler-core.ts';
/**
 * 抽屉页脚账户行右端的一键设置齿轮。
 *
 * 任务靠 `getFrame()` 只在移动分支存在时注入（桌面帧标记不存在 → 直接 no-op），
 * `scopes: ['*']` 让宿主 React 重建该行后由 `ensure()` 幂等重挂。
 *
 * @param ctx - 客户端根上下文：locale 文案。
 * @returns reconciler 任务。
 */
export declare function createDrawerQuickSettingsTask(ctx: ClientContext): ReconcilerTask;
//# sourceMappingURL=drawer-quick-settings.d.ts.map