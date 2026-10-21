// 真实锁定 DSH CLI/BrowserAuth/settings RPC；仅使用 app/build 下的独立空 Profile。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createRequire} from 'node:module';
import {randomUUID,createHash} from 'node:crypto';
import {testRuntime,requireNativeHost} from './test-runtime-fixture.mjs';
const root=path.resolve(import.meta.dirname,'..');
const runtime=testRuntime('raw');requireNativeHost(runtime);
const base=fs.mkdtempSync(path.join(root,'app/build/build190-fix11-web-ready/host-'));
const home=path.join(base,'home'),profile=path.join(home,'profiles/web'),workspace=path.join(base,'workspace');
for(const dir of [profile,workspace])fs.mkdirSync(dir,{recursive:true});
fs.writeFileSync(path.join(profile,'package.json'),JSON.stringify({name:'dsha-web-ready-fixture',version:'1.0.0',private:true,dsh:{profile:{bundles:['@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app'],patchReload:'startup'}}}));
const env=Object.fromEntries(Object.entries(process.env).filter(([key])=>!/(API_KEY|TOKEN|SECRET|PASSWORD)/i.test(key)));
const child=spawn(process.execPath,[path.join(runtime,'node_modules/@deepseek-ai/dsh/lib/bin.js'),'web','--no-open','--host','127.0.0.1','--port','0'],{cwd:workspace,windowsHide:true,env:{...env,DSH_HOME:home,HOME:home,USERPROFILE:home,BROWSER:'true',DEEPSEEK_API_KEY:'',NARB_DISABLE_NATIVE_CACHE:'1'},stdio:['ignore','pipe','pipe']});
let log='',exited=false,browser,page,inspectBrowser;
child.on('exit',()=>exited=true);
for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{log=(log+chunk).slice(-250000);});
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const redact=text=>text.replace(/token=[A-Za-z0-9_-]+/g,'token=[REDACTED]');
try{
 let auth;for(let i=0;i<600&&!exited;i++){auth=log.match(/http:\/\/127\.0\.0\.1:\d+\/\?token=[A-Za-z0-9_-]{43}/)?.[0];if(auth)break;await delay(200);}
 assert(auth,'Host boot failed: '+redact(log.slice(-2000)));
 const origin=new URL(auth).origin;
 const exchange=await fetch(auth,{redirect:'manual'});assert.equal(exchange.status,303);
 const cookie=exchange.headers.get('set-cookie').split(';')[0];
 const rpc=async(method,args={})=>{const id=randomUUID();const response=await fetch(origin+'/api/'+method,{method:'POST',headers:{Cookie:cookie,Origin:origin,'Content-Type':'application/json'},body:JSON.stringify({type:'client-request',rpcId:id,method,payload:{args}}),signal:AbortSignal.timeout(15000)});assert.equal(response.status,200);const result=await response.json();assert.equal(result.rpcId,id);assert(result.result.ok,JSON.stringify(result.result.error));return result.result.value;};
 const describe=await rpc('settings/describe');const notice=describe.namespaces.find(row=>row.ns==='ui-settings-general');assert(notice,'actual welcome namespace');
 const started=Date.now();
 await rpc('settings/mutate',{ns:notice.ns,ops:[{op:'set',path:['welcomeNoticeVersion'],value:'2026-09-28.1'}],expectedRevision:notice.revision});
 const after=(await rpc('settings/describe')).namespaces.find(row=>row.ns===notice.ns);
 assert.equal(after.value.welcomeNoticeVersion,'2026-09-28.1');
 assert.match(fs.readFileSync(path.join(profile,'cordis.patch.yml'),'utf8'),/welcomeNoticeVersion: 2026-09-28\.1/);
 console.log(JSON.stringify({status:'PASS',stage:'real-host-settings-mutate',elapsedMs:Date.now()-started,version:'0.2.1-rc.2',deviceValidation:false}));
 await rpc('settings/mutate',{ns:notice.ns,ops:[{op:'unset',path:['welcomeNoticeVersion']}],expectedRevision:after.revision});
 const require=createRequire(import.meta.url);
 let playwright;try{playwright=require(process.env.DSHA_PLAYWRIGHT||'playwright');}catch{playwright=require(path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));}
 const engine=process.env.DSHA_BROWSER||'chromium';assert(['chromium','firefox'].includes(engine));
 browser=await playwright[engine].launch({headless:true});
 page=await browser.newPage({viewport:{width:360,height:800}});
 const recipe=JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets/welcome-notice-patch.json'),'utf8'));
 const canonical=fs.readFileSync(path.join(runtime,'node_modules',recipe.module),'utf8');
 assert.equal(createHash('sha256').update(canonical).digest('hex'),recipe.sourceSha256);
 let transformed=canonical;for(const patch of recipe.patches){assert.equal(transformed.split(patch.before).length,2);transformed=transformed.replace(patch.before,patch.after);}
 const comboSource=value=>{let source=value.replace(/(?:\r?\n)?\/\/# sourceURL=([^\r\n]+)(?:\r?\n)?$/,'').replace(/(?:\r?\n)?\/\/# sourceMappingURL=[^\r\n]*(?:\r?\n)?$/,'');return source.endsWith('\n')?source:source+'\n';};
 const servedCanonical=comboSource(canonical),servedPatched=comboSource(transformed);
 let patched=0,heldMutation=false,releaseMutation;
 const held=new Promise(resolve=>releaseMutation=resolve);
 await page.route('**/*',async route=>{
  const url=new URL(route.request().url());
  if(url.origin!==origin)return route.abort();
  if(url.pathname==='/api/settings/mutate'){
   const response=await route.fetch();heldMutation=true;
   await held;return route.fulfill({response});
  }
  if(url.pathname.endsWith('.js')||url.pathname==='/plugins/'&&url.search.startsWith('??')){
   const response=await route.fetch();const source=await response.text();
   // rc2 以 /plugins/??.../client.js,...&rev=... 合并提供多个原始模块，
   // 仅 source-map trailer 被合并器剥离；锁定模块的全部正式锚点仍须唯一。
   if(source.includes(servedCanonical)){
    assert.equal(source.split(servedCanonical).length,2);const body=source.replace(servedCanonical,()=>servedPatched);
    new Function(body);
    patched++;return route.fulfill({response,body});
   }
   return route.fulfill({response});
  }
  return route.continue();
 });
 const reports=[],errors=[],consoleErrors=[];
 inspectBrowser=async()=>fs.writeFileSync(path.join(base,'browser-observed.json'),JSON.stringify({patched,reports,errors,consoleErrors,document:await page.evaluate(()=>({path:location.pathname,text:document.body?.textContent,root:document.getElementById('root')?.outerHTML})).then(value=>JSON.parse(redact(JSON.stringify(value))))},null,2));
 page.on('pageerror',error=>errors.push(redact(String(error))));
 page.on('console',message=>{if(message.text().startsWith('[DSHA_PAGE] '))reports.push(JSON.parse(message.text().slice(12)));else if(message.type()==='error')consoleErrors.push(redact(message.text()));});
 await page.addInitScript({content:'window.__DSHA_NATIVE_PLUGINS__=true;'});
 await page.goto(auth);
 const enter=page.getByRole('button',{name:/^(Continue|继续)$/});
 await enter.waitFor({timeout:45000});
 await page.waitForFunction(()=>!!document.querySelector('#root [data-shell-overlay]'));
 await page.waitForFunction(()=>!document.querySelector('[data-dsh-boot]'));
 assert.equal(patched,1,'production notice recipe served to actual client');
 // 模拟没有 DOCUMENT_START_SCRIPT 的旧内核：boot 已消失、首次说明已挂载后才注入。
 await page.addScriptTag({content:"window.__DSHA_PAGE_BINDING__={nonce:'"+'a'.repeat(32)+"'};\n"+fs.readFileSync(path.join(root,'app/src/main/assets/web-integration/startup.js'),'utf8')});
 assert(reports.some(row=>row.type==='ready'),'mounted first notice must report ready');
 await enter.click();
 for(let i=0;i<150&&!heldMutation;i++)await delay(100);
 assert(heldMutation,'actual mutate response is still held after Host persisted/pushed');
 await page.waitForFunction(()=>!document.body.textContent.includes('Saving…')&&!document.body.textContent.includes('正在保存…'),undefined,{timeout:15000});
 assert.equal(await page.getByRole('button',{name:/^(Continue|继续)$/}).count(),0,'Host update must release welcome while mutate response remains held');
 const saved=(await rpc('settings/describe')).namespaces.find(row=>row.ns===notice.ns);
 assert.equal(saved.value.welcomeNoticeVersion,'2026-09-28.1');
 releaseMutation();
 await page.reload();
 await page.waitForFunction(()=>!!document.querySelector('#root [data-shell-overlay]'));
 assert.equal(await page.getByRole('button',{name:/^(Continue|继续)$/}).count(),0,'fresh browser load reads durable acknowledgement');
 assert.deepEqual(errors,[]);
 fs.writeFileSync(path.join(base,'browser-results.json'),JSON.stringify({engine,patched,ready:reports.filter(row=>row.type==='ready').map(row=>({type:row.type,sequence:row.sequence})),heldMutation,errors,deviceValidation:false},null,2));
 console.log(JSON.stringify({status:'PASS',stage:'real-browser-host-save-push-before-held-rpc-and-refresh',engine,patched,deviceValidation:false}));
}finally{
 if(inspectBrowser)await inspectBrowser();
 if(browser)await browser.close();
 if(!exited)child.kill();
 for(let i=0;i<100&&!exited;i++)await delay(100);
 assert(exited,'owned host exit');
 fs.writeFileSync(path.join(base,'host.log'),redact(log));
}
