/**
 * dsh-destructive-guard —— 容器内破坏性命令的**硬确认**守卫。
 *
 * 机制（全部是上游公开 API，没有改动 DSH 本体）：
 *  1. `tools/pre-execute` waterfall（`dsh-tools/lib/index.js:3225`）里最后一道闸，
 *     返回 `{kind:'ask'}` 会经 `ToolRuntime.serviceAsk()`（同文件 3439）转成一次
 *     `ctx.approval.request()`；
 *  2. `ApprovalService.decide()`（`dsh-user-approval/lib/index.js:172`）在策略为
 *     `ask` 时走 `approval/request` waterfall，Web 端的 `dsh-client-ui-approval`
 *     会弹出确认面板（面板同时显示本插件给出的目标清单与 ui-chat 渲染的原始命令）；
 *  3. 策略为 `never` 时 `decide()` 在弹窗之前就返回 `rejected` —— 本插件在这种情况下
 *     **主动降级为硬拒绝**，并把话说清楚，绝不放行。
 *
 * 因此不存在"配置成不确认就直接执行"的状态：`ask` 会问、`never` 会拒、没有审批服务
 * 会拒、应答通道不可用会拒。破坏性命令要真正执行，必须有一次显式的用户决定。
 *
 * @module dsh-destructive-guard
 */
import { readFileSync, globSync, statSync } from 'node:fs';
import { analyzeCommand } from './destructive.js';
import { resolveEffectiveWorkdir } from './workdir.js';

export const name = 'dsh-destructive-guard';

/** 审批不可用 / 被策略挡下时给模型看的说明（模型必须停下来问人，而不是绕路）。 */
const BLOCK_HINT = '破坏性命令没有被确认，因此没有执行。请把要删的内容和原因告诉用户，由用户决定；不要改用别的工具或写法绕过这次确认。';

const KIND_LABELS = {
  rm: ['删除文件/目录（rm）', 'delete files/directories (rm)'],
  rmdir: ['删除空目录（rmdir）', 'remove empty directories (rmdir)'],
  unlink: ['删除文件（unlink）', 'unlink a file'],
  shred: ['擦除文件（shred）', 'overwrite/erase a file (shred)'],
  truncate: ['截断文件（truncate）', 'truncate a file'],
  dd: ['裸覆写（dd）', 'raw overwrite (dd)'],
  'mv-void': ['移入临时区（mv）', 'move into a temporary/void location (mv)'],
  'mv-clobber': ['覆盖已存在文件（mv）', 'overwrite an existing file (mv)'],
  'cp-clobber': ['覆盖已存在文件（cp）', 'overwrite an existing file (cp)'],
  tee: ['截断写入（tee）', 'truncating write (tee)'],
  'rsync-delete': ['同步删除（rsync --delete）', 'mirror-delete (rsync --delete)'],
  'find-delete': ['按匹配批量删除（find）', 'bulk delete by match (find)'],
  'git-clean': ['清理未跟踪文件（git clean -f）', 'delete untracked files (git clean -f)'],
  'git-reset-hard': ['丢弃工作区改动（git reset --hard）', 'discard working-tree changes (git reset --hard)'],
  'git-discard': ['丢弃工作区改动（git checkout/restore）', 'discard working-tree changes (git checkout/restore)'],
  'git-branch-delete': ['删除分支（git branch -D）', 'delete a branch (git branch -D)'],
  'git-stash-drop': ['丢弃 stash 条目', 'drop a stash entry'],
  'git-unparsed': ['git 调用无法完整识别', 'unclassified git invocation'],
  'redirect-truncate': ['重定向截断（>）', 'truncate by redirection (>)'],
  'redirect-device': ['写入裸设备（>）', 'write to a raw device (>)'],
};

/** 目标形态的两种语言标注。 */
const TARGET_LABELS = {
  dir: ['目录', 'directory'],
  file: ['文件', 'file'],
  other: ['其它类型', 'other type'],
  missing: ['当前不存在', 'does not exist right now'],
  glob: ['通配，已匹配 {n} 项', 'glob, {n} match(es)'],
  'glob-truncated': ['通配，至少 {n} 项（匹配已截断）', 'glob, at least {n} match(es) (list truncated)'],
  'glob-empty': ['通配，当前无匹配', 'glob, no match right now'],
  'glob-unknown': ['通配，数量未知', 'glob, unknown count'],
  unlisted: ['数量未知', 'unknown count'],
};

/** 关键目标最多列几个，超出只报数量。 */
const MAX_LISTED = 6;

/** 只读、有上限、避开凭据区的脚本读取；守卫不需要也不应该读敏感文件。 */
function safeReadFile(path) {
  try {
    if (path.includes('/.dsh/') || path.includes('/.ssh/') || path.includes('/.android/')) return null;
    if (/(?:\.credentials\.yaml|id_rsa|id_ed25519|\.bridge_token|\.bridge_headers)$/.test(path)) return null;
    const stats = statSync(path);
    if (!stats.isFile() || stats.size > 65536) return null;
    return readFileSync(path, 'utf8');
  } catch {
    return null;
  }
}

/** 通配展开：`**` 与受保护根一律不展开（可能扫全盘），此时按"数量未知"保守上报。 */
function safeGlob(pattern) {
  try {
    if (pattern.includes('**')) return null;
    const cut = pattern.search(/[*?[{]/);
    const prefix = (cut < 0 ? pattern : pattern.slice(0, cut)).replace(/\/+$/, '');
    if (prefix === '') return null;
    const depth = prefix.split('/').filter((part) => part !== '').length;
    if (depth < 2) return null;
    const found = globSync(pattern);
    return Array.isArray(found) ? found : null;
  } catch {
    return null;
  }
}

function probe(path) {
  try {
    const stats = statSync(path);
    if (stats.isDirectory()) return 'dir';
    if (stats.isFile()) return 'file';
    return 'other';
  } catch {
    return 'missing';
  }
}

/** 目标当前形态的短标注。 */
function targetLabel(target, language) {
  const key = target.truncated === true && target.kind === 'glob' ? 'glob-truncated' : target.kind;
  const entry = TARGET_LABELS[key] ?? ['存在性未知', 'existence unknown'];
  return entry[language].replace('{n}', String(target.count ?? 0));
}

/** 把识别结果压成"删什么、几个"的清单（审批面板 headline 不渲染换行）。 */
function describe(analysis) {
  const heads = { zh: [], en: [] };
  const listed = { zh: [], en: [] };
  for (const finding of analysis.findings) {
    const labels = KIND_LABELS[finding.kind] ?? [finding.kind, finding.kind];
    const flagsZh = [finding.recursive ? '递归' : '', finding.force ? '强制' : ''].filter((part) => part !== '').join('、');
    const flagsEn = [finding.recursive ? 'recursive' : '', finding.force ? 'force' : ''].filter((part) => part !== '').join(', ');
    heads.zh.push(labels[0] + (flagsZh === '' ? '' : `［${flagsZh}］`));
    heads.en.push(labels[1] + (flagsEn === '' ? '' : ` [${flagsEn}]`));
    for (const target of finding.targets) {
      if (listed.zh.length >= MAX_LISTED) continue;
      const shown = target.path ?? target.text;
      listed.zh.push(`${listed.zh.length + 1}. ${shown}（${targetLabel(target, 0)}）`);
      listed.en.push(`${listed.en.length + 1}. ${shown} (${targetLabel(target, 1)})`);
    }
  }
  const count = analysis.unknownTargets ? null : analysis.total;
  return {
    heads: { zh: [...new Set(heads.zh)], en: [...new Set(heads.en)] },
    listed,
    count,
    truncated: analysis.truncatedTargets === true,
  };
}

export function apply(ctx) {
  /**
   * 最后一道 pre-execute 闸：先让下游（含 Auto review 等）给出结论，
   * 下游不是 allow 就直接透传 —— 守卫只可能更严，不可能更松。
   */
  ctx.on('tools/pre-execute', async (exec, next) => {
    const downstream = await next();
    if (downstream !== undefined && downstream.kind !== 'allow') return downstream;
    if (exec.name !== 'bash') return downstream;
    const args = exec.arguments;
    const command = args !== null && typeof args === 'object' ? args.command : undefined;
    if (typeof command !== 'string' || command.trim() === '') return downstream;

    const workdir = effectiveWorkdir(ctx, exec);
    let analysis;
    try {
      analysis = analyzeCommand(command, {
        cwd: workdir.exact ? workdir.dir : undefined,
        env: process.env,
        // 目录不确定时不给探测能力：文件事实一律按"未知"保守上报，宁可多问一次也不猜。
        probe: workdir.exact ? probe : null,
        glob: workdir.exact ? safeGlob : null,
        readFile: safeReadFile,
      });
    } catch (error) {
      // 识别器异常时按粗粒度启发式兜底：宁可信其有，不能因为自身 bug 静默放行。
      ctx.logger?.warn?.(`destructive-guard: 识别失败，改用兜底判据：${error instanceof Error ? error.message : String(error)}`);
      if (!/\b(?:rm|rmdir|unlink|shred|truncate|dd|git\s+clean)\b/.test(command)) return downstream;
      return blocked(ctx, exec, 'DSHA 删除守卫识别失败，命令里出现了删除类程序名，按保守处理拒绝本次执行。', 'DSHA destructive-command guard could not classify this bash command; it names a deletion program, so it was blocked conservatively.');
    }
    if (!analysis.destructive) return downstream;

    const policy = approvalPolicy(ctx, exec);
    const { heads, listed, count, truncated } = describe(analysis);
    const notesZh = [...new Set(analysis.findings.map((finding) => finding.note).filter((note) => note !== ''))];
    const notesEn = [...new Set(analysis.findings.map((finding) => finding.noteEn).filter((note) => note !== ''))];
    const shownZh = listed.zh.length === 0 ? '目标无法预先枚举' : listed.zh.join('；');
    const shownEn = listed.en.length === 0 ? 'not enumerable' : listed.en.join('; ');
    const countZh = count === null
      ? (truncated ? `至少 ${analysis.total} 个目标（匹配被截断，实际可能更多）` : '数量未知的目标')
      : `${count} 个目标`;
    const countEn = count === null
      ? (truncated ? `at least ${analysis.total} target(s) (the match list was truncated, so there may be more)` : 'an unknown number of targets')
      : `${count} target(s)`;

    const zh = `破坏性命令确认：${heads.zh.join(' + ')} 将影响 ${countZh}。`
      + `｜目标：${shownZh}${analysis.unknownTargets && listed.zh.length > 0 ? '（还有未能枚举的部分）' : ''}。`
      + (notesZh.length === 0 ? '' : `｜注意：${notesZh.join('；')}。`)
      + '｜原始命令见下方（严重度 ' + analysis.severity + '）。请核对无误后再选择"仅本次允许"；不确定就拒绝。';
    const en = `DSHA destructive-command confirmation: ${heads.en.join(' + ')} affects ${countEn}.`
      + ` Targets: ${shownEn}${analysis.unknownTargets && listed.en.length > 0 ? ' (plus targets that cannot be enumerated)' : ''}.`
      + (notesEn.length === 0 ? '' : ` Note: ${notesEn.join('; ')}.`)
      + ` Severity ${analysis.severity}. The exact command is shown below — approve once only if those targets are exactly what you intend to delete.`;

    if (policy === 'never') {
      return blocked(
        ctx,
        exec,
        `DSHA 已阻止这条破坏性命令：本会话的审批策略是 "never"，没有可用的确认通道，删除不会被自动授权。${zh}${BLOCK_HINT}`,
        `DSHA destructive-command guard: this session's approval policy is "never", so no confirmation channel exists and the destructive command was denied. ${en}`,
      );
    }
    return {
      kind: 'ask',
      reason: truncate(en, 900),
      displayReason: { en: truncate(en, 900), zh: truncate(zh, 900) },
    };
  });
}

/** 没有审批服务可用时不能弹窗，只能拒绝。 */
function blocked(ctx, exec, zh, en) {
  ctx.logger?.info?.(`destructive-guard: 拒绝 ${String(exec.callId ?? '')} —— ${truncate(en, 200)}`);
  return {
    kind: 'deny',
    reason: truncate(`${zh}`, 900),
    info: { name: 'DSHA_DESTRUCTIVE_COMMAND_BLOCKED', code: 'DSHA_DESTRUCTIVE_BLOCKED' },
  };
}

/** 会话的有效审批策略；取不到就按"会问"处理，让宿主的 fail-closed 路径接手。 */
function approvalPolicy(ctx, exec) {
  const approval = ctx.get('approval');
  const session = exec.agent?.session;
  if (approval === undefined || session === undefined || typeof approval.effectivePolicy !== 'function') return 'ask';
  try {
    return approval.effectivePolicy(session);
  } catch {
    return 'ask';
  }
}

/**
 * 当前沙箱策略（与 bash 执行器同一套服务）；拿不到就返回 undefined，不猜。
 * `workspaceRoot` 会让执行器把 workdir 解析到同一个受限根上，确认目标必须跟着走。
 */
function standingPolicy(ctx, exec) {
  const service = typeof ctx.get === 'function' ? ctx.get('sandboxPolicy') : undefined;
  if (service === undefined || typeof service.resolve !== 'function') return undefined;
  try {
    return service.resolve(exec.agent === undefined ? {} : { session: exec.agent.session });
  } catch {
    return undefined;
  }
}

/**
 * 本次 bash 调用真正生效的工作目录：`arguments.workdir` 优先（相对值按会话 cwd 解析），
 * 其次沙箱 workspaceRoot / 会话 cwd，最后才是执行器给的 cwd。
 * 解析不出来时返回 `exact: false`，调用方按"事实未知"收紧处理。
 */
function effectiveWorkdir(ctx, exec) {
  const policy = standingPolicy(ctx, exec);
  return resolveEffectiveWorkdir({
    workdir: exec.arguments?.workdir,
    workspaceRoot: typeof policy?.workspaceRoot === 'string' ? policy.workspaceRoot : undefined,
    sessionCwd: exec.agent?.session?.header?.cwd,
    execCwd: typeof exec.cwd === 'string' ? exec.cwd : undefined,
  });
}

function truncate(text, limit) {
  return text.length <= limit ? text : text.slice(0, limit - 1) + '…';
}
