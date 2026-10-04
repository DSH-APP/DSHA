#!/usr/bin/env python3
"""Bounded formatter inventory and package batching regressions."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("format_java_tree", ROOT / "tools/format-java-tree.py")
tree = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tree)


class FormatTreeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="dsha-java-tree-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for source_set in tree.SETS:
            (self.root / "app/src" / source_set / tree.SOURCE).mkdir(parents=True)
        helper = self.root / tree.TOOL_SOURCE
        helper.parent.mkdir(parents=True)
        helper.write_text("class JavaTokenFingerprint {}\n", encoding="utf-8")

    def source(self, source_set, package, name):
        base = self.root / "app/src" / source_set / tree.SOURCE
        path = base / package / name if package else base / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("class Example {}\n", encoding="utf-8")
        return path.relative_to(self.root).as_posix()

    def test_inventory_has_exact_six_owned_sets_and_protected_exclusion(self):
        protected = self.source("main", "", "ShellService.java")
        generated = self.source("main", "util", "CredentialPathRules.java")
        main = self.source("main", "util", "Query.java")
        low = self.source("low", "ui", "Gecko.java")
        self.source("main", "generated", "DoNotFormat.java")
        self.source("main", "vendor", "Foreign.java")
        (self.root / "app/src/main/java/org/upstream").mkdir(parents=True)
        (self.root / "app/src/main/java/org/upstream/Foreign.java").write_text("class Foreign {}\n")
        self.assertEqual(sorted((main, low, tree.TOOL_SOURCE)),
                         tree.inventory(self.root, protected=(protected,), generated=(generated,)))

    def test_declared_machine_generated_sources_have_real_owners(self):
        lock = json.loads(tree.LOCK.read_text(encoding="utf-8"))
        self.assertEqual(set(tree.GENERATED_OWNERS), set(lock["generatedFiles"]))
        for name, generator in tree.GENERATED_OWNERS.items():
            self.assertTrue((ROOT / name).is_file(), name)
            self.assertTrue((ROOT / generator).is_file(), generator)
            self.assertNotIn(name, tree.inventory(ROOT, protected=lock["protectedFiles"],
                                                   generated=lock["generatedFiles"]))

    def test_batches_group_by_source_set_and_package_without_crossing_limit(self):
        names = [f"app/src/main/java/com/deepseekharness/app/util/Class{n}.java"
                 for n in range(300)]
        names += ["app/src/test/java/com/deepseekharness/app/util/QueryTest.java",
                  "app/src/main/java/com/deepseekharness/app/HttpShellService.java"]
        batches = tree.package_batches(names)
        self.assertEqual([1, 256, 44, 1], [len(files) for _, files in batches])
        self.assertEqual([("main", "(root)"), ("main", "util"),
                          ("main", "util"), ("test", "util")],
                         [key for key, _ in batches])
        self.assertEqual(set(names), {name for _, files in batches for name in files})

    def test_manifest_rejects_duplicates_and_non_string_entries(self):
        manifest = self.root / "manifest.json"
        for data in ({"schema": 1, "files": ["x.java", "x.java"]},
                     {"schema": 1, "files": [42]}, {"schema": 2, "files": []}):
            manifest.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(tree.TreeFormatError):
                tree.declared_files(manifest)


if __name__ == "__main__":
    unittest.main(verbosity=2)
