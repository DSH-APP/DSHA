#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「启用了却跑不起来、还会拖垮整个 profile」的 loader entry 禁用掉。

为什么需要：dsh 的插件列表里会列出它自己的依赖，其中 cordis-plugin-hmr（热重载）
要求 node 带 --expose-internals 才能初始化。用户在 WebUI 里手一点启用，重启后
整个 profile 加载失败：

    Error: failed to apply loader entry 8583b572 (@deepseek-ai/cordis-plugin-hmr):
    --expose-internals is required for HMR service

于是 Web 起不来 → 进不去 WebUI → 也就没法把它关掉，死锁。

本脚本从启动日志里认出崩掉的 entry，在 profile 的 cordis.patch.yml 里追加
`{id: <entry>, disabled: true}`（patch 层就是干这个的：id 定向的覆盖与禁用），
下次启动即可正常进入。

只禁用不删除 —— 用户想恢复，把 patch.yml 里那条删掉就行。改动前备份。

用法：
  python3 heal-profile-boot.py --log /root/dsh-web.log [--profile /root/.dsh/profiles/web]
"""
import os
import re
import shutil
import sys
import time

DEFAULT_PROFILE = "/root/.dsh/profiles/web"
# 只对「明确知道需要特殊启动标志」的插件动手；别的启动失败原因（缺依赖、配置错）
# 不该靠禁用来掩盖，那样会把真问题藏起来。
KNOWN_UNRUNNABLE = (
    ("--expose-internals is required", "cordis-plugin-hmr", "热重载插件需要 node --expose-internals"),
)
ENTRY_RE = re.compile(r"failed to (?:apply|create|init) loader entry ([0-9a-zA-Z_]+) \(([^)]+)\)")


def validate_patch_text(text):
    """Validate the small list-shaped patch without requiring a new rootfs package.

    Ubuntu's minimal offline rootfs does not ship PyYAML.  Use it when present for
    full validation; otherwise reject only shapes that cannot be a Cordis patch
    list.  The generated entries contain a restricted id and fixed scalar values,
    so this fallback still prevents a torn/obviously malformed write while keeping
    HMR recovery fully offline.
    """
    try:
        import yaml
        parsed = yaml.safe_load(text)
        return parsed is None or isinstance(parsed, list)
    except ImportError:
        meaningful = []
        for raw in text.splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if "\t" in raw or any(ord(ch) < 0x20 for ch in raw if ch not in "\r\n"):
                return False
            meaningful.append(line)
        if not meaningful:
            return True
        # An empty list is normalized before this check.  Existing Cordis patch
        # files are sequences of mapping entries.  Do not append to an inline
        # non-empty list: that shape needs a real YAML parser to edit safely.
        if meaningful == ["[]"]:
            return True
        return meaningful[0].startswith("-")
    except Exception:
        return False


def arg(name, default=None):
    key = "--" + name
    if key in sys.argv:
        i = sys.argv.index(key)
        if i + 1 < len(sys.argv) and not sys.argv[i + 1].startswith("--"):
            return sys.argv[i + 1]
        return True
    return default


def main():
    log_path = arg("log", "/root/dsh-web.log")
    profile = arg("profile", DEFAULT_PROFILE)
    patch = os.path.join(profile, "cordis.patch.yml")

    if not os.path.isfile(log_path):
        print("HEAL_PROFILE: 没有日志 %s" % log_path)
        return 0
    try:
        with open(log_path, encoding="utf-8", errors="replace") as f:
            # 只看尾部：日志会累积，早期的旧错误不该反复触发禁用
            log = f.read()[-60000:]
    except OSError as e:
        print("HEAL_PROFILE: 读不到日志：%s" % e)
        return 0

    hits = []
    for marker, hint_pkg, why in KNOWN_UNRUNNABLE:
        if marker not in log:
            continue
        # 优先用日志里那条 entry 的真实 id（patch 是按 id 定向的）
        for m in ENTRY_RE.finditer(log):
            eid, name = m.group(1), m.group(2)
            if hint_pkg in name:
                hits.append((eid, name, why))
        if not hits:
            print("HEAL_PROFILE: 认出「%s」但日志里没有 entry id，无法定向禁用" % why)

    if not hits:
        print("HEAL_PROFILE: 没有需要处理的 entry")
        return 0

    if not os.path.isfile(patch):
        print("HEAL_PROFILE: 找不到 %s" % patch)
        return 0
    try:
        with open(patch, encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        print("HEAL_PROFILE: 读不到 patch 文件：%s" % e)
        return 0

    added = []
    for eid, name, why in hits:
        # 幂等：已经禁用过就别重复追加
        if re.search(r"id:\s*['\"]?%s['\"]?" % re.escape(eid), text):
            print("HEAL_PROFILE: %s（%s）已在 patch 里，跳过" % (eid, name))
            continue
        entry = ("- id: %s\n  disabled: true\n"
                 "  # DSHA 自动禁用：%s；启动时它会让整个 profile 加载失败。\n"
                 "  # 想恢复就删掉这三行，并改用 node --expose-internals 启动。\n"
                 % (eid, why))
        # 空补丁层是字面量 []，得先去掉才能变成 YAML 数组
        stripped = text.rstrip()
        if stripped.endswith("[]"):
            text = stripped[: stripped.rfind("[]")].rstrip("\n") + "\n" + entry
        else:
            text = stripped + "\n" + entry
        added.append((eid, name))

    if not added:
        print("HEAL_PROFILE_OK: 无需改动")
        return 0

    # 写回前先验证 YAML 合法，别把用户的 profile 弄成打不开的样子。
    # 轻量 rootfs 可能没有 PyYAML，validate_patch_text() 会走离线保守校验。
    if not validate_patch_text(text):
        print("HEAL_PROFILE: 生成结果不是 YAML 数组，放弃改动")
        return 1

    try:
        shutil.copy2(patch, patch + ".dsha-bak-" + time.strftime("%Y%m%d-%H%M%S"))
        # 原子写：直接覆盖的话，进程被杀/掉电会留下半个 YAML，
        # 而这个文件写坏了 dsh 根本起不来（比不修更糟）
        tmp = patch + ".dsha-tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, patch)
    except OSError as e:
        print("HEAL_PROFILE: 写入失败：%s" % e)
        return 1

    for eid, name in added:
        print("HEAL_PROFILE: 已禁用 %s（%s）" % (eid, name))
    print("HEAL_PROFILE_OK: 禁用 %d 项，重启 Web 即可正常进入" % len(added))
    return 0


if __name__ == "__main__":
    sys.exit(main())
