#!/usr/bin/env python3
"""隔离目录验证 Web/终端插件发现、停用再启用和安装来源优先级。"""
import contextlib
import importlib.util
import io
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path
import tempfile
import tarfile
import types
import threading
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

    def authenticate_runtime(self, name, directory):
        proof = self.put('root/.dsh/managed-package-proofs.json',
                         {'version': 1, 'packages': {name: self.builtin.package_content_proof(directory)}})
        os.environ['DSHA_MANAGED_PACKAGE_PROOFS_SHA256'] = hashlib.sha256(proof.read_bytes()).hexdigest()

    def runtime_alias_fixture(self, library_count=120):
        packages = {}
        for index in range(library_count + 2):
            name = '@fixture/' + ('bundle-' + str(index) if index < 2 else 'library-' + str(index))
            directory = self.plugin('usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules', name, bundle=index < 2)
            (directory / 'payload.bin').write_bytes(b'original-package-bytes\n' * 400)
            packages[name] = directory
        proof = self.put('root/.dsh/managed-package-proofs.json',
                         {'version': 1, 'packages': {name: self.builtin.package_content_proof(directory)
                                                    for name, directory in packages.items()}})
        os.environ['DSHA_MANAGED_PACKAGE_PROOFS_SHA256'] = hashlib.sha256(proof.read_bytes()).hexdigest()
        aliases = []
        for base in ('root/.dsh/node_modules', 'usr/local/lib/node_modules'):
            for name, directory in packages.items():
                alias = self.root / base / name
                alias.parent.mkdir(parents=True, exist_ok=True)
                aliases.append([str(directory), str(alias)])
        if os.name == 'nt':
            # Directory junctions are real aliases and do not need Windows'
            # optional symbolic-link privilege. Input travels through stdin.
            run = subprocess.run(['node', '-e',
                'for(const [target,alias] of JSON.parse(require("fs").readFileSync(0,"utf8")))require("fs").symlinkSync(target,alias,"junction")'],
                input=json.dumps(aliases), text=True, capture_output=True)
            self.assertEqual(0, run.returncode, run.stderr)
        else:
            for directory, alias in aliases:
                os.symlink(directory, alias, target_is_directory=True)
        doc = json.loads(self.manifest.read_text())
        selected = [name for name in packages if name.startswith('@fixture/bundle-')]
        doc['dependencies'] = {name: '1.0.0' for name in selected}
        doc['dsh']['profile']['bundles'] += selected
        self.manifest.write_text(json.dumps(doc))
        return packages, selected

    def test_only_real_bundles_are_hashed_once_per_command_despite_duplicate_runtime_aliases(self):
        packages, selected = self.runtime_alias_fixture()
        with patch.object(self.builtin, 'managed_package_proofs', wraps=self.builtin.managed_package_proofs) as proofs, \
                patch.object(self.builtin, 'package_content_proof', wraps=self.builtin.package_content_proof) as hashes:
            rows = self.items()
            self.assertEqual(1, proofs.call_count, 'authenticate the table once for this query')
            self.assertEqual(2, hashes.call_count, 'two actual bundles, not 122 packages x two aliases')
            self.assertEqual({str(packages[name]) for name in selected}, {str(call.args[0]) for call in hashes.call_args_list})
        for name in selected:
            self.assertTrue(rows[name]['runtimeProvided'])
        for name in set(packages) - set(selected):
            self.assertNotIn(name, rows)
        # The next query must rehash, even when only a deep file changed.
        (packages[selected[0]] / 'payload.bin').write_bytes(b'user override after the first query')
        with patch.object(self.builtin, 'package_content_proof', wraps=self.builtin.package_content_proof) as hashes:
            rows = self.items()
            self.assertEqual(2, hashes.call_count)
            self.assertFalse(rows[selected[0]]['runtimeProvided'])
            self.assertTrue(rows[selected[1]]['runtimeProvided'])

    def test_refresh_does_not_take_profile_writer_lock_and_rejects_concurrent_intent_changes(self):
        name = '@deepseek-ai/dsh-experimental-voice-input-bundle'
        self.plugin('usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules', name)
        started, release = threading.Event(), threading.Event()
        original = self.builtin.discover_plugins
        failures = []
        def paused(*args, **kwargs):
            started.set()
            self.assertTrue(release.wait(5), 'query test release was not signalled')
            return original(*args, **kwargs)
        def read():
            try:
                self.manager.cmd_list()
            except Exception as error:
                failures.append(error)
        with patch.object(self.builtin, 'discover_plugins', side_effect=paused), \
                patch.object(self.manager, 'result') as result:
            thread = threading.Thread(target=read)
            thread.start()
            try:
                self.assertTrue(started.wait(5))
                # This actual writer completes while the slow query is paused.
                state = self.manager.set_plugin_state(name, True)
                self.assertIs(state['enabled'], True)
                self.assertIs(state['intentVerified'], True)
            finally:
                release.set(); thread.join(5)
            self.assertFalse(thread.is_alive())
            self.assertEqual(1, len(failures))
            self.assertIn('列表读取期间变化', str(failures[0]))
            result.assert_not_called()
        with self.builtin.operation_lock():
            lock = Path(self.builtin.local(self.builtin.MANIFEST) + '.lock')
            before = lock.read_bytes()
            with patch.object(self.manager, 'result') as result:
                with self.assertRaisesRegex(ValueError, '配置正在修改'):
                    self.manager.cmd_list()
                self.assertEqual(before, lock.read_bytes(), 'read query must leave the existing writer lock alone')
                result.assert_not_called()

    def test_short_state_commands_read_back_intent_without_scan_or_content_proof(self):
        name = '@deepseek-ai/dsh-experimental-voice-input-bundle'
        self.plugin('usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules', name)
        with patch.object(self.manager, 'cmd_list', side_effect=AssertionError('mutation must not query')), \
                patch.object(self.builtin, 'discover_plugins', side_effect=AssertionError('mutation must not discover')), \
                patch.object(self.builtin, 'package_content_proof', side_effect=AssertionError('defer content to actual load')):
            for command, enabled in [('enable-state', True), ('disable-state', False)]:
                with patch('sys.argv', ['plugin-manager.py', command, name]), \
                        patch.object(self.manager, 'result') as result, contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0, self.manager.main())
                    self.assertEqual('ok', result.call_args.args[0])
                    self.assertEqual(name, result.call_args.kwargs['name'])
                    self.assertIs(enabled, result.call_args.kwargs['enabled'])
                    self.assertIs(True, result.call_args.kwargs['intentVerified'])
                    self.assertNotIn('items', result.call_args.kwargs)
                saved = json.loads(self.manifest.read_text())['dsh']['profile']['bundles']
                self.assertEqual(enabled, name in saved)
                self.assertEqual(not enabled, Path(self.builtin.marker_path(name)).is_file())
        with patch.object(self.builtin, 'enable_plugin', return_value=0), \
                patch('sys.argv', ['plugin-manager.py', 'enable-state', name]), \
                patch.object(self.manager, 'result') as result:
            self.assertEqual(1, self.manager.main(), 'a no-op backend cannot claim an enable acknowledgement')
            self.assertEqual('error', result.call_args.args[0])
            self.assertNotIn('intentVerified', result.call_args.kwargs)

    def test_read_query_cancellation_reaches_content_scan_and_does_not_mutate_intent(self):
        name = '@fixture/cancellable-bundle'
        directory = self.plugin('usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules', name)
        self.authenticate_runtime(name, directory)
        doc = json.loads(self.manifest.read_text()); doc['dsh']['profile']['bundles'].append(name)
        self.manifest.write_text(json.dumps(doc)); before = self.manifest.read_bytes()
        key = 'b' * 32
        cancel = self.root / ('root/.dsha-plugin-task-' + key + '.cancel')
        original = self.builtin.package_content_proof
        def cancelled(*args, **kwargs):
            cancel.touch()
            return original(*args, **kwargs)
        with patch.dict(os.environ, {'DSHA_PLUGIN_TASK': key}), \
                patch.object(self.builtin, 'package_content_proof', side_effect=cancelled), \
                patch('sys.argv', ['plugin-manager.py', 'refresh']), \
                patch.object(self.manager, 'result') as result:
            self.assertEqual(1, self.manager.main())
            self.assertEqual('cancelled', result.call_args.args[0])
            progress = json.loads((self.root / ('root/.dsha-plugin-task-' + key + '.json')).read_text(encoding='utf-8'))
            self.assertIs(True, progress['cancellable'])
        self.assertEqual(before, self.manifest.read_bytes())

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
        self.authenticate_runtime(name, runtime)
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
        self.authenticate_runtime(name, runtime)
        user = self.plugin('root/.dsh/profiles/web/node_modules', name, '9.0.0-user-edit')
        original = self.put(str(user.relative_to(self.root)) + '/user-edit.js', 'preserve my shadowed edit')
        doc = json.loads(self.manifest.read_text())
        doc['dependencies'][name] = '9.0.0-user-edit'
        doc['dsh']['profile']['bundles'].append(name)
        self.manifest.write_text(json.dumps(doc))
        item = self.items()[name]
        self.assertEqual(runtime.resolve(), Path(self.manager.resolve_plugin_dir(name)).resolve())
        self.assertEqual(runtime.resolve(), Path(self.builtin.entity_dir(name)).resolve())
        self.assertTrue(item['runtimeProvided']); self.assertFalse(item['builtin'])
        self.assertEqual('dsh-managed', item['origin'])
        self.assertEqual('0.2.0-rc.2', item['version']); self.assertFalse(item['deletable'])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.builtin.disable_plugin(name))
            self.assertEqual(0, self.builtin.enable_plugin(name))
        self.assertEqual('preserve my shadowed edit', original.read_text())
        self.assertEqual('9.0.0-user-edit', json.loads((user / 'package.json').read_text())['version'])
        self.assertEqual('9.0.0-user-edit', json.loads(self.manifest.read_text())['dependencies'][name])

    def test_scope_and_prefix_do_not_make_ai_installed_plugins_system_owned(self):
        for name in ('@deepseek-ai/dsh-user-created', 'dsh-user-created', '@author/dsh-custom'):
            with self.subTest(name=name):
                directory = self.plugin('root/.dsh/profiles/web/node_modules', name)
                self.assertEqual(name, self.manager.plugin_package(str(directory))['name'])
                doc = json.loads(self.manifest.read_text())
                doc['dependencies'][name] = '1.0.0'
                doc['dsh']['profile']['bundles'].append(name)
                self.manifest.write_text(json.dumps(doc))
                item = self.items()[name]
                self.assertFalse(item['builtin']); self.assertFalse(item['runtimeProvided'])
                self.assertEqual('user', item['origin']); self.assertTrue(item['deletable'])
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0, self.builtin.disable_plugin(name))
                    self.assertEqual(0, self.builtin.enable_plugin(name))
                    self.assertEqual(0, self.manager.cmd_delete(name))
                self.assertFalse(directory.exists())

    def test_runtime_location_mutable_dependencies_and_claims_are_not_apk_proof(self):
        name = '@deepseek-ai/dsh-user-added-in-runtime'
        directory = self.plugin('usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules', name)
        self.put('usr/local/lib/node_modules/@deepseek-ai/dsh/package.json',
                 {'name': '@deepseek-ai/dsh', 'dependencies': {name: '1.0.0'}})
        doc = json.loads(self.manifest.read_text()); doc['dsh']['profile']['bundles'].append(name)
        self.manifest.write_text(json.dumps(doc))
        item = self.items()[name]
        self.assertTrue(item['runtimeLocated']); self.assertFalse(item['runtimeProvided'])
        self.assertFalse(item['builtin']); self.assertEqual('user', item['origin'])
        self.assertIn('未验证', item['location'])
        self.authenticate_runtime(name, directory)
        self.assertTrue(self.items()[name]['runtimeProvided'])
        (directory / 'ai-edit.js').write_text('preserve this user override')
        self.assertFalse(self.items()[name]['runtimeProvided'])
        self.assertEqual('user', self.items()[name]['origin'])
        # A rewritten guest JSON cannot grant the current launcher's APK proof.
        self.put('root/.dsh/managed-package-proofs.json',
                 {'version': 1, 'packages': {name: self.builtin.package_content_proof(directory)}})
        self.assertFalse(self.items()[name]['runtimeProvided'])
        self.assertTrue((directory / 'ai-edit.js').is_file())

    def test_package_proof_rejects_directory_members_added_during_enumeration(self):
        name = '@deepseek-ai/dsh-ai-created'
        directory = self.plugin('usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules', name)
        self.authenticate_runtime(name, directory)
        original = self.builtin.os.listdir
        injected = False
        def inject(path):
            nonlocal injected
            members = original(path)
            if Path(path) == directory and not injected:
                injected = True
                (directory / 'user-private-draft.txt').write_text('must remain user data')
            return members
        with patch.object(self.builtin.os, 'listdir', side_effect=inject):
            self.assertFalse(self.builtin.managed_runtime_package(name, directory))
        self.assertTrue((directory / 'user-private-draft.txt').is_file())

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
                          'dsh-computer-use-android','@deepseek-ai/dsh-experimental-auto-review','dsh-tool-vscreen'}, {item['name'] for item in visible})

    def test_auto_review_web_off_survives_register_and_removes_both_aliases(self):
        aliases = self.builtin.AUTO_REVIEW_ALIASES
        self.plugin('root', 'dsha-auto-review')
        marker = self.put('root/.dsh/profiles/web/node_modules/dsh-auto-review.disabled', 'DSHA_WEB_AUTO_REVIEW_DISABLED_V1\n')
        doc = json.loads(self.manifest.read_text()); doc['dsh']['profile']['bundles'] += list(aliases)
        self.manifest.write_text(json.dumps(doc), encoding='utf8')
        with patch.object(self.builtin, 'builtin_names', return_value=['dsh-auto-review']), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.builtin.register())
            self.assertEqual(0, self.builtin.register())
        after = json.loads(self.manifest.read_text())['dsh']['profile']['bundles']
        self.assertFalse(set(aliases) & set(after)); self.assertEqual('DSHA_WEB_AUTO_REVIEW_DISABLED_V1\n', marker.read_text())

    def test_native_profile_writer_uses_web_pid_lock_for_entire_operation_and_releases_on_error(self):
        lock = Path(self.builtin.local(self.builtin.MANIFEST) + '.lock')
        with self.assertRaisesRegex(ValueError, 'owned failure'):
            with self.builtin.operation_lock(timeout=.1):
                self.assertEqual(str(os.getpid()) + '\n', lock.read_text(encoding='ascii'))
                raise ValueError('owned failure')
        self.assertFalse(lock.exists())

    def test_native_profile_writer_preserves_live_unknown_and_incomplete_web_locks(self):
        lock = Path(self.builtin.local(self.builtin.MANIFEST) + '.lock')
        before = self.manifest.read_bytes()
        for record in [str(os.getpid()) + '\n', '', 'unknown-holder', '0\n']:
            lock.write_text(record, encoding='ascii')
            with self.assertRaisesRegex(TimeoutError, 'PLUGIN_PROFILE_LOCK_TIMEOUT'):
                with self.builtin.operation_lock(timeout=.01):
                    self.fail('native operation entered through an existing Web lock')
            self.assertEqual(record, lock.read_text(encoding='ascii'))
            self.assertEqual(before, self.manifest.read_bytes())
        lock.unlink()

    def test_native_profile_writer_cancel_while_waiting_never_removes_web_lock(self):
        lock = Path(self.builtin.local(self.builtin.MANIFEST) + '.lock')
        record = str(os.getpid()) + '\n'; lock.write_text(record, encoding='ascii')
        calls = [0]
        def cancel():
            calls[0] += 1
            if calls[0] >= 3: raise self.manager.PluginCancelled('owned cancel')
        with self.assertRaises(self.manager.PluginCancelled):
            with self.builtin.profile_writer_lock(cancel, timeout=1):
                self.fail('cancelled writer entered')
        self.assertEqual(record, lock.read_text(encoding='ascii')); lock.unlink()

    def test_native_profile_writer_takes_only_proven_exited_child_pid_lock(self):
        import sys
        child = subprocess.Popen([sys.executable, '-c', 'pass'])
        child.wait(timeout=5)
        lock = Path(self.builtin.local(self.builtin.MANIFEST) + '.lock')
        lock.write_text(str(child.pid) + '\n', encoding='ascii')
        with self.builtin.profile_writer_lock(timeout=.5):
            self.assertEqual(str(os.getpid()) + '\n', lock.read_text(encoding='ascii'))
        self.assertFalse(lock.exists())

    def test_auto_review_native_disable_and_explicit_enable_share_both_aliases(self):
        aliases = self.builtin.AUTO_REVIEW_ALIASES
        self.plugin('root', 'dsha-auto-review')
        self.plugin('root/.dsh/profiles/web/node_modules', 'dsh-auto-review')
        doc = json.loads(self.manifest.read_text()); doc['dsh']['profile']['bundles'] += list(aliases)
        self.manifest.write_text(json.dumps(doc), encoding='utf8')
        with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(0, self.builtin.disable_plugin(aliases[0]))
        self.assertFalse(set(aliases) & set(json.loads(self.manifest.read_text())['dsh']['profile']['bundles']))
        self.assertTrue(self.builtin.is_disabled(aliases[1]))
        marker = Path(self.builtin.marker_path(aliases[0])); self.assertEqual('', marker.read_text())
        # Keep link creation platform independent; this isolates selection/marker state.
        shutil.copytree(self.root/'root/dsha-auto-review', self.root/'root/.dsh/profiles/web/node_modules/dsh-auto-review')
        with patch.object(self.builtin, 'ensure_symlink', return_value=False), patch.object(self.builtin.os.path, 'realpath', side_effect=lambda p: str(self.root/'root/dsha-auto-review') if str(p).replace('\\', '/').endswith('node_modules/dsh-auto-review') else os.path.abspath(p)), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.builtin.enable_plugin(aliases[0]))
        self.assertFalse(marker.exists()); self.assertIn(aliases[0], json.loads(self.manifest.read_text())['dsh']['profile']['bundles'])

    def test_auto_review_register_keeps_canonical_selection_without_wrapper_readdition(self):
        aliases = self.builtin.AUTO_REVIEW_ALIASES
        doc = {'dependencies': {}, 'dsh': {'profile': {'bundles': [aliases[0], aliases[1], 'other-plugin']}}}
        merged, added = self.builtin.merge_manifest(doc, {aliases[0]: '/root/dsha-auto-review'})
        self.assertEqual([aliases[1], 'other-plugin'], merged['dsh']['profile']['bundles'])
        self.assertNotIn(aliases[0], added)
        self.assertEqual('link:/root/dsha-auto-review', merged['dependencies'][aliases[0]])

    def test_auto_review_native_official_alias_disable_enable_retains_runtime_bytes(self):
        aliases = self.builtin.AUTO_REVIEW_ALIASES
        runtime = self.plugin('usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules', aliases[1])
        original = (runtime/'package.json').read_bytes()
        self.put('root/.dsh/profiles/web/node_modules/@deepseek-ai/dsh-experimental-auto-review.disabled', 'unknown previous native intent')
        self.assertTrue(self.builtin.is_disabled(aliases[0]))
        doc = json.loads(self.manifest.read_text()); doc['dsh']['profile']['bundles'] += list(aliases)
        self.manifest.write_text(json.dumps(doc), encoding='utf8')
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.builtin.disable_plugin(aliases[1]))
            self.assertFalse(set(aliases) & set(json.loads(self.manifest.read_text())['dsh']['profile']['bundles']))
            self.assertEqual(0, self.builtin.enable_plugin(aliases[1]))
        self.assertEqual([aliases[1]], [name for name in json.loads(self.manifest.read_text())['dsh']['profile']['bundles'] if name in aliases])
        self.assertFalse(any(Path(self.builtin.marker_path(alias)).exists() for alias in aliases))
        self.assertEqual(original, (runtime/'package.json').read_bytes())

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
