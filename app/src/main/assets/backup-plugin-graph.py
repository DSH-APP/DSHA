#!/usr/bin/env python3
"""把 npm/pnpm 的实际依赖关系保存成普通文件与路径无关的引用图。"""
import hashlib
import json
import os
from pathlib import Path
import re

POOL_MARKER = '.dsha-dependency-pool.json'
CORE = {'@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app'}
SHARED = {'@deepseek-ai/cordis', '@deepseek-ai/schemastery', 'react', 'react-dom'}


class Records(list):
    def __init__(self):
        super().__init__()
        self.graph = {}


def resolve_module(source, name, global_nm):
    for parent in (source, *source.parents):
        if parent.name == 'node_modules':
            continue
        candidate = parent / 'node_modules' / name
        if (candidate / 'package.json').is_file():
            return candidate.resolve()
    for base in global_nm:
        if (base / name / 'package.json').is_file():
            return (base / name).resolve()
    return None


def archive_plugins(root, stage, copy_data, skip, checks, valid_name, global_nm, discovered):
    records = Records()
    source_ids, roots = {}, set()
    shared_pools = {base.resolve() for base in global_nm if base.is_dir()}
    pool = stage / '.dsha-plugin-src/.deps'
    def provided(name):
        return name in SHARED or (name.startswith('@deepseek-ai/dsh-')
                                  and any((base / name / 'package.json').is_file() for base in global_nm))

    def node(source):
        source = source.resolve(strict=True)
        if source in source_ids:
            return source_ids[source]
        if len(source_ids) >= 20000:
            raise ValueError('插件依赖数量超过备份上限')
        pkg = json.loads((source / 'package.json').read_text(encoding='utf-8'))
        if not valid_name(pkg.get('name')):
            raise ValueError('插件依赖的 package.json 缺少有效包名：' + str(source))
        key = hashlib.sha256(str(source).encode()).hexdigest()[:20]
        if key in records.graph:
            raise ValueError('插件快照标识冲突')
        source_ids[source] = key
        record = records.graph[key] = {'name': pkg['name'], 'links': {}}
        destination = pool / key
        copy_data(source, destination, exclude=skip, checks=checks)
        requirements = {}
        runtime = pkg.get('dependencies') or {}
        peers = pkg.get('peerDependencies') or {}
        optional = set(pkg.get('optionalDependencies') or {})
        for section in ('dependencies', 'peerDependencies', 'optionalDependencies'):
            values = pkg.get(section) or {}
            if not isinstance(values, dict):
                raise ValueError('插件依赖声明必须是对象')
            requirements.update(values)
        optional.update(name for name, meta in (pkg.get('peerDependenciesMeta') or {}).items()
                        if isinstance(meta, dict) and meta.get('optional'))
        # 未列入 manifest 的随包依赖同样保留；裸资源目录维持原相对位置。
        embedded = source / 'node_modules'
        # 受管插件的 node_modules 可借用整个宿主池；只归档其明确依赖，不能连带打包整个 dsh。
        if embedded.is_dir() and embedded.resolve() not in shared_pools:
            children = list(embedded.iterdir())
            if checks is not None:
                checks.append((embedded.resolve(), sorted(child.name for child in children), set()))
            candidates = []
            for child in children:
                if child.name.startswith('@') and child.is_dir():
                    if checks is not None:
                        checks.append((child.resolve(), sorted(p.name for p in child.iterdir()), set()))
                    candidates.extend((child.name + '/' + p.name, p) for p in child.iterdir())
                elif child.name != '.bin':
                    candidates.append((child.name, child))
            for name, child in candidates:
                if (child / 'package.json').is_file() and valid_name(name):
                    requirements.setdefault(name, '*')
                elif child.name not in skip:
                    copy_data(child, destination / 'node_modules' / name,
                              exclude=skip - {'node_modules'}, checks=checks)
        for name in requirements:
            if not valid_name(name):
                raise ValueError('插件依赖包名无效')
            if provided(name):
                continue
            dependency = resolve_module(source, name, global_nm)
            if dependency is None:
                if name in optional:
                    continue
                # 旧插件把浏览器宿主接口/编译期类型列为 dsh peer，未必存在同名 npm 包。
                # 只放行纯宿主 peer；真实运行依赖和其他缺失 peer 仍需修复，已安装依赖照常归档。
                if name in peers and name not in runtime and name.startswith('@deepseek-ai/dsh-'):
                    continue
                # 保留损坏前的真实状态：已有源码/依赖照常归档，缺失项明确记入清单。
                # 备份负责保护数据，不能要求用户先修好第三方插件才允许升级。
                record.setdefault('missing', []).append(name)
                continue
            record['links'][name] = node(dependency)
        return key

    profiles = stage / '.dsh/profiles'
    for pkg_path in sorted(profiles.glob('*/package.json')):
        pkg = json.loads(pkg_path.read_text(encoding='utf-8'))
        original = root / '.dsh/profiles' / pkg_path.parent.name
        dependencies = pkg.get('dependencies') or {}
        bundles = ((pkg.get('dsh') or {}).get('profile') or {}).get('bundles') or []
        for name in dict.fromkeys(list(dependencies) + list(bundles)):
            if not valid_name(name):
                raise ValueError('插件包名无效')
            specification = dependencies.get(name, '')
            if not isinstance(specification, str):
                raise ValueError('插件依赖声明无效：' + name)
            if name in CORE:
                continue
            active = original / 'node_modules' / name
            source = active.resolve() if (active / 'package.json').is_file() else None
            if source is None and specification.startswith(('file:', 'link:')):
                source = Path(specification.split(':', 1)[1])
                if not source.is_absolute():
                    source = original / source
            elif source is None:
                source = resolve_module(original, name, global_nm)
            if source is None or not (source / 'package.json').is_file():
                # 官方模块由随包运行时重建；未知第三方缺失时必须报告，不能备份空壳。
                if name.startswith('@deepseek-ai/') and not specification.startswith(('file:', 'link:')):
                    continue
                raise ValueError('插件源码缺失，请修复后再备份：' + name)
            key = node(source)
            roots.add((name, source.resolve()))
            records.append({'profile': pkg_path.parent.name, 'name': name,
                            'slot': hashlib.sha256((pkg_path.parent.name + '/' + name).encode()).hexdigest()[:20], 'node': key,
                            'bundledOnly': name not in dependencies})
        nm = original / 'node_modules'
        if nm.is_dir():
            for marker in nm.rglob('*.disabled'):
                if marker.is_file() and not marker.is_symlink():
                    copy_data(marker, pkg_path.parent / 'node_modules' / marker.relative_to(nm), checks=checks)
    for name, row in discovered.items():
        source = Path(row['directory']).resolve()
        if (name, source) in roots:
            continue
        key = node(source)
        records.append({'profile': 'web', 'name': name,
                        'slot': hashlib.sha256(('discovered/' + name).encode()).hexdigest()[:20],
                        'node': key, 'unregistered': True})

    # 插件源目录中的草稿、未构建源码和 Git 历史也属于用户数据，不能因没登记而丢弃。
    source_home = root / '.dsh/plugin-src'
    owned = set()
    marker = source_home / POOL_MARKER
    if marker.is_file():
        try:
            metadata = json.loads(marker.read_text(encoding='utf-8'))
            if metadata.get('version') == 1 and all(re.fullmatch('[a-f0-9]{20}', key) for key in metadata.get('nodes', [])):
                owned = set(metadata['nodes'])
        except (ValueError, KeyError, TypeError):
            pass
    def unmanaged(source, destination):
        if source.resolve() in source_ids or (source.parent == source_home / '.deps' and source.name in owned):
            return
        if source == marker and owned:
            return
        if source.is_dir() and (source == source_home / '.deps' or any(source.resolve() in path.parents for path in source_ids)):
            for child in source.iterdir():
                unmanaged(child, destination / child.name)
        else:
            copy_data(source, destination, exclude=skip, checks=checks)
    if source_home.is_dir():
        for child in source_home.iterdir():
            unmanaged(child, stage / '.dsh/plugin-src' / child.name)
    if records:
        (stage / '.dsh/plugin-src').mkdir(parents=True, exist_ok=True)
    return records


def validate_graph(graph, stage, valid_name):
    if not isinstance(graph, dict) or len(graph) > 20000:
        raise ValueError('插件依赖图无效')
    for key, row in graph.items():
        if not re.fullmatch('[a-f0-9]{20}', key) or not isinstance(row, dict) or not valid_name(row.get('name')):
            raise ValueError('插件依赖标识无效')
        links = row.get('links')
        if not isinstance(links, dict) or any(not valid_name(name) or not isinstance(target, str) or target not in graph for name, target in links.items()):
            raise ValueError('插件依赖引用无效')
        missing = row.get('missing', [])
        if not isinstance(missing, list) or len(missing) > 20000 or any(not valid_name(name) or name in links for name in missing):
            raise ValueError('插件缺失依赖记录无效')
        source = stage / '.dsha-plugin-src/.deps' / key
        pkg = json.loads((source / 'package.json').read_text(encoding='utf-8'))
        if pkg.get('name') != row['name']:
            raise ValueError('插件依赖图与包名不符')


def restore_graph(graph, stage, plugin_stage, generation, copy_data, valid_name):
    validate_graph(graph, stage, valid_name)
    for key in graph:
        source = stage / '.dsha-plugin-src/.deps' / key
        destination = plugin_stage / '.deps' / key
        if destination.exists():
            raise ValueError('插件依赖目标已存在，已停止覆盖')
        copy_data(source, destination)
    for key, row in graph.items():
        for name, target in row['links'].items():
            owner = plugin_stage / '.deps' / key
            link = owner / 'node_modules' / name
            link.parent.mkdir(parents=True, exist_ok=True)
            if os.path.lexists(link):
                raise ValueError('插件依赖链接与随包文件冲突')
            # 相对链接在暂存树及最终树中都成立，proroot 不必解析宿主不可见的 guest 绝对路径。
            link.symlink_to(os.path.relpath(plugin_stage / '.deps' / target, link.parent), target_is_directory=True)
            pkg = json.loads((plugin_stage / '.deps' / target / 'package.json').read_text(encoding='utf-8'))
            bins = pkg.get('bin') or {}
            if isinstance(bins, str):
                bins = {name.split('/')[-1]: bins}
            if isinstance(bins, dict):
                for command, entry in bins.items():
                    if not isinstance(command, str) or not re.fullmatch('[A-Za-z0-9_.-]+', command) or command in ('.', '..'):
                        raise ValueError('插件命令名无效')
                    if not isinstance(entry, str) or Path(entry).is_absolute() or '..' in Path(entry).parts:
                        raise ValueError('插件命令路径无效')
                    path = plugin_stage / '.deps' / target / entry
                    if not path.is_file():
                        raise ValueError('插件命令入口缺失：' + name + '/' + entry)
                    command_path = owner / 'node_modules/.bin' / command
                    command_path.parent.mkdir(parents=True, exist_ok=True)
                    if not os.path.lexists(command_path):
                        command_path.symlink_to(os.path.relpath(plugin_stage / '.deps' / target / entry, command_path.parent))
    if graph:
        (plugin_stage / POOL_MARKER).write_text(json.dumps({'version': 1, 'nodes': list(graph)}), encoding='utf-8')
