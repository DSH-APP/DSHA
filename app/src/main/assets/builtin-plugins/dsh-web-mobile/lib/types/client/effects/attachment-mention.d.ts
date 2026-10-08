import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
import type { AttachmentCandidate, AttachmentInsert, DraftAttachment } from '../core/attachment-mention-core.ts';
/**
 * 「本次附件」 @ source: while the composer rail above the input holds draft
 * attachments (uploaded files and images), typing `@` lists them in their own
 * menu section; a pick inserts one non-editable chip whose model form names
 * that attachment (format + rationale: `serializeRef` in the core file).
 * The `/` command menu is untouched — this source binds `@` only.
 *
 * Mobile-only by the plugin's desktop no-op promise: the source is registered
 * under MOBILE_QUERY via installMobileEffect and unregistered when it stops
 * matching.
 *
 * HOW THE RAIL IS READ (public services only — no DOM, no fiber):
 *   sessions.scope(sessionId)                 → the session-scope ctx
 *   conversation.input.for(actx).state        → SnapshotStore<InputState>
 *       (frozen contract `SessionInput.state`, ui-conversation input/contract.d.ts)
 *   InputState.attachmentIds                  → ordered draft ids (rc.2;
 *       the rc.6 contract named the image-only predecessor `imageIds`)
 *   conversation.resolveDraftAttachments(ids) → `{ kind, id, file }` descriptors
 *       (rc.2; rc.6 `draftImages(ids)` — both feature-detected)
 *   conversation.fileUploads.getSnapshot()    → `{ [id]: { status } }` (optional)
 * This is exactly what the host's own composer renders the rail from
 * (ui-conversation lib/client.js: InputBar `resolveDraftAttachments(input.attachmentIds)`).
 *
 * RE-AUDIT ON EVERY HOST UPGRADE (ui-conversation / ui-input-trigger):
 * - `attachmentIds` / `resolveDraftAttachments` / `fileUploads` names (the
 *   two latter are NOT in the frozen IConversation face — public class
 *   members only). A rename degrades to "section never appears", not to an error.
 * - `sinkSerialized` clears attachmentIds BEFORE codec.serialize runs, which is
 *   why the chip ref is a self-contained pick-time snapshot.
 * - rc.2 candidate fields `section`/`value`, `showGroupTitle`, `drilled`, and
 *   the insert `appearance` (chip glyphs: 'session' | 'file' | 'folder' only —
 *   there is no image glyph, so images use 'file').
 */
/** Own locale namespace (locales.ts stays untouched; same NS/zh/en shape as ui-reference). */
export declare const ATTACHMENT_NS = "mobileNav.attachments";
export declare const ATTACHMENT_ZH: {
    readonly section: "本次附件";
    readonly 'kind.file': "文件";
    readonly 'kind.image': "图片";
    readonly 'status.uploading': "上传中";
    readonly 'status.failed': "上传失败";
    readonly unnamed: "附件 {n}";
    readonly mention: "附件";
};
export type AttachmentMentionKey = keyof typeof ATTACHMENT_ZH;
export declare const ATTACHMENT_EN: Record<AttachmentMentionKey, string>;
declare module '@deepseek-ai/dsh-client-ui-slots' {
    interface LocaleNamespaceMap {
        /** 「本次附件」 @ source copy. */
        'mobileNav.attachments': AttachmentMentionKey;
    }
}
interface SessionProjection {
    readonly sessionId: string;
}
interface CandidateRequestLike {
    readonly query: string;
    readonly drilled?: boolean;
    readonly signal: AbortSignal;
}
interface AttachmentSource {
    readonly trigger: '@';
    readonly name: string;
    readonly order: number;
    readonly showGroupTitle: false;
    candidates(session: SessionProjection, req: CandidateRequestLike): Promise<readonly AttachmentCandidate[]>;
    onPick(pick: {
        readonly candidate: {
            readonly value?: unknown;
        };
    }): {
        insert: AttachmentInsert;
    } | undefined;
    readonly codec: {
        clipboardText(ref: string): string;
        serialize(ref: string, signal: AbortSignal): Promise<string>;
    };
}
/**
 * The current draft rail of one session, or [] when this host generation
 * does not expose it (missing members = capability absent, not an error).
 */
export declare function readDraftAttachments(ctx: ClientContext, sessionId: string): DraftAttachment[];
/** The source object; `ctx` must be a scope where inputTriggers/sessions/conversation resolve. */
export declare function createAttachmentSource(ctx: ClientContext): AttachmentSource;
/**
 * Register the 「本次附件」 @ source while the mobile breakpoint matches.
 * `inputTriggers` is optional at the fiber level (ctx.inject): a host
 * without ui-input-trigger simply never arms the feature.
 * @param ctx - client root context.
 */
export declare function installAttachmentMention(ctx: ClientContext): void;
export {};
//# sourceMappingURL=attachment-mention.d.ts.map