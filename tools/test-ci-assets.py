#!/usr/bin/env python3
"""Exercise fixed source input pins and real ZIP pack/restore in private fixtures."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location('ci_assets_under_test', Path(__file__).with_name('ci-assets.py'))
assets = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(assets)


def sha(data):
    return hashlib.sha256(data).hexdigest()


class CiAssetsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='dsha-ci-assets-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.assets = self.root / 'app/src/main/assets'
        self.assets.mkdir(parents=True)
        self.recovery = self.root / 'tools/recovery-runtime'
        (self.recovery / 'archives').mkdir(parents=True)
        self.root_patch = patch.object(assets, 'ROOT', self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.blobs = {name: ('original source:' + name).encode() for name in assets.NAMES}
        for name, data in self.blobs.items():
            (self.assets / name).write_bytes(data)
        self.descriptor = {'inputs': {name: sha(data) for name, data in self.blobs.items()
                                     if not name.endswith('.inputs.json')
                                     and name not in ('python-support.bin', 'adb-wheels.tar.gz')}}
        self.input_lock = {'schema': 1, 'archives': [
            {'asset': 'python-support.bin', 'sha256': sha(self.blobs['python-support.bin']),
             'bytes': len(self.blobs['python-support.bin'])}]}
        self.adb_lock = {'schema': 1, 'archiveSha256': sha(self.blobs['adb-wheels.tar.gz'])}
        self.recovery_blobs = {'recovery-rootfs.bin': b'pinned recovery rootfs',
                               'recovery-dsh-runtime.bin': b'pinned recovery dsh'}
        self.recovery_lock = {'schema': 1, 'archives': [
            {'asset': name, 'sha256': sha(data)} for name, data in self.recovery_blobs.items()]}
        for name, data in self.recovery_blobs.items():
            (self.recovery / 'archives' / name).write_bytes(data)
        self.save_locks()

    def save_locks(self):
        (self.assets / 'runtime-descriptor.json').write_text(json.dumps(self.descriptor), encoding='utf-8')
        (self.root / 'tools/ci-build-inputs.lock.json').write_text(json.dumps(self.input_lock), encoding='utf-8')
        (self.assets / 'adb-wheels.lock.json').write_text(json.dumps(self.adb_lock), encoding='utf-8')
        (self.recovery / 'lock.json').write_text(json.dumps(self.recovery_lock), encoding='utf-8')

    def pack(self, target=None):
        target = target or self.root / 'verified-inputs.zip'
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            assets.main(['--pack', str(target)])
        return target, json.loads(output.getvalue())

    def test_exact_inputs_verify_and_normal_pack_keeps_original_bytes(self):
        assets.verify_sources()
        package, receipt = self.pack()
        self.assertEqual(receipt['sha256'], sha(package.read_bytes()))
        self.assertEqual(receipt['bytes'], package.stat().st_size)
        with zipfile.ZipFile(package) as archive:
            self.assertEqual(set(archive.namelist()), assets.allowed())
            self.assertEqual(len(archive.namelist()), len(assets.allowed()))
            for name, data in self.blobs.items():
                self.assertEqual(archive.read('app/src/main/assets/' + name), data)
            for name, data in self.recovery_blobs.items():
                self.assertEqual(archive.read('tools/recovery-runtime/archives/' + name), data)
        self.assertNotIn('tools/ci-build-inputs.lock.json', assets.allowed(), 'source lock is already in the source checkout')

    def test_bad_python_digest_and_size_are_rejected(self):
        original = self.blobs['python-support.bin']
        (self.assets / 'python-support.bin').write_bytes(b'X' + original[1:])
        with self.assertRaisesRegex(ValueError, 'CI_ASSET_SOURCE_MISMATCH: python-support.bin'):
            assets.verify_sources()
        (self.assets / 'python-support.bin').write_bytes(original)
        self.input_lock['archives'][0]['bytes'] += 1
        self.save_locks()
        with self.assertRaisesRegex(ValueError, 'CI_ASSET_SOURCE_MISMATCH: python-support.bin'):
            assets.verify_sources()

    def test_bad_adb_cannot_use_modified_descriptor_as_fallback(self):
        changed = b'changed wheels'
        (self.assets / 'adb-wheels.tar.gz').write_bytes(changed)
        self.descriptor['inputs']['adb-wheels.tar.gz'] = sha(changed)
        self.save_locks()
        with self.assertRaisesRegex(ValueError, 'CI_ASSET_PIN_CONFLICT: adb-wheels.tar.gz'):
            assets.verify_sources()
        self.descriptor['inputs'].pop('adb-wheels.tar.gz')
        self.save_locks()
        with self.assertRaisesRegex(ValueError, 'CI_ASSET_SOURCE_MISMATCH: adb-wheels.tar.gz'):
            assets.verify_sources()

    def test_missing_separate_locks_do_not_trust_local_bytes(self):
        for relative, error in [('tools/ci-build-inputs.lock.json', 'CI_BUILD_INPUT_LOCK_MISSING'),
                                ('app/src/main/assets/adb-wheels.lock.json', 'CI_ADB_WHEELS_LOCK_MISSING')]:
            with self.subTest(relative=relative):
                path = self.root / relative
                original = path.read_bytes()
                path.unlink()
                with self.assertRaisesRegex(ValueError, error):
                    assets.verify_sources()
                path.write_bytes(original)

    def test_invalid_pin_shapes_fail_without_using_local_digest(self):
        python_path = self.root / 'tools/ci-build-inputs.lock.json'
        adb_path = self.assets / 'adb-wheels.lock.json'
        for invalid in ([], {'schema': 1, 'archives': [{'asset': 'python-support.bin', 'sha256': 123, 'bytes': 1}]}):
            with self.subTest(python=invalid):
                python_path.write_text(json.dumps(invalid), encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'CI_BUILD_INPUT_LOCK_INVALID'):
                    assets.verify_sources()
        self.save_locks()
        for invalid in ([], {'schema': 1, 'archiveSha256': 'not a fixed sha256'}):
            with self.subTest(adb=invalid):
                adb_path.write_text(json.dumps(invalid), encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'CI_ADB_WHEELS_LOCK_INVALID'):
                    assets.verify_sources()

    def test_unknown_archive_cannot_borrow_separate_pin(self):
        self.input_lock['archives'].append({'asset': 'unknown.bin', 'sha256': sha(b'unknown'), 'bytes': 7})
        self.save_locks()
        with self.assertRaisesRegex(ValueError, 'CI_BUILD_INPUT_LOCK_INVALID'):
            assets.verify_sources()
        self.input_lock['archives'].pop()
        self.save_locks()
        (self.assets / 'unknown.bin').write_bytes(b'unknown')
        with patch.object(assets, 'NAMES', assets.NAMES + ['unknown.bin']):
            with self.assertRaisesRegex(ValueError, 'CI_ASSET_SOURCE_MISMATCH: unknown.bin'):
                assets.verify_sources()

    def test_missing_normal_descriptor_pin_has_no_fallback(self):
        self.descriptor['inputs'].pop('pnpm-runtime.bin')
        self.save_locks()
        with self.assertRaisesRegex(ValueError, 'CI_ASSET_SOURCE_MISMATCH: pnpm-runtime.bin'):
            assets.verify_sources()

    def test_recovery_still_requires_its_original_lock(self):
        (self.recovery / 'archives/recovery-rootfs.bin').write_bytes(b'other recovery')
        with self.assertRaisesRegex(ValueError, 'CI_RECOVERY_ARCHIVE_MISMATCH'):
            assets.verify_sources()

    def test_existing_pack_output_is_preserved(self):
        target = self.root / 'already-present.zip'
        target.write_bytes(b'original output remains')
        before = target.stat()
        with self.assertRaises(FileExistsError):
            self.pack(target)
        self.assertEqual(target.read_bytes(), b'original output remains')
        self.assertEqual(target.stat().st_mtime_ns, before.st_mtime_ns)

    def test_pack_then_restore_fixture_verifies_the_same_fixed_inputs(self):
        package, receipt = self.pack()
        binary_paths = [self.assets / name for name in self.blobs]
        binary_paths += [self.recovery / 'archives' / name for name in self.recovery_blobs]
        for path in binary_paths:
            path.unlink()
        output = io.StringIO()
        with patch.object(assets.urllib.request, 'urlopen', return_value=io.BytesIO(package.read_bytes())) as download:
            with contextlib.redirect_stdout(output):
                assets.main(['--url', 'https://example.invalid/fixed-inputs.zip', '--sha256', receipt['sha256']])
        download.assert_called_once_with('https://example.invalid/fixed-inputs.zip', timeout=60)
        self.assertIn('PASS locked CI asset inputs', output.getvalue())
        assets.verify_sources()
        for name, data in self.blobs.items():
            self.assertEqual((self.assets / name).read_bytes(), data)

    def test_wrong_download_sha_never_publishes_inputs(self):
        package, _ = self.pack()
        target = self.assets / 'python-support.bin'
        target.unlink()
        with patch.object(assets.urllib.request, 'urlopen', return_value=io.BytesIO(package.read_bytes())):
            with self.assertRaisesRegex(ValueError, 'CI_ASSET_BUNDLE_SHA256'):
                assets.main(['--url', 'https://example.invalid/fixed-inputs.zip', '--sha256', '0' * 64])
        self.assertFalse(target.exists())


if __name__ == '__main__':
    unittest.main()
