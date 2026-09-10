/** One unit of DOM reconciliation driven by the shared full-tree observer. */
export interface ReconcilerTask {
    readonly name: string;
    /**
     * Dirty keys that must be present for this task to re-run on a flush.
     * Undefined = unscoped: run on every non-empty flush (legacy behavior).
     */
    readonly scopes?: readonly string[];
    /** Called once on activation and after every matching DOM mutation. */
    ensure(): void;
    /** Called on deactivation, disposal, or explicit removal. */
    dispose(): void;
}
/**
 * Schedule `flush` on the next frame and return a cancel for a still-pending
 * frame. Injected so the core stays DOM-free: browsers pass an
 * requestAnimationFrame adapter, tests pass a manual queue.
 */
export type FrameRequest = (flush: () => void) => () => void;
/** Error sink for task failures. Defaults to a console.error shim. */
export type ReconcilerErrorHandler = (taskName: string, error: unknown, phase: 'ensure' | 'dispose') => void;
export interface ReconcilerCoreOptions {
    readonly requestFrame: FrameRequest;
    readonly onError?: ReconcilerErrorHandler;
}
export interface ReconcilerCore {
    /** Number of tasks in the registry (registered but not necessarily active). */
    readonly size: number;
    /** Register a task; the returned disposer removes and disposes it. */
    register(task: ReconcilerTask): () => void;
    /** Snapshot the registry as the active task set and run every task once. */
    activate(): void;
    /** Dispose every active task, cancel any pending frame, clear dirty keys. */
    deactivate(): void;
    /** Mark scope keys dirty and schedule one coalesced flush. */
    note(keys: Iterable<string>): void;
    /** Run dirty tasks now (activation runs everything; empty dirty runs none). */
    flush(): void;
}
export declare function createReconcilerCore(options: ReconcilerCoreOptions): ReconcilerCore;
//# sourceMappingURL=reconciler-core.d.ts.map