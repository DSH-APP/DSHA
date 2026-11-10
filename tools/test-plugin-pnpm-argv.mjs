/** Real locked current command functions + bundled pnpm. Only metadata npm is a local
 * spy in the argv regression; installation and Git prepare execute real tools
 * in fresh host-only profiles. No phone, global config or market activation. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { execFileSync, spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { testRuntime } from './test-runtime-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..'), python = process.env.DSHA_PYTHON ?? 'python';
const runtime = testRuntime('raw'), moduleName = '@deepseek-ai/dsh-plugin-manager/lib/index.js';
const out = fs.mkdtempSync(path.join(root, 'app/build/plugin-pnpm-argv-'));
const sha = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const packed = path.join(root, 'app/src/main/assets/pnpm-runtime.bin');
const expectedArchive = JSON.parse(fs.readFileSync(path.join(root, 'app/src/main/assets/runtime-descriptor.json'))).inputs['pnpm-runtime.bin'];
assert.equal(sha(packed), expectedArchive);
execFileSync(python, ['-c', [
    'import pathlib,tarfile,sys',
    'target=pathlib.Path(sys.argv[2])',
    'archive=tarfile.open(sys.argv[1],"r:gz")',
    'prefix="usr/local/lib/dsha-pnpm/"',
    'for item in archive:',
    ' if not item.isfile(): continue',
    ' if not item.name.startswith(prefix): raise ValueError("PNPM_ARCHIVE_PREFIX")',
    ' relative=pathlib.PurePosixPath(item.name[len(prefix):])',
    ' if relative.is_absolute() or ".." in relative.parts: raise ValueError("PNPM_ARCHIVE_PATH")',
    ' file=target.joinpath(*relative.parts);file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(archive.extractfile(item).read())'
].join('\n'), packed, path.join(out, 'bundled')], { windowsHide: true });
const cli = path.join(out, 'bundled/bin/pnpm.cjs');
assert.equal(execFileSync(process.execPath, [cli, '--version'], { encoding: 'utf8', windowsHide: true }).trim(), '10.34.5');
const build = execFileSync(python, ['-c', [
    'import pathlib,json,sys', 'sys.path.insert(0,str(pathlib.Path(sys.argv[1])/"tools"))',
    'from runtime_stable_patches import load_chains,apply',
    'root=pathlib.Path(sys.argv[1]);runtime=pathlib.Path(sys.argv[2]);module=sys.argv[3]',
    'version=json.loads((root/"tools/dsh-runtime/package.json").read_text(encoding="utf8"))["dependencies"]["@deepseek-ai/dsh"]',
    'chains,_=load_chains(root/"app/src/main/assets",version)',
    'source=(runtime/"node_modules"/module).read_bytes()',
    'previous=[row for row in chains[module] if row[0]!="plugin-pnpm-argv-patch.json"]',
    'dependencies={name:apply((runtime/"node_modules"/name).read_bytes(),chain).decode() for name,chain in chains.items() if name!=module}',
    'print(json.dumps({"before":apply(source,previous).decode(),"after":apply(source,chains[module]).decode(),"dependencies":dependencies}))'
].join('\n'), root, runtime, moduleName], { encoding: 'utf8', windowsHide: true, maxBuffer: 32 * 1024 * 1024 });
const sources = JSON.parse(build);
// Full-chain dependency exports (notably readPluginEntries) must agree with the
// manager. Build a private overlay; never patch the certified raw fixture.
const packages = new Set(Object.keys(sources.dependencies).map(name => name.startsWith('@') ? name.split('/').slice(0, 2).join('/') : name.split('/')[0]));
const modules = path.join(out, 'node_modules'); fs.mkdirSync(modules);
for (const entry of fs.readdirSync(path.join(runtime, 'node_modules'), { withFileTypes: true })) {
    if (entry.name.startsWith('@')) {
        fs.mkdirSync(path.join(modules, entry.name));
        for (const name of fs.readdirSync(path.join(runtime, 'node_modules', entry.name))) {
            const key = entry.name + '/' + name, source = path.join(runtime, 'node_modules', key), target = path.join(modules, key);
            if (packages.has(key)) fs.cpSync(source, target, { recursive: true });
            else fs.symlinkSync(source, target, process.platform === 'win32' ? 'junction' : 'dir');
        }
    } else if (entry.isDirectory()) fs.symlinkSync(path.join(runtime, 'node_modules', entry.name), path.join(modules, entry.name), process.platform === 'win32' ? 'junction' : 'dir');
}
for (const [name, source] of Object.entries(sources.dependencies)) fs.writeFileSync(path.join(modules, name), source);
for (const name of ['before', 'after']) fs.writeFileSync(path.join(out, name + '.mjs'), sources[name] + '\nexport { namedSpecManifest, viewProfilePackage, runProfilePnpm };\n');
const after = await import(pathToFileURL(path.join(out, 'after.mjs')));
const privateHome = path.join(out, 'home'); fs.mkdirSync(privateHome);
const bin = path.join(out, 'bin'); fs.mkdirSync(bin);
const spy = path.join(out, 'npm-spy.mjs'), spyLog = path.join(out, 'npm-spy.jsonl');
fs.writeFileSync(spy, `import fs from 'node:fs';
fs.appendFileSync(process.env.DSHA_NPM_SPY,JSON.stringify(process.argv.slice(2))+'\\n');
if(process.argv[2]!=='view'){process.stderr.write('unexpected npm spy verb');process.exit(90);}
process.stdout.write(JSON.stringify({name:'dsha-registry-fixture',version:'1.0.0',description:'isolated fixture',dsh:{bundle:{patch:'./cordis.patch.yml'}}}));
`);
function shim(file, script, args = []) {
    fs.writeFileSync(file, process.platform === 'win32'
        ? '@"' + process.execPath + '" "' + script + '" ' + args.join(' ') + ' %*\r\n'
        : '#!/bin/sh\nexec ' + [process.execPath, script, ...args].map(s => "'" + s.replaceAll("'", "'\\''") + "'").join(' ') + ' "$@"\n', { mode: 0o755 });
}
shim(path.join(bin, process.platform === 'win32' ? 'npm.cmd' : 'npm'), spy);
const config = path.join(out, 'isolated.npmrc');
fs.writeFileSync(config, 'npm-path=' + path.join(bin, process.platform === 'win32' ? 'npm.cmd' : 'npm').replaceAll('\\', '/') + '\n');
const globalConfig = path.join(out, 'empty-global.npmrc'); fs.writeFileSync(globalConfig, '');
const env = { ...process.env, HOME: privateHome, USERPROFILE: privateHome, NPM_CONFIG_USERCONFIG: config,
    NPM_CONFIG_GLOBALCONFIG: globalConfig, npm_config_cache: path.join(out, 'npm-cache'),
    npm_config_store_dir: path.join(out, 'pnpm-store'), npm_config_audit: 'false', npm_config_fund: 'false',
    npm_config_update_notifier: 'false', npm_config_manage_package_manager_versions: 'false',
    DSHA_NPM_SPY: spyLog, NARB_DISABLE_NATIVE_CACHE: '1' };
delete env.NODE_OPTIONS; delete env.NODE_PATH;
for (const key of Object.keys(env)) if (key.toLowerCase() === 'path') delete env[key];
env.PATH = bin + path.delimiter + (process.env.PATH ?? process.env.Path ?? '');
function profile(name) {
    const dir = path.join(out, name); fs.mkdirSync(dir);
    fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify({ name, private: true, version: '1.0.0', dsh: { profile: { bundles: [] } } }));
    fs.writeFileSync(path.join(dir, 'cordis.patch.yml'), '[]\n');
    return dir;
}
const checks = [];
async function check(name, work) { await work(); checks.push(name); console.log('PASS ' + name); }
let server;
const previousUnlocked = process.env.DSHA_PLUGIN_UNLOCKED;
try {
    await check('negative: real bundled pnpm flags-before-view reproduces recursive exec failure with zero registry traffic', async () => {
        const dir = profile('negative-query');
        const result = spawnSync(process.execPath, [cli, '--config.update-notifier=false', 'view', 'dsh-market', 'name', 'version', 'description', 'dsh', '--json', '--config.fetch-retries=0'],
            { cwd: dir, env, encoding: 'utf8', windowsHide: true, timeout: 15000 });
        fs.writeFileSync(path.join(out, 'negative-query.log'), (result.stdout ?? '') + (result.stderr ?? ''));
        assert.equal(result.status, 1); assert.match(result.stdout, /ERR_PNPM_RECURSIVE_EXEC_FIRST_FAIL/); assert.match(result.stdout, /view/);
        assert.equal(fs.existsSync(spyLog), false);
    });
    await check('real official registry inspection keeps view first and honors executable-prefix arguments', async () => {
        const dir = profile('positive-query');
        const view = await after.viewProfilePackage(dir, 'dsha-registry-fixture', { command: process.execPath, args: [cli], env, timeoutMs: 15000, registry: 'https://registry.npmjs.org/' });
        fs.writeFileSync(path.join(out, 'positive-query.json'), JSON.stringify(view, null, 2));
        assert.equal(view.exitCode, 0, JSON.stringify(view)); assert.equal(JSON.parse(view.stdout).name, 'dsha-registry-fixture');
        const args = JSON.parse(fs.readFileSync(spyLog, 'utf8').trim().split('\n').at(-1));
        assert.equal(args[0], 'view'); assert(args.includes('--registry=https://registry.npmjs.org/'));
    });
    const registryPackage = path.join(out, 'registry-package'); fs.mkdirSync(registryPackage);
    const metadata = { name: 'dsha-registry-fixture', version: '1.0.0', main: 'index.js',
        scripts: { postinstall: 'node hook.cjs' }, dsh: { bundle: { patch: './cordis.patch.yml' } } };
    fs.writeFileSync(path.join(registryPackage, 'package.json'), JSON.stringify(metadata));
    fs.writeFileSync(path.join(registryPackage, 'index.js'), 'module.exports.apply=()=>{};\n');
    fs.writeFileSync(path.join(registryPackage, 'hook.cjs'), "require('fs').writeFileSync('lifecycle-ran','actual postinstall');\n");
    fs.writeFileSync(path.join(registryPackage, 'cordis.patch.yml'), '[]\n');
    const tarball = path.join(out, 'fixture.tgz');
    execFileSync(python, ['-c', 'import tarfile,sys\nwith tarfile.open(sys.argv[2],"w:gz") as a:a.add(sys.argv[1],arcname="package")', registryPackage, tarball], { windowsHide: true });
    const bytes = fs.readFileSync(tarball), requests = [];
    server = createServer((req, res) => {
        requests.push(req.url);
        if (req.url === '/fixture.tgz') { res.writeHead(200, { 'content-type': 'application/octet-stream', 'content-length': bytes.length }); res.end(bytes); return; }
        if (req.url === '/dsha-registry-fixture') {
            const value = { name: metadata.name, 'dist-tags': { latest: metadata.version }, versions: { [metadata.version]: { ...metadata,
                dist: { tarball: `http://127.0.0.1:${server.address().port}/fixture.tgz`, integrity: 'sha512-' + createHash('sha512').update(bytes).digest('base64') } } } };
            res.setHeader('content-type', 'application/json'); res.end(JSON.stringify(value)); return;
        }
        res.writeHead(404); res.end('{}');
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const registry = `http://127.0.0.1:${server.address().port}/`;
    process.env.DSHA_PLUGIN_UNLOCKED = '1';
    const outputs = [];
    const options = { command: process.execPath, args: [cli], env, execution: 'service', outputBytes: 131072,
        activateNewBundles: false, idleTimeoutMs: 30000, lookupTimeoutMs: 15000, onOutput: text => outputs.push(text) };
    await check('patched official runProfilePnpm performs real registry preflight and actual postinstall without leading policy flags', async () => {
        const dir = profile('registry-install');
        const result = await after.runProfilePnpm({ dir, name: 'web', installAnchor: path.join(runtime, 'package.json') },
            ['add', 'dsha-registry-fixture@1.0.0', '--registry=' + registry], options);
        fs.writeFileSync(path.join(out, 'registry-install.log'), outputs.join(''));
        assert.equal(result.exitCode, 0, JSON.stringify(result));
        assert.equal(fs.readFileSync(path.join(dir, 'node_modules/dsha-registry-fixture/lifecycle-ran'), 'utf8'), 'actual postinstall');
        const probes = fs.readFileSync(spyLog, 'utf8').trim().split('\n').map(JSON.parse);
        assert(probes.some(argv => argv[0] === 'view' && argv.includes('peerDependencies')));
        assert(requests.includes('/fixture.tgz')); assert(fs.existsSync(path.join(dir, 'pnpm-lock.yaml')));
    });
    await check('Git npm-install prepare executes real dev dependency build and retains fixed commit in lock', async () => {
        const repo = path.join(out, 'git-package'); fs.mkdirSync(repo);
        fs.mkdirSync(path.join(repo, 'builder'));
        fs.writeFileSync(path.join(repo, 'builder/package.json'), JSON.stringify({ name: 'dsha-git-builder', version: '1.0.0', bin: { 'dsha-git-builder': './build.cjs' } }));
        fs.writeFileSync(path.join(repo, 'builder/build.cjs'), "#!/usr/bin/env node\nconst fs=require('fs');fs.mkdirSync('lib',{recursive:true});fs.writeFileSync('lib/index.js','module.exports.apply=()=>{};');fs.writeFileSync('prepare-ran','real npm install and dev build');\n", { mode: 0o755 });
        fs.writeFileSync(path.join(repo, 'package.json'), JSON.stringify({ name: 'dsha-git-prepare-fixture', version: '1.0.0', main: 'lib/index.js',
            scripts: { prepare: 'dsha-git-builder' }, devDependencies: { 'dsha-git-builder': 'file:./builder' },
            files: ['lib', 'prepare-ran', 'cordis.patch.yml'], dsh: { bundle: { patch: './cordis.patch.yml' } } }));
        fs.writeFileSync(path.join(repo, 'cordis.patch.yml'), '[]\n');
        const git = argv => execFileSync('git', argv, { cwd: repo, env, encoding: 'utf8', windowsHide: true });
        git(['init', '-q']); git(['add', '.']); git(['-c', 'user.name=DSHA isolated fixture', '-c', 'user.email=fixture@invalid', 'commit', '-qm', 'fixed prepare fixture']);
        const commit = git(['rev-parse', 'HEAD']).trim();
        const realBin = path.join(out, 'real-bin'); fs.mkdirSync(realBin);
        const npmCli = [process.env.DSHA_TEST_NPM_CLI,
            path.join(path.dirname(process.execPath), 'node_modules/npm/bin/npm-cli.js'),
            path.resolve(path.dirname(process.execPath), '../lib/node_modules/npm/bin/npm-cli.js'),
            path.resolve(path.dirname(process.execPath), '../share/nodejs/npm/bin/npm-cli.js')]
            .find(file => file && fs.existsSync(file));
        assert(npmCli, 'Host npm CLI required; set DSHA_TEST_NPM_CLI when not beside Node');
        shim(path.join(realBin, process.platform === 'win32' ? 'npm.cmd' : 'npm'), npmCli);
        const realConfig = path.join(out, 'real.npmrc'); fs.writeFileSync(realConfig, 'npm-path=' + path.join(realBin, process.platform === 'win32' ? 'npm.cmd' : 'npm').replaceAll('\\', '/') + '\n');
        const realEnv = { ...env, NPM_CONFIG_USERCONFIG: realConfig, PATH: realBin + path.delimiter + (process.env.PATH ?? process.env.Path ?? ''), npm_config_registry: registry };
        const dir = profile('git-install'); outputs.length = 0;
        const spec = 'git+' + pathToFileURL(repo).href + '#' + commit;
        const result = await after.runProfilePnpm({ dir, name: 'web', installAnchor: path.join(runtime, 'package.json') }, ['add', spec, '--registry=' + registry],
            { ...options, env: realEnv, idleTimeoutMs: 60000 });
        fs.writeFileSync(path.join(out, 'git-install.log'), outputs.join(''));
        assert.equal(result.exitCode, 0, JSON.stringify(result));
        assert.equal(fs.readFileSync(path.join(dir, 'node_modules/dsha-git-prepare-fixture/prepare-ran'), 'utf8'), 'real npm install and dev build');
        assert(fs.existsSync(path.join(dir, 'node_modules/dsha-git-prepare-fixture/lib/index.js')));
        assert(fs.readFileSync(path.join(dir, 'pnpm-lock.yaml'), 'utf8').includes(commit));
        fs.writeFileSync(path.join(out, 'git-fixed-source.json'), JSON.stringify({ commit, spec, npmCli, npmVersion: execFileSync(process.execPath, [npmCli, '--version'], { encoding: 'utf8', windowsHide: true }).trim() }, null, 2));
    });
    const result = { status: 'PASS', checks, output: out, runtime, productionModule: moduleName,
        canonicalSha256: sha(path.join(runtime, 'node_modules', moduleName)),
        patchedSha256: createHash('sha256').update(sources.after).digest('hex'), pnpmVersion: '10.34.5', pnpmArchiveSha256: sha(packed),
        recipeSha256: sha(path.join(root, 'app/src/main/assets/plugin-pnpm-argv-patch.json')),
        scope: 'isolated host only; no production profile, phone, real marketplace activation or Android/proot claim' };
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(result, null, 2) + '\n');
    console.log(JSON.stringify(result));
} finally {
    if (previousUnlocked === undefined) delete process.env.DSHA_PLUGIN_UNLOCKED; else process.env.DSHA_PLUGIN_UNLOCKED = previousUnlocked;
    if (server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
}
