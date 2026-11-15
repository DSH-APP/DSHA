#!/usr/bin/env python3
"""按 tools/robolectric-sdk.lock.json 准备 Robolectric 离线 android-all 目录。

单测时 Robolectric 默认临时从 Maven 下载 android-all，runner 网络抖动会让整批
sdk=N 的测试类以 MavenArtifactFetcher IOException 失败（build273、build277）。
本脚本在单测之前把锁定的 jar 平铺到目标目录（文件名 <artifact>-<version>.jar，
即 Robolectric LocalDependencyResolver 的查找格式），逐个核对大小与 sha256；
已存在且摘要正确的文件直接复用（配合 actions/cache），下载失败按退避重试。

用法：prepare-robolectric-sdks.py --dir DIR [--check]
  --check  只核对，不下载；缺失或摘要不符即失败。
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "tools/robolectric-sdk.lock.json"
VERSION = re.compile(r"^[0-9A-Za-z._-]+$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")


def load_lock(path=LOCK):
    lock = json.loads(Path(path).read_text(encoding="utf-8"))
    if lock.get("schema") != 1 or lock.get("repository") != "https://repo1.maven.org/maven2":
        raise ValueError("ROBOLECTRIC_LOCK_SCHEMA")
    if lock.get("group") != "org.robolectric" or lock.get("artifact") != "android-all-instrumented":
        raise ValueError("ROBOLECTRIC_LOCK_ARTIFACT")
    jars = lock.get("jars")
    if not isinstance(jars, list) or not jars:
        raise ValueError("ROBOLECTRIC_LOCK_EMPTY")
    seen = set()
    for jar in jars:
        version, size, digest = jar.get("version"), jar.get("size"), jar.get("sha256")
        if not isinstance(version, str) or not VERSION.match(version) or ".." in version:
            raise ValueError("ROBOLECTRIC_LOCK_VERSION")
        if not isinstance(size, int) or size <= 0 or not isinstance(digest, str) or not HEX64.match(digest):
            raise ValueError("ROBOLECTRIC_LOCK_DIGEST")
        if version in seen:
            raise ValueError("ROBOLECTRIC_LOCK_DUPLICATE")
        seen.add(version)
    return lock


def file_name(lock, jar):
    return "%s-%s.jar" % (lock["artifact"], jar["version"])


def url(lock, jar):
    group = lock["group"].replace(".", "/")
    return "%s/%s/%s/%s/%s" % (lock["repository"], group, lock["artifact"], jar["version"], file_name(lock, jar))


def matches(path, jar):
    """大小与 sha256 都对上才算可用；不跟随软链接。"""
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_size != jar["size"]:
            return False
    except OSError:
        return False
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest() == jar["sha256"]


def fetch(lock, jar, dest, attempts=5, opener=urllib.request.urlopen, sleep=time.sleep):
    part = dest.with_name(dest.name + ".part")
    last = None
    for attempt in range(1, attempts + 1):
        try:
            h = hashlib.sha256()
            size = 0
            with opener(url(lock, jar), timeout=120) as response, open(part, "wb") as out:
                for chunk in iter(lambda: response.read(1 << 20), b""):
                    size += len(chunk)
                    if size > jar["size"]:
                        raise ValueError("ROBOLECTRIC_JAR_TOO_LARGE")
                    h.update(chunk)
                    out.write(chunk)
            if size != jar["size"] or h.hexdigest() != jar["sha256"]:
                raise ValueError("ROBOLECTRIC_JAR_DIGEST")
            os.replace(part, dest)
            return attempt
        except (OSError, ValueError) as error:
            last = error
            try:
                part.unlink()
            except FileNotFoundError:
                pass
            if attempt < attempts:
                print("retry %s (%d/%d): %s" % (jar["version"], attempt, attempts, error), file=sys.stderr)
                sleep(min(60, 5 * 2 ** (attempt - 1)))
    raise RuntimeError("ROBOLECTRIC_JAR_UNAVAILABLE %s: %s" % (jar["version"], last))


def prepare(directory, check=False, lock=None, **fetch_options):
    lock = lock or load_lock()
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    report = []
    for jar in lock["jars"]:
        dest = directory / file_name(lock, jar)
        if matches(dest, jar):
            report.append((jar["version"], "cached"))
            continue
        if check:
            raise RuntimeError("ROBOLECTRIC_JAR_MISSING %s" % jar["version"])
        if dest.is_symlink() or dest.exists():
            dest.unlink()
        fetch(lock, jar, dest, **fetch_options)
        report.append((jar["version"], "downloaded"))
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dir", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    for version, state in prepare(args.dir, check=args.check):
        print("%s %s" % (state, version))
    print("PASS robolectric android-all ready in %s" % args.dir)


if __name__ == "__main__":
    main()
