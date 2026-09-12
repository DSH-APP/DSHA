// 使用锁定的上游安装树核验补丁边界、行号及 Worker 兼容前导；真实渲染另在手机验证。
import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
import vm from 'node:vm';
const root=new URL('../',import.meta.url), assets=new URL('app/src/main/assets/',root);
const spec=JSON.parse(await readFile(new URL('pdf-compat-patch.json',assets),'utf8'));
const tree=process.argv[2];assert.ok(tree,'提供锁定的 node_modules 目录');
const source=await readFile(`${tree}/${spec.module}`,'utf8');
const compatibility=(await readFile(new URL(spec.asset,assets),'utf8')).trim().replaceAll('\n',' ');
function patch(source,before,after){const count=(s,p)=>s.split(p).length-1;
 if(count(source,after)===1&&count(source,before)===count(after,before))return source;
 assert.equal(count(source,before),1,'上游锚点必须唯一');assert.equal(count(source,after),0);
 return source.replace(before,()=>after);
}
function apply(source){return patch(patch(source,spec.mainBefore,`/* DSHA_PDF_COMPAT_V1 */ ${compatibility} ${spec.mainBefore}`),
 spec.workerBefore,`new Blob([${JSON.stringify(compatibility+'\n')}, _dsh_pdf_worker_default,`);}
const updated=apply(source);assert.equal(apply(updated),updated);
assert.equal(updated.split('\n').length,source.split('\n').length,'主线程上游行号保持不变');
assert.throws(()=>apply(source.replaceAll(spec.workerBefore,'changed upstream worker')));
let registered;vm.runInNewContext(updated,{window:{__ModuleLoader__:{load(entry){registered=entry;}}},URL,Response,structuredClone});
assert.equal(registered.id,'@deepseek-ai/dsh-client-ui-sidebar-documentpreview');
assert.equal(typeof registered.factory,'function');
const workerLiteral=source.match(/var _dsh_pdf_worker_default = (".*");/);
assert.ok(workerLiteral);const worker=vm.runInNewContext(workerLiteral[1]);
assert.ok(worker.includes('Iterator.prototype.join')&&worker.includes('Math.sumPrecise'));
assert.ok(updated.includes('new Blob(['+JSON.stringify(compatibility+'\n')+', _dsh_pdf_worker_default,'));
console.log('PASS 锁定 PDF 插件补丁：准确锚点、幂等、失败拒绝、行号保持、页面注册、独立 Worker 注入');
const registry=await readFile(`${tree}/${spec.resourceModule}`,'utf8');
const fixed=patch(registry,spec.resourceBefore,spec.resourceAfter);
assert.equal(patch(fixed,spec.resourceBefore,spec.resourceAfter),fixed);
const fn=fixed.slice(fixed.indexOf('function protocolOf(address)'),fixed.indexOf('\n\t\tfunction idle('));
class OldURL extends URL {get hostname(){return this.protocol==='dsh-resource:'?'':super.hostname;}}
for(const Type of [OldURL,URL]) {
 const ctx=vm.createContext({URL:Type});vm.runInContext(fn,ctx);
 for(const path of ['absolute/root/file.pdf','session/owned/file.pdf'])assert.equal(ctx.protocolOf('dsh-resource://file/'+path),'file');
 for(const bad of ['http://file/root/a','file:///root/a','dsh-resource://file.evil/root/a','dsh-resource://file@evil/root/a'])
  assert.notEqual(ctx.protocolOf(bad),'file');
}
console.log('PASS 旧 WebView 非特殊 URL：文件资源正确分派，普通 URL、相似主机和用户信息不能冒充文件协议');
