#!/usr/bin/env python3
"""在独立 DSH_HOME 运行真实 CLI 故障样例和观察器开销比较，不读取用户配置。"""
import json
import os
from pathlib import Path
import select
import subprocess
import tempfile
import time

base = Path(tempfile.mkdtemp(prefix='.dsha-startup-diagnostics-', dir='/root'))
observer = Path(os.environ.get('DSHA_TEST_OBSERVER', '/root/.dsh/startup-observer.cjs'))

def write(file, value):
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(json.dumps(value) if isinstance(value, dict) else value, encoding='utf-8')

def profile(home, plugin=None):
    folder = home / 'profiles/web'
    bundles = ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app']
    if plugin: bundles.append(plugin)
    write(folder / 'package.json', {'name':'dsha-audit-profile', 'private':True, 'dsh':{'profile':{'bundles':bundles, 'patchReload':'startup'}}})
    write(folder / 'cordis.patch.yml', '[]\n')
    return folder

def run(home, observed=True, timeout=22):
    env = dict(os.environ, DSH_HOME=str(home), DSH_CONFIRM='1', BROWSER='true', DSHA_STARTUP_PROFILE='web')
    for key in ['DEEPSEEK_API_KEY', 'OPENAI_API_KEY', 'NODE_OPTIONS', 'DSHA_PRELOAD_PREVIOUS']:
        env.pop(key, None)
    cmd = ['node'] + (['--import', str(observer)] if observed else [])
    cmd += ['/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js', 'web', '--no-open', '--host', '127.0.0.1', '--port', '3187']
    began = time.monotonic(); process = subprocess.Popen(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = b''; ready = False
    try:
        while time.monotonic() - began < timeout:
            readable, _, _ = select.select([process.stdout], [], [], 0.1)
            if readable:
                chunk = os.read(process.stdout.fileno(), 8192)
                if not chunk: break
                output += chunk
                if b'dsh web: http://127.0.0.1:3187/' in output:
                    ready = True; break
            elif process.poll() is not None: break
        elapsed = time.monotonic() - began
    finally:
        if process.poll() is None: process.terminate()
        try: tail, _ = process.communicate(timeout=4)
        except subprocess.TimeoutExpired:
            process.kill(); tail, _ = process.communicate(timeout=4)
        output += tail
    # 原始鉴权只存在独立测试进程内存；结果仅保存经过脱敏的测试记录。
    import re
    safe = re.sub(r'([?&]token=)[^\s"\\]+', r'\1***', output.decode('utf-8', 'replace'))
    write(base / ('last-output.txt'), safe)
    return ready, elapsed, safe

for kind in ['missing', 'throw']:
    home = base / kind; folder = profile(home, 'fixture-' + kind)
    plugin = folder / 'node_modules' / ('fixture-' + kind)
    write(plugin / 'package.json', {'name':'fixture-' + kind, 'version':'1.0.0', 'main':'index.js', 'dsh':{'bundle':{'patch':'cordis.patch.yml'}}})
    write(plugin / 'cordis.patch.yml', '- insert:\n    - id: fixture-' + kind + '\n      name: fixture-' + kind + '\n')
    code = "require('dsha-nonexistent-dependency');" if kind == 'missing' else "exports.apply = function(){throw new Error('DSHA_FIXTURE_THROW');};"
    write(plugin / 'index.js', code)
    ready, elapsed, output = run(home, timeout=18)
    assert 'fixture-' + kind in output, output
    assert ('dsha-nonexistent-dependency' if kind == 'missing' else 'DSHA_FIXTURE_THROW') in output, output
    print(json.dumps({'fixture':kind, 'ready':ready, 'seconds':round(elapsed,3), 'named_error':True}), flush=True)
    write(base / (kind + '-output.txt'), output)

home = base / 'benchmark'; profile(home)
for observed in [False, True, False, True]:
    ready, elapsed, output = run(home, observed)
    assert ready, output
    print(json.dumps({'benchmark':'core', 'observer':observed, 'seconds':round(elapsed,3)}), flush=True)
print('DEVICE_FIXTURE_PASS ' + str(base), flush=True)
