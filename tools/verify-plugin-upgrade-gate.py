#!/usr/bin/env python3
"""发布前统一验证插件覆盖升级边界；任一子检查失败即拒绝交付。"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]


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
        default=ROOT / "app/build/release136/host-runtime",
        help="已应用 DSHA 网页插件补丁的宿主运行时",
    )
    parser.add_argument(
        "--raw-runtime",
        type=Path,
        default=ROOT / "app/build/locked-dsh-runtime-138",
        help="锁定的原始 DSH 运行时",
    )
    parser.add_argument(
        "apks",
        nargs="*",
        type=Path,
        default=[
            ROOT / "release/dsha-0.1.6-alpha2.apk",
            ROOT / "release/dsha-0.1.6-alpha2low.apk",
        ],
    )
    args = parser.parse_args()

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
        ("启动链接缓存与受管身份", "test-startup-recovery.py"),
        ("插件发现、启停与删除边界", "test-plugin-discovery.py"),
        ("系统与用户插件备份恢复分层", "test-backup-engine.py"),
        ("第三方插件原生审阅", "test-plugin-review.py"),
        ("插件依赖冻结与离线失败保护", "test-plugin-dependencies.py"),
        ("插件安装事务与强杀恢复", "test-plugin-transactions.py"),
    ]
    for label, script in python_tests:
        run(label, [sys.executable, "-B", str(ROOT / "tools" / script)])

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
