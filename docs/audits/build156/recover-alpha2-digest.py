"""Bind the published historical alpha2 APK to independently fetched evidence.

Reads the actual artifact and saved HTTPS responses. Does not rewrite historical
documents, production inputs, APKs, or any device state.
"""
from pathlib import Path
import datetime
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
RAW = ROOT / 'app/build/audit-build156/historical-digest-recovery'

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def ref(path):
    return {'path': path.relative_to(ROOT).as_posix(), 'bytes': path.stat().st_size, 'sha256': sha(path)}

def run():
    response = RAW / 'github-alpha2-release.json'
    release = json.loads(response.read_text(encoding='utf8'))
    assert release['tag_name'] == 'v0.1.7-alpha2' and release['id'] == 394683261
    assert release['html_url'] == 'https://github.com/DSH-APP/DSHA/releases/tag/v0.1.7-alpha2'
    name = 'dsha-0.1.7-alpha2.apk'
    apk = ROOT / 'release' / name
    local = ROOT / 'release' / (name + '.sha256')
    remote = RAW / 'github-alpha2-standard.apk.sha256'
    assets = {entry['name']: entry for entry in release['assets']}
    artifact, sidecar = assets[name], assets[name + '.sha256']
    digest = sha(apk)
    assert apk.stat().st_size == artifact['size'] == 272933762
    assert artifact['digest'] == 'sha256:' + digest
    assert local.read_bytes() == remote.read_bytes()
    assert remote.read_text(encoding='ascii').split() == [digest, name]
    assert sidecar['size'] == remote.stat().st_size == 89
    assert sidecar['digest'] == 'sha256:' + sha(remote)
    assert artifact['browser_download_url'] == release['html_url'].replace('/tag/', '/download/') + '/' + name
    assert sidecar['browser_download_url'] == artifact['browser_download_url'] + '.sha256'
    signing = RAW / 'alpha2-standard-signature.txt'
    signature = signing.read_text(encoding='utf-8-sig')
    cert = json.loads((ROOT / 'ci/release-identity.json').read_text(encoding='utf8'))['certificateSha256']
    assert re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)', signature) == [cert]
    assert all(re.search(r'Verified using ' + scheme + r' scheme[^\n]+true', signature) for scheme in ('v1', 'v2', 'v3'))
    badging_path = RAW / 'alpha2-standard-manifest.txt'
    badging = badging_path.read_text(encoding='utf-8-sig')
    assert "package: name='com.dsh.client' versionCode='145' versionName='0.1.7-alpha2'" in badging
    assert 'application-debuggable' not in badging
    old = json.loads((ROOT / 'docs/audits/build154/historical-invalid-digests.json').read_text(encoding='utf8'))
    invalid = next(row['invalidRecordedValue'] for row in old['items'] if row['path'].endswith('alpha2-pre-release.md'))
    assert len(invalid) == 63 and len(digest) == 64
    positions = [i + 1 for i in range(64) if digest[:i] + digest[i + 1:] == invalid]
    assert positions == [8]
    result = {
        'schemaVersion': 1,
        'status': 'RECOVERED_FROM_ACTUAL_APK_AND_OFFICIAL_PUBLISHED_ASSETS',
        'checkedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'scope': 'Historical alpha2 Standard only; no guessed replacement and no change to current build156 software or release files.',
        'historicalDocument': 'docs/releases/v0.1.7-alpha2-pre-release.md',
        'invalidOriginalPreserved': invalid,
        'recoveredSha256': digest,
        'actualApk': ref(apk),
        'package': 'com.dsh.client', 'versionCode': 145, 'versionName': '0.1.7-alpha2',
        'debuggable': False, 'certificateSha256': cert,
        'signatureSchemesVerified': ['v1', 'v2', 'v3'],
        'sources': {
            'releaseUrl': release['html_url'],
            'metadataUrl': 'https://api.github.com/repos/DSH-APP/DSHA/releases/tags/v0.1.7-alpha2',
            'releaseId': release['id'], 'publishedAt': release['published_at'],
            'artifact': {key: artifact[key] for key in ('id', 'name', 'size', 'digest', 'browser_download_url', 'created_at', 'updated_at')},
            'sidecar': {key: sidecar[key] for key in ('id', 'name', 'size', 'digest', 'browser_download_url', 'created_at', 'updated_at')},
        },
        'checks': {
            'actualApkMatchesOfficialAssetDigestAndSize': True,
            'fetchedOfficialSidecarMatchesLocalSidecarBytes': True,
            'fetchedSidecarMatchesOfficialSidecarAssetDigestAndSize': True,
            'oldInvalidValueMatchesOnlyOneDeletedHexCharacter': True,
            'deletedCharacterOneBasedPosition': positions[0],
            'digestWasCalculatedFromArtifactBeforeComparingTypo': True,
        },
        'evidence': [ref(path) for path in (response, remote, local, signing, badging_path)],
        'unresolved': [{
            'document': 'docs/releases/v0.1.7-alpha1-build143.md',
            'reason': 'The available version143 Standard artifact has a different complete digest from the invalid historical alpha1 record. It is not substituted for that original candidate.',
        }],
    }
    (HERE / 'historical-alpha2-digest-recovery.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    print('PASS historical alpha2: actual APK, official metadata, official sidecar, local sidecar and E7E3 identity agree; alpha1 remains unresolved')

if __name__ == '__main__':
    run()
