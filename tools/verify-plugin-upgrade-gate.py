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
import tempfile


ROOT = Path(__file__).resolve().parents[1]
from test_runtime_fixture import runtime as verified_runtime
from release_names import apk_filename


def run(label: str, command: list[str], extra_env: dict[str, str] | None = None) -> None:
    print(f"\n==> {label}", flush=True)
    environment = os.environ.copy()
    if extra_env:
        environment.update(extra_env)
    result = subprocess.run(command, cwd=ROOT, env=environment)
    if result.returncode:
        raise SystemExit(f"插件覆盖升级门禁失败：{label}（退出码 {result.returncode}）")


def run_current_host_plugin_manager(node: str, raw_runtime: Path, managed_runtime: Path) -> None:
    run(
        "Web 插件管理直接安装与启用",
        [node, str(ROOT / "tools/test-native-plugin-manager.mjs")],
        {
            "DSHA_TEST_MANAGED_RUNTIME": str(managed_runtime.resolve()),
            "DSHA_TEST_RUNTIME": str(raw_runtime.resolve()),
            "DSHA_PYTHON": sys.executable,
            "NARB_DISABLE_NATIVE_CACHE": "1",
        },
    )


def run_current_builtin_guide(node: str, raw_runtime: Path) -> None:
    guide_source = ROOT / 'app/src/main/assets/builtin-plugins/dsh-device-shell-guide'
    if not (guide_source / 'package.json').is_file() or not (guide_source / 'lib/index.js').is_file():
        raise RuntimeError('CURRENT_BUILTIN_GUIDE_SOURCE_REQUIRED')
    with tempfile.TemporaryDirectory(prefix='plugin-upgrade-guide-', dir=ROOT / 'app/build') as temporary:
        owned = Path(temporary).resolve()
        if not owned.is_relative_to(ROOT / 'app/build'):
            raise RuntimeError('BUILTIN_GUIDE_FIXTURE_SCOPE')
        guide = owned / 'guide'
        shutil.copytree(guide_source, guide)
        modules = owned / 'node_modules'
        target = raw_runtime.resolve() / 'node_modules'
        # The copied real guide resolves exactly the verified raw dependencies. No byte
        # or member in the canonical managed/raw fixtures is written by this preparation.
        if os.name == 'nt':
            subprocess.run([node, '-e',
                            "require('node:fs').symlinkSync(process.argv[1],process.argv[2],'junction')",
                            str(target), str(modules)], check=True)
        else:
            modules.symlink_to(target, target_is_directory=True)
        run(
            "内置插件 peer 兼容与消息准入",
            [node, str(ROOT / "tools/test-dsh-builtins.mjs")],
            {"DSHA_TEST_RUNTIME": str(raw_runtime.resolve()),
             "DSHA_TEST_GUIDE": str(guide / 'lib/index.js'),
             "NARB_DISABLE_NATIVE_CACHE": "1"},
        )


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
        args.apks = [ROOT / 'release' / apk_filename(flavor) for flavor in ('standard', 'low')]
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

    baseline = {"DSHA_TEST_RUNTIME": str(args.raw_runtime.resolve()),
                "DSHA_TEST_MANAGED_RUNTIME": str(args.managed_runtime.resolve()),
                "DSHA_PYTHON": sys.executable, "NARB_DISABLE_NATIVE_CACHE": "1"}
    def run_bound(label, command, extra_env=None):
        run(label, command, {**baseline, **(extra_env or {})})

    python_tests = [
        ("rc1 分代迁移与独立快照", "test-rc1-migration.py"),
        ("启动链接缓存与受管身份", "test-startup-recovery.py"),
        ("插件发现、启停与删除边界", "test-plugin-discovery.py"),
        ("系统与用户插件备份恢复分层", "test-backup-engine.py"),
        ("第三方插件直接启用与旧标记迁移", "test-plugin-review.py"),
        ("插件依赖未锁定重解析与失败保护", "test-plugin-dependencies.py"),
        ("插件安装事务与强杀恢复", "test-plugin-transactions.py"),
    ]
    for label, script in python_tests:
        run_bound(label, [sys.executable, "-B", str(ROOT / "tools" / script)])

    run_bound("当前 DSH schema 迁移与逐节读回",[node,str(ROOT/"tools/test-rc1-settings-migration.mjs")],{"DSHA_TEST_RUNTIME":str(args.raw_runtime)})
    run_current_host_plugin_manager(node, args.raw_runtime, args.managed_runtime)
    run_bound(
        "实际 DSH 加载器与原生同名插件来源一致",
        [node, str(ROOT / "tools/test-plugin-resolution-order.mjs")],
        {"DSHA_TEST_RUNTIME": str(args.raw_runtime.resolve()), "DSHA_PYTHON": sys.executable},
    )
    run_bound(
        "Web 请求停止按钮与保留待发送队列",
        [node, str(ROOT / "tools/test-conversation-stop.mjs")],
        {"DSHA_STOP_RUNTIME": str(args.raw_runtime.resolve())},
    )
    run_bound(
        "前后台 WebSocket 恢复与旧输出重新同步",
        [node, str(ROOT / "tools/test-browser-stream-resume.mjs")],
        {"DSHA_TEST_RUNTIME": str(args.raw_runtime.resolve())},
    )
    run_bound(
        "Android MCP 原生截图图片传输与取消",
        [node, str(ROOT / "tools/test-android-computer-use.mjs")],
    )
    run_current_builtin_guide(node, args.raw_runtime)
    run_bound(
        "旧工作流包名兼容链接",
        [node, str(ROOT / "tools/test-workflow-compat-alias.mjs")],
        {"DSHA_TEST_RUNTIME": str(args.raw_runtime.resolve())},
    )
    run_bound(
        "DeepSeek Messages Agent Team 与工具结果兼容",
        [node, str(ROOT / "tools/test-deepseek-messages-compat.mjs")],
        {
            "DSHA_TEST_RUNTIME": str(args.raw_runtime.resolve()),
            "DSHA_RUNTIME_ARCHIVE": str((ROOT / "app/src/main/assets/dsh-runtime.bin").resolve()),
        },
    )
    run_bound(
        "Lexical claim 装饰与运行时归档兼容",
        [node, str(ROOT / "tools/test-lexical-claim-compat.mjs")],
        {
            "DSHA_TEST_RUNTIME": str(args.raw_runtime.resolve()),
            "DSHA_RUNTIME_ARCHIVE": str((ROOT / "app/src/main/assets/dsh-runtime.bin").resolve()),
        },
    )
    run_bound(
        "Conversation target withdrawal hidden fallback",
        [node, str(ROOT / "tools/test-conversation-materialized.mjs")],
        {
            "DSHA_TEST_RUNTIME": str(args.raw_runtime.resolve()),
            "DSHA_RUNTIME_ARCHIVE": str((ROOT / "app/src/main/assets/dsh-runtime.bin").resolve()),
        },
    )
    run_bound(
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
