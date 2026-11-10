import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
// The emergency ABI is pinned only when the explicit recovery fixture is used.
// A normal current fixture follows the formal runtime lock; S1 keeps recovery on the same rc1 archive with an independent lock.
const recoveryRuntime = process.env.DSHA_TEST_RECOVERY_RUNTIME;
const expectedDsh = recoveryRuntime
  ? JSON.parse(await readFile(path.join(root, 'tools/recovery-runtime/lock.json'), 'utf8')).dshVersion
  : JSON.parse(await readFile(path.join(root, 'tools/dsh-runtime/package.json'), 'utf8')).dependencies['@deepseek-ai/dsh'];
const fixture = JSON.parse(await readFile(path.join(root, 'app/build/test-runtimes/current.json'), 'utf8'));
const runtime = recoveryRuntime || process.env.DSHA_TEST_RUNTIME || fixture.raw;
const requireRuntime = createRequire(path.join(runtime, 'package.json'));
const load = name => import(pathToFileURL(requireRuntime.resolve(name)).href);
const { Context } = await load('@deepseek-ai/cordis');
const { createScope } = await load('@deepseek-ai/dsh-scope');
const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
const { default: ToolRuntime, defineTool } = await load('@deepseek-ai/dsh-tools');
const NativeExecutor = await load('@deepseek-ai/dsh-tool-executor-native');
const { composeEntries, loadOverlayPatches } = await load('@deepseek-ai/dsh-app-boot');
const actual = JSON.parse(await readFile(path.join(runtime, 'node_modules/@deepseek-ai/dsh/package.json'), 'utf8'));
assert.equal(actual.version, expectedDsh, 'must run the pinned emergency ABI');
const source = (await readFile(path.join(root, 'app/src/main/assets/recovery-agent.js'), 'utf8'))
  .replace("'@deepseek-ai/dsh-tools'", JSON.stringify(pathToFileURL(requireRuntime.resolve('@deepseek-ai/dsh-tools')).href)) + '\n//# sourceURL=recovery-agent-test.js';
const recovery = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

const ctx = new Context();
await ctx.plugin(SystemPrompt, {});
await ctx.plugin(ToolRuntime, {});
await ctx.plugin(NativeExecutor, {});
const requests = [];
const mounted = ctx.plugin({ inject: ['tools'], apply(scope) {
  recovery.registerRecoveryTools(scope, async (route, args) => { requests.push({ route, args }); return { status: 'ok', route }; });
} });
await mounted;
assert.deepEqual(recovery.verifyRoster(ctx.tools), [...recovery.toolNames].sort());
const invoke = async (name, args = {}, agent) => {
  const batch = await ctx.tools.execute({
    calls: [{ id: `test-${name}`, name, arguments: JSON.stringify(args) }],
    ...(agent === undefined ? {} : { agent }),
    signal: new AbortController().signal,
  });
  const result = batch.results[0]?.result;
  return { isError: result?.isError === true, result, batch };
};
const diagnostic = await invoke('dsha_diagnostics');
assert.equal(diagnostic.isError, false);
assert.equal(requests[0].route, 'diagnostics');
const read = await invoke('dsha_read', { targetId: 'profile-one' });
assert.equal(read.isError, false);
assert.deepEqual(requests[1], { route: 'read', args: { targetId: 'profile-one' } });
assert.equal((await invoke('dsha_propose', { action: 'shell', targetId: 'runtime', sourceSha256: 'x', dataGeneration: 'x' })).isError, true);
assert.equal(requests.length, 2, 'schema rejection must occur before broker dispatch');
const cliRepair = await invoke('dsha_propose', {
  action: 'repair-cli-dependencies', targetId: 'runtime', sourceSha256: 'x', dataGeneration: 'x',
});
assert.equal(cliRepair.isError, false, 'pinned CLI dependency repair must be exposed through the existing proposal tool');
assert.deepEqual(requests[2], {
  route: 'propose',
  args: { action: 'repair-cli-dependencies', targetId: 'runtime', sourceSha256: 'x', dataGeneration: 'x' },
});
assert.equal((await invoke('dsha_targets')).isError, false);
assert.deepEqual(requests[3], { route: 'targets', args: {} });
assert.equal((await invoke('dsha_result', { planId: 'fixture-candidate' })).isError, false);
assert.deepEqual(requests[4], { route: 'result', args: { planId: 'fixture-candidate' } });
assert.equal(requests.length, 5, 'all five recovery tools must dispatch only their fixed broker routes');

let dangerousExecutions = 0;
const unsafe = ctx.plugin({ inject: ['tools'], apply(scope) {
  scope.tools.register(defineTool({ name: 'bash', description: 'fixture only', parameters: {},
    output: { schema: { type: 'json' }, render: () => [] }, execute() { dangerousExecutions++; return {}; } }));
} });
await new Promise(resolve => setTimeout(resolve, 25));
assert.throws(() => recovery.verifyRoster(ctx.tools), /RECOVERY_TOOL_ROSTER/);
assert.equal((await invoke('bash')).isError, true, 'monotonic guard must reject unexpected registered tools');
assert.equal(dangerousExecutions, 0);
const scopeKey={};const scoped=createScope(ctx,scopeKey);
await scoped.ctx.plugin({ inject:['tools'], apply(scope){
  scope.tools.register(defineTool({name:'write_file',description:'scope fixture only',parameters:{},output:{schema:{type:'json'},render:()=>[]},execute(){dangerousExecutions++;return {};}}));
}});
assert.throws(()=>recovery.verifyRoster(ctx.tools,scopeKey),/RECOVERY_TOOL_ROSTER/);
assert.equal((await invoke('write_file', {}, scopeKey)).isError, true);
assert.equal(dangerousExecutions,0,'scope-local tools must not bypass the host monotonic guard');
await scoped.dispose();
await unsafe.dispose();
await mounted.dispose();
assert.equal(ctx.tools.schemas().length, 0, 'plugin disposal must remove tools and guard');
await ctx.fiber.dispose();

// 用锁定 dsh-app-boot 的真实 patch 合成器验证 Profile，不以字符串存在证明隔离。
const patches = [];
for (const bundle of ['dsh-base', 'dsh-web-app']) {
  const directory = path.join(runtime, 'node_modules/@deepseek-ai', bundle);
  const pkg = JSON.parse(await readFile(path.join(directory, 'package.json'), 'utf8'));
  for (const file of [].concat(pkg.dsh.bundle.patch)) patches.push(loadOverlayPatches('dsh', path.join(directory, file)));
}
patches.push(loadOverlayPatches('dsh', path.join(root, 'app/src/main/assets/recovery-profile.patch.yml')));
const warnings = [];
const rows = composeEntries(patches, warning => warnings.push(warning));
assert.deepEqual(warnings, [], 'fixed profile must not contain silently skipped rows');
const active = rows.filter(row => row.disabled !== true);
assert.deepEqual(active.filter(row => row.name === '@deepseek-ai/dsh-agent-preset').map(row => row.config.id), ['dsha-emergency']);
for (const row of rows.filter(row => row.id.startsWith('tool-') && row.id !== 'tool-executor-native')) assert.equal(row.disabled, true, `host tool ${row.id} enabled`);
for (const id of ['plugin-manager', 'ui-plugin-manager', 'entry-customization', 'terminal-controller', 'workspace-files', 'cordis-host-runner', 'ptc-runtime']) {
  assert.equal(rows.find(row => row.id === id)?.disabled, true, `${id} must be disabled`);
}
const preset = active.find(row => row.id === 'preset-dsha-emergency');
assert.deepEqual(preset.config.plugins.map(row => row.name), ['@deepseek-ai/dsh-persona']);
assert.equal(rows.find(row => row.id === 'tools').config?.mode, undefined, '0.2.2 tools no longer accepts the removed mode field');
assert.equal(rows.find(row => row.id === 'tool-executor-native')?.disabled, undefined, 'recovery must retain the native executor');

const calls = [];
const client = recovery.createBrokerClient({ DSHA_RECOVERY_BROKER_PORT: '43121', DSHA_RECOVERY_BROKER_TOKEN: 'a'.repeat(64) }, async (url, options) => {
  calls.push({ url, options });return { ok: true, text: async () => '{"status":"ok"}' };
});
await client('read', { targetId: 'profile-one' });
assert.equal(calls[0].url, 'http://127.0.0.1:43121/v1/read');
assert.equal(calls[0].options.headers.Authorization, `Bearer ${'a'.repeat(64)}`);
await assert.rejects(client('confirm', {}), /RECOVERY_ROUTE/);
assert.equal(calls.length, 1);
assert.throws(() => recovery.createBrokerClient({ DSHA_RECOVERY_BROKER_PORT: 'https://remote', DSHA_RECOVERY_BROKER_TOKEN: 'a'.repeat(64) }), /RECOVERY_BROKER_ID/);
let tick,stopValue='old-instance',stops=0,cancels=0;
const disposeWatch=recovery.watchRecoveryStop('current-instance',async()=>{stops++;},async()=>stopValue,callback=>{tick=callback;return{unref(){}};},()=>{cancels++;});
await tick();assert.equal(stops,0);
stopValue='current-instance\n';await tick();await tick();assert.equal(stops,1);
disposeWatch();await tick();assert.equal(stops,1);assert.equal(cancels,1);
console.log(`PASS: ${actual.version} actual ToolRuntime dispatch/global+scoped guard/disposal, profile composition (${warnings.length} patch warnings), broker routing/auth and owned stop sentinel`);
