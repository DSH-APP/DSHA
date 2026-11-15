'use strict';
// 切网后回收外网连接（T37）：宿主在默认网络切换、恢复验证或解除后台限制时写入网络代次
// /run/dsha-network/epoch；本进程有外网连接时每 2 秒看一次代次（无外网连接不轮询）。
// 代次变化后：
//   1) 空闲的外网连接立即关闭，后续请求（含模型重试）一律新建连接，不复用旧路径上的连接；
//   2) 正在使用的外网连接留宽限期，期间收到过任何字节就保留；一个字节都没收到才关闭，
//      调用方得到普通的连接错误，由原有重试策略处理。
// 不重放任何请求，不碰本机回环连接（桥、MCP、本机代理），不改变超时和重试策略本身。
// DSHA_NET_EPOCH=0 关闭；DSHA_NET_EPOCH_GRACE_MS 调整宽限期（1000–120000）。
const marker = Symbol.for('dsha.net.epoch');
if (process.env.DSHA_NET_EPOCH !== '0' && !globalThis[marker]) {
  let dc;
  try { dc = require('node:diagnostics_channel'); } catch { dc = null; }
  if (dc && typeof dc.subscribe === 'function') {
    Object.defineProperty(globalThis, marker, { value: true });
    const fs = require('node:fs');
    const FILE = process.env.DSHA_NET_EPOCH_FILE || '/run/dsha-network/epoch';
    const POLL_MS = 2000;
    const graceEnv = Number(process.env.DSHA_NET_EPOCH_GRACE_MS);
    const GRACE_MS = graceEnv >= 1000 && graceEnv <= 120000 ? graceEnv : 20000;
    // 仅供宿主测试：本机假服务也按外网连接处理。
    const includeLoopback = process.env.DSHA_NET_EPOCH_TEST_LOOPBACK === '1';
    const sockets = new Map(); // socket -> { busy }
    const owners = new WeakMap(); // undici request -> socket
    let timer = null, baseline = null;

    const loopback = (host) => {
      if (typeof host !== 'string') return false;
      const h = host.replace(/^\[|\]$/g, '').toLowerCase();
      return h === 'localhost' || h.endsWith('.localhost') || h === '::1'
        || /^127\./.test(h) || /^::ffff:127\./.test(h);
    };
    // 代次文件由宿主写入；只读小的普通文件，不跟随链接，不因 FIFO 等特殊文件阻塞。
    function readEpoch() {
      let fd;
      try {
        fd = fs.openSync(FILE, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
        const info = fs.fstatSync(fd);
        if (!info.isFile() || info.size < 1 || info.size > 64) return '';
        const buffer = Buffer.alloc(info.size);
        const text = buffer.toString('latin1', 0, fs.readSync(fd, buffer, 0, info.size, 0)).trim();
        return /^[0-9a-z.-]{1,64}$/.test(text) ? text : '';
      } catch { return ''; }
      finally { if (fd !== undefined) try { fs.closeSync(fd); } catch {} }
    }
    function startPolling() {
      if (timer) return;
      baseline = readEpoch();
      timer = setInterval(tick, POLL_MS);
      timer.unref();
    }
    function stopPolling() {
      if (timer) clearInterval(timer);
      timer = null;
      baseline = null;
    }
    function tick() {
      if (sockets.size === 0) return stopPolling();
      const now = readEpoch();
      // 文件暂缺或损坏不算网络变化；首次出现代次只作为基线。
      if (!now || now === baseline) return;
      const first = !baseline;
      baseline = now;
      if (!first) recycle();
    }
    function recycle() {
      let idle = 0;
      const watched = [];
      for (const [socket, state] of sockets) {
        if (socket.destroyed) { sockets.delete(socket); continue; }
        if (state.busy === 0) {
          idle++;
          socket.destroy();
        } else watched.push([socket, socket.bytesRead]);
      }
      if (idle) report('idle', idle);
      if (!watched.length) return;
      const check = setTimeout(() => {
        let stalled = 0;
        for (const [socket, before] of watched) {
          const state = sockets.get(socket);
          // 已结束、已空闲或期间收到过数据的连接都还活着，不动它。
          if (!state || socket.destroyed || state.busy === 0 || socket.bytesRead !== before) continue;
          stalled++;
          socket.destroy(Object.assign(new Error('DSHA_NETWORK_CHANGED: no data on this connection since the default network changed'), { code: 'DSHA_NETWORK_CHANGED' }));
        }
        if (stalled) report('stalled', stalled);
      }, GRACE_MS);
      check.unref();
    }
    function report(kind, count) {
      try { process.stderr.write(`[dsha-net] default network changed; closed ${count} ${kind} upstream connection(s)\n`); } catch {}
    }
    function release(request) {
      const socket = owners.get(request);
      if (!socket) return;
      owners.delete(request);
      const state = sockets.get(socket);
      if (state && state.busy > 0) state.busy--;
    }
    try {
      dc.subscribe('undici:client:connected', ({ connectParams, socket }) => {
        if (!socket || sockets.has(socket)) return;
        if (!includeLoopback && (loopback(connectParams && connectParams.hostname) || loopback(socket.remoteAddress))) return;
        sockets.set(socket, { busy: 0 });
        socket.once('close', () => { sockets.delete(socket); });
        startPolling();
      });
      dc.subscribe('undici:client:sendHeaders', ({ request, socket }) => {
        const state = socket && sockets.get(socket);
        if (!state || !request) return;
        state.busy++;
        owners.set(request, socket);
      });
      dc.subscribe('undici:request:trailers', ({ request }) => release(request));
      dc.subscribe('undici:request:error', ({ request }) => release(request));
    } catch {}
  }
}
