/** 只取上游公开的无源码摘要；会话临时插件不能冒充 npm 持久安装。 */
export function runtimePlugins(agents, runner) {
  if (!runner || typeof runner.listPlugins !== 'function') return [];
  const items = [];
  try {
    for (const agent of agents) {
      for (const row of runner.listPlugins(agent)) {
        if (items.length >= 256) return items;
        if (typeof row.pluginId !== 'string' || typeof row.name !== 'string') continue;
        items.push({ id: row.pluginId.slice(0, 256), name: row.name.slice(0, 214),
          sessionId: String(agent.id).slice(0, 128), active: Boolean(row.activeRun),
          state: typeof row.latestRun?.status === 'string' ? row.latestRun.status.slice(0, 40) : '',
          version: String(row.currentPackageId || row.nextPackageId || '').slice(0, 128) });
      }
    }
  } catch { return []; }
  return items;
}
