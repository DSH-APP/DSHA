/**
 * dsh-web-mobile, host half: session deletion.
 *
 * Port of community-fork wzxmt-zhc v2.7.0 src/delete-session.ts. Host faces
 * it relies on (supported hosts >=0.2.2-rc.1, re-verified 2026-11-05 against
 * the installed 0.2.2-rc.1 packages):
 *
 * 1. `persistence.list()` returns snapshot entries that carry `.header`
 *    (dsh-session-persistence-jsonl `list()`); the flat `SessionHeader[]`
 *    shape of 0.1.1/0.1.2 hosts is no longer accepted.
 * 2. Live-session teardown uses the agent's disposal face (callable `cancel`
 *    + `whenIdle`, the agent-loop agent that `AgentRegistry.get()` returns).
 *    An agent without that face is still refused with 409 session-busy rather
 *    than deleted under a still-registered live agent (defensive: a provider
 *    other than agent-loop may register agents of another shape).
 * 3. Workspace accounting calls `detachSession` on every registry workspace
 *    (dsh-workspace `WorkspaceEntity.detachSession`); the 0.1.1-rc.2
 *    "no detachSession" skip was removed with the host floor.
 *
 * The JSONL backend stores one directory per session under its public
 * `config.root`:
 *
 *   <root>/<projectKey(cwd)>/<encodeSegment(id)>/
 *
 * with immutable generation log files inside. This module recomputes that
 * directory (mirroring the backend's projectKey / encodeSegment layout from
 * @deepseek-ai/dsh-session-persistence-jsonl) and moves it into the trash
 * (`<root>/.sessions-trash/<UTC>-<projectKey>-<encodedId>/`, payloads renamed
 * with a `.trash` suffix plus a best-effort manifest.json) so a deletion can
 * be restored; entries older than 24h are purged best-effort.
 *
 * USED (live) sessions stay deletable on hosts that expose the disposal
 * face: the host stops the session's agent (runtime
 * `cancel({kind:'disposed'})` + `whenIdle()`, mirroring the agent-loop
 * disposal sequence), flushes the durable checkpoint (`SessionStore.flush`),
 * and unregisters the live entries via the runtime-visible store internals
 * (there is no public teardown API in either generation). All internal
 * probes are optional-chained so a harness shape change degrades to a clear
 * error instead of a crash.
 *
 * Attachment bytes are content-addressed in a shared backend and are NOT
 * removed; they only become unreachable garbage once no log references them.
 */
import { rm, mkdir, readdir, rename, stat, writeFile } from 'node:fs/promises';
import { join, relative, resolve, sep } from 'node:path';
/** How long to wait for a live agent to converge to idle before refusing. */
const IDLE_TIMEOUT_MS = 20_000;
/** Trash entries older than this are purged (best effort) after a successful move. */
const TRASH_TTL_MS = 24 * 60 * 60 * 1000;
/** Canonical payload names the host list() scan recognizes
 * (session[.vN].jsonl[.zstd] — mirror of the backend's CANONICAL_LOG_FILENAME). */
const PAYLOAD_NAME = /^session(?:\.v[1-9][0-9]*)?\.jsonl(?:\.zstd)?$/;
/** 精确列出「已改名」映射，供对外文案与恢复指引使用（空则 none）。 */
function describeRenamed(list) {
    return list.length === 0 ? 'none' : list.map((entry) => `${entry.from} -> ${entry.to}`).join(', ');
}
/** `rename-payloads` 失败：**先逆序回滚**已改名的 payload，再据实标注恢复状态；
 * `stash` 失败：payload 已改名（会话已从宿主列表隐藏），目录仍在原地。
 * 两种失败都携带精确已改名清单、失败目标与恢复状态/残留映射。 */
class TrashMoveError extends Error {
    stage;
    renamed;
    failedTarget;
    recovery;
    residual;
    constructor(stage, cause, detail = {}) {
        super(errorMessage(cause));
        this.stage = stage;
        this.renamed = detail.renamed ?? [];
        this.failedTarget = detail.failedTarget ?? null;
        this.recovery = detail.recovery ?? 'not-needed';
        this.residual = detail.residual ?? [];
    }
}
/** Escape one raw session id into one filesystem-safe path segment (backend layout). */
function encodeSegment(raw) {
    if (raw.length === 0)
        throw new Error('cannot encode an empty path segment');
    if (raw === '.')
        return '~002E';
    if (raw === '..')
        return '~002E~002E';
    let out = '';
    for (let i = 0; i < raw.length; i++) {
        const code = raw.charCodeAt(i);
        const ch = String.fromCharCode(code);
        if (ch !== '~' && /^[A-Za-z0-9._-]$/.test(ch)) {
            out += ch;
        }
        else {
            out += '~' + code.toString(16).toUpperCase().padStart(4, '0');
        }
    }
    return out;
}
/** Build the readable directory key for a project path (backend layout). */
function projectKey(cwd) {
    if (cwd.length === 0)
        throw new Error('cannot encode an empty project path');
    let readable = '';
    let separatorRun = false;
    for (let i = 0; i < cwd.length; i++) {
        const code = cwd.charCodeAt(i);
        const ch = String.fromCharCode(code);
        if (ch === '/' || ch === '\\' || ch === ':') {
            if (!separatorRun)
                readable += '-';
            separatorRun = true;
        }
        else if (ch !== '~' && /^[A-Za-z0-9._-]$/.test(ch)) {
            readable += ch;
            separatorRun = false;
        }
        else {
            readable += '~' + code.toString(16).toUpperCase().padStart(4, '0');
            separatorRun = false;
        }
    }
    const slug = readable.replace(/^-+/, '') || 'root';
    return `--${slug.slice(0, 251)}--`;
}
/** The directory owned by one session under the backend root. */
function sessionDir(root, cwd, id) {
    const project = cwd === undefined ? '_no-cwd' : projectKey(cwd);
    return join(root, project, encodeSegment(id));
}
/** Whether `target` resolves to a path inside `root` (defense against escapes). */
function isInside(root, target) {
    const rel = relative(root, target);
    return rel !== '..' && !rel.startsWith('..' + sep) && rel !== '';
}
/** Bound a promise with a rejection deadline so a stuck agent never hangs the endpoint. */
function withTimeout(promise, ms, message) {
    return new Promise((resolvePromise, reject) => {
        const timer = setTimeout(() => reject(new Error(message)), ms);
        promise.then((value) => { clearTimeout(timer); resolvePromise(value); }, (error) => { clearTimeout(timer); reject(error); });
    });
}
/** Unregister a live agent via runtime-visible store internals, if present. */
function detachLiveAgent(agents, id) {
    const registry = agents;
    const entry = registry?.store?.get(id);
    if (entry !== undefined)
        registry?.detachEntered?.(entry);
}
/** Unregister a live session via runtime-visible store internals, if present. */
function detachLiveSession(sessions, id) {
    const store = sessions;
    store?.store?.get(id)?.detach?.();
}
/** Remove the session from every workspace account (idempotent; unknown ids
 * resolve without writing). */
async function detachFromWorkspaces(deps, sessionId) {
    if (deps.workspaceRegistry === undefined)
        return;
    for (const workspace of deps.workspaceRegistry.list()) {
        await workspace.detachSession(sessionId);
    }
}
function errorMessage(error) {
    return error instanceof Error ? error.message : String(error);
}
/**
 * Move one session's stored directory into the trash (`.sessions-trash/`
 * under the persistence root) instead of removing it, replacing the plain
 * recursive rm.
 *
 * Order is load-bearing: payloads are renamed with a `.trash` suffix FIRST —
 * non-canonical names are invisible to the host's list() scan — so a
 * canonical directory with canonical payload names never appears under the
 * trash root (that would make assertStoredIdentity throw a plain Error which
 * listArtifacts does not filter, failing the ENTIRE session list). The
 * directory rename then removes the canonical directory within milliseconds.
 * `manifest.json` (a `.json` name is safe) records the restore mapping, best
 * effort: the from→to rule is deterministic even without it (drop the
 * `.trash` suffix). Stale entries older than TRASH_TTL_MS are purged after
 * each successful move; every purge failure is swallowed (pure core, no
 * logger) and no long-lived timer is ever created.
 */
async function moveToTrash(root, dir, cwd, sessionId, trashRename = rename) {
    const project = cwd === undefined ? '_no-cwd' : projectKey(cwd);
    // 1. Rename canonical payloads in place: the host scan only recognizes
    //    canonical names, so this alone hides the session from list().
    let entries;
    try {
        entries = await readdir(dir, { withFileTypes: true });
    }
    catch (error) {
        throw new TrashMoveError('rename-payloads', error);
    }
    const renamed = [];
    for (const entry of entries) {
        if (!entry.isFile() || !PAYLOAD_NAME.test(entry.name))
            continue;
        const to = `${entry.name}.trash`;
        try {
            await trashRename(join(dir, entry.name), join(dir, to));
        }
        catch (error) {
            // C10：失败即按**逆序**回滚此前已改名的 payload（最晚改名的先还原），
            // 然后据实报告：全部还原 ⇒ rolled-back（此时"untouched"才是真话）；
            // 有还原失败的 ⇒ partial + 精确残留映射（绝不再对外说 untouched）。
            const residual = [];
            for (const done of [...renamed].reverse()) {
                try {
                    await trashRename(join(dir, done.to), join(dir, done.from));
                }
                catch {
                    residual.unshift(done);
                }
            }
            throw new TrashMoveError('rename-payloads', error, {
                renamed: [...renamed],
                failedTarget: entry.name,
                recovery: residual.length === 0 ? 'rolled-back' : 'partial',
                residual,
            });
        }
        renamed.push({ from: entry.name, to });
    }
    // 2. Move the whole directory into the trash root.
    const trashRoot = join(root, '.sessions-trash');
    const trashName = `${new Date().toISOString().replaceAll(':', '-')}-${project}-${encodeSegment(sessionId)}`;
    const trashDir = join(trashRoot, trashName);
    try {
        await mkdir(trashRoot, { recursive: true });
        await rename(dir, trashDir);
    }
    catch (error) {
        // C10：stash 失败时不回滚（既定判据：会话已隐藏），但必须带上**精确已改名清单**
        // 与恢复状态，供对外文案给出可执行的恢复指引。
        throw new TrashMoveError('stash', error, {
            renamed: [...renamed],
            failedTarget: dir,
            recovery: renamed.length === 0 ? 'not-needed' : 'partial',
        });
    }
    // 3. Best-effort manifest: the entry is already invisible to the host, so a
    //    failed manifest only degrades the restore info. Never fails the move.
    try {
        const manifest = { id: sessionId, cwd, deletedAt: new Date().toISOString(), files: renamed };
        await writeFile(join(trashDir, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);
    }
    catch {
        // restore info degrades to the deterministic from→to rule
    }
    // 4. Best-effort purge of stale trash entries (synchronous, no timers).
    await purgeStaleTrash(trashRoot);
}
/** Remove trash entries older than TRASH_TTL_MS; every failure is swallowed. */
async function purgeStaleTrash(trashRoot) {
    try {
        const cutoff = Date.now() - TRASH_TTL_MS;
        for (const name of await readdir(trashRoot)) {
            try {
                const stats = await stat(join(trashRoot, name));
                if (stats.mtimeMs < cutoff)
                    await rm(join(trashRoot, name), { recursive: true, force: true });
            }
            catch {
                // skip an entry that vanished or cannot be stat-ed
            }
        }
    }
    catch {
        // trash root unreadable: purge is best effort, the deletion already succeeded
    }
}
/** Read one `persistence.list()` snapshot's header. Entries without a usable
 * id are skipped. */
function entryHeader(entry) {
    const header = entry.header;
    if (header === undefined || typeof header.id !== 'string' || header.id === '')
        return undefined;
    return {
        id: header.id,
        cwd: typeof header.cwd === 'string' ? header.cwd : undefined,
    };
}
/**
 * Delete one session: stop it if live, remove its persisted directory, and
 * detach it from every workspace account.
 * @param deps - Injected services; the deletion flow never imports the harness.
 * @param sessionId - The session to delete.
 * @returns A structured result the caller maps to an HTTP response.
 */
async function deleteSessionInner(deps, sessionId) {
    const root = deps.persistence.config?.root;
    if (root === undefined || root === '') {
        return {
            status: 503,
            ok: false,
            error: {
                code: 'persistence-unavailable',
                message: 'session persistence is not configured with a storage root',
            },
        };
    }
    let snapshot;
    try {
        snapshot = (await deps.persistence.list())
            .map(entryHeader)
            .find(header => header !== undefined && header.id === sessionId);
    }
    catch (error) {
        return {
            status: 500,
            ok: false,
            error: {
                code: 'delete-lookup-failed',
                message: `failed to look up the session: ${errorMessage(error)}`,
            },
        };
    }
    if (snapshot === undefined) {
        return {
            status: 404,
            ok: false,
            error: { code: 'session-not-found', message: `no such session '${sessionId}'` },
        };
    }
    // C10（fail closed）：目标目录解析/合法性校验必须在**任何破坏性操作之前**。
    // cwd 非法（如空字符串）时直接返回结构化 500，**不停会话、不注销**，状态与回执一致。
    try {
        // cwd 缺失（undefined）是合法的宿主形态（解析为 _no-cwd）；只有**非法值**（如空字符串）
        // 才 fail closed。
        if (snapshot.cwd !== undefined)
            projectKey(snapshot.cwd);
    }
    catch (error) {
        return {
            status: 500,
            ok: false,
            error: {
                code: 'delete-failed',
                message: `cannot resolve the session log directory: ${errorMessage(error)} (the session was not stopped or unregistered)`,
            },
        };
    }
    // Live sessions: only delete when the live agent exposes the disposal face
    // (callable cancel + whenIdle, as the agent-loop agent does). An agent of
    // another shape is refused with 409 instead of deleting under a
    // still-registered live agent (otherwise the session would keep running on
    // a removed log and stay listed in `session.list`).
    const live = deps.sessions?.get(sessionId);
    if (live !== undefined && deps.sessions !== undefined) {
        const agent = deps.agents?.get(sessionId);
        const handle = agent !== undefined
            && typeof agent.cancel === 'function'
            && typeof agent.whenIdle === 'function'
            ? agent
            : undefined;
        if (agent !== undefined && handle === undefined) {
            return {
                status: 409,
                ok: false,
                error: {
                    code: 'session-busy',
                    message: `session '${sessionId}' is live under an agent that exposes no disposal face; stop it first, then retry`,
                },
            };
        }
        try {
            if (handle !== undefined) {
                handle.cancel({ kind: 'disposed' });
                await withTimeout(handle.whenIdle(), IDLE_TIMEOUT_MS, `agent for session '${sessionId}' did not converge to idle within ${IDLE_TIMEOUT_MS}ms`);
            }
            await deps.sessions.flush(live);
            detachLiveAgent(deps.agents, sessionId);
            detachLiveSession(deps.sessions, sessionId);
        }
        catch (error) {
            return {
                status: 409,
                ok: false,
                error: {
                    code: 'session-busy',
                    message: `cannot delete session '${sessionId}': it is running and could not be stopped: ` +
                        `${errorMessage(error)}`,
                },
            };
        }
    }
    const resolvedRoot = resolve(root);
    const dir = sessionDir(resolvedRoot, snapshot.cwd, sessionId);
    if (!isInside(resolvedRoot, dir)) {
        return {
            status: 500,
            ok: false,
            error: {
                code: 'delete-failed',
                message: `refusing to remove '${dir}': it resolves outside the session storage root`,
            },
        };
    }
    try {
        await moveToTrash(resolvedRoot, dir, snapshot.cwd, sessionId, deps.trashRename);
    }
    catch (error) {
        // The trash move failed; report the observable state per stage. A live
        // session was already stopped, flushed and unregistered above, so settle
        // the workspace accounting now (best effort — the cleanup-failed response
        // must not be overridden) and report the distinct outcome instead of
        // implying the session is still intact.
        const stage = error instanceof TrashMoveError ? error.stage : 'stash';
        const trash = error instanceof TrashMoveError
            ? error
            : new TrashMoveError(stage, error);
        if (live !== undefined) {
            try {
                await detachFromWorkspaces(deps, sessionId);
            }
            catch {
                // The stale workspace id stays until the host reconciles it.
            }
            return {
                status: 500,
                ok: false,
                deletedLiveSession: true,
                error: {
                    code: 'cleanup-failed',
                    stage: trash.stage,
                    failedTarget: trash.failedTarget,
                    renamed: trash.renamed,
                    recovery: trash.recovery,
                    residual: trash.residual,
                    message: stage === 'rename-payloads'
                        ? (trash.recovery !== 'partial'
                            ? `session '${sessionId}' was stopped and unregistered, but its log directory could not be moved to the trash and remains untouched in place: ${errorMessage(error)} (stage=rename-payloads target=${trash.failedTarget ?? 'unknown'}; renamed=${describeRenamed(trash.renamed)}${trash.recovery === 'rolled-back' ? ' (all previously renamed payloads were rolled back, so the directory is exactly as before)' : ''}); the session will not resume — retry the delete to clean up the leftover files`
                            : `session '${sessionId}' was stopped and unregistered, and its log directory is now PARTIALLY renamed: ${errorMessage(error)} (stage=rename-payloads target=${trash.failedTarget ?? 'unknown'}; renamed=${describeRenamed(trash.renamed)}; residual still staged: ${describeRenamed(trash.residual)}) — the session is hidden from the list; restore the residual names (strip the suffix ".trash") and delete again, or remove the directory manually`)
                        : `session '${sessionId}' was stopped and unregistered, but its log directory could only be partially stashed (directory still in place): ${errorMessage(error)} (stage=stash target=${trash.failedTarget ?? 'unknown'}; renamed=${describeRenamed(trash.renamed)}) — the session will not resume; it is hidden from the list: restore the original file names (strip the suffix ".trash") and delete again to finish the move, or remove the directory manually`,
                },
            };
        }
        return {
            status: 500,
            ok: false,
            error: {
                code: 'delete-failed',
                stage: trash.stage,
                failedTarget: trash.failedTarget,
                renamed: trash.renamed,
                recovery: trash.recovery,
                residual: trash.residual,
                message: stage === 'rename-payloads'
                    ? (trash.recovery !== 'partial'
                        ? `failed to move the session log to the trash: ${errorMessage(error)} (the directory is untouched; stage=rename-payloads target=${trash.failedTarget ?? 'unknown'}; renamed=${describeRenamed(trash.renamed)}${trash.recovery === 'rolled-back' ? ' — all previously renamed payloads were rolled back' : ''})`
                        : `failed to move the session log to the trash, and the directory is now PARTIALLY renamed: ${errorMessage(error)} (stage=rename-payloads target=${trash.failedTarget ?? 'unknown'}; renamed=${describeRenamed(trash.renamed)}; residual still staged: ${describeRenamed(trash.residual)})`)
                    : `failed to stash the session log directory: ${errorMessage(error)} (stage=stash target=${trash.failedTarget ?? 'unknown'}; renamed=${describeRenamed(trash.renamed)} — the session is hidden from the list: restore those names (strip the suffix ".trash") to unhide it)`,
            },
        };
    }
    // Workspace accounting: remove the deleted session from every workspace
    // account (idempotent; unknown ids resolve without writing). The session
    // directory is already in the trash, so
    // a failing store must never fail an already-finished deletion — the caller
    // reports success and the host reconciles the stale id (same isolation as
    // the cleanup-failed branch above).
    try {
        await detachFromWorkspaces(deps, sessionId);
    }
    catch {
        // Best effort: the deletion itself already succeeded.
    }
    return { status: 200, ok: true, deleted: sessionId };
}
/** C10：对外只暴露这一层 —— 任何未预期异常都收敛为结构化 500，绝不裸抛给调用方。 */
export async function deleteSession(deps, sessionId) {
    try {
        return await deleteSessionInner(deps, sessionId);
    }
    catch (error) {
        return {
            status: 500,
            ok: false,
            error: { code: 'delete-failed', message: `unexpected failure while deleting session '${sessionId}': ${errorMessage(error)}` },
        };
    }
}
//# sourceMappingURL=delete-session.js.map