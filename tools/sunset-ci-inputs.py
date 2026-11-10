#!/usr/bin/env python3
"""成对解析 Sunset CI 输入；未覆盖时使用已经发布的固定原始源码归档。"""
import argparse
import json
import os
from pathlib import Path
import re
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def resolve(environment, lock_path=None):
    selected = None
    for prefix, source in (("SUNSET_DISPATCH", "workflow-dispatch"),
                           ("SUNSET_REPOSITORY", "repository-variables")):
        url = environment.get(prefix + "_URL", "")
        sha = environment.get(prefix + "_SHA", "")
        if not url and not sha:
            continue
        if not url or not sha:
            raise ValueError("SUNSET_INPUT_PAIR_REQUIRED: " + source)
        selected = dict(url=url, sha256=sha, source=source,
                        format=environment.get(prefix + "_FORMAT") or "bundle")
        break
    if selected is None:
        lock = json.loads((lock_path or ROOT / "ci/sunset-inputs.lock.json").read_text(encoding="utf-8"))
        if lock.get("schema") != 1:
            raise ValueError("SUNSET_INPUT_LOCK_SCHEMA")
        selected = {key: lock[key] for key in ("url", "sha256", "format")}
        selected["source"] = "source-lock"
    if any(not isinstance(value, str) or any(ord(c) < 32 or ord(c) == 127 for c in value)
           for value in selected.values()):
        raise ValueError("SUNSET_INPUT_VALUE")
    url = urlsplit(selected["url"])
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.fragment:
        raise ValueError("SUNSET_INPUT_HTTPS_REQUIRED")
    if not re.fullmatch(r"[a-f0-9]{64}", selected["sha256"]):
        raise ValueError("SUNSET_INPUT_SHA256_REQUIRED")
    if selected["format"] not in ("bundle", "source-archive"):
        raise ValueError("SUNSET_INPUT_FORMAT")
    return selected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--github-output", action="store_true")
    args = parser.parse_args()
    selected = resolve(os.environ)
    if args.github_output:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            for key, value in selected.items():
                output.write(f"{key}={value}\n")
    # 自定义 URL 可能带下载签名；日志只记录来源、格式和内容摘要。
    print(json.dumps({key: selected[key] for key in ("source", "format", "sha256")}))


if __name__ == "__main__":
    main()
