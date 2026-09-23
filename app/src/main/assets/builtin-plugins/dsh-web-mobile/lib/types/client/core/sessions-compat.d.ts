/** The current session id: rc.2's `current` field when present, else the a2
 *  main-view-retained session. Undefined when the shape matches neither. */
export declare function currentSessionIdOf(list: unknown): string | undefined;
/** a2 removed `clear()` (selection lifecycle moved to the retain model). */
export declare function sessionsCanClear(sessions: unknown): boolean;
/** a2 removed `open()`; callers must degrade (armNav fallback in
 *  phone-chrome) instead of throwing inside the capture pointerup listener. */
export declare function sessionsCanOpen(sessions: unknown): boolean;
//# sourceMappingURL=sessions-compat.d.ts.map