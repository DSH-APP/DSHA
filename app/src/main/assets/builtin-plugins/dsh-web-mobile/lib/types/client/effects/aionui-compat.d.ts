import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
import { type ReconcilerTask } from './phone-chrome.ts';
/** dsh-web-ui 兼容：explorer / preview 列的显隐标记与升起动画（同域同机制，合并一处）。 */
export declare function installAionuiCompat(ctx: ClientContext): void;
export declare function createPreviewCloseTask(): ReconcilerTask;
export declare function createSheetRiseTask(): ReconcilerTask;
//# sourceMappingURL=aionui-compat.d.ts.map