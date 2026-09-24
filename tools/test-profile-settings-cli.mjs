import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,existsSync,symlinkSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';
const runtime=resolve(process.env.DSHA_TEST_RUNTIME??JSON.parse(readFileSync('app/build/test-runtimes/current.json','utf8')).managed);
const modules=join(runtime,'node_modules');
const expectedVersion=JSON.parse(readFileSync(join(modules,'@deepseek-ai/dsh/package.json'))).version;
const root=mkdtempSync(resolve('app/build/profile-settings-cli-'));
const profile='dsha-recovery-'+randomUUID().replaceAll('-','').slice(0,16);
const home=join(root,'home'),dir=join(home,'profiles',profile),plugin=join(root,'review'),fixture=join(root,'fixture');
for(const path of [dir,plugin,fixture,join(root,'isolated-home')])mkdirSync(path,{recursive:true});
const json=(path,value)=>writeFileSync(path,JSON.stringify(value,null,2));
symlinkSync(modules,join(plugin,'node_modules'),'junction');symlinkSync(modules,join(fixture,'node_modules'),'junction');
mkdirSync(join(dir,'node_modules'),{recursive:true});
symlinkSync(plugin,join(dir,'node_modules','dsha-profile-settings-review'),'junction');
symlinkSync(fixture,join(dir,'node_modules','dsha-settings-fixture'),'junction');
json(join(plugin,'package.json'),{name:'dsha-profile-settings-review',version:'1.0.0',type:'module',main:'index.js',dsh:{bundle:{patch:'./cordis.patch.yml'}}});
writeFileSync(join(plugin,'index.js'),readFileSync('app/src/main/assets/profile-settings-review.js'));
writeFileSync(join(plugin,'cordis.patch.yml'),'- insert:\n    - id: dsha-profile-settings-review\n      name: dsha-profile-settings-review\n');
json(join(fixture,'package.json'),{name:'dsha-settings-fixture',version:'1.0.0',type:'module',main:'index.js',dsh:{bundle:{patch:'./cordis.patch.yml'}}});
writeFileSync(join(fixture,'index.js'),`import Schema from '@deepseek-ai/schemastery';
export const Config=Schema.object({model:Schema.string().default('default'),limit:Schema.number().min(1).max(100).default(5),apiKey:Schema.string().role('secret').default('')}).volatile();
export function apply(){}
`);
writeFileSync(join(fixture,'cordis.patch.yml'),'- insert:\n    - id: dsha-settings-fixture\n      name: dsha-settings-fixture\n');
json(join(dir,'package.json'),{name:profile,private:true,dependencies:{'dsha-profile-settings-review':'link:'+plugin,'dsha-settings-fixture':'link:'+fixture},dsh:{profile:{bundles:['@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app','dsha-settings-fixture','dsha-profile-settings-review'],patchReload:'startup'}}});
const baseline='- id: dsha-settings-fixture\n  config:\n    model: initial\n    limit: 7\n    apiKey: test-only-secret\n';
writeFileSync(join(dir,'cordis.patch.yml'),baseline);
const source='- id: dsha-settings-fixture\n  config:\n    model: restored\n    limit: 101\n    apiKey: replacement-must-not-apply\n';
async function run(request,incoming=source){
 const nonce=randomUUID().replaceAll('-',''),invocation=join(root,nonce);mkdirSync(invocation);json(join(invocation,'request.json'),{...request,nonce});writeFileSync(join(invocation,'incoming.yml'),incoming);
 const child=spawn(process.execPath,[join(modules,'@deepseek-ai/dsh/lib/bin.js'),'--profile',profile,'--no-open','--host','127.0.0.1','--port','0'],{cwd:root,windowsHide:true,env:{...process.env,DSH_HOME:home,HOME:join(root,'isolated-home'),USERPROFILE:join(root,'isolated-home'),BROWSER:'true',DSHA_PROFILE_SETTINGS_DIRECTORY:invocation,DSHA_PROFILE_SETTINGS_NONCE:nonce,DSHA_RUNTIME_TRIAL_NONCE:nonce,NARB_DISABLE_NATIVE_CACHE:'1',DEEPSEEK_API_KEY:''},stdio:['ignore','pipe','pipe']});
 let logs='';child.stdout.on('data',x=>logs=(logs+x).slice(-16000));child.stderr.on('data',x=>logs=(logs+x).slice(-16000));
 try{const until=Date.now()+90000;while(!existsSync(join(invocation,'result.json'))){if(child.exitCode!==null)throw Error('CLI_EXIT '+child.exitCode+'\n'+logs);if(Date.now()>until)throw Error('CLI_TIMEOUT\n'+logs);await new Promise(r=>setTimeout(r,100));}
  const result=JSON.parse(readFileSync(join(invocation,'result.json'),'utf8'));assert.equal(result.nonce,nonce);assert.notEqual(result.status,'FAILED',JSON.stringify(result));return result;
 }finally{child.kill('SIGTERM');await new Promise(r=>{if(child.exitCode!==null)r();else child.once('exit',r);});}
}
const preview=await run({mode:'preview'});assert(preview.items.some(r=>r.id==='dsha-settings-fixture/model'));assert(preview.warnings.includes('SCHEMA_REJECTED:dsha-settings-fixture/limit'));assert(!JSON.stringify(preview).includes('test-only-secret'));assert(!JSON.stringify(preview).includes('replacement-must-not-apply'));
writeFileSync(join(dir,'cordis.patch.yml'),baseline);
const applied=await run({mode:'apply',selected:['dsha-settings-fixture/model']});const patch=readFileSync(join(dir,'cordis.patch.yml'),'utf8');assert(patch.includes('restored'));assert(patch.includes('test-only-secret'));assert(!patch.includes('replacement-must-not-apply'));assert(patch.includes('7'));
await run({mode:'verify',expected:applied.items});
const reset=await run({mode:'reset',selected:['dsha-settings-fixture/model','dsha-settings-fixture/limit']},'[]');await run({mode:'verify',expected:reset.items},'[]');
assert(readFileSync(join(dir,'cordis.patch.yml'),'utf8').includes('test-only-secret'));assert.equal(reset.items.find(r=>r.field==='model').actual,'default');assert.equal(reset.items.find(r=>r.field==='limit').actual,5);
console.log('PASS current DSH CLI: isolated schema preview → selection → persisted patch → fresh process readback; reset preserves secret');
console.log('Synthetic fixture retained: '+root);
