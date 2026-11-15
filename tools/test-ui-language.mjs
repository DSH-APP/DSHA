import { testRuntime } from './test-runtime-fixture.mjs';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
const assets='app/src/main/assets/',spec=JSON.parse(readFileSync(assets+'language-patch.json','utf8'));
const runtime = testRuntime('raw');
let source=readFileSync(runtime+'/node_modules/'+spec.module,'utf8');
for(const patch of spec.patches){const after=(patch.prependAsset?readFileSync(assets+patch.prependAsset,'utf8')+'\n':'')+patch.after;
 assert.equal(source.split(patch.before).length-1,1);source=source.replace(patch.before,()=>after);
}
new vm.Script(source);
for(const patch of spec.patches){const after=(patch.prependAsset?readFileSync(assets+patch.prependAsset,'utf8')+'\n':'')+patch.after;assert.equal(source.split(after).length-1,1);}
const helper=readFileSync(assets+'web-integration/language.js','utf8');
// 0.2.2：上游自带 ja/es/fr/de/ru/pt，DSHA 只补 zh-Hant/ko/ar/la。
const upstream=['ja','es','fr','de','ru','pt'],dsha=['zh-Hant','ko','ar','la'],fallback={'zh-Hant':'zh',ko:'en',ar:'en',la:'en'};
const tags=['zh','en',...upstream,...dsha];
const messages=JSON.parse(readFileSync('tools/i18n/web-messages.json','utf8')).messages;
const translations=Object.fromEntries(dsha.map(tag=>[tag,JSON.parse(readFileSync('tools/i18n/web-translations/'+tag+'.json','utf8'))]));
// 上游非源语言文案不进入 DSHA 目录；夹具用可辨认的占位文本代替，验证解析链而非译文本身。
const upstreamText=(tag,row)=>'['+tag+'] '+row.en;
const expected=(tag,row)=>tag==='zh'||tag==='en'?row[tag]
 :upstream.includes(tag)?((row.upstreamMissing||[]).includes(tag)?row.en:upstreamText(tag,row))
 :translations[tag][row.id]??row[fallback[tag]];

async function fixture(language='en',stored=new Map()){
 let definition,service;const events=new Map(),selections=[],disposers=[];
 const window={__DSHA_LANGUAGE__:language,
  localStorage:{getItem:key=>stored.has(key)?stored.get(key):null,setItem:(key,value)=>stored.set(key,String(value)),removeItem:key=>stored.delete(key)},
  addEventListener:(type,fn)=>events.set(type,fn),removeEventListener:(type,fn)=>{if(events.get(type)===fn)events.delete(type);},
  dispatchEvent(event){if(event.type==='dsha-language-selected')selections.push(event.detail);events.get(event.type)?.(event);},
  __ModuleLoader__:{load(value){definition=value;}}};window.top=window;
 const document={documentElement:{lang:'en',dir:'ltr'},body:{textContent:'User content, 用户原文, نص المستخدم'},querySelector(){return {};}};
 const context=vm.createContext({window,document,navigator:{languages:['en'],language:'en'},console,
  CustomEvent:class {constructor(type,options){this.type=type;this.detail=options?.detail;}}});
 vm.runInContext(source,context);
 const exported=definition.factory(name=>name==='@deepseek-ai/dsh-client-store'?{defineStore:value=>value}:{});
 await exported.apply({fiber:{uid:1},configForms:{get(){return undefined;}},provide(name,value){assert.equal(name,'locale');service=value;},
  emit(){},effect(factory){disposers.push(factory());},slots:{installLocale(){},inject(){},register(){}}});
 // Register the extracted original dictionaries as their feature fibers do: zh/en plus upstream's six languages.
 const groups=new Map();for(const row of messages){if(!groups.has(row.namespace))groups.set(row.namespace,Object.fromEntries(['zh','en',...upstream].map(tag=>[tag,{}])));
  const group=groups.get(row.namespace);group.zh[row.key]=row.zh;group.en[row.key]=row.en;
  for(const tag of upstream)if(!(row.upstreamMissing||[]).includes(tag))group[tag][row.key]=upstreamText(tag,row);}
 for(const [namespace,data] of groups)if(namespace!=='common'&&namespace!=='settings.locale')disposers.push(service.register(namespace,data));
 return {window,document,service,events,selections,dispose(){for(const fn of disposers.reverse())if(typeof fn==='function')fn();}};
}

test('real locked locale: upstream 8 + DSHA 4 languages without duplicates; every fixed UI key resolves; user content stays verbatim',async()=>{
 const page=await fixture('ar');
 const ids=[...page.service.getSnapshot().locales].map(row=>row.id);
 assert.deepEqual(ids,tags,'selector order: upstream registration, then DSHA additions');
 assert.equal(new Set(ids.map(id=>id.toLowerCase())).size,ids.length);
 for(const tag of tags){
  page.window.__DSHA_LANGUAGE__=tag;page.window.dispatchEvent({type:'dsha-language'});
  assert.equal(page.service.getSnapshot().active,tag);
  assert.equal(page.document.documentElement.lang,tag);
  assert.equal(page.document.documentElement.dir,tag==='ar'?'rtl':'ltr');
  for(const row of messages){
   // common/settings.locale 由 locale 包自身以真实上游译文注册，夹具不替换。
   if(upstream.includes(tag)&&(row.namespace==='common'||row.namespace==='settings.locale'))continue;
   assert.equal(page.service.bind(row.namespace)(row.key),expected(tag,row),tag+':'+row.id);
  }
 }
 assert.equal(page.document.body.textContent,'User content, 用户原文, نص المستخدم');
 assert.deepEqual(page.selections,[],'native delivery is not an explicit user selection');
 page.dispose();assert.equal(page.events.size,0);assert.equal(page.service.catalog.size,2);
});

test('real locked native/web selection is bidirectional; Arabic direction clears; stale Host cannot override',async()=>{
 const page=await fixture('en');
 for(const tag of ['ar','zh-Hant','ja','en']){
  page.service.setLocale(tag);assert.equal(page.window.__DSHA_LANGUAGE__,tag);
  assert.equal(page.selections.at(-1),tag);assert.equal(page.service.getSnapshot().active,tag);
  assert.equal(page.document.documentElement.dir,tag==='ar'?'rtl':'ltr');
  page.service.adopt({getSnapshot:()=>({value:{preference:'zh'}})});
  assert.equal(page.service.getSnapshot().active,tag);
 }
 const before=page.service.getSnapshot().active;
 for(const invalid of ['system','ar-EG','zh-TW','invalid','<script>']){
  page.window.__DSHA_LANGUAGE__=invalid;page.window.dispatchEvent({type:'dsha-language'});
  assert.equal(page.service.getSnapshot().active,before);
 }
 assert.throws(()=>page.service.setLocale('unregistered'),/not registered/);
 page.dispose();
});

test('web-only upstream language (es/de) is kept across native re-injection and never sent to the app; an app language change wins',async()=>{
 const stored=new Map();
 let page=await fixture('en',stored);
 page.service.setLocale('es');
 assert.equal(page.service.getSnapshot().active,'es');
 assert.equal(page.window.__DSHA_LANGUAGE__,'en','native language is not replaced by a web-only choice');
 assert.deepEqual(page.selections,[],'web-only language is never posted to the app');
 // 同一 App 语言再次注入（切页、重连）不覆盖网页单独选的语言。
 page.window.dispatchEvent({type:'dsha-language'});
 assert.equal(page.service.getSnapshot().active,'es');
 page.dispose();
 // 重新加载页面：App 仍注入 en，网页选择保留。
 page=await fixture('en',stored);
 assert.equal(page.service.getSnapshot().active,'es');
 // 用户在 App 里换了语言：App 语言优先，网页单独选择作废。
 page.window.__DSHA_LANGUAGE__='ja';page.window.dispatchEvent({type:'dsha-language'});
 assert.equal(page.service.getSnapshot().active,'ja');
 assert.equal(stored.size,0,'stale web-only choice is cleared once the app language changes');
 // 网页里选回 App 支持的语言：照旧回传 App，并清掉网页单独选择。
 page.service.setLocale('de');assert.equal(stored.size,1);
 page.service.setLocale('fr');
 assert.equal(page.window.__DSHA_LANGUAGE__,'fr');assert.deepEqual(page.selections,['fr']);assert.equal(stored.size,0);
 page.dispose();
 // 存储不可用（隐私模式等）时退回原行为，不抛错。
 const broken=await fixture('en',{has(){throw Error('denied');},get(){throw Error('denied');},set(){throw Error('denied');},delete(){throw Error('denied');}});
 broken.service.setLocale('es');assert.equal(broken.service.getSnapshot().active,'es');assert.deepEqual(broken.selections,[]);
 broken.dispose();
});

test('a conflicting namespace or language is skipped and reported without rolling back the rest',()=>{
 const reported=[],registered=[],added=[];
 const window={addEventListener(){},removeEventListener(){},reportError:error=>reported.push(error.message)};
 const context=vm.createContext({window,document:{documentElement:{}},console:{error(){},warn(){}},setTimeout});
 vm.runInContext(helper,context);
 const locales=[{id:'zh'},{id:'en'},{id:'ja'},{id:'KO'}];
 const locale={getSnapshot:()=>({active:'en',locales}),subscribe:()=>()=>{},setLocale(){},
  addLanguage(row){if(row.id==='ar')throw Error('boom');added.push(row.id);locales.push({id:row.id});return ()=>{};},
  register(namespace,dicts){
   const ids=typeof dicts==='string'?[dicts]:Object.keys(dicts);
   if(namespace==='chat'&&ids.includes('la'))throw Error('locale namespace "chat" already has locale "la"');
   registered.push(namespace+':'+ids.join(','));return ()=>{};}};
 const dispose=context.installDshaLanguageBridge(locale);
 assert.deepEqual(added,['zh-Hant','la'],'upstream-owned ko (case-insensitive) is never re-added; failed ar is skipped');
 assert.ok(registered.includes('chat:zh-Hant'),'a failing batch falls back to per-language registration');
 assert.ok(!registered.some(row=>/(^|,)(ko|ar|ja)(,|$)/.test(row.split(':')[1])),'no upstream-owned or failed language is registered');
 assert.ok(registered.filter(row=>!row.startsWith('chat:')).length>10,'other namespaces stay registered');
 assert.ok(reported.some(text=>text.includes('language ar')));
 assert.ok(reported.some(text=>text.includes('namespace chat locale la')));
 assert.equal(typeof dispose,'function');
});

test('non-browser import keeps upstream available',()=>{
 const context=vm.createContext({});vm.runInContext(helper,context);
 assert.equal(typeof context.installDshaLanguageBridge({}),'function');
});
