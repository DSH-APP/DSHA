import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createRequire} from 'node:module';
import {createHash, randomBytes} from 'node:crypto';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {countNewlines, createComboCache} from '../app/src/main/assets/client-combo-cache/index.js';

const root = path.resolve(import.meta.dirname, '..');
const runtime = path.resolve(process.env.DSHA_TEST_RUNTIME || path.join(root, 'app/build/locked-dsh-runtime'));
const original = fs.readFileSync(path.join(runtime, 'node_modules/@deepseek-ai/dsh-client-modules/lib/index.js'), 'utf8');
const recipe = JSON.parse(fs.readFileSync(path.join(root, 'app/src/main/assets/client-combo-patch.json'), 'utf8'));
let patched = original;
for (const {before, after} of recipe.patches) {
  assert.equal(patched.split(before).length, 2);
  patched = patched.replace(before, after);
}
function load(source) {
  const context = {createRequire, createHash, randomBytes, ...fs, ...path, fileURLToPath, pathToFileURL,
    Service:class {}, URL, Buffer, Map, Set, Response, countNewlines, createComboCache};
  return vm.runInNewContext(source.replace(/^import [^\n]*\n/gm, '').replace(/^export .*;\s*$/m, '')
    + '\n({buildCombo,ClientModuleRegistry,newlineCount})', context);
}
const before = load(original), after = load(patched);
for (const source of ['', '\n', '中文🙂\r\n第二行\n', 'x'.repeat(2_000_000)+'\nend'])
  assert.equal(after.newlineCount(source), before.newlineCount(source));
function record(id, code, rev='one', sourceMap) {
  return {entry:{id,rev,platform:'web',inject:[],external:[],url:'/plugins/'+id+'/client.js'}, meta:{clientPath:'/fixture/'+id+'/client.js'}, bundle:Buffer.from(code), sourceMap};
}
const a = record('fixture-a','console.log("中文🙂");\n');
const b = record('fixture-b','console.log("B");\n', 'two', {parsed:{version:3,sources:['src/b.ts'],names:[],mappings:'AAAA',sourcesContent:['const b=1;']},body:Buffer.from('map')});
const normalize = value => JSON.parse(JSON.stringify(value));
const sourceMapOf = filename => [a,b].find(row=>row.meta.clientPath===filename)?.sourceMap?.parsed;
async function equalArtifact(actual,expected) {
  assert.deepEqual(normalize(actual),normalize(expected));
  assert.deepEqual(await actual.scriptBody(),await expected.scriptBody());
  assert.deepEqual(await actual.sourceMapBody(),await expected.sourceMapBody());
}
for (const list of [[a],[b],[a,b],[b,a]]) {
  await equalArtifact(after.buildCombo(list,sourceMapOf),before.buildCombo(list,sourceMapOf));
  await equalArtifact(after.buildCombo(list,sourceMapOf,'explicit-revision'),before.buildCombo(list,sourceMapOf,'explicit-revision'));
}
let builds = 0;
const build = (records,maps,rev) => { builds++; return after.buildCombo(records,maps,rev); };
const first = createComboCache(build);
const initial = first([a,b],sourceMapOf); first([a],sourceMapOf,a.entry.rev);
assert.equal(builds,2);
let next = createComboCache(build,first.cache);
assert.equal(next([a,b],sourceMapOf),initial); next([a],sourceMapOf,a.entry.rev); assert.equal(builds,2);
next([b,a],sourceMapOf); assert.equal(builds,3,'顺序改变必须重新拼接');
b.bundle = Buffer.from('console.log("changed");\n');
next([a,b],sourceMapOf); assert.equal(builds,4,'脚本改动必须生效');
b.sourceMap = {...b.sourceMap, parsed:{...b.sourceMap.parsed,mappings:'AACA'}};
next([a,b],sourceMapOf); assert.equal(builds,5,'仅映射改变也必须生效');
b.entry.rev = 'three'; next([a,b],sourceMapOf); assert.equal(builds,6);
next([a,b],()=>undefined);assert.equal(builds,7,'映射加载器变化不能复用旧闭包');
next = createComboCache(build,next.cache); next([a],sourceMapOf,a.entry.rev);
assert.equal(next.cache.size,1,'不保留移除插件的缓存');
function registry(module) {
  const result = Object.create(module.ClientModuleRegistry.prototype);
  result.table = new Map([[a.entry.id,a],[b.entry.id,b]]);
  result.batchResponses = new Map();
  result.responses = new Map();result.readSourceMap=sourceMapOf;
  return result;
}
const oldRegistry=registry(before), newRegistry=registry(after);
for (const change of [()=>{},()=>{},()=>{b.entry.rev='four';b.bundle=Buffer.from('console.log(4);\n');},()=>{oldRegistry.table.delete(b.entry.id);newRegistry.table.delete(b.entry.id);}]) {
  change();
  assert.deepEqual(normalize(newRegistry.compose()),normalize(oldRegistry.compose()));
  assert.deepEqual(normalize([...newRegistry.responses]),normalize([...oldRegistry.responses]));
  assert.deepEqual(normalize([...newRegistry.previousBatchResponses]),normalize([...oldRegistry.previousBatchResponses]));
  for(const [url,response] of newRegistry.responses) {
    assert.deepEqual(await response.body(),await oldRegistry.responses.get(url).body());
  }
}
let reads=0;const lazy=after.buildCombo([a],()=>{reads++;return undefined;});
assert.equal(reads,0);await lazy.scriptBody();assert.equal(reads,0,'普通脚本请求不读取源码映射');
await lazy.sourceMapBody();await lazy.sourceMapBody();assert.equal(reads,1,'映射按需生成并复用');
console.log('PASS: 脚本/映射/URL/版本号与上游逐字节一致；复用、重排、HMR、仅映射变化和移除均通过');
