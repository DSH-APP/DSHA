"""The signed manifest is shared by installation, runtime identity and Gradle inputs."""
import json
from pathlib import Path, PurePosixPath

MANIFEST = 'app/src/main/assets/managed-runtime-inputs.json'
ASSETS = 'app/src/main/assets'
JAVA = 'app/src/main/java/com/deepseekharness/app'


def relative(value):
    if not isinstance(value, str) or not value or '\\' in value or ':' in value or '\0' in value \
            or value.startswith('/') or any(p in ('', '.', '..') for p in value.split('/')):
        raise ValueError('INVALID_RUNTIME_INPUT_PATH: ' + repr(value))
    return value


def load(root):
    root = Path(root)
    spec = json.loads((root / MANIFEST).read_text(encoding='utf-8'))
    if spec.get('schema') != 1:
        raise ValueError('RUNTIME_INPUT_SCHEMA')
    targets, assets = set(), set()
    for row in spec['installs']:
        source, target = relative(row['asset']), relative(row['target'])
        if source in assets or target in targets or not isinstance(row['executable'], bool):
            raise ValueError('DUPLICATE_OR_INVALID_RUNTIME_INSTALL')
        assets.add(source)
        targets.add(target)
    for key in ('assetFiles', 'assetTrees', 'launcherTrees', 'launcherSources'):
        values = spec[key]
        if not isinstance(values, list) or len(set(values)) != len(values):
            raise ValueError('RUNTIME_INPUT_LIST: ' + key)
        for value in values:
            relative(value)
    generators=spec.get('generatorSources', [])
    if not isinstance(generators,list) or len(set(generators))!=len(generators):
        raise ValueError('RUNTIME_GENERATOR_SOURCE_LIST')
    for value in generators:relative(value)
    return spec


def _file(root, relative_path):
    root = Path(root).resolve()
    path = root / relative_path
    if not path.is_file() or not path.resolve().is_relative_to(root):
        raise ValueError('RUNTIME_INPUT_MISSING_OR_OUTSIDE: ' + relative_path)
    return path


def asset_paths(root, spec=None):
    root = Path(root)
    spec = spec or load(root)
    result = {root / MANIFEST}
    for name in spec['assetFiles'] + [row['asset'] for row in spec['installs']]:
        result.add(_file(root, ASSETS + '/' + name))
    for name in spec['assetTrees']:
        directory = root / ASSETS / name
        if not directory.is_dir() or not directory.resolve().is_relative_to((root / ASSETS).resolve()):
            raise ValueError('RUNTIME_INPUT_TREE: ' + name)
        for path in directory.rglob('*'):
            if path.is_file():
                result.add(_file(root, path.relative_to(root).as_posix()))
    return sorted(result)


def launcher_paths(root, spec=None):
    root = Path(root)
    spec = spec or load(root)
    result = {}
    for name in spec['launcherSources']:
        result[name] = _file(root, JAVA + '/' + name)
    for name in spec['launcherTrees']:
        directory = root / name
        if not directory.is_dir():
            raise ValueError('RUNTIME_LAUNCHER_TREE: ' + name)
        suffix = '.java' if name.startswith(JAVA + '/') else '.so'
        for path in directory.rglob('*' + suffix):
            key = path.relative_to(root / JAVA).as_posix() if suffix == '.java' else path.relative_to(root).as_posix()
            result[key] = _file(root, path.relative_to(root).as_posix())
    for name in spec.get('generatorSources', []):
        result['generator:'+name]=_file(root,name)
    return dict(sorted(result.items()))
