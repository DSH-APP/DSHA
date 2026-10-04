#!/usr/bin/env python3
"""Structural guard for native-dependency ownership/order, not ELF loading or Android execution."""
from pathlib import Path
import re
import sys
from verification_require import require

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/deepseekharness/app/runtime'


def method_body(source, signature):
    index = source.find(signature)
    require(index >= 0, 'NATIVE_DEPENDENCY_METHOD_MISSING:' + signature)
    start = source.index('{', index)
    depth = 0
    for end in range(start, len(source)):
        depth += (source[end] == '{') - (source[end] == '}')
        if depth == 0:
            return source[start:end + 1]
    raise ValueError('NATIVE_DEPENDENCY_METHOD_UNCLOSED:' + signature)


def validate(bootstrap, native, recovery):
    ensure = method_body(bootstrap, 'public void ensureRuntimeFiles()')
    require('nativeFiles.prepare();' in ensure, 'BOOTSTRAP_NATIVE_OWNER_NOT_CALLED')
    require(ensure.index('nativeFiles.prepare();') < ensure.index('ensureDshRuntimePatches();'),
            'BOOTSTRAP_NATIVE_PREPARATION_ORDER')
    preparation = method_body(native, 'void prepare()')
    require(not re.search(r'runtime\(|getContainerRuntime|"proot"|"proroot"', preparation),
            'NATIVE_PREPARATION_GATED_BY_USER_RUNTIME')
    for source, target in [('libtalloc.so', 'libtalloc.so.2'),
                           ('libandroidshmem.so', 'libandroid-shmem.so')]:
        copy = r'copy\(find\("' + re.escape(source) + r'"\),\s*new File\(lib,\s*"' + re.escape(target) + r'"\)\);'
        require(len(re.findall(copy, preparation)) == 1, 'NATIVE_DEPENDENCY_COPY_MISSING:' + target)
    for signature in ('public BoundedProcessRunner.Result runRecoveryMaintenance(',
                      'public BoundedProcessRunner.Result runPersonalMaintenance('):
        caller = method_body(bootstrap, signature)
        require('return recovery.run(command, onLine, timeoutMs);' in caller,
                'RECOVERY_TOOLCHAIN_ENTRY_NOT_DELEGATED:' + signature)
    launch = method_body(recovery, 'BoundedProcessRunner.Result run(')
    require('runtime().id()' not in launch and 'getContainerRuntime' not in launch,
            'RECOVERY_LAUNCH_GATED_BY_USER_RUNTIME')
    anchors = ['nativeFiles.prepare();', 'new ContainerRuntime.Proot(', 'builder.start()']
    require(all(launch.count(anchor) == 1 for anchor in anchors), 'RECOVERY_LAUNCH_ANCHOR_COUNT')
    require([launch.index(anchor) for anchor in anchors] == sorted(launch.index(anchor) for anchor in anchors),
            'RECOVERY_NATIVE_PREPARATION_ORDER')


def main():
    if sys.flags.optimize:
        raise SystemExit('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    validate(*(path.read_text(encoding='utf-8') for path in (
        JAVA / 'ProotBootstrap.java', JAVA / 'RuntimeNativeFiles.java', JAVA / 'RecoveryToolchain.java')))
    print('PASS structure: native owner copies both proot libraries; recovery prepares them before Proot and exec')


if __name__ == '__main__':
    main()
