import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ASSETS = Path(os.environ.get('DSHA_TEST_ASSETS', str(Path(__file__).resolve().parents[1] / 'app/src/main/assets')))

class RecoveryTests(unittest.TestCase):
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
            spec = importlib.util.spec_from_file_location('recovery', ASSETS / 'startup-recovery.py')
            module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
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
                (base / 'new-plugin').mkdir()
                self.assertEqual(7, module.ensure_runtime_modules())
                (base / 'new-plugin').rmdir()
                self.assertEqual(7, module.ensure_runtime_modules())
                self.assertEqual(7, module.ensure_runtime_modules(force=True))
                self.assertEqual(4, repair.call_count)

if __name__ == '__main__': unittest.main()
