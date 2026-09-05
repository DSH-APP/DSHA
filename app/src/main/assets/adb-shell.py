#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# DSHA_ADB_SCRIPT_VERSION=13
"""
DSHA 设备 shell 工具（ADB 无线通道，免 Shizuku）。
用法：
  adb-shell.py <command...>         # 在设备上以 shell(uid=2000) 身份执行
  /root/dsh-bin/adb-shell "命令"     # 包装命令（PATH 内）
连接端口优先级：--port > /root/.dsh/adbkeys/connect_port > 5555
输出：stdout + "\n[EXIT=n]"（与 3090 桥保持一致的格式）
"""
import sys
import os
import time

KEYDIR = '/root/.dsh/adbkeys'
KEY = KEYDIR + '/adbkey'
KEYPUB = KEY + '.pub'

# 免确认的只读命令（见 is_readonly_cmd）。只放「无论参数怎么给都不改设备状态」的命令。
# dumpsys 刻意不在这里：`dumpsys deviceidle whitelist +pkg`、`dumpsys battery set` 都会改
# 系统状态，而白名单按命令名匹配 —— 放进来等于给这些写操作免掉确认。
READONLY_CMDS = frozenset((
    'getprop', 'logcat', 'id', 'ps', 'df', 'free', 'uptime', 'date',
    'whoami', 'getevent', 'ls', 'stat', 'wc', 'head', 'tail', 'grep', 'cat',
    'md5sum', 'sha1sum', 'printenv', 'env', 'pwd', 'which', 'true', 'echo'))
# 这些命令名本身可写，只有满足附加条件时才算只读。
READONLY_SUB = {
    'pm': frozenset(('list', 'path', 'dump')),
    'settings': frozenset(('get', 'list')),
    'cmd': frozenset(),
    'am': frozenset(),
    'wm': frozenset(),
    'input': frozenset(),
    'svc': frozenset(),
}
# dumpsys 的写动词：出现其中任何一个就必须走确认。
DUMPSYS_WRITE_WORDS = frozenset((
    'set', 'unset', 'whitelist', 'enable', 'disable', 'reset', 'force',
    'add', 'remove', 'put', 'start', 'stop', 'clear', 'send', 'trim'))


def main():
    args = sys.argv[1:]
    port = 0
    use_su = False
    if args and args[0] == '--port':
        if len(args) >= 2:
            port = int(args[1])
        args = args[2:]
    # --su：以 root 身份执行（需手机已 root；未 root 会提示）
    # 安全：必须用户已在 App「配置」页勾选「允许 root shell」才会生成
    # /root/.dsh/allow-root-shell 标记；未授权一律拒绝（防止 agent 擅自提权）
    if args and args[0] == '--su':
        if not os.path.exists('/root/.dsh/allow-root-shell'):
            print('ROOT_NOT_ALLOWED: 未授权 root shell')
            print('请在 App「配置」页勾选「允许 root shell」并保存后重试')
            print('[EXIT=1]')
            sys.exit(1)
        use_su = True
        args = args[1:]
    if not args:
        args = ['id']
    cmd = ' '.join(args)
    # su 模式：shell 下用 su -c 提升到 root
    if use_su:
        cmd = "su -c '" + cmd.replace("'", "'\\''") + "'"

    if not (os.path.exists(KEY) and os.path.exists(KEYPUB)):
        print('NO_KEY: 请先在 App「工作区 → ADB 无线配对」完成配对')
        print('[EXIT=1]')
        sys.exit(1)

    if not port:
        try:
            port = int(open(KEYDIR + '/connect_port').read().strip())
        except Exception:
            port = 0
    # 注意：端口过期（手机重启/重开无线调试后随机变化）时不能只靠这里的兜底 ——
    # 真正的重试在下面 connect_with_retry()：先用记录端口，失败再 mDNS 重发现。

    try:
        from adb_shell_wifi.adb_device import AdbDeviceTls  # Android 11+ Wi-Fi 调试必须用 TLS 传输
        from adb_shell_wifi.auth.sign_pythonrsa import PythonRSASigner
    except ImportError as e:
        print('DEPS_MISSING: 缺少 adb_shell_wifi 库（%s）' % e)
        print('请重开 App 配置页的 ADB 开关，或重跑安装步骤⑥（会自动安装依赖）')
        print('[EXIT=1]')
        sys.exit(1)

    # ===== 执行前报备确认（用户要求：用 shell 必须先说明理由，用户确认后才执行）=====
    # 通过 3090 桥 /confirm 弹窗（App 前台）或通知（后台）让用户确认；
    # 命令里 # 后的注释作为「理由」展示。未确认/超时默认拒绝。
    # 只读命令（getprop/dumpsys 等以只读开头）直接放行，减少打扰。
    #
    # **DSH_INTERNAL 不再是「等于 1 就跳过」**：那是个任何容器内进程都能自设的环境变量，
    # 也就是说 agent 只要写 `DSH_INTERNAL=1 python3 adb-shell.py ...` 就能免掉整个确认关卡。
    # 现在它携带 App 现场铸出的一次性票，判据完全在 App 侧（唯一可信的一端）：
    # 票有效 → 桥自己回 YES 不弹窗；票是伪造的 → 照常弹窗给用户看。
    confirm_reason = cmd.split('#', 1)[1].strip() if '#' in cmd else ''
    if not is_readonly_cmd(cmd):
        ok = request_confirm(cmd, confirm_reason, os.environ.get('DSH_INTERNAL', ''))
        if not ok:
            print('USER_REJECTED: 未获授权，命令未执行')
            print('  命令：%s' % cmd)
            print('  可能原因：用户点了拒绝 / 60 秒内未确认 / 3090 确认桥不可达')
            print('  处理：在 App「配置」页启用 ADB 设备通道，确认桥运行后重试')
            print('[EXIT=1]')
            sys.exit(1)

    try:
        out = connect_with_retry(AdbDeviceTls, PythonRSASigner, cmd, port)
    except ConnectFail as e:
        print('CONNECT_FAIL: %s' % e)
        print('请确认手机「开发者选项→无线调试」已开启，且已配对（App 工作区→ADB 无线配对）')
        print('[EXIT=1]')
        sys.exit(1)

    if isinstance(out, (bytes, bytearray)):  # 库版本差异：可能返回 bytes
        out = out.decode('utf-8', 'replace')
    out = out if isinstance(out, str) else str(out)
    sys.stdout.write(out if out.endswith('\n') else out + '\n')
    print('[EXIT=0]')


class ConnectFail(Exception):
    """所有候选端口都连不上（携带尝试记录，便于用户排查）"""


def run_on_port(device_cls, signer_cls, cmd, port):
    """在指定端口上连接并执行命令，返回输出。任何失败抛异常。"""
    signer = signer_cls(open(KEYPUB, 'rb').read().strip(), open(KEY, 'rb').read())
    priv_pem = open(KEY, 'rb').read()  # PKCS#8 PEM，作为 TLS 客户端私钥（0.5.0 库：传给 connect()）
    dev = device_cls('127.0.0.1', port)
    dev.connect(rsa_keys=[signer], auth_timeout_s=20, tls_priv_pem=priv_pem)
    try:
        # 超时兜底：logcat（不带 -d）这类命令会一直挂住，旧实现无超时会永久卡死
        try:
            return dev.shell(cmd, read_timeout_s=30, timeout_s=180)
        except TypeError:
            return dev.shell(cmd)  # 老版本库没有超时参数
    finally:
        try:
            dev.close()
        except Exception:
            pass


def load_port_history():
    """最近成功过的连接端口（新→旧）。"""
    try:
        with open(KEYDIR + '/connect_port_history') as f:
            out = []
            for tok in f.read().split():
                if tok.strip().isdigit():
                    v = int(tok)
                    if 1 <= v <= 65535 and v not in out:
                        out.append(v)
            return out[:5]
    except Exception:
        return []


def remember_port(port):
    """记住成功过的端口。无线调试端口重启后会变，但常在少数几个值之间轮换；
    mDNS 不可用时（WiFi 刚连上、组播被路由器拦、省电模式限制多播）这份历史就是救命的。
    """
    try:
        if not port or not (1 <= int(port) <= 65535):
            return
        hist = [p for p in load_port_history() if p != int(port)]
        hist.insert(0, int(port))
        with open(KEYDIR + '/connect_port_history', 'w') as f:
            f.write('\n'.join(str(p) for p in hist[:5]) + '\n')
    except Exception:
        pass


def connect_with_retry(device_cls, signer_cls, cmd, port):
    """连接执行 + 端口自愈。

    无线调试的连接端口是随机的，手机重启/重开无线调试后就会变 —— 旧实现只在
    connect_port 文件「缺失」时才做 mDNS 发现，文件存在但端口过期时直接连旧端口、
    失败即报 CONNECT_FAIL，mDNS 自愈成了死代码（用户实测：改错端口后永久失败）。
    现在：记录端口失败 → mDNS 重发现（并回写）→ 再试；仍不行才报错。
    """
    tried = []
    last = None
    for p in [port] if port else []:
        tried.append(p)
        try:
            out = run_on_port(device_cls, signer_cls, cmd, p)
            remember_port(p)
            return out
        except Exception as e:
            last = e
    # 端口过期/未知：mDNS 重发现（discover_conn_port 内部会回写 connect_port）
    fresh = discover_conn_port()
    if fresh and fresh not in tried:
        tried.append(fresh)
        try:
            out = run_on_port(device_cls, signer_cls, cmd, fresh)
            remember_port(fresh)
            return out
        except Exception as e:
            last = e
    # mDNS 也没戏（组播被拦/省电模式限制多播）→ 翻历史端口，往往还在用同一个
    for p in load_port_history():
        if p in tried:
            continue
        tried.append(p)
        try:
            out = run_on_port(device_cls, signer_cls, cmd, p)
            remember_port(p)
            try:
                with open(KEYDIR + '/connect_port', 'w') as f:
                    f.write(str(p))
            except Exception:
                pass
            return out
        except Exception as e:
            last = e
    # 最后兜底：老式 `adb tcpip 5555` 固定端口（无线调试随机端口场景几乎不命中）
    if 5555 not in tried:
        tried.append(5555)
        try:
            out = run_on_port(device_cls, signer_cls, cmd, 5555)
            remember_port(5555)
            return out
        except Exception as e:
            last = e
    raise ConnectFail('%s (%s) 已尝试端口=%s' % (last, type(last).__name__, tried))


def is_readonly_cmd(cmd):
    """判定命令是否「确定只读」（免确认）。安全优先：拿不准一律 False。

    旧实现只看第一个 token，白名单里还混进了 echo/cat/pm/settings/input/wm ——
    `echo x > /sdcard/f`、`pm uninstall`、`settings put`、`ls; rm -rf /sdcard`
    全部免确认直接执行，确认机制形同虚设（用户实测：弹窗没出现，文件已写入）。
    现在：出现任何 shell 元字符（重定向/管道/分号/后台/命令替换）即需确认；
    可写命令按子命令白名单收口。
    """
    s = cmd.strip()
    if not s:
        return False
    for m in ('>', '<', '|', ';', '&', '$(', '`', '\n', '\r'):
        if m in s:
            return False
    parts = s.split()
    name = parts[0].rsplit('/', 1)[-1]  # 容许 /system/bin/getprop 这种绝对路径
    if name == 'find':  # find -delete / -exec 会改盘
        return not any(a.startswith('-delete') or a.startswith('-exec')
                       or a.startswith('-fprint') or a.startswith('-fls') for a in parts[1:])
    if name == 'dumpsys':
        # dumpsys 大多数子命令只读，但 deviceidle whitelist / battery set 这类会改状态。
        return not any(a.lstrip('-+').lower() in DUMPSYS_WRITE_WORDS for a in parts[1:])
    if name in READONLY_SUB:
        return len(parts) > 1 and parts[1] in READONLY_SUB[name]
    return name in READONLY_CMDS


def request_confirm(cmd, reason='', internal_ticket=''):
    """请求用户确认执行设备命令（3090 桥 /confirm，App 弹窗/通知）。
    返回 True=允许。失败/超时默认拒绝（安全优先）。

    internal_ticket 是 App 现场铸出的一次性票：桥认得它就自动放行、不打扰用户
    （保活探活每分钟一次，不能弹窗）。伪造的票在桥侧不成立，会照常弹给用户。"""
    import urllib.request
    import urllib.parse
    import urllib.error
    token = ''
    try:
        with open('/root/.dsh/.bridge_token') as f:
            token = f.read().strip()
    except Exception:
        pass
    # 命令 + 理由一起发给确认弹窗
    display = cmd if not reason else cmd + '\n\n[理由] ' + reason
    # 这里**不带 force=1**：带上就是「无条件弹确认」，于是经 ADB 通道执行的每一条
    # 命令都要用户点一次 —— 连 `am start` 打开应用、`monkey`、`ls` 都拦。用户实测
    # 「危险 shell 拦截会拦所有操作」就是这么来的。
    #
    # 后果比麻烦更严重：DangerShellGuard 自己的注释里写过这条教训 —— 每次都弹，
    # 用户几次之后就学会无脑点允许，那时真正危险的命令也拦不住了。
    #
    # 桥端 DangerShellGuard.isDangerous() 本来就有一段专门处理这条路径的判据：
    # 认出 `adb … shell/exec-out/exec-in` 之后对**后面那截真实命令**判危。
    # force=1 一直把它短路掉，那段代码从写下来就没生效过。
    q = ('/confirm?cmd=' + urllib.parse.quote(display)
         + ('&internal=' + urllib.parse.quote(internal_ticket) if internal_ticket else '')
         + '&token=' + urllib.parse.quote(token))
    # 桥的监听地址取决于 App 版本：新版绑 127.0.0.1（并附加 ::1），
    # 老版 getLoopbackAddress() 在 Android 上只绑 [::1] → IPv4 连不上。两个都试。
    for host in ('127.0.0.1', '[::1]'):
        try:
            with urllib.request.urlopen('http://' + host + ':3090' + q, timeout=65) as r:
                body = r.read().decode('utf-8', 'ignore')
            # 兼容两种响应体：{"result":"YES"}（合法 JSON）与旧版 {"result":YES}
            return '"YES"' in body or '":YES' in body
        except TimeoutError:
            return False  # 桥收到了请求但用户没在 60s 内确认 → 拒绝
        except urllib.error.URLError:
            continue      # 该地址连不上 → 换另一个地址族
        except Exception:
            return False
    return False

def discover_conn_port(timeout_s=5):
    """mDNS 自动发现无线调试连接端口（_adb-tls-connect）。找到返回端口，失败返回 0。"""
    try:
        from zeroconf import Zeroconf, ServiceBrowser, ServiceListener
        found = {}
        class L(ServiceListener):
            def add_service(self, zc, type_, name):
                info = zc.get_service_info(type_, name)
                if info:
                    found[info.port] = name
            def update_service(self, zc, type_, name):
                pass
            def remove_service(self, zc, type_, name):
                pass
        zc = Zeroconf()
        ServiceBrowser(zc, '_adb-tls-connect._tcp.local.', L())
        time.sleep(timeout_s)
        zc.close()
        if found:
            p = sorted(found)[0]
            # 顺手回写 connect_port，下次秒连
            try:
                with open(KEYDIR + '/connect_port', 'w') as f:
                    f.write(str(p))
            except Exception:
                pass
            return p
    except Exception:
        pass
    return 0


if __name__ == '__main__':
    main()
