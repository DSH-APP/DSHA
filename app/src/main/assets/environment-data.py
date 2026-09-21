#!/usr/bin/env python3
"""环境重建专用个人文件快照：不混回旧系统，恢复校验完成前保留原环境。"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tarfile
import re
import time
from collections import deque

MANIFEST = '.dsha-environment-data.json'
SYSTEM = {'bin', 'sbin', 'lib', 'lib64', 'usr', 'etc', 'var', 'tmp', 'run', 'proc', 'dev', 'sys',
          'root', '.l2s', 'lost+found', 'sdcard', 'storage', 'system', 'apex'}
EXTERNAL = {'proc', 'dev', 'sys', 'sdcard', 'storage', 'system', 'apex'}
HOME_GENERATED = {'.dsh', '.cache', '.npm', '.pnpm-store', '.dsha-bundled-before-maintenance',
                  '.dsha-stopped', '.dsha-web.pid', '.dsha-web-activity.json',
                  '.dsha-bundled-tools', '.dsha-ubuntu-tools-version', '.dsha-environment-data.py',
                  'dsha-device-shell-guide', 'dsha-task-notifier', 'dsha-status-overlay',
                  'dsha-web-mobile', 'dsha-app-integration'}
MAX_BYTES = 16 * 1024 ** 3
MAX_FILES = 300_000
REPLACED_TOOLS = ('data/data/com.termux/files/usr', 'root/.local/share/pnpm/store')
# proot 为宿主绝对路径绑定建立的占位目录，不是个人文件；不能宽泛排除 /data。
HOST_ROOT = re.compile(r'^/?data/(?:data|user(?:_de)?/[0-9]+)/com\.dsh\.client/files/linux/ubuntu(?=/|$)')


def runtime_cache(name):
    relative = HOST_ROOT.sub('', name, count=1).lstrip('/')
    return relative == '.l2s' or relative.startswith('.l2s/')


def guest_target(target, rootfs):
    """旧版 L2S 链含宿主绝对路径，读取时映射回独立 rootfs，而不是宿主绑定别名。"""
    prefix = str(rootfs).rstrip('/')
    if prefix and target.startswith(prefix + '/'):
        return target[len(prefix):]
    if target.startswith('/') and HOST_ROOT.match(target):
        return '/' + HOST_ROOT.sub('', target, count=1).lstrip('/')
    return target


def resolve_internal_link(rootfs, source):
    """按 guest 根解析模拟硬链接；限制跳数、边界及外部挂载，不跟随宿主绝对链接。"""
    pending = deque(source.relative_to(rootfs).parts)
    parts, hops = [], 0
    while pending:
        part = pending.popleft()
        if part in ('', '.'):
            continue
        if part == '..':
            if not parts:
                raise ValueError('模拟硬链接超出个人环境边界')
            parts.pop()
            continue
        candidate = rootfs.joinpath(*parts, part)
        info = candidate.lstat()
        if stat.S_ISLNK(info.st_mode):
            hops += 1
            if hops > 40:
                raise ValueError('模拟硬链接存在循环')
            target = PurePosixPath(guest_target(os.readlink(candidate), rootfs))
            if target.is_absolute():
                parts = []
                target = target.relative_to('/')
            pending.extendleft(reversed(target.parts))
        else:
            parts.append(part)
            if parts[0] in EXTERNAL:
                raise ValueError('模拟硬链接指向外部挂载')
    resolved = rootfs.joinpath(*parts)
    if not resolved.is_file():
        raise ValueError('模拟硬链接没有可读取的文件内容')
    return resolved


class Progress:
    def __init__(self):
        self.started = time.monotonic()
        self.last = 0

    def report(self, stage, files, size, force=False):
        now = time.monotonic()
        if not force and now - self.last < 1:
            return
        self.last = now
        print('DSHA_ENV_PROGRESS=' + json.dumps({'stage': stage, 'files': files,
              'bytes': size, 'seconds': int(now - self.started)}, ensure_ascii=False), flush=True)


class HashingReader:
    """随归档写入计算摘要，不再为每个源文件额外完整读取一遍。"""
    def __init__(self, stream, progress):
        self.stream, self.progress = stream, progress
        self.hash = hashlib.sha256()
        self.size = 0

    def read(self, size=-1):
        block = self.stream.read(size)
        self.hash.update(block)
        self.size += len(block)
        self.progress(self.size)
        return block


def generated_home(name):
    return name in HOME_GENERATED or bool(re.fullmatch(
        r'\.dsha-personal-(?:input-)?[0-9a-f-]{36}\.tar\.gz(?:\.part)?', name))


def digest(path, on_bytes=None):
    result = hashlib.sha256()
    size = 0
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
            size += len(block)
            if on_bytes:
                on_bytes(size)
    return result.hexdigest()


def canonical_name(name):
    path = PurePosixPath(name)
    if not name or '\\' in name or '\x00' in name or path.is_absolute() or any(p in ('', '.', '..') for p in name.split('/')):
        raise ValueError('个人数据路径无效')
    return path


def mapped(rootfs, guest):
    return rootfs.joinpath(*PurePosixPath(guest).parts[1:])


def selection(rootfs, workdir):
    """/root 与非系统顶层目录原样保留；系统树只带走明确登记的独立工作目录。"""
    roots = [p.name for p in rootfs.iterdir() if p.name not in SYSTEM]
    home = rootfs / 'root'
    if home.is_dir():
        roots.extend('root/' + p.name for p in home.iterdir() if not generated_home(p.name))
    workspaces = [workdir if workdir.startswith('/') else '/root/' + workdir]
    registry = home / '.dsh/storages/workspace.json'
    if registry.is_file():
        data = json.loads(registry.read_text(encoding='utf-8'))
        for value in ((data.get('tables') or {}).get('workspaces') or {}).values():
            path = (value or {}).get('path')
            if isinstance(path, str) and path.startswith('/'):
                workspaces.append(path)
    for workspace in workspaces:
        path = PurePosixPath(workspace)
        if '..' in path.parts:
            raise ValueError('工作目录含上级跳转，已停止自动迁移：' + workspace)
        if len(path.parts) > 1 and path.parts[1] in EXTERNAL:
            continue  # 外部挂载的数据不在待替换 rootfs 中。
        if path == PurePosixPath('/root'):
            continue
        if len(path.parts) < 3 or str(path) in ('/usr/local', '/usr/local/lib', '/usr/local/lib/node_modules', '/var/lib'):
            if len(path.parts) == 2 and path.parts[1] not in SYSTEM:
                continue
            raise ValueError('工作区直接使用系统目录，请先改为独立项目目录后重试：' + workspace)
        name = path.as_posix().lstrip('/')
        if any(name == base or name.startswith(base + '/') for base in ('root/.dsh', 'usr/local/lib/node_modules/@deepseek-ai/dsh')):
            continue
        if os.path.lexists(rootfs / name):
            roots.append(name)
    roots = sorted(set(roots))
    return [name for name in roots if not any(name.startswith(other + '/') for other in roots if other != name)]


def snapshot(rootfs, output, workdir='deepseek-harness'):
    rootfs, output = Path(rootfs).resolve(), Path(output).absolute()
    progress = Progress()
    progress.report('正在选择个人目录与登记工作区', 0, 0, True)
    if output.exists():
        raise ValueError('个人数据安全副本已存在，禁止覆盖')
    roots = selection(rootfs, workdir)
    inventory, checks = {}, []
    temporary = output.with_name(output.name + '.part')
    total = 0

    def add(archive, source, name):
        nonlocal total
        if runtime_cache(name):
            return  # 在 lstat/遍历之前跳过 000 权限挂载占位；真实个人目录错误仍会中止。
        if any(name == prefix or name.startswith(prefix + '/') for prefix in REPLACED_TOOLS):
            return
        if source.absolute() in (output, temporary):
            return
        canonical_name(name)
        info = source.lstat()
        entry = tarfile.TarInfo(name)
        entry.mode, entry.mtime = stat.S_IMODE(info.st_mode), info.st_mtime
        if stat.S_ISLNK(info.st_mode):
            target = os.readlink(source)
            # proot 模拟硬链接属于文件内容，不把指向旧 rootfs 的 .l2s 链带到新环境。
            # bxroot（第三运行时）布局同样命中：客户文件 symlink → /.l2s/.l2s.<名>，
            # target 含 .l2s. → 实体化进备份（数据正确）；集中目录 <rootfs>/.l2s
            # 本身已在上方 EXCLUDE 清单（'.l2s'），不会重复打包。
            if any(part == '.l2s' or part.startswith('.l2s.') for part in PurePosixPath(target).parts):
                resolved = resolve_internal_link(rootfs, source)
                checks.append((source, ('link', target)))
                return add(archive, resolved, name)
            entry.type, entry.linkname = tarfile.SYMTYPE, target
            archive.addfile(entry)
            inventory[name] = {'link': target}
            checks.append((source, ('link', target)))
        elif stat.S_ISDIR(info.st_mode):
            entry.type = tarfile.DIRTYPE
            archive.addfile(entry)
            inventory[name] = {'directory': True}
            before = sorted(p.name for p in source.iterdir() if p.absolute() not in (output, temporary))
            for child in before:
                add(archive, source / child, name + '/' + child)
            checks.append((source, ('directory', before)))
        elif stat.S_ISREG(info.st_mode):
            total += info.st_size
            if total > MAX_BYTES:
                raise ValueError('个人数据超过迁移容量上限，原环境保持原位')
            entry.size = info.st_size
            with source.open('rb') as stream:
                hashed = HashingReader(stream, lambda size: progress.report(
                    '正在保护个人文件', len(inventory), total - info.st_size + size))
                archive.addfile(entry, hashed)
            inventory[name] = {'size': info.st_size, 'sha256': hashed.hash.hexdigest()}
            after = source.stat()
            if (info.st_size, info.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                raise ValueError('个人文件在快照期间变化，请稍后重试：' + name)
            checks.append((source, ('file', after.st_size, after.st_mtime_ns)))
        elif stat.S_ISSOCK(info.st_mode):
            return  # 已停止进程留下的套接字不能迁移，服务启动时会重建。
        else:
            raise ValueError('个人目录包含不能迁移的特殊文件：' + name)
        if len(inventory) > MAX_FILES:
            raise ValueError('个人数据文件数超过迁移上限')
        progress.report('正在保护个人文件', len(inventory), total)

    try:
        with tarfile.open(temporary, 'w:gz', compresslevel=1) as archive:
            for name in roots:
                add(archive, rootfs / name, name)
            for source, before in checks:
                progress.report('正在核对源文件状态', len(inventory), total)
                if before[0] == 'link':
                    current = ('link', os.readlink(source))
                elif before[0] == 'directory':
                    current = ('directory', sorted(p.name for p in source.iterdir() if p.absolute() not in (output, temporary)))
                else:
                    now = source.stat()
                    current = ('file', now.st_size, now.st_mtime_ns)
                if before != current:
                    raise ValueError('个人数据在快照期间变化，已停止迁移')
            manifest = json.dumps({'version': 1, 'roots': roots, 'inventory': inventory, 'bytes': total}, ensure_ascii=False).encode()
            entry = tarfile.TarInfo(MANIFEST); entry.size = len(manifest)
            archive.addfile(entry, io.BytesIO(manifest))
        # 逐文件重新读取压缩归档验证，不能只校验压缩文件本身可读。
        verify_archive(temporary, progress)
        os.replace(temporary, output)
    finally:
        if temporary.exists():
            temporary.unlink()
    progress.report('正在校验完整安全副本', len(inventory), total, True)
    result = {'files': len(inventory), 'bytes': total, 'archiveBytes': output.stat().st_size,
              'sha256': digest(output, lambda size: progress.report('正在校验完整安全副本', len(inventory), size))}
    progress.report('个人文件保护校验完成', len(inventory), total, True)
    return result


def verify_archive(archive_path, progress=None):
    records = {}
    total = 0
    with tarfile.open(archive_path, 'r:gz') as archive:
        manifest = None
        for entry in archive:
            name = entry.name.rstrip('/')
            canonical_name(name)
            if name == MANIFEST:
                if manifest is not None or not entry.isfile() or entry.size > 64 * 1024 ** 2:
                    raise ValueError('个人数据清单无效')
                manifest = json.load(archive.extractfile(entry))
                continue
            if name in records or any('/'.join(name.split('/')[:i]) in records
                                      and 'link' in records['/'.join(name.split('/')[:i])]
                                      for i in range(1, len(name.split('/')))):
                raise ValueError('个人数据含重复路径或链接子项')
            if entry.issym():
                records[name] = {'link': entry.linkname}
            elif entry.isdir():
                records[name] = {'directory': True}
            elif entry.isfile():
                total += entry.size
                if total > MAX_BYTES:
                    raise ValueError('个人数据归档超出容量上限')
                value = hashlib.sha256()
                with archive.extractfile(entry) as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b''):
                        value.update(block)
                        if progress:
                            progress.report('正在逐文件校验安全副本', len(records), total)
                records[name] = {'size': entry.size, 'sha256': value.hexdigest()}
            else:
                raise ValueError('个人数据归档含不支持的类型')
            if len(records) > MAX_FILES:
                raise ValueError('个人数据归档超出文件数上限')
            if progress:
                progress.report('正在逐文件校验安全副本', len(records), total)
        if not isinstance(manifest, dict) or manifest.get('version') != 1 or manifest.get('inventory') != records:
            raise ValueError('个人数据逐文件校验失败')
        roots = manifest.get('roots')
        if not isinstance(roots, list):
            raise ValueError('个人数据范围无效')
        for name in roots:
            canonical_name(name)
            if name.split('/')[0] in EXTERNAL or name in SYSTEM or name in ('root/.dsh', '.l2s'):
                raise ValueError('个人数据范围包含系统或外部挂载')
        if any(not any(name == root or name.startswith(root + '/') for root in roots) for name in records):
            raise ValueError('个人数据超出声明范围')
        for name in records:
            if any('link' in records.get('/'.join(name.split('/')[:i]), {}) for i in range(1, len(name.split('/')))):
                raise ValueError('个人数据不能沿归档链接写入')
        if manifest.get('bytes') != total:
            raise ValueError('个人数据容量清单不符')
        return manifest


def safe_destination(rootfs, name):
    canonical_name(name)
    target = rootfs.joinpath(*PurePosixPath(name).parts)
    parent = target.parent
    while parent != rootfs:
        if parent.is_symlink():
            raise ValueError('新环境的恢复父目录是链接，已停止：' + name)
        parent = parent.parent
    return target


def restore(rootfs, archive_path):
    rootfs = Path(rootfs).resolve()
    progress = Progress()
    manifest = verify_archive(archive_path, progress)
    directories = []
    files, size = 0, 0
    with tarfile.open(archive_path, 'r:gz') as archive:
        for entry in archive:
            name = entry.name.rstrip('/')
            if name == MANIFEST:
                continue
            target = safe_destination(rootfs, name)
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.is_symlink() or (target.exists() and not (target.is_dir() and entry.isdir())):
                if target.is_dir() and not target.is_symlink():
                    shutil.rmtree(target)
                else:
                    target.unlink()
            if entry.isdir():
                target.mkdir(exist_ok=True)
                directories.append((target, entry.mode, entry.mtime))
            elif entry.issym():
                target.symlink_to(entry.linkname)
            else:
                with archive.extractfile(entry) as stream, target.open('wb') as out:
                    for block in iter(lambda: stream.read(1024 * 1024), b''):
                        out.write(block)
                        size += len(block)
                        progress.report('正在恢复个人文件', files, size)
                os.chmod(target, entry.mode & 0o777)
                os.utime(target, (entry.mtime, entry.mtime))
            files += 1
            progress.report('正在恢复个人文件', files, size)
    for target, mode, mtime in reversed(directories):
        os.chmod(target, mode & 0o777)
        os.utime(target, (mtime, mtime))
    # 恢复后对实际落盘文件再校验；通过此关才能提交环境切换、释放旧运行时。
    for name, expected in manifest['inventory'].items():
        progress.report('正在校验恢复后的文件', files, size)
        target = safe_destination(rootfs, name)
        if 'link' in expected:
            actual = {'link': os.readlink(target)} if target.is_symlink() else {}
        elif expected.get('directory'):
            actual = {'directory': True} if target.is_dir() and not target.is_symlink() else {}
        else:
            actual = {'size': target.stat().st_size, 'sha256': digest(target, lambda read: progress.report(
                '正在校验恢复后的文件', files, read))} if target.is_file() and not target.is_symlink() else {}
        if actual != expected:
            raise ValueError('个人文件恢复后校验失败：' + name)
    return {'files': len(manifest['inventory']), 'bytes': manifest['bytes'], 'verified': True}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('snapshot', 'restore'))
    parser.add_argument('--rootfs', default='/')
    parser.add_argument('--archive', required=True)
    parser.add_argument('--workdir', default='deepseek-harness')
    args = parser.parse_args()
    try:
        result = snapshot(args.rootfs, args.archive, args.workdir) if args.command == 'snapshot' else restore(args.rootfs, args.archive)
        print('DSHA_ENV_DATA=' + json.dumps(result, ensure_ascii=False))
    except Exception as error:
        print('个人数据迁移失败：' + str(error), file=__import__('sys').stderr)
        raise SystemExit(1)
