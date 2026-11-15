import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ASSETS = Path(os.environ.get('DSHA_TEST_ASSETS', str(Path(__file__).resolve().parents[1] / 'app/src/main/assets')))

class RecoveryTests(unittest.TestCase):
    def register_module(self):
        spec = importlib.util.spec_from_file_location(
            'register_locks', ASSETS / 'register-builtin-plugins.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    def test_production_guest_paths_remain_absolute_from_root_working_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root_working_directory = Path(temporary) / 'root'
            decoy_marker = root_working_directory / 'usr/local/share/dsha/managed-assets-v2'
            decoy_marker.parent.mkdir(parents=True)
            decoy_marker.write_text(
                'DSHA_MANAGED_ASSETS_V2\n' + 'f' * 64 + '\n', encoding='ascii')
            previous_directory = os.getcwd()
            try:
                os.chdir(root_working_directory)
                with patch.dict(os.environ, {'DSH_HOME': '/root/.dsh'}, clear=False):
                    os.environ.pop('DSHA_TEST_ROOT', None)
                    spec = importlib.util.spec_from_file_location(
                        'register_production_paths', ASSETS / 'register-builtin-plugins.py')
                    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
            finally:
                os.chdir(previous_directory)

            self.assertTrue(os.path.isabs(module.BUILTIN_LIST))
            self.assertTrue(os.path.isabs(module.MANAGED_ASSET_MARKER))
            self.assertEqual(os.path.normpath('/root/dsha-builtin.txt'),
                             os.path.normpath(module.BUILTIN_LIST))
            self.assertEqual(os.path.normpath('/usr/local/share/dsha/managed-assets-v2'),
                             os.path.normpath(module.MANAGED_ASSET_MARKER))
            self.assertNotEqual(decoy_marker.resolve(), Path(module.MANAGED_ASSET_MARKER).resolve())
            with patch.object(module, 'Path') as path_class:
                path_class.return_value.read_text.return_value = (
                    'DSHA_MANAGED_ASSETS_V2\n' + 'a' * 64 + '\n')
                self.assertEqual('a' * 64, module.managed_asset_identity())
                path_class.assert_called_once_with(module.MANAGED_ASSET_MARKER)

    @unittest.skipIf(os.name == 'nt', '真实软链在 Android Ubuntu 验证')
    def test_real_cache_repairs_deleted_link_and_rejects_outside_scope(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {'DSHA_TEST_ROOT': temporary, 'DSH_HOME': '/root/.dsh'}):
            spec = importlib.util.spec_from_file_location('register_links', ASSETS / 'register-builtin-plugins.py')
            module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
            root = Path(temporary)
            package = root / 'usr/local/lib/node_modules/@deepseek-ai/dsh'
            dependency = package / 'node_modules/@example/runtime'
            dependency.mkdir(parents=True)
            (package / 'package.json').write_text('{"name":"@deepseek-ai/dsh"}')
            (dependency / 'package.json').write_text('{"name":"@example/runtime"}')
            self.assertGreater(module.ensure_runtime_modules(), 0)
            self.assertEqual(0, module.ensure_runtime_modules())
            link = root / 'root/.dsh/node_modules/@example/runtime'
            self.assertEqual(dependency, link.resolve())
            link.unlink()
            self.assertEqual(1, module.ensure_runtime_modules())
            self.assertEqual(dependency, link.resolve())
            link.unlink(); link.parent.rmdir()
            outside = root / 'outside'; outside.mkdir()
            link.parent.symlink_to(outside, target_is_directory=True)
            with self.assertRaises(RuntimeError): module.ensure_runtime_modules()
            self.assertEqual([], list(outside.iterdir()))

    def test_recovery_ignores_broken_original_and_preserves_shared_data(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {'DSHA_TEST_ROOT': temporary, 'DSH_HOME': '/root/.dsh'}):
            spec = importlib.util.spec_from_file_location('recovery', Path(__file__).resolve().parents[1] / 'tools/history/engineering/assets/startup-recovery.py')
            module = importlib.util.module_from_spec(spec)
            original_spec = importlib.util.spec_from_file_location
            def historical_dependency(name, path, *args, **kwargs):
                if name == 'register': path = ASSETS / 'register-builtin-plugins.py'
                return original_spec(name,path,*args,**kwargs)
            with patch('importlib.util.spec_from_file_location',side_effect=historical_dependency):
                spec.loader.exec_module(module)
            home = Path(temporary) / 'root/.dsh'
            web = home / 'profiles/web'; web.mkdir(parents=True)
            (web / 'package.json').write_text('{BROKEN', encoding='utf-8')
            (home / 'settings.json').write_text('PRESERVED', encoding='utf-8')
            first, second = module.prepare(), module.prepare()
            self.assertNotEqual(first, second)
            self.assertEqual('{BROKEN', (web / 'package.json').read_text())
            self.assertEqual('PRESERVED', (home / 'settings.json').read_text())
            doc = json.loads((home / 'profiles' / first / 'package.json').read_text())
            self.assertEqual(list(module.register.OFFICIAL_BUNDLES), doc['dsh']['profile']['bundles'])
            self.assertEqual('startup', doc['dsh']['profile']['patchReload'])

    def test_unchanged_links_skip_repair_and_directory_changes_invalidate(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {'DSHA_TEST_ROOT': temporary, 'DSH_HOME': '/root/.dsh'}):
            spec = importlib.util.spec_from_file_location('register_cache', ASSETS / 'register-builtin-plugins.py')
            module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
            base = Path(temporary) / 'root/.dsh/node_modules/@author'; base.mkdir(parents=True)
            with patch.object(module, 'repair_runtime_modules', return_value=7) as repair:
                self.assertEqual(7, module.ensure_runtime_modules())
                self.assertEqual(0, module.ensure_runtime_modules())
                self.assertEqual(1, repair.call_count)
                marker = Path(temporary) / 'usr/local/share/dsha/managed-assets-v2'
                marker.parent.mkdir(parents=True)
                marker.write_text('DSHA_MANAGED_ASSETS_V2\n' + 'a' * 64 + '\n', encoding='ascii')
                self.assertEqual(7, module.ensure_runtime_modules())
                self.assertEqual(0, module.ensure_runtime_modules())
                marker.write_text('DSHA_MANAGED_ASSETS_V2\n' + 'b' * 64 + '\n', encoding='ascii')
                self.assertEqual(7, module.ensure_runtime_modules())
                (base / 'new-plugin').mkdir()
                self.assertEqual(7, module.ensure_runtime_modules())
                (base / 'new-plugin').rmdir()
                self.assertEqual(7, module.ensure_runtime_modules())
                self.assertEqual(7, module.ensure_runtime_modules(force=True))
                self.assertEqual(6, repair.call_count)

    @unittest.skipIf(os.name == 'nt', 'POSIX PID 判定')
    def test_profile_lock_state_distinguishes_live_stale_and_unknown(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
                os.environ, {'DSHA_TEST_ROOT': temporary, 'DSH_HOME': '/root/.dsh'}):
            module = self.register_module()
            lock = Path(temporary) / 'root/.dsh/profiles/web/package.json.lock'
            lock.parent.mkdir(parents=True)
            self.assertEqual('missing', module.profile_lock_state(str(lock)))
            lock.write_text(str(os.getpid()) + '\n', encoding='ascii')
            self.assertEqual('live', module.profile_lock_state(str(lock)))
            # 超过 Linux pid_max 的记录必然指向已退出进程。
            lock.write_text('2147483647\n', encoding='ascii')
            self.assertEqual('stale', module.profile_lock_state(str(lock)))
            lock.write_text('garbage', encoding='ascii')
            self.assertEqual('unknown', module.profile_lock_state(str(lock)))

    @unittest.skipIf(os.name == 'nt', 'POSIX take-over')
    def test_clear_stale_profile_lock_removes_only_dead_holder_lock(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
                os.environ, {'DSHA_TEST_ROOT': temporary, 'DSH_HOME': '/root/.dsh'}):
            module = self.register_module()
            lock = Path(temporary) / 'root/.dsh/profiles/web/package.json.lock'
            lock.parent.mkdir(parents=True)
            self.assertEqual(0, module.clear_stale_profile_lock())
            self.assertFalse(os.path.lexists(lock))
            lock.write_text('2147483647\n', encoding='ascii')
            self.assertEqual(0, module.clear_stale_profile_lock())
            self.assertFalse(os.path.lexists(lock), '已退出持有者的锁应被原子接管删除')
            lock.write_text(str(os.getpid()) + '\n', encoding='ascii')
            self.assertEqual(0, module.clear_stale_profile_lock())
            self.assertTrue(os.path.lexists(lock), '存活持有者的锁必须保留')

    @unittest.skipIf(os.name == 'nt', 'POSIX take-over')
    def test_take_over_dead_profile_lock_matches_node_claim_semantics(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
                os.environ, {'DSHA_TEST_ROOT': temporary, 'DSH_HOME': '/root/.dsh'}):
            module = self.register_module()
            lock = Path(temporary) / 'root/.dsh/profiles/web/package.json.lock'
            lock.parent.mkdir(parents=True)
            lock.write_text('2147483647\n', encoding='ascii')
            self.assertTrue(
                module.take_over_dead_profile_lock(str(lock), str(os.getpid()) + '\n'))
            self.assertFalse(os.path.lexists(lock))
            lock.write_text('garbage', encoding='ascii')
            self.assertFalse(
                module.take_over_dead_profile_lock(str(lock), str(os.getpid()) + '\n'))
            self.assertTrue(os.path.lexists(lock), '不完整记录不能授权接管')

    def test_successful_registration_writes_budget_identity_marker(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
                os.environ, {'DSHA_TEST_ROOT': temporary, 'DSH_HOME': '/root/.dsh'}):
            module = self.register_module()
            target = Path(temporary) / 'usr/local/share/dsha/register-managed-asset-id'
            module.write_register_identity()
            self.assertFalse(target.exists(), '没有受管资产身份时不能写预算标记')
            marker = Path(temporary) / 'usr/local/share/dsha/managed-assets-v2'
            marker.parent.mkdir(parents=True)
            identity = 'a' * 64
            marker.write_text('DSHA_MANAGED_ASSETS_V2\n' + identity + '\n', encoding='ascii')
            module.write_register_identity()
            self.assertEqual(identity, target.read_text(encoding='ascii').strip())

if __name__ == '__main__': unittest.main()
