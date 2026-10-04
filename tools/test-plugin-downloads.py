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
import sys
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
            result = subprocess.CompletedProcess(args, 1 if len(calls)==1 else 0, '', 'E404' if len(calls)==1 else '')
            result.confirmed_exit = True
            return result
        with patch.object(self.m,'run_package_command',side_effect=run):
            self.assertEqual(0,n.package_command(['npm','pack','--ignore-scripts','--','test@1.2.3'],str(self.home),frozen=True).returncode)
        self.assertEqual(2,len(calls))
        for args in calls:
            self.assertEqual('test@1.2.3',args[-1]);self.assertIn('--prefer-offline',args)
            self.assertTrue(args.index(next(a for a in args if a.startswith('--registry='))) < args.index('--'))
        for error in ['EINTEGRITY','ERR_PNPM_TARBALL_INTEGRITY','EACCES','CERT_HAS_EXPIRED','ERR_PNPM_OUTDATED_LOCKFILE']:
            with patch.object(self.m,'run_package_command',return_value=subprocess.CompletedProcess([],1,'',error)) as run:
                n.package_command(['npm','pack','--','test@latest'],str(self.home));self.assertEqual(1,run.call_count)

    def test_dependency_failure_never_replays_install_and_keeps_unlocked_hooks(self):
        n=self.network('mirror'); lock=self.home/'pnpm-lock.yaml';calls=[]
        def run(args,cwd):
            calls.append(args)
            if len(calls)==1:
                lock.write_text('owned frozen bytes');return subprocess.CompletedProcess(args,1,'','ERR_PNPM_FETCH_503')
            self.assertEqual('owned frozen bytes',lock.read_text());return subprocess.CompletedProcess(args,0,'','')
        args=['pnpm','install','--no-frozen-lockfile']
        with patch.object(self.m,'run_package_command',side_effect=run):
            self.assertEqual(1,n.package_command(args,str(self.home)).returncode)
        self.assertEqual(1,len(calls));self.assertEqual('owned frozen bytes',lock.read_text())
        self.assertIn('--no-frozen-lockfile',calls[0]);self.assertNotIn('--frozen-lockfile',calls[0])
        self.assertNotIn('--ignore-scripts',calls[0]);self.assertNotIn('--ignore-pnpmfile',calls[0])

    def test_cancel_does_not_attempt_second_registry(self):
        n=self.network('mirror')
        with patch.object(self.m,'run_package_command',side_effect=self.m.PluginCancelled('owned cancellation')) as run:
            with self.assertRaises(self.m.PluginCancelled):n.package_command(['npm','pack','--','test'],str(self.home))
            self.assertEqual(1,run.call_count)

    def test_whole_package_timeout_can_fall_back_once_after_runner_cleanup(self):
        n=self.network('mirror')
        with patch.object(self.m,'run_package_command',side_effect=[self.m.PackageCommandTimeout('owned timeout',confirmed_exit=True),subprocess.CompletedProcess([],0,'','')]) as run:
            self.assertEqual(0,n.package_command(['npm','pack','--ignore-scripts','--','test@1.0.0'],str(self.home)).returncode)
            self.assertEqual(2,run.call_count)
        with patch.object(self.m,'run_package_command',side_effect=self.m.PackageCommandTimeout('owned timeout',confirmed_exit=True)) as run:
            with self.assertRaises(self.m.PackageCommandTimeout):n.package_command(['npm','pack','--ignore-scripts','--','test'],str(self.home))
            self.assertEqual(2,run.call_count)

    def test_download_timeout_without_exit_proof_or_script_gate_is_not_replayed(self):
        n=self.network('mirror')
        for args, error in [(['npm','pack','--ignore-scripts','--','test'], self.m.PackageCommandTimeout('owned unknown')),
                            (['npm','pack','--','test'], self.m.PackageCommandTimeout('owned closed',confirmed_exit=True))]:
            with patch.object(self.m,'run_package_command',side_effect=error) as run:
                with self.assertRaises(self.m.PackageCommandTimeout):n.package_command(args,str(self.home))
                self.assertEqual(1,run.call_count)

    def test_actual_child_silent_stall_uses_idle_budget_and_proven_close(self):
        started=time.monotonic()
        with self.assertRaises(self.m.PackageCommandTimeout) as caught:
            self.m.run_package_command([sys.executable,'-c','import time; time.sleep(20)'],str(self.home),timeout=3,idle_timeout=.2)
        self.assertTrue(caught.exception.confirmed_exit);self.assertLess(time.monotonic()-started,2)
        self.assertIn('没有新进展',str(caught.exception))

    def test_actual_output_progress_extends_idle_but_never_overall_budget(self):
        code='import time\nfor _ in range(100):\n print("progress",flush=True);time.sleep(.03)'
        started=time.monotonic()
        with self.assertRaises(self.m.PackageCommandTimeout) as caught:
            self.m.run_package_command([sys.executable,'-c',code],str(self.home),timeout=.5,idle_timeout=.2)
        self.assertTrue(caught.exception.confirmed_exit);self.assertIn('整体时限',str(caught.exception))
        self.assertLess(time.monotonic()-started,2)
        result=self.m.run_package_command([sys.executable,'-c','import time\nfor _ in range(8):\n print("ok",flush=True);time.sleep(.05)'],str(self.home),timeout=2,idle_timeout=.2)
        self.assertEqual(0,result.returncode);self.assertEqual(8,result.stdout.count('ok'));self.assertTrue(result.confirmed_exit)

    def test_actual_flood_is_bounded_and_progress_reports_counts_only(self):
        result=self.m.run_package_command([sys.executable,'-c','import sys;sys.stdout.write("a"*2200000);sys.stderr.write("b"*2200000)'],str(self.home),timeout=3,idle_timeout=1)
        self.assertEqual(2200000,result.stdout_bytes);self.assertEqual(2200000,result.stderr_bytes)
        self.assertLessEqual(len(result.stdout),1024*1024);self.assertLess(len(result.stderr),1024*1024+20000);self.assertTrue(result.output_truncated)
        self.m.run_package_command([sys.executable,'-c','import time\nfor _ in range(24):\n print("opaque-test-value",flush=True);time.sleep(.05)'],str(self.home),timeout=3,idle_timeout=.3)
        value=json.loads(Path(self.m.task_file('.json')).read_text(encoding='utf8'))
        self.assertIn('字节',value['message']);self.assertNotIn('opaque-test-value',value['message'])

    def test_actual_dependency_hook_side_effect_executes_once_on_network_error_and_timeout(self):
        n=self.network('mirror'); original=self.m.run_package_command
        for stall in [False,True]:
            counter=self.home/('stall-counter' if stall else 'error-counter');calls=[]
            code='from pathlib import Path\nimport sys,time\np=Path(sys.argv[1]);p.write_text(p.read_text()+"1" if p.exists() else "1")\n'+('time.sleep(20)' if stall else 'print("ERR_PNPM_FETCH_503",file=sys.stderr);sys.exit(1)')
            def run(args,cwd):
                calls.append(args)
                return original([sys.executable,'-c',code,str(counter)],cwd,timeout=.4,idle_timeout=.2)
            with patch.object(self.m,'run_package_command',side_effect=run):
                if stall:
                    with self.assertRaises(self.m.PackageCommandTimeout):n.package_command(['pnpm','install','--no-frozen-lockfile'],str(self.home))
                else:self.assertEqual(1,n.package_command(['pnpm','install','--no-frozen-lockfile'],str(self.home)).returncode)
            self.assertEqual('1',counter.read_text());self.assertEqual(1,len(calls));self.assertNotIn('--ignore-scripts',calls[0])

    def test_actual_cancel_closes_only_owned_child_without_source_replay(self):
        counter=self.home/'cancel-counter'; original=self.m.run_package_command;n=self.network('mirror');calls=[]
        def cancel():
            if counter.exists():raise self.m.PluginCancelled('owned cancel')
        def run(args,cwd):
            calls.append(args)
            return original([sys.executable,'-c','from pathlib import Path\nimport sys,time\nPath(sys.argv[1]).write_text("1");time.sleep(20)',str(counter)],cwd,timeout=3,idle_timeout=1)
        with patch.object(self.m,'run_package_command',side_effect=run),patch.object(self.m,'check_cancel',side_effect=cancel):
            with self.assertRaises(self.m.PluginCancelled):n.package_command(['pnpm','install'],str(self.home))
        self.assertEqual('1',counter.read_text());self.assertEqual(1,len(calls))

    def test_unknown_exit_preserves_candidate_in_place_and_never_replays(self):
        n=self.network('mirror'); original=self.m.subprocess.Popen; processes=[];counter=self.home/'unknown-counter'
        def launch(args,**kwargs):
            process=original([sys.executable,'-c','from pathlib import Path\nimport sys,time\nPath(sys.argv[1]).write_text("1");time.sleep(20)',str(counter)],**kwargs)
            processes.append(process);return process
        slot=''
        try:
            with patch.object(self.m.subprocess,'Popen',side_effect=launch),patch.object(self.m,'_stop_package_process',return_value=False):
                with self.m.candidate_workspace(prefix='plugin-deps-',dir=str(self.home)) as slot:
                    identity=os.stat(slot).st_ino
                    with self.assertRaises(self.m.PackageCommandUnknown):
                        self.m.run_package_command(['pnpm','install'],slot,timeout=.3,idle_timeout=.2)
            self.assertTrue(Path(slot).is_dir());self.assertEqual(identity,os.stat(slot).st_ino)
            self.assertTrue((Path(slot)/'.dsha-package-command-unknown.json').is_file());self.assertEqual('1',counter.read_text())
            self.assertEqual(1,len(processes))
            with patch.object(self.m,'run_package_command',side_effect=self.m.PackageCommandUnknown('unconfirmed')) as run:
                with self.assertRaises(self.m.PackageCommandUnknown):n.package_command(['npm','pack','--ignore-scripts','--','test'],slot)
                self.assertEqual(1,run.call_count)
        finally:
            for process in processes:
                if process.poll() is None:process.kill()
                process.wait(timeout=3)

    def test_candidate_workspace_closes_success_but_retains_all_nested_unknown_slots(self):
        with self.m.candidate_workspace(prefix='plugin-npm-',dir=str(self.home)) as ordinary:
            self.put(Path(ordinary)/'source','preserved until successful close')
        self.assertFalse(Path(ordinary).exists())
        with self.m.candidate_workspace(prefix='plugin-import-',dir=str(self.home)) as outer:
            with self.m.candidate_workspace(prefix='plugin-deps-',dir=str(self.home)) as inner:
                self.m._retain_package_candidates(inner)
        self.assertTrue(Path(outer).is_dir());self.assertTrue(Path(inner).is_dir())

    def test_shell_and_offline_preserve_configured_registry(self):
        n=self.network('')
        with patch.object(self.m,'run_package_command',return_value=subprocess.CompletedProcess([],0,'','')) as run:
            n.package_command(['pnpm','install','--offline'],str(self.home),frozen=True,offline=True)
            self.assertFalse(any('registry=' in x for x in run.call_args.args[0]))
            self.assertIn('--prefer-offline',run.call_args.args[0])


if __name__=='__main__':unittest.main(verbosity=2)
