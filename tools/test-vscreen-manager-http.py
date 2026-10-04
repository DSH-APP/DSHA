"""Compile current Java and exercise its real loopback HTTP concurrency; no phone/Gradle/APK."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def run_release_source_tests(current, work, cache, sdk, junit, hamcrest, host_json):
    # Compile the actual production release sources against Android37. Runtime JSON
    # is a separately locked host implementation; Android stubs are not UI acceptance.
    resources = list((ROOT / 'app/build/intermediates/compile_and_runtime_r_class_jar/standardRelease').glob('**/R.jar'))
    if not resources:
        raise RuntimeError('CURRENT_STANDARD_RELEASE_RESOURCES_REQUIRED')
    resource = max(resources, key=lambda path: path.stat().st_mtime_ns)
    for folder in ('main', 'standard'):
        if any(path.stat().st_mtime_ns > resource.stat().st_mtime_ns
               for path in (ROOT / ('app/src/' + folder + '/res')).rglob('*') if path.is_file()):
            raise RuntimeError('STALE_STANDARD_RELEASE_RESOURCES')
    generated = work / 'generated'
    subprocess.run([os.sys.executable, '-B', str(ROOT / 'tools/prepare-ui-languages.py'),
                    '--output', str(generated)], check=True, timeout=30)
    locked, coordinates = current.locked_backup_classpath(cache)
    cp = [str(sdk), str(resource), str(junit), str(hamcrest)] + locked
    cp += [str(path) for path in cache.glob('caches/9.3.1/transforms/**/classes.jar') if 'instrumented' not in path.parts]
    cp += [str(path) for path in cache.glob('caches/modules-2/files-2.1/**/*.jar')
           if 'sources' not in path.name and 'javadoc' not in path.name
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
    args = parser.parse_args()
    jdk, sdk, cache = map(lambda value: Path(value).resolve(), [args.jdk, args.sdk, args.gradle_home])
    os.environ.update(JAVA_HOME=str(jdk), ANDROID_SDK_ROOT=str(sdk), GRADLE_USER_HOME=str(cache),
                      LANG="C.UTF-8", LC_CTYPE="C.UTF-8", PYTHONIOENCODING="utf-8",
                      DSHA_ONLY="CoalescingPollerTest,VirtualScreenRequestFenceTest,VirtualScreenManagerRevocationTest,TouchGestureDispatchTest,VirtualScreenPolicyTest,VscreenBridgeRequestTest")
    os.environ["PATH"] = str(jdk / "bin") + os.pathsep + os.environ.get("PATH", "")
    spec = importlib.util.spec_from_file_location("dsha_current_java_tests", ROOT / "tools/run-unit-tests.py")
    current = importlib.util.module_from_spec(spec); spec.loader.exec_module(current)
    current.ensure_utf8_locale()
    lock = json.loads((ROOT / "tools/vscreen-test-dependencies.json").read_text(encoding="utf-8"))
    destination = ROOT / "app/build/vscreen-test"; destination.mkdir(parents=True, exist_ok=True)
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
                   "-Dfile.encoding=UTF-8", "--add-modules", "jdk.httpserver", "-cp",
                   os.pathsep.join(map(str, [classes, jar, work / "main", android])),
                   "com.deepseekharness.app.vscreen.ManagerHttpFixture"]
        subprocess.run(command, cwd=ROOT, check=True, timeout=60)
    print("PASS current source compilation, selected JUnit and actual HTTP manager fixture; no Android platform acceptance claimed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
