// End-to-end host transport fixture. DSHA_TEST_SDK_ROOT selects the pinned rc3 runtime.
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFileSync} from 'node:fs';
import {join,resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {deflateSync} from 'node:zlib';

const runtime=process.env.DSHA_TEST_SDK_ROOT;
if(!runtime)throw Error('DSHA_TEST_SDK_ROOT is required');
const owned=createRequire(join(resolve(runtime),'node_modules/@deepseek-ai/dsh-mcp-client/package.json'));
const {Client}=await import(pathToFileURL(owned.resolve('@modelcontextprotocol/client')));
const {StdioClientTransport}=await import(pathToFileURL(owned.resolve('@modelcontextprotocol/client/stdio')));
const recipe=JSON.parse(readFileSync('app/src/main/assets/mcp-image-base64-patch.json','utf8'));
const upstream=readFileSync(owned.resolve('@deepseek-ai/dsh-mcp-client'),'utf8');
const {before,after}=recipe.patches[0];
assert.equal(upstream.split(before).length,2);
assert.equal(upstream.includes(after),false);
// Resolve the exact patched module's static package imports from the pinned runtime;
// this keeps the installed cache and any generated runtime assets untouched.
const patched=upstream.replace(before,after).replace(/from "([^\"]+)"/g,(match,specifier)=>
  specifier.startsWith('node:')?match:`from "${pathToFileURL(owned.resolve(specifier)).href}"`);
const {createMcpToolDefinition}=await import('data:text/javascript;base64,'+Buffer.from(patched).toString('base64'));
const oldPattern=/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;
const newPattern=/^[A-Za-z0-9+/]*={0,2}$/;
const canonical=(pattern,value)=>pattern.test(value)&&Buffer.from(value,'base64').toString('base64')===value;
let cases=0;
function compare(value,depth){assert.equal(canonical(oldPattern,value),canonical(newPattern,value),JSON.stringify(value));cases++;
  if(depth)for(const ch of ['A','/','+','=',' ','-'])compare(value+ch,depth-1);}
compare('',6);
const plugin=resolve('app/src/main/assets/builtin-plugins/dsh-computer-use-android/lib/index.js');
assert.match(readFileSync(plugin,'utf8'),/maxBufferSize:\s*24\s*\*\s*1024\s*\*\s*1024/);

// A real 1x1 PNG with a valid ancillary chunk, exactly 8 MiB and slash-dense in base64.
const table=Array.from({length:256},(_,i)=>{let c=i;for(let j=0;j<8;j++)c=(c&1)?0xedb88320^(c>>>1):c>>>1;return c>>>0;});
function chunk(type,data){const name=Buffer.from(type,'ascii'), size=Buffer.alloc(4);size.writeUInt32BE(data.length);let c=0xffffffff;for(const x of name)c=table[(c^x)&255]^(c>>>8);for(const x of data)c=table[(c^x)&255]^(c>>>8);const crc=Buffer.alloc(4);crc.writeUInt32BE((c^0xffffffff)>>>0);return Buffer.concat([size,name,data,crc]);}
const header=Buffer.alloc(13);header.writeUInt32BE(1,0);header.writeUInt32BE(1,4);header[8]=8;header[9]=2;
const core=[Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',deflateSync(Buffer.from([0,0,0,0]))),chunk('IEND',Buffer.alloc(0))];
const padding=8*1024*1024-core.reduce((n,b)=>n+b.length,0)-12;
const extra=Buffer.alloc(padding,255);
const png=Buffer.concat([core[0],core[1],chunk('aaAa',extra),core[2],core[3]]);
assert.equal(png.length,8*1024*1024);
const base64=png.toString('base64');assert.equal(base64.length,Math.ceil(png.length/3)*4);
assert.ok(base64.includes('/'.repeat(10000)));
assert.equal(canonical(newPattern,base64),true);
assert.equal(canonical(newPattern,base64.slice(0,-1)+'!'),false);
const image={kind:'dsha-file-image-v1',mimeType:'image/png',data:base64};
const body=Buffer.from(JSON.stringify({result:JSON.stringify(image)}));
assert.equal(JSON.parse(JSON.parse(body.toString()).result).data,base64);
assert.ok(body.length<12*1024*1024);
let requests=0;
const http=createServer((req,res)=>{requests++;const url=new URL(req.url,'http://localhost');
  assert.equal(url.pathname,'/app/readfile');assert.equal(url.searchParams.get('format'),'image');
  assert.equal(url.searchParams.get('path'),'/sdcard/Download/example.png');assert.equal(req.headers['x-token'],'fixture-token');
  res.writeHead(200,{'Content-Type':'application/json','Content-Length':body.length});res.end(body);});
await new Promise(resolve=>http.listen(0,'127.0.0.1',resolve));
const server=resolve('app/src/main/assets/builtin-plugins/dsh-computer-use-android/lib/server.cjs');
const bootstrap="const s=require(process.env.DSHA_IMAGE_TEST_SERVER);s.startServer({fs:{async readFile(){return 'fixture-token';}},fetch:(url,opts)=>fetch('http://127.0.0.1:'+process.env.DSHA_IMAGE_TEST_PORT+url.pathname+url.search,opts)});";
const transport=new StdioClientTransport({command:process.execPath,args:['-e',bootstrap],maxBufferSize:24*1024*1024,
  env:{...process.env,DSHA_IMAGE_TEST_SERVER:server,DSHA_IMAGE_TEST_PORT:String(http.address().port)}});
const client=new Client({name:'dsha-image-wire-test',version:'1.0.0'},{capabilities:{}});
try{
  await client.connect(transport);
  let saved=0;
  const attachments={async saveImages(values){saved++;assert.equal(values.length,1);assert.equal(values[0].mediaType,'image/png');assert.deepEqual(values[0].data,png);return [{attachmentId:'fixture-image',mediaType:'image/png'}];}};
  const ctx={get(name){return name==='attachments'?attachments:name==='llm'?{async resolveModelInfo(){return {inputModalities:['image']};}}:undefined;}};
  const exec={signal:new AbortController().signal,agent:{session:{requestHeader(){return {config:{provider:'fixture',model:'image-capable'}};}}}};
  const tool=createMcpToolDefinition(ctx,{name:'image',rawName:'android_read_image',description:'fixture',inputSchema:{type:'object'},
    call(args){return client.callTool({name:'android_read_image',arguments:args},{timeout:30000});}});
  const value=await tool.execute({path:'/sdcard/Download/example.png'},exec);
  assert.equal(value.content[1].data,base64);assert.equal(requests,1);
  const projected=tool.projectContent(exec,{value,content:tool.output.render({},value),isError:false});
  if(saved!==1)throw Error('Image admission failed: '+JSON.stringify(projected));
  assert.equal(projected[1].type,'image');assert.equal(projected[1].attachment.attachmentId,'fixture-image');
  assert.equal(Object.hasOwn(JSON.parse(projected[0].text),'observationId'),false);
  console.log(`image wire PASS: png=${png.length} base64=${base64.length} HTTP=${body.length} bytes; ${cases} canonical cases; actual SDK stdio and exact-patched DSH image projection`);
}finally{await client.close();await new Promise(resolve=>http.close(resolve));}
