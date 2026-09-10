#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSHA 插件注册 / 启停：rootfs 烘焙的内置插件（+ 官方核心）登记进 dsh web profile。

背景：内置插件（dsh-device-shell-guide / dsh-task-notifier / dsh-status-overlay /
dsh-web-mobile）的实体随离线 rootfs 烘焙在 /root/dsha-*，但 dsh 只有在该插件的
名字出现在 web profile（$DSH_HOME/profiles/web/package.json）的 dsh.profile.bundles
里、且 profile 的 node_modules 下能解析到实体（链接到 /root/dsha-*）时才会加载。
官方核心（@deepseek-ai/dsh-base / @deepseek-ai/dsh-web-app）从 dsh 安装树解析，
无需 node_modules 链接，但同样以 bundles 里的名字决定是否加载。

本脚本负责「注册 / 启用 / 禁用」三类动作，幂等 —— 覆盖安装（rootfs 保留、旧 profile
已存在）与全新安装（rootfs 重新解压、profile 尚未生成）两条路径都适用。

用法：
  python3 register-builtin-plugins.py                # 注册：把未禁用的内置插件并进 profile
  python3 register-builtin-plugins.py --enable 名字   # 启用：加回 bundles + 建链
  python3 register-builtin-plugins.py --disable 名字  # 禁用：移出 bundles + 摘链 + 写禁用标记

注册契约（与 selftest.py / fix-stale-bundles.sh 保持一致）：
  1. profiles/web/package.json 的 dsh.profile.bundles 含插件名；
  2. dependencies 有 link: 声明（pnpm 重装/加插件时不会把内置链接摘掉）；
  3. profiles/web/node_modules/<name> 是指向 /root/dsha-* 的符号链接。
禁用标记：profiles/web/node_modules/<name>.disabled（空文件）—— 存在即表示用户主动
禁用，注册流程会尊重它而永远跳过（与 selftest.py 的判定一致），只有 --enable 会清掉。

清单通过临时文件原子替换，不为每次开关累积备份；每次运行写 repair-builtin.log。

只读 / 保守原则：只对「名字已知且实体存在」的插件动手；不删除已有 bundle、
不改写用户第三方插件、不覆盖已存在的 node_modules 实体（那可能是用户 pnpm 装的）。

本地测试（不走 proot，用假 root 目录模拟）：
  DSHA_TEST_ROOT=/tmp/fakeroot DSH_HOME=/tmp/fakeroot/root/.dsh python3 register-builtin-plugins.py
"""
import json
import os
import shutil
import sys
import time
import re
import uuid
from contextlib import contextmanager

# ================= 位置与清单 =================

ROOT = os.environ.get("DSHA_TEST_ROOT", "")       # 本地测试时把 "/" 换成假 root
DSH_HOME = os.environ.get("DSH_HOME", "/root/.dsh")
PROFILE = os.path.join(DSH_HOME, "profiles", "web")
MANIFEST = os.path.join(PROFILE, "package.json")
PATCH_FILE = os.path.join(PROFILE, "cordis.patch.yml")
WORKSPACE = os.path.join(PROFILE, "pnpm-workspace.yaml")
NODE_MODULES = os.path.join(PROFILE, "node_modules")
REPAIR_LOG = os.path.join(DSH_HOME, "repair-builtin.log")
BUILTIN_LIST = os.path.join(ROOT, "root", "dsha-builtin.txt")

# 兜底清单：dsha-builtin.txt 缺失（精简包/手改）时仍能认出这四个内置插件
DEFAULT_BUILTINS = (
    "dsh-device-shell-guide",
    "dsh-task-notifier",
    "dsh-status-overlay",
    "dsh-web-mobile",
    "dsh-app-integration",
)

# web profile 的官方核心（dsh 的 PROFILE_TEMPLATES.web），新建 profile 时打底
OFFICIAL_BUNDLES = ("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app")

PROFILE_PATCH_TEMPLATE = (
    "# Your patch layer for this dsh profile, applied after every bundle layer:\n"
    "# a top-level YAML array of loader patch entries (id-targeted config\n"
    "# overrides, disables, and insert lists; `!!js` expressions allowed).\n"
    "[]\n"
)

PROFILE_WORKSPACE_TEMPLATE = (
    "packages:\n"
    "  - .\n"
    "\n"
    "nodeLinker: hoisted\n"
    "autoInstallPeers: false\n"
)


def local(path):
    """容器内绝对路径 → 本地文件系统路径（测试用 DSHA_TEST_ROOT 前缀）。"""
    if not ROOT:
        return path
    root = os.path.normcase(os.path.abspath(ROOT))
    candidate = os.path.normcase(os.path.abspath(path))
    if candidate == root or candidate.startswith(root + os.sep):
        return path
    return os.path.join(ROOT, path.lstrip("/\\"))


def valid_name(name):
    return isinstance(name, str) and len(name) <= 214 and re.fullmatch(
        r"(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*", name) is not None


def ensure_relative_link(link, target):
    """受管链接用相对路径；仅迁移指向同一目标的旧链接，保留用户实体与其他链接。"""
    if os.path.lexists(link):
        if not os.path.islink(link):
            return False
        current = os.readlink(link)
        same = os.path.realpath(link) == os.path.realpath(target)
        if not same and os.path.normpath(current) != os.path.normpath(target):
            return False
        if not os.path.isabs(current) and same:
            return False
    os.makedirs(os.path.dirname(link), exist_ok=True)
    relative = os.path.relpath(target, os.path.dirname(link))
    # 原子替换，避免中断恰好发生在删旧链接与建新链接之间。
    temporary = link + '.dsha-link-' + uuid.uuid4().hex
    created = False
    try:
        os.symlink(relative, temporary, target_is_directory=True)
        created = True
        os.replace(temporary, link)
        return True
    finally:
        if created and os.path.islink(temporary):
            os.unlink(temporary)


def runtime_links_stamp():
    """仅检查链接所在目录和受管入口；增删/替换链接会改变父目录，无需逐个 realpath。"""
    package = local('/usr/local/lib/node_modules/@deepseek-ai/dsh')
    bundled = os.path.join(package, 'node_modules')
    shared = os.path.join(local(DSH_HOME), 'node_modules')
    paths = [package, os.path.join(package, 'package.json'), bundled, shared]
    for base in (bundled, shared):
        if os.path.isdir(base):
            paths.extend(os.path.join(base, name) for name in os.listdir(base) if name.startswith('@'))
    for name in DEFAULT_BUILTINS:
        managed = local('/root/dsha-' + name.removeprefix('dsh-'))
        paths.extend([managed, os.path.join(managed, 'package.json'), os.path.join(managed, 'node_modules')])
    stamp = []
    for path in sorted(paths):
        try:
            stat = os.lstat(path)
            stamp.append([path, stat.st_dev, stat.st_ino, stat.st_mtime_ns, stat.st_ctime_ns,
                          stat.st_size, os.path.realpath(path)])
        except FileNotFoundError:
            stamp.append([path, None])
    return stamp


def ensure_runtime_modules(force=False):
    cache = local(os.path.join(DSH_HOME, '.runtime-links-cache.json'))
    stamp = runtime_links_stamp()
    if not force:
        try:
            with open(cache, encoding='utf-8') as stream:
                previous = json.load(stream)
            if previous == {'version': 1, 'stamp': stamp}:
                print('RUNTIME_LINKS_CACHED: 共享依赖未变化，复用已检查链接', flush=True)
                return 0
        except (OSError, ValueError):
            pass
    print('RUNTIME_LINKS_CHECK: 检查共享依赖链接', flush=True)
    count = repair_runtime_modules()
    try:
        os.makedirs(os.path.dirname(cache), exist_ok=True)
        temporary = cache + '.tmp'
        with open(temporary, 'w', encoding='utf-8') as stream:
            json.dump({'version': 1, 'stamp': runtime_links_stamp()}, stream)
        os.replace(temporary, cache)
    except OSError:
        pass
    return count


def repair_runtime_modules():
    """导入插件与 /root 下受管内置插件均解析同一份运行时，供 ESM 查找 peer 模块。"""
    package = local('/usr/local/lib/node_modules/@deepseek-ai/dsh')
    bundled = os.path.join(package, 'node_modules')
    if not os.path.isdir(bundled):
        return 0
    home = os.path.realpath(local(DSH_HOME))
    shared = os.path.join(local(DSH_HOME), 'node_modules')
    candidates = {}
    for name in os.listdir(bundled):
        source = os.path.join(bundled, name)
        if name.startswith('@') and os.path.isdir(source):
            for child in os.listdir(source):
                candidates[name + '/' + child] = os.path.join(source, child)
        else:
            candidates[name] = source
    candidates['@deepseek-ai/dsh'] = package
    count = 0
    for name in DEFAULT_BUILTINS:
        managed = local('/root/dsha-' + name.removeprefix('dsh-'))
        modules = os.path.join(managed, 'node_modules')
        if os.path.isfile(os.path.join(managed, 'package.json')):
            count += int(ensure_relative_link(modules, bundled))
    for name, source in candidates.items():
        if not valid_name(name) or not os.path.isfile(os.path.join(source, 'package.json')):
            continue
        target = os.path.join(shared, name)
        parent = os.path.dirname(target)
        if os.path.commonpath([home, os.path.realpath(parent)]) != home:
            raise RuntimeError('运行时模块目录指向用户环境之外，已停止修复')
        # 用户已有实体/依赖保持原样，同时迁移旧版由本工具创建的绝对链接。
        count += int(ensure_relative_link(target, source))
    return count


@contextmanager
def operation_lock(check_cancel=None):
    """所有 DSHA 插件清单写入共用锁；进程退出由系统释放。"""
    os.makedirs(local(DSH_HOME), exist_ok=True)
    # 锁放在 .dsh 外，恢复整个 .dsh 时不会换掉正在使用的锁 inode。
    data_root = os.path.dirname(local(DSH_HOME))
    with open(os.path.join(data_root, ".dsha-data.lock"), "a") as lock:
        if os.name != "nt":
            import fcntl
            while True:
                if check_cancel:
                    check_cancel()
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    time.sleep(0.1)
        try:
            if os.path.isfile(os.path.join(data_root, ".dsha-restore-journal.json")):
                raise RuntimeError("恢复事务尚未完成，请先启动 DSHA 完成恢复后再操作插件")
            yield
        finally:
            if os.name != "nt":
                fcntl.flock(lock, fcntl.LOCK_UN)


def entity_dir(name):
    """内置插件名 → 其实体目录（/root/dsha-*），找不到（官方核心/第三方）返回 None。"""
    if not valid_name(name):
        return None
    if name in DEFAULT_BUILTINS:
        managed = '/root/dsha-' + name.removeprefix('dsh-')
        if os.path.isfile(local(os.path.join(managed, 'package.json'))):
            return managed
    if name not in DEFAULT_BUILTINS:
        active = os.path.join(NODE_MODULES, name)
        if os.path.isfile(local(os.path.join(active, "package.json"))):
            return active
    imported = os.path.join(DSH_HOME, "plugin-src", name)
    if os.path.isfile(local(os.path.join(imported, "package.json"))):
        return imported
    if name.startswith("@"):
        found = discover_plugins().get(name)
        return found["directory"] if found else None
    cands = ["/root/" + name, "/root/dsha-" + name]
    if name.startswith("dsh-"):
        cands.insert(0, "/root/dsha-" + name[4:])
    for c in cands:
        if os.path.isfile(local(os.path.join(c, "package.json"))):
            return c
    found = discover_plugins().get(name)
    return found["directory"] if found else None


def discover_plugins(home_directory=None, include_global=True):
    """只读枚举常用安装位置；仅识别带可读取 bundle patch 的插件，不把运行时依赖当插件。"""
    roots = [(NODE_MODULES, "Web 配置"), (os.path.join(DSH_HOME, "plugin-src"), "本地导入"),
             (os.path.join(DSH_HOME, "node_modules"), "共享插件"),
             (os.path.join(DSH_HOME, "profiles", "node_modules"), "共享配置"),
             (os.path.join(home_directory or '/root', 'node_modules'), "终端安装")]
    if include_global:
        roots.append(("/usr/local/lib/node_modules", "全局安装"))
    profiles = local(os.path.join(DSH_HOME, "profiles"))
    if os.path.isdir(profiles):
        for profile in sorted(os.listdir(profiles)):
            if profile in ("web", "node_modules") or not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", profile):
                continue
            roots.append((os.path.join(DSH_HOME, "profiles", profile, "node_modules"), "配置 " + profile))
    runtime = os.path.realpath(local('/usr/local/lib/node_modules/@deepseek-ai/dsh'))
    found = {}
    for root, location in roots:
        path = local(root)
        if not os.path.isdir(path):
            continue
        candidates = []
        for entry in sorted(os.listdir(path)):
            if entry.startswith('@') and os.path.isdir(os.path.join(path, entry)):
                candidates.extend(entry + '/' + child for child in sorted(os.listdir(os.path.join(path, entry))))
            else:
                candidates.append(entry)
        for name in candidates:
            if not valid_name(name) or name in OFFICIAL_BUNDLES or name in DEFAULT_BUILTINS:
                continue
            directory = os.path.realpath(os.path.join(path, name))
            # 由 ensure_runtime_modules 自动生成的共享依赖链接不属于用户安装。
            try:
                shared_runtime = os.path.commonpath([runtime, directory]) == runtime
            except ValueError:
                shared_runtime = False
            if shared_runtime:
                continue
            manifest = os.path.join(directory, 'package.json')
            try:
                if os.path.getsize(manifest) > 1024 * 1024:
                    continue
                with open(manifest, encoding='utf-8') as stream:
                    package = json.load(stream)
                patch = package.get('dsh', {}).get('bundle', {}).get('patch')
                patches = [patch] if isinstance(patch, str) else patch
                if package.get('name') != name or not isinstance(patches, list) or not patches:
                    continue
                if any(not isinstance(p, str) or not p or os.path.isabs(p)
                       or os.path.commonpath([directory, os.path.realpath(os.path.join(directory, p))]) != directory
                       or not os.path.isfile(os.path.join(directory, p)) for p in patches):
                    continue
            except (OSError, ValueError, TypeError, AttributeError):
                continue
            if name not in found:
                found[name] = dict(directory=directory, location=location, locations=[location])
            elif location not in found[name]['locations']:
                found[name]['locations'].append(location)
    return found
    return None


def marker_path(name):
    """禁用标记：node_modules/<name>.disabled（空文件即禁用，与 selftest.py 一致）。"""
    return os.path.join(local(NODE_MODULES), name + ".disabled")


def is_disabled(name):
    return os.path.isfile(marker_path(name))


def builtin_names():
    """内置插件名清单：优先读 dsha-builtin.txt，缺失时用兜底清单。"""
    try:
        with open(BUILTIN_LIST, encoding="utf-8") as f:
            names = [ln.strip() for ln in f if ln.strip() and not ln.startswith("#")]
        if names:
            return list(dict.fromkeys(names + ["dsh-app-integration"]))
    except OSError:
        pass
    return list(DEFAULT_BUILTINS)


# ================= profile 读写 =================

def read_manifest():
    if not os.path.isfile(local(MANIFEST)):
        return None
    try:
        with open(local(MANIFEST), encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        raise RuntimeError("profile package.json 无法解析：%s" % e)


def write_manifest(doc):
    os.makedirs(local(PROFILE), exist_ok=True)
    text = json.dumps(doc, indent=2) + "\n"
    tmp = local(MANIFEST + ".dsha-tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, local(MANIFEST))


def ensure_profile_files():
    """新建 profile 时补齐 dsh initProfile 会写的三个文件（已是 dsh 模板就跳过）。"""
    os.makedirs(local(PROFILE), exist_ok=True)
    if not os.path.isfile(local(PATCH_FILE)):
        with open(local(PATCH_FILE), "w", encoding="utf-8") as f:
            f.write(PROFILE_PATCH_TEMPLATE)
    if not os.path.isfile(local(WORKSPACE)):
        with open(local(WORKSPACE), "w", encoding="utf-8") as f:
            f.write(PROFILE_WORKSPACE_TEMPLATE)


def new_manifest(registered):
    """profile 尚不存在：按 dsh web 模板 + 内置插件新建。

    registered 形如 { "dsh-device-shell-guide": "/root/dsha-device-shell-guide" }，
    dependencies 形如 { "dsh-device-shell-guide": "link:/root/dsha-device-shell-guide" }。

    patchReload 刻意用 "startup" 而非 dsh web 模板默认的 "live"：live 会要求 dsh 进程
    加载 cordis-plugin-hmr（热重载），而它强制需要 node --expose-internals —— 本应用
    的启动命令不带该标志，live 会让 dsh 直接崩（"failed to apply loader entry ...
    --expose-internals is required"），Web 起不来。startup 只在重启时应用补丁，移动端
    本来就走「重启 Web」流程，功能不受影响。
    """
    deps = {name: "link:" + d for name, d in registered.items()}
    return {
        "name": "dsh-profile-web",
        "private": True,
        "dependencies": deps,
        "dsh": {
            "profile": {
                "bundles": list(OFFICIAL_BUNDLES) + list(registered.keys()),
                "patchReload": "startup",
            }
        },
    }


def merge_manifest(doc, registered):
    """profile 已存在：把内置插件并进 bundles 与 dependencies，不动其它。"""
    doc = dict(doc)
    doc["dsh"] = dict(doc.get("dsh") or {})
    doc["dsh"]["profile"] = dict(doc["dsh"].get("profile") or {})
    bundles = list(doc["dsh"]["profile"].get("bundles") or [])
    deps = dict(doc.get("dependencies") or {})
    added = []
    for name, d in registered.items():
        if name not in bundles:
            bundles.append(name)
            added.append(name)
        deps[name] = "link:" + d
    # 同 new_manifest：Android 上 live（HMR）会让 dsh 因缺 --expose-internals 崩溃，
    # 覆盖安装老 profile 也可能是 live，必须拉回 startup 才能起得来
    doc["dsh"]["profile"]["patchReload"] = "startup"
    doc["dsh"]["profile"]["bundles"] = bundles
    doc["dependencies"] = deps
    return doc, added


# ================= node_modules 链接 =================

def ensure_symlink(name, d):
    """保证 profiles/web/node_modules/<name> 是指向实体目录的链接。返回 True=改动了。"""
    link = os.path.join(local(NODE_MODULES), name)
    target = local(d)
    os.makedirs(os.path.dirname(link), exist_ok=True)
    if os.path.lexists(link):
        try:
            if os.path.islink(link) and os.path.realpath(link) == os.path.realpath(target):
                return ensure_relative_link(link, target)
        except OSError:
            pass
        # 已存在的非正确链接/实体：proot 下 islink 不可信，用 realpath 对比判断；
        # 指向正确就当作好，指向别处才替换（绝不覆盖用户 pnpm 装的第三方实体）
        if os.path.isdir(link) and os.path.realpath(link) == os.path.realpath(target):
            return False
        if os.path.islink(link) or not os.path.isdir(link):
            try:
                os.remove(link)
            except OSError:
                return False
        else:
            # 是实体目录但指向不对（几乎不可能是内置场景，保守起见不动）
            return False
    try:
        return ensure_relative_link(link, target)
    except OSError:
        return False


def remove_link(name):
    """禁用时摘掉 node_modules 链接（只摘符号链接/文件，绝不碰 pnpm 实体副本目录）。"""
    link = os.path.join(local(NODE_MODULES), name)
    try:
        if os.path.islink(link) or (os.path.lexists(link) and not os.path.isdir(link)):
            os.remove(link)
            return True
    except OSError:
        pass
    return False


# ================= 启用 / 禁用 =================

def enable_plugin(name):
    """--enable：清禁用标记、加回 bundles、重建链接（官方核心无标记/链接，只改 bundles）。"""
    lines = ["== " + time.strftime("%Y-%m-%d %H:%M:%S") + " 启用 " + name]
    try:
        existing_web = os.path.isfile(os.path.join(local(NODE_MODULES), name, "package.json"))
        d = entity_dir(name)
        if d is None and name not in OFFICIAL_BUNDLES:
            link = os.path.join(local(NODE_MODULES), name, "package.json")
            if not os.path.isfile(link):
                raise RuntimeError("找不到插件实体，请重新导入：" + name)
        doc = read_manifest()
        if doc is None:
            lines.append("profile 尚不存在，先注册再启用")
            _write_log(lines, ok=False)
            print("BUILTIN_REGISTER_FAIL: profile 不存在，先运行注册")
            return 1
        changed = False
        bundles = list(doc.get("dsh", {}).get("profile", {}).get("bundles") or [])
        if name not in bundles:
            doc.setdefault("dsh", {}).setdefault("profile", {})["bundles"] = bundles + [name]
            changed = True
        if d is not None and ensure_symlink(name, d):
            changed = True
        if d is not None:
            if not os.path.isfile(os.path.join(local(NODE_MODULES), name, "package.json")):
                raise RuntimeError("无法建立插件链接：" + name)
            if name in DEFAULT_BUILTINS or not existing_web:
                doc.setdefault("dependencies", {})[name] = "link:" + d
                changed = True
        if changed:
            write_manifest(doc)
            lines.append("已加回 bundles：%s" % name)
        else:
            lines.append("本就启用：%s" % name)
        if d is not None and os.path.isfile(marker_path(name)):
            os.remove(marker_path(name))
            lines.append("已清除禁用标记")
    except RuntimeError as e:
        lines.append(str(e))
        _write_log(lines, ok=False)
        print("BUILTIN_REGISTER_FAIL: %s" % e)
        return 1
    lines.append("启用完成，重启 Web 后生效")
    _write_log(lines, ok=True)
    print("BUILTIN_REGISTER_OK: %s 已启用" % name)
    return 0


def disable_plugin(name):
    """--disable：写禁用标记、移出 bundles、摘链接（官方核心只移出 bundles）。"""
    lines = ["== " + time.strftime("%Y-%m-%d %H:%M:%S") + " 禁用 " + name]
    try:
        d = entity_dir(name)
        if d is not None:
            os.makedirs(os.path.dirname(marker_path(name)), exist_ok=True)
            # 显式禁用覆盖安全模式的临时标记，批量恢复时保留用户的新选择。
            with open(marker_path(name), "w", encoding="utf-8") as f:
                f.write("")
            lines.append("已写禁用标记")
        doc = read_manifest()
        changed = False
        if doc is not None:
            bundles = list(doc.get("dsh", {}).get("profile", {}).get("bundles") or [])
            if name in bundles:
                bundles.remove(name)
                doc.setdefault("dsh", {}).setdefault("profile", {})["bundles"] = bundles
                write_manifest(doc)
                changed = True
                lines.append("已移出 bundles：%s" % name)
            else:
                lines.append("本就不在 bundles：%s" % name)
        # 第三方 pnpm 链接是其安装记录的一部分，禁用只移出 bundles，保留实体以便再次启用。
        if name in DEFAULT_BUILTINS and d is not None and remove_link(name):
            changed = True
            lines.append("已摘 node_modules 链接")
        if not changed:
            lines.append("无需改动")
    except RuntimeError as e:
        lines.append(str(e))
        _write_log(lines, ok=False)
        print("BUILTIN_REGISTER_FAIL: %s" % e)
        return 1
    lines.append("禁用完成，重启 Web 后生效")
    _write_log(lines, ok=True)
    print("BUILTIN_REGISTER_OK: %s 已禁用" % name)
    return 0


# ================= 注册主流程 =================

def register():
    os.makedirs(local(DSH_HOME), exist_ok=True)
    lines = ["== " + time.strftime("%Y-%m-%d %H:%M:%S")]
    try:
        linked = ensure_runtime_modules()
        if linked: lines.append('已补充 %d 个共享运行时模块链接' % linked)
    except (OSError, RuntimeError) as error:
        print('BUILTIN_REGISTER_FAIL: ' + str(error))
        return 1

    names = builtin_names()
    present = {}
    skipped = []
    for name in names:
        d = entity_dir(name)
        if d is None:
            continue
        if is_disabled(name):
            skipped.append(name)  # 用户禁用过的：尊重标记，不补回
            continue
        present[name] = d

    if skipped:
        lines.append("尊重禁用标记跳过：%s" % ", ".join(skipped))
    if not present:
        if skipped:
            lines.append("内置插件均已禁用，无需注册")
            _write_log(lines, ok=True)
            print("BUILTIN_REGISTER_OK: 无待注册内置插件（已禁用 %s）" % ", ".join(skipped))
            return 0
        lines.append("内置插件实体缺失：%s（精简包？）" % ", ".join(names))
        _write_log(lines, ok=True)
        print("BUILTIN_REGISTER: 无内置插件实体，跳过")
        return 0

    changed = []
    try:
        doc = read_manifest()
        if doc is None:
            ensure_profile_files()
            doc = new_manifest(present)
            write_manifest(doc)
            lines.append("已新建 web profile 并注册 %d 个内置插件：%s"
                         % (len(present), ", ".join(present)))
            changed = list(present)
        else:
            ensure_profile_files()
            merged, added = merge_manifest(doc, present)
            if added or merged != doc:
                write_manifest(merged)
                if added:
                    lines.append("已注册内置插件：%s" % ", ".join(added))
                else:
                    lines.append("内置插件本就注册在列：%s" % ", ".join(present))
                changed = added or list(present)
    except RuntimeError as e:
        lines.append(str(e))
        _write_log(lines, ok=False)
        print("BUILTIN_REGISTER_FAIL: %s" % e)
        return 1

    link_changed, link_failed = [], []
    for name, d in present.items():
        if ensure_symlink(name, d):
            link_changed.append(name)
        else:
            link = os.path.join(local(NODE_MODULES), name)
            # 已存在「可解析」实体（正确链接，或老版本 pnpm 装的实体副本）
            # 都算就绪 —— dsh 只认 node_modules 下能解析到 package.json；
            # 只有「缺实体 / 悬空链接且补不了」才算失败。
            if os.path.isfile(os.path.join(link, "package.json")):
                continue
            link_failed.append(name)

    if link_failed:
        lines.append("仍未注册（node_modules 链接失败）：%s" % ", ".join(link_failed))
        _write_log(lines, ok=False)
        print("BUILTIN_REGISTER_PARTIAL: %s" % ", ".join(link_failed))
        return 1
    if link_changed:
        lines.append("已补 node_modules 链接：%s" % ", ".join(link_changed))

    if changed or link_changed:
        lines.append("修好 %d 个内置插件注册（bundles+deps+links）" % len(present))
    else:
        lines.append("内置插件注册均已就绪，无需改动")

    _write_log(lines, ok=True)
    print("BUILTIN_REGISTER_OK: %d 个内置插件注册就绪" % len(present))
    return 0


def _write_log(lines, ok):
    try:
        with open(local(REPAIR_LOG), "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    except OSError:
        pass


def main():
    args = sys.argv[1:]
    if len(args) >= 2 and args[0] in ("--enable", "--disable"):
        if not valid_name(args[1]):
            print("BUILTIN_REGISTER_FAIL: 无效插件名")
            return 1
        return enable_plugin(args[1]) if args[0] == "--enable" else disable_plugin(args[1])
    return register()


if __name__ == "__main__":
    with operation_lock():
        sys.exit(main())
