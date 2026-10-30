
import http from 'node:http';
import compression from '/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/compression/index.js';
import Negotiator from '/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/negotiator/index.js';
import assert from 'node:assert';

const mod = await import('/root/work/DSHA/app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/compress.js');
const restore = mod.installResponseCompression({ matches: () => true });

const middleware = compression({ level: 1, threshold: 1024, filter(req, res) {
  if (res.getHeader('content-range') !== undefined) return false;
  const ct = res.getHeader('content-type');
  if (typeof ct === 'string' && ct.toLowerCase().startsWith('text/event-stream')) return false;
  return compression.filter(req, res);
}});
const gzip = (req, res, next) => {
  const encoding = new Negotiator(req).encoding(['gzip', 'identity']);
  const g = Object.create(req);
  Object.defineProperty(g, 'headers', { value: { ...req.headers, 'accept-encoding': encoding === 'gzip' ? 'gzip' : 'identity' } });
  middleware(g, res, next);
};

let crashes = 0;
process.on('uncaughtException', () => { crashes++; });

const SMALL = JSON.stringify({ ok: false, error: 'method-not-allowed' });   // 41B < MIN(4KB)
const LARGE = JSON.stringify({ ok: true, rows: 'z'.repeat(120 * 1024) });   // > MIN
const TEXT = 'plain text body';

const server = http.createServer((req, res) => {
  gzip(req, res, () => {
    const u = new URL(req.url, 'http://x');
    const which = u.searchParams.get('k');
    if (which === 'text') {
      res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8', 'content-length': Buffer.byteLength(TEXT) });
      res.end(TEXT); return;
    }
    const body = which === 'large' ? LARGE : SMALL;
    res.writeHead(405, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body) });
    res.end(body);
  });
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const port = server.address().port;

function fetchIt(k, ae) {
  return fetch('http://127.0.0.1:' + port + '/?k=' + k, { headers: { 'accept-encoding': ae }, signal: AbortSignal.timeout(3000) })
    .then(async r => ({ status: r.status, enc: r.headers.get('content-encoding') ?? 'none', body: await r.text() }));
}

const results = [];
function record(name, fn) { results.push([name, fn]); }

// 1) 回归：外部压缩中间件重入 writeHead（本 bug）
record('small JSON + gzip 中间件不崩、返回原状态与原文', async () => {
  const r = await fetchIt('small', 'gzip');
  assert.equal(r.status, 405);
  assert.equal(r.body, SMALL);
});
record('small JSON + br 中间件同样不崩', async () => {
  const r = await fetchIt('small', 'br');
  assert.equal(r.status, 405);
  assert.equal(r.body, SMALL);
});
record('小 JSON 不被压缩（保持原始字节）', async () => {
  const r = await fetchIt('small', 'gzip');
  assert.equal(r.enc, 'none');
  assert.equal(r.body, SMALL);
});

// 2) 原有能力不回归：大 JSON 仍压缩且内容正确
record('大 JSON 仍按 gzip 压缩且解压后原文一致', async () => {
  const r = await fetchIt('large', 'gzip');
  assert.equal(r.status, 405);
  assert.equal(r.enc, 'gzip');
  assert.equal(r.body, LARGE);
});
record('大 JSON 在 br 下也正确（不再断连）', async () => {
  const r = await fetchIt('large', 'br');
  assert.equal(r.body, LARGE);
});

// 3) 非 JSON 不受影响
record('text/plain 原样透传', async () => {
  const r = await fetchIt('text', 'gzip');
  assert.equal(r.body, TEXT);
  assert.equal(r.enc, 'none');
});

// 4) dispose 恢复原型
record('restore() 后 writeHead 恢复原生行为', async () => {
  restore();
  const proto = http.ServerResponse.prototype;
  assert.equal(typeof proto.writeHead, 'function');
});

let pass = 0, fail = 0;
for (const [name, fn] of results) {
  try { await fn(); pass++; console.log('PASS', name); }
  catch (e) { fail++; console.log('FAIL', name, '::', e.message); }
}
console.log('\npass=' + pass, 'fail=' + fail, 'crashes=' + crashes);
server.close(); process.exit(fail === 0 && crashes === 0 ? 0 : 1);
