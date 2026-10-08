import type { ServerResponse } from 'node:http';
/**
 * Parse one `Accept-Encoding` field into a coding → qvalue map. A missing
 * weight means 1; an unparsable or out-of-range weight means 0 ("not
 * acceptable"), which is what HTTP implementations do with garbage. The
 * wildcard `*` is kept as its own entry and consulted as the fallback for any
 * coding the client did not name.
 */
export declare function parseAcceptedEncodings(field: string): Map<string, number>;
/**
 * Choose the codec the client accepts; `br` outranks `gzip`, and a weight of 0
 * is a refusal (RFC 9110 §12.4.2) — `br;q=0` must not select brotli, and a
 * bare `*` is an offer, not a refusal. No acceptable coding means `null` and
 * the response passes through identity (never 406: identity stays acceptable).
 */
export declare function pickEncoding(res: ServerResponse): 'br' | 'gzip' | null;
/**
 * Find a header value regardless of the caller's key casing. The patch sees
 * the RAW writeHead argument (before Node lowercases), and HTTP header names
 * are case-insensitive — a caller may pass `Content-Type` or `content-type`.
 * (Case-insensitivity fix ported from community fork wzxmt-zhc/dsh-web-mobile.)
 */
export declare function headerValue(headers: Record<string, string | number | string[]>, name: string): string | undefined;
/** Whether a response warrants deferred (potentially compressed) handling. */
export declare function isDeferrable(headers: Record<string, string | number | string[]>): boolean;
/**
 * Append the Accept-Encoding Vary token without clobbering an existing Vary
 * and without duplicating the token: re-running the patch (or a caller that
 * already varied on it, in any casing) must stay idempotent, and `*` already
 * covers Accept-Encoding.
 */
export declare function varyWithAcceptEncoding(headers: Record<string, string | number | string[]>): void;
/**
 * Install the compression patch on http.ServerResponse.prototype.
 * @returns disposer restoring the original methods (plugin reload safety).
 */
export declare function installResponseCompression(): () => void;
//# sourceMappingURL=compress.d.ts.map