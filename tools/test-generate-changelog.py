"""Guard installed-build identity, locale completeness and exact bundled notes bytes."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from ui_language_catalog import LANGUAGES, RESOURCE_FOLDERS

spec = importlib.util.spec_from_file_location(
    'generate_changelog', Path(__file__).with_name('generate-changelog.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ChangelogGenerationTest(unittest.TestCase):
    version = '0.2.1-rc.3-20261024.0345-dsh0.2.1-rc.3'

    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='dsha-changelog-')
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.write('app/build.gradle', f'versionCode 194\nversionName "{self.version}"\n')
        for locale in LANGUAGES:
            self.write(f'docs/releases/build194-changes.{locale}.md', self.notes(locale))

    def notes(self, body='Current changes'):
        return f'# DSHA {self.version} / build194\n\n{body}\n'

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(text.encode('utf-8'))

    def test_all_ten_locales_are_bundled_and_checked_exactly(self):
        self.assertEqual(gate.update(self.root), (194, self.version))
        self.assertEqual(gate.update(self.root, check=True), (194, self.version))
        for locale in LANGUAGES:
            folder = RESOURCE_FOLDERS[locale].replace('values', 'raw', 1)
            self.assertEqual(
                (self.root / f'app/src/main/res/{folder}/changelog.md').read_bytes(),
                self.notes(locale).encode('utf-8'))

    def test_stale_source_cannot_be_relabelled_with_current_code(self):
        self.write('docs/releases/build194-changes.zh.md', self.notes().replace('build194', 'build157'))
        with self.assertRaisesRegex(ValueError, 'SOURCE_IDENTITY_MISMATCH:zh'):
            gate.update(self.root)

    def test_another_diagnostic_version_of_the_same_code_is_rejected(self):
        self.write('docs/releases/build194-changes.zh.md', self.notes().replace('.0345-', '.0120-'))
        with self.assertRaisesRegex(ValueError, 'SOURCE_IDENTITY_MISMATCH:zh'):
            gate.update(self.root)

    def test_missing_current_build_source_fails(self):
        self.write('app/build.gradle', f'versionCode 195\nversionName "{self.version}"\n')
        with self.assertRaisesRegex(ValueError, 'SOURCE_MISSING:zh'):
            gate.update(self.root)

    def test_missing_english_source_fails(self):
        (self.root / 'docs/releases/build194-changes.en.md').unlink()
        with self.assertRaisesRegex(ValueError, 'SOURCE_MISSING:en'):
            gate.update(self.root)

    def test_missing_any_supported_language_cannot_fall_back_to_other_notes(self):
        (self.root / 'docs/releases/build194-changes.ar.md').unlink()
        with self.assertRaisesRegex(ValueError, 'SOURCE_MISSING:ar'):
            gate.update(self.root)

    def test_changed_raw_bytes_fail_the_build_gate(self):
        gate.update(self.root)
        self.write('app/src/main/res/raw-en/changelog.md', self.notes('Old build'))
        with self.assertRaisesRegex(ValueError, 'CHANGELOG_STALE:en'):
            gate.update(self.root, check=True)

    def test_empty_notes_and_notes_over_the_activity_limit_fail(self):
        for body, error in [('', 'BODY_MISSING'), ('x' * 16385, 'CHANGELOG_LIMIT')]:
            self.write('docs/releases/build194-changes.zh.md', self.notes(body))
            with self.assertRaisesRegex(ValueError, error):
                gate.update(self.root)


if __name__ == '__main__':
    unittest.main(verbosity=2)
