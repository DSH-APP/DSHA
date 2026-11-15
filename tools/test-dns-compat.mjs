import {readFileSync, mkdtempSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {execFileSync} from 'node:child_process';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
import {promisify} from 'node:util';
const source='(function(){'+readFileSync(process.env.DSHA_DNS_COMPAT_SOURCE||'app/src/main/assets/dns-compat.cjs','utf8')+'\n})();';
function fixture(mode='auto', code='EAI_AGAIN') {
  const calls=[], error=Object.assign(new Error('getaddrinfo '+code),{code});
  let nativeSuccess=false, failFallback=false, syncs=0;
  function answer(host,options) {
    calls.push({host,options});
    if(nativeSuccess || (options?.family===4 && !failFallback)) {
      const address={address:options?.family===4?'192.0.2.42':'2001:db8::42',family:options?.family===4?4:6};
      return options?.all?[address]:address;
    }
    throw error;
  }
  const dns={lookup(host,options,callback){if(typeof options==='function'){callback=options;options=undefined;}
    if(typeof callback!=='function')throw new TypeError('callback');
    queueMicrotask(()=>{try{const value=answer(host,options);if(options?.all)callback(null,value);else callback(null,value.address,value.family);}catch(e){callback(e);}});
    return 'native-request';
  },promises:{async lookup(host,options){return answer(host,options);}}};
  const original=dns.lookup;
  const context=vm.createContext({process:{env:{DSHA_DNS_MODE:mode}},require:name=>name==='node:dns'?dns:name==='node:util'?{promisify}:name==='node:module'?{syncBuiltinESMExports(){syncs++;}}:null});
  vm.runInContext(source,context);
  return {dns,calls,error,original,loadAgain:()=>vm.runInContext(source,context),success:()=>nativeSuccess=true,fail:()=>failFallback=true,get syncs(){return syncs;}};
}
const callbackLookup=(f,options)=>new Promise((resolve,reject)=>f.dns.lookup('api.example',options,(error,...values)=>error?reject(error):resolve(values)));
test('AAAA refusal retries IPv4 once, preserving all/options without mutation',async()=>{
  const f=fixture(), options={all:true,order:'verbatim',hints:32};
  const result=await callbackLookup(f,options);assert.equal(result[0][0].family,4);assert.equal(f.calls.length,2);
  assert.equal(f.calls[1].options.order,'verbatim');assert.equal(f.calls[1].options.hints,32);assert.deepEqual(options,{all:true,order:'verbatim',hints:32});
});
test('normal IPv6 and explicit IPv6/IPv4 are not downgraded',async()=>{
  const f=fixture();f.success();assert.equal((await callbackLookup(f,{all:true}))[0][0].family,6);assert.equal(f.calls.length,1);
  for(const options of [4,6,{family:6},{family:'IPv6'},{family:'IPv4'}]){
    const explicit=fixture();await assert.rejects(callbackLookup(explicit,options));assert.equal(explicit.calls.length,1);
  }
});
test('NXDOMAIN/argument errors and failed fallback keep errors, no retry loop',async()=>{
  const f=fixture('auto','ENOTFOUND');await assert.rejects(callbackLookup(f,undefined),e=>e===f.error);assert.equal(f.calls.length,1);
  const failed=fixture();failed.fail();await assert.rejects(callbackLookup(failed,{}),e=>e===failed.error);assert.equal(failed.calls.length,2);
  assert.throws(()=>failed.dns.lookup('api.example',{}),TypeError);
});
test('promise API and promisify retain result shape',async()=>{
  const f=fixture();assert.equal((await f.dns.promises.lookup('example')).family,4);
  assert.equal((await promisify(f.dns.lookup)('example',{all:true}))[0].family,4);assert.equal(f.calls.length,4);
});
test('native opt-out and duplicate preload are stable',async()=>{
  const native=fixture('native');assert.equal(native.dns.lookup,native.original);assert.equal(native.syncs,0);
  const f=fixture();const lookup=f.dns.lookup;f.loadAgain();assert.equal(f.dns.lookup,lookup);assert.equal(f.syncs,1);
  await callbackLookup(f,undefined);assert.equal(f.calls.length,2);
});
test('default callback signature, native return value and callback exactly once',async()=>{
  const f=fixture();let count=0;
  await new Promise((resolve,reject)=>{const result=f.dns.lookup('api.example',(e,a,family)=>{count++;try{assert.equal(e,null);assert.equal(a,'192.0.2.42');assert.equal(family,4);resolve();}catch(error){reject(error);}});assert.equal(result,'native-request');});
  assert.equal(count,1);
});

// ---- 宿主解析兜底：用假 fs/http 隔离，不读真实凭据、不连真实桥 ----
import {EventEmitter} from 'node:events';
function hostFixture({code='EAI_AGAIN', env={}, reply, mode=0o600, status=200, port='3091', delay=0}={}) {
  const calls=[], requests=[], error=Object.assign(new Error('getaddrinfo '+code),{code});
  const dns={lookup(host,options,callback){if(typeof options==='function'){callback=options;options=undefined;}
    if(typeof callback!=='function')throw new TypeError('callback');
    calls.push({host,options});queueMicrotask(()=>callback(error));return 'native-request';},
    promises:{async lookup(host,options){calls.push({host,options});throw error;}}};
  const files={'/root/.dsh/.bridge_headers':'X-Token: abc_DEF-123\n','/root/.dsh/.bridge_port':port};
  const fs={lstatSync(path){if(!(path in files))throw Object.assign(Error('ENOENT'),{code:'ENOENT'});
      return {isFile:()=>true,isSymbolicLink:()=>false,mode,size:files[path].length};},
    readFileSync(path){return files[path];}};
  const http={get(options,onResponse){
    const request=new EventEmitter();request.destroy=()=>{request.destroyed=true;};requests.push(options);
    const later=delay?fn=>setTimeout(fn,delay):queueMicrotask;
    later(()=>{
      if(reply===undefined){request.emit('error',Object.assign(Error('ECONNREFUSED'),{code:'ECONNREFUSED'}));return;}
      const response=new EventEmitter();response.statusCode=status;response.setEncoding=()=>{};
      onResponse(response);response.emit('data',JSON.stringify({result:reply}));response.emit('end');response.emit('end');
    });
    return request;}};
  const context=vm.createContext({process:{env:{DSHA_DNS_MODE:'auto',...env}},require:name=>({
    'node:dns':dns,'node:util':{promisify},'node:module':{syncBuiltinESMExports(){}},'node:fs':fs,'node:http':http})[name]??null});
  vm.runInContext(source,context);
  return {dns,calls,requests,error};
}
const OK='DSHA_RESOLVE_V1\nstatus=OK\n4 198.51.100.7\n6 2001:db8::7\n';
const cb=(f,host,options)=>new Promise((resolve,reject)=>{let count=0;
  const handler=(e,...values)=>{if(++count>1)reject(Error('callback twice'));else e?reject(e):resolve(values);};
  options===undefined?f.dns.lookup(host,handler):f.dns.lookup(host,options,handler);});
test('stale resolver: EAI_AGAIN falls back to host resolver once, after the IPv4 retry',async()=>{
  const f=hostFixture({reply:OK});
  const [address,family]=await cb(f,'api.example.com');
  assert.equal(address,'198.51.100.7');assert.equal(family,4);
  assert.equal(f.calls.length,2);assert.equal(f.requests.length,1);
  const r=f.requests[0];assert.equal(r.host,'127.0.0.1');assert.equal(r.port,3091);assert.equal(r.headers['X-Token'],'abc_DEF-123');
  assert.equal(r.path,'/app/net/resolve?host=api.example.com&family=0');assert.equal(r.agent,false);
  const all=await cb(f,'api.example.com',{all:true,order:'ipv6first'});
  assert.equal(JSON.stringify(all[0].map(a=>a.family)),'[6,4]');
});
test('explicit family goes to host with the same family and is not downgraded',async()=>{
  const f=hostFixture({reply:'DSHA_RESOLVE_V1\nstatus=OK\n6 2001:db8::7\n'});
  const [address,family]=await cb(f,'v6.example.com',{family:6});
  assert.equal(address,'2001:db8::7');assert.equal(family,6);
  assert.equal(f.calls.length,1);assert.match(f.requests[0].path,/family=6$/);
});
test('ENOTFOUND, IP literals, localhost and single labels never reach the bridge',async()=>{
  const nx=hostFixture({code:'ENOTFOUND',reply:OK});
  await assert.rejects(cb(nx,'missing.example.com'),e=>e===nx.error);assert.equal(nx.requests.length,0);
  for(const host of ['localhost','svc.localhost','intranet','10.0.0.1']){
    const f=hostFixture({reply:OK});await assert.rejects(cb(f,host),e=>e===f.error);assert.equal(f.requests.length,0);
  }
});
test('host failure keeps the original error and is negatively cached; concurrent lookups coalesce',async()=>{
  const f=hostFixture({reply:'DSHA_RESOLVE_V1\nstatus=NXDOMAIN\n'});
  await assert.rejects(cb(f,'gone.example.com'),e=>e===f.error);
  await assert.rejects(cb(f,'gone.example.com'),e=>e===f.error);
  assert.equal(f.requests.length,1);
  const down=hostFixture();
  await assert.rejects(cb(down,'a.example.com'),e=>e===down.error);
  const ok=hostFixture({reply:OK,delay:20});
  await Promise.all([cb(ok,'same.example.com'),cb(ok,'same.example.com'),ok.dns.promises.lookup('same.example.com')]);
  assert.equal(ok.requests.length,1);
});
test('old app ([NO_CMD]), HTTP errors and bad credential files fall back to the original error',async()=>{
  for(const options of [{reply:'[NO_CMD]'},{reply:'[UNAUTHORIZED]'},{reply:OK,status:500},{reply:OK,mode:0o644},
    {reply:'DSHA_RESOLVE_V1\nstatus=OK\n4 not-an-ip\n'}]){
    const f=hostFixture(options);await assert.rejects(cb(f,'api.example.com'),e=>e===f.error);
  }
  // 端口文件无效时不猜 3090（可能是同机另一个 DSHA 安装），保留原错误且不发送 token。
  const bad=hostFixture({reply:OK,port:'99999'});await assert.rejects(cb(bad,'api.example.com'),e=>e===bad.error);assert.equal(bad.requests.length,0);
  const explicit=hostFixture({reply:OK,port:'99999',env:{DSHA_BRIDGE_PORT:'3095'}});await cb(explicit,'api.example.com');assert.equal(explicit.requests[0].port,3095);
});
test('promise API returns the native result shape from the host fallback',async()=>{
  const f=hostFixture({reply:OK});
  assert.equal(JSON.stringify(await f.dns.promises.lookup('p.example.com')),'{"address":"198.51.100.7","family":4}');
  const all=await f.dns.promises.lookup('p.example.com',{all:true});assert.equal(all.length,2);
  assert.equal((await promisify(f.dns.lookup)('q.example.com')).address,'198.51.100.7');
});
test('DSHA_DNS_HOST_FALLBACK=0 and native mode disable the bridge fallback',async()=>{
  const off=hostFixture({reply:OK,env:{DSHA_DNS_HOST_FALLBACK:'0'}});
  await assert.rejects(cb(off,'api.example.com'),e=>e===off.error);assert.equal(off.requests.length,0);
  const native=hostFixture({reply:OK,env:{DSHA_DNS_MODE:'native'}});
  await assert.rejects(cb(native,'api.example.com'),e=>e===native.error);assert.equal(native.requests.length,0);
});

test('native libuv/getaddrinfo fixture compiles and preserves one HTTP request',
  {skip:process.platform!=='linux'?'Real LD_PRELOAD boundary requires a Linux host':false},()=>{
    const folder=mkdtempSync(join(tmpdir(),'dsha-dns-native-'));
    try {
      const library=join(folder,'dns-compat-fixture.so');
      execFileSync(process.env.CC||'cc',['-std=c11','-shared','-fPIC','-O2','-nostdlib',
        '-Wl,--build-id=none',resolve('tools/dns-compat-fixture.c'),'-o',library],
        {stdio:'pipe',timeout:30000});
      for(const mode of ['auto','native']){
        const env={...process.env,LD_PRELOAD:library,DSHA_DNS_MODE:mode,DSHA_DNS_HOST_FALLBACK:'0'};
        delete env.NODE_OPTIONS;
        const output=execFileSync(process.execPath,['--require',
          resolve(process.env.DSHA_DNS_COMPAT_SOURCE||'app/src/main/assets/dns-compat.cjs'),
          resolve('tools/test-dns-compat-device.mjs')],{env,encoding:'utf8',timeout:30000});
        assert.match(output,new RegExp(`DNS_NATIVE_BOUNDARY_PASS mode=${mode}`));
        assert.match(output,mode==='auto'?/HTTP_requests=1/:/HTTP_requests=0/);
      }
    } finally {rmSync(folder,{recursive:true,force:true});}
  });
