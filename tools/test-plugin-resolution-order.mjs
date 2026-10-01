// Differential check: the native inventory and enable path must describe the
// very same bundle that the real rc2 loader will load when package names clash.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
import {testRuntime,requireNativeHost} from './test-runtime-fixture.mjs';
const runtime=testRuntime('raw');requireNativeHost(runtime);
const {resolveBundleDir,loadProfileDirectory}=await import(pathToFileURL(path.join(runtime,'node_modules/@deepseek-ai/dsh-app-boot/lib/index.js')));
const root=path.resolve(import.meta.dirname,'..');
const temporary=fs.mkdtempSync(path.join(root,'app/build/plugin-resolution-order-'));
const name='@author/optional-fixture';
const install=path.join(temporary,'usr/local/lib/node_modules/@deepseek-ai/dsh');
const profile=path.join(temporary,'root/.dsh/profiles/web');
const user=path.join(profile,'node_modules',name),provided=path.join(install,'node_modules',name);
const put=(file,value)=>{fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,typeof value==='string'?value:JSON.stringify(value));};
try {
  put(path.join(install,'package.json'),{name:'@deepseek-ai/dsh',version:'0.2.0-rc.2'});
  for(const [directory,version]of [[provided,'0.2.0'],[user,'9.0.0-user-edit']]){
    put(path.join(directory,'package.json'),{name,version,dsh:{bundle:{patch:'cordis.patch.yml'}}});
    put(path.join(directory,'cordis.patch.yml'),'[]\n');
  }
  put(path.join(user,'user-edit.js'),'// Keep the user-owned source when a runtime bundle wins.\n');
  put(path.join(profile,'package.json'),{name:'fixture-profile',version:'1.0.0',dependencies:{[name]:'9.0.0-user-edit'},dsh:{profile:{bundles:[name]}}});
  const original=fs.readFileSync(path.join(user,'user-edit.js'));
  const anchor=path.join(install,'package.json');
  const actual=resolveBundleDir('dsh',name,anchor,profile);
  const loaded=loadProfileDirectory('dsh',profile,anchor);
  assert.equal(fs.realpathSync(actual),fs.realpathSync(provided));
  assert.equal(loaded.skippedBundles.length,0);
  assert.equal(fs.realpathSync(loaded.layers[0].packageDir),fs.realpathSync(provided));
  const script=`import importlib.util,json,os
spec=importlib.util.spec_from_file_location('manager',os.path.join(os.environ['DSHA_RESOLUTION_ASSETS'],'plugin-manager.py'))
manager=importlib.util.module_from_spec(spec);spec.loader.exec_module(manager)
captured={}
def result(status,message,**extra): captured.update(extra)
manager.result=result
name=os.environ['DSHA_RESOLUTION_NAME']
manager.cmd_list()
item=next(row for row in captured['items'] if row['name']==name)
print(json.dumps(dict(resolve=manager.resolve_plugin_dir(name),entity=manager.local(manager.builtin.entity_dir(name)),version=item['version'],runtimeProvided=item['runtimeProvided'],deletable=item['deletable'])))
`;
  const python=process.env.DSHA_PYTHON||'python';
  const result=spawnSync(python,['-B','-c',script],{cwd:root,encoding:'utf8',env:{...process.env,DSHA_TEST_ROOT:temporary,DSH_HOME:'/root/.dsh',DSHA_RESOLUTION_ASSETS:path.join(root,'app/src/main/assets'),DSHA_RESOLUTION_NAME:name}});
  assert.equal(result.status,0,result.stderr||result.error?.message);
  const native=JSON.parse(result.stdout);
  assert.equal(fs.realpathSync(native.resolve),fs.realpathSync(actual),'inventory describes different bytes from real app-boot');
  assert.equal(fs.realpathSync(native.entity),fs.realpathSync(actual),'enable path differs from real app-boot');
  assert.equal(native.version,'0.2.0');assert.equal(native.runtimeProvided,true);assert.equal(native.deletable,false);
  assert.deepEqual(fs.readFileSync(path.join(user,'user-edit.js')),original);
  assert.equal(JSON.parse(fs.readFileSync(path.join(user,'package.json'),'utf8')).version,'9.0.0-user-edit');
  console.log('PASS: actual rc2 app-boot, native inventory and enable resolution select the same installation bundle; same-name user source is preserved.');
} finally {
  fs.rmSync(temporary,{recursive:true,force:true});
}
