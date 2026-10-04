#!/usr/bin/env python3
"""Run the production descriptor against isolated changes, never the real worktree."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from runtime_input_contract import MANIFEST, ASSETS, JAVA, load, asset_paths, launcher_paths
ROOT = Path(__file__).resolve().parents[1]
module_spec = importlib.util.spec_from_file_location('descriptor', ROOT / 'tools/prepare-runtime-descriptor.py')
descriptor = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(descriptor)

class RuntimeInputContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='runtime-input-test-', dir=ROOT / 'app/build')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.spec = {'schema': 1, 'installs': [{'asset': 'plugin.py', 'target': 'root/.dsh/plugin.py', 'executable': False}], 'assetFiles': ['offline-rootfs.version'], 'assetTrees': ['web'], 'launcherSources': ['util/Policy.java'], 'launcherTrees': [JAVA + '/runtime', 'app/src/main/jniLibs']}
        self.write(MANIFEST, json.dumps(self.spec))
        for path, text in {ASSETS + '/plugin.py': 'pass\n', ASSETS + '/offline-rootfs.version': '10', ASSETS + '/web/compat.js': 'true;', JAVA + '/util/Policy.java': 'policy', JAVA + '/runtime/Runner.java': 'runner', 'app/src/main/jniLibs/arm64-v8a/liba.so': 'elf', 'tools/dsh-runtime/package.json': '{"dependencies":{"@deepseek-ai/dsh":"0.1.7-rc.2"}}'}.items():
            self.write(path, text)
    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf8')
    def identity(self):
        return descriptor.build_descriptor(self.root)['runtimeId']
    def test_every_selected_input_changes_identity(self):
        for path in asset_paths(self.root) + list(launcher_paths(self.root).values()):
            if path == self.root / MANIFEST:
                continue
            with self.subTest(path=path.relative_to(self.root).as_posix()):
                before, raw = self.identity(), path.read_bytes()
                path.write_bytes(raw + b'\nchanged')
                self.assertNotEqual(before, self.identity())
                path.write_bytes(raw)
    def test_new_launcher_and_deleted_tree_asset_invalidate(self):
        before = self.identity()
        self.write(JAVA + '/runtime/NewRunner.java', 'new mechanism')
        self.assertNotEqual(before, self.identity())
        before = self.identity()
        (self.root / ASSETS / 'web/compat.js').unlink()
        self.assertNotEqual(before, self.identity())
    def test_missing_installed_asset_fails_closed(self):
        (self.root / ASSETS / 'plugin.py').unlink()
        with self.assertRaises(ValueError):
            self.identity()
    def test_install_destination_is_part_of_identity(self):
        before = self.identity()
        self.spec['installs'][0]['target'] = 'root/.dsh/plugin-v2.py'
        self.write(MANIFEST, json.dumps(self.spec))
        self.assertNotEqual(before, self.identity())
    def test_generator_content_and_missing_source_are_identified(self):
        self.spec['generatorSources'] = ['tools/render.py']
        self.write('tools/render.py', 'first recipe')
        self.write(MANIFEST, json.dumps(self.spec))
        before=self.identity()
        self.write('tools/render.py', 'changed recipe')
        self.assertNotEqual(before,self.identity())
        (self.root/'tools/render.py').unlink()
        with self.assertRaises(ValueError):self.identity()
    def test_ui_and_apk_version_do_not_change_identity(self):
        before = self.identity()
        self.write(JAVA + '/ui/Page.java', 'UI only')
        self.write('app/build.gradle', 'versionCode 148')
        self.assertEqual(before, self.identity())
    def test_production_cold_probe_and_package_policy_are_identified(self):
        selected = launcher_paths(ROOT)
        for name in ('util/ColdInstallPlan.java', 'util/ColdInstallPackages.java'):
            self.assertIn(name, selected, 'Cold compatibility and package policy must invalidate old runtime proofs')
            self.assertEqual(selected[name], ROOT / JAVA / name)
    def test_invalid_recipe_rejected_before_install(self):
        for bad in ('../outside', '/absolute', 'root//duplicate', 'C:/windows', 'a\\b', 'a/./b'):
            with self.subTest(path=bad):
                self.spec['installs'][0]['target'] = bad
                self.write(MANIFEST, json.dumps(self.spec))
                with self.assertRaises(ValueError):
                    load(self.root)

if __name__ == '__main__':
    unittest.main()

