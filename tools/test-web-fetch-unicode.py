#!/usr/bin/env python3
"""Exercise the signed web-fetch patch recipes against the actual bundled 0.2.2-rc.1 code."""
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
from runtime_stable_patches import load_chains, apply

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
PREFIX = 'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/'
MODULES = ['@deepseek-ai/dsh-web-fetch-http/lib/index.js', '@deepseek-ai/dsh-tool-web/lib/index.js']


def main():
    version = json.loads((ASSETS / 'runtime-patches.json').read_text(encoding='utf8'))['dshVersion']
    chains, inputs = load_chains(ASSETS, version)
    assert 'web-fetch-http-surrogate-patch.json' in inputs
    assert 'web-fetch-output-surrogate-patch.json' in inputs
    manifest = json.loads((ASSETS / 'managed-runtime-inputs.json').read_text(encoding='utf8'))
    assert all(name in manifest['assetFiles'] for name in inputs if name.startswith('web-fetch-'))
    bundled = {}
    with tarfile.open(ASSETS / 'dsh-runtime.bin', 'r|gz') as archive:
        for member in archive:
            name = member.name.removeprefix('./')
            if name in [PREFIX + module for module in MODULES]:
                assert member.isfile() and name not in bundled
                bundled[name] = archive.extractfile(member).read()
    with tempfile.TemporaryDirectory(prefix='dsha-web-fetch-test-') as temporary:
        paths = []
        for index, module in enumerate(MODULES):
            archived = bundled[PREFIX + module]
            chain = chains[module]
            patched = apply(archived, chain)
            # Reconstruct the exact upstream form for drift checks after a future archive rebuild.
            canonical = patched.decode('utf8')
            for _, parts, _ in reversed(chain):
                for before, after in reversed(parts):
                    assert canonical.count(after) == 1
                    canonical = canonical.replace(after, before, 1)
            raw = canonical.encode('utf8')
            assert apply(raw, chain) == patched, 'upstream and bundled upgrade must converge'
            assert apply(patched, chain) == patched, 'patch must be idempotent'
            # Every exact source anchor fails closed when drifted independently.
            for _, parts, _ in chain:
                for before, _ in parts:
                    assert raw.decode('utf8').count(before) == 1
                    drift = raw.decode('utf8').replace(before, '/* drifted */', 1).encode('utf8')
                    try:
                        apply(drift, chain)
                    except ValueError:
                        pass
                    else:
                        raise AssertionError('changed source anchor was accepted')
            path = Path(temporary) / f'{index}.js'
            path.write_bytes(patched)
            paths.append(str(path))
        subprocess.run(['node', str(ROOT / 'tools/test-web-fetch-unicode.mjs'), *paths], check=True, cwd=ROOT)
    print('PASS bundled 0.2.2-rc.1 exact anchors, idempotent managed recipes, drift rejection and executed Unicode boundaries')


if __name__ == '__main__':
    main()
