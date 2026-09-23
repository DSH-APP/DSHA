// 实际 DSH 服务、API、会话写盘与重启回归；模型端点限定为本机的确定性 fixture。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {randomUUID,createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';

const base=path.resolve(process.env.DSHA_ALPHA2_TEST_ROOT||'app/build/release136/data-regression');
assert.ok(base.startsWith(path.resolve('app/build')+path.sep));fs.mkdirSync(base,{recursive:true});
const home=path.join(base,'home'),workspace=path.join(base,'workspace');
const runMode=process.env.DSHA_TEST_PHASE||'';
const checkpointPath=path.join(base,'checkpoint.json');
const checkpoint=runMode==='resume'?JSON.parse(fs.readFileSync(checkpointPath,'utf8')):undefined;
fs.mkdirSync(workspace,{recursive:true});
if(process.env.DSHA_OFFICE_FIXTURE_DIR)for(const extension of ['docx','xlsx','pptx'])
 fs.copyFileSync(path.join(process.env.DSHA_OFFICE_FIXTURE_DIR,'fixture.'+extension),path.join(workspace,'fixture.'+extension));
const fixture=path.join(workspace,'保留目录','nested');fs.mkdirSync(fixture,{recursive:true});
const original=checkpoint?Buffer.from(checkpoint.original,'base64'):Buffer.from('DSHA upgrade fixture 中文\n'+randomUUID());
if(!checkpoint)fs.writeFileSync(path.join(fixture,'original.bin'),original);
const agentsHome=path.join(base,'agents-home'),skillRoot=path.join(agentsHome,'skills','dsha-fixture');
if(!checkpoint){fs.mkdirSync(skillRoot,{recursive:true});fs.writeFileSync(path.join(skillRoot,'SKILL.md'),'---\nname: dsha-fixture\ndescription: Owned regression fixture.\n---\nDSHA_SKILL_OK\n');}
const digest=x=>createHash('sha256').update(x).digest('hex');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const reports=[],requests=[],toolResults=[],modelErrors=[];
let action;
let fakeRequests=0;
const fake=createServer(async(req,res)=>{
 try{
  assert.match(req.url,/\/chat\/completions$/);let raw='';for await(const chunk of req)raw+=chunk;
  const body=JSON.parse(raw);assert.equal(req.headers.authorization,'Bearer dsha-owned-fixture-key');
  requests.push(body);fakeRequests++;
  const tools=body.tools?.map(t=>t.function)||[];
  fs.writeFileSync(path.join(base,'tool-schemas.json'),JSON.stringify(tools,null,2));
  let delta={role:'assistant',content:'DSHA_FIXTURE_COMPLETE'},finish='stop';
  // 标题等后台摘要请求没有工具声明，不能误认成当前工具回合的后续请求。
  if(action&&tools.length>0){
   if(!action.sent){
    assert.ok(tools.some(t=>t.name===action.name),'Tool unavailable: '+action.name);
    action.sent=true;delta={role:'assistant',tool_calls:[{index:0,id:action.id,type:'function',function:{name:action.name,arguments:JSON.stringify(action.args)}}]};finish='tool_calls';
   }else{
    const result=body.messages.find(m=>m.role==='tool'&&m.tool_call_id===action.id);
    if(!result)fs.writeFileSync(path.join(base,'unexpected-model-tail.json'),JSON.stringify(body.messages.slice(-4),null,2));
    assert.ok(result,'Tool result missing: '+action.name);
    const value=typeof result.content==='string'?result.content:JSON.stringify(result.content);
    toolResults.push({phase:action.phase,name:action.name,result:value});
    fs.writeFileSync(path.join(base,'tool-results.json'),JSON.stringify(toolResults,null,2));
    assert.doesNotMatch(value,/(?:^|\n)Error:|\[tool error\]|ToolCallError|permission denied|ACCESS_DENIED|sandbox denied|could not execute/i,action.name);
    if(action.expected)assert.ok(value.includes(action.expected),action.name+' unexpected result: '+value.slice(0,500));
    action.received=true;delta={role:'assistant',content:'DSHA_FIXTURE_COMPLETE '+action.id};
   }
  }
  const id='chatcmpl-'+randomUUID();res.writeHead(200,{'content-type':'text/event-stream','cache-control':'no-cache'});
  for(const [chunk,finish_reason] of [[delta,null],[{},finish]])
   res.write('data: '+JSON.stringify({id,object:'chat.completion.chunk',created:Math.floor(Date.now()/1000),model:'fixture-model',choices:[{index:0,delta:chunk,finish_reason}]})+'\n\n');
  res.end('data: [DONE]\n\n');
 }catch(e){modelErrors.push(String(e));res.writeHead(500);res.end(String(e));}
});
await new Promise((r,reject)=>{fake.once('error',reject);fake.listen(checkpoint?.port||0,'127.0.0.1',r);});
const provider={displayName:'DSHA owned fixture',api:'openai-completions',baseURL:`http://127.0.0.1:${fake.address().port}/v1`,apiKeyEnv:'DSHA_FIXTURE_KEY',models:[{id:'fixture-model',name:'Fixture model',contextWindow:64000,maxTokens:4096,input:['text']}]};
async function boot(runtime,phase){
 fs.mkdirSync(path.join(home,'profiles/web'),{recursive:true});
 const manifest=path.join(home,'profiles/web/package.json');
 if(!fs.existsSync(manifest))fs.writeFileSync(manifest,JSON.stringify({name:'dsha-data-regression',private:true,dsh:{profile:{bundles:['@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app'],patchReload:'startup'}}}));
 const env=Object.fromEntries(Object.entries(process.env).filter(([k])=>!/(API_KEY|TOKEN|SECRET|PASSWORD)/i.test(k)));
 const entry=process.env.DSHA_PACKAGE_DIR?path.resolve(process.env.DSHA_PACKAGE_DIR,'lib/bin.js'):path.resolve(runtime,'node_modules/@deepseek-ai/dsh/lib/bin.js');
 const child=spawn(process.execPath,[entry,'web','--no-open','--host','127.0.0.1','--port','0'],
  {cwd:workspace,env:{...env,DSH_HOME:home,DSH_AGENTS_HOME:agentsHome,BROWSER:'true',DEEPSEEK_API_KEY:'',DSH_CONFIRM:'1',DSH_PERMISSION_MODE:process.env.DSHA_TEST_PERMISSION_MODE||'workspace-write',SSH_CONNECTION:'127.0.0.1 1 127.0.0.1 22'},windowsHide:true,stdio:['ignore','pipe','pipe']});
 let log='',exited=false;child.on('exit',()=>exited=true);
 for(const stream of [child.stdout,child.stderr])stream.on('data',b=>{log=(log+b).slice(-250000);});
 const close=async()=>{if(!exited)child.kill();for(let i=0;i<100&&!exited;i++)await delay(100);assert.ok(exited,'Owned test host did not exit');
  fs.writeFileSync(path.join(base,phase+'-boot.log'),log.replace(/token=[A-Za-z0-9_-]+/g,'token=[REDACTED]'));};
 try{
  let auth;for(let i=0;i<600&&!exited;i++){auth=log.match(/http:\/\/127\.0\.0\.1:\d+\/\?token=[A-Za-z0-9_-]{43}/)?.[0];if(auth)break;await delay(200);}
  assert.ok(auth,phase+' did not boot: '+log.slice(-1500));const origin=new URL(auth).origin;
  let exchange;for(let i=0;i<30;i++){try{exchange=await fetch(auth,{redirect:'manual'});break;}catch{await delay(200);}}
  assert.equal(exchange.status,303);const cookie=exchange.headers.get('set-cookie').split(';')[0];
  const rpc=async(method,args={})=>{if(method.startsWith('session/'))args=method==='session/list'?{_request:args}:{request:args};const rpcId=randomUUID();const response=await fetch(origin+'/api/'+method,{method:'POST',headers:{Cookie:cookie,Origin:origin,'Content-Type':'application/json'},body:JSON.stringify({type:'client-request',rpcId,method,payload:{args}}),signal:AbortSignal.timeout(method.startsWith('officeToPdf/')?180000:30000)});
   assert.equal(response.status,200,method+' HTTP');const value=await response.json();assert.equal(value.rpcId,rpcId);assert.equal(value.result.ok,true,method+': '+JSON.stringify(value.result.error));return value.result.value;};
  return {rpc,close,origin,cookie};
 }catch(e){await close();throw e;}
}
let sessionId=checkpoint?.sessionId;
try{
 const newer=process.env.DSHA_ALPHA2_RUNTIME||'app/build/release136/host-runtime';
 const fresh=process.env.DSHA_FRESH_ALPHA2==='1';
 const runtimes=runMode==='seed'?[['alpha1-device',newer]]:runMode==='resume'?[['alpha2-device-upgraded',newer],['alpha2-device-restart',newer]]:fresh?[['alpha2-fresh',newer],['alpha2-fresh-restart',newer]]:
   [['alpha1',process.env.DSHA_ALPHA1_RUNTIME||'app/build/alpha1-016-host-runtime'],['alpha2',newer],['alpha2-restart',newer]];
 const firstPhase=checkpoint?.firstPhase||runtimes[0][0];
 for(const [phase,runtime] of runtimes){
  const host=await boot(runtime,phase);
  const modules=path.resolve(process.env.DSHA_PACKAGE_DIR||runtime,'node_modules');
  const {Context}=await import(pathToFileURL(path.join(modules,'@deepseek-ai/cordis/lib/index.js')));
  const {default:Persistence}=await import(pathToFileURL(path.join(modules,'@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js')));
  const readerContext=new Context(),persistence=new Persistence(readerContext,{root:path.join(home,'sessions')});
  async function readEvents(id=sessionId){const handle=await persistence.open(id,'read');try{return (await handle.read()).events;}finally{await handle.close();}}
  try{
   const settings=await host.rpc('settings/describe');
   fs.writeFileSync(path.join(base,phase+'-settings.json'),JSON.stringify(settings,null,2));
   const namespace=settings.namespaces.find(n=>n.ns==='llm-pi-ai');assert.ok(namespace,'pi-ai settings missing');
   if(!checkpoint&&phase===firstPhase){
    await host.rpc('credentials/set',{ref:'DSHA_FIXTURE_KEY',value:'dsha-owned-fixture-key'});
    await host.rpc('settings/mutate',{ns:'llm-pi-ai',ops:[{op:'set',path:['providers','dsha-fixture'],value:provider}],expectedRevision:namespace.revision});
    sessionId=(await host.rpc('session/create',{cwd:workspace,agentPreset:'standard'})).sessionId;
    await host.rpc('session/rename',{sessionId,title:'DSHA upgrade preserved session'});
   }else{
    assert.deepEqual(namespace.user.providers['dsha-fixture'],provider,'Provider configuration disappeared');
    const list=await host.rpc('session/list');assert.ok(list.items.some(s=>s.sessionId===sessionId),'Session disappeared after update/restart');
    assert.ok(JSON.stringify(await readEvents()).includes('DSHA owned upgrade check '+firstPhase),'Original conversation missing from actual persistence backend');
   }
   const key=await host.rpc('credentials/describe',{refs:['DSHA_FIXTURE_KEY']});assert.equal(key.DSHA_FIXTURE_KEY.configured,true);
   assert.equal(digest(fs.readFileSync(path.join(fixture,'original.bin'))),digest(original),'Workspace file changed/lost');
   await host.rpc('session/selectModel',{sessionId,provider:'dsha-fixture',model:'fixture-model'});
   const count=fakeRequests;
   await host.rpc('session/prompt',{requestId:randomUUID(),sessionId,mode:'queue',content:[{type:'text',text:'DSHA owned upgrade check '+phase}]});
   let completed=false;
   for(let i=0;i<300;i++){
    const events=await readEvents(),t=JSON.stringify(events),prompt=t.lastIndexOf('DSHA owned upgrade check '+phase);
    completed=fakeRequests>count&&prompt>=0&&t.lastIndexOf('DSHA_FIXTURE_COMPLETE')>prompt&&events.at(-1)?.type==='turn/end';
    if(completed)break;await delay(200);
   }
   assert.ok(completed,phase+' model request/session write did not finish');
   if(process.env.DSHA_OFFICE_FIXTURE_DIR){
    for(const extension of ['docx','xlsx','pptx']){
     const result=await host.rpc('officeToPdf/render',{workspaceFileScopeId:sessionId,path:path.join(workspace,'fixture.'+extension),priority:'foreground'});
     const pdf=Buffer.from(result.data,'base64');assert.equal(pdf.subarray(0,5).toString(),'%PDF-');assert.ok(pdf.length>1000);
     fs.writeFileSync(path.join(base,phase+'-'+extension+'.pdf'),pdf);
     console.log(JSON.stringify({phase,office:extension,status:'PASS',pdfBytes:pdf.length,missingFonts:result.missingFonts}));
    }
   }
   if(process.env.DSHA_TEST_TOOLS==='1'){
    const file=path.join(workspace,'tool-output.txt');
    const token='DSHA_WRITE_'+phase.replaceAll('-','_'),edited='DSHA_EDIT_'+phase.replaceAll('-','_');
    const actions=[['write',{file_path:file,content:token}],['read',{file_path:file},token],
      ['edit',{file_path:file,old_string:token,new_string:edited}],['read',{file_path:file},edited],
      ['glob',{pattern:'tool-output.txt',path:workspace},'tool-output.txt'],
      ['grep',{pattern:edited,path:workspace},edited],['skill',{name:'dsha-fixture'},'DSHA_SKILL_OK'],
      [process.platform==='win32'?'pwsh':'bash',{command:process.platform==='win32'?"Write-Output 'DSHA_SHELL_OK'":"printf 'DSHA_SHELL_OK\\n'",description:'Owned fixture echo'},'DSHA_SHELL_OK'],
      ['job_list',{}],['present',{files:[{path:file,description:'Owned fixture'}]}]];
    // 覆盖已有文件必须先在本次 Agent 生命周期实际读取，遵守上游文件新鲜度保护。
    if(fs.existsSync(file))actions.unshift(['read',{file_path:file},fs.readFileSync(file,'utf8')]);
    const ptc=(await host.rpc('session/create',{cwd:workspace,agentPreset:'ptc'})).sessionId;
    await host.rpc('session/selectModel',{sessionId:ptc,provider:'dsha-fixture',model:'fixture-model'});
    actions.push(['run_code',{code:'return await tools.read({file_path:'+JSON.stringify(file)+'});',description:'Read the owned fixture through PTC'},edited,ptc]);
    for(const [name,args,expected,target=sessionId] of actions){
     action={phase,name,args,expected,id:'call_'+randomUUID().replaceAll('-',''),sent:false,received:false};
     await host.rpc('session/prompt',{requestId:randomUUID(),sessionId:target,mode:'queue',content:[{type:'text',text:'Owned tool regression '+action.id}]});
     let done=false;
     for(let i=0;i<400;i++){
      if(modelErrors.length)throw Error(modelErrors.at(-1));
      const events=await readEvents(target),text=JSON.stringify(events);
      if(action.received&&events.at(-1)?.type==='turn/end'&&text.includes('DSHA_FIXTURE_COMPLETE '+action.id)){done=true;break;}
      await delay(150);
     }
     assert.ok(done,phase+' tool did not complete: '+name);
     console.log(JSON.stringify({phase,tool:name,status:'PASS'}));action=undefined;
    }
    assert.equal(fs.readFileSync(file,'utf8'),edited);
    assert.equal(digest(fs.readFileSync(path.join(fixture,'original.bin'))),digest(original));
   }
   const metadata=process.env.DSHA_PACKAGE_DIR?path.join(process.env.DSHA_PACKAGE_DIR,'package.json'):path.join(modules,'@deepseek-ai/dsh/package.json');
   const version=JSON.parse(fs.readFileSync(metadata,'utf8')).version;
   reports.push({phase,version,sessionId,credentialConfigured:true,credentialUsed:true,workspaceSha256:digest(original),modelRequests:fakeRequests-count});
   console.log(JSON.stringify(reports.at(-1)));
  }finally{await host.close();}
 }
 fs.writeFileSync(path.join(base,runMode?'report-'+runMode+'.json':'report.json'),JSON.stringify({status:'PASS',reports},null,2));
 fs.writeFileSync(checkpointPath,JSON.stringify({sessionId,firstPhase,port:fake.address().port,original:original.toString('base64')},null,2));
}finally{await new Promise(r=>fake.close(r));}
