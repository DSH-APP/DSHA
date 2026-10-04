#!/usr/bin/env python3
"""为正式 APK 准备固定应急资产；任何输入变化均显式核验，不自动更新运行时锁。"""
from source_text import write_text as write_source_text, matches_text
import argparse
import hashlib
import json
import posixpath
import re
import shutil
import tarfile
from pathlib import Path
from generated_asset_directory import prune as prune_generated
from recovery_runtime_overlay import build as build_browser_overlay, TARGETS as BROWSER_TARGETS, INPUT_ASSETS

ROOT = Path(__file__).resolve().parents[1]
HOME = ROOT / 'tools/recovery-runtime'
SHARED_ASSETS = {
    'recovery-rootfs.bin': 'offline-rootfs.bin',
    'recovery-dsh-runtime.bin': 'dsh-runtime.bin',
}


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def safe_name(value):
    name = value.removeprefix('./').rstrip('/')
    if not name or name == '.':
        return ''
    if name.startswith('/') or '\\' in name or posixpath.normpath(name) != name or '..' in name.split('/'):
        raise ValueError('RECOVERY_ARCHIVE_PATH: ' + value)
    return name


def describe(archives):
    files, links = {}, {}
    for archive in archives:
        with tarfile.open(archive, 'r|gz') as tree:
            for item in tree:
                name = safe_name(item.name)
                if not name:
                    continue
                if item.isfile():
                    h = hashlib.sha256()
                    with tree.extractfile(item) as stream:
                        for block in iter(lambda: stream.read(1024 * 1024), b''):
                            h.update(block)
                    files[name] = dict(path=name, sha256=h.hexdigest(), bytes=item.size)
                    links.pop(name, None)
                elif item.issym():
                    target = item.linkname
                    resolved = posixpath.normpath(target.lstrip('/') if target.startswith('/') else posixpath.join(posixpath.dirname(name), target))
                    if resolved == '..' or resolved.startswith('../'):
                        raise ValueError('RECOVERY_LINK_ESCAPE: ' + name)
                    links[name] = dict(path=name, target=resolved)
                    files.pop(name, None)
                elif not item.isdir() and not (item.ischr() or item.isblk() or item.isfifo()):
                    raise ValueError('RECOVERY_ARCHIVE_TYPE: ' + name)
    return sorted(files.values(), key=lambda x: x['path']), sorted(links.values(), key=lambda x: x['path'])


def prepare_archive_locations(lock, output, shared_assets, store, seed_root):
    """仅复用摘要相同的签名资产；应急逻辑身份和独立固定副本保持原样。"""
    rows = lock.get('archives', [])
    if lock.get('schema') != 1 or len(rows) != len(SHARED_ASSETS) \
            or {row.get('asset') for row in rows} != set(SHARED_ASSETS):
        raise ValueError('RECOVERY_LOCK_INVALID')
    output = output.absolute()
    if output.resolve() != output:
        raise ValueError('RECOVERY_OUTPUT_ALIAS')
    if output == shared_assets.resolve() or output == store.resolve():
        raise ValueError('RECOVERY_OUTPUT_OVERLAP')
    output.mkdir(parents=True, exist_ok=True)
    store.mkdir(parents=True, exist_ok=True)
    archives, locations = [], []
    for row in rows:
        if not isinstance(row.get('sha256'), str) or not re.fullmatch(r'[a-f0-9]{64}', row['sha256']):
            raise ValueError('RECOVERY_LOCK_HASH')
        path = store / row['asset']
        if path.is_symlink():
            raise ValueError('RECOVERY_PINNED_ALIAS: ' + row['asset'])
        if not path.exists():
            seed = seed_root / row['seed']
            if not seed.is_file() or digest(seed) != row['sha256']:
                raise ValueError('RECOVERY_PINNED_ARCHIVE_MISSING: ' + row['asset'] + '; restore the pinned archive, do not change the lock')
            shutil.copyfile(seed, path)
        if digest(path) != row['sha256']:
            raise ValueError('RECOVERY_ARCHIVE_HASH: ' + row['asset'])
        target = output / row['asset']
        if target.is_symlink():
            raise ValueError('RECOVERY_OUTPUT_ALIAS: ' + row['asset'])
        shared_name = SHARED_ASSETS[row['asset']]
        shared = shared_assets / shared_name
        if shared.is_file() and not shared.is_symlink() and digest(shared) == row['sha256']:
            # 只删除本生成目录内已知的独立归档，避免增量构建继续带入旧副本。
            if target.exists():
                if not target.is_file():
                    raise ValueError('RECOVERY_OUTPUT_TYPE: ' + row['asset'])
                target.unlink()
            source = shared_name
        else:
            if not target.is_file() or digest(target) != row['sha256']:
                shutil.copyfile(path, target)
            source = row['asset']
        locations.append(dict(asset=row['asset'], source=source, sha256=row['sha256']))
        archives.append(path)
    return archives, locations


def prepare(output, shared_assets):
    lock = json.loads((HOME / 'lock.json').read_text(encoding='utf8'))
    archives, locations = prepare_archive_locations(lock, output, shared_assets, HOME / 'archives', ROOT)
    files, links = describe(archives)
    dsh_archive = next(path for row, path in zip(lock['archives'], archives)
                       if row['asset'] == 'recovery-dsh-runtime.bin')
    original, patched = build_browser_overlay(dsh_archive, ROOT / 'app/src/main/assets', lock['dshVersion'])
    by_path = {row['path']: row for row in files}
    overlays = []
    for name, path in BROWSER_TARGETS.items():
        if path not in by_path or by_path[path]['sha256'] != hashlib.sha256(original[name]).hexdigest():
            raise ValueError('RECOVERY_OVERLAY_ARCHIVE_PROOF: ' + name)
        asset = 'recovery-overlay/' + name
        target = output / asset
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(patched[name])
        final_sha = hashlib.sha256(patched[name]).hexdigest()
        by_path[path] = dict(path=path, sha256=final_sha, bytes=len(patched[name]))
        overlays.append(dict(path=path, asset=asset, originalSha256=hashlib.sha256(original[name]).hexdigest(),
                             sha256=final_sha, bytes=len(patched[name])))
    files = sorted(by_path.values(), key=lambda row: row['path'])
    agent = ROOT / 'app/src/main/assets/recovery-agent.js'
    if not agent.is_file():
        raise ValueError('RECOVERY_AGENT_MISSING')
    launchers = {}
    for flavor, folder, names in [
        ('standard', 'main', ['libproot.so', 'libprootloader.so', 'libtalloc.so', 'libandroidshmem.so']),
        ('low', 'low', ['libproot_legacy.so', 'libprootloader_legacy.so'])
    ]:
        launchers[flavor] = {name: digest(ROOT / ('app/src/' + folder + '/jniLibs/arm64-v8a') / name) for name in names}
    launchers['low']['libtalloc.so'] = launchers['standard']['libtalloc.so']
    launchers['low']['libandroidshmem.so'] = launchers['standard']['libandroidshmem.so']
    profiles = {name: digest(ROOT / 'app/src/main/assets' / name)
                for name in ['recovery-profile-package.json', 'recovery-profile.patch.yml']}
    overlay_inputs = {name: digest(ROOT / 'app/src/main/assets' / name) for name in INPUT_ASSETS}
    contract = dict(schema=1, dshVersion=lock['dshVersion'], baseVersion=lock['baseVersion'],
                    archives=[dict(asset=row['asset'], sha256=row['sha256']) for row in lock['archives']],
                    expandedBytes=sum(row['bytes'] for row in files), files=files, links=links,
                    overlays=overlays, overlayInputs=overlay_inputs,
                    agentSha256=digest(agent), profileHashes=profiles, launcherHashes=launchers)
    identity = hashlib.sha256(json.dumps(contract, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    write_source_text(output / 'recovery-runtime.json',json.dumps(dict(id=identity, **contract), ensure_ascii=False, separators=(',', ':')) + '\n',encoding='utf8')
    # 物理位置不进入运行时内容身份；相同字节复用不会使已有应急舱失效。
    write_source_text(output / 'recovery-asset-locations.json',json.dumps(
        dict(schema=1, runtimeId=identity, archives=locations), separators=(',', ':')) + '\n',encoding='utf8')
    expected = {'recovery-runtime.json', 'recovery-asset-locations.json'}
    expected.update(row['asset'] for row in overlays)
    expected.update(row['asset'] for row in locations if row['source'] == row['asset'])
    prune_generated(output, expected)
    print('recovery runtime:', identity, 'files:', len(files), 'expanded:', contract['expandedBytes'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'app/build/generated/recoveryAssets')
    parser.add_argument('--shared-assets', type=Path, default=ROOT / 'app/build/generated/standardAssets')
    args = parser.parse_args()
    prepare(args.output, args.shared_assets)
