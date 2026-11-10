// Sessions service shape on supported hosts (>=0.2.2-rc.1): there is no
// `ISessions.open`/`clear` and no `SessionListState.current`; selection lives in
// per-session `retainedBy` counters (dsh-api-session-controller
// `projectList()`; dsh-client-ui-layout reads it as
// `Object.values(byId).find(s => (s.retainedBy.mainView ?? 0) > 0)?.id`).
// The repo type-checks against pinned 0.1.0-rc.6 typings that still describe
// the old shape, so the read stays structural instead of importing types.

/** Structural view of the list snapshot — never import service types here. */
interface SessionListLike {
  byId?: Record<string, { id?: unknown; retainedBy?: { mainView?: unknown } }>
}

/** The current session id: the main-view-retained session. Undefined when no
 *  session is retained by the main view or the shape is foreign. The rc.2
 *  `current` field read was removed with the host floor (0.2.2 never sets it). */
export function currentSessionIdOf(list: unknown): string | undefined {
  if (typeof list !== 'object' || list === null) return undefined
  const snapshot = list as SessionListLike
  for (const key in snapshot.byId) {
    const summary = snapshot.byId[key]
    // for-in guarantees the key exists, not the value — an explicitly
    // undefined property still reaches the guard below.
    if (summary === undefined) continue
    const mainView = summary.retainedBy?.mainView
    if (typeof mainView === 'number' && mainView > 0 && typeof summary.id === 'string') return summary.id
  }
  return undefined
}

/** How many times an unanswered delete request is re-checked against the list. */
export const DELETE_VERIFY_ATTEMPTS = 4

/** Delay between two re-checks of the session list. */
export const DELETE_VERIFY_INTERVAL_MS = 350

/** What the post-failure verification needs, injected so it stays testable. */
export interface DeleteVerificationDeps {
  /** Whether the id is still present in the current list snapshot. */
  listed: () => boolean
  /** Re-pull the list from the host; absent on hosts without `refresh`. */
  refresh?: (() => Promise<void>) | undefined
  /** Wait between attempts (page code passes a timer, tests a no-op). */
  sleep: (ms: number) => Promise<void>
  /** Attempt budget; defaults to DELETE_VERIFY_ATTEMPTS. */
  attempts?: number
  /** Delay passed to `sleep`; defaults to DELETE_VERIFY_INTERVAL_MS. */
  intervalMs?: number
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
export async function verifySessionDeleted(deps: DeleteVerificationDeps): Promise<boolean> {
  const attempts = deps.attempts ?? DELETE_VERIFY_ATTEMPTS
  const intervalMs = deps.intervalMs ?? DELETE_VERIFY_INTERVAL_MS
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      await deps.refresh?.()
    } catch {
      // A failed refresh is not an answer; the snapshot below still is.
    }
    if (!deps.listed()) return true
    if (attempt + 1 < attempts) await deps.sleep(intervalMs)
  }
  return !deps.listed()
}
