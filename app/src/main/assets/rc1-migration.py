#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""rc1 分代保护；宿主状态与用户可导入数据分离，失败不启动有副作用的导入。"""
import argparse, hashlib, json, os, re, shutil, stat, tempfile, time, traceback, uuid
from pathlib import Path

# Dependency version for converted preset candidates, not a data-format claim.
VERSION = '0.2.0-rc.2'
# Stable asset marker: settings.yaml.imported is a retryable input after a
# pending section result; it never authorizes a false committed receipt.
RESTORED_SETTINGS_IMPORTED_FOR_RETRY = True
MAX_FILES = 100000
MAX_BYTES = 8 * 1024 * 1024 * 1024


class MigrationSourceError(ValueError):
    def __init__(self, reason, logical, actual, operation):
        super().__init__(reason)
        self.logical, self.actual, self.operation = str(logical), str(actual), operation


def require_regular(logical, actual, operation):
    node = os.stat(actual)
    if not stat.S_ISREG(node.st_mode):
        raise MigrationSourceError('MIGRATION_SOURCE_NOT_REGULAR', logical, actual, operation)
    return node


def failure_details(error, dsh, operation, logical=None, actual=None):
    logical = getattr(error, 'logical', logical)
    actual = getattr(error, 'actual', actual)
    try: path = Path(logical).relative_to(dsh).as_posix() if logical else None
    except ValueError: path = str(logical)
    return dict(operation=getattr(error, 'operation', operation), path=path,
                mapping=str(actual) if actual else None, errorType=type(error).__name__,
                # Kept only in the private migration record; never snapshot file contents.
                traceback=traceback.format_exc(limit=8)[-8192:])


def digest(path):
    result = hashlib.sha256()
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''): result.update(chunk)
    return result.hexdigest()


def write_json(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(prefix='.migration-', dir=str(Path(path).parent))
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        if os.path.lexists(temp): os.unlink(temp)


def read_json(path):
    with open(path, encoding='utf-8') as stream: return json.load(stream)


def emit(status, **data):
    print('DSHA_RC1_MIGRATION=' + json.dumps(dict(status=status, **data), ensure_ascii=False))


def within(root, path):
    return os.path.commonpath((root, path)) == root


class Resolver:
    """批准根内逐链接解析。禁止 realpath 隐式跨 guest 外部挂载。"""
    def __init__(self, dsh, approved=()):
        self.roots = [os.path.abspath(dsh)] + [os.path.abspath(p) for p in approved]
    def resolve(self, path):
        path = os.path.abspath(path); links = []; visited = set()
        for _ in range(41):
            if not any(within(root, path) for root in self.roots): raise ValueError('LINK_OUTSIDE_APPROVED_ROOT')
            parts = Path(path).parts; cursor = Path(parts[0]); followed = False
            for index, part in enumerate(parts[1:], 1):
                cursor /= part
                if cursor.is_symlink():
                    key = str(cursor)
                    if key in visited: raise ValueError('LINK_LOOP')
                    visited.add(key); target = os.readlink(cursor)
                    links.append({'path':key, 'target':target})
                    # 历史 Android 公开路径使用本次显式映射，不接受任意 /data 别名。
                    if target.startswith('/storage/emulated/0/Documents/dshdata'):
                        target = '/sdcard/Documents/dshdata' + target[len('/storage/emulated/0/Documents/dshdata'):]
                    path = os.path.abspath(os.path.join(str(cursor.parent), target, *parts[index+1:]))
                    followed = True; break
            if not followed: return Path(path), links
        raise ValueError('LINK_LOOP')
    def files(self, path, skip=()):
        count = 0; ancestors = set()
        def visit(logical):
            nonlocal count
            actual, links = self.resolve(logical)
            node = os.lstat(actual)
            if stat.S_ISDIR(node.st_mode):
                identity = (node.st_dev, node.st_ino)
                if identity in ancestors: raise ValueError('DIRECTORY_LINK_LOOP')
                ancestors.add(identity)
                for child in sorted(os.listdir(actual)):
                    if child not in skip: yield from visit(Path(logical) / child)
                ancestors.remove(identity)
            elif stat.S_ISREG(node.st_mode):
                count += 1
                if count > MAX_FILES: raise ValueError('MIGRATION_FILE_LIMIT')
                yield Path(logical), actual, links
            else: raise ValueError('MIGRATION_SPECIAL_FILE')
        if os.path.lexists(path): yield from visit(path)


def quick(dsh, resolver):
    """普通启动只读取两个小配置和宿主恢复代次；不遍历会话。"""
    source = {}
    for name in ('settings.yaml', 'settings.yaml.imported'):
        if os.path.lexists(dsh / name):
            actual, _ = resolver.resolve(dsh / name)
            if require_regular(dsh / name, actual, 'settings-input').st_size > 4 * 1024 * 1024: raise ValueError('SETTINGS_SIZE_LIMIT')
            source[name] = digest(actual)
    restore = dsh / '.dsha-rc1-restore-generation'
    if restore.exists():
        actual, _ = resolver.resolve(restore)
        if require_regular(restore, actual, 'restore-generation').st_size > 4096: raise ValueError('RESTORE_GENERATION_FORMAT')
        source['restore'] = digest(actual)
    st = os.stat(dsh)
    return dict(dataRoot={'path':str(dsh), 'device':str(st.st_dev), 'inode':str(st.st_ino)}, inputs=source)


def same_input(old, current):
    if not isinstance(old,dict) or not isinstance(current,dict): return False
    if old.get('dataRoot') != current.get('dataRoot'): return False
    a, b = old.get('inputs', {}), current.get('inputs', {})
    if not isinstance(a,dict) or not isinstance(b,dict): return False
    if set(a) - {'settings.yaml','settings.yaml.imported','restore'} or set(b) - {'settings.yaml','settings.yaml.imported','restore'}: return False
    if any(not isinstance(value,str) or not re.fullmatch('[a-f0-9]{64}',value) for value in [*a.values(),*b.values()]): return False
    # 上游合法 settings→imported rename 是同一份输入，不是新恢复。
    # 两项同时存在时必须逐项相等；不能忽略 imported 的独立变化。
    if {'settings.yaml','settings.yaml.imported'} <= set(a) or {'settings.yaml','settings.yaml.imported'} <= set(b): return a == b
    normalize = lambda obj: {'settings':obj.get('settings.yaml', obj.get('settings.yaml.imported')), 'restore':obj.get('restore')}
    return normalize(a) == normalize(b)


def snapshot_row(folder, dsh, logical, actual, links, kind, ordinal):
    relative = logical.relative_to(dsh).as_posix()
    before = require_regular(logical, actual, 'snapshot'); destination = folder / 'snapshots' / str(ordinal)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with open(actual, 'rb') as source, open(destination, 'xb') as target:
        opened = os.fstat(source.fileno())
        if not stat.S_ISREG(opened.st_mode) or (before.st_dev, before.st_ino) != (opened.st_dev, opened.st_ino):
            raise MigrationSourceError('SOURCE_CHANGED_BEFORE_SNAPSHOT', logical, actual, 'snapshot')
        sha = hashlib.sha256(); total = 0
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            total += len(chunk)
            if total > MAX_BYTES: raise ValueError('MIGRATION_BYTE_LIMIT')
            target.write(chunk); sha.update(chunk)
        target.flush(); os.fsync(target.fileno())
    after = os.stat(actual)
    if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns): raise ValueError('SOURCE_CHANGED_DURING_SNAPSHOT')
    if digest(destination) != sha.hexdigest(): raise ValueError('SNAPSHOT_READBACK_FAILED')
    return dict(kind=kind, path=relative, size=total, sha256=sha.hexdigest(), snapshot=destination.relative_to(folder).as_posix(), links=links,
                mapping=str(actual), sourceIdentity=[str(before.st_dev),str(before.st_ino)], type='file')


def inventory(dsh, resolver):
    inputs = [(dsh/name, 'settings', ()) for name in ('settings.yaml','settings.yaml.imported')]
    inputs += [(dsh/'profiles','profile', ('node_modules','.pnpm-store','.cache')), (dsh/'.agent-presets','preset', ('node_modules','.pnpm-store','.cache')), (dsh/'sessions','session', ())]
    entries = []; total = 0
    for source, kind, skip in inputs:
        for logical, actual, links in resolver.files(source, skip):
            if kind == 'profile' and logical.name not in ('package.json','compatibility.json','pnpm-lock.yaml','pnpm-workspace.yaml','cordis.patch.yml'): continue
            before = require_regular(logical, actual, 'inventory')
            total += before.st_size
            if len(entries) >= MAX_FILES: raise ValueError('MIGRATION_FILE_LIMIT')
            if total > MAX_BYTES: raise ValueError('MIGRATION_BYTE_LIMIT')
            sha = digest(actual); after = os.stat(actual)
            identity = lambda node: [str(node.st_dev), str(node.st_ino), node.st_size, node.st_mtime_ns, node.st_ctime_ns]
            if identity(before) != identity(after): raise ValueError('SOURCE_CHANGED_DURING_INVENTORY')
            entries.append(dict(path=logical.relative_to(dsh).as_posix(), mapping=str(actual), kind=kind, links=links, size=before.st_size, sha256=sha, identity=identity(before)))
    fingerprint = hashlib.sha256(json.dumps(entries,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    return entries, fingerprint


def reusable_rows(folder, doc, entries):
    expected = {entry['path']: entry for entry in entries}; reusable = {}
    for row in doc.get('snapshotInventory', []) + doc.get('sources', []):
        entry = expected.get(row.get('path'))
        if not entry or row.get('sha256') != entry['sha256'] or row.get('sourceIdentity') != entry['identity'][:2] or row.get('links') != entry['links']: continue
        name = row.get('snapshot', '')
        if not re.fullmatch(r'snapshots/[0-9]+(?:-[a-f0-9]{32})?',name): continue
        source = folder/name
        if source.is_file() and not source.is_symlink() and source.stat().st_size == entry['size'] and digest(source) == entry['sha256']:
            reusable[row['path']] = row
    return reusable


def inert_directory(root, path):
    cursor = root
    for part in path.relative_to(root).parts:
        cursor /= part
        if os.path.lexists(cursor):
            node = os.lstat(cursor)
            if not stat.S_ISDIR(node.st_mode): raise ValueError('MIGRATION_CANDIDATE_PARENT_LINK_OR_TYPE')
        else: cursor.mkdir()



def state_path(root, state_root):
    # 单测默认位于临时 root 旁；生产必须由 Java 指定宿主私有绑定。
    return Path(state_root) if state_root else Path(root)/'.dsha-rc1-host-state'


def prepare(root, state_root=None, startup_id='manual', approved=()):
    dsh = Path(os.path.abspath(os.path.join(root, '.dsh'))); state = state_path(root, state_root)
    if not dsh.exists(): emit('skipped', reason='DSH_MISSING'); return 0
    if dsh.is_symlink(): raise ValueError('DSH_HOME_LINK_NOT_APPROVED')
    resolver = Resolver(dsh, approved); stamp = quick(dsh, resolver)
    state.mkdir(parents=True, exist_ok=True)
    if state.is_symlink(): raise ValueError('STATE_ROOT_LINK')
    current = read_json(state/'current.json') if (state/'current.json').is_file() else None
    if current and current.get('version') == 2 and same_input(current, stamp):
        folder = state/'generations'/current['generation']; prepared = read_json(folder/'prepare.json')
        if prepared.get('protectionComplete'):
            current['startupId'] = startup_id; write_json(state/'current.json', current)
            emit('already', generation=current['generation'], protectionComplete=True, continuation='保留数据 → rc1 迁移记录'); return 0
    # Full inventory only for a new/incomplete migration. Completed ordinary startup remains a small read.
    try:
        entries, fingerprint = inventory(dsh, resolver)
    except Exception as error:
        emit('failed', protectionComplete=False, stage='inventory', reason=str(error), errorType=type(error).__name__)
        return 1
    pending = read_json(state/'pending.json') if (state/'pending.json').is_file() and not (state/'pending.json').is_symlink() else None
    reuse = pending and same_input(pending,stamp) and pending.get('inputDigest') == fingerprint and re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', str(pending.get('generation','')))
    generation = pending['generation'] if reuse else str(uuid.uuid4())
    folder = state/'generations'/generation
    if folder.is_symlink(): raise ValueError('MIGRATION_GENERATION_LINK')
    previous = read_json(folder/'prepare.json') if reuse and (folder/'prepare.json').is_file() and not (folder/'prepare.json').is_symlink() else {}
    reusable = reusable_rows(folder, previous, entries) if previous.get('inputDigest') == fingerprint and not previous.get('protectionComplete') else {}
    # Two copies for presets (snapshot + inert converted candidate), plus bounded record/publication reserve.
    needed = sum(entry['size'] for entry in entries if entry['path'] not in reusable) + sum(entry['size'] for entry in entries if entry['kind']=='preset') + 8*1024*1024
    if shutil.disk_usage(state).free < needed:
        emit('failed', generation=generation if reuse else None, protectionComplete=False, stage='space-preflight', reason='MIGRATION_SPACE_REQUIRED', requiredBytes=needed)
        return 1
    folder.mkdir(parents=True, exist_ok=True)
    doc = dict(version=2, dshVersion=VERSION, generation=generation, startupId=startup_id, dshHome=str(dsh), status='preparing', protectionComplete=False, createdAt=previous.get('createdAt',int(time.time())), **stamp, inputDigest=fingerprint, snapshotInventory=list(reusable.values()), sources=[], presets=[], sessions=[], warnings=[])
    write_json(folder/'prepare.json', doc)
    write_json(state/'pending.json',dict(generation=generation,inputDigest=fingerprint,**stamp))
    operation, logical, actual = 'snapshot', None, None
    try:
        for entry in entries:
            logical, actual = dsh/entry['path'], Path(entry['mapping'])
            row = reusable.get(entry['path'])
            if row is None:
                ordinal = str(len(doc['sources']))
                if os.path.lexists(folder/'snapshots'/ordinal): ordinal += '-'+uuid.uuid4().hex
                row = snapshot_row(folder, dsh, logical, actual, entry['links'], entry['kind'], ordinal)
                if row['sha256'] != entry['sha256'] or row['sourceIdentity'] != entry['identity'][:2]: raise ValueError('SOURCE_CHANGED_AFTER_INVENTORY')
            doc['sources'].append(row)
            if entry['kind']=='session': doc['sessions'].append({'path':row['path'],'sha256':row['sha256'],'size':row['size'],'compatibility':'awaiting-runtime-open'})
            if len(doc['sources']) % 32 == 0: write_json(folder/'prepare.json',doc)
        write_json(folder/'prepare.json',doc)
        # 旧 v1 记录只留作来源证据，不能授予当前代次完整保护/导入成功。
        for name in ('prepare.json','receipt.json'):
            old = dsh/'.dsha-rc1-migration'/name
            if old.is_file() and not old.is_symlink():
                operation, logical, actual = 'legacy-record-copy', old, old
                saved = folder/('legacy-'+name)
                if os.path.lexists(saved):
                    if saved.is_symlink() or not saved.is_file() or digest(saved) != digest(old): raise ValueError('LEGACY_RECORD_CHANGED')
                else: shutil.copyfile(old, saved)
        preset_groups = {}
        for row in doc['sources']:
            if row['kind'] != 'preset': continue
            parts = Path(row['path']).parts
            if len(parts) < 3:
                # A .agent-presets root file is metadata/unknown input, not a preset
                # directory. Its verified snapshot is retained above. Treating its
                # filename as a preset used to copy a file onto candidate/".".
                doc['warnings'].append('PRESET_ROOT_FILE_PRESERVED:' + row['path'])
                continue
            preset_groups.setdefault(parts[1],[]).append(row)
        for name, rows in preset_groups.items():
            sha = hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest()
            candidate = dsh/'.dsha-rc1-migration/legacy-agent-presets'/name/(sha[:16]+'-'+generation[:8])
            if candidate.is_symlink(): raise ValueError('PRESET_CANDIDATE_LINK')
            inert_directory(dsh,candidate)
            for row in rows:
                relative = Path(*Path(row['path']).parts[2:]); destination = candidate/relative
                operation, logical, actual = 'preset-candidate-copy', dsh/row['path'], folder/row['snapshot']
                inert_directory(candidate,destination.parent)
                if os.path.lexists(destination):
                    if destination.is_symlink() or not destination.is_file() or digest(destination) != row['sha256']: raise ValueError('PRESET_CANDIDATE_CHANGED')
                else: shutil.copyfile(folder/row['snapshot'], destination)
            # Keep an inert, byte-identical original. The explicit native
            # restore action parses YAML with legacy-preset-convert.cjs before
            # creating a current bundle; never embed arbitrary YAML by indent.
            doc['presets'].append(dict(id=name,source='.agent-presets/'+name,candidate=candidate.relative_to(dsh).as_posix(),bundle=None,sha256=sha,target='@deepseek-ai/dsh-agent-preset',activated=False,requiresReview=True,generation=generation))
        operation, logical, actual = 'record-commit', None, folder
        doc.update(status='prepared',protectionComplete=True); write_json(folder/'prepare.json',doc)
        current = dict(version=2,generation=generation,startupId=startup_id,dshHome=str(dsh),**stamp)
        write_json(state/'current.json',current)
        # 公开的兼容索引只供只读清单，不作为控制依据。
        inert_directory(dsh,dsh/'.dsha-rc1-migration')
        write_json(dsh/'.dsha-rc1-migration/prepare.json',doc)
        emit('prepared',generation=generation,protectionComplete=True,settings=sum(x['kind']=='settings' for x in doc['sources']),presets=len(doc['presets']),sessions=len(doc['sessions']))
        return 0
    except Exception as error:
        doc.update(status='failed',protectionComplete=False); doc['warnings'].append(str(error) if isinstance(error,ValueError) else type(error).__name__)
        doc['failure'] = failure_details(error, dsh, operation, logical, actual)
        write_json(folder/'prepare.json',doc)
        emit('failed',generation=generation,protectionComplete=False,stage=operation,reason=doc['warnings'][-1],path=doc['failure']['path'],errorType=doc['failure']['errorType'],diagnostic='generations/'+generation+'/prepare.json',continuation='按迁移记录中的操作和路径检查；原件未删除')
        return 1


def publish_reuse(folder, prepared, current, receipt):
    """Bounded host checkpoint; legacy or damaged receipts grant no fast path."""
    generation = current.get('generation')
    if not re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}',str(generation)): return False
    if any(row.get('version') != 2 or row.get('generation') != generation for row in (prepared,current,receipt)): return False
    if prepared.get('status') != 'prepared' or prepared.get('protectionComplete') is not True or not same_input(prepared,current): return False
    if receipt.get('status') != 'committed' or any(receipt.get(key) is not True for key in ('sourcePreserved','settingsImported','protectionComplete')): return False
    checkpoint = {key:prepared[key] for key in ('version','generation','dshVersion','dshHome','dataRoot','inputs','status','protectionComplete')}
    target = folder/'reuse.json'
    if os.path.lexists(target):
        if target.is_symlink() or not target.is_file(): raise ValueError('REUSE_CHECKPOINT_TYPE')
        if target.stat().st_size <= 65536:
            try:
                if read_json(target) == checkpoint: return True
            except (ValueError,UnicodeError):
                emit('reuse-checkpoint-rebuilt',generation=generation,reason='REUSE_CHECKPOINT_FORMAT')
    write_json(target,checkpoint)
    return True


def finalize(root, state_root=None, startup_id='manual', approved=()):
    dsh=Path(os.path.abspath(os.path.join(root,'.dsh'))); state=state_path(root,state_root)
    if not (state/'current.json').is_file(): emit('missing-prepare',protectionComplete=False); return 1
    current=read_json(state/'current.json'); folder=state/'generations'/current['generation']; doc=read_json(folder/'prepare.json')
    if current['startupId'] != startup_id: emit('stale-startup',protectionComplete=False); return 1
    resolver=Resolver(dsh,approved); warnings=[]; preserved=True
    # 完成代次普通重启不反复哈希历史。首次 finalize 或 pending 明确迁移才核验。
    old=read_json(folder/'receipt.json') if (folder/'receipt.json').is_file() else {}
    if old.get('status')=='committed' and old.get('version')==2 and old.get('generation')==current['generation'] and all(old.get(key) is True for key in ('sourcePreserved','settingsImported','protectionComplete')):
        publish_reuse(folder,doc,current,old)
        emit('committed',generation=current['generation'],sourcePreserved=True,settingsImported=True,cached=True); return 0
    for row in doc['sources']:
        snapshot=folder/row['snapshot']
        if not snapshot.is_file() or snapshot.is_symlink() or digest(snapshot)!=row['sha256']:
            preserved=False; warnings.append('SNAPSHOT_CHANGED:'+row['path']); continue
        try:
            logical=dsh/row['path']
            if row['path']=='settings.yaml' and not os.path.lexists(logical): logical=dsh/'settings.yaml.imported'
            actual,links=resolver.resolve(logical)
            # profile 配置由 settings 正式写入。独立原件仍须在快照中，当前目标允许改变。
            if row['kind']=='profile': continue
            # 当前 V4 会话可续写，不承诺运行中旧快照等于活动文件；旧代日志不可改写。
            if row['kind']=='session' and re.search(r'session\.v4\.',row['path']): continue
            identity = [str(os.stat(actual).st_dev), str(os.stat(actual).st_ino)]
            if row.get('sourceIdentity') and identity != row['sourceIdentity']:
                raise ValueError('SOURCE_IDENTITY_CHANGED')
            if digest(actual)!=row['sha256']: raise ValueError('SOURCE_CHANGED')
        except (OSError,ValueError): preserved=False; warnings.append('SOURCE_MISSING_OR_CHANGED:'+row['path'])
    source_settings=any(row['kind']=='settings' for row in doc['sources'])
    result=read_json(folder/'settings-result.json') if (folder/'settings-result.json').is_file() else None
    imported=not source_settings or bool(result and result.get('generation')==current['generation'] and result.get('status')=='verified')
    if not imported: warnings.append('SETTINGS_PENDING_READBACK_OR_REVIEW')
    old_sessions=[row for row in doc['sessions'] if not re.search(r'session\.v4\.',row['path'])]
    status='failed' if not preserved else ('pending' if not imported else 'committed')
    receipt=dict(version=2,dshVersion=VERSION,generation=current['generation'],startupId=startup_id,status=status,sourcePreserved=preserved,protectionComplete=preserved,settingsImported=imported,settingsStatus='verified' if imported else 'pending',sessionsStatus='preserved-awaiting-runtime-open' if old_sessions else 'no-legacy-input',sessionCompatibilityVerified=False if old_sessions else None,presets=len(doc['presets']),sessions=len(doc['sessions']),warnings=warnings,verifiedAt=int(time.time()))
    write_json(folder/'receipt.json',receipt); write_json(dsh/'.dsha-rc1-migration/receipt.json',receipt)
    if status == 'committed': publish_reuse(folder,doc,current,receipt)
    emit(status,**{k:v for k,v in receipt.items() if k!='status'},continuation='保留数据 → rc1 迁移记录；待审阅设置/预设不影响进入已就绪网页')
    return 0 if preserved else 1


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('command',choices=('prepare','finalize')); parser.add_argument('--root',default='/root'); parser.add_argument('--state-root',default='/run/dsha-rc1-state'); parser.add_argument('--startup-id',required=True); parser.add_argument('--approved-root',action='append',default=[]); args=parser.parse_args()
    try: return (prepare if args.command=='prepare' else finalize)(args.root,args.state_root,args.startup_id,args.approved_root)
    except Exception as error:
        emit('error',protectionComplete=False,reason=str(error) if isinstance(error,ValueError) else type(error).__name__,operation=getattr(error,'operation',args.command),path=getattr(error,'logical',None)); return 1

if __name__=='__main__': raise SystemExit(main())
