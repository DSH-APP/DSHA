import tempfile
from pathlib import Path
import unittest
from release_names import apk_filename, apk_name_version

class ReleaseNamesTest(unittest.TestCase):
    def test_diagnostic_version_does_not_rename_downloads(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root/'app').mkdir()
            build = root/'app/build.gradle'
            build.write_text('ext.dshaApkNameVersion = \'0.2.0-rc2\'\nversionName "0.2.1-20261001.1835-dsh0.2.0-rc.2"')
            self.assertEqual(apk_filename('standard', root), 'dsha-0.2.0-rc2.apk')
            self.assertEqual(apk_filename('low', root), 'dsha-0.2.0-rc2low.apk')
            build.write_text('versionName "0.1.7-rc2"')
            self.assertEqual(apk_filename('low', root), 'dsha-0.1.7-rc2low.apk')

    def test_invalid_file_names_and_flavors_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root/'app').mkdir()
            for value in ('../outside', '', 'foo/bar'):
                (root/'app/build.gradle').write_text("ext.dshaApkNameVersion = '"+value+"'")
                with self.assertRaises(ValueError): apk_name_version(root)
            with self.assertRaises(ValueError): apk_filename('debug', root)

if __name__ == '__main__': unittest.main()
