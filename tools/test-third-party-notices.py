"""Exercise the actual archive/declaration gate with isolated npm and JNI fixtures."""
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('third_party_notices',
                                             Path(__file__).with_name('check-third-party-notices.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ThirdPartyNoticesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / 'runtime.bin'
        self.lock = {'packages': {'node_modules/@example/native': {
            'version': '1.2.3', 'license': 'MIT AND MPL-2.0'}}}

    def archive_package(self, license_name='MIT AND MPL-2.0', version='1.2.3'):
        content = json.dumps({'name': '@example/native', 'version': version,
                              'license': license_name}).encode()
        with tarfile.open(self.archive, 'w:gz') as output:
            member = tarfile.TarInfo('usr/node_modules/@example/native/package.json')
            member.size = len(content)
            output.addfile(member, io.BytesIO(content))

    def test_actual_restricted_identity_requires_notice_and_exact_declaration(self):
        self.archive_package()
        result = gate.inventory(self.archive, self.lock, '`node_modules/@example/native`')
        self.assertEqual('MIT AND MPL-2.0', result[0]['declaredLicense'])
        with self.assertRaisesRegex(ValueError, 'RESTRICTED_NOTICE'):
            gate.inventory(self.archive, self.lock, 'wrapper alone is MIT')
        for license_name, version in [('MIT', '1.2.3'), ('MIT AND MPL-2.0', '2.0.0')]:
            self.archive_package(license_name, version)
            with self.subTest(license_name=license_name), self.assertRaisesRegex(ValueError, 'PACKAGE_DECLARATION'):
                gate.inventory(self.archive, self.lock, '`node_modules/@example/native`')

    def test_new_native_file_requires_an_explicit_reviewed_notice(self):
        native = self.root / 'app/src/main/jniLibs/arm64-v8a'
        native.mkdir(parents=True)
        (native / 'libdsha-session.so').write_bytes(b'fixture')
        self.assertEqual(1, len(gate.verify_native_members(self.root)))
        (native / 'unknown.so').write_bytes(b'new')
        with self.assertRaisesRegex(ValueError, 'NEW_NATIVE'):
            gate.verify_native_members(self.root)

    def test_source_license_bytes_and_required_missing_file_are_rejected(self):
        gate.verify_licenses()
        licenses = self.root / 'app/src/main/assets/licenses'
        licenses.mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, 'LICENSE_MISSING'):
            gate.verify_licenses(self.root)


if __name__ == '__main__':
    unittest.main()
