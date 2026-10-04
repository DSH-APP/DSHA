#!/usr/bin/env python3
"""Real javac fixtures for the fallback runner, including helpers, quoted paths and failure cleanup."""
import contextlib
import importlib.util
import io
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('unit_runner', ROOT / 'tools/run-unit-tests.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class UnitRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='unit runner # 中文 ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.app = self.root / 'app'
        lock = self.root / 'tools/backup-dependencies.lock.json'
        lock.parent.mkdir()
        lock.write_text(json.dumps({'version': 1, 'dependencies': []}), encoding='utf-8')
        self.put('src/main/java/Production.java', 'public class Production {}')
        self.put('build/generated/uiLanguage/UiMessages.java', 'class UiMessages {}')
        self.put('src/test/java/Helper.java', 'public class Helper { public static int answer() { return 42; } }')
        self.put('src/test/java/DependentTest.java', 'public class DependentTest { public static int value = Helper.answer(); }')
        # An explicit launcher fixture, not evidence about JUnit's own implementation.
        self.put('src/test/java/org/junit/runner/JUnitCore.java',
                 'package org.junit.runner; public class JUnitCore { public static void main(String[] args) throws Exception {'
                 'if(args.length != 1 || !args[0].equals("DependentTest") || '
                 'Class.forName(args[0]).getField("value").getInt(null) != 42) throw new AssertionError(); '
                 'System.out.println("PASS compiled helper invoked from dependent test"); } }')

    def put(self, name, text):
        path = self.app / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')
        return path

    def test_every_helper_is_compiled_and_whitespace_paths_reach_java_intact(self):
        self.assertIsNotNone(shutil.which('javac'), 'JDK is a declared input')
        self.assertIsNotNone(shutil.which('java'), 'JDK is a declared input')
        with patch.object(runner, 'ROOT', self.root), patch.object(runner, 'APP', self.app), \
                patch.object(runner, 'r_classpath_entry', return_value=str(self.root)), \
                patch.dict(os.environ, {'DSHA_ONLY': ''}), contextlib.redirect_stdout(io.StringIO()) as captured:
            code = runner.run_compiled_tests(self.root / 'work', self.root, self.root, self.root, self.root)
        self.assertEqual(0, code, captured.getvalue())
        self.assertIn('PASS compiled helper invoked', captured.getvalue())
        self.assertTrue((self.root / 'work/test/Helper.class').is_file())
        self.assertTrue((self.root / 'work/test/org/junit/runner/JUnitCore.class').is_file())

    def test_real_compile_failure_cleans_main_temporary_directory(self):
        captured = []
        def compile_failure(work, *unused):
            captured.append(work)
            source = work / 'Broken.java'
            source.write_text('public class Broken { invalid syntax }')
            runner._javac([str(source)], work / 'classes', [])
        with patch.object(runner, 'ensure_utf8_locale'), patch.object(runner, 'gradle_user_home', return_value=self.root), \
                patch.object(runner, 'android_jar', return_value=self.root), patch.object(runner, 'find_jar', return_value=self.root), \
                patch.object(runner, 'run_compiled_tests', side_effect=compile_failure), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                runner.main()
        self.assertEqual(1, len(captured))
        self.assertFalse(captured[0].exists(), 'javac failure must not retain its temporary directory')

    def test_argfile_rejects_control_characters(self):
        for value in ('a\nb', 'a\rb', 'a\0b'):
            with self.assertRaisesRegex(ValueError, 'CONTROL_CHARACTER'):
                runner.write_argfile(self.root / 'args', [value])

    def test_locked_backup_dependency_uses_only_exact_version_and_checks_real_bytes(self):
        body = b'explicit dependency-selection fixture, not an executable JAR'
        row = {'group': 'org.yaml', 'name': 'snakeyaml', 'version': '2.4',
               'sha256': hashlib.sha256(body).hexdigest()}
        (self.root / 'tools/backup-dependencies.lock.json').write_text(
            json.dumps({'version': 1, 'dependencies': [row]}), encoding='utf-8')
        selected = self.root / 'caches/modules-2/files-2.1/org.yaml/snakeyaml/2.4/hash/snakeyaml-2.4.jar'
        selected.parent.mkdir(parents=True)
        selected.write_bytes(body)
        older = self.root / 'caches/modules-2/files-2.1/org.yaml/snakeyaml/2.2/hash/snakeyaml-2.2.jar'
        older.parent.mkdir(parents=True)
        older.write_bytes(b'older wrong version')
        with patch.object(runner, 'ROOT', self.root), patch.object(runner, 'APP', self.app):
            paths, coordinates = runner.locked_backup_classpath(self.root)
            self.assertEqual([str(selected)], paths)
            self.assertEqual({('org.yaml', 'snakeyaml')}, coordinates)
            selected.write_bytes(b'mutated current dependency')
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                runner.locked_backup_classpath(self.root)


if __name__ == '__main__':
    unittest.main(verbosity=2)
