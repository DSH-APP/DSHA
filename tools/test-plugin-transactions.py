#!/usr/bin/env python3
"""真实插件目录/元数据提交与子进程强杀恢复；仅操作本次夹具根。"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
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


def deletion_fixture(root):
    fixture(root)
    # 同时覆盖实体与禁用标记的逐次移动边界，不依赖 Windows 的软链接权限。
    put(root, 'root/.dsh/profiles/web/node_modules/test-plugin/owned', 'installed entry')
    put(root, 'root/.dsh/profiles/web/node_modules/test-plugin.disabled', 'DSHA_REVIEW_REQUIRED\n')


class TransactionTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix='dsha-plugin-transactions-'))

    def tearDown(self):
        if os.environ.get('DSHA_KEEP_FIXTURES') == '1':
            print('KEPT_FIXTURE', self.root)
        else:
            shutil.rmtree(self.root)

    def kill(self, root, boundary, recovery=False, operation='install'):
        marker = root / 'plugin-boundary'
        if marker.exists():
            marker.unlink()
        environment = dict(os.environ, DSHA_TEST_ROOT=str(root), DSH_HOME='/root/.dsh', DSHA_PLUGIN_TEST_BOUNDARY=boundary)
        with (root / ('child-' + boundary + '.log')).open('wb') as log:
            child = subprocess.Popen([sys.executable, '-B', str(Path(__file__).resolve()), '--child', str(root), 'recover' if recovery else operation], stdout=log, stderr=subprocess.STDOUT, env=environment)
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

    @unittest.skipIf(os.name == 'nt', '提交后的链接恢复需要 Android/Linux 的真实符号链接')
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

    @unittest.skipIf(os.name == 'nt', '回切后的链接恢复需要 Android/Linux 的真实符号链接')
    def test_death_during_rollback_remains_recoverable(self):
        fixture(self.root); self.kill(self.root, 'manifest-replaced'); self.kill(self.root, 'rollback-new', True)
        with manager(self.root) as module:
            module.transactions().recover_all()
            self.assertEqual('1.0.0', self.version(self.root, 'root/.dsh/plugin-src/test-plugin'))
            self.assertFalse(module.transactions().pending())
            work = next(path for path in (self.root / 'root/.dsh/plugin-install-operations/completed').iterdir() if path.is_dir())
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

    def test_completed_history_does_not_exhaust_future_transaction_slots(self):
        with manager(self.root) as module:
            transaction=module.transactions()
            home=Path(transaction.directory);home.mkdir(parents=True,exist_ok=True)
            retained=[]
            for index in range(129):
                entry=home/str(uuid.uuid4());entry.mkdir()
                (entry/'plan.json').write_text(json.dumps({'format':2,'id':entry.name,'historical':index}),encoding='utf8')
                transaction.mark(str(entry),'committed')
                retained.append(entry)
            self.assertEqual([],transaction.pending())
            with transaction.workspace() as current:
                self.assertTrue(Path(current).is_dir())
            self.assertEqual(129,len(list((home/'completed').iterdir())))
            self.assertTrue(all((home/'completed'/entry.name/'plan.json').is_file() for entry in retained))

    def test_delete_crash_boundaries_restore_or_leave_committed_user_changes(self):
        boundaries = ('delete-prepared', 'delete-source-moved', 'delete-link-moved',
                      'delete-marker-moved', 'delete-history-moved',
                      'delete-sources-replaced', 'delete-manifest-replaced', 'delete-committed')
        for boundary in boundaries:
            with self.subTest(boundary=boundary):
                root = self.root / boundary; root.mkdir(); deletion_fixture(root)
                manifest = (root / 'root/.dsh/profiles/web/package.json').read_bytes()
                sources = (root / 'root/.dsh/plugin-sources.json').read_bytes()
                self.kill(root, boundary, operation='delete')
                with manager(root) as module:
                    if boundary == 'delete-committed':
                        put(root, 'root/.dsh/profiles/web/package.json', {'user': 'later settings'})
                    module.transactions().recover_all(); module.transactions().recover_all()
                    self.assertFalse(module.transactions().pending())
                    source = root / 'root/.dsh/plugin-src/test-plugin'
                    if boundary == 'delete-committed':
                        self.assertFalse(source.exists())
                        self.assertEqual({'user': 'later settings'}, json.loads((root / 'root/.dsh/profiles/web/package.json').read_text()))
                    else:
                        self.assertEqual('1.0.0', self.version(root, 'root/.dsh/plugin-src/test-plugin'))
                        self.assertEqual(manifest, (root / 'root/.dsh/profiles/web/package.json').read_bytes())
                        self.assertEqual(sources, (root / 'root/.dsh/plugin-sources.json').read_bytes())
                    self.assertEqual('newest conversation bytes', (root / 'root/.dsh/sessions/owned-conversation').read_text())

    def test_delete_death_during_rollback_is_recoverable(self):
        deletion_fixture(self.root)
        self.kill(self.root, 'delete-manifest-replaced', operation='delete')
        self.kill(self.root, 'delete-rollback-manifest', recovery=True)
        with manager(self.root) as module:
            module.transactions().recover_all()
            self.assertFalse(module.transactions().pending())
            self.assertEqual('1.0.0', self.version(self.root, 'root/.dsh/plugin-src/test-plugin'))

    def test_delete_modified_staged_original_blocks_recovery(self):
        deletion_fixture(self.root)
        self.kill(self.root, 'delete-source-moved', operation='delete')
        with manager(self.root) as module:
            work = Path(module.transactions().pending()[0])
            staged = work / 'delete-source/index.js'
            staged.write_text('later modifications')
            with self.assertRaisesRegex(ValueError, '全部现场保留'):
                module.transactions().recover_all()
            self.assertEqual('later modifications', staged.read_text())
            self.assertTrue(module.transactions().pending())

    def test_delete_failed_rollback_keeps_the_only_old_source(self):
        deletion_fixture(self.root)
        with manager(self.root) as module:
            transaction = module.transactions()
            original_boundary = transaction.boundary
            original_replace = os.replace
            def interrupt(name):
                if name == 'delete-source-moved':
                    raise OSError('injected commit failure')
                original_boundary(name)
            def fail_restore(source, target):
                if Path(source).name == 'delete-source':
                    raise OSError('injected rollback failure')
                return original_replace(source, target)
            transaction.boundary = interrupt
            with patch.object(os, 'replace', side_effect=fail_restore):
                with self.assertRaisesRegex(OSError, 'injected rollback failure'):
                    module.cmd_delete('test-plugin')
            work = Path(transaction.pending()[0])
            self.assertEqual('1.0.0', self.version(self.root, work.relative_to(self.root) / 'delete-source'))
            self.assertFalse((self.root / 'root/.dsh/plugin-src/test-plugin').exists())
            transaction.boundary = original_boundary
            transaction.recover_all()
            self.assertEqual('1.0.0', self.version(self.root, 'root/.dsh/plugin-src/test-plugin'))

    def test_read_only_pending_does_not_move_committed_work_still_used_by_child(self):
        deletion_fixture(self.root)
        marker = self.root / 'plugin-boundary'
        environment = dict(os.environ, DSHA_TEST_ROOT=str(self.root), DSH_HOME='/root/.dsh',
                           DSHA_PLUGIN_TEST_BOUNDARY='delete-committed')
        with (self.root / 'child-commit-pause.log').open('wb') as log:
            child = subprocess.Popen([sys.executable, '-B', str(Path(__file__).resolve()), '--child', str(self.root), 'delete'],
                                     stdout=log, stderr=subprocess.STDOUT, env=environment)
            try:
                deadline = time.monotonic() + 20
                while not marker.exists() and child.poll() is None and time.monotonic() < deadline:
                    time.sleep(.025)
                self.assertTrue(marker.exists())
                with manager(self.root) as module:
                    self.assertEqual([], module.transactions().pending())
                    home = Path(module.transactions().directory)
                    self.assertEqual(1, len([p for p in home.iterdir() if p.is_dir()]))
                    self.assertFalse((home / 'completed').exists())
            finally:
                child.kill(); child.wait(timeout=5)
        with manager(self.root) as module:
            module.transactions().recover_all()
            self.assertFalse(module.transactions().pending())
            self.assertEqual(1,len(list((Path(module.transactions().directory)/'completed').iterdir())))

    def test_committed_id_lookup_and_recovery_are_idempotent_after_archive(self):
        deletion_fixture(self.root)
        with manager(self.root) as module:
            module.cmd_delete('test-plugin')
            transactions = module.transactions()
            work = next(path for path in (Path(transactions.directory)/'completed').iterdir() if path.is_dir())
            self.assertEqual(work, Path(transactions.locate(work.name)))
            manifest = put(self.root,'root/.dsh/profiles/web/package.json',{'user':'later changes'})
            transactions.recover(str(work));transactions.recover(str(work))
            self.assertEqual({'user':'later changes'},json.loads(manifest.read_text()))
            active = Path(transactions.directory)/work.name
            active.mkdir()
            with self.assertRaisesRegex(ValueError,'重复'):
                transactions.locate(work.name)


if __name__ == '__main__':
    if len(sys.argv) == 4 and sys.argv[1] == '--child':
        root = Path(sys.argv[2]).resolve()
        with manager(root) as module:
            if sys.argv[3] == 'recover':
                module.transactions().recover_all()
            elif sys.argv[3] == 'delete':
                module.cmd_delete('test-plugin')
            else:
                link = patch.object(module.os, 'symlink', side_effect=lambda src, dst, **_: shutil.copytree(src, dst)) if os.name == 'nt' else contextlib.nullcontext()
                with link:
                    module.register_plugin(str(root / 'incoming'), 'owned fixture')
    else:
        unittest.main(verbosity=2)
