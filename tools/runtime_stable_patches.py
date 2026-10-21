"""Apply the signed active host patch chain while preparing a DSH archive.

Matches ManagedPatchChain/ExactTextPatch semantics. Specialized recipes remain
host-owned; no plugin install hook or dependency resolution executes here.
"""
import json
from pathlib import Path
from runtime_input_contract import relative


def load_chains(assets, version):
    assets = Path(assets)
    registry = json.loads((assets / 'runtime-patches.json').read_text(encoding='utf-8'))
    if registry.get('schema') != 1 or registry.get('dshVersion') != version:
        raise ValueError('STABLE_PATCH_REGISTRY_VERSION')
    chains, inputs, ids = {}, {'runtime-patches.json'}, set()
    for row in registry['active']:
        name = relative(row['asset'])
        if name in ids: raise ValueError('STABLE_PATCH_DUPLICATE: ' + name)
        ids.add(name)
        recipe = json.loads((assets / name).read_text(encoding='utf-8'))
        if recipe.get('dshVersion') != version:
            raise ValueError('STABLE_PATCH_VERSION: ' + name)
        module = relative(row.get('module', recipe.get('module')))
        inputs.add(name)
        patches = []
        for patch in recipe['patches']:
            before, after = patch['before'], patch['after']
            if 'prependAsset' in patch:
                prepend = relative(patch['prependAsset'])
                inputs.add(prepend)
                after = (assets / prepend).read_text(encoding='utf-8') + '\n' + after
            if not before or not after or before == after:
                raise ValueError('STABLE_PATCH_INVALID: ' + name)
            patches.append((before, after))
        markers = row.get('markers', [])
        if not isinstance(markers, list) or any(not isinstance(m, str) or not m for m in markers):
            raise ValueError('STABLE_PATCH_MARKERS: ' + name)
        chains.setdefault(module, []).append((name, patches, markers))
    if ids.intersection(row['asset'] for row in registry.get('retired', [])):
        raise ValueError('STABLE_PATCH_RETIRED_ACTIVE')
    return chains, sorted(inputs)


def apply(data, chain):
    text = data.decode('utf-8')
    for name, patches, markers in chain:
        if markers and all(marker in text for marker in markers): continue
        for before, after in patches:
            old, patched = text.count(before), text.count(after)
            if patched == 1 and old == after.count(before): continue
            if old != 1 or patched != 0:
                raise ValueError('STABLE_PATCH_ANCHOR: ' + name)
            text = text.replace(before, after)
    return text.encode('utf-8')
