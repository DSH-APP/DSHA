/**
 * dsh-web-mobile, host half: default thinking-effort levels for hand-declared
 * models.
 *
 * `llm-pi-ai` describes reasoning per MODEL (`models[].reasoningEfforts`), so a
 * provider route the user declares by hand gets no 「推理等级」 control in the
 * composer until every model entry spells its levels out — which on a phone
 * means finding the profile patch file and writing YAML. This module removes
 * that step: at load, and whenever the settings document changes, it adds the
 * conventional level set to exactly those models of the USER'S OWN layer that
 * declare none.
 *
 * Store contract (DSH 0.2.0-rc.2 `entry-config` settings model, verified
 * against the installed `dsh-settings`):
 *
 *   `settings.describe()` → one descriptor per configurable Loader entry, with
 *   `ns` = the entry id (`llm-pi-ai` for `@deepseek-ai/dsh-llm-pi-ai`),
 *   `value` = the live merged section (every layer), and `user` = the profile
 *   patch layer only. `settings.mutate(ns, ops, expectedRevision)` applies
 *   ordered path ops, refusing paths the schema does not mark volatile.
 *
 * Safety invariants, all load-bearing:
 *
 * 1. Only the user's own layer is written. A model that only a lower layer
 *    supplies is counted, never materialized: `models` is an ARRAY, config
 *    layers replace arrays wholesale, so materializing one entry would drop
 *    that lower layer's `name` / `contextWindow` / `input` / `compat` for it.
 * 2. Nothing that already declares `reasoningEfforts` is touched — neither a
 *    user entry nor its resolved counterpart — so hand-written wire spellings
 *    (e.g. mapping `high` to a gateway's `ultra`) survive.
 * 3. No settings service (older hosts) means no work at all: the installer
 *    waits on the inject scope and silently does nothing when the service
 *    never arrives.
 * 4. `mutate` carries the revision read in the same pass, so a fill that races
 *    a user edit in the Models page fails the write instead of clobbering it.
 *
 * The default set is deliberately the three levels an OpenAI-compatible
 * gateway accepts most often; a route needing different vocabulary declares it
 * itself, which is also the documented escape hatch (README).
 */
/** Loader entry id whose config owns `llm-pi-ai.providers`. */
export const LLM_PI_AI_ENTRY = 'llm-pi-ai';
/**
 * Levels added to a hand-declared model that declares none.
 *
 * The UNION of the two conventions found in the wild — the classic OpenAI trio
 * (`low`/`medium`/`high`) and the DeepSeek/kimi/GLM family (`low`/`high`/`max`)
 * — plus `off`. A single-convention set leaves one family without a level it
 * supports, and this is a factory default for users whose gateways and models
 * nobody here can enumerate; the union is also a strict superset of the three
 * levels shipped before, so no install loses a level. `off` stays `null` (the
 * parameter is omitted), the most compatible way to say "off" on a custom
 * endpoint. `minimal`/`xhigh` are left out on purpose: they are the newest
 * official values and the ones gateways reject most often, and a rejected level
 * costs a failed request when the user picks it.
 * (2026-10-07 ecosystem survey: `docs/handover/2026-10-07-reasoning-defaults-*`.)
 */
export const DEFAULT_REASONING_EFFORTS = Object.freeze({
    off: null,
    low: 'low',
    medium: 'medium',
    high: 'high',
    max: 'max',
});
/** How long the settings write may take before the fill gives up. */
const MUTATE_TIMEOUT_MS = 15_000;
/**
 * Bound the store write.
 *
 * `describe()` is synchronous, but `mutate()` is awaited: a write that never
 * settles would keep the installer's one-fill-at-a-time guard set forever, so
 * every later pass — the 10s sweep included — would return immediately and the
 * feature would be silently dead until the host restarts (2026-10-07 audit).
 * A write that lands after its timeout is harmless: the next pass re-reads the
 * section and finds nothing to do.
 */
function withTimeout(promise, ms) {
    return new Promise((resolvePromise, reject) => {
        const timer = setTimeout(() => { reject(new Error(`settings write did not answer within ${ms}ms`)); }, ms);
        promise.then((value) => { clearTimeout(timer); resolvePromise(value); }, (error) => { clearTimeout(timer); reject(error instanceof Error ? error : new Error(String(error))); });
    });
}
/** Narrow one unknown value to a plain object (arrays excluded). */
function isRecord(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
/** Whether a model entry already carries a level set (possibly `false`). */
function hasLevels(entry) {
    return isRecord(entry) && entry.reasoningEfforts !== undefined;
}
/**
 * Plan the default-level fill for one `llm-pi-ai` section.
 *
 * `resolved` decides WHERE a level set is missing; `user` supplies WHAT may be
 * written. Neither argument is mutated.
 *
 * @param resolved - the live merged section (`descriptor.value`).
 * @param user - the profile patch layer (`descriptor.user`).
 * @returns the ordered path ops, the number of models the fill covers, and the
 *   number left unresolved below the user's layer.
 */
export function planReasoningEffortFill(resolved, user) {
    const ops = [];
    let filled = 0;
    let unresolved = 0;
    if (!isRecord(user) || !isRecord(user.providers))
        return { ops, filled, unresolved };
    const resolvedProviders = isRecord(resolved) && isRecord(resolved.providers) ? resolved.providers : {};
    for (const [route, userProfile] of Object.entries(user.providers)) {
        if (!isRecord(userProfile))
            continue;
        const resolvedProfile = isRecord(resolvedProviders[route]) ? resolvedProviders[route] : undefined;
        if (Array.isArray(userProfile.models)) {
            const userModels = userProfile.models;
            const resolvedModels = resolvedProfile !== undefined && Array.isArray(resolvedProfile.models) ? resolvedProfile.models : [];
            let changed = 0;
            const models = [];
            for (const [index, entry] of userModels.entries()) {
                const resolvedEntry = resolvedModels[index];
                if (isRecord(entry) && entry.reasoningEfforts === undefined && !hasLevels(resolvedEntry)) {
                    models.push({ ...entry, reasoningEfforts: { ...DEFAULT_REASONING_EFFORTS } });
                    changed += 1;
                    continue;
                }
                models.push(entry);
                if (!isRecord(entry) && !hasLevels(resolvedEntry))
                    unresolved += 1;
            }
            // Resolved entries past the user's own list have no user-layer fields to
            // contribute — counted so the log can name them, never materialized.
            for (const resolvedEntry of resolvedModels.slice(userModels.length)) {
                if (!hasLevels(resolvedEntry))
                    unresolved += 1;
            }
            if (changed > 0) {
                ops.push({ op: 'set', path: ['providers', route, 'models'], value: models });
                filled += changed;
            }
            continue;
        }
        if (resolvedProfile !== undefined && Array.isArray(resolvedProfile.models)) {
            for (const resolvedEntry of resolvedProfile.models)
                if (!hasLevels(resolvedEntry))
                    unresolved += 1;
        }
    }
    // `modelOverrides` are addressed one path at a time, so an override the user
    // wrote can take levels without restating anything around it.
    for (const [route, userProfile] of Object.entries(user.providers)) {
        if (!isRecord(userProfile) || !isRecord(userProfile.modelOverrides))
            continue;
        const resolvedProfile = isRecord(resolvedProviders[route]) ? resolvedProviders[route] : undefined;
        const resolvedOverrides = resolvedProfile !== undefined && isRecord(resolvedProfile.modelOverrides)
            ? resolvedProfile.modelOverrides
            : undefined;
        for (const [id, entry] of Object.entries(userProfile.modelOverrides)) {
            if (!isRecord(entry) || entry.reasoningEfforts !== undefined)
                continue;
            if (hasLevels(resolvedOverrides?.[id]))
                continue;
            ops.push({
                op: 'set',
                path: ['providers', route, 'modelOverrides', id, 'reasoningEfforts'],
                value: { ...DEFAULT_REASONING_EFFORTS },
            });
            filled += 1;
        }
    }
    return { ops, filled, unresolved };
}
/** Read the descriptor for the `llm-pi-ai` entry, or undefined. */
function sectionDescriptor(settings) {
    if (typeof settings.describe !== 'function')
        return undefined;
    const described = settings.describe();
    if (!Array.isArray(described))
        return undefined;
    return described.find((entry) => isRecord(entry) && entry.ns === LLM_PI_AI_ENTRY);
}
/**
 * Add the default level set to every reachable hand-declared model that lacks
 * one, in one `mutate` batch. Never throws: a host without the settings face,
 * a refused write, and a concurrent edit all report through the returned
 * status and `reason` (the caller decides whether to log, and a refusal is
 * worth retrying — see the installer).
 *
 * @param settings - the host settings service face.
 * @returns what happened, including the counts and reason worth logging.
 */
export async function fillReasoningEffortDefaults(settings) {
    if (settings === undefined || typeof settings.mutate !== 'function') {
        return { status: 'unavailable', filled: 0, unresolved: 0, reason: 'settings service face missing' };
    }
    try {
        const descriptor = sectionDescriptor(settings);
        if (descriptor === undefined) {
            return { status: 'unavailable', filled: 0, unresolved: 0, reason: `${LLM_PI_AI_ENTRY} is not describable yet` };
        }
        const plan = planReasoningEffortFill(descriptor.value, descriptor.user);
        if (plan.ops.length === 0) {
            return { status: 'nothing-to-do', filled: 0, unresolved: plan.unresolved, reason: '' };
        }
        const revision = typeof descriptor.revision === 'number' ? descriptor.revision : undefined;
        await withTimeout(Promise.resolve(settings.mutate(LLM_PI_AI_ENTRY, plan.ops, revision)), MUTATE_TIMEOUT_MS);
        return { status: 'filled', filled: plan.filled, unresolved: plan.unresolved, reason: '' };
    }
    catch (error) {
        return {
            status: 'failed',
            filled: 0,
            unresolved: 0,
            reason: error instanceof Error ? error.message : String(error),
        };
    }
}
//# sourceMappingURL=reasoning-effort.js.map