const validId = value => typeof value === 'string' && value.trim()
  && value.length <= 256 && !value.includes('\0')
const validTurn = value => /^[1-9][0-9]{0,15}$/.test(value)
  && Number.isSafeInteger(Number(value))

/** Snapshot the actual caller, before crossing the asynchronous MCP transport. */
export function executionTask(execution) {
  const session = execution?.agent?.session
  if (!validId(session?.id)) throw new Error('SCREEN_TASK_IDENTITY_REQUIRED')
  const events = session.snapshotEvents()
  const start = events.findLast(event => event.type === 'turn/start')
  const end = events.findLast(event => event.type === 'turn/end')
  const turnId = String(start?.data?.turn ?? '')
  if (!validTurn(turnId) || !start || (end && end.seq > start.seq))
    throw new Error('SCREEN_TURN_NOT_ACTIVE')
  const completedTurnId = String(end?.data?.turn ?? '')
  if (end && (!validTurn(completedTurnId) || Number(completedTurnId) >= Number(turnId)))
    throw new Error('SCREEN_TURN_INVALID')
  return { sessionId: session.id, turnId, ...(end ? { completedTurnId } : {}) }
}
