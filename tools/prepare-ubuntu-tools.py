#!/usr/bin/env python3
"""把已由 Ubuntu 签名索引锁定的 ARM64 deb 包生成为离线安装资产。"""
import argparse
import concurrent.futures
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'tools/ubuntu-tools/packages.lock.json'


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def inputs():
    return {p.relative_to(ROOT).as_posix(): digest(p) for p in (Path(__file__), LOCK)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, default=ROOT / 'app/build/ubuntu-tools-cache')
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    cache = args.cache.resolve()
    cache.relative_to(ROOT / 'app/build')
    cache.mkdir(parents=True, exist_ok=True)
    lock = json.loads(LOCK.read_text())
    if lock['format'] != 1 or lock['architecture'] != 'arm64':
        raise ValueError('不支持的 Ubuntu 工具锁')
    rows = lock['packages']
    if len({r['Package'] for r in rows}) != len(rows):
        raise ValueError('工具包重复')
    def fetch(row):
        relative = row['Filename']
        if not relative.startswith('pool/main/') or '..' in relative.split('/') or not re.fullmatch(r'[A-Za-z0-9_./+~%-]+', relative):
            raise ValueError('软件包路径无效')
        if row['Architecture'] not in ('arm64', 'all'):
            raise ValueError('软件包架构不符')
        target = cache / Path(relative).name
        if not target.is_file() or digest(target) != row['SHA256']:
            if args.offline:
                raise ValueError('离线缓存缺少：' + target.name)
            temporary = target.with_suffix('.part')
            with urllib.request.urlopen('https://ports.ubuntu.com/ubuntu-ports/' + relative, timeout=40) as response:
                temporary.write_bytes(response.read())
            if temporary.stat().st_size != int(row['Size']) or digest(temporary) != row['SHA256']:
                temporary.unlink()
                raise ValueError('Ubuntu 软件包摘要不符：' + target.name)
            temporary.replace(target)
        return target
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as workers:
        files = list(workers.map(fetch, rows))
    output = ROOT / 'app/src/main/assets/ubuntu-tools.bin'
    temporary = output.with_suffix('.tmp')
    with temporary.open('wb') as raw, gzip.GzipFile(fileobj=raw, filename='', mode='wb', mtime=0, compresslevel=1) as compressed:
        with tarfile.open(fileobj=compressed, mode='w|', format=tarfile.PAX_FORMAT) as archive:
            for file in files:
                item = tarfile.TarInfo(file.name); item.mode = 0o600; item.size = file.stat().st_size
                with file.open('rb') as stream: archive.addfile(item, stream)
            for name, data in {
                'SHA256SUMS': ''.join(r['SHA256'] + '  ' + f.name + '\n' for r, f in zip(rows, files)),
                'packages.txt': ' '.join(r['Package'] for r in rows) + '\n',
                'version.txt': digest(LOCK) + '\n',
            }.items():
                value = data.encode(); item = tarfile.TarInfo(name); item.mode = 0o600; item.size = len(value)
                archive.addfile(item, io.BytesIO(value))
    temporary.replace(output)
    metadata = {'inputs': inputs(), 'archive_sha256': digest(output),
                'installed_bytes': sum(int(r['Installed-Size']) * 1024 for r in rows),
                'base_status_sha256': lock['baseStatusSha256'], 'packages': len(rows)}
    output.with_suffix('.inputs.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps({'archiveBytes': output.stat().st_size, **metadata}))


if __name__ == '__main__':
    main()
