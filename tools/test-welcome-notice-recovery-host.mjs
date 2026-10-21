// Real locked rc2 CLI, BrowserAuth, settings RPC, ConfigForm and welcome component.
// The local proxy controls delivery order only; no model request or user profile is used.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createRequire} from 'node:module';
import {randomUUID,createHash} from 'node:crypto';
import {testRuntime,requireNativeHost} from './test-runtime-fixture.mjs';
const root=path.resolve(import.meta.dirname,'..');
const runtime=testRuntime('raw');requireNativeHost(runtime);
const output=path.resolve(process.env.DSHA_NOTICE_OUTPUT||fs.mkdtempSync(path.join(root,'app/build/welcome-recovery-host-')));
fs.mkdirSync(output,{recursive:true});
const expected=process.env.DSHA_NOTICE_EXPECT||'fixed';assert(['broken','fixed'].includes(expected));
const recipeFile=path.resolve(process.env.DSHA_NOTICE_RECIPE||path.join(root,'app/src/main/assets/welcome-notice-patch.json'));
const recipe=JSON.parse(fs.readFileSync(recipeFile,'utf8'));
const canonical=fs.readFileSync(path.join(runtime,'node_modules',recipe.module),'utf8');
const sha=value=>createHash('sha256').update(value).digest('hex');
assert.equal(sha(canonical),recipe.sourceSha256);
let patchedModule=canonical;
for(const patch of recipe.patches){assert.equal(patchedModule.split(patch.before).length,2);patchedModule=patchedModule.replace(patch.before,()=>patch.after);}
const combo=value=>value.replace(/(?:\r?\n)?\/\/# sourceURL=[^\r\n]*(?:\r?\n)?$/,'').replace(/(?:\r?\n)?\/\/# sourceMappingURL=[^\r\n]*(?:\r?\n)?$/,'').replace(/\n?$/,'\n');
const originalCombo=combo(canonical),patchedCombo=combo(patchedModule);
const home=path.join(output,'home'),profile=path.join(home,'profiles/web'),workspace=path.join(output,'workspace');
for(const folder of [profile,workspace])fs.mkdirSync(folder,{recursive:true});
fs.writeFileSync(path.join(profile,'package.json'),JSON.stringify({name:'dsha-welcome-recovery-fixture',version:'1.0.0',private:true,dsh:{profile:{bundles:['@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app'],patchReload:'startup'}}}));
const env=Object.fromEntries(Object.entries(process.env).filter(([key])=>!/(API_KEY|TOKEN|SECRET|PASSWORD|DSH_HOME)/i.test(key)));
const child=spawn(process.execPath,[path.join(runtime,'node_modules/@deepseek-ai/dsh/lib/bin.js'),'web','--no-open','--host','127.0.0.1','--port','0'],{cwd:workspace,windowsHide:true,env:{...env,DSH_HOME:home,HOME:home,USERPROFILE:home,BROWSER:'true',DEEPSEEK_API_KEY:'',NARB_DISABLE_NATIVE_CACHE:'1'},stdio:['ignore','pipe','pipe']});
let log='',exited=false,browser;
child.on('exit',()=>exited=true);
for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>log=(log+chunk).slice(-250000));
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const redact=value=>value.replace(/token=[A-Za-z0-9_-]+/g,'token=[REDACTED]');
const version='2026-09-28.1',results=[],errors=[];
try{
 let auth;for(let i=0;i<600&&!exited;i++){auth=log.match(/http:\/\/127\.0\.0\.1:\d+\/\?token=[A-Za-z0-9_-]{43}/)?.[0];if(auth)break;await delay(200);}
 assert(auth,'Host boot failed: '+redact(log.slice(-2000)));
 const origin=new URL(auth).origin;
 const exchange=await fetch(auth,{redirect:'manual'});assert.equal(exchange.status,303);
 const cookie=exchange.headers.get('set-cookie').split(';')[0],split=cookie.indexOf('=');
 const wire=async(method,body)=>{
  const response=await fetch(origin+'/api/'+method,{method:'POST',headers:{Cookie:cookie,Origin:origin,'Content-Type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
  assert.equal(response.status,200);const answer=await response.json();assert.equal(answer.rpcId,body.rpcId);return answer.result;
 };
 const rpc=async(method,args={})=>{const result=await wire(method,{type:'client-request',rpcId:randomUUID(),method,payload:{args}});assert(result.ok,JSON.stringify(result.error));return result.value;};
 const describe=async()=>{const result=await rpc('settings/describe');const notice=result.namespaces.find(row=>row.ns==='ui-settings-general');assert(notice);return notice;};
 const require=createRequire(import.meta.url);let playwright;
 try{playwright=require(process.env.DSHA_PLAYWRIGHT||'playwright');}catch{playwright=require(path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));}
 const engine=process.env.DSHA_BROWSER||'chromium';assert(['chromium','firefox'].includes(engine));browser=await playwright[engine].launch({headless:true});
 for(const scenario of ['lost-response-before-host-commit','refused-without-host-write','revision-conflict']){
  let notice=await describe();if(notice.value.welcomeNoticeVersion!==undefined)await rpc('settings/mutate',{ns:notice.ns,ops:[{op:'unset',path:['welcomeNoticeVersion']}],expectedRevision:notice.revision});
  const context=await browser.newContext({viewport:{width:393,height:852}});
  await context.addCookies([{name:cookie.slice(0,split),value:cookie.slice(split+1),url:origin,httpOnly:true,sameSite:'Strict'}]);
  const page=await context.newPage();page.on('pageerror',error=>errors.push(redact(String(error))));
  let patchCount=0,mutations=0,forwarded=0,describeAfterFailure=0,intercepted=false,delivery;
  const deliveryDone=new Promise((resolve,reject)=>delivery={resolve,reject});
  await page.route('**/*',async route=>{
   try{
    const url=new URL(route.request().url());if(url.origin!==origin)return route.abort();
    if(url.pathname==='/api/settings/describe'&&intercepted)describeAfterFailure++;
    if(url.pathname==='/api/settings/mutate'){
     mutations++;const request=route.request().postDataJSON();
     assert.equal(request.payload.args.ns,'ui-settings-general');
     assert.deepEqual(request.payload.args.ops,[{op:'set',path:['welcomeNoticeVersion'],value:version}]);
     if(mutations===1){
      intercepted=true;
      if(scenario==='lost-response-before-host-commit'){
       // A controlled local proxy buffers the original request, drops its reply
       // channel, then delivers that one request. Production never retries it.
       await route.abort('connectionreset');await delay(200);
       forwarded++;const result=await wire('settings/mutate',request);assert(result.ok);delivery.resolve();return;
      }
      if(scenario==='refused-without-host-write'){await route.abort('connectionreset');delivery.resolve();return;}
      const current=await describe();
      await rpc('settings/mutate',{ns:current.ns,ops:[{op:'set',path:['welcomeNoticeVersion'],value:'older-fixture-version'}],expectedRevision:current.revision});
      forwarded++;const response=await route.fetch();const body=await response.json();
      assert.equal(body.result.ok,false);assert.equal(body.result.error.code,'settings/conflict');
      await route.fulfill({response});delivery.resolve();return;
     }
     forwarded++;
    }
    if(url.pathname.endsWith('.js')||url.pathname==='/plugins/'&&url.search.startsWith('??')){
     const response=await route.fetch();const source=await response.text();
     if(source.includes(originalCombo)){assert.equal(source.split(originalCombo).length,2);patchCount++;return route.fulfill({response,body:source.replace(originalCombo,()=>patchedCombo)});}
     return route.fulfill({response});
    }
    return route.continue();
   }catch(error){delivery.reject(error);throw error;}
  });
  await page.addInitScript({content:'window.__DSHA_NATIVE_PLUGINS__=true;'});
  await page.goto(origin+'/');
  const enter=page.getByRole('button',{name:/^(Continue|继续)$/});await enter.waitFor({timeout:45000});assert.equal(patchCount,1);
  await enter.click();await deliveryDone;
  const shouldFinish=scenario!=='refused-without-host-write'&&expected==='fixed';
  if(shouldFinish){
   await page.waitForFunction(()=>!document.querySelector('[role="dialog"]'),undefined,{timeout:15000});
   assert.equal((await describe()).value.welcomeNoticeVersion,version);
  }else{
   await page.getByRole('alert').waitFor({timeout:15000});await delay(500);
   assert.equal(await page.getByRole('dialog').count(),1);
   assert.equal(await page.getByRole('button',{name:/^(Retry save|重试保存)$/}).count(),1);
  }
  const saved=await describe();
  if(scenario==='lost-response-before-host-commit'){
   assert.equal(saved.value.welcomeNoticeVersion,version);assert.equal(mutations,1);assert.equal(forwarded,1);
   assert.match(fs.readFileSync(path.join(profile,'cordis.patch.yml'),'utf8'),/welcomeNoticeVersion: 2026-09-28\.1/);
  }else if(scenario==='refused-without-host-write'){
   assert.equal(saved.value.welcomeNoticeVersion,undefined);assert.equal(mutations,1);assert.equal(forwarded,0);
   await page.screenshot({path:path.join(output,engine+'-unsaved-error.png')});
  }else assert.equal(mutations,expected==='fixed'?2:1,'only one bounded revision recovery write');
  results.push({scenario,expected,patchCount,mutations,forwarded,describeAfterFailure,persisted:saved.value.welcomeNoticeVersion===version,dismissed:shouldFinish});
  await page.reload();
  if(saved.value.welcomeNoticeVersion===version){await page.waitForFunction(()=>!!document.querySelector('#root [data-shell-overlay]'));assert.equal(await enter.count(),0,'new load reads actual durable confirmation');}
  else await enter.waitFor({timeout:15000});
  await context.close();
 }
 assert.deepEqual(errors,[]);
 const report={status:'PASS',expected,engine,recipeSha256:sha(fs.readFileSync(recipeFile)),actualModuleSha256:recipe.sourceSha256,results,errors,deviceValidation:false,modelRequests:0};
 fs.writeFileSync(path.join(output,'results.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
}finally{
 if(browser)await browser.close();if(!exited)child.kill();for(let i=0;i<100&&!exited;i++)await delay(100);assert(exited,'owned isolated Host exited');fs.writeFileSync(path.join(output,'host.log'),redact(log));
}
