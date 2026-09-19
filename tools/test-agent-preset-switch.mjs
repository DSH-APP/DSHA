import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
const assets='app/src/main/assets/',spec=JSON.parse(readFileSync(assets+'agent-preset-patch.json','utf8'));
const runtime=process.env.DSHA_TEST_RUNTIME||'app/build/locked-dsh-runtime';
let source=readFileSync(runtime+'/node_modules/'+spec.module,'utf8');
for(const patch of spec.patches){const after=(patch.prependAsset?readFileSync(assets+patch.prependAsset,'utf8')+'\n':'')+patch.after;
 if(source.includes(after)&&!source.includes(patch.before))continue;
 assert.equal(source.split(patch.before).length-1,1);source=source.replace(patch.before,()=>after);
}
new vm.Script(source);
for(const patch of spec.patches){const after=(patch.prependAsset?readFileSync(assets+patch.prependAsset,'utf8')+'\n':'')+patch.after;
 assert.equal(source.split(after).length-1,1);assert.equal(source.split(patch.before).length-1,after.split(patch.before).length-1);
}
const context=vm.createContext({});vm.runInContext(readFileSync(assets+'web-integration/agent-preset-switch.js','utf8'),context);
test('预设样式在模块自身加载，旧补丁升级与重复应用都保持可识别',()=>{
 const css=readFileSync(assets+'web-integration/agent-preset-header.css','utf8');
 assert.ok(spec.patches.some(p=>p.after.includes(JSON.stringify(css))));
 for(const patch of spec.patches){const after=(patch.prependAsset?readFileSync(assets+patch.prependAsset,'utf8')+'\n':'')+patch.after;assert.ok(source.includes(after));}
 assert.match(css,/border: 0/);assert.match(css,/:focus-visible/);assert.match(css,/margin-inline-start: auto/);
});
function fixture(blank=true){const origin={id:'original',cwd:'/owned',blank,retainedBy:{mainView:1},projectionValues:{agentPreset:'standard'}};
 const list={current:'original',byId:{original:origin}},calls=[];
 const state={current:'standard',busy:false,options:['standard','ptc','minimal','cordis','my-custom-agent'].map(id=>({id}))};
 const seat={staged:{id:undefined,introduce:false},currentSession:()=>list.current===undefined?undefined:list.byId[list.current],store:{getSnapshot:()=>state},set:patch=>Object.assign(state,patch),stage(id){this.staged.id=id;state.current=id;}};
 const scope={sessions:{list:{getSnapshot:()=>list},async create(options){calls.push(['create',options]);list.byId.new={id:'new',blank:true,projectionValues:{agentPreset:'standard'}};return 'new';}},
  uiWorkspace:{workspaces:{list:{getSnapshot:()=>({items:[{workspaceId:'workspace',sessionIds:['original']}]})}},openSession(id){calls.push(['open',id]);list.current=id;}},
  remote:{agentPresets:{async select(target,id){calls.push(['select',target,id]);list.byId[target].projectionValues.agentPreset=id;return {ok:true,value:id};}}}};
 return {scope,seat,state,list,calls,run:context.createDshaAgentPresetSwitcher(scope,seat)};
}
test('四个选项在空会话逐一调用 Host，并以真实返回值更新标签',async()=>{
 const f=fixture();for(const id of ['ptc','minimal','cordis','standard']){
  assert.equal(await f.run(id),undefined);assert.equal(f.state.current,id);assert.equal(f.list.byId.original.projectionValues.agentPreset,id);
 }assert.equal(f.calls.filter(x=>x[0]==='select').length,4);assert.equal(f.calls.filter(x=>x[0]==='create').length,0);
});
test('自创预设沿用完整服务端列表并实际切换',async()=>{
 const f=fixture();await f.run('my-custom-agent');assert.equal(f.state.current,'my-custom-agent');assert.equal(f.calls[0][2],'my-custom-agent');
 assert.match(source,/items: state\.options\.map/);assert.doesNotMatch(source,/\["standard", "ptc", "minimal", "cordis"\]\.map/);
});
test('历史会话切换先创建同工作区空会话，确认 Host 后打开，原会话保持',async()=>{
 const f=fixture(false);await f.run('minimal');
 assert.deepEqual(f.calls.map(x=>x[0]),['create','select','open']);assert.equal(f.calls[0][1].workspaceId,'workspace');
 assert.equal(f.list.byId.original.projectionValues.agentPreset,'standard');assert.equal(f.list.current,'new');assert.equal(f.state.current,'minimal');
});
test('Host 拒绝、传输异常或返回不符都不能伪装成功或卡住控件',async()=>{
 for(const action of [async()=>({ok:false,error:{message:'refused',details:{}}}),async()=>{throw Error('offline');},async()=>({ok:true,value:'standard'})]){
  const f=fixture();f.scope.remote.agentPresets.select=action;
  assert.equal(typeof await f.run('ptc'),'string');assert.equal(f.state.current,'standard');assert.equal(f.state.busy,false);assert.equal(f.list.current,'original');
 }
});
test('连点单飞、切换期间导航和未知预设不会覆盖其他会话',async()=>{
 const f=fixture(false);let complete;f.scope.remote.agentPresets.select=()=>new Promise(r=>complete=r);
 const first=f.run('ptc');await new Promise(r=>setImmediate(r));
 assert.equal(typeof await f.run('cordis'),'string');assert.equal(f.calls.filter(x=>x[0]==='create').length,1);
 f.list.byId.another={id:'another',projectionValues:{agentPreset:'standard'}};f.list.current='another';complete({ok:true,value:'ptc'});await first;
 assert.equal(f.list.current,'another');assert.equal(f.state.current,'standard');assert.equal(f.state.busy,false);
 const empty=fixture();assert.equal(typeof await empty.run('unknown'),'string');assert.equal(empty.calls.length,0);
});
test('还没有会话时只暂存下一会话选择，不报告一次不存在的 Host 切换',async()=>{
 const f=fixture();f.list.current=undefined;assert.equal(await f.run('cordis'),undefined);assert.equal(f.seat.staged.id,'cordis');assert.equal(f.calls.length,0);
});
test('多会话保留时原会话失去主视图，不抢回用户的新导航',async()=>{
 const f=fixture(false);let complete;f.scope.remote.agentPresets.select=()=>new Promise(r=>complete=r);
 const first=f.run('ptc');await new Promise(r=>setImmediate(r));
 f.list.byId.original.retainedBy.mainView=0;complete({ok:true,value:'ptc'});
 assert.equal(typeof await first,'string');assert.equal(f.calls.filter(x=>x[0]==='open').length,0);
 assert.equal(f.state.busy,false);assert.equal(typeof f.seat.staged,'object');
});
