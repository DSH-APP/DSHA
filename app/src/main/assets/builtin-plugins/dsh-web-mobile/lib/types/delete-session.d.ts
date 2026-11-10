/** Failure stage of a trash move, carried on the thrown error so the caller
 * can describe the observable state accurately. */
type TrashStage = 'rename-payloads' | 'stash';
/** 恢复状态（C10）：`rolled-back` 已把此前改名全部回滚（目录确实恢复原状）；
 * `partial` 仍有 payload 带着 `.trash`（必须如实暴露残留映射，禁止对外说 untouched）；
 * `not-needed` 尚未发生任何改名。 */
type TrashRecovery = 'rolled-back' | 'partial' | 'not-needed';
/** One entry of `persistence.list()`: a snapshot carrying the stored header. */
export interface PersistenceListEntry {
    readonly header?: {
        readonly id?: unknown;
        readonly cwd?: unknown;
    };
}
/** Injected services the deletion flow depends on (structural, no harness import). */
export interface DeleteSessionDeps {
    persistence: {
        /** The JSONL backend exposes its configured root publicly (`config.root`). */
        config?: {
            root?: string;
        };
        list(): Promise<PersistenceListEntry[]>;
    };
    sessions?: {
        get(id: string): unknown;
        flush(session: unknown): Promise<unknown>;
    };
    agents?: {
        /** The live agent (agent-loop: callable cancel + whenIdle), or undefined. */
        get(id: string): unknown;
    };
    workspaceRegistry?: {
        list(): readonly {
            detachSession(id: string): Promise<void>;
        }[];
    };
    /** 测试专用 seam：默认用 node:fs 的 rename。仅用于在隔离 fixture 里构造
     * 「回滚自身失败」这类真实 fs 无法制造的分支；生产路径不传即等价原行为。 */
    readonly trashRename?: (from: string, to: string) => Promise<void>;
}
export type DeleteSessionResult = {
    status: 200;
    ok: true;
    deleted: string;
} | {
    status: 404;
    ok: false;
    error: {
        code: 'session-not-found';
        message: string;
    };
} | {
    status: 409;
    ok: false;
    error: {
        code: 'session-busy';
        message: string;
    };
} | {
    status: 500;
    ok: false;
    deletedLiveSession?: true;
    error: {
        code: 'delete-lookup-failed' | 'delete-failed' | 'cleanup-failed';
        message: string;
        stage?: TrashStage;
        failedTarget?: string | null;
        renamed?: ReadonlyArray<{
            from: string;
            to: string;
        }>;
        recovery?: TrashRecovery;
        residual?: ReadonlyArray<{
            from: string;
            to: string;
        }>;
    };
} | {
    status: 503;
    ok: false;
    error: {
        code: 'persistence-unavailable';
        message: string;
    };
};
/** C10：对外只暴露这一层 —— 任何未预期异常都收敛为结构化 500，绝不裸抛给调用方。 */
export declare function deleteSession(deps: DeleteSessionDeps, sessionId: string): Promise<DeleteSessionResult>;
export {};
//# sourceMappingURL=delete-session.d.ts.map