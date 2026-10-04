#!/usr/bin/env python3
"""Current bridge/file policy JVM behavior; no Gradle, phone, shell process or APK."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TESTS = [
    'util.DeviceFileOperationsTest', 'util.LanTokenStateTest', 'LanAuthTest',
    'util.DeviceShellPolicyTest', 'util.DeviceAppPolicyTest', 'util.SelfPackageIdentityTest',
    'util.BridgeCredentialAuthTest', 'util.ScreenTargetTest', 'util.ScreenSessionGrantTest',
    'util.VscreenBridgeRequestTest', 'util.VirtualScreenRequestFenceTest',
    'util.TouchGestureDispatchTest', 'util.BridgeTemplateFormattingTest',
]


def jar(cache, group, name, version):
    matches = sorted((cache / 'caches/modules-2/files-2.1' / group / name / version).glob('**/*.jar'))
    if len(matches) != 1:
        raise RuntimeError('EXACT_JVM_TEST_DEPENDENCY_REQUIRED:' + name)
    return matches[0]


def main():
    if sys.flags.optimize:
        raise RuntimeError('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', required=True)
    parser.add_argument('--gradle-home', required=True)
    args = parser.parse_args()
    java_root = Path(args.jdk).resolve() / 'bin'
    java = java_root / ('java.exe' if os.name == 'nt' else 'java')
    javac = java_root / ('javac.exe' if os.name == 'nt' else 'javac')
    cache = Path(args.gradle_home).resolve()
    dependencies = [jar(cache, 'junit', 'junit', '4.13.2'), jar(cache, 'org.hamcrest', 'hamcrest-core', '1.3'),
                    jar(cache, 'com.google.code.gson', 'gson', '2.13.1')]
    cp = os.pathsep.join(map(str, dependencies))
    with tempfile.TemporaryDirectory(prefix='dsha-current-bridge-files-') as folder:
        work = Path(folder)
        generated = work / 'generated'
        subprocess.run([sys.executable, '-B', str(ROOT / 'tools/prepare-ui-languages.py'),
                        '--output', str(generated)], check=True, timeout=30)
        test_root = ROOT / 'app/src/test/java/com/deepseekharness/app'
        sources = [test_root / (name.replace('.', '/') + '.java') for name in TESTS]
        sources.extend(generated.rglob('*.java'))
        classes = work / 'classes'
        command = [str(javac), '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '--release', '17',
                   '-cp', cp, '-d', str(classes), '-sourcepath',
                   os.pathsep.join(map(str, [ROOT / 'app/src/main/java', ROOT / 'app/src/test/java', generated]))]
        subprocess.run(command + list(map(str, sources)), check=True, timeout=90)
        subprocess.run([str(java), '-Dfile.encoding=UTF-8', '-cp', str(classes) + os.pathsep + cp,
                        'org.junit.runner.JUnitCore', *['com.deepseekharness.app.' + name for name in TESTS]],
                       check=True, timeout=60)
    print('PASS current bridge/file JVM behaviors. Descriptor fixture models the platform handle contract; Android/FUSE/installed app_process are not exercised.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
