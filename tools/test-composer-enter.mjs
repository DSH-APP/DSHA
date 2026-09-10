// 在实际上游 keymap 上施加 APK 的同一份补丁，检查输入法、候选菜单和快捷提交。
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
const path = process.env.DSHA_COMPOSER_CLIENT || (process.env.DSHA_TEST_RUNTIME || 'app/build/locked-dsh-runtime') + '/node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js';
let source = readFileSync(path,'utf8');
const policy = JSON.parse(readFileSync('app/src/main/assets/composer-enter-patch.json','utf8'));
for (const {before,after} of policy.patches) {
  assert.equal(source.split(before).length-1,1);
  source=source.replace(before,after);
}
const start=source.indexOf('function isComposingEvent('), end=source.indexOf('\n\t\t//#endregion',start);
assert.ok(start>0 && end>start);
function fixture() {
  const commands=new Map(), root=new EventTarget(); let now=100, composingRoot, menu='pass', allowed=true;
  const sent=[];
  const editor={registerRootListener(fn){fn(root,null);composingRoot=fn;return()=>fn(null,root)},registerCommand(id,fn){commands.set(id,fn);return()=>commands.delete(id)}};
  const handlers={arbitrate:()=>menu,canSubmit:()=>allowed,submit:accelerated=>sent.push(accelerated),dismissPopup(){},space:()=>false};
  const ctx={Date:{now:()=>now},Eu:(...disposers)=>()=>disposers.forEach(fn=>fn())};
  for(const name of ['sn$2','ln$2','hn$2','fn$1','an$1','cn$1','Je$2'])ctx[name]=name;
  vm.createContext(ctx);vm.runInContext(source.slice(start,end)+'\nglobalThis.install=registerComposerKeymap;',ctx);
  const dispose=ctx.install(editor,handlers);
  const enter=(properties={})=>{let prevented=0;const event=properties===null?null:{preventDefault(){prevented++},...properties};const consumed=commands.get('cn$1')(event);return{consumed,prevented}};
  return{sent,enter,root,dispose,setMenu:value=>menu=value,setAllowed:value=>allowed=value,advance:ms=>now+=ms};
}
test('普通回车、Shift+Enter 与没有 keydown 的 IME 换行交给编辑器，不提交',()=>{
  const f=fixture();for(const event of [{},{shiftKey:true},null])assert.deepEqual(f.enter(event),{consumed:false,prevented:0});
  assert.deepEqual(f.sent,[]);f.dispose();
});
test('Ctrl/Cmd+Enter 保留显式快捷提交，长按不连续发送',()=>{
  const f=fixture();f.enter({ctrlKey:true});f.enter({metaKey:true});f.enter({ctrlKey:true,repeat:true});
  assert.deepEqual(f.sent,[true,true]);f.dispose();
});
test('组合输入与 keyCode 229 不触发提交，包括 Shift/快捷键',()=>{
  const f=fixture();for(const event of [{isComposing:true},{keyCode:229},{isComposing:true,ctrlKey:true},{isComposing:true,shiftKey:true}])f.enter(event);
  f.root.dispatchEvent(new Event('compositionstart'));f.enter({ctrlKey:true});
  f.root.dispatchEvent(new Event('compositionend'));f.enter({metaKey:true});
  assert.deepEqual(f.sent,[]);f.advance(11);f.enter({ctrlKey:true});assert.deepEqual(f.sent,[true]);f.dispose();
});
test('候选菜单的 Enter 仍能选中项目，Shift+Enter 不抢菜单',()=>{
  const f=fixture();f.setMenu('consumed');assert.deepEqual(f.enter(),{consumed:true,prevented:1});
  assert.deepEqual(f.enter({shiftKey:true}),{consumed:false,prevented:0});assert.deepEqual(f.sent,[]);f.dispose();
});
test('锁定输入不能由快捷键绕过，软键盘明确显示换行',()=>{
  const f=fixture();f.setAllowed(false);f.enter({ctrlKey:true});assert.deepEqual(f.sent,[]);f.dispose();
  assert.ok(source.includes('enterKeyHint: "enter"'));assert.ok(source.includes('"data-dsha-enter-mode": "newline"'));
});
