#!/usr/bin/env python3
"""Developer checks for the real protocol/journal classes. No Android/device acceptance."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/deepseekharness/app'
TEST = ROOT / 'app/src/test/java/com/deepseekharness/app'

# 宿主编译需要 androidx.annotation 的实现 jar（Android SDK 桩会抛异常）；
# 版本与 Gradle 缓存中使用的 1.9.1 一致，摘要来自 Google Maven 同一构件。
ANNOTATION_URL = ('https://dl.google.com/dl/android/maven2/androidx/annotation/'
                  'annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar')
ANNOTATION_SIZE = 60577
ANNOTATION_SHA256 = '1e343917ebf27ba96fe4dc52b1cad7fd32b738fbc6355bb6cd5b3b305d7212d0'


def main():
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
    jdk = Path(os.environ['JAVA_HOME']) / 'bin'
    extension = '.exe' if os.name == 'nt' else ''
    def jar(group, name, version):
        return next((cache / 'caches/modules-2/files-2.1' / group / name / version).glob('**/' + name + '-' + version + '.jar'))
    junit = jar('junit', 'junit', '4.13.2')
    hamcrest = jar('org.hamcrest', 'hamcrest-core', '1.3')
    gson_lock = next(item for item in json.loads((ROOT / 'tools/backup-dependencies.lock.json').read_text(encoding='utf-8'))['dependencies'] if item['name'] == 'gson')
    gson = jar(gson_lock['group'], gson_lock['name'], gson_lock['version'])
    if hashlib.sha256(gson.read_bytes()).hexdigest() != gson_lock['sha256']:
        raise RuntimeError('GSON_DIGEST_MISMATCH')
    lock = json.loads((ROOT / 'tools/vscreen-test-dependencies.json').read_text(encoding='utf-8'))
    names = ['util/VirtualScreenCoreIdentity', 'util/VirtualScreenHandshake', 'util/VirtualScreenLifecycleState',
             'util/VirtualScreenExitProofPolicy', 'util/ProcStat',
             'util/VirtualScreenBootstrap', 'util/VirtualScreenFailures', 'util/SensitiveData', 'util/UiText',
             'util/UiLanguagePreference', 'util/SystemLanguage', 'util/Ids', 'util/VirtualScreenPolicy',
             'util/VirtualScreenInputPolicy', 'util/VirtualScreenInputResult', 'util/VirtualScreenStroke',
             'util/AppIdentity', 'util/AppInstallIdentity', 'core/NioInstallationIdentity',
             'vscreen/VirtualScreenJournal', 'vscreen/VirtualScreenSessionJournal',
             'util/VirtualScreenErrors', 'util/VirtualScreenWindowPolicy', 'util/VirtualScreenRequestFence',
             'util/VirtualScreenRoutes', 'util/VscreenBridgeRequest', 'util/HttpProtocol',
             'backup/BackupJson', 'backup/BackupLimits', 'backup/BackupFileSystem', 'util/FileKind', 'util/GrepReadPaths', 'util/DeviceShellPolicy', 'util/SelfPackageIdentity',
             'util/WebProcSel', 'util/SmsQuery', 'util/DshEntrypoint']
    tests = ['util/VirtualScreenHandshakeTest', 'util/VirtualScreenBootstrapTest', 'util/VirtualScreenPolicyTest',
             'util/VirtualScreenExitProofPolicyTest',
             'util/VirtualScreenStrokeTest', 'util/VirtualScreenInputPolicyTest',
             'util/VirtualScreenInputResultTest', 'util/VirtualScreenErrorsTest', 'util/VirtualScreenFailuresTest',
             'util/VirtualScreenRequestFenceTest', 'util/VirtualScreenRequestFrameTest',
             'util/VirtualScreenRoutesTest', 'util/VirtualScreenWindowPolicyTest',
             'util/VscreenBridgeRequestTest', 'util/VscreenAnchoredGrammarTest', 'util/DeviceShellPolicyTest']
    with tempfile.TemporaryDirectory(prefix='dsha-vscreen-protocol-') as directory:
        work = Path(directory)
        host_json = work / lock['artifact']
        with urllib.request.urlopen(lock['url'], timeout=30) as response:
            data = response.read(lock['size'] + 1)
        if len(data) != lock['size'] or hashlib.sha256(data).hexdigest() != lock['sha256']:
            raise RuntimeError('HOST_JSON_DIGEST_MISMATCH')
        host_json.write_bytes(data)
        # 宿主注解 jar 优先取 Gradle 缓存；托管 runner 未必解析到该配置，
        # 就按固定摘要从 Google Maven 取同一份字节（与 android-json 同一策略）。
        annotations = next((cache / 'caches/modules-2/files-2.1' / 'androidx.annotation' /
                            'annotation-jvm' / '1.9.1').glob('**/annotation-jvm-1.9.1.jar'), None)
        if annotations is None:
            with urllib.request.urlopen(ANNOTATION_URL, timeout=30) as response:
                annotation_bytes = response.read(ANNOTATION_SIZE + 1)
            if len(annotation_bytes) != ANNOTATION_SIZE or \
                    hashlib.sha256(annotation_bytes).hexdigest() != ANNOTATION_SHA256:
                raise RuntimeError('ANNOTATION_JAR_DIGEST_MISMATCH')
            annotations = work / 'annotation-jvm-1.9.1.jar'
            annotations.write_bytes(annotation_bytes)
        generated = work / 'generated'
        subprocess.run([os.sys.executable, str(ROOT / 'tools/prepare-ui-languages.py'), '--output', str(generated)], check=True, timeout=30)
        cp = os.pathsep.join(map(str, [junit, hamcrest, host_json, gson, annotations]))
        # AppIdentity reads the AGP-generated BuildConfig; host compilation needs the same
        # production application id, not a hard-coded package name. build.gradle now reads it
        # from the selected release identity (default mod), so take it from that same file.
        application_id = json.loads((ROOT / 'ci/release-identity.mod.json').read_text(encoding='utf-8'))['packageName']
        build_config = work / 'BuildConfig.java'
        build_config.write_text('package com.deepseekharness.app;\n\npublic final class BuildConfig {\n  public static final String APPLICATION_ID = "' + application_id + '";\n}\n', encoding='utf-8')
        sources = [str(JAVA / (name + '.java')) for name in names] + [str(TEST / (name + '.java')) for name in tests]
        sources += [str(path) for path in generated.rglob('*.java')] + [str(build_config)]
        subprocess.run([str(jdk / ('javac' + extension)), '-J-Dfile.encoding=UTF-8', '-J-Duser.language=en', '-J-Duser.country=US', '-encoding', 'UTF-8', '-cp', cp, '-d', str(work)] + sources,
                       check=True, timeout=40)
        subprocess.run([str(jdk / ('java' + extension)), '-cp', str(work) + os.pathsep + cp, 'org.junit.runner.JUnitCore']
                       + ['com.deepseekharness.app.' + name.replace('/', '.') for name in tests], check=True, timeout=40)
    print('PASS developer protocol JUnit; Journal requires the actual Robolectric SDK34 test runtime; Android transport/permissions/exit remain NOT_RUN_ANDROID')


if __name__ == '__main__':
    main()
