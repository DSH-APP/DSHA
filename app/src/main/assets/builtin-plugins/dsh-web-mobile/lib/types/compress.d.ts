/**
 * Find a header value regardless of the caller's key casing. The patch sees
 * the RAW writeHead argument (before Node lowercases), and HTTP header names
 * are case-insensitive — a caller may pass `Content-Type` or `content-type`.
 * (Case-insensitivity fix ported from community fork wzxmt-zhc/dsh-web-mobile.)
 */
export declare function headerValue(headers: Record<string, string | number | string[]>, name: string): string | undefined;
/** Whether a response warrants deferred (potentially compressed) handling. */
export declare function isDeferrable(headers: Record<string, string | number | string[]>): boolean;
/** Append the Accept-Encoding Vary token without clobbering an existing Vary. */
export declare function varyWithAcceptEncoding(headers: Record<string, string | number | string[]>): void;
/**
 * Install the compression patch on http.ServerResponse.prototype.
 * @returns disposer restoring the original methods (plugin reload safety).
 */
export declare function installResponseCompression(): () => void;
//# sourceMappingURL=compress.d.ts.map