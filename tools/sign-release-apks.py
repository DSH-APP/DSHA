#!/usr/bin/env python3
"""核验未签名正式 APK，用所选发布身份的证书签名并复验；不发布、不安装。

两段式发布链（release-production.yml / _node-test-packages.yml）：

  build-unsigned → 本工具 --check-only：清单（包名 / 版本 / minSdk / arm64 / 非 debuggable /
                   非测试身份）必须与源码一致，且 apksigner verify 必须失败（确实未签名）。
  sign-verify    → 本工具：签名前核对 keystore 中的证书等于 ci/release-identity.<identity>.json，
                   按身份 schemes 显式签 v1+v2+v3，签名后复验证书与全部 scheme，并断言除
                   META-INF/ 外每个 ZIP 成员的 CRC32 与大小与未签名输入完全一致（签名不改载荷）。

用法：

  python3 tools/sign-release-apks.py --check-only \
      --apk app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk \
      --metadata app/build/outputs/apk/standard/release/output-metadata.json \
      --receipt out/unsigned-apk.json

  python3 tools/sign-release-apks.py \
      --apk unsigned/app-standard-release-unsigned.apk \
      --name dsha-a-su-<sha8>.apk --output-dir out --identity mod

签名所需的 DSHA_KEYSTORE / DSHA_KEYSTORE_PASSWORD / DSHA_KEY_PASSWORD 只从环境读取；
DSHA_KEY_ALIAS 缺省用身份文件里的 keyAlias。--check-only 不读取任何签名凭据。
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
META_PREFIX = "META-INF/"
MIN_SDK = 30
# apksigner 只在假定的 platform minSdk < 24 时才实际校验 v1（JAR signing）；用 23 复核是为了让
# v1 真正被验证，而不是仅凭 META-INF/ 文件存在。APK 自身 minSdk=30 由 badging 单独核对。
VERIFY_MIN_SDK = 23
REQUIRED_ENV = ("DSHA_KEYSTORE", "DSHA_KEYSTORE_PASSWORD", "DSHA_KEY_PASSWORD")
TEST_SUFFIXES = ("-test", "-SunsetTest", "-island", "-SunsetDebug", "-SunsetDev")


def _module(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def build_tools(root=ROOT):
    return _module("ci_toolchain", "tools/ci-toolchain.py").read(root)["buildTools"]


def identity(name):
    return _module("release_identity", "tools/release-identity.py").load(name, ROOT)


def source_version(root=ROOT):
    """versionCode / versionName 的源码基准；不接受 APK 自带值自证。"""
    build = (Path(root) / "app/build.gradle").read_text(encoding="utf-8")
    code = re.search(r"versionCode\s+(\d+)", build)
    name = re.search(r'versionName\s+"([^"]+)"', build)
    if not code or not name:
        raise SystemExit("SOURCE_VERSION_MISSING")
    return {"versionCode": int(code.group(1)), "versionName": name.group(1)}


def parse_badging(text):
    package = re.search(r"^package: name='([^']+)'", text, re.M)
    code = re.search(r"versionCode='(\d+)'", text)
    version = re.search(r"versionName='([^']*)'", text)
    minimum = re.search(r"^(?:sdkVersion|minSdkVersion):'(\d+)'", text, re.M)
    native = re.search(r"^native-code: '([^']*)'", text, re.M)
    return {
        "packageName": package.group(1) if package else None,
        "versionCode": int(code.group(1)) if code else None,
        "versionName": version.group(1) if version else None,
        "minSdk": int(minimum.group(1)) if minimum else None,
        "nativeCode": native.group(1) if native else None,
        "debuggable": "application-debuggable" in text,
    }


def check_manifest(values, selected, expected):
    """未签名正式包的清单必须与源码和所选身份一致；测试 / 开发身份直接拒绝。"""
    if values["packageName"] != selected["packageName"]:
        raise SystemExit("UNSIGNED_APK_PACKAGE_MISMATCH")
    # 身份后缀先于版本比对：正式包绝不能带测试 / 开发身份（即使源码版本号也错了）。
    if not values["versionName"] or values["versionName"].endswith(TEST_SUFFIXES):
        raise SystemExit("UNSIGNED_APK_TEST_OR_DEV_IDENTITY")
    if values["versionCode"] != expected["versionCode"] or values["versionName"] != expected["versionName"]:
        raise SystemExit("UNSIGNED_APK_VERSION_MISMATCH")
    if values["minSdk"] != MIN_SDK:
        raise SystemExit("UNSIGNED_APK_MINSDK_MISMATCH")
    if values["nativeCode"] != "arm64-v8a":
        raise SystemExit("UNSIGNED_APK_NATIVE_CODE_MISMATCH")
    if values["debuggable"]:
        raise SystemExit("UNSIGNED_APK_DEBUGGABLE")
    return values


def metadata_version(path):
    path = Path(path)
    if not path.is_file():
        raise SystemExit("APK_METADATA_MISSING")
    value = json.loads(path.read_text(encoding="utf-8"))["elements"][0]
    return {"versionCode": int(value["versionCode"]), "versionName": value["versionName"]}


def digest(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1048576), b""):
            value.update(chunk)
    return value.hexdigest()


def payload_entries(path):
    """ZIP 载荷清单：签名只允许新增 META-INF/ 成员，其余成员的 CRC32 与大小不得变化。"""
    with zipfile.ZipFile(path) as archive:
        return {info.filename: (info.CRC, info.file_size)
                for info in archive.infolist() if not info.filename.startswith(META_PREFIX)}


def compare_payload(unsigned, signed):
    before = payload_entries(unsigned)
    after = payload_entries(signed)
    if before == after:
        return len(before)
    missing = sorted(set(before) - set(after))[:5]
    extra = sorted(set(after) - set(before))[:5]
    changed = sorted(name for name in set(before) & set(after) if before[name] != after[name])[:5]
    raise SystemExit("SIGNED_APK_PAYLOAD_CHANGED: " + json.dumps(
        {"missing": missing, "extra": extra, "changed": changed}, ensure_ascii=False))


def verified_schemes(text):
    found = {}
    for match in re.finditer(r"^Verified using (v[1-4](?:\.\d)?) scheme[^\n]*: (true|false)$", text, re.M):
        found[match.group(1)] = match.group(2) == "true"
    return found


def require_schemes(text, selected):
    found = verified_schemes(text)
    for scheme in selected["schemes"]:
        if found.get(scheme) is not True:
            raise SystemExit("SIGNED_SCHEME_MISSING: " + scheme)
    return found


def require_certificate(text, selected):
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})", text)
    if certificates != [selected["certificateSha256"]]:
        raise SystemExit("SIGNED_CERTIFICATE_MISMATCH")
    return certificates[0]


def require_unsigned(apksigner, apk):
    """未签名输入的 apksigner verify 必须明确失败，防止把已签名包再签一次。"""
    result = subprocess.run([str(apksigner), "verify", "--verbose", str(apk)],
                            capture_output=True, text=True)
    output = result.stdout + result.stderr
    if result.returncode == 0:
        raise SystemExit("UNSIGNED_APK_IS_SIGNED")
    if "DOES NOT VERIFY" not in output:
        raise SystemExit("UNSIGNED_APK_VERIFY_AMBIGUOUS")


def sdk_tools(sdk):
    sdk = Path(sdk)
    tools = sdk / "build-tools" / build_tools()
    apksigner = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    aapt2 = tools / ("aapt2.exe" if os.name == "nt" else "aapt2")
    if not apksigner.is_file() or not aapt2.is_file():
        raise SystemExit("ANDROID_BUILD_TOOLS_MISSING: " + str(tools))
    return {"apksigner": apksigner, "aapt2": aapt2}


def badging(aapt2, apk):
    try:
        return subprocess.check_output([str(aapt2), "dump", "badging", str(apk)],
                                       stderr=subprocess.STDOUT, text=True)
    except subprocess.CalledProcessError as error:
        raise SystemExit("APK_BADGING_FAILED") from error


def export_certificate(alias, keystore):
    keytool = shutil.which("keytool")
    if not keytool:
        raise SystemExit("KEYTOOL_MISSING")
    try:
        return subprocess.run([keytool, "-exportcert", "-alias", alias, "-keystore", str(keystore),
                               "-storepass:env", "DSHA_KEYSTORE_PASSWORD"],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout
    except subprocess.CalledProcessError as error:
        raise SystemExit("RELEASE_KEYSTORE_INVALID") from error


def check_unsigned(apk, selected, expected, tools):
    apk = Path(apk)
    if not apk.is_file() or apk.is_symlink():
        raise SystemExit("UNSIGNED_APK_MISSING")
    if not apk.name.endswith("-unsigned.apk"):
        raise SystemExit("ONLY_UNSIGNED_INPUT")
    values = check_manifest(parse_badging(badging(tools["aapt2"], apk)), selected, expected)
    require_unsigned(tools["apksigner"], apk)
    return values


def sign(apk, target, selected, tools):
    key = Path(os.environ.get("DSHA_KEYSTORE", ""))
    if not key.is_file():
        raise SystemExit("RELEASE_KEYSTORE_MISSING")
    alias = os.environ.get("DSHA_KEY_ALIAS") or selected["keyAlias"]
    if not re.fullmatch(r"[A-Za-z0-9._-]+", alias):
        raise SystemExit("RELEASE_KEY_ALIAS_UNSAFE")
    # 先核对 keystore 中的证书，避免用错密钥签出不可覆盖升级的包。
    certificate = export_certificate(alias, key)
    if hashlib.sha256(certificate).hexdigest() != selected["certificateSha256"]:
        raise SystemExit("PUBLISH_CERTIFICATE_MISMATCH")
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="signing-", dir=target.parent) as temporary:
        candidate = Path(temporary) / target.name
        try:
            subprocess.run([str(tools["apksigner"]), "sign",
                            "--ks", str(key), "--ks-key-alias", alias,
                            "--ks-pass", "env:DSHA_KEYSTORE_PASSWORD",
                            "--key-pass", "env:DSHA_KEY_PASSWORD",
                            "--v1-signing-enabled", "true",
                            "--v2-signing-enabled", "true",
                            "--v3-signing-enabled", "true",
                            "--out", str(candidate), str(apk)], check=True)
        except subprocess.CalledProcessError as error:
            raise SystemExit("APK_SIGNING_FAILED") from error
        candidate.replace(target)
    return {"alias": alias, "certificateSha256": selected["certificateSha256"]}


def verify_signed(apk, unsigned, selected, tools):
    result = subprocess.run([str(tools["apksigner"]), "verify", "--min-sdk-version", str(VERIFY_MIN_SDK),
                             "--verbose", "--print-certs", str(apk)],
                            capture_output=True, text=True)
    output = result.stdout + result.stderr
    if result.returncode != 0:
        raise SystemExit("SIGNED_APK_VERIFY_FAILED")
    certificate = require_certificate(output, selected)
    schemes = require_schemes(output, selected)
    text = badging(tools["aapt2"], apk)
    values = check_manifest(parse_badging(text), selected, source_version())
    members = compare_payload(unsigned, apk)
    return {"signature": output, "badging": text, "values": values,
            "certificate": certificate, "schemes": schemes, "payloadMembers": members}


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--identity", default="mod")
    parser.add_argument("--check-only", action="store_true")
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--name")
    parser.add_argument("--output-dir", type=Path, default=Path("out"))
    parser.add_argument("--sdk", type=Path,
                        default=Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
                                     or "/usr/local/lib/android/sdk"))
    args = parser.parse_args(argv)
    selected = identity(args.identity)
    expected = source_version()
    if args.metadata is not None:
        if metadata_version(args.metadata) != expected:
            raise SystemExit("METADATA_VERSION_MISMATCH")
    tools = sdk_tools(args.sdk)
    values = check_unsigned(args.apk, selected, expected, tools)
    receipt = {
        "schema": 1,
        "kind": "dsha-release-unsigned-apk",
        "identity": selected["identity"],
        "apkFile": args.apk.name,
        "apkSize": args.apk.stat().st_size,
        "apkSha256": digest(args.apk),
        "packageName": values["packageName"],
        "versionCode": values["versionCode"],
        "versionName": values["versionName"],
        "minSdk": values["minSdk"],
        "nativeCode": values["nativeCode"],
        "debuggable": values["debuggable"],
        "signed": False,
    }
    if args.check_only:
        if args.receipt is not None:
            write_json(args.receipt, receipt)
        print(json.dumps(receipt, ensure_ascii=False, sort_keys=True))
        print("PASS unsigned release APK manifest; apksigner verify correctly rejected it")
        return 0
    if not args.name:
        parser.error("--name 是签名输出的文件名，必需")
    if args.name != Path(args.name).name or not args.name.endswith(".apk") or args.name.endswith("-unsigned.apk"):
        parser.error("--name 必须是最终 APK 文件名")
    for name in REQUIRED_ENV:
        if not os.environ.get(name):
            raise SystemExit("RELEASE_SIGNING_ENV_MISSING: " + name)
    target = args.output_dir / args.name
    if target.exists():
        raise SystemExit("SIGNED_OUTPUT_EXISTS: " + str(target))
    signing = sign(args.apk, target, selected, tools)
    verified = verify_signed(target, args.apk, selected, tools)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "apk-signature.txt").write_text(verified["signature"], encoding="utf-8")
    (args.output_dir / "apk-badging.txt").write_text(verified["badging"], encoding="utf-8")
    signing.update({
        "kind": "dsha-release-signed-apk",
        "apkFile": target.name,
        "apkSize": target.stat().st_size,
        "apkSha256": digest(target),
        "unsignedSha256": receipt["apkSha256"],
        "packageName": verified["values"]["packageName"],
        "versionCode": verified["values"]["versionCode"],
        "versionName": verified["values"]["versionName"],
        "schemes": verified["schemes"],
        "payloadMembers": verified["payloadMembers"],
    })
    write_json(args.output_dir / "apk-signing.json", signing)
    print(json.dumps(signing, ensure_ascii=False, sort_keys=True))
    print("PASS signed release APK identity, certificate, v1+v2+v3 and unchanged payload")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
