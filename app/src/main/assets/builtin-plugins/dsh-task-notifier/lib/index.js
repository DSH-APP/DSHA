/** Real Agent turn facts and one-shot cancellation through the authenticated native bridge. */
import { randomBytes } from 'node:crypto'
import { readFile } from 'node:fs/promises'
export const inject = ['agents', 'sessionProjections', 'webServer']
export const name = 'dsh-task-notifier'
export function completionNotice(reason) {
  if (reason === 'completed') return ['DSHA · 任务完成', 'Agent 已完成一轮对话，点击查看结果']
  if (reason === 'max-tokens') return ['DSHA · 已达到输出上限', '请查看结果，按需继续对话']
  if (reason === 'aborted' || reason === 'interrupted') return ['DSHA · 任务已停止', '本轮任务未完成，点击查看状态']
  return ['DSHA · 任务需要处理', '本轮任务因错误或阻塞结束，请查看对话详情']
}
async function bridge(operation, value) {
  const token = (await readFile('/root/.dsh/.bridge_token', 'utf8')).trim()
  if (!token) throw Error('bridge unavailable')
  const response = await fetch('http://127.0.0.1:3090/app/tasks/' + operation
    + '?payload=' + encodeURIComponent(JSON.stringify(value)), {
    headers: { 'X-Token': token }, signal: AbortSignal.timeout(2000)
  })
  const result = (await response.json()).result
  if (!response.ok || typeof result !== 'string' || result.startsWith('[')) throw Error('task bridge rejected')
  return result === 'OK' ? true : JSON.parse(result)
}
function registrationFromEnv(env) {
  const generation = Number(env.DSH_TASK_GENERATION), instance = env.DSH_TASK_INSTANCE, nonce = env.DSH_TASK_TOKEN
  if (!Number.isSafeInteger(generation) || generation < 1 || typeof instance !== 'string' || !instance
      || typeof nonce !== 'string' || !/^[a-f0-9]{64}$/.test(nonce)) return undefined
  return Object.freeze({ generation, instance, nonce })
}
function processRegistration() {
  // A server-process protocol survives module HMR. The secret stays in a closure,
  // never a file/profile, browser global, or ambient child environment.
  const key = Symbol.for('dsha.formal-web-task-registration.v1')
  if (!Object.hasOwn(globalThis, key)) {
    let lease, sequence = 0, activeReporter
    const protocol = Object.freeze({ capture(env) {
      const offered = registrationFromEnv(env)
      for (const name of ['DSH_TASK_GENERATION', 'DSH_TASK_INSTANCE', 'DSH_TASK_TOKEN']) delete env[name]
      if (!lease && offered) lease = Object.freeze({ ...offered, client: randomBytes(16).toString('hex'),
        nextSequence: () => ++sequence,
        attach(reporter) { if (activeReporter && activeReporter !== reporter) activeReporter.dispose(); activeReporter = reporter }
      })
      return lease
    } })
    Object.defineProperty(globalThis, key, { value: protocol })
  }
  return globalThis[key].capture(process.env)
}
export class TaskReporter {
  constructor(ctx, transport = bridge, clock = Date.now, registration) {
    this.ctx = ctx; this.transport = transport; this.clock = clock
    this.registration = registration === undefined ? processRegistration() : registrationFromEnv(registration)
    this.client = this.registration?.client ?? randomBytes(16).toString('hex'); this.identity = undefined
    this.rows = new Map(); this.pending = new Map(); this.sequence = 0
    this.nextSequence = this.registration?.nextSequence ?? (() => ++this.sequence)
    this.controls = new Map()
    this.disposed = false; this.pumping = undefined; this.claiming = false
    this.registration?.attach?.(this)
  }
  event(session, event, emittingAgent) {
    const id = session?.id
    if (this.disposed || typeof id !== 'string' || !id || id.length > 256) return
    if (!['turn/start', 'assistant/chunk', 'tool/call', 'turn/end'].includes(event?.type)) return
    const data = event?.data
    const projected = this.ctx.sessionProjections.stateOf(session, 'turnBoundary')
    const turn = data?.turn ?? projected?.lastTurn
    if (!Number.isSafeInteger(turn) || turn < 1) return
    let row = this.rows.get(id)
    const current = this.ctx.agents.get(id)
    const surface = session.surface?.contentGeneration ?? 0
    const root = current?.session === session && typeof this.ctx.agents.roots === 'function' && this.ctx.agents.roots().includes(current)
    // End events have the same live ownership requirement as progress events.
    // Session ids, generation numbers and turn counters can all be reused after replacement.
    if (!root || (emittingAgent && emittingAgent !== current)) return
    if (row && row.sessionObject === session && row.surface === surface && row.agent === current && row.turn > turn) return
    if (!row || row.turn !== turn || row.sessionObject !== session || row.surface !== surface || row.agent !== current) {
      // A historical/replayed end cannot create a live task or a completion alert.
      if (event.type === 'turn/end') return
      const agent = current
      if (agent?.session !== session || agent.status !== 'running') return
      if (event.type !== 'turn/start' && (projected?.lastTurn !== turn || projected.openTurnStartSeq === null)) return
      if (row) this.controls.delete(row.owner)
      if ([...this.rows.values()].filter(value => value.active).length >= 32 && !this.rows.has(id)) return
      row = { session: id, turn, phase: 'running', reason: '', active: true,
        agent, sessionObject: session, surface, startSeq: projected?.openTurnStartSeq,
        owner: randomBytes(16).toString('hex') }
      this.rows.set(id, row)
    }
    if (!row.active || row.sessionObject !== session || row.agent !== current) return
    switch (event.type) {
      case 'turn/start': row.phase = 'running'; break
      case 'assistant/chunk': row.phase = 'reply'; break
      case 'tool/call': row.phase = data?.name === 'ask_user' ? 'waiting' : 'tool'; break
      case 'turn/end': row.phase = 'ended'; row.reason = data?.reason?.kind ?? 'unknown'; row.active = false; break
      default: return
    }
    this.pending.set(id, { ...row })
    if (!row.active) { void this.flush(); this.prune() }
  }
  prune() {
    const ended = [...this.rows].filter(([, row]) => !row.active)
    for (const [id,row] of ended.slice(0, Math.max(0, ended.length - 5))) { this.rows.delete(id); this.controls.delete(row.owner) }
  }
  async tick() {
    if (this.disposed) return
    let active = false
    for (const row of this.rows.values()) {
      if (!row.active) continue
      active = true
      const agent = this.ctx.agents.get(row.session)
      const boundary = agent && this.ctx.sessionProjections.stateOf(agent.session, 'turnBoundary')
      if (agent === row.agent && agent?.session === row.sessionObject && agent.session.surface?.contentGeneration === row.surface
          && this.ctx.agents.roots().includes(agent) && agent.status === 'running' && boundary?.lastTurn === row.turn && boundary.openTurnStartSeq === row.startSeq)
        this.pending.set(row.session, { ...row })
    }
    // Only live tasks can own a native stop control. Empty/terminal reporters
    // need no registration or claim polling; failed terminal facts still flush.
    if (!active && !this.pending.size) return
    await this.flush()
    if (!active || !this.identity || this.claiming || this.disposed) return
    this.claiming = true
    try {
      const command = await this.transport('claim', { ...this.identity, client: this.client })
      if (command?.action) await this.control(command)
    } catch { /* Unknown transfers do not requeue a claimed control. */ }
    finally { this.claiming = false }
  }
  async flush() {
    if (this.pumping) return this.pumping
    this.pumping = (async () => {
      try {
        if (!this.identity) {
          const port = this.ctx.webServer?.port
          if (!Number.isInteger(port) || port < 1 || port > 65535) return
          const lease = this.registration
          if (!lease || !Number.isSafeInteger(lease.generation) || lease.generation < 1
              || typeof lease.instance !== 'string' || !lease.instance
              || typeof lease.nonce !== 'string' || !/^[a-f0-9]{64}$/.test(lease.nonce)) return
          const identity = await this.transport('context', {
            generation: lease.generation, instance: lease.instance, nonce: lease.nonce, client: this.client, port
          })
          if (identity?.generation !== lease.generation || identity.instance !== lease.instance || identity.port !== port) return
          this.identity = identity
        }
        if (!Number.isSafeInteger(this.identity?.generation) || typeof this.identity.instance !== 'string') {
          this.identity = undefined; return
        }
        while (!this.disposed && this.pending.size) {
          const [id, row] = this.pending.entries().next().value; this.pending.delete(id)
          try { await this.transport('event', { ...this.identity, client: this.client,
            session: row.session, turn: row.turn, owner: row.owner, sequence: this.nextSequence(), phase: row.phase, reason: row.reason }) }
          catch { if (!this.pending.has(id)) this.pending.set(id, row); break }
        }
      } catch { /* Wait for ready without affecting the Agent. Never replace a pinned identity. */ }
    })()
    try { await this.pumping } finally { this.pumping = undefined }
  }
  async control(command) {
    if (this.disposed || command.generation !== this.identity.generation || command.instance !== this.identity.instance) return
    const agent = this.ctx.agents.get(command.session)
    const boundary = agent && this.ctx.sessionProjections.stateOf(agent.session, 'turnBoundary')
    const row = this.rows.get(command.session)
    let outcome = 'not-sent'
    if (row?.owner === command.owner && row.agent === agent && row.sessionObject === agent?.session && row.surface === (agent.session.surface?.contentGeneration ?? 0)
        && this.ctx.agents.roots().includes(agent) && agent?.session?.id === command.session && agent.status === 'running'
        && boundary?.lastTurn === command.turn && boundary.openTurnStartSeq !== null
        && boundary.openTurnStartSeq === row.startSeq
        && typeof agent.cancel === 'function' && typeof agent.whenIdle === 'function') {
      if (this.controls.get(command.owner) === command.action) return
      this.controls.set(command.owner, command.action)
      try {
        agent.cancel({ kind: 'user' }); outcome = 'requested'
        // Only the real turn/end event supplies a terminal reason.
        void Promise.resolve(agent.whenIdle()).catch(() => {})
      } catch { outcome = 'unknown' }
    }
    try { await this.transport('ack', { ...this.identity, ...command, client: this.client, outcome }) }
    catch { /* An unknown acknowledgement never permits calling cancel again. */ }
  }
  dispose() {
    this.disposed = true; this.cleanup?.(); this.cleanup = undefined
    this.pending.clear(); this.rows.clear(); this.controls.clear()
  }
}
export function apply(ctx) {
  const reporter = new TaskReporter(ctx)
  ctx.on('session/event', (session, event) => { try { reporter.event(session, event) } catch {} })
  ctx.on('agent/assistant-stream', ({ agent, frame }) => {
    if (frame?.type === 'chunk') { try { reporter.event(agent?.session, { type: 'assistant/chunk', data: { chunk: frame.chunk } }, agent) } catch {} }
  })
  const timer = setInterval(() => { void reporter.tick().catch(() => {}) }, 1000); timer.unref?.()
  reporter.cleanup = () => clearInterval(timer)
  ctx.on('dispose', () => reporter.dispose())
}
