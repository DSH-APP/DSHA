#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""备份格式 v3：独立快照、逐文件摘要、预检和可回滚的多目录提交。

只在容器内运行；--root 可指向独立测试目录。旧归档先归一化，绝不按归档
中的绝对链接写入宿主目录。备份与预检均不修改运行中的 .dsh。
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tarfile
import tempfile
import time
import uuid
from contextlib import contextmanager

SCOPES = {"full": None, "sessions": ("sessions", "storages", "attachments"),
          "settings": ("settings.yaml",),
          "plugins": ("profiles", "plugin-src", "plugin-sources.json", "plugin-history", "plugin-safe-mode.json")}
HOT = ("sessions", "storages", "attachments", "settings.yaml")
MANIFEST = ".dsha-backup-manifest.json"
SKIP = {".pnpm-store", ".cache", "session_projcache", "dist-cache",
        "node_modules", MANIFEST, ".dsha-plugin-src", "DSHA-README.txt"}
# 仅排除 App 管理的顶层缓存；插件内同名目录与配对密钥均属于备份内容。
TOP_CACHE = {"plugin-previews", "plugin-updates.json", ".plugins.lock"}
# APK 管理的缓存只有摘要一致时才排除；用户修改或另加的文件仍备份。
ADB_ARCHIVE_SHA256 = '173000bd60f8d58f90f988320b9b675540806139da9eae64ea78a0e6ecf5d9d4'
ADB_WHEEL_CACHE = {
    "adb_shell_wifi-0.5.0-py3-none-any.whl": "231d4c608f2631ddb2df55bdc7ea111c43275277d04fcbc791f8b613dfaa391a",
    "aiofiles-25.1.0-py3-none-any.whl": "abe311e527c862958650f9438e859c1fa7568a141b22abcd015e120e86a85695",
    "async_timeout-5.0.1-py3-none-any.whl": "39e3809566ff85354557ec2398b55e096c8364bacac9405a7a1fa429e77fe76c",
    "cffi-2.1.1-cp312-cp312-manylinux2014_aarch64.manylinux_2_17_aarch64.whl": "68e62fe11f30d5ca8289242866f0a5291402d8529ca2178ab8afc5c9694ae890",
    "cryptography-50.0.0-cp311-abi3-manylinux2014_aarch64.manylinux_2_17_aarch64.whl": "fd9192b7b70c573d7f214eb1ae35e00d359f6f5e4b27c7e21e30de1fc6204645",
    "ifaddr-0.2.0-py3-none-any.whl": "085e0305cfe6f16ab12d72e2024030f5d52674afad6911bb1eee207177b8a748",
    "pip-26.2.1-py3-none-any.whl": "71138adf1f4ca900cdb7d289c21b7494329f2332b6d85f0e1c42108c0384ed3e",
    "pyasn1-0.6.4-py3-none-any.whl": "deda9277cfd454080ec40b207fb6df82206a3a2688735233cdcd8d3d565f088b",
    "pycparser-3.0-py3-none-any.whl": "b727414169a36b7d524c1c3e31839a521725078d7b2ff038656844266160a992",
    "pyopenssl-26.4.0-py3-none-any.whl": "f0eb0cb2d581d3ad2b9c489468485e7f2ab6727d08401bcf9d824c3caddf3c1c",
    "rsa-4.9.1-py3-none-any.whl": "68635866661c6836b8d39430f97a996acbd61bfa49406748ea243539fe239762",
    "setuptools-84.0.0-py3-none-any.whl": "51a52592b3b99e102b609654876bd65f19f999935166d1352678931132b0c670",
    "spake2_cffi-1.0.1-cp312-cp312-manylinux2014_aarch64.manylinux_2_17_aarch64.manylinux_2_28_aarch64.whl": "72267aa5bc611528235f274c3b261cb4650c71a65ecf7dbfe181960798588769",
    "typing_extensions-4.16.0-py3-none-any.whl": "481caa481374e813c1b176ada14e97f1f67a4539ce9cfeb3f350d78d6370c2e8",
    "zeroconf-0.150.0-cp312-cp312-manylinux2014_aarch64.manylinux_2_17_aarch64.manylinux_2_28_aarch64.whl": "e17a3adc2e4e425c59a1f2a72e289706ec6655fead4d5287f23e1748663df7dc"
}
MAX_BYTES = 16 * 1024 ** 3
MAX_FILES = 300000
RESERVE = 32 * 1024 ** 2
GLOBAL_NM = (Path("/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules"),
             Path("/usr/local/lib/node_modules"))


def package_name(name):
    return isinstance(name, str) and re.fullmatch(r"(?:@[A-Za-z0-9_-][A-Za-z0-9._-]*/)?[A-Za-z0-9_-][A-Za-z0-9._-]*", name)


def dump(path, value):
    path = Path(path)
    tmp = path.with_name(path.name + ".tmp")
    with tmp.open("w", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def remove(path):
    path = Path(path)
    if path.is_symlink() or path.is_file():
        path.unlink()
    elif path.exists():
        shutil.rmtree(path)


def unlink_symlink(path):
    """若目标是软链，先摘掉**链接本身**再让调用方建普通文件。

    归档里这条文件条目表达的意思就是「这里应该是一个普通文件」。``open("xb")`` 是
    ``O_EXCL``，但对一条**已存在的悬空软链**它会穿过链接去创建目标 —— 内容就落到
    链接指向的地方，而不是路径本身。

    现有调用方都传全新临时目录，本来碰不到；摘掉链接是让这条不变式不依赖调用方
    守规矩（tools/test-backup-engine.py 里有断言钉住）。
    """
    path = Path(path)
    if path.is_symlink():
        path.unlink()


def copy_data(src, dst, exclude=(), ancestors=(), checks=None):
    """热目录解引用，同时检测循环、特殊文件和复制期间的变化。"""
    src, dst = Path(src), Path(dst)
    resolved = src.resolve(strict=True)
    if resolved in ancestors:
        raise ValueError("数据目录含循环链接：" + str(src))
    mode = resolved.stat().st_mode
    if stat.S_ISDIR(mode):
        dst.mkdir(parents=True, exist_ok=True)
        before = sorted(p.name for p in resolved.iterdir() if p.name not in exclude)
        for name in before:
            copy_data(resolved / name, dst / name, exclude, ancestors + (resolved,), checks)
        after = sorted(p.name for p in resolved.iterdir() if p.name not in exclude)
        if before != after:
            raise ValueError("备份期间目录内容变化，请停止 Web 后重试：" + str(src))
        if checks is not None:
            checks.append((resolved, before, exclude))
    elif stat.S_ISREG(mode):
        before = resolved.stat()
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(resolved, dst)
        os.chmod(dst, stat.S_IMODE(mode) & 0o777)
        after = resolved.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise ValueError("备份期间文件变化，请停止 Web 后重试：" + str(src))
        if checks is not None:
            checks.append((resolved, (after.st_size, after.st_mtime_ns), None))
    else:
        raise ValueError("不能备份特殊文件：" + str(src))


def bundled_cache(path, expected, checks=None):
    matches = bool(expected) and not path.is_symlink() and path.is_file() and digest(path) == expected
    if matches and checks is not None:
        checks.append((path, expected))
    return matches


def copy_wheel_cache(src, dst, checks, cache_checks):
    """只丢弃 APK 可重建的原始 wheel；自定义 wheel、说明和嵌套目录保持原样。"""
    if src.is_symlink() or not src.is_dir():
        return copy_data(src, dst, exclude=SKIP, checks=checks)
    before = sorted(p.name for p in src.iterdir() if p.name not in SKIP)
    generated = {src_name for src_name in before
                 if bundled_cache(src / src_name, ADB_WHEEL_CACHE.get(src_name), cache_checks)}
    excluded = SKIP | generated
    names = sorted(p.name for p in src.iterdir() if p.name not in excluded)
    for name in names:
        copy_data(src / name, dst / name, exclude=SKIP, checks=checks)
    checks.append((src, before, SKIP))


def inventory(stage):
    result = {}
    for path in sorted(Path(stage).rglob("*")):
        name = path.relative_to(stage).as_posix()
        if name == MANIFEST:
            continue
        if path.is_symlink():
            raise ValueError("快照中不应残留软链接：" + name)
        if path.is_file():
            result[name] = {"size": path.stat().st_size, "sha256": digest(path)}
        elif path.is_dir():
            result[name] = {"directory": True}
    return result


def plugin_support(filename):
    import importlib.util
    path = Path(__file__).parent / filename
    if not path.is_file():
        path = Path(__file__).parent / ('.dsha-' + filename)
    specification = importlib.util.spec_from_file_location(filename.replace('-', '_'), path)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def inline_plugins(root, stage, checks=None):
    builtin = plugin_support('register-builtin-plugins.py')
    builtin.ROOT = ''
    builtin.DSH_HOME = str(root / '.dsh')
    builtin.PROFILE = str(root / '.dsh/profiles/web')
    builtin.NODE_MODULES = str(root / '.dsh/profiles/web/node_modules')
    # 在扫描之前记住包目录及 scope 目录；扫描期间的新安装也必须使本次快照失败。
    if checks is not None:
        bases = [root / 'node_modules', root / '.dsh/node_modules', root / '.dsh/profiles/node_modules',
                 root / '.dsh/plugin-src'] + list((root / '.dsh/profiles').glob('*/node_modules'))
        if str(root) == '/root':
            bases += list(GLOBAL_NM)
        for base in bases:
            base = Path(base)
            if base.is_dir():
                children = list(base.iterdir())
                checks.append((base.resolve(), sorted(path.name for path in children), set()))
                for child in children:
                    if child.name.startswith('@') and child.is_dir():
                        checks.append((child.resolve(), sorted(path.name for path in child.iterdir()), set()))
    discovered = builtin.discover_plugins(home_directory=str(root), include_global=str(root) == '/root')
    return plugin_support('backup-plugin-graph.py').archive_plugins(
        root, stage, copy_data, SKIP, checks, package_name, GLOBAL_NM, discovered)


def make_backup(root, output, scope, app_version="unknown", app_code=0,
                workdir="deepseek-harness", native_config=None):
    root, output = Path(root), Path(output)
    if (root / ".dsha-restore-journal.json").exists():
        raise ValueError("上次恢复尚未完成，请先启动 DSHA 完成事务恢复")
    if not (root / ".dsh").is_dir():
        raise ValueError("NO_DSH_DIR：尚无可备份的环境数据")
    with tempfile.TemporaryDirectory(prefix=".dsha-snapshot-", dir=root) as temp:
        stage = Path(temp)
        checks = []
        cache_checks = []
        (stage / ".dsh").mkdir()
        names = SCOPES[scope]
        if names is None:
            names = sorted(p.name for p in (root / ".dsh").iterdir() if p.name not in SKIP | TOP_CACHE)
            checks.append((root / ".dsh", names, SKIP | TOP_CACHE))
        for name in names:
            if name == "plugin-src":
                continue  # 已安装源码按 profile 依赖内联，避免重复和无引用缓存。
            src = root / ".dsh" / name
            if os.path.lexists(src):
                if scope == "full" and name == "adb-wheels.tar.gz" and bundled_cache(src, ADB_ARCHIVE_SHA256, cache_checks):
                    continue
                if scope == "full" and name == "wheels":
                    copy_wheel_cache(src, stage / ".dsh" / name, checks, cache_checks)
                    continue
                copy_data(src, stage / ".dsh" / name,
                          exclude=SKIP - {"node_modules"} if name == "plugin-history" else SKIP, checks=checks)
        plugins = inline_plugins(root, stage, checks) if scope in ("full", "plugins") else []
        if scope == "full":
            wd = Path(workdir)
            if not wd.is_absolute():
                wd = root / wd
            for name in (".env", "dsh-web.log"):
                if (wd / name).is_file():
                    copy_data(wd / name, stage / ".dsha-workdir" / name, checks=checks)
        if native_config and scope in ("full", "settings"):
            dump(stage / ".dsha-native-config.json", json.loads(Path(native_config).read_text(encoding="utf-8")))
        for path, previous, excluded in checks:
            if excluded is None:
                now = path.stat()
                current = (now.st_size, now.st_mtime_ns)
            else:
                current = sorted(p.name for p in path.iterdir() if p.name not in excluded)
            if current != previous:
                raise ValueError("备份期间数据变化，请停止 Web 后重试：" + str(path))
        for path, expected in cache_checks:
            if not bundled_cache(path, expected):
                raise ValueError("备份期间缓存内容变化，请停止 Web 后重试：" + str(path))
        entries = inventory(stage)
        if not any(not row.get("directory") for row in entries.values()):
            raise ValueError("NOTHING_TO_PACK：所选范围没有可备份内容")
        total = sum(row.get("size", 0) for row in entries.values())
        if total > MAX_BYTES or len(entries) > MAX_FILES:
            raise ValueError("备份超出容量或文件数上限")
        version = "unknown"
        installed = Path("/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json")
        if installed.is_file():
            version = json.loads(installed.read_text()).get("version", "unknown")
        manifest = {"formatVersion": 4, "scope": scope, "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                    "appVersion": app_version, "appVersionCode": app_code, "dshVersion": version,
                    "workdir": workdir, "plugins": plugins, "pluginDependencyGraph": getattr(plugins, "graph", {}), "inventory": entries, "bytes": total}
        dump(stage / MANIFEST, manifest)
        temp_out = output.with_name(output.name + ".part")
        try:
            with tarfile.open(temp_out, "w:gz", compresslevel=3) as tar:
                for path in sorted(stage.iterdir()):
                    tar.add(path, arcname=path.name)
            with tempfile.TemporaryDirectory(prefix=".dsha-verify-", dir=root) as verify:
                inspect_archive(temp_out, Path(verify), scope)
            os.replace(temp_out, output)
        finally:
            if temp_out.exists():
                temp_out.unlink()
        return {"scope": scope, "files": sum(not v.get("directory") for v in entries.values()),
                "warnings": plugin_warnings(manifest),
                "bytes": output.stat().st_size, "unpackedBytes": total, "sha256": digest(output)}


def plugin_warnings(manifest):
    return ['插件原有依赖缺失，源码已保留，恢复后仍需补装：' + row['name'] + ' → ' + name
            for row in manifest.get('pluginDependencyGraph', {}).values()
            for name in row.get('missing', [])]


def safe_name(name):
    if name in (".", "./"):
        return ""
    parts = PurePosixPath(name).parts
    if not parts or name.startswith("/") or ".." in parts or "\\" in name or ":" in name:
        raise ValueError("归档路径不安全：" + name[:120])
    return "/".join(p for p in parts if p != ".")


def inspect_archive(archive, stage, filename_scope="full"):
    """完整读完 gzip，手动提取普通文件；拒绝重复、越界和未知成员类型。"""
    stage = Path(stage)
    stage.mkdir(parents=True, exist_ok=True)
    seen, links, total, files = set(), [], 0, 0
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar:
            name = safe_name(member.name)
            if not name:
                continue
            if name in seen:
                raise ValueError("归档包含重复路径：" + name)
            seen.add(name)
            total += member.size
            if len(seen) > MAX_FILES or member.size < 0 or total > MAX_BYTES:
                raise ValueError("归档超过解压上限")
            dst = stage / name
            if member.isdir():
                dst.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                files += 1
                if shutil.disk_usage(stage).free < member.size + RESERVE:
                    raise ValueError("空间不足，尚未修改现有数据")
                dst.parent.mkdir(parents=True, exist_ok=True)
                unlink_symlink(dst)
                with tar.extractfile(member) as src, dst.open("xb") as out:
                    shutil.copyfileobj(src, out, 1024 * 1024)
                if dst.stat().st_size != member.size:
                    raise ValueError("归档文件不完整：" + name)
                os.chmod(dst, member.mode & 0o777)
            elif member.issym():
                links.append((name, member.linkname))
            else:
                raise ValueError("归档含不支持的成员：" + name)
        # tar 结束块后仍需读到 gzip 尾部，验证 CRC 和截断。
        while tar.fileobj.read(1024 * 1024):
            pass
    manifests = [p for p in stage.rglob(MANIFEST) if "node_modules" not in p.parts]
    if not manifests:
        manifests = list(stage.rglob("backup-manifest.json"))
    manifest = None
    for path in sorted(manifests, key=lambda p: len(p.parts)):
        value = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError("备份清单格式无效")
        if manifest is not None and value != manifest:
            raise ValueError("归档中有互相冲突的备份清单")
        manifest = value
    scope = (manifest or {}).get("scope") or filename_scope or "full"
    if scope not in SCOPES:
        raise ValueError("备份范围无法识别，现有数据未覆盖")
    if filename_scope != "full" and scope != filename_scope:
        raise ValueError("备份文件名与清单范围不符，请核对文件")
    if manifest and manifest.get("formatVersion", 1) > 4:
        raise ValueError("备份格式较新，请更新 DSHA 后恢复")
    if manifest and manifest.get("formatVersion") in (3, 4):
        if links or manifest.get("inventory") != inventory(stage):
            raise ValueError("备份内容与 SHA-256 清单不符，现有数据未覆盖")
    if manifest and manifest.get('formatVersion') == 4:
        plugin_support('backup-plugin-graph.py').validate_graph(manifest.get('pluginDependencyGraph', {}), stage, package_name)
    candidates = sorted((p for p in stage.rglob(".dsh") if p.is_dir()), key=lambda p: len(p.parts))
    dsh = candidates[0] if candidates else stage
    if not candidates and not any((stage / n).exists() for n in ("sessions", "profiles", "settings.yaml", "storages")):
        raise ValueError("备份里未找到可恢复的 .dsh 数据")
    # 旧备份的公开链接只接受包内实际快照，绝不读取另一台设备的绝对目标。
    for name, target in links:
        path = stage / name
        if "node_modules" in Path(name).parts:
            continue
        if path.parent == dsh and path.name in HOT:
            continue
        if target.startswith("/"):
            raise ValueError("旧备份缺少可移植的链接内容：" + name)
        resolved = (path.parent / target).resolve()
        if not resolved.is_relative_to(stage.resolve()) or not resolved.exists():
            raise ValueError("归档含越界或悬空链接：" + name)
        copy_data(resolved, path)
    snapshots = sorted((p for p in stage.rglob(".dsha-pub") if p.is_dir()), key=lambda p: len(p.parts))
    if snapshots:
        for name in HOT:
            if (snapshots[0] / name).exists():
                remove(dsh / name)
                copy_data(snapshots[0] / name, dsh / name)
    for name, _ in links:
        path = stage / name
        if path.parent == dsh and path.name in HOT and not path.exists():
            raise ValueError("旧备份只有链接、缺少实际数据：" + path.name)
    allowed = SCOPES[scope]
    payload = [p for p in dsh.iterdir() if p.name not in (MANIFEST, "DSHA-README.txt", ".dsha-plugin-src")]
    if allowed and any(p.name not in allowed for p in payload):
        raise ValueError("部分备份含超出声明范围的数据")
    if not payload and not (scope == "settings" and (stage / ".dsha-native-config.json").is_file()):
        raise ValueError("备份没有可恢复的数据")
    for pkg in (dsh / "profiles").glob("*/package.json"):
        if not isinstance(json.loads(pkg.read_text(encoding="utf-8")), dict):
            raise ValueError("插件 profile JSON 无效")
    return {"scope": scope, "files": files, "bytes": total, "warnings": plugin_warnings(manifest or {}),
            "manifest": manifest or {}, "dsh": str(dsh), "legacy": not manifest or manifest.get("formatVersion") not in (3, 4)}


def recover(root):
    """重启后的日志回滚；备份文件只有本机生成的随机事务路径。"""
    root = Path(root)
    journal = root / ".dsha-restore-journal.json"
    if not journal.exists():
        return
    state = json.loads(journal.read_text(encoding="utf-8"))
    if not state.get("complete"):
        for row in reversed(state["steps"]):
            dst, old, new = map(Path, (row["dst"], row["old"], row["new"]))
            if os.path.lexists(old):
                remove(dst)
                os.replace(old, dst)
            elif not row["existed"] and not os.path.lexists(new):
                remove(dst)
    for row in state["steps"]:
        remove(row["new"])
    journal.unlink()


def repair_profile_links(candidate, stage, root, generation, plugin_stage, records):
    """只修复候选树的模块链接；所需插件缺失时终止，不能伪报完整恢复。"""
    indexed = {(r["profile"], r["name"]) for r in records}
    legacy_inline = sorted((p for p in stage.rglob(".dsha-plugin-src") if p.is_dir()), key=lambda p: len(p.parts))
    if (candidate / "plugin-src").is_dir():
        legacy_inline.append(candidate / "plugin-src")
    for pkg_path in sorted((candidate / "profiles").glob("*/package.json")):
        pkg = json.loads(pkg_path.read_text(encoding="utf-8"))
        original = json.dumps(pkg, sort_keys=True)
        deps = pkg.get("dependencies") or {}
        bundles = ((pkg.get("dsh") or {}).get("profile") or {}).get("bundles") or []
        for name in set(deps) | set(bundles):
            if not package_name(name):
                raise ValueError("插件包名无效")
            if (pkg_path.parent.name, name) in indexed:
                continue
            nm = pkg_path.parent / "node_modules" / name
            if (nm / "package.json").is_file():
                continue
            target = next((base / name for base in GLOBAL_NM if (base / name / "package.json").is_file()), None)
            source = next((base / name for base in legacy_inline if (base / name / "package.json").is_file()), None)
            if source is not None:
                relative = name if pkg_path.parent.name == "web" else ".profiles/" + pkg_path.parent.name + "/" + name
                destination = plugin_stage / relative
                if source.resolve() != destination.resolve():
                    copy_data(source, destination)
                target = generation / relative
                deps[name] = "link:" + str(target)
            if target is None:
                spec = deps.get(name, "")
                if isinstance(spec, str) and spec.startswith(("link:", "file:")):
                    original = Path(spec.split(":", 1)[1])
                    if not original.is_absolute():
                        original = root / ".dsh" / "profiles" / pkg_path.parent.name / original
                    if (original / "package.json").is_file():
                        target = original.resolve()
            if target is None:
                # 已禁用的插件声明可以保留；启用项必须可解析。
                if name in bundles:
                    raise ValueError("备份缺少已启用插件的源码或依赖，请先补装：" + name)
                continue
            nm.parent.mkdir(parents=True, exist_ok=True)
            remove(nm)
            final_nm = root / '.dsh' / nm.relative_to(candidate)
            nm.symlink_to(os.path.relpath(target, final_nm.parent), target_is_directory=True)
        if deps:
            pkg["dependencies"] = deps
        if original != json.dumps(pkg, sort_keys=True):
            dump(pkg_path, pkg)


def workspace_replacements(source, root, stage):
    registry = source / "storages" / "workspace.json"
    if not registry.is_file():
        return []
    data = json.loads(registry.read_text(encoding="utf-8"))
    replacements = []
    allowed = (root.resolve(), Path("/sdcard"), Path("/storage/emulated/0")) if str(root) == "/root" else (root.resolve(),)
    for record in ((data.get("tables") or {}).get("workspaces") or {}).values():
        value = (record or {}).get("path")
        if not isinstance(value, str) or not value.startswith("/"):
            continue
        path = Path(value)
        if path.is_dir():
            continue
        resolved = path.resolve()
        if not any(resolved.is_relative_to(base) for base in allowed):
            raise ValueError("会话工作目录在当前设备不可用，请先创建或挂载：" + value)
        empty = stage / ("workspace-" + uuid.uuid4().hex[:12])
        empty.mkdir()
        replacements.append((empty, resolved))
    # 嵌套工作目录由最上层目录一次创建，避免先创建子目录导致父目标发生冲突。
    selected = [(src, dst) for src, dst in replacements if not any(dst != other and dst.is_relative_to(other) for _, other in replacements)]
    for src, dst in selected:
        for _, child in replacements:
            if child != dst and child.is_relative_to(dst):
                (src / child.relative_to(dst)).mkdir(parents=True, exist_ok=True)
    return selected


def commit(root, replacements, fail_after=None, defer=False):
    """所有副本先落到目标所在文件系统，再记录日志并切换；旧数据保留。"""
    root = Path(root)
    recover(root)
    token = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8]
    steps = []
    journal = root / ".dsha-restore-journal.json"
    try:
        for src, dst in replacements:
            dst = Path(dst)
            dst.parent.mkdir(parents=True, exist_ok=True)
            new = dst.with_name(dst.name + ".restore-stage-" + token)
            old = dst.with_name(dst.name + ".pre-restore-" + token)
            steps.append({"dst": str(dst), "old": str(old), "new": str(new), "existed": os.path.lexists(dst)})
            if Path(src).is_dir():
                shutil.copytree(src, new, symlinks=True)
            else:
                shutil.copy2(src, new, follow_symlinks=False)
        state = {"steps": steps, "complete": False}
        dump(journal, state)
        for index, row in enumerate(steps):
            if row["existed"]:
                os.replace(row["dst"], row["old"])
            if fail_after == index:
                raise OSError("测试注入：提交中断")
            os.replace(row["new"], row["dst"])
        if not defer:
            finalize(root)
        return [r["old"] for r in steps if r["existed"]]
    except BaseException:
        if journal.exists():
            recover(root)
        else:
            for row in steps:
                remove(row["new"])
        raise


def finalize(root):
    root = Path(root)
    journal = root / ".dsha-restore-journal.json"
    state = json.loads(journal.read_text(encoding="utf-8"))
    state["complete"] = True
    dump(journal, state)
    recover(root)


def preserve_plugin_source(alias, preserved):
    """同名未登记源码保留一次；内容不同则另存版本，重复恢复不得冲突或无限复制。"""
    def signature(path):
        result = hashlib.sha256()
        def visit(current, relative):
            if current.is_symlink():
                value = {'link': os.readlink(current)}
            elif current.is_file():
                value = {'sha256': digest(current), 'mode': stat.S_IMODE(current.stat().st_mode)}
            elif current.is_dir():
                value = {'directory': True}
            else:
                raise ValueError('待保留的插件源码包含特殊文件')
            result.update(json.dumps([relative, value], sort_keys=True).encode('utf-8'))
            if value.get('directory'):
                for child in sorted(current.iterdir()):
                    visit(child, relative + '/' + child.name)
        visit(path, '')
        return result.hexdigest()
    if os.path.lexists(preserved):
        value = signature(alias)
        if signature(preserved) == value:
            remove(alias)
            return
        preserved = preserved.parent / '.versions' / value / preserved.name
        if os.path.lexists(preserved):
            if signature(preserved) != value:
                raise ValueError('插件源码版本摘要冲突，所有文件已保留')
            remove(alias)
            return
    preserved.parent.mkdir(parents=True, exist_ok=True)
    os.replace(alias, preserved)


def restore_archive(root, archive, filename_scope="full", workdir="deepseek-harness", fail_after=None, defer=False):
    root = Path(root)
    recover(root)
    with tempfile.TemporaryDirectory(prefix=".dsha-restore-", dir=root) as temp:
        stage = Path(temp)
        info = inspect_archive(archive, stage, filename_scope)
        scope, source = info["scope"], Path(info["dsh"])
        current = root / ".dsh"
        candidate = stage / ".candidate"
        # 部分恢复只复制现有树的链接本体，未选中的公开数据不会被读取或改写。
        if scope != "full" and current.is_dir():
            shutil.copytree(current, candidate, symlinks=True)
        else:
            candidate.mkdir()
        selected = SCOPES[scope] or [p.name for p in source.iterdir() if p.name not in SKIP]
        replacements = []
        for name in selected:
            src = source / name
            if not src.exists():
                continue
            dst = candidate / name
            remove(dst)
            if name in HOT and (current / name).is_symlink():
                public = (current / name).resolve(strict=False)
                # 仅沿用本机现有公开链接；从归档带来的链接不会成为写入目标。
                replacements.append((src, public))
                dst.symlink_to(public, target_is_directory=src.is_dir())
            else:
                copy_data(src, dst)
        manifest = info["manifest"]
        if scope in ("full", "plugins"):
            generation = root / ".dsh" / "plugin-src"
            plugin_stage = candidate / "plugin-src"
            if manifest.get("formatVersion") == 3:
                remove(plugin_stage)
            graph = manifest.get('pluginDependencyGraph', {}) if manifest.get('formatVersion') == 4 else {}
            if graph:
                plugin_support('backup-plugin-graph.py').restore_graph(graph, stage, plugin_stage, generation, copy_data, package_name)
            for record in manifest.get("plugins", []):
                slot, name, profile = record["slot"], record["name"], record["profile"]
                if (not re.fullmatch(r"[a-f0-9]{20}", slot)
                        or not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9._-]*", profile)
                        or not package_name(name)):
                    raise ValueError("插件清单路径无效")
                relative = name if profile == "web" else ".profiles/" + profile + "/" + name
                if manifest.get('formatVersion') == 4:
                    key = record.get('node')
                    if not isinstance(key, str) or key not in graph or graph[key]['name'] != name:
                        raise ValueError('插件记录与依赖图不符')
                    target = generation / '.deps' / key
                    alias = plugin_stage / relative
                    if os.path.lexists(alias):
                        preserved = plugin_stage / '.preserved' / slot / relative
                        preserve_plugin_source(alias, preserved)
                    alias.parent.mkdir(parents=True, exist_ok=True)
                    alias.symlink_to(os.path.relpath(plugin_stage / '.deps' / key, alias.parent), target_is_directory=True)
                    if record.get('unregistered'):
                        continue
                    pkg_path = candidate / 'profiles' / profile / 'package.json'
                    pkg = json.loads(pkg_path.read_text(encoding='utf-8'))
                    bundles = ((pkg.get('dsh') or {}).get('profile') or {}).get('bundles') or []
                    if name not in pkg.get('dependencies', {}) and not (record.get('bundledOnly') and name in bundles):
                        raise ValueError('插件记录与配置不符')
                    pkg.setdefault('dependencies', {})[name] = 'link:' + str(target)
                    dump(pkg_path, pkg)
                    nm = pkg_path.parent / 'node_modules' / name
                    nm.parent.mkdir(parents=True, exist_ok=True)
                    remove(nm); nm.symlink_to(os.path.relpath(plugin_stage / '.deps' / key, nm.parent), target_is_directory=True)
                    continue
                src = stage / ".dsha-plugin-src" / slot
                copy_data(src, plugin_stage / relative)
                pkg_path = candidate / "profiles" / profile / "package.json"
                pkg = json.loads(pkg_path.read_text(encoding="utf-8"))
                if name not in pkg.get("dependencies", {}):
                    raise ValueError("插件清单与 profile 依赖不一致")
                pkg.setdefault("dependencies", {})[name] = "link:" + str(generation / relative)
                dump(pkg_path, pkg)
                nm = pkg_path.parent / "node_modules" / name
                nm.parent.mkdir(parents=True, exist_ok=True)
                remove(nm)
                nm.symlink_to(os.path.relpath(plugin_stage / relative, nm.parent), target_is_directory=True)
            repair_profile_links(candidate, stage, root, generation, plugin_stage, manifest.get("plugins", []))
        if scope == "full":
            wd = Path(workdir)
            if not wd.is_absolute():
                wd = root / wd
            for name in (".env", "dsh-web.log"):
                src = stage / ".dsha-workdir" / name
                if src.exists():
                    replacements.append((src, wd / name))
        # 原生设置仍由 ConfigStore 导入，不让 Python 接触 Android 私有偏好文件。
        native = stage / ".dsha-native-config.json"
        native_data = json.loads(native.read_text(encoding="utf-8")) if native.exists() and scope in ("full", "settings") else None
        if native_data is not None and not isinstance(native_data, dict):
            raise ValueError("原生设置格式无效")
        if scope in ("full", "sessions"):
            replacements.extend(workspace_replacements(source, root, stage))
        replacements.append((candidate, current))
        backups = commit(root, replacements, fail_after, defer)
        return {"scope": scope, "files": info["files"], "legacy": info["legacy"],
                "previous": backups, "nativeConfig": native_data, "committed": True}


@contextmanager
def data_lock(root):
    with open(Path(root) / ".dsha-data.lock", "a") as lock:
        if os.name != "nt":
            import fcntl
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise ValueError("有插件或备份任务正在修改数据，请完成后再试")
        try:
            yield
        finally:
            if os.name != "nt":
                fcntl.flock(lock, fcntl.LOCK_UN)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("backup", "inspect", "restore", "recover", "finalize"))
    parser.add_argument("--root", default="/root")
    parser.add_argument("--archive")
    parser.add_argument("--scope", choices=SCOPES, default="full")
    parser.add_argument("--app-version", default="unknown")
    parser.add_argument("--app-code", type=int, default=0)
    parser.add_argument("--workdir", default="deepseek-harness")
    parser.add_argument("--native-config")
    parser.add_argument("--defer-commit", action="store_true")
    args = parser.parse_args()
    with data_lock(args.root):
        execute(args)


def execute(args):
    if args.command == "backup":
        result = make_backup(args.root, args.archive, args.scope, args.app_version,
                             args.app_code, args.workdir, args.native_config)
    elif args.command == "inspect":
        with tempfile.TemporaryDirectory(prefix=".dsha-inspect-", dir=args.root) as temp:
            result = inspect_archive(args.archive, Path(temp), args.scope)
            result.pop("dsh")
            result["manifest"].pop("inventory", None)
    elif args.command == "restore":
        result = restore_archive(args.root, args.archive, args.scope, args.workdir, defer=args.defer_commit)
    elif args.command == "finalize":
        finalize(args.root)
        result = {"committed": True}
    else:
        recover(args.root)
        result = {"recovered": True}
    print("DSHA_BACKUP_RESULT=" + json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("备份/恢复失败：" + str(error), file=sys.stderr)
        sys.exit(1)
