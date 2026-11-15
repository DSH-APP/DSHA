// Execute the locked rc2 Connection, WebSocket mux and snapshot consumer. A
// background-dead socket stays OPEN; foreground resumes subscriptions only.
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID,webcrypto} from 'node:crypto';
import {testRuntime} from './test-runtime-fixture.mjs';

const runtime = testRuntime('raw');
const connectionSource = readFileSync(runtime+'/node_modules/@deepseek-ai/dsh-client-connection/lib/client.browser.js','utf8');
const gatewaySource = readFileSync(runtime+'/node_modules/@deepseek-ai/dsh-api-gateway/lib/client.browser.js','utf8');
const integrationSource = readFileSync('app/src/main/assets/app-integration/client.js','utf8');
const wait = ms => new Promise(resolve => setTimeout(resolve,ms));
async function until(check) {
  const deadline = Date.now()+2500;
  while (!check()) { if (Date.now()>deadline) throw Error('stream fixture deadline'); await wait(5); }
}

function fixture({holdRpc=false,probeUnavailable=false,generatedProbe=false}={}) {
  const window = new EventTarget(), document = new EventTarget(), modules = new Map(), disposers = [];
  Object.assign(document,{hidden:false,baseURI:'http://127.0.0.1:3080/'});
  window.navigator = {onLine:true};
  window.top = window;
  let hostText='before-background', rpcCalls=0;
  let sessionBaseline={type:'baseline',sessions:['session-before']};
  let workspaceBaseline={type:'baseline',value:{workspaces:['workspace-before']}};
  const pendingRpc=[];
  const sockets=[];
  class Socket extends EventTarget {
    static OPEN=1;
    constructor(url) {
      super();this.url=url;this.readyState=0;this.dead=false;this.sent=[];sockets.push(this);
      queueMicrotask(()=>{if(this.readyState!==0)return;this.readyState=1;this.dispatchEvent(new Event('open'));});
    }
    send(text) {
      const frame=JSON.parse(text);this.sent.push(frame);
      if(this.dead||frame.type!=='open')return;
      const value=frame.endpoint==='$events'?{type:'ready',clientId:randomUUID(),host:{home:'/root'}}:
        frame.endpoint==='session.control'?sessionBaseline:
        frame.endpoint==='workspace.follow'?workspaceBaseline:{type:'snapshot',text:hostText};
      queueMicrotask(()=>{if(this.readyState!==1)return;const event=new Event('message');event.data=JSON.stringify({type:'item',streamId:frame.streamId,value});this.dispatchEvent(event);});
    }
    close() {if(this.readyState===3)return;this.readyState=3;queueMicrotask(()=>this.dispatchEvent(new Event('close')));}
  }
  class Service {constructor(ctx,name){this.ctx=ctx;ctx.provide(name,this);}}
  window.__ModuleLoader__={load:definition=>modules.set(definition.id,definition.factory(name=>{
    if(name==='@deepseek-ai/cordis')return {Service};throw Error('unexpected static dependency '+name);
  }))};
  const sandbox={window,document,WebSocket:Socket,Event,URL,AbortController,AbortSignal,Promise,Date,
    setTimeout,clearTimeout,setInterval,clearInterval,queueMicrotask,crypto:webcrypto,
    fetch:(input,init)=>{
      rpcCalls++;
      if(!holdRpc)throw Error('Foreground recovery must not replay unary RPC');
      const wire=JSON.parse(init.body);
      return new Promise(resolve=>pendingRpc.push(()=>resolve({ok:true,headers:{get:()=> 'application/json'},
        json:async()=>({type:'server-response',rpcId:wire.rpcId,result:{ok:true,value:{accepted:true}}})})));
    },
    console:{warn(){},error(...args){throw Error(args.join(' '));}}};
  vm.createContext(sandbox);vm.runInContext(connectionSource,sandbox);vm.runInContext(gatewaySource,sandbox);vm.runInContext(integrationSource,sandbox);
  const values=new Map();
  const ctx={get:name=>values.get(name),provide:(name,value)=>values.set(name,value),
    effect:factory=>{const dispose=factory();disposers.push(dispose);return dispose;},emit(){}};
  modules.get('@deepseek-ai/dsh-client-connection').installConnection(ctx,{location:{hostname:'127.0.0.1'}});
  modules.get('@deepseek-ai/dsh-api-gateway').apply(ctx);
  const connection=ctx.get('connection'), remote=ctx.get('remote'), observations=[], failures=[];
  if(generatedProbe)remote.session={control:signal=>remote.openRemoteStream('session.control',{args:{}},signal)};
  const {RemoteSnapshotStream}=modules.get('@deepseek-ai/dsh-api-gateway');
  const stream=remote.$stream({name:'session-output',open:signal=>remote.openRemoteStream('fixture.session.output',{},signal),
    ended:()=>Error('unexpected session stream end')});
  const consumer=new RemoteSnapshotStream(stream,{name:'session-output',isSnapshot:value=>value.type==='snapshot',
    replace:value=>observations.push(value.text),update(){},failed:error=>failures.push(error)});
  consumer.start();
  const stop=modules.get('dsh-app-integration').installStreamResume({
    connection,
    get:name=>!probeUnavailable&&name==='remote'?remote:undefined
  },40);
  return {document,window,sandbox,sockets,connection,remote,modules,observations,failures,hostText:value=>hostText=value,rpcCalls:()=>rpcCalls,
    baselines:(sessions,workspaces)=>{sessionBaseline={type:'baseline',sessions};workspaceBaseline={type:'baseline',value:{workspaces}};},
    acknowledge:()=>pendingRpc.shift()?.(),
    stop,async close(){stop();await consumer.dispose();for(const dispose of disposers.reverse())await dispose?.();}};
}

test('dead-but-OPEN carrier waits for the official close instead of a probe-timeout reset',async()=>{
  const f=fixture();
  try {
    await until(()=>f.observations.length===1);
    const original=f.sockets[0], generation=f.connection.generation.getSnapshot().id;
    original.dead=true;f.document.hidden=true;f.document.dispatchEvent(new Event('visibilitychange'));
    f.hostText('finished-in-background');
    await wait(80);assert.equal(original.readyState,1);assert.equal(f.sockets.length,1);
    assert.deepEqual(f.observations,['before-background']);
    f.document.hidden=false;f.document.dispatchEvent(new Event('visibilitychange'));
    f.window.dispatchEvent(new Event('dsha-browser-resume'));
    await wait(200);
    assert.equal(original.readyState,1);
    assert.equal(f.sockets.length,1,'a connected Connection must not be reset by a slow probe');
    assert.equal(f.connection.generation.getSnapshot().id,generation);
    assert.equal(f.window.__DSHA_RESUME_DIAG__.probe,'timeout-kept');
    assert.equal(f.window.__DSHA_RESUME_DIAG__.reset,false);
    assert.deepEqual(f.observations,['before-background']);
    // The Host heartbeat terminates a carrier that stops answering pings; the
    // official close path reconnects and rebaselines without a prompt replay.
    original.close();
    await until(()=>f.observations.includes('finished-in-background'));
    assert.equal(f.sockets.length,2);
    assert.ok(f.connection.generation.getSnapshot().id>generation);
    assert.equal(f.rpcCalls(),0);assert.deepEqual(f.failures,[]);
  } finally {await f.close();}
});

test('same-document in-app round trip keeps a live carrier and its rendered output',async()=>{
  const f=fixture();
  try {
    await until(()=>f.observations.length===1);
    const original=f.sockets[0], generation=f.connection.generation.getSnapshot().id;
    // Web -> native page -> Web: the kept document receives the native resume
    // signal while visible. The carrier answers the read-only probe, so the whole
    // Remote baseline must not be rebuilt (K5 scenario 1 long streaming page).
    f.window.dispatchEvent(new Event('dsha-browser-resume'));
    await wait(200);
    assert.equal(original.readyState,1);
    assert.equal(f.sockets.length,1,'live carrier must not be replaced');
    assert.equal(f.connection.generation.getSnapshot().id,generation);
    assert.equal(f.connection.state.getSnapshot(),'connected');
    assert.deepEqual(f.observations,['before-background']);
    assert.equal(f.rpcCalls(),0);assert.deepEqual(f.failures,[]);
  } finally {await f.close();}
});

test('generated Session control probe answers without resetting a live carrier',async()=>{
  const f=fixture({generatedProbe:true});
  try {
    await until(()=>f.observations.length===1);
    const generation=f.connection.generation.getSnapshot().id;
    f.window.dispatchEvent(new Event('dsha-browser-resume'));
    await wait(200);
    assert.equal(f.sockets.length,1,'generated control probe must not reset');
    assert.equal(f.connection.generation.getSnapshot().id,generation);
    assert.deepEqual(f.observations,['before-background']);
    assert.equal(f.rpcCalls(),0);assert.deepEqual(f.failures,[]);
  } finally {await f.close();}
});

test('visibility hide/show on a live carrier keeps the same rendered baseline',async()=>{
  const f=fixture();
  try {
    await until(()=>f.observations.length===1);
    const original=f.sockets[0], generation=f.connection.generation.getSnapshot().id;
    f.document.hidden=true;f.document.dispatchEvent(new Event('visibilitychange'));
    f.hostText('finished-while-still-connected');
    f.document.hidden=false;f.document.dispatchEvent(new Event('visibilitychange'));
    await wait(200);
    assert.equal(original.readyState,1);
    assert.equal(f.sockets.length,1,'hidden/visible on a live carrier must not reset');
    assert.equal(f.connection.generation.getSnapshot().id,generation);
    assert.deepEqual(f.observations,['before-background']);
    assert.equal(f.rpcCalls(),0);assert.deepEqual(f.failures,[]);
  } finally {await f.close();}
});

test('missing probe capability leaves a connected carrier to the official close',async()=>{
  const f=fixture({probeUnavailable:true});
  try {
    await until(()=>f.observations.length===1);
    const original=f.sockets[0], generation=f.connection.generation.getSnapshot().id;
    f.window.dispatchEvent(new Event('dsha-browser-resume'));
    await wait(200);
    assert.equal(f.sockets.length,1,'unavailable probe must not reset a connected Connection');
    assert.equal(f.connection.generation.getSnapshot().id,generation);
    assert.equal(f.window.__DSHA_RESUME_DIAG__.probe,'unavailable-kept');
    original.close();
    await until(()=>f.sockets.length===2);
    assert.ok(f.connection.generation.getSnapshot().id>generation);
    assert.equal(f.rpcCalls(),0);
  } finally {await f.close();}
});

test('foreground manual reset exits rc2 offline wait when Android missed the online callback',async()=>{
  const f=fixture();
  try {
    await until(()=>f.observations.length===1);
    f.document.hidden=true;f.document.dispatchEvent(new Event('visibilitychange'));
    f.window.navigator.onLine=false;f.window.dispatchEvent(new Event('offline'));
    await until(()=>f.connection.state.getSnapshot()==='disconnected');
    f.hostText('output-while-navigator-remains-offline');
    f.document.hidden=false;
    // No online or visibility callback: the native resume signal is sufficient.
    f.window.dispatchEvent(new Event('dsha-browser-resume'));
    await until(()=>f.observations.includes('output-while-navigator-remains-offline'));
    assert.equal(f.window.navigator.onLine,false);assert.equal(f.connection.state.getSnapshot(),'connected');
    assert.equal(f.rpcCalls(),0);assert.deepEqual(f.failures,[]);
  } finally {await f.close();}
});

test('native resume received while hidden remains pending until the later visible callback',async()=>{
  const f=fixture();
  try {
    await until(()=>f.observations.length===1);
    f.sockets[0].dead=true;f.hostText('output-after-late-visible-callback');
    // Chromium lost the hidden callback; Activity resumes before it publishes
    // visible. The native signal must retain intent without touching the socket.
    f.document.hidden=true;f.window.dispatchEvent(new Event('dsha-browser-resume'));
    await wait(70);assert.equal(f.sockets.length,1);
    f.document.hidden=false;f.document.dispatchEvent(new Event('visibilitychange'));
    await wait(200);
    assert.equal(f.sockets.length,1,'visible probe timeout must not reset a connected carrier');
    assert.equal(f.window.__DSHA_RESUME_DIAG__.probe,'timeout-kept');
    f.sockets[0].close();
    await until(()=>f.observations.includes('output-after-late-visible-callback'));
    assert.equal(f.sockets.length,2);assert.equal(f.rpcCalls(),0);assert.deepEqual(f.failures,[]);
  } finally {await f.close();}
});

test('background/disposal cannot trigger a recovery or resend a user action',async()=>{
  const f=fixture();
  try {
    await until(()=>f.observations.length===1);
    const count=f.sockets.length;
    f.document.hidden=true;f.document.dispatchEvent(new Event('visibilitychange'));
    f.window.dispatchEvent(new Event('dsha-browser-resume'));await wait(70);
    assert.equal(f.sockets.length,count);
    f.document.hidden=false;f.window.dispatchEvent(new Event('dsha-browser-resume'));f.stop();await wait(70);
    assert.equal(f.sockets.length,count);assert.equal(f.rpcCalls(),0);
  } finally {await f.close();}
});

test('Gecko native-port foreground message reaches the actual page Connection reset',async()=>{
  const f=fixture();
  try {
    await until(()=>f.observations.length===1);
    let receive;
    const sent=[];
    f.sandbox.browser={runtime:{connectNative:name=>{
      assert.equal(name,'dsha');return {onMessage:{addListener:fn=>receive=fn},postMessage:value=>sent.push(value)};
    }}};
    f.document.head={appendChild:script=>vm.runInContext(script.textContent,f.sandbox)};
    f.document.createElement=()=>({textContent:'',remove(){}});
    vm.runInContext(readFileSync('app/src/main/assets/web-integration/startup-relay.js','utf8'),f.sandbox);
    f.sockets[0].dead=true;f.hostText('Gecko resumed output');
    receive({type:'unrelated'});await wait(60);assert.equal(f.sockets.length,1);
    receive({type:'browser-resume'});
    await wait(200);
    assert.equal(f.sockets.length,1,'probe timeout must not reset a connected carrier');
    assert.equal(f.window.__DSHA_RESUME_DIAG__.probe,'timeout-kept');
    f.sockets[0].close();
    await until(()=>f.observations.includes('Gecko resumed output'));
    assert.equal(f.sockets.length,2);assert.equal(f.rpcCalls(),0);assert.deepEqual(sent,[]);
    const extension=JSON.parse(readFileSync('app/src/main/assets/web-integration/manifest.json','utf8'));
    assert.ok(extension.content_scripts.some(script=>script.js.includes('startup-relay.js') && !script.all_frames));
  } finally {await f.close();}
});

test('stream recovery preserves one pending unary admission and its later acknowledgement',async()=>{
  const f=fixture({holdRpc:true});
  try {
    await until(()=>f.observations.length===1);
    const admission=new AbortController();
    const pending=f.connection.rpc.call('/api','session.prompt',{args:{sessionId:'fixture',requestId:'synthetic-pending-admission'}},admission.signal);
    await until(()=>f.rpcCalls()===1);
    f.sockets[0].dead=true;f.hostText('baseline-while-admission-is-pending');
    f.window.dispatchEvent(new Event('dsha-browser-resume'));
    await wait(200);
    assert.equal(f.sockets.length,1,'probe timeout must not reset a connected carrier');
    assert.equal(f.window.__DSHA_RESUME_DIAG__.probe,'timeout-kept');
    f.sockets[0].close();
    await until(()=>f.observations.includes('baseline-while-admission-is-pending'));
    assert.equal(f.rpcCalls(),1);assert.equal(admission.signal.aborted,false);
    f.acknowledge();const result=await pending;
    assert.equal(result.ok,true);assert.equal(result.value.accepted,true);
    assert.equal(f.rpcCalls(),1);assert.deepEqual(f.failures,[]);
  } finally {await f.close();}
});

// The checked-in foreground mechanism is also used by the real rc3 Session
// control and Workspace follow stream factories. This is a carrier fixture, not
// an Android or real-server restart test.
test('rc3 session and workspace streams rebaseline after foreground recovery and a socket close',async()=>{
  const f=fixture();
  let session,workspace;
  try {
    const sources=[
      ['session','dsh-api-session-controller','createSessionControlStream'],
      ['workspace','dsh-api-workspace-controller','createWorkspaceStateStream'],
    ];
    const gateway=f.modules.get('@deepseek-ai/dsh-api-gateway');
    f.sandbox._deepseek_ai_dsh_api_gateway_client=gateway;
    for (const [name,pkg,fn] of sources) {
      const source=readFileSync(`${runtime}/node_modules/@deepseek-ai/${pkg}/lib/client.js`,'utf8');
      const start=source.indexOf(`function ${fn}(`), end=source.indexOf('\n\t\t}',start);
      assert.ok(start>=0 && end>start,`${fn} source`);
      vm.runInContext(`globalThis.${name}Factory=${source.slice(start,end+4)};`,f.sandbox);
    }
    const baseline={session:null,workspace:null}, failed=[];
    const remotes={
      $stream:f.remote.$stream.bind(f.remote),
      session:{control:signal=>f.remote.openRemoteStream('session.control',{},signal)},
      workspace:{follow:signal=>f.remote.openRemoteStream('workspace.follow',{},signal)},
    };
    session=f.sandbox.sessionFactory(remotes,{accept:frame=>baseline.session=frame.sessions,failed:error=>failed.push(error)});
    workspace=f.sandbox.workspaceFactory(remotes,{accept:{replaceBaseline:value=>baseline.workspace=value.workspaces},failed:error=>failed.push(error)});
    session.start();workspace.start();
    await until(()=>baseline.session?.[0]==='session-before' && baseline.workspace?.[0]==='workspace-before');
    f.sockets[0].dead=true;
    f.document.hidden=true;f.document.dispatchEvent(new Event('visibilitychange'));
    f.document.hidden=false;f.document.dispatchEvent(new Event('visibilitychange'));
    await wait(200);
    assert.equal(f.sockets.length,1,'probe timeout must not reset a connected carrier');
    assert.equal(f.window.__DSHA_RESUME_DIAG__.probe,'timeout-kept');
    f.baselines(['session-resumed'],['workspace-resumed']);
    f.sockets[0].close();
    await until(()=>baseline.session?.[0]==='session-resumed' && baseline.workspace?.[0]==='workspace-resumed');
    f.baselines(['session-after'],['workspace-after']);
    f.sockets.at(-1).close();
    await until(()=>baseline.session?.[0]==='session-after' && baseline.workspace?.[0]==='workspace-after');
    assert.equal(f.rpcCalls(),0,'stream recovery must not replay a user action');
    assert.deepEqual(failed,[]);
  } finally {await session?.dispose();await workspace?.dispose();await f.close();}
});
