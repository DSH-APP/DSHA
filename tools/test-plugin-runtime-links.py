#!/usr/bin/env python3
"""Real POSIX links: full runtime repair preserves user aliases and exact migrations."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets'


@unittest.skipIf(os.name == 'nt', 'Requires actual POSIX symlinks')
class RuntimeLinksTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='dsha-runtime-links-')
        self.root = Path(self.temporary.name)
        self.environment = patch.dict(os.environ, {'DSHA_TEST_ROOT': str(self.root), 'DSH_HOME': '/root/.dsh'})
        self.environment.start()
        spec = importlib.util.spec_from_file_location('runtime_links_fixture', ASSETS / 'register-builtin-plugins.py')
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.runtime = self.root / 'usr/local/lib/node_modules/@deepseek-ai/dsh'
        self.modules = self.runtime / 'node_modules'
        self.shared = self.root / 'root/.dsh/node_modules'
        self.modules.mkdir(parents=True)
        self.shared.mkdir(parents=True)
        (self.runtime / 'package.json').write_text('{"name":"@deepseek-ai/dsh"}')
        self.target = self.package('target')

    def tearDown(self):
        self.environment.stop()
        self.temporary.cleanup()

    def package(self, name):
        target = self.modules / name
        target.mkdir(parents=True)
        (target / 'package.json').write_text(json.dumps({'name': name, 'version': '1.0.0'}))
        return target

    def ensure(self, link, target=None):
        return self.module.ensure_relative_link(str(link), str(target or self.target))

    def test_correct_and_foreign_relative_links_never_resolve_their_contents(self):
        for index, value in enumerate((os.path.relpath(self.target, self.shared), '../user-owned', '../missing', 'cycle')):
            link = self.shared / ('link-' + str(index))
            link.symlink_to(value)
            with patch.object(self.module.os.path, 'realpath', side_effect=AssertionError('must not follow existing relative link')):
                self.assertFalse(self.ensure(link))
            self.assertEqual(os.readlink(link), value)

    def test_exact_absolute_link_migrates_without_redundant_realpath(self):
        link = self.shared / 'absolute'
        link.symlink_to(self.target)
        with patch.object(self.module.os.path, 'realpath', side_effect=AssertionError('same absolute spelling')):
            self.assertTrue(self.ensure(link))
        self.assertFalse(os.path.isabs(os.readlink(link)))
        self.assertEqual(link.resolve(), self.target.resolve())

    def test_different_absolute_alias_is_only_migrated_when_canonical_target_matches(self):
        alias = self.root / 'alias'
        alias.symlink_to(self.target, target_is_directory=True)
        link = self.shared / 'alias-link'
        link.symlink_to(alias)
        self.assertTrue(self.ensure(link))
        self.assertEqual(link.resolve(), self.target.resolve())
        other = self.package('other')
        foreign = self.shared / 'foreign'
        foreign.symlink_to(other)
        self.assertFalse(self.ensure(foreign))
        self.assertEqual(os.readlink(foreign), str(other))

    def test_failed_atomic_replace_retains_original_and_removes_only_own_temporary(self):
        link = self.shared / 'absolute'
        link.symlink_to(self.target)
        with patch.object(self.module.os, 'replace', side_effect=OSError('fixture blocked replace')):
            with self.assertRaises(OSError):
                self.ensure(link)
        self.assertEqual(os.readlink(link), str(self.target))
        self.assertEqual(list(self.shared.glob('*.dsha-link-*')), [])

    def test_forced_repair_still_enumerates_late_packages_and_preserves_user_entries(self):
        user = self.shared / 'target'
        user.mkdir()
        (user / 'owned').write_bytes(b'keep')
        with contextlib.redirect_stdout(io.StringIO()):
            self.module.ensure_runtime_modules(force=True)
            late = self.package('@late/new-package')
            self.assertGreater(self.module.ensure_runtime_modules(force=True), 0)
        self.assertEqual((self.shared / '@late/new-package').resolve(), late.resolve())
        self.assertEqual((user / 'owned').read_bytes(), b'keep')

    def test_runtime_parent_escaping_home_is_still_rejected(self):
        self.package('@escape/package')
        external = self.root / 'outside'
        external.mkdir()
        (self.shared / '@escape').symlink_to(external, target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, '目录指向'):
            self.module.repair_runtime_modules()
        self.assertEqual(list(external.iterdir()), [])

    def test_builtin_wrong_relative_alias_is_repaired_by_its_owner(self):
        target = self.root / 'root/dsha-app-integration'
        target.mkdir()
        (target / 'package.json').write_text('{"name":"dsh-app-integration"}')
        link = self.root / 'root/.dsh/profiles/web/node_modules/dsh-app-integration'
        link.parent.mkdir(parents=True)
        link.symlink_to('../foreign')
        self.assertTrue(self.module.ensure_symlink('dsh-app-integration', '/root/dsha-app-integration'))
        self.assertEqual(link.resolve(), target.resolve())


if __name__ == '__main__':
    unittest.main()
