import { testRuntime } from './test-runtime-fixture.mjs';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
const runtime=testRuntime('raw');
const require=createRequire(pathToFileURL(resolve(runtime,'package.json')));
const source=readFileSync('app/src/main/assets/profile-settings-review.js','utf8').replace("from 'yaml'",`from '${pathToFileURL(require.resolve('yaml')).href}'`).replace("from '@deepseek-ai/schemastery'",`from '${pathToFileURL(require.resolve('@deepseek-ai/schemastery')).href}'`);
const {review,parseIncoming}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
const secret={type:'string',meta:{role:'secret'}};
function service(){
 let value={model:'current',options:{size:1,apiKey:'retained-secret'}},user=structuredClone(value),revision=0;
 const schema={type:'object',dict:{model:{type:'string'},options:{type:'object',dict:{size:{type:'number'},apiKey:secret}}},'~standard':{validate(v){if(typeof v.model!=='string'||typeof v.options?.size!=='number'||v.options.size<0) return {issues:[{}]};return {value:v};}}};
 return {calls:[],describe(options){const row={ns:'llm',schema,revision,value:structuredClone(value),user:structuredClone(user),base:{model:'default',options:{size:0}}};if(options?.redactSecrets){delete row.value.options.apiKey;delete row.user.options.apiKey;}return [row];},
  async update(ns,patch,expected){assert.equal(ns,'llm');assert.equal(expected,revision);if(patch.model!==undefined&&typeof patch.model!=='string')throw Error('schema refused');this.calls.push(patch);for(const [k,v] of Object.entries(patch))value[k]=v&&typeof v==='object'?{...value[k],...v}:v;user=structuredClone(value);revision++;},
  async mutate(ns,ops,expected){assert.equal(expected,revision);for(const op of ops){if(op.op==='unset'){if(op.path[0]==='model')value.model='default';else if(op.path[0]==='options')value.options.size=0;}else value[op.path[0]]=op.value;}user=structuredClone(value);revision++;}
 };
}
let live=service();let result=await review(live,{mode:'preview'},'- id: llm\n  config:\n    model: restored\n    options:\n      size: 4\n      apiKey: incoming-secret\n');
assert.equal(result.items.length,2);assert(!JSON.stringify(result).includes('incoming-secret'));assert(!JSON.stringify(result).includes('retained-secret'));
assert.equal(live.describe()[0].value.options.apiKey,'retained-secret');
assert.equal(live.describe()[0].value.model,'current');assert.equal(live.describe()[0].value.options.size,1);
live=service();result=await review(live,{mode:'apply',selected:['llm/model']},'- id: llm\n  config:\n    model: restored\n    options:\n      size: 4\n');
assert.equal(live.calls.length,1);assert.equal(live.describe()[0].value.options.size,1);
await review(live,{mode:'verify',expected:result.items},'[]');
await assert.rejects(()=>review(service(),{mode:'verify',expected:result.items},'[]'),/READBACK/);
live=service();result=await review(live,{mode:'reset'},'[]');assert.equal(live.describe()[0].value.model,'default');assert.equal(live.describe()[0].value.options.size,0);assert.equal(live.describe()[0].value.options.apiKey,'retained-secret');
assert(!JSON.stringify(result).includes('retained-secret'));
result=await review(service(),{mode:'preview'},'- insert:\n    - id: arbitrary-code\n      name: evil\n- id: llm\n  config:\n    model: 42\n');
assert.equal(result.items.length,0);assert(result.warnings.includes('SCHEMA_REJECTED:llm/model'));assert(result.warnings.includes('CODE_OR_COMPOSITION_REQUIRES_REVIEW'));
await assert.rejects(()=>review(service(),{mode:'apply',selected:['llm/model']},'- id: llm\n  config:\n    model: 42\n'),/VALIDATION_FAILED/);
assert.throws(()=>parseIncoming('- id: llm\n  config: !!js process.exit()\n'),/YAML/);
assert.throws(()=>parseIncoming('- &a {id: llm, config: {model: ok}}\n- *a\n'),/ALIAS/);
result=await review(service(),{mode:'preview'},'- id: llm\n  config:\n    model: "${process.exit()}"\n');assert.equal(result.items.length,0);
// 锁定 rc1 的真实 SettingsForms / schemastery 验证，不仅测试接口替身。
const {SettingsForms}=await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-settings')).href);
const {resolveConfig}=await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);
const {default:Schema}=await import(pathToFileURL(require.resolve('@deepseek-ai/schemastery')).href);
const Config=Schema.object({model:Schema.string().default('default'),limit:Schema.number().min(1).max(100).default(5)});
Config.meta.volatile=true;
let raw={model:'current',limit:5};const runtimeOwner={Config};
const entry={id:'llm',options:{id:'llm',name:'test-settings',config:raw},fiber:{uid:1,state:2,runtime:runtimeOwner,config:resolveConfig(runtimeOwner,raw)}};
const actual=Object.create(SettingsForms.prototype);actual.revisions=new Map();actual.presentations=new Map();
actual.ownerContext={emit(){},configEditor:{entries:()=>[entry],configuration:()=>[{entry,inherited:{},override:raw}],async edit(_entry,change){const next=change(raw,{});const validated=resolveConfig(runtimeOwner,next);raw=next;entry.options.config=raw;entry.fiber.config=validated;}}};
result=await review(actual,{mode:'preview'},'- id: llm\n  config:\n    model: valid\n    limit: 101\n');
assert.equal(result.items.length,1);assert(result.warnings.includes('SCHEMA_REJECTED:llm/limit'));assert.equal(actual.describe()[0].value.limit,5);
result=await review(actual,{mode:'apply',selected:['llm/limit']},'- id: llm\n  config:\n    limit: 42\n');assert.equal(actual.describe()[0].value.limit,42);
await review(actual,{mode:'verify',expected:result.items},'[]');
console.log('PASS profile settings: field selection, schema rejection, executable quarantine, secret retention, reset and readback');
