#!/usr/bin/env python3
"""传递经源码摘要核对的离线构建输入；不包含密钥、用户数据或 APK。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NAMES = ["offline-rootfs.bin", "dsh-runtime.bin", "dsh-runtime.inputs.json",
         "ubuntu-tools.bin", "ubuntu-tools.inputs.json", "pnpm-runtime.bin",
         "python-support.bin", "glibc-python.tar.gz", "adb-wheels.tar.gz",
         "gh-cli.tar.gz"]


def check_name(name):
    if any(char in name for char in ("\\", ":", "\x00")) \
            or any(part in ("", ".", "..") for part in name.split("/")):
        raise ValueError("CI_ASSET_PATH")


def bounded(name, root=None):
    check_name(name)
    root = ROOT if root is None else root
    target = root / name
    if not target.resolve().is_relative_to(root.resolve()):
        raise ValueError("CI_ASSET_OUTSIDE")
    for parent in [target, *target.parents]:
        if parent == root:
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


def verify_sources(inputs_root=None):
    descriptor = json.loads((ROOT / "app/src/main/assets/runtime-descriptor.json").read_text(encoding="utf-8"))
    separate = separate_input_pins()
    for name in NAMES:
        if name.endswith(".inputs.json"):
            continue
        path = bounded("app/src/main/assets/" + name, inputs_root)
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
        path = bounded("tools/recovery-runtime/archives/" + row["asset"], inputs_root)
        if not path.is_file() or path.is_symlink() or digest(path) != row["sha256"]:
            raise ValueError("CI_RECOVERY_ARCHIVE_MISMATCH")


def require_https(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username is not None \
            or parsed.password is not None or "\\" in url or any(char.isspace() for char in url):
        raise ValueError("CI_ASSET_HTTPS_REQUIRED")


class HttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        require_https(newurl)
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None:
            for name, _ in redirected.header_items():
                if name.lower() in ("authorization", "proxy-authorization", "cookie"):
                    redirected.remove_header(name)
        return redirected


def download(url, package):
    require_https(url)
    request = urllib.request.Request(url)
    if re.fullmatch(r"https://api\.github\.com/repos/[A-Za-z0-9-]+/[A-Za-z0-9_.-]+/releases/assets/[1-9][0-9]*", url) \
            and urllib.parse.urlsplit(url).path.split("/")[3] not in (".", ".."):
        request.add_header("Accept", "application/octet-stream")
        token = os.environ.get("GITHUB_TOKEN")
        if token:
            request.add_unredirected_header("Authorization", "Bearer " + token)
    opener = urllib.request.build_opener(HttpsRedirect())
    total = 0
    with opener.open(request, timeout=60) as stream, package.open("wb") as output:
        for chunk in iter(lambda: stream.read(1048576), b""):
            total += len(chunk)
            if total > 2 * 1024 ** 3:
                raise ValueError("CI_ASSET_DOWNLOAD_LIMIT")
            output.write(chunk)


def selected_members(archive, names, source_archive):
    entries = archive.infolist()
    if not source_archive and (len(entries) != len(names) or {entry.filename for entry in entries} != names):
        raise ValueError("CI_ASSET_MEMBER_SET")
    selected, prefixes, seen = {}, set(), set()
    for entry in entries:
        filename = entry.filename[:-1] if entry.is_dir() else entry.filename
        check_name(filename)
        if entry.orig_filename != entry.filename or filename in seen:
            raise ValueError("CI_ASSET_MEMBER_SET")
        seen.add(filename)
        kind = stat.S_IFMT(entry.external_attr >> 16)
        if kind not in (0, stat.S_IFDIR if entry.is_dir() else stat.S_IFREG):
            raise ValueError("CI_ASSET_MEMBER_TYPE")
        for name in names:
            if filename == name or source_archive and filename.endswith("/" + name):
                if entry.is_dir() or name in selected:
                    raise ValueError("CI_ASSET_MEMBER_SET")
                selected[name] = entry
                prefixes.add(filename[:-len(name)])
    if set(selected) != names or len(prefixes) != 1 \
            or sum(entry.file_size for entry in selected.values()) > 2 * 1024 ** 3:
        raise ValueError("CI_ASSET_MEMBER_SET")
    return selected


def restore(package, sha256, source_archive, directory):
    if package.stat().st_size > 2 * 1024 ** 3:
        raise ValueError("CI_ASSET_DOWNLOAD_LIMIT")
    if digest(package) != sha256:
        raise ValueError("CI_ASSET_BUNDLE_SHA256")
    prepared = Path(directory) / "prepared"
    with zipfile.ZipFile(package) as archive:
        members = selected_members(archive, allowed(), source_archive)
        # 只用源码白名单选择输出路径；额外源码成员不会被读取或执行。
        for name, entry in sorted(members.items()):
            candidate = bounded(name, prepared)
            candidate.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(entry) as source, candidate.open("xb") as output:
                for chunk in iter(lambda: source.read(1048576), b""):
                    output.write(chunk)
    # 所有候选先通过当前源码的 pin，任何损坏都不会覆盖原输入。
    verify_sources(prepared)
    targets = {name: bounded(name) for name in members}
    for name, target in targets.items():
        if target.exists() and not target.is_file():
            raise ValueError("CI_ASSET_NOT_REGULAR: " + name)
        target.parent.mkdir(parents=True, exist_ok=True)
    for name, target in targets.items():
        (prepared / name).replace(target)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--pack", type=Path)
    modes.add_argument("--url")
    modes.add_argument("--archive", type=Path, help="复用本地 ZIP，仍需固定 SHA-256")
    parser.add_argument("--source-archive", action="store_true", help="仅恢复完整源码 ZIP 中的固定输入子集")
    parser.add_argument("--sha256")
    args = parser.parse_args(argv)
    names = allowed()
    if args.pack:
        if args.source_archive:
            parser.error("--source-archive 仅用于恢复")
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
    if not re.fullmatch(r"[a-f0-9]{64}", args.sha256 or ""):
        parser.error("输入 ZIP 的固定 SHA-256 必需")
    if args.url:
        require_https(args.url)
    with tempfile.TemporaryDirectory(prefix="ci-assets-", dir=bounded("app")) as directory:
        package = args.archive or Path(directory) / "inputs.zip"
        if args.url:
            download(args.url, package)
        restore(package, args.sha256, args.source_archive, directory)
    print("PASS locked CI asset inputs; source builders must verify their own recipe metadata")


if __name__ == "__main__":
    main()
