#!/usr/bin/env python3
"""只在本次私有夹具目录运行宿主备份验收，输出新鲜 JUnit 证据及机器可读结果。"""
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', help='JDK 17 java executable; otherwise JAVA_HOME or PATH')
    parser.add_argument('--node', default='node')
    parser.add_argument('--modules', default=str(ROOT / 'app/build/rc2-20260911/locked-runtime/node_modules'))
    args = parser.parse_args()
    java = args.java or (str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java'))
                         if 'JAVA_HOME' in os.environ else shutil.which('java'))
    if not java:
        parser.error('JDK 17 is required; configure JAVA_HOME')
    owned = ROOT / 'app/build/backup-acceptance' / str(uuid.uuid4())
    if not owned.resolve().is_relative_to((ROOT / 'app/build').resolve()):
        raise RuntimeError('Unsafe acceptance directory')
    owned.mkdir(parents=True)
    temporary = owned / 'tmp'
    temporary.mkdir()
    if not owned.resolve().is_relative_to((ROOT / 'app/build').resolve()):
        raise RuntimeError('Unsafe acceptance directory')
    escaped = str(owned.resolve()).replace('\\', '/').replace("'", "\\'")
    init = owned / 'acceptance.init.gradle'
    init.write_text("gradle.projectsEvaluated { rootProject.allprojects { p ->\n"
                    "p.tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { test ->\n"
                    "  test.outputs.upToDateWhen { false }; test.outputs.cacheIf { false }\n"
                    f"  test.systemProperty 'java.io.tmpdir', '{escaped}/tmp'\n"
                    "  test.reports.junitXml.required.set(true)\n"
                    f"  test.reports.junitXml.outputLocation.set(new File('{escaped}/reports', test.name))\n"
                    "} } }\n", encoding='utf-8')
    # 直接调用 Java WrapperMain，不把路径或参数拼给 PowerShell/cmd。
    command = [java, '-cp', str(ROOT / 'gradle/wrapper/gradle-wrapper.jar'),
               'org.gradle.wrapper.GradleWrapperMain', '--console=plain', '--continue', '-I', str(init)]
    tasks = ['testStandardDebugUnitTest', 'testLowDebugUnitTest']
    for task in tasks:
        command += [':app:' + task, '--tests', 'com.deepseekharness.app.backup.*']
    started = time.time()
    with (owned / 'gradle.log').open('wb') as log:
        try:
            gradle_exit = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=False).returncode
        except OSError as error:
            log.write(type(error).__name__.encode('ascii'))
            gradle_exit = -1
    report = {'version': 1, 'fixture': 'synthetic; each test uses this run-owned temporary directory',
              'startedAt': started, 'gradleExitCode': gradle_exit,
              'hardware': {'status': 'DEFERRED_BY_USER', 'adbUsed': False}, 'flavors': {}}
    passed = gradle_exit == 0
    for task in tasks:
        cases, errors = [], []
        for file in sorted((owned / 'reports' / task).glob('*.xml')):
            try:
                suite = ET.parse(file).getroot()
            except (OSError, ET.ParseError):
                errors.append(file.name)
                continue
            for case in suite.findall('testcase'):
                status = 'FAIL' if case.find('failure') is not None or case.find('error') is not None else 'SKIP' if case.find('skipped') is not None else 'PASS'
                cases.append({'class': case.get('classname'), 'name': case.get('name'), 'status': status})
        complete = bool(cases) and not errors and all(case['status'] == 'PASS' for case in cases)
        report['flavors'][task] = {'status': 'PASS' if complete else 'INCOMPLETE_OR_FAILED', 'cases': cases, 'unreadableReports': errors}
        passed &= complete
    with (owned / 'node.log').open('wb') as log:
        try:
            node_exit = subprocess.run([args.node, str(ROOT / 'tools/test-runtime-trial.mjs'), args.modules, str(owned)],
                                       cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=False).returncode
        except OSError as error:
            log.write(type(error).__name__.encode('ascii'))
            node_exit = -1
    protocol_file = owned / 'runtime-trial-protocol.json'
    report['desktopProtocol'] = json.loads(protocol_file.read_text(encoding='utf-8')) if protocol_file.exists() else {'status': 'MISSING'}
    passed &= node_exit == 0 and protocol_file.exists()
    report['status'] = 'PASS' if passed else 'INCOMPLETE_OR_FAILED'
    report['scope'] = 'Host backup package software checks only; not a final APK or device acceptance claim'
    target = owned / 'result.json'
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': report['status'], 'report': str(target)}, ensure_ascii=False))
    # 保留本次报告，不递归删除任何旧报告、工作区数据或用户文件。
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
