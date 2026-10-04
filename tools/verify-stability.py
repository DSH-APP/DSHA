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
from test_runtime_fixture import runtime as verified_runtime
from release_acceptance import requirements as acceptance_requirements, validate as validate_acceptance, baseline_from_receipt
from release_names import apk_filename

ROOT=Path(__file__).resolve().parents[1]
CERT=json.loads((ROOT/'ci/release-identity.json').read_text(encoding='utf-8'))['certificateSha256']
PNPM_VERSION=json.loads((ROOT/'tools/runtime-tools.lock.json').read_text(encoding='utf-8'))['pnpm']['version']

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
        if path.is_file() and public_source_path(name) and (name.startswith(('app/src/','tools/','gradle/','ci/','.github/','agent-skills/','website/')) or name in ('app/build.gradle','build.gradle','settings.gradle','gradle.properties','build.sh','gradlew','gradlew.bat')):
            selected[name]=digest(path)
    for pattern in ('*.bin','*.sha256','*.version','*.layout'):
        for path in (ROOT/'app/src/main/assets').glob(pattern):selected[path.relative_to(ROOT).as_posix()]=digest(path)
    return selected


def public_source_path(name):
    """Keep private device evidence and unredacted historical originals out of exports."""
    parts=name.replace('\\','/').split('/')
    return 'private-device-audits' not in parts and 'history' not in parts


def validate_device_evidence(proof, apks):
    """Bind a separately reviewed, non-destructive device run to both built APKs."""
    if not isinstance(proof, dict) or not isinstance(apks, list):
        raise ValueError('DEVICE_EVIDENCE_FORMAT')
    variants={item['flavor']:item for item in apks}
    if set(variants)!={'standard','low'}:
        raise ValueError('DEVICE_EVIDENCE_FLAVORS')
    if proof.get('schema')!=1 or proof.get('package')!='com.dsh.client' or proof.get('certificateSha256')!=CERT \
            or proof.get('versionCode')!=variants['standard']['versionCode'] \
            or proof.get('versionCode')!=variants['low']['versionCode'] \
            or not isinstance(proof.get('serial'),str) or not proof['serial'].strip() \
            or proof.get('firstInstallTimePreserved') is not True or proof.get('nonDestructive') is not True:
        raise ValueError('DEVICE_EVIDENCE_IDENTITY')
    reports=proof.get('flavors')
    if not isinstance(reports,dict):
        raise ValueError('DEVICE_EVIDENCE_FLAVORS')
    required={'web-ready','existing-data-preserved','plugins-visible','recovery-ready','no-crash'}
    for flavor,item in variants.items():
        tested=reports.get(flavor)
        if not isinstance(tested,dict):
            raise ValueError('DEVICE_EVIDENCE_'+flavor.upper())
        checks=tested.get('checks')
        if tested.get('sha256')!=item['sha256'] or tested.get('result')!='PASS' \
                or not isinstance(checks,list) or not all(isinstance(value,str) for value in checks) \
                or not required.issubset(checks):
            raise ValueError('DEVICE_EVIDENCE_'+flavor.upper())

def publish_apks(apks):
    for item in apks:
        source=Path(item['path'])
        if not source.is_file() or digest(source)!=item['sha256']:
            raise ValueError('DELIVERY_APK_CHANGED')
        target=ROOT/'release'/apk_filename(item['flavor'], ROOT)
        target.parent.mkdir(parents=True,exist_ok=True)
        if not target.exists() or digest(target)!=item['sha256']:
            temporary=target.with_name(target.name+'.part-'+str(uuid.uuid4()))
            shutil.copyfile(source,temporary)
            if digest(temporary)!=item['sha256']:raise RuntimeError('Delivery copy mismatch')
            temporary.replace(target)
        target.with_suffix('.apk.sha256').write_text(item['sha256']+'  '+target.name+'\n',encoding='ascii',newline='\n')
        item['deliveredPath']=str(target)


def validate_software_receipt(report,current):
    if report.get('verificationSchema')!=2 or report.get('status') not in ('PASS_WITH_EXPLICIT_DEVICE_GAPS','PASS_FOR_EXECUTED_SCOPE'):
        raise ValueError('DELIVERY_SOFTWARE_RECEIPT')
    snapshot=report.get('sourceSnapshot',{})
    path=Path(snapshot.get('path',''))
    if not path.is_file() or digest(path)!=snapshot.get('sha256') \
            or json.loads(path.read_text(encoding='utf8'))!=current:
        raise ValueError('DELIVERY_SOURCE_CHANGED')
    required={'gradle-verification','runtime-input-contract','release-acceptance-contract',
              'apk-assets','standard-elf','low-elf','standard-signature','low-signature',
              'plugin-upgrade-gate','recovery-apk','recovery-browser-overlay','recovery-profile-boot','mobile-modal',
              'adb-flow','adb-vscreen-bridge','web-ui-host-fixtures','plugin-downloads',
              'third-party-notices','third-party-apk'}
    commands={row.get('name'):row for row in report.get('commands',[])}
    if not required.issubset(commands):raise ValueError('DELIVERY_CHECK_MISSING')
    for command in commands.values():
        log=Path(command.get('log',''))
        if command.get('exitCode')!=0 or not log.is_file() or digest(log)!=command.get('sha256'):
            raise ValueError('DELIVERY_CHECK_CHANGED')
    for flavor in ('Standard','Low'):
        counts=report.get('junit',{}).get(flavor,{})
        if counts.get('tests',0)<=0 or counts.get('failures')!=0 or counts.get('errors')!=0:
            raise ValueError('DELIVERY_JUNIT_INCOMPLETE')


def deliver_from_receipt(path,evidence,baseline):
    report=json.loads(path.read_text(encoding='utf8'))
    current=source_snapshot();validate_software_receipt(report,current)
    baseline_sources,provenance=baseline_from_receipt(baseline)
    selected=acceptance_requirements(baseline_sources,current,provenance=provenance)
    if selected!=report.get('acceptance'):raise ValueError('DELIVERY_ACCEPTANCE_CHANGED')
    proof=json.loads(evidence.read_text(encoding='utf8'))
    validate_device_evidence(proof,report['apks']);validate_acceptance(proof,selected)
    # Recheck exact APK bytes before replacing either prior local delivery.
    for item in report['apks']:
        if digest(Path(item['path']))!=item['sha256']:raise ValueError('DELIVERY_APK_CHANGED')
    publish_apks(report['apks'])
    report['status']='PASS_FOR_EXECUTED_SCOPE'
    report['softwareReceipt']={'path':str(path.resolve()),'sha256':digest(path)}
    report['device']={'status':'MATCHED_EXTERNAL_EVIDENCE','package':'com.dsh.client',
                      'evidence':str(evidence.resolve()),'sha256':digest(evidence)}
    report['deliveredAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    destination=path.parent/('delivery-'+str(uuid.uuid4())+'.json')
    destination.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps({'status':report['status'],'report':str(destination)},ensure_ascii=False))
    return 0

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', choices=('software','package','all'), default='all', help='software不需要发布密钥；package生成未签名完整包；all核验正式签名包')
    parser.add_argument('--java-home',type=Path,default=Path(os.environ.get('JAVA_HOME','F:/DSHA/_toolchains/jdk-17')))
    parser.add_argument('--sdk',type=Path,default=Path(os.environ.get('ANDROID_HOME','F:/DSHA/_toolchains/android-sdk')))
    parser.add_argument('--node',default=shutil.which('node') or 'node')
    parser.add_argument('--test-forks',type=int,choices=range(1,5),default=1,help='每个 flavor 的独立 JUnit worker 数（1–4）；测试与新鲜报告要求不变')
    parser.add_argument('--device',help='已停用旧审计包流程；真机改用正式 Release 覆盖安装验收')
    parser.add_argument('--deliver',action='store_true',help='所有本轮所选检查通过后，本地复制 APK 与校验码到 release')
    parser.add_argument('--device-evidence',type=Path,help='正式 APK 非破坏性真机覆盖的独立验收 JSON；--deliver 必需')
    parser.add_argument('--baseline-receipt',type=Path,help='上一轮正式交付通过回执；核对其中源码摘要后按实际改动选择设备必验行为')
    parser.add_argument('--deliver-from',type=Path,help='在源码、检查日志和 APK 字节均未变化时复用本轮软件通过回执，补真机证据后交付')
    args=parser.parse_args()
    if args.stage!='all' and (args.deliver or args.deliver_from):
        parser.error('只有正式签名和设备证据完整的all阶段可以交付')
    if args.device:
        parser.error('不再生成或安装额外测试 APK。请先用同签名正式 Release 覆盖安装并完成非破坏性检查，再使用 --deliver 替换同版本交付。')
    if args.deliver_from:
        if args.deliver or args.device_evidence is None or args.baseline_receipt is None:
            parser.error('--deliver-from 需 --device-evidence 和 --baseline-receipt，不能同时使用 --deliver')
        try:return deliver_from_receipt(args.deliver_from,args.device_evidence,args.baseline_receipt)
        except (OSError,ValueError,KeyError,TypeError) as error:
            print(json.dumps({'status':'INCOMPLETE_OR_FAILED','failure':str(error)},ensure_ascii=False));return 1
    if args.deliver and args.device_evidence is None:
        parser.error('--deliver 需要 --device-evidence，且证据必须绑定本轮两版正式 APK')
    if args.deliver and not args.device_evidence.is_file():
        parser.error('--device-evidence 文件不存在；请先完成正式 APK 非破坏性覆盖验收')
    if args.deliver and args.baseline_receipt is None:
        parser.error('--deliver 需要 --baseline-receipt，不能用固定五项代替本轮变更验收')
    report_dir=ROOT/'app/build/stability-acceptance'/str(uuid.uuid4());report_dir.mkdir(parents=True)
    report={'schema':1,'verificationSchema':2,'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'status':'RUNNING','commands':[],
        'commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        'dirty':bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT)),
        'device':{'status':'EXTERNAL_EVIDENCE_REQUIRED','package':'com.dsh.client'},
        'notVerified':['Android 6/7 physical devices','minimum standard API 30 physical device','reported Android 16 tablet','physical 16 KiB kernel','power loss durability'],
        'githubUploaded':False,'productionInstallationModified':False,'junitForksPerFlavor':args.test_forks}
    initial_source=source_snapshot()
    baseline_sources,provenance=baseline_from_receipt(args.baseline_receipt) if args.baseline_receipt else (None,None)
    acceptance=acceptance_requirements(baseline_sources,initial_source,provenance=provenance) if baseline_sources else None
    report['acceptance']=acceptance
    env=dict(os.environ,JAVA_HOME=str(args.java_home),ANDROID_HOME=str(args.sdk),DSHA_PYTHON=sys.executable,PYTHONUTF8='1')
    env.pop('PYTHONOPTIMIZE',None)
    if args.stage!='all':
        for key in ('DSHA_KEYSTORE','DSHA_KEYSTORE_PASSWORD','DSHA_KEY_PASSWORD','DSHA_KEY_ALIAS'):env.pop(key,None)
    java=args.java_home/'bin'/('java.exe' if os.name=='nt' else 'java')
    wrapper=[str(java),'-cp',str(ROOT/'gradle/wrapper/gradle-wrapper.jar'),'org.gradle.wrapper.GradleWrapperMain','--console=plain']
    def run(name,command,extra_env=None):
        target=report_dir/(name+'.log');current=dict(env);current.update(extra_env or {});current.pop('PYTHONOPTIMIZE',None)
        with target.open('wb') as log:
            try:code=subprocess.run([str(value) for value in command],cwd=ROOT,env=current,stdout=log,stderr=subprocess.STDOUT,check=False).returncode
            except OSError as error:log.write(str(error).encode());code=-1
        report['commands'].append({'name':name,'argv':[str(v) for v in command],'exitCode':code,'log':str(target),'sha256':digest(target)})
        print(json.dumps({'phase':name,'exitCode':code,'log':str(target)},ensure_ascii=False),flush=True)
        if code:raise RuntimeError(name+' failed; see '+str(target))
        return target.read_text(encoding='utf8',errors='replace')
    try:
        if not java.is_file():raise RuntimeError('JDK is unavailable')
        key=env.get('DSHA_KEYSTORE','')
        signed=bool(key and Path(key).is_file()) and args.stage=='all'
        report['tools']={'java':run('java-version',[java,'-version']).strip(),'node':run('node-version',[args.node,'--version']).strip(),
                         'python':sys.version,'gradle':run('gradle-version',wrapper+['--version'])}
        escaped=str(report_dir).replace('\\','/').replace("'","\\'")
        init=report_dir/'tests.init.gradle'
        init.write_text("gradle.projectsEvaluated { rootProject.allprojects { p -> p.tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { t ->\n"
                        "t.outputs.upToDateWhen { false }; t.outputs.cacheIf { false }; t.reports.junitXml.required.set(true)\n"
                        f"t.maxParallelForks = {args.test_forks}\n"
                        f"t.reports.junitXml.outputLocation.set(new File('{escaped}/junit',t.name))\n"
                        "} } }\n",encoding='utf8')
        tasks=[':app:testStandardDebugUnitTest',':app:testLowDebugUnitTest',':app:lintStandardRelease',':app:lintLowRelease']
        if signed or args.stage=='package':tasks+=[':app:assembleStandardRelease',':app:assembleLowRelease']
        if args.stage=='package':wrapper+=['-Pdsha.unsignedPackage=true']
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
        if built_source!=initial_source:
            raise RuntimeError('Tracked source or assets changed during Gradle verification; inspect generated descriptors and rerun')
        run('startup-observer',[args.node,'tools/test-startup-diagnostics.mjs'])
        run('issue67',[args.node,'tools/test-issue67-startup.mjs'])
        run('runtime-input-contract',[sys.executable,'-B','tools/test-runtime-input-contract.py'])
        run('architecture-boundaries',[sys.executable,'-B','tools/test-architecture-boundaries.py'])
        run('plugin-downloads',[sys.executable,'-B','tools/test-plugin-downloads.py'])
        run('release-acceptance-contract',[sys.executable,'-B','tools/test-release-acceptance.py'])
        run('release-evidence-contract',[sys.executable,'-B','tools/test-release-evidence.py'])
        run('adb-flow',[sys.executable,'-B','tools/test-adb-flow.py'])
        run('adb-vscreen-bridge',[sys.executable,'-B','tools/test-adb-vscreen-bridge.py'])
        run('third-party-notices',[sys.executable,'-B','tools/check-third-party-notices.py',
                                   '--archive','app/src/main/assets/dsh-runtime.bin',
                                   '--output',report_dir/'third-party-notices.json'])
        pnpm=ROOT/f'app/build/stability-tools/pnpm-{PNPM_VERSION}';prefix='usr/local/lib/dsha-pnpm/'
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
        if actual!=PNPM_VERSION:raise RuntimeError('Unexpected pnpm version')
        report['pythonTests']={}
        for suite in ('discovery','dependencies','transactions','review'):
            log=run('plugin-'+suite,[sys.executable,'-B','tools/test-plugin-'+suite+'.py'],{'DSHA_TEST_PNPM_CLI':str(cli),'DSHA_TEST_NODE':args.node})
            count=re.search(r'Ran (\d+) tests?',log);skipped=re.search(r'OK \(skipped=(\d+)\)',log)
            report['pythonTests'][suite]={'tests':int(count[1]) if count else None,'skipped':int(skipped[1]) if skipped else 0}
        if args.stage=='software':
            final_source=source_snapshot()
            if initial_source!=final_source:raise RuntimeError('Software inputs changed during verification')
            source_file=report_dir/'source-sha256.json'
            source_file.write_text(json.dumps(final_source,ensure_ascii=False,sort_keys=True,indent=2),encoding='utf8')
            report['sourceSnapshot']={'path':str(source_file),'sha256':digest(source_file)}
            report['status']='PASS_SOFTWARE'
            report['signature']={'status':'NOT_REQUESTED'}
            report['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
            target=report_dir/'manifest.json';target.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
            print(json.dumps({'status':report['status'],'report':str(target)},ensure_ascii=False));return 0
        if not signed and args.stage!='package':raise RuntimeError('Software checks completed, but historical DSHA_KEYSTORE is unavailable. No substitute key or signed APK was generated')
        apks=[ROOT/f'app/build/outputs/apk/{v}/release/app-{v}-release{"" if signed else "-unsigned"}.apk' for v in ('standard','low')]
        # Always compare the APK against the currently locked raw runtime fixture.
        try:
            locked_runtime=verified_runtime('raw',full=True,require_current_archive=True)
        except (OSError,ValueError,KeyError,TypeError) as error:
            raise RuntimeError('Current locked DSH runtime fixture is unavailable; run tools/prepare-test-runtime.py: '+str(error)) from error
        run('apk-assets',[sys.executable,'-B','tools/verify-dsh-upgrade-apk.py',*apks],{'DSHA_TEST_RUNTIME':str(locked_runtime)})
        run('third-party-apk',[sys.executable,'-B','tools/verify-third-party-apk.py',*apks,
                              '--output',report_dir/'third-party-apk.json'])
        build_tools=args.sdk/'build-tools/36.0.0';report['apks']=[]
        for flavor,apk,minimum in zip(('standard','low'),apks,(30,23)):
            run(flavor+'-elf',[sys.executable,'-B','tools/audit-standard-apk.py',apk,'--report',report_dir/(flavor+'-elf.json')])
            if signed:
                signature=run(flavor+'-signature',[java,'-jar',build_tools/'lib/apksigner.jar','verify','--min-sdk-version','23','--verbose','--print-certs',apk])
                if re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)',signature)!=[CERT]:raise RuntimeError('Signing certificate mismatch')
                for scheme in ('v1','v2','v3'):
                    if f'Verified using {scheme} scheme' not in signature or not re.search(r'Verified using '+scheme+r' scheme[^\n]+true',signature):raise RuntimeError('APK lacks verified '+scheme+' signing')
            badging=run(flavor+'-manifest',[build_tools/('aapt.exe' if os.name=='nt' else 'aapt'),'dump','badging',apk])
            package=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badging)
            if not package or package[1]!='com.dsh.client' or 'application-debuggable' in badging or f"sdkVersion:'{minimum}'" not in badging or "native-code: 'arm64-v8a'" not in badging:raise RuntimeError('Unexpected release manifest')
            report['apks'].append({'flavor':flavor,'path':str(apk),'package':package[1],'versionCode':int(package[2]),'versionName':package[3],
                                  'minSdk':minimum,'abi':['arm64-v8a'],'size':apk.stat().st_size,'sha256':digest(apk),'certificateSha256':CERT if signed else None})
        try:
            managed_runtime=verified_runtime('managed',full=True,require_current_archive=True)
        except (OSError,ValueError,KeyError,TypeError) as error:
            raise RuntimeError('Current managed DSH runtime fixture is unavailable; run tools/prepare-test-runtime.py: '+str(error)) from error
        run('plugin-upgrade-gate',[sys.executable,'-B','tools/verify-plugin-upgrade-gate.py',
                                   '--managed-runtime',managed_runtime,'--raw-runtime',locked_runtime,*apks])
        run('recovery-apk',[sys.executable,'-B','tools/verify-recovery-apk.py',*apks])
        run('recovery-browser-overlay',[args.node,'tools/test-recovery-browser-overlay.mjs',ROOT/'app/build/generated/recoveryAssets'])
        run('web-ui-host-fixtures',[args.node,'tools/test-web-ui-host-fixtures.mjs'])
        run('recovery-profile-boot',[args.node,'tools/test-recovery-profile-boot.mjs'],{'DSHA_TEST_RUNTIME':str(locked_runtime)})
        run('mobile-modal',[args.node,'tools/test-mobile-modal-behavior.mjs'],{'DSHA_TEST_RUNTIME':str(locked_runtime)})
        run('mobile-auth',[args.node,'tools/test-mobile-auth.mjs'])
        run('mobile-update',[args.node,'tools/test-mobile-update.mjs'])
        final_source=source_snapshot()
        if built_source!=final_source:raise RuntimeError('Source or assets changed after verification; rerun affected checks')
        source_file=report_dir/'source-sha256.json';source_file.write_text(json.dumps(final_source,ensure_ascii=False,sort_keys=True,indent=2),encoding='utf8')
        report['sourceSnapshot']={'path':str(source_file),'sha256':digest(source_file)}
        patch_file=report_dir/'workspace.patch';patch_file.write_bytes(subprocess.check_output([
            'git','diff','--binary','--','.',':(exclude)tools/history/**',
            ':(exclude)docs/history/**',':(exclude)tools/**/private-device-audits/**'],cwd=ROOT))
        report['patch']={'path':str(patch_file),'sha256':digest(patch_file)}
        changed=set(subprocess.check_output(['git','diff','--name-only','-z'],cwd=ROOT).decode().split('\0')+subprocess.check_output(['git','ls-files','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0'))-{''}
        snapshot=report_dir/'source-changes.zip'
        with zipfile.ZipFile(snapshot,'x',zipfile.ZIP_DEFLATED) as archive:
            for name in sorted(changed):
                path=ROOT/name
                if path.is_file() and public_source_path(name) and (name in final_source or name.startswith('docs/') or name in ('README.md','README.en.md','BUILD.md','AGENTS.md','CHANGELOG.md','THIRD_PARTY_NOTICES.md')):
                    archive.write(path,name)
        report['sourceChanges']={'path':str(snapshot),'sha256':digest(snapshot),'scope':'Changed public source and redacted current documentation; private device audits and historical originals are excluded; offline binary assets remain in the workspace and are identified by hashes'}
        if args.deliver:
            proof=json.loads(args.device_evidence.read_text(encoding='utf8'))
            validate_device_evidence(proof,report['apks'])
            validate_acceptance(proof,acceptance)
            report['device']={'status':'MATCHED_EXTERNAL_EVIDENCE','package':'com.dsh.client',
                              'evidence':str(args.device_evidence.resolve()),'sha256':digest(args.device_evidence)}
            publish_apks(report['apks'])
        report['status']='PASS_UNSIGNED_PACKAGE' if args.stage=='package' else 'PASS_FOR_EXECUTED_SCOPE' if args.deliver else 'PASS_WITH_EXPLICIT_DEVICE_GAPS'
    except Exception as error:
        report['status']='INCOMPLETE_OR_FAILED';report['failure']=str(error)
    report['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    target=report_dir/'manifest.json';target.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps({'status':report['status'],'report':str(target)},ensure_ascii=False))
    return 1 if report['status']=='INCOMPLETE_OR_FAILED' else 0

if __name__=='__main__':sys.exit(main())
