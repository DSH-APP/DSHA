#!/usr/bin/env python3
"""Prepare byte-verified React 18 browser test files outside signed assets."""
import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
STORE = ROOT / 'app/build/browser-react-reference'
LOCK = ROOT / 'tools/browser-react-reference.lock.json'


def prepare(check=False):
    lock = json.loads(LOCK.read_text(encoding='utf8'))
    if lock.get('schema') != 1 or len(lock.get('packages', [])) != 2:
        raise ValueError('BROWSER_REACT_LOCK')
    for package in lock['packages']:
        name, version = package['name'], package['version']
        if (name, version) not in {('react', '18.3.1'), ('react-dom', '18.3.1')}:
            raise ValueError('BROWSER_REACT_PACKAGE')
        archive = STORE / (name + '-' + version + '.tgz')
        if not archive.is_file():
            if check:
                raise ValueError('BROWSER_REACT_NOT_PREPARED:' + name)
            STORE.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(package['url'], timeout=40) as response:
                archive.write_bytes(response.read(10 * 1024 * 1024 + 1))
        data = archive.read_bytes()
        if len(data) > 10 * 1024 * 1024 or 'sha512-' + base64.b64encode(hashlib.sha512(data).digest()).decode() != package['integrity']:
            raise ValueError('BROWSER_REACT_INTEGRITY:' + name)
        member = 'package/' + package['file']
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as source:
            item = source.getmember(member)
            if not item.isfile() or item.size > 5 * 1024 * 1024:
                raise ValueError('BROWSER_REACT_MEMBER:' + name)
            expected = source.extractfile(item).read()
        target = STORE / name / package['file']
        if target.is_symlink():
            raise ValueError('BROWSER_REACT_LINK:' + name)
        if check:
            if not target.is_file() or target.read_bytes() != expected:
                raise ValueError('BROWSER_REACT_BYTES:' + name)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(expected)
    return STORE


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    print('PASS byte-verified React browser test reference:', prepare(args.check))
