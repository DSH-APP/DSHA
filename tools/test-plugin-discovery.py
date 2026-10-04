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
import tarfile
import types
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
            package['dsh'] = {'bundle': {'patch': 'cordis.patch.yml'}}
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

    def test_update_query_capability_is_independent_of_delete_permission(self):
        directory = self.plugin('root/node_modules', '@author/detected')
        package = json.loads((directory / 'package.json').read_text())
        package['repository'] = {'url': 'https://github.com/author/detected'}
        (directory / 'package.json').write_text(json.dumps(package))
        found = self.items()['@author/detected']
        self.assertTrue(found['updatable']); self.assertFalse(found['deletable'])
        self.assertTrue(found['detected'])
        package['repository']['url'] = 'https://example.invalid/fixed.tgz'
        (directory / 'package.json').write_text(json.dumps(package))
        self.assertFalse(self.items()['@author/detected']['updatable'])
        for source, expected in [('npm:@author/detected@1.0.0', True),
                                 ('https://github.com/author/detected/releases/latest', True),
                                 ('https://github.com/author/detected/tree/main/subdir', True),
                                 ('https://github.com/author/detected/archive/sha.tgz', False),
                                 ('restored:original-dependency-group', False), ('', False)]:
            self.assertEqual(expected, self.manager.lifecycle().can_check_updates(source), source)

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

    def test_runtime_optional_bundle_resolves_and_toggles_without_fake_user_installation(self):
        name = '@deepseek-ai/dsh-experimental-fixture-bundle'
        runtime = self.plugin('usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules', name,
                              version='0.2.0-rc.2')
        doc = json.loads(self.manifest.read_text())
        doc['dsh']['profile']['bundles'].append(name)
        self.manifest.write_text(json.dumps(doc))
        item = self.items()[name]
        self.assertTrue(item['available']); self.assertTrue(item['runtimeProvided'])
        self.assertFalse(item['deletable']); self.assertTrue(item['exportable'])
        self.assertEqual('0.2.0-rc.2', item['version'])
        self.assertEqual(runtime.resolve(), Path(self.manager.resolve_plugin_dir(name)).resolve())
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.builtin.disable_plugin(name))
            self.assertFalse(self.items()[name]['enabled'], 'disabled optional bundle must remain visible')
            self.assertEqual(0, self.builtin.enable_plugin(name))
        after = json.loads(self.manifest.read_text())
        self.assertIn(name, after['dsh']['profile']['bundles'])
        self.assertNotIn(name, after['dependencies'])
        self.assertFalse((self.manifest.parent / 'node_modules' / name).exists())
        with self.assertRaisesRegex(ValueError, '不能删除'):
            self.manager.cmd_delete(name)
        self.assertTrue((runtime / 'package.json').is_file())
        self.put(str(runtime.relative_to(self.root)) + '/node_modules/unrelated-system-library/private-byte', 'do not sweep runtime')
        with patch.object(self.manager, 'result') as result, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.manager.cmd_export(json.dumps([name]), '/root/optional.tgz'))
            self.assertEqual([name], result.call_args.kwargs['runtimeSources'])
        with tarfile.open(self.root / 'root/optional.tgz') as archive:
            exported = archive.getnames()
            self.assertTrue(any(item.endswith('/package.json') for item in exported))
            self.assertFalse(any('node_modules' in item or 'private-byte' in item for item in exported))

    def test_refresh_is_read_only_and_discovers_only_once(self):
        self.plugin('root/.dsh/profiles/web/node_modules')
        before = self.manifest.read_bytes()
        with patch.object(self.builtin, 'register', side_effect=AssertionError('refresh must not register')):
            with patch.object(self.builtin, 'discover_plugins', wraps=self.builtin.discover_plugins) as discover:
                with patch('sys.argv', ['plugin-manager.py', 'refresh']), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0, self.manager.main())
                self.assertEqual(1, discover.call_count)
        self.assertEqual(before, self.manifest.read_bytes())

    def test_busy_guest_lock_has_a_budget_and_never_terminates_another_process(self):
        fake=types.SimpleNamespace(LOCK_EX=1,LOCK_NB=2,LOCK_UN=8,flock=lambda *_:(_ for _ in ()).throw(BlockingIOError()))
        with patch.object(self.builtin.os,'name','posix'),patch.dict('sys.modules',{'fcntl':fake}):
            with self.assertRaisesRegex(TimeoutError,'PLUGIN_LOCK_TIMEOUT'):
                with self.builtin.operation_lock(timeout=0):self.fail('lock must not enter')

    def test_install_anchor_runtime_bundle_wins_over_same_name_user_copy(self):
        name = '@deepseek-ai/dsh-experimental-shadow-fixture'
        runtime = self.plugin('usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules', name, '0.2.0-rc.2')
        user = self.plugin('root/.dsh/profiles/web/node_modules', name, '9.0.0-user-edit')
        original = self.put(str(user.relative_to(self.root)) + '/user-edit.js', 'preserve my shadowed edit')
        doc = json.loads(self.manifest.read_text())
        doc['dependencies'][name] = '9.0.0-user-edit'
        doc['dsh']['profile']['bundles'].append(name)
        self.manifest.write_text(json.dumps(doc))
        item = self.items()[name]
        self.assertEqual(runtime.resolve(), Path(self.manager.resolve_plugin_dir(name)).resolve())
        self.assertEqual(runtime.resolve(), Path(self.builtin.entity_dir(name)).resolve())
        self.assertTrue(item['runtimeProvided']); self.assertTrue(item['builtin'])
        self.assertEqual('0.2.0-rc.2', item['version']); self.assertFalse(item['deletable'])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.builtin.disable_plugin(name))
            self.assertEqual(0, self.builtin.enable_plugin(name))
        self.assertEqual('preserve my shadowed edit', original.read_text())
        self.assertEqual('9.0.0-user-edit', json.loads((user / 'package.json').read_text())['version'])
        self.assertEqual('9.0.0-user-edit', json.loads(self.manifest.read_text())['dependencies'][name])

    def test_enable_switch_does_not_hash_entire_dependency_graph(self):
        self.plugin('root/.dsh/profiles/web/node_modules')
        with patch.object(self.manager.dependencies(), 'current', side_effect=AssertionError('defer graph to load')):
            with patch('sys.argv', ['plugin-manager.py', 'enable-list', 'test-plugin']), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, self.manager.main())
        entry = self.manager.lifecycle().activation_state()['entries']['test-plugin']
        self.assertEqual('queued', entry['status']); self.assertEqual('', entry['fingerprint'])

    def test_combined_disable_and_delete_report_real_final_state_without_touching_siblings(self):
        web=self.plugin('root/.dsh/profiles/web/node_modules')
        sibling=self.plugin('root/.dsh/profiles/web/node_modules','sibling-plugin')
        doc=json.loads(self.manifest.read_text())
        doc['dependencies']={'test-plugin':'1.0.0','sibling-plugin':'1.0.0'}
        doc['dsh']['profile']['bundles']+=['test-plugin','sibling-plugin']
        self.manifest.write_text(json.dumps(doc))
        with patch('sys.argv',['plugin-manager.py','disable-list','test-plugin']), patch.object(self.manager,'result') as result, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,self.manager.main())
            rows={r['name']:r for r in result.call_args.kwargs['items']}
            self.assertFalse(rows['test-plugin']['enabled']);self.assertTrue(rows['sibling-plugin']['enabled'])
            self.assertTrue((web/'package.json').is_file())
        with patch('sys.argv',['plugin-manager.py','enable-list','test-plugin']), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,self.manager.main(),'合并入口应可直接启用第三方插件')
            self.assertEqual('queued',self.manager.lifecycle().activation_state()['entries']['test-plugin']['status'])
        with patch('sys.argv',['plugin-manager.py','delete-list','test-plugin']), patch.object(self.manager,'result') as result, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,self.manager.main())
            self.assertNotIn('test-plugin',[r['name'] for r in result.call_args.kwargs['items']])
            self.assertFalse(web.exists());self.assertTrue((sibling/'package.json').is_file())

    def test_ignores_invalid_manifest_and_patch_outside_package(self):
        root = self.plugin('root/.dsh/node_modules')
        self.put('root/.dsh/node_modules/cordis.patch.yml', '[]')
        self.put(str(root.relative_to(self.root)) + '/package.json',
                 {'name': 'test-plugin', 'version': '1.0.0', 'dsh': {'bundle': {'patch': '../cordis.patch.yml'}}})
        self.assertNotIn('test-plugin', self.items())
        self.put(str(root.relative_to(self.root)) + '/package.json', '{bad json')
        self.assertNotIn('test-plugin', self.items())

    def test_array_patch_is_validated_before_real_dsh_start(self):
        root = self.plugin('root/.dsh/node_modules')
        self.put(str(root.relative_to(self.root)) + '/package.json',
                 {'name': 'test-plugin', 'version': '1.0.0', 'dsh': {'bundle': {'patch': ['cordis.patch.yml', 'cordis.patch.yml']}}})
        self.assertIn('test-plugin', self.items())
        self.assertEqual('test-plugin', self.manager.plugin_package(str(root))['name'])
        self.put(str(root.relative_to(self.root)) + '/package.json',
                 {'name': 'test-plugin', 'version': '1.0.0', 'dsh': {'bundle': {'patch': ['cordis.patch.yml', '../outside.yml']}}})
        with self.assertRaisesRegex(ValueError, '越界路径'):
            self.manager.plugin_package(str(root))

    def test_old_builtin_file_cannot_shrink_signed_minimum_or_enable_name_spoof(self):
        self.put('root/dsha-builtin.txt', 'dsh-web-mobile\n../../fake\n')
        names = self.builtin.builtin_names()
        self.assertTrue(set(self.builtin.DEFAULT_BUILTINS).issubset(names))
        self.assertNotIn('../../fake', names)
        spoof = self.plugin('incoming', 'dsh-web-mobile', version='999.0.0')
        with self.assertRaisesRegex(ValueError, '签名 APK'):
            self.manager.plugin_package(str(spoof))

    def test_old_profile_builtin_entity_is_quarantined_and_current_apk_entity_wins(self):
        old = self.plugin('root/.dsh/profiles/web/node_modules', 'dsh-web-mobile', version='1.0.0')
        (old / 'user-byte').write_text('preserve old bytes', encoding='utf-8')
        current = self.plugin('root', 'dsha-web-mobile', version='2.0.0')
        self.assertTrue(self.builtin.quarantine_builtin_entity('dsh-web-mobile', str(old)))
        retained = list((self.root / 'root/.dsha-system-plugin-quarantine').glob('*/content/user-byte'))
        self.assertEqual(1, len(retained)); self.assertEqual('preserve old bytes', retained[0].read_text())
        self.assertEqual(current.resolve(), Path(self.manager.resolve_plugin_dir('dsh-web-mobile')).resolve())

    def test_system_plugin_delete_and_export_stay_protected_with_stale_manifest(self):
        self.put('root/dsha-builtin.txt', 'dsh-device-shell-guide\n')
        current = self.plugin('root', 'dsha-web-mobile', version='2.0.0')
        with self.assertRaisesRegex(ValueError, '不能删除'):
            self.manager.cmd_delete('dsh-web-mobile')
        with self.assertRaisesRegex(ValueError, '签名 APK'):
            self.manager.cmd_export(json.dumps(['dsh-web-mobile']), str(self.root / 'export.tar.gz'))
        self.assertTrue((current / 'package.json').is_file())

    def test_missing_current_apk_entity_blocks_start_and_quarantines_old_alias(self):
        old = self.plugin('root/.dsh/profiles/web/node_modules', 'dsh-web-mobile', version='1.0.0')
        doc = json.loads(self.manifest.read_text())
        doc['dependencies']['dsh-web-mobile'] = 'link:/old/profile/copy'
        doc['dsh']['profile']['bundles'].append('dsh-web-mobile')
        self.manifest.write_text(json.dumps(doc), encoding='utf-8')
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1, self.builtin.register())
        self.assertIn('BUILTIN_REGISTER_FAIL', output.getvalue())
        self.assertFalse(old.exists())
        self.assertEqual(1, len(list((self.root / 'root/.dsha-system-plugin-quarantine').glob('*/content'))))
        after = json.loads(self.manifest.read_text())
        self.assertNotIn('dsh-web-mobile', after['dependencies'])
        self.assertNotIn('dsh-web-mobile', after['dsh']['profile']['bundles'])

    def test_missing_signed_entity_does_not_skip_healthy_registration(self):
        current=self.plugin('root','dsha-device-shell-guide','1.0.0')
        # Host cannot create symlinks; use a real isolated copy as the stand-in,
        # then report its resolution through the same prepared managed path.
        def link(name,directory):
            target=self.root/'root/.dsh/profiles/web/node_modules'/name
            shutil.copytree(self.root/'root/dsha-device-shell-guide',target,dirs_exist_ok=True)
            return True
        with patch.object(self.builtin,'builtin_names',return_value=['dsh-device-shell-guide','dsh-web-mobile']),patch.object(self.builtin,'ensure_symlink',side_effect=link),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1,self.builtin.register())
        after=json.loads(self.manifest.read_text())
        self.assertIn('dsh-device-shell-guide',after['dsh']['profile']['bundles']);self.assertNotIn('dsh-web-mobile',after['dsh']['profile']['bundles'])
        self.assertIn('BUILTIN_REGISTER_PARTIAL',output.getvalue());self.assertTrue((current/'package.json').is_file())

    def test_disabled_marker_wins_over_stale_bundle_dependency_and_entity(self):
        old = self.plugin('root/.dsh/profiles/web/node_modules', 'dsh-web-mobile', version='1.0.0')
        (old / 'user-byte').write_text('preserve disabled old bytes', encoding='utf-8')
        self.plugin('root', 'dsha-web-mobile', version='2.0.0')
        marker = self.put('root/.dsh/profiles/web/node_modules/dsh-web-mobile.disabled', '')
        doc = json.loads(self.manifest.read_text())
        doc['dependencies']['dsh-web-mobile'] = 'link:/old/profile/copy'
        doc['dsh']['profile']['bundles'] += ['dsh-web-mobile', 'dsh-web-mobile']
        self.manifest.write_text(json.dumps(doc), encoding='utf-8')

        with patch.object(self.builtin, 'builtin_names', return_value=['dsh-web-mobile']):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0, self.builtin.register())

        self.assertIn('BUILTIN_REGISTER_OK', output.getvalue())
        self.assertTrue(marker.is_file(), 'register must preserve the explicit disabled choice')
        self.assertFalse(old.exists(), 'the stale profile entity must leave the loading path')
        retained = list((self.root / 'root/.dsha-system-plugin-quarantine').glob('*/content/user-byte'))
        self.assertEqual(1, len(retained))
        self.assertEqual('preserve disabled old bytes', retained[0].read_text(encoding='utf-8'))
        after = json.loads(self.manifest.read_text(encoding='utf-8'))
        self.assertNotIn('dsh-web-mobile', after['dsh']['profile']['bundles'])
        self.assertEqual('link:/root/dsha-web-mobile', after['dependencies']['dsh-web-mobile'])

    def test_core_inventory_remains_available_but_is_marked_internal(self):
        items = self.items()
        for name in self.builtin.OFFICIAL_BUNDLES + ('dsh-app-integration',):
            self.assertTrue(items[name]['internal'])
        visible = [item for item in items.values() if not item['internal']]
        self.assertEqual({'dsh-device-shell-guide','dsh-task-notifier','dsh-status-overlay','dsh-web-mobile',
                          'dsh-computer-use-android','dsh-auto-review','dsh-tool-vscreen'}, {item['name'] for item in visible})

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
