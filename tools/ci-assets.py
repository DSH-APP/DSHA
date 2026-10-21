#!/usr/bin/env python3
"""传递经源码摘要核对的离线构建输入；不包含密钥、用户数据或 APK。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NAMES = ["offline-rootfs.bin", "dsh-runtime.bin", "dsh-runtime.inputs.json",
         "ubuntu-tools.bin", "ubuntu-tools.inputs.json", "pnpm-runtime.bin",
         "python-support.bin", "glibc-python.tar.gz", "adb-wheels.tar.gz"]


def bounded(name):
    if "\\" in name or ":" in name or any(part in ("", ".", "..") for part in name.split("/")):
        raise ValueError("CI_ASSET_PATH")
    target = ROOT / name
    if not target.resolve().is_relative_to(ROOT.resolve()):
        raise ValueError("CI_ASSET_OUTSIDE")
    for parent in [target, *target.parents]:
        if parent == ROOT:
            break
        if parent.is_symlink():
            raise ValueError("CI_ASSET_LINK")
    return target


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1048576), b""):
            value.update(chunk)
    return value.hexdigest()


def allowed():
    names = {"app/src/main/assets/" + name for name in NAMES}
    lock = json.loads((ROOT / "tools/recovery-runtime/lock.json").read_text(encoding="utf-8"))
    names.update("tools/recovery-runtime/archives/" + row["asset"] for row in lock["archives"])
    return names


def required_lock(name, error):
    try:
        return json.loads(bounded(name).read_text(encoding="utf-8"))
    except FileNotFoundError as missing:
        raise ValueError(error) from missing


def separate_input_pins():
    # Only these two build inputs are outside runtime-descriptor.inputs.
    # Never infer a missing pin from the local file being checked.
    lock = required_lock("tools/ci-build-inputs.lock.json", "CI_BUILD_INPUT_LOCK_MISSING")
    if not isinstance(lock, dict):
        raise ValueError("CI_BUILD_INPUT_LOCK_INVALID")
    rows = lock.get("archives")
    if lock.get("schema") != 1 or not isinstance(rows, list) or len(rows) != 1:
        raise ValueError("CI_BUILD_INPUT_LOCK_INVALID")
    python = rows[0]
    if not isinstance(python, dict) or python.get("asset") != "python-support.bin" \
            or not isinstance(python.get("sha256"), str) \
            or not re.fullmatch(r"[a-f0-9]{64}", python.get("sha256", "")) \
            or type(python.get("bytes")) is not int or not 0 < python["bytes"] <= 2 * 1024 ** 3:
        raise ValueError("CI_BUILD_INPUT_LOCK_INVALID")
    adb = required_lock("app/src/main/assets/adb-wheels.lock.json", "CI_ADB_WHEELS_LOCK_MISSING")
    if not isinstance(adb, dict) or adb.get("schema") != 1 or not isinstance(adb.get("archiveSha256"), str) \
            or not re.fullmatch(r"[a-f0-9]{64}", adb["archiveSha256"]):
        raise ValueError("CI_ADB_WHEELS_LOCK_INVALID")
    return {"python-support.bin": (python["sha256"], python["bytes"]),
            "adb-wheels.tar.gz": (adb["archiveSha256"], None)}


def verify_sources():
    descriptor = json.loads((ROOT / "app/src/main/assets/runtime-descriptor.json").read_text(encoding="utf-8"))
    separate = separate_input_pins()
    for name in NAMES:
        if name.endswith(".inputs.json"):
            continue
        path = bounded("app/src/main/assets/" + name)
        expected = descriptor["inputs"].get(name)
        size = None
        if name in separate:
            pinned, size = separate[name]
            if expected is not None and expected != pinned:
                raise ValueError("CI_ASSET_PIN_CONFLICT: " + name)
            expected = pinned
        if not path.is_file() or path.is_symlink():
            raise ValueError("CI_ASSET_NOT_REGULAR: " + name)
        if not isinstance(expected, str) or not re.fullmatch(r"[a-f0-9]{64}", expected) \
                or size is not None and path.stat().st_size != size or digest(path) != expected:
            raise ValueError("CI_ASSET_SOURCE_MISMATCH: " + name)
    lock = json.loads((ROOT / "tools/recovery-runtime/lock.json").read_text(encoding="utf-8"))
    for row in lock["archives"]:
        if digest(bounded("tools/recovery-runtime/archives/" + row["asset"])) != row["sha256"]:
            raise ValueError("CI_RECOVERY_ARCHIVE_MISMATCH")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--pack", type=Path)
    modes.add_argument("--url")
    parser.add_argument("--sha256")
    args = parser.parse_args(argv)
    names = allowed()
    if args.pack:
        verify_sources()
        args.pack.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.pack, "x", zipfile.ZIP_STORED) as output:
            for name in sorted(names):
                path = bounded(name)
                if path.is_symlink() or not path.is_file():
                    raise ValueError("CI_ASSET_NOT_REGULAR: " + name)
                output.write(path, name)
        print(json.dumps({"sha256": digest(args.pack), "bytes": args.pack.stat().st_size}))
        return
    if not args.url.startswith("https://") or not re.fullmatch(r"[a-f0-9]{64}", args.sha256 or ""):
        parser.error("HTTPS 输入包及固定 SHA-256 必需")
    with tempfile.TemporaryDirectory(prefix="ci-assets-", dir=bounded("app")) as directory:
        package = Path(directory) / "inputs.zip"
        total = 0
        with urllib.request.urlopen(args.url, timeout=60) as stream, package.open("wb") as output:
            for chunk in iter(lambda: stream.read(1048576), b""):
                total += len(chunk)
                if total > 2 * 1024 ** 3:
                    raise ValueError("CI_ASSET_DOWNLOAD_LIMIT")
                output.write(chunk)
        if digest(package) != args.sha256:
            raise ValueError("CI_ASSET_BUNDLE_SHA256")
        with zipfile.ZipFile(package) as archive:
            entries = archive.infolist()
            if len(entries) != len(names) or {entry.filename for entry in entries} != names \
                    or sum(entry.file_size for entry in entries) > 2 * 1024 ** 3:
                raise ValueError("CI_ASSET_MEMBER_SET")
            # No archive-selected pathname or directory extraction.
            for name in sorted(names):
                target = bounded(name)
                if target.is_symlink():
                    raise ValueError("CI_ASSET_TARGET_LINK")
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(name) as source, target.open("wb") as output:
                    for chunk in iter(lambda: source.read(1048576), b""):
                        output.write(chunk)
    verify_sources()
    print("PASS locked CI asset inputs; source builders must verify their own recipe metadata")


if __name__ == "__main__":
    main()
