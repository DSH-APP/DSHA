// Real hash-locked rc3 PlatformAccount/Authorization/Cordis and browser client.
// Only the credential adapter and localhost Platform replies are synthetic.
// No production fixture bytes, actual accounts, or public network are used.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {spawnSync} from 'node:child_process';
import {testRuntime} from './test-runtime-fixture.mjs';
import {browserFixture} from './rc1-browser-fixture.mjs';

const root=path.resolve(import.meta.dirname,'..'), runtime=testRuntime('raw');
const base=fs.mkdtempSync(path.join(root,'app/build/account-requests-'));
fs.symlinkSync(path.join(runtime,'node_modules'),path.join(base,'node_modules'),process.platform==='win32'?'junction':'dir');
const sha=value=>createHash('sha256').update(value).digest('hex');
const recipes=['deepseek-account-requests-patch.json','deepseek-account-client-diagnostic-patch.json']
  .map(name=>({name,...JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets',name),'utf8'))}));
const lock=JSON.parse(fs.readFileSync(path.join(root,'tools/dsh-runtime/package-lock.json'),'utf8'));
const patches=[];
for(const recipe of recipes){
  const original=fs.readFileSync(path.join(runtime,'node_modules',recipe.module),'utf8');
  assert.equal(sha(original),recipe.sourceSha256,'whole official module must match the reviewed source');
  assert.equal(recipe.packageIntegrity,lock.packages['node_modules/'+recipe.module.split('/lib/')[0]].integrity);
  let source=original;
  for(const edit of recipe.patches){assert.equal(source.split(edit.before).length,2);source=source.replace(edit.before,()=>edit.after);}
  const syntax=spawnSync(process.execPath,['--check','--input-type='+ (recipe.module.endsWith('client.js')?'commonjs':'module')],{input:source,encoding:'utf8',windowsHide:true});
  assert.equal(syntax.status,0,syntax.stderr);
  patches.push({recipe,original,source});
}
const [serverPatch,clientPatch]=patches;
const sourceFile=path.join(base,'platform-official.mjs'),fixedFile=path.join(base,'platform-patched.mjs');
fs.writeFileSync(sourceFile,serverPatch.original);fs.writeFileSync(fixedFile,serverPatch.source);
const load=name=>import(pathToFileURL(path.join(runtime,'node_modules',name,'lib/index.js')));
const [{Context},{default:Authorization},{default:Official},{default:Fixed}]=await Promise.all([
  load('@deepseek-ai/cordis'),load('@deepseek-ai/dsh-authorization'),import(pathToFileURL(sourceFile)),import(pathToFileURL(fixedFile))]);
const client={version:'fixture-only',locale:'en',timezoneOffsetSeconds:0};
const KEY='deepseek-account-platform/default';
const results=[],logs=[];
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(predicate){for(let i=0;i<400;i++){if(await predicate())return;await delay(5);}throw Error('fixture phase deadline');}
async function test(name,run){await run();results.push({name,status:'PASS'});}
function capture(...values){logs.push(values);}
const originalConsole=console.error;
console.error=capture;
async function backend(Provider=Fixed,options={}){
  const context=new Context(),memory=new Map(),wire={profile:[],balance:0,bonus:0,deletes:0};
  const platform=http.createServer((req,res)=>{
    const pathname=new URL(req.url,'http://127.0.0.1').pathname;
    const reply=(value,status=200)=>res.writeHead(status,{'content-type':'application/json'}).end(JSON.stringify({code:0,data:{biz_code:0,biz_data:value}}));
    if(pathname==='/auth-api/v0/users/current'){
      const call={headers:req.headers,done:false,reply(value={id:'synthetic-user',email:'synthetic@example.invalid',id_profile:{name:'fixture'}},status=200){if(this.done||res.destroyed)return;this.done=true;reply(value,status);},
        raw(value,status=200){if(this.done||res.destroyed)return;this.done=true;res.writeHead(status,{'content-type':'application/json'}).end(value);},drop(){this.done=true;res.destroy();}};
      wire.profile.push(call);
      if(options.immediateStatus)call.raw('untrusted response token=SHOULD_NOT_BE_LOGGED',options.immediateStatus);
    }else if(pathname==='/api/v0/users/get_user_summary'){++wire.balance;reply({normal_wallets:[],bonus_wallets:[]});}
    else if(pathname==='/api/v0/users/get_unnotified_bonuses'){++wire.bonus;reply([]);}
    else if(pathname==='/auth-api/v0/users/logout')reply({});
    else res.writeHead(404).end();
  });
  await new Promise(resolve=>platform.listen(0,'127.0.0.1',resolve));
  const origin=`http://127.0.0.1:${platform.address().port}`;
  const store=(token='fixture-grant-one')=>{
    memory.set(KEY,{kind:'grant',payload:{version:1,token,issuer:origin}});
    context.emit('credentials/record-updated',KEY);
  };
  context.provide('credentials',{
    async readRecord(key){return memory.get(key);},
    async describeRecord(key){return {configured:memory.has(key)};},
    async deleteRecord(key){++wire.deletes;memory.delete(key);context.emit('credentials/record-updated',key);},
    async modifyRecord(key,modify){const value=await modify(memory.get(key));if(value!==undefined){memory.set(key,value);context.emit('credentials/record-updated',key);}return memory.get(key);}
  });
  store();
  await context.plugin(Authorization);
  await context.plugin(Provider,{platformOrigin:origin,allowLoopbackHttp:true,requestTimeoutMs:2000});
  return {context,memory,wire,store,account:context.deepseekAccount,async close(){
    await context.fiber.dispose();platform.closeAllConnections();await new Promise(resolve=>platform.close(resolve));
  }};
}

try{
  await test('archive stable engine applies from official canonical rc3, repeats identically, and rejects drift',async()=>{
    const python=spawnSync(process.env.DSHA_PYTHON||'python',['-B','-c',[
      'import json,sys,os;from pathlib import Path;sys.path.insert(0,"tools");from runtime_stable_patches import apply',
      'for name in sys.argv[1:]:',
      ' p=json.loads(Path("app/src/main/assets",name).read_text(encoding="utf8"))',
      ' pointer=json.loads(Path("app/build/test-runtimes/current.json").read_text())',
      ' raw=Path(os.environ.get("DSHA_TEST_RUNTIME") or pointer["raw"],"node_modules",p["module"]).read_bytes()',
      ' chain=[(name,[(x["before"],x["after"]) for x in p["patches"]],[])]',
      ' changed=apply(raw,chain);assert changed!=raw;assert apply(changed,chain)==changed',
      ' try: apply(raw.replace(p["patches"][0]["before"].encode(),b"DRIFT",1),chain)',
      ' except ValueError: pass',
      ' else: raise AssertionError("drift was accepted")',
      'print("PASS_ARCHIVE_CANONICAL_REPEAT_DRIFT")'
    ].join('\n'),...recipes.map(row=>row.name)],{encoding:'utf8',windowsHide:true,timeout:30000});
    assert.equal(python.status,0,python.stderr);assert.match(python.stdout,/PASS_ARCHIVE_CANONICAL_REPEAT_DRIFT/);
  });
  await test('official negative control issues two users/current calls for concurrent profile and bonus readers',async()=>{
    const f=await backend(Official);try{
      const reads=[f.account.getProfile(client),f.account.getUnnotifiedBonuses(client)];
      await until(()=>f.wire.profile.length===2);f.wire.profile.forEach(call=>call.reply());
      const [profile,bonus]=await Promise.all(reads);assert.equal(profile.status,'ready');assert.equal(bonus.accountId,'synthetic-user');
    }finally{await f.close();}
  });
  await test('patched profile and bonus consumers share exactly one in-flight users/current read',async()=>{
    const f=await backend();try{
      const reads=[f.account.getProfile(client),f.account.getUnnotifiedBonuses(client),f.account.getProfile({...client})];
      await until(()=>f.wire.profile.length===1);await delay(30);assert.equal(f.wire.profile.length,1);
      const balance=await f.account.getBalance(client);assert.equal(balance.status,'ready');assert.equal(f.wire.balance,1,'balance is not blocked by profile');
      f.wire.profile[0].reply();const [a,b,c]=await Promise.all(reads);
      assert.equal(a.status,'ready');assert.equal(b.accountId,'synthetic-user');assert.deepEqual(a,c);assert.equal(f.account.profileReads.size,0);
      const later=f.account.getProfile(client);await until(()=>f.wire.profile.length===2);f.wire.profile[1].reply();await later;
      assert.equal(f.wire.profile.length,2,'a settled read is not a permanent cache');
    }finally{await f.close();}
  });
  await test('different effective client headers stay separate while header order and wire locale normalization can share',async()=>{
    const f=await backend();try{
      const reads=[f.account.getProfile(client),f.account.getProfile({...client,locale:'zh-CN'}),
        f.account.getProfile({...client,version:'another-client'}),f.account.getProfile({...client,timezoneOffsetSeconds:28800}),
        f.account.getProfile({timezoneOffsetSeconds:0,locale:'en-US',version:'fixture-only'})];
      await until(()=>f.wire.profile.length===4);await delay(25);assert.equal(f.wire.profile.length,4);
      f.wire.profile.forEach(call=>call.reply());await Promise.all(reads);assert.equal(f.account.profileReads.size,0);
    }finally{await f.close();}
  });
  await test('403 failure is shared only while pending, preserves credentials, and emits one safe structured HTTP diagnosis',async()=>{
    const f=await backend();try{
      const start=logs.length;
      const reads=[f.account.getProfile(client),f.account.getProfile(client),f.account.getUnnotifiedBonuses(client).catch(error=>error)];
      await until(()=>f.wire.profile.length===1);f.wire.profile[0].raw('token=SHOULD_NOT_BE_LOGGED email=private@example.invalid',403);
      const [a,b,notice]=await Promise.all(reads);assert.deepEqual(a,{status:'failed'});assert.deepEqual(a,b);assert.equal(notice.code,'network');
      assert.equal((await f.account.getState()).status,'credential-stored');assert.equal(f.wire.deletes,0);assert.equal(f.account.profileReads.size,0);
      const emitted=logs.slice(start).filter(args=>String(args[0]).startsWith('[deepseek-account] request failed '));
      assert.equal(emitted.length,1);assert.equal(emitted[0].length,1);
      const record=JSON.parse(emitted[0][0].slice('[deepseek-account] request failed '.length));
      assert.equal(record.status,403);assert.equal(record.errorCode,'http-error');assert.equal(record.httpClass,'forbidden');assert.equal(record.failureKind,'http-response');assert.equal(record.path,'/auth-api/v0/users/current');assert.equal(typeof record.requestId,'number');
      const text=JSON.stringify(emitted);for(const secret of ['fixture-grant','SHOULD_NOT_BE_LOGGED','private@example.invalid','x-dsh-auth-token'])assert(!text.includes(secret));
      await delay(30);assert.equal(f.wire.profile.length,1,'no timer or HTTP retry was added');
      const next=f.account.getProfile(client);await until(()=>f.wire.profile.length===2);f.wire.profile[1].reply();assert.equal((await next).status,'ready');
    }finally{await f.close();}
  });
  await test('credential replacement aborts old shared read, starts the new token, and cannot publish old identity',async()=>{
    const f=await backend();try{
      const old=[f.account.getProfile(client),f.account.getUnnotifiedBonuses(client)];await until(()=>f.wire.profile.length===1);
      f.store('fixture-grant-two');const next=f.account.getProfile(client);await until(()=>f.wire.profile.length===2);
      assert.equal(f.wire.profile[0].headers['x-dsh-auth-token'],'fixture-grant-one');assert.equal(f.wire.profile[1].headers['x-dsh-auth-token'],'fixture-grant-two');
      f.wire.profile[1].reply({id:'new-user',email:'new@example.invalid'});
      assert.equal((await next).value.id,'new-user');assert.deepEqual(await Promise.all(old),[null,null]);
      assert.equal(f.wire.deletes,0);assert.equal(f.account.lastProfile.profile.value.id,'new-user');
    }finally{await f.close();}
  });
  await test('same-token credential generation change cannot reuse an old in-flight entry or let its finally delete the new one',async()=>{
    const f=await backend();try{
      const first=f.account.getProfile(client);await until(()=>f.wire.profile.length===1);
      f.store();const next=f.account.getProfile(client);await until(()=>f.wire.profile.length===2);
      assert.equal(await first,null);const third=f.account.getProfile(client);await delay(30);assert.equal(f.wire.profile.length,2);
      f.wire.profile[1].reply();const values=await Promise.all([next,third]);assert.equal(values[0].status,'ready');assert.deepEqual(values[0],values[1]);
    }finally{await f.close();}
  });
  await test('caller watch cancellation does not cancel another consumer shared request',async()=>{
    const f=await backend();try{
      const controller=new AbortController(),watch=f.account.watch(controller.signal);await watch.next();
      const reads=[f.account.getProfile(client),f.account.getUnnotifiedBonuses(client)];await until(()=>f.wire.profile.length===1);
      controller.abort();await watch.return();f.wire.profile[0].reply();const values=await Promise.all(reads);
      assert.equal(values[0].status,'ready');assert.equal(values[1].accountId,'synthetic-user');assert.equal(f.wire.deletes,0);
    }finally{await f.close();}
  });
  await test('401 and successful HTTP code 40003 retain the official credential-expiry behavior',async()=>{
    for(const response of ['401','40003']){
      const f=await backend();try{
        const reads=[f.account.getProfile(client),f.account.getUnnotifiedBonuses(client)];await until(()=>f.wire.profile.length===1);
        f.wire.profile[0].raw(response==='401'?'untrusted':'{"code":40003}',response==='401'?401:200);
        assert.deepEqual(await Promise.all(reads),[null,null]);assert.equal((await f.account.getState()).status,'signed-out');assert.equal(f.wire.deletes,1);
      }finally{await f.close();}
    }
  });
  await test('transport and protocol failures are not confused with HTTP rejection and never expire credentials',async()=>{
    for(const mode of ['no-response','malformed','oversize']){
      const f=await backend();try{
        const start=logs.length,read=f.account.getProfile(client);await until(()=>f.wire.profile.length===1);
        if(mode==='no-response')f.wire.profile[0].drop();else f.wire.profile[0].raw(mode==='malformed'?'not JSON':'x'.repeat(65537));
        assert.deepEqual(await read,{status:'failed'});assert.equal(f.wire.deletes,0);assert.equal((await f.account.getState()).status,'credential-stored');
        const entry=logs.slice(start).find(args=>String(args[0]).startsWith('[deepseek-account] '));assert.equal(entry.length,1);
        const message=entry[0],record=JSON.parse(message.slice(message.indexOf('{')));assert.equal(record.errorCode,mode==='no-response'?'no-response':'protocol');
        assert.equal(f.account.profileReads.size,0);
      }finally{await f.close();}
    }
  });
  await test('provider teardown aborts shared requests and releases all in-flight bookkeeping',async()=>{
    const f=await backend();try{
      const reads=[f.account.getProfile(client),f.account.getProfile(client)];await until(()=>f.wire.profile.length===1);
      await f.context.fiber.dispose();assert.deepEqual(await Promise.all(reads),[null,null]);assert.equal(f.account.profileReads.size,0);
    }finally{await f.close();}
  });
  await test('whole locked browser account client forwards failed profile and balance as safe single strings',async()=>{
    const browser=await browserFixture(runtime);try{
      await browser.load('dsh-client-ui-settings-account',[clientPatch.recipe]);
      const observed=await browser.page.evaluate(async()=>{
        const messages=[],dispose=[];
        console.error=(...args)=>messages.push(args);
        const stream={async *[Symbol.asyncIterator](){yield {value:{status:'credential-stored',attempt:null,links:{}},accept(){}};},async dispose(){}};
        const ctx={effect(fn){const cleanup=fn();if(typeof cleanup==='function')dispose.push(cleanup);return cleanup;},
          locale:{register(){return ()=>{};},bind(){return key=>key;},getSnapshot(){return {active:'en'};}},
          theme:{getTheme(){return {active:{colorScheme:'light'}};}},slots:{inject(){}},
          remote:{$stream(){return stream;},account:{async getProfile(){return {ok:true,value:{status:'failed'}};},async getBalance(){return {ok:true,value:{status:'failed'}};},async getUnnotifiedBonuses(){return {ok:true,value:null};}}}};
        auditExports['@deepseek-ai/dsh-client-ui-settings-account'].apply(ctx);
        await new Promise(resolve=>setTimeout(resolve,40));for(const cleanup of dispose.reverse())await cleanup();
        return messages.filter(args=>String(args[0]).startsWith('[deepseek-account] account read failed '));
      });
      assert.equal(observed.length,2);assert(observed.every(args=>args.length===1&&!args[0].includes('[object Object]')));
      assert.deepEqual(observed.map(args=>JSON.parse(args[0].slice(args[0].indexOf('{'))).field).sort(),['balance','profile']);
      assert.deepEqual(browser.errors,[]);
    }finally{await browser.close();}
  });
}finally{console.error=originalConsole;}
const receipt={scope:'LOCKED_OFFICIAL_ACCOUNT_PROVIDER_AND_CLIENT_SYNTHETIC_LOCALHOST',dshVersion:recipes[0].dshVersion,
  deviceUsed:false,userCredentialsUsed:false,publicNetworkUsed:false,negativeControl:'official concurrent readers send two users/current requests',
  behavior:'profile in-flight sharing only; settled failures not cached; HTTP wire contract and credential expiry rules preserved',
  sourceSha256:recipes[0].sourceSha256,recipes:recipes.map(row=>({asset:row.name,sha256:sha(fs.readFileSync(path.join(root,'app/src/main/assets',row.name)))})),
  cases:results.length,results};
fs.writeFileSync(path.join(base,'receipt.json'),JSON.stringify(receipt,null,2)+'\n');
console.log(JSON.stringify({...receipt,receipt:path.join(base,'receipt.json')},null,2));
