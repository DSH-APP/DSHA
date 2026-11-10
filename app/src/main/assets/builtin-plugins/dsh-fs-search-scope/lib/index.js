/**
 * DSHA 内置修复插件「检索范围」：上游 glob（dsh-tool-fs-search）固定 `rg --files --no-ignore --hidden`，
 * 只排除 .git 等版本库目录。多会话仓库里 `.agents/worktrees/` 下的几十个工作树会被整棵展开，
 * 从仓库根检索一次就是十几万条结果并逐个 stat（--sort=modified），足以拖死会话。
 *
 * 本插件在 host 层 `tools/execute` 上接管上游 glob 的这一次执行：参数校验、ripgrep 调用、结果形状
 * 全部复用上游导出函数，只在 argv 中追加剪枝用的排除 glob：
 *   - 任意深度的 `.agents/worktrees` 目录；
 *   - 检索根所在仓库 `git worktree list` 列出的、位于检索根之内的链接工作树（不含主工作树）。
 * 检索根本身位于 `.agents/worktrees` 内（显式传 path 指向某个工作树或其上层）时不剪枝，行为与上游一致。
 * 仍保留「含忽略文件、含隐藏文件」语义；结果上限、渲染、溢出保存都由上游已注册的 glob 定义负责。
 *
 * DSHA 运行时给 dsh-tools 打了必经策略补丁（DSHA_REQUIRED_POLICY_V1）：工具体执行前须过
 * verifyGlobalConsent / resolveExecution（rc5）或 executable（0.2.2）/ verifyDispatch。本插件接管执行时按同一顺序调用这三步，
 * 不跳过、不放宽；策略模块加载失败、上游形状不认识或无需剪枝时，一律交回 next() 走原工具。
 *
 * 停用或删除本插件（取消选中 bundle）即恢复上游 glob 原样。临时措施：撤除条件见 docs/fs-search-scope-plugin.md。
 */
import { execFile } from 'node:child_process';
import { readFileSync, realpathSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { isAbsolute, relative, resolve, sep } from 'node:path';

export const name = 'dsh-fs-search-scope';

/** 上游 glob 描述的固定开头：用于确认接管对象确实是 dsh-tool-fs-search 注册的 glob。 */
export const UPSTREAM_GLOB_DESCRIPTION = 'Find files, not directories, whose paths match a glob pattern';
/** 剪枝的固定区域：多会话约定的工作树目录。检索标记：DSHA_FS_SEARCH_SCOPE_V1 */
export const WORKTREE_ZONE = ['.agents', 'worktrees'];
/** `git worktree list` 的时限与输出上限；失败只是少剪一部分，不影响检索本身。 */
export const GIT_TIMEOUT_MS = 3000;
export const GIT_MAX_BUFFER = 1 << 20;
/** 系统提示里的说明，只在 glob 可用时出现。 */
export const PROMPT_NOTE = 'The glob tool skips nested git worktrees (.agents/worktrees and linked worktrees inside the search root); to search one, pass its directory as path.';

function segments(path) {
  return String(path).split(/[\\/]+/).filter(Boolean);
}

function inside(parent, child) {
  const rel = relative(parent, child);
  return rel !== '' && rel !== '..' && !rel.startsWith('..' + sep) && !isAbsolute(rel);
}

/** 路径是否位于（或就是）某个 `.agents/worktrees` 目录。 */
export function inWorktreeZone(path) {
  const parts = segments(path);
  for (let i = 0; i + 1 < parts.length; i++) if (parts[i] === WORKTREE_ZONE[0] && parts[i + 1] === WORKTREE_ZONE[1]) return true;
  return false;
}

/** 转义 glob 元字符，路径按字面匹配。 */
export function escapeGlob(path) {
  return String(path).replace(/[\\*?[\]{}!]/g, ch => '\\' + ch);
}

/** 解析 `git worktree list --porcelain -z`：返回按出现顺序的工作树路径，第一条是主工作树。 */
export function parseWorktreeList(output) {
  const paths = [];
  for (const field of String(output).split('\0')) if (field.startsWith('worktree ')) paths.push(field.slice('worktree '.length));
  return paths;
}

/**
 * 计算一次 glob 要追加的排除 glob；返回空数组表示无需接管。
 * @param workdir - ripgrep 的 cwd（会话工作目录，绝对路径）。
 * @param path - 模型传入的 path（可缺省、可相对）。
 * @param linked - 检索根所在仓库的链接工作树真实路径（不含主工作树）。
 * @param rootReal - 检索根的真实路径；用于把 git 给出的真实路径映射回检索根的字面路径。
 */
export function pruneGlobs({ workdir, path, linked = [], rootReal }) {
  const root = resolve(workdir, path ?? '.');
  if (inWorktreeZone(root)) return [];
  const globs = new Set(['!**/' + WORKTREE_ZONE.join('/')]);
  const realRoot = rootReal ?? root;
  for (const real of linked) {
    if (!isAbsolute(real)) continue;
    let lexical;
    if (inside(realRoot, real)) lexical = resolve(root, relative(realRoot, real));
    else if (inside(root, real)) lexical = real;
    else continue;
    if (inWorktreeZone(relative(root, lexical))) continue; // 已被固定规则覆盖
    // ripgrep 用「去掉 cwd 前缀后的遍历路径」匹配：根在 cwd 内时用相对形式，否则用 **/ 绝对形式。
    const fromCwd = relative(workdir, lexical);
    if (fromCwd && !isAbsolute(fromCwd)) globs.add('!' + escapeGlob(fromCwd.split(sep).join('/')));
    globs.add('!**' + escapeGlob(lexical.split(sep).join('/')));
  }
  return [...globs];
}

/** 把排除 glob 插到 `--` 之前（没有 `--` 就追加在末尾），每条一个 `--glob=` 参数。 */
export function withExcludes(argv, globs) {
  const flags = globs.map(glob => '--glob=' + glob);
  const cut = argv.indexOf('--');
  return cut === -1 ? [...argv, ...flags] : [...argv.slice(0, cut), ...flags, ...argv.slice(cut)];
}

/** 列出 root 所在仓库的链接工作树（真实路径）；不在仓库内或 git 不可用时返回空数组。 */
export function listLinkedWorktrees(root, signal) {
  return new Promise(done => {
    try {
      execFile('git', ['-C', root, 'worktree', 'list', '--porcelain', '-z'], { timeout: GIT_TIMEOUT_MS, maxBuffer: GIT_MAX_BUFFER, signal, windowsHide: true }, (error, stdout) => {
        if (error) return done([]);
        done(parseWorktreeList(stdout).slice(1).map(path => { try { return realpathSync(path); } catch { return path; } }));
      });
    } catch {
      done([]);
    }
  });
}

/** 与 dsh-tools 的 toolErrorResult 同形的失败结果。 */
export function errorResult(error) {
  const message = error instanceof Error ? error.message : String(error);
  const code = typeof error?.code === 'string' ? error.code : undefined;
  return { content: [{ type: 'text', text: `Error: ${message}` }], isError: true, error: { message, ...code ? { info: { name: error.name, code } } : {} } };
}

/**
 * 运行时的 dsh-tools 是否带 DSHA 必经策略补丁（带补丁时接管执行必须先过同样的三步）。
 * rc5 把入口放在 index.js，0.2.2 把执行管线拆到 pipeline.js；两者都要带标记。
 */
function toolsPatched() {
  try {
    const indexUrl = import.meta.resolve('@deepseek-ai/dsh-tools');
    const sources = [readFileSync(fileURLToPath(indexUrl), 'utf8')];
    try {
      const pipelineUrl = import.meta.resolve('@deepseek-ai/dsh-tools/pipeline');
      sources.push(readFileSync(fileURLToPath(pipelineUrl), 'utf8'));
    } catch {
      // rc5 不导出独立 pipeline，index.js 是完整执行入口。
    }
    return sources.some(
      source => source.includes('DSHA_REQUIRED_POLICY_V1') && source.includes('verifyDispatch'));
  } catch {
    return undefined;
  }
}

function isUpstreamGlob(tool) {
  return tool?.name === 'glob' && typeof tool.execute === 'function'
    && String(tool.description ?? '').startsWith(UPSTREAM_GLOB_DESCRIPTION)
    && tool.output?.schema?.properties?.root !== undefined && tool.output?.schema?.properties?.paths !== undefined;
}

/** Resolve the executable definition across the rc5 and 0.2.2 tool registries. */
export function resolveTool(tools, exec) {
  if (typeof tools?.resolveExecution === 'function')
    return tools.resolveExecution(exec.name, exec.agent, exec.parent !== undefined);
  if (typeof tools?.executable === 'function')
    return tools.executable(exec.name, exec.agent, exec.toolSet);
  return undefined;
}

function isDirectory(path) {
  try { return statSync(path).isDirectory(); } catch { return false; }
}

/**
 * @param deps - 测试注入；生产由 apply 自行加载上游模块与策略模块。
 */
export function createHandler(ctx, deps) {
  const { search, policy, listLinked = listLinkedWorktrees } = deps;
  return async (exec, next) => {
    if (exec?.name !== 'glob' || exec.parent !== undefined || exec.agent === undefined) return next();
    const tools = ctx.get('tools'), workingDirectory = ctx.get('workingDirectory'), subprocess = ctx.get('subprocess');
    if (!tools || !workingDirectory || !subprocess) return next();
    let input, workdir, globs;
    try {
      input = search.parseGlobArgs(exec.arguments);
      workdir = await workingDirectory.ensure(exec.agent, exec.signal);
      const root = resolve(workdir, input.path ?? '.');
      if (!isDirectory(root) || inWorktreeZone(root)) return next();
      let rootReal = root;
      try { rootReal = realpathSync(root); } catch {}
      globs = pruneGlobs({ workdir, path: input.path, rootReal, linked: await listLinked(root, exec.signal) });
    } catch {
      return next(); // 参数错误、工作目录不可用等交给上游给出原报错
    }
    if (!globs.length) return next();
    try {
      // 与带补丁的 dispatchToolBody 同序：全局同意 → 解析可执行定义 → 派发核验（一次性）。
      if (policy) await policy.verifyGlobalConsent(exec);
      const tool = resolveTool(tools, exec);
      if (!isUpstreamGlob(tool)) return next();
      if (policy) policy.verifyDispatch(tools, exec, tool);
    } catch (error) {
      return errorResult(error);
    }
    try {
      const argv = withExcludes(search.buildGlobCommand(input), globs);
      const run = await search.runRipgrep({ subprocess, workingDirectory }, exec, 'glob', argv, search.RAW_OUTPUT_MAX_BYTES, search.SEARCH_GRACE_MS, search.SEARCH_STDERR_MAX_BYTES);
      const root = input.path === undefined ? '.' : search.toWorkdirRelative(input.path, run.workdir);
      const paths = run.noMatches ? [] : run.stdout.split('\n').filter(Boolean).map(line => search.toWorkdirRelative(line, run.workdir));
      return { isError: false, value: { root, paths } };
    } catch (error) {
      return errorResult(error);
    }
  };
}

export async function apply(ctx) {
  let search, policy;
  try {
    search = await import('@deepseek-ai/dsh-tool-fs-search');
    for (const key of ['parseGlobArgs', 'buildGlobCommand', 'runRipgrep', 'toWorkdirRelative']) if (typeof search[key] !== 'function') throw Error(key);
  } catch (error) {
    ctx.logger?.warn?.(`${name}: upstream fs-search unavailable (${error?.message ?? error}); glob left unchanged`);
    return;
  }
  const patched = toolsPatched();
  if (patched !== false) {
    try {
      policy = await import('dsha-destructive-policy');
      if (typeof policy.verifyGlobalConsent !== 'function' || typeof policy.verifyDispatch !== 'function') throw Error('policy exports');
    } catch (error) {
      // 必经策略存在却加载不到：不接管，保持上游原样，绝不绕过。
      ctx.logger?.warn?.(`${name}: DSHA policy unavailable (${error?.message ?? error}); glob left unchanged`);
      return;
    }
  }
  ctx.on('tools/execute', createHandler(ctx, { search, policy }));
  ctx.inject(['systemPrompt'], promptCtx => {
    promptCtx.systemPrompt.section({
      name: 'dsha:glob-scope',
      order: promptCtx.systemPrompt.getSectionOrder?.('TOOL_GLOB') ?? 150,
      text: ({ scope } = {}) => ctx.get('tools')?.get?.('glob', scope) === undefined ? '' : PROMPT_NOTE,
    });
  });
}
