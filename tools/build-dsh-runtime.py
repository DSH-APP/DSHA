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
HOOKS = Path(__file__).resolve().parents[1] / 'app/src/main/assets/runtime-fs'
SESSION_HOOKS = HOOKS.parent / 'session-compat'
COMBO_HOOKS = HOOKS.parent / 'client-combo-cache'
COMBO_PATCH = HOOKS.parent / 'client-combo-patch.json'
COMBO_MODULE = '@deepseek-ai/dsh-client-modules/lib/index.js'
LOCK_ROOT = HOOKS.parents[4] / 'tools/dsh-runtime'
PATCHES = {'@deepseek-ai/dsh-fs-local/lib/index.js': 'publishExclusive',
           '@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js': 'publishSessionExclusive'}


def recipe_inputs():
    paths = [Path(__file__), LOCK_ROOT / 'package.json', LOCK_ROOT / 'package-lock.json']
    paths += [directory / name for directory in (HOOKS, SESSION_HOOKS, COMBO_HOOKS) for name in ('index.js', 'package.json')]
    paths.append(COMBO_PATCH)
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


def omitted(relative):
    parts = relative.parts
    if '.bin' in parts or '.cache' in parts or relative.name == '.package-lock.json':
        return True
    if 'prebuilds' in parts:
        at = parts.index('prebuilds') + 1
        if at < len(parts) and parts[at] != 'linux-arm64':
            return True
    return False


def build(source, output, version):
    source = source.resolve(strict=True)
    package = json.loads((source / PACKAGE / 'package.json').read_text(encoding='utf-8'))
    if package.get('name') != PACKAGE or package.get('version') != version:
        raise ValueError('npm 安装树不是要求的 dsh 版本')
    if not (source / PACKAGE / 'lib/bin.js').is_file():
        raise ValueError('npm 包没有编译后的 dsh 入口')
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + '.tmp')
    count, size, binaries = 0, 0, []
    links = {}
    try:
        with temporary.open('wb') as raw, gzip.GzipFile(fileobj=raw, mode='wb', filename='', mtime=0, compresslevel=9) as compressed:
            with tarfile.open(fileobj=compressed, mode='w|', format=tarfile.PAX_FORMAT) as archive:
                for path in sorted(source.rglob('*')):
                    relative = path.relative_to(source)
                    if omitted(relative):
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
                            or relative.as_posix() in (COMBO_MODULE, '@deepseek-ai/dsh-session-format-v2-to-v3/lib/index.js') else None
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
            'arm64_binaries': binaries, 'note': '安装脚本没有在宿主执行；仍须 Linux arm64 真运行验证'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='npm 安装后的 node_modules 目录')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--version', default=json.loads((LOCK_ROOT / 'package.json').read_text(encoding='utf-8'))['dependencies'][PACKAGE])
    args = parser.parse_args()
    print(json.dumps(build(args.source, args.output, args.version), ensure_ascii=False, indent=2))
