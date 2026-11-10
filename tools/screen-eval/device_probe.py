#!/usr/bin/env python3
"""ScreenSense 只读 state 的真机核对探针（仅测试包 io.github.sunsetrne.dsha.test）。

在测试包自己的 DSH 对话里运行，taskSessionId/turnId 取该对话工具环境的
$DSH_PHONE_SESSION_ID / $DSH_PHONE_TURN_ID。每次运行读当前前台页面（或 --launch 指定的应用）：

  预热 tree（WebView 等懒建树页面第一次查询只返回外壳）→ tree → 负控（把 k000 当 nodeId 交给 node，
  期望 STALE_NODE）→ sense/state → sense/state?geom=1 → tree（核对读取期间页面是否变化）

输出只含计数、比例、耗时、长度和拒绝码，不写任何屏幕文字、包名以外的标识或截图。

脚本**不**调用 task-begin / task-end：手机任务的开始与结束归运行它的 AI 所有（android_control begin/finish）。
脚本若自己 task-end，会用 AI 本轮的身份把这一轮永久结束，AI 之后的 finish 或任何手机工具都会得到
SCREEN_TURN_ENDED。运行前请先 android_control begin，结束后由 AI 自己 finish。
不执行点击、输入、滑动；负控用 focus 且租约必然不匹配。
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

TEST_PACKAGE = "io.github.sunsetrne.dsha.test"
HEADERS_FILE = Path("/root/.dsh/.bridge_headers")


def headers() -> dict:
    result = {}
    for line in HEADERS_FILE.read_text(encoding="utf-8").splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            result[key.strip()] = value.strip()
    return result


def call(base: str, path: str, params: dict | None = None, timeout: float = 30.0) -> str:
    url = base.rstrip("/") + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers=headers())
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return unwrap(response.read().decode("utf-8", "replace"))


def unwrap(text: str) -> str:
    """3091/3092 桥把正文放在 {"result": "<原文>"} 信封里；只解这一层，其它形状原样返回。"""
    try:
        value = json.loads(text)
    except ValueError:
        return text
    if isinstance(value, dict) and set(value) == {"result"} and isinstance(value["result"], str):
        return value["result"]
    return text


def parse(text: str) -> dict:
    try:
        value = json.loads(text)
        return value if isinstance(value, dict) else {"error": "NON_OBJECT_REPLY"}
    except ValueError:
        # 不回显原文，只保留错误前缀（[ERR] CODE 形式）。
        head = text.strip().split("\n", 1)[0]
        return {"error": head[:80] if head.startswith("[ERR]") else "NON_JSON_REPLY"}


def compact_labels(encoded: str) -> list[str]:
    labels = []
    for line in encoded.splitlines()[1:]:
        start = line.find(' "')
        if start < 0:
            continue
        i, out = start + 2, []
        while i < len(line):
            ch = line[i]
            if ch == "\\" and i + 1 < len(line):
                nxt = line[i + 1]
                out.append({"n": "\n", "r": "\r", "t": "\t"}.get(nxt, nxt))
                i += 2
                continue
            if ch == '"':
                break
            out.append(ch)
            i += 1
        labels.append("".join(out))
    return labels


def tree_summary(tree: dict) -> dict:
    return {
        "ok": tree.get("ok"),
        "error": tree.get("error"),
        "nodes": len(tree.get("nodes") or []),
        "visited": tree.get("visited"),
        "truncated": bool(tree.get("truncated")),
        "replyChars": len(json.dumps(tree, ensure_ascii=False)),
    }


def labels_of(nodes: list) -> collections.Counter:
    return collections.Counter(norm(str(n.get("text", "")) or "?") for n in nodes)


def norm(label: str) -> str:
    # 两条路径对换行与截断处理不同：只比较前 40 个字符、换行折成空格。
    return label.replace("\n", " ")[:40]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=os.environ.get("DSHA_BRIDGE_BASE", "http://127.0.0.1:3091"))
    parser.add_argument("--label", required=True, help="页面代号，如 settings / chat-list（不要写真实内容）")
    parser.add_argument("--out", default="/sdcard/Download/dsha-sense-probe.jsonl")
    parser.add_argument("--launch", help="先启动该包名的应用再读，例如 com.android.settings")
    parser.add_argument("--settle", type=float, default=1.5, help="启动或预热后等待秒数")
    args = parser.parse_args(argv)
    session = os.environ.get("DSH_PHONE_SESSION_ID", "")
    turn = os.environ.get("DSH_PHONE_TURN_ID", "")
    if not session or not turn:
        print("PHONE_TASK_IDENTITY_MISSING", file=sys.stderr)
        return 2
    identity = {"taskSessionId": session, "turnId": turn}
    completed = os.environ.get("DSH_PHONE_COMPLETED_TURN_ID", "")
    if completed:
        identity["completedTurnId"] = completed

    version = dict(
        line.split("=", 1) for line in call(args.base, "/app/version").splitlines() if "=" in line
    )
    if version.get("APPLICATION_ID") != TEST_PACKAGE:
        print("NOT_TEST_PACKAGE: 只在测试包上运行", file=sys.stderr)
        return 3
    record = {
        "kind": "dsha-sense-device-probe-v1",
        "page": args.label,
        "appCode": version.get("APP_CODE"),
        "screenSenseFlag": version.get("SCREEN_SENSE"),
        "time": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    record["taskLifecycle"] = "owned-by-caller"
    try:

        if args.launch:
            launched = call(args.base, "/app/launch", dict(identity, pkg=args.launch)).strip()
            # 回执是桥的状态文本（不含屏幕内容），保留完整首行便于定位失败原因。
            record["launch"] = {"package": args.launch, "reply": launched.splitlines()[0][:200] if launched else ""}
        warm = parse(call(args.base, "/app/ui/tree", identity))
        record["warmupNodes"] = len(warm.get("nodes") or [])
        time.sleep(max(args.settle, 0))

        t0 = time.monotonic()
        tree = parse(call(args.base, "/app/ui/tree", identity))
        record["treeWallMs"] = round((time.monotonic() - t0) * 1000)
        nodes = tree.get("nodes") or []
        record["tree"] = tree_summary(tree)

        if tree.get("treeId"):
            negative = parse(
                call(
                    args.base,
                    "/app/ui/node",
                    dict(identity, treeId=tree["treeId"], nodeId="k000", action="focus"),
                )
            )
            record["negativeControl"] = {
                "expected": "STALE_NODE",
                "ok": negative.get("ok"),
                "error": negative.get("error"),
                "pass": negative.get("ok") is False and negative.get("error") == "STALE_NODE",
            }

        compact_text = ""
        # 探针固定要全量编码：连续读数默认回增量，第二次的 encodedState 可能只有变化行。
        for name, extra in (("sense", {"full": "1"}), ("senseGeom", {"geom": "1", "full": "1"})):
            t0 = time.monotonic()
            sense = parse(call(args.base, "/app/sense/state", dict(identity, **extra)))
            wall = round((time.monotonic() - t0) * 1000)
            encoded = sense.get("encodedState") or ""
            record[name] = {
                "ok": sense.get("ok"),
                "error": sense.get("error"),
                "wallMs": wall,
                "format": sense.get("format"),
                "mode": sense.get("mode"),
                "actionCredential": sense.get("actionCredential"),
                "readOnlyPreview": sense.get("readOnlyPreview"),
                "elements": sense.get("elements"),
                "visited": sense.get("visited"),
                "quality": sense.get("quality"),
                "partial": sense.get("partial"),
                "source": sense.get("source"),
                "readMs": sense.get("readMs"),
                "ageMs": sense.get("ageMs"),
                "encodedChars": len(encoded),
                "replyChars": len(json.dumps(sense, ensure_ascii=False)),
            }
            if name == "sense":
                compact_text = encoded
        after = parse(call(args.base, "/app/ui/tree", identity))
        after_nodes = after.get("nodes") or []
        record["treeAfter"] = tree_summary(after)
        before_labels = labels_of(nodes)
        after_labels = labels_of(after_nodes)
        # 前后两次 tree 标签不同，说明读取期间页面在变，下面的对照不可信。
        record["pageStable"] = before_labels == after_labels
        if compact_text:
            compact = collections.Counter(norm(label) for label in compact_labels(compact_text))
            legacy = after_labels
            matched = sum((legacy & compact).values())
            record["labelMatch"] = {
                "against": "treeAfter",
                "legacyOnly": sum((legacy - compact).values()),
                "compactOnly": sum((compact - legacy).values()),
                "matched": matched,
                # 以较大一方为分母；sense 达到 max 上限时 legacyOnly 偏大属预期。
                "ratio": round(matched / max(sum(legacy.values()), sum(compact.values()), 1), 3),
            }
            if record["pageStable"] and not record["treeAfter"]["truncated"]:
                record["legacyOverSenseReplyChars"] = round(
                    record["treeAfter"]["replyChars"] / max(record["sense"]["replyChars"], 1), 2
                )
        return 0
    finally:
        with open(args.out, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
        print(json.dumps(record, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
