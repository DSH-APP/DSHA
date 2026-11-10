#!/usr/bin/env python3
"""独立运行时描述：APK UI 版本仅作诊断，不参与受管运行时身份。"""
from source_text import write_text as write_source_text, matches_text
import hashlib
import json
from pathlib import Path
import re
import sys
from runtime_input_contract import load, asset_paths, launcher_paths, relative

ROOT = Path(__file__).resolve().parents[1]

# The descriptor uses explicit data-format evidence rather than deriving
# compatibility from a version number.  DSH 0.2.0-rc.2 keeps the same session
# and JSON storage readers as the shipped 0.1.7-rc.2 runtime (both runtimes
# expose the v4 session catalog and identical storage-json implementation), so
# an upgrade may read the previous runtime's records.  Keep this list narrow:
# a future runtime must add a reviewed entry here after comparing its readers.
READ_COMPATIBILITY = {
    '0.2.0-rc.2': ['dsh-0.1.7-rc.1', 'dsh-0.1.7-rc.2'],
    # build190: verified old storage and V4 none/zstd logs with the real new readers.
    # This is forward reading only; it does not authorize an old runtime rollback.
    '0.2.1-rc.2': ['dsh-0.1.7-rc.1', 'dsh-0.1.7-rc.2', 'dsh-0.2.0-rc.2'],
    # build193: the 75 actual data/config modules and four persistence helpers
    # match shipped build192 after the real rc3 builder/registry projection;
    # real API cross-write/reopen checks cover the direct rc2 -> rc3 transition.
    # This only declares forward reading. Complete-format status stays unknown,
    # and rollback still requires the archive/config fingerprint and RuntimeTrial.
    # See docs/runtime-data-compatibility-rc3-20261024.md. Do not infer older edges.
    '0.2.1-rc.3': ['dsh-0.2.1-rc.2'],
    # build213 (upstream dsh-v0.2.1-rc.5): all 31 data/storage reader packages
    # (75 JS modules) are byte-identical between the shipped rc3 archive and the
    # rc5 builder projection; the real rc3 -> rc5 API cross read/write/reopen
    # test passed. This is forward reading only; no rollback edge is declared.
    # See docs/runtime-data-compatibility-rc5.md. Do not infer older edges.
    '0.2.1-rc.5': ['dsh-0.2.1-rc.3'],
    # T55 (upstream dsh-v0.2.2-rc.1): unlike rc5, 14 of the 31 reader packages
    # changed bytes (storage-json, storage-domain, session-persistence-jsonl,
    # settings, app-boot ...). Evidence is NOT byte identity: the real
    # rc5-managed -> 0.2.2 (builder-patched data modules) API cross
    # write/read/reopen test passed all five phases. Forward reading only.
    # Known one-way writes: schedule unit v1 -> v2 on first write, app-boot
    # profile package.json normalization. See
    # docs/runtime-data-compatibility-0.2.2-rc.1.md. Do not infer older edges.
    '0.2.2-rc.1': ['dsh-0.2.1-rc.5'],
}

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(data)
    return value.hexdigest()

def validate_installs(root, spec):
    """Managed release archives may install exactly one pinned, digested member.

    This stays here (not in runtime_input_contract.load) because that module is a
    recipe input of the fixed dsh-runtime archive; a gh-only rule must not force a
    dsh runtime rebuild. The installer repeats the same checks natively.
    """
    for row in spec['installs']:
        extract = row.get('extract')
        if extract is None:
            continue
        if not isinstance(extract, dict) or set(extract) != {'member', 'sha256'}:
            raise ValueError('RUNTIME_INSTALL_EXTRACT')
        if not relative(row['asset']).endswith('.tar.gz'):
            raise ValueError('RUNTIME_INSTALL_EXTRACT')
        relative(extract['member'])
        if not isinstance(extract['sha256'], str) or not re.fullmatch(r'[a-f0-9]{64}', extract['sha256']):
            raise ValueError('RUNTIME_INSTALL_EXTRACT')


def build_descriptor(root):
    root = Path(root)
    assets = root / 'app/src/main/assets'
    spec = load(root)
    validate_installs(root, spec)
    inputs = {p.relative_to(assets).as_posix(): digest(p) for p in asset_paths(root, spec)}
    launchers = {name: digest(path) for name, path in launcher_paths(root, spec).items()}
    version = json.loads((root / 'tools/dsh-runtime/package.json').read_text(encoding='utf8'))['dependencies']['@deepseek-ai/dsh']
    previous = READ_COMPATIBILITY.get(version, [])
    contract = {'baseVersion': (assets / 'offline-rootfs.version').read_text().strip(),
                'dshVersion': version, 'launcherContract': 'DSHA_ARM64_V2',
                'launcherInputs': launchers, 'bridgeProtocol': 3,
                'dataRead': previous + ['dsh-' + version], 'dataWrite': 'dsh-' + version,
                'inputs': inputs}
    identity = hashlib.sha256(json.dumps(contract, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    return dict(version=1, runtimeId=identity, **contract)

def main(args):
    output = build_descriptor(ROOT)
    target = ROOT / 'app/src/main/assets/runtime-descriptor.json'
    content = json.dumps(output, ensure_ascii=False, indent=2) + '\n'
    if args == ['--check']:
        if not matches_text(target,content,encoding='utf8'):
            raise SystemExit('运行时描述符与当前受管输入不一致；先运行 tools/prepare-runtime-descriptor.py --write 并审阅改动')
    elif args in ([], ['--write']):
        write_source_text(target,content,encoding='utf8')
    else:
        raise SystemExit('usage: prepare-runtime-descriptor.py [--check|--write]')
    print('runtime descriptor:', output['runtimeId'])

if __name__ == '__main__':
    main(sys.argv[1:])
