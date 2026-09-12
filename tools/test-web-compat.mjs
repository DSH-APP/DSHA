import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
const source=await readFile(new URL('../app/src/main/assets/web-integration/compat.js',import.meta.url),'utf8');
const esSource=await readFile(new URL('../app/src/main/assets/web-integration/es-compat.js',import.meta.url),'utf8');

test('PDF 单行前导保持 Base64 换行与空白语义',()=>{
 const ctx=vm.createContext({URL,Response,structuredClone});
 vm.runInContext('delete globalThis.Iterator;delete Uint8Array.fromBase64',ctx);
 vm.runInContext(esSource.trim().replaceAll('\n',' '),ctx);
 assert.equal(vm.runInContext("typeof Iterator",ctx),'function');
 assert.equal(vm.runInContext("Uint8Array.fromBase64('YQ\\n==')[0]",ctx),97);
});

function oldBrowser() {
 const ctx=vm.createContext({URL:class extends URL {},Response:class extends Response {},structuredClone});
 vm.runInContext(`URL.parse=undefined;Promise.withResolvers=undefined;Promise.try=undefined;
  const proto=Object.getPrototypeOf(Object.getPrototypeOf([][Symbol.iterator]()));
  for(const name of Object.getOwnPropertyNames(proto))if(typeof proto[name]==='function')delete proto[name];
  delete globalThis.Iterator;delete Math.sumPrecise;
  for(const name of ['transfer','transferToFixedLength'])delete ArrayBuffer.prototype[name];
  for(const name of ['fromBase64','fromHex'])delete Uint8Array[name];
  for(const name of ['toBase64','toHex','setFromBase64','setFromHex'])delete Uint8Array.prototype[name];
  for(const Type of [Map,WeakMap]){delete Type.prototype.getOrInsert;delete Type.prototype.getOrInsertComputed;}`,ctx);
 vm.runInContext(esSource,ctx);return ctx;
}
test('旧 WebView 和独立 Worker 都能使用 PDF 的迭代器链，提前结束会关闭生成器',()=>{
 for(let realm=0;realm<2;realm++) {
  const ctx=oldBrowser();
  assert.equal(vm.runInContext(`new Set([1,2,3]).keys().filter(x=>x>1).toArray().join(',')`,ctx),'2,3');
  assert.equal(vm.runInContext(`new Map([['x',1],['y',2]]).values().some(x=>x===2)`,ctx),true);
  assert.equal(vm.runInContext(`(()=>{let closed=false;function* values(){try{yield 1;yield 2;}finally{closed=true;}}
   return values().find(x=>x===1)===1&&closed;})()`,ctx),true);
 }
});
test('PDF URL、Promise.try 和精确求和在缺失新接口时仍保留语义',async()=>{
 const ctx=oldBrowser();
 assert.equal(vm.runInContext(`URL.parse('/document.pdf','https://example.test/folder/').href`,ctx),'https://example.test/document.pdf');
 assert.equal(vm.runInContext(`URL.parse('http://[')`,ctx),null);
 assert.equal(vm.runInContext(`Math.sumPrecise([1e20,1,-1e20])`,ctx),1);
 assert.equal(await vm.runInContext(`Promise.try((a,b)=>a+b,2,3)`,ctx),5);
 await assert.rejects(vm.runInContext(`Promise.try(()=>{throw new Error('pdf failure')})`,ctx),/pdf failure/);
});
test('PDF 字体与二进制转换保留字节、偏移和转移后的失效语义',()=>{
 const ctx=oldBrowser();
 assert.equal(vm.runInContext(`Uint8Array.fromBase64('AP+A').toHex()`,ctx),'00ff80');
 assert.equal(vm.runInContext(`new Uint8Array([9,0,255,128,9]).subarray(1,4).toBase64()`,ctx),'AP+A');
 assert.throws(()=>vm.runInContext(`Uint8Array.fromHex('xyz')`,ctx));
 assert.equal(vm.runInContext(`(()=>{const b=new Uint8Array([1,2,3]).buffer;const t=b.transferToFixedLength(5);
  return b.byteLength===0&&new Uint8Array(t).join(',')==='1,2,3,0,0';})()`,ctx),true);
});
test('兼容脚本重复注入不改变已有 Iterator 和已补齐的方法',()=>{
 const ctx=oldBrowser();vm.runInContext(`globalThis.savedIterator=Iterator;globalThis.savedFind=Iterator.prototype.find;`,ctx);
 vm.runInContext(esSource,ctx);
 assert.equal(vm.runInContext(`Iterator===savedIterator&&Iterator.prototype.find===savedFind`,ctx),true);
});
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
