import { writeFileSync, renameSync, unlinkSync } from 'node:fs';
import { confirmedIdle } from './activity.js';
import { runtimePlugins } from './runtime-plugins.js';
export const inject = ['agents', 'sessions', 'sessionProjections'];
export function apply(ctx) {
  const generation = process.env.DSHA_WEB_GENERATION;
  if (!generation) return;
  const file = '/root/.dsha-web-activity.json';
  const temp = file + '.' + process.pid + '.tmp';
  let dynamicRunner;
  ctx.inject(['dynamicCordisRunner'], dynamic => {
    dynamic.effect(() => {
      dynamicRunner = dynamic.dynamicCordisRunner;
      return () => { dynamicRunner = undefined; };
    });
  });
  ctx.effect(() => {
    let sampling = false, alive = true;
    const write = async () => {
      if (sampling || !alive) return;
      sampling = true;
      try {
        const idle = await confirmedIdle(ctx);
        if (!alive) return;
        writeFileSync(temp, JSON.stringify({ generation, at: Date.now(), idle,
          plugins: runtimePlugins(ctx.agents.list(), dynamicRunner) }), { mode: 0o600 });
        renameSync(temp, file);
      } catch { /* 原生侧将过期状态视为未知，继续保活。 */ }
      finally { sampling = false; }
    };
    write();
    const timer = setInterval(write, 2000); timer.unref();
    return () => { alive = false; clearInterval(timer); try { unlinkSync(temp); } catch {} };
  }, 'dsha-app-activity');
}
