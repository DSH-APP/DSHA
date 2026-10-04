"""Actual locked formatter/JDK lexer and isolated source/ownership regressions."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("dsha_format_java", ROOT / "tools/format-java.py")
formatter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(formatter)
LOCK = json.loads(formatter.LOCK_PATH.read_text(encoding="utf-8"))


class JavaFormatTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if sys.flags.optimize:
            raise RuntimeError("JAVA_FORMAT_TEST_OPTIMIZATION_FORBIDDEN")
        cls.java, cls.javac, cls.version = formatter.java_command(None, LOCK)
        cls.jar = formatter.artifact(LOCK, offline=False)
        cls.token_command = formatter.token_helper(cls.java, cls.javac)
        fixtures = formatter.CACHE / "fixtures"
        fixtures.mkdir(parents=True, exist_ok=True)
        cls.directory = tempfile.TemporaryDirectory(prefix="formatter-", dir=fixtures)
        cls.fixture = Path(cls.directory.name)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def source(self, name, data):
        path = self.fixture / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def cli(self, mode, files, *extra):
        return subprocess.run(
            [sys.executable, str(ROOT / "tools/format-java.py"), mode, "--files",
             *map(str, files), "--java", str(self.java), "--offline", *extra],
            cwd=ROOT, capture_output=True, timeout=90
        )

    def run_java(self, source, classes):
        classes.mkdir(parents=True, exist_ok=True)
        compiled = subprocess.run(
            [str(self.javac), "-encoding", "UTF-8", "-d", str(classes), str(source)],
            capture_output=True, timeout=60
        )
        self.assertEqual(compiled.returncode, 0, compiled.stderr.decode())
        return subprocess.check_output([str(self.java), "-Dfile.encoding=UTF-8", "-cp", str(classes), source.stem], timeout=30)

    def test_check_write_check_and_actual_compiled_behavior(self):
        before = ('public final class Calculation{public static void main(String[]a){'
                  'int sum=0;for(int n=0;n<7;n++){sum+=n*n;}'
                  'String value="\\u4e2d文";System.out.print(value+":"+sum);}}\n').encode("utf-8")
        path = self.source("Calculation.java", before)
        output_before = self.run_java(path, self.fixture / "before-classes")
        checked = self.cli("--check", [path])
        self.assertEqual(checked.returncode, 1, checked.stderr.decode())
        self.assertEqual(path.read_bytes(), before)
        report = self.fixture / "format-report.json"
        written = self.cli("--write", [path], "--report", str(report))
        self.assertEqual(written.returncode, 0, written.stderr.decode())
        self.assertNotEqual(path.read_bytes(), before)
        evidence = json.loads(report.read_text(encoding="utf-8"))["files"][0]
        self.assertTrue(evidence["diff"])
        self.assertNotEqual(evidence["beforeSha256"], evidence["formattedSha256"])
        self.assertEqual(len(evidence["javaTokenSha256"]), 64)
        checked = self.cli("--check", [path])
        self.assertEqual(checked.returncode, 0, checked.stderr.decode())
        self.assertEqual(output_before, self.run_java(path, self.fixture / "after-classes"))
        self.assertEqual(output_before.decode("utf-8"), "中文:91")

    def test_invalid_second_file_does_not_partially_write_first(self):
        before = b"final class Good{int value=1;}\n"
        good = self.source("Good.java", before)
        invalid = self.source("Invalid.java", b"final class Invalid { int x = ; }\n")
        result = self.cli("--write", [good, invalid])
        self.assertEqual(result.returncode, 2)
        self.assertIn(b"FORMAT_PARSE_FAILED", result.stderr)
        self.assertEqual(good.read_bytes(), before)

    def test_lexer_detects_operator_literal_identifier_and_unicode_changes(self):
        original = b'final class Guard{int named=1+2;String text="\\u0061";}\n'
        whitespace = b'final class Guard { /* allowed comment */ int named = 1 + 2; String text = "\\u0061"; }\n'
        self.assertEqual(len(formatter.verify_tokens(self.token_command, original, whitespace)), 64)
        for changed in [original.replace(b"1+2", b"1-2"), original.replace(b"named", b"other"),
                        original.replace(b"\\u0061", b"a")]:
            with self.subTest(changed=changed), self.assertRaises(formatter.FormatError):
                formatter.verify_tokens(self.token_command, original, changed)

    def test_history_upstream_and_traversal_are_refused_without_mutation(self):
        for name in ["history/Original.java", "upstream/Original.java", "generated/Original.java"]:
            path = self.source(name, b"final class Original{int x=1;}\n")
            before = path.read_bytes()
            result = self.cli("--write", [path])
            self.assertEqual(result.returncode, 2, name)
            self.assertIn(b"FORMAT_UNOWNED_OR_PROTECTED", result.stderr)
            self.assertEqual(path.read_bytes(), before)
        with self.assertRaises(formatter.FormatError):
            formatter.checked_path(self.fixture / ".." / "Anything.java")
        with self.assertRaises(formatter.FormatError):
            formatter.checked_path(ROOT / LOCK["protectedFiles"][0], protected=LOCK["protectedFiles"])
        for name in LOCK["generatedFiles"]:
            with self.assertRaises(formatter.FormatError):
                formatter.select_files([name], LOCK)

    def test_symlink_or_junction_refused_even_for_an_in_project_target(self):
        target = self.source("Target.java", b"class Target {}\n")
        link = self.fixture / "Link.java"
        try:
            link.symlink_to(target)
        except OSError as error:
            if os.name != "nt":
                self.skipTest("Host denies creating symbolic links: " + str(error))
            junction = self.fixture / "Junction"
            environment = dict(os.environ)
            environment["DSHA_FORMAT_JUNCTION_PATH"] = str(junction)
            environment["DSHA_FORMAT_JUNCTION_TARGET"] = str(target.parent)
            result = subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command",
                 "$ErrorActionPreference='Stop'; New-Item -ItemType Junction -Path $env:DSHA_FORMAT_JUNCTION_PATH -Target $env:DSHA_FORMAT_JUNCTION_TARGET | Out-Null"],
                env=environment, capture_output=True, timeout=30
            )
            self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
            try:
                self.assertEqual(junction.resolve(), self.fixture.resolve())
                with self.assertRaises(formatter.FormatError):
                    formatter.checked_path(junction / target.name)
            finally:
                # Remove the checked owned junction itself, never recursively follow its target.
                self.assertEqual(junction.parent.resolve(), self.fixture.resolve())
                junction.rmdir()
            return
        with self.assertRaises(formatter.FormatError):
            formatter.checked_path(link)

    def test_cached_jar_corruption_and_offline_miss_fail_closed(self):
        original_cache = formatter.CACHE
        try:
            formatter.CACHE = self.fixture / "private-artifact-cache"
            formatter.CACHE.mkdir()
            with self.assertRaises(formatter.FormatError):
                formatter.artifact(LOCK, offline=True)
            (formatter.CACHE / LOCK["artifact"]).write_bytes(b"corrupt formatter")
            with self.assertRaises(formatter.FormatError):
                formatter.artifact(LOCK, offline=True)
        finally:
            formatter.CACHE = original_cache

    def test_changed_selector_uses_actual_git_staged_unstaged_and_untracked_files(self):
        repository = self.fixture / "git-selection"
        repository.mkdir()
        subprocess.run(["git", "init", "--quiet", "--template="], cwd=repository, check=True)
        subprocess.run(["git", "config", "core.autocrlf", "false"], cwd=repository, check=True)
        for name in ["Changed.java", "Staged.java", "Deleted.java"]:
            (repository / name).write_bytes(b"class Example {}\n")
        subprocess.run(["git", "add", "."], cwd=repository, check=True)
        subprocess.run(["git", "-c", "user.name=Formatter fixture", "-c", "user.email=fixture@example.invalid",
                        "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "isolated formatter fixture"],
                       cwd=repository, check=True)
        (repository / "Changed.java").write_bytes(b"class Example { int changed; }\n")
        (repository / "Staged.java").write_bytes(b"class Example { int staged; }\n")
        subprocess.run(["git", "add", "Staged.java"], cwd=repository, check=True)
        (repository / "Deleted.java").unlink()
        (repository / "New.java").write_bytes(b"class New {}\n")
        (repository / "NotJava.txt").write_bytes(b"not selected")
        self.assertEqual(formatter.changed_files(repository), ["Changed.java", "New.java", "Staged.java"])

    def test_no_implicit_whole_tree_selection_and_duplicate_refusal(self):
        result = subprocess.run([sys.executable, str(ROOT / "tools/format-java.py"), "--write"],
                                capture_output=True, cwd=ROOT, timeout=30)
        self.assertEqual(result.returncode, 2)
        source = self.source("Duplicate.java", b"class Duplicate {}\n")
        with self.assertRaises(formatter.FormatError):
            formatter.select_files([str(source), str(source)], LOCK)


if __name__ == "__main__":
    unittest.main(verbosity=2)
