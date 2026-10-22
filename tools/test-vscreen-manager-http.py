"""Compile current Java and exercise its real loopback HTTP concurrency; no phone/Gradle/APK."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def release_resource_inputs():
    # These are the actual AGP source-set inputs, including generated resValues.
    # Never treat merged/packaged outputs as new source inputs.
    path_map = ROOT / 'app/build/intermediates/android_res_source_set_path_map/standardRelease/mapStandardReleaseSourceSetPaths/file-map.txt'
    if not path_map.is_file():
        raise RuntimeError('CURRENT_STANDARD_RELEASE_RESOURCE_INPUTS_REQUIRED')
    roots = [ROOT / ('app/src/' + name + '/res')
             for name in ('main', 'release', 'standard', 'standardRelease')]
    generated = (ROOT / 'app/build/generated').resolve()
    for line in path_map.read_text(encoding='utf-8').splitlines():
        path = Path(line.split(' ', 1)[1]).resolve()
        if path.is_relative_to(generated):
            roots.append(path)
    files = sorted({path.resolve() for folder in roots for path in folder.rglob('*')
                    if path.is_file()}, key=str)
    if not files:
        raise RuntimeError('CURRENT_STANDARD_RELEASE_RESOURCE_INPUTS_REQUIRED')
    controls = [path_map, ROOT / 'build.gradle', ROOT / 'app/build.gradle', ROOT / 'gradle.properties']
    return files, {str(path.relative_to(ROOT)): digest(path) for path in files + controls}


def scalar_symbols(text, kind):
    symbols = set()
    for line in text.splitlines():
        words = line.split()
        if not words or words[0] in ('R_DEF:', 'local'):
            continue
        if kind == 'aapt':
            if len(words) != 4 or words[1] != 'int':
                raise RuntimeError('UNSUPPORTED_CURRENT_RESOURCE_SYMBOL: ' + line)
            resource_type, name = words[2:4]
        elif kind == 'definitions':
            if len(words) != 2:
                raise RuntimeError('UNSUPPORTED_CURRENT_RESOURCE_SYMBOL: ' + line)
            resource_type, name = words
        else:
            if len(words) != 4 or words[0] != 'int' or int(words[3], 0) != 0:
                raise RuntimeError('CURRENT_COMPILE_R_SYMBOL_FORMAT: ' + line)
            resource_type, name = words[1:3]
        symbols.add((resource_type, name.replace('.', '_').replace(':', '_')))
    return symbols


def require_same_symbols(actual, expected, stage):
    if actual != expected:
        raise RuntimeError('STALE_STANDARD_RELEASE_RESOURCE_SYMBOLS:' + stage
                           + ':missing=' + repr(sorted(expected - actual)[:8])
                           + ':extra=' + repr(sorted(actual - expected)[:8]))


def verify_release_resource_symbols(work, cache, jdk):
    # AGP's compile R is the Java input; its zero IDs are deliberate. Runtime R
    # may retain older timestamps when resource values change but IDs do not.
    resource = ROOT / 'app/build/intermediates/compile_r_class_jar/standardRelease/generateStandardReleaseRFile/R.jar'
    symbols = ROOT / 'app/build/intermediates/compile_symbol_list/standardRelease/generateStandardReleaseRFile/R.txt'
    definitions = ROOT / 'app/build/intermediates/local_only_symbol_list/standardRelease/parseStandardReleaseLocalResources/R-def.txt'
    artifacts = [resource, symbols, definitions]
    if not all(path.is_file() for path in artifacts):
        raise RuntimeError('CURRENT_STANDARD_RELEASE_COMPILE_RESOURCES_REQUIRED')
    agp = re.search(r"id 'com.android.application' version '([^']+)'", (ROOT / 'build.gradle').read_text(encoding='utf-8'))
    if not agp:
        raise RuntimeError('CURRENT_AAPT2_VERSION_REQUIRED')
    candidates = list(cache.glob('caches/9.3.1/transforms/**/aapt2-' + agp[1] + '-*/aapt2.exe'))
    if not candidates or len({digest(path) for path in candidates}) != 1:
        raise RuntimeError('CURRENT_AGP_AAPT2_REQUIRED')
    aapt = candidates[0]
    files, inputs = release_resource_inputs()
    artifacts_before = {str(path.relative_to(ROOT)): digest(path) for path in artifacts}

    def compile_symbols(item):
        index, source = item
        folder = work / 'resource-symbols' / str(index)
        staged = folder / 'res' / source.parent.name / source.name
        staged.parent.mkdir(parents=True)
        data = source.read_bytes()
        if hashlib.sha256(data).hexdigest() != inputs[str(source.relative_to(ROOT))]:
            raise RuntimeError('RESOURCE_INPUT_CHANGED_DURING_CHECK')
        staged.write_bytes(data)
        output = folder / 'compiled'; output.mkdir()
        text = folder / 'symbols.txt'
        subprocess.run([str(aapt), 'compile', str(staged), '--output-text-symbols', str(text),
                        '-o', str(output)], check=True, timeout=30, capture_output=True)
        if not text.is_file():
            # AAPT2 validates/compiles binary resources but does not emit a symbol
            # text file for them; their resource key is the validated filename.
            if source.suffix == '.xml' or not list(output.glob('*.flat')):
                raise RuntimeError('CURRENT_AAPT2_RESOURCE_SYMBOLS_REQUIRED')
            return {(source.parent.name.split('-')[0], source.name.split('.')[0])}
        return scalar_symbols(text.read_text(encoding='utf-8'), 'aapt')

    fresh = set()
    # Compile immutable copies into this test's private directory, not Gradle outputs.
    with ThreadPoolExecutor(max_workers=2) as pool:
        for value in pool.map(compile_symbols, enumerate(files)):
            fresh.update(value)
    require_same_symbols(scalar_symbols(definitions.read_text(encoding='utf-8'), 'definitions'), fresh, 'R-def')
    require_same_symbols(scalar_symbols(symbols.read_text(encoding='utf-8'), 'compile'), fresh, 'R.txt')
    with zipfile.ZipFile(resource) as jar:
        classes = sorted(name[:-6].replace('/', '.') for name in jar.namelist()
                         if name.startswith('com/deepseekharness/app/R$') and name.endswith('.class'))
    javap = jdk / 'bin' / ('javap.exe' if os.name == 'nt' else 'javap')
    result = subprocess.run([str(javap), '-classpath', str(resource), '-constants'] + classes,
                            check=True, capture_output=True, text=True, timeout=30)
    actual = set(); resource_type = None
    for line in result.stdout.splitlines():
        match = re.match(r'public final class com\.deepseekharness\.app\.R\$(\w+) \{', line)
        if match:
            resource_type = match[1]
        elif 'public static ' in line:
            field = re.fullmatch(r'  public static int (\w+) = 0;', line)
            if not field or resource_type is None:
                raise RuntimeError('CURRENT_COMPILE_R_CLASS_FORMAT: ' + line)
            actual.add((resource_type, field[1]))
    require_same_symbols(actual, fresh, 'R.jar')
    if release_resource_inputs()[1] != inputs or any(digest(path) != artifacts_before[str(path.relative_to(ROOT))] for path in artifacts):
        raise RuntimeError('RESOURCE_INPUT_CHANGED_DURING_CHECK')
    proof = {'scope': 'current Java compile symbols; resource value packaging requires Release Gradle',
             'resourceInputs': inputs, 'agpArtifacts': artifacts_before, 'aapt2Sha256': digest(aapt),
             'symbolCount': len(fresh)}
    print('PASS current StandardRelease resource bytes -> AAPT2 symbols -> AGP R-def/R.txt/R.jar (' + str(len(fresh)) + ' symbols); no resource-value packaging acceptance claimed')
    return resource, proof


def run_release_source_tests(current, work, cache, sdk, junit, hamcrest, host_json):
    # Compile the actual production release sources against Android37. Runtime JSON
    # is a separately locked host implementation; Android stubs are not UI acceptance.
    resource, proof = verify_release_resource_symbols(work, cache, Path(os.environ['JAVA_HOME']))
    (ROOT / 'app/build/vscreen-test/release-resource-symbol-proof.json').write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
    generated = work / 'generated'
    subprocess.run([os.sys.executable, '-B', str(ROOT / 'tools/prepare-ui-languages.py'),
                    '--output', str(generated)], check=True, timeout=30)
    locked, coordinates = current.locked_backup_classpath(cache)
    cp = [str(sdk), str(resource), str(junit), str(hamcrest)] + locked
    cp += [str(path) for path in cache.glob('caches/9.3.1/transforms/**/classes.jar') if 'instrumented' not in path.parts]
    cp += [str(path) for path in cache.glob('caches/modules-2/files-2.1/**/*.jar')
           if not path.name.endswith(('-sources.jar', '-javadoc.jar'))
           and not any(group in path.parts and name in path.parts for group, name in coordinates)]
    sources = [str(path) for folder in ('main', 'standard')
               for path in (ROOT / ('app/src/' + folder + '/java')).rglob('*.java')]
    sources += [str(path) for path in (ROOT / 'tools/aidl-stub').rglob('*.java')]
    sources += [str(path) for path in generated.rglob('*.java')]
    build_config = list((ROOT / 'app/build/generated/source/buildConfig/standard/release').rglob('*.java'))
    if not build_config:
        raise RuntimeError('CURRENT_RELEASE_BUILD_CONFIG_REQUIRED')
    sources += list(map(str, build_config))
    current._javac(sources, work / 'main', cp)
    names = ['util/CoalescingPollerTest', 'util/VirtualScreenRequestFenceTest',
             'util/TouchGestureDispatchTest', 'util/VirtualScreenPolicyTest',
             'util/VscreenBridgeRequestTest', 'util/ScreenSessionGrantTest',
             'vscreen/VirtualScreenManagerRevocationTest', 'vscreen/VirtualScreenManagerOwnershipTest']
    tests = [str(ROOT / ('app/src/test/java/com/deepseekharness/app/' + name + '.java')) for name in names]
    current._javac(tests, work / 'test', cp + [str(work / 'main')])
    run_cp = os.pathsep.join([str(host_json), str(work / 'test'), str(work / 'main')] + cp)
    arguments = work / 'junit-args.txt'
    current.write_argfile(arguments, ['-Dfile.encoding=UTF-8', '-cp', run_cp, 'org.junit.runner.JUnitCore']
                          + ['com.deepseekharness.app.' + name.replace('/', '.') for name in names],
                          encoding='mbcs' if os.name == 'nt' else 'utf-8')
    subprocess.run(['java', '-Dfile.encoding=UTF-8', '@' + str(arguments)], check=True, timeout=60)
    print('PASS current main/standard release source compilation and owned-state JUnit; Android framework construction is bypassed only in explicit host owner fixtures')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", required=True)
    parser.add_argument("--sdk", required=True)
    parser.add_argument("--gradle-home", required=True)
    parser.add_argument('--resource-gate-only', action='store_true', help='Verify current resource symbols without compiling/running the HTTP fixture')
    args = parser.parse_args()
    jdk, sdk, cache = map(lambda value: Path(value).resolve(), [args.jdk, args.sdk, args.gradle_home])
    os.environ.update(JAVA_HOME=str(jdk), ANDROID_SDK_ROOT=str(sdk), GRADLE_USER_HOME=str(cache),
                      LANG="C.UTF-8", LC_CTYPE="C.UTF-8", PYTHONIOENCODING="utf-8",
                      DSHA_ONLY="CoalescingPollerTest,VirtualScreenRequestFenceTest,VirtualScreenManagerRevocationTest,TouchGestureDispatchTest,VirtualScreenPolicyTest,VscreenBridgeRequestTest")
    os.environ["PATH"] = str(jdk / "bin") + os.pathsep + os.environ.get("PATH", "")
    spec = importlib.util.spec_from_file_location("dsha_current_java_tests", ROOT / "tools/run-unit-tests.py")
    current = importlib.util.module_from_spec(spec); spec.loader.exec_module(current)
    current.ensure_utf8_locale()
    destination = ROOT / "app/build/vscreen-test"; destination.mkdir(parents=True, exist_ok=True)
    if args.resource_gate_only:
        with tempfile.TemporaryDirectory(prefix='vscreen-resources-', dir=destination) as folder:
            _, proof = verify_release_resource_symbols(Path(folder), cache, jdk)
            (destination / 'release-resource-symbol-proof.json').write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
        return 0
    lock = json.loads((ROOT / "tools/vscreen-test-dependencies.json").read_text(encoding="utf-8"))
    jar = destination / lock["artifact"]
    if jar.is_file():
        data = jar.read_bytes()
    else:
        with urllib.request.urlopen(lock["url"], timeout=40) as response:
            data = response.read(lock["size"] + 1)
    if (len(data) != lock["size"] or hashlib.sha256(data).hexdigest() != lock["sha256"]
            or hashlib.sha1(data).hexdigest() != lock["publishedSha1"]):
        raise RuntimeError("VSCREEN_HOST_JSON_DIGEST_MISMATCH")
    jar.write_bytes(data)
    android = current.android_jar()
    junit = current.find_jar(cache, "junit-4.13.2", "junit-4.13")
    hamcrest = current.find_jar(cache, "hamcrest-core-1.3", "hamcrest-core")
    with tempfile.TemporaryDirectory(prefix="vscreen-current-", dir=destination) as folder:
        work = Path(folder)
        run_release_source_tests(current, work, cache, android, junit, hamcrest, jar)
        fixture = ROOT / "tools/fixtures/vscreen-manager/ManagerHttpFixture.java"
        classes = work / "http-fixture"
        current._javac([str(fixture)], classes, [str(jar), str(work / "main"), str(android)])
        command = [str(jdk / "bin" / ("java.exe" if os.name == "nt" else "java")),
                   "-Dfile.encoding=UTF-8", "-cp",
                   os.pathsep.join(map(str, [classes, jar, work / "main", android])),
                   "com.deepseekharness.app.vscreen.ManagerHttpFixture"]
        subprocess.run(command, cwd=ROOT, check=True, timeout=60)
    print("PASS current source compilation, selected JUnit and actual HTTP manager fixture; no Android platform acceptance claimed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
