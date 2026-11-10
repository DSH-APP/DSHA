// 首次进入页面的连接提示：首个连接静默，真实断线恢复仍显示黄→绿。
// 直接在锁定的 0.2.2 raw 模块上应用 APK 同一份受管补丁，再执行生产状态迁移；
// 不渲染完整设置面板，也不访问设备或网络。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import test from 'node:test';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {testRuntime} from './test-runtime-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..');
const assets = path.join(root, 'app/src/main/assets');
const recipeFile = path.join(assets, 'first-connect-indicator-patch.json');
const recipe = JSON.parse(fs.readFileSync(recipeFile, 'utf8'));
const registry = JSON.parse(fs.readFileSync(path.join(assets, 'runtime-patches.json'), 'utf8'));
const runtime = testRuntime('raw');
const rawFile = path.join(runtime, 'node_modules', recipe.module);
const original = fs.readFileSync(rawFile, 'utf8');
const sha = (text) => createHash('sha256').update(text).digest('hex');
const currentVersion = JSON.parse(
  fs.readFileSync(path.join(root, 'tools/dsh-runtime/package.json'), 'utf8'),
).dependencies['@deepseek-ai/dsh'];

test('受管补丁登记与锁定模块逐字节一致', () => {
  assert.equal(recipe.version, 1);
  assert.equal(recipe.dshVersion, currentVersion);
  assert.equal(registry.dshVersion, currentVersion);
  const entry = registry.active.filter((row) => row.asset === path.basename(recipeFile));
  assert.deepEqual(entry, [
    {
      asset: 'first-connect-indicator-patch.json',
      module: recipe.module,
      markers: ['DSHA_FIRST_CONNECT_SILENT_V1'],
    },
  ]);
  const lock = JSON.parse(
    fs.readFileSync(path.join(root, 'tools/dsh-runtime/package-lock.json'), 'utf8'),
  );
  assert.equal(
    recipe.packageIntegrity,
    lock.packages['node_modules/@deepseek-ai/dsh-client-ui-settings-general'].integrity,
  );
  assert.equal(sha(original), recipe.sourceSha256);
});

function applyRecipe(source, patches) {
  let text = source;
  for (const [index, patch] of patches.entries()) {
    assert.equal(text.split(patch.before).length - 1, 1, `锚点 ${index + 1} 必须唯一`);
    assert.ok(!text.includes(patch.after), `锚点 ${index + 1} 不得重复套用`);
    text = text.replace(patch.before, patch.after);
  }
  return text;
}

const patched = applyRecipe(original, recipe.patches);

test('完整客户端补丁后可解析，归档补丁链整体应用且幂等', () => {
  assert.ok(patched.includes('DSHA_FIRST_CONNECT_SILENT_V1'));
  const parse = spawnSync(process.execPath, ['--check', '--input-type=commonjs'], {
    input: patched,
    encoding: 'utf8',
    windowsHide: true,
  });
  assert.equal(parse.status, 0, parse.stderr);
  const probe = spawnSync(
    process.env.DSHA_PYTHON ?? (process.platform === 'win32' ? 'python' : 'python3'),
    [
      '-B',
      '-c',
      'import json,sys\n' +
        "from pathlib import Path\n" +
        "sys.path.insert(0,'tools')\n" +
        'from runtime_stable_patches import apply\n' +
        "p=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))\n" +
        'data=Path(sys.argv[2]).read_bytes()\n' +
        "chain=[(sys.argv[1],[(x['before'],x['after']) for x in p['patches']],['DSHA_FIRST_CONNECT_SILENT_V1'])]\n" +
        'out=apply(data,chain)\n' +
        'assert out!=data\n' +
        'assert apply(out,chain)==out\n' +
        "print('PASS_ARCHIVE_APPLY_AND_IDEMPOTENCE')",
      recipeFile,
      rawFile,
    ],
    {encoding: 'utf8', windowsHide: true},
  );
  assert.equal(probe.status, 0, probe.stderr);
  assert.match(probe.stdout, /PASS_ARCHIVE_APPLY_AND_IDEMPOTENCE/);
});

function extractFunction(source, name) {
  const start = source.indexOf(`function ${name}(`);
  assert.ok(start > 0, `缺少 ${name}`);
  const end = source.indexOf('\n\t\t}', start);
  assert.ok(end > start, `${name} 未闭合`);
  return source.slice(start, end + '\n\t\t}'.length);
}

const sandbox = {};
vm.createContext(sandbox);
vm.runInContext(
  `${extractFunction(patched, 'dshaAdvanceFirstConnect')}\n` +
    `${extractFunction(patched, 'dshaConnectionIndicator')}\n` +
    'globalThis.advance = dshaAdvanceFirstConnect;\n' +
    'globalThis.indicator = dshaConnectionIndicator;\n',
  sandbox,
);
const {advance, indicator} = sandbox;

test('首次进入时仍处于 connecting：首连不显示黄色或绿色提示', () => {
  assert.match(patched, /const firstConnectPending = \(0, react\.useRef\)\(!hasConnectedOnceInPage\);/);
  assert.match(
    patched,
    /if \(!firstConnectPending\.current\) connectingShownAt\.current = Date\.now\(\);/,
  );
  assert.match(
    patched,
    /const connectionIndicator = dshaConnectionIndicator\(connectionState, holdConnecting, showRecovery, firstConnectPending\.current\);/,
  );
  const page = {hasConnectedOnce: false};
  const view = createConnection(page);
  assert.equal(view.mount('connecting'), undefined);
  assert.equal(view.pending, true);
  view.tick(300);
  const connected = view.change('connected');
  assert.deepEqual(connected, {rendered: undefined, holdArmed: false});
  assert.equal(view.pending, false);
  assert.equal(page.hasConnectedOnce, true);
  assert.equal(view.showRecovery, false);
  assert.equal(view.render('connected'), undefined);
});

test('首次连接成功前失败重试不会被当成恢复', () => {
  const page = {hasConnectedOnce: false};
  const view = createConnection(page);
  view.mount('connecting');
  assert.equal(view.change('disconnected').rendered, 'disconnected');
  assert.equal(view.pending, true);
  assert.equal(view.change('connecting').rendered, undefined);
  assert.equal(view.change('connected').rendered, undefined);
  assert.equal(view.showRecovery, false);
});

test('曾经连上后真实断开恢复仍显示黄 → 绿', () => {
  const page = {hasConnectedOnce: false};
  const view = createConnection(page);
  view.mount('connecting');
  view.tick(100);
  view.change('connected');
  view.tick(1000);
  assert.equal(view.change('disconnected').rendered, 'disconnected');
  view.tick(1000);
  assert.equal(view.change('connecting').rendered, 'connecting');
  view.tick(200);
  assert.equal(view.change('connected').holdArmed, true);
  assert.equal(view.render('connected'), 'connecting');
  view.holdExpired();
  assert.equal(view.render('connected'), 'recovered');
  view.recoveredExpired();
  assert.equal(view.render('connected'), undefined);
});

test('连上后断开期间重新挂载：重连仍显示黄 → 绿', () => {
  const page = {hasConnectedOnce: false};
  const first = createConnection(page);
  first.mount('connecting');
  first.tick(50);
  first.change('connected');
  first.tick(500);
  first.change('disconnected');
  const remounted = createConnection(page);
  assert.equal(remounted.mount('disconnected'), 'disconnected');
  assert.equal(remounted.pending, false);
  remounted.tick(600);
  assert.equal(remounted.change('connecting').rendered, 'connecting');
  remounted.tick(100);
  assert.equal(remounted.change('connected').holdArmed, true);
  assert.equal(remounted.render('connected'), 'connecting');
  remounted.holdExpired();
  assert.equal(remounted.render('connected'), 'recovered');
});

test('挂载时已经 connected 的首次观察也不显示绿色', () => {
  const page = {hasConnectedOnce: false};
  const view = createConnection(page);
  assert.equal(view.mount('connected'), undefined);
  assert.equal(view.pending, false);
  assert.equal(page.hasConnectedOnce, true);
  assert.equal(view.showRecovery, false);
});

test('恢复提示与最短可见常量保持上游数值', () => {
  assert.match(patched, /const RECOVERY_CONFIRMATION_MS = 2e3;/);
  assert.match(patched, /const CONNECTING_MIN_VISIBLE_MS = 800;/);
});

// 模拟组件挂载、连接状态提交和两个 layout effect；常量从生产源静态核对。
function createConnection(page) {
  let now = 0;
  let previous;
  let pending;
  let showRecovery = false;
  let holdConnecting = false;
  let connectingShownAt;
  const render = (state) => indicator(state, holdConnecting, showRecovery, pending);
  const applyAdvance = (state) => {
    const next = advance(previous, state, pending, page.hasConnectedOnce);
    previous = state;
    pending = next.firstConnectPending;
    page.hasConnectedOnce = next.hasConnectedOnce;
    if (next.showRecovery !== void 0) showRecovery = next.showRecovery;
  };
  return {
    get pending() {
      return pending;
    },
    get showRecovery() {
      return showRecovery;
    },
    mount(state) {
      previous = state;
      pending = !page.hasConnectedOnce;
      showRecovery = false;
      holdConnecting = false;
      connectingShownAt = undefined;
      applyAdvance(state);
      if (state === 'connecting' && !pending) connectingShownAt = now;
      return render(state);
    },
    change(state) {
      const rendered = render(state);
      applyAdvance(state);
      let holdArmed = false;
      if (state === 'connecting') {
        if (!pending) connectingShownAt = now;
      } else {
        const shownAt = connectingShownAt;
        connectingShownAt = undefined;
        if (shownAt !== undefined && 800 - (now - shownAt) > 0) {
          holdConnecting = true;
          holdArmed = true;
        }
      }
      return {rendered, holdArmed};
    },
    tick(ms) {
      now += ms;
    },
    holdExpired() {
      holdConnecting = false;
    },
    recoveredExpired() {
      showRecovery = false;
    },
    render,
  };
}
