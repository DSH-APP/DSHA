#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSHA 一键自检 & 修补。

把过去要人工一条条粘的终端命令固化成一次运行：环境、3090 桥、ADB 通道、
设备引导插件、write 补丁、会话健康、内置插件、备份、守卫、版本标记。
每项给 PASS / FAIL / SKIP，FAIL 带下一步怎么办。

绝大多数项是只读的，但有几项会动手修（能自动修好的就别让用户干等）：
补插件链接、修 pnpm 空壳、补回被摘掉的官方 bundle、去掉插件的模块级硬依赖。
每次改动前都留 .bak，并在报告里说清改了什么。

用法（期望版本由 App 传入，脚本自己不硬编码）：
  python3 selftest.py --script-ver 9 --guard-ver 10 --step6 4 --assets 7 --guide-ver 0.1.6
"""
import glob
import json
import os
import re
import subprocess
import sys
import time

# 允许用环境变量指向别的 dsh 目录：这样才能拿损坏的 profile 喂给自检验证它的判断，
# 否则任何测试都会读到真实环境的健康数据，「自检会不会误判」根本没法验。
SELFTEST_VERSION = "15"   # 报问题时对账用：能分清「提示没更新」和「走了别的分支」
DSH_HOME = os.environ.get("DSHA_DSH_HOME", "/root/.dsh")
SESSIONS = DSH_HOME + "/sessions"
GUIDE_DIR = "/root/dsha-device-shell-guide"
FS_LOCAL_CANDIDATES = (
    "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js",
    "/usr/local/lib/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js",
)
SESSION_PKG_CANDIDATES = (
    "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/"
    "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    "/usr/local/lib/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
)
ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"

# 每条 FAIL 都必须告诉用户下一步做什么。分散写在各处必然有漏 ——
# 这轮扫描发现 11 条 FAIL 只报告问题、不给出路。
#
# 措辞原则：修复本身是**自动**的（绝大多数在启动 Web 时完成），
# 所以说「点启动页的重启」而不是「重开 App 会自动修」——
# 后者用户既不知道要等多久，也无法确认到底做了没有。
NEXT_STEP = {
    "运行环境": "到「安装」页重跑步骤③（Node）和步骤⑤（dsh）",
    "3090 桥 token": "到「配置」页把「设备桥」关掉再打开",
    "ADB 只读白名单": "到「安装」页重跑步骤⑥（更新 ADB 脚本）",
    "设备引导插件版本": "点启动页的「重启」——启动时会重新注入新版实体",
    "l2s 悬空链": "到「配置」页点一次备份即会自动清理（清理是备份流程的一部分）",
    "Web 服务鉴权": "点启动页的「重启」——鉴权补丁是启动时打的",
    "会话完整性": "点启动页的「重启」——会话自愈会补 id，原文件留 .pre-fix 备份",
    "会话可读性": "点启动页的「重启」——自愈会修好或隔离损坏会话",
    "profile bundles": "点启动页的「重启」——启动前会自动补链接、摘掉解析不到的项",
    "备份内容": "到「配置」页重新备份一次",
    "设备引导插件未注册": "点启动页的「重启」——启动前会自动补链接并注册",
    "内置插件修复记录": "点启动页的「重启」触发一次完整校准",
    "profile 文件损坏": "到「配置」页恢复最近一次备份（这个自动修不了）",
    "Web 启动失败": "点启动页的「重启」——启动前会自动校准 profile",
    "上次备份失败": "到「配置」页重新点一次备份",
    "3090 桥启动": "到「配置」页把「设备桥」关掉再打开",
    "3090 桥响应": "到「配置」页重开「设备桥」；仍异常请贴到 GitHub issue",
    "3090 桥鉴权": "点启动页的「重启」",
    "容器环境": "到「安装」页重跑步骤①（解压环境）",
    "基础命令": "到「安装」页重跑步骤②（基础工具）",
    "App 层接口": "到「配置」页把「设备桥」关掉再打开",
    "ADB 保活": "打开手机「开发者选项 → 无线调试」，保活会自动重连",
    "电池优化白名单": "到「配置」页点「申请电池优化白名单」",
    "write 补丁": "点启动页的「重启」——补丁是启动时打的",
    "会话发布补丁": "点启动页的「重启」——补丁是启动时打的",
    "插件副本检查": "点启动页的「重启」——启动时会自动改成符号链接",
    "危险命令守卫": "点启动页的「重启」——守卫缺失时会自动补装",
    "设备引导插件": "点启动页的「重启」",
}

rows = []          # (状态, 标题, 说明)
counts = {"PASS": 0, "WARN": 0, "FAIL": 0, "SKIP": 0}


def add(state, title, detail=""):
    # 统一补「下一步」：FAIL 只报问题不给出路，用户只能干瞪眼或者乱试。
    # 放在这里而不是各处调用点 —— 分散写必然有漏（这轮扫出 11 条没写）。
    if state == "FAIL":
        step = NEXT_STEP.get(title)
        if step and step not in (detail or ""):
            detail = (detail or "") + "\n    → " + step
    rows.append((state, title, detail))
    counts[state] = counts.get(state, 0) + 1


def arg(name, default=""):
    key = "--" + name
    if key in sys.argv:
        i = sys.argv.index(key)
        if i + 1 < len(sys.argv):
            return sys.argv[i + 1]
    return default


SH_FAILED = "\x00SH_FAILED\x00"


def sh(cmd, timeout=20):
    """跑一条查询命令。

    执行本身失败时返回带 SH_FAILED 前缀的串，而不是空字符串 ——
    分不清「命令回答说没有」和「命令根本没跑起来」，是自动诊断误报的头号来源：
    容器没起来时，每一项都会因为拿到空输出而报 FAIL，把人吓一跳，
    而真相只有一条「环境没跑起来」。
    """
    try:
        p = subprocess.run(["bash", "-lc", cmd], capture_output=True, timeout=timeout)
        return (p.stdout or b"").decode("utf-8", "replace").strip()
    except subprocess.TimeoutExpired:
        return SH_FAILED + "执行超过 %d 秒" % timeout
    except Exception as e:
        return SH_FAILED + str(e)


def sh_failed(v):
    """命令是否根本没跑起来（区别于「跑了但回答是空」）"""
    return isinstance(v, str) and v.startswith(SH_FAILED)


def sh_why(v):
    return v[len(SH_FAILED):] if sh_failed(v) else ""


def read(path, limit=4096):
    try:
        with open(path, "rb") as f:
            return f.read(limit).decode("utf-8", "replace")
    except Exception:
        return ""


def env_stage():
    """环境走到哪一步了。

    很多检查项的前置条件是「跑过某个流程」：守卫脚本是启动 Web 时装的，
    write/会话补丁也是启动 Web 时打的，ADB 脚本是开 ADB 通道时注入的。
    在还没走到那一步的环境里报 FAIL，等于把「你还没用这个功能」说成
    「你的功能坏了」—— 这是最常见的一类误诊。
    """
    ran = os.path.exists("/root/dsh-web.log") or os.path.isdir(DSH_HOME + "/sessions")
    if ran:
        return "ran"
    installed = os.path.isdir(DSH_HOME + "/profiles") or os.path.exists("/usr/local/bin/dsh")
    return "installed" if installed else "fresh"


STAGE = None


def stage():
    global STAGE
    if STAGE is None:
        STAGE = env_stage()
    return STAGE


def preflight():
    """先确认「检查这件事本身能不能做」。

    容器起不来的时候，下面每一项的结论都不可信 —— 与其报一屏 FAIL，
    不如只说一句真话：环境没跑起来。
    """
    probe = sh("echo dsha_probe", timeout=15)
    if sh_failed(probe):
        add("FAIL", "容器环境",
            "连 bash 都执行不了（%s）—— 先到「安装」页确认环境已解压并安装完成。"
            "其余检查已跳过：在这种状态下它们的结论都不可信。" % sh_why(probe))
        return False
    if "dsha_probe" not in probe:
        add("FAIL", "容器环境",
            "bash 执行结果异常（输出：%s）—— 环境可能只装了一半，"
            "建议到「安装」页重跑一遍。其余检查已跳过。" % (probe[:60] or "空"))
        return False
    return True


# ===================== 1. 运行环境 =====================
def check_env():
    node = sh("command -v node >/dev/null && node -v || echo NONE")
    dsh_ver = sh("command -v dsh >/dev/null && dsh --version 2>/dev/null | head -1 || echo NONE")
    if sh_failed(node):
        add("SKIP", "运行环境", "查不了 node 版本（%s）—— 容器可能正忙或刚启动" % sh_why(node))
        return
    if node.startswith("v"):
        add("PASS", "运行环境", "node %s，dsh %s" % (node, dsh_ver if dsh_ver != "NONE" else "未装（可能走源码模式）"))
    else:
        add("FAIL", "运行环境", "rootfs 里没有 node —— 到分步安装页跑③安装 Node")


# ===================== 1.5 基础命令齐备 =====================
def check_tools():
    """缺命令是最难猜的一类故障：脚本挂在半路，报错却指向别处
    （比如 rootfs 没装 unzip，ADB 配对就卡在「pip 仍不可用」）。"""
    need = ["python3", "tar", "curl", "git", "xz"]
    opt = ["unzip", "zstd", "wget"]
    out = sh("for c in %s; do command -v $c >/dev/null && echo \"$c Y\" || echo \"$c N\"; done"
             % " ".join(need + opt))
    have = {}
    for line in out.split("\n"):
        parts = line.split()
        if len(parts) == 2:
            have[parts[0]] = parts[1] == "Y"
    if not have:
        add("SKIP", "基础命令", "探测命令没能执行 —— 容器可能没起来，跳过（避免误报缺失）")
        return
    missing = [c for c in need if not have.get(c, False)]
    miss_opt = [c for c in opt if not have.get(c, False)]
    if missing:
        add("FAIL", "基础命令", "缺 %s —— 到「安装」页重跑②基础工具" % "、".join(missing))
    else:
        note = ("；可选缺失 %s（脚本内有兜底，不影响使用）" % "、".join(miss_opt)) if miss_opt else ""
        add("PASS", "基础命令", "%d 项必需命令齐备%s" % (len(need), note))


# ===================== 2. 3090 桥 =====================
def check_bridge():
    token = read(DSH_HOME + "/.bridge_token").strip()
    if not token:
        if stage() != "ran":
            add("SKIP", "3090 桥 token", "还没启动过 Web —— token 由 App 在桥启动时生成")
        else:
            add("FAIL", "3090 桥 token",
                "缺 %s/.bridge_token —— 确认弹窗与 App 层接口都会失效；"
                "重开一次 App 让桥重新生成" % DSH_HOME)
        return
    import urllib.request
    import urllib.error
    import urllib.parse
    ok_hosts, bodies = [], []
    for host in ("127.0.0.1", "[::1]"):
        url = ("http://%s:3090/exec?cmd=%s&token=%s"
               % (host, urllib.parse.quote("echo dsha-selftest"), urllib.parse.quote(token)))
        try:
            with urllib.request.urlopen(url, timeout=8) as r:
                bodies.append(r.read().decode("utf-8", "replace"))
            ok_hosts.append(host)
        except Exception:
            pass
    if not ok_hosts:
        # 没开 ADB 设备通道时桥本来就不会启动，这种情况不算失败
        adb_on = arg("adb-on", "1") == "1"
        status = read(DSH_HOME + "/.bridge_status").strip()
        if status.startswith("fail"):
            # App 侧记下了绑定失败的真实原因（端口被占等），直接摊开说
            add("FAIL", "3090 桥启动", (status[5:].strip() or "绑定失败（原因未记录）")
            + "\n    到「配置」页把「设备桥」关掉再打开（该服务随 App 启动）")
        elif status == "stopped":
            add("SKIP" if not adb_on else "FAIL", "3090 桥",
                "桥已停止（设备桥服务没在跑）—— 重开 App，或在「配置」页勾选 ADB 设备通道并保存")
        else:
            add("FAIL" if adb_on else "SKIP", "3090 桥连通",
                "两个回环地址都连不上 —— App 需在运行中，且「配置」页勾过「启用 ADB 设备通道」并保存"
                if adb_on else "未启用 ADB 设备通道，桥不启动（正常）")
        return
    body = bodies[0]
    if '"result"' not in body:
        add("FAIL", "3090 桥响应", "响应不含 result 字段：%s\n    到「配置」页重开「设备桥」；若仍异常请把这段贴到 GitHub issue" % body[:80])
        return
    if "[UNAUTHORIZED]" in body:
        add("FAIL", "3090 桥鉴权", "token 不匹配 —— 删掉 .bridge_token 后重开 App 让它重签")
        return
    # 合法 JSON 检查：旧版本输出 {"result":YES} 不带引号，客户端判定会全线失效
    try:
        json.loads(body)
        json_ok = True
    except Exception:
        json_ok = False
    add("PASS" if json_ok else "FAIL", "3090 桥",
        "可达地址 %s；响应%s合法 JSON" % ("+".join(ok_hosts), "是" if json_ok else "不是"))
    # 顺带抽查 App 层接口（agent 能直接调的那批能力）
    try:
        url = "http://127.0.0.1:3090/app/device?token=" + urllib.parse.quote(token)
        with urllib.request.urlopen(url, timeout=8) as r:
            d = json.loads(r.read().decode("utf-8", "replace")).get("result", "")
        first = d.split("\n")[0] if d else ""
        add("PASS" if "model=" in d else "FAIL", "App 层接口",
            first if "model=" in d else "/app/device 返回异常：%s" % d[:80])
    except Exception as e:
        add("FAIL", "App 层接口", "/app/device 调不通：%s（旧版 App 没有这些端点）" % e)


# ===================== 3. ADB 通道脚本 =====================
def check_adb(want_ver):
    cur = read(DSH_HOME + "/script-version").strip()
    if not cur:
        add("SKIP", "ADB 脚本", "未注入（没开 ADB 设备通道就正常）")
        return
    if want_ver and cur != want_ver:
        add("WARN", "ADB 脚本版本",
            "rootfs=%s 期望=%s —— App 刚升级、脚本还没重注入时会这样，"
            "打开一次「配置」页的 ADB 开关即可（不影响已有功能）" % (cur, want_ver))
    else:
        add("PASS", "ADB 脚本版本", "v%s" % cur)
    # 只读白名单：必须只放行真正只读的命令
    path = DSH_HOME + "/adb-shell.py"
    if not os.path.isfile(path):
        add("SKIP", "ADB 只读白名单", "adb-shell.py 未注入")
        return
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location("dsha_adb", path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        fn = getattr(mod, "is_readonly_cmd", None)
        if fn is None:
            add("FAIL", "ADB 只读白名单", "还是老版判定（只看首个 token）—— 重注入 ADB 脚本")
            return
        cases = [("getprop ro.product.model", True), ("echo x > /sdcard/f", False),
                 ("pm uninstall com.x", False), ("settings put global x 1", False),
                 ("input tap 1 2", False), ("ls; rm -rf /sdcard/x", False)]
        bad = [c for c, want in cases if bool(fn(c)) != want]
        if bad:
            add("FAIL", "ADB 只读白名单", "判定异常：%s" % "、".join(bad))
        else:
            add("PASS", "ADB 只读白名单", "%d 条用例全部符合预期" % len(cases))
    except Exception as e:
        add("FAIL", "ADB 只读白名单", "加载 adb-shell.py 失败：%s" % e)


# ===================== 3.5 ADB 保活状态 =====================
def check_adb_keepalive(adb_on, battery_ok):
    """看门狗自己记的状态（App 侧每轮探测都会写 /root/.dsh/adb-status）。"""
    if not adb_on:
        add("SKIP", "ADB 保活", "未启用 ADB 设备通道")
        return
    raw = read(DSH_HOME + "/adb-status")
    if not raw.strip():
        add("SKIP", "ADB 保活", "还没有状态记录（App 需运行一会儿，或本次是旧版 App）")
        return
    st = {}
    for line in raw.split("\n"):
        if "=" in line:
            k, v = line.split("=", 1)
            st[k.strip()] = v.strip()
    state = st.get("state", "?")
    detail = st.get("detail", "")
    fails = st.get("failures", "0")
    last_ok = st.get("last_ok", "never")
    if state == "ok":
        add("PASS", "ADB 保活", "连接正常（最近成功 %s）" % last_ok)
    elif state in ("reconnecting", "installing"):
        add("SKIP", "ADB 保活", "正在自愈：%s（失败 %s 次，最近成功 %s）" % (detail, fails, last_ok))
    elif state == "need_pair":
        add("FAIL", "ADB 保活", "配对已失效 —— 到「配置」页点「ADB 无线配对」重配一次")
    elif state == "need_manual":
        add("FAIL", "ADB 保活",
            "自动重连失败 %s 次 —— 打开手机「开发者选项 → 无线调试」即会自动恢复" % fails)
    elif state == "network_lost":
        add("SKIP", "ADB 保活", "网络断开，等恢复后会自动重连")
    else:
        add("SKIP", "ADB 保活", "state=%s %s" % (state, detail))
    # 电池优化没放行的话，休眠中后台网络会被冻结，保活等于白做
    if battery_ok == "0":
        add("FAIL", "电池优化白名单",
            "未放行 —— 到「配置」页点「关闭电池优化」，否则休眠后 ADB 必掉且无法自动恢复")
    elif battery_ok == "1":
        add("PASS", "电池优化白名单", "已放行，休眠不会冻结后台网络")


# ===================== 4. 设备引导插件（会话损坏根因） =====================
def check_guide(want_ver):
    pkg = os.path.join(GUIDE_DIR, "package.json")
    idx = os.path.join(GUIDE_DIR, "lib", "index.js")
    if not os.path.isfile(pkg):
        add("SKIP", "设备引导插件", "未安装（没跑过步骤⑥）")
        return
    try:
        ver = json.loads(read(pkg, 65536)).get("version", "?")
    except Exception:
        ver = "?"
    body = read(idx, 200000)
    has_id = "randomUUID" in body
    # 光有目录不算装上：必须注册进 web profile 的 bundles，并且 node_modules 里有链接，
    # 否则 dsh 根本不会加载它，agent 也就看不到设备操作提示词
    nm = os.path.join(DSH_HOME, "profiles", "web", "node_modules", "dsh-device-shell-guide")
    disabled = os.path.exists(nm + ".disabled")
    # readlink 优先：proot 下 islink 恒 False，而 exists 对**悬空**符号链接同样是 False，
    # 两者一起用仍会把「链接在、但指向的实体丢了」误判成「没有链接」，指错修复方向
    try:
        os.readlink(nm)
        linked = True
    except OSError:
        linked = os.path.exists(nm)
    registered = False
    prof = os.path.join(DSH_HOME, "profiles", "web", "package.json")
    profile_exists = os.path.isfile(prof)
    dep_ok = False
    parse_err = None
    if profile_exists:
        try:
            pkg = json.loads(read(prof, 200000))
            bundles = (((pkg.get("dsh") or {}).get("profile") or {}).get("bundles")) or []
            registered = "dsh-device-shell-guide" in bundles
            # dependencies 缺声明时 dsh reconcile 会把它从 bundles 摘掉，
            # 所以两者都要在才算真注册
            dep_ok = str((pkg.get("dependencies") or {}).get(
                "dsh-device-shell-guide", "")).startswith("link:")
        except Exception as e:
            registered = False
            parse_err = str(e)
    if parse_err:
        add("FAIL", "profile 文件损坏",
            "profiles/web/package.json 无法解析（%s）。\n"
            "    这不是插件的问题，重开 App 或重跑步骤⑥都修不了 —— 解析不了就没法改。\n"
            "    可选：① 用「配置」页恢复最近一次备份；"
            "② 删掉该文件让 dsh 重新生成（会丢插件注册，之后打开「插件」页会自动补回）"
            % parse_err)
    elif disabled:
        add("SKIP", "设备引导插件", "已被用户手动禁用（市场页可重新启用）")
    elif not has_id:
        add("FAIL", "设备引导插件", "注入的消息没补 message.id（会把会话写坏）—— 重跑步骤⑥")
    elif want_ver and ver != want_ver:
        add("FAIL", "设备引导插件版本", "当前 %s 期望 %s —— 重开 App 会自动重注入" % (ver, want_ver))
    elif not profile_exists:
        add("SKIP", "设备引导插件", "v%s 实体已就位，但还没有 web profile —— 启动一次 WebUI 后会自动注册" % ver)
    elif not registered or not linked or not dep_ok:
        if not registered and not dep_ok:
            why = "bundles 与 dependencies 都没有它（dsh reconcile 摘掉了）"
        elif not dep_ok:
            why = "dependencies 缺 link: 声明（下次 reconcile 还会被摘）"
        elif not registered:
            why = "bundles 里没有它"
        else:
            why = "node_modules 缺链接"
        # 「重开 App 会自动补齐」不能无条件说 —— 存在禁用标记时补回逻辑会尊重用户
        # 而永远跳过，用户重开 App、重跑步骤⑥都不会有反应（有人就这样卡住过）。
        mark = "/root/.dsh/profiles/web/node_modules/dsh-device-shell-guide.disabled"
        if os.path.exists(mark):
            if os.path.isfile(mark) and os.path.getsize(mark) == 0:
                hint = ("发现一个**空的**禁用标记（%s）：那是上次禁用时实体已丢失留下的残留。"
                        "新版本启动时会自动清掉并补回；想立刻解决就手动删掉它再重开 App" % mark)
            else:
                hint = ("它是被禁用的（存在 %s）—— 请到「插件」页把开关打开。"
                        "重开 App 与重跑步骤⑥都不会补回用户主动禁用的插件" % mark)
        else:
            hint = "重开 App 会自动补齐（补不上就重跑步骤⑥）"
        add("FAIL", "设备引导插件未注册", "v%s 实体在，但 %s —— %s\n    修复是自动的：启动 Web 时会补链接并注册。现在就想修好 → 点启动页的「重启」" % (ver, why, hint))
    else:
        add("PASS", "设备引导插件", "v%s，已注册进 profile，注入消息带 id" % ver)


# ============== 4.5 插件模块级硬依赖（Web 起不来的头号原因）==============
# 目标：内置的 dsh-device-shell-guide 在所有可能的落点。
# linkPlugin 是 Java 递归复制而不是符号链接，所以同一份代码会有多个副本，
# 改一个不够 —— 曾经就是因为只改了源目录、profile 里那份还是旧的而白折腾。
GUIDE_TARGET_PATTERNS = (
    GUIDE_DIR + "/lib/index.js",
    DSH_HOME + "/profiles/*/node_modules/dsh-device-shell-guide/lib/index.js",
    DSH_HOME + "/profiles/*/node_modules/.pnpm/*/node_modules/dsh-device-shell-guide/lib/index.js",
)
# 模块级 inject 数组里含 systemPrompt（行首声明，不含 apply 里的 ctx.inject）
HARD_INJECT_RE = re.compile(
    r"^[ \t]*export\s+const\s+inject\s*=\s*\[[^\]]*['\"]systemPrompt['\"][^\]]*\][ \t]*;?[ \t]*\r?$",
    re.M)
HARD_INJECT_NOTE = (
    "// [DSHA 自检修补] 这里原来有一行模块级 inject 声明（硬依赖 systemPrompt）。\n"
    "// 模块级 inject 是**硬依赖**：极简模式不提供 systemPrompt 服务，插件就永远\n"
    "// pending，dsh 判定 entry 未激活 → 整棵 plugin tree 加载失败、Web 起不来。\n"
    "// 已改成 apply() 里的 ctx.inject 作用域注入（不阻塞激活），与官方 dsh-web-app 一致。\n"
    "// 原件在同目录 .dsha-bak。")
SECTION_RE = re.compile(
    r"ctx\.systemPrompt\.section\(\{\s*name:\s*['\"]dsh:device-shell-guide['\"],\s*"
    r"order:\s*150,\s*text:\s*PROMPT,?\s*\}\)", re.S)
SECTION_NEW = ("ctx.inject(['systemPrompt'], (pc) => {\n"
               "    pc.systemPrompt.section({ name: 'dsh:device-shell-guide', "
               "order: 150, text: PROMPT })\n  })")


def heal_hard_inject():
    """检出并修掉内置引导插件的模块级硬依赖。

    用户侧症状是这一行：
        dsh: plugin tree failed to load: dsh: 1 entry did not activate
        dsh-device-shell-guide: pending (waiting for service: systemPrompt)
    Web 完全起不来，清数据、重装、清环境都无效（rootfs 数据卸载后保留）。
    1.1.7 的内置插件（0.1.9）就是模块级 inject 的写法，已有三个用户中招。

    **两处必须一起改**：只删掉 inject 声明而不把 section 调用包进 ctx.inject，
    运行时读未声明的服务会直接抛 `cannot get property "systemPrompt" without
    inject` —— 从「插件不激活」变成「插件一跑就炸」，比原来更糟。所以任一处没
    匹配上就整体回滚，只报告不动手。
    """
    files = []
    for pat in GUIDE_TARGET_PATTERNS:
        files.extend(sorted(glob.glob(pat)))
    if not files:
        add("SKIP", "插件硬依赖检查", "找不到设备引导插件的任何副本（还没跑过步骤⑥）")
        return

    healed, already, failed = [], [], []
    for path in files:
        try:
            body = read(path, 400000)
            if not body:
                continue
            if not HARD_INJECT_RE.search(body):
                already.append(path)
                continue
            patched = HARD_INJECT_RE.sub(HARD_INJECT_NOTE, body)
            patched, n = SECTION_RE.subn(SECTION_NEW, patched)
            if n == 0:
                # 插件代码和我们认识的不一样了 —— 不敢只改一半
                failed.append(path)
                continue
            bak = path + ".dsha-bak"
            if not os.path.exists(bak):        # 只留第一份原件，别被二次修补覆盖
                with open(bak, "w", encoding="utf-8") as f:
                    f.write(body)
            with open(path, "w", encoding="utf-8") as f:
                f.write(patched)
            healed.append(path)
        except Exception as e:
            failed.append("%s（%r）" % (path, e))

    if healed:
        add("FAIL", "插件硬依赖已修补",
            "改好了 %d 处（原件存 .dsha-bak）：\n    %s\n"
            "    这就是 `1 entry did not activate` 让 Web 起不来的原因。\n"
            "    **现在回启动页点「重启」**，Web 就能起来了。\n"
            "    根治在 1.1.7-fix 之后的版本（内置插件 0.1.10 起改成作用域注入）"
            % (len(healed), "\n    ".join(healed)))
    elif failed:
        add("FAIL", "插件硬依赖修不了",
            "发现硬依赖但代码结构与预期不符，没敢只改一半（只删声明会让插件运行时直接抛错）：\n"
            "    %s\n"
            "    应急：把它从 profile 摘掉先让 Web 起来 ——\n"
            "    python3 - <<'EOF'\n"
            "import json\n"
            "p='%s/profiles/web/package.json'; d=json.load(open(p))\n"
            "b=d['dsh']['profile'].get('bundles') or []\n"
            "d['dsh']['profile']['bundles']=[x for x in b if x!='dsh-device-shell-guide']\n"
            "json.dump(d,open(p,'w'),indent=2); print('已摘掉，回启动页点重启')\n"
            "EOF"
            % ("\n    ".join(failed), DSH_HOME))
    else:
        add("PASS", "插件硬依赖检查",
            "%d 个副本都是作用域注入（不会阻塞 plugin tree 加载）" % len(already))


# ===================== 5. write 发布补丁 =====================
def check_repair_log():
    """App 侧内置插件修复的落盘记录。

    没有这项的话，用户只能看到「插件还是不见」，而「App 压根没跑到那段代码」、
    「跑了但条件不满足」、「写进去又被 dsh 覆盖」三种情况在界面上长得一模一样，
    对应的修法却完全不同。
    """
    path = os.path.join(DSH_HOME, "repair-builtin.log")
    if not os.path.isfile(path):
        add("SKIP", "内置插件修复记录",
            "还没有记录 —— 打开一次「插件」页会触发修复并留下记录")
        return
    txt = read(path, 20000).strip()
    blocks = [b for b in txt.split("== ") if b.strip()]
    if not blocks:
        add("SKIP", "内置插件修复记录", "记录是空的")
        return
    last = blocks[-1].strip()
    head = last.split("\n")[0]
    if "仍未注册" in last:
        add("FAIL", "内置插件修复记录",
            "最近一次修复没成功（%s）—— 写入条件不满足，或写完被覆盖。详情见 %s"
            % (head, path))
    elif "修好" in last:
        add("PASS", "内置插件修复记录", "最近一次修复成功（%s）" % head)
    else:
        add("SKIP", "内置插件修复记录", "最近一次：%s（本来就都在）" % head)


def check_write_patch():
    target = next((p for p in FS_LOCAL_CANDIDATES if os.path.isfile(p)), None)
    if target is None:
        add("SKIP", "write 补丁", "找不到 dsh-fs-local（dsh 可能装在别处）")
        return
    if "DSHA_L2S_FIX" in read(target, 400000):
        add("PASS", "write 补丁", "已生效（新建文件走 rename，不会变悬空链接）")
    else:
        if stage() != "ran":
            add("SKIP", "write 补丁", "还没启动过 Web —— 补丁是启动时打的，启动一次即会自动补上")
            return
        add("FAIL", "write 补丁", "未打 —— 重开一次 App（启动自愈会补），或到启动页点一次启动")
    # 会话日志发布补丁：没打的话每轮对话结束就 ENOENT（会话文件发布后即悬空）
    starget = next((p for p in SESSION_PKG_CANDIDATES if os.path.isfile(p)), None)
    if starget is None:
        add("SKIP", "会话发布补丁", "找不到 dsh-session-persistence-jsonl")
    else:
        _ssrc = read(starget, 400000)
        # 标记版本必须与 fs-write-patch.sh 保持同步！补丁那边升到 FIX4 时，
        # 这里还在查 FIX2 —— 于是形成双向死锁：脚本看到 FIX4 报
        # SESSION_PATCH_ALREADY 直接跳过，自检看不到 FIX2 报「未打」，
        # 提示用户「重开 App 会自动补」，重开一百次也没用（本来就是好的）。
        # 用户以为功能坏了，实际只是自检说错话。
        if "DSHA_L2S_FIX4" in _ssrc:
            add("PASS", "会话发布补丁", "已生效（会话日志走 rename 发布，不会悬空）")
        elif "DSHA_L2S_FIX2" in _ssrc or "DSHA_L2S_FIX3" in _ssrc:
            add("WARN", "会话发布补丁",
                "打的是老版本补丁 —— 下次启动 Web 时会自动升级到最新版")
        elif stage() != "ran":
            add("SKIP", "会话发布补丁", "还没启动过 Web —— 补丁是启动时打的，启动一次即会自动补上")
            return
        else:
            add("FAIL", "会话发布补丁",
                "未打 —— 会话写完即失效（ENOENT ... session.jsonl.zstd）；"
                "到启动页点一次「重启」即会自动补上（补丁是启动 Web 时打的）")
    mark = read("/root/.dsha-hardlink").strip()
    _rt = arg("runtime") or "proot"
    if mark.startswith("ok"):
        add("PASS", "硬链接支持", "文件系统支持真实硬链接，未启用 link2symlink")
    elif mark:
        # 这项的结论只对 proot 有直接意义：proroot 的硬链接模拟是另一套机制
        # （anchor + symlink group），「不支持」并不代表它会产生 proot 那种 .l2s 链
        tailmsg = "不支持（Android 私有目录常态）：%s" % mark[:90]
        if _rt == "proroot":
            tailmsg += "\n    当前运行时是 proroot，它用自己的 anchor+symlink group 机制，" \
                       "不产生 proot 式的 .l2s 链"
        add("SKIP", "硬链接支持", tailmsg)


# ===================== 5.5 proot l2s 残留 =====================
def check_l2s():
    """proot 的 .l2s 链会让 tar 失败 —— 备份 100% 挂在这里。

    机制：Android 私有目录禁真硬链接，proot 用 --link2symlink 把 link() 模拟成
    「目标 → .l2s.<名>.<hash>.tmp0001 → ….0001」，同时劫持了 stat/lstat（伪造
    st_nlink）。tar 必须 lstat 判断类型，于是报 ELOOP / EPERM。
    写入侧已由 fs-write-patch.sh 治本（一律 rename），这里查存量。
    注意：不能用 os.path.islink 判断 —— 它内部走 lstat，对这些链恒返回 False。
    """
    if not os.path.isdir(DSH_HOME):
        add("SKIP", "l2s 残留", "还没有 .dsh 目录")
        return
    total, dangling = 0, 0
    for root, dirs, files in os.walk(DSH_HOME):
        if "corrupt-backup" in root:
            continue
        for n in files + dirs:
            p = os.path.join(root, n)
            try:
                tgt = os.readlink(p)
            except OSError:
                if ".l2s." in n:
                    total += 1
                continue
            if ".l2s." in tgt or ".l2s." in n:
                total += 1
                try:
                    with open(p, "rb") as f:
                        f.read(1)
                except OSError:
                    dangling += 1
    if dangling:
        add("FAIL", "l2s 悬空链",
            "%d 个悬空 / 共 %d 个 .l2s 条目 —— 备份会因 tar 报错失败；"
            "备份一次即会自动摊平并把悬空的挪进 corrupt-backup" % (dangling, total))
    elif total:
        add("SKIP", "l2s 残留", "%d 个 .l2s 条目（内容完好，备份时会自动摊平成真实文件）" % total)
    else:
        add("PASS", "l2s 残留", "无（写入侧已改为 rename 发布）")


# ===================== 5.6 dsh Web 服务鉴权 =====================
PENDING_RE = re.compile(r"([\w@/.-]+):\s*pending \(waiting for service")


def scan_boot_blockers(log_text):
    """从 dsh-web.log 里认出「entry 未激活」这类致命启动失败。

    真实事故（1.1.7）：内置插件 dsh-device-shell-guide 用模块级
    `export const inject = ['systemPrompt']` 声明硬依赖，而极简模式
    不提供这个服务 —— 插件永远 pending，dsh 判定 entry 未激活，
    **整个 plugin tree 加载失败，Web 完全起不来**。
    日志里写得明明白白，而自检以前只会说「可能只是还没启动」。
    """
    if "did not activate" not in log_text:
        return None
    names = []
    for m in PENDING_RE.finditer(log_text):
        n = m.group(1)
        if n not in names:
            names.append(n)
    return names or ["（日志里没给出插件名）"]


def check_web_auth():
    """dsh 的 Web 服务（3080）必须使用上游 BrowserAuth。"""
    import glob
    cands = glob.glob("/usr/local/lib/node_modules/@deepseek-ai/**/dsh-host-webserver/lib/index.js",
                      recursive=True)
    if not cands:
        cands = glob.glob("/root/**/dsh-host-webserver/lib/index.js", recursive=True)
    if not cands:
        add("SKIP", "Web 服务鉴权", "没找到 webserver（dsh 未安装？）")
        return
    try:
        with open(cands[0], encoding="utf-8", errors="replace") as f:
            src = f.read()
    except OSError as e:
        add("SKIP", "Web 服务鉴权", "读不到 webserver：%s" % e)
        return
    if "dsh-auth-" in src and "authorizeIndex" in src:
        add("PASS", "Web 服务鉴权", "已启用上游 BrowserAuth（loopback URL 交换 HttpOnly Cookie）")
    else:
        add("FAIL", "Web 服务鉴权",
            "未发现上游 BrowserAuth —— 请重新解压匹配的离线 dsh 运行时")


# ===================== 5.7 @deepseek-ai 双副本 =====================
def check_dsh_dupes():
    """profile 里的 @deepseek-ai/* 物理副本 —— 生态里最致命的坑。

    症状极具误导性：**所有**工具调用都失败（连内置 filesystem 都崩），报
    「Cannot read properties of undefined (reading 'prepare')」，看着像 dsh 坏了。
    真因是 dsh-agent-loop 用模块级 Symbol 找调度器，而 profile 里跑过
    pnpm install（dsh plugin add 就是转发给 pnpm）会把 @deepseek-ai/dsh-tools
    物理复制一份，两份副本 → 两个 Symbol → 查不到调度器。
    两份版本相同、内容一致，按版本 dedupe 看不出来。

    判据：profile 自己的 node_modules/@deepseek-ai/ 下有真实目录（非符号链接）。
    用 readlink 判断 —— proot 下 os.path.islink 会因 lstat 被劫持而一律返回 False。
    """
    root = "/root/.dsh/profiles"
    if not os.path.isdir(root):
        add("SKIP", "插件副本检查", "还没有 profile 目录")
        return
    critical = ("dsh-tools", "cordis", "dsh-agent-loop", "dsh-session", "dsh-skill")
    hits, crit = [], []
    for prof in sorted(os.listdir(root)):
        if prof == "node_modules":
            continue  # 扁平兜底目录里全是正常符号链接
        scope = os.path.join(root, prof, "node_modules", "@deepseek-ai")
        if not os.path.isdir(scope):
            continue
        for name in sorted(os.listdir(scope)):
            if name.startswith("."):
                continue
            q = os.path.join(scope, name)
            try:
                os.readlink(q)
                continue          # 符号链接 = 正常
            except OSError:
                pass
            if os.path.isdir(q):
                hits.append("%s/%s" % (prof, name))
                if name in critical:
                    crit.append(name)
    if crit:
        add("FAIL", "插件副本检查",
            "profile 内有 %d 个 @deepseek-ai 物理副本（%s）—— 这会让**所有**工具调用失败"
            "（Cannot read properties of undefined reading 'prepare'）；"
            "在「插件」页重装/卸载一次会自动修，或让 AI 跑 check-dsh-dupes.py --fix"
            % (len(hits), "、".join(crit[:3])))
    elif hits:
        add("WARN", "插件副本检查",
            "有 %d 个非关键 @deepseek-ai 副本（%s）—— 暂不影响工具调用，但建议清掉"
            % (len(hits), "、".join(hits[:3])))
    else:
        add("PASS", "插件副本检查", "没有 @deepseek-ai 物理副本（工具调用不会因此崩）")


# ===================== 6. 会话健康（只统计，不修） =====================
def check_sessions():
    if not os.path.isdir(SESSIONS):
        add("SKIP", "会话文件", "还没有会话目录")
        return
    names = ("session.jsonl", "session.jsonl.zstd")
    files, strays = [], 0
    for root, dirs, fs in os.walk(SESSIONS):
        for f in fs:
            if f in names:
                files.append(os.path.join(root, f))
            elif f.startswith("session.jsonl"):
                strays += 1  # .corrupt-* / .pre-fix-* 之类残留
    if strays:
        add("SKIP", "会话目录残留", "%d 个历史备份文件留在 sessions 里（老版本 heal 产物，不影响使用）" % strays)
    if not files:
        add("SKIP", "会话文件", "目录为空")
        return
    try:
        import zstandard as zstd
    except ImportError:
        add("SKIP", "会话可读性", "容器内没装 zstandard，跳过解码抽查（%d 个会话）" % len(files))
        return
    import io
    bad, missing_id = [], 0
    for p in sorted(files, key=os.path.getmtime, reverse=True)[:5]:  # 抽查最近 5 个
        raw = open(p, "rb").read()
        try:
            if raw[:4] == ZSTD_MAGIC:
                data = zstd.ZstdDecompressor().stream_reader(io.BytesIO(raw)).read()
            else:
                data = raw
        except Exception as e:
            bad.append("%s(%s)" % (os.path.basename(os.path.dirname(p)), e))
            continue
        for line in data.decode("utf-8", "replace").split("\n"):
            line = line.strip()
            if not line:
                continue
            try:
                ev = json.loads(line)
            except Exception:
                continue
            t = ev.get("type", "")
            if t in ("user/message", "assistant/message", "tool/result"):
                d = ev.get("data")
                m = d if t == "user/message" else (d.get("message") if isinstance(d, dict) else None)
                if isinstance(m, dict) and not m.get("id"):
                    missing_id += 1
    if bad:
        add("FAIL", "会话可读性", "解码失败：%s\n    修复是自动的：启动 Web 时会修好或隔离损坏的会话，原文件留 .pre-fix 备份。现在就想修 → 点启动页的「重启」" % "、".join(bad[:3]))
    elif missing_id:
        add("FAIL", "会话完整性", "抽查发现 %d 条消息缺 id（历史遗留）—— 重开 App 让自愈修复" % missing_id)
    else:
        add("PASS", "会话健康", "共 %d 个会话，抽查最近 %d 个：可解码、无缺 id"
            % (len(files), min(5, len(files))))


# ===================== 7. 内置插件可解析 =====================
def check_sanitize_log():
    """启动前 profile 校准的结果。

    校准会做两件用户看不见的事：给解析不到的内置插件补 node_modules 链接，
    以及把仍然解析不到的项从 bundles 摘掉（否则 dsh 启动直接崩）。
    摘掉意味着某个插件不再生效 —— 这必须让用户知道，否则他只会觉得
    「装了插件却没反应」，永远查不到是启动时被摘了。
    """
    path = os.path.join(DSH_HOME, "profile-sanitize.log")
    if not os.path.isfile(path):
        add("SKIP", "profile 校准记录", "还没有记录（bundles 一直是健康的）")
        return
    txt = read(path, 20000).strip()
    blocks = [b for b in txt.split("== ") if b.strip()]
    if not blocks:
        add("SKIP", "profile 校准记录", "记录是空的")
        return
    last = blocks[-1].strip()
    dropped = [ln for ln in last.split("\n") if "已摘除" in ln]
    linked = [ln for ln in last.split("\n") if "补链接" in ln]
    # 记录是历史的：插件后来被补回来的话，这条「已摘除」就过时了 ——
    # 用当前 profile 的真实状态覆盖它，否则用户看到的是已经不成立的警告。
    if dropped:
        still_missing = []
        try:
            _pkg = json.loads(read(os.path.join(DSH_HOME, "profiles", "web", "package.json"), 200000))
            _b = (((_pkg.get("dsh") or {}).get("profile") or {}).get("bundles")) or []
            for _n in ("dsh-device-shell-guide", "dsh-task-notifier",
                       "dsh-status-overlay", "dsh-web-mobile"):
                if _n not in _b:
                    still_missing.append(_n)
        except Exception:
            still_missing = ["?"]
        if not still_missing:
            add("PASS", "profile 校准记录",
                "历史上摘过 bundle，但现在三个内置插件都已重新注册，记录已过时")
            return
    if dropped:
        add("WARN", "profile 校准记录",
            "最近一次启动摘掉了解析不到的 bundle：%s\n"
            "    被摘的插件不会生效。到「市场」重装即可恢复；"
            "内置插件会在下次启动自动补链接" % dropped[0].split(":", 1)[-1].strip())
    elif linked:
        add("PASS", "profile 校准记录",
            "最近一次启动补好了链接：%s" % linked[0].split(":", 1)[-1].strip())
    else:
        add("SKIP", "profile 校准记录", "最近一次没有需要处理的项")


def check_official_bundles():
    """web profile 里必须有官方核心 bundle，否则 Web 一定起不来。

    真实事故（1.1.7）：sanitizeProfileBundles 的 bundleResolvable 没查
    源码模式的 node_modules、也没查 .pnpm store，于是把
    @deepseek-ai/dsh-base 和 @deepseek-ai/dsh-web-app 判成「解析不到」
    并摘掉。那个方法的本意是「宁可少个插件也要让 Web 起来」，
    结果摘掉内核，Web 100% 起不来 —— 而且清数据、重装、清除环境
    全都救不回来，因为每次启动又摘一遍。

    这一项独立于 bundles 可解析性检查：那个只管「列表里的能不能加载」，
    管不了「该有的是不是还在」。
    """
    pf = os.path.join(DSH_HOME, "profiles", "web", "package.json")
    if not os.path.isfile(pf):
        add("SKIP", "官方核心插件", "profile 还没生成")
        return
    try:
        with open(pf, encoding="utf-8") as f:
            d = json.load(f)
    except Exception as e:
        add("FAIL", "官方核心插件",
            "profile package.json 无法解析：%s\n"
            "    下一步：从备份恢复，或删掉 %s 让 dsh 重建" % (e, pf))
        return
    bundles = d.get("dsh", {}).get("profile", {}).get("bundles", []) or []
    official = [b for b in bundles if str(b).startswith("@deepseek-ai/")]
    if official:
        add("PASS", "官方核心插件", "%d 个在列：%s" % (len(official), ", ".join(official)))
    else:
        add("FAIL", "官方核心插件",
            "**bundles 里没有任何 @deepseek-ai/* —— Web 起不来的直接原因**\n"
            "    当前 bundles：%s\n"
            "    成因：旧版本的 profile 校准把官方核心误判为「解析不到」并摘掉了\n"
            "    下一步：升级到修复版后启动一次会自动补回；\n"
            "      想立刻修，在终端跑：\n"
            "      python3 - <<'EOF'\n"
            "      import json;p='%s';d=json.load(open(p))\n"
            "      b=d['dsh']['profile'].setdefault('bundles',[])\n"
            "      for x in ['@deepseek-ai/dsh-web-app','@deepseek-ai/dsh-base']:\n"
            "          if x not in b: b.insert(0,x)\n"
            "      json.dump(d,open(p,'w'),indent=2);print('已补回',b)\n"
            "      EOF" % (bundles, pf))


def check_pnpm_shells():
    """pnpm 装一半留下的空壳插件（议题 #36 Bug 3）。

    pnpm 装 GitHub 来源插件时，tarball 落地成功但 prepare 脚本失败，
    它会把完整目录 rename 成 .ignored_<name>，原位只留一个含
    _pnpmPlaceholder 的 package.json 壳。dsh 加载到的是壳 ——
    插件形同不存在，而用户从任何界面都看不出来，只能人工翻 node_modules。
    这一项就是把这种静默失败摊开，并顺手修掉。
    """
    script = "/root/dsha-heal-pnpm-shells.py"
    if not os.path.isfile(script):
        add("SKIP", "插件空壳检查", "修复脚本未随包下发")
        return
    try:
        r = subprocess.run(["python3", script], capture_output=True, text=True, timeout=90)
        out = (r.stdout or "").strip()
    except Exception as e:
        add("SKIP", "插件空壳检查", "没跑起来：%s" % e)
        return
    if "没有需要修的" in out:
        add("PASS", "插件空壳检查", "没有 pnpm 半装残留")
    elif "已修复" in out:
        detail = "\n    ".join(out.splitlines()[:12])
        add("WARN", "插件空壳检查",
            "发现并修好了 pnpm 半装残留（完整文件被 pnpm 改名成 .ignored_ 藏起来了）\n"
            "    %s\n"
            "    下一步：点启动页「重启」让插件真正加载" % detail)
    else:
        add("SKIP", "插件空壳检查", out.splitlines()[0] if out else "无输出")


def check_runtime_env():
    """报告运行环境：是否在鸿蒙安卓容器（卓易通）里、内存与进程配额。

    加这一项是因为用户报「总卡在安装第五步」——那一步是源码构建
    （pnpm install + node-gyp 编译），最吃内存和 fork。而卓易通环境有
    三个硬约束：鸿蒙沙箱限制 fork 子进程、容器自身占 ~8GB 内存、
    鸿蒙 6 对卓易通后台杀得极快。这些从「卡住」的现象完全反推不出来，
    必须让自检把环境摊开给用户看。
    """
    marks = []
    try:
        with open("/proc/self/cgroup") as f:
            cg = f.read().lower()
        for kw in ("isulad", "/lxc/", "zhuoyi"):
            if kw in cg:
                marks.append(kw)
    except OSError:
        pass
    try:
        with open("/proc/version") as f:
            ver = f.read().strip()
    except OSError:
        ver = "?"
    if "ohos" in ver.lower() or "harmony" in ver.lower():
        marks.append("harmony-kernel")

    # 可用内存与最大进程数：anco 里这两项才是真正的瓶颈
    mem_avail = mem_total = 0
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemAvailable:"):
                    mem_avail = int(line.split()[1]) // 1024
                elif line.startswith("MemTotal:"):
                    mem_total = int(line.split()[1]) // 1024
    except OSError:
        pass
    try:
        nproc = subprocess.run(["bash", "-lc", "ulimit -u"],
                               capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception:
        nproc = "?"

    detail = "内存 %dMB 可用 / %dMB 总，进程上限 %s\n    内核 %s" % (
        mem_avail, mem_total, nproc, ver[:80])
    if marks:
        add("WARN", "运行环境",
            "检测到鸿蒙安卓兼容容器（卓易通 / anco，标识：%s）\n"
            "    %s\n"
            "    已知限制与建议：\n"
            "      · 鸿蒙沙箱限制 fork 子进程 —— 安装已自动降为串行（并发 1）\n"
            "      · 系统对卓易通后台杀得很快 —— 安装第五步请保持 App 前台、屏幕常亮\n"
            "      · 容器自身占用约 8GB 内存 —— 建议关掉其它大应用再装"
            % (", ".join(marks), detail))
    elif mem_avail and mem_avail < 900:
        add("WARN", "运行环境",
            "可用内存偏低，源码构建（第五步）可能被系统杀掉\n    " + detail)
    else:
        add("PASS", "运行环境", detail)


def check_public_data():
    """会话数据是否已迁到公开目录（决定「卸载重装会不会丢」）。

    这一项必须存在：迁移在缺少「所有文件访问」权限时会**静默跳过** ——
    用户以为数据安全了，其实还在 App 私有目录里，一卸载全没。
    静默失败是这个项目最该避免的形态。
    """
    items = ("sessions", "storages", "attachments", "settings.yaml")
    linked, plain, missing = [], [], []
    for name in items:
        path = os.path.join(DSH_HOME, name)
        # proot 下 os.path.islink 不可信，用 readlink 判断
        try:
            tgt = os.readlink(path)
            linked.append("%s → %s" % (name, tgt))
        except OSError:
            if os.path.exists(path):
                plain.append(name)
            else:
                missing.append(name)
    if linked and not plain:
        add("PASS", "对话数据存放位置",
            "已迁到公开目录，卸载重装不会丢：\n    " + "\n    ".join(linked))
    elif linked and plain:
        add("WARN", "对话数据存放位置",
            "部分迁移：已迁 %d 项，仍在私有目录 %s\n"
            "    下一步：到「设置」页点「运行自检」或重启 Web，会自动补迁"
            % (len(linked), ", ".join(plain)))
    elif plain:
        add("FAIL", "对话数据存放位置",
            "仍在 App 私有目录（%s）——**卸载或换机重装会全部丢失**\n"
            "    原因：缺少「所有文件访问」权限，写不进 内部存储/Documents/dshdata\n"
            "    下一步：系统设置 → 应用 → DSHA → 权限 → 所有文件访问，打开后回到 App"
            % ", ".join(plain))
    else:
        add("SKIP", "对话数据存放位置", "还没有会话数据")


def check_activity():
    """最近的自动动作 —— 这一项存在的唯一理由是「让用户看见 App 做了什么」。

    这个项目反复出问题的地方不是功能坏了，而是 App 悄悄做了事（摘插件、
    降级运行时、跳过备份清单、修复失败）却只写进用户拿不到的 logcat，
    界面上只剩一个说不清的结果。
    """
    path = os.path.join(DSH_HOME, "dsha-activity.log")
    if not os.path.isfile(path):
        add("SKIP", "最近的自动动作", "还没有记录")
        return
    lines = [ln for ln in read(path, 40000).strip().split("\n") if ln.strip()]
    if not lines:
        add("SKIP", "最近的自动动作", "记录是空的")
        return
    tail = lines[-4:]
    add("PASS", "最近的自动动作",
        "最近 %d 条（完整见 %s）：\n    %s" % (len(tail), path, "\n    ".join(tail)))


def check_runtime():
    """当前容器运行时。**选的**和**实际生效的**可能不同 ——
    proroot 缺文件或连续失败会被自动降回 proot，用户界面上看不出来，
    而「为什么没变快」这个问题只有这里能回答。
    """
    actual = arg("runtime") or "proot"
    pref = arg("runtime-pref") or "proot"
    names = {"proot": "proot（内置，稳定）", "proroot": "proroot（实验，零 ptrace）", "bxroot": "bxroot（实验，LD_PRELOAD 零 ptrace）"}
    if actual == pref:
        add("PASS", "容器运行时", "%s" % names.get(actual, actual))
    else:
        add("WARN", "容器运行时",
            "选的是 %s，实际用的是 %s —— 说明它不可用或连续失败已被自动切回。\n"
            "    原因见 logcat；想再试就到「配置」页重新勾选"
            % (names.get(pref, pref), names.get(actual, actual)))


def check_web_boot():
    """dsh 最近一次启动有没有致命错误 —— 这是整份自检里最该先看的一项。

    前面十几项可以全绿，而 dsh 根本起不来。用户看到「16 通过 / 0 失败」
    然后发现 Web 白屏，第一反应是自检没用 —— 而且他会拿着这份报告去查
    完全错误的方向（实际发生过：bundles 报「全部可解析」，dsh 那边正抛
    cannot resolve profile bundle）。

    判据分两层：日志里的致命错误（说明启动过但崩了）+ 端口是否在听（硬证据）。
    """
    log = "/root/dsh-web.log"
    if not os.path.isfile(log):
        add("SKIP", "Web 启动状态", "还没有启动日志（没启动过 Web）")
        return
    _p = arg("web-port")
    port = int(_p) if (_p and _p.isdigit()) else 3080
    tail = read(log, 400000)[-12000:]
    fatal = (
        ("cannot resolve profile bundle",
         "profile 里注册了解析不到的 bundle —— 见上面「profile bundles」那项，"
         "打开一次「插件」页会自动补链接"),
        ("--expose-internals",
         "某个插件需要 node --expose-internals —— 启动时的 profile 自愈会定向禁用它"),
        ("EADDRINUSE",
         "端口被占用 —— 点「重启」会先深杀残留进程"),
        ("Cannot find module",
         "依赖缺失 —— 到「安装」页重跑步骤⑤，或在「插件」页重装出问题的插件"),
        ("ERR_MODULE_NOT_FOUND",
         "模块解析失败 —— 同上，多为插件装了一半"),
        ("Invalid or unexpected token",
         "某个 js 文件损坏（下载中断多见）—— 重跑步骤⑤覆盖安装"),
    )
    # 先看「entry 未激活」：比下面的模式更具体，能点出是哪个插件把 Web 拖死的。
    # 一个插件声明了环境里不存在的服务 → 它永远 pending → dsh 判定 entry 未激活
    # → **整棵 plugin tree 加载失败**，其它插件全都无辜受害。
    blockers = scan_boot_blockers(tail)
    if blockers:
        add("FAIL", "Web 启动状态",
            "**插件未激活，整个 plugin tree 加载失败 → Web 起不来**\n"
            "    卡住的插件：%s\n"
            "    成因：插件声明了当前环境不提供的服务（模块级 inject 是硬依赖，\n"
            "      服务缺失就永远 pending）。极简模式没有 systemPrompt 是最常见的一种。\n"
            "    下一步：\n"
            "      · 内置插件 → 升级 App 到修复版（已改为运行时作用域注入）\n"
            "      · 第三方插件 → 到「插件」页禁用它，或让作者改用 ctx.inject(deps, cb)\n"
            "      · 想立刻起来：在终端把它从 bundles 里摘掉\n"
            "        python3 - <<'EOF'\n"
            "        import json;p='%s/profiles/web/package.json';d=json.load(open(p))\n"
            "        b=d['dsh']['profile']['bundles'];bad=%r\n"
            "        d['dsh']['profile']['bundles']=[x for x in b if x not in bad]\n"
            "        json.dump(d,open(p,'w'),indent=2);print('剩余',d['dsh']['profile']['bundles'])\n"
            "        EOF"
            % (", ".join(blockers), DSH_HOME, blockers))
        return

    hit = None
    for pat, why in fatal:
        if pat in tail:
            hit = (pat, why)
            break
    port_up = False
    try:
        import socket
        for host in ("127.0.0.1", "::1"):
            try:
                fam = socket.AF_INET6 if ":" in host else socket.AF_INET
                sk = socket.socket(fam, socket.SOCK_STREAM)
                sk.settimeout(1.5)
                if sk.connect_ex((host, port)) == 0:
                    port_up = True
                sk.close()
            except Exception:
                pass
            if port_up:
                break
    except Exception:
        pass
    if port_up and hit:
        add("WARN", "Web 启动状态",
            "端口 %d 在听（现在能用），但日志里有过 %s —— %s" % (port, hit[0], hit[1]))
    elif port_up:
        add("PASS", "Web 启动状态", "端口 %d 在听，日志里没有致命错误" % port)
    elif hit:
        add("FAIL", "Web 启动失败",
            "%s\n    %s\n    大多数情况点启动页的「重启」即可 —— "
            "启动前会自动校准 profile、补链接、摘掉解析不到的项" % (hit[0], hit[1]))
    else:
        add("SKIP", "Web 启动状态",
            "端口 %d 没在听，日志里也没有致命错误 —— 可能只是还没启动" % port)


def check_bundles():
    prof = os.path.join(DSH_HOME, "profiles", "web", "package.json")
    if not os.path.isfile(prof):
        add("SKIP", "profile bundles", "还没有 web profile")
        return
    try:
        pkg = json.loads(read(prof, 200000))
    except Exception as e:
        add("FAIL", "profile bundles", "package.json 解析失败：%s" % e)
        return
    deps = pkg.get("dependencies") or {}
    bundles = (((pkg.get("dsh") or {}).get("profile") or {}).get("bundles")) or []
    nm = os.path.join(DSH_HOME, "profiles", "web", "node_modules")
    globals_ = ("/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules",
                "/usr/local/lib/node_modules")
    missing = []
    for b in bundles:
        # 判据必须和 dsh 的 resolveBundleDir 一致：它只从 profile 的 node_modules
        # 或 dsh 安装树里找，**完全不看 dependencies 里的 link: 声明** ——
        # 那个声明是给 pnpm 建链接用的，不是运行时解析依据。
        #
        # 旧实现把「link: 指向的目录里有 package.json」当成可解析，于是
        # 「bundles + dependencies 都齐、但 node_modules 没链接」被判成 PASS，
        # 而 dsh 启动时直接抛 cannot resolve profile bundle —— 自检说一切正常，
        # Web 却起不来。用户拿着一份全绿的自检报告完全没法排查。
        if os.path.isfile(os.path.join(nm, b, "package.json")):
            continue
        if any(os.path.isfile(os.path.join(g, b, "package.json")) for g in globals_):
            continue
        spec = deps.get(b, "")
        real = spec[5:] if isinstance(spec, str) and spec.startswith(("link:", "file:")) else None
        if real and os.path.isfile(os.path.join(real, "package.json")):
            missing.append("%s（实体在 %s，但 node_modules 缺链接 —— dsh 只认 node_modules）"
                           % (b, real))
        else:
            missing.append(b)
    if missing:
        add("FAIL", "profile bundles",
            "%d/%d 个解析不到：%s\n"
            "    这会让 dsh 启动直接崩溃（cannot resolve profile bundle）。\n"
            "    打开一次「插件」页会自动补链接；或到「市场」重装该插件"
            % (len(missing), len(bundles), "、".join(missing)))
    else:
        official = [b for b in bundles if str(b).startswith("@deepseek-ai/")]
        detail = "%d 个全部可解析：%s" % (len(bundles), "、".join(str(b) for b in bundles))
        if not official:
            add("FAIL", "profile bundles",
                detail + "\n"
                "    **一个 @deepseek-ai/* 都没有** —— 官方核心不在列表里，\n"
                "    dsh 的服务（systemPrompt 等）没人提供，插件会永远 pending\n"
                "    并把整个 Web 启动拖死。见下面「官方核心插件」那项的修复命令")
        else:
            add("PASS", "profile bundles", detail)


# ===================== 8. 备份可用性 =====================
def check_backup():
    d = "/sdcard/Download/DSHA"
    if not os.path.isdir(d):
        add("SKIP", "备份", "还没备份过（数据与备份页可手动创建）")
        return
    packs = [os.path.join(d, f) for f in os.listdir(d)
             if f.lower().endswith((".tar.gz", ".tgz")) and f.lower().startswith("dsha-")]
    if not packs:
        add("SKIP", "备份", "Download/DSHA 里没有备份包")
        return
    err = read(DSH_HOME + "/backup-last-error").strip()
    if err:
        add("FAIL", "上次备份失败", err[:160]
            + "\n    到「配置」页重新点一次备份；若仍失败请把这段贴到 GitHub issue")
    latest = max(packs, key=os.path.getmtime)
    listing = sh("tar -tzf %s 2>/dev/null | head -80" % latest.replace(" ", "\\ "), timeout=60)
    has_dsh = ".dsh/" in listing
    has_manifest = "backup-manifest.json" in listing
    age = (time.time() - os.path.getmtime(latest)) / 86400.0
    detail = "%s（%.1f 天前，%.0fMB）" % (os.path.basename(latest), age, os.path.getsize(latest) / 1048576.0)
    if not has_dsh:
        add("FAIL", "备份内容", detail + " 里没有 .dsh —— 重新备份一次")
    elif not has_manifest:
        add("WARN", "备份格式", detail + " 是老格式（无清单）：能恢复，但跨设备可能缺插件；重新备份即升级到 v2")
    else:
        add("PASS", "备份", detail + " 含 .dsh 与清单，可跨设备恢复")


# ===================== 9. 危险命令守卫 + 版本标记 =====================
def check_guard(want_guard, want_step6, want_assets):
    v = read("/root/dsh-bin/.version").strip()
    if not os.path.isdir("/root/dsh-bin"):
        if stage() != "ran":
            add("SKIP", "危险命令守卫", "还没启动过 Web —— 守卫脚本在启动时安装")
        else:
            add("FAIL", "危险命令守卫",
                "/root/dsh-bin 不存在 —— 危险命令拦不住了；点一次「重启 Web」会自动重装")
    elif want_guard and v != want_guard:
        add("WARN", "守卫版本",
            ("守卫已装但没写版本标记（很早的版本）—— 启动一次 Web 会更新到 %s" % want_guard)
            if not v else
            ("当前 %s 期望 %s —— 启动一次 Web 即会自动更新；"
             "旧版守卫仍在拦截，只是少了新版的改进" % (v, want_guard)))
    else:
        add("PASS", "危险命令守卫", "dsh-bin v%s，确认脚本 %s" % (v, "在" if os.path.isfile("/root/dsh-confirm.sh") else "缺失"))
    s6 = read(DSH_HOME + "/step6.version").strip()
    av = read(DSH_HOME + "/builtin-assets.version").strip()
    if want_step6 and want_assets and (s6 != want_step6 or av != want_assets):
        adb_on = arg("adb-on", "1") == "1"
        if not adb_on:
            add("SKIP", "步骤⑥版本标记", "没启用 ADB 设备通道，不需要跑步骤⑥")
            return
        if stage() != "ran":
            add("SKIP", "步骤⑥版本标记", "还没启动过 Web —— 步骤⑥的内容会在启动时补")
            return
        add("WARN", "步骤⑥版本标记", "rootfs=%s|%s 期望=%s|%s —— 重开 App 会自动重跑⑥，或手动点⑥"
            % (s6 or "无", av or "无", want_step6, want_assets))
    elif s6:
        add("PASS", "步骤⑥版本标记", "%s|%s" % (s6, av))


def main():
    print("=== DSHA 自检 & 修补 v%s" % SELFTEST_VERSION + " · %s ===" % time.strftime("%Y-%m-%d %H:%M:%S"))
    # 先确认「检查本身做得了」。做不了就只报这一条，别拿一屏 FAIL 去吓人
    env_ok = preflight()
    for fn, args in (
        (check_env, ()),
        (check_tools, ()),
        (check_bridge, ()),
        (check_adb, (arg("script-ver"),)),
        (check_adb_keepalive, (arg("adb-on", "1") == "1", arg("battery-opt", ""))),
        (check_guide, (arg("guide-ver"),)),
        (heal_hard_inject, ()),
        (check_repair_log, ()),
        (check_write_patch, ()),
        (check_l2s, ()),
        (check_web_auth, ()),
        (check_dsh_dupes, ()),
        (check_sessions, ()),
        (check_official_bundles, ()),
        (check_pnpm_shells, ()),
        (check_runtime_env, ()),
        (check_public_data, ()),
        (check_activity, ()),
        (check_runtime, ()),
        (check_sanitize_log, ()),
        (check_web_boot, ()),
        (check_bundles, ()),
        (check_backup, ()),
        (check_guard, (arg("guard-ver"), arg("step6"), arg("assets"))),
    ):
        if not env_ok:
            continue
        try:
            fn(*args)
        except Exception as e:
            # 检查代码自己抛异常，说明这一项「查不了」，而不是被查的东西有问题
            add("WARN", "自检项 " + fn.__name__, "这一项没跑完，结论不可用：%r" % e)

    icon = {"PASS": "✅", "WARN": "⚠️", "FAIL": "❌", "SKIP": "➖"}
    for state, title, detail in rows:
        print("%s %s" % (icon[state], title))
        if detail:
            print("    %s" % detail)
    print("")
    print("=== 汇总：%d 通过 / %d 待处理 / %d 失败 / %d 跳过 ==="
          % (counts["PASS"], counts["WARN"], counts["FAIL"], counts["SKIP"]))
    if counts["FAIL"] == 0:
        print("全部关键项通过。")
    else:
        print("❌ 是需要处理的问题，⚠️ 是可以稍后处理的过渡状态（多为版本待更新），➖ 是当前场景用不到所以没查。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
