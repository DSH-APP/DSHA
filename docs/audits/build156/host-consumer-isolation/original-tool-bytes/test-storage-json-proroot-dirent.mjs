#!/usr/bin/env node
// 模拟 proroot bind 的两类异常：unit 根不可靠枚举，以及刚发布的
// 记录暂时不出现在表目录 readdir 中。重开必须用固定路径重读磁盘正文。
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {fileURLToPath,pathToFileURL} from 'node:url';

const repository=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const sourcePath=path.resolve(process.argv[2]||'');
if(!sourcePath.startsWith(repository+path.sep))throw Error('Storage module must be inside the repository');
const source=await fs.readFile(sourcePath,'utf8');
assert.match(source,/DSHA_PROROOT_DIRENT_FALLBACK_V2/);
assert.match(source,/DSHA_PROROOT_DIRECT_RECORD_HINTS_V1/);
assert.doesNotMatch(source,/withFileTypes:\s*true/);
assert.match(source,/const DSHA_RECORD_HINT_LIMIT = 4096;/);
assert.match(source,/dshaRecordHints = new DshaRecordHints\(\)/);
assert.match(source,/this\.dshaRecordHints\.clear\(\)/);

const moduleDir=path.dirname(sourcePath);
const token=`dsha-dirent-test-${process.pid}-${randomUUID()}`;
const fakeName=`.${token}-fs.mjs`;
const backendName=`.${token}-backend.mjs`;
const fakePath=path.join(moduleDir,fakeName);
const backendPath=path.join(moduleDir,backendName);
const fixtureRoot=await fs.mkdtemp(path.join(repository,'app/build/storage-dirent-'));
const storageRoot=path.join(fixtureRoot,'storage');
const nonce=randomUUID().replaceAll('-','');
const descriptor=(prefix,options={})=>({
 name:`${prefix}_${nonce}`,version:1,tables:['items'],hasGlobal:options.hasGlobal===true,layout:'per-record'
});
const record=value=>`${JSON.stringify({version:1,record:value},null,2)}\n`;
const legacy=(name,value)=>`${JSON.stringify({unit:{name,version:1},global:null,tables:{items:{legacy:value}}},null,2)}\n`;

try{
 await fs.writeFile(fakePath,`import * as real from 'node:fs/promises';
import path from 'node:path';
export const mkdir=real.mkdir,open=real.open,rename=real.rename,rm=real.rm;
export let unitRootCalls=0,hiddenTableCalls=0;
export async function readFile(target,...args){
 const text=String(target);
 if(text.includes('dsha_io_${nonce}')&&text.endsWith(path.sep+'probe.json')){
  const error=Error('simulated record I/O failure');error.code='EIO';throw error;
 }
 return real.readFile(target,...args);
}
export async function readdir(target,options){
 if(options?.withFileTypes)throw Error('Dirent access is forbidden in this fixture');
 const text=String(target),base=path.basename(text),parent=path.basename(path.dirname(text));
 if(parent==='storage'&&base.startsWith('dsha_')){
  unitRootCalls++;const error=Error('unit root enumeration is forbidden');error.code='EUNITROOT';throw error;
 }
 if((text.includes('dsha_hint_${nonce}')||text.includes('dsha_fresh_${nonce}'))&&base==='items'){
  hiddenTableCalls++;return [];
 }
 return real.readdir(target,options);
}
`,'utf8');
 const testSource=source.replace('from "node:fs/promises";',`from "./${fakeName}";`);
 assert.notEqual(testSource,source);
 await fs.writeFile(backendPath,testSource,'utf8');
 const backendUrl=pathToFileURL(backendPath).href+`?v=${randomUUID()}`;
 const {JsonStorageBackend}=await import(backendUrl);
 const fake=await import(pathToFileURL(fakePath).href);

 // 后端提示表按唯一固定路径计数，并在真正落盘前保留容量；达到
 // 4096 条后必须明确拒绝，不能静默淘汰一个已承诺可重开的键。
 const boundedBackend=new JsonStorageBackend(storageRoot);
 const virtualDir=path.join(fixtureRoot,'never-materialized');
 for(let index=0;index<4096;index++)await boundedBackend.dshaRecordHints.reserve(virtualDir,`key_${index}`);
 assert.equal(boundedBackend.dshaRecordHints.size,4096);
 await assert.rejects(
  boundedBackend.dshaRecordHints.reserve(virtualDir,'overflow'),
  /DSHA_PROROOT_RECORD_HINT_LIMIT/
 );
 await boundedBackend.close();
 assert.equal(boundedBackend.dshaRecordHints.size,0);

 const backend=new JsonStorageBackend(storageRoot);

 // 表目录枚举始终返回空；成功 put 留下的键提示只用来定位文件，
 // 重开仍必须重新 readFile 并解析版本与 nonce。
 const hinted=descriptor('dsha_hint');
 let unit=await backend.kv.open(hinted);
 await unit.putRecord('items','probe',{nonce});
 await unit.close();
 unit=await backend.kv.open(hinted);
 assert.equal((await unit.loadAll()).tables.items.probe?.nonce,nonce);
 await unit.deleteRecord('items','probe');
 await unit.close();
 unit=await backend.kv.open(hinted);
 assert.deepEqual((await unit.loadAll()).tables.items,{});
 await unit.close();
 unit=await backend.kv.open(hinted);
 await unit.putRecord('items','archived',{nonce,kind:'backup'});
 await unit.backupRecord('items','archived');
 await unit.close();
 unit=await backend.kv.open(hinted);
 assert.deepEqual((await unit.loadAll()).tables.items,{});
 await unit.close();
 assert.ok(fake.hiddenTableCalls>=2);

 // 健康目录在 unit close 时已经能列出记录，提示应立即协调掉；只有
 // 枚举仍陈旧的表才把提示保留到同 backend 的下一次打开。
 const visible=descriptor('dsha_visible');
 const visibleDir=path.join(storageRoot,visible.name,'items');
 unit=await backend.kv.open(visible);
 await unit.putRecord('items','probe',{nonce,kind:'visible'});
 assert.ok(backend.dshaRecordHints.get(visibleDir)?.has('probe'));
 await unit.close();
 assert.equal(backend.dshaRecordHints.get(visibleDir),undefined);

 // 提示只能属于创建它的 backend。同一模块的新 backend 与 fresh import
 // 都不能继承提示；若目录枚举永久返回空，它们必须如实读为空，不能把
 // 进程内提示误报成跨进程持久化修复。真实 fresh-process 回归会在正常
 // 文件系统上重新枚举并读回该记录。
 const freshDescriptor=descriptor('dsha_fresh');
 unit=await backend.kv.open(freshDescriptor);
 await unit.putRecord('items','probe',{nonce,kind:'fresh-boundary'});
 await unit.close();
 unit=await backend.kv.open(freshDescriptor);
 assert.deepEqual((await unit.loadAll()).tables.items.probe,{nonce,kind:'fresh-boundary'});
 await unit.close();
 assert.ok(backend.dshaRecordHints.size>=1);
 const isolatedBackend=new JsonStorageBackend(storageRoot);
 unit=await isolatedBackend.kv.open(freshDescriptor);
 assert.deepEqual((await unit.loadAll()).tables.items,{});
 await unit.close();
 await isolatedBackend.close();
 const freshUrl=pathToFileURL(backendPath).href+`?fresh=${randomUUID()}`;
 const {JsonStorageBackend:FreshBackend}=await import(freshUrl);
 const freshBackend=new FreshBackend(storageRoot);
 unit=await freshBackend.kv.open(freshDescriptor);
 assert.deepEqual((await unit.loadAll()).tables.items,{});
 await unit.close();
 await freshBackend.close();

 // 模拟进程重启后没有写入提示：descriptor 直达表目录，unit 根完全不枚举。
 const scanned=descriptor('dsha_scan');
 await fs.mkdir(path.join(storageRoot,scanned.name,'items'),{recursive:true});
 await fs.writeFile(path.join(storageRoot,scanned.name,'items','probe.json'),record({nonce,kind:'preexisting'}),'utf8');
 unit=await backend.kv.open(scanned);
 assert.deepEqual((await unit.loadAll()).tables.items.probe,{nonce,kind:'preexisting'});
 await unit.close();

 // global.json 的路径同样由 descriptor 确定，不依赖上级目录枚举。
 const globalDescriptor=descriptor('dsha_global',{hasGlobal:true});
 await fs.mkdir(path.join(storageRoot,globalDescriptor.name),{recursive:true});
 await fs.writeFile(path.join(storageRoot,globalDescriptor.name,'global.json'),record({nonce,kind:'global'}),'utf8');
 unit=await backend.kv.open(globalDescriptor);
 assert.deepEqual((await unit.loadAll()).global,{nonce,kind:'global'});
 await unit.close();

 // 存在但 JSON 损坏/版本不匹配的新格式记录仍是 foreign，且必须抑制
 // legacy bootstrap；不能把旧值伪装成本次重开成功。
 const foreign=descriptor('dsha_foreign');
 await fs.mkdir(path.join(storageRoot,foreign.name,'items'),{recursive:true});
 await fs.writeFile(path.join(storageRoot,`${foreign.name}.json`),legacy(foreign.name,{nonce,kind:'legacy'}),'utf8');
 await fs.writeFile(path.join(storageRoot,foreign.name,'items','broken.json'),'{bad json\n','utf8');
 await fs.writeFile(path.join(storageRoot,foreign.name,'items','stale.json'),JSON.stringify({version:2,record:{nonce}}),'utf8');
 unit=await backend.kv.open(foreign);
 assert.deepEqual((await unit.loadAll()).tables.items,{});
 await unit.close();

 // 真实文件系统读取错误不能被吞掉并折叠成“空表”。
 const io=descriptor('dsha_io');
 await fs.mkdir(path.join(storageRoot,io.name,'items'),{recursive:true});
 await fs.writeFile(path.join(storageRoot,io.name,'items','probe.json'),record({nonce}),'utf8');
 unit=await backend.kv.open(io);
 await assert.rejects(unit.loadAll(),error=>error?.code==='EIO');
 await unit.close();

 await backend.close();
 assert.equal(backend.dshaRecordHints.size,0);
 assert.equal(fake.unitRootCalls,0);
 console.log(JSON.stringify({
  status:'PASS',
  scenario:'proroot stale root/table readdir with backend-owned verified direct-path reopen',
  freshModulePermanentEmptyListing:'not-recovered'
 }));
}finally{
 await Promise.allSettled([fs.rm(fakePath,{force:true}),fs.rm(backendPath,{force:true})]);
 const resolved=await fs.realpath(fixtureRoot).catch(()=>fixtureRoot);
 if(!resolved.startsWith(path.join(repository,'app/build')+path.sep))throw Error('Unsafe fixture cleanup');
 await fs.rm(resolved,{recursive:true,force:true});
}
