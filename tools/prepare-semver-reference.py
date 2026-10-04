"""Exact official SemVer differential input; never modify the DSH raw fixture."""
import argparse,base64,hashlib,io,json,tarfile,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def prepare(check=False):
    lock=json.loads((ROOT/'tools/semver-reference.lock.json').read_text(encoding='utf8'))
    if lock.get('schema')!=1 or lock.get('name')!='semver' or lock.get('version')!='7.8.1' or not lock['url'].startswith('https://registry.npmjs.org/semver/'):
        raise ValueError('SEMVER_REFERENCE_LOCK')
    store=ROOT/'app/build/semver-reference';archive=store/'semver-7.8.1.tgz';store.mkdir(parents=True,exist_ok=True)
    if not archive.is_file():
        if check:raise ValueError('SEMVER_REFERENCE_NOT_PREPARED')
        with urllib.request.urlopen(lock['url'],timeout=30) as response:archive.write_bytes(response.read(1024*1024))
    data=archive.read_bytes();expected='sha512-'+base64.b64encode(hashlib.sha512(data).digest()).decode()
    if expected!=lock['integrity']:raise ValueError('SEMVER_REFERENCE_INTEGRITY')
    members={}
    with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as source:
        for item in source:
            if item.isdir():continue
            if not item.isfile() or not item.name.startswith('package/') or '..' in item.name.split('/') or '\\' in item.name or item.size>1024*1024:raise ValueError('SEMVER_REFERENCE_MEMBER')
            relative=item.name[8:]
            if relative in members:raise ValueError('SEMVER_REFERENCE_DUPLICATE')
            members[relative]=source.extractfile(item).read()
    folder=store/'package'
    if folder.is_symlink():raise ValueError('SEMVER_REFERENCE_ROOT_LINK')
    if check:
        actual={p.relative_to(folder).as_posix() for p in folder.rglob('*') if p.is_file() or p.is_symlink()}
        if actual!=set(members):raise ValueError('SEMVER_REFERENCE_MEMBERS_CHANGED')
        for name,data in members.items():
            target=folder/name
            if target.is_symlink() or target.read_bytes()!=data:raise ValueError('SEMVER_REFERENCE_BYTES_CHANGED:'+name)
    else:
        for name,data in members.items():
            target=folder/name;target.parent.mkdir(parents=True,exist_ok=True)
            if target.is_symlink():raise ValueError('SEMVER_REFERENCE_LINK')
            target.write_bytes(data)
        return prepare(True)
    return folder
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--check',action='store_true');args=p.parse_args()
    print('PASS official byte-exact SemVer7.8.1 reference:',prepare(args.check))
