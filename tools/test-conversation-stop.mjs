// Render the real locked rc2 InputBar and call its stop controls. A draft,
// attachment or submitting phase must never remove ordinary turn cancellation.
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
import {testRuntime} from './test-runtime-fixture.mjs';
const runtime=process.env.DSHA_STOP_RUNTIME ? testRuntime('raw', 'DSHA_STOP_RUNTIME') : testRuntime('raw');
let source=readFileSync(runtime+'/node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js','utf8');
const policy=JSON.parse(readFileSync('app/src/main/assets/composer-enter-patch.json','utf8'));
for(const {before,after}of policy.patches){assert.equal(source.split(before).length-1,1);source=source.replace(before,after);}
const start=source.indexOf('\t\tconst InputBar = '),end=source.indexOf('\n\t\t//#endregion',start);
assert.ok(start>0&&end>start);
function render({running=true,draft='',phase='plain',attachments=[],subagent=null,blocked,stopProvided=true}={}){
  let stops=0,submits=0;
  const session={running,subagent,removed:false,promptError:null};
  const input={draft,phase,attachmentIds:attachments.map((_,i)=>''+i),queue:[]};
  const jsx=(type,props)=>({type,props});
  const react={memo:f=>f,useState:v=>[v,()=>{}],useRef:current=>({current}),useEffect:()=>{},useLayoutEffect:()=>{},useCallback:f=>f,useMemo:f=>f()};
  const context={react,react_jsx_runtime:{jsx,jsxs:jsx},clsx:(...xs)=>xs.filter(Boolean).join(' '),InputBar_module_css_default:new Proxy({}, {get:(_,key)=>key}),
    _deepseek_ai_dsh_client_ui_primitives:{Tooltip:'Tooltip'},resolveSubmitMode:()=> 'queue',ComposerContentEditable:'Editor',ContextMeter:'Meter',
    AttachmentChip:'AttachmentChip',DraftEditor:'DraftEditor',PromptNotices:'PromptNotices',iconPlus:'plus'};
  vm.createContext(context);vm.runInContext(source.slice(start,end)+'\nglobalThis.render=InputBar;',context);
  const tree=context.render({useSession:select=>select(session),useInput:select=>select(input),inputActions:{},keyboard:{editor:null,submit(){submits++;}},
    resolveDraftAttachments:()=>attachments,useBusyEnter:select=>select('queue'),useFileUploads:select=>select({}),useNotices:select=>select(null),
    useLexicon:select=>select([]),useMenuLauncher:select=>select(null),useStopShortcut:select=>select([]),useProjection:()=>undefined,
    stop:stopProvided?()=>stops++:undefined,t:key=>key,renderSlot:()=>null,sessionId:'fixture-session',variant:'composer',blocked});
  const buttons=[];
  function visit(value){if(!value||typeof value!=='object')return;if(Array.isArray(value)){value.forEach(visit);return;}if(value.type==='button')buttons.push(value);visit(value.props?.children);}
  visit(tree);
  return {buttons,stopButtons:buttons.filter(b=>b.props['aria-label']==='input.stop'),counts:()=>({stops,submits})};
}
test('ordinary running turn remains stoppable with text draft, pending admission and attachments',()=>{
  for(const state of [{draft:'next instruction'},{draft:'admitting',phase:'submitting'},{draft:'',attachments:[{kind:'file',id:'f'}]}]){
    const f=render(state);assert.equal(f.stopButtons.length,1);assert.equal(f.stopButtons[0].props.disabled,false);f.stopButtons[0].props.onClick();assert.deepEqual(f.counts(),{stops:1,submits:0});
  }
});
test('empty running ordinary turn uses one primary stop; blocked draft still stops',()=>{
  for(const state of [{},{draft:'blocked draft',blocked:'plugin-gate'}]){const f=render(state);assert.equal(f.stopButtons.length,1);f.stopButtons[0].props.onClick();assert.equal(f.counts().stops,1);}
});
test('idle and read-only subagent do not offer cancellation; missing handler stays disabled',()=>{
  assert.equal(render({running:false,draft:'hello'}).stopButtons.length,0);
  assert.equal(render({subagent:{address:{mode:'readonly'}}}).stopButtons.length,0);
  const missing=render({draft:'hello',stopProvided:false});assert.equal(missing.stopButtons[0].props.disabled,true);
  const child=render({subagent:{address:{mode:'continuable'},parentAvailable:false}});assert.equal(child.stopButtons.length,1);child.stopButtons[0].props.onClick();assert.equal(child.counts().stops,1);
});
test('Host cancellation keeps queued inbox work and calls the current Agent once',()=>{
  const server=readFileSync(runtime+'/node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js','utf8');
  const begin=server.indexOf('\tcancel(request) {'),finish=server.indexOf('\n\tasync resolveAgent(',begin);
  assert.ok(begin>0&&finish>begin);const calls=[];const agent={session:{},cancel:(...args)=>calls.push(args)};
  const context={hasApiSessionSubagentOwner:()=>false,apiSessionSubagentOwnershipError:()=>Error('owned'),RemoteError:Error};
  vm.createContext(context);vm.runInContext('globalThis.command={'+server.slice(begin,finish)+'};',context);
  context.command.ctx={agents:{get:id=>id==='fixture-session'?agent:undefined}};
  assert.equal(context.command.cancel({sessionId:'fixture-session'}).accepted,true);
  assert.equal(calls.length,1);assert.equal(calls[0][0].kind,'user');assert.equal(calls[0][1].keepInbox,true);
});
