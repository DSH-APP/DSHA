"""Read actual ELF and native member bytes; no execution or device claims."""
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BXROOT_NAMES = ('libbxroot.so', 'libbxroot-runtime.so', 'libbxroot-ulx.so')
COMMON_RECOVERY_NAMES = (*BXROOT_NAMES, 'libdsha-session.so', 'libtermux.so',
                         'libtalloc.so', 'libandroidshmem.so')
PROOT_NAMES = {'standard': ('libproot.so', 'libprootloader.so'),
               'low': ('libproot_legacy.so', 'libprootloader_legacy.so')}
PRESERVED_PROOT_PATHS = {
    'app/src/main/jniLibs/arm64-v8a/'+name for name in
    ('libproot.so','libprootloader.so','libprootloader32.so','libtalloc.so','libandroidshmem.so')
} | {'app/src/low/jniLibs/arm64-v8a/'+name for name in PROOT_NAMES['low']}


def digest(content):
    return hashlib.sha256(content).hexdigest()


def elf_info(content):
    if len(content) < 64 or content[:7] != b'\x7fELF\x02\x01\x01':
        raise ValueError('NATIVE_ELF_HEADER')
    kind, machine = struct.unpack_from('<HH', content, 16)
    entry, phoff = struct.unpack_from('<QQ', content, 24)
    phsize, phcount = struct.unpack_from('<HH', content, 54)
    if kind not in (2, 3) or machine != 183 or not phcount or phcount > 256 \
            or phsize < 56 or phoff + phsize * phcount > len(content):
        raise ValueError('NATIVE_ELF_PROGRAM_HEADERS')
    segments = []
    for index in range(phcount):
        row = struct.unpack_from('<IIQQQQQQ', content, phoff + phsize * index)
        segment = dict(type=row[0], flags=row[1], offset=row[2], address=row[3],
                       fileSize=row[5], memorySize=row[6], align=row[7])
        if segment['offset'] + segment['fileSize'] > len(content):
            raise ValueError('NATIVE_ELF_SEGMENT_BOUNDS')
        segments.append(segment)
    loads = [s for s in segments if s['type'] == 1]
    if not loads or any(s['align'] < 16384 or (s['offset'] - s['address']) % 16384 for s in loads):
        raise ValueError('NATIVE_ELF_16K_ALIGNMENT')
    for page in (4096, 16384):
        intervals = sorted((s['address'] // page * page,
                            (s['address'] + s['memorySize'] + page - 1) // page * page) for s in loads)
        for segment in (s for s in segments if s['type'] == 0x6474e552):
            cursor = segment['address'] // page * page
            limit = (segment['address'] + segment['memorySize'] + page - 1) // page * page
            for left, right in intervals:
                if left <= cursor < right:
                    cursor = right
            if cursor < limit:
                raise ValueError('NATIVE_ELF_RELRO_OUTSIDE_LOAD')
    interpreters = [s for s in segments if s['type'] == 3]
    if len(interpreters) > 1:
        raise ValueError('NATIVE_ELF_MULTIPLE_INTERPRETERS')
    interp = None
    if interpreters:
        s = interpreters[0]
        raw = content[s['offset']:s['offset'] + s['fileSize']]
        if not raw.endswith(b'\0') or b'\0' in raw[:-1]:
            raise ValueError('NATIVE_ELF_INTERPRETER')
        interp = raw[:-1].decode('ascii')
    dynamic = [s for s in segments if s['type'] == 2]
    if len(dynamic) > 1:
        raise ValueError('NATIVE_ELF_MULTIPLE_DYNAMIC')
    tags = []
    if dynamic:
        s = dynamic[0]
        if s['fileSize'] % 16:
            raise ValueError('NATIVE_ELF_DYNAMIC_SIZE')
        for offset in range(s['offset'], s['offset'] + s['fileSize'], 16):
            tag, value = struct.unpack_from('<qQ', content, offset)
            if tag == 0:
                break
            tags.append((tag, value))
        else:
            raise ValueError('NATIVE_ELF_DYNAMIC_UNTERMINATED')
    needed_offsets = [value for tag, value in tags if tag == 1]
    string_addresses = [value for tag, value in tags if tag == 5]
    string_sizes = [value for tag, value in tags if tag == 10]
    needed, versions = [], []
    if needed_offsets:
        if len(string_addresses) != 1 or len(string_sizes) != 1:
            raise ValueError('NATIVE_ELF_STRING_TABLE')
        address, size = string_addresses[0], string_sizes[0]
        matches = [s for s in loads if s['address'] <= address
                   and address + size <= s['address'] + s['fileSize']]
        if len(matches) != 1 or size > 16 * 1024 * 1024:
            raise ValueError('NATIVE_ELF_STRING_TABLE_BOUNDS')
        s = matches[0]; offset = s['offset'] + address - s['address']
        strings = content[offset:offset + size]
        for name_offset in needed_offsets:
            end = strings.find(b'\0', name_offset)
            if not 0 <= name_offset < size or end < 0:
                raise ValueError('NATIVE_ELF_NEEDED_BOUNDS')
            name = strings[name_offset:end].decode('ascii')
            if '/' in name or not name or name in needed:
                raise ValueError('NATIVE_ELF_NEEDED_NAME')
            needed.append(name)
        versions = sorted({match.decode('ascii') for match in re.findall(rb'GLIBC_[0-9]+(?:\.[0-9]+)+', strings)})
    return dict(machine=machine, type=kind, entry=entry, loadAlign=[s['align'] for s in loads],
                interp=interp, needed=needed, glibcVersions=versions)


def source_native_members(root=ROOT, flavor='standard'):
    if flavor not in PROOT_NAMES:
        raise ValueError('NATIVE_FLAVOR')
    members = {}
    for folder in ('main', 'low') if flavor == 'low' else ('main',):
        for path in sorted((Path(root) / f'app/src/{folder}/jniLibs/arm64-v8a').glob('*.so')):
            if path.is_symlink() or not path.resolve().is_relative_to(Path(root).resolve()):
                raise ValueError('NATIVE_SOURCE_ALIAS')
            if path.name in members:
                raise ValueError('NATIVE_SOURCE_DUPLICATE_NAME')
            members[path.name] = path
    if any(name.startswith('libproroot') for name in members):
        raise ValueError('PROROOT_PRODUCTION_SOURCE_RETAINED')
    if not set(COMMON_RECOVERY_NAMES + PROOT_NAMES[flavor]).issubset(members):
        raise ValueError('NATIVE_SOURCE_CLOSURE_MISSING')
    return members


def dependency_native_members(root=ROOT, flavor='standard'):
    """Verify pinned AAR bytes and return the ARM64 members used by this flavor."""
    if flavor not in PROOT_NAMES:
        raise ValueError('NATIVE_FLAVOR')
    lock=json.loads((Path(root)/'tools/native-dependencies.lock.json').read_text(encoding='utf8'))
    artifacts=lock.get('artifacts',[])
    expected = {
        'org.mozilla.geckoview:geckoview-arm64-v8a:143.0.20251003115653': 'low',
        'androidx.graphics:graphics-path:1.0.1': 'common',
    }
    if lock.get('schema')!=1 or len(artifacts)!=len(expected) \
            or {row.get('coordinate') for row in artifacts}!=set(expected):
        raise ValueError('NATIVE_DEPENDENCY_LOCK')
    build=(Path(root)/'app/build.gradle').read_text(encoding='utf8')
    members={}
    for artifact in artifacts:
        coordinate=artifact['coordinate']
        scope=expected[coordinate]
        if artifact.get('flavor')!=scope \
                or not re.fullmatch('[a-f0-9]{64}',str(artifact.get('aarSha256',''))):
            raise ValueError('NATIVE_DEPENDENCY_COORDINATE')
        declaration=('implementation' if scope=='common' else 'lowImplementation')+" '"+coordinate+"'"
        if declaration not in build:
            raise ValueError('NATIVE_DEPENDENCY_BUILD_DECLARATION')
        if scope not in ('common',flavor):
            continue
        resolved=dependency_artifact_members(artifact)
        if set(members)&set(resolved):
            raise ValueError('NATIVE_DEPENDENCY_DUPLICATE_MEMBER')
        members.update(resolved)
    return members


def dependency_artifact_members(artifact):
    coordinate=artifact['coordinate']
    rows=artifact.get('members',[])
    if not rows or len({row.get('name') for row in rows})!=len(rows):
        raise ValueError('NATIVE_DEPENDENCY_MEMBERS')
    members={}
    for row in rows:
        name=row.get('name','')
        if not re.fullmatch(r'lib[\w.-]+\.so',name) \
                or row.get('aarMember')!='jni/arm64-v8a/'+name \
                or not re.fullmatch('[a-f0-9]{64}',str(row.get('sha256',''))) \
                or type(row.get('size')) is not int or row['size']<=0:
            raise ValueError('NATIVE_DEPENDENCY_MEMBER_PROOF')
        members[name]={**row,'coordinate':coordinate,'aarSha256':artifact['aarSha256']}
    gradle=Path(os.environ.get('GRADLE_USER_HOME') or Path.home()/'.gradle')
    group, name, version = coordinate.split(':')
    cache=gradle/'caches/modules-2/files-2.1'/group/name/version
    archives=list(cache.glob('*/'+name+'-'+version+'.aar'))
    if len(archives)!=1:
        raise ValueError('NATIVE_DEPENDENCY_RESOLVED_AAR_COUNT')
    sha=hashlib.sha256()
    with archives[0].open('rb') as source:
        for block in iter(lambda:source.read(1024*1024),b''):sha.update(block)
    if sha.hexdigest()!=artifact['aarSha256']:
        raise ValueError('NATIVE_DEPENDENCY_RESOLVED_AAR_CHANGED')
    with zipfile.ZipFile(archives[0]) as source:
        names=source.namelist()
        if len(names)!=len(set(names)):
            raise ValueError('NATIVE_DEPENDENCY_AAR_DUPLICATE_MEMBER')
        actual={name for name in names if name.startswith('jni/arm64-v8a/') and not name.endswith('/')}
        if actual!={row['aarMember'] for row in rows}:
            raise ValueError('NATIVE_DEPENDENCY_AAR_CLOSURE')
        for row in rows:
            raw=source.read(row['aarMember'])
            if len(raw)!=row['size'] or digest(raw)!=row['sha256']:
                raise ValueError('NATIVE_DEPENDENCY_AAR_MEMBER_CHANGED')
    return members


def packaging_transforms(root=ROOT):
    lock=json.loads((Path(root)/'tools/native-packaging.lock.json').read_text(encoding='utf8'))
    rows=lock.get('transforms',[])
    if lock.get('schema')!=1 or len(rows)>1:
        raise ValueError('NATIVE_PACKAGING_LOCK')
    for row in rows:
        if row.get('sourcePath')!='app/src/main/jniLibs/arm64-v8a/libprootloader32.so' \
                or row.get('ndkVersion')!='26.3.11579264' or row.get('llvmVersion')!='17.0.2' \
                or row.get('arguments')!=['--strip-unneeded'] \
                or any(not re.fullmatch('[a-f0-9]{64}',str(row.get(key,''))) for key in ('sourceSha256','packagedSha256')) \
                or row.get('reference')!={'versionCode':171,'packagedSha256':row.get('packagedSha256')}:
            raise ValueError('NATIVE_PACKAGING_TRANSFORM')
    return rows


def strip_tool(root, row):
    sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        local=Path(root)/'local.properties'
        values=re.findall(r'^sdk\.dir=(.+)$',local.read_text(encoding='utf8'),re.M) if local.is_file() else []
        if len(values)!=1:
            raise ValueError('NATIVE_STRIP_SDK_UNAVAILABLE')
        sdk=values[0].strip().replace('\\:',':').replace('\\\\','\\')
    ndk=Path(sdk)/'ndk'/row['ndkVersion']
    if ('Pkg.Revision = '+row['ndkVersion']) not in (ndk/'source.properties').read_text(encoding='utf8'):
        raise ValueError('NATIVE_STRIP_NDK_VERSION')
    folder='windows-x86_64' if os.name=='nt' else 'linux-x86_64'
    tool=ndk/'toolchains/llvm/prebuilt'/folder/'bin'/('llvm-strip.exe' if os.name=='nt' else 'llvm-strip')
    version=subprocess.check_output([str(tool),'--version'],text=True,timeout=15)
    if ('LLVM version '+row['llvmVersion']) not in version:
        raise ValueError('NATIVE_STRIP_LLVM_VERSION')
    return tool


def packaged_native_bytes(path, root=ROOT):
    path=Path(path);raw=path.read_bytes()
    relative=path.relative_to(root).as_posix()
    rows=[row for row in packaging_transforms(root) if row['sourcePath']==relative]
    if not rows:
        return raw,None
    row=rows[0]
    if digest(raw)!=row['sourceSha256']:
        raise ValueError('NATIVE_STRIP_SOURCE_CHANGED')
    tool=strip_tool(root,row)
    folder=Path(root)/'app/build/native-proof'
    folder.mkdir(parents=True,exist_ok=True)
    if not folder.resolve().is_relative_to(Path(root).resolve()):
        raise ValueError('NATIVE_STRIP_OUTPUT_ALIAS')
    with tempfile.TemporaryDirectory(prefix='strip-',dir=folder) as temporary:
        output=Path(temporary)/path.name
        subprocess.run([str(tool),*row['arguments'],'-o',str(output),str(path)],check=True,timeout=30,
                       stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        transformed=output.read_bytes()
    if digest(transformed)!=row['packagedSha256']:
        raise ValueError('NATIVE_STRIP_PACKAGED_CHANGED')
    return transformed,row


def recovery_launcher_hashes(root=ROOT):
    result = {}
    for flavor in PROOT_NAMES:
        members = source_native_members(root, flavor)
        result[flavor] = {name:digest(members[name].read_bytes())
                          for name in COMMON_RECOVERY_NAMES + PROOT_NAMES[flavor]}
    return result


def infer_flavor(apk):
    names = set(apk.namelist())
    legacy = ['lib/arm64-v8a/' + name in names for name in PROOT_NAMES['low']]
    if legacy[0] != legacy[1]:
        raise ValueError('NATIVE_FLAVOR_PARTIAL_LEGACY_PAIR')
    return 'low' if all(legacy) else 'standard'


def load_bxroot_lock(root=ROOT):
    lock = json.loads((Path(root) / 'tools/bxroot/lock.json').read_text(encoding='utf8'))
    rows = lock.get('artifacts', [])
    if lock.get('schema') != 1 or len(rows) != 3 or {row.get('name') for row in rows} != set(BXROOT_NAMES):
        raise ValueError('BXROOT_NATIVE_LOCK_MEMBERS')
    policy = lock.get('policy', {})
    expected = dict(buildProfile='default',shmCopyoutVerified=0,shmClaimVerified=0,
                    reentry=False,externalStub=False,strictExec=True)
    if any(type(policy.get(key)) is not type(value) or policy[key] != value for key,value in expected.items()):
        raise ValueError('BXROOT_NATIVE_RELEASE_PROFILE')
    for row in rows:
        if not re.fullmatch('[a-f0-9]{64}',str(row.get('sha256',''))) \
                or not isinstance(row.get('size'),int) or row['size'] <= 0 or not isinstance(row.get('elf'),dict):
            raise ValueError('BXROOT_NATIVE_LOCK_PROOF')
    preserved=lock.get('preservedProot',[])
    if len(preserved)!=len(PRESERVED_PROOT_PATHS) or {row.get('path') for row in preserved}!=PRESERVED_PROOT_PATHS \
            or any(not re.fullmatch('[a-f0-9]{64}',str(row.get('sha256',''))) for row in preserved):
        raise ValueError('BXROOT_NATIVE_FALLBACK_LOCK')
    return lock
