import { writeFileSync, renameSync, unlinkSync } from 'node:fs';
import { knownIdle } from './activity.js';
export const inject = ['agents', 'sessions', 'sessionProjections'];
export function apply(ctx) {
  const generation = process.env.DSHA_WEB_GENERATION;
  if (!generation) return;
  const file = '/root/.dsha-web-activity.json';
  const temp = file + '.' + process.pid + '.tmp';
  ctx.effect(() => {
    const write = () => {
      try {
        writeFileSync(temp, JSON.stringify({ generation, at: Date.now(), idle: knownIdle(ctx) }), { mode: 0o600 });
        renameSync(temp, file);
      } catch { /* 原生侧将过期状态视为未知，继续保活。 */ }
    };
    write();
    const timer = setInterval(write, 2000); timer.unref();
    return () => { clearInterval(timer); try { unlinkSync(temp); } catch {} };
  }, 'dsha-app-activity');
}
