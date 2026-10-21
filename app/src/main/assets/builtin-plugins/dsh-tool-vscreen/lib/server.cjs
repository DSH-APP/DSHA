'use strict';
// Production build170's fixed authenticated bridge; no experimental SDK is needed.
const fs=require('node:fs'),http=require('node:http');
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
 let token;
 try{
  if(init.signal?.aborted)throw bridgeFailure('BRIDGE_CANCELLED_BEFORE_SEND',false);
  const target=new URL(route,'http://127.0.0.1:3090');
  if(target.origin!=='http://127.0.0.1:3090'||!target.pathname.startsWith('/app/vscreen/')||!['GET','POST'].includes(init.method))throw bridgeFailure('BRIDGE_ROUTE_INVALID',false);
  if(Buffer.byteLength(init.body||'','utf8')>128*1024)throw bridgeFailure('BRIDGE_BODY_LIMIT',false);
  token=bridgeCredential();
 }catch(error){error.executionUnknown=false;throw error;}
 return new Promise((resolve,reject)=>{
  let sent=false;
  const failed=error=>reject(bridgeFailure(error?.code==='ABORT_ERR'?'BRIDGE_CANCELLED':'BRIDGE_TRANSPORT_UNAVAILABLE',sent));
  const request=http.request({protocol:'http:',hostname:'127.0.0.1',port:3090,path:route,method:init.method,
   headers:{...init.headers,'X-Token':token},signal:init.signal,agent:false},response=>{
   const parts=[];let size=0;
   response.on('data',part=>{size+=part.length;if(size>RESPONSE_LIMIT){response.destroy(bridgeFailure('BRIDGE_RESPONSE_TOO_LARGE',true));return;}parts.push(part);});
   response.on('error',failed);
   response.on('end',()=>{try{if(bridgeCredential()!==token)throw bridgeFailure('BRIDGE_TOKEN_CHANGED',true);
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
 ['android_vscreen_create','创建或复用虚拟屏。',{orientation:{type:'string',enum:['portrait','landscape']}}],
 ['android_vscreen_status','读取显示尺寸、方向、通道和最新 frameSeq。',{}],
 ['android_vscreen_launch','把已安装应用启动到虚拟屏。',{package:{type:'string',maxLength:160}}],
 ['android_vscreen_see','读取同次原图及观察元数据。anchoredAvailable=true 且原图中有唯一可操作按钮时，优先显式 anchor→anchored_click；保留原 coreInstance/generation/frameSeq/observationId，不用旧 tap 试探。不可锚定时根据 tree/node 或新观察明确决定。',{}],
 ['android_vscreen_anchor','从 see 的原观察和全分辨率整数坐标选择唯一可操作节点，不执行点击、不续期。必须使用该原观察的 coreInstance/generation/frameSeq/observationId；ANCHOR_ALREADY_ISSUED 或丢失回复时不得自动重选或补图。',{generation:{type:'string',maxLength:80},frameSeq:sourceFrame,observationId:coreIdentity,x:sourcePoint,y:sourcePoint}],
 ['android_vscreen_anchored_click','一次性点击原观察的 anchorId。保留原 coreInstance/generation/frameSeq；由原生严格复核节点语义及原区域。失败或 UNKNOWN 不恢复锚点、不重放，不自动换成 tap。',{generation:{type:'string',maxLength:80},frameSeq:sourceFrame,anchorId:coreIdentity}],
 ['android_vscreen_tap','单次点击；x/y 使用 see 返回的 width×height 显示坐标。若图像 previewWidth/previewHeight 不同，先按比例换算，不把缩图坐标直接当显示坐标。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},x:{type:'number',minimum:0},y:{type:'number',minimum:0}}],
 ['android_vscreen_swipe','连续滑动；使用 see 的 width×height 显示坐标，缩图坐标须先按 width/previewWidth、height/previewHeight 换算。手势后再观察结果。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},x1:{type:'number',minimum:0},y1:{type:'number',minimum:0},x2:{type:'number',minimum:0},y2:{type:'number',minimum:0},ms:{type:'integer',minimum:50,maximum:3000}}],
 ['android_vscreen_touch','发送一个触控阶段：0 按下、2 移动、1 抬起、3 取消。x/y 是 see 的 width×height 显示坐标，缩图坐标须按比例换算。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},stroke:{type:'string',pattern:'^[a-f0-9-]{16,64}$'},action:{type:'integer',enum:[0,1,2,3]},x:{type:'number',minimum:0},y:{type:'number',minimum:0}}],
 ['android_vscreen_type','通过虚拟屏输入目标输入文字；中文走无障碍。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},text:{type:'string',maxLength:2000}}],
 ['android_vscreen_key','发送返回、主页、确认或退格键。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},keycode:{type:'integer',enum:[3,4,66,67]}}],
 ['android_vscreen_tree','读取虚拟屏无障碍控件树；返回 nodeId 供后续操作。',{}],
 ['android_vscreen_node','通过 nodeId 做语义操作；不受像素帧率影响，nodeId 用一次即失效。',{generation:{type:'string',maxLength:80},frameSeq:{type:'integer',minimum:1},nodeId:{type:'string',maxLength:80},action:{type:'string',enum:['click','long_click','scroll_forward','scroll_backward','focus','set_text']},text:{type:'string',maxLength:16000}}],
 ['android_vscreen_editor','读取或修改虚拟屏当前输入框；op 为 get/edit/submit。',{generation:{type:'string',maxLength:80},op:{type:'string',enum:['get','edit','submit']},editorId:{type:'string',maxLength:160},text:{type:'string',maxLength:16000},start:{type:'integer',minimum:0},end:{type:'integer',minimum:0}}],
 ['android_vscreen_close','关闭并回收虚拟屏。',{}]
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
  if(rule.type==='string'&&(typeof v!=='string'||(rule.maxLength!==undefined&&v.length>rule.maxLength)))throw Error('INVALID_'+key);
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
async function call(name,args,signal,dependencies={}){
 const request=requestFor(name,args);
 const operation=request.route.split('?')[0].split('/').pop();
 const mutating=['tap','swipe','touch','type','key','node','edit','submit','anchored-click'].includes(operation);
 try{
  const {route,...init}=request;
  const response=await (dependencies.bridgeFetch||bridgeFetch)(route,{...init,signal});
  const value=decodeBridge(await response.text());const{previewB64,...metadata}=value;const content=[{type:'text',text:JSON.stringify(metadata)}];
  if(name==='android_vscreen_see'&&previewB64)content.push({type:'image',mimeType:'image/jpeg',data:previewB64});
  return{content,isError:!response.ok||bridgeFailed(value)};
 }catch(error){
  if(operation==='anchor'&&error.executionUnknown!==false)return{content:[{type:'text',text:JSON.stringify({ok:false,error:'ANCHOR_RESULT_UNKNOWN',selectionUnknown:true,noReplay:true,sourceFrameSeq:args.frameSeq})}],isError:true};
  if(!mutating||error.executionUnknown===false)throw error;
  // 取消请求或丢失回执不能证明写动作尚未执行。
  return{content:[{type:'text',text:JSON.stringify({ok:false,error:'INPUT_RESULT_UNKNOWN',executionUnknown:true,...(operation==='anchored-click'?{noReplay:true,mode:'ANCHORED_NODE',sourceFrameSeq:args.frameSeq}:{})})}],isError:true};
 }
}
function start(){const active=new Map(),cancelled=new Set();let buffer='',queue=Promise.resolve(),ended=false,pending=0;const send=m=>process.stdout.write(JSON.stringify({jsonrpc:'2.0',...m})+'\n');async function receive(m){const{id,method,params={}}=m;if(method==='notifications/cancelled'){if(active.has(params.requestId))active.get(params.requestId).abort();else if(cancelled.size<128)cancelled.add(params.requestId);return;}if(id===undefined)return;if(ended||cancelled.delete(id)){send({id,error:{code:-32800,message:'Request cancelled'}});return;}try{let result;if(method==='initialize')result={protocolVersion:params.protocolVersion||'2025-03-26',capabilities:{tools:{listChanged:false}},serverInfo:{name:'DSHA Virtual Screen',version:'0.2.1'},instructions:'Read create/status first. For every existing-Core operation pass its authenticated coreInstance UUID explicitly. Keep coreInstance, generation and frame/node identity from the same observation; never upgrade an old observation with a newer Core identity. Close requires that Core identity while a live or pending Core exists. Observe before input. Legacy tap/swipe/touch use logical display coordinates and require the latest observed frame. The displayed JPEG has previewWidth by previewHeight pixels; if these differ from width and height, convert image x/y by width/previewWidth and height/previewHeight before an explicit coordinate action. An anchoredAvailable=true observation returns its retained full-resolution source image, so its image coordinates directly match the anchor display coordinates. Touch supports down/move/up. Use accessibility tree/editor for app controls and Unicode text. Semantic node actions do not require frameSeq. When see returns anchoredAvailable=true and the original image has one actionable button, prefer explicit android_vscreen_anchor then android_vscreen_anchored_click; never try a legacy tap first or fall back between these modes. Anchor x/y are full-resolution integer coordinates from that original image, not normalized or preview coordinates. Keep the original coreInstance/generation/frameSeq/observationId and returned anchorId together; never refresh the frame or upgrade old evidence with current status. Selection executes no click, does not renew TTL, and ANCHOR_ALREADY_ISSUED never returns the old ID. A lost selection reply is not permission to reselect or take a hidden screenshot. Anchored click consumes its anchor once; UNKNOWN/noReplay never restores it. Its sourceFrameSeq stays original and validatedCurrentSeq reports the actual later validation frame. If anchoring is unavailable, Canvas or ambiguous, choose an explicit semantic tree/node operation or a new observation; do not automatically retry. Keep anchor selection and click on the same observation; after a business action take a new explicit observation before deciding the next action. A cleanupPending, CLOSE_UNKNOWN, START_UNKNOWN or DISCONNECTED result is not permission to recreate automatically; inspect status first. An executionUnknown result or targetChangedAfterExecution/runChangedAfterExecution marker must never be replayed automatically; observe the real state first.'};else if(method==='ping')result={};else if(method==='tools/list')result={tools};else if(method==='tools/call'){const ac=new AbortController();active.set(id,ac);try{result=await call(params.name,params.arguments||{},ac.signal);}finally{active.delete(id);}}else{send({id,error:{code:-32601,message:'Method not found'}});return;}send({id,result});}catch(error){send({id,result:{content:[{type:'text',text:'Virtual screen unavailable: '+(error.message||'OPERATION_UNAVAILABLE')}],isError:true}});}}
process.stdin.setEncoding('utf8');process.stdin.on('data',chunk=>{buffer+=chunk;if(buffer.length>1024*1024)process.exit(1);let end;while((end=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,end);buffer=buffer.slice(end+1);if(!line.trim())continue;let value;try{value=JSON.parse(line);}catch{send({id:null,error:{code:-32700,message:'Parse error'}});continue;}if(value.method==='notifications/cancelled'){void receive(value);continue;}if(pending>=32){send({id:value.id??null,error:{code:-32000,message:'Request queue full'}});continue;}pending++;queue=queue.then(()=>receive(value)).finally(()=>pending--);}});process.stdin.on('end',()=>{ended=true;for(const ac of active.values())ac.abort();});process.stdout.on('error',e=>{if(e.code==='EPIPE')process.exit(0);});}
if(require.main===module)start();module.exports={definitions,tools,validate,call,requestFor,decodeBridge,bridgeFailed};

