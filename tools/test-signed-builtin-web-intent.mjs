import {testRuntime} from './test-runtime-fixture.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawn,spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';

const raw=testRuntime('raw'),managed=testRuntime('managed'),root=process.cwd();
const {PluginManager}=await import(pathToFileURL(path.join(managed,'node_modules/@deepseek-ai/dsh-plugin-manager/lib/index.js')));
const registry=JSON.parse(fs.readFileSync('app/src/main/assets/builtin-plugins.json','utf8'));
const targets=registry.plugins.filter(p=>p.name!=='dsh-auto-review').map(p=>p.name);
assert.equal(targets.length,7);
const owned=fs.mkdtempSync(path.join(os.tmpdir(),'dsha-signed-web-intent-'));
const nativeScript=path.resolve('app/src/main/assets/register-builtin-plugins.py');
const python=process.env.DSHA_TEST_PYTHON??process.env.DSHA_PYTHON??'python';
const profile=path.join(owned,'root/.dsh/profiles/web');
const nativeEnv={...process.env,DSHA_TEST_ROOT:owned,DSH_HOME:'/root/.dsh'};
const marker=name=>path.join(profile,'node_modules',name+'.disabled');
const physical=name=>path.join(owned,registry.plugins.find(p=>p.name===name).guestDirectory.replace(/^\//,''));
const alias=name=>path.join(profile,'node_modules',name);
const selected=()=>JSON.parse(fs.readFileSync(path.join(profile,'package.json'),'utf8')).dsh.profile.bundles;
const ensureAlias=name=>{if(!fs.existsSync(alias(name)))fs.symlinkSync(physical(name),alias(name),process.platform==='win32'?'junction':'dir');};
function native(args=[]){
  const result=spawnSync(python,[nativeScript,...args],{env:nativeEnv,encoding:'utf8',windowsHide:true,timeout:30000});
  assert.equal(result.status,0,result.stderr||result.stdout||String(result.error));return result;
}
function managerFor(dir){
  const manager=Object.create(PluginManager.prototype),ctx={get:()=>undefined,emit:()=>{},loader:{entries:()=>[]},logger:{warn:()=>{}}};
  Object.defineProperty(manager,'ctx',{value:ctx});
  Object.assign(manager,{profile:{name:path.basename(dir),dir,home:path.dirname(dir),patchPath:path.join(dir,'cordis.patch.yml'),overlays:[],startedBundles:[],installAnchor:path.join(raw,'node_modules/@deepseek-ai/dsh/package.json')},ownerContext:ctx,managementBundles:new Set(),installedFrom:new Map(),packageOperations:new Set(),installs:new Map(),abort:new AbortController(),lockWaitMs:1000});
  return manager;
}
try {
  fs.mkdirSync(path.join(profile,'node_modules'),{recursive:true});
  for(const plugin of registry.plugins){
    const source=plugin.name==='dsh-app-integration'?path.resolve('app/src/main/assets/app-integration'):path.resolve('app/src/main/assets/builtin-plugins',plugin.name);
    fs.cpSync(source,physical(plugin.name),{recursive:true});ensureAlias(plugin.name);
  }
  fs.writeFileSync(path.join(profile,'package.json'),JSON.stringify({name:'isolated-signed-web-intent',private:true,fixtureUntouched:{keep:'same bytes and values'},dependencies:Object.fromEntries(registry.plugins.map(p=>[p.name,'link:'+p.guestDirectory])),dsh:{profile:{bundles:[...registry.officialBundles.map(p=>p.name),...registry.plugins.map(p=>p.name)],patchReload:'startup'}}}));
  fs.writeFileSync(path.join(profile,'cordis.patch.yml'),'[]\n');
  const other=path.join(owned,'root/.dsh/profiles/other');fs.mkdirSync(other,{recursive:true});
  fs.writeFileSync(path.join(other,'package.json'),JSON.stringify({name:'unrelated-profile',dsh:{profile:{bundles:targets}}}));
  const otherBefore=fs.readFileSync(path.join(other,'package.json'));
  const manager=managerFor(profile);
  for(const name of targets){
    assert.equal((await manager.listBundles()).find(p=>p.name===name).readOnlyReason,undefined,name+' must remain user-switchable');
    const result=await manager.setBundleEnabled(name,false);assert.equal(result.application,'restart-required',name);
    assert.equal(selected().includes(name),false);assert.equal(fs.readFileSync(marker(name),'utf8'),'DSHA_WEB_BUILTIN_DISABLED_V1\n');
  }
  native();native();
  for(const name of targets){
    assert.equal(selected().includes(name),false,name+' was resurrected by registration');
    assert.equal(fs.realpathSync(alias(name)),fs.realpathSync(physical(name)),name+' dormant alias must target the current APK entity');
    assert.equal(fs.readFileSync(marker(name),'utf8'),'DSHA_WEB_BUILTIN_DISABLED_V1\n');
    assert.equal((await manager.setBundleEnabled(name,true)).application,'restart-required',name);
    assert.equal(selected().includes(name),true);assert.equal(fs.existsSync(marker(name)),false);
  }
  console.log('PASS: all seven actual Host OFF -> two native register -> Host ON roundtrips preserve intent and current APK metadata aliases.');
  for(const content of ['', 'unknown historical native intent', 'DSHA_SAFE_MODE\n']){
    for(const name of targets)fs.writeFileSync(marker(name),content);
    native();
    for(const name of targets){
      assert.equal(selected().includes(name),false);
      assert.equal((await manager.setBundleEnabled(name,true)).application,'failed',name);
      assert.equal(fs.readFileSync(marker(name),'utf8'),content,name+' native marker must survive Web ON');
    }
  }
  assert.deepEqual(fs.readFileSync(path.join(other,'package.json')),otherBefore);
  console.log('PASS: empty, unknown, and safe-mode native markers beat Web ON for all seven; the other profile is unchanged.');
  // Explicit native enable is authorized only for these synthetic fixtures.
  // Node can prepare junctions on Windows, where Python symlink creation may
  // require a privilege the test host lacks. Native code still verifies them.
  for(const name of targets){ensureAlias(name);native(['--enable',name]);}
  const observedNative=`import contextlib,importlib.util,pathlib,sys\np=pathlib.Path(sys.argv[1]);spec=importlib.util.spec_from_file_location('observed_native',p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)\noriginal=m.profile_writer_lock\n@contextlib.contextmanager\ndef observed(*a,**k):\n pathlib.Path(sys.argv[3]).write_text('native reached shared profile lock')\n with original(*a,**k):yield\nm.profile_writer_lock=observed\nwith m.operation_lock():\n result=m.disable_plugin(sys.argv[2])\nsys.exit(result)\n`;
  const nap=new Int32Array(new SharedArrayBuffer(4));
  for(const name of targets){
    assert.equal((await manager.setBundleEnabled(name,false)).application,'restart-required');
    const entered=path.join(owned,'native-entered-'+name),originalRows=manager.bundleRows;
    let child,exited;
    manager.bundleRows=function(bundle){
      if(bundle===name&&!child){
        child=spawn(python,['-c',observedNative,nativeScript,name,entered],{env:nativeEnv,windowsHide:true,stdio:'ignore'});
        exited=new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',code=>resolve(code));});
        const deadline=Date.now()+5000;while(!fs.existsSync(entered)&&Date.now()<deadline)Atomics.wait(nap,0,0,10);
        assert(fs.existsSync(entered),'native must enter the shared-lock boundary');Atomics.wait(nap,0,0,100);
        assert.equal(fs.readFileSync(path.join(profile,'package.json.lock'),'utf8'),process.pid+'\n');
        assert.equal(fs.readFileSync(marker(name),'utf8'),'DSHA_WEB_BUILTIN_DISABLED_V1\n','native OFF must wait for Web ownership');
      }
      return originalRows.call(this,bundle);
    };
    try{assert.equal((await manager.setBundleEnabled(name,true)).application,'restart-required');}
    finally{manager.bundleRows=originalRows;}
    assert.equal(await exited,0);assert.equal(fs.readFileSync(marker(name),'utf8'),'');assert.equal(selected().includes(name),false,name+' concurrent native OFF must win');
    assert.equal(fs.existsSync(path.join(profile,'package.json.lock')),false);
  }
  assert.deepEqual(fs.readFileSync(path.join(other,'package.json')),otherBefore);
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(profile,'package.json'),'utf8')).fixtureUntouched,{keep:'same bytes and values'});
  console.log('PASS: all seven real native OFF processes wait for actual Host Web ON lock ownership, then win after release; unrelated profile/settings remain unchanged.');
} finally {fs.rmSync(owned,{recursive:true,force:true});}
