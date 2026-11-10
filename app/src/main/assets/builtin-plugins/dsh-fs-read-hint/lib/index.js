/**
 * DSHA 内置修复插件「读取提示」：上游 fs-observation-policy 以 FS_NOT_OBSERVED 拒绝修改时，
 * 若本会话用 read 工具读过同名的另一个路径（典型：读了主检出、改的是 worktree 副本），
 * 在报错末尾追加该路径，帮模型一次读对文件。
 *
 * 只改模型可见的报错文字：不放行任何修改、不自动读取、不重试，错误码与原因保持原样。
 * 未找到同名候选、报错形状不认识或插件停用时，结果与上游完全一致。
 *
 * 临时补丁：上游反馈 https://github.com/deepseek-ai/deepseek-harness/discussions/11977 。
 * 撤除条件与追踪见 docs/fs-read-hint-plugin.md。
 */
export const name = 'dsh-fs-read-hint'

/** 只处理会触发观察门禁的文件修改工具。 */
export const TOOLS = Object.freeze(new Set(['edit', 'write', 'str_replace_editor']))
/** 每个会话最多记住的已读路径数；超出时淘汰最早的。 */
export const MAX_OBSERVED = 4096
/** 报错里最多列出的候选路径数。 */
export const MAX_HINTS = 2
/** 追加提示的固定开头；同时用作「已追加过」判定，避免重复包装。检索标记：DSHA_FS_READ_HINT_V1 */
export const HINT_PREFIX = ' — note: this session read '

const separators = /[\\/]+/

function segments(path) {
  return String(path).split(separators).filter(Boolean)
}

/** 末段文件名；空路径返回空串。 */
export function basename(path) {
  const parts = segments(path)
  return parts.length ? parts[parts.length - 1] : ''
}

/** 两个路径从末尾起相同的段数（含文件名）。 */
export function commonSuffix(a, b) {
  const x = segments(a), y = segments(b)
  let n = 0
  while (n < x.length && n < y.length && x[x.length - 1 - n] === y[y.length - 1 - n]) n++
  return n
}

/** 从上游报错文字里取第一个带引号的路径；两种已知形状都把目标路径放在第一对引号里。 */
export function quotedPath(message) {
  const match = /"([^"\n]+)"/.exec(String(message))
  return match ? match[1] : undefined
}

/**
 * 从已读记录中挑同名、但路径不同的候选：公共后缀越长越靠前，同分时最近读过的优先。
 * @param observed - 按读取先后排列的 displayPath 列表（Map 的键序）。
 * @param target - 被拒绝修改的目标路径。
 */
export function candidates(observed, target, limit = MAX_HINTS) {
  const file = basename(target)
  if (!file) return []
  const ranked = []
  let order = 0
  for (const path of observed) {
    order++
    if (path === target || basename(path) !== file) continue
    ranked.push({ path, score: commonSuffix(path, target), order })
  }
  ranked.sort((a, b) => b.score - a.score || b.order - a.order)
  return ranked.slice(0, limit).map(item => item.path)
}

/** 追加到上游报错之后的提示句。 */
export function hintText(paths) {
  const quoted = paths.map(path => `"${path}"`).join(' / ')
  return `${HINT_PREFIX}${quoted}, a different path with the same file name; read the exact path above, then retry`
}

/** 会话对象：与上游观察策略取 owner 的方式一致。 */
function ownerOf(actor) {
  return actor?.agent?.session
}

function targetPath(exec, message) {
  const fromMessage = quotedPath(message)
  if (fromMessage) return fromMessage
  const args = exec?.arguments
  const raw = args?.file_path ?? args?.path
  return typeof raw === 'string' ? raw : undefined
}

/**
 * 只认上游 dsh-tools 的错误结果形状：isError、error.info.code、单段 `Error: <message>` 文本。
 * 形状不符就原样返回，绝不猜测着改写。
 */
export function rewrite(result, exec, observed) {
  if (!result || result.isError !== true) return result
  const error = result.error
  if (error?.info?.code !== 'FS_NOT_OBSERVED' || typeof error.message !== 'string') return result
  if (error.message.includes(HINT_PREFIX)) return result
  const content = result.content
  if (!Array.isArray(content) || content.length !== 1 || content[0]?.type !== 'text') return result
  if (content[0].text !== `Error: ${error.message}`) return result
  const target = targetPath(exec, error.message)
  if (!target || !observed) return result
  const paths = candidates(observed.keys(), target)
  if (!paths.length) return result
  const message = error.message + hintText(paths)
  return { ...result, content: [{ type: 'text', text: `Error: ${message}` }], error: { ...error, message } }
}

export function apply(ctx) {
  /** owner(session) → Map<displayPath, true>，键序即读取先后。 */
  let observed = new WeakMap()
  ctx.effect(() => () => { observed = new WeakMap() }, 'dsh-fs-read-hint observed-path teardown')

  ctx.on('fs/observed', (target, observation, actor) => {
    const owner = ownerOf(actor)
    const path = target?.displayPath
    if (!owner || typeof path !== 'string' || observation?.kind !== 'present') return
    let paths = observed.get(owner)
    if (!paths) observed.set(owner, paths = new Map())
    paths.delete(path)
    paths.set(path, true)
    if (paths.size > MAX_OBSERVED) paths.delete(paths.keys().next().value)
  })

  ctx.on('tools/execute', async (exec, next) => {
    if (!TOOLS.has(exec?.name)) return next()
    const result = await next()
    try {
      return rewrite(result, exec, observed.get(ownerOf(exec)))
    } catch {
      return result
    }
  })
}
