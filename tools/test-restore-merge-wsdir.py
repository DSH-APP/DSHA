#!/usr/bin/env python3
"""Historical restore-merge workspace behavior in owned temporary directories.

The retained module is no longer deployed. These tests do not establish the
current NativeBackup restore filesystem/security boundary.
"""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

SOURCE=Path(__file__).resolve().parent/'history/engineering/assets/restore-merge.py'
SPEC=importlib.util.spec_from_file_location('legacy_restore_merge_workspaces',SOURCE)
legacy=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(legacy)

class HistoricalWorkspace(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='dsha-legacy-ws-')
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);legacy.report.clear()
    def record(self,target):
        store=self.root/'.dsh/storages';store.mkdir(parents=True)
        (store/'workspace.json').write_text(json.dumps({'unit':{'name':'workspace','version':2},
            'global':{'initialized':True,'workspaceIds':['w1'],'archivedSessionIds':[]},
            'tables':{'workspaces':{'w1':{'path':str(target),'title':'fixture','sessionIds':['s1']}}}}),encoding='utf-8')
    @unittest.skipIf(os.name=='nt','Retained guest function accepts only POSIX absolute paths; native Windows paths are not guest-root evidence')
    def test_owned_absolute_workspace_is_created(self):
        target=self.root/'owned-workspace';self.record(target)
        self.assertTrue(legacy.ensure_workspace_dirs(str(self.root)))
        self.assertTrue(target.is_dir())
        self.assertTrue(any('补建工作区目录' in row for row in legacy.report))
    @unittest.skipIf(os.name=='nt','Retained guest function accepts only POSIX absolute paths')
    def test_existing_owned_workspace_remains(self):
        target=self.root/'owned-existing';target.mkdir();(target/'sentinel').write_text('keep')
        self.record(target)
        self.assertTrue(legacy.ensure_workspace_dirs(str(self.root)))
        self.assertEqual((target/'sentinel').read_text(),'keep')
    def test_missing_registry_returns_false_without_making_workspace(self):
        self.assertFalse(legacy.ensure_workspace_dirs(str(self.root)))
        self.assertEqual(list(self.root.iterdir()),[])
    def test_relative_workspace_is_not_created_in_isolated_cwd(self):
        self.record('relative/workspace');previous=Path.cwd()
        try:
            os.chdir(self.root)
            self.assertFalse(legacy.ensure_workspace_dirs(str(self.root)))
            self.assertFalse((self.root/'relative').exists())
        finally:os.chdir(previous)

if __name__=='__main__':unittest.main()
