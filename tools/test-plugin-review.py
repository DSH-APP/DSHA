#!/usr/bin/env python3
"""真实文件验证静态审阅、审批绑定、旧安装及加载失败后的安全激活状态。"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import tarfile
import unittest
import uuid
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]


class ReviewTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='dsha-review-');self.root=Path(self.temporary.name)
        self.environment=patch.dict(os.environ,{'DSHA_TEST_ROOT':str(self.root),'DSH_HOME':'/root/.dsh','DSHA_PLUGIN_TASK':'1'*32});self.environment.start()
        spec=importlib.util.spec_from_file_location('plugin_review_fixture',ROOT/'app/src/main/assets/plugin-manager.py')
        self.manager=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.manager);self.life=self.manager.lifecycle()
        self.package=self.root/'root/.dsh/profiles/web/node_modules/test-plugin';self.package.mkdir(parents=True)
        self.put(self.package/'package.json',{'name':'test-plugin','version':'1.0.0','dsh':{'bundle':{'patch':'cordis.patch.yml'}}})
        self.put(self.package/'cordis.patch.yml','[]\n');self.put(self.package/'index.js',"throw Error('metadata inspection must never execute this');")
        self.manifest=self.package.parents[1]/'package.json'
        self.put(self.manifest,{'dependencies':{'test-plugin':'^1.0.0'},'dsh':{'profile':{'bundles':[]}}})

    def tearDown(self):
        self.environment.stop();self.temporary.cleanup()

    def put(self,path,value):
        path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value) if isinstance(value,dict) else value,encoding='utf8')

    def preview(self):
        with patch.object(self.manager,'result') as result:
            self.life.review_existing('test-plugin')
            return result.call_args.kwargs['preview']

    def approve(self,preview):
        self.put(self.root/('root/.dsha-plugin-task-'+'1'*32+'.approval'),preview['confirmationSha256'])

    def enabled(self):
        return 'test-plugin' in json.loads(self.manifest.read_text())['dsh']['profile']['bundles']

    def enable(self):
        preview=self.preview();self.approve(preview)
        with contextlib.redirect_stdout(io.StringIO()):self.life.install_preview(preview['previewId'],preview['confirmationSha256'])
        self.assertTrue(self.enabled());return preview

    def test_unreviewed_entry_is_rejected_and_native_confirmation_is_single_use(self):
        preview=self.preview();self.assertEqual('legacy-unknown',preview['items'][0]['dependencyState'])
        self.assertFalse(self.enabled())
        with self.assertRaisesRegex(ValueError,'原生插件界面'):
            self.life.install_preview(preview['previewId'],preview['confirmationSha256'])
        with contextlib.redirect_stdout(io.StringIO()):self.assertEqual(1,self.manager.builtin.enable_plugin('test-plugin'))
        self.approve(preview)
        with contextlib.redirect_stdout(io.StringIO()):self.life.install_preview(preview['previewId'],preview['confirmationSha256'])
        self.assertTrue(self.enabled());self.assertFalse(Path(self.manager.task_file('.approval')).exists())
        self.assertEqual('queued',self.life.activation_state()['entries']['test-plugin']['status'])

    def test_changed_source_after_review_does_not_enable(self):
        preview=self.preview();self.approve(preview);self.put(self.package/'index.js','new source after review')
        with self.assertRaisesRegex(ValueError,'审阅后发生变化'):
            self.life.install_preview(preview['previewId'],preview['confirmationSha256'])
        self.assertFalse(self.enabled());self.assertEqual('new source after review',(self.package/'index.js').read_text())

    def test_loading_failure_and_process_restart_do_not_repeat_candidate(self):
        self.enable();first=str(uuid.uuid4())
        with contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('begin',first);self.life.loading('failed',first)
            self.assertFalse(self.enabled());self.life.loading('begin',str(uuid.uuid4()));self.assertFalse(self.enabled())
        self.assertEqual('failed',self.life.activation_state()['entries']['test-plugin']['status'])
        self.enable()
        with contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('begin',str(uuid.uuid4()));self.life.loading('begin',str(uuid.uuid4()))
        self.assertFalse(self.enabled());self.assertEqual('unconfirmed',self.life.activation_state()['entries']['test-plugin']['status'])

    def test_same_startup_compatibility_retry_retains_approval_but_rechecks_content(self):
        self.enable();startup=str(uuid.uuid4())
        with contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('begin',startup);self.life.loading('begin',startup)
        self.assertTrue(self.enabled());self.assertEqual('attempted',self.life.activation_state()['entries']['test-plugin']['status'])
        self.put(self.package/'index.js','changed before compatibility retry')
        with contextlib.redirect_stdout(io.StringIO()):self.life.loading('begin',startup)
        self.assertFalse(self.enabled());self.assertEqual('changed',self.life.activation_state()['entries']['test-plugin']['status'])

    def test_confirmed_health_event_preserves_activation(self):
        self.enable();startup=str(uuid.uuid4())
        with contextlib.redirect_stdout(io.StringIO()):
            self.life.loading('begin',startup);self.life.loading('complete',startup);self.life.loading('begin',str(uuid.uuid4()))
        self.assertTrue(self.enabled());self.assertEqual('loaded',self.life.activation_state()['entries']['test-plugin']['status'])

    def test_safe_mode_restores_only_unchanged_previously_enabled_source(self):
        self.enable()
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,self.life.safe_mode('on'));self.assertFalse(self.enabled());self.assertEqual(0,self.life.safe_mode('off'));self.assertTrue(self.enabled())
            self.life.safe_mode('on');self.put(self.package/'index.js','edited during safe mode');self.assertEqual(1,self.life.safe_mode('off'))
        self.assertFalse(self.enabled());self.assertEqual('edited during safe mode',(self.package/'index.js').read_text())

    def test_pending_previews_survive_new_manager_and_corrupt_record_is_retained(self):
        preview=self.preview();pending,unreadable=self.life.pending_previews();self.assertEqual(1,len(pending));self.assertEqual(0,unreadable)
        marker=self.root/'root/.dsh/plugin-previews'/uuid.uuid4().hex/'preview.json';self.put(marker,'{broken')
        pending,unreadable=self.life.pending_previews();self.assertEqual(1,len(pending));self.assertEqual(1,unreadable);self.assertTrue(marker.exists())

    def restored(self,name):
        group=str(uuid.uuid4());node='package-'+'a'*20
        path=self.root/'root/dsha-native-plugin-reviews'/group/'packages'/node
        self.put(path/'package.json',{'name':name,'version':'2.0.0','dsh':{'bundle':{'patch':'cordis.patch.yml'}}})
        self.put(path/'cordis.patch.yml','[]\n');self.put(path/'index.js','owned restored source')
        with patch.object(self.manager,'result') as result:
            self.life.review_restored(group,node);preview=result.call_args.kwargs['preview']
        return path,preview

    def test_restored_name_conflict_keeps_current_installation_untouched(self):
        source,preview=self.restored('test-plugin');self.assertTrue(preview['items'][0]['existingConflict']);self.approve(preview)
        with self.assertRaisesRegex(ValueError,'同名插件'):
            self.life.install_preview(preview['previewId'],preview['confirmationSha256'])
        self.assertEqual('1.0.0',json.loads((self.package/'package.json').read_text())['version']);self.assertTrue((source/'index.js').is_file())

    def test_plugin_export_excludes_credentials_but_keeps_native_lock_and_originals(self):
        self.put(self.package/'.npmrc','//registry.invalid/:_authToken=owned-secret')
        self.put(self.package/'.env','PRIVATE_API_KEY=owned-secret')
        self.put(self.package/'pnpm-lock.yaml',"lockfileVersion: '9.0'\nimporters: {}\n")
        self.put(self.package/'.dsha-dependencies.json',{'oldSnapshot':True})
        with patch.object(self.manager,'result') as result:
            self.manager.cmd_export('["test-plugin"]','/root/export.tgz')
            self.assertTrue(result.call_args.kwargs['excluded'])
        with tarfile.open(self.root/'root/export.tgz','r:gz') as archive:
            names=archive.getnames();self.assertTrue(any(name.endswith('/pnpm-lock.yaml') for name in names))
            self.assertFalse(any(name.endswith(('/.npmrc','/.env','/.dsha-dependencies.json')) for name in names))
        self.assertIn('owned-secret',(self.package/'.npmrc').read_text());self.assertTrue((self.package/'.env').exists())

    @unittest.skipIf(os.name=='nt','恢复插件真实软链启用在 Android/Linux 验证')
    def test_restored_new_name_activates_from_preserved_dependency_group(self):
        source,preview=self.restored('restored-fixture');self.approve(preview)
        with contextlib.redirect_stdout(io.StringIO()):self.life.install_preview(preview['previewId'],preview['confirmationSha256'])
        manifest=json.loads(self.manifest.read_text());self.assertIn('restored-fixture',manifest['dsh']['profile']['bundles'])
        self.assertEqual(source.resolve(),(self.manifest.parent/'node_modules/restored-fixture').resolve())
        self.assertEqual('queued',self.life.activation_state()['entries']['restored-fixture']['status'])
        self.assertTrue((source/'index.js').is_file());self.assertFalse(self.manager.transactions().pending())


if __name__=='__main__':unittest.main(verbosity=2)
