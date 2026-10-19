"""Actual ZIP/ELF fixtures for native closure, flavor and pinned-byte rejection."""
import copy
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from native_runtime_proof import (ROOT,BXROOT_NAMES,COMMON_RECOVERY_NAMES,PROOT_NAMES,
                                  PRESERVED_PROOT_PATHS,digest,elf_info,source_native_members)

spec=importlib.util.spec_from_file_location('native_apk_gate',Path(__file__).with_name('verify-native-runtime-apk.py'))
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)
proof_spec=importlib.util.spec_from_file_location('native_projection',Path(__file__).with_name('prepare-bxroot-native-proof.py'))
proof=importlib.util.module_from_spec(proof_spec);proof_spec.loader.exec_module(proof)


def binary(kind=2,needed=()):
    data=bytearray(2048);data[:16]=b'\x7fELF\x02\x01\x01'+bytes(9)
    count=2 if needed else 1
    struct.pack_into('<HHIQQQIHHHHHH',data,16,kind,183,1,0x400080 if kind==2 else 0,64,0,0,64,56,count,0,0,0)
    struct.pack_into('<IIQQQQQQ',data,64,1,5,0,0x400000,0x400000,len(data),len(data),16384)
    if needed:
        strings=b'\0';offsets=[]
        for name in needed:
            offsets.append(len(strings));strings+=name.encode()+b'\0'
        strings+=b'GLIBC_2.17\0'
        data[0x300:0x300+len(strings)]=strings
        tags=[(1,value) for value in offsets]+[(5,0x400300),(10,len(strings)),(0,0)]
        struct.pack_into('<IIQQQQQQ',data,120,2,6,0x200,0x400200,0x400200,len(tags)*16,len(tags)*16,8)
        for index,row in enumerate(tags):struct.pack_into('<qQ',data,0x200+index*16,*row)
    return bytes(data)


class NativeApkGateTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        packaging=self.root/'tools/native-packaging.lock.json';packaging.parent.mkdir(parents=True)
        packaging.write_text(json.dumps(dict(schema=1,transforms=[])),encoding='utf8')
        self.gecko=binary(3,('libc.so',))
        gradle=self.root/'gradle-home'
        archive=gradle/'caches/modules-2/files-2.1/org.mozilla.geckoview/geckoview-arm64-v8a/143.0.20251003115653/fixed/geckoview-arm64-v8a-143.0.20251003115653.aar'
        archive.parent.mkdir(parents=True)
        with zipfile.ZipFile(archive,'w') as aar:aar.writestr('jni/arm64-v8a/libmozglue.so',self.gecko)
        env=patch.dict('os.environ',{'GRADLE_USER_HOME':str(gradle)})
        env.start();self.addCleanup(env.stop)
        self.gecko_archive=archive
        dependency=dict(schema=1,artifacts=[dict(
            coordinate='org.mozilla.geckoview:geckoview-arm64-v8a:143.0.20251003115653',
            flavor='low',aarSha256=digest(archive.read_bytes()),members=[dict(name='libmozglue.so',
                aarMember='jni/arm64-v8a/libmozglue.so',sha256=digest(self.gecko),size=len(self.gecko))])])
        self.graphics=binary(3,('libc.so',))+b'graphics'
        graphics=gradle/'caches/modules-2/files-2.1/androidx.graphics/graphics-path/1.0.1/fixed/graphics-path-1.0.1.aar'
        graphics.parent.mkdir(parents=True)
        with zipfile.ZipFile(graphics,'w') as aar:
            aar.writestr('jni/arm64-v8a/libandroidx.graphics.path.so',self.graphics)
            aar.writestr('jni/x86_64/libandroidx.graphics.path.so',binary(3))
        dependency['artifacts'].append(dict(coordinate='androidx.graphics:graphics-path:1.0.1',
            flavor='common',aarSha256=digest(graphics.read_bytes()),members=[dict(
                name='libandroidx.graphics.path.so',aarMember='jni/arm64-v8a/libandroidx.graphics.path.so',
                sha256=digest(self.graphics),size=len(self.graphics))]))
        self.graphics_archive=graphics
        (self.root/'tools/native-dependencies.lock.json').write_text(json.dumps(dependency),encoding='utf8')
        build=self.root/'app/build.gradle';build.parent.mkdir(parents=True,exist_ok=True)
        build.write_text("implementation 'androidx.graphics:graphics-path:1.0.1'\nlowImplementation 'org.mozilla.geckoview:geckoview-arm64-v8a:143.0.20251003115653'",encoding='utf8')
        names=set(COMMON_RECOVERY_NAMES+PROOT_NAMES['standard']+('libprootloader32.so',))
        for name in names:
            path=self.root/'app/src/main/jniLibs/arm64-v8a'/name;path.parent.mkdir(parents=True,exist_ok=True)
            path.write_bytes(binary(3,('libc.so.6',)) if name=='libbxroot-runtime.so'
                             else binary(3) if name=='libbxroot-ulx.so' else binary())
        for name in PROOT_NAMES['low']:
            path=self.root/'app/src/low/jniLibs/arm64-v8a'/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(binary())
        self.lock=dict(schema=1,source=dict(repo='https://github.com/qiannianhuanxiang/bxroot',commit='a'*40,version='v0.2.4'),
                       policy=dict(buildProfile='default',shmCopyoutVerified=0,shmClaimVerified=0,
                                   reentry=False,externalStub=False,strictExec=True),
                       artifacts=[],preservedProot=[])
        self.refresh_lock()
        script=self.root/'tools/prepare-bxroot-native-proof.py';script.parent.mkdir(parents=True,exist_ok=True)
        script.write_bytes((ROOT/'tools/prepare-bxroot-native-proof.py').read_bytes())

    def refresh_lock(self):
        rows=[]
        for name in BXROOT_NAMES:
            raw=(self.root/'app/src/main/jniLibs/arm64-v8a'/name).read_bytes()
            rows.append(dict(name=name,sha256=digest(raw),size=len(raw),elf=elf_info(raw)))
        self.lock['artifacts']=rows
        self.lock['preservedProot']=[dict(path=path,sha256=digest((self.root/path).read_bytes())) for path in sorted(PRESERVED_PROOT_PATHS)]
        lock=self.root/'tools/bxroot/lock.json';lock.parent.mkdir(parents=True,exist_ok=True)
        lock.write_text(json.dumps(self.lock),encoding='utf8')
        self.projection=self.root/'app/src/main/assets/bxroot-native-lock.json'
        self.projection.parent.mkdir(parents=True,exist_ok=True)
        self.projection.write_text(json.dumps(proof.projection(self.root)),encoding='utf8')

    def apk(self,flavor='standard',omit=None,extra=None,changed=None,projection=None):
        target=self.root/(flavor+'.apk')
        with zipfile.ZipFile(target,'w') as apk:
            for name,path in source_native_members(self.root,flavor).items():
                if name!=omit:apk.writestr('lib/arm64-v8a/'+name,(changed or {}).get(name,path.read_bytes()))
            if omit!='libandroidx.graphics.path.so':
                apk.writestr('lib/arm64-v8a/libandroidx.graphics.path.so',(changed or {}).get('libandroidx.graphics.path.so',self.graphics))
            if flavor=='low' and omit!='libmozglue.so':
                apk.writestr('lib/arm64-v8a/libmozglue.so',(changed or {}).get('libmozglue.so',self.gecko))
            for name,data in (extra or {}).items():apk.writestr(name,data)
            apk.writestr('assets/bxroot-native-lock.json',projection or self.projection.read_bytes())
        return target

    def test_both_actual_flavor_closures_and_metadata_pass(self):
        for flavor in ('standard','low'):
            result=gate.verify(self.apk(flavor),flavor,self.root)
            self.assertEqual(3,len(result['bxrootMembers']))
            self.assertEqual('NOT_RUN_BY_THIS_TOOL',result['device'])

    def test_missing_or_extra_bxroot_dependency_is_rejected(self):
        for args in (dict(omit='libbxroot-ulx.so'),dict(extra={'lib/arm64-v8a/libbxroot-bridge.so':binary()})):
            with self.subTest(args=args),self.assertRaisesRegex(ValueError,'BXROOT_CLOSURE'):
                gate.verify(self.apk(**args),'standard',self.root)

    def test_old_proroot_or_other_abi_cannot_reenter_package(self):
        for name in ('lib/arm64-v8a/libproroot.so','lib/x86_64/libbxroot.so'):
            with self.subTest(name=name),self.assertRaises(ValueError):
                gate.verify(self.apk(extra={name:binary()}),'standard',self.root)

    def test_renamed_engine_stub_and_non_library_members_are_rejected(self):
        for flavor in ('standard','low'):
            for name in ('libenter.so','libstub-loader.so','bx-host','librenamed.so','nested/libenter.so'):
                with self.subTest(flavor=flavor,name=name),self.assertRaisesRegex(ValueError,'MEMBER_CLOSURE'):
                    gate.verify(self.apk(flavor,extra={'lib/arm64-v8a/'+name:binary()}),flavor,self.root)

    def test_gecko_members_are_low_only_complete_and_byte_pinned(self):
        with self.assertRaisesRegex(ValueError,'MEMBER_CLOSURE'):
            gate.verify(self.apk(extra={'lib/arm64-v8a/libmozglue.so':self.gecko}),'standard',self.root)
        with self.assertRaisesRegex(ValueError,'MEMBER_CLOSURE'):
            gate.verify(self.apk('low',omit='libmozglue.so'),'low',self.root)
        with self.assertRaisesRegex(ValueError,'DEPENDENCY_CHANGED'):
            gate.verify(self.apk('low',changed={'libmozglue.so':self.gecko[:-1]+b'\x05'}),'low',self.root)
        (self.root/'app/build.gradle').write_text("lowImplementation 'org.mozilla.geckoview:geckoview-arm64-v8a:144'",encoding='utf8')
        with self.assertRaisesRegex(ValueError,'BUILD_DECLARATION'):
            gate.verify(self.apk('low'),'low',self.root)

    def test_gecko_proof_must_match_the_actual_resolved_aar(self):
        with zipfile.ZipFile(self.gecko_archive,'a') as aar:aar.writestr('jni/arm64-v8a/libenter.so',binary())
        with self.assertRaisesRegex(ValueError,'RESOLVED_AAR_CHANGED'):
            gate.verify(self.apk('low'),'low',self.root)
        lock=self.root/'tools/native-dependencies.lock.json'
        value=json.loads(lock.read_text(encoding='utf8'))
        value['artifacts'][0]['aarSha256']=digest(self.gecko_archive.read_bytes())
        lock.write_text(json.dumps(value),encoding='utf8')
        with self.assertRaisesRegex(ValueError,'AAR_CLOSURE'):
            gate.verify(self.apk('low'),'low',self.root)

    def test_compose_dependency_is_required_and_byte_pinned_in_both_flavors(self):
        for flavor in ('standard','low'):
            with self.subTest(flavor=flavor):
                with self.assertRaisesRegex(ValueError,'MEMBER_CLOSURE'):
                    gate.verify(self.apk(flavor,omit='libandroidx.graphics.path.so'),flavor,self.root)
                with self.assertRaisesRegex(ValueError,'DEPENDENCY_CHANGED'):
                    gate.verify(self.apk(flavor,changed={'libandroidx.graphics.path.so':self.graphics+b'changed'}),flavor,self.root)

    def test_compose_resolved_aar_and_lock_cannot_be_substituted(self):
        original=self.graphics_archive.read_bytes()
        self.graphics_archive.write_bytes(original+b'changed')
        with self.assertRaisesRegex(ValueError,'RESOLVED_AAR_CHANGED'):
            gate.verify(self.apk(),'standard',self.root)
        self.graphics_archive.write_bytes(original)
        lock=self.root/'tools/native-dependencies.lock.json'
        value=json.loads(lock.read_text())
        value['artifacts'].pop()
        lock.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError,'DEPENDENCY_LOCK'):
            gate.verify(self.apk(),'standard',self.root)

    def test_packed_native_bytes_must_equal_source_and_lock(self):
        raw=bytearray(binary());raw[-1]=1
        with self.assertRaisesRegex(ValueError,'SOURCE_CHANGED'):
            gate.verify(self.apk(changed={'libbxroot.so':bytes(raw)}),'standard',self.root)
        self.lock['artifacts'][0]['sha256']='f'*64
        (self.root/'tools/bxroot/lock.json').write_text(json.dumps(self.lock),encoding='utf8')
        with self.assertRaises(ValueError):gate.verify(self.apk(),'standard',self.root)

    def test_flavor_mismatch_and_partial_legacy_pair_are_rejected(self):
        with self.assertRaisesRegex(ValueError,'FLAVOR'):gate.verify(self.apk('low'),'standard',self.root)
        with self.assertRaisesRegex(ValueError,'PARTIAL_LEGACY_PAIR'):
            gate.verify(self.apk('low',omit='libprootloader_legacy.so'),'low',self.root)

    def test_signed_projection_must_bind_the_current_lock(self):
        data=json.loads(self.projection.read_text());data['sourceCommit']='b'*40
        with self.assertRaisesRegex(ValueError,'PROJECTION_BYTES'):
            gate.verify(self.apk(projection=json.dumps(data).encode()),'standard',self.root)
        self.projection.write_text(json.dumps(data),encoding='utf8')
        with self.assertRaisesRegex(ValueError,'PROJECTION_CHANGED'):
            gate.verify(self.apk(),'standard',self.root)

    def test_verified_shm_profile_is_not_a_default_release(self):
        self.lock['policy']['shmCopyoutVerified']=1
        (self.root/'tools/bxroot/lock.json').write_text(json.dumps(self.lock),encoding='utf8')
        with self.assertRaisesRegex(ValueError,'RELEASE_PROFILE'):gate.verify(self.apk(),'standard',self.root)

    def test_bionic_dependency_is_not_a_glibc_runtime(self):
        path=self.root/'app/src/main/jniLibs/arm64-v8a/libbxroot-runtime.so'
        path.write_bytes(binary(3,('libc.so',)));self.refresh_lock()
        with self.assertRaisesRegex(ValueError,'GUEST_GLIBC_CLOSURE'):
            gate.verify(self.apk(),'standard',self.root)

    def test_static_launcher_cannot_gain_dynamic_dependencies(self):
        path=self.root/'app/src/main/jniLibs/arm64-v8a/libbxroot.so'
        path.write_bytes(binary(2,('libc.so.6',)));self.refresh_lock()
        with self.assertRaisesRegex(ValueError,'STATIC_LAUNCHER'):
            gate.verify(self.apk(),'standard',self.root)

    def test_ulx_must_be_static_pie_even_when_locked_bytes_match(self):
        path=self.root/'app/src/main/jniLibs/arm64-v8a/libbxroot-ulx.so'
        for raw in (binary(2), binary(3,('libc.so.6',))):
            with self.subTest(elf=elf_info(raw)):
                path.write_bytes(raw);self.refresh_lock()
                with self.assertRaisesRegex(ValueError,'STATIC_LAUNCHER:libbxroot-ulx.so'):
                    gate.verify(self.apk(),'standard',self.root)

    def test_actual_program_header_alignment_and_bounds_are_checked(self):
        raw=bytearray(binary());struct.pack_into('<Q',raw,64+48,4096)
        with self.assertRaisesRegex(ValueError,'ALIGNMENT'):elf_info(raw)
        raw=bytearray(binary());struct.pack_into('<Q',raw,64+32,65536)
        with self.assertRaisesRegex(ValueError,'BOUNDS'):elf_info(raw)

    def test_legacy_strip_transform_binds_raw_and_packaged_bytes(self):
        source=self.root/'app/src/main/jniLibs/arm64-v8a/libprootloader32.so'
        raw=source.read_bytes();packed=raw[:-1]+b'\x02'
        transform=dict(sourcePath=source.relative_to(self.root).as_posix(),sourceSha256=digest(raw),
                       packagedSha256=digest(packed),ndkVersion='26.3.11579264',llvmVersion='17.0.2',
                       arguments=['--strip-unneeded'],reference=dict(versionCode=171,packagedSha256=digest(packed)))
        (self.root/'tools/native-packaging.lock.json').write_text(json.dumps(dict(schema=1,transforms=[transform])),encoding='utf8')
        def run_strip(command,**kwargs):
            self.assertEqual('--strip-unneeded',command[1])
            Path(command[command.index('-o')+1]).write_bytes(packed)
        with patch('native_runtime_proof.strip_tool',return_value=Path('locked-llvm-strip')), \
                patch('native_runtime_proof.subprocess.run',side_effect=run_strip):
            result=gate.verify(self.apk(changed={'libprootloader32.so':packed}),'standard',self.root)
            self.assertEqual(digest(raw),result['packagingTransforms'][0]['sourceSha256'])
            self.assertEqual(digest(packed),result['packagingTransforms'][0]['packagedSha256'])
            source.write_bytes(raw[:-1]+b'\x03')
            with self.assertRaisesRegex(ValueError,'STRIP_SOURCE_CHANGED'):
                gate.verify(self.apk(),'standard',self.root)

    def test_strip_exception_cannot_be_applied_to_new_bxroot_or_widened(self):
        transform=json.loads((ROOT/'tools/native-packaging.lock.json').read_text())['transforms'][0]
        for changed in ({**transform,'sourcePath':'app/src/main/jniLibs/arm64-v8a/libbxroot.so'},
                        {**transform,'arguments':['--strip-all']}):
            (self.root/'tools/native-packaging.lock.json').write_text(json.dumps(dict(schema=1,transforms=[changed])),encoding='utf8')
            with self.subTest(changed=changed),self.assertRaisesRegex(ValueError,'PACKAGING_TRANSFORM'):
                gate.verify(self.apk(),'standard',self.root)


if __name__=='__main__':unittest.main()
