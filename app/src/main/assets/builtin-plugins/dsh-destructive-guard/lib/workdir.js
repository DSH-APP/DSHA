/**
 * 会话有效工作目录：与安装版 bash 执行器 `dsh-tool-bash` 的 `resolveWorkdir()` 同一套语义。
 *
 * 为什么单独成文件：确认面板上的目标路径必须与命令真正执行的目录一致，而"命令真正跑在哪个
 * 目录"由宿主决定 —— 工具参数 `arguments.workdir` 优先（相对值按会话 cwd 解析），其次是
 * 沙箱策略的 `workspaceRoot` / 会话 cwd，最后才是执行器自己的默认值。早先的实现只看会话
 * cwd，于是 `workdir` 指向别的目录时，面板会把确认目标指到错误的路径，甚至把"覆盖已有文件"
 * 误判成"新建文件"而直接放行。
 *
 * 这段判断是纯函数（零依赖、零 I/O），便于用假 exec 直接单测；取不到目录时返回
 * `exact: false`，由调用方收紧处理，绝不猜一个目录出来。
 *
 * @module dsh-destructive-guard/workdir
 */

/** POSIX 绝对路径判断（容器里只有 `/`）。 */
export function isAbsolutePath(value) {
  return typeof value === 'string' && value.startsWith('/');
}

function firstNonEmpty(...values) {
  for (const value of values) if (typeof value === 'string' && value !== '') return value;
  return undefined;
}

/** 把相对目录接到基准目录后面；基准为根时不产生 `//`。 */
export function joinDirectory(base, relative, separator = '/') {
  if (isAbsolutePath(relative)) return relative;
  const tail = relative.replace(/^\/+/, '');
  if (base === separator || base === '') return separator + tail;
  return base.replace(/\/+$/, '') + separator + tail;
}

/**
 * 解析本次 bash 调用真正生效的工作目录。
 *
 * @param {object} input
 * @param {string} [input.workdir] 工具参数 `arguments.workdir`（模型显式指定）。
 * @param {string} [input.workspaceRoot] 当前沙箱策略的 workspaceRoot。
 * @param {string} [input.sessionCwd] 会话 header 里的 cwd。
 * @param {string} [input.execCwd] 执行器给出的 cwd（最后兜底）。
 * @param {string} [input.separator]
 * @returns {{dir: string|null, exact: boolean}} `exact: false` 表示无法确定，调用方必须收紧。
 */
export function resolveEffectiveWorkdir(input = {}) {
  const separator = input.separator === undefined ? '/' : input.separator;
  const base = firstNonEmpty(input.workspaceRoot, input.sessionCwd);
  const explicit = firstNonEmpty(input.workdir);
  if (explicit !== undefined) {
    if (isAbsolutePath(explicit)) return { dir: explicit, exact: true };
    if (base !== undefined) return { dir: joinDirectory(base, explicit, separator), exact: true };
    return { dir: null, exact: false };
  }
  if (base !== undefined) return { dir: base, exact: true };
  const fallback = firstNonEmpty(input.execCwd);
  if (fallback !== undefined) return { dir: fallback, exact: true };
  return { dir: null, exact: false };
}
