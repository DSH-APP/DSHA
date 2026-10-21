"""Exercise actual gzip construction and signed policy-overlay completeness."""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('dsh_packaging_builder',
                                             Path(__file__).with_name('build-dsh-runtime.py'))
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)
ASSETS = builder.HOOKS.parent


class DshRuntimePackaging(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def fixture_assets(self):
        assets = self.root / 'assets'
        for relative in ('destructive-policy', 'builtin-plugins/dsh-destructive-guard/lib'):
            shutil.copytree(ASSETS / relative, assets / relative)
        manifest = json.loads((ASSETS / 'managed-runtime-inputs.json').read_text(encoding='utf-8'))
        self.save_manifest(assets, manifest)
        return assets, manifest

    @staticmethod
    def save_manifest(assets, manifest):
        (assets / 'managed-runtime-inputs.json').write_text(json.dumps(manifest), encoding='utf-8')

    def test_real_archive_keeps_dsh_and_hooks_while_policy_comes_from_exact_signed_installs(self):
        modules = self.root / 'node_modules'
        version = json.loads((builder.LOCK_ROOT / 'package.json').read_text(encoding='utf-8'))['dependencies'][builder.PACKAGE]
        for name in (builder.PACKAGE, '@deepseek-ai/dsh-llm-deepseek'):
            package = modules / name
            package.mkdir(parents=True)
            (package / 'package.json').write_text(json.dumps(dict(name=name, version=version)), encoding='utf-8')
        entry = modules / builder.PACKAGE / 'lib/bin.js'
        entry.parent.mkdir()
        entry.write_bytes(b'#!/usr/bin/env node\nconsole.log("fixture");\n')
        archive = self.root / 'dsh-runtime.bin'
        # Keep this small archive fixture honest: use one declared active chain
        # and exercise its actual archive preparation path, without inventing
        # empty modules for the production DSH dependency tree.
        chain = [('fixture-chain', [('console.log("fixture")', 'console.log("prepared")')], [])]
        with patch.object(builder, 'load_chains', return_value=({builder.PACKAGE + '/lib/bin.js': chain}, [])):
            report = builder.build(modules, archive, version)
        with tarfile.open(archive, 'r:gz') as output:
            names = output.getnames()
            self.assertEqual(entry.read_bytes().replace(b'fixture', b'prepared'), output.extractfile(builder.PREFIX + '/lib/bin.js').read())
            self.assertEqual(version + '\n', output.extractfile('usr/local/share/dsha/dsh-runtime.version').read().decode())
            self.assertFalse(any('/dsha-destructive-policy/' in name for name in names))
            for folder, package in ((builder.HOOKS, 'dsha-runtime-fs'),
                                    (builder.SESSION_HOOKS, 'dsha-session-compat'),
                                    (builder.COMBO_HOOKS, 'dsha-client-combo-cache')):
                for filename in ('index.js', 'package.json'):
                    self.assertEqual((folder / filename).read_bytes(),
                                     output.extractfile(builder.PREFIX + '/node_modules/' + package + '/' + filename).read())
        metadata = json.loads(archive.with_suffix('.inputs.json').read_text(encoding='utf-8'))
        self.assertEqual(report['sha256'], metadata['archive_sha256'])
        self.assertIn('app/src/main/assets/managed-runtime-inputs.json', metadata['inputs'])
        # Materialize precisely the current signed install table into an isolated
        # candidate and compare every policy byte with the builder's safe sources.
        manifest = json.loads((ASSETS / 'managed-runtime-inputs.json').read_text(encoding='utf-8'))
        expected = builder.destructive_policy_files()
        selected = [row for row in manifest['installs'] if row['asset'].startswith('destructive-policy/')]
        self.assertEqual(set(expected), {Path(row['asset']).name for row in selected})
        candidate = self.root / 'candidate'
        for row in selected:
            target = candidate / row['target']
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ASSETS / row['asset'], target)
        for filename, source in expected.items():
            self.assertEqual(source.read_bytes(),
                             (candidate / builder.PREFIX / 'node_modules/dsha-destructive-policy' / filename).read_bytes())

    def test_removed_redirected_executable_or_shadowed_policy_install_stops_build(self):
        assets, original = self.fixture_assets()
        position = next(i for i, row in enumerate(original['installs'])
                        if row['asset'] == 'destructive-policy/index.js')
        mutations = ('missing', 'redirected-source', 'redirected-target', 'executable', 'shadowed')
        for mutation in mutations:
            manifest = copy.deepcopy(original)
            entry = manifest['installs'][position]
            if mutation == 'missing':
                del manifest['installs'][position]
            elif mutation == 'redirected-source':
                entry['asset'] = 'different-policy/index.js'
            elif mutation == 'redirected-target':
                entry['target'] += '.wrong'
            elif mutation == 'executable':
                entry['executable'] = True
            else:
                manifest['installs'].append(copy.deepcopy(entry))
            self.save_manifest(assets, manifest)
            with self.subTest(mutation=mutation), self.assertRaisesRegex(ValueError, 'DESTRUCTIVE_POLICY_MANAGED_INSTALL'):
                builder.destructive_policy_files(assets)

    def test_changed_canonical_policy_cannot_hide_behind_overlay_copy(self):
        assets, _ = self.fixture_assets()
        original = builder.destructive_policy_files(assets)
        self.assertEqual(4, len(original))
        target = assets / 'destructive-policy/destructive.js'
        target.write_bytes(target.read_bytes() + b'\n// changed\n')
        with self.assertRaisesRegex(ValueError, 'DESTRUCTIVE_POLICY_COPY_CHANGED'):
            builder.destructive_policy_files(assets)


if __name__ == '__main__':
    unittest.main()
