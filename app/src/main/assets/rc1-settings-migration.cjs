'use strict';
// 仅由锁定 settings 模块调用；回执在宿主私有绑定中，归档内同名 JSON 不具权威。
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { isDeepStrictEqual } = require('node:util');
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
async function read(file) { return JSON.parse(await fs.readFile(file, 'utf8')); }
async function atomic(file, value) {
  const temp = file + '.' + crypto.randomUUID() + '.tmp';
  await fs.writeFile(temp, JSON.stringify(value, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  await fs.rename(temp, file);
}
function contains(actual, wanted) {
  if (wanted && typeof wanted === 'object' && !Array.isArray(wanted))
    return actual && typeof actual === 'object' && Object.keys(wanted).every(k => Object.hasOwn(actual, k) && contains(actual[k], wanted[k]));
  return isDeepStrictEqual(actual, wanted);
}
const clone = value => JSON.parse(JSON.stringify(value));
// Schema metadata is the only authority for secret fields. Unknown fields are
// retained for comparison but never copied from a credential-shaped key by name.
function redactBySchema(value, schema) {
  if (schema?.meta?.role === 'secret') return undefined;
  if (Array.isArray(value)) return value.map((item) => redactBySchema(item, schema?.inner)).filter(item => item !== undefined);
  if (value && typeof value === 'object') {
    const out = {};
    for (const [key, item] of Object.entries(value)) {
      const child = schema?.dict?.[key];
      if (child === undefined && /(?:api[_-]?key|token|password|secret|credential)/i.test(key)) continue;
      const safe = redactBySchema(item, child);
      if (safe !== undefined) out[key] = safe;
    }
    return out;
  }
  return value;
}
const reason = error => /No configurable plugin entry/.test(String(error)) ? 'SECTION_UNAVAILABLE' :
  /volatile|schema|invalid/i.test(String(error)) ? 'SECTION_SCHEMA_REJECTED' : 'SECTION_WRITE_UNVERIFIED';

async function migrate(settings, parse, aliases, options = {}) {
  const home = settings.ownerContext.profileContext.home;
  const state = options.stateRoot || '/run/dsha-rc1-state';
  let current;
  try { current = await read(path.join(state, 'current.json')); }
  catch (error) {
    // 全新的隔离试运行仅有空文档，不作任何写入。带旧值时必须先建立保护事务。
    let empty;
    try { empty = parse(await fs.readFile(path.join(home, 'settings.yaml'), 'utf8')); }
    catch (missing) { if (missing.code === 'ENOENT') return; throw missing; }
    if (empty && Object.keys(empty).length === 0) return;
    throw new Error('DSHA_MIGRATION_PROTECTION_REQUIRED');
  }
  if (current.version !== 2 || current.dshHome !== home || !/^[a-f0-9-]{36}$/.test(current.generation)) {
    if (process.env.DSHA_RUNTIME_TRIAL_NONCE) return; // 隔离试运行不得操作真实用户迁移记录。
    throw new Error('DSHA_MIGRATION_GENERATION_MISMATCH');
  }
  const folder = path.join(state, 'generations', current.generation);
  const prepared = await read(path.join(folder, 'prepare.json'));
  if (prepared.status !== 'prepared' || !prepared.protectionComplete) throw new Error('DSHA_MIGRATION_PROTECTION_REQUIRED');
  const sources = prepared.sources.filter(row => row.kind === 'settings');
  const source = sources.find(row => row.path === 'settings.yaml') || sources.find(row => row.path === 'settings.yaml.imported');
  if (!source) return;
  // 对已保护的独立正文验证；不按归档或外部路径加载可执行内容。
  if (!/^snapshots\/[0-9]+$/.test(source.snapshot)) throw new Error('DSHA_MIGRATION_SNAPSHOT_PATH');
  const bytes = await fs.readFile(path.join(folder, source.snapshot));
  if (hash(bytes) !== source.sha256) throw new Error('DSHA_MIGRATION_SNAPSHOT_CHANGED');
  const activePath = path.join(home, 'settings.yaml');
  const importedPath = activePath + '.imported';
  let sourcePath;
  for (const candidate of [activePath, importedPath]) {
    try { if (hash(await fs.readFile(candidate)) === source.sha256) { sourcePath = candidate; break; } }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  if (!sourcePath) throw new Error('DSHA_MIGRATION_SOURCE_CHANGED');
  const sections = parse(bytes.toString('utf8')) ?? {};
  if (!sections || typeof sections !== 'object' || Array.isArray(sections)) throw new Error('DSHA_MIGRATION_SETTINGS_FORMAT');
  const ledgerFile = path.join(folder, 'settings-result.json');
  let result;
  try { result = await read(ledgerFile); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (result && (result.generation !== current.generation || result.sourceSha256 !== source.sha256)) throw new Error('DSHA_MIGRATION_RESULT_IDENTITY');
  result ||= { version: 2, generation: current.generation, sourceSha256: source.sha256, targetVersion: '0.1.7-rc.2', sections: {} };
  result.startupId = current.startupId;
  for (const [name, values] of Object.entries(sections)) {
    const ns = Object.hasOwn(aliases, name) ? aliases[name] : name;
    const descriptor = settings.describe().find(row => row.ns === ns);
    let section = Object.hasOwn(result.sections, name) ? result.sections[name] : undefined;
    if (section?.status === 'verified') continue; // 已完成节从不在普通重启覆盖后来用户修改。
    if (!section) {
      section = { ns, status: 'pending' };
      Object.defineProperty(result.sections, name, { value: section, enumerable: true, configurable: true, writable: true });
    }
    if (!descriptor) { section.reason = 'SECTION_UNAVAILABLE'; await atomic(ledgerFile, result); continue; }
    const safeValues = redactBySchema(values, descriptor.schema);
    section.source = clone(safeValues);
    const safeCurrent = redactBySchema(descriptor.value, descriptor.schema);
    if (Object.hasOwn(section, 'before') && !isDeepStrictEqual(section.before, safeCurrent)) {
      if (contains(safeCurrent, safeValues)) { section.status = 'verified'; section.verified = clone(safeCurrent); }
      else { section.status = 'conflict'; section.reason = 'TARGET_CHANGED_REVIEW_REQUIRED'; }
      await atomic(ledgerFile, result); continue;
    }
    section.before = clone(redactBySchema(descriptor.value, descriptor.schema)); section.status = 'writing';
    await atomic(ledgerFile, result); // 先记预期值，掉电后不盲目重放。
    try {
      await settings.update(ns, values, descriptor.revision);
      const after = settings.describe().find(row => row.ns === ns);
      const safeAfter = after && redactBySchema(after.value, after.schema);
      if (!after || !contains(safeAfter, safeValues)) throw new Error('SETTINGS_READBACK_MISMATCH');
      section.status = 'verified'; section.verified = clone(safeAfter); delete section.reason;
    } catch (error) { section.status = 'pending'; section.reason = reason(error); }
    await atomic(ledgerFile, result);
  }
  result.status = Object.values(result.sections).every(row => row.status === 'verified') ? 'verified' : 'pending';
  result.verifiedAt = Date.now();
  await atomic(ledgerFile, result);
  // 不再把整份 settings 放回给上游。合法 rename 仅在正文未变、目标未冲突时完成。
  const active = activePath, imported = importedPath;
  try {
    if (hash(await fs.readFile(active)) === source.sha256) {
      try { if (hash(await fs.readFile(imported)) !== source.sha256) return; }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
      await fs.rename(active, imported);
    }
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  settings.ownerContext.logger.info('DSHA rc1 settings migration: %s', result.status);
  return result;
}
module.exports = { migrate, contains };
