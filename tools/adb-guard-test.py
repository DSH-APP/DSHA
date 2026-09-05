#!/usr/bin/env python3
"""ADB 通道的免确认判据断言（adb-shell.py 的 is_readonly_cmd）。

为什么必须有：这个判据决定「哪些设备命令不弹确认框直接执行」。它错一次的后果不是
报错，而是**确认机制静默失效** —— 用户实测过一次（`echo x > /sdcard/f` 免确认执行）。
纯字符串逻辑，不需要设备也不需要 adb，直接把函数从脚本里取出来跑。

用法：python3 tools/adb-guard-test.py
"""
import pathlib
import re
import sys

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/adb-shell.py"
src = SCRIPT.read_text(encoding="utf-8")

# 只取需要的常量与函数，不 import 整个脚本（它顶层要连 ADB 依赖）。
ns = {}
for name in ("READONLY_CMDS", "READONLY_SUB", "DUMPSYS_WRITE_WORDS"):
    m = re.search(r"^%s = .*?\)\n" % name, src, re.S | re.M)
    if not m:
        print("✗ 找不到常量 %s（脚本被改动过，测试需要跟上）" % name)
        sys.exit(1)
    exec(m.group(0), ns)
m = re.search(r"^def is_readonly_cmd\(cmd\):.*?\n(?=^def |\Z)", src, re.S | re.M)
if not m:
    print("✗ 找不到 is_readonly_cmd（脚本被改动过，测试需要跟上）")
    sys.exit(1)
exec(m.group(0), ns)
is_readonly_cmd = ns["is_readonly_cmd"]

# (命令, 期望免确认?, 这条断言守的是什么)
CASES = [
    ("id", True, "保活探活：每分钟一次，弹窗会让用户疯掉"),
    ("getprop ro.build.version.sdk", True, "读系统属性"),
    ("dumpsys battery", True, "纯读取的 dumpsys"),
    ("dumpsys deviceidle whitelist +com.dsh.client", False,
     "dumpsys 也能改状态：whitelist 是写操作，不能因为命令名叫 dumpsys 就免确认"),
    ("dumpsys battery set level 5", False, "battery set 会改电量上报"),
    ("cmd appops set com.x RUN_ANY_IN_BACKGROUND allow", False, "appops set 是提权类写操作"),
    ("pm grant com.x android.permission.WRITE_SECURE_SETTINGS", False, "授权必须用户知情"),
    ("pm list packages", True, "列已装应用是只读"),
    ("pm uninstall com.x", False, "卸载应用"),
    ("settings get system font_scale", True, "读设置"),
    ("settings put system font_scale 2", False, "写设置"),
    ("input tap 100 200", False, "点屏幕是操作设备"),
    ("am start -n com.a/.B", False, "拉起应用"),
    ("echo x > /sdcard/f", False, "重定向：这条曾被判成只读，确认机制因此形同虚设"),
    ("ls; rm -rf /sdcard", False, "分号串命令"),
    ("ls /sdcard | head", False, "管道"),
    ("cat /proc/version", True, "读文件"),
    ("find /sdcard -name x", True, "普通 find"),
    ("find /sdcard -delete", False, "find -delete 会改盘"),
    ("rm -rf /sdcard/x", False, "删除"),
    ("/system/bin/getprop", True, "绝对路径的只读命令"),
    ("", False, "空命令：拿不准一律要确认"),
]

bad = 0
for cmd, want, why in CASES:
    got = bool(is_readonly_cmd(cmd))
    if got != want:
        bad += 1
        print("✗ %s\n   命令 %r\n   期望免确认=%s 实际=%s" % (why, cmd, want, got))

# 脚本侧不能再有「等于某个常量就跳过确认」的分支：那种判据在容器里可被任意伪造。
if re.search(r"DSH_INTERNAL'?\)?\s*!=\s*'1'", src):
    print("✗ 免确认判据又回到脚本自己的环境变量比较（容器内可伪造）")
    bad += 1
if "internal_ticket" not in src:
    print("✗ 内部调用没有走 App 铸的一次性票")
    bad += 1

if bad:
    print("✗ ADB 免确认判据 %d 条不通过" % bad)
    sys.exit(1)
print("✓ ADB 免确认判据 %d 条断言通过" % len(CASES))
