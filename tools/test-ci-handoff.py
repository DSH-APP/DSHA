"""Isolated regressions for tools/ci-handoff.py and tools/verify-unit-results.py; no network."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import xml.etree.ElementTree as ET

TOOLS = Path(__file__).resolve().parent


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, TOOLS / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


handoff = load("ci_handoff", "ci-handoff.py")
results = load("unit_results", "verify-unit-results.py")


class Handoff(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        base = Path(self.temp.name)
        self.source, self.target, self.out = base / "source", base / "target", base / "out"
        self.source.mkdir()
        (self.source / "tools/recovery-runtime").mkdir(parents=True)
        (self.source / "tools/recovery-runtime/lock.json").write_text(
            json.dumps({"archives": [{"asset": "recovery-rootfs.bin"}]}))
        (self.source / "app").mkdir()
        (self.source / "README.md").write_text("source\n")
        self.names = handoff.names(self.source)
        for name in self.names:
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"original " + name.encode())
        git = ["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid"]
        subprocess.run(["git", "init", "-q", str(self.source)], check=True)
        subprocess.run(["git", "-C", str(self.source), "add", "-A"], check=True)
        subprocess.run([*git, "-C", str(self.source), "commit", "-qm", "base"], check=True)
        self.commit = handoff.head(self.source)
        shutil.copytree(self.source, self.target)
        for name in self.names:
            (self.source / name).write_bytes(b"restored " + name.encode())

    def test_round_trip_restores_exact_bytes(self):
        manifest = handoff.pack(self.source, self.out, self.commit)
        self.assertEqual([row["path"] for row in manifest["files"]], self.names)
        handoff.unpack(self.target, self.out, self.commit)
        for name in self.names:
            self.assertEqual((self.target / name).read_bytes(), b"restored " + name.encode())

    def test_pack_rejects_unexpected_source_change(self):
        (self.source / "README.md").write_text("edited\n")
        with self.assertRaisesRegex(ValueError, "HANDOFF_UNEXPECTED_CHANGE"):
            handoff.pack(self.source, self.out, self.commit)

    def test_commit_mismatch_and_tampering_never_write(self):
        handoff.pack(self.source, self.out, self.commit)
        with self.assertRaisesRegex(ValueError, "HANDOFF_COMMIT"):
            handoff.unpack(self.target, self.out, "0" * 40)
        manifest = json.loads((self.out / handoff.MANIFEST_NAME).read_text())
        manifest["files"][0]["sha256"] = "0" * 64
        (self.out / handoff.MANIFEST_NAME).write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "HANDOFF_FILE_SHA256"):
            handoff.unpack(self.target, self.out, self.commit)
        for name in self.names:
            self.assertEqual((self.target / name).read_bytes(), b"original " + name.encode())

    def test_extra_or_traversal_member_is_rejected(self):
        handoff.pack(self.source, self.out, self.commit)
        archive = self.out / handoff.TAR_NAME
        evil = Path(self.temp.name) / "evil"
        evil.write_bytes(b"x")
        with tarfile.open(archive, "a") as tar:
            tar.add(evil, arcname="../escape.txt")
        manifest = json.loads((self.out / handoff.MANIFEST_NAME).read_text())
        manifest["tarSha256"] = handoff.digest(archive)
        (self.out / handoff.MANIFEST_NAME).write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "HANDOFF_MEMBER"):
            handoff.unpack(self.target, self.out, self.commit)
        self.assertFalse((Path(self.temp.name) / "escape.txt").exists())

    def test_member_set_must_match_source_allow_list(self):
        handoff.pack(self.source, self.out, self.commit)
        manifest = json.loads((self.out / handoff.MANIFEST_NAME).read_text())
        manifest["files"].pop()
        (self.out / handoff.MANIFEST_NAME).write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "HANDOFF_MEMBER_SET"):
            handoff.unpack(self.target, self.out, self.commit)


class UnitResults(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.reports = Path(self.temp.name)
        self.passing = [(owner, name, "") for owner, name in results.REQUIRED_FIXTURES]

    def write(self, rows, **override):
        counts = dict(tests=len(rows), failures=sum(r[2] == "failure" for r in rows),
                      errors=sum(r[2] == "error" for r in rows),
                      skipped=sum(r[2] == "skipped" for r in rows))
        counts.update(override)
        suite = ET.Element("testsuite", **{k: str(v) for k, v in counts.items()})
        for owner, name, status in rows:
            case = ET.SubElement(suite, "testcase", classname=owner, name=name)
            if status:
                ET.SubElement(case, status)
        ET.ElementTree(suite).write(self.reports / "TEST-all.xml")

    def test_full_with_fixtures_passes_once(self):
        self.write(self.passing)
        result = results.verify(self.reports, "full", require_fixtures=True)
        self.assertEqual(result["flavors"]["Standard"]["requiredFixtureTestsPassed"], 7)

    def test_missing_skipped_or_duplicated_fixture_is_rejected(self):
        for target in results.REQUIRED_FIXTURES:
            for status in ("missing", "skipped", "duplicate"):
                with self.subTest(target=target, status=status):
                    rows = [r for r in self.passing if r[:2] != target]
                    if status == "skipped":
                        rows.append((*target, "skipped"))
                    if status == "duplicate":
                        rows += [(*target, ""), (*target, "")]
                    self.write(rows)
                    with self.assertRaises(ValueError):
                        results.verify(self.reports, "full", require_fixtures=True)

    def test_full_rejects_any_skip_failure_or_forged_counts(self):
        self.write(self.passing + [("x.Y", "skip", "skipped")])
        with self.assertRaisesRegex(ValueError, "UNEXPECTED_SKIP"):
            results.verify(self.reports, "full")
        self.write(self.passing + [("x.Y", "boom", "failure")])
        with self.assertRaisesRegex(ValueError, "FULL_UNIT_TESTS_FAILED"):
            results.verify(self.reports, "full")
        self.write(self.passing + [("x.Y", "hidden", "failure")], failures=0)
        with self.assertRaisesRegex(ValueError, "JUNIT_COUNTS"):
            results.verify(self.reports, "full")

    def test_source_only_reports_skips_and_optional_limit(self):
        rows = [("x.Y", f"s{n}", "skipped") for n in range(3)]
        self.write(rows)
        self.assertEqual(len(results.verify(self.reports, "source-only")["skipped"]), 3)
        with self.assertRaisesRegex(ValueError, "SOURCE_ONLY_SKIPS"):
            results.verify(self.reports, "source-only", max_skips=2)

    def test_missing_reports_fail(self):
        with self.assertRaisesRegex(ValueError, "JUNIT_MISSING"):
            results.verify(self.reports, "full")


if __name__ == "__main__":
    unittest.main()
