#!/usr/bin/env python3
"""Recompile current physical-screen owners and run controlled Android facts offline.

The dependency reference supplies cached external JARs only. No application or test
classes from that reference are loaded. This does not run a phone or build an APK.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dependency-reference', type=Path, required=True)
    parser.add_argument('--production-classes', type=Path, default=ROOT / 'app/build/intermediates/javac/standardDebug/compileStandardDebugJavaWithJavac/classes')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--android-jar', type=Path, help='Current compile SDK android.jar, before cached runtime Android stubs')
    parser.add_argument('--resource-jar', type=Path, help='Current flavor R.jar; otherwise inferred beside production classes')
    args = parser.parse_args()
    reference = json.loads(args.dependency_reference.read_text(encoding='utf-8'))
    # Restrict inherited inputs to installed dependency caches. Never inherit a
    # different checkout's compiled app, unit tests, resources or source tree.
    dependencies = [Path(value) for value in reference['classpath']
                    if Path(value).is_file() and Path(value).suffix == '.jar'
                    and 'caches' in Path(value).parts]
    props = {key: value for key, value in reference['selectedSystemProperties'].items()
             if key.startswith('robolectric.')}
    if props.get('robolectric.offline') != 'true' or not dependencies:
        raise RuntimeError('OFFLINE_CACHED_DEPENDENCIES_REQUIRED')
    if not args.production_classes.is_dir():
        raise RuntimeError('CURRENT_PRODUCTION_CLASSES_REQUIRED')
    resource = args.resource_jar
    if resource is None:
        flavor = args.production_classes.parent.parent.name
        resource = args.production_classes.parents[3] / 'compile_and_runtime_r_class_jar' / flavor / ('process' + flavor[0].upper() + flavor[1:] + 'Resources') / 'R.jar'
    if not resource.is_file():
        raise RuntimeError('CURRENT_FLAVOR_RESOURCE_JAR_REQUIRED')
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=False)
    classes = output / 'classes'
    classes.mkdir()
    empty = output / 'empty-sourcepath'
    empty.mkdir()
    main = ROOT / 'app/src/main/java/com/deepseekharness/app'
    sources = list((main / 'screen').glob('*.java'))
    sources += list((main / 'capabilities').glob('*.java'))
    sources += [main / 'DshaAccessibilityService.java', main / 'HttpShellService.java', main / 'core/RuntimeSnapshot.java']
    names = ['ScreenRequestExecutor', 'ScreenConnectionEpoch', 'ScreenObservationLedger',
             'ScreenNodeLeaseStore', 'ScreenTarget', 'ScreenCaptureDelivery',
             'ScreenPixelFingerprint', 'ScreenWindowRegion', 'MainScreenWindowPolicy',
             'VirtualScreenWindowPolicy', 'AccessibilityTreeBudget', 'AccessibilityEnableList',
             'AccessibilityDumpBounds', 'AccessibilityRepairState']
    sources += [main / ('util/' + name + '.java') for name in names]
    sources.append(ROOT / 'tools/fixtures/physical-screen/com/deepseekharness/app/screen/PhysicalScreenFlowTest.java')
    before = {str(path.relative_to(ROOT)): digest(path) for path in sources}
    cp = os.pathsep.join(map(str, [*([args.android_jar] if args.android_jar else []), resource, args.production_classes, *dependencies]))
    jdk = Path(os.environ['JAVA_HOME']) / 'bin'
    ext = '.exe' if os.name == 'nt' else ''
    compile_args = ['-encoding', 'UTF-8', '-source', '17', '-target', '17', '-proc:none',
                    '-implicit:none', '-sourcepath', str(empty), '-classpath', cp,
                    '-d', str(classes), *map(str, sources)]
    argfile = output / 'javac.args'
    argfile.write_text('\n'.join('"' + value.replace('\\', '/') + '"' for value in compile_args), encoding='utf-8')
    commands = [('compile', [str(jdk / ('javac' + ext)), '-J-Dfile.encoding=UTF-8', '@' + str(argfile)], 30),
                ('junit', [str(jdk / ('java' + ext)), '-Xmx768m', '-Dfile.encoding=UTF-8',
                           '-Djava.awt.headless=true', '-Dphysical.classes=' + str(classes),
                           '-Dphysical.evidence=' + str(output),
                           *['-D' + key + '=' + value for key, value in props.items()],
                           '-cp', str(classes) + os.pathsep + cp, 'org.junit.runner.JUnitCore',
                           'com.deepseekharness.app.screen.PhysicalScreenFlowTest'], 45)]
    stages = []
    try:
        for stage, command, timeout in commands:
            result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=timeout)
            stdout, stderr = output / (stage + '.stdout.bin'), output / (stage + '.stderr.bin')
            stdout.write_bytes(result.stdout)
            stderr.write_bytes(result.stderr)
            stages.append({'stage': stage, 'exitCode': result.returncode,
                           'stdoutSha256': digest(stdout), 'stderrSha256': digest(stderr)})
            sys.stdout.buffer.write(result.stdout)
            sys.stderr.buffer.write(result.stderr)
            if result.returncode:
                return result.returncode
        return 0
    finally:
        after = {str(path.relative_to(ROOT)): digest(path) for path in sources}
        (output / 'receipt.json').write_text(json.dumps({
            'scope': 'Current production screen owners with controlled cached Android facts; no phone, service lifecycle, native producer, socket, network or APK acceptance',
            'sourceBefore': before, 'sourceAfter': after, 'sourceUnchanged': before == after,
            'cachedDependencyReference': str(args.dependency_reference),
            'cachedDependencyReferenceSha256': digest(args.dependency_reference),
            'currentProductionClasses': str(args.production_classes),
            'compileAndroidJar': str(args.android_jar) if args.android_jar else None,
            'compileAndroidJarSha256': digest(args.android_jar) if args.android_jar else None,
            'resourceJar': str(resource), 'resourceJarSha256': digest(resource),
            'stages': stages}, indent=2), encoding='utf-8')


if __name__ == '__main__':
    raise SystemExit(main())
