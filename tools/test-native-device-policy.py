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
        subprocess.run([java, '-cp', str(fixture / 'classes'), 'DevicePolicyProbe'],
                       check=True, timeout=30)
    return 0


if __name__ == '__main__':
    sys.exit(main())
