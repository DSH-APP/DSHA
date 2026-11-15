#!/usr/bin/env python3
"""Check that local delivery cannot reuse a different APK's device result."""

import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


spec=importlib.util.spec_from_file_location('verify_stability',Path(__file__).with_name('verify-stability.py'))
gate=importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ReleaseEvidenceTest(unittest.TestCase):
    def test_public_source_snapshot_excludes_private_history(self):
        for name in ('tools/history/root-build154/old.py',
                     'docs/history/unredacted.md',
                     'tools/history/private-device-audits/old.py'):
            self.assertFalse(gate.public_source_path(name))
        self.assertTrue(gate.public_source_path('docs/audits/build154/POLICY.md'))

    def setUp(self):
        self.apks=[{'flavor':'standard','versionCode':147,'sha256':'a'*64}]
        checks=['web-ready','existing-data-preserved','plugins-visible','recovery-ready','no-crash']
        self.proof={'schema':1,'package':'io.github.sunsetrne.dsha','certificateSha256':gate.CERT,
                    'versionCode':147,'serial':'test-device','firstInstallTimePreserved':True,
                    'nonDestructive':True,'flavors':{
                        'standard':{'sha256':'a'*64,'result':'PASS','checks':checks}}}

    def test_current_standard_evidence_is_accepted(self):
        gate.validate_device_evidence(self.proof,self.apks)

    def test_standard_evidence_is_bound_to_the_selected_apk(self):
        proof=copy.deepcopy(self.proof)
        gate.validate_device_evidence(proof,self.apks)
        proof['flavors']['standard']['sha256']='c'*64
        with self.assertRaises(ValueError):gate.validate_device_evidence(proof,self.apks)

    def test_legacy_low_apk_or_evidence_is_rejected(self):
        apks=self.apks+[{'flavor':'low','versionCode':147,'sha256':'b'*64}]
        with self.assertRaisesRegex(ValueError,'DEVICE_EVIDENCE_FLAVORS'):
            gate.validate_device_evidence(self.proof,apks)
        proof=copy.deepcopy(self.proof)
        proof['flavors']['low']={'sha256':'b'*64,'result':'PASS','checks':self.proof['flavors']['standard']['checks']}
        with self.assertRaisesRegex(ValueError,'DEVICE_EVIDENCE_FLAVORS'):
            gate.validate_device_evidence(proof,apks)

    def test_mismatched_or_missing_apk_is_rejected(self):
        changed=copy.deepcopy(self.proof)
        changed['flavors']['standard']['sha256']='c'*64
        with self.assertRaises(ValueError):gate.validate_device_evidence(changed,self.apks)
        changed=copy.deepcopy(self.proof)
        del changed['flavors']['standard']
        with self.assertRaises(ValueError):gate.validate_device_evidence(changed,self.apks)

    def test_identity_or_behavior_gaps_are_rejected(self):
        for field,value in [('certificateSha256','0'*64),('versionCode',146),
                            ('firstInstallTimePreserved',False),('nonDestructive',False)]:
            changed=copy.deepcopy(self.proof);changed[field]=value
            with self.assertRaises(ValueError):gate.validate_device_evidence(changed,self.apks)
        changed=copy.deepcopy(self.proof)
        changed['flavors']['standard']['checks'].remove('existing-data-preserved')
        with self.assertRaises(ValueError):gate.validate_device_evidence(changed,self.apks)

    def test_delivery_receipt_requires_same_sources_and_untouched_pass_logs(self):
        (gate.ROOT / 'app/build').mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='delivery-receipt-',dir=gate.ROOT/'app/build') as folder:
            root=Path(folder);source=root/'sources.json';log=root/'check.log'
            current={'app/src/main/java/Example.java':'a'*64}
            source.write_text(json.dumps(current),encoding='utf8');log.write_text('passed\n',encoding='utf8')
            names=['gradle-verification','runtime-input-contract','release-acceptance-contract','apk-assets',
                   'standard-elf','standard-signature','plugin-upgrade-gate',
                   'recovery-apk','recovery-browser-overlay','recovery-profile-boot','mobile-modal','adb-flow','adb-vscreen-bridge','web-ui-host-fixtures','plugin-downloads',
                   'third-party-notices','third-party-apk']
            receipt={'verificationSchema':2,'status':'PASS_WITH_EXPLICIT_DEVICE_GAPS',
                     'sourceSnapshot':{'path':str(source),'sha256':gate.digest(source)},
                     'commands':[{'name':name,'log':str(log),'sha256':gate.digest(log),'exitCode':0} for name in names],
                     'junit':{'Standard':{'tests':1,'failures':0,'errors':0}}}
            gate.validate_software_receipt(receipt,current)
            with self.assertRaises(ValueError):gate.validate_software_receipt(receipt,{**current,'new.java':'b'*64})
            incomplete=copy.deepcopy(receipt);incomplete['commands'].pop()
            with self.assertRaises(ValueError):gate.validate_software_receipt(incomplete,current)
            log.write_text('changed result\n',encoding='utf8')
            with self.assertRaises(ValueError):gate.validate_software_receipt(receipt,current)


if __name__=='__main__':unittest.main()
