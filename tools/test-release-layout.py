#!/usr/bin/env python3
"""快速核对目录迁移的边界、冲突和原字节保留。"""
import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from release_layout import digest


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


migration = load('release_migration', 'migrate-release-layout.py')
delivery = load('release_delivery', 'verify-stability.py')
manifest = load('release_manifest', 'generate-release-manifest.py')


class ReleaseLayoutTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(); self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name).resolve()

    def write(self, relative, content):
        path = self.root / relative; path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content); return path

    def apk_pair(self, folder='', content=b'current APK'):
        apk = self.write('release/' + folder + 'dsha-0.2.0-rc2.apk', content)
        self.write(str(apk.relative_to(self.root)) + '.sha256', (digest(apk) + '  ' + apk.name + '\n').encode())
        return apk

    def test_plan_then_apply_keeps_original_bytes_and_history(self):
        self.apk_pair(); self.apk_pair('history/before-fix/', b'historical APK')
        receipt = self.write('release/build157-delivery.json', b'{"originalPath":"release/dsha-0.2.0-rc2.apk"}\n')
        self.write('release/build157-source/source.zip', b'original ZIP')
        self.write('release/build157-source/receipt.json', b'original source receipt')
        self.write('release/device-report.txt', b'historical report')
        plan = migration.make_plan(self.root)
        self.assertTrue(receipt.exists()); self.assertFalse((self.root / 'artifacts').exists())
        result = migration.apply_plan(plan, self.root)
        self.assertEqual(result['status'], 'APPLIED')
        self.assertEqual((self.root / 'release/0.2.0-rc2/dsha-0.2.0-rc2.apk').read_bytes(), b'current APK')
        self.assertEqual((self.root / 'release/0.2.0-rc2/history/before-fix/dsha-0.2.0-rc2.apk').read_bytes(), b'historical APK')
        self.assertEqual((self.root / 'artifacts/deliveries/build157.json').read_bytes(), b'{"originalPath":"release/dsha-0.2.0-rc2.apk"}\n')
        self.assertEqual((self.root / 'artifacts/source/build157/source.zip').read_bytes(), b'original ZIP')
        self.assertEqual((self.root / 'artifacts/release-history/device-report.txt').read_bytes(), b'historical report')
        for row in result['files']:
            self.assertEqual(digest(Path(row['target'])), row['sha256'])
        self.assertTrue(all(path.name.endswith(('.apk', '.apk.sha256')) for path in migration.file_inventory(self.root)))
        self.assertEqual(migration.make_plan(self.root)['summary']['moves'], 0)
        # A partial or repeated apply only verifies already-moved bytes.
        self.assertEqual(migration.apply_plan(plan, self.root)['status'], 'APPLIED')

    def test_changed_source_and_destination_conflict_stop_before_any_move(self):
        source = self.apk_pair(); plan = migration.make_plan(self.root)
        target = self.write('release/0.2.0-rc2/dsha-0.2.0-rc2.apk', b'existing destination')
        with self.assertRaisesRegex(ValueError, 'DESTINATION_CONFLICT'):
            migration.apply_plan(plan, self.root)
        self.assertTrue(source.exists()); self.assertEqual(target.read_bytes(), b'existing destination')
        target.unlink(); source.write_bytes(b'changed source')
        with self.assertRaisesRegex(ValueError, 'SOURCE_CHANGED'):
            migration.apply_plan(plan, self.root)
        self.assertTrue(source.exists())

    def test_tampered_path_and_checksum_are_rejected(self):
        source = self.apk_pair(); plan = migration.make_plan(self.root)
        changed = copy.deepcopy(plan); changed['files'][0]['target'] = str(self.root.parent / 'outside.apk')
        with self.assertRaisesRegex(ValueError, 'PATH_OUTSIDE_ROOT'):
            migration.apply_plan(changed, self.root)
        self.assertTrue(source.exists())
        source.with_suffix('.apk.sha256').write_text('0' * 64 + '\n', encoding='ascii')
        with self.assertRaisesRegex(ValueError, 'CHECKSUM_MISMATCH'):
            migration.make_plan(self.root)

    def test_new_release_file_and_link_paths_are_rejected(self):
        source = self.apk_pair(); plan = migration.make_plan(self.root)
        self.write('release/late-report.txt', b'new file')
        with self.assertRaisesRegex(ValueError, 'NEW_RELEASE_FILE'):
            migration.apply_plan(plan, self.root)
        self.assertTrue(source.exists())
        try:
            os.symlink(self.root / 'release', self.root / 'linked', target_is_directory=True)
        except (OSError, NotImplementedError):
            return
        with self.assertRaises(ValueError):
            migration.checked_path(self.root / 'linked/dsha-0.2.0-rc2.apk', self.root, True)

    def test_new_delivery_uses_version_group_and_separate_bound_snapshot(self):
        self.write('app/build.gradle', b"ext.dshaApkNameVersion = '0.2.0-rc2'\n")
        apks = []
        for flavor in ('standard', 'low'):
            source = self.write('app/build/' + flavor + '.apk', flavor.encode())
            apks.append(dict(flavor=flavor, path=str(source), sha256=digest(source), versionCode=158))
        snapshot = self.write('app/build/stability-acceptance/fixture/source-sha256.json', b'{"Example.java":"original"}\n')
        report = dict(apks=apks, sourceSnapshot=dict(path=str(snapshot), sha256=digest(snapshot)))
        with patch.object(delivery, 'ROOT', self.root):
            delivery.publish_apks(apks); target = delivery.store_delivery_receipt(report)
        self.assertEqual(target, self.root / 'artifacts/deliveries/build158.json')
        for item in apks:
            self.assertEqual(Path(item['deliveredPath']).parent, self.root / 'release/0.2.0-rc2')
            self.assertEqual(digest(Path(item['deliveredPath'])), item['sha256'])
        self.assertTrue(all(path.name.endswith(('.apk','.apk.sha256')) for path in migration.file_inventory(self.root)))
        stored = json.loads(target.read_text(encoding='utf8'))['sourceSnapshot']
        self.assertEqual(Path(stored['path']), self.root / 'artifacts/source/build158/source-sha256.json')
        self.assertEqual(digest(Path(stored['path'])), digest(snapshot))

    def test_failed_publish_does_not_leave_non_apk_bytes_in_release(self):
        self.write('app/build.gradle', b"ext.dshaApkNameVersion = '0.2.0-rc2'\n")
        source=self.write('app/build/standard.apk',b'original APK')
        def failed_copy(_source,target):
            Path(target).write_bytes(b'partial bytes')
            raise OSError('copy failed')
        with patch.object(delivery,'ROOT',self.root),patch.object(delivery.shutil,'copyfile',failed_copy):
            with self.assertRaises(OSError):
                delivery.publish_apks([dict(flavor='standard',path=str(source),sha256=digest(source),versionCode=158)])
        self.assertEqual(migration.file_inventory(self.root),[])
        self.assertEqual(list((self.root/'artifacts/release-staging').iterdir()),[])

    def test_manifest_downloads_follow_download_version_group(self):
        notes = self.write('notes.txt', b'limited checked scope')
        output = self.root / 'artifacts/manifests/release-manifest.json'
        version = '0.2.9-20261004.2100-dsh0.2.0-rc.2'
        apks = [self.write('dsha-0.2.0-rc2.apk', b'standard'), self.write('dsha-0.2.0-rc2low.apk', b'low')]
        def inspect(apk, flavor, *_):
            return dict(filename=apk.name, flavor=flavor, versionName=version + ('low' if flavor == 'low' else ''),
                        versionCode=158, runtimeId='same-runtime', dshVersion='0.2.0-rc.2', minSdk=30 if flavor == 'standard' else 23,
                        bytes=apk.stat().st_size, sha256=digest(apk))
        argv = ['manifest', '--standard', str(apks[0]), '--low', str(apks[1]), '--build-tools', str(self.root),
                '--notes', str(notes), '--output', str(output)]
        with patch('sys.argv', argv), patch.object(manifest, 'inspect', inspect):
            manifest.main()
        release = json.loads(output.read_text(encoding='utf8'))['releases'][0]
        self.assertEqual(release['version'], version)
        self.assertEqual(release['apkNameVersion'], '0.2.0-rc2')
        for artifact in release['artifacts']:
            self.assertEqual(artifact['url'], 'https://dsha.cc/downloads/0.2.0-rc2/' + artifact['filename'])


if __name__ == '__main__':
    unittest.main()
