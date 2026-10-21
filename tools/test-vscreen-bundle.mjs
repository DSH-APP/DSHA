// 真实锁定 rc2 组合、Cordis 工具与 MCP stdio 生命周期；不调用模型或设备。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {verifyRuntimeContent} from './test-runtime-fixture.mjs';

const root=path.resolve(import.meta.dirname,'..');
const runtime=process.env.DSHA_TEST_RUNTIME||JSON.parse(fs.readFileSync(path.join(root,'app/build/test-runtimes/current.json'),'utf8')).raw;
process.env.DSHA_BUILTIN_TEST_RUNTIME=runtime;
await import('./fixtures/builtin-host-hooks.mjs');
const proof=JSON.parse(fs.readFileSync(path.join(runtime,'dsha-test-runtime.json'),'utf8'));
const expected=JSON.parse(fs.readFileSync(path.join(root,'tools/dsh-runtime/package.json'),'utf8')).dependencies['@deepseek-ai/dsh'];
assert.equal(JSON.parse(fs.readFileSync(path.join(runtime,'node_modules/@deepseek-ai/dsh/package.json'),'utf8')).version,expected);
assert.equal(proof.lockSha256,createHash('sha256').update(fs.readFileSync(path.join(root,'tools/dsh-runtime/package-lock.json'))).digest('hex'));
assert.equal(proof.platform.os,process.platform);assert.equal(proof.platform.cpu,process.arch);
verifyRuntimeContent(runtime,proof);
const load=async name=>import(pathToFileURL(path.join(runtime,'node_modules',name,'lib/index.js')));
const {Context}=await load('@deepseek-ai/cordis');
const {default:ToolRuntime}=await load('@deepseek-ai/dsh-tools');
const {default:SystemPrompt}=await load('@deepseek-ai/dsh-system-prompt');
const {loadProfileDirectory,composeEntries}=await load('@deepseek-ai/dsh-app-boot');
const directory=fs.mkdtempSync(path.join(root,'app/build/vscreen-bundle-'));
const profile=path.join(directory,'root/.dsh/profiles/web');
const pluginDir=path.join(directory,'root/dsha-tool-vscreen');
const write=(file,value)=>{fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,typeof value==='string'?value:JSON.stringify(value));};
let ctx;
try{
  fs.cpSync(path.join(root,'app/src/main/assets/builtin-plugins/dsh-tool-vscreen'),pluginDir,{recursive:true});
  const anchor=path.join(directory,'installation/package.json');
  write(anchor,{name:'@deepseek-ai/dsh',version:expected});
  write(path.join(profile,'package.json'),{name:'vscreen-fixture',version:'1.0.0',dsh:{profile:{bundles:['dsh-tool-vscreen']}},dependencies:{'dsh-tool-vscreen':'link:'+pluginDir}});
  const link=path.join(profile,'node_modules/dsh-tool-vscreen');fs.mkdirSync(path.dirname(link),{recursive:true});
  fs.symlinkSync(pluginDir,link,process.platform==='win32'?'junction':'dir');
  write(path.join(profile,'cordis.patch.yml'),'[]\n');
  const composed=()=>{const loaded=loadProfileDirectory('dsh',profile,anchor);return composeEntries([...loaded.layers.map(layer=>layer.patches),loaded.patches]);};
  assert.deepEqual(composed().map(row=>[row.id,row.name]),[['dsh-tool-vscreen','dsh-tool-vscreen']]);
  const python=(mode)=>{
    const source=`import importlib.util,os
from pathlib import Path
p=Path(os.environ['DSHA_REGISTER'])
spec=importlib.util.spec_from_file_location('register_vscreen',p)
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
r.builtin_names=lambda:['dsh-tool-vscreen']
r.OFFICIAL_BUNDLES=()
r.ensure_runtime_modules=lambda:0
mode=os.environ['DSHA_MODE']
assert not hasattr(r,'migrate_vscreen_workaround')
if mode=='disable': assert r.disable_plugin('dsh-tool-vscreen')==0
elif mode=='register': assert r.register()==0
elif mode=='enable': assert r.enable_plugin('dsh-tool-vscreen')==0
`;
    const result=spawnSync(process.env.DSHA_PYTHON||'python',['-B','-c',source],{env:{...process.env,DSHA_TEST_ROOT:directory,DSH_HOME:'/root/.dsh',DSHA_MODE:mode,DSHA_REGISTER:path.join(root,'app/src/main/assets/register-builtin-plugins.py')},encoding:'utf8',windowsHide:true,timeout:30000});
    assert.equal(result.status,0,result.stderr||result.stdout||result.error?.message);return result.stdout;
  };
  const plugin=await import(pathToFileURL(path.join(pluginDir,'lib/index.js')));
  const wanted=(await import(pathToFileURL(path.join(pluginDir,'lib/server.cjs')))).tools.map(row=>'mcp__dsha-vscreen__'+row.name).sort();
  for(let turn=0;turn<2;turn++){
    ctx=new Context();await ctx.plugin(SystemPrompt,{});await ctx.plugin(ToolRuntime,{mode:'native'});
    const mounted=ctx.plugin(plugin);await mounted;
    assert.deepEqual(ctx.tools.schemas().map(row=>row.name).sort(),wanted);
    assert.equal(wanted.length,15);
    await mounted.dispose();assert.equal(ctx.tools.schemas().length,0);
    await ctx.fiber.dispose();ctx=null;
  }
  python('disable');assert.equal(composed().filter(row=>row.name==='dsh-tool-vscreen').length,0);
  const disabled=path.join(profile,'node_modules/dsh-tool-vscreen.disabled');assert.equal(fs.readFileSync(disabled,'utf8'),'');
  const custom='# experimental user layer\n[]\n';
  write(path.join(profile,'cordis.patch.yml'),custom);
  python('register');python('register');
  assert.equal(composed().filter(row=>row.name==='dsh-tool-vscreen').length,0);
  assert.ok(fs.existsSync(disabled));
  assert.equal(fs.readFileSync(path.join(profile,'cordis.patch.yml'),'utf8'),custom,'fresh registration must not rewrite user patch layers');
  if(!fs.existsSync(link))fs.symlinkSync(pluginDir,link,process.platform==='win32'?'junction':'dir');
  python('enable');assert.equal(composed().filter(row=>row.name==='dsh-tool-vscreen').length,1);
  assert.ok(!fs.existsSync(disabled));
  console.log('PASS real rc2 fresh composition, preserved custom patch and disabled intent, reenable, and two MCP lifecycles with exactly 15 tools');
}finally{
  if(ctx)await ctx.fiber.dispose();
  const relative=path.relative(path.resolve(root,'app/build'),fs.realpathSync(directory));
  assert.ok(relative && relative!=='..' && !relative.startsWith('..'+path.sep) && !path.isAbsolute(relative));
  fs.rmSync(directory,{recursive:true,force:true});
}
