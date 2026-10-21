#!/usr/bin/env python3
"""Real isolated asset selections/contract traversal; no APK or user files."""
import hashlib,json,tempfile,unittest
from pathlib import Path
from asset_deployment import selected_assets,verify_reader_contracts,load_manifest,ROOT

class Deployment(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.repo=Path(self.temp.name);self.source=self.repo/'assets';self.source.mkdir()
        self.manifest=dict(schema=1,packagedFiles=['known.txt','managed-runtime-inputs.json','runtime-patches.json','builtin-plugins.json','active-patch.json'],packagedTrees=['licenses','builtin-plugins','web-integration'],sourceInputs=['original.tgz'],sourceOnlyTrees=['runtime-python'],generatedFiles=['web-integration/gecko-compat.js'],externalGeneratedFiles=['recovery-runtime.json'],extensionTrees=['web-integration'],dynamicReaders=[],optionalHistoricalReads=[])
        self.put('known.txt','data');self.put('original.tgz','source-only');self.put('runtime-python/private-source','not deployed')
        self.put('licenses/vendor-license.txt','license');self.put('builtin-plugins/fixture/package.json','{}');self.put('builtin-plugins/fixture/lib/index.js','export {}');self.put('builtin-plugins/fixture/assets/font.bin','font')
        self.put('web-integration/live.js','live');self.doc('web-integration/manifest.json',{'content_scripts':[{'js':['live.js','gecko-compat.js']}]})
        self.doc('managed-runtime-inputs.json',{'installs':[{'asset':'known.txt'}],'assetFiles':['known.txt'],'assetTrees':['web-integration']})
        self.doc('builtin-plugins.json',{'plugins':[{'name':'fixture','entrypoint':'lib/index.js'}]})
        self.doc('runtime-patches.json',{'active':[{'asset':'active-patch.json'}],'specialized':[]});self.doc('active-patch.json',{'patches':[]})
    def put(self,name,value):
        p=self.source/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value,encoding='utf-8')
    def doc(self,name,value):self.put(name,json.dumps(value))
    def verify(self):return verify_reader_contracts(self.source,self.manifest,self.repo)
    def test_complete_licenses_builtin_assets_extensions_and_generated_reader_contracts(self):
        selected={p.as_posix() for _,p in selected_assets(self.source,self.manifest)}
        self.assertIn('licenses/vendor-license.txt',selected);self.assertIn('builtin-plugins/fixture/assets/font.bin',selected)
        self.assertNotIn('original.tgz',selected);self.assertNotIn('runtime-python/private-source',selected);self.assertEqual(len(selected),self.verify())
    def test_unknown_top_level_script_cannot_silently_enter_apk(self):
        self.put('old-selftest.py','print(1)')
        with self.assertRaisesRegex(ValueError,'UNREGISTERED'):self.verify()
    def test_dynamic_managed_asset_must_be_deployed(self):
        self.doc('managed-runtime-inputs.json',{'installs':[{'asset':'missing.py'}],'assetFiles':[],'assetTrees':[]})
        with self.assertRaisesRegex(ValueError,'READER_UNDEPLOYED'):self.verify()
    def test_prepend_asset_is_checked_without_java_literal_reference(self):
        self.doc('active-patch.json',{'patches':[{'prependAsset':'missing.cjs'}]})
        with self.assertRaisesRegex(ValueError,'runtime prependAsset'):self.verify()
    def test_extension_script_must_be_present_or_generated(self):
        self.doc('web-integration/manifest.json',{'content_scripts':[{'js':['missing.js']}]})
        with self.assertRaisesRegex(ValueError,'webextension'):self.verify()
    def test_builtin_entrypoint_and_complete_package_are_required(self):
        self.doc('builtin-plugins.json',{'plugins':[{'name':'fixture','entrypoint':'lib/missing.js'}]})
        with self.assertRaisesRegex(ValueError,'builtin entrypoint'):self.verify()
    def test_literal_new_java_asset_read_is_rejected(self):
        p=self.repo/'app/src/main/java/Reader.java';p.parent.mkdir(parents=True);p.write_text('getAssets().open("missing.bin")')
        with self.assertRaisesRegex(ValueError,'Reader.java'):self.verify()
    def test_deployment_manifest_cannot_escape_or_have_duplicates(self):
        file=self.repo/'manifest.json';value=dict(self.manifest);value['packagedFiles']=['../outside'];file.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError,'DEPLOYMENT_PATH'):load_manifest(file)
        value['packagedFiles']=['known.txt','known.txt'];file.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError,'DUPLICATE'):load_manifest(file)
    def test_source_identity_has_explicit_generated_physical_alias(self):
        self.manifest['sourceTransforms']={'original.tgz':'web-integration/gecko-compat.js'}
        self.doc('managed-runtime-inputs.json',{'installs':[],'assetFiles':['original.tgz'],'assetTrees':[]})
        self.verify()
        file=self.repo/'manifest.json';bad=dict(self.manifest);bad['sourceTransforms']={'original.tgz':'undeclared.bin'};file.write_text(json.dumps(bad))
        with self.assertRaisesRegex(ValueError,'TRANSFORM'):load_manifest(file)
    def test_current_project_deployment_and_retirement_boundary(self):
        manifest=load_manifest();verify_reader_contracts(ROOT/'app/src/main/assets',manifest)
        selected={p.as_posix() for _,p in selected_assets(ROOT/'app/src/main/assets',manifest)}
        for name in ['selftest.py','rootfs-confirm-install.sh','pnpm-env-fix.sh','backup-prepare.py','restore-merge.py','startup-checkpoints.py','startup-recovery.py','dsh-deps-heal.sh','fix-stale-bundles.sh','flatten-l2s.py','fs-write-patch.sh','heal-pnpm-shells.py','heal-profile-boot.py','heal-session.sh','heal-sessions.py','migrate-public-data.sh','webserver-auth-patch.sh','webui-degrade-patch.sh','webui-origin-port-patch.sh','webui-polyfill.sh','environment-data.py']:
            self.assertNotIn(name,selected)
            self.assertFalse((ROOT/'app/src/main/assets'/name).exists())
        self.assertIn('bridge-token-compat.cjs',selected);self.assertIn('licenses/GPL-3.0.txt',selected)
    def test_build156_retired_sources_keep_exact_bytes_outside_deployment(self):
        record=json.loads((ROOT/'docs/audits/build156/retired-asset-sources.json').read_text(encoding='utf-8'))
        self.assertEqual(record['schema'],1)
        self.assertEqual(len(record['sources']),13)
        for row in record['sources']:
            saved=ROOT/row['saved']
            self.assertTrue(saved.is_file())
            self.assertFalse(saved.is_symlink())
            self.assertEqual(saved.stat().st_size,row['bytes'])
            self.assertEqual(hashlib.sha256(saved.read_bytes()).hexdigest(),row['sha256'])
            self.assertFalse((ROOT/row['source']).exists())
        environment=json.loads((ROOT/'docs/audits/build156/retired-environment-data-sources.json').read_text(encoding='utf-8'))
        self.assertEqual(len(environment['sources']),2)
        for row in environment['sources']:
            saved=ROOT/row['saved']
            self.assertEqual(saved.stat().st_size,row['bytes'])
            self.assertEqual(hashlib.sha256(saved.read_bytes()).hexdigest(),row['sha256'])
            self.assertFalse((ROOT/row['source']).exists())

if __name__=='__main__':unittest.main()
