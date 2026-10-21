// Explicitly authorized online plugin install in private host profiles; no phone or user profile.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {execFileSync} from 'node:child_process';

const root=path.resolve(import.meta.dirname,'..');
const out=path.join(root,'app/build/build190-fix2-package-commands');
const published=JSON.parse(fs.readFileSync(path.join(out,'published-commands.json'),'utf8'));
assert.equal(published.pnpmVersion,'10.34.5');
assert.match(published.wrappers.pnpm,/exec \/usr\/local\/bin\/node \/usr\/local\/lib\/dsha-pnpm\/bin\/pnpm.cjs "\$@"/);
const cli=path.join(out,'bundled/usr/local/lib/dsha-pnpm/bin/pnpm.cjs');
assert.equal(execFileSync(process.execPath,[cli,'--version'],{encoding:'utf8',windowsHide:true}).trim(),'10.34.5');
const managed=JSON.parse(fs.readFileSync(path.join(root,'app/build/test-runtimes/current.json'),'utf8')).managed;
const module=path.join(managed,'node_modules/@deepseek-ai/dsh-plugin-manager/lib/index.js');
const {PluginManager}=await import(pathToFileURL(module));
const sha=file=>createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const bin=path.join(out,'host-bin');fs.mkdirSync(bin,{recursive:true});
// Only the platform entrypoint is adapted: the same published node + bundled CLI argv.
const command=process.platform==='win32'?'pnpm.cmd':'pnpm';
fs.writeFileSync(path.join(bin,command),process.platform==='win32'
  ?`@"${process.execPath}" "${cli}" %*\r\n`
  :`#!/bin/sh\nexec '${process.execPath.replaceAll("'","'\\''")}' '${cli.replaceAll("'","'\\''")}' "$@"\n`,{mode:0o755});
const config=path.join(out,'isolated.npmrc');
fs.writeFileSync(config,`registry=https://registry.npmjs.org/\nstore-dir=${path.join(out,'pnpm-store').replaceAll('\\','/')}\nfetch-retries=0\nprefer-offline=true\n`);
const globalConfig=path.join(out,'empty-global.npmrc');fs.writeFileSync(globalConfig,'');
const baseEnv={...process.env,NPM_CONFIG_USERCONFIG:config,NPM_CONFIG_GLOBALCONFIG:globalConfig,
  NO_UPDATE_NOTIFIER:'1',PNPM_HOME:bin,DSHA_NATIVE_PLUGIN_MANAGER:'1'};
delete baseEnv.NODE_OPTIONS;delete baseEnv.NODE_PATH;
const nativePath=process.env.PATH??process.env.Path??'';
for(const key of Object.keys(baseEnv))if(key.toLowerCase()==='path')delete baseEnv[key];
const logs=[];
function manager(slot,hasCommand){
  const dir=path.join(out,'online-profiles',slot);assert.equal(fs.existsSync(dir),false,'Use a fresh profile, never reuse an unknown install.');
  fs.mkdirSync(dir,{recursive:true});
  fs.writeFileSync(path.join(dir,'package.json'),JSON.stringify({name:'dsha-command-fixture-'+slot,version:'1.0.0',private:true,dsh:{profile:{bundles:[]}},dependencies:Object.fromEntries(['@deepseek-ai/cordis','@deepseek-ai/schemastery','@deepseek-ai/dsh-settings'].map(name=>[name,'link:'+path.join(managed,'node_modules',name).replaceAll('\\','/')]))}));
  fs.writeFileSync(path.join(dir,'cordis.patch.yml'),'[]\n');
  fs.writeFileSync(path.join(dir,'pnpm-workspace.yaml'),'dangerouslyAllowAllBuilds: true\n');
  const m=Object.create(PluginManager.prototype);
  Object.assign(m,{profile:{name:'web',dir,installAnchor:path.join(managed,'package.json'),startedBundles:[],
      packageManager:{command:'pnpm',env:{...baseEnv,PATH:hasCommand?bin+path.delimiter+nativePath:path.join(out,'missing-command')}}},
    abort:new AbortController(),installs:new Map(),packageOperations:new Set(),installedFrom:new Map(),managementBundles:new Set(),
    outputBytes:128*1024,lockWaitMs:1000,inspectTimeoutMs:20000,githubConnectionTimeoutMs:15000,idleTimeoutMs:90000,
    configuredRegistries:{registry:'https://registry.npmjs.org/',fallbackRegistries:[]},
    ownerContext:{get:()=>undefined,emit:(event,value)=>{logs.push({event,...value});},logger:{warn:(...args)=>logs.push({warning:args.map(String)})}}});
  // Host has no live DSH HMR/UI. Package resolution, pnpm, file lock, compatibility and bundle
  // validation remain production; only post-install application is reported as restart-required.
  m.reload=async()=>({application:'restart-required',restartReason:'no-hmr'});
  return m;
}
const records=[];
async function install(slot,spec,hasCommand){
  const m=manager(slot,hasCommand),started=Date.now();
  const timer=setTimeout(()=>m.abort.abort(),240000);
  try{
    console.log('START',slot,spec);
    const result=await m.installBundle(spec,{enabled:true,saveExact:true,requestId:slot});
    const dir=m.profile.dir,record={slot,spec,elapsedMs:Date.now()-started,result,profile:dir};
    records.push(record);
    if(hasCommand){
      assert.equal(result.application,'restart-required',JSON.stringify(result));
      assert.equal(result.bundle,'dshmarket');
      const entry=path.join(dir,'node_modules/dshmarket');
      const metadata=JSON.parse(fs.readFileSync(path.join(entry,'package.json'),'utf8'));
      assert.ok(fs.existsSync(path.join(entry,metadata.main)),'Actual built plugin entry must exist.');
      const imported=await import(pathToFileURL(path.join(entry,metadata.main)));
      assert.equal(typeof imported.apply,'function');
      record.installed={name:metadata.name,version:metadata.version,entrySha256:sha(path.join(entry,metadata.main)),
        manifestSha256:sha(path.join(dir,'package.json')),lockSha256:sha(path.join(dir,'pnpm-lock.yaml'))};
    }else assert.equal(result.application,'failed',JSON.stringify(result));
    console.log('END',slot,result.application,record.elapsedMs);
    return record;
  }finally{clearTimeout(timer);}
}
let failure;
try{
  const metadata=await (await fetch('https://registry.npmjs.org/dshmarket/latest')).json();
  assert.equal(metadata.name,'dshmarket');
  fs.writeFileSync(path.join(out,'npm-market-metadata.json'),JSON.stringify(metadata,null,2));
  const pinned='dshmarket@'+metadata.version;
  await install('missing-pnpm-'+Date.now(),pinned,false);
  await install('npm-market-'+Date.now(),pinned,true);
  const head=JSON.parse(fs.readFileSync(path.join(out,'github-head.json'),'utf8')).sha;
  assert.match(head,/^[a-f0-9]{40}$/);
  await install('github-market-'+Date.now(),'git+https://github.com/dsh-market/dsh-market.git#'+head,true);
}catch(error){failure={message:error.message,stack:error.stack};console.error(error.stack);process.exitCode=1;}
finally{
  fs.writeFileSync(path.join(out,'online-install-events.jsonl'),logs.map(row=>JSON.stringify(row)).join('\n')+'\n');
  fs.writeFileSync(path.join(out,'online-install-receipt.json'),JSON.stringify({scope:'Actual locked pnpm and official PluginManager installBundle in isolated Windows/host profiles',
    productionModule:module,productionModuleSha256:sha(module),pnpmVersion:'10.34.5',
    wrapperAdaptation:'Windows cmd shim for the actual published node+bundled CLI argv; native Android/proot was not executed.',
    postInstallApplication:'Only reload/HMR is adapted; downloaded plugin main is imported, not activated.',
    noUserProfile:true,noGlobalConfigWrite:true,noCustomSourceSwitch:true,noAutomaticRetry:true,records,failure},null,2));
}
