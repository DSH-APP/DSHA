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
  return {entry:{id,rev,platform:'web',inject:[],external:[],url:'/plugins/'+id+'/client.js'}, bundle:Buffer.from(code), sourceMap};
}
const a = record('fixture-a','console.log("中文🙂");\n');
const b = record('fixture-b','console.log("B");\n', 'two', {parsed:{version:3,sources:['src/b.ts'],names:[],mappings:'AAAA',sourcesContent:['const b=1;']},body:Buffer.from('map')});
const normalize = value => JSON.parse(JSON.stringify(value));
for (const list of [[a],[b],[a,b],[b,a]]) {
  assert.deepEqual(normalize(after.buildCombo(list)), normalize(before.buildCombo(list)));
  assert.deepEqual(normalize(after.buildCombo(list,'explicit-revision')), normalize(before.buildCombo(list,'explicit-revision')));
}
let builds = 0;
const build = (records,rev) => { builds++; return after.buildCombo(records,rev); };
const first = createComboCache(build);
const initial = first([a,b]); first([a],a.entry.rev);
assert.equal(builds,2);
let next = createComboCache(build,first.cache);
assert.equal(next([a,b]),initial); next([a],a.entry.rev); assert.equal(builds,2);
next([b,a]); assert.equal(builds,3,'顺序改变必须重新拼接');
b.bundle = Buffer.from('console.log("changed");\n');
next([a,b]); assert.equal(builds,4,'脚本改动必须生效');
b.sourceMap = {...b.sourceMap, parsed:{...b.sourceMap.parsed,mappings:'AACA'}};
next([a,b]); assert.equal(builds,5,'仅映射改变也必须生效');
b.entry.rev = 'three'; next([a,b]); assert.equal(builds,6);
next = createComboCache(build,next.cache); next([a],a.entry.rev);
assert.equal(next.cache.size,1,'不保留移除插件的缓存');
function registry(module) {
  const result = Object.create(module.ClientModuleRegistry.prototype);
  result.table = new Map([[a.entry.id,a],[b.entry.id,b]]);
  result.batchResponses = new Map();
  return result;
}
const oldRegistry=registry(before), newRegistry=registry(after);
for (const change of [()=>{},()=>{},()=>{b.entry.rev='four';b.bundle=Buffer.from('console.log(4);\n');},()=>{oldRegistry.table.delete(b.entry.id);newRegistry.table.delete(b.entry.id);}]) {
  change();
  assert.deepEqual(normalize(newRegistry.compose()),normalize(oldRegistry.compose()));
  assert.deepEqual(normalize([...newRegistry.responses]),normalize([...oldRegistry.responses]));
  assert.deepEqual(normalize([...newRegistry.previousBatchResponses]),normalize([...oldRegistry.previousBatchResponses]));
}
console.log('PASS: 脚本/映射/URL/版本号与上游逐字节一致；复用、重排、HMR、仅映射变化和移除均通过');
