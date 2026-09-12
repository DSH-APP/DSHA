import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
const source=fs.readFileSync(new URL('../app/src/main/assets/web-integration/compat.js',import.meta.url),'utf8');
const timers=[];let added=0,removed=0;
function Signal(){}Signal.prototype=AbortSignal.prototype;
const context=vm.createContext({AbortSignal:Signal,AbortController,DOMException,
  EventTarget:{prototype:{addEventListener(...args){added++;return EventTarget.prototype.addEventListener.apply(this,args);},removeEventListener(...args){removed++;return EventTarget.prototype.removeEventListener.apply(this,args);}}},
  crypto:{getRandomValues:webcrypto.getRandomValues.bind(webcrypto)},setTimeout(fn,ms){timers.push({fn,ms});return timers.length;}});
vm.runInContext(source,context);
const a=new AbortController(),b=new AbortController(),reason={source:'b'};
const combined=Signal.any([a.signal,b.signal,a.signal]);assert.equal(added,2);
a.signal.dispatchEvent(new Event('abort'));assert.equal(combined.aborted,false);
b.abort(reason);assert.equal(combined.reason,reason);assert.equal(removed,2);
const first=new AbortController(),second=new AbortController();first.abort('first');second.abort('second');
assert.equal(Signal.any([first.signal,second.signal]).reason,'first');
assert.equal(Signal.any([]).aborted,false);
const count=added;assert.throws(()=>Signal.any([new AbortController().signal,{}]),{name:'TypeError'});assert.equal(added,count);
assert.throws(()=>Signal.any([first.signal,{}]),{name:'TypeError'});
for(const invalid of [null,undefined,1,{}])assert.throws(()=>Signal.any(invalid),{name:'TypeError'});
const zero=Signal.timeout(0);assert.equal(zero.aborted,false);assert.equal(timers[0].ms,0);timers.shift().fn();assert.equal(zero.reason.name,'TimeoutError');
const long=Signal.timeout(2147483652);assert.equal(timers[0].ms,2147483647);timers.shift().fn();assert.equal(long.aborted,false);assert.equal(timers[0].ms,5);timers.shift().fn();assert.equal(long.reason.name,'TimeoutError');
for(const invalid of [-1,NaN,Infinity,undefined,1n])assert.throws(()=>Signal.timeout(invalid),{name:'TypeError'});
const uuids=Array.from({length:50},()=>context.crypto.randomUUID());assert.equal(new Set(uuids).size,50);for(const uuid of uuids)assert.match(uuid,/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
const any=Signal.any,timeout=Signal.timeout,uuid=context.crypto.randomUUID;vm.runInContext(source,context);assert.equal(Signal.any,any);assert.equal(Signal.timeout,timeout);assert.equal(context.crypto.randomUUID,uuid);
const nativeAny=AbortSignal.any;vm.runInNewContext(source,{AbortSignal,AbortController,EventTarget,crypto:webcrypto,DOMException,setTimeout});assert.equal(AbortSignal.any,nativeAny);
console.log('PASS browser compatibility: abort reasons, first-aborted precedence, validation, duplicate inputs, listener cleanup, manual-event protection, asynchronous timeout, long timers, UUID entropy format, idempotence and native preservation');
