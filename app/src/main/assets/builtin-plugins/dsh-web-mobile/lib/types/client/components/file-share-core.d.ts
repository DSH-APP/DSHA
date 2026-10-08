/**
 * File-share pure core: how a workspace file's bytes are read and how they
 * reach the user — the system share sheet when the platform can share files,
 * a plain download otherwise. DOM-free and import-free so node --test drives
 * every decision with injected fakes (tests/file-share.test.ts); the React
 * controls in file-share-controls.tsx bind the real navigator/document.
 *
 * Reading goes through the host's own Remote (`remote.workspaceFiles.readBytes`,
 * dsh-api-workspace-files) — the same call the document preview uses, so
 * authentication, sandbox policy and workspace confinement stay host-owned.
 * Reads are windowed: one ranged call is capped by the host `maxBytes`
 * (default 2 MiB, a larger window is refused rather than shortened), so the
 * file is pulled in CHUNK_BYTES windows until EOF.
 */
/** Above this size the share sheet is skipped and the file is downloaded instead. */
export declare const SHARE_MAX_BYTES: number;
/** One ranged read; well under the host's default 2 MiB per-call window cap. */
export declare const CHUNK_BYTES: number;
/** Stable failure codes the controls map to localized copy. */
export type FileShareErrorCode = 'too-large' | 'not-found' | 'read-failed';
/** A share-flow failure carrying its stable code. */
export declare class FileShareError extends Error {
    readonly code: FileShareErrorCode;
    /**
     * @param code - stable failure code.
     * @param message - diagnostic text.
     */
    constructor(code: FileShareErrorCode, message: string);
}
/** The successful readBytes payload fields this module consumes. */
export interface ReadBytesValue {
    readonly offset: number;
    readonly data: Uint8Array;
    readonly eof: boolean;
    readonly bytes?: number | undefined;
}
/** The host RemoteResult shape of one readBytes call. */
export type ReadBytesResult = {
    readonly ok: true;
    readonly value: ReadBytesValue;
} | {
    readonly ok: false;
    readonly error: {
        readonly code?: string;
        readonly message?: string;
    };
};
/**
 * One bound ranged read (session already applied).
 * @param path - absolute or workspace-relative file path.
 * @param range - byte window.
 * @param signal - cancellation.
 */
export type ReadRange = (path: string, range: {
    offset: number;
    length: number;
}, signal: AbortSignal) => Promise<ReadBytesResult>;
/** A fully read file. */
export interface ReadFile {
    readonly parts: Uint8Array<ArrayBuffer>[];
    readonly size: number;
}
/**
 * Read a whole file in windows until EOF. The first window also reports the
 * file's size (`bytes`), so an oversized file is refused after one small read.
 * @param read - bound ranged read.
 * @param path - file path.
 * @param signal - cancellation; an abort rejects with the signal's reason.
 * @param chunk - window size (tests shrink it).
 * @param max - refuse files above this size.
 * @returns the chunks and their total size.
 */
export declare function readWholeFile(read: ReadRange, path: string, signal: AbortSignal, chunk?: number, max?: number): Promise<ReadFile>;
/** Final outcome of one delivery. */
export type ShareOutcome = {
    readonly kind: 'shared';
} | {
    readonly kind: 'cancelled';
} | {
    readonly kind: 'downloaded';
    readonly reason: 'unsupported' | 'too-large' | 'share-failed';
};
/** The minimal File surface the decision needs. */
export interface ShareableFile {
    readonly name: string;
    readonly size: number;
}
/** Platform seams (navigator.canShare / navigator.share / anchor download). */
export interface ShareDeps<F extends ShareableFile> {
    readonly canShare?: ((data: {
        files: F[];
    }) => boolean) | undefined;
    readonly share?: ((data: {
        files: F[];
        title: string;
    }) => Promise<void>) | undefined;
    readonly download: (file: F) => void;
    readonly shareMax?: number;
}
/** Whether a rejection is the user dismissing the share sheet. */
export declare function isAbortError(error: unknown): boolean;
/**
 * Whether this platform can deliver a file to another app at all.
 *
 * The UI reads it to label the button HONESTLY: when nothing can share, the
 * press can only end in a download, so calling it 「分享」 would be a lie (owner
 * report 2026-10-07: 「还是选择存到哪里，而不是分享到媒体，比如说微信QQ，不然这不算是
 * 文件分享」). Deliberately a *surface* probe, not a per-file `canShare` call:
 * `canShare` wants real `File` objects, and a fabricated probe object makes
 * browsers report false even for shareable files.
 *
 * Deliberately NOT named after `navigator.share`: Android WebView implements no
 * Web Share at all (only standalone browsers do), so a host-side bridge — e.g. a
 * DSHA `ACTION_SEND` entry point — is the route that actually matters on a phone,
 * and it would NOT show up in these two seams. When such a bridge lands, extend
 * `ShareDeps` with it and make this predicate (`bridge !== undefined ||` …) and
 * the `deliverFile` routing order **bridge → Web Share → download** agree; the
 * label and the glyph follow this predicate automatically, so the button flips
 * back to 「分享「x」」 with no UI change. Do not pre-guess the bridge's shape from
 * here: a `path`-only bridge needs the host half, a byte/base64 bridge does not.
 * @param deps - platform seams.
 * @returns true when both Web Share members are present.
 */
export declare function canShareFiles<F extends ShareableFile>(deps: ShareDeps<F>): boolean;
/**
 * Deliver one file: the system share sheet when the platform can share it,
 * otherwise a download. A dismissed sheet (AbortError) is a silent cancel;
 * any other share rejection (NotAllowedError after the user-activation window
 * lapsed during a long read, a target app refusing the type, …) still gets
 * the file to the user through the download route.
 * @param file - the file to deliver.
 * @param deps - platform seams.
 * @returns the outcome.
 */
export declare function deliverFile<F extends ShareableFile>(file: F, deps: ShareDeps<F>): Promise<ShareOutcome>;
/**
 * The basename of a host path, in either separator spelling.
 * @param path - absolute or relative path.
 * @returns the last segment (the path itself when it has none).
 */
export declare function fileNameOf(path: string): string;
/**
 * MIME type guessed from a file name's extension.
 * @param name - file name.
 * @returns the guessed type, or application/octet-stream.
 */
export declare function mimeTypeOf(name: string): string;
//# sourceMappingURL=file-share-core.d.ts.map