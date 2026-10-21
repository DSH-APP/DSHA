#!/usr/bin/env python3
"""Real GNU libc tests: constructor calls, forwarding/errno and actual C auxv parsing."""
import argparse
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
SOURCE = HERE / 'stack-sysconf.c'
VENDOR = r'''
#include <unistd.h>
long dsha_constructor_values[4];
__attribute__((constructor)) static void before_main(void) {
  dsha_constructor_values[0] = sysconf(_SC_THREAD_STACK_MIN);
  dsha_constructor_values[1] = sysconf(_SC_MINSIGSTKSZ);
  dsha_constructor_values[2] = sysconf(_SC_SIGSTKSZ);
  dsha_constructor_values[3] = sysconf(_SC_PAGESIZE);
}
'''
PROBE = r'''
#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/auxv.h>
#include <unistd.h>
#include <gnu/libc-version.h>
_Static_assert(_SC_THREAD_STACK_MIN == 75, "GNU thread ABI");
_Static_assert(_SC_MINSIGSTKSZ == 249, "GNU minsig ABI");
_Static_assert(_SC_SIGSTKSZ == 250, "GNU sig ABI");
typedef long (*query)(int);
static int fds(void) {
  DIR *dir = opendir("/proc/self/fd"); assert(dir);
  int count = 0; while (readdir(dir)) ++count;
  assert(closedir(dir) == 0); return count;
}
int main(int argc, char **argv) {
  (void)argc;
  void *libc = dlopen("libc.so.6", RTLD_NOW | RTLD_LOCAL); assert(libc);
  query original = (query)dlsym(libc, "sysconf"); assert(original);
  query selected = (query)dlsym(RTLD_DEFAULT, "sysconf"); assert(selected && selected != original);
  query alias = (query)dlsym(RTLD_DEFAULT, "__sysconf"); assert(alias && alias != original);
  unsigned long minimum = argv[1] ? strtoul(argv[1], NULL, 10) : getauxval(AT_MINSIGSTKSZ);
  if (!minimum) minimum = 5120;
  long signal = (minimum < 5120 ? 5120 : minimum) * 4;
  if (signal < 16384) signal = 16384;
  long thread = minimum < 16384 ? 16384 : minimum;
  assert(selected(_SC_MINSIGSTKSZ) == (long)minimum);
  assert(selected(_SC_SIGSTKSZ) == signal);
  assert(selected(_SC_THREAD_STACK_MIN) == thread);
  assert(alias(_SC_MINSIGSTKSZ) == (long)minimum);
  assert(alias(_SC_SIGSTKSZ) == signal);
  assert(alias(_SC_THREAD_STACK_MIN) == thread);
  long *early = dlsym(RTLD_DEFAULT, "dsha_constructor_values"); assert(early);
  assert(early[0] == thread && early[1] == (long)minimum && early[2] == signal);
  assert(early[3] == original(_SC_PAGESIZE));
  int names[] = {_SC_PAGESIZE, _SC_OPEN_MAX, _SC_NPROCESSORS_ONLN, _SC_CLK_TCK, _SC_ARG_MAX, -1, 0x7fffffff};
  for (unsigned int i = 0; i < sizeof(names)/sizeof(names[0]); ++i) {
    errno = 777; long expected = original(names[i]); int error = errno;
    errno = 777; assert(selected(names[i]) == expected && errno == error);
    errno = 777; assert(alias(names[i]) == expected && errno == error);
  }
  int before = fds();
  for (int i = 0; i < 1000; ++i) {
    assert(selected(_SC_MINSIGSTKSZ) == (long)minimum);
    assert(selected(_SC_SIGSTKSZ) == signal);
    assert(selected(_SC_THREAD_STACK_MIN) == thread);
  }
  assert(fds() == before);
  printf("{\"glibc\":\"%s\",\"minimum\":%lu,\"signal\":%ld,\"thread\":%ld,\"constructor\":true,\"forwardingAndErrno\":true,\"fdStable\":true}\n", gnu_get_libc_version(), minimum, signal, thread);
  return 0;
}
'''


def main(output):
    if os.name != 'posix' or not shutil.which('cc'):
        raise SystemExit('Requires a real GNU/Linux compiler/runtime; no Windows emulation claim')
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='proroot-real-glibc-', dir=output) as directory:
        work = Path(directory)
        (work / 'vendor.c').write_text(VENDOR)
        (work / 'probe.c').write_text(PROBE)
        subprocess.run(['cc', '-shared', '-fPIC', '-O2', str(work / 'vendor.c'), '-Wl,-soname,libproroot-runtime.so', '-o', str(work / 'libproroot-runtime.so')], check=True)
        subprocess.run(['cc', '-O2', '-Wall', '-Wextra', '-Werror', str(work / 'probe.c'), '-ldl', '-o', str(work / 'probe')], check=True)
        def build(name, auxiliary=None):
            target = work / name
            command = ['cc', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', '-D_GNU_SOURCE', '-DDSHA_SYSCONF_HOST_TEST', '-shared', '-fPIC', '-nostdlib', '-fno-stack-protector', '-Wl,-z,now', str(SOURCE), '-o', str(target)]
            if auxiliary is not None: command.insert(1, '-DDSHA_SYSCONF_TEST_AUX_FILE="' + str(auxiliary) + '"')
            subprocess.run(command, check=True)
            return target
        normal = build('proxy.so')
        def run(proxy, minimum=None, library_path=None):
            env = dict(os.environ, LD_PRELOAD=str(proxy) + ':' + str(work / 'libproroot-runtime.so'))
            env.pop('LD_LIBRARY_PATH', None)
            if library_path is not None: env['LD_LIBRARY_PATH'] = str(library_path)
            return subprocess.run([str(work / 'probe')] + ([] if minimum is None else [str(minimum)]), env=env, text=True, capture_output=True)
        rows = []
        def success(proxy, minimum=None):
            result = run(proxy, minimum)
            if result.returncode: raise AssertionError((result.returncode, result.stdout, result.stderr))
            rows.append(json.loads(result.stdout))
        success(normal)
        poison = work / 'poison'; poison.mkdir()
        (poison / 'vendor.c').write_text('#include <unistd.h>\n__attribute__((constructor)) static void wrong_dependency(void) { _exit(99); }\n')
        subprocess.run(['cc', '-shared', '-fPIC', str(poison / 'vendor.c'), '-Wl,-soname,libproroot-runtime.so', '-o', str(poison / 'libproroot-runtime.so')], check=True)
        result = run(normal, library_path=poison)
        assert result.returncode == 0, ('explicit direct preload path lost priority', result)
        aux = work / 'auxv'
        for name, payload, minimum in (
            ('real-value', struct.pack('<QQQQ', 51, 32768, 0, 0), 32768),
            ('missing-kernel-value', struct.pack('<QQQQ', 6, 4096, 0, 0), 5120),
            ('small-kernel-value', struct.pack('<QQQQ', 51, 2048, 0, 0), 2048)):
            aux.write_bytes(payload)
            success(build(name + '.so', aux), minimum)
        failures = []
        for name, payload in (
            ('zero', struct.pack('<QQQQ', 51, 0, 0, 0)),
            ('duplicate', struct.pack('<QQQQQQ', 51, 5120, 51, 5120, 0, 0)),
            ('overflow', struct.pack('<QQQQ', 51, 1 << 63, 0, 0)),
            ('truncated', b'\x00' * 7),
            ('unterminated', struct.pack('<QQ', 6, 4096) * 256)):
            aux.write_bytes(payload)
            result = run(build(name + '.so', aux))
            assert result.returncode == 126 and 'DSHA_PROROOT_SYSCONF_UNAVAILABLE' in result.stderr, (name, result)
            failures.append(name)
        aux.unlink()
        result = run(build('unreadable.so', aux))
        assert result.returncode == 126 and 'DSHA_PROROOT_SYSCONF_UNAVAILABLE' in result.stderr
        failures.append('unreadable')
    report = dict(realGlibcCases=rows, rejectedAuxiliaryVectors=failures, explicitDirectPreloadBeforeLibraryPath=True, productionVendorExecuted=False,
                  limitation='Real GNU libc host forwarding and same C parser; ARM64 vendor loader integration needs the authorized device check.')
    (output / 'host-result.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    main(parser.parse_args().output.resolve())
