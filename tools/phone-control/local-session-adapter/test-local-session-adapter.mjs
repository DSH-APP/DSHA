import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { join, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { createServer } from 'node:http'
import { createHash } from 'node:crypto'
import { testRuntime } from '../../test-runtime-fixture.mjs'
import { sessionAdapter, phoneTaskEnvironment, installSessionAdapter, hostInstanceId } from '../../../app/src/main/assets/builtin-plugins/dsh-status-overlay/lib/session-adapter.js'
const inputs = ['tools/phone-control/local-session-adapter/test-local-session-adapter.mjs',
  'app/src/main/assets/builtin-plugins/dsh-status-overlay/lib/session-adapter.js']
process.stderr.write(JSON.stringify({ stage: 'assistant-local-runtime-proof-start', inputs: Object.fromEntries(inputs.map(file =>
  [file, createHash('sha256').update(readFileSync(file)).digest('hex')])) }) + '\n')
const runtime = testRuntime('raw')
process.stderr.write(JSON.stringify({ stage: 'assistant-local-runtime-proof-complete', runtime }) + '\n')
const require = createRequire(join(runtime, 'package.json'))
const load = name => import(pathToFileURL(require.resolve('@deepseek-ai/' + name)))
const { Context } = await load('cordis')
const { SessionController } = await load('dsh-api-session-controller')
const SessionQuery = (await load('dsh-session-query')).default
const { createUserMessage, createAssistantMessage } = await load('dsh-llm')
const titleModule = await load('dsh-session-title')
const modules = await Promise.all(['dsh-session', 'dsh-agent', 'dsh-session-projection', 'dsh-llm',
  'dsh-system-prompt', 'dsh-tools', 'dsh-agent-loop'].map(load))
/** Real locked SessionController over real SessionQuery, Agents, Sessions and Inbox.
 * Storage/auth/attachment ports are isolated fixtures; no model, network, file mutation,
 * complete Host assembly or Android is claimed here.
 */
async function hostContext(run) {
  const ctx = new Context(), routes = []
  try {
    for (let index = 0; index < modules.length; index++)
      await ctx.plugin(modules[index].default, index === 6 ? { agents: [], maxParallelToolCalls: 1 } : {})
    await ctx.plugin(titleModule.default, { fallbackMaxWords: 12, fallbackMaxBytes: 100, maxTitleBytes: 200 })
    await ctx.plugin(SessionQuery, {})
    ctx.provide('agentDefaultModel', { currentSelection: () => ({ provider: 'owned-test', model: 'no-network' }) })
    ctx.provide('typert', { resources: { register() {} }, lookups: { configure() {} }, contexts: { configureHost() {} } })
    ctx.provide('typertGateway', { getPendingRemoteEvents: () => [] })
    ctx.provide('remoteAccess', { allows: () => true })
    ctx.provide('workspaceRegistry', { archivedSessionIds: [], get() {} })
    ctx.provide('fileUploads', { registerAgentResolver() { return () => {} },
      bindPrompt() { return { commit() {}, [Symbol.dispose]() {} } } })
    ctx.provide('attachments', { admitPromptContent: async content => content,
      imageLimits: { maxImageBytes: 1024, maxImagesPerMessage: 1, maxMessageImageBytes: 1024,
        maxImagePixels: 1024, maxImageDimension: 32, mediaTypes: ['image/png'] } })
    ctx.provide('fs', {})
    ctx.provide('webServer', { register(route) { routes.push(route); return () => routes.splice(routes.indexOf(route), 1) } })
    ctx.provide('connection', { requestRejection: req => req.headers.cookie === 'fixture-auth=owned' ? undefined : 401 })
    await ctx.plugin(SessionController, { nativeOpen: false })
    const controller = ctx.sessionController
    assert.ok(controller, 'the actual locked Controller plugin and its declared dependencies activate')
    await run({ ctx, controller, routes })
  } finally { await ctx.fiber.dispose() }
}
async function fixture(run) {
  await hostContext(async ({ ctx, controller }) => {
    const handles = await Promise.all(['prefix-A-same1234', 'prefix-B-same1234'].map(sessionId => ctx.agents.create({ sessionId, meta: { cwd: resolve('.') } })))
    // Real passive state, history and prompt entrypoints; only prompt admission is replaced
    // so queue/steer modes stay observable without any model, provider or network.
    const modes = []
    controller.commands = { prompt(request, source) {
      modes.push(request.mode)
      ctx.agents.get(request.sessionId).send(createUserMessage({ source, content: request.content }), 'next-turn', false)
      return { accepted: true }
    } }
    const call = sessionAdapter({ sessionController: controller, agents: ctx.agents }, 'host-actual-fixture')
    await run(ctx, call, handles.map(handle => handle.agent), modes)
  })
}
const args = sessionId => ({ hostInstanceId: 'host-actual-fixture', sessionId })

test('send forwards an explicit steer mode, defaults to queue and rejects unknown modes', () => fixture(async (_ctx, call, [agent], modes) => {
  await call('sessions.send', { ...args(agent.id), text: 'queued by default', requestId: 'mode-default' })
  await call('sessions.send', { ...args(agent.id), text: 'into the running turn', requestId: 'mode-steer', mode: 'steer' })
  await assert.rejects(call('sessions.send', { ...args(agent.id), text: 'bad', requestId: 'mode-bad', mode: 'interrupt' }), /PROMPT_INVALID/)
  assert.deepEqual(modes, ['queue', 'steer'])
}))

test('status reads current passive state and history without the removed inspect entrypoint', () => fixture(async (ctx, call, [agent]) => {
  // The pinned Host has no SessionController.inspect; a regression here is the build336 409.
  assert.equal(typeof ctx.sessionController.inspect, 'undefined')
  ctx.sessionController.inspect = () => { throw new Error('removed inspect must never be called') }
  agent.session.append('turn/start', { turn: 6 })
  const status = await call('sessions.status', args(agent.id))
  assert.deepEqual(Object.keys(status).sort(), ['failed','hostInstanceId','messages','running','sessionId','streamText','taskId','text','turnActive','turnId'])
  assert.equal(status.sessionId, agent.id); assert.equal(status.hostInstanceId, 'host-actual-fixture')
  assert.equal(status.taskId, agent.id); assert.equal(status.turnId, '6'); assert.equal(status.turnActive, true)
  assert.equal(status.running, false); assert.equal(status.failed, false)
  assert.deepEqual(status.messages, []); assert.equal(status.streamText, ''); assert.equal(status.text, '')
}))

test('a long open turn keeps its identity while the transcript stays bounded to the newest eight messages', () => fixture(async (_ctx, call, [agent]) => {
  agent.session.append('turn/start', { turn: 12 })
  for (let index = 0; index < 20; index++)
    agent.session.append('user/message', createUserMessage({ source: { kind: 'user', rpcId: 'long-turn-' + index },
      content: [{ type: 'text', text: 'long turn message ' + index }] }), { surfaceOp: 'append' })
  const status = await call('sessions.status', args(agent.id))
  // The current turn started before the bounded page cut; the Host projection still names it.
  assert.equal(status.turnId, '12'); assert.equal(status.turnActive, true)
  assert.deepEqual(status.messages.map(item => item.text),
    Array.from({ length: 8 }, (_, index) => 'long turn message ' + (12 + index)))
  assert.ok(!status.text.includes('long turn message 11'))
  assert.ok(status.text.endsWith('long turn message 19'))
}))

test('real locked Session inspection retains complete IDs sharing the same suffix', () => fixture(async (ctx, call, [a,b]) => {
  a.session.append('turn/start', { turn: 17 }); b.session.append('turn/start', { turn: 29 })
  a.session.append('assistant/message', { turn: 17, step: 1, message: createAssistantMessage({
    source: { provider: 'owned-test', model: 'no-network' }, content: [{ type: 'text', text: 'Committed assistant reply survives stream removal' }],
  }), stream: [] }, { surfaceOp: 'append' })
  const first = await call('sessions.status', args(a.id)), second = await call('sessions.status', args(b.id))
  assert.equal(first.sessionId, a.id); assert.equal(first.turnId, '17'); assert.equal(first.turnActive, true)
  assert.equal(second.sessionId, b.id); assert.equal(second.turnId, '29'); assert.equal(second.turnActive, true); assert.notEqual(first.taskId, second.taskId)
  assert.match(first.text, /Committed assistant reply survives stream removal/)
  assert.deepEqual(phoneTaskEnvironment({ agent: a }), { DSH_PHONE_SESSION_ID: a.id, DSH_PHONE_TURN_ID: '17' })
  assert.deepEqual(phoneTaskEnvironment({ agent: b }), { DSH_PHONE_SESSION_ID: b.id, DSH_PHONE_TURN_ID: '29' })
  assert.deepEqual(phoneTaskEnvironment({}), {})
}))
test('host change rejects before prompt admission; actual public prompt creates an identified real Inbox occurrence', () => fixture(async (ctx, call, [agent]) => {
  await assert.rejects(call('sessions.send', { ...args(agent.id), hostInstanceId: 'old-host', text: 'no', requestId: 'r0' }), /HOST_INSTANCE_CHANGED/)
  const receipt = await call('sessions.send', { ...args(agent.id), text: 'retained text', requestId: 'request-full-id' })
  assert.equal(receipt.accepted, true); assert.equal(agent.inbox.nextTurn.length, 1)
  assert.equal(agent.inbox.nextTurn[0].source.rpcId, 'request-full-id')
  assert.equal(agent.inbox.nextTurn[0].content[0].text, 'retained text')
  const status = await call('sessions.status', args(agent.id)); assert.equal(status.running, true, 'queued input is not idle')
  assert.equal(status.turnActive, false, 'queued input cannot authorize attachment to a phone turn which has not started')
}))
test('ended real turn cannot authorize a phone overlay while a later human message is queued', () => fixture(async (ctx, call, [agent]) => {
  agent.session.append('turn/start', { turn: 3 })
  agent.session.append('turn/end', { turn: 3, reason: { kind: 'completed' } })
  await call('sessions.send', { ...args(agent.id), text: 'quoted android_get_state and phone-control discussion', requestId: 'quoted-control-only' })
  const queued = await call('sessions.status', args(agent.id))
  assert.equal(queued.running, true)
  assert.equal(queued.turnId, '3')
  assert.equal(queued.turnActive, false)
  agent.session.append('turn/start', { turn: 4 })
  const started = await call('sessions.status', args(agent.id))
  assert.equal(started.turnId, '4'); assert.equal(started.turnActive, true)
  // A late end from the previous turn cannot retire the actual new one.
  agent.session.append('turn/end', { turn: 3, reason: { kind: 'completed' } })
  assert.equal((await call('sessions.status', args(agent.id))).turnActive, true)
}))
test('pause cancels actual Agent maintenance and pending Inbox, waits for quiescence, leaves conversation attached', () => fixture(async (ctx, call, [agent]) => {
  let cancelled = false, settled = false
  agent.session.append('turn/start', { turn: 3 })
  const work = agent.runMaintenance(signal => new Promise(resolve => signal.addEventListener('abort', () => {
    cancelled = true; setTimeout(() => { settled = true; resolve() }, 20)
  }, { once: true })))
  await call('sessions.send', { ...args(agent.id), text: 'queued-do-not-replay', requestId: 'queued1' })
  const result = await call('sessions.cancel', { ...args(agent.id), turnId: '3' })
  assert.equal(result.accepted, true); assert.equal(cancelled, true); assert.equal(settled, true)
  await work; assert.equal(agent.inbox.nextTurn.length, 0); assert.equal(ctx.agents.get(agent.id), agent)
  assert.equal((await call('sessions.status', args(agent.id))).running, false)
}))
test('stale turn cancellation is rejected without cancelling a newer real turn', () => fixture(async (ctx, call, [agent]) => {
  agent.session.append('turn/start', { turn: 8 })
  await assert.rejects(call('sessions.cancel', { ...args(agent.id), turnId: '7' }), /TURN_CHANGED/)
  assert.equal(agent.session.snapshotEvents().at(-1).data.turn, 8)
}))

test('actual message provenance hides time/runtime injections while preserving identical human text', () => fixture(async (ctx,call,[agent]) => {
  const append = (text,source) => agent.session.append('user/message', createUserMessage({source,content:[{type:'text',text}]}), {surfaceOp:'append'})
  const human = 'Time sampled while preparing turn 3, step 1: this is my own quoted text. DSHA runtime context.'
  append(human,{kind:'user',rpcId:'human-provenance'})
  append('HIDDEN_TIME',{kind:'time-context',form:'snapshot',sections:[{name:'time-context',text:'HIDDEN_TIME'}]})
  append('HIDDEN_RUNTIME',{kind:'plugin',plugin:'dsh-device-shell-guide',form:'snapshot',sections:[{name:'runtime-context',text:'HIDDEN_RUNTIME'}]})
  const result = await call('sessions.status',args(agent.id))
  assert.deepEqual(result.messages,[{role:'user',text:human}])
  assert.equal(result.text,human)
  assert.ok(!JSON.stringify(result).includes('HIDDEN_'))
}))

/** Real controller, route and authenticated loopback transport over an isolated workspace. */
async function hostFixture(run) {
  const directory = await mkdtemp(join(tmpdir(), 'dsha-assistant-current-'))
  const retired = []
  let server
  try {
    await hostContext(async ({ ctx, controller, routes }) => {
      controller.commands.defaultCwd = directory
      installSessionAdapter(ctx, () => 'fixture live stream', identity => retired.push(identity))
      // The actual injected registration must activate, rather than exercising an unmounted helper.
      await new Promise(resolve => setImmediate(resolve))
      const route = routes.find(route => route.path === '/api/dsha-assistant')
      assert.ok(route, 'installed exact assistant route activates against the actual locked Controller')
      server = createServer((req,res) => req.url === route.path ? route.handler(req,res) : (res.writeHead(404),res.end()))
      await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
      const url = `http://127.0.0.1:${server.address().port}${route.path}`
      const request = async (method, args = {}, headers = {}) => {
        const response = await fetch(url, { method: 'POST', headers: { Cookie: 'fixture-auth=owned', ...headers }, body: JSON.stringify({ method,args }) })
        return { status: response.status, ...await response.json() }
      }
      await run({ ctx,controller,request,retired,url })
    })
  } finally {
    if (server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)) }
    assert.ok(resolve(directory).startsWith(resolve(tmpdir(), 'dsha-assistant-current-')))
    await rm(directory, { recursive: true, force: true })
  }
}
test('installed HTTP route lists and creates via the actual locked Controller, then admits and cancels exact conversation', () => hostFixture(async ({ctx,request,retired}) => {
  const first = await request('sessions.list')
  assert.equal(first.ok, true); assert.deepEqual(first.value.items, [])
  const host = first.value.hostInstanceId; assert.equal(host, hostInstanceId)
  const created = await request('sessions.create', { hostInstanceId: host })
  assert.equal(created.ok, true)
  const sessionId = created.value.sessionId, agent = ctx.agents.get(sessionId)
  assert.ok(sessionId.startsWith('session-')); assert.ok(agent)
  assert.equal((await request('sessions.list')).value.items[0].sessionId, sessionId)
  // Maintenance holds the real executor so public followup queues without contacting any model.
  const maintenance = agent.runMaintenance(signal => new Promise(resolve => signal.addEventListener('abort', resolve, { once: true })))
  agent.session.append('turn/start', { turn: 41 })
  assert.deepEqual(phoneTaskEnvironment({agent}), { DSH_PHONE_SESSION_ID: sessionId, DSH_PHONE_TURN_ID: '41' })
  const identity = { hostInstanceId: host, sessionId }
  const receipt = await request('sessions.send', { ...identity, requestId: 'real-command-admission', text: 'real native conversation prompt' })
  assert.equal(receipt.ok, true); assert.equal(receipt.value.accepted, true)
  assert.equal(agent.inbox.nextTurn[0].content[0].text, 'real native conversation prompt')
  const running = await request('sessions.status', identity)
  assert.equal(running.ok, true, JSON.stringify(running)); assert.equal(running.value.running, true)
  assert.equal(running.value.turnId, '41'); assert.equal(running.value.turnActive, true)
  assert.equal((await request('sessions.list')).value.items[0].running, true)
  const stopped = await request('sessions.cancel', { ...identity, turnId: '41' })
  assert.equal(stopped.ok, true); await maintenance
  const after = await request('sessions.status', identity)
  assert.equal(after.value.running, false); assert.equal(agent.inbox.nextTurn.length, 0)
  assert.equal(ctx.agents.get(sessionId), agent)
  agent.session.append('turn/end', { turn: 41, reason: { kind: 'cancelled' } })
  agent.session.append('turn/start', { turn: 42 })
  assert.deepEqual(retired, [{ sessionId, turnId: '41' }], 'old turn retirement retains the event identity')
  assert.deepEqual(phoneTaskEnvironment({agent}), { DSH_PHONE_SESSION_ID: sessionId, DSH_PHONE_TURN_ID: '42', DSH_PHONE_COMPLETED_TURN_ID: '41' })
  agent.session.append('turn/end', { turn: 42, reason: { kind: 'cancelled' } })
  assert.deepEqual(phoneTaskEnvironment({agent}), {}, 'closed turn cannot acquire a new shell binding')
  await ctx.sessionController.rename({ sessionId, title: 'Recognizable complete conversation' })
  ctx.sessionProjections.snapshot(agent.session, ['title'])
  assert.equal((await request('sessions.list')).value.items[0].title, 'Recognizable complete conversation')
  agent.session.append('user/message', createUserMessage({ source: { kind: 'user', rpcId: 'committed-user' }, content: [{ type: 'text', text: 'my actual committed prompt' }] }), { surfaceOp: 'append' })
  agent.session.append('assistant/message', { turn: 42, step: 1, message: createAssistantMessage({
    source: { provider: 'owned-test', model: 'no-network' }, content: [{ type: 'text', text: 'actual committed reply' }],
  }), stream: [] }, { surfaceOp: 'append' })
  const transcript = (await request('sessions.status', identity)).value
  assert.deepEqual(transcript.messages.map(item => item.role), ['user','assistant'])
  assert.equal(transcript.messages[1].text, 'actual committed reply')
  assert.equal(transcript.streamText, '', 'idle never appends an old live-stream line')
  ctx.emit('agent/error', { agent, error: new Error('provider fixture failure') })
  assert.equal((await request('sessions.status', identity)).value.failed, true)
  agent.session.append('turn/start', { turn: 43 })
  assert.equal((await request('sessions.status', identity)).value.failed, false)
}))

test('installed HTTP route denies missing auth/cross origin and preserves unknown outcomes without leaked errors', () => hostFixture(async ({ctx,controller,request,url}) => {
  assert.equal((await request('sessions.list', {}, { Cookie: '' })).status, 401)
  assert.equal((await request('sessions.list', {}, { Origin: 'https://different.example' })).status, 403)
  const created = await request('sessions.create', { hostInstanceId })
  const identity = { sessionId: created.value.sessionId, hostInstanceId }
  const missing = await request('sessions.status', { hostInstanceId, sessionId: 'session-missing' })
  assert.equal(missing.status, 409); assert.equal(missing.code, 'session/not-found')
  const stale = await request('sessions.send', { ...identity, hostInstanceId: 'stale', text: 'blocked', requestId: 'stale' })
  assert.equal(stale.rejected, true); assert.equal(ctx.agents.get(identity.sessionId).inbox.nextTurn.length, 0)
  // Explicit injected pre-admission failure and transport-like error must remain distinguishable.
  const realPrompt = controller.prompt
  controller.prompt = async () => { throw Object.assign(new Error('secret provider key must never leave the host'), { code: 'session/model-unavailable' }) }
  const rejected = await request('sessions.send', { ...identity, text: 'draft retained', requestId: 'model-missing' })
  assert.equal(rejected.rejected, true); assert.equal(rejected.code, 'session/model-unavailable')
  controller.prompt = async () => { throw new Error('secret provider key must never leave the host') }
  const unknown = await request('sessions.send', { ...identity, text: 'do not replay', requestId: 'unknown' })
  assert.equal(unknown.rejected, undefined); assert.equal(unknown.code, 'SESSION_REQUEST_FAILED')
  controller.prompt = realPrompt
  const malformed = await fetch(url, { method: 'POST', headers: { Cookie: 'fixture-auth=owned' }, body: '{"method":"sessions.list","args":[]}' })
  assert.equal((await malformed.json()).code, 'SESSION_REQUEST_INVALID')
}))
