#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# DSHA_ADB_SCRIPT_VERSION=16
"""无线配对只握手一次；配对授权与连接验证分别反馈。

PAIR_OK 表示配对完成；只有 CONNECT_OK 才能执行设备命令。
CONNECT_WARN 表示已配对但连接未验证，不应重复消耗配对码。
"""
import argparse
import importlib.util
import os
import re
import socket
import sys
import time

# 与 shell 共用有限时连接/退出码/mDNS实现，避免两条路径再次漂移。
_spec = importlib.util.spec_from_file_location('dsha_adb_shell', os.path.join(os.path.dirname(__file__), 'adb-shell.py'))
adb = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(adb)
KEYDIR, KEY, KEYPUB = adb.KEYDIR, adb.KEY, adb.KEYPUB


def check_deps():
    try:
        from adb_shell_wifi.pairing import pair
        from spake2.spake2 import Spake2_Alice, Spake2_Bob
        return True
    except ImportError:
        print('DEPS_MISSING: 无线配对依赖不完整，请重新准备 ADB 环境', flush=True)
        return False


def ensure_key():
    os.makedirs(KEYDIR, mode=0o700, exist_ok=True)
    os.chmod(KEYDIR, 0o700)
    if not (os.path.isfile(KEY) and os.path.isfile(KEYPUB)):
        from adb_shell_wifi.auth.keygen import keygen
        keygen(KEY)
        print('KEY_GEN_OK', flush=True)


def probe(host, port):
    try:
        with socket.create_connection((host, port), timeout=2):
            return True
    except OSError:
        return False


def do_pair(host, port, code):
    from adb_shell_wifi.pairing import pair
    try:
        with open(KEY, 'rb') as f:
            private = f.read()
        with open(KEYPUB, 'rb') as f:
            public = f.read().strip()
        # 不打印配对码、密钥或库返回的配对对象。
        pair(host, port, code, private, public, timeout_s=25)
        return True, 'PAIR_OK: 无线配对授权已完成'
    except Exception as e:
        kind = type(e).__name__
        message = str(e).lower()
        if any(s in message for s in ('decrypt', 'spake', 'init_cipher')):
            return False, 'SPAKE2_ERROR: 配对码错误或已失效，请重新打开配对弹窗（%s）' % kind
        if any(s in message for s in ('ssl', 'tls', 'handshake')):
            return False, 'TLS_ERROR: 配对握手失败，请确认使用的是配对端口（%s）' % kind
        return False, 'PAIR_FAIL: 配对未完成，请重新获取配对码和端口（%s）' % kind


def verify_connection(port=0, host=''):
    from adb_shell_wifi.adb_device import AdbDeviceTls
    from adb_shell_wifi.auth.sign_pythonrsa import PythonRSASigner
    if not port:
        try:
            with open(KEYDIR + '/connect_port') as f:
                port = int(f.read(20).strip())
            if not 1 <= port <= 65535: port = 0
        except (OSError, ValueError):
            port = 0
    result = adb.connect_with_retry(AdbDeviceTls, PythonRSASigner,
        'id', port, host, connect_timeout=25, command_timeout=10)
    if result.exit_code != 0 or not re.search(r'(?m)^uid=\d+', result.output):
        raise adb.ExecutionUnknown('连接自检没有返回有效身份或远端命令失败')
    print('CONNECT_OK: 设备连接已验证', flush=True)
    print(result.output.strip(), flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--code', help='6 位配对码')
    ap.add_argument('--host', default='', help='本机配对服务 IP')
    ap.add_argument('--port', type=int, default=0, help='配对端口，0=重新发现')
    ap.add_argument('--connect-port', type=int, default=0, help='连接端口，0=重新发现')
    ap.add_argument('--genkey', action='store_true')
    ap.add_argument('--verify-only', action='store_true', help='仅验证已有配对连接，不再配对')
    ap.add_argument('--grant-keepalive', action='store_true', help='配对设置专用：仅授予 DSHA 自身的保活权限')
    a = ap.parse_args()
    if not 0 <= a.port <= 65535 or not 0 <= a.connect_port <= 65535:
        print('INVALID_PORT: 端口必须在 1—65535，自动发现请留空')
        return 2
    if a.code is not None and not re.fullmatch(r'[0-9]{6}', a.code):
        print('INVALID_CODE: 配对码必须恰好为 6 位数字')
        return 2
    if not check_deps():
        return 1
    if a.grant_keepalive:
        from adb_shell_wifi.adb_device import AdbDeviceTls
        from adb_shell_wifi.auth.sign_pythonrsa import PythonRSASigner
        # 固定的应用设置操作，没有接收任意命令的“内部跳过”入口。
        try:
            result = adb.connect_with_retry(AdbDeviceTls, PythonRSASigner,
                'pm grant com.dsh.client android.permission.WRITE_SECURE_SETTINGS',
                a.connect_port, a.host, connect_timeout=15, command_timeout=10)
            print(result.output); print('[EXIT=%d]' % result.exit_code)
            return result.exit_code
        except (adb.ConnectFail, adb.ExecutionUnknown) as error:
            print('KEEPALIVE_WARN: %s\n[EXIT=1]' % error)
            return 1
    if a.genkey:
        ensure_key()
        return 0
    if a.verify_only:
        if not (os.path.isfile(KEY) and os.path.isfile(KEYPUB)):
            print('NO_KEY: 尚未配对，请先获取配对码完成配对')
            return 1
        try:
            verify_connection(a.connect_port, a.host)
            return 0
        except (adb.ConnectFail, adb.ExecutionUnknown, ValueError) as e:
            print('CONNECT_WARN: 已有配对的连接尚未验证：%s' % e, flush=True)
            return 1
    if not a.code:
        print('NO_CODE: 请输入本次配对弹窗里的 6 位数字')
        return 2
    ensure_key()
    hosts = adb.candidate_hosts(a.host)
    print('PAIR_DISCOVERING: 正在查找本机配对服务…', flush=True)
    if a.port:
        endpoints = [(h, a.port) for h in hosts]
    else:
        endpoints = adb.discover_endpoints('_adb-tls-pairing._tcp.local.', 5, hosts)
    if not endpoints:
        print('NO_PAIR_PORT: 未发现本机配对服务；请保持系统配对弹窗打开，或填写该弹窗的 IP 和配对端口')
        return 1
    reachable = next(((host, port) for host, port in endpoints if probe(host, port)), None)
    if reachable is None:
        print('PORT_UNREACHABLE: 本次配对端口不可达；重开配对弹窗后请使用新端口与新配对码')
        return 1
    print('PAIR_HANDSHAKE: 正在完成一次配对握手…', flush=True)
    ok, detail = do_pair(*reachable, a.code)
    print(detail, flush=True)
    if not ok:
        return 1
    print('CONNECT_VERIFYING: 正在验证设备连接（与配对端口不同）…', flush=True)
    time.sleep(1)
    try:
        verify_connection(a.connect_port, reachable[0])
    except (adb.ConnectFail, adb.ExecutionUnknown) as e:
        print('CONNECT_WARN: 配对已完成，连接尚未验证：%s\n'
              '请检查无线调试主页面的连接端口，点击「验证连接」；无需重新配对。' % e, flush=True)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print('PAIR_ERROR: 配对流程异常（%s），请重新准备环境后重试' % type(error).__name__, flush=True)
        sys.exit(1)
