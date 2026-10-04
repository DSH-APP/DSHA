#!/usr/bin/env python3
"""Real byte/metadata, Node/Python child, stale-receipt and mandatory-final-proof regressions."""
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from runtime_fixture_proof import SCHEMA, content, fingerprint, validate
from runtime_fixture_session import FixtureSession, sha

ROOT = Path(__file__).resolve().parents[1]


class RuntimeSessionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='runtime-session-regression-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.fixture = self.root / 'fixture'
        self.put('package.json', '{}')
        self.put('package-lock.json', '{}')
        version = json.loads((ROOT / 'tools/dsh-runtime/package.json').read_text())['dependencies']['@deepseek-ai/dsh']
        self.put('node_modules/@deepseek-ai/dsh/package.json', json.dumps({'version': version}))
        self.member = self.put('node_modules/third-party/index.js', 'original')
        self.proof = {'version': SCHEMA, 'installation': 'npm-ci-ignore-scripts', 'installationId': 'a' * 32,
                      'kind': 'raw', 'dshVersion': version, 'lockSha256': sha(ROOT / 'tools/dsh-runtime/package-lock.json'),
                      'archiveRecipeInputs': {}, 'overlayInputs': {}, 'contentProof': content(self.fixture)}
        self.marker = self.put('dsha-test-runtime.json', json.dumps(self.proof))
        self.session = FixtureSession(self.root)
        self.closed = False

    def tearDown(self):
        if not self.closed:
            self.session.finalize()

    def put(self, relative, body):
        path = self.fixture / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
        return path

    def environment(self):
        environment = {key: value for key, value in os.environ.items() if not key.startswith('DSHA_')}
        environment.update(self.session.environment(), DSHA_TEST_RUNTIME=str(self.fixture), PYTHONUTF8='1')
        return environment

    def children(self, environment=None):
        environment = environment or self.environment()
        node_code = 'const {testRuntime}=await import(process.argv[1]);console.log(testRuntime());'
        node = subprocess.run([shutil.which('node') or 'node', '--input-type=module', '-e', node_code,
                               (ROOT / 'tools/test-runtime-fixture.mjs').as_uri()],
                              capture_output=True, text=True, env=environment)
        python = subprocess.run([sys.executable, '-B', '-c', 'from test_runtime_fixture import runtime; print(runtime())'],
                                cwd=ROOT / 'tools', capture_output=True, text=True, env=environment)
        return node, python

    def rejected(self, code, environment=None):
        for result in self.children(environment):
            self.assertNotEqual(0, result.returncode)
            self.assertIn(code, result.stderr)

    def test_full_bytes_are_checked_once_reused_by_real_children_and_rechecked_at_end(self):
        with patch('runtime_fixture_session.validate', wraps=validate) as complete:
            self.session.register(self.fixture, self.proof)
            self.session.register(self.fixture, self.proof)
            self.assertEqual(1, complete.call_count)
            for result in self.children():
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(str(self.fixture), result.stdout.strip())
            results = self.session.finalize()
            self.closed = True
            self.assertEqual(2, complete.call_count)
        self.assertEqual(['PASS_FINAL_COMPLETE_BYTE_RECHECK'], [row['status'] for row in results])
        self.assertFalse(self.session.path.exists())

    def test_changed_dependency_and_added_member_are_rejected_by_both_consumers(self):
        self.session.register(self.fixture, self.proof)
        self.member.write_text('modified', encoding='utf-8')
        self.rejected('SESSION_METADATA_CHANGED')
        self.member.write_text('original', encoding='utf-8')
        self.put('node_modules/extra/new.js', 'extra member')
        self.rejected('SESSION_METADATA_CHANGED')

    def test_changed_bytes_with_restored_metadata_invalidate_the_whole_final_proof(self):
        self.session.register(self.fixture, self.proof)
        before = self.member.stat()
        self.member.write_text('modified', encoding='utf-8')
        os.utime(self.member, ns=(before.st_atime_ns, before.st_mtime_ns))
        # Metadata reuse is only provisional; it can never authorize a final PASS by itself.
        results = self.session.finalize()
        self.closed = True
        self.assertEqual('FAILED', results[0]['status'])
        self.assertIn('BYTES_OR_MEMBERS_CHANGED', results[0]['error'])
        self.assertFalse(self.session.path.exists())

    def test_marker_rewrite_and_same_bytes_directory_replacement_do_not_reuse_proof(self):
        self.session.register(self.fixture, self.proof)
        original = self.marker.read_bytes()
        changed = copy.deepcopy(self.proof)
        changed['installationId'] = 'b' * 32
        self.marker.write_text(json.dumps(changed), encoding='utf-8')
        self.rejected('SESSION_MARKER_CHANGED')
        self.marker.write_bytes(original)
        old = self.root / 'previous'
        self.fixture.rename(old)
        shutil.copytree(old, self.fixture)
        for result in self.children():
            self.assertNotEqual(0, result.returncode)
            self.assertIn('SESSION_', result.stderr)

    def test_wrong_secret_stale_parent_and_arbitrary_receipt_path_fail_closed(self):
        self.session.register(self.fixture, self.proof)
        environment = self.environment()
        environment['DSHA_TEST_RUN_SECRET'] = 'f' * 64
        self.rejected('EXPIRED_OR_FOREIGN', environment)
        original = self.session.path.read_bytes()
        data = json.loads(original)
        data['ownerPid'] += 1
        self.session.path.write_text(json.dumps(data), encoding='utf-8')
        self.rejected('EXPIRED_OR_FOREIGN')
        self.session.path.write_bytes(original)
        arbitrary = self.root / 'old-proof.json'
        arbitrary.write_bytes(original)
        environment = self.environment()
        environment['DSHA_TEST_RUN_RECEIPT'] = str(arbitrary)
        self.rejected('RECEIPT_SCOPE', environment)

    def test_empty_consumer_scope_and_changed_current_input_do_not_skip_checks(self):
        self.session.register(self.fixture, self.proof)
        original = self.session.path.read_bytes()
        data = json.loads(original)
        data['consumerInputs'] = {}
        self.session.path.write_text(json.dumps(data), encoding='utf-8')
        self.rejected('CONSUMER_SCOPE')
        data = json.loads(original)
        data['consumerInputs']['tools/test-runtime-fixture.mjs'] = '0' * 64
        self.session.path.write_text(json.dumps(data), encoding='utf-8')
        self.rejected('CONSUMER_CHANGED')

    def test_standalone_selection_still_hashes_complete_tree_without_a_receipt(self):
        self.session.register(self.fixture, self.proof)
        before = self.member.stat()
        self.member.write_text('modified', encoding='utf-8')
        os.utime(self.member, ns=(before.st_atime_ns, before.st_mtime_ns))
        environment = self.environment()
        environment.pop('DSHA_TEST_RUN_RECEIPT')
        environment.pop('DSHA_TEST_RUN_SECRET')
        for result in self.children(environment):
            self.assertNotEqual(0, result.returncode)
            self.assertIn('BYTES_OR_MEMBERS_CHANGED', result.stderr)

    def test_full_device_and_inode_bits_are_bound_without_truncation(self):
        self.session.register(self.fixture, self.proof)
        original = self.session.path.read_bytes()
        for field, delta in (('dev', 1 << 32), ('ino', 1 << 64)):
            for root in (True, False):
                data = json.loads(original)
                record = data['fixtures'][str(self.fixture)]['metadata']
                node = record['root'] if root else record['entries']['node_modules/third-party/index.js']
                node[field] = str(int(node[field]) + delta)
                self.session.path.write_text(json.dumps(data), encoding='utf-8')
                for result in self.children():
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn('SESSION_', result.stderr)
        self.session.path.write_bytes(original)

    def test_directory_allocation_length_is_canonical_but_file_size_remains_bound(self):
        node = SimpleNamespace(st_dev=1 << 48, st_ino=1 << 80, st_size=0,
                               st_mtime_ns=123456789000001, st_mode=0o755)
        empty = fingerprint(node, 'DIRECTORY')
        node.st_size = 4096
        self.assertEqual(empty, fingerprint(node, 'DIRECTORY'))
        self.assertEqual(str(1 << 48), empty['dev'])
        self.assertEqual(str(1 << 80), empty['ino'])
        self.assertEqual('4096', fingerprint(node, 'FILE')['size'])
        self.session.register(self.fixture, self.proof)
        self.member.write_text('original-with-extra-bytes', encoding='utf-8')
        self.rejected('SESSION_METADATA_CHANGED')

    def test_creator_interpreter_path_and_bytes_are_required_by_both_consumers(self):
        self.session.register(self.fixture, self.proof)
        original = self.session.path.read_bytes()
        for field, value in (('python', str(self.root / 'foreign-python.exe')),
                             ('pythonSha256', '0' * 64), ('pythonVersion', '0.0.0')):
            data = json.loads(original)
            data['metadataRuntime'][field] = value
            self.session.path.write_text(json.dumps(data), encoding='utf-8')
            self.rejected('SESSION_METADATA_RUNTIME_CHANGED')
        self.session.path.write_bytes(original)

    @unittest.skipUnless(os.name == 'nt', 'Windows full-precision worker contract')
    def test_windows_worker_requires_actual_node_parent_and_pinned_executable(self):
        self.session.register(self.fixture, self.proof)
        worker = subprocess.run([sys.executable, '-B', str(ROOT / 'tools/runtime_fixture_session.py'),
                                 '--verify-node-receipt', str(self.session.path), str(self.fixture)],
                                capture_output=True, text=True, env=self.environment())
        self.assertNotEqual(0, worker.returncode)
        self.assertIn('EXPIRED_OR_FOREIGN', worker.stderr)
        data = json.loads(self.session.path.read_bytes())
        data['metadataRuntime']['nodeSha256'] = '0' * 64
        self.session.path.write_text(json.dumps(data), encoding='utf-8')
        node, _ = self.children()
        self.assertNotEqual(0, node.returncode)
        self.assertIn('SESSION_METADATA_RUNTIME_CHANGED', node.stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
