#!/usr/bin/env python3
"""以真实 ZIP 夹具验证包内复用、历史包兼容以及映射篡改拒绝。"""
import copy
import hashlib
import io
import importlib.util
import json
import unittest
import zipfile
from pathlib import Path
from recovery_apk_assets import archive_locations, SHARED
from recovery_runtime_overlay import TARGETS, INPUT_ASSETS

spec=importlib.util.spec_from_file_location('verify_recovery_apk',Path(__file__).with_name('verify-recovery-apk.py'))
verifier=importlib.util.module_from_spec(spec);spec.loader.exec_module(verifier)


class RecoveryApkAssetsTests(unittest.TestCase):
    def setUp(self):
        self.content = {'recovery-rootfs.bin': b'pinned ubuntu', 'recovery-dsh-runtime.bin': b'pinned rc2'}
        self.descriptor = dict(id='a' * 64, archives=[dict(asset=name, sha256=hashlib.sha256(body).hexdigest()) for name, body in self.content.items()])
        self.mapping = dict(schema=1, runtimeId=self.descriptor['id'], archives=[dict(**row, source=SHARED[row['asset']]) for row in self.descriptor['archives']])

    def check(self, mapping, blobs=None, require_locations=False):
        memory = io.BytesIO()
        with zipfile.ZipFile(memory, 'w') as apk:
            if mapping is not None:
                apk.writestr('assets/recovery-asset-locations.json', json.dumps(mapping))
            for name, body in (blobs if blobs is not None else {SHARED[k]: v for k, v in self.content.items()}).items():
                apk.writestr('assets/' + name, body)
        with zipfile.ZipFile(memory) as apk:
            return archive_locations(apk, self.descriptor, require_locations=require_locations)

    def test_shared_signed_bytes(self):
        self.assertEqual(self.check(self.mapping), SHARED)

    def test_legacy_dedicated_archive(self):
        self.assertEqual(self.check(None, self.content), {k: k for k in SHARED})

    def test_current_apk_must_include_mapping(self):
        with self.assertRaisesRegex(ValueError, 'RECOVERY_ARCHIVE_SOURCE_MISSING'):
            self.check(None, self.content, require_locations=True)

    def test_main_upgrade_keeps_old_emergency_archive(self):
        mapping = copy.deepcopy(self.mapping)
        mapping['archives'][1]['source'] = 'recovery-dsh-runtime.bin'
        blobs = {'offline-rootfs.bin': self.content['recovery-rootfs.bin'], 'dsh-runtime.bin': b'future rc3', 'recovery-dsh-runtime.bin': self.content['recovery-dsh-runtime.bin']}
        self.assertEqual(self.check(mapping, blobs)['recovery-dsh-runtime.bin'], 'recovery-dsh-runtime.bin')

    def test_changed_shared_content_is_not_an_emergency_upgrade(self):
        blobs = {SHARED[k]: v for k, v in self.content.items()}
        blobs['dsh-runtime.bin'] = b'future rc3'
        with self.assertRaisesRegex(ValueError, 'RECOVERY_ARCHIVE_HASH'): self.check(self.mapping, blobs)

    def test_runtime_identity_and_missing_duplicate_rows(self):
        for change in ('identity', 'missing', 'duplicate'):
            mapping = copy.deepcopy(self.mapping)
            if change == 'identity': mapping['runtimeId'] = 'b' * 64
            if change == 'missing': mapping['archives'].pop()
            if change == 'duplicate': mapping['archives'][1] = mapping['archives'][0]
            with self.subTest(change=change), self.assertRaises(ValueError): self.check(mapping)

    def test_paths_wrong_source_hash_and_missing_archive_rejected(self):
        for source in ('../offline-rootfs.bin', '/root/runtime.bin', 'dsh-runtime.bin', 'unknown.bin'):
            mapping = copy.deepcopy(self.mapping); mapping['archives'][0]['source'] = source
            with self.subTest(source=source), self.assertRaises(ValueError): self.check(mapping)
        mapping = copy.deepcopy(self.mapping); mapping['archives'][0]['sha256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'RECOVERY_ARCHIVE_SOURCE_HASH'): self.check(mapping)
        with self.assertRaises(KeyError): self.check(self.mapping, {})

    def test_signed_overlay_bytes_and_final_file_proof_are_required(self):
        payloads={name:('patched '+name).encode() for name in TARGETS}
        rows=[dict(path=path,asset='recovery-overlay/'+name,originalSha256='a'*64,
                   sha256=hashlib.sha256(payloads[name]).hexdigest(),bytes=len(payloads[name]))
              for name,path in TARGETS.items()]
        descriptor=dict(overlays=rows,files=[dict(path=row['path'],sha256=row['sha256'],bytes=row['bytes']) for row in rows])
        def verify(payloads_to_pack,description):
            memory=io.BytesIO()
            with zipfile.ZipFile(memory,'w') as apk:
                for name,body in payloads_to_pack.items():apk.writestr('assets/recovery-overlay/'+name,body)
            with zipfile.ZipFile(memory) as apk:verifier.verify_overlays(apk,description)
        verify(payloads,descriptor)
        changed=dict(payloads);changed['index.html']=b'tampered'
        with self.assertRaisesRegex(RuntimeError,'RECOVERY_OVERLAY_HASH'):verify(changed,descriptor)
        stale=copy.deepcopy(descriptor);stale['files'][0]['sha256']='b'*64
        with self.assertRaisesRegex(RuntimeError,'RECOVERY_OVERLAY_PROOF'):verify(payloads,stale)
        missing=dict(payloads);missing.pop('client.pdf.js')
        with self.assertRaises(KeyError):verify(missing,descriptor)

    def test_overlay_recipe_and_language_asset_hashes_are_bound(self):
        content={name:('source '+name).encode() for name in INPUT_ASSETS}
        descriptor={'overlayInputs':{name:hashlib.sha256(body).hexdigest() for name,body in content.items()}}
        def check(blobs):
            memory=io.BytesIO()
            with zipfile.ZipFile(memory,'w') as apk:
                for name,body in blobs.items():apk.writestr('assets/'+name,body)
            with zipfile.ZipFile(memory) as apk:verifier.verify_overlay_inputs(apk,descriptor)
        check(content)
        changed=dict(content);changed['web-integration/language.js']=b'changed'
        with self.assertRaisesRegex(RuntimeError,'RECOVERY_OVERLAY_INPUT_HASH'):check(changed)


if __name__ == '__main__': unittest.main()
