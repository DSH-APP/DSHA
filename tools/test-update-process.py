#!/usr/bin/env python3
"""协调真实手机进程终止验收；只终止已发出独立下载测试标记的主进程。"""
import hashlib
import json
import queue
import re
import subprocess
import threading
import time
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = Path('F:/DSHA/_toolchains/android-sdk/platform-tools/adb.exe')
BASE = [str(ADB), '-s', 'fmuoeujvonizjj4d']
RUNNER = 'com.dsh.client.test/com.deepseekharness.app.core.UpdateProcessInstrumentation'
FIXTURE = ROOT / 'app/build/optimization-https-fixtures/valid.apk'
URL = 'https://dsha.cc/opt-https-6cb498ed.apk'
def run(*args):
    return subprocess.check_output(BASE + list(args), text=True, encoding='utf-8', errors='replace', timeout=30).strip()
def phase():
    xml = run('shell', 'run-as', 'com.dsh.client', 'cat', 'shared_prefs/dsha-updates.xml')
    return {node.attrib['name']: node.text for node in ET.fromstring(xml)}.get('phase')
def size(hash):
    result = subprocess.run(BASE + ['shell', 'run-as', 'com.dsh.client', 'stat', '-c', '%s', 'files/updates/'+hash+'.part'], capture_output=True, text=True, timeout=10)
    return int(result.stdout.strip()) if result.returncode == 0 else 0
def main():
    hash = hashlib.sha256(FIXTURE.read_bytes()).hexdigest()
    command = BASE + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'mode', 'hold', '-e', 'url', URL,
                      '-e', 'sha256', hash, '-e', 'bytes', str(FIXTURE.stat().st_size), '-e', 'code', '114', RUNNER]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
    lines = queue.Queue()
    def consume():
        for line in process.stdout: lines.put(line.rstrip())
        lines.put(None)
    threading.Thread(target=consume, daemon=True).start()
    evidence = {'url':URL, 'sha256':hash, 'bytes':FIXTURE.stat().st_size}
    deadline = time.monotonic()+150
    killed = False
    while time.monotonic() < deadline:
        try: line = lines.get(timeout=1)
        except queue.Empty: continue
        if line is None: break
        print(line, flush=True)
        match = re.search(r'KILL_READY pid=(\d+) bytes=(\d+)', line)
        if not match: continue
        pid = match[1]
        assert pid in run('shell','pidof','com.dsh.client').split(), '测试 PID 已改变，不能终止'
        saved = size(hash)
        assert 0 < saved < evidence['bytes'], '没有可续传的独立文件'
        evidence.update(oldPid=int(pid), savedBytes=saved)
        run('shell','run-as','com.dsh.client','touch','cache/optimization-https-kill')
        killed = True
        print('SIGKILL_SENT',json.dumps(evidence),flush=True)
        break
    if not killed:
        process.terminate()
        raise AssertionError('未达到可控的进程终止节点')
    process.wait(timeout=20)
    # 观察系统是否重投递前台服务；不把显式重新打开误记为系统自动恢复。
    for i in range(20):
        time.sleep(1)
        pid_result = subprocess.run(BASE+['shell','pidof','com.dsh.client'],capture_output=True,text=True,timeout=10).stdout.strip()
        current = phase()
        if pid_result and str(evidence['oldPid']) not in pid_result.split():
            evidence['automaticNewPid'] = pid_result
        if i in (0,4,9,19) or current == 'ready': print('AFTER_KILL',pid_result,current,size(hash),flush=True)
        if current == 'ready': evidence['automaticCompleted']=True; break
    verify = subprocess.run(BASE+['shell','am','instrument','-w','-r','-e','mode','verify',RUNNER],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
    print(verify.stdout,flush=True)
    assert 'INSTRUMENTATION_RESULT: result=PASS' in verify.stdout and 'cleanup=FAILED' not in verify.stdout, '重建续传或清理失败'
    evidence['verification'] = verify.stdout
    folder = ROOT / 'app/build/optimization-evidence'
    folder.mkdir(exist_ok=True)
    (folder/'https-process.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
    print('PASS: actual HTTPS process restart and cleanup',flush=True)
if __name__ == '__main__': main()
