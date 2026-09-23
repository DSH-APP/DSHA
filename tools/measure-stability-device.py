#!/usr/bin/env python3
"""只读采样审计进程 PSS、设备电池和前台状态；不重置统计、不改变充电/电源设置。"""
import argparse,datetime,json,os,re,subprocess,time,uuid
from pathlib import Path
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--package',choices=['com.dsh.client.rc21audit','com.dsh.client.stabilityaudit'],default='com.dsh.client.rc21audit')
parser.add_argument('--seconds',type=int,default=30)
parser.add_argument('--scenario',default='foreground-idle-usb')
args=parser.parse_args()
if not 1<=args.seconds<=600:parser.error('seconds must be in 1..600')
sdk=Path(os.environ.get('ANDROID_HOME','F:/DSHA/_toolchains/android-sdk'));adb=[str(sdk/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb')),'-s',args.serial,'shell']
def read(*command):
    result=subprocess.run(adb+list(command),stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf8',errors='replace',timeout=15)
    return result.stdout.strip()
owned=root/'app/build/stability-measurements'/str(uuid.uuid4());owned.mkdir(parents=True)
record={'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'package':args.package,'scenario':args.scenario,
        'device':read('getprop','ro.product.model'),'api':read('getprop','ro.build.version.sdk'),'kernel':read('uname','-a'),'pageSize':read('getconf','PAGESIZE'),
        'scope':'host process PSS only; battery current/charge is device-wide and USB charging prevents app energy attribution; no performance gain is inferred','samples':[]}
package_uid=re.search(r'package:'+re.escape(args.package)+r' uid:(\d+)',read('cmd','package','list','packages','-U',args.package))
record['uid']=int(package_uid[1]) if package_uid else None
record['processScope']='Per-process PSS for this audit UID, including Node when observable. Isolated browser renderers with another UID are not attributed. Values are not summed or interpreted as an optimization gain.'
until=time.monotonic()+args.seconds
while True:
    memory=read('dumpsys','meminfo',args.package);battery=read('dumpsys','battery');power=read('dumpsys','power')
    pss=re.search(r'TOTAL PSS:\s*(\d+)',memory)
    if not pss:pss=re.search(r'^\s*TOTAL\s+(\d+)\s',memory,re.M)
    processes=[]
    for line in read('ps','-A','-o','PID,UID,NAME').splitlines():
        fields=line.split(None,2)
        if len(fields)!=3 or not fields[0].isdigit() or not fields[1].isdigit() or int(fields[1])!=record['uid']:continue
        detail=read('dumpsys','meminfo',fields[0]);value=re.search(r'TOTAL PSS:\s*(\d+)',detail) or re.search(r'^\s*TOTAL\s+(\d+)\s',detail,re.M)
        processes.append({'pid':int(fields[0]),'name':fields[2],'pssKiB':int(value[1]) if value else None})
    record['samples'].append({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'hostPssKiB':int(pss[1]) if pss else None,'auditUidProcesses':processes,
        'battery':battery,'wakefulness':next((line.strip() for line in power.splitlines() if 'mWakefulness=' in line),''),
        'currentNow':read('cat','/sys/class/power_supply/battery/current_now'),'voltageNow':read('cat','/sys/class/power_supply/battery/voltage_now')})
    if time.monotonic()>=until:break
    time.sleep(min(5,max(0,until-time.monotonic())))
target=owned/'measurement.json';target.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps({'report':str(target),'samples':len(record['samples']),'scope':record['scope']},ensure_ascii=False))
