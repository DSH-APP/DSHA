'use strict';
// Production build170's fixed authenticated bridge; no experimental SDK is needed.
const fs=require('node:fs'),http=require('node:http');
const bridgePort=require('./bridge-port.cjs');
const TOKEN_FILE='/root/.dsh/.bridge_token',RESPONSE_LIMIT=24*1024*1024;

function bridgeFailure(code,unknown){const error=Error(code);error.executionUnknown=unknown;return error;}
function bridgeCredential(){
 for(const path of ['/root','/root/.dsh']){const value=fs.lstatSync(path);if(!value.isDirectory()||value.isSymbolicLink())throw bridgeFailure('BRIDGE_TOKEN_PARENT',false);}
 const before=fs.lstatSync(TOKEN_FILE);
 if(!before.isFile()||before.isSymbolicLink()||before.size<1||before.size>129||(before.mode&0o077)!==0)throw bridgeFailure('BRIDGE_TOKEN_FILE',false);
 const fd=fs.openSync(TOKEN_FILE,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW);
 try{
  const opened=fs.fstatSync(fd),bytes=Buffer.alloc(130);
  if(!opened.isFile()||opened.dev!==before.dev||opened.ino!==before.ino||opened.size!==before.size||(opened.mode&0o077)!==0)throw bridgeFailure('BRIDGE_TOKEN_CHANGED',false);
  const count=fs.readSync(fd,bytes,0,bytes.length,0),token=bytes.subarray(0,count).toString('ascii').trim(),after=fs.lstatSync(TOKEN_FILE);
  if(count!==opened.size||!/^[A-Za-z0-9_-]{1,128}$/.test(token)||!after.isFile()||after.dev!==opened.dev||after.ino!==opened.ino||after.size!==opened.size||(after.mode&0o077)!==0)throw bridgeFailure('BRIDGE_TOKEN_CHANGED',false);
  return token;
 }finally{fs.closeSync(fd);}
}
async function bridgeFetch(route,init={}){
 let token,port;
 try{
  if(init.signal?.aborted)throw bridgeFailure('BRIDGE_CANCELLED_BEFORE_SEND',false);
  port=bridgePort();
  const origin='http://127.0.0.1:'+port;
  const target=new URL(route,origin);
  if(target.origin!==origin||!target.pathname.startsWith('/app/vscreen/')||!['GET','POST'].includes(init.method))throw bridgeFailure('BRIDGE_ROUTE_INVALID',false);
  if(Buffer.byteLength(init.body||'','utf8')>128*1024)throw bridgeFailure('BRIDGE_BODY_LIMIT',false);
  token=bridgeCredential();
 }catch(error){error.executionUnknown=false;throw error;}
 return new Promise((resolve,reject)=>{
  let sent=false;
  const failed=error=>reject(bridgeFailure(error?.code==='ABORT_ERR'?'BRIDGE_CANCELLED':'BRIDGE_TRANSPORT_UNAVAILABLE',sent));
  const request=http.request({protocol:'http:',hostname:'127.0.0.1',port,path:route,method:init.method,
   headers:{...init.headers,'X-Token':token},signal:init.signal,agent:false},response=>{
   const parts=[];let size=0;
   response.on('data',part=>{size+=part.length;if(size>RESPONSE_LIMIT){response.destroy(bridgeFailure('BRIDGE_RESPONSE_TOO_LARGE',true));return;}parts.push(part);});
   response.on('error',failed);
   response.on('end',()=>{try{if(bridgeCredential()!==token)throw bridgeFailure('BRIDGE_TOKEN_CHANGED',true);if(bridgePort()!==port)throw bridgeFailure('BRIDGE_PORT_CHANGED',true);
    const body=Buffer.concat(parts,size).toString('utf8');resolve({ok:response.statusCode>=200&&response.statusCode<300,status:response.statusCode,text:async()=>body});
   }catch(error){reject(bridgeFailure(error.message,true));}});
  });
  request.on('error',failed);sent=true;request.end(init.body||undefined);
 });
}
const coreIdentity={type:'string',maxLength:36,pattern:'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'};
const sourceFrame={type:'integer',minimum:1,maximum:Number.MAX_SAFE_INTEGER};
const sourcePoint={type:'integer',minimum:0,maximum:Number.MAX_SAFE_INTEGER};
const definitions=[
 ['android_vscreen_create','创建或复用虚拟屏。对已有 Core 换方向即旋转：coreInstance 不变、generation 与 displayId 换新，原应用窗口不会跟过去；回复带 displayRecreated、previousPackage 和 nextAction=LAUNCH_APP_FIRST，需要原应用时重新 launch 后再 see。',{orientation:{type:'string',enum:['portrait','landscape']}}],
 ['android_vscreen_status','读取显示尺寸、方向、通道和最新 frameSeq。',{}],
 ['android_vscreen_launch','把已安装应用启动到虚拟屏。可选 generation 仅用于启动前状态检查；检查与启动不是原子操作，原生 launch 不校验 generation，显示代次可能在检查后变化。取消或中断（包括 SDK 抛出 AbortError）可能丢失回执，不证明启动未执行；先观察实际状态，不自动重放。不接受把 DSHA 自身启动到虚拟屏（系统会复用本进程，副屏 WebView 崩溃），返回 VSCREEN_SELF_LAUNCH_REFUSED（state=NOT_SENT，未发送）。',{package:{type:'string',maxLength:160},generation:{type:'string',minLength:1,maxLength:80}}],
 ['android_vscreen_see','读取同次原图及观察元数据。anchoredAvailable=true 且原图中有唯一可操作按钮时，优先显式 anchor→anchored_click；保留原 coreInstance/generation/frameSeq/observationId，不用旧 tap 试探。不可锚定时根据 tree/node 或新观察明确决定。新建的虚拟屏可能没有任何窗口：失败回复的 nextAction 为 LAUNCH_APP_FIRST（error VSCREEN_NO_WINDOW）时先 launch 应用；WAIT_AND_SEE_AGAIN（FRAME_PENDING）时稍等再 see；REPORT_TO_USER（FRAME_STALLED）时告诉用户；LAUNCH_APP_OR_WAIT 表示无障碍不可用、无法区分空屏与首帧未到。这些都是只读失败，重新 see 安全；legacyError 保留旧码 FRAME_NOT_READY。成功/失败回执可能带 sensitivity 与 sensitivityBasis（只给类别，不含界面文本）；error=SENSITIVE_SCREEN_HANDOFF 表示敏感界面已交接并暂停助手，不要重试，告诉用户由其本人操作。',{}],
 ['android_vscreen_anchor','从 see 的原观察选择唯一可操作节点，不执行点击、不续期。须用该原观察的 coreInstance/generation/frameSeq/observationId；x/y 是原图全分辨率整数坐标（anchoredAvailable=true 时返回的就是全分辨率原图），不是归一化或缩略图坐标。ANCHOR_ALREADY_ISSUED 不会返回旧 ID；它或丢失回复都不允许自动重选或补截图。锚定不可用、Canvas 或有歧义时，明确改用 tree/node 或新观察，不自动重试。',{generation:{type:'string',maxLength:80},frameSeq:sourceFrame,observationId:coreIdentity,x:sourcePoint,y:sourcePoint}],
 ['android_vscreen_anchored_click','一次性点击原观察的 anchorId。保留原 coreInstance/generation/frameSeq，不刷新帧、不用当前 status 升级旧证据；由原生严格复核节点语义及原区域。成功或失败都消耗该锚点，UNKNOWN/noReplay 不恢复锚点、不重放，不自动换成 tap。回复的 sourceFrameSeq 保持原帧，validatedCurrentSeq 是实际复核帧。点击后先重新观察再决定下一步。',{generation:{type:'string',maxLength:80},frameSeq:sourceFrame,anchorId:coreIdentity}],
 ['android_vscreen_tap','单次点击，须带最新已观察帧的 frameSeq；x/y 使用 see 返回的 width×height 显示坐标。若图像 previewWidth/previewHeight 不同，先按 width/previewWidth、height/previewHeight 换算，不把缩图坐标直接当显示坐标。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},x:{type:'number',minimum:0},y:{type:'number',minimum:0}}],
 ['android_vscreen_swipe','连续滑动；使用 see 的 width×height 显示坐标，缩图坐标须先按 width/previewWidth、height/previewHeight 换算。手势后再观察结果。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},x1:{type:'number',minimum:0},y1:{type:'number',minimum:0},x2:{type:'number',minimum:0},y2:{type:'number',minimum:0},ms:{type:'integer',minimum:50,maximum:3000}}],
 ['android_vscreen_touch','发送一个触控阶段：0 按下、2 移动、1 抬起、3 取消。x/y 是 see 的 width×height 显示坐标，缩图坐标须按比例换算。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},stroke:{type:'string',pattern:'^[a-f0-9-]{16,64}$'},action:{type:'integer',enum:[0,1,2,3]},x:{type:'number',minimum:0},y:{type:'number',minimum:0}}],
 ['android_vscreen_type','向虚拟屏当前输入目标写入文字，须带最新已观察帧的 frameSeq。只含可打印 ASCII 时走输入通道，不需要无障碍；中文等其它字符走无障碍。密码焦点会被阻断并返回 PASSWORD_INPUT_UNAVAILABLE（硬阻断，确认也不解除）；敏感界面可能要求确认或交接。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},text:{type:'string',maxLength:2000}}],
 ['android_vscreen_key','发送返回、主页、确认或退格键。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},keycode:{type:'integer',enum:[3,4,66,67]}}],
 ['android_vscreen_tree','读取虚拟屏无障碍控件树；返回 nodeId 供后续操作。ACCESSIBILITY_UNAVAILABLE 时按 reason/nextAction 处理：VSCREEN_NO_WINDOW→先 launch，ACCESSIBILITY_NOT_ENABLED→请用户在系统设置开启 DSHA 无障碍，ACCESSIBILITY_NOT_CONNECTED→等待重连后再试，VSCREEN_DISPLAY_UNAVAILABLE→先 status。树里密码框不回显文本；敏感界面可能要求确认或交接（SENSITIVE_SCREEN_HANDOFF 时不要重试）。',{}],
 ['android_vscreen_node','通过 tree 返回的 nodeId 做语义操作；不需要 frameSeq、不受像素帧率影响，nodeId 用一次即失效。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},nodeId:{type:'string',maxLength:80},action:{type:'string',enum:['click','long_click','scroll_forward','scroll_backward','focus','set_text']},text:{type:'string',maxLength:16000}}],
 ['android_vscreen_editor','读取或修改虚拟屏当前输入框；op 为 get/edit/submit。',{generation:{type:'string',maxLength:80},op:{type:'string',enum:['get','edit','submit']},editorId:{type:'string',maxLength:160},text:{type:'string',maxLength:16000},start:{type:'integer',minimum:0},end:{type:'integer',minimum:0}}],
 ['android_vscreen_close','关闭并回收虚拟屏；仍有存活或待清理的 Core 时须传其 coreInstance。',{}]
].map(row=>['android_vscreen_create','android_vscreen_status'].includes(row[0])?row:[row[0],row[1],{coreInstance:coreIdentity,...row[2]}]);
const required={
 android_vscreen_create:[],android_vscreen_status:[],android_vscreen_launch:['package'],android_vscreen_see:[],
 android_vscreen_anchor:['generation','frameSeq','observationId','x','y'],android_vscreen_anchored_click:['generation','frameSeq','anchorId'],
 android_vscreen_tap:['generation','frameSeq','x','y'],android_vscreen_swipe:['generation','frameSeq','x1','y1','x2','y2'],
 android_vscreen_touch:['generation','frameSeq','stroke','action','x','y'],android_vscreen_type:['generation','frameSeq','text'],
 android_vscreen_key:['generation','frameSeq','keycode'],android_vscreen_tree:[],
 android_vscreen_node:['generation','nodeId','action'],android_vscreen_editor:['generation','op'],android_vscreen_close:[]
};
for(const name of Object.keys(required))if(!['android_vscreen_create','android_vscreen_status','android_vscreen_close'].includes(name))required[name].unshift('coreInstance');
const tools=definitions.map(([name,description,properties])=>{
 const inputSchema={type:'object',properties,required:required[name],additionalProperties:false};
 if(name==='android_vscreen_node')inputSchema.allOf=[{if:{properties:{action:{const:'set_text'}},required:['action']},then:{required:['text']}}];
 if(name==='android_vscreen_editor')inputSchema.allOf=[
  {if:{properties:{op:{const:'edit'}},required:['op']},then:{required:['editorId','text']}},
  {if:{properties:{op:{const:'submit'}},required:['op']},then:{required:['editorId']}}
 ];
 return{name,description,inputSchema};
});
function validate(def,args){
 if(!args||typeof args!=='object'||Array.isArray(args))throw Error('INVALID_ARGUMENTS');
 const fields=def[2],name=def[0];if(Object.keys(args).some(k=>!Object.hasOwn(fields,k)))throw Error('UNKNOWN_ARGUMENT');
 const needed=[...required[name]];
 if(name==='android_vscreen_node'&&args.action==='set_text')needed.push('text');
 if(name==='android_vscreen_editor'&&args.op!=='get')needed.push('editorId');
 if(name==='android_vscreen_editor'&&args.op==='edit')needed.push('text');
 for(const key of needed)if(!Object.hasOwn(args,key))throw Error('INVALID_'+key);
 for(const [key,rule]of Object.entries(fields)){
  if(!Object.hasOwn(args,key))continue;const v=args[key];
  if(rule.enum&&!rule.enum.includes(v))throw Error('INVALID_'+key);
  if(rule.type==='integer'&&(!Number.isInteger(v)||(rule.minimum!==undefined&&v<rule.minimum)||(rule.maximum!==undefined&&v>rule.maximum)))throw Error('INVALID_'+key);
  if(rule.type==='number'&&(typeof v!=='number'||!Number.isFinite(v)||v<rule.minimum))throw Error('INVALID_'+key);
  if(rule.type==='string'&&(typeof v!=='string'||(rule.minLength!==undefined&&v.length<rule.minLength)||(rule.maxLength!==undefined&&v.length>rule.maxLength)))throw Error('INVALID_'+key);
  if(rule.pattern&&typeof v==='string'&&!new RegExp(rule.pattern).test(v))throw Error('INVALID_'+key);
 }
 for(const key of ['generation','package','nodeId'])if(needed.includes(key)&&!args[key])throw Error('INVALID_'+key);
 if(name==='android_vscreen_editor'&&args.op!=='get'&&!args.editorId)throw Error('INVALID_editorId');
 if(name==='android_vscreen_editor'&&args.op==='edit')for(const key of ['start','end'])if(args[key]!==undefined&&args[key]>args.text.length)throw Error('INVALID_'+key);
}
function bridgeFailed(value){return !value||value.ok!==true;}
function requestFor(name,args){
 const def=definitions.find(x=>x[0]===name);if(!def)throw Error('UNKNOWN_TOOL');validate(def,args);
 let route={android_vscreen_create:'create',android_vscreen_status:'status',android_vscreen_launch:'launch',android_vscreen_see:'see',android_vscreen_anchor:'anchor',android_vscreen_anchored_click:'anchored-click',android_vscreen_tap:'tap',android_vscreen_swipe:'swipe',android_vscreen_touch:'touch',android_vscreen_type:'type',android_vscreen_key:'key',android_vscreen_tree:'tree',android_vscreen_node:'node',android_vscreen_close:'close'}[name];
 const parameters={...args};
 if(name==='android_vscreen_launch')delete parameters.generation;
 if(name==='android_vscreen_editor'){route={get:'editor',edit:'edit',submit:'submit'}[args.op];delete parameters.op;}
 const endpoint='/app/vscreen/'+route;
 if(['edit','submit','node','type','anchor','anchored-click'].includes(route)){
  const body=JSON.stringify(parameters);if(Buffer.byteLength(body,'utf8')>128*1024)throw Error('BRIDGE_BODY_LIMIT');
  return{route:endpoint,method:'POST',body,headers:{'Content-Type':'application/json; charset=utf-8'}};
 }
 const query=new URLSearchParams();for(const[k,v]of Object.entries(parameters))query.set(k,String(v));
 return{route:endpoint+(query.size?'?'+query:''),method:'GET',headers:{}};
}
function decodeBridge(raw){
 let value;try{value=JSON.parse(raw);}catch{throw Error('INVALID_BRIDGE_RESPONSE');}
 if(value&&typeof value.result==='string'){
  try{value=JSON.parse(value.result);}catch{return{ok:false,error:'BRIDGE_ERROR',detail:value.result};}
 }
 if(!value||typeof value!=='object'||Array.isArray(value))throw Error('INVALID_BRIDGE_RESPONSE');return value;
}
// HTTP authorization and caller-identity refusals occur before appVscreen's action supplier.
// Manager.bridge checks Core/evidence before dispatch; query grammar and method guards precede it.
const launchPreDispatchErrors=new Set([
 'USER_REJECTED','USER_NO_ANSWER','USER_CONFIRM_UNAVAILABLE',
 'VSCREEN_CORE_IDENTITY_REQUIRED','VSCREEN_CORE_IDENTITY_CHANGED','VSCREEN_GENERATION_CHANGED',
 'CORE_EVIDENCE_REQUIRED','EXPLICIT_SCREEN_REQUEST_REQUIRED',
 'VSCREEN_SELF_LAUNCH_REFUSED',
 'INVALID_coreInstance','INVALID_package','UNKNOWN_ARGUMENT','VSCREEN_QUERY_LIMIT',
 'DUPLICATE_ARGUMENT','INVALID_ARGUMENT_ENCODING','METHOD_NOT_ALLOWED'
]);
// Only fixed diagnostic codes are echoed; arbitrary errors/causes may contain private data.
const launchKnownDetails=new Set([
 ...launchPreDispatchErrors,'LAUNCH_RESULT_UNKNOWN','INPUT_RESULT_UNKNOWN',
 'VSCREEN_TRANSPORT_UNAVAILABLE','VSCREEN_RESPONSE_LIMIT','SCREEN_TARGET_CHANGED','VSCREEN_REVOKED',
 'VSCREEN_OPERATION_FAILED','VSCREEN_DISPLAY_IO','STALE_GENERATION','VSCREEN_PERMISSION_DENIED',
 'VSCREEN_DISPLAY_SERVICE_UNAVAILABLE','VSCREEN_NATIVE_API_UNAVAILABLE','VSCREEN_INVALID_DISPLAY_REQUEST',
 'VSCREEN_DISPLAY_STATE','VSCREEN_DISPLAY_CREATE_FAILED','VSCREEN_TRUSTED_DISPLAY_DENIED',
 'VSCREEN_TARGET_CHANGED','VSCREEN_TARGET_UNVERIFIABLE','VSCREEN_NOT_CREATED','VSCREEN_NOT_RUNNING',
 'VSCREEN_START_IN_PROGRESS','VSCREEN_RESPONSE_BOUNDARY_INVALID','VSCREEN_RESPONSE_DEADLINE',
 'VSCREEN_RESPONSE_INCOMPLETE','VSCREEN_REQUEST_TARGET_INVALID','VSCREEN_REQUEST_TOKEN_INVALID',
 'INVALID_ARGUMENT','INVALID_PACKAGE','NO_LAUNCH_ACTIVITY','LAUNCH_REJECTED','BRIDGE_ERROR',
 'BRIDGE_TRANSPORT_UNAVAILABLE','BRIDGE_CANCELLED','BRIDGE_TOKEN_CHANGED','BRIDGE_PORT_CHANGED',
 'BRIDGE_RESPONSE_TOO_LARGE','INVALID_BRIDGE_RESPONSE'
]);
function launchDetail(error){
 if(typeof error!=='string'||!error)return 'UNSPECIFIED_RESULT';
 return launchKnownDetails.has(error)?error:'UNCLASSIFIED_ERROR';
}
function launchResult(value,checked,isError){
 const metadata={...value,generationCheckedBeforeLaunch:checked,generationEnforcedAtLaunch:false};
 if(metadata.executionUnknown===true)metadata.noReplay=true;
 return{content:[{type:'text',text:JSON.stringify(metadata)}],isError};
}
function launchNotSent(error,checked=false,extra={}){
 return launchResult({ok:false,state:'NOT_SENT',error,...extra},checked,true);
}
function launchUnknown(error,checked){
 return launchResult({ok:false,state:'UNKNOWN',error:'LAUNCH_RESULT_UNKNOWN',detail:launchDetail(error),executionUnknown:true,noReplay:true},checked,true);
}
function launchBridgeResult(value,checked,httpOk){
 const marked=value.executionUnknown===true||value.targetChangedAfterExecution===true||value.runChangedAfterExecution===true||value.noReplay===true;
 const failureState=value.state===undefined||value.state==='REJECTED'||value.state==='NOT_SENT';
 if(value.ok===false&&failureState&&!marked&&launchPreDispatchErrors.has(value.error))
  return launchNotSent(value.error,checked);
 const successState=value.state===undefined||value.state==='COMPLETED';
 if(httpOk&&value.ok===true&&successState&&!marked&&(value.error===undefined||value.error===''))
  return launchResult(value,checked,false);
 return launchUnknown(value.error,checked);
}
function launchErrorCode(error,fallback){
 const safe=['BRIDGE_CANCELLED_BEFORE_SEND','BRIDGE_ROUTE_INVALID','BRIDGE_BODY_LIMIT','BRIDGE_TOKEN_PARENT','BRIDGE_TOKEN_FILE','BRIDGE_TOKEN_CHANGED'];
 return safe.includes(error?.message)?error.message:fallback;
}
async function call(name,args,signal,dependencies={}){
 const request=requestFor(name,args);
 const operation=request.route.split('?')[0].split('/').pop();
 const mutating=['tap','swipe','touch','type','key','node','edit','submit','anchored-click'].includes(operation);
 const fetchBridge=dependencies.bridgeFetch||bridgeFetch;
 let launchGenerationChecked=false;
 if(operation==='launch'){
  if(signal?.aborted)return launchNotSent('BRIDGE_CANCELLED_BEFORE_LAUNCH');
  if(Object.hasOwn(args,'generation')){
   try{
    const response=await fetchBridge('/app/vscreen/status',{method:'GET',headers:{},signal});
    const status=decodeBridge(await response.text());
    if(signal?.aborted)return launchNotSent('BRIDGE_CANCELLED_BEFORE_LAUNCH');
    if(!response.ok||bridgeFailed(status)||status.executionUnknown===true||status.cleanupPending===true||
       (status.state!==undefined&&status.state!=='COMPLETED')||(status.error!==undefined&&status.error!=='')){
     const statusCode=launchDetail(status.error),statusError=statusCode==='UNSPECIFIED_RESULT'?{}:{statusError:statusCode};
     return launchNotSent('VSCREEN_STATUS_UNAVAILABLE',false,statusError);
    }
    if(typeof status.coreInstance!=='string'||!status.coreInstance||typeof status.generation!=='string'||!status.generation)
     return launchNotSent('VSCREEN_STATUS_IDENTITY_UNAVAILABLE');
    if(status.coreInstance!==args.coreInstance)return launchNotSent('VSCREEN_CORE_IDENTITY_CHANGED');
    if(status.generation!==args.generation)return launchNotSent('VSCREEN_GENERATION_CHANGED');
    launchGenerationChecked=true;
   }catch(error){
    return launchNotSent(signal?.aborted?'BRIDGE_CANCELLED_BEFORE_LAUNCH':'VSCREEN_STATUS_UNAVAILABLE');
   }
  }
  if(signal?.aborted)return launchNotSent('BRIDGE_CANCELLED_BEFORE_LAUNCH',launchGenerationChecked);
 }
 try{
  const {route,...init}=request;
  const response=await fetchBridge(route,{...init,signal});
  const value=decodeBridge(await response.text());
  if(operation==='launch')return launchBridgeResult(value,launchGenerationChecked,response.ok);
  const{previewB64,...metadata}=value;const content=[{type:'text',text:JSON.stringify(metadata)}];
  if(name==='android_vscreen_see'&&previewB64)content.push({type:'image',mimeType:'image/jpeg',data:previewB64});
  return{content,isError:!response.ok||bridgeFailed(value)};
 }catch(error){
  if(operation==='launch'){
   if(error?.executionUnknown===false)return launchNotSent(launchErrorCode(error,'VSCREEN_LAUNCH_NOT_SENT'),launchGenerationChecked);
   return launchUnknown(error?.message,launchGenerationChecked);
  }
  if(operation==='anchor'&&error.executionUnknown!==false)return{content:[{type:'text',text:JSON.stringify({ok:false,error:'ANCHOR_RESULT_UNKNOWN',selectionUnknown:true,noReplay:true,sourceFrameSeq:args.frameSeq})}],isError:true};
  if(!mutating||error.executionUnknown===false)throw error;
  // 取消请求或丢失回执不能证明写动作尚未执行。
  return{content:[{type:'text',text:JSON.stringify({ok:false,error:'INPUT_RESULT_UNKNOWN',executionUnknown:true,...(operation==='anchored-click'?{noReplay:true,mode:'ANCHORED_NODE',sourceFrameSeq:args.frameSeq}:{})})}],isError:true};
 }
}
function start(){const active=new Map(),cancelled=new Set();let buffer='',queue=Promise.resolve(),ended=false,pending=0;const send=m=>process.stdout.write(JSON.stringify({jsonrpc:'2.0',...m})+'\n');async function receive(m){const{id,method,params={}}=m;if(method==='notifications/cancelled'){if(active.has(params.requestId))active.get(params.requestId).abort();else if(cancelled.size<128)cancelled.add(params.requestId);return;}if(id===undefined)return;if(ended||cancelled.delete(id)){send({id,error:{code:-32800,message:'Request cancelled'}});return;}try{let result;if(method==='initialize')result={protocolVersion:params.protocolVersion||'2025-03-26',capabilities:{tools:{listChanged:false}},serverInfo:{name:'DSHA Virtual Screen',version:'0.2.1'},instructions:'本服务只操作独立虚拟屏；不需要也不调用 android_control begin/finish（那只用于实体主屏），失败时不改用实体主屏兜底，也不从实体主屏截图推断本屏内容。流程：先 status（没有 Core 时才 create）；除 create/status 外每次调用显式传 status 返回的 coreInstance；新建或旋转后的屏可能是空屏（see 回复 nextAction=LAUNCH_APP_FIRST），先 launch 应用；输入前先 see 或 tree 观察，动作后重新观察再决定下一步。能做什么：不需要无障碍——status、create、close、launch、see，以及基于最新 see 帧的 tap/swipe/touch/key 和只含可打印 ASCII 的 type（写动作要求屏上已有前台应用，空屏会返回未执行）；需要无障碍——tree、node、editor、anchor/anchored_click 与中文等非 ASCII 的 type，不可用时返回 ACCESSIBILITY_UNAVAILABLE，可改用上面的像素路径或请用户开启无障碍。坐标、语义节点/editor、锚定三种动作各有独立身份与目标复核，不互相静默回退；中文和动态页面输入优先用已观察的 editable 节点或 editor，不靠旧像素坐标。coreInstance、generation、frameSeq、observationId、nodeId 必须来自同一次观察，不用更新的 Core 身份或当前 status 升级旧观察。取消、超时或中断（包括 SDK 抛出 AbortError）可能丢失回执，不证明动作或 launch 未执行；executionUnknown、noReplay、targetChangedAfterExecution、runChangedAfterExecution 一律不自动重放，先观察实际状态。只读观察失败按回复的 nextAction 处理（WAIT_AND_SEE_AGAIN、REPORT_TO_USER、LAUNCH_APP_OR_WAIT、ENABLE_ACCESSIBILITY），重新观察安全，也不代表任何动作已执行；CHECK_STATUS 表示先读 status 再决定；ASK_USER_TO_CLOSE 表示 Core 待清理，重看无用：告诉用户，经用户同意后才 close 该 coreInstance，绝不自动新建替代。cleanupPending、CLOSE_UNKNOWN、START_UNKNOWN、DISCONNECTED 不是自动重建的许可，先读 status。\n敏感界面：回复带 sensitivity（PASSWORD/OTP/PAYMENT/SYSTEM_SECURITY/SECURE_UNKNOWN）时按策略处理。默认 ASK，即本次动作仍需用户确认；error=SENSITIVE_SCREEN_HANDOFF 表示该类别配置为交接：助手已被暂停，动作未发送（state=NOT_SENT），不要重试、不要换屏或换工具，告诉用户由其本人在手机上操作；用户恢复后先重新 see，绝不重放上一动作。PASSWORD_INPUT_UNAVAILABLE 是密码文本读写硬阻断，确认也不会解除。用户对某类别选择「不启用」时，回复不带该类别 sensitivity，按普通屏幕确认策略处理（密码文本硬阻断仍独立生效）。'};else if(method==='ping')result={};else if(method==='tools/list')result={tools};else if(method==='tools/call'){const ac=new AbortController();active.set(id,ac);try{result=await call(params.name,params.arguments||{},ac.signal);}finally{active.delete(id);}}else{send({id,error:{code:-32601,message:'Method not found'}});return;}send({id,result});}catch(error){send({id,result:{content:[{type:'text',text:'Virtual screen unavailable: '+(error.message||'OPERATION_UNAVAILABLE')}],isError:true}});}}
process.stdin.setEncoding('utf8');process.stdin.on('data',chunk=>{buffer+=chunk;if(buffer.length>1024*1024)process.exit(1);let end;while((end=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,end);buffer=buffer.slice(end+1);if(!line.trim())continue;let value;try{value=JSON.parse(line);}catch{send({id:null,error:{code:-32700,message:'Parse error'}});continue;}if(value.method==='notifications/cancelled'){void receive(value);continue;}if(pending>=32){send({id:value.id??null,error:{code:-32000,message:'Request queue full'}});continue;}pending++;queue=queue.then(()=>receive(value)).finally(()=>pending--);}});process.stdin.on('end',()=>{ended=true;for(const ac of active.values())ac.abort();});process.stdout.on('error',e=>{if(e.code==='EPIPE')process.exit(0);});}
if(require.main===module)start();module.exports={definitions,tools,validate,call,requestFor,decodeBridge,bridgeFailed};

