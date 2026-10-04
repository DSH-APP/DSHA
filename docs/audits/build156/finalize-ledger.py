#!/usr/bin/env python3
"""Read-only receipt verification and detached build156 audit finalization.

No Gradle, APK generation, phone action, deployment or software-input write is
performed here. Freeze the project ZIP once before running this finalizer.
"""
from __future__ import annotations

import argparse
import collections
import copy
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
HEX = re.compile(r"^[a-f0-9]{64}$")
STAGES = {"fast", "runtime", "package", "windows", "linux"}
DETACHED = {
    "docs/audits/build156/execution-ledger-final.json",
    "docs/releases/build156-remaining-work-20261003.md",
    "docs/releases/v0.2.7-build156-20261003.md",
}
REQUIRED_SOFTWARE = {
    "gradle-verification", "runtime-input-contract", "release-acceptance-contract",
    "apk-assets", "standard-elf", "low-elf", "standard-signature", "low-signature",
    "standard-manifest", "low-manifest", "plugin-upgrade-gate", "recovery-apk",
    "recovery-browser-overlay", "recovery-profile-boot", "mobile-modal", "adb-flow",
    "adb-vscreen-bridge", "web-ui-host-fixtures", "plugin-downloads",
    "third-party-notices", "third-party-apk",
}


class ReceiptError(ValueError):
    pass


def require(value, message):
    if not value:
        raise ReceiptError(message)


def sha(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def read(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


class Verify:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.observed = {}

    def path(self, value, relative_to=None):
        require(isinstance(value, str) and bool(value), "receipt file path is missing")
        path = Path(value.replace("\\", "/"))
        if not path.is_absolute():
            direct = self.root / path
            path = direct if direct.exists() or relative_to is None else relative_to / path
        path = path.resolve(strict=True)
        require(path.is_relative_to(self.root) and path.is_file(), "receipt must reference a real workspace file: " + value)
        return path

    def ref(self, path: Path):
        return {"path": path.relative_to(self.root).as_posix(), "sha256": sha(path), "bytes": path.stat().st_size}

    def file(self, value, expected, relative_to=None):
        require(isinstance(expected, str) and HEX.fullmatch(expected), "invalid declared SHA-256 for " + str(value))
        path = self.path(value, relative_to)
        actual = sha(path)
        require(actual == expected, "receipt byte mismatch: " + str(value))
        self.observed[path] = actual
        return path

    def log(self, entry, relative_to=None):
        require(entry.get("exitCode") == 0, "command is not successful: " + str(entry.get("name", entry.get("path"))))
        return self.file(entry.get("log"), entry.get("logSha256", entry.get("sha256")), relative_to)

    def snapshot(self, record):
        path = self.file(record.get("path"), record.get("sha256"))
        snapshot = read(path)
        require(isinstance(snapshot, dict) and bool(snapshot), "sourceSnapshot must be an exact path/SHA map")
        for name, expected in snapshot.items():
            require(isinstance(name, str) and not Path(name).is_absolute() and ".." not in Path(name).parts, "invalid sourceSnapshot member")
            self.file(name, expected)
        return snapshot

    def source_bindings(self, references, snapshot):
        result = []
        for reference in references:
            if not isinstance(reference, str):
                result.append({"reference": reference, "scope": "non-file owner reference; no byte-equivalence claim"})
                continue
            name = reference.replace("\\", "/")
            if name in snapshot:
                result.append({"path": name, "sha256": snapshot[name], "scope": "actual final software sourceSnapshot input"})
            elif not Path(name).is_absolute() and ".." not in Path(name).parts and (self.root / name).is_dir():
                prefix = name.rstrip("/") + "/"
                members = [{"path": key, "sha256": expected} for key, expected in snapshot.items() if key.startswith(prefix)]
                result.append({"directory": name, "members": members, "scope": "only declared final software sourceSnapshot members expanded; no unlisted file digest or execution claimed"})
            elif not Path(name).is_absolute() and ".." not in Path(name).parts and (self.root / name).is_file():
                result.append({"path": name, "sha256": sha(self.root / name), "scope": "current file outside APK software sourceSnapshot; not inferred compiled/tested"})
            else:
                result.append({"reference": reference, "scope": "directory/retired/non-file reference; no current input SHA invented"})
        return result


def validate_software(v: Verify, path: Path):
    body = read(path)
    require(body.get("verificationSchema") == 2 and body.get("status") == "PASS_WITH_EXPLICIT_DEVICE_GAPS", "--software requires the completed signed software manifest, never RUNNING/FAILED/unsigned/software-only")
    require(body.get("finishedAt"), "software manifest is unfinished")
    snapshot = v.snapshot(body.get("sourceSnapshot", {}))
    # Reproduce only the read-only selection rule, never import an execution tool.
    names = set(subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=v.root).decode("utf-8").split("\0")) - {""}
    selected = set()
    prefixes = ("app/src/", "tools/", "gradle/", "ci/", ".github/", "agent-skills/", "website/")
    top = {"app/build.gradle", "build.gradle", "settings.gradle", "gradle.properties", "build.sh", "gradlew", "gradlew.bat"}
    for name in names:
        parts = name.replace("\\", "/").split("/")
        if (v.root / name).is_file() and "history" not in parts and "private-device-audits" not in parts and (name.startswith(prefixes) or name in top):
            selected.add(name)
    for pattern in ("*.bin", "*.sha256", "*.version", "*.layout"):
        selected.update(value.relative_to(v.root).as_posix() for value in (v.root / "app/src/main/assets").glob(pattern))
    require(set(snapshot) == selected, "current software input inventory differs from sourceSnapshot")
    gradle = (v.root / "app/build.gradle").read_text(encoding="utf-8")
    code = re.search(r"^\s*versionCode\s+(\d+)\s*$", gradle, re.M)
    version = re.search(r'^\s*versionName\s+"([^"]+)"', gradle, re.M)
    require(code and int(code[1]) == 156 and version, "current application source is not156")
    commands = {}
    for command in body.get("commands", []):
        require(command.get("name") not in commands, "duplicate software command name")
        commands[command["name"]] = command
        v.log(command, path.parent)
    require(REQUIRED_SOFTWARE <= set(commands), "software integrated gate is missing required commands: " + str(sorted(REQUIRED_SOFTWARE - set(commands))))
    junit = {}
    for flavor in ("Standard", "Low"):
        actual = dict(tests=0, failures=0, errors=0, skipped=0)
        xmls = sorted((path.parent / "junit" / ("test" + flavor + "DebugUnitTest")).glob("*.xml"))
        require(xmls, "no actual fresh JUnit XML for " + flavor)
        xml_refs = []
        skipped = []
        for xml in xmls:
            suite = ET.parse(xml).getroot()
            for key in actual:
                actual[key] += int(suite.get(key, "0"))
            for test in suite.findall("testcase"):
                skip = test.find("skipped")
                if skip is not None:
                    skipped.append({"class": test.get("classname"), "name": test.get("name"), "reason": skip.get("message") or (skip.text or "").strip() or "JUnit reported skipped; not counted as pass"})
            xml_refs.append(v.ref(xml))
        require(actual == body.get("junit", {}).get(flavor) and actual["tests"] > 0 and not actual["failures"] and not actual["errors"], "JUnit counts/failures differ for " + flavor)
        junit[flavor] = {**actual, "passed": actual["tests"] - actual["skipped"], "skippedCases": skipped, "xmls": xml_refs}
    cert = read(v.root / "ci/release-identity.json")["certificateSha256"]
    artifacts = body.get("apks", [])
    require(len(artifacts) == 2 and {value.get("flavor") for value in artifacts} == {"standard", "low"}, "two real signed flavors are required")
    for apk in artifacts:
        flavor = apk["flavor"]
        require(apk.get("package") == "com.dsh.client" and apk.get("versionCode") == 156 and apk.get("versionName") == version[1] + ("low" if flavor == "low" else "") and apk.get("certificateSha256") == cert, "formal APK identity mismatch")
        require(apk.get("abi") == ["arm64-v8a"] and apk.get("minSdk") == (23 if flavor == "low" else 30), "formal flavor/API/ABI mismatch")
        actual = v.file(apk.get("path"), apk.get("sha256"))
        require(actual.stat().st_size == apk.get("size"), "actual APK size differs")
        signature = v.path(commands[flavor + "-signature"]["log"]).read_text(encoding="utf-8", errors="replace")
        require(re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)", signature) == [cert], "actual signing log has another signer")
        for scheme in ("v1", "v2", "v3"):
            require(re.search(r"Verified using " + scheme + r" scheme[^\n]+true", signature), "actual signature scheme missing: " + scheme)
        badging = v.path(commands[flavor + "-manifest"]["log"]).read_text(encoding="utf-8", errors="replace")
        package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
        require(package and package[1] == apk["package"] and package[2] == "156" and package[3] == apk["versionName"] and "application-debuggable" not in badging and "native-code: 'arm64-v8a'" in badging and f"sdkVersion:'{apk['minSdk']}'" in badging, "actual APK is debuggable or has unexpected package/version/API/ABI")
    return body, snapshot, {"status": body["status"], "sourceSnapshot": body["sourceSnapshot"], "junit": junit, "apks": [{key: value[key] for key in ("flavor", "package", "versionCode", "versionName", "minSdk", "abi", "size", "sha256", "certificateSha256")} for value in artifacts], "device": "software receipt does not establish phone acceptance"}


def validate_host(v: Verify, path: Path, snapshot):
    outer = read(path)
    documents = [(path, outer)]
    if "reports" in outer:
        require(outer.get("status") in {"PASS_HOST_AND_PACKAGE", "PASS_FOR_CURRENT_HOST_AND_PACKAGE", "PASS_COMPLETE_ACTIVE_HOST_MANIFEST"}, "host composite is not a completed pass")
        documents = []
        for record in outer["reports"]:
            child = v.file(record.get("path"), record.get("sha256"), path.parent)
            documents.append((child, read(child)))
    declared = read(v.root / "tools/host-tests.manifest.json")["tests"]
    active = {value["path"]: value for value in declared if value["stage"] in STAGES}
    expected = set(active)
    required_stages = {value["stage"] for value in active.values()}
    actual, stages, receipts, fixture_kinds = {}, set(), [], set()
    for current_path, body in documents:
        require(body.get("status") == "PASS_FOR_SELECTED_STAGES" and not body.get("failures"), "host manifest has unfinished/failed rows")
        stages.update(body.get("selectedStages", []))
        for test in body.get("tests", []):
            require(test.get("status") == "PASS", "host child did not pass: " + str(test.get("path")))
            require(test.get("path") in snapshot, "host test input is absent from actual software snapshot")
            v.log(test, current_path.parent)
            actual[test["path"]] = {"path": test["path"], "log": test["log"], "logSha256": test["logSha256"], "finalSoftwareInputSha256": snapshot[test["path"]], "scope": "latest SHA comes from final root software snapshot, not retroactively from host/focused logs; old focused source equality is not inferred"}
        for proof in body.get("runtimeFixtureVerification", []):
            require(proof.get("status") == "PASS_FINAL_COMPLETE_BYTE_RECHECK", "host runtime final byte check did not pass")
            fixture_kinds.add(proof.get("kind"))
            require(proof.get("directory") and HEX.fullmatch(proof.get("markerSha256", "")) and HEX.fullmatch(proof.get("contentDigest", "")), "host final fixture proof is missing actual directory/marker/content identity")
            v.file(str(Path(proof["directory"]) / "dsha-test-runtime.json"), proof["markerSha256"])
        receipts.append(v.ref(current_path))
    executed_stages = {active[name]["stage"] for name in actual if name in active}
    require(required_stages <= stages and executed_stages == required_stages and set(actual) == expected, "complete active host coverage differs: missing=" + str(sorted(expected-set(actual))) + "; extra=" + str(sorted(set(actual)-expected)))
    require({"raw", "managed"} <= fixture_kinds, "host final raw/managed runtime byte proofs are incomplete")
    counts = dict(collections.Counter(value["stage"] for value in active.values()))
    return {"status": "PASS_COMPLETE_ACTIVE_HOST_MANIFEST", "selectedStages": sorted(stages), "executedStages": sorted(executed_stages), "declaredStageCounts": counts, "allDeclaredStageCounts": dict(collections.Counter(value["stage"] for value in declared)), "excludedDeclaredEntries": [copy.deepcopy(value) for value in declared if value["path"] not in expected], "tests": len(actual), "receipts": receipts, "checks": list(actual.values()), "packageScope": "Declared package host entries: " + str(counts.get("package", 0)) + "; selectedStages alone is not execution. Actual signed APK/assets/ELF/plugin/recovery checks are verified separately in --software."}


def validate_style(v: Verify, path: Path, snapshot):
    body = read(path)
    inventory_path = v.root / "tools/java-format-files.json"
    inventory_sha = sha(inventory_path)
    require(snapshot.get("tools/java-format-files.json") == inventory_sha, "style inventory must bind actual software sourceSnapshot")
    declared = read(inventory_path)["files"]
    count = len(declared)
    require(count > 0 and len(set(declared)) == count, "current Java inventory is empty/duplicated")
    require(body.get("status") == "PASS_CURRENT_STYLE" and body.get("files") == count and body.get("verified") == count and not body.get("missing") and not body.get("extra"), "current declared style composite is incomplete")
    entries = body.get("entries", [])
    require(len(entries) == count and {row["file"] for row in entries} == set(declared), "style current inventory mismatch")
    lock = read(v.root / "tools/java-format.lock.json")
    # Mirror the actual owned-source inventory rule, including late new tests.
    owned = set()
    excluded_parts = {"history", "upstream", "vendor", "third_party", "third-party", "generated"}
    excluded_files = set(lock["protectedFiles"]) | set(lock["generatedFiles"])
    for source_set in ("main", "low", "standard", "debug", "androidTest", "test"):
        directory = v.root / "app/src" / source_set / "java/com/deepseekharness/app"
        require(directory.is_dir(), "owned Java source set is missing")
        for current in directory.rglob("*.java"):
            name = current.relative_to(v.root).as_posix()
            if name in excluded_files or excluded_parts & set(current.relative_to(directory).parts):
                continue
            require(current.is_file() and not current.is_symlink(), "owned Java source is linked/non-file")
            owned.add(name)
    helper = "tools/java-format/JavaTokenFingerprint.java"
    if helper not in lock["protectedFiles"]:
        owned.add(helper)
    require(owned == set(declared), "current declared style inventory omits/adds owned sources; run --plan rather than retaining a fixed count")
    receipts = {}
    for row in entries:
        require(row["file"] in snapshot and snapshot[row["file"]] == row["sha256"] and HEX.fullmatch(row.get("javaTokenSha256", "")), "style entry does not bind actual software bytes/tokens: " + row["file"])
        receipt_path = v.file(row["receipt"], row["receiptSha256"])
        if receipt_path not in receipts:
            receipt = read(receipt_path)
            require(all(receipt.get(key) == lock[key] for key in ("tool", "version")) and receipt.get("artifactSha256") == lock["sha256"] and receipt.get("mode") in {"check", "write"}, "style receipt is not pinned formatter proof")
            records = {entry["file"]: entry for entry in receipt["files"]}
            require(len(records) == len(receipt["files"]), "style receipt has duplicate files")
            receipts[receipt_path] = (receipt, records)
        receipt, records = receipts[receipt_path]
        record = records.get(row["file"], {})
        require(record.get("formattedSha256") == row["sha256"] and record.get("javaTokenSha256") == row["javaTokenSha256"], "style/token proof does not correspond to final bytes")
        require(receipt["mode"] == "write" or record.get("needsFormatting") is False, "current check still requires formatting")
    return {"status": "PASS_CURRENT_STYLE", "files": count, "inventory": v.ref(inventory_path), "receipt": v.ref(path), "pinnedFormatter": {"tool": lock["tool"], "version": lock["version"], "sha256": lock["sha256"]}, "proofReceipts": [v.ref(value) for value in receipts], "scope": "actual software SHA equals formatted bytes and compiler token proof; does not re-label old focused test runs as post-format execution"}


def validate_lint(v: Verify, path: Path, snapshot):
    body = read(path)
    require(body.get("status") in {"CLASSIFIED_CURRENT", "PASS_CLASSIFIED", "PASS_CLASSIFIED_CURRENT"}, "current lint classification is unfinished")
    reports = body.get("reports", [])
    require(len(reports) == 2 and {row.get("flavor") for row in reports} == {"standard", "low"}, "both current Release Lint reports are required")
    result = {}
    for report in reports:
        xml = v.file(report.get("path"), report.get("sha256"), path.parent)
        actual = ET.parse(xml).getroot().findall("issue")
        classified = report.get("issues", [])
        require(len(classified) == len(actual), "every actual Lint issue must be classified")
        indexed = {row["issueIndex"]: row for row in classified}
        require(set(indexed) == set(range(len(actual))) and len(indexed) == len(classified), "lint issue indices must be exact zero-based coverage")
        severity, classes = collections.Counter(), collections.Counter()
        for index, issue in enumerate(actual):
            row = indexed[index]
            require(all(row.get(key) == issue.get(key) for key in ("id", "severity", "message")), "lint classified issue differs from actual XML")
            require(isinstance(row.get("classification"), str) and row["classification"] and isinstance(row.get("reason"), str) and row["reason"], "lint issue lacks an explained classification")
            require(not row.get("softwareActionRequired") and row["classification"] not in {"software_remaining", "unresolved_software"}, "Lint still has a declared required software fix")
            if "locations" in row:
                expected_locations = [dict(location.attrib) for location in issue.findall("location")]
                require(row["locations"] == expected_locations, "Lint classification must retain every actual location")
            for evidence in row.get("sourceEvidence", []):
                if evidence.get("available") and evidence.get("sha256"):
                    source = evidence["file"].replace("\\", "/")
                    # Generated/SDK evidence can be outside APK inputs; retain
                    # its declared scope without inventing sourceSnapshot SHA.
                    if not Path(source).is_absolute() and ".." not in Path(source).parts and (v.root / source).is_file():
                        v.file(source, evidence["sha256"])
                        if source in snapshot:
                            require(snapshot[source] == evidence["sha256"], "Lint source classification differs from actual software bytes")
            severity[issue.get("severity")] += 1
            classes[row["classification"]] += 1
        require(not severity["Error"] and not severity["Fatal"], "Release Lint still has Error/Fatal")
        result[report["flavor"]] = {"xml": v.ref(xml), "issues": len(actual), "severityCounts": dict(severity), "classificationCounts": dict(classes), "warnings": severity["Warning"], "scope": "every current XML issue explained; warnings are not claimed eliminated"}
    return {"status": "PASS_CLASSIFIED_CURRENT", "receipt": v.ref(path), "reports": result, "unknownReviewRequired": copy.deepcopy(body.get("unknownReviewRequired", [])), "scope": "location classifications are retained; uncertainty is not automatically accepted as harmless"}


def validate_website(v: Verify, path: Path, software, snapshot):
    body = read(path)
    require(body.get("result") == "PASS" and body.get("artifactVersionCode") == 156 and body.get("testCount", 0) >= 28, "actual156 website did not pass; no155 preflight substitution")
    for record in body.get("inputFingerprints", []):
        v.file(record["path"], record["sha256"])
        if record["path"] in snapshot:
            require(snapshot[record["path"]] == record["sha256"], "website code differs from final software snapshot")
    require(body.get("inputFingerprints"), "website input proof is empty")
    checks = body.get("checks", [])
    require(len(checks) == 2, "website actual build and test logs are both required")
    for check in checks:
        v.log(check, path.parent)
    test_log = v.path(checks[1]["log"]).read_text(encoding="utf-8")
    counts = {name: re.search(r"(?:#|ℹ) " + name + r" (\d+)", test_log) for name in ("tests", "pass", "fail", "skipped", "cancelled")}
    require(all(counts.values()) and int(counts["tests"][1]) == body["testCount"] and int(counts["pass"][1]) == body["testCount"] and all(int(counts[name][1]) == 0 for name in ("fail", "skipped", "cancelled")), "website actual test log does not establish reported complete passes")
    expected = {value["flavor"]: value["sha256"] for value in software["apks"]}
    actual = {value["flavor"]: value["sha256"] for value in body.get("artifactHashes", [])}
    require(expected == actual, "website download APKs differ from actual software candidates")
    manifest = v.file("app/build/release-manifest.json", body.get("manifestSha256"))
    feed = read(manifest)["releases"][0]
    require(feed["versionCode"] == 156 and {value["flavor"]: value["sha256"] for value in feed["artifacts"]} == expected, "current public manifest is not actual156")
    for artifact in feed["artifacts"]:
        from urllib.parse import urlparse, unquote
        url = urlparse(artifact["url"])
        require(url.scheme == "https" and url.netloc == "dsha.cc", "website APK URL must remain configured official HTTPS")
        target = "website/dist/" + unquote(url.path).lstrip("/")
        apk = v.file(target, artifact["sha256"])
        require(apk.stat().st_size == artifact["bytes"], "website actual APK byte count differs")
        sidecar = v.path(target + ".sha256").read_text(encoding="ascii").split()
        require(sidecar == [artifact["sha256"], artifact["filename"]], "website APK sidecar differs")
    return {"status": "PASS_ACTUAL156_LOCAL_WEBSITE", "receipt": v.ref(path), "tests": body["testCount"], "versionCode": 156, "scope": "local build/download bytes and behavior only; no production deployment"}


def validate_project(v: Verify, path: Path, software_path: Path, snapshot):
    body = read(path)
    source_receipt = body.get("softwareReceipt", {})
    require(v.file(source_receipt.get("path"), source_receipt.get("sha256")) == software_path, "project archive is linked to another software receipt")
    info = body.get("archive", {})
    archive_path = v.file(info.get("path"), info.get("sha256"))
    require(archive_path.stat().st_size == info.get("bytes"), "project ZIP byte count differs")
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "project ZIP has duplicate members")
        source = json.loads(archive.read("PROJECT-FILES.sha256.json"))
        inputs = json.loads(archive.read("APK-INPUTS.sha256.json"))
        require(inputs == snapshot, "project ZIP APK input manifest differs from actual software sourceSnapshot")
        require(set(names) == set(source) | {"PROJECT-FILES.sha256.json", "APK-INPUTS.sha256.json"}, "project ZIP contains undeclared members")
        require(not DETACHED & set(names), "project ZIP contains detached reports and would cause a digest cycle")
        require("docs/audits/build156/finalize-ledger.py" in source and "docs/audits/build156/classification-draft.json" in source and "docs/audits/build156/剩余现状.md" in source, "project ZIP is missing current finalizer or complete classification")
        for name, expected in source.items():
            require(not Path(name).is_absolute() and ".." not in Path(name).parts and HEX.fullmatch(expected), "invalid project source member")
            require(hashlib.sha256(archive.read(name)).hexdigest() == expected, "project ZIP member digest differs: " + name)
            v.file(name, expected)
        require(body.get("sourceFileCount") == len(source) and info.get("members") == len(names), "project member counts differ")
    excluded = {row["path"]: row for row in body.get("excluded", [])}
    require(DETACHED <= set(excluded), "detached final report exclusions must be explicit")
    for name in DETACHED:
        require(excluded[name].get("reason"), "detached report exclusion lacks reason")
    return {"status": "PASS_EXACT_SOURCE_ZIP", "receipt": v.ref(path), "archive": {**info, "path": archive_path.relative_to(v.root).as_posix()}, "sourceFiles": body["sourceFileCount"], "apkInputs": len(snapshot), "detachedFinalReports": sorted(DETACHED), "scope": "archived source bytes at freeze; later final ledger/remaining/delivery reports are separate, not claimed inside ZIP"}


def validate_device(v: Verify, path: Path, software_path: Path, software, snapshot):
    body = read(path)
    require(body.get("verificationSchema") == 2 and body.get("status") == "PASS_FOR_EXECUTED_SCOPE", "device delivery receipt is not a completed formal acceptance")
    link = body.get("softwareReceipt", {})
    require(v.file(link.get("path"), link.get("sha256")) == software_path, "device receipt links another software receipt")
    require(v.snapshot(body.get("sourceSnapshot", {})) == snapshot, "device delivery sourceSnapshot differs")
    artifacts = {row["flavor"]: row for row in software["apks"]}
    delivered = {row["flavor"]: row for row in body.get("apks", [])}
    require(set(delivered) == set(artifacts), "device delivery has another flavor set")
    device = body.get("device", {})
    require(device.get("status") == "MATCHED_EXTERNAL_EVIDENCE", "device evidence is not matched")
    evidence_path = v.file(device.get("evidence"), device.get("sha256"))
    evidence = read(evidence_path)
    cert = read(v.root / "ci/release-identity.json")["certificateSha256"]
    require(evidence.get("schema") == 1 and evidence.get("package") == "com.dsh.client" and evidence.get("versionCode") == 156 and evidence.get("certificateSha256") == cert and evidence.get("serial") and evidence.get("nonDestructive") is True and evidence.get("firstInstallTimePreserved") is True, "device actual identity/non-destructive preservation evidence differs")
    scopes = {}
    for flavor, candidate in artifacts.items():
        tested = evidence.get("flavors", {}).get(flavor, {})
        require(tested.get("sha256") == candidate["sha256"] and tested.get("result") == "PASS", "device evidence does not bind actual APK: " + flavor)
        checks = tested.get("checks", [])
        require({"web-ready", "existing-data-preserved", "plugins-visible", "recovery-ready", "no-crash"} <= set(checks), "device required checks are incomplete: " + flavor)
        row = delivered[flavor]
        require(row.get("sha256") == candidate["sha256"], "delivered APK differs from actual accepted software")
        target = row.get("deliveredPath")
        require(target and v.file(target, candidate["sha256"]).parent == v.root / "release", "accepted final release file is missing")
        sidecar = v.path(str(Path(target).with_suffix(".apk.sha256"))).read_text(encoding="ascii").split()
        require(sidecar == [candidate["sha256"], Path(target).name], "final release sidecar differs")
        scopes[flavor] = {"sha256": candidate["sha256"], "checks": checks, "release": v.ref(v.path(target))}
    require(body.get("acceptance") == software.get("acceptance"), "delivery acceptance contract differs from software receipt")
    require(body.get("acceptance"), "actual device acceptance must include the changed-source contract")
    acceptance_path = v.root / "tools/release_acceptance.py"
    previous_bytecode = sys.dont_write_bytecode
    sys.dont_write_bytecode = True
    try:
        spec = importlib.util.spec_from_file_location("read_only_device_acceptance156", acceptance_path)
        acceptance_module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(acceptance_module)
        acceptance_module.validate(evidence, body["acceptance"])
    finally:
        sys.dont_write_bytecode = previous_bytecode
    # Preserve arbitrary extra acceptance checks without copying serial, keys or raw logs.
    return {"status": "PASS_FOR_EXECUTED_DEVICE_SCOPE", "deliveryReceipt": v.ref(path), "evidence": v.ref(evidence_path), "package": evidence["package"], "versionCode": 156, "firstInstallTimePreserved": True, "nonDestructive": True, "flavors": scopes, "acceptance": body.get("acceptance"), "scope": "actual declared device(s)/checks only; not Android6/7, 16KiB, every OEM/root/FUSE/model matrix"}


def remaining_markdown(ledger):
    data = ["# build156 当前剩余范围草稿", "", "本稿由真实回执核验后生成，最终措辞由 root 确认。236 项全部有本轮 owner 处置；已授权的当前软件修复没有剩余项。此结论不等于完整设备、外部服务或来源证据全部完成。", "", "| 当前集合 | 数量 | ID |", "|---|---:|---|"]
    for key, label in (("remainingSoftwareIds", "仍需当前软件修复"), ("deviceEvidenceIds", "仍需具体设备/平台证据"), ("externalEvidenceIds", "仍需外部证据"), ("requiredContractIds", "保留的功能/保护契约"), ("duplicateIds", "重复源条目")):
        values = ledger[key]
        data.append(f"| {label} | {len(values)} | {', '.join(values) or '—'} |")
    checks = ledger["rootReceipts"]
    data += ["", "实际整合的软件回执：", ""]
    for flavor, counts in checks["software"]["junit"].items():
        data.append(f"- {flavor}：{counts['tests']} 项，{counts['passed']} 通过、{counts['skipped']} 跳过、{counts['failures']} 失败、{counts['errors']} 错误；跳过没有计为通过。")
    host = checks["host"]
    data += [f"- 宿主：{host['tests']} 个实际声明的 active 入口，实际日志和最终夹具字节检查通过；stage数量为 {host['declaredStageCounts']}。package host 条目 {host['declaredStageCounts'].get('package', 0)} 个，不从选择标签虚构执行。两版真正APK门禁由软件回执另证。", f"- 样式：{checks['style']['files']} 个当前项逐字节绑定格式化/真实 token 证明；旧 focused 回执不冒充格式化后执行。", f"- 网站：真实156产物本地构建和 {checks['website']['tests']} 项检查通过，未据此声称 dsha.cc 部署。", ""]
    for flavor, report in checks["lint"]["reports"].items():
        data.append(f"- {flavor} Release Lint：{report['warnings']} 条 Warning，所有 {report['issues']} 条实际 XML 记录逐项分类；没有声称警告清零。")
    data.append(f"- Lint分类仍标注 {len(checks['lint']['unknownReviewRequired'])} 条需要进一步位置/owner审查的不确定记录；不能把分类动作称为这些警告已消除。")
    if "device" in checks:
        data += ["", "正式包已按实际设备回执完成同签名、非调试、非破坏性覆盖验收，release 常规文件及 sidecar 与被验收 APK 逐字节一致。该单设备/声明检查范围没有扩写成 Android6/7、真实16KiB、OEM/Root/Shizuku/FUSE/掉电或外部模型矩阵。原 owner 未验历史保留，并由新回执补充实际范围。"]
    else:
        data += ["", "未提供有效的正式设备 delivery receipt；软件前提不证明已覆盖安装或替换 release。设备与正式交付步骤仍未完成。"]
    data += ["", "owner原记录的具体限制与缺口（保留原文；有新设备回执时，下列旧“未操作”句子仅描述owner当时范围，不能覆盖新的实际验收结论；完整矩阵仍不自动算通过）：", ""]
    for row in ledger["items"]:
        gaps = row.get("missingEvidence", [])
        if gaps:
            details = [value.get("detail", str(value)) if isinstance(value, dict) else str(value) for value in gaps]
            data.append(f"- {row['id']}：" + "; ".join(details))
    data += ["", "旧源码来源授权和旧63位摘要没有可信材料时保持未知；本轮未发布 GitHub 或部署服务器。历史读者、未知退出屏障、原件保护、完整性复核与开放正式插件/终端是现行契约，不以删除它们降低技术债分数。", "", "项目ZIP覆盖冻结时的明确源码成员；最终台账、本剩余稿和最终交付报告分离交付。冻结后不为追写这些报告重做ZIP，避免摘要循环。", ""]
    return "\n".join(data)


def finalize(args):
    verifier = Verify(ROOT)
    paths = {key: verifier.path(str(getattr(args, key))) for key in ("software", "host", "style", "lint", "website", "project")}
    software, snapshot, software_summary = validate_software(verifier, paths["software"])
    summaries = {"software": software_summary, "host": validate_host(verifier, paths["host"], snapshot), "style": validate_style(verifier, paths["style"], snapshot), "lint": validate_lint(verifier, paths["lint"], snapshot), "website": validate_website(verifier, paths["website"], software, snapshot), "project": validate_project(verifier, paths["project"], paths["software"], snapshot)}
    spec = importlib.util.spec_from_file_location("build156_owner_merge", HERE / "merge-ledger.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    ledger = module.merge(ROOT, True)
    require(ledger["sourceCoverage"] == ledger["reviewCoverage"] == 236 and not ledger["remainingSoftwareIds"], "all236 current reviews must have zero remainingSoftwareIds; never auto-close owner software findings")
    classification = read(HERE / "classification-draft.json")
    require(classification == ledger, "complete classification must be refreshed before source ZIP/finalization")
    if args.device:
        summaries["device"] = validate_device(verifier, verifier.path(str(args.device)), paths["software"], software, snapshot)
    receipt_refs = {key: verifier.ref(value) for key, value in paths.items()}
    if args.device:
        receipt_refs["device"] = verifier.ref(verifier.path(str(args.device)))
    for row in ledger["items"]:
        # Preserve the complete raw row, including any future owner extension.
        report = read(HERE / ("review-" + row["owner"] + ".json"))
        raw = next(value for value in report["items"] if value["id"] == row["id"])
        row["ownerReportRow"] = copy.deepcopy(raw)
        row["currentInputBindings"] = verifier.source_bindings(raw.get("files", []), snapshot)
        row["ownerChecksSourceEquivalence"] = "not inferred; historical focused checks retain their declared input/scope, latest input SHA comes only from the actual root software sourceSnapshot"
        row["resolvedIntegratedChecks"] = [{"kind": "root_final_software", "sourceSnapshot": software["sourceSnapshot"], "receipts": copy.deepcopy(receipt_refs), "scope": "final current source/JUnit/host/package/style/Lint/APK/ELF/signature/website/source archive as executed; device/external gaps remain separate"}]
        for deferred in raw.get("deferredIntegratedChecks", []):
            row["resolvedIntegratedChecks"].append({"originalDeferredCheck": copy.deepcopy(deferred), "resolution": "actual root integrated software receipts supplied", "receipts": copy.deepcopy(receipt_refs), "limits": "does not erase original history or establish untested device/external matrices"})
        if args.device:
            row["actualDeviceReceiptSupplement"] = {"receipt": copy.deepcopy(receipt_refs["device"]), "scope": "actual declared checks supplement prior no-device history; per-item broader missingEvidence is not automatically cleared"}
    # The original owner gaps remain intact. Their wording is historical when
    # a new device receipt exists; only that receipt states actual new scope.
    ledger.update(schemaVersion=2, phase="final_receipts_verified", status="PASS_CURRENT_SOFTWARE_WITH_RECORDED_GAPS", completedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(), rootReceiptReferences=receipt_refs, rootReceipts=summaries, sourceSnapshot=copy.deepcopy(software["sourceSnapshot"]), deviceHistoryInterpretation="owner no-device statements are retained as history; a supplied actual delivery receipt supplements only its declared devices/checks", finalizationOrder="project ZIP frozen before detached final ledger/remaining/delivery reports; no refreeze after finalization")
    # Detect receipt/log changes during validation without running any checks.
    for path, expected in verifier.observed.items():
        require(sha(path) == expected, "input changed during finalization: " + str(path))
    final_path = HERE / "execution-ledger-final.json"
    remaining_path = ROOT / "docs/releases/build156-remaining-work-20261003.md"
    require(not final_path.exists(), "final ledger already exists; do not silently replace a signed-byte/delivery chain")
    final_path.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    remaining_path.write_text(remaining_markdown(ledger), encoding="utf-8", newline="\n")
    print(json.dumps({"status": ledger["status"], "sourceCoverage": 236, "reviewCoverage": 236, "remainingSoftwareIds": [], "actualDeviceReceipt": bool(args.device), "ledger": final_path.relative_to(ROOT).as_posix(), "ledgerSha256": sha(final_path), "remainingDraft": remaining_path.relative_to(ROOT).as_posix(), "remainingDraftSha256": sha(remaining_path)}, ensure_ascii=False))


def self_test():
    class Tests(unittest.TestCase):
        def setUp(self):
            parent = ROOT / "app/build/audit-build156/finalizer-self-tests"
            parent.mkdir(parents=True, exist_ok=True)
            self.temporary = tempfile.TemporaryDirectory(prefix="owned-", dir=parent)
            self.root = Path(self.temporary.name).resolve()
            self.verify = Verify(self.root)
            (self.root / "sample.txt").write_text("exact evidence", encoding="utf-8")

        def tearDown(self):
            require(self.root.is_relative_to((ROOT / "app/build/audit-build156/finalizer-self-tests").resolve()), "self-test cleanup root escapes explicit owned target")
            self.temporary.cleanup()

        def test_file_actual_sha(self):
            self.assertEqual(self.verify.file("sample.txt", sha(self.root / "sample.txt")), self.root / "sample.txt")

        def test_changed_log_rejected(self):
            with self.assertRaises(ReceiptError):
                self.verify.log({"exitCode": 0, "log": "sample.txt", "logSha256": "0" * 64})

        def test_failed_command_rejected(self):
            with self.assertRaises(ReceiptError):
                self.verify.log({"exitCode": 1, "log": "sample.txt", "logSha256": sha(self.root / "sample.txt")})

        def test_outside_path_rejected(self):
            with self.assertRaises(ReceiptError):
                self.verify.path(str(HERE / "merge-ledger.py"))

        def test_source_snapshot_binds_actual_bytes(self):
            expected = sha(self.root / "sample.txt")
            (self.root / "snapshot.json").write_text(json.dumps({"sample.txt": expected}), encoding="utf-8")
            record = {"path": "snapshot.json", "sha256": sha(self.root / "snapshot.json")}
            self.assertEqual(self.verify.snapshot(record), {"sample.txt": expected})
            (self.root / "sample.txt").write_text("later mutation", encoding="utf-8")
            with self.assertRaises(ReceiptError):
                self.verify.snapshot(record)

        def test_noncurrent_focus_not_invented(self):
            expected = sha(self.root / "sample.txt")
            result = self.verify.source_bindings(["sample.txt", "retired.java"], {"sample.txt": expected})
            self.assertEqual(result[0]["sha256"], expected)
            self.assertNotIn("sha256", result[1])

        def test_running_software_never_passes(self):
            (self.root / "receipt.json").write_text(json.dumps({"status": "RUNNING", "verificationSchema": 2}), encoding="utf-8")
            with self.assertRaises(ReceiptError):
                validate_software(self.verify, self.root / "receipt.json")

        def test_style_count_is_current_inventory_not783(self):
            directory = self.root / "tools"
            directory.mkdir()
            inventory = directory / "java-format-files.json"
            inventory.write_text(json.dumps({"files": ["only.java"]}), encoding="utf-8")
            style = self.root / "style.json"
            style.write_text(json.dumps({"status": "PASS_CURRENT_STYLE", "files": 783, "verified": 783, "missing": [], "extra": []}), encoding="utf-8")
            with self.assertRaisesRegex(ReceiptError, "declared style composite"):
                validate_style(self.verify, style, {"tools/java-format-files.json": sha(inventory)})

        def test_late_owned_java_cannot_be_omitted_from_inventory(self):
            for source_set in ("main", "low", "standard", "debug", "androidTest", "test"):
                (self.root / "app/src" / source_set / "java/com/deepseekharness/app").mkdir(parents=True)
            folder = self.root / "app/src/main/java/com/deepseekharness/app"
            (folder / "One.java").write_text("class One {}", encoding="utf-8")
            (folder / "Late.java").write_text("class Late {}", encoding="utf-8")
            (self.root / "tools/java-format").mkdir(parents=True)
            helper = "tools/java-format/JavaTokenFingerprint.java"
            (self.root / helper).write_text("class Token {}", encoding="utf-8")
            names = ["app/src/main/java/com/deepseekharness/app/One.java", helper]
            inventory = self.root / "tools/java-format-files.json"
            inventory.write_text(json.dumps({"files": names}), encoding="utf-8")
            (self.root / "tools/java-format.lock.json").write_text(json.dumps({"protectedFiles": [], "generatedFiles": []}), encoding="utf-8")
            style = self.root / "style.json"
            style.write_text(json.dumps({"status": "PASS_CURRENT_STYLE", "files": 2, "verified": 2, "missing": [], "extra": [], "entries": [{"file": name} for name in names]}), encoding="utf-8")
            with self.assertRaisesRegex(ReceiptError, "omits/adds owned"):
                validate_style(self.verify, style, {"tools/java-format-files.json": sha(inventory)})

    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Tests))
    return 0 if result.wasSuccessful() else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("software", "host", "style", "lint", "website", "project"):
        parser.add_argument("--" + name, type=Path)
    parser.add_argument("--device", type=Path, help="actual formal delivery receipt linked to --software; no matrix expansion")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    for name in ("software", "host", "style", "lint", "website", "project"):
        if getattr(args, name) is None:
            parser.error("--" + name + " is required")
    finalize(args)
    return 0


if __name__ == "__main__":
    if sys.flags.optimize:
        raise SystemExit("FINALIZER_OPTIMIZATION_FORBIDDEN")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    try:
        raise SystemExit(main())
    except (ReceiptError, OSError, KeyError, TypeError, ValueError, ET.ParseError, zipfile.BadZipFile) as error:
        print("FINALIZATION_REFUSED: " + str(error), file=sys.stderr)
        raise SystemExit(1)
