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
const prefix = '[DSHA_STARTUP] ';
function emit(type, plugin, message, extra = {}) {
  process.stdout.write(prefix + JSON.stringify({ type, plugin, message, ...extra }) + '\n');
}
const validName = name => typeof name === 'string' && name.length <= 214 && /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/.test(name);
const home = process.env.DSH_HOME || '/root/.dsh';
const profile = process.env.DSHA_STARTUP_PROFILE || 'web';
const directory = path.join(home, 'profiles', profile);
const installation = process.env.DSHA_OBSERVER_INSTALL || '/usr/local/lib/node_modules/@deepseek-ai/dsh';
const plugins = [];
const moduleOwners = new Map();
function readJson(file) {
  if (fs.statSync(file).size > 1024 * 1024) throw new Error('配置文件超过 1 MiB');
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}
function locate(name, roots) {
  for (const root of roots) {
    const candidate = path.join(root, name);
    if (fs.existsSync(path.join(candidate, 'package.json'))) return fs.realpathSync(candidate);
  }
  throw new Error('找不到插件目录，请到插件管理检查安装：' + name);
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
    if (!validName(name)) { emit('issue', '', '插件清单含无效名称'); continue; }
    try {
      const root = locate(name, [path.join(installation, 'node_modules'), path.join(directory, 'node_modules'),
        path.join(home, 'profiles/node_modules'), path.join(home, 'node_modules')]);
      const pkg = readJson(path.join(root, 'package.json'));
      const info = { name, directory: root, url: pathToFileURL(root).href };
      plugins.push(info);
      const patch = pkg.dsh?.bundle?.patch;
      if (typeof patch !== 'string' || !fs.existsSync(path.resolve(root, patch)))
        emit('issue', name, '缺少 dsh.bundle.patch 声明或补丁文件');
      emit('plugin', name, '配置检查：' + name + ' @ ' + (pkg.version || '未知版本'), { path: root });
      if (typeof patch === 'string') {
        // 只读取 id/module 的普通标量，不执行 YAML 的 !!js 用户表达式。
        const patchFile = path.resolve(root, patch);
        if (fs.existsSync(patchFile) && fs.statSync(patchFile).size <= 1024 * 1024) {
          const source = fs.readFileSync(patchFile, 'utf8');
          const ids = [...source.matchAll(/^\s*(?:-\s*)?(?:id|name|module):\s*["']?([@A-Za-z0-9_./:-]+)["']?\s*$/gm)].map(m => m[1]);
          for (const id of ids) moduleOwners.set(id, moduleOwners.has(id) && moduleOwners.get(id) !== name ? '' : name);
          emit('catalog', name, '', { ids: ids.slice(0, 100), path: root });
        }
      }
    } catch (error) { emit('issue', name, error.message); }
  }
  emit('stage', '', '加载 DSH 和已启用插件');
} catch (error) { emit('issue', '', '启动配置检查失败：' + error.message); }

function entryOwner(entry) { return moduleOwners.get(entry?.options?.name) || moduleOwners.get(entry?.options?.id)
  || plugins.find(p => p.name === entry?.options?.name)?.name || ''; }
function errorDetail(error) {
  let detail = String(error?.stack || error);
  if (error?.cause && error.cause !== error) detail += '\n原因：' + String(error.cause.stack || error.cause);
  return detail;
}
// 使用已锁定 Cordis 的实际加载边界；同一模块缓存只加载一次，不拦截每一次 Node 依赖解析。
try {
  const { Entry } = require(path.join(installation, 'node_modules/@deepseek-ai/cordis-plugin-loader/lib/index.js'));
  const original = Entry.prototype.init;
  Entry.prototype.init = function () {
    const name = entryOwner(this), id = this.options?.name || this.options?.id || '';
    const show = name && !['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app'].includes(name)
      || ['web-startup', 'webserver', 'modules'].includes(this.options?.id);
    if (show) emit('loading', name, '正在加载插件：' + (name || id));
    const failed = error => { emit('issue', name || owner(errorDetail(error)), id + '\n' + errorDetail(error)); throw error; };
    try {
      return original.apply(this, arguments).then(value => {
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
