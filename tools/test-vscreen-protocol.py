#!/usr/bin/env python3
"""Developer checks for the real protocol/journal classes. No Android/device acceptance."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/deepseekharness/app'
TEST = ROOT / 'app/src/test/java/com/deepseekharness/app'


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
    annotations = jar('androidx.annotation', 'annotation-jvm', '1.9.1')
    lock = json.loads((ROOT / 'tools/vscreen-test-dependencies.json').read_text(encoding='utf-8'))
    names = ['util/VirtualScreenCoreIdentity', 'util/VirtualScreenHandshake', 'util/VirtualScreenLifecycleState',
             'util/VirtualScreenExitProofPolicy', 'util/ProcStat',
             'util/VirtualScreenBootstrap', 'util/VirtualScreenFailures', 'util/SensitiveData', 'util/UiText',
             'util/UiLanguagePreference', 'util/SystemLanguage', 'util/VirtualScreenPolicy',
             'util/VirtualScreenInputPolicy', 'util/VirtualScreenInputResult', 'util/VirtualScreenStroke',
             'util/AppIdentity', 'util/AppInstallIdentity', 'core/NioInstallationIdentity',
             'vscreen/VirtualScreenJournal', 'vscreen/VirtualScreenSessionJournal',
             'util/VirtualScreenErrors', 'util/VirtualScreenWindowPolicy', 'util/VirtualScreenRequestFence',
             'util/VirtualScreenRoutes', 'util/VscreenBridgeRequest', 'util/HttpProtocol',
             'backup/BackupJson', 'backup/BackupLimits', 'backup/BackupFileSystem', 'util/FileKind', 'util/DeviceShellPolicy', 'util/SelfPackageIdentity',
             'util/WebProcSel', 'util/SmsQuery']
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
        generated = work / 'generated'
        subprocess.run([os.sys.executable, str(ROOT / 'tools/prepare-ui-languages.py'), '--output', str(generated)], check=True, timeout=30)
        cp = os.pathsep.join(map(str, [junit, hamcrest, host_json, gson, annotations]))
        sources = [str(JAVA / (name + '.java')) for name in names] + [str(TEST / (name + '.java')) for name in tests]
        sources += [str(path) for path in generated.rglob('*.java')]
        subprocess.run([str(jdk / ('javac' + extension)), '-J-Dfile.encoding=UTF-8', '-J-Duser.language=en', '-J-Duser.country=US', '-encoding', 'UTF-8', '-cp', cp, '-d', str(work)] + sources,
                       check=True, timeout=40)
        subprocess.run([str(jdk / ('java' + extension)), '-cp', str(work) + os.pathsep + cp, 'org.junit.runner.JUnitCore']
                       + ['com.deepseekharness.app.' + name.replace('/', '.') for name in tests], check=True, timeout=40)
    print('PASS developer protocol JUnit; Journal requires the actual Robolectric SDK34 test runtime; Android transport/permissions/exit remain NOT_RUN_ANDROID')


if __name__ == '__main__':
    main()
