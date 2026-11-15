#!/usr/bin/env python3
"""Deletion-only object fingerprints and real native CLI; private fixtures only."""
import contextlib
import ctypes
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'


class DeleteNoFollowTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix='dsha-delete-nofollow-'))
        self.home = self.root / 'root/.dsh'
        self.profile = self.home / 'profiles/web'
        self.package = self.profile / 'node_modules/test-plugin'
        self.package.mkdir(parents=True)
        (self.package / 'package.json').write_text(json.dumps({'name': 'test-plugin', 'version': '1.0.0', 'dsh': {'bundle': {'patch': 'cordis.patch.yml'}}}))
        (self.package / 'cordis.patch.yml').write_text('[]\n')
        (self.package / 'index.js').write_text('original owned bytes')
        (self.profile / 'package.json').write_text(json.dumps({'dependencies': {'test-plugin': '1.0.0'}, 'dsh': {'profile': {'bundles': ['test-plugin']}}}))
        self.environment = patch.dict(os.environ, {'DSHA_TEST_ROOT': str(self.root), 'DSH_HOME': '/root/.dsh'})
        self.environment.start()
        spec = importlib.util.spec_from_file_location('delete_nofollow_manager', ASSETS / 'plugin-manager.py')
        self.manager = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.manager)
        self.transaction = self.manager.transactions()
        globals_ = self.transaction.object_state.__globals__
        self.objects = globals_['DeleteObjects']
        self.algorithm = globals_['DELETE_FINGERPRINT']
        self.store = self.root / 'shared-store'; self.store.mkdir()
        (self.store / 'object').write_text('shared immutable original')

    def tearDown(self):
        self.environment.stop()
        shutil.rmtree(self.root)

    def prepare(self, legacy=False):
        self.transaction.directory = str(self.home / 'plugin-install-operations')
        Path(self.transaction.directory).mkdir(exist_ok=True)
        work = Path(self.transaction.directory) / str(uuid.uuid4()); work.mkdir()
        doc = self.manager.builtin.read_manifest()
        doc['dependencies'].pop('test-plugin'); doc['dsh']['profile']['bundles'] = []
        plan = self.transaction.prepare_delete(str(work), 'test-plugin', doc, {})
        if legacy:
            plan.pop('objectFingerprint')
            plan['objects'] = {k: self.transaction.object_state(v) for k, v in self.transaction.delete_targets('test-plugin').items()}
            self.manager.write_json(str(work / 'plan.json'), plan)
        return work, plan

    def scanner(self, check=lambda: None):
        return self.objects(str(self.home), check)

    def cli(self, *args):
        return subprocess.run([sys.executable, str(ASSETS / 'plugin-manager.py'), *args], env=os.environ, capture_output=True, text=True, timeout=30)

    @unittest.skipIf(os.name == 'nt', 'Actual POSIX link semantics')
    def test_real_cli_retains_external_dangling_directory_and_cycle_links(self):
        (self.package / 'store-link').symlink_to(self.store / 'object')
        (self.package / 'missing-link').symlink_to(self.store / 'missing')
        (self.package / 'directory-link').symlink_to(self.store, target_is_directory=True)
        (self.package / 'cycle-link').symlink_to('cycle-link')
        stored = (self.store / 'object').stat()
        with self.assertRaisesRegex(ValueError, '链接缺失或越界'):
            self.manager.dependencies().tree(str(self.package))
        off = self.cli('disable-list', 'test-plugin'); self.assertEqual(off.returncode, 0, off.stdout + off.stderr)
        result = self.cli('delete-list', 'test-plugin'); self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        retained = list((self.home / 'plugin-install-operations/completed').glob('*/delete-link'))
        self.assertEqual(len(retained), 1)
        for name in ['store-link', 'missing-link', 'directory-link', 'cycle-link']:
            self.assertTrue((retained[0] / name).is_symlink())
        self.assertFalse(self.package.exists())
        self.assertEqual((self.store / 'object').read_text(), 'shared immutable original')
        self.assertEqual((self.store / 'object').stat().st_ino, stored.st_ino)
        self.transaction.recover_all(); self.transaction.recover_all()
        self.assertFalse(self.package.exists())

    def test_delete_receipt_confirms_target_without_full_list(self):
        execution = self.cli('delete', 'test-plugin')
        self.assertEqual(0, execution.returncode, execution.stdout + execution.stderr)
        rows = [json.loads(line.split('PLUGIN_RESULT: ', 1)[1])
                for line in execution.stdout.splitlines() if line.startswith('PLUGIN_RESULT: ')]
        self.assertEqual(1, len(rows))
        self.assertEqual('test-plugin', rows[0]['name'])
        self.assertIs(True, rows[0]['removed'])
        self.assertIs(True, rows[0]['deletionVerified'])
        self.assertNotIn('items', rows[0])
        self.assertNotIn('test-plugin', json.loads((self.profile / 'package.json').read_text())['dependencies'])

    def test_delete_expected_version_mismatch_preserves_package_and_profile(self):
        package_before = {
            path.relative_to(self.package): (path.read_bytes(), path.stat().st_ino)
            for path in self.package.rglob('*') if path.is_file()
        }
        manifest = self.profile / 'package.json'
        profile_before = manifest.read_bytes(), manifest.stat().st_ino
        execution = self.cli('delete', 'test-plugin', '2.0.0')
        self.assertNotEqual(0, execution.returncode, execution.stdout + execution.stderr)
        self.assertIn('PLUGIN_TARGET_VERSION_CHANGED', execution.stdout + execution.stderr)
        rows = [json.loads(line.split('PLUGIN_RESULT: ', 1)[1])
                for line in execution.stdout.splitlines() if line.startswith('PLUGIN_RESULT: ')]
        self.assertEqual(1, len(rows))
        self.assertEqual('error', rows[0]['status'])
        self.assertIn('PLUGIN_TARGET_VERSION_CHANGED', rows[0]['message'])
        self.assertTrue(self.package.is_dir())
        self.assertEqual(package_before, {
            path.relative_to(self.package): (path.read_bytes(), path.stat().st_ino)
            for path in self.package.rglob('*') if path.is_file()
        })
        self.assertEqual(profile_before, (manifest.read_bytes(), manifest.stat().st_ino))
        self.assertFalse((self.home / 'plugin-install-operations').exists())

    def test_native_versioned_delete_retains_a_plugin_with_missing_patch(self):
        (self.package / 'cordis.patch.yml').unlink()
        execution = self.cli('delete', 'test-plugin', '1.0.0')
        self.assertEqual(0, execution.returncode, execution.stdout + execution.stderr)
        retained = list((self.home / 'plugin-install-operations/completed').glob('*/delete-link'))
        self.assertEqual(1, len(retained))
        self.assertEqual('original owned bytes', (retained[0] / 'index.js').read_text())
        self.assertFalse(self.package.exists())

    def test_native_versioned_delete_does_not_require_loadable_main_or_cordis_entry(self):
        manifest = self.package / 'package.json'
        package = json.loads(manifest.read_text())
        package.update(main='missing-main.js', cordis={'entry': 'missing-cordis.js'})
        manifest.write_text(json.dumps(package))
        execution = self.cli('delete', 'test-plugin', '1.0.0')
        self.assertEqual(0, execution.returncode, execution.stdout + execution.stderr)
        self.assertFalse(self.package.exists())

    def test_empty_expected_version_cannot_delete_a_later_valid_version(self):
        before = (self.profile / 'package.json').read_bytes()
        execution = self.cli('delete', 'test-plugin', '')
        self.assertNotEqual(0, execution.returncode, execution.stdout + execution.stderr)
        self.assertIn('PLUGIN_TARGET_VERSION_CHANGED', execution.stdout + execution.stderr)
        self.assertTrue(self.package.exists())
        self.assertEqual(before, (self.profile / 'package.json').read_bytes())

    def test_corrupt_disabled_plugin_stays_visible_and_can_be_retained(self):
        original = b'{broken package metadata'
        (self.package / 'package.json').write_bytes(original)
        (self.profile / 'node_modules/test-plugin.disabled').write_text('')
        doc = json.loads((self.profile / 'package.json').read_text())
        doc['dsh']['profile']['bundles'] = []
        (self.profile / 'package.json').write_text(json.dumps(doc))
        with patch.object(self.manager, 'result') as result:
            self.assertEqual(0, self.manager.cmd_list())
            row = next(row for row in result.call_args.kwargs['items'] if row['name'] == 'test-plugin')
        self.assertTrue(row['deletable'])
        self.assertFalse(row['enabled'])
        self.assertEqual('', row['version'])
        self.assertTrue(row['metadataError'])
        execution = self.cli('delete', 'test-plugin', row['version'])
        self.assertEqual(0, execution.returncode, execution.stdout + execution.stderr)
        retained = list((self.home / 'plugin-install-operations/completed').glob('*/delete-link/package.json'))
        self.assertEqual([original], [path.read_bytes() for path in retained])

    def test_disabled_plugin_with_missing_metadata_stays_visible(self):
        (self.package / 'package.json').unlink()
        (self.profile / 'node_modules/test-plugin.disabled').write_text('')
        doc = json.loads((self.profile / 'package.json').read_text())
        doc['dsh']['profile']['bundles'] = []
        (self.profile / 'package.json').write_text(json.dumps(doc))
        with patch.object(self.manager, 'result') as result:
            self.assertEqual(0, self.manager.cmd_list())
            row = next(row for row in result.call_args.kwargs['items'] if row['name'] == 'test-plugin')
        self.assertTrue(row['deletable'])
        self.assertFalse(row['available'])
        execution = self.cli('delete', 'test-plugin', row['version'])
        self.assertEqual(0, execution.returncode, execution.stdout + execution.stderr)
        self.assertFalse(self.package.exists())

    def test_delete_readback_rejects_unconfirmed_load_entry(self):
        with patch.object(self.transaction, 'apply_delete', return_value=None):
            with self.assertRaisesRegex(ValueError, '删除结果未确认'):
                self.manager.cmd_delete('test-plugin')
        self.assertTrue(self.package.is_dir())
        self.assertIn('test-plugin', json.loads((self.profile / 'package.json').read_text())['dependencies'])

    def test_legacy_unmarked_delete_journal_recovers_with_original_algorithm(self):
        work, plan = self.prepare(legacy=True)
        os.replace(self.package, work / 'delete-link')
        self.transaction.recover(str(work))
        self.assertEqual((self.package / 'index.js').read_text(), 'original owned bytes')
        self.assertTrue(self.transaction.marker(str(work), 'rolled-back'))

    def test_new_fingerprint_cannot_be_relabelled_as_legacy_or_unknown(self):
        work, plan = self.prepare()
        plan.pop('objectFingerprint')
        with self.assertRaisesRegex(ValueError, '变化'):
            self.transaction.apply_delete(str(work), plan)
        plan['objectFingerprint'] = 'unknown'
        with self.assertRaisesRegex(ValueError, '算法无效'):
            self.transaction.apply_delete(str(work), plan)
        self.assertTrue(self.package.exists())

    def test_same_content_replaced_inode_and_new_planned_missing_object_refused(self):
        work, plan = self.prepare()
        original = self.package / 'index.js'
        temporary = self.package / 'replacement'; temporary.write_bytes(original.read_bytes())
        os.replace(temporary, original)
        with self.assertRaisesRegex(ValueError, '变化'):
            self.transaction.apply_delete(str(work), plan)
        # A fresh plan must also bind objects which were absent at preview.
        work2, plan2 = self.prepare()
        source = self.home / 'plugin-src/test-plugin'; source.mkdir(parents=True)
        (source / 'later').write_text('later object')
        with self.assertRaisesRegex(ValueError, '变化'):
            self.transaction.apply_delete(str(work2), plan2)
        self.assertTrue(self.package.exists()); self.assertEqual((source / 'later').read_text(), 'later object')

    @unittest.skipIf(os.name == 'nt', 'Actual POSIX link semantics')
    def test_parent_escape_and_link_target_swap_refused_without_following(self):
        work, plan = self.prepare()
        parent = self.profile / 'node_modules'
        parent.rename(self.profile / 'old-modules')
        parent.symlink_to(self.store, target_is_directory=True)
        with self.assertRaises((ValueError, OSError)):
            self.transaction.apply_delete(str(work), plan)
        self.assertEqual((self.store / 'object').read_text(), 'shared immutable original')
        parent.unlink(); (self.profile / 'old-modules').rename(parent)
        link = self.package / 'a-link'; link.symlink_to(self.store / 'object')
        readlink = os.readlink
        def replace_link(name, *args, **kwargs):
            result = readlink(name, *args, **kwargs)
            if name == 'a-link':
                link.unlink(); link.symlink_to(self.store / 'missing')
            return result
        with patch.object(os, 'readlink', side_effect=replace_link), self.assertRaisesRegex(ValueError, '变化'):
            self.scanner().state(str(self.package))
        self.assertEqual((self.store / 'object').read_text(), 'shared immutable original')

    @unittest.skipIf(os.name == 'nt', 'Actual POSIX special files and descriptor race')
    def test_special_file_and_file_swap_during_open_refused(self):
        fifo = self.package / 'pipe'; os.mkfifo(fifo)
        with self.assertRaisesRegex(ValueError, '特殊文件'):
            self.scanner().state(str(self.package))
        fifo.unlink()
        actual_open = os.open
        def swap(name, flags, *args, **kwargs):
            if name == 'index.js':
                (self.package / 'index.js').unlink()
                (self.package / 'index.js').symlink_to(self.store / 'object')
            return actual_open(name, flags, *args, **kwargs)
        with patch.object(os, 'open', side_effect=swap), self.assertRaises(OSError):
            self.scanner().state(str(self.package))
        self.assertEqual((self.store / 'object').read_text(), 'shared immutable original')

    @unittest.skipIf(os.name == 'nt', 'Actual Linux renameat2 race')
    def test_atomic_claim_detects_late_inode_and_parent_replacement(self):
        for replace_parent in [False, True]:
            with self.subTest(parent=replace_parent):
                source = self.home / ('claim-' + str(replace_parent)); source.mkdir()
                (source / 'owned').write_text('selected object')
                destination = self.home / ('retained-' + str(replace_parent))
                scanner = self.scanner(); wanted = scanner.state(str(source))
                actual_library = ctypes.CDLL
                library = actual_library(None, use_errno=True)
                # 生产代码直接发 renameat2 系统调用（proroot 的 libc 符号忽略目录描述符）。
                actual_rename = library.syscall
                actual_rename.argtypes = [ctypes.c_long, ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
                actual_rename.restype = ctypes.c_long
                class Rename:
                    def __call__(self, *args):
                        if replace_parent:
                            moved = self_home.with_name('original-dsh-parent')
                            self_home.rename(moved); self_home.symlink_to(store, target_is_directory=True)
                        else:
                            source.rename(source.with_name(source.name + '-original'))
                            source.mkdir(); (source / 'owned').write_text('selected object')
                        return actual_rename(*args)
                class Library:
                    syscall = Rename()
                self_home, store = self.home, self.store
                with patch.object(ctypes, 'CDLL', return_value=Library()), self.assertRaises((ValueError, OSError)):
                    scanner.move(str(source), str(destination), wanted)
                self.assertEqual((self.store / 'object').read_text(), 'shared immutable original')
                if replace_parent:
                    self.home.unlink(); self.home.with_name('original-dsh-parent').rename(self.home)
                self.assertTrue(destination.exists())

    @unittest.skipIf(os.name == 'nt', 'Linux descriptor-relative renameat2 syscall')
    def test_retained_move_uses_descriptor_syscall_not_libc_renameat2(self):
        # proroot v1.2.8 的 libc renameat2 忽略目录描述符、按 cwd 解析名字：返回 ENOENT，
        # 或把 cwd 下同名对象改名。生产路径必须绕开该符号，且仍拒绝覆盖已有目标。
        rename = self.transaction.object_state.__globals__['rename_noreplace']
        real = ctypes.CDLL(None, use_errno=True)
        class Library:
            syscall = real.syscall
            @property
            def renameat2(self):
                raise AssertionError('libc renameat2 must not be used')
        decoy = self.root / 'cwd'; decoy.mkdir(); (decoy / 'object').write_text('cwd decoy')
        source_dir, target_dir = self.root / 'source', self.root / 'target'
        source_dir.mkdir(); target_dir.mkdir(); (source_dir / 'object').write_text('selected')
        descriptors = [os.open(str(path), os.O_RDONLY | os.O_DIRECTORY) for path in (source_dir, target_dir)]
        previous = os.getcwd(); os.chdir(str(decoy))
        try:
            with patch.object(ctypes, 'CDLL', return_value=Library()):
                rename(descriptors[0], 'object', descriptors[1], 'retained')
                (source_dir / 'object').write_text('second')
                with self.assertRaises(FileExistsError):
                    rename(descriptors[0], 'object', descriptors[1], 'retained')
                with self.assertRaisesRegex(ValueError, '名称无效'):
                    rename(descriptors[0], '../object', descriptors[1], 'other')
        finally:
            os.chdir(previous)
            for descriptor in descriptors:
                os.close(descriptor)
        self.assertEqual((target_dir / 'retained').read_text(), 'selected')
        self.assertEqual((source_dir / 'object').read_text(), 'second')
        self.assertEqual(sorted(p.name for p in decoy.iterdir()), ['object'])
        self.assertEqual((decoy / 'object').read_text(), 'cwd decoy')

    def test_cancellation_is_propagated_before_any_move(self):
        class Cancelled(Exception):
            pass
        def cancel():
            raise Cancelled('owned cancellation')
        with self.assertRaisesRegex(Cancelled, 'owned cancellation'):
            self.scanner(cancel).state(str(self.package))
        self.assertEqual((self.package / 'index.js').read_text(), 'original owned bytes')


if __name__ == '__main__':
    unittest.main(verbosity=2)
