#!/usr/bin/env python3
"""跨平台重编会话启动器；显式输出，锁定 NDK/API 和页对齐。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
NDK_VERSION = json.loads((ROOT / "ci/toolchain.lock.json").read_text(encoding="utf-8"))["ndk"]


def build(ndk, output, check=False):
    properties = (ndk / "source.properties").read_text(encoding="utf-8")
    if "Pkg.Revision = " + NDK_VERSION not in properties:
        raise ValueError("NATIVE_SESSION_NDK_VERSION")
    host = {"Windows": "windows-x86_64", "Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}.get(platform.system())
    if not host:
        raise ValueError("NATIVE_SESSION_BUILD_HOST")
    suffix = ".exe" if platform.system() == "Windows" else ""
    tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="dsha-session-build-", dir=output.parent) as directory:
        candidate = Path(directory) / "libdsha-session.so"
        command = [str(tools / ("clang" + suffix)), "--target=aarch64-linux-android23",
                   "-std=c11", "-D_GNU_SOURCE", "-O2", "-fPIE", "-pie",
                   "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2",
                   "-ffile-prefix-map=" + str(ROOT) + "=/src",
                   "-Wl,-z,relro", "-Wl,-z,now", "-Wl,-z,max-page-size=16384",
                   "-Wl,-z,common-page-size=4096", str(Path(__file__).with_name("session-launcher.c")),
                   "-o", str(candidate)]
        subprocess.run(command, check=True)
        subprocess.run([str(tools / ("llvm-strip" + suffix)), "--strip-unneeded", str(candidate)], check=True)
        subprocess.run([str(tools / ("llvm-readelf" + suffix)), "--program-headers", "--wide", str(candidate)], check=True)
        content = candidate.read_bytes()
        if check:
            if not output.is_file() or output.read_bytes() != content:
                raise ValueError("NATIVE_SESSION_REBUILD_MISMATCH")
        else:
            candidate.replace(output)
    value = hashlib.sha256(content).hexdigest()
    print("PASS native session reproducible bytes" if check else "native session sha256", value)
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, default=os.environ.get("ANDROID_NDK_HOME"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check", action="store_true", help="Rebuild in a temporary directory and compare without replacing the shipped ELF")
    args = parser.parse_args()
    if args.ndk is None:
        parser.error("需要 --ndk 或 ANDROID_NDK_HOME")
    if args.output is None and not args.check:
        parser.error("Write mode requires explicit --output; use --check for read-only verification")
    output = args.output or ROOT / "app/src/main/jniLibs/arm64-v8a/libdsha-session.so"
    build(args.ndk.resolve(), output.resolve(), check=args.check)


if __name__ == "__main__":
    main()
