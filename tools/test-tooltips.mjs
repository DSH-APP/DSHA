import {readFileSync, writeFileSync, mkdirSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const asset = 'app/src/main/assets/web-integration/';
const helper = readFileSync(asset + 'tooltip-interactions.js', 'utf8');
const component = readFileSync(asset + 'tooltip-component.js', 'utf8');
const spec = JSON.parse(readFileSync(asset + 'tooltip-patch.json', 'utf8'));
const runtime = process.env.DSHA_TEST_RUNTIME || 'app/build/locked-dsh-runtime';
const frontend = runtime + '/node_modules/@deepseek-ai/dsh-web-frontend/dist/';
const source = readFileSync(frontend + spec.bundle, 'utf8');
assert.equal(source.split(spec.before).length - 1, 1);
const replacement = helper + '\nconst dshaTooltipRuntime = createDshaTooltipRuntime({ document, window });\n' + component;
const patched = source.replace(spec.before, replacement);
mkdirSync('app/build/ui-polish-20260909', {recursive:true});
writeFileSync('app/build/ui-polish-20260909/frontend-patched.mjs', patched);

class Target extends EventTarget {
  listeners = new Map(); hidden = false;
  addEventListener(name, fn, options) { super.addEventListener(name, fn, options); this.listeners.set(fn, name); }
  removeEventListener(name, fn, options) { super.removeEventListener(name, fn, options); this.listeners.delete(fn); }
  fire(type, properties = {}) { const e = Object.assign(new Event(type), properties); this.dispatchEvent(e); return e; }
}
function fixture() {
  const document = new Target(), window = new Target(), timers = new Map(); let next = 0;
  const context = vm.createContext({Set,Array,setTimeout,clearTimeout});
  vm.runInContext(helper + '\nglobalThis.create = createDshaTooltipRuntime;', context);
  const runtime = context.create({document,window,setTimeout:fn=>{timers.set(++next,fn);return next;},clearTimeout:id=>timers.delete(id)});
  const items = [];
  const item = (delay=0) => {
    const state={visible:false,opened:0,enabled:true};
    const controller=runtime.create({enabled:()=>state.enabled,delay:()=>delay,open:()=>{state.visible=true;state.opened++;},close:()=>state.visible=false});
    const dispose=controller.mount(); const result={...controller,state,dispose};items.push(result); return result;
  };
  return {document,window,item,timers,flush(){const tasks=[...timers.values()];timers.clear();tasks.forEach(fn=>fn());},dispose(){items.forEach(x=>x.dispose());}};
}

test('触摸及兼容鼠标事件不会弹出气泡，点击动作后也不出现新标签',()=>{
  const f=fixture(), tip=f.item();
  tip.pointerEnter({pointerType:'touch'});f.document.fire('pointerdown',{pointerType:'touch'});tip.focus();tip.activate();
  tip.pointerEnter({});f.flush();assert.equal(tip.state.opened,0);assert.equal(tip.state.visible,false);f.dispose();
});
test('真实鼠标悬停有提示，点击后关闭且不会随发送/暂停标签更新复活',()=>{
  const f=fixture(), tip=f.item();tip.pointerEnter({pointerType:'mouse'});assert.equal(tip.state.visible,true);
  f.document.fire('pointerdown');tip.focus();tip.activate();assert.equal(tip.state.visible,false);
  f.flush();assert.equal(tip.state.opened,1);tip.pointerLeave();tip.pointerEnter({pointerType:'mouse'});assert.equal(tip.state.opened,2);f.dispose();
});
test('Tab 焦点保留键盘提示；触摸后的程序焦点不弹提示，Escape 不吞事件',()=>{
  const f=fixture(), tip=f.item();f.document.fire('pointerdown');tip.focus();assert.equal(tip.state.visible,false);
  f.document.fire('keydown',{key:'Tab'});tip.focus();assert.equal(tip.state.visible,true);
  const event=f.document.fire('keydown',{key:'Escape'});assert.equal(tip.state.visible,false);assert.equal(event.defaultPrevented,false);f.dispose();
});
test('点击空白、滚动、切换页面和失焦同时清理显示与延迟计时',()=>{
  for(const action of [f=>f.document.fire('pointerdown'),f=>f.document.fire('scroll'),f=>f.window.fire('blur'),f=>{f.document.hidden=true;f.document.fire('visibilitychange');}]) {
    const f=fixture(), tip=f.item(100);tip.pointerEnter({pointerType:'mouse'});action(f);f.flush();assert.equal(tip.state.visible,false);assert.equal(tip.state.opened,0);f.dispose();
  }
});
test('同一时刻仅保留一个提示，禁用和卸载后不再延迟出现',()=>{
  const f=fixture(), first=f.item(), second=f.item();first.focus();second.focus();assert.equal(first.state.visible,false);assert.equal(second.state.visible,true);
  second.state.enabled=false;second.dismiss();second.pointerEnter({pointerType:'mouse'});assert.equal(second.state.visible,false);
  const pending=f.item(10);pending.pointerEnter({pointerType:'mouse'});pending.dispose();f.flush();assert.equal(pending.state.opened,0);f.dispose();
  assert.equal(f.document.listeners.size,0);assert.equal(f.window.listeners.size,0);
});
test('严格模式的卸载/重新挂载仍可工作',()=>{
  const f=fixture(), tip=f.item();tip.dispose();const stop=tip.mount();tip.pointerEnter({pointerType:'mouse'});assert.equal(tip.state.visible,true);stop();f.dispose();
});
test('组件仍转发原按钮事件，异常动作也关闭提示，不自行删除 React DOM',()=>{
  const calls=[], behavior={mount:()=>()=>{},dismiss(){},pointerEnter:()=>calls.push('enter'),pointerLeave(){},focus(){},blur(){},activate:()=>calls.push('close')};
  const context=vm.createContext({dshaTooltipRuntime:{create:()=>behavior},I:{useRef:value=>({current:value}),useState:value=>[value,()=>{}],useCallback:fn=>fn,useEffect(){},useLayoutEffect(){},cloneElement:(child,props)=>({props:{...child.props,...props}})},d:{Fragment:'fragment',jsxs:(type,props)=>props,jsx:(type,props)=>props}});
  vm.runInContext(component+'\nglobalThis.render = Fr;',context);
  const event={pointerType:'mouse'}, click=()=>{calls.push('action');throw new Error('expected');};
  const result=context.render({label:'发送',children:{props:{onClick:click,onPointerEnter:e=>{assert.equal(e,event);calls.push('childEnter');}}}});
  result.children[0].props.onPointerEnter(event);assert.deepEqual(calls,['childEnter','enter']);calls.length=0;
  assert.throws(()=>result.children[0].props.onClick(event),/expected/);assert.deepEqual(calls,['action','close']);
});
test('固定补丁只替换 Tooltip，保留其余前端模块并刷新入口缓存标记',()=>{
  assert.ok(patched.includes('DSHA_TOOLTIP_INTERACTION_V1'));assert.ok(patched.includes('data-dsha-tooltip'));
  const html=readFileSync(frontend + 'index.html','utf8');
  assert.equal(html.split(spec.indexBefore).length-1,1);assert.ok(html.replace(spec.indexBefore,spec.indexAfter).includes('?dsha-tooltip=4'));
});
