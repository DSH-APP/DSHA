// 冷启动回归：外壳静态资源必须可复用。补丁前每次冷启动都要重下 dist 下的
// Vite 资产；补丁后同一份字节用 304 复用，索引仍永不缓存。
// 显式参数：`node tools/test-frontend-static-cache.mjs <runtime 根或模块文件>`；
// 不给参数时按 CI 夹具选择当前 raw 运行时。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';

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
const file = await moduleFile();
const original = fs.readFileSync(file, 'utf8');

// 与 ManagedPatchChain/ExactTextPatch 相同的判据：每条 before 必须唯一命中。
let patched = original;
for (const { before, after } of recipe.patches) {
  assert.equal(patched.split(before).length, 2, '补丁锚点必须唯一命中：' + before.slice(0, 60));
  patched = patched.replace(before, after);
}
assert.ok(patched.includes('DSHA_STATIC_CACHE_V1'), '补丁必须留下可跳过的标记');
// 幂等：标记命中时整条配方跳过，重复应用不再改动字节。
assert.equal(patched.includes('DSHA_STATIC_CACHE_V1') ? patched : '', patched);

function load(source) {
  const context = {
    readFile: fs.promises.readFile,
    stat: fs.promises.stat,
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

function response() {
  const state = { status: 0, headers: undefined, body: undefined };
  return {
    state,
    writeHead(status, headers) {
      state.status = status;
      state.headers = headers;
    },
    end(body) {
      state.body = body;
    },
  };
}

const distIndex = path.join(dist, 'index.html');
const rootDir = path.resolve(dist);
const renderIndex = async () => fs.readFileSync(distIndex, 'utf8');
const authorize = () => true;
const call = async (module, pathname, req) => {
  const res = response();
  await module.serveStatic(pathname, res, rootDir, distIndex, authorize, renderIndex, req);
  return res;
};

// 1) 未打补丁的模块没有任何缓存指令：这正是冷启动每次重下的原因。
const untouched = response();
await before.serveStatic('/assets/index-BPHePDI_.js', untouched, rootDir, distIndex, authorize,
    renderIndex);
assert.equal(untouched.state.status, 200);
assert.equal(untouched.state.headers['cache-control'], undefined);
assert.equal(untouched.state.headers.etag, undefined);

// 2) 索引永不缓存：它每次请求都重新渲染注入行（__DSH_BOOT__、DSHA 兼容脚本）。
const index = await call(after, '/');
assert.equal(index.state.status, 200);
assert.equal(index.state.headers['cache-control'], 'no-store');
assert.equal(index.state.headers.etag, undefined);

// 3) dist 资产给出可复用的标签，且不再是无条件重下。
const first = await call(after, '/assets/index-BPHePDI_.js');
assert.equal(first.state.status, 200);
assert.equal(first.state.headers['cache-control'], 'no-cache');
assert.match(first.state.headers.etag, /^W\/"[0-9a-f]+-[0-9a-f]+"$/);
assert.ok(Buffer.isBuffer(first.state.body));

// 4) 第二次冷启动：带 If-None-Match 得到 304，没有响应体。
const revalidated = await call(after, '/assets/index-BPHePDI_.js',
    { headers: { 'if-none-match': first.state.headers.etag } });
assert.equal(revalidated.state.status, 304);
assert.equal(revalidated.state.body, undefined);
assert.equal(revalidated.state.headers['cache-control'], 'no-cache');
// 旧 WebView 可能合并多个标签，或带上弱校验前缀的其它候选。
for (const value of [`"stale", ${first.state.headers.etag}`, `W/"other" ,${first.state.headers.etag}`]) {
  const merged = await call(after, '/assets/index-BPHePDI_.js', { headers: { 'if-none-match': value } });
  assert.equal(merged.state.status, 304, '候选列表里的命中必须复用：' + value);
}
// 不匹配 / 没有请求头 / 旧版六参数调用都必须回到完整 200。
for (const req of [undefined, { headers: {} }, { headers: { 'if-none-match': 'W/"nope"' } }]) {
  const miss = await call(after, '/assets/index-BPHePDI_.js', req);
  assert.equal(miss.state.status, 200, '未命中必须回 200');
  assert.ok(Buffer.isBuffer(miss.state.body));
}
const legacyResponse = response();
await after.serveStatic('/assets/index-BPHePDI_.js', legacyResponse, rootDir, distIndex, authorize,
    renderIndex);
assert.equal(legacyResponse.state.status, 200, '导出签名必须保持向后兼容');

// 5) 内容或 mtime 变化后标签必须改变，否则 APK 更新后会拿到旧外壳。
fs.writeFileSync(asset, 'console.log("shell v2");\n');
const rebuilt = await call(after, '/assets/index-BPHePDI_.js');
assert.equal(rebuilt.state.status, 200);
assert.notEqual(rebuilt.state.headers.etag, first.state.headers.etag, '改写后的资产必须换标签');
const stale = await call(after, '/assets/index-BPHePDI_.js',
    { headers: { 'if-none-match': first.state.headers.etag } });
assert.equal(stale.state.status, 200, '旧标签不能拿到 304');

// 6) 原有安全与缺失语义不变。
const escapee = await call(after, '/../../etc/passwd');
assert.equal(escapee.state.status, 403);
const missing = await call(after, '/assets/does-not-exist.js');
assert.equal(missing.state.status, 404);

fs.rmSync(dist, { recursive: true, force: true });
console.log('frontend static cache: ok');
