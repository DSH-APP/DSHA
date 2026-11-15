import { randomUUID } from 'node:crypto'

/** The process instance is deliberately separate from Android Web generations. */
export const hostInstanceId = randomUUID()
const MAX_BODY = 65536
function id(value) {
  if (typeof value !== 'string' || !value.trim() || value.length > 256 || value.includes('\0'))
    throw new Error('SESSION_ID_INVALID')
  return value
}
function textOf(event) {
  const message = event?.type === 'assistant/message' ? event?.data?.message : event?.data
  if (!['user/message', 'assistant/message'].includes(event?.type)) return ''
  return (message?.content || []).filter(part => part.type === 'text')
    .map(part => String(part.text || '')).join('\n')
}
function visibleConversationMessage(event) {
  if (event?.type === 'assistant/message') return true
  // Runtime/time/plugin context also uses user/message in the model history.
  // Only human provenance belongs in the chat transcript. Never inspect text.
  return event?.type === 'user/message' && event?.data?.source?.kind === 'user'
}
/** Trusted values are rebuilt for each actual shell execution, never last-active-session globals. */
export function phoneTaskEnvironment(execution) {
  const session = execution?.agent?.session
  const events = session?.snapshotEvents()
  const turn = events?.findLast(event => event.type === 'turn/start')
  const ended = turn && events.some(event => event.type === 'turn/end' && event.seq > turn.seq && event.data.turn === turn.data.turn)
  if (!session?.id || !turn || ended) return {}
  const completed = events.findLast(event => event.type === 'turn/end' && event.seq < turn.seq
    && Number.isSafeInteger(event.data?.turn) && event.data.turn > 0 && event.data.turn < turn.data.turn)
  return { DSH_PHONE_SESSION_ID: session.id, DSH_PHONE_TURN_ID: String(turn.data.turn),
    ...(completed ? { DSH_PHONE_COMPLETED_TURN_ID: String(completed.data.turn) } : {}) }
}
/** The Host's own turn-boundary projection: O(1) and independent of the bounded page. */
function projectedTurn(ctx, agent) {
  if (!agent || typeof ctx.agents?.turnBoundaryOf !== 'function') return undefined
  try {
    const boundary = ctx.agents.turnBoundaryOf(agent.session)
    return { turnId: boundary.lastTurn > 0 ? String(boundary.lastTurn) : '',
      turnActive: boundary.openTurnStartSeq !== null }
  } catch { return undefined }
}
/** The bounded page's own turn events; absent when the open turn started before the cut. */
function pageTurn(events) {
  const turn = events.findLast(event => event.type === 'turn/start')
  const ended = turn !== undefined && events.some(event => event.type === 'turn/end'
    && event.seq > turn.seq && event.data?.turn === turn.data?.turn)
  return { turnId: turn ? String(turn.data.turn) : '', turnActive: turn !== undefined && !ended }
}
/** A long turn can outgrow the transcript page, so the Host projection names the current
 * turn, and the page decides whether that exact turn already ended when it covers it. */
function currentTurn(ctx, agent, events) {
  const projected = projectedTurn(ctx, agent)
  if (projected === undefined) return pageTurn(events)
  const page = pageTurn(events)
  return page.turnId === projected.turnId ? { turnId: projected.turnId, turnActive: page.turnActive } : projected
}
/** Uses the exact locked public business API. No private registry mutation. */
export function sessionAdapter(ctx, instance = hostInstanceId, liveText = () => '', sessionError = () => false) {
  return async function call(method, args = {}, signal = AbortSignal.timeout(10000)) {
    if (args.hostInstanceId !== undefined && args.hostInstanceId !== instance)
      throw new Error('HOST_INSTANCE_CHANGED')
    const controller = ctx.sessionController
    if (!controller) throw new Error('SESSION_CONTROLLER_UNAVAILABLE')
    if (method === 'sessions.list') {
      const result = await controller.list({}, signal)
      return { ...result, items: result.items.map(item => {
        const agent = ctx.agents?.get(item.sessionId)
        const title = item.projections?.values?.title
        return { ...item, ...(typeof title === 'string' && title.trim() ? { title } : {}),
          running: item.running || (agent?.inbox?.nextTurn?.length || 0) + (agent?.inbox?.nextStep?.length || 0) > 0 }
      }), hostInstanceId: instance }
    }
    if (!args.hostInstanceId) throw new Error('HOST_INSTANCE_REQUIRED')
    if (method === 'sessions.create') {
      // Native UI supplies no arbitrary path. Respect the configured Host workspace.
      return { ...await controller.create({}), hostInstanceId: instance }
    }
    const sessionId = id(args.sessionId)
    if (method === 'sessions.status') {
      // 0.2.2-rc.1 has no SessionController.inspect. One passive state read plus one
      // bounded history page are the current public reads; neither activates a cold
      // conversation. The message budget also covers hidden runtime/time context entries.
      const [state, history] = await Promise.all([
        controller.state({ sessionId }, signal),
        controller.events({ address: { kind: 'session', sessionId }, maxMessages: 16 }, signal),
      ])
      const events = history.records.map(record => record.event)
      const turn = currentTurn(ctx, ctx.agents?.get(sessionId), events)
      const queued = state.queue?.length || 0
      const running = state.running === true || queued > 0
      const messages = events.filter(visibleConversationMessage).slice(-8)
      const stream = running ? liveText(sessionId) : ''
      return { sessionId, hostInstanceId: instance, running,
        turnId: turn.turnId, turnActive: turn.turnActive, taskId: sessionId,
        messages: messages.map(event => ({ role: event.type === 'user/message' ? 'user' : 'assistant', text: textOf(event).slice(-3000) })),
        streamText: String(stream).slice(-4000),
        failed: !running && sessionError(sessionId),
        text: (messages.map(textOf).filter(Boolean).join('\n\n') + (stream ? '\n\n' + stream : '')).slice(-24000) }
    }
    if (method === 'sessions.send') {
      if (typeof args.text !== 'string' || !args.text.trim() || args.text.length > 32768)
        throw new Error('PROMPT_INVALID')
      const requestId = id(args.requestId)
      // steer splices the message into the running turn's next step; queue starts a later turn.
      // Anything else is rejected rather than silently downgraded.
      const mode = args.mode === undefined ? 'queue' : args.mode
      if (mode !== 'queue' && mode !== 'steer') throw new Error('PROMPT_INVALID')
      // A receipt only means real inbox admission; never fabricated completion.
      return await controller.prompt({ sessionId, requestId, mode,
        content: [{ type: 'text', text: args.text }] }, signal)
    }
    if (method === 'sessions.cancel') {
      const agent = ctx.agents?.get(sessionId)
      if (!agent) {
        // The passive state read proves an existing cold conversation; it has no turn to cancel.
        await controller.state({ sessionId }, signal)
        return { accepted: true, running: false }
      }
      const lastTurn = agent.session.snapshotEvents().findLast(event => event.type === 'turn/start')
      if (args.turnId && String(lastTurn?.data?.turn) !== args.turnId) throw new Error('TURN_CHANGED')
      // Unlike ordinary Web cancel, pause must not let an old queued prompt wake later.
      // Public Agent.cancel defaults keepInbox=false and durably cancels pending input.
      agent.cancel({ kind: 'user' }, { keepInbox: false })
      await Promise.race([agent.whenIdle(), new Promise((_, reject) => {
        if (signal.aborted) { reject(signal.reason); return }
        signal.addEventListener('abort', () => reject(signal.reason), { once: true })
      })])
      return { accepted: true }
    }
    throw new Error('SESSION_METHOD_UNSUPPORTED')
  }
}
export function installSessionAdapter(ctx, liveText, retireTask = () => {}) {
  const failed = new Set()
  ctx.on('agent/error', ({ agent }) => { if (agent?.id) failed.add(agent.id) })
  ctx.on('session/event', (session, event) => {
    if (event.type === 'turn/start') failed.delete(session.id)
    if (event.type === 'turn/end' && Number.isSafeInteger(event.data?.turn) && event.data.turn > 0) {
      // Event identity, not the latest-turn snapshot: delivery may race the next turn.
      try { Promise.resolve(retireTask({ sessionId: session.id, turnId: String(event.data.turn) })).catch(() => {}) }
      catch { /* Lifecycle reporting cannot interrupt the real Agent. */ }
    }
  })
  ctx.on('session/disposed', session => failed.delete(session.id))
  ctx.on('dispose', () => failed.clear())
  ctx.inject(['shellEnv'], scope => scope.effect(() => scope.shellEnv.register({
    name: 'dsha-phone-task', variables: {
      DSH_PHONE_SESSION_ID: { description: 'Exact local phone-control conversation ID for this tool execution.' },
      DSH_PHONE_TURN_ID: { description: 'Exact local phone-control turn ID for this tool execution.' },
      DSH_PHONE_COMPLETED_TURN_ID: { description: 'Latest actually ended earlier turn in this exact conversation, if any.' },
    }, resolve: phoneTaskEnvironment,
  }), 'dsha exact per-execution phone task environment'))
  ctx.inject(['systemPrompt'], scope => scope.systemPrompt.section({ name: 'dsha:phone-task-binding', order: 155,
    text: 'For physical-phone /app/ui/* calls, use the current tool environment identity: append '
      + '--data-urlencode "taskSessionId=$DSH_PHONE_SESSION_ID" --data-urlencode "turnId=$DSH_PHONE_TURN_ID" '
      + 'to curl -G. If DSH_PHONE_COMPLETED_TURN_ID is nonempty, also append --data-urlencode "completedTurnId=$DSH_PHONE_COMPLETED_TURN_ID". '
      + 'These values identify this exact full conversation and turn; never copy another session or a shortened suffix. '
      + 'Pause/stop/expanded chat invalidates phone actions immediately. After user continuation, obtain a fresh screen observation; never replay UNKNOWN actions.',
  }))
  ctx.inject(['webServer', 'connection', 'sessionController', 'agents'], web => {
    const call = sessionAdapter(web, hostInstanceId, liveText, key => failed.has(key))
    web.effect(() => web.webServer.register({ kind: 'exact', path: '/api/dsha-assistant',
      handler: assistantRequestHandler(call, web.connection) }), 'dsha assistant exact local Host session API')
  })
}
/** Same handler is exercised over real HTTP by the pinned-runtime integration fixture. */
export function assistantRequestHandler(call, connection) {
  return async (req, res) => {
        const reply = (status, value) => { const bytes = Buffer.from(JSON.stringify(value));
          res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': bytes.length }); res.end(bytes) }
        const denied = connection.requestRejection(req)
        if (denied !== undefined) { reply(denied, { ok: false, code: 'AUTH_REQUIRED' }); return }
        if (req.method !== 'POST') { reply(405, { ok: false, code: 'POST_REQUIRED' }); return }
        if (req.headers.origin) {
          try { if (new URL(req.headers.origin).host !== req.headers.host) throw Error() }
          catch { reply(403, { ok: false, code: 'ORIGIN_REJECTED' }); return }
        }
        const deadline = AbortSignal.timeout(10000)
        let method
        try {
          req.setTimeout?.(10000, () => req.destroy(new Error('REQUEST_TIMEOUT')))
          let size = 0, blocks = []
          for await (const block of req) {
            size += block.length
            if (size > MAX_BODY) { reply(413, { ok: false, code: 'BODY_TOO_LARGE' }); return }
            blocks.push(block)
          }
          const body = JSON.parse(Buffer.concat(blocks).toString('utf8'))
          if (!body || typeof body !== 'object' || typeof body.method !== 'string'
              || !body.args || typeof body.args !== 'object' || Array.isArray(body.args))
            throw new Error('SESSION_REQUEST_INVALID')
          method = body.method
          const value = await call(body.method, body.args, deadline)
          reply(200, { ok: true, value })
        } catch (error) {
          const raw = String(error?.code || error?.message || '')
          // Error messages may contain credentials or arbitrary provider text.
          const code = /^[a-zA-Z][a-zA-Z0-9_/-]{0,100}$/.test(raw) ? raw : 'SESSION_REQUEST_FAILED'
          const rejected = method === 'sessions.send' && ['session/model-unavailable', 'PROMPT_INVALID',
            'HOST_INSTANCE_CHANGED', 'HOST_INSTANCE_REQUIRED', 'SESSION_ID_INVALID'].includes(code)
          reply(409, { ok: false, code, ...(rejected ? { rejected: true } : {}) })
        }
  }
}
