#!/usr/bin/env python3
"""Fresh-interpreter regression for local startup commands and lazy optional I/O."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets'
PROBE = """import importlib.util,json,sys
spec=importlib.util.spec_from_file_location('startup_fixture_manager',sys.argv[1])
manager=importlib.util.module_from_spec(spec); spec.loader.exec_module(manager)
sys.argv=[sys.argv[1],sys.argv[2]]
code=manager.main()
print('IMPORT_PROBE: '+json.dumps({'code':code,'modules':sorted(sys.modules)}))
"""


class StartupImportsTest(unittest.TestCase):
    def invoke(self, command, *, cancelled=False):
        with tempfile.TemporaryDirectory(prefix='dsha-startup-imports-') as directory:
            root = Path(directory)
            profile = root / 'root/.dsh/profiles/web'
            profile.mkdir(parents=True)
            (profile / 'package.json').write_text(json.dumps({
                'dependencies': {}, 'dsh': {'profile': {'bundles': []}}}), encoding='utf8')
            task = 'a' * 32
            if cancelled:
                (root / ('root/.dsha-plugin-task-' + task + '.cancel')).touch()
            process = subprocess.run(
                [sys.executable, '-c', PROBE, str(ASSETS / 'plugin-manager.py'), command],
                env=dict(os.environ, DSHA_TEST_ROOT=str(root), DSH_HOME='/root/.dsh',
                         DSHA_PLUGIN_TASK=task, DSHA_PLUGIN_QUERY='1' if command == 'list' else '0',
                         PYTHONIOENCODING='utf-8'),
                capture_output=True, text=True, timeout=30,
                encoding='utf8')
            self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
            lines = process.stdout.splitlines()
            result = json.loads(next(line.removeprefix('PLUGIN_RESULT: ') for line in lines
                                     if line.startswith('PLUGIN_RESULT: ')))
            probe = json.loads(next(line.removeprefix('IMPORT_PROBE: ') for line in lines
                                    if line.startswith('IMPORT_PROBE: ')))
            return result, probe

    def assert_local_modules(self, probe):
        self.assertFalse(set(probe['modules']) & {'ssl', 'urllib.request', 'tarfile', 'zipfile'})

    def test_legacy_review_noop_preserves_receipt_without_optional_modules(self):
        result, probe = self.invoke('migrate-review-markers')
        self.assertEqual((result['status'], result['activated'], result['skipped']), ('ok', [], []))
        self.assertEqual(probe['code'], 0)
        self.assert_local_modules(probe)

    def test_read_only_list_does_not_load_download_or_archive_modules(self):
        result, probe = self.invoke('list')
        self.assertEqual(result['status'], 'ok')
        self.assertEqual(probe['code'], 0)
        self.assert_local_modules(probe)

    def test_cancellation_remains_cancelled_without_optional_imports(self):
        result, probe = self.invoke('migrate-review-markers', cancelled=True)
        self.assertEqual(result['status'], 'cancelled')
        self.assertEqual(probe['code'], 1)
        self.assert_local_modules(probe)

    def test_invalid_command_keeps_original_error_path(self):
        result, probe = self.invoke('invalid-startup-test-command')
        self.assertEqual(result['status'], 'error')
        self.assertEqual(probe['code'], 1)
        self.assert_local_modules(probe)


if __name__ == '__main__':
    unittest.main()
