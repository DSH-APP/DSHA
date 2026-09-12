#!/usr/bin/env python3
"""有界启动配置快照。固定文件白名单；不读取会话、凭据文件、依赖树或工作区。"""
import base64
import hashlib
import importlib.util
import json
import os
import stat
import sys
import time
import uuid

spec = importlib.util.spec_from_file_location('register', os.path.join(os.path.dirname(__file__), 'register-builtin-plugins.py'))
register = importlib.util.module_from_spec(spec)
spec.loader.exec_module(register)
HOME = register.local(register.DSH_HOME)
STORE = os.path.join(HOME, 'dsha-startup-checkpoints')
FILES = ('profiles/web/package.json', 'profiles/web/cordis.patch.yml',
         'profiles/web/pnpm-workspace.yaml', 'profiles/web/pnpm-lock.yaml', 'settings.yaml', 'cordis.patch.yml')
LIMIT = 4 * 1024 * 1024
SNAPSHOT_LIMIT = 36 * 1024 * 1024
SLOTS = tuple('healthy-' + str(i) for i in range(1, 4))
SAFETY = tuple('before-' + str(i) for i in range(1, 4))


def checked(path):
    # 不跟随配置或快照的软链接；配置被重定向时保留原物并给出可见错误。
    path = os.path.abspath(path)
    cursor = path
    while True:
        if os.path.lexists(cursor) and stat.S_ISLNK(os.lstat(cursor).st_mode):
            raise ValueError('RECOVERY_LINK')
        parent = os.path.dirname(cursor)
        if parent == cursor:
            return path
        cursor = parent


def read(path, limit=LIMIT):
    checked(path)
    try:
        fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
    except FileNotFoundError:
        return None
    with os.fdopen(fd, 'rb') as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_size > limit:
            raise ValueError('RECOVERY_SIZE')
        data = stream.read(limit + 1)
        after = os.fstat(stream.fileno())
        if len(data) > limit or (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise ValueError('RECOVERY_CHANGED')
        return data


def atomic(path, data):
    checked(path)
    os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
    checked(os.path.dirname(path))
    temporary = path + '.' + uuid.uuid4().hex + '.tmp'
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        checked(path)
        os.replace(temporary, path)
        # Windows 本地单测不提供目录 fsync；Linux/Android 容器必须持久化目录项。
        if os.name != 'nt':
            directory = os.open(os.path.dirname(path), os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def write_json(path, doc):
    atomic(path, json.dumps(doc, ensure_ascii=False).encode('utf-8'))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def pack(data):
    return None if data is None else {'sha256': digest(data), 'data': base64.b64encode(data).decode('ascii')}


def unpack(record):
    if record is None:
        return None
    data = base64.b64decode(record['data'], validate=True)
    if len(data) > LIMIT or digest(data) != record['sha256']:
        raise ValueError('RECOVERY_CHECKSUM')
    return data


def capture():
    original = {name: read(os.path.join(HOME, name)) for name in FILES}
    # 固定文件重复读取，发现并发编辑就放弃这次快照，不保存混合状态。
    if any(read(os.path.join(HOME, name)) != data for name, data in original.items()):
        raise ValueError('RECOVERY_CHANGED')
    return {'version': 1, 'id': uuid.uuid4().hex, 'created': int(time.time() * 1000),
            'dshVersion': '0.1.5-rc.2', 'files': {name: pack(data) for name, data in original.items()}}


def load(slot):
    if slot not in SLOTS + SAFETY:
        raise ValueError('RECOVERY_SLOT')
    raw = read(os.path.join(STORE, slot + '.json'), SNAPSHOT_LIMIT)
    if raw is None:
        return None
    doc = json.loads(raw)
    if doc.get('version') != 1 or set(doc['files']) != set(FILES):
        raise ValueError('RECOVERY_FORMAT')
    for record in doc['files'].values():
        unpack(record)
    return doc


def save(doc, slots):
    records = [(slot, load(slot)) for slot in slots]
    target = min(records, key=lambda row: row[1]['created'] if row[1] else 0)[0]
    write_json(os.path.join(STORE, target + '.json'), doc)
    return target


def metadata(slot, doc):
    return {'slot': slot, 'id': doc['id'], 'created': doc['created'], 'dshVersion': doc['dshVersion'],
            'files': [name for name, data in doc['files'].items() if data is not None]}


def listing():
    records = []
    for slot in SLOTS + SAFETY:
        try:
            doc = load(slot)
            if doc:
                records.append(metadata(slot, doc))
        except Exception:
            records.append({'slot': slot, 'invalid': True})
    return {'status': 'ok', 'snapshots': records, 'pending': os.path.lexists(os.path.join(STORE, 'pending.json'))}


def apply_files(files):
    if not set(files).issubset(FILES):
        raise ValueError('RECOVERY_FORMAT')
    values = {name: unpack(record) for name, record in files.items()}
    for name in values:
        read(os.path.join(HOME, name))  # 写任何文件前先核验所有目标。
    for name, data in values.items():
        path = os.path.join(HOME, name)
        if data is None:
            if os.path.exists(path):
                checked(path)
                os.unlink(path)
        else:
            atomic(path, data)
    if any(read(os.path.join(HOME, name)) != data for name, data in values.items()):
        raise ValueError('RECOVERY_CHECKSUM')


def recover():
    path = os.path.join(STORE, 'pending.json')
    raw = read(path, SNAPSHOT_LIMIT)
    if raw is not None:
        doc = json.loads(raw)
        if doc.get('version') != 1 or set(doc['files']) != set(FILES):
            raise ValueError('RECOVERY_FORMAT')
        apply_files(doc['files'])
        os.unlink(path)
    return {'status': 'ok', 'message': 'RECOVERY_ROLLED_BACK'}


def change(files):
    if os.path.lexists(os.path.join(STORE, 'pending.json')):
        raise ValueError('RECOVERY_PENDING')
    before = capture()
    save(before, SAFETY)
    journal = os.path.join(STORE, 'pending.json')
    write_json(journal, before)
    try:
        apply_files(files)
        atomic(os.path.join(STORE, 'skip-healthy'), b'1')
        os.unlink(journal)
    except Exception:
        recover()
        raise
    return {'status': 'ok', 'message': 'RECOVERY_CONFIG_SAVED'}


def execute(request):
    command = request['command']
    if command == 'list':
        return listing()
    if command == 'before':
        return {'status': 'ok', 'slot': save(capture(), SAFETY)}
    if command == 'prepare':
        doc = capture()
        doc['startupId'] = request['startupId']
        write_json(os.path.join(STORE, 'candidate.json'), doc)
        return {'status': 'ok'}
    if command == 'healthy':
        if os.path.lexists(os.path.join(STORE, 'pending.json')):
            raise ValueError('RECOVERY_PENDING')
        skip = os.path.join(STORE, 'skip-healthy')
        if read(skip) is not None:
            os.unlink(skip)
            return {'status': 'ok', 'skipped': True}
        doc = capture()
        if 'startupId' in request:
            raw = read(os.path.join(STORE, 'candidate.json'), SNAPSHOT_LIMIT)
            candidate = json.loads(raw) if raw is not None else {}
            if candidate.get('startupId') != request['startupId'] or candidate.get('files') != doc['files']:
                raise ValueError('RECOVERY_CHANGED')
            doc = candidate
        slot = save(doc, SLOTS)
        return {'status': 'ok', 'snapshot': metadata(slot, doc)}
    if command == 'restore':
        doc = load(request['slot'])
        if not doc or doc['id'] != request['id']:
            raise ValueError('RECOVERY_CHANGED')
        return change(doc['files'])
    if command == 'new':
        target = request['target']
        if target == 'web':
            manifest = register.new_manifest({})
            files = {'profiles/web/package.json': pack(json.dumps(manifest).encode()),
                     'profiles/web/cordis.patch.yml': pack(b'[]\n')}
        elif target in ('settings.yaml', 'cordis.patch.yml'):
            files = {target: pack(b'{}\n' if target == 'settings.yaml' else b'[]\n')}
        else:
            raise ValueError('RECOVERY_FORMAT')
        return change(files)
    if command == 'recover':
        return recover()
    raise ValueError('RECOVERY_FORMAT')


if __name__ == '__main__':
    try:
        request = json.loads(sys.argv[1])
        with register.operation_lock():
            result = execute(request)
    except Exception as error:
        result = {'status': 'error', 'message': str(error)}
    print('STARTUP_RECOVERY_RESULT=' + json.dumps(result, ensure_ascii=False), flush=True)
