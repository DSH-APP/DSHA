#!/usr/bin/env python3
"""从固定预构建源码树部署移动插件；不运行上游 pnpm/build/prepack。"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'tools/mobile-plugin-upstream.lock.json'
DESTINATION = ROOT / 'app/src/main/assets/builtin-plugins/dsh-web-mobile'
PATCHED = {'lib/client.js', 'lib/compress.js', 'lib/delete-session.js', 'lib/index.js',
           'lib/compress.js.map', 'lib/delete-session.js.map', 'lib/index.js.map',
           'src/client/i18n/locales.ts', 'src/client/components/file-share-locale.ts',
           'src/client/effects/attachment-mention.ts', 'src/client/index.tsx',
           'src/client/effects/file-share.ts', 'lib/types/client/i18n/locales.d.ts',
           'src/client/effects/drawer-session-log.ts', 'src/client/effects/drawer-quick-settings.ts',
           'lib/types/client/components/file-share-locale.d.ts',
           'lib/types/client/effects/attachment-mention.d.ts'}
# 上游 3.3.1 起删掉了 lib/file-transfer.js（b52b2cb「清 lib 残留」）；DSHA 的移动服务入口
# tools/mobile-server/index.js 仍 import 该模块，改由 tools/file-transfer/file-transfer.js
# 生成为仅本地追加成员，字节核验与 source map 规则不变。
ADDITIONAL = {'lib/file-transfer.js', 'lib/file-transfer.js.map'}
SHA1 = re.compile(r'[0-9a-f]{40}\Z')

def validate_lock(lock, package):
    if lock.get('schema') != 1 or lock.get('name') != 'dsh-web-mobile' or not lock.get('version'):
        raise ValueError('MOBILE_UPSTREAM_LOCK')
    if lock.get('ref') != 'main' or not lock.get('tag'):
        raise ValueError('MOBILE_UPSTREAM_LOCK')
    for key in ('commit', 'releaseCommit'):
        if not SHA1.fullmatch(lock.get(key, '')):
            raise ValueError('MOBILE_UPSTREAM_LOCK: ' + key)
    archive = lock.get('archive', {})
    if not str(archive.get('url', '')).endswith('/tar.gz/' + lock['commit']):
        raise ValueError('MOBILE_UPSTREAM_ARCHIVE')
    if package.get('name') != lock['name'] or package.get('version') != lock['version'] \
            or package.get('license') != 'MIT':
        raise ValueError('MOBILE_UPSTREAM_METADATA')
    recorded = package.get('dshaUpstream')
    if recorded and (recorded.get('version') != lock['version'] or recorded.get('commit') != lock['commit']):
        raise ValueError('MOBILE_UPSTREAM_COMMIT')

def prepare(source, write):
    lock = json.loads(LOCK.read_text(encoding='utf8'))
    if lock.get('schema') != 1:
        raise ValueError('MOBILE_UPSTREAM_LOCK')
    source = source.resolve(strict=True)
    contents = {}
    for name, expected in lock['files'].items():
        relative = PurePosixPath(name)
        if relative.is_absolute() or '..' in relative.parts or '\\' in name:
            raise ValueError('MOBILE_UPSTREAM_PATH')
        file = source / name
        if file.is_symlink() or not file.is_file() or not file.resolve().is_relative_to(source):
            raise ValueError('MOBILE_UPSTREAM_FILE: ' + name)
        content = file.read_bytes()
        if len(content) != expected['bytes'] or hashlib.sha256(content).hexdigest() != expected['sha256']:
            raise ValueError('MOBILE_UPSTREAM_BYTES: ' + name)
        contents[name] = content
    package = json.loads(contents['package.json'])
    validate_lock(lock, package)
    package['dshaUpstream'] = {key: lock[key] for key in ['version', 'source', 'commit', 'sourceDate']}
    # 最新 main 已撤回标签之后的视口试验；发布标签与实际取用提交分别记录。
    package['dshaUpstream'].update(ref=lock.get('ref', lock['tag']), releaseTag=lock['tag'],
                                  releaseCommit=lock['releaseCommit'], clientSha256=lock['files']['lib/client.js']['sha256'],
                                  lock='tools/mobile-plugin-upstream.lock.json', prebuilt=True)
    contents['package.json'] = (json.dumps(package, ensure_ascii=False, indent=2) + '\n').encode()
    for name, content in contents.items():
        if name in {'lib/client.js', 'lib/compress.js', 'lib/delete-session.js', 'lib/index.js'}:
            pristine = ROOT / 'tools/mobile-plugin-upstream' / name
            if write:
                pristine.parent.mkdir(parents=True, exist_ok=True)
                pristine.write_bytes(content)
            elif not pristine.is_file() or pristine.read_bytes() != content:
                raise ValueError('MOBILE_PRISTINE_BYTES: ' + name)
        if name in PATCHED:
            continue
        destination = DESTINATION / name
        if write:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)
        elif not destination.is_file() or destination.read_bytes() != content:
            raise ValueError('MOBILE_DEPLOYED_BYTES: ' + name)
    node = os.environ.get('DSHA_NODE', 'node')
    action = '--write' if write else '--check'
    patch = ROOT / 'tools/apply-mobile-client-patches.mjs'
    subprocess.run([node, str(patch), str(source / 'lib/client.js'), action], cwd=ROOT, check=True)
    subprocess.run([node, str(patch), '--server', str(source / 'lib'), action], cwd=ROOT, check=True)
    subprocess.run([node, str(patch), '--locale-sources', str(source), action], cwd=ROOT, check=True)
    # Retire only the explicitly reviewed previous-version members; unknown local files stay errors.
    for name in lock.get('retiredFiles', []):
        relative = PurePosixPath(name)
        if relative.is_absolute() or '..' in relative.parts or '\\' in name or name in contents:
            raise ValueError('MOBILE_RETIRED_PATH')
        target = DESTINATION / name
        if not target.exists() and not target.is_symlink():
            continue
        if target.is_symlink() or not target.is_file() or not target.resolve().is_relative_to(DESTINATION.resolve()):
            raise ValueError('MOBILE_RETIRED_FILE: ' + name)
        if write:
            target.unlink()
        else:
            raise ValueError('MOBILE_RETIRED_PRESENT: ' + name)
    actual = {p.relative_to(DESTINATION).as_posix() for p in DESTINATION.rglob('*') if p.is_file()}
    expected_members = set(contents) | ADDITIONAL
    if actual != expected_members:
        raise ValueError('MOBILE_DEPLOYED_MEMBERS: ' + str(sorted(actual.symmetric_difference(expected_members))))
    print('PASS mobile fixed prebuilt assets:', lock['commit'], 'files:', len(contents))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--upstream-dir', required=True, type=Path)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--write', action='store_true')
    mode.add_argument('--check', action='store_true')
    options = parser.parse_args()
    prepare(options.upstream_dir, options.write)
