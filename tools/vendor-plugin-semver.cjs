// 从 npm 官方 semver 发布包生成离线适配器；用法：node tools/vendor-plugin-semver.cjs /path/to/semver
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(process.argv[2]);
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
if (pkg.name !== 'semver' || pkg.version !== '7.8.1') throw Error('需要官方 semver 7.8.1');
const modules = new Map();
function collect(id) {
  if (modules.has(id)) return;
  const code = fs.readFileSync(path.join(root, id), 'utf8').replace(/\r\n/g, '\n');
  modules.set(id, '');
  const linked = code.replace(/require\(['"]([^'"]+)['"]\)/g, (_, name) => {
    if (!name.startsWith('.')) throw Error('不支持外部依赖：' + name);
    const target = path.posix.normalize(path.posix.join(path.posix.dirname(id), name)) + (name.endsWith('.js') ? '' : '.js');
    if (target.startsWith('../')) throw Error('依赖越界');
    collect(target);
    return `load(${JSON.stringify(target)})`;
  });
  modules.set(id, linked);
}
collect('index.js');
const license = fs.readFileSync(path.join(root, 'LICENSE'), 'utf8').replace(/\r\n/g, '\n');
const bundle = `// 由 tools/vendor-plugin-semver.cjs 生成。npm/node-semver ${pkg.version}，未修改解析语义。\n/*\n${license}\n*/\n` +
  'const modules = {\n' + [...modules].map(([id, code]) => `${JSON.stringify(id)}: (module, exports, load) => {\n${code}\n}`).join(',\n') + '\n};\n' +
  `const cache = {};
function load(id) {
  if (!cache[id]) { const m = cache[id] = { exports: {} }; modules[id](m, m.exports, load); }
  return cache[id].exports;
}
const semver = load('index.js');
module.exports = semver;
if (require.main === module) {
  const request = JSON.parse(require('node:fs').readFileSync(0, 'utf8'));
  let result = null;
  try {
    if (request.action === 'compare') result = semver.valid(request.left) && semver.valid(request.right)
      ? semver.compare(request.left, request.right) : null;
    else if (semver.valid(request.version) && semver.validRange(request.range) !== null)
      result = semver.satisfies(request.version, request.range);
  } catch (_) { }
  process.stdout.write(JSON.stringify(result));
}
`;
fs.writeFileSync(path.resolve(__dirname, '../app/src/main/assets/plugin-semver.cjs'), bundle);
console.log(`semver ${pkg.version}: ${modules.size} modules, ${Buffer.byteLength(bundle)} bytes`);
