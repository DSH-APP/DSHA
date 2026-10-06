#!/usr/bin/env python3
"""先封存逐文件 SHA 迁移计划，再按计划迁移；不覆盖目标，不递归删除。"""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
from release_layout import ROOT, checked_path, digest
from release_names import apk_version_from_filename


def destination_for(source, root):
    root = Path(root).resolve()
    release = root / 'release'
    source = checked_path(source, release)
    relative = source.relative_to(release)
    if source.name.endswith(('.apk', '.apk.sha256')):
        version = apk_version_from_filename(source.name)
        if relative.parts[0] == version:
            return source, 'apk'
        history = relative.parts[:-1]
        if history and history[0] == 'history':
            history = history[1:]
        target = release / version
        if source.parent != release:
            target = target / 'history' / Path(*history)
        return target / source.name, 'apk'
    match = re.fullmatch(r'build(\d+)-source/(.+)', relative.as_posix())
    if match:
        return root / 'artifacts/source' / ('build' + match[1]) / match[2], 'source'
    match = re.fullmatch(r'build(\d+)-delivery\.json', relative.as_posix())
    if match:
        return root / 'artifacts/deliveries' / ('build' + match[1] + '.json'), 'delivery'
    return root / 'artifacts/release-history' / relative, 'historical-metadata'


def file_inventory(root):
    release = checked_path(Path(root).resolve() / 'release', Path(root).resolve())
    files = []
    for path in sorted(release.rglob('*')):
        checked_path(path, release)
        if path.is_file():
            files.append(path)
    return files


def verify_apk_pairs(files, rows):
    by_path = {row['source']: row for row in rows}
    for source in files:
        if not source.name.endswith(('.apk', '.apk.sha256')):
            continue
        apk = source.with_name(source.name[:-7]) if source.name.endswith('.sha256') else source
        checksum = apk.with_suffix('.apk.sha256')
        if str(apk) not in by_path or str(checksum) not in by_path:
            raise ValueError('RELEASE_LAYOUT_APK_CHECKSUM_MISSING:' + str(apk))
        tokens = checksum.read_text(encoding='ascii').strip().split()
        if not tokens or tokens[0].lower() != by_path[str(apk)]['sha256'] \
                or (len(tokens) > 1 and (len(tokens) != 2 or tokens[1].lstrip('*') != apk.name)):
            raise ValueError('RELEASE_LAYOUT_APK_CHECKSUM_MISMATCH:' + str(apk))


def make_plan(root=ROOT):
    root = Path(root).resolve()
    files = file_inventory(root)
    rows = []
    targets = set()
    for source in files:
        target, kind = destination_for(source, root)
        checked_path(target, root)
        if target != source and target.exists():
            raise ValueError('RELEASE_LAYOUT_DESTINATION_CONFLICT:' + str(target))
        if str(target) in targets:
            raise ValueError('RELEASE_LAYOUT_DUPLICATE_DESTINATION:' + str(target))
        targets.add(str(target))
        rows.append(dict(source=str(source), target=str(target), sourceRelative=source.relative_to(root).as_posix(),
                         targetRelative=target.relative_to(root).as_posix(), bytes=source.stat().st_size,
                         sha256=digest(source), kind=kind, action='keep' if target == source else 'move'))
    verify_apk_pairs(files, rows)
    directories = [str(path) for path in sorted((root / 'release').rglob('*')) if path.is_dir()]
    return dict(schema=1, status='PLANNED', root=str(root), createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                files=rows, originalDirectories=directories,
                summary=dict(files=len(rows), moves=sum(row['action'] == 'move' for row in rows),
                             bytes=sum(row['bytes'] for row in rows), apkFiles=sum(row['kind'] == 'apk' for row in rows)))


def apply_plan(plan, root=ROOT):
    root = Path(root).resolve()
    if plan.get('schema') != 1 or plan.get('status') != 'PLANNED' or plan.get('root') != str(root):
        raise ValueError('RELEASE_LAYOUT_PLAN_IDENTITY')
    release = root / 'release'
    known = set()
    targets = set()
    for row in plan['files']:
        source = checked_path(Path(row['source']), release)
        target = checked_path(Path(row['target']), root)
        # Derive destinations again; the JSON cannot authorize arbitrary moves.
        relative = source.relative_to(release)
        expected, kind = destination_for(source, root)
        if target != expected or row['kind'] != kind \
                or row.get('sourceRelative') != 'release/' + relative.as_posix() \
                or row.get('targetRelative') != target.relative_to(root).as_posix():
            raise ValueError('RELEASE_LAYOUT_DESTINATION_CHANGED')
        if str(target) in targets:
            raise ValueError('RELEASE_LAYOUT_DUPLICATE_DESTINATION')
        targets.add(str(target)); known.update((str(source), str(target)))
        existing = source if source.exists() else target
        checked_path(existing, root, must_exist=True)
        if existing.stat().st_size != row['bytes'] or digest(existing) != row['sha256']:
            raise ValueError('RELEASE_LAYOUT_SOURCE_CHANGED:' + str(existing))
        if target != source and target.exists() and source.exists() and not os.path.samefile(source, target):
            raise ValueError('RELEASE_LAYOUT_DESTINATION_CONFLICT:' + str(target))
    if any(str(path) not in known for path in file_inventory(root)):
        raise ValueError('RELEASE_LAYOUT_NEW_RELEASE_FILE')
    for row in plan['files']:
        source, target = Path(row['source']), Path(row['target'])
        if source == target or not source.exists():
            continue
        checked_path(source, release, must_exist=True); checked_path(target, root)
        target.parent.mkdir(parents=True, exist_ok=True)
        # Same-volume hard-link creation fails atomically if a target exists; no large byte copy.
        if not target.exists():
            os.link(source, target)
        if not os.path.samefile(source, target) or digest(target) != row['sha256']:
            raise ValueError('RELEASE_LAYOUT_TARGET_CHANGED:' + str(target))
        checked_path(source, release, must_exist=True)
        source.unlink()
    for value in sorted(plan.get('originalDirectories', []), key=lambda value: len(Path(value).parts), reverse=True):
        directory = checked_path(Path(value), release)
        if directory != release and directory.is_dir() and not any(directory.iterdir()):
            directory.rmdir()
    for row in plan['files']:
        checked_path(Path(row['target']), root, must_exist=True)
    if any(not path.name.endswith(('.apk', '.apk.sha256')) for path in file_inventory(root)):
        raise ValueError('RELEASE_LAYOUT_NON_APK_REMAINING')
    return {**plan, 'status': 'APPLIED', 'appliedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'historicalReceiptBytesChanged': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--plan', type=Path, help='仅写入迁移计划，不移动 release 原件')
    mode.add_argument('--apply', type=Path, help='重新核对全部源 SHA，再实施已有计划')
    parser.add_argument('--mapping', type=Path, help='实施后的原路径到新路径映射 JSON')
    args = parser.parse_args()
    root = args.root.resolve()
    if args.plan:
        target = checked_path(args.plan.resolve(), root / 'artifacts/release-layout-migrations')
        plan = make_plan(root)
    else:
        source = checked_path(args.apply.resolve(), root / 'artifacts/release-layout-migrations', must_exist=True)
        target = checked_path((args.mapping or source.with_name(source.name.replace('.plan.json', '.json'))).resolve(),
                              root / 'artifacts/release-layout-migrations')
        if target == source:
            parser.error('--mapping 必须与原计划文件不同')
        if target.exists():
            parser.error('迁移映射已存在，不覆盖')
        plan = apply_plan(json.loads(source.read_text(encoding='utf8')), root)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('x', encoding='utf8') as stream:
        json.dump(plan, stream, ensure_ascii=False, indent=2); stream.write('\n')
    print(json.dumps(dict(status=plan['status'], plan=str(target), **plan['summary']), ensure_ascii=False))


if __name__ == '__main__':
    main()
