// Real locked rc3 provider, credential provider, launch snapshot and native Request.
// All credentials/responses are synthetic; fetch is intercepted before any socket.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {spawnSync} from 'node:child_process';
import {testRuntime} from './test-runtime-fixture.mjs';

const root=path.resolve(import.meta.dirname,'..'),runtime=testRuntime('raw');
const output=fs.mkdtempSync(path.join(root,'app/build/web-search-headers-'));
fs.symlinkSync(path.join(runtime,'node_modules'),path.join(output,'node_modules'),process.platform==='win32'?'junction':'dir');
const sha=value=>createHash('sha256').update(value).digest('hex');
const recipeName='deepseek-web-search-headers-patch.json';
const recipe=JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets',recipeName),'utf8'));
const canonical=fs.readFileSync(path.join(runtime,'node_modules',recipe.module),'utf8');
assert.equal(sha(canonical),recipe.sourceSha256);
const lock=JSON.parse(fs.readFileSync(path.join(root,'tools/dsh-runtime/package-lock.json'),'utf8'));
assert.equal(recipe.packageIntegrity,lock.packages['node_modules/'+recipe.module.split('/lib/')[0]].integrity);
let changed=canonical;
for(const {before,after} of recipe.patches){assert.equal(changed.split(before).length,2);changed=changed.replace(before,()=>after);}
assert.equal(sha(changed),recipe.patchedSha256);
const syntax=spawnSync(process.execPath,['--check','--input-type=module'],{input:changed,encoding:'utf8',windowsHide:true});
assert.equal(syntax.status,0,syntax.stderr);
for(const [name,value]of [['official.mjs',canonical],['patched.mjs',changed]])fs.writeFileSync(path.join(output,name),value);
const load=name=>import(pathToFileURL(path.join(runtime,'node_modules',name,'lib/index.js')));
const [Official,Fixed,{Context},{LocalCredentialProvider},{createLaunchEnvironmentSnapshot}]=await Promise.all([
  import(pathToFileURL(path.join(output,'official.mjs'))),import(pathToFileURL(path.join(output,'patched.mjs'))),
  load('@deepseek-ai/cordis'),load('@deepseek-ai/dsh-credentials-local'),load('@deepseek-ai/dsh-launch-environment')]);
const nativeFetch=globalThis.fetch,originalConsole=console.error;
const calls=[],logs=[],results=[];
const response=()=>new Response(JSON.stringify({content:[{type:'web_search_tool_result',content:[{type:'web_search_result',url:'https://fixture.invalid/result',title:'synthetic result'}]}]}),{status:200,headers:{'content-type':'application/json'}});
let dispatchMode='success';
globalThis.fetch=async(input,init)=>{
  // Native WebIDL ByteString and Request validation, but never native fetch.
  const request=new Request(input,init);
  calls.push({request,body:await request.clone().text()});
  if(dispatchMode==='reject')throw new TypeError('synthetic transport exception SECRET_MUST_NOT_APPEAR');
  if(dispatchMode==='401')return new Response('{}',{status:401});
  return response();
};
console.error=(...items)=>logs.push(items);
const defaults={baseURL:'https://fixture.invalid/anthropic/v1',model:'fixture-model',apiVersion:'2023-06-01',maxTokens:64,maxUses:1};
function direct(Provider=Fixed,extras={}){
  const events=[];
  return {events,provider:new Provider.DeepSeekSearchProvider(()=>({...defaults,apiKey:'SYNTHETIC-KEY-KEEP-EXACT',recordRequest:value=>events.push(value),...extras}))};
}
async function test(name,operation){await operation();results.push({name,status:'PASS'});}
async function rejected(f,expected,query='test'){
  const startCalls=calls.length,startLogs=logs.length,startEvents=f.events.length;
  const error=await f.provider.search({query}).then(()=>assert.fail('preflight accepted'),error=>error);
  assert.equal(error.code,'WEB_PROVIDER_ERROR');
  assert.equal(calls.length,startCalls,'invalid header reached constructed Request/fetch');
  assert.equal(f.events.length,startEvents,'preflight incorrectly recorded dispatch');
  assert.match(error.message,/before HTTP dispatch; no request was sent/);
  assert(!error.message.includes('The web request used endpoint'));
  assert.equal(error.cause,undefined,'raw construction error must not expose header values');
  const emitted=logs.slice(startLogs);
  assert.equal(emitted.length,1);assert.equal(emitted[0].length,1);
  const diagnostic=JSON.parse(emitted[0][0].slice('[deepseek-web-search] preflight rejected '.length));
  assert.deepEqual(Object.keys(diagnostic).sort(),['phase','dispatched','field','source','category','index','codePoint','codePointHex'].sort());
  assert.equal(diagnostic.phase,'local-preflight');assert.equal(diagnostic.dispatched,false);
  for(const[key,value]of Object.entries(expected))assert.equal(diagnostic[key],value,key);
  const serial=JSON.stringify({emitted,error:{message:error.message,cause:error.cause}});
  for(const value of ['SYNTHETIC-KEY','SECRET_MUST_NOT_APPEAR','PRIVATE-ENDPOINT','x\u115f','Bearer SYNTHETIC'])assert(!serial.includes(value),'secret/value escaped: '+value);
  return diagnostic;
}
let setupSequence=0;
async function integration({stored,processKey,projectKey,userKey,literal,token,account=false,apiVersion='2023-06-01',credentials=true}={}){
  const directory=path.join(output,'credentials-'+ ++setupSequence);fs.mkdirSync(directory);
  const filename=path.join(directory,'.credentials.yaml');
  fs.writeFileSync(filename,JSON.stringify({version:1,refs:stored===undefined?{}:{DEEPSEEK_API_KEY:stored}}),{mode:0o600});
  const before=fs.readFileSync(filename),ctx=new Context(),events=[];
  const snapshot=createLaunchEnvironmentSnapshot([
    {source:'user-env',path:'synthetic-user.env',values:userKey===undefined?{}:{DEEPSEEK_API_KEY:userKey}},
    {source:'project-env',path:'synthetic-project.env',values:projectKey===undefined?{}:{DEEPSEEK_API_KEY:projectKey}},
    {source:'process',values:processKey===undefined?{}:{DEEPSEEK_API_KEY:processKey}}
  ]);
  ctx.provide('launchEnvironment',snapshot);
  if(credentials)await ctx.plugin(LocalCredentialProvider,{path:filename,watch:false});
  let provider,tokenReads=0;
  ctx.provide('web',{registerSearchProvider:value=>provider=value});
  ctx.provide('agents',{currentInitiator:()=>({session:{requestContext:()=>({provider:account?'deepseek-account':'deepseek'}),append:(kind,event)=>{assert.equal(kind,'web/deepseek-search-llm-request');events.push(event);}}})});
  ctx.provide('deepseekAccount',{resolveToken:async()=>{++tokenReads;return token;}});
  const config={...defaults,apiVersion,apiKey:literal,apiKeyEnv:'DEEPSEEK_API_KEY'};
  Fixed.apply(ctx,Object.fromEntries(Object.entries(config).map(([key,value])=>[key,{get:()=>value}])));
  return {provider,events,get tokenReads(){return tokenReads;},async close(){await ctx.fiber.dispose();assert.deepEqual(fs.readFileSync(filename),before,'search altered managed credential store');}};
}

try{
  await test('hash-locked recipe archive fresh/repeat/drift, source generation and account diagnostics recipes retained',async()=>{
    const python=spawnSync(process.env.DSHA_PYTHON||'python',['-B','-c',[
      'import hashlib,json,sys,os;from pathlib import Path;sys.path.insert(0,"tools");from runtime_stable_patches import apply',
      'p=json.loads(Path("app/src/main/assets/deepseek-web-search-headers-patch.json").read_text(encoding="utf8"))',
      'pointer=json.loads(Path("app/build/test-runtimes/current.json").read_text());raw=Path(os.environ.get("DSHA_TEST_RUNTIME") or pointer["raw"],"node_modules",p["module"]).read_bytes()',
      'chain=[("web-search",[(e["before"],e["after"])for e in p["patches"]],[])]',
      'fixed=apply(raw,chain);assert hashlib.sha256(fixed).hexdigest()==p["patchedSha256"];assert apply(fixed,chain)==fixed',
      'try: apply(raw.replace(p["patches"][0]["before"].encode(),b"DRIFT",1),chain)',
      'except ValueError: pass',
      'else: raise AssertionError("drift accepted")',
      'print("PASS_ARCHIVE_FRESH_REPEAT_DRIFT")'
    ].join('\n')],{cwd:root,encoding:'utf8',windowsHide:true,timeout:30000});
    assert.equal(python.status,0,python.stderr);assert.match(python.stdout,/PASS_ARCHIVE_FRESH_REPEAT_DRIFT/);
    const check=spawnSync(process.env.DSHA_PYTHON||'python',['-B','tools/prepare-web-search-headers-patch.py','--modules',path.join(runtime,'node_modules'),'--check'],{cwd:root,encoding:'utf8',windowsHide:true,timeout:30000});
    assert.equal(check.status,0,check.stderr);
    const retained=spawnSync(process.env.DSHA_PYTHON||'python',['-B','tools/prepare-account-requests-patch.py','--modules',path.join(runtime,'node_modules'),'--check'],{cwd:root,encoding:'utf8',windowsHide:true,timeout:30000});
    assert.equal(retained.status,0,retained.stderr);
  });
  for(const target of ['apiKey','accountToken','apiVersion'])await test('official negative control ByteString U+115F '+target,async()=>{
    const extras=target==='accountToken'?{resolveAccountToken:async()=>'x\u115f'}:{[target]:'x\u115f'};
    const f=direct(Official,extras),before=calls.length;
    const error=await f.provider.search({query:'中文 query'}).catch(error=>error);
    assert.match(error.message,/index 1 has a value of 4447/);
    assert.equal(calls.length,before);assert.equal(f.events.length,1,'official negative control recorded before construction');
  });
  for(const target of ['apiKey','accountToken','apiVersion'])await test('preflight identifies exact U+115F field '+target,async()=>{
    const extras=target==='accountToken'?{resolveAccountToken:async()=>'x\u115f'}:{[target]:'x\u115f'};
    await rejected(direct(Fixed,extras),{field:{apiKey:'x-api-key',accountToken:'x-dsh-auth-token',apiVersion:'anthropic-version'}[target],source:{apiKey:'plugin-config.apiKey',accountToken:'deepseek-account',apiVersion:'plugin-config.apiVersion'}[target],category:'non-bytestring',index:1,codePoint:4447,codePointHex:'U+115F'});
  });
  for(const[value,category,index,point]of [['x\rkey','control-character',1,13],['x\nkey','control-character',1,10],['x\0key','control-character',1,0],['x\tkey','control-character',1,9],['x\x7fkey','control-character',1,127],['x\x85key','control-character',1,133],['x key','credential-format',1,32],['xékey','credential-format',1,233],['x🙂key','non-bytestring',1,128578],['x\ud800key','non-bytestring',1,55296]])await test('reject exact credential character '+category+'/'+point,async()=>{
    await rejected(direct(Fixed,{apiKey:value}),{field:'x-api-key',category,index,codePoint:point});
  });
  for(const value of ['bad-version','2023-6-01','2023-06-01 ','2023-06-01-2024'])await test('reject invalid apiVersion date grammar '+value,async()=>{
    await rejected(direct(Fixed,{apiVersion:value}),{field:'anthropic-version',category:'api-version-format'});
  });
  for(const[field,options]of [['x-dsh-auth-token',value=>({resolveAccountToken:async()=>value})],['x-api-key',value=>({apiKey:value})]])
    for(const value of [null,42,{}])await test('reject malformed '+field+' type '+(value===null?'null':typeof value),async()=>{
      await rejected(direct(Fixed,options(value)),{field,category:'invalid-type'});
    });
  await test('request construction failure stays local and cannot quote endpoint or body',async()=>{
    const f=direct(Fixed,{baseURL:'https://user:PRIVATE-ENDPOINT@fixture.invalid/v1'});
    await rejected(f,{field:'request',source:'provider-config',category:'request-construction'});
  });
  for(const query of ['test','drone logistics','无人机物流','中文 English 🙂'])await test('unchanged native Request with legal query '+query,async()=>{
    const f=direct(),before=calls.length,logStart=logs.length;
    const result=await f.provider.search({query});assert.equal(result.sources.length,1);
    assert.equal(calls.length,before+1);assert.equal(f.events.length,1);assert.equal(logs.length,logStart);
    const call=calls.at(-1);assert.equal(call.request.headers.get('x-api-key'),'SYNTHETIC-KEY-KEEP-EXACT');
    assert.equal(call.request.headers.get('authorization'),'Bearer SYNTHETIC-KEY-KEEP-EXACT');
    assert.equal(call.request.headers.get('anthropic-version'),'2023-06-01');
    assert.equal(JSON.parse(call.body).messages[0].content[0].text,'Perform a web search for the query: '+query);
    assert.equal(f.events[0].dispatchState,'fetch-invoked');
  });
  const layers=[['credential-store',{stored:'x\u115f',processKey:'VALID-PROCESS',projectKey:'VALID-PROJECT',userKey:'VALID-USER'}],
    ['process-env',{processKey:'x\u115f',projectKey:'VALID-PROJECT',userKey:'VALID-USER'}],
    ['project-env',{projectKey:'x\u115f',userKey:'VALID-USER'}],['user-env',{userKey:'x\u115f'}]];
  for(const[source,options]of layers)await test('real credentialsLocal provenance and no fallback from '+source,async()=>{
    const f=await integration(options);try{await rejected(f,{field:'x-api-key',source,index:1,codePoint:4447});}finally{await f.close();}
  });
  await test('account token outranks literal and stored API key without reading replacements',async()=>{
    const f=await integration({account:true,token:'SYNTHETIC-ACCOUNT-EXACT',literal:'x\u115f',stored:'x\u115f'});
    try{await f.provider.search({query:'中文'});assert.equal(f.tokenReads,1);assert.equal(calls.at(-1).request.headers.get('x-dsh-auth-token'),'SYNTHETIC-ACCOUNT-EXACT');assert.equal(calls.at(-1).request.headers.get('x-api-key'),null);}finally{await f.close();}
  });
  await test('invalid selected account token never silently changes identity to API key',async()=>{
    const f=await integration({account:true,token:'x\u115f',literal:'VALID-LITERAL',stored:'VALID-STORED'});
    try{await rejected(f,{field:'x-dsh-auth-token',source:'deepseek-account',codePoint:4447});assert.equal(f.tokenReads,1);}finally{await f.close();}
  });
  await test('literal plugin key outranks credentialsLocal and nonaccount never resolves account',async()=>{
    const f=await integration({literal:'SYNTHETIC-LITERAL-EXACT',stored:'x\u115f',token:'x\u115f'});
    try{await f.provider.search({query:'test'});assert.equal(f.tokenReads,0);assert.equal(calls.at(-1).request.headers.get('x-api-key'),'SYNTHETIC-LITERAL-EXACT');}finally{await f.close();}
  });
  await test('empty account token retains original key fallback and absent stored reference resolves process',async()=>{
    const f=await integration({account:true,token:'',processKey:'SYNTHETIC-PROCESS-EXACT'});
    try{await f.provider.search({query:'test'});assert.equal(calls.at(-1).request.headers.get('x-api-key'),'SYNTHETIC-PROCESS-EXACT');}finally{await f.close();}
  });
  await test('launch snapshot fallback without credential service still reports exact source',async()=>{
    const f=await integration({credentials:false,projectKey:'x\u115f'});
    try{await rejected(f,{field:'x-api-key',source:'project-env'});}finally{await f.close();}
  });
  await test('preaborted search does not resolve credentials, record or fetch',async()=>{
    let reads=0;const controller=new AbortController();controller.abort();
    const f=direct(Fixed,{apiKey:undefined,resolveApiKey:async()=>{++reads;return 'SYNTHETIC-KEY';}}),before=calls.length;
    const error=await f.provider.search({query:'test'},controller.signal).catch(error=>error);
    assert.equal(error.code,'WEB_ABORTED');assert.equal(reads,0);assert.equal(calls.length,before);assert.equal(f.events.length,0);
  });
  await test('abort while resolving credentials never constructs or records a request',async()=>{
    let settle,entered;const started=new Promise(resolve=>entered=resolve),controller=new AbortController();
    const f=direct(Fixed,{apiKey:undefined,resolveApiKey:()=>{entered();return new Promise(resolve=>settle=resolve);}}),before=calls.length;
    const running=f.provider.search({query:'test'},controller.signal).catch(error=>error);
    await started;controller.abort();settle('SYNTHETIC-LATE-KEY');
    const error=await running;assert.equal(error.code,'WEB_ABORTED');assert.equal(calls.length,before);assert.equal(f.events.length,0);
  });
  await test('resolver failure is predispatch and cannot expose arbitrary secret exception',async()=>{
    const f=direct(Fixed,{apiKey:undefined,resolveApiKey:async()=>{throw new Error('SECRET_MUST_NOT_APPEAR');}}),before=calls.length;
    const error=await f.provider.search({query:'test'}).catch(error=>error);
    assert.match(error.message,/before HTTP dispatch/);assert(!error.message.includes('SECRET_MUST_NOT_APPEAR'));assert.equal(error.cause,undefined);
    assert.equal(calls.length,before);assert.equal(f.events.length,0);
  });
  await test('fetch failure reports unconfirmed transmission without exposing exception or claiming endpoint used',async()=>{
    dispatchMode='reject';const f=direct(),before=calls.length;
    try{const error=await f.provider.search({query:'test'}).catch(error=>error);assert.equal(calls.length,before+1);assert.equal(f.events.length,1);assert.match(error.message,/transmission is unconfirmed/);assert(!error.message.includes('SECRET_MUST_NOT_APPEAR'));assert.equal(error.cause,undefined);}finally{dispatchMode='success';}
  });
  await test('throwing request observer cannot abandon pending fetch or quote observer secrets',async()=>{
    dispatchMode='reject';const before=calls.length,startLogs=logs.length;
    const f=direct(Fixed,{recordRequest:()=>{throw new Error('SECRET_MUST_NOT_APPEAR');}});
    try{const error=await f.provider.search({query:'test'}).catch(error=>error);assert.equal(calls.length,before+1);assert.match(error.message,/transmission is unconfirmed/);assert.equal(logs.length,startLogs+1);assert(!JSON.stringify(logs.slice(startLogs)).includes('SECRET_MUST_NOT_APPEAR'));}finally{dispatchMode='success';}
  });
  await test('account 401 retains sign-in guidance and adds no key fallback or extra request',async()=>{
    dispatchMode='401';const f=direct(Fixed,{resolveAccountToken:async()=>'SYNTHETIC-ACCOUNT-EXACT'}),before=calls.length;
    try{const error=await f.provider.search({query:'test'}).catch(error=>error);assert.match(error.message,/rejected the account sign-in/);assert.equal(calls.length,before+1);assert.equal(f.events.length,1);}finally{dispatchMode='success';}
  });
  const receipt={status:'PASS',tests:results.length,results,module:recipe.module,sourceSha256:recipe.sourceSha256,patchedSha256:sha(changed),recipeSha256:sha(fs.readFileSync(path.join(root,'app/src/main/assets',recipeName))),networkMode:'synthetic fetch intercept with real native Request; no HTTP sockets or real account/model requests',deviceValidation:false,output};
  fs.writeFileSync(path.join(output,'result.json'),JSON.stringify(receipt,null,2)+'\n');
  console.log(JSON.stringify(receipt));
}finally{globalThis.fetch=nativeFetch;console.error=originalConsole;}
