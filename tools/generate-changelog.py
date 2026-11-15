"""Generate/check current-build locale notes; never attach a new identity to old notes."""
import argparse
import re
from pathlib import Path
from source_text import matches_text, write_text
from ui_language_catalog import LANGUAGES, RESOURCE_FOLDERS

ROOT = Path(__file__).resolve().parents[1]


def build_identity(root):
    gradle = (root / 'app/build.gradle').read_text(encoding='utf-8')
    code = re.search(r'^\s*versionCode\s+(\d+)\s*$', gradle, re.M)
    version = re.search(r'^\s*versionName\s+"([^"\r\n]+)"\s*$', gradle, re.M)
    if not code or not version:
        raise ValueError('CHANGELOG_BUILD_IDENTITY_MISSING')
    return int(code.group(1)), version.group(1)


def update(root, check=False):
    code, version = build_identity(root)
    heading = f'# DSHA {version} / build{code}'
    for locale in LANGUAGES:
        folder = RESOURCE_FOLDERS[locale].replace('values', 'raw', 1)
        source = root / f'docs/releases/build{code}-changes.{locale}.md'
        target = root / f'app/src/main/res/{folder}/changelog.md'
        if not source.is_file():
            raise ValueError('CHANGELOG_SOURCE_MISSING:' + locale)
        content = source.read_text(encoding='utf-8')
        first, separator, body = content.partition('\n')
        if first != heading:
            raise ValueError('CHANGELOG_SOURCE_IDENTITY_MISMATCH:' + locale)
        if not separator or not body.strip():
            raise ValueError('CHANGELOG_BODY_MISSING:' + locale)
        if len(content.encode('utf-8')) > 16384:
            raise ValueError('CHANGELOG_LIMIT:' + locale)
        if check:
            if not matches_text(target, content):
                raise ValueError('CHANGELOG_STALE:' + locale)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            write_text(target, content)
    return code, version


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    try:
        code, version = update(ROOT, args.check)
    except ValueError as failure:
        raise SystemExit(str(failure))
    print(f'PASS bundled locale changelog: {version} / build{code}')
