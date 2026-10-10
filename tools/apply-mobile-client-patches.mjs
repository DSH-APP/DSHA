// 从固定上游产物重建 DSHA 局部差异；不执行上游安装/构建脚本。
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

export const upstreamCommit = '9b16223e6c5ee8209c25034b24fb790990967b3f';
export const upstreamClientHash = '1403ab28a1f3478c2a7a11236830f242140e2de7463b7ccb384e469e93bd3ffa';
const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// Host modules are whole-module replacements because the upstream buffered
// stream and private-registry lifecycle must be replaced together. Exact
// commit bytes plus named public export anchors pin that review boundary.
export const upstreamServerHashes = {
  'compress.js': '66c7cc33cb01c5fcc03830422ca587a378daa8c8e4bb10c300c86dccf7f0f5f3',
  'delete-session.js': 'ea7ab47189ca6150313f9a16c7e68afa820f6d57adc27fee82b39ecc96a28d6b',
  'index.js': '9e9fea06e83e4b6554d65c11dbf7764a8a087d3f2cde655578891efac217b5ca',
};
const serverAnchors = {
  'compress.js': 'export function installResponseCompression()',
  'delete-session.js': 'export async function deleteSession(deps, sessionId)',
  'index.js': 'export function apply(ctx)',
};
export function applyMobileServerPatch(name, bytes) {
  if (!(name in upstreamServerHashes)) throw new Error('unknown mobile server module: ' + name);
  const output = readFileSync(path.join(project, 'tools/mobile-server', name));
  // Idempotent over the canonical patched output, with no partial re-patch.
  if (bytes.equals(output)) return output;
  const source = bytes.toString('utf8');
  if (createHash('sha256').update(bytes).digest('hex') !== upstreamServerHashes[name]
      || source.split(serverAnchors[name]).length !== 2)
    throw new Error('mobile server source differs from fixed commit/anchor: ' + name);
  return output;
}

export function applyMobileClientPatches(bytes) {
  if (createHash('sha256').update(bytes).digest('hex') !== upstreamClientHash)
    throw new Error('上游 client 字节不匹配固定 commit；请重新审阅补丁');
  let source = bytes.toString('utf8');
  const replace = (before, after) => {
    if (source.split(before).length !== 2) throw new Error('补丁锚点缺失或不唯一: ' + before.slice(0, 100));
    source = source.replace(before, after);
  };
  // rc2 热替换重新执行模块工厂，旧模块局部变量不能把滚动位置交给新模块。
  // 按真实 DOM 节点保存，避免按数组下标将旧位置套到另一条会话。
  const scrollStart = source.indexOf('const SWAP_RESTORE_MS = 10_000;');
  const scrollEnd = source.indexOf('/**\n * Mobile-adaptive shell, browser half:', scrollStart);
  if (scrollStart < 0 || scrollEnd < 0) throw new Error('移动滚动恢复模块锚点缺失');
  source = source.slice(0, scrollStart) + `const SWAP_RESTORE_MS = 10_000;
const scrollState = window.__dshaMobileScrollState instanceof WeakMap
    ? window.__dshaMobileScrollState
    : (window.__dshaMobileScrollState = new WeakMap());
function conversationScrollers() {
    return [...document.querySelectorAll('[data-mobile-nav="frame"] [class*="scrollBody"]')];
}
function rememberConversationScroll() {
    for (const element of conversationScrollers()) {
        if (element.scrollTop > 0)
            scrollState.set(element, {top: element.scrollTop, savedAt: Date.now(), url: location.href});
        else scrollState.delete(element);
    }
}
function restoreConversationScroll() {
    for (const element of conversationScrollers()) {
        const saved = scrollState.get(element);
        scrollState.delete(element);
        if (!saved || Date.now() - saved.savedAt > SWAP_RESTORE_MS || saved.url !== location.href) continue;
        requestAnimationFrame(() => {
            if (element.isConnected && saved.url === location.href
                && Date.now() - saved.savedAt <= SWAP_RESTORE_MS && element.scrollTop === 0)
                element.scrollTop = saved.top;
        });
    }
}
` + source.slice(scrollEnd);
  // Android WebView 没有 Web Share；仅正式顶层页面注入的文件接口可接管。
  replace("        canShare: typeof nav.canShare === 'function' ? data => nav.canShare(data) : undefined,\n        share: typeof nav.share === 'function' ? data => nav.share(data) : undefined,",
    "        canShare: typeof window.DSHA?.canShareFile === 'function' ? data => data.files.length === 1 && window.DSHA.canShareFile(data.files[0]) : typeof nav.canShare === 'function' ? data => nav.canShare(data) : undefined,\n        share: typeof window.DSHA?.shareFile === 'function' ? data => window.DSHA.shareFile(data.files[0]) : typeof nav.share === 'function' ? data => nav.share(data) : undefined,");
  replace('const consumed = new Map();', 'const consumed = new WeakMap();');
  replace(`    if (!isElementLike(target)) {
        consumed.set(target, until);
        return;
    }`, `    if (!isElementLike(target)) {
        if ((typeof target !== 'object' && typeof target !== 'function') || target === null) return;
        consumed.set(target, until);
        return;
    }`);
  replace(`    if (!isElementLike(target)) {
        for (const [t, until] of consumed) {
            if (until <= now)
                consumed.delete(t);
        }
        return false;
    }`, `    if (!isElementLike(target)) {
        if ((typeof target !== 'object' && typeof target !== 'function') || target === null) return false;
        const until = consumed.get(target);
        if (until === undefined) return false;
        if (until <= now) {
            consumed.delete(target);
            return false;
        }
        return true;
    }`);
  replace('if (event.touches.length > 1) {',
    'if (event.touches.length !== 1 || !event.cancelable || document.hidden) {');
  replace('            for (const record of records) {\n                keys.add(',
    "            for (const record of records) {\n                // 历史消息和流式文本不改变 shell；flow 外的插入仍可唤醒布局。\n                const target = record.target instanceof Element ? record.target : record.target.parentElement;\n                if (target?.closest('[data-chat-flow]')) continue;\n                keys.add(");
  replace('            core.note(keys);', '            if (keys.size) core.note(keys);');

  // 两个焦点守卫共享上游管理器；保留 DSHA 对锁定原型和第三方包装的归属检查。
  for (const [name, local] of [
    ['core/prototype-focus-shadow.js', 'mobile-focus-shadow.js'],
    ['effects/shortcut-modal-keyboard-guard.js', 'mobile-shortcut-guard.js'],
  ]) {
    const header = '__modules["' + name + '"] = function (require, module, exports) {';
    const start = source.indexOf(header);
    const end = source.indexOf('\n__modules[', start + 1);
    if (start < 0 || end < 0 || source.split(header).length !== 2)
      throw new Error('缺失焦点守卫模块边界: ' + name);
    const guard = readFileSync(path.join(project, 'tools', local), 'utf8').trim();
    source = source.slice(0, start) + header + '\n' + guard + '\n};\n' + source.slice(end);
  }

  // 保留键盘外高度基线，同时以当前真正可见区域限制纸片，支持同宽分屏缩短。
  // [DSHA-PERF] 值未变不写 + 宽度变化立即 + 其余按 150ms 静默期合并。
  // 实测（vivo V2463A / Android 16 / 120Hz）：原来每个 resize/visualViewport scroll 都无条件往
  // :root 写两个自定义属性，整棵文档样式失效 —— 单帧 114-149ms，其中样式布局 29-40ms。
  replace('        let stableVh = 0;\n        let stableWidth = 0;\n        const syncStableViewport = () => {\n            const height = window.innerHeight;\n            const width = window.innerWidth;\n            if (stableVh === 0',
    "        let stableVh = 0;\n        let stableWidth = 0;\n        let lastVisibleVh = '';\n        let lastViewportTop = '';\n        let syncWidth = -1;\n        let syncTimer = 0;\n        let syncRaf = 0;\n        const writeStableViewport = () => {\n            const height = window.innerHeight;\n            const width = window.innerWidth;\n            const visible = window.visualViewport;\n            const available = Math.max(1, Math.min(height, visible?.height ?? height));\n            const nextVh = `${available}px`;\n            const nextTop = `${visible?.offsetTop ?? 0}px`;\n            if (nextVh !== lastVisibleVh) {\n                lastVisibleVh = nextVh;\n                root.style.setProperty('--dsha-mobile-visible-vh', nextVh);\n            }\n            if (nextTop !== lastViewportTop) {\n                lastViewportTop = nextTop;\n                root.style.setProperty('--dsha-mobile-viewport-top', nextTop);\n            }\n            if (stableVh === 0");
  replace("        };\n        syncStableViewport();\n        window.addEventListener('resize', syncStableViewport);",
    "        };\n        const syncStableViewport = () => {\n            const width = window.innerWidth;\n            if (width !== syncWidth) {\n                syncWidth = width;\n                if (syncTimer !== 0) { clearTimeout(syncTimer); syncTimer = 0; }\n                if (syncRaf !== 0) { cancelAnimationFrame(syncRaf); syncRaf = 0; }\n                writeStableViewport();\n                return;\n            }\n            if (syncTimer !== 0) clearTimeout(syncTimer);\n            syncTimer = setTimeout(() => {\n                syncTimer = 0;\n                if (syncRaf !== 0) return;\n                syncRaf = requestAnimationFrame(() => { syncRaf = 0; writeStableViewport(); });\n            }, 150);\n        };\n        syncStableViewport();\n        window.addEventListener('resize', syncStableViewport);\n        window.visualViewport?.addEventListener('resize', syncStableViewport);\n        window.visualViewport?.addEventListener('scroll', syncStableViewport);");
  replace("            window.removeEventListener('resize', syncStableViewport);\n            root.style.removeProperty(exports.STABLE_VIEWPORT_VAR);",
    "            if (syncTimer !== 0) { clearTimeout(syncTimer); syncTimer = 0; }\n            if (syncRaf !== 0) { cancelAnimationFrame(syncRaf); syncRaf = 0; }\n            window.removeEventListener('resize', syncStableViewport);\n            window.visualViewport?.removeEventListener('resize', syncStableViewport);\n            window.visualViewport?.removeEventListener('scroll', syncStableViewport);\n            root.style.removeProperty('--dsha-mobile-visible-vh');\n            root.style.removeProperty('--dsha-mobile-viewport-top');\n            root.style.removeProperty(exports.STABLE_VIEWPORT_VAR);");
  replace('        // appears, and the keyboard simply covers their lower half. Content that\n        // would fall behind the keyboard gets a keyboard-sized bottom padding on\n        // the scroller (layout.css.ts), which shifts nothing visible.',
    '        // appears. DSHA also caps cards with the current visible viewport: deliberate\n        // keyboard input and same-width split-screen resizing must keep all actions\n        // reachable. No keyboard-padding implementation is assumed here.');

  const hideStart = source.indexOf('  /* 手机档收掉搜索行');
  const hideEnd = source.indexOf('  /* 这一层的遮罩', hideStart);
  if (hideStart < 0 || hideEnd < 0) throw new Error('手机搜索隐藏块锚点缺失');
  source = source.slice(0, hideStart) + '  /* DSHA 保留手机搜索；首次自动聚焦由定向守卫处理，宿主节点保持原位。 */\n' + source.slice(hideEnd);
  // 有键盘时缩短卡片是可操作性要求；禁止过渡让保存/关闭按钮滞留键盘下。
  // 只覆盖两种受影响的卡片，不复活上游已撤回的动画/合成层批次。
  const css = `
  /* DSHA 可见区域边界：分屏、短横屏、软键盘和 visualViewport 平移均可达。 */
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
      if (serverAction === '--write') writeFileSync(output, patched);
      else if (!readFileSync(output).equals(patched)) throw new Error('移动服务产物与锁定补丁不符: ' + name);
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
