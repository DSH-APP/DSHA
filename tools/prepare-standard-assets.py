#!/usr/bin/env python3
"""生成两版共用资产；用已锁定的新 dsh 替换旧依赖，原始 rootfs 保持不变。"""
import argparse
import collections
import gzip
import hashlib
import json
import posixpath
import re
from pathlib import Path
import shutil
import tarfile
import importlib.util


def excluded_platform(values, current):
    if isinstance(values, str):
        values = [values]
    if not isinstance(values, list) or not values:
        return False
    positives = [v for v in values if isinstance(v, str) and not v.startswith("!")]
    return "!" + current in values or bool(positives and current not in positives and "any" not in positives)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def omit(name, foreign):
    parts = name.split("/")
    if ".npm" in parts:
        return "npm-cache"
    if "prebuilds" in parts:
        index = parts.index("prebuilds") + 1
        if index < len(parts) and parts[index].startswith(("win32-", "darwin-", "linux-x64", "linux-ia32")):
            return "foreign-prebuild"
    if name == "data/data/com.termux" or name.startswith("data/data/com.termux/") \
            or name in ("usr/bin/python3.14", "usr/bin/python3", "bin/python3.14", "bin/python3"):
        return "replaced-termux-python"
    for prefix in foreign:
        if name == prefix or name.startswith(prefix + "/"):
            return "foreign-platform"
    return ""


def replaced_runtime(name):
    """原包曾把 dsh 依赖同时装在全局和嵌套目录；只保留 npm 工具自身。"""
    prefix = "usr/local/lib/node_modules/"
    if name.startswith(prefix):
        relative = name[len(prefix):]
        return relative != "npm" and not relative.startswith("npm/")
    if name in ("usr/local/bin/dsh", "usr/local/bin/tsc", "usr/local/bin/tsserver"):
        return True
    # 四个内置插件改由可审查的 APK 资产安装，不再同时保留旧的压缩树。
    return any(name == prefix or name.startswith(prefix + '/') for prefix in (
        'root/dsha-device-shell-guide', 'root/dsha-task-notifier', 'root/dsha-status-overlay', 'root/dsha-web-mobile'))


def valid_overlay(item, name):
    prefix = 'usr/local/lib/node_modules/@deepseek-ai/dsh/'
    if name != posixpath.normpath(name) or name.startswith('/') or '\\' in name:
        return False
    if not (item.isfile() or item.isdir() or item.issym()):
        return False
    if name.startswith(prefix):
        return not item.issym() or posixpath.normpath(posixpath.join(posixpath.dirname(name), item.linkname)).startswith(prefix)
    if name == 'usr/local/share/dsha/dsh-runtime.version':
        return item.isfile()
    if name in ('usr/local/bin/dsh', 'usr/local/bin/tsc', 'usr/local/bin/tsserver'):
        return item.issym() and posixpath.normpath(posixpath.join(posixpath.dirname(name), item.linkname)).startswith(prefix)
    global_prefix = 'usr/local/lib/node_modules/'
    package = name.removeprefix(global_prefix)
    return item.issym() and name.startswith(global_prefix) and package not in ('npm', '@deepseek-ai/dsh') \
        and re.fullmatch(r'(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*', package) is not None \
        and posixpath.normpath(posixpath.join(posixpath.dirname(name), item.linkname)) == prefix + 'node_modules/' + package


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    source, output = Path(args.source).resolve(), Path(args.output).resolve()
    if source == output or source in output.parents:
        raise ValueError("生成目录不能放在原始资产目录内")
    output.mkdir(parents=True, exist_ok=True)
    rootfs = source / "offline-rootfs.bin"
    runtime = source / "dsh-runtime.bin"
    excluded = {"offline-rootfs.bin", "runtime-python/python-runtime.tgz",
                "glibc-python.tar.gz", "adb-wheels.tar.gz", "dsh-runtime.bin", "dsh-runtime.inputs.json", "ubuntu-tools.inputs.json"}
    for path in source.rglob("*"):
        relative = path.relative_to(source)
        if not path.is_file() or "__pycache__" in relative.parts or path.suffix == ".pyc" \
                or relative.parts[0] == "runtime-python" or relative.as_posix() in excluded:
            continue
        destination = output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
    # Gecko 内容脚本处于隔离世界；把同一份兼容代码注入页面世界，供新版 PDF.js 使用。
    compatibility = (source / 'web-integration/compat.js').read_text(encoding='utf-8')
    compatibility += '\n' + (source / 'web-integration/startup.js').read_text(encoding='utf-8')
    injector = "(function(){function install(){var root=document.head||document.documentElement;if(!root)return;var script=document.createElement('script');script.textContent=" \
        + json.dumps(compatibility, ensure_ascii=True) + ";root.appendChild(script);script.remove();}if(document.documentElement)install();else document.addEventListener('DOMContentLoaded',install,{once:true});})();\n"
    (output / 'web-integration/gecko-compat.js').write_text(injector, encoding='utf-8')
    # 清理旧构建生成的两个兼容版资产，不触碰输入目录。
    for name in ("python3.14", "python-runtime.tgz"):
        (output / "runtime-python" / name).unlink(missing_ok=True)
    # 用 bin 扩展名避免 aapt 展开 gzip；读取端按 magic 判断格式。
    for name in ("glibc-python", "adb-wheels"):
        shutil.copyfile(source / (name + ".tar.gz"), output / (name + ".bin"))
    if not rootfs.is_file():
        (output / "offline-rootfs.bin").unlink(missing_ok=True)
        for name in ('dsh-runtime.bin', 'dsh-runtime.sha256', 'offline-rootfs.layout'):
            (output / name).unlink(missing_ok=True)
        (output.parent / "standard-assets-report.json").unlink(missing_ok=True)
        print("未提供离线 rootfs，生成精简资产")
        return
    if not runtime.is_file():
        raise ValueError("缺少锁定的新版 dsh-runtime.bin；请先运行 tools/build-dsh-runtime.py，不能发布旧版 dsh")
    specification = importlib.util.spec_from_file_location('dsh_runtime_builder', Path(__file__).with_name('build-dsh-runtime.py'))
    builder = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(builder)
    metadata = json.loads(runtime.with_suffix('.inputs.json').read_text(encoding='utf-8'))
    expected_version = json.loads((Path(__file__).parent / 'dsh-runtime/package.json').read_text(encoding='utf-8'))['dependencies']['@deepseek-ai/dsh']
    if metadata.get('version') != expected_version or metadata.get('inputs') != builder.recipe_inputs() \
            or metadata.get('archive_sha256') != sha256(runtime):
        raise ValueError('dsh 离线运行时与当前补丁/依赖锁不一致，请重新生成 dsh-runtime.bin')
    # 同一份 dsh 覆盖层供冷安装和覆盖更新读取；不再嵌进 Ubuntu 大归档重复解压。
    shutil.copyfile(runtime, output / 'dsh-runtime.bin')
    (output / 'dsh-runtime.sha256').write_text(metadata['archive_sha256'] + '\n', encoding='ascii')
    (output / 'offline-rootfs.layout').write_text('split-runtime-v1\n', encoding='ascii')
    tools_spec = importlib.util.spec_from_file_location('ubuntu_tools_builder', Path(__file__).with_name('prepare-ubuntu-tools.py'))
    tools_builder = importlib.util.module_from_spec(tools_spec)
    tools_spec.loader.exec_module(tools_builder)
    tools_archive = source / 'ubuntu-tools.bin'
    tools_metadata = json.loads((source / 'ubuntu-tools.inputs.json').read_text())
    if tools_metadata.get('inputs') != tools_builder.inputs() or tools_metadata.get('archive_sha256') != sha256(tools_archive):
        raise ValueError('Ubuntu 离线基础工具与依赖锁不一致，请运行 tools/prepare-ubuntu-tools.py')
    with tarfile.open(rootfs, 'r:gz') as original:
        status = next(item for item in original if item.name.removeprefix('./') == 'var/lib/dpkg/status')
        if hashlib.sha256(original.extractfile(status).read()).hexdigest() != tools_metadata.get('base_status_sha256'):
            raise ValueError('Ubuntu 基础包状态已变化，必须重新解析离线工具依赖')
    signature = {"source_sha256": sha256(rootfs), "recipe_sha256": sha256(Path(__file__)),
                 "dsh_runtime_sha256": sha256(runtime)}
    report_path = output.parent / "standard-assets-report.json"
    optimized = output / "offline-rootfs.bin"
    if report_path.is_file() and optimized.is_file():
        try:
            cached = json.loads(report_path.read_text(encoding="utf-8"))
            if cached.get("inputs") == signature and cached.get("output_sha256") == sha256(optimized) and cached.get('unpacked_bytes', 0) > 0:
                (output / 'offline-rootfs.bytes').write_text(str(cached['unpacked_bytes']) + '\n', encoding='ascii')
                (output / 'offline-rootfs.sha256').write_text(cached['output_sha256'] + '\n', encoding='ascii')
                print("rootfs 内容未变化，复用已校验的减重资产")
                return
        except (ValueError, OSError):
            pass
    foreign = set()
    with tarfile.open(rootfs, "r|gz") as archive:
        for item in archive:
            name = item.name.removeprefix("./").rstrip("/")
            if item.isfile() and name.endswith("/package.json") and item.size < 1024 * 1024 \
                    and "/node_modules/" in name and ".npm" not in name.split("/"):
                try:
                    pkg = json.load(archive.extractfile(item))
                    if excluded_platform(pkg.get("os"), "linux") or excluded_platform(pkg.get("cpu"), "arm64") \
                            or excluded_platform(pkg.get("libc"), "glibc"):
                        foreign.add(name.rsplit("/", 1)[0])
                except (ValueError, UnicodeError, AttributeError):
                    pass
    removed = collections.Counter()
    kept = hashlib.sha256()
    kept_bytes = 0
    temp = output / "offline-rootfs.bin.tmp"
    with temp.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0,
                                             compresslevel=9) as compressed:
        with tarfile.open(rootfs, "r|gz") as archive, tarfile.open(
                fileobj=compressed, mode="w|", format=tarfile.PAX_FORMAT) as target:
            for item in archive:
                name = item.name.removeprefix("./").rstrip("/")
                reason = "replaced-dsh-runtime" if replaced_runtime(name) else omit(name, foreign)
                if reason:
                    removed[reason] += item.size
                    continue
                # 删除外平台包时同目录的符号链接也必须一起去掉。
                if item.issym():
                    resolved = posixpath.normpath(posixpath.join(posixpath.dirname(name), item.linkname))
                    if omit(resolved, foreign):
                        removed["obsolete-link"] += 0
                        continue
                kept.update((name + "\n").encode())
                kept_bytes += item.size if item.isfile() else 0
                target.addfile(item, archive.extractfile(item) if item.isfile() else None)
    with tarfile.open(runtime, "r|gz") as overlay:
        for item in overlay:
            name = item.name.removeprefix("./").rstrip("/")
            if not valid_overlay(item, name):
                raise ValueError("dsh 覆盖层包含非运行目录：" + name)
            kept.update((name + "\n").encode())
            kept_bytes += item.size if item.isfile() else 0
    temp.replace(output / "offline-rootfs.bin")
    report = dict(original_bytes=rootfs.stat().st_size,
                  unpacked_bytes=kept_bytes,
                  optimized_bytes=(output / "offline-rootfs.bin").stat().st_size,
                  removed_unpacked_bytes=dict(removed), foreign_packages=sorted(foreign),
                  kept_paths_sha256=kept.hexdigest(),
                  inputs=signature, output_sha256=sha256(optimized),
                  note="新 dsh 及依赖只保留一份；环境更新须经过用户数据迁移及校验，禁止直接清空旧数据")
    report_path.write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    (output / 'offline-rootfs.bytes').write_text(str(kept_bytes) + '\n', encoding='ascii')
    (output / 'offline-rootfs.sha256').write_text(report['output_sha256'] + '\n', encoding='ascii')
    print("rootfs: %.2f -> %.2f MiB" % (report["original_bytes"] / 1048576,
                                     report["optimized_bytes"] / 1048576))
    print("移除解压内容:", {key: round(value / 1048576, 2) for key, value in removed.items()})


if __name__ == "__main__":
    main()
