#!/usr/bin/env python3
"""在同一次 Actions run 的节点之间交接已还原/重建的固定构建输入。

pack：由 inputs 节点在 ci-assets.py 还原、受管运行时重建之后执行，把白名单内的
文件写成无压缩 tar 与逐文件 SHA-256 清单；同时拒绝白名单外的已跟踪源码改动。
unpack：下游节点在相同 commit 的干净 checkout 中执行；只接受清单中逐字列出的
常规文件，核对摘要后才覆盖目标，任何不符都不写入。不含密钥、用户数据或 APK。
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import tarfile
import tempfile
from git_guard import git_output

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = 1
TAR_NAME = "handoff.tar"
MANIFEST_NAME = "handoff.json"
# 受管运行时重建与描述符重算会改写的已跟踪文件；除此之外的源码改动一律拒绝。
GENERATED = (
    "app/src/main/assets/managed-package-proofs.json",
    "app/src/main/assets/runtime-descriptor.json",
)
LIMIT = 3 * 1024 ** 3


def assets_module():
    spec = importlib.util.spec_from_file_location("ci_assets", ROOT / "tools/ci-assets.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def names(root=ROOT):
    assets = assets_module()
    if root != ROOT:
        assets.ROOT = root
    return sorted(set(assets.allowed()) | set(GENERATED))


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1048576), b""):
            value.update(chunk)
    return value.hexdigest()


def bounded(name, root):
    if not isinstance(name, str) or any(char in name for char in ("\\", ":", "\x00")) \
            or name.startswith("/") or any(part in ("", ".", "..") for part in name.split("/")):
        raise ValueError("HANDOFF_PATH: " + repr(name))
    target = root / name
    if not target.resolve().is_relative_to(root.resolve()):
        raise ValueError("HANDOFF_OUTSIDE: " + name)
    for parent in [target, *target.parents]:
        if parent == root:
            break
        if parent.is_symlink():
            raise ValueError("HANDOFF_LINK: " + name)
    return target


def head(root):
    return git_output(["-C", str(root), "rev-parse", "HEAD"], text=True).strip()


def changed_tracked(root):
    output = git_output(
        ["-C", str(root), "status", "--porcelain=v1", "-z", "--untracked-files=no"])
    rows = [row for row in output.decode("utf-8", "surrogateescape").split("\0") if row]
    result = set()
    skip = False
    for row in rows:
        if skip:
            skip = False
            continue
        status, path = row[:2], row[3:]
        if "R" in status or "C" in status:
            skip = True
        result.add(path)
    return result


def pack(root, output, commit):
    allowed = names(root)
    unexpected = sorted(changed_tracked(root) - set(allowed))
    if unexpected:
        raise ValueError("HANDOFF_UNEXPECTED_CHANGE: " + ", ".join(unexpected))
    files = []
    for name in allowed:
        path = bounded(name, root)
        if path.is_symlink() or not path.is_file():
            raise ValueError("HANDOFF_NOT_REGULAR: " + name)
        files.append(dict(path=name, bytes=path.stat().st_size, sha256=digest(path),
                          executable=bool(path.stat().st_mode & 0o111)))
    if sum(row["bytes"] for row in files) > LIMIT:
        raise ValueError("HANDOFF_LIMIT")
    output.mkdir(parents=True, exist_ok=True)
    archive = output / TAR_NAME
    with tarfile.open(archive, "x", format=tarfile.PAX_FORMAT) as tar:
        for row in files:
            info = tarfile.TarInfo(row["path"])
            info.size = row["bytes"]
            info.mode = 0o755 if row["executable"] else 0o644
            info.mtime = 0
            with bounded(row["path"], root).open("rb") as stream:
                tar.addfile(info, stream)
    manifest = dict(schema=SCHEMA, kind="dsha-ci-input-handoff", commit=commit,
                    tarSha256=digest(archive), files=files)
    (output / MANIFEST_NAME).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def load_manifest(directory, commit, allowed):
    manifest = json.loads((directory / MANIFEST_NAME).read_text(encoding="utf-8"))
    if manifest.get("schema") != SCHEMA or manifest.get("kind") != "dsha-ci-input-handoff":
        raise ValueError("HANDOFF_SCHEMA")
    if manifest.get("commit") != commit:
        raise ValueError("HANDOFF_COMMIT")
    rows = manifest.get("files")
    if not isinstance(rows, list) or [row.get("path") for row in rows] != allowed:
        raise ValueError("HANDOFF_MEMBER_SET")
    for row in rows:
        if not re.fullmatch(r"[a-f0-9]{64}", str(row.get("sha256"))) \
                or type(row.get("bytes")) is not int or row["bytes"] < 0 \
                or type(row.get("executable")) is not bool:
            raise ValueError("HANDOFF_ROW: " + str(row.get("path")))
    if sum(row["bytes"] for row in rows) > LIMIT:
        raise ValueError("HANDOFF_LIMIT")
    return manifest


def unpack(root, directory, commit):
    allowed = names(root)
    manifest = load_manifest(directory, commit, allowed)
    archive = directory / TAR_NAME
    if digest(archive) != manifest["tarSha256"]:
        raise ValueError("HANDOFF_TAR_SHA256")
    expected = {row["path"]: row for row in manifest["files"]}
    with tempfile.TemporaryDirectory(prefix="ci-handoff-", dir=bounded("app", root)) as temporary:
        prepared = Path(temporary)
        seen = set()
        with tarfile.open(archive, "r:") as tar:
            for member in tar:
                row = expected.get(member.name)
                if row is None or member.name in seen or not member.isreg() or member.size != row["bytes"]:
                    raise ValueError("HANDOFF_MEMBER: " + member.name)
                seen.add(member.name)
                candidate = bounded(member.name, prepared)
                candidate.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(member) as source, candidate.open("xb") as output:
                    for chunk in iter(lambda: source.read(1048576), b""):
                        output.write(chunk)
                if digest(candidate) != row["sha256"]:
                    raise ValueError("HANDOFF_FILE_SHA256: " + member.name)
                os.chmod(candidate, 0o755 if row["executable"] else 0o644)
        if seen != set(expected):
            raise ValueError("HANDOFF_MEMBER_SET")
        # 全部候选通过后才写入工作区，避免半套输入。
        targets = {name: bounded(name, root) for name in expected}
        for name, target in targets.items():
            if target.exists() and (target.is_symlink() or not target.is_file()):
                raise ValueError("HANDOFF_TARGET_NOT_REGULAR: " + name)
            target.parent.mkdir(parents=True, exist_ok=True)
        for name, target in targets.items():
            (prepared / name).replace(target)
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("pack", "unpack"))
    parser.add_argument("--dir", type=Path, required=True, help="交接 artifact 目录")
    parser.add_argument("--commit", help="默认当前 HEAD；必须是完整 40 位 commit")
    args = parser.parse_args(argv)
    commit = args.commit or head(ROOT)
    if not re.fullmatch(r"[a-f0-9]{40}", commit) or commit != head(ROOT):
        parser.error("--commit 必须等于当前 checkout 的 HEAD")
    if args.mode == "pack":
        manifest = pack(ROOT, args.dir, commit)
    else:
        manifest = unpack(ROOT, args.dir, commit)
    print(json.dumps(dict(status="PASS_HANDOFF_" + args.mode.upper(), commit=commit,
                          files=len(manifest["files"]), tarSha256=manifest["tarSha256"])))


if __name__ == "__main__":
    main()
