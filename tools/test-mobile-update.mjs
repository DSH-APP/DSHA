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

test('3.0.3 上游 bundle 保留 DSHA 移动端入口与头部让位', () => {
  assert.equal(packageJson.version, '3.0.3');
  assert.equal(packageJson.dshaUpstream.commit, '881415ffe4daeab1723230e8940f87caeec09348');
  assert.equal(packageJson.dshaUpstream.clientSha256, '967241b6c7447eff4f2c85454c88730a5a361adc6502cb9d1f4807735b13fba4');
  for (const marker of [
    'DSHA_SESSION_INTERACTION_V1',
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
    'top: 42px !important',
    'transform: rotate(180deg) !important',
  ]) assert.ok(source.includes(marker), marker);
  for (const stale of [
    'dsh_client_ui_primitives_1.IconPanelLeftOutline16',
    'dsh_client_ui_primitives_1.IconFolderOpenOutline16',
    'dsh_client_ui_primitives_1.IconDownloadOutline16',
    'dsh_client_ui_primitives_1.IconPaperclipOutline16',
  ]) assert.equal(source.includes(stale), false, stale);
  assert.equal(packageJson.peerDependencies['@deepseek-ai/dsh-client-runtime'].includes('<0.2.0'), true);
  assert.equal(packageJson.peerDependencies['@deepseek-ai/dsh-client-ui-sidebar-right'], undefined);
  assert.ok(source.includes('function openFilesPanel'));
});

test('3.0.3 侧栏与文件面板手势使用同一方向/速度门槛', () => {
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

test('3.0.3 拖动让位与水平滚动容器判定仍由纯函数负责', () => {
  const {modules} = loadModules();
  const swipe = {};
  modules['effects/sidebar-swipe.js'](() => ({}), {}, swipe);
  assert.equal(swipe.hitTestStart(20, 390, false, {startZonePx: 176}), true);
  assert.equal(swipe.hitTestStart(200, 390, false, {startZonePx: 176}), false);
});

test('3.0.3 DOM reconciler 按 dirty scope 合并到一帧', () => {
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
