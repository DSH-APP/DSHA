// 从固定上游预构建产物重建 DSHA 差异；不执行上游安装/构建脚本。
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { applyFileTransferClient } from './file-transfer/client-mobile.mjs';
import { applyMobileSafeArea } from './mobile-safe-area.mjs';
import { applyMobileLocaleBundlePatch, applyMobileLocaleSourcePatch, mobileLocaleSourceFiles } from './mobile-locales.mjs';
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
  // build336（T101 重做，用户选 C）+ build351（2026-11-09 同步上游 3.3.5）：以上游为基座，
  // client 只保留四类本地差异：
  //   1) 宿主集成 DSHA_GEAR_FIT_V1：footer 齿轮按实测行宽让位（上游 3.3.5 仍是旧的
  //      「连接条 :has() 存在性」判据，DSH 0.2.2 首连不渲染指示灯时会判错）；
  //   2) 安全区：applyMobileSafeArea 把 env(safe-area-inset-*) 包成 DSHA 原生发布的变量；
  //   3) 文件传输：applyFileTransferClient（原生 share / save-as / 下载记录与 32 MiB 限制）；
  //   4) 多语言：applyMobileLocaleBundlePatch（zh-Hant/ru/ja/ko/ar/pt/la/fr + live-locale）。
  // 会话输入 V4、手机搜索、快捷键弹层可见带、字体族行换行、遮罩按压染色按「完全追随上游」
  // 删除；残留问题、上游覆盖情况与测试变更见 docs/web-mobile-patches.md 与 build336/build351 记录。
  // DSHA_GEAR_FIT_V1（T61）：footer 齿轮的让位判据改成**只量几何**，不再读宿主
  // ConnectionIndicator 是否存在。DSH 0.2.2 首次连接不再渲染指示灯后，上游
  // `:has(> button[data-phase], > [role="status"])` 判据永远「放得下」，手机上这条
  // 行第一次走进「没有指示灯」分支；让位口径改成实测行内剩余宽度，宿主以后增删任何
  // 孩子（指示灯、更新条、新按钮）都自动计入。3.3.1/3.3.5 构建剥了 CSS 注释，锚点改为规则本体。
  const gearGuardRule = `[data-mobile-nav="frame"] [class*="sidebarCol"] [class*="triggerRow"]:has(> button[data-phase], > [role="status"]) [data-mobile-nav="quick-settings-wrap"] {
  display: none !important;
}`;
  replace(gearGuardRule,
    `/* @patch
   id: drawer-quick-settings-fit-guard
   for: 侧栏 footer 的 triggerRow（账户 launcher + 宿主连接条 / 更新条 / 以后新增的孩子）
   why: 该行只有 260px；连接条 flex:none（中文约 136px、英文约 190px），齿轮常驻会在英文下把账户按钮挤到约 26px、头像被裁。旧守卫用 :has(> button[data-phase], > [role="status"]) 依赖宿主指示灯**存在**：DSH 0.2.2 首次连接不再渲染指示灯后判据失灵。改为由 effects/drawer-quick-settings.ts 实测行内剩余宽度，把结论写在本插件自己的属性上——齿轮可见性不再依赖宿主任何子节点的存在。
   until: 宿主给 settings.launcher 提供独立的次要动作位——届时删本块
   test: tools/test-mobile-gear-contract.mjs
*/
[data-mobile-nav="frame"] [class*="sidebarCol"] [class*="triggerRow"] [data-mobile-nav="quick-settings-wrap"][data-mobile-nav-quick-fit="0"] {
  display: none !important;
}`);
  // DSHA_GEAR_FIT_V1：纯几何判据（导出给宿主测试，见 tools/test-mobile-gear-contract.mjs）。
  replace('function createDrawerQuickSettingsTask(ctx) {',
    `/** DSHA_GEAR_FIT_V1：齿轮占位＝按钮 30px + 右外边距 6px + 4px 余量（见上方 CSS）。 */
const GEAR_SLOT_PX = 40;
/**
 * DSHA_GEAR_FIT_V1：齿轮此刻放不放得下。
 *
 * 旧判据读「行的直接孩子里有没有宿主的连接条（button[data-phase] / [role="status"]）」，
 * 于是插件的渲染依赖宿主那个节点**存在**：宿主改了首次连接的渲染（DSH 0.2.2 首连不再
 * 显示指示灯）之后，判据永远得到「放得下」，行的真实宽度却没人核对。这里只核对几何：
 * 行内容宽度 − 其它可见孩子宽度 ≥ 齿轮占位；宿主以后增删任何孩子都自动被计入。
 *
 * @param rowWidth 行内容宽度（已扣左右 padding，CSS px）
 * @param usedWidth 其它可见孩子的宽度和（含外边距，CSS px）
 * @returns 放得下为 true；宽度不可用（≤ 0 或非有限数）按「放不下」处理，保守保留账户菜单入口
 */
function dshaGearFits(rowWidth, usedWidth) {
  return Number.isFinite(rowWidth) && Number.isFinite(usedWidth)
    && rowWidth > 0 && usedWidth >= 0 && rowWidth - usedWidth >= GEAR_SLOT_PX;
}
exports.dshaGearFits = dshaGearFits;
exports.GEAR_SLOT_PX = GEAR_SLOT_PX;
function createDrawerQuickSettingsTask(ctx) {`);
  replace(`    /**
     * 齿轮此刻在不在场：唯一会隐藏它的是连接条守卫（\`drawer-quick-settings-chip-guard\`），
     * 条件就是行的**直接孩子**里有连接条（\`button[data-phase]\` / \`[role="status"]\`）。
     * 这里镜像同一判据而不是读计算样式——两处口径必须一致，改守卫时一起改。
     */
    const gearAvailable = (target) => ![...target.children].some((child) => child.getAttribute('data-phase') !== null || child.getAttribute('role') === 'status');`,
    `    /**
     * DSHA_GEAR_FIT_V1：实测这一行还剩多少宽度。
     *
     * 只量几何、不看宿主节点的身份：连接条、更新条、以后新增的任何孩子都按宽度计入。
     * 布局还没就绪（行宽 ≈ 0）时 dshaGearFits 返回 false，即保守地保留账户菜单入口。
     */
    const gearAvailable = (target) => {
        const style = getComputedStyle(target);
        const rowWidth = target.getBoundingClientRect().width
            - (Number.parseFloat(style.paddingLeft) || 0)
            - (Number.parseFloat(style.paddingRight) || 0);
        let usedWidth = 0;
        for (const child of target.children) {
            if (child === wrap) continue;
            const childStyle = getComputedStyle(child);
            if (childStyle.display === 'none' || childStyle.visibility === 'hidden') continue;
            usedWidth += child.getBoundingClientRect().width
                + (Number.parseFloat(childStyle.marginLeft) || 0)
                + (Number.parseFloat(childStyle.marginRight) || 0);
        }
        return dshaGearFits(rowWidth, usedWidth);
    };`);
  replace('    const syncMenuEntry = (target) => {',
    '    const syncMenuEntry = (target, gearVisible) => {');
  replace("        const keep = trigger !== null && trigger.getAttribute('aria-expanded') === 'true' && gearAvailable(target);",
    "        const keep = trigger !== null && trigger.getAttribute('aria-expanded') === 'true' && gearVisible;");
  replace(`            if (wrap !== null && wrap.parentElement !== target)
                target.appendChild(wrap);
            bindRowHook();
            syncMenuEntry(target);`,
    `            if (wrap !== null && wrap.parentElement !== target)
                target.appendChild(wrap);
            bindRowHook();
            // DSHA_GEAR_FIT_V1：可见性只由实测宽度决定，结论写在插件自己的属性上；
            // 幂等写入（值没变就不写），避免自己的 mutation 再把任务唤醒一轮。
            const gearVisible = gearAvailable(target);
            if (wrap !== null) {
                const fit = gearVisible ? '1' : '0';
                if (wrap.dataset.mobileNavQuickFit !== fit) wrap.dataset.mobileNavQuickFit = fit;
            }
            syncMenuEntry(target, gearVisible);`);
  return applyMobileSafeArea(applyFileTransferClient(applyMobileLocaleBundlePatch(source)));
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [input, action = '--check'] = process.argv.slice(2);
  if (input === '--locale-sources') {
    const [directory, localeAction = '--check'] = process.argv.slice(3);
    if (!directory || !['--check', '--write'].includes(localeAction)) throw new Error('用法: node tools/apply-mobile-client-patches.mjs --locale-sources <固定上游目录> [--check|--write]');
    for (const name of mobileLocaleSourceFiles) {
      const bytes = readFileSync(path.join(directory, name));
      if (sha(bytes) !== lock.files[name]?.sha256) throw new Error('mobile locale source differs from fixed commit: ' + name);
      const patched = applyMobileLocaleSourcePatch(name, bytes.toString('utf8'));
      const output = path.join(project, 'app/src/main/assets/builtin-plugins/dsh-web-mobile', name);
      if (localeAction === '--write') writeFileSync(output, patched);
      else if (readFileSync(output, 'utf8') !== patched) throw new Error('移动语言源码与锁定补丁不符: ' + name);
    }
    console.log('移动语言源码与上游指纹核验通过: ' + upstreamCommit);
    process.exit(0);
  }
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
    // New DSHA-only module has no upstream member: copy only the reviewed
    // canonical source, and retain the same exact-byte check as other modules.
    const transfer = readFileSync(path.join(project, 'tools/file-transfer/file-transfer.js'));
    const transferOutput = path.join(project, 'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/file-transfer.js');
    const transferMap = JSON.parse(mobileServerSourceMap('file-transfer.js', transfer));
    transferMap.sources = ['dsha://tools/file-transfer/file-transfer.js'];
    const transferMapText = JSON.stringify(transferMap) + '\n';
    if (serverAction === '--write') { writeFileSync(transferOutput, transfer); writeFileSync(transferOutput + '.map', transferMapText); }
    else if (!readFileSync(transferOutput).equals(transfer) || readFileSync(transferOutput + '.map', 'utf8') !== transferMapText)
      throw new Error('文件传输服务产物/映射与canonical不符');
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
