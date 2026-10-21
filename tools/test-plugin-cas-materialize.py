#!/usr/bin/env python3
"""Native old-pnpm file-link migration: exact bytes, no scripts, private roots."""
import contextlib
import ctypes
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'


@unittest.skipIf(os.name == 'nt', 'Actual Linux NOFOLLOW, symlinks and renameat2')
class CasMaterializeTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix='dsha-cas-materialize-'))
        self.home = self.root / 'root/.dsh'
        self.profile = self.home / 'profiles/web'
        self.modules = self.profile / 'node_modules'
        self.package = self.modules / 'test-plugin'
        self.package.mkdir(parents=True)
        self.store = self.root / 'root/.local/share/pnpm/store/v10/files'
        self.store.mkdir(parents=True)
        (self.profile / 'package.json').write_text('{"private":true,"dependencies":{"test-plugin":"1.0.0"},"dsh":{"profile":{"bundles":["test-plugin"]}}}')
        (self.profile / 'pnpm-lock.yaml').write_text('owned exact lock bytes\n')
        (self.package / 'package.json').write_text('{"name":"test-plugin","version":"1.0.0"}')
        self.meta = [(p, p.read_bytes()) for p in [self.profile / 'package.json', self.profile / 'pnpm-lock.yaml', self.package / 'package.json']]
        self.environment = patch.dict(os.environ, {'DSHA_TEST_ROOT': str(self.root), 'DSH_HOME': '/root/.dsh'})
        self.environment.start()
        spec = importlib.util.spec_from_file_location('cas_fixture_manager', ASSETS / 'plugin-manager.py')
        self.manager = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.manager)
        self.transaction = self.manager.transactions()
        self.link, self.target = self.make_link(self.package / 'index.js', b'export const answer=42;\n', executable=True)

    def tearDown(self):
        self.environment.stop()
        shutil.rmtree(self.root)

    def make_link(self, path, content, executable=False):
        digest = hashlib.sha512(content).hexdigest()
        target = self.store / digest[:2] / (digest[2:] + ('-exec' if executable else ''))
        target.parent.mkdir(exist_ok=True)
        target.write_bytes(content); target.chmod(0o555 if executable else 0o444)
        path.parent.mkdir(parents=True, exist_ok=True); path.symlink_to(target)
        return path, target

    def cli(self, action='materialize-cas-links'):
        result = subprocess.run([sys.executable, str(ASSETS / 'plugin-manager.py'), action], env=os.environ, capture_output=True, text=True, timeout=30)
        lines = [line[len('PLUGIN_RESULT: '):] for line in result.stdout.splitlines() if line.startswith('PLUGIN_RESULT: ')]
        self.assertEqual(len(lines), 1, result.stdout + result.stderr)
        return result, json.loads(lines[0])

    def test_cli_exact_bytes_metadata_other_profile_and_store_preserved_noop(self):
        other = self.home / 'profiles/other'; other.mkdir()
        (other / 'package.json').write_text('other profile unchanged')
        target_stat, target_data = self.target.stat(), self.target.read_bytes()
        result, value = self.cli()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((value['status'], value['algorithm'], value['profile'], value['storeReadOnly']), ('ok', 'DSHA_PNPM_CAS_COPY_V1', 'web', True))
        self.assertEqual(value['migratedFiles'], 1); self.assertTrue(value['changed'])
        self.assertFalse(self.link.is_symlink()); self.assertEqual(self.link.read_bytes(), target_data)
        self.assertEqual(self.target.stat().st_ino, target_stat.st_ino); self.assertEqual(self.target.read_bytes(), target_data)
        work = self.home / 'plugin-install-operations/completed' / value['operationId']
        self.assertTrue((work / 'before-0').is_symlink()); self.assertEqual(os.readlink(work / 'before-0'), str(self.target))
        for path, data in self.meta:
            self.assertEqual(path.read_bytes(), data)
        self.assertEqual((other / 'package.json').read_text(), 'other profile unchanged')
        again, noop = self.cli(); self.assertEqual(again.returncode, 0)
        self.assertEqual((noop['changed'], noop['migratedFiles'], noop['operationId']), (False, 0, ''))

    def test_directory_and_unrelated_links_never_followed(self):
        outside = self.root / 'outside'; outside.mkdir(); (outside / 'keep').write_text('external original')
        (self.package / 'external-directory').symlink_to(outside, target_is_directory=True)
        (self.package / 'external-file').symlink_to(outside / 'keep')
        (self.package / 'cycle').symlink_to('cycle')
        result, value = self.cli(); self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(value['migratedFiles'], 1)
        self.assertEqual((outside / 'keep').read_text(), 'external original')
        for name in ['external-directory', 'external-file', 'cycle']:
            self.assertTrue((self.package / name).is_symlink())

    def test_large_profile_uses_bounded_native_readable_plans(self):
        for index in range(512):
            (self.package / ('part-' + str(index))).symlink_to(self.target)
        result, value = self.cli()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(value['migratedFiles'], 513)
        history = self.home / 'plugin-install-operations/completed'
        plans = [json.loads(path.read_text()) for path in history.glob('*/plan.json')]
        self.assertEqual(sorted(len(plan['entries']) for plan in plans), [1, 512])
        self.assertIn(value['operationId'], [plan['id'] for plan in plans])
        def fields(value):
            if isinstance(value, dict):
                return len(value) + sum(fields(child) for child in value.values())
            if isinstance(value, list):
                return sum(fields(child) for child in value)
            return 0
        for path in history.glob('*/plan.json'):
            self.assertLessEqual(path.stat().st_size, 2 * 1024 * 1024)
            self.assertLess(fields(json.loads(path.read_text())), 100000)
        self.assertEqual(len(list(history.glob('*/before-*'))), 513)
        self.assertTrue(all(not path.is_symlink() for path in self.package.iterdir()))

    def test_native_disabled_markers_are_never_materialized(self):
        marker, target = self.make_link(self.modules / 'test-plugin.disabled', b'DSHA_SAFE_MODE\n')
        before = marker.lstat().st_ino
        result, value = self.cli(); self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(value['migratedFiles'], 1)
        self.assertTrue(marker.is_symlink()); self.assertEqual(marker.lstat().st_ino, before)
        self.assertEqual(os.readlink(marker), str(target)); self.assertEqual(target.read_bytes(), b'DSHA_SAFE_MODE\n')

    def test_hash_mismatch_store_leaf_symlink_and_special_file_refuse_before_publish(self):
        self.target.chmod(0o644); self.target.write_bytes(b'wrong content')
        result, value = self.cli(); self.assertEqual(result.returncode, 1); self.assertEqual(value['status'], 'error')
        self.assertTrue(self.link.is_symlink())
        self.target.unlink(); outside = self.root / 'outside-file'; outside.write_bytes(b'not read by CAS')
        self.target.symlink_to(outside)
        result, value = self.cli(); self.assertEqual(result.returncode, 1); self.assertEqual(value['status'], 'error')
        self.assertTrue(self.link.is_symlink()); self.assertEqual(outside.read_bytes(), b'not read by CAS')
        self.target.unlink(); self.target.write_bytes(b'export const answer=42;\n')
        os.mkfifo(self.package / 'special')
        result, value = self.cli(); self.assertEqual(result.returncode, 1); self.assertEqual(value['status'], 'error')
        self.assertTrue(self.link.is_symlink())

    def test_store_parent_and_module_parent_links_rejected(self):
        bucket = self.target.parent
        bucket.rename(bucket.with_name(bucket.name + '-old'))
        outside = self.root / 'outside'; outside.mkdir()
        bucket.symlink_to(outside, target_is_directory=True)
        result, value = self.cli(); self.assertEqual(result.returncode, 1); self.assertEqual(value['status'], 'error')
        self.assertTrue(self.link.is_symlink())
        bucket.unlink(); bucket.with_name(bucket.name + '-old').rename(bucket)
        self.modules.rename(self.profile / 'old-modules')
        self.modules.symlink_to(outside, target_is_directory=True)
        result, value = self.cli(); self.assertEqual(result.returncode, 1); self.assertEqual(value['status'], 'error')
        self.assertEqual(list(outside.iterdir()), [])

    def kill_boundary(self, boundary):
        marker = self.root / 'plugin-boundary'
        env = dict(os.environ, DSHA_PLUGIN_TEST_BOUNDARY=boundary)
        log = self.root / ('child-' + boundary + '.log')
        with log.open('wb') as stream:
            child = subprocess.Popen([sys.executable, str(ASSETS / 'plugin-manager.py'), 'materialize-cas-links'], env=env, stdout=stream, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 20
                while not marker.exists() and child.poll() is None and time.monotonic() < deadline:
                    time.sleep(.02)
                self.assertTrue(marker.exists(), log.read_text(errors='replace'))
            finally:
                child.kill(); child.wait(timeout=5)

    def test_crash_states_block_automatic_retry_and_explicit_rollback_restores_links(self):
        self.kill_boundary('cas-new-0')
        result, value = self.cli(); self.assertEqual(result.returncode, 1); self.assertEqual(value['status'], 'pending')
        self.assertFalse(value['automaticRetry']); self.assertFalse(self.link.is_symlink())
        self.transaction.recover_all(); self.transaction.recover_all()
        self.assertTrue(self.link.is_symlink()); self.assertEqual(os.readlink(self.link), str(self.target))
        self.assertEqual(self.target.read_bytes(), b'export const answer=42;\n')

    def test_missing_during_publish_explicit_recovery_and_committed_no_replay(self):
        self.kill_boundary('cas-old-0')
        self.assertFalse(self.link.exists()); self.assertFalse(self.link.is_symlink())
        self.transaction.recover_all(); self.assertTrue(self.link.is_symlink())
        (self.root / 'plugin-boundary').unlink()
        self.kill_boundary('cas-committed')
        self.assertFalse(self.link.is_symlink())
        self.transaction.recover_all(); self.assertFalse(self.link.is_symlink())
        result, value = self.cli(); self.assertEqual(result.returncode, 0); self.assertFalse(value['changed'])

    def test_later_modified_copy_and_unknown_algorithm_are_not_overwritten(self):
        self.kill_boundary('cas-new-0')
        self.link.write_bytes(b'later user changes')
        with self.assertRaisesRegex(ValueError, '变化'):
            self.transaction.recover_all()
        self.assertEqual(self.link.read_bytes(), b'later user changes')
        work = Path(self.transaction.pending()[0]); plan = json.loads((work / 'plan.json').read_text())
        plan['casFingerprint'] = 'unknown'; (work / 'plan.json').write_text(json.dumps(plan))
        with self.assertRaisesRegex(ValueError, '算法'):
            self.transaction.recover_all()
        self.assertEqual(self.link.read_bytes(), b'later user changes')

    def test_cancel_after_old_link_claim_propagates_and_keeps_pending(self):
        original = self.transaction.boundary
        def boundary(name):
            if name == 'cas-old-0':
                raise self.manager.PluginCancelled('owned cancellation')
            return original(name)
        self.transaction.boundary = boundary
        with self.assertRaisesRegex(self.manager.PluginCancelled, 'owned cancellation'):
            self.transaction.materialize_cas_links()
        self.assertTrue(self.transaction.pending()); self.assertFalse(self.link.exists())
        self.transaction.boundary = original
        self.transaction.recover_all(); self.assertTrue(self.link.is_symlink())

    def test_prepared_link_replacement_and_recovery_escape_refused(self):
        original = self.transaction.boundary
        def boundary(name):
            if name == 'cas-prepared':
                self.link.unlink(); self.link.symlink_to(self.target)
            return original(name)
        self.transaction.boundary = boundary
        with self.assertRaisesRegex(ValueError, '变化'):
            self.transaction.materialize_cas_links()
        self.assertTrue(self.link.is_symlink())
        work = Path(self.transaction.pending()[0]); plan = json.loads((work / 'plan.json').read_text())
        plan['entries'][0]['path'] = '../outside'
        with self.assertRaisesRegex(ValueError, '路径无效'):
            self.transaction.recover_cas_links(str(work), plan)

    def test_link_swap_inside_rename_is_retained_and_cannot_reuse_held_inode(self):
        real_library = ctypes.CDLL(None, use_errno=True)
        real_rename = real_library.renameat2
        real_rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        real_rename.restype = ctypes.c_int
        old_inode = self.link.lstat().st_ino
        replacement = []
        class Library:
            pass
        def rename(*args):
            if not replacement:
                self.link.unlink(); self.link.symlink_to(self.target)
                replacement.append(self.link.lstat().st_ino)
            return real_rename(*args)
        library = Library(); library.renameat2 = rename
        with patch.object(ctypes, 'CDLL', return_value=library):
            with self.assertRaisesRegex(ValueError, '移动期间变化'):
                self.transaction.materialize_cas_links()
        self.assertNotEqual(replacement[0], old_inode)
        work = Path(self.transaction.pending()[0])
        self.assertEqual((work / 'before-0').lstat().st_ino, replacement[0])
        with self.assertRaisesRegex(ValueError, '中断后变化'):
            self.transaction.recover_all()
        self.assertTrue((work / 'before-0').is_symlink())
        self.assertEqual(self.target.read_bytes(), b'export const answer=42;\n')


if __name__ == '__main__':
    unittest.main(verbosity=2)
