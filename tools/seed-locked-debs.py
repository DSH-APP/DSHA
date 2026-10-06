#!/usr/bin/env python3
"""为离线生成器预填已锁定的 Ubuntu deb 缓存。

ports.ubuntu.com 的 pool 只保留每个系列的最新安全更新，锁定的旧版本会被移除（404）；
Launchpad librarian 永久保存同一文件。这里只按锁里已有的文件名和 SHA-256 下载，
摘要不符直接失败，写入的缓存由 build-standard-runtime.py 与 prepare-ubuntu-tools.py
按同一摘要再次核验后复用。不修改任何锁或生成器。
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MIRRORS = ('https://ports.ubuntu.com/ubuntu-ports/{path}',
           'https://launchpad.net/ubuntu/+archive/primary/+files/{name}')


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def locked_rows():
    spec = importlib.util.spec_from_file_location('standard_runtime', ROOT / 'tools/build-standard-runtime.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    runtime = [(remote, checksum) for remote, checksum, *_ in module.PACKAGES]
    lock = json.loads((ROOT / 'tools/ubuntu-tools/packages.lock.json').read_text(encoding='utf-8'))
    tools = [(row['Filename'], row['SHA256']) for row in lock['packages']]
    return runtime, tools


def seed(rows, cache):
    cache.mkdir(parents=True, exist_ok=True)
    for remote, checksum in rows:
        if not remote.startswith('pool/main/') or '..' in remote.split('/'):
            raise SystemExit('LOCKED_DEB_PATH: ' + remote)
        name = Path(remote).name
        target = cache / name
        if target.is_file() and digest(target) == checksum:
            continue
        for template in MIRRORS:
            url = template.format(path=remote, name=name)
            temporary = target.with_name(name + '.part')
            try:
                with urllib.request.urlopen(url, timeout=60) as response:
                    temporary.write_bytes(response.read())
            except OSError as error:
                print('miss', url, error)
                continue
            if digest(temporary) != checksum:
                temporary.unlink()
                raise SystemExit('LOCKED_DEB_DIGEST: ' + url)
            temporary.replace(target)
            print('seeded', name, 'from', url.split('/')[2])
            break
        else:
            raise SystemExit('LOCKED_DEB_UNAVAILABLE: ' + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-cache', type=Path, default=ROOT / 'app/build/runtime-downloads')
    parser.add_argument('--tools-cache', type=Path, default=ROOT / 'app/build/ubuntu-tools-cache')
    args = parser.parse_args()
    runtime, tools = locked_rows()
    seed(runtime, args.runtime_cache)
    seed(tools, args.tools_cache)
    print('PASS %d locked debs present with pinned SHA-256' % (len(runtime) + len(tools)))


if __name__ == '__main__':
    main()
