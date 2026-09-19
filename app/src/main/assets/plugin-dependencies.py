#!/usr/bin/env python3
"""使用 pnpm 原生锁文件冻结解析；快照只记录实际内容，不执行插件或安装钩子。"""
import hashlib
import importlib.util
import json
import os
import re
import shutil
import stat
import tempfile
import urllib.parse
from pathlib import Path

SNAPSHOT = '.dsha-dependencies.json'
LOCK = 'pnpm-lock.yaml'
MAX_ENTRIES = 100000
MAX_BYTES = 2 * 1024 * 1024 * 1024


def digest(path, check=lambda: None):
    value = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            check()
            value.update(block)
    return value.hexdigest()


def read(path):
    if os.path.islink(path) or not os.path.isfile(path) or os.path.getsize(path) > 8 * 1024 * 1024:
        raise ValueError('插件依赖记录不可读取，原版本已保留')
    with open(path, encoding='utf-8') as stream:
        return json.load(stream)


def source_label(source):
    if not isinstance(source, str):
        return ''
    if source.startswith(('http://', 'https://')):
        value = urllib.parse.urlsplit(source)
        host = value.hostname or ''
        if value.port:
            host += ':' + str(value.port)
        # 下载用的临时签名/认证参数不进入来源快照。
        query = urllib.parse.urlencode([(k, v) for k, v in urllib.parse.parse_qsl(value.query)
                                       if k.lower() in ('ref', 'tag', 'version', 'path')])
        return urllib.parse.urlunsplit((value.scheme, host, value.path, query, ''))
    return source[:8192]


def walk_error(error):
    raise error


def tree(root, check=lambda: None, exclude_top=()):
    root = os.path.realpath(root)
    if not os.path.isdir(root):
        raise ValueError('插件目录不可读取或不存在')
    value = hashlib.sha256()
    packages = []
    count = size = 0
    for base, directories, files in os.walk(root, followlinks=False, onerror=walk_error):
        if base == root:
            directories[:] = [name for name in directories if name not in exclude_top]
        directories.sort(); files.sort()
        for name in sorted(directories + files):
            check()
            path = os.path.join(base, name)
            relative = os.path.relpath(path, root).replace(os.sep, '/')
            if relative in exclude_top:
                continue
            if relative == SNAPSHOT:
                continue
            count += 1
            if count > MAX_ENTRIES or relative.count('/') > 64:
                raise ValueError('插件依赖目录超过核验上限')
            info = os.lstat(path)
            if stat.S_ISLNK(info.st_mode):
                target = os.path.realpath(path)
                if os.path.commonpath([root, target]) != root or not os.path.exists(target):
                    raise ValueError('插件依赖链接缺失或越界')
                record = ['link', relative, os.readlink(path)]
            elif stat.S_ISDIR(info.st_mode):
                record = ['directory', relative, stat.S_IMODE(info.st_mode)]
            elif stat.S_ISREG(info.st_mode):
                size += info.st_size
                if size > MAX_BYTES:
                    raise ValueError('插件依赖目录超过核验上限')
                record = ['file', relative, info.st_size, stat.S_IMODE(info.st_mode), digest(path, check)]
                if name == 'package.json' and relative.startswith('node_modules/'):
                    pkg = read(path)
                    if isinstance(pkg, dict) and isinstance(pkg.get('name'), str) and isinstance(pkg.get('version'), str):
                        packages.append({'path': relative[:-13], 'name': pkg['name'], 'version': pkg['version'], 'manifestSha256': record[-1]})
            else:
                raise ValueError('插件依赖中存在不支持的特殊文件')
            value.update(json.dumps(record, ensure_ascii=True, separators=(',', ':')).encode() + b'\n')
    return value.hexdigest(), packages


def validate_lock(path):
    if os.path.islink(path) or not os.path.isfile(path) or os.path.getsize(path) > 8 * 1024 * 1024:
        raise ValueError('插件锁文件不可读取')
    with open(path, encoding='utf8') as stream:
        value = stream.read()
    # pnpm 自己解析并校验锁格式；这里仅拒绝本地越界来源与会泄露认证的锁。
    if re.search(r'(?<![A-Za-z0-9_-])(?:file|link|workspace):|https?://[^/\s]*@|[?&](?:token|access_token|auth|signature|key)=|_authToken', value, re.I):
        raise ValueError('插件锁文件包含本地来源或认证信息，不能作为可移植依赖快照')
    return digest(path)


class Dependencies:
    source_label = staticmethod(source_label)
    tree = staticmethod(tree)
    validate_lock = staticmethod(validate_lock)
    def __init__(self, manager):
        self.g = manager
        self.home = manager['local'](manager['DSH_HOME'])

    def current(self, root):
        """只读核对现存 npm/pnpm 图；循环依赖按节点去重，不运行任何入口。"""
        spec = importlib.util.spec_from_file_location('dependency_graph_reader', os.path.join(os.path.dirname(__file__), 'backup-plugin-graph.py'))
        graph_reader = importlib.util.module_from_spec(spec); spec.loader.exec_module(graph_reader)
        global_dirs = [Path(self.g['local'](value)) for value in (self.g['DSH_HOME'] + '/node_modules',
                       '/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules', '/usr/local/lib/node_modules')]
        shared = {path.resolve() for path in global_dirs if path.is_dir()}
        pending = [Path(root).resolve(strict=True)]; queued = {str(pending[0])}; graph = {}; missing = []
        count = total = edge_count = 0
        while pending:
            source = pending.pop(0)
            if str(source) in graph:
                continue
            if len(graph) >= 2000:
                raise ValueError('插件依赖图超过审阅上限')
            pkg = read(source / 'package.json')
            if not self.g['builtin'].valid_name(pkg.get('name')):
                raise ValueError('插件依赖包名无效')
            code = hashlib.sha256()
            for base, directories, files in os.walk(source, followlinks=False, onerror=walk_error):
                self.g['check_cancel']()
                # node_modules 在下面按真实包解析为图，避免 pnpm 循环/共享池重复遍历。
                if Path(base) == source and 'node_modules' in directories:
                    directories.remove('node_modules')
                directories.sort(); files.sort()
                for name in sorted(directories + files):
                    path = Path(base) / name; relative = str(path.relative_to(source)).replace(os.sep, '/')
                    if relative == SNAPSHOT:
                        continue
                    count += 1
                    if count > MAX_ENTRIES:
                        raise ValueError('插件依赖图超过审阅上限')
                    info = path.lstat()
                    if path.is_symlink():
                        target = path.resolve(strict=True)
                        if os.path.commonpath([str(source), str(target)]) != str(source):
                            raise ValueError('插件源码含未归属的外部链接，原件已保留')
                        if target.is_file():
                            row = ['link', relative, os.readlink(path), digest(target, self.g['check_cancel'])]
                        elif target.is_dir():
                            # 实体目录仍由主遍历核对，别名不递归，避免内部目录环。
                            row = ['directory-link', relative, os.readlink(path)]
                        else:
                            raise ValueError('插件源码链接目标类型异常')
                    elif stat.S_ISREG(info.st_mode):
                        total += info.st_size
                        if total > MAX_BYTES:
                            raise ValueError('插件依赖图超过审阅上限')
                        row = ['file', relative, stat.S_IMODE(info.st_mode), digest(path, self.g['check_cancel'])]
                    elif stat.S_ISDIR(info.st_mode):
                        row = ['directory', relative, stat.S_IMODE(info.st_mode)]
                    else:
                        raise ValueError('插件包含不能审阅的特殊文件')
                    code.update(json.dumps(row, ensure_ascii=True, separators=(',', ':')).encode() + b'\n')
            requirements = {}; optional = set(pkg.get('optionalDependencies') or {})
            for section in ('dependencies', 'peerDependencies', 'optionalDependencies'):
                declared = pkg.get(section) or {}
                if not isinstance(declared, dict):
                    raise ValueError('插件依赖声明必须是对象')
                for name, requested in declared.items():
                    requirements[name] = (requested, section)
            optional.update(name for name, meta in (pkg.get('peerDependenciesMeta') or {}).items() if isinstance(meta, dict) and meta.get('optional'))
            embedded = source / 'node_modules'
            if embedded.is_dir() and embedded.resolve() not in shared:
                for child in sorted(embedded.iterdir()):
                    for candidate in sorted(child.iterdir()) if child.name.startswith('@') and child.is_dir() else [child]:
                        if (candidate / 'package.json').is_file():
                            name = child.name + '/' + candidate.name if child.name.startswith('@') else candidate.name
                            requirements.setdefault(name, ('*', 'bundled'))
            edges = {}
            for name, (requested, kind) in sorted(requirements.items()):
                edge_count += 1
                if edge_count > 50000:
                    raise ValueError('插件依赖图超过审阅上限')
                if not self.g['builtin'].valid_name(name) or not isinstance(requested, str) or len(requested) > 4096:
                    raise ValueError('插件依赖声明无效')
                target = graph_reader.resolve_module(source, name, global_dirs)
                if target is None:
                    host_service = kind == 'peerDependencies' and name.startswith('@deepseek-ai/dsh-')
                    edges[name] = {'requested': requested, 'kind': kind, 'missing': True, 'hostService': host_service, 'optional': name in optional}
                    if name not in optional and not host_service:
                        missing.append(name)
                else:
                    edges[name] = {'requested': requested, 'kind': kind, 'target': str(target)}
                    if str(target) not in queued:
                        queued.add(str(target)); pending.append(target)
            graph[str(source)] = {'name': pkg['name'], 'version': str(pkg.get('version', '')), 'codeSha256': code.hexdigest(), 'dependencies': edges}
        encoded = json.dumps(graph, sort_keys=True, ensure_ascii=True, separators=(',', ':')).encode()
        if len(encoded) > 8 * 1024 * 1024:
            raise ValueError('插件依赖图超过审阅上限')
        return {'sha256': hashlib.sha256(encoded).hexdigest(), 'nodes': graph, 'missing': sorted(set(missing)), 'complete': not missing}

    def inspect(self, root):
        path = os.path.join(root, SNAPSHOT)
        if not os.path.lexists(path):
            return {'state': 'legacy-unknown', 'message': '历史依赖信息不足，未重新解析原版本'}
        snapshot = read(path)
        if snapshot.get('format') != 1 or snapshot.get('manifestSha256') != digest(os.path.join(root, 'package.json')):
            raise ValueError('插件依赖快照与包声明不一致')
        actual, packages = tree(root, self.g['check_cancel'])
        if actual != snapshot.get('treeSha256') or packages != snapshot.get('resolved'):
            raise ValueError('插件依赖内容与确认快照不一致')
        if snapshot.get('lockSha256') and validate_lock(os.path.join(root, LOCK)) != snapshot['lockSha256']:
            raise ValueError('插件锁文件摘要不一致')
        return snapshot

    def prepare(self, root, pkg, archive_sha='', offline=False, restoring=False):
        existing = os.path.join(root, SNAPSHOT)
        if os.path.lexists(existing):
            return self.inspect(root)
        if restoring:
            # 旧完整副本按实际字节回退；缺记录只报告未知，绝不重新解析最新依赖。
            tree(root, self.g['check_cancel'])
            return {'state': 'legacy-unknown', 'message': '历史依赖信息不足，未重新解析原版本'}
        dependencies = pkg.get('dependencies') or {}
        optional = pkg.get('optionalDependencies') or {}
        if not isinstance(dependencies, dict) or not isinstance(optional, dict) or any(not self.g['builtin'].valid_name(name) for name in [*dependencies, *optional]):
            raise ValueError('插件 dependencies 格式无效')
        if any(not isinstance(value, str) or value.startswith(('workspace:', 'link:', 'file:')) for value in [*dependencies.values(), *optional.values()]):
            raise ValueError('插件仍引用本地工作区依赖，请使用包含依赖的发布包')
        manifest_sha = digest(os.path.join(root, 'package.json'))
        identity = hashlib.sha256((manifest_sha + ':' + archive_sha).encode()).hexdigest()
        cache = os.path.join(self.home, 'plugin-dependency-locks')
        os.makedirs(cache, exist_ok=True)
        if os.path.islink(cache) or os.path.commonpath([os.path.realpath(self.home), os.path.realpath(cache)]) != os.path.realpath(self.home):
            raise ValueError('插件依赖缓存路径异常')
        cached = os.path.join(cache, identity)
        missing = [name for name in dependencies if not os.path.isfile(os.path.join(root, 'node_modules', name, 'package.json'))]
        if missing and os.path.isdir(os.path.join(root, 'node_modules')) and os.listdir(os.path.join(root, 'node_modules')):
            raise ValueError('插件已附带部分依赖，缺失项未自动覆盖，请提供完整依赖包')
        manager_version = 'not-executed'
        state = 'bundled-verified' if dependencies else 'no-dependencies'
        lock_hash = ''
        if missing or optional and not os.path.isdir(os.path.join(root, 'node_modules')):
            version = self.g['run_package_command'](['pnpm', '--version'], cwd=self.home)
            manager_version = version.stdout.strip()
            if version.returncode or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', manager_version):
                raise ValueError('无法确认 pnpm 版本，未更改已安装插件')
            with tempfile.TemporaryDirectory(prefix='plugin-deps-', dir=self.home) as work:
                chosen = None
                original_manifest = False
                if os.path.exists(cached):
                    metadata = read(os.path.join(cached, 'state.json'))
                    chosen = os.path.join(cached, LOCK)
                    if metadata.get('manifestSha256') != manifest_sha or metadata.get('archiveSha256') != archive_sha or metadata.get('managerVersion') != manager_version or validate_lock(chosen) != metadata.get('lockSha256'):
                        raise ValueError('插件依赖缓存记录不一致，未重新解析依赖')
                    original_manifest = metadata.get('manifestMode') == 'original'
                elif os.path.exists(os.path.join(root, LOCK)):
                    chosen = os.path.join(root, LOCK)
                    validate_lock(chosen)
                    original_manifest = True
                planned = dict(pkg) if original_manifest else {'name': 'dsha-plugin-deps', 'private': True, 'dependencies': dependencies, 'optionalDependencies': optional}
                planned.pop('packageManager', None)
                if (planned.get('pnpm') or {}).get('patchedDependencies'):
                    raise ValueError('插件锁定补丁需要包含完整离线依赖')
                self.g['write_json'](os.path.join(work, 'package.json'), planned)
                if chosen:
                    shutil.copyfile(chosen, os.path.join(work, LOCK))
                arguments = ['pnpm', 'install', '--prod', '--ignore-scripts', '--ignore-pnpmfile', '--frozen-lockfile' if chosen else '--no-frozen-lockfile',
                             '--config.node-linker=hoisted', '--config.package-import-method=copy', '--config.auto-install-peers=false',
                             '--config.manage-package-manager-versions=false', '--reporter=append-only']
                if offline:
                    arguments.append('--offline')
                process = self.g['run_package_command'](arguments, cwd=work)
                if process.returncode:
                    codes = re.findall(r'ERR_PNPM_[A-Z0-9_]+', (process.stderr or '') + (process.stdout or ''))
                    raise ValueError('插件依赖安装失败：' + (codes[0] if codes else 'PNPM_FAILED'))
                prepared_lock = os.path.join(work, LOCK)
                lock_hash = validate_lock(prepared_lock)
                if chosen and lock_hash != validate_lock(chosen):
                    raise ValueError('冻结安装修改了锁文件，未提交插件')
                modules = os.path.join(root, 'node_modules')
                if os.path.islink(modules):
                    os.unlink(modules)
                elif os.path.isdir(modules):
                    shutil.rmtree(modules)
                shutil.move(os.path.join(work, 'node_modules'), modules)
                shutil.copyfile(prepared_lock, os.path.join(root, LOCK))
                if not os.path.exists(cached):
                    if len(os.listdir(cache)) >= 128:
                        raise ValueError('保留的插件锁记录已达上限，原记录未自动删除')
                    with tempfile.TemporaryDirectory(prefix='.new-', dir=cache) as stage:
                        self.g['write_json'](os.path.join(stage, 'state.json'), {'manifestSha256': manifest_sha, 'archiveSha256': archive_sha, 'managerVersion': manager_version, 'lockSha256': lock_hash, 'manifestMode': 'original' if original_manifest else 'runtime-dependencies'})
                        shutil.copyfile(prepared_lock, os.path.join(stage, LOCK))
                        os.rename(stage, cached)
                state = 'locked'
        elif os.path.isfile(os.path.join(root, LOCK)):
            lock_hash = validate_lock(os.path.join(root, LOCK))
        actual, packages = tree(root, self.g['check_cancel'])
        snapshot = {'format': 1, 'state': state, 'plugin': pkg['name'], 'version': str(pkg.get('version', '')), 'archiveSha256': archive_sha,
                    'manifestSha256': manifest_sha, 'treeSha256': actual, 'resolved': packages, 'lockSha256': lock_hash,
                    'manager': 'pnpm', 'managerVersion': manager_version, 'integrity': 'actual file tree SHA-256 and native pnpm lockfile',
                    'runtimeDsh': self.g['lifecycle']().dsh_version()}
        self.g['write_json'](existing, snapshot)
        return snapshot
