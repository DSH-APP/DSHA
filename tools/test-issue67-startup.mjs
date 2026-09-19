import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
const root=path.resolve(import.meta.dirname,'..');
const runtime=path.join(root,'app/build/rc2-20260911/locked-runtime/node_modules');
const {resolveBundleDir}=await import(pathToFileURL(path.join(runtime,'@deepseek-ai/dsh-app-boot/lib/index.js')).href);
const base=path.join(root,'app/build/issue67-tests');fs.mkdirSync(base,{recursive:true});
const fixture=fs.mkdtempSync(path.join(base,'run-'));
const install=path.join(fixture,'global/node_modules/@deepseek-ai/dsh');
const home=path.join(fixture,'home'),profile=path.join(home,'profiles/web');
function put(file,value){fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,typeof value==='string'?value:JSON.stringify(value));}
function plugin(directory,name){put(path.join(directory,'package.json'),{name,version:'1.0.0',type:'module',exports:{'.':'./index.js'},dsh:{bundle:{patch:'cordis.patch.yml'}}});put(path.join(directory,'cordis.patch.yml'),'[]\n');put(path.join(directory,'index.js'),"throw Error('diagnostics must not execute plugin code');\n");}
const names=['dsh-tavern','modlens','dshmarket','better-sidebar','mcp-panel','liangshen','task-board','agent-teams','session-recycle-bin','modsearch','purge','email','find-plugin','approval-gate','flowglass','skill-explorer','test-global-bundle'];
put(path.join(install,'package.json'),{name:'@deepseek-ai/dsh',version:'0.1.5-rc.2'});
put(path.join(profile,'package.json'),{dsh:{profile:{bundles:names}}});
for(const name of names)plugin(path.join(fixture,'global/node_modules',name),name);
put(path.join(fixture,'entry.mjs'),"console.log('OWNED_ENTRY_ONCE');\n");
const result=spawnSync(process.execPath,['--import',pathToFileURL(path.join(root,'app/src/main/assets/startup-observer.cjs')).href,path.join(fixture,'entry.mjs')],{env:{...process.env,DSH_HOME:home,DSHA_OBSERVER_INSTALL:install,DSHA_STARTUP_PROFILE:'web'},encoding:'utf8',timeout:15000});
assert.equal(result.status,0,result.stderr);assert.equal(result.stdout.match(/OWNED_ENTRY_ONCE/g)?.length,1);
const events=result.stdout.split('\n').filter(line=>line.startsWith('[DSHA_STARTUP] ')).map(line=>JSON.parse(line.slice(15)));
for(const name of names){
  const actual=resolveBundleDir('dsh',name,path.join(install,'package.json'),profile);
  const event=events.find(event=>event.type==='plugin'&&event.plugin===name);
  assert(event,`observer incorrectly reported installed global plugin missing: ${name}`);
  assert.equal(event.path,fs.realpathSync(actual));
  assert(!events.some(event=>event.type==='issue'&&event.plugin===name),name);
}
console.log('PASS issue67: observer matches locked loader for 17 global plugins without executing them');

const specFile=path.join(root,'app/src/main/assets/persona-compat-patch.json');
if(fs.existsSync(specFile)){
  const spec=JSON.parse(fs.readFileSync(specFile,'utf8'));
  let source=fs.readFileSync(path.join(runtime,'@deepseek-ai/dsh-persona/lib/index.js'),'utf8');
  for(const patch of spec.patches){assert.equal(source.split(patch.before).length,2);source=source.replace(patch.before,patch.after);}
  source=source.replace('"@deepseek-ai/schemastery"',JSON.stringify(pathToFileURL(path.join(runtime,'@deepseek-ai/schemastery/lib/index.mjs')).href));
  source=source.replace('import { PERSONA_PREFIX_SECTION, PERSONA_SUFFIX_SECTION } from "@deepseek-ai/dsh-system-prompt";','const PERSONA_PREFIX_SECTION="prefix",PERSONA_SUFFIX_SECTION="suffix";');
  const module=path.join(fixture,'persona.mjs');put(module,source);const {Config,apply}=await import(pathToFileURL(module).href);
  for(const legacy of ['User persona\n保持原文', '', 'quoted "value" and # comment']){
    const input={text:legacy,suffix:'suffix',complete:true};const before=JSON.stringify(input);const config=Config(input);
    assert.equal(config.prefix,legacy);assert.equal(config.suffix,'suffix');assert.equal(config.complete,true);
    // Schemastery may add defaults to its input; the legacy text itself must remain exact.
    assert.equal(input.text,JSON.parse(before).text);
    const sections=[];apply({effect:f=>f(),systemPrompt:{section:value=>{sections.push(value);return()=>{};},getSectionOrder:()=>0,suppressRuntimeContext(){}}},config);
    assert.equal(sections[0].text,legacy);
  }
  assert.equal(Config({prefix:'new text',text:'old text'}).prefix,'new text');
  assert.throws(()=>Config({}),/prefix|required/);assert.throws(()=>Config({text:3}),/prefix|required/);assert.throws(()=>Config({prefix:3,text:'old text'}));
  console.log('PASS issue67: persona text alias preserves prompt and validates modern prefix');
}
