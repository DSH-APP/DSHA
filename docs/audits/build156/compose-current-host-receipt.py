"""Compose unchanged host coverage with the five actually rerun source readers."""
from pathlib import Path
import hashlib
import importlib.util
import json

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent

def read(path):
    return json.loads(path.read_text(encoding='utf8'))

def ref(path):
    return {'path': path.relative_to(ROOT).as_posix(), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}

original = ROOT / 'app/build/audit-build156/host-workspace-final/manifest.json'
supplement = HERE / 'host-connected-label-delta-evidence/affected-host-manifest.json'
impact = HERE / 'host-connected-label-delta-evidence/host112-input-impact.json'
delta = HERE / 'host-connected-label-delta-evidence/receipt.json'
software_path = ROOT / 'app/build/stability-acceptance/5e2d5521-beb5-4775-b87e-85a074b4aade/manifest.json'
software = read(software_path)
original_rows = read(original)['tests']
new_rows = read(supplement)['tests']
assert len(original_rows) == 112 and len(new_rows) == 5
assert all(row['status'] == 'PASS' for row in original_rows + new_rows)
assert {row['path'] for row in new_rows} <= {row['path'] for row in original_rows}
assert read(delta)['originalHost112']['sha256'] == ref(original)['sha256']
assert read(delta)['latestSoftware']['sha256'] == ref(software_path)['sha256']
body = {
    'schema': 1,
    'status': 'PASS_COMPLETE_ACTIVE_HOST_MANIFEST',
    'scope': '112 declared entry points: original successful execution retained; five readers of the sole changed Java source actually rerun. Not a new112-entry run.',
    'reports': [ref(original), ref(supplement)],
    'latestSoftware': ref(software_path),
    'sourceSnapshot': software['sourceSnapshot'],
    'inputImpactReview': ref(impact),
    'affectedCheckAndAdditionalSourceGateReceipt': ref(delta),
    'uniqueDeclaredEntryPoints': 112,
    'entriesActuallyRerunAfterSourceDelta': 5,
    'unchangedSelectedInputEntriesReused': 107,
    'additionalFullTreeSourceGates': 2,
    'packageHostEntries': 0,
    'packageValidation': 'Actual APK/ELF/signature gates belong to the linked completed software receipt.',
    'limitations': 'The source-reader review is not dynamic all-I/O tracing; no old compiled classpath byte equality or full device matrix is inferred.',
}
target = HERE / 'host-current-composite.json'
if target.exists():
    raise SystemExit('Refusing to overwrite an already composed receipt')
target.write_text(json.dumps(body, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
spec = importlib.util.spec_from_file_location('readonly_host_finalizer156', HERE / 'finalize-ledger.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
verifier = module.Verify(ROOT)
snapshot = read(Path(software['sourceSnapshot']['path']))
summary = module.validate_host(verifier, target, snapshot)
assert summary['tests'] == 112
for path, expected in verifier.observed.items():
    assert module.sha(path) == expected
print(json.dumps({'status': 'PASS_READONLY_HOST_VALIDATOR', 'uniqueEntries': summary['tests'], 'rerun': 5, 'reusedUnchangedSelectedInputs': 107, 'receipt': ref(target)}, ensure_ascii=False))
