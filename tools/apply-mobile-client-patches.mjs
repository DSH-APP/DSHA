// 从固定上游预构建产物重建 DSHA 差异；不执行上游安装/构建脚本。
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const lock = JSON.parse(readFileSync(path.join(project, 'tools/mobile-plugin-upstream.lock.json'), 'utf8'));
export const upstreamCommit = lock.commit;
export const upstreamClientHash = lock.files['lib/client.js'].sha256;
export const upstreamServerHashes = Object.fromEntries(
  ['compress.js', 'delete-session.js', 'index.js'].map(name => [name, lock.files['lib/' + name].sha256]));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const serverAnchors = {
  'compress.js': 'export function installResponseCompression()',
  'delete-session.js': 'export async function deleteSession(deps, sessionId)',
  'index.js': 'export function apply(ctx)',
};
// rc2 鉴权、公开生命周期、原件保留和有界流压缩仍由 DSHA 实现。
export function applyMobileServerPatch(name, bytes) {
  if (!(name in upstreamServerHashes)) throw new Error('unknown mobile server module: ' + name);
  const output = readFileSync(path.join(project, 'tools/mobile-server', name));
  if (bytes.equals(output)) return output;
  if (sha(bytes) !== upstreamServerHashes[name] || bytes.toString('utf8').split(serverAnchors[name]).length !== 2)
    throw new Error('mobile server source differs from fixed commit/anchor: ' + name);
  return output;
}
// canonical 与真实本地来源逐行对应，不能沿用上游 TypeScript 的旧映射。
export function mobileServerSourceMap(name, bytes) {
  const source = bytes.toString('utf8'), lines = source.split('\n').length;
  return JSON.stringify({ version: 3, file: name, sourceRoot: '',
    sources: ['dsha://tools/mobile-server/' + name], sourcesContent: [source], names: [],
    mappings: ['AAAA', ...Array(Math.max(0, lines - 1)).fill('AACA')].join(';') }) + '\n';
}
export function applyMobileClientPatches(bytes) {
  if (sha(bytes) !== upstreamClientHash) throw new Error('上游 client 字节不匹配固定 commit；请重新审阅补丁');
  let source = bytes.toString('utf8');
  const replace = (before, after) => {
    if (source.split(before).length !== 2) throw new Error('补丁锚点缺失或不唯一: ' + before.slice(0, 100));
    source = source.replace(before, after);
  };
  const replaceRange = (startAnchor, endAnchor, after) => {
    const start = source.indexOf(startAnchor), end = source.indexOf(endAnchor, start + startAnchor.length);
    if (start < 0 || end < 0 || source.split(startAnchor).length !== 2 || source.split(endAnchor).length !== 2)
      throw new Error('补丁模块范围缺失或不唯一: ' + startAnchor);
    source = source.slice(0, start) + after + source.slice(end);
  };
  // 3.2.1 沿用已吸收的可见视口变量、写入去重与销毁清理；最新 main 已撤回
  // 后续按帧合并试验。保留原 viewport-meta 与 interactive-widget=resizes-content。
  // 按实际鼠标/touch/pen事件分流；不能把用户点击伪造成键盘 detail=0。
  replaceRange('            // DSHA detail=0 follows the host\'s keyboard/programmatic open path.',
    '            // A touch row-tap owns the close (pointerup or the navigation observer);',
    '            // DSHA_SESSION_INPUT_V3：保留原始点击，实际打开仍由 dsha-session-open 关闭抽屉。\n');
  replaceRange('  /* 手机档收掉搜索行', '  /* 这一层的遮罩',
    '  /* DSHA 保留手机搜索；上游定向首轮焦点守卫不删除宿主 React 节点。 */\n');
  const css = `
  /* DSHA 可见区域边界：原生已消费 IME/systemBars，只读浏览器实际视口。 */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])):not([data-shortcut-modal="shortcuts"]),
  [aria-modal="true"][data-shortcut-modal="shortcuts"] {
    top: calc(env(safe-area-inset-top, 0px) + 12px + var(--dsha-mobile-viewport-top, 0px)) !important;
    max-height: max(1px, calc(var(--dsha-mobile-visible-vh, 100vh) - 24px - env(safe-area-inset-top, 0px))) !important;
    min-height: 0 !important;
    box-sizing: border-box;
    overflow-y: auto;
    transition: none;
  }
  [aria-modal="true"][data-shortcut-modal="shortcuts"] > :first-child {
    min-height: 0;
    max-height: 100%;
  }
`;
  replace('  /* ---------- sidebar panel enter / exit (see effects/panel-exit.ts) ----------',
    css + '  /* ---------- sidebar panel enter / exit (see effects/panel-exit.ts) ----------');
  return source;
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [input, action = '--check'] = process.argv.slice(2);
  if (input === '--server') {
    const [directory, serverAction = '--check'] = process.argv.slice(3);
    if (!directory || !['--check', '--write'].includes(serverAction)) throw new Error('用法: node tools/apply-mobile-client-patches.mjs --server <固定上游lib目录> [--check|--write]');
    for (const name of Object.keys(upstreamServerHashes)) {
      const patched = applyMobileServerPatch(name, readFileSync(path.join(directory, name)));
      const output = path.join(project, 'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib', name);
      const map = mobileServerSourceMap(name, patched);
      if (serverAction === '--write') { writeFileSync(output, patched); writeFileSync(output + '.map', map); }
      else if (!readFileSync(output).equals(patched) || readFileSync(output + '.map', 'utf8') !== map)
        throw new Error('移动服务产物/映射与锁定补丁不符: ' + name);
    }
    console.log('移动服务补丁与上游指纹核验通过: ' + upstreamCommit);
    process.exit(0);
  }
  if (!input || !['--check', '--write'].includes(action)) throw new Error('用法: node tools/apply-mobile-client-patches.mjs <上游lib/client.js> [--check|--write]');
  const patched = applyMobileClientPatches(readFileSync(input));
  const output = path.join(project, 'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js');
  if (action === '--write') writeFileSync(output, patched);
  else if (readFileSync(output, 'utf8') !== patched) throw new Error('当前内置产物与锁定上游及 DSHA 补丁不符');
  console.log('移动插件补丁与上游指纹核验通过: ' + upstreamCommit);
}
