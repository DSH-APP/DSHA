import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const directory='app/src/main/assets/builtin-plugins/dsh-web-mobile/';
const source=readFileSync(directory+'lib/client.js','utf8');
const functionSource=name=>{
  const start=source.indexOf('function '+name+'(');assert.ok(start>=0,name);
  const end=source.indexOf('\n}',start);assert.ok(end>start,name);return source.slice(start,end+2);
};
class Element {
  constructor({parent=null,position='static',width=50,height=50,frame=false,dragging=false}={}){Object.assign(this,{parentElement:parent,position,offsetWidth:width,offsetHeight:height,frame,dragging});}
  hasAttribute(name){return name==='data-mobile-nav-dragging'&&this.dragging;}
  closest(selector){for(let node=this;node;node=node.parentElement)if(selector==='[data-mobile-nav="frame"]'?node.frame:selector==='[data-mobile-nav-dragging]'?node.dragging:false)return node;return null;}
}
function fixture(){
  const document={documentElement:new Element(),body:new Element()};let resets=0;
  const context=vm.createContext({document,Element,HTMLElement:Element,getComputedStyle:node=>({position:node.position}),onCooldown:()=>false,modalOpen:()=>false,takeoverActive:()=>false,selectionOwnsStroke:()=>false,findHorizontalScroller:()=>null,chainFrom:()=>[],drawerOpen:()=>false,hitTestStart:x=>x<180,startZonePxFor:()=>180,reset:()=>resets++});
  vm.runInContext('let trackingPointer=-1,tracking=false,startX=0,startY=0,samples=[];const LOCK_PX=8;'+source.match(/const FLOATING_WIDGET_MAX_PX = \d+;/)[0]+['dragMarkYields','findFloatingWidget','floatingWidgetYields','beginStroke','tryLock'].map(functionSource).join('\n'),context);
  return {context,document,get resets(){return resets;},event:target=>({target,pointerId:1,clientX:50,clientY:50,timeStamp:0})};
}
test('2.4.1 拖动悬浮物时让位，不劫持为抽屉手势',()=>{
  const f=fixture(),widget=new Element({position:'fixed',width:148,height:160}),child=new Element({parent:widget});
  assert.equal(f.context.beginStroke(f.event(child),false,390),false);
  assert.equal(f.resets,0);
});
test('全局/祖先拖动标记在开始和锁轴前都生效',()=>{
  const f=fixture(),parent=new Element(),child=new Element({parent});
  f.document.body.dragging=true;assert.equal(f.context.beginStroke(f.event(child),false,390),false);
  f.document.body.dragging=false;assert.equal(f.context.beginStroke(f.event(child),false,390),true);
  parent.dragging=true;assert.equal(f.context.tryLock({...f.event(child),clientX:120,clientY:52}),false);assert.equal(f.resets,1);
});
test('普通内容、大容器和自己的抽屉按钮仍可开始手势',()=>{
  const f=fixture();
  for(const target of [new Element(),new Element({position:'fixed',width:390,height:600}),new Element({position:'absolute',frame:true})])assert.equal(f.context.beginStroke(f.event(target),false,390),true);
});
test('触屏大平板删除菜单采用指针门控，桌面仍不注入',()=>{
  let plugin,modules;
  const window={__ModuleLoader__:{load:value=>plugin=value}};
  vm.runInNewContext(source.replace('var __cache = {};','window.__testModules=__modules;window.__testRequire=__localRequire;var __cache = {};'),{window});plugin.factory(()=>({}));modules=window.__testModules;
  const phone=window.__testRequire('./effects/phone-chrome.js');
  assert.equal(phone.TOUCH_QUERY,'(pointer: coarse)');assert.match(phone.MOBILE_QUERY,/max-width: 1023px/);
  let query;const menu={};modules['effects/session-menu.js'](()=>({...phone,installMobileEffect:(ctx,label,work,q)=>query=q}),{},menu);
  menu.installSessionMenuDelete({});assert.equal(query,phone.TOUCH_QUERY);
  const style={};modules['styles/base.css.js'](()=>({}),{},style);assert.match(style.BASE_CSS,/@media \(min-width: 1024px\) and \(pointer: coarse\)/);
});
test('更新来源与既有 DSHA 适配一起交付',()=>{
  const pkg=JSON.parse(readFileSync(directory+'package.json','utf8'));assert.equal(pkg.version,'2.4.1-dsha.2');assert.equal(pkg.dshaUpstream.version,'2.4.1');assert.match(pkg.dshaUpstream.integrity,/^sha512-/);
  for(const marker of ['interactive-widget=resizes-content','dsha-session-open','[data-dsha-session-select]','dsha-preset-header-anchor',"ctx.sidebarRight.openTab('files')"])assert.ok(source.includes(marker),marker);
  assert.equal(pkg.peerDependencies['@deepseek-ai/dsh-client-ui-sidebar-right'],'0.1.5-rc.2');
});
