#!/usr/bin/env python3
"""正式 Release 附带共存测试包证据合并的正反例。

不构建 APK、不读取真实证书与 Secret：用临时目录 + 手工证据触发每一类接受 / 拒绝。
"""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))

COMMIT = 'ba8c37ff0' + '0' * 31
PRODUCTION_PACKAGE = 'io.github.sunsetrne.dsha'
PRODUCTION_CERT = 'a' * 64
TEST_CERT_DER = b'\x30\x82\x01\x0a\x02\x82\x01\x01\x00' + b'fixture-test-certificate' * 8
TEST_CERT = hashlib.sha256(TEST_CERT_DER).hexdigest()
VERSION_NAME = '0.2.1-rc.5-20261110.0700-dsh0.2.2-rc.1'


def load_tool():
    spec = importlib.util.spec_from_file_location(
        'release_test_packages', ROOT / 'tools/release-test-packages.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256_bytes(value):
    return hashlib.sha256(value).hexdigest()


def write_pem(path, der):
    body = base64.b64encode(der).decode('ascii')
    path.write_text(
        '-----BEGIN CERTIFICATE-----\n' + body + '\n-----END CERTIFICATE-----\n', encoding='ascii')


def make_production_bundle(root):
    bundle = root / 'bundle'
    bundle.mkdir()
    apk_name = 'dsha-a-su-' + COMMIT[:8] + '.apk'
    apk = bundle / apk_name
    apk.write_bytes(b'production-apk-bytes')
    evidence = bundle / 'dsha-a-su-build-evidence.zip'
    evidence.write_bytes(b'evidence-zip')
    info = {
        'schema': 1,
        'kind': 'dsha-a-su-production-release-apk',
        'commit': COMMIT,
        'runId': '1234567890',
        'runAttempt': '1',
        'packageName': PRODUCTION_PACKAGE,
        'versionCode': 352,
        'versionName': VERSION_NAME,
        'apkFile': apk_name,
        'apkSize': apk.stat().st_size,
        'apkSha256': sha256_bytes(apk.read_bytes()),
        'certificateSha256': PRODUCTION_CERT,
        'assetBundleSha256': 'b' * 64,
        'testPackageEvidenceToolSha256': 'c' * 64,
    }
    info_path = bundle / 'build-info.json'
    info_path.write_text(json.dumps(info, indent=2) + '\n', encoding='utf-8')
    sums = ''.join(
        sha256_bytes((bundle / name).read_bytes()) + '  ' + name + '\n'
        for name in (apk_name, 'dsha-a-su-build-evidence.zip', 'build-info.json'))
    (bundle / 'SHA256SUMS').write_text(sums, encoding='utf-8')
    (bundle / 'release-notes.md').write_text('# build352\n\n正文\n', encoding='utf-8')
    return bundle


def make_variant(root, variant, mutate=None):
    directory = root / 'test-packages' / ('release-test-package-evidence-' + variant)
    directory.mkdir(parents=True, exist_ok=True)
    suffix = {'debug': '.debug', 'dev': '.dev'}[variant]
    version_suffix = {'debug': '-SunsetDebug', 'dev': '-SunsetDev'}[variant]
    apk_name = 'dsha-a-su-%s-%s.apk' % (variant, COMMIT[:8])
    apk = directory / apk_name
    apk.write_bytes(('apk-' + variant).encode('utf-8'))
    write_pem(directory / 'test-cert.pem', TEST_CERT_DER)
    info = {
        'schema': 1,
        'kind': 'dsha-a-su-test-package-variant',
        'commit': COMMIT,
        'runId': '1234567890',
        'runAttempt': '1',
        'packageName': PRODUCTION_PACKAGE + suffix,
        'variant': variant,
        'debuggable': variant == 'debug',
        'releaseIdentity': 'mod',
        'versionCode': 352,
        'versionName': VERSION_NAME + version_suffix,
        'apkFile': apk_name,
        'apkSize': apk.stat().st_size,
        'apkSha256': sha256_bytes(apk.read_bytes()),
        'certificateSha256': TEST_CERT,
        'signingKeySource': 'configured-secret',
        'signedWith': 'test-cert',
    }
    if mutate:
        mutate(info)
    (directory / 'build-info.json').write_text(json.dumps(info, indent=2) + '\n', encoding='utf-8')
    return directory


class MergeTest(unittest.TestCase):
    def setUp(self):
        self.tool = load_tool()
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.bundle = make_production_bundle(self.root)
        make_variant(self.root, 'debug')
        make_variant(self.root, 'dev')

    def tearDown(self):
        self._tmp.cleanup()

    def merge(self, requested='debug+dev'):
        return self.tool.merge(self.bundle, self.root / 'test-packages',
                               'release-test-package-evidence-', 'dsha-a-su', requested)

    def assert_checksums_consistent(self):
        lines = (self.bundle / 'SHA256SUMS').read_text(encoding='utf-8').splitlines()
        names = {}
        for line in lines:
            digest, name = line.split('  ', 1)
            self.assertEqual(len(digest), 64)
            names[name] = digest
            self.assertEqual(sha256_bytes((self.bundle / name).read_bytes()), digest, name)
        return names

    def test_merge_both_variants_updates_evidence(self):
        result = self.merge()
        self.assertEqual([entry['variant'] for entry in result['testPackages']], ['debug', 'dev'])
        self.assertEqual(result['testCertificateSha256'], TEST_CERT)
        self.assertEqual(
            [entry['apkFile'] for entry in result['testPackages']],
            ['dsha-a-su-debug-' + COMMIT[:8] + '.apk', 'dsha-a-su-dev-' + COMMIT[:8] + '.apk'])
        self.assertTrue((self.bundle / 'dsha-a-su-test-cert.pem').is_file())
        assets = (self.bundle / 'test-package-assets.txt').read_text(encoding='utf-8').splitlines()
        self.assertEqual(assets, [
            'dsha-a-su-debug-' + COMMIT[:8] + '.apk',
            'dsha-a-su-dev-' + COMMIT[:8] + '.apk',
            'dsha-a-su-test-cert.pem',
        ])
        info = json.loads((self.bundle / 'build-info.json').read_text(encoding='utf-8'))
        self.assertEqual(info['testCertificateSha256'], TEST_CERT)
        self.assertEqual(info['testPackages'][0]['signedWith'], 'test-cert')
        self.assertEqual(info['testPackages'][0]['packageName'], PRODUCTION_PACKAGE + '.debug')
        self.assertEqual(info['testPackages'][1]['packageName'], PRODUCTION_PACKAGE + '.dev')
        # SHA256SUMS 必须自洽：build-info.json 行随合并更新，新资产有独立条目。
        names = self.assert_checksums_consistent()
        self.assertIn('dsha-a-su-debug-' + COMMIT[:8] + '.apk', names)
        self.assertIn('dsha-a-su-dev-' + COMMIT[:8] + '.apk', names)
        self.assertIn('dsha-a-su-test-cert.pem', names)
        self.assertEqual(names['build-info.json'], sha256_bytes((self.bundle / 'build-info.json').read_bytes()))
        notes = (self.bundle / 'release-notes.md').read_text(encoding='utf-8')
        self.assertIn('# build352', notes)
        self.assertIn('## 共存测试包（.debug / .dev）', notes)
        self.assertIn(PRODUCTION_PACKAGE + '.debug', notes)
        self.assertIn(TEST_CERT, notes)

    def test_merge_single_debug_only(self):
        result = self.merge(requested='debug')
        self.assertEqual([entry['variant'] for entry in result['testPackages']], ['debug'])
        assets = (self.bundle / 'test-package-assets.txt').read_text(encoding='utf-8').splitlines()
        self.assertEqual(len(assets), 2)
        self.assertFalse((self.bundle / ('dsha-a-su-dev-' + COMMIT[:8] + '.apk')).exists())
        self.assert_checksums_consistent()

    def test_merge_runs_twice_is_rejected(self):
        self.merge()
        with self.assertRaises(SystemExit):
            self.merge()

    def test_requested_variants_mapping(self):
        self.assertEqual(self.tool.requested_variants('debug+dev'), ['debug', 'dev'])
        self.assertEqual(self.tool.requested_variants('debug'), ['debug'])
        self.assertEqual(self.tool.requested_variants('dev'), ['dev'])
        for value in ('none', 'debug,dev', '', 'DEBUG'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.tool.requested_variants(value)

    def test_rejects_missing_variant_evidence(self):
        (self.root / 'test-packages' / 'release-test-package-evidence-dev' / 'build-info.json').unlink()
        with self.assertRaisesRegex(SystemExit, 'TEST_PACKAGE_EVIDENCE_MISSING'):
            self.merge()

    def test_rejects_commit_mismatch(self):
        make_variant(self.root, 'dev', mutate=lambda info: info.update(commit='f' * 40))
        with self.assertRaisesRegex(SystemExit, 'TEST_PACKAGE_COMMIT_MISMATCH'):
            self.merge()

    def test_rejects_run_mismatch(self):
        make_variant(self.root, 'debug', mutate=lambda info: info.update(runId='999'))
        with self.assertRaisesRegex(SystemExit, 'TEST_PACKAGE_RUN_MISMATCH'):
            self.merge()

    def test_rejects_package_suffix_mismatch(self):
        make_variant(self.root, 'debug', mutate=lambda info: info.update(packageName=PRODUCTION_PACKAGE + '.dev'))
        with self.assertRaisesRegex(SystemExit, 'TEST_PACKAGE_PACKAGE_MISMATCH'):
            self.merge()

    def test_rejects_debuggable_mismatch(self):
        make_variant(self.root, 'debug', mutate=lambda info: info.update(debuggable=False))
        with self.assertRaisesRegex(SystemExit, 'TEST_PACKAGE_DEBUGGABLE_MISMATCH'):
            self.merge()

    def test_rejects_ephemeral_signing_key(self):
        make_variant(self.root, 'debug', mutate=lambda info: info.update(signingKeySource='ephemeral'))
        with self.assertRaisesRegex(SystemExit, 'TEST_PACKAGE_SIGNING_KEY_NOT_CONFIGURED'):
            self.merge()

    def test_rejects_production_certificate(self):
        make_variant(self.root, 'debug', mutate=lambda info: info.update(certificateSha256=PRODUCTION_CERT))
        with self.assertRaisesRegex(SystemExit, 'TEST_PACKAGE_USES_PRODUCTION_CERTIFICATE'):
            self.merge()

    def test_rejects_apk_digest_mismatch(self):
        apk = self.root / 'test-packages/release-test-package-evidence-dev' / ('dsha-a-su-dev-' + COMMIT[:8] + '.apk')
        apk.write_bytes(b'tampered')
        with self.assertRaisesRegex(SystemExit, 'TEST_PACKAGE_APK_DIGEST_MISMATCH'):
            self.merge()

    def test_rejects_path_like_apk_name(self):
        make_variant(self.root, 'debug', mutate=lambda info: info.update(apkFile='../escape.apk'))
        with self.assertRaisesRegex(SystemExit, 'TEST_PACKAGE_APK_NAME_MISMATCH'):
            self.merge()

    def test_rejects_certificate_differing_between_variants(self):
        other = hashlib.sha256(b'other-cert').hexdigest()
        make_variant(self.root, 'dev', mutate=lambda info: info.update(certificateSha256=other))
        write_pem(self.root / 'test-packages/release-test-package-evidence-dev/test-cert.pem',
                  b'other-cert')
        with self.assertRaisesRegex(SystemExit, 'TEST_PACKAGE_CERTIFICATE_DIFFERS_BETWEEN_VARIANTS'):
            self.merge()

    def test_rejects_tampered_production_evidence(self):
        (self.bundle / 'dsha-a-su-build-evidence.zip').write_bytes(b'tampered')
        with self.assertRaisesRegex(SystemExit, 'SHA256SUMS_MISMATCH'):
            self.merge()

    def test_pem_digest_matches_der(self):
        pem = self.root / 'cert.pem'
        write_pem(pem, TEST_CERT_DER)
        self.assertEqual(self.tool.pem_certificate_sha256(pem), TEST_CERT)


if __name__ == '__main__':
    unittest.main()
