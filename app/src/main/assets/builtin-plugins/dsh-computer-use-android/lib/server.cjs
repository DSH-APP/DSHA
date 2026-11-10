'use strict';
// MCP bridges Android screen actions and the native bridge's restricted image reader.
const fs=require('node:fs/promises');
const bridgePort=require('./bridge-port.cjs');
const MAX_SCREENSHOT_BYTES=16*1024*1024;
const MAX_FILE_IMAGE_BYTES=8*1024*1024;

const observationId={type:'string',minLength:1,maxLength:64};
const point={observationId,x:{type:'integer',minimum:0},y:{type:'integer',minimum:0}};
// 动作成功后默认随回执返回同一请求生成的新观察；observe:false 可关。坐标手势仍然不返回截图。
const observeTools=new Set(['android_node','android_type','android_key','android_click','android_double_click','android_long_press','android_swipe']);
const OBSERVE_RULE={type:'boolean'};
const definitions=[
  ['android_control','仅实体主屏：在用户明确要求操控实体手机时开始或结束当前对话的手机任务；虚拟屏工具不需要也不调用它。begin由传输层绑定当前真实会话与轮次（不由模型传入、不回退到全局活动任务、不新建聊天）并显示原生执行状态；不授予系统权限或自动同意确认。先begin，再启动应用或读屏操作；需要观察时状态窗自动隐藏，旧观察失效。任务完成或放弃时调用一次finish，关闭本轮状态界面与控制权，不取消模型对话；finish后本轮不能重新begin，也不能用于重试UNKNOWN动作。普通讨论、引用、翻译和未明确要求执行时不要调用。','/app/ui/task-begin',{action:{type:'string',enum:['begin','finish']}}],
  ['android_get_state','读取实体主屏（display 0）当前语义结构，返回treeId、nodeId和supportedActions；用android_node对新鲜节点执行语义动作，包括scroll_forward/scroll_backward。此树不签发坐标触控凭据，零面积bounds不能当触点。动作成功后默认随回执返回新观察，通常不必再单独读屏；虚拟屏用 android_vscreen_*。','/app/ui/dump',{}],
  ['android_history','读取实体主屏（display 0）手机任务的屏幕活动历史：目标切换、用户点按/滚动、内容变化与助手动作。记录只在手机任务活动期间存在，约 120 秒 / 64 条，任务结束、暂停或无障碍断开立即清空；不落盘、不返回图片（frames 为空数组）。since 传上一次返回的 nextSince 增量续读（事件序号，不是毫秒时间戳），limit 默认 20、最大 100；任务未开始时 events 为空数组。每个 /app/ui/* 回执的 recentActivity 已自动附带最近活动，通常不必单独轮询此工具。','/app/ui/history',{since:{type:'integer',minimum:0,maximum:9007199254740991},limit:{type:'integer',minimum:1,maximum:100}}],
  ['android_node','对实体主屏（display 0）最近android_get_state或上一次动作回执里 observation 的treeId/nodeId执行supportedActions中的语义动作；scroll_forward/scroll_backward调用真实无障碍节点滚动，不需要截图坐标。节点凭据限当前任务/窗口/代次、30秒、一次使用。动作成功后默认随回执返回同一请求生成的新观察（新 treeId/nodeId 与 settle 状态），可直接继续操作；观察不可用时按 observationUnavailable 重新读屏。Android接受不代表界面已改变；失败或UNKNOWN不得自动转坐标手势或重放。observe=false 关闭自动观察。','/app/ui/node',{treeId:{type:'string',minLength:1,maxLength:64},nodeId:{type:'string',minLength:1,maxLength:64},action:{type:'string',enum:['click','long_click','scroll_forward','scroll_backward','focus']},observe:OBSERVE_RULE}],
  ['android_screenshot','截取实体主屏（display 0），返回图片和JSON观察metadata。仅coordinatesAvailable:true且含observationId才可显式坐标触控；凭据限最新帧、30秒内、一次使用。需要系统截屏能力和DSHA授权。','/app/ui/screenshot',{}],
  ['android_read_image','读取现有的容器或当前用户共享存储静态 PNG、JPEG 或 WebP 图片，返回图片本身，文件上限 8 MiB。动图和 GIF 不支持；遵循桥路径、凭据和系统权限限制；不产生截图观察凭据，也不能授权坐标触控。','/app/readfile',{path:{type:'string',minLength:1,maxLength:4096}}],
  ['android_click','真实触控点击实体主屏（display 0）。observationId与坐标必须来自最近一次android_screenshot，不能用dump/tree替代。完成后默认返回新树观察（不含截图）；需要坐标的下一步仍须重新 android_screenshot。observe=false 关闭自动观察。','/app/ui/tap',{...point,observe:OBSERVE_RULE}],
  ['android_double_click','真实触控双击实体主屏同一位置（一次原子手势，不是失败重试）。使用最新截图observationId。完成后默认返回新树观察（不含截图）；需要坐标的下一步仍须重新 android_screenshot。observe=false 关闭自动观察。','/app/ui/tap',{...point,observe:OBSERVE_RULE}],
  ['android_long_press','真实触控长按实体主屏。使用最新截图observationId，ms限定500至5000。完成后默认返回新树观察（不含截图）；需要坐标的下一步仍须重新 android_screenshot。observe=false 关闭自动观察。','/app/ui/tap',{...point,ms:{type:'integer',minimum:500,maximum:5000},observe:OBSERVE_RULE}],
  ['android_type','向实体主屏（display 0）已聚焦输入框写入文字。涉及敏感操作时先取得用户确认。完成后默认返回新树观察。observe=false 关闭自动观察。','/app/ui/input',{text:{type:'string',maxLength:2000},observe:OBSERVE_RULE}],
  ['android_key','向实体主屏（display 0）发送 Android 导航键。完成后默认返回新树观察；窗口切换时观察不可用属正常，按 nextAction 重新读屏。observe=false 关闭自动观察。','/app/ui/key',{name:{type:'string',enum:['back','home','recents','notifications','quicksettings']},observe:OBSERVE_RULE}],
  ['android_swipe','真实触控滑动实体主屏。observationId与整条路径必须来自最新截图可用区域，不能用dump/tree替代。完成后默认返回新树观察（不含截图）；需要坐标的下一步仍须重新 android_screenshot。observe=false 关闭自动观察。','/app/ui/swipe',{observationId,x1:{type:'integer',minimum:0},y1:{type:'integer',minimum:0},x2:{type:'integer',minimum:0},y2:{type:'integer',minimum:0},ms:{type:'integer',minimum:50,maximum:3000},observe:OBSERVE_RULE}]
];
const OPTIONAL_FIELDS=new Set(['observe','since','limit']);
const tools=definitions.map(([name,description,,properties])=>({name,description,inputSchema:{type:'object',properties,required:Object.keys(properties).filter(key=>!OPTIONAL_FIELDS.has(key)),additionalProperties:false}}));
function validate(def,args){if(!args||typeof args!=='object'||Array.isArray(args))throw Error('INVALID_ARGUMENTS');const fields=def[3];if(Object.keys(args).some(k=>!Object.hasOwn(fields,k)))throw Error('UNKNOWN_ARGUMENT');for(const [key,rule]of Object.entries(fields)){const v=args[key];if(v===undefined)continue;if(rule.type==='boolean'&&typeof v!=='boolean')throw Error('INVALID_'+key);if(rule.type==='integer'&&(!Number.isInteger(v)||v<rule.minimum||rule.maximum!==undefined&&v>rule.maximum))throw Error('INVALID_'+key);if(rule.type==='string'&&(typeof v!=='string'||rule.minLength!==undefined&&v.length<rule.minLength||rule.maxLength&&v.length>rule.maxLength||rule.enum&&!rule.enum.includes(v)))throw Error('INVALID_'+key);}}
// 只识别完整原生错误；读屏正文可能包含终端日志里的 [ERR] 等普通文字。
function bridgeFailed(result){
  if(/^\s*(?:\[ERR\]|\[?(?:POLICY_BLOCKED|EXECUTION_UNKNOWN|NO_PERMISSION|DISABLED|UNAUTHORIZED|STOPPED)\b)/.test(result))return true;
  // ScreenReply carries execution facts as JSON. Preserve it verbatim: UNKNOWN
  // cannot become a successful tool result or permission to repeat the action.
  try{const value=JSON.parse(result);return value!==null&&typeof value==='object'&&!Array.isArray(value)
    &&value.ok===false&&['NOT_SENT','REJECTED','PARTIAL','UNKNOWN'].includes(value.state)
    &&typeof value.error==='string'&&value.error===value.code;}catch{return false;}
}
// Native capture returns the freshly generated PNG directly: the guest does not
// necessarily mount the current Android user's /storage/emulated/<id> directory.
function screenshotContent(result){let shot;try{shot=JSON.parse(result);}catch{throw Error('SCREENSHOT_RESPONSE_INVALID');}
  if(shot?.kind!=='dsha-screenshot-v1'||shot.mimeType!=='image/png'||typeof shot.data!=='string')throw Error('SCREENSHOT_RESPONSE_INVALID');
  if(shot.data.length>Math.ceil(MAX_SCREENSHOT_BYTES/3)*4)throw Error('SCREENSHOT_TOO_LARGE');
  const png=Buffer.from(shot.data,'base64');
  if(png.toString('base64')!==shot.data)throw Error('SCREENSHOT_RESPONSE_INVALID');
  if(png.length>MAX_SCREENSHOT_BYTES)throw Error('SCREENSHOT_TOO_LARGE');
  if(png.length<8||!png.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])))throw Error('SCREENSHOT_RESPONSE_INVALID');
  // Preserve the native observation credential and its restrictions beside the image.
  // Do not invent an id, keep hidden latest-frame state, or auto-capture/replay input.
  const {data,...metadata}=shot;
  return [{type:'text',text:JSON.stringify(metadata)},{type:'image',mimeType:'image/png',data:shot.data}];
}
function fileImageContent(result){let value;try{value=JSON.parse(result);}catch{throw Error('IMAGE_RESPONSE_INVALID');}
  if(value?.kind!=='dsha-file-image-v1'||typeof value.data!=='string'
    ||!['image/png','image/jpeg','image/webp'].includes(value.mimeType)
    ||value.data.length>Math.ceil(MAX_FILE_IMAGE_BYTES/3)*4)throw Error('IMAGE_RESPONSE_INVALID');
  const bytes=Buffer.from(value.data,'base64');
  if(bytes.length>MAX_FILE_IMAGE_BYTES||bytes.toString('base64')!==value.data)throw Error('IMAGE_RESPONSE_INVALID');
  const png=bytes.length>=8&&bytes.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10]));
  const jpeg=bytes.length>=3&&bytes[0]===255&&bytes[1]===216&&bytes[2]===255;
  const webp=bytes.length>=12&&bytes.subarray(0,4).toString('latin1')==='RIFF'&&bytes.subarray(8,12).toString('latin1')==='WEBP';
  if(!({'image/png':png,'image/jpeg':jpeg,'image/webp':webp})[value.mimeType])throw Error('IMAGE_RESPONSE_INVALID');
  // A stored image is not a fresh physical-screen observation and carries no observation ID.
  return [{type:'text',text:JSON.stringify({kind:'dsha-file-image-v1',mimeType:value.mimeType})},
    {type:'image',mimeType:value.mimeType,data:value.data}];
}
function validateTask(task){
  if(!task||typeof task!=='object'||Array.isArray(task)
    ||typeof task.sessionId!=='string'||!task.sessionId.trim()||task.sessionId.length>256||task.sessionId.includes('\0')
    ||typeof task.turnId!=='string'||!/^[1-9][0-9]{0,15}$/.test(task.turnId)||!Number.isSafeInteger(Number(task.turnId)))
    throw Error('SCREEN_TASK_IDENTITY_REQUIRED');
  if(task.completedTurnId!==undefined&&(typeof task.completedTurnId!=='string'
    ||!/^[1-9][0-9]{0,15}$/.test(task.completedTurnId)||!Number.isSafeInteger(Number(task.completedTurnId))
    ||Number(task.completedTurnId)>=Number(task.turnId)))throw Error('SCREEN_TURN_INVALID');
  return task;
}
async function call(name,args,signal,dependencies={fs,fetch:globalThis.fetch},task){const def=definitions.find(t=>t[0]===name);if(!def)throw Error('UNKNOWN_TOOL');validate(def,args);const fileImage=name==='android_read_image';if(fileImage&&args.path.includes('\0'))throw Error('INVALID_path');if(!fileImage)validateTask(task);
  const token=(await dependencies.fs.readFile('/root/.dsh/.bridge_token','utf8')).trim();if(!token)throw Error('BRIDGE_NOT_READY');
  const route=name==='android_control'&&args.action==='finish'?'/app/ui/task-end':def[2];
  const url=new URL('http://127.0.0.1:'+bridgePort()+route);for(const [k,v]of Object.entries(args)){if(name==='android_control'&&k==='action')continue;url.searchParams.set(k,String(v));}
  // 动作即观察：默认请求同一次动作回执附带新观察；模型显式 observe:false 时只回动作。
  if(observeTools.has(name)&&args.observe===undefined)url.searchParams.set('observe','1');
  if(!fileImage){url.searchParams.set('taskSessionId',task.sessionId);url.searchParams.set('turnId',task.turnId);
  if(task.completedTurnId!==undefined)url.searchParams.set('completedTurnId',task.completedTurnId);}
  if(name==='android_screenshot')url.searchParams.set('format','mcp');
  if(fileImage)url.searchParams.set('format','image');
  if(name==='android_double_click')url.searchParams.set('gesture','double_tap');
  if(name==='android_long_press')url.searchParams.set('gesture','long_press');
  const response=await dependencies.fetch(url,{signal,headers:{'X-Token':token}});if(!response.ok)throw Error('BRIDGE_HTTP_'+response.status);
  const raw=await readBridgeResponse(response,name==='android_screenshot'?24*1024*1024:fileImage?12*1024*1024:2*1024*1024);let result=raw;try{const parsed=JSON.parse(raw);result=typeof parsed.result==='string'?parsed.result:raw;}catch{}
  const failed=bridgeFailed(result)||(fileImage&&/^(?:FORBIDDEN|NOT_FOUND_OR_NO_PERMISSION|NOT_IMAGE|INVALID_IMAGE|ANIMATED_OR_INVALID_IMAGE|IMAGE_TOO_LARGE|IMAGE_BUSY|BAD_FORMAT|ERROR):?\b/.test(result));
  const content=name==='android_screenshot'&&!failed?screenshotContent(result):fileImage&&!failed?fileImageContent(result):[{type:'text',text:result}];
  return {content,isError:failed};
}
async function readBridgeResponse(response,limit){
  const advertised=Number(response.headers.get('content-length'));
  if(Number.isFinite(advertised)&&advertised>limit){await response.body?.cancel();throw Error('BRIDGE_RESPONSE_TOO_LARGE');}
  if(!response.body) return '';
  const reader=response.body.getReader(),parts=[];let size=0;
  try{for(;;){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>limit){await reader.cancel();throw Error('BRIDGE_RESPONSE_TOO_LARGE');}parts.push(value);}}
  finally{reader.releaseLock();}
  return Buffer.concat(parts,size).toString('utf8');
}
function startServer(dependencies){
const active=new Map(),cancelled=new Set();let buffer='',queue=Promise.resolve(),ended=false,pending=0;
const send=message=>process.stdout.write(JSON.stringify({jsonrpc:'2.0',...message})+'\n');
async function receive(message){const {id,method,params={}}=message;if(method==='notifications/cancelled'){if(active.has(params.requestId))active.get(params.requestId).abort();else if(cancelled.size<128)cancelled.add(params.requestId);return;}if(id===undefined)return;if(ended||cancelled.delete(id)){send({id,error:{code:-32800,message:'Request cancelled before execution'}});return;}
  try{let result;
    if(method==='initialize')result={protocolVersion:params.protocolVersion||'2025-03-26',capabilities:{tools:{listChanged:false}},serverInfo:{name:'DSHA Android Computer Use',version:'0.1.0'},instructions:'本服务只操作实体主屏（display 0），存在独立虚拟屏时也不会转过去；虚拟屏用 mcp__dsha-vscreen__*，失败时不拿本服务兜底。只在用户明确要求操控实体手机时开始：任何手机动作（包括启动应用）前先调用 android_control begin，完成或放弃时在收尾调用一次 android_control finish（细则见该工具描述）；必要的 Android 权限与原生确认仍然生效。begin 后原生任务窗口在首次读屏前隐藏，直到任务结束或用户暂停；输入前若该窗口可见状态变了，先取新观察再明确决定，不暗中重试。两类动作凭据互不通用：语义动作用 android_get_state 的 treeId/nodeId 调 android_node（含 scroll_forward/scroll_backward，走真实无障碍节点动作，不需要截图坐标），凭据绑定任务、目标与代次，30 秒内一次有效；坐标触控（点击、双击、长按、单指滑动，不提供多指）只认最新 android_screenshot 中 coordinatesAvailable:true 的 observationId，30 秒内一次手势有效，读屏树不能授权坐标，像素、目标、代次、遮挡与边界检查照常执行。节点动作被拒或 UNKNOWN 时不自动改用坐标手势、不重放；之后若改用截图坐标，须重新截图并明确选择。Android 接受无障碍动作不代表 WebView 页面已变，effectVerified:false 时要核对实际界面；动作工具默认随回执返回同一请求生成的新观察（observation：新 treeId/nodeId 与 settle 状态，坐标动作不含截图），可直接继续操作；观察不可用（observationUnavailable / TARGET_CHANGED）或状态 UNKNOWN 时才重新读屏，observe:false 可关闭自动观察。不要重放 UNKNOWN 动作。'};
    else if(method==='ping')result={};
    else if(method==='tools/list')result={tools};
    else if(method==='tools/call'){const controller=new AbortController();active.set(id,controller);try{result=await call(params.name,params.arguments||{},controller.signal,dependencies,params._meta?.['dsha/phone-task']);}finally{active.delete(id);}}
    else{send({id,error:{code:-32601,message:'Method not found'}});return;}
    send({id,result});
  }catch(error){send({id,result:{content:[{type:'text',text:'Android operation unavailable: '+String(error.code||(/^[A-Z][A-Za-z0-9_]{0,60}$/.test(error.message)?error.message:'OPERATION_UNAVAILABLE'))+'. Review the native error or arguments. Coordinate input requires a fresh android_screenshot observationId; dump is not sufficient. If execution may have started, observe the screen and never automatically replay.'}],isError:true}});}
}
process.stdin.setEncoding('utf8');process.stdin.on('data',chunk=>{buffer+=chunk;if(buffer.length>1024*1024){process.stderr.write('MCP_INPUT_LIMIT\n');process.exit(1);}let end;while((end=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,end);buffer=buffer.slice(end+1);if(!line.trim())continue;let value;try{value=JSON.parse(line);}catch{send({id:null,error:{code:-32700,message:'Parse error'}});continue;}if(value.method==='notifications/cancelled')void receive(value);else if(pending>=32)send({id:value.id??null,error:{code:-32000,message:'Request queue full'}});else{pending++;queue=queue.then(()=>receive(value)).finally(()=>pending--);}}});
process.stdin.on('end',()=>{ended=true;for(const controller of active.values())controller.abort();});
process.stdout.on('error',error=>{if(error.code==='EPIPE')process.exit(0);});
}
if(require.main===module)startServer();
module.exports={validate,validateTask,definitions,tools,bridgeFailed,screenshotContent,fileImageContent,readBridgeResponse,call,startServer};
