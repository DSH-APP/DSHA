import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
const assets='app/src/main/assets/',spec=JSON.parse(readFileSync(assets+'language-patch.json','utf8'));
const runtime=process.env.DSHA_TEST_RUNTIME||'app/build/locked-dsh-runtime';
let source=readFileSync(runtime+'/node_modules/'+spec.module,'utf8');
for(const patch of spec.patches){const after=(patch.prependAsset?readFileSync(assets+patch.prependAsset,'utf8')+'\n':'')+patch.after;
 assert.equal(source.split(patch.before).length-1,1);source=source.replace(patch.before,()=>after);
}
new vm.Script(source);
for(const patch of spec.patches){const after=(patch.prependAsset?readFileSync(assets+patch.prependAsset,'utf8')+'\n':'')+patch.after;assert.equal(source.split(after).length-1,1);}
const helper=readFileSync(assets+'web-integration/language.js','utf8');
test('原生语言通过 locale 服务切换，双向变化可订阅且能清理',()=>{
 const handlers=new Map(),calls=[];let active='zh';
 const window={__DSHA_LANGUAGE__:'en',addEventListener:(k,v)=>handlers.set(k,v),removeEventListener:(k)=>handlers.delete(k)};
 const document={documentElement:{lang:'zh'},body:{textContent:'用户的中文对话保持原文'}};
 const context=vm.createContext({window,document});vm.runInContext(helper,context);
 const dispose=context.installDshaLanguageBridge({getSnapshot:()=>({active}),setLocale:id=>{calls.push(id);active=id;}});
 assert.deepEqual(calls,['en']);assert.equal(document.documentElement.lang,'en');
 window.__DSHA_LANGUAGE__='zh';handlers.get('dsha-language')();assert.deepEqual(calls,['en','zh']);
 window.__DSHA_LANGUAGE__='invalid';handlers.get('dsha-language')();assert.equal(calls.length,2);
 assert.equal(document.body.textContent,'用户的中文对话保持原文');dispose();assert.equal(handlers.size,0);
});
test('无浏览器运行时保持上游可用',()=>{
 const context=vm.createContext({});vm.runInContext(helper,context);assert.equal(typeof context.installDshaLanguageBridge({}),'function');
});
test('较晚到达的旧 Host 偏好不能覆盖原生语言',()=>{
 const method=source.match(/resolveActive\(\) \{([\s\S]*?)\n\t\t\t\}/)[1];
 const context=vm.createContext({window:{__DSHA_LANGUAGE__:'en'},localeKey:x=>x});
 const resolve=vm.runInContext('(function(){'+method+'})',context);
 const state={preference:'zh',provisional:'zh',catalog:new Map([['zh',{id:'zh'}],['en',{id:'en'}]])};
 assert.equal(resolve.call(state),'en');delete context.window.__DSHA_LANGUAGE__;assert.equal(resolve.call(state),'zh');
});
