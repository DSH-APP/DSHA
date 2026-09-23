#!/usr/bin/env python3
"""本地稳定性验收：保留新鲜报告，传播失败，不发布、不上传、不覆盖正式安装。"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
CERT='e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5'

def digest(path):
    value=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):value.update(block)
    return value.hexdigest()

def source_snapshot():
    names=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
    selected={}
    for name in sorted(set(names)-{''}):
        path=ROOT/name
        if path.is_file() and (name.startswith(('app/src/','tools/','gradle/')) or name in ('app/build.gradle','build.gradle','settings.gradle','gradle.properties','build.sh','gradlew','gradlew.bat')):
            selected[name]=digest(path)
    for pattern in ('*.bin','*.sha256','*.version','*.layout'):
        for path in (ROOT/'app/src/main/assets').glob(pattern):selected[path.relative_to(ROOT).as_posix()]=digest(path)
    return selected

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home',type=Path,default=Path(os.environ.get('JAVA_HOME','F:/DSHA/_toolchains/jdk-17')))
    parser.add_argument('--sdk',type=Path,default=Path(os.environ.get('ANDROID_HOME','F:/DSHA/_toolchains/android-sdk')))
    parser.add_argument('--node',default=shutil.which('node') or 'node')
    parser.add_argument('--device',help='已停用旧审计包流程；真机改用正式 Release 覆盖安装验收')
    parser.add_argument('--device-modes',default='core,runtime,network,bridge,credential,plugins,plugin_workflow,plugin_recovery,retained,workflow,io,ui')
    parser.add_argument('--deliver',action='store_true',help='所有本轮所选检查通过后，本地复制 APK 与校验码到 release')
    args=parser.parse_args()
    if args.device:
        parser.error('不再生成或安装额外测试 APK。请先用同签名正式 Release 覆盖安装并完成非破坏性检查，再使用 --deliver 替换同版本交付。')
    report_dir=ROOT/'app/build/stability-acceptance'/str(uuid.uuid4());report_dir.mkdir(parents=True)
    report={'schema':1,'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'status':'RUNNING','commands':[],
        'commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        'dirty':bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT)),
        'device':{'status':'NOT_REQUESTED' if not args.device else 'PENDING','package':'com.dsh.client.stabilityaudit'},
        'notVerified':['Android 6/7 physical devices','minimum standard API 30 physical device','reported Android 16 tablet','physical 16 KiB kernel','power loss durability'],
        'githubUploaded':False,'productionInstallationModified':False}
    env=dict(os.environ,JAVA_HOME=str(args.java_home),ANDROID_HOME=str(args.sdk),DSHA_PYTHON=sys.executable,PYTHONUTF8='1')
    java=args.java_home/'bin'/('java.exe' if os.name=='nt' else 'java')
    wrapper=[str(java),'-cp',str(ROOT/'gradle/wrapper/gradle-wrapper.jar'),'org.gradle.wrapper.GradleWrapperMain','--console=plain']
    def run(name,command,extra_env=None):
        target=report_dir/(name+'.log');current=dict(env);current.update(extra_env or {})
        with target.open('wb') as log:
            try:code=subprocess.run([str(value) for value in command],cwd=ROOT,env=current,stdout=log,stderr=subprocess.STDOUT,check=False).returncode
            except OSError as error:log.write(str(error).encode());code=-1
        report['commands'].append({'name':name,'argv':[str(v) for v in command],'exitCode':code,'log':str(target)})
        print(json.dumps({'phase':name,'exitCode':code,'log':str(target)},ensure_ascii=False),flush=True)
        if code:raise RuntimeError(name+' failed; see '+str(target))
        return target.read_text(encoding='utf8',errors='replace')
    try:
        if not java.is_file():raise RuntimeError('JDK is unavailable')
        key=env.get('DSHA_KEYSTORE','')
        signed=bool(key and Path(key).is_file())
        report['tools']={'java':run('java-version',[java,'-version']).strip(),'node':run('node-version',[args.node,'--version']).strip(),
                         'python':sys.version,'gradle':run('gradle-version',wrapper+['--version'])}
        escaped=str(report_dir).replace('\\','/').replace("'","\\'")
        init=report_dir/'tests.init.gradle'
        init.write_text("gradle.projectsEvaluated { rootProject.allprojects { p -> p.tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { t ->\n"
                        "t.outputs.upToDateWhen { false }; t.outputs.cacheIf { false }; t.reports.junitXml.required.set(true)\n"
                        f"t.reports.junitXml.outputLocation.set(new File('{escaped}/junit',t.name))\n"
                        "} } }\n",encoding='utf8')
        tasks=[':app:testStandardDebugUnitTest',':app:testLowDebugUnitTest',':app:lintStandardRelease',':app:lintLowRelease']
        if signed:tasks+=[':app:assembleStandardRelease',':app:assembleLowRelease']
        run('gradle-verification',wrapper+['--init-script',init,*tasks])
        report['junit']={}
        for variant in ('Standard','Low'):
            counts=dict(tests=0,failures=0,errors=0,skipped=0);xmls=list((report_dir/'junit'/('test'+variant+'DebugUnitTest')).glob('*.xml'))
            if not xmls:raise RuntimeError('Missing fresh JUnit reports for '+variant)
            for xml in xmls:
                suite=ET.parse(xml).getroot()
                for key in counts:counts[key]+=int(suite.get(key,0))
            if counts['failures'] or counts['errors']:raise RuntimeError('JUnit failure')
            report['junit'][variant]=counts
        built_source=source_snapshot()
        run('startup-observer',[args.node,'tools/test-startup-diagnostics.mjs'])
        run('issue67',[args.node,'tools/test-issue67-startup.mjs'])
        pnpm=ROOT/'app/build/stability-tools/pnpm-10.34.5';prefix='usr/local/lib/dsha-pnpm/'
        import tarfile
        with tarfile.open(ROOT/'app/src/main/assets/pnpm-runtime.bin','r:gz') as archive:
            for entry in archive:
                name=entry.name.removeprefix('./')
                if entry.isfile() and name.startswith(prefix):
                    target=pnpm/name[len(prefix):]
                    if not target.resolve().is_relative_to(pnpm.resolve()):raise RuntimeError('pnpm asset path')
                    target.parent.mkdir(parents=True,exist_ok=True);content=archive.extractfile(entry).read()
                    if target.exists() and target.read_bytes()!=content:raise RuntimeError('pnpm fixture changed')
                    if not target.exists():target.write_bytes(content)
        cli=pnpm/'dist/pnpm.cjs';actual=run('pnpm-version',[args.node,cli,'--version']).strip()
        if actual!='10.34.5':raise RuntimeError('Unexpected pnpm version')
        report['pythonTests']={}
        for suite in ('discovery','dependencies','transactions','review'):
            log=run('plugin-'+suite,[sys.executable,'-B','tools/test-plugin-'+suite+'.py'],{'DSHA_TEST_PNPM_CLI':str(cli),'DSHA_TEST_NODE':args.node})
            count=re.search(r'Ran (\d+) tests?',log);skipped=re.search(r'OK \(skipped=(\d+)\)',log)
            report['pythonTests'][suite]={'tests':int(count[1]) if count else None,'skipped':int(skipped[1]) if skipped else 0}
        if not signed:raise RuntimeError('Software checks completed, but historical DSHA_KEYSTORE is unavailable. No substitute key or signed APK was generated')
        apks=[ROOT/f'app/build/outputs/apk/{v}/release/app-{v}-release.apk' for v in ('standard','low')]
        # APK 里是 alpha.2 运行时；必须与同版本的原始锁定树比较。旧 rc2 树会把
        # alpha.2 官方前端自身的变化误报成受管补丁越界。
        locked_runtime=ROOT/'app/build/locked-dsh-runtime-017'
        locked_package=locked_runtime/'node_modules/@deepseek-ai/dsh/package.json'
        expected_dsh=json.loads((ROOT/'tools/dsh-runtime/package.json').read_text(encoding='utf8'))['dependencies']['@deepseek-ai/dsh']
        if not locked_package.is_file() or json.loads(locked_package.read_text(encoding='utf8')).get('version')!=expected_dsh:
            raise RuntimeError('Locked current DSH runtime is unavailable')
        run('apk-assets',[sys.executable,'-B','tools/verify-dsh-upgrade-apk.py',*apks],{'DSHA_TEST_RUNTIME':str(locked_runtime)})
        build_tools=args.sdk/'build-tools/36.0.0';report['apks']=[]
        for flavor,apk,minimum in zip(('standard','low'),apks,(30,23)):
            run(flavor+'-elf',[sys.executable,'-B','tools/audit-standard-apk.py',apk,'--report',report_dir/(flavor+'-elf.json')])
            signature=run(flavor+'-signature',[java,'-jar',build_tools/'lib/apksigner.jar','verify','--verbose','--print-certs',apk])
            if re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)',signature)!=[CERT]:raise RuntimeError('Signing certificate mismatch')
            if flavor=='low' and 'Verified using v1 scheme (JAR signing): true' not in signature:raise RuntimeError('Low APK lacks verified V1 signing')
            badging=run(flavor+'-manifest',[build_tools/('aapt.exe' if os.name=='nt' else 'aapt'),'dump','badging',apk])
            package=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badging)
            if not package or package[1]!='com.dsh.client' or 'application-debuggable' in badging or f"sdkVersion:'{minimum}'" not in badging or "native-code: 'arm64-v8a'" not in badging:raise RuntimeError('Unexpected release manifest')
            report['apks'].append({'flavor':flavor,'path':str(apk),'package':package[1],'versionCode':int(package[2]),'versionName':package[3],
                                  'minSdk':minimum,'abi':['arm64-v8a'],'size':apk.stat().st_size,'sha256':digest(apk),'certificateSha256':CERT})
        if args.device:
            run('build-device-audit',wrapper+['--init-script','tools/device-backup-audit.init.gradle','-Pdsha.auditPackage=com.dsh.client.stabilityaudit',':app:assembleStandardDeviceAudit',':app:assembleLowDeviceAudit'])
            report['device']['runs']=[]
            for flavor in ('standard','low'):
                apk=ROOT/f'app/build/outputs/apk/{flavor}/deviceAudit/app-{flavor}-deviceAudit.apk'
                for mode in args.device_modes.split(','):
                    log=run(flavor+'-device-'+mode,[sys.executable,'-B','tools/run-backup-device-audit.py','--serial',args.device,'--package','com.dsh.client.stabilityaudit','--apk',apk,'--mode',mode])
                    matches=re.findall(r'^\{"status": "PASS", "report": (.+)\}$',log,re.M)
                    if not matches:raise RuntimeError('Missing device PASS report')
                    report['device']['runs'].append(json.loads(matches[-1]))
            report['device']['status']='PASS_FOR_SELECTED_DEVICE_AND_MODES'
        final_source=source_snapshot()
        if built_source!=final_source:raise RuntimeError('Source or assets changed after verification; rerun affected checks')
        source_file=report_dir/'source-sha256.json';source_file.write_text(json.dumps(final_source,ensure_ascii=False,sort_keys=True,indent=2),encoding='utf8')
        report['sourceSnapshot']={'path':str(source_file),'sha256':digest(source_file)}
        patch_file=report_dir/'workspace.patch';patch_file.write_bytes(subprocess.check_output(['git','diff','--binary'],cwd=ROOT))
        report['patch']={'path':str(patch_file),'sha256':digest(patch_file)}
        changed=set(subprocess.check_output(['git','diff','--name-only','-z'],cwd=ROOT).decode().split('\0')+subprocess.check_output(['git','ls-files','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0'))-{''}
        snapshot=report_dir/'source-changes.zip'
        with zipfile.ZipFile(snapshot,'x',zipfile.ZIP_DEFLATED) as archive:
            for name in sorted(changed):
                path=ROOT/name
                if path.is_file() and (name in final_source or name.startswith('docs/') or name in ('README.md','README.en.md','BUILD.md','AGENTS.md','CHANGELOG.md','THIRD_PARTY_NOTICES.md')):
                    archive.write(path,name)
        report['sourceChanges']={'path':str(snapshot),'sha256':digest(snapshot),'scope':'Changed tracked and untracked source/documentation; offline binary assets remain in the workspace and are identified by hashes'}
        if args.deliver:
            for item in report['apks']:
                base=item['versionName'].removesuffix('low');suffix='low' if item['flavor']=='low' else ''
                target=ROOT/'release'/f"dsha-{base}{suffix}.apk"
                if not target.exists() or digest(target)!=item['sha256']:
                    temporary=target.with_name(target.name+'.part-'+str(uuid.uuid4()));shutil.copyfile(item['path'],temporary)
                    if digest(temporary)!=item['sha256']:raise RuntimeError('Delivery copy mismatch')
                    temporary.replace(target)
                checksum=target.with_suffix('.apk.sha256');value=item['sha256']+'  '+target.name+'\n'
                checksum.write_text(value,encoding='ascii',newline='\n');item['deliveredPath']=str(target)
        report['status']='PASS_WITH_EXPLICIT_DEVICE_GAPS' if not args.device else 'PASS_FOR_EXECUTED_SCOPE'
    except Exception as error:
        report['status']='INCOMPLETE_OR_FAILED';report['failure']=str(error)
    report['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    target=report_dir/'manifest.json';target.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps({'status':report['status'],'report':str(target)},ensure_ascii=False))
    return 1 if report['status']=='INCOMPLETE_OR_FAILED' else 0

if __name__=='__main__':sys.exit(main())
