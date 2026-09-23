#!/usr/bin/env python3
"""真实插件目录/元数据提交与子进程强杀恢复；仅操作本次夹具根。"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'


@contextlib.contextmanager
def manager(root):
    with patch.dict(os.environ, {'DSHA_TEST_ROOT': str(root), 'DSH_HOME': '/root/.dsh'}):
        spec = importlib.util.spec_from_file_location('plugin_transaction_fixture', ASSETS / 'plugin-manager.py')
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        yield module


def put(root, path, value):
    target = root / path; target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value) if isinstance(value, dict) else value, encoding='utf8')
    return target


def plugin(root, path, version):
    put(root, path + '/package.json', {'name': 'test-plugin', 'version': version, 'dsh': {'bundle': {'patch': 'cordis.patch.yml'}}})
    put(root, path + '/cordis.patch.yml', '[]\n'); put(root, path + '/index.js', 'owned version ' + version)


def fixture(root):
    plugin(root, 'root/.dsh/plugin-src/test-plugin', '1.0.0'); plugin(root, 'incoming', '2.0.0')
    put(root, 'root/.dsh/profiles/web/package.json', {'dependencies': {'test-plugin': 'link:/root/.dsh/plugin-src/test-plugin'}, 'dsh': {'profile': {'bundles': ['test-plugin']}}})
    put(root, 'root/.dsh/plugin-sources.json', {'test-plugin': 'npm:test-plugin@1.0.0'})
    put(root, 'root/.dsh/plugin-activations.json', {'format': 1, 'entries': {'test-plugin': {'status': 'loaded', 'version': '1.0.0', 'fingerprint': 'a'*64}}})
    put(root, 'root/.dsh/sessions/owned-conversation', 'newest conversation bytes')
    with manager(root) as module:
        history = Path(module.lifecycle().history_path('test-plugin')).relative_to(root).as_posix()
        plugin(root, history + '/package', '0.9.0')
        put(root, history + '/state.json', {'name': 'test-plugin', 'version': '0.9.0', 'source': 'owned previous source'})


class TransactionTest(unittest.TestCase):
    def setUp(self):
        self.root = ROOT / 'app/build/stability-20260914/plugin-transaction-tests' / str(uuid.uuid4())
        self.root.mkdir(parents=True)

    def tearDown(self):
        pass  # 本轮强杀日志和合成文件保留，便于核对失败边界。

    def kill(self, root, boundary, recovery=False):
        marker = root / 'plugin-boundary'
        if marker.exists():
            marker.unlink()
        environment = dict(os.environ, DSHA_TEST_ROOT=str(root), DSH_HOME='/root/.dsh', DSHA_PLUGIN_TEST_BOUNDARY=boundary)
        with (root / ('child-' + boundary + '.log')).open('wb') as log:
            child = subprocess.Popen([sys.executable, '-B', str(Path(__file__).resolve()), '--child', str(root), 'recover' if recovery else 'install'], stdout=log, stderr=subprocess.STDOUT, env=environment)
            try:
                until = time.monotonic() + 20
                while not marker.exists() and child.poll() is None and time.monotonic() < until:
                    time.sleep(.025)
                detail = Path(log.name).read_text(encoding='utf8', errors='replace')[-4000:] if not marker.exists() else ''
                self.assertTrue(marker.exists(), 'Did not reach owned boundary: ' + boundary + '; ' + detail)
            finally:
                child.kill(); child.wait(timeout=5)
        self.assertNotEqual(0, child.returncode)

    def version(self, root, path):
        return json.loads((root / path / 'package.json').read_text())['version']

    def test_every_commit_boundary_recovers_or_preserves_finalized_user_changes(self):
        for boundary in ('prepared', 'old-moved', 'new-moved', 'activation-replaced', 'sources-replaced', 'history-retained', 'manifest-replaced', 'committed'):
            with self.subTest(boundary=boundary):
                root = self.root / boundary; root.mkdir(); fixture(root)
                manifest = (root / 'root/.dsh/profiles/web/package.json').read_bytes()
                sources = (root / 'root/.dsh/plugin-sources.json').read_bytes()
                activations = (root / 'root/.dsh/plugin-activations.json').read_bytes()
                self.kill(root, boundary)
                with manager(root) as module, contextlib.redirect_stdout(io.StringIO()):
                    if boundary == 'committed':
                        put(root, 'root/.dsh/profiles/web/package.json', {'user': 'later configuration'})
                    module.transactions().recover_all(); module.transactions().recover_all()
                    self.assertFalse(module.transactions().pending())
                    self.assertEqual('2.0.0' if boundary == 'committed' else '1.0.0', self.version(root, 'root/.dsh/plugin-src/test-plugin'))
                    if boundary == 'committed':
                        self.assertEqual({'user': 'later configuration'}, json.loads((root / 'root/.dsh/profiles/web/package.json').read_text()))
                        self.assertEqual('2.0.0',module.lifecycle().activation_state()['entries']['test-plugin']['version'])
                    else:
                        self.assertEqual(manifest, (root / 'root/.dsh/profiles/web/package.json').read_bytes())
                        self.assertEqual(sources, (root / 'root/.dsh/plugin-sources.json').read_bytes())
                        self.assertEqual(activations, (root / 'root/.dsh/plugin-activations.json').read_bytes())
                        self.assertEqual('0.9.0', self.version(root, Path(module.lifecycle().history_path('test-plugin')).relative_to(root).as_posix() + '/package'))
                self.assertEqual('newest conversation bytes', (root / 'root/.dsh/sessions/owned-conversation').read_text())

    def test_death_during_rollback_remains_recoverable(self):
        fixture(self.root); self.kill(self.root, 'manifest-replaced'); self.kill(self.root, 'rollback-new', True)
        with manager(self.root) as module:
            module.transactions().recover_all()
            self.assertEqual('1.0.0', self.version(self.root, 'root/.dsh/plugin-src/test-plugin'))
            work = next((self.root / 'root/.dsh/plugin-install-operations').iterdir())
            self.assertEqual('2.0.0', self.version(self.root, str(work.relative_to(self.root) / 'failed')))

    def test_legacy_three_file_journal_preserves_unrecorded_activation_state(self):
        fixture(self.root);self.kill(self.root,'new-moved')
        with manager(self.root) as module:
            work=Path(module.transactions().pending()[0]);plan=json.loads((work/'plan.json').read_text())
            plan['format']=1;plan['before'].pop('activation');plan['after'].pop('activation')
            (work/'plan.json').write_text(json.dumps(plan))
            current=put(self.root,'root/.dsh/plugin-activations.json',{'format':1,'entries':{'test-plugin':{'status':'user-observed'}}}).read_bytes()
            module.transactions().recover_all()
            self.assertEqual('1.0.0',self.version(self.root,'root/.dsh/plugin-src/test-plugin'))
            self.assertEqual(current,(self.root/'root/.dsh/plugin-activations.json').read_bytes())

    def test_modified_configuration_after_crash_is_never_overwritten(self):
        fixture(self.root); self.kill(self.root, 'new-moved')
        target = put(self.root, 'root/.dsh/profiles/web/package.json', {'user': 'changed while application was stopped'})
        with manager(self.root) as module:
            with self.assertRaisesRegex(ValueError, '未覆盖新内容'):
                module.transactions().recover_all()
            self.assertEqual({'user': 'changed while application was stopped'}, json.loads(target.read_text()))
            self.assertTrue(module.transactions().pending())

    def test_changed_candidate_and_invalid_log_preserve_all_originals(self):
        fixture(self.root); self.kill(self.root, 'new-moved')
        code = put(self.root, 'root/.dsh/plugin-src/test-plugin/index.js', 'new user modifications')
        with manager(self.root) as module:
            with self.assertRaisesRegex(ValueError, '未覆盖新内容'):
                module.transactions().recover_all()
            self.assertEqual('new user modifications', code.read_text())
            work = Path(module.transactions().pending()[0]); old = work / 'old/package.json'
            self.assertEqual('1.0.0', json.loads(old.read_text())['version'])
            plan = json.loads((work / 'plan.json').read_text()); plan['name'] = '../../outside'; (work / 'plan.json').write_text(json.dumps(plan))
            with self.assertRaises(ValueError):
                module.transactions().recover_all()
            self.assertTrue(old.is_file())


if __name__ == '__main__':
    if len(sys.argv) == 4 and sys.argv[1] == '--child':
        root = Path(sys.argv[2]).resolve()
        with manager(root) as module:
            if sys.argv[3] == 'recover':
                module.transactions().recover_all()
            else:
                module.register_plugin(str(root / 'incoming'), 'owned fixture', reviewed=True)
    else:
        unittest.main(verbosity=2)
