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
    builder=load_builder();recipe=builder.recipe_inputs();archive=(getattr(args,'archive',None) or ASSETS/'dsh-runtime.bin').resolve();archive_hash=sha(archive)
    archive_proof=json.loads(archive.with_suffix('.inputs.json').read_text())
    archive_current=archive_proof.get('archive_sha256')==archive_hash and archive_proof.get('inputs')==recipe
    if not archive_current and not args.allow_stale_archive:raise ValueError('ARCHIVE_INPUTS_STALE: 请先由发布执行者生成当前归档；开发夹具可明确 --allow-stale-archive')
    integrity={name:row['integrity'] for name,row in json.loads(lock.read_text())['packages'].items() if 'integrity' in row}
    raw_proof=dict(previous,tarballIntegrities=integrity,archiveSha256=archive_hash,archiveCurrent=archive_current)
    raw_proof['moduleHashes']={name:row['sha256'] for name,row in raw_proof['contentProof']['entries'].items() if row['type']=='FILE'}
    write(marker,raw_proof)
    managed=base/('managed-'+uuid.uuid4().hex[:12]);print('复制并准备 managed:',managed,flush=True);shutil.copytree(io_path(raw),io_path(managed),symlinks=True)
    modules=managed/'node_modules'
    # Use the archive's exact complete per-module chain. Later recipes can
    # alter an earlier after form, so never validate partially applied text.
    chains, _ = builder.load_chains(ASSETS, version)
    targets = set(builder.PATCHES) | set(chains) | {
        builder.COMBO_MODULE, builder.DEEPSEEK_MESSAGES_MODULE,
        builder.CONVERSATION_MATERIALIZED_MODULE, builder.RC1_SETTINGS_MODULE,
        builder.STORAGE_JSON_MODULE, builder.WORKSPACE_DIRECTORY_MODULE,
        builder.SESSION_PERSISTENCE_JSONL_MODULE,
        '@deepseek-ai/dsh-session-format-v2-to-v3/lib/index.js'}
    for module_target in sorted(targets):
        source=io_path(raw/'node_modules'/module_target)
        physical=io_path(modules/module_target)
        if not source.is_file() or not physical.is_file():
            if module_target in chains:raise ValueError('OVERLAY_MODULE_MISSING:'+module_target)
            continue
        canonical=source.read_bytes()
        prepared=builder.patched_content(Path(module_target), canonical)
        if module_target in chains:prepared=builder.apply_stable_patches(prepared, chains[module_target])
        physical.write_bytes(prepared)
    for folder,name in [('runtime-fs','dsha-runtime-fs'),('session-compat','dsha-session-compat'),('client-combo-cache','dsha-client-combo-cache')]:
        shutil.copytree(io_path(ASSETS/folder),io_path(modules/name),dirs_exist_ok=True)
    policy=io_path(modules/'dsha-destructive-policy'); policy.mkdir(exist_ok=True)
    for name,file in builder.destructive_policy_files().items():
        shutil.copyfile(file,policy/name)
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
    # The complete chain above is shared with gzip construction. Record each
    # signed recipe and helper without replaying an already completed prefix.
    for entry in registry['active']:
        file=ASSETS/entry['asset']
        spec=json.loads(file.read_text(encoding='utf8'))
        overlays[file.relative_to(ROOT).as_posix()]=sha(file)
        for patch in spec['patches']:
            if 'prependAsset' in patch:
                helper=ASSETS/patch['prependAsset']
                overlays[helper.relative_to(ROOT).as_posix()]=sha(helper)
        if file.name in builder_owned:
            specialized[file.name]={'sha256':sha(file),'declaredDshVersion':version,'status':'builder_owned_verified'}
    proof=dict(raw_proof,kind='managed',archiveRecipeInputs=recipe,overlayInputs=overlays,specializedReceipts=specialized)
    proof['contentProof']=content_proof(managed)
    proof['moduleHashes']={name:row['sha256'] for name,row in proof['contentProof']['entries'].items() if row['type']=='FILE'}
    write(managed/'dsha-test-runtime.json',proof)
    pointer=dict(version=1,dshVersion=version,platform=target,raw=str(raw),managed=str(managed),archiveCurrent=archive_current)
    write(STORE/'current.json',pointer)
    print(json.dumps(pointer,ensure_ascii=False,indent=2),flush=True)

if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--platform',choices=['host','linux-arm64'],default='host');ap.add_argument('--offline',action='store_true');ap.add_argument('--cache',type=Path);ap.add_argument('--allow-stale-archive',action='store_true');ap.add_argument('--archive',type=Path,help='Explicit candidate archive for development fixtures; does not alter APK assets');prepare(ap.parse_args())
