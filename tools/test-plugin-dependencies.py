#!/usr/bin/env python3
"""真实 pnpm 10.34.5 + 本机合成 registry；依赖脚本在隔离暂存目录执行。"""
import base64
import contextlib
import hashlib
import http.server
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
NODE = os.environ.get('DSHA_TEST_NODE', shutil.which('node') or 'node')
PNPM = os.environ.get('DSHA_TEST_PNPM_CLI', str(ROOT / 'app/build/stability-20260914/pnpm-10.34.5/dist/pnpm.cjs'))


def packed(name, version, dependencies=None):
    output = io.BytesIO()
    pkg = {'name': name, 'version': version, 'main': 'index.js', 'dependencies': dependencies or {},
           'scripts': {'postinstall': 'node -e "require(\'fs\').writeFileSync(process.env.DSHA_FIXTURE_SENTINEL,\'executed\')"'}}
    with tarfile.open(fileobj=output, mode='w:gz') as archive:
        for filename, value in {'package.json': json.dumps(pkg), 'index.js': "module.exports = 'owned-" + name + '-' + version + "';\n"}.items():
            data = value.encode(); item = tarfile.TarInfo('package/' + filename); item.size = len(data); item.mode = 0o644
            archive.addfile(item, io.BytesIO(data))
    return output.getvalue(), pkg


class Registry:
    def __init__(self):
        self.newer = False
        self.slow = False
        self.requested = []
        self.packages = {}
        for version in ('1.0.0', '1.1.0'):
            for name in ('dsha-fixture-dep', 'dsha-fixture-leaf'):
                dependencies = {'dsha-fixture-leaf': '^1.0.0'} if name.endswith('-dep') else {}
                self.packages[name, version] = packed(name, version, dependencies)
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *arguments):
                pass

            def do_GET(self):
                name = self.path.strip('/')
                owner.requested.append(name)
                if owner.slow:
                    time.sleep(1)
                if name.endswith('.tgz'):
                    pieces = name.split('/')
                    package, version = pieces[0], pieces[-1][:-4]
                    value = owner.packages.get((package, version))
                    if value is None:
                        self.send_error(404); return
                    data = value[0]
                elif name in ('dsha-fixture-dep', 'dsha-fixture-leaf'):
                    versions = {}
                    for version in ('1.0.0', '1.1.0') if owner.newer else ('1.0.0',):
                        tar, pkg = owner.packages[name, version]
                        versions[version] = dict(pkg, dist={'tarball': owner.url + '/' + name + '/-/' + version + '.tgz',
                            'integrity': 'sha512-' + base64.b64encode(hashlib.sha512(tar).digest()).decode()})
                    data = json.dumps({'name': name, 'versions': versions, 'dist-tags': {'latest': '1.1.0' if owner.newer else '1.0.0'}}).encode()
                else:
                    self.send_error(404); return
                try:
                    self.send_response(200); self.send_header('Content-Length', str(len(data))); self.send_header('Content-Type', 'application/octet-stream' if name.endswith('.tgz') else 'application/json'); self.end_headers(); self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.url = 'http://127.0.0.1:' + str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(3)


class DependenciesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='dsha-deps-test-')
        self.root = Path(self.temporary.name)
        self.registry = Registry()
        self.home = self.root / 'root/.dsh'; self.home.mkdir(parents=True)
        self.store = self.root / 'pnpm-store'
        self.sentinel = self.root / 'script-must-not-run'
        self.userconfig = self.root / 'fixture.npmrc'
        self.userconfig.write_text('registry=' + self.registry.url + '/\nstore-dir=' + str(self.store).replace('\\', '/') + '\n'
                                   '//127.0.0.1:' + str(self.registry.server.server_port) + '/:_authToken=owned-registry-secret\n', encoding='utf8')
        globalconfig = self.root / 'global.npmrc'; globalconfig.write_text('', encoding='utf8')
        self.environment = patch.dict(os.environ, {'DSHA_TEST_ROOT': str(self.root), 'DSH_HOME': '/root/.dsh',
            'NPM_CONFIG_USERCONFIG': str(self.userconfig), 'NPM_CONFIG_GLOBALCONFIG': str(globalconfig),
            'DSHA_FIXTURE_SENTINEL': str(self.sentinel), 'NO_UPDATE_NOTIFIER': '1'})
        self.environment.start()
        spec = importlib.util.spec_from_file_location('dependency_manager_fixture', ASSETS / 'plugin-manager.py')
        self.manager = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.manager)
        original = self.manager.run_package_command
        self.commands = []
        self.work_contents = []

        def run(arguments, cwd, timeout=120):
            self.commands.append(list(arguments))
            if arguments[0] == 'pnpm' and 'install' in arguments:
                self.work_contents.append(sorted(path.name for path in Path(cwd).iterdir()))
            if arguments[0] == 'pnpm':
                arguments = [NODE, PNPM, *arguments[1:]]
            return original(arguments, cwd, timeout)
        self.manager.run_package_command = run
        self.dependencies = self.manager.dependencies()

    def tearDown(self):
        self.environment.stop(); self.registry.close(); self.temporary.cleanup()

    def plugin(self, slot, dependencies=True):
        root = self.root / slot; root.mkdir()
        pkg = {'name': 'dsha-fixture-plugin', 'version': '1.0.0', 'dsh': {'bundle': {'patch': 'cordis.patch.yml'}},
               'devDependencies': {'unused-development-package': '^1.0.0'}}
        if dependencies:
            pkg['dependencies'] = {'dsha-fixture-dep': '^1.0.0'}
        (root / 'package.json').write_text(json.dumps(pkg), encoding='utf8'); (root / 'cordis.patch.yml').write_text('[]\n', encoding='utf8')
        # 作者目录中的 hook/配置不进入独立解析工作目录。
        (root / '.pnpmfile.cjs').write_text("require('fs').writeFileSync(process.env.DSHA_FIXTURE_SENTINEL,'hook');module.exports={};", encoding='utf8')
        (root / '.npmrc').write_text('ignore-scripts=false\n', encoding='utf8')
        return root, pkg

    def prepare(self, root, pkg, **options):
        return self.manager.prepare_dependencies(str(root), pkg, hashlib.sha256(b'owned synthetic archive').hexdigest(), **options)

    def test_unlocked_resolution_runs_dependency_hooks_without_plugin_source_hooks(self):
        first, pkg = self.plugin('first'); snapshot = self.prepare(first, pkg)
        self.assertEqual('locked', snapshot['state']); self.assertEqual('10.34.5', snapshot['managerVersion'])
        self.assertEqual({'1.0.0'}, {entry['version'] for entry in snapshot['resolved']})
        install = next(command for command in self.commands if 'install' in command)
        self.assertIn('--no-frozen-lockfile', install)
        self.assertIn('--config.dangerously-allow-all-builds=true', install)
        self.assertIn('--config.ignore-scripts=false', install)
        self.assertIn('--config.ignore-dep-scripts=false', install)
        self.assertEqual('executed', self.sentinel.read_text(encoding='utf8'))
        self.assertNotIn('.pnpmfile.cjs', self.work_contents[0])
        self.assertNotIn('.npmrc', self.work_contents[0])
        self.registry.newer = True
        second, pkg = self.plugin('second'); again = self.prepare(second, pkg, offline=True)
        self.assertEqual(snapshot['lockSha256'], again['lockSha256'])
        self.assertEqual({'1.0.0'}, {entry['version'] for entry in again['resolved']})
        self.assertIn('--no-frozen-lockfile', next(command for command in reversed(self.commands) if 'install' in command))
        self.assertNotIn('owned-registry-secret', (second / '.dsha-dependencies.json').read_text())
        self.assertNotIn('owned-registry-secret', (second / 'pnpm-lock.yaml').read_text())

    def test_unlocked_refresh_replaces_same_identity_cache(self):
        first, pkg = self.plugin('first'); initial = self.prepare(first, pkg)
        self.registry.newer = True
        refreshed_root, refreshed_pkg = self.plugin('refreshed')
        refreshed = self.prepare(refreshed_root, refreshed_pkg)
        refreshed_install = next(command for command in reversed(self.commands) if 'install' in command)
        self.assertNotIn('--prefer-offline', refreshed_install)
        self.assertEqual({'1.1.0'}, {entry['version'] for entry in refreshed['resolved']})
        cache = next((self.home / 'plugin-dependency-locks').glob('*/state.json'))
        state = json.loads(cache.read_text(encoding='utf8'))
        self.assertEqual(refreshed['lockSha256'], state['lockSha256'])
        lock = cache.parent / 'pnpm-lock.yaml'
        self.assertEqual(refreshed['lockSha256'], self.dependencies.validate_lock(str(lock)))

    def test_corrupt_lock_cache_fails_without_replacing_existing_version(self):
        first, pkg = self.plugin('first'); snapshot = self.prepare(first, pkg)
        lock = next((self.home / 'plugin-dependency-locks').glob('*/pnpm-lock.yaml')); lock.write_text(lock.read_text() + '\n# changed\n', encoding='utf8')
        second, pkg = self.plugin('second')
        with self.assertRaisesRegex(ValueError, '缓存记录不一致'):
            self.prepare(second, pkg)
        self.assertEqual(snapshot['treeSha256'], self.dependencies.inspect(str(first))['treeSha256'])

    @unittest.skipIf(os.name == 'nt', 'symlink creation requires elevated privileges on Windows')
    def test_cache_identity_symlink_is_rejected_without_following_external_state(self):
        first, pkg = self.plugin('first'); self.prepare(first, pkg)
        identity = next((self.home / 'plugin-dependency-locks').iterdir())
        outside = self.root / 'outside-cache'; outside.mkdir()
        identity.rename(outside / 'moved')
        identity.symlink_to(outside / 'moved', target_is_directory=True)
        second, pkg = self.plugin('second')
        with self.assertRaisesRegex(ValueError, '缓存记录路径异常'):
            self.prepare(second, pkg)

    def test_missing_offline_package_preserves_current_tree_and_has_specific_error(self):
        first, pkg = self.plugin('first'); snapshot = self.prepare(first, pkg)
        self.assertTrue(self.store.resolve().is_relative_to(self.root.resolve()))
        shutil.rmtree(self.store)
        second, pkg = self.plugin('second')
        with self.assertRaisesRegex(ValueError, 'ERR_PNPM_'):
            self.prepare(second, pkg, offline=True)
        self.assertEqual(snapshot['treeSha256'], self.dependencies.inspect(str(first))['treeSha256'])

    def test_author_snapshot_claims_do_not_become_local_verification(self):
        root,pkg=self.plugin('author-claims',False)
        tree,packages=self.dependencies.tree(str(root))
        claims={'format':1,'state':'locked','managerVersion':'spoofed-999','integrity':'fully trusted',
                'manifestSha256':hashlib.sha256((root/'package.json').read_bytes()).hexdigest(),
                'treeSha256':tree,'resolved':packages,'lockSha256':''}
        (root/'.dsha-dependencies.json').write_text(json.dumps(claims),encoding='utf8')
        with patch.object(self.manager,'run_package_command',side_effect=AssertionError('no dependencies')):
            snapshot=self.prepare(root,pkg)
        self.assertEqual('no-dependencies',snapshot['state']);self.assertEqual('not-executed',snapshot['managerVersion'])
        self.assertEqual('locked',snapshot['authorClaims']['state']);self.assertEqual('spoofed-999',snapshot['authorClaims']['managerVersion'])
        inspected=self.dependencies.inspect(str(root))
        self.assertEqual('tree-verified',inspected['state']);self.assertEqual('not-executed',inspected['managerVersion'])

    def test_verified_rollback_never_calls_package_manager(self):
        first, pkg = self.plugin('first'); self.prepare(first, pkg)
        with patch.object(self.manager, 'run_package_command', side_effect=AssertionError('rollback must not resolve dependencies')):
            self.assertEqual('tree-verified-lock-present', self.prepare(first, pkg, restoring=True)['state'])
        legacy, pkg = self.plugin('legacy', False)
        with patch.object(self.manager, 'run_package_command', side_effect=AssertionError('legacy rollback must not resolve dependencies')):
            self.assertEqual('legacy-unknown', self.prepare(legacy, pkg, restoring=True)['state'])

    def test_unmanaged_pnpm_version_is_rejected_before_dependency_install(self):
        root, pkg = self.plugin('wrong-pnpm')
        original = self.manager.run_package_command

        def run(arguments, cwd, timeout=120):
            if arguments == ['pnpm', '--version']:
                return subprocess.CompletedProcess(arguments, 0, '11.0.0\n', '')
            return original(arguments, cwd, timeout)

        with patch.object(self.manager, 'run_package_command', side_effect=run):
            with self.assertRaisesRegex(ValueError, '需要 10.34.5'):
                self.prepare(root, pkg)
        self.assertFalse((root / '.dsha-dependencies.json').exists())

    def test_changed_confirmation_tree_and_credential_bearing_lock_are_rejected(self):
        first, pkg = self.plugin('first', False); self.prepare(first, pkg)
        (first / 'cordis.patch.yml').write_text('changed', encoding='utf8')
        with self.assertRaisesRegex(ValueError, '确认快照不一致'):
            self.dependencies.inspect(str(first))
        second, pkg = self.plugin('second'); (second / 'pnpm-lock.yaml').write_text('tarball: https://user:owned-secret@example.invalid/pkg.tgz', encoding='utf8')
        with self.assertRaisesRegex(ValueError, '认证信息'):
            self.prepare(second, pkg)

    def test_cancel_during_resolution_does_not_create_a_verified_snapshot(self):
        root, pkg = self.plugin('cancelled'); self.registry.slow = True
        original = self.manager.run_package_command
        deadline = [None]

        def cancellation():
            if deadline[0] is not None and time.monotonic() > deadline[0]:
                raise self.manager.PluginCancelled('owned cancellation')

        def run(arguments, cwd, timeout=120):
            if 'install' in arguments:
                deadline[0] = time.monotonic() + .25
            return original(arguments, cwd, timeout)
        with patch.object(self.manager, 'run_package_command', side_effect=run), patch.object(self.manager, 'check_cancel', side_effect=cancellation):
            with self.assertRaises(self.manager.PluginCancelled):
                self.prepare(root, pkg)
        self.assertFalse((root / '.dsha-dependencies.json').exists()); self.assertFalse(self.sentinel.exists())


if __name__ == '__main__':
    if not Path(PNPM).is_file():
        raise SystemExit('Set DSHA_TEST_PNPM_CLI to the verified pnpm 10.34.5 CLI before running these tests')
    unittest.main(verbosity=2)
