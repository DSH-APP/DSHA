// Verify the actual pinned recovery overlay, including script order and PDF Worker prelude.
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import path from 'node:path';
import vm from 'node:vm';

const output = process.argv[2];
assert.ok(output, 'provide the generated recoveryAssets directory');
const root = path.resolve(import.meta.dirname, '..');
const descriptor = JSON.parse(await readFile(path.join(output, 'recovery-runtime.json'), 'utf8'));
const rows = descriptor.overlays;
assert.equal(rows.length, 4);
for (const name of ['web-integration/es-compat.js','web-integration/startup.js',
  `recovery-recipes/${descriptor.dshVersion}/pdf-compat-patch.json`,`recovery-recipes/${descriptor.dshVersion}/language-patch.json`,'web-integration/language.js']) {
  const bytes = await readFile(path.join(root,'app/src/main/assets',name));
  assert.equal(descriptor.overlayInputs[name],createHash('sha256').update(bytes).digest('hex'));
}
const contents = {};
for (const row of rows) {
  const bytes = await readFile(path.join(output, row.asset));
  assert.equal(createHash('sha256').update(bytes).digest('hex'), row.sha256);
  assert.equal(bytes.length, row.bytes);
  contents[path.basename(row.asset)] = bytes.toString('utf8');
}
const html = contents['index.html'];
const begin = html.indexOf('<!-- DSHA_BROWSER_COMPAT_BEGIN -->');
const end = html.indexOf('<!-- DSHA_BROWSER_COMPAT_END -->');
assert.ok(begin >= 0 && end > begin);
assert.ok(html.indexOf('<script', begin) < end && html.indexOf('<script', end) > end);
assert.ok(html.indexOf('<script') < html.indexOf('<script', end));
assert.match(html.slice(begin, end), /AbortSignal|Iterator/);

const pdf = contents['client.pdf.js'];
const es = (await readFile(path.join(root, 'app/src/main/assets/web-integration/es-compat.js'), 'utf8')).trim().replaceAll('\n', ' ');
assert.ok(pdf.includes('/* DSHA_PDF_COMPAT_V1 */ ' + es));
assert.ok(pdf.includes('new Blob([' + JSON.stringify(es + '\n') + ', _dsh_pdf_worker_default,'));
let registered;
vm.runInNewContext(pdf, {window:{__ModuleLoader__:{load(entry){registered=entry;}}}, URL, Response, structuredClone});
assert.equal(registered.id, '@deepseek-ai/dsh-client-ui-sidebar-documentpreview');
const workerLiteral = pdf.match(/var _dsh_pdf_worker_default = (".*");/);
assert.ok(workerLiteral);
const worker = vm.runInNewContext(workerLiteral[1]);
assert.match(worker, /Iterator\.prototype\.join/);
assert.match(worker, /Math\.sumPrecise/);
assert.match(contents['client-resources.js'], /DSHA_FILE_RESOURCE_URL_V1/);
const localeSource = contents['client-locale.js'];
const languageRecipe = JSON.parse(await readFile(path.join(root, 'app/src/main/assets/language-patch.json'), 'utf8'));
for (const patch of languageRecipe.patches) {
  const after = (patch.prependAsset
    ? await readFile(path.join(root, 'app/src/main/assets', patch.prependAsset), 'utf8') + '\n' : '') + patch.after;
  assert.equal(localeSource.split(after).length - 1, 1, 'locked locale source anchor must be patched exactly once');
}
let localeModule;
const events = new Map(), selections = [], disposers = [];
const pageWindow = {
  __DSHA_LANGUAGE__: 'en',
  addEventListener(type, fn) { events.set(type, fn); },
  removeEventListener(type, fn) { if (events.get(type) === fn) events.delete(type); },
  dispatchEvent(event) { if (event.type === 'dsha-language-selected') selections.push(event.detail); events.get(event.type)?.(event); },
};
pageWindow.top = pageWindow;
const pageDocument = {documentElement:{lang:'zh'},body:{textContent:'用户对话原文'},
  querySelector(){return {};}};
const localeContext = vm.createContext({window:pageWindow,document:pageDocument,
  navigator:{languages:['zh'],language:'zh'},
  CustomEvent:class {constructor(type,options){this.type=type;this.detail=options?.detail;}},
  console, URL, Response, structuredClone});
localeContext.window.__ModuleLoader__ = {load(value){localeModule=value;}};
vm.runInContext(localeSource,localeContext);
assert.equal(localeModule.id, '@deepseek-ai/dsh-client-locale');
const localeExports = localeModule.factory(name => name === '@deepseek-ai/dsh-client-store'
  ? {defineStore: value => value} : {});
let service;
await localeExports.apply({fiber:{uid:1},configForms:{get(){return undefined;}},
  provide(name,value){assert.equal(name,'locale');service=value;},
  emit(){},
  effect(factory){disposers.push(factory());},
  slots:{installLocale(){},inject(){},register(){}},
});
assert.equal(service.getSnapshot().active,'en');
assert.equal(pageDocument.documentElement.lang,'en');
// 0.2.2：上游自带 ja/es/fr/de/ru/pt，DSHA 只补 zh-Hant/ko/ar/la；选择器不得重复。
const dshaTags=['zh-Hant','ko','ar','la'],tags=['zh','en','ja','es','fr','de','ru','pt',...dshaTags];
assert.deepEqual([...service.getSnapshot().locales].map(row=>row.id),tags);
for(const tag of tags){
  pageWindow.__DSHA_LANGUAGE__=tag;pageWindow.dispatchEvent({type:'dsha-language'});
  assert.equal(service.getSnapshot().active,tag);
  assert.equal(pageDocument.documentElement.lang,tag);
  assert.equal(pageDocument.documentElement.dir,tag==='ar'?'rtl':'ltr');
  if(dshaTags.includes(tag)){
    const dictionary=JSON.parse(await readFile(path.join(root,'tools/i18n/web-translations',tag+'.json'),'utf8'));
    assert.equal(service.bind('common')('cancel'),dictionary['common:cancel']);
    assert.equal(service.bind('conversation')('placeholder.default'),dictionary['conversation:placeholder.default']);
  }else if(tag!=='zh'&&tag!=='en'){
    // 上游语言使用上游自己的 common 文案，DSHA 不再覆盖。
    assert.notEqual(service.bind('common')('cancel'),'cancel');
  }
}
pageWindow.__DSHA_LANGUAGE__='zh';
pageWindow.dispatchEvent({type:'dsha-language'});
assert.equal(service.getSnapshot().active,'zh');
assert.equal(pageDocument.documentElement.lang,'zh');
service.setLocale('en');
assert.deepEqual(selections,['en']);
pageWindow.__DSHA_LANGUAGE__='invalid';
pageWindow.dispatchEvent({type:'dsha-language'});
assert.equal(service.getSnapshot().active,'en');
assert.equal(pageDocument.body.textContent,'用户对话原文');
for (const dispose of disposers) if (typeof dispose === 'function') dispose();
pageWindow.__DSHA_LANGUAGE__='zh';
pageWindow.dispatchEvent({type:'dsha-language'});
assert.equal(service.getSnapshot().active,'en','cancelled locale listener cannot change the service');
assert.equal(events.has('dsha-language'),false);

// The recovery relay is only installed at its separate localhost authority.
const relay = await readFile(path.join(root,'app/src/main/assets/recovery-web-integration/locale-relay.js'),'utf8');
let foreignConnects = 0;
const foreignWindow={addEventListener(){}};foreignWindow.top=foreignWindow;
vm.runInNewContext(relay,{window:foreignWindow,
  location:{protocol:'http:',hostname:'127.0.0.1',port:'3081',username:'',password:''},
  browser:{runtime:{connectNative(){foreignConnects++;throw new Error('foreign origin reached locale bridge');}}}});
assert.equal(foreignConnects,0);
console.log('PASS locked recovery HTML/PDF Worker/resource overlay and real upstream 8 + DSHA 4 language locale, RTL, cancellation, invalid origin');
