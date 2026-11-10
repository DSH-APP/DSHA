#!/usr/bin/env python3
"""Compile the current native policy and execute real dry plans; no Android or device command."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    if sys.flags.optimize:
        raise SystemExit('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    javac, java = shutil.which('javac'), shutil.which('java')
    if not javac or not java:
        raise SystemExit('JDK17_REQUIRED')
    with tempfile.TemporaryDirectory(prefix='native-device-policy-') as folder:
        fixture = Path(folder)
        generated = fixture / 'generated'
        subprocess.run([sys.executable, '-B', str(ROOT / 'tools/prepare-ui-languages.py'),
                        '--output', str(generated)], check=True, timeout=30)
        sources = ROOT / 'app/src/main/java'
        probe = ROOT / 'tools/fixtures/native-device-policy/DevicePolicyProbe.java'
        args = ['-encoding', 'UTF-8', '-source', '17', '-target', '17', '-d', str(fixture / 'classes'),
                '-sourcepath', os.pathsep.join([str(sources), str(generated)]), str(probe)]
        args.extend(str(path) for path in generated.rglob('*.java'))
        subprocess.run([javac, *args], check=True, timeout=90)
        probe_run = subprocess.run(
            [java, '-cp', str(fixture / 'classes'), 'DevicePolicyProbe'],
            check=True, timeout=30, capture_output=True, text=True)
        lines = [line for line in probe_run.stdout.splitlines() if line.strip()]
        summary = lines[-1] if lines else ''
        prefix = 'PASS native device dry plans: '
        if not summary.startswith(prefix):
            raise SystemExit('NATIVE_DEVICE_POLICY_PROBE_SUMMARY_MISSING: ' + summary)
        try:
            checked = int(summary[len(prefix):].split(';', 1)[0])
        except ValueError:
            raise SystemExit('NATIVE_DEVICE_POLICY_PROBE_SUMMARY_INVALID: ' + summary)
        if checked < 1:
            raise SystemExit('NATIVE_DEVICE_POLICY_PROBE_CHECKED_NOTHING')
        print(summary)
    return 0


if __name__ == '__main__':
    sys.exit(main())
