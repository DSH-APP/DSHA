# Independent proroot GNU stack-query proxy

Android 13 proroot v1.2.8 runs exhibited glibc signal-stack and pthread-stack
assertions. Both assertion expressions read `dl_minsigstacksize`; a missing
clean-room-loader initialization is a supported inference, not a source-level
diagnosis of the proprietary loader. This shim avoids private glibc structures: only GNU
`sysconf` / `__sysconf` names 75, 249 and 250 use the kernel's real
`/proc/self/auxv`. Every other query forwards to the original `sysconf` through
`RTLD_NEXT`, retaining the caller's errno across symbol lookup.

The ABI constants and formulas follow the official glibc sources linked in
`abi.lock.json`. A complete auxiliary vector without type 51 uses glibc's
AArch64 default 5120. Unreadable, zero, duplicate, overflowing, truncated or
unterminated input exits with code 126 and a fixed diagnostic. No user file is
written. There is no constructor and no private offset or memory patch.

`libdsha-proroot-compat.so` is a GNU AArch64 shared object built with `-nostdlib`;
it has no `DT_NEEDED`, interpreter or library search path. The freestanding
`libdsha-proroot-linker.so` is a static PIE with no dependencies or relocations.
Only proroot selects this entry adapter. `PROROOT_LIB_PATH` continues to name the
original vendor runtime, so its path hooks remain a **direct** preload. The
adapter locates its current instruction in the unique `r-xp` file mapping in
`/proc/self/maps`. It requires the exact own entry name inside the framework
`/data/app/.../lib/arm64/` directory, and checks the mapped inode against a
NOFOLLOW-opened regular file. The entry and four companions must be owned by
another UID and have no group/other write bits. This makes the directory read-only
to the application UID. Fixed bridge/linker/shim/runtime names are derived from
that directory; there is no argv, environment or kernel-executable path fallback.
The adapter rejects leaf links and mismatched preload
arguments, then executes the original bridge and linker with one additional
leading `--preload <shim>`. Original business arguments and the environment are
passed unchanged. There is no environment override for these derived paths.
The five vendor libraries retain their original names and locked bytes.

The original vendor launcher conditionally adds a second preload for
`<rootfs>/usr/lib/aarch64-linux-gnu/libgcc_s.so.1` when that file is readable.
The adapter preserves this one optional original pair, requiring the value to
equal `PROROOT_ROOTFS` plus that fixed suffix. It permits no third preload, other
filename or different root. `PROROOT_ROOTFS` is never used to locate the own shim
or the vendor native binaries. Android's full Ubuntu root exercised this branch;
the earlier `/` shell fixture lacked that ARM library and did not cover it.
The previous PC-only candidate rejected this legitimate pair at `ARGV_PRELOAD`.
Current diagnostics distinguish the vendor value, optional libgcc value and
excess preload count. Process recognition removes the optional pair only when
its root exactly matches the actual managed node/bash host executable.

The first proxy-only device candidate passed the glibc assertions but failed
guest path lookup (`/root` missing, `dsh` not found). Loading the vendor runtime
only as a proxy dependency did not preserve its direct-preload path hooks.
The current adapter corrects that wiring. The original ARM64 loader fixture
likewise returns 101 for a dependency-only lookup and 0 for two direct preloads.
The first adapter then failed its `/proc/self/exe` identity check on Android.
An actual shell-domain probe confirmed that both vendor launcher modes map the
entry as a separate executable file while the kernel executable has another
identity; neither has a bridge mapping. Current-PC file identity addresses that
observed difference. Shell probe success does not establish app-domain success.

Every failure prints only the fixed `DSHA_PROROOT_PRELOAD_ENTRY_UNAVAILABLE`
marker, a fixed `stage` and the positive raw syscall `errno` (zero for a rejected
identity or argument shape). It never includes path or business argument values.
The fstat layout and ARM64-specific `O_NOFOLLOW` value follow the locked NDK's
Linux UAPI headers; they do not use bionic's sysconf enum or private libc fields.

Build, then verify reproducibility without replacing the shipped output:

```text
python tools/proroot-compat/build.py --ndk <NDK-26.3.11579264> --output app/src/main/jniLibs/arm64-v8a/libdsha-proroot-compat.so
python tools/proroot-compat/build.py --ndk <NDK-26.3.11579264> --output app/src/main/jniLibs/arm64-v8a/libdsha-proroot-compat.so --check
python tools/test-proroot-compat.py
```

Run the same C parser and actual GNU libc forwarding/constructor checks on a
GNU/Linux host with a C compiler. This is separate from Android execution:

```text
python3 tools/proroot-compat/test-host.py --output <private-host-output>
```

The host test uses two explicit direct preloads and verifies constructor calls,
GNU enum values, non-target forwarding/errno, malformed auxv and descriptor
stability. It does not execute proprietary ARM64 code. A separate Windows NDK /
WSL fixture executes the unchanged ARM64 loader and bridge through QEMU:

```text
python tools/proroot-compat/test-arm64.py --ndk <NDK-26.3.11579264> --wsl-distro <GNU-Linux-with-qemu-aarch64-static-and-arm64-glibc> --output <private-arm64-output>
```

That fixture exercises repeated direct preloads and the actual shipped ARM64
shim's three stack queries plus page-size/error forwarding, including the real
ARM64 libgcc as the vendor's optional additional preload. The original bridge
maps the shipped entry and its exact next exec arguments are checked. Without
ARM64 binfmt in WSL the second kernel exec returns ENOEXEC; the fixture records
this limitation. Entry tests create isolated root-owned framework-shaped files
in the WSL distribution and execute QEMU as `nobody`; writable/owned companions,
leaf symlinks and an entry outside the framework directory must be rejected.
Android vendor path hooks, Node, Python, Web authentication,
normal stopping and both cold-loader modes still require authorized formal-package
device checks. A successful proot fallback is not evidence that proroot works.
