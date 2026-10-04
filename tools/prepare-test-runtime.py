#!/usr/bin/env python3
"""建立可复现 raw/managed 测试输入；不会生成 APK 或改写发布归档。"""
from source_text import write_text as write_source_text, matches_text
import argparse, hashlib, importlib.util, json, os, platform, shutil, subprocess, uuid
from pathlib import Path
from runtime_fixture_proof import SCHEMA, content as content_proof, reuse as reuse_proof, io_path, logical_path
ROOT=Path(__file__).resolve().parents[1]; ASSETS=ROOT/'app/src/main/assets'; STORE=ROOT/'app/build/test-runtimes'

def sha(path):
    h=hashlib.sha256()
    with io_path(path).open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()
def write(path,value):write_source_text(path,json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
def files_under(root, suffix=None):
    for base, dirs, names in os.walk(io_path(root), topdown=True, followlinks=False):
        dirs[:] = [name for name in dirs if not (Path(base)/name).is_symlink()]
        for name in names:
            file=Path(base)/name
            if file.is_symlink() or not file.is_file(): continue
            if suffix is None or file.suffix==suffix or file.name=='package.json': yield logical_path(file)

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
    expected=dict(kind='raw',dshVersion=version,platform=target,lockSha256=locksha,packageSha256=sha(package))
    if raw.is_symlink() or getattr(raw,'is_junction',lambda:False)():raise ValueError('TEST_FIXTURE_ROOT_LINK')
    previous=reuse_proof(raw,expected) if raw.exists() else None
    if previous is None:
        fresh=base/('raw-npm-ci-'+uuid.uuid4().hex);fresh.mkdir()
        for name in ('package.json','package-lock.json'):shutil.copyfile(ROOT/'tools/dsh-runtime'/name,fresh/name)
        command=[npm,'ci','--prefix',str(fresh),'--ignore-scripts','--no-audit','--no-fund','--os='+target['os'],'--cpu='+target['cpu'],'--loglevel=error']
        if target['os']=='linux':command+=['--libc=glibc']
        if args.offline:command+=['--offline']
        if args.cache:command+=['--cache',str(args.cache.resolve())]
        print('安装全新 raw（npm 校验锁文件 integrity，禁用脚本）:',fresh,flush=True);subprocess.run(command,check=True)
        if json.loads((fresh/'node_modules/@deepseek-ai/dsh/package.json').read_text())['version']!=version or sha(fresh/'package-lock.json')!=locksha:raise ValueError('RAW_LOCK_OR_VERSION_MISMATCH')
        # Known legacy trees are retained. Only the newly npm-ci-created tree is certified.
        trusted=content_proof(fresh)
        previous=dict(expected,version=SCHEMA,installation='npm-ci-ignore-scripts',installationId=uuid.uuid4().hex,contentProof=trusted)
        write(fresh/'dsha-test-runtime.json',previous)
        if raw.exists():raw.rename(base/('raw-retained-'+uuid.uuid4().hex))
        fresh.rename(raw)
    actual=json.loads((raw/'node_modules/@deepseek-ai/dsh/package.json').read_text())['version']
    if actual!=version or sha(raw/'package-lock.json')!=locksha:raise ValueError('RAW_LOCK_OR_VERSION_MISMATCH')
    builder=load_builder();recipe=builder.recipe_inputs();archive=ASSETS/'dsh-runtime.bin';archive_hash=sha(archive)
    archive_proof=json.loads((ASSETS/'dsh-runtime.inputs.json').read_text())
    archive_current=archive_proof.get('archive_sha256')==archive_hash and archive_proof.get('inputs')==recipe
    if not archive_current and not args.allow_stale_archive:raise ValueError('ARCHIVE_INPUTS_STALE: 请先由发布执行者生成当前归档；开发夹具可明确 --allow-stale-archive')
    integrity={name:row['integrity'] for name,row in json.loads(lock.read_text())['packages'].items() if 'integrity' in row}
    raw_proof=dict(previous,tarballIntegrities=integrity,archiveSha256=archive_hash,archiveCurrent=archive_current)
    raw_proof['moduleHashes']={name:row['sha256'] for name,row in raw_proof['contentProof']['entries'].items() if row['type']=='FILE'}
    write(marker,raw_proof)
    managed=base/('managed-'+uuid.uuid4().hex[:12]);print('复制并准备 managed:',managed,flush=True);shutil.copytree(io_path(raw),io_path(managed),symlinks=True)
    modules=managed/'node_modules'
    # 与发布归档共用精确源码转换，不从旧版安装树猜测新运行时。
    for file in files_under(modules, '.js'):
        physical=io_path(file)
        if physical.is_symlink() or not physical.is_file():continue
        original=physical.read_bytes();patched=builder.patched_content(file.relative_to(modules),original)
        if patched!=original:physical.write_bytes(patched)
    for folder,name in [('runtime-fs','dsha-runtime-fs'),('session-compat','dsha-session-compat'),('client-combo-cache','dsha-client-combo-cache')]:
        shutil.copytree(io_path(ASSETS/folder),io_path(modules/name),dirs_exist_ok=True)
    overlays={}
    builder_owned = {'deepseek-messages-compat-patch.json','lexical-claim-patch.json','conversation-materialized-patch.json','client-combo-patch.json'}
    registry=json.loads((ASSETS/'runtime-patches.json').read_text(encoding='utf8'))
    if registry.get('schema')!=1 or registry.get('dshVersion')!=version:raise ValueError('PATCH_REGISTRY_VERSION')
    selected=[row['asset'] for row in registry['active']]
    if len(selected)!=len(set(selected)):raise ValueError('PATCH_REGISTRY_DUPLICATE')
    retired={row['asset'] for row in registry['retired']}
    dedicated=registry['specialized']
    if len(dedicated)!=len(set(dedicated)):raise ValueError('PATCH_REGISTRY_SPECIALIZED_DUPLICATE')
    if (set(selected)|set(dedicated)) & retired or set(selected)&set(dedicated):raise ValueError('PATCH_REGISTRY_CONFLICT')
    overlays['app/src/main/assets/runtime-patches.json']=sha(ASSETS/'runtime-patches.json')
    specialized={}
    for name in registry['specialized']:
        if not name.endswith('.json'):continue
        file=ASSETS/name;spec=json.loads(file.read_text(encoding='utf8'));fingerprint=sha(file)
        overlays[file.relative_to(ROOT).as_posix()]=fingerprint
        declared=spec.get('dshVersion')
        if declared!=version:raise ValueError('SPECIALIZED_PATCH_VERSION:'+name)
        specialized[name]={'sha256':fingerprint,'declaredDshVersion':declared,
                          'status':'builder_owned' if name in builder_owned else 'dedicated_consumer'}
    # Preserve the registry's ordinary-patch order. Specialized PDF/browser/LAN
    # consumers have their own contracts and must never enter this module loop.
    for entry in registry['active']:
        file=ASSETS/entry['asset']
        spec=json.loads(file.read_text(encoding='utf8'))
        if spec.get('dshVersion')!=version:raise ValueError('ACTIVE_PATCH_VERSION:'+file.name)
        module_target=entry.get('module') or spec.get('module')
        if not module_target or not isinstance(spec.get('patches'),list):raise ValueError('ACTIVE_PATCH_SHAPE:'+file.name)
        module_path=Path(module_target)
        if module_path.is_absolute() or '\\' in module_target or any(part in ('','..','.') for part in module_target.split('/')):raise ValueError('OVERLAY_MODULE_PATH:'+file.name)
        targetfile=modules/module_target
        physical=io_path(targetfile)
        if not physical.is_file():raise ValueError('OVERLAY_MODULE_MISSING:'+module_target)
        if not physical.resolve().is_relative_to(io_path(modules).resolve()):raise ValueError('OVERLAY_MODULE_OUTSIDE:'+file.name)
        source=physical.read_text(encoding='utf8')
        overlays[file.relative_to(ROOT).as_posix()]=sha(file)
        if file.name in builder_owned:
            # Later patches can intentionally modify text inside an earlier
            # after form. Reconstruct the complete ordered result from the
            # proven canonical npm bytes, rather than counting nested snippets.
            canonical=io_path(raw/'node_modules'/module_target).read_bytes()
            expected=builder.patched_content(module_path,canonical)
            if physical.read_bytes()!=expected:raise ValueError('BUILDER_PATCH_AFTER_MISMATCH:'+file.name)
            specialized[file.name]={'sha256':sha(file),'declaredDshVersion':version,'status':'builder_owned_verified'}
            continue
        for patch in spec['patches']:
            before,after=patch['before'],patch['after']
            if 'prependAsset' in patch:
                asset=ASSETS/patch['prependAsset']
                if not asset.is_file() or not asset.resolve().is_relative_to(ASSETS.resolve()):raise ValueError('OVERLAY_PREPEND_PATH')
                overlays[asset.relative_to(ROOT).as_posix()]=sha(asset)
                after=asset.read_text(encoding='utf8')+'\n'+after
            if source.count(after)==1 and source.count(before)-after.count(before)==0:continue
            if source.count(before)!=1:raise ValueError('OVERLAY_ANCHOR_MISMATCH:'+file.name)
            source=source.replace(before,after)
        write_source_text(physical,source,encoding='utf8');overlays[str(file.relative_to(ROOT)).replace(os.sep,'/')]=sha(file)
    proof=dict(raw_proof,kind='managed',archiveRecipeInputs=recipe,overlayInputs=overlays,specializedReceipts=specialized)
    proof['contentProof']=content_proof(managed)
    proof['moduleHashes']={name:row['sha256'] for name,row in proof['contentProof']['entries'].items() if row['type']=='FILE'}
    write(managed/'dsha-test-runtime.json',proof)
    pointer=dict(version=1,dshVersion=version,platform=target,raw=str(raw),managed=str(managed),archiveCurrent=archive_current)
    write(STORE/'current.json',pointer)
    print(json.dumps(pointer,ensure_ascii=False,indent=2),flush=True)

if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--platform',choices=['host','linux-arm64'],default='host');ap.add_argument('--offline',action='store_true');ap.add_argument('--cache',type=Path);ap.add_argument('--allow-stale-archive',action='store_true');prepare(ap.parse_args())
