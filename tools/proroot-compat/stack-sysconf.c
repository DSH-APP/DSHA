/* SPDX-License-Identifier: MIT
 * Narrow GNU/AArch64 sysconf compatibility for the original proroot loader.
 * No private glibc offsets, constructors, libc dependency, or vendor changes.
 */
typedef unsigned long word;
typedef long (*sysconf_function)(int);
extern void *dlsym(void *, const char *);
extern int *__errno_location(void);

/* GNU glibc ABI, deliberately not Android/Bionic's <unistd.h> enum. */
enum { GNU_SC_THREAD_STACK_MIN = 75, GNU_SC_MINSIGSTKSZ = 249, GNU_SC_SIGSTKSZ = 250 };
enum { GNU_AARCH64_MINSIGSTKSZ = 5120, GNU_AARCH64_SIGSTKSZ = 16384,
       GNU_AARCH64_PTHREAD_STACK_MIN = 16384 };
enum { AUX_NULL = 0, AUX_MINSIGSTKSZ = 51, MAX_AUX_RECORDS = 256 };

#if defined(__aarch64__)
enum { NR_OPENAT = 56, NR_CLOSE = 57, NR_READ = 63, NR_WRITE = 64, NR_EXIT_GROUP = 94 };
static long native_call(long number, word a, word b, word c, word d) {
  register word x0 __asm__("x0") = a;
  register word x1 __asm__("x1") = b;
  register word x2 __asm__("x2") = c;
  register word x3 __asm__("x3") = d;
  register long x8 __asm__("x8") = number;
  __asm__ volatile("svc 0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x8) : "memory", "cc");
  return (long)x0;
}
#elif defined(__x86_64__) && defined(DSHA_SYSCONF_HOST_TEST)
/* Host-only real-glibc forwarding tests. The packaged build permits ARM64 only. */
enum { NR_OPENAT = 257, NR_CLOSE = 3, NR_READ = 0, NR_WRITE = 1, NR_EXIT_GROUP = 231 };
static long native_call(long number, word a, word b, word c, word d) {
  register word r10 __asm__("r10") = d;
  long result;
  __asm__ volatile("syscall" : "=a"(result) : "a"(number), "D"(a), "S"(b), "d"(c), "r"(r10) : "rcx", "r11", "memory", "cc");
  return result;
}
#else
#error This proxy requires GNU AArch64; x86_64 is allowed only for host tests.
#endif

__attribute__((noreturn)) static void unavailable(void) {
  static const char message[] = "DSHA_PROROOT_SYSCONF_UNAVAILABLE\n";
  native_call(NR_WRITE, 2, (word)message, sizeof(message) - 1, 0);
  native_call(NR_EXIT_GROUP, 126, 0, 0, 0);
  __builtin_unreachable();
}

/* Read the kernel's real auxiliary vector, bypassing guest libc interposition.
 * A completely read AT_NULL with no type 51 uses the same AArch64 ABI default
 * as glibc's _dl_parse_auxv. An unreadable/truncated vector is not "missing".
 */
static word signal_minimum(void) {
#if defined(DSHA_SYSCONF_HOST_TEST) && defined(DSHA_SYSCONF_TEST_AUX_FILE)
  static const char path[] = DSHA_SYSCONF_TEST_AUX_FILE;
#else
  static const char path[] = "/proc/self/auxv";
#endif
  long fd = native_call(NR_OPENAT, (word)-100, (word)path, 0x80000, 0);
  if (fd < 0) unavailable();
  word minimum = GNU_AARCH64_MINSIGSTKSZ;
  int found = 0, ended = 0, invalid = 0;
  for (int index = 0; index < MAX_AUX_RECORDS; ++index) {
    word record[2];
    word used = 0;
    while (used < sizeof(record)) {
      long count = native_call(NR_READ, (word)fd, (word)record + used, sizeof(record) - used, 0);
      if (count == -4) continue; /* EINTR, without changing caller errno. */
      if (count <= 0) { invalid = 1; break; }
      used += (word)count;
    }
    if (invalid) break;
    if (record[0] == AUX_NULL) { ended = 1; break; }
    if (record[0] == AUX_MINSIGSTKSZ) {
      if (found || record[1] == 0 || record[1] > ((word)-1 >> 3)) { invalid = 1; break; }
      minimum = record[1];
      found = 1;
    }
  }
  long closed = native_call(NR_CLOSE, (word)fd, 0, 0, 0);
  if (!ended || invalid || closed < 0) unavailable();
  return minimum;
}

long sysconf(int name);
long __sysconf(int name);

static long dispatch(int name) {
  if (name == GNU_SC_MINSIGSTKSZ || name == GNU_SC_SIGSTKSZ || name == GNU_SC_THREAD_STACK_MIN) {
    word minimum = signal_minimum();
    if (name == GNU_SC_MINSIGSTKSZ) return (long)minimum;
    if (name == GNU_SC_THREAD_STACK_MIN)
      return (long)(minimum < GNU_AARCH64_PTHREAD_STACK_MIN ? GNU_AARCH64_PTHREAD_STACK_MIN : minimum);
    if (minimum < GNU_AARCH64_MINSIGSTKSZ) minimum = GNU_AARCH64_MINSIGSTKSZ;
    word size = minimum * 4;
    return (long)(size < GNU_AARCH64_SIGSTKSZ ? GNU_AARCH64_SIGSTKSZ : size);
  }
  int *error = __errno_location();
  int saved = *error;
  sysconf_function next = (sysconf_function)dlsym((void *)-1L, "sysconf");
  if (!next || next == sysconf || next == __sysconf) unavailable();
  *error = saved;
  return next(name);
}

long sysconf(int name) { return dispatch(name); }
long __sysconf(int name) { return dispatch(name); }
