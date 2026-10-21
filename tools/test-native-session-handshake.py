#!/usr/bin/env python3
"""Linux host behavior of the signed session leader before its guest launch handshake."""
import os
import argparse
import hashlib
import json
from pathlib import Path
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() if hasattr(hashlib, 'file_digest') else hashlib.sha256(stream.read()).hexdigest()


def tree_digest(directory):
    """Bind every prepared header/library byte and link before and after compilation."""
    entries = {}
    for folder, directories, files in os.walk(directory, followlinks=False):
        for name in sorted(directories + files):
            path = Path(folder) / name
            key = path.relative_to(directory).as_posix()
            before = path.lstat()
            if path.is_symlink():
                if name in directories:
                    directories.remove(name)
                entries[key] = {'type': 'LINK', 'target': os.readlink(path)}
            elif path.is_dir():
                entries[key] = {'type': 'DIRECTORY'}
            elif path.is_file():
                value = digest(path)
                after = path.lstat()
                if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
                    raise ValueError('NATIVE_SYSROOT_FILE_CHANGED')
                entries[key] = {'type': 'FILE', 'bytes': before.st_size, 'sha256': value}
            else:
                raise ValueError('NATIVE_SYSROOT_SPECIAL_FILE')
    return hashlib.sha256(json.dumps(entries, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def windows_linux_probe(args):
    """Compile current source with the verified prepared NDK/sysroot, then probe real WSL /proc.

    No downloads, distro repair, mount changes, Android device or simulated platform are used.
    The prepared inputs are an explicit dependency, not a prior behavior PASS.
    """
    if not args.linux_host or not args.native_inputs or not args.linux_tempdir:
        raise ValueError('ENVIRONMENT_GAP: pass --linux-host, --native-inputs and --linux-tempdir')
    from pathlib import PurePosixPath
    temporary_base = PurePosixPath(args.linux_tempdir)
    if not temporary_base.is_absolute() or '..' in temporary_base.parts:
        raise ValueError('NATIVE_LINUX_TMPDIR_INVALID')
    inputs = Path(args.native_inputs).resolve(strict=True)
    host = Path(args.linux_host).resolve(strict=True)
    source = ROOT / 'tools/native-session/session-launcher.c'
    harness = ROOT / 'tools/native-session/handshake-probe.mjs'
    receipt_path = inputs / 'compile-receipt.json'
    prepared = json.loads(receipt_path.read_text(encoding='utf-8'))
    if prepared.get('schema') != 1 or prepared['source']['sha256'] != digest(source):
        raise ValueError('NATIVE_PREPARED_SOURCE_CHANGED')
    if digest(inputs / 'session-host') != prepared['binary']['sha256']:
        raise ValueError('NATIVE_PREPARED_BINARY_CHANGED')
    if digest(inputs / 'Packages.xz') != prepared['officialIndex']['sha256']:
        raise ValueError('NATIVE_PREPARED_PACKAGE_INDEX_CHANGED')
    if {entry['name'] for entry in prepared['packages']} != {'libc6', 'libc6-dev', 'linux-libc-dev'}:
        raise ValueError('NATIVE_PREPARED_PACKAGE_SCOPE')
    for package in prepared['packages']:
        archive = inputs / package['url'].rsplit('/', 1)[1]
        if not package['url'].startswith('https://archive.ubuntu.com/ubuntu/pool/') or archive.stat().st_size != package['bytes'] or digest(archive) != package['sha256']:
            raise ValueError('NATIVE_PREPARED_PACKAGE_CHANGED')
    compiler = Path(prepared['compiler']['path']).resolve(strict=True)
    linker = compiler.with_name('ld.lld.exe')
    if digest(compiler) != prepared['compiler']['sha256']:
        raise ValueError('NATIVE_PREPARED_COMPILER_CHANGED')
    node = host / 'node'
    if not node.is_file() or not linker.is_file():
        raise ValueError('ENVIRONMENT_GAP: prepared Linux Node and NDK linker are required')
    sysroot = inputs / 'sysroot'
    before = tree_digest(sysroot)
    source_hash, harness_hash, prepared_hash = digest(source), digest(harness), digest(receipt_path)
    def lx(path):
        value = Path(path).resolve()
        return '/mnt/host/' + value.drive[0].lower() + value.as_posix().split(':', 1)[1]
    work = Path(args.work_directory).resolve() if args.work_directory else ROOT / 'app/build'
    with tempfile.TemporaryDirectory(prefix='native-handshake-', dir=work) as temporary:
        output = Path(temporary)
        object_file, binary = output / 'session.o', output / 'session-host'
        commands = [
            [str(compiler), '--target=x86_64-linux-gnu', '--sysroot=' + str(sysroot),
             '-isystem', str(sysroot / 'usr/include/x86_64-linux-gnu'), '-std=c11',
             '-D_GNU_SOURCE', '-O2', '-fPIE', '-c', str(source), '-o', str(object_file)],
            [str(linker), '--sysroot=' + str(sysroot), '-m', 'elf_x86_64', '-pie',
             '-dynamic-linker', lx(sysroot / 'lib/x86_64-linux-gnu/ld-linux-x86-64.so.2'),
             '-L' + str(sysroot / 'usr/lib/x86_64-linux-gnu'), '-L' + str(sysroot / 'lib/x86_64-linux-gnu'),
             str(sysroot / 'usr/lib/x86_64-linux-gnu/Scrt1.o'), str(sysroot / 'usr/lib/x86_64-linux-gnu/crti.o'),
             str(object_file), '-lc', str(sysroot / 'usr/lib/x86_64-linux-gnu/crtn.o'), '-o', str(binary)]]
        checks = []
        for command in commands:
            result = subprocess.run(command, capture_output=True, timeout=30)
            checks.append({'argv': command, 'exitCode': result.returncode,
                           'logSha256': hashlib.sha256(result.stdout + result.stderr).hexdigest()})
            if result.returncode:
                sys.stderr.buffer.write(result.stdout + result.stderr)
                raise ValueError('NATIVE_CURRENT_SOURCE_COMPILE_FAILED')
        binary_hash = digest(binary)
        libraries = ':'.join(lx(sysroot / relative) for relative in ('lib/x86_64-linux-gnu', 'usr/lib/x86_64-linux-gnu'))
        command = ['wsl.exe', '-d', args.wsl_distro, '--', 'env', 'LD_LIBRARY_PATH=' + lx(host),
                   'TMPDIR=' + str(temporary_base), lx(node), lx(harness), lx(binary), lx(source),
                   binary_hash, source_hash, libraries]
        result = subprocess.run(command, capture_output=True, timeout=30)
        sys.stdout.buffer.write(result.stdout)
        sys.stderr.buffer.write(result.stderr)
        if result.returncode:
            raise ValueError('NATIVE_REAL_LINUX_HANDSHAKE_FAILED')
        behavior = json.loads(result.stdout.decode('utf-8').strip())
        if behavior.get('status') != 'PASS' or behavior.get('platform') != 'linux' or behavior.get('sourceSha256') != source_hash or behavior.get('binarySha256') != binary_hash or behavior.get('cleanupConfirmed') is not True:
            raise ValueError('NATIVE_REAL_LINUX_RECEIPT_INVALID')
        if before != tree_digest(sysroot) or source_hash != digest(source) or harness_hash != digest(harness) or prepared_hash != digest(receipt_path) or binary_hash != digest(binary):
            raise ValueError('NATIVE_INPUTS_CHANGED_DURING_PROBE')
        print(json.dumps({'status': 'PASS', 'sourceSha256': source_hash, 'harnessSha256': harness_hash,
                          'preparedInputsReceiptSha256': prepared_hash, 'sysrootDigest': before,
                          'compilerSha256': digest(compiler), 'linkerSha256': digest(linker),
                          'linuxNodeSha256': digest(node), 'compileChecks': checks,
                          'behaviorLogSha256': hashlib.sha256(result.stdout + result.stderr).hexdigest(),
                          'scope': 'fresh Linux x86_64 C compilation and real WSL kernel; no Android device claim'}))

class NativeSessionHandshakeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.name != 'posix' or not Path('/proc/self/stat').is_file():
            raise RuntimeError('ENVIRONMENT_GAP: Linux /proc and isolated sessions are required')
        cls.compiler = shutil.which('cc') or shutil.which('gcc') or shutil.which('clang')
        if not cls.compiler:
            raise RuntimeError('ENVIRONMENT_GAP: A host C compiler is required')
        cls.directory = tempfile.TemporaryDirectory(prefix='dsha-session-handshake-')
        cls.binary = Path(cls.directory.name) / 'session'
        subprocess.run([cls.compiler, '-std=c11', '-D_GNU_SOURCE', '-O2', '-fPIE', '-pie',
                        str(ROOT / 'tools/native-session/session-launcher.c'), '-o', str(cls.binary)], check=True)

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, 'directory'): cls.directory.cleanup()

    def test_birth_is_self_stat_and_guest_does_not_start_before_parent_confirmation(self):
        script = 'IFS= read -r start; [ "$start" = DSHA_START ] || exit 125; echo GUEST_STARTED; kill -STOP $$'
        process = subprocess.Popen([str(self.binary), '--birth-handshake', '/bin/sh', '-c', script],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        born = None
        try:
            self.assertTrue(select.select([process.stdout], [], [], 2)[0])
            line = process.stdout.readline().decode('ascii').rstrip('\n')
            self.assertTrue(line.startswith('DSHA_SESSION_STAT_V1 '), line)
            stat = line[len('DSHA_SESSION_STAT_V1 '):]
            pid = int(stat.split(' (', 1)[0])
            values = stat.rsplit(')', 1)[1].split()
            born = values[19]
            self.assertEqual(process.pid, pid)
            self.assertEqual(os.getpid(), int(values[1]))
            self.assertEqual(pid, int(values[2]))
            self.assertEqual(pid, int(values[3]))
            self.assertEqual(born, Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()[19])
            self.assertFalse(select.select([process.stdout], [], [], .1)[0], 'Guest started before DSHA_START')
            process.stdin.write(b'DSHA_START\n'); process.stdin.flush()
            self.assertTrue(select.select([process.stdout], [], [], 2)[0])
            self.assertEqual(b'GUEST_STARTED\n', process.stdout.readline())
        finally:
            if process.poll() is None:
                current = Path(f'/proc/{process.pid}/stat').read_text().rsplit(')', 1)[1].split()
                if born is not None:
                    self.assertEqual(born, current[19])
                    self.assertEqual(process.pid, int(current[3]))
                os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=2)
            for stream in (process.stdin, process.stdout, process.stderr): stream.close()

    def test_missing_command_is_explicit_failure(self):
        result = subprocess.run([str(self.binary), '--birth-handshake'], capture_output=True, timeout=2)
        self.assertEqual(125, result.returncode)
        self.assertIn(b'DSHA_SESSION_USAGE', result.stderr)
        self.assertNotIn(b'DSHA_SESSION_STAT_V1', result.stdout)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--linux-host')
    parser.add_argument('--native-inputs')
    parser.add_argument('--linux-tempdir')
    parser.add_argument('--wsl-distro', default='docker-desktop')
    parser.add_argument('--work-directory')
    args, tests = parser.parse_known_args()
    if sys.flags.optimize:
        parser.error('Assertions must remain enabled')
    if os.name == 'nt':
        windows_linux_probe(args)
    elif sys.platform == 'linux':
        unittest.main(argv=[sys.argv[0], *tests])
    else:
        raise SystemExit('ENVIRONMENT_GAP: Linux /proc or the explicit verified WSL fixture is required')
