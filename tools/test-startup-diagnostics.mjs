import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
const root = path.resolve(import.meta.dirname, '..');
const assets = path.join(root, 'app/src/main/assets');
fs.mkdirSync(path.join(root, 'app/build/startup-diagnostics-tests'), {recursive:true});
const fixture = fs.mkdtempSync(path.join(root, 'app/build/startup-diagnostics-tests/run-'));
function put(file, data) { fs.mkdirSync(path.dirname(file), {recursive:true}); fs.writeFileSync(file, typeof data === 'string' ? data : JSON.stringify(data)); }
const profile = path.join(fixture, 'profiles/web');
const plugin = path.join(profile, 'node_modules/test-broken');
put(path.join(profile, 'package.json'), {dsh:{profile:{bundles:['test-broken']}}});
put(path.join(plugin, 'package.json'), {name:'test-broken',version:'1.0.0',type:'module',dsh:{bundle:{patch:'cordis.patch.yml'}}});
put(path.join(plugin, 'cordis.patch.yml'), '- insert:\n    - id: test-broken\n      name: test-broken\n');
const observer = path.join(assets, 'startup-observer.cjs');
function execute(source, language = "zh") {
  put(path.join(plugin, 'index.js'), source);
  return spawnSync(process.execPath, ['--import', pathToFileURL(observer).href, path.join(plugin, 'index.js')], {
    env:{...process.env, DSH_HOME:fixture, DSHA_OBSERVER_INSTALL:fixture, DSHA_STARTUP_PROFILE:'web', DSHA_UI_LANGUAGE:language}, encoding:'utf8', timeout:10000
  });
}
let result = execute("import 'nonexistent-dsha-fixture';\n");
assert.equal(result.status, 1, result.stderr);
let events = result.stdout.split('\n').filter(l => l.startsWith('[DSHA_STARTUP] ')).map(l => JSON.parse(l.slice(15)));
assert(events.some(e => e.type === 'issue' && e.plugin === 'test-broken' && e.message.includes('nonexistent-dsha-fixture')));
result = execute("throw new Error('fixture activation exploded');\n");
assert.equal(result.status, 1);
assert(result.stdout.includes('test-broken') && result.stdout.includes('fixture activation exploded'));
result = execute("console.log('EXECUTED_ONCE');\n");
assert.equal(result.status, 0, result.stderr);
assert.equal(result.stdout.match(/EXECUTED_ONCE/g).length, 1);

for (const language of ['zh', 'en']) {
  result = execute("console.log('EXECUTED_ONCE');\n", language);
  assert.equal(result.status, 0, result.stderr);
  const localized = result.stdout.split('\n').filter(line => line.startsWith('[DSHA_STARTUP] ')).map(line => JSON.parse(line.slice(15)));
  assert(localized.some(event => event.type === 'plugin' && event.message.includes(language === 'en' ? 'Configuration check: test-broken' : '配置检查：test-broken')));
  assert(localized.some(event => event.type === 'stage' && event.message.includes(language === 'en' ? 'Loading DSH' : '加载 DSH')));
  if (language === 'en') assert(localized.every(event => !/[\p{Script=Han}]/u.test(event.message)));
}

const source = fs.readFileSync(path.join(assets, 'web-integration/startup.js'), 'utf8');
const reports = [], listeners = {};
let mutation, currentDefinition, boot = null, composer = null;
const document = { querySelector(selector) { return selector === '[data-dsh-boot]' ? boot : composer; }, getElementById(){return {children:[{}]};} };
const context = {document, console:{info(line){reports.push(JSON.parse(line.slice(12)));}},
  CustomEvent:class {constructor(type,options){this.type=type;this.detail=options.detail;}},
  MutationObserver:class {constructor(callback){mutation=callback;} observe(){} disconnect(){this.stopped=true;}},
  addEventListener(type,callback){listeners[type]=callback;}, dispatchEvent(){}};
context.window = context; context.top = context;
vm.runInNewContext(source, context);
context.__ModuleLoader__ = {load(definition){currentDefinition=definition;return 17;}};
let calls = 0;
assert.equal(context.__ModuleLoader__.load({id:'plugin-a',factory(){calls++;return {apply(x){return this.value+x;}};}}),17);
const exports = currentDefinition.factory();
assert.equal(exports.apply.call({value:4},5),9); assert.equal(calls,1);
context.__ModuleLoader__.load({id:'plugin-reject',factory(){return {apply(){return Promise.reject(new Error('async apply failure'));}};}});
await assert.rejects(currentDefinition.factory().apply(), /async apply failure/);
assert(reports.some(e => e.id==='plugin-reject' && e.type==='issue'));
context.__ModuleLoader__.load({id:'plugin-import',factory(){throw new Error('factory failed');}});
assert.throws(()=>currentDefinition.factory(), /factory failed/);
assert(reports.some(e => e.id==='plugin-import' && e.message.includes('factory failed')));
boot={textContent:'HARNESS Failed to load plugins plugin-reject'}; mutation();
assert(reports.some(e=>e.fatal));
boot=null; composer={}; mutation();
assert(reports.some(e=>e.type==='ready'));
listeners.unhandledrejection({reason:new Error('later error')});
assert.equal(reports.at(-1).fatal,false);
console.log('PASS startup observer: named missing import, thrown module, one execution, browser factory/apply/rejection, boot failure, ready');
