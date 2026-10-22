#!/usr/bin/env python3
"""插件现有替换流程的持久化日志；恢复只接受固定目标与已记录的原件摘要。"""
import contextlib
import hashlib
import json
import os
import re
import shutil
import stat
import uuid

DELETE_FINGERPRINT = 'DSHA_DELETE_OBJECT_NOFOLLOW_V1'
CAS_OPERATION = 'materialize-pnpm-cas-files-v1'
CAS_FINGERPRINT = 'DSHA_PNPM_CAS_COPY_V1'
CAS_BATCH_FILES = 512


class DeleteObjects:
    """Fingerprint retained objects, never resolving a link's contents.

    Linux/Android uses descriptor-relative NOFOLLOW traversal and renameat2.
    Windows host fixtures additionally check every path component and opened
    file identity; Windows rename already refuses an existing destination.
    This is deliberately separate from the strict loadable dependency graph.
    """
    def __init__(self, home, check):
        self.home = os.path.abspath(home)
        self.check = check
        self.relative = os.name != 'nt'

    @staticmethod
    def identity(info):
        return [info.st_dev, info.st_ino, stat.S_IMODE(info.st_mode), stat.S_IFMT(info.st_mode)]

    @classmethod
    def stable(cls, before, after):
        return cls.identity(before) == cls.identity(after) and (
            before.st_size, before.st_mtime_ns, before.st_ctime_ns, before.st_nlink
        ) == (after.st_size, after.st_mtime_ns, after.st_ctime_ns, after.st_nlink)

    @staticmethod
    def reparse(info):
        return bool(getattr(info, 'st_file_attributes', 0) & 0x400)

    def stat(self, parent, name):
        return os.stat(name, dir_fd=parent, follow_symlinks=False) if self.relative else os.lstat(os.path.join(parent, name))

    def readlink(self, parent, name):
        return os.readlink(name, dir_fd=parent) if self.relative else os.readlink(os.path.join(parent, name))

    def open(self, parent, name, directory=False):
        flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0)
        if directory:
            flags |= getattr(os, 'O_DIRECTORY', 0)
        return os.open(name, flags, dir_fd=parent) if self.relative else os.open(os.path.join(parent, name), flags | getattr(os, 'O_BINARY', 0))

    @contextlib.contextmanager
    def parent(self, path):
        path = os.path.abspath(path)
        if os.path.commonpath([self.home, path]) != self.home or path == self.home:
            raise ValueError('插件删除对象不在私有目录内，全部现场保留')
        descriptors, chain = [], []
        try:
            if self.relative:
                current = os.open(os.path.sep, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                descriptors.append(current)
                parts = path.split(os.path.sep)[1:]
                for part in parts[:-1]:
                    self.check()
                    try:
                        before = self.stat(current, part)
                    except FileNotFoundError:
                        yield None
                        return
                    if not stat.S_ISDIR(before.st_mode) or self.reparse(before):
                        raise ValueError('插件删除父目录含链接或类型异常，全部现场保留')
                    child = self.open(current, part, True)
                    descriptors.append(child)
                    if self.identity(before) != self.identity(os.fstat(child)):
                        raise ValueError('插件删除父目录在核验期间变化，全部现场保留')
                    chain.append((current, part, self.identity(before)))
                    current = child
                def verify():
                    for parent, name, wanted in chain:
                        if self.identity(self.stat(parent, name)) != wanted:
                            raise ValueError('插件删除父目录在核验期间变化，全部现场保留')
                verify()
                yield current, parts[-1], verify
                verify()
            else:
                parent = os.path.dirname(path)
                current = os.path.splitdrive(parent)[0] + os.path.sep
                parts = parent[len(current):].split(os.path.sep)
                for part in filter(None, parts):
                    self.check(); current = os.path.join(current, part)
                    try:
                        info = os.lstat(current)
                    except FileNotFoundError:
                        yield None
                        return
                    if not stat.S_ISDIR(info.st_mode) or self.reparse(info):
                        raise ValueError('插件删除父目录含链接或类型异常，全部现场保留')
                    chain.append((current, self.identity(info)))
                def verify():
                    for name, wanted in chain:
                        info = os.lstat(name)
                        if self.identity(info) != wanted or self.reparse(info):
                            raise ValueError('插件删除父目录在核验期间变化，全部现场保留')
                verify()
                yield parent, os.path.basename(path), verify
                verify()
        finally:
            for descriptor in reversed(descriptors):
                os.close(descriptor)

    def snapshot(self, parent, name, verify, budget=None, depth=0):
        self.check(); verify()
        try:
            before = self.stat(parent, name)
        except FileNotFoundError:
            return {'kind': 'missing'}
        budget = [0, 0] if budget is None else budget
        budget[0] += 1
        if budget[0] > 100000 or depth > 64:
            raise ValueError('插件删除对象超过核验上限，全部现场保留')
        identity = self.identity(before)
        if stat.S_ISLNK(before.st_mode):
            target = self.readlink(parent, name)
            value = {'kind': 'link', 'target': target, 'identity': identity}
        elif self.reparse(before):
            raise ValueError('插件删除对象含不支持的重解析点，全部现场保留')
        elif stat.S_ISDIR(before.st_mode):
            if self.relative:
                descriptor = self.open(parent, name, True)
                child_parent = descriptor
            else:
                descriptor = None
                child_parent = os.path.join(parent, name)
            try:
                opened = os.fstat(descriptor) if self.relative else os.lstat(child_parent)
                if not self.stable(before, opened):
                    raise ValueError('插件删除对象在打开期间变化，全部现场保留')
                verify()
                names = sorted(os.listdir(child_parent))
                def child_verify():
                    verify()
                    if self.identity(self.stat(parent, name)) != identity:
                        raise ValueError('插件删除目录在核验期间变化，全部现场保留')
                value_hash = hashlib.sha256()
                for child in names:
                    record = [child, self.snapshot(child_parent, child, child_verify, budget, depth + 1)]
                    if record[1]['kind'] == 'missing':
                        raise ValueError('插件删除对象在核验期间变化，全部现场保留')
                    value_hash.update(json.dumps(record, ensure_ascii=True, separators=(',', ':')).encode() + b'\n')
                if sorted(os.listdir(child_parent)) != names or not self.stable(before, os.fstat(descriptor) if self.relative else os.lstat(child_parent)):
                    raise ValueError('插件删除目录在核验期间变化，全部现场保留')
                value = {'kind': 'directory', 'sha256': value_hash.hexdigest(), 'identity': identity}
            finally:
                if descriptor is not None:
                    os.close(descriptor)
        elif stat.S_ISREG(before.st_mode):
            descriptor = self.open(parent, name)
            try:
                if not self.stable(before, os.fstat(descriptor)):
                    raise ValueError('插件删除文件在打开期间变化，全部现场保留')
                verify()
                value_hash = hashlib.sha256()
                while True:
                    self.check()
                    block = os.read(descriptor, 65536)
                    if not block:
                        break
                    budget[1] += len(block)
                    if budget[1] > 2 * 1024 * 1024 * 1024:
                        raise ValueError('插件删除对象超过核验上限，全部现场保留')
                    value_hash.update(block)
                if not self.stable(before, os.fstat(descriptor)):
                    raise ValueError('插件删除文件在读取期间变化，全部现场保留')
                value = {'kind': 'file', 'sha256': value_hash.hexdigest(), 'size': before.st_size, 'identity': identity}
            finally:
                os.close(descriptor)
        else:
            raise ValueError('插件删除对象含特殊文件，全部现场保留')
        verify()
        if not self.stable(before, self.stat(parent, name)):
            raise ValueError('插件删除对象在核验期间变化，全部现场保留')
        return value

    def state(self, path):
        with self.parent(path) as handle:
            return {'kind': 'missing'} if handle is None else self.snapshot(*handle)

    def move(self, source, target, wanted, source_stamp=None):
        with self.parent(source) as original, self.parent(target) as retained, contextlib.ExitStack() as held:
            if original is None or retained is None:
                raise ValueError('插件删除移动父目录缺失，全部现场保留')
            if self.snapshot(*original) != wanted or self.snapshot(*retained)['kind'] != 'missing':
                raise ValueError('插件原件在删除期间变化，全部现场保留')
            if source_stamp is not None:
                info = self.stat(original[0], original[1])
                if [info.st_mtime_ns, info.st_ctime_ns] != source_stamp:
                    raise ValueError('旧 pnpm 链接身份时间在核对后变化，全部现场保留')
                if self.relative:
                    # Keep the selected link inode alive across rename. A
                    # concurrent unlink/recreate cannot reuse that inode and
                    # masquerade as the prepared object in the postcheck.
                    if not hasattr(os, 'O_PATH'):
                        raise ValueError('系统缺少旧 pnpm 链接身份保留能力，原件已保留')
                    descriptor = os.open(original[1], os.O_PATH | os.O_NOFOLLOW, dir_fd=original[0])
                    held.callback(os.close, descriptor)
                    opened = os.fstat(descriptor)
                    if self.identity(opened) != wanted['identity'] or [opened.st_mtime_ns, opened.st_ctime_ns] != source_stamp:
                        raise ValueError('旧 pnpm 链接在保留身份期间变化，全部现场保留')
            self.check(); original[2](); retained[2]()
            if self.relative:
                import ctypes
                library = ctypes.CDLL(None, use_errno=True)
                rename = getattr(library, 'renameat2', None)
                if rename is None:
                    raise ValueError('系统缺少原子保留移动能力，全部现场保留')
                rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
                rename.restype = ctypes.c_int
                if rename(original[0], os.fsencode(original[1]), retained[0], os.fsencode(retained[1]), 1) != 0:
                    number = ctypes.get_errno()
                    raise OSError(number, os.strerror(number))
                os.fsync(original[0]); os.fsync(retained[0])
            else:
                os.rename(source, target)
            # A swapped source is retained, never erased or restored over a later
            # object. The caller's recovery also requires this exact identity.
            if self.snapshot(*retained) != wanted or self.snapshot(*original)['kind'] != 'missing':
                raise ValueError('插件原件在移动期间变化，全部现场保留')


def sha(path):
    value = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            value.update(block)
    return value.hexdigest()


def sync_directory(path):
    if os.name == 'nt':
        return
    descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


class Transactions:
    def __init__(self, manager):
        self.g = manager
        self.home = manager['local'](manager['DSH_HOME'])
        self.directory = os.path.join(self.home, 'plugin-install-operations')

    def path(self, operation):
        if str(uuid.UUID(operation)) != operation:
            raise ValueError('插件事务标识无效')
        path = os.path.join(self.directory, operation)
        if os.path.islink(path) or os.path.commonpath([os.path.realpath(self.home), os.path.realpath(path)]) != os.path.realpath(self.home):
            raise ValueError('插件事务目录越界')
        return path

    def locate(self, operation):
        active = self.path(operation)
        retained = os.path.join(self.directory, 'completed', operation)
        history = os.path.join(self.directory, 'completed')
        if os.path.islink(history) or os.path.islink(retained):
            raise ValueError('插件事务历史目录越界，原件已保留')
        a, h = os.path.lexists(active), os.path.lexists(retained)
        if a and h:
            raise ValueError('插件活动与完成日志重复，原件已保留')
        if not a and not h:
            raise ValueError('插件事务记录不存在')
        selected = active if a else retained
        if not os.path.isdir(selected):
            raise ValueError('插件事务目录类型异常，原件已保留')
        return selected

    def pending(self):
        if not os.path.isdir(self.directory):
            return []
        names = sorted(os.listdir(self.directory))
        result = []
        for name in names:
            if name in ('completed', '.completed-proof-v1.json'):
                continue
            path = self.path(name)
            if os.path.lexists(os.path.join(self.directory, 'completed', name)):
                raise ValueError('插件活动与完成日志重复，原件已保留')
            if not os.path.isfile(os.path.join(path, 'plan.json')):
                continue
            if self.marker(path, 'committed') or self.marker(path, 'rolled-back'):
                continue
            result.append(path)
        return result

    def archive_terminal(self):
        history = os.path.join(self.directory, 'completed')
        if os.path.lexists(history) and (os.path.islink(history) or not os.path.isdir(history)):
            raise ValueError('插件完成历史目录类型异常')
        for name in sorted(os.listdir(self.directory)):
            if name in ('completed', '.completed-proof-v1.json'):
                continue
            path = self.path(name)
            if not os.path.isdir(path):
                raise ValueError('插件事务目录类型异常，原件已保留')
            if not (self.marker(path, 'committed') or self.marker(path, 'rolled-back')):
                continue
            plan_file = os.path.join(path, 'plan.json')
            if self.state(plan_file)['kind'] != 'file':
                raise ValueError('插件完成日志计划类型异常，原件已保留')
            plan = self.g['read_json'](plan_file, None)
            if not isinstance(plan, dict) or plan.get('id') != name or plan.get('format') not in (1, 2, 3):
                raise ValueError('插件完成日志无有效计划，原件已保留')
            if self.marker(path, 'committed') and self.marker(path, 'rolled-back'):
                raise ValueError('插件事务终态冲突，原件已保留')
            os.makedirs(history, exist_ok=True)
            target = os.path.join(history, name)
            if os.path.lexists(target):
                raise ValueError('插件活动与完成日志重复，原件已保留')
            os.replace(path, target)
            sync_directory(self.directory); sync_directory(history)

    @staticmethod
    def marker(path, name):
        marker = os.path.join(path, name)
        if not os.path.lexists(marker):
            return False
        if os.path.islink(marker) or not os.path.isfile(marker) or os.path.getsize(marker) > 128:
            raise ValueError('插件事务标记无效，原件已保留')
        with open(marker, encoding='ascii') as stream:
            if stream.read() != os.path.basename(path) + '\n' + name + '\n':
                raise ValueError('插件事务标记无效，原件已保留')
        return True

    @staticmethod
    def mark(path, name):
        if Transactions.marker(path, name):
            return
        with open(os.path.join(path, name), 'x', encoding='ascii') as stream:
            stream.write(os.path.basename(path) + '\n' + name + '\n'); stream.flush(); os.fsync(stream.fileno())

    @contextlib.contextmanager
    def workspace(self):
        os.makedirs(self.directory, exist_ok=True)
        self.archive_terminal()
        if self.pending():
            raise ValueError('存在中断的插件事务，请先恢复')
        # Historical committed and failed candidates remain owned records;
        # their total count must not permanently disable future installs.
        work = self.path(str(uuid.uuid4())); os.mkdir(work)
        # 成功/失败均保留已归属日志和历史原件；不让 TemporaryDirectory 清理唯一旧版本。
        try:
            yield work
        finally:
            # Callers leave the work scope under the plugin operation lock only
            # after their last read of the committed/rolled-back directory.
            self.archive_terminal()

    def targets(self, name):
        if not self.g['builtin'].valid_name(name):
            raise ValueError('插件事务包名无效')
        return {'manifest': self.g['local'](self.g['builtin'].MANIFEST), 'sources': self.g['local'](self.g['SOURCES']),
                'marker': self.g['builtin'].marker_path(name), 'activation': os.path.join(self.home, 'plugin-activations.json')}

    def state(self, path):
        if not os.path.lexists(path):
            return {'kind': 'missing'}
        info = os.lstat(path)
        if stat.S_ISLNK(info.st_mode):
            return {'kind': 'link', 'target': os.readlink(path)}
        if not stat.S_ISREG(info.st_mode) or info.st_size > 8 * 1024 * 1024:
            raise ValueError('插件事务配置类型或大小异常')
        return {'kind': 'file', 'sha256': sha(path), 'mode': stat.S_IMODE(info.st_mode)}

    def delete_targets(self, name):
        if not self.g['builtin'].valid_name(name):
            raise ValueError('插件事务包名无效')
        paths = {'source': self.g['local'](os.path.join(self.g['PLUGIN_SRC'], name)),
                 'link': os.path.join(self.g['local'](self.g['builtin'].NODE_MODULES), name),
                 'marker': self.g['builtin'].marker_path(name),
                 'history': self.g['lifecycle']().history_path(name)}
        home = os.path.realpath(self.home)
        scopes = {'source': self.g['local'](self.g['PLUGIN_SRC']),
                  'link': self.g['local'](self.g['builtin'].NODE_MODULES),
                  'marker': self.g['local'](self.g['builtin'].NODE_MODULES),
                  'history': self.home}
        for key, path in paths.items():
            scope = os.path.realpath(scopes[key])
            parent = os.path.realpath(os.path.dirname(path))
            if os.path.commonpath([home, scope]) != home or os.path.commonpath([scope, parent]) != scope:
                raise ValueError('插件目录指向安装目录以外，已取消删除：' + name)
        return paths

    @staticmethod
    def delete_algorithm(plan):
        algorithm = plan.get('objectFingerprint')
        if plan.get('format') != 3 or algorithm not in (None, DELETE_FINGERPRINT):
            raise ValueError('插件删除对象指纹算法无效，全部现场保留')
        return algorithm

    def object_state(self, path, algorithm=None):
        if algorithm == DELETE_FINGERPRINT:
            return DeleteObjects(self.home, self.g['check_cancel']).state(path)
        if algorithm is not None:
            raise ValueError('插件删除对象指纹算法无效，全部现场保留')
        # Unmarked historical format-3 plans retain the original strict digest.
        # Never reinterpret their digest as the new NOFOLLOW object fingerprint.
        if not os.path.lexists(path):
            return {'kind': 'missing'}
        info = os.lstat(path)
        if stat.S_ISLNK(info.st_mode):
            return {'kind': 'link', 'target': os.readlink(path)}
        if stat.S_ISDIR(info.st_mode):
            return {'kind': 'directory', 'sha256': self.g['dependencies']().tree(path, self.g['check_cancel'])[0]}
        return self.state(path)

    def cas_module_path(self, relative):
        if not isinstance(relative, str) or len(relative) > 4096 or '\\' in relative or ':' in relative \
                or any(part in ('', '.', '..') for part in relative.split('/')):
            raise ValueError('旧 pnpm 链接路径无效，全部现场保留')
        return os.path.join(self.g['local'](self.g['builtin'].NODE_MODULES), *relative.split('/'))

    def cas_links(self):
        """Only inspect this profile's modules; directory links are never entered."""
        root = self.g['local'](self.g['builtin'].NODE_MODULES)
        store = self.g['local']('/root/.local/share/pnpm/store/v10/files')
        reader = DeleteObjects(self.home, self.g['check_cancel'])
        found, budget = [], [0]
        def walk(parent, name, verify, prefix, depth):
            self.g['check_cancel'](); verify()
            before = reader.stat(parent, name)
            if not stat.S_ISDIR(before.st_mode) or reader.reparse(before) or depth > 64:
                raise ValueError('旧 pnpm 模块目录类型或深度异常，全部现场保留')
            descriptor = reader.open(parent, name, True) if reader.relative else None
            directory = descriptor if reader.relative else os.path.join(parent, name)
            try:
                if not reader.stable(before, os.fstat(descriptor) if reader.relative else os.lstat(directory)):
                    raise ValueError('旧 pnpm 模块目录在打开期间变化，全部现场保留')
                def nested_verify():
                    verify()
                    if reader.identity(reader.stat(parent, name)) != reader.identity(before):
                        raise ValueError('旧 pnpm 模块父目录在核验期间变化，全部现场保留')
                names = sorted(os.listdir(directory))
                for child in names:
                    self.g['check_cancel'](); nested_verify()
                    budget[0] += 1
                    if budget[0] > 100000:
                        raise ValueError('旧 pnpm 模块条目超过核验上限，全部现场保留')
                    info = reader.stat(directory, child)
                    relative = prefix + child
                    if stat.S_ISLNK(info.st_mode):
                        # Native enable/disable markers are intent, not package
                        # cache entries, even if a user made one a CAS link.
                        if relative.endswith('.disabled') and ('/' not in relative or relative.startswith('@') and relative.count('/') == 1):
                            continue
                        old = reader.snapshot(directory, child, nested_verify)
                        path = self.cas_module_path(relative)
                        target = self.g['local'](old['target']) if os.path.isabs(old['target']) else os.path.normpath(os.path.join(os.path.dirname(path), old['target']))
                        target = os.path.abspath(target)
                        if os.path.commonpath([os.path.abspath(store), target]) != os.path.abspath(store):
                            continue
                        cas_relative = os.path.relpath(target, store).replace(os.sep, '/')
                        match = re.fullmatch(r'([a-f0-9]{2})/([a-f0-9]{126})(-exec)?', cas_relative)
                        if not match:
                            raise ValueError('旧 pnpm CAS 链接名称无效，原件已保留')
                        found.append({'path': relative, 'before': old, 'beforeStat': [info.st_mtime_ns, info.st_ctime_ns], 'cas': cas_relative,
                                      'sha512': match[1] + match[2], 'mode': 0o755 if match[3] else 0o644})
                    elif stat.S_ISDIR(info.st_mode) and not reader.reparse(info):
                        walk(directory, child, nested_verify, relative + '/', depth + 1)
                    elif not stat.S_ISREG(info.st_mode) or reader.reparse(info):
                        raise ValueError('旧 pnpm 模块含特殊文件，全部现场保留')
                if names != sorted(os.listdir(directory)) or not reader.stable(before, os.fstat(descriptor) if reader.relative else os.lstat(directory)):
                    raise ValueError('旧 pnpm 模块目录在核验期间变化，全部现场保留')
            finally:
                if descriptor is not None:
                    os.close(descriptor)
        with reader.parent(root) as handle:
            if handle is None:
                return []
            try:
                root_info = reader.stat(handle[0], handle[1])
            except FileNotFoundError:
                return []
            if not stat.S_ISDIR(root_info.st_mode):
                raise ValueError('旧 pnpm 模块根不是私有目录，原件已保留')
            walk(*handle, '', 0)
        return found

    def copy_cas_file(self, entry, candidate, total):
        store = self.g['local']('/root/.local/share/pnpm/store/v10/files')
        target = os.path.join(store, *entry['cas'].split('/'))
        # The store is outside DSH_HOME but still under the app's private guest
        # /root. Each parent and the CAS leaf are opened without following links.
        reader = DeleteObjects(self.g['local']('/root'), self.g['check_cancel'])
        private = DeleteObjects(self.home, self.g['check_cancel'])
        with reader.parent(target) as handle, private.parent(candidate) as destination:
            if handle is None or destination is None:
                raise ValueError('旧 pnpm CAS 原件缺失，未修改模块')
            before = reader.stat(handle[0], handle[1])
            if not stat.S_ISREG(before.st_mode) or reader.reparse(before):
                raise ValueError('旧 pnpm CAS 原件不是常规文件，未修改模块')
            source = reader.open(handle[0], handle[1])
            output = None
            try:
                if not reader.stable(before, os.fstat(source)):
                    raise ValueError('旧 pnpm CAS 原件在打开期间变化，未修改模块')
                handle[2]()
                flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0)
                destination[2]()
                output = os.open(destination[1], flags, entry['mode'], dir_fd=destination[0]) if private.relative \
                    else os.open(candidate, flags, entry['mode'])
                digest = hashlib.sha512()
                while True:
                    self.g['check_cancel']()
                    block = os.read(source, 65536)
                    if not block:
                        break
                    total[0] += len(block)
                    if total[0] > 2 * 1024 * 1024 * 1024:
                        raise ValueError('旧 pnpm CAS 总量超过核验上限，原件已保留')
                    digest.update(block)
                    view = memoryview(block)
                    while view:
                        count = os.write(output, view)
                        if count <= 0:
                            raise OSError('旧 pnpm CAS 候选写入未完成')
                        view = view[count:]
                if digest.hexdigest() != entry['sha512'] or not reader.stable(before, os.fstat(source)) \
                        or not reader.stable(before, reader.stat(handle[0], handle[1])):
                    raise ValueError('旧 pnpm CAS 内容摘要不匹配或原件变化，未修改模块')
                handle[2]()
                os.fchmod(output, entry['mode']) if hasattr(os, 'fchmod') else os.chmod(candidate, entry['mode'])
                os.fsync(output)
                destination[2]()
            finally:
                os.close(source)
                if output is not None:
                    os.close(output)

    def materialize_cas_links(self):
        # Caller holds the normal data -> profile writer lock, before Web spawn.
        os.makedirs(self.directory, exist_ok=True)
        self.archive_terminal()
        if self.pending():
            raise ValueError('存在中断的插件事务，请先恢复；不会自动重放旧 pnpm 修复')
        entries = self.cas_links()
        if not entries:
            return {'changed': False, 'migratedFiles': 0, 'operationId': ''}
        # Each format-3 plan remains readable by the native history reader's
        # 2-MiB / 100000-field limits, including profiles with many old links.
        # Earlier batches are byte-equivalent committed copies; any interrupted
        # batch blocks the next start and is never automatically replayed.
        total, migrated, operation = [0], 0, ''
        for offset in range(0, len(entries), CAS_BATCH_FILES):
            copied = self.materialize_cas_batch(entries[offset:offset + CAS_BATCH_FILES], total)
            migrated += copied['migratedFiles']
            operation = copied['operationId']
        return {'changed': True, 'migratedFiles': migrated, 'operationId': operation}

    def materialize_cas_batch(self, entries, total):
        with self.workspace() as work:
            for index, entry in enumerate(entries):
                candidate = os.path.join(work, 'new-' + str(index))
                self.copy_cas_file(entry, candidate, total)
                entry['after'] = self.object_state(candidate, DELETE_FINGERPRINT)
                self.g['progress']('copy-links', '正在核对旧 pnpm 内容链接', index + 1, len(entries))
            plan = {'format': 3, 'operation': CAS_OPERATION, 'casFingerprint': CAS_FINGERPRINT,
                    'objectFingerprint': DELETE_FINGERPRINT, 'id': os.path.basename(work),
                    'name': 'dsha-pnpm-cas-copy', 'profile': 'web', 'entries': entries}
            if len(json.dumps(plan, ensure_ascii=False, indent=2).encode('utf8')) > 2 * 1024 * 1024:
                raise ValueError('旧 pnpm 修复日志超过核验上限，未修改模块')
            self.g['write_json'](os.path.join(work, 'plan.json'), plan)
            sync_directory(work)
            self.boundary('cas-prepared')
            reader = DeleteObjects(self.home, self.g['check_cancel'])
            # Verify every selected old link before publishing the first copy.
            for entry in entries:
                if reader.state(self.cas_module_path(entry['path'])) != entry['before']:
                    raise ValueError('旧 pnpm 链接在核对后变化，全部现场保留')
                with reader.parent(self.cas_module_path(entry['path'])) as handle:
                    info = reader.stat(handle[0], handle[1])
                    if [info.st_mtime_ns, info.st_ctime_ns] != entry['beforeStat']:
                        raise ValueError('旧 pnpm 链接身份时间在核对后变化，全部现场保留')
            for index, entry in enumerate(entries):
                path = self.cas_module_path(entry['path'])
                reader.move(path, os.path.join(work, 'before-' + str(index)), entry['before'], source_stamp=entry['beforeStat'])
                self.boundary('cas-old-' + str(index))
                reader.move(os.path.join(work, 'new-' + str(index)), path, entry['after'])
                self.boundary('cas-new-' + str(index))
            self.mark(work, 'committed'); sync_directory(work)
            self.boundary('cas-committed')
            return {'changed': True, 'migratedFiles': len(entries), 'operationId': os.path.basename(work)}

    def recover_cas_links(self, work, plan):
        if plan.get('format') != 3 or plan.get('operation') != CAS_OPERATION or plan.get('casFingerprint') != CAS_FINGERPRINT \
                or plan.get('objectFingerprint') != DELETE_FINGERPRINT or plan.get('profile') != 'web' \
                or not isinstance(plan.get('entries'), list) or not 0 < len(plan['entries']) <= 100000:
            raise ValueError('旧 pnpm 修复日志算法或范围无效，全部现场保留')
        reader = DeleteObjects(self.home, self.g['check_cancel'])
        states = []
        paths = [entry.get('path') for entry in plan['entries']]
        if len(set(paths)) != len(paths):
            raise ValueError('旧 pnpm 修复日志有重复路径，全部现场保留')
        for index, entry in enumerate(plan['entries']):
            path = self.cas_module_path(entry['path'])
            before, after = entry['before'], entry['after']
            if before.get('kind') != 'link' or after.get('kind') != 'file':
                raise ValueError('旧 pnpm 修复日志对象类型无效，全部现场保留')
            current = reader.state(path)
            original = reader.state(os.path.join(work, 'before-' + str(index)))
            candidate = reader.state(os.path.join(work, 'new-' + str(index)))
            failed = reader.state(os.path.join(work, 'failed-' + str(index)))
            missing = {'kind': 'missing'}
            valid = (current == before and original == missing and ((candidate == after and failed == missing) or (candidate == missing and failed == after))) \
                    or (current == missing and original == before and ((candidate == after and failed == missing) or (candidate == missing and failed == after))) \
                    or (current == after and original == before and candidate == missing and failed == missing)
            if not valid:
                raise ValueError('旧 pnpm 修复对象在中断后变化，全部现场保留')
            states.append(current)
        for index in reversed(range(len(plan['entries']))):
            entry = plan['entries'][index]; path = self.cas_module_path(entry['path'])
            if states[index] == entry['after']:
                reader.move(path, os.path.join(work, 'failed-' + str(index)), entry['after'])
                self.boundary('cas-rollback-new-' + str(index))
            original = os.path.join(work, 'before-' + str(index))
            if os.path.lexists(original):
                reader.move(original, path, entry['before'])
                self.boundary('cas-rollback-old-' + str(index))
        self.mark(work, 'rolled-back'); sync_directory(work)

    def prepare_delete(self, work, name, manifest, sources):
        objects = self.delete_targets(name)
        before = {key: self.object_state(path, DELETE_FINGERPRINT) for key, path in objects.items()}
        config = {}
        for key, path, value in (('sources', self.g['local'](self.g['SOURCES']), sources),
                                 ('manifest', self.g['local'](self.g['builtin'].MANIFEST), manifest)):
            prior = self.state(path)
            if prior['kind'] not in ('missing', 'file'):
                raise ValueError('插件配置类型异常，原件已保留')
            if prior['kind'] == 'file':
                shutil.copy2(path, os.path.join(work, 'before-' + key))
                if self.state(os.path.join(work, 'before-' + key)) != prior:
                    raise ValueError('插件配置副本摘要不一致')
            output = os.path.join(work, 'after-' + key)
            self.g['write_json'](output, value)
            config[key] = {'before': prior, 'after': self.state(output)}
        plan = {'format': 3, 'objectFingerprint': DELETE_FINGERPRINT, 'id': os.path.basename(work), 'name': name,
                'objects': before, 'config': config}
        self.g['write_json'](os.path.join(work, 'plan.json'), plan)
        sync_directory(work)
        return plan

    def apply_delete(self, work, plan):
        objects = self.delete_targets(plan['name'])
        algorithm = self.delete_algorithm(plan)
        # Includes planned-missing objects: a newly created object is not the
        # selected original and must prevent the deletion from committing.
        for key, path in objects.items():
            if self.object_state(path, algorithm) != plan['objects'][key]:
                raise ValueError('插件原件在删除期间变化，全部现场保留')
        for key, path in objects.items():
            saved = os.path.join(work, 'delete-' + key)
            if plan['objects'][key]['kind'] != 'missing':
                if self.object_state(path, algorithm) != plan['objects'][key] or os.path.lexists(saved):
                    raise ValueError('插件原件在删除期间变化，全部现场保留')
                if algorithm == DELETE_FINGERPRINT:
                    DeleteObjects(self.home, self.g['check_cancel']).move(path, saved, plan['objects'][key])
                else:
                    os.replace(path, saved)
                    sync_directory(os.path.dirname(path)); sync_directory(work)
                self.boundary('delete-' + key + '-moved')
        for key in ('sources', 'manifest'):
            path = self.g['local'](self.g['SOURCES'] if key == 'sources' else self.g['builtin'].MANIFEST)
            states = plan['config'][key]
            if self.state(path) not in (states['before'], states['after']):
                raise ValueError('插件配置在删除期间变化，全部现场保留')
            self.replace_file(work, path, key, 'after', states['after'])
            sync_directory(os.path.dirname(path))
            self.boundary('delete-' + key + '-replaced')
        for key, path in objects.items():
            if self.object_state(path, algorithm)['kind'] != 'missing':
                raise ValueError('插件删除现场出现额外文件，全部现场保留')
        self.mark(work, 'committed')
        sync_directory(work)
        self.boundary('delete-committed')

    def recover_delete(self, work, plan):
        name = plan['name']
        objects = self.delete_targets(name)
        algorithm = self.delete_algorithm(plan)
        configs = {'sources': self.g['local'](self.g['SOURCES']),
                   'manifest': self.g['local'](self.g['builtin'].MANIFEST)}
        if set(plan.get('objects', {})) != set(objects) or set(plan.get('config', {})) != set(configs):
            raise ValueError('插件删除目录映射无效，全部现场保留')
        for key, path in configs.items():
            states = plan['config'][key]
            if set(states) != {'before', 'after'} or self.state(path) not in (states['before'], states['after']):
                raise ValueError('插件配置在中断后变化，未覆盖新内容')
            if states['before']['kind'] == 'file' and self.state(os.path.join(work, 'before-' + key)) != states['before']:
                raise ValueError('插件配置原件摘要不一致，全部现场保留')
        for key, path in objects.items():
            wanted = plan['objects'][key]
            saved = os.path.join(work, 'delete-' + key)
            current = self.object_state(path, algorithm)
            staged = self.object_state(saved, algorithm)
            if wanted['kind'] == 'missing':
                if current != wanted or staged != wanted:
                    raise ValueError('插件删除现场出现额外文件，全部现场保留')
            elif not ((current == wanted and staged['kind'] == 'missing')
                      or (current['kind'] == 'missing' and staged == wanted)):
                raise ValueError('插件原件在中断后变化，全部现场保留')
        for key in ('manifest', 'sources'):
            states = plan['config'][key]
            self.replace_file(work, configs[key], key, 'before', states['before'])
            sync_directory(os.path.dirname(configs[key]))
            self.boundary('delete-rollback-' + key)
        for key in reversed(tuple(objects)):
            path = objects[key]
            saved = os.path.join(work, 'delete-' + key)
            if os.path.lexists(saved):
                if algorithm == DELETE_FINGERPRINT:
                    DeleteObjects(self.home, self.g['check_cancel']).move(saved, path, plan['objects'][key])
                else:
                    os.replace(saved, path)
                    sync_directory(os.path.dirname(path)); sync_directory(work)
                self.boundary('delete-rollback-' + key)
        self.mark(work, 'rolled-back')
        sync_directory(work)

    def prepare(self, work, name, prepared, manifest, sources, marker_bytes, link_target=None, remove_marker=False):
        dest = self.g['local'](os.path.join(self.g['PLUGIN_SRC'], name))
        before = {}; after = {}
        for key, path in self.targets(name).items():
            before[key] = self.state(path)
            if before[key]['kind'] == 'file':
                shutil.copy2(path, os.path.join(work, 'before-' + key))
            if key == 'activation':
                after[key] = before[key]
                continue
            if key == 'marker' and remove_marker:
                after[key] = {'kind': 'missing'}
                continue
            if key == 'marker' and marker_bytes is None:
                after[key] = before[key]
                continue
            output = os.path.join(work, 'after-' + key)
            if key == 'marker':
                with open(output, 'xb') as stream:
                    stream.write(marker_bytes); stream.flush(); os.fsync(stream.fileno())
            else:
                self.g['write_json'](output, manifest if key == 'manifest' else sources)
            after[key] = self.state(output)
        history = self.g['lifecycle']().history_path(name)
        if os.path.lexists(history) and (os.path.islink(history) or not os.path.isdir(history)):
            raise ValueError('插件历史目录类型异常，原件已保留')
        plan = {'format': 2, 'id': os.path.basename(work), 'name': name,
                'before': before, 'after': after, 'oldPresent': os.path.isdir(dest) and link_target is None,
                'oldTree': self.g['dependencies']().tree(dest, self.g['check_cancel'])[0] if os.path.isdir(dest) and link_target is None else '',
                'newTree': self.g['dependencies']().tree(prepared, self.g['check_cancel'])[0] if link_target is None else self.g['dependencies']().current(prepared)['sha256'],
                'linkOnly': link_target is not None, 'linkAfter': link_target or dest,
                'linkBefore': self.state_link(name),
                'oldVersion': self.g['read_json'](os.path.join(dest, 'package.json'), {}).get('version', ''),
                'historyBefore': self.g['dependencies']().tree(history, self.g['check_cancel'])[0] if os.path.isdir(history) else ''}
        self.g['write_json'](os.path.join(work, 'plan.json'), plan)
        return plan

    def activation(self, work, plan, value):
        # 先持久化新摘要及恢复材料，再写激活观察记录。进程中断时与插件目录一起恢复。
        output = os.path.join(work, 'after-activation')
        self.g['write_json'](output, value)
        plan['after']['activation'] = self.state(output)
        self.g['write_json'](os.path.join(work, 'plan.json'), plan)
        self.apply_file(work, 'activation', plan)

    def state_link(self, name):
        path = os.path.join(self.g['local'](self.g['builtin'].NODE_MODULES), name)
        if not os.path.lexists(path):
            return None
        if not os.path.islink(path):
            raise ValueError('插件事务链接已被实体替换，原件已保留')
        return os.readlink(path)

    def apply_file(self, work, key, plan):
        path = self.targets(plan['name'])[key]
        if plan['after'][key] == plan['before'][key]:
            return
        if self.state(path) not in (plan['before'][key], plan['after'][key]):
            raise ValueError('插件配置在提交期间变化，原件已保留')
        self.replace_file(work, path, key, 'after', plan['after'][key])

    def replace_file(self, work, path, key, side, wanted):
        if self.state(path) == wanted:
            return
        os.makedirs(os.path.dirname(path), exist_ok=True)
        if wanted['kind'] == 'missing':
            if os.path.lexists(path):
                os.unlink(path)
        elif wanted['kind'] == 'link':
            if os.path.lexists(path):
                os.unlink(path)
            os.symlink(wanted['target'], path)
        else:
            saved = os.path.join(work, side + '-' + key)
            if self.state(saved) != wanted:
                raise ValueError('插件事务原件摘要不一致')
            temporary = path + '.plugin-transaction-' + os.path.basename(work)
            if os.path.lexists(temporary):
                if self.state(temporary) != wanted:
                    raise ValueError('插件事务暂存文件已变化，原件已保留')
            else:
                # Windows 的 fsync 需要可写描述符；写入句柄同步后再恢复原文件属性。
                with open(saved, 'rb') as source, open(temporary, 'xb') as stream:
                    shutil.copyfileobj(source, stream)
                    stream.flush(); os.fsync(stream.fileno())
                shutil.copystat(saved, temporary)
            os.replace(temporary, path)

    def recover(self, work):
        operation = os.path.basename(work)
        if os.path.normcase(os.path.normpath(self.locate(operation))) != os.path.normcase(os.path.normpath(work)):
            raise ValueError('插件事务目录无效')
        if os.path.normcase(os.path.normpath(os.path.dirname(work))) == os.path.normcase(os.path.normpath(os.path.join(self.directory, 'completed'))):
            if self.marker(work, 'committed') != self.marker(work, 'rolled-back'):
                plan_file = os.path.join(work, 'plan.json')
                if self.state(plan_file)['kind'] != 'file':
                    raise ValueError('插件完成历史计划类型异常，原件已保留')
                plan = self.g['read_json'](plan_file, {})
                if plan.get('id') != operation or plan.get('format') not in (1, 2, 3):
                    raise ValueError('插件完成历史计划无效，原件已保留')
                return
            raise ValueError('插件完成历史终态异常，原件已保留')
        if self.marker(work, 'committed') or self.marker(work, 'rolled-back'):
            return
        plan_file = os.path.join(work, 'plan.json')
        if self.state(plan_file)['kind'] != 'file':
            raise ValueError('插件事务记录类型异常')
        plan = self.g['read_json'](plan_file, {})
        if plan.get('format') not in (1, 2, 3) or plan.get('id') != operation or not self.g['builtin'].valid_name(plan.get('name')):
            raise ValueError('插件事务记录无效，原件已保留')
        if plan['format'] == 3:
            if plan.get('operation') == CAS_OPERATION:
                self.recover_cas_links(work, plan)
                return
            if 'operation' in plan:
                raise ValueError('插件事务操作算法未知，全部现场保留')
            self.recover_delete(work, plan)
            return
        name = plan['name']; targets = self.targets(name)
        if plan['format'] == 1:
            targets.pop('activation')  # 历史三文件日志只恢复它实际记录的范围。
        if type(plan.get('oldPresent')) is not bool or not isinstance(plan.get('newTree'), str) or len(plan['newTree']) != 64:
            raise ValueError('插件事务目录映射无效')
        if set(plan.get('before', {})) != set(targets) or set(plan.get('after', {})) != set(targets):
            raise ValueError('插件事务配置映射无效')
        for key, path in targets.items():
            if self.state(path) not in (plan['before'][key], plan['after'][key]):
                raise ValueError('插件配置在中断后变化，未覆盖新内容')
        dest = self.g['local'](os.path.join(self.g['PLUGIN_SRC'], name))
        original = os.path.join(work, 'old')
        history_root = self.g['lifecycle']().history_path(name)
        history = os.path.join(history_root, 'package')
        if not plan.get('linkOnly') and os.path.islink(dest):
            raise ValueError('插件目标变为链接，原件已保留')
        if not plan.get('linkOnly') and os.path.lexists(dest) and not os.path.isdir(dest):
            raise ValueError('插件目标类型在中断后变化，原件已保留')
        if not plan.get('linkOnly') and os.path.isdir(dest):
            current = self.g['dependencies']().tree(dest, self.g['check_cancel'])[0]
            if current == plan['oldTree'] and plan['oldPresent']:
                original = dest
            elif current == plan['newTree']:
                failed = os.path.join(work, 'failed')
                if os.path.lexists(failed):
                    raise ValueError('插件失败候选已存在，原件已保留')
                os.replace(dest, failed)
                self.boundary('rollback-new')
            else:
                raise ValueError('插件在中断后被修改，未覆盖新内容')
        if plan['oldPresent'] and original != dest:
            location = original if os.path.lexists(original) else history
            if os.path.islink(location) or not os.path.isdir(location) or self.g['dependencies']().tree(location, self.g['check_cancel'])[0] != plan['oldTree']:
                raise ValueError('无法确认插件原件，已保留全部现场')
            os.replace(location, dest)
        saved_history = os.path.join(work, 'previous-history')
        if os.path.lexists(saved_history):
            if os.path.islink(saved_history) or not os.path.isdir(saved_history) or self.g['dependencies']().tree(saved_history, self.g['check_cancel'])[0] != plan.get('historyBefore'):
                raise ValueError('插件历史原件已变化，全部现场保留')
            if os.path.exists(history_root):
                history_state = self.g['read_json'](os.path.join(history_root, 'state.json'), {})
                if os.path.islink(history_root) or (os.listdir(history_root) and (history_state.get('name') != name or history_state.get('version') != plan.get('oldVersion'))):
                    raise ValueError('插件历史在中断后变化，全部现场保留')
                os.rename(history_root, os.path.join(work, 'history-after-failure'))
            os.rename(saved_history, history_root)
        link = os.path.join(self.g['local'](self.g['builtin'].NODE_MODULES), name)
        current_link = self.state_link(name)
        if current_link not in (None, plan.get('linkAfter', dest), plan['linkBefore']):
            raise ValueError('插件链接在中断后变化，未覆盖新内容')
        if current_link != plan['linkBefore']:
            if os.path.lexists(link):
                os.unlink(link)
            if plan['linkBefore'] is not None:
                os.makedirs(os.path.dirname(link), exist_ok=True); os.symlink(plan['linkBefore'], link, target_is_directory=True)
        for key, path in targets.items():
            self.replace_file(work, path, key, 'before', plan['before'][key])
            self.boundary('rollback-' + key)
        self.mark(work, 'rolled-back')

    def recover_all(self):
        with self.g['builtin'].operation_lock(self.g['check_cancel']):
            for work in self.pending():
                self.recover(work)
            self.archive_terminal()

    def boundary(self, name):
        # 仅隔离夹具可以暂停在持久化边界，由测试父进程真正强杀。
        root = os.environ.get('DSHA_TEST_ROOT', '')
        if root and os.environ.get('DSHA_PLUGIN_TEST_BOUNDARY') == name:
            marker = os.path.join(root, 'plugin-boundary')
            with open(marker, 'x', encoding='ascii') as stream:
                stream.write(name); stream.flush(); os.fsync(stream.fileno())
            import time
            while True:
                time.sleep(1)
