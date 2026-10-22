#!/usr/bin/env python3
"""Execute the production Gradle generation gate in isolated offline assemble fixtures."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
START = '// BEGIN DSHA_BUNDLED_DSH_VERSION_GATE'
END = '// END DSHA_BUNDLED_DSH_VERSION_GATE'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle-user-home', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    source = (ROOT / 'app/build.gradle').read_text(encoding='utf-8')
    if source.count(START) != 1 or source.count(END) != 1:
        raise ValueError('DSH_GRADLE_GATE_SOURCE_ANCHOR')
    gate = source.split(START, 1)[1].split(END, 1)[0]
    if not re.search(r"tasks\.named\('preBuild'\)\.configure\s*\{[^\n}]*dependsOn\([^\n}]*verifyBundledDshVersion", source):
        raise ValueError('DSH_GRADLE_PREBUILD_GATE_MISSING')
    properties = (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text(encoding='utf-8')
    version = re.search(r'gradle-([0-9.]+)-bin\.zip', properties).group(1)
    candidates = list((args.gradle_user_home / 'wrapper/dists' / ('gradle-' + version + '-bin')).glob('*/gradle-' + version + '/bin/gradle' + ('.bat' if os.name == 'nt' else '')))
    if len(candidates) != 1:
        raise ValueError('EXACT_LOCKED_GRADLE_REQUIRED')
    gradle = candidates[0]
    env = dict(os.environ, JAVA_HOME=str(args.jdk.resolve()), GRADLE_USER_HOME=str(args.gradle_user_home.resolve()))
    env['PATH'] = str(args.jdk.resolve() / 'bin') + os.pathsep + env.get('PATH', '')
    manifest = json.loads((ROOT / 'tools/dsh-runtime/package.json').read_text(encoding='utf-8'))
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf-8'))
    registry = json.loads((ROOT / 'app/src/main/assets/runtime-patches.json').read_text(encoding='utf-8'))
    native = (ROOT / 'app/src/main/java/com/deepseekharness/app/util/Constants.java').read_text(encoding='utf-8')
    expected = manifest['dependencies']['@deepseek-ai/dsh']
    output = args.output.resolve() if args.output else Path(tempfile.mkdtemp(prefix='dsha-gradle-version-', dir=ROOT / 'app/build'))
    output.mkdir(parents=True, exist_ok=True)
    results = []
    variants = ('current', 'native-old', 'native-expression', 'native-duplicate', 'registry-old', 'registry-schema', 'lock-root-old', 'lock-package-old')
    for case in variants:
        fixture = output / case
        fixture.mkdir()
        app = fixture / 'app'
        app.mkdir()
        values = dict(manifest=copy.deepcopy(manifest), lock=copy.deepcopy(lock), registry=copy.deepcopy(registry), native=native)
        if case == 'native-old':
            values['native'] = native.replace('DSH_VERSION = "' + expected + '";', 'DSH_VERSION = "0.2.0-rc.2";')
        elif case == 'native-expression':
            values['native'] = native.replace('DSH_VERSION = "' + expected + '";', 'DSH_VERSION = "0.2.1" + "-rc.2";')
        elif case == 'native-duplicate':
            values['native'] += '\npublic static final String DSH_VERSION = "' + expected + '";\n'
        elif case == 'registry-old':
            values['registry']['dshVersion'] = '0.2.0-rc.2'
        elif case == 'registry-schema':
            values['registry']['schema'] = 2
        elif case == 'lock-root-old':
            values['lock']['packages']['']['version'] = '0.2.0-rc.2'
        elif case == 'lock-package-old':
            values['lock']['packages']['node_modules/@deepseek-ai/dsh']['version'] = '0.2.0-rc.2'
        files = {'tools/dsh-runtime/package.json': json.dumps(values['manifest']),
                 'tools/dsh-runtime/package-lock.json': json.dumps(values['lock']),
                 'app/src/main/assets/runtime-patches.json': json.dumps(values['registry']),
                 'app/src/main/java/com/deepseekharness/app/util/Constants.java': values['native'],
                 'settings.gradle': "rootProject.name='native-version-fixture'\ninclude ':app'\n",
                 'app/build.gradle': gate + "\ntasks.register('assemble') { doLast { file('assembled.marker').text = 'assembled' } }\n"}
        for relative, body in files.items():
            target = fixture / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(body, encoding='utf-8', newline='\n')
        command = [str(gradle), '--offline', '--no-daemon', '--no-configuration-cache', '--console=plain', '-p', str(fixture), ':app:assemble']
        result = subprocess.run(command, env=env, cwd=ROOT, capture_output=True, timeout=90)
        (fixture / 'stdout.bin').write_bytes(result.stdout)
        (fixture / 'stderr.bin').write_bytes(result.stderr)
        combined = result.stdout + result.stderr
        if case == 'current':
            assert result.returncode == 0 and (app / 'assembled.marker').is_file(), combined.decode('utf-8', 'replace')
        else:
            assert result.returncode != 0 and not (app / 'assembled.marker').exists(), case
            error = b'BUNDLED_DSH_NATIVE_VERSION_ANCHOR' if case in ('native-expression', 'native-duplicate') else b'BUNDLED_DSH_VERSION_MISMATCH'
            assert error in combined, combined.decode('utf-8', 'replace')
        results.append(dict(case=case, status='PASS', exitCode=result.returncode,
                            stdoutSha256=hashlib.sha256(result.stdout).hexdigest(), stderrSha256=hashlib.sha256(result.stderr).hexdigest()))
        print(case + ': PASS', flush=True)
    receipt = dict(scope='Actual production Gradle gate executed at configuration before isolated assemble; no APK/Android/device or source mutation',
                   gradleVersion=version, gradle=str(gradle), sourceSha256=hashlib.sha256(source.encode()).hexdigest(), results=results)
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8', newline='\n')
    print('PASS production Gradle/native/lock/registry gate: ' + str(output / 'receipt.json'))


if __name__ == '__main__':
    main()
