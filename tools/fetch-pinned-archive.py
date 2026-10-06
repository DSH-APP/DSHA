#!/usr/bin/env python3
"""从已发布 APK 中按 HTTP Range 取出未压缩（STORED）的固定归档，并按仓库内摘要核验。

仓库没有基础 Ubuntu 原始归档的生成配方（它是单独固定输入，见 CONTRIBUTING）；
已发布 APK 原样携带了同一字节。期望摘要只从仓库文件读取，不接受命令行传入，
摘要不符直接失败，不写入目标路径。
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DESCRIPTOR = ROOT / 'app/src/main/assets/runtime-descriptor.json'
RECOVERY_LOCK = ROOT / 'tools/recovery-runtime/lock.json'
CHUNK = 1024 * 1024


def expected_digest(args):
    if args.descriptor_input:
        value = json.loads(DESCRIPTOR.read_text(encoding='utf-8'))['inputs'].get(args.descriptor_input)
    else:
        rows = json.loads(RECOVERY_LOCK.read_text(encoding='utf-8'))['archives']
        value = next((row['sha256'] for row in rows if row['asset'] == args.recovery_asset), None)
    if not isinstance(value, str) or len(value) != 64:
        raise SystemExit('PINNED_DIGEST_MISSING')
    return value


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(CHUNK), b''):
            value.update(block)
    return value.hexdigest()


def fetch_range(url, start, end):
    request = urllib.request.Request(url, headers={'Range': 'bytes=%d-%d' % (start, end)})
    response = urllib.request.urlopen(request, timeout=120)
    if response.status != 206:
        raise SystemExit('RANGE_NOT_SUPPORTED: %s' % response.status)
    return response


def locate(url, member):
    with fetch_range(url, 0, 0) as response:
        total = int(response.headers['Content-Range'].split('/')[1])
    tail_start = max(0, total - 65557)
    with fetch_range(url, tail_start, total - 1) as response:
        tail = response.read()
    end = tail.rfind(b'PK\x05\x06')
    if end < 0:
        raise SystemExit('ZIP_EOCD_MISSING')
    size, offset = struct.unpack('<II', tail[end + 12:end + 20])
    with fetch_range(url, offset, offset + size - 1) as response:
        directory = response.read()
    position = 0
    while position < len(directory):
        fields = struct.unpack('<IHHHHHHIIIHHHHHII', directory[position:position + 46])
        if fields[0] != 0x02014b50:
            raise SystemExit('ZIP_DIRECTORY_INVALID')
        method, compressed, plain = fields[4], fields[8], fields[9]
        name_length, extra_length, comment_length, local = fields[10], fields[11], fields[12], fields[16]
        name = directory[position + 46:position + 46 + name_length].decode('utf-8')
        if name == member:
            if method != 0 or compressed != plain:
                raise SystemExit('ZIP_MEMBER_NOT_STORED')
            with fetch_range(url, local, local + 29) as response:
                header = response.read()
            if struct.unpack('<I', header[:4])[0] != 0x04034b50:
                raise SystemExit('ZIP_LOCAL_HEADER_INVALID')
            local_name, local_extra = struct.unpack('<HH', header[26:30])
            return local + 30 + local_name + local_extra, plain
        position += 46 + name_length + extra_length + comment_length
    raise SystemExit('ZIP_MEMBER_MISSING: ' + member)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--member', required=True)
    parser.add_argument('--output', type=Path, required=True)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--descriptor-input')
    source.add_argument('--recovery-asset')
    args = parser.parse_args()
    if not args.url.startswith('https://'):
        parser.error('只接受 HTTPS 来源')
    expected = expected_digest(args)
    output = args.output.absolute()
    if output.is_file() and not output.is_symlink() and digest(output) == expected:
        print(json.dumps({'reused': str(output), 'sha256': expected}))
        return
    start, size = locate(args.url, args.member)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + '.part')
    value = hashlib.sha256()
    received = 0
    with fetch_range(args.url, start, start + size - 1) as response, temporary.open('wb') as stream:
        for block in iter(lambda: response.read(CHUNK), b''):
            received += len(block)
            value.update(block)
            stream.write(block)
    if received != size or value.hexdigest() != expected:
        temporary.unlink(missing_ok=True)
        raise SystemExit('PINNED_ARCHIVE_DIGEST_MISMATCH: got %s (%d bytes), expected %s'
                         % (value.hexdigest(), received, expected))
    temporary.replace(output)
    print(json.dumps({'fetched': str(output), 'bytes': size, 'sha256': expected}))


if __name__ == '__main__':
    main()
