// Gecko 的原生导航消息必须进入页面世界，不能只保存在 Activity Intent。
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const listeners={},events=[],outbound=[];let receive;
const window={addEventListener:(type,fn)=>listeners[type]=fn,dispatchEvent:event=>events.push(event.type)};window.top=window;
const context=vm.createContext({window,location:{origin:'http://127.0.0.1:3080',pathname:'/'},browser:{runtime:{connectNative:()=>({onMessage:{addListener:fn=>receive=fn},postMessage:value=>outbound.push(value)})}},
  Event:class {constructor(type){this.type=type}},CustomEvent:class {constructor(type){this.type=type}},
  document:{createElement:()=>({textContent:'',remove(){}})}});
context.document.head={appendChild:script=>vm.runInContext(script.textContent,context)};
vm.runInContext(readFileSync('app/src/main/assets/web-integration/startup-relay.js','utf8'),context);
receive({type:'language',language:'zh',openModels:true,observationNonce:'a'.repeat(32)});
assert.equal(window.__DSHA_LANGUAGE__,'zh');
assert.deepEqual(events,['dsha-language']);
events.length=0;
receive({type:'language',language:'en',openModels:'true'});
assert.equal(window.__DSHA_LANGUAGE__,'en');
assert.deepEqual(events,['dsha-language']);
receive({type:'language',language:'invalid',openModels:true});assert.equal(window.__DSHA_LANGUAGE__,'en');
listeners['dsha-startup']({detail:JSON.stringify({type:'ready'})});assert.equal(outbound[0].report.type,'ready');
console.log('Gecko page relay: locale validation and startup report passed.');
