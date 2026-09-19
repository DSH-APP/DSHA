import {mkdirSync,writeFileSync,readFileSync,readdirSync,openSync,fsyncSync,closeSync} from 'node:fs';
import {join,basename} from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {createRequire} from 'node:module';
import {spawnSync} from 'node:child_process';
import {Session} from '@deepseek-ai/dsh-session';
export const inject=['webServer','connection','storage','storage.backend.json','sessionPersistence'];
const require=createRequire(import.meta.url);
function errorCode(error){return typeof error?.code==='string'?error.code:typeof error?.name==='string'?error.name:'UNKNOWN'}
function pathHash(path){return createHash('sha256').update(String(path||'')).digest('hex')}
function storageEntry(){try{return require.resolve('@deepseek-ai/dsh-storage-json')}catch{return ''}}
function storagePatch(entry){
 try{
  if(!entry)throw Object.assign(Error('unresolved'),{code:'UNRESOLVED'});const source=readFileSync(entry,'utf8');
  return {entryHash:pathHash(entry),marker:source.includes('DSHA_PROROOT_DIRENT_FALLBACK_V3')?'V3':source.includes('DSHA_PROROOT_DIRENT_FALLBACK_V2')?'V2':source.includes('DSHA_PROROOT_DIRENT_FALLBACK_V1')?'V1':'UPSTREAM',directRecordHints:source.includes('DSHA_PROROOT_DIRECT_RECORD_HINTS_V1')};
 }catch(error){return {entryHash:'unavailable',marker:'UNREADABLE_'+errorCode(error),directRecordHints:false}}
}
function directoryState(path,expected){
 try{const entries=readdirSync(path);return {read:'ok',count:entries.length,expected:entries.includes(expected)}}
 catch(error){return {read:'error_'+errorCode(error),count:-1,expected:false}}
}
function captureDirectories(diagnostic,unitDir,tableDir){diagnostic.unitDirectory=directoryState(unitDir,'items');diagnostic.tableDirectory=directoryState(tableDir,'probe.json')}
function failKv(message,diagnostic){throw Error(message+'; DSHA_TRIAL_KV_DIAG='+JSON.stringify(diagnostic))}
const freshReopenSource=`
import {pathToFileURL} from 'node:url';
import {join} from 'node:path';
const module=await import(pathToFileURL(process.env.DSHA_TRIAL_STORAGE_ENTRY).href);
const backend=new module.JsonStorageBackend(process.env.DSHA_TRIAL_STORAGE_ROOT);let unit;
try{
 const descriptor=JSON.parse(process.env.DSHA_TRIAL_STORAGE_DESCRIPTOR);
 // proroot may return ENOENT for directory enumeration in a new process even
 // though a direct record path is readable.  Transfer only the key that this
 // trial just published; the backend still reads/parses the on-disk document
 // and validates its nonce, so this does not turn an in-memory value into a
 // persistence pass.  Normal DSH processes keep their own backend hints.
 let hintMap={};try{hintMap=JSON.parse(process.env.DSHA_TRIAL_STORAGE_RECORD_KEYS||'{}')}catch{hintMap={}};
 const hints=backend.dshaRecordHints;
 if(hints&&typeof hints.reserve==='function')for(const table of descriptor.tables){
  const keys=Array.isArray(hintMap[table])?hintMap[table]:[];
  for(const key of keys)if(typeof key==='string'){
   const reservation=await hints.reserve(join(process.env.DSHA_TRIAL_STORAGE_ROOT,descriptor.name,table),key);
   hints.commit(reservation);
  }
 }
 unit=await backend.kv.open(descriptor);const state=await unit.loadAll();
 if(state?.tables?.items?.probe?.nonce!==process.env.DSHA_TRIAL_STORAGE_NONCE)process.exitCode=42;
 else process.stdout.write('DSHA_TRIAL_FRESH_REOPEN_OK\\n');
}finally{if(unit)await unit.close();await backend.close()}
`;
export async function apply(ctx){
 const nonce=process.env.DSHA_RUNTIME_TRIAL_NONCE,home=process.env.DSH_HOME;
 if(!nonce||!/^[a-f0-9]{32}$/.test(nonce)||!home)throw Error('Invalid isolated runtime trial');
 const file=join(home,'trial-data','probe.json');mkdirSync(join(home,'trial-data'),{recursive:true});
 const payload=JSON.stringify({nonce,kind:'dsha-owned-trial'});writeFileSync(file,payload,{flag:'wx',mode:0o600});const fd=openSync(file,'r+');try{fsyncSync(fd)}finally{closeSync(fd)};
 const read=readFileSync(file,'utf8');if(read!==payload)throw Error('Trial write/read mismatch');
 // 使用锁定版本的真实存储后端，关闭后重开，避免只验证普通文件 I/O 或内存值。
 const descriptor={name:'dsha_trial_'+nonce,version:1,tables:['items'],hasGlobal:false,layout:'per-record'};
 const backend=ctx.storage.backend.get('json'),expectedRoot=join(home,'storages'),backendRoot=typeof backend?.root==='string'?backend.root:'',entry=storageEntry();
 const unitDir=join(backendRoot||expectedRoot,descriptor.name),tableDir=join(unitDir,'items'),recordFile=join(tableDir,'probe.json');
 const diagnostic={backendRole:backend?.constructor?.name||'unknown',rootRole:backendRoot===expectedRoot?'dsh-home-storages':backendRoot?'unexpected':'unavailable',rootBase:basename(backendRoot||expectedRoot)==='storages'?'storages':'unexpected',rootHash:pathHash(backendRoot||expectedRoot),layout:descriptor.layout,tableRole:'items',recordRole:'probe.json',patch:storagePatch(entry),backendWrite:'not-attempted',recordRead:'not-attempted',recordBytesNonzero:false,recordParse:'not-attempted',recordJsonObject:false,recordVersion:'not-checked',recordNonce:'not-checked',unitDirectory:{read:'not-attempted',count:-1,expected:false},tableDirectory:{read:'not-attempted',count:-1,expected:false},backendReload:'not-attempted',freshProcess:{status:'not-attempted',stdoutBytes:0,stderrBytes:0}};
 if(diagnostic.rootRole!=='dsh-home-storages')failKv('Trial KV backend root mismatch',diagnostic);
 if(!entry)failKv('Trial KV storage module unavailable',diagnostic);
 let unit=await backend.kv.open(descriptor);
 try{await unit.putRecord('items','probe',{nonce});diagnostic.backendWrite='ok'}catch(error){diagnostic.backendWrite='error_'+errorCode(error);captureDirectories(diagnostic,unitDir,tableDir);failKv('Trial KV durable write failed',diagnostic)}finally{await unit.close()}
 let recordText,document;
 try{recordText=readFileSync(recordFile,'utf8');diagnostic.recordRead='ok';diagnostic.recordBytesNonzero=recordText.length>0}catch(error){diagnostic.recordRead='error_'+errorCode(error)}
 if(diagnostic.recordRead==='ok')try{document=JSON.parse(recordText);diagnostic.recordParse='ok';diagnostic.recordJsonObject=typeof document==='object'&&document!==null&&!Array.isArray(document)}catch(error){diagnostic.recordParse='error_'+errorCode(error)}
 diagnostic.recordVersion=document?.version===descriptor.version?'match':'mismatch';
 diagnostic.recordNonce=document?.record?.nonce===nonce?'match':'mismatch';
 if(diagnostic.recordRead!=='ok'||diagnostic.recordParse!=='ok'||diagnostic.recordVersion!=='match'||diagnostic.recordNonce!=='match'){captureDirectories(diagnostic,unitDir,tableDir);failKv('Trial KV durable record mismatch',diagnostic)}
 unit=await backend.kv.open(descriptor);try{
  let state;try{state=await unit.loadAll()}catch(error){diagnostic.backendReload='error_'+errorCode(error);captureDirectories(diagnostic,unitDir,tableDir);failKv('Trial KV reopen failed',diagnostic)}
  diagnostic.backendReload=state?.tables?.items?.probe?.nonce===nonce?'match':'mismatch';
  if(diagnostic.backendReload!=='match'){captureDirectories(diagnostic,unitDir,tableDir);failKv('Trial KV reopen mismatch',diagnostic)}
 }finally{await unit.close()}
 // 在 proroot 中 process.execPath 可能是宿主侧 bridge 的路径。把它再次
 // 交给 spawnSync 会让子进程脱离当前 guest 映射：入口仍能启动，但
 // `/root/.dsh-runtime-trial-…` 会被当成宿主绝对路径，目录读取得到
 // ENOENT，最终把真实持久化误报成 exit_42。使用受管 rootfs 内的 Node
 // 路径，让 proroot 为这次 exec 重新安装同一套 guest 映射；proot 下
 // 该路径也由 rootfs 直接解析。fresh-process 检查仍然是独立 Node 进程，
 // 这里只修复其启动边界，不放宽任何读取或 nonce 校验。
 const trialNode=process.platform==='win32'?process.execPath:'/usr/local/bin/node';
 const child=spawnSync(trialNode,['--input-type=module','--eval',freshReopenSource],{encoding:'utf8',timeout:20000,maxBuffer:64*1024,windowsHide:true,env:{...process.env,DSHA_TRIAL_STORAGE_ENTRY:entry,DSHA_TRIAL_STORAGE_ROOT:backendRoot,DSHA_TRIAL_STORAGE_DESCRIPTOR:JSON.stringify(descriptor),DSHA_TRIAL_STORAGE_NONCE:nonce,DSHA_TRIAL_STORAGE_RECORD_KEYS:JSON.stringify({items:['probe']})}});
 diagnostic.freshProcess={status:child.error?'spawn-error_'+errorCode(child.error):child.signal?'signal_'+child.signal:child.status!==0?'exit_'+child.status:child.stdout==='DSHA_TRIAL_FRESH_REOPEN_OK\n'?'match':'token-mismatch',stdoutBytes:typeof child.stdout==='string'?child.stdout.length:0,stderrBytes:typeof child.stderr==='string'?child.stderr.length:0};
 if(diagnostic.freshProcess.status!=='match'){captureDirectories(diagnostic,unitDir,tableDir);failKv('Trial KV fresh-process reopen mismatch',diagnostic)}
 // Session.create 的第一个参数是 id，不能把 id 当作唯一参数传入：那样
 // 会话 header 没有 cwd，DSH 的 sessionQuery 会在插件加载时把它判为
 // “session … not found”。用完整、可持久化的 header 建立真实会话，再
 // 通过持久化服务按同一 id 重开，验证跨进程以外的会话契约。
 const sessionId=randomUUID();
 const sample=Session.create(sessionId,[],{version:3,id:sessionId,createdAt:Date.now(),cwd:process.cwd(),isSeeded:false});
 const writer=await ctx.sessionPersistence.create(sample.header);
 try{await writer.flush()}finally{await writer.close()}
 const reader=await ctx.sessionPersistence.open(sample.id,'read');
 try{const state=await reader.read();if(reader.header.id!==sample.id||reader.header.version!==3||state.events.length!==0)throw Error('Trial session reopen mismatch')}finally{await reader.close()}
 const hash=createHash('sha256').update(read).digest('hex');let renderer=false,failure='';
 const endpoint='/dsha-runtime-trial/'+nonce;
 ctx.effect(()=>ctx.webServer.register({kind:'exact',path:endpoint,handler:async(req,res)=>{
  const rejection=ctx.connection.requestRejection(req);if(rejection!==undefined){res.writeHead(rejection);res.end();return}
  if(req.method==='POST'){
   let body='';for await(const chunk of req){body+=chunk;if(body.length>4096){res.writeHead(413);res.end();return}}
   try{const value=JSON.parse(body);if(value.nonce!==nonce)throw Error('nonce');if(value.failure)failure='TRIAL_RENDERER_FAILED';else renderer=value.ready===true&&value.transport===true;}catch{res.writeHead(400);res.end();return}
  }
  res.setHeader('content-type','application/json');res.setHeader('cache-control','no-store');res.end(JSON.stringify({nonce,pid:process.pid,port:ctx.webServer.port,dataRead:read===payload,dataWrite:true,storageReopened:true,storageFreshReopened:true,sessionReopened:true,hash,renderer,failure}));
 }}));
 const client=readFileSync(new URL('./page.js',import.meta.url),'utf8').replace('__DSHA_TRIAL_NONCE__',nonce).replace('__DSHA_TRIAL_HOME__',JSON.stringify(process.env.HOME));
 ctx.on('webserver/index-inject',rows=>rows.push({kind:'script',placement:'head',text:client}));
}
