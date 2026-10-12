#!/usr/bin/env node
// 电脑端：把手机导出的 DSHA 便携包恢复成可用的 $DSH_HOME。
//
// 设计原则：
//   * `plan` 是默认动作，只读不写；`apply` 才落盘。
//   * 落地前先整体改名保留旧数据（`~/.dsh.before-restore-<ts>`），不做静默覆盖。
//   * 包里没有的路径**一律不动**；包里带的设备侧垃圾（投影缓存、桥凭据、
//     node_modules）按策略丢弃或直接拒绝。
//   * 凭据 fail-closed：`.credentials.yaml` 里只要还剩 `client-connection/`
//     记录就整份不落地 —— 那是浏览器会话签名密钥，迁到电脑上没有用处，
//     留着只是泄露面。
//
// 用法：
//   node dsha-portable-restore.mjs plan  --package DSHA-portable-<ts>.tar.gz
//   node dsha-portable-restore.mjs apply --package <pkg> --workspace-dir ~/work
//   node dsha-portable-restore.mjs apply --package <pkg> --workspace-dir ~/work --rewrite-cwd

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';

const HOME_PREFIX = 'home/';
const CREDENTIALS_FILE = '.credentials.yaml';
const MACHINE_RECORD_PREFIX = 'client-connection/';
const PROJCACHE = path.join('storages', 'session_projcache');
const ENCODING_ROW_MARKER = '# --- DSHA portable: plaintext session encoding ---';

function die(message, code = 2) {
  process.stderr.write(`错误：${message}\n`);
  process.exit(code);
}

function info(message) {
  process.stdout.write(`${message}\n`);
}

function human(bytes) {
  let value = bytes;
  for (const unit of ['B', 'KiB', 'MiB', 'GiB', 'TiB']) {
    if (value < 1024 || unit === 'TiB') return unit === 'B' ? `${value} B` : `${value.toFixed(1)} ${unit}`;
    value /= 1024;
  }
  return `${bytes} B`;
}

function expandHome(value) {
  if (!value) return value;
  if (value === '~') return os.homedir();
  if (value.startsWith('~/') || value.startsWith('~\\')) return path.join(os.homedir(), value.slice(2));
  return value;
}

/**
 * dsh 的会话目录名编码（逐字节照抄 @deepseek-ai/dsh-session-persistence-jsonl 的 projectKey）。
 * 恢复工具要靠它核验「目录名 ↔ header.cwd」自洽，抄错就等于给出假证据。
 */
function projectKey(cwd) {
  if (cwd.length === 0) throw new Error('cannot encode an empty project path');
  let readable = '';
  let separatorRun = false;
  for (let i = 0; i < cwd.length; i++) {
    const code = cwd.charCodeAt(i);
    const ch = String.fromCharCode(code);
    if (ch === '/' || ch === '\\' || ch === ':') {
      if (!separatorRun) readable += '-';
      separatorRun = true;
    } else if (ch !== '~' && /^[A-Za-z0-9._-]$/.test(ch)) {
      readable += ch;
      separatorRun = false;
    } else {
      readable += `~${code.toString(16).toUpperCase().padStart(4, '0')}`;
      separatorRun = false;
    }
  }
  return `--${(readable.replace(/^-+/, '') || 'root').slice(0, 251)}--`;
}

function decodeLog(file) {
  const buffer = fs.readFileSync(file);
  if (file.endsWith('.zstd')) return zlib.zstdDecompressSync(buffer);
  return buffer;
}

function readHeader(file) {
  const text = decodeLog(file).toString('utf8');
  const newline = text.indexOf('\n');
  const line = newline >= 0 ? text.slice(0, newline) : text;
  return { header: JSON.parse(line), raw: text };
}

function listSessionLogs(homeDir) {
  const root = path.join(homeDir, 'sessions');
  const out = [];
  if (!fs.existsSync(root)) return out;
  for (const project of fs.readdirSync(root, { withFileTypes: true })) {
    if (!project.isDirectory() || project.name.startsWith('.')) continue;
    const projectDir = path.join(root, project.name);
    for (const session of fs.readdirSync(projectDir, { withFileTypes: true })) {
      if (!session.isDirectory()) continue;
      const sessionDir = path.join(projectDir, session.name);
      const log = fs
        .readdirSync(sessionDir)
        .filter((name) => /^session\.v\d+\.jsonl(\.zstd)?$/.test(name))
        .sort()[0];
      if (!log) continue;
      out.push({ projectKey: project.name, id: session.name, sessionDir, log: path.join(sessionDir, log), logName: log });
    }
  }
  return out;
}

/** 用 tar 自带清单预检，禁止绝对路径与 `..` —— 包不是可信输入。 */
function inspectedEntries(pkg) {
  const listing = execFileSync('tar', ['-tzf', pkg], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  const entries = listing.split('\n').filter(Boolean);
  for (const entry of entries) {
    if (path.isAbsolute(entry) || entry.startsWith('/') || entry.includes('..')) {
      die(`包里有不安全的路径条目：${entry}`);
    }
    const top = entry.split('/')[0];
    if (![ 'home', 'workspace', 'manifest.json' ].includes(top) && !entry.startsWith('README')) {
      die(`包里有预期之外的顶层条目：${entry}`);
    }
  }
  return entries;
}

function extractTo(pkg, staging) {
  fs.mkdirSync(staging, { recursive: true });
  execFileSync('tar', ['-xzf', pkg, '-C', staging], { stdio: ['ignore', 'ignore', 'inherit'] });
}

function loadContext(args) {
  const pkg = path.resolve(expandHome(args.package));
  if (!fs.existsSync(pkg)) die(`找不到包：${pkg}`);
  const staging = fs.mkdtempSync(path.join(os.tmpdir(), 'dsha-portable-restore-'));
  inspectedEntries(pkg);
  extractTo(pkg, staging);
  const manifestPath = path.join(staging, 'manifest.json');
  if (!fs.existsSync(manifestPath)) die('包里没有 manifest.json，拒绝按猜测恢复');
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  if (manifest.kind !== 'dsha-portable-package') die(`manifest.kind 不是 dsha-portable-package：${manifest.kind}`);
  const stagedHome = path.join(staging, 'home');
  const home = path.resolve(expandHome(args.home ?? path.join(os.homedir(), '.dsh')));
  return { pkg, staging, manifest, stagedHome, home };
}

function stagingBytes(dir) {
  let files = 0;
  let bytes = 0;
  const walk = (current) => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.isFile()) {
        files += 1;
        bytes += fs.statSync(full).size;
      }
    }
  };
  if (fs.existsSync(dir)) walk(dir);
  return { files, bytes };
}

function sessionReport(stagedHome) {
  return listSessionLogs(stagedHome).map((item) => {
    let cwd = null;
    let ok = false;
    let note = '';
    try {
      const { header } = readHeader(item.log);
      cwd = header.cwd ?? null;
      ok = header.id === item.id && (cwd === null || projectKey(cwd) === item.projectKey);
      if (!ok) note = '目录名与 header 不自洽（dsh 会拒绝读取）';
    } catch (error) {
      note = `无法解析：${error.message}`;
    }
    return { ...item, cwd, consistent: ok, note };
  });
}

function credentialsReport(stagedHome) {
  const file = path.join(stagedHome, CREDENTIALS_FILE);
  if (!fs.existsSync(file)) return { present: false, records: [], machineRecords: [] };
  const text = fs.readFileSync(file, 'utf8');
  const records = [...text.matchAll(/^ {2}([a-z][a-z0-9-]*\/[a-z][a-z0-9-]*):/gm)].map((m) => m[1]);
  return {
    present: true,
    records,
    machineRecords: records.filter((name) => name.startsWith(MACHINE_RECORD_PREFIX)),
  };
}

function cmdPlan(args) {
  const ctx = loadContext(args);
  const sessions = sessionReport(ctx.stagedHome);
  const size = stagingBytes(ctx.stagedHome);
  const credentials = credentialsReport(ctx.stagedHome);

  info(`包        : ${path.basename(ctx.pkg)}  (${human(fs.statSync(ctx.pkg).size)})`);
  info(`来源      : ${ctx.manifest.source.dshHome}  dsh ${ctx.manifest.source.dshVersion}`);
  info(`导出时间  : ${ctx.manifest.createdAt}`);
  info(`目标 $DSH_HOME : ${ctx.home}${fs.existsSync(ctx.home) ? '  ← 已存在，apply 时会改名保留' : '  ← 不存在，将新建'}`);
  info('');
  info(`home 内容 : ${size.files} 文件 / ${human(size.bytes)}`);
  info(`会话      : ${sessions.length} 个`);
  const cwds = [...new Set(sessions.map((s) => s.cwd).filter(Boolean))];
  for (const cwd of cwds) {
    const count = sessions.filter((s) => s.cwd === cwd).length;
    info(`  cwd ${cwd}  (${count} 个会话)`);
  }
  const broken = sessions.filter((s) => !s.consistent);
  if (broken.length) info(`  ⚠ ${broken.length} 个会话目录名与 header 不自洽：${broken.map((b) => b.id).join(', ')}`);
  info('');
  info(`凭据      : ${credentials.present ? '包含 .credentials.yaml' : '不包含（默认策略）'}`);
  if (credentials.present) {
    info(`  记录    : ${credentials.records.join(', ') || '（无）'}`);
    if (credentials.machineRecords.length) {
      info(`  ⚠ 仍含本机记录 ${credentials.machineRecords.join(', ')} —— apply 会拒绝落地这份凭据（fail-closed）`);
    }
  }
  info('');
  const workspacePresent = fs.existsSync(path.join(ctx.staging, 'workspace'));
  info(`工作区    : ${workspacePresent ? `包含，需 --workspace-dir 指定落点` : '未包含'}`);
  if (ctx.manifest.options.workspaces.length) info(`  手机上的路径 : ${ctx.manifest.options.workspaces.join(', ')}`);
  info('');
  info('下一步（apply 的完整命令）：');
  const flags = [`--package ${path.basename(ctx.pkg)}`];
  if (workspacePresent) flags.push('--workspace-dir <目录>');
  if (cwds.some((cwd) => cwd && !fs.existsSync(cwd))) {
    flags.push('--rewrite-cwd');
    info('  提示：上面有 cwd 在本机不存在。只想查看历史就照下面执行；想在电脑上「接着聊」，');
    info('        加 --rewrite-cwd（会把会话日志转成明文 jsonl 并改写 cwd，同时在 profile 里加 compression: none）。');
  }
  info(`  node dsha-portable-restore.mjs apply ${flags.join(' ')}`);
  fs.rmSync(ctx.staging, { recursive: true, force: true });
  return 0;
}

function copyTree(src, dst) {
  fs.mkdirSync(dst, { recursive: true });
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const from = path.join(src, entry.name);
    const to = path.join(dst, entry.name);
    if (entry.isDirectory()) copyTree(from, to);
    else if (entry.isSymbolicLink()) continue;
    else if (entry.isFile()) {
      fs.mkdirSync(path.dirname(to), { recursive: true });
      fs.copyFileSync(from, to);
    }
  }
}

function rewriteWorkspacePaths(home, workspaceDir) {
  const file = path.join(home, 'storages', 'workspace.json');
  if (!fs.existsSync(file)) return [];
  const document = JSON.parse(fs.readFileSync(file, 'utf8'));
  const changed = [];
  const workspaces = document?.tables?.workspaces;
  if (workspaces && typeof workspaces === 'object') {
    for (const [id, record] of Object.entries(workspaces)) {
      if (record && typeof record.path === 'string') {
        changed.push(`${record.path} → ${workspaceDir}`);
        record.path = workspaceDir;
      }
    }
  }
  fs.writeFileSync(file, `${JSON.stringify(document, null, 2)}\n`, 'utf8');
  return changed;
}

/** 把整棵 sessions 转成明文 jsonl 并把 header.cwd 改写为 targetCwd。 */
function rewriteSessionCwd(home, targetCwd) {
  const result = { converted: 0, moved: 0, error: null };
  const sessions = listSessionLogs(home);
  const newKey = projectKey(targetCwd);
  for (const item of sessions) {
    let text;
    let header;
    try {
      const parsed = readHeader(item.log);
      header = parsed.header;
      text = parsed.raw;
    } catch (error) {
      result.error = `会话 ${item.id} 读取失败：${error.message}`;
      return result;
    }
    if (header.cwd === targetCwd && !item.logName.endsWith('.zstd')) continue;
    const newline = text.indexOf('\n');
    const tail = newline >= 0 ? text.slice(newline) : '';
    header.cwd = targetCwd;
    const plainName = item.logName.replace(/\.zstd$/, '');
    const targetDir = path.join(home, 'sessions', newKey, item.id);
    fs.mkdirSync(targetDir, { recursive: true });
    fs.writeFileSync(path.join(targetDir, plainName), JSON.stringify(header) + tail, 'utf8');
    fs.rmSync(item.sessionDir, { recursive: true, force: true });
    result.converted += 1;
    if (item.projectKey !== newKey) result.moved += 1;
  }
  // 收掉空的旧 projectKey 目录
  const root = path.join(home, 'sessions');
  if (fs.existsSync(root)) {
    for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      const dir = path.join(root, entry.name);
      if (fs.readdirSync(dir).length === 0) fs.rmdirSync(dir);
    }
  }
  return result;
}

/**
 * 往 profile 的用户设置层追加 session-persistence-jsonl 的 compression: none。
 * 必须连 root 一起写：补丁是 `target[key] = value` 整键替换，不是深合并，
 * 只写 compression 会把 bundle 里的 root 抹掉。
 */
function appendEncodingRow(home, profile, sessionsRoot) {
  const file = path.join(home, 'profiles', profile, 'cordis.patch.yml');
  if (!fs.existsSync(file)) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, '# Your patch layer for this dsh profile.\n', 'utf8');
  }
  const text = fs.readFileSync(file, 'utf8');
  if (text.includes(ENCODING_ROW_MARKER)) return file;
  const row = [
    ENCODING_ROW_MARKER,
    '# 会话日志已转成明文 jsonl（dsh 不允许同一个 sessions 根混用两种编码）。',
    '# 删掉下面这段再重启 dsh，就会回到默认的 zstd 编码 —— 但那样读不了这些明文日志。',
    '- id: session-persistence-jsonl',
    "  name: \"@deepseek-ai/dsh-session-persistence-jsonl\"",
    '  config:',
    `    root: ${JSON.stringify(sessionsRoot)}`,
    '    compression: none',
    '',
  ].join('\n');
  fs.writeFileSync(file, `${text.replace(/\s*$/, '')}\n${row}`, 'utf8');
  return file;
}

function cmdApply(args) {
  const ctx = loadContext(args);
  const size = stagingBytes(ctx.stagedHome);
  info(`包        : ${path.basename(ctx.pkg)}  (${human(fs.statSync(ctx.pkg).size)})`);
  info(`目标      : ${ctx.home}`);

  // 1) 凭据 fail-closed：包里带本机记录就整份不落地
  const credentials = credentialsReport(ctx.stagedHome);
  const credentialsFile = path.join(ctx.stagedHome, CREDENTIALS_FILE);
  if (credentials.present && credentials.machineRecords.length) {
    fs.rmSync(credentialsFile, { force: true });
    info(`凭据      : 拒绝落地（仍含 ${credentials.machineRecords.join(', ')}），已从暂存区丢弃`);
  } else if (credentials.present) {
    info(`凭据      : 落地并 chmod 600（记录：${credentials.records.join(', ') || '无'}）`);
  } else {
    info('凭据      : 无（第一次启动 dsh 时重新填 API Key）');
  }

  // 2) 旧数据改名保留
  if (fs.existsSync(ctx.home)) {
    if (args.merge) {
      info('旧数据    : --merge，直接合并进入已有目录');
    } else {
      const backup = `${ctx.home}.before-restore-${new Date().toISOString().replace(/[:.]/g, '-')}`;
      fs.renameSync(ctx.home, backup);
      info(`旧数据    : 已整体改名为 ${backup}`);
    }
  }
  fs.mkdirSync(ctx.home, { recursive: true });

  // 3) 拷贝 home 内容
  copyTree(ctx.stagedHome, ctx.home);
  const dropped = [];
  if (fs.existsSync(path.join(ctx.home, PROJCACHE))) {
    fs.rmSync(path.join(ctx.home, PROJCACHE), { recursive: true, force: true });
    dropped.push(PROJCACHE);
  }
  const copiedCredentials = path.join(ctx.home, CREDENTIALS_FILE);
  if (fs.existsSync(copiedCredentials)) {
    fs.chmodSync(copiedCredentials, 0o600);
    const stillMachine = credentialsReport(ctx.home).machineRecords;
    if (stillMachine.length) die(`落地后的凭据仍含 ${stillMachine.join(', ')}，已完成的数据请自行检查 ${ctx.home}`);
  }

  // 4) 工作区
  const packagedWorkspace = path.join(ctx.staging, 'workspace');
  let workspaceTarget = null;
  if (args['workspace-dir']) {
    // 即使用户没带工作区进包，也要认这个落点：它是 cwd 改写与注册表改写的目标
    workspaceTarget = path.resolve(expandHome(args['workspace-dir']));
    if (fs.existsSync(packagedWorkspace)) {
      fs.mkdirSync(workspaceTarget, { recursive: true });
      copyTree(packagedWorkspace, workspaceTarget);
      info(`工作区    : 已复制到 ${workspaceTarget}`);
    } else {
      info(`工作区    : 包内没有工作区文件；--workspace-dir 仅用作路径改写目标 ${workspaceTarget}`);
    }
  } else if (fs.existsSync(packagedWorkspace)) {
    info('工作区    : 包里带了工作区，但没给 --workspace-dir —— 原样留在暂存区里，未复制');
  }

  // 5) 改写工作区注册表的绝对路径
  if (workspaceTarget) {
    const changed = rewriteWorkspacePaths(ctx.home, workspaceTarget);
    for (const line of changed) info(`注册表    : ${line}`);
  }

  // 6) 会话 cwd 改写（可选）
  if (args['rewrite-cwd']) {
    if (!workspaceTarget) die('--rewrite-cwd 需要 --workspace-dir 来确定新工作区');
    const result = rewriteSessionCwd(ctx.home, workspaceTarget);
    if (result.error) die(result.error);
    const patchFile = appendEncodingRow(ctx.home, ctx.manifest.source.profile, path.join(ctx.home, 'sessions'));
    info(`会话编码  : ${result.converted} 个会话已转为明文 jsonl（${result.moved} 个换了项目目录）`);
    info(`编码开关  : 已写入 ${patchFile}`);
  } else {
    const sessions = sessionReport(ctx.home);
    const missing = [...new Set(sessions.map((s) => s.cwd).filter((cwd) => cwd && !fs.existsSync(cwd)))];
    if (missing.length) {
      info('');
      info(`注意      : 这些会话记录的 cwd 在本机不存在：${missing.join(', ')}`);
      info('            查看历史没问题；要在旧会话上继续，请重跑并加 --rewrite-cwd --workspace-dir <目录>。');
    }
  }

  info('');
  info(`完成      : ${size.files} 文件 / ${human(size.bytes)} → ${ctx.home}`);
  if (dropped.length) info(`丢弃      : ${dropped.join(', ')}（可重建的投影缓存）`);
  info('');
  info('接着做：');
  info(`  1) 确认 dsh 版本与手机一致（手机上是 ${ctx.manifest.source.dshVersion}）：dsh --version`);
  info('  2) 起 Web：dsh web --no-open');
  info('  3) 第一次会要求填 API Key —— 手机上的 Key 没有随包带过来（默认排除凭据）。');

  fs.rmSync(ctx.staging, { recursive: true, force: true });
  return 0;
}

function parseArgs(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (!token.startsWith('--')) {
      args._.push(token);
      continue;
    }
    const name = token.slice(2);
    if (['rewrite-cwd', 'merge', 'help'].includes(name)) args[name] = true;
    else {
      args[name] = argv[++i];
    }
  }
  return args;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const command = args._[0] ?? 'plan';
  if (args.help || command === 'help') {
    info('用法：node dsha-portable-restore.mjs <plan|apply> --package <pkg.tar.gz> [选项]');
    info('  --home <dir>          目标 $DSH_HOME（默认 ~/.dsh）');
    info('  --workspace-dir <dir> 把包里的工作区放到哪里');
    info('  --rewrite-cwd         会话日志转明文并改写 cwd（需要 --workspace-dir）');
    info('  --merge               不保留旧数据，直接合并进已有 $DSH_HOME');
    info('不带子命令时等于 plan（只读）。');
    return 0;
  }
  if (!args.package) die('必须给 --package <包路径>');
  if (command === 'plan') return cmdPlan(args);
  if (command === 'apply') return cmdApply(args);
  return die(`未知子命令：${command}`);
}

process.exit(main());
