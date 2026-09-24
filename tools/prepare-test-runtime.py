#!/usr/bin/env python3
"""建立可复现 raw/managed 测试输入；不会生成 APK 或改写发布归档。"""
import argparse, hashlib, importlib.util, json, os, platform, shutil, subprocess, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]; ASSETS=ROOT/'app/src/main/assets'; STORE=ROOT/'app/build/test-runtimes'

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()
def write(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
def files_under(root, suffix=None):
    for base, dirs, names in os.walk(root, topdown=True, followlinks=False):
        dirs[:] = [name for name in dirs if not (Path(base)/name).is_symlink()]
        for name in names:
            file=Path(base)/name
            if file.is_symlink() or not file.is_file(): continue
            if suffix is None or file.suffix==suffix or file.name=='package.json': yield file

def load_builder():
    spec=importlib.util.spec_from_file_location('runtime_builder',ROOT/'tools/build-dsh-runtime.py');builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder);return builder

def prepare(args):
    npm=shutil.which('npm.cmd') or shutil.which('npm');node=shutil.which('node')
    if not npm or not node:raise RuntimeError('需要 Node 24/npm')
    host=json.loads(subprocess.check_output([node,'-e','process.stdout.write(JSON.stringify({os:process.platform,cpu:process.arch}))'],text=True))
    target=host if args.platform=='host' else dict(os='linux',cpu='arm64')
    lock=ROOT/'tools/dsh-runtime/package-lock.json';package=ROOT/'tools/dsh-runtime/package.json';version=json.loads(package.read_text())['dependencies']['@deepseek-ai/dsh'];locksha=sha(lock)
    base=STORE/(version+'-'+target['os']+'-'+target['cpu']+'-'+locksha[:12]);raw=base/'raw';base.mkdir(parents=True,exist_ok=True)
    marker=raw/'dsha-test-runtime.json'
    if not marker.exists():
        raw.mkdir(exist_ok=True)
        for name in ('package.json','package-lock.json'):shutil.copyfile(ROOT/'tools/dsh-runtime'/name,raw/name)
        command=[npm,'ci','--prefix',str(raw),'--ignore-scripts','--no-audit','--no-fund','--os='+target['os'],'--cpu='+target['cpu'],'--loglevel=error']
        if target['os']=='linux':command+=['--libc=glibc']
        if args.offline:command+=['--offline']
        if args.cache:command+=['--cache',str(args.cache.resolve())]
        print('安装 raw（npm 校验锁文件 integrity，禁用脚本）:',raw,flush=True);subprocess.run(command,check=True)
    actual=json.loads((raw/'node_modules/@deepseek-ai/dsh/package.json').read_text())['version']
    if actual!=version or sha(raw/'package-lock.json')!=locksha:raise ValueError('RAW_LOCK_OR_VERSION_MISMATCH')
    builder=load_builder();recipe=builder.recipe_inputs();archive=ASSETS/'dsh-runtime.bin';archive_hash=sha(archive)
    archive_proof=json.loads((ASSETS/'dsh-runtime.inputs.json').read_text())
    archive_current=archive_proof.get('archive_sha256')==archive_hash and archive_proof.get('inputs')==recipe
    if not archive_current and not args.allow_stale_archive:raise ValueError('ARCHIVE_INPUTS_STALE: 请先由发布执行者生成当前归档；开发夹具可明确 --allow-stale-archive')
    integrity={name:row['integrity'] for name,row in json.loads(lock.read_text())['packages'].items() if 'integrity' in row}
    raw_proof=dict(version=1,kind='raw',dshVersion=version,platform=target,lockSha256=locksha,packageSha256=sha(package),tarballIntegrities=integrity,installation='npm-ci-ignore-scripts',archiveSha256=archive_hash,archiveCurrent=archive_current)
    raw_proof['moduleHashes']={file.relative_to(raw).as_posix():sha(file) for file in files_under(raw/'node_modules/@deepseek-ai') if file.suffix=='.js' or file.name=='package.json'}
    write(marker,raw_proof)
    managed=base/('managed-'+uuid.uuid4().hex[:12]);print('复制并准备 managed:',managed,flush=True);shutil.copytree(raw,managed,symlinks=True)
    modules=managed/'node_modules'
    # 与发布归档共用精确源码转换，不从旧版安装树猜测新运行时。
    for file in files_under(modules, '.js'):
        if file.is_symlink() or not file.is_file():continue
        original=file.read_bytes();patched=builder.patched_content(file.relative_to(modules),original)
        if patched!=original:file.write_bytes(patched)
    for folder,name in [('runtime-fs','dsha-runtime-fs'),('session-compat','dsha-session-compat'),('client-combo-cache','dsha-client-combo-cache')]:
        shutil.copytree(ASSETS/folder,modules/name,dirs_exist_ok=True)
    overlays={}
    builder_owned = {'deepseek-messages-compat-patch.json','lexical-claim-patch.json','conversation-materialized-patch.json','client-combo-patch.json'}
    for file in sorted(ASSETS.glob('*-patch.json')):
        spec=json.loads(file.read_text(encoding='utf8'))
        if file.name in builder_owned: continue
        if spec.get('dshVersion')!=version or not spec.get('module') or not isinstance(spec.get('patches'),list):continue
        targetfile=modules/spec['module']
        if not targetfile.is_file():raise ValueError('OVERLAY_MODULE_MISSING:'+spec['module'])
        source=targetfile.read_text(encoding='utf8')
        for patch in spec['patches']:
            before,after=patch['before'],patch['after']
            if 'prependAsset' in patch:after=(ASSETS/patch['prependAsset']).read_text(encoding='utf8')+'\n'+after
            if source.count(after)==1 and source.count(before)==0:continue
            if source.count(before)!=1:raise ValueError('OVERLAY_ANCHOR_MISMATCH:'+file.name)
            source=source.replace(before,after)
        targetfile.write_text(source,encoding='utf8');overlays[str(file.relative_to(ROOT)).replace(os.sep,'/')]=sha(file)
    proof=dict(raw_proof,kind='managed',archiveRecipeInputs=recipe,overlayInputs=overlays)
    proof['moduleHashes']={file.relative_to(managed).as_posix():sha(file) for file in files_under(modules/'@deepseek-ai') if file.suffix=='.js' or file.name=='package.json'}
    write(managed/'dsha-test-runtime.json',proof)
    pointer=dict(version=1,dshVersion=version,platform=target,raw=str(raw),managed=str(managed),archiveCurrent=archive_current)
    write(STORE/'current.json',pointer)
    print(json.dumps(pointer,ensure_ascii=False,indent=2),flush=True)

if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--platform',choices=['host','linux-arm64'],default='host');ap.add_argument('--offline',action='store_true');ap.add_argument('--cache',type=Path);ap.add_argument('--allow-stale-archive',action='store_true');prepare(ap.parse_args())
