import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';

const directory = 'app/src/main/assets/builtin-plugins/dsh-web-mobile/';
const source = readFileSync(directory + 'lib/client.js', 'utf8');
const packageJson = JSON.parse(readFileSync(directory + 'package.json', 'utf8'));

function loadModules() {
  let plugin;
  const window = { __ModuleLoader__: { load(value) { plugin = value; } } };
  const sandbox = { window, console, performance: { now: () => 1000 } };
  vm.runInNewContext(source.replace('var __cache = {};',
    'window.__testModules = __modules; window.__testRequire = __localRequire; var __cache = {};'), sandbox);
  plugin.factory(() => ({}));
  return {modules: window.__testModules};
}

test('3.3.5 固定上游 bundle 保留 DSHA 移动端入口与头部让位', () => {
  assert.equal(packageJson.version, '3.3.5');
  assert.equal(packageJson.dshaUpstream.commit, '646fe837440f9939228bdba0024d407eaac01128');
  assert.equal(packageJson.dshaUpstream.clientSha256, '8a17dd03b5de35f1aea52c93d6e3352fcd43c781de380639f60a93bf6040a26a');
  assert.equal(packageJson.dshaUpstream.ref, 'main');
  assert.equal(packageJson.dshaUpstream.releaseTag, 'v3.3.5');
  assert.equal(packageJson.dshaUpstream.releaseCommit, '14ecfccf5a6acb69e3a84229dee328d31801306f');
  // 上游 v3.3.5 标签之后还有 3 个修复提交（README、插件页交还宿主排版），实际取用 main 提交。
  assert.notEqual(packageJson.dshaUpstream.commit, packageJson.dshaUpstream.releaseCommit);
  for (const marker of [
    'DSHA_GEAR_FIT_V1',
    'mobileNavLocales',
    'DSHAFileTransfer',
    'var(--dsha-safe-area-inset-top, env(safe-area-inset-top, 0px))',
    '[data-dsha-session-select]',
    'dsha-preset-header-anchor',
    'data-mobile-nav="file-upload"',
    'data-sidebar-right-expand',
    '--dsh-frame-leading-clearance',
    'IconPanelLeftOutlineRegular',
    'IconFolderOpenOutlineRegular',
    'IconDownloadOutlineRegular',
    'IconPaperclipOutlineRegular',
    'pickIcon',
    'icon-compat',
    'ComposerFileButton',
    '-webkit-tap-highlight-color: transparent',
    'filter: brightness(.92)',
    'bottom: -2px !important',
    'transform: rotate(180deg) !important',
    'session-log-downloads',
    'downloadMenu',
    'data-sidebar-right-open',
    '.dsh-pet-root',
    'findFloatingWidget',
  ]) assert.ok(source.includes(marker), marker);
  for (const stale of [
    "'data-mobile-nav': 'files', 'data-dsha-downloads': true",
    'dsh_client_ui_primitives_1.IconPanelLeftOutline16',
    'dsh_client_ui_primitives_1.IconFolderOpenOutline16',
    'dsh_client_ui_primitives_1.IconDownloadOutline16',
    'dsh_client_ui_primitives_1.IconPaperclipOutline16',
    'panel.getBoundingClientRect().left < window.innerWidth',
    'transform: scale(.66)',
    // 3.2.2 代的 DSHA 本地补丁：上游 3.3.1 已接管，或 build336 按「完全追随上游」删除。
    'DSHA_WIDE_TOUCH_ROW_ACTIONS_V1',
    'DSHA_BACKDROP_PRESS_V1',
    'DSHA_SESSION_INPUT_V4',
    'DSHA_FONT_FAMILY_ROW_V1',
    'var(--dsha-mobile-visible-vh, 100vh)',
  ]) assert.equal(source.includes(stale), false, stale);
  // 上游自己的手机搜索隐藏与会话打开流程保持原字节（C 基线不再接管）。
  assert.ok(source.includes('[class*="_searchRow"]'));
  assert.ok(source.includes("// DSHA detail=0 follows the host's keyboard/programmatic open path."));
  assert.equal(packageJson.peerDependencies['@deepseek-ai/dsh-client-runtime'], undefined);
  assert.ok(packageJson.peerDependencies['@deepseek-ai/dsh-client-ui-conversation'].includes('>=0.2.2-rc.1 <0.3.0-0'));
  // react 在宿主运行时树里没有实体：3.4.0/3.3.0 把它当必选 peer 会让依赖门槛判 missing，
  // 上游 3.3.1（c41f65c + be9b7fc）改为 optional，必须保持。
  assert.equal(packageJson.peerDependenciesMeta.react.optional, true);
  assert.equal(packageJson.peerDependencies['@deepseek-ai/dsh-client-ui-sidebar-right'], undefined);
  assert.ok(source.includes('function openFilesPanel'));
});

test('3.3.5 侧栏与文件面板手势使用同一方向/速度门槛', () => {
  const {modules} = loadModules();
  const swipe = {};
  modules['effects/sidebar-swipe.js'](() => ({}), {}, swipe);
  // issue #268（上游 3.3.4）：起点区退役，全屏按方向定族。
  assert.equal(swipe.openStateStartMode(true, false), 'drawer');
  assert.equal(swipe.openStateStartMode(false, true), 'files');
  assert.equal(swipe.openStateStartMode(true, true), 'drawer');
  assert.equal(swipe.closedStateStrokeMode(true), 'drawer');
  assert.equal(swipe.closedStateStrokeMode(false), 'files');
  assert.equal('startZonePxFor' in swipe, false);
  assert.equal('hitTestStart' in swipe, false);
  assert.equal(swipe.classifySwipe({lockPx: 8, drawerOpen: false, viewportWidthPx: 390,
    openDistanceRatio: .16, openVelocity: .45}, {dx: 70, dy: 3, velX: 0}, false), 'open');
  assert.equal(swipe.classifySwipe({lockPx: 8, drawerOpen: true, viewportWidthPx: 390,
    closeDistanceRatio: .13, closeVelocity: .45}, {dx: -60, dy: 2, velX: 0}, false), 'close');
  assert.equal(swipe.classifyFilesSwipe({lockPx: 8, panelOpen: false, drawerOpen: false,
    viewportWidthPx: 390, distanceRatio: .16, velocity: .45}, {dx: -70, dy: 2, velX: 0}, false), 'files');
  assert.equal(swipe.slidingVelocity([{x: 0, t: 900}, {x: 20, t: 940}, {x: 50, t: 1000}], 60, 1000), .5);
});

test('3.3.5 拖动跟随与水平滚动容器判定仍由纯函数负责', () => {
  const {modules} = loadModules();
  const swipe = {};
  modules['effects/sidebar-swipe.js'](() => ({}), {}, swipe);
  // 抽屉开：向左拖才跟随关闭；向右拖返回 null（留给原 A 档关闭）。
  assert.equal(swipe.followTranslate(-308, -40, false, true), -40);
  assert.equal(swipe.followTranslate(-308, 40, false, true), null);
  // 抽屉关：向右拖沿开槽跟随；超过槽宽钳在静止位。
  assert.equal(swipe.followTranslate(-308, 40, false, false), -268);
  assert.equal(swipe.followTranslate(-308, 999, false, false), 0);
  assert.equal(swipe.followTranslate(-308, -1, false, false), null);
  // 开向跟随保持上游的百分比槽（-110%），不用像素基线。
  assert.equal(swipe.followOpenTransform(0, false), null);
  assert.equal(swipe.followOpenTransform(30, false), 'translateX(min(0px, calc(-101% + 30px)))');
  const chain = (overflowX, scrollWidth, clientWidth, parent = null) => ({parent, scrollWidth, clientWidth, overflowX});
  assert.equal(swipe.findHorizontalScroller(null), null);
  assert.equal(swipe.findHorizontalScroller(chain('hidden', 500, 200)), null);
  const scroller = chain('auto', 500, 300, chain('visible', 0, 0));
  assert.equal(swipe.findHorizontalScroller(chain('visible', 400, 300, scroller)), scroller);
});

test('3.3.5 DOM reconciler 按 dirty scope 合并到一帧', () => {
  const {modules} = loadModules();
  const reconciler = {};
  modules['core/reconciler-core.js'](() => ({}), {}, reconciler);
  let frames = 0, all = 0, classes = 0, queued = null;
  const core = reconciler.createReconcilerCore({requestFrame(run) {
    frames++; queued = run; return () => { queued = null; };
  }});
  core.register({name: 'all', ensure: () => { all++; }, dispose: () => {}});
  core.register({name: 'class', scopes: ['class'], ensure: () => { classes++; }, dispose: () => {}});
  core.activate();
  assert.equal(all, 1); assert.equal(classes, 1);
  core.note(['text']);
  queued();
  assert.equal(frames, 1); assert.equal(all, 2); assert.equal(classes, 1);
  core.note(['class', 'style']);
  queued();
  assert.equal(frames, 2); assert.equal(all, 3); assert.equal(classes, 2);
  core.deactivate();
});

// 验证实际部署 bundle 中的新缩放模块，不以下载到的源码代替产物。
test('3.3.5 原图缩放保持触点锚定，缩回原尺寸必须清除平移', () => {
  const {modules} = loadModules();
  const zoom = {};
  modules['core/image-zoom-core.js'](() => ({}), {}, zoom);
  const before = {scale:1.5, x:12, y:-8}, anchor = {x:40, y:-24};
  const local = {x:(anchor.x-before.x)/before.scale, y:(anchor.y-before.y)/before.scale};
  const after = zoom.zoomAround(before, 1.6, anchor);
  assert.ok(Math.abs(after.x + after.scale*local.x - anchor.x) < 1e-9);
  assert.ok(Math.abs(after.y + after.scale*local.y - anchor.y) < 1e-9);
  const reset = zoom.zoomAround(after, .01, anchor);
  assert.equal(reset.scale, 1); assert.equal(reset.x, 0); assert.equal(reset.y, 0);
  const invalid = zoom.zoomAround(before, Number.NaN, anchor);
  assert.equal(invalid.scale, before.scale); assert.equal(invalid.x, before.x); assert.equal(invalid.y, before.y);
});

test('3.3.5 缩放平移受实际可见边界限制，双击第二次返回原图', () => {
  const {modules} = loadModules();
  const zoom = {};
  modules['core/image-zoom-core.js'](() => ({}), {}, zoom);
  const clamped = zoom.clampTranslation({scale:4, x:9999, y:-9999}, {width:300,height:600});
  assert.equal(clamped.x, 450); assert.equal(clamped.y, -900);
  assert.equal(zoom.clampScale(Number.NaN), 1); assert.equal(zoom.clampScale(99), 4);
  assert.equal(zoom.pinchFactor({x:0,y:0}, {x:10,y:10}), 1);
  const doubleTap = zoom.doubleTapTarget(zoom.identityZoom());
  assert.equal(doubleTap.scale, 2.5);
  const reset = zoom.doubleTapTarget(doubleTap);
  assert.equal(reset.scale, 1); assert.equal(reset.x, 0); assert.equal(reset.y, 0);
});
