#!/usr/bin/env python3
"""发布身份解析：mod（默认）与 upstream 两套身份互斥，绝不混用。

身份文件 ci/release-identity.<name>.json 保存包名、证书 SHA-256、显示名、默认 key alias、
测试/开发后缀、tag 前缀和允许发布的仓库/分支。versionCode/versionName 两种身份共用，只写在
app/build.gradle。ci/release-identity.json 仍是 Mod 身份的兼容入口（旧脚本与门禁读取它），
必须与 ci/release-identity.mod.json 的包名、证书和签名方案一致。

app/build.gradle 用同一规则解析 -Pdsha.identity；两边的允许值由 test-release-identity.py 交叉核对。

  python3 tools/release-identity.py --identity upstream --field packageName
  python3 tools/release-identity.py --check
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IDENTITIES = ("mod", "upstream")
DEFAULT = "mod"
FIELDS = ("schema", "identity", "packageName", "certificateSha256", "schemes", "appName", "keyAlias",
          "testSuffix", "devSuffix", "tagPrefix", "apkPrefix", "repository", "ref", "privateRepository",
          "workflow")
SHARED = ("packageName", "certificateSha256", "schemes")


def path_for(name):
    if name not in IDENTITIES:
        raise ValueError(f"UNKNOWN_RELEASE_IDENTITY: {name!r}（只接受 {' / '.join(IDENTITIES)}）")
    return ROOT / "ci" / f"release-identity.{name}.json"


def validate(name, value):
    if not isinstance(value, dict) or set(value) != set(FIELDS):
        raise ValueError(f"RELEASE_IDENTITY_FIELDS: {name}")
    if value["schema"] != 1 or value["identity"] != name:
        raise ValueError(f"RELEASE_IDENTITY_NAME: {name}")
    if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", value["packageName"]):
        raise ValueError(f"RELEASE_IDENTITY_PACKAGE: {name}")
    if not re.fullmatch(r"[a-f0-9]{64}", value["certificateSha256"]):
        raise ValueError(f"RELEASE_IDENTITY_CERTIFICATE: {name}")
    if value["schemes"] != ["v1", "v2", "v3"]:
        raise ValueError(f"RELEASE_IDENTITY_SCHEMES: {name}")
    if not re.fullmatch(r"\.[A-Za-z][A-Za-z0-9_]*", value["testSuffix"]):
        raise ValueError(f"RELEASE_IDENTITY_TEST_SUFFIX: {name}")
    if not re.fullmatch(r"\.[A-Za-z][A-Za-z0-9_]*", value["devSuffix"]):
        raise ValueError(f"RELEASE_IDENTITY_DEV_SUFFIX: {name}")
    if not re.fullmatch(r"[A-Za-z0-9._-]+", value["keyAlias"]) or not value["appName"].strip():
        raise ValueError(f"RELEASE_IDENTITY_ALIAS_OR_NAME: {name}")
    if not re.fullmatch(r"[a-z0-9-]+-build", value["tagPrefix"]) or not re.fullmatch(r"[a-z0-9-]+", value["apkPrefix"]):
        raise ValueError(f"RELEASE_IDENTITY_TAG: {name}")
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", value["repository"]) or not value["ref"].startswith("refs/heads/") \
            or not isinstance(value["privateRepository"], bool):
        raise ValueError(f"RELEASE_IDENTITY_REPOSITORY: {name}")
    return value


def load(name=None, root=None):
    """name 为 None 或空串时取默认 mod；未知值直接报错，不回退。"""
    selected = DEFAULT if name in (None, "") else name
    path = path_for(selected)
    if root is not None:
        path = Path(root) / "ci" / path.name
    return validate(selected, json.loads(path.read_text(encoding="utf-8")))


def check(root=None):
    """两套身份互不相同，且 Mod 兼容入口与 mod 身份一致。"""
    base = Path(root) if root is not None else ROOT
    loaded = {name: load(name, base) for name in IDENTITIES}
    for key in ("packageName", "certificateSha256", "appName", "keyAlias", "testSuffix", "devSuffix",
                "tagPrefix", "repository", "workflow"):
        if loaded["mod"][key] == loaded["upstream"][key]:
            raise ValueError(f"RELEASE_IDENTITIES_SHARE_{key}")
    legacy = json.loads((base / "ci/release-identity.json").read_text(encoding="utf-8"))
    for key in SHARED:
        if legacy.get(key) != loaded["mod"][key]:
            raise ValueError(f"LEGACY_IDENTITY_DIFFERS_FROM_MOD: {key}")
    return loaded


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--identity", default=DEFAULT)
    parser.add_argument("--field", choices=FIELDS)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.check:
            check()
        value = load(args.identity)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2
    if args.field:
        field = value[args.field]
        print(json.dumps(field) if isinstance(field, (list, bool)) else field)
    elif not args.check:
        print(json.dumps(value, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
