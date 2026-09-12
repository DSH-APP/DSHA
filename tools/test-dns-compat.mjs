import {readFileSync} from 'node:fs';
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
