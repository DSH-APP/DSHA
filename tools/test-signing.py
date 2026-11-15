#!/usr/bin/env python3
"""固定测试包签名：钉住测试证书指纹，生成/校验测试密钥。

为什么需要它：测试包（.test / .debug / .dev）如果每次 CI 用一次性密钥签名，两次构建
之间无法覆盖安装，真机升级路径就没法连续验证。这里把测试证书的 SHA-256 固定进
`ci/test-signing.json`，工作流在签名后必须核对，签名证书与钉住值不一致直接失败。

私钥（PKCS#12）只应放在仓库 Secrets `SUNSET_TEST_KEYSTORE_B64` 里，仓库内只保存
公开证书与指纹。命令：

  python3 tools/test-signing.py --show
  python3 tools/test-signing.py --check-distinct
  python3 tools/test-signing.py --check-pem out/test-cert.pem [--require-pinned]
  python3 tools/test-signing.py --bootstrap --out /secure/dir [--write-pin]
"""
import argparse
import base64
import hashlib
import json
import re
import secrets
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PIN = ROOT / "ci" / "test-signing.json"
CERT = ROOT / "docs" / "test-signing" / "test-cert.pem"
IDENTITIES = ("mod", "upstream")
DN = "CN=DSHA Test Package (NOT FOR RELEASE), O=SunsetRNE, C=CN"


def load_pin(path=PIN):
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict) or value.get("schema") != 1:
        raise ValueError("TEST_SIGNING_PIN_SCHEMA")
    alias = value.get("keyAlias")
    if not isinstance(alias, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", alias):
        raise ValueError("TEST_SIGNING_PIN_ALIAS")
    pin = value.get("certificateSha256")
    if not isinstance(pin, str) or (pin and not re.fullmatch(r"[a-f0-9]{64}", pin)):
        raise ValueError("TEST_SIGNING_PIN_DIGEST")
    return value


def pem_to_sha256(text):
    body = "".join(re.findall(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", text, re.S))
    der = base64.b64decode("".join(body.split()))
    if not der:
        raise ValueError("TEST_CERT_EMPTY")
    return hashlib.sha256(der).hexdigest()


def production_digests():
    out = []
    for name in IDENTITIES:
        path = ROOT / "ci" / f"release-identity.{name}.json"
        out.append((name, json.loads(path.read_text(encoding="utf-8"))["certificateSha256"]))
    return out


def check_distinct(pin):
    problems = []
    for name, digest in production_digests():
        if pin and pin == digest:
            problems.append(f"TEST_SIGNING_PIN_EQUALS_{name.upper()}")
    if problems:
        raise ValueError("; ".join(problems))
    return True


def check_pem(path, require_pinned):
    pin = load_pin()
    expected = pin["certificateSha256"]
    actual = pem_to_sha256(Path(path).read_text(encoding="ascii"))
    check_distinct(actual)
    if not expected:
        if require_pinned:
            raise ValueError("TEST_SIGNING_PIN_UNSET")
        print("TEST_SIGNING_PIN_UNSET: 仅本次一次性测试证书，不能覆盖升级", file=sys.stderr)
        return 0, actual
    if actual != expected:
        raise ValueError(f"TEST_SIGNING_CERT_MISMATCH expected={expected} actual={actual}")
    return 0, actual


def bootstrap(out_dir, write_pin):
    out = Path(out_dir).expanduser().resolve()
    out.mkdir(parents=True, exist_ok=True)
    key = out / "sunset-test.p12"
    cert = out / "test-cert.pem"
    password = secrets.token_hex(24)
    (out / "password.txt").write_text(password + "\n", encoding="ascii")
    (out / "password.txt").chmod(0o600)
    subprocess.run(
        ["keytool", "-genkeypair", "-keystore", str(key), "-storetype", "PKCS12",
         "-alias", "sunset-test", "-keyalg", "RSA", "-keysize", "3072", "-validity", "3650",
         "-dname", DN, "-storepass", password, "-keypass", password],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(
        ["keytool", "-exportcert", "-rfc", "-keystore", str(key), "-alias", "sunset-test",
         "-storepass", password],
        check=True, stdout=cert.open("w"), stderr=subprocess.DEVNULL)
    digest = pem_to_sha256(cert.read_text(encoding="ascii"))
    check_distinct(digest)
    b64 = base64.b64encode(key.read_bytes()).decode()
    (out / "keystore.b64").write_text(b64 + "\n", encoding="ascii")
    if write_pin:
        value = load_pin()
        value["certificateSha256"] = digest
        value["distinguishedName"] = DN
        PIN.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        CERT.parent.mkdir(parents=True, exist_ok=True)
        CERT.write_text(cert.read_text(encoding="ascii"), encoding="ascii")
    print(json.dumps({
        "certificateSha256": digest,
        "keystore": str(key),
        "certificate": str(cert),
        "keystoreBase64": str(out / "keystore.b64"),
        "passwordFile": str(out / "password.txt"),
        "keyAlias": "sunset-test",
        "pinWritten": bool(write_pin),
    }, ensure_ascii=False, indent=2))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--show", action="store_true")
    group.add_argument("--check-pem", type=Path)
    group.add_argument("--check-distinct", action="store_true")
    group.add_argument("--bootstrap", action="store_true")
    parser.add_argument("--require-pinned", action="store_true")
    parser.add_argument("--out", type=Path)
    parser.add_argument("--write-pin", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.show:
            pin = load_pin()
            check_distinct(pin["certificateSha256"])
            print(json.dumps(pin, ensure_ascii=False, indent=2))
            return 0
        if args.check_pem:
            code, _ = check_pem(args.check_pem, args.require_pinned)
            return code
        if args.check_distinct:
            pin = load_pin()
            check_distinct(pin["certificateSha256"])
            print("TEST_SIGNING_PIN_DISTINCT")
            return 0
        if not args.out:
            parser.error("--bootstrap 需要 --out 目录")
        return bootstrap(args.out, args.write_pin)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
