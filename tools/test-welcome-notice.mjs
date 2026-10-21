// 使用真实锁定 rc2 说明、ConfigForm 写队列/镜像、snapshot store、React 和 Modal。
// 仅控制宿主 RPC 传输边界，不生成 APK，不写真实配置或设备数据。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {execFileSync, spawnSync} from 'node:child_process';
import {browserFixture} from './rc1-browser-fixture.mjs';
import {testRuntime} from './test-runtime-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..');
const runtime = testRuntime('raw');
const recipePath = path.join(root, 'app/src/main/assets/welcome-notice-patch.json');
const recipe = JSON.parse(fs.readFileSync(recipePath, 'utf8'));
const moduleFile = path.join(runtime, 'node_modules', recipe.module);
const sha = value => createHash('sha256').update(value).digest('hex');
const canonical = fs.readFileSync(moduleFile, 'utf8');
assert.equal(sha(canonical), recipe.sourceSha256);
const lock = JSON.parse(fs.readFileSync(path.join(root, 'tools/dsh-runtime/package-lock.json'), 'utf8'));
assert.equal(recipe.packageIntegrity, lock.packages['node_modules/@deepseek-ai/dsh-client-ui-settings-models'].integrity);
const python = process.env.DSHA_PYTHON || 'python';
execFileSync(python, ['-B', path.join(root, 'tools/prepare-welcome-notice-patch.py'), '--check', '--module', moduleFile], {windowsHide: true});
const changedInput = path.join(fs.mkdtempSync(path.join(root, 'app/build/welcome-input-changed-')), 'client.js');
fs.writeFileSync(changedInput, canonical + '\n// changed official input\n');
const changed = spawnSync(python, ['-B', path.join(root, 'tools/prepare-welcome-notice-patch.py'), '--check', '--module', changedInput], {encoding: 'utf8', windowsHide: true});
assert.notEqual(changed.status, 0);
assert.match(changed.stderr, /WELCOME_NOTICE_OFFICIAL_SOURCE_CHANGED/);
let transformed = canonical;
for (const patch of recipe.patches) {
  assert.equal(transformed.split(patch.before).length, 2, 'unique original anchor');
  transformed = transformed.replace(patch.before, patch.after);
}
const registry = JSON.parse(fs.readFileSync(path.join(root, 'app/src/main/assets/runtime-patches.json'), 'utf8'));
assert.equal(registry.active.filter(row => row.asset === 'welcome-notice-patch.json').length, 1);
const contract = JSON.parse(fs.readFileSync(path.join(root, 'app/src/main/assets/managed-runtime-inputs.json'), 'utf8'));
assert(contract.assetFiles.includes('welcome-notice-patch.json'));
for (const source of ['tools/prepare-welcome-notice-patch.py', 'tools/welcome-notice/WelcomeNotice.js', 'tools/welcome-notice/WelcomeNoticeStore.js']) assert(contract.generatorSources.includes(source));
if (process.argv.includes('--verify-managed')) {
  const managed = testRuntime('managed');
  assert.equal(fs.readFileSync(path.join(managed, 'node_modules', recipe.module), 'utf8'), transformed,
    'managed module must equal the whole production recipe result');
}
assert(process.argv.slice(2).every(argument => argument === '--verify-managed'));
const fixture = await browserFixture(runtime);
const page = fixture.page;
let cases = 0;
const sourceInputs = {
  models: recipe.sourceSha256,
  settings: sha(fs.readFileSync(path.join(runtime, 'node_modules/@deepseek-ai/dsh-client-ui-settings/lib/client.js'))),
  recipe: sha(fs.readFileSync(recipePath))
};
async function install() {
  await fixture.load('dsh-client-ui-settings', [], 'module.exports.audit={ConfigFormController,SettingsDescribeMirror};');
  await fixture.load('dsh-client-ui-settings-models', [recipe],
    'module.exports.audit={WelcomeNoticeStore,WelcomeNotice,OnboardingModal,en,zh,WELCOME_NOTICE_VERSION};');
  await page.evaluate(() => {
    const React = auditModules.react;
    const {createRoot} = auditModules['react-dom/client'];
    const {ConfigFormController,SettingsDescribeMirror} = auditExports['@deepseek-ai/dsh-client-ui-settings'].audit;
    const api = auditExports['@deepseek-ai/dsh-client-ui-settings-models'].audit;
    window.auditUnhandled = [];
    window.addEventListener('unhandledrejection', event => auditUnhandled.push(String(event.reason)));
    window.setupWelcome = async ({keep = false, unavailable = false, unavailableOnce = false, delayedDescribe = false, writable = true, language = 'en', initialInert = false} = {}) => {
      window.testRoot?.unmount();
      window.controller?.dispose();
      if (window.form) await window.form.dispose();
      document.getElementById('root').inert = initialInert;
      if (!keep) localStorage.removeItem('welcome-fixture-durable');
      window.wire = [];
      window.completed = 0;
      window.describeReads = 0;
      window.hostRevision = 0;
      window.holdDescribe = false;
      const ns = 'ui-settings-general';
      const persisted = () => JSON.parse(localStorage.getItem('welcome-fixture-durable') || '{}');
      const namespace = () => ({ns, value: persisted(), user: persisted(), base: {}, revision: 'fixture-' + hostRevision});
      const ctx = {remote: {settings: {
        describe: async () => {
          ++window.describeReads;
          if (delayedDescribe && describeReads === 1) await new Promise(resolve => { window.releaseDescribe = resolve; });
          if (window.holdDescribe) await new Promise(resolve => { window.releaseCurrentDescribe = resolve; });
          return {ok: true, value: {writable, namespaces: unavailable || unavailableOnce && describeReads === 1 ? [] : [namespace()]}};
        },
        mutate: (namespaceId, ops, revision) => new Promise((resolve, reject) => {
          wire.push({namespaceId, ops, revision, resolve, reject});
        })
      }}};
      window.mirror = new SettingsDescribeMirror(ctx);
      const initialRead = mirror.load();
      if (!delayedDescribe) await initialRead;
      // 使用合法的已解码说明命名空间，写队列及失败恢复均运行生产实现。
      window.form = new ConfigFormController(ctx, {namespace: ns, decode: value => value}, mirror, {});
      window.controller = new api.WelcomeNoticeStore(form, mirror);
      window.testRoot = createRoot(document.getElementById('root'));
      function Demo() {
        const [done, setDone] = React.useState(false);
        return done ? React.createElement('button', {id: 'after-welcome'}, 'Entered') : React.createElement(api.WelcomeNotice, {
          controller,
          complete: () => { ++window.completed; setDone(true); },
          useWelcome: selector => selector(React.useSyncExternalStore(controller.store.subscribe, controller.store.getSnapshot)),
          t: key => api[language][key]
        });
      }
      window.testRoot.render(React.createElement(Demo));
      window.settleWelcome = (index, result) => {
        const call = wire[index];
        if (call.settled) throw Error('duplicate test settlement');
        call.settled = true;
        if (result === 'reject') { call.reject(Error('controlled transport rejection')); return; }
        if (result === 'refuse') { call.resolve({ok: false, error: {message: 'controlled refusal'}}); return; }
        if (result === 'conflict') { ++hostRevision; call.resolve({ok: false, error: {code: 'settings/conflict', message: 'controlled changed revision'}}); return; }
        if (result === 'empty-success') { call.resolve({ok: true, value: namespace()}); return; }
        const value = persisted();
        for (const op of call.ops) value[op.path[0]] = op.value;
        localStorage.setItem('welcome-fixture-durable', JSON.stringify(value));
        ++hostRevision;
        call.resolve({ok: true, value: namespace()});
      };
      window.noticeState = () => controller.store.getSnapshot();
    };
  });
}
async function setup(options = {}) {
  const {expectNotice = true, ...settings} = options;
  await page.evaluate(options => setupWelcome(options), settings);
  if (expectNotice) await page.getByRole('dialog').waitFor();
}
async function begin() {
  await page.getByRole('button', {name: 'Continue', exact: true}).click();
  await page.waitForFunction(() => wire.length === 1);
  assert.equal(await page.getByRole('button', {name: 'Saving…', exact: true}).isDisabled(), true);
  assert.equal(await page.evaluate(() => document.getElementById('root').inert), true);
}
async function settled(index, result) {
  await page.evaluate(({index, result}) => settleWelcome(index, result), {index, result});
}
async function state() { return page.evaluate(() => noticeState()); }
async function entered() {
  await page.locator('#after-welcome').waitFor();
  assert.equal(await page.evaluate(() => completed), 1);
  assert.equal(await page.evaluate(() => document.getElementById('root').inert), false);
  assert.equal(await page.getByRole('dialog').count(), 0);
}
async function noticeLayout() {
  const layout = await page.evaluate(() => {
    const dialog = document.querySelector('[role="dialog"]');
    const rect = dialog.getBoundingClientRect();
    return {
      horizontalPageOverflow: document.documentElement.scrollWidth > innerWidth + 1,
      horizontalDialogOverflow: dialog.scrollWidth > dialog.clientWidth + 1,
      outsideButtons: [...dialog.querySelectorAll('button')].filter(button => {
        const box = button.getBoundingClientRect();
        return box.left < rect.left - 1 || box.right > rect.right + 1;
      }).map(button => button.textContent)
    };
  });
  assert.deepEqual(layout, {horizontalPageOverflow: false, horizontalDialogOverflow: false, outsideButtons: []});
}
try {
  await install();
  await setup();
  await noticeLayout();
  await begin();
  await settled(0, 'success');
  await entered();
  assert.equal((await state()).acknowledged, true);
  assert.equal((await state()).status, 'ready');
  assert.equal((await state()).sessionOnly, false);
  ++cases;

  // 真正 Host describe 推送已包含确认值，而原 mutate 回答仍未决：
  // 保存完成以持久配置为准，不能被等待 RPC 的本地 saving 位挡住。
  await setup(); await begin();
  await page.evaluate(() => {
    localStorage.setItem('welcome-fixture-durable', JSON.stringify({welcomeNoticeVersion:'2026-09-28.1'}));
    return mirror.load();
  });
  await entered();
  assert.equal((await state()).acknowledged, true);
  assert.equal(await page.evaluate(() => wire[0].settled === undefined), true, 'mutate remains pending');
  const hostConfirmed = await state();
  await settled(0, 'reject');
  await page.waitForFunction(() => controller.saving === false);
  assert.deepEqual(await state(), hostConfirmed, 'late transport rejection cannot erase authoritative Host confirmation');
  assert.equal(await page.evaluate(() => completed), 1, 'late RPC answer cannot enter twice');
  ++cases;

  // 未确认/无关字段推送不能将未决保存标成完成。
  await setup(); await begin();
  await page.evaluate(() => mirror.acceptView({ns:'ui-settings-general', value:{unrelated:true}, revision:1}));
  assert.equal((await state()).status, 'saving');
  assert.equal((await state()).acknowledged, false);
  assert.equal(await page.evaluate(() => completed), 0);
  await settled(0, 'success'); await entered();
  ++cases;

  // 已返回说明期间旧 Host 请求实际保存，重试读取已验证镜像即可；
  // 不把同一确认排在旧未决 wire 后方再次等待。
  await setup(); await begin();
  await page.getByRole('button', {name: 'Return to notice', exact: true}).click();
  await page.evaluate(() => {
    localStorage.setItem('welcome-fixture-durable', JSON.stringify({welcomeNoticeVersion:'2026-09-28.1'}));
    return mirror.load();
  });
  assert.equal((await state()).acknowledged, false, 'explicit return remains detached until retry');
  await page.getByRole('button', {name: 'Retry save', exact: true}).click();
  await entered();
  assert.equal(await page.evaluate(() => wire.length), 1, 'authoritative confirmation needs no duplicate mutation');
  await settled(0, 'success');
  ++cases;

  // 真实表单队列直接抛错后允许重试，点击处理器无未处理的 Promise。
  await setup(); await begin(); await settled(0, 'reject');
  await page.getByRole('button', {name: 'Retry save', exact: true}).waitFor();
  assert.equal((await state()).status, 'error');
  assert.equal((await state()).error, 'save-exception');
  assert.equal(await page.evaluate(() => controller.saving), false);
  await page.getByRole('button', {name: 'Retry save', exact: true}).click();
  await page.waitForFunction(() => wire.length === 2);
  await settled(1, 'success'); await entered();
  ++cases;

  // 结构化拒绝执行真实 ConfigForm 恢复读取，并进入可重试错误终态。
  await setup(); await begin(); await settled(0, 'refuse');
  await page.getByRole('button', {name: 'Retry save', exact: true}).waitFor();
  assert.equal((await state()).error, 'save-refused');
  assert.equal((await state()).acknowledged, false);
  assert.equal(await page.evaluate(() => describeReads), 2);
  ++cases;

  // 传输成功但确认字段没有持久化，不能当作已保存。
  await setup(); await begin(); await settled(0, 'empty-success');
  await page.getByRole('button', {name: 'Retry save', exact: true}).waitFor();
  assert.equal((await state()).error, 'save-not-persisted');
  assert.equal((await state()).acknowledged, false);
  ++cases;

  // 初次 describe 暂缺命名空间时，只刷新官方共享镜像，不提前写入或伪造已读。
  await setup({unavailableOnce: true});
  await page.getByRole('button', {name: 'Continue', exact: true}).waitFor();
  assert.equal(await page.evaluate(() => describeReads), 2);
  assert.equal(await page.evaluate(() => wire.length), 0);
  assert.equal((await state()).acknowledged, false);
  await begin(); await settled(0, 'success'); await entered();
  ++cases;

  // 明确拒绝后只有真实恢复读显示 revision 变化才有限重试同一确认；最多两次。
  await setup(); await begin(); await settled(0, 'conflict');
  await page.waitForFunction(() => wire.length === 2);
  assert.equal((await state()).attempts, 2);
  await settled(1, 'success'); await entered();
  assert.equal(await page.evaluate(() => wire.length), 2);
  ++cases;
  await setup(); await begin(); await settled(0, 'conflict');
  await page.waitForFunction(() => wire.length === 2);
  await settled(1, 'conflict');
  await page.getByRole('button', {name: 'Retry save', exact: true}).waitFor();
  assert.equal((await state()).error, 'save-refused');
  assert.equal(await page.evaluate(() => wire.length), 2, 'no unbounded conflict loop');
  ++cases;

  // Host 为只读时即使有旧 ready 视图，也不能发起写入。
  await setup({writable: false});
  await page.getByRole('button', {name: 'Continue', exact: true}).click();
  await page.getByRole('button', {name: 'Retry save', exact: true}).waitFor();
  assert.equal((await state()).error, 'settings-read-only');
  assert.equal(await page.evaluate(() => wire.length), 0);
  assert.equal((await state()).acknowledged, false);
  ++cases;

  // 宿主一直未决时仍保留出口，不凭空宣称超时或取消。
  await page.clock.install();

  // 首次 Host 读取没有回答时有界显示可重试说明；晚到真实就绪后仍无默认已读。
  await setup({delayedDescribe: true, expectNotice: false});
  await page.waitForFunction(() => controller.following && typeof releaseDescribe === 'function');
  await page.clock.fastForward(9000);
  await page.getByRole('alert').waitFor();
  assert.equal((await state()).error, 'settings-not-ready');
  assert.equal(await page.evaluate(() => wire.length), 0);
  await page.evaluate(() => releaseDescribe());
  await page.getByRole('button', {name: 'Continue', exact: true}).waitFor();
  assert.equal((await state()).acknowledged, false);
  await begin(); await settled(0, 'success'); await entered();
  ++cases;

  // 传输异常后的核实读也有界；读超时不重发写入，晚到真实确认才能关闭说明。
  await setup(); await begin();
  await page.evaluate(() => { window.holdDescribe = true; });
  await settled(0, 'reject');
  await page.waitForFunction(() => noticeState().phase === 'verify' && typeof releaseCurrentDescribe === 'function');
  await page.clock.fastForward(9000);
  await page.getByRole('alert').waitFor();
  assert.equal((await state()).error, 'save-exception');
  assert.equal((await state()).acknowledged, false);
  assert.equal(await page.evaluate(() => wire.length), 1);
  await page.evaluate(() => {
    localStorage.setItem('welcome-fixture-durable', JSON.stringify({welcomeNoticeVersion:'2026-09-28.1'}));
    window.holdDescribe = false; releaseCurrentDescribe();
  });
  await entered();
  assert.equal(await page.evaluate(() => wire.length), 1, 'unknown result never replays the mutation');
  ++cases;

  await setup(); await begin();
  await page.clock.fastForward(120000);
  await page.getByRole('status').waitFor();
  assert.match(await page.getByRole('status').textContent(), /does not cancel the save request/);
  await page.getByText('Save diagnostics', {exact: true}).click();
  assert.match(await page.locator('details pre').textContent(), /"status": "saving"/);
  assert.match(await page.locator('details pre').textContent(), /"hostRequestMayStillComplete": true/);
  assert((JSON.parse(await page.locator('details pre').textContent()).elapsedSeconds) >= 120,
    'two minutes of browser-clock time must retain a usable pending state, without cancelling the Host');
  assert.equal(await page.evaluate(() => wire.length), 1);
  assert.equal(await page.getByRole('button', {name: 'Return to notice', exact: true}).isEnabled(), true);
  await page.getByRole('button', {name: 'I have read this; enter this visit only (not saved)', exact: true}).click();
  await entered();
  const temporary = await state();
  assert.equal(temporary.acknowledged, false);
  assert.equal(temporary.sessionOnly, true);
  assert.equal(await page.evaluate(() => localStorage.getItem('welcome-fixture-durable')), null);
  // 宿主之后确实成功可持久化，但不能改写本次已经明确选择的临时状态。
  await settled(0, 'success');
  await page.waitForFunction(() => form.pendingRevision === undefined && controller.saving === false);
  assert.deepEqual(await state(), temporary);
  assert.equal(await page.evaluate(() => completed), 1);
  ++cases;

  // 返回说明仅使旧界面请求身份失效；重试仍经过真实表单写队列。
  await setup(); await begin();
  await page.getByRole('button', {name: 'Return to notice', exact: true}).click();
  await page.getByRole('button', {name: 'Retry save', exact: true}).waitFor();
  assert.match(await page.getByRole('alert').textContent(), /was not cancelled/);
  await page.getByRole('button', {name: 'Retry save', exact: true}).click();
  await page.waitForFunction(() => noticeState().status === 'saving' && form.writeGeneration === 2);
  assert.equal(await page.evaluate(() => wire.length), 1, 'the real ConfigForm serializes the second write');
  await settled(0, 'success');
  await page.waitForFunction(() => wire.length === 2);
  assert.equal((await state()).status, 'saving', 'old success cannot finish the newer pending request');
  assert.equal((await state()).acknowledged, false);
  assert.equal(await page.evaluate(() => completed), 0);
  await settled(1, 'reject');
  await entered();
  assert.equal((await state()).acknowledged, true, 'a recovery read proves the older identical acknowledgement really persisted');
  assert.equal(await page.evaluate(() => wire.length), 2, 'unknown outcome is not automatically replayed');
  ++cases;

  // 重新进入读取宿主实际保存内容：允许先前真实成功的写入已经持久化。
  await setup({keep: true, expectNotice: false});
  await page.locator('#after-welcome').waitFor();
  assert.equal((await state()).acknowledged, true);
  assert.equal((await state()).sessionOnly, false);
  assert.equal(await page.getByRole('dialog').count(), 0);
  ++cases;

  // 未发起写入的临时进入经过真实浏览器刷新后，必须再次显示说明。
  await setup();
  await page.getByRole('button', {name: 'I have read this; enter this visit only (not saved)', exact: true}).click();
  await entered();
  assert.equal(await page.evaluate(() => wire.length), 0);
  assert.deepEqual(await page.evaluate(() => auditUnhandled), []);
  await page.reload(); await page.waitForFunction(() => globalThis.auditModules);
  await install(); await setup({keep: true});
  assert.equal((await state()).acknowledged, false);
  assert.equal((await state()).sessionOnly, false);
  ++cases;

  // 保存期间卸载恢复原 inert 值，晚到回调不能推进已经卸载的界面。
  for (const initialInert of [false, true]) {
    await setup({initialInert});
    await begin();
    await page.evaluate(() => testRoot.unmount());
    assert.equal(await page.evaluate(() => document.getElementById('root').inert), initialInert);
    await settled(0, 'success');
    await page.waitForFunction(() => noticeState().acknowledged);
    assert.equal(await page.evaluate(() => completed), 0, 'unmounted handler cannot advance onboarding');
    assert.equal(await page.getByRole('dialog').count(), 0);
    ++cases;
  }

  await setup(); await begin();
  await page.evaluate(() => { testRoot.unmount(); controller.dispose(); });
  const disposed = await state();
  assert.equal(disposed.status, 'error');
  assert.equal(disposed.error, 'disposed-host-request-not-cancelled');
  await settled(0, 'reject');
  assert.deepEqual(await state(), disposed);
  assert.equal(await page.evaluate(() => document.getElementById('root').inert), false);
  ++cases;

  // rc2 只有 Host 配置。真实命名空间缺失时仍允许用户显式仅本次进入，
  // 不用旧版已删除的 memory 构造参数虚构一种可持久化模式。
  await setup({unavailable: true});
  assert.equal(await page.getByRole('button', {name: 'Continue', exact: true}).count(), 0);
  await page.getByRole('button', {name: 'I have read this; enter this visit only (not saved)', exact: true}).click();
  await entered();
  assert.equal((await state()).acknowledged, false);
  assert.equal((await state()).sessionOnly, true);
  assert.equal(await page.evaluate(() => wire.length), 0);
  ++cases;

  // 短屏英文说明与较长的临时进入按钮必须可换行、可操作，不横向溢出。
  await page.setViewportSize({width: 320, height: 480});
  await setup(); await begin();
  await noticeLayout();
  await page.getByRole('button', {name: 'I have read this; enter this visit only (not saved)', exact: true}).click();
  await entered(); await settled(0, 'reject');
  ++cases;

  await setup({language: 'zh'});
  await noticeLayout();
  await page.getByRole('button', {name: '继续', exact: true}).click();
  await page.waitForFunction(() => wire.length === 1);
  await page.getByRole('button', {name: '返回说明', exact: true}).click();
  await page.getByRole('button', {name: '重试保存', exact: true}).waitFor();
  assert.match(await page.getByRole('alert').textContent(), /没有取消/);
  await page.getByRole('button', {name: '我已阅读，仅本次进入（未保存）', exact: true}).click();
  await entered();
  // 结算受控请求以完成测试收尾，临时进入状态仍须明确保留。
  await settled(0, 'reject');
  assert.equal((await state()).acknowledged, false);
  ++cases;
  assert.deepEqual(await page.evaluate(() => auditUnhandled), []);
  assert.deepEqual(fixture.errors, []);
  console.log(JSON.stringify({engine: process.env.DSHA_BROWSER || 'chromium', cases, sourceInputs,
    actualModules: ['WelcomeNotice', 'WelcomeNoticeStore', 'ConfigFormController', 'SettingsDescribeMirror', 'createSnapshotStore', 'React', 'Modal'],
    hostRpc: 'controlled', pendingBrowserClockSeconds: 120, deviceValidation: false}));
} finally {
  await fixture.close();
}
