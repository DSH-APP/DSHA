import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const root=fs.mkdtempSync(path.resolve('app/build/alpha1-016-intake/observer-fixture-'));
const installation=path.join(root,'installation'),home=path.join(root,'home');
const loader=path.join(installation,'node_modules/@deepseek-ai/cordis-plugin-loader/lib/index.js');
fs.mkdirSync(path.dirname(loader),{recursive:true});
fs.writeFileSync(loader,'exports.Entry=class Entry{constructor(id){this.options={id,name:id};}init(){return Promise.reject(new Error("fixture failure"));}};');
fs.mkdirSync(path.join(home,'profiles/web'),{recursive:true});
fs.writeFileSync(path.join(home,'profiles/web/package.json'),JSON.stringify({dsh:{profile:{bundles:[]}}}));
process.env.DSH_HOME=home;process.env.DSHA_OBSERVER_INSTALL=installation;
const lines=[],write=process.stdout.write.bind(process.stdout);
process.stdout.write=value=>{lines.push(String(value));return true;};
try{
  require('../app/src/main/assets/startup-observer.cjs');
  const {Entry}=require(loader);
  await assert.rejects(new Entry('optional-widget').init(),/fixture failure/);
  await assert.rejects(new Entry('webserver').init(),/fixture failure/);
}finally{process.stdout.write=write;}
const events=lines.filter(line=>line.startsWith('[DSHA_STARTUP] ')).map(line=>JSON.parse(line.slice(15)));
const optional=events.find(e=>e.type==='issue'&&e.message.startsWith('optional-widget'));
const required=events.find(e=>e.type==='issue'&&e.message.startsWith('webserver'));
assert.equal(optional?.fatal,false);assert.equal(required?.fatal,true);
const native=fs.readFileSync('app/src/main/java/com/deepseekharness/app/core/StartupDiagnostics.java','utf8');
assert.ok(native.includes('event.optBoolean("fatal",true)'));
console.log('Alpha.1 startup: optional failures retained without overriding upstream startup policy; required failures remain fatal.');
