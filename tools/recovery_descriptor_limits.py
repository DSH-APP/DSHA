"""Schema 1 producer/consumer budget, sourced from the Android reader's constants."""
import json
import re
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/deepseekharness/app/util/RecoveryDescriptorLimits.java'

def read_limits(source):
    source = re.sub(r'/\*.*?\*/|//[^\n]*', '', source, flags=re.S)
    limits = {}
    for name in ('MAX_BYTES', 'MAX_ENTRIES', 'MAX_PATH_BYTES', 'MAX_PATH_COMPONENTS'):
        values = re.findall(r'^\s*public static final int ' + name + r' = ([0-9_]+);\s*$', source, re.M)
        if len(values) != 1:
            raise ValueError('RECOVERY_DESCRIPTOR_BUDGET_SOURCE')
        limits[name] = int(values[0].replace('_', ''))
    return limits


_limits = read_limits(SOURCE.read_text(encoding='utf8'))
MAX_BYTES = _limits['MAX_BYTES']
MAX_ENTRIES = _limits['MAX_ENTRIES']
MAX_PATH_BYTES = _limits['MAX_PATH_BYTES']
MAX_PATH_COMPONENTS = _limits['MAX_PATH_COMPONENTS']


def path(value):
    if not isinstance(value, str) or not value or value.startswith('/') or '\\' in value or '\0' in value:
        raise ValueError('RECOVERY_PATH_INVALID')
    parts = value.split('/')
    if len(value.encode('utf8')) > MAX_PATH_BYTES or len(parts) > MAX_PATH_COMPONENTS:
        raise ValueError('RECOVERY_PATH_LIMIT')
    if any(part in ('', '.', '..') for part in parts):
        raise ValueError('RECOVERY_PATH_INVALID')
    return value


def validate(data):
    """Check final serialized bytes, before publishing output or loading the whole APK proof."""
    if not isinstance(data, bytes) or len(data) > MAX_BYTES:
        raise ValueError('RECOVERY_DESCRIPTOR_BYTE_LIMIT')
    value = json.loads(data)
    files, links = value.get('files'), value.get('links', [])
    if not isinstance(files, list) or not isinstance(links, list) or len(files) < 6 \
            or len(files) + len(links) > MAX_ENTRIES:
        raise ValueError('RECOVERY_DESCRIPTOR_ENTRY_LIMIT')
    for row in files + links:
        path(row['path'])
    for row in links:
        path(row['target'])
    return value
