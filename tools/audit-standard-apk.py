#!/usr/bin/env python3
"""只读检查标准 APK 的宿主 JNI 与离线环境 ELF 的 arm64 / 16 KB 段对齐。"""
import argparse
import ctypes
import ctypes.util
import io
import json
import struct
import tarfile
import zipfile


def deb_payload(raw):
    """仅解码 deb 的数据 tar，不执行维护脚本；资产摘要由发布内容核验负责。"""
    if not raw.startswith(b'!<arch>\n'):
        raise ValueError('无效 deb 头')
    offset = 8
    while offset + 60 <= len(raw):
        header = raw[offset:offset + 60]
        length = int(header[48:58]); name = header[:16].decode().strip().rstrip('/')
        data = raw[offset + 60:offset + 60 + length]
        if len(data) != length:
            raise ValueError('deb 成员截断')
        if name.startswith('data.tar'):
            if name.endswith('.zst'):
                from pathlib import Path
                library = ctypes.util.find_library('zstd')
                if not library and Path('C:/Program Files/Git/mingw64/bin/libzstd.dll').is_file():
                    library = 'C:/Program Files/Git/mingw64/bin/libzstd.dll'
                if not library:
                    raise ValueError('检查 Ubuntu 包需要系统 libzstd')
                zstd = ctypes.CDLL(library)
                zstd.ZSTD_getFrameContentSize.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
                zstd.ZSTD_getFrameContentSize.restype = ctypes.c_ulonglong
                source = ctypes.create_string_buffer(data)
                size = zstd.ZSTD_getFrameContentSize(source, len(data))
                unknown = size == (1 << 64) - 1
                if unknown:
                    size = 128 * 1024 * 1024  # dpkg 使用无预告尺寸的流式 zstd 帧，仍给解码设置硬上限。
                if size > 256 * 1024 * 1024:
                    raise ValueError('zstd 成员大小未知或超限')
                target = ctypes.create_string_buffer(size)
                zstd.ZSTD_decompress.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t]
                zstd.ZSTD_decompress.restype = ctypes.c_size_t
                written = zstd.ZSTD_decompress(target, size, source, len(data))
                if written > size or (not unknown and written != size):
                    raise ValueError('zstd 解压失败')
                return target.raw[:written]
            return data
        offset += 60 + length + length % 2
    raise ValueError('deb 缺少数据归档')


def inspect_elf(stream):
    header = stream.read(64)
    if len(header) < 64 or header[:6] != b"\x7fELF\x02\x01":
        return None
    kind, machine = struct.unpack_from("<HH", header, 16)
    if kind not in (2, 3):
        return None
    offset = struct.unpack_from("<Q", header, 32)[0]
    size, count = struct.unpack_from("<HH", header, 54)
    if not count or size < 56 or offset + size * count > 65536:
        raise ValueError("无法读取 ELF program headers")
    data = header + stream.read(max(0, offset + size * count - 64))
    aligned = True
    loads, relros = [], []
    for index in range(count):
        base = offset + index * size
        segment_type = struct.unpack_from("<I", data, base)[0]
        address = struct.unpack_from("<Q", data, base + 16)[0]
        memory_size = struct.unpack_from("<Q", data, base + 40)[0]
        if segment_type == 0x6474e552:
            relros.append((address, address + memory_size))
        if segment_type != 1:
            continue
        loads.append((address, address + memory_size))
        file_offset, address = struct.unpack_from("<QQ", data, base + 8)
        alignment = struct.unpack_from("<Q", data, base + 48)[0]
        aligned &= alignment >= 16384 and (file_offset - address) % 16384 == 0
    mapped = True
    for page in (4096, 16384):
        intervals = sorted((start // page * page, (end + page - 1) // page * page) for start, end in loads)
        for start, end in relros:
            cursor, limit = start // page * page, (end + page - 1) // page * page
            for left, right in intervals:
                if left <= cursor < right: cursor = right
            mapped &= cursor >= limit
    return dict(machine=machine, aligned_16k=aligned, relro_mapped=mapped)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--report")
    args = parser.parse_args()
    counts = dict(host=0, rootfs=0, python=0, wheels=0, python_support=0, pnpm=0, ubuntu_tools=0)
    failures, foreign, relro_failures = [], [], []

    def check(stream, name, group):
        elf = inspect_elf(stream)
        if not elf:
            return
        if elf["machine"] != 183:
            foreign.append(name)
            return
        counts[group] = counts.get(group, 0) + 1
        if not elf["aligned_16k"]:
            failures.append(name)
        if not elf["relro_mapped"]:
            relro_failures.append(name)

    with zipfile.ZipFile(args.apk) as apk:
        for item in apk.infolist():
            if item.filename.startswith("lib/"):
                with apk.open(item) as stream:
                    check(stream, item.filename, "host")
        for asset, group in (("offline-rootfs.bin", "rootfs"), ("dsh-runtime.bin", "dsh"), ("glibc-python.bin", "python"),
                             ("adb-wheels.bin", "wheels"), ("python-support.bin", "python_support"),
                             ("pnpm-runtime.bin", "pnpm"), ("ubuntu-tools.bin", "ubuntu_tools")):
            if asset == 'dsh-runtime.bin' and 'assets/' + asset not in apk.namelist():
                continue
            with apk.open("assets/" + asset) as stream, tarfile.open(fileobj=stream, mode="r|gz") as archive:
                for item in archive:
                    if not item.isfile():
                        continue
                    if group == 'ubuntu_tools' and item.name.endswith('.deb'):
                        with tarfile.open(fileobj=io.BytesIO(deb_payload(archive.extractfile(item).read())), mode='r:*') as deb:
                            for entry in deb:
                                if entry.isfile():
                                    check(deb.extractfile(entry), item.name + '/' + entry.name, group)
                    elif group == "wheels" and item.name.endswith(".whl"):
                        with zipfile.ZipFile(io.BytesIO(archive.extractfile(item).read())) as wheel:
                            for entry in wheel.infolist():
                                if entry.filename.endswith(".so"):
                                    with wheel.open(entry) as binary:
                                        check(binary, item.name + "/" + entry.filename, group)
                    else:
                        check(archive.extractfile(item), asset + "/" + item.name, group)
    report = dict(arm64_elf_counts=counts, unaligned_16k=failures, other_architectures=foreign,
                  relro_outside_load=relro_failures,
                  limitation="静态 ELF 检查不能替代真实 16 KB 内核上的 proot / dsh / ADB 运行验证")
    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.report:
        from pathlib import Path
        Path(args.report).write_text(text, encoding="utf-8")
    print(text)
    return bool(failures or foreign or relro_failures)


if __name__ == "__main__":
    raise SystemExit(main())
