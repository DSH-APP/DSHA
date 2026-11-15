/* 启动观察器：只读插件元数据，观察真实模块加载，不重复执行插件。 */
'use strict';
// 观察器只用于当前 Web，工具子进程沿用启动前的 NODE_OPTIONS。
if ('DSHA_PRELOAD_PREVIOUS' in process.env) {
  if (process.env.DSHA_PRELOAD_PREVIOUS) process.env.NODE_OPTIONS = process.env.DSHA_PRELOAD_PREVIOUS;
  else delete process.env.NODE_OPTIONS;
  delete process.env.DSHA_PRELOAD_PREVIOUS;
}
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL, fileURLToPath } = require('node:url');
const Module = require('node:module');
const { createHash } = require('node:crypto');
const uiPhrases = {"检查启动配置和插件清单": "Checking startup configuration and plugin list", "加载 DSH 和已启用插件": "Loading DSH and enabled plugins", "配置文件超过 1 MiB": "Configuration file exceeds 1 MiB", "dsh.profile.bundles 必须是插件名称数组": "dsh.profile.bundles must be an array of plugin names", "插件清单含无效名称": "The plugin list contains an invalid name", "缺少 dsh.bundle.patch 声明或补丁文件": "Missing dsh.bundle.patch declaration or patch file", "未知版本": "Unknown version", "读取启动恢复记录": "Read startup recovery records", "启动配置快照": "Startup configuration snapshot", "配置检查：": "Configuration check: ", "正在加载插件：": "Loading plugin: ", "插件加载完成：": "Plugin loaded: ", "插件等待服务：": "Plugin waiting for services: ", "启动配置检查失败：": "Startup configuration check failed: ", "加载器观察不可用，保留原始异常输出：": "Loader monitoring unavailable; original errors retained: ", "找不到插件目录，请到插件管理检查安装：": "Plugin directory not found; check its installation: "};
function uiText(message) {
  if (process.env.DSHA_UI_LANGUAGE !== "en") return message;
  if (Object.hasOwn(uiPhrases, message)) return uiPhrases[message];
  for (const [zh, en] of Object.entries(uiPhrases)) if (zh.endsWith("：") && message.startsWith(zh)) return en + uiText(message.slice(zh.length));
  return message;
}
const prefix = '[DSHA_STARTUP] ';
function emit(type, plugin, message, extra = {}) {
  // 观察和翻译失败不能被当成插件初始化失败，也不能改变原 Loader 的行为。
  try { process.stdout.write(prefix + JSON.stringify({ type, plugin, message: uiText(message), ...extra }) + '\n'); }
  catch (_) { }
}
const validName = name => typeof name === 'string' && name.length <= 214 && /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/.test(name);
const home = process.env.DSH_HOME || '/root/.dsh';
const profile = process.env.DSHA_STARTUP_PROFILE || 'web';
const directory = path.join(home, 'profiles', profile);
const installation = process.env.DSHA_OBSERVER_INSTALL || '/usr/local/lib/node_modules/@deepseek-ai/dsh';
const plugins = [];
const moduleOwners = new Map();
const installedBundles = [];
const initAttempts = new WeakMap();
const changedGroups = new Set();
const moduleHash = name => createHash('sha256').update(name).digest('hex');
const requireAnchors = new Map();
function requireFrom(anchor) {
  // 固定 anchor 的 require 工厂不随 bundle 改变；仍逐次检查磁盘上的包与补丁。
  // 延迟创建，保留解析失败在当前 bundle/loader 诊断边界内的原有行为。
  if (!requireAnchors.has(anchor)) requireAnchors.set(anchor, Module.createRequire(anchor));
  return requireAnchors.get(anchor);
}
let yaml, yamlSchema;
// T116：sharp（附件图片）若先于 node-pty 加载，pty.node 会注册成 sharp 的导出，终端随后抛
// pty.fork is not a function。这里在插件加载前固定 node-pty；失败静默，不影响启动。
try {
  requireFrom(path.join(installation, 'package.json'))('node-pty');
} catch (_) { }
function bundleEntries(patches, patchFile, output) {
  // 只解析普通数据；!!js 保留为不透明标记，绝不执行表达式或插件。
  if (!yaml) {
    const anchor = requireFrom(path.join(installation, 'package.json'));
    const boot = anchor.resolve('@deepseek-ai/dsh-app-boot');
    yaml = requireFrom(boot)('js-yaml');
  }
  if (!yamlSchema) yamlSchema = yaml.JSON_SCHEMA.extend(new yaml.Type('tag:yaml.org,2002:js', {
    kind: 'scalar', construct: () => Object.freeze({ expression: true }),
  }));
  const parsed = yaml.load(patches, { schema: yamlSchema });
  if (!Array.isArray(parsed)) throw new Error('插件补丁不是数组');
  const visit = (rows, parentId = '', depth = 0) => {
    if (!Array.isArray(rows) || depth > 32) throw new Error('插件入口结构无法确认');
    for (const row of rows) {
      if (!row || typeof row !== 'object' || Array.isArray(row) || output.entries.length >= 2048)
        throw new Error('插件入口结构无法确认');
      if (typeof row.id !== 'string' || !row.id || row.id.length > 256
          || typeof row.name !== 'string' || !row.name || row.name.length > 4096) {
        output.ambiguous = true; continue;
      }
      const name = path.isAbsolute(row.name) || row.name.startsWith('./') || row.name.startsWith('../')
        ? pathToFileURL(path.resolve(path.dirname(patchFile), row.name)).href : row.name;
      const group = row.group === true || name === 'cordis:group' || name === '@deepseek-ai/cordis-plugin-group';
      output.entries.push({ id: row.id, moduleHash: moduleHash(name), parentId, group });
      // include 的子树不能由该入口 active 推定；未知 group/config 也不做乐观归属。
      if (name === 'cordis:include' || name === '@deepseek-ai/cordis-plugin-include'
          || (row.group != null && typeof row.group !== 'boolean')) output.ambiguous = true;
      if (group) visit(row.config, row.id, depth + 1);
    }
  };
  for (const patch of parsed) {
    if (!patch || typeof patch !== 'object' || Array.isArray(patch)) throw new Error('插件补丁结构无法确认');
    if (patch.insert != null) {
      if (patch.id != null && (typeof patch.id !== 'string' || !patch.id)) output.ambiguous = true;
      else visit(patch.insert, patch.id || '');
    } else if (typeof patch.id === 'string' && ('config' in patch || 'group' in patch)) {
      changedGroups.add(patch.id);
    }
  }
}
function readJson(file) {
  if (fs.statSync(file).size > 1024 * 1024) throw new Error('配置文件超过 1 MiB');
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}
function locate(name) {
  // 与锁定 dsh-app-boot.resolveBundleDir 的两个 anchor 及 Node 查找顺序一致。
  // 不能只列几个固定目录：全局安装、hoisted 依赖和 pnpm 链接都可能合法存在。
  for (const anchor of [path.join(installation, 'package.json'), path.join(directory, 'package.json')]) {
    for (const root of requireFrom(anchor).resolve.paths(name) || []) {
      const candidate = path.join(root, name);
      if (fs.existsSync(path.join(candidate, 'package.json'))) return candidate;
    }
  }
  throw new Error('找不到插件目录，请到插件管理检查安装：' + name);
}
const EXACT_VERSION = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/;

/**
 * 与原生 PluginIdentity、插件管理器的 DSHA_AUTO_REVIEW_ALIAS_V2 一致：只有这组历史别名共用
 * 一张可见卡片。原生列表展示的是官方包版本，运行证据必须报告补丁实际加载的官方版本；其它
 * 包装（如 dsh-computer-use-android）在列表里仍以包装自己的版本出现，不能替换。
 * 只在补丁插入锁定模块、且实际安装版本等于 peerDependencies 锁定时替换；解析不到或版本不
 * 一致就保留包装版本，宁可未确认也不把别的版本报成已加载。
 */
const ALIASED_EVIDENCE = new Map([
  ['dsh-auto-review', '@deepseek-ai/dsh-experimental-auto-review'],
]);

function effectiveVersion(bundle, pkg, inserted, fallback) {
  const canonical = ALIASED_EVIDENCE.get(bundle);
  if (!canonical || !Array.isArray(inserted) || !inserted.includes(canonical)) return fallback;
  const peers = pkg && typeof pkg.peerDependencies === 'object' && pkg.peerDependencies ? pkg.peerDependencies : null;
  const pin = peers ? peers[canonical] : undefined;
  if (typeof pin !== 'string' || !EXACT_VERSION.test(pin)) return fallback;
  try {
    const root = fs.realpathSync(locate(canonical));
    return readJson(path.join(root, 'package.json')).version === pin ? pin : fallback;
  } catch { return fallback; }
}
function owner(text) {
  text = text.replaceAll('\\', '/');
  const matches = plugins.filter(p => text.includes(p.url + '/') || text.includes(p.directory.replaceAll('\\', '/') + '/')
    || text.includes('"' + p.name + '"') || text.includes("'" + p.name + "'"));
  return matches.sort((a,b) => b.directory.length - a.directory.length)[0]?.name || '';
}
try {
  emit('stage', '', '检查启动配置和插件清单');
  const doc = readJson(path.join(directory, 'package.json'));
  const bundles = doc.dsh?.profile?.bundles;
  if (!Array.isArray(bundles)) throw new Error('dsh.profile.bundles 必须是插件名称数组');
  for (const name of bundles.slice(0, 500)) {
    // Upstream skips an unresolvable optional bundle. This metadata observer must not
    // turn that warning into a fatal result before the actual loader can decide.
    if (!validName(name)) { emit('issue', '', '插件清单含无效名称', { fatal: false }); continue; }
    const evidence = { name, version: '', profile, entries: [], ambiguous: false };
    installedBundles.push(evidence);
    try {
      const resolved = locate(name), root = fs.realpathSync(resolved);
      const pkg = readJson(path.join(root, 'package.json'));
      evidence.version = typeof pkg.version === 'string' && pkg.version.length <= 128 ? pkg.version : '';
      const info = { name, directory: root, url: pathToFileURL(root).href };
      plugins.push(info);
      const patch = pkg.dsh?.bundle?.patch;
      // 0.1.7 支持有序补丁数组；每一个入口仍须存在于该包内部。
      const patches = typeof patch === 'string' ? [patch] : Array.isArray(patch) ? patch : [];
      if (!patches.length || patches.some(item => {
        if (typeof item !== 'string' || !item || path.isAbsolute(item)) return true;
        const target = path.resolve(root, item);
        return !target.startsWith(root + path.sep) || !fs.existsSync(target)
          || !fs.realpathSync(target).startsWith(root + path.sep) || !fs.statSync(target).isFile();
      })) throw new Error('缺少 dsh.bundle.patch 声明或补丁文件');
      const inserted = [];
      for (const patch of patches) {
        // 只读取 id/module 的普通标量，不执行 YAML 的 !!js 用户表达式。
        const patchFile = path.resolve(root, patch);
        if (fs.existsSync(patchFile) && fs.statSync(patchFile).size <= 1024 * 1024) {
          const source = fs.readFileSync(patchFile, 'utf8');
          try { bundleEntries(source, path.resolve(resolved, patch), evidence); }
          catch (_) { evidence.ambiguous = true; }
          const ids = [...source.matchAll(/^\s*(?:-\s*)?(?:id|name|module):\s*["']?([@A-Za-z0-9_./:-]+)["']?\s*$/gm)].map(m => m[1]);
          inserted.push(...ids);
          for (const id of ids) moduleOwners.set(id, moduleOwners.has(id) && moduleOwners.get(id) !== name ? '' : name);
          emit('catalog', name, '', { ids: ids.slice(0, 100), path: root });
        } else evidence.ambiguous = true;
      }
      evidence.version = effectiveVersion(name, pkg, inserted, evidence.version);
      emit('plugin', name, '配置检查：' + name + ' @ ' + (evidence.version || uiText('未知版本')), { path: root });
    } catch (error) { evidence.ambiguous = true; emit('issue', name, error.message, { fatal: false }); }
  }
  emit('stage', '', '加载 DSH 和已启用插件');
} catch (error) { emit('issue', '', '启动配置检查失败：' + error.message); }

// 心跳仅消费这些当前进程元数据；路径、源码、配置和错误正文均不导出。
// 版本固定在启动时，磁盘升级不能把仍运行的旧 fiber 冒认为新部署版本。
for (const bundle of installedBundles) {
  if (bundle.entries.some(entry => entry.group && changedGroups.has(entry.id))) bundle.ambiguous = true;
  bundle.entries.forEach(Object.freeze);
  Object.freeze(bundle.entries); Object.freeze(bundle);
}
Object.defineProperty(globalThis, Symbol.for('dsha.installed-plugin-evidence'), { value: Object.freeze({
  generation: process.env.DSHA_WEB_GENERATION || '', instance: process.env.DSH_TASK_INSTANCE || '',
  profile, bundles: Object.freeze(installedBundles),
  initState(entry) {
    const attempt = initAttempts.get(entry);
    return attempt && attempt.options === entry.options && attempt.name === entry.options?.name
      && attempt.disabled === entry.options?.disabled ? attempt.state : '';
  },
}) });

function entryOwner(entry) { return moduleOwners.get(entry?.options?.name) || moduleOwners.get(entry?.options?.id)
  || plugins.find(p => p.name === entry?.options?.name)?.name || ''; }
function errorDetail(error) {
  let detail = String(error?.stack || error);
  if (error?.cause && error.cause !== error) detail += (process.env.DSHA_UI_LANGUAGE === 'en' ? '\nCause: ' : '\n原因：') + String(error.cause.stack || error.cause);
  return detail;
}
// 使用已锁定 Cordis 的实际加载边界；同一模块缓存只加载一次，不拦截每一次 Node 依赖解析。
try {
  const loaderModule = requireFrom(path.join(installation, 'package.json')).resolve('@deepseek-ai/cordis-plugin-loader');
  const { Entry } = require(loaderModule);
  const original = Entry.prototype.init;
  const required = new Set(['agent-loop','webserver','web-runtime','modules','connection','headless-runner','acp','sdk-jsonrpc-server']);
  Entry.prototype.init = function () {
    const attempt = { options: this.options, name: this.options?.name, disabled: this.options?.disabled,
      fiber: this.fiber, state: 'loading' };
    initAttempts.set(this, attempt);
    const name = entryOwner(this), id = this.options?.name || this.options?.id || '';
    const show = name && !['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app'].includes(name)
      || ['web-startup', 'webserver', 'modules'].includes(this.options?.id);
    if (show) emit('loading', name, '正在加载插件：' + (name || id));
    // alpha.1 允许可选插件失败；只有上游声明的关键入口可提前触发原生恢复。
    const failed = error => { attempt.state = 'failed'; emit('issue', name || owner(errorDetail(error)), id + '\n' + errorDetail(error),
      { fatal: required.has(this.options?.id) }); throw error; };
    try {
      return original.apply(this, arguments).then(value => {
        attempt.state = !this.fiber || (this.fiber === attempt.fiber && this.fiber.state === 4) ? 'failed' : 'settled';
        if (show) {
          const fiber = this.fiber;
          const waiting = fiber?.state === 0 ? Object.keys(fiber.inject || {}).filter(key => !fiber._store?.[key]) : [];
          emit('loaded', name, waiting.length ? '插件等待服务：' + (name || id) + ' → ' + waiting.join(', ')
            : '插件加载完成：' + (name || id));
        }
        return value;
      }, failed);
    } catch (error) { return failed(error); }
  };
} catch (error) {
  emit('note', '', '加载器观察不可用，保留原始异常输出：' + error.message);
}
process.on('uncaughtExceptionMonitor', error => {
  const detail = String(error.stack || error);
  emit('issue', owner(detail), detail);
});
