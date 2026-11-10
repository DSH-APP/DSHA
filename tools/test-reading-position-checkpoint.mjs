#!/usr/bin/env node
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const source = fs.readFileSync(new URL('../app/src/main/assets/app-integration/client.js',import.meta.url),'utf8');
export function fixture(text=source, previous=null) {
  let api, now=10000, serial=0, session='a';
  const counts={raf:0,timerCreates:0,timerClears:0,pathChecks:0,json:0,offsetReads:0};
  const listeners=new Map(),windowListeners=new Map(),timeouts=new Map(),intervals=new Map(),rafs=new Map(),subscribers=new Set(),writes=[];
  const listen=map=>({addEventListener(type,fn){if(!map.has(type))map.set(type,new Set());map.get(type).add(fn);},removeEventListener(type,fn){map.get(type)?.delete(fn);}});
  const emit=(map,type,event={})=>{for(const fn of [...map.get(type)||[]])fn(event);};
  class Element {
    constructor(slot,parent=null){this.slot=slot;this.parentElement=parent;this.children=[];this.isConnected=true;this.top=0;this.left=0;this.scrollHeight=10000;this.clientHeight=100;if(parent)parent.children.push(this);}
    hasAttribute(name){counts.pathChecks++;return name==='data-slot'&&this.slot!==null;}
    getAttribute(name){return name==='data-slot'?this.slot:null;}
    get scrollTop(){counts.offsetReads++;return this.top;}set scrollTop(value){this.top=value;}
    get scrollLeft(){counts.offsetReads++;return this.left;}set scrollLeft(value){this.left=value;}
    remove(){this.isConnected=false;this.parentElement?.children.splice(this.parentElement.children.indexOf(this),1);this.parentElement=null;this.top=0;this.left=0;}
  }
  const body=new Element(null),nodes=[];
  const document={...listen(listeners),body,scrollingElement:body,hidden:false,readyState:'complete',querySelectorAll:()=>nodes.filter(node=>node.isConnected&&node.slot!==null)};
  const window={...listen(windowListeners),__ModuleLoader__:{load:declaration=>{api=declaration.factory();}}};
  class Clock extends Date {static now(){return now;}}
  const location={pathname:'/chat/a'};
  const localStorage={getItem:()=>previous===null?null:JSON.stringify(previous),setItem(key,value){writes.push({key,value:JSON.parse(value)});}};
  const sandbox={window,document,Element,location,localStorage,Blob,File,Promise,Date:Clock,Math,Map,Set,Array,Error,
    JSON:{parse:JSON.parse,stringify(...args){counts.json++;return JSON.stringify(...args);}},
    setTimeout(fn,delay){counts.timerCreates++;const id=++serial;timeouts.set(id,{fn,at:now+delay});return id;},
    clearTimeout(id){counts.timerClears++;timeouts.delete(id);},
    setInterval(fn){const id=++serial;intervals.set(id,fn);return id;},clearInterval:id=>intervals.delete(id),
    requestAnimationFrame(fn){counts.raf++;const id=++serial;rafs.set(id,fn);return id;},cancelAnimationFrame:id=>rafs.delete(id)};
  vm.createContext(sandbox);vm.runInContext(text,sandbox);
  const index={current:()=>session,invalidate(){}};
  const ctx={sessions:{list:{getSnapshot:()=>({phase:'ready',byId:{a:{id:'a'},b:{id:'b'}}}),subscribe(fn){subscribers.add(fn);return()=>subscribers.delete(fn);}}},uiWorkspace:{openSession(id){session=id;}}};
  const stop=api.installReadingPosition(ctx,index);
  const advance=ms=>{const end=now+ms;for(let limit=0;limit<10000;limit++){const ready=[...timeouts].filter(([,item])=>item.at<=end).sort((a,b)=>a[1].at-b[1].at)[0];if(!ready)break;now=ready[1].at;timeouts.delete(ready[0]);ready[1].fn();}now=end;};
  return {counts,writes,listeners,windowListeners,timeouts,intervals,rafs,subscribers,document,location,stop,advance,
    node(slot='messages',parent=body){const node=new Element(slot,parent);nodes.push(node);return node;},
    emit(type,event){emit(listeners,type,event);},win(type,event){emit(windowListeners,type,event);},
    start(){emit(listeners,'pointerdown');},
    scroll(node,top,left=0){node.top=top;node.left=left;emit(listeners,'scroll',{target:node});},
    frame(){const work=[...rafs.values()];rafs.clear();for(const fn of work)fn();},
    changeSession(id,url=location.pathname){session=id;location.pathname=url;for(const fn of subscribers)fn();},
    restore(){for(const fn of [...intervals.values()])fn();},
    latest(){return writes.at(-1)?.value;}};
}

test('continuous scrolling records exact latest offsets without RAF, repeated DOM paths or per-event timer resets',()=>{
  const f=fixture(),node=f.node();f.start();
  for(let i=0;i<1200;i++){f.scroll(node,i+.25,3.5);f.advance(8);}
  assert.equal(f.writes.length,0);assert.equal(f.counts.raf,0);assert.equal(f.counts.pathChecks,1);
  assert.ok(f.counts.timerCreates<60);assert.equal(f.counts.timerClears,0);
  f.emit('scrollend');assert.equal(f.latest().positions[0].top,1199.25);assert.equal(f.latest().positions[0].left,3.5);
  assert.equal(f.timeouts.size,0);f.stop();
});
test('without scrollend the bounded idle check rereads the final live offset once',()=>{
  const f=fixture(),node=f.node();f.start();f.scroll(node,123);f.advance(100);f.scroll(node,234);
  f.advance(179);assert.equal(f.writes.length,0);node.top=235.75;f.advance(1);
  assert.equal(f.latest().positions[0].top,235.75);assert.equal(f.writes.length,1);f.stop();
});
test('node unmount retains the last delivered offsets and its pre-removal path',()=>{
  const f=fixture(),slot=f.node('conversation'),node=f.node(null,slot);f.start();f.scroll(node,777.5,19);
  node.remove();assert.equal(node.scrollTop,0);f.advance(180);
  assert.deepEqual(f.latest().positions,[{path:{slot:'conversation',path:[0]},top:777.5,left:19}]);f.stop();
});
test('a connected moved scroller resolves its current path at checkpoint',()=>{
  const f=fixture(),slot=f.node('conversation'),node=f.node(null,slot);f.start();f.scroll(node,222);
  const other=f.node(null,slot);slot.children.splice(0,2,other,node);f.emit('scrollend');
  assert.deepEqual(f.latest().positions[0].path,{slot:'conversation',path:[1]});f.stop();
});
test('session replacement snapshots old identity before reused DOM resets and accepts new offsets',()=>{
  const f=fixture(),node=f.node();f.start();f.scroll(node,888);node.top=0;
  f.changeSession('b','/chat/b');assert.equal(f.latest().id,'a');assert.equal(f.latest().url,'/chat/a');assert.equal(f.latest().positions[0].top,888);
  f.scroll(node,99);f.emit('scrollend');assert.equal(f.latest().id,'b');assert.equal(f.latest().url,'/chat/b');assert.equal(f.latest().positions[0].top,99);f.stop();
});
test('popstate and navigation capture checkpoint before route unmount without wrong-session writes',()=>{
  const f=fixture(),node=f.node();f.start();f.scroll(node,555);f.emit('pointerdown');node.remove();
  assert.equal(f.latest().positions[0].top,555);f.location.pathname='/workspace';f.win('popstate');
  assert.equal(f.writes.at(-2).value.url,'/chat/a');assert.equal(f.latest().url,'/workspace');assert.deepEqual(f.latest().positions,[]);f.stop();
});
test('more than sixteen scrollers retain the latest eight including a revisited old container',()=>{
  const f=fixture(),nodes=Array.from({length:20},(_,i)=>f.node('slot-'+i));f.start();
  nodes.forEach((node,i)=>f.scroll(node,i+10));f.scroll(nodes[0],999);f.emit('scrollend');
  assert.deepEqual(f.latest().positions.map(item=>item.path.slot),['slot-13','slot-14','slot-15','slot-16','slot-17','slot-18','slot-19','slot-0']);
  assert.equal(f.latest().positions.at(-1).top,999);f.stop();
});
test('hidden/pagehide checkpoints final live position, BFCache resumes, dispose rejects queued callbacks',()=>{
  const f=fixture(),node=f.node();f.start();f.scroll(node,123);const stale=[...f.timeouts.values()][0].fn;
  node.top=124;f.document.hidden=true;f.emit('visibilitychange');assert.equal(f.latest().positions[0].top,124);assert.equal(f.timeouts.size,0);
  f.document.hidden=false;f.emit('visibilitychange');f.scroll(node,200);f.win('pagehide');assert.equal(f.latest().positions[0].top,200);
  const writes=f.writes.length;f.scroll(node,300);stale();assert.equal(f.writes.length,writes);
  f.win('pageshow');f.scroll(node,400);stale();assert.equal(f.timeouts.size,1,'cancelled previous-page timer cannot replace the live timer');
  f.emit('scrollend');assert.equal(f.latest().positions[0].top,400);
  f.stop();const after=f.writes.length;stale();f.win('pageshow');f.advance(1000);assert.equal(f.writes.length,after);
  assert.equal(f.timeouts.size,0);assert.equal(f.intervals.size,0);assert.equal(f.subscribers.size,0);
  assert.ok([...f.listeners.values(),...f.windowListeners.values()].every(set=>set.size===0));
});
test('restoration ignores generated scrolls, retains saved offsets and later samples user scrolling',()=>{
  const previous={id:'a',url:'/chat/a',positions:[{path:{slot:'messages',path:[]},top:600,left:7}]};
  const f=fixture(source,previous),node=f.node();f.scroll(node,0);assert.equal(f.timeouts.size,0);f.restore();
  assert.equal(node.top,600);assert.equal(node.left,7);f.scroll(node,700);f.emit('scrollend');assert.equal(f.latest().positions[0].top,700);f.stop();
});
test('root/document scrolling is saved with the existing root identity',()=>{
  const f=fixture();f.start();f.document.scrollingElement.top=65;f.emit('scroll',{target:f.document});f.emit('scrollend');
  assert.deepEqual(f.latest().positions,[{path:{root:true},top:65,left:0}]);f.stop();
});

if(process.env.DSHA_READING_BASELINE_SOURCE){
  const baseline=fs.readFileSync(process.env.DSHA_READING_BASELINE_SOURCE,'utf8');const result={};
  for(const [name,text] of [['baseline',baseline],['candidate',source]]){
    const f=fixture(text),node=f.node();f.start();
    for(let i=0;i<1200;i++){f.scroll(node,i+.25);f.frame();f.advance(8);}
    f.advance(200);assert.equal(f.latest().positions[0].top,1199.25);
    result[name]={events:1200,counts:f.counts,writes:f.writes.length,finalPosition:f.latest().positions[0]};f.stop();
  }
  if(process.env.DSHA_READING_COUNTERS_OUTPUT)fs.writeFileSync(process.env.DSHA_READING_COUNTERS_OUTPUT,JSON.stringify(result,null,2)+'\n');
  console.log('READING_WORK_COUNTS '+JSON.stringify(result));
}
