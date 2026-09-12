import assert from 'node:assert/strict';
import dns from 'node:dns';
import {lookup} from 'node:dns/promises';
import http from 'node:http';
const native=process.env.DSHA_DNS_MODE==='native';
if(native) await assert.rejects(lookup('refused.dsha.test'),{code:'EAI_AGAIN'});
else {
  assert.equal((await lookup('refused.dsha.test')).address,'127.0.0.1');
  const all=await lookup('refused.dsha.test',{all:true});assert.equal(all[0].family,4);
  await new Promise((resolve,reject)=>dns.lookup('refused.dsha.test',(e,a,f)=>{try{assert.equal(e,null);assert.equal(a,'127.0.0.1');assert.equal(f,4);resolve();}catch(error){reject(error);}}));
}
assert.ok((await lookup('dual.dsha.test',{all:true})).some(a=>a.family===6));
await assert.rejects(lookup('refused.dsha.test',{family:6}),{code:'EAI_AGAIN'});
let requests=0;
const server=http.createServer((request,response)=>{requests++;response.end('dns-compatible');});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
try {
  const url=`http://refused.dsha.test:${server.address().port}/`;
  if(native){await assert.rejects(fetch(url));assert.equal(requests,0);}
  else {assert.equal(await (await fetch(url)).text(),'dns-compatible');assert.equal(requests,1);}
} finally {server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
console.log(`DNS_NATIVE_BOUNDARY_PASS mode=${native?'native':'auto'} node=${process.version} HTTP_requests=${requests} IPv6=preserved`);
