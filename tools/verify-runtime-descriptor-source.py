"""Check every available descriptor byte without requiring ignored offline archives."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
from runtime_input_contract import load, launcher_paths, ASSETS

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            value.update(block)
    return value.hexdigest()


def archive_inputs(root):
    spec = importlib.util.spec_from_file_location('descriptor_ci_assets', root / 'tools/ci-assets.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    # Only the named archive bytes may be absent in a clean source checkout.
    return {name for name in module.NAMES if name.endswith(('.bin', '.tar.gz'))}


def verify(root=ROOT, deferred_archives=None):
    root = Path(root)
    assets = root / ASSETS
    spec = load(root)
    descriptor = json.loads((assets / 'runtime-descriptor.json').read_text(encoding='utf-8'))
    fields = ('baseVersion', 'dshVersion', 'launcherContract', 'launcherInputs',
              'bridgeProtocol', 'dataRead', 'dataWrite', 'inputs')
    contract = {name: descriptor[name] for name in fields}
    identity = hashlib.sha256(json.dumps(contract, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    if descriptor.get('version') != 1 or identity != descriptor.get('runtimeId'):
        raise ValueError('DESCRIPTOR_SOURCE_IDENTITY')
    package = json.loads((root / 'tools/dsh-runtime/package.json').read_text(encoding='utf-8'))
    if descriptor['dshVersion'] != package['dependencies']['@deepseek-ai/dsh'] \
            or descriptor['baseVersion'] != (assets / 'offline-rootfs.version').read_text().strip():
        raise ValueError('DESCRIPTOR_SOURCE_VERSION')
    expected = {'managed-runtime-inputs.json', *spec['assetFiles'],
                *(row['asset'] for row in spec['installs'])}
    for name in spec['assetTrees']:
        directory = assets / name
        if not directory.is_dir() or directory.is_symlink():
            raise ValueError('DESCRIPTOR_SOURCE_TREE: ' + name)
        for path in directory.rglob('*'):
            if path.is_file():
                expected.add(path.relative_to(assets).as_posix())
    if set(descriptor.get('inputs', {})) != expected:
        raise ValueError('DESCRIPTOR_SOURCE_MEMBER_SET')
    allowed = archive_inputs(root) if deferred_archives is None else set(deferred_archives)
    deferred = []
    checked = 0
    for name, claimed in descriptor['inputs'].items():
        if not re.fullmatch('[a-f0-9]{64}', str(claimed)):
            raise ValueError('DESCRIPTOR_SOURCE_DIGEST: ' + name)
        path = assets / name
        if not path.is_file() and name in allowed:
            deferred.append(name)
            continue
        if not path.is_file() or not path.resolve().is_relative_to(assets.resolve()) \
                or digest(path) != claimed:
            raise ValueError('DESCRIPTOR_SOURCE_BYTES: ' + name)
        checked += 1
    launchers = launcher_paths(root, spec)
    if set(descriptor.get('launcherInputs', {})) != set(launchers):
        raise ValueError('DESCRIPTOR_LAUNCHER_MEMBER_SET')
    for name, path in launchers.items():
        if digest(path) != descriptor['launcherInputs'][name]:
            raise ValueError('DESCRIPTOR_LAUNCHER_BYTES: ' + name)
    return {'checkedAssets': checked, 'checkedLaunchers': len(launchers),
            'deferredArchives': sorted(deferred)}


if __name__ == '__main__':
    print(json.dumps(verify(), sort_keys=True))
