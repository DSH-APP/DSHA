import importlib.util, json, tempfile, unittest, os
from pathlib import Path
from unittest.mock import patch

SCRIPT=Path(__file__).resolve().parents[1]/'app/src/main/assets/rc1-migration.py'
spec=importlib.util.spec_from_file_location('rc1_migration',SCRIPT); mod=importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)

class Migration(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='dsha-rc1-');self.root=Path(self.temp.name);self.dsh=self.root/'.dsh';self.dsh.mkdir();self.state=self.root/'host-state'
        (self.dsh/'settings.yaml').write_text('llm: {model: legacy}\n',encoding='utf8')
        (self.dsh/'sessions').mkdir();(self.dsh/'sessions/session.v3.jsonl').write_text('{"version":3}\n',encoding='utf8')
    def tearDown(self):self.temp.cleanup()
    def prepare(self):return mod.prepare(str(self.root),str(self.state),'boot')
    def finalize(self):return mod.finalize(str(self.root),str(self.state),'boot')
    def folder(self):return self.state/'generations'/json.loads((self.state/'current.json').read_text())['generation']
    def doc(self):return json.loads((self.folder()/'prepare.json').read_text())
    def result(self):return json.loads((self.folder()/'receipt.json').read_text())
    def verified(self):
        doc=self.doc();(self.folder()/'settings-result.json').write_text(json.dumps({'generation':doc['generation'],'status':'verified'}))
    def test_real_pending_sequence_never_commits_without_settings_readback(self):
        self.assertEqual(self.prepare(),0);(self.dsh/'settings.yaml').rename(self.dsh/'settings.yaml.imported')
        self.assertEqual(self.finalize(),0);self.assertEqual(self.result()['status'],'pending')
        for _ in range(3):
            self.assertEqual(self.prepare(),0);self.assertFalse((self.dsh/'settings.yaml').exists());self.finalize();self.assertEqual(self.result()['status'],'pending')
        self.verified();self.finalize();self.assertEqual(self.result()['status'],'committed');self.assertEqual(self.result()['sessionsStatus'],'preserved-awaiting-runtime-open')
    def test_new_input_after_commit_has_new_generation(self):
        self.prepare();first=self.folder();self.verified();self.finalize();(self.dsh/'settings.yaml').write_text('llm: {model: other}\n')
        self.prepare();self.assertNotEqual(first,self.folder());self.assertTrue(first.is_dir())
    def test_restore_epoch_same_bytes_has_new_generation(self):
        self.prepare();first=self.folder();(self.dsh/'.dsha-rc1-restore-generation').write_text('restore-operation-2');self.prepare();self.assertNotEqual(first,self.folder())
    def test_completed_ordinary_prepare_does_not_scan_sessions(self):
        self.prepare();self.verified();self.finalize()
        with patch.object(mod.Resolver,'files',side_effect=AssertionError('ordinary startup must not enumerate')):self.prepare()
    def test_missing_source_and_changed_session_do_not_claim_preserved(self):
        self.prepare();(self.dsh/'settings.yaml').unlink();(self.dsh/'sessions/session.v3.jsonl').write_text('changed')
        self.assertEqual(self.finalize(),1);self.assertFalse(self.result()['sourcePreserved'])
    def test_snapshot_modified_detected_even_source_unchanged(self):
        self.prepare();(self.folder()/self.doc()['sources'][0]['snapshot']).write_text('changed');self.assertEqual(self.finalize(),1)
    def test_source_replaced_with_same_bytes_is_unknown_not_preserved(self):
        self.prepare();source=self.dsh/'settings.yaml';value=source.read_text();source.unlink();source.write_text(value);self.assertEqual(self.finalize(),1);self.assertFalse(self.result()['sourcePreserved'])
    def test_snapshot_io_error_blocks_prepared_and_never_creates_current(self):
        with patch.object(mod,'snapshot_row',side_effect=OSError('disk full')):self.assertEqual(self.prepare(),1)
        self.assertFalse((self.state/'current.json').exists());self.assertTrue((self.dsh/'settings.yaml').is_file())
    def test_file_and_byte_budget_are_errors_not_partial_success(self):
        with patch.object(mod,'MAX_FILES',1):self.assertEqual(self.prepare(),1)
        with patch.object(mod,'MAX_BYTES',1):self.assertEqual(self.prepare(),1)
    def test_legacy_committed_receipt_is_not_authority(self):
        folder=self.dsh/'.dsha-rc1-migration';folder.mkdir();(folder/'receipt.json').write_text('{"version":1,"status":"committed","sourcePreserved":true}')
        self.prepare();self.assertEqual(self.doc()['version'],2);self.assertTrue((self.folder()/'legacy-receipt.json').exists())
    def test_preset_source_and_candidate_remain_independent(self):
        preset=self.dsh/'.agent-presets/crew';preset.mkdir(parents=True);(preset/'agent.cordis.yml').write_text('- id: old\n')
        self.prepare();candidate=self.dsh/self.doc()['presets'][0]['candidate'];self.assertTrue((candidate/'bundle/package.json').is_file());(preset/'agent.cordis.yml').write_text('changed')
        self.assertEqual(json.loads((candidate/'bundle/package.json').read_text())['dependencies']['@deepseek-ai/dsh-agent-preset'],'0.2.0-rc.2')
        self.assertEqual((candidate/'agent.cordis.yml').read_text(),'- id: old\n');self.assertEqual(self.finalize(),1)
    def test_preset_root_metadata_is_snapshotted_without_becoming_directory_candidate(self):
        presets=self.dsh/'.agent-presets';presets.mkdir();(presets/'index.json').write_text('{"selected":"crew"}')
        (presets/'notes').write_text('keep this unknown root file')
        crew=presets/'crew';crew.mkdir();(crew/'agent.cordis.yml').write_text('- id: old\n')
        self.assertEqual(self.prepare(),0)
        doc=self.doc();self.assertEqual(len(doc['presets']),1);self.assertEqual(doc['presets'][0]['id'],'crew')
        rows={row['path']:row for row in doc['sources']}
        for name,value in [('index.json','{"selected":"crew"}'),('notes','keep this unknown root file')]:
            self.assertEqual((self.folder()/rows['.agent-presets/'+name]['snapshot']).read_text(),value)
            self.assertEqual((presets/name).read_text(),value)
            self.assertIn('PRESET_ROOT_FILE_PRESERVED:.agent-presets/'+name,doc['warnings'])
        self.assertEqual(len(list((self.folder()/'snapshots').iterdir())),len(doc['sources']))
    def test_post_snapshot_copy_failure_records_exact_operation_and_path(self):
        crew=self.dsh/'.agent-presets/crew';crew.mkdir(parents=True);(crew/'agent.cordis.yml').write_text('- id: old\n')
        with patch.object(mod.shutil,'copyfile',side_effect=IsADirectoryError(21,'is a directory')):
            self.assertEqual(self.prepare(),1)
        folder=next((self.state/'generations').iterdir());doc=json.loads((folder/'prepare.json').read_text())
        self.assertEqual(doc['failure']['operation'],'preset-candidate-copy')
        self.assertEqual(doc['failure']['path'],'.agent-presets/crew/agent.cordis.yml')
        self.assertIn('IsADirectoryError',doc['failure']['traceback'])
        self.assertFalse(doc['protectionComplete']);self.assertFalse((self.state/'current.json').exists())
        self.assertEqual(len(list((folder/'snapshots').iterdir())),len(doc['sources']))
        self.assertEqual((crew/'agent.cordis.yml').read_text(),'- id: old\n')
    def test_directory_settings_has_typed_source_error(self):
        settings=self.dsh/'settings.yaml';settings.unlink();settings.mkdir();(settings/'keep').write_text('original')
        with self.assertRaises(mod.MigrationSourceError) as raised:self.prepare()
        self.assertEqual(str(raised.exception),'MIGRATION_SOURCE_NOT_REGULAR')
        self.assertEqual(raised.exception.operation,'settings-input')
        self.assertEqual(raised.exception.logical,str(settings))
        self.assertFalse((self.state/'current.json').exists());self.assertEqual((settings/'keep').read_text(),'original')
    def test_directory_never_passes_snapshot_source_guard(self):
        folder=self.root/'candidate';folder.mkdir();directory=self.dsh/'directory';directory.mkdir()
        with self.assertRaisesRegex(mod.MigrationSourceError,'SOURCE_NOT_REGULAR'):
            mod.snapshot_row(folder,self.dsh,directory,directory,[],'preset',0)
        self.assertFalse((folder/'snapshots/0').exists())
    def test_stale_finalize_cannot_commit_new_startup(self):
        self.prepare();self.assertEqual(mod.finalize(str(self.root),str(self.state),'old-boot'),1);self.assertFalse((self.folder()/'receipt.json').exists())
    def link(self,target,link,directory=False):
        try:os.symlink(target,link,target_is_directory=directory)
        except OSError as error:self.skipTest('host cannot create symlink: '+str(error.winerror if hasattr(error,'winerror') else error.errno))
    def test_public_link_copies_bytes_and_rejects_escape(self):
        external=self.root/'approved';external.mkdir();source=external/'settings';source.write_text('llm: {model: linked}\n');(self.dsh/'settings.yaml').unlink();self.link(source,self.dsh/'settings.yaml')
        self.assertEqual(mod.prepare(str(self.root),str(self.state),'boot',[str(external)]),0);row=self.doc()['sources'][0];snapshot=self.folder()/row['snapshot'];self.assertFalse(snapshot.is_symlink());source.write_text('later');self.assertEqual(snapshot.read_text(),'llm: {model: linked}\n')
        with self.assertRaisesRegex(ValueError,'OUTSIDE'):mod.Resolver(self.dsh).resolve(self.dsh/'settings.yaml')
    def test_linked_session_root_is_traversed(self):
        external=self.root/'approved';external.mkdir();(external/'session.v2.jsonl').write_text('old');(self.dsh/'sessions/session.v3.jsonl').unlink();(self.dsh/'sessions').rmdir();self.link(external,self.dsh/'sessions',True)
        mod.prepare(str(self.root),str(self.state),'boot',[str(external)]);self.assertEqual(len(self.doc()['sessions']),1)

if __name__=='__main__':unittest.main()
