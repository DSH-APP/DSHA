#!/usr/bin/env python3
"""Exercise the unmodified ARM64 bridge/linker in WSL/QEMU; never operate a device."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / 'app/src/main/jniLibs/arm64-v8a'
ENTRY = 'libdsha-proroot-linker.so'
SHIM = 'libdsha-proroot-compat.so'
MAIN = r'''
extern long alpha(void); extern long beta(void);
void probe_start(void) {
  long code = alpha() + beta() == 33 ? 0 : 101;
  register long x0 __asm__("x0") = code;
  register long x8 __asm__("x8") = 94;
  __asm__ volatile("svc 0" :: "r"(x0), "r"(x8) : "memory");
  __builtin_unreachable();
}
__asm__(".global _start\n_start:\nbl probe_start\n");
'''
SYSCONF = r'''
extern long sysconf(int); extern int *__errno_location(void);
void probe_start(void) {
  long code = 0;
  if (sysconf(249) <= 0 || sysconf(250) < 20480 || sysconf(75) < 16384) code = 102;
  if (sysconf(30) != 4096) code = 103;
  *__errno_location() = 0;
  if (sysconf(-1) != -1 || *__errno_location() != 22) code = 104;
  register long x0 __asm__("x0") = code;
  register long x8 __asm__("x8") = 94;
  __asm__ volatile("svc 0" :: "r"(x0), "r"(x8) : "memory");
  __builtin_unreachable();
}
__asm__(".global _start\n_start:\nbl probe_start\n");
'''


def main(ndk, distro, output):
    output.mkdir(parents=True, exist_ok=True)
    cc = ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
    def wsl(*args, **kwargs):
        if kwargs.get('text'):
            kwargs.setdefault('encoding', 'utf-8')
            kwargs.setdefault('errors', 'replace')
        return subprocess.run(['wsl', '-d', distro, '--exec', *map(str, args)], capture_output=True, **kwargs)
    def linux(path):
        result = wsl('wslpath', '-a', '-u', str(path.resolve()), text=True, check=True)
        return result.stdout.strip()
    work = linux(output)
    native = linux(NATIVE)
    for name in ('libc.so.6', 'ld-linux-aarch64.so.1', 'libgcc_s.so.1'):
        result = wsl('cat', '/usr/aarch64-linux-gnu/lib/' + name, check=True)
        (output / name).write_bytes(result.stdout)
    for name in ('libproroot-bridge.so', 'libproroot-linker.so', 'libproroot-runtime.so', ENTRY, SHIM):
        shutil.copyfile(NATIVE / name, output / name)
    def compile(source, name, shared=True, dependencies=()):
        (output / (name + '.c')).write_text(source, encoding='utf-8')
        command = [str(cc), '--target=aarch64-linux-gnu', '-nostdlib', '-O2', '-fno-stack-protector',
                   '-Wl,-z,max-page-size=16384', '-fPIC', '-Wl,-rpath,$ORIGIN']
        command += ['-shared', '-Wl,-soname,' + name] if shared else [
            '-pie', '-Wl,--no-dynamic-linker', '-Wl,-e,_start', '-Wl,--allow-shlib-undefined']
        command += [str(output / (name + '.c')), '-Wl,--no-as-needed']
        command += [str(output / dependency) for dependency in dependencies]
        subprocess.run(command + ['-o', str(output / name)], check=True)
    compile('long alpha(void){return 0;} long beta(void){return 0;}', 'libbase.so')
    compile('long alpha(void){return 11;}', 'libvendor.so')
    compile('long beta(void){return 22;}', 'libshim.so')
    compile('long beta(void){return 22;}', 'libdependent.so', dependencies=['libvendor.so'])
    compile(MAIN, 'symbol-probe', False, ['libbase.so', 'libc.so.6'])
    compile(SYSCONF, 'sysconf-probe', False, ['libc.so.6'])
    def qemu(label, executable, arguments, trace=False, nobody=False, extra_env=()):
        command = ['env', 'QEMU_CPU=max,pauth=off', 'LD_LIBRARY_PATH=' + work, 'qemu-aarch64-static']
        command[3:3] = extra_env
        if trace: command += ['-strace']
        if nobody: command = ['/usr/sbin/runuser', '-u', 'nobody', '--', *command]
        result = wsl(*command, executable, *arguments, text=True, errors='replace')
        (output / (label + '.log')).write_text(result.stdout + '\n' + result.stderr, encoding='utf-8')
        return result
    loader = native + '/libproroot-linker.so'
    result = qemu('vendor-repeated-preload', loader, ['--preload', work + '/libshim.so',
        '--preload', work + '/libvendor.so', work + '/symbol-probe'])
    assert result.returncode == 0, result
    dependent = qemu('vendor-dependency-only', loader, ['--preload', work + '/libdependent.so', work + '/symbol-probe'])
    assert dependent.returncode == 101, dependent
    result = qemu('vendor-real-shim-sysconf', loader, ['--preload', native + '/' + SHIM,
        '--preload', work + '/libvendor.so', work + '/sysconf-probe'])
    assert result.returncode == 0, result
    result = qemu('vendor-real-shim-with-libgcc', loader, ['--preload', native + '/' + SHIM,
        '--argv0', 'node', '--preload', work + '/libvendor.so', '--preload', work + '/libgcc_s.so.1', work + '/sysconf-probe'])
    assert result.returncode == 0, result
    # Kernel-owned read-only fixtures model framework native extraction. The guest
    # runs as nobody; a test owned/writable by that UID must fail the production gate.
    framework = '/data/app/dsha-proroot-host-' + uuid.uuid4().hex + '/lib/arm64'
    wsl('install', '-d', '-m', '755', framework, check=True)
    names = ('libproroot-bridge.so', 'libproroot-linker.so', 'libproroot-runtime.so', ENTRY, SHIM)
    wsl('cp', *(native + '/' + name for name in names), framework + '/', check=True)
    wsl('chmod', '555', *(framework + '/' + name for name in names), check=True)
    bridge, entry, runtime = [framework + '/' + name for name in ('libproroot-bridge.so', ENTRY, 'libproroot-runtime.so')]
    original = ['--argv0', 'node', '--preload', runtime, '/payload/node', '-e', 'business-value']
    result = qemu('original-bridge-entry', bridge, [entry, *original], True, True)
    assert result.returncode == 126 and 'errno=8 (Exec format error)' in result.stderr, result
    expected = [bridge, framework + '/libproroot-linker.so', '--preload', framework + '/' + SHIM, *original]
    exact = 'execve("' + bridge + '",{' + ','.join('"' + value + '"' for value in expected) + ',NULL})'
    assert exact in result.stderr, result.stderr
    rootfs = work + '/rootfs'
    gcc = rootfs + '/usr/lib/aarch64-linux-gnu/libgcc_s.so.1'
    env = ['PROROOT_ROOTFS=' + rootfs]
    with_gcc = ['--argv0', 'node', '--preload', runtime, '--preload', gcc, '/payload/node', '-e', 'business-value']
    result = qemu('original-bridge-entry-with-libgcc', bridge, [entry, *with_gcc], True, True, env)
    expected_gcc = [bridge, framework + '/libproroot-linker.so', '--preload', framework + '/' + SHIM, *with_gcc]
    exact_gcc = 'execve("' + bridge + '",{' + ','.join('"' + value + '"' for value in expected_gcc) + ',NULL})'
    assert result.returncode == 126 and exact_gcc in result.stderr and 'errno=8 (Exec format error)' in result.stderr, result
    failures = []
    for label, args, stage in [
            ('writable-runtime', ['--argv0', 'node', '--preload', '/tmp/libproroot-runtime.so', '/payload/node'], 'ARGV_PRELOAD_VENDOR'),
            ('duplicate-preload', ['--preload', runtime, '--preload', runtime, '/payload/node'], 'ENV_ROOTFS_MISSING'),
            ('duplicate-argv0', ['--argv0', 'node', '--argv0', 'bash', '--preload', runtime, '/payload/node'], 'ARGV_ARGV0'),
            ('missing-preload', ['--argv0', 'node', '/payload/node'], 'ARGV_PAYLOAD'),
            ('missing-payload', ['--argv0', 'node', '--preload', runtime], 'ARGV_PAYLOAD')]:
        result = qemu('reject-' + label, bridge, [entry, *args], True, True)
        assert result.returncode == 126 and 'execve(' not in result.stderr, (label, result)
        plain = qemu('diagnostic-' + label, bridge, [entry, *args], nobody=True)
        assert 'DSHA_PROROOT_PRELOAD_ENTRY_UNAVAILABLE stage=' + stage + ' errno=0' in plain.stderr, (label, plain)
        assert '/payload' not in plain.stderr and framework not in plain.stderr, plain.stderr
        failures.append(label)
    for label, preload, extra, stage in [
            ('gcc-outside-root', '/tmp/libgcc_s.so.1', env, 'ARGV_PRELOAD_LIBGCC'),
            ('gcc-different-root', '/different-root/usr/lib/aarch64-linux-gnu/libgcc_s.so.1', env, 'ARGV_PRELOAD_LIBGCC'),
            ('gcc-wrong-name', rootfs + '/usr/lib/aarch64-linux-gnu/other.so', env, 'ARGV_PRELOAD_LIBGCC'),
            ('gcc-env-missing', gcc, [], 'ENV_ROOTFS_MISSING'),
            ('gcc-env-invalid', gcc, ['PROROOT_ROOTFS=/tmp/../root'], 'ENV_ROOTFS_SHAPE')]:
        args = ['--argv0', 'node', '--preload', runtime, '--preload', preload, '/payload/node']
        result = qemu('reject-' + label, bridge, [entry, *args], nobody=True, extra_env=extra)
        assert result.returncode == 126 and 'stage=' + stage + ' errno=0' in result.stderr, (label, result)
        failures.append(label)
    result = qemu('reject-gcc-third-preload', bridge,
        [entry, '--argv0', 'node', '--preload', runtime, '--preload', gcc, '--preload', gcc, '/payload/node'], nobody=True, extra_env=env)
    assert result.returncode == 126 and 'stage=ARGV_PRELOAD_COUNT errno=0' in result.stderr, result
    failures.append('gcc-third-preload')
    for label, operation in [('writable-shim', ('chmod', '777')), ('owned-shim', ('chown', '65534:65534'))]:
        wsl(*operation, framework + '/' + SHIM, check=True)
        result = qemu('reject-' + label, bridge, [entry, *original], nobody=True)
        assert result.returncode == 126 and 'stage=NATIVE_WRITABLE_OR_TYPE errno=0' in result.stderr, result
        wsl('chown', '0:0', framework + '/' + SHIM, check=True)
        wsl('chmod', '555', framework + '/' + SHIM, check=True)
        failures.append(label)
    wsl('mv', framework + '/' + SHIM, framework + '/' + SHIM + '.saved', check=True)
    wsl('ln', '-s', SHIM + '.saved', framework + '/' + SHIM, check=True)
    result = qemu('reject-link-shim', bridge, [entry, *original], nobody=True)
    assert result.returncode == 126 and 'stage=SHIM_OPEN errno=40' in result.stderr, result
    failures.append('link-shim')
    result = qemu('reject-nonframework-entry', work + '/libproroot-bridge.so', [work + '/' + ENTRY, *original])
    assert result.returncode == 126 and 'stage=NATIVE_SCOPE errno=0' in result.stderr, result
    failures.append('nonframework-entry')
    report = dict(originalArm64LoaderExecuted=True, repeatedDirectPreloadExit=0,
        dependencyOnlyExit=dependent.returncode, actualArm64ShimSysconfAndErrnoExit=0,
        actualArm64LoaderWithOptionalLibgccExit=0, optionalLibgccExactExecArgvPreserved=True,
        originalArm64BridgeMappedStaticEntry=True, currentPcFileMappingAndInodeVerified=True,
        nativeFilesReadOnlyToGuestUid=True, exactExecArgvPreserved=True,
        entryRejectCases=failures, kernelSecondExec='ENOEXEC: WSL ARM64 binfmt is absent',
        fullAndroidVendorHookIntegrationVerified=False,
        artifacts={name: hashlib.sha256((NATIVE / name).read_bytes()).hexdigest() for name in (ENTRY, SHIM)})
    (output / 'arm64-result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--wsl-distro', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    main(args.ndk.resolve(), args.wsl_distro, args.output.resolve())
