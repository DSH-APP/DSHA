"""Explicit APK asset deployment and signed/dynamic reader contracts."""
import json
from pathlib import Path, PurePosixPath
import re
ROOT=Path(__file__).resolve().parents[1]
MANIFEST=Path(__file__).with_name('asset-deployment.json')
def logical(value):
    if not isinstance(value,str) or not value or '\\' in value or value.startswith('/') or PurePosixPath(value).as_posix()!=value or any(p in ('','.','..') for p in value.split('/')):
        raise ValueError('ASSET_DEPLOYMENT_PATH:'+str(value))
    return value
def load_manifest(path=MANIFEST):
    data=json.loads(Path(path).read_text(encoding='utf-8'))
    if data.get('schema')!=1:raise ValueError('ASSET_DEPLOYMENT_SCHEMA')
    for key in ('packagedFiles','packagedTrees','sourceInputs','sourceOnlyTrees','generatedFiles','externalGeneratedFiles'):
        values=data[key]
        if not isinstance(values,list) or len(values)!=len(set(values)):raise ValueError('ASSET_DEPLOYMENT_DUPLICATE:'+key)
        for value in values:logical(value)
    if set(data['packagedFiles']) & set(data['sourceInputs']):raise ValueError('ASSET_DEPLOYMENT_OVERLAP')
    for source,target in data.get('sourceTransforms',{}).items():
        logical(source);logical(target)
        if source not in data['sourceInputs'] or target not in data['generatedFiles']:
            raise ValueError('ASSET_DEPLOYMENT_TRANSFORM:'+source)
    for row in data['dynamicReaders']:
        logical(row['source'])
        for name in row.get('assets',[]):logical(name)
    return data
def selected_assets(source,manifest):
    source=Path(source).resolve();selected=[]
    for name in manifest['packagedFiles']:
        path=source/name
        if not path.is_file() or path.is_symlink():raise ValueError('ASSET_DEPLOYMENT_MISSING:'+name)
    for name in manifest['packagedTrees']:
        if not (source/name).is_dir() or (source/name).is_symlink():raise ValueError('ASSET_DEPLOYMENT_TREE:'+name)
    for path in source.rglob('*'):
        name=path.relative_to(source).as_posix();parts=path.relative_to(source).parts
        if '__pycache__' in parts or path.suffix=='.pyc':continue
        if any(name==tree or name.startswith(tree+'/') for tree in manifest['sourceOnlyTrees']):continue
        if path.is_symlink():raise ValueError('ASSET_DEPLOYMENT_SOURCE_LINK:'+name)
        if not path.is_file():continue
        if name in manifest['sourceInputs']:continue
        if name in manifest['packagedFiles'] or any(name.startswith(tree+'/') for tree in manifest['packagedTrees']):selected.append((path,Path(name)));continue
        raise ValueError('ASSET_DEPLOYMENT_UNREGISTERED:'+name)
    return sorted(selected,key=lambda row:row[1].as_posix())
def verify_reader_contracts(source,manifest,repository=ROOT):
    source=Path(source);repository=Path(repository)
    selected={relative.as_posix() for _,relative in selected_assets(source,manifest)}
    deployed=selected|set(manifest['generatedFiles'])|set(manifest['externalGeneratedFiles'])
    def require(name,reader):
        name=logical(name)
        if name not in deployed:raise ValueError('ASSET_READER_UNDEPLOYED:'+reader+':'+name)
    managed=json.loads((source/'managed-runtime-inputs.json').read_text(encoding='utf-8'))
    for row in managed['installs']:require(row['asset'],'managed-runtime installs')
    for name in managed['assetFiles']:
        if name in manifest.get('sourceTransforms',{}):
            if not (source/name).is_file() or (source/name).is_symlink():raise ValueError('ASSET_TRANSFORM_SOURCE:'+name)
            require(manifest['sourceTransforms'][name],'managed-runtime transformed assetFiles')
        else:require(name,'managed-runtime assetFiles')
    for tree in managed['assetTrees']:
        if tree not in manifest['packagedTrees']:raise ValueError('ASSET_READER_TREE:'+tree)
    patches=json.loads((source/'runtime-patches.json').read_text(encoding='utf-8'))
    for row in patches['active']:
        require(row['asset'],'runtime-patches active');recipe=json.loads((source/row['asset']).read_text(encoding='utf-8'))
        for patch in recipe.get('patches',[]):
            if 'prependAsset' in patch:require(patch['prependAsset'],'runtime prependAsset')
    for name in patches['specialized']:
        if name.endswith('.json'):require(name,'runtime-patches specialized')
    builtins=json.loads((source/'builtin-plugins.json').read_text(encoding='utf-8'))
    for row in builtins['plugins']:
        tree='app-integration' if row['name']=='dsh-app-integration' else 'builtin-plugins/'+row['name']
        require(tree+'/package.json','builtin registry');require(tree+'/'+row['entrypoint'],'builtin entrypoint')
    for file in source.rglob('manifest.json'):
        if file.parent.name not in manifest['extensionTrees']:continue
        spec=json.loads(file.read_text(encoding='utf-8'));base=file.parent.relative_to(source).as_posix()
        for script in spec.get('content_scripts',[]):
            for name in script.get('js',[])+script.get('css',[]):require(base+'/'+name,'webextension '+base)
        for name in spec.get('background',{}).get('scripts',[]):require(base+'/'+name,'webextension background '+base)
    for row in manifest['dynamicReaders']:
        if not (repository/row['source']).is_file():raise ValueError('ASSET_DYNAMIC_READER_SOURCE:'+row['source'])
        for name in row.get('assets',[]):require(name,row['source'])
    # Supplemental Java literals catch new reads; they never select/prune assets.
    # Explicit deployment roots and structured dynamic registries own selection.
    pattern=re.compile(r'(?:(?:getAssets\(\)\.open|readAsset|readAssetString|assetText|assetObject|profileAsset)\(\s*(?:\w+\s*,\s*)?|\bread\(\s*context\s*,\s*)"([^"\n]+)"')
    for base in ('main','standard','low'):
        for file in (repository/'app/src'/base/'java').rglob('*.java'):
            for name in pattern.findall(file.read_text(encoding='utf-8')):
                if name in deployed or name in manifest.get('optionalHistoricalReads',[]):continue
                if (source/name).exists() or name.endswith(('.cjs','.js','.json','.py','.bin','.crt','.sha256','.layout','.version')):require(name,file.relative_to(repository).as_posix())
    return len(selected)
