import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
const source=await readFile(new URL('../app/src/main/assets/web-integration/compat.js',import.meta.url),'utf8');
function legacy(){const ctx=vm.createContext({});vm.runInContext('Promise.withResolvers=undefined',ctx);vm.runInContext(source,ctx);return ctx;}
test('HTML boot-ready marker resolves before the frontend modules start',async()=>{
 const ctx=legacy();vm.runInContext('(globalThis.__DSH_BOOT_READY__ ??= Promise.withResolvers()).resolve("ready")',ctx);
 assert.equal(await ctx.__DSH_BOOT_READY__.promise,'ready');
});
test('Promise subclass and rejection preserve native call semantics',async()=>{
 const ctx=legacy();vm.runInContext('class Derived extends Promise {};globalThis.kit=Derived.withResolvers();globalThis.derived=kit.promise instanceof Derived',ctx);
 assert.equal(ctx.derived,true);ctx.kit.reject(new Error('expected'));await assert.rejects(ctx.kit.promise,/expected/);
 assert.throws(()=>vm.runInContext('Promise.withResolvers.call({})',ctx));
 assert.throws(()=>vm.runInContext('Promise.withResolvers.call(function(){})',ctx));
});
test('existing browser implementation is kept unchanged',()=>{
 const ctx=vm.createContext({});vm.runInContext('globalThis.original=Promise.withResolvers',ctx);vm.runInContext(source,ctx);
 assert.equal(vm.runInContext('original===Promise.withResolvers',ctx),true);
});

function legacyCollections() {
 const ctx=vm.createContext({});
 vm.runInContext('for(const Type of [Map,WeakMap]){delete Type.prototype.getOrInsert;delete Type.prototype.getOrInsertComputed;}',ctx);
 vm.runInContext(source,ctx);return ctx;
}
test('PDF 使用的 Map 插入保留已有 undefined、只计算一次且覆盖回调中的同键写入',()=>{
 const ctx=legacyCollections();
 assert.equal(vm.runInContext(`(()=>{const m=new Map([['existing',undefined]]);let calls=0;
   if(m.getOrInsertComputed('existing',()=>{calls++;return 1;})!==undefined)throw Error('existing');
   const value=m.getOrInsertComputed('missing',key=>{calls++;m.set(key,'temporary');return 'computed';});
   return calls===1&&value==='computed'&&m.get('missing')==='computed'&&m.getOrInsert('missing','unused')==='computed';})()`,ctx),true);
 assert.throws(()=>vm.runInContext("new Map([['x',1]]).getOrInsertComputed('x',null)",ctx));
 assert.throws(()=>vm.runInContext("Map.prototype.getOrInsertComputed.call({},'x',()=>1)",ctx));
 assert.equal(vm.runInContext('new Map().getOrInsertComputed(-0,key=>Object.is(key,0))',ctx),true);
});
test('WeakMap 拒绝无效键且不运行回调，补丁方法不可枚举',()=>{
 const ctx=legacyCollections();
 assert.equal(vm.runInContext(`(()=>{const m=new WeakMap(),key={};let calls=0;
   try{m.getOrInsertComputed(1,()=>calls++);}catch{}
   return calls===0&&m.getOrInsertComputed(key,()=>42)===42&&m.getOrInsert(key,7)===42
     &&!Object.getOwnPropertyDescriptor(Map.prototype,'getOrInsertComputed').enumerable;})()`,ctx),true);
});
test('集合已有原生实现时保持其函数身份',()=>{
 const ctx=vm.createContext({});
 vm.runInContext('Map.prototype.getOrInsertComputed=function nativeFixture(){};globalThis.saved=Map.prototype.getOrInsertComputed;',ctx);
 vm.runInContext(source,ctx);assert.equal(vm.runInContext('saved===Map.prototype.getOrInsertComputed',ctx),true);
});
