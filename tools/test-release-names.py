import tempfile
from pathlib import Path
import unittest
from release_names import apk_filename, apk_name_version, apk_delivery_path, apk_version_from_filename, source_directory, delivery_receipt_path

class ReleaseNamesTest(unittest.TestCase):
    def test_diagnostic_version_does_not_rename_downloads(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root/'app').mkdir()
            build = root/'app/build.gradle'
            build.write_text('ext.dshaApkNameVersion = \'0.2.0-rc2\'\nversionName "0.2.1-20261001.1835-dsh0.2.0-rc.2"')
            self.assertEqual(apk_filename('standard', root), 'dsha-0.2.0-rc2.apk')
            self.assertEqual(apk_delivery_path('standard',root), root/'release/0.2.0-rc2/dsha-0.2.0-rc2.apk')
            self.assertEqual(source_directory(158,root),root/'artifacts/source/build158')
            self.assertEqual(delivery_receipt_path(158,root),root/'artifacts/deliveries/build158.json')
            build.write_text('versionName "0.1.7-rc2"')
            self.assertEqual(apk_filename('standard', root), 'dsha-0.1.7-rc2.apk')

    def test_invalid_file_names_and_flavors_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root/'app').mkdir()
            for value in ('../outside', '', 'foo/bar'):
                (root/'app/build.gradle').write_text("ext.dshaApkNameVersion = '"+value+"'")
                with self.assertRaises(ValueError): apk_name_version(root)
            (root/'app/build.gradle').write_text("ext.dshaApkNameVersion = '0.2.0-rc2'")
            # 单 Standard 变体：其它 flavor（包括已删除的 low）不能生成下载名。
            for flavor in ('debug','low','Low',''):
                with self.subTest(flavor=flavor), self.assertRaises(ValueError):
                    apk_filename(flavor, root)
            for code in (0,-1,'158',True):
                with self.assertRaises(ValueError):delivery_receipt_path(code,root)

    def test_historical_versions_keep_conventional_names(self):
        for version in ('0.2.0-rc2','1.2.0-alpha.4-vc105','0.1.5-rc2.1'):
            for suffix in ('.apk','.apk.sha256'):
                self.assertEqual(apk_version_from_filename('dsha-'+version+suffix),version)
        # 旧的 -low 文件名不再解析，避免把历史双包当当前发布身份。
        for name in ('dsha-0.2.0-rc2low.apk','dsha-0.2.0-rc2low.apk.sha256'):
            with self.assertRaises(ValueError):apk_version_from_filename(name)
        for name in ('../dsha-0.2.0.apk','dsha-.apk','dsha-../../outside.apk','other.apk','dsha-0.2.0.zip'):
            with self.assertRaises(ValueError):apk_version_from_filename(name)

if __name__ == '__main__': unittest.main()
