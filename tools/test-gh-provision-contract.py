#!/usr/bin/env python3
"""gh 供给契约：包装脚本/固定输入/受管安装/环境重建目录归属必须同源。

只读当前源码与已还原的固定输入；不联网、不启动容器、不改文件。
自举行为本身由 tools/test-gh-managed-shim.mjs 用本地 fixture 执行。
"""
import hashlib
import importlib.util
import json
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
ASSETS = ROOT / 'app/src/main/assets'
LOCK = ROOT / 'tools/gh-cli/packages.lock.json'
WRAPPER = ASSETS / 'gh-wrapper.sh'
MANAGED = ASSETS / 'managed-runtime-inputs.json'
DOC = ROOT / 'docs/runtime-data-plugin-architecture.md'
HISTORICAL_SYSTEM = {
    'bin', 'sbin', 'lib', 'lib64', 'usr', 'etc', 'var', 'tmp', 'run', 'proc', 'dev', 'sys',
    'root', '.l2s', 'lost+found', 'sdcard', 'storage', 'system', 'apex',
}


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / (name + '.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


class GhProvisionContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lock = json.loads(LOCK.read_text(encoding='utf-8'))
        cls.managed = json.loads(MANAGED.read_text(encoding='utf-8'))

    def test_fixed_input_bytes_match_the_tool_lock(self):
        asset = ASSETS / 'gh-cli.tar.gz'
        self.assertTrue(asset.is_file(), 'gh-cli.tar.gz 未还原；先运行 tools/prepare-gh-cli.py')
        self.assertEqual(asset.stat().st_size, self.lock['bytes'])
        self.assertEqual(sha256(asset), self.lock['sha256'])
        license_file = ASSETS / 'licenses/gh-MIT.txt'
        self.assertEqual(sha256(license_file), self.lock['licenseSha256'])

    def test_managed_install_rows_extract_the_same_member_and_digest(self):
        rows = [row for row in self.managed['installs'] if row.get('asset') == 'gh-cli.tar.gz']
        self.assertEqual(len(rows), 1)
        row = rows[0]
        self.assertEqual(row['target'], 'opt/gh/bin/gh')
        self.assertIs(row['executable'], True)
        self.assertEqual(row['extract'], {
            'member': self.lock['member'],
            'sha256': self.lock['binarySha256'],
        })

    def test_asset_serving_and_fixed_input_restore_agree(self):
        deployment = json.loads((ROOT / 'tools/asset-deployment.json').read_text(encoding='utf-8'))
        # 源固定输入名保持 gh-cli.tar.gz；APK 打包名改 gh-cli.bin，避免 AGP 9.1.1
        # 资产合并对 .gz 自动 gunzip 并去掉扩展名（T78 结论 B）。
        self.assertIn('gh-cli.tar.gz', deployment['sourceInputs'])
        self.assertNotIn('gh-cli.tar.gz', deployment['packagedFiles'])
        self.assertEqual(deployment['sourceTransforms']['gh-cli.tar.gz'], 'gh-cli.bin')
        self.assertIn('gh-cli.bin', deployment['generatedFiles'])
        self.assertIn('gh-cli.tar.gz', module('ci-assets').NAMES)
        runtime_tools = (ROOT / 'app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java') \
            .read_text(encoding='utf-8')
        self.assertIn('context.getAssets().open("gh-cli.bin")', runtime_tools)
        layout = (ROOT / 'app/src/main/java/com/deepseekharness/app/util/ManagedRuntimeLayout.java') \
            .read_text(encoding='utf-8')
        self.assertIn('out.add("opt/gh/bin/gh")', layout)

    def test_wrapper_pins_match_the_lock_and_keep_the_honest_failure_message(self):
        source = WRAPPER.read_text(encoding='utf-8')
        for name, value in (
            ('GH_VERSION', self.lock['version']),
            ('GH_TARBALL_SHA256', self.lock['sha256']),
            ('GH_BINARY_SHA256', self.lock['binarySha256']),
            ('GH_BINARY_BYTES', str(self.lock['binaryBytes'])),
        ):
            match = re.search(r'^%s="([^"]*)"$' % name, source, re.M)
            self.assertIsNotNone(match, name)
            self.assertEqual(match.group(1), value)
        self.assertIn('DSHA_GH_BOOTSTRAP', source)
        self.assertNotIn('install the bundled gh binary first', source)

    def test_native_login_page_pins_match_the_lock(self):
        source = (ROOT / 'app/src/main/java/com/deepseekharness/app/util/GhManagedInstall.java') \
            .read_text(encoding='utf-8')
        for name, value in (
            ('VERSION', self.lock['version']),
            ('BINARY_BYTES', str(self.lock['binaryBytes'])),
            ('BINARY_SHA256', self.lock['binarySha256']),
        ):
            match = re.search(r'%s\s*=\s*"([^"]*)"' % name, source)
            if value[0].isdigit() and name == 'BINARY_BYTES':
                match = re.search(r'%s\s*=\s*([0-9_]+)L' % name, source)
                self.assertIsNotNone(match, name)
                self.assertEqual(match.group(1).replace('_', ''), value)
                continue
            self.assertIsNotNone(match, name)
            self.assertEqual(match.group(1), value)
        for needle in ('"opt/gh/bin/gh"', '"root/.config/gh/hosts.yml"', '"github.com"'):
            self.assertIn(needle, source)
        # 页面只消费锁定的同一版本；包装脚本仍负责缺失/损坏时的自举与拒绝执行。
        wrapper = WRAPPER.read_text(encoding='utf-8')
        self.assertIn('GH_VERSION="%s"' % self.lock['version'], wrapper)

    def test_rebuild_contract_classification_is_explicit(self):
        source = (ROOT / 'app/src/main/java/com/deepseekharness/app/util/MaintenanceToolPaths.java') \
            .read_text(encoding='utf-8')
        system = source.split('SYSTEM_ROOTS =', 1)[1].split(');', 1)[0]
        self.assertEqual(set(re.findall(r'"([^"]+)"', system)), HISTORICAL_SYSTEM)
        tools = source.split('USER_TOOL_ROOTS =', 1)[1].split(';', 1)[0]
        self.assertEqual(set(re.findall(r'"([^"]+)"', tools)), {'opt'})
        snapshot = (ROOT / 'app/src/main/java/com/deepseekharness/app/backup/MaintenanceDataSnapshot.java') \
            .read_text(encoding='utf-8')
        self.assertIn('inspectToolRoots', snapshot)
        maintenance = (ROOT / 'app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java') \
            .read_text(encoding='utf-8')
        self.assertIn('inspectToolRoots', maintenance)
        self.assertIn('overflowed', maintenance)

    def test_descriptor_rejects_malformed_extract_rows(self):
        descriptor = module('prepare-runtime-descriptor')
        base = {
            'installs': [{
                'asset': 'gh-cli.tar.gz',
                'target': 'opt/gh/bin/gh',
                'executable': True,
                'extract': {'member': self.lock['member'], 'sha256': self.lock['binarySha256']},
            }],
            'assetFiles': [], 'assetTrees': [], 'launcherTrees': [], 'launcherSources': [],
            'generatorSources': [],
        }
        descriptor.validate_installs(ROOT, base)
        for extract in (
            {'member': self.lock['member']},
            {'member': '/absolute/path', 'sha256': 'a' * 64},
            {'member': 'm', 'sha256': 'not-a-digest'},
            {'member': 'm', 'sha256': 'a' * 64, 'extra': True},
        ):
            spec = json.loads(json.dumps(base))
            spec['installs'][0]['extract'] = extract
            with self.assertRaises(ValueError) as rejected:
                descriptor.validate_installs(ROOT, spec)
            self.assertTrue(
                str(rejected.exception).startswith(
                    ('RUNTIME_INSTALL_EXTRACT', 'INVALID_RUNTIME_INPUT_PATH')
                ),
                str(rejected.exception),
            )
        spec = json.loads(json.dumps(base))
        spec['installs'][0]['asset'] = 'gh-cli.bin'
        with self.assertRaisesRegex(ValueError, 'RUNTIME_INSTALL_EXTRACT'):
            descriptor.validate_installs(ROOT, spec)

    def test_documented_contract_records_the_measured_risk(self):
        text = DOC.read_text(encoding='utf-8')
        for needle in ('/opt', '100,000', '48,294', '3.5 GB', 'usr/local/bin'):
            self.assertIn(needle, text)
        for needle in ('opt/gh/bin/gh', 'gh-cli.tar.gz', 'MaintenanceToolPaths'):
            self.assertIn(needle, text)


if __name__ == '__main__':
    unittest.main()
