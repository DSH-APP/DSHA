import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {testRuntime} from './test-runtime-fixture.mjs';
const runtime=testRuntime('raw'), module=path.join(runtime,'node_modules/yaml'), require=createRequire(import.meta.url),yaml=require(module);
const parent=fs.mkdtempSync(path.join(os.tmpdir(),'dsha-preset-ast-'));
try{
  const run=(id,agent)=>{const input=path.join(parent,'input.json');fs.writeFileSync(input,JSON.stringify({id,agent}));return spawnSync(process.execPath,['app/src/main/assets/legacy-preset-convert.cjs',input,module],{encoding:'utf8'});};
  for(const agent of ['- id: preserved\n  name: plugin\n  config: {text: "user: prompt"}\n','[ {id: preserved, name: plugin} ]']){
    const result=run('old-preset',agent);assert.equal(result.status,0,result.stderr);const parsed=yaml.parse(JSON.parse(result.stdout).patch);
    assert.equal(parsed[0].insert[0].config.plugins[0].id,'preserved');
  }
  const anchored='---\n# original description\n- id: first\n  name: plugin\n  config: &shared\n    prompt: |\n      user: multi-line prompt\n      keep: literal text\n- id: second\n  name: plugin\n  config: *shared\n';
  const converted=run('old-preset',anchored);assert.equal(converted.status,0,converted.stderr);
  const convertedPatch=JSON.parse(converted.stdout).patch;
  assert.deepEqual(yaml.parse(convertedPatch)[0].insert[0].config.plugins,yaml.parse(anchored));
  assert.match(convertedPatch,/original description/);
  const expression="- id: kept\n  name: plugin\n  config: !!js (()=>{throw Error('must not evaluate')})()\n";
  const preserved=run('old-preset',expression);assert.equal(preserved.status,0,preserved.stderr);assert.match(JSON.parse(preserved.stdout).patch,/!!js/);
  for(const [id,agent]of [['unsafe: id','- id: x\n'],['valid','key: object-not-list\n'],['valid','- id: x\n  id: duplicate\n'],['valid','[unclosed']])assert.notEqual(run(id,agent).status,0);
  console.log('PASS: legacy preset AST preserves loader lists/prompts/!!js without execution; invalid IDs, roots and duplicate keys are rejected.');
}finally{fs.rmSync(parent,{recursive:true,force:true});}
