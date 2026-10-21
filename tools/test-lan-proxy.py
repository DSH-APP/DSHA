#!/usr/bin/env python3
"""Exercise the production Java LAN proxy on host loopback sockets; no Android/device claims."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', required=True)
    parser.add_argument('--gradle-home', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--baseline-proxy', help='optional pre-change LanProxyService source for regression evidence')
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    stubs = output / 'stubs'
    sources = {
        'android/content/Context.java': '''package android.content;
public class Context {
 public static final int MODE_PRIVATE = 0;
 public Context getApplicationContext() { return this; }
 public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
}''',
        'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {
 Editor edit();
 interface Editor { Editor putString(String key, String value); void apply(); }
}''',
        'android/util/Log.java': '''package android.util;
public final class Log { public static int i(String tag, String message) { return 0; } }''',
        'com/deepseekharness/app/bridge/LocalNetworkAccess.java': '''package com.deepseekharness.app.bridge;
public final class LocalNetworkAccess {
 public static boolean granted(android.content.Context context) { return true; }
}''',
        'com/deepseekharness/app/util/UiMessages.java': '''package com.deepseekharness.app.util;
public final class UiMessages { public static final java.util.Map<String,String> EN = java.util.Map.of(); }''',
    }
    for name, value in sources.items():
        target = stubs / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value, encoding='utf-8')
    cache = Path(args.gradle_home) / 'caches/modules-2/files-2.1'
    dependencies = []
    for group, name, version in [('junit', 'junit', '4.13.2'), ('org.hamcrest', 'hamcrest-core', '1.3')]:
        matches = list((cache / group / name / version).glob('**/*.jar'))
        if len(matches) != 1:
            raise RuntimeError('EXACT_JVM_DEPENDENCY_REQUIRED:' + name)
        dependencies.extend(matches)
    cp = os.pathsep.join(map(str, dependencies))
    classes = output / 'classes'
    classes.mkdir(exist_ok=True)
    overrides = output / 'overrides'
    if args.baseline_proxy:
        target = overrides / 'com/deepseekharness/app/LanProxyService.java'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(Path(args.baseline_proxy).read_bytes())
    suffix = '.exe' if os.name == 'nt' else ''
    jdk = Path(args.jdk) / 'bin'
    tests = ['LanAuthTest', 'util.SocketDispatchTest', 'util.HttpProtocolTest', 'util.LanNetworkStateTest', 'util.LanAddressPolicyTest']
    test_sources = [ROOT / 'app/src/test/java/com/deepseekharness/app' / (name.replace('.', '/') + '.java') for name in tests]
    command = [str(jdk / ('javac' + suffix)), '-encoding', 'UTF-8', '--release', '17', '-cp', cp,
               '-d', str(classes), '-sourcepath', os.pathsep.join(map(str, [stubs, overrides, ROOT / 'app/src/main/java', ROOT / 'app/src/test/java'])),
               str(ROOT / 'tools/fixtures/lan-proxy-host/LanProxyHostTest.java'), *map(str, test_sources)]
    subprocess.run(command, check=True, timeout=90)
    subprocess.run([str(jdk / ('java' + suffix)), '-Dfile.encoding=UTF-8', '-cp', str(classes) + os.pathsep + cp,
                    'org.junit.runner.JUnitCore', 'com.deepseekharness.app.LanProxyHostTest',
                    *['com.deepseekharness.app.' + name for name in tests]], check=True, timeout=90)
    print('PASS production LAN proxy sockets and HTTP/auth/dispatch regressions. Android context/log/local-network permission are stubs; no phone/network-switch or APK claim.')


if __name__ == '__main__':
    main()
