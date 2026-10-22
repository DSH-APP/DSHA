import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
import type { ReconcilerTask } from '../core/reconciler-core.ts';
/**
 * The drawer's brand-row export icon (replaces the footer pill; issue #225).
 *
 * The task is scoped to tree changes (`*`) because React may recreate the
 * host's logo row on any sidebar re-render; `ensure()` re-mounts the wrapper
 * idempotently. The button never sits directly in the row (see `mount()`).
 * `dispose()` (mobile breakpoint teardown or task removal) unsubscribes and
 * removes the node.
 *
 * @param ctx - client root context: locale, sessions list and the host's
 *   session-log download service.
 * @returns the reconciler task.
 */
export declare function createDrawerSessionLogTask(ctx: ClientContext): ReconcilerTask;
//# sourceMappingURL=drawer-session-log.d.ts.map