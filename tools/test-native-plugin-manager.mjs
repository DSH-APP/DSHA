// 在隔离目录验证 alpha.2 新管理入口不会越过 DSHA 原生审阅，也不运行安装脚本。
import assert from 'node:assert/strict';
import fs from 'node:fs';import path from 'node:path';import vm from 'node:vm';import {pathToFileURL} from 'node:url';
const runtime=path.resolve(process.env.DSHA_TEST_RUNTIME||'app/build/release136/host-runtime');
const clientRuntime=path.resolve(process.env.DSHA_RAW_RUNTIME||'app/build/locked-dsh-runtime-138');
const base=fs.mkdtempSync(path.resolve('app/build/release136/native-plugin-policy-'));
const sdk=path.join(base,'sdk'),profile=path.join(base,'profile');fs.mkdirSync(profile,{recursive:true});fs.mkdirSync(sdk,{recursive:true});
fs.writeFileSync(path.join(sdk,'package.json'),JSON.stringify({name:'@deepseek-ai/dsh',dependencies:{'dsha-fixture-core':'1.0.0'}}));
fs.writeFileSync(path.join(profile,'package.json'),'{}');
const pkg=path.join(sdk,'node_modules/dsha-fixture-core');fs.mkdirSync(pkg,{recursive:true});
fs.writeFileSync(path.join(pkg,'package.json'),JSON.stringify({name:'dsha-fixture-core',version:'1.0.0',main:'index.js',dsh:{bundle:{patch:'cordis.patch.yml'}}}));
fs.writeFileSync(path.join(pkg,'index.js'),'export {};');fs.writeFileSync(path.join(pkg,'cordis.patch.yml'),'[]');
const {PluginManager}=await import(pathToFileURL(path.join(runtime,'node_modules/@deepseek-ai/dsh-plugin-manager/lib/index.js')));
const {resolveBundleDir}=await import(pathToFileURL(path.join(runtime,'node_modules/@deepseek-ai/dsh-app-boot/lib/index.js')));
const manager=Object.create(PluginManager.prototype);manager.profile={installAnchor:path.join(sdk,'package.json'),dir:profile};
let touched=0;manager.change=()=>{touched++;return Promise.resolve({application:'applied'});};
const old=process.env.DSHA_NATIVE_PLUGIN_MANAGER;process.env.DSHA_NATIVE_PLUGIN_MANAGER='1';
try{
 for(const result of [await manager.installBundle('unreviewed@1.0.0',{enabled:true,approvedBuilds:['unreviewed']}),await manager.removeBundle('unreviewed'),await manager.setBundleEnabled('unreviewed',true)]){
  assert.equal(result.changed,false);assert.equal(result.application,'failed');assert.match(result.error.diagnostic,/DSHA_NATIVE_REVIEW_REQUIRED/);
 }
 assert.equal(touched,0);assert.equal(fs.readFileSync(path.join(profile,'package.json'),'utf8'),'{}');
 assert.equal((await manager.setBundleEnabled('dsha-fixture-core/../../foreign',true)).changed,false);
 await manager.setBundleEnabled('unreviewed',false);assert.equal(touched,1,'停用保留真实上游流程');
 await manager.setBundleEnabled('dsha-fixture-core',true);assert.equal(touched,2,'随包受管目录继续允许启用');
 const shadow=path.join(profile,'node_modules/dsha-fixture-core');fs.mkdirSync(shadow,{recursive:true});
 fs.copyFileSync(path.join(pkg,'package.json'),path.join(shadow,'package.json'));fs.writeFileSync(path.join(shadow,'index.js'),'export {};');
 assert.equal(resolveBundleDir('dsh','dsha-fixture-core',manager.profile.installAnchor,profile),pkg,'同名目录不能覆盖上游锁定的安装锚点');
 const foreign=path.join(profile,'node_modules/third-party');fs.mkdirSync(foreign,{recursive:true});
 fs.writeFileSync(path.join(foreign,'package.json'),JSON.stringify({name:'third-party',version:'1.0.0',main:'index.js'}));fs.writeFileSync(path.join(foreign,'index.js'),'export {};');
 assert.equal(resolveBundleDir('dsh','third-party',manager.profile.installAnchor,profile),foreign);
 const denied=await manager.setBundleEnabled('third-party',true);assert.equal(denied.changed,false);assert.equal(touched,2,'实际已安装的用户包也需要审阅');
 manager.configure=operation=>operation();manager.listPlugins=async()=>[{entryId:'third',moduleName:'third-party',patchId:'third'}];
 manager.change=async(operation,request)=>{const result={...request,changed:false};try{await operation(result);}catch(error){result.error=String(error);}return result;};
 const row=await manager.setPluginEnabled('third',true);assert.match(row.error,/DSHA_NATIVE_REVIEW_REQUIRED/);
 assert.equal(fs.readFileSync(path.join(profile,'package.json'),'utf8'),'{}');
}finally{if(old===undefined)delete process.env.DSHA_NATIVE_PLUGIN_MANAGER;else process.env.DSHA_NATIVE_PLUGIN_MANAGER=old;}

let client;const jsx=(type,props)=>({type,props});const window={__DSHA_NATIVE_PLUGINS__:true,location:{href:''},__ModuleLoader__:{load({factory}){client=factory(name=>{
 if(name==='react/jsx-runtime')return {jsx,jsxs:jsx,Fragment:'fragment'};
 if(name==='@deepseek-ai/dsh-client-store')return {createSnapshotStore(initial){let state=initial;return{getSnapshot:()=>state,set:value=>{state=value;},subscribe:()=>()=>{}};}};
 return {};
})}}};
const navigation=JSON.parse(fs.readFileSync('app/src/main/assets/plugin-manager-navigation-patch.json','utf8'));
const occurrences=(value,part)=>value.split(part).length-1;
function applyNavigation(value){for(const patch of navigation.patches){const before=occurrences(value,patch.before),after=occurrences(value,patch.after);
 if(after===1&&before===occurrences(patch.after,patch.before))continue;
 assert.equal(before,1,'navigation patch anchor changed');assert.equal(after,0,'navigation patch is partially applied');value=value.replace(patch.before,patch.after);
 }return value;}
let source=applyNavigation(fs.readFileSync(path.join(clientRuntime,'node_modules/@deepseek-ai/dsh-client-ui-plugin-manager/lib/client.js'),'utf8'));
const migrated=applyNavigation(fs.readFileSync(path.join(runtime,'node_modules/@deepseek-ai/dsh-client-ui-plugin-manager/lib/client.js'),'utf8'));
assert.equal(migrated,source,'已安装旧补丁必须无重复地迁移到当前补丁');
assert.equal(source.split('function dshaNativeReviewRoute').length-1,1,'review helper must be injected once');
source=source.replace('exports.apply = apply;','exports.__Controller=PluginManagerController; exports.__packageView=packageView; exports.__EnableSwitch=EnableSwitch; exports.apply = apply;');
vm.runInNewContext(source,{window,Map,Set,AbortController,console,URL,encodeURIComponent});
const unresolved={code:'operation-error',diagnostic:'dsh: cannot resolve profile bundle "third-party" from the dsh installation'};
const review=client.__packageView({name:'third-party',installed:true,optional:false,enabled:false,rows:[],error:unresolved},[]);
assert.equal(review.dshaNativeReviewRequired,true);assert.match(review.error.diagnostic,/原生审阅/);assert.doesNotMatch(review.error.diagnostic,/cannot resolve/);
assert.equal(client.__EnableSwitch({pkg:review,title:'third-party',t:()=>'',busy:false,onSetEnabled(){}}).props.disabled,false,'待审阅插件入口必须可点击');
const realFailure=client.__packageView({name:'third-party',installed:true,optional:false,enabled:false,rows:[],error:{code:'operation-error',diagnostic:'invalid manifest'}},[]);
assert.equal(realFailure.error.diagnostic,'invalid manifest');assert.equal(client.__EnableSwitch({pkg:realFailure,title:'third-party',t:()=>'',busy:false,onSetEnabled(){}}).props.disabled,true,'真实错误仍须阻止启用');
window.__DSHA_NATIVE_PLUGINS__=false;
const desktop=client.__packageView({name:'third-party',installed:true,optional:false,enabled:false,rows:[],error:unresolved},[]);
assert.equal(desktop.error.diagnostic,unresolved.diagnostic,'非 DSHA 网页不能显示原生审阅指引');
window.__DSHA_NATIVE_PLUGINS__=true;
const controller=new client.__Controller({get remote(){throw Error('Native navigation must not call an install RPC');}});
controller.patchInstall({spec:'@example/demo@1.2.3'});await controller.runInstall();
assert.equal(new URL(window.location.href).searchParams.get('url'),'@example/demo@1.2.3');
controller.patch({packages:[{name:'third-party',dshaNativeReviewRequired:true,rows:[{entryId:'entry',dshaNativeReviewRequired:true}]}]});
const actions=controller.inject({});
for(const invoke of [()=>actions.setEnabled('third-party',true),()=>actions.uninstall('third-party'),()=>actions.setRowEnabled('entry',true)]){
 window.location.href='';invoke();assert.equal(window.location.href,'https://dsha.cc/app/plugins');
}
console.log('PASS: review-required errors become an actionable native route without hiding real errors; install/remove/bundle and row activation require native review; managed runtime enables normally; pinned anchor wins over shadow names; client navigates without install RPC.');
