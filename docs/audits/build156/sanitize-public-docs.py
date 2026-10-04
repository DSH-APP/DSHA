"""Sanitize cited public historical copies without rewriting retained originals."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[3]
targets = [
    "docs/stability-acceptance.md",
    "docs/audits/2026-09-27-round2-architecture/final-assessment.md",
    "docs/evidence/rc1.4-functional/release-build.log",
]
records = []
for relative in targets:
    public = ROOT / relative
    retained = ROOT / "tools/history/documents/build155-public" / relative
    retained.parent.mkdir(parents=True, exist_ok=True)
    if not retained.exists():
        retained.write_bytes(public.read_bytes())
    original = retained.read_bytes()
    current = public.read_bytes()
    replacements = {
        b"F:\\DSHA\\dsha\\key\\DSHA-ACTUAL-PUBLISH-KEY-debug.keystore": b"<release-keystore>",
        b"F:/DSHA/_toolchains/gradle-user-home": b"<gradle-user-home>",
        b"C:/Users/18768/AppData/Local/Programs/Python/Python311/python.exe": b"<python>",
        b"C:/Program Files/nodejs/node.exe": b"<node>",
        b"F:/DSHA_RESTART/": b"<repo>/",
        b"F:\\DSHA_RESTART\\": b"<repo>/",
    }
    if relative.endswith("final-assessment.md"):
        replacements[b"(F:/DSHA_RESTART/"] = b"(../../../"
        # Longer Markdown targets must be replaced before generic text paths.
        current = current.replace(b"(F:/DSHA_RESTART/", b"(../../../")
    for old, new in replacements.items():
        current = current.replace(old, new)
    if relative.endswith("final-assessment.md") and b"build156" not in current[:600]:
        banner = "> 历史记录：以下评分、设备合同与通过结果仅对应 2026-09-28 的产物，不能推导 build156 完成或低于20。公开副本已脱敏路径；精确原字节与摘要见 [本轮保存清单](../build156/privacy-document-sources.json)。\n\n".encode("utf-8")
        split = current.find(b"\n") + 1
        current = current[:split] + b"\n" + banner + current[split:]
    if relative.endswith(".log") and not current.startswith(b"# Public redacted"):
        current = b"# Public redacted historical log; original retained privately, see docs/audits/build156/privacy-document-sources.json.\n" + current
    public.write_bytes(current)
    records.append({"path": relative, "retainedSource": retained.relative_to(ROOT).as_posix(), "sha256": hashlib.sha256(original).hexdigest(), "bytes": len(original), "publicSha256": hashlib.sha256(current).hexdigest(), "scope": "public path redaction only; original historical evidence unchanged"})
(ROOT / "docs/audits/build156/privacy-document-sources.json").write_text(json.dumps({"schemaVersion": 1, "items": records}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
print("sanitized three cited public copies; exact retained bytes preserved")
