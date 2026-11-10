import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
import path from 'node:path';
import {testRuntime} from './test-runtime-fixture.mjs';
import {TaskReporter} from '../app/src/main/assets/builtin-plugins/dsh-task-notifier/lib/index.js';
const session={id:'root-session',surface:{contentGeneration:1}}; let turn=7, open=42, cancel=0, idle=0, claim;
const agent={session,status:'running',cancel(cause){assert.deepEqual(cause,{kind:'user'});cancel++},whenIdle(){idle++;return Promise.resolve()}};
const ctx={webServer:{port:41821},agents:{get:id=>id===session.id?agent:undefined,roots:()=>[agent]},sessionProjections:{stateOf:()=>({lastTurn:turn,openTurnStartSeq:open})}};
const traffic=[];
const webRegistration={DSH_TASK_GENERATION:'3',DSH_TASK_INSTANCE:'actual-runtime',DSH_TASK_TOKEN:'a'.repeat(64)};
const reporter=new TaskReporter(ctx,async(operation,payload)=>{
  traffic.push({operation,payload});
  if(operation==='context') {assert.equal(payload.port,41821);assert.equal(payload.nonce,webRegistration.DSH_TASK_TOKEN);assert.equal(payload.generation,3);assert.equal(payload.instance,'actual-runtime');return {generation:3,instance:'actual-runtime',port:41821};}
  if(operation==='claim') {const c=claim;claim=undefined;return c??{}};
  if(operation==='ack'&&payload.action==='lost-ack') throw Error('ack lost');
  return true;
},Date.now,webRegistration);
for(let i=0;i<600;i++) await reporter.tick();
assert.equal(traffic.length,0,'a reporter without a live task or pending fact must not reserve a native context or poll stop controls');
reporter.event(session,{type:'turn/end',data:{turn:6,reason:{kind:'completed'}}});
reporter.event(session,{type:'irrelevant/event',data:{turn:7}});
assert.equal(reporter.rows.size,0,'historical end and unrelated events cannot invent live tasks');
reporter.event(session,{type:'turn/start',data:{turn:7}});
for(let i=0;i<200;i++) reporter.event(session,{type:'assistant/chunk',data:{chunk:{type:'text-delta',text:'private text'}}});
assert.equal(traffic.length,0,'chunks must coalesce until the 1 Hz tick');
await reporter.tick();
assert.equal(traffic.filter(t=>t.operation==='event').length,1);
assert.equal(traffic.find(t=>t.operation==='event').payload.phase,'reply');
assert.ok(!JSON.stringify(traffic).includes('private text'),'no dialogue text crosses the task progress bridge');
const owner=reporter.rows.get(session.id).owner;
claim={generation:3,instance:'actual-runtime',session:session.id,turn:7,owner,action:'lost-ack'};
await reporter.tick();assert.equal(cancel,1);assert.equal(idle,1);
await reporter.control({generation:3,instance:'actual-runtime',session:session.id,turn:7,owner,action:'lost-ack'});
assert.equal(cancel,1,'unknown acknowledgement cannot replay cancellation');
turn=8;
await reporter.control({generation:3,instance:'actual-runtime',session:session.id,turn:7,action:'stale-turn'});
assert.equal(cancel,1,'old notification cannot cancel a newer turn');
await reporter.control({generation:3,instance:'actual-runtime',session:session.id,turn:7,owner,action:'lost-ack'});assert.equal(cancel,1);
reporter.event(session,{type:'turn/start',data:{turn:8}});
await reporter.tick();
const count=traffic.filter(t=>t.operation==='event').length;
open=null;agent.status='idle';reporter.event(session,{type:'turn/end',data:{turn:8,reason:{kind:'max-tokens'}}});
await reporter.flush();
const records=traffic.filter(t=>t.operation==='event');assert.equal(records.length,count+1);
assert.equal(records.at(-1).payload.phase,'ended');assert.equal(records.at(-1).payload.reason,'max-tokens');
const endedTraffic=traffic.length;
for(let i=0;i<600;i++) await reporter.tick();
assert.equal(traffic.length,endedTraffic,'a delivered terminal fact stops claim polling while retaining task history');
await reporter.control({generation:2,instance:'old',session:session.id,turn:8,action:'old-generation'});assert.equal(cancel,1);
let replacementCancels=0;
const replacement={...agent,status:'running',cancel(){replacementCancels++}};
ctx.agents.get=id=>id===session.id?replacement:undefined;ctx.agents.roots=()=>[replacement];
session.surface.contentGeneration=2;turn=1;open=99;
reporter.event(session,{type:'turn/start',data:{turn:1}});await reporter.tick();
const replacementOwner=reporter.rows.get(session.id).owner;assert.notEqual(replacementOwner,owner);
await reporter.control({generation:3,instance:'actual-runtime',session:session.id,turn:1,owner,action:'old-owner'});assert.equal(replacementCancels,0);
await reporter.control({generation:3,instance:'actual-runtime',session:session.id,turn:1,owner:replacementOwner,action:'new-owner'});assert.equal(replacementCancels,1);
reporter.dispose();await reporter.tick();assert.equal(cancel,1);
// An interrupted terminal transfer is still retried even when no live row remains.
// Idle optimization must not suppress the actual end fact or fabricate completion.
const terminalSession={id:'terminal-retry',surface:{contentGeneration:1}};
const terminalAgent={session:terminalSession,status:'running'};
let terminalOpen=1,terminalTurn=1,failTerminal=true;
const terminalTraffic=[];
const terminalCtx={webServer:{port:41821},agents:{get:()=>terminalAgent,roots:()=>[terminalAgent]},
  sessionProjections:{stateOf:()=>({lastTurn:terminalTurn,openTurnStartSeq:terminalOpen})}};
const terminalReporter=new TaskReporter(terminalCtx,async(operation,payload)=>{
  terminalTraffic.push({operation,payload});
  if(operation==='context') return {generation:3,instance:'actual-runtime',port:41821};
  if(operation==='claim') return {};
  if(operation==='event'&&payload.phase==='ended'&&failTerminal) throw Error('synthetic terminal transport failure');
  return true;
},Date.now,webRegistration);
terminalReporter.event(terminalSession,{type:'turn/start',data:{turn:1}});
await terminalReporter.tick();
terminalAgent.status='idle';terminalOpen=null;
terminalReporter.event(terminalSession,{type:'turn/end',data:{turn:1,reason:{kind:'aborted'}}});
await terminalReporter.flush();assert.equal(terminalReporter.pending.size,1);
failTerminal=false;
const terminalRetryStart=terminalTraffic.length;
await terminalReporter.tick();
assert.deepEqual(terminalTraffic.slice(terminalRetryStart).map(row=>row.operation),['event']);
assert.equal(terminalTraffic.at(-1).payload.phase,'ended');
assert.equal(terminalTraffic.at(-1).payload.reason,'aborted');
assert.equal(terminalReporter.pending.size,0);
const terminalIdleStart=terminalTraffic.length;
for(let i=0;i<600;i++) await terminalReporter.tick();
assert.equal(terminalTraffic.length,terminalIdleStart,'successfully delivered aborted task has no idle bridge traffic');
terminalAgent.status='running';terminalOpen=2;terminalTurn=2;
terminalReporter.event(terminalSession,{type:'turn/start',data:{turn:2}});
await terminalReporter.tick();
assert.equal(terminalTraffic.at(-1).operation,'claim','a real task after an idle period restores active stop polling');
terminalReporter.dispose();
const disposedTraffic=terminalTraffic.length;
await terminalReporter.tick();assert.equal(terminalTraffic.length,disposedTraffic);
// Distinct Session and Agent instances deliberately reuse every numeric/text identity.
// Old event delivery must neither end the new owner nor alter its progress/stop action.
const previousSession={id:'reused-session',surface:{contentGeneration:0}};
const nextSession={id:'reused-session',surface:{contentGeneration:0}};
let currentSession=previousSession, actualTurn=1, actualStart=1, nextCancels=0;
const previousAgent={session:previousSession,status:'running',cancel(){throw Error('old Agent must not be canceled')},whenIdle(){return Promise.resolve()}};
const nextAgent={session:nextSession,status:'running',cancel(cause){assert.deepEqual(cause,{kind:'user'});nextCancels++},whenIdle(){return Promise.resolve()}};
let currentAgent=previousAgent;
const replacementTraffic=[];
const replacedCtx={webServer:{port:41821},agents:{get:()=>currentAgent,roots:()=>[currentAgent]},
  sessionProjections:{stateOf:s=>({lastTurn:actualTurn,openTurnStartSeq:s===currentSession?actualStart:1})}};
const replacementReporter=new TaskReporter(replacedCtx,async(operation,payload)=>{
  replacementTraffic.push({operation,payload});
  if(operation==='context') return {generation:3,instance:'actual-runtime',port:41821};
  if(operation==='claim') return {};
  return true;
},Date.now,webRegistration);
replacementReporter.event(previousSession,{type:'turn/start',data:{turn:1}});
await replacementReporter.tick();
const oldDistinctOwner=replacementReporter.rows.get(previousSession.id).owner;
currentSession=nextSession;currentAgent=nextAgent;actualStart=2;
replacementReporter.event(nextSession,{type:'turn/start',data:{turn:1}});
replacementReporter.event(nextSession,{type:'assistant/chunk',data:{turn:1}});
await replacementReporter.tick();
const distinctOwner=replacementReporter.rows.get(nextSession.id).owner;
assert.notEqual(distinctOwner,oldDistinctOwner);
const beforeLateEvents=replacementTraffic.filter(t=>t.operation==='event').length;
for(const late of [
  {type:'turn/end',data:{turn:1,reason:{kind:'completed'}}},
  {type:'tool/call',data:{turn:1,name:'ask_user'}},
  {type:'assistant/chunk',data:{turn:1,chunk:{type:'text-delta',text:'old private text'}}},
  {type:'turn/start',data:{turn:1}}
]) replacementReporter.event(previousSession,late);
// An old stream producer cannot gain ownership by being handed the new Session object.
replacementReporter.event(nextSession,{type:'assistant/chunk',data:{turn:1}},previousAgent);
await replacementReporter.flush();
const liveRow=replacementReporter.rows.get(nextSession.id);
assert.equal(liveRow.owner,distinctOwner);assert.equal(liveRow.active,true);assert.equal(liveRow.phase,'reply');
assert.equal(replacementTraffic.filter(t=>t.operation==='event').length,beforeLateEvents,'delayed old end/progress/tool events must not publish facts for the current owner');
await replacementReporter.control({generation:3,instance:'actual-runtime',session:nextSession.id,turn:1,owner:oldDistinctOwner,action:'old-distinct-owner'});
assert.equal(nextCancels,0);
await replacementReporter.control({generation:3,instance:'actual-runtime',session:nextSession.id,turn:1,owner:distinctOwner,action:'current-distinct-owner'});
assert.equal(nextCancels,1,'stale end must not hide or invalidate the current task stop');
replacementReporter.event(nextSession,{type:'tool/call',data:{turn:1,name:'ask_user'}});
await replacementReporter.tick();
assert.equal(replacementTraffic.filter(t=>t.operation==='event').at(-1).payload.owner,distinctOwner);
assert.equal(replacementTraffic.filter(t=>t.operation==='event').at(-1).payload.phase,'waiting');
nextAgent.status='idle';actualStart=null;
replacementReporter.event(nextSession,{type:'turn/end',data:{turn:1,reason:{kind:'completed'}}});
await replacementReporter.flush();
assert.equal(replacementTraffic.filter(t=>t.operation==='event').at(-1).payload.phase,'ended');
assert.equal(replacementTraffic.filter(t=>t.operation==='event').at(-1).payload.owner,distinctOwner);
replacementReporter.dispose();
let withoutLeaseCalls=0;
const cliReporter=new TaskReporter(replacedCtx,async()=>{withoutLeaseCalls++;return {}},Date.now,{});
nextAgent.status='running';actualStart=3;
cliReporter.event(nextSession,{type:'turn/start',data:{turn:1}});
await cliReporter.tick();assert.equal(withoutLeaseCalls,0,'ordinary CLI without a formal Web registration lease must not reserve a reporter');
cliReporter.dispose();
const mismatchedContextReporter=new TaskReporter(replacedCtx,async operation=>operation==='context'
  ? {generation:4,instance:'replacement-runtime',port:41821}:assert.fail('mismatched native context must not publish or claim'),Date.now,webRegistration);
mismatchedContextReporter.event(nextSession,{type:'turn/start',data:{turn:1}});
await mismatchedContextReporter.tick();assert.equal(mismatchedContextReporter.identity,undefined);
mismatchedContextReporter.dispose();
// Import the actual canonical rc2 scrubber used by bash/MCP/PTY launchers.
const raw=testRuntime('raw');
const subprocessSource=path.join(raw,'node_modules/@deepseek-ai/dsh-subprocess/lib/index.js');
assert.equal(JSON.parse(readFileSync(path.join(raw,'node_modules/@deepseek-ai/dsh-subprocess/package.json'),'utf8')).version,
  JSON.parse(readFileSync('tools/dsh-runtime/package.json','utf8')).dependencies['@deepseek-ai/dsh']);
const {scrubbedParentEnv}=await import(pathToFileURL(subprocessSource).href);
for(const [key,value]of Object.entries(webRegistration)) process.env[key]=value;
const filtered=scrubbedParentEnv();
for(const key of Object.keys(webRegistration)) assert.ok(!Object.hasOwn(filtered,key),'canonical rc2 scrubber must remove '+key+' even before notifier initialization');
const hmrTraffic=[];
const hmrTransport=async(operation,payload)=>{
  hmrTraffic.push({operation,payload});
  if(operation==='context') {assert.equal(payload.nonce,webRegistration.DSH_TASK_TOKEN);return {generation:3,instance:'actual-runtime',port:41821};}
  return operation==='claim'?{}:true;
};
const firstHmr=new TaskReporter(replacedCtx,hmrTransport);
let retiredProducerCleanup=0;firstHmr.cleanup=()=>retiredProducerCleanup++;
for(const key of Object.keys(webRegistration)) assert.ok(!Object.hasOwn(process.env,key),'notifier must consume and delete the ambient launch field '+key);
firstHmr.event(nextSession,{type:'turn/start',data:{turn:1}});await firstHmr.tick();
const firstHmrClient=firstHmr.client;
const firstHmrSequence=hmrTraffic.filter(t=>t.operation==='event').at(-1).payload.sequence;
const moduleUrl=pathToFileURL(path.resolve('app/src/main/assets/builtin-plugins/dsh-task-notifier/lib/index.js')).href;
const ReloadedReporter=(await import(moduleUrl+'?hmr-regression=1')).TaskReporter;
const secondHmr=new ReloadedReporter(replacedCtx,hmrTransport);
assert.equal(firstHmr.disposed,true,'new legitimate module instance must retire its previous producer');
assert.equal(retiredProducerCleanup,1,'replacement must release the previous producer timer');
assert.equal(secondHmr.client,firstHmrClient,'HMR preserves the registered client binding');
secondHmr.event(nextSession,{type:'turn/start',data:{turn:1}});await secondHmr.tick();
assert.ok(hmrTraffic.filter(t=>t.operation==='event').at(-1).payload.sequence>firstHmrSequence,'HMR preserves the native session sequence fence');
assert.equal(hmrTraffic.filter(t=>t.operation==='context').length,2,'HMR registers again from the in-process lease after ambient env removal');
// A late old disposer cannot revoke the current producer or force a process restart.
firstHmr.dispose();await secondHmr.tick();assert.equal(secondHmr.disposed,false);
assert.equal(retiredProducerCleanup,1,'late old dispose cannot repeat cleanup or affect the new timer');
const ThirdReporter=(await import(moduleUrl+'?hmr-regression=2')).TaskReporter;
const thirdHmr=new ThirdReporter(replacedCtx,hmrTransport);
assert.equal(secondHmr.disposed,true);assert.equal(thirdHmr.client,firstHmrClient);
thirdHmr.event(nextSession,{type:'turn/start',data:{turn:1}});await thirdHmr.tick();thirdHmr.dispose();
// Ordinary independent CLI inherits the real scrubbed child env and no process registry.
const child=spawnSync(process.execPath,['--input-type=module','-e',`
import {TaskReporter} from ${JSON.stringify(moduleUrl)};
const session={id:'ordinary-cli',surface:{contentGeneration:0}};
const agent={session,status:'running'};
const ctx={webServer:{port:41821},agents:{get:()=>agent,roots:()=>[agent]},sessionProjections:{stateOf:()=>({lastTurn:1,openTurnStartSeq:1})}};
let calls=0;const reporter=new TaskReporter(ctx,async()=>{calls++;return {}});
reporter.event(session,{type:'turn/start',data:{turn:1}});await reporter.tick();reporter.dispose();
console.log(JSON.stringify({calls,ambient:['DSH_TASK_GENERATION','DSH_TASK_INSTANCE','DSH_TASK_TOKEN'].map(key=>Object.hasOwn(process.env,key))}));
`],{env:scrubbedParentEnv(),encoding:'utf8',timeout:10000});
assert.equal(child.status,0,child.stderr);assert.deepEqual(JSON.parse(child.stdout.trim()),{calls:0,ambient:[false,false,false]});
// Detail, heartbeat and no-turn/end fallbacks share the same live-ownership rules.
const detailSession={id:'detail-session',surface:{contentGeneration:0}};
let detailNow=1000;
const detailAgent={session:detailSession,status:'running',cancel(){},whenIdle(){return Promise.resolve()}};
const detailTraffic=[];
const detailCtx={webServer:{port:41821},agents:{get:()=>detailAgent,roots:()=>[detailAgent]},
  sessionProjections:{stateOf:(session,key)=>key==='title'?'修复上岛':{lastTurn:1,openTurnStartSeq:1}}};
const detailReporter=new TaskReporter(detailCtx,async(operation,payload)=>{
  detailTraffic.push({operation,payload});
  if(operation==='context') return {generation:3,instance:'actual-runtime',port:41821};
  if(operation==='claim') return {};
  return true;
},()=>detailNow,webRegistration);
detailReporter.event(detailSession,{type:'turn/start',data:{turn:1}});
detailReporter.event(detailSession,{type:'tool/call',data:{turn:1,name:'read_file',arguments:'{"path":"src/README.md"}'}});
await detailReporter.tick();
const detailEvent=detailTraffic.filter(t=>t.operation==='event').at(-1).payload;
assert.equal(detailEvent.phase,'tool');
assert.equal(detailEvent.detail,'read_file · src/README.md');
assert.equal(detailEvent.title,'修复上岛');
assert.ok(!JSON.stringify(detailTraffic).includes('private text'),'chunks still never cross the bridge');
const heartbeatStart=detailTraffic.filter(t=>t.operation==='event').length;
for(let i=0;i<5;i++) await detailReporter.tick();
assert.equal(detailTraffic.filter(t=>t.operation==='event').length,heartbeatStart+5,'a live turn heartbeats once per tick');
// A command is reduced to argv[0]: full command lines can carry secrets.
detailReporter.event(detailSession,{type:'tool/call',data:{turn:1,name:'bash',arguments:'{"command":"curl https://example.invalid?token=SECRET"}'}});
await detailReporter.tick();
assert.equal(detailTraffic.filter(t=>t.operation==='event').at(-1).payload.detail,'bash · curl');
assert.ok(!JSON.stringify(detailTraffic).includes('SECRET'),'full command lines must not cross the bridge');
// turn/end lost while the agent goes idle: converge to completed after the grace window.
detailAgent.status='idle';
for(const step of [2100,3200,5200]) { detailNow=step; await detailReporter.tick() }
const detailEnd=detailTraffic.filter(t=>t.operation==='event').at(-1).payload;
assert.equal(detailEnd.phase,'ended');
assert.equal(detailEnd.reason,'completed');
assert.equal(detailReporter.pending.size,0);
const detailIdleStart=detailTraffic.length;
for(let i=0;i<200;i++) await detailReporter.tick();
assert.equal(detailTraffic.length,detailIdleStart,'a converged terminal fact stops heartbeat and claim traffic');
detailReporter.dispose();
// Agent replacement without turn/end must converge to interrupted, never completed.
const replacedLiveSession={id:'replaced-live',surface:{contentGeneration:0}};
const replacedLiveAgent={session:replacedLiveSession,status:'running'};
const replacementLiveAgent={session:replacedLiveSession,status:'running'};
let replacedLiveCurrent=replacedLiveAgent,replacedLiveNow=0;
const replacedLiveTraffic=[];
const replacedLiveCtx={webServer:{port:41821},agents:{get:()=>replacedLiveCurrent,roots:()=>[replacedLiveCurrent]},
  sessionProjections:{stateOf:()=>({lastTurn:1,openTurnStartSeq:1})}};
const replacedLiveReporter=new TaskReporter(replacedLiveCtx,async(operation,payload)=>{
  replacedLiveTraffic.push({operation,payload});
  if(operation==='context') return {generation:3,instance:'actual-runtime',port:41821};
  if(operation==='claim') return {};
  return true;
},()=>replacedLiveNow,webRegistration);
replacedLiveReporter.event(replacedLiveSession,{type:'turn/start',data:{turn:1}});
await replacedLiveReporter.tick();
replacedLiveCurrent=replacementLiveAgent;
for(const step of [1000,2000,4100]) { replacedLiveNow=step; await replacedLiveReporter.tick() }
const replacedLiveEnd=replacedLiveTraffic.filter(t=>t.operation==='event').at(-1).payload;
assert.equal(replacedLiveEnd.phase,'ended');
assert.equal(replacedLiveEnd.reason,'interrupted','a replaced Agent must not be reported as completed');
replacedLiveReporter.dispose();
// Ask-user questions expose a one-shot native answer endpoint and follow the projection.
const askSession={id:'ask-session',surface:{contentGeneration:0}};
const askAgent={session:askSession,status:'running'};
let askOpen=[];
const askTraffic=[];
const askCtx={webServer:{port:41821},agents:{get:()=>askAgent,roots:()=>[askAgent]},
  sessionProjections:{stateOf:(session,key)=>key==='userQuestions'?{questions:{active:askOpen}}:
    key==='title'?'问一下':{lastTurn:1,openTurnStartSeq:1}}};
const askReporter=new TaskReporter(askCtx,async(operation,payload)=>{
  askTraffic.push({operation,payload});
  if(operation==='context') return {generation:3,instance:'actual-runtime',port:41821};
  if(operation==='claim') return {};
  return true;
},Date.now,webRegistration);
askReporter.event(askSession,{type:'tool/call',data:{turn:1,callId:'call-1',name:'ask_user_question',
  arguments:JSON.stringify({questions:[{id:'q1',question:'选哪个？',options:[{label:'继续'},{label:'取消'}]}]})}});
await askReporter.tick();
let askEvent=askTraffic.filter(t=>t.operation==='event').at(-1).payload;
assert.equal(askEvent.phase,'waiting');
assert.equal(askEvent.detail,'选哪个？');
let askPayload=JSON.parse(askEvent.question);
assert.equal(askPayload.callId,'call-1');
assert.equal(askPayload.port,41821);
assert.equal(askPayload.path,'/__dsha_question_answer');
assert.equal(askPayload.questions[0].options.length,2);
assert.equal(typeof askPayload.token,'string');
// Projection lag must not erase the waiting state before the question is observed.
assert.equal(askReporter.rows.get(askSession.id).phase,'waiting');
// One-shot answer endpoint validation and resolution.
let nativeAnswer;
askReporter.waiters.set('call-1',{request:{questions:[{id:'q1',options:[{label:'继续'},{label:'取消'}]}]},
  resolve:value=>{nativeAnswer=value},expires:Infinity});
assert.deepEqual(askReporter.acceptAnswer({token:'wrong',callId:'call-1',answers:[]}),{ok:false,error:'TOKEN'});
assert.deepEqual(askReporter.acceptAnswer({token:askReporter.answerToken,callId:'call-1',answers:[{id:'q1',selected:['不存在']}]}),{ok:false,error:'OPTION'});
assert.deepEqual(askReporter.acceptAnswer({token:askReporter.answerToken,callId:'call-1',answers:[{id:'q1',selected:['继续']}]}),{ok:true});
assert.deepEqual(nativeAnswer,{answers:[{id:'q1',selected:['继续']}]});
assert.equal(askReporter.acceptAnswer({token:askReporter.answerToken,callId:'call-1',answers:[{id:'q1',selected:['继续']}]}).error,'NO_WAITING');
// Multi-select keeps every chosen label; custom text is a first-class answer.
let multiAnswer, customAnswer, mixedAnswer;
askReporter.waiters.set('call-3',{request:{questions:[{id:'q3',options:[{label:'A'},{label:'B'}],multiSelect:true}]},
  resolve:value=>{multiAnswer=value},expires:Infinity});
assert.deepEqual(askReporter.acceptAnswer({token:askReporter.answerToken,callId:'call-3',answers:[{id:'q3',selected:['A','B']}]}),{ok:true});
assert.deepEqual(multiAnswer,{answers:[{id:'q3',selected:['A','B']}]});
askReporter.waiters.set('call-4',{request:{questions:[{id:'q4',options:[{label:'A'}]}]},
  resolve:value=>{customAnswer=value},expires:Infinity});
assert.deepEqual(askReporter.acceptAnswer({token:askReporter.answerToken,callId:'call-4',answers:[{id:'q4',selected:[],custom:'我自己写'}]}),{ok:true});
assert.deepEqual(customAnswer,{answers:[{id:'q4',selected:[],custom:'我自己写'}]});
askReporter.waiters.set('call-5',{request:{questions:[{id:'q5',options:[{label:'A'},{label:'B'}]}]},
  resolve:value=>{mixedAnswer=value},expires:Infinity});
assert.deepEqual(askReporter.acceptAnswer({token:askReporter.answerToken,callId:'call-5',answers:[{id:'q5',selected:['A'],custom:'补充'}]}),{ok:true});
assert.deepEqual(mixedAnswer,{answers:[{id:'q5',selected:[],custom:'补充'}]},'single-select custom overrides selected');
assert.deepEqual(askReporter.acceptAnswer({token:askReporter.answerToken,callId:'call-6',answers:[{id:'q6',selected:[]}]}).error,'NO_WAITING');
// Multi-select question payload carries the flag and all option labels.
askOpen=[{callId:'call-7',state:'open',questions:[{id:'q7',question:'选几个？',multiSelect:true,
  options:[{label:'A'},{label:'B'},{label:'C'}]}]}];
await askReporter.tick();
askEvent=askTraffic.filter(t=>t.operation==='event').at(-1).payload;
assert.equal(askEvent.phase,'waiting');
assert.equal(JSON.parse(askEvent.question).questions[0].multiSelect,true);
assert.equal(JSON.parse(askEvent.question).questions[0].options.length,3);
// The projection keeps a live question and clears it once the web/native UI settles it.
askOpen=[{callId:'call-2',state:'open',questions:[{id:'q2',question:'继续吗？',options:[{label:'是'}]}]}];
await askReporter.tick();
askEvent=askTraffic.filter(t=>t.operation==='event').at(-1).payload;
assert.equal(askEvent.phase,'waiting');
assert.equal(JSON.parse(askEvent.question).callId,'call-2');
askOpen=[];
await askReporter.tick();
askEvent=askTraffic.filter(t=>t.operation==='event').at(-1).payload;
assert.equal(askEvent.phase,'running');
assert.equal(askEvent.question,'');
askReporter.dispose();
console.log('task-progress: late Session/producer ownership, one-shot cancellation, ask-answer options/multi/custom, detail/heartbeat/terminal convergence, real rc2 child scrub, ambient secret removal, independent CLI zero context, HMR client/sequence/lease continuity PASS; subprocess source SHA256='+createHash('sha256').update(readFileSync(subprocessSource)).digest('hex'));
