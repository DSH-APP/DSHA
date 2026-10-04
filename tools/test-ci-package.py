import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('ci_package',Path(__file__).with_name('verify-ci-package.py'))
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)
toolchain_spec=importlib.util.spec_from_file_location('ci_toolchain',Path(__file__).with_name('ci-toolchain.py'))
toolchain=importlib.util.module_from_spec(toolchain_spec);toolchain_spec.loader.exec_module(toolchain)


class ToolchainBinding(unittest.TestCase):
    def test_current_source_matches_then_ndk_or_sdk_drift_is_rejected(self):
        self.assertEqual('17',toolchain.read()['java'])
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for name in ('ci/toolchain.lock.json','app/build.gradle','build.gradle',
                         'gradle/wrapper/gradle-wrapper.properties'):
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True)
                path.write_bytes((toolchain.ROOT/name).read_bytes())
            toolchain.read(root)
            app=root/'app/build.gradle'
            original=app.read_text(encoding='utf-8')
            app.write_text(original.replace("ndkVersion '26.3.11579264'","ndkVersion '27.0.0'"),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'TOOLCHAIN_DRIFT'):toolchain.read(root)
            app.write_text(original.replace('targetSdk 37','targetSdk 36'),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'TARGET_SDK'):toolchain.read(root)

class PackageBinding(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.source=self.root/'source';self.source.mkdir()
        self.reports=self.root/'stability-acceptance'/'018412c5-321b-4710-9bcb-a64fbec61aa1'
        self.reports.mkdir(parents=True)
        file=self.source/'app/build.gradle';file.parent.mkdir();file.write_text('verified source')
        snapshot=self.reports/'source-sha256.json'
        snapshot.write_text(json.dumps({'app/build.gradle':gate.digest(file)}))
        self.commands=[]
        for name in sorted(gate.REQUIRED_CHECKS):
            log=self.reports/(name+'.log');log.write_text(name+' passed\n')
            self.commands.append(dict(name=name,exitCode=0,sha256=gate.digest(log)))
        rows=[]
        for flavor in ('standard','low'):
            apk=self.root/f'app-{flavor}-release-unsigned.apk';apk.write_bytes(flavor.encode())
            rows.append(dict(flavor=flavor,sha256=gate.digest(apk),certificateSha256=None))
        self.receipt=dict(verificationSchema=2,status='PASS_UNSIGNED_PACKAGE',commit='abc',dirty=False,
                          sourceSnapshot=dict(sha256=gate.digest(snapshot)),apks=rows,
                          commands=self.commands,
                          junit={flavor:dict(tests=2,skipped=0,failures=0,errors=0)
                                 for flavor in ('Standard','Low')})
    def validate(self):return gate.validate_receipt(self.receipt,self.root,'abc',self.source,
                                                  {'app/build.gradle'},set())
    def test_exact_pair_is_selected(self):self.assertEqual(2,len(self.validate()))
    def test_actual_uuid_report_layout_is_selected_and_duplicate_receipts_rejected(self):
        manifest=self.reports/'manifest.json';manifest.write_text(json.dumps(self.receipt))
        self.assertEqual(manifest,gate.select_manifest(self.root))
        duplicate=self.root/'stability-acceptance'/'older'/'manifest.json'
        duplicate.parent.mkdir();duplicate.write_text('{}')
        with self.assertRaisesRegex(ValueError,'MANIFEST_COUNT'):gate.select_manifest(self.root)
    def test_apk_tamper_and_failed_gate_are_rejected(self):
        (self.root/'app-low-release-unsigned.apk').write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError,'APK_CHANGED'):self.validate()
        self.receipt['commands'][0]['exitCode']=1
        with self.assertRaisesRegex(ValueError,'FAILED_CHECK'):self.validate()
    def test_source_change_and_commit_mismatch_are_rejected(self):
        (self.source/'app/build.gradle').write_text('new source')
        with self.assertRaisesRegex(ValueError,'SOURCE_CHANGED'):self.validate()
        self.receipt['commit']='other'
        with self.assertRaisesRegex(ValueError,'RECEIPT_IDENTITY'):self.validate()
    def test_missing_named_gate_changed_log_and_unexecuted_tests_are_rejected(self):
        self.receipt['commands'].pop()
        with self.assertRaisesRegex(ValueError,'CHECK_INCOMPLETE'):self.validate()
        self.receipt['commands']=[dict(name=name,exitCode=0,
                                      sha256=gate.digest(self.reports/(name+'.log')))
                                  for name in sorted(gate.REQUIRED_CHECKS)]
        (self.reports/'apk-assets.log').write_text('unverified replacement')
        with self.assertRaisesRegex(ValueError,'CHECK_CHANGED'):self.validate()
        self.receipt['commands']=[dict(name=name,exitCode=0,
                                      sha256=gate.digest(self.reports/(name+'.log')))
                                  for name in sorted(gate.REQUIRED_CHECKS)]
        self.receipt['junit']['Low']['skipped']=2
        with self.assertRaisesRegex(ValueError,'JUNIT_INCOMPLETE'):self.validate()
    def test_omitted_deleted_and_unsafe_source_cannot_be_hidden(self):
        snapshot=self.reports/'source-sha256.json'
        for captured,pattern in [({'missing.java':'a'*64},'SOURCE_INCOMPLETE'),
                                 ({'app/build.gradle':gate.digest(self.source/'app/build.gradle'),
                                   'tools/removed.py':'a'*64},'SOURCE_MISSING'),
                                 ({'app/build.gradle':gate.digest(self.source/'app/build.gradle'),
                                   'C:/outside.txt':'a'*64},'SOURCE_PATH')]:
            snapshot.write_text(json.dumps(captured))
            self.receipt['sourceSnapshot']['sha256']=gate.digest(snapshot)
            with self.subTest(captured=captured),self.assertRaisesRegex(ValueError,pattern):self.validate()
    def test_foreign_failed_or_wrong_workflow_run_is_rejected(self):
        run=dict(repository=dict(full_name='DSH-APP/DSHA'),head_repository=dict(full_name='DSH-APP/DSHA'),
                 head_sha='abc',status='completed',conclusion='success',event='workflow_dispatch',
                 path='.github/workflows/ci-package.yml')
        gate.validate_run(run,'DSH-APP/DSHA','abc')
        for key,value in [('head_sha','other'),('conclusion','failure'),('path','.github/workflows/other.yml'),('event','pull_request')]:
            other=copy.deepcopy(run);other[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):gate.validate_run(other,'DSH-APP/DSHA','abc')

if __name__=='__main__':unittest.main()
