/* SPDX-License-Identifier: MIT
 * Freestanding entry adapter. Keep the vendor runtime a direct preload and
 * prepend the independent sysconf shim; do not replace vendor path hooks.
 */
typedef unsigned long word;
#if !defined(__aarch64__)
#error This entry is GNU AArch64 only.
#endif
enum { NR_OPENAT = 56, NR_CLOSE = 57, NR_READ = 63, NR_WRITE = 64, NR_FSTAT = 80,
       NR_EXIT_GROUP = 94, NR_GETEUID = 175, NR_MMAP = 222, NR_EXECVE = 221 };
/* AArch64 asm/fcntl.h overrides the generic O_NOFOLLOW value. */
enum { KERNEL_O_NOFOLLOW = 0100000, KERNEL_O_CLOEXEC = 02000000 };
/* Linux AArch64 UAPI asm-generic/stat.h; this is a kernel ABI, not a libc struct. */
struct kernel_stat {
  word device, inode;
  unsigned int mode, links, uid, gid;
  word rdevice, pad1;
  long size;
  int block_size, pad2;
  long blocks, atime; word atime_nsec;
  long mtime; word mtime_nsec;
  long ctime; word ctime_nsec;
  unsigned int unused4, unused5;
};
_Static_assert(sizeof(struct kernel_stat) == 128, "AArch64 kernel stat size");
_Static_assert(__builtin_offsetof(struct kernel_stat, inode) == 8, "AArch64 kernel inode");
_Static_assert(__builtin_offsetof(struct kernel_stat, mode) == 16, "AArch64 kernel mode");
_Static_assert(__builtin_offsetof(struct kernel_stat, uid) == 24, "AArch64 kernel uid");
static long call(long number, word a, word b, word c, word d, word e, word f) {
  register word x0 __asm__("x0") = a;
  register word x1 __asm__("x1") = b;
  register word x2 __asm__("x2") = c;
  register word x3 __asm__("x3") = d;
  register word x4 __asm__("x4") = e;
  register word x5 __asm__("x5") = f;
  register long x8 __asm__("x8") = number;
  __asm__ volatile("svc 0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8) : "memory", "cc");
  return (long)x0;
}
static void diagnostic(const char *text) {
  word length = 0;
  while (text[length]) ++length;
  call(NR_WRITE, 2, (word)text, length, 0, 0, 0);
}
__attribute__((noreturn)) static void fail(const char *stage, long result) {
  diagnostic("DSHA_PROROOT_PRELOAD_ENTRY_UNAVAILABLE stage=");
  diagnostic(stage);
  diagnostic(" errno=");
  word error = result < 0 && result >= -4095 ? (word)-result : 0;
  char digits[20]; word offset = sizeof(digits);
  do { digits[--offset] = '0' + error % 10; error /= 10; } while (error);
  call(NR_WRITE, 2, (word)(digits + offset), sizeof(digits) - offset, 0, 0, 0);
  diagnostic("\n");
  call(NR_EXIT_GROUP, 126, 0, 0, 0, 0, 0);
  __builtin_unreachable();
}
static int equal(const char *a, const char *b) {
  while (*a && *a == *b) { ++a; ++b; }
  return *a == *b;
}
static int starts(const char *text, const char *prefix) {
  while (*prefix) if (*text++ != *prefix++) return 0;
  return 1;
}
static word number(const char **cursor, word radix) {
  word value = 0, digits = 0;
  while (**cursor) {
    unsigned char c = **cursor;
    word digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : radix;
    if (digit >= radix) break;
    if (++digits > 20 || value > (~(word)0 - digit) / radix) fail("MAPS_NUMBER", 0);
    value = value * radix + digit; ++*cursor;
  }
  if (!digits) fail("MAPS_NUMBER", 0);
  return value;
}
static void spaces(const char **cursor) {
  if (**cursor != ' ') fail("MAPS_COLUMNS", 0);
  while (**cursor == ' ') ++*cursor;
}
static word mapping(const char *line, word pc, char *origin, word *matches) {
  const char *at = line;
  word low = number(&at, 16);
  if (*at++ != '-') fail("MAPS_RANGE", 0);
  word high = number(&at, 16);
  if (pc < low || pc >= high) return 0;
  if (++*matches != 1) fail("MAPS_AMBIGUOUS", 0);
  if (!starts(at, " r-xp ")) fail("MAPS_PC_PERMISSIONS", 0);
  at += 5;
  spaces(&at); number(&at, 16); spaces(&at);
  number(&at, 16); if (*at++ != ':') fail("MAPS_DEVICE", 0);
  number(&at, 16); spaces(&at);
  word inode = number(&at, 10); spaces(&at);
  if (!inode || *at != '/') fail("MAPS_PC_ANONYMOUS", 0);
  word length = 0;
  do {
    if (length >= 4095) fail("MAPS_PATH_BOUNDS", 0);
    origin[length++] = *at;
  } while (*at++);
  return inode;
}
static word origin_path(char *origin) {
  /* The vendor launcher may mmap this entry without exec; kernel self/exe is not its identity. */
  word pc; __asm__ volatile("adr %0, ." : "=r"(pc));
  long fd = call(NR_OPENAT, (word)-100, (word)"/proc/self/maps", KERNEL_O_CLOEXEC, 0, 0, 0);
  if (fd < 0) fail("MAPS_OPEN", fd);
  char buffer[1024], line[4096];
  word used = 0, total = 0, matches = 0, inode = 0;
  for (;;) {
    long size = call(NR_READ, (word)fd, (word)buffer, sizeof(buffer), 0, 0, 0);
    if (size < 0) fail("MAPS_READ", size);
    if (!size) break;
    for (long index = 0; index < size; ++index) {
      if (++total > 4194304) fail("MAPS_TOTAL_BOUNDS", 0);
      if (buffer[index] == '\n') {
        line[used] = 0;
        word found = mapping(line, pc, origin, &matches);
        if (found) inode = found;
        used = 0;
      } else {
        if (used >= sizeof(line) - 1) fail("MAPS_LINE_BOUNDS", 0);
        line[used++] = buffer[index];
      }
    }
  }
  long result = call(NR_CLOSE, (word)fd, 0, 0, 0, 0, 0);
  if (result < 0) fail("MAPS_CLOSE", result);
  if (used || matches != 1 || !inode) fail("MAPS_PC_MISSING", 0);
  return inode;
}
static void append(char *out, const char *prefix, word length, const char *name) {
  word at = 0;
  for (; at < length; ++at) out[at] = prefix[at];
  do { if (at >= 4095) fail("PATH_BOUNDS", 0); out[at++] = *name; } while (*name++);
}
static void vendor_libgcc(char *out, char **env) {
  const char *root = (void *)0;
  word count = 0;
  for (; count < 65536 && env[count]; ++count) {
    if (!starts(env[count], "PROROOT_ROOTFS=")) continue;
    if (root) fail("ENV_ROOTFS_DUPLICATE", 0);
    root = env[count] + 15;
  }
  if (count == 65536) fail("ENV_ROOTFS_BOUNDS", 0);
  if (!root) fail("ENV_ROOTFS_MISSING", 0);
  if (*root != '/') fail("ENV_ROOTFS_SHAPE", 0);
  word length = 0;
  while (root[length]) {
    if (length >= 4000) fail("ENV_ROOTFS_BOUNDS", 0);
    if (starts(root + length, "/../") || starts(root + length, "/./")
        || starts(root + length, "//")) fail("ENV_ROOTFS_SHAPE", 0);
    ++length;
  }
  if ((length > 1 && root[length - 1] == '/')
      || (length >= 3 && equal(root + length - 3, "/.."))
      || (length >= 2 && equal(root + length - 2, "/."))) fail("ENV_ROOTFS_SHAPE", 0);
  append(out, root, length, "/usr/lib/aarch64-linux-gnu/libgcc_s.so.1");
}
static void regular(const char *path, word expected_inode, const char *open_stage, const char *close_stage) {
  /* APK framework extraction is read-only to this UID. Refuse leaf links. */
  long fd = call(NR_OPENAT, (word)-100, (word)path, KERNEL_O_CLOEXEC | KERNEL_O_NOFOLLOW, 0, 0, 0);
  if (fd < 0) fail(open_stage, fd);
  struct kernel_stat status;
  long result = call(NR_FSTAT, (word)fd, (word)&status, 0, 0, 0, 0);
  if (result < 0) fail("NATIVE_FSTAT", result);
  if ((status.mode & 0170000) != 0100000 || (status.mode & 0022)
      || status.uid == (word)call(NR_GETEUID, 0, 0, 0, 0, 0, 0)) fail("NATIVE_WRITABLE_OR_TYPE", 0);
  if (expected_inode && status.inode != expected_inode) fail("NATIVE_MAPPING_INODE", 0);
  result = call(NR_CLOSE, (word)fd, 0, 0, 0, 0, 0);
  if (result < 0) fail(close_stage, result);
}

__attribute__((noreturn)) void dsha_preload_entry(word *stack) {
  word argc = stack[0];
  if (argc < 4 || argc > 65536) fail("ARGV_COUNT", 0);
  char **argv = (char **)(stack + 1), **env = argv + argc + 1;
  if (argv[argc] != (void *)0) fail("ARGV_TERMINATOR", 0);
  char self[4096], bridge[4096], linker[4096], shim[4096], runtime[4096];
  word inode = origin_path(self), length = 0;
  while (self[length]) ++length;
  word prefix = 0;
  for (word index = 0; index < length; ++index) {
    if (self[index] == '/') prefix = index + 1;
    if (starts(self + index, "/../") || starts(self + index, "/./") || starts(self + index, "//")) fail("NATIVE_SCOPE", 0);
  }
  if (!starts(self, "/data/app/") || prefix < 11
      || !starts(self + prefix - 11, "/lib/arm64/")
      || !equal(self + prefix, "libdsha-proroot-linker.so")) fail("NATIVE_SCOPE", 0);
  regular(self, inode, "ENTRY_OPEN", "ENTRY_CLOSE");
  append(bridge, self, prefix, "libproroot-bridge.so");
  append(linker, self, prefix, "libproroot-linker.so");
  append(shim, self, prefix, "libdsha-proroot-compat.so");
  append(runtime, self, prefix, "libproroot-runtime.so");
  regular(bridge, 0, "BRIDGE_OPEN", "BRIDGE_CLOSE");
  regular(linker, 0, "LINKER_OPEN", "LINKER_CLOSE");
  regular(shim, 0, "SHIM_OPEN", "SHIM_CLOSE");
  regular(runtime, 0, "RUNTIME_OPEN", "RUNTIME_CLOSE");
  word index = 1, preloads = 0, argv0 = 0;
  char libgcc[4096];
  while (index < argc) {
    if (equal(argv[index], "--argv0")) {
      if (++argv0 != 1 || index + 1 >= argc) fail("ARGV_ARGV0", 0);
      index += 2;
    } else if (equal(argv[index], "--preload")) {
      if (index + 1 >= argc) fail("ARGV_PRELOAD_VALUE", 0);
      if (++preloads == 1) {
        if (!equal(argv[index + 1], runtime)) fail("ARGV_PRELOAD_VENDOR", 0);
      } else if (preloads == 2) {
        vendor_libgcc(libgcc, env);
        if (!equal(argv[index + 1], libgcc)) fail("ARGV_PRELOAD_LIBGCC", 0);
      } else fail("ARGV_PRELOAD_COUNT", 0);
      index += 2;
    } else break; /* Everything from the executable onward is business argv. */
  }
  if (!preloads || index >= argc) fail("ARGV_PAYLOAD", 0);
  word bytes = (argc + 4) * sizeof(char *);
  long memory = call(NR_MMAP, 0, bytes, 3, 0x22, (word)-1, 0);
  if ((word)memory >= (word)-4095) fail("ARGV_ALLOCATE", memory);
  char **next = (char **)memory;
  next[0] = bridge;
  next[1] = linker;
  next[2] = "--preload";
  next[3] = shim;
  for (word at = 1; at < argc; ++at) next[at + 3] = argv[at];
  next[argc + 3] = (void *)0;
  long result = call(NR_EXECVE, (word)bridge, (word)next, (word)env, 0, 0, 0);
  fail("BRIDGE_EXEC", result);
}
__asm__(".global _start\n_start:\nmov x0, sp\nb dsha_preload_entry\n");
