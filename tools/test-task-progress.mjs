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
console.log('task-progress: late Session/producer ownership, one-shot cancellation, real rc2 child scrub, ambient secret removal, independent CLI zero context, HMR client/sequence/lease continuity PASS; subprocess source SHA256='+createHash('sha256').update(readFileSync(subprocessSource)).digest('hex'));
