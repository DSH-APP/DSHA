"""Build/check local actual signed156 candidates without delivering real release.

The existing website source and original manifest generator are used unchanged.
Only audit staging, website/dist and local generated release metadata are written.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from urllib.parse import unquote, urlparse

sys.stdout.reconfigure(encoding="utf-8")
ROOT = Path(__file__).resolve().parents[3]
AUDIT = ROOT / "docs/audits/build156"
OUT = ROOT / "app/build/audit-build156/docs-website-candidate-5e2d"
VIEW = OUT / "exact-candidate-source"


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def record(path):
    return {"path": path.relative_to(ROOT).as_posix(), "sha256": digest(path), "bytes": path.stat().st_size}


def exact_copy(source, destination, expected):
    if digest(source) != expected:
        raise RuntimeError("source changed before candidate staging: " + str(source))
    if not destination.resolve().is_relative_to(OUT.resolve()):
        raise RuntimeError("candidate staging escapes explicit owned output")
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if destination.is_symlink() or digest(destination) != expected:
            raise RuntimeError("existing staged bytes differ; preserve rather than overwrite")
    else:
        shutil.copyfile(source, destination)
    if digest(destination) != expected or digest(source) != expected:
        raise RuntimeError("candidate copy does not match actual source")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--software", type=Path, default=ROOT / "app/build/stability-acceptance/5e2d5521-beb5-4775-b87e-85a074b4aade/manifest.json")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    if OUT.is_symlink() or VIEW.is_symlink():
        raise RuntimeError("audit output/source view cannot be linked")
    software_path = args.software.resolve(strict=True)
    software = json.loads(software_path.read_text(encoding="utf-8"))
    if software["status"] != "PASS_WITH_EXPLICIT_DEVICE_GAPS" or {row["flavor"] for row in software["apks"]} != {"standard", "low"}:
        raise RuntimeError("completed actual signed candidate software receipt required")
    snapshot_path = Path(software["sourceSnapshot"]["path"])
    if digest(snapshot_path) != software["sourceSnapshot"]["sha256"]:
        raise RuntimeError("actual software sourceSnapshot changed")
    snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    selected = ["app/build.gradle", "app/src/main/java/com/deepseekharness/app/util/Constants.java", "tools/dsh-runtime/package.json", "app/src/main/assets/runtime-descriptor.json", "app/src/main/assets/builtin-plugins.json", "LICENSE", "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png", "app/src/main/res/mipmap-mdpi/ic_launcher.png", "agent-skills/device-shell/SKILL.md", "agent-skills/screen-ocr-operator/SKILL.md"]
    registry = json.loads((ROOT / "app/src/main/assets/builtin-plugins.json").read_text(encoding="utf-8"))
    for row in registry["plugins"]:
        if not row.get("internal"):
            folder = "app-integration" if row["name"] == "dsh-app-integration" else "builtin-plugins/" + row["name"]
            selected.append("app/src/main/assets/" + folder + "/package.json")
    source_bindings = []
    for name in selected:
        if name not in snapshot and name != "LICENSE":
            raise RuntimeError("website source is absent from actual software snapshot: " + name)
        expected = snapshot.get(name, digest(ROOT / name))
        exact_copy(ROOT / name, VIEW / name, expected)
        source_bindings.append({"source": record(ROOT / name), "staged": record(VIEW / name), "binding": "actual5e2d software sourceSnapshot" if name in snapshot else "explicit current repository MIT license, outside APK software sourceSnapshot"})
    print("Staged exact current metadata/Constants/builtin manifests/icons/skills: " + str(len(source_bindings)), flush=True)
    # Fingerprint real release bytes and sidecars before any staging. They are
    # never passed as output destinations or candidate manifest artifacts.
    real_release = []
    staged_apks = []
    for row in software["apks"]:
        if row["versionCode"] != 156:
            raise RuntimeError("candidate APK is not156")
        filename = "dsha-0.2.0-rc2" + ("low" if row["flavor"] == "low" else "") + ".apk"
        original = ROOT / "release" / filename
        real_release.append(record(original))
        real_release.append(record(original.with_suffix(".apk.sha256")))
        candidate = Path(row["path"])
        exact_copy(candidate, VIEW / "release" / filename, row["sha256"])
        staged_apks.append({"flavor": row["flavor"], "filename": filename, "originalCandidate": record(candidate), "staged": record(VIEW / "release" / filename), "sha256": row["sha256"]})
    local_manifest = ROOT / "app/build/release-manifest.json"
    previous_bytes = local_manifest.read_bytes()
    previous_hash = hashlib.sha256(previous_bytes).hexdigest()
    previous_path = OUT / ("previous-generated-manifest-" + previous_hash + ".json")
    if previous_path.exists() and previous_path.read_bytes() != previous_bytes:
        raise RuntimeError("saved original generated metadata differs")
    if not previous_path.exists():
        previous_path.write_bytes(previous_bytes)
    notes = ROOT / "docs/releases/build156-changes.zh.md"
    staged_manifest = OUT / "actual-candidate-release-manifest.json"
    generator = ROOT / "tools/generate-release-manifest.py"
    generator_sha = snapshot.get("tools/generate-release-manifest.py")
    if not generator_sha or digest(generator) != generator_sha:
        raise RuntimeError("original manifest generator differs from software snapshot")
    standard = next(row for row in staged_apks if row["flavor"] == "standard")
    low = next(row for row in staged_apks if row["flavor"] == "low")
    command = [sys.executable, str(generator), "--standard", str(ROOT / standard["staged"]["path"]), "--low", str(ROOT / low["staged"]["path"]), "--build-tools", "F:/DSHA/_toolchains/android-sdk/build-tools/36.0.0", "--java", "F:/DSHA/_toolchains/jdk-17/bin/java.exe", "--notes", str(notes), "--output", str(staged_manifest), "--previous-manifest", str(previous_path), "--channel", "stable"]
    generation_env = dict(os.environ, PYTHONUTF8="1", PYTHONIOENCODING="utf-8")
    generation = subprocess.run(command, cwd=ROOT, env=generation_env, capture_output=True, text=True, encoding="utf-8")
    generation_log = OUT / "manifest-generation.log"
    generation_log.write_text(generation.stdout + generation.stderr, encoding="utf-8", newline="\n")
    print(generation.stdout[-1400:], flush=True)
    if generation.returncode:
        print(generation.stderr[-1400:], flush=True)
        raise RuntimeError("original candidate manifest generation failed; see audit log")
    manifest = json.loads(staged_manifest.read_text(encoding="utf-8"))
    expected_hashes = {row["flavor"]: row["sha256"] for row in software["apks"]}
    if manifest["releases"][0]["versionCode"] != 156 or {row["flavor"]: row["sha256"] for row in manifest["releases"][0]["artifacts"]} != expected_hashes:
        raise RuntimeError("generated metadata does not bind actual candidates")
    # Local generated metadata can advance; real release and all software inputs
    # remain untouched. No edits are made inside the generator's actual JSON.
    local_manifest.write_bytes(staged_manifest.read_bytes())
    inputs = {name for name in snapshot if name.startswith("website/") and "history" not in Path(name).parts}
    inputs.update(selected)
    inputs.update(["docs/releases/build156-changes.zh.md", "app/build/release-manifest.json"])
    input_fingerprints = []
    for name in sorted(inputs):
        item = record(ROOT / name)
        if name in snapshot and item["sha256"] != snapshot[name]:
            raise RuntimeError("website current source differs from software snapshot: " + name)
        input_fingerprints.append({"path": item["path"], "sha256": item["sha256"]})
    input_fingerprints += [{"path": row["staged"]["path"], "sha256": row["staged"]["sha256"]} for row in source_bindings]
    input_fingerprints += [{"path": record(staged_manifest)["path"], "sha256": digest(staged_manifest)}]
    env = dict(os.environ, DSHA_SOURCE_ROOT=str(VIEW), DSHA_RELEASE_MANIFEST=str(staged_manifest))
    node = shutil.which("node")
    if not node:
        raise RuntimeError("Node is unavailable")
    checks = []
    tests = 0
    for index, command in enumerate([[node, "scripts/build.mjs"], [node, "--test", "scripts/site.test.mjs", "scripts/interactions.test.mjs"]]):
        result = subprocess.run(command, cwd=ROOT / "website", env=env, capture_output=True, text=True, encoding="utf-8")
        log = OUT / ("check-" + str(index + 1) + ".log")
        log.write_text(result.stdout + result.stderr, encoding="utf-8", newline="\n")
        checks.append({"command": command[1:], "cwd": "website", "exitCode": result.returncode, "log": log.relative_to(ROOT).as_posix(), "logSha256": digest(log)})
        if index == 1:
            count = re.search(r"(?:#|ℹ) tests (\d+)", result.stdout)
            tests = int(count[1]) if count else 0
        print(result.stdout[-1800:], flush=True)
        if result.stderr:
            print(result.stderr[-1400:], flush=True)
        if result.returncode:
            break
    for item in input_fingerprints:
        if digest(ROOT / item["path"]) != item["sha256"]:
            raise RuntimeError("input changed during candidate website check")
    download_proofs = []
    for row in manifest["releases"][0]["artifacts"]:
        url = urlparse(row["url"])
        destination = ROOT / "website/dist" / unquote(url.path).lstrip("/")
        if digest(destination) != row["sha256"] or destination.stat().st_size != row["bytes"]:
            raise RuntimeError("actual website APK bytes do not match candidate")
        if destination.with_suffix(".apk.sha256").read_text(encoding="ascii").split() != [row["sha256"], row["filename"]]:
            raise RuntimeError("actual website sidecar differs")
        download_proofs.append({"flavor": row["flavor"], "file": record(destination), "sidecar": record(destination.with_suffix(".apk.sha256"))})
    for item in real_release:
        if digest(ROOT / item["path"]) != item["sha256"]:
            raise RuntimeError("real release changed during candidate check; do not claim untouched")
    receipt = {"schemaVersion": 1, "scope": "actual signed156 candidates, local-only website with staged exact current source; NOT device acceptance, actual release delivery, GitHub publication or server deployment", "artifactVersionCode": 156, "version": manifest["releases"][0]["version"], "softwareReceipt": record(software_path), "sourceSnapshot": record(snapshot_path), "manifestSha256": digest(local_manifest), "stagedManifest": record(staged_manifest), "manifestGeneration": {"originalGenerator": record(generator), "exitCode": generation.returncode, "log": record(generation_log), "previousGeneratedMetadataPreserved": record(previous_path), "notesScope": "current declared release prose outside APK sourceSnapshot"}, "artifactHashes": [{"flavor": row["flavor"], "filename": row["filename"], "sha256": row["sha256"]} for row in staged_apks], "candidateStagedArtifacts": staged_apks, "sourceBindings": source_bindings, "inputFingerprints": input_fingerprints, "checks": checks, "testCount": tests, "downloadProofs": download_proofs, "realReleaseUntouched": real_release, "candidateOnly": True, "formalDeliveryCompleted": False, "deviceAcceptanceCompleted": False, "hostedDeploymentPerformed": False, "result": "PASS" if len(checks) == 2 and tests >= 28 and all(row["exitCode"] == 0 for row in checks) else "FAIL"}
    target = AUDIT / "website-build-check.json"
    if target.exists():
        saved = OUT / ("previous-website-build-check-" + digest(target) + ".json")
        if not saved.exists():
            saved.write_bytes(target.read_bytes())
    target.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"result": receipt["result"], "tests": tests, "receipt": str(target.relative_to(ROOT)), "sha256": digest(target), "candidateOnly": True, "realReleaseUntouched": True}, ensure_ascii=False), flush=True)
    return 0 if receipt["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
