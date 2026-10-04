#!/usr/bin/env python3
"""Create a local complete public-source snapshot bound to the actual software receipt."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools"))
PREFIXES = ("app/src/", "tools/", "gradle/", "ci/", ".github/", "agent-skills/", "website/", "docs/")
TOP = {
    ".gitattributes", ".gitignore", "AGENTS.md", "README.md", "README.en.md", "BUILD.md",
    "CHANGELOG.md", "CONTRIBUTING.md", "LICENSE", "THIRD_PARTY_NOTICES.md", "build.gradle",
    "settings.gradle", "gradle.properties", "build.sh", "gradlew", "gradlew.bat", "app/build.gradle",
}
GENERATED_ARCHIVES = {".bin", ".gz", ".tgz", ".apk", ".zip", ".whl"}
PRIVATE_SUFFIXES = {".keystore", ".jks", ".key", ".pem"}
DETACHED_FINAL_REPORTS = {
    "docs/audits/build156/execution-ledger-final.json",
    "docs/releases/build156-remaining-work-20261003.md",
    "docs/releases/v0.2.7-build156-20261003.md",
}
DETACHED_REASON = (
    "detached final audit/delivery report; written after this ZIP and bound by the final "
    "delivery report, not covered by archived source bytes; exclusion hashes, if present, "
    "describe the pre-finalization bytes only"
)
PUBLIC_SOURCE_SUFFIXES = {".java", ".py", ".sh", ".gradle"}
PUBLIC_RETIRED_ASSETS = (
    "dsh-deps-heal.sh", "fix-stale-bundles.sh", "flatten-l2s.py", "fs-write-patch.sh",
    "heal-pnpm-shells.py", "heal-profile-boot.py", "heal-session.sh", "heal-sessions.py",
    "migrate-public-data.sh", "webserver-auth-patch.sh", "webui-degrade-patch.sh",
    "webui-origin-port-patch.sh", "webui-polyfill.sh",
)
# These are byte fixtures consumed by the two current fast host entry points.
# The manifests remain the SHA authority; this finite origin/path policy cannot
# turn a new history record into a public ZIP member without a code review.
PUBLIC_RETIRED_TABLES = (
    ("docs/audits/build156/retired-asset-sources.json", "sources", "source", "saved",
     "tools/test-asset-deployment.py", {
         "app/src/main/assets/" + name: "tools/history/build156/unused-assets/" + name
         for name in PUBLIC_RETIRED_ASSETS
     }),
    ("docs/audits/build156/retired-environment-data-sources.json", "sources", "source", "saved",
     "tools/test-asset-deployment.py", {
         "app/src/main/assets/environment-data.py":
             "tools/history/build156/unused-assets/environment-data.py",
         "app/src/main/java/com/deepseekharness/app/core/EnvironmentDataBackup.java":
             "tools/history/build156/java/core/EnvironmentDataBackup.java",
     }),
    ("docs/audits/build154/retired-audit-sources.json", None, "path", "retainedSource",
     "tools/test-retired-audit-contract.py", {
         "app/src/debug/java/com/deepseekharness/app/" + name:
             "tools/history/android-audits/com/deepseekharness/app/" + name
         for name in (
             "core/Rc21AttachmentAudit.java", "runtime/TabletEnvironmentAudit.java",
             "ui/RecoveryEntryAudit.java", "ui/InstallSettingsAudit.java", "ui/UiPolishAudit.java",
         )
     }),
    ("docs/audits/build154/engineering-retired-sources.json", None, "path", "retainedSource",
     "tools/test-retired-audit-contract.py", {
         "app/src/" + name: "tools/history/engineering/app/src/" + name
         for name in (
             "androidTest/java/com/deepseekharness/app/Rc13Instrumentation.java",
             "debug/java/com/deepseekharness/app/ui/FunctionalAuditInstrumentation.java",
             "debug/java/com/deepseekharness/app/core/UpgradeDeviceAudit.java",
             "debug/java/com/deepseekharness/app/core/StartupDiagnosticsAudit.java",
             "debug/java/com/deepseekharness/app/ui/ConfigurationAuditInstrumentation.java",
         )
     }),
    ("docs/audits/build156/retired-test-control-sources.json", None, "path", "retainedSource",
     "tools/test-retired-audit-contract.py", {
         "tools/control-functional-audit.py":
             "tools/history/build156/obsolete-device-audits/control-functional-audit.py",
         "tools/device-backup-audit.init.gradle":
             "tools/history/build156/obsolete-device-audits/device-backup-audit.init.gradle",
     }),
)


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def retired_audit_origin(name):
    """Mirror only the actual current retired-audit consumer's row filter."""
    return isinstance(name, str) and (
        name.startswith(("app/src/debug/", "app/src/androidTest/"))
        or name in {"tools/control-functional-audit.py", "tools/device-backup-audit.init.gradle"}
    )


def validate_public_retired_file(repository, name, record):
    if not isinstance(name, str) or "\\" in name or not name.startswith("tools/history/") \
            or PurePosixPath(name).as_posix() != name or any(
                part in {"", ".", ".."} for part in name.split("/")
            ):
        raise ValueError("PUBLIC_RETIRED_PATH_SCOPE:" + str(name))
    if Path(name).suffix.lower() not in PUBLIC_SOURCE_SUFFIXES:
        raise ValueError("PUBLIC_RETIRED_SOURCE_SUFFIX:" + name)
    candidate = repository / name
    for node in (candidate, *candidate.parents):
        if node.is_symlink() or getattr(node, "is_junction", lambda: False)():
            raise ValueError("PUBLIC_RETIRED_SOURCE_LINK:" + name)
        if node == repository:
            break
    resolved = candidate.resolve(strict=True)
    if resolved != candidate.absolute() or not resolved.is_relative_to(repository / "tools/history") \
            or not candidate.is_file():
        raise ValueError("PUBLIC_RETIRED_SOURCE_OUTSIDE_OR_NONFILE:" + name)
    expected = record.get("sha256")
    if not isinstance(expected, str) or len(expected) != 64 \
            or any(character not in "0123456789abcdef" for character in expected):
        raise ValueError("PUBLIC_RETIRED_SOURCE_DIGEST:" + name)
    if "bytes" in record and (
        type(record["bytes"]) is not int or record["bytes"] < 0
        or candidate.stat().st_size != record["bytes"]
    ):
        raise ValueError("PUBLIC_RETIRED_SOURCE_SIZE:" + name)
    if digest(candidate) != expected:
        raise ValueError("PUBLIC_RETIRED_SOURCE_SHA:" + name)
    return candidate.stat().st_size


def public_retired_sources(repository=ROOT):
    """Recompute the finite required public byte fixtures, with no file writes."""
    repository = Path(repository).absolute()
    selected = {}
    for table, container, origin_key, saved_key, consumer, approved in PUBLIC_RETIRED_TABLES:
        document = json.loads((repository / table).read_text(encoding="utf-8"))
        if container:
            if not isinstance(document, dict) or document.get("schema") != 1:
                raise ValueError("PUBLIC_RETIRED_MANIFEST_SCHEMA:" + table)
            rows = document.get(container)
        else:
            rows = document
        if not isinstance(rows, list):
            raise ValueError("PUBLIC_RETIRED_MANIFEST_ROWS:" + table)
        seen = set()
        for row in rows:
            if not isinstance(row, dict):
                raise ValueError("PUBLIC_RETIRED_MANIFEST_ROW:" + table)
            origin = row.get(origin_key)
            if not container and not retired_audit_origin(origin):
                continue
            if origin not in approved or origin in seen:
                raise ValueError("PUBLIC_RETIRED_UNAPPROVED_OR_DUPLICATE_ORIGIN:" + str(origin))
            seen.add(origin)
            name = row.get(saved_key)
            # Refuse unapproved destinations before reading any of their bytes.
            if name != approved[origin] or name in selected:
                raise ValueError("PUBLIC_RETIRED_SOURCE_MAPPING:" + str(name))
            validate_public_retired_file(repository, name, row)
            selected[name] = {
                "path": name, "source": origin, "sha256": row["sha256"],
                "bytes": (repository / name).stat().st_size, "manifest": table, "consumer": consumer,
                "scope": "required current host byte-provenance fixture; never an APK source/deployment input",
            }
        if seen != set(approved):
            raise ValueError("PUBLIC_RETIRED_MANIFEST_REQUIRED_MEMBERS:" + table)
    return selected


def exclusion(name, path, public_retired=None, repository=ROOT):
    parts = Path(name).parts
    if name in DETACHED_FINAL_REPORTS:
        return DETACHED_REASON
    if path.suffix.lower() in PRIVATE_SUFFIXES or (path.name.startswith(".env") and path.name != ".env.example"):
        return "local credential material is excluded"
    if path.is_symlink() or (hasattr(path, "is_junction") and path.is_junction()):
        return "linked source is not followed"
    if "history" in parts or "private-device-audits" in parts or name.startswith("docs/evidence/"):
        if public_retired and name in public_retired:
            if path.absolute() != (repository / name).absolute():
                raise ValueError("PUBLIC_RETIRED_SELECTION_PATH:" + name)
            validate_public_retired_file(repository, name, public_retired[name])
            return None
        return "immutable or private historical original; retained in workspace"
    if name.startswith(("docs/audits/2026-09-27-", "website/artifacts/", "website/releases/")):
        return "historical evidence or generated delivery output; retained in workspace"
    if path.suffix.lower() in GENERATED_ARCHIVES:
        return "generated binary archive; input digest remains independently recorded"
    if not name.startswith(PREFIXES) and name not in TOP:
        return "temporary or unrelated local work is outside project source scope"
    if name.startswith("docs/releases/v0.2.7-build156-"):
        return "final report delivered separately to avoid a self-referential archive digest"
    return None


def self_test_public_retired_sources():
    class Selection(unittest.TestCase):
        def setUp(self):
            parent = ROOT / "app/build/audit-build156/public-retired-source-selection-tests"
            parent.mkdir(parents=True, exist_ok=True)
            self.directory = Path(tempfile.mkdtemp(prefix="selection-", dir=parent)).absolute()
            # Retain the tiny owned fixture as audit evidence; no recursive deletion.
            for table, *_ in PUBLIC_RETIRED_TABLES:
                target = self.directory / table
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / table, target)
            self.actual = public_retired_sources()
            for name in self.actual:
                target = self.directory / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / name, target)
            self.table = self.directory / PUBLIC_RETIRED_TABLES[0][0]
            self.document = json.loads(self.table.read_text(encoding="utf-8"))
            self.name = next(iter(self.actual))

        def test_current_manifest_exact_27_public_fixtures(self):
            self.assertEqual(27, len(public_retired_sources()))
            self.assertEqual(215974, sum(row["bytes"] for row in public_retired_sources().values()))

        def test_exact_selection_and_private_history_exclusion(self):
            selected = public_retired_sources(self.directory)
            self.assertEqual(set(self.actual), set(selected))
            for name in selected:
                self.assertIsNone(exclusion(name, self.directory / name, selected, self.directory))
            for name in ("tools/history/documents/private.md",
                         "tools/history/private-device-audits/private.java",
                         "tools/history/unknown.java", "tools/history/local.key"):
                self.assertIsNotNone(exclusion(name, self.directory / name, selected, self.directory))

        def test_byte_drift_and_missing_source_are_rejected(self):
            source = self.directory / self.name
            original = source.read_bytes()
            source.write_bytes(bytes([original[0] ^ 1]) + original[1:])
            with self.assertRaisesRegex(ValueError, "PUBLIC_RETIRED_SOURCE_SHA"):
                public_retired_sources(self.directory)
            source.write_bytes(original)
            source.unlink()
            with self.assertRaises(FileNotFoundError):
                public_retired_sources(self.directory)

        def test_unapproved_origin_private_docs_suffix_escape_and_duplicate_are_rejected(self):
            for key, value in (
                ("saved", "../../outside.java"),
                ("saved", "tools/history/unknown.pem"),
                ("saved", "tools/history/documents/private.md"),
                ("source", "docs/releases/private.md"),
                ("sha256", "0" * 64),
            ):
                with self.subTest(key=key, value=value):
                    document = json.loads(json.dumps(self.document))
                    document["sources"][0][key] = value
                    self.table.write_text(json.dumps(document), encoding="utf-8")
                    with self.assertRaises((ValueError, FileNotFoundError)):
                        public_retired_sources(self.directory)
            document = json.loads(json.dumps(self.document))
            document["sources"].append(document["sources"][0])
            self.table.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "DUPLICATE_ORIGIN"):
                public_retired_sources(self.directory)

        def test_link_and_junction_metadata_are_rejected(self):
            target = self.directory / self.name
            # Model metadata denial directly: host Windows symlink privileges
            # are not required or claimed by this selection-policy self-test.
            original = Path.is_symlink
            with mock.patch.object(Path, "is_symlink",
                                   lambda node: node == target or original(node)):
                with self.assertRaisesRegex(ValueError, "PUBLIC_RETIRED_SOURCE_LINK"):
                    public_retired_sources(self.directory)
            parent = target.parent
            with mock.patch.object(Path, "is_junction", lambda node: node == parent, create=True):
                with self.assertRaisesRegex(ValueError, "PUBLIC_RETIRED_SOURCE_LINK"):
                    public_retired_sources(self.directory)

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(Selection)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful():
        raise ValueError("PUBLIC_RETIRED_SELECTION_SELF_TEST_FAILED")
    print(json.dumps({"status": "PASS_PUBLIC_RETIRED_SELECTION_SELF_TESTS",
                      "tests": result.testsRun,
                      "scope": "owned temporary fixtures and selection/denial policy; links use explicit modeled metadata, not an OS symlink claim"}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/audit-build156")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--check-public-retired-sources", action="store_true")
    selection.add_argument("--self-test-public-retired-sources", action="store_true")
    args = parser.parse_args()
    if args.check_public_retired_sources:
        required = public_retired_sources()
        print(json.dumps({"status": "PASS_PUBLIC_RETIRED_SOURCE_BYTES", "files": len(required),
                          "bytes": sum(row["bytes"] for row in required.values()),
                          "sources": list(required.values()), "zipCreated": False}))
        return
    if args.self_test_public_retired_sources:
        self_test_public_retired_sources()
        return
    if args.receipt is None:
        parser.error("--receipt is required unless a public-retired selection check is requested")
    if (ROOT / "docs/audits/build156/execution-ledger-final.json").exists():
        raise ValueError("DO_NOT_REFREEZE_AFTER_FINAL_LEDGER: the ZIP/receipt/ledger SHA chain would become circular")
    classification = json.loads((ROOT / "docs/audits/build156/classification-draft.json").read_text(encoding="utf-8"))
    if classification.get("sourceCoverage") != 236 or classification.get("reviewCoverage") != 236 \
            or classification.get("pendingOwnerLanes") or classification.get("remainingSoftwareIds"):
        raise ValueError("COMPLETE_CURRENT_236_CLASSIFICATION_REQUIRED_BEFORE_SOURCE_ZIP")
    for entry in classification.get("inputFingerprints", []):
        if digest(ROOT / entry["path"]) != entry["sha256"]:
            raise ValueError("CURRENT_CLASSIFICATION_INPUT_CHANGED:" + entry["path"])
    receipt_path = args.receipt.resolve(strict=True)
    report = json.loads(receipt_path.read_text(encoding="utf-8"))
    if report.get("status") != "PASS_WITH_EXPLICIT_DEVICE_GAPS" or report.get("verificationSchema") != 2:
        raise ValueError("FINAL_SOFTWARE_RECEIPT_REQUIRED")
    stored = report["sourceSnapshot"]
    stored_path = Path(stored["path"])
    if digest(stored_path) != stored["sha256"]:
        raise ValueError("SOFTWARE_SOURCE_RECEIPT_CHANGED")
    spec = importlib.util.spec_from_file_location("local_stability", ROOT / "tools/verify-stability.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    current = module.source_snapshot()
    if current != json.loads(stored_path.read_text(encoding="utf-8")):
        raise ValueError("SOFTWARE_SOURCE_CHANGED_AFTER_VERIFICATION")
    for item in report["apks"]:
        if item["versionCode"] != 156 or digest(Path(item["path"])) != item["sha256"]:
            raise ValueError("APK_RECEIPT_CHANGED")
    names = set(subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT
    ).decode("utf-8").split("\0")) - {""}
    public_retired = public_retired_sources()
    # Current host byte-provenance fixtures are explicit even if git ignores
    # their history path. Every member remains bound to its existing manifest.
    names.update(public_retired)
    selected, excluded = {}, [
        {"path": name, "reason": DETACHED_REASON, "presentAtFreeze": False}
        for name in sorted(DETACHED_FINAL_REPORTS) if not (ROOT / name).exists()
    ]
    for name in sorted(names):
        path = ROOT / name
        if not path.is_file() and not path.is_symlink():
            continue
        reason = exclusion(name, path, public_retired)
        if reason:
            row = {"path": name, "reason": reason}
            if name in DETACHED_FINAL_REPORTS:
                row["presentAtFreeze"] = True
                row["hashScope"] = "pre-finalization bytes only; final external SHA is bound by final delivery report"
            if path.is_file() and not path.is_symlink():
                row.update(bytes=path.stat().st_size, sha256=digest(path))
            excluded.append(row)
        else:
            selected[name] = digest(path)
    args.output.mkdir(parents=True, exist_ok=True)
    archive_path = args.output / "build156-complete-project-source.zip"
    with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, expected in selected.items():
            data = (ROOT / name).read_bytes()
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError("PROJECT_SOURCE_CHANGED_DURING_EXPORT:" + name)
            archive.writestr(name, data)
        archive.writestr("PROJECT-FILES.sha256.json", json.dumps(selected, sort_keys=True, indent=2) + "\n")
        archive.writestr("APK-INPUTS.sha256.json", json.dumps(current, sort_keys=True, indent=2) + "\n")
    with zipfile.ZipFile(archive_path) as archive:
        if set(archive.namelist()) != set(selected) | {"PROJECT-FILES.sha256.json", "APK-INPUTS.sha256.json"}:
            raise ValueError("PROJECT_ARCHIVE_MEMBERS")
        for name, expected in selected.items():
            if hashlib.sha256(archive.read(name)).hexdigest() != expected or digest(ROOT / name) != expected:
                raise ValueError("PROJECT_ARCHIVE_BYTES:" + name)
    if module.source_snapshot() != current:
        raise ValueError("SOFTWARE_SOURCE_CHANGED_DURING_EXPORT")
    result = {
        "schema": 1,
        "archive": {"path": str(archive_path.resolve()), "bytes": archive_path.stat().st_size,
                    "sha256": digest(archive_path), "members": len(selected) + 2},
        "sourceFileCount": len(selected), "excludedCount": len(excluded), "excluded": excluded,
        "softwareReceipt": {"path": str(receipt_path), "sha256": digest(receipt_path)},
        "apkInputCount": len(current), "verification": "every project member and APK input matched the current workspace",
        "detachedFinalReports": sorted(DETACHED_FINAL_REPORTS),
        "publicHistoricalSourceFixtures": list(public_retired.values()),
        "finalizationOrder": "freeze once before the final ledger; do not refreeze after detached reports are written",
        "device": "not established by source ZIP or software receipt; see the separate actual device delivery receipt",
    }
    (args.output / "complete-project-source-receipt.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (args.output / "final-source-sha256.json").write_text(json.dumps(current, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"files": len(selected), "apkInputs": len(current), "archive": result["archive"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
