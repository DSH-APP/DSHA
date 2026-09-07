#!/usr/bin/env python3
"""私有 ADB 验收命令助手；只有显式运行 main 才调用 USB adb。

真实配对码通过 getpass 或 stdin 进入内存，只经 adb stdin 写到 App 私有一次性请求。
不接受命令行 code 参数，不保存主机凭据文件，也不打印原始请求/adb stderr。
"""
import argparse
import getpass
import json
import re
import shlex
import subprocess
import sys
import time
import uuid

ADB = r'F:\DSHA\_toolchains\android-sdk\platform-tools\adb.exe'
FOLDER = 'cache/adb-flow-audit/'
OPERATIONS = ('status', 'prepare', 'verify', 'pair', 'state', 'launch', 'rotate', 'theme', 'lifecycle', 'stop')


def request(op, host='', port='', connect_port='', ui=False, code='', night='toggle'):
    if op not in OPERATIONS:
        raise ValueError('未知操作')
    for value in (port, connect_port):
        if value and (not re.fullmatch(r'[0-9]{1,5}', value) or not 1 <= int(value) <= 65535):
            raise ValueError('端口必须是 1—65535，自动发现请留空')
    if op == 'pair' and not re.fullmatch(r'[0-9]{6}', code):
        raise ValueError('配对码必须恰好为六位数字；未发送请求')
    if code and op != 'pair':
        raise ValueError('仅 pair 操作接受配对码')
    data = {'id': uuid.uuid4().hex, 'op': op}
    if host: data['host'] = host
    if port: data['port'] = port
    if connect_port: data['connect_port'] = connect_port
    if ui: data['ui'] = True
    if op == 'pair': data['code'] = code
    if op == 'theme': data['night'] = night
    return data


def adb_prefix(executable, serial=''):
    return [executable] + (['-s', serial] if serial else [])


def read_report(prefix, name):
    if not re.fullmatch(r'(?:state|[a-f0-9]{32}(?:-result)?)\.json', name):
        raise ValueError('无效报告名')
    result = subprocess.run(prefix + ['exec-out', 'run-as com.dsh.client sh -c ' +
        shlex.quote('cat ' + FOLDER + name)], capture_output=True, timeout=10)
    if result.returncode != 0:
        return None
    try:
        value = json.loads(result.stdout)
        return value if isinstance(value, dict) else None
    except (UnicodeError, ValueError):
        return None


def send(prefix, data):
    identifier = data['id']
    if not re.fullmatch(r'[a-f0-9]{32}', identifier):
        raise ValueError('无效请求标识')
    payload = json.dumps(data, ensure_ascii=False).encode('utf-8')
    if len(payload) > 4096:
        raise ValueError('请求过长')
    path = FOLDER + 'command-' + identifier
    # 请求名唯一，rename 后服务器才读取；不与其他命令争用一个 mailbox 文件。
    command = 'umask 077; head -c ' + str(len(payload)) + ' > ' + path + '.tmp && mv ' + path + '.tmp ' + path + '.json'
    result = subprocess.run(prefix + ['shell', '-T', 'run-as com.dsh.client sh -c ' + shlex.quote(command)],
                            input=payload, capture_output=True, timeout=10)
    if result.returncode != 0:
        raise RuntimeError('私有请求发送未确认，请检查插桩状态；不要直接重发配对')


def run(prefix, data, wait=True, timeout=480):
    state = read_report(prefix, 'state.json')
    if not state or not state.get('listening'):
        raise RuntimeError('请先启动 AdbFlowAudit 的 listen 模式并等待 READY')
    send(prefix, data)
    identifier = data['id']
    end = time.monotonic() + timeout
    response = None
    while time.monotonic() < end:
        response = read_report(prefix, identifier + '.json')
        if response is not None:
            break
        time.sleep(.2)
    if response is None:
        raise RuntimeError('请求结果未返回，请先查 state；禁止自动重放配对')
    if not response.get('ok') or not response.get('accepted') or not wait:
        return response
    while time.monotonic() < end:
        result = read_report(prefix, identifier + '-result.json')
        if result is not None:
            return result
        time.sleep(.3)
    raise RuntimeError('任务仍未返回，请先查 state；配对可能已执行，不会自动重发')


def display(value, secret=''):
    text = json.dumps(value, ensure_ascii=False, indent=2)
    if secret: text = text.replace(secret, '[配对码已隐藏]')
    return re.sub(r'(?<!\d)[0-9]{6}(?!\d)', '[配对码已隐藏]', text)


def main():
    class PrivateParser(argparse.ArgumentParser):
        def error(self, message):
            self.print_usage(sys.stderr)
            self.exit(2, display({'error': message}) + '\n')
    parser = PrivateParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('op', choices=OPERATIONS)
    parser.add_argument('--adb', default=ADB)
    parser.add_argument('--serial', default='')
    parser.add_argument('--host', default='')
    parser.add_argument('--port', default='')
    parser.add_argument('--connect-port', default='')
    parser.add_argument('--ui', action='store_true', help='在已由 launch 打开的配对页内启动实际任务')
    parser.add_argument('--night', choices=('toggle', 'dark', 'light', 'restore'), default='toggle')
    parser.add_argument('--code-stdin', action='store_true', help='程序化调用：仅从 stdin 读取配对码，禁止写入命令行或日志')
    parser.add_argument('--no-wait', action='store_true', help='返回 accepted 后即可发 rotate/theme；随后用 state 查最终结果')
    parser.add_argument('--timeout', type=int, default=480)
    args = parser.parse_args()
    secret = ''
    try:
        if args.op == 'pair':
            if args.code_stdin:
                secret = sys.stdin.readline(64).strip()
            else:
                if not sys.stdin.isatty():
                    raise ValueError('无交互终端，请使用 --code-stdin 通过 stdin 提供配对码')
                secret = getpass.getpass('本次系统配对码（隐藏输入）：').strip()
        data = request(args.op, args.host, args.port, args.connect_port, args.ui, secret, args.night)
        result = run(adb_prefix(args.adb, args.serial), data, not args.no_wait, args.timeout)
        print(display(result, secret))
        if result.get('ok') is False or result.get('outcome') in ('FAIL', 'PAIRED_UNVERIFIED', 'ENVIRONMENT_BUSY'):
            return 1
        return 0
    except (ValueError, RuntimeError) as e:
        print(display({'error': str(e)}, secret), file=sys.stderr)
        return 1
    except (subprocess.SubprocessError, OSError):
        print('ADB 传输未确认；请先检查 state，不要直接重发配对。', file=sys.stderr)
        return 1
    finally:
        secret = ''


if __name__ == '__main__':
    sys.exit(main())
