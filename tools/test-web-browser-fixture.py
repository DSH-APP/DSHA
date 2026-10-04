#!/usr/bin/env python3
"""Compile the one shared test-only HTTP server and exercise its real loopback TCP behavior."""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    javac, java = shutil.which('javac'), shutil.which('java')
    if not javac or not java:
        raise SystemExit('JDK17_REQUIRED')
    with tempfile.TemporaryDirectory(prefix='web-browser-shared-fixture-') as folder:
        subprocess.run([javac, '-encoding', 'UTF-8', '--release', '17', '-d', folder,
                        str(ROOT / 'app/src/debug/java/com/deepseekharness/app/ui/WebBrowserFixture.java'),
                        str(ROOT / 'tools/fixtures/web-state/java/WebBrowserFixtureProbe.java')], check=True, timeout=30)
        subprocess.run([java, '-cp', folder, 'WebBrowserFixtureProbe'], check=True, timeout=30)


if __name__ == '__main__':
    main()
