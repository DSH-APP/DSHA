"""Select required device behaviors from actual changes against a sealed source baseline."""
import fnmatch
import hashlib
import json
from pathlib import Path
from release_layout import ROOT, source_snapshot_path

CONTRACT=Path(__file__).with_name('release-acceptance.json')
CERT='e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5'


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def baseline_from_receipt(path, root=ROOT):
    """A raw current snapshot is not a baseline: require a completed two-flavor delivery receipt."""
    path=Path(path).resolve()
    receipt=json.loads(path.read_text(encoding='utf8'))
    if receipt.get('status')!='PASS_FOR_EXECUTED_SCOPE':
        raise ValueError('ACCEPTANCE_BASELINE_NOT_DELIVERED')
    apks=receipt.get('apks',[])
    if len(apks)!=2 or {a.get('flavor') for a in apks}!={'standard','low'} \
            or any(a.get('certificateSha256')!=CERT or a.get('package')!='com.dsh.client' for a in apks):
        raise ValueError('ACCEPTANCE_BASELINE_IDENTITY')
    source=receipt.get('sourceSnapshot',{})
    snapshot=source_snapshot_path(source.get('path',''), source.get('sha256'), root)
    return json.loads(snapshot.read_text(encoding='utf8')),{'receiptSha256':digest(path),'sourceSha256':digest(snapshot)}


def requirements(baseline, current, contract=CONTRACT, provenance=None):
    rules=json.loads(Path(contract).read_text(encoding='utf8'))
    if rules.get('schema')!=1 or not isinstance(baseline,dict) or not isinstance(current,dict):
        raise ValueError('ACCEPTANCE_BASELINE_FORMAT')
    if not baseline or not current:
        raise ValueError('ACCEPTANCE_BASELINE_EMPTY')
    changed=sorted(name for name in set(baseline)|set(current) if baseline.get(name)!=current.get(name))
    selected={flavor:set(rules['baseDeviceChecks']) for flavor in ('standard','low')}
    matched=[]
    for rule in rules['changeRules']:
        hits=[name for name in changed if any(fnmatch.fnmatchcase(name,pattern) for pattern in rule['paths'])]
        if not hits:continue
        matched.append({'rule':rule['id'],'paths':hits})
        for flavor in rule['flavors']:selected[flavor].update(rule['deviceChecks'])
    return {'schema':1,'contractSha256':digest(contract),'baselineReceipt':provenance,'baselineSha256':hashlib.sha256(json.dumps(baseline,sort_keys=True).encode()).hexdigest(),
            'changed':changed,'rules':matched,'required':{k:sorted(v) for k,v in selected.items()}}


def validate(proof, selection):
    if not isinstance(proof,dict) or proof.get('acceptance')!=selection:
        raise ValueError('DEVICE_ACCEPTANCE_PLAN_MISMATCH')
    for flavor,checks in selection['required'].items():
        tested=proof.get('flavors',{}).get(flavor,{})
        behaviors=tested.get('behaviors',{})
        for check in checks:
            row=behaviors.get(check)
            if not isinstance(row,dict) or row.get('result')!='PASS' \
                    or not isinstance(row.get('evidence'),str) or not row['evidence'].strip():
                raise ValueError('DEVICE_ACCEPTANCE_MISSING:'+flavor+':'+check)
