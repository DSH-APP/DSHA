#!/usr/bin/env python3
"""Independent negative controls for descriptor-anchored startup CAS scans."""
import contextlib
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch


_spec = importlib.util.spec_from_file_location(
    'cas_scan_review_fixture', Path(__file__).with_name('test-plugin-cas-materialize.py'))
_fixture = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_fixture)


@unittest.skipIf(os.name == 'nt', 'Actual Linux directory descriptors and NOFOLLOW')
class CasScanReviewTest(unittest.TestCase):
    setUp = _fixture.CasMaterializeTest.setUp
    tearDown = _fixture.CasMaterializeTest.tearDown
    make_link = _fixture.CasMaterializeTest.make_link

    def inject_during_deep_read(self, action):
        deep = self.package / 'a/b/c'
        deep.mkdir(parents=True)
        (deep / 'payload').write_bytes(b'ordinary deep file')
        original = os.scandir
        listdir = os.listdir
        fired = []

        def inspect(scan, directory):
            for entry in scan:
                if entry.name == 'payload' and not fired:
                    # The operation addresses the held deep directory FD.
                    self.assertEqual(Path(os.readlink('/proc/self/fd/' + str(directory))), deep)
                    fired.append(True)
                    action()
                yield entry

        def list_private(directory):
            actual = (Path(os.readlink('/proc/self/fd/' + str(directory)))
                      if isinstance(directory, int) else Path(directory))
            outside = self.root / 'outside'
            self.assertFalse(actual == outside or outside in actual.parents,
                             'The scanner followed a substituted external directory')
            return listdir(directory)

        @contextlib.contextmanager
        def scan_private(directory):
            actual = Path(os.readlink('/proc/self/fd/' + str(directory)))
            outside = self.root / 'outside'
            self.assertFalse(actual == outside or outside in actual.parents,
                             'The scanner followed a substituted external directory')
            with original(directory) as scan:
                yield inspect(scan, directory)

        @contextlib.contextmanager
        def injected():
            with patch.object(os, 'scandir', scan_private), patch.object(os, 'listdir', list_private):
                yield

        return injected(), fired

    def test_replaced_parent_link_cannot_redirect_held_descendant_scan(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'untouched').write_bytes(b'external original')
        retained = self.root / 'retained-package'

        def replace():
            self.package.rename(retained)
            self.package.symlink_to(outside, target_is_directory=True)

        injected, fired = self.inject_during_deep_read(replace)
        with injected, self.assertRaises((ValueError, OSError)):
            self.transaction.cas_links()
        self.assertTrue(fired)
        self.assertEqual((outside / 'untouched').read_bytes(), b'external original')
        self.assertEqual(list(outside.iterdir()), [outside / 'untouched'])
        self.assertTrue((retained / 'index.js').is_symlink())
        self.assertEqual(self.target.read_bytes(), b'export const answer=42;\n')

    def test_parent_rename_away_and_back_rejected_by_directory_stability(self):
        retained = self.root / 'retained-package'

        def replace_and_restore():
            self.package.rename(retained)
            self.package.mkdir()
            self.package.rmdir()
            retained.rename(self.package)

        injected, fired = self.inject_during_deep_read(replace_and_restore)
        with injected, self.assertRaises((ValueError, OSError)):
            self.transaction.cas_links()
        self.assertTrue(fired)
        self.assertTrue(self.link.is_symlink())
        self.assertEqual(self.target.read_bytes(), b'export const answer=42;\n')

    def test_replaced_ancestor_above_module_root_rejected_on_unwind(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'untouched').write_bytes(b'external original')
        retained = self.root / 'retained-profile'

        def replace():
            self.profile.rename(retained)
            self.profile.symlink_to(outside, target_is_directory=True)

        injected, fired = self.inject_during_deep_read(replace)
        with injected, self.assertRaises((ValueError, OSError)):
            self.transaction.cas_links()
        self.assertTrue(fired)
        self.assertEqual((outside / 'untouched').read_bytes(), b'external original')
        self.assertEqual(list(outside.iterdir()), [outside / 'untouched'])
        self.assertTrue((retained / 'node_modules/test-plugin/index.js').is_symlink())

    def test_scan_candidates_do_not_authorize_replaced_publish_ancestor(self):
        original = self.transaction.boundary
        retained = self.root / 'retained-package'
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'index.js').write_bytes(b'external later original')

        def boundary(name):
            if name == 'cas-prepared':
                self.package.rename(retained)
                self.package.symlink_to(outside, target_is_directory=True)
            return original(name)

        self.transaction.boundary = boundary
        with self.assertRaises((ValueError, OSError)):
            self.transaction.materialize_cas_links()
        self.assertTrue(self.transaction.pending())
        self.assertEqual((outside / 'index.js').read_bytes(), b'external later original')
        self.assertTrue((retained / 'index.js').is_symlink())
        self.assertEqual(self.target.read_bytes(), b'export const answer=42;\n')

    def test_stale_regular_dirent_replaced_by_cas_link_is_rejected(self):
        payload = self.package / 'payload'
        payload.write_bytes(b'ordinary original')
        before = self.package.stat()
        original = os.scandir
        fired = []

        class CachedEntry:
            def __init__(entry_self, entry):
                entry_self.entry = entry
                entry_self.name = entry.name

            def is_symlink(entry_self):
                return entry_self.entry.is_symlink()

            def is_dir(entry_self, **kwargs):
                return entry_self.entry.is_dir(**kwargs)

            def is_file(entry_self, **kwargs):
                cached = entry_self.entry.is_file(**kwargs)
                if entry_self.name == 'payload' and not fired:
                    self.assertTrue(cached)
                    payload.unlink()
                    payload.symlink_to(self.target)
                    # Restoring directory mtime must not hide the replacement;
                    # its ctime and identity are independently checked.
                    os.utime(self.package, ns=(before.st_atime_ns, before.st_mtime_ns))
                    fired.append(True)
                return cached

        @contextlib.contextmanager
        def cached_scan(directory):
            with original(directory) as scan:
                yield (CachedEntry(entry) for entry in scan)

        with patch.object(os, 'scandir', cached_scan), self.assertRaisesRegex(ValueError, '变化'):
            self.transaction.cas_links()
        self.assertTrue(fired)
        self.assertTrue(payload.is_symlink())
        self.assertTrue(self.link.is_symlink())
        self.assertEqual(self.target.read_bytes(), b'export const answer=42;\n')

    def test_late_broken_cas_link_is_not_hidden_by_prior_success(self):
        self.transaction.materialize_cas_links()
        self.assertFalse(self.link.is_symlink())
        deep = self.package / 'nested/deeper'
        deep.mkdir(parents=True)
        stamp = self.modules.stat().st_mtime_ns
        broken, target = self.make_link(deep / 'broken.js', b'new CAS bytes')
        target.unlink()
        self.assertEqual(self.modules.stat().st_mtime_ns, stamp)
        result, value = _fixture.CasMaterializeTest.cli(self)
        self.assertEqual((result.returncode, value['status']), (1, 'error'))
        self.assertTrue(broken.is_symlink())
        self.assertFalse(self.link.is_symlink())

    def test_cancel_during_regular_file_scan_returns_no_partial_receipt(self):
        for index in range(100):
            (self.package / ('plain-' + str(index))).write_bytes(b'ordinary bytes')
        original = self.transaction.g['check_cancel']
        cancelled = []
        seen = []

        def check():
            seen.append(True)
            if len(seen) == 60:
                cancelled.append(True)
                raise self.manager.PluginCancelled('cancel during live scan')
            original()

        with patch.dict(self.transaction.g, check_cancel=check):
            with self.assertRaisesRegex(self.manager.PluginCancelled, 'cancel during live scan'):
                self.transaction.cas_links()
        self.assertTrue(cancelled)
        self.assertTrue(self.link.is_symlink())
        self.assertFalse((self.home / 'plugin-install-operations').exists())

    def test_path_based_fallback_preserves_per_entry_checks(self):
        reader = self.transaction.cas_links.__globals__['DeleteObjects']
        original_init, original_stat = reader.__init__, reader.stat
        calls = []

        def path_init(instance, *args):
            original_init(instance, *args)
            instance.relative = False

        def counted(instance, parent, name):
            calls.append(name)
            return original_stat(instance, parent, name)

        with patch.object(reader, '__init__', path_init), patch.object(reader, 'stat', counted), \
                patch.object(os, 'scandir', side_effect=AssertionError('path fallback cannot use dirents')):
            found = self.transaction.cas_links()
        self.assertEqual([entry['path'] for entry in found], ['test-plugin/index.js'])
        self.assertIn('package.json', calls)


if __name__ == '__main__':
    unittest.main(verbosity=2)
