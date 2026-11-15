#!/usr/bin/env python3
"""Fetch and verify the pinned GitHub CLI (gh) release tarball.

The tarball is a fixed build input: it is not committed, CI restores it from
the fixed-input bundle and verifies it against this lock. This script is the
only writer: it downloads exactly the pinned URL, verifies the exact SHA-256,
extracts the pinned arm64 member and its license, and (unless --check) stores
them as the APK asset and the packaged license text.
"""
import argparse
import hashlib
import io
import json
import re
import tarfile
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'tools/gh-cli/packages.lock.json'
ASSET = ROOT / 'app/src/main/assets/gh-cli.tar.gz'
LICENSE = ROOT / 'app/src/main/assets/licenses/gh-MIT.txt'
DOWNLOAD_LIMIT = 64 * 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load_lock():
    lock = json.loads(LOCK.read_text(encoding='utf-8'))
    if lock.get('schema') != 1 or lock.get('tool') != 'gh':
        raise ValueError('GH_CLI_LOCK_SCHEMA')
    for key in ('version', 'url', 'sha256', 'member', 'binarySha256', 'licenseMember'):
        if not isinstance(lock.get(key), str) or not lock[key]:
            raise ValueError('GH_CLI_LOCK_FIELD:' + key)
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', lock['version']):
        raise ValueError('GH_CLI_LOCK_VERSION')
    expected_url = ('https://github.com/cli/cli/releases/download/v' + lock['version']
                    + '/gh_' + lock['version'] + '_linux_arm64.tar.gz')
    if lock['url'] != expected_url:
        raise ValueError('GH_CLI_LOCK_URL')
    if lock['member'] != 'gh_' + lock['version'] + '_linux_arm64/bin/gh':
        raise ValueError('GH_CLI_LOCK_MEMBER')
    if not re.fullmatch(r'[a-f0-9]{64}', lock['sha256']) \
            or not re.fullmatch(r'[a-f0-9]{64}', lock['binarySha256']) \
            or not re.fullmatch(r'[a-f0-9]{64}', lock.get('licenseSha256', '')):
        raise ValueError('GH_CLI_LOCK_DIGEST')
    if type(lock.get('bytes')) is not int or not 0 < lock['bytes'] <= DOWNLOAD_LIMIT:
        raise ValueError('GH_CLI_LOCK_BYTES')
    if type(lock.get('binaryBytes')) is not int or not 0 < lock['binaryBytes'] <= 256 * 1024 * 1024:
        raise ValueError('GH_CLI_LOCK_BINARY_BYTES')
    return lock


def require_https(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username is not None \
            or parsed.password is not None or '\\' in url:
        raise ValueError('GH_CLI_DOWNLOAD_HTTPS')


class HttpsOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        require_https(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(url):
    require_https(url)
    opener = urllib.request.build_opener(HttpsOnly())
    with opener.open(urllib.request.Request(url), timeout=120) as stream:
        data = stream.read(DOWNLOAD_LIMIT + 1)
    if len(data) > DOWNLOAD_LIMIT:
        raise ValueError('GH_CLI_DOWNLOAD_LIMIT')
    return data


def inspect(tarball, lock):
    with tarfile.open(fileobj=io.BytesIO(tarball), mode='r:gz') as archive:
        member = archive.getmember(lock['member'])
        if not member.isfile():
            raise ValueError('GH_CLI_MEMBER_TYPE')
        binary = archive.extractfile(member).read()
        license_member = archive.getmember(lock['licenseMember'])
        if not license_member.isfile() or license_member.size > 64 * 1024:
            raise ValueError('GH_CLI_LICENSE_TYPE')
        license_text = archive.extractfile(license_member).read()
    if len(binary) != lock['binaryBytes'] or digest(binary) != lock['binarySha256']:
        raise ValueError('GH_CLI_BINARY_DIGEST')
    if digest(license_text) != lock['licenseSha256']:
        raise ValueError('GH_CLI_LICENSE_DIGEST')
    if b'MIT License' not in license_text:
        raise ValueError('GH_CLI_LICENSE_TEXT')
    return binary, license_text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true',
                        help='verify the restored asset and license instead of downloading')
    args = parser.parse_args()
    lock = load_lock()
    if args.check:
        if not ASSET.is_file():
            raise SystemExit('GH_CLI_ASSET_MISSING: run tools/prepare-gh-cli.py')
        tarball = ASSET.read_bytes()
    else:
        tarball = download(lock['url'])
    if len(tarball) != lock['bytes'] or digest(tarball) != lock['sha256']:
        raise SystemExit('GH_CLI_TARBALL_DIGEST: pinned release bytes do not match the lock')
    _, license_text = inspect(tarball, lock)
    if not args.check:
        ASSET.parent.mkdir(parents=True, exist_ok=True)
        temporary = ASSET.with_suffix('.tmp')
        temporary.write_bytes(tarball)
        temporary.replace(ASSET)
        LICENSE.parent.mkdir(parents=True, exist_ok=True)
        LICENSE.write_bytes(license_text)
    elif not LICENSE.is_file() or LICENSE.read_bytes() != license_text:
        raise SystemExit('GH_CLI_LICENSE_MISMATCH: regenerate assets/licenses/gh-MIT.txt')
    print(json.dumps({
        'tool': 'gh',
        'version': lock['version'],
        'archiveBytes': len(tarball),
        'archiveSha256': digest(tarball),
        'binaryBytes': lock['binaryBytes'],
        'binarySha256': lock['binarySha256'],
        'licenseBytes': len(license_text),
    }, sort_keys=True))


if __name__ == '__main__':
    main()
