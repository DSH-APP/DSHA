// 只读取运行状态，不读取或导出会话内容；无法确认时保持保活。
export function knownIdle(ctx) {
  try {
    const agents = ctx.agents.list();
    const sessions = ctx.sessions.list();
    const jobs = ctx.get('jobs');
    if (!jobs || !ctx.sessionProjections) return false;
    for (const agent of agents) {
      if (agent.status !== 'idle' || agent.inbox.nextTurn.length || agent.inbox.nextStep.length) return false;
      if (jobs.list(agent).some(job => !['completed', 'failed', 'killed'].includes(job.status))) return false;
    }
    for (const session of sessions) {
      const schedule = ctx.sessionProjections.stateOf(session, 'schedule');
      if (!schedule || !Array.isArray(schedule.active) || schedule.active.length) return false;
    }
    return true;
  } catch { return false; }
}
