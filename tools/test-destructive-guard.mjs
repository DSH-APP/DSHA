#!/usr/bin/env node
// DSHA 破坏性命令守卫的自测：识别纯逻辑 + 闸门行为。
//
// 为什么用 node 而不是 JUnit：要被测的逻辑活在 DSH 宿主进程里（cordis 插件），
// 只有 JS 版本才是真实运行的那一份。用 Java 再写一遍只会造出第二份真相 —— 测过了
// 也不代表线上生效。这里用自建桩（假 FS + 假 ctx）真跑，不依赖任何构建系统。
//
// 跑法：node tools/test-destructive-guard.mjs
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { analyzeCommand } from '../app/src/main/assets/builtin-plugins/dsh-destructive-guard/lib/destructive.js';
import * as guard from '../app/src/main/assets/builtin-plugins/dsh-destructive-guard/lib/index.js';

const CWD = '/root/work';

/** 假文件系统：路径 → 'file' | 'dir'。 */
const FILES = new Map([
  ['/root', 'dir'], ['/root/work', 'dir'], ['/root/work/build', 'dir'], ['/root/work/dist', 'dir'],
  ['/root/work/a', 'file'], ['/root/work/b', 'file'], ['/root/work/dir', 'dir'], ['/root/work/out.txt', 'file'],
  ['/root/.dsh', 'dir'], ['/root/.dsh/sessions', 'dir'], ['/root/.bashrc', 'file'],
  ['/etc', 'dir'], ['/etc/motd', 'file'],
  ['/tmp', 'dir'], ['/tmp/a.txt', 'file'], ['/tmp/log', 'file'], ['/tmp/emptydir', 'dir'],
  ['/tmp/secret', 'file'], ['/tmp/img', 'file'], ['/tmp/build', 'dir'], ['/tmp/build/x', 'file'],
  ['/tmp/cleanup.sh', 'file'], ['/tmp/evil.sh', 'file'],
]);
FILES.set('/tmp/build/x', 'file');
FILES.set('/tmp/build/y', 'file');

const probe = (path) => FILES.get(path) ?? 'missing';

/**
 * 只支持 `*`/`?` 的迷你展开，够用例用。
 * 与插件里的 safeGlob 同契约：展开不了就返回 null（不是空数组），
 * 让识别器按"数量未知"保守上报。
 */
function glob(pattern) {
  if (pattern.includes('**')) return null;
  const source = pattern.split('').map((ch) => {
    if (ch === '*') return '[^/]*';
    if (ch === '?') return '[^/]';
    return ch.replace(/[.+^$()|[\]\\]/g, '\\$&');
  }).join('');
  const rx = new RegExp('^' + source + '$');
  return [...FILES.keys()].filter((path) => rx.test(path));
}

const analyze = (command, extra = {}) => analyzeCommand(command, { cwd: CWD, probe, glob, readFile: readFile, ...extra });
const readFile = (path) => (path.endsWith('/cleanup.sh') ? '#!/bin/sh\nrm -rf /tmp/build\n' : path === '/tmp/evil.sh' ? 'rm -rf /\n' : null);

// #region 必抓用例：期望 destructive=true

const MUST_CATCH = [
  // —— rm 的正写与各种写法 ——
  ['rm -rf /root/work/build', 'rm 递归强制删除目录', 'rm'],
  ['rm -r /root/work/build', 'rm 递归', 'rm'],
  ['rm -f /tmp/a.txt', 'rm 强制删文件', 'rm'],
  ['rm /tmp/a.txt', 'rm 裸删', 'rm'],
  ['rm --recursive --force /root/work/build', 'rm 长选项', 'rm'],
  ['rm --recursive /root/work/build', 'rm 长选项递归', 'rm'],
  ['rm -rf "/tmp/with space"', 'rm 带空格的引号路径', 'rm'],
  ['rm -rf /', 'rm -rf / 一票 critical', 'rm'],
  ['rm -rf /root', 'rm -rf 家目录', 'rm'],
  ['rm -rf /root/.dsh', 'rm -rf DSH_HOME', 'rm'],
  ['rm -rf /root/.dsh/sessions', 'rm -rf 会话目录', 'rm'],
  ['rm -rf ~/work', 'rm -rf 波浪号家目录', 'rm'],
  ['rm -rf $HOME/work', 'rm -rf $HOME', 'rm'],
  ['rm -rf .', 'rm -rf 当前目录', 'rm'],
  ['rm -rf *', 'rm -rf 通配全删', 'rm'],
  ['rm -rf /tmp/build/*', 'rm -rf 通配子项', 'rm'],
  ['rm -rf /tmp/build/../build', 'rm -rf 带 .. 的路径', 'rm'],
  ['rm -rf --no-preserve-root /', 'rm 显式 --no-preserve-root', 'rm'],
  ['rm -rf /tmp/does-not-exist', 'rm 目标不存在（幻觉形态）', 'rm'],
  // —— 程序名混淆/间接写法 ——
  ['/bin/rm -rf /tmp/build', '绝对路径 rm', 'rm'],
  ['$(which rm) -rf /tmp/build', '$(which rm)', 'rm'],
  ['`command -v rm` -rf /tmp/build', '反引号 command -v rm', 'rm'],
  ['$(type -p rm) -rf /tmp/build', '$(type -p rm)', 'rm'],
  ['"r""m" -rf /tmp/build', '相邻引号拼成 rm', 'rm'],
  ["$'\\x72\\x6d' -rf /tmp/build", 'ANSI-C 转义 rm', 'rm'],
  ['r=rm; $r -rf /tmp/build', '变量保存程序名', 'rm'],
  ['RM=/bin/rm; $RM -rf /tmp/build', '变量保存绝对路径', 'rm'],
  ["alias wipe='rm -rf'; wipe /tmp/build", 'alias 定义后使用', 'rm'],
  // —— 包装器 ——
  ['sudo rm -rf /tmp/build', 'sudo 包装', 'rm'],
  ['env rm -rf /tmp/build', 'env 包装', 'rm'],
  ['env -i FOO=1 rm -rf /tmp/build', 'env -i 与变量赋值', 'rm'],
  ['timeout 5 rm -rf /tmp/build', 'timeout 包装', 'rm'],
  ['nice -n 5 rm -rf /tmp/build', 'nice 包装', 'rm'],
  ['busybox rm -rf /tmp/build', 'busybox applet', 'rm'],
  ['command rm -rf /tmp/build', 'command 包装', 'rm'],
  // —— 管道 / xargs / 子 shell / 脚本 ——
  ['find /tmp -name "*.log" | xargs rm -f', 'find | xargs rm', 'rm'],
  ['find /tmp -print0 | xargs -0 rm -rf', 'xargs -0 rm（目标来自 stdin）', 'rm'],
  ['xargs -0 rm -rf', '纯 xargs rm', 'rm'],
  ["sh -c 'rm -rf /tmp/build'", 'sh -c', 'rm'],
  ['bash -c "rm -rf /tmp/build"', 'bash -c', 'rm'],
  ['/bin/sh -c "rm -rf /tmp/build"', '绝对路径 sh -c', 'rm'],
  ['busybox sh -c "rm -rf /tmp/build"', 'busybox sh -c', 'rm'],
  ['bash /tmp/cleanup.sh', '脚本文件内容里的 rm', 'rm'],
  ['sh /tmp/cleanup.sh', 'sh 执行脚本文件', 'rm'],
  ['./cleanup.sh', '直接执行 .sh', null],
  ['echo $(rm -rf /tmp/build)', '命令替换里的 rm', 'rm'],
  ['A=$(rm -rf /root/work/build); echo $A', '赋值里嵌命令替换', 'rm'],
  // —— 其它删除类程序 ——
  ['rmdir /tmp/emptydir', 'rmdir', 'rmdir'],
  ['unlink /tmp/a.txt', 'unlink', 'unlink'],
  ['shred /tmp/secret', 'shred', 'shred'],
  ['shred -u /tmp/secret', 'shred -u', 'shred'],
  ['truncate -s 0 /tmp/log', 'truncate -s 0', 'truncate'],
  ['truncate --size=0 /tmp/log', 'truncate --size=0', 'truncate'],
  ['truncate -s0 /tmp/log', 'truncate -s0', 'truncate'],
  ['dd if=/dev/zero of=/dev/sda', 'dd 写裸设备', 'dd'],
  ['dd if=/dev/urandom of=/tmp/img', 'dd 覆写已存在文件', 'dd'],
  // —— find ——
  ['find . -delete', 'find -delete', 'find-delete'],
  ['find /tmp -name "*.tmp" -delete', 'find -name -delete', 'find-delete'],
  ['find /tmp -type f -exec rm -f {} +', 'find -exec rm + 结尾', 'rm'],
  ['find /tmp -exec shred -u {} \\;', 'find -exec shred ; 结尾', 'shred'],
  // —— git ——
  ['git clean -fdx', 'git clean -fdx', 'git-clean'],
  ['git clean -fd', 'git clean -fd', 'git-clean'],
  ['git clean --force', 'git clean --force', 'git-clean'],
  ['git reset --hard', 'git reset --hard', 'git-reset-hard'],
  ['git checkout -- .', 'git checkout -- .', 'git-discard'],
  ['git restore .', 'git restore .', 'git-discard'],
  ['git branch -D feature', 'git branch -D', 'git-branch-delete'],
  ['git stash clear', 'git stash clear', 'git-stash-drop'],
  // —— 重定向截断（只对已存在目标） ——
  ['cat > /etc/motd', '> 覆盖已存在文件', 'redirect-truncate'],
  ['echo x > /root/.bashrc', '> 覆盖 .bashrc', 'redirect-truncate'],
  [': > /tmp/log', '> 截断已有日志', 'redirect-truncate'],
  ['printf "" >| /tmp/log', '>| 截断已有日志', 'redirect-truncate'],
  ['echo x &> /tmp/log', '&> 覆盖已有文件', 'redirect-truncate'],
  // —— mv / cp / tee / rsync ——
  ['mv /root/work/a /tmp', 'mv 到临时区', 'mv-void'],
  ['mv /root/work/a /root/work/b', 'mv 覆盖已存在文件', 'mv-clobber'],
  ['cp /root/work/a /root/work/b', 'cp 覆盖已存在文件', 'cp-clobber'],
  ['echo hi | tee /root/work/out.txt', 'tee 截断已有文件', 'tee'],
  ['rsync -a --delete /src/ /root/work/', 'rsync --delete', 'rsync-delete'],
];

// #endregion

// #region 不得误报：期望 destructive=false

const MUST_NOT_FLAG = [
  ['ls -la', '列目录'],
  ['echo hello', '纯输出'],
  ['cat /etc/hosts', '读文件'],
  ['rm --help', '看帮助（没有操作数）'],
  ['rm -rf', '没有操作数'],
  ['git status', 'git 查询'],
  ['git clean -n', 'git clean 干跑'],
  ['git clean --dry-run', 'git clean 干跑长选项'],
  ['find . -name "*.log"', 'find 只查不删'],
  ['find /tmp -type f -print', 'find -print'],
  ['mkdir -p /tmp/newdir', '建目录'],
  ['echo data > /tmp/newfile', '> 新建文件不算破坏'],
  ['cat > /tmp/newfile', 'cat > 新建文件'],
  ['echo x >> /tmp/log', '>> 追加'],
  ['echo ok > /dev/null', '> /dev/null'],
  ['cp -r /root/work/a /root/work/dir', 'cp 到目录不覆盖'],
  ['mv /root/work/a /root/work/dir', 'mv 到目录'],
  ['tee -a /root/work/out.txt', 'tee -a 追加'],
  ['truncate -s 100 /tmp/log', 'truncate 扩容'],
  ['truncate -s 0 /tmp/newlog', 'truncate 新建空文件'],
  ['shred /tmp/missing-file', 'shred 目标不存在'],
  ['rmdir /tmp/does-not-exist', 'rmdir 目标不存在'],
  ['dd if=/dev/zero of=/tmp/newimg', 'dd 新建文件'],
  ['dd if=/tmp/a.txt of=/dev/null', 'dd 写 /dev/null'],
  ['grep -rn "rm -rf /" /tmp/log', '把 rm 当搜索词'],
  ['echo "rm -rf /"', '把 rm 当字符串输出'],
  ['echo "rm -rf /" > /tmp/newfile', '字符串重定向到新文件'],
  ['python3 -c "print(1)"', '普通 python'],
  ['git push origin main', '普通 push'],
  ['npm install', '普通安装'],
  ['sed -n "1p" /tmp/log', 'sed 只读'],
  ['cat > /tmp/newscript.sh <<EOF\necho hi\nrm -rf /\nEOF', 'here-doc 正文不是命令'],
  ['ls /tmp; echo $(date)', '命令替换里不是删除'],
  ['echo skipped || ls /tmp', '逻辑或兜底命令'],
  ['if [ -d /tmp/build ]; then echo found; fi', 'shell 控制结构'],
  ['printf "rm -rf /" >> /tmp/log', '把 rm 追加进日志'],

];

// #endregion

// #region 已知绕过：如实登记，不判失败（识别器看不见就是看不见）

const KNOWN_BYPASS = [
  ['python3 -c "import shutil; shutil.rmtree(\'/tmp/build\')"', '用 Python 删目录（不解析 -c 正文）'],
  ['node -e "require(\'node:fs\').rmSync(\'/tmp/build\',{recursive:true})"', '用 Node 删目录'],
  ['perl -e \'unlink("/tmp/a.txt")\'', '用 Perl 删文件'],
  ['ruby -e \'File.delete("/tmp/a.txt")\'', '用 Ruby 删文件'],
  ['echo "rm -rf /tmp/build" | bash', '管到 bash 的 stdin（不把管道数据当命令）'],
  ['bash <<EOF\nrm -rf /tmp/build\nEOF', 'here-doc 喂给 bash'],
  ['eval "$(cat /tmp/evil.sh)"', 'eval 动态内容'],
  ["CMD='rm -rf /tmp/build'; $CMD", '变量存整条命令（预扫只认单字面量）'],
  [': > $TARGET', '重定向目标来自未定义变量'],
  ['sed -i "s/a/b/" /tmp/log', 'sed -i 原地改写'],
  ['tar -xf /tmp/x.tar -C /root', 'tar 解包覆盖'],
  ['unzip -o /tmp/x.zip -d /root', 'unzip -o 覆盖'],
  ['install -m 644 /tmp/a /root/work/b', 'install 覆盖'],
  ['curl -o /root/work/b https://example.com', 'curl -o 覆盖'],
  ['git push --force origin main', '强推远端'],
  ['trash /tmp/a.txt', 'trash（可恢复，按非破坏处理）'],
];

// #endregion

let failures = 0;
let passed = 0;

function check(condition, label, detail) {
  if (condition) {
    passed += 1;
    return true;
  }
  failures += 1;
  console.error(`✗ ${label}${detail === undefined ? '' : `\n    ${detail}`}`);
  return false;
}

console.log('== 必抓（期望判为破坏性） ==');
let catchHit = 0;
const missed = [];
for (const [command, why, expectedKind] of MUST_CATCH) {
  const result = analyze(command);
  const ok = check(result.destructive, `${why}：${command}`, '识别器认为无害');
  if (ok) catchHit += 1;
  else missed.push(command);
  if (result.destructive && expectedKind !== null) {
    const kinds = result.findings.map((finding) => finding.kind);
    check(kinds.includes(expectedKind), `${why}：${command} 应命中 ${expectedKind}`, `实际 ${kinds.join(',')}`);
  }
}
console.log(`必抓：${catchHit}/${MUST_CATCH.length}`);

console.log('== 不得误报（期望判为无害） ==');
let quiet = 0;
const noise = [];
for (const [command, why] of MUST_NOT_FLAG) {
  const result = analyze(command);
  const ok = check(!result.destructive, `${why}：${command}`, `误报为 ${result.severity} ${result.findings.map((finding) => finding.kind).join(',')}`);
  if (ok) quiet += 1;
  else noise.push(`${command} → ${result.findings.map((finding) => finding.kind).join(',')}`);
}
console.log(`不误报：${quiet}/${MUST_NOT_FLAG.length}`);

console.log('== 已知绕过（如实登记，不计入失败） ==');
let bypassCaught = 0;
for (const [command, why] of KNOWN_BYPASS) {
  const result = analyze(command);
  if (result.destructive) bypassCaught += 1;
  console.log(`  ${result.destructive ? '命中' : '漏网'}  ${why}`);
}
console.log(`已知绕过命中：${bypassCaught}/${KNOWN_BYPASS.length}（漏网项就是必须写进文档的覆盖边界）`);

// #region 目标清单与计数（需求：弹窗要显示"删什么、几个"）

console.log('== 目标清单与计数 ==');
{
  const one = analyze('rm -rf /root/work/build /root/work/dist');
  check(one.total === 2, '两个目录应计 2 个目标', `实际 ${one.total}`);
  check(one.targets.includes('/root/work/build') && one.targets.includes('/root/work/dist'), '应解析出绝对路径', JSON.stringify(one.targets));
  const globbed = analyze('rm -rf /tmp/build/*');
  check(globbed.total === 2, '通配应展开计数为 2', `实际 ${globbed.total} ${JSON.stringify(globbed.findings[0].targets)}`);
  const unknown = analyze('rm -rf /tmp/build/**');
  check(unknown.unknownTargets === true, '** 通配应报"数量未知"', JSON.stringify(unknown));
  const relative = analyze('rm -rf build');
  check(relative.targets.includes('/root/work/build'), '相对路径应按 cwd 解析', JSON.stringify(relative.targets));
  const piped = analyze('find /tmp -name "*.log" | xargs rm -rf');
  check(piped.unknownTargets === true, 'xargs 目标应报"数量未知"', JSON.stringify(piped.targets));
  const critical = analyze('rm -rf /');
  check(critical.severity === 'critical', 'rm -rf / 应为 critical', critical.severity);
  const homeGlob = analyze('rm -rf /root/*');
  check(homeGlob.severity === 'critical', 'rm -rf /root/* 应为 critical', homeGlob.severity);
  const nested = analyze("find /tmp -exec rm -rf {} +");
  check(nested.destructive, 'find -exec 里的 rm 应被识别');
}

// #endregion

// #region 闸门行为（cordis 插件层）

console.log('== 闸门行为 ==');
{
  const registered = [];
  const makeCtx = (policy) => ({
    logger: { warn() {}, info() {} },
    get: (key) => (key === 'approval' ? { effectivePolicy: () => policy } : undefined),
    on: (event, handler) => registered.push({ event, handler }),
  });
  const shell = (command) => ({
    name: 'bash',
    callId: 'call-1',
    arguments: { command, description: 'test' },
    agent: { session: { header: { cwd: CWD } } },
  });
  const allow = async () => ({ kind: 'allow' });
  const denyDownstream = async () => ({ kind: 'deny', reason: 'auto review denied' });
  const cancelDownstream = async () => ({ kind: 'cancel' });

  guard.apply(makeCtx('ask'));
  check(registered.length === 1 && registered[0].event === 'tools/pre-execute', '应注册 tools/pre-execute 闸门', JSON.stringify(registered.map((row) => row.event)));
  const gate = registered[0].handler;

  const benign = await gate(shell('ls -la'), allow);
  check(benign.kind === 'allow', '无害命令应放行', JSON.stringify(benign));

  const asked = await gate(shell('rm -rf /root/work/build'), allow);
  check(asked.kind === 'ask', '破坏性命令应触发审批', JSON.stringify(asked).slice(0, 200));
  check(typeof asked.reason === 'string' && asked.reason.length > 0, 'ask 必须带 reason');
  check(asked.displayReason !== undefined && typeof asked.displayReason.zh === 'string', 'ask 必须带中文 displayReason');
  check(asked.reason.includes('/root/work/build'), '英文 reason 必须点出目标路径', asked.reason);
  check(asked.displayReason.zh.includes('/root/work/build'), '中文 reason 必须点出目标路径', asked.displayReason.zh);
  check(/1 个目标/.test(asked.displayReason.zh), '中文 reason 必须报数量', asked.displayReason.zh);

  const listed = await gate(shell('rm -rf /root/work/build /root/work/dist/*'), allow);
  check(listed.displayReason.zh.includes('/root/work/dist/*'), '通配目标也要列出来', listed.displayReason.zh);
  check(listed.displayReason.zh.includes('2 个目标'), '两个目标要报 2', listed.displayReason.zh);

  const unbounded = await gate(shell('rm -rf /tmp/build/**'), allow);
  check(unbounded.kind === 'ask' && unbounded.displayReason.zh.includes('数量未知'), '** 通配应提示"数量未知"', unbounded.displayReason?.zh ?? '');

  const notBash = await gate({ ...shell('rm -rf /root/work/build'), name: 'write' }, allow);
  check(notBash.kind === 'allow', '非 bash 工具不归本守卫管', JSON.stringify(notBash));

  const downstreamDeny = await gate(shell('rm -rf /root/work/build'), denyDownstream);
  check(downstreamDeny.kind === 'deny', '下游拒绝必须透传，守卫不得翻成允许', JSON.stringify(downstreamDeny));

  const downstreamCancel = await gate(shell('rm -rf /root/work/build'), cancelDownstream);
  check(downstreamCancel.kind === 'cancel', '下游 cancel 必须透传', JSON.stringify(downstreamCancel));

  guard.apply(makeCtx('never'));
  const neverGate = registered[registered.length - 1].handler;
  const blocked = await neverGate(shell('rm -rf /root/work/build'), allow);
  check(blocked.kind === 'deny', '策略 never 时必须硬拒绝（不弹窗也不放行）', JSON.stringify(blocked).slice(0, 200));
  check(blocked.info?.code === 'DSHA_DESTRUCTIVE_BLOCKED', '拒绝要带可识别的错误码', JSON.stringify(blocked.info));
  check(blocked.reason.includes('/root/work/build'), '拒绝理由要点出目标', blocked.reason);
  const neverBenign = await neverGate(shell('ls -la'), allow);
  check(neverBenign.kind === 'allow', '策略 never 不影响无害命令', JSON.stringify(neverBenign));
}

// #endregion

// #region 真实文件系统（不是桩）

console.log('== 真实 FS（/tmp 临时目录） ==');
{
  const dir = mkdtempSync(join(tmpdir(), 'dsha-guard-'));
  try {
    const existing = join(dir, 'existing.txt');
    writeFileSync(existing, 'x');
    const missing = join(dir, 'not-yet.txt');
    const registered = [];
    guard.apply({
      logger: { warn() {}, info() {} },
      get: () => ({ effectivePolicy: () => 'ask' }),
      on: (event, handler) => registered.push(handler),
    });
    const gate = registered[0];
    const exec = (command) => ({ name: 'bash', callId: 'c', arguments: { command, description: 't' }, agent: { session: { header: { cwd: dir } } } });
    const allow = async () => ({ kind: 'allow' });
    const onExisting = await gate(exec(`rm -rf ${existing}`), allow);
    check(onExisting.kind === 'ask', '真实存在的文件被 rm 要问', JSON.stringify(onExisting).slice(0, 160));
    const newRedirect = await gate(exec(`echo hi > ${missing}`), allow);
    check(newRedirect.kind === 'allow', '真实不存在的新文件重定向不该打扰用户', JSON.stringify(newRedirect).slice(0, 160));
    const overwrite = await gate(exec(`echo hi > ${existing}`), allow);
    check(overwrite.kind === 'ask', '真实存在的文件被重定向截断要问', JSON.stringify(overwrite).slice(0, 160));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

// #endregion

console.log('');
if (failures > 0) {
  console.error(`✗ 失败 ${failures} 项，通过 ${passed} 项`);
  if (missed.length > 0) console.error(`  漏抓：${missed.join(' | ')}`);
  if (noise.length > 0) console.error(`  误报：${noise.join(' | ')}`);
  process.exit(1);
}
console.log(`✓ 全部通过：${passed} 项断言；必抓 ${catchHit}/${MUST_CATCH.length}，不误报 ${quiet}/${MUST_NOT_FLAG.length}`);
