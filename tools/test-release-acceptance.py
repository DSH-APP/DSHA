#!/usr/bin/env python3
import copy
import json
from pathlib import Path
import tempfile
import unittest
from release_acceptance import requirements,validate,baseline_from_receipt,digest,CERT

class AcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.path='app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java'
        self.base={self.path:'a'*64,'app/src/main/java/com/deepseekharness/app/ui/BrowserMicrophone.java':'b'*64}
    def test_changed_upload_requires_real_callbacks_in_both_flavors(self):
        selected=requirements(self.base,{**self.base,self.path:'c'*64})
        for flavor in ('standard','low'):
            self.assertIn('two-file-upload',selected['required'][flavor])
            self.assertNotIn('microphone-origin-and-cancellation',selected['required'][flavor])
    def test_removed_source_is_still_a_change(self):
        selected=requirements(self.base,{self.path:'a'*64})
        self.assertIn('microphone-origin-and-cancellation',selected['required']['low'])
    def test_runtime_snapshot_change_requires_real_process_and_terminal_checks(self):
        path='app/src/main/java/com/deepseekharness/app/runtime/RuntimeHostPorts.java'
        selected=requirements({path:'a'*64},{path:'b'*64})
        for flavor in ('standard','low'):
            self.assertIn('bounded-proroot-diagnostic',selected['required'][flavor])
            self.assertIn('terminal-close-and-web-restart',selected['required'][flavor])
    def test_plugin_install_changes_require_real_auto_activation(self):
        path='app/src/main/java/com/deepseekharness/app/core/PluginRepository.java'
        selected=requirements({path:'a'*64},{path:'b'*64})
        for flavor in ('standard','low'):
            self.assertIn('plugin-install-auto',selected['required'][flavor])
    def test_retained_navigation_changes_require_the_real_history_page(self):
        path='app/src/main/java/com/deepseekharness/app/ui/NativeDataActivity.java'
        selected=requirements({path:'a'*64},{path:'b'*64})
        for flavor in ('standard','low'):
            self.assertIn('retained-history-pagination',selected['required'][flavor])
    def test_recovery_language_inputs_require_real_rotation_check(self):
        for path in ('app/src/main/assets/language-patch.json',
                     'app/src/main/assets/web-integration/language.js',
                     'app/src/main/java/com/deepseekharness/app/ui/RecoveryActivity.java',
                     'app/src/main/java/com/deepseekharness/app/util/RecoveryStatusText.java'):
            selected=requirements({path:'a'*64},{path:'b'*64})
            for flavor in ('standard','low'):
                self.assertIn('recovery-language-rotation',selected['required'][flavor])
    def test_no_change_does_not_invent_new_device_matrix(self):
        selected=requirements(self.base,self.base)
        self.assertEqual([],selected['rules'])
        self.assertNotIn('two-file-upload',selected['required']['standard'])
    def test_system_language_change_requires_cold_override_and_follow_system_evidence(self):
        for path in ('app/src/main/java/com/deepseekharness/app/util/SystemLanguage.java',
                     'app/src/main/java/com/deepseekharness/app/util/AppLocaleDispatch.java',
                     'app/src/main/java/com/deepseekharness/app/util/UiLanguagePreference.java',
                     'app/src/main/java/com/deepseekharness/app/ui/LanguageController.java'):
            selected=requirements({path:'a'*64},{path:'b'*64})
            for flavor in ('standard','low'):
                self.assertIn('native-language-follow-system',selected['required'][flavor])
            proof={'acceptance':selected,'flavors':{f:{'behaviors':{c:{'result':'PASS','evidence':'reviewed exact APK device record'} for c in checks}} for f,checks in selected['required'].items()}}
            validate(proof,selected)
            del proof['flavors']['low']['behaviors']['native-language-follow-system']
            with self.assertRaises(ValueError):validate(proof,selected)
    def test_document_mutation_changes_require_real_file_operations(self):
        for path in ('app/src/main/java/com/deepseekharness/app/DshaDocumentsProvider.java',
                     'app/src/main/java/com/deepseekharness/app/util/DocumentPaths.java',
                     'app/src/main/java/com/deepseekharness/app/backup/UserDataLayout.java',
                     'app/src/main/java/com/deepseekharness/app/backup/AndroidBackupFileSystem.java'):
            selected=requirements({path:'a'*64},{path:'b'*64})
            for flavor in ('standard','low'):
                self.assertIn('document-provider-files',selected['required'][flavor])
    def test_missing_unknown_or_old_contract_proof_rejected(self):
        selection=requirements(self.base,{**self.base,self.path:'c'*64})
        proof={'acceptance':selection,'flavors':{flavor:{'behaviors':{c:{'result':'PASS','evidence':'private reviewed record'} for c in checks}} for flavor,checks in selection['required'].items()}}
        validate(proof,selection)
        for status in ('NOT_TESTED','UNAVAILABLE','FAIL'):
            changed=copy.deepcopy(proof);changed['flavors']['low']['behaviors']['two-file-upload']['result']=status
            with self.assertRaises(ValueError):validate(changed,selection)
        changed=copy.deepcopy(proof);changed['acceptance']['contractSha256']='0'*64
        with self.assertRaises(ValueError):validate(changed,selection)
    def test_no_empty_baseline_shortcut(self):
        with self.assertRaises(ValueError):requirements({},self.base)

    def test_raw_or_changed_snapshot_cannot_be_substituted_for_delivered_baseline(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);raw=root/'sources.json';receipt=root/'manifest.json'
            raw.write_text(json.dumps(self.base),encoding='utf8')
            with self.assertRaises(ValueError):baseline_from_receipt(raw)
            data={'status':'PASS_FOR_EXECUTED_SCOPE','apks':[{'flavor':f,'package':'com.dsh.client','certificateSha256':CERT} for f in ('standard','low')],
                  'sourceSnapshot':{'path':str(raw),'sha256':digest(raw)}}
            receipt.write_text(json.dumps(data),encoding='utf8')
            actual,provenance=baseline_from_receipt(receipt)
            self.assertEqual(self.base,actual);self.assertEqual(digest(receipt),provenance['receiptSha256'])
            raw.write_text(json.dumps({self.path:'c'*64}),encoding='utf8')
            with self.assertRaises(ValueError):baseline_from_receipt(receipt)

    def test_compatibility_behavior_inputs_select_recovery_matrix(self):
        for path in ('app/src/main/assets/web-integration/es-compat.js','app/src/main/assets/web-integration/compat.js',
                     'app/src/main/assets/pdf-compat-patch.json','tools/prepare-recovery-assets.py'):
            with self.subTest(path=path):
                selected=requirements({path:'a'*64},{path:'b'*64})
                self.assertIn('recovery-language-rotation',selected['required']['standard'])
                self.assertIn('recovery-language-rotation',selected['required']['low'])

if __name__=='__main__':unittest.main()
