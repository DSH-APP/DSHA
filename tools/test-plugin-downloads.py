#!/usr/bin/env python3
"""插件市场下载、取消、并行只读更新及提交锁回归；全部写入隔离夹具。"""
import concurrent.futures
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'


class PluginDownloadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='dsha-download-test-')
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {'DSHA_TEST_ROOT': str(self.root), 'DSH_HOME': '/root/.dsh',
                                          'DSHA_PLUGIN_DOWNLOAD_SOURCE': '', 'DSHA_PLUGIN_TASK': '1'*32})
        self.env.start()
        path = ASSETS / 'plugin-manager.py'
        spec = importlib.util.spec_from_file_location('plugin_download_fixture', path)
        self.m = importlib.util.module_from_spec(spec)
        before = os.environ.get('DSHA_PLUGIN_FIXTURE_MANAGER')
        if before:
            exec(compile(Path(before).read_text(encoding='utf8'), str(path), 'exec'), self.m.__dict__)
        else:
            spec.loader.exec_module(self.m)
        self.home = self.root / 'root/.dsh'
        self.home.mkdir(parents=True)

    def tearDown(self):
        self.env.stop()
        self.temp.cleanup()

    def put(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value) if isinstance(value, dict) else value, encoding='utf8')
        return path

    def package(self, name, path):
        self.put(path/'package.json', {'name': name, 'version': '1.0.0', 'dsh': {'bundle': {'patch': 'cordis.patch.yml'}}})
        self.put(path/'cordis.patch.yml', '[]\n')
        return path

    def network(self, mode):
        with patch.dict(os.environ, {'DSHA_PLUGIN_DOWNLOAD_SOURCE': mode}):
            return self.m.network()

    def test_install_holds_one_lock_through_commit_without_nested_acquisition(self):
        incoming = self.package('test-download', self.root/'incoming')
        original = self.m.builtin.operation_lock
        depth, acquisitions, boundaries = [0], [], []
        @contextlib.contextmanager
        def single_lock(cancel=None):
            self.assertEqual(0, depth[0], 'same process opened data lock twice: Linux flock deadlock')
            with original(cancel):
                depth[0] += 1; acquisitions.append(True)
                try: yield
                finally: depth[0] -= 1
        def boundary(stage):
            self.assertEqual(1, depth[0]); boundaries.append(stage)
        link = patch.object(self.m.os, 'symlink', side_effect=lambda src, dst, **_: shutil.copytree(src, dst)) if os.name == 'nt' else contextlib.nullcontext()
        with patch.object(self.m.builtin, 'operation_lock', single_lock), patch.object(self.m.transactions(), 'boundary', boundary), link:
            self.assertEqual('test-download', self.m.register_plugin(str(incoming), 'npm:test-download@1.0.0'))
        self.assertEqual(1, len(acquisitions))
        self.assertIn('committed', boundaries)
        self.assertFalse(Path(self.m.builtin.marker_path('test-download')).exists())
        self.assertIn('test-download', self.m.builtin.read_manifest()['dsh']['profile']['bundles'])

    def test_parallel_updates_are_bounded_and_keep_per_plugin_failure(self):
        names = ['test-download-'+str(i) for i in range(6)]
        paths = {n: self.package(n, self.root/n) for n in names}
        self.put(Path(self.m.builtin.local(self.m.builtin.PROFILE))/'package.json', {'dependencies': {n:'1.0.0' for n in names}})
        life = self.m.lifecycle(); barrier = threading.Barrier(3)
        active, peak, guard = [0], [0], threading.Lock()
        def metadata(name):
            with guard: active[0] += 1; peak[0] = max(peak[0], active[0])
            try:
                barrier.wait(timeout=15)
                if name == names[2]: raise ValueError('owned offline source')
                return {'version':'2.0.0','compatibilityMessage':'fixture'}, {'command':'npm '+name+'@2.0.0'}
            finally:
                with guard: active[0] -= 1
        with patch.object(self.m, 'resolve_plugin_dir', side_effect=lambda n: str(paths[n])), \
                patch.object(life, 'update_metadata', side_effect=metadata), \
                patch.object(life, 'source_command', side_effect=lambda n:'npm '+n), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, life.check_updates())
        states = json.loads((self.home/'plugin-updates.json').read_text())
        self.assertEqual(3, peak[0]); self.assertEqual(set(names), set(states))
        self.assertEqual('owned offline source', states[names[2]]['message'])
        self.assertFalse(states[names[2]]['available'])
        self.assertTrue(states[names[0]]['available'])

    def test_concurrent_progress_keeps_valid_single_document(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            list(pool.map(lambda n:self.m.progress('metadata','owned '+str(n)), range(30)))
        value = json.loads(Path(self.m.task_file('.json')).read_text())
        self.assertEqual('metadata', value['stage']); self.assertTrue(value['message'].startswith('owned '))

    def test_pinned_github_commit_uses_no_metadata_request(self):
        with patch.object(self.m,'open_url',side_effect=AssertionError('unnecessary API call')):
            self.assertEqual(('a'*40,'packages/plugin'),self.m.github_revision('owner','repo','a'*40+'/packages/plugin'))

    def test_auto_selects_first_successful_source_and_reuses_within_task(self):
        n=self.network('auto'); calls=[]; slow=threading.Event()
        def probe(registry):
            calls.append(registry)
            if registry.endswith('npmjs.org'): slow.wait(1)
            return registry
        with patch.object(n,'probe',side_effect=probe):
            try:
                self.assertIn('npmmirror.com',n.registries()[0]); n.registries()
            finally:slow.set()
        self.assertEqual(2,len(calls))

    def test_metadata_mirror_404_falls_back_without_forwarding_credentials(self):
        n=self.network('mirror'); response=object(); seen=[]
        def request(url):
            seen.append(url)
            if 'npmmirror' in url:raise urllib.error.HTTPError(url,404,'missing',{},None)
            return response
        with patch.object(n,'request',side_effect=request):
            self.assertIs(response,n.open('https://registry.npmjs.org/test/latest'))
        self.assertEqual(['https://registry.npmmirror.com/test/latest','https://registry.npmjs.org/test/latest'],seen)

    def test_certificate_failure_and_custom_archive_do_not_switch_source(self):
        n=self.network('mirror')
        for url in ['https://registry.npmjs.org/test/latest','https://downloads.example/test.tgz']:
            with patch.object(n,'request',side_effect=urllib.error.URLError(ssl.SSLCertVerificationError('owned bad certificate'))) as request:
                with self.assertRaises(urllib.error.URLError):n.open(url)
                self.assertEqual(1,request.call_count)

    def test_signed_registry_link_keeps_its_original_origin(self):
        n=self.network('mirror')
        url='https://registry.npmjs.org/owned.tgz?token=synthetic-only'
        with patch.object(n,'request',return_value=object()) as request:
            n.open(url)
            request.assert_called_once_with(url)

    def test_https_redirect_rejects_downgrade_before_request(self):
        n=self.network('official')
        klass=n.request.__globals__['HttpsRedirect']
        for url in ['http://example.test/file','https://name:secret@example.test/file']:
            with self.assertRaises(ValueError):klass().redirect_request(None,None,302,'',{},url)

    def test_npm_switches_only_network_failure_and_keeps_exact_spec(self):
        n=self.network('mirror'); calls=[]
        def run(args,cwd):
            calls.append(args)
            return subprocess.CompletedProcess(args, 1 if len(calls)==1 else 0, '', 'E404' if len(calls)==1 else '')
        with patch.object(self.m,'run_package_command',side_effect=run):
            self.assertEqual(0,n.package_command(['npm','pack','--ignore-scripts','--','test@1.2.3'],str(self.home),frozen=True).returncode)
        self.assertEqual(2,len(calls))
        for args in calls:
            self.assertEqual('test@1.2.3',args[-1]);self.assertIn('--prefer-offline',args)
            self.assertTrue(args.index(next(a for a in args if a.startswith('--registry='))) < args.index('--'))
        for error in ['EINTEGRITY','ERR_PNPM_TARBALL_INTEGRITY','EACCES','CERT_HAS_EXPIRED','ERR_PNPM_OUTDATED_LOCKFILE']:
            with patch.object(self.m,'run_package_command',return_value=subprocess.CompletedProcess([],1,'',error)) as run:
                n.package_command(['npm','pack','--','test@latest'],str(self.home));self.assertEqual(1,run.call_count)

    def test_dependency_retry_keeps_existing_lock_and_allows_unlocked_hooks(self):
        n=self.network('mirror'); lock=self.home/'pnpm-lock.yaml';calls=[]
        def run(args,cwd):
            calls.append(args)
            if len(calls)==1:
                lock.write_text('owned frozen bytes');return subprocess.CompletedProcess(args,1,'','ERR_PNPM_FETCH_503')
            self.assertEqual('owned frozen bytes',lock.read_text());return subprocess.CompletedProcess(args,0,'','')
        args=['pnpm','install','--no-frozen-lockfile']
        with patch.object(self.m,'run_package_command',side_effect=run):n.package_command(args,str(self.home))
        self.assertIn('--no-frozen-lockfile',calls[1]);self.assertNotIn('--frozen-lockfile',calls[1])
        self.assertNotIn('--ignore-scripts',calls[1]);self.assertNotIn('--ignore-pnpmfile',calls[1])

    def test_cancel_does_not_attempt_second_registry(self):
        n=self.network('mirror')
        with patch.object(self.m,'run_package_command',side_effect=self.m.PluginCancelled('owned cancellation')) as run:
            with self.assertRaises(self.m.PluginCancelled):n.package_command(['npm','pack','--','test'],str(self.home))
            self.assertEqual(1,run.call_count)

    def test_whole_package_timeout_can_fall_back_once_after_runner_cleanup(self):
        n=self.network('mirror')
        with patch.object(self.m,'run_package_command',side_effect=[self.m.PackageCommandTimeout('owned timeout'),subprocess.CompletedProcess([],0,'','')]) as run:
            self.assertEqual(0,n.package_command(['npm','pack','--','test@1.0.0'],str(self.home)).returncode)
            self.assertEqual(2,run.call_count)
        with patch.object(self.m,'run_package_command',side_effect=self.m.PackageCommandTimeout('owned timeout')) as run:
            with self.assertRaises(self.m.PackageCommandTimeout):n.package_command(['npm','pack','--','test'],str(self.home))
            self.assertEqual(2,run.call_count)

    def test_shell_and_offline_preserve_configured_registry(self):
        n=self.network('')
        with patch.object(self.m,'run_package_command',return_value=subprocess.CompletedProcess([],0,'','')) as run:
            n.package_command(['pnpm','install','--offline'],str(self.home),frozen=True,offline=True)
            self.assertFalse(any('registry=' in x for x in run.call_args.args[0]))
            self.assertIn('--prefer-offline',run.call_args.args[0])


if __name__=='__main__':unittest.main(verbosity=2)
