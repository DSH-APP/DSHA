#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# DSHA_ADB_SCRIPT_VERSION=20
"""设备 shell：原生白名单判定、有限时连接、发送后不重放、真实远端退出码。

用法：adb-shell.py [--host 本机IP] [--port 端口] [--timeout 秒] [--su] 命令
失败码：124=连接失败/超时，125=已发送但执行结果未知，126=策略拦截。
命令正常结束时，本地退出码与 [EXIT=n] 都使用远端退出码。
"""
import ipaddress
import json
import os
import re
import shlex
import socket
import sys
import threading
import time
import uuid
import importlib.util

_policy_spec = importlib.util.spec_from_file_location('dsha_device_policy', os.path.join(os.path.dirname(__file__), 'device-shell-policy.py'))
policy = importlib.util.module_from_spec(_policy_spec)
_policy_spec.loader.exec_module(policy)

KEYDIR = '/root/.dsh/adbkeys'
KEY = KEYDIR + '/adbkey'
KEYPUB = KEY + '.pub'
TRANSPORT_TIMEOUT = 4.0
CONNECT_TIMEOUT = 25.0
COMMAND_TIMEOUT = 90.0

class ConnectFail(Exception):
    """命令尚未发送，允许重新发现地址。"""


class ExecutionUnknown(Exception):
    """命令可能已执行，禁止自动重放。"""



class ShellResult:
    def __init__(self, output, exit_code):
        self.output = output
        self.exit_code = exit_code


def atomic_text(path, text):
    tmp = path + '.' + uuid.uuid4().hex + '.tmp'
    try:
        with open(tmp, 'w', encoding='utf-8') as f:
            f.write(text)
        os.replace(tmp, path)
    finally:
        try:
            os.unlink(tmp)
        except OSError:
            pass


def local_ips():
    """只枚举本机接口，不向公网地址探路。"""
    found = []
    try:
        import subprocess
        rows = json.loads(subprocess.check_output(
            ['ip', '-j', '-4', 'address', 'show'], timeout=3,
            stderr=subprocess.DEVNULL).decode())
        found = [a['local'] for r in rows for a in r.get('addr_info', [])
                 if a.get('family') == 'inet']
    except Exception:
        try:
            import fcntl
            import struct
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                for _, name in socket.if_nameindex():
                    try:
                        data = fcntl.ioctl(sock.fileno(), 0x8915,
                            struct.pack('256s', name.encode()[:15]))
                        found.append(socket.inet_ntoa(data[20:24]))
                    except OSError:
                        pass
        except Exception:
            pass
    return list(dict.fromkeys(a for a in found if a and not a.startswith('127.')))


def candidate_hosts(preferred=''):
    hosts = local_ips()
    if preferred:
        ipaddress.ip_address(preferred)
        hosts.insert(0, preferred)
    hosts.append('127.0.0.1')
    return list(dict.fromkeys(hosts))


def discover_endpoints(service_type, timeout_s=4, allowed_hosts=None):
    """保留主机/端口关联，只接受本机；所有退出路径都停止 mDNS。"""
    allowed = set(candidate_hosts() if allowed_hosts is None else allowed_hosts)
    found = {}
    lock = threading.Lock()
    zc = browser = None
    until = time.monotonic() + timeout_s
    try:
        from zeroconf import Zeroconf, ServiceBrowser, ServiceListener
        class Listener(ServiceListener):
            def add_service(self, client, type_, name):
                remaining = until - time.monotonic()
                if remaining <= 0:
                    return
                info = client.get_service_info(type_, name, timeout=max(1, int(min(remaining, 1) * 1000)))
                if info is None or not 1 <= info.port <= 65535:
                    return
                addresses = [a for a in info.parsed_addresses() if a in allowed]
                if addresses:
                    with lock:
                        found[name] = [(a, info.port) for a in addresses]
            def update_service(self, client, type_, name):
                self.remove_service(client, type_, name)
                self.add_service(client, type_, name)
            def remove_service(self, client, type_, name):
                with lock:
                    found.pop(name, None)
        zc = Zeroconf()
        browser = ServiceBrowser(zc, service_type, Listener())
        time.sleep(max(0, until - time.monotonic()))
        with lock:
            return list(dict.fromkeys(ep for name in sorted(found) for ep in found[name]))
    except Exception:
        return []
    finally:
        if browser is not None:
            try:
                browser.cancel()
            except Exception:
                pass
        if zc is not None:
            try:
                zc.close()
            except Exception:
                pass


def load_port_history():
    try:
        with open(KEYDIR + '/connect_port_history') as f:
            values = f.read(1024).split()
        return list(dict.fromkeys(int(v) for v in values if v.isdigit() and 1 <= int(v) <= 65535))[:5]
    except Exception:
        return []


def remember_endpoint(host, port):
    # 只有握手/命令已验证的地址才写入，发现失败不能污染可用端口。
    try:
        atomic_text(KEYDIR + '/connect_host', host)
        atomic_text(KEYDIR + '/connect_port', str(port))
        history = [port] + [p for p in load_port_history() if p != port]
        atomic_text(KEYDIR + '/connect_port_history', '\n'.join(map(str, history[:5])) + '\n')
    except OSError:
        pass


def frame_command(cmd, marker):
    # 子 shell 隔离 exit/exec；随机尾部标记避免普通输出碰撞。
    return '/system/bin/sh -c ' + shlex.quote(cmd) + "; __dsha_rc=$?; printf '\\n" + marker + "%s\\n' \"$__dsha_rc\""


def parse_shell_result(raw, marker):
    out = raw.decode('utf-8', 'replace') if isinstance(raw, bytes) else str(raw)
    match = re.search(r'\r?\n' + re.escape(marker) + r'([0-9]{1,3})\r?\n?\Z', out)
    if match is None or int(match.group(1)) > 255:
        raise ExecutionUnknown('未收到完整退出码；命令可能已执行，不会自动重试')
    return ShellResult(out[:match.start()], int(match.group(1)))


def request_native_vscreen_commit(ticket):
    """Consume the one-use managed launch authority immediately before the ADB shell send."""
    import urllib.request
    import urllib.parse
    try:
        with open('/root/.dsh/.bridge_token') as source:
            token = source.read().strip()
        if not token:
            raise ValueError('missing token')
        query = urllib.parse.urlencode({'ticket': ticket})
        request = urllib.request.Request('http://127.0.0.1:3090/device/vscreen/commit?' + query,
            headers={'X-Token': token})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=10) as response:
            value = json.loads(response.read(65536)).get('result')
        if value != 'VSCREEN_START_COMMITTED':
            raise policy.Blocked('虚拟屏启动许可已撤销或已使用')
    except policy.Blocked:
        raise
    except Exception as error:
        # A lost commit response may mean the one-use ticket was consumed. Never send or retry.
        raise ExecutionUnknown('虚拟屏启动许可结果未知，命令未重放（' + type(error).__name__ + '）') from error


def run_on_endpoint(device_cls, signer_cls, cmd, host, port, deadline, command_timeout=COMMAND_TIMEOUT):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise ConnectFail('连接总时限已到，命令尚未发送')
    dev = None
    try:
        try:
            with open(KEYPUB, 'rb') as f:
                public = f.read().strip()
            with open(KEY, 'rb') as f:
                private = f.read()
            signer = signer_cls(public, private)
            dev = device_cls(host, port, default_transport_timeout_s=min(TRANSPORT_TIMEOUT, remaining))
            connected = dev.connect(rsa_keys=[signer], transport_timeout_s=min(TRANSPORT_TIMEOUT, remaining),
                auth_timeout_s=min(8, remaining), read_timeout_s=min(8, remaining), tls_priv_pem=private)
            if not connected:
                raise ConnectionError('ADB 握手未完成')
            if time.monotonic() >= deadline:
                raise TimeoutError('连接总时限已到')
        except Exception as e:
            raise ConnectFail('%s:%d %s: %s' % (host, port, type(e).__name__, str(e)[:200])) from e
        marker = '__DSHA_EXIT_' + uuid.uuid4().hex + '__='
        # 从这里开始即使异常也禁止换地址重放，包括不兼容库抛出的 TypeError。
        try:
            def shell(command):
                marker = '__DSHA_EXIT_' + uuid.uuid4().hex + '__='
                if isinstance(cmd, dict) and cmd.get('capability') == 'sms.read':
                    # A connection may outlive the user's native permission. Recheck
                    # the same canonical query at the last pre-send boundary; a new
                    # plan must not silently change its user or requested fields.
                    current = request_device_plan(command, bool(cmd.get('su')))
                    if (current.get('authorization') != 'remembered'
                            or any(current.get(key) != cmd.get(key)
                                   for key in ('version', 'kind', 'argv', 'capability', 'authorization'))):
                        raise policy.Blocked('敏感查询授权或内容已变化，命令未发送')
                if isinstance(cmd, dict) and cmd.get('kind') == 'VIRTUAL_SCREEN':
                    if cmd.get('_dsha_sent'):
                        raise policy.Blocked('受管虚拟屏启动只允许发送一次')
                    ticket = cmd.get('nativeTicket', '')
                    if not re.fullmatch(r'[a-f0-9]{48}', ticket):
                        raise policy.Blocked('缺少受管虚拟屏启动许可')
                    request_native_vscreen_commit(ticket)
                    cmd['_dsha_sent'] = True
                if isinstance(cmd, dict) and cmd.get('su'):
                    command = 'su -c ' + shlex.quote(command)
                raw = dev.shell(frame_command(command, marker),
                    transport_timeout_s=min(TRANSPORT_TIMEOUT, command_timeout),
                    read_timeout_s=min(8, command_timeout), timeout_s=command_timeout)
                return parse_shell_result(raw, marker)
            result = policy.execute(cmd, shell, ShellResult) if isinstance(cmd, dict) else shell(cmd)
        except policy.Blocked:
            raise
        except Exception as e:
            raise ExecutionUnknown('%s:%d 返回中断（%s）；命令可能已执行，请先检查设备，未自动重试'
                                   % (host, port, type(e).__name__)) from e
        remember_endpoint(host, port)
        return result
    finally:
        if dev is not None:
            try:
                dev.close()
            except Exception:
                pass


def connect_with_retry(device_cls, signer_cls, cmd, port, host='',
                       connect_timeout=CONNECT_TIMEOUT, command_timeout=COMMAND_TIMEOUT):
    deadline = time.monotonic() + connect_timeout
    hosts = candidate_hosts(host)
    tried, errors = set(), []
    def attempt(endpoints):
        for addr, p in endpoints:
            if (addr, p) in tried or not 1 <= p <= 65535 or time.monotonic() >= deadline:
                continue
            tried.add((addr, p))
            try:
                return run_on_endpoint(device_cls, signer_cls, cmd, addr, p, deadline, command_timeout)
            except ConnectFail as e:
                errors.append(str(e))
        return None
    result = attempt([(h, port) for h in hosts] if port else [])
    if result is not None:
        return result
    remaining = deadline - time.monotonic()
    if remaining > 0:
        result = attempt(discover_endpoints('_adb-tls-connect._tcp.local.', min(4, remaining), hosts))
        if result is not None:
            return result
    result = attempt([(h, p) for p in load_port_history() + [5555] for h in hosts])
    if result is not None:
        return result
    raise ConnectFail('命令尚未发送；请检查无线调试与配对授权。尝试记录：\n' + '\n'.join(errors[-8:]))


def request_device_plan(cmd, use_su=False):
    """所有设备命令均经过同一原生白名单；不接受 DSH_INTERNAL 或关闭确认来绕过。"""
    import urllib.request
    import urllib.parse
    try:
        with open('/root/.dsh/.bridge_token') as source:
            token = source.read().strip()
        if not token:
            raise ValueError('missing token')
        query = urllib.parse.urlencode({'cmd': cmd, 'su': '1' if use_su else '0'})
        request = urllib.request.Request('http://127.0.0.1:3090/device/plan?' + query, headers={'X-Token': token})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=75) as response:
            value = json.loads(response.read(1024 * 1024)).get('result')
        if not isinstance(value, str) or not value.startswith('{'):
            raise policy.Blocked(str(value or '原生策略未就绪'))
        plan = json.loads(value)
        if plan.get('version') != 1 or plan.get('kind') not in ('READ', 'FILE', 'STOP'):
            raise policy.Blocked(plan.get('reason') or '命令未获策略允许')
        plan['su'] = use_su
        return plan
    except policy.Blocked:
        raise
    except Exception as error:
        raise policy.Blocked('设备策略桥不可用，命令未发送；请打开或更新 DSHA（' + type(error).__name__ + '）') from error


def request_native_vscreen_start(cmd, ticket):
    """Get a typed plan only while the native manager holds the matching launch lease."""
    import urllib.request
    import urllib.parse
    try:
        with open('/root/.dsh/.bridge_token') as source:
            token = source.read().strip()
        if not token:
            raise ValueError('missing token')
        query = urllib.parse.urlencode({'cmd': cmd, 'ticket': ticket})
        request = urllib.request.Request('http://127.0.0.1:3090/device/vscreen/start?' + query,
            headers={'X-Token': token})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=15) as response:
            envelope = json.loads(response.read(1024 * 1024))
        value = json.loads(envelope['result'])
        plan = value.get('plan')
        if (value.get('state') != 'adb' or not isinstance(plan, dict)
                or plan.get('kind') != 'VIRTUAL_SCREEN' or plan.get('nativeTicket') != ticket
                or plan.get('nativeAuthorization') != 'managed-vscreen-start'):
            raise policy.Blocked('原生虚拟屏启动许可没有授权此操作')
        return plan
    except policy.Blocked:
        raise
    except Exception as error:
        raise policy.Blocked('无法取得原生虚拟屏启动许可，命令未发送（' + type(error).__name__ + '）') from error


def parse_args(args):
    port, host, timeout, connect_timeout, use_su, vscreen_launch = 0, '', COMMAND_TIMEOUT, CONNECT_TIMEOUT, False, False
    explicit_endpoint = False
    while args:
        option = args[0]
        if option == '--su':
            use_su = True
            args = args[1:]
        elif option == '--vscreen-launch':
            if vscreen_launch:
                raise ValueError('虚拟屏启动标记重复')
            vscreen_launch = True
            args = args[1:]
        elif option in ('--port', '--host', '--timeout', '--connect-timeout'):
            if len(args) < 2:
                raise ValueError(option + ' 缺少参数')
            value, args = args[1], args[2:]
            if option == '--host':
                host = str(ipaddress.ip_address(value))
                explicit_endpoint = True
            elif option == '--port':
                port = int(value)
                explicit_endpoint = True
                if not 1 <= port <= 65535:
                    raise ValueError('端口应在 1—65535')
            else:
                number = int(value)
                if not 1 <= number <= 180:
                    raise ValueError('超时应在 1—180 秒')
                if option == '--timeout': timeout = number
                else: connect_timeout = number
        elif option == '--':
            args = args[1:]
            break
        else:
            break
    if vscreen_launch and (use_su or explicit_endpoint):
        raise ValueError('受管虚拟屏启动不能覆盖设备通道或 Root 身份')
    return port, host, timeout, connect_timeout, use_su, vscreen_launch, (args[0] if len(args) == 1 else shlex.join(args)) if args else 'id'


def read_vscreen_ticket(stream=None):
    """Read exactly one 48-byte lease from the private parent pipe, then require EOF."""
    source = sys.stdin.buffer if stream is None else stream
    raw = source.read(49)
    if len(raw) != 48 or not re.fullmatch(rb'[a-f0-9]{48}', raw):
        raise ValueError('无效的受管虚拟屏启动许可')
    return raw.decode('ascii')


def request_native_execution(cmd, use_su=False, force_adb=False):
    """原生层先选 root/Shizuku；仅收到明确的 ADB 计划才连接，不重放未知结果。"""
    import urllib.request
    import urllib.parse
    try:
        with open('/root/.dsh/.bridge_token') as source:
            token = source.read().strip()
        if not token:
            raise ValueError('missing token')
    except (OSError, ValueError) as error:
        raise policy.Blocked('设备桥未准备好，请打开 DSHA 后重试') from error
    query = urllib.parse.urlencode({'cmd': cmd, 'su': '1' if use_su else '0', 'adb': '1' if force_adb else '0'})
    request = urllib.request.Request('http://127.0.0.1:3090/device/execute?' + query, headers={'X-Token': token})
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=155) as response:
            envelope = json.loads(response.read(1024 * 1024))
        value = json.loads(envelope['result'])
        if value.get('state') == 'completed':
            code = value.get('exit')
            if not isinstance(code, int) or not 0 <= code <= 255 or not isinstance(value.get('output'), str):
                raise ValueError('invalid execution result')
            return ShellResult(value['output'], code)
        plan = value.get('plan')
        if value.get('state') != 'adb' or not isinstance(plan, dict) or plan.get('version') != 1:
            raise ValueError('missing explicit ADB plan')
        if plan.get('kind') not in ('READ', 'FILE', 'STOP'):
            raise ValueError('invalid ADB plan')
        return plan
    except Exception as error:
        raise ExecutionUnknown('设备桥响应不完整，命令可能已执行，不会切换通道重试（' + type(error).__name__ + '）') from error


def main():
    try:
        port, host, timeout, connect_timeout, use_su, vscreen_launch, cmd = parse_args(sys.argv[1:])
        launch_ticket = read_vscreen_ticket() if vscreen_launch else ''
    except ValueError as e:
        print('INVALID_ARGUMENT: %s\n[EXIT=2]' % e)
        return 2
    try:
        plan = request_native_vscreen_start(cmd, launch_ticket) if vscreen_launch else request_native_execution(cmd, use_su, bool(port or host))
    except policy.Blocked as error:
        print('[POLICY_BLOCKED] %s\n[EXIT=126]' % error)
        return 126
    except ExecutionUnknown as error:
        print('EXECUTION_UNKNOWN: %s\n[EXIT=125]' % error)
        return 125
    if isinstance(plan, ShellResult):
        print(plan.output)
        return plan.exit_code
    if not (os.path.isfile(KEY) and os.path.isfile(KEYPUB)):
        print('NO_KEY: 请到设置 → 设备能力授权完成 ADB 无线配对\n[EXIT=1]')
        return 1
    try:
        from adb_shell_wifi.adb_device import AdbDeviceTls
        from adb_shell_wifi.auth.sign_pythonrsa import PythonRSASigner
    except ImportError:
        print('DEPS_MISSING: ADB 依赖未就绪，请重新打开配对页准备环境\n[EXIT=1]')
        return 1
    if not port:
        try:
            with open(KEYDIR + '/connect_port') as f:
                port = int(f.read(20).strip())
            if not 1 <= port <= 65535: port = 0
        except (OSError, ValueError):
            port = 0
    if not host:
        try:
            with open(KEYDIR + '/connect_host') as f:
                saved_host = f.read(128).strip()
            if saved_host in candidate_hosts(): host = saved_host
        except OSError:
            pass
    try:
        result = connect_with_retry(AdbDeviceTls, PythonRSASigner, plan, port, host, connect_timeout, timeout)
    except policy.Blocked as error:
        print('[POLICY_BLOCKED] %s\n[EXIT=126]' % error)
        return 126
    except ConnectFail as e:
        print('CONNECT_FAIL: %s\n[EXIT=124]' % e)
        return 124
    except ExecutionUnknown as e:
        print('EXECUTION_UNKNOWN: %s\n[EXIT=125]' % e)
        return 125
    sys.stdout.write(result.output)
    if result.output and not result.output.endswith('\n'): print()
    print('[EXIT=%d]' % result.exit_code)
    return result.exit_code


if __name__ == '__main__':
    sys.exit(main())
