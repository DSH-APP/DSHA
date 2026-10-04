#!/usr/bin/env python3
"""Narrow source gate for active managed bridge clients; behavior is tested separately."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
CLIENTS = (
    'dsh-device-shell-guide/lib/index.js', 'dsh-computer-use-android/lib/server.cjs',
    'dsh-task-notifier/lib/index.js', 'dsh-tool-vscreen/lib/server.cjs',
)


def main():
    if sys.flags.optimize:
        raise RuntimeError('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    failures = []
    for name in CLIENTS:
        source = (ROOT / 'app/src/main/assets/builtin-plugins' / name).read_text(encoding='utf-8')
        if re.search(r'token=\$T|searchParams\.set\([\'\"]token[\'\"]|URLSearchParams\(\s*\{\s*token', source):
            failures.append(name + ': query credential transport')
        if name != CLIENTS[0] and 'X-Token' not in source:
            failures.append(name + ': missing header transport')
    source = (ROOT / 'app/src/main/java/com/deepseekharness/app/HttpShellService.java').read_text(encoding='utf-8')
    help_source = source.split('private String appHelp()', 1)[1].split('private String appPlugins(', 1)[0]
    if 'token=$T' in help_source or 'token=' in help_source or '-H @/root/.dsh/.bridge_headers' not in help_source:
        failures.append('HttpShellService help: non-header credential guidance')
    if failures:
        raise RuntimeError('\n'.join(failures))
    print('PASS active managed client/help header transport source gate; LAN/browser first-visit credentials and the limited migration shim are separate protocols.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
