"""Deterministic generated-text writer, compatible with Python 3.9.

Only controls the writer's newline translation. Existing package/recipe CR bytes
are not silently normalized; original tar/npm bytes remain the caller's bytes.
"""
from pathlib import Path


def write_text(path, value, encoding='utf-8'):
    with Path(path).open('w', encoding=encoding, newline='\n') as output:
        return output.write(value)


def matches_text(path, value, encoding='utf-8'):
    target=Path(path)
    return target.is_file() and target.read_bytes()==value.encode(encoding)
