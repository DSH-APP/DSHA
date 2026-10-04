/**
 * DSHA session deletion for the locked rc2 public lifecycle.
 * A live Agent is deleted only through the exact AgentHandle returned by
 * agents.create/resume. No private registry maps are read or modified.
 * Original log directories remain in .sessions-trash indefinitely.
 */
import { mkdir, readdir, rename, lstat, writeFile } from 'node:fs/promises';
import { isAbsolute, join, relative, resolve, sep } from 'node:path';
import { randomUUID } from 'node:crypto';

const IDLE_TIMEOUT_MS = 20_000;
const PAYLOAD_NAME = /^session(?:\.v[1-9][0-9]*)?\.jsonl(?:\.zstd)?$/;
const ownedHandles = new WeakMap();
const deleting = new Set();
const creating = new Map();

/** Capture capabilities at the public factory boundary, including traced
 * Cordis callers. Returning the original handle preserves owner teardown. */
export function installSessionDeletionLifecycle(agents) {
    if (agents === undefined) return () => {};
    const proto = Object.getPrototypeOf(agents);
    const originals = new Map();
    for (const method of ['create', 'resume']) {
        const original = proto[method];
        if (typeof original !== 'function') throw new Error(`missing public agents.${method} API`);
        const patched = async function (...args) {
            const id = method === 'create' ? args[0]?.sessionId : args[0]?.resumeSessionId;
            if (deleting.has(id)) throw new Error(`session '${id}' is being deleted`);
            creating.set(id, (creating.get(id) ?? 0) + 1);
            try {
                const handle = await Reflect.apply(original, this, args);
                if (handle?.agent === undefined || typeof handle.dispose !== 'function')
                    throw new Error(`agents.${method} returned no public disposal capability`);
                ownedHandles.set(handle.agent, handle);
                return handle;
            } finally {
                const count = creating.get(id) - 1;
                if (count) creating.set(id, count); else creating.delete(id);
            }
        };
        proto[method] = patched;
        originals.set(method, { original, patched });
    }
    return () => {
        for (const [method, { original, patched }] of originals)
            if (proto[method] === patched) proto[method] = original;
    };
}
function encodeSegment(raw) {
    if (!raw) throw new Error('cannot encode an empty path segment');
    if (raw === '.') return '~002E';
    if (raw === '..') return '~002E~002E';
    let out = '';
    for (let i = 0; i < raw.length; i++) {
        const ch = raw[i];
        out += ch !== '~' && /^[A-Za-z0-9._-]$/.test(ch)
            ? ch : '~' + raw.charCodeAt(i).toString(16).toUpperCase().padStart(4, '0');
    }
    return out;
}
function projectKey(cwd) {
    if (!cwd) throw new Error('cannot encode an empty project path');
    let readable = '', separatorRun = false;
    for (let i = 0; i < cwd.length; i++) {
        const ch = cwd[i];
        if (ch === '/' || ch === '\\' || ch === ':') {
            if (!separatorRun) readable += '-';
            separatorRun = true;
        } else {
            readable += ch !== '~' && /^[A-Za-z0-9._-]$/.test(ch)
                ? ch : '~' + cwd.charCodeAt(i).toString(16).toUpperCase().padStart(4, '0');
            separatorRun = false;
        }
    }
    return `--${(readable.replace(/^-+/, '') || 'root').slice(0, 251)}--`;
}
function inside(root, target) {
    const rel = relative(root, target);
    return rel !== '' && rel !== '..' && !rel.startsWith('..' + sep) && !isAbsolute(rel);
}
function errorMessage(error) { return error instanceof Error ? error.message : String(error); }
function failure(status, code, message) { return { status, ok: false, error: { code, message } }; }
function withTimeout(promise, message) {
    return new Promise((resolvePromise, reject) => {
        const timer = setTimeout(() => reject(new Error(message)), IDLE_TIMEOUT_MS);
        Promise.resolve(promise).then(value => { clearTimeout(timer); resolvePromise(value); }, error => { clearTimeout(timer); reject(error); });
    });
}
async function plainDirectory(dir) {
    const entry = await lstat(dir);
    if (!entry.isDirectory() || entry.isSymbolicLink()) throw new Error(`not an owned session directory: '${dir}'`);
}
/** Rename canonical logs before moving: the backend scans descendants and
 * would otherwise mistake trash logs for a second canonical session. On a
 * failed move, restore every renamed log; report any rollback failure. */
async function moveToTrash(root, dir, cwd, sessionId) {
    await plainDirectory(join(root, cwd === undefined ? '_no-cwd' : projectKey(cwd)));
    await plainDirectory(dir);
    const trashRoot = join(root, '.sessions-trash');
    await mkdir(trashRoot, { recursive: true });
    await plainDirectory(trashRoot);
    const trashDir = join(trashRoot, `${new Date().toISOString().replaceAll(':', '-')}-${randomUUID()}`);
    const renamed = [];
    try {
        for (const entry of await readdir(dir, { withFileTypes: true })) {
            if (!entry.isFile() || !PAYLOAD_NAME.test(entry.name)) continue;
            const to = `${entry.name}.trash`;
            // rename() may replace an existing file on POSIX; preserve unknown originals.
            try { await lstat(join(dir, to)); throw new Error(`trash payload already exists: '${to}'`); }
            catch (error) { if (error.code !== 'ENOENT') throw error; }
            await rename(join(dir, entry.name), join(dir, to));
            renamed.push({ from: entry.name, to });
        }
        if (!renamed.length) throw new Error('session directory contains no canonical log payload');
        await rename(dir, trashDir);
    } catch (error) {
        const rollbackFailures = [];
        for (const row of renamed.reverse()) {
            try { await rename(join(dir, row.to), join(dir, row.from)); }
            catch (rollbackError) { rollbackFailures.push(errorMessage(rollbackError)); }
        }
        throw new Error(`${errorMessage(error)}${rollbackFailures.length ? '; payload rollback incomplete: ' + rollbackFailures.join('; ') : '; original payload names retained'}`);
    }
    let warning;
    try {
        await writeFile(join(trashDir, 'manifest.json'), JSON.stringify({ id: sessionId, cwd, deletedAt: new Date().toISOString(), files: renamed }, null, 2) + '\n', { flag: 'wx' });
    } catch (error) {
        warning = `original logs retained, but trash manifest could not be written: ${errorMessage(error)}`;
    }
    // No TTL, recursive rm, or implicit cleanup of retained originals.
    return { trashDir, warning };
}

export async function deleteSession(deps, sessionId) {
    if (typeof sessionId !== 'string' || sessionId === '')
        return failure(400, 'invalid-session-id', 'sessionId must be a non-empty string');
    const root = deps.persistence?.config?.root;
    if (typeof root !== 'string' || root === '')
        return failure(503, 'persistence-unavailable', 'session persistence has no storage root');
    if (deleting.has(sessionId) || creating.has(sessionId))
        return failure(409, 'session-busy', `session '${sessionId}' has another lifecycle operation in progress`);
    deleting.add(sessionId);
    try {
        let snapshot, workspaces;
        try {
            snapshot = (await deps.persistence.list()).map(entry => entry.header ?? entry).find(header => header.id === sessionId);
            workspaces = deps.workspaceRegistry === undefined ? [] : deps.workspaceRegistry.list();
            if (!Array.isArray(workspaces) || workspaces.some(row => typeof row.detachSession !== 'function'))
                throw new Error('workspace public detachSession API is unavailable');
        } catch (error) {
            return failure(500, 'delete-lookup-failed', `cannot inspect session deletion: ${errorMessage(error)}`);
        }
        if (snapshot === undefined) return failure(404, 'session-not-found', `no such session '${sessionId}'`);
        const live = deps.sessions?.get(sessionId);
        const agent = deps.agents?.get(sessionId);
        if (live !== undefined || agent !== undefined) {
            const handle = agent === undefined ? undefined : ownedHandles.get(agent);
            if (handle === undefined || agent.session !== live || typeof agent.cancel !== 'function'
                || typeof agent.whenIdle !== 'function' || typeof deps.sessions?.flush !== 'function')
                return failure(409, 'session-busy', `session '${sessionId}' has no captured public lifecycle capability; stop it through its owner before deleting`);
            try {
                agent.cancel({ kind: 'disposed' });
                await withTimeout(agent.whenIdle(), `agent '${sessionId}' did not reach idle`);
                if (await deps.sessions.flush(live) !== true) throw new Error('no durability listener confirmed the session flush');
                await withTimeout(handle.dispose(), `agent '${sessionId}' disposal did not complete`);
                if (deps.agents.get(sessionId) !== undefined || deps.sessions.get(sessionId) !== undefined)
                    throw new Error('public lifecycle disposal left a live agent or session');
                ownedHandles.delete(agent);
            } catch (error) {
                return failure(409, 'session-busy', `cannot safely close session '${sessionId}': ${errorMessage(error)}; original logs retained`);
            }
        }
        const resolvedRoot = resolve(root);
        const dir = join(resolvedRoot, snapshot.cwd === undefined ? '_no-cwd' : projectKey(snapshot.cwd), encodeSegment(sessionId));
        if (!inside(resolvedRoot, dir)) return failure(500, 'delete-failed', 'session directory escapes its storage root');
        let retained;
        try { retained = await moveToTrash(resolvedRoot, dir, snapshot.cwd, sessionId); }
        catch (error) { return failure(500, 'delete-failed', `session logs could not be retained in trash: ${errorMessage(error)}`); }
        const warnings = retained.warning === undefined ? [] : [retained.warning];
        for (const workspace of workspaces) {
            try { await workspace.detachSession(sessionId); }
            catch (error) { warnings.push(`workspace '${workspace.id}' still references the removed session: ${errorMessage(error)}`); }
        }
        return { status: 200, ok: true, deleted: sessionId, ...(warnings.length ? { warnings } : {}) };
    } finally { deleting.delete(sessionId); }
}
