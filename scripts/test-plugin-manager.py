#!/usr/bin/env python3
"""少量插件回归检查：仅操作临时目录，不调用网络/pnpm，不碰真实用户数据。"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ASSET = Path(__file__).resolve().parents[1] / "app/src/main/assets/plugin-manager.py"


class PluginTestBase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {"DSHA_TEST_ROOT": str(self.root), "DSH_HOME": "/root/.dsh"})
        self.env.start()
        spec = importlib.util.spec_from_file_location("manager", ASSET)
        self.manager = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.manager)
        self.home = self.root / "root/.dsh"
        self.home.mkdir(parents=True)
        self.out = io.StringIO()
        self.capture = contextlib.redirect_stdout(self.out)
        self.capture.__enter__()

    def tearDown(self):
        self.capture.__exit__(None, None, None)
        self.env.stop()
        self.temp.cleanup()

    def package(self, path, name="dsh-demo", version="1"):
        path.mkdir(parents=True, exist_ok=True)
        (path / "package.json").write_text(json.dumps({
            "name": name, "version": version, "main": "index.js",
            "dsh": {"bundle": {"patch": "cordis.patch.yml"}}}), encoding="utf-8")
        (path / "index.js").write_text("export default {};", encoding="utf-8")
        (path / "cordis.patch.yml").write_text("[]", encoding="utf-8")
        return path

    def marker(self, name):
        marker = Path(self.manager.builtin.marker_path(name))
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.touch()

class PluginManagerTest(PluginTestBase):
    def test_scoped_multi_export_import_preserves_disabled_state_and_bytes(self):
        names = ["@sample/demo", "dsh-extra"]
        for name in names:
            self.package(self.home / "plugin-src" / name, name)
            self.marker(name)
        archive = self.home / "out.tar.gz"
        self.manager.cmd_export(json.dumps(names), str(archive))
        self.assertEqual(0, self.manager.cmd_import(str(archive)))
        manifest = self.manager.builtin.read_manifest()
        self.assertEqual(list(self.manager.builtin.OFFICIAL_BUNDLES), manifest["dsh"]["profile"]["bundles"])
        for name in names:
            self.assertEqual("link:/root/.dsh/plugin-src/" + name, manifest["dependencies"][name])
            self.assertEqual("export default {};", (self.home / "plugin-src" / name / "index.js").read_text())
        self.assertFalse(list((self.home / "plugin-src").glob(".install-*")))

    def test_bad_update_keeps_existing_plugin_and_manifest(self):
        name = "dsh-demo"
        self.package(self.home / "plugin-src" / name, name, "old")
        original = {"name": "existing", "dependencies": {}, "dsh": {"profile": {"bundles": []}}}
        self.manager.builtin.write_manifest(original)
        incoming = self.package(self.root / "incoming", name, "new")
        with patch.object(self.manager.os, "symlink", side_effect=OSError("link unavailable")):
            with self.assertRaises(OSError):
                self.manager.register_plugin(str(incoming), "")
        self.assertEqual(original, self.manager.builtin.read_manifest())
        self.assertEqual("old", json.loads((self.home / "plugin-src" / name / "package.json").read_text())["version"])

    def test_delete_scoped_plugin_only_and_protect_builtins(self):
        name = "@sample/demo"
        self.package(self.home / "plugin-src" / name, name)
        other = self.package(self.home / "plugin-src/dsh-other", "dsh-other")
        self.marker(name)
        config = self.home / "settings.json"
        config.write_text('{"keep":true}')
        self.manager.builtin.write_manifest({
            "dependencies": {name: "link:/root/.dsh/plugin-src/" + name, "dsh-other": "1"},
            "dsh": {"profile": {"bundles": [name, "dsh-other"]}}})
        sources = self.home / "plugin-sources.json"
        sources.write_text(json.dumps({name: "https://example.org/plugin.tgz"}))
        original = self.manager.builtin.read_manifest()
        with patch.object(self.manager.builtin, "write_manifest", side_effect=OSError("disk unavailable")):
            with self.assertRaises(OSError):
                self.manager.cmd_delete(name)
        self.assertTrue((self.home / "plugin-src" / name / "index.js").is_file())
        self.assertTrue(Path(self.manager.builtin.marker_path(name)).exists())
        self.assertEqual(original, self.manager.builtin.read_manifest())
        self.assertIn(name, json.loads(sources.read_text()))
        self.manager.cmd_delete(name)
        self.assertFalse((self.home / "plugin-src" / name).exists())
        self.assertFalse(Path(self.manager.builtin.marker_path(name)).exists())
        self.assertTrue(other.is_dir())
        self.assertNotIn(name, json.loads(sources.read_text()))
        self.assertEqual('{"keep":true}', config.read_text())
        self.assertEqual(["dsh-other"], self.manager.builtin.read_manifest()["dsh"]["profile"]["bundles"])
        for protected in ["@deepseek-ai/dsh-base", "dsh-web-mobile", "../settings.json"]:
            with self.assertRaises(ValueError):
                self.manager.cmd_delete(protected)

    def test_subdirectory_does_not_install_neighbor_plugins(self):
        staging = self.root / "staging"
        selected = self.package(staging / "repo-main/packages/one", "dsh-one")
        self.package(staging / "repo-main/packages/two", "dsh-two")
        self.assertEqual([str(selected)], self.manager.find_plugin_roots(str(staging), "packages/one"))
        with self.assertRaises(ValueError):
            self.manager.find_plugin_roots(str(staging), "packages/missing")

    def test_zip_tar_and_absolute_or_traversal_entries(self):
        for filename in ("../escape", "/root/escape", "C:/escape"):
            archive = self.root / "bad.zip"
            with zipfile.ZipFile(archive, "w") as z:
                z.writestr(filename, "bad")
            with self.assertRaises(ValueError):
                self.manager.extract_archive(str(archive), str(self.root / "unzip"))
        archive = self.root / "good.tar"
        with tarfile.open(archive, "w") as tar:
            entry = tarfile.TarInfo("package/index.js")
            entry.size, entry.mode = 2, 0o755
            tar.addfile(entry, io.BytesIO(b"ok"))
        self.manager.extract_archive(str(archive), str(self.root / "untar"))
        self.assertEqual(b"ok", (self.root / "untar/package/index.js").read_bytes())

    def test_invalid_bundle_and_missing_build_are_not_reported_as_success(self):
        incoming = self.package(self.root / "bad")
        (incoming / "index.js").unlink()
        with self.assertRaisesRegex(ValueError, "入口"):
            self.manager.plugin_package(str(incoming))
        manifest = incoming / "package.json"
        manifest.write_text(json.dumps({"name": "dsh-fake"}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "dsh.bundle.patch"):
            self.manager.plugin_package(str(incoming))
        # 一个包中一好一坏：结果必须是 partial，不能因为出现一个成功就吞掉失败。
        good = self.package(self.root / "good", "dsh-good")
        bad = self.package(self.root / "bad-build", "dsh-bad")
        (bad / "index.js").unlink()
        self.marker("dsh-good")
        archive = self.root / "mixed.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            tar.add(good, arcname="plugins/good")
            tar.add(bad, arcname="plugins/bad")
        self.assertEqual(1, self.manager.cmd_import(str(archive)))
        last = json.loads(self.out.getvalue().strip().split("\n")[-1].removeprefix("PLUGIN_RESULT: "))
        self.assertEqual("partial", last["status"])
        self.assertEqual(["dsh-good"], last["installed"])

    def test_slash_branch_resolves_requested_commit_and_subdirectory(self):
        responses = []
        def response(url):
            responses.append(url)
            if url.endswith("feature%2Fandroid"):
                return io.BytesIO(json.dumps({"sha": "abc123" + "a" * 34}).encode())
            raise self.manager.urllib.error.HTTPError(url, 404, "missing", {}, None)
        with patch.object(self.manager, "open_url", side_effect=response), \
                patch.object(self.manager, "cmd_download", return_value=0) as download:
            self.manager.cmd_github("owner", "repo", "feature/android/packages/demo")
            args = download.call_args.args
            self.assertEqual("https://codeload.github.com/owner/repo/tar.gz/abc123" + "a" * 34, args[0])
            self.assertEqual("packages/demo", args[1])

    def test_github_zip_uses_official_download_and_preserves_ref(self):
        rewrite = self.manager.archive_download_url
        self.assertEqual("https://codeload.github.com/Minglink/dsh-infinite-gen-3/zip/refs/heads/master",
                         rewrite("https://github.com/Minglink/dsh-infinite-gen-3/archive/refs/heads/master.zip"))
        self.assertEqual("https://codeload.github.com/o/r/tar.gz/refs/tags/v1.0",
                         rewrite("https://github.com/o/r/archive/refs/tags/v1.0.tar.gz"))
        external = "https://example.org/plugin.zip"
        self.assertEqual(external, rewrite(external))

    def test_npm_packs_only_requested_package_and_rejects_options(self):
        def pack(args, **kwargs):
            destination = Path(args[args.index("--pack-destination") + 1])
            archive = destination / "sample-demo-1.0.0.tgz"
            archive.write_bytes(b"test")
            self.assertEqual(["--", "@sample/demo@1.0.0"], args[-2:])
            self.assertIn("--ignore-scripts", args)
            return self.manager.subprocess.CompletedProcess(args, 0, '[{"filename":"sample-demo-1.0.0.tgz"}]', '')
        with patch.object(self.manager.shutil, "which", return_value="/usr/bin/npm"), \
                patch.object(self.manager, "run_package_command", side_effect=pack), \
                patch.object(self.manager, "cmd_import", return_value=0) as install:
            self.assertEqual(0, self.manager.cmd_npm("@sample/demo@1.0.0"))
            self.assertEqual("npm:@sample/demo@1.0.0", install.call_args.kwargs["source"])
        for invalid in ["--global", "../package", "https://example.com/x", "demo;rm -rf /", ""]:
            with self.assertRaises(ValueError):
                self.manager.cmd_npm(invalid)


class PluginLifecycleTest(PluginTestBase):
    def test_imported_plugins_share_runtime_modules_without_overwriting_user_deps(self):
        runtime = self.root / 'usr/local/lib/node_modules/@deepseek-ai/dsh'
        self.package(runtime, name='@deepseek-ai/dsh')
        self.package(runtime / 'node_modules/@deepseek-ai/dsh-tools', name='@deepseek-ai/dsh-tools')
        self.package(runtime / 'node_modules/react', name='react')
        owned = self.package(self.home / 'node_modules/react', name='react', version='user')
        self.manager.register_plugin(self.package(self.root / 'plugin'), '')
        target = self.home / 'node_modules/@deepseek-ai/dsh-tools'
        self.assertTrue(target.is_symlink())
        self.assertEqual((runtime / 'node_modules/@deepseek-ai/dsh-tools').resolve(), target.resolve())
        self.assertEqual('user', json.loads((owned / 'package.json').read_text())['version'])
        self.assertEqual(0, self.manager.builtin.ensure_runtime_modules())

    def test_shared_runtime_modules_reject_scope_escape(self):
        runtime = self.root / 'usr/local/lib/node_modules/@deepseek-ai/dsh'
        self.package(runtime / 'node_modules/@deepseek-ai/dsh-tools', name='@deepseek-ai/dsh-tools')
        outside = self.root / 'outside-shared'; outside.mkdir()
        modules = self.home / 'node_modules'; modules.mkdir()
        os.symlink(outside, modules / '@deepseek-ai', target_is_directory=True)
        with self.assertRaises(RuntimeError): self.manager.builtin.ensure_runtime_modules()
        self.assertEqual([], list(outside.iterdir()))

    def test_npm_tarball_updates_use_latest_registry_package(self):
        self.manager.register_plugin(self.package(self.root / 'v1', name='@scope/demo'), 'https://registry.npmjs.org/@scope/demo/-/demo-1.0.0.tgz')
        self.assertEqual("npm @scope/demo@latest", self.manager.lifecycle().source_command('@scope/demo'))

    def archive(self, name='dsh-demo', version='1.0.0'):
        root = self.package(self.root / ('source-' + version), name, version)
        archive = self.root / ('archive-' + version + '.zip')
        with zipfile.ZipFile(archive, 'w') as out:
            for file in root.iterdir(): out.write(file, 'package/' + file.name)
        return archive

    def test_rollback_retains_only_one_previous_version_and_disabled_state(self):
        for version in ('1.0.0', '2.0.0', '3.0.0'):
            self.manager.register_plugin(self.package(self.root / version, version=version), 'npm:dsh-demo@' + version)
        life = self.manager.lifecycle()
        self.assertEqual('2.0.0', life.history_info('dsh-demo')['version'])
        self.marker('dsh-demo')
        life.rollback('dsh-demo')
        self.assertEqual('2.0.0', json.loads((self.home / 'plugin-src/dsh-demo/package.json').read_text())['version'])
        self.assertEqual('3.0.0', life.history_info('dsh-demo')['version'])
        self.assertTrue(Path(self.manager.builtin.marker_path('dsh-demo')).is_file())
        self.manager.cmd_delete('dsh-demo')
        self.assertFalse(Path(life.history_path('dsh-demo')).exists())

    def test_failed_update_restores_current_package_and_prior_history(self):
        for version in ('1.0.0', '2.0.0'):
            self.manager.register_plugin(self.package(self.root / version, version=version), 'source-' + version)
        before = self.manager.builtin.read_manifest()
        with patch.object(self.manager.builtin, 'write_manifest', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                self.manager.register_plugin(self.package(self.root / '3.0.0', version='3.0.0'), 'new-source')
        self.assertEqual(before, self.manager.builtin.read_manifest())
        self.assertEqual('2.0.0', json.loads((self.home / 'plugin-src/dsh-demo/package.json').read_text())['version'])
        self.assertEqual('1.0.0', self.manager.lifecycle().history_info('dsh-demo')['version'])
        self.assertEqual('source-2.0.0', self.manager.read_json(self.home / 'plugin-sources.json')['dsh-demo'])

    def test_preview_does_not_install_until_confirmed_and_verifies_content(self):
        archive = self.archive()
        life = self.manager.lifecycle()
        def download(url, target):
            import shutil
            shutil.copyfile(archive, target)
        request = {'command': "download 'https://example.com/demo.zip'", 'name': 'dsh-demo', 'version': '1.0.0'}
        with patch.object(self.manager, 'download', side_effect=download):
            preview = life.inspect(request, False)
            self.assertFalse((self.home / 'plugin-src/dsh-demo').exists())
            self.assertEqual('1.0.0', preview['items'][0]['version'])
            self.assertEqual(0, life.install_preview(preview['previewId']))
            self.assertTrue((self.home / 'plugin-src/dsh-demo').exists())
            with self.assertRaises(ValueError): life.inspect(dict(request, sha256='a' * 64), False)
            preview = life.inspect(request, False)
            (Path(life.preview_path(preview['previewId'])) / 'archive').write_bytes(b'changed')
            with self.assertRaises(ValueError): life.install_preview(preview['previewId'])

    def test_update_preview_is_bound_to_plugin_identity_and_installed_version(self):
        self.manager.register_plugin(self.package(self.root / 'v1', version='1.0.0'), 'https://example.com/demo.zip')
        archive = self.archive(version='2.0.0')
        def download(url, target):
            import shutil
            shutil.copyfile(archive, target)
        life = self.manager.lifecycle()
        with patch.object(self.manager, 'download', side_effect=download), patch.object(life, 'update_metadata', return_value=(
                {'version': '2.0.0', 'compatibilityMessage': 'test'},
                {'command': "download 'https://example.com/demo.zip'", 'name': 'dsh-demo', 'version': '2.0.0'})):
            life.check_updates('dsh-demo')
            state = self.manager.read_json(self.home / 'plugin-updates.json')['dsh-demo']
            self.assertTrue(state['available'])
            self.assertEqual('2.0.0', state['latestVersion'])
            life.prepare_update('dsh-demo')
            preview = json.loads(self.out.getvalue().split('PLUGIN_RESULT: ')[-1])['preview']
            self.manager.register_plugin(self.package(self.root / 'v3', version='3.0.0'), 'https://example.com/demo.zip')
            self.manager.cmd_list()
            listing = json.loads(self.out.getvalue().split('PLUGIN_RESULT: ')[-1])
            changed = next(item for item in listing['items'] if item['name'] == 'dsh-demo')
            self.assertFalse(changed['updateAvailable'])
            self.assertEqual('', changed['updateMessage'])
            with self.assertRaises(ValueError): life.install_preview(preview['previewId'])
            with self.assertRaises(ValueError): life.inspect({'command': "download 'https://example.com/demo.zip'", 'name': 'other-plugin'}, False)

    def test_safe_mode_preserves_manual_disables_and_restores_only_its_own_changes(self):
        for name in ('demo-one', 'demo-two', 'demo-three'):
            self.manager.register_plugin(self.package(self.root / name, name=name, version='1.0.0'), '')
        self.manager.builtin.disable_plugin('demo-three')
        life = self.manager.lifecycle()
        life.safe_mode('on')
        bundles = self.manager.builtin.read_manifest()['dsh']['profile']['bundles']
        self.assertFalse(set(bundles) & {'demo-one', 'demo-two', 'demo-three'})
        self.assertTrue(set(self.manager.builtin.OFFICIAL_BUNDLES).issubset(bundles))
        self.manager.builtin.disable_plugin('demo-two')
        life.safe_mode('off')
        bundles = self.manager.builtin.read_manifest()['dsh']['profile']['bundles']
        self.assertIn('demo-one', bundles)
        self.assertNotIn('demo-two', bundles)
        self.assertNotIn('demo-three', bundles)

    def test_rejects_import_through_scope_link_outside_managed_directory(self):
        outside = self.root / 'outside'; outside.mkdir()
        parent = self.home / 'plugin-src'; parent.mkdir()
        os.symlink(outside, parent / '@scope', target_is_directory=True)
        with self.assertRaises(ValueError):
            self.manager.register_plugin(self.package(self.root / 'scoped', name='@scope/demo'), '')
        self.assertEqual([], list(outside.iterdir()))


class PluginOptimizationTest(PluginTestBase):
    @unittest.skipIf(os.name == 'nt', '需要 Linux 文件锁')
    def test_cancel_while_waiting_for_data_lock(self):
        import fcntl
        import threading
        import time
        cancel = self.task()
        with open(self.home.parent / '.dsha-data.lock', 'a') as held:
            fcntl.flock(held, fcntl.LOCK_EX)
            timer = threading.Timer(.2, cancel.touch)
            timer.start()
            started = time.monotonic()
            try:
                with self.assertRaises(self.manager.PluginCancelled):
                    with self.manager.builtin.operation_lock(self.manager.check_cancel):
                        self.fail('取消任务不应取得尚未释放的锁')
                self.assertLess(time.monotonic() - started, 1.0)
            finally:
                timer.join()
                fcntl.flock(held, fcntl.LOCK_UN)

    def install_sample(self, source='npm:dsh-demo@1.0.0'):
        self.manager.register_plugin(self.package(self.root / 'original', version='1.0.0'), source)

    def task(self):
        self.env_task = patch.dict(os.environ, {'DSHA_PLUGIN_TASK': 'a' * 32})
        self.env_task.start()
        self.addCleanup(self.env_task.stop)
        return Path(self.manager.task_file('.cancel'))

    def test_npm_semver_rules_are_used_by_python_adapter(self):
        life = self.manager.lifecycle()
        fn = life.package_metadata.__globals__['accepts_version']
        for installed, requirement, expected in [('0.5.0', '^0', True), ('1.8.0', '~1', True),
                ('1.2.9', '1.2', True), ('0.1.2-rc.1', '^0.1.1-alpha', False),
                ('0.1.2-rc.1', '^0.1.2-rc.0', True), ('1.0.0', 'latest', None)]:
            self.assertIs(expected, fn(installed, requirement))

    def test_unchanged_npm_version_never_downloads_archive_or_creates_preview(self):
        self.install_sample()
        pkg = {'name': 'dsh-demo', 'version': '1.0.0', 'engines': {'dsh': '^0'}}
        life = self.manager.lifecycle()
        with patch.object(self.manager, 'open_url', return_value=io.BytesIO(json.dumps(pkg).encode())) as get, \
                patch.object(self.manager, 'cmd_npm', side_effect=AssertionError('must not download')):
            life.check_updates('dsh-demo')
        self.assertEqual('https://registry.npmjs.org/dsh-demo/latest', get.call_args.args[0])
        state = self.manager.read_json(self.home / 'plugin-updates.json')['dsh-demo']
        self.assertFalse(state['available'])
        self.assertNotIn('previewId', state)
        self.assertFalse((self.home / 'plugin-previews').exists())

    def test_release_metadata_discovers_update_without_fetching_asset(self):
        self.install_sample('https://github.com/o/repo/releases/download/v1.0.0/demo.zip')
        release = {'tag_name': 'v2.0.0', 'assets': [{'name': 'demo.zip',
            'browser_download_url': 'https://github.com/o/repo/releases/download/v2.0.0/demo.zip', 'digest': 'sha256:' + 'b' * 64}]}
        with patch.object(self.manager, 'open_url', return_value=io.BytesIO(json.dumps(release).encode())) as get, \
                patch.object(self.manager, 'download', side_effect=AssertionError('must not download')):
            self.manager.lifecycle().check_updates('dsh-demo')
        self.assertTrue(get.call_args.args[0].endswith('/releases/latest'))
        state = self.manager.read_json(self.home / 'plugin-updates.json')['dsh-demo']
        self.assertTrue(state['available'])
        self.assertEqual('b' * 64, state['request']['sha256'])

    def test_fixed_archive_reports_limitation_without_download(self):
        self.install_sample('https://example.org/demo.zip')
        with patch.object(self.manager, 'open_url', side_effect=AssertionError('no metadata endpoint')):
            self.manager.lifecycle().check_updates('dsh-demo')
        state = self.manager.read_json(self.home / 'plugin-updates.json')['dsh-demo']
        self.assertFalse(state['available'])
        self.assertIn('固定归档', state['message'])

    def test_cancel_during_download_removes_preview_and_preserves_installed_data(self):
        self.install_sample()
        cancel = self.task()
        before = (self.home / 'plugin-src/dsh-demo/package.json').read_bytes()
        class Response(io.BytesIO):
            headers = {'Content-Length': '20'}
            def read(inner, count=-1):
                cancel.touch()
                return super().read(count)
        with patch.object(self.manager, 'open_url', return_value=Response(b'abcdefghij' * 2)):
            with self.assertRaises(self.manager.PluginCancelled):
                self.manager.lifecycle().inspect({'command': 'download https://example.org/demo.zip'}, False)
        self.assertEqual(before, (self.home / 'plugin-src/dsh-demo/package.json').read_bytes())
        self.assertEqual([], list((self.home / 'plugin-previews').iterdir()))
        self.assertFalse(list(self.home.glob('plugin-download-*')))

    def test_cancel_before_commit_keeps_old_version_and_manifest(self):
        self.install_sample()
        cancel = self.task()
        old = self.manager.builtin.read_manifest()
        incoming = self.package(self.root / 'new', version='2.0.0')
        copytree = self.manager.shutil.copytree
        def copied(*args, **kwargs):
            value = copytree(*args, **kwargs)
            cancel.touch()
            return value
        with patch.object(self.manager.shutil, 'copytree', side_effect=copied):
            with self.assertRaises(self.manager.PluginCancelled): self.manager.register_plugin(incoming, '')
        self.assertEqual(old, self.manager.builtin.read_manifest())
        self.assertEqual('1.0.0', self.manager.read_json(self.home / 'plugin-src/dsh-demo/package.json')['version'])
        self.assertFalse(list((self.home / 'plugin-src').glob('.install-*')))

    def test_cancel_arriving_in_commit_finishes_consistent_transaction(self):
        self.install_sample()
        cancel = self.task()
        incoming = self.package(self.root / 'new', version='2.0.0')
        write = self.manager.builtin.write_manifest
        def committed(doc):
            cancel.touch()
            return write(doc)
        with patch.object(self.manager.builtin, 'write_manifest', side_effect=committed):
            self.manager.register_plugin(incoming, 'npm:dsh-demo@2.0.0')
        self.assertEqual('2.0.0', self.manager.read_json(self.home / 'plugin-src/dsh-demo/package.json')['version'])
        self.assertEqual('1.0.0', self.manager.lifecycle().history_info('dsh-demo')['version'])
        self.assertIn('dsh-demo', self.manager.builtin.read_manifest()['dependencies'])

    def test_cancelling_owned_package_subprocess_cleans_child(self):
        import threading
        import time
        import sys
        cancel = self.task()
        marker = self.root / 'unexpected-child-output'
        timer = threading.Timer(.3, cancel.touch)
        timer.start()
        started = time.monotonic()
        try:
            with self.assertRaises(self.manager.PluginCancelled):
                self.manager.run_package_command([sys.executable, '-c',
                    'import time,pathlib;time.sleep(1.5);pathlib.Path(' + repr(str(marker)) + ').write_text("bad")'], str(self.root))
            self.assertLess(time.monotonic() - started, 1.4)
            time.sleep(1.5)
            self.assertFalse(marker.exists())
        finally:
            timer.join()


if __name__ == "__main__":
    unittest.main()
