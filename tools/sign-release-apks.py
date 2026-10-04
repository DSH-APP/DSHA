#!/usr/bin/env python3
"""只为已核验的未签名正式源集签历史证书；不发布、不安装。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from release_names import apk_filename

ROOT = Path(__file__).resolve().parents[1]


def run(command):
    return subprocess.check_output([str(value) for value in command], stderr=subprocess.STDOUT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apks", type=Path, nargs=2, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    args = parser.parse_args()
    key = Path(os.environ.get("DSHA_KEYSTORE", ""))
    for name in ("DSHA_KEYSTORE", "DSHA_KEYSTORE_PASSWORD", "DSHA_KEY_PASSWORD"):
        if not os.environ.get(name):
            parser.error("需要明确的 " + name)
    if args.java_home is None or args.sdk is None or not key.is_file():
        parser.error("发布密钥/JDK/SDK 不可用")
    suffix = ".exe" if os.name == "nt" else ""
    identity = json.loads((ROOT / "ci/release-identity.json").read_text(encoding="utf-8"))
    alias = os.environ.get("DSHA_KEY_ALIAS") or "androiddebugkey"
    # Certificate output is binary DER. Keytool warnings must never be mixed
    # into the bytes whose digest represents the historical certificate.
    certificate = subprocess.run([str(args.java_home / ("bin/keytool" + suffix)), "-exportcert", "-alias", alias,
                       "-keystore", str(key), "-storepass:env", "DSHA_KEYSTORE_PASSWORD"],
                       stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout
    if hashlib.sha256(certificate).hexdigest() != identity["certificateSha256"]:
        raise ValueError("PUBLISH_CERTIFICATE_MISMATCH")
    build = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    version = int(re.search(r"versionCode\s+(\d+)", build)[1])
    name = re.search(r'versionName\s+"([^\"]+)"', build)[1]
    tools = args.sdk / "build-tools/36.0.0"
    signer = tools / "lib/apksigner.jar"
    args.output.mkdir(parents=True, exist_ok=True)
    report = []
    for flavor, source, minimum, expected_name in zip(
            ("standard", "low"), args.apks, (30, 23), (name, name + "low")):
        if not source.name.endswith("-unsigned.apk"):
            raise ValueError("ONLY_VERIFIED_UNSIGNED_INPUT")
        badging = run([tools / ("aapt" + suffix), "dump", "badging", source]).decode("utf-8")
        expected = f"package: name='com.dsh.client' versionCode='{version}' versionName='{expected_name}'"
        if expected not in badging or "application-debuggable" in badging \
                or f"sdkVersion:'{minimum}'" not in badging or "native-code: 'arm64-v8a'" not in badging:
            raise ValueError("UNSIGNED_INPUT_MANIFEST")
        with tempfile.TemporaryDirectory(prefix="signing-", dir=args.output) as temporary:
            candidate = Path(temporary) / "candidate.apk"
            run([args.java_home / ("bin/java" + suffix), "-jar", signer, "sign", "--ks", key,
                 "--ks-key-alias", alias, "--ks-pass", "env:DSHA_KEYSTORE_PASSWORD", "--key-pass", "env:DSHA_KEY_PASSWORD",
                 "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
                 "--out", candidate, source])
            signature = run([args.java_home / ("bin/java" + suffix), "-jar", signer, "verify",
                             "--min-sdk-version", "23", "--verbose", "--print-certs", candidate]).decode("utf-8")
            if re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)", signature) != [identity["certificateSha256"]]:
                raise ValueError("SIGNED_CERTIFICATE_MISMATCH")
            for scheme in identity["schemes"]:
                if not re.search(r"Verified using " + scheme + r" scheme[^\n]+true", signature):
                    raise ValueError("SIGNED_SCHEME_MISSING: " + scheme)
            target = args.output / apk_filename(flavor, ROOT)
            candidate.replace(target)
        value = hashlib.sha256(target.read_bytes()).hexdigest()
        target.with_suffix(".apk.sha256").write_text(value + "  " + target.name + "\n", encoding="ascii")
        report.append({"flavor": flavor, "sha256": value, "unsignedSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                       "certificateSha256": identity["certificateSha256"], "schemes": identity["schemes"]})
    (args.output / "signing.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("PASS historical signing identity and v1/v2/v3; not installed or published")


if __name__ == "__main__":
    main()
