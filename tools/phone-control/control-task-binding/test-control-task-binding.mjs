import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { resolve, join } from 'node:path'
import { cp, mkdir, writeFile, symlink } from 'node:fs/promises'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { spawn, spawnSync } from 'node:child_process'
import { testRuntime } from '../../test-runtime-fixture.mjs'

// Real pinned rc3 Agent turns + ToolRuntime + public MCP adapter + SDK stdio +
// production server.cjs + production Java task authority over host-only HTTP.
// Token IO and Android execution are explicit fixture seams; no device is contacted.
const root=resolve('.'), output=resolve(process.argv[2]||'app/build/control-task-binding')
await mkdir(output,{recursive:true})
const runtime=testRuntime('raw'), require=createRequire(join(runtime,'package.json'))
const load=name=>import(pathToFileURL(require.resolve('@deepseek-ai/'+name)))
const {Context}=await load('cordis'), {createUserMessage}=await load('dsh-llm')
const pluginSource=join(root,'app/src/main/assets/builtin-plugins/dsh-computer-use-android')
const javaSource=join(root,'app/src/main/java/com/deepseekharness/app/control/ControlTaskAuthority.java')
const classes=join(output,'classes');await mkdir(classes,{recursive:true})
const built=spawnSync('javac',['--release','17','-d',classes,javaSource,
  join(root,'tools/phone-control/control-task-binding/AuthorityHost.java')],{encoding:'utf8',windowsHide:true})
assert.equal(built.status,0,built.stderr)
const host=spawn('java',['-cp',classes,'AuthorityHost'],{stdio:['ignore','pipe','pipe'],windowsHide:true})
let hostOutput='',hostErrors=''
host.stderr.on('data',block=>hostErrors+=block)
const port=await new Promise((resolvePort,reject)=>{
  const timer=setTimeout(()=>reject(Error('AUTHORITY_HOST_TIMEOUT')),10000)
  host.stdout.on('data',block=>{hostOutput+=block;const found=/PORT=([0-9]+)/.exec(hostOutput)
    if(found){clearTimeout(timer);resolvePort(found[1])}})
  host.on('error',reject)
})
const base='http://127.0.0.1:'+port, context=new Context(), calls=[], results=[]
async function native(operation,sessionId,turnId,extra={}) {
  const url=new URL(base+'/app/ui/'+operation)
  for(const [key,value]of Object.entries({taskSessionId:sessionId,turnId,...extra}))url.searchParams.set(key,value)
  return (await (await fetch(url,{headers:{'X-Token':'fixture-token'}})).json()).result
}
try {
  const directory=join(output,'plugin');await cp(pluginSource,directory,{recursive:true})
  try{await symlink(join(runtime,'node_modules'),join(output,'node_modules'),'junction')}catch(error){if(error.code!=='EEXIST')throw error}
  // Only token IO and destination port change; run the same exported MCP receive
  // loop, validation and request implementation used by the production entrypoint.
  await writeFile(join(directory,'lib/server.cjs'),`const server=require(${JSON.stringify(join(pluginSource,'lib/server.cjs'))});
server.startServer({fs:{async readFile(file){if(file!=='/root/.dsh/.bridge_token')throw Error('FIXTURE_PATH');return 'fixture-token';}},
fetch(url,options){return fetch(${JSON.stringify(base)}+url.pathname+url.search,options);}});\n`)
  const modules=await Promise.all(['dsh-session','dsh-agent','dsh-session-projection','dsh-llm',
    'dsh-system-prompt','dsh-tools','dsh-agent-loop','dsh-computer-use'].map(load))
  for(let index=0;index<modules.length;index++)await context.plugin(modules[index].default,
    index===5?{mode:'native'}:index===6?{agents:[],maxParallelToolCalls:1}:{})
  const android=await import(pathToFileURL(join(directory,'lib/index.js')))
  await context.plugin(android,{})
  const handles=await Promise.all(['prefix-A-same1234','prefix-B-same1234'].map(sessionId=>
    context.agents.create({sessionId,meta:{cwd:root}})))
  const [a,b]=handles.map(handle=>handle.agent), plans=new Map()
  for(const agent of [a,b])agent.ctx.on('agent/pre-step',async function(input){
    const run=plans.get(agent.id);assert.ok(run,'explicit real turn plan')
    await run(input.signal)
    return {kind:'reject'} // End the actual turn through the public hook; no model or network.
  })
  const run=(agent,plan)=>{
    plans.set(agent.id,plan)
    agent.send(createUserMessage({source:{kind:'user'},content:[{type:'text',text:'host task identity fixture'}]}),'next-turn',true)
    return agent.whenIdle()
  }
  const call=async(agent,name,args,signal)=>{
    const value=await context.tools.execute({callId:'physical-'+(calls.length+1),
      name:'mcp__dsha-android__'+name,arguments:args,agent,signal})
    calls.push({sessionId:agent.id,name,isError:value.isError,text:value.content.map(part=>part.text||'').join('')})
    return calls.at(-1)
  }
  const isError=(value,code)=>{assert.equal(value.isError,true);assert.match(value.text,new RegExp(code))}
  let releaseA,startedA
  const aReady=new Promise(resolveReady=>startedA=resolveReady), aHold=new Promise(release=>releaseA=release)
  const first=run(a,async signal=>{
    const begin=await call(a,'android_control',{action:'begin'},signal)
    assert.equal(begin.isError,false);assert.match(begin.text,/needsObservation/)
    isError(await call(a,'android_key',{name:'back'},signal),'FRESH_SCREEN_OBSERVATION_REQUIRED')
    const observed=await call(a,'android_get_state',{},signal);assert.equal(observed.isError,false)
    assert.match(observed.text,/prefix-A-same1234/);assert.match(observed.text,/"turnId":"1"/)
    startedA();await aHold
    assert.equal((await call(a,'android_key',{name:'back'},signal)).isError,false)
  })
  await aReady
  await run(b,async signal=>isError(await call(b,'android_control',{action:'begin'},signal),'PHYSICAL_SCREEN_BUSY'))
  releaseA();await first
  results.push('two concurrent real conversations preserve full IDs and exclusive ownership')
  // No lifecycle notification yet: next execution's actual completedTurnId must
  // close turn 1 before beginning turn 2, and action cannot inherit its observation.
  await run(a,async signal=>{
    isError(await call(a,'android_key',{name:'back'},signal),'FRESH_SCREEN_OBSERVATION_REQUIRED')
    assert.equal((await call(a,'android_get_state',{},signal)).isError,false)
    assert.equal(await native('task-end',a.id,'1'),'OK') // late stale turn/end
    assert.equal((await call(a,'android_key',{name:'back'},signal)).isError,false)
    assert.match(await native('fixture-pause',b.id,'1'),/SCREEN_TASK_CHANGED/)
    assert.equal((await call(a,'android_key',{name:'back'},signal)).isError,false)
    assert.equal(await native('fixture-pause',a.id,'2'),'OK')
    isError(await call(a,'android_key',{name:'back'},signal),'SCREEN_CONTROL_PAUSED')
    assert.equal(await native('fixture-resume',a.id,'2'),'OK')
    isError(await call(a,'android_key',{name:'back'},signal),'FRESH_SCREEN_OBSERVATION_REQUIRED')
    assert.equal((await call(a,'android_get_state',{},signal)).isError,false)
    assert.equal((await call(a,'android_key',{name:'back'},signal)).isError,false)
  })
  results.push('consecutive real turns retire old ownership; late completion and wrong task pause preserve current task; resume requires fresh observation')
  assert.equal(await native('task-end',a.id,'2'),'OK')
  assert.match(await native('dump',a.id,'1'),/SCREEN_TURN_ENDED/)
  assert.match(await native('dump',a.id,'2'),/SCREEN_TURN_ENDED/)
  await run(b,async signal=>{
    assert.equal((await call(b,'android_control',{action:'begin'},signal)).isError,false)
    isError(await call(b,'android_key',{name:'home'},signal),'FRESH_SCREEN_OBSERVATION_REQUIRED')
    assert.equal((await call(b,'android_get_state',{},signal)).isError,false)
    assert.equal((await call(b,'android_control',{action:'finish'},signal)).isError,false)
    isError(await call(b,'android_get_state',{},signal),'SCREEN_TURN_ENDED')
  })
  results.push('completed turns stay fenced; independent next conversation can acquire after exact completion')
  results.push('explicit begin uses the actual Agent conversation without granting an observation; finish retires the exact turn without model identity arguments')
  const unbound=await context.tools.execute({callId:'missing-agent',name:'mcp__dsha-android__android_get_state',
    arguments:{},signal:new AbortController().signal})
  assert.equal(unbound.isError,true);assert.match(unbound.content[0].text,/SCREEN_TASK_IDENTITY_REQUIRED/)
  const ended=await context.tools.execute({callId:'ended-agent',name:'mcp__dsha-android__android_get_state',
    arguments:{},agent:b,signal:new AbortController().signal})
  assert.equal(ended.isError,true);assert.match(ended.content[0].text,/SCREEN_TURN_NOT_ACTIVE/)
  assert.equal(context.tools.schemas(a).some(schema=>JSON.stringify(schema).includes('taskSessionId')),false)
  results.push('missing or ended caller rejected; model cannot supply task identity fields')
  for(const agent of [a,b]) {
    const events=agent.session.snapshotEvents(),starts=events.filter(event=>event.type==='turn/start'),ends=events.filter(event=>event.type==='turn/end')
    assert.deepEqual(starts.map(event=>event.data.turn),[1,2]);assert.deepEqual(ends.map(event=>event.data.turn),[1,2])
  }
  const inputs=['app/src/main/java/com/deepseekharness/app/control/ControlTaskAuthority.java',
    'app/src/main/assets/builtin-plugins/dsh-computer-use-android/lib/index.js',
    'app/src/main/assets/builtin-plugins/dsh-computer-use-android/lib/server.cjs',
    'app/src/main/assets/builtin-plugins/dsh-computer-use-android/lib/task-binding.js',
    'tools/phone-control/control-task-binding/AuthorityHost.java','tools/phone-control/control-task-binding/test-control-task-binding.mjs']
  const receipt={scope:'REAL_RC3_AGENT_TOOL_MCP_STDIO_PRODUCTION_AUTHORITY_HOST_HTTP',
    dshVersion:'0.2.1-rc.3',deviceContacted:false,androidExecution:false,results,calls,
    inputs:Object.fromEntries(inputs.map(file=>[file,createHash('sha256').update(readFileSync(file)).digest('hex')]))}
  await writeFile(join(output,'receipt.json'),JSON.stringify(receipt,null,2)+'\n')
  console.log(JSON.stringify({passed:results.length,calls:calls.length,results}))
} finally {
  await context.fiber.dispose();host.kill();assert.equal(hostErrors,'')
}
