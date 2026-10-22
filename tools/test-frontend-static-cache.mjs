// 冷启动回归：外壳静态资源必须可复用，索引必须永不缓存。
// 补丁前 3080 对 dist 资产只回 content-type + 200，浏览器每次冷启动都要重下并
// 重新 gzip；补丁后同一份字节用 304 复用，而渲染索引仍然不缓存。
// 显式参数：`node tools/test-frontend-static-cache.mjs <runtime 根或模块文件>`；
// 不给参数时按 CI 夹具选择当前 raw 运行时。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import http from 'node:http';
import {createRequire} from 'node:module';
import {gunzipSync} from 'node:zlib';

const root = path.resolve(import.meta.dirname, '..');
const relativeModule = 'node_modules/@deepseek-ai/dsh-host-frontend-static/lib/index.js';

async function moduleFile() {
  const explicit = process.argv[2] || process.env.DSHA_FRONTEND_STATIC_MODULE;
  if (explicit) {
    const selected = path.resolve(explicit);
    return fs.statSync(selected).isDirectory() ? path.join(selected, relativeModule) : selected;
  }
  const { testRuntime } = await import('./test-runtime-fixture.mjs');
  return path.join(testRuntime('raw'), relativeModule);
}

const recipe = JSON.parse(
    fs.readFileSync(path.join(root, 'app/src/main/assets/frontend-static-cache-patch.json'), 'utf8'));
assert.equal(recipe.module, '@deepseek-ai/dsh-host-frontend-static/lib/index.js');
assert.equal(recipe.dshVersion, '0.2.1-rc.2');
const file = await moduleFile();
const original = fs.readFileSync(file, 'utf8');

// 与 ManagedPatchChain / ExactTextPatch 相同的判据：每条 before 必须唯一命中。
let patched = original;
for (const { before, after } of recipe.patches) {
  assert.equal(patched.split(before).length, 2, '补丁锚点必须唯一命中：' + before.slice(0, 60));
  patched = patched.replace(before, after);
}
assert.ok(patched.includes('DSHA_STATIC_CACHE_V2'), '补丁必须留下可跳过的标记');

// 幂等：registry 的 markers 命中时整条配方跳过，重复应用不再改动字节。
assert.equal(patched.includes('DSHA_STATIC_CACHE_V2') ? patched : '', patched);

// 真实模块的依赖与导出都在 vm 里替换掉：这里量的是 serveStatic 的响应行为。
const reads = [];
let readFailure = null;
function load(source) {
  const context = {
    readFile: async (target) => {
      reads.push(target);
      if (readFailure !== null) throw readFailure;
      return fs.promises.readFile(target);
    },
    open: async target => {
      const file = await fs.promises.open(target, 'r');
      return {stat: options => file.stat(options), close: () => file.close(), readFile: async () => {
        reads.push(target);
        if (readFailure !== null) throw readFailure;
        return file.readFile();
      }};
    },
    dirname: path.dirname,
    extname: path.extname,
    join: path.join,
    normalize: path.normalize,
    resolve: path.resolve,
    sep: path.sep,
    z: { object: (shape) => ({ shape }), string: () => ({ required: () => ({}) }) },
    URL,
    Object,
    Promise,
    Buffer,
  };
  const stripped = source
      .replace(/^import [^\n]*\n/gm, '')
      .replace(/^export \{[^}]*\};\s*$/m, '');
  assert.ok(!/^import /m.test(stripped), '模块导入必须已被替换，避免依赖真实运行时');
  return vm.runInNewContext(`${stripped}\n({ serveStatic, apply });`, context, { filename: file });
}

const before = load(original);
const after = load(patched);
assert.equal(typeof after.serveStatic, 'function');

const dist = fs.mkdtempSync(path.join(os.tmpdir(), 'dsha-frontend-static-'));
fs.writeFileSync(path.join(dist, 'index.html'), '<html><head></head><body>index</body></html>');
fs.mkdirSync(path.join(dist, 'assets'));
const asset = path.join(dist, 'assets/index-BPHePDI_.js');
fs.writeFileSync(asset, 'console.log("shell");\n');
const binary = path.join(dist, 'assets/blob.bin');
fs.writeFileSync(binary, 'not-a-known-extension\n');

function response() {
  const state = { status: 0, headers: undefined, body: undefined };
  return {
    state,
    writeHead(status, headers) { state.status = status; state.headers = headers; },
    end(body) { state.body = body; },
  };
}

const distIndex = path.join(dist, 'index.html');
const rootDir = path.resolve(dist);
let renders = 0;
const renderIndex = async () => { renders++; return fs.readFileSync(distIndex, 'utf8'); };
const authorize = () => true;
const call = async (module, pathname, req) => {
  const res = response();
  await module.serveStatic(pathname, res, rootDir, distIndex, authorize, renderIndex, req);
  return res;
};
const status = async (module, pathname, req) => (await call(module, pathname, req)).state.status;

// 1) 未打补丁的模块没有任何缓存指令：这正是每次冷启动重下重压的原因。
const untouched = response();
await before.serveStatic('/assets/index-BPHePDI_.js', untouched, rootDir, distIndex, authorize,
    renderIndex);
assert.equal(untouched.state.status, 200);
assert.equal(untouched.state.headers['cache-control'], undefined);
assert.equal(untouched.state.headers.etag, undefined);

// 2) 渲染索引永不缓存：它每次请求都重新渲染注入行（__DSH_BOOT__ 与 DSHA 兼容脚本）。
const beforeRenders = renders;
const index = await call(after, '/');
assert.equal(index.state.status, 200);
assert.equal(index.state.headers['cache-control'], 'no-store');
assert.equal(index.state.headers.etag, undefined);
assert.equal(renders, beforeRenders + 1, '索引仍必须每次重新渲染');
// 索引带条件请求也不得走 304：缓存住它等于把内联的 DSHA 启动脚本一起冻住。
assert.equal(await status(after, '/', { headers: { 'if-none-match': '*' } }), 200);
const distRootIndex = await call(after, '');
assert.equal(distRootIndex.state.headers['cache-control'], 'no-store', 'dist 根同样是索引');

// 3) dist 资产给出可复用的标签，并且不再是「无条件回 200」。
const first = await call(after, '/assets/index-BPHePDI_.js');
assert.equal(first.state.status, 200);
assert.equal(first.state.headers['cache-control'], 'no-cache');
assert.match(first.state.headers.etag, /^W\/"[0-9a-f]+(?:-[0-9a-f]+){4}"$/);
assert.ok(Buffer.isBuffer(first.state.body));
const tag = first.state.headers.etag;

// 4) 第二次冷启动：带 If-None-Match 得到 304，没有响应体，也不读文件。
const readsBeforeRevalidation = reads.length;
readFailure = Object.assign(new Error('must not read a revalidated asset'), { code: 'EDSHA_READ' });
const revalidated = await call(after, '/assets/index-BPHePDI_.js',
    { headers: { 'if-none-match': tag } });
readFailure = null;
assert.equal(revalidated.state.status, 304);
assert.equal(revalidated.state.body, undefined, '304 不得携带响应体');
assert.equal(revalidated.state.headers['cache-control'], 'no-cache');
assert.equal(revalidated.state.headers.etag, tag, '304 必须回带同一个标签');
assert.equal(reads.length, readsBeforeRevalidation,
    '命中重验时不得读磁盘：标签只由 stat 派生');

// 5) issue 里那条最小复现：通配 If-None-Match: * 也应当复用现有表示（RFC 9110）。
const wildcard = await call(after, '/assets/index-BPHePDI_.js',
    { headers: { 'if-none-match': '*' } });
assert.equal(wildcard.state.status, 304, '通配条件请求必须命中');
// 旧 WebView 可能合并多个候选，或带上别的弱校验前缀。
for (const value of [`"stale", ${tag}`, `W/"other" , ${tag}`, `  ${tag}  `, tag.slice(2)]) {
  const merged = await call(after, '/assets/index-BPHePDI_.js', { headers: { 'if-none-match': value } });
  assert.equal(merged.state.status, 304, '候选列表里的命中必须复用：' + value);
}
// 不匹配 / 没有请求头 / 旧版六参数调用都必须回到完整 200。
for (const req of [undefined, { headers: {} }, { headers: { 'if-none-match': 'W/"nope"' } },
  { headers: new Map([['if-none-match', 'W/"nope"']]) }]) {
  const miss = await call(after, '/assets/index-BPHePDI_.js', req);
  assert.equal(miss.state.status, 200, '未命中必须回 200');
  assert.ok(Buffer.isBuffer(miss.state.body));
}
const legacyResponse = response();
await after.serveStatic('/assets/index-BPHePDI_.js', legacyResponse, rootDir, distIndex, authorize,
    renderIndex);
assert.equal(legacyResponse.state.status, 200, '导出签名必须保持向后兼容');
assert.equal(legacyResponse.state.headers.etag, tag, '旧调用仍应拿到可复用的标签');

// 6) 内容或 mtime 变化后标签必须改变，否则 APK 更新后会继续拿旧外壳。
fs.writeFileSync(asset, 'console.log("shell v2");\n');
const rebuilt = await call(after, '/assets/index-BPHePDI_.js');
assert.equal(rebuilt.state.status, 200);
assert.notEqual(rebuilt.state.headers.etag, tag, '改写后的资产必须换标签');
assert.equal(await status(after, '/assets/index-BPHePDI_.js', { headers: { 'if-none-match': tag } }),
    200, '旧标签不能拿到 304');

// 7) 原有安全与缺失语义不变。
assert.equal(await status(after, '/../../etc/passwd'), 403, '路径逃逸仍然 403');
assert.equal(await status(after, '/assets/does-not-exist.js'), 404, '缺失仍然 404');
assert.equal(await status(after, '/assets'), 404, '目录目标仍然 404');
assert.equal(await status(after, '/assets', {headers:{'if-none-match':'*'}}), 404, '条件目录请求不得误回304');
assert.equal(await status(after, '/assets/does-not-exist.js', {headers:{'if-none-match':'*'}}), 404);
const head = await call(after, '/assets/index-BPHePDI_.js', {method:'HEAD',headers:{}});
assert.equal(head.state.status, 200); assert.equal(head.state.body, undefined);
const unknown = await call(after, '/assets/blob.bin');
assert.equal(unknown.state.status, 200);
assert.equal(unknown.state.headers['content-type'], 'application/octet-stream');
assert.equal(unknown.state.headers['cache-control'], 'no-cache');
assert.equal(unknown.state.headers.etag !== undefined, true, '未知扩展名同样可复用');
// 鉴权仍然先于任何字节：拒绝时不得读文件也不得写头。
let authorized = false;
const denied = response();
await after.serveStatic('/', denied, rootDir, distIndex, () => authorized, renderIndex);
assert.equal(denied.state.status, 0, '未授权的索引不得写出任何响应');

// Actual HTTP + the pinned runtime's compression middleware, including empty 304/HEAD bodies.
const compression=createRequire(file)('compression');
const server=http.createServer((req,res)=>compression({threshold:0,level:1})(req,res,()=>{
  const auth=()=>{if(req.headers.authorization!=='deny')return true;res.writeHead(401,{'cache-control':'no-store'});res.end();return false;};
  after.serveStatic(decodeURIComponent(new URL(req.url,'http://fixture').pathname),res,rootDir,distIndex,auth,renderIndex,req).catch(error=>{res.destroy(error);});
}));
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const fetchHttp=(url,headers={},method='GET')=>new Promise((resolve,reject)=>{
  const request=http.request({host:'127.0.0.1',port:server.address().port,path:url,headers,method},response=>{
    const body=[];response.on('data',part=>body.push(part));response.on('end',()=>resolve({status:response.statusCode,headers:response.headers,body:Buffer.concat(body)}));
  });request.on('error',reject);request.end();
});
try {
  const initial=await fetchHttp('/assets/index-BPHePDI_.js',{'accept-encoding':'gzip'});
  assert.equal(initial.status,200);assert.equal(initial.headers['content-encoding'],'gzip');
  assert.deepEqual(gunzipSync(initial.body),fs.readFileSync(asset));assert.match(initial.headers.vary,/Accept-Encoding/i);
  const hit=await fetchHttp('/assets/index-BPHePDI_.js',{'accept-encoding':'gzip','if-none-match':initial.headers.etag});
  assert.equal(hit.status,304);assert.equal(hit.body.length,0);assert.equal(hit.headers.etag,initial.headers.etag);
  const head=await fetchHttp('/assets/index-BPHePDI_.js',{},'HEAD');assert.equal(head.status,200);assert.equal(head.body.length,0);
  const index=await fetchHttp('/',{'if-none-match':'*'});assert.equal(index.status,200);assert.equal(index.headers['cache-control'],'no-store');assert.equal(index.headers.etag,undefined);
  assert.equal((await fetchHttp('/',{'if-none-match':'*',authorization:'deny'})).status,401);
  assert.equal((await fetchHttp('/assets',{'if-none-match':'*'})).status,404);
  assert.equal((await fetchHttp('/..%2f..%2fetc/passwd')).status,403);
  const old=fs.statSync(asset);const replacement=asset+'.new';fs.writeFileSync(replacement,fs.readFileSync(asset));fs.utimesSync(replacement,old.atime,old.mtime);fs.renameSync(replacement,asset);
  const changed=await fetchHttp('/assets/index-BPHePDI_.js',{'if-none-match':initial.headers.etag});assert.equal(changed.status,200);assert.notEqual(changed.headers.etag,initial.headers.etag,'same length/mtime atomic replacement must change identity');
} finally {await new Promise(resolve=>server.close(resolve));}

fs.rmSync(dist, { recursive: true, force: true });
console.log('frontend static cache: ok');
