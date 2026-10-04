"""Complete npm fixture content proof; producer-created schema 2 is required for reuse."""
from pathlib import Path
import hashlib
import json
import os
import stat
import re

SCHEMA = 2
MARKER = 'dsha-test-runtime.json'


def io_path(path):
    """Exact Win32 extended I/O spelling; never part of logical proof names."""
    selected=Path(path).absolute()
    if os.name!='nt':return selected
    value=str(selected)
    if value.startswith('\\\\?\\'):return selected
    return Path('\\\\?\\UNC\\'+value[2:] if value.startswith('\\\\') else '\\\\?\\'+value)


def logical_path(path):
    value=str(path)
    if os.name=='nt' and value.startswith('\\\\?\\UNC\\'):return Path('\\\\'+value[8:])
    if os.name=='nt' and value.startswith('\\\\?\\'):return Path(value[4:])
    return Path(path)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')


def digest_file(path, before):
    flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0)
    fd = os.open(path, flags)
    try:
        opened = os.fstat(fd)
        if not stat.S_ISREG(opened.st_mode) or (before.st_dev, before.st_ino, before.st_size) != (opened.st_dev, opened.st_ino, opened.st_size):
            raise ValueError('TEST_FIXTURE_FILE_CHANGED')
        digest = hashlib.sha256()
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            for chunk in iter(lambda: stream.read(1048576), b''):
                digest.update(chunk)
        after = path.lstat()
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_mode) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_mode):
            raise ValueError('TEST_FIXTURE_FILE_CHANGED')
        return digest.hexdigest()
    finally:
        os.close(fd)


def fingerprint(node, kind, target=None):
    result = {'type': kind, 'dev': str(node.st_dev), 'ino': str(node.st_ino),
              # Directory allocation length is not a payload length. On Windows NTFS,
              # identical directory queries can return 0 or 4096 after file reads.
              # Exact directory identity/time/type and the full member set remain bound.
              'size': '0' if kind == 'DIRECTORY' else str(node.st_size), 'mtimeNs': str(node.st_mtime_ns),
              'executable': 0 if os.name == 'nt' else node.st_mode & 0o111}
    if target is not None:
        result['target'] = target
    return result


def content(directory, metadata_out=None):
    selected = Path(directory).absolute()
    if selected.is_symlink() or getattr(selected, 'is_junction', lambda: False)():
        raise ValueError('TEST_FIXTURE_ROOT_LINK')
    root = io_path(selected).resolve(strict=True)
    if not root.is_dir():
        raise ValueError('TEST_FIXTURE_ROOT_TYPE')
    if metadata_out is not None:
        before = root.stat()
        metadata_out.update(root={'dev': str(before.st_dev), 'ino': str(before.st_ino)}, entries={})
    entries = {}
    for base, directories, files in os.walk(root, topdown=True, followlinks=False):
        for name in sorted(directories + files):
            path = Path(base) / name
            relative = path.relative_to(root).as_posix()
            if relative == MARKER:
                if not stat.S_ISREG(path.lstat().st_mode):
                    raise ValueError('TEST_FIXTURE_MARKER_TYPE')
                continue
            if len(relative) > 4096 or '\\' in relative or any(c in relative for c in '\r\n\0'):
                raise ValueError('TEST_FIXTURE_PATH')
            node = path.lstat()
            if stat.S_ISLNK(node.st_mode) or getattr(path, 'is_junction', lambda: False)():
                if name in directories:
                    directories.remove(name)
                target = os.readlink(path)
                if not target or len(target) > 4096 or any(c in target for c in '\r\n\0'):
                    raise ValueError('TEST_FIXTURE_LINK_FORMAT')
                try:
                    resolved = path.resolve(strict=True)
                except (OSError, RuntimeError) as error:
                    raise ValueError('TEST_FIXTURE_LINK_UNREADABLE') from error
                if not resolved.is_relative_to(root):
                    raise ValueError('TEST_FIXTURE_LINK_OUTSIDE:' + relative)
                entries[relative] = {'type': 'LINK', 'target': target}
            elif stat.S_ISDIR(node.st_mode):
                entries[relative] = {'type': 'DIRECTORY'}
            elif stat.S_ISREG(node.st_mode):
                entries[relative] = {'type': 'FILE', 'size': node.st_size, 'executable': 0 if os.name=='nt' else node.st_mode & 0o111, 'sha256': digest_file(path, node)}
            else:
                raise ValueError('TEST_FIXTURE_SPECIAL:' + relative)
            if metadata_out is not None:
                metadata_out['entries'][relative] = fingerprint(node, entries[relative]['type'],
                    entries[relative].get('target'))
            if len(entries) > 200000:
                raise ValueError('TEST_FIXTURE_ENTRY_LIMIT')
        directories.sort()
    for name in ('package.json', 'package-lock.json'):
        if entries.get(name, {}).get('type') != 'FILE':
            raise ValueError('TEST_FIXTURE_PACKAGE_METADATA')
    if entries.get('node_modules', {}).get('type') != 'DIRECTORY':
        raise ValueError('TEST_FIXTURE_MODULES_ROOT')
    entries = dict(sorted(entries.items()))
    return {'algorithm': 'sha256', 'executablePolicy':'windows-no-posix-mode' if os.name=='nt' else 'posix-mode', 'entries': entries, 'digest': hashlib.sha256(canonical(entries)).hexdigest()}


def validate(directory, proof, metadata_out=None):
    if proof.get('version') != SCHEMA or proof.get('installation') != 'npm-ci-ignore-scripts' or not isinstance(proof.get('installationId'), str) or not re.fullmatch('[a-f0-9]{32}',proof['installationId']):
        raise ValueError('TEST_FIXTURE_REINSTALL_REQUIRED')
    expected = proof.get('contentProof')
    if not isinstance(expected, dict) or expected.get('algorithm') != 'sha256' or not isinstance(expected.get('entries'), dict):
        raise ValueError('TEST_FIXTURE_CONTENT_PROOF_MISSING')
    if expected.get('executablePolicy')!=('windows-no-posix-mode' if os.name=='nt' else 'posix-mode'):
        raise ValueError('TEST_FIXTURE_REINSTALL_REQUIRED')
    if hashlib.sha256(canonical(expected['entries'])).hexdigest() != expected.get('digest'):
        raise ValueError('TEST_FIXTURE_CONTENT_PROOF_INVALID')
    actual = content(directory, metadata_out=metadata_out)
    if actual != expected:
        raise ValueError('TEST_FIXTURE_BYTES_OR_MEMBERS_CHANGED')
    return actual


def reuse(directory, expected):
    """Return trusted existing schema 2, or None for a known legacy fixture requiring npm ci."""
    path = Path(directory) / MARKER
    if not path.exists():
        return None
    if path.is_symlink() or not path.is_file():
        raise ValueError('TEST_FIXTURE_MARKER_TYPE')
    proof = json.loads(path.read_text(encoding='utf-8'))
    if proof.get('version') == 1 or proof.get('version')==SCHEMA and not proof.get('contentProof',{}).get('executablePolicy'):
        return None
    validate(directory, proof)
    for key, value in expected.items():
        if proof.get(key) != value:
            raise ValueError('TEST_FIXTURE_REUSE_IDENTITY:' + key)
    return proof
