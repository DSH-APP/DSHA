#!/usr/bin/env python3
"""Real POSIX open/fstat checks using the current Android reader's flag expressions.

Flag NAMES are mapped to the executing host's os constants; arm64 numeric flags
are never passed to x86 syscalls. Set DSHA_NDK_SYSROOT to the locked NDK sysroot
to independently verify both production O_DIRECTORY constants against its
aarch64 header (arm64 040000 differs from x86/asm-generic 00200000).
This tests Linux FIFO and NOFOLLOW behavior, not Android framework execution.
AndroidBackupFileSystemReadTest separately checks production Java ownership and
type/identity rejection with controlled Android facts. No device is accessed.
"""
import errno
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import unittest


SOURCE = (Path(__file__).resolve().parents[1] /
          'app/src/main/java/com/deepseekharness/app/backup/AndroidBackupFileSystem.java')
DEVICE_SOURCE = SOURCE.parents[1] / 'bridge/AndroidDeviceFiles.java'


def read_flags(method):
    source = SOURCE.read_text(encoding='utf-8')
    start = source.index(method)
    end = source.find('\n  @Override', start + len(method))
    block = source[start:end if end >= 0 else len(source)]
    found = re.search(r'\b(?:owned|anchored)\(\s*file,\s*(.*?)\s*,\s*0\s*\)', block, re.S)
    if not found:
        raise AssertionError('Production final-open flags were not found')
    flags = 0
    for term in found[1].split('|'):
        name = term.strip().removeprefix('OsConstants.')
        if not re.fullmatch(r'O_[A-Z_]+', name):
            raise AssertionError('Unsupported production flag expression: ' + name)
        # This is a semantic host mapping, not an assertion of cross-ABI equality.
        flags |= getattr(os, name)
    return flags


class Arm64DirectoryAbiTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('DSHA_NDK_SYSROOT'), 'Locked NDK header path not supplied')
    def test_both_production_directory_flags_match_aarch64_ndk_header(self):
        header = (Path(os.environ['DSHA_NDK_SYSROOT']) /
                  'usr/include/aarch64-linux-android/asm/fcntl.h').read_text(encoding='utf-8')
        value = re.search(r'^#define O_DIRECTORY (0[0-7]+)\s*$', header, re.M)
        self.assertIsNotNone(value)
        expected = int(value[1], 8)
        for source in (SOURCE, DEVICE_SOURCE):
            with self.subTest(source=source.name):
                actual = re.search(r'private static final int O_DIRECTORY = (0[0-7]+);',
                                   source.read_text(encoding='utf-8'))
                self.assertIsNotNone(actual)
                self.assertEqual(int(actual[1], 8), expected)


PROBE = r'''
import errno,json,os,stat,sys
path,flags=sys.argv[1],int(sys.argv[2])
print('OPEN_ENTERED',flush=True)
before=len(os.listdir('/proc/self/fd'))
result={}
for unused in range(200):
    descriptor=None
    try:
        descriptor=os.open(path,flags)
        info=os.fstat(descriptor)
        result={'regular':stat.S_ISREG(info.st_mode),'directory':stat.S_ISDIR(info.st_mode),'fifo':stat.S_ISFIFO(info.st_mode)}
    except OSError as error:
        result={'errno':error.errno}
    finally:
        if descriptor is not None: os.close(descriptor)
result['fdBefore']=before
result['fdAfter']=len(os.listdir('/proc/self/fd'))
print(json.dumps(result),flush=True)
'''


@unittest.skipIf(os.name == 'nt', 'Actual Linux FIFO open and NOFOLLOW semantics')
class BackupReadNonblockTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='dsha-backup-read-')
        self.root = Path(self.temporary.name)
        self.flags = {
            'read': read_flags('public InputStream read(File file, Node expected)'),
            'snapshot': read_flags('public byte[] currentSnapshot(File file, int limit)'),
            'list': read_flags('public List<String> list(File file)'),
            'sync': read_flags('public void syncDirectory(File file)'),
        }

    def tearDown(self):
        self.temporary.cleanup()

    def probe(self, path, flags, *, blocks=False):
        # 写成脚本文件再执行：proot 下超长 `-c` 参数可能被截断，探针代码必须逐字节到达子进程。
        script = self.root.parent / (self.root.name + '-probe.py')
        if not script.exists():
            script.write_text(PROBE, encoding='utf-8')
            self.addCleanup(script.unlink, missing_ok=True)
        process = subprocess.Popen([sys.executable, str(script), str(path), str(flags)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            if blocks:
                with self.assertRaises(subprocess.TimeoutExpired):
                    process.communicate(timeout=.3)
                process.kill()
                output, errors = process.communicate(timeout=5)
                self.assertIn('OPEN_ENTERED', output, errors)
                return
            output, errors = process.communicate(timeout=5)
            self.assertEqual(process.returncode, 0, output + errors)
            result = json.loads(output.splitlines()[-1])
            self.assertEqual(result['fdBefore'], result['fdAfter'])
            return result
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate(timeout=5)

    def test_blocking_legacy_open_reproduces_fifo_wait_before_fstat(self):
        fifo = self.root / 'legacy-fifo'
        os.mkfifo(fifo, 0o600)
        legacy = self.flags['read'] & ~os.O_NONBLOCK
        self.probe(fifo, legacy, blocks=True)

    def test_stat_then_fifo_replacement_is_quickly_observable_without_descriptor_leaks(self):
        for method in ('read', 'snapshot'):
            with self.subTest(method=method):
                path = self.root / method
                path.write_bytes(b'synthetic original')
                self.assertTrue(stat.S_ISREG(path.lstat().st_mode))
                path.unlink()
                os.mkfifo(path, 0o600)
                result = self.probe(path, self.flags[method])
                self.assertTrue(result['fifo'])
                self.assertFalse(result['regular'])
                self.assertTrue(stat.S_ISFIFO(path.lstat().st_mode))

    def test_directory_replaced_by_fifo_fails_without_waiting_or_descriptor_leaks(self):
        path = self.root / 'directory'
        path.mkdir()
        self.assertTrue(stat.S_ISDIR(path.lstat().st_mode))
        path.rmdir()
        os.mkfifo(path, 0o600)
        for method in ('list', 'sync'):
            result = self.probe(path, self.flags[method])
            self.assertEqual(result['errno'], errno.ENOTDIR)
        self.assertTrue(stat.S_ISFIFO(path.lstat().st_mode))

    def test_regular_file_exact_bytes_and_metadata_survive_nonblocking_read(self):
        path = self.root / 'payload'
        payload = bytes(range(256)) * 33
        path.write_bytes(payload)
        before = path.stat()
        for method in ('read', 'snapshot'):
            with self.subTest(method=method):
                descriptor = os.open(path, self.flags[method])
                try:
                    self.assertTrue(stat.S_ISREG(os.fstat(descriptor).st_mode))
                    self.assertEqual(os.read(descriptor, len(payload) + 1), payload)
                    self.assertEqual(os.read(descriptor, 1), b'')
                finally:
                    os.close(descriptor)
                self.assertTrue(self.probe(path, self.flags[method])['regular'])
        after = path.stat()
        self.assertEqual((before.st_ino, before.st_mode, before.st_size, before.st_mtime_ns),
                         (after.st_ino, after.st_mode, after.st_size, after.st_mtime_ns))
        self.assertEqual(hashlib.sha256(path.read_bytes()).digest(), hashlib.sha256(payload).digest())

    def test_normal_directory_membership_is_unchanged(self):
        directory = self.root / 'normal'
        directory.mkdir()
        (directory / 'sentinel').write_bytes(b'unchanged')
        descriptor = os.open(directory, self.flags['list'])
        try:
            self.assertTrue(stat.S_ISDIR(os.fstat(descriptor).st_mode))
            self.assertEqual(os.listdir(descriptor), ['sentinel'])
        finally:
            os.close(descriptor)
        self.assertTrue(self.probe(directory, self.flags['list'])['directory'])
        self.assertEqual((directory / 'sentinel').read_bytes(), b'unchanged')

    def test_normal_directory_can_still_fsync_without_changing_membership(self):
        directory = self.root / 'sync'
        directory.mkdir()
        (directory / 'sentinel').write_bytes(b'unchanged')
        descriptor = os.open(directory, self.flags['sync'])
        try:
            self.assertTrue(stat.S_ISDIR(os.fstat(descriptor).st_mode))
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        self.assertTrue(self.probe(directory, self.flags['sync'])['directory'])
        self.assertEqual(list(directory.iterdir()), [directory / 'sentinel'])
        self.assertEqual((directory / 'sentinel').read_bytes(), b'unchanged')

    def test_leaf_symlinks_never_open_their_targets(self):
        outside = self.root / 'external'
        outside.write_bytes(b'protected synthetic sentinel')
        link = self.root / 'link'
        link.symlink_to(outside)
        for flags in self.flags.values():
            result = self.probe(link, flags)
            self.assertIn(result['errno'], (errno.ELOOP, errno.ENOTDIR))
        self.assertTrue(link.is_symlink())
        self.assertEqual(outside.read_bytes(), b'protected synthetic sentinel')


if __name__ == '__main__':
    unittest.main(verbosity=2)
