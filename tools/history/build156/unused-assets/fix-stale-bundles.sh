#!/bin/bash
# fix-stale-bundles.sh — 只清理【确定失效的内置 bundle 引用】，绝不误删第三方插件。
# 原则：第三方插件（非 dsh-device-shell-guide / dsh-task-notifier / @deepseek-ai/*）
# 即使暂时解析不到也只警告不清除——它们的实体可能还在 .pnpm，误删会丢插件。
# 启动前由 App 调用；幂等。
set -u
PF=/root/.dsh/profiles/web/package.json
[ -f "$PF" ] || exit 0

python3 - "$PF" <<'PY'
import json, os, sys
pf = sys.argv[1]
d = json.load(open(pf))
bundles = d.get('dsh', {}).get('profile', {}).get('bundles', [])
if not bundles:
    sys.exit(0)
nm = os.path.join(os.path.dirname(pf), 'node_modules')

# 内置插件白名单：解析失败才清（这些是 App 管理的，能重新注册）
BUILTIN = {'dsh-device-shell-guide', 'dsh-task-notifier',
           'dsh-status-overlay', 'dsh-web-mobile'}

def resolvable(name):
    sub = name.split('/')[-1]
    for base in (
        '/usr/local/lib/node_modules/@deepseek-ai',
        '/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai',
        '/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules',
    ):
        if os.path.isfile(os.path.join(base, sub, 'package.json')):
            return True
    if os.path.isfile(os.path.join(nm, name, 'package.json')):
        return True
    # DSHA 内置插件实体（/root/dsha-mobile-nav 等）——不在 @deepseek-ai 全局，
    # 也不是 nm 下实体（是符号链接）。不认这里会把正常内置插件当 stale 清掉。
    for real in ('/root/dsha-device-shell-guide', '/root/dsha-task-notifier',
                 '/root/dsha-status-overlay', '/root/dsha-web-mobile'):
        if name in ('dsh-device-shell-guide', 'dsh-task-notifier',
                    'dsh-status-overlay', 'dsh-web-mobile') and os.path.isfile(os.path.join(real, 'package.json')):
            return True
    pnpm = os.path.join(nm, '.pnpm')
    if os.path.isdir(pnpm):
        key = name.replace('@', '').replace('/', '+')
        for e in os.listdir(pnpm):
            if e.startswith(key + '@'):
                return True
    return False

keep, removed, warned = [], [], []
for b in bundles:
    if resolvable(b):
        keep.append(b)
    elif b in BUILTIN:
        removed.append(b)  # 内置且解析不到 → 清（App 会重新注册）
    else:
        keep.append(b)     # 第三方解析不到 → 保留（防误删），仅警告
        warned.append(b)

if removed:
    d['dsh']['profile']['bundles'] = keep
    json.dump(d, open(pf, 'w'), indent=2)
    print('STALE_REMOVED_BUILTIN: ' + ','.join(removed))
if warned:
    print('WARN_KEPT_THIRD_PARTY: ' + ','.join(warned) + ' （保留，实体可能在 .pnpm）')
if not removed and not warned:
    print('BUNDLES_OK')
PY
