import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {definitions,tools,validate,requestFor,decodeBridge,call} from '../app/src/main/assets/builtin-plugins/dsh-tool-vscreen/lib/server.cjs';
const coreInstance='11111111-1111-4111-8111-111111111111';
const names=definitions.map(row=>row[0]);
assert.throws(()=>requestFor('android_vscreen_see',{}),/INVALID_coreInstance/);
assert.throws(()=>requestFor('android_vscreen_close',{coreInstance:'old'}),/INVALID_coreInstance/);
assert.equal(requestFor('android_vscreen_close',{}).route,'/app/vscreen/close');
assert.deepEqual(names,['android_vscreen_create','android_vscreen_status','android_vscreen_launch','android_vscreen_see','android_vscreen_anchor','android_vscreen_anchored_click','android_vscreen_tap','android_vscreen_swipe','android_vscreen_touch','android_vscreen_type','android_vscreen_key','android_vscreen_tree','android_vscreen_node','android_vscreen_editor','android_vscreen_close']);
const tap=definitions.find(row=>row[0]==='android_vscreen_tap');
validate(tap,{coreInstance,generation:'test-generation',frameSeq:1,x:3,y:4});
assert.throws(()=>validate(tap,{coreInstance,generation:'test-generation',frameSeq:0,x:3,y:4}),/INVALID_frameSeq/);
assert.throws(()=>validate(tap,{coreInstance,generation:'test-generation',frameSeq:1,x:3,y:4,extra:true}),/UNKNOWN_ARGUMENT/);
const touch=definitions.find(row=>row[0]==='android_vscreen_touch');
validate(touch,{coreInstance,generation:'test-generation',frameSeq:1,stroke:'0123456789abcdef',action:0,x:3,y:4});
assert.throws(()=>validate(touch,{coreInstance,generation:'test-generation',frameSeq:1,stroke:'bad',action:0,x:3,y:4}),/INVALID_stroke/);
const editor=definitions.find(row=>row[0]==='android_vscreen_editor');
validate(editor,{coreInstance,generation:'test-generation',op:'get',editorId:'',text:'',start:0,end:0});
validate(editor,{coreInstance,generation:'test-generation',op:'get'});
validate(editor,{coreInstance,generation:'test-generation',op:'submit',editorId:'3:42'});
assert.throws(()=>validate(editor,{coreInstance,generation:'test-generation',op:'submit'}),/INVALID_editorId/);
const get=requestFor('android_vscreen_editor',{coreInstance,generation:'test-generation',op:'get'});assert.equal(get.method,'GET');assert.match(get.route,/\/editor\?/);
const text='中'.repeat(16000),edit=requestFor('android_vscreen_editor',{coreInstance,generation:'test-generation',op:'edit',editorId:'3:42',text});
assert.equal(edit.method,'POST');assert.match(edit.route,/\/edit$/);assert.ok(edit.route.length<150);assert.equal(JSON.parse(edit.body).text,text);assert.ok(Buffer.byteLength(edit.body)<128*1024);
const submit=requestFor('android_vscreen_editor',{coreInstance,generation:'test-generation',op:'submit',editorId:'3:42'});assert.match(submit.route,/\/submit$/);assert.equal(submit.method,'POST');assert.ok(!Object.hasOwn(JSON.parse(submit.body),'text'));
const node=definitions.find(row=>row[0]==='android_vscreen_node');validate(node,{coreInstance,generation:'g',frameSeq:1,nodeId:'n',action:'click'});assert.throws(()=>validate(node,{coreInstance,generation:'g',frameSeq:1,nodeId:'n',action:'set_text'}),/INVALID_text/);
validate(node,{coreInstance,generation:'g',nodeId:'n',action:'click'});
assert.deepEqual(tools.find(row=>row.name==='android_vscreen_node').inputSchema.required,['coreInstance','generation','nodeId','action']);
assert.deepEqual(decodeBridge(JSON.stringify({result:JSON.stringify({ok:true,targetChangedAfterExecution:true})})),{ok:true,targetChangedAfterExecution:true});
assert.deepEqual(decodeBridge(JSON.stringify({result:JSON.stringify({ok:false,error:'INPUT_RESULT_UNKNOWN',executionUnknown:true})})),{ok:false,error:'INPUT_RESULT_UNKNOWN',executionUnknown:true});
assert.deepEqual(tools.find(row=>row.name==='android_vscreen_editor').inputSchema.required,['coreInstance','generation','op']);
assert.deepEqual(decodeBridge(JSON.stringify({result:JSON.stringify({ok:true,generation:'g',previewB64:'image'})})),{ok:true,generation:'g',previewB64:'image'});
assert.equal(decodeBridge(JSON.stringify({result:'[UNAUTHORIZED]'})).ok,false);
let sends=0;
const transport={bridgeFetch:async()=>{sends++;throw Error('lost response');}};
const unknown=await call('android_vscreen_key',{coreInstance,generation:'g',frameSeq:1,keycode:4},undefined,transport);
assert.equal(sends,1);assert.equal(unknown.isError,true);
assert.deepEqual(JSON.parse(unknown.content[0].text),{ok:false,error:'INPUT_RESULT_UNKNOWN',executionUnknown:true});
await assert.rejects(()=>call('android_vscreen_status',{},undefined,transport),/lost response/);
const marked=await call('android_vscreen_key',{coreInstance,generation:'g',frameSeq:1,keycode:4},undefined,{bridgeFetch:async()=>({ok:true,text:async()=>JSON.stringify({result:JSON.stringify({ok:true,runChangedAfterExecution:true})})})});
assert.equal(marked.isError,false);assert.deepEqual(JSON.parse(marked.content[0].text),{ok:true,runChangedAfterExecution:true});

const launch=tools.find(row=>row.name==='android_vscreen_launch');
assert.match(launch.description,/AbortError/);assert.match(launch.description,/先观察实际状态/);assert.match(launch.description,/不自动重放/);
// This checks the real stdio instructions, not the rc3 SDK's cancellation receipt delivery.
const initialize=spawnSync(process.execPath,[fileURLToPath(new URL('../app/src/main/assets/builtin-plugins/dsh-tool-vscreen/lib/server.cjs',import.meta.url))],{
 input:JSON.stringify({jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:'2025-03-26'}})+'\n',encoding:'utf8',timeout:5000,windowsHide:true
});
assert.equal(initialize.status,0);assert.equal(initialize.stderr,'');
const instructions=JSON.parse(initialize.stdout.trim()).result.instructions;
assert.match(instructions,/SDK 抛出 AbortError/);assert.match(instructions,/不证明动作或 launch 未执行/);
assert.match(instructions,/一律不自动重放，先观察实际状态/);
assert.deepEqual(launch.inputSchema.required,['coreInstance','package']);
assert.deepEqual(Object.keys(launch.inputSchema.properties),['coreInstance','package','generation']);
assert.equal(launch.inputSchema.additionalProperties,false);
assert.equal(launch.inputSchema.properties.generation.minLength,1);
assert.equal(launch.inputSchema.properties.generation.maxLength,80);
const launchArgs={coreInstance,package:'com.example.app'};
const launchWithGeneration={...launchArgs,generation:'display-a'};
const originalArgs={...launchWithGeneration};
function launchQuery(args){return new URL(requestFor('android_vscreen_launch',args).route,'http://127.0.0.1').searchParams;}
assert.deepEqual([...launchQuery(launchArgs)],Object.entries(launchArgs));
assert.deepEqual([...launchQuery(launchWithGeneration)],Object.entries(launchArgs));
assert.deepEqual(launchWithGeneration,originalArgs);
for(const generation of ['',null,7,'x'.repeat(81)])
 assert.throws(()=>requestFor('android_vscreen_launch',{...launchArgs,generation}),/INVALID_generation/);
assert.doesNotThrow(()=>requestFor('android_vscreen_launch',{...launchArgs,generation:'x'.repeat(80)}));
assert.throws(()=>requestFor('android_vscreen_launch',{...launchWithGeneration,extra:true}),/UNKNOWN_ARGUMENT/);
assert.equal(new URL(requestFor('android_vscreen_tap',{coreInstance,generation:'display-a',frameSeq:1,x:2,y:3}).route,'http://127.0.0.1').searchParams.get('generation'),'display-a');
assert.equal(JSON.parse(requestFor('android_vscreen_node',{coreInstance,generation:'display-a',nodeId:'n',action:'click'}).body).generation,'display-a');

const response=(value,ok=true)=>({ok,text:async()=>JSON.stringify({result:JSON.stringify(value)})});
const resultValue=result=>JSON.parse(result.content[0].text);
const currentStatus={ok:true,coreInstance,generation:'display-a'};
let launchRequests=[];
const launchFetch=async(route,init)=>{
 launchRequests.push({route,init});
 return route==='/app/vscreen/status'?response(currentStatus):response({ok:true,launched:true});
};
let result=await call('android_vscreen_launch',launchArgs,undefined,{bridgeFetch:launchFetch});
assert.equal(result.isError,false);
assert.deepEqual(launchRequests.map(row=>row.route),[requestFor('android_vscreen_launch',launchArgs).route]);
assert.deepEqual(resultValue(result),{ok:true,launched:true,generationCheckedBeforeLaunch:false,generationEnforcedAtLaunch:false});
launchRequests=[];
result=await call('android_vscreen_launch',launchWithGeneration,undefined,{bridgeFetch:launchFetch});
assert.equal(result.isError,false);
assert.deepEqual(launchRequests.map(row=>row.route),['/app/vscreen/status',requestFor('android_vscreen_launch',launchArgs).route]);
assert.equal(launchRequests[0].init.method,'GET');assert.equal(launchRequests[1].init.method,'GET');
assert.deepEqual(resultValue(result),{ok:true,launched:true,generationCheckedBeforeLaunch:true,generationEnforcedAtLaunch:false});
assert.deepEqual(launchWithGeneration,originalArgs);

for(const [status,expected] of [
 [{ok:true,coreInstance,generation:'display-b'},'VSCREEN_GENERATION_CHANGED'],
 [{ok:true,coreInstance:'22222222-2222-4222-8222-222222222222',generation:'display-a'},'VSCREEN_CORE_IDENTITY_CHANGED'],
 [{ok:true,coreInstance},'VSCREEN_STATUS_IDENTITY_UNAVAILABLE'],
 [{ok:true,generation:'display-a'},'VSCREEN_STATUS_IDENTITY_UNAVAILABLE'],
 [{ok:false,error:'VSCREEN_NOT_RUNNING'},'VSCREEN_STATUS_UNAVAILABLE'],
 [{...currentStatus,state:'UNKNOWN'},'VSCREEN_STATUS_UNAVAILABLE'],
 [{...currentStatus,cleanupPending:true},'VSCREEN_STATUS_UNAVAILABLE']
]){
 let sent=0;
 result=await call('android_vscreen_launch',launchWithGeneration,undefined,{bridgeFetch:async route=>{sent++;assert.equal(route,'/app/vscreen/status');return response(status);}});
 assert.equal(sent,1);assert.equal(result.isError,true);assert.equal(resultValue(result).state,'NOT_SENT');assert.equal(resultValue(result).error,expected);
 assert.equal(resultValue(result).generationEnforcedAtLaunch,false);
}
for(const statusResponse of [response(currentStatus,false),{ok:true,text:async()=>'{invalid'},response([])]){
 let sent=0;
 result=await call('android_vscreen_launch',launchWithGeneration,undefined,{bridgeFetch:async()=>{sent++;return statusResponse;}});
 assert.equal(sent,1);assert.equal(resultValue(result).state,'NOT_SENT');assert.equal(resultValue(result).error,'VSCREEN_STATUS_UNAVAILABLE');
}
let preflightCalls=0;
result=await call('android_vscreen_launch',launchWithGeneration,undefined,{bridgeFetch:async()=>{preflightCalls++;throw Error('unavailable');}});
assert.equal(preflightCalls,1);assert.equal(resultValue(result).state,'NOT_SENT');assert.equal(resultValue(result).error,'VSCREEN_STATUS_UNAVAILABLE');
let invalidCalls=0;
await assert.rejects(()=>call('android_vscreen_launch',{...launchWithGeneration,extra:true},undefined,{bridgeFetch:async()=>{invalidCalls++;}}),/UNKNOWN_ARGUMENT/);
await assert.rejects(()=>call('android_vscreen_launch',{...launchArgs,generation:''},undefined,{bridgeFetch:async()=>{invalidCalls++;}}),/INVALID_generation/);
assert.equal(invalidCalls,0);

const cancelledBefore=new AbortController();cancelledBefore.abort();let cancelledCalls=0;
result=await call('android_vscreen_launch',launchArgs,cancelledBefore.signal,{bridgeFetch:async()=>{cancelledCalls++;}});
assert.equal(cancelledCalls,0);assert.equal(resultValue(result).state,'NOT_SENT');assert.equal(resultValue(result).error,'BRIDGE_CANCELLED_BEFORE_LAUNCH');
const cancelledAfterStatus=new AbortController();cancelledCalls=0;
result=await call('android_vscreen_launch',launchWithGeneration,cancelledAfterStatus.signal,{bridgeFetch:async()=>{cancelledCalls++;cancelledAfterStatus.abort();return response(currentStatus);}});
assert.equal(cancelledCalls,1);assert.equal(resultValue(result).state,'NOT_SENT');assert.equal(resultValue(result).error,'BRIDGE_CANCELLED_BEFORE_LAUNCH');

let launchCalls=0;
const lostAfterSend=Error('BRIDGE_TRANSPORT_UNAVAILABLE');lostAfterSend.executionUnknown=true;
result=await call('android_vscreen_launch',launchWithGeneration,undefined,{bridgeFetch:async route=>{
 if(route==='/app/vscreen/status')return response(currentStatus);launchCalls++;throw lostAfterSend;
}});
assert.equal(launchCalls,1);assert.equal(result.isError,true);
assert.deepEqual(resultValue(result),{ok:false,state:'UNKNOWN',error:'LAUNCH_RESULT_UNKNOWN',detail:'BRIDGE_TRANSPORT_UNAVAILABLE',executionUnknown:true,noReplay:true,generationCheckedBeforeLaunch:true,generationEnforcedAtLaunch:false});
result=await call('android_vscreen_launch',launchArgs,undefined,{bridgeFetch:async()=>response({ok:false,state:'REJECTED',error:'LAUNCH_RESULT_UNKNOWN'})});
assert.deepEqual(resultValue(result),{ok:false,state:'UNKNOWN',error:'LAUNCH_RESULT_UNKNOWN',detail:'LAUNCH_RESULT_UNKNOWN',executionUnknown:true,noReplay:true,generationCheckedBeforeLaunch:false,generationEnforcedAtLaunch:false});
for(const originalError of ['VSCREEN_TRANSPORT_UNAVAILABLE','VSCREEN_RESPONSE_LIMIT','SCREEN_TARGET_CHANGED','VSCREEN_REVOKED','VSCREEN_OPERATION_FAILED','VSCREEN_DISPLAY_IO','STALE_GENERATION']){
 let requests=0;
 result=await call('android_vscreen_launch',launchWithGeneration,undefined,{bridgeFetch:async route=>{
  requests++;return route==='/app/vscreen/status'?response(currentStatus):response({ok:false,state:'REJECTED',error:originalError});
 }});
 assert.equal(requests,2);assert.equal(result.isError,true);
 assert.deepEqual(resultValue(result),{ok:false,state:'UNKNOWN',error:'LAUNCH_RESULT_UNKNOWN',detail:originalError,executionUnknown:true,noReplay:true,generationCheckedBeforeLaunch:true,generationEnforcedAtLaunch:false});
 result=await call('android_vscreen_launch',launchArgs,undefined,{bridgeFetch:async()=>response({ok:false,state:'NOT_SENT',error:originalError})});
 assert.equal(resultValue(result).state,'UNKNOWN');assert.equal(resultValue(result).error,'LAUNCH_RESULT_UNKNOWN');
 assert.equal(resultValue(result).detail,originalError);assert.equal(resultValue(result).executionUnknown,true);assert.equal(resultValue(result).noReplay,true);
}
for(const originalError of ['USER_REJECTED','USER_NO_ANSWER','USER_CONFIRM_UNAVAILABLE','VSCREEN_CORE_IDENTITY_REQUIRED','VSCREEN_CORE_IDENTITY_CHANGED','VSCREEN_GENERATION_CHANGED','CORE_EVIDENCE_REQUIRED','EXPLICIT_SCREEN_REQUEST_REQUIRED','INVALID_coreInstance','INVALID_package','UNKNOWN_ARGUMENT','VSCREEN_QUERY_LIMIT','DUPLICATE_ARGUMENT','INVALID_ARGUMENT_ENCODING','METHOD_NOT_ALLOWED']){
 result=await call('android_vscreen_launch',launchArgs,undefined,{bridgeFetch:async()=>response({ok:false,error:originalError,cause:'private detail'})});
 assert.equal(result.isError,true);assert.equal(resultValue(result).state,'NOT_SENT');assert.equal(resultValue(result).error,originalError);
 assert.equal(Object.hasOwn(resultValue(result),'executionUnknown'),false);assert.equal(Object.hasOwn(resultValue(result),'cause'),false);
}
for(const uncertainReply of [{},{ok:false},{ok:'true'},{ok:true,state:'REJECTED'}, {ok:false,error:'USER_REJECTED',executionUnknown:true}, {ok:true,runChangedAfterExecution:true}]){
 result=await call('android_vscreen_launch',launchArgs,undefined,{bridgeFetch:async()=>response(uncertainReply)});
 assert.equal(result.isError,true);assert.equal(resultValue(result).state,'UNKNOWN');assert.equal(resultValue(result).executionUnknown,true);assert.equal(resultValue(result).noReplay,true);
}
result=await call('android_vscreen_launch',launchArgs,undefined,{bridgeFetch:async()=>response({ok:true},false)});
assert.equal(resultValue(result).state,'UNKNOWN');assert.equal(resultValue(result).detail,'UNSPECIFIED_RESULT');
const secret='SUPER_SECRET_TOKEN';
result=await call('android_vscreen_launch',launchArgs,undefined,{bridgeFetch:async()=>response({ok:false,state:'NOT_SENT',error:secret,detail:secret,cause:secret})});
assert.equal(resultValue(result).detail,'UNCLASSIFIED_ERROR');assert.equal(resultValue(result).state,'UNKNOWN');
assert.doesNotMatch(result.content[0].text,new RegExp(secret));
let failedStatusRequests=0;
result=await call('android_vscreen_launch',launchWithGeneration,undefined,{bridgeFetch:async()=>{failedStatusRequests++;return response({ok:false,error:'VSCREEN_TRANSPORT_UNAVAILABLE'});}});
assert.equal(failedStatusRequests,1);assert.equal(resultValue(result).state,'NOT_SENT');
assert.equal(resultValue(result).error,'VSCREEN_STATUS_UNAVAILABLE');assert.equal(resultValue(result).statusError,'VSCREEN_TRANSPORT_UNAVAILABLE');
const nonLaunchFailure={ok:false,state:'REJECTED',error:'VSCREEN_TRANSPORT_UNAVAILABLE'};
result=await call('android_vscreen_status',{},undefined,{bridgeFetch:async()=>response(nonLaunchFailure)});
assert.equal(result.isError,true);assert.deepEqual(resultValue(result),nonLaunchFailure);
const stoppedBeforeSend=Error('BRIDGE_CANCELLED_BEFORE_SEND');stoppedBeforeSend.executionUnknown=false;
result=await call('android_vscreen_launch',launchArgs,undefined,{bridgeFetch:async()=>{throw stoppedBeforeSend;}});
assert.equal(resultValue(result).state,'NOT_SENT');assert.equal(resultValue(result).error,'BRIDGE_CANCELLED_BEFORE_SEND');
result=await call('android_vscreen_launch',launchArgs,undefined,{bridgeFetch:async()=>({ok:true,text:async()=>'{invalid'})});
assert.equal(resultValue(result).error,'LAUNCH_RESULT_UNKNOWN');assert.equal(resultValue(result).noReplay,true);
let raceLaunches=0;
result=await call('android_vscreen_launch',launchWithGeneration,undefined,{bridgeFetch:async route=>{
 if(route==='/app/vscreen/status')return response(currentStatus);raceLaunches++;return response({ok:true,generation:'display-b'});
}});
assert.equal(raceLaunches,1);assert.equal(resultValue(result).generation,'display-b');
assert.equal(resultValue(result).generationCheckedBeforeLaunch,true);assert.equal(resultValue(result).generationEnforcedAtLaunch,false);
console.log('PASS virtual screen tool schema, frameSeq validation and fixed tool surface; rc3 SDK cancellation receipt delivery NOT_TESTED');
