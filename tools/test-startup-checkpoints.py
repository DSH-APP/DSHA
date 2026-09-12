#!/usr/bin/env python3
"""固定配置快照的轮换、校验、回退和凭据隔离回归。"""
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('checkpoints', ROOT / 'app/src/main/assets/startup-checkpoints.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Checkpoints(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.home = pathlib.Path(self.temporary.name)
        module.HOME = str(self.home)
        module.STORE = str(self.home / 'dsha-startup-checkpoints')
        self.profile = self.home / 'profiles/web'
        self.profile.mkdir(parents=True)
        (self.profile / 'package.json').write_text('{"name":"original"}')
        (self.home / '.credentials.yaml').write_text('never snapshot this')
        (self.home / 'sessions').mkdir()
        (self.home / 'sessions/user.txt').write_text('user conversation')

    def test_three_healthy_slots_and_no_user_data(self):
        for i in range(5):
            with patch.object(module.time, 'time', return_value=1000 + i):
                module.execute({'command': 'healthy'})
        rows = module.listing()['snapshots']
        self.assertEqual(3, len(rows))
        self.assertEqual([1002000, 1003000, 1004000], sorted(row['created'] for row in rows))
        for row in rows:
            self.assertNotIn('credentials', json.dumps(module.load(row['slot'])))
            self.assertNotIn('user conversation', json.dumps(module.load(row['slot'])))

    def test_restore_exact_selected_snapshot_and_absence(self):
        saved = module.execute({'command': 'healthy'})['snapshot']
        (self.profile / 'package.json').write_text('{"name":"changed"}')
        (self.home / 'settings.yaml').write_text('changed: true')
        module.execute(dict(command='restore', slot=saved['slot'], id=saved['id']))
        self.assertEqual('{"name":"original"}', (self.profile / 'package.json').read_text())
        self.assertFalse((self.home / 'settings.yaml').exists())
        self.assertEqual('never snapshot this', (self.home / '.credentials.yaml').read_text())
        self.assertTrue(module.execute({'command': 'healthy'})['skipped'])
        self.assertEqual(saved['id'], module.load(saved['slot'])['id'])

    def test_stale_id_and_corrupt_bytes_are_rejected(self):
        saved = module.execute({'command': 'healthy'})['snapshot']
        with self.assertRaisesRegex(ValueError, 'RECOVERY_CHANGED'):
            module.execute(dict(command='restore', slot=saved['slot'], id='stale'))
        doc = module.load(saved['slot'])
        doc['files']['profiles/web/package.json']['sha256'] = '0' * 64
        module.write_json(str(pathlib.Path(module.STORE) / (saved['slot'] + '.json')), doc)
        with self.assertRaisesRegex(ValueError, 'RECOVERY_CHECKSUM'):
            module.execute(dict(command='restore', slot=saved['slot'], id=saved['id']))
        self.assertEqual('{"name":"original"}', (self.profile / 'package.json').read_text())

    def test_crash_journal_rolls_back_before_next_write(self):
        before = module.capture()
        module.write_json(str(pathlib.Path(module.STORE) / 'pending.json'), before)
        (self.profile / 'package.json').write_text('partial write')
        with self.assertRaisesRegex(ValueError, 'RECOVERY_PENDING'):
            module.execute(dict(command='new', target='settings.yaml'))
        module.execute(dict(command='recover'))
        self.assertEqual('{"name":"original"}', (self.profile / 'package.json').read_text())
        self.assertFalse(module.listing()['pending'])

    def test_new_settings_leaves_profile_and_stores_original(self):
        (self.home / 'settings.yaml').write_text('broken: [')
        module.execute(dict(command='new', target='settings.yaml'))
        self.assertEqual('{}\n', (self.home / 'settings.yaml').read_text())
        row = module.listing()['snapshots'][0]
        self.assertEqual(b'broken: [', module.unpack(module.load(row['slot'])['files']['settings.yaml']))
        with self.assertRaisesRegex(ValueError, 'RECOVERY_FORMAT'):
            module.execute(dict(command='new', target='../.credentials.yaml'))

    def test_failure_during_write_restores_all_original_files(self):
        original = module.atomic
        failed = [False]
        def failing(path, data):
            if str(path).endswith('cordis.patch.yml') and not failed[0]:
                failed[0] = True
                raise IOError('simulated disk failure')
            return original(path, data)
        with patch.object(module, 'atomic', side_effect=failing):
            with self.assertRaisesRegex(IOError, 'simulated'):
                module.execute(dict(command='new', target='web'))
        self.assertEqual('{"name":"original"}', (self.profile / 'package.json').read_text())
        self.assertFalse(module.listing()['pending'])

    def test_healthy_checkpoint_must_match_the_configuration_that_booted(self):
        module.execute(dict(command='prepare', startupId='first'))
        (self.profile / 'package.json').write_text('{"name":"edited-during-startup"}')
        with self.assertRaisesRegex(ValueError, 'RECOVERY_CHANGED'):
            module.execute(dict(command='healthy', startupId='first'))
        self.assertEqual([], module.listing()['snapshots'])
        module.execute(dict(command='prepare', startupId='second'))
        with self.assertRaisesRegex(ValueError, 'RECOVERY_CHANGED'):
            module.execute(dict(command='healthy', startupId='first'))
        self.assertIn('snapshot', module.execute(dict(command='healthy', startupId='second')))


if __name__ == '__main__':
    unittest.main()
