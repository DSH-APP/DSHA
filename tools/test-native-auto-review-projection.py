#!/usr/bin/env python3
"""Run the production Native CLI against private alias/intent fixtures; no Web or device."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets'
ALIASES = ('dsh-auto-review', '@deepseek-ai/dsh-experimental-auto-review')
CANONICAL = ALIASES[1]
PROOF_ENV = 'DSHA_MANAGED_PACKAGE_PROOFS_SHA256'

# Compute evidence for this test's synthetic bytes using the production encoder.
# No digest or package identity is borrowed from the production proof table.
_spec = importlib.util.spec_from_file_location('native_alias_fixture_proof', ASSETS / 'register-builtin-plugins.py')
_proof = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_proof)


class NativeAutoReviewProjectionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='dsha-native-auto-projection-')
        self.root = Path(self.temp.name)
        self.profile = self.root / 'root/.dsh/profiles/web/package.json'
        self.profile.parent.mkdir(parents=True)
        self.write_profile([])
        self.runtime = self.root / 'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules' / CANONICAL
        self.package(self.runtime, CANONICAL, '0.2.1-rc.2')
        self.proof_path = self.root / 'root/.dsh/managed-package-proofs.json'
        self.proof_path.write_text(json.dumps({'version': 1, 'packages': {
            CANONICAL: _proof.package_content_proof(self.runtime)}}), encoding='utf-8')
        self.proof_sha = hashlib.sha256(self.proof_path.read_bytes()).hexdigest()
        self.package(self.root / 'root/dsha-auto-review', ALIASES[0], '0.1.0')
        self.settings = self.root / 'root/.dsh/settings.yaml'
        self.settings.write_text('permissionMode: ask\nfixture: preserve explicit choice\n', encoding='utf-8')
        self.original_settings = self.settings.read_bytes()
        self.original_runtime = self.snapshot(self.runtime)

    def tearDown(self):
        self.assertEqual(self.original_settings, self.settings.read_bytes(), 'Native switch must not select a permission mode')
        self.temp.cleanup()

    def package(self, path, name, version):
        path.mkdir(parents=True, exist_ok=True)
        (path / 'package.json').write_text(json.dumps(dict(name=name, version=version,
            main='index.js', dsh={'bundle': {'patch': 'cordis.patch.yml'}})), encoding='utf-8')
        (path / 'cordis.patch.yml').write_text('[]\n', encoding='utf-8')
        (path / 'index.js').write_text('module.exports = {}\n', encoding='utf-8')

    def snapshot(self, path):
        return {str(p.relative_to(path)): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in path.rglob('*') if p.is_file()}

    def write_profile(self, aliases, dependencies=None):
        self.profile.write_text(json.dumps({'dependencies': dependencies or {}, 'dsh': {'profile': {
            'bundles': ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app', *aliases]}}}), encoding='utf-8')

    def selected(self):
        return json.loads(self.profile.read_text(encoding='utf-8'))['dsh']['profile']['bundles']

    def marker(self, name):
        path = self.profile.parent / 'node_modules' / (name + '.disabled')
        path.parent.mkdir(parents=True, exist_ok=True)
        return path

    def cli(self, *args, success=True):
        env = dict(os.environ, DSHA_TEST_ROOT=str(self.root), DSH_HOME='/root/.dsh',
                   PYTHONDONTWRITEBYTECODE='1', PYTHONIOENCODING='utf-8')
        # The test launcher binds the exact temporary proof bytes, just as the
        # native launcher binds APK bytes. Never inherit ambient guest authority.
        env.pop(PROOF_ENV, None)
        if self.proof_sha is not None:
            env[PROOF_ENV] = self.proof_sha
        process = subprocess.run([sys.executable, str(ASSETS / 'plugin-manager.py'), *args], env=env,
                                 capture_output=True, text=True, encoding='utf-8', timeout=15)
        self.assertEqual(0 if success else 1, process.returncode, process.stdout + process.stderr)
        rows = [json.loads(line.removeprefix('PLUGIN_RESULT: ')) for line in process.stdout.splitlines()
                if line.startswith('PLUGIN_RESULT: ')]
        self.assertTrue(rows, process.stdout + process.stderr)
        return rows[-1]

    def card(self, result):
        rows = [row for row in result['items'] if row['name'] in ALIASES]
        self.assertEqual(1, len(rows), rows)
        self.assertEqual(CANONICAL, rows[0]['name'])
        self.assertTrue(rows[0]['builtin'])
        self.assertFalse(rows[0]['official'], 'optional service is not a required Web core')
        self.assertFalse(rows[0]['internal'])
        self.assertFalse(rows[0]['deletable'])
        self.assertFalse(rows[0]['updatable'])
        return rows[0]

    def test_one_canonical_card_projects_either_alias_without_writing_profile(self):
        for selected in ([], [ALIASES[0]], [CANONICAL], list(ALIASES)):
            with self.subTest(selected=selected):
                self.write_profile(selected)
                before = self.profile.read_bytes()
                row = self.card(self.cli('refresh'))
                self.assertEqual(bool(selected), row['enabled'])
                self.assertTrue(row['available']); self.assertTrue(row['runtimeProvided'])
                self.assertEqual('0.2.1-rc.2', row['version'])
                self.assertEqual(before, self.profile.read_bytes())

    def test_each_native_marker_wins_over_inconsistent_selected_aliases(self):
        for name in ALIASES:
            for value in ('', 'UNKNOWN_PREVIOUS_INTENT\n', 'DSHA_SAFE_MODE\n'):
                with self.subTest(name=name, value=value):
                    self.write_profile(list(ALIASES))
                    marker = self.marker(name); marker.write_text(value, encoding='utf-8')
                    before = self.profile.read_bytes()
                    self.assertFalse(self.card(self.cli('refresh'))['enabled'])
                    self.assertEqual(before, self.profile.read_bytes())
                    self.assertEqual(value, marker.read_text(encoding='utf-8'))
                    marker.unlink()

    def test_canonical_enable_clears_group_markers_and_keeps_runtime_bytes(self):
        for name in ALIASES:
            self.marker(name).write_text('DSHA_SAFE_MODE\n', encoding='utf-8')
        self.assertTrue(self.card(self.cli('enable-list', CANONICAL))['enabled'])
        self.assertEqual([CANONICAL], [n for n in self.selected() if n in ALIASES])
        self.assertFalse(any(self.marker(name).exists() for name in ALIASES))
        self.assertEqual(self.original_runtime, self.snapshot(self.runtime))

    def test_native_disable_of_either_alias_removes_the_whole_selection(self):
        for name in ALIASES:
            with self.subTest(name=name):
                self.write_profile(list(ALIASES))
                self.assertFalse(self.card(self.cli('disable-list', name))['enabled'])
                self.assertFalse(set(ALIASES) & set(self.selected()))
                self.assertEqual('', self.marker(ALIASES[0]).read_text(encoding='utf-8'))
                self.assertEqual(self.original_runtime, self.snapshot(self.runtime))

    def test_delete_denies_both_aliases_even_when_runtime_is_missing(self):
        self.write_profile(list(ALIASES), {CANONICAL: 'user-shadow-must-remain'})
        before = self.profile.read_bytes()
        for missing in (False, True):
            if missing:
                shutil.rmtree(self.runtime)
            for name in ALIASES:
                with self.subTest(missing=missing, name=name):
                    result = self.cli('delete-list', name, success=False)
                    self.assertIn('不能删除', result['message'])
                    self.assertEqual(before, self.profile.read_bytes())
            if not missing:
                self.assertEqual(self.original_runtime, self.snapshot(self.runtime))

    def test_missing_canonical_runtime_cannot_be_enabled_via_same_name_user_copy(self):
        shutil.rmtree(self.runtime)
        shadow = self.profile.parent / 'node_modules' / CANONICAL
        self.package(shadow, CANONICAL, '9.9.9')
        self.write_profile([], {CANONICAL: '9.9.9'})
        before = self.profile.read_bytes(); shadow_before = self.snapshot(shadow)
        row = self.card(self.cli('refresh'))
        self.assertFalse(row['available']); self.assertFalse(row['runtimeProvided'])
        self.cli('enable-list', CANONICAL, success=False)
        self.assertEqual(before, self.profile.read_bytes())
        self.assertEqual(shadow_before, self.snapshot(shadow))

    def test_actual_runtime_metadata_wins_over_same_name_user_copy(self):
        shadow = self.profile.parent / 'node_modules' / CANONICAL
        self.package(shadow, CANONICAL, '9.9.9')
        self.write_profile([CANONICAL], {CANONICAL: '9.9.9'})
        row = self.card(self.cli('refresh'))
        self.assertTrue(row['runtimeProvided']); self.assertEqual('0.2.1-rc.2', row['version'])
        self.assertEqual('9.9.9', json.loads((shadow / 'package.json').read_text(encoding='utf-8'))['version'])

    def test_missing_or_unauthenticated_proof_cannot_classify_runtime_as_managed(self):
        expected = self.proof_sha
        self.proof_sha = None
        row = self.card(self.cli('refresh'))
        self.assertTrue(row['available']); self.assertTrue(row['runtimeLocated'])
        self.assertFalse(row['runtimeProvided'])
        self.proof_sha = expected
        self.proof_path.unlink()
        self.assertFalse(self.card(self.cli('refresh'))['runtimeProvided'])

    def test_tampered_proof_bytes_cannot_classify_runtime_as_managed(self):
        original = self.proof_path.read_bytes()
        # Still-valid JSON, but no longer the bytes authenticated by the launcher.
        self.proof_path.write_bytes(original + b'\n')
        self.assertNotEqual(self.proof_sha, hashlib.sha256(self.proof_path.read_bytes()).hexdigest())
        self.assertFalse(self.card(self.cli('refresh'))['runtimeProvided'])
        self.assertEqual(self.original_runtime, self.snapshot(self.runtime))

    def test_added_user_content_cannot_classify_same_runtime_name_as_managed(self):
        self.assertTrue(self.card(self.cli('refresh'))['runtimeProvided'])
        draft = self.runtime / 'user-private-draft.txt'
        draft.write_text('preserve modified runtime package bytes\n', encoding='utf-8')
        before = self.snapshot(self.runtime)
        row = self.card(self.cli('refresh'))
        self.assertTrue(row['available']); self.assertTrue(row['runtimeLocated'])
        self.assertFalse(row['runtimeProvided'])
        self.assertEqual(before, self.snapshot(self.runtime))
        self.assertEqual('preserve modified runtime package bytes\n', draft.read_text(encoding='utf-8'))

    def test_missing_runtime_and_only_disabled_marker_still_show_protected_card(self):
        shutil.rmtree(self.runtime)
        self.marker(CANONICAL).write_text('', encoding='utf-8')
        row = self.card(self.cli('refresh'))
        self.assertFalse(row['enabled']); self.assertFalse(row['available'])
        self.assertFalse(row['exportable']); self.assertFalse(row['updateAvailable'])


if __name__ == '__main__':
    unittest.main()
