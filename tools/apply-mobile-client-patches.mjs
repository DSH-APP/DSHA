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
    '            // DSHA_SESSION_INPUT_V4：保留原始点击，实际打开仍由 dsha-session-open 关闭抽屉。\n');
  replaceRange('  /* 手机档收掉搜索行', '  /* 这一层的遮罩',
    '  /* DSHA 保留手机搜索；上游定向首轮焦点守卫不删除宿主 React 节点。 */\n');
  // Documentation-only: V4 opens valid touch/pen/mouse clicks through the host;
  // keep the owned-row exemption and close on the actual open event, not pointerup.
  replace('        // DSHA_SESSION_INTERACTION_V1：宿主把「单击=选中、双击=打开」拆成了两步\n'
    + '        // （data-dsha-session-select 标记 + dsha-session-open 事件）。上游的\n'
    + '        // 「点行即关抽屉」会在第一次单击就把抽屉收掉，双击永远到不了。\n'
    + '        // 这些行改由 dsha-session-open 事件关闭（见下方 document 监听）。',
    '        // DSHA_SESSION_INTERACTION_V4：有效的 touch/pen/mouse 单击由宿主打开会话。\n'
    + '        // data-dsha-session-select 标记的行等待真实 dsha-session-open 事件关闭抽屉。\n'
    + '        // 不在 pointerup 提前收起，避免吞掉浏览器随后派发的原始 click。\n'
    + '        // 关闭依据是宿主已经打开会话的事实（见下方 document 监听）。');
  replace('        /** Swallow the host\'s title double-click rename: on touch a double *tap*\n'
    + '         *  means "open the session" (DSHA\'s own two-tap detection sits on the row\'s\n'
    + '         *  click handler), and letting the browser\'s `dblclick` reach the title would\n'
    + '         *  open the host\'s rename dialog on that same gesture. Capture phase on',
    '        /** Swallow the host\'s title double-click rename: repeated touch taps already\n'
    + '         *  use the host\'s normal session-open path. Letting the browser\'s `dblclick`\n'
    + '         *  reach the title would also open the rename dialog on that gesture.\n'
    + '         *  Keep the original click handling unchanged. Capture phase on');
  replace('        // DSHA 宿主在「真正打开会话」时才派发 dsha-session-open（单击只选中），\n'
    + '        // 所以抽屉的关闭挂在这个事实上，而不是挂在点击上。',
    '        // DSHA V4 有效单击打开会话后派发 dsha-session-open；\n'
    + '        // 抽屉只在打开事实到达时关闭，保留原始 click 与行标记豁免。');
  // The full-page backdrop is a button for accessibility, so the upstream
  // generic :active inset tint also paints it. On dark-theme touch taps that
  // adds #ffffff24 across the whole dim layer. Keep its own fade/close behavior
  // and ordinary button feedback; only this owned backdrop skips the tint.
  replace('/* Floating fallback button (hero / blank phases without a session header).',
    `/* DSHA_BACKDROP_PRESS_V1: a dimming surface must not become a bright pressed button. */
@media (max-width: 1023px) and (pointer: coarse) and (hover: none) {
  [data-mobile-nav="backdrop"]:active {
    box-shadow: none;
  }
}

/* Floating fallback button (hero / blank phases without a session header).`);
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
