#!/usr/bin/env python3
"""Real GNU libc fcntl checks: the proxy must restore kernel errno that proroot v1.2.8 rewrites to EPERM.

The fake vendor reproduces the disassembled v1.2.8 wrapper (raw syscall wrapper already returned -1 and
set errno; the wrapper then stores -(-1) == EPERM) and counts F_DUPFD/F_DUPFD_CLOEXEC so dup forwarding
is observable. Preload order matches production: proxy first, vendor second.

Normal GNU/Linux hosts use LD_PRELOAD directly. Inside a proroot guest (PROROOT_ROOTFS set) the vendor
loader ignores LD_PRELOAD, so the same binaries run under dynamic qemu-aarch64 with real host paths.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'tools/proroot-compat/stack-sysconf.c'
VENDOR = r'''
#define _GNU_SOURCE
#include <errno.h>
#include <stdarg.h>
#include <sys/syscall.h>
#include <unistd.h>
int dsha_fake_vendor_dups;
int fcntl(int fd, int command, ...) {
  va_list list; va_start(list, command); long argument = va_arg(list, long); va_end(list);
  long result = syscall(SYS_fcntl, fd, command, argument);   /* -1 + errno, like proroot_raw_syscall6 */
  if (result < 0) { errno = (int)-result; return -1; }         /* v1.2.8 bug: errno = 1 (EPERM) */
  if (command == 0 || command == 1030) ++dsha_fake_vendor_dups;
  return (int)result;
}
'''
PROBE = r'''
#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
static struct flock range(short type) { struct flock value = {0}; value.l_type = type; value.l_whence = SEEK_SET; value.l_len = 1; return value; }
int main(int argc, char **argv) {
  int expect_bug = argc > 2 && strcmp(argv[2], "vendor-only") == 0;
  int fd = open(argv[1], O_RDWR | O_CREAT, 0600); assert(fd >= 0);
  int *dups = dlsym(RTLD_DEFAULT, "dsha_fake_vendor_dups"); assert(dups);
  int ready[2], done[2]; assert(pipe(ready) == 0 && pipe(done) == 0);
  pid_t child = fork(); assert(child >= 0);
  if (child == 0) {                       /* holder: raw syscall, independent of both libraries */
    struct flock held = range(F_WRLCK);
    char byte = 1;
    if (syscall(SYS_fcntl, fd, F_SETLK, &held) != 0) _exit(2);
    if (write(ready[1], &byte, 1) != 1 || read(done[0], &byte, 1) != 1) _exit(3);
    _exit(0);
  }
  char byte; assert(read(ready[0], &byte, 1) == 1);
  struct flock want = range(F_WRLCK);
  errno = 0; int contended = fcntl(fd, F_SETLK, &want); int contended_errno = errno;
  struct flock query = range(F_WRLCK);
  errno = 777; int queried = fcntl(fd, F_GETLK, &query); int queried_errno = errno;
  errno = 0; int bad_fd = fcntl(1 << 20, F_GETFL); int bad_fd_errno = errno;
  struct flock invalid = range(77);
  errno = 0; int bad_type = fcntl(fd, F_SETLK, &invalid); int bad_type_errno = errno;
  errno = 777; int flags = fcntl(fd, F_GETFL); int flags_errno = errno;
  int before = *dups;
  errno = 777; int copy = fcntl(fd, F_DUPFD_CLOEXEC, 10); int copy_errno = errno;
  int forwarded = *dups - before;
  assert(write(done[1], &byte, 1) == 1);
  int status; assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
  errno = 0; int free_lock = fcntl(fd, F_SETLK, &want); int free_errno = errno;
  printf("{\"contended\":[%d,%d],\"getlk\":[%d,%d,%d],\"badFd\":[%d,%d],\"badType\":[%d,%d],"
         "\"getfl\":[%d,%d],\"dup\":[%d,%d,%d],\"afterRelease\":[%d,%d]}\n",
         contended, contended_errno, queried, queried_errno, query.l_type == F_WRLCK && query.l_pid == child,
         bad_fd, bad_fd_errno, bad_type, bad_type_errno, flags >= 0, flags_errno, copy >= 10, copy_errno, forwarded,
         free_lock, free_errno);
  if (expect_bug) return contended == -1 && contended_errno == EPERM && bad_fd_errno == EPERM ? 0 : 41;
  assert(contended == -1 && (contended_errno == EAGAIN || contended_errno == EACCES));
  assert(queried == 0 && queried_errno == 777 && query.l_type == F_WRLCK && query.l_pid == child);
  assert(bad_fd == -1 && bad_fd_errno == EBADF);
  assert(bad_type == -1 && bad_type_errno == EINVAL);
  assert(flags >= 0 && flags_errno == 777);
  assert(copy >= 10 && copy_errno == 777 && forwarded == 1);
  assert(free_lock == 0 && free_errno == 0);
  return 0;
}
'''


def main(output):
    if os.name != 'posix' or not shutil.which('cc'):
        raise SystemExit('Requires a real GNU/Linux compiler/runtime')
    machine = platform.machine()
    if machine not in ('aarch64', 'x86_64'):
        raise SystemExit('Requires GNU AArch64 or x86_64 host')
    guest = os.environ.get('PROROOT_ROOTFS')
    if guest and (machine != 'aarch64' or not shutil.which('qemu-aarch64')):
        raise SystemExit('Inside proroot this test needs dynamic qemu-aarch64 (LD_PRELOAD is ignored by the vendor loader)')
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='proroot-fcntl-', dir=output) as directory:
        work = Path(directory)
        (work / 'vendor.c').write_text(VENDOR)
        (work / 'probe.c').write_text(PROBE)
        cc = ['cc', '-O2', '-Wall', '-Wextra', '-Werror']
        subprocess.run(cc + ['-shared', '-fPIC', str(work / 'vendor.c'), '-Wl,-soname,libproroot-runtime.so',
                             '-o', str(work / 'libproroot-runtime.so')], check=True)
        subprocess.run(cc + [str(work / 'probe.c'), '-ldl', '-o', str(work / 'probe')], check=True)
        subprocess.run(['cc', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', '-DDSHA_SYSCONF_HOST_TEST', '-shared', '-fPIC',
                        '-nostdlib', '-fno-stack-protector', '-fno-builtin', '-Wl,-z,now', str(SOURCE),
                        '-o', str(work / 'proxy.so')], check=True)
        def real(path):
            return guest + str(path) if guest else str(path)
        def run(preload, mode):
            target = work / ('lock-' + mode)
            arguments = [real(work / 'probe'), real(target), mode]
            if guest:
                library = guest + '/lib/aarch64-linux-gnu'
                command = ['qemu-aarch64', '-L', guest, '-E', 'LD_LIBRARY_PATH=' + library,
                           '-E', 'LD_PRELOAD=' + ':'.join(real(item) for item in preload), str(work / 'probe'), *arguments[1:]]
                env = dict(os.environ)
            else:
                command = arguments
                env = dict(os.environ, LD_PRELOAD=':'.join(str(item) for item in preload))
            result = subprocess.run(command, env=env, text=True, capture_output=True)
            if result.returncode:
                raise AssertionError((mode, result.returncode, result.stdout, result.stderr))
            return json.loads(result.stdout)
        vendor = work / 'libproroot-runtime.so'
        bug = run([vendor], 'vendor-only')
        fixed = run([work / 'proxy.so', vendor], 'proxy-first')
    report = dict(vendorOnlyReproducesEperm=bug, proxyFirstRestoresKernelErrno=fixed, dupForwardedToVendor=fixed['dup'][2] == 1,
                  runner='qemu-aarch64 inside proroot guest' if guest else 'native LD_PRELOAD', productionVendorExecuted=False,
                  limitation='Fake vendor reproduces the disassembled v1.2.8 errno path; production loader order needs the device check.')
    (output / 'fcntl-result.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    main(parser.parse_args().output.resolve())
