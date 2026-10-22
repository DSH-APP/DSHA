#!/usr/bin/env python3
"""Check the independent proxy's identity and its exact native dependency boundary."""
import hashlib
import json
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / 'tools/proroot-compat/abi.lock.json').read_text(encoding='utf-8'))


def require(value, reason):
    if not value: raise ValueError(reason)


def dynamic(data, no_relocations=False):
    require(len(data) >= 64 and data[:6] == b'\x7fELF\x02\x01', 'PROXY_ELF_HEADER')
    require(struct.unpack_from('<H', data, 18)[0] == 183, 'PROXY_ARM64')
    offset = struct.unpack_from('<Q', data, 32)[0]
    width, count = struct.unpack_from('<HH', data, 54)
    require(56 <= width <= 128 and 1 <= count <= 128 and offset + width * count <= len(data), 'PROXY_PROGRAM_HEADERS')
    loads, entries = [], []
    for index in range(count):
        kind, flags, start, address, physical, size, memory, alignment = struct.unpack_from('<IIQQQQQQ', data, offset + width * index)
        require(start + size <= len(data), 'PROXY_SEGMENT_BOUNDS')
        require(kind != 3, 'PROXY_MUST_NOT_USE_ANDROID_INTERPRETER')
        if kind == 1:
            require(alignment >= 16384 and (start - address) % 16384 == 0, 'PROXY_PAGE_ALIGNMENT')
            loads.append((address, address + size, start))
        if kind == 2:
            require(size % 16 == 0 and size <= 8192, 'PROXY_DYNAMIC_BOUNDS')
            for at in range(start, start + size, 16):
                tag, value = struct.unpack_from('<QQ', data, at)
                if tag == 0: break
                entries.append((tag, value))
    unique = dict(entries)
    if no_relocations:
        require(all(unique.get(tag, 0) == 0 for tag in (2, 8, 18, 35)), 'ENTRY_MUST_BE_RELOCATION_FREE')
    address, size = unique.get(5), unique.get(10)
    require(isinstance(address, int) and isinstance(size, int) and 0 < size <= 65536, 'PROXY_STRING_TABLE')
    positions = [start + address - low for low, high, start in loads if low <= address and address + size <= high]
    require(len(positions) == 1, 'PROXY_STRING_TABLE_SCOPE')
    strings = data[positions[0]:positions[0] + size]
    def names(tag):
        result = []
        for key, index in entries:
            if key != tag: continue
            require(index < len(strings) and strings.find(b'\0', index) >= 0, 'PROXY_STRING_BOUNDS')
            result.append(strings[index:strings.index(b'\0', index)].decode('ascii'))
        return result
    return dict(needed=names(1), soname=names(14), rpath=names(15), runpath=names(29))


def main():
    require(not sys.flags.optimize, 'TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    native = ROOT / 'app/src/main/jniLibs/arm64-v8a'
    for name, expected in LOCK['originalVendorSha256'].items():
        require(hashlib.sha256((native / name).read_bytes()).hexdigest() == expected, 'PROROOT_VENDOR_CHANGED:' + name)
    source = (ROOT / 'tools/proroot-compat/stack-sysconf.c').read_bytes().replace(b'\r\n', b'\n')
    require(hashlib.sha256(source).hexdigest() == LOCK['proxySourceSha256'], 'PROXY_SOURCE_IDENTITY')
    data = (native / LOCK['proxySoname']).read_bytes()
    require(hashlib.sha256(data).hexdigest() == LOCK['proxySha256'], 'PROXY_BINARY_IDENTITY')
    expected = dict(needed=LOCK['proxyNeeded'], soname=[LOCK['proxySoname']], rpath=LOCK['proxyRpath'], runpath=[])
    require(dynamic(data) == expected, 'PROXY_DEPENDENCY_SCOPE')
    source = (ROOT / 'tools/proroot-compat/linker-entry.c').read_bytes().replace(b'\r\n', b'\n')
    require(hashlib.sha256(source).hexdigest() == LOCK['entrySourceSha256'], 'ENTRY_SOURCE_IDENTITY')
    entry = (native / LOCK['entrySoname']).read_bytes()
    require(hashlib.sha256(entry).hexdigest() == LOCK['entrySha256'], 'ENTRY_BINARY_IDENTITY')
    require(dynamic(entry, True) == dict(needed=[], soname=[LOCK['entrySoname']], rpath=[], runpath=[]), 'ENTRY_DEPENDENCY_SCOPE')
    vendor = dynamic((native / 'libproroot-runtime.so').read_bytes())
    require(vendor['needed'] == LOCK['vendorRuntime']['needed'] and vendor['soname'] == [LOCK['vendorRuntime']['soname']], 'PROXY_VENDOR_ABI')
    print('PASS shim+entry identity, GNU ARM64, 16 KiB LOADs, no dependencies/search paths, zero entry relocations; original five ELFs unchanged')


if __name__ == '__main__': main()
