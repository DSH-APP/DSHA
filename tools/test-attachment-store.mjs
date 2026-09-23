import assert from 'node:assert/strict';
import { mkdtemp,mkdir,writeFile,readFile,rm,link,symlink,lstat,readdir,chmod,statfs } from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
const nm=process.env.DSHA_TEST_NODE_MODULES||'/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules';
const {LocalAttachmentStore,saveImageFile,readImageFile}=await import(pathToFileURL(join(nm,'@deepseek-ai/dsh-attachment-local/lib/index.js')));
const sharp=createRequire(join(nm,'../package.json'))('sharp');
const parent=process.argv[2];assert.ok(parent&&parent.startsWith('/'),'必须提供隔离测试目录');await mkdir(parent,{recursive:true});
const physical=await mkdtemp(join(parent,'dsha-attachment-audit-'));let home=physical,checks=0;
if(process.argv[3]){await mkdir(process.argv[3],{recursive:true});home=await mkdtemp(join(process.argv[3],'dsha-linked-home-'));await symlink(physical,join(home,'attachments'));}
function check(value,message){checks++;assert.ok(value,message);}
try{
 const root=join(home,'attachments/v1');const store=Object.create(LocalAttachmentStore.prototype);store.root=root;
 const raw=Buffer.from('完整文件\nattachment bytes\n');const source=join(physical,'native-source'),nativeTarget=join(physical,'native-target');await writeFile(source,raw);
 let nativeResult;try{await link(source,nativeTarget);nativeResult=(await lstat(nativeTarget)).isSymbolicLink()?'symlink-emulation':'hardlink';}catch(e){nativeResult=e.code;}
 console.log('FILESYSTEM',JSON.stringify(await statfs(physical)),'NATIVE_LINK',nativeResult,'LINKED_HOME',home!==physical);
 const one=await store.saveFile({data:raw,name:'文件一.txt'}),two=await store.saveFile({data:raw,name:'alias-two.txt'});
 check(one.attachmentId===two.attachmentId,'相同字节应共用摘要对象');
 const hash=one.attachmentId.slice(7),object=join(root,'file-objects',hash.slice(0,2),hash);
 check((await readFile(object)).equals(raw),'不可变别名不得挪走源对象');
 for(const ref of [one,two]){const chunks=[];for await(const b of store.readFileStream(ref))chunks.push(b);check(Buffer.concat(chunks).equals(raw),'别名字节必须完整');}
 const streamed=await store.saveFileStream({name:'stream.bin',data:(async function*(){yield raw.subarray(0,7);yield raw.subarray(7)})()});check(streamed.bytes===raw.length,'分块文件完整落盘');
 const concurrent=await Promise.all(Array.from({length:5},(_,i)=>store.saveFile({data:raw,name:'race-'+i+'.txt'})));check(concurrent.every(r=>r.attachmentId===one.attachmentId),'并发别名一致');
 const image=await sharp({create:{width:8,height:6,channels:4,background:{r:30,g:100,b:220,alpha:1}}}).png().toBuffer();
 const limits={maxImageBytes:20971520,maxImagePixels:64000000,maxImageDimension:8192,mediaTypes:['image/png','image/jpeg','image/webp','image/gif']};
 const ref=await saveImageFile(root,{data:image,mediaType:'image/png',name:'图片.png'},limits,{maxPixels:4194304,maxDimension:8192,maxBytes:4194304});
 const restored=await readImageFile(root,ref);check(restored.data.length===ref.bytes&&ref.width===8&&ref.height===6,'图片必须完成解码、发布和回读');
 check((await readdir(join(root,'tmp'))).length===0,'暂存文件应被清理');
 const controller=new AbortController();controller.abort('owned cancellation');
 await assert.rejects(store.saveFileStream({name:'cancelled',data:(async function*(){yield raw})(),signal:controller.signal}),e=>e==='owned cancellation');checks++;
 await chmod(object,0o600);await writeFile(object,'corrupt-owned-object');
 await assert.rejects(store.saveFile({data:raw,name:'corruption.txt'}),e=>e.code==='ATTACHMENT_CORRUPT');checks++;
 check((await readFile(object,'utf8'))==='corrupt-owned-object','摘要冲突时不得覆盖已有内容');
 console.log('ATTACHMENT_AUDIT_PASS checks='+checks);
}finally{if(home!==physical)await rm(home,{recursive:true,force:true});await rm(physical,{recursive:true,force:true});}
