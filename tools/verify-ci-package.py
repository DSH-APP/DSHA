"""Bind a downloaded successful package run, its source and both unsigned APKs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REQUIRED_CHECKS = {
    'gradle-verification', 'runtime-input-contract', 'release-acceptance-contract',
    'apk-assets', 'standard-elf', 'low-elf', 'plugin-upgrade-gate',
    'recovery-apk', 'recovery-browser-overlay', 'recovery-profile-boot',
    'mobile-modal', 'adb-flow', 'adb-vscreen-bridge', 'web-ui-host-fixtures',
    'plugin-downloads',
    'third-party-notices', 'third-party-apk',
}


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def validate_run(run, repository, commit):
    if run.get('repository', {}).get('full_name') != repository \
            or run.get('head_repository', {}).get('full_name') != repository \
            or run.get('head_sha') != commit or run.get('status') != 'completed' \
            or run.get('conclusion') != 'success' or run.get('event') != 'workflow_dispatch' \
            or run.get('path') != '.github/workflows/ci-package.yml':
        raise ValueError('PACKAGE_RUN_IDENTITY')


def checked_member(path, artifacts):
    base = artifacts.resolve()
    if not path.is_file() or not path.resolve().is_relative_to(base):
        raise ValueError('PACKAGE_ARTIFACT_PATH')
    for item in [path, *path.parents]:
        if item == artifacts:
            break
        if item.is_symlink() or (hasattr(item, 'is_junction') and item.is_junction()):
            raise ValueError('PACKAGE_ARTIFACT_LINK')
    return path


def one_member(artifacts, filename):
    candidates = list(artifacts.rglob(filename))
    if len(candidates) != 1:
        raise ValueError('PACKAGE_ARTIFACT_COUNT: ' + filename)
    return checked_member(candidates[0], artifacts)


def source_names(source):
    names = subprocess.check_output(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=source
    ).decode('utf-8').split('\0')
    selected = set()
    for name in names:
        parts = name.split('/')
        if name and 'history' not in parts and 'private-device-audits' not in parts \
                and (name.startswith(('app/src/', 'tools/', 'gradle/', 'ci/', '.github/',
                                      'agent-skills/', 'website/'))
                     or name in ('app/build.gradle', 'build.gradle', 'settings.gradle',
                                 'gradle.properties', 'build.sh', 'gradlew', 'gradlew.bat')) \
                and (source / name).is_file():
            selected.add(name)
    return selected


def permitted_offline_inputs(source):
    # Missing source must never be hidden as an arbitrary "offline" input.
    # The input transfer tool names the only archives absent from a clean checkout.
    import importlib.util
    spec = importlib.util.spec_from_file_location('ci_assets', source / 'tools/ci-assets.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.allowed()


def select_manifest(artifacts):
    manifests = [p for p in artifacts.rglob('manifest.json')
                 if 'stability-acceptance' in p.relative_to(artifacts).parts[:-1]]
    if len(manifests) != 1:
        raise ValueError('PACKAGE_MANIFEST_COUNT')
    return checked_member(manifests[0], artifacts)


def validate_receipt(receipt, artifacts, commit, source=ROOT, expected_sources=None,
                     offline_inputs=None):
    if receipt.get('verificationSchema') != 2 \
            or receipt.get('status') != 'PASS_UNSIGNED_PACKAGE' or receipt.get('commit') != commit \
            or receipt.get('dirty') is not False:
        raise ValueError('PACKAGE_RECEIPT_IDENTITY')
    snapshot = one_member(artifacts, 'source-sha256.json')
    if digest(snapshot) != receipt.get('sourceSnapshot', {}).get('sha256'):
        raise ValueError('PACKAGE_SOURCE_RECEIPT')
    captured = json.loads(snapshot.read_text(encoding='utf-8'))
    if not isinstance(captured, dict) or not captured:
        raise ValueError('PACKAGE_SOURCE_RECEIPT')
    expected_sources = source_names(source) if expected_sources is None else set(expected_sources)
    offline_inputs = permitted_offline_inputs(source) if offline_inputs is None else set(offline_inputs)
    if not expected_sources.issubset(captured):
        raise ValueError('PACKAGE_SOURCE_INCOMPLETE')
    # Offline binary inputs are bound by the successful package receipt.
    # Available checked-out sources must match before signing.
    for name, expected in captured.items():
        path = source / name
        if not isinstance(name, str) or not name or ':' in name or '\\' in name \
                or any(part in ('', '.', '..') for part in name.split('/')) \
                or not re.fullmatch('[a-f0-9]{64}', str(expected)):
            raise ValueError('PACKAGE_SOURCE_PATH')
        if not path.is_file() and name not in offline_inputs:
            raise ValueError('PACKAGE_SOURCE_MISSING: ' + name)
        if path.is_file() and (not path.resolve().is_relative_to(source.resolve())
                               or digest(path) != expected):
            raise ValueError('PACKAGE_SOURCE_CHANGED: ' + name)
    rows = receipt.get('apks', [])
    if len(rows) != 2 or {row.get('flavor') for row in rows} != {'standard', 'low'}:
        raise ValueError('PACKAGE_FLAVORS')
    commands = receipt.get('commands', [])
    if not commands or any(row.get('exitCode') != 0 for row in commands):
        raise ValueError('PACKAGE_FAILED_CHECK')
    names = [row.get('name') for row in commands]
    if any(not isinstance(name, str) or not re.fullmatch('[a-z0-9-]+', name) for name in names) \
            or len(set(names)) != len(names) or not REQUIRED_CHECKS.issubset(names):
        raise ValueError('PACKAGE_CHECK_INCOMPLETE')
    for row in commands:
        log = one_member(artifacts, row['name'] + '.log')
        if digest(log) != row.get('sha256'):
            raise ValueError('PACKAGE_CHECK_CHANGED: ' + row['name'])
    for flavor in ('Standard', 'Low'):
        counts = receipt.get('junit', {}).get(flavor, {})
        if counts.get('tests', 0) <= counts.get('skipped', 0) \
                or counts.get('failures') != 0 or counts.get('errors') != 0:
            raise ValueError('PACKAGE_JUNIT_INCOMPLETE: ' + flavor)
    selected = []
    for flavor in ('standard', 'low'):
        row = next(row for row in rows if row['flavor'] == flavor)
        candidate = one_member(artifacts, 'app-' + flavor + '-release-unsigned.apk')
        if digest(candidate) != row.get('sha256') \
                or row.get('certificateSha256') is not None:
            raise ValueError('PACKAGE_APK_CHANGED: ' + flavor)
        selected.append(candidate)
    return selected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--artifacts', type=Path, required=True)
    args = parser.parse_args()
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    validate_run(json.loads(args.run.read_text()), os.environ['GITHUB_REPOSITORY'], commit)
    manifest = select_manifest(args.artifacts)
    selected = validate_receipt(json.loads(manifest.read_text(encoding='utf-8')),
                                args.artifacts, commit)
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        for flavor, apk in zip(('standard', 'low'), selected):
            output.write(flavor + '=' + str(apk.resolve()) + '\n')
    print('PASS successful package run, same commit, source receipt and two APK digests')


if __name__ == '__main__':
    main()
