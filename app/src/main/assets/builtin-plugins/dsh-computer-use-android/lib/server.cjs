'use strict';
// MCP 只桥接已存在的 Android 设备接口；不暴露 shell、凭据读取或任意文件访问。
const fs=require('node:fs/promises'),path=require('node:path');
const definitions=[
  ['android_get_state','读取当前 Android 页面结构。操作前读取，操作后再次验证。','/app/ui/dump',{}],
  ['android_screenshot','截取当前屏幕并返回图片。需要系统截屏能力和 DSHA 授权。','/app/ui/screenshot',{}],
  ['android_click','点击当前页面上的坐标；坐标必须来自最近一次观察。','/app/ui/tap',{x:{type:'integer',minimum:0},y:{type:'integer',minimum:0}}],
  ['android_type','向已聚焦输入框写入文字。涉及敏感操作时先取得用户确认。','/app/ui/input',{text:{type:'string',maxLength:2000}}],
  ['android_key','按下 Android 导航键。','/app/ui/key',{name:{type:'string',enum:['back','home','recents','notifications','quicksettings']}}],
  ['android_swipe','在观察过的页面上滑动。','/app/ui/swipe',{x1:{type:'integer',minimum:0},y1:{type:'integer',minimum:0},x2:{type:'integer',minimum:0},y2:{type:'integer',minimum:0},ms:{type:'integer',minimum:50,maximum:3000}}]
];
const tools=definitions.map(([name,description,,properties])=>({name,description,inputSchema:{type:'object',properties,required:Object.keys(properties),additionalProperties:false}}));
function validate(def,args){if(!args||typeof args!=='object'||Array.isArray(args))throw Error('INVALID_ARGUMENTS');const fields=def[3];if(Object.keys(args).some(k=>!Object.hasOwn(fields,k)))throw Error('UNKNOWN_ARGUMENT');for(const [key,rule]of Object.entries(fields)){const v=args[key];if(rule.type==='integer'&&(!Number.isInteger(v)||v<rule.minimum||rule.maximum!==undefined&&v>rule.maximum))throw Error('INVALID_'+key);if(rule.type==='string'&&(typeof v!=='string'||rule.maxLength&&v.length>rule.maxLength||rule.enum&&!rule.enum.includes(v)))throw Error('INVALID_'+key);}}
// 只识别桥返回的错误前缀；读屏正文可能包含终端日志里的 [ERR] 等普通文字。
function bridgeFailed(result){return /^\s*(?:\[ERR\]|\[?(?:POLICY_BLOCKED|EXECUTION_UNKNOWN|NO_PERMISSION|DISABLED)\b)/.test(result);}
// /sdcard 与 /storage/emulated/0 在 proot 中是两个独立挂载别名；各自校验同一允许目录。
function screenshotTarget(result){const match=result.match(/\/(?:storage\/emulated\/[0-9]+|sdcard)\/(?:Download\/DSHA|Android\/data\/com\.dsh\.client\/files\/Pictures\/DSHA)\/screen-[a-zA-Z0-9-]+\.png/);if(!match)return null;
  return {file:match[0],base:path.dirname(match[0])};}
async function call(name,args,signal){const def=definitions.find(t=>t[0]===name);if(!def)throw Error('UNKNOWN_TOOL');validate(def,args);
  const token=(await fs.readFile('/root/.dsh/.bridge_token','utf8')).trim();if(!token)throw Error('BRIDGE_NOT_READY');
  const url=new URL('http://127.0.0.1:3090'+def[2]);url.searchParams.set('token',token);for(const [k,v]of Object.entries(args))url.searchParams.set(k,String(v));
  const response=await fetch(url,{signal});if(!response.ok)throw Error('BRIDGE_HTTP_'+response.status);
  const raw=await response.text();let result=raw;try{const parsed=JSON.parse(raw);result=typeof parsed.result==='string'?parsed.result:raw;}catch{}
  const failed=bridgeFailed(result);
  const content=[{type:'text',text:result}];
  if(name==='android_screenshot'&&!failed){const target=screenshotTarget(result);if(target){
    const base=await fs.realpath(target.base),file=await fs.realpath(target.file);
    if(path.dirname(file)!==base)throw Error('SCREENSHOT_PATH_REJECTED');const stat=await fs.stat(file);if(!stat.isFile()||stat.size>16*1024*1024)throw Error('SCREENSHOT_TOO_LARGE');
    content.push({type:'image',mimeType:'image/png',data:(await fs.readFile(file)).toString('base64')});
  }}
  return {content,isError:failed};
}
function startServer(){
const active=new Map(),cancelled=new Set();let buffer='',queue=Promise.resolve(),ended=false,pending=0;
const send=message=>process.stdout.write(JSON.stringify({jsonrpc:'2.0',...message})+'\n');
async function receive(message){const {id,method,params={}}=message;if(method==='notifications/cancelled'){if(active.has(params.requestId))active.get(params.requestId).abort();else if(cancelled.size<128)cancelled.add(params.requestId);return;}if(id===undefined)return;if(ended||cancelled.delete(id)){send({id,error:{code:-32800,message:'Request cancelled before execution'}});return;}
  try{let result;
    if(method==='initialize')result={protocolVersion:params.protocolVersion||'2025-03-26',capabilities:{tools:{listChanged:false}},serverInfo:{name:'DSHA Android Computer Use',version:'0.1.0'},instructions:'Use a fresh Android observation before actions and verify afterwards. Respect native permission and confirmation results. Never replay an action whose outcome is unknown.'};
    else if(method==='ping')result={};
    else if(method==='tools/list')result={tools};
    else if(method==='tools/call'){const controller=new AbortController();active.set(id,controller);try{result=await call(params.name,params.arguments||{},controller.signal);}finally{active.delete(id);}}
    else{send({id,error:{code:-32601,message:'Method not found'}});return;}
    send({id,result});
  }catch(error){send({id,result:{content:[{type:'text',text:'Android operation unavailable: '+String(error.code||(/^[A-Z][A-Za-z0-9_]{0,60}$/.test(error.message)?error.message:'OPERATION_UNAVAILABLE'))+'. Check DSHA device permissions. If execution may have started, verify the screen before retrying.'}],isError:true}});}
}
process.stdin.setEncoding('utf8');process.stdin.on('data',chunk=>{buffer+=chunk;if(buffer.length>1024*1024){process.stderr.write('MCP_INPUT_LIMIT\n');process.exit(1);}let end;while((end=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,end);buffer=buffer.slice(end+1);if(!line.trim())continue;let value;try{value=JSON.parse(line);}catch{send({id:null,error:{code:-32700,message:'Parse error'}});continue;}if(value.method==='notifications/cancelled')void receive(value);else if(pending>=32)send({id:value.id??null,error:{code:-32000,message:'Request queue full'}});else{pending++;queue=queue.then(()=>receive(value)).finally(()=>pending--);}}});
process.stdin.on('end',()=>{ended=true;for(const controller of active.values())controller.abort();});
process.stdout.on('error',error=>{if(error.code==='EPIPE')process.exit(0);});
}
if(require.main===module)startServer();
module.exports={validate,definitions,tools,bridgeFailed,screenshotTarget};
