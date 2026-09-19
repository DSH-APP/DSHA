#!/usr/bin/env python3
"""把锁定的 npm Linux arm64 安装树转为 dsh 离线覆盖层，不执行第三方脚本。"""
import argparse
import gzip
import hashlib
import json
import io
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import tarfile

PREFIX = 'usr/local/lib/node_modules/@deepseek-ai/dsh'
PACKAGE = '@deepseek-ai/dsh'
WORKFLOW_PTC = '@deepseek-ai/dsh-workflow-ptc'
LEGACY_WORKFLOW_PTC = '@deepseek-ai/dsh-workflow-worker-thread'
HOOKS = Path(__file__).resolve().parents[1] / 'app/src/main/assets/runtime-fs'
SESSION_HOOKS = HOOKS.parent / 'session-compat'
COMBO_HOOKS = HOOKS.parent / 'client-combo-cache'
COMBO_PATCH = HOOKS.parent / 'client-combo-patch.json'
COMBO_MODULE = '@deepseek-ai/dsh-client-modules/lib/index.js'
DEEPSEEK_MESSAGES_PATCH = HOOKS.parent / 'deepseek-messages-compat-patch.json'
DEEPSEEK_MESSAGES_MODULE = '@deepseek-ai/dsh-llm-deepseek/lib/index.js'
STORAGE_JSON_MODULE = '@deepseek-ai/dsh-storage-json/lib/index.js'
SESSION_PERSISTENCE_JSONL_MODULE = '@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js'
LOCK_ROOT = HOOKS.parents[4] / 'tools/dsh-runtime'
PATCHES = {'@deepseek-ai/dsh-fs-local/lib/index.js': 'publishExclusive',
           '@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js': 'publishSessionExclusive',
           '@deepseek-ai/dsh-attachment-local/lib/index.js': 'publishAttachmentExclusive'}


def recipe_inputs():
    paths = [Path(__file__), LOCK_ROOT / 'package.json', LOCK_ROOT / 'package-lock.json']
    paths += [directory / name for directory in (HOOKS, SESSION_HOOKS, COMBO_HOOKS) for name in ('index.js', 'package.json')]
    paths.append(COMBO_PATCH)
    paths.append(DEEPSEEK_MESSAGES_PATCH)
    recipe = json.loads(DEEPSEEK_MESSAGES_PATCH.read_text(encoding='utf-8'))
    paths += [HOOKS.parent / patch['prependAsset'] for patch in recipe['patches'] if 'prependAsset' in patch]
    root = Path(__file__).resolve().parents[1]
    return {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}


def patched_content(relative, data):
    """只修改固定版本的两个发布调用依赖，保留上游的校验、迁移及排他语义。"""
    name = relative.as_posix()
    if name == COMBO_MODULE:
        text = data.decode('utf-8')
        for patch in json.loads(COMBO_PATCH.read_text(encoding='utf-8'))['patches']:
            if text.count(patch['before']) != 1:
                raise ValueError('上游网页拼接结构变化，必须重新检查')
            text = text.replace(patch['before'], patch['after'])
        return text.encode('utf-8')
    if name == DEEPSEEK_MESSAGES_MODULE:
        text = data.decode('utf-8')
        recipe = json.loads(DEEPSEEK_MESSAGES_PATCH.read_text(encoding='utf-8'))
        if recipe.get('module') != name:
            raise ValueError('DeepSeek Messages 补丁目标与锁定模块不一致')
        for patch in recipe['patches']:
            before, after = patch['before'], patch['after']
            if text.count(before) != 1:
                raise ValueError('上游 DeepSeek Messages 序列化结构变化，必须重新检查')
            if 'prependAsset' in patch:
                after = (HOOKS.parent / patch['prependAsset']).read_text(encoding='utf-8') + '\n' + after
            text = text.replace(before, after)
        return text.encode('utf-8')
    if name == STORAGE_JSON_MODULE:
        text = data.decode('utf-8')
        imports = 'import { mkdir, open, readFile, readdir, rename, rm } from "node:fs/promises";'
        if text.count(imports) != 1:
            raise ValueError('上游 JSON 存储导入结构变化，必须重新检查')
        text = text.replace(imports, '/* DSHA_PROROOT_DIRENT_FALLBACK_V2 */\n'
                            '/* DSHA_PROROOT_DIRECT_RECORD_HINTS_V1 */\n' + imports)
        # proroot 在部分 bind/内核组合上不仅会把 getdents 的 d_type 报成
        # DT_UNKNOWN，刚刚 mkdir/rename 的项还可能暂时不出现在上级目录枚举中。
        # descriptor 已经给出全部表名，因此不枚举 unit 根，而是直达每个
        # 已声明表目录。表内保留字符串文件名枚举，并将本进程已成功
        # 发布的键作为直接路径提示：重开仍会重新 readFile+解析磁盘正文，
        # 不会拿写入时的内存值冒充持久化验证。
        scan = '''\tlet entries;
\ttry {
\t\tentries = await readdir(dir, { withFileTypes: true });
\t} catch (error) {
\t\tif (error.code !== "ENOENT") throw error;
\t}
\tif (!(entries === void 0 ? false : (await Promise.all(entries.map(async (entry) => {
\t\tif (entry.isDirectory()) {
\t\t\tconst records = state.tables.get(entry.name);
\t\t\tif (records !== void 0) return loadTableRecords(records, versions, join(dir, entry.name));
\t\t}
\t\tif (entry.name === "global.json" && descriptor.hasGlobal) {
\t\t\tconst global = await readRecord(join(dir, entry.name), versions);
\t\t\tif (global !== void 0) state.global = global;
\t\t\treturn true;
\t\t}
\t\treturn false;
\t}))).some(Boolean))) await bootstrapLegacyUnit(descriptor, dir, state);'''
        replacement = '''\tconst tableDocuments = await Promise.all([...state.tables].map(async ([table, records]) => {
\t\treturn loadTableRecords(records, versions, join(dir, table), dshaRecordHints);
\t}));
\tlet hasDocuments = tableDocuments.some(Boolean);
\tif (descriptor.hasGlobal) {
\t\tconst global = await inspectRecord(join(dir, "global.json"), versions);
\t\tif (global.present) {
\t\t\thasDocuments = true;
\t\t\tif (global.value !== void 0) state.global = global.value;
\t\t}
\t}
\tif (!hasDocuments) await bootstrapLegacyUnit(descriptor, dir, state);'''
        if text.count(scan) != 1:
            raise ValueError('上游 JSON 存储目录加载结构变化，必须重新检查')
        text = text.replace(scan, replacement)
        table_scan = '''\tconst files = await readdir(dir, { withFileTypes: true });
\tconst hasDocuments = files.some((file) => file.name.endsWith(".json"));
\tconst loaded = await Promise.all(files.map(async (file) => {
\t\tif (!file.name.endsWith(".json")) return;
\t\tconst key = file.name.slice(0, -5);
\t\tif (!SAFE_KEY_RE.test(key)) return;
\t\tconst record = await readRecord(join(dir, file.name), versions);
\t\tif (record !== void 0) return [key, record];
\t}));'''
        table_replacement = '''\tlet files;
\ttry {
\t\tfiles = await readdir(dir);
\t} catch (error) {
\t\tif (error.code !== "ENOENT" && error.code !== "ENOTDIR") throw error;
\t\tfiles = [];
\t}
\tconst listed = new Set(files);
\tconst recent = dshaRecordHints.get(dir);
\tif (recent !== void 0) for (const key of recent) files.push(`${key}.json`);
\tconst candidates = [...new Set(files)];
\tlet hintedDocument = false;
\tconst hasDocuments = candidates.some((file) => listed.has(file) && file.endsWith(".json"));
\tconst loaded = await Promise.all(candidates.map(async (file) => {
\t\tif (!file.endsWith(".json")) return;
\t\tconst key = file.slice(0, -5);
\t\tif (!SAFE_KEY_RE.test(key)) return;
\t\tconst record = await inspectRecord(join(dir, file), versions);
\t\tif (recent?.has(key)) {
\t\t\tif (record.present) hintedDocument = true;
\t\t\tif (!record.present || listed.has(file)) dshaRecordHints.forget(dir, key);
\t\t}
\t\tif (record.value !== void 0) return [key, record.value];
\t}));'''
        if text.count(table_scan) != 1:
            raise ValueError('上游 JSON 存储记录加载结构变化，必须重新检查')
        text = text.replace(table_scan, table_replacement)
        table_return = '''\tfor (const record of loaded) if (record !== void 0) records.set(...record);
\treturn hasDocuments;
}
/** Read one record document; a foreign (unreadable or stale) one reads as absent. */
async function readRecord(path, versions) {
\ttry {
\t\treturn parseRecord(await readFile(path, "utf8"), versions);
\t} catch {
\t\treturn;
\t}
}'''
        table_return_replacement = '''\tfor (const record of loaded) if (record !== void 0) records.set(...record);
\treturn hasDocuments || hintedDocument;
}
/**
* Read and classify one record document. Malformed JSON and foreign versions
* remain present-but-ignored; a genuinely missing path is absent. Unexpected
* filesystem failures must propagate instead of being misreported as an empty table.
*/
async function inspectRecord(path, versions) {
\tlet text;
\ttry {
\t\ttext = await readFile(path, "utf8");
\t} catch (error) {
\t\tif (error.code === "ENOENT" || error.code === "ENOTDIR") return { present: false };
\t\tif (error.code === "EISDIR" || error.code === "ELOOP") return { present: true };
\t\tthrow error;
\t}
\treturn { present: true, value: parseRecord(text, versions) };
}'''
        if text.count(table_return) != 1:
            raise ValueError('上游 JSON 存储记录读取结构变化，必须重新检查')
        text = text.replace(table_return, table_return_replacement)

        recent_anchor = '/** Keys become path segments in this layout; this set is path-safe on every OS. */\nconst SAFE_KEY_RE = /^[a-zA-Z0-9_-]+$/;'
        recent_replacement = recent_anchor + '''
/* DSHA: backend-owned direct-path hints for records durably published in this process. */
const DSHA_RECORD_HINT_LIMIT = 4096;
var DshaRecordHints = class {
\trecords = /* @__PURE__ */ new Map();
\tsize = 0;
\tget(dir) {
\t\tconst records = this.records.get(dir);
\t\tif (records === void 0) return;
\t\tconst keys = /* @__PURE__ */ new Set();
\t\tfor (const [key, record] of records) if (record.committed) keys.add(key);
\t\treturn keys.size === 0 ? void 0 : keys;
\t}
\tasync reserve(dir, key) {
\t\tlet records = this.records.get(dir);
\t\tlet record = records?.get(key);
\t\tif (record !== void 0) {
\t\t\trecord.pending++;
\t\t\treturn { dir, key };
\t\t}
\t\tif (this.size >= DSHA_RECORD_HINT_LIMIT) await this.reconcile();
\t\trecords = this.records.get(dir);
\t\trecord = records?.get(key);
\t\tif (record !== void 0) {
\t\t\trecord.pending++;
\t\t\treturn { dir, key };
\t\t}
\t\tif (this.size >= DSHA_RECORD_HINT_LIMIT) throw new Error("DSHA_PROROOT_RECORD_HINT_LIMIT");
\t\tif (records === void 0) this.records.set(dir, records = /* @__PURE__ */ new Map());
\t\trecords.set(key, { committed: false, pending: 1 });
\t\tthis.size++;
\t\treturn { dir, key };
\t}
\tcommit(reservation) {
\t\tconst record = this.records.get(reservation.dir)?.get(reservation.key);
\t\tif (record === void 0) return;
\t\trecord.pending--;
\t\trecord.committed = true;
\t}
\tcancel(reservation) {
\t\tconst record = this.records.get(reservation.dir)?.get(reservation.key);
\t\tif (record === void 0) return;
\t\trecord.pending--;
\t\tif (record.pending === 0 && !record.committed) this.remove(reservation.dir, reservation.key);
\t}
\tforget(dir, key) {
\t\tconst record = this.records.get(dir)?.get(key);
\t\tif (record === void 0) return;
\t\trecord.committed = false;
\t\tif (record.pending === 0) this.remove(dir, key);
\t}
\tremove(dir, key) {
\t\tconst records = this.records.get(dir);
\t\tif (records === void 0 || !records.delete(key)) return;
\t\tthis.size--;
\t\tif (records.size === 0) this.records.delete(dir);
\t}
\tasync reconcile() {
\t\tfor (const [dir, records] of [...this.records]) {
\t\t\tlet files;
\t\t\ttry {
\t\t\t\tfiles = new Set(await readdir(dir));
\t\t\t} catch {
\t\t\t\tcontinue;
\t\t\t}
\t\t\tfor (const [key, record] of [...records]) {
\t\t\t\tif (record.committed && files.has(`${key}.json`)) this.forget(dir, key);
\t\t\t}
\t\t}
\t}
\tclear() {
\t\tthis.records.clear();
\t\tthis.size = 0;
\t}
};'''
        if text.count(recent_anchor) != 1:
            raise ValueError('上游 JSON 存储键校验结构变化，必须重新检查')
        text = text.replace(recent_anchor, recent_replacement)

        mutations = [
            ('''\t\tawait this.tracked(this.writeDocument(join(this.tableDir(table), `${key}.json`), value));''',
             '''\t\tconst dir = this.tableDir(table);
\t\tawait this.tracked((async () => {
\t\t\tconst reservation = await this.dshaRecordHints.reserve(dir, key);
\t\t\ttry {
\t\t\t\tawait this.writeDocument(join(dir, `${key}.json`), value);
\t\t\t\tthis.dshaRecordHints.commit(reservation);
\t\t\t} catch (error) {
\t\t\t\tthis.dshaRecordHints.cancel(reservation);
\t\t\t\tthrow error;
\t\t\t}
\t\t})());'''),
            ('''\t\tawait this.tracked(rm(join(this.tableDir(table), `${key}.json`), { force: true }));''',
             '''\t\tconst dir = this.tableDir(table);
\t\tawait this.tracked(rm(join(dir, `${key}.json`), { force: true }));
\t\tthis.dshaRecordHints.forget(dir, key);'''),
            ('''\t\tawait this.tracked(rename(path, moved));
\t\treturn moved;''',
             '''\t\tawait this.tracked(rename(path, moved));
\t\tthis.dshaRecordHints.forget(this.tableDir(table), key);
\t\treturn moved;'''),
        ]
        for before, after in mutations:
            if text.count(before) != 1:
                raise ValueError('上游 JSON 存储记录变更结构变化，必须重新检查')
            text = text.replace(before, after)
        lifecycle_mutations = [
            ('async function loadTableRecords(records, versions, dir) {',
             'async function loadTableRecords(records, versions, dir, dshaRecordHints) {'),
            ('''async function openPerRecordUnit(descriptor, root, onClose) {
\treturn new PerRecordJsonUnit(descriptor, join(root, descriptor.name), onClose);
}''',
             '''async function openPerRecordUnit(descriptor, root, onClose, dshaRecordHints) {
\treturn new PerRecordJsonUnit(descriptor, join(root, descriptor.name), onClose, dshaRecordHints);
}'''),
            ('async function loadPerRecordState(descriptor, dir) {',
             'async function loadPerRecordState(descriptor, dir, dshaRecordHints) {'),
            ('''\tdir;
\tonClose;
\tclosed = false;''',
             '''\tdir;
\tonClose;
\tdshaRecordHints;
\tclosed = false;'''),
            ('''\tconstructor(descriptor, dir, onClose) {
\t\tthis.descriptor = descriptor;
\t\tthis.dir = dir;
\t\tthis.onClose = onClose;
\t}''',
             '''\tconstructor(descriptor, dir, onClose, dshaRecordHints) {
\t\tthis.descriptor = descriptor;
\t\tthis.dir = dir;
\t\tthis.onClose = onClose;
\t\tthis.dshaRecordHints = dshaRecordHints;
\t}'''),
            ('const state = await loadPerRecordState(this.descriptor, this.dir);',
             'const state = await loadPerRecordState(this.descriptor, this.dir, this.dshaRecordHints);'),
            ('''\t/** Drain in-flight writes and release the unit. Idempotent. */
\tasync close() {
\t\tif (this.closed) {
\t\t\tawait Promise.allSettled(this.inFlight);
\t\t\treturn;
\t\t}
\t\tthis.closed = true;
\t\tawait Promise.allSettled(this.inFlight);
\t\tthis.onClose();
\t}''',
             '''\t/** Drain in-flight writes and release the unit. Idempotent. */
\tasync close() {
\t\tif (this.closed) {
\t\t\tawait Promise.allSettled(this.inFlight);
\t\t\treturn;
\t\t}
\t\tthis.closed = true;
\t\tawait Promise.allSettled(this.inFlight);
\t\tawait this.dshaRecordHints.reconcile().catch(() => {});
\t\tthis.onClose();
\t}'''),
            ('''var JsonStorageBackend = class {
\troot;
\topen = /* @__PURE__ */ new Map();''',
             '''var JsonStorageBackend = class {
\troot;
\tdshaRecordHints = new DshaRecordHints();
\topen = /* @__PURE__ */ new Map();'''),
            ('''const unit = descriptor.layout === "per-record" ? await openPerRecordUnit(descriptor, this.root, onClose) : await openSingleUnit(descriptor, this.root, onClose);''',
             '''const unit = descriptor.layout === "per-record" ? await openPerRecordUnit(descriptor, this.root, onClose, this.dshaRecordHints) : await openSingleUnit(descriptor, this.root, onClose);'''),
            ('''\tasync close() {
\t\tif (!this.closed) this.closed = true;
\t\tawait Promise.allSettled([...this.opening.values()]);
\t\tfor (const unit of [...this.open.values()]) await unit.close();
\t}''',
             '''\tasync close() {
\t\tif (!this.closed) this.closed = true;
\t\ttry {
\t\t\tawait Promise.allSettled([...this.opening.values()]);
\t\t\tfor (const unit of [...this.open.values()]) await unit.close();
\t\t} finally {
\t\t\tthis.dshaRecordHints.clear();
\t\t}
\t}'''),
        ]
        for before, after in lifecycle_mutations:
            if text.count(before) != 1:
                raise ValueError('上游 JSON 存储生命周期结构变化，必须重新检查')
            text = text.replace(before, after)
        return text.encode('utf-8')
    if name == SESSION_PERSISTENCE_JSONL_MODULE:
        text = data.decode('utf-8')
        # 会话发布必须走受管的排他发布器；proroot/SELinux 下原生
        # link(2) 可能被转换成悬空链接或直接返回 EINVAL。保留调用点
        # 的 link 名称，避免改写上游状态机和 EEXIST 语义。
        import_pattern = re.compile(r'^import \{([^}]+)\} from "node:fs/promises";', re.M)
        import_match = import_pattern.search(text)
        if import_match is None:
            raise ValueError('会话后端 fs/promises 导入结构变化，必须重新检查')
        import_fields = [item.strip() for item in import_match.group(1).split(',') if item.strip()]
        if 'link' not in import_fields:
            raise ValueError('会话后端缺少 link 发布调用，必须重新检查')
        import_fields.remove('link')
        import_replacement = ('import { publishSessionExclusive as link } from "dsha-runtime-fs";\n'
                              'import { ' + ', '.join(import_fields) + ' } from "node:fs/promises";')
        text = text[:import_match.start()] + import_replacement + text[import_match.end():]
        # proroot 下刚刚发布的 session 目录有时不会出现在父目录的
        # getdents 结果中。正常运行会话的物理文件仍然按 JSONL 后端的
        # 原子发布、格式校验和路径校验读取；这里只为本进程已经通过
        # create() 接收的 header 保存一个受管 cwd 提示，使 open(id)
        # 可以直接检查确定的日志路径，不把“目录没列出来”误报成不存在。
        marker = '/* DSHA_SESSION_DIRECT_HINTS_V1 */\n'
        if marker in text:
            raise ValueError('会话后端已经包含 DSHA_SESSION_DIRECT_HINTS_V1')
        create_anchor = '''\t\tthis.tracker.registerCreated(snapshot, inheritedEventCount);\n\t\treturn this.tracker.adopt(new JsonlSessionHandle(this, snapshot.id, snapshot, "write", {'''
        create_replacement = '''\t\tthis.tracker.registerCreated(snapshot, inheritedEventCount);\n\t\tthis.dshaSessionHints.remember(snapshot);\n\t\treturn this.tracker.adopt(new JsonlSessionHandle(this, snapshot.id, snapshot, "write", {'''
        if text.count(create_anchor) != 1:
            raise ValueError('会话 create 结构变化，必须重新检查')
        text = text.replace(create_anchor, create_replacement)
        field_anchor = '''\tprivate readonly tracker;\n\t/**\n\t * Bounded LRU'''
        # The emitted JS has no private field declarations. Add the field next
        # to the tracker declaration in the class body instead.
        class_anchor = '''\ttracker = new JsonlBackendTracker(this.name);\n\tgenerationFormat;'''
        class_replacement = '''\ttracker;\n\t/** DSHA_SESSION_DIRECT_HINTS_V1: cwd hints for durable sessions published here. */\n\tdshaSessionHints = new DshaSessionHints();\n\t/**\n\t * Bounded LRU'''
        if text.count(class_anchor) != 1:
            raise ValueError('会话后端 tracker 结构变化，必须重新检查')
        text = text.replace(class_anchor, '''\ttracker = new JsonlBackendTracker(this.name);\n\tdshaSessionHints = new DshaSessionHints();\n\tgenerationFormat;''')
        find_anchor = '''\tasync findLog(id, signal) {\n\t\tconst matches = [];'''
        find_replacement = '''\tasync findLog(id, signal) {\n\t\tconst hinted = this.dshaSessionHints.get(id);\n\t\tif (hinted !== void 0) {\n\t\t\tconst selected = await this.resolveHintedGeneration(hinted, id, signal);\n\t\t\tif (selected !== void 0) return selected;\n\t\t\tthis.dshaSessionHints.forget(id);\n\t\t}\n\t\tconst matches = [];'''
        if text.count(find_anchor) != 1:
            raise ValueError('会话 findLog 结构变化，必须重新检查')
        text = text.replace(find_anchor, find_replacement)
        resolve_anchor = '''\t/** Find the unique authoritative generation for an id across project directories. */\n\tasync findLog(id, signal) {'''
        resolve_replacement = '''\t/** Resolve a session published by this backend without relying on parent getdents. */\n\tasync resolveHintedGeneration(header, id, signal) {\n\t\tsignal?.throwIfAborted();\n\t\tconst dir = sessionDir(this.root, header.cwd, id);\n\t\tconst currentPath = generationLogPath(this.root, header.cwd, id, sessionFormatCatalog.currentVersion, this.compression);\n\t\tif (await this.exists(currentPath)) return { sourcePath: currentPath, sourceVersion: sessionFormatCatalog.currentVersion, currentPath };\n\t\tconst oppositePath = generationLogPath(this.root, header.cwd, id, sessionFormatCatalog.currentVersion, this.oppositeCompression());\n\t\tif (await this.exists(oppositePath)) throw this.encodingMismatch(oppositePath);\n\t\treturn void 0;\n\t}\n\t/** Find the unique authoritative generation for an id across project directories. */\n\tasync findLog(id, signal) {'''
        if text.count(resolve_anchor) != 1:
            raise ValueError('会话 findLog 锚点变化，必须重新检查')
        text = text.replace(resolve_anchor, resolve_replacement)
        release_anchor = '''\treleaseHandle(handle, materialized) {\n\t\tthis.tracker.release(handle, materialized);\n\t}'''
        release_replacement = '''\treleaseHandle(handle, materialized) {\n\t\tif (!materialized) this.dshaSessionHints.forget(handle.id);\n\t\tthis.tracker.release(handle, materialized);\n\t}'''
        if text.count(release_anchor) != 1:
            raise ValueError('会话 releaseHandle 结构变化，必须重新检查')
        text = text.replace(release_anchor, release_replacement)
        hints_anchor = '''/**\n* The JSONL persistence backend.'''
        hints_code = '''/* DSHA_SESSION_DIRECT_HINTS_V1 */\nclass DshaSessionHints {\n\tentries = new Map();\n\tremember(header) {\n\t\tif (header && typeof header.id === "string") this.entries.set(header.id, { id: header.id, cwd: header.cwd });\n\t}\n\tget(id) { return this.entries.get(id); }\n\tforget(id) { this.entries.delete(id); }\n}\n'''
        if text.count(hints_anchor) != 1:
            raise ValueError('会话后端类锚点变化，必须重新检查')
        text = text.replace(hints_anchor, hints_code + hints_anchor)
        return (marker + text).encode('utf-8')
    if name == '@deepseek-ai/dsh-session-format-v2-to-v3/lib/index.js':
        text = data.decode('utf-8')
        target = 'return new ReleasedV2ToV3Stage(input);'
        if text.count(target) != 1:
            raise ValueError('上游 V2/V3 阶段结构变化，必须重新检查')
        return ('/* DSHA_LEGACY_SESSION_V1 */\nimport { wrapDshaLegacyStage } from "dsha-session-compat";\n'
                + text.replace(target, 'return wrapDshaLegacyStage(new ReleasedV2ToV3Stage(input), input, remapEvent);')).encode('utf-8')
    if name not in PATCHES:
        return data
    text = data.decode('utf-8')
    pattern = re.compile(r'^import \{([^}]+)\} from "node:fs/promises";', re.M)
    found = pattern.search(text)
    if not found or 'link' not in [item.strip() for item in found.group(1).split(',')]:
        raise ValueError('上游原子发布结构变化，必须重新检查：' + name)
    fields = [item.strip() for item in found.group(1).split(',') if item.strip() != 'link']
    replacement = '/* DSHA_ATOMIC_PUBLISH_V1 */\nimport { %s as link } from "dsha-runtime-fs";\nimport { %s } from "node:fs/promises";' % (PATCHES[name], ', '.join(fields))
    return (text[:found.start()] + replacement + text[found.end():]).encode('utf-8')


def target_path(relative):
    name = relative.as_posix()
    if name == PACKAGE or name.startswith(PACKAGE + '/'):
        return PREFIX + name[len(PACKAGE):]
    return PREFIX + '/node_modules/' + name


def _platform_excluded(values, current):
    """npm 的 os/cpu/libc 白名单判定；和 npm 的负向规则保持同一方向。"""
    if isinstance(values, str):
        values = [values]
    if not isinstance(values, list) or not values:
        return False
    values = [item for item in values if isinstance(item, str)]
    positives = [item for item in values if not item.startswith('!')]
    if '!' + current in values:
        return True
    return bool(positives and current not in positives and 'any' not in positives)


def _foreign_packages(source):
    """找出 npm 为其它平台安装的可选包，避免把 PE/其它 libc 带进 arm64 glibc APK。"""
    foreign = set()
    for package_json in source.rglob('package.json'):
        try:
            metadata = json.loads(package_json.read_text(encoding='utf-8'))
        except (OSError, ValueError, UnicodeError):
            continue
        if (_platform_excluded(metadata.get('os'), 'linux')
                or _platform_excluded(metadata.get('cpu'), 'arm64')
                or _platform_excluded(metadata.get('libc'), 'glibc')):
            foreign.add(package_json.parent.relative_to(source))
    return foreign


def omitted(relative, foreign=()):
    parts = relative.parts
    if '.bin' in parts or '.cache' in parts or relative.name == '.package-lock.json':
        return 'regenerable-cache'
    for package in foreign:
        try:
            relative.relative_to(package)
            return 'foreign-platform'
        except ValueError:
            pass
    if 'prebuilds' in parts:
        at = parts.index('prebuilds') + 1
        if at < len(parts) and parts[at] != 'linux-arm64':
            return 'foreign-prebuild'
    # 这些是开发/调试产物，Node 的模块解析不会读取它们；删掉可显著减少冷安装 I/O。
    if relative.name.endswith(('.map', '.d.ts', '.d.mts', '.d.cts', '.tsbuildinfo')):
        return 'development-metadata'
    if any(part.lower() in {'.github', '.yarn', '.circleci'} for part in parts):
        return 'repository-metadata'
    if 'node-pty' in parts and 'third_party' in parts and 'conpty' in parts:
        return 'foreign-conpty'
    if any(part.lower() in {'test', 'tests', '__tests__'} for part in parts):
        return 'tests'
    return ''


def build(source, output, version):
    source = source.resolve(strict=True)
    package = json.loads((source / PACKAGE / 'package.json').read_text(encoding='utf-8'))
    if package.get('name') != PACKAGE or package.get('version') != version:
        raise ValueError('npm 安装树不是要求的 dsh 版本')
    deepseek_recipe = json.loads(DEEPSEEK_MESSAGES_PATCH.read_text(encoding='utf-8'))
    deepseek_package = json.loads((source / '@deepseek-ai/dsh-llm-deepseek/package.json').read_text(encoding='utf-8'))
    if (deepseek_recipe.get('dshVersion') != version
            or deepseek_package.get('name') != '@deepseek-ai/dsh-llm-deepseek'
            or deepseek_package.get('version') != deepseek_recipe.get('dshVersion')):
        raise ValueError('DeepSeek Messages 补丁没有锁定到本次 dsh 依赖树')
    if not (source / PACKAGE / 'lib/bin.js').is_file():
        raise ValueError('npm 包没有编译后的 dsh 入口')
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + '.tmp')
    count, size, binaries = 0, 0, []
    omitted_sizes = {}
    foreign = _foreign_packages(source)
    links = {}
    try:
        with temporary.open('wb') as raw, gzip.GzipFile(fileobj=raw, mode='wb', filename='', mtime=0, compresslevel=9) as compressed:
            with tarfile.open(fileobj=compressed, mode='w|', format=tarfile.PAX_FORMAT) as archive:
                for path in sorted(source.rglob('*')):
                    relative = path.relative_to(source)
                    reason = omitted(relative, foreign)
                    if reason:
                        omitted_sizes[reason] = omitted_sizes.get(reason, 0) + (path.stat().st_size if path.is_file() else 0)
                        continue
                    name = target_path(relative)
                    if path.is_symlink():
                        resolved = path.resolve(strict=True)
                        resolved.relative_to(source)
                        item = tarfile.TarInfo(name)
                        item.type = tarfile.SYMTYPE
                        item.linkname = posixpath.relpath(target_path(resolved.relative_to(source)), posixpath.dirname(name))
                        item.mode = 0o777
                        archive.addfile(item)
                        continue
                    if path.is_dir():
                        continue
                    if not path.is_file():
                        raise ValueError('npm 树含特殊文件：' + str(relative))
                    with path.open('rb') as stream:
                        header = stream.read(20)
                        stream.seek(0)
                        if header[:4] == b'\x7fELF':
                            if len(header) < 20 or int.from_bytes(header[18:20], 'little') != 183:
                                raise ValueError('离线包混入非 arm64 ELF：' + str(relative))
                            binaries.append(relative.as_posix())
                        content = patched_content(relative, stream.read()) if relative.as_posix() in PATCHES \
                            or relative.as_posix() in (COMBO_MODULE, DEEPSEEK_MESSAGES_MODULE, STORAGE_JSON_MODULE,
                                                       SESSION_PERSISTENCE_JSONL_MODULE,
                                                       '@deepseek-ai/dsh-session-format-v2-to-v3/lib/index.js') else None
                        if content is not None:
                            stream = io.BytesIO(content)
                        item = tarfile.TarInfo(name)
                        item.size = len(content) if content is not None else path.stat().st_size
                        item.mode = 0o755 if header.startswith((b'#!', b'\x7fELF')) else 0o644
                        archive.addfile(item, stream)
                    count += 1
                    size += item.size
                    if path.name == 'package.json':
                        parent = path.parent.parent
                        if parent.name.startswith('@'):
                            parent = parent.parent
                        if parent != source:
                            continue
                        try:
                            meta = json.loads(path.read_text(encoding='utf-8'))
                        except (ValueError, UnicodeError):
                            continue
                        package_name = meta.get('name', '')
                        if package_name not in (PACKAGE, 'npm') and re.fullmatch(r'(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*', package_name):
                            alias = 'usr/local/lib/node_modules/' + package_name
                            links[alias] = posixpath.relpath(PREFIX + '/node_modules/' + package_name, posixpath.dirname(alias))
                        entries = meta.get('bin', {})
                        if isinstance(entries, str):
                            entries = {meta.get('name', '').split('/')[-1]: entries}
                        for command, entry in entries.items() if isinstance(entries, dict) else ():
                            if not command or '/' in command or '\\' in command or not isinstance(entry, str):
                                continue
                            entry_path = (path.parent / entry).resolve(strict=True)
                            entry_path.relative_to(source)
                            destination = PREFIX + '/node_modules/.bin/' + command
                            links.setdefault(destination, posixpath.relpath(target_path(entry_path.relative_to(source)), posixpath.dirname(destination)))
                            if command in ('tsc', 'tsserver'):
                                links['usr/local/bin/' + command] = posixpath.relpath(target_path(entry_path.relative_to(source)), 'usr/local/bin')
                for directory, package in ((HOOKS, 'dsha-runtime-fs'), (SESSION_HOOKS, 'dsha-session-compat'), (COMBO_HOOKS, 'dsha-client-combo-cache')):
                    for filename in ('package.json', 'index.js'):
                        content = (directory / filename).read_bytes()
                        item = tarfile.TarInfo(PREFIX + '/node_modules/' + package + '/' + filename)
                        item.size, item.mode = len(content), 0o644
                        archive.addfile(item, io.BytesIO(content))
                        count += 1; size += len(content)
                # 一些社区预设仍使用 0.1.5 的工作流包名。只在 alpha.2 新包真实存在且
                # 旧包不存在时提供受管别名，不改写用户插件源码，也不会遮蔽用户安装的旧包。
                if (source / WORKFLOW_PTC).is_dir() and not (source / LEGACY_WORKFLOW_PTC).exists():
                    target = PREFIX + '/node_modules/' + WORKFLOW_PTC
                    nested_alias = PREFIX + '/node_modules/' + LEGACY_WORKFLOW_PTC
                    global_alias = 'usr/local/lib/node_modules/' + LEGACY_WORKFLOW_PTC
                    links[nested_alias] = posixpath.relpath(target, posixpath.dirname(nested_alias))
                    links[global_alias] = posixpath.relpath(target, posixpath.dirname(global_alias))
                links['usr/local/bin/dsh'] = '../lib/node_modules/@deepseek-ai/dsh/lib/bin.js'
                for name, target in sorted(links.items()):
                    item = tarfile.TarInfo(name)
                    item.type = tarfile.SYMTYPE
                    item.linkname = target
                    item.mode = 0o777
                    archive.addfile(item)
                # 归档携带独立身份，运行验证与打包检查可确认真正安装的版本。
                identity = (version + '\n').encode()
                item = tarfile.TarInfo('usr/local/share/dsha/dsh-runtime.version')
                item.size, item.mode = len(identity), 0o644
                archive.addfile(item, io.BytesIO(identity))
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    inputs = {'version': version, 'inputs': recipe_inputs(), 'archive_sha256': hashlib.sha256(output.read_bytes()).hexdigest()}
    output.with_suffix('.inputs.json').write_text(json.dumps(inputs, indent=2) + '\n', encoding='utf-8')
    return {'version': version, 'files': count, 'unpacked_bytes': size,
            'archive_bytes': output.stat().st_size, 'sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
            'arm64_binaries': binaries,
            'omitted_unpacked_bytes': omitted_sizes,
            'omitted_foreign_packages': sorted(item.as_posix() for item in foreign),
            'note': '安装脚本没有在宿主执行；仍须 Linux arm64 真运行验证'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='npm 安装后的 node_modules 目录')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--version', default=json.loads((LOCK_ROOT / 'package.json').read_text(encoding='utf-8'))['dependencies'][PACKAGE])
    args = parser.parse_args()
    print(json.dumps(build(args.source, args.output, args.version), ensure_ascii=False, indent=2))
