#!/usr/bin/env python3
"""在独立 DSH_HOME 比较真实 CLI 启动缓存，并输出加载耗时；不发模型请求。"""
import json
import os
from pathlib import Path
import re
import select
import subprocess
import tempfile
import time

base = Path(tempfile.mkdtemp(prefix='dsha-startup-perf-', dir='/tmp'))
profile = base / 'home/profiles/web'
profile.mkdir(parents=True)
source = Path('/root/.dsh/profiles/web')
manifest = json.loads((source / 'package.json').read_text())
manifest['dsh']['profile']['patchReload'] = 'startup'
(profile / 'package.json').write_text(json.dumps(manifest))
(profile / 'cordis.patch.yml').write_text('[]\n')
(profile / 'node_modules').symlink_to(source / 'node_modules')
preload = base / 'timing.cjs'
preload.write_text(r'''
const t0 = performance.now();
const fs = require('node:fs');
const Module = require('node:module');
const records = {};
const entries = [];
function wrap(object, name, label) {
  const original = object[name];
  if (typeof original !== 'function') return;
  object[name] = function (...args) {
    const began = performance.now();
    try { return original.apply(this, args); }
    finally { const r = records[label] ||= {count:0, ms:0}; r.count++; r.ms += performance.now()-began; }
  };
  Object.assign(object[name], original);
}
for (const op of ['readFileSync','statSync','lstatSync','realpathSync','readlinkSync','existsSync','mkdirSync','writeFileSync']) wrap(fs, op, op);
wrap(fs.realpathSync, 'native', 'realpathSync.native');
Module.syncBuiltinESMExports();
require('/root/.dsh/startup-observer.cjs');
const {Entry} = require('/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/cordis-plugin-loader/lib/index.js');
const init = Entry.prototype.init;
Entry.prototype.init = function (...args) {
  const began = performance.now();
  return init.apply(this,args).finally(() => entries.push({id:this.options?.id || this.options?.name,start:Math.round(began),ms:Math.round(performance.now()-began)}));
};
const write = process.stdout.write;
let reported = false;
process.stdout.write = function (chunk, ...args) {
  if (!reported && String(chunk).includes('dsh web: http://127.0.0.1:3187/')) {
    reported = true;
    setTimeout(() => {
      write.call(process.stdout, '[PERF] '+JSON.stringify({elapsed:performance.now()-t0,fs:records,entries:entries.sort((a,b)=>b.ms-a.ms).slice(0,20)})+'\n');
      Module.flushCompileCache();
    }, 100);
  }
  return write.call(this,chunk,...args);
};
''')

for index, cache in enumerate([False, True, True, False]):
    env = dict(os.environ, DSH_HOME=str(base / 'home'), BROWSER='true', DSH_CONFIRM='1', DSHA_STARTUP_PROFILE='web')
    for key in ['DEEPSEEK_API_KEY', 'OPENAI_API_KEY', 'NODE_OPTIONS', 'DSHA_PRELOAD_PREVIOUS', 'NODE_COMPILE_CACHE']:
        env.pop(key, None)
    if cache:
        env['NODE_COMPILE_CACHE'] = str(base / 'node-cache')
    command = ['node', '--import', str(preload)]
    if index == 2:
        command += ['--cpu-prof', '--cpu-prof-dir=' + str(base)]
    command += ['/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js', 'web', '--no-open', '--host', '127.0.0.1', '--port', '3187']
    began = time.monotonic()
    proc = subprocess.Popen(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = b''; ready = None
    try:
        while time.monotonic() - began < 40:
            readable, _, _ = select.select([proc.stdout], [], [], 0.05)
            if readable:
                chunk = os.read(proc.stdout.fileno(), 65536)
                if not chunk: break
                output += chunk
                if ready is None and b'dsh web: http://127.0.0.1:3187/' in output: ready = time.monotonic()-began
                if ready is not None and time.monotonic()-began > ready+0.7: break
            elif ready is not None and time.monotonic()-began > ready+0.7: break
    finally:
        proc.terminate()
        try: tail, _ = proc.communicate(timeout=4)
        except subprocess.TimeoutExpired:
            proc.kill(); tail, _ = proc.communicate(timeout=4)
        output += tail
    safe = re.sub(r'([?&]token=)[^\s"\\]+', r'\1***', output.decode('utf-8','replace'))
    (base / f'run-{index}.log').write_text(safe)
    assert ready is not None, safe
    print(json.dumps({'cache':cache,'seconds':round(ready,3)}),flush=True)
    for line in safe.splitlines():
        if line.startswith('[PERF] '): print(line,flush=True)

for path in base.glob('*.cpuprofile'):
    doc = json.loads(path.read_text()); nodes = {n['id']:n for n in doc['nodes']}; times = {}
    for node, micros in zip(doc['samples'],doc['timeDeltas']): times[node] = times.get(node,0) + micros
    print('CPU_TOP '+json.dumps([{'ms':round(ms/1000),'function':nodes[node]['callFrame']['functionName'],'url':nodes[node]['callFrame']['url']} for node,ms in sorted(times.items(),key=lambda row:-row[1])[:25]]),flush=True)
print('PERF_DONE '+str(base),flush=True)
