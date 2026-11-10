#!/usr/bin/env python3
"""由 tools/external-tools/lock.json 生成并核对外接设备工具证明 app/src/main/assets/external-tool-proof.json。

--check        （默认，构建用，不联网）证明必须与锁逐字节一致
--write        按锁重写证明
--verify-debs  下载或复用缓存的 deb，核对 Ubuntu 索引给出的大小与 SHA-256，解包后核对锁内文件摘要
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'tools/external-tools/lock.json'
PROOF = ROOT / 'app/src/main/assets/external-tool-proof.json'
REQUIRED = ('adb', 'fastboot', 'libc6')
ENTRYPOINTS = ('usr/bin/adb', 'usr/bin/fastboot', 'usr/lib/ld-linux-aarch64.so.1')
HEX64 = re.compile(r'[0-9a-f]{64}')
RELATIVE = re.compile(r'(?:[A-Za-z0-9_.+~-]+/)*[A-Za-z0-9_.+~-]+')


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def safe_relative(value):
    return isinstance(value, str) and RELATIVE.fullmatch(value) is not None and '..' not in value.split('/')


def validate(lock):
    """锁的结构核验；任何不符抛 ValueError。"""
    if lock.get('format') != 1 or lock.get('architecture') != 'arm64':
        raise ValueError('外接工具锁格式或架构不符')
    for source in lock.get('sources', []):
        if not HEX64.fullmatch(source.get('inReleaseSha256', '')):
            raise ValueError('外接工具锁来源摘要无效')
    packages = lock.get('packages', [])
    names = [p.get('Package') for p in packages]
    if sorted(names) != sorted(REQUIRED) or len(set(names)) != len(names):
        raise ValueError('外接工具锁必须且只能包含 ' + ' / '.join(REQUIRED))
    owned = {}
    for p in packages:
        if not HEX64.fullmatch(p.get('SHA256', '')) or not isinstance(p.get('Size'), int) or p['Size'] <= 0:
            raise ValueError('软件包摘要或大小无效：' + p['Package'])
        filename = p.get('Filename', '')
        if not filename.startswith('pool/') or not filename.endswith('_arm64.deb') or not safe_relative(filename):
            raise ValueError('软件包路径无效：' + p['Package'])
        if not re.fullmatch(r'[0-9A-Za-z.+~:-]{1,64}', p.get('Version', '')):
            raise ValueError('软件包版本无效：' + p['Package'])
        files = p.get('files') or {}
        if not files:
            raise ValueError('软件包未列出核验文件：' + p['Package'])
        for path, digest in files.items():
            if not safe_relative(path) or not HEX64.fullmatch(digest) or path in owned:
                raise ValueError('核验文件无效或重复：' + path)
            owned[path] = (p, digest)
    entrypoints = lock.get('entrypoints') or {}
    if sorted(entrypoints) != sorted(ENTRYPOINTS):
        raise ValueError('入口必须为 ' + ' / '.join(ENTRYPOINTS))
    for entry, target in entrypoints.items():
        if target not in owned:
            raise ValueError('入口未指向锁内文件：' + entry)
    return owned


def render(lock):
    owned = validate(lock)
    packages = {p['Package']: p['Version'] for p in lock['packages']}
    proof = {
        'schema': 2,
        'generatedBy': 'tools/prepare-external-tools.py',
        'lockSha256': sha256_bytes(LOCK.read_bytes()),
        'distribution': lock['distribution'],
        'packages': dict(sorted(packages.items())),
        'files': {entry: owned[target][1] for entry, target in sorted(lock['entrypoints'].items())},
        'targets': dict(sorted(lock['entrypoints'].items())),
        'install': 'apt-get install --allow-downgrades '
        + ' '.join(f'{name}={packages[name]}' for name in sorted(packages)),
    }
    return json.dumps(proof, ensure_ascii=False, indent=2) + '\n'


def verify_debs(lock, cache, offline):
    validate(lock)
    if shutil.which('dpkg-deb') is None:
        raise ValueError('需要 dpkg-deb 解包（Ubuntu noble 的 deb 使用 zstd）')
    cache = cache.resolve()
    cache.relative_to(ROOT / 'app/build')
    cache.mkdir(parents=True, exist_ok=True)
    for p in lock['packages']:
        target = cache / Path(p['Filename']).name
        if not target.is_file() or sha256_file(target) != p['SHA256']:
            if offline:
                raise ValueError('离线缓存缺少：' + target.name)
            partial = target.with_suffix('.part')
            with urllib.request.urlopen(lock['mirror'] + p['Filename'], timeout=60) as response:
                partial.write_bytes(response.read())
            if partial.stat().st_size != p['Size'] or sha256_file(partial) != p['SHA256']:
                partial.unlink()
                raise ValueError('Ubuntu 软件包摘要不符：' + target.name)
            partial.replace(target)
        with tempfile.TemporaryDirectory(dir=cache) as unpack:
            subprocess.run(['dpkg-deb', '-x', str(target), unpack], check=True)
            for path, digest in p['files'].items():
                file = Path(unpack) / path
                if file.is_symlink() or not file.is_file() or sha256_file(file) != digest:
                    raise ValueError(f'deb 内文件摘要不符：{p["Package"]} {path}')
    return len(lock['packages'])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--check', action='store_true')
    mode.add_argument('--write', action='store_true')
    mode.add_argument('--verify-debs', action='store_true')
    parser.add_argument('--cache', type=Path, default=ROOT / 'app/build/external-tools-cache')
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text(encoding='utf-8'))
    if args.verify_debs:
        count = verify_debs(lock, args.cache, args.offline)
        print(json.dumps({'verifiedPackages': count}))
        return
    expected = render(lock)
    if args.write:
        PROOF.write_text(expected, encoding='utf-8')
        print('WROTE ' + PROOF.relative_to(ROOT).as_posix())
        return
    if not PROOF.is_file() or PROOF.read_text(encoding='utf-8') != expected:
        raise SystemExit('外接工具证明与 tools/external-tools/lock.json 不一致；先运行 tools/prepare-external-tools.py --write')
    print('PASS external tool proof matches lock')


if __name__ == '__main__':
    main()
