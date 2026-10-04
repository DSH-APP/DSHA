#!/usr/bin/env python3
"""Build-time table validation. Native consumers have separate behavior tests."""
import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('credential_paths_generator', ROOT / 'tools/generate-credential-paths.py')
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class CredentialPathGeneratorTest(unittest.TestCase):
    def doc(self):
        return json.loads(generator.SOURCE.read_bytes())

    def test_current_input_generates_current_java_exactly(self):
        self.assertEqual(generator.OUTPUT.read_bytes(), generator.generate(generator.SOURCE.read_bytes()))

    def test_missing_real_machine_names_fail_generation(self):
        for name in ('.bridge_token', '.anonymous-user-id'):
            doc = self.doc()
            doc['externalMachine']['exact'].remove(name)
            with self.assertRaises(ValueError):
                generator.generate(json.dumps(doc).encode())

    def test_session_rule_cannot_expand_to_all_projects(self):
        doc = self.doc()
        doc['sessionExclusions'][0]['root'] = 'projects'
        with self.assertRaises(ValueError):
            generator.generate(json.dumps(doc).encode())

    def test_externals_and_backup_groups_stay_distinct(self):
        doc = self.doc()
        self.assertIn('.env', doc['externalCredentials']['exact'])
        self.assertNotIn('.env', doc['externalMachine']['exact'])
        self.assertNotIn('.env', doc['backupMachine']['additionalExact'])
        self.assertIn('.dsha-web.identity', doc['backupMachine']['additionalExact'])


if __name__ == '__main__':
    unittest.main()
