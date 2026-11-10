#!/usr/bin/env python3
"""合成小 APK 的正反例：受管资产名/摘要与 gh 固定成员门禁。

不读取真实离线输入、不构建 APK、不联网：用临时仓库根与手工 ZIP 触发每一类拒绝。
"""
import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
GH_MEMBER = 'gh_fixture/bin/gh'
GH_BODY = b'#!/bin/sh\necho fixture gh\n'


def load_gate():
    spec = importlib.util.spec_from_file_location(
        'apk_managed_assets_gate', ROOT / 'tools/verify-apk-managed-assets.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


GATE = load_gate()


def digest(value):
    return hashlib.sha256(value).hexdigest()


def gzip_tar(member=GH_MEMBER, body=GH_BODY):
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode='w') as archive:
        info = tarfile.TarInfo(member)
        info.size = len(body)
        info.mode = 0o755
        archive.addfile(info, io.BytesIO(body))
    return gzip.compress(raw.getvalue())


def fixture_root(base, archive=None, member_sha=None, member_bytes=None):
    """最小仓库根：部署清单、受管清单、gh 锁与源文件。"""
    root = base / 'root'
    assets = root / 'app/src/main/assets'
    assets.mkdir(parents=True)
    source = gzip_tar() if archive is None else archive
    (assets / 'gh-cli.tar.gz').write_bytes(source)
    (assets / 'plain.txt').write_bytes(b'plain managed asset\n')
    (assets / 'tree').mkdir()
    (assets / 'tree/entry.js').write_bytes(b'// tree entry\n')
    managed = {
        'schema': 1,
        'installs': [
            {'asset': 'plain.txt', 'target': 'root/plain.txt', 'executable': False},
            {'asset': 'gh-cli.tar.gz', 'target': 'opt/gh/bin/gh', 'executable': True,
             'extract': {'member': GH_MEMBER, 'sha256': digest(GH_BODY)}},
        ],
        'assetFiles': ['gh-cli.tar.gz'],
        'assetTrees': ['tree'],
        'launcherTrees': [], 'launcherSources': [], 'generatorSources': [],
    }
    (assets / 'managed-runtime-inputs.json').write_text(
        json.dumps(managed), encoding='utf-8')
    deployment = {
        'schema': 1,
        'packagedFiles': ['managed-runtime-inputs.json', 'plain.txt'],
        'packagedTrees': ['tree'],
        'sourceInputs': ['gh-cli.tar.gz'],
        'sourceOnlyTrees': [],
        'generatedFiles': ['gh-cli.bin'],
        'externalGeneratedFiles': [],
        'dynamicReaders': [],
        'sourceTransforms': {'gh-cli.tar.gz': 'gh-cli.bin'},
    }
    (root / 'tools/gh-cli').mkdir(parents=True)
    (root / 'tools/asset-deployment.json').write_text(
        json.dumps(deployment), encoding='utf-8')
    lock = {'schema': 1, 'version': '2.103.3', 'member': GH_MEMBER,
            'binarySha256': digest(GH_BODY) if member_sha is None else member_sha,
            'binaryBytes': len(GH_BODY) if member_bytes is None else member_bytes}
    (root / 'tools/gh-cli/packages.lock.json').write_text(
        json.dumps(lock), encoding='utf-8')
    return root


class ManagedApkAssetsGate(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = fixture_root(self.base)
        self.assets = self.root / 'app/src/main/assets'
        self.apk_count = 0

    def packaged(self, **overrides):
        files = {
            'assets/managed-runtime-inputs.json':
                (self.assets / 'managed-runtime-inputs.json').read_bytes(),
            'assets/plain.txt': (self.assets / 'plain.txt').read_bytes(),
            'assets/tree/entry.js': (self.assets / 'tree/entry.js').read_bytes(),
            'assets/gh-cli.bin': (self.assets / 'gh-cli.tar.gz').read_bytes(),
        }
        for name in overrides.pop('omit', ()):
            files.pop(name, None)
        files.update(overrides)
        return files

    def apk(self, files):
        self.apk_count += 1
        path = self.base / ('fixture-%d.apk' % self.apk_count)
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_STORED) as archive:
            for name, data in files.items():
                archive.writestr(name, data)
        return path

    def assert_rejected(self, path, code, root=None):
        with self.assertRaises((RuntimeError, ValueError)) as caught:
            GATE.verify(path, self.root if root is None else root)
        self.assertIn(code, str(caught.exception))

    def test_complete_packaged_apk_passes_with_the_renamed_gh_archive(self):
        report = GATE.verify(self.apk(self.packaged()), self.root)
        self.assertEqual(report['gh'], {'member': GH_MEMBER, 'bytes': len(GH_BODY),
                                        'sha256': digest(GH_BODY)})
        self.assertEqual(report['managedMembers'], 4)
        self.assertEqual(report['generatedReferences'], 0)

    def test_generated_member_is_compared_with_the_build_directory(self):
        # descriptor 的 generatedFiles 由生成器重写（如离线 rootfs 被重新打包），
        # 不能拿仓库源输入当基准；缺少生成目录时必须失败而不是静默跳过。
        (self.assets / 'built.txt').write_bytes(b'repository copy\n')
        managed = json.loads((self.assets / 'managed-runtime-inputs.json').read_text(encoding='utf-8'))
        managed['assetFiles'].append('built.txt')
        (self.assets / 'managed-runtime-inputs.json').write_text(
            json.dumps(managed), encoding='utf-8')
        deployment = json.loads((self.root / 'tools/asset-deployment.json').read_text(encoding='utf-8'))
        deployment['generatedFiles'].append('built.txt')
        (self.root / 'tools/asset-deployment.json').write_text(
            json.dumps(deployment), encoding='utf-8')
        files = self.packaged()
        files['assets/built.txt'] = b'generated copy\n'
        built = self.root / 'app/build/generated/standardAssets'
        built.mkdir(parents=True)
        (built / 'built.txt').write_bytes(b'generated copy\n')
        report = GATE.verify(self.apk(files), self.root)
        self.assertEqual(report['generatedReferences'], 1)
        # 生成目录缺失 → 明确失败，不用仓库副本冒充基准。
        (built / 'built.txt').unlink()
        self.assert_rejected(self.apk(files), 'APK_GENERATED_ASSETS_MISSING: built.txt')
        (built / 'built.txt').write_bytes(b'generated copy\n')
        # APK 里是仓库副本而不是生成字节 → 摘要不符。
        (built / 'built.txt').write_bytes(b'other generated\n')
        self.assert_rejected(self.apk(files), 'APK_MANAGED_ASSET_DIGEST: assets/built.txt')

    def test_missing_packaged_gh_name_is_rejected_even_when_the_source_name_exists(self):
        files = self.packaged(omit=['assets/gh-cli.bin'])
        files['assets/gh-cli.tar.gz'] = (self.assets / 'gh-cli.tar.gz').read_bytes()
        self.assert_rejected(self.apk(files), 'APK_MANAGED_ASSET_MISSING: assets/gh-cli.bin')

    def test_source_name_leaking_into_the_apk_is_rejected(self):
        files = self.packaged()
        files['assets/gh-cli.tar.gz'] = (self.assets / 'gh-cli.tar.gz').read_bytes()
        self.assert_rejected(self.apk(files), 'APK_TRANSFORM_SOURCE_LEAK: gh-cli.tar.gz')

    def test_agp_gunzip_survivor_gh_cli_tar_is_rejected(self):
        files = self.packaged(omit=['assets/gh-cli.bin'])
        files['assets/gh-cli.tar'] = (self.assets / 'gh-cli.tar.gz').read_bytes()
        self.assert_rejected(self.apk(files), 'APK_MANAGED_ASSET_MISSING: assets/gh-cli.bin')

    def test_managed_bytes_must_equal_the_repository_source(self):
        files = self.packaged()
        files['assets/plain.txt'] = b'changed bytes\n'
        self.assert_rejected(self.apk(files), 'APK_MANAGED_ASSET_DIGEST: assets/plain.txt')

    def test_managed_tree_member_cannot_disappear(self):
        self.assert_rejected(self.apk(self.packaged(omit=['assets/tree/entry.js'])),
                             'APK_MANAGED_ASSET_MISSING: assets/tree/entry.js')

    def test_managed_inputs_manifest_must_match_the_repository_copy(self):
        files = self.packaged()
        files['assets/managed-runtime-inputs.json'] = b'{"schema":1,"installs":[]}\n'
        self.assert_rejected(self.apk(files), 'APK_MANAGED_ASSET_DIGEST: '
                             'assets/managed-runtime-inputs.json')

    def test_gh_archive_must_be_gzip(self):
        source = b'not a gzip archive\n'
        self.root = fixture_root(self.base / 'plain', archive=source)
        self.assets = self.root / 'app/src/main/assets'
        files = self.packaged()
        files['assets/gh-cli.bin'] = source
        self.assert_rejected(self.apk(files), 'APK_GH_ARCHIVE_NOT_GZIP')

    def test_gh_member_digest_mismatch_is_rejected(self):
        longer = b'#!/bin/sh\necho other gh\n'
        self.root = fixture_root(self.base / 'tampered', archive=gzip_tar(body=longer))
        self.assets = self.root / 'app/src/main/assets'
        self.assert_rejected(self.apk(self.packaged()), 'APK_GH_MEMBER_BYTES')

    def test_gh_member_digest_is_checked_when_sizes_match(self):
        same_length = b'#!/bin/sh\necho fixture gX\n'
        self.assertEqual(len(same_length), len(GH_BODY))
        self.root = fixture_root(self.base / 'same-size', archive=gzip_tar(body=same_length))
        self.assets = self.root / 'app/src/main/assets'
        self.assert_rejected(self.apk(self.packaged()), 'APK_GH_MEMBER_DIGEST')

    def test_lock_and_managed_install_row_must_share_the_member_digest(self):
        self.root = fixture_root(self.base / 'row', member_sha='0' * 64)
        self.assets = self.root / 'app/src/main/assets'
        self.assert_rejected(self.apk(self.packaged()), 'MANAGED_GH_MEMBER_DIGEST')

    def test_managed_inputs_without_a_gh_install_row_is_rejected(self):
        managed = json.loads((self.assets / 'managed-runtime-inputs.json').read_text(encoding='utf-8'))
        managed['installs'] = [managed['installs'][0]]
        managed['assetFiles'] = []
        (self.assets / 'managed-runtime-inputs.json').write_text(
            json.dumps(managed), encoding='utf-8')
        self.assert_rejected(self.apk(self.packaged()), 'MANAGED_GH_ROW')

    def test_rename_target_must_stay_a_generated_file(self):
        deployment = json.loads((self.root / 'tools/asset-deployment.json').read_text(encoding='utf-8'))
        deployment['generatedFiles'] = []
        (self.root / 'tools/asset-deployment.json').write_text(
            json.dumps(deployment), encoding='utf-8')
        self.assert_rejected(self.apk(self.packaged()), 'ASSET_DEPLOYMENT_TRANSFORM')

    def test_missing_apk_is_rejected(self):
        with self.assertRaises(RuntimeError) as caught:
            GATE.verify(self.base / 'absent.apk', self.root)
        self.assertIn('APK_MISSING', str(caught.exception))


if __name__ == '__main__':
    unittest.main()
