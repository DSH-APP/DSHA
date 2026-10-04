"""无密钥地检查正式发布身份；不能替代软件/签名/真机回执。"""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
identity = json.loads((ROOT / "ci/release-identity.json").read_text(encoding="utf-8"))
if identity.get("schema") != 1 or identity.get("packageName") != "com.dsh.client" \
        or not re.fullmatch(r"[a-f0-9]{64}", identity.get("certificateSha256", "")) \
        or identity.get("schemes") != ["v1", "v2", "v3"]:
    raise SystemExit("RELEASE_IDENTITY_INVALID")
print("PASS release identity; signing keys, complete software receipt and physical acceptance remain separate requirements")
