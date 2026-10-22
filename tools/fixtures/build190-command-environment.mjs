// Executed by Node's import hook in the exact production-generated Web shell.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';

const out = process.env.DSHA_REVIEW_OUTPUT;
const mode = process.env.DSHA_REVIEW_MODE;
if (out && mode) {
  const receipt = {mode, path: process.env.PATH, argv: process.argv.slice(2),
    permission: process.env.DSH_PERMISSION_MODE, language: process.env.DSHA_UI_LANGUAGE,
    profile: process.env.DSHA_STARTUP_PROFILE};
  const save = () => fs.writeFileSync(path.join(out, mode + '.json'), JSON.stringify(receipt, null, 2) + '\n');
  process.on('uncaughtExceptionMonitor', save);
  // The signed observer has restored the previous options. Retire this test-only hook before
  // launching children so no child recursively performs the review's package operations.
  receipt.previousNodeOptionsRestored = process.env.NODE_OPTIONS?.includes('build190-command-environment.mjs');
  delete process.env.NODE_OPTIONS;
  delete process.env.DSHA_REVIEW_MODE;
  const run = (command, args, options = {}) => {
    const r = spawnSync(command, args, {encoding: 'utf8', timeout: 45000, ...options});
    return {status: r.status, stdout: r.stdout?.trim(), stderr: r.stderr?.trim(), error: r.error?.code};
  };
  if (mode === 'quoted') {
    save();
    process.exit(0);
  }
  receipt.pnpmSpawn = run('pnpm', ['--version']);
  if (mode === 'negative') {
    assert.equal(receipt.pnpmSpawn.error, 'ENOENT');
    save();
    process.exit(127);
  }
  assert.equal(receipt.pnpmSpawn.status, 0);
  assert.equal(receipt.pnpmSpawn.stdout, '10.34.5');
  assert(process.env.PATH.endsWith(':' + process.env.DSHA_REVIEW_PATH));
  receipt.userTailCommand = run('user-env-review-tool', []);
  assert.equal(receipt.userTailCommand.status, 0);
  assert.equal(receipt.userTailCommand.stdout, 'user-tail-command');
  receipt.npm = run('npm', ['--version']);
  receipt.npx = run('npx', ['--version']);
  receipt.dsh = run('dsh', ['--version']);
  for (const name of ['npm', 'npx', 'dsh']) assert.equal(receipt[name].status, 0, name);
  assert.equal(receipt.dsh.stdout, '0.2.1-rc.2');
  const root = process.env.DSHA_REVIEW_RUNTIME;
  const load = name => import(pathToFileURL(path.join(root, 'node_modules', name, 'lib/index.js')));
  const {PluginManager} = await load('@deepseek-ai/dsh-plugin-manager');
  const profile = '/root/.dsh/profiles/env-review';
  const manager = Object.create(PluginManager.prototype);
  const ownerContext = {get: () => undefined, emit: () => {}, logger: {warn: console.warn}};
  Object.assign(manager, {profile: {name: 'env-review', dir: profile, home: '/root/.dsh',
    patchPath: path.join(profile, 'cordis.patch.yml'), overlays: [],
    startedBundles: [], installAnchor: path.join(root, 'node_modules/@deepseek-ai/dsh/package.json')},
    outputBytes: 65536, lockWaitMs: 120000, inspectTimeoutMs: 20000,
    githubConnectionTimeoutMs: 15000, idleTimeoutMs: 45000, pnpmCommand: 'pnpm',
    configuredRegistries: {registry: 'https://registry.npmjs.org/', fallbackRegistries: []},
    ownerContext, managementBundles: new Set(), installedFrom: new Map(),
    packageOperations: new Set(), installs: new Map(), abort: new AbortController()});
  Object.defineProperty(manager, 'ctx', {value: {loader: {entries: () => []}, get: () => undefined}});
  receipt.webManagerPnpm = await manager.runPnpm(['--version']);
  assert.equal(receipt.webManagerPnpm.exitCode, 0);
  assert.equal(receipt.webManagerPnpm.output.trim(), '10.34.5');
  receipt.cliPnpm = run('dsh', ['plugin', '--profile', 'env-cli', '--version']);
  assert.equal(receipt.cliPnpm.status, 0);
  assert(receipt.cliPnpm.stdout.includes('10.34.5'));
  const argumentsProbe = path.join(out, 'arguments-probe.cjs');
  fs.writeFileSync(argumentsProbe, 'process.stdout.write(JSON.stringify(process.argv.slice(2)));');
  const literalArguments = ['space value', "single'quote", '$(touch /root/injected-wrapper)', '; touch /root/injected-wrapper'];
  receipt.wrapperArguments = run('pnpm', ['exec', 'node', argumentsProbe, ...literalArguments], {cwd: profile});
  assert.equal(receipt.wrapperArguments.status, 0);
  assert.deepEqual(JSON.parse(receipt.wrapperArguments.stdout), literalArguments);
  assert(!fs.existsSync('/root/injected-wrapper'));
  const localBundle = path.join(out, 'fixture-bundle');
  fs.mkdirSync(localBundle, {recursive: true});
  fs.writeFileSync(path.join(localBundle, 'package.json'), JSON.stringify({
    name: 'dsha-env-review-bundle', version: '1.0.0', type: 'module',
    dsh: {bundle: {patch: 'cordis.patch.yml'}}}));
  fs.writeFileSync(path.join(localBundle, 'cordis.patch.yml'), '[]\n');
  receipt.webInstall = await manager.installBundle('file:' + localBundle,
    {enabled: false, requestId: 'environment-local-install'});
  assert.notEqual(receipt.webInstall.application, 'failed', JSON.stringify(receipt.webInstall));
  assert(JSON.parse(fs.readFileSync(path.join(profile, 'package.json'))).dependencies['dsha-env-review-bundle']);
  receipt.webRemove = await manager.removeBundle('dsha-env-review-bundle');
  assert.notEqual(receipt.webRemove.application, 'failed', JSON.stringify(receipt.webRemove));
  assert(!JSON.parse(fs.readFileSync(path.join(profile, 'package.json'))).dependencies?.['dsha-env-review-bundle']);
  const pkg = JSON.parse(fs.readFileSync(path.join(profile, 'package.json')));
  const counter = path.join(out, 'failed-script-count');
  const failure = path.join(out, 'fail-once.cjs');
  fs.writeFileSync(failure, `const fs=require('node:fs'); const p=${JSON.stringify(counter)}; fs.appendFileSync(p,'once\\n'); process.exit(37);`);
  pkg.scripts = {failonce: 'node ' + failure};
  fs.writeFileSync(path.join(profile, 'package.json'), JSON.stringify(pkg));
  receipt.failedPnpm = await manager.runPnpm(['run', 'failonce']);
  assert.equal(receipt.failedPnpm.exitCode, 37);
  assert.equal(fs.readFileSync(counter, 'utf8'), 'once\n');
  receipt.failedPnpm.executions = 1;
  const {Context} = await load('@deepseek-ai/cordis');
  const {LocalSubprocessRuntime} = await load('@deepseek-ai/dsh-subprocess-local');
  const {LocalBashExecutor} = await load('@deepseek-ai/dsh-bash-local');
  const ctx = new Context();
  new LocalSubprocessRuntime(ctx);
  // Schemastery's volatile values expose get(); keep the actual executor and provider intact.
  const values = {cwd: '/root', timeoutMs: 30000, maxTimeoutMs: 30000,
    maxOutputBytes: 65536, maxSpillBytes: 65536, graceMs: 1000};
  const config = Object.fromEntries(Object.entries(values).map(([k, v]) => [k, {get: () => v}]));
  const bash = new LocalBashExecutor(ctx, config);
  const handle = await bash.execute(bash.resolve({command: 'pnpm --version && npm --version && dsh --version',
    onExpiry: 'kill'}));
  receipt.agentBash = await handle.result();
  assert.equal(receipt.agentBash.exitCode, 0);
  assert(receipt.agentBash.stdout.text.includes('10.34.5'));
  assert(receipt.agentBash.stdout.text.includes('0.2.1-rc.2'));
  await ctx.fiber.dispose();
  save();
  process.exit(0);
}
