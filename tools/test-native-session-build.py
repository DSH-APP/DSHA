"""Exercise reproducibility comparisons without writing shipped native assets."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('native_session_build',
                                             Path(__file__).parent / 'native-session/build.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class NativeSessionBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.ndk = self.root / 'ndk'
        self.ndk.mkdir()
        (self.ndk / 'source.properties').write_text('Pkg.Revision = ' + builder.NDK_VERSION)
        self.output = self.root / 'libdsha-session.so'
        self.output.write_bytes(b'shipped')
        self.commands = []

    def compile(self, command, **options):
        self.commands.append(command)
        if '-o' in command:
            Path(command[command.index('-o') + 1]).write_bytes(b'shipped')

    def test_read_only_check_uses_locked_api_alignment_and_exact_comparison(self):
        with patch.object(builder.platform, 'system', return_value='Linux'), \
                patch.object(builder.subprocess, 'run', side_effect=self.compile):
            builder.build(self.ndk, self.output, check=True)
        self.assertEqual(b'shipped', self.output.read_bytes())
        self.assertIn('--target=aarch64-linux-android23', self.commands[0])
        self.assertIn('-Wl,-z,max-page-size=16384', self.commands[0])
        self.assertIn('-Wl,-z,common-page-size=4096', self.commands[0])
        self.assertNotEqual(self.output, Path(self.commands[0][-1]))
        self.assertFalse(Path(self.commands[0][-1]).exists())

    def test_mismatch_preserves_original_and_rejects_missing_original(self):
        self.output.write_bytes(b'changed')
        with patch.object(builder.platform, 'system', return_value='Linux'), \
                patch.object(builder.subprocess, 'run', side_effect=self.compile):
            with self.assertRaisesRegex(ValueError, 'REBUILD_MISMATCH'):
                builder.build(self.ndk, self.output, check=True)
            self.assertEqual(b'changed', self.output.read_bytes())
            self.output.unlink()
            with self.assertRaisesRegex(ValueError, 'REBUILD_MISMATCH'):
                builder.build(self.ndk, self.output, check=True)
        self.assertFalse(self.output.exists())

    def test_wrong_ndk_never_starts_compiler(self):
        (self.ndk / 'source.properties').write_text('Pkg.Revision = 27.0.0')
        with patch.object(builder.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'NDK_VERSION'):
                builder.build(self.ndk, self.output, check=True)
        run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
