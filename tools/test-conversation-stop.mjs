// Render the real locked rc2 InputBar and call its stop controls. A draft,
// attachment or submitting phase must never remove ordinary turn cancellation.
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test,{after} from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import {createRequire, registerHooks} from 'node:module';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {testRuntime} from './test-runtime-fixture.mjs';
import {reuseConversationRuntime} from './qa-rc21/conversation-stop-runtime.mjs';
const stage=(phase,facts={})=>process.stderr.write(JSON.stringify({phase,at:new Date().toISOString(),...facts})+'\n');
stage('conversation-stop-runtime-binding-before');
const namedProof=process.env.DSHA_STOP_REUSE_INPUTS?reuseConversationRuntime(process.env.DSHA_STOP_REUSE_INPUTS,{sourceRoot:process.cwd(),environmentRuntime:process.env.DSHA_STOP_RUNTIME}):undefined;
const runtime=namedProof?.runtime??(process.env.DSHA_STOP_RUNTIME ? testRuntime('raw', 'DSHA_STOP_RUNTIME') : testRuntime('raw'));
stage('conversation-stop-runtime-binding-after',{controlRole:namedProof?'PRIOR_MANAGED_UNCHANGED_CONTROL':'RAW_LOCKED_CONTROL',currentWholeRootProofExecuted:!namedProof});
namedProof?.observe(path.join(runtime,'node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js'));
let source=readFileSync(runtime+'/node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js','utf8');
const policy=JSON.parse(readFileSync('app/src/main/assets/composer-enter-patch.json','utf8'));
for(const {before,after}of policy.patches){if(namedProof&&source.includes(after)){assert.equal(source.split(after).length-1,1);assert.equal(source.split(before).length-1,after.split(before).length-1);}else{assert.equal(source.split(before).length-1,1);source=source.replace(before,after);}}
const start=source.indexOf('\t\tconst InputBar = '),end=source.indexOf('\n\t\t//#endregion',start);
assert.ok(start>0&&end>start);
function render({running=true,draft='',phase='plain',attachments=[],subagent=null,blocked,stopProvided=true,onStop}={}){
  let stops=0,submits=0;
  const session={running,subagent,removed:false,promptError:null};
  const input={draft,phase,attachmentIds:attachments.map((_,i)=>''+i),queue:[]};
  const jsx=(type,props)=>({type,props});
  const react={memo:f=>f,useState:v=>[v,()=>{}],useRef:current=>({current}),useEffect:()=>{},useLayoutEffect:()=>{},useCallback:f=>f,useMemo:f=>f()};
  const context={react,react_jsx_runtime:{jsx,jsxs:jsx},clsx:(...xs)=>xs.filter(Boolean).join(' '),InputBar_module_css_default:new Proxy({}, {get:(_,key)=>key}),
    _deepseek_ai_dsh_client_ui_primitives:{Tooltip:'Tooltip'},resolveSubmitMode:()=> 'queue',ComposerContentEditable:'Editor',ContextMeter:'Meter',
    AttachmentChip:'AttachmentChip',DraftEditor:'DraftEditor',PromptNotices:'PromptNotices',iconPlus:'plus'};
  vm.createContext(context);vm.runInContext(source.slice(start,end)+'\nglobalThis.render=InputBar;',context);
  const tree=context.render({useSession:select=>select(session),useInput:select=>select(input),inputActions:{},keyboard:{editor:null,submit(){submits++;}},
    resolveDraftAttachments:()=>attachments,useBusyEnter:select=>select('queue'),useFileUploads:select=>select({}),useNotices:select=>select(null),
    useLexicon:select=>select([]),useMenuLauncher:select=>select(null),useStopShortcut:select=>select([]),useProjection:()=>undefined,
    stop:stopProvided?()=>{stops++;onStop?.();}:undefined,t:key=>key,renderSlot:()=>null,sessionId:'fixture-session',variant:'composer',blocked});
  const buttons=[];
  function visit(value){if(!value||typeof value!=='object')return;if(Array.isArray(value)){value.forEach(visit);return;}if(value.type==='button')buttons.push(value);visit(value.props?.children);}
  visit(tree);
  return {buttons,stopButtons:buttons.filter(b=>b.props['aria-label']==='input.stop'),counts:()=>({stops,submits})};
}
test('ordinary running turn remains stoppable with text draft, pending admission and attachments',()=>{
  for(const state of [{draft:'next instruction'},{draft:'admitting',phase:'submitting'},{draft:'',attachments:[{kind:'file',id:'f'}]}]){
    const f=render(state);assert.equal(f.stopButtons.length,1);assert.equal(f.stopButtons[0].props.disabled,false);f.stopButtons[0].props.onClick();assert.deepEqual(f.counts(),{stops:1,submits:0});
  }
});
test('empty running ordinary turn uses one primary stop; blocked draft still stops',()=>{
  for(const state of [{},{draft:'blocked draft',blocked:'plugin-gate'}]){const f=render(state);assert.equal(f.stopButtons.length,1);f.stopButtons[0].props.onClick();assert.equal(f.counts().stops,1);}
});
test('idle and read-only subagent do not offer cancellation; missing handler stays disabled',()=>{
  assert.equal(render({running:false,draft:'hello'}).stopButtons.length,0);
  assert.equal(render({subagent:{address:{mode:'readonly'}}}).stopButtons.length,0);
  const missing=render({draft:'hello',stopProvided:false});assert.equal(missing.stopButtons[0].props.disabled,true);
  const child=render({subagent:{address:{mode:'continuable'},parentAvailable:false}});assert.equal(child.stopButtons.length,1);child.stopButtons[0].props.onClick();assert.equal(child.counts().stops,1);
});
test('Host cancellation keeps queued inbox work and calls the current Agent once',()=>{
  namedProof?.observe(path.join(runtime,'node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js'));
  const server=readFileSync(runtime+'/node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js','utf8');
  const begin=server.indexOf('\tcancel(request) {'),finish=server.indexOf('\n\tasync resolveAgent(',begin);
  assert.ok(begin>0&&finish>begin);const calls=[];const agent={session:{},cancel:(...args)=>calls.push(args)};
  const context={hasApiSessionSubagentOwner:()=>false,apiSessionSubagentOwnershipError:()=>Error('owned'),RemoteError:Error};
  vm.createContext(context);vm.runInContext('globalThis.command={'+server.slice(begin,finish)+'};',context);
  context.command.ctx={agents:{get:id=>id==='fixture-session'?agent:undefined}};
  assert.equal(context.command.cancel({sessionId:'fixture-session'}).accepted,true);
  assert.equal(calls.length,1);assert.equal(calls[0][0].kind,'user');assert.equal(calls[0][1].keepInbox,true);
});

// These cases run only the pinned adapter plus the real loop turn/step methods.
// The session log, prompt preparation and RPC carrier are explicit in-memory
// ports; there is no Host boot, model/network call, private data or tool body.
const require = createRequire(path.join(runtime,'package.json'));
const modulePath = name => require.resolve(name);
const moduleSource = name => {const file=modulePath(name);namedProof?.observe(file);return readFileSync(file,'utf8');};
function applyRecipe(text,name){
  const recipe=JSON.parse(readFileSync('app/src/main/assets/'+name,'utf8'));
  assert.equal(recipe.dshVersion,'0.2.1-rc.2');
  for(const {before,after}of recipe.patches){assert.equal(text.split(before).length-1,1,name+' source anchor');text=text.replace(before,after);}
  return text;
}
const candidateTag='?dsha-owned-cancel-test';
const patchSources=new Map([
  [pathToFileURL(modulePath('@deepseek-ai/dsh-timeout')).href,applyRecipe(moduleSource('@deepseek-ai/dsh-timeout'),'conversation-stream-cancel-patch.json')],
  [pathToFileURL(modulePath('@deepseek-ai/dsh-llm-deepseek')).href,applyRecipe(moduleSource('@deepseek-ai/dsh-llm-deepseek'),'deepseek-stream-cancel-patch.json')]
]);
const hook=registerHooks({
  resolve(specifier,context,next){
    const resolved=next(specifier,context);
    if(namedProof&&resolved.url.startsWith('file:')){const file=fileURLToPath(resolved.url);assert(namedProof.inside(file),'unexpected executable module outside the proven runtime');namedProof.observe(file);}
    if(specifier==='@deepseek-ai/dsh-timeout'&&context.parentURL?.endsWith(candidateTag))return {...resolved,url:resolved.url+candidateTag};
    return resolved;
  },
  load(url,context,next){
    if(url.endsWith(candidateTag)){const original=url.slice(0,-candidateTag.length);if(patchSources.has(original))return {format:'module',source:patchSources.get(original),shortCircuit:true};}
    return next(url,context);
  }
});
stage('conversation-stop-adapter-imports-before');
const llm=await import(pathToFileURL(modulePath('@deepseek-ai/dsh-llm')).href);
const sessionsSdk=await import(pathToFileURL(modulePath('@deepseek-ai/dsh-session')).href);
const rawTimeout=await import(pathToFileURL(modulePath('@deepseek-ai/dsh-timeout')).href);
const timeout=await import(pathToFileURL(modulePath('@deepseek-ai/dsh-timeout')).href+candidateTag);
const rawDeepSeek=await import(pathToFileURL(modulePath('@deepseek-ai/dsh-llm-deepseek')).href);
const deepSeek=await import(pathToFileURL(modulePath('@deepseek-ai/dsh-llm-deepseek')).href+candidateTag);
stage('conversation-stop-adapter-imports-after');
after(()=>{try{if(namedProof){stage('conversation-stop-named-used-final-before');const result=namedProof.finish(require.cache);stage('conversation-stop-named-used-final-after');assert(result.currentWholeRootProofExecuted===false);}}finally{hook.deregister();}});
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function bounded(promise,ms=300){let timer;try{return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('OWNED_CANCEL_DID_NOT_SETTLE')),ms);})]);}finally{clearTimeout(timer);}}
function region(text,start,end){const from=text.indexOf(start),to=text.indexOf(end,from);assert.ok(from>=0&&to>from,start);return text.slice(from,to);}

function productionChain(agent){
  const controller=moduleSource('@deepseek-ai/dsh-api-session-controller/client');
  const server=moduleSource('@deepseek-ai/dsh-api-session-controller');
  const context=vm.createContext({hasApiSessionSubagentOwner:()=>false,apiSessionSubagentOwnershipError:()=>Error('owned'),RemoteError:Error});
  vm.runInContext('globalThis.command={'+region(server,'\tcancel(request) {','\n\tasync resolveAgent(')+'};',context);
  context.command.ctx={agents:{get:id=>id==='fixture-session'?agent:undefined}};
  const calls=[];
  const remote={session:{async cancel(request){calls.push({...request});return {ok:true,value:context.command.cancel(request)};}}};
  vm.runInContext('globalThis.session={'+region(controller,'\t\t\tasync cancel() {','\n\t\t\t/**')+'};',context);
  Object.assign(context.session,{sessionId:'fixture-session',remote,notifier:{markDirty(){}}});
  const binding={session:context.session};
  const scoped={get:name=>name==='conversation'?context.conversation:undefined};
  context.sessions={binding:id=>id==='fixture-session'?binding:undefined,scope:id=>id==='fixture-session'?scoped:undefined,scopeOf:()=> 'fixture-session'};
  const cancel=region(source,'\t\t\tasync cancel() {','\n\t\t\t/**');
  const faces=region(source,'\t\t\tscopedSession(op) {','\n\t\t\t/**\n\t\t\t* Settle');
  vm.runInContext('globalThis.conversation=new (class {'+cancel+'\n'+faces+'})();',context);
  context.conversation.ctx={get:name=>name==='sessions'?context.sessions:undefined};
  vm.runInContext(region(source,'\t\tfunction scopedConversation(sessions, id) {','\n\t\t/**')+'\n'+region(source,'\t\t\tconst stop = (sessionId) => {','\n\t\t\tconst stopShortcut')+'\nglobalThis.stop=stop;',context);
  return {stop:()=>context.stop('fixture-session'),calls};
}

function streamLoop(adapter,{preserveQueued=true}={}){
  const loop=moduleSource('@deepseek-ai/dsh-agent-loop');
  const chunks=[],events=[],statuses=[],firstText=Promise.withResolvers();let toolBodies=0,requestErrors=0;
  const admitted=llm.createUserMessage({source:{kind:'user'},content:[{type:'text',text:'Owned synthetic cancellation only.'}]});
  // Borrow fresh Session facts for the constructor-owned request generation and
  // header checks; append/publication below remains an explicit memory port.
  const freshSession=sessionsSdk.Session.create('fixture-session');
  const context=vm.createContext({...llm,...sessionsSdk,AbortController,Date,Promise,
    renderPrompt:()=>'',renderContextSections:()=>[],joinContextSections:()=>'',assembleContextFor:()=>({}),
    executeToolCalls(){toolBodies++;throw Error('NO_TOOL_BODY_ALLOWED');}});
  vm.runInContext(region(loop,'var AssistantStreamAttempt = class {','\n//#endregion')+'\n'+region(loop,'function abortedCancelCause(signal) {','\n//#endregion')+'\nglobalThis.Agent=ReactLoopAgent;',context);
  const agent=Object.create(context.Agent.prototype);
  Object.assign(agent,{id:'fixture-session',options:{},phase:{kind:'running',turn:0,step:0,abort:new AbortController(),wakeRequested:false},assistantAttemptCounter:0,assistantStreamRevision:0,requestSurfaceGeneration:freshSession.surface.contentGeneration,
    inbox:{hasPending:true,nextStep:[],messages:[admitted],clear(){throw Error('QUEUE_MUST_REMAIN');},splice(_target,_start,_removed,messages){this.hasPending=true;this.messages=messages;}},
    session:{id:freshSession.id,surface:freshSession.surface,requestHeader:()=>freshSession.requestHeader(),append(type,data){events.push({type,data});return {seq:events.length-1};}},
    ctx:{on:()=>()=>{}},systemPrompt:{project:()=>[]},runtimeContext:{project:()=>undefined},
    loopCtx:{llm:adapter,systemPrompt:{refreshContext:()=>({})},agents:{withInitiator:(_agent,run)=>run()}},
    dispatch:{emit(type,data){if(type==='agent/error')stage('conversation-stop-agent-error',{name:data.error?.name,message:data.error?.message,turn:data.turn,step:data.step});if(type==='agent/status')statuses.push(data.status);if(type==='agent/assistant-stream'&&data.frame.type==='chunk'){chunks.push(data.frame.chunk);if(data.frame.chunk.type==='text-delta')firstText.resolve();}},async serial(){},async waterfall(){requestErrors++;return undefined;}},
    // Explicit test ports for prompt assembly and request configuration only.
    async preStep(){if(!preserveQueued||this.phase.turn>1)this.inbox.hasPending=false;return {kind:'enter',messages:this.inbox.messages,assembly:{tools:[]}};},
    async prepareRequest(){return {config:{provider:'owned',model:'owned-local'}};},
    buildRequest(_config,_prepared,_tools,_position,_series,signal){return {provider:'owned',model:'owned-local',messages:this.inbox.messages,signal};}
  });
  assert.equal(agent.requestSurfaceGeneration,agent.session.surface.contentGeneration);assert.equal(agent.session.requestHeader(),undefined);
  for(const method of ['cancel','kick','turn','step','whenIdle','followup','send','wakeDriver'])assert.equal(typeof agent[method],'function');
  stage('conversation-stop-agent-ports-ready',{agentConstructorExecuted:false,freshSessionConstructorExecuted:true,logAndPromptPorts:'EXPLICIT_IN_MEMORY',sameRequestSurfaceGeneration:true});
  agent.activityDone=agent.kick();
  return {agent,events,chunks,statuses,firstText:firstText.promise,facts:()=>({toolBodies,requestErrors})};
}

function heldBodyFetch({cooperative=false,pendingCancel=false,complete=false}={}){
  const encoder=new TextEncoder();let controller,signal,requests=0,cancelCalls=0,cancelSettled=0,completeNext=complete;
  const wire=event=>encoder.encode('event: '+event.type+'\ndata: '+JSON.stringify(event)+'\n\n');
  return {async fetch(url,init){
    assert.equal(new URL(url).host,'owned-cancel.invalid');assert.equal(init.method,'POST');requests++;signal=init.signal;
    const body=new ReadableStream({start(owned){controller=owned;for(const event of [
      {type:'message_start',message:{usage:{input_tokens:1,output_tokens:0}}},
      {type:'content_block_start',index:0,content_block:{type:'text',text:''}},
      {type:'content_block_delta',index:0,delta:{type:'text_delta',text:'owned-prefix'}}])owned.enqueue(wire(event));
      if(completeNext){for(const event of [{type:'content_block_stop',index:0},{type:'message_delta',delta:{stop_reason:'end_turn'},usage:{output_tokens:1}},{type:'message_stop'}])owned.enqueue(wire(event));owned.close();}
      else if(cooperative)signal.addEventListener('abort',()=>owned.error(signal.reason),{once:true});
    },cancel(){cancelCalls++;if(pendingCancel)return new Promise(()=>{});cancelSettled++;}});
    return new Response(body,{status:200,headers:{'content-type':'text/event-stream'}});
  },finishNext(){completeNext=true;},release(){try{controller.close();}catch{}},late(){try{controller.enqueue(wire({type:'content_block_delta',index:0,delta:{type:'text_delta',text:'late-must-not-render'}}));}catch{}},facts:()=>({requests,cancelCalls,cancelSettled,aborted:signal?.aborted})};
}
function adapterFor(sdk,idleMs=1500){const dependencies={
  options:()=>sdk.resolveAdapterOptions({baseURL:'https://owned-cancel.invalid',models:[{id:'owned-local',inputModalities:['text']}],streamIdleTimeoutMs:idleMs}),
  resolveFiles:()=>({}),resolveAuth:async()=>({headers:{}}),resolveUserId:()=> 'owned-synthetic',
  // Same empty-registry fallback as official registerDeepSeekProvider; no
  // plugin enumeration/Host is installed in this adapter-only fixture.
  prepareExtensions:async()=>({fields:{},accept:()=>Promise.resolve()})
};
  const mandatory=['options','resolveAuth','resolveUserId','prepareExtensions'];for(const name of mandatory)assert.equal(typeof dependencies[name],'function',name);
  stage('conversation-stop-adapter-ports-ready',{mandatory,extensions:'OFFICIAL_REGISTER_PROVIDER_EMPTY_REGISTRY_FALLBACK',images:'NOT_USED_FILES_PORT_UNREACHABLE'});
  return new sdk.DeepSeekAdapter(dependencies);
}

test('unchanged control watchdog stalls on upstream abort; candidate preserves abort reason and rejects before any further demand',async()=>{
  for(const [sdk,fixed]of [[rawTimeout,false],[timeout,true]]){
    const controller=new AbortController(),reason={kind:'user'},held=Promise.withResolvers();let demands=0;
    const guard=sdk.idleWatchdog(controller.signal,1500,'OWNED_IDLE');
    const pending=guard.next({next(){demands++;return held.promise;}});controller.abort(reason);
    if(fixed){await assert.rejects(bounded(pending),error=>error===reason);await assert.rejects(guard.next({next(){demands++;return Promise.resolve({done:true});}}),error=>error===reason);assert.equal(demands,1);}
    else{assert.equal(await Promise.race([pending.then(()=>false,()=>false),delay(20).then(()=>true)]),true);held.resolve({done:true});await pending;}
    guard[Symbol.dispose]();
  }
  const guard=timeout.idleWatchdog(undefined,20,'OWNED_IDLE');
  await assert.rejects(guard.next({next:()=>new Promise(()=>{})}),error=>timeout.timeoutOf({reason:error},'OWNED_IDLE')===error);guard[Symbol.dispose]();
});

for(const mode of ['raw-cooperative','raw-stalled','candidate-stalled','candidate-cancel-pending'])test('real composer cancel chain and active DeepSeek body: '+mode.replace(/^raw/,namedProof?'prior-managed-control':'raw'),async()=>{
  const original=globalThis.fetch,owned=heldBodyFetch({cooperative:mode==='raw-cooperative',pendingCancel:mode==='candidate-cancel-pending'});
  globalThis.fetch=owned.fetch;
  let f;
  try{
    f=streamLoop(adapterFor(mode.startsWith('raw')?rawDeepSeek:deepSeek));await bounded(Promise.race([f.firstText,f.agent.activityDone.then(()=>{throw Error('LOOP_SETTLED_BEFORE_TEXT: '+JSON.stringify(f.events.filter(row=>row.type==='turn/end').at(-1)?.data.reason));})]),1000);
    const chain=productionChain(f.agent),ui=render({draft:'queued draft remains',onStop:chain.stop});
    assert.equal(ui.stopButtons.length,1);ui.stopButtons[0].props.onClick();
    assert.equal(chain.calls.length,1);assert.equal(chain.calls[0].sessionId,'fixture-session');assert.equal(f.agent.phase.abort.signal.reason.kind,'user');
    if(mode==='raw-stalled'){
      assert.equal(await Promise.race([f.agent.activityDone.then(()=>false),delay(25).then(()=>true)]),true);
      assert.equal(f.agent.status,'running');assert.equal(owned.facts().cancelCalls,0);owned.release();
    }
    await bounded(f.agent.activityDone,500);assert.equal(f.agent.status,'idle');assert.equal(f.agent.inbox.hasPending,true);
    assert.equal(f.events.filter(row=>row.type==='turn/end').at(-1).data.reason.kind,'aborted');
    assert.equal(f.events.filter(row=>row.type==='turn/end').at(-1).data.reason.reason.kind,'user');
    const before=f.chunks.length;owned.late();await delay(15);assert.equal(f.chunks.length,before);
    assert.equal(owned.facts().requests,1);assert.equal(owned.facts().aborted,true);assert.deepEqual(f.facts(),{toolBodies:0,requestErrors:0});
    if(mode.startsWith('candidate')){assert.equal(owned.facts().cancelCalls,1);assert.equal(owned.facts().cancelSettled,mode==='candidate-cancel-pending'?0:1);}
    if(mode==='candidate-stalled'){
      owned.finishNext();f.agent.followup(llm.createUserMessage({source:{kind:'user'},content:[{type:'text',text:'Owned explicit continuation.'}]}));
      await bounded(f.agent.whenIdle(),1000);assert.equal(f.agent.status,'idle');assert.equal(owned.facts().requests,2);
      assert.deepEqual(f.events.filter(row=>row.type==='turn/end').map(row=>row.data.reason.kind),['aborted','completed']);
      assert.equal(f.agent.session.id,'fixture-session');assert.deepEqual(f.facts(),{toolBodies:0,requestErrors:0});
    }
  }finally{owned.release();if(f)await bounded(f.agent.activityDone,1800);globalThis.fetch=original;}
});

test('candidate still completes an ordinary response without cancellation',async()=>{
  const original=globalThis.fetch,owned=heldBodyFetch({complete:true});globalThis.fetch=owned.fetch;
  try{const f=streamLoop(adapterFor(deepSeek),{preserveQueued:false});await bounded(f.agent.activityDone,1000);
    assert.equal(f.agent.status,'idle');assert.equal(f.events.filter(row=>row.type==='turn/end').at(-1).data.reason.kind,'completed');
    assert.equal(f.chunks.filter(chunk=>chunk.type==='text-delta').map(chunk=>chunk.text).join(''),'owned-prefix');
    assert.equal(owned.facts().requests,1);assert.equal(owned.facts().cancelCalls,0);assert.deepEqual(f.facts(),{toolBodies:0,requestErrors:0});
  }finally{owned.release();globalThis.fetch=original;}
});
