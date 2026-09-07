"""给调试包 Instrumentation 发送一次测试命令，正式 APK 不包含该接收端。"""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time

ADB = r"F:\DSHA\_toolchains\android-sdk\platform-tools\adb.exe"
parser = argparse.ArgumentParser()
parser.add_argument("id")
parser.add_argument("--script", type=Path)
parser.add_argument("--finish", action="store_true")
args = parser.parse_args()
assert re.fullmatch(r"[a-zA-Z0-9_-]{1,40}", args.id)
job = {"id": args.id}
if args.finish:
    job["finish"] = True
else:
    job["script"] = args.script.read_text(encoding="utf-8")
data = json.dumps(job, ensure_ascii=False).encode("utf-8")
folder = "cache/functional-audit/"
command = ("umask 077; head -c " + str(len(data)) + " > " + folder + "command.tmp && mv "
           + folder + "command.tmp " + folder + "command.json")
subprocess.run([ADB, "shell", "-T", "run-as com.dsh.client sh -c " + shlex.quote(command)],
               input=data, capture_output=True, timeout=10, check=True)
deadline = time.monotonic() + 40
while time.monotonic() < deadline:
    result = subprocess.run([ADB, "exec-out", "run-as com.dsh.client sh -c "
                             + shlex.quote("cat " + folder + args.id + ".json")],
                            capture_output=True, timeout=8)
    if result.returncode == 0 and result.stdout.lstrip().startswith(b"{"):
        dest = Path(__file__).resolve().parents[1] / "app/build/functional-evidence" / (args.id + ".json")
        dest.parent.mkdir(exist_ok=True)
        dest.write_bytes(result.stdout)
        print(result.stdout.decode("utf-8"))
        break
    time.sleep(.3)
else:
    raise SystemExit("Instrumentation 未返回；先检查当前任务状态，勿重放已派发操作")
