"""发布通道回归：新预览不能让稳定通道失去可更新版本。"""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('manifest', Path(__file__).resolve().parents[1] / 'tools/generate-release-manifest.py')
manifest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manifest)


def release(code, channel):
    return dict(versionCode=code, version=f'1.2.{code}' + ('-rc.1' if channel == 'preview' else ''), channel=channel)


def feed(*items):
    return dict(schemaVersion=1, packageName='com.dsh.client', certificateSha256=manifest.PUBLISH_CERT, releases=list(items))


class ReleaseChannelTest(unittest.TestCase):
    def test_explicit_official_release_keeps_tested_rc_name(self):
        self.assertEqual(manifest.release_channel('0.1.5-rc1', 'stable'), 'stable')
        self.assertEqual(manifest.release_channel('0.1.5-rc1'), 'preview')
        self.assertEqual(manifest.release_channel('1.1.10'), 'stable')

    def test_new_stable_retains_published_preview_unchanged(self):
        previous = release(112, 'preview')
        current = dict(version='0.1.5-rc1', versionCode=116, channel=manifest.release_channel('0.1.5-rc1', 'stable'))
        self.assertEqual(manifest.retain_channels(current, feed(previous)), [current, previous])

    def test_preview_retains_latest_stable(self):
        stable, preview = release(113, 'stable'), release(114, 'preview')
        self.assertEqual(manifest.retain_channels(preview, feed(stable, release(112, 'preview'))), [preview, stable])

    def test_stable_then_preview_retains_new_stable(self):
        stable, preview = release(115, 'stable'), release(116, 'preview')
        previous = manifest.retain_channels(stable, feed(release(114, 'preview'), release(113, 'stable')))
        self.assertEqual(manifest.retain_channels(preview, feed(*previous)), [preview, stable])

    def test_rebuild_replaces_same_release_without_duplicate(self):
        current = release(112, 'preview')
        self.assertEqual(manifest.retain_channels(current, feed(current)), [current])

    def test_rejects_lower_code_or_wrong_signing_identity(self):
        with self.assertRaises(ValueError):
            manifest.retain_channels(release(111, 'preview'), feed(release(112, 'preview')))
        previous = feed(release(111, 'preview'))
        previous['certificateSha256'] = '0' * 64
        with self.assertRaises(ValueError):
            manifest.retain_channels(release(112, 'preview'), previous)


if __name__ == '__main__':
    unittest.main()
