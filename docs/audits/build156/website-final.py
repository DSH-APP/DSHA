"""Run the actual156 local site check after root has produced both real APKs."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")
ROOT = Path(__file__).resolve().parents[3]
WEB = ROOT / "website"
OUT = ROOT / "app/build/audit-build156/docs-website-final"
OUT.mkdir(parents=True, exist_ok=True)
manifest_path = ROOT / "app/build/release-manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
current = manifest["releases"][0]
if current["versionCode"] != 156:
    raise RuntimeError("final website check requires actual156 APK manifest, not an edited old fixture")
for artifact in current["artifacts"]:
    if artifact["versionCode"] != 156 or Path(artifact["filename"]).name != artifact["filename"]:
        raise RuntimeError("actual156 artifact identity is incomplete")

inputs = ["website/data/catalog.mjs", "website/scripts/build.mjs", "website/scripts/release-input.mjs", "website/scripts/install-page.mjs", "website/scripts/site.test.mjs", "website/scripts/interactions.test.mjs", "website/src/app.js", "website/src/install.js", "website/src/styles.css", "website/src/theme.js", "agent-skills/device-shell/SKILL.md", "agent-skills/screen-ocr-operator/SKILL.md", "app/build/release-manifest.json"]
fingerprints = [{"path": name, "sha256": hashlib.sha256((ROOT / name).read_bytes()).hexdigest()} for name in inputs]
env = dict(os.environ, DSHA_SOURCE_ROOT=str(ROOT), DSHA_RELEASE_MANIFEST=str(manifest_path))
node = shutil.which("node")
if not node:
    raise RuntimeError("Node unavailable")
checks = []
commands = [[node, "scripts/build.mjs"], [node, "--test", "scripts/site.test.mjs", "scripts/interactions.test.mjs"]]
tests = 0
for index, command in enumerate(commands):
    result = subprocess.run(command, cwd=WEB, env=env, capture_output=True, text=True, encoding="utf-8")
    log = OUT / f"check-{index + 1}.log"
    if log.exists():
        previous = OUT / f"check-{index + 1}-{hashlib.sha256(log.read_bytes()).hexdigest()[:12]}.log"
        if not previous.exists():
            previous.write_bytes(log.read_bytes())
    log.write_text(result.stdout + result.stderr, encoding="utf-8", newline="\n")
    checks.append({"command": command[1:], "cwd": "website", "exitCode": result.returncode, "log": log.relative_to(ROOT).as_posix(), "logSha256": hashlib.sha256(log.read_bytes()).hexdigest()})
    if index == 1:
        count = re.search(r"(?:#|ℹ) tests (\d+)", result.stdout)
        tests = int(count[1]) if count else 0
    print(result.stdout[-1400:])
    if result.stderr:
        print(result.stderr[-1400:])
    if result.returncode:
        break
for record in fingerprints:
    if hashlib.sha256((ROOT / record["path"]).read_bytes()).hexdigest() != record["sha256"]:
        raise RuntimeError("website inputs changed during the actual check: " + record["path"])
receipt = {"schemaVersion": 1, "scope": "actual156 local website build and behavior checks; no phone, deployment or hosted CI", "artifactVersionCode": 156, "version": current["version"], "manifestSha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(), "artifactHashes": [{"flavor": value["flavor"], "filename": value["filename"], "sha256": value["sha256"]} for value in current["artifacts"]], "inputFingerprints": fingerprints, "checks": checks, "testCount": tests, "result": "PASS" if len(checks) == 2 and tests >= 28 and all(value["exitCode"] == 0 for value in checks) else "FAIL"}
target = ROOT / "docs/audits/build156/website-build-check.json"
if target.exists():
    previous = target.with_name("website-build-check-" + hashlib.sha256(target.read_bytes()).hexdigest()[:12] + ".json")
    if not previous.exists():
        previous.write_bytes(target.read_bytes())
target.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
if receipt["result"] != "PASS":
    raise SystemExit(1)
print(f"PASS actual156 website build and {tests} tests")
