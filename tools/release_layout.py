"""Path and byte checks shared by release migration and historical receipt reads."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def checked_path(path, boundary, must_exist=False):
    """Reject escape paths and existing links/junctions before any file operation."""
    boundary = Path(boundary).absolute()
    path = Path(path)
    if not path.is_absolute():
        raise ValueError('RELEASE_LAYOUT_ABSOLUTE_PATH_REQUIRED')
    path = Path(os.path.abspath(path))
    if not path.is_relative_to(boundary) or not path.resolve().is_relative_to(boundary):
        raise ValueError('RELEASE_LAYOUT_PATH_OUTSIDE_ROOT')
    for component in (boundary, *reversed(path.parents), path):
        if component != boundary and not component.is_relative_to(boundary):
            continue
        try:
            info = component.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('RELEASE_LAYOUT_LINK_PATH')
    if must_exist and not path.is_file():
        raise ValueError('RELEASE_LAYOUT_FILE_MISSING')
    return path


def source_snapshot_path(recorded_path, expected_sha256, root=ROOT):
    """A migration map locates original evidence; its bytes still prove the snapshot."""
    root = Path(root).resolve()
    recorded = Path(recorded_path)
    if not recorded.is_absolute():
        recorded = root / recorded
    recorded = checked_path(recorded, root)
    allowed = (root / 'app/build/stability-acceptance', root / 'artifacts/source')

    def checked_snapshot(candidate):
        candidate = checked_path(candidate, root, must_exist=True)
        if not any(candidate.is_relative_to(folder) for folder in allowed):
            raise ValueError('ACCEPTANCE_BASELINE_SNAPSHOT')
        if digest(candidate) != expected_sha256:
            raise ValueError('ACCEPTANCE_BASELINE_SNAPSHOT')
        return candidate

    if recorded.exists():
        return checked_snapshot(recorded)
    legacy = re.fullmatch(r'release/build(\d+)-source/(.+)', recorded.relative_to(root).as_posix())
    if not legacy:
        raise ValueError('ACCEPTANCE_BASELINE_SNAPSHOT')
    migrated = root / 'artifacts/source' / ('build' + legacy[1]) / legacy[2]
    migrations = root / 'artifacts/release-layout-migrations'
    checked_path(migrations, root)
    matches = set()
    for path in sorted(migrations.glob('*.json')):
        checked_path(path, migrations, must_exist=True)
        mapping = json.loads(path.read_text(encoding='utf8'))
        if mapping.get('schema') != 1 or mapping.get('status') != 'APPLIED' or mapping.get('root') != str(root):
            continue
        for row in mapping.get('files', []):
            if row.get('kind') == 'source' and row.get('source') == str(recorded) \
                    and row.get('target') == str(migrated) and row.get('sha256') == expected_sha256:
                matches.add(str(checked_snapshot(Path(row['target']))))
    if len(matches) != 1:
        raise ValueError('ACCEPTANCE_BASELINE_SNAPSHOT')
    return Path(matches.pop())
