#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSHA 插件包导入/导出/链接安装。末行 PLUGIN_RESULT JSON 是唯一操作结果。
仅安装声明 dsh.bundle.patch 的发布包；安装依赖不执行 prepare/build 脚本。
"""
import importlib.util
import json
import contextlib
import os
import re
import shutil
import ssl
import stat
import subprocess
import signal
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

spec = importlib.util.spec_from_file_location("builtin", os.path.join(
    os.path.dirname(__file__), "register-builtin-plugins.py"))
builtin = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builtin)
local = builtin.local
DSH_HOME = builtin.DSH_HOME
PLUGIN_SRC = os.path.join(DSH_HOME, "plugin-src")
SOURCES = os.path.join(DSH_HOME, "plugin-sources.json")
MAX_DOWNLOAD = 256 * 1024 * 1024
MAX_EXPANDED = 768 * 1024 * 1024
MAX_FILES = 50000

_lifecycle = None
_last_progress = 0


class PluginCancelled(Exception):
    pass


def task_file(suffix):
    key = os.environ.get('DSHA_PLUGIN_TASK', '')
    return local('/root/.dsha-plugin-task-' + key + suffix) if re.fullmatch('[a-f0-9]{32}', key) else ''


def check_cancel():
    path = task_file('.cancel')
    if path and os.path.exists(path):
        raise PluginCancelled('已取消；尚未提交的插件数据已清理')


def progress(stage, message, current=0, total=0, cancellable=True):
    global _last_progress
    if cancellable:
        check_cancel()
    now = time.monotonic()
    if current and now - _last_progress < .3 and current != total:
        return
    _last_progress = now
    path = task_file('.json')
    if path:
        write_json(path, dict(stage=stage, message=message, current=current, total=total, cancellable=cancellable))


@contextlib.contextmanager
def committing(message):
    # 仅在进入事务前响应取消。切换期间完成提交或回滚，不能杀死 proot/容器。
    check_cancel()
    progress('commit', message, cancellable=False)
    try:
        yield
    finally:
        progress('committed', '本项变更已处理', cancellable=False)


def run_package_command(argv, cwd, timeout=120):
    check_cancel()
    # 只终止本次启动的 npm/pnpm 子进程组；stdout/stderr 放临时文件避免管道填满。
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
        process = subprocess.Popen(argv, cwd=cwd, stdout=out, stderr=err, start_new_session=os.name != 'nt')
        deadline = time.monotonic() + timeout
        try:
            while process.poll() is None:
                check_cancel()
                if time.monotonic() > deadline:
                    raise ValueError('包管理操作超时，请检查网络后重试')
                time.sleep(.15)
        except Exception:
            if os.name != 'nt':
                try: os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError: pass
            else:
                process.kill()
            process.wait()
            raise
        out.seek(0); err.seek(0)
        return subprocess.CompletedProcess(argv, process.returncode, out.read(1024 * 1024).decode('utf-8', 'replace'),
                                           err.read(1024 * 1024).decode('utf-8', 'replace'))


def lifecycle():
    global _lifecycle
    if _lifecycle is None:
        module_spec = importlib.util.spec_from_file_location("plugin_lifecycle", os.path.join(os.path.dirname(__file__), "plugin-lifecycle.py"))
        module = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(module)
        _lifecycle = module.Lifecycle(globals())
    return _lifecycle


def result(status, message, **extra):
    print("PLUGIN_RESULT: " + json.dumps(dict(status=status, message=message, **extra),
                                         ensure_ascii=False), flush=True)


def read_json(path, default=None):
    if not os.path.isfile(path):
        return default
    with open(path, encoding="utf-8-sig") as stream:
        return json.load(stream)


def write_json(path, doc):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as stream:
        json.dump(doc, stream, indent=2, ensure_ascii=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(tmp, path)


def repository_url(pkg):
    value = pkg.get("repository", "")
    if isinstance(value, dict):
        value = value.get("url", "")
    if not isinstance(value, str):
        return ""
    value = value.removeprefix("git+")
    if value.startswith("git@github.com:"):
        value = "https://github.com/" + value[len("git@github.com:"):]
    if value.endswith(".git"):
        value = value[:-4]
    uri = urllib.parse.urlsplit(value)
    return value if uri.scheme == "https" and uri.hostname and not uri.username else ""


def safe_target(root, name):
    name = name.replace("\\", "/")
    parts = name.split("/")
    if not name or name.startswith("/") or any(p == ".." or ":" in p for p in parts):
        raise ValueError("归档含越界路径：" + name)
    target = os.path.realpath(os.path.join(root, name))
    if os.path.commonpath([os.path.realpath(root), target]) != os.path.realpath(root):
        raise ValueError("归档路径越界：" + name)
    return target


def extract_archive(path, staging):
    progress('extract', '正在校验并解压插件包…')
    """先校验所有条目，再解包；内部软链接延迟建立，不允许链接写出暂存目录。"""
    size, count, links = 0, 0, []
    os.makedirs(staging, exist_ok=True)

    def check(name, length):
        nonlocal size, count
        check_cancel()
        target = safe_target(staging, name)
        size += length
        count += 1
        if size > MAX_EXPANDED or count > MAX_FILES:
            raise ValueError("插件包解压后过大（上限 768 MiB / 50000 个文件）")
        return target

    def copy(src, target, mode):
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if os.path.lexists(target):
            raise ValueError("归档包含重复文件：" + os.path.relpath(target, staging))
        with open(target, "wb") as dst:
            while True:
                check_cancel()
                chunk = src.read(65536)
                if not chunk:
                    break
                dst.write(chunk)
        os.chmod(target, 0o755 if mode & 0o111 else 0o644)

    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            entries = [(i, check(i.filename, i.file_size)) for i in archive.infolist()]
            for entry, target in entries:
                check_cancel()
                mode = entry.external_attr >> 16
                if entry.is_dir():
                    os.makedirs(target, exist_ok=True)
                elif stat.S_ISLNK(mode):
                    if entry.file_size > 4096:
                        raise ValueError("软链接目标过长")
                    links.append((target, archive.read(entry).decode("utf-8")))
                else:
                    with archive.open(entry) as src:
                        copy(src, target, mode)
    else:
        with tarfile.open(path, "r:*") as archive:
            entries = [(i, check(i.name, i.size)) for i in archive]
            for entry, target in entries:
                check_cancel()
                if entry.isdir():
                    os.makedirs(target, exist_ok=True)
                elif entry.isfile():
                    with archive.extractfile(entry) as src:
                        copy(src, target, entry.mode)
                elif entry.issym():
                    links.append((target, entry.linkname))
                else:
                    raise ValueError("归档包含不支持的设备文件或硬链接：" + entry.name)
    for target, link in links:
        if os.path.isabs(link) or "\\" in link or ":" in link:
            raise ValueError("归档软链接必须指向包内相对路径")
        safe_target(staging, os.path.relpath(os.path.normpath(
            os.path.join(os.path.dirname(target), link)), staging))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if os.path.lexists(target):
            raise ValueError("归档包含重复链接")
        os.symlink(link, target)
    for target, _ in links:
        safe_target(staging, os.path.relpath(target, staging))


def plugin_package(root):
    pkg = read_json(os.path.join(root, "package.json"))
    if not isinstance(pkg, dict) or not builtin.valid_name(pkg.get("name")):
        raise ValueError("package.json 缺少合法的 npm 插件名称")
    bundle = (pkg.get("dsh") or {}).get("bundle")
    if not isinstance(bundle, dict) or "patch" not in bundle:
        raise ValueError(pkg["name"] + " 未声明 dsh.bundle.patch（普通 npm 包不是 dsh 插件）")
    patch = bundle["patch"]
    # 当前锁定的 dsh-app-boot 只接受单个包内文件路径；数组会在真实启动时
    # 传入 path.join 并崩溃，因此预览阶段就拒绝。
    if not isinstance(patch, str) or not patch:
        raise ValueError(pkg["name"] + " 的 dsh.bundle.patch 格式无效")
    if not os.path.isfile(safe_target(root, patch)):
        raise ValueError(pkg["name"] + " 缺少 patch 文件，请下载构建后的发布包")
    main = pkg.get("main")
    if isinstance(main, str) and not os.path.isfile(safe_target(root, main)):
        raise ValueError(pkg["name"] + " 缺少入口 " + main + "，请下载 Release 中的构建包")
    entry = (pkg.get("cordis") or {}).get("entry")
    if isinstance(entry, str) and not os.path.isfile(safe_target(root, entry)):
        raise ValueError(pkg["name"] + " 缺少 Cordis 入口，请下载构建后的发布包")
    if pkg["name"] in builtin.OFFICIAL_BUNDLES or pkg["name"] in builtin.builtin_names():
        raise ValueError("系统插件只随签名 APK 更新，不支持用第三方归档覆盖")
    return pkg


def find_plugin_roots(staging, subdir=""):
    root = staging
    children = [os.path.join(staging, p) for p in os.listdir(staging)]
    if len(children) == 1 and os.path.isdir(children[0]):
        root = children[0]
    if subdir:
        root = safe_target(root, subdir)
        if not os.path.isdir(root):
            raise ValueError("仓库中不存在指定的插件目录：" + subdir)
    found = []
    for base, dirs, files in os.walk(root):
        depth = os.path.relpath(base, root).count(os.sep)
        dirs[:] = sorted(d for d in dirs if d not in (
            "node_modules", ".git", ".cache", "__pycache__") and not os.path.islink(os.path.join(base, d)))
        if depth > 5:
            dirs[:] = []
            continue
        if "package.json" in files:
            pkg = read_json(os.path.join(base, "package.json"), {})
            if isinstance(pkg, dict) and isinstance(pkg.get("dsh"), dict) and "bundle" in pkg["dsh"]:
                found.append(base)
                dirs[:] = []  # 找到发布包后不把它的内部目录当额外插件
    if not found:
        raise ValueError("未找到 dsh 插件：需要 package.json 中的 dsh.bundle.patch")
    if len(found) > 30:
        raise ValueError("仓库包含过多插件，请粘贴具体插件子目录链接")
    return found


_dependencies = None


def dependencies():
    global _dependencies
    if _dependencies is None:
        descriptor = importlib.util.spec_from_file_location("plugin_dependencies", os.path.join(os.path.dirname(__file__), "plugin-dependencies.py"))
        module = importlib.util.module_from_spec(descriptor)
        descriptor.loader.exec_module(module)
        _dependencies = module.Dependencies(globals())
    return _dependencies


def prepare_dependencies(root, pkg, archive_sha='', offline=False, restoring=False):
    progress('dependencies', '正在准备插件依赖：' + str(pkg.get('name', '')))
    return dependencies().prepare(root, pkg, archive_sha, offline, restoring)


_transactions = None


def transactions():
    global _transactions
    if _transactions is None:
        descriptor = importlib.util.spec_from_file_location("plugin_transactions", os.path.join(os.path.dirname(__file__), "plugin-transactions.py"))
        module = importlib.util.module_from_spec(descriptor)
        descriptor.loader.exec_module(module)
        _transactions = module.Transactions(globals())
    return _transactions


def register_plugin(root, source, expected_version=None, *, reviewed=False, restoring=False):
    pkg = plugin_package(root)
    name = pkg["name"]
    if not reviewed:
        raise ValueError('插件必须先完成静态预览并确认，不能直接安装')
    prepare_dependencies(root, pkg, restoring=restoring)
    source = dependencies().source_label(source)
    dest = local(os.path.join(PLUGIN_SRC, name))
    managed_root = os.path.realpath(local(PLUGIN_SRC))
    if os.path.commonpath([os.path.realpath(local(DSH_HOME)), managed_root]) != os.path.realpath(local(DSH_HOME)) \
            or os.path.commonpath([managed_root, os.path.realpath(os.path.dirname(dest))]) != managed_root:
        raise ValueError("插件目标目录越界，已停止安装")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    transaction = transactions()
    with transaction.workspace() as work:
        prepared = os.path.join(work, "new")
        shutil.copytree(root, prepared, symlinks=True)
        if not restoring:
            dependencies().inspect(prepared)
        for base, dirs, files in os.walk(prepared, followlinks=False):
            for item in dirs + files:
                candidate = os.path.join(base, item)
                if os.path.islink(candidate):
                    safe_target(prepared, os.path.relpath(candidate, prepared))
        old = os.path.join(work, "old")
        with builtin.operation_lock(check_cancel), committing('正在登记插件：' + name):
            builtin.ensure_runtime_modules()
            if expected_version is not None:
                current_dir = resolve_plugin_dir(name)
                current_package = read_json(os.path.join(current_dir, 'package.json'), {}) if current_dir else {}
                if current_package.get('version') != expected_version:
                    raise ValueError('插件版本在确认后发生变化，请重新检查更新')
            doc = builtin.read_manifest()
            if doc is None:
                builtin.ensure_profile_files()
                doc = builtin.new_manifest({})
            sources = read_json(local(SOURCES), {})
            if not isinstance(sources, dict):
                raise ValueError('插件来源记录异常，原件已保留')
            previous_sources = dict(sources)
            link = os.path.join(local(builtin.NODE_MODULES), name)
            if os.path.lexists(link) and not os.path.islink(link):
                raise ValueError(name + " 已由 pnpm 安装为实体目录，请先在终端移除该依赖再导入")
            marker = builtin.marker_path(name)
            previous_bundles = doc.get("dsh", {}).get("profile", {}).get("bundles", [])
            known = name in doc.get("dependencies", {}) or name in previous_bundles
            enabled = restoring and not os.path.isfile(marker) and (not known or name in previous_bundles)
            if os.path.lexists(marker) and (os.path.islink(marker) or not os.path.isfile(marker)):
                raise ValueError('插件停用标记类型异常，原件已保留')
            existed = os.path.lexists(dest)
            if existed and (os.path.islink(dest) or not os.path.isdir(dest)):
                raise ValueError("插件目标目录类型异常：" + name)
            bundles = doc.setdefault("dsh", {}).setdefault("profile", {}).setdefault("bundles", [])
            if enabled and name not in bundles:
                bundles.append(name)
            if not enabled and name in bundles:
                bundles.remove(name)
            doc["dsh"]["profile"]["patchReload"] = "startup"
            doc.setdefault("dependencies", {})[name] = "link:" + os.path.join(PLUGIN_SRC, name).replace("\\", "/")
            sources[name] = source or dependencies().source_label(repository_url(pkg)) or sources.get(name, "")
            marker_bytes = b'DSHA_REVIEW_REQUIRED\n' if not enabled and not os.path.lexists(marker) else None
            plan = transaction.prepare(work, name, prepared, doc, sources, marker_bytes)
            transaction.boundary('prepared')
            try:
                transaction.apply_file(work, 'marker', plan)
                if existed:
                    os.replace(dest, old)
                transaction.boundary('old-moved')
                os.replace(prepared, dest)
                transaction.boundary('new-moved')
                if os.path.islink(link):
                    os.unlink(link)
                if enabled:
                    os.makedirs(os.path.dirname(link), exist_ok=True)
                    os.symlink(dest, link, target_is_directory=True)
                lifecycle().queue_activation(name, dependencies().current(dest)['sha256'], str(pkg.get('version', '')),
                                             transaction=(transaction, work, plan), status='queued' if enabled else 'disabled')
                transaction.boundary('activation-replaced')
                transaction.apply_file(work, 'sources', plan)
                transaction.boundary('sources-replaced')
                if existed and name not in builtin.builtin_names():
                    lifecycle().retain(name, old, work, previous_sources.get(name, ""))
                transaction.boundary('history-retained')
                transaction.apply_file(work, 'manifest', plan)
                transaction.boundary('manifest-replaced')
                transaction.mark(work, 'committed')
                transaction.boundary('committed')
            except Exception:
                transaction.recover(work)
                raise
    return name


def cmd_import(archive, subdir="", source="", expected_versions=None):
    archive = local(archive)
    if not os.path.isfile(archive):
        raise ValueError("所选插件包不存在")
    if os.path.getsize(archive) > MAX_DOWNLOAD:
        raise ValueError("插件包过大（上限 256 MiB）")
    with tempfile.TemporaryDirectory(prefix="plugin-import-", dir=local(DSH_HOME)) as staging:
        extract_archive(archive, staging)
        found = find_plugin_roots(staging, subdir)
        names, failures, seen = [], [], set()
        # 重名包必须在任何写入之前拒绝，避免顺序覆盖。
        for root in found:
            name = read_json(os.path.join(root, "package.json"), {}).get("name")
            if name in seen:
                raise ValueError("归档含多个同名插件，请只保留一份：" + str(name))
            seen.add(name)
        for root in found:
            try:
                name = read_json(os.path.join(root, 'package.json'), {}).get('name')
                names.append(register_plugin(root, source, (expected_versions or {}).get(name)))
            except PluginCancelled:
                result('partial' if names else 'cancelled',
                       ('已安装 ' + '、'.join(names) + '；' if names else '') + '已取消其余安装，未提交的插件保持原状态', installed=names)
                return 1
            except Exception as error:
                failures.append(os.path.basename(root) + "：" + str(error))
        status = "partial" if names and failures else ("error" if failures else "ok")
        message = "已安装 " + "、".join(names) + "；重启 Web 后生效。" if names else ""
        if failures:
            message += "\n未安装：" + "\n".join(failures)
        result(status, message, installed=names)
        return 0 if not failures else 1


def resolve_plugin_dir(name, discovered=None):
    if not builtin.valid_name(name):
        raise ValueError("无效的插件名称")
    if name in builtin.builtin_names():
        # 系统插件永远从当前 APK 刷新的 /root/dsha-* 读取，旧 profile 的
        # 同名实体或旧备份副本不能改变列表、导出或回退所看到的版本。
        directory = builtin.entity_dir(name)
        return local(directory) if directory else None
    path = os.path.join(local(builtin.NODE_MODULES), name)
    if os.path.isfile(os.path.join(path, "package.json")):
        return os.path.realpath(path)
    if discovered is not None and name in discovered:
        return discovered[name]['directory']
    directory = builtin.entity_dir(name)
    if directory:
        return local(directory)
    path = os.path.join(local(builtin.NODE_MODULES), name)
    if os.path.isfile(os.path.join(path, "package.json")):
        return os.path.realpath(path)
    return None


def cmd_export(names, out):
    progress('export', '正在打包插件…')
    names = json.loads(names)
    if not isinstance(names, list) or not names or len(names) > 30:
        raise ValueError("请选择 1–30 个插件")
    out = local(out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    total, count = 0, 0
    excluded = []

    def add_tree(archive, path, arc, ancestors):
        nonlocal total, count
        progress('export', '正在打包：' + arc, count)
        real = os.path.realpath(path)
        basename = os.path.basename(path)
        if basename in ('.npmrc', '.yarnrc.yml', '.env', '.dsha-dependencies.json') or basename.startswith('.env.'):
            excluded.append(arc)
            return
        if basename == 'pnpm-lock.yaml':
            dependencies().validate_lock(real)
        if real in ancestors:
            raise ValueError("插件依赖含循环链接，无法导出：" + arc)
        if not os.path.exists(real):
            raise ValueError("插件存在失效链接：" + arc)
        info = archive.gettarinfo(real, arcname=arc)
        if not (info.isdir() or info.isfile()):
            raise ValueError("插件包含不可导出的特殊文件：" + arc)
        count += 1
        total += info.size
        if total > MAX_EXPANDED or count > MAX_FILES:
            raise ValueError("导出内容过大，请减少所选插件")
        if info.isfile():
            with open(real, "rb") as stream:
                archive.addfile(info, stream)
        else:
            archive.addfile(info)
            for child in sorted(os.listdir(real)):
                if child not in (".git", ".cache", "__pycache__", ".DS_Store"):
                    add_tree(archive, os.path.join(real, child), arc + "/" + child, ancestors | {real})

    try:
        with tarfile.open(out, "w:gz", dereference=True) as archive:
            for name in dict.fromkeys(names):
                if name in builtin.OFFICIAL_BUNDLES or name in builtin.builtin_names():
                    raise ValueError("系统插件由当前签名 APK 重建，不提供独立导出")
                directory = resolve_plugin_dir(name)
                if not directory:
                    raise ValueError("找不到插件实体：" + str(name))
                add_tree(archive, directory, "plugins/" + name, set())
        if os.path.getsize(out) > MAX_DOWNLOAD:
            raise ValueError("导出包超过 256 MiB，请减少插件数量")
    except Exception:
        if os.path.isfile(out):
            os.remove(out)
        raise
    result("ok", "插件包已生成；凭据配置未导出，原生锁文件保留，导入时重新核对内容" if excluded else "插件包已生成", path=out, excluded=excluded)
    return 0


def cmd_delete(name):
    """只移除指定第三方插件；不追随链接去删除用户工作区或共享 pnpm 仓库。"""
    if not builtin.valid_name(name):
        raise ValueError("无效的插件名称")
    if name in builtin.OFFICIAL_BUNDLES or name in builtin.builtin_names():
        raise ValueError("官方核心和内置插件请使用禁用开关，不能删除")
    with builtin.operation_lock(check_cancel), committing('正在删除插件：' + name):
        doc = builtin.read_manifest()
        if doc is None:
            raise ValueError("插件配置不存在")
        bundles = doc.get("dsh", {}).get("profile", {}).get("bundles", [])
        deps = doc.get("dependencies", {})
        if name not in deps and name not in bundles:
            raise ValueError("插件未安装或已经删除，请刷新列表")
        sources = read_json(local(SOURCES), {})
        if not isinstance(sources, dict):
            sources = {}
        previous_sources = dict(sources)
        paths = [(local(PLUGIN_SRC), os.path.join(local(PLUGIN_SRC), name)),
                 (local(builtin.NODE_MODULES), os.path.join(local(builtin.NODE_MODULES), name)),
                 (local(builtin.NODE_MODULES), builtin.marker_path(name)),
                 (local(DSH_HOME), lifecycle().history_path(name))]
        for parent, path in paths:
            # scope 目录本身可能被换成包外软链，不能只校验 npm 名称。
            root = os.path.realpath(parent)
            home = os.path.realpath(local(DSH_HOME))
            if os.path.commonpath([home, root]) != home:
                raise ValueError("插件目录指向安装目录以外，已取消删除：" + name)
            actual_parent = os.path.realpath(os.path.dirname(path))
            if os.path.commonpath([root, actual_parent]) != root:
                raise ValueError("插件目录越界，已取消删除：" + name)
        with tempfile.TemporaryDirectory(prefix=".plugin-delete-", dir=local(DSH_HOME)) as work:
            moved = []
            source_written = False
            try:
                for index, (_, path) in enumerate(paths):
                    if os.path.lexists(path):
                        staged = os.path.join(work, str(index))
                        os.replace(path, staged)
                        moved.append((path, staged))
                doc.setdefault("dependencies", {}).pop(name, None)
                profile = doc.setdefault("dsh", {}).setdefault("profile", {})
                profile["bundles"] = [bundle for bundle in bundles if bundle != name]
                sources.pop(name, None)
                write_json(local(SOURCES), sources)
                source_written = True
                builtin.write_manifest(doc)
            except Exception:
                for path, staged in reversed(moved):
                    os.replace(staged, path)
                if source_written:
                    write_json(local(SOURCES), previous_sources)
                raise
    result("ok", "已删除 " + name + "；重启 Web 后停止加载。对话和其他插件保留。")
    return 0


def open_url(url):
    check_cancel()
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("下载地址必须是 HTTPS 链接")
    response = urllib.request.urlopen(urllib.request.Request(url, headers={
        "User-Agent": "DSHA-plugin-manager", "Accept": "application/vnd.github+json"
        if parsed.hostname == "api.github.com" else "*/*"}), timeout=30)
    if urllib.parse.urlsplit(response.url).scheme != "https":
        response.close()
        raise ValueError("下载被重定向到非 HTTPS 地址")
    return response


def archive_download_url(url):
    """GitHub 归档直接使用官方 codeload，保留 refs/heads 或 refs/tags。"""
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme == "https" and parsed.hostname in ("github.com", "www.github.com") \
            and not parsed.username and not parsed.password and not parsed.port:
        match = re.fullmatch(r"/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+)/archive/(.+)\.(zip|tar\.gz)", parsed.path)
        if match:
            owner, repo, revision, extension = match.groups()
            return "https://codeload.github.com/%s/%s/%s/%s" % (owner, repo,
                    "zip" if extension == "zip" else "tar.gz", revision)
    return url


def download(url, target):
    progress('download', '正在下载插件包…')
    with open_url(archive_download_url(url)) as response, open(target, "wb") as stream:
        total = int(response.headers.get("Content-Length", "0"))
        if total > MAX_DOWNLOAD:
            raise ValueError("插件包超过 256 MiB")
        size = 0
        while True:
            check_cancel()
            data = response.read(65536)
            if not data:
                break
            size += len(data)
            if size > MAX_DOWNLOAD:
                raise ValueError("插件包超过 256 MiB")
            stream.write(data)
            progress('download', '正在下载插件包…', size, total)
        if total and size != total:
            raise ValueError('下载未完成，请重新尝试')


def cmd_download(url, subdir="", source="", *, consume=None):
    with tempfile.TemporaryDirectory(prefix="plugin-download-", dir=local(DSH_HOME)) as staging:
        target = os.path.join(staging, "archive")
        download(url, target)
        return (consume or cmd_import)(target, subdir, source or url)


def github_revision(owner, repo, tree=""):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}", owner) or not re.fullmatch(
            r"[A-Za-z0-9_.-]+", repo) or repo in (".", ".."):
        raise ValueError("无效的 GitHub 仓库")
    revision, subdir = "HEAD", ""
    if tree:
        parts = tree.split("/")
        if len(parts) > 16 or any(p in ("", ".", "..") for p in parts):
            raise ValueError("无效的仓库目录")
        # 最长匹配支持 feature/foo 等带斜杠分支；不默默改成默认分支。
        for end in range(len(parts), 0, -1):
            candidate = "/".join(parts[:end])
            api = "https://api.github.com/repos/%s/%s/commits/%s" % (
                owner, repo, urllib.parse.quote(candidate, safe=""))
            try:
                with open_url(api) as response:
                    commit = json.loads(response.read(1024 * 1024))
                revision = commit["sha"]
                subdir = "/".join(parts[end:])
                break
            except urllib.error.HTTPError as error:
                if error.code not in (404, 422):
                    raise
        else:
            raise ValueError("GitHub 分支或标签不存在")
    else:
        with open_url('https://api.github.com/repos/%s/%s/commits/HEAD' % (owner, repo)) as response:
            revision = json.loads(response.read(1024 * 1024))['sha']
    if not re.fullmatch('[a-f0-9]{40,64}', revision):
        raise ValueError('GitHub 未返回有效提交')
    return revision, subdir


def cmd_github(owner, repo, tree="", *, consume=None):
    revision, subdir = github_revision(owner, repo, tree)
    url = "https://codeload.github.com/%s/%s/tar.gz/%s" % (
        owner, repo, urllib.parse.quote(revision, safe=""))
    source = "https://github.com/%s/%s" % (owner, repo) + ("/tree/" + tree if tree else "")
    return cmd_download(url, subdir, source, **({"consume": consume} if consume else {}))


def cmd_release(owner, repo, tag, *, consume=None):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}", owner) or not re.fullmatch(
            r"[A-Za-z0-9_.-]+", repo) or repo in (".", ".."):
        raise ValueError("无效的 GitHub 仓库")
    suffix = "latest" if tag == "latest" else "tags/" + urllib.parse.quote(tag, safe="")
    with open_url("https://api.github.com/repos/%s/%s/releases/%s" % (owner, repo, suffix)) as response:
        release = json.loads(response.read(4 * 1024 * 1024))
    archives = [asset for asset in release.get("assets", [])
                if re.search(r"\.(zip|tar|tar\.gz|tgz)$", asset.get("name", ""), re.I)]
    if not archives:
        raise ValueError("这个 Release 没有插件压缩包附件，请使用作者提供的构建包或仓库链接")
    if len(archives) != 1:
        raise ValueError("这个 Release 含多个压缩包，请复制所需附件的下载链接：\n"
                         + "\n".join(a["name"] for a in archives))
    return cmd_download(archives[0]["browser_download_url"], **({"consume": consume} if consume else {}))


def cmd_list(message="插件状态已同步"):
    doc = builtin.read_manifest() or {}
    bundles = doc.get("dsh", {}).get("profile", {}).get("bundles", [])
    deps = doc.get("dependencies", {})
    sources = read_json(local(SOURCES), {})
    if not isinstance(sources, dict):
        sources = {}
    discovered = builtin.discover_plugins()
    names = list(dict.fromkeys(list(builtin.OFFICIAL_BUNDLES) + builtin.builtin_names()
                              + list(deps) + bundles + list(discovered)))
    items = []
    updates = lifecycle().read(lifecycle().path('plugin-updates.json'), {})
    for name in names:
        if not builtin.valid_name(name):
            continue
        official = name in builtin.OFFICIAL_BUNDLES
        directory = resolve_plugin_dir(name, discovered)
        pkg = read_json(os.path.join(directory, "package.json"), {}) if directory else {}
        if name not in builtin.OFFICIAL_BUNDLES and name not in builtin.builtin_names() \
                and name not in bundles and not (pkg.get("dsh") or {}).get("bundle"):
            continue
        items.append(dict(name=name, enabled=name in bundles, builtin=name in builtin.builtin_names(),
                          official=official, available=official or directory is not None,
                          version=pkg.get("version", ""), description=pkg.get("description", ""),
                          source=sources.get(name, "") or repository_url(pkg),
                          exportable=not official and directory is not None,
                          deletable=not official and name not in builtin.builtin_names()
                                    and (name in deps or name in bundles),
                          internal=official or name == 'dsh-app-integration',
                          detected=name in discovered and name not in deps and name not in bundles,
                          location='、'.join(discovered.get(name, {}).get('locations', []))))
        update = updates.get(name, {})
        previous = lifecycle().history_info(name) if not official and name not in builtin.builtin_names() else {}
        items[-1].update(latestVersion=update.get('latestVersion', ''), updateAvailable=bool(update.get('available'))
                         and update.get('installedVersion') == str(pkg.get('version', '')),
                         updatePreviewId=update.get('previewId', ''), updateMessage=update.get('message', '')
                         if update.get('installedVersion') == str(pkg.get('version', '')) else '',
                         rollbackVersion=previous.get('version', ''), compatibility=update.get('compatibility', ''))
    mode = lifecycle().read(lifecycle().path('plugin-safe-mode.json'), {})
    activations = lifecycle().activation_state()['entries']
    for item in items:
        activation = activations.get(item['name'], {})
        item['loadState'] = activation.get('status', '') if activation.get('version') == item.get('version') else ''
        marker = builtin.marker_path(item['name'])
        if not item.get('enabled') and os.path.isfile(marker) and os.path.getsize(marker) <= 128:
            with open(marker, encoding='utf8') as stream:
                if stream.read() == 'DSHA_REVIEW_REQUIRED\n':
                    item['loadState'] = 'review-required'
    pending, unreadable = lifecycle().pending_previews()
    result("ok", message, items=items, safeMode=bool(mode.get('active')), pendingReviews=pending, unreadableReviews=unreadable)
    return 0


def cmd_npm(package, *, consume=None):
    """npm pack 只下载发布包；再走与界面完全相同的校验、依赖准备和登记。"""
    spec = package.removeprefix("npm:")
    match = re.fullmatch(r"((?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*)(?:@([A-Za-z0-9][A-Za-z0-9._+~-]*))?", spec)
    if not match or not builtin.valid_name(match.group(1)):
        raise ValueError("请使用 npm 包名或 包名@版本，例如 @作者/插件@1.0.0")
    if not shutil.which("npm"):
        raise ValueError("npm 尚未就绪，请关闭终端后重新打开")
    with tempfile.TemporaryDirectory(prefix="plugin-npm-", dir=local(DSH_HOME)) as staging:
        progress('download', '正在获取 npm 发布包…')
        process = run_package_command(["npm", "pack", "--json", "--ignore-scripts", "--pack-destination", staging, "--", spec], cwd=staging)
        if process.returncode:
            raise ValueError("npm 插件下载失败：" + (process.stderr or process.stdout)[-1600:].strip())
        packed = json.loads(process.stdout)
        if not isinstance(packed, list) or len(packed) != 1:
            raise ValueError("npm 未返回唯一插件包")
        filename = packed[0].get("filename", "")
        target = safe_target(staging, filename)
        if not filename or not os.path.isfile(target) or os.path.getsize(target) > MAX_DOWNLOAD:
            raise ValueError("npm 插件归档无效或超过 256 MiB")
        return (consume or cmd_import)(target, source="npm:" + spec)


def main():
    os.makedirs(local(DSH_HOME), exist_ok=True)
    args = sys.argv[1:]
    try:
        check_cancel()
        # 一次容器启动内完成变更与状态读取；仍沿用原锁、审阅及提交边界。
        if args == ["refresh"]:
            with builtin.operation_lock(check_cancel):
                progress('refresh', '正在检测已安装插件…', cancellable=False)
                if builtin.register() != 0:
                    raise ValueError('部分内置插件待修复，请检查环境')
                return cmd_list('插件检测完成；变更后重启 Web 生效')
        if len(args) == 2 and args[0] in ('enable-list', 'disable-list'):
            name = args[1]
            if not builtin.valid_name(name):
                raise ValueError('无效的插件名称')
            with builtin.operation_lock(check_cancel):
                enable = args[0] == 'enable-list'
                progress('configure', '正在更新插件状态…', cancellable=False)
                if (builtin.enable_plugin(name) if enable else builtin.disable_plugin(name)) != 0:
                    raise ValueError('插件状态更新失败，请检查配置或审阅状态')
                return cmd_list('已' + ('启用 ' if enable else '禁用 ') + name + '；重启 Web 后生效')
        if len(args) == 2 and args[0] == 'delete-list':
            if cmd_delete(args[1]) != 0:
                return 1
            with builtin.operation_lock():
                progress('refresh', '插件已删除，正在同步列表…', cancellable=False)
                return cmd_list('已删除 ' + args[1] + '；重启 Web 后停止加载。对话和其他插件保留。')
        if args[0] == "inspect" and len(args) == 2:
            return lifecycle().inspect(args[1])
        if args[0] == "install-preview" and len(args) == 3:
            return lifecycle().install_preview(args[1], args[2])
        if args[0] == "show-preview" and len(args) == 2:
            return lifecycle().show_preview(args[1])
        if args[0] == "review-enable" and len(args) == 2:
            return lifecycle().review_existing(args[1])
        if args[0] == 'review-restored' and len(args) == 3:
            return lifecycle().review_restored(args[1],args[2])
        if args[0] in ('begin-load', 'complete-load', 'failed-load') and len(args) == 2:
            return lifecycle().loading(args[0].split('-')[0], args[1])
        if args[0] == 'recover-installs' and len(args) == 2:
            import uuid
            if str(uuid.UUID(args[1])) != args[1]:
                raise ValueError('插件恢复授权标识无效')
            proof = local('/root/.dsha-plugin-recovery-' + args[1])
            if os.path.islink(proof) or not os.path.isfile(proof) or os.path.getsize(proof) != 25:
                raise ValueError('插件恢复必须从原生维护入口进行')
            with open(proof, encoding='ascii') as stream:
                if stream.read() != 'RECOVER_PLUGIN_OPERATIONS':
                    raise ValueError('插件恢复授权标识无效')
            os.unlink(proof)
            transactions().recover_all()
            result('ok', '中断的插件操作已恢复，原件已保留')
            return 0
        if args[0] == "discard-preview" and len(args) == 2:
            return lifecycle().discard_preview(args[1])
        if args[0] == "check-updates" and len(args) in (1, 2):
            return lifecycle().check_updates(args[1] if len(args) == 2 else '')
        if args[0] == "prepare-update" and len(args) == 2:
            return lifecycle().prepare_update(args[1])
        if args[0] == "rollback" and len(args) in (2, 3):
            return lifecycle().rollback(*args[1:])
        if args[0] == "safe-mode" and len(args) == 2:
            return lifecycle().safe_mode(args[1])
        if args[0] == "import" and len(args) == 2:
            return lifecycle().inspect({"command": "file " + __import__("shlex").quote(args[1])})
        if args[0] == "export" and len(args) == 3:
            with builtin.operation_lock(check_cancel):
                return cmd_export(args[1], args[2])
        if args[0] == "delete" and len(args) == 2:
            return cmd_delete(args[1])
        if args[0] == "download" and len(args) == 2:
            return lifecycle().inspect({"command": " ".join(__import__("shlex").quote(arg) for arg in args)})
        if args[0] == "npm" and len(args) == 2:
            return lifecycle().inspect({"command": " ".join(__import__("shlex").quote(arg) for arg in args)})
        if args[0] == "github" and len(args) in (3, 4):
            return lifecycle().inspect({"command": " ".join(__import__("shlex").quote(arg) for arg in args)})
        if args[0] == "release" and len(args) == 4:
            return lifecycle().inspect({"command": " ".join(__import__("shlex").quote(arg) for arg in args)})
        if args[0] == "list":
            with builtin.operation_lock(check_cancel):
                return cmd_list()
        raise ValueError("不支持的插件操作")
    except PluginCancelled as error:
        result('cancelled', str(error))
    except urllib.error.HTTPError as error:
        hint = {403: "访问受限或 GitHub API 额度已用完", 404: "链接或指定版本不存在",
                429: "请求过于频繁，请稍后重试"}.get(error.code, "服务器返回错误")
        result("error", "下载失败：%s（HTTP %s），也可下载压缩包后本地导入" % (hint, error.code))
    except urllib.error.URLError as error:
        if isinstance(error.reason, ssl.SSLCertVerificationError):
            result("error", "HTTPS 证书校验失败。请更新 DSHA 并重新进入插件页，检查设备日期及网络代理；未关闭证书校验。")
        else:
            result("error", "网络连接失败：" + str(error.reason))
    except Exception as error:
        result("error", str(error))
    return 1


if __name__ == "__main__":
    sys.exit(main())
