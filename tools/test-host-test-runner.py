#!/usr/bin/env python3
"""Actual child-process regressions for host manifest coverage, isolation and failure aggregation."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('host_runner', ROOT / 'tools/run-host-tests.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class HostRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='host-runner-contract-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tools = self.root / 'tools'
        self.tools.mkdir()
        self.output = self.root / 'receipts'
        self.rows = []
        self.extra_args = []
        self.add('overlay-bridge-test.mjs', '', stage='history')
        self.manifest = self.tools / 'host-tests.manifest.json'
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(runner, 'ROOT', self.root))
        self.stack.enter_context(patch.object(runner, 'MANIFEST', self.manifest))

    def add(self, name, source, stage='fast', **fields):
        (self.tools / name).write_text(source, encoding='utf-8')
        row = dict(path='tools/' + name, stage=stage, reason='Isolated runner regression',
                   requires=[], args=[], environment={}, timeoutSeconds=10)
        row.update(fields)
        self.rows.append(row)
        return row

    def save(self):
        self.manifest.write_text(json.dumps(dict(schema=1, tests=self.rows)), encoding='utf-8')

    def invoke(self):
        self.save()
        argv = ['run-host-tests.py', '--stage', 'fast', '--output', str(self.output), *self.extra_args]
        with patch.object(sys, 'argv', argv), contextlib.redirect_stdout(io.StringIO()):
            code = runner.main()
        return code, json.loads((self.output / 'manifest.json').read_text())

    def test_real_failed_child_does_not_hide_later_pass_and_fixture_is_removed(self):
        self.add('test-failure.py', 'raise SystemExit(7)')
        self.add('test-pass.py', 'print("later child actually ran")')
        code, report = self.invoke()
        self.assertEqual(1, code)
        self.assertEqual(['FAILED', 'PASS'], [row['status'] for row in report['tests']])
        self.assertEqual(7, report['tests'][0]['exitCode'])
        self.assertIn('actually ran', (self.output / 'test-pass.py.log').read_text())
        self.assertFalse(list(self.output.glob('host-test-*')))

    def test_timeout_is_reported_then_next_child_runs(self):
        self.add('test-timeout.py', 'import time; time.sleep(20)', timeoutSeconds=1)
        self.add('test-pass.py', 'print("after timeout")')
        code, report = self.invoke()
        self.assertEqual(1, code)
        self.assertEqual(['TIMEOUT', 'PASS'], [row['status'] for row in report['tests']])
        self.assertFalse(list(self.output.glob('host-test-*')))

    def test_setup_exception_is_an_explicit_failure_and_aggregation_continues(self):
        self.add('test-missing.py', 'raise AssertionError("must not execute")')
        self.add('test-pass.py', 'print("after failed setup")')
        original = runner.Inputs.values
        def values(inputs, fixture, row):
            if row['path'].endswith('test-missing.py'):
                raise KeyError('fixture metadata missing')
            return original(inputs, fixture, row)
        with patch.object(runner.Inputs, 'values', new=values):
            code, report = self.invoke()
        self.assertEqual(1, code)
        self.assertEqual(['FAILED', 'PASS'], [row['status'] for row in report['tests']])
        self.assertEqual('KeyError', report['tests'][0]['errorType'])

    def test_children_receive_private_home_and_no_inherited_secrets_or_optimization(self):
        self.add('test-environment.py', 'import json,os; print(json.dumps({k:os.getenv(k) for k in '
                 '["HOME","USERPROFILE","PYTHONOPTIMIZE","API_KEY","DSHA_OLD_AUTH","NODE_OPTIONS","NPM_CONFIG_PASSWORD"]}))')
        with patch.dict(os.environ, {'API_KEY': 'synthetic-secret', 'DSHA_OLD_AUTH': 'synthetic',
                                    'NODE_OPTIONS': '--trace-deprecation', 'PYTHONOPTIMIZE': '2',
                                    'NPM_CONFIG_PASSWORD': 'synthetic-password'}):
            code, report = self.invoke()
        self.assertEqual(0, code)
        facts = json.loads((self.output / 'test-environment.py.log').read_text())
        self.assertEqual(facts['HOME'], facts['USERPROFILE'])
        self.assertTrue(Path(facts['HOME']).is_relative_to(self.output))
        for key in ('PYTHONOPTIMIZE', 'API_KEY', 'DSHA_OLD_AUTH', 'NODE_OPTIONS', 'NPM_CONFIG_PASSWORD'):
            self.assertIsNone(facts[key], key)
        self.assertFalse(Path(facts['HOME']).exists())

    def test_orphan_and_duplicate_entries_fail_manifest_check(self):
        self.add('test-known.py', '')
        self.save()
        self.assertEqual(2, len(runner.load_manifest()['tests']))
        (self.tools / 'test-orphan.py').write_text('')
        with self.assertRaisesRegex(ValueError, 'COVERAGE'):
            runner.load_manifest()
        (self.tools / 'test-orphan.py').unlink()
        self.rows.append(dict(self.rows[-1]))
        self.save()
        with self.assertRaisesRegex(ValueError, 'COVERAGE'):
            runner.load_manifest()

    def test_invalid_executor_placeholder_and_boolean_timeout_cannot_be_silent(self):
        row = self.add('test-known.py', '')
        for field, value, code in [('executor', 'shell', 'EXECUTOR'), ('args', ['{unknown}'], 'PLACEHOLDER'),
                                   ('platform', ['android'], 'PLATFORM'), ('timeoutSeconds', True, 'TIMEOUT')]:
            old = row.get(field)
            row[field] = value
            self.save()
            with self.assertRaisesRegex(ValueError, code):
                runner.load_manifest()
            if old is None:
                row.pop(field)
            else:
                row[field] = old

    def test_runtime_identity_and_session_metadata_are_checked_for_each_test(self):
        args = type('Args', (), dict(node='node', toolchain_root=None, playwright=None,
                    browsers_path=None, jdk=None, raw='raw-fixture', managed='managed-fixture'))()
        with patch.dict(os.environ, {'JAVA_HOME': str(self.root / 'jdk')}):
            inputs = runner.Inputs(args)
        self.assertEqual((self.root / 'jdk').resolve(), inputs.jdk)
        session = type('Session', (), {})()
        from unittest.mock import Mock
        session.register = Mock(side_effect=[None, ValueError('changed bytes')])
        inputs.session = session
        with patch('test_runtime_fixture.identity', return_value=(Path('first'), {})) as validate:
            self.assertEqual(Path('first'), inputs.runtime('raw'))
            with self.assertRaisesRegex(ValueError, 'changed bytes'):
                inputs.runtime('raw')
        self.assertEqual(2, validate.call_count)
        self.assertEqual(2, session.register.call_count)

    def test_final_full_byte_failure_invalidates_a_provisionally_successful_child(self):
        from runtime_fixture_proof import content
        fixture = self.root / 'raw'
        fixture.mkdir()
        (fixture / 'package.json').write_text('{}')
        (fixture / 'package-lock.json').write_text('{}')
        package = fixture / 'node_modules/@deepseek-ai/dsh/package.json'
        package.parent.mkdir(parents=True)
        version = json.loads((ROOT / 'tools/dsh-runtime/package.json').read_text())['dependencies']['@deepseek-ai/dsh']
        package.write_text(json.dumps({'version': version}))
        (fixture / 'node_modules/tool.js').write_text('original')
        import hashlib
        proof = dict(version=2, installation='npm-ci-ignore-scripts', installationId='a' * 32,
                     kind='raw', dshVersion=version,
                     lockSha256=hashlib.sha256((ROOT / 'tools/dsh-runtime/package-lock.json').read_bytes()).hexdigest(),
                     archiveRecipeInputs={}, overlayInputs={}, contentProof=content(fixture))
        (fixture / 'dsha-test-runtime.json').write_text(json.dumps(proof))
        self.extra_args = ['--raw', str(fixture)]
        self.add('test-byte-change.py', 'import os; from pathlib import Path; '
                 'p=Path(os.environ["DSHA_TEST_RUNTIME"])/"node_modules/tool.js"; '
                 's=p.stat(); p.write_text("modified"); os.utime(p,ns=(s.st_atime_ns,s.st_mtime_ns)); '
                 'print("child would pass before the mandatory final byte check")', requires=['raw'])
        code, report = self.invoke()
        self.assertEqual(1, code)
        self.assertEqual('FAILED_FIXTURE_INVALIDATED', report['tests'][0]['status'])
        self.assertEqual('PASS', report['tests'][0]['childExitStatus'])
        self.assertIn('runtime-fixture-final-verification', report['failures'])
        self.assertIn('BYTES_OR_MEMBERS_CHANGED', report['runtimeFixtureVerification'][0]['error'])
        self.assertFalse(list(self.output.glob('dsha-runtime-session-*')))


if __name__ == '__main__':
    unittest.main(verbosity=2)
