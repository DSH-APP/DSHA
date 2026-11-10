#!/usr/bin/env python3
"""发布签名工具的纯逻辑回归：清单、载荷、证书与 scheme，不签名、不联网、不需要 Android SDK。"""
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest
import zipfile

TOOL = Path(__file__).resolve().with_name("sign-release-apks.py")


def load():
    spec = importlib.util.spec_from_file_location("sign_release_apks", TOOL)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SIGN = load()
IDENTITY = SIGN.identity("mod")
# 版本号从源码读取，不写死：发布准备递增版本时本回归仍应通过。
EXPECTED = SIGN.source_version()
BADGING = f"""package: name='{IDENTITY['packageName']}' versionCode='{EXPECTED['versionCode']}' versionName='{EXPECTED['versionName']}' platformBuildVersionName='14' platformBuildVersionCode='34' compileSdkVersion='37' compileSdkVersionCodename='14'
sdkVersion:'30'
targetSdkVersion:'37'
uses-permission: name='android.permission.INTERNET'
application-label:'DSH-A-Su'
application-label-zh:'DSH-A-Su'
native-code: 'arm64-v8a'
"""


class BadgingTest(unittest.TestCase):
    def test_badging_is_parsed(self):
        values = SIGN.parse_badging(BADGING)
        self.assertEqual(values["packageName"], IDENTITY["packageName"])
        self.assertEqual(values["versionCode"], EXPECTED["versionCode"])
        self.assertEqual(values["versionName"], EXPECTED["versionName"])
        self.assertEqual(values["minSdk"], 30)
        self.assertEqual(values["nativeCode"], "arm64-v8a")
        self.assertFalse(values["debuggable"])

    def test_alternate_minsdk_key_is_accepted(self):
        self.assertEqual(SIGN.parse_badging(BADGING.replace("sdkVersion:", "minSdkVersion:"))["minSdk"], 30)

    def test_debug_flag_is_visible(self):
        self.assertTrue(SIGN.parse_badging(BADGING + "application-debuggable\n")["debuggable"])


class ManifestTest(unittest.TestCase):
    def values(self, **changes):
        value = {"packageName": IDENTITY["packageName"], "versionCode": EXPECTED["versionCode"],
                 "versionName": EXPECTED["versionName"], "minSdk": 30,
                 "nativeCode": "arm64-v8a", "debuggable": False}
        value.update(changes)
        return value

    def test_valid_manifest_is_accepted(self):
        SIGN.check_manifest(self.values(), IDENTITY, EXPECTED)

    def test_wrong_package_version_minsdk_abi_or_debuggable_is_rejected(self):
        cases = [
            ({"packageName": IDENTITY["packageName"] + ".dev"}, "UNSIGNED_APK_PACKAGE_MISMATCH"),
            ({"versionCode": EXPECTED["versionCode"] + 1}, "UNSIGNED_APK_VERSION_MISMATCH"),
            ({"versionName": EXPECTED["versionName"] + "-x"}, "UNSIGNED_APK_VERSION_MISMATCH"),
            ({"minSdk": 24}, "UNSIGNED_APK_MINSDK_MISMATCH"),
            ({"nativeCode": None}, "UNSIGNED_APK_NATIVE_CODE_MISMATCH"),
            ({"nativeCode": "arm64-v8a,armeabi-v7a"}, "UNSIGNED_APK_NATIVE_CODE_MISMATCH"),
            ({"debuggable": True}, "UNSIGNED_APK_DEBUGGABLE"),
            ({"versionName": EXPECTED["versionName"] + "-SunsetDev"}, "UNSIGNED_APK_TEST_OR_DEV_IDENTITY"),
            ({"versionName": "1.0-test"}, "UNSIGNED_APK_TEST_OR_DEV_IDENTITY"),
        ]
        for change, error in cases:
            with self.subTest(change=change):
                with self.assertRaises(SystemExit) as raised:
                    SIGN.check_manifest(self.values(**change), IDENTITY, EXPECTED)
                self.assertIn(error, str(raised.exception))

    def test_identity_selected_by_name_never_mixes_up(self):
        upstream = SIGN.identity("upstream")
        self.assertNotEqual(upstream["certificateSha256"], IDENTITY["certificateSha256"])
        self.assertNotEqual(upstream["packageName"], IDENTITY["packageName"])
        self.assertEqual(IDENTITY["schemes"], ["v1", "v2", "v3"])


class VersionTest(unittest.TestCase):
    def test_source_version_matches_build_gradle(self):
        build = (TOOL.parents[1] / "app/build.gradle").read_text(encoding="utf-8")
        code = int(re.search(r"versionCode\s+(\d+)", build).group(1))
        name = re.search(r'versionName\s+"([^"]+)"', build).group(1)
        self.assertEqual(SIGN.source_version(), {"versionCode": code, "versionName": name})

    def test_metadata_version_must_exist_and_parse(self):
        with tempfile.TemporaryDirectory(prefix="release-metadata-") as folder:
            path = Path(folder) / "output-metadata.json"
            path.write_text(json.dumps({"elements": [dict(EXPECTED)]}), encoding="utf-8")
            self.assertEqual(SIGN.metadata_version(path), EXPECTED)
            with self.assertRaises(SystemExit):
                SIGN.metadata_version(Path(folder) / "missing.json")
            path.write_text(json.dumps({"elements": [{"versionCode": EXPECTED["versionCode"] + 1,
                                                      "versionName": EXPECTED["versionName"]}]}),
                            encoding="utf-8")
            self.assertNotEqual(SIGN.metadata_version(path), EXPECTED)

    def test_scheme_verification_assumes_pre_24_platform(self):
        # apksigner 只在假定 platform minSdk < 24 时才实际校验 v1；用 23 复核，APK 自身 minSdk 30
        # 由 badging 单独核对（本地用真实 apksigner 0.9 验证过该行为）。
        self.assertEqual(SIGN.VERIFY_MIN_SDK, 23)
        self.assertEqual(SIGN.MIN_SDK, 30)

    def test_build_tools_follows_the_source_lock(self):
        lock = json.loads((TOOL.parents[1] / "ci/toolchain.lock.json").read_text(encoding="utf-8"))
        self.assertEqual(SIGN.build_tools(), lock["buildTools"])


def make_zip(path, entries, extra=None):
    with zipfile.ZipFile(path, "x", zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
        for name, data in (extra or {}).items():
            archive.writestr(name, data)
    return path


class PayloadTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="release-payload-")
        self.root = Path(self.folder.name)

    def tearDown(self):
        self.folder.cleanup()

    def test_identical_payload_with_added_meta_inf_is_accepted(self):
        unsigned = make_zip(self.root / "a-unsigned.apk",
                            {"AndroidManifest.xml": b"manifest", "assets/offline-rootfs.bin": b"rootfs"})
        signed = make_zip(self.root / "a.apk",
                          {"AndroidManifest.xml": b"manifest", "assets/offline-rootfs.bin": b"rootfs"},
                          {"META-INF/MANIFEST.MF": b"manifest", "META-INF/CERT.SF": b"sf"})
        self.assertEqual(SIGN.compare_payload(unsigned, signed), 2)
        self.assertNotIn("META-INF/MANIFEST.MF", SIGN.payload_entries(signed))

    def test_changed_removed_or_added_payload_member_is_rejected(self):
        unsigned = make_zip(self.root / "a-unsigned.apk",
                            {"AndroidManifest.xml": b"manifest", "assets/x.bin": b"one"})
        for name, entries in {
            "changed": {"AndroidManifest.xml": b"manifest", "assets/x.bin": b"two"},
            "removed": {"AndroidManifest.xml": b"manifest"},
            "added": {"AndroidManifest.xml": b"manifest", "assets/x.bin": b"one", "assets/y.bin": b"new"},
        }.items():
            with self.subTest(case=name):
                signed = make_zip(self.root / (name + ".apk"), entries)
                with self.assertRaises(SystemExit) as raised:
                    SIGN.compare_payload(unsigned, signed)
                self.assertIn("SIGNED_APK_PAYLOAD_CHANGED", str(raised.exception))

    def test_meta_inf_only_difference_is_not_a_payload_change(self):
        unsigned = make_zip(self.root / "a-unsigned.apk", {"assets/x.bin": b"one"})
        signed = make_zip(self.root / "a.apk", {"assets/x.bin": b"one"},
                          {"META-INF/MANIFEST.MF": b"other"})
        self.assertEqual(SIGN.compare_payload(unsigned, signed), 1)


VERIFY_OUTPUT = """Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Number of signers: 1
Signer #1 certificate SHA-256 digest: """ + IDENTITY["certificateSha256"] + """
Signer #1 certificate DN: CN=DSHA
"""


class SignatureTest(unittest.TestCase):
    def test_all_identity_schemes_must_be_verified(self):
        found = SIGN.verified_schemes(VERIFY_OUTPUT)
        self.assertEqual(found, {"v1": True, "v2": True, "v3": True, "v3.1": False, "v4": False})
        SIGN.require_schemes(VERIFY_OUTPUT, IDENTITY)
        for scheme in ("v1", "v2", "v3"):
            with self.subTest(scheme=scheme):
                broken = "\n".join(
                    line.replace(": true", ": false") if line.startswith(f"Verified using {scheme} scheme") else line
                    for line in VERIFY_OUTPUT.splitlines())
                with self.assertRaises(SystemExit) as raised:
                    SIGN.require_schemes(broken, IDENTITY)
                self.assertIn("SIGNED_SCHEME_MISSING", str(raised.exception))

    def test_missing_scheme_line_is_rejected(self):
        without_v1 = "\n".join(line for line in VERIFY_OUTPUT.splitlines()
                               if not line.startswith("Verified using v1 scheme"))
        with self.assertRaises(SystemExit):
            SIGN.require_schemes(without_v1, IDENTITY)

    def test_certificate_must_equal_the_identity_pin(self):
        self.assertEqual(SIGN.require_certificate(VERIFY_OUTPUT, IDENTITY), IDENTITY["certificateSha256"])
        wrong = VERIFY_OUTPUT.replace(IDENTITY["certificateSha256"], "0" * 64)
        with self.assertRaises(SystemExit) as raised:
            SIGN.require_certificate(wrong, IDENTITY)
        self.assertIn("SIGNED_CERTIFICATE_MISMATCH", str(raised.exception))
        two_signers = VERIFY_OUTPUT + "Signer #2 certificate SHA-256 digest: " + IDENTITY["certificateSha256"] + "\n"
        with self.assertRaises(SystemExit):
            SIGN.require_certificate(two_signers, IDENTITY)


class ToolFailureTest(unittest.TestCase):
    def test_badging_failure_is_reported_with_a_code(self):
        with self.assertRaises(SystemExit) as raised:
            SIGN.badging("/bin/false", "ignored.apk")
        self.assertIn("APK_BADGING_FAILED", str(raised.exception))

    def test_unsigned_verify_requires_explicit_marker(self):
        class Result:
            returncode = 1
            stdout = "ERROR: something else"
            stderr = ""

        class FakeRun:
            @staticmethod
            def run(*args, **kwargs):
                return Result()

        original = SIGN.subprocess.run
        SIGN.subprocess.run = FakeRun.run
        try:
            with self.assertRaises(SystemExit) as raised:
                SIGN.require_unsigned("apksigner", "x.apk")
            self.assertIn("UNSIGNED_APK_VERIFY_AMBIGUOUS", str(raised.exception))
        finally:
            SIGN.subprocess.run = original


if __name__ == "__main__":
    unittest.main()
