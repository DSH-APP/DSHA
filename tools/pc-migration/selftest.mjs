#!/usr/bin/env node
// 便携包的离线自检：真源导出 → 恢复 → 逐项核验。
//
// 它跑在**手机容器里**（有真实的 /root/.dsh、node 24 的 zstd、python3），
// 所以能在不碰用户会话、不起第二个 dsh 的前提下，把「导出 + 恢复」这条链路
// 的每一条断言都验一遍。
//
// 用法：node selftest.mjs [--home /root/.dsh] [--keep]

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';

const HERE = path.dirname(new URL(import.meta.url).pathname);
const EXPORTER = path.join(HERE, 'dsha-portable.py');
const RESTORER = path.join(HERE, 'dsha-portable-restore.mjs');

let failures = 0;
let checks = 0;
function check(label, condition, detail = '') {
  checks += 1;
  if (condition) {
    process.stdout.write(`  ok   ${label}\n`);
  } else {
    failures += 1;
    process.stdout.write(`  FAIL ${label}${detail ? `  — ${detail}` : ''}\n`);
  }
}

function projectKey(cwd) {
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

function decode(file) {
  const buffer = fs.readFileSync(file);
  return file.endsWith('.zstd') ? zlib.zstdDecompressSync(buffer) : buffer;
}

function logsUnder(home) {
  const out = [];
  const root = path.join(home, 'sessions');
  if (!fs.existsSync(root)) return out;
  for (const project of fs.readdirSync(root, { withFileTypes: true })) {
    if (!project.isDirectory()) continue;
    const projectDir = path.join(root, project.name);
    for (const session of fs.readdirSync(projectDir, { withFileTypes: true })) {
      if (!session.isDirectory()) continue;
      const sessionDir = path.join(projectDir, session.name);
      for (const name of fs.readdirSync(sessionDir)) {
        if (/^session\.v\d+\.jsonl(\.zstd)?$/.test(name)) {
          out.push({
            projectKey: project.name,
            id: session.name,
            file: path.join(sessionDir, name),
            name,
          });
        }
      }
    }
  }
  return out;
}

function listFiles(dir, relative = '') {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const rel = relative ? `${relative}/${entry.name}` : entry.name;
    if (entry.isDirectory()) out.push(...listFiles(path.join(dir, entry.name), rel));
    else out.push(rel);
  }
  return out;
}

function run(command, args, options = {}) {
  return execFileSync(command, args, { encoding: 'utf8', ...options });
}

function main() {
  const args = process.argv.slice(2);
  const keep = args.includes('--keep');
  const homeIndex = args.indexOf('--home');
  const sourceHome = homeIndex >= 0 ? args[homeIndex + 1] : '/root/.dsh';
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'dsha-portable-selftest-'));
  const outDir = path.join(tmp, 'out');
  const restHome = path.join(tmp, 'restored');
  const restHomeCreds = path.join(tmp, 'restored-creds');
  const workDir = path.join(tmp, 'work');
  fs.mkdirSync(outDir, { recursive: true });
  fs.mkdirSync(workDir, { recursive: true });

  process.stdout.write(`源 DSH_HOME : ${sourceHome}\n临时目录    : ${tmp}\n\n`);

  // ── 1. plan 模式不写文件 ───────────────────────────────────────────────
  process.stdout.write('== 1. plan 只读 ==\n');
  const before = listFiles(outDir).length;
  run('python3', [EXPORTER, 'plan', '--home', sourceHome]);
  check('plan 不产出任何文件', listFiles(outDir).length === before);

  // ── 2. 默认导出（不含凭据）───────────────────────────────────────────
  process.stdout.write('\n== 2. 默认导出 ==\n');
  const exportOut = run('python3', [EXPORTER, 'export', '--home', sourceHome, '--out', outDir]);
  const pkgName = exportOut.match(/已生成：(.+\.tar\.gz)/)?.[1];
  check('导出成功并给出包路径', Boolean(pkgName), exportOut.split('\n')[0]);
  const pkg = pkgName ?? '';
  check('包不为空', fs.existsSync(pkg) && fs.statSync(pkg).size > 0);

  const entries = run('tar', ['-tzf', pkg]).split('\n').filter(Boolean);
  check('包内没有绝对路径', entries.every((e) => !e.startsWith('/')));
  check('包内没有 .. 逃逸', entries.every((e) => !e.includes('..')));
  check('包内只有 home/ workspace/ manifest.json README', entries.every((e) => {
    const top = e.split('/')[0];
    return top === 'home' || top === 'workspace' || top === 'manifest.json' || e.startsWith('README');
  }));
  check('包内不含 session.lock（租约文件由恢复端重建）', !entries.some((e) => e.endsWith('session.lock')));

  const stagedManifest = path.join(tmp, 'manifest-check');
  fs.mkdirSync(stagedManifest, { recursive: true });
  run('tar', ['-xzf', pkg, '-C', stagedManifest]);
  const manifest = JSON.parse(fs.readFileSync(path.join(stagedManifest, 'manifest.json'), 'utf8'));
  check('manifest.kind 正确', manifest.kind === 'dsha-portable-package');
  check('manifest 记录了 dsh 版本', typeof manifest.source.dshVersion === 'string' && manifest.source.dshVersion.length > 0);
  check('manifest 记录了排除理由', Array.isArray(manifest.excluded) && manifest.excluded.length > 0);
  check('凭据默认排除', manifest.credentials.included === false);
  check('README 在包内', entries.some((e) => e.startsWith('README')));
  check('README 讲清了怎么恢复', fs.readFileSync(path.join(stagedManifest, 'README-在电脑上恢复.md'), 'utf8').includes('dsha-portable-restore.mjs'));

  const sourceLogs = logsUnder(sourceHome);
  check(`源会话数 ${sourceLogs.length} > 0`, sourceLogs.length > 0);
  const stagedLogs = logsUnder(path.join(stagedManifest, 'home'));
  check(`包内会话数与源一致（${stagedLogs.length}）`, stagedLogs.length === sourceLogs.length);

  // ── 3. 默认恢复（保原编码、不改 cwd）─────────────────────────────────
  process.stdout.write('\n== 3. 默认恢复（原样保留 zstd 与 cwd）==\n');
  run('node', [RESTORER, 'apply', '--package', pkg, '--home', restHome]);
  const restoredLogs = logsUnder(restHome);
  check(`恢复后会话数一致（${restoredLogs.length}）`, restoredLogs.length === sourceLogs.length);
  check('恢复后仍为 zstd 编码', restoredLogs.every((l) => l.name.endsWith('.zstd')));
  let allConsistent = true;
  let sampleCwd = null;
  const stagedLogsById = new Map(stagedLogs.map((l) => [l.id, l]));
  for (const item of restoredLogs) {
    const text = decode(item.file).toString('utf8');
    const header = JSON.parse(text.slice(0, text.indexOf('\n')));
    sampleCwd = sampleCwd ?? header.cwd;
    if (header.id !== item.id || projectKey(header.cwd) !== item.projectKey) allConsistent = false;
    // 与**包内快照**逐字节相同（不能拿源比：源里正在写入的会话会在导出之后继续增长）
    const stagedLog = stagedLogsById.get(item.id);
    if (!stagedLog || !fs.readFileSync(stagedLog.file).equals(fs.readFileSync(item.file))) allConsistent = false;
  }
  check('会话 header 与目录名自洽，且与包内快照逐字节一致', allConsistent);
  check('投影缓存被丢弃', !fs.existsSync(path.join(restHome, 'storages', 'session_projcache')));
  check('未落地凭据文件', !fs.existsSync(path.join(restHome, '.credentials.yaml')));

  const restoredAll = listFiles(restHome);
  for (const forbidden of [
    '.bridge_token', '.bridge_headers', '.bridge_status', '.anonymous-user-id',
    '.runtime-descriptor.json', 'node_modules', 'script-version', 'builtin-plugins.json',
  ]) {
    check(`不含设备侧文件 ${forbidden}`, !restoredAll.some((f) => f === forbidden || f.startsWith(`${forbidden}/`)));
  }
  check('不含任何 DSHA 随包脚本', !restoredAll.some((f) => /\.(py|cjs)$/.test(f)));
  check('不含 profiles/*/package.json', !restoredAll.some((f) => /^profiles\/[^/]+\/package\.json$/.test(f)));
  check(`保留了 profile 设置层（${restoredAll.filter((f) => f.endsWith('cordis.patch.yml')).length} 份）`, restoredAll.some((f) => f.endsWith('cordis.patch.yml')));

  // ── 4. --rewrite-cwd：转明文 + 改写 cwd ──────────────────────────────
  process.stdout.write('\n== 4. --rewrite-cwd ==\n');
  run('node', [RESTORER, 'apply', '--package', pkg, '--home', restHomeCreds, '--workspace-dir', workDir, '--rewrite-cwd']);
  const rewritten = logsUnder(restHomeCreds);
  check(`改写后会话数一致（${rewritten.length}）`, rewritten.length === restoredLogs.length);
  check('全部转成明文 jsonl', rewritten.every((l) => l.name.endsWith('.jsonl')));
  check('全部落到新的 projectKey 目录', rewritten.every((l) => l.projectKey === projectKey(workDir)));
  let rewriteOk = true;
  for (const item of rewritten) {
    const text = fs.readFileSync(item.file, 'utf8');
    const header = JSON.parse(text.slice(0, text.indexOf('\n')));
    if (header.id !== item.id || header.cwd !== workDir) rewriteOk = false;
    const source = sourceLogs.find((s) => s.id === item.id);
    if (!source) {
      rewriteOk = false;
      continue;
    }
    const original = decode(source.file).toString('utf8');
    const originalTail = original.slice(original.indexOf('\n'));
    const newTail = text.slice(text.indexOf('\n'));
    // 除 header 的 cwd 外必须逐字节一致 —— 这是「转换无损」的核心证据
    if (originalTail !== newTail) rewriteOk = false;
  }
  check('header.cwd 已改写，且除 cwd 外正文逐字节无损', rewriteOk);

  const patchFiles = listFiles(restHomeCreds).filter((f) => f.endsWith('cordis.patch.yml'));
  const patchText = patchFiles.map((f) => fs.readFileSync(path.join(restHomeCreds, f), 'utf8')).join('\n');
  check('profile patch 里有 session-persistence-jsonl 覆盖', patchText.includes('session-persistence-jsonl'));
  check('覆盖里带 compression: none', /compression:\s*none/.test(patchText));
  check('覆盖里同时重写了 root（补丁是整键替换）', /root:\s*"/.test(patchText));
  const workspaceJson = JSON.parse(fs.readFileSync(path.join(restHomeCreds, 'storages', 'workspace.json'), 'utf8'));
  const paths = Object.values(workspaceJson.tables.workspaces).map((w) => w.path);
  check(`workspace.json 路径已改写到 ${workDir}`, paths.every((p) => p === workDir), JSON.stringify(paths));

  // ── 5. 带凭据导出：必须剔除 client-connection/* ──────────────────────
  process.stdout.write('\n== 5. --with-credentials 的字段级剔除 ==\n');
  const credSource = path.join(sourceHome, '.credentials.yaml');
  if (!fs.existsSync(credSource)) {
    check('源没有 .credentials.yaml，跳过（不算失败）', true);
  } else {
    const outDir2 = path.join(tmp, 'out-creds');
    fs.mkdirSync(outDir2, { recursive: true });
    const exportOut2 = run('python3', [EXPORTER, 'export', '--home', sourceHome, '--out', outDir2, '--with-credentials']);
    const pkg2 = exportOut2.match(/已生成：(.+\.tar\.gz)/)?.[1] ?? '';
    const staged2 = path.join(tmp, 'manifest-check-creds');
    fs.mkdirSync(staged2, { recursive: true });
    run('tar', ['-xzf', pkg2, '-C', staged2]);
    const manifest2 = JSON.parse(fs.readFileSync(path.join(staged2, 'manifest.json'), 'utf8'));
    check('manifest 标明凭据已包含', manifest2.credentials.included === true);
    const credText = fs.readFileSync(path.join(staged2, 'home', '.credentials.yaml'), 'utf8');
    check('已剔除 client-connection/* 记录', !credText.includes('client-connection/'), credText.slice(0, 200));
    check('保留了 refs 区块', /^refs:/m.test(credText));
    const sourceText = fs.readFileSync(credSource, 'utf8');
    check('确实剔除了东西（源里本来有）', sourceText.includes('client-connection/'));
    const restCreds = path.join(tmp, 'restored-2');
    run('node', [RESTORER, 'apply', '--package', pkg2, '--home', restCreds]);
    const mode = fs.statSync(path.join(restCreds, '.credentials.yaml')).mode & 0o777;
    check('落地后凭据权限为 600', mode === 0o600, mode.toString(8));
  }

  // ── 6. 拒绝不安全输入 ────────────────────────────────────────────────
  process.stdout.write('\n== 6. 不安全输入必须被拒绝 ==\n');
  const evilDir = path.join(tmp, 'evil');
  fs.mkdirSync(evilDir, { recursive: true });
  fs.writeFileSync(path.join(evilDir, 'escape.txt'), 'x');
  const evilPkg = path.join(tmp, 'evil.tar.gz');
  run('tar', ['-czf', evilPkg, '-C', evilDir, '--transform', 's|escape.txt|../escape.txt|', 'escape.txt']);
  let rejected = false;
  try {
    execFileSync('node', [RESTORER, 'plan', '--package', evilPkg, '--home', path.join(tmp, 'never')], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch {
    rejected = true;
  }
  check('含 ../ 的包被拒绝', rejected);

  process.stdout.write(`\n${failures === 0 ? '全部通过' : '有失败'}：${checks - failures}/${checks}\n`);
  if (!keep) fs.rmSync(tmp, { recursive: true, force: true });
  else process.stdout.write(`临时目录保留：${tmp}\n`);
  process.exit(failures === 0 ? 0 : 1);
}

main();
