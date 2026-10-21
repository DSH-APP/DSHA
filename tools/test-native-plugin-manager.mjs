import os from 'node:os';
import { testRuntime } from './test-runtime-fixture.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import {createHash} from 'node:crypto';

// The rc2.1 overlay must remove the old review denial from both Host and UI.
const managed = testRuntime('managed');
const raw = testRuntime('raw');
const hostPath = 'node_modules/@deepseek-ai/dsh-plugin-manager/lib/index.js';
const uiPath = 'node_modules/@deepseek-ai/dsh-client-ui-plugin-manager/lib/client.js';
const policy = JSON.parse(fs.readFileSync('app/src/main/assets/plugin-manager-policy-patch.json', 'utf8'));
const upstreamHost = fs.readFileSync(path.join(raw, hostPath), 'utf8').replaceAll('\r\n', '\n');
const currentHost = fs.readFileSync(path.join(managed, hostPath), 'utf8').replaceAll('\r\n', '\n');
let expectedHost = upstreamHost;
assert.equal(policy.patches.length, 15);
const active = JSON.parse(fs.readFileSync('app/src/main/assets/runtime-patches.json','utf8')).active;
for (const row of active) {
  const recipe = JSON.parse(fs.readFileSync(path.join('app/src/main/assets',row.asset),'utf8'));
  if ((row.module ?? recipe.module) !== policy.module) continue;
  for (const patch of recipe.patches) {
    assert.equal(expectedHost.split(patch.before).length - 1, 1,row.asset);
    const after = patch.prependAsset === undefined ? patch.after : fs.readFileSync(path.join('app/src/main/assets',patch.prependAsset),'utf8')+'\n'+patch.after;
    expectedHost = expectedHost.replace(patch.before, after);
  }
}
assert.ok(currentHost === expectedHost, 'Host overlay differs from the complete pinned source chain');
assert.doesNotMatch(currentHost, /["']--ignore-scripts(?:["']|=true)/);
assert.doesNotMatch(currentHost, /--ignore-pnpmfile/);
assert.match(currentHost, /--config\.dangerously-allow-all-builds=true/);
assert.match(currentHost, /--config\.ignore-scripts=false/);
assert.match(currentHost, /--config\.ignore-dep-scripts=false/);
assert.match(currentHost, /DSHA_PLUGIN_SINGLE_INSTALL_V1/);
assert.match(currentHost, /DSHA_PLUGIN_HTTPS_GITHUB_V1/);
assert.doesNotMatch(currentHost, /DSHA_NATIVE_REVIEW_REQUIRED|DSHA_NATIVE_PLUGIN_POLICY_V1/);
const uiPatch = JSON.parse(fs.readFileSync('app/src/main/assets/plugin-manager-auto-enable-patch.json', 'utf8')).patches[0];
const upstreamUi = fs.readFileSync(path.join(raw, uiPath), 'utf8').replaceAll('\r\n', '\n');
const currentUi = fs.readFileSync(path.join(managed, uiPath), 'utf8').replaceAll('\r\n', '\n');
assert.equal(upstreamUi.split(uiPatch.before).length - 1, 1);
assert.ok(currentUi === upstreamUi.replace(uiPatch.before, uiPatch.after),
  'Web plugin installation must enable the installed bundle by default');

const { PluginManager } = await import(pathToFileURL(path.join(managed, hostPath)));
const manager = Object.create(PluginManager.prototype);
const directory = fs.mkdtempSync(path.join(os.tmpdir(),'native-plugin-policy-'));
try {
const sdk = path.join(directory, 'sdk');
const profile = path.join(directory, 'profile');
fs.mkdirSync(sdk, { recursive: true });
fs.mkdirSync(profile, { recursive: true });
fs.writeFileSync(path.join(sdk, 'package.json'), JSON.stringify({ name: '@deepseek-ai/dsh' }));
fs.writeFileSync(path.join(profile, 'package.json'), '{}');
manager.profile = { installAnchor: path.join(sdk, 'package.json'), dir: profile };
let changes = 0;
manager.change = async () => { changes++; return { changed: true, application: 'applied' }; };
const original = process.env.DSHA_NATIVE_PLUGIN_MANAGER;
process.env.DSHA_NATIVE_PLUGIN_MANAGER = '1';
try {
  for (const result of [
    await manager.installBundle('community-plugin@1.0.0', { enabled: true }),
    await manager.setBundleEnabled('community-plugin', true),
    await manager.removeBundle('community-plugin')
  ]) {
    assert.equal(result.application, 'applied');
  }
  assert.equal(changes, 3, 'Host operations must reach the upstream implementation');
} finally {
  if (original === undefined) delete process.env.DSHA_NATIVE_PLUGIN_MANAGER;
  else process.env.DSHA_NATIVE_PLUGIN_MANAGER = original;
}
console.log('PASS: Host plugin install, enable and remove are available; no review gate remains; unlocked lifecycle install is preserved.');

// A package hook can write before pnpm reports a registry error. Exercise the
// real Host operation and a real child process to prove fallback does not run it
// twice, and that this failure never becomes a successful install/enable result.
const failedProfile = path.join(directory, 'failed-profile');
fs.mkdirSync(failedProfile);
fs.writeFileSync(path.join(failedProfile, 'package.json'), JSON.stringify({name:'fixture-profile',private:true}));
fs.writeFileSync(path.join(failedProfile, 'cordis.patch.yml'), '[]\n');
const child = path.join(directory, 'package-child.cjs');
const counter = path.join(directory, 'hook-count');
const argumentsFile = path.join(directory, 'package-arguments.json');
const gitEnvironment = path.join(directory, 'package-git-environment.json');
fs.writeFileSync(child, `const fs=require('node:fs');
const args=process.argv.slice(2);
if(args.includes('config')) { console.log('https://registry.npmjs.org/'); process.exit(0); }
if(args.includes('view')) { console.log(JSON.stringify({name:'fixture-plugin',version:'1.0.0'})); process.exit(0); }
if(args.includes('add')) {
  fs.appendFileSync(process.env.DSHA_FIXTURE_COUNTER,'1');
  fs.writeFileSync(process.env.DSHA_FIXTURE_ARGUMENTS,JSON.stringify(args));
  fs.writeFileSync(process.env.DSHA_FIXTURE_GIT_ENV,JSON.stringify(Object.fromEntries(Object.entries(process.env).filter(([key])=>key.startsWith('GIT_CONFIG_')))));
  console.error('ERR_PNPM_FETCH_503 fixture hook wrote before the network failure'); process.exit(1);
}
process.exit(2);
`);
const failureManager = Object.create(PluginManager.prototype);
Object.assign(failureManager, {
  profile: {name:'web',dir:failedProfile,home:directory,patchPath:path.join(failedProfile,'cordis.patch.yml'),overlays:[],startedBundles:[],installAnchor:path.join(sdk,'package.json'),
    packageManager:{command:process.execPath,args:[child],env:{DSHA_FIXTURE_COUNTER:counter,DSHA_FIXTURE_ARGUMENTS:argumentsFile,DSHA_FIXTURE_GIT_ENV:gitEnvironment,GIT_CONFIG_COUNT:'0'}}},
  pnpmCommand:process.execPath,outputBytes:16384,lockWaitMs:1000,inspectTimeoutMs:1000,githubConnectionTimeoutMs:1000,idleTimeoutMs:2000,
  configuredRegistries:{registry:'https://registry.npmjs.org/',fallbackRegistries:['https://registry.npmmirror.com/']},
  ownerContext:{get:()=>undefined,emit:()=>{},logger:{warn:()=>{}}},
  managementBundles:new Set(),installedFrom:new Map(),packageOperations:new Set(),installs:new Map(),abort:new AbortController()
});
const priorUnlocked = process.env.DSHA_PLUGIN_UNLOCKED;
process.env.DSHA_PLUGIN_UNLOCKED = '1';
try {
  const failed = await failureManager.installBundle('fixture-plugin@1.0.0',{enabled:true,requestId:'fixture-side-effect'});
  assert.equal(failed.application,'failed');
  assert.equal(failed.packageResult.exitCode,1);
  assert.equal(failed.packageResult.kind,'network');
  assert.equal(fs.readFileSync(counter,'utf8'),'1','a failed script-bearing installation was replayed');
  assert.deepEqual(failed.registries,['https://registry.npmjs.org/']);
  const actualArguments = JSON.parse(fs.readFileSync(argumentsFile,'utf8'));
  for (const flag of ['--config.dangerously-allow-all-builds=true','--config.ignore-scripts=false','--config.ignore-dep-scripts=false','--config.fetch-retries=1','--config.fetch-timeout=60000','--reporter=append-only']) assert(actualArguments.includes(flag),flag);
  assert.equal(actualArguments.filter(arg=>arg==='fixture-plugin@1.0.0').length,1);
  assert.equal(JSON.parse(fs.readFileSync(path.join(failedProfile,'package.json'),'utf8')).dependencies,undefined);
  assert.equal(fs.existsSync(path.join(failedProfile,'pnpm-lock.yaml')),false);
  console.log('PASS: real Host child receives unlocked scripts and bounded download options; script-side-effect failure runs once and remains failed.');
  assert.equal(JSON.parse(fs.readFileSync(gitEnvironment,'utf8')).GIT_CONFIG_COUNT,'0','registry install must retain its transport policy');
  await failureManager.runPnpm(['add','https://github.com/fixture-owner/fixture-repo']);
  const githubEnv = JSON.parse(fs.readFileSync(gitEnvironment,'utf8'));
  assert.equal(githubEnv.GIT_CONFIG_COUNT,'3');
  assert.equal(githubEnv.GIT_CONFIG_KEY_0,'url.https://github.com/.insteadOf');
  assert.deepEqual([githubEnv.GIT_CONFIG_VALUE_0,githubEnv.GIT_CONFIG_VALUE_1,githubEnv.GIT_CONFIG_VALUE_2],['git+ssh://git@github.com/','ssh://git@github.com/','git@github.com:']);
  Object.assign(failureManager.profile.packageManager.env,{GIT_CONFIG_COUNT:'1',GIT_CONFIG_KEY_0:'fixture.explicit',GIT_CONFIG_VALUE_0:'keep'});
  await failureManager.runPnpm(['add','https://github.com/fixture-owner/fixture-repo']);
  const explicitEnv = JSON.parse(fs.readFileSync(gitEnvironment,'utf8'));
  assert.equal(explicitEnv.GIT_CONFIG_COUNT,'1');
  assert.equal(explicitEnv.GIT_CONFIG_KEY_0,'fixture.explicit');
  assert.equal(explicitEnv.GIT_CONFIG_VALUE_0,'keep');
  console.log('PASS: explicit GitHub HTTPS uses invocation-only transport rules; npm sources and explicit Git settings keep their own policy.');
} finally {
  if (priorUnlocked === undefined) delete process.env.DSHA_PLUGIN_UNLOCKED;
  else process.env.DSHA_PLUGIN_UNLOCKED=priorUnlocked;
}

} finally { fs.rmSync(directory,{recursive:true,force:true}); }


// Exercise the actual patched Host's provenance projection and reserved-name policy.
const provenanceRoot = fs.mkdtempSync(path.join(os.tmpdir(),'dsha-plugin-provenance-'));
const previousProof = process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256;
const proofOf = directory => {
  const rows = new Map();
  const walk = (folder, prefix='') => {
    for (const name of fs.readdirSync(folder)) {
      const file=path.join(folder,name),relative=prefix?prefix+'/'+name:name,node=fs.lstatSync(file);
      if(node.isSymbolicLink())rows.set(relative,relative+'\0LINK\0'+fs.readlinkSync(file)+'\n');
      else if(node.isDirectory())walk(file,relative);
      else rows.set(relative,relative+'\0FILE\0'+(node.mode&0o777)+'\0'+createHash('sha256').update(fs.readFileSync(file)).digest('hex')+'\n');
    }
  };
  walk(directory);return createHash('sha256').update([...rows.keys()].sort().map(name=>rows.get(name)).join('')).digest('hex');
};
try {
  const home=path.join(provenanceRoot,'home'),profile=path.join(home,'profiles/web'),sdk=path.join(provenanceRoot,'sdk');
  fs.mkdirSync(profile,{recursive:true});fs.mkdirSync(sdk,{recursive:true});
  const custom=['@deepseek-ai/dsh-ai-created','dsh-ai-created','@author/dsh-custom'];
  const writePlugin=(base,name)=>{
    const directory=path.join(base,'node_modules',name);fs.mkdirSync(directory,{recursive:true});
    fs.writeFileSync(path.join(directory,'package.json'),JSON.stringify({name,version:'1.0.0',dsh:{bundle:{patch:'cordis.patch.yml'}}}));
    fs.writeFileSync(path.join(directory,'cordis.patch.yml'),'[]\n');return directory;
  };
  for(const name of custom)writePlugin(profile,name);
  const runtimeName='@deepseek-ai/dsh-experimental-voice-input-bundle';
  const runtime=writePlugin(sdk,runtimeName);
  fs.writeFileSync(path.join(sdk,'package.json'),JSON.stringify({name:'@deepseek-ai/dsh',version:'0.2.1-rc.2',dependencies:{[runtimeName]:'1.0.0'}}));
  fs.writeFileSync(path.join(profile,'package.json'),JSON.stringify({name:'isolated-provenance-profile',private:true,dependencies:Object.fromEntries([...custom,runtimeName].map(name=>[name,'1.0.0'])),dsh:{profile:{bundles:[...custom,runtimeName]}}}));
  fs.writeFileSync(path.join(profile,'cordis.patch.yml'),'[]\n');
  const ctx={get:()=>undefined,emit:()=>{},loader:{entries:()=>[]},logger:{warn:()=>{}}},manager=Object.create(PluginManager.prototype);
  Object.defineProperty(manager,'ctx',{value:ctx});
  Object.assign(manager,{profile:{name:'web',dir:profile,home,patchPath:path.join(profile,'cordis.patch.yml'),overlays:[],startedBundles:[],installAnchor:path.join(sdk,'package.json')},ownerContext:ctx,managementBundles:new Set(),installedFrom:new Map(),packageOperations:new Set(),installs:new Map(),abort:new AbortController(),lockWaitMs:1000});
  const rows=async()=>new Map((await manager.listBundles()).map(row=>[row.name,row]));
  delete process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256;
  for(const name of custom){const item=(await rows()).get(name);assert.equal(item.origin,'user',name);assert.equal(item.official,false,name);assert.equal(item.availability,'profile',name);assert.equal(item.removable,true,name);}
  assert.equal((await rows()).get(runtimeName).origin,'user');
  assert.equal((await rows()).get(runtimeName).official,false,'a mutable installation dependency is not official provenance');
  const proofFile=path.join(home,'managed-package-proofs.json');
  const authenticate=(name,digest)=>{
    fs.writeFileSync(proofFile,JSON.stringify({version:1,packages:{[name]:digest}}));
    process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256=createHash('sha256').update(fs.readFileSync(proofFile)).digest('hex');
  };
  authenticate(runtimeName,proofOf(runtime));
  assert.equal((await rows()).get(runtimeName).origin,'dsh-managed');
  assert.equal((await rows()).get(runtimeName).availability,'installation');
  assert.equal((await rows()).get(runtimeName).official,true);
  assert.equal((await rows()).get(runtimeName).removable,false);
  fs.writeFileSync(path.join(runtime,'user-private-draft.txt'),'preserve this user override');
  assert.equal((await rows()).get(runtimeName).origin,'user');
  assert.equal((await rows()).get(runtimeName).official,false);
  fs.writeFileSync(proofFile,JSON.stringify({version:1,packages:{[runtimeName]:proofOf(runtime)}}));
  assert.equal((await rows()).get(runtimeName).origin,'user','a guest-rewritten proof cannot authenticate itself');
  console.log('PASS: actual Host separates custom scopes/prefixes, exact APK content, user overrides and unauthenticated proof edits.');
  // A component without dsh.bundle must never trigger bundle provenance hashing.
  // Then wrap its real patched module bytes in a private, explicitly declared
  // test bundle so full raw / managed / user-content comparisons remain real.
  const actualName='@deepseek-ai/dsh-plugin-manager',copy=path.join(sdk,'node_modules',actualName);
  fs.mkdirSync(path.dirname(copy),{recursive:true});fs.cpSync(path.join(managed,'node_modules',actualName),copy,{recursive:true});
  authenticate(actualName,proofOf(copy));
  const profileDoc=JSON.parse(fs.readFileSync(path.join(profile,'package.json'),'utf8'));
  profileDoc.dependencies[actualName]='0.2.1-rc.2';profileDoc.dsh.profile.bundles.push(actualName);
  fs.writeFileSync(path.join(profile,'package.json'),JSON.stringify(profileDoc));
  assert.equal((await rows()).get(actualName).origin,'user','a component without bundle metadata is not an installation bundle');
  const componentManifest=JSON.parse(fs.readFileSync(path.join(copy,'package.json'),'utf8'));
  assert.equal(componentManifest.dsh?.bundle,undefined);
  componentManifest.dsh={...componentManifest.dsh,bundle:{patch:'fixture.bundle.patch.yml'}};
  fs.writeFileSync(path.join(copy,'package.json'),JSON.stringify(componentManifest));
  fs.writeFileSync(path.join(copy,'fixture.bundle.patch.yml'),'[]\n');
  const managedDigest=proofOf(copy);
  authenticate(actualName,managedDigest);
  assert.equal((await rows()).get(actualName).origin,'dsh-managed','final managed patch bytes must match');
  const actualModule=path.join(copy,'lib/index.js'),patchedBytes=fs.readFileSync(actualModule);
  fs.copyFileSync(path.join(raw,'node_modules',actualName,'lib/index.js'),actualModule);
  assert.notEqual(proofOf(copy),managedDigest);
  assert.equal((await rows()).get(actualName).origin,'user','raw bytes cannot match the final managed patch proof');
  fs.writeFileSync(actualModule,patchedBytes);assert.equal((await rows()).get(actualName).origin,'dsh-managed');
  fs.appendFileSync(actualModule,'\n// user edit after managed patch\n');
  assert.equal((await rows()).get(actualName).origin,'user','later user bytes must remain a user override');
  console.log('PASS: a non-bundle component is skipped; its real raw / final patched / user-edited module bytes have distinct provenance in an explicitly declared private bundle.');
  for(const name of [...JSON.parse(fs.readFileSync('app/src/main/assets/builtin-plugins.json','utf8')).plugins.map(row=>row.name),'@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app','@deepseek-ai/dsh-experimental-auto-review']){
    const before=fs.readFileSync(path.join(profile,'package.json'));
    const result=await manager.installBundle(name+'@999.0.0',{enabled:true});
    assert.equal(result.application,'failed',name+' reserved system source must not be overwritten');
    assert.deepEqual(fs.readFileSync(path.join(profile,'package.json')),before,name+' profile changed');
  }
  console.log('PASS: actual Host refuses named installs over every signed/system identity without changing profile data.');
} finally {
  if(previousProof===undefined)delete process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256;
  else process.env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256=previousProof;
  fs.rmSync(provenanceRoot,{recursive:true,force:true});
}
