#!/usr/bin/env python3
"""独立目录中的往返与故障测试，Linux 上额外验证真实软链接和提交回滚。"""
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ENGINE = Path(sys.argv.pop(1)) if len(sys.argv) > 1 and sys.argv[1].endswith(".py") else Path(__file__).resolve().parents[1] / "app/src/main/assets/backup-engine.py"
spec = importlib.util.spec_from_file_location("backup_engine", ENGINE)
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)


def read_following_links(path):
    """读一个要走过多层相对软链的文件（见下面 test_nested_versions 的说明）。

    为什么要 realpath 绕一手：本项目的 proroot 容器里，**做过创建的那个进程**随后用
    stat/open 走自己刚建的嵌套相对软链，会间歇性拿到 ENOENT；同一路径交给新进程
    （shell 的 ls/test、或子进程 python）一律正常。realpath 用 readlink 在用户态手工
    展开，因此不受影响，而断言强度不变 —— 链真断了照样 FileNotFoundError。
    """
    return Path(os.path.realpath(path))


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.put(".dsh/sessions/one/session.jsonl", '{"message":"你好"}\n')
        self.put(".dsh/storages/workspace.json", '{"tables":{"workspaces":{}}}')
        self.put(".dsh/attachments/image.png", b"\x89PNG-test-attachment")
        self.put(".dsh/settings.yaml", "model: test\n")
        self.put(".dsh/profiles/web/package.json", '{"dependencies":{},"dsh":{"profile":{"bundles":[]}}}')
        self.put(".dsh/plugin-sources.json", '{"sample":"npm:sample@1.0.0"}')
        self.put(".dsh/plugin-safe-mode.json", '{"active":false}')
        self.put(".dsh/plugin-history/sample/state.json", '{"version":"0.9.0"}')
        self.archive = self.root / "backup.tar.gz"

    def tearDown(self):
        self.temp.cleanup()

    def put(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data if isinstance(data, bytes) else data.encode())
        return path

    def contents(self, path):
        return {p.relative_to(path).as_posix(): p.read_bytes() for p in path.rglob("*") if p.is_file()}

    def pack(self, entries):
        with tarfile.open(self.archive, "w:gz") as tar:
            for name, content in entries:
                member = tarfile.TarInfo(name)
                content = content.encode() if isinstance(content, str) else content
                member.size = len(content)
                tar.addfile(member, io.BytesIO(content))

    def test_all_scopes_roundtrip_and_isolation(self):
        for scope in engine.SCOPES:
            with self.subTest(scope=scope):
                before = self.contents(self.root / ".dsh")
                result = engine.make_backup(self.root, self.archive, scope, "1.2.0-test", 113)
                self.assertEqual(engine.digest(self.archive), result["sha256"])
                for name in before:
                    self.put(".dsh/" + name, b"changed")
                engine.restore_archive(self.root, self.archive, scope)
                after = self.contents(self.root / ".dsh")
                selected = engine.SCOPES[scope]
                for name, data in before.items():
                    self.assertEqual(data if selected is None or name.split('/')[0] in selected else b"changed", after[name])
                    self.put(".dsh/" + name, data)

    def test_inventory_rejects_modified_member(self):
        engine.make_backup(self.root, self.archive, "full")
        stage = self.root / "stage"
        engine.inspect_archive(self.archive, stage)
        (stage / ".dsh/settings.yaml").write_text("modified")
        with tarfile.open(self.archive, "w:gz") as tar:
            for p in stage.iterdir():
                tar.add(p, arcname=p.name)
        before = self.contents(self.root / ".dsh")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            engine.restore_archive(self.root, self.archive)
        self.assertEqual(before, self.contents(self.root / ".dsh"))

    def test_legacy_mobile_host_peer_does_not_block_upgrade_backup(self):
        base = '.dsh/profiles/web/node_modules/dsh-web-mobile/'
        self.put(base + 'package.json', json.dumps({'name': 'dsh-web-mobile', 'version': '2.3.0',
                 'peerDependencies': {'@deepseek-ai/dsh-client-runtime': '^0.1.2-rc.1'}}))
        self.put(base + 'lib/client.js', 'export default function() {}')
        self.put('.dsh/profiles/web/package.json', json.dumps({'dependencies': {'dsh-web-mobile': '2.3.0'}}))
        engine.make_backup(self.root, self.archive, 'full')
        stage = self.root / 'inspection'
        engine.inspect_archive(self.archive, stage)
        manifest = json.loads((stage / engine.MANIFEST).read_text())
        mobile = next(row for row in manifest['pluginDependencyGraph'].values() if row['name'] == 'dsh-web-mobile')
        self.assertEqual({}, mobile['links'])
        self.assertTrue(list(stage.rglob('lib/client.js')))

    def test_missing_dependencies_preserve_source_and_report_in_backup(self):
        base = '.dsh/profiles/web/node_modules/plugin/'
        self.put('.dsh/profiles/web/package.json', json.dumps({'dependencies': {'plugin': '1'}}))
        for requirements in ({'dependencies': {'missing-runtime': '1'}},
                             {'dependencies': {'@deepseek-ai/dsh-client-runtime': '1'},
                              'peerDependencies': {'@deepseek-ai/dsh-client-runtime': '1'}},
                             {'peerDependencies': {'missing-third-party': '1'}}):
            with self.subTest(requirements=requirements):
                self.put(base + 'package.json', json.dumps({'name': 'plugin', **requirements}))
                source = json.dumps({'name': 'plugin', **requirements})
                self.put(base + 'lib/index.js', 'export const original = true')
                result = engine.make_backup(self.root, self.archive, 'plugins')
                self.assertEqual(1, len(result['warnings']))
                with tempfile.TemporaryDirectory() as folder:
                    summary = engine.inspect_archive(self.archive, Path(folder), 'plugins')
                    self.assertEqual(result['warnings'], summary['warnings'])
                    graph = summary['manifest']['pluginDependencyGraph']
                    row = next(row for row in graph.values() if row['name'] == 'plugin')
                    self.assertEqual(1, len(row['missing']))
                    saved = next(Path(folder).glob('.dsha-plugin-src/.deps/*/package.json'))
                    self.assertEqual(json.loads(source), json.loads(saved.read_text()))
                    self.assertEqual('export const original = true', (saved.parent/'lib/index.js').read_text())

    def test_file_upload_missing_mammoth_preserves_conversations(self):
        self.put('.dsh/profiles/web/package.json', '{"dependencies":{"dsh-file-upload":"1"}}')
        self.put('.dsh/profiles/web/node_modules/dsh-file-upload/package.json',
                 '{"name":"dsh-file-upload","dependencies":{"mammoth":"1"}}')
        original = (self.root/'.dsh/sessions/one/session.jsonl').read_bytes()
        result = engine.make_backup(self.root, self.archive, 'full')
        self.assertIn('dsh-file-upload → mammoth', result['warnings'][0])
        self.assertEqual(original, (self.root/'.dsh/sessions/one/session.jsonl').read_bytes())

    def test_installed_host_peer_is_kept_in_backup(self):
        base = '.dsh/profiles/web/node_modules/'
        self.put(base + 'plugin/package.json', json.dumps({'name': 'plugin',
                 'peerDependencies': {'@deepseek-ai/dsh-custom-service': '1'}}))
        self.put(base + '@deepseek-ai/dsh-custom-service/package.json', '{"name":"@deepseek-ai/dsh-custom-service"}')
        self.put('.dsh/profiles/web/package.json', json.dumps({'dependencies': {'plugin': '1'}}))
        engine.make_backup(self.root, self.archive, 'plugins')
        stage = self.root / 'inspection'
        engine.inspect_archive(self.archive, stage)
        manifest = json.loads((stage / engine.MANIFEST).read_text())
        plugin = next(row for row in manifest['pluginDependencyGraph'].values() if row['name'] == 'plugin')
        self.assertIn('@deepseek-ai/dsh-custom-service', plugin['links'])

    @unittest.skipIf(os.name == 'nt', '真实共享运行时软链在 Linux 验证')
    def test_shared_runtime_pool_does_not_copy_unreferenced_framework_dependencies(self):
        base = '.dsh/profiles/web/node_modules/'
        self.put(base + 'plugin/package.json', json.dumps({'name': 'plugin', 'dependencies': {'needed': '1'}}))
        for name in ('needed', 'unrelated-host-runtime'):
            self.put('global/' + name + '/package.json', json.dumps({'name': name}))
        (self.root / base / 'plugin/node_modules').symlink_to(self.root / 'global', target_is_directory=True)
        self.put('.dsh/profiles/web/package.json', json.dumps({'dependencies': {'plugin': '1'}}))
        with patch.object(engine, 'GLOBAL_NM', (self.root / 'global',)):
            engine.make_backup(self.root, self.archive, 'plugins')
        stage = self.root / 'inspection'; engine.inspect_archive(self.archive, stage)
        manifest = json.loads((stage / engine.MANIFEST).read_text())
        self.assertEqual({'plugin', 'needed'}, {row['name'] for row in manifest['pluginDependencyGraph'].values()})

    def test_inspection_rejects_broken_dependency_graph_before_restore(self):
        engine.make_backup(self.root, self.archive, 'full')
        stage = self.root / 'invalid-graph'
        engine.inspect_archive(self.archive, stage)
        manifest_path = next(stage.rglob(engine.MANIFEST))
        manifest = json.loads(manifest_path.read_text())
        manifest['pluginDependencyGraph'] = {'a' * 20: {'name': 'example', 'links': {'dep': 'missing-node'}}}
        manifest_path.write_text(json.dumps(manifest))
        with tarfile.open(self.archive, 'w:gz') as archive:
            for path in stage.iterdir():
                archive.add(path, arcname=path.name)
        before = self.contents(self.root / '.dsh')
        with self.assertRaisesRegex(ValueError, '插件依赖引用无效'):
            engine.inspect_archive(self.archive, self.root / 'inspection')
        self.assertEqual(before, self.contents(self.root / '.dsh'))

    def test_truncated_archive_keeps_current_data(self):
        engine.make_backup(self.root, self.archive, "full")
        self.archive.write_bytes(self.archive.read_bytes()[:-8])
        before = self.contents(self.root / ".dsh")
        with self.assertRaises((EOFError, tarfile.TarError, OSError)):
            engine.restore_archive(self.root, self.archive)
        self.assertEqual(before, self.contents(self.root / ".dsh"))

    def test_scope_mismatch_unknown_and_extra_data(self):
        for manifest in ({"scope": "unknown"}, {"scope": "sessions"}, {"scope": "settings"}):
            self.pack([(engine.MANIFEST, json.dumps(manifest)), (".dsh/settings.yaml", "test")])
            with self.assertRaises(ValueError):
                engine.inspect_archive(self.archive, self.root / str(len(list(self.root.iterdir()))), "sessions")

    def test_path_and_duplicate_rejected_without_outside_write(self):
        for entries in (([("../escape", "bad")]), ([(".dsh/a", "a"), (".dsh/a", "b")])):
            self.pack(entries)
            with self.assertRaises(ValueError):
                engine.inspect_archive(self.archive, self.root / ("stage-" + str(len(list(self.root.iterdir())))))
        self.assertFalse((self.root.parent / "escape").exists())

    def test_insufficient_space_keeps_current(self):
        engine.make_backup(self.root, self.archive, "full")
        before = self.contents(self.root / ".dsh")
        with patch.object(engine.shutil, "disk_usage", return_value=shutil._ntuple_diskusage(10, 9, 1)):
            with self.assertRaisesRegex(ValueError, "空间不足"):
                engine.restore_archive(self.root, self.archive)
        self.assertEqual(before, self.contents(self.root / ".dsh"))

    def test_commit_failure_rolls_back_all_steps(self):
        old = self.put("target-a/value", "old-a")
        self.put("target-b/value", "old-b")
        self.put("new-a/value", "new-a")
        self.put("new-b/value", "new-b")
        with self.assertRaises(OSError):
            engine.commit(self.root, [(self.root / "new-a", old.parent), (self.root / "new-b", self.root / "target-b")], fail_after=1)
        self.assertEqual("old-a", old.read_text())
        self.assertEqual("old-b", (self.root / "target-b/value").read_text())
        self.assertFalse((self.root / ".dsha-restore-journal.json").exists())

    def test_recovery_after_interrupted_process(self):
        self.put("dst/value", "old")
        self.put("new/value", "new")
        engine.dump(self.root / ".dsha-restore-journal.json", {"complete": False, "steps": [{"dst": str(self.root / "dst"), "old": str(self.root / "old"), "new": str(self.root / "new"), "existed": True}]})
        os.replace(self.root / "dst", self.root / "old")
        os.replace(self.root / "new", self.root / "dst")
        engine.recover(self.root)
        self.assertEqual("old", (self.root / "dst/value").read_text())

    def test_rebuildable_cache_excluded(self):
        self.put(".dsh/session_projcache/large", "cache")
        engine.make_backup(self.root, self.archive, "full")
        with tarfile.open(self.archive) as tar:
            self.assertFalse(any("session_projcache" in n for n in tar.getnames()))

    def test_only_unchanged_bundled_adb_wheels_are_excluded(self):
        import hashlib
        from unittest.mock import patch
        self.put(".dsh/wheels/bundled.whl", "bundled")
        self.put(".dsh/wheels/modified.whl", "custom replacement")
        self.put(".dsh/wheels/custom.whl", "user supplied")
        self.put(".dsh/wheels/nested/bundled.whl", "nested user data")
        self.put(".dsh/adb-wheels.tar.gz", "archive")
        self.put(".dsh/adbkeys/adbkey", "keep pairing key")
        hashes = {n: hashlib.sha256(b"bundled").hexdigest() for n in ("bundled.whl", "modified.whl")}
        with patch.object(engine, "ADB_WHEEL_CACHE", hashes), patch.object(engine, "ADB_ARCHIVE_SHA256", hashlib.sha256(b"archive").hexdigest()):
            engine.make_backup(self.root, self.archive, "full")
        with tarfile.open(self.archive) as tar:
            names = tar.getnames()
            self.assertNotIn(".dsh/wheels/bundled.whl", names)
            self.assertNotIn(".dsh/adb-wheels.tar.gz", names)
            for kept in ("modified.whl", "custom.whl", "nested/bundled.whl"):
                self.assertIn(".dsh/wheels/" + kept, names)
            self.assertIn(".dsh/adbkeys/adbkey", names)

    def test_modified_adb_cache_archive_is_kept(self):
        self.put(".dsh/adb-wheels.tar.gz", "custom archive")
        engine.make_backup(self.root, self.archive, "full")
        with tarfile.open(self.archive) as tar:
            self.assertIn(".dsh/adb-wheels.tar.gz", tar.getnames())

    def test_excluded_cache_changed_during_snapshot_is_rejected(self):
        import hashlib
        for name in ("wheels/bundled.whl", "adb-wheels.tar.gz"):
            with self.subTest(name=name):
                source = self.put(".dsh/" + name, "bundled")
                expected = hashlib.sha256(b"bundled").hexdigest()
                def mutate(*args):
                    previous = source.stat()
                    source.write_bytes(b"changed")
                    os.utime(source, ns=(previous.st_atime_ns, previous.st_mtime_ns))
                    return []
                with patch.object(engine, "ADB_WHEEL_CACHE", {"bundled.whl": expected}), \
                        patch.object(engine, "ADB_ARCHIVE_SHA256", expected), \
                        patch.object(engine, "inline_plugins", side_effect=mutate):
                    with self.assertRaisesRegex(ValueError, "缓存内容变化"):
                        engine.make_backup(self.root, self.archive, "full")
                self.assertFalse(self.archive.exists())

    def test_new_top_level_user_data_during_snapshot_is_rejected(self):
        def mutate(*args):
            self.put(".dsh/new-user-data", "must not silently omit")
            return []
        with patch.object(engine, "inline_plugins", side_effect=mutate):
            with self.assertRaisesRegex(ValueError, "数据变化"):
                engine.make_backup(self.root, self.archive, "full")
        self.assertFalse(self.archive.exists())

    def test_bundled_adb_cache_fingerprints_match_assets(self):
        import hashlib
        asset = Path(__file__).resolve().parents[1] / "app/src/main/assets/adb-wheels.tar.gz"
        if not asset.is_file():
            self.skipTest("资产摘要在源码工作区验证")
        self.assertEqual(hashlib.sha256(asset.read_bytes()).hexdigest(), engine.ADB_ARCHIVE_SHA256)
        with tarfile.open(asset) as tar:
            expected = {Path(m.name).name: hashlib.sha256(tar.extractfile(m).read()).hexdigest()
                        for m in tar if m.isfile() and m.name.endswith(".whl")}
        self.assertEqual(expected, engine.ADB_WHEEL_CACHE)

    def test_deferred_commit_can_rollback_or_finalize(self):
        self.put("target/value", "old")
        self.put("new/value", "new")
        engine.commit(self.root, [(self.root / "new", self.root / "target")], defer=True)
        self.assertEqual("new", (self.root / "target/value").read_text())
        engine.recover(self.root)
        self.assertEqual("old", (self.root / "target/value").read_text())
        engine.commit(self.root, [(self.root / "new", self.root / "target")], defer=True)
        engine.finalize(self.root)
        engine.recover(self.root)
        self.assertEqual("new", (self.root / "target/value").read_text())

    @unittest.skipIf(os.name == "nt", "Linux 文件锁在真实容器验证")
    def test_busy_data_lock_and_unfinished_restore_block_backup(self):
        with engine.data_lock(self.root):
            with self.assertRaisesRegex(ValueError, "正在修改数据"):
                with engine.data_lock(self.root):
                    self.fail("同一数据集不应并发修改")
        engine.dump(self.root / ".dsha-restore-journal.json", {"complete": False, "steps": []})
        with self.assertRaisesRegex(ValueError, "恢复尚未完成"):
            engine.make_backup(self.root, self.archive, "full")
        engine.recover(self.root)

    def test_missing_workspace_directories_are_created(self):
        workspace = self.root / "project"
        nested = workspace / "sub"
        self.put(".dsh/storages/workspace.json", json.dumps({"tables": {"workspaces": {"1": {"path": str(workspace)}, "2": {"path": str(nested)}}}}))
        engine.make_backup(self.root, self.archive, "sessions")
        engine.restore_archive(self.root, self.archive, "sessions")
        self.assertTrue(nested.is_dir())

    @unittest.skipIf(os.name == "nt", "真实符号链接在 Linux 容器执行")
    def test_official_modules_and_disabled_markers_relinked(self):
        self.put("global/@deepseek-ai/example/package.json", '{"name":"@deepseek-ai/example"}')
        self.put(".dsh/profiles/web/package.json", json.dumps({"dependencies": {"@deepseek-ai/example": "1.0.0"}, "dsh": {"profile": {"bundles": ["@deepseek-ai/example"]}}}))
        self.put(".dsh/profiles/web/node_modules/@deepseek-ai/example.disabled", "disabled")
        engine.make_backup(self.root, self.archive, "plugins")
        with patch.object(engine, "GLOBAL_NM", (self.root / "global",)):
            engine.restore_archive(self.root, self.archive, "plugins")
        self.assertTrue((self.root / ".dsh/profiles/web/node_modules/@deepseek-ai/example/package.json").is_file())
        self.assertEqual("disabled", (self.root / ".dsh/profiles/web/node_modules/@deepseek-ai/example.disabled").read_text())

    @unittest.skipIf(os.name == "nt", "真实符号链接在 Linux 容器执行")
    def test_public_symlinks_cross_device_and_existing_bindings(self):
        public = self.root / "public"
        public.mkdir()
        for name in engine.HOT:
            os.rename(self.root / ".dsh" / name, public / name)
            (self.root / ".dsh" / name).symlink_to(public / name)
        before = self.contents(public)
        engine.make_backup(self.root, self.archive, "sessions")
        self.put("public/sessions/one/session.jsonl", "changed")
        self.put("public/attachments/image.png", "changed")
        engine.restore_archive(self.root, self.archive, "sessions")
        restored = {name: data for name, data in self.contents(public).items()
                    if ".pre-restore-" not in name}
        self.assertEqual(before, restored)
        self.assertTrue(list(public.glob("sessions.pre-restore-*")))
        self.assertTrue((self.root / ".dsh/sessions").is_symlink())
        other = self.root / "other-device"
        other.mkdir()
        engine.restore_archive(other, self.archive, "sessions")
        self.assertEqual(before["sessions/one/session.jsonl"], (other / ".dsh/sessions/one/session.jsonl").read_bytes())
        self.assertFalse((other / ".dsh/sessions").is_symlink())

    @unittest.skipIf(os.name == "nt", "真实符号链接在 Linux 容器执行")
    def test_local_plugin_code_and_runtime_dependency_roundtrip(self):
        self.put("plugin/lib/client.js", "plugin-content")
        self.put("plugin/package.json", '{"name":"test-plugin","version":"1.0.0"}')
        self.put("plugin/node_modules/runtime/index.js", "dependency")
        self.put(".dsh/profiles/web/package.json", json.dumps({"dependencies": {"test-plugin": "link:" + str(self.root / "plugin")}}))
        engine.make_backup(self.root, self.archive, "plugins")
        shutil.rmtree(self.root / "plugin")
        engine.restore_archive(self.root, self.archive, "plugins")
        pkg = json.loads((self.root / ".dsh/profiles/web/package.json").read_text())
        installed = Path(pkg["dependencies"]["test-plugin"][5:])
        self.assertEqual("plugin-content", (installed / "lib/client.js").read_text())
        self.assertEqual("dependency", (installed / "node_modules/runtime/index.js").read_text())
        self.assertEqual(installed, (self.root / ".dsh/profiles/web/node_modules/test-plugin").resolve())

    def test_legacy_snapshot_and_missing_snapshot(self):
        self.pack([(".dsha-pub/sessions/a/session.jsonl", "legacy")])
        with tarfile.open(self.archive, "r:gz") as old:
            content = [(m.name, old.extractfile(m).read()) for m in old if m.isfile()]
        with tarfile.open(self.archive, "w:gz") as tar:
            for name, data in content:
                member = tarfile.TarInfo(name)
                member.size = len(data)
                tar.addfile(member, io.BytesIO(data))
            directory = tarfile.TarInfo(".dsh")
            directory.type = tarfile.DIRTYPE
            tar.addfile(directory)
            link = tarfile.TarInfo(".dsh/sessions")
            link.type = tarfile.SYMTYPE
            link.linkname = "/old-device/sessions"
            tar.addfile(link)
        engine.restore_archive(self.root, self.archive, "sessions")
        self.assertEqual("legacy", (self.root / ".dsh/sessions/a/session.jsonl").read_text())
        with tarfile.open(self.archive, "w:gz") as tar:
            tar.addfile(directory)
            tar.addfile(link)
        with self.assertRaisesRegex(ValueError, "缺少实际数据"):
            engine.restore_archive(self.root, self.archive, "sessions")

    @unittest.skipIf(os.name == 'nt', '依赖链接与运行身份由 Linux 验证')
    def test_hoisted_circular_dependencies_keep_one_module_identity(self):
        import subprocess
        base = '.dsh/profiles/web/node_modules/'
        self.put(base + 'plugin-a/package.json', json.dumps({'name': 'plugin-a', 'dependencies': {'plugin-b': '1.0.0'}}))
        self.put(base + 'plugin-a/index.js', "exports.name='A';const b=require('plugin-b');exports.same=()=>b.getA()===module.exports;")
        self.put(base + 'plugin-b/package.json', json.dumps({'name': 'plugin-b', 'dependencies': {'plugin-a': '1.0.0'}}))
        self.put(base + 'plugin-b/index.js', "exports.getA=()=>require('plugin-a');")
        self.put('.dsh/profiles/web/package.json', json.dumps({'dependencies': {'plugin-a': '1.0.0'}}))
        engine.make_backup(self.root, self.archive, 'plugins')
        shutil.rmtree(self.root / base)
        engine.restore_archive(self.root, self.archive, 'plugins')
        installed = self.root / base / 'plugin-a'
        self.assertEqual(installed.resolve(), (installed / 'node_modules/plugin-b/node_modules/plugin-a').resolve())
        node = os.environ.get('DSHA_TEST_NODE') or shutil.which('node')
        self.assertIsNotNone(node, '需要 Node 验证恢复后的真实模块身份')
        result = subprocess.run([node, '-e', "if(!require(process.argv[1]).same())process.exit(2)", str(installed)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    @unittest.skipIf(os.name == 'nt', '真实依赖链接由 Linux 验证')
    def test_nested_versions_do_not_get_hoisted_into_wrong_dependency(self):
        base = '.dsh/profiles/web/node_modules/'
        for name, version, deps in [('plugin-a', '1', {'dep': '2', 'plugin-b': '1'}),
                                     ('plugin-b', '1', {'dep': '1'}), ('dep', '2', {}),
                                     ('plugin-b/node_modules/dep', '1', {})]:
            package_name = name.rsplit('/', 1)[-1]
            self.put(base + name + '/package.json', json.dumps({'name': package_name, 'version': version, 'dependencies': deps}))
        self.put('.dsh/profiles/web/package.json', json.dumps({'dependencies': {'plugin-a': '1'}}))
        engine.make_backup(self.root, self.archive, 'plugins')
        shutil.rmtree(self.root / base)
        engine.restore_archive(self.root, self.archive, 'plugins')
        a = self.root / base / 'plugin-a'
        # 这两条路径要连穿两层相对软链（profile 里的 plugin-a → 依赖池的 key →
        # key 内的 node_modules/<name>）。产物本身是对的 —— 实测 Node 从这棵树上
        # require 出来就是 dep=2、plugin-b 里的 dep=1，正是本测试要守的不变式；
        # 只是 proroot 下同进程再走一遍会间歇性 ENOENT，所以经 realpath 展开。
        self.assertEqual('2', json.loads(
            read_following_links(a / 'node_modules/dep/package.json').read_text())['version'])
        self.assertEqual('1', json.loads(
            read_following_links(
                a / 'node_modules/plugin-b/node_modules/dep/package.json').read_text())['version'])

    @unittest.skipIf(os.name == 'nt', '真实依赖链接由 Linux 验证')
    def test_unregistered_plugin_and_unpublished_source_history_survive(self):
        self.put('.dsh/node_modules/web-downloaded/package.json', json.dumps({'name': 'web-downloaded', 'dsh': {'bundle': {'patch': 'cordis.patch.yml'}}}))
        self.put('.dsh/node_modules/web-downloaded/cordis.patch.yml', '[]')
        self.put('.dsh/plugin-src/prototype/script.py', 'unpublished code')
        self.put('.dsh/plugin-src/prototype/.git/private-commit', 'unpublished history')
        engine.make_backup(self.root, self.archive, 'plugins')
        shutil.rmtree(self.root / '.dsh/node_modules')
        shutil.rmtree(self.root / '.dsh/plugin-src')
        engine.restore_archive(self.root, self.archive, 'plugins')
        self.assertTrue((self.root / '.dsh/plugin-src/web-downloaded/package.json').is_file())
        self.assertEqual('unpublished history', (self.root / '.dsh/plugin-src/prototype/.git/private-commit').read_text())
        profile = json.loads((self.root / '.dsh/profiles/web/package.json').read_text())
        self.assertNotIn('web-downloaded', profile['dsh']['profile']['bundles'])

    @unittest.skipIf(os.name == 'nt', '真实恢复链接在 Linux 验证')
    def test_repeated_rebuild_keeps_conflicting_source_once_without_losing_revisions(self):
        managed = self.root / 'managed/plugin'
        self.put('managed/plugin/package.json', '{"name":"plugin","version":"1"}')
        self.put('managed/plugin/index.js', 'managed-v1')
        self.put('.dsh/plugin-src/plugin/draft.txt', 'unpublished-original')
        self.put('.dsh/plugin-src/plugin/.git/commit', 'unpublished-history')
        counts = []
        for attempt in range(3):
            self.put('.dsh/profiles/web/package.json', json.dumps({'dependencies': {'plugin': 'link:' + str(managed)}}))
            active = self.root / '.dsh/profiles/web/node_modules/plugin'
            engine.remove(active); active.parent.mkdir(parents=True, exist_ok=True); active.symlink_to(managed, target_is_directory=True)
            engine.make_backup(self.root, self.archive, 'plugins')
            engine.restore_archive(self.root, self.archive, 'plugins')
            preserved = self.root / '.dsh/plugin-src/.preserved'
            self.assertEqual(['unpublished-original'], [p.read_text() for p in preserved.rglob('draft.txt')])
            self.assertEqual(['unpublished-history'], [p.read_text() for p in preserved.rglob('commit')])
            self.assertEqual('managed-v1', (active / 'index.js').read_text())
            counts.append(sum(p.is_file() for p in preserved.rglob('*')))
        self.assertEqual(counts[1], counts[2], '重复恢复不能不断累积相同的源码副本')

    @unittest.skipIf(os.name == 'nt', '真实依赖链接由 Linux 验证')
    def test_v3_plugin_archive_remains_readable(self):
        stage = self.root / 'v3-stage'
        slot = 'a' * 20
        paths = {'.dsh/profiles/web/package.json': json.dumps({'dependencies': {'old-plugin': 'link:/old/plugin'}}),
                 '.dsha-plugin-src/' + slot + '/package.json': '{"name":"old-plugin"}',
                 '.dsha-plugin-src/' + slot + '/index.js': 'old-plugin-content'}
        for name, content in paths.items():
            target = stage / name; target.parent.mkdir(parents=True, exist_ok=True); target.write_text(content)
        engine.dump(stage / engine.MANIFEST, {'formatVersion': 3, 'scope': 'plugins',
                    'plugins': [{'profile': 'web', 'name': 'old-plugin', 'slot': slot}], 'inventory': engine.inventory(stage)})
        with tarfile.open(self.archive, 'w:gz') as archive:
            for path in stage.iterdir(): archive.add(path, arcname=path.name)
        engine.restore_archive(self.root, self.archive, 'plugins')
        self.assertEqual('old-plugin-content', (self.root / '.dsh/profiles/web/node_modules/old-plugin/index.js').read_text())


@unittest.skipIf(os.name == 'nt', '依赖真实软链接，由 Linux 验证')
class ExtractionSymlinkSafetyTest(unittest.TestCase):
    """归档成员写入不得穿过已存在的软链。

    归档里一条文件条目表达的意思就是「这里应该是一个普通文件」。``open("xb")`` 是
    ``O_EXCL``，但对一条**已存在的悬空软链**它会穿过链接去创建目标 —— 内容落到链接
    指向的地方，而不是路径本身。

    这一版 inspect_archive 的所有调用方都传全新临时目录，本不会碰到；但阶段目录是
    调用方给的，这条不变式不该靠调用方守规矩来成立。摘掉链接本身是 1.1.x 支线
    修过的同一个形状（writeFile 直接 new FileOutputStream 会把归档内容写到
    sessions/storages 那些指向公开数据目录的软链后面）。
    """

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.stage = self.root / "stage"
        self.stage.mkdir()
        (self.stage / ".dsh").mkdir()
        self.archive = self.root / "backup.tar.gz"

    def tearDown(self):
        self.temp.cleanup()

    def make_archive(self, members):
        with tarfile.open(self.archive, "w:gz") as tar:
            for name, data in members:
                info = tarfile.TarInfo(name)
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))

    def test_file_member_wins_over_preexisting_dangling_symlink(self):
        outside = self.root / "outside"
        outside.mkdir()
        victim = outside / "victim.txt"
        target = self.stage / ".dsh" / "settings.yaml"
        # 阶段目录里预先放一条指向 stage 之外的悬空软链。
        target.symlink_to(victim)
        self.make_archive([(".dsh/settings.yaml", b"real backup payload\n")])

        engine.inspect_archive(self.archive, self.stage, "full")

        self.assertFalse(target.is_symlink(), "写入前应先摘掉链接本身")
        self.assertEqual(target.read_bytes(), b"real backup payload\n",
                         "归档内容必须落在路径本身")
        self.assertFalse(victim.exists(), "内容不得穿过软链写到 stage 之外")
        self.assertEqual(sorted(p.name for p in outside.iterdir()), [])

    def test_file_member_wins_over_symlink_to_existing_file(self):
        outside = self.root / "outside"
        outside.mkdir()
        victim = outside / "victim.txt"
        victim.write_bytes(b"ORIGINAL-DO-NOT-TOUCH")
        target = self.stage / ".dsh" / "settings.yaml"
        target.symlink_to(victim)
        self.make_archive([(".dsh/settings.yaml", b"real backup payload\n")])

        engine.inspect_archive(self.archive, self.stage, "full")

        self.assertEqual(target.read_bytes(), b"real backup payload\n")
        self.assertEqual(victim.read_bytes(), b"ORIGINAL-DO-NOT-TOUCH",
                         "链接指向的既有文件不能被覆盖")

    def test_normal_stage_is_untouched(self):
        # 回归护栏：没有软链时行为不变，仍是原子的独占创建。
        self.make_archive([(".dsh/settings.yaml", b"payload")])
        engine.inspect_archive(self.archive, self.stage, "full")
        self.assertEqual((self.stage / ".dsh" / "settings.yaml").read_bytes(), b"payload")

    def test_duplicate_member_is_still_rejected(self):
        # 换一个空阶段目录：独占创建本就会因已存在而失败，重复成员要在更早处拒绝。
        fresh = self.root / "stage-2"
        (fresh / ".dsh").mkdir(parents=True)
        self.make_archive([(".dsh/settings.yaml", b"a"), (".dsh/settings.yaml", b"b")])
        with self.assertRaisesRegex(ValueError, "重复路径"):
            engine.inspect_archive(self.archive, fresh, "full")


if __name__ == "__main__":
    unittest.main(verbosity=2)
