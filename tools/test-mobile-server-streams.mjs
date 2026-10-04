// Real Node 24 HTTP sockets: isolated loopback servers, no Android/user data.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, request, ServerResponse } from 'node:http';
import { once } from 'node:events';
import { gunzipSync, brotliDecompressSync } from 'node:zlib';
import { installResponseCompression, MAX_JSON_BYTES, MAX_COMPRESSIONS } from '../app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/compress.js';

const json = JSON.stringify({ text: '测试é'.repeat(4000) });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function fixture(handler, run) {
  const dispose = installResponseCompression();
  const server = createServer(handler);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try { await run(server.address().port); }
  finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); dispose(); }
}
function fetch(port, options = {}) {
  return new Promise((resolve, reject) => {
    const req = request({ hostname: '127.0.0.1', port, headers: { 'Accept-Encoding': 'gzip' }, ...options }, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('error', reject);
      res.on('end', () => resolve({ res, body: Buffer.concat(chunks) }));
    });
    req.on('error', reject); req.end();
  });
}
function decoded({ res, body }) {
  return res.headers['content-encoding'] === 'gzip' ? gunzipSync(body)
    : res.headers['content-encoding'] === 'br' ? brotliDecompressSync(body) : body;
}

for (const encoding of ['gzip', 'br', 'BR;q=1,gzip;q=0']) test(`complete JSON compresses ${encoding}, preserves reason/end callback`, async () => {
  let callback = 0;
  await fixture((_req, res) => {
    res.writeHead(200, 'Mobile JSON', { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(json), Vary: 'Origin' });
    assert.equal(res.end(json, 'utf8', () => callback++), res);
  }, async port => {
    const result = await fetch(port, { headers: { 'Accept-Encoding': encoding } });
    assert.equal(decoded(result).toString(), json);
    assert.equal(result.res.headers['content-length'], undefined);
    assert.equal(result.res.headers['transfer-encoding'], 'chunked');
    assert.equal(result.res.statusMessage, 'Mobile JSON');
    assert.equal(result.res.headers.vary, 'Origin, Accept-Encoding');
    await delay(5); assert.equal(callback, 1);
  });
});
test('accepted asynchronous end exposes native ended guard, immutable headers and one final callback', async () => {
  let callbacks = 0, response;
  await fixture((_req, res) => {
    response = res;
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(json, () => callbacks++);
    assert.equal(res.finished, true); assert.equal(res.writableEnded, true);
    assert.equal(res.writableFinished, false); assert.equal(res.headersSent, true);
    assert.equal(callbacks, 0);
    assert.throws(() => res.writeHead(201), { code: 'ERR_HTTP_HEADERS_SENT' });
  }, async port => {
    assert.equal(decoded(await fetch(port)).toString(), json);
    await delay(5); assert.equal(callbacks, 1);
    assert.equal(response.writableFinished, true);
    assert.equal(Object.hasOwn(response, 'writableFinished'), false);
    assert.equal(Object.getOwnPropertyDescriptor(response, 'finished').get, undefined);
  });
});
test('17 MiB JSON passes through while a concurrent small request finishes within 100 ms', async () => {
  const large = JSON.stringify({ text: 'x'.repeat(17 * 1024 * 1024) });
  let started;
  const ready = new Promise(resolve => started = resolve);
  await fixture((req, res) => {
    res.setHeader('Content-Type', 'application/json');
    if (req.url === '/large') { res.end(large); started(); }
    else res.end('{"ok":true}');
  }, async port => {
    const pending = fetch(port, { path: '/large' });
    await ready;
    const begin = performance.now(), small = await fetch(port);
    assert.ok(performance.now() - begin < 100, 'small response was blocked by large JSON');
    assert.equal(small.body.toString(), '{"ok":true}');
    const big = await pending;
    assert.equal(big.res.headers['content-encoding'], undefined);
    assert.equal(big.body.toString(), large);
  });
});
test('asynchronous worker saturation passes excess complete bodies through without queuing', async () => {
  const body = Buffer.alloc(MAX_JSON_BYTES);
  for (let index = 0; index < body.length; index++) body[index] = (index * 73 + (index >>> 8)) & 255;
  await fixture((_req, res) => { res.setHeader('Content-Type', 'application/json'); res.end(body); }, async port => {
    const results = await Promise.all(Array.from({ length: MAX_COMPRESSIONS + 6 }, () => fetch(port, { headers: { 'Accept-Encoding': 'br' } })));
    for (const result of results) assert.deepEqual(decoded(result), body);
    assert.ok(results.some(result => result.res.headers['content-encoding'] === undefined), 'saturated work must use native pass-through');
  });
});
test('scope excludes a different HTTP server and unload restores the prototype during accepted work', async () => {
  const original = Object.fromEntries(['writeHead', 'write', 'end', 'flushHeaders'].map(name => [name, ServerResponse.prototype[name]]));
  let dispose, callbacks = 0;
  const owned = createServer((_req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(json, () => callbacks++);
    dispose();
    for (const [name, method] of Object.entries(original)) assert.equal(ServerResponse.prototype[name], method);
  });
  const other = createServer((_req, res) => { res.setHeader('Content-Type', 'application/json'); res.end(json); });
  owned.listen(0, '127.0.0.1'); other.listen(0, '127.0.0.1');
  await Promise.all([once(owned, 'listening'), once(other, 'listening')]);
  dispose = installResponseCompression({ matches: req => req.socket.localPort === owned.address().port });
  try {
    const unowned = await fetch(other.address().port);
    assert.equal(unowned.res.headers['content-encoding'], undefined);
    assert.equal(unowned.body.toString(), json);
    assert.equal(decoded(await fetch(owned.address().port)).toString(), json);
    await delay(5); assert.equal(callbacks, 1);
  } finally {
    dispose();
    await Promise.all([owned, other].map(server => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); })));
  }
});
test('cancellation during accepted compression closes once and releases public end guards', async () => {
  let response, callbacks = 0, callbackCode;
  await fixture((_req, res) => {
    response = res;
    res.setHeader('Content-Type', 'application/json');
    res.end('x'.repeat(MAX_JSON_BYTES), error => { callbacks++; callbackCode = error?.code; });
    res.destroy();
  }, async port => {
    await assert.rejects(fetch(port), { code: 'ECONNRESET' });
    for (let index = 0; index < 20 && callbacks === 0; index++) await delay(5);
    assert.equal(callbacks, 1); assert.equal(callbackCode, 'ERR_STREAM_PREMATURE_CLOSE');
    assert.equal(response.writableEnded, true);
    assert.equal(Object.hasOwn(response, 'writableFinished'), false);
    await delay(10); assert.equal(callbacks, 1);
  });
});
test('write after accepted asynchronous end retains Node error and does not append a second body', async () => {
  let endCalls = 0, writeCode, errorCode;
  await fixture((_req,res) => {
    res.on('error',error => errorCode=error.code);
    res.setHeader('Content-Type','application/json');
    res.end(json,() => endCalls++);
    assert.equal(res.write('late',error => writeCode=error.code),false);
  },async port => {
    assert.equal(decoded(await fetch(port)).toString(),json);
    for(let index=0;index<20&&endCalls===0;index++) await delay(5);
    assert.equal(endCalls,1);assert.equal(writeCode,'ERR_STREAM_WRITE_AFTER_END');
    assert.equal(errorCode,writeCode);
  });
});
for (const type of ['buffer', 'uint8', 'latin1', 'implicit']) test(`end overload ${type} preserves original bytes`, async () => {
  const expected = type === 'latin1' ? Buffer.from('é'.repeat(5000), 'latin1') : Buffer.from(json);
  await fixture((_req, res) => {
    res.setHeader('Content-Type', 'application/json');
    if (type !== 'implicit') res.writeHead(200);
    if (type === 'latin1') res.end('é'.repeat(5000), 'latin1');
    else res.end(type === 'uint8' ? new Uint8Array(expected) : expected);
  }, async port => {
    const result = await fetch(port);
    assert.equal(result.res.headers['content-encoding'], 'gzip');
    assert.deepEqual(decoded(result), expected);
  });
});
for (const implicit of [false, true]) test(`chunked JSON (${implicit ? 'implicit' : 'explicit'} head) reaches client and callback before end`, async () => {
  let callback = false, beforeEnd = false, ended = false;
  await fixture((_req, res) => {
    res.setHeader('Content-Type', 'application/json');
    if (!implicit) res.writeHead(200);
    res.write('{"text":"', 'utf8', () => callback = true);
    setTimeout(() => { beforeEnd = callback; ended = true; res.end('later"}'); }, 80);
  }, async port => {
    const data = [];
    const result = await new Promise((resolve, reject) => {
      const req = request({ hostname: '127.0.0.1', port, headers: { 'Accept-Encoding': 'gzip' } }, res => {
        assert.equal(res.headers['content-encoding'], undefined);
        res.on('data', chunk => { data.push(chunk); if (data.length === 1) assert.equal(ended, false); });
        res.on('end', () => resolve(Buffer.concat(data).toString()));
        res.on('error', reject);
      }); req.on('error', reject); req.end();
    });
    assert.equal(result, '{"text":"later"}'); assert.equal(beforeEnd, true);
  });
});
test('large writes return native false and drain; all chunks/encodings/callbacks survive', async () => {
  const expected = Buffer.alloc(2 * MAX_JSON_BYTES, 65);
  let returned, writes = 0, drains = 0, finished = 0;
  await fixture((_req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    returned = res.write(expected, () => writes++);
    res.once('drain', () => { drains++; res.end('é', 'latin1', () => finished++); });
  }, async port => {
    const result = await fetch(port);
    assert.equal(result.res.headers['content-encoding'], undefined);
    assert.deepEqual(result.body, Buffer.concat([expected, Buffer.from([233])]));
    await delay(5);
    assert.equal(returned, false); assert.equal(writes, 1); assert.equal(drains, 1); assert.equal(finished, 1);
  });
});
test('write string encoding, Buffer encoding overload and Uint8Array preserve native callbacks and bytes', async () => {
  let writes=0;
  await fixture((_req,res)=>{
    res.writeHead(200,{ 'Content-Type':'application/json' });
    res.write('é','latin1',()=>writes++);
    res.write(Buffer.from([65]),'utf8',()=>writes++);
    res.write(new Uint8Array([66]),()=>writes++);
    res.end('é','utf8');
  },async port=>{
    const result=await fetch(port);assert.equal(result.res.headers['content-encoding'],undefined);
    assert.deepEqual(result.body,Buffer.from([233,65,66,195,169]));await delay(5);assert.equal(writes,3);
  });
});
for (const contentType of ['application/json', 'text/event-stream']) test(`${contentType} unfinished stream sends data, supports client cancellation`, async () => {
  let closed = false, callbacks = 0, response;
  await fixture((_req, res) => {
    response = res; res.once('close', () => closed = true);
    res.writeHead(200, { 'Content-Type': contentType });
    res.write('data: initial\n\n', () => callbacks++);
  }, async port => {
    await new Promise((resolve, reject) => {
      const req = request({ hostname: '127.0.0.1', port, headers: { 'Accept-Encoding': 'gzip,br' } }, res => {
        assert.equal(res.headers['content-encoding'], undefined);
        res.once('data', chunk => { assert.equal(chunk.toString(), 'data: initial\n\n'); req.destroy(); resolve(); });
      }); req.on('error', reject); req.end();
    });
    for (let i = 0; i < 20 && !closed; i++) await delay(5);
    assert.equal(closed, true); assert.equal(callbacks, 1); assert.equal(response.writableEnded, false);
  });
});
test('flushHeaders explicitly commits deferred JSON and retains streaming behavior', async () => {
  let headSent;
  await fixture((_req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.flushHeaders(); headSent = res.headersSent;
    setTimeout(() => res.end(json), 20);
  }, async port => {
    const result = await fetch(port);
    assert.equal(headSent, true); assert.equal(result.res.headers['content-encoding'], undefined);
    assert.equal(result.body.toString(), json);
  });
});
for (const mode of ['head', 'empty', 'tiny', 'oversized', 'q-zero', 'encoded', 'sse', 'raw-headers', 'no-transform', 'chunked', 'strong-etag', 'strict-length', '204'])
  test(`native pass-through ${mode}`, async () => {
    let callbacks = 0;
    const body = mode === 'empty' || mode === '204' ? '' : mode === 'tiny' ? '123' : mode === 'oversized' ? 'x'.repeat(MAX_JSON_BYTES + 1) : json;
    await fixture((_req, res) => {
      const headers = { 'Content-Type': mode === 'sse' ? 'text/event-stream' : 'application/json' };
      if (mode === 'encoded') headers['Content-Encoding'] = 'identity';
      if (mode === 'no-transform') headers['Cache-Control'] = 'private, no-transform';
      if (mode === 'chunked') headers['Transfer-Encoding'] = 'chunked';
      if (mode === 'strong-etag') headers.ETag = '"original-bytes"';
      if (mode === 'strict-length') { res.strictContentLength = true; headers['Content-Length'] = Buffer.byteLength(body); }
      if (mode === 'raw-headers') res.writeHead(200, ['Content-Type', 'application/json', 'Set-Cookie', 'a=1', 'Set-Cookie', 'b=2']);
      else res.writeHead(mode === '204' ? 204 : 200, headers);
      if (mode === 'empty') res.end(() => callbacks++); else res.end(body, () => callbacks++);
    }, async port => {
      const result = await fetch(port, { method: mode === 'head' ? 'HEAD' : 'GET', headers: { 'Accept-Encoding': mode === 'q-zero' ? 'gzip;q=0,br;q=0' : 'gzip' } });
      assert.equal(result.res.headers['content-encoding'], mode === 'encoded' ? 'identity' : undefined);
      assert.equal(result.body.toString(), mode === 'head' ? '' : body);
      if (mode === 'raw-headers') assert.deepEqual(result.res.headers['set-cookie'], ['a=1', 'b=2']);
      await delay(5); assert.equal(callbacks, 1);
    });
  });
test('writeHead validates native status/header errors synchronously; callback errors after end remain native', async () => {
  let invalid = 0, callbackError, responseError;
  await fixture((_req, res) => {
    for (const args of [[99, { 'Content-Type': 'application/json' }], [200, { 'Content-Type': 'application/json', 'Bad\nHeader': 'x' }], [200, 'Bad\nReason', { 'Content-Type': 'application/json' }]]) {
      try { res.writeHead(...args); } catch { invalid++; }
    }
    res.on('error', error => responseError = error.code);
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}');
    assert.equal(res.write('late', error => callbackError = error.code), false);
  }, async port => {
    assert.equal((await fetch(port)).body.toString(), '{}');
    await delay(5); assert.equal(invalid, 3);
    assert.equal(callbackError, 'ERR_STREAM_WRITE_AFTER_END'); assert.equal(responseError, callbackError);
  });
});
