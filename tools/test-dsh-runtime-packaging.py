"""Exercise actual gzip construction and signed policy-overlay completeness."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath, PureWindowsPath
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
        # 同内容在 Windows/Linux 的原生 Path 排序不同，必须锁定实际 tar 成员次序。
        for name in ('Z-last.js', 'alpha/child.js', 'A-first.js', 'middle.js', 'Zoo/child.js'):
            extra = entry.parent / name
            extra.parent.mkdir(parents=True, exist_ok=True)
            extra.write_bytes(('fixture:' + name).encode())
        archive = self.root / 'dsh-runtime.bin'
        # Keep this small archive fixture honest: use one declared active chain
        # and exercise its actual archive preparation path, without inventing
        # empty modules for the production DSH dependency tree.
        chain = [('fixture-chain', [('console.log("fixture")', 'console.log("prepared")')], [])]
        with patch.object(builder, 'load_chains', return_value=({builder.PACKAGE + '/lib/bin.js': chain}, [])):
            report = builder.build(modules, archive, version)
            native_rglob = Path.rglob
            def reversed_enumeration(directory, pattern):
                return iter(reversed(list(native_rglob(directory, pattern))))
            repeated = self.root / 'repeated.bin'
            with patch.object(Path, 'rglob', reversed_enumeration):
                builder.build(modules, repeated, version)
            self.assertEqual(archive.read_bytes(), repeated.read_bytes(),
                             'filesystem enumeration must not change gzip bytes')
        with tarfile.open(archive, 'r:gz') as output:
            names = output.getnames()
            prefix = builder.PREFIX + '/lib/'
            self.assertEqual([name[len(prefix):] for name in names if name.startswith(prefix)],
                             ['A-first.js', 'alpha/child.js', 'bin.js', 'middle.js',
                              'Z-last.js', 'Zoo/child.js'])
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

    def test_path_order_is_identical_for_both_host_path_flavors(self):
        relative = ['pkg/dir/index.js', 'pkg/dir-a.js', 'pkg/Z.js', 'pkg/a.js', 'pkg/A.js']
        expected = ['pkg/A.js', 'pkg/a.js', 'pkg/dir/index.js', 'pkg/dir-a.js', 'pkg/Z.js']
        for kind in (PurePosixPath, PureWindowsPath):
            base = kind('/fixture/node_modules')
            paths = [base / name for name in relative]
            ordered = sorted(paths, key=lambda item: builder.archive_path_key(item, base))
            self.assertEqual([item.relative_to(base).as_posix() for item in ordered], expected)

    def small_modules(self):
        modules = self.root / 'small-node-modules'
        version = json.loads((builder.LOCK_ROOT / 'package.json').read_text(encoding='utf-8'))['dependencies'][builder.PACKAGE]
        for name in (builder.PACKAGE, '@deepseek-ai/dsh-llm-deepseek'):
            package = modules / name
            package.mkdir(parents=True)
            (package / 'package.json').write_text(json.dumps(dict(name=name, version=version)), encoding='utf-8')
        entry = modules / builder.PACKAGE / 'lib/bin.js'
        entry.parent.mkdir()
        entry.write_bytes(b'console.log("before");\n')
        chain = [('fixture-chain', [('"before"', '"after"')], [])]
        return modules, version, {builder.PACKAGE + '/lib/bin.js': chain}

    def test_recipe_edit_during_real_gzip_keeps_previous_archive_and_receipt(self):
        modules, version, chains = self.small_modules()
        recipe = self.root / 'changing-recipe.json'
        recipe.write_bytes(b'before')
        archive = self.root / 'retained.bin'
        receipt = archive.with_suffix('.inputs.json')
        archive.write_bytes(b'previous archive bytes')
        receipt.write_bytes(b'previous receipt bytes')
        real_apply = builder.apply_stable_patches

        def fingerprint():
            return {'changing-recipe.json': hashlib.sha256(recipe.read_bytes()).hexdigest()}

        def edit_while_preparing(data, chain):
            prepared = real_apply(data, chain)
            recipe.write_bytes(b'after')
            return prepared

        with patch.object(builder, 'load_chains', return_value=(chains, [])), \
                patch.object(builder, 'recipe_inputs', side_effect=fingerprint), \
                patch.object(builder, 'apply_stable_patches', side_effect=edit_while_preparing):
            with self.assertRaisesRegex(ValueError, 'DSH_BUILD_INPUTS_CHANGED_DURING_BUILD'):
                builder.build(modules, archive, version)
        self.assertEqual(b'previous archive bytes', archive.read_bytes())
        self.assertEqual(b'previous receipt bytes', receipt.read_bytes())
        self.assertFalse(archive.with_suffix('.bin.tmp').exists())

    def test_receipt_uses_start_snapshot_without_a_post_publication_input_read(self):
        modules, version, chains = self.small_modules()
        initial = {'fixture-recipe.json': 'start snapshot'}
        archive = self.root / 'stable.bin'
        with patch.object(builder, 'load_chains', return_value=(chains, [])), \
                patch.object(builder, 'recipe_inputs', side_effect=[initial.copy(), initial.copy(), {'later': 'must not be certified'}]) as inputs:
            report = builder.build(modules, archive, version)
        self.assertEqual(2, inputs.call_count)
        receipt = json.loads(archive.with_suffix('.inputs.json').read_text(encoding='utf-8'))
        self.assertEqual(initial, receipt['inputs'])
        self.assertEqual(report['sha256'], receipt['archive_sha256'])
        with tarfile.open(archive, 'r:gz') as built:
            self.assertEqual(b'console.log("after");\n', built.extractfile(builder.PREFIX + '/lib/bin.js').read())

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
