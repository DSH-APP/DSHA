#!/usr/bin/env python3
"""核对实际 APK 的应急归档、精确描述、工具 Profile 和本 flavor 的启动器。"""
from verification_require import require
import hashlib
import json
import sys
import zipfile
from pathlib import Path
from recovery_apk_assets import archive_locations
from recovery_runtime_overlay import TARGETS as BROWSER_TARGETS, INPUT_ASSETS

ROOT=Path(__file__).resolve().parents[1]


def hash_entry(apk,name):
    h=hashlib.sha256()
    with apk.open(name) as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()


def verify_overlays(apk,descriptor):
    overlays=descriptor.get('overlays')
    require((isinstance(overlays,list) and len(overlays)==len(BROWSER_TARGETS)), 'RECOVERY_OVERLAY_COUNT')
    file_proofs={row['path']:row for row in descriptor['files']}
    for row,(name,target) in zip(overlays,BROWSER_TARGETS.items()):
        require((row['path']==target and row['asset']=='recovery-overlay/'+name), 'RECOVERY_OVERLAY_PATH')
        require((row['sha256']==hash_entry(apk,'assets/'+row['asset'])), 'RECOVERY_OVERLAY_HASH')
        require((row['bytes']==apk.getinfo('assets/'+row['asset']).file_size), 'RECOVERY_OVERLAY_SIZE')
        require((file_proofs[target]['sha256']==row['sha256'] and file_proofs[target]['bytes']==row['bytes']), 'RECOVERY_OVERLAY_PROOF')
        require((len(row['originalSha256'])==64 and all(c in '0123456789abcdef' for c in row['originalSha256'])), 'RECOVERY_OVERLAY_SOURCE_HASH')


def verify_overlay_inputs(apk,descriptor):
    inputs=descriptor.get('overlayInputs')
    require((isinstance(inputs,dict) and set(inputs)==set(INPUT_ASSETS)), 'RECOVERY_OVERLAY_INPUTS')
    for name in INPUT_ASSETS:
        require((hash_entry(apk,'assets/'+name)==inputs[name]), 'RECOVERY_OVERLAY_INPUT_HASH')


def verify(path):
    lock=json.loads((ROOT/'tools/recovery-runtime/lock.json').read_text(encoding='utf8'))
    with zipfile.ZipFile(path) as apk:
        descriptor=json.loads(apk.read('assets/recovery-runtime.json'))
        identity=descriptor['id']
        require((identity==hashlib.sha256(json.dumps({k:v for k,v in descriptor.items() if k!='id'},sort_keys=True,separators=(',',':')).encode()).hexdigest()), 'RECOVERY_ID')
        require((descriptor['schema']==1 and descriptor['dshVersion']==lock['dshVersion']), 'RECOVERY_VERSION')
        require((descriptor['archives']==[{k:row[k] for k in ('asset','sha256')} for row in lock['archives']]), 'RECOVERY_LOCK')
        sources=archive_locations(apk,descriptor,require_locations=True)
        require((hash_entry(apk,'assets/recovery-agent.js')==descriptor['agentSha256']), 'RECOVERY_AGENT')
        for name,sha in descriptor['profileHashes'].items():
            require((hash_entry(apk,'assets/'+name)==sha), 'RECOVERY_PROFILE')
        verify_overlays(apk,descriptor)
        verify_overlay_inputs(apk,descriptor)
        flavor='low' if 'lib/arm64-v8a/libproot_legacy.so' in apk.namelist() else 'standard'
        for name,sha in descriptor['launcherHashes'][flavor].items():
            require((hash_entry(apk,'lib/arm64-v8a/'+name)==sha), 'RECOVERY_LAUNCHER')
        for name in ('recovery-web-integration/manifest.json',
                     'recovery-web-integration/locale-relay.js'):
            source=ROOT/'app/src/main/assets'/name
            require((source.is_file() and hash_entry(apk,'assets/'+name)==hashlib.sha256(source.read_bytes()).hexdigest()), 'RECOVERY_WEB_INTEGRATION')
        require((len(descriptor['files'])>100 and descriptor['expandedBytes']==sum(row['bytes'] for row in descriptor['files'])), 'RECOVERY_MANIFEST')
        print(json.dumps(dict(apk=str(path),flavor=flavor,recoveryId=identity,dshVersion=descriptor['dshVersion'],files=len(descriptor['files']),archiveSources=sources,result='PASS'),ensure_ascii=False))


if __name__=='__main__':
    if len(sys.argv)<2:raise SystemExit('usage: verify-recovery-apk.py APK [APK...]')
    for path in sys.argv[1:]:verify(Path(path))
