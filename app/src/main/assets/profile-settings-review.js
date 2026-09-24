import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { join } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { parseDocument, visit } from 'yaml';
import Schema from '@deepseek-ai/schemastery';

export const inject = ['settings', 'profileContext'];
const record = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const forbidden = k => ['__proto__', 'prototype', 'constructor', '__jsExpr'].includes(k);
function literal(value, schema, path, warnings) {
  if (schema?.meta?.role === 'secret') { warnings.push(`SECRET_RETAINED:${path}`); return undefined; }
  if (record(value)) {
    if (Object.keys(value).some(forbidden)) throw Error('EXECUTABLE_CONFIGURATION');
    const output = {};
    for (const [key, child] of Object.entries(value)) {
      const field = schema?.dict?.[key] ?? (schema?.type === 'dict' ? schema.inner : undefined);
      if (!field) { warnings.push(`FIELD_REQUIRES_REVIEW:${path}/${key}`); continue; }
      const next = literal(child, field, `${path}/${key}`, warnings);
      if (next !== undefined) output[key] = next;
    }
    return output;
  }
  if (Array.isArray(value)) return value.map((v, i) => literal(v, schema?.inner, `${path}/${i}`, warnings));
  if (typeof value === 'string' && /\$\{|\{\{/.test(value)) throw Error('EXPRESSION_REQUIRES_REVIEW');
  if (value !== null && !['string', 'boolean', 'number'].includes(typeof value)) throw Error('CONFIGURATION_TYPE');
  return value;
}
export function parseIncoming(text) {
  const doc = parseDocument(text, { uniqueKeys: true, maxAliasCount: 0 });
  if (doc.errors.length || doc.warnings.length) throw Error('YAML_REQUIRES_REVIEW');
  visit(doc, { Alias() { throw Error('YAML_ALIAS_REQUIRES_REVIEW'); }, Node(_key, node) {
    if (node.tag && !['tag:yaml.org,2002:str','tag:yaml.org,2002:map','tag:yaml.org,2002:seq','tag:yaml.org,2002:int','tag:yaml.org,2002:float','tag:yaml.org,2002:bool','tag:yaml.org,2002:null'].includes(node.tag)) throw Error('YAML_TAG_REQUIRES_REVIEW');
  }});
  const rows = doc.toJS({ maxAliasCount: 0 });
  if (!Array.isArray(rows) || rows.length > 2048) throw Error('PROFILE_PATCH_FORMAT');
  return rows;
}
function display(value) { const text = JSON.stringify(value ?? null); return text.length > 400 ? text.slice(0, 400) + '…' : text; }
function validate(descriptor, value) {
  const result = descriptor.schema?.['~standard']?.validate(value);
  if (result?.then) throw Error('ASYNC_SCHEMA_REQUIRES_REVIEW');
  if (result?.issues) throw Error('SCHEMA_REJECTED');
  return result?.value ?? value;
}
function retainSecrets(base, current, schema) {
  if (schema?.meta?.role === 'secret') return current;
  if (Array.isArray(current)) return hasSecret(schema?.inner) ? current : base;
  if (!record(current)) return base;
  const next = record(base) ? structuredClone(base) : {};
  for (const [key, value] of Object.entries(current)) {
    const field = schema?.dict?.[key] ?? (schema?.type === 'dict' ? schema.inner : undefined);
    const retained = retainSecrets(next[key], value, field);
    if (retained !== undefined && (field?.meta?.role === 'secret' || record(retained) && Object.keys(retained).length)) next[key] = retained;
  }
  return Object.keys(next).length || base !== undefined ? next : undefined;
}
function hasSecret(schema) {
  return Boolean(schema && (schema.meta?.role === 'secret' || hasSecret(schema.inner) || Object.values(schema.dict ?? {}).some(hasSecret) || (schema.list ?? []).some(hasSecret)));
}
function resetOps(schema, value, path = []) {
  if (schema?.meta?.role === 'secret') return [];
  if (schema?.type === 'object' && record(value)) {
    const ops = [];
    for (const [key, child] of Object.entries(schema.dict ?? {})) if (Object.hasOwn(value, key)) ops.push(...resetOps(child, value[key], [...path, key]));
    return ops;
  }
  if (schema?.type === 'dict' || schema?.type === 'array') return hasSecret(schema) ? [] : [{ op: 'unset', path }];
  return path.length ? [{ op: 'unset', path }] : [];
}
/** settings 是当前真正的 Cordis 服务；所有验证/写入均限定在宿主准备的隔离 profile。 */
export async function review(settings, request, incoming) {
  const describe = () => settings.describe({ redactSecrets: true }).map(row => ({ ...row, schema: row.schema?.refs ? new Schema(row.schema) : row.schema }));
  const warnings = [], items = [], described = describe();
  const descriptors = new Map(described.map(row => [row.ns, row]));
  if (request.mode === 'verify') {
    for (const expected of request.expected ?? []) {
      const actual = descriptors.get(expected.ns)?.value?.[expected.field] ?? null;
      if (!isDeepStrictEqual(actual, expected.actual)) throw Error('SETTINGS_READBACK_MISMATCH');
    }
    return { version: 1, items: request.expected ?? [], warnings: [], status: 'VERIFIED' };
  }
  const desired = new Map();
  if (request.mode === 'reset') {
    for (const row of described) for (const key of Object.keys(row.user ?? {})) {
      const field = row.schema?.dict?.[key];
      if (!field || field.meta?.role === 'secret') continue;
      desired.set(`${row.ns}/${key}`, { ns: row.ns, key, reset: true, before: row.value?.[key] });
    }
  } else {
    for (const row of parseIncoming(incoming)) {
      if (!record(row) || typeof row.id !== 'string' || !record(row.config) || Object.keys(row).some(k => !['id','name','config'].includes(k))) { warnings.push('CODE_OR_COMPOSITION_REQUIRES_REVIEW'); continue; }
      const descriptor = descriptors.get(row.id);
      if (!descriptor) { warnings.push(`NAMESPACE_UNAVAILABLE:${row.id}`); continue; }
      for (const [key, value] of Object.entries(row.config)) {
        if (forbidden(key) || !Object.hasOwn(descriptor.schema?.dict ?? {}, key)) { warnings.push(`FIELD_REQUIRES_REVIEW:${row.id}/${key}`); continue; }
        try {
          const safe = literal(value, descriptor.schema.dict[key], `${row.id}/${key}`, warnings);
          if (safe !== undefined) desired.set(`${row.id}/${key}`, { ns: row.id, key, value: safe, before: descriptor.value?.[key] });
        } catch { warnings.push(`EXECUTABLE_FIELD_REQUIRES_REVIEW:${row.id}/${key}`); }
      }
    }
  }
  if (desired.size > 2048) throw Error('SETTINGS_ITEM_LIMIT');
  const selected = request.selected ? new Set(request.selected) : null;
  for (const [id, change] of desired) {
    if (selected && !selected.has(id)) continue;
    const descriptor = describe().find(row => row.ns === change.ns);
    if (!descriptor) throw Error('SETTINGS_NAMESPACE_CHANGED');
    try {
      if (change.reset) {
        const full = settings.describe().find(row => row.ns === change.ns);
        const next = retainSecrets(full.base?.[change.key], full.value?.[change.key], descriptor.schema.dict[change.key]);
        const operations = resetOps(descriptor.schema?.dict?.[change.key], descriptor.value?.[change.key], [change.key]);
        const candidate = structuredClone(descriptor.value ?? {}); if (next === undefined) delete candidate[change.key]; else candidate[change.key] = next;
        if (request.mode === 'preview') { validate(descriptor, candidate); items.push({ id, ns: change.ns, field: change.key, before: display(change.before), after: display(next), actual: next ?? null }); continue; }
        if (operations.length) await settings.mutate(change.ns, operations, descriptor.revision);
      } else {
        const candidate = structuredClone(descriptor.value ?? {}); candidate[change.key] = change.value;
        if (request.mode === 'preview') { const actual = validate(descriptor, candidate)[change.key]; items.push({ id, ns: change.ns, field: change.key, before: display(change.before), after: display(actual), actual }); continue; }
        await settings.update(change.ns, { [change.key]: change.value }, descriptor.revision);
      }
      const after = settings.describe({ redactSecrets: true }).find(row => row.ns === change.ns);
      if (!after) throw Error('SETTINGS_READBACK_MISSING');
      const actual = after.value?.[change.key];
      items.push({ id, ns: change.ns, field: change.key, before: display(change.before), after: display(actual), actual: actual ?? null });
    } catch (error) {
      if (selected || request.mode !== 'preview') throw Error(`SETTINGS_VALIDATION_FAILED:${change.ns}/${change.key}`);
      warnings.push(`SCHEMA_REJECTED:${change.ns}/${change.key}`);
    }
  }
  if (selected && items.length !== selected.size) throw Error('SETTINGS_SELECTION_CHANGED');
  return { version: 1, items, warnings: [...new Set(warnings)], status: warnings.length ? 'PARTIAL_REVIEW' : 'VERIFIED' };
}
export function apply(ctx) {
  const directory = process.env.DSHA_PROFILE_SETTINGS_DIRECTORY;
  const nonce = process.env.DSHA_PROFILE_SETTINGS_NONCE;
  if (!directory || !/^[a-f0-9]{32}$/.test(nonce ?? '')) throw Error('SETTINGS_TRIAL_ID');
  // 不在插件初始化期间 await loader 自己，避免 await() 相互等待。
  ctx.root.loader.await().then(async () => {
    let result;
    try {
      const request = JSON.parse(readFileSync(join(directory, 'request.json'), 'utf8'));
      if (request.nonce !== nonce) throw Error('SETTINGS_TRIAL_ID');
      result = await review(ctx.settings, request, readFileSync(join(directory, 'incoming.yml'), 'utf8'));
    } catch (error) { result = { version: 1, status: 'FAILED', error: String(error.message).replace(/[^A-Za-z0-9_:/.-]/g, '').slice(0, 256) }; }
    result.nonce = nonce;
    writeFileSync(join(directory, 'result.tmp'), JSON.stringify(result), { mode: 0o600 });
    renameSync(join(directory, 'result.tmp'), join(directory, 'result.json'));
  }).catch(() => {});
}
