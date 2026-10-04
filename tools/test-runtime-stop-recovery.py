#!/usr/bin/env python3
"""Current stop/recovery algorithms with explicit kernel outcomes and real JVM private files.

The supplied existing Android/resource classes are compilation dependencies, not evidence
of a current full-source build or of Android fd/signal behavior; the release gate owns that.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--gradle-home', type=Path, required=True)
    parser.add_argument('--flavor', choices=('standard', 'low'), default='standard')
    args = parser.parse_args()
    if sys.flags.optimize:
        parser.error('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    cache = args.gradle_home / 'caches'
    capital = args.flavor.capitalize()
    compiled = ROOT / f'app/build/intermediates/javac/{args.flavor}Debug/compile{capital}DebugJavaWithJavac/classes'
    resources = list((ROOT / 'app/build/intermediates').glob(f'compile*_r_class_jar/{args.flavor}Debug/**/R.jar'))
    if not compiled.is_dir() or not resources:
        raise RuntimeError('EXPLICIT_ANDROID_COMPILATION_DEPENDENCY_REQUIRED')
    cp = [args.sdk / 'platforms/android-37.0/android.jar', compiled, resources[0]]
    cp.extend(sorted(file for file in (cache / '9.3.1/transforms').rglob('classes.jar') if 'instrumented' not in file.parts))
    cp.extend(sorted(file for file in (cache / 'modules-2/files-2.1').rglob('*.jar')
                     if 'sources' not in file.name and 'javadoc' not in file.name))
    spec = importlib.util.spec_from_file_location('fallback_args', ROOT / 'tools/run-unit-tests.py')
    fallback = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fallback)
    locked, coordinates = fallback.locked_backup_classpath(args.gradle_home)
    cp = [Path(path) for path in locked] + [path for path in cp
          if not any(group in path.parts and name in path.parts for group, name in coordinates)]
    main_root, test_root = [ROOT / ('app/src/' + source + '/java/com/deepseekharness/app') for source in ('main', 'test')]
    # Runtime facades and their pure policies are rebuilt together. A historical compiled
    # ProotBootstrap/RuntimeHostPorts API must not substitute for this source generation.
    files = sorted((main_root / 'runtime').glob('*.java'))
    files += sorted((main_root / 'util').glob('*.java'))
    files += [main_root / 'backup' / name for name in (
        'BackupFileSystem.java', 'BackupLimits.java', 'BackupJson.java', 'RuntimeTrialRecords.java')]
    files += [test_root / 'backup/JvmBackupFileSystem.java',
              test_root / 'runtime/WebRecordedStopTest.java', test_root / 'runtime/TrialRecoveryTest.java',
              test_root / 'util/WebStopDiagnosticTest.java', test_root / 'runtime/WebStopResultTest.java']
    suffix = '.exe' if os.name == 'nt' else ''
    with tempfile.TemporaryDirectory(prefix='dsha-current-stop-recovery-') as folder:
        work = Path(folder)
        generated = work / 'generated'
        subprocess.run([sys.executable, '-B', str(ROOT / 'tools/prepare-ui-languages.py'),
                        '--output', str(generated)], check=True, timeout=30)
        files.extend(generated.rglob('*.java'))
        classpath = os.pathsep.join(map(str, cp))
        argument_file = work / 'javac.args'
        fallback.write_argfile(argument_file, ['-encoding', 'UTF-8', '--release', '17',
                               '-cp', classpath, '-d', str(work / 'classes'), *map(str, files)])
        subprocess.run([str(args.jdk / ('bin/javac' + suffix)), '-J-Dfile.encoding=UTF-8',
                        '@' + str(argument_file)], check=True, timeout=90)
        runtime_arguments = work / 'java.args'
        fallback.write_argfile(runtime_arguments, ['-Dfile.encoding=UTF-8', '-cp',
            str(work / 'classes') + os.pathsep + classpath, 'org.junit.runner.JUnitCore',
            'com.deepseekharness.app.runtime.WebRecordedStopTest',
            'com.deepseekharness.app.runtime.TrialRecoveryTest',
            'com.deepseekharness.app.util.WebStopDiagnosticTest',
            'com.deepseekharness.app.runtime.WebStopResultTest'],
            encoding='mbcs' if os.name == 'nt' else 'utf-8')
        subprocess.run([str(args.jdk / ('bin/java' + suffix)), '@' + str(runtime_arguments)],
                       check=True, timeout=60)
    print('PASS current SIGTERM/identity/wait algorithm and pending-trial recovery; platform adapter results are explicit fixtures, no Android signal or process operation')


if __name__ == '__main__':
    main()
