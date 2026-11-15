#!/usr/bin/env python3
"""ScreenSense 离线评估：确定性合成样本、编码对照与字符/可选 token 摘要。

只使用合成节点，不读真机屏幕、不含用户数据。结果只能说明「同一批合成节点在两种编码下的
相对大小」，不是真机页面性能或识别质量结论。

  python3 tools/screen-eval/screen_eval.py --check          # 固定样本与 golden 摘要未漂移
  python3 tools/screen-eval/screen_eval.py --write          # 重新生成固定样本（改生成器后才用）
  python3 tools/screen-eval/screen_eval.py --summary [--tokenizer PATH --tokenizer-sha256 HEX]

compact 编码逐字节镜像 app/.../util/ScreenSenseCodec.encode；两边共用 GOLDEN 摘要，
Java 单测 ScreenSenseBudgetTest 用真实 codec 计算同一摘要，任何一侧单独改动都会失败。
legacy 编码按 ScreenSnapshotReader 的字段与文本行重建，JSON 外壳是近似值（未含 ScreenReply 的
状态字段），因此 legacy 数字偏保守、只作对照。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = (
    ROOT
    / "app/src/test/resources/com/deepseekharness/app/util/screen-sense-synthetic-200.tsv"
)
SIZES = (30, 60, 120, 200)
SEED = 20261031
PACKAGE = "com.example.chat"
WIDTH, HEIGHT = 1080, 2400

# 字符回归上限：按本样本实测（30/60/120/200 = 516/963/1869/3090）留少量余量。
# 报告 §10.2 的代理口径是 60 元素 ≤900、120 元素 ≤1900；本实现每个元素都带预览 id，
# 60 元素未达 900，不能写成已满足原目标。token 需离线 tokenizer 复核。
CHAR_BUDGET = {60: 1000, 120: 1900, 200: 3200}

# 由 --write 生成后固定；Java 单测断言同一值。
GOLDEN = {
    "fixtureSha256": "b0b68d1ad8dc8d2a9ef852ce7456573b6c37bc2719dfa1248611daeb819f216f",
    "compact60Sha256": "c1ab04a8907d77ec863373f2ffc75e35d96620aba70aa8288f8f9f18103b80c0",
}

LABELS = (
    "返回", "聊天信息", "消息列表", "张三 19:02", "今晚一起吃饭吗？", "我 19:03", "好，七点见",
    "语音", "输入消息", "表情", "发送", "更多功能", "搜索", "通讯录", "发现", "我", "朋友圈",
    "扫一扫", "设置", "新消息通知", "隐私", "通用", "帮助与反馈", "关于", "退出登录",
    "李四 昨天", "收到，谢谢", "图片", "文件传输助手", "[语音] 12\"", "置顶聊天", "消息免打扰",
    "清空聊天记录", "投诉", "添加到通讯录", "视频通话", "位置", "红包", "转账", "收藏",
)


class Lcg:
    """固定 64 位 LCG，避免 random 模块跨版本实现差异。"""

    def __init__(self, seed: int) -> None:
        self.state = seed & 0xFFFFFFFFFFFFFFFF

    def next(self, bound: int) -> int:
        self.state = (self.state * 6364136223846793005 + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
        return (self.state >> 33) % bound


def base62(value: int) -> str:
    alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    out = ""
    while True:
        out = alphabet[value % 62] + out
        value //= 62
        if value == 0:
            break
    return out.rjust(3, "0")


def generate(count: int = max(SIZES)) -> list[dict]:
    """55% 可点击、4% 输入、5% 滚动，其余纯文本；与报告附录 A 的样本口径一致。"""
    rng = Lcg(SEED)
    rows = []
    y = 96
    for index in range(count):
        bucket = rng.next(100)
        if bucket < 4:
            role, actions = "I", "t" if rng.next(4) else "ct"
        elif bucket < 9:
            role, actions = "L", "fb" if rng.next(2) else "f"
        elif bucket < 64:
            role, actions = "B", "cl" if rng.next(10) == 0 else "c"
        else:
            role, actions = "T", ""
        flags = ""
        if role != "T" and rng.next(25) == 0:
            flags += "!"
        if role == "B" and rng.next(20) == 0:
            flags += "*"
        if role == "I" and rng.next(3) == 0:
            flags += "%"
        label = LABELS[rng.next(len(LABELS))]
        height = 96 + rng.next(4) * 24
        left = rng.next(3) * 120
        right = WIDTH - rng.next(3) * 120
        top = y % (HEIGHT - 240) + 80
        rows.append(
            {
                "id": "k" + base62(index),
                "role": role,
                "label": label,
                "flags": flags,
                "actions": actions,
                "bounds": (left, top, right, top + height),
            }
        )
        y += height + 8
    return rows


def fixture_text(rows: list[dict]) -> str:
    lines = ["# id\trole\tlabel\tflags\tactions\tleft\ttop\tright\tbottom  （合成样本，由 tools/screen-eval/screen_eval.py 生成）"]
    for row in rows:
        lines.append(
            "\t".join(
                [row["id"], row["role"], row["label"], row["flags"], row["actions"]]
                + [str(v) for v in row["bounds"]]
            )
        )
    return "\n".join(lines) + "\n"


def read_fixture() -> list[dict]:
    rows = []
    for line in FIXTURE.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 9:
            raise ValueError("SCREEN_EVAL_FIXTURE_INVALID")
        rows.append(
            {
                "id": parts[0],
                "role": parts[1],
                "label": parts[2],
                "flags": parts[3],
                "actions": parts[4],
                "bounds": tuple(int(v) for v in parts[5:9]),
            }
        )
    return rows


def _escape(value: str) -> str:
    out = []
    for ch in value:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "\b":
            out.append("\\b")
        elif ch == "\f":
            out.append("\\f")
        elif ord(ch) < 0x20:
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
    return "".join(out)


def encode_compact(rows: list[dict], bounds: bool = False, sid: int = 1) -> str:
    """镜像 ScreenSenseCodec.encode（quality=ok（样本非空且未截断）、focused=0、ime=?、src=tree、visited=元素数）。"""
    out = [f"S{sid} {PACKAGE} {WIDTH}x{HEIGHT} q=ok f=0 ime=? src=tree v={len(rows)}\n"]
    for row in rows:
        line = f'{row["id"]} {row["role"]} "{_escape(row["label"] or "?")}"'
        token = row["flags"] + row["actions"]
        if token:
            line += " " + token
        if bounds:
            line += " @%d,%d,%d,%d" % row["bounds"]
        out.append(line + "\n")
    return "".join(out)


def encode_legacy(rows: list[dict]) -> str:
    """按 ScreenSnapshotReader 字段与文本行重建现状 dump（JSON 外壳近似）。"""
    names = {"c": "click", "l": "long_click", "f": "scroll_forward", "b": "scroll_backward", "o": "focus", "t": "set_text"}
    nodes = []
    text = [f"窗口应用: {PACKAGE}\n"]
    for index, row in enumerate(rows):
        left, top, right, bottom = row["bounds"]
        clickable = "c" in row["actions"]
        editable = row["role"] == "I"
        scrollable = row["role"] == "L"
        enabled = "!" not in row["flags"]
        nodes.append(
            {
                "nodeId": "%08x-0000-4000-8000-%012x" % (index, index),
                "text": row["label"],
                "description": "",
                "clickable": clickable,
                "editable": editable,
                "scrollable": scrollable,
                "supportedActions": [names[a] for a in row["actions"]],
                "enabled": enabled,
                "checked": "*" in row["flags"],
                "boundsHaveArea": True,
                "bounds": [left, top, right, bottom],
            }
        )
        line = f'[{index + 1}] "{row["label"]}"'
        if clickable:
            line += " 可点击"
        if editable:
            line += " 可输入"
        if "f" in row["actions"]:
            line += " 可向前滚动(scroll_forward)"
        if "b" in row["actions"]:
            line += " 可向后滚动(scroll_backward)"
        if not enabled:
            line += " 不可用"
        line += f" 中心=({(left + right) // 2},{(top + bottom) // 2}) 区域={left},{top},{right},{bottom}\n"
        text.append(line)
    body = {
        "kind": "dsha-screen-tree-v1",
        "text": "".join(text),
        "treeId": "00000000-0000-4000-8000-000000000000",
        "nodes": nodes,
        "serviceEpoch": 1,
        "visited": len(rows),
        "coordinateAuthorization": "SCREENSHOT_OBSERVATION_REQUIRED",
        "target": {"displayId": 0, "windowId": 7, "packageName": PACKAGE},
    }
    return json.dumps(body, ensure_ascii=False, separators=(",", ":"))


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_tokenizer(path: str | None, expected: str | None):
    if not path:
        return None, None
    data = Path(path).read_bytes()
    actual = hashlib.sha256(data).hexdigest()
    if not expected or actual != expected.lower():
        raise SystemExit(f"TOKENIZER_SHA256_MISMATCH actual={actual}")
    try:
        from tokenizers import Tokenizer  # type: ignore
    except ImportError as error:
        raise SystemExit("TOKENIZER_LIBRARY_MISSING: pip install tokenizers（仅评估机）") from error
    return Tokenizer.from_str(data.decode("utf-8")), actual


def summary(rows: list[dict], tokenizer=None, tokenizer_sha=None) -> dict:
    result = {
        "kind": "dsha-screen-eval-v1",
        "evidence": "synthetic-nodes-offline",
        "fixtureSha256": sha256(fixture_text(rows)),
        "tokenizerSha256": tokenizer_sha,
        "sizes": [],
    }
    for size in SIZES:
        subset = rows[:size]
        entry = {"elements": size}
        for name, text in (
            ("legacy", encode_legacy(subset)),
            ("compact", encode_compact(subset)),
            ("compactBounds", encode_compact(subset, bounds=True)),
        ):
            entry[name] = {
                "chars": len(text),
                "utf8Bytes": len(text.encode("utf-8")),
                "tokens": len(tokenizer.encode(text).ids) if tokenizer else None,
            }
        entry["legacyOverCompactChars"] = round(entry["legacy"]["chars"] / entry["compact"]["chars"], 2)
        budget = CHAR_BUDGET.get(size)
        if budget:
            entry["compactCharBudget"] = budget
        result["sizes"].append(entry)
    return result


def check() -> list[str]:
    failures = []
    rows = generate()
    expected_fixture = fixture_text(rows)
    if not FIXTURE.exists() or FIXTURE.read_text(encoding="utf-8") != expected_fixture:
        failures.append("FIXTURE_DRIFT: 运行 --write 并核对差异")
    if read_fixture() != rows:
        failures.append("FIXTURE_PARSE_MISMATCH")
    if GOLDEN["fixtureSha256"] != sha256(expected_fixture):
        failures.append("GOLDEN_FIXTURE_SHA256_MISMATCH")
    if GOLDEN["compact60Sha256"] != sha256(encode_compact(rows[:60])):
        failures.append("GOLDEN_COMPACT60_SHA256_MISMATCH")
    for size, budget in CHAR_BUDGET.items():
        chars = len(encode_compact(rows[:size]))
        if chars > budget:
            failures.append(f"COMPACT_CHAR_BUDGET_EXCEEDED size={size} chars={chars} budget={budget}")
    return failures


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--summary", action="store_true")
    parser.add_argument("--tokenizer")
    parser.add_argument("--tokenizer-sha256")
    args = parser.parse_args(argv)
    if args.write:
        rows = generate()
        FIXTURE.parent.mkdir(parents=True, exist_ok=True)
        FIXTURE.write_text(fixture_text(rows), encoding="utf-8", newline="\n")
        print(json.dumps({
            "fixtureSha256": sha256(fixture_text(rows)),
            "compact60Sha256": sha256(encode_compact(rows[:60])),
        }, indent=2))
        return 0
    if args.check:
        failures = check()
        for failure in failures:
            print(failure, file=sys.stderr)
        print("PASS screen-eval fixture/golden/budget" if not failures else "FAIL")
        return 1 if failures else 0
    tokenizer, tokenizer_sha = load_tokenizer(args.tokenizer, args.tokenizer_sha256)
    print(json.dumps(summary(read_fixture(), tokenizer, tokenizer_sha), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
