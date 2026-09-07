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
