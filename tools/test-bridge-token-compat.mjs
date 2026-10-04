import assert from 'node:assert/strict';
import fs from 'node:fs';
import zlib from 'node:zlib';
import vm from 'node:vm';
import {createRequire} from 'node:module';
const requests=[];
globalThis.fetch=async(input,init)=>{requests.push({input,init});return new Response('OK');};
const {rewrite}=createRequire(import.meta.url)('../app/src/main/assets/bridge-token-compat.cjs');
function member(archive,name){const raw=zlib.gunzipSync(fs.readFileSync(archive));for(let offset=0;offset+512<=raw.length;){const header=raw.subarray(offset,offset+512),path=header.subarray(0,100).toString().split('\0')[0];if(!path)break;const size=parseInt(header.subarray(124,136).toString().replace(/\0/g,'').trim(),8);if(path===name)return raw.subarray(offset+512,offset+512+size).toString();offset+=512+Math.ceil(size/512)*512;}throw Error('MISSING_ARCHIVE_MEMBER');}
// Run the actual function body distributed in the unmodified marketplace tgz.
const peak=member(new URL('../website/src/packages/dsh-peak-chip-4.1.8.tgz',import.meta.url),'package/lib/index.js');
const start=peak.indexOf('async function openTopUpPage(ctx) {'),end=peak.indexOf('/**',start+1);assert(start>=0&&end>start);
const token='fixture-legacy-token-12345678901234567890',warnings=[];
await vm.runInNewContext(peak.slice(start,end)+'\nopenTopUpPage(ctx)',{readBridgeToken:async()=>token,BRIDGE_OPEN_URL:'http://127.0.0.1:3090/app/open',TOPUP_URL:'https://fixture.example.invalid/topup',TIMEOUT_MS:5000,AbortSignal,fetch:globalThis.fetch,ctx:{logger:{warn:text=>warnings.push(text)}}});
assert.deepEqual(warnings,[]);assert.equal(requests.length,1);
const actual=new URL(requests[0].input);assert.equal(actual.searchParams.has('token'),false);assert.equal(actual.searchParams.get('url'),'https://fixture.example.invalid/topup');assert.equal(requests[0].init.headers.get('X-Token'),token);
const abort=new AbortController(),request=new Request('http://127.0.0.1:3090/app/ui/dump?token='+token,{headers:{'X-Custom':'kept'},signal:abort.signal});
const rewritten=rewrite(request,{cache:'no-store'});assert(rewritten.input instanceof Request);assert.equal(rewritten.input.headers.get('X-Custom'),'kept');assert.equal(rewritten.init.headers.get('X-Custom'),'kept');assert.equal(rewritten.init.cache,'no-store');abort.abort('fixture-cancel');assert.equal(rewritten.input.signal.aborted,true);assert.equal(rewritten.input.signal.reason,'fixture-cancel');
for(const query of ['token=','token=a&token=b','token=a&TOKEN=a'])await assert.rejects(fetch('http://127.0.0.1:3090/app/open?'+query),/BRIDGE_CREDENTIAL_INVALID/);
await assert.rejects(fetch('http://127.0.0.1:3090/app/open?token='+token,{headers:new Headers({'X-Token':'different'})}),/BRIDGE_CREDENTIAL_CONFLICT/);
assert.equal(rewrite('https://example.invalid/?token='+token,{}),null);assert.equal(rewrite('http://127.0.0.1:3091/?token='+token,{}),null);assert.equal(rewrite('http://localhost:3090/?token='+token,{}),null);assert.equal(rewrite('http://127.0.0.1:3090/?token='+token,{method:'POST'}),null);
const signal=new AbortController().signal,same=rewrite('http://[::1]:3090/app/open?token='+token,{headers:new Headers({'X-Token':token,'Keep':'yes'}),signal});assert.equal(same.init.signal,signal);assert.equal(same.init.headers.get('Keep'),'yes');
const browserRequests=[],browser=vm.createContext({URL,Headers,Promise,fetch:async(input,init)=>{browserRequests.push({input,init});return new Response('OK');}});
vm.runInContext('Object.hasOwn=undefined;Request=undefined;',browser);
vm.runInContext(fs.readFileSync(new URL('../app/src/main/assets/bridge-token-compat.cjs',import.meta.url),'utf8'),browser);
await browser.fetch('http://127.0.0.1:3090/app/open?token='+token,{signal});assert.equal(browserRequests[0].init.signal,signal);assert.equal(browserRequests[0].init.headers.get('X-Token'),token);assert.equal(new URL(browserRequests[0].input).searchParams.has('token'),false);
const missing=vm.createContext({Promise,fetch:async()=>new Response('OK')});vm.runInContext(fs.readFileSync(new URL('../app/src/main/assets/bridge-token-compat.cjs',import.meta.url),'utf8'),missing);await missing.fetch('http://127.0.0.1:3090/app/open?token='+token);
console.log('Bridge fetch migration: actual peak 4.1.8 tgz function, Request/Headers/AbortSignal, literal loopback/port bounds, credential conflicts and duplicates PASS. No network/device request sent.');
