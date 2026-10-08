#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把手机端 DSH 数据打包成「电脑端可继续使用」的便携包。

设计要点（与 tools/pc-migration/portable-plan.json 一一对应）：

* **白名单**：只打包 include 列出的路径，别的一律不进包。手机上的 ``$DSH_HOME``
  混着 DSHA 的随包脚本（``*.py`` / ``*.cjs``）、受管运行时契约、桥凭据和
  设备侧 profile 链接 —— 这些搬到电脑上不是「没用的数据」，而是会让 dsh 起不来
  或把设备凭据送出去的垃圾，所以必须靠白名单而不是黑名单。
* **凭据默认排除**：``.credentials.yaml`` 只在 ``--with-credentials`` 时进包，
  且必须经 ``credential-yaml-filter.cjs`` 字段级剔除 ``client-connection/*``
  （浏览器会话签名密钥），只留下 refs 里的用户 API Key 引用。过滤失败就整个
  不带凭据 —— 不回退成「原样打包」。
* **落到用户看得见的地方**：默认写 ``/sdcard/Download/DSHA/``。容器里 ``/sdcard``
  就是外部存储，用户可以直接在文件管理器里拿走；这条路径不受 ``/app/export``
  的 64 MiB 单文件上限约束。

用法：
    python3 dsha-portable.py plan                 # 只看会打包什么
    python3 dsha-portable.py export               # 打包（不含凭据）
    python3 dsha-portable.py export --with-credentials
    python3 dsha-portable.py export --with-workspace /root/Documents/foo
"""

from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PLAN_PATH = HERE / "portable-plan.json"
DEFAULT_OUT_DIRS = (Path("/sdcard/Download/DSHA"), Path("/storage/emulated/0/Download/DSHA"))
DSH_PACKAGE_JSONS = (
    Path("/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json"),
    Path("/usr/lib/node_modules/@deepseek-ai/dsh/package.json"),
)
YAML_MODULE_CANDIDATES = (
    Path("/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/yaml"),
    Path("/usr/lib/node_modules/@deepseek-ai/dsh/node_modules/yaml"),
)
WORKSPACE_SKIP_DIRS = {".git", "node_modules", "__pycache__", ".venv", "venv", ".gradle", "build"}
# 工作区里明显是缓存/可再生的顶层目录，不值得塞进便携包
WORKSPACE_SKIP_TOP = {".cache", ".npm", ".pnpm-store", "target"}


def die(message: str, code: int = 2) -> "None":
    print("ERROR: " + message, file=sys.stderr)
    raise SystemExit(code)


def load_plan() -> dict:
    try:
        return json.loads(PLAN_PATH.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001 - 策略文件读不出来就不要继续
        die("无法读取策略文件 {}: {}".format(PLAN_PATH, exc))


def human_bytes(n: int) -> str:
    step = 0.0
    value = float(n)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if value < 1024 or unit == "TiB":
            return "{:.1f} {}".format(value, unit) if unit != "B" else "{} B".format(int(value))
        value /= 1024
    return "{} B".format(n)


def dsh_version() -> str:
    for path in DSH_PACKAGE_JSONS:
        try:
            return str(json.loads(path.read_text(encoding="utf-8")).get("version") or "unknown")
        except Exception:  # noqa: BLE001
            continue
    try:
        out = subprocess.run(
            ["dsh", "--version"], capture_output=True, text=True, timeout=20, check=False
        )
        return (out.stdout or out.stderr).strip().splitlines()[0] if (out.stdout or out.stderr) else "unknown"
    except Exception:  # noqa: BLE001
        return "unknown"


def default_out_dir() -> Path:
    for candidate in DEFAULT_OUT_DIRS:
        try:
            candidate.mkdir(parents=True, exist_ok=True)
            probe = candidate / ".dsha-portable-write-probe"
            probe.write_text("", encoding="utf-8")
            probe.unlink()
            return candidate
        except Exception:  # noqa: BLE001
            continue
    die("找不到可写的外部存储目录（试过 {}）".format(", ".join(str(p) for p in DEFAULT_OUT_DIRS)))


def copy_tree(src: Path, dst: Path, skip_names: set, skip_top: set, top: bool = True) -> tuple:
    """复制目录，返回 (文件数, 字节数, 被跳过的路径列表)。"""
    files = 0
    size = 0
    skipped = []
    for root, dirs, names in os.walk(src):
        rel_root = Path(root).relative_to(src)
        dirs[:] = sorted(
            d
            for d in dirs
            if d not in skip_names and not (top and rel_root == Path(".") and d in skip_top)
        )
        for name in sorted(names):
            if name in skip_names:
                skipped.append(str(rel_root / name) + " (按策略跳过)")
                continue
            source = Path(root) / name
            if source.is_symlink() and not source.exists():
                skipped.append(str(rel_root / name) + " (悬空链接)")
                continue
            target = dst / rel_root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_symlink():
                # 便携包里不放链接：电脑上目标可能不存在，解包后只会变成坏入口
                skipped.append(str(rel_root / name) + " (符号链接)")
                continue
            shutil.copy2(source, target)
            files += 1
            try:
                size += target.stat().st_size
            except OSError:
                pass
    return files, size, skipped


def count_tree(src: Path, skip_names: set, skip_top: set, top: bool = True) -> tuple:
    """只统计，不落盘（plan 用）。"""
    files = 0
    size = 0
    for root, dirs, names in os.walk(src):
        rel_root = Path(root).relative_to(src)
        dirs[:] = sorted(
            d
            for d in dirs
            if d not in skip_names and not (top and rel_root == Path(".") and d in skip_top)
        )
        for name in sorted(names):
            if name in skip_names:
                continue
            path = Path(root) / name
            if path.is_symlink():
                continue
            files += 1
            try:
                size += path.stat().st_size
            except OSError:
                pass
    return files, size, []


def filter_credentials(source: Path, home: Path, plan: dict) -> tuple:
    """经 credential-yaml-filter.cjs 剔除 machines 记录；失败即放弃凭据。"""
    spec = plan["credentials"]
    filter_script = home / spec["filter"]
    if not filter_script.is_file():
        return None, "未找到 {}".format(filter_script)
    yaml_module = next((str(p) for p in YAML_MODULE_CANDIDATES if p.is_dir()), None)
    if yaml_module is None:
        return None, "未找到 yaml 模块（dsh 安装目录不完整？）"
    try:
        text = source.read_text(encoding="utf-8")
    except Exception as exc:  # noqa: BLE001
        return None, "读取失败: {}".format(exc)
    payload = json.dumps({"text": text, "prefixes": spec["pruneRecordPrefixes"]})
    try:
        proc = subprocess.run(
            ["node", str(filter_script), yaml_module],
            input=payload,
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
    except Exception as exc:  # noqa: BLE001
        return None, "filter 未执行: {}".format(exc)
    if proc.returncode != 0:
        return None, "filter 拒绝（{}）".format((proc.stderr or "").strip() or proc.returncode)
    try:
        result = json.loads(proc.stdout)
        return result["text"], result.get("removed", [])
    except Exception as exc:  # noqa: BLE001
        return None, "filter 输出无法解析: {}".format(exc)


def session_summary(home: Path) -> list:
    """列出 sessions 根下的 projectKey 目录与会话数（cwd 原文由电脑端恢复工具解出）。"""
    root = home / "sessions"
    out = []
    if not root.is_dir():
        return out
    for project in sorted(p for p in root.iterdir() if p.is_dir() and not p.name.startswith(".")):
        sessions = []
        for session in sorted(p for p in project.iterdir() if p.is_dir()):
            logs = sorted(session.glob("session.v*.jsonl*"))
            if not logs:
                continue
            sessions.append(
                {
                    "id": session.name,
                    "log": logs[0].name,
                    "bytes": logs[0].stat().st_size,
                    "encoding": "zstd" if logs[0].name.endswith(".zstd") else "plain",
                }
            )
        if sessions:
            out.append({"projectKey": project.name, "sessionCount": len(sessions), "sessions": sessions})
    return out


def build_readme(manifest: dict) -> str:
    cred = manifest["credentials"]
    lines = [
        "# 在电脑上继续用这份数据",
        "",
        "这个包从 DSHA 容器里的 `$DSH_HOME`（默认 `/root/.dsh`）导出，只包含可跨机器的数据。",
        "",
        "## 包里的内容",
        "",
        "```",
        "home/             →  恢复到电脑的 $DSH_HOME（默认 ~/.dsh）",
        "workspace/        →  可选，工作区文件（若导出时带了 --with-workspace）",
        "manifest.json     →  来源、清单、凭据策略",
        "```",
        "",
        "## 电脑上怎么做",
        "",
        "1. 装 dsh（版本尽量与手机一致，手机上是 `{}`）：".format(manifest["source"]["dshVersion"]),
        "",
        "   ```bash",
        "   npm install -g @deepseek-ai/dsh@{}".format(manifest["source"]["dshVersion"]),
        "   ```",
        "",
        "2. 先空跑看它打算干什么（默认不改任何文件）：",
        "",
        "   ```bash",
        "   node dsha-portable-restore.mjs plan --package {}".format(manifest["package"]["name"]),
        "   ```",
        "",
        "3. 确认后执行：",
        "",
        "   ```bash",
        "   node dsha-portable-restore.mjs apply --package {}".format(manifest["package"]["name"]),
        "   ```",
        "",
        "   已有 `~/.dsh` 会被整体改名为 `~/.dsh.before-restore-<时间戳>` 保留，不会静默覆盖。",
        "",
        "4. 起 dsh：",
        "",
        "   ```bash",
        "   dsh web --no-open",
        "   ```",
        "",
        "## 凭据",
        "",
    ]
    if cred["included"]:
        lines += [
            "本包**包含** `.credentials.yaml`，但已经字段级剔除：`{}`。".format(
                "、".join(cred["prunedRecords"]) or "（无匹配记录）"
            ),
            "留下的只有 `refs`（你自己的 API Key 引用）。文件权限已设为 600（dsh 拒绝群组/他人可读的凭据文件）。",
        ]
    else:
        lines += [
            "本包**不含** `.credentials.yaml`（默认行为：{}）。".format(cred["reason"]),
            "到电脑上第一次跑 dsh 时，重新填一次 API Key 即可。",
        ]
    lines += [
        "",
        "包里也绝不含设备桥凭据（`.bridge_token` / `.bridge_headers`）、"
        "浏览器会话密钥、DSHA 随包脚本与设备侧插件。",
        "",
        "## 会话能不能「接着聊」",
        "",
        "会话日志里记着手机上的绝对工作目录（cwd），dsh 也用 `projectKey(cwd)` 推导日志所在目录并做一致性核验。",
        "",
    ]
    if manifest["options"]["rewriteCwd"]:
        lines += [
            "导出时已开启 `--rewrite-cwd`：日志已转成明文 `session.vN.jsonl` 并改写 cwd，",
            "profile 里也加了 `session-persistence-jsonl: compression: none`。",
            "恢复时请务必带 `--rewrite-cwd`，否则 dsh 会因为同一个 sessions 根混用两种编码而报错。",
        ]
    else:
        lines += [
            "本包**原样保留**了这些 cwd。电脑上恢复后：查看历史没问题；",
            "要在旧会话上继续对话，工作区最好落在同一个绝对路径，或者恢复时带 `--rewrite-cwd`",
            "让恢复工具改写 cwd（会同时把 sessions 转成明文编码）。",
        ]
    lines += [
        "",
        "手机上的工作目录：`{}`".format(
            ", ".join(manifest["options"]["workspaces"]) or "（未导出工作区）"
        ),
        "",
        "## 会话数",
        "",
        "共 {} 个会话、{} 个项目目录。".format(
            manifest["summary"]["sessionCount"], len(manifest["sessions"])
        ),
        "",
    ]
    return "\n".join(lines)


def cmd_plan(home: Path, plan: dict, args) -> int:
    total_files = 0
    total_bytes = 0
    print("DSH_HOME   : {}".format(home))
    print("dsh 版本   : {}".format(dsh_version()))
    print("package    : {}{}{}".format(plan["packagePrefix"], "<时间戳>", plan["extension"]))
    print()
    print("== 进包（白名单）==")
    for entry in plan["include"]:
        rel = entry["path"].format(profile=args.profile)
        target = home / rel
        if target.is_dir():
            files, size, _ = count_tree(target, set(entry.get("skip", [])), set())
            print("  [dir ] {:44s} {:>6} 文件  {:>10}  {}".format(rel, files, human_bytes(size), entry["why"]))
            total_files += files
            total_bytes += size
        elif target.is_file():
            size = target.stat().st_size
            print("  [file] {:44s} {:>6} 文件  {:>10}  {}".format(rel, 1, human_bytes(size), entry["why"]))
            total_files += 1
            total_bytes += size
        else:
            print("  [skip] {:44s} 不存在（跳过）".format(rel))
    cred = plan["credentials"]
    print(
        "  [{}] {:44s} {}".format(
            "cred" if args.with_credentials else "skip",
            cred["path"],
            "已要求包含（会剔除 {}）".format("、".join(cred["pruneRecordPrefixes"]))
            if args.with_credentials
            else "默认不含：" + cred["why"],
        )
    )
    print()
    print("== 不进包（节选，全部理由见 manifest.json 的 excluded）==")
    for entry in plan["exclude"][:6]:
        print("  {:52s} {}".format(entry["path"], entry["why"]))
    print("  ...（共 {} 条）".format(len(plan["exclude"])))
    print()
    print("小计：{} 文件，{}（不含工作区与凭据）".format(total_files, human_bytes(total_bytes)))
    sessions = session_summary(home)
    print("会话：{} 个项目目录，{} 个会话".format(len(sessions), sum(s["sessionCount"] for s in sessions)))
    return 0


def cmd_export(home: Path, plan: dict, args) -> int:
    if not home.is_dir():
        die("DSH_HOME 不存在：{}".format(home))
    out_dir = Path(args.out).expanduser() if args.out else default_out_dir()
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    name = "{}{}{}".format(plan["packagePrefix"], stamp, plan["extension"])
    out_path = out_dir / name

    workspace_files = 0
    workspace_bytes = 0
    workspace_list = [Path(p).expanduser() for p in (args.with_workspace or [])]
    for ws in workspace_list:
        if not ws.is_dir():
            die("工作区不存在：{}".format(ws))

    with tempfile.TemporaryDirectory(prefix="dsha-portable-") as tmp:
        stage = Path(tmp) / "pkg"
        home_stage = stage / "home"
        home_stage.mkdir(parents=True, exist_ok=True)

        included = []
        excluded_notes = list(plan["exclude"])
        total_files = 0
        total_bytes = 0

        for entry in plan["include"]:
            rel = entry["path"].format(profile=args.profile)
            source = home / rel
            if source.is_dir():
                dst = home_stage / rel
                dst.mkdir(parents=True, exist_ok=True)
                skip = set(entry.get("skip", []))
                files, size, skipped = copy_tree(source, dst, skip, set())
                included.append(
                    {"path": rel, "files": files, "bytes": size, "why": entry["why"], "skipped": skipped}
                )
                total_files += files
                total_bytes += size
            elif source.is_file():
                dst = home_stage / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, dst)
                included.append(
                    {"path": rel, "files": 1, "bytes": source.stat().st_size, "why": entry["why"]}
                )
                total_files += 1
                total_bytes += source.stat().st_size

        # 凭据：默认排除；要求包含时必须经过滤器成功，否则整个不带
        cred_spec = plan["credentials"]
        cred_entry = {
            "included": False,
            "policy": "exclude-by-default",
            "flag": cred_spec["flag"],
            "prunedRecords": [],
            "reason": "默认不导出（" + cred_spec["why"] + "）",
        }
        if args.with_credentials:
            filtered, removed_or_reason = filter_credentials(home / cred_spec["path"], home, plan)
            if filtered is None:
                cred_entry["reason"] = "过滤失败，已改为不导出：{}".format(removed_or_reason)
                print(
                    "警告：凭据过滤失败（{}），本包不含 .credentials.yaml".format(removed_or_reason),
                    file=sys.stderr,
                )
            else:
                target = home_stage / cred_spec["path"]
                target.write_text(filtered, encoding="utf-8")
                os.chmod(target, 0o600)
                cred_entry.update(
                    {
                        "included": True,
                        "removed": removed_or_reason,
                        "prunedRecords": list(removed_or_reason)
                        if isinstance(removed_or_reason, list)
                        else [],
                        "reason": "用户显式要求，已剔除 " + "、".join(cred_spec["pruneRecordPrefixes"]),
                    }
                )
                total_files += 1
                total_bytes += target.stat().st_size

        if workspace_list:
            ws_root = stage / "workspace"
            for index, ws in enumerate(workspace_list):
                dst = ws_root if len(workspace_list) == 1 else ws_root / "ws{}".format(index + 1)
                dst.mkdir(parents=True, exist_ok=True)
                files, size, skipped = copy_tree(
                    ws, dst, WORKSPACE_SKIP_DIRS, WORKSPACE_SKIP_TOP
                )
                workspace_files += files
                workspace_bytes += size
                included.append(
                    {
                        "path": "workspace/{}".format(dst.name if len(workspace_list) > 1 else ""),
                        "source": str(ws),
                        "files": files,
                        "bytes": size,
                        "why": "用户要求的工作区文件（跳过 .git/node_modules 等）",
                        "skipped": skipped,
                    }
                )

        sessions = session_summary(home)
        package_info = {"name": name, "bytes": 0, "sha256": "", "outDir": str(out_dir)}
        manifest = {
            "schemaVersion": plan["schemaVersion"],
            "kind": "dsha-portable-package",
            "createdAt": _dt.datetime.now().astimezone().isoformat(timespec="seconds"),
            "source": {
                "dshHome": str(home),
                "dshVersion": dsh_version(),
                "profile": args.profile,
                "host": os.uname().nodename if hasattr(os, "uname") else "android",
                "platform": "dsha-android-container",
            },
            "options": {
                "withCredentials": bool(args.with_credentials),
                "workspaces": [str(w) for w in workspace_list],
                "rewriteCwd": False,
            },
            "package": package_info,
            "included": included,
            "excluded": excluded_notes,
            "credentials": cred_entry,
            "sessions": sessions,
            "summary": {
                "files": total_files + workspace_files,
                "bytes": total_bytes + workspace_bytes,
                "homeFiles": total_files,
                "homeBytes": total_bytes,
                "workspaceFiles": workspace_files,
                "workspaceBytes": workspace_bytes,
                "sessionCount": sum(s["sessionCount"] for s in sessions),
                "projectDirs": len(sessions),
            },
        }
        (stage / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        (stage / "README-在电脑上恢复.md").write_text(build_readme(manifest), encoding="utf-8")

        with tarfile.open(out_path, "w:gz", format=tarfile.PAX_FORMAT) as archive:
            for child in sorted(stage.iterdir()):
                archive.add(child, arcname=child.name, recursive=True)

    import hashlib

    digest = hashlib.sha256()
    with out_path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    size = out_path.stat().st_size
    print("已生成：{}".format(out_path))
    print("大小  ：{}（工作区 {} 文件 {}）".format(human_bytes(size), workspace_files, human_bytes(workspace_bytes)))
    print("sha256：{}".format(digest.hexdigest()))
    print(
        "内容  ：{} 文件 / {} 会话 / 凭据{}".format(
            total_files + workspace_files,
            manifest["summary"]["sessionCount"],
            "已含（已剔除 client-connection/*）" if manifest["credentials"]["included"] else "不含",
        )
    )
    print()
    print("电脑上：node dsha-portable-restore.mjs plan --package {}".format(name))
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="dsha-portable", description="导出可在电脑上继续使用的 DSH 数据包"
    )
    sub = parser.add_subparsers(dest="command", required=True)

    def common(p):
        p.add_argument(
            "--home",
            default=os.environ.get("DSH_HOME") or str(Path.home() / ".dsh"),
            help="DSH_HOME（默认 $DSH_HOME 或 ~/.dsh）",
        )
        p.add_argument("--profile", default="web", help="profile 名（默认 web）")

    p_plan = sub.add_parser("plan", help="只打印会打包什么，不写文件")
    common(p_plan)
    p_plan.add_argument("--with-credentials", action="store_true")

    p_export = sub.add_parser("export", help="打包")
    common(p_export)
    p_export.add_argument("--out", default="", help="输出目录（默认 /sdcard/Download/DSHA）")
    p_export.add_argument(
        "--with-credentials",
        action="store_true",
        help="包含 .credentials.yaml（仍会剔除 client-connection/*；过滤失败则整个不带）",
    )
    p_export.add_argument(
        "--with-workspace",
        action="append",
        default=[],
        metavar="DIR",
        help="附带工作区目录（可重复；跳过 .git/node_modules）",
    )

    args = parser.parse_args(argv)
    plan = load_plan()
    home = Path(args.home).expanduser()
    if args.command == "plan":
        return cmd_plan(home, plan, args)
    return cmd_export(home, plan, args)


if __name__ == "__main__":
    sys.exit(main())
