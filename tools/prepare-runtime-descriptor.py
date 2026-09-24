#!/usr/bin/env python3
"""独立运行时描述：APK UI 版本仅作为诊断来源，不参与受管运行时身份。"""
from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets'
fixed=['offline-rootfs.bin','offline-rootfs.version','dsh-runtime.bin','glibc-python.tar.gz','pnpm-runtime.bin','ubuntu-tools.bin','ca-certificates.crt','dns-compat.cjs','dsha-runtime-env.sh','dsha-builtin.txt','plugin-manager.py','plugin-lifecycle.py','plugin-semver.cjs','register-builtin-plugins.py','device-shell-policy.py','adb-shell.py','dsha-device-shell.sh','dsha-plugin.sh','install-ubuntu-tools.sh','startup-observer.cjs','startup-recovery.py','startup-checkpoints.py','agent-preset-patch.json','session-interaction-patch.json','composer-enter-patch.json','client-combo-patch.json','pdf-compat-patch.json','language-patch.json']
fixed.extend(['rc1-migration.py','rc1-settings-migration.cjs','rc1-settings-migration-patch.json','plugin-dependencies.py','plugin-transactions.py','backup-plugin-graph.py'])
paths=[assets/name for name in fixed]
paths.append(assets/'persona-compat-patch.json')
paths.append(assets/'models-navigation-patch.json')
paths.append(assets/'subagent-navigation-patch.json')
paths.extend(assets/name for name in ['plugin-manager-policy-patch.json','plugin-manager-navigation-patch.json'])
paths.append(assets/'office-fonts-patch.json')
paths.extend(assets/name for name in ['deepseek-messages-compat-patch.json','dsha-deepseek-messages-compat.js'])
paths.append(assets/'lexical-claim-patch.json')
paths.append(assets/'conversation-materialized-patch.json')
paths.extend(assets/name for name in ['runtime-trial-plugin.js','runtime-trial-page.js','profile-settings-review.js'])
for folder in ['web-integration','app-integration']:
 paths.extend(p for p in (assets/folder).rglob('*') if p.is_file())
for folder in (assets/'builtin-plugins').iterdir():
 if not folder.is_dir():continue
 for name in ['package.json','cordis.patch.yml','lib/index.js','lib/server.cjs','lib/client.js','lib/compress.js','lib/delete-session.js','LICENSE']:
  if (folder/name).is_file():paths.append(folder/name)
def digest(path):
 result=hashlib.sha256()
 with path.open('rb') as stream:
  for chunk in iter(lambda:stream.read(1024*1024),b''):result.update(chunk)
 return result.hexdigest()
inputs={p.relative_to(assets).as_posix():digest(p) for p in sorted(set(paths)) if p.is_file()}
launchers={}
for folder in ['app/src/main/jniLibs','app/src/low/jniLibs']:
 for path in sorted((root/folder).rglob('*.so')):launchers[path.relative_to(root).as_posix()]=digest(path)
for name in ['runtime/ProotBootstrap.java','runtime/ContainerRuntime.java','runtime/UserDataBindings.java','runtime/WebProcessManager.java','runtime/NativeProcess.java','runtime/RuntimeTools.java','runtime/RuntimeTrial.java','backup/RuntimeTrialRecords.java','util/WebProcSel.java','util/WebPidIdentity.java','util/ManagedRuntimeLayout.java','util/ManagedAssetVersion.java']:
 path=root/'app/src/main/java/com/deepseekharness/app'/name
 launchers[name]=digest(path)
version=json.loads((root/'tools/dsh-runtime/package.json').read_text(encoding='utf8'))['dependencies']['@deepseek-ai/dsh']
previous = ['dsh-0.1.7-rc.1'] if version == '0.1.7-rc.2' else []
contract={'baseVersion':(assets/'offline-rootfs.version').read_text().strip(),'dshVersion':version,'launcherContract':'DSHA_ARM64_V2','launcherInputs':launchers,'bridgeProtocol':2,'dataRead':previous + ['dsh-'+version],'dataWrite':'dsh-'+version,'inputs':inputs}
identity=hashlib.sha256(json.dumps(contract,sort_keys=True,separators=(',',':')).encode()).hexdigest()
output=dict(version=1,runtimeId=identity,**contract)
(assets/'runtime-descriptor.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print('runtime descriptor:',identity)
