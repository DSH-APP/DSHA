#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修复 pnpm 装一半留下的「空壳插件」（GitHub 议题 #36 Bug 3）。

现象（用户实测）：
    node_modules/dsh-device-shell-guide/
        └── package.json      内容是 pnpm 的 _pnpmPlaceholder 占位
    node_modules/.ignored_dsh-device-shell-guide/
        └── （完整的插件文件都在这里）

成因：pnpm 装 GitHub 来源的插件时，tarball 下载成功但 prepare/build 脚本失败
（pnpm 11+ 默认不执行未知包的 build script，会报 ERR_PNPM_IGNORED_BUILDS）。
pnpm 把已经落地的完整目录 rename 成 `.ignored_<name>`，同时在原位留一个
只含 package.json 的占位壳。dsh 加载时看到的是那个壳 —— 插件形同不存在，
而用户从任何界面都看不出问题，只能靠人工翻 node_modules 才能发现。

这个脚本做三件事：
  ① 找出所有 `.ignored_*` 目录；
  ② 判断同名的正常目录是不是空壳（占位标记 / 只有 package.json / 没有入口文件）；
  ③ 是空壳就换回来（壳挪去 .shell-backup/，完整目录改回正名）。

只动「确认是空壳」的情形。正常目录看起来完整时一律不碰 ——
`.ignored_` 也可能是 pnpm 正常的忽略产物，误换会把好的覆盖掉。
"""

import json
import os
import shutil
import sys

DSH_HOME = os.environ.get("DSH_HOME", "/root/.dsh")
# 插件可能落在这几处：dsh 源码工作区、profile 私有 node_modules、全局 npm 根
CANDIDATE_ROOTS = [
    "/root/deepseek-harness/node_modules",
    "/root/dsh/node_modules",
    os.path.join(DSH_HOME, "node_modules"),
    "/usr/lib/node_modules",
    "/usr/local/lib/node_modules",
]

# pnpm 占位壳的标记；命中任意一个就认定是壳
PLACEHOLDER_MARKS = ("_pnpmPlaceholder", "placeholder")


def profile_roots():
    """profiles/<name>/node_modules 也要扫（用户插件多半装在这里）"""
    out = []
    pdir = os.path.join(DSH_HOME, "profiles")
    try:
        for name in os.listdir(pdir):
            nm = os.path.join(pdir, name, "node_modules")
            if os.path.isdir(nm):
                out.append(nm)
    except OSError:
        pass
    return out


def is_shell(path):
    """判断一个包目录是不是 pnpm 留下的空壳。

    三种判据，任一命中即是壳：
      · package.json 里有 _pnpmPlaceholder 标记
      · 目录里除了 package.json 什么都没有
      · package.json 声明的入口文件根本不存在
    """
    pj = os.path.join(path, "package.json")
    if not os.path.isfile(pj):
        # 连 package.json 都没有，不是包，交给别的机制处理
        return False, "没有 package.json"
    try:
        with open(pj, encoding="utf-8", errors="replace") as f:
            raw = f.read()
    except OSError as e:
        return False, "读不到 package.json：%s" % e

    for mark in PLACEHOLDER_MARKS:
        if mark in raw:
            return True, "package.json 含 pnpm 占位标记 %s" % mark

    try:
        entries = [e for e in os.listdir(path) if e != "package.json"]
    except OSError:
        entries = []
    if not entries:
        return True, "目录里只有 package.json"

    # 入口文件缺失也算壳（装了一半、文件没落全）
    try:
        meta = json.loads(raw)
    except Exception:
        return False, "package.json 不是合法 JSON（不当作壳，交给别的检查）"
    for key in ("main", "module"):
        ent = meta.get(key)
        if isinstance(ent, str) and ent:
            if not os.path.exists(os.path.join(path, ent)):
                return True, "入口 %s=%s 不存在" % (key, ent)
    return False, "看起来完整"


def looks_complete(path):
    """`.ignored_` 目录本身得像个完整的包，才值得换回去。"""
    pj = os.path.join(path, "package.json")
    if not os.path.isfile(pj):
        return False
    try:
        with open(pj, encoding="utf-8", errors="replace") as f:
            raw = f.read()
    except OSError:
        return False
    for mark in PLACEHOLDER_MARKS:
        if mark in raw:
            return False           # 它自己也是个壳，换了没意义
    try:
        return len([e for e in os.listdir(path) if e != "package.json"]) > 0
    except OSError:
        return False


def heal_root(root, dry_run):
    """处理一个 node_modules 目录，返回 (修复数, 报告行列表)"""
    fixed, lines = 0, []
    try:
        names = os.listdir(root)
    except OSError:
        return 0, []

    for name in names:
        if not name.startswith(".ignored_"):
            continue
        real_name = name[len(".ignored_"):]
        ignored_path = os.path.join(root, name)
        target_path = os.path.join(root, real_name)
        if not os.path.isdir(ignored_path):
            continue
        if not looks_complete(ignored_path):
            lines.append("  跳过 %s：.ignored_ 本身不完整" % real_name)
            continue

        if not os.path.exists(target_path):
            # 正名不存在：直接改回去
            if dry_run:
                lines.append("  [待修] %s：正名缺失，可直接改回" % real_name)
            else:
                try:
                    os.rename(ignored_path, target_path)
                    fixed += 1
                    lines.append("  已恢复 %s（正名此前缺失）" % real_name)
                except OSError as e:
                    lines.append("  恢复 %s 失败：%s" % (real_name, e))
            continue

        shell, why = is_shell(target_path)
        if not shell:
            lines.append("  跳过 %s：现有目录看起来完整（%s）" % (real_name, why))
            continue

        if dry_run:
            lines.append("  [待修] %s：%s" % (real_name, why))
            fixed += 1
            continue

        # 壳挪走留证据，别直接删 —— 万一判断错了还能找回
        bak_dir = os.path.join(root, ".shell-backup")
        try:
            os.makedirs(bak_dir, exist_ok=True)
            bak = os.path.join(bak_dir, real_name)
            if os.path.exists(bak):
                shutil.rmtree(bak, ignore_errors=True)
            os.rename(target_path, bak)
            os.rename(ignored_path, target_path)
            fixed += 1
            lines.append("  已恢复 %s（%s）" % (real_name, why))
        except OSError as e:
            lines.append("  恢复 %s 失败：%s" % (real_name, e))
    return fixed, lines


def main():
    dry_run = "--check" in sys.argv
    roots = [r for r in CANDIDATE_ROOTS + profile_roots() if os.path.isdir(r)]
    if not roots:
        print("PNPM_SHELL: 没找到 node_modules 目录")
        return 0

    total, all_lines = 0, []
    for root in roots:
        n, lines = heal_root(root, dry_run)
        total += n
        if lines:
            all_lines.append("%s：" % root)
            all_lines.extend(lines)

    if total == 0:
        print("PNPM_SHELL: 没有需要修的空壳插件")
    else:
        print("PNPM_SHELL: %s %d 个空壳插件" % ("发现" if dry_run else "已修复", total))
    for line in all_lines:
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
