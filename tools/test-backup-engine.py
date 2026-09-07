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


if __name__ == "__main__":
    unittest.main(verbosity=2)
