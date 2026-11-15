import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { installMobileEffect } from '../platform/breakpoints.ts'
import {
  ATTACHMENT_SOURCE,
  ATTACHMENT_SOURCE_ORDER,
  buildCandidates,
  draftAttachmentIdsOf,
  insertForPick,
  normalizeDraftAttachments,
  serializeRef,
} from '../core/attachment-mention-core.ts'
import type { AttachmentCandidate, AttachmentCopy, AttachmentInsert, DraftAttachment } from '../core/attachment-mention-core.ts'

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
 *   InputState.attachmentIds                  → ordered draft ids
 *   conversation.resolveDraftAttachments(ids) → `{ kind, id, file }` descriptors
 *       (the rc.6 `imageIds` / `draftImages(ids)` predecessors were removed with
 *       the host floor: 0 hits in the 0.2.2-rc.1 ui-conversation bundle)
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

/** Own locale namespace, package-prefixed (locales.ts stays untouched; same NS/zh/en shape as ui-reference). */
export const ATTACHMENT_NS = 'dshWebMobileNav.attachments'

export const ATTACHMENT_ZH = {
  'section': '本次附件',
  'kind.file': '文件',
  'kind.image': '图片',
  'status.uploading': '上传中',
  'status.failed': '上传失败',
  'unnamed': '附件 {n}',
  'mention': '附件',
} as const

export type AttachmentMentionKey = keyof typeof ATTACHMENT_ZH

export const ATTACHMENT_EN: Record<AttachmentMentionKey, string> = {
  'section': 'Attachments in this message',
  'kind.file': 'File',
  'kind.image': 'Image',
  'status.uploading': 'Uploading',
  'status.failed': 'Upload failed',
  'unnamed': 'Attachment {n}',
  'mention': 'attachment',
}

declare module '@deepseek-ai/dsh-client-ui-slots' {
  interface LocaleNamespaceMap {
    /** 「本次附件」 @ source copy. */
    'dshWebMobileNav.attachments': AttachmentMentionKey
  }
}

// ── Structural views of host services (rc.2 runtime shapes; never imported,
//    so this compiles against the rc.6 typings the repo pins). ──

interface SnapshotLike { getSnapshot(): unknown }

interface ConversationLike {
  input?: { for?: (actx: unknown) => { state?: SnapshotLike } | undefined }
  resolveDraftAttachments?: (ids: readonly string[]) => unknown
  fileUploads?: SnapshotLike
}

interface SessionsLike { scope?: (id: string) => unknown }

interface SessionProjection { readonly sessionId: string }

interface CandidateRequestLike {
  readonly query: string
  readonly drilled?: boolean
  readonly signal: AbortSignal
}

interface AttachmentSource {
  readonly trigger: '@'
  readonly name: string
  readonly order: number
  readonly showGroupTitle: false
  candidates(session: SessionProjection, req: CandidateRequestLike): Promise<readonly AttachmentCandidate[]>
  onPick(pick: { readonly candidate: { readonly value?: unknown } }): { insert: AttachmentInsert } | undefined
  readonly codec: {
    clipboardText(ref: string): string
    serialize(ref: string, signal: AbortSignal): Promise<string>
  }
}

interface InputTriggersLike { registerSource(src: AttachmentSource): () => void }

/**
 * The current draft rail of one session, or [] when this host generation
 * does not expose it (missing members = capability absent, not an error).
 */
export function readDraftAttachments(ctx: ClientContext, sessionId: string): DraftAttachment[] {
  const sessions = ctx.get('sessions') as unknown as SessionsLike | undefined
  const conversation = ctx.get('conversation') as unknown as ConversationLike | undefined
  const actx = sessions?.scope?.(sessionId)
  if (actx === undefined || conversation?.input?.for === undefined) return []
  const ids = draftAttachmentIdsOf(conversation.input.for(actx)?.state?.getSnapshot())
  if (ids.length === 0) return []
  const descriptors = typeof conversation.resolveDraftAttachments === 'function'
    ? conversation.resolveDraftAttachments(ids)
    : undefined
  return normalizeDraftAttachments(descriptors, conversation.fileUploads?.getSnapshot())
}

function copyOf(ctx: ClientContext): AttachmentCopy {
  const t = ctx.locale.bind(ATTACHMENT_NS)
  return {
    section: t('section'),
    kindFile: t('kind.file'),
    kindImage: t('kind.image'),
    uploading: t('status.uploading'),
    failed: t('status.failed'),
    unnamed: (n) => t('unnamed', { n }),
    mention: t('mention'),
  }
}

/** The source object; `ctx` must be a scope where inputTriggers/sessions/conversation resolve. */
export function createAttachmentSource(ctx: ClientContext): AttachmentSource {
  return {
    trigger: '@',
    name: ATTACHMENT_SOURCE,
    order: ATTACHMENT_SOURCE_ORDER,
    // Section title comes from the candidates' own `section`; the group title
    // would otherwise look up `slash.menu` with our raw source name.
    showGroupTitle: false,
    candidates(session, req) {
      if (req.signal.aborted) return Promise.resolve([])
      // MUST NOT THROW. The host calls `source.candidates(...)` synchronously and
      // only attaches `.then(onFulfilled, onRejected)` AFTER the call returns
      // (ui-input-trigger `fetchCandidates`: 「for (const source of roster)
      // source.candidates(projection, …).then(…)」), so a synchronous throw here
      // is nobody's rejection and takes down that whole `@` / `/` menu beat.
      // The read below is deliberately synchronous, and its dependencies are
      // host-side getters that can throw on a released scope
      // (`conversation.input.for()` → "requires a retained Session scope"), so a
      // capability gap must stay a capability gap: an empty list, the same
      // result as a host generation without these members.
      try {
        // Synchronous read: resolves in the same microtask turn as the reference
        // source's skeleton, so no pending row flashes for this group.
        return Promise.resolve(buildCandidates(readDraftAttachments(ctx, session.sessionId), req.query, req.drilled === true, copyOf(ctx)))
      } catch {
        return Promise.resolve([])
      }
    },
    onPick({ candidate }) {
      const insert = insertForPick(candidate.value, copyOf(ctx))
      return insert === undefined ? undefined : { insert }
    },
    codec: {
      // The chip's own clipboard projection is cached on the occurrence at
      // insert time; this path only serves owner-side re-projection.
      clipboardText: (ref) => insertForPick(ref, copyOf(ctx))?.clipboardText ?? '',
      serialize: (ref) => {
        try {
          return Promise.resolve(serializeRef(ref))
        } catch (error) {
          return Promise.reject(error)
        }
      },
    },
  }
}

/**
 * Register the 「本次附件」 @ source while the mobile breakpoint matches.
 * `inputTriggers` is optional at the fiber level (ctx.inject): a host
 * without ui-input-trigger simply never arms the feature.
 * @param ctx - client root context.
 */
export function installAttachmentMention(ctx: ClientContext): void {
  ctx.effect(() => ctx.locale.register(ATTACHMENT_NS, attachmentLocales), 'dsh-web-mobile: attachment mention dictionaries')
  ctx.inject(['inputTriggers'], (scope: ClientContext) => {
    installMobileEffect(scope, 'dsh-web-mobile: attachment mention source', () => {
      const registry = scope.get('inputTriggers') as unknown as InputTriggersLike | undefined
      if (typeof registry?.registerSource !== 'function') return undefined
      return registry.registerSource(createAttachmentSource(scope))
    })
  })
}

// DSHA_MOBILE_LOCALES_V1: generated from tools/mobile-locales.json; do not edit here.
export const attachmentLocales: Record<string, Record<AttachmentMentionKey, string>> = { zh: ATTACHMENT_ZH, en: ATTACHMENT_EN, ...{
  "zh-Hant": {
    "section": "本次附件",
    "kind.file": "檔案",
    "kind.image": "圖片",
    "status.uploading": "上傳中",
    "status.failed": "上傳失敗",
    "unnamed": "附件 {n}",
    "mention": "附件"
  },
  "ru": {
    "section": "Вложения в этом сообщении",
    "kind.file": "Файл",
    "kind.image": "Изображение",
    "status.uploading": "Загрузка",
    "status.failed": "Не удалось загрузить",
    "unnamed": "Вложение {n}",
    "mention": "вложение"
  },
  "ja": {
    "section": "このメッセージの添付ファイル",
    "kind.file": "ファイル",
    "kind.image": "画像",
    "status.uploading": "アップロード中",
    "status.failed": "アップロード失敗",
    "unnamed": "添付ファイル {n}",
    "mention": "添付ファイル"
  },
  "ko": {
    "section": "이 메시지의 첨부 파일",
    "kind.file": "파일",
    "kind.image": "이미지",
    "status.uploading": "업로드 중",
    "status.failed": "업로드 실패",
    "unnamed": "첨부 파일 {n}",
    "mention": "첨부 파일"
  },
  "ar": {
    "section": "مرفقات هذه الرسالة",
    "kind.file": "ملف",
    "kind.image": "صورة",
    "status.uploading": "جارٍ الرفع",
    "status.failed": "فشل الرفع",
    "unnamed": "مرفق {n}",
    "mention": "مرفق"
  },
  "pt": {
    "section": "Anexos desta mensagem",
    "kind.file": "Arquivo",
    "kind.image": "Imagem",
    "status.uploading": "Enviando",
    "status.failed": "Falha no envio",
    "unnamed": "Anexo {n}",
    "mention": "anexo"
  },
  "la": {
    "section": "Adnexa huius nuntii",
    "kind.file": "Fasciculus",
    "kind.image": "Imago",
    "status.uploading": "Mittitur",
    "status.failed": "Missio defecit",
    "unnamed": "Adnexum {n}",
    "mention": "adnexum"
  },
  "fr": {
    "section": "Pièces jointes de ce message",
    "kind.file": "Fichier",
    "kind.image": "Image",
    "status.uploading": "Importation",
    "status.failed": "Échec de l’importation",
    "unnamed": "Pièce jointe {n}",
    "mention": "pièce jointe"
  }
} }
