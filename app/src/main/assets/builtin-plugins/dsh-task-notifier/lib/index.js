/** Real Agent turn facts and one-shot cancellation through the authenticated native bridge. */
import { randomBytes } from 'node:crypto'
import bridgePort from './bridge-port.cjs'
import { readFile } from 'node:fs/promises'
export const inject = ['agents', 'sessionProjections', 'webServer']

export const name = 'dsh-task-notifier'

/** 原生通知/岛上的选项按钮把答案 POST 到这个本机端点；token 只随任务事件下发。 */
const ANSWER_PATH = '/__dsha_question_answer'
const QUESTION_LIMIT = 2000
const ANSWER_BODY_LIMIT = 4096
export function completionNotice(reason) {
  if (reason === 'completed') return ['DSHA · 任务完成', 'Agent 已完成一轮对话，点击查看结果']
  if (reason === 'max-tokens') return ['DSHA · 已达到输出上限', '请查看结果，按需继续对话']
  if (reason === 'aborted' || reason === 'interrupted') return ['DSHA · 任务已停止', '本轮任务未完成，点击查看状态']
  return ['DSHA · 任务需要处理', '本轮任务因错误或阻塞结束，请查看对话详情']
}
async function bridge(operation, value) {
  const token = (await readFile('/root/.dsh/.bridge_token', 'utf8')).trim()
  if (!token) throw Error('bridge unavailable')
  const response = await fetch('http://127.0.0.1:' + bridgePort() + '/app/tasks/' + operation
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
/** 单行化：控制字符与换行都会把通知卡片撑高，先压成一行再限长。 */
function oneLine(value) {
  return String(value).replace(/[\u0000-\u001f\u007f]+/g, ' ').replace(/\s+/g, ' ').trim()
}
/** 按 code point 限长，避免把中文/emoji 的代理对切成半个字符。 */
function clip(value, limit) {
  const characters = Array.from(String(value))
  return characters.length <= limit ? String(value) : characters.slice(0, limit).join('')
}
function parseArguments(value) {
  if (value && typeof value === 'object') return value
  if (typeof value !== 'string' || value.length < 2 || value.length > 8192) return undefined
  try {
    const parsed = JSON.parse(value)
    return parsed && typeof parsed === 'object' ? parsed : undefined
  } catch {
    return undefined
  }
}
const TARGET_KEYS = ['path', 'file_path', 'filePath', 'filename', 'url', 'query', 'pattern',
  'command', 'cmd', 'name', 'description', 'question']
function toolDetail(data) {
  const name = typeof data?.name === 'string' ? clip(oneLine(data.name), 40) : ''
  const parsed = parseArguments(data?.arguments)
  let target = ''
  if (parsed !== undefined) {
    for (const key of TARGET_KEYS) {
      const raw = parsed[key]
      if (typeof raw !== 'string' || raw.trim() === '') continue
      let value = oneLine(raw)
      // 命令只留可执行文件名：完整命令行可能带 token、路径或环境变量，不适合出现在锁屏。
      if (key === 'command' || key === 'cmd') value = value.split(' ')[0].split('/').pop() ?? ''
      target = clip(value, 40)
      break
    }
  }
  if (target === '') return name
  return name === '' ? target : name + ' · ' + target
}
/** 从 ask_user_question 的模型参数里取出问题列表（兼容旧的单问题写法）。 */
function argumentsQuestions(data) {
  const parsed = parseArguments(data?.arguments)
  const list = Array.isArray(parsed?.questions) ? parsed.questions
    : Array.isArray(parsed) ? parsed
    : parsed && typeof parsed.question === 'string' ? [parsed] : []
  return list.slice(0, 4).map(question => ({
    id: typeof question?.id === 'string' ? question.id : '',
    question: typeof question?.question === 'string' ? question.question : '',
    options: Array.isArray(question?.options)
      ? question.options.map(option => ({ label: typeof option === 'string' ? option : option?.label }))
      : [],
    multiSelect: question?.multi_select === true || question?.multiSelect === true
  })).filter(question => question.id !== '')
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
    this.answerToken = randomBytes(32).toString('hex')
    this.waiters = new Map()
    this.disposed = false; this.pumping = undefined; this.claiming = false; this.ticking = false
    this.registration?.attach?.(this)
  }
  /** 会话标题是可选信息；投影不存在或尚未生成时返回空串。 */
  titleOf(session) {
    try {
      const title = this.ctx.sessionProjections?.stateOf?.(session, 'title')
      return typeof title === 'string' ? clip(oneLine(title), 40) : ''
    } catch {
      return ''
    }
  }
  boundaryOf(session) {
    try {
      return this.ctx.sessionProjections?.stateOf?.(session, 'turnBoundary')
    } catch {
      return undefined
    }
  }
  roots() {
    try {
      return typeof this.ctx.agents.roots === 'function' ? this.ctx.agents.roots() : []
    } catch {
      return []
    }
  }
  /**
   * 通知/岛上的快速回答载荷：端口、端点、一次性令牌与问题选项。
   * 只在 web 服务器端口可用时生成，且整段 JSON 不超过 bridge 的 4KB 上限。
   */
  questionPayload(callId, questions) {
    const port = this.ctx.webServer?.port
    if (typeof callId !== 'string' || callId === '' || !Number.isInteger(port) || port < 1 || port > 65535) return ''
    const normalized = questions.slice(0, 4).map(question => ({
      id: question.id,
      question: clip(oneLine(question.question ?? ''), 80),
      ...(question.multiSelect ? { multiSelect: true } : {}),
      options: (Array.isArray(question.options) ? question.options : []).slice(0, 4)
        .map(option => ({ label: clip(oneLine(option?.label ?? ''), 24) }))
        .filter(option => option.label !== '')
    })).filter(question => question.id !== '')
    if (normalized.length === 0) return ''
    const value = { version: 1, port, path: ANSWER_PATH, token: this.answerToken, callId, questions: normalized }
    const json = JSON.stringify(value)
    return json.length <= QUESTION_LIMIT ? json : ''
  }
  /** 当前 session 里仍在等回答的问题（undefined=投影不可用，null=没有待答）。 */
  openQuestion(session) {
    try {
      const state = this.ctx.sessionProjections?.stateOf?.(session, 'userQuestions')
      if (state === undefined || state === null || typeof state !== 'object'
          || state.questions === undefined || !Array.isArray(state.questions.active)) return undefined
      for (const entry of state.questions.active) {
        if (entry?.state !== 'open' || !Array.isArray(entry.questions)) continue
        if (entry.questions.some(question => typeof question?.question === 'string')) return entry
      }
      return null
    } catch {
      return undefined
    }
  }
  questionText(questions) {
    for (const question of questions) {
      if (typeof question?.question === 'string' && question.question.trim() !== '') {
        return clip(oneLine(question.question), 60)
      }
    }
    return ''
  }
  /** 原生端点调用：令牌 + 答案批次全部校验通过才唤醒等待中的 waterfall。 */
  acceptAnswer(value) {
    if (value === null || typeof value !== 'object' || value.token !== this.answerToken) return { ok: false, error: 'TOKEN' }
    const callId = typeof value.callId === 'string' ? value.callId : ''
    const waiter = this.waiters.get(callId)
    if (waiter === undefined || waiter.expires < this.clock()) return { ok: false, error: 'NO_WAITING' }
    const request = waiter.request
    const answers = Array.isArray(value.answers) ? value.answers : []
    if (answers.length !== request.questions.length) return { ok: false, error: 'ANSWERS' }
    const output = []
    for (const question of request.questions) {
      const answer = answers.find(item => item !== null && typeof item === 'object' && item.id === question.id)
      if (answer === undefined) return { ok: false, error: 'ANSWERS' }
      const selected = Array.isArray(answer.selected) ? answer.selected : []
      const custom = typeof answer.custom === 'string' ? clip(oneLine(answer.custom), 2000) : ''
      if (custom === '' && selected.length === 0) return { ok: false, error: 'ANSWERS' }
      const labels = (question.options ?? []).map(option => option.label)
      if (selected.some(label => typeof label !== 'string' || !labels.includes(label))) return { ok: false, error: 'OPTION' }
      if (custom === '' && question.multiSelect !== true && selected.length !== 1) return { ok: false, error: 'OPTION' }
      // 与网页端一致：单选时自定义答案覆盖已选选项；多选才允许两者并存。
      const kept = custom === '' || question.multiSelect === true ? [...selected] : []
      output.push({ id: question.id, selected: kept, ...(custom === '' ? {} : { custom }) })
    }
    this.waiters.delete(callId)
    waiter.resolve({ answers: output })
    return { ok: true }
  }
  sweepWaiters(now) {
    for (const [callId, waiter] of this.waiters) if (waiter.expires < now) this.waiters.delete(callId)
  }
  event(session, event, emittingAgent) {
    const id = session?.id
    if (this.disposed || typeof id !== 'string' || !id || id.length > 256) return
    if (!['turn/start', 'assistant/chunk', 'tool/call', 'tool/result', 'turn/end'].includes(event?.type)) return
    const data = event?.data
    const projected = this.boundaryOf(session)
    const turn = data?.turn ?? projected?.lastTurn
    if (!Number.isSafeInteger(turn) || turn < 1) return
    let row = this.rows.get(id)
    const current = this.ctx.agents.get(id)
    const surface = session.surface?.contentGeneration ?? 0
    const root = current?.session === session && this.roots().includes(current)
    // End events have the same live ownership requirement as progress events.
    // Session ids, generation numbers and turn counters can all be reused after replacement.
    if (!root || (emittingAgent && emittingAgent !== current)) return
    if (row && row.sessionObject === session && row.surface === surface && row.agent === current && row.turn > turn) return
    if (!row || row.turn !== turn || row.sessionObject !== session || row.surface !== surface || row.agent !== current) {
      // A historical/replayed end/result cannot create a live task or a completion alert.
      if (event.type === 'turn/end' || event.type === 'tool/result') return
      const agent = current
      if (agent?.session !== session || agent.status !== 'running') return
      if (event.type !== 'turn/start' && (projected?.lastTurn !== turn || projected.openTurnStartSeq === null)) return
      if (row) this.controls.delete(row.owner)
      if ([...this.rows.values()].filter(value => value.active).length >= 32 && !this.rows.has(id)) return
      row = { session: id, turn, phase: 'running', reason: '', detail: '', question: '', questionSeen: false, title: this.titleOf(session),
        active: true, agent, sessionObject: session, surface, startSeq: projected?.openTurnStartSeq,
        owner: randomBytes(16).toString('hex') }
      this.rows.set(id, row)
    }
    if (!row.active || row.sessionObject !== session || row.agent !== current) return
    switch (event.type) {
      case 'turn/start':
        row.phase = 'running'; row.detail = ''; row.reason = ''; row.question = ''; row.questionSeen = false
        break
      case 'assistant/chunk':
        // 只上报阶段，不把对话正文带出网页。
        row.phase = 'reply'; row.detail = ''; row.reason = ''; row.question = ''; row.questionSeen = false
        break
      case 'tool/call': {
        const questions = argumentsQuestions(data)
        const asking = data?.name === 'ask_user_question' || data?.name === 'ask_user'
        row.phase = asking ? 'waiting' : 'tool'
        row.detail = asking ? this.questionText(questions) : toolDetail(data)
        row.reason = ''
        row.question = asking ? this.questionPayload(data?.callId, questions) : ''
        row.questionSeen = false
        break
      }
      case 'tool/result':
        // 问题已被网页端回答：立刻撤掉“等你确认”，不等下一次投影刷新。
        if (row.phase !== 'waiting') return
        row.phase = 'running'; row.detail = ''; row.reason = ''; row.question = ''; row.questionSeen = false
        break
      case 'turn/end':
        row.phase = 'ended'
        row.reason = typeof data?.reason?.kind === 'string' ? data.reason.kind : 'unknown'
        row.active = false
        row.detail = ''
        row.question = ''
        row.questionSeen = false
        break
      default:
        return
    }
    row.title = this.titleOf(session)
    row.idleSince = undefined
    this.pending.set(id, { ...row })
    if (!row.active) { void this.flush(); this.prune() }
  }
  prune() {
    const ended = [...this.rows].filter(([, row]) => !row.active)
    for (const [id,row] of ended.slice(0, Math.max(0, ended.length - 5))) { this.rows.delete(id); this.controls.delete(row.owner) }
  }
  /**
   * 每秒推进一次：
   * - 仍然存活的任务重发一次心跳，原生据此刷新“最后活动时间”，长时间思考也不会被判死；
   * - 本轮已结束但没收到 turn/end（例如 Agent 被替换或流中断）时，3 秒后补发终态；
   * - 只有真实存在活动任务时才领取停止指令。
   */
  async tick() {
    if (this.disposed || this.ticking) return
    this.ticking = true
    try {
      const now = this.clock()
      for (const row of [...this.rows.values()]) {
        if (!row.active) continue
        const agent = this.ctx.agents.get(row.session)
        const sameSurface = (agent?.session?.surface?.contentGeneration ?? -1) === row.surface
        const sameAgent = agent !== undefined && agent === row.agent
        const rooted = sameAgent && sameSurface && this.roots().includes(agent)
        const boundary = agent === undefined ? undefined : this.boundaryOf(agent.session)
        const openQuestion = this.openQuestion(row.sessionObject)
        const applyQuestion = () => {
          if (openQuestion === undefined) return
          if (openQuestion === null) {
            // 只有投影里确实见过这个问题，才用“投影已清空”撤掉等待；
            // 事件先到、投影后到时不能把刚出现的“等你确认”立刻抹掉。
            if (row.phase === 'waiting' && row.questionSeen) {
              row.phase = 'running'; row.detail = ''; row.question = ''; row.questionSeen = false
            }
            return
          }
          row.questionSeen = true
          row.phase = 'waiting'
          row.detail = this.questionText(openQuestion.questions)
          row.question = this.questionPayload(openQuestion.callId, openQuestion.questions)
        }
        if (rooted && agent.status === 'running') {
          // 任务还活着：无条件心跳。边界投影暂时不可用时也不能判死，宁可少领一次停止。
          row.idleSince = undefined
          applyQuestion()
          if (Number.isSafeInteger(boundary?.openTurnStartSeq)
              && boundary.lastTurn === row.turn) row.startSeq = boundary.openTurnStartSeq
          this.pending.set(row.session, { ...row })
          continue
        }
        // 问题还开着但 Agent 暂时不在 running（例如等待输入被上层调度）：
        // 不能按“被替换/中断”结束，保持等待并继续心跳。
        if (openQuestion) {
          row.idleSince = undefined
          applyQuestion()
          this.pending.set(row.session, { ...row })
          continue
        }
        // 没有 turn/end 也要收敛：先给 3 秒（真正的 end 事件通常在同一毫秒内到达），
        // 之后按“被替换/中断”或“停止后结束”补一个终态，不能让胶囊永远停在执行中。
        if (row.idleSince === undefined) row.idleSince = now
        if (now - row.idleSince >= 3000) {
          const replaced = !sameAgent || !sameSurface
          row.phase = 'ended'
          row.reason = replaced ? 'interrupted' : row.stopping ? 'aborted' : 'completed'
          row.active = false
          row.detail = ''
          this.pending.set(row.session, { ...row })
          this.prune()
        }
      }
      // Only live tasks can own a native stop control. Empty/terminal reporters
      // need no registration or claim polling; failed terminal facts still flush.
      this.sweepWaiters(now)
      const hasActive = [...this.rows.values()].some(row => row.active)
      if (!hasActive && !this.pending.size) return
      await this.flush()
      if (!hasActive || !this.identity || this.claiming || this.disposed) return
      this.claiming = true
      try {
        const command = await this.transport('claim', { ...this.identity, client: this.client })
        if (command?.action) await this.control(command)
      } catch { /* Unknown transfers do not requeue a claimed control. */ }
      finally { this.claiming = false }
    } finally {
      this.ticking = false
    }
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
          try {
            await this.transport('event', { ...this.identity, client: this.client,
              session: row.session, turn: row.turn, owner: row.owner, sequence: this.nextSequence(),
              phase: row.phase, reason: row.reason, detail: row.detail, title: row.title, question: row.question })
          } catch { if (!this.pending.has(id)) this.pending.set(id, row); break }
        }
      } catch { /* Wait for ready without affecting the Agent. Never replace a pinned identity. */ }
    })()
    try { await this.pumping } finally { this.pumping = undefined }
  }
  async control(command) {
    if (this.disposed || command.generation !== this.identity.generation || command.instance !== this.identity.instance) return
    const agent = this.ctx.agents.get(command.session)
    const boundary = agent && this.boundaryOf(agent.session)
    const row = this.rows.get(command.session)
    let outcome = 'not-sent'
    if (row?.owner === command.owner && row.agent === agent && row.sessionObject === agent?.session && row.surface === (agent.session.surface?.contentGeneration ?? 0)
        && this.roots().includes(agent) && agent?.session?.id === command.session && agent.status === 'running'
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
    this.disposed = true; this.ticking = false; this.cleanup?.(); this.cleanup = undefined
    this.pending.clear(); this.rows.clear(); this.controls.clear(); this.waiters.clear()
  }
}
async function answerRequest(reporter, request, response) {
  const send = (status, value) => {
    try { response.writeHead(status, { 'content-type': 'application/json' }); response.end(JSON.stringify(value)) } catch {}
  }
  try {
    if (request.method !== 'POST') return send(405, { ok: false, error: 'METHOD' })
    const remote = request.socket?.remoteAddress ?? ''
    if (!['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(remote)) return send(403, { ok: false, error: 'REMOTE' })
    let body = ''
    for await (const chunk of request) {
      body += chunk
      if (body.length > ANSWER_BODY_LIMIT) return send(413, { ok: false, error: 'BODY' })
    }
    const result = reporter.acceptAnswer(JSON.parse(body))
    send(result.ok ? 200 : 409, result)
  } catch {
    send(400, { ok: false, error: 'BAD_REQUEST' })
  }
}

export function apply(ctx) {
  const reporter = new TaskReporter(ctx)
  // 原生通知按钮 → 本机端点 → 唤醒 user-questions waterfall。端点只认一次性令牌。
  if (typeof ctx.webServer?.register === 'function') {
    ctx.effect(() => ctx.webServer.register({
      kind: 'exact',
      path: ANSWER_PATH,
      handler: (request, response) => answerRequest(reporter, request, response)
    }), 'dsh-task-notifier: question answer endpoint')
  }
  ctx.on('user-questions/request', (request, next) => {
    if (reporter.disposed) return next()
    const callId = request?.wait?.callId
    if (typeof callId !== 'string' || callId === '' || !Array.isArray(request.questions) || request.questions.length === 0) return next()
    const web = next()
    const native = new Promise(resolve => {
      reporter.waiters.set(callId, { request, resolve, expires: reporter.clock() + 10 * 60_000 })
    })
    return Promise.race([native, web]).finally(() => { reporter.waiters.delete(callId) })
  }, { prepend: true })
  ctx.on('session/event', (session, event) => { try { reporter.event(session, event) } catch {} })
  ctx.on('agent/assistant-stream', ({ agent, frame }) => {
    if (frame?.type === 'chunk') { try { reporter.event(agent?.session, { type: 'assistant/chunk', data: { chunk: frame.chunk } }, agent) } catch {} }
  })
  const timer = setInterval(() => { void reporter.tick().catch(() => {}) }, 1000); timer.unref?.()
  reporter.cleanup = () => clearInterval(timer)
  ctx.on('dispose', () => reporter.dispose())
}
