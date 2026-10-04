#!/usr/bin/env python3
"""Bind the complete current Java inventory to pinned formatter/token receipts."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import uuid


ROOT = Path(__file__).resolve().parents[3]
AUDIT = Path(__file__).resolve().parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--refresh", action="store_true", help="register the exact owned inventory and format only files without a current proof")
    parser.add_argument("--java", default="F:/DSHA/_toolchains/jdk-17/bin/java.exe")
    args = parser.parse_args()
    lock = read(ROOT / "tools/java-format.lock.json")
    spec = importlib.util.spec_from_file_location("current_tree", ROOT / "tools/format-java-tree.py")
    tree = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tree)
    expected = tree.inventory(ROOT, lock["protectedFiles"], lock["generatedFiles"])
    manifest_path = ROOT / "tools/java-format-files.json"
    manifest = read(manifest_path)
    if manifest["files"] != expected:
        if not args.refresh:
            raise ValueError("CURRENT_INVENTORY_REGISTRATION_REQUIRED")
        manifest["files"] = expected
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")

    def proofs():
        candidates = list((ROOT / "app/build/audit-build156/style-first").glob("tree-preflight-*.json"))
        candidates += list((ROOT / "app/build/java-format").glob("*.json"))
        selected = {}
        for path in sorted(candidates, key=lambda value: value.stat().st_mtime_ns):
            receipt = read(path)
            if not isinstance(receipt.get("files"), list) or not all(isinstance(value, dict) for value in receipt["files"]):
                continue
            if receipt.get("tool") != lock["tool"] or receipt.get("version") != lock["version"] or receipt.get("artifactSha256") != lock["sha256"]:
                continue
            if receipt.get("mode") not in {"check", "write"}:
                continue
            for entry in receipt["files"]:
                name = entry.get("file")
                if name not in expected or not (ROOT / name).is_file():
                    continue
                if receipt["mode"] == "check" and entry.get("needsFormatting") is not False:
                    continue
                if sha(ROOT / name) != entry.get("formattedSha256"):
                    continue
                token = entry.get("javaTokenSha256", "")
                if len(token) != 64 or any(char not in "0123456789abcdef" for char in token):
                    continue
                selected[name] = {"file": name, "sha256": entry["formattedSha256"], "javaTokenSha256": token,
                                  "receipt": path.relative_to(ROOT).as_posix(), "receiptSha256": sha(path)}
        return selected

    selected = proofs()
    missing = sorted(set(expected) - set(selected))
    if missing and not args.refresh:
        raise ValueError("CURRENT_FORMATTER_PROOF_REQUIRED:" + ",".join(missing))
    fresh_count = len(missing)
    for start in range(0, len(missing), 64):
        names = missing[start:start + 64]
        receipt = ROOT / "app/build/java-format" / ("root-final-owned156-" + uuid.uuid4().hex + ".json")
        command = [sys.executable, "-B", str(ROOT / "tools/format-java.py"), "--write", "--files", *names,
                   "--offline", "--java", args.java, "--report", str(receipt)]
        subprocess.run(command, cwd=ROOT, check=True)
    selected = proofs()
    missing = sorted(set(expected) - set(selected))
    extra = sorted(set(selected) - set(expected))
    if missing or extra or tree.inventory(ROOT, lock["protectedFiles"], lock["generatedFiles"]) != expected:
        raise ValueError("JAVA_INVENTORY_OR_PROOF_CHANGED")
    for name, entry in selected.items():
        if sha(ROOT / name) != entry["sha256"] or sha(ROOT / entry["receipt"]) != entry["receiptSha256"]:
            raise ValueError("JAVA_BYTES_CHANGED_DURING_BINDING:" + name)
    body = {"schema": 1, "status": "PASS_CURRENT_STYLE", "scope": "Complete current owned inventory; unchanged bytes reuse exact pinned check receipts, changed/new bytes have pinned formatter and javac-token proofs",
            "files": len(expected), "verified": len(selected), "missing": missing, "extra": extra,
            "inventory": {"path": manifest_path.relative_to(ROOT).as_posix(), "sha256": sha(manifest_path)},
            "receipts": sorted({entry["receipt"] for entry in selected.values()}),
            "entries": [selected[name] for name in expected]}
    target = AUDIT / "java-format-current-style.json"
    if target.exists():
        original = target.read_bytes()
        saved = target.with_name("java-format-current-style-" + hashlib.sha256(original).hexdigest()[:12] + ".json")
        if not saved.exists():
            saved.write_bytes(original)
    target.write_text(json.dumps(body, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"status": body["status"], "files": body["files"], "freshFormatterFiles": fresh_count}, ensure_ascii=False))


if __name__ == "__main__":
    main()
