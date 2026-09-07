#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""assets 里的脚本「能不能跑起来」的静态检查。

为什么必须有：assets 下的 .py/.sh/.js 是 APK 内置的离线脚本，
随 Java 与离线 rootfs 一起发布，不经过 gradle 编译，也没有 Android 运行时测试。

  2026-08-25 实例：selftest.py 里
      PENDING_RE = re.compile(r"([\\w@/.-]+):\\s*pending \\\\(waiting for service")
  raw string 里的 `\\\\(` 是「反斜杠 + 左括号」，正则里就是「字面反斜杠 +
  未闭合分组」→ re.error: missing )。它在**模块级**执行，所以
  `python3 selftest.py` 一启动就抛异常退出 —— 自检功能整个不可用。
  而「让用户跑自检把报告贴过来」是我们排查问题的第一步。
  ast.parse 查不出这种错（语法完全合法），只有真去编译那个正则才知道。

查三件事，都不执行脚本本身（不会有副作用）：
  1. .py：ast 语法 + 把所有 re.compile(字面量) 真编译一遍
  2. .sh：bash -n（有 bash 才查）
  3. .js：node --check（有 node 才查）

用法：python3 tools/check-asset-scripts.py
退出码非 0 = 有问题。
"""
import ast
import os
import re
import shutil
import subprocess
import sys

ASSETS = "app/src/main/assets"
SKIP_DIRS = {"__pycache__", "node_modules"}

problems = []
checked = {"py": 0, "sh": 0, "js": 0, "regex": 0}


def walk(exts):
    for root, dirs, files in os.walk(ASSETS):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in sorted(files):
            if name.endswith(exts):
                yield os.path.join(root, name)


def check_python(path):
    src = open(path, encoding="utf-8").read()
    try:
        tree = ast.parse(src, filename=path)
    except SyntaxError as e:
        problems.append("%s:%s 语法错误：%s" % (path, e.lineno, e.msg))
        return
    checked["py"] += 1
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        f = node.func
        # re.compile(...) / re.match(...) / re.sub(...) 等第一个参数是模式
        if isinstance(f, ast.Attribute) and isinstance(f.value, ast.Name) \
                and f.value.id == "re" and f.attr in (
                    "compile", "match", "search", "fullmatch", "findall",
                    "finditer", "sub", "subn", "split"):
            if not node.args:
                continue
            pat = node.args[0]
            if not isinstance(pat, ast.Constant) or not isinstance(pat.value, str):
                continue        # 变量拼出来的模式查不了，跳过
            checked["regex"] += 1
            try:
                re.compile(pat.value)
            except re.error as e:
                problems.append("%s:%s 正则编译失败：%s\n      模式：%r"
                                % (path, pat.lineno, e, pat.value))


def check_with(cmd, path, kind):
    r = subprocess.run(cmd + [path], stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, text=True)
    checked[kind] += 1
    if r.returncode != 0:
        problems.append("%s 检查失败：\n      %s"
                        % (path, (r.stdout or "").strip().replace("\n", "\n      ")))


def main():
    if not os.path.isdir(ASSETS):
        print("请在仓库根目录运行（找不到 %s）" % ASSETS)
        return 2

    for p in walk((".py",)):
        check_python(p)

    if shutil.which("bash"):
        for p in walk((".sh",)):
            check_with(["bash", "-n"], p, "sh")
    else:
        print("（没有 bash，跳过 .sh 检查）")

    if shutil.which("node"):
        for p in walk((".js",)):
            check_with(["node", "--check"], p, "js")
    else:
        print("（没有 node，跳过 .js 检查）")

    print("检查完成：%d 个 .py（含 %d 个字面量正则）· %d 个 .sh · %d 个 .js"
          % (checked["py"], checked["regex"], checked["sh"], checked["js"]))
    if problems:
        print("")
        for p in problems:
            print("  ✗ %s" % p)
        print("")
        print("共 %d 个问题。资产脚本随 APK 离线发布，坏的不能发。" % len(problems))
        return 1
    print("全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
