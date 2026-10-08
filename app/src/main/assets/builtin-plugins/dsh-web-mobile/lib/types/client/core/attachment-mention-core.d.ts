/** Source name: unique per trigger in the input-trigger registry, and the
 *  routing key the composer uses to find our codec at submit time. */
export declare const ATTACHMENT_SOURCE = "mobile-nav-attachments";
/** Group display order. The registry sorts ascending (default 0, which is
 *  where ui-reference's @文件/@会话 group sits), so a negative order puts the
 *  small, context-local 「本次附件」 section ABOVE the long file listing. */
export declare const ATTACHMENT_SOURCE_ORDER = -10;
export type AttachmentKind = 'file' | 'image';
/** Upload state of a file draft (images never upload before send). */
export type UploadStatus = 'uploading' | 'ready' | 'error';
/** One draft attachment, normalized from the host descriptor. */
export interface DraftAttachment {
    readonly id: string;
    readonly kind: AttachmentKind;
    /** Browser `File.name`; '' for unnamed pastes. */
    readonly name: string;
    readonly bytes?: number;
    readonly status?: UploadStatus;
}
/** The opaque chip payload: a pick-time snapshot. It must be self-contained —
 *  the composer clears `attachmentIds` BEFORE it asks the codec to serialize
 *  (ui-conversation `sinkSerialized`), so serialize cannot consult live state. */
export interface AttachmentRef {
    readonly v: number;
    readonly id: string;
    readonly kind: AttachmentKind;
    readonly name: string;
    /** 1-based position in the draft rail at pick time. */
    readonly ordinal: number;
    /** Whether the name alone is ambiguous (empty, or shared by another draft). */
    readonly ambiguous: boolean;
}
/** Copy the candidate builder needs; supplied by the effect's locale bind. */
export interface AttachmentCopy {
    readonly section: string;
    readonly kindFile: string;
    readonly kindImage: string;
    readonly uploading: string;
    readonly failed: string;
    /** Display label for an unnamed draft, `{n}` = ordinal. */
    readonly unnamed: (ordinal: number) => string;
    /** Persistence prefix of the clipboard projection, e.g. `附件`. */
    readonly mention: string;
}
/** The candidate shape the rc.2 menu renders (superset of the frozen rc.6
 *  `InputTriggerCandidate`: `label`/`section`/`value` arrived later). */
export interface AttachmentCandidate {
    readonly name: string;
    readonly description?: string;
    readonly icon: 'file';
    readonly section: string;
    readonly value: string;
}
/** The insert half of a pick outcome (`appearance` is the rc.2 chip glyph). */
export interface AttachmentInsert {
    readonly source: string;
    readonly ref: string;
    readonly label: string;
    readonly appearance: 'file';
    readonly clipboardText: string;
}
/**
 * Ordered draft attachment ids from one input-state snapshot. 0.2.0-rc.2
 * publishes `attachmentIds` (files + images); the frozen rc.6 contract named
 * the image-only predecessor `imageIds`. Anything else → empty.
 */
export declare function draftAttachmentIdsOf(state: unknown): readonly string[];
/**
 * Normalize host draft descriptors (`{ kind, id, file }`, plus `previewUrl` on
 * images) into plain records. `uploads` is the host `fileUploads` snapshot
 * (`{ [id]: { status } }`), optional. Unknown shapes are dropped, not guessed.
 */
export declare function normalizeDraftAttachments(descriptors: unknown, uploads?: unknown): DraftAttachment[];
/** Compact byte size for the row description. */
export declare function formatBytes(bytes: number): string;
/** Pick-time refs for the whole rail: ordinal + ambiguity need every sibling. */
export declare function buildRefs(attachments: readonly DraftAttachment[]): AttachmentRef[];
/**
 * Whether one attachment survives the live query. A drilled listing or a
 * path-shaped query (contains `/`) belongs to the @文件 browser, so the
 * section stays out of it; an empty query lists everything.
 */
export declare function matchesQuery(name: string, query: string, drilled: boolean): boolean;
/**
 * Menu rows for the current rail. Empty rail or no match → [] (the menu drops
 * an empty ready group, so the section simply does not appear). Prefix
 * matches rank before infix matches; ties keep rail order.
 */
export declare function buildCandidates(attachments: readonly DraftAttachment[], query: string, drilled: boolean, copy: AttachmentCopy): AttachmentCandidate[];
/** Decode a ref string (candidate value or chip ref); undefined when foreign. */
export declare function parseRef(value: unknown): AttachmentRef | undefined;
/** Clipboard / persistence projection: `@附件:name` (or `@附件:#n`). */
export declare function clipboardTextOf(ref: AttachmentRef, copy: Pick<AttachmentCopy, 'mention'>): string;
/**
 * Turn one menu pick into the insert outcome; undefined (= default sink) for a
 * value this source did not mint.
 */
export declare function insertForPick(value: unknown, copy: Pick<AttachmentCopy, 'unnamed' | 'mention'>): AttachmentInsert | undefined;
/**
 * Model form of one chip. It mirrors the identity the model already sees for
 * the attachment itself — dsh-llm renders a file part as
 * `File "<name>" (<bytes> bytes, sha256:…)` and an image part as
 * `Image "<name>" (<attachmentId>)` — so `[attachment: File "report.pdf"]`
 * lines up with exactly one block of the same user message. Names are
 * JSON-quoted like the host does. The ordinal (`#n`, rail order = the order
 * the attachment parts precede the text) is added only when the name alone
 * is ambiguous (unnamed paste, duplicate names). English on purpose: it is
 * model-facing and must not change with the UI locale.
 */
export declare function serializeRef(ref: string): string;
//# sourceMappingURL=attachment-mention-core.d.ts.map