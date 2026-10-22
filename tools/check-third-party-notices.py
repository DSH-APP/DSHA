"""Check current declarations and license bytes; never assert legal provenance."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tarfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('third_party_notices_generator',
                                             Path(__file__).with_name('generate-third-party-notices.py'))
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)
REQUIRED_LICENSES = ['proot-COPYING.txt', 'GPL-3.0.txt', 'LGPL-3.0.txt', 'MPL-2.0.txt',
                     'gson-LICENSE.txt', 'bouncycastle-LICENSE.txt', 'certifi-LICENSE.txt',
                     'Shizuku-MIT.txt', 'Termux-terminal-Apache-2.0.txt', 'snakeyaml-LICENSE.txt']


def verify_licenses(root=ROOT):
    for name in REQUIRED_LICENSES:
        path = root / 'app/src/main/assets/licenses' / name
        if not path.is_file() or path.stat().st_size < 100:
            raise ValueError('THIRD_PARTY_LICENSE_MISSING: ' + name)
    evidence = json.loads((root / 'docs/audits/build156/third-party-license-sources.json').read_text(encoding='utf-8'))
    for row in evidence['licenses']:
        if row['target'] not in {'app/src/main/assets/licenses/LGPL-3.0.txt',
                                 'app/src/main/assets/licenses/MPL-2.0.txt'}:
            raise ValueError('THIRD_PARTY_LICENSE_SOURCE_PATH')
        if hashlib.sha256((root / row['target']).read_bytes()).hexdigest() != row['sha256']:
            raise ValueError('THIRD_PARTY_LICENSE_CHANGED: ' + row['target'])
    if {row['target'] for row in evidence['licenses']} != {
            'app/src/main/assets/licenses/LGPL-3.0.txt', 'app/src/main/assets/licenses/MPL-2.0.txt'}:
        raise ValueError('THIRD_PARTY_LICENSE_SOURCE_MISSING')


def verify_native_members(root=ROOT):
    paths = generator.native_members(root)
    for path in paths:
        if Path(path).name not in generator.NATIVE_NOTICES:
            raise ValueError('THIRD_PARTY_NEW_NATIVE: ' + path)
    return paths


def inventory(archive, lock, notices, own_packages=None):
    expected = {}
    for path, row in lock['packages'].items():
        if path:
            name = path.rsplit('node_modules/', 1)[-1]
            expected.setdefault((name, row.get('version')), set()).add(row.get('license', 'unknown'))
    if own_packages is None:
        own_packages = [json.loads((ROOT / f'app/src/main/assets/{name}/package.json').read_text(encoding='utf-8'))
                        for name in ('runtime-fs', 'session-compat', 'client-combo-cache')]
    own = {(row['name'], row['version']): row for row in own_packages}
    for row in own_packages:
        expected.setdefault((row['name'], row['version']), set()).add(row.get('license', 'unknown'))
    packages = []
    with tarfile.open(archive, 'r:gz') as source:
        for member in source:
            if not member.isfile() or member.size > 1024 * 1024 \
                    or not re.search(r'/node_modules/(?:@[^/]+/)?[^/]+/package\.json$', member.name):
                continue
            value = json.load(source.extractfile(member))
            name, version = value.get('name'), value.get('version')
            license_name = value.get('license', 'unknown')
            if (name, version) not in expected or license_name not in expected[(name, version)]:
                raise ValueError('THIRD_PARTY_PACKAGE_DECLARATION: ' + member.name)
            if (name, version) in own and value != own[(name, version)]:
                raise ValueError('THIRD_PARTY_OWN_PACKAGE_CHANGED: ' + member.name)
            if (name, version) not in own and license_name not in generator.PERMISSIVE \
                    and not any('`' + path + '`' in notices and row.get('version') == version
                                and row.get('license', 'unknown') == license_name
                                for path, row in lock['packages'].items()
                                if path.rsplit('node_modules/', 1)[-1] == name):
                raise ValueError('THIRD_PARTY_RESTRICTED_NOTICE: ' + str(name))
            packages.append({'path': member.name, 'name': name, 'version': version,
                             'declaredLicense': license_name})
    if not packages:
        raise ValueError('THIRD_PARTY_PACKAGE_INVENTORY_EMPTY')
    return packages


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, help='Check the actually packaged npm tree after generation')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    notices = (ROOT / 'THIRD_PARTY_NOTICES.md').read_text(encoding='utf-8')
    if notices != generator.render():
        raise ValueError('THIRD_PARTY_NOTICES_STALE')
    verify_licenses()
    native = verify_native_members()
    for coordinate in generator.gradle_coordinates():
        if '`' + coordinate + '`' not in notices:
            raise ValueError('THIRD_PARTY_ANDROID_NOTICE: ' + coordinate)
    packages = inventory(args.archive,
                         json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf-8')),
                         notices) if args.archive else []
    report = {'schema': 1, 'nativeMembers': native, 'verifiedLicenseFiles': REQUIRED_LICENSES,
              'actualPackageDeclarations': packages,
              'legalOrigin': 'unknown_for_unproven_originals',
              'scope': 'Declarations, source-notice consistency and actual license text bytes; not complete redistribution/source-offer or transitive Gradle license compliance.'}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n',
                               encoding='utf-8', newline='\n')
    print('PASS current notices,', len(native), 'JNI identities,', len(packages),
          'actual npm declarations; original binary provenance and redistribution review remain separate')


if __name__ == '__main__':
    main()
