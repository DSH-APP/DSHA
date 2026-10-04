"""Exercise changed website code with existing real155 APKs and exact155 sources.

This catches site regressions while final156 APKs are still being built. It is
explicitly not the final156 website receipt and does not fabricate a manifest.
"""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import os
import zipfile
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parents[3]
output = ROOT / "app/build/audit-build156/docs-website-preflight"
output.mkdir(parents=True, exist_ok=True)
archive = ROOT / "app/build/audit-build155/build155-complete-project-source.zip"
expected = "864df310ae80ea82b47efcfd46096e70c7e4bdcce8f6697a0b2bacb196277d32"
if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
    raise RuntimeError("authenticated local155 source archive changed")
manifest_bytes = (ROOT / "app/build/release-manifest.json").read_bytes()
manifest = json.loads(manifest_bytes)
if manifest["releases"][0]["versionCode"] != 155:
    raise RuntimeError("real155 manifest no longer current; perform the final156 check instead")
# The actual referenced files stay the same; only source metadata is read from
# the old, byte-authenticated source archive rather than the changing156 tree.
manifest_path = output / "actual-build155-manifest.json"
manifest_path.write_bytes(manifest_bytes)
source = output / "exact-build155-source"
selected = [
    "app/build.gradle", "app/src/main/java/com/deepseekharness/app/util/Constants.java",
    "tools/dsh-runtime/package.json", "app/src/main/assets/runtime-descriptor.json",
    "app/src/main/assets/builtin-plugins.json", "LICENSE",
    "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png", "app/src/main/res/mipmap-mdpi/ic_launcher.png",
    "agent-skills/device-shell/SKILL.md", "agent-skills/screen-ocr-operator/SKILL.md",
]
entries = []
with zipfile.ZipFile(archive) as bundle:
    registry = json.loads(bundle.read("app/src/main/assets/builtin-plugins.json"))
    for row in registry["plugins"]:
        if not row.get("internal"):
            folder = "app-integration" if row["name"] == "dsh-app-integration" else f"builtin-plugins/{row['name']}"
            selected.append(f"app/src/main/assets/{folder}/package.json")
    for relative in selected:
        data = bundle.read(relative)
        target = (source / relative).resolve()
        if not target.is_relative_to(source.resolve()):
            raise RuntimeError("source extraction escapes explicit fixture root")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        entries.append({"path": relative, "sha256": hashlib.sha256(data).hexdigest()})
for artifact in manifest["releases"][0]["artifacts"]:
    filename = artifact["filename"]
    if Path(filename).name != filename:
        raise RuntimeError("artifact filename escapes explicit fixture root")
    original = ROOT / "release" / filename
    target = source / "release" / filename
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        # The fixture only reads signed originals. No chmod/truncate/write is
        # performed on these links; build rehashes before copying into dist.
        os.link(original, target)
env = dict(os.environ, DSHA_SOURCE_ROOT=str(source), DSHA_RELEASE_MANIFEST=str(manifest_path))
node = shutil.which("node")
if not node:
    raise RuntimeError("Node executable unavailable")
checks = []
commands = [[node, "scripts/build.mjs"], [node, "--test", "scripts/site.test.mjs", "scripts/interactions.test.mjs"]]
for index, command in enumerate(commands):
    result = subprocess.run(command, cwd=ROOT / "website", env=env, capture_output=True, text=True, encoding="utf-8")
    log = output / f"check-{index + 1}.log"
    log.write_text(result.stdout + result.stderr, encoding="utf-8", newline="\n")
    checks.append({"command": command[1:], "cwd": "website", "exitCode": result.returncode, "log": log.relative_to(ROOT).as_posix(), "logSha256": hashlib.sha256(log.read_bytes()).hexdigest()})
    print(result.stdout[-1600:])
    if result.stderr:
        print(result.stderr[-1600:])
    if result.returncode:
        break
receipt = {"schemaVersion": 1, "scope": "changed website software preflight using real155 manifest/APKs and exact authenticated155 source files; NOT final156 or deployment", "artifactVersionCode": 155, "sourceArchiveSha256": expected, "selectedSourceFiles": entries, "checks": checks, "result": "PASS" if len(checks) == 2 and all(row["exitCode"] == 0 for row in checks) else "FAIL"}
(ROOT / "docs/audits/build156/website-preflight-build155.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
if receipt["result"] != "PASS":
    raise SystemExit(1)
