#!/usr/bin/env python3
"""针对本项目真实缺陷模式的全量扫描（补 audit.py 的通用规则之外那一层）。

每条规则都来自这个仓库真出过的故障类别，不是泛泛的 lint：
外部数据进 shell、split 后直接取下标、exists-then-act 竞态、
静默失败被当成功、无界集合、Cursor/Stream 泄漏、主线程 IO。
"""
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
files = subprocess.check_output(["git", "ls-files"], cwd=ROOT, text=True).splitlines()
JAVA = [ROOT / f for f in files if f.endswith(".java") and "pure-logic-test" not in f]
PY = [ROOT / f for f in files if f.endswith(".py") and "tools/" not in f]
SH = [ROOT / f for f in files if f.endswith(".sh")]

hits = []


def add(kind, path, line, text, why):
    hits.append((kind, pathlib.Path(path).name, line, text.strip()[:150], why))


def lines_of(p):
    return p.read_text(encoding="utf-8", errors="replace").split("\n")


def scan_java(p):
    ls = lines_of(p)
    src = "\n".join(ls)
    for i, ln in enumerate(ls, 1):
        s = ln.strip()
        if s.startswith("//") or s.startswith("*"):
            continue

        # 1) split 之后直接取下标：输入畸形就是 ArrayIndexOutOfBounds
        m = re.search(r"\.split\([^)]*\)\s*\[\s*([0-9]+)\s*\]", ln)
        if m and m.group(1) != "0":
            add("split 直接取下标", p, i, ln, "输入少一段就 AIOOBE，外部数据必须先查长度")

        # 2) 拼进 shell 的变量没走 ShellQuote
        if re.search(r'exec(AndRead|Rootfs|Shell)?\s*\(\s*"', ln) and '" +' in ln:
            if "ShellQuote" not in ln:
                add("shell 拼接未转义", p, i, ln, "值若来自外部（插件市场/文件名/用户输入）即命令注入")

        # 3) exists() 之后再写/删：TOCTOU
        if re.search(r"\.exists\(\)\s*\)\s*\{?\s*$", ln) and i < len(ls):
            nxt = ls[i].strip() if i < len(ls) else ""
            if re.search(r"new FileOutputStream|Files\.write|\.delete\(\)|createNewFile", nxt):
                add("exists 后再动手", p, i, ln + " / " + nxt, "并发或软链会让判断与动作对不上")

        # 4) 返回值被忽略的关键操作
        m = re.match(r"^(?!.*(?:if|return|=|&&|\|\|)).*\b(mkdirs|delete|renameTo|setReadable|setExecutable|createNewFile)\(\)\s*;$", s)
        if m and "noinspection" not in (ls[i - 2] if i >= 2 else ""):
            add("忽略返回值", p, i, ln, "失败被当成功；这个项目的自愈链路最怕这种静默")

        # 5) 无界集合：put 进静态集合但没有清理
        if re.search(r"static\s+(final\s+)?(java\.util\.)?(Map|List|Set|Deque|Queue)", ln) \
                and "Collections.unmodifiable" not in ln and "=" in ln:
            name = re.search(r"(\w+)\s*=", ln)
            if name:
                nm = name.group(1)
                if re.search(r"\b%s\.(put|add|addLast|offer)\b" % re.escape(nm), src) \
                        and not re.search(r"\b%s\.(remove|clear|poll|entrySet\(\)\.removeIf|keySet\(\)\.remove)" % re.escape(nm), src):
                    add("集合只进不出", p, i, ln, "长跑进程里无界增长 = 慢性泄漏")

        # 6) Cursor / Stream 没用 try-with-resources
        if re.search(r"=\s*\w*(getContentResolver\(\)\.query|rawQuery)\(", ln) and "try (" not in ln:
            add("Cursor 可能泄漏", p, i, ln, "异常路径不会 close，ContentProvider 侧会累积")

        # 7) 主线程疑似 IO：onCreate/onViewCreated/onClick 里直接 exec 或读文件
        if re.search(r"(execAndRead|Files\.read|Files\.write|new FileInputStream)", ln):
            back = "\n".join(ls[max(0, i - 40):i])
            if re.search(r"(onCreate|onViewCreated|onClick|setOnClickListener|onResume)\b", back) \
                    and "new Thread" not in back and "IO.execute" not in back \
                    and "ioLater" not in back and "post(" not in back:
                add("疑似主线程 IO", p, i, ln, "起 proot 子进程或读大文件会 ANR")

        # 8) 整数解析没有边界：直接 parseInt 后当索引/长度用
        if re.search(r"Integer\.parseInt\(", ln) and "try" not in ln and "catch" not in src[:0] :
            pass  # 单行判断不可靠，跳过；由 9) 的 substring 覆盖

        # 9) substring 用了未校验的下标
        if re.search(r"\.substring\(\s*\w+\s*(\+\s*\d+\s*)?\)", ln) and "indexOf" in ln:
            if not re.search(r"(>=?\s*0|>\s*-1|!=\s*-1)", ln):
                add("substring 未校验 indexOf", p, i, ln, "indexOf 返回 -1 时 substring 抛异常")
    # 10) synchronized 里做 IO / 起进程：把锁按住几秒
    for m in re.finditer(r"synchronized\s*\([^)]*\)\s*\{((?:[^{}]|\{[^{}]*\})*)\}", src, re.S):
        body = m.group(1)
        if re.search(r"execAndRead|Files\.(read|write)|new FileOutputStream|Thread\.sleep|\.await\(", body):
            add("锁内做 IO", p, src[:m.start()].count("\n") + 1, m.group(0).split("\n")[0],
                "持锁做 IO/等待，其他线程全排队")


def scan_py(p):
    ls = lines_of(p)
    for i, ln in enumerate(ls, 1):
        s = ln.strip()
        if s.startswith("#"):
            continue
        # subprocess 用 shell=True 且拼了变量
        if "shell=True" in ln and ("%" in ln or "+" in ln or "format(" in ln or "f'" in ln or 'f"' in ln):
            add("py shell 注入面", p, i, ln, "拼字符串进 shell；值来自归档/配置就是注入")
        # os.system 一律可疑
        if re.search(r"\bos\.system\(", ln):
            add("os.system", p, i, ln, "无法转义参数，改 subprocess 列表形式")
        # 直接 index 取值
        m = re.search(r"\.split\([^)]*\)\s*\[\s*([1-9][0-9]*)\s*\]", ln)
        if m:
            add("py split 取下标", p, i, ln, "输入少一段就 IndexError，脚本一崩整条自愈失效")
        # 递归删除
        if "shutil.rmtree" in ln and "ignore_errors" not in ln:
            add("rmtree 无兜底", p, i, ln, "删到一半异常会留半个目录，且调用方不知道")


def scan_sh(p):
    ls = lines_of(p)
    src = "\n".join(ls)
    has_set = bool(re.search(r"^set -[a-z]*e", src, re.M))
    if not has_set and len(ls) > 15:
        add("shell 无 set -e", p, 1, p.name, "中途失败继续跑，最后 echo OK —— 假成功")
    for i, ln in enumerate(ls, 1):
        s = ln.strip()
        if s.startswith("#"):
            continue
        # rm -rf 带变量
        if re.search(r"rm\s+-[rf]{1,2}\s+.*\$", ln) and not re.search(r'"\$\{?\w+\}?"', ln):
            add("rm -rf 未加引号的变量", p, i, ln, "变量为空或带空格 = 删错目录")
        # 未加引号的变量用在路径位置
        if re.search(r"(cp|mv|mkdir|cat|tar)\s+[^|]*[^\"']\$\{?[A-Za-z_]\w*\}?(\s|$)", ln) \
                and "$(" not in ln and not re.search(r'"\$', ln):
            add("路径变量未加引号", p, i, ln, "中文/空格路径会被拆成多个参数")


for f in JAVA:
    scan_java(f)
for f in PY:
    scan_py(f)
for f in SH:
    scan_sh(f)

by = {}
for h in hits:
    by.setdefault(h[0], []).append(h)
print("=== 深扫（%d 项，%d java / %d py / %d sh）===" % (len(hits), len(JAVA), len(PY), len(SH)))
for k, v in sorted(by.items(), key=lambda x: -len(x[1])):
    print("  %-24s %d" % (k, len(v)))
print()
want = sys.argv[1] if len(sys.argv) > 1 else None
for k, v in sorted(by.items(), key=lambda x: -len(x[1])):
    if want and want not in k:
        continue
    print("--- %s（%s）---" % (k, v[0][4]))
    for _, name, line, text, _why in v[:60]:
        print("  %s:%s  %s" % (name, line, text))
    print()
