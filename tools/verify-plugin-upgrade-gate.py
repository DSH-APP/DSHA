#!/usr/bin/env python3
"""发布前统一验证插件覆盖升级边界；任一子检查失败即拒绝交付。"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import re


ROOT = Path(__file__).resolve().parents[1]
from test_runtime_fixture import runtime as verified_runtime


def run(label: str, command: list[str], extra_env: dict[str, str] | None = None) -> None:
    print(f"\n==> {label}", flush=True)
    environment = os.environ.copy()
    if extra_env:
        environment.update(extra_env)
    result = subprocess.run(command, cwd=ROOT, env=environment)
    if result.returncode:
        raise SystemExit(f"插件覆盖升级门禁失败：{label}（退出码 {result.returncode}）")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--managed-runtime",
        type=Path,
        default=None,
        help="已应用 DSHA 网页插件补丁的宿主运行时",
    )
    parser.add_argument(
        "--raw-runtime",
        type=Path,
        default=None,
        help="锁定的原始 DSH 运行时",
    )
    parser.add_argument(
        "apks",
        nargs="*",
        type=Path,
        default=None,
    )
    args = parser.parse_args()
    if not args.apks:
        build = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        match = re.search(r'versionName\s+"([^"]+)"', build)
        if not match:
            raise SystemExit("无法从 app/build.gradle 读取当前 APK 版本名")
        version_name = match.group(1)
        args.apks = [ROOT / f"release/dsha-{version_name}.apk", ROOT / f"release/dsha-{version_name}low.apk"]
    args.managed_runtime=verified_runtime("managed",args.managed_runtime,full=True,require_current_archive=True)
    args.raw_runtime=verified_runtime("raw",args.raw_runtime,full=True,require_current_archive=True)

    node = shutil.which("node")
    if not node:
        raise SystemExit("找不到 Node，无法执行插件网页与兼容链接门禁")
    for runtime in (args.managed_runtime, args.raw_runtime):
        if not runtime.is_dir():
            raise SystemExit(f"测试运行时不存在：{runtime}")
    for apk in args.apks:
        if not apk.is_file():
            raise SystemExit(f"待验证 APK 不存在：{apk}")

    python_tests = [
        ("rc1 分代迁移与独立快照", "test-rc1-migration.py"),
        ("启动链接缓存与受管身份", "test-startup-recovery.py"),
        ("插件发现、启停与删除边界", "test-plugin-discovery.py"),
        ("系统与用户插件备份恢复分层", "test-backup-engine.py"),
        ("第三方插件原生审阅", "test-plugin-review.py"),
        ("插件依赖冻结与离线失败保护", "test-plugin-dependencies.py"),
        ("插件安装事务与强杀恢复", "test-plugin-transactions.py"),
    ]
    for label, script in python_tests:
        run(label, [sys.executable, "-B", str(ROOT / "tools" / script)])

    run("当前 DSH schema 迁移与逐节读回",[node,str(ROOT/"tools/test-rc1-settings-migration.mjs")],{"DSHA_TEST_RUNTIME":str(args.raw_runtime)})
    run(
        "Web 插件管理原生审阅入口",
        [node, str(ROOT / "tools/test-native-plugin-manager.mjs")],
        {
            "DSHA_TEST_RUNTIME": str(args.managed_runtime.resolve()),
            "DSHA_RAW_RUNTIME": str(args.raw_runtime.resolve()),
        },
    )
    run(
        "旧工作流包名兼容链接",
        [node, str(ROOT / "tools/test-workflow-compat-alias.mjs")],
        {"DSHA_TEST_RUNTIME": str(args.raw_runtime.resolve())},
    )
    run(
        "DeepSeek Messages Agent Team 与工具结果兼容",
        [node, str(ROOT / "tools/test-deepseek-messages-compat.mjs")],
        {
            "DSHA_TEST_RUNTIME": str(args.raw_runtime.resolve()),
            "DSHA_RUNTIME_ARCHIVE": str((ROOT / "app/src/main/assets/dsh-runtime.bin").resolve()),
        },
    )
    run(
        "Lexical claim 装饰与运行时归档兼容",
        [node, str(ROOT / "tools/test-lexical-claim-compat.mjs")],
        {
            "DSHA_TEST_RUNTIME": str(args.raw_runtime.resolve()),
            "DSHA_RUNTIME_ARCHIVE": str((ROOT / "app/src/main/assets/dsh-runtime.bin").resolve()),
        },
    )
    run(
        "Conversation target withdrawal hidden fallback",
        [node, str(ROOT / "tools/test-conversation-materialized.mjs")],
        {
            "DSHA_TEST_RUNTIME": str(args.raw_runtime.resolve()),
            "DSHA_RUNTIME_ARCHIVE": str((ROOT / "app/src/main/assets/dsh-runtime.bin").resolve()),
        },
    )
    run(
        "APK 内置运行时、插件与共享链接",
        [
            sys.executable,
            "-B",
            str(ROOT / "tools/verify-dsh-upgrade-apk.py"),
            *(str(apk.resolve()) for apk in args.apks),
        ],
        {"DSHA_TEST_RUNTIME": str(args.raw_runtime.resolve())},
    )
    print("\nPASS: 插件覆盖升级发布门禁全部通过。", flush=True)


if __name__ == "__main__":
    main()
