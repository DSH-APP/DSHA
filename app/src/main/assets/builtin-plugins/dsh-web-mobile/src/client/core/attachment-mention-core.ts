// attachment-mention-core.ts — DOM-free, service-free half of the
// 「本次附件」 @ source (effects/attachment-mention.ts). Everything that can be
// decided from plain data lives here so node:test can pin it with no DOM, no
// renderer and no DSH runtime: host-shape normalization, candidate building,
// query filtering, pick parsing and the model serialization of one chip.
//
// Deliberately has ZERO import statements, like reconciler-core.ts and
// session-row-fiber.ts: tests load it through Node's native type stripping and
// the client bundle has nothing to resolve.

/** Source name: unique per trigger in the input-trigger registry, and the
 *  routing key the composer uses to find our codec at submit time. */
export const ATTACHMENT_SOURCE = 'mobile-nav-attachments'

/** Group display order. The registry sorts ascending (default 0, which is
 *  where ui-reference's @文件/@会话 group sits), so a negative order puts the
 *  small, context-local 「本次附件」 section ABOVE the long file listing. */
export const ATTACHMENT_SOURCE_ORDER = -10

/** Version tag inside the opaque ref, so a future format change can tell its
 *  own refs apart from ones cached in an older draft occurrence. */
const REF_VERSION = 1

export type AttachmentKind = 'file' | 'image'

/** Upload state of a file draft (images never upload before send). */
export type UploadStatus = 'uploading' | 'ready' | 'error'

/** One draft attachment, normalized from the host descriptor. */
export interface DraftAttachment {
  readonly id: string
  readonly kind: AttachmentKind
  /** Browser `File.name`; '' for unnamed pastes. */
  readonly name: string
  readonly bytes?: number
  readonly status?: UploadStatus
}

/** The opaque chip payload: a pick-time snapshot. It must be self-contained —
 *  the composer clears `attachmentIds` BEFORE it asks the codec to serialize
 *  (ui-conversation `sinkSerialized`), so serialize cannot consult live state. */
export interface AttachmentRef {
  readonly v: number
  readonly id: string
  readonly kind: AttachmentKind
  readonly name: string
  /** 1-based position in the draft rail at pick time. */
  readonly ordinal: number
  /** Whether the name alone is ambiguous (empty, or shared by another draft). */
  readonly ambiguous: boolean
}

/** Copy the candidate builder needs; supplied by the effect's locale bind. */
export interface AttachmentCopy {
  readonly section: string
  readonly kindFile: string
  readonly kindImage: string
  readonly uploading: string
  readonly failed: string
  /** Display label for an unnamed draft, `{n}` = ordinal. */
  readonly unnamed: (ordinal: number) => string
  /** Persistence prefix of the clipboard projection, e.g. `附件`. */
  readonly mention: string
}

/** The candidate shape the rc.2 menu renders (superset of the frozen rc.6
 *  `InputTriggerCandidate`: `label`/`section`/`value` arrived later). */
export interface AttachmentCandidate {
  readonly name: string
  readonly description?: string
  readonly icon: 'file'
  readonly section: string
  readonly value: string
}

/** The insert half of a pick outcome (`appearance` is the rc.2 chip glyph). */
export interface AttachmentInsert {
  readonly source: string
  readonly ref: string
  readonly label: string
  readonly appearance: 'file'
  readonly clipboardText: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/**
 * Ordered draft attachment ids from one input-state snapshot (`attachmentIds`,
 * files + images). The rc.6 image-only `imageIds` fallback was removed with
 * the host floor. Anything else → empty.
 */
export function draftAttachmentIdsOf(state: unknown): readonly string[] {
  if (!isRecord(state)) return []
  const ids = Array.isArray(state.attachmentIds) ? state.attachmentIds : []
  return ids.filter((id): id is string => typeof id === 'string')
}

/**
 * Normalize host draft descriptors (`{ kind, id, file }`, plus `previewUrl` on
 * images) into plain records. `uploads` is the host `fileUploads` snapshot
 * (`{ [id]: { status } }`), optional. Unknown shapes are dropped, not guessed.
 */
export function normalizeDraftAttachments(descriptors: unknown, uploads?: unknown): DraftAttachment[] {
  if (!Array.isArray(descriptors)) return []
  const out: DraftAttachment[] = []
  for (const raw of descriptors) {
    if (!isRecord(raw) || typeof raw.id !== 'string') continue
    const kind = raw.kind === 'image' || raw.kind === 'file' ? raw.kind : undefined
    if (kind === undefined) continue
    const file = isRecord(raw.file) ? raw.file : undefined
    const name = typeof file?.name === 'string' ? file.name : ''
    const size = typeof file?.size === 'number' && Number.isFinite(file.size) ? file.size : undefined
    const upload = isRecord(uploads) && isRecord(uploads[raw.id]) ? uploads[raw.id] as Record<string, unknown> : undefined
    const status = upload?.status === 'uploading' || upload?.status === 'ready' || upload?.status === 'error' ? upload.status : undefined
    out.push({
      id: raw.id,
      kind,
      name,
      ...(size === undefined ? {} : { bytes: size }),
      ...(kind === 'file' && status !== undefined ? { status } : {}),
    })
  }
  return out
}

/** Compact byte size for the row description. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value >= 10 ? Math.round(value) : Math.round(value * 10) / 10} ${units[unit]}`
}

/** Pick-time refs for the whole rail: ordinal + ambiguity need every sibling. */
export function buildRefs(attachments: readonly DraftAttachment[]): AttachmentRef[] {
  const counts = new Map<string, number>()
  for (const a of attachments) counts.set(a.name, (counts.get(a.name) ?? 0) + 1)
  return attachments.map((a, index) => ({
    v: REF_VERSION,
    id: a.id,
    kind: a.kind,
    name: a.name,
    ordinal: index + 1,
    ambiguous: a.name === '' || (counts.get(a.name) ?? 0) > 1,
  }))
}

/** Chip / row title: the file name, or the localized unnamed label. */
function labelOf(ref: Pick<AttachmentRef, 'name' | 'ordinal'>, copy: Pick<AttachmentCopy, 'unnamed'>): string {
  return ref.name === '' ? copy.unnamed(ref.ordinal) : ref.name
}

/**
 * Whether one attachment survives the live query. A drilled listing or a
 * path-shaped query (contains `/`) belongs to the @文件 browser, so the
 * section stays out of it; an empty query lists everything.
 */
export function matchesQuery(name: string, query: string, drilled: boolean): boolean {
  if (drilled || query.includes('/')) return false
  const q = query.trim().toLowerCase()
  return q === '' || name.toLowerCase().includes(q)
}

/**
 * Menu rows for the current rail. Empty rail or no match → [] (the menu drops
 * an empty ready group, so the section simply does not appear). Prefix
 * matches rank before infix matches; ties keep rail order.
 */
export function buildCandidates(
  attachments: readonly DraftAttachment[],
  query: string,
  drilled: boolean,
  copy: AttachmentCopy,
): AttachmentCandidate[] {
  const q = query.trim().toLowerCase()
  const rows: { rank: number; index: number; row: AttachmentCandidate }[] = []
  for (const [index, ref] of buildRefs(attachments).entries()) {
    const attachment = attachments[index]
    if (attachment === undefined) continue
    const label = labelOf(ref, copy)
    if (!matchesQuery(label, query, drilled)) continue
    const parts = [attachment.kind === 'image' ? copy.kindImage : copy.kindFile]
    if (attachment.bytes !== undefined) parts.push(formatBytes(attachment.bytes))
    if (attachment.status === 'uploading') parts.push(copy.uploading)
    else if (attachment.status === 'error') parts.push(copy.failed)
    rows.push({
      rank: q !== '' && label.toLowerCase().startsWith(q) ? 0 : 1,
      index,
      row: { name: label, description: parts.join(' · '), icon: 'file', section: copy.section, value: JSON.stringify(ref) },
    })
  }
  rows.sort((a, b) => a.rank - b.rank || a.index - b.index)
  return rows.map((entry) => entry.row)
}

/** Decode a ref string (candidate value or chip ref); undefined when foreign. */
export function parseRef(value: unknown): AttachmentRef | undefined {
  if (typeof value !== 'string') return undefined
  let parsed: unknown
  try {
    parsed = JSON.parse(value)
  } catch {
    return undefined
  }
  if (!isRecord(parsed) || parsed.v !== REF_VERSION) return undefined
  const { id, kind, name, ordinal, ambiguous } = parsed
  if (typeof id !== 'string' || (kind !== 'file' && kind !== 'image') || typeof name !== 'string') return undefined
  if (typeof ordinal !== 'number' || !Number.isInteger(ordinal) || ordinal < 1 || typeof ambiguous !== 'boolean') return undefined
  return { v: REF_VERSION, id, kind, name, ordinal, ambiguous }
}

/** Clipboard / persistence projection: `@附件:name` (or `@附件:#n`). */
export function clipboardTextOf(ref: AttachmentRef, copy: Pick<AttachmentCopy, 'mention'>): string {
  return `@${copy.mention}:${ref.name === '' ? `#${ref.ordinal}` : ref.name}`
}

/**
 * Turn one menu pick into the insert outcome; undefined (= default sink) for a
 * value this source did not mint.
 */
export function insertForPick(value: unknown, copy: Pick<AttachmentCopy, 'unnamed' | 'mention'>): AttachmentInsert | undefined {
  const ref = parseRef(value)
  if (ref === undefined) return undefined
  return {
    source: ATTACHMENT_SOURCE,
    ref: JSON.stringify(ref),
    label: labelOf(ref, copy),
    appearance: 'file',
    clipboardText: clipboardTextOf(ref, copy),
  }
}

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
export function serializeRef(ref: string): string {
  const parsed = parseRef(ref)
  if (parsed === undefined) throw new Error(`${ATTACHMENT_SOURCE}: unrecognized attachment reference`)
  const kind = parsed.kind === 'image' ? 'Image' : 'File'
  const named = parsed.name === '' ? kind : `${kind} ${JSON.stringify(parsed.name)}`
  return parsed.ambiguous ? `[attachment #${parsed.ordinal}: ${named}]` : `[attachment: ${named}]`
}
