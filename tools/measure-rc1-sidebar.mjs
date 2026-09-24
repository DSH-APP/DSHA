// 合成数据 + rc1 真 React/Primitives/SessionNodeItem 的选择提交时延，和设备端整页测量分开报告。
import fs from 'node:fs';
import {browserFixture} from './rc1-browser-fixture.mjs';
const mode=process.argv[2]||'after';
let policy=JSON.parse(fs.readFileSync(mode==='before'?'tmp-device/rc1-fix-20260924/session-interaction-before.json':'app/src/main/assets/session-interaction-patch.json','utf8'));
if(mode==='before')for(const p of policy.patches)if(p.prependAsset){p.after=fs.readFileSync('tmp-device/rc1-fix-20260924/session-interaction-before.js','utf8')+'\n'+p.after;delete p.prependAsset;}
const fixture=await browserFixture();
try {
  await fixture.load('dsh-client-ui-workspace',[policy],'exports.audit={SessionNodeItem,SearchResultItem,FlatList,deriveFlat,deriveGroups,zh,en};');
  await fixture.page.addStyleTag({content:'body{margin:0;font:14px sans-serif}#root{width:340px;height:760px;overflow:auto}[role=treeitem]{min-height:34px;box-sizing:border-box}[aria-selected=true]{background:#b6d2ff}'});
  const results=[];
  for(const count of [1,100]) {
    const result=await fixture.page.evaluate(async count=>{
      const {react:R,'react-dom/client':D,'react-dom':DOM}=auditModules;
      const a=auditExports['@deepseek-ai/dsh-client-ui-workspace'].audit;
      const frame=()=>new Promise(resolve=>requestAnimationFrame(resolve));
      const records=[];const observer=new PerformanceObserver(list=>records.push(...list.getEntries().map(e=>e.duration)));
      observer.observe({entryTypes:['longtask']});
      window.benchRoot?.unmount();const root=D.createRoot(document.getElementById('root'));window.benchRoot=root;
      const t=(key,args)=>{let value=a.zh[key]||key;for(const [k,v] of Object.entries(args||{}))value=value.replace('{'+k+'}',String(v));return value;};
      let opened=0,commits=0;const start=performance.now();
      DOM.flushSync(()=>root.render(R.createElement(R.Profiler,{id:'rows',onRender:()=>commits++},Array.from({length:count},(_,i)=>R.createElement(a.SessionNodeItem,{key:i,node:{id:'s'+i,title:'会话 '+i,updatedAt:1,runningSubagentCount:0,pinned:i===0,archived:false},currentId:'s0',now:2,onOpen:()=>opened++,onRenameRequest(){},renderSlot:()=>null,t})))));
      await frame();const mount=performance.now()-start;
      const latencies=[];
      for(let i=0;i<30;i++) {
        const target=document.querySelector(`[data-dsha-session-select="s${i%2?(count-1):0}"]`);
        await frame();const at=performance.now();target.dispatchEvent(new MouseEvent('click',{bubbles:true,detail:1,clientX:10+i*30,clientY:10}));
        await frame();if(target.getAttribute('aria-selected')!=='true')throw Error('Selection did not commit');
        latencies.push(performance.now()-at);
      }
      observer.disconnect();latencies.sort((a,b)=>a-b);
      return {count,mountMs:mount,p50:latencies[14],p95:latencies[28],commits,opened,longTasks:records,domNodes:document.querySelectorAll('*').length,heap:performance.memory?.usedJSHeapSize};
    },count);
    results.push(result);
  }
  if(fixture.errors.length)throw Error(fixture.errors.join('\n'));
  console.log(JSON.stringify({mode,engine:await fixture.browser.version(),viewport:'360x800',scope:'synthetic actual rc1 SessionNodeItem + React/Primitives, fixture layout; not whole-device navigation',results},null,2));
}finally{await fixture.close();}
