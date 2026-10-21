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

test('3.2.1 固定上游 bundle 保留 DSHA 移动端入口与头部让位', () => {
  assert.equal(packageJson.version, '3.2.1');
  assert.equal(packageJson.dshaUpstream.commit, '10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4');
  assert.equal(packageJson.dshaUpstream.clientSha256, '411f0626b4b87d40e5046892686a4685feb894b3b0740ef4b5292caedd149a64');
  assert.equal(packageJson.dshaUpstream.ref, 'main');
  assert.equal(packageJson.dshaUpstream.releaseTag, 'v3.2.1');
  assert.equal(packageJson.dshaUpstream.releaseCommit, '1844bf21db47a9b942275b8118988e3f6718cf4b');
  assert.notEqual(packageJson.dshaUpstream.commit, packageJson.dshaUpstream.releaseCommit);
  for (const marker of [
    'DSHA_SESSION_INPUT_V3',
    '[data-dsha-session-select]',
    'dsha-preset-header-anchor',
    'data-mobile-nav="file-upload"',
    'data-sidebar-right-expand',
    'dsh-web-mobile-panel-clearance',
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
  ]) assert.ok(source.includes(marker), marker);
  for (const stale of [
    'dsh_client_ui_primitives_1.IconPanelLeftOutline16',
    'dsh_client_ui_primitives_1.IconFolderOpenOutline16',
    'dsh_client_ui_primitives_1.IconDownloadOutline16',
    'dsh_client_ui_primitives_1.IconPaperclipOutline16',
  ]) assert.equal(source.includes(stale), false, stale);
  assert.equal(packageJson.peerDependencies['@deepseek-ai/dsh-client-runtime'], undefined);
  assert.ok(packageJson.peerDependencies['@deepseek-ai/dsh-client-ui-conversation'].includes('>=0.2.0-rc.1 <0.3.0-0'));
  assert.equal(packageJson.peerDependencies['@deepseek-ai/dsh-client-ui-sidebar-right'], undefined);
  assert.ok(source.includes('function openFilesPanel'));
});

test('3.2.1 侧栏与文件面板手势使用同一方向/速度门槛', () => {
  const {modules} = loadModules();
  const swipe = {};
  modules['effects/sidebar-swipe.js'](() => ({}), {}, swipe);
  assert.equal(swipe.startZonePxFor(390), 176);
  assert.equal(swipe.classifySwipe({lockPx: 8, drawerOpen: false, viewportWidthPx: 390,
    openDistanceRatio: .16, openVelocity: .45}, {dx: 70, dy: 3, velX: 0}, false), 'open');
  assert.equal(swipe.classifySwipe({lockPx: 8, drawerOpen: true, viewportWidthPx: 390,
    closeDistanceRatio: .13, closeVelocity: .45}, {dx: -60, dy: 2, velX: 0}, false), 'close');
  assert.equal(swipe.classifyFilesSwipe({lockPx: 8, panelOpen: false, drawerOpen: false,
    viewportWidthPx: 390, distanceRatio: .16, velocity: .45}, {dx: -70, dy: 2, velX: 0}, false), 'files');
  assert.equal(swipe.slidingVelocity([{x: 0, t: 900}, {x: 20, t: 940}, {x: 50, t: 1000}], 60, 1000), .5);
});

test('3.2.1 拖动让位与水平滚动容器判定仍由纯函数负责', () => {
  const {modules} = loadModules();
  const swipe = {};
  modules['effects/sidebar-swipe.js'](() => ({}), {}, swipe);
  assert.equal(swipe.hitTestStart(20, 390, false, {startZonePx: 176}), true);
  assert.equal(swipe.hitTestStart(200, 390, false, {startZonePx: 176}), false);
});

test('3.2.1 DOM reconciler 按 dirty scope 合并到一帧', () => {
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
test('3.2.1 原图缩放保持触点锚定，缩回原尺寸必须清除平移', () => {
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

test('3.2.1 缩放平移受实际可见边界限制，双击第二次返回原图', () => {
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
