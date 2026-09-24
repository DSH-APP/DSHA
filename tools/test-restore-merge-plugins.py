#!/usr/bin/env python3
"""restore-merge.py 的插件冲突与旧 Agent preset 迁移候选测试。

测试只使用临时目录，不运行 dsh，不执行插件代码，也不改变正式 rootfs。
"""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app" / "src" / "main" / "assets" / "restore-merge.py"
SPEC = importlib.util.spec_from_file_location("dsha_restore_merge_plugins", SOURCE)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class RestoreMergePluginTests(unittest.TestCase):
    def setUp(self):
        MODULE.report.clear()
        MODULE.partial = False
        MODULE.retain_stage = False

    @staticmethod
    def put(root: Path, relative: str, content: str):
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    @staticmethod
    def records(root: Path, kind: str):
        directory = root / MODULE.PLUGIN_MIGRATION_DIRNAME
        return [json.loads(path.read_text(encoding="utf-8"))
                for path in directory.glob(kind + "-*.json")]

    def test_same_name_source_keeps_current_and_quarantines_incoming(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            stage = root / "stage"
            self.put(root, "root/plugin-src/demo/package.json", '{"name":"demo","version":"current"}')
            self.put(root, "root/plugin-src/demo/index.js", "current bytes")
            self.put(stage, ".dsha-plugin-src/demo/package.json", '{"name":"demo","version":"incoming"}')
            self.put(stage, ".dsha-plugin-src/demo/index.js", "incoming bytes")
            landed = MODULE.restore_inlined_plugins(str(stage), str(root / "root"))
            current = root / "root/plugin-src/demo"
            self.assertEqual({}, landed)
            self.assertEqual("current bytes", (current / "index.js").read_text(encoding="utf-8"))
            candidates = list((root / "root" / MODULE.PLUGIN_MIGRATION_DIRNAME / "conflicts").rglob("index.js"))
            self.assertEqual(1, len(candidates))
            self.assertEqual("incoming bytes", candidates[0].read_text(encoding="utf-8"))
            self.assertTrue(self.records(root / "root", "plugin-conflicts"))
            self.assertTrue(MODULE.partial)

    def test_copy_failure_does_not_remove_current_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            stage = root / "stage"
            self.put(root, "root/plugin-src/demo/index.js", "current bytes")
            self.put(stage, ".dsha-plugin-src/demo/index.js", "incoming bytes")
            original = MODULE.shutil.copytree
            try:
                def fail_copy(*args, **kwargs):
                    raise OSError("injected copy failure")
                MODULE.shutil.copytree = fail_copy
                MODULE.restore_inlined_plugins(str(stage), str(root / "root"))
            finally:
                MODULE.shutil.copytree = original
            self.assertEqual("current bytes", (root / "root/plugin-src/demo/index.js").read_text(encoding="utf-8"))
            self.assertTrue(MODULE.partial)

    def test_missing_bundle_and_local_dependency_stay_in_profile(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "root"
            package = root / ".dsh/profiles/web/package.json"
            self.put(root, ".dsh/profiles/web/package.json", json.dumps({
                "dependencies": {"legacy-plugin": "link:/root/plugin-src/legacy-plugin"},
                "dsh": {"profile": {"bundles": ["legacy-plugin"]}},
            }))
            before = package.read_bytes()
            MODULE.fix_profiles(str(root), {})
            self.assertEqual(before, package.read_bytes())
            rows = self.records(root, "plugin-pending")
            self.assertEqual(1, len(rows))
            item = rows[0]["payload"]["items"]
            self.assertEqual("legacy-plugin", item[0]["dependency"])
            self.assertEqual("BUNDLE_SOURCE_MISSING", item[1]["reason"])

    def test_legacy_agent_preset_is_copied_as_unactivated_candidate(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "root"
            self.put(root, ".dsh/.agent-presets/crew/preset.yml", "name: crew\n")
            self.put(root, ".dsh/.agent-presets/crew/agent.cordis.yml", "- id: old\n")
            candidates = MODULE.collect_legacy_agent_presets(str(root / ".dsh"), str(root))
            self.assertEqual(1, len(candidates))
            self.assertEqual("- id: old\n", (Path(candidates[0]) / "agent.cordis.yml").read_text(encoding="utf-8"))
            bundle = Path(candidates[0]) / "bundle"
            self.assertEqual("dsha-legacy-preset-crew", json.loads((bundle / "package.json").read_text(encoding="utf-8"))["name"])
            self.assertIn("@deepseek-ai/dsh-agent-preset", (bundle / "cordis.patch.yml").read_text(encoding="utf-8"))
            self.assertTrue((root / ".dsh/.agent-presets/crew/agent.cordis.yml").is_file())
            rows = self.records(root, "legacy-agent-presets")
            self.assertEqual(1, len(rows))
            self.assertFalse(rows[0]["payload"]["activated"])
            self.assertTrue(rows[0]["payload"]["presets"][0]["requiresReview"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
