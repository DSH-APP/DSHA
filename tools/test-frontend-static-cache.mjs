// 冷启动回归：外壳静态资源必须可复用，索引必须永不缓存。
// 0.2.2 起上游自带内容哈希弱 ETag + 304 + HEAD；DSHA_STATIC_CACHE_V3 只补两点：
// 渲染索引显式 no-store（其中内联了 DSHA 启动注入），以及 RFC 9110 的
// If-None-Match 候选列表 / 通配匹配（旧 WebView 会合并多个候选）。
// 显式参数：`node tools/test-frontend-static-cache.mjs <runtime 根或模块文件>`；
// 不给参数时按 CI 夹具选择当前 raw 运行时。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { createHash } from 'node:crypto';

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
const currentDshVersion = JSON.parse(
    fs.readFileSync(path.join(root, 'app/src/main/assets/runtime-patches.json'), 'utf8')).dshVersion;
assert.equal(recipe.module, '@deepseek-ai/dsh-host-frontend-static/lib/index.js');
assert.equal(recipe.dshVersion, currentDshVersion);
const file = await moduleFile();
const original = fs.readFileSync(file, 'utf8');
if (recipe.sourceSha256)
  assert.equal(createHash('sha256').update(fs.readFileSync(file)).digest('hex'), recipe.sourceSha256, '上游源码与配方绑定不一致');

// 与 ManagedPatchChain / ExactTextPatch 相同的判据：每条 before 必须唯一命中。
let patched = original;
for (const { before, after } of recipe.patches) {
  assert.equal(patched.split(before).length, 2, '补丁锚点必须唯一命中：' + before.slice(0, 60));
  patched = patched.replace(before, after);
}
assert.ok(patched.includes('DSHA_STATIC_CACHE_V3'), '补丁必须留下可跳过的标记');

// 真实模块的依赖与导出都在 vm 里替换掉：这里量的是 serveStatic 的响应行为。
const reads = [];
function load(source) {
  const context = {
    createHash,
    readFile: async (target, encoding) => { reads.push(target); return fs.promises.readFile(target, encoding); },
    dirname: path.dirname, extname: path.extname, join: path.join,
    normalize: path.normalize, resolve: path.resolve, sep: path.sep,
    z: { object: (shape) => ({ shape }), string: () => ({ required: () => ({}) }) },
    URL, Response, Headers, ReadableStream, Object, Promise, Buffer, Set,
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
fs.writeFileSync(path.join(dist, 'assets/blob.bin'), 'not-a-known-extension\n');
const distIndex = path.join(dist, 'index.html');
const rootDir = path.resolve(dist);
let renders = 0;
const renderIndex = (html) => { renders++; return html; };
let refusal;
const authorize = () => refusal;
const call = (module, pathname, headers = {}, method = 'GET') =>
  module.serveStatic(new Request('http://fixture' + pathname, { headers, method }), rootDir, distIndex, authorize, renderIndex);

try {
  // 1) 上游原样：索引不带任何缓存头，候选列表不命中。
  const rawIndex = await call(before, '/');
  assert.equal(rawIndex.headers.get('cache-control'), null);
  const rawAsset = await call(before, '/assets/index-BPHePDI_.js');
  const tag = rawAsset.headers.get('etag');
  assert.match(tag, /^W\/"/);
  assert.equal((await call(before, '/assets/index-BPHePDI_.js', { 'if-none-match': `"stale", ${tag}` })).status, 200);

  // 2) 渲染索引永不缓存，也不走 304：缓存住它等于冻住内联的 DSHA 启动脚本。
  const rendersBefore = renders;
  const index = await call(after, '/');
  assert.equal(index.status, 200);
  assert.equal(index.headers.get('cache-control'), 'no-store');
  assert.equal(index.headers.get('etag'), null);
  assert.equal(renders, rendersBefore + 1, '索引仍必须每次重新渲染');
  assert.equal((await call(after, '/', { 'if-none-match': '*' })).status, 200);
  assert.equal((await call(after, '/index.html')).headers.get('cache-control'), 'no-store', '显式 index 路径同样是索引');

  // 3) 资产标签保持上游语义，精确命中与列表 / 通配 / 弱前缀都回 304。
  const first = await call(after, '/assets/index-BPHePDI_.js');
  assert.equal(first.status, 200);
  assert.equal(first.headers.get('cache-control'), 'no-cache');
  assert.equal(first.headers.get('etag'), tag, '标签算法沿用上游');
  for (const value of [tag, '*', ` * `, `"stale", ${tag}`, `W/"other" , ${tag}`, `  ${tag}  `, tag.slice(2)]) {
    const hit = await call(after, '/assets/index-BPHePDI_.js', { 'if-none-match': value });
    assert.equal(hit.status, 304, '候选列表里的命中必须复用：' + value);
    assert.equal(hit.body, null, '304 不得携带响应体');
    assert.equal(hit.headers.get('etag'), tag);
  }
  for (const headers of [{}, { 'if-none-match': 'W/"nope"' }, { 'if-none-match': '"a", "b"' }]) {
    const miss = await call(after, '/assets/index-BPHePDI_.js', headers);
    assert.equal(miss.status, 200, '未命中必须回 200');
    assert.equal(await miss.text(), 'console.log("shell");\n');
  }

  // 4) 内容变化后标签必须改变，旧标签不能拿到 304（APK 更新后不能继续用旧外壳）。
  fs.writeFileSync(asset, 'console.log("shell v2");\n');
  const rebuilt = await call(after, '/assets/index-BPHePDI_.js');
  assert.notEqual(rebuilt.headers.get('etag'), tag);
  assert.equal((await call(after, '/assets/index-BPHePDI_.js', { 'if-none-match': tag })).status, 200);

  // 5) 原有安全与缺失语义不变。
  assert.equal((await call(after, '/..%2f..%2fetc/passwd')).status, 403, '路径逃逸仍然 403');
  assert.equal((await call(after, '/assets/does-not-exist.js')).status, 404);
  assert.equal((await call(after, '/assets')).status, 404, '目录目标仍然 404');
  assert.equal((await call(after, '/assets', { 'if-none-match': '*' })).status, 404, '条件目录请求不得误回 304');
  assert.equal((await call(after, '/assets/does-not-exist.js', { 'if-none-match': '*' })).status, 404);
  const head = await call(after, '/assets/index-BPHePDI_.js', {}, 'HEAD');
  assert.equal(head.status, 200); assert.equal(head.body, null);
  const unknown = await call(after, '/assets/blob.bin');
  assert.equal(unknown.headers.get('content-type'), 'application/octet-stream');
  assert.equal(unknown.headers.get('cache-control'), 'no-cache');

  // 6) 鉴权仍然先于任何字节：拒绝时不得读索引，返回鉴权给出的响应。
  refusal = new Response(null, { status: 401 });
  const readsBefore = reads.length;
  assert.equal((await call(after, '/')).status, 401);
  assert.equal(reads.length, readsBefore, '未授权的索引不得读文件');
} finally {
  fs.rmSync(dist, { recursive: true, force: true });
}
console.log('frontend static cache: ok');
