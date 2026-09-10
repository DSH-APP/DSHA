#!/usr/bin/env python3
"""配合 debug DevicePolicyAudit：真实原生策略、USB 设备传输及自建临时应用/目录。

可预装并启动 com.dsha.audit.devicepolicy；存在时仅结束此测试应用。
不配对无线 ADB、不读取用户凭据、不执行任何被测危险命令。
"""
import argparse
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess
import time
import types
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('device_policy', root / 'app/src/main/assets/device-shell-policy.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
prefix = [args.adb, '-s', args.serial]
fixture = 'com.dsha.audit.devicepolicy'
suffix = 'dsha-policy-audit-' + uuid.uuid4().hex[:12]
temporary = '/data/local/tmp/' + suffix
download = '/storage/emulated/0/Download/' + suffix
port, token = None, ''
report, dispatched = [], []


def adb(*values):
    return subprocess.run(prefix + list(values), capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)


def shell(command):
    dispatched.append(command)
    process = adb('shell', command)
    return types.SimpleNamespace(output=process.stdout + process.stderr, exit_code=process.returncode)


def require(ok, label):
    if not ok: raise AssertionError(label)
    report.append(label)


def request(endpoint, **params):
    query = urllib.parse.urlencode(params)
    req = urllib.request.Request('http://127.0.0.1:' + str(port) + endpoint + ('?' + query if query else ''), headers={'X-Token': token})
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=20) as response:
        return json.load(response)['result']


def plan(command, su=False):
    value = request('/device/plan', cmd=command, su=int(su))
    if not value.startswith('{'): raise policy.Blocked(value)
    value = json.loads(value)
    if value['kind'] == 'DENY': raise policy.Blocked(value['reason'])
    return value


def execute(command):
    value = plan(command)
    result = policy.execute(value, shell, lambda out, code: types.SimpleNamespace(output=out, exit_code=code))
    require(result.exit_code == 0, '实际执行通过：' + command + (('；' + result.output[:300]) if result.exit_code else ''))
    return result.output


try:
    for _ in range(80):
        ready = adb('shell', 'run-as com.dsh.client cat cache/device-policy-audit/ready')
        if ready.returncode == 0 and ready.stdout.strip() == 'ready': break
        time.sleep(.25)
    else: raise AssertionError('原生测试桥未就绪')
    token = adb('shell', 'run-as com.dsh.client cat cache/device-policy-audit/token').stdout.strip()
    require(token.startswith('audit_'), '仅使用独立测试 token')
    forwarded = adb('forward', 'tcp:0', 'tcp:3090')
    require(forwarded.returncode == 0, 'USB 端口转发就绪')
    port = int(forwarded.stdout.strip())
    require(request('/health') == 'OK', '真实原生桥健康检查')
    require('BRIDGE_PROTOCOL=2' in request('/app/version'), '桥协议版本 2')
    denied = ['mkdir /system/test', 'touch /sdcard/DCIM/test', 'cp /system/build.prop /sdcard/Pictures/test',
              'mv /sdcard/DCIM/a /sdcard/Download/a', 'rm -rf /*', 'rename /sdcard/Android/data/a /sdcard/Download/a',
              'dd if=/dev/zero of=/dev/block/sda', 'mkfs.ext4 /dev/block/sda', 'fdisk -l', 'parted /dev/block/sda print',
              'setenforce 0', 'chcon -R x /data', 'restorecon -RF /data', 'settings put global x 1', 'setprop persist.x 1',
              'mount -o remount,rw /system', 'umount /data', 'fastboot flash boot x', 'flash boot x', 'format data',
              'erase userdata', 'wipe data', 'unrecognized-command', "sh -c 'id'", 'rmdir -p /data/local/tmp/x']
    for command in denied:
        for su in (False, True):
            try: plan(command, su); raise AssertionError('原生策略错误放行：' + command)
            except policy.Blocked: pass
        require(request('/confirm', cmd=command) == 'NO', '旧确认入口不能放行：' + command)
        require('[POLICY_BLOCKED]' in request('/exec', cmd=command), '原生执行入口拒绝：' + command)
    require(not dispatched, '全部危险样例在任何设备传输前被拦截')
    require(request('/confirm', cmd='id') == 'YES', '允许的查询无需弹确认')
    require('Name:' in request('/app/readfile', path='/proc/self/status'), 'App 接口实际读取零长度 /proc 文件')
    require('NO_PERMISSION' in request('/app/readfile', path='/proc/version') or 'Permission denied' in request('/app/readfile', path='/proc/version'), 'Android 未授予读取权限时明确返回原因')
    require('Linux version' in execute('cat /proc/version'), '设备 shell 读取系统文件')
    for path in ('/', '/system', '/data', '/storage/emulated/0/DCIM', '/storage/emulated/0/Pictures', '/storage/emulated/0/Android/data'):
        require(plan('ls ' + path)['kind'] == 'READ', '读取目录在策略层放行：' + path)
    groups = request('/app/apps')
    all_packages = {row.split(':', 1)[1] for row in adb('shell', 'pm list packages').stdout.splitlines() if row.startswith('package:')}
    displayed = {row.split('\t', 1)[0] for row in groups.splitlines() if '\t' in row}
    if displayed:
        require(displayed == all_packages, '原生应用清单与设备 pm 一致：' + str(len(displayed)))
        require('[用户应用]' in groups and '[系统应用]' in groups, '原生用户/系统应用均有分组')
    else:
        require(groups.startswith('[APP_LIST_UNAVAILABLE]'), 'App 权限不足时明确报告完整清单不可用')
    device_groups = execute('pm list packages -U -3') + execute('pm list packages -U -s')
    device_names = {row.split(':', 1)[1].split(' uid:')[0] for row in device_groups.splitlines() if row.startswith('package:')}
    require(device_names == all_packages, '实际设备连接的完整应用清单与 pm 一致：' + str(len(device_names)))
    for directory in (temporary, download):
        execute('mkdir -p ' + directory + '/nested')
        execute('touch ' + directory + '/nested/empty')
        execute('cp /proc/version ' + directory + '/info')
        execute('rename ' + directory + '/info ' + directory + '/renamed')
        execute('cp ' + directory + '/renamed ' + directory + '/nested')
        execute('rm ' + directory + '/nested/renamed')
    shell('/system/bin/ln -s /storage/emulated/0/DCIM ' + temporary + '/link')
    before = len(dispatched)
    try:
        value = plan('touch ' + temporary + '/link/forbidden')
        policy.execute(value, shell, lambda a,b: None)
        raise AssertionError('符号链接被错误放行')
    except policy.Blocked: pass
    require(all(not cmd.startswith('/system/bin/touch ') for cmd in dispatched[before:]), '通向相册的符号链接在元数据阶段拒绝')
    for command in ('am force-stop android', 'am force-stop com.android.systemui', 'am force-stop com.dsh.client', 'am force-stop unknown.app'):
        before = len(dispatched)
        try: policy.execute(plan(command), shell, lambda a,b: None); raise AssertionError('受保护应用错误放行')
        except policy.Blocked: pass
        require(not any(cmd.startswith('/system/bin/am ') for cmd in dispatched[before:]), '执行前保护应用：' + command)
        require(request('/confirm', cmd=command) == 'NO', '旧接口不能绕过应用保护：' + command)
    pid = adb('shell', 'pidof ' + fixture).stdout.strip()
    has_fixture = pid.isdigit()
    if not has_fixture:
        # 没有测试应用时，仅使用现有普通应用作归属检查，执行器仍为记录回调，不发停止命令。
        user_names = [row.split(':', 1)[1] for row in adb('shell', 'pm list packages -3').stdout.splitlines()
                      if row.startswith('package:') and row.split(':', 1)[1] not in ('com.dsh.client', 'moe.shizuku.privileged.api')]
        require(bool(user_names), '设备存在可用于只读归属验证的普通应用')
        value = plan('am force-stop ' + user_names[0])
        spy_calls = []
        def spy(command):
            if command.startswith('/system/bin/am '):
                spy_calls.append(command)
                return types.SimpleNamespace(output='', exit_code=0)
            return shell(command)
        response = policy.execute(value, spy, lambda a,b: types.SimpleNamespace(output=a, exit_code=b))
        require(spy_calls == ['/system/bin/am force-stop ' + user_names[0]], '真实全量清单下普通应用放行（执行记录，不结束日常应用）')
        require('[用户应用]' in response.output and '[系统应用]' in response.output, '普通应用放行前返回完整分组')
        pid = '99999999'
    # 整批验证不能先执行普通 PID，再拒绝 system_server。
    system_pid = adb('shell', 'pidof system_server').stdout.strip()
    value = plan('kill ' + pid + ' ' + system_pid)
    before = len(dispatched)
    try: policy.execute(value, shell, lambda a,b: None); raise AssertionError('混合批次被放行')
    except policy.Blocked: pass
    require(not any(cmd.startswith('/system/bin/am ') for cmd in dispatched[before:]), '混合用户/系统 PID 批次未执行任何停止')
    if has_fixture:
        require(adb('shell', 'pidof ' + fixture).stdout.strip() == pid, '拒绝批次后测试应用仍在运行')
        output = execute('kill -9 ' + pid)
        require('[用户应用]' in output and '[系统应用]' in output, '实际停止前刷新并返回完整分组')
        require(not adb('shell', 'pidof ' + fixture).stdout.strip(), '普通应用实际结束成功')
    require(adb('shell', 'pidof system_server').stdout.strip() == system_pid, '系统关键进程未变')
    Path(args.output).write_text(json.dumps(dict(result='PASS', assertions=len(report), checks=report, apps=len(device_names), nativeInventoryAvailable=bool(displayed), realAppStop=has_fixture), ensure_ascii=False, indent=2), encoding='utf-8')
    print('PASS:', len(report), 'checks;', len(device_names), 'apps; real app stop:', has_fixture, '; protected commands never dispatched')
finally:
    # 仅清理本次随机命名的测试目录；拒绝其它目标，不触及相册或用户应用数据。
    for value, parent in ((temporary, '/data/local/tmp/'), (download, '/storage/emulated/0/Download/')):
        if value == parent + suffix and suffix.startswith('dsha-policy-audit-'):
            adb('shell', '/system/bin/rm -rf -- ' + shlex.quote(value))
    if port is not None: adb('forward', '--remove', 'tcp:' + str(port))
    adb('shell', 'run-as com.dsh.client touch cache/device-policy-audit/done')
