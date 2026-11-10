#!/usr/bin/env python3
"""外接工具锁与证明：当前证明与锁一致；篡改锁的任一关键字段都被拒绝。"""
import copy
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('prepare_external_tools', ROOT / 'tools/prepare-external-tools.py')
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)

lock = json.loads(tool.LOCK.read_text(encoding='utf-8'))
assert tool.PROOF.read_text(encoding='utf-8') == tool.render(lock), '证明未由当前锁生成'
proof = json.loads(tool.PROOF.read_text(encoding='utf-8'))
assert proof['schema'] == 2
assert sorted(proof['files']) == sorted(tool.ENTRYPOINTS)
assert proof['install'].startswith('apt-get install ')


def rejected(mutate, label):
    broken = copy.deepcopy(lock)
    mutate(broken)
    try:
        tool.validate(broken)
    except ValueError:
        return
    raise AssertionError('未拒绝：' + label)


rejected(lambda d: d['packages'].pop(), '缺少软件包')
rejected(lambda d: d['packages'].append(copy.deepcopy(d['packages'][0])), '重复软件包')
rejected(lambda d: d['packages'][0].update(SHA256='0' * 63), '短摘要')
rejected(lambda d: d['packages'][0].update(Filename='pool/../x_arm64.deb'), '路径穿越')
rejected(lambda d: d['packages'][0].update(Filename='https://evil/x_arm64.deb'), '外部地址')
rejected(lambda d: d['packages'][0].update(Size=0), '大小为 0')
rejected(lambda d: d['packages'][0]['files'].clear(), '无核验文件')
rejected(lambda d: d['entrypoints'].update({'usr/bin/adb': 'usr/bin/sh'}), '入口指向锁外文件')
rejected(lambda d: d['entrypoints'].pop('usr/bin/fastboot'), '缺少入口')
rejected(lambda d: d.update(architecture='amd64'), '架构')
rejected(lambda d: d['sources'][0].update(inReleaseSha256='x'), '来源摘要')
print('PASS external tool lock: proof matches lock, 11 tampering cases rejected')
