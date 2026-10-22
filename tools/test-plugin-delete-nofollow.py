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
                actual_rename = library.renameat2
                actual_rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
                actual_rename.restype = ctypes.c_int
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
                    renameat2 = Rename()
                self_home, store = self.home, self.store
                with patch.object(ctypes, 'CDLL', return_value=Library()), self.assertRaises((ValueError, OSError)):
                    scanner.move(str(source), str(destination), wanted)
                self.assertEqual((self.store / 'object').read_text(), 'shared immutable original')
                if replace_parent:
                    self.home.unlink(); self.home.with_name('original-dsh-parent').rename(self.home)
                self.assertTrue(destination.exists())

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
