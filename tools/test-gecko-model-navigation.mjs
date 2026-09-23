// Gecko 的原生导航消息必须进入页面世界，不能只保存在 Activity Intent。
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const listeners={},events=[],outbound=[];let receive;
const window={addEventListener:(type,fn)=>listeners[type]=fn,dispatchEvent:event=>events.push(event.type)};window.top=window;
const context=vm.createContext({window,browser:{runtime:{connectNative:()=>({onMessage:{addListener:fn=>receive=fn},postMessage:value=>outbound.push(value)})}},
  Event:class {constructor(type){this.type=type}},CustomEvent:class {constructor(type){this.type=type}},
  document:{createElement:()=>({textContent:'',remove(){}})}});
context.document.head={appendChild:script=>vm.runInContext(script.textContent,context)};
vm.runInContext(readFileSync('app/src/main/assets/web-integration/startup-relay.js','utf8'),context);
receive({type:'language',language:'zh',openModels:true});
assert.equal(window.__DSHA_LANGUAGE__,'zh');assert.equal(window.__DSHA_OPEN_MODELS__,true);
assert.deepEqual(events,['dsha-language','dsha-open-models']);
window.__DSHA_OPEN_MODELS__=false;events.length=0;
receive({type:'language',language:'en',openModels:'true'});
assert.equal(window.__DSHA_LANGUAGE__,'en');assert.equal(window.__DSHA_OPEN_MODELS__,false);
receive({type:'language',language:'invalid',openModels:true});assert.equal(window.__DSHA_OPEN_MODELS__,false);
listeners['dsha-startup']({detail:JSON.stringify({type:'ready'})});assert.equal(outbound[0].report.type,'ready');
console.log('Gecko page relay: model navigation, locale validation and startup report passed.');
