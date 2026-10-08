/** The current session id: rc.2's `current` field when present, else the a2
 *  main-view-retained session. Undefined when the shape matches neither. */
export declare function currentSessionIdOf(list: unknown): string | undefined;
/** a2 removed `clear()` (selection lifecycle moved to the retain model). */
export declare function sessionsCanClear(sessions: unknown): boolean;
/** a2 removed `open()`; callers must degrade (armNav fallback in
 *  phone-chrome) instead of throwing inside the capture pointerup listener. */
export declare function sessionsCanOpen(sessions: unknown): boolean;
/** How many times an unanswered delete request is re-checked against the list. */
export declare const DELETE_VERIFY_ATTEMPTS = 4;
/** Delay between two re-checks of the session list. */
export declare const DELETE_VERIFY_INTERVAL_MS = 350;
/** What the post-failure verification needs, injected so it stays testable. */
export interface DeleteVerificationDeps {
    /** Whether the id is still present in the current list snapshot. */
    listed: () => boolean;
    /** Re-pull the list from the host; absent on hosts without `refresh`. */
    refresh?: (() => Promise<void>) | undefined;
    /** Wait between attempts (page code passes a timer, tests a no-op). */
    sleep: (ms: number) => Promise<void>;
    /** Attempt budget; defaults to DELETE_VERIFY_ATTEMPTS. */
    attempts?: number;
    /** Delay passed to `sleep`; defaults to DELETE_VERIFY_INTERVAL_MS. */
    intervalMs?: number;
}
/**
 * Decide whether a session delete landed even though no HTTP response arrived.
 *
 * The delete route is the plugin's only REST call, and the host half deployed
 * on DSHA aborts its reply AFTER the handler already moved the session into the
 * trash: the browser rejects the fetch with `TypeError: Failed to fetch`
 * (`net::ERR_EMPTY_RESPONSE`) for a delete that DID happen. Reporting that as a
 * failure is a lie the user has to work around, so the session list — not the
 * fetch promise — is the judge: re-read it a bounded number of times and only
 * conclude failure when the id survives every attempt.
 *
 * @param deps - list accessors plus the injected wait.
 * @returns true as soon as the id is gone, false when it survives the budget.
 */
export declare function verifySessionDeleted(deps: DeleteVerificationDeps): Promise<boolean>;
//# sourceMappingURL=sessions-compat.d.ts.map