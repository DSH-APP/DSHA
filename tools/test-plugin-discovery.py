#!/usr/bin/env python3
"""隔离目录验证 Web/终端插件发现、停用再启用和安装来源优先级。"""
import contextlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets'


class DiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {'DSHA_TEST_ROOT': str(self.root), 'DSH_HOME': '/root/.dsh'})
        self.env.start()
        spec = importlib.util.spec_from_file_location('plugin_manager_test', ASSETS / 'plugin-manager.py')
        self.manager = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.manager)
        self.builtin = self.manager.builtin
        self.manifest = self.put('root/.dsh/profiles/web/package.json',
                                 {'dependencies': {}, 'dsh': {'profile': {'bundles': list(self.builtin.OFFICIAL_BUNDLES)}}})

    def tearDown(self):
        self.env.stop()
        self.temp.cleanup()

    def put(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data) if isinstance(data, dict) else data, encoding='utf-8')
        return path

    def plugin(self, base, name='test-plugin', version='1.0.0', bundle=True):
        directory = base + '/' + name
        package = dict(name=name, version=version)
        if bundle:
            package['dsh'] = {'bundle': {'patch': ['cordis.patch.yml']}}
        self.put(directory + '/package.json', package)
        self.put(directory + '/cordis.patch.yml', '[]\n')
        return self.root / directory

    def items(self):
        with patch.object(self.manager, 'result') as result:
            self.assertEqual(0, self.manager.cmd_list())
            return {item['name']: item for item in result.call_args.kwargs['items']}

    def test_finds_every_supported_location_and_filters_plain_dependencies(self):
        locations = ['root/.dsh/profiles/web/node_modules', 'root/.dsh/node_modules',
                     'root/node_modules', 'usr/local/lib/node_modules', 'root/.dsh/profiles/tui/node_modules',
                     'root/.dsh/plugin-src', 'root/.dsh/profiles/node_modules']
        for index, location in enumerate(locations):
            self.plugin(location, '@author/plugin-' + str(index))
            self.plugin(location, 'plain-library-' + str(index), bundle=False)
        found = self.items()
        for index in range(len(locations)):
            item = found['@author/plugin-' + str(index)]
            self.assertTrue(item['available']); self.assertTrue(item['detected'])
            self.assertFalse(item['enabled']); self.assertFalse(item['deletable'])
            self.assertNotIn('plain-library-' + str(index), found)

    def test_real_web_update_wins_over_old_imported_copy(self):
        self.plugin('root/.dsh/plugin-src', version='1.0.0')
        web = self.plugin('root/.dsh/profiles/web/node_modules', version='2.0.0')
        self.assertEqual(str(web.resolve()), self.manager.resolve_plugin_dir('test-plugin'))
        self.assertEqual('2.0.0', self.items()['test-plugin']['version'])

    def test_toggle_keeps_npm_specification_and_files(self):
        web = self.plugin('root/.dsh/profiles/web/node_modules')
        doc = json.loads(self.manifest.read_text())
        doc['dependencies']['test-plugin'] = '^1.0.0'
        doc['dsh']['profile']['bundles'].append('test-plugin')
        self.manifest.write_text(json.dumps(doc))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.builtin.disable_plugin('test-plugin'))
            self.assertTrue((web / 'package.json').is_file())
            self.assertFalse(self.items()['test-plugin']['enabled'])
            self.assertEqual(0, self.builtin.enable_plugin('test-plugin'))
        after = json.loads(self.manifest.read_text())
        self.assertEqual('^1.0.0', after['dependencies']['test-plugin'])
        self.assertIn('test-plugin', after['dsh']['profile']['bundles'])

    def test_ignores_invalid_manifest_and_patch_outside_package(self):
        root = self.plugin('root/.dsh/node_modules')
        self.put('root/.dsh/node_modules/cordis.patch.yml', '[]')
        self.put(str(root.relative_to(self.root)) + '/package.json',
                 {'name': 'test-plugin', 'dsh': {'bundle': {'patch': '../cordis.patch.yml'}}})
        self.assertNotIn('test-plugin', self.items())
        self.put(str(root.relative_to(self.root)) + '/package.json', '{bad json')
        self.assertNotIn('test-plugin', self.items())

    def test_core_inventory_remains_available_but_is_marked_internal(self):
        items = self.items()
        for name in self.builtin.OFFICIAL_BUNDLES + ('dsh-app-integration',):
            self.assertTrue(items[name]['internal'])
        visible = [item for item in items.values() if not item['internal']]
        self.assertEqual(4, len(visible))

    def test_symlinked_shared_runtime_is_not_a_user_plugin(self):
        runtime = self.plugin('usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules')
        link = self.root / 'root/.dsh/node_modules/test-plugin'
        link.parent.mkdir(parents=True)
        try:
            link.symlink_to(runtime, target_is_directory=True)
        except OSError as error:
            self.skipTest('此宿主无创建符号链接权限：' + str(error))
        self.assertNotIn('test-plugin', self.items())

    def test_detected_profile_plugin_can_be_enabled_without_deleting_original(self):
        probe = self.root / 'link-permission-probe'
        try:
            probe.symlink_to(self.root / 'root', target_is_directory=True)
            probe.unlink()
        except OSError as error:
            if getattr(error, 'winerror', None) == 1314:
                self.skipTest('宿主没有创建符号链接权限')
            raise
        original = self.plugin('root/.dsh/profiles/tui/node_modules')
        with contextlib.redirect_stdout(io.StringIO()):
            code = self.builtin.enable_plugin('test-plugin')
        self.assertEqual(0, code)
        item = self.items()['test-plugin']
        self.assertTrue(item['enabled']); self.assertTrue(item['deletable'])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.manager.cmd_delete('test-plugin'))
        self.assertTrue((original / 'package.json').is_file())
        self.assertFalse(self.items()['test-plugin']['enabled'])

    @unittest.skipIf(os.name == 'nt', '真实 ESM 与运行时软链在 Linux 验证')
    def test_relative_link_migration_preserves_unrelated_entities(self):
        target = self.plugin('root', 'dsha-app-integration')
        modules = self.root / 'root/.dsh/profiles/web/node_modules'
        modules.mkdir(parents=True, exist_ok=True)
        link = modules / 'dsh-app-integration'
        link.symlink_to(target, target_is_directory=True)
        self.assertTrue(self.builtin.ensure_symlink('dsh-app-integration', '/root/dsha-app-integration'))
        self.assertFalse(os.path.isabs(os.readlink(link)))
        self.assertEqual(target.resolve(), link.resolve())
        self.assertFalse(self.builtin.ensure_symlink('dsh-app-integration', '/root/dsha-app-integration'))
        foreign = self.plugin('root', 'foreign')
        unrelated = modules / 'unrelated'
        unrelated.symlink_to(foreign, target_is_directory=True)
        self.assertFalse(self.builtin.ensure_relative_link(str(unrelated), str(target)))
        self.assertEqual(foreign.resolve(), unrelated.resolve())
        entity = modules / 'entity'; entity.mkdir(); (entity / 'user-file').write_text('keep')
        self.assertFalse(self.builtin.ensure_relative_link(str(entity), str(target)))
        self.assertEqual('keep', (entity / 'user-file').read_text())
        # 原子替换失败时旧链接仍可用，也没有留下半成品。
        link.unlink(); link.symlink_to(target, target_is_directory=True)
        with patch.object(self.builtin.os, 'replace', side_effect=OSError('fixture')):
            self.assertFalse(self.builtin.ensure_symlink('dsh-app-integration', '/root/dsha-app-integration'))
        self.assertTrue(link.is_symlink()); self.assertEqual(target.resolve(), link.resolve())
        self.assertEqual([], list(modules.glob('*.dsha-link-*')))

    @unittest.skipIf(os.name == 'nt', '真实 ESM 与运行时软链在 Linux 验证')
    def test_managed_root_plugin_resolves_same_peer_as_imported_plugin(self):
        runtime = 'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules'
        self.put(runtime + '/@deepseek-ai/dsh-llm/package.json',
                 {'name': '@deepseek-ai/dsh-llm', 'type': 'module', 'exports': './index.js'})
        self.put(runtime + '/@deepseek-ai/dsh-llm/index.js', 'export default {};')
        self.put('root/dsha-device-shell-guide/package.json', {'name': 'dsh-device-shell-guide', 'type': 'module'})
        source = self.put('root/dsha-device-shell-guide/index.js',
                          "import peer from '@deepseek-ai/dsh-llm';export default peer;")
        imported = self.put('root/.dsh/plugin-src/example/index.mjs',
                            "import peer from '@deepseek-ai/dsh-llm';export default peer;")
        self.assertGreater(self.builtin.ensure_runtime_modules(), 0)
        self.assertEqual(0, self.builtin.ensure_runtime_modules())
        links = [self.root / 'root/dsha-device-shell-guide/node_modules',
                 self.root / 'root/.dsh/node_modules/@deepseek-ai/dsh-llm']
        for link in links:
            self.assertFalse(os.path.isabs(os.readlink(link)))
            target = link.resolve(); link.unlink(); link.symlink_to(target, target_is_directory=True)
        self.assertEqual(len(links), self.builtin.ensure_runtime_modules())
        self.assertEqual(0, self.builtin.ensure_runtime_modules())
        for link in links:
            self.assertFalse(os.path.isabs(os.readlink(link)))
        node = os.environ.get('DSHA_TEST_NODE') or shutil.which('node')
        probe = 'import a from ' + json.dumps(source.as_uri()) + ';import b from ' + json.dumps(imported.as_uri()) + ';if(a!==b)throw Error("duplicate peer");'
        result = subprocess.run([node, '--input-type=module', '-e', probe], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
