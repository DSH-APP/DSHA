// DSH 0.2.2：工具执行在 dsh-tools/lib/pipeline.js 的 ToolExecutionPipeline，由原生与 PTC 执行器共用。
// 本测试用真实锁定的 0.2.2 ToolsService / 执行器 / Bash / FS / editor 及真实策略模块，
// 只替换原生同意 SDK（固定 globalAllow=false）、会话投影与 shell 进程。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import Module from 'node:module';
import { pathToFileURL } from 'node:url';
import { testRuntime } from './test-runtime-fixture.mjs';
import { classifyCommand } from '../app/src/main/assets/builtin-plugins/dsh-destructive-guard/lib/destructive.js';
const root=path.resolve(import.meta.dirname,'..'), assets=path.join(root,'app/src/main/assets');
const raw=testRuntime('raw'), directory=fs.mkdtempSync(path.join(root,'app/build/guard172-'));
// 被补丁或需要解析到补丁副本的包：执行器按自身真实路径解析 @deepseek-ai/dsh-tools/pipeline，必须是实体副本。
const OWNED=['dsh-tools','dsh-tool-executor-native','dsh-tool-executor-ptc','dsh-tool-bash','dsh-tool-fs','dsh-tool-str-replace-editor'].map(name=>'@deepseek-ai/'+name);
const RECIPES=['guard-tools-patch.json','tool-dispatch-validation-pipeline-patch.json','guard-tools-pipeline-patch.json','guard-bash-patch.json','guard-fs-patch.json','guard-editor-patch.json'];
const link=(from,to)=>fs.symlinkSync(from,to,process.platform==='win32'?'junction':'dir');
function tree(label,recipes){
 const modules=path.join(directory,label,'node_modules');fs.mkdirSync(path.join(modules,'@deepseek-ai'),{recursive:true});
 for(const name of fs.readdirSync(path.join(raw,'node_modules'))){
  const source=path.join(raw,'node_modules',name);
  if(name==='@deepseek-ai'){for(const child of fs.readdirSync(source)){const id=name+'/'+child;if(OWNED.includes(id))fs.cpSync(path.join(source,child),path.join(modules,id),{recursive:true});else link(path.join(source,child),path.join(modules,id));}}
  else if(fs.statSync(source).isDirectory())link(source,path.join(modules,name));
 }
 for(const asset of recipes){
  const recipe=JSON.parse(fs.readFileSync(path.join(assets,asset),'utf8')), target=path.join(modules,recipe.module);
  assert(OWNED.includes(recipe.module.split('/').slice(0,2).join('/')),asset);
  let source=fs.readFileSync(target,'utf8');
  for(const patch of recipe.patches){assert.equal(source.split(patch.before).length-1,1,'locked 0.2.2 anchor '+asset);source=source.replace(patch.before,patch.after);}
  fs.writeFileSync(target,source);
 }
 fs.cpSync(path.join(assets,'destructive-policy'),path.join(modules,'dsha-destructive-policy'),{recursive:true});
 return modules;
}
// 真机容器里 /run/dsha-launcher/native-consent.cjs 是真实 SDK，其全局允许会豁免审批；测试固定为关闭。
const consent={schema:1,available:true,globalAllow:false,revision:0,installationId:'11111111-2222-3333-4444-555555555555',bridgeRunId:'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',webGeneration:7,runtimeInstanceId:'22222222-3333-4444-5555-666666666666:7'};
const sdk={readConnection:()=>({applicationId:'com.dsh.client',generation:1,port:3090}),bridgeFetch:async()=>({ok:true,json:async()=>({result:JSON.stringify(consent)})})};
const originalLoad=Module._load;Module._load=function(name,...args){return name==='/run/dsha-launcher/native-consent.cjs'?sdk:originalLoad.call(this,name,...args);};
async function runtime(modules){
 const load=name=>import(pathToFileURL(path.join(modules,name)));
 const value={Context:(await load('@deepseek-ai/cordis/lib/index.js')).Context,ToolsService:(await load('@deepseek-ai/dsh-tools/lib/index.js')).ToolsService,
  native:await load('@deepseek-ai/dsh-tool-executor-native/lib/index.js'),ptc:await load('@deepseek-ai/dsh-tool-executor-ptc/lib/index.js'),
  ApprovalService:(await load('@deepseek-ai/dsh-user-approval/lib/index.js')).ApprovalService,policy:await load('dsha-destructive-policy/index.js'),
  LocalFileSystem:(await load('@deepseek-ai/dsh-fs-local/lib/index.js')).LocalFileSystem,fsTools:await load('@deepseek-ai/dsh-tool-fs/lib/index.js'),
  editor:await load('@deepseek-ai/dsh-tool-str-replace-editor/lib/index.js'),bash:await load('@deepseek-ai/dsh-tool-bash/lib/index.js')};
 return value;
}
const full=await runtime(tree('full',RECIPES));
// 两个执行器都必须解析到本 fixture 的补丁 pipeline，而不是原始树。
for(const Executor of [full.native.NativeToolExecutor,full.ptc.PtcExecutor]){
 const pipeline=new Executor({tools:{executable(){}},systemPrompt:{getSectionOrder(){return 1;}}}).pipeline;
 const methods=['createExecution','prepare','dispatchToolBody'].map(name=>Object.getPrototypeOf(pipeline)[name].toString()).join('\n');
 for(const hook of ['captureExecution(this.tools','prepareOperation(this.tools','verifyGlobalConsent(exec)','verifyDispatch(this.tools'])assert(methods.includes(hook),hook);
}
const original=fs.readFileSync(path.join(root,'tools/test-destructive-guard.mjs'),'utf8');
const literal=original.slice(original.indexOf('const KNOWN_BYPASS = [')+'const KNOWN_BYPASS = '.length,original.indexOf('\n];',original.indexOf('const KNOWN_BYPASS = ['))+3);
const bypasses=Function('return '+literal)();assert.equal(bypasses.length,16);
let cases=0;
const settle=()=>new Promise(resolve=>setTimeout(resolve,0));
const prompt={tools(){},context(){},getContextOrder(){return 10;},getSectionOrder(){return 10;},section(){}};
// 最小会话：approval.request 需要 turnOpen 的投影与 append 返回 seq。
function session(cwd){const events=[{type:'turn/start',data:{}}];return {header:{cwd},events,get seq(){return events.length;},eventAt(i){return events[i];},append(type,data){events.push({type,data});return {seq:events.length};}};}
async function context(rt,{cwd='/root/work',approval='ask',executor='native',ptcRuntime}={}){
 const ctx=new rt.Context();ctx.provide('systemPrompt',prompt);
 ctx.provide('workingDirectory',{get:()=>cwd,ensure:async()=>cwd});
 ctx.provide('sessionProjections',{async register(){return {stateOf:()=>({turnOpen:true})};}});
 new rt.ToolsService(ctx);
 if(approval!=='missing-service')ctx.plugin(rt.ApprovalService,{policy:approval});
 if(executor==='ptc'){ctx.provide('ptcRuntime',ptcRuntime);ctx.plugin(rt.ptc,{});}else ctx.plugin(rt.native);
 await settle();assert(ctx.get('tools'));
 return ctx;
}
function call(ctx,agent,name,args){
 return ctx.tools.execute({calls:[{type:'tool-call',id:'case-'+cases++,name,arguments:JSON.stringify(args)}],agent,signal:new AbortController().signal}).then(batch=>batch.results[0].result);
}
// run_code 的语言运行时是内存缝：真实 PTC 传输把绑定调用送进同一 pipeline（parent=run_code token）。
const ptcBash=command=>({language:'typescript',resolve(request){return request;},async run(request){try{await request.bindings[0].functions.bash({command,description:'run_code nested fixture'});return {logs:[],value:{}};}catch(error){return {logs:[],error:{kind:'exception',message:error.message}};}}});
async function harness(command,outcome='allowed-once',mutate,approval='ask',executor='native',rt=full){
 const ctx=await context(rt,{approval:outcome==='missing-service'?'missing-service':approval,executor,ptcRuntime:ptcBash(command)});
 let collects=0;ctx.provide('shellEnv',{collect(){collects++;return {};}});
 const agent={session:session('/root/work'),ctx};
 let asks=0,bodies=0;const definition={name:'bash',description:'Memory counter only; no shell is started.',parameters:{},output:{schema:{type:'object'},render:()=>[{type:'text',text:'counter'}]},execute(args,exec){rt.policy.preparedEnvironment(exec);bodies++;return {};}};
 const dispose=ctx.tools.register(definition);
 ctx.on('approval/request',async request=>{asks++;assert(request.displayReason.en.includes('DSHA'));if(executor==='ptc')assert(request.callId.endsWith(':ptc:1'));if(mutate)await mutate({ctx,agent,dispose,definition,request});return outcome;});
 const result=executor==='ptc'?await call(ctx,agent,'run_code',{code:'await tools.bash({command:"x"})',description:'memory runtime invoking real transport binding'}):await call(ctx,agent,'bash',{command});
 await ctx.fiber.dispose();return {asks,bodies,collects,result};
}
for(const executor of ['native','ptc'])for(const [command] of bypasses){
 assert(classifyCommand(command,{cwd:'/root/work'}).requiresApproval,command);
 for(const outcome of ['allowed-once','rejected','cancelled','unavailable','missing-service']){
  const result=await harness(command,outcome,undefined,'ask',executor);
  assert.equal(result.bodies,outcome==='allowed-once'?1:0,executor+' '+command+' '+outcome+' '+JSON.stringify(result.result));assert.equal(result.asks,outcome==='missing-service'?0:1);assert.equal(result.collects,1);
 }
}
for(const executor of ['native','ptc'])for(const command of ['pwd','ls /tmp','cat /tmp/log','printf "rm -rf /"','sed -n "1p" /tmp/log','git status','curl https://example.com']){const result=await harness(command,'allowed-once',undefined,'ask',executor);assert.equal(result.asks,0,command);assert.equal(result.bodies,1,executor+' '+command+' '+JSON.stringify(result.result));}
assert.equal((await harness('rm /tmp/fixture','allowed-once',undefined,'never')).bodies,0);
assert.equal((await harness('rm /tmp/fixture','allowed-once',({agent})=>{agent.session.header.cwd='/changed';})).bodies,0);
assert.equal((await harness('rm /tmp/fixture','allowed-once',({agent})=>{agent.session=session('/root/work');})).bodies,0);
assert.equal((await harness('rm /tmp/fixture','allowed-once',({ctx,dispose,definition})=>{dispose();ctx.tools.register({...definition,execute(){throw Error('replacement must not run');}});})).bodies,0);
assert.equal((await harness('rm /tmp/fixture','allowed-once',({definition})=>{definition.execute=()=>{throw Error('mutated body must not run');};})).bodies,0);
assert.equal((await harness('rm /tmp/fixture','allowed-once',({agent})=>{agent.ctx=new full.Context();})).bodies,0);
assert.equal((await harness('rm /tmp/fixture','allowed-once',({agent})=>{agent.session.header={...agent.session.header};})).bodies,0);
assert.equal((await harness('rm /tmp/fixture','allowed-once',({ctx})=>{ctx.set('approval',{request(){return 'allowed-once';},effectivePolicy(){return 'ask';}});})).bodies,0);
assert.equal((await harness('rm /tmp/fixture','allowed-once',({ctx})=>{ctx.set('shellEnv',{collect(){throw Error('replacement');}});})).bodies,0);
const oldEnv=process.env.DSHA_GUARD_TEMP;
assert.equal((await harness('rm /tmp/fixture','allowed-once',()=>{process.env.DSHA_GUARD_TEMP='changed';})).bodies,0);
if(oldEnv===undefined)delete process.env.DSHA_GUARD_TEMP;else process.env.DSHA_GUARD_TEMP=oldEnv;
// 执行对象的身份字段（含 0.2.2 新增 toolSet）冻结：around 包装改写后不能派发未批准的调用。
for(const key of ['arguments','name','toolSet']){
 const ctx=await context(full);ctx.provide('shellEnv',{collect(){return {};}});let bodies=0,asks=0;
 for(const name of ['bash','read'])ctx.tools.register({name,description:'counter',parameters:{},output:{schema:{type:'object'},render:()=>[]},execute(){bodies++;return {};}});
 ctx.on('approval/request',async()=>{asks++;return 'allowed-once';});
 ctx.on('tools/execute',async(exec,next)=>{exec[key]=key==='name'?'bash':key==='toolSet'?{view:()=>new Map()}:{command:'rm /unapproved'};return next();});
 const result=await call(ctx,{ctx,session:session('/root/work')},key==='name'?'read':'bash',{command:'rm /approved'});assert(result.isError,key);assert.equal(bodies,0,key);assert.equal(asks,key==='name'?0:1,key);await ctx.fiber.dispose();
}
// 真实 0.2.2 文件系统 provider 与 write/edit/editor 工具体，只写私有临时目录。
async function fsContext(rt,{cwd=directory,executor='native',ptcRuntime}={}){
 const ctx=await context(rt,{cwd,executor,ptcRuntime});new rt.LocalFileSystem(ctx,{cwd,diffBasisMaxBytes:1048576});
 rt.fsTools.apply(ctx,rt.fsTools.Config({readImage:false}));rt.editor.apply(ctx,{maxOutputChars:16000,description:'temporary editor'});
 return ctx;
}
for(const outcome of ['allowed-once','rejected','cancelled','unavailable']){
 const ctx=await fsContext(full);let asks=0;ctx.on('approval/request',async()=>{asks++;return outcome;});
 const agent={ctx,session:session(directory)},run=(name,args)=>call(ctx,agent,name,args);
 const existing=path.join(directory,'file-'+outcome);fs.writeFileSync(existing,'original');
 let result=await run('write',{file_path:existing,content:'replacement'});assert.equal(result.isError===true,outcome!=='allowed-once',JSON.stringify(result));assert.equal(fs.readFileSync(existing,'utf8'),outcome==='allowed-once'?'replacement':'original');assert.equal(asks,1);
 const fresh=path.join(directory,'fresh-'+outcome);result=await run('write',{file_path:fresh,content:'fresh'});assert.equal(result.isError===true,false,JSON.stringify(result));assert.equal(asks,1);
 result=await run('edit',{file_path:existing,old_string:outcome==='allowed-once'?'replacement':'original',new_string:'edited'});assert.equal(result.isError===true,outcome!=='allowed-once');assert.equal(asks,2);
 result=await run('str_replace_editor',{command:'insert',path:existing,insert_line:0,new_str:'prefix'});assert.equal(result.isError===true,outcome!=='allowed-once');assert.equal(asks,3);await ctx.fiber.dispose();cases++;
}
// 真实 Bash 工具体必须消费已收集的环境；shell 执行器是内存缝。原生与 run_code 嵌套各一次。
for(const executor of ['native','ptc'])for(const outcome of ['allowed-once','rejected']){
 const command='rm /root/work/existing';
 const ctx=await context(full,{executor,ptcRuntime:ptcBash(command)});
 let collects=0,bodies=0,asks=0;ctx.provide('shellEnv',{collect(){collects++;return {DSH_GUARD_FIXTURE:'frozen'};}});
 ctx.provide('shell',{resolve(request){assert.equal(request.command,command);assert.equal(request.workdir,'/root/work');assert.deepEqual(request.dshEnv,{DSH_GUARD_FIXTURE:'frozen'});return request;},async execute(){bodies++;return {result:async()=>({exitCode:0,signal:null,timedOut:false,aborted:false,timeoutMs:30000,stdout:{text:'',truncated:false},stderr:{text:'',truncated:false}})}}});
 ctx.plugin(full.bash,{enableRunInBackground:false,promoteOnTimeout:false});await settle();assert(ctx.tools.get('bash'));
 ctx.on('approval/request',async request=>{asks++;if(executor==='ptc')assert(request.callId.endsWith(':ptc:1'));return outcome;});
 const agent={ctx,session:session('/root/work')};
 const result=executor==='ptc'?await call(ctx,agent,'run_code',{code:'await tools.bash(...)',description:'actual binding'}):await call(ctx,agent,'bash',{command,description:'counter-only rm fixture'});
 assert.equal(bodies,outcome==='allowed-once'?1:0,executor+' '+JSON.stringify(result));assert.equal(asks,1,executor);assert.equal(collects,1);await ctx.fiber.dispose();cases++;
}
// 审批期间文件被他人改写：保留他人字节，不写入已批准的替换内容。
for(const name of ['write','edit','str_replace_editor']){
 const ctx=await fsContext(full);
 const file=path.join(directory,'race-'+name);fs.writeFileSync(file,'original');let asks=0;ctx.on('approval/request',async()=>{asks++;fs.writeFileSync(file,'concurrent-writer');return 'allowed-once';});
 const args=name==='write'?{file_path:file,content:'replacement'}:name==='edit'?{file_path:file,old_string:'original',new_string:'replacement'}:{command:'insert',path:file,insert_line:0,new_str:'replacement'};
 const result=await call(ctx,{ctx,session:session(directory)},name,args);assert(result.isError);assert.equal(asks,1);assert.equal(fs.readFileSync(file,'utf8'),'concurrent-writer');await ctx.fiber.dispose();cases++;
}
// 缺任一挂点都必须 fail-closed：rm 与覆盖写在原生和 run_code 嵌套路径都不能执行工具体。
for(const [label,omitted] of [['no-pipeline-hooks','guard-tools-pipeline-patch.json'],['no-attach-policy','guard-tools-patch.json']]){
 const rt=await runtime(tree(label,RECIPES.filter(name=>name!==omitted)));
 for(const executor of ['native','ptc']){
  const result=await harness('rm /tmp/fixture','allowed-once',undefined,'ask',executor,rt);
  assert.equal(result.bodies,0,label+' '+executor+' '+JSON.stringify(result.result));assert(result.result.isError||executor==='ptc',label);
 }
 const ctx=await fsContext(rt);let asks=0;ctx.on('approval/request',async()=>{asks++;return 'allowed-once';});
 const file=path.join(directory,'file-'+label);fs.writeFileSync(file,'original');
 const result=await call(ctx,{ctx,session:session(directory)},'write',{file_path:file,content:'replacement'});
 assert(result.isError,label);assert.equal(fs.readFileSync(file,'utf8'),'original',label);await ctx.fiber.dispose();cases++;
}
// apply_patch：上游默认关闭（用户决定不加 DSHA 拒绝补丁）。默认配置下不得注册。
{
 assert.equal(full.fsTools.Config({}).applyPatch,false);
 const ctx=await fsContext(full);assert.equal(ctx.tools.get('apply_patch'),undefined);assert(ctx.tools.get('write'));await ctx.fiber.dispose();cases++;
}
Module._load=originalLoad;
fs.rmSync(directory,{recursive:true,force:true});
console.log(JSON.stringify({status:'PASS',cases,historicalBypasses:bypasses.length,executors:['native','ptc-run_code-nested'],missingHookFailClosed:true,mandatoryProfileIndependent:true,ordinaryReadonlyNoPrompt:true,realDsh022:true,temporaryFilesystem:true}));
