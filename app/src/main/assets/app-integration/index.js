import { writeFileSync, renameSync, unlinkSync, readFileSync } from 'node:fs';
import { confirmedIdle } from './activity.js';
import { runtimePlugins, installedPluginSampler } from './runtime-plugins.js';
export function apply(ctx) {
  ctx.inject(['webServer','connection'], scoped => {
    scoped.effect(() => scoped.webServer.register({kind:'exact',path:'/__dsha/browser-origin-restore',handler:async (req,res) => {
      if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405,{'cache-control':'no-store'}); res.end(); return; }
      if (!scoped.connection.authorizeIndex(req,res)) return;
      const page = readFileSync(new URL('./browser-origin-restore.html',import.meta.url),'utf8')
        .replace('/*DSHA_ORIGIN_STATE_SCRIPT*/',readFileSync(new URL('./browser-origin-state.js',import.meta.url),'utf8'));
      res.writeHead(200,{'content-type':'text/html; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff',
        'content-security-policy':"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src http://127.0.0.1:*; base-uri 'none'; frame-ancestors 'none'"});
      res.end(req.method === 'HEAD' ? undefined : page);
    }}), 'dsha-browser-origin-restore');
  });
  const generation = process.env.DSHA_WEB_GENERATION;
  if (!generation) return;
  const instance = process.env.DSH_TASK_INSTANCE || '';
  const profile = process.env.DSHA_STARTUP_PROFILE || 'web';
  // 活动采样等待自己的服务，不阻塞独立路由或把插件 apply 返回当作网页集成就绪。
  // 服务撤回时 Cordis 释放本作用域，旧异步采样不能继续发布空闲证明。
  ctx.inject(['agents', 'sessions', 'sessionProjections'], samplingContext => {
    const installedPlugins = installedPluginSampler(samplingContext, { generation, instance, profile });
    const file = '/root/.dsha-web-activity.json';
    const temp = file + '.' + process.pid + '.tmp';
    let dynamicRunner;
    samplingContext.inject(['dynamicCordisRunner'], dynamic => {
      dynamic.effect(() => {
        dynamicRunner = dynamic.dynamicCordisRunner;
        return () => { dynamicRunner = undefined; };
      });
    });
    samplingContext.effect(() => {
      let sampling = false, alive = true;
      const write = async () => {
        if (sampling || !alive) return;
        sampling = true;
        try {
          const idle = await confirmedIdle(samplingContext);
          if (!alive) return;
          writeFileSync(temp, JSON.stringify({ generation, instance, at: Date.now(), idle,
            installedPlugins: installedPlugins(), plugins: runtimePlugins(samplingContext.agents.list(), dynamicRunner) }), { mode: 0o600 });
          renameSync(temp, file);
        } catch { /* 原生侧将过期状态视为未知，继续保活。 */ }
        finally { sampling = false; }
      };
      write();
      const timer = setInterval(write, 2000); timer.unref();
      return () => { alive = false; clearInterval(timer); try { unlinkSync(temp); } catch {} };
    }, 'dsha-app-activity');
  });
}
