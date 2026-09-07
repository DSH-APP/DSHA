#!/usr/bin/env python3
"""只生成 app/build 中的验收样本，绝不复制到 release 或安装到手机。"""
import argparse
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--debug-key', type=Path, required=True)
    parser.add_argument('--flavor', choices=['standard', 'low'], default='low')
    parser.add_argument('--version-code', type=int, default=114)
    parser.add_argument('--size-mib', type=int, default=8)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[1]
    output = (args.output or repository / 'app/build/rc13-update-fixtures').resolve()
    if not output.is_relative_to((repository / 'app/build').resolve()) or not 1 <= args.size_mib <= 64:
        parser.error('样本只允许生成到 app/build，大小限制为 1–64 MiB')
    output.mkdir(parents=True, exist_ok=True)
    build_tools = args.sdk / 'build-tools/36.0.0'
    java, keytool = args.jdk / 'bin/java.exe', args.jdk / 'bin/keytool.exe'
    def run(*command):
        subprocess.run([str(x) for x in command], check=True, stdout=subprocess.DEVNULL)
    alien = output / 'test-only.keystore'
    if not alien.exists():
        run(keytool, '-genkeypair', '-alias', 'test', '-keyalg', 'RSA', '-keysize', '2048',
            '-validity', '7', '-dname', 'CN=DSHA validation only', '-keystore', alien,
            '-storepass', 'test-only-rc13', '-keypass', 'test-only-rc13')
    for name in ['valid', 'wrong-signature', 'wrong-package', 'wrong-flavor']:
        package = 'com.dsh.client' if name != 'wrong-package' else 'com.dsh.validation.wrong'
        low = args.flavor == 'low'
        if name == 'wrong-flavor': low = not low
        version = '1.2.0-validation' + ('low' if low else '')
        manifest = output / 'AndroidManifest.xml'
        manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="{args.version_code}" android:versionName="{version}"><uses-sdk android:minSdkVersion="{23 if low else 30}" android:targetSdkVersion="37"/><application android:label="DSHA validation - DO NOT INSTALL"/></manifest>', encoding='utf-8')
        unsigned = output / (name + '-unsigned.apk')
        run(build_tools / 'aapt2.exe', 'link', '--manifest', manifest, '-I', args.sdk / 'platforms/android-37.0/android.jar', '-o', unsigned)
        if name == 'valid':
            with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_STORED) as archive:
                archive.writestr('assets/download-progress-fixture.bin', bytes(args.size_mib * 1024 * 1024))
        keystore, alias, password = (alien, 'test', 'test-only-rc13') if name == 'wrong-signature' else (args.debug_key, 'androiddebugkey', 'android')
        run(java, '-jar', build_tools / 'lib/apksigner.jar', 'sign', '--ks', keystore, '--ks-key-alias', alias,
            '--ks-pass', 'pass:' + password, '--key-pass', 'pass:' + password, '--out', output / (name + '.apk'), unsigned)
        unsigned.unlink()
    print(output)


if __name__ == '__main__':
    main()
