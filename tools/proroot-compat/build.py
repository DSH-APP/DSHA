#!/usr/bin/env python3
"""Build the independent GNU/AArch64 proxy; never replace the five vendor ELFs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
LOCK = json.loads((HERE / 'abi.lock.json').read_text(encoding='utf-8'))


def dynamic(tool, file):
    text = subprocess.check_output([str(tool), '-d', str(file)], text=True)
    return dict(needed=re.findall(r'\(NEEDED\).*?\[([^\]]+)\]', text),
                soname=re.findall(r'\(SONAME\).*?\[([^\]]+)\]', text),
                runpath=re.findall(r'\(RUNPATH\).*?\[([^\]]+)\]', text),
                rpath=re.findall(r'\(RPATH\).*?\[([^\]]+)\]', text))


def build(ndk, output, check=False):
    if 'Pkg.Revision = ' + LOCK['ndk'] not in (ndk / 'source.properties').read_text():
        raise ValueError('PROROOT_PROXY_NDK')
    for source, key in [('stack-sysconf.c', 'proxySourceSha256'), ('linker-entry.c', 'entrySourceSha256')]:
        if hashlib.sha256((HERE / source).read_bytes().replace(b'\r\n', b'\n')).hexdigest() != LOCK[key]:
            raise ValueError('PROROOT_PROXY_SOURCE_IDENTITY:' + source)
    host = {'Windows': 'windows-x86_64', 'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64'}[platform.system()]
    suffix = '.exe' if platform.system() == 'Windows' else ''
    tools = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    readelf = tools / ('llvm-readelf' + suffix)
    vendor = ROOT / LOCK['vendorRuntime']['path']
    if hashlib.sha256(vendor.read_bytes()).hexdigest() != LOCK['vendorRuntime']['sha256']:
        raise ValueError('PROROOT_PROXY_VENDOR_BYTES')
    actual = dynamic(readelf, vendor)
    if actual['needed'] != LOCK['vendorRuntime']['needed'] or actual['soname'] != [LOCK['vendorRuntime']['soname']]:
        raise ValueError('PROROOT_PROXY_VENDOR_ABI')
    output.parent.mkdir(parents=True, exist_ok=True)
    results = []
    for source, soname, identity, flags in [
            ('stack-sysconf.c', LOCK['proxySoname'], LOCK['proxySha256'], ['-shared']),
            ('linker-entry.c', LOCK['entrySoname'], LOCK['entrySha256'], ['-static-pie', '-Wl,-e,_start'])]:
      target = output if source == 'stack-sysconf.c' else output.with_name(soname)
      with tempfile.TemporaryDirectory(prefix='proroot-proxy-', dir=output.parent) as directory:
        candidate = Path(directory) / soname
        command = [str(tools / ('clang' + suffix)), '--target=' + LOCK['target'],
                   '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', '-fPIC',
                   '-nostdlib', '-fno-stack-protector', '-fno-builtin', '-mno-outline-atomics',
                   '-ffile-prefix-map=' + str(ROOT) + '=/src',
                   '-Wl,-z,relro', '-Wl,-z,now', '-Wl,-z,noexecstack',
                   '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=4096',
                   *flags, '-Wl,-soname,' + soname, str(HERE / source),
                   '-o', str(candidate)]
        subprocess.run(command, check=True)
        subprocess.run([str(tools / ('llvm-strip' + suffix)), '--strip-unneeded', str(candidate)], check=True)
        actual = dynamic(readelf, candidate)
        if actual != dict(needed=[], soname=[soname], runpath=[], rpath=[]):
            raise ValueError('PROROOT_PROXY_DEPENDENCY_SCOPE:' + repr(actual))
        symbols = subprocess.check_output([str(readelf), '--dyn-symbols', str(candidate)], text=True)
        if any(name in symbols for name in ('__stack_chk_fail', '__aeabi_', '__aarch64_')):
            raise ValueError('PROROOT_PROXY_UNEXPECTED_RUNTIME')
        data = candidate.read_bytes()
        if data[:6] != b'\x7fELF\x02\x01' or int.from_bytes(data[18:20], 'little') != 183:
            raise ValueError('PROROOT_PROXY_ELF')
        if hashlib.sha256(data).hexdigest() != identity:
            raise ValueError('PROROOT_PROXY_LOCKED_BUILD_IDENTITY')
        if check:
            if not target.is_file() or target.read_bytes() != data:
                raise ValueError('PROROOT_PROXY_REBUILD_MISMATCH')
        else:
            candidate.replace(target)
        if source == 'linker-entry.c':
            relocation = subprocess.check_output([str(readelf), '-r', str(target)], text=True)
            if 'There are no relocations' not in relocation:
                raise ValueError('PROROOT_ENTRY_RELOCATION')
        results.append(dict(file=soname, sha256=hashlib.sha256(data).hexdigest(), size=len(data), target=LOCK['target'], dynamic=actual))
    print(json.dumps(results, indent=2))
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, default=os.environ.get('ANDROID_NDK_HOME'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.ndk is None: parser.error('--ndk is required')
    build(args.ndk.resolve(), args.output.resolve(), args.check)
