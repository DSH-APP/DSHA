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
export declare const LLM_PI_AI_ENTRY = "llm-pi-ai";
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
export declare const DEFAULT_REASONING_EFFORTS: Readonly<Record<string, string | null>>;
/** One settings path edit, as `settings.mutate` takes them. */
export interface SettingsPathOp {
    readonly op: 'set';
    readonly path: ReadonlyArray<string | number>;
    readonly value: unknown;
}
/** What one planning pass would write, plus the entries it cannot reach. */
export interface ReasoningFillPlan {
    /** Ordered path ops; empty when every reachable model already declares levels. */
    readonly ops: SettingsPathOp[];
    /** Model entries that would receive the default level set. */
    readonly filled: number;
    /** Entries that still lack levels but live below the user's layer. */
    readonly unresolved: number;
}
/**
 * Structural slice of the host settings service. Both members are optional:
 * an older host exposes a different shape, and a missing member must degrade
 * to "no fill", never to a throw.
 */
export interface SettingsFace {
    describe?: () => unknown;
    mutate?: (ns: string, ops: SettingsPathOp[], expectedRevision?: number) => unknown;
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
export declare function planReasoningEffortFill(resolved: unknown, user: unknown): ReasoningFillPlan;
/** Outcome of one fill attempt; `status` is what the caller logs. */
export interface ReasoningFillOutcome {
    readonly status: 'filled' | 'nothing-to-do' | 'unavailable' | 'failed';
    readonly filled: number;
    readonly unresolved: number;
    /** Why an attempt could not land (empty when it did) — logged, never thrown. */
    readonly reason: string;
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
export declare function fillReasoningEffortDefaults(settings: SettingsFace | undefined): Promise<ReasoningFillOutcome>;
//# sourceMappingURL=reasoning-effort.d.ts.map